package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

    private final TaskRepository taskRepo;
    private final TaskAnalysisRepository analysisRepo;
    private final TaskStatusHistoryRepository historyRepo;

    @Value("${app.task.user-concurrent-limit:5}")
    private int userConcurrentLimit;

    @Value("${app.task.max-retry:3}")
    private int maxRetry;

    public TaskService(TaskRepository taskRepo,
                       TaskAnalysisRepository analysisRepo,
                       TaskStatusHistoryRepository historyRepo) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
    }

    @Transactional
    public Task create(TaskCreateRequest req, String requesterId) {
        long active = taskRepo.countActiveByRequester(requesterId);
        if (active >= userConcurrentLimit) {
            throw TaskException.tooManyRequests(
                    "동시에 보유할 수 있는 미완료 작업 한도(" + userConcurrentLimit + ")를 초과했습니다");
        }
        Task t = Task.create(req.githubRepo(), req.githubBranch(), req.title(),
                             req.description(), requesterId, maxRetry);
        Task saved = taskRepo.save(t);
        historyRepo.save(TaskStatusHistory.log(saved.getId(), null, TaskStatus.PENDING,
                "user", requesterId, "작업 등록"));
        return saved;
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
        t.setDeletedAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(),
                isAdmin ? "system" : "user", actorId, "삭제"));
    }

    @Transactional
    public TaskAnalysis approve(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.COMPLETED) {
            throw TaskException.conflict("분석완료 상태에서만 승인할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskAnalysis a = analysisRepo.findById(taskId)
                .orElseThrow(() -> TaskException.conflict("분석 결과가 없습니다"));
        if (a.isApproved()) {
            throw TaskException.conflict("이미 승인된 작업입니다");
        }
        a.setApproved(true);
        a.setApprovedBy(adminId);
        a.setApprovedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(),
                "user", adminId, "관리자 승인"));
        return a;
    }

    @Transactional
    public Task retry(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) {
            throw TaskException.forbidden();
        }
        if (t.getStatus() != TaskStatus.FAILED) {
            throw TaskException.conflict("분석실패 상태에서만 재시도할 수 있습니다 (현재: "
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
}
