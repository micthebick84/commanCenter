package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
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
 *   claimNextTask   ─► PENDING 작업 1건 atomic claim (SKIP LOCKED)
 *                       → status=IN_PROGRESS, worker_id, claimed_at
 *   recordResult    ─► 워커가 분석 완료/실패 보고
 *                       COMPLETED: task_analysis upsert + status=COMPLETED
 *                       FAILED:    failure_reason 기록 + status=FAILED
 *                       자기가 잡은 작업만 가능 (worker_id 일치)
 */
@Service
@Profile("api")
public class WorkerService {

    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final WorkerHeartbeatRepository heartbeatRepo;

    public WorkerService(TaskRepository taskRepo,
                         TaskAnalysisRepository analysisRepo,
                         TaskStatusHistoryRepository historyRepo,
                         WorkerHeartbeatRepository heartbeatRepo) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
        this.heartbeatRepo = heartbeatRepo;
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
        h.setLastSeenAt(OffsetDateTime.now());
        heartbeatRepo.save(h);
    }

    /**
     * PENDING 작업 1건을 atomic claim. 없으면 Optional.empty.
     * SELECT FOR UPDATE SKIP LOCKED로 동시 워커 안전.
     */
    @Transactional
    public Optional<Task> claimNextTask(String workerId) {
        List<Task> candidates = taskRepo.findPendingForUpdateSkipLocked(PageRequest.of(0, 1));
        if (candidates.isEmpty()) return Optional.empty();
        Task t = candidates.get(0);
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.IN_PROGRESS);
        t.setWorkerId(workerId);
        t.setClaimedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.IN_PROGRESS,
                "worker", workerId, "워커 claim"));
        return Optional.of(t);
    }

    @Transactional
    public void recordResult(Long taskId, WorkerResultRequest req) {
        Task t = taskRepo.findById(taskId)
                .orElseThrow(TaskException::notFound);

        // 자기가 잡은 작업만 결과 업로드 가능
        if (!t.getStatus().equals(TaskStatus.IN_PROGRESS)) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "현재 분석중 상태가 아닙니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        if (t.getWorkerId() != null && !t.getWorkerId().equals(req.workerId())) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }

        TaskStatus from = t.getStatus();

        if (req.status() == TaskStatus.COMPLETED) {
            if (req.markdownResult() == null || req.markdownResult().isBlank()) {
                throw new TaskException(HttpStatus.BAD_REQUEST,
                        "분석완료 시 markdownResult 필수");
            }
            TaskAnalysis a = TaskAnalysis.create(t.getId(),
                    req.markdownResult(), req.subtasksJson(),
                    req.claudeLog(), req.durationMs());
            analysisRepo.save(a);
            t.setStatus(TaskStatus.COMPLETED);
        } else if (req.status() == TaskStatus.FAILED) {
            t.setStatus(TaskStatus.FAILED);
            t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
        } else {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "허용되지 않은 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(),
                t.getStatus() == TaskStatus.FAILED ? t.getFailureReason() : "분석 완료"));
    }
}
