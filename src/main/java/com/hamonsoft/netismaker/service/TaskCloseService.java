package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
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
 *   cancel       ─► 종료실패(MERGED 전) → 직전 상태, close_progress·사유 비움  (관리자)
 *   skipArtifact ─► 종료실패(산출물 단계에서 멈춤) → 종료대기, 모드 SKIPPED  (관리자, 스펙 2026-10-14 §3)
 *
 * 락 정책은 TaskService와 같다 — findActiveByIdForUpdate 후 재판정.
 */
@Service
@Profile("api")
public class TaskCloseService {

    private static final Set<TaskStatus> REQUESTABLE = EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST);

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final RepoCatalogRepository catalogRepo;
    private final CloseArtifactProperties artifactProps;

    public TaskCloseService(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo,
                            RepoCatalogRepository catalogRepo, CloseArtifactProperties artifactProps) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.catalogRepo = catalogRepo;
        this.artifactProps = artifactProps;
    }

    @Transactional
    public Task requestClose(Long taskId, String actorId, boolean isAdmin) {
        return requestClose(taskId, actorId, isAdmin, null);
    }

    /** actorEmail: 요청자의 JWT email. 등록자 본인의 요청이면 수신 주소를 갱신한다 (스펙 2026-10-14 §7). */
    @Transactional
    public Task requestClose(Long taskId, String actorId, boolean isAdmin, String actorEmail) {
        Task t = lockOwned(taskId, actorId, isAdmin);
        if (!REQUESTABLE.contains(t.getStatus())) {
            throw TaskException.conflict("배포완료 또는 배포중단됨 상태에서만 종료를 요청할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (t.getPrNumber() == null || isBlank(t.getHeadBranch()) || isBlank(t.getHeadSha())) {
            throw TaskException.conflict("PR 정보(번호·브랜치·커밋)가 없어 종료할 수 없습니다");
        }
        if (actorEmail != null && t.isOwnedBy(actorId)) t.setRequesterEmail(actorEmail);
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
        // 산출물 모드는 승인 때 한 번 정한다 — 재시도·stale 재큐는 바꾸지 않는다 (스펙 2026-10-14 §3)
        t.setCloseArtifactMode(artifactRequired(t) ? CloseStep.ARTIFACT_REQUIRED : null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId, "관리자 종료 승인 → 종료 큐 진입"
                + (t.getCloseArtifactMode() == null ? "" : " (산출물 보관)"));
    }

    /** 전역 스위치 + 작업 레포의 카탈로그 항목(repo_catalog_id, 없으면 git_url)이 둘 다 켜졌을 때만. */
    boolean artifactRequired(Task t) {
        return artifactProps.enabled()
                && RepoCatalogRepository.findForTask(catalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                        .map(RepoCatalogEntry::isArtifactEnabled).orElse(false);
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
     * 병합 전 단계에서 실패한 종료를 취소한다. 병합이 첫 단계라 MERGED가 없으면 되돌릴 수 없는 일은 아직 없다 —
     * 진행·실패 사유를 비우고 종료 요청 직전 상태(배포완료|배포중단됨)로 돌린다. 병합된 뒤에는 재시도만 허용.
     */
    @Transactional
    public Task cancel(Long taskId, String adminId) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.CLOSE_FAILED) {
            throw TaskException.conflict("종료실패 상태에서만 종료를 취소할 수 있습니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        if (CloseStep.parse(t.getCloseProgress()).contains(CloseStep.MERGED)) {
            throw TaskException.conflict("이미 병합되어 종료를 취소할 수 없습니다 — 재시도로 마무리하세요");
        }
        t.setFailureReason(null);
        t.setCloseProgress(null);
        return move(t, statusBeforeReview(t.getId()), "user", adminId, "종료 취소(병합 전)");
    }

    /**
     * 산출물 단계에서 멈춘 종료실패를 산출물 없이 이어 간다 (스펙 2026-10-14 §3). 병합 뒤라 종료 취소(409)가 안 되고,
     * 이미지가 아주 없어지면 재시도도 성공할 수 없어서 둔 출구. 워커는 다음 claim에서 노트·브랜치 정리만 한다.
     */
    @Transactional
    public Task skipArtifact(Long taskId, String adminId, String reason) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.CLOSE_FAILED) {
            throw TaskException.conflict("종료실패 상태에서만 산출물 없이 마무리할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        EnumSet<CloseStep> done = CloseStep.parse(t.getCloseProgress());
        if (!CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())
                || !done.contains(CloseStep.MERGED) || !done.contains(CloseStep.UNDEPLOYED)
                || done.contains(CloseStep.ARTIFACT_UPLOADED)) {
            throw TaskException.conflict("산출물 단계에서 멈춘 종료만 산출물 없이 마무리할 수 있습니다");
        }
        t.setCloseArtifactMode(CloseStep.ARTIFACT_SKIPPED);
        t.setFailureReason(null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId,
                "산출물 없이 마무리" + (isBlank(reason) ? "" : ": " + reason.trim()));
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
