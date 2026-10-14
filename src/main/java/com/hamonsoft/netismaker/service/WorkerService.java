package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.dto.CloseContext;
import com.hamonsoft.netismaker.dto.CloseProgressRequest;
import com.hamonsoft.netismaker.dto.DeployedTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.InterviewPlanRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 *  워커 API 비즈니스 로직.
 *
 *   heartbeat       ─► worker_heartbeat upsert
 *   claimNextTask   ─► PENDING/APPROVED 작업 1건 atomic claim (SKIP LOCKED)
 *                       PENDING         → IN_PROGRESS  (kind=ANALYSIS)
 *                       APPROVED        → IMPLEMENTING  (kind=IMPLEMENTATION, analysis 동봉)
 *                       DESIGN_PENDING  → DESIGNING     (kind=DESIGN, 이전 디자인/피드백 동봉)
 *                       DEPLOY_PENDING  → DEPLOYING     (kind=DEPLOY)
 *                       UNDEPLOY_PENDING → UNDEPLOYING  (kind=UNDEPLOY)
 *                       CLOSE_PENDING   → CLOSING       (kind=CLOSE, 종료 노트 재료·진행 단계 동봉)
 *   recordResult    ─► 워커가 분석/구현/배포/디자인 완료/실패 보고. worker_id 일치 필수.
 *                       COMPLETED           : task_analysis upsert + status=COMPLETED
 *                       FAILED              : failure_reason + status=FAILED
 *                       PR_CREATED          : pr_url/pr_number/head_* + status=PR_CREATED
 *                       IMPLEMENTATION_FAILED: failure_reason + (있으면)log + status=IMPLEMENTATION_FAILED
 *                       DESIGN_REVIEW       : task_design upsert(반려 이력 보존) + status=DESIGN_REVIEW
 *                       DESIGN_FAILED       : failure_reason + status=DESIGN_FAILED
 *                       CLOSED              : 종료 4단계가 모두 보고된 뒤에만 (status=CLOSED)
 *                       CLOSE_FAILED        : failure_reason + status=CLOSE_FAILED
 *   recordCloseProgress ─► 종료 단계 완료 보고를 task.close_progress에 누적 (멱등)
 */
@Service
@Profile("api")
public class WorkerService {

    /** 종료 노트 재료로 싣는 상태 이력 상한(최신 N건). 본문 길이 상한은 CloseContext가 가진다. */
    private static final int CLOSE_HISTORY_LIMIT = 200;
    /** task.merge_commit_sha / close_note_path 컬럼 길이 — 넘으면 500 대신 400으로 거절. */
    private static final int MAX_COMMIT_SHA_LENGTH = 40;
    private static final int MAX_NOTE_PATH_LENGTH = 500;

    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskDesignRepository designRepo;
    private final RepoCatalogRepository repoCatalogRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final TaskStageUsageRepository stageUsageRepo;
    private final WorkerHeartbeatRepository heartbeatRepo;
    private final DeployLogStreamService deployLogStream;
    private final InterviewSessionRepository sessionRepo;
    private final InterviewPlanRepository planRepo;

    public WorkerService(TaskRepository taskRepo,
                         TaskAnalysisRepository analysisRepo,
                         TaskDesignRepository designRepo,
                         RepoCatalogRepository repoCatalogRepo,
                         TaskStatusHistoryRepository historyRepo,
                         TaskStageUsageRepository stageUsageRepo,
                         WorkerHeartbeatRepository heartbeatRepo,
                         DeployLogStreamService deployLogStream,
                         InterviewSessionRepository sessionRepo,
                         InterviewPlanRepository planRepo) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.designRepo = designRepo;
        this.repoCatalogRepo = repoCatalogRepo;
        this.historyRepo = historyRepo;
        this.stageUsageRepo = stageUsageRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.deployLogStream = deployLogStream;
        this.sessionRepo = sessionRepo;
        this.planRepo = planRepo;
    }

    @Transactional
    public void heartbeat(WorkerHeartbeatRequest req) {
        WorkerHeartbeat h = heartbeatRepo.findById(req.workerId()).orElseGet(() -> {
            WorkerHeartbeat fresh = new WorkerHeartbeat();
            fresh.setWorkerId(req.workerId());
            return fresh;
        });
        h.setHostname(req.hostname());
        h.setVersion(req.version());
        h.setClaudeSessionOk(req.claudeSessionOk());
        h.setVpnStatus(req.vpnStatus());
        h.setMcps(req.mcps() == null ? List.of() : req.mcps());
        h.setLostReportCount(req.lostReportCount() == null ? 0 : req.lostReportCount());
        h.setLastSeenAt(OffsetDateTime.now());
        heartbeatRepo.save(h);
    }

    /**
     * PENDING(분석) 또는 APPROVED(구현) 1건을 atomic claim. 없으면 Optional.empty.
     * SELECT FOR UPDATE SKIP LOCKED. 같은 워커가 분석/구현 큐를 공유 (V1 직렬).
     *
     * 반환된 WorkerTaskResponse.kind를 보고 워커가 분기:
     *  - ANALYSIS       → 기존 분석 흐름 (analysis 필드 null)
     *  - IMPLEMENTATION → worktree 구현 흐름 (analysis 마크다운 + subtasks 동봉)
     */
    @Transactional
    public Optional<WorkerTaskResponse> claimNextTask(String workerId) {
        List<Task> candidates = taskRepo.findClaimableForUpdateSkipLocked(PageRequest.of(0, 1));
        if (candidates.isEmpty()) return Optional.empty();
        Task t = candidates.get(0);
        TaskStatus from = t.getStatus();

        if (from == TaskStatus.PENDING) {
            t.setStatus(TaskStatus.IN_PROGRESS);
        } else if (from == TaskStatus.APPROVED) {
            t.setStatus(TaskStatus.IMPLEMENTING);
        } else if (from == TaskStatus.DESIGN_PENDING) {
            t.setStatus(TaskStatus.DESIGNING);
        } else if (from == TaskStatus.DEPLOY_PENDING) {
            t.setStatus(TaskStatus.DEPLOYING);
        } else if (from == TaskStatus.UNDEPLOY_PENDING) {
            t.setStatus(TaskStatus.UNDEPLOYING);
        } else if (from == TaskStatus.CLOSE_PENDING) {
            t.setStatus(TaskStatus.CLOSING);
        } else {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "claim 후보가 처리 가능한 상태가 아님: " + from);
        }
        t.setWorkerId(workerId);
        t.setClaimedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", workerId, "워커 claim"));

        if (t.getStatus() == TaskStatus.DESIGNING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            TaskDesign prev = designRepo.findById(t.getId()).orElse(null);
            String dsProjectId = null;
            String outProjectId = null;
            if (t.getRepoCatalogId() != null) {
                var cat = repoCatalogRepo.findById(t.getRepoCatalogId()).orElse(null);
                if (cat != null) {
                    dsProjectId = cat.getDesignSystemProjectId();
                    outProjectId = cat.getDesignOutputProjectId();
                }
            }
            return Optional.of(WorkerTaskResponse.forDesign(t, a, prev, dsProjectId, outProjectId));
        }
        if (t.getStatus() == TaskStatus.IMPLEMENTING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            TaskDesign d = designRepo.findById(t.getId()).orElse(null);
            return Optional.of(WorkerTaskResponse.forImplementation(t, a,
                    (d != null && d.isApproved()) ? d : null, registeredPlanMarkdown(t.getId())));
        }
        if (from == TaskStatus.DEPLOY_PENDING) {
            return Optional.of(WorkerTaskResponse.forDeploy(t));
        }
        if (from == TaskStatus.UNDEPLOY_PENDING) {
            return Optional.of(WorkerTaskResponse.forUndeploy(t));
        }
        if (from == TaskStatus.CLOSE_PENDING) {
            return Optional.of(WorkerTaskResponse.forClose(t, closeContext(t)));
        }
        return Optional.of(WorkerTaskResponse.forAnalysis(t));
    }

    /** 종료 노트 재료 — 확정 플랜·분석·구현 로그 끝·이력(시간순)·단계별 비용. 본문 상한은 CloseContext.of. */
    private CloseContext closeContext(Task t) {
        TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
        List<TaskStatusHistory> history = new ArrayList<>(
                historyRepo.findByTaskIdOrderByAtDescIdDesc(t.getId(), Limit.of(CLOSE_HISTORY_LIMIT)));
        Collections.reverse(history); // 시간순(오래된 것 먼저)
        return CloseContext.of(t, registeredPlanMarkdown(t.getId()), a, history,
                stageUsageRepo.findByTaskIdOrderByStageAsc(t.getId()));
    }

    /**
     * 작업을 확정(confirm)한 인터뷰의 플랜 본문. confirm은 task_analysis에 design만 넣으므로(LOCKED CONTRACT v2)
     * 플랜 본문은 interview_plan에서 가져온다. 확정된 세션은 REGISTERED — 취소·만료된 재인터뷰 세션은 건너뛴다.
     * 인터뷰 없이 온 작업(레거시 자동분석)이면 null.
     */
    private String registeredPlanMarkdown(Long taskId) {
        return sessionRepo.findByTaskIdOrderByCreatedAtDesc(taskId).stream()
                .filter(s -> s.getStatus() == InterviewStatus.REGISTERED)
                .findFirst()
                .flatMap(s -> planRepo.findById(s.getId()))
                .map(InterviewPlan::getPlanMarkdown)
                .orElse(null);
    }

    @Transactional
    public void recordResult(Long taskId, WorkerResultRequest req) {
        // 비관적 락(FOR UPDATE) + findActive — ① 결과 보고를 admin 진입점(deploy/undeploy/
        // rejectDesign 등)·claim과 직렬화해 stale 스냅샷 기반 이중 전이/lost update를 막고
        // ② 소프트삭제된 task의 지각 보고(dead-letter replay 포함)가 삭제 row를 변이시키지
        // 않게 한다 — 404는 워커 쪽에서 4xx 영구 거부로 dead-letter에 보존된다.
        Task t = taskRepo.findActiveByIdForUpdate(taskId)
                .orElseThrow(TaskException::notFound);

        TaskStatus current = t.getStatus();

        // 지각 보고 정합화: stale 회수로 IMPLEMENTATION_FAILED가 됐지만 워커가 실제로는
        // PR 생성까지 성공한 경우, 늦게 도착한 PR_CREATED 보고를 받아 정합화한다.
        // (PR 메타가 유효할 때만 — undeploy 복귀(prUrl 없는 PR_CREATED)와 구분됨)
        if (current == TaskStatus.IMPLEMENTATION_FAILED
                && req.status() == TaskStatus.PR_CREATED
                && req.prUrl() != null && !req.prUrl().isBlank()
                && req.headBranch() != null && !req.headBranch().isBlank()) {
            t.setStatus(TaskStatus.PR_CREATED);
            t.setPrUrl(req.prUrl());
            t.setPrNumber(req.prNumber());
            t.setHeadBranch(req.headBranch());
            t.setHeadSha(req.headSha());
            t.setImplementationLog(req.implementationLog());
            applyEnvTemplate(t, req);
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.IMPLEMENTATION_FAILED, TaskStatus.PR_CREATED,
                    "worker", req.workerId(), "지각 보고 정합화: stale 회수 → PR_CREATED"));
            accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            return;
        }

        // 지각 보고 정합화(분석): stale 회수로 FAILED가 됐지만 워커가 실제로는 분석에 성공한 경우.
        // PENDING은 받지 않는다 — stale 회수가 재큐한 PENDING은 재클레임(SKIP LOCKED)과 경합하고,
        // 어차피 재분석이 같은 결과로 수렴하므로 지각 보고로 덮지 않는다.
        // markdownResult가 빈 지각 보고는 분기 불충족 → 아래 in-flight 가드에서 409 (PR_CREATED 관행과 동일).
        if (current == TaskStatus.FAILED
                && req.status() == TaskStatus.COMPLETED
                && req.markdownResult() != null && !req.markdownResult().isBlank()) {
            TaskAnalysis a = TaskAnalysis.create(t.getId(),
                    req.markdownResult(), req.subtasksJson(),
                    req.claudeLog(), req.durationMs());
            analysisRepo.save(a);
            t.setStatus(TaskStatus.COMPLETED);
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.FAILED, TaskStatus.COMPLETED,
                    "worker", req.workerId(), "지각 보고 정합화: stale 회수 → 분석완료"));
            accumulateUsage(t.getId(), TaskStageUsage.STAGE_ANALYSIS, req);
            return;
        }

        // 지각 보고 정합화(배포): stale 회수로 DEPLOY_FAILED가 됐지만 워커가 실제로는 배포에 성공한 경우.
        // DEPLOYING/DEPLOY_PENDING은 받지 않는다(재배포가 진행 중이면 그쪽이 이겨야 함).
        // DEPLOY_LOST도 대상 아님(DEPLOYED에서만 진입하는 관측 상태).
        // 잘못된 정합화(컨테이너가 실제로 죽은 경우)는 DeployReconcileJob(300초 주기)이
        // DEPLOYED↔DEPLOY_LOST 관측 보고로 자가치유한다.
        if (current == TaskStatus.DEPLOY_FAILED
                && req.status() == TaskStatus.DEPLOYED
                && req.deployUrl() != null && !req.deployUrl().isBlank()) {
            t.setStatus(TaskStatus.DEPLOYED);
            t.setDeployUrl(req.deployUrl());
            t.setDeployContainerId(req.deployContainerId());
            t.setDeployHostPort(req.deployHostPort());
            t.setDeployImage(req.deployImage());
            t.setDeployedAt(OffsetDateTime.now());
            t.setDeployLog(req.deployLog());
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.DEPLOY_FAILED, TaskStatus.DEPLOYED,
                    "worker", req.workerId(), "지각 보고 정합화: stale 회수 → 배포완료"));
            // stale 회수가 이미 finish했을 수 있으나 finish는 멱등
            // (emitters.remove → null no-op, deleteByTaskId → 0건 no-op).
            deployLogStream.finish(t.getId());
            return;
        }

        // 지각 보고 정합화(디자인): stale 회수로 DESIGN_PENDING 재큐됐지만 워커가 실제로는
        // 디자인을 완성한 경우 — 늦은 DESIGN_REVIEW 보고를 받아 재생성 사이클(수 분 + claude
        // 비용)을 생략한다. 재큐분을 다른 워커가 이미 claim했으면(DESIGNING) 이 분기 불충족 →
        // 아래 in-flight 가드의 소유 워커 검사로 귀결. 산출물이 유효할 때만 수용한다.
        // (배포중지 지각 PR_CREATED는 정합화하지 않는다 — UNDEPLOY_PENDING 재큐는 docker rm -f가
        //  이미 없는 컨테이너를 무해 통과해 스스로 수렴하고, 여러 사이클 뒤의 초지각 replay를
        //  수용하면 살아있는 컨테이너를 고아로 만들 수 있다.)
        if (current == TaskStatus.DESIGN_PENDING
                && req.status() == TaskStatus.DESIGN_REVIEW
                && req.designMarkdown() != null && !req.designMarkdown().isBlank()
                && req.mockupFilesJson() != null && !req.mockupFilesJson().isBlank()) {
            recordDesignResult(t, req, "지각 보고 정합화: stale 재큐 → 디자인승인대기");
            return;
        }

        if (current != TaskStatus.IN_PROGRESS
                && current != TaskStatus.IMPLEMENTING
                && current != TaskStatus.DESIGNING
                && current != TaskStatus.DEPLOYING
                && current != TaskStatus.UNDEPLOYING
                && current != TaskStatus.CLOSING) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "현재 처리중 상태가 아닙니다 (현재: " + current.dbValue() + ")");
        }
        if (t.getWorkerId() != null && !t.getWorkerId().equals(req.workerId())) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }

        // 종료 단계는 별도 처리 (4단계 완료 검증).
        if (current == TaskStatus.CLOSING) {
            recordCloseResult(t, req);
            return;
        }

        // 배포/중지 단계는 별도 처리 (분석/구현 검증 로직과 분리).
        if (current == TaskStatus.DEPLOYING || current == TaskStatus.UNDEPLOYING) {
            recordDeployResult(t, req);
            return;
        }

        // 디자인 단계도 별도 처리 (분석/구현 검증 로직과 분리).
        if (current == TaskStatus.DESIGNING) {
            recordDesignResult(t, req);
            return;
        }

        // 단계별 허용 상태 검증 — 잘못된 보고를 일찍 차단
        if (current == TaskStatus.IN_PROGRESS
                && req.status() != TaskStatus.COMPLETED
                && req.status() != TaskStatus.FAILED) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "분석 단계에서 허용되지 않는 status: " + req.status());
        }
        if (current == TaskStatus.IMPLEMENTING
                && req.status() != TaskStatus.PR_CREATED
                && req.status() != TaskStatus.IMPLEMENTATION_FAILED) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "구현 단계에서 허용되지 않는 status: " + req.status());
        }

        TaskStatus from = current;

        switch (req.status()) {
            case COMPLETED -> {
                if (req.markdownResult() == null || req.markdownResult().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "분석완료 시 markdownResult 필수");
                }
                TaskAnalysis a = TaskAnalysis.create(t.getId(),
                        req.markdownResult(), req.subtasksJson(),
                        req.claudeLog(), req.durationMs());
                analysisRepo.save(a);
                t.setStatus(TaskStatus.COMPLETED);
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_ANALYSIS, req);
            }
            case FAILED -> {
                t.setStatus(TaskStatus.FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_ANALYSIS, req);
            }
            case PR_CREATED -> {
                if (req.prUrl() == null || req.prUrl().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "PR 생성 시 prUrl 필수");
                }
                if (req.headBranch() == null || req.headBranch().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "PR 생성 시 headBranch 필수");
                }
                t.setStatus(TaskStatus.PR_CREATED);
                t.setPrUrl(req.prUrl());
                t.setPrNumber(req.prNumber());
                t.setHeadBranch(req.headBranch());
                t.setHeadSha(req.headSha());
                t.setImplementationLog(req.implementationLog());
                applyEnvTemplate(t, req);
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            }
            case IMPLEMENTATION_FAILED -> {
                t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                t.setImplementationLog(req.implementationLog());
                // 일부만 진행된 경우라도 (브랜치 push 했지만 PR 실패 등) 가능한 메타 저장
                if (req.headBranch() != null) t.setHeadBranch(req.headBranch());
                if (req.headSha() != null) t.setHeadSha(req.headSha());
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "허용되지 않은 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());

        String reason = switch (t.getStatus()) {
            case FAILED, IMPLEMENTATION_FAILED -> t.getFailureReason();
            case PR_CREATED -> "PR 생성: " + t.getPrUrl();
            case COMPLETED -> "분석 완료";
            default -> "처리 완료";
        };
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
    }

    /** 구현 성공 보고의 배포 env 템플릿 저장 — null(구버전 워커)이면 기존 값 유지 (스펙 2026-10-07 §5). */
    private static void applyEnvTemplate(Task t, WorkerResultRequest req) {
        if (req.envTemplate() != null) t.setEnvTemplate(EnvTemplateItem.sanitize(req.envTemplate()));
    }

    private void recordDeployResult(Task t, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case DEPLOYED -> {
                if (req.deployUrl() == null || req.deployUrl().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "배포완료 시 deployUrl 필수");
                }
                t.setStatus(TaskStatus.DEPLOYED);
                t.setFailureReason(null);
                t.setDeployUrl(req.deployUrl());
                t.setDeployContainerId(req.deployContainerId());
                t.setDeployHostPort(req.deployHostPort());
                t.setDeployImage(req.deployImage());
                t.setDeployedAt(OffsetDateTime.now());
                t.setDeployLog(req.deployLog());
                reason = "배포 완료: " + req.deployUrl();
            }
            case DEPLOY_FAILED -> {
                t.setStatus(TaskStatus.DEPLOY_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                t.setDeployLog(req.deployLog());
                reason = t.getFailureReason();
            }
            case PR_CREATED -> {
                t.setStatus(TaskStatus.PR_CREATED);
                t.setFailureReason(null);
                t.setDeployUrl(null);
                t.setDeployContainerId(null);
                t.setDeployHostPort(null);
                t.setDeployImage(null);
                t.setDeployedAt(null);
                t.setDeployLog(req.deployLog());
                reason = "배포 중지 → PR생성 복귀";
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "배포 단계에서 허용되지 않는 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
        // 배포/중지 종료 → 스트림 구독자에 done 통지 + 청크 정리
        deployLogStream.finish(t.getId());
    }

    private void recordDesignResult(Task t, WorkerResultRequest req) {
        recordDesignResult(t, req, null);
    }

    /** reasonOverride: 지각 보고 정합화처럼 이력 사유를 구분해야 할 때만 지정 (null=기본 사유). */
    private void recordDesignResult(Task t, WorkerResultRequest req, String reasonOverride) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case DESIGN_REVIEW -> {
                if (req.designMarkdown() == null || req.designMarkdown().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "디자인 완료 시 designMarkdown 필수");
                }
                if (req.mockupFilesJson() == null || req.mockupFilesJson().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "디자인 완료 시 mockupFilesJson 필수");
                }
                TaskDesign d = designRepo.findById(t.getId()).orElse(null);
                if (d == null) {
                    d = TaskDesign.create(t.getId(), req.designMarkdown(), req.mockupFilesJson(),
                            req.designProjectId(), req.designUrl(), req.claudeLog(), req.durationMs());
                } else {
                    // 반려 재실행: feedback_history/reject_count 보존, 산출물만 갱신
                    d.setDesignMarkdown(req.designMarkdown());
                    d.setMockupFilesJson(req.mockupFilesJson());
                    if (req.designProjectId() != null) d.setDesignProjectId(req.designProjectId());
                    d.setDesignUrl(req.designUrl());
                    d.setClaudeLog(req.claudeLog());
                    d.setDurationMs(req.durationMs());
                    d.setCompletedAt(OffsetDateTime.now());
                }
                designRepo.save(d);
                // 출력 프로젝트 최초 생성 시 카탈로그에 박제 (이후 작업이 재사용)
                if (req.designProjectId() != null && t.getRepoCatalogId() != null) {
                    repoCatalogRepo.findById(t.getRepoCatalogId())
                            .filter(c -> c.getDesignOutputProjectId() == null)
                            .ifPresent(c -> {
                                c.setDesignOutputProjectId(req.designProjectId());
                                c.setUpdatedAt(OffsetDateTime.now());
                            });
                }
                t.setStatus(TaskStatus.DESIGN_REVIEW);
                reason = reasonOverride != null ? reasonOverride : "디자인 생성 완료 → 승인 대기";
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_DESIGN, req);
            }
            case DESIGN_FAILED -> {
                t.setStatus(TaskStatus.DESIGN_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                reason = t.getFailureReason();
                accumulateUsage(t.getId(), TaskStageUsage.STAGE_DESIGN, req);
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "디자인 단계에서 허용되지 않는 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
    }

    /**
     * 종료 단계 진행 보고 — 끝난 CloseStep을 task.close_progress에 누적하고 단계별 값을 저장한다.
     * 같은 단계를 다시 보고하거나 값이 null이어도 무해(멱등). 재claim한 워커는 이 목록의 단계를 건너뛴다.
     */
    @Transactional
    public void recordCloseProgress(Long taskId, CloseProgressRequest req) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.CLOSING) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "종료중 상태가 아닙니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        if (!req.workerId().equals(t.getWorkerId())) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }
        switch (req.step()) {
            case MERGED -> {
                if (req.mergeCommitSha() != null && !req.mergeCommitSha().isBlank()) {
                    if (req.mergeCommitSha().length() > MAX_COMMIT_SHA_LENGTH) {
                        throw new TaskException(HttpStatus.BAD_REQUEST, "mergeCommitSha가 너무 깁니다");
                    }
                    t.setMergeCommitSha(req.mergeCommitSha());
                }
            }
            case UNDEPLOYED -> {
                // 컨테이너를 내렸으니 배포 런타임 정보를 비운다 (undeploy→PR_CREATED 복귀와 같은 규칙; 배포 로그는 보존)
                t.setDeployUrl(null);
                t.setDeployContainerId(null);
                t.setDeployHostPort(null);
                t.setDeployImage(null);
                t.setDeployedAt(null);
            }
            case ARTIFACT_UPLOADED -> {
                // 파일이 먼저 저장돼 있어야 한다 — PUT /worker/tasks/{id}/artifact (스펙 2026-10-14 §5)
                if (t.getArtifactName() == null) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "산출물이 아직 저장되지 않았습니다");
                }
            }
            case NOTE_WRITTEN -> {
                if (req.notePath() != null && !req.notePath().isBlank()) {
                    if (req.notePath().length() > MAX_NOTE_PATH_LENGTH) {
                        throw new TaskException(HttpStatus.BAD_REQUEST, "notePath가 너무 깁니다");
                    }
                    t.setCloseNotePath(req.notePath());
                }
            }
            case BRANCH_DELETED -> { /* 기록만 */ }
        }
        t.setCloseProgress(CloseStep.append(t.getCloseProgress(), req.step()));
        t.setUpdatedAt(OffsetDateTime.now());
    }

    /** 종료 결과 보고. CLOSED는 이 작업에 필요한 단계(CloseStep.required)가 모두 보고된 뒤에만 받는다 — 단계가 빠진 채 끝났다고 믿지 않는다. */
    private void recordCloseResult(Task t, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case CLOSED -> {
                if (!CloseStep.allDone(t.getCloseProgress(), t.getCloseArtifactMode())) {
                    throw new TaskException(HttpStatus.BAD_REQUEST,
                            "종료 단계가 모두 끝나지 않았습니다 (끝난 단계: "
                                    + (t.getCloseProgress() == null ? "없음" : t.getCloseProgress()) + ")");
                }
                t.setStatus(TaskStatus.CLOSED);
                t.setFailureReason(null);
                String sha = t.getMergeCommitSha();
                reason = "작업 종료" + (sha == null || sha.isBlank() ? ""
                        : ": 병합 " + sha.substring(0, Math.min(7, sha.length())));
            }
            case CLOSE_FAILED -> {
                t.setStatus(TaskStatus.CLOSE_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                reason = t.getFailureReason();
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "종료 단계에서 허용되지 않는 status: " + req.status());
        }
        accumulateUsage(t.getId(), TaskStageUsage.STAGE_CLOSE, req);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
    }

    /** 워커가 배포 중 올리는 증분 로그 청크. 상태 검증 없이 best-effort 영속/중계. */
    @Transactional
    public void appendDeployLog(Long taskId, com.hamonsoft.netismaker.dto.DeployLogChunkRequest req) {
        deployLogStream.ingestChunk(taskId, req.seq() == null ? 0 : req.seq(), req.content());
    }

    /** 배포 런타임 정합 대상(배포완료 + 배포중단됨) 목록. 워커 DeployReconcileJob이 주기 조회. */
    @Transactional(readOnly = true)
    public List<DeployedTaskSummary> listDeployReconcilable() {
        return taskRepo.findDeployReconcilable().stream()
                .map(t -> new DeployedTaskSummary(t.getId(), t.getStatus()))
                .toList();
    }

    /** worktree 정리 잡 보호 목록(구현중·디자인중·배포중·배포중지중, soft-delete 제외). 워커 WorktreeCleanupJob이 회차마다 조회. */
    @Transactional(readOnly = true)
    public List<ActiveWorktreeTaskSummary> listWorktreeActive() {
        return taskRepo.findWorktreeActive().stream()
                .map(t -> new ActiveWorktreeTaskSummary(t.getId(), t.getStatus()))
                .toList();
    }

    /**
     * 워커의 컨테이너 런타임 관측 반영.
     *
     *  배포완료   + running=false → 배포중단됨 (컨테이너 소실 감지)
     *  배포중단됨 + running=true  → 배포완료 (컨테이너 복구 감지)
     *
     * 그 외 조합은 no-op — 관측·보고 사이에 재배포/중지가 시작됐을 수 있으므로
     * (관측 시점의 스냅샷이 최신 상태를 덮어쓰지 않게) 조용히 무시한다.
     */
    @Transactional
    public void recordRuntimeStatus(Long taskId, WorkerRuntimeStatusRequest req) {
        // findActiveByIdForUpdate: ① 관측 목록 수신 후 삭제된 task의 지각 보고가 삭제 row를
        // 변이시키지 않게(findActive) ② 관측·보고 사이에 시작된 재배포/중지(FOR UPDATE 보유)의
        // 커밋을 기다렸다가 갱신된 상태로 재판정 — 무락이면 stale 스냅샷(DEPLOYED)이
        // DEPLOY_PENDING을 DEPLOY_LOST로 되덮는 lost update가 가능하다.
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        TaskStatus from = t.getStatus();
        boolean running = Boolean.TRUE.equals(req.running());

        if (from == TaskStatus.DEPLOYED && !running) {
            t.setStatus(TaskStatus.DEPLOY_LOST);
            historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DEPLOY_LOST,
                    "system", req.workerId(),
                    "컨테이너 소실 감지" + (req.detail() == null ? "" : ": " + req.detail())));
        } else if (from == TaskStatus.DEPLOY_LOST && running) {
            t.setStatus(TaskStatus.DEPLOYED);
            historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DEPLOYED,
                    "system", req.workerId(),
                    "컨테이너 복구 감지" + (req.detail() == null ? "" : ": " + req.detail())));
        } else {
            return; // 상태가 이미 진행됨(재배포/중지 등) — 관측 스냅샷 무시
        }
        t.setUpdatedAt(OffsetDateTime.now());
    }

    /** 보고 usage를 stage에 누적. usage 없음/전부 0이면 no-op (스펙 §7). */
    private void accumulateUsage(Long taskId, String stage, WorkerResultRequest req) {
        WorkerResultRequest.UsageReport u = req.usage();
        if (u == null || u.isEmpty()) return;
        stageUsageRepo.accumulate(taskId, stage,
                u.costUsd() == null ? java.math.BigDecimal.ZERO : u.costUsd(),
                nz(u.inputTokens()), nz(u.outputTokens()),
                nz(u.cacheCreationTokens()), nz(u.cacheReadTokens()));
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
