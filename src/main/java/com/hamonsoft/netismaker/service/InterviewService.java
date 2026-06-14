package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.InterviewSummary;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 인터뷰 세션 상태 전이의 단일 진입점. 모든 상태 변경은 여기서 (TaskService 패턴).
 *
 *   create        ─► QUEUED
 *   claim         ─► QUEUED → RUNNING (워커, SKIP LOCKED)
 *   recordQuestion─► RUNNING → AWAITING_INPUT (워커가 질문 emit, 반납)
 *   submitAnswer  ─► AWAITING_INPUT → QUEUED (사용자 답변, 재큐; idempotent)
 *   recordPlan    ─► RUNNING → PLAN_READY (writing-plans 완료)
 *   register      ─► PLAN_READY → REGISTERED (+ Task(COMPLETED)+TaskAnalysis 프리필)
 *   cancel        ─► QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY → CANCELLED
 *   expire        ─► AWAITING_INPUT → EXPIRED (idle TTL)
 *   fail          ─► QUEUED/RUNNING/AWAITING_INPUT → FAILED
 *
 * Task 상태머신(TaskStatus)은 변경하지 않는다.
 */
@Service
@Profile("api")
public class InterviewService {

    private final InterviewSessionRepository sessionRepo;
    private final InterviewTurnRepository turnRepo;
    private final InterviewPlanRepository planRepo;
    private final McpCatalogService mcpCatalogService;
    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskStatusHistoryRepository historyRepo;

    @Value("${app.interview.user-concurrent-limit:3}")
    private int userConcurrentLimit;

    @Value("${app.task.max-retry:3}")
    private int maxRetry;

    public InterviewService(InterviewSessionRepository sessionRepo,
                            InterviewTurnRepository turnRepo,
                            InterviewPlanRepository planRepo,
                            McpCatalogService mcpCatalogService,
                            TaskRepository taskRepo,
                            TaskAnalysisRepository analysisRepo,
                            TaskStatusHistoryRepository historyRepo) {
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.planRepo = planRepo;
        this.mcpCatalogService = mcpCatalogService;
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
    }

    @Transactional
    public InterviewSession create(CreateInterviewRequest req, String requesterId) {
        long active = sessionRepo.countActiveByRequester(requesterId);
        if (active >= userConcurrentLimit) {
            throw TaskException.tooManyRequests(
                    "동시에 진행할 수 있는 인터뷰 한도(" + userConcurrentLimit + ")를 초과했습니다");
        }
        List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
        InterviewSession s = InterviewSession.create(req.githubRepo(), req.githubBranch(),
                req.title(), req.description(), requesterId, extras);
        return sessionRepo.save(s);
    }

    /** 카탈로그 id 리스트 → snapshot 스펙. 비활성/누락 id는 거절. TaskService와 동일 규칙. */
    private List<TaskMcpSpec> resolveMcpExtras(List<Long> catalogIds) {
        if (catalogIds == null || catalogIds.isEmpty()) return new ArrayList<>();
        List<McpCatalogEntry> entries = mcpCatalogService.resolveByIds(catalogIds);
        if (entries.size() != catalogIds.size()) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "존재하지 않는 MCP 카탈로그 id 포함. 요청=" + catalogIds.size()
                            + " 매칭=" + entries.size());
        }
        for (McpCatalogEntry e : entries) {
            if (!e.isEnabled()) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "비활성화된 MCP 카탈로그 항목: " + e.getName());
            }
        }
        List<TaskMcpSpec> out = new ArrayList<>(entries.size());
        for (McpCatalogEntry e : entries) {
            out.add(new TaskMcpSpec(e.getName(), e.getUrl(), e.getTransport()));
        }
        return out;
    }

    /** session_id의 다음 seq. 턴이 없으면 0. */
    private int nextSeq(Long sessionId) {
        Integer max = turnRepo.findMaxSeq(sessionId);
        return max == null ? 0 : max + 1;
    }

    /** assistant/system 턴 (reply_to_seq 없음). */
    private InterviewTurn appendTurn(Long sessionId, String role, String kind, String content) {
        return appendTurn(sessionId, role, kind, content, null);
    }

    /** user answer 턴은 replyToSeq를 함께 기록 (idempotency 키 + UI 스레딩). */
    private InterviewTurn appendTurn(Long sessionId, String role, String kind, String content,
                                     Integer replyToSeq) {
        return turnRepo.save(
                InterviewTurn.of(sessionId, nextSeq(sessionId), role, kind, content, replyToSeq));
    }

    /**
     * QUEUED 1건을 atomic claim → RUNNING. 없으면 Optional.empty.
     * SELECT FOR UPDATE SKIP LOCKED, last_activity_at ASC (TaskRepository.claim 패턴).
     *
     * 첫 claim 시 work_dir를 결정적 경로로 배정/영속화하고,
     * resume claim에서는 저장된 값을 그대로 반환 (Claude Code 세션 스토어가 cwd 종속, 스파이크 02).
     */
    @Transactional
    public Optional<InterviewClaimResponse> claim(String workerId) {
        List<InterviewSession> candidates =
                sessionRepo.findClaimableForUpdateSkipLocked(PageRequest.of(0, 1));
        if (candidates.isEmpty()) return Optional.empty();
        InterviewSession s = candidates.get(0);
        if (s.getStatus() != InterviewStatus.QUEUED) {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "claim 후보가 처리 가능한 상태가 아님: " + s.getStatus());
        }
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId(workerId);
        s.setClaimedAt(OffsetDateTime.now());
        // 첫 claim에만 work_dir 배정. resume이면 기존 값 유지.
        if (s.getWorkDir() == null || s.getWorkDir().isBlank()) {
            s.setWorkDir(deriveWorkDir(s.getGithubRepo(), s.getId()));
        }
        touch(s);
        List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(s.getId());
        return Optional.of(InterviewClaimResponse.of(s, turns));
    }

    /**
     * 결정적 체크아웃 경로: ~/netis-maker/interviews/{owner}/{repo}/session-{id}.
     * 단일 호스트/공유 FS 전제. 같은 세션은 resume마다 항상 같은 경로 → 동일 cwd 보장.
     */
    private String deriveWorkDir(String githubRepo, Long sessionId) {
        String[] parts = githubRepo.split("/", 2);
        String owner = parts.length == 2 ? parts[0] : "_";
        String repo = parts.length == 2 ? parts[1] : githubRepo;
        String home = System.getProperty("user.home");
        return home + "/netis-maker/interviews/" + owner + "/" + repo + "/session-" + sessionId;
    }

    /**
     * 워커가 질문(평문) emit → AWAITING_INPUT, 워커 반납.
     * turn(role=assistant) 저장 + claude_session_id 캡처 + 비용 누적.
     */
    @Transactional
    public InterviewTurn recordQuestion(Long sessionId, String workerId, WorkerQuestionRequest req) {
        InterviewSession s = requireSession(sessionId);
        if (s.getStatus() != InterviewStatus.RUNNING) {
            throw TaskException.conflict("인터뷰중 상태에서만 질문을 보고할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        requireWorker(s, workerId);
        if (req.claudeSessionId() != null && !req.claudeSessionId().isBlank()) {
            s.setClaudeSessionId(req.claudeSessionId());
        }
        addCost(s, req.costUsd());
        String kind = req.kind() == null || req.kind().isBlank() ? "question" : req.kind();
        InterviewTurn turn = appendTurn(sessionId, "assistant", kind,
                req.content() == null ? "" : req.content());
        s.setStatus(InterviewStatus.AWAITING_INPUT);
        s.setWorkerId(null);
        s.setClaimedAt(null);
        s.setCurrentPhase("brainstorming");
        touch(s);
        return turn;
    }

    /**
     * writing-plans 완료 → PLAN_READY. design+plan 영속화, 비용 누적, 워커 반납.
     */
    @Transactional
    public InterviewPlan recordPlan(Long sessionId, String workerId, WorkerPlanRequest req) {
        InterviewSession s = requireSession(sessionId);
        if (s.getStatus() != InterviewStatus.RUNNING) {
            throw TaskException.conflict("인터뷰중 상태에서만 플랜을 보고할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        requireWorker(s, workerId);
        addCost(s, req.costUsd());
        InterviewPlan plan = planRepo.save(InterviewPlan.create(sessionId,
                req.designMarkdown(), req.planMarkdown(), req.planJson(),
                req.durationMs(), s.getTotalCostUsd()));
        appendTurn(sessionId, "assistant", "design",
                req.designMarkdown() == null ? "" : req.designMarkdown());
        s.setStatus(InterviewStatus.PLAN_READY);
        s.setWorkerId(null);
        s.setClaimedAt(null);
        s.setCurrentPhase("writing-plans");
        touch(s);
        return plan;
    }

    /** 워커가 idle heartbeat 시 last_activity 갱신 (회수 오탐 방지용 best-effort). */
    @Transactional
    public void heartbeat(Long sessionId, String workerId) {
        InterviewSession s = requireSession(sessionId);
        requireWorker(s, workerId);
        s.setClaimedAt(OffsetDateTime.now());
        touch(s);
    }

    /** clone/SDK/parse/비용상한 등 오류 → FAILED. terminal 상태에선 거부. */
    @Transactional
    public InterviewSession fail(Long sessionId, String actor, String reason) {
        InterviewSession s = requireSession(sessionId);
        InterviewStatus st = s.getStatus();
        if (st != InterviewStatus.QUEUED && st != InterviewStatus.RUNNING
                && st != InterviewStatus.AWAITING_INPUT) {
            throw TaskException.conflict("진행중 인터뷰만 실패 처리할 수 있습니다 (현재: "
                    + st.dbValue() + ")");
        }
        appendTurn(sessionId, "system", "note", "인터뷰 실패: " + (reason == null ? "원인 미상" : reason));
        s.setStatus(InterviewStatus.FAILED);
        s.setWorkerId(null);
        s.setClaimedAt(null);
        touch(s);
        return s;
    }

    /**
     * 사용자 답변 → AWAITING_INPUT → QUEUED 재큐. idempotent:
     * 같은 replyToSeq에 응답하는 user answer 턴이 이미 있으면 무시 (reply_to_seq 컬럼이 키).
     */
    @Transactional
    public InterviewSession submitAnswer(Long sessionId, String actorId, boolean isAdmin, AnswerRequest req) {
        InterviewSession s = requireSession(sessionId);
        requireOwner(s, actorId, isAdmin);
        // idempotency: 같은 replyToSeq에 대한 user answer 턴이 이미 있으면 중복 → 무시 (상태 무관).
        // status 가드보다 먼저 검사해야 한다 — 1차 답변이 이미 AWAITING_INPUT→QUEUED로 전이시킨 뒤
        // 동일 답변이 재제출되면(클라 재시도/더블서밋) conflict가 아니라 no-op 성공이어야 하기 때문.
        if (req.replyToSeq() != null) {
            boolean already = turnRepo.findBySessionIdOrderBySeqAsc(sessionId).stream()
                    .anyMatch(t -> "user".equals(t.getRole()) && "answer".equals(t.getKind())
                            && req.replyToSeq().equals(t.getReplyToSeq()));
            if (already) return s; // no-op, 상태 유지
        }
        if (s.getStatus() != InterviewStatus.AWAITING_INPUT) {
            throw TaskException.conflict("입력대기 상태에서만 답변할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        appendTurn(sessionId, "user", "answer", req.answer(), req.replyToSeq());
        s.setStatus(InterviewStatus.QUEUED);
        touch(s);
        return s;
    }

    /** 사용자 취소 — QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY에서만. */
    @Transactional
    public InterviewSession cancel(Long sessionId, String actorId, boolean isAdmin) {
        InterviewSession s = requireSession(sessionId);
        requireOwner(s, actorId, isAdmin);
        InterviewStatus st = s.getStatus();
        if (st != InterviewStatus.QUEUED && st != InterviewStatus.RUNNING
                && st != InterviewStatus.AWAITING_INPUT && st != InterviewStatus.PLAN_READY) {
            throw TaskException.conflict("진행중 인터뷰만 취소할 수 있습니다 (현재: " + st.dbValue() + ")");
        }
        appendTurn(sessionId, "system", "note", "사용자 취소");
        s.setStatus(InterviewStatus.CANCELLED);
        s.setWorkerId(null);
        s.setClaimedAt(null);
        touch(s);
        return s;
    }

    /** idle TTL 초과 → EXPIRED. AWAITING_INPUT(사람 미복귀) 한정. */
    @Transactional
    public InterviewSession expire(Long sessionId, String reason) {
        InterviewSession s = requireSession(sessionId);
        if (s.getStatus() != InterviewStatus.AWAITING_INPUT) {
            throw TaskException.conflict("입력대기 상태에서만 만료할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        appendTurn(sessionId, "system", "note", "만료: " + (reason == null ? "idle TTL 초과" : reason));
        s.setStatus(InterviewStatus.EXPIRED);
        touch(s);
        return s;
    }

    /**
     * "작업 등록" — PLAN_READY → REGISTERED. Task(COMPLETED) + TaskAnalysis 프리필 생성.
     * 기존 승인 게이트(COMPLETED→APPROVED)는 유지(거버넌스). task는 인터뷰 완료 후에만 생성.
     * 반환: 생성된 taskId.
     */
    @Transactional
    public Long register(Long sessionId, String actorId, boolean isAdmin) {
        InterviewSession s = requireSession(sessionId);
        requireOwner(s, actorId, isAdmin);
        if (s.getStatus() != InterviewStatus.PLAN_READY) {
            throw TaskException.conflict("플랜완료 상태에서만 작업 등록할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        InterviewPlan plan = planRepo.findById(sessionId)
                .orElseThrow(() -> TaskException.conflict("인터뷰 플랜이 없습니다"));

        // Task(COMPLETED) 생성 — 인터뷰가 분석을 대체. mcps_extra 스냅샷 승계.
        Task t = Task.create(s.getGithubRepo(), s.getGithubBranch(), s.getTitle(),
                s.getDescription(), s.getRequesterId(), maxRetry,
                new ArrayList<>(s.getMcpsExtra() == null ? List.of() : s.getMcpsExtra()));
        t.setStatus(TaskStatus.COMPLETED);
        Task saved = taskRepo.save(t);

        // TaskAnalysis 프리필 (계약 고정):
        //   markdown_result = design_markdown ONLY (합본 X),
        //   subtasks_json   = plan_json,
        //   claude_log      = null,
        //   duration_ms     = plan.durationMs.
        TaskAnalysis a = TaskAnalysis.create(saved.getId(), plan.getDesignMarkdown(),
                plan.getPlanJson(), null, plan.getDurationMs());
        analysisRepo.save(a);

        historyRepo.save(TaskStatusHistory.log(saved.getId(), null, TaskStatus.COMPLETED,
                "system", actorId, "대화형 분석 등록 (interview_session " + sessionId + ")"));

        // 세션 마감.
        s.setTaskId(saved.getId());
        s.setStatus(InterviewStatus.REGISTERED);
        touch(s);
        return saved.getId();
    }

    /** ACL 검증 후 세션 반환 (소유자/관리자만). 스트림 구독 전 권한 체크에도 사용. */
    @Transactional(readOnly = true)
    public InterviewSession getForView(Long id, String viewerId, boolean isAdmin) {
        InterviewSession s = sessionRepo.findActiveById(id).orElseThrow(TaskException::notFound);
        requireOwner(s, viewerId, isAdmin);
        return s;
    }

    /** 상세 뷰 조립 (status + turns + plan). ACL은 getForView가 강제. */
    @Transactional(readOnly = true)
    public InterviewResponse getResponse(Long id, String viewerId, boolean isAdmin) {
        InterviewSession s = getForView(id, viewerId, isAdmin);
        return InterviewResponse.of(s,
                turnRepo.findBySessionIdOrderBySeqAsc(id),
                planRepo.findById(id).orElse(null));
    }

    /** 요청자 본인의 비종료 인터뷰 목록(경량). 새로고침 후 디스커버리/재오픈용. */
    @Transactional(readOnly = true)
    public List<InterviewSummary> listActiveForRequester(String requesterId) {
        return sessionRepo.findActiveByRequester(requesterId).stream()
                .map(InterviewSummary::of)
                .toList();
    }

    private void requireOwner(InterviewSession s, String actorId, boolean isAdmin) {
        if (!s.isOwnedBy(actorId) && !isAdmin) {
            throw TaskException.forbidden();
        }
    }

    private InterviewSession requireSession(Long sessionId) {
        return sessionRepo.findActiveById(sessionId).orElseThrow(TaskException::notFound);
    }

    private void requireWorker(InterviewSession s, String workerId) {
        if (s.getWorkerId() != null && !s.getWorkerId().equals(workerId)) {
            throw TaskException.conflict("다른 워커가 잡은 인터뷰입니다 (소유: " + s.getWorkerId() + ")");
        }
    }

    private void addCost(InterviewSession s, BigDecimal cost) {
        if (cost != null) {
            BigDecimal base = s.getTotalCostUsd() == null ? BigDecimal.ZERO : s.getTotalCostUsd();
            s.setTotalCostUsd(base.add(cost));
        }
    }

    private void touch(InterviewSession s) {
        OffsetDateTime now = OffsetDateTime.now();
        s.setUpdatedAt(now);
        s.setLastActivityAt(now);
    }
}
