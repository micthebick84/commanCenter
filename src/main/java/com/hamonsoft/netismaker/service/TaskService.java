package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 작업 상태 전이의 단일 진입점. 모든 상태 변경은 여기서 + 히스토리 로깅.
 *
 *   create   ─► AWAITING_APPROVAL  (모델/effort/MCP는 승인 시점에 결정, Task 4)
 *   cancel   ─► AWAITING_APPROVAL/PENDING → CANCELLED  (본인, 두 상태 한정)
 *   delete   ─► soft delete (deleted_at = now)
 *   approve  ─► 상태에 따라 두 갈래 (ADMIN 한정, 그 외 상태는 409)
 *                 AWAITING_APPROVAL → INTERVIEWING (인터뷰 세션 생성, 현행 경로)
 *                 COMPLETED         → APPROVED/DESIGN_PENDING + approved=true (레거시 자동분석 경로)
 *   retry    ─► FAILED → PENDING + retry_count++  (retry_count < max_retry)
 */
@Service
@Profile("api")
public class TaskService {

    static final int MAX_DESIGN_REJECTS = 3;

    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskDesignRepository designRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final McpCatalogService mcpCatalogService;
    private final RepoCatalogService repoCatalogService;
    private final ObjectMapper objectMapper;
    private final InterviewService interviewService;
    private final TaskAttachmentRepository attachmentRepo;
    private final AttachmentStorage attachmentStorage;

    @Value("${app.task.user-concurrent-limit:5}")
    private int userConcurrentLimit;

    @Value("${app.task.max-retry:3}")
    private int maxRetry;

    public TaskService(TaskRepository taskRepo,
                       TaskAnalysisRepository analysisRepo,
                       TaskDesignRepository designRepo,
                       TaskStatusHistoryRepository historyRepo,
                       McpCatalogService mcpCatalogService,
                       RepoCatalogService repoCatalogService,
                       ObjectMapper objectMapper,
                       InterviewService interviewService,
                       TaskAttachmentRepository attachmentRepo,
                       AttachmentStorage attachmentStorage) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.designRepo = designRepo;
        this.historyRepo = historyRepo;
        this.mcpCatalogService = mcpCatalogService;
        this.repoCatalogService = repoCatalogService;
        this.objectMapper = objectMapper;
        this.interviewService = interviewService;
        this.attachmentRepo = attachmentRepo;
        this.attachmentStorage = attachmentStorage;
    }

    @Transactional
    public Task create(TaskCreateRequest req, String requesterId) {
        long active = taskRepo.countActiveByRequester(requesterId);
        if (active >= userConcurrentLimit) {
            throw TaskException.tooManyRequests(
                    "동시에 보유할 수 있는 미완료 작업 한도(" + userConcurrentLimit + ")를 초과했습니다");
        }
        RepoCatalogService.ResolvedRepo repo = repoCatalogService.resolveForRegistration(req.repoCatalogId());
        // 모델/effort/MCP는 승인 시점에 관리자가 결정 — 등록은 서버 기본값으로 시작한다.
        Task t = Task.create(repo.ownerRepo(), req.githubBranch(), req.title(),
                             req.description(), requesterId, maxRetry,
                             new ArrayList<>(), null, null);
        t.setStatus(TaskStatus.AWAITING_APPROVAL);
        t.setGitUrl(repo.gitUrl());
        t.setRepoAlias(repo.alias());
        t.setRepoCatalogId(repo.catalogId());
        Task saved = taskRepo.save(t);
        historyRepo.save(TaskStatusHistory.log(saved.getId(), null, TaskStatus.AWAITING_APPROVAL,
                "user", requesterId, "작업 등록"));
        return saved;
    }

    /**
     * 파일 첨부 등록 (스펙 2026-08-16 §6.2). 검증 → 기존 create 재사용(같은 tx) →
     * 메타 행 + 디스크 쓰기. 실패 시 tx 롤백 + 이미 쓴 파일 best-effort 삭제 — 부분 상태 없음.
     * 경로의 ordinal은 업로드 순번(1..N) — IDENTITY라 INSERT 전 id를 못 쓴다.
     */
    @Transactional
    public Task create(TaskCreateRequest req, List<MultipartFile> files, String requesterId) {
        List<MultipartFile> attached = files == null ? List.of() : files;
        attachmentStorage.validate(attached);
        Task saved = create(req, requesterId);   // 내부 호출 — 이미 @Transactional 안이라 같은 tx
        List<String> written = new ArrayList<>();
        try {
            int ordinal = 1;
            for (MultipartFile f : attached) {
                String rel = attachmentStorage.relativePath(saved.getId(), ordinal, f.getOriginalFilename());
                attachmentRepo.save(TaskAttachment.create(saved.getId(),
                        AttachmentStorage.sanitize(f.getOriginalFilename()), rel,
                        f.getContentType(), f.getSize(), requesterId));
                written.add(rel);
                attachmentStorage.write(rel, f);
                ordinal++;
            }
        } catch (RuntimeException e) {
            written.forEach(attachmentStorage::deleteQuietly);
            throw e;   // tx 롤백 → task/메타 행 전부 취소
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TaskAttachment> getAttachments(Long taskId) {
        return attachmentRepo.findByTaskIdOrderByIdAsc(taskId);
    }

    /** 첨부 단건 — task 소속이 아니면 존재를 숨긴다(404). ACL은 컨트롤러의 getForView가 담당. */
    @Transactional(readOnly = true)
    public TaskAttachment getAttachment(Long taskId, Long attachmentId) {
        TaskAttachment a = attachmentRepo.findById(attachmentId)
                .orElseThrow(TaskException::notFound);
        if (!a.getTaskId().equals(taskId)) throw TaskException.notFound();
        return a;
    }

    public Path resolveAttachmentPath(TaskAttachment att) {
        return attachmentStorage.resolve(att.getStoredPath());
    }

    /** 카탈로그 id 리스트 → snapshot 스펙. 비활성/누락 id는 거절. */
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

    @Transactional(readOnly = true)
    public Task getForView(Long taskId, String viewerId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!isAdmin && !t.isOwnedBy(viewerId)) {
            throw TaskException.forbidden();
        }
        return t;
    }

    @Transactional(readOnly = true)
    public Optional<TaskAnalysis> getAnalysis(Long taskId) {
        return analysisRepo.findById(taskId);
    }

    @Transactional(readOnly = true)
    public Optional<TaskDesign> getDesign(Long taskId) {
        return designRepo.findById(taskId);
    }

    @Transactional(readOnly = true)
    public Page<Task> list(String viewerId, boolean isAdmin, boolean mineOnly,
                           TaskStatus status, Pageable pageable) {
        if (isAdmin && !mineOnly) {
            return taskRepo.findAllActive(status, pageable);
        }
        return taskRepo.findByRequester(viewerId, status, pageable);
    }

    @Transactional
    public Task cancel(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) {
            throw TaskException.forbidden();
        }
        if (t.getStatus() != TaskStatus.PENDING && t.getStatus() != TaskStatus.AWAITING_APPROVAL) {
            throw TaskException.conflict("승인대기/작업대기 상태에서만 취소할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.CANCELLED);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.CANCELLED,
                "user", actorId, "사용자 취소"));
        return t;
    }

    @Transactional
    public void softDelete(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) {
            throw TaskException.forbidden();
        }
        // 배포 계열 상태 가드: 삭제하면 reconcile 관측·GC 보호에서 빠져 컨테이너(재시작 정책
        // 부여 시 불멸)와 공개 URL이 추적 불가 고아로 남는다 — 먼저 중지(undeploy)를 요구.
        switch (t.getStatus()) {
            case DEPLOYED, DEPLOY_LOST, DEPLOY_PENDING, DEPLOYING, UNDEPLOY_PENDING, UNDEPLOYING ->
                    throw TaskException.conflict("배포 이력이 활성인 작업은 먼저 중지 후 삭제할 수 있습니다 (현재: "
                            + t.getStatus().dbValue() + ")");
            default -> { }
        }
        interviewService.closeOpenSessionsForTask(t.getId(), actorId);
        t.setDeletedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(),
                isAdmin ? "system" : "user", actorId, "삭제"));
    }

    /**
     * 관리자 승인. 상태에 따라 두 의미로 갈린다.
     *
     *  승인대기 → 인터뷰 세션 생성 + task=인터뷰중  (현행 경로)
     *  분석완료 → analysis.approved + task=구현대기|디자인대기 (레거시 자동분석 경로)
     */
    @Transactional
    public void approve(Long taskId, String adminId, ApproveRequest req) {
        // 비관적 락(FOR UPDATE, SKIP LOCKED 없음) — 동시 승인 더블클릭 시 두 번째 호출이
        // 첫 번째 커밋을 기다렸다가 갱신된 상태를 재판정하도록 한다 (세션 중복 생성 방지).
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        switch (t.getStatus()) {
            case AWAITING_APPROVAL -> startInterview(t, adminId, req);
            case COMPLETED -> approveAnalysis(t, adminId);
            default -> throw TaskException.conflict(
                    "승인대기 또는 분석완료 상태에서만 승인할 수 있습니다 (현재: "
                            + t.getStatus().dbValue() + ")");
        }
    }

    /** 레거시 2-arg 호출부(테스트 등) 호환 — 바디 없는 승인. */
    @Transactional
    public void approve(Long taskId, String adminId) {
        approve(taskId, adminId, null);
    }

    /** 승인대기 → 인터뷰중. 세션을 만들고 모델/effort/MCP 선택을 task에도 박제한다. */
    private void startInterview(Task t, String adminId, ApproveRequest req) {
        List<TaskMcpSpec> extras = resolveMcpExtras(req == null ? null : req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req == null ? null : req.model());
        String effort = ModelEffortPolicy.resolveEffort(req == null ? null : req.effort());
        ModelEffortPolicy.validate(model, effort);   // 검증이 먼저 — 실패 시 세션이 생기면 안 된다

        t.setModel(model);
        t.setEffort(effort);
        t.setMcpsExtra(new ArrayList<>(extras));
        t.setFailureReason(null);
        interviewService.createForTask(t, model, effort, extras);

        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.INTERVIEWING);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.INTERVIEWING,
                "user", adminId, "관리자 승인 → 인터뷰 시작"));
    }

    /** 레거시: 분석완료 + admin 승인 → 구현/디자인 큐. 기존 동작 그대로. */
    private void approveAnalysis(Task t, String adminId) {
        TaskAnalysis a = analysisRepo.findById(t.getId())
                .orElseThrow(() -> TaskException.conflict("분석 결과가 없습니다"));
        boolean toDesign = t.isDesignRequested();
        String reason;
        if (!a.isApproved()) {
            a.setApproved(true);
            a.setApprovedBy(adminId);
            a.setApprovedAt(OffsetDateTime.now());
            reason = toDesign ? "관리자 승인 → 디자인 큐 진입" : "관리자 승인 → 구현 큐 진입";
        } else {
            reason = toDesign ? "이미 승인된 작업 → 디자인 큐 재진입" : "이미 승인된 작업 → 구현 큐 재진입";
        }
        TaskStatus from = t.getStatus();
        TaskStatus to = toDesign ? TaskStatus.DESIGN_PENDING : TaskStatus.APPROVED;
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", adminId,
                reason + (to == TaskStatus.DESIGN_PENDING ? " (디자인 구간)" : "")));
    }

    /** 디자인승인대기 → 구현대기. admin 한정. */
    @Transactional
    public TaskDesign approveDesign(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DESIGN_REVIEW) {
            throw TaskException.conflict("디자인승인대기 상태에서만 승인할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskDesign d = designRepo.findById(taskId)
                .orElseThrow(() -> TaskException.conflict("디자인 결과가 없습니다"));
        d.setApproved(true);
        d.setApprovedBy(adminId);
        d.setApprovedAt(OffsetDateTime.now());
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.APPROVED);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.APPROVED,
                "user", adminId, "디자인 승인 → 구현 큐 진입"));
        return d;
    }

    /** 디자인승인대기 → 디자인대기 (피드백 반려, 최대 MAX_DESIGN_REJECTS회). admin 한정. */
    @Transactional
    public Task rejectDesign(Long taskId, String adminId, String feedback) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DESIGN_REVIEW) {
            throw TaskException.conflict("디자인승인대기 상태에서만 반려할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskDesign d = designRepo.findById(taskId)
                .orElseThrow(() -> TaskException.conflict("디자인 결과가 없습니다"));
        if (d.getRejectCount() >= MAX_DESIGN_REJECTS) {
            throw TaskException.conflict("반려 한도(" + MAX_DESIGN_REJECTS
                    + ")에 도달했습니다 — 승인 또는 삭제만 가능합니다");
        }
        d.setFeedbackHistoryJson(appendFeedback(d.getFeedbackHistoryJson(), feedback, adminId));
        d.setRejectCount(d.getRejectCount() + 1);
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.DESIGN_PENDING);
        t.setWorkerId(null);
        t.setClaimedAt(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DESIGN_PENDING,
                "user", adminId, "디자인 반려 (" + d.getRejectCount() + "/" + MAX_DESIGN_REJECTS + ")"));
        return t;
    }

    /** feedback_history jsonb 배열에 항목 append. 파싱 실패 시 새 배열로 시작 (방어). */
    private String appendFeedback(String historyJson, String feedback, String adminId) {
        try {
            var list = objectMapper.readValue(
                    historyJson == null || historyJson.isBlank() ? "[]" : historyJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
            list.add(java.util.Map.of(
                    "feedback", feedback,
                    "rejectedBy", adminId,
                    "rejectedAt", OffsetDateTime.now().toString()));
            return objectMapper.writeValueAsString(list);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "피드백 이력 직렬화 실패");
        }
    }

    @Transactional
    public Task retry(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) {
            throw TaskException.forbidden();
        }
        if (t.getStatus() == TaskStatus.DESIGN_FAILED) {
            // 디자인 재시도: admin 수동 조작이므로 retryCount 소비 없음
            TaskStatus from = t.getStatus();
            t.setStatus(TaskStatus.DESIGN_PENDING);
            t.setFailureReason(null);
            t.setClaimedAt(null);
            t.setWorkerId(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DESIGN_PENDING,
                    isAdmin ? "system" : "user", actorId, "디자인 재시도"));
            return t;
        }
        if (t.getStatus() != TaskStatus.FAILED) {
            throw TaskException.conflict("분석실패/디자인실패 상태에서만 재시도할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (t.getRetryCount() >= t.getMaxRetry()) {
            throw TaskException.conflict("재시도 한도(" + t.getMaxRetry() + ")에 도달했습니다");
        }
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.PENDING);
        t.setRetryCount(t.getRetryCount() + 1);
        t.setFailureReason(null);
        t.setClaimedAt(null);
        t.setWorkerId(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.PENDING,
                isAdmin ? "system" : "user", actorId,
                "재시도 (" + t.getRetryCount() + "/" + t.getMaxRetry() + ")"));
        return t;
    }

    /** PR생성 → 배포대기. admin 한정. 워커가 다음 폴링에 claim해 배포 수행. */
    @Transactional
    public Task deploy(Long taskId, String adminId, List<EnvVar> envVars) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.PR_CREATED) {
            throw TaskException.conflict("PR생성 상태에서만 배포할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (envVars != null) t.setEnvVars(new ArrayList<>(envVars));
        return toDeployPending(t, adminId, "관리자 배포 요청 → 배포 큐 진입");
    }

    /** 배포완료/배포실패/배포중단됨 → 배포대기 (기존 컨테이너는 배포 시 stop 후 교체). */
    @Transactional
    public Task redeploy(Long taskId, String adminId, List<EnvVar> envVars) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DEPLOYED && t.getStatus() != TaskStatus.DEPLOY_FAILED
                && t.getStatus() != TaskStatus.DEPLOY_LOST) {
            throw TaskException.conflict("배포완료/배포실패/배포중단됨 상태에서만 재배포할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (envVars != null) t.setEnvVars(new ArrayList<>(envVars));
        return toDeployPending(t, adminId, "관리자 재배포 요청 → 배포 큐 진입");
    }

    /** 배포완료/배포실패/배포중단됨 → 배포중지대기. 워커가 claim해 컨테이너 stop 후 PR생성 복귀. */
    @Transactional
    public Task undeploy(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DEPLOYED && t.getStatus() != TaskStatus.DEPLOY_FAILED
                && t.getStatus() != TaskStatus.DEPLOY_LOST) {
            throw TaskException.conflict("배포완료/배포실패/배포중단됨 상태에서만 중지할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.UNDEPLOY_PENDING);
        t.setClaimedAt(null);
        t.setWorkerId(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.UNDEPLOY_PENDING,
                "user", adminId, "관리자 배포 중지 요청"));
        return t;
    }

    private Task toDeployPending(Task t, String adminId, String reason) {
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        t.setClaimedAt(null);
        t.setWorkerId(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DEPLOY_PENDING,
                "user", adminId, reason));
        return t;
    }
}
