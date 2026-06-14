package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 *  워커 API 비즈니스 로직.
 *
 *   heartbeat       ─► worker_heartbeat upsert
 *   claimNextTask   ─► PENDING/APPROVED 작업 1건 atomic claim (SKIP LOCKED)
 *                       PENDING         → IN_PROGRESS  (kind=ANALYSIS)
 *                       APPROVED        → IMPLEMENTING  (kind=IMPLEMENTATION, analysis 동봉)
 *                       DEPLOY_PENDING  → DEPLOYING     (kind=DEPLOY)
 *                       UNDEPLOY_PENDING → UNDEPLOYING  (kind=UNDEPLOY)
 *   recordResult    ─► 워커가 분석/구현/배포 완료/실패 보고. worker_id 일치 필수.
 *                       COMPLETED           : task_analysis upsert + status=COMPLETED
 *                       FAILED              : failure_reason + status=FAILED
 *                       PR_CREATED          : pr_url/pr_number/head_* + status=PR_CREATED
 *                       IMPLEMENTATION_FAILED: failure_reason + (있으면)log + status=IMPLEMENTATION_FAILED
 */
@Service
@Profile("api")
public class WorkerService {

    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final WorkerHeartbeatRepository heartbeatRepo;
    private final DeployLogStreamService deployLogStream;

    public WorkerService(TaskRepository taskRepo,
                         TaskAnalysisRepository analysisRepo,
                         TaskStatusHistoryRepository historyRepo,
                         WorkerHeartbeatRepository heartbeatRepo,
                         DeployLogStreamService deployLogStream) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.deployLogStream = deployLogStream;
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
        } else if (from == TaskStatus.DEPLOY_PENDING) {
            t.setStatus(TaskStatus.DEPLOYING);
        } else if (from == TaskStatus.UNDEPLOY_PENDING) {
            t.setStatus(TaskStatus.UNDEPLOYING);
        } else {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "claim 후보가 처리 가능한 상태가 아님: " + from);
        }
        t.setWorkerId(workerId);
        t.setClaimedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", workerId, "워커 claim"));

        if (t.getStatus() == TaskStatus.IMPLEMENTING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            return Optional.of(WorkerTaskResponse.forImplementation(t, a));
        }
        if (from == TaskStatus.DEPLOY_PENDING) {
            return Optional.of(WorkerTaskResponse.forDeploy(t));
        }
        if (from == TaskStatus.UNDEPLOY_PENDING) {
            return Optional.of(WorkerTaskResponse.forUndeploy(t));
        }
        return Optional.of(WorkerTaskResponse.forAnalysis(t));
    }

    @Transactional
    public void recordResult(Long taskId, WorkerResultRequest req) {
        Task t = taskRepo.findById(taskId)
                .orElseThrow(TaskException::notFound);

        TaskStatus current = t.getStatus();
        if (current != TaskStatus.IN_PROGRESS
                && current != TaskStatus.IMPLEMENTING
                && current != TaskStatus.DEPLOYING
                && current != TaskStatus.UNDEPLOYING) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "현재 처리중 상태가 아닙니다 (현재: " + current.dbValue() + ")");
        }
        if (t.getWorkerId() != null && !t.getWorkerId().equals(req.workerId())) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }

        // 배포/중지 단계는 별도 처리 (분석/구현 검증 로직과 분리).
        if (current == TaskStatus.DEPLOYING || current == TaskStatus.UNDEPLOYING) {
            recordDeployResult(t, req);
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
            }
            case FAILED -> {
                t.setStatus(TaskStatus.FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
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
            }
            case IMPLEMENTATION_FAILED -> {
                t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                t.setImplementationLog(req.implementationLog());
                // 일부만 진행된 경우라도 (브랜치 push 했지만 PR 실패 등) 가능한 메타 저장
                if (req.headBranch() != null) t.setHeadBranch(req.headBranch());
                if (req.headSha() != null) t.setHeadSha(req.headSha());
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

    private void recordDeployResult(Task t, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case DEPLOYED -> {
                if (req.deployUrl() == null || req.deployUrl().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "배포완료 시 deployUrl 필수");
                }
                t.setStatus(TaskStatus.DEPLOYED);
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

    /** 워커가 배포 중 올리는 증분 로그 청크. 상태 검증 없이 best-effort 영속/중계. */
    @Transactional
    public void appendDeployLog(Long taskId, com.hamonsoft.netismaker.dto.DeployLogChunkRequest req) {
        deployLogStream.ingestChunk(taskId, req.seq() == null ? 0 : req.seq(), req.content());
    }
}
