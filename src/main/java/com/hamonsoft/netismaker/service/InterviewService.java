package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.InterviewSummaryResponse;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
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
 *   createForTask ─► QUEUED (관리자 승인, TaskService.approve의 유일한 출구)
 *   claim         ─► QUEUED → RUNNING (워커, SKIP LOCKED)
 *   recordQuestion─► RUNNING → AWAITING_INPUT (워커가 질문 emit, 반납)
 *   submitAnswer  ─► AWAITING_INPUT → QUEUED (사용자 답변, 재큐; idempotent)
 *   recordPlan    ─► RUNNING → PLAN_READY (writing-plans 완료)
 *   confirm       ─► PLAN_READY → REGISTERED (기존 task 갱신: analysis 프리필 + 구현/디자인 큐)
 *   cancel        ─► QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY → CANCELLED
 *   expire        ─► AWAITING_INPUT → EXPIRED (idle TTL) | QUEUED → EXPIRED (인터뷰 서비스 미처리 TTL)
 *   fail          ─► QUEUED/RUNNING/AWAITING_INPUT → FAILED
 *
 * task_id가 있는 세션은 각 전이마다 소유 task 상태도 함께 미러링한다:
 *   recordQuestion → INTERVIEW_INPUT, submitAnswer → INTERVIEWING, recordPlan → INTERVIEW_REVIEW,
 *   fail/expire/cancel → AWAITING_APPROVAL.
 *
 * 세션 상태 전이는 행 잠금(FOR UPDATE) 하에서만 — claim(SKIP LOCKED)과 스윕(expire/fail)이
 * 같은 행을 두고 경합하기 때문. 읽기 경로(getForView/getResponse 등)는 잠그지 않는다.
 */
@Service
@Profile("api")
public class InterviewService {

    private final InterviewSessionRepository sessionRepo;
    private final InterviewTurnRepository turnRepo;
    private final InterviewPlanRepository planRepo;
    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final TaskAttachmentRepository attachmentRepo;
    private final AttachmentStorage attachmentStorage;

    public InterviewService(InterviewSessionRepository sessionRepo,
                            InterviewTurnRepository turnRepo,
                            InterviewPlanRepository planRepo,
                            TaskRepository taskRepo,
                            TaskAnalysisRepository analysisRepo,
                            TaskStatusHistoryRepository historyRepo,
                            TaskAttachmentRepository attachmentRepo,
                            AttachmentStorage attachmentStorage) {
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.planRepo = planRepo;
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
        this.attachmentRepo = attachmentRepo;
        this.attachmentStorage = attachmentStorage;
    }

    /**
     * 관리자 승인 → 해당 task의 인터뷰 세션 생성(QUEUED).
     * task 필드를 스냅샷으로 복사한다 — 워커는 세션만 보고 일하므로 계약이 바뀌지 않는다.
     * task 상태 전이는 호출자(TaskService)가 담당한다.
     */
    @Transactional
    public InterviewSession createForTask(Task t, String model, String effort,
                                          List<TaskMcpSpec> extras) {
        InterviewSession s = InterviewSession.create(t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), t.getRequesterId(),
                new ArrayList<>(extras == null ? List.of() : extras), model, effort);
        s.setGitUrl(t.getGitUrl());
        s.setRepoAlias(t.getRepoAlias());
        s.setRepoCatalogId(t.getRepoCatalogId());
        s.setTaskId(t.getId());
        return sessionRepo.save(s);
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
        return Optional.of(InterviewClaimResponse.of(s, turns, attachmentRefsFor(s)));
    }

    /** 세션 소유 task의 첨부 → 절대경로 ref (스펙 §5.4). task 없는 레거시 세션은 []. */
    private List<InterviewClaimResponse.AttachmentRef> attachmentRefsFor(InterviewSession s) {
        if (s.getTaskId() == null) return List.of();
        return attachmentRepo.findByTaskIdOrderByIdAsc(s.getTaskId()).stream()
                .map(a -> new InterviewClaimResponse.AttachmentRef(
                        a.getId(), a.getOriginalFilename(),
                        attachmentStorage.absolutePathOf(a.getStoredPath()),
                        a.getContentType(), a.getSizeBytes()))
                .toList();
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
        InterviewSession s = requireSessionForUpdate(sessionId);
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
        mirrorTask(s, TaskStatus.INTERVIEW_INPUT, workerId, "인터뷰 질문 도착 → 관리자 답변 대기");
        return turn;
    }

    /**
     * writing-plans 완료 → PLAN_READY. design+plan 영속화, 비용 누적, 워커 반납.
     */
    @Transactional
    public InterviewPlan recordPlan(Long sessionId, String workerId, WorkerPlanRequest req) {
        InterviewSession s = requireSessionForUpdate(sessionId);
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
        mirrorTask(s, TaskStatus.INTERVIEW_REVIEW, workerId, "플랜 생성 완료 → 확정 대기");
        return plan;
    }

    /** 워커가 idle heartbeat 시 last_activity 갱신 (회수 오탐 방지용 best-effort). */
    @Transactional
    public void heartbeat(Long sessionId, String workerId) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        requireWorker(s, workerId);
        s.setClaimedAt(OffsetDateTime.now());
        touch(s);
    }

    /** clone/SDK/parse/비용상한 등 오류 → FAILED. terminal 상태에선 거부. */
    @Transactional
    public InterviewSession fail(Long sessionId, String actor, String reason) {
        InterviewSession s = requireSessionForUpdate(sessionId);
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
        mirrorTask(s, TaskStatus.AWAITING_APPROVAL, actor, "인터뷰 실패 → 승인대기 복귀");
        return s;
    }

    /**
     * 사용자 답변 → AWAITING_INPUT → QUEUED 재큐. idempotent:
     * 같은 replyToSeq에 응답하는 user answer 턴이 이미 있으면 무시 (reply_to_seq 컬럼이 키).
     */
    @Transactional
    public InterviewSession submitAnswer(Long sessionId, String actorId, boolean isAdmin, AnswerRequest req) {
        InterviewSession s = requireSessionForUpdate(sessionId);
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
        mirrorTask(s, TaskStatus.INTERVIEWING, actorId, "관리자 답변 → 인터뷰 재개");
        return s;
    }

    /** 사용자 취소 — QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY에서만. */
    @Transactional
    public InterviewSession cancel(Long sessionId, String actorId, boolean isAdmin) {
        InterviewSession s = requireSessionForUpdate(sessionId);
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
        mirrorTask(s, TaskStatus.AWAITING_APPROVAL, actorId, "인터뷰 취소 → 승인대기 복귀");
        return s;
    }

    /**
     * TTL 초과 → EXPIRED. AWAITING_INPUT(사람 미복귀) 또는 QUEUED(인터뷰 서비스 미처리) 한정.
     * QUEUED 만료도 task 미러는 AWAITING_APPROVAL 복귀가 맞다 — 승인 시 task가 INTERVIEWING이 됐으므로.
     */
    @Transactional
    public InterviewSession expire(Long sessionId, String reason) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        if (s.getStatus() != InterviewStatus.AWAITING_INPUT
                && s.getStatus() != InterviewStatus.QUEUED) {
            throw TaskException.conflict("인터뷰대기/입력대기 상태에서만 만료할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        appendTurn(sessionId, "system", "note", "만료: " + (reason == null ? "idle TTL 초과" : reason));
        s.setStatus(InterviewStatus.EXPIRED);
        touch(s);
        mirrorTask(s, TaskStatus.AWAITING_APPROVAL, "system", "인터뷰 만료 → 승인대기 복귀");
        return s;
    }

    /**
     * 플랜 확정 — 세션 PLAN_READY + task 플랜승인대기 → task 구현대기|디자인대기, 세션 REGISTERED.
     *
     * TaskAnalysis 프리필 계약(고정):
     *   markdown_result = design_markdown ONLY (합본 X)
     *   subtasks_json   = plan_json
     *   claude_log      = null
     *   duration_ms     = plan.durationMs
     * 확정이 곧 승인이므로 approved=true로 저장한다 (별도 승인 게이트 없음).
     */
    @Transactional
    public Long confirm(Long sessionId, String adminId, boolean designRequested) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        if (s.getStatus() != InterviewStatus.PLAN_READY) {
            throw TaskException.conflict("플랜완료 상태에서만 확정할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        if (s.getTaskId() == null) {
            throw TaskException.conflict("작업에 연결되지 않은 세션입니다");
        }
        Task t = taskRepo.findActiveById(s.getTaskId()).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.INTERVIEW_REVIEW) {
            throw TaskException.conflict("플랜승인대기 상태에서만 확정할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        InterviewPlan plan = planRepo.findById(sessionId)
                .orElseThrow(() -> TaskException.conflict("인터뷰 플랜이 없습니다"));

        TaskAnalysis a = TaskAnalysis.create(t.getId(), plan.getDesignMarkdown(),
                plan.getPlanJson(), null, plan.getDurationMs());
        a.setApproved(true);
        a.setApprovedBy(adminId);
        a.setApprovedAt(OffsetDateTime.now());
        analysisRepo.save(a);

        t.setDesignRequested(designRequested);
        TaskStatus from = t.getStatus();
        TaskStatus to = designRequested ? TaskStatus.DESIGN_PENDING : TaskStatus.APPROVED;
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", adminId,
                "플랜 확정 → " + to.dbValue() + " (interview_session " + sessionId + ")"));

        s.setStatus(InterviewStatus.REGISTERED);
        touch(s);
        return t.getId();
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

    /** task 상세가 열 세션 — 최신 1건. */
    @Transactional(readOnly = true)
    public Optional<Long> latestSessionIdForTask(Long taskId) {
        return sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(taskId).map(InterviewSession::getId);
    }

    /**
     * task의 전체 세션 이력 (최신순, 경량 — turns/plan 미포함).
     * ACL은 세션이 아니라 task 소유자 기준으로 통일: TaskController.get과 동일 의미론
     * (소프트삭제된 task는 404, 비소유자·비관리자는 403).
     */
    @Transactional(readOnly = true)
    public List<InterviewSummaryResponse> listForTask(Long taskId, String viewerId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!isAdmin && !t.isOwnedBy(viewerId)) {
            throw TaskException.forbidden();
        }
        return sessionRepo.findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .map(InterviewSummaryResponse::of)
                .toList();
    }

    /**
     * task 삭제 시 열린 세션을 닫는다. 워커가 이미 잡고 있어도 다음 보고에서 상태 가드에 걸려
     * 조용히 실패하므로(고아 컨테이너 같은 부작용 없음) 차단 대신 정리로 처리한다.
     * task 상태는 이미 삭제 대상이므로 미러링하지 않는다.
     */
    @Transactional
    public void closeOpenSessionsForTask(Long taskId, String actorId) {
        for (InterviewSession s : sessionRepo.findOpenByTaskId(taskId)) {
            appendTurn(s.getId(), "system", "note", "작업 삭제로 인터뷰 취소 (" + actorId + ")");
            s.setStatus(InterviewStatus.CANCELLED);
            s.setWorkerId(null);
            s.setClaimedAt(null);
            touch(s);
        }
    }

    private void requireOwner(InterviewSession s, String actorId, boolean isAdmin) {
        if (!s.isOwnedBy(actorId) && !isAdmin) {
            throw TaskException.forbidden();
        }
    }

    private InterviewSession requireSession(Long sessionId) {
        return sessionRepo.findActiveById(sessionId).orElseThrow(TaskException::notFound);
    }

    /**
     * 상태 전이용 잠금 로드(FOR UPDATE). claim(SKIP LOCKED)/스윕과 경합하는 전이는
     * 앞선 트랜잭션 커밋을 기다렸다가 갱신된 상태를 재판정해야 하므로 이쪽을 쓴다.
     */
    private InterviewSession requireSessionForUpdate(Long sessionId) {
        return sessionRepo.findByIdForUpdate(sessionId).orElseThrow(TaskException::notFound);
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

    /**
     * 세션 전이를 소유 task 상태로 미러링한다. 관리자가 행동해야 하는 구간(입력대기/플랜승인대기)을
     * 작업 목록에서 바로 식별하기 위한 것. task_id가 없는 레거시 세션은 조용히 무시한다.
     */
    private void mirrorTask(InterviewSession s, TaskStatus to, String actorId, String reason) {
        if (s.getTaskId() == null) return;
        Task t = taskRepo.findActiveById(s.getTaskId()).orElse(null);
        if (t == null || t.getStatus() == to) return;
        TaskStatus from = t.getStatus();
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "system", actorId, reason));
    }
}
