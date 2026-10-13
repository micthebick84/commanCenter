package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * 작업 종료 단계의 사람 쪽 전이 (스펙 2026-10-13 §3·§5). 워커 쪽(claim·진행·결과)은 WorkerService.
 *
 *   requestClose ─► 배포완료|배포중단됨 → 종료승인대기   (등록자 본인 또는 관리자)
 *   withdraw     ─► 종료승인대기 → 직전 상태             (등록자 본인 또는 관리자)
 *   approve      ─► 종료승인대기 → 종료대기              (관리자 — 컨트롤러가 강제)
 *   reject       ─► 종료승인대기 → 직전 상태 + 사유      (관리자)
 *   retry        ─► 종료실패 → 종료대기 (close_progress 유지 → 남은 단계부터)  (관리자)
 *
 * 락 정책은 TaskService와 같다 — findActiveByIdForUpdate 후 재판정.
 */
@Service
@Profile("api")
public class TaskCloseService {

    private static final Set<TaskStatus> REQUESTABLE = EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST);

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;

    public TaskCloseService(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
    }

    @Transactional
    public Task requestClose(Long taskId, String actorId, boolean isAdmin) {
        Task t = lockOwned(taskId, actorId, isAdmin);
        if (!REQUESTABLE.contains(t.getStatus())) {
            throw TaskException.conflict("배포완료 또는 배포중단됨 상태에서만 종료를 요청할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (t.getPrNumber() == null || isBlank(t.getHeadBranch()) || isBlank(t.getHeadSha())) {
            throw TaskException.conflict("PR 정보(번호·브랜치·커밋)가 없어 종료할 수 없습니다");
        }
        return move(t, TaskStatus.CLOSE_REVIEW, "user", actorId, "결과 확인 완료 → 종료 요청");
    }

    @Transactional
    public Task withdraw(Long taskId, String actorId, boolean isAdmin) {
        Task t = lockOwned(taskId, actorId, isAdmin);
        requireReview(t, "철회");
        return move(t, statusBeforeReview(t.getId()), "user", actorId, "종료 요청 철회");
    }

    @Transactional
    public Task approve(Long taskId, String adminId) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        requireReview(t, "승인");
        t.setFailureReason(null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId, "관리자 종료 승인 → 종료 큐 진입");
    }

    @Transactional
    public Task reject(Long taskId, String adminId, String reason) {
        if (isBlank(reason)) throw new TaskException(HttpStatus.BAD_REQUEST, "반려 사유는 필수입니다");
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        requireReview(t, "반려");
        return move(t, statusBeforeReview(t.getId()), "user", adminId, "종료 반려: " + reason.trim());
    }

    @Transactional
    public Task retry(Long taskId, String adminId) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.CLOSE_FAILED) {
            throw TaskException.conflict("종료실패 상태에서만 재시도할 수 있습니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        t.setFailureReason(null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId,
                "종료 재시도 (완료 단계: " + (t.getCloseProgress() == null ? "없음" : t.getCloseProgress()) + ")");
    }

    /**
     * 종료승인대기로 들어오기 직전 상태. 배포완료·배포중단됨만 돌려준다 — 이력을 못 읽으면 컨테이너 생존을 장담할 수
     * 없으므로 배포중단됨(재배포를 유도하는 쪽)으로 돌린다. DeployReconcileJob이 살아 있으면 배포완료로 되돌린다.
     */
    TaskStatus statusBeforeReview(Long taskId) {
        for (TaskStatusHistory h : historyRepo.findByTaskIdOrderByAtDescIdDesc(taskId, Limit.of(50))) {
            if (TaskStatus.CLOSE_REVIEW.dbValue().equals(h.getToStatus()) && h.getFromStatus() != null) {
                try {
                    TaskStatus from = TaskStatus.fromDb(h.getFromStatus());
                    return REQUESTABLE.contains(from) ? from : TaskStatus.DEPLOY_LOST;
                } catch (IllegalArgumentException e) {
                    return TaskStatus.DEPLOY_LOST;
                }
            }
        }
        return TaskStatus.DEPLOY_LOST;
    }

    private Task lockOwned(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) throw TaskException.forbidden();
        return t;
    }

    private static void requireReview(Task t, String verb) {
        if (t.getStatus() != TaskStatus.CLOSE_REVIEW) {
            throw TaskException.conflict("종료승인대기 상태에서만 " + verb + "할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
    }

    private Task move(Task t, TaskStatus to, String actorType, String actorId, String reason) {
        TaskStatus from = t.getStatus();
        t.setStatus(to);
        t.setWorkerId(null);
        t.setClaimedAt(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, actorType, actorId, reason));
        return t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
