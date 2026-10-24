package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.InterviewSummaryResponse;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 인터뷰 세션 상태 전이의 단일 진입점. 모든 상태 변경은 여기서 (TaskService 패턴).
 *
 *   createForTask ─► QUEUED (관리자 승인, TaskService.approve의 유일한 출구)
 *   claim         ─► QUEUED → RUNNING (워커, SKIP LOCKED)
 *   recordQuestion─► RUNNING → AWAITING_INPUT (워커가 질문 emit, 반납)
 *   submitAnswer  ─► AWAITING_INPUT → QUEUED (사용자 답변, 재큐; idempotent)
 *   submitAnswerWithRefs ─► submitAnswer + 참조 repo 변경(작업 인터뷰 답변, 한 트랜잭션)
 *   recordPlan    ─► RUNNING → PLAN_READY (writing-plans 완료)
 *   confirm       ─► PLAN_READY → REGISTERED (기존 task 갱신: analysis 프리필 + 구현/디자인 큐)
 *   cancel        ─► QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY → CANCELLED
 *   expire        ─► AWAITING_INPUT → EXPIRED (idle TTL) | QUEUED → EXPIRED (인터뷰 서비스 미처리 TTL)
 *   fail          ─► QUEUED/RUNNING/AWAITING_INPUT → FAILED
 *   releaseForRestart ─► RUNNING → QUEUED (인터뷰 서비스 재기동 정합, 턴당 최대 2회; 소진 시 FAILED)
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
    private final TaskStageUsageRepository stageUsageRepo;
    private final QuestionAttachmentRepository questionAttachmentRepo;
    private final InterviewWorkPaths workPaths;

    public InterviewService(InterviewSessionRepository sessionRepo,
                            InterviewTurnRepository turnRepo,
                            InterviewPlanRepository planRepo,
                            TaskRepository taskRepo,
                            TaskAnalysisRepository analysisRepo,
                            TaskStatusHistoryRepository historyRepo,
                            TaskAttachmentRepository attachmentRepo,
                            AttachmentStorage attachmentStorage,
                            TaskStageUsageRepository stageUsageRepo,
                            QuestionAttachmentRepository questionAttachmentRepo,
                            InterviewWorkPaths workPaths) {
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.planRepo = planRepo;
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
        this.attachmentRepo = attachmentRepo;
        this.attachmentStorage = attachmentStorage;
        this.stageUsageRepo = stageUsageRepo;
        this.questionAttachmentRepo = questionAttachmentRepo;
        this.workPaths = workPaths;
    }

    /** 작업 인터뷰 답변 중 참조 repo 변경의 카탈로그 검증 (스펙 2026-10-24 §4.3). 생성자를 바꾸지 않으려고 setter 주입. */
    private RepoCatalogService repoCatalogService;

    @Autowired
    void setRepoCatalogService(RepoCatalogService repoCatalogService) {
        this.repoCatalogService = repoCatalogService;
    }

    /**
     * submitAnswer 결과 (스펙 2026-09-13 §5.2). turnSeq = 방금 붙인 user answer 턴의 seq —
     * 질문 첨부를 이 턴에 링크하는 키. 중복 제출 no-op이면 null(첨부는 조용히 버려진다).
     */
    public record AnswerOutcome(InterviewSession session, Integer turnSeq) {}

    /**
     * 작업 인터뷰 답변 결과 (스펙 2026-10-24 §4.3). refNote = 참조가 바뀌었을 때 남긴 system 메모 턴(아니면 null) —
     * 호출자(컨트롤러)가 status(QUEUED) 다음에 note 이벤트로 민다.
     */
    public record AnswerResult(AnswerOutcome outcome, InterviewTurn refNote) {}

    /**
     * fail 결과. note = 실패 사유를 담은 system 턴 — 호출자가 터미널 status보다 **먼저** SSE로 밀어야 한다
     * (프론트는 FAILED를 받는 즉시 스트림을 닫으므로, DB에만 남기면 사유가 라이브로 전달되지 않는다).
     */
    public record FailOutcome(InterviewSession session, InterviewTurn note) {}

    /**
     * 재기동 정합 결과. released=true면 QUEUED로 되돌렸고(note = 재개 안내), false면 상한 소진으로 FAILED(note = 실패 사유).
     * 호출자는 note를 status보다 먼저 SSE로 밀어야 한다(FailOutcome과 같은 이유).
     */
    public record RestartOutcome(InterviewSession session, InterviewTurn note, boolean released) {}

    /** 인터뷰 서비스 재기동으로 중단된 같은 턴을 자동으로 다시 돌리는 횟수 상한(턴당, D13). 소진되면 실패 처리. */
    static final int MAX_RESTART_RELEASES = 2;

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
        // 작업 DB 연결 선택(스펙 2026-10-14 §5) — 인터뷰 claim이 이 id로 접속정보를 싣는다
        s.setDbConnectionIds(new ArrayList<>(t.getDbConnectionIds() == null ? List.of() : t.getDbConnectionIds()));
        // 작업 참조 repo(스펙 2026-10-24 §3.2) — 승인 때 조정한 결과를 복사. 리스트를 따로 두어 세션 쪽 변경(고정 커밋·
        // 대화 중 추가·해제)이 작업 목록에 번지지 않게 한다
        s.setRefRepos(new ArrayList<>(t.getRefRepos() == null ? List.of() : t.getRefRepos()));
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
     * role=system,kind=note 턴 추가 — 상태 전이 없음. 호출자(QuestionService.applyMcpChange)가
     * 같은 트랜잭션에서 세션 행을 이미 잠그고 있으므로 여기서는 잠그지 않는다 (스펙 2026-09-13 §5.2).
     */
    @Transactional
    public InterviewTurn appendSystemNote(Long sessionId, String content) {
        return appendTurn(sessionId, "system", "note", content);
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
            s.setWorkDir(workPaths.workDirFor(s.getGithubRepo(), s.getGitUrl(), s.getId()));
        }
        touch(s);
        List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(s.getId());
        return Optional.of(InterviewClaimResponse.of(s, turns, attachmentRefsFor(s),
                turnAttachmentsFor(s), attachmentRootFor(s))
                .withRefRepos(refsFor(s))
                .withRefsCleared(refsCleared(s, turns)));
    }

    /**
     * 세션의 참조 repo를 claim 원소로 바꾼다 — 질문·인터뷰 세션 공통 (스펙 2026-10-22 §4.4, 2026-10-24 §4.4).
     * dir은 저장하지 않고 claim마다 같은 규칙({루트}/_refs/{세션 id}/{카탈로그 id})으로 계산한다.
     * sha는 처음 받아 고정한 커밋이고 아직 없으면 null — 러너는 그 커밋을 쓰고, 없으면 브랜치 최신을 받아 보고한다.
     */
    private List<InterviewClaimResponse.ClaimRefRepo> refsFor(InterviewSession s) {
        if (s.getRefRepos() == null) return List.of();
        return s.getRefRepos().stream()
                .map(r -> new InterviewClaimResponse.ClaimRefRepo(
                        r.catalogId(), r.alias(), r.path(), r.gitUrl(),
                        RepoRef.fromSnapshot(r.path(), r.gitUrl()).host(),
                        r.branch(), workPaths.refDir(s.getId(), r.catalogId()), r.sha()))
                .toList();
    }

    /**
     * 작업 인터뷰에서 참조를 대화 중 모두 뺐는지 (스펙 2026-10-24 §4.4) — INTERVIEW이고, 지금 참조가 없고, 마지막 참조 변경 메모
     * (system·note 턴 중 {@link RefRepoChanges#NOTE_PREFIX}로 시작하는 것)가 {@link RefRepoChanges#CLEARED_NOTE}일 때만 true.
     * 참조를 한 번도 고르지 않은 세션(메모 없음)과 질문 세션은 false라 프롬프트가 그대로다. claim이 이미 읽은 턴을 쓴다(조회 추가 없음).
     */
    private static boolean refsCleared(InterviewSession s, List<InterviewTurn> turns) {
        if (s.isQuestion() || (s.getRefRepos() != null && !s.getRefRepos().isEmpty())) return false;
        for (int i = turns.size() - 1; i >= 0; i--) {
            InterviewTurn t = turns.get(i);
            if ("system".equals(t.getRole()) && "note".equals(t.getKind())
                    && t.getContent() != null && t.getContent().startsWith(RefRepoChanges.NOTE_PREFIX)) {
                return RefRepoChanges.CLEARED_NOTE.equals(t.getContent());
            }
        }
        return false;
    }

    /**
     * 세션 레벨 첨부 → 절대경로 ref (스펙 §5.4). kind 분기(스펙 2026-09-13 §5.2):
     * INTERVIEW = 소유 task의 첨부(task 없는 레거시 세션은 []), QUESTION = turn_seq null(킥오프) 행.
     */
    private List<InterviewClaimResponse.AttachmentRef> attachmentRefsFor(InterviewSession s) {
        if (s.isQuestion()) {
            return questionAttachmentRepo.findBySessionIdAndTurnSeqIsNullOrderByIdAsc(s.getId()).stream()
                    .map(this::toRef)
                    .toList();
        }
        if (s.getTaskId() == null) return List.of();
        return attachmentRepo.findByTaskIdOrderByIdAsc(s.getTaskId()).stream()
                .map(a -> new InterviewClaimResponse.AttachmentRef(
                        a.getId(), a.getOriginalFilename(),
                        attachmentStorage.absolutePathOf(a.getStoredPath()),
                        a.getContentType(), a.getSizeBytes()))
                .toList();
    }

    /** QUESTION: 추가 질문 첨부를 답변 turn seq별로 묶는다 (id 순 유지). INTERVIEW는 빈 맵. */
    private Map<Integer, List<InterviewClaimResponse.AttachmentRef>> turnAttachmentsFor(InterviewSession s) {
        if (!s.isQuestion()) return Map.of();
        Map<Integer, List<InterviewClaimResponse.AttachmentRef>> byTurn = new LinkedHashMap<>();
        for (QuestionAttachment a : questionAttachmentRepo.findBySessionIdAndTurnSeqIsNotNullOrderByIdAsc(s.getId())) {
            byTurn.computeIfAbsent(a.getTurnSeq(), k -> new ArrayList<>()).add(toRef(a));
        }
        return byTurn;
    }

    /** QUESTION이면 {dir}/question-{sid} 절대경로(러너 Read 게이트의 두 번째 허용 루트), INTERVIEW면 null. */
    private String attachmentRootFor(InterviewSession s) {
        if (!s.isQuestion()) return null;
        return attachmentStorage.absolutePathOf(attachmentStorage.questionRootRelative(s.getId()));
    }

    private InterviewClaimResponse.AttachmentRef toRef(QuestionAttachment a) {
        return new InterviewClaimResponse.AttachmentRef(
                a.getId(), a.getOriginalFilename(),
                attachmentStorage.absolutePathOf(a.getStoredPath()),
                a.getContentType(), a.getSizeBytes(),
                a.getExtractedTextPath() == null ? null : attachmentStorage.absolutePathOf(a.getExtractedTextPath()));
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
        addUsage(s, req.costUsd(), req.inputTokens(), req.outputTokens(),
                req.cacheCreationTokens(), req.cacheReadTokens());
        // 컨텍스트 스냅샷(스펙 2026-09-05 §4.2): 마지막 턴 값으로 덮어쓴다. 미보고(null)면 이전 값 유지.
        if (req.contextTokens() != null) s.setContextTokens(req.contextTokens());
        if (req.contextWindow() != null) s.setContextWindow(req.contextWindow());
        applyRefStates(s, req.refStates());
        s.setRestartRequeueCount(0); // 턴이 정상 종료 → 재기동 재개 횟수는 턴 단위(D13)
        String kind = req.kind() == null || req.kind().isBlank() ? "question" : req.kind();
        InterviewTurn turn = appendTurn(sessionId, "assistant", kind,
                req.content() == null ? "" : req.content());
        s.setStatus(InterviewStatus.AWAITING_INPUT);
        s.setWorkerId(null);
        s.setClaimedAt(null);
        // 질문 세션은 phase 개념이 없다 (스펙 §4) — brainstorming 표기를 남기지 않는다.
        if (!s.isQuestion()) s.setCurrentPhase("brainstorming");
        touch(s);
        mirrorTask(s, TaskStatus.INTERVIEW_INPUT, workerId, "인터뷰 질문 도착 → 관리자 답변 대기");
        return turn;
    }

    /**
     * 러너가 보고한 참조 repo 준비 결과를 세션의 현재 참조에 반영한다 (스펙 2026-10-22 §4.5).
     * 상태마다 {@link RefRepo#matches}(카탈로그 id + 브랜치)인 원소에만 적용하고, 그새 빠진 참조의 보고는 무시한다.
     * <ul>
     *   <li>error가 있으면 그 사유를 기록한다(같은 상태의 sha는 무시). 이미 고정된 커밋·시각은 건드리지 않는다.
     *       저장하는 값은 자격증명을 한 번 더 가리고({@link GitRemotes#mask}) {@value #REF_ERROR_MAX_LENGTH}자로 자른 것이다
     *       ({@link #storedRefError}) — 러너가 이미 가리고 줄여 보내지만 워커 API는 러너의 출력을 그대로 믿지 않는다.</li>
     *   <li>아니고 sha가 온전한 커밋 id(40자·64자 16진수, {@link #isFullSha})면 처음 받은 커밋으로 고정하고 낡은 error를
     *       지운다 — 이미 고정된 sha는 바뀌지 않는다({@link RefRepo#pinned}). 형식이 다른 sha는 무시한다(error도 그대로) —
     *       화면이 '고정'으로 보여 준 참조가 구현(워커 isFullSha와 같은 정의)에서 형식 오류로 실패하지 않게(스펙 2026-10-24 §4.5).</li>
     *   <li>둘 다 없으면 무시한다.</li>
     * </ul>
     * 바뀐 게 있을 때만 목록을 갈아 끼운다. refStates가 null이거나 비면(구버전 러너·참조 없는 질문) 아무것도 하지 않는다.
     * 질문 보고(recordQuestion)와 계획 보고(recordPlan) 모두 이 메서드를 쓴다.
     */
    private void applyRefStates(InterviewSession s, List<WorkerQuestionRequest.RefState> states) {
        if (states == null || states.isEmpty() || s.getRefRepos() == null || s.getRefRepos().isEmpty()) return;
        List<RefRepo> next = new ArrayList<>(s.getRefRepos());
        boolean changed = false;
        for (WorkerQuestionRequest.RefState st : states) {
            if (st == null) continue;
            for (int i = 0; i < next.size(); i++) {
                RefRepo cur = next.get(i);
                if (!cur.matches(st.catalogId(), st.branch())) continue;
                RefRepo upd;
                if (st.error() != null) {
                    upd = cur.withError(storedRefError(st.error()));
                } else if (isFullSha(st.sha())) {
                    upd = cur.pinned(st.sha(), OffsetDateTime.now().toString());
                } else {
                    continue;
                }
                if (!upd.equals(cur)) {
                    next.set(i, upd);
                    changed = true;
                }
            }
        }
        if (changed) s.setRefRepos(new ArrayList<>(next));
    }

    /** 고정 커밋으로 인정하는 sha — 40자(SHA-1)·64자(SHA-256) 16진수, 대소문자 무관 (워커 isFullSha와 같은 정의). */
    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");

    private static boolean isFullSha(String sha) {
        return sha != null && FULL_SHA.matcher(sha).matches();
    }

    /** 저장하는 참조 실패 사유의 글자 수 상한(UTF-16 단위). 러너는 이미 200자 이내로 줄여 보내므로 이 값은 방어용이다. */
    static final int REF_ERROR_MAX_LENGTH = 500;

    /**
     * 러너가 보고한 참조 실패 사유를 저장 형태로 바꾼다 — 자격증명을 가리고({@link GitRemotes#mask}) 나서
     * {@value #REF_ERROR_MAX_LENGTH}자로 자른다. 가린 뒤에 자르는 이유: 먼저 자르면 토큰이 반쪽으로 남고 mask(끝의 `@`까지 있어야
     * 가린다)가 알아보지 못한다. 서로게이트 쌍의 앞 절반에서 잘렸으면 그 반쪽은 버린다 — 깨진 문자가 JSONB로 나가면 직렬화가 실패해
     * 답변 보고 전체가 500이 된다.
     */
    private static String storedRefError(String error) {
        String masked = GitRemotes.mask(error);
        if (masked.length() <= REF_ERROR_MAX_LENGTH) return masked;
        int end = REF_ERROR_MAX_LENGTH;
        if (Character.isHighSurrogate(masked.charAt(end - 1))) end--;
        return masked.substring(0, end);
    }

    /**
     * writing-plans 완료 → PLAN_READY. design+plan 영속화, 비용 누적, 참조 repo 준비 결과 반영, 워커 반납.
     * 참조 결과도 반영해야 마지막 답변에서 더한 참조가 계획 턴에 처음 받아졌을 때 커밋이 고정된다 (스펙 2026-10-24 §4.5).
     */
    @Transactional
    public InterviewPlan recordPlan(Long sessionId, String workerId, WorkerPlanRequest req) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        requireNotQuestion(s, "플랜 보고");
        if (s.getStatus() != InterviewStatus.RUNNING) {
            throw TaskException.conflict("인터뷰중 상태에서만 플랜을 보고할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        requireWorker(s, workerId);
        addUsage(s, req.costUsd(), req.inputTokens(), req.outputTokens(),
                req.cacheCreationTokens(), req.cacheReadTokens());
        applyRefStates(s, req.refStates());
        s.setRestartRequeueCount(0); // 턴이 정상 종료 → 재기동 재개 횟수는 턴 단위(D13)
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

    /**
     * 워커 heartbeat — 생존 신호는 last_activity_at에만 기록한다 (touch).
     * claimed_at은 갱신하지 않는다: claimed_at은 "이 턴이 시작된 시각"(claim이 기록)이고
     * 스윕의 wall-clock 백스톱 기준이다. heartbeat가 이를 갱신하면 SDK가 행업해도
     * (워커 프로세스는 살아서 계속 heartbeat) stale-RUNNING 회수가 영영 발동하지 않는다.
     */
    @Transactional
    public void heartbeat(Long sessionId, String workerId) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        requireWorker(s, workerId);
        touch(s);
    }

    /** 시스템/스윕용 실패 처리 → FAILED. terminal 상태에선 거부. */
    @Transactional
    public FailOutcome fail(Long sessionId, String actor, String reason) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        InterviewStatus st = s.getStatus();
        if (st != InterviewStatus.QUEUED && st != InterviewStatus.RUNNING
                && st != InterviewStatus.AWAITING_INPUT) {
            throw TaskException.conflict("진행중 인터뷰만 실패 처리할 수 있습니다 (현재: "
                    + st.dbValue() + ")");
        }
        return doFail(s, actor, reason);
    }

    /**
     * 워커발 실패 보고 — 소유권 강제 버전. RUNNING + 소유 워커일 때만 FAILED로 전이한다.
     * 워커는 RUNNING 세션만 소유하므로, 지각 도착한 실패 보고(턴 타임아웃 fail이
     * in-flight postQuestion과 경합하는 경계 등)가 이미 질문을 전달해 AWAITING_INPUT이
     * 된 세션이나 다른 워커가 재클레임한 세션을 파괴하지 못하게 409로 거절한다.
     * 스윕/시스템 경로는 기존 fail()을 그대로 쓴다.
     */
    @Transactional
    public FailOutcome failFromWorker(Long sessionId, String workerId, String reason) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        if (s.getStatus() != InterviewStatus.RUNNING) {
            throw TaskException.conflict("인터뷰중(워커 소유) 상태에서만 워커 실패 보고를 받을 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        requireWorker(s, workerId);
        return doFail(s, workerId, reason);
    }

    private FailOutcome doFail(InterviewSession s, String actor, String reason) {
        // 세션 종류에 맞는 라벨을 노트에 그대로 넣는다 — 프론트 배너가 이 문장을 접두사 없이 쓴다
        // (질문 세션 배너는 '답변 실패', 인터뷰는 '인터뷰 실패').
        String label = s.getKind() == InterviewKind.QUESTION ? "답변 실패" : "인터뷰 실패";
        InterviewTurn note = appendTurn(s.getId(), "system", "note",
                label + ": " + (reason == null ? "원인 미상" : reason));
        s.setStatus(InterviewStatus.FAILED);
        s.setWorkerId(null);
        s.setClaimedAt(null);
        touch(s);
        mirrorTask(s, TaskStatus.AWAITING_APPROVAL, actor, "인터뷰 실패 → 승인대기 복귀");
        return new FailOutcome(s, note);
    }

    /**
     * 사용자 답변 → AWAITING_INPUT → QUEUED 재큐. idempotent:
     * 같은 replyToSeq에 응답하는 user answer 턴이 이미 있으면 무시 (reply_to_seq 컬럼이 키).
     */
    @Transactional
    public AnswerOutcome submitAnswer(Long sessionId, String actorId, boolean isAdmin, AnswerRequest req) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        requireOwner(s, actorId, isAdmin);
        // idempotency: 같은 replyToSeq에 대한 user answer 턴이 이미 있으면 중복 → 무시 (상태 무관).
        // status 가드보다 먼저 검사해야 한다 — 1차 답변이 이미 AWAITING_INPUT→QUEUED로 전이시킨 뒤
        // 동일 답변이 재제출되면(클라 재시도/더블서밋) conflict가 아니라 no-op 성공이어야 하기 때문.
        if (req.replyToSeq() != null) {
            boolean already = turnRepo.findBySessionIdOrderBySeqAsc(sessionId).stream()
                    .anyMatch(t -> "user".equals(t.getRole()) && "answer".equals(t.getKind())
                            && req.replyToSeq().equals(t.getReplyToSeq()));
            if (already) return new AnswerOutcome(s, null); // no-op, 상태 유지, 턴 없음
        }
        if (s.getStatus() != InterviewStatus.AWAITING_INPUT) {
            throw TaskException.conflict("입력대기 상태에서만 답변할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        InterviewTurn answer = appendTurn(sessionId, "user", "answer", req.answer(), req.replyToSeq());
        s.setStatus(InterviewStatus.QUEUED);
        touch(s);
        mirrorTask(s, TaskStatus.INTERVIEWING, actorId, "관리자 답변 → 인터뷰 재개");
        return new AnswerOutcome(s, answer.getSeq());
    }

    /**
     * 작업 인터뷰 답변(관리자) + 대화 중 참조 repo 변경 (스펙 2026-10-24 §4.3). 1단계 질문 ask와 같은 순서·규칙:
     * submitAnswer(재큐, 중복 제출은 no-op) → {@link RefRepoChanges#diff}(대상 = 세션의 repoCatalogId; null = 유지, [] = 전부 해제,
     * 같은 집합 = 검증 없는 no-op, 다르면 새로 들어오는 짝만 검증) → 바뀌었으면 세션 참조 갱신 + system 메모.
     * 한 트랜잭션이라 참조가 거절(400)되면 답변 턴·재큐·작업 상태 미러링까지 함께 롤백된다. 유지되는 짝은 기존 원소(고정 커밋 포함)를
     * 그대로 두고, 다음 claim이 현재 목록을 다시 읽으므로 러너는 다음 턴부터 새 목록으로 받는다.
     */
    @Transactional
    public AnswerResult submitAnswerWithRefs(Long sessionId, String adminId, AnswerRequest req) {
        AnswerOutcome outcome = submitAnswer(sessionId, adminId, true, req);
        InterviewSession s = outcome.session();
        RefRepoChanges.Change change =
                RefRepoChanges.diff(s.getRepoCatalogId(), s.getRefRepos(), req.refRepos(), repoCatalogService);
        if (change == null) return new AnswerResult(outcome, null);
        s.setRefRepos(new ArrayList<>(change.next()));
        return new AnswerResult(outcome, appendSystemNote(s.getId(), RefRepoChanges.systemNote(change)));
    }

    /** 이 워커가 잡고 있는(RUNNING) 세션 id들 — 기동 정합 대상. */
    @Transactional(readOnly = true)
    public List<Long> runningSessionIdsOf(String workerId) {
        return sessionRepo.findRunningIdsByWorker(workerId);
    }

    /**
     * 인터뷰 서비스 재기동 정합 — 이 워커가 RUNNING으로 잡고 있던 세션 1건을 QUEUED로 되돌려 같은 답을 다시 돌린다.
     * 횟수(restart_requeue_count)는 턴 단위다: recordQuestion/recordPlan(정상 종료)이 0으로 되돌린다.
     * 상한({@value #MAX_RESTART_RELEASES}회) 소진이면 기존 doFail 경로로 FAILED.
     * RUNNING이 아니거나 다른 워커 소유면 아무것도 바꾸지 않고 empty — 정합 호출과 경합한 정상 보고·회수를 건드리지 않는다.
     */
    @Transactional
    public Optional<RestartOutcome> releaseForRestart(Long sessionId, String workerId) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        if (s.getStatus() != InterviewStatus.RUNNING || !java.util.Objects.equals(s.getWorkerId(), workerId)) {
            return Optional.empty();
        }
        if (s.getRestartRequeueCount() >= MAX_RESTART_RELEASES) {
            FailOutcome f = doFail(s, "worker-restart",
                    "인터뷰 서비스 재기동으로 중단(자동 재개 " + MAX_RESTART_RELEASES + "회 소진)");
            return Optional.of(new RestartOutcome(f.session(), f.note(), false));
        }
        s.setStatus(InterviewStatus.QUEUED);
        s.setWorkerId(null);
        s.setClaimedAt(null);
        s.setRestartRequeueCount(s.getRestartRequeueCount() + 1);
        touch(s);
        InterviewTurn note = appendTurn(sessionId, "system", "note",
                "인터뷰 서비스가 다시 시작돼 이 단계를 다시 진행합니다.");
        mirrorTask(s, TaskStatus.INTERVIEWING, "worker-restart", "인터뷰 서비스 재기동 → 인터뷰 재개");
        return Optional.of(new RestartOutcome(s, note, true));
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
        return confirm(sessionId, adminId, designRequested, ImplementationMode.DIRECT);
    }

    /** mode: 확정 때 고른 구현 방식 — null이면 직접 (스펙 2026-10-15 §5.1). */
    @Transactional
    public Long confirm(Long sessionId, String adminId, boolean designRequested, ImplementationMode mode) {
        InterviewSession s = requireSessionForUpdate(sessionId);
        requireNotQuestion(s, "확정");
        if (s.getStatus() != InterviewStatus.PLAN_READY) {
            throw TaskException.conflict("플랜완료 상태에서만 확정할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        if (s.getTaskId() == null) {
            throw TaskException.conflict("작업에 연결되지 않은 세션입니다");
        }
        // task도 비관적 락(FOR UPDATE) — softDelete/approve 등 task 진입점과 경합 시 갱신된
        // 상태를 재판정한다(무락이면 상태 가드가 낡은 스냅샷을 보고 이중 전이·lost update 가능).
        // 락 순서는 세션 → task (cancel 등 동일 순서의 세션 진입점과는 대기-재판정으로 수렴).
        Task t = taskRepo.findActiveByIdForUpdate(s.getTaskId()).orElseThrow(TaskException::notFound);
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
        t.setImplementationMode(mode == null ? ImplementationMode.DIRECT : mode);
        TaskStatus from = t.getStatus();
        TaskStatus to = designRequested ? TaskStatus.DESIGN_PENDING : TaskStatus.APPROVED;
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", adminId,
                "플랜 확정 → " + to.dbValue() + " (interview_session " + sessionId
                        + ", 구현 방식: " + t.getImplementationMode().label() + ")"));
        copyPinnedRefs(s, t, to, adminId);

        // 인터뷰 사용량 이관 (스펙 §4.2) — 세션당 confirm 1회(PLAN_READY 가드)라 이중 누적 없음.
        if ((s.getTotalCostUsd() != null && s.getTotalCostUsd().signum() > 0)
                || s.getInputTokens() > 0 || s.getOutputTokens() > 0
                || s.getCacheCreationTokens() > 0 || s.getCacheReadTokens() > 0) {
            stageUsageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_INTERVIEW,
                    s.getTotalCostUsd() == null ? BigDecimal.ZERO : s.getTotalCostUsd(),
                    s.getInputTokens(), s.getOutputTokens(),
                    s.getCacheCreationTokens(), s.getCacheReadTokens());
        }

        s.setStatus(InterviewStatus.REGISTERED);
        touch(s);
        return t.getId();
    }

    /**
     * 확정 때 세션의 참조를 작업으로 넘긴다 (스펙 2026-10-24 §3.2·§4.6) — 고정 커밋(sha)이 있는 원소만 error를 비워 복사하고,
     * 인터뷰에서 한 번도 받지 못한 원소(sha 없음)는 뺀다. 세션 참조가 비면 작업 참조도 []로 덮는다(인터뷰 중 모두 뺀 경우).
     * 세션에 참조가 있었으면 기존 확정 이력(문구 불변) 다음에 같은 상태의 별도 이력 행을 남긴다:
     * {@code 구현 참조: {별칭}({브랜치})@{sha7}, …}(고정된 것이 없으면 {@code 없음}) + 뺀 것이 있으면
     * {@code  / 제외(인터뷰에서 받지 못함): {별칭}({브랜치}), …}. 확정은 막지 않는다.
     */
    private void copyPinnedRefs(InterviewSession s, Task t, TaskStatus to, String adminId) {
        List<RefRepo> sessionRefs = s.getRefRepos() == null ? List.of() : s.getRefRepos();
        List<RefRepo> pinned = sessionRefs.stream().filter(r -> r.sha() != null && !r.sha().isBlank())
                .map(r -> r.withError(null)).toList();
        t.setRefRepos(new ArrayList<>(pinned));
        if (sessionRefs.isEmpty()) return;
        List<RefRepo> excluded = sessionRefs.stream().filter(r -> r.sha() == null || r.sha().isBlank()).toList();
        String reason = "구현 참조: " + (pinned.isEmpty() ? "없음" : pinned.stream()
                .map(r -> r.label() + "@" + r.sha().substring(0, Math.min(7, r.sha().length())))
                .collect(Collectors.joining(", ")));
        if (!excluded.isEmpty()) {
            reason += " / 제외(인터뷰에서 받지 못함): "
                    + excluded.stream().map(RefRepo::label).collect(Collectors.joining(", "));
        }
        historyRepo.save(TaskStatusHistory.log(t.getId(), to, to, "user", adminId, reason));
    }

    /** ACL 검증 후 세션 반환 (소유자/관리자만). 스트림 구독 전 권한 체크에도 사용. */
    @Transactional(readOnly = true)
    public InterviewSession getForView(Long id, String viewerId, boolean isAdmin) {
        InterviewSession s = sessionRepo.findActiveById(id).orElseThrow(TaskException::notFound);
        requireOwner(s, viewerId, isAdmin);
        return s;
    }

    /**
     * 상세 뷰 조립 (status + turns + plan [+ 질문 첨부·MCP id]). ACL은 getForView가 강제.
     * QUESTION이면 첨부를 등록분(turn_seq null)/턴별로 나눠 싣는다 — 경로는 내보내지 않는다 (스펙 2026-09-13 §5.1).
     */
    @Transactional(readOnly = true)
    public InterviewResponse getResponse(Long id, String viewerId, boolean isAdmin) {
        InterviewSession s = getForView(id, viewerId, isAdmin);
        List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(id);
        InterviewPlan plan = planRepo.findById(id).orElse(null);
        if (!s.isQuestion()) return InterviewResponse.of(s, turns, plan);
        List<InterviewResponse.AttachmentView> kickoff = new ArrayList<>();
        Map<Integer, List<InterviewResponse.AttachmentView>> byTurn = new LinkedHashMap<>();
        for (QuestionAttachment a : questionAttachmentRepo.findBySessionIdOrderByIdAsc(id)) {
            InterviewResponse.AttachmentView v = new InterviewResponse.AttachmentView(
                    a.getId(), a.getOriginalFilename(), a.getContentType(), a.getSizeBytes());
            if (a.getTurnSeq() == null) kickoff.add(v);
            else byTurn.computeIfAbsent(a.getTurnSeq(), k -> new ArrayList<>()).add(v);
        }
        return InterviewResponse.of(s, turns, plan, kickoff, byTurn);
    }

    /** task 상세가 열 세션 — 최신 1건. */
    @Transactional(readOnly = true)
    public Optional<Long> latestSessionIdForTask(Long taskId) {
        return sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(taskId).map(InterviewSession::getId);
    }

    /**
     * task 상세가 인터뷰 단계일 때 보여 줄 참조 — 최신 세션의 참조 사본(고정 커밋·실패 사유 포함, 스펙 2026-10-24 §4.9).
     * 세션이 없으면 empty, 세션 값이 null이면 [].
     */
    @Transactional(readOnly = true)
    public Optional<List<RefRepo>> latestSessionRefRepos(Long taskId) {
        return sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(taskId)
                .map(s -> s.getRefRepos() == null ? List.of() : List.copyOf(s.getRefRepos()));
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

    /**
     * "이 대화로 작업 등록"이 첨부할 질문 대화 전문 (스펙 2026-10-24 §4.1). 등록자가 그 질문을 볼 수 있어야 한다 —
     * 없거나 질문이 아니면 404, 소유자도 관리자도 아니면 403(QuestionService.requireViewable과 같은 규칙).
     * 답변 중(QUEUED·RUNNING)이면 아직 대화가 끝나지 않았으므로 409. 끝난 질문(종료·만료·실패)도 등록할 수 있다.
     */
    @Transactional(readOnly = true)
    public String questionTranscript(Long questionId, String viewerId, boolean isAdmin) {
        InterviewSession q = requireKind(questionId, InterviewKind.QUESTION);
        requireOwner(q, viewerId, isAdmin);
        if (q.getStatus() == InterviewStatus.QUEUED || q.getStatus() == InterviewStatus.RUNNING) {
            throw TaskException.conflict("답변이 끝난 뒤에 작업으로 등록할 수 있습니다");
        }
        return QuestionTranscript.render(q, turnRepo.findBySessionIdOrderBySeqAsc(questionId),
                questionAttachmentRepo.findBySessionIdOrderByIdAsc(questionId));
    }

    /** 질문 세션은 플랜/등록 경로 진입 불가 (스펙 §6-③). 상태와 무관하게 400. */
    private void requireNotQuestion(InterviewSession s, String action) {
        if (s.isQuestion()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "질문 세션에서는 " + action + "이(가) 불가능합니다");
        }
    }

    /**
     * 컨트롤러 교차 kind 가드 — 다른 kind의 세션은 다른 리소스로 취급한다(404, 스펙 §5).
     * /api/interviews/*는 INTERVIEW만, /api/questions/*는 QUESTION만 조작 가능.
     */
    @Transactional(readOnly = true)
    public InterviewSession requireKind(Long id, InterviewKind kind) {
        InterviewSession s = sessionRepo.findActiveById(id).orElseThrow(TaskException::notFound);
        if (s.getKind() != kind) throw TaskException.notFound();
        return s;
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

    private void addUsage(InterviewSession s, BigDecimal cost,
                          Long in, Long out, Long cc, Long cr) {
        if (cost != null) {
            BigDecimal base = s.getTotalCostUsd() == null ? BigDecimal.ZERO : s.getTotalCostUsd();
            s.setTotalCostUsd(base.add(cost));
        }
        s.setInputTokens(s.getInputTokens() + nz(in));
        s.setOutputTokens(s.getOutputTokens() + nz(out));
        s.setCacheCreationTokens(s.getCacheCreationTokens() + nz(cc));
        s.setCacheReadTokens(s.getCacheReadTokens() + nz(cr));
    }
    private static long nz(Long v) { return v == null ? 0L : v; }

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
        // 비관적 락(FOR UPDATE) — softDelete 등 task 진입점과 경합 시 커밋을 기다렸다가
        // 재판정한다(무락이면 JPA 전체-컬럼 UPDATE가 상대 변경을 되돌리는 lost update 가능).
        // 삭제가 먼저 커밋됐으면 findActive가 비어 조용히 스킵된다.
        Task t = taskRepo.findActiveByIdForUpdate(s.getTaskId()).orElse(null);
        if (t == null || t.getStatus() == to) return;
        TaskStatus from = t.getStatus();
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "system", actorId, reason));
    }
}
