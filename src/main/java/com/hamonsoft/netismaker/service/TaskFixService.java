package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.FixRequestKind;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 배포 뒤 수정 요청의 사람 쪽 전이 (스펙 2026-11-03 §4·§6·§7.1). 구현·자동 재배포와 그 결과로 회차를 닫는 일은
 * 이 서비스 밖(WorkerService·TaskRecovery) — 여기는 요청을 받고 회차를 여는 데와 사람이 회차를 접는 수정 중단까지다.
 *
 *   request  ─► 배포완료|배포중단됨|배포실패 → 수정승인대기 (등록자 본인 또는 관리자)
 *               관리자가 낸 요청은 승인 단계 없이 곧바로 → 구현대기 (수정 n회차)
 *   approve  ─► 수정승인대기 → 구현대기, 회차 확정·구현 방식 결정   (관리자 — 컨트롤러가 강제)
 *   reject   ─► 수정승인대기 → 요청 전 상태 + 사유                  (관리자)
 *   withdraw ─► 수정승인대기 → 요청 전 상태                         (등록자 본인 또는 관리자)
 *   abort    ─► 수정 회차의 구현실패 → PR생성, 회차 닫힘(ABORTED)   (관리자, §7.1)
 *               이전 배포가 떠 있으면 → 배포중지대기 (워커가 내린 뒤 PR생성)
 *
 * 락 정책은 TaskService·TaskCloseService와 같다 — findActiveByIdForUpdate 후 재판정.
 * 회차(round)는 승인된 요청 수 + 1이다. 열린 회차는 task.active_fix_request_id가 가리키고, 반려·철회는 회차에 세지 않는다.
 * 작업의 구현 방식·시도 번호·브랜치·head_sha·PR 칸은 건드리지 않는다 — 회차의 방식은 요청 기록에 둔다(§6.2).
 */
@Service
@Profile("api")
public class TaskFixService {

    /** 수정 요청을 받는 작업 상태 (스펙 §6.1) — 배포가 한 번 끝난(또는 끝내지 못한) 작업. */
    public static final Set<TaskStatus> REQUESTABLE =
            EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, TaskStatus.DEPLOY_FAILED);

    /** 요청 본문(Markdown) 길이 상한 — 재검증 근거·오류 발췌가 붙어도 넉넉하되 한없이 길 수는 없다. */
    static final int MAX_BODY_CHARS = 20_000;

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final TaskFixRequestRepository fixRepo;
    private final int maxRounds;

    public TaskFixService(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo,
                          TaskFixRequestRepository fixRepo,
                          @Value("${app.task.fix-request-max-rounds:3}") int maxRounds) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.fixRepo = fixRepo;
        this.maxRounds = maxRounds;
    }

    /**
     * 수정 요청. rawMode(DIRECT|REVIEW, 비면 REVIEW)는 관리자가 낸 요청에서만 쓴다 — 등록자의 요청은 승인 때 관리자가 고르므로
     * 무시한다. 관리자 요청은 곧바로 승인된 것으로 보고 구현대기로 보낸다.
     */
    @Transactional
    public Task request(Long taskId, String actorId, boolean isAdmin, String body, String rawMode) {
        String text = requireBody(body);
        ImplementationMode adminMode = isAdmin ? parseFixMode(rawMode) : null;
        Task t = lockOwned(taskId, actorId, isAdmin);

        if (!REQUESTABLE.contains(t.getStatus())) {
            throw TaskException.conflict("배포완료·배포중단됨·배포실패 상태에서만 수정을 요청할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (t.getActiveFixRequestId() != null) {
            throw TaskException.conflict("이미 진행 중인 수정이 있습니다 — 끝난 뒤에 다시 요청하세요");
        }
        if (isBlank(t.getHeadBranch()) || isBlank(t.getHeadSha())) {
            throw TaskException.conflict("이어서 고칠 브랜치·커밋 정보가 없어 수정을 요청할 수 없습니다 — 새 작업으로 등록하세요");
        }
        // 회차는 head_branch에 이어 push한다 — 베이스 브랜치를 가리키면(깨진 데이터) 베이스에 직접 커밋하게 되므로 받지 않는다
        if (t.getGithubBranch() != null && t.getHeadBranch().strip().equals(t.getGithubBranch().strip())) {
            throw TaskException.conflict("이어서 고칠 브랜치가 베이스 브랜치(" + t.getGithubBranch().strip()
                    + ")와 같아 수정을 요청할 수 없습니다 — 새 작업으로 등록하세요");
        }
        long approved = fixRepo.countByTaskIdAndStatusAndKind(taskId, FixRequestStatus.APPROVED, FixRequestKind.FIX);
        requireRoundLeft(approved);

        TaskStatus from = t.getStatus();
        TaskFixRequest r = TaskFixRequest.pending(taskId, text, from, actorId);
        if (!isAdmin) {
            fixRepo.save(r);
            return move(t, TaskStatus.FIX_REVIEW, actorId, "수정 요청 → 수정승인대기");
        }
        // 관리자 요청 — 승인 단계 없이 회차를 연다. 저장해 id를 얻은 뒤 작업이 그 요청을 가리킨다.
        openRound(r, (int) approved + 1, adminMode, actorId);
        TaskFixRequest saved = fixRepo.save(r);
        return startRound(t, saved, actorId);
    }

    @Transactional
    public Task approve(Long taskId, String adminId, String rawMode) {
        ImplementationMode mode = parseFixMode(rawMode);
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        requireReview(t, "승인");
        TaskFixRequest r = pendingOf(taskId);
        long approved = fixRepo.countByTaskIdAndStatusAndKind(taskId, FixRequestStatus.APPROVED, FixRequestKind.FIX);
        requireRoundLeft(approved);

        openRound(r, (int) approved + 1, mode, adminId);
        fixRepo.save(r);
        return startRound(t, r, adminId);
    }

    @Transactional
    public Task reject(Long taskId, String adminId, String reason) {
        if (isBlank(reason)) throw new TaskException(HttpStatus.BAD_REQUEST, "반려 사유는 필수입니다");
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        requireReview(t, "반려");
        TaskFixRequest r = pendingOrNull(taskId);
        String why = reason.trim();
        if (r != null) decide(r, FixRequestStatus.REJECTED, adminId, why);
        return move(t, statusBeforeRequest(r), adminId, "수정 요청 반려: " + why);
    }

    @Transactional
    public Task withdraw(Long taskId, String actorId, boolean isAdmin) {
        Task t = lockOwned(taskId, actorId, isAdmin);
        requireReview(t, "철회");
        TaskFixRequest r = pendingOrNull(taskId);
        if (r != null) decide(r, FixRequestStatus.WITHDRAWN, actorId, null);
        return move(t, statusBeforeRequest(r), actorId, "수정 요청 철회");
    }

    /**
     * 수정 중단 (스펙 §7.1) — 수정 회차의 구현실패 → PR생성, 회차를 ABORTED로 닫는다(승인된 회차로 센다). 관리자 한정(컨트롤러).
     * 자동 재배포는 없다 — 사람이 PR생성에서 보통 배포(브랜치 끝)를 다시 누른다. head_sha는 건드리지 않는다: 회차의 구현실패 보고가
     * 이미 브랜치 끝(wip 커밋 포함)을 남겼다(WorkerService). 이전 구현 결과의 흔적은 실패 사유만 비운다(로그는 확인용으로 둔다).
     *
     * 이전 배포가 아직 떠 있으면(배포 런타임 칸이 남아 있으면) PR생성이 아니라 배포중지대기로 보낸다 — 회차가 닫히면 GC 보호가 풀리고
     * PR생성은 중지·보호 대상이 아니라 컨테이너와 공개 주소(DNS)가 추적 없이 남기 때문이다. 이후는 관리자 배포 중지와 같은 길이다:
     * 워커가 컨테이너를 내리고 PR생성 보고로 배포 칸을 비운다(WorkerService.recordDeployResult). 회차는 지금 닫으므로 그 길에서
     * 이 작업을 회차로 다루는 곳은 없다.
     *
     * 회차 표시는 있는데 열린 회차 기록이 없는 경우 재시도는 409라 이 길이 유일한 출구다 — 요청 기록은 건드리지 않고 표시만 지운 뒤
     * PR생성으로 보낸다. 이런 표시는 두 곳에서 생긴다: claim이 깨진 회차를 구현실패로 치운 경우(WorkerService.failBrokenFixRound —
     * 구현 시작 전이라 브랜치·head_sha는 회차 전 그대로)와 재기동 재큐가 기록을 못 찾은 경우(TaskRecovery — 끊긴 시도가 push한 커밋이
     * 브랜치에 남아 head_sha가 원격 끝보다 뒤일 수 있다). 어느 쪽이든 다음 배포는 브랜치 끝을 빌드하고, 빌드한 커밋을 배포완료 보고가
     * head_sha로 다시 기록한다(WorkerService.recordDeployResult).
     */
    @Transactional
    public Task abort(Long taskId, String adminId) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.IMPLEMENTATION_FAILED) {
            throw TaskException.conflict("수정 회차의 구현실패 상태에서만 수정을 중단할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (t.getActiveFixRequestId() == null) {
            throw TaskException.conflict("수정 회차가 아닌 구현실패는 중단할 수 없습니다 — 구현 재시도를 쓰세요");
        }
        Long requestId = t.getActiveFixRequestId();
        TaskFixRequest r = FixRounds.closeActive(fixRepo, t, FixOutcome.ABORTED);
        t.setFailureReason(null);
        if (FixRounds.isBaseSync(r)) {
            // 기준 브랜치 반영 회차 — 새 배포를 만들려던 일이 아니라 요청 전 상태로 되돌린다(배포 중지·PR생성 분기 없음, head_sha 그대로)
            TaskStatus back = FixRounds.baseSyncReturnStatus(r, t);
            return move(t, back, adminId, FixRounds.label(r) + " 중단 → " + back.dbValue());
        }
        boolean liveDeploy = hasDeployRuntime(t);
        String next = liveDeploy ? "배포 중지" : "PR생성";
        String reason = r != null
                ? FixRounds.label(r) + " 중단 → " + next
                : "열린 수정 회차 기록이 없어(요청 #" + requestId + ") 회차 표시만 지움 — 수정 중단 → " + next;
        return move(t, liveDeploy ? TaskStatus.UNDEPLOY_PENDING : TaskStatus.PR_CREATED, adminId, reason);
    }

    /**
     * 이전 배포의 런타임 칸(컨테이너 id·배포 주소)이 남아 있다 — 배포 중지 보고(PR생성 복귀)가 비우는 칸이다. 컨테이너가 이미
     * 없어도(배포실패 등) 중지는 해가 없다: 워커의 docker rm -f·DNS 삭제는 없는 대상을 그냥 지나간다.
     */
    private static boolean hasDeployRuntime(Task t) {
        return t.getDeployContainerId() != null || t.getDeployUrl() != null;
    }

    /** 작업의 수정 요청 기록, 최신순. 열람 권한(등록자·관리자)은 호출자가 TaskService.getForView로 먼저 검증한다. */
    @Transactional(readOnly = true)
    public List<TaskFixRequest> list(Long taskId) {
        return fixRepo.findByTaskIdOrderByIdDesc(taskId);
    }

    /** 작업 상세 응답에 싣는 회차 현황. 열린 회차가 없으면 activeRound·activeMode는 null. */
    @Transactional(readOnly = true)
    public TaskResponse.FixView fixViewOf(Task t) {
        Integer activeRound = null;
        String activeMode = null;
        String activeKind = null;
        if (t.getActiveFixRequestId() != null) {
            TaskFixRequest active = fixRepo.findById(t.getActiveFixRequestId()).orElse(null);
            if (active != null) {
                activeRound = active.getRound();
                activeMode = active.getImplementationMode() == null ? null : active.getImplementationMode().name();
                activeKind = active.getKind().name();
            }
        }
        int approved = (int) fixRepo.countByTaskIdAndStatusAndKind(t.getId(), FixRequestStatus.APPROVED, FixRequestKind.FIX);
        return new TaskResponse.FixView(activeRound, activeMode, approved, maxRounds, activeKind);
    }

    /**
     * 수정 회차의 구현 방식 해석 — null·공백은 REVIEW(기본), DIRECT·REVIEW는 그 값, SDD와 그 밖은 400.
     * SDD는 확정 플랜의 작업마다 서브에이전트를 돌려 원래 플랜 전체를 다시 구현하게 되므로 고를 수 없다(스펙 §6.2).
     */
    public static ImplementationMode parseFixMode(String raw) {
        ImplementationMode mode = ImplementationModePolicy.parse(raw, ImplementationMode.REVIEW);
        if (mode == ImplementationMode.SDD) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "수정 요청은 직접 또는 리뷰 방식만 고를 수 있습니다 (SDD 불가)");
        }
        return mode;
    }

    // ── 내부 ─────────────────────────────────────────────

    /** 요청을 승인된 상태로 만든다 — 회차 번호와 구현 방식은 이때 확정된다. */
    private static void openRound(TaskFixRequest r, int round, ImplementationMode mode, String deciderId) {
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(mode);
        r.setDecidedBy(deciderId);
        r.setDecidedAt(OffsetDateTime.now());
    }

    private static void decide(TaskFixRequest r, FixRequestStatus to, String deciderId, String reason) {
        r.setStatus(to);
        r.setDecidedBy(deciderId);
        r.setDecidedAt(OffsetDateTime.now());
        r.setDecisionReason(reason);
    }

    /**
     * 승인된 요청의 회차를 열고 작업을 구현대기로 보낸다. 직전 구현의 결과·점유 흔적만 비운다 — 이어 갈 브랜치·head_sha·PR 칸과
     * 작업의 구현 방식·시도 번호는 그대로. 재기동 재큐 횟수는 새 회차가 새로 받는다(작업당 2회 상한이 첫 구현에서 이미 소진돼
     * 있으면 수정 회차가 첫 재기동에 구현실패가 되므로).
     */
    private Task startRound(Task t, TaskFixRequest r, String actorId) {
        t.setActiveFixRequestId(r.getId());
        t.setFailureReason(null);
        t.setImplementationLog(null);
        t.setImplementationTimeoutMinutes(null);
        t.setRestartRequeueCount(0);
        return move(t, TaskStatus.APPROVED, actorId, "수정 " + r.getRound() + "회차 승인 (구현 방식: "
                + r.getImplementationMode().label() + ") → 구현대기");
    }

    private void requireRoundLeft(long approved) {
        if (approved >= maxRounds) {
            throw TaskException.conflict("수정 요청은 작업당 " + maxRounds + "회까지 승인됩니다 — 새 작업으로 등록하세요");
        }
    }

    /** 승인은 요청 기록이 있어야만 한다 — 수정승인대기인데 기록이 없으면(있을 수 없는 상태) 409. */
    private TaskFixRequest pendingOf(Long taskId) {
        TaskFixRequest r = pendingOrNull(taskId);
        if (r == null) throw TaskException.conflict("대기 중인 수정 요청이 없습니다");
        return r;
    }

    private TaskFixRequest pendingOrNull(Long taskId) {
        return fixRepo.findFirstByTaskIdAndStatusOrderByIdDesc(taskId, FixRequestStatus.PENDING).orElse(null);
    }

    /**
     * 요청 전 상태 — 요청 기록에 박제해 둔 값. 기록이 없거나 요청 가능 상태가 아니면(있을 수 없는 불일치) 배포중단됨(재배포를
     * 유도하는 쪽)으로 돌린다 — 반려·철회가 막히면 작업이 수정승인대기에 갇히므로 이 둘은 기록 없이도 빠져나올 수 있어야 한다.
     */
    private static TaskStatus statusBeforeRequest(TaskFixRequest r) {
        TaskStatus from = r == null ? null : r.getFromStatus();
        return from != null && REQUESTABLE.contains(from) ? from : TaskStatus.DEPLOY_LOST;
    }

    private Task lockOwned(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (!t.isOwnedBy(actorId) && !isAdmin) throw TaskException.forbidden();
        return t;
    }

    private static void requireReview(Task t, String verb) {
        if (t.getStatus() != TaskStatus.FIX_REVIEW) {
            throw TaskException.conflict("수정승인대기 상태에서만 " + verb + "할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
    }

    private static String requireBody(String body) {
        if (isBlank(body)) throw new TaskException(HttpStatus.BAD_REQUEST, "수정 요청 내용은 필수입니다");
        String text = body.strip();
        if (text.length() > MAX_BODY_CHARS) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "수정 요청 내용은 " + MAX_BODY_CHARS + "자 이하여야 합니다");
        }
        return text;
    }

    private Task move(Task t, TaskStatus to, String actorId, String reason) {
        TaskStatus from = t.getStatus();
        t.setStatus(to);
        t.setWorkerId(null);
        t.setClaimedAt(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", actorId, reason));
        return t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
