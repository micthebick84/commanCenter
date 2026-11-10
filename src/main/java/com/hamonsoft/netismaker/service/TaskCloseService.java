package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.TaskType;
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
 *                   DB 작업은 PR생성에서도 가능 (스펙 2026-11-03 §6.9)
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

    /** 타입과 상관없이 종료를 요청할 수 있는 상태. DB 작업의 PR생성은 {@link #canRequestClose}가 더한다. */
    private static final Set<TaskStatus> REQUESTABLE = EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST);

    /** 종료승인대기에서 돌아갈 수 있는 직전 상태 후보 — 종료 요청을 받는 상태 전부(DB 작업의 PR생성 포함). */
    private static final Set<TaskStatus> RETURNABLE =
            EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, TaskStatus.PR_CREATED);

    private static final String REQUEST_GATE_MESSAGE =
            "배포완료 또는 배포중단됨 상태에서만 종료를 요청할 수 있습니다 (DB 작업은 PR생성에서도 가능)";

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final RepoCatalogRepository catalogRepo;
    private final CloseArtifactProperties artifactProps;
    private final ClosePatchProperties patchProps;

    public TaskCloseService(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo,
                            RepoCatalogRepository catalogRepo, CloseArtifactProperties artifactProps,
                            ClosePatchProperties patchProps) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.catalogRepo = catalogRepo;
        this.artifactProps = artifactProps;
        this.patchProps = patchProps;
    }

    @Transactional
    public Task requestClose(Long taskId, String actorId, boolean isAdmin) {
        return requestClose(taskId, actorId, isAdmin, null);
    }

    /** actorEmail: 요청자의 JWT email. 등록자 본인의 요청이면 수신 주소를 갱신한다 (스펙 2026-10-14 §7). */
    @Transactional
    public Task requestClose(Long taskId, String actorId, boolean isAdmin, String actorEmail) {
        Task t = lockOwned(taskId, actorId, isAdmin);
        if (!canRequestClose(t)) {
            throw TaskException.conflict(REQUEST_GATE_MESSAGE + " (현재: " + t.getStatus().dbValue() + ")");
        }
        if (t.getPrNumber() == null || isBlank(t.getHeadBranch()) || isBlank(t.getHeadSha())) {
            throw TaskException.conflict("PR 정보(번호·브랜치·커밋)가 없어 종료할 수 없습니다");
        }
        if (actorEmail != null && t.isOwnedBy(actorId)) t.setRequesterEmail(actorEmail);
        return move(t, TaskStatus.CLOSE_REVIEW, "user", actorId, "결과 확인 완료 → 종료 요청");
    }

    /**
     * 종료 요청을 받는 작업인가 — 배포완료·배포중단됨, 그리고 타입이 DB 작업인 PR생성(스펙 2026-11-03 §6.9).
     * 수정 요청의 후보({@code TaskFixService.REQUESTABLE})와는 별개라 거기는 바뀌지 않는다.
     */
    static boolean canRequestClose(Task t) {
        return REQUESTABLE.contains(t.getStatus())
                || (t.getStatus() == TaskStatus.PR_CREATED && t.getTaskType() == TaskType.DB_WORK);
    }

    /**
     * 배포한 흔적이 없는 작업인가 — 배포 URL도 배포 시각도 비어 있다. 배포가 끝나면 둘 다 채워지고(WorkerService), 배포를
     * 중지하면 둘 다 비워지므로(중지 뒤 PR생성) 구현 직후나 배포 중지 뒤의 PR생성은 늘 참이다.
     * 종료 단계에서 배포 중지를 건너뛰는 워커 쪽 판정({@code TaskCloser.neverDeployed})과 기준이 같아야 한다.
     */
    static boolean neverDeployed(Task t) {
        return t.getDeployUrl() == null && t.getDeployedAt() == null;
    }

    @Transactional
    public Task withdraw(Long taskId, String actorId, boolean isAdmin) {
        Task t = lockOwned(taskId, actorId, isAdmin);
        requireReview(t, "철회");
        return move(t, statusBeforeReview(t), "user", actorId, "종료 요청 철회");
    }

    @Transactional
    public Task approve(Long taskId, String adminId) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        requireReview(t, "승인");
        t.setFailureReason(null);
        // 산출물 모드는 승인 때 한 번 정한다 — 재시도·stale 재큐는 바꾸지 않는다 (스펙 2026-10-14 §3)
        t.setCloseArtifactMode(artifactRequired(t) ? CloseStep.ARTIFACT_REQUIRED : null);
        t.setClosePatchMode(patchRequired(t) ? CloseStep.PATCH_REQUIRED : null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId, "관리자 종료 승인 → 종료 큐 진입"
                + (t.getCloseArtifactMode() == null ? "" : " (산출물 보관)")
                + (t.getClosePatchMode() == null ? "" : " (부분 패치)"));
    }

    /**
     * 전역 스위치 + 작업 레포의 카탈로그 항목(repo_catalog_id, 없으면 git_url)이 둘 다 켜졌을 때만.
     * 단 DB 작업이 배포한 적 없으면 늘 꺼진 것 — 꺼낼 배포 이미지가 없다(스펙 2026-11-03 §6.9). 보조 '배포'로 배포까지 한
     * DB 작업은 다른 작업과 같이 스위치·카탈로그를 따른다.
     */
    boolean artifactRequired(Task t) {
        if (t.getTaskType() == TaskType.DB_WORK && neverDeployed(t)) return false;
        return artifactProps.enabled()
                && RepoCatalogRepository.findForTask(catalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                        .map(RepoCatalogEntry::isArtifactEnabled).orElse(false);
    }

    /**
     * 부분 패치 대상 판정 (스펙 2026-11-10): 전역 스위치 + 카탈로그 patchEnabled + 미리 빌드 명령 + DB 작업 아님.
     */
    boolean patchRequired(Task t) {
        if (!patchProps.enabled() || t.getTaskType() == TaskType.DB_WORK) return false;
        return RepoCatalogRepository.findForTask(catalogRepo, t.getRepoCatalogId(), t.getGitUrl())
                .map(e -> e.isPatchEnabled() && !isBlank(e.getPrebuildCommand())).orElse(false);
    }

    @Transactional
    public Task reject(Long taskId, String adminId, String reason) {
        if (isBlank(reason)) throw new TaskException(HttpStatus.BAD_REQUEST, "반려 사유는 필수입니다");
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        requireReview(t, "반려");
        return move(t, statusBeforeReview(t), "user", adminId, "종료 반려: " + reason.trim());
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
        t.setClosePatchMode(null); // 다음 승인 때 다시 정한다
        return move(t, statusBeforeReview(t), "user", adminId, "종료 취소(병합 전)");
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
     * 패치 단계에서 멈춘 종료실패를 패치 없이 이어 간다 (스펙 2026-11-10). skipArtifact와 같은 출구.
     */
    @Transactional
    public Task skipPatch(Long taskId, String adminId, String reason) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.CLOSE_FAILED) {
            throw TaskException.conflict("종료실패 상태에서만 패치 없이 마무리할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (!CloseStep.PATCH_REQUIRED.equals(t.getClosePatchMode())
                || CloseStep.parse(t.getCloseProgress()).contains(CloseStep.PATCH_BUILT)) {
            throw TaskException.conflict("패치 단계에서 멈춘 종료만 패치 없이 마무리할 수 있습니다");
        }
        t.setClosePatchMode(CloseStep.PATCH_SKIPPED);
        t.setFailureReason(null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId,
                "패치 없이 마무리" + (isBlank(reason) ? "" : ": " + reason.trim()));
    }

    /**
     * 종료승인대기로 들어오기 직전 상태. 배포완료·배포중단됨·PR생성(DB 작업)만 돌려준다 — 이력을 못 읽으면
     * {@link #fallbackBeforeReview}.
     */
    TaskStatus statusBeforeReview(Task t) {
        for (TaskStatusHistory h : historyRepo.findByTaskIdOrderByAtDescIdDesc(t.getId(), Limit.of(50))) {
            if (TaskStatus.CLOSE_REVIEW.dbValue().equals(h.getToStatus()) && h.getFromStatus() != null) {
                try {
                    TaskStatus from = TaskStatus.fromDb(h.getFromStatus());
                    return RETURNABLE.contains(from) ? from : fallbackBeforeReview(t);
                } catch (IllegalArgumentException e) {
                    return fallbackBeforeReview(t);
                }
            }
        }
        return fallbackBeforeReview(t);
    }

    /**
     * 직전 상태를 이력에서 못 찾았을 때의 대체값. 컨테이너 생존을 장담할 수 없으므로 배포중단됨(재배포를 유도하는 쪽)으로
     * 돌린다 — DeployReconcileJob이 살아 있으면 배포완료로 되돌린다. 단 배포한 적 없는 DB 작업은 PR생성이다: 컨테이너가
     * 없어 정합 잡이 배포완료로 되돌릴 수 없으니 배포중단됨에 갇힌다. (스펙 2026-11-03 §6.9)
     */
    private static TaskStatus fallbackBeforeReview(Task t) {
        return t.getTaskType() == TaskType.DB_WORK && neverDeployed(t) ? TaskStatus.PR_CREATED : TaskStatus.DEPLOY_LOST;
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
