package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 작업 상태 전이의 단일 진입점. 모든 상태 변경은 여기서 + 히스토리 로깅.
 *
 *   create   ─► PENDING
 *   cancel   ─► PENDING → CANCELLED  (본인, PENDING 한정)
 *   delete   ─► soft delete (deleted_at = now)
 *   approve  ─► COMPLETED + approved=true  (ADMIN, COMPLETED 한정)
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
                       ObjectMapper objectMapper) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.designRepo = designRepo;
        this.historyRepo = historyRepo;
        this.mcpCatalogService = mcpCatalogService;
        this.repoCatalogService = repoCatalogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Task create(TaskCreateRequest req, String requesterId) {
        long active = taskRepo.countActiveByRequester(requesterId);
        if (active >= userConcurrentLimit) {
            throw TaskException.tooManyRequests(
                    "동시에 보유할 수 있는 미완료 작업 한도(" + userConcurrentLimit + ")를 초과했습니다");
        }
        List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req.model());
        String effort = ModelEffortPolicy.resolveEffort(req.effort());
        ModelEffortPolicy.validate(model, effort);
        RepoCatalogService.ResolvedRepo repo = repoCatalogService.resolveForRegistration(req.repoCatalogId());
        Task t = Task.create(repo.ownerRepo(), req.githubBranch(), req.title(),
                             req.description(), requesterId, maxRetry, extras, model, effort);
        t.setGitUrl(repo.gitUrl());
        t.setRepoAlias(repo.alias());
        t.setRepoCatalogId(repo.catalogId());
        t.setDesignRequested(Boolean.TRUE.equals(req.designRequested()));
        Task saved = taskRepo.save(t);
        historyRepo.save(TaskStatusHistory.log(saved.getId(), null, TaskStatus.PENDING,
                "user", requesterId, "작업 등록"));
        return saved;
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
        if (t.getStatus() != TaskStatus.PENDING) {
            throw TaskException.conflict("작업대기 상태에서만 취소할 수 있습니다 (현재: "
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
        t.setDeletedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(),
                isAdmin ? "system" : "user", actorId, "삭제"));
    }

    /**
     * 관리자 승인 (idempotent).
     *
     *  COMPLETED + !approved          ─► analysis.approved=true + task.status=APPROVED (1차 승인)
     *  COMPLETED + approved           ─► task.status=APPROVED만 전이 (구 데이터 마이그레이션 / 큐 재진입)
     *  APPROVED/IMPLEMENTING/PR_CREATED 등 ─► conflict (이미 큐/진행중)
     *
     * task.status 전이가 핵심 — 워커 큐가 PENDING/APPROVED 둘 다 보고 있어
     * 승인 즉시 다음 폴링에 picked up되어 worktree 구현 → PR 생성으로 이어짐.
     */
    @Transactional
    public TaskAnalysis approve(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.COMPLETED) {
            throw TaskException.conflict("분석완료 상태에서만 승인할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskAnalysis a = analysisRepo.findById(taskId)
                .orElseThrow(() -> TaskException.conflict("분석 결과가 없습니다"));

        boolean toDesign = t.isDesignRequested();
        String reason;
        if (!a.isApproved()) {
            a.setApproved(true);
            a.setApprovedBy(adminId);
            a.setApprovedAt(OffsetDateTime.now());
            reason = toDesign ? "관리자 승인 → 디자인 큐 진입" : "관리자 승인 → 구현 큐 진입";
        } else {
            // 이미 approved이지만 status가 COMPLETED인 경우: 구버전 데이터 또는 admin 명시적 재큐잉
            reason = toDesign ? "이미 승인된 작업 → 디자인 큐 재진입" : "이미 승인된 작업 → 구현 큐 재진입";
        }

        TaskStatus from = t.getStatus();
        TaskStatus to = toDesign ? TaskStatus.DESIGN_PENDING : TaskStatus.APPROVED;
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", adminId,
                reason + (to == TaskStatus.DESIGN_PENDING ? " (디자인 구간)" : "")));
        return a;
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
