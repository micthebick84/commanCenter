package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.hamonsoft.netismaker.entity.TaskStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 수정 회차 구현·자동 재배포 결과 처리 (스펙 2026-11-03 §4·§7) — WorkerServiceImplementationAttemptTest와 같은 하네스.
 * 작업 42는 배포된 뒤 수정 2회차(요청 #502)가 열린 상태다. 작업의 시도 번호는 1 그대로, 회차 안 시도 번호는 요청이 든다.
 */
class WorkerServiceFixRoundResultTest {

    private static final String BRANCH = "netismaker/task-42-x";
    private static final String OLD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PR_URL = "https://github.com/acme/widgets/pull/3";

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskStageUsageRepository stageUsageRepo;
    private TaskFixRequestRepository fixRepo;
    private DeployLogStreamService deployLogStream;
    private WorkerService svc;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        stageUsageRepo = mock(TaskStageUsageRepository.class);
        fixRepo = mock(TaskFixRequestRepository.class);
        deployLogStream = mock(DeployLogStreamService.class);
        svc = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, stageUsageRepo,
                mock(WorkerHeartbeatRepository.class), deployLogStream,
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        svc.setFixRequestRepository(fixRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fixRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** 수정 회차가 열린 작업 — 이어 갈 브랜치·PR·저장된 env가 있다. */
    private Task t(TaskStatus status) {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        t.setImplementationAttempt(1);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setWorkerId("w1");
        t.setHeadBranch(BRANCH);
        t.setHeadSha(OLD_SHA);
        t.setPrUrl(PR_URL);
        t.setPrNumber(3);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("A", "1", false))));
        t.setActiveFixRequestId(502L);
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        return t;
    }

    /** 승인된 수정 요청 #502 (회차·회차 안 시도). */
    private TaskFixRequest request(int round, int attempt) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "대상장비 탭 갱신 안 됨", DEPLOYED, "user1");
        ReflectionTestUtils.setField(r, "id", 502L);
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(ImplementationMode.REVIEW);
        r.setAttempt(attempt);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(r));
        return r;
    }

    private static final WorkerResultRequest.UsageReport USAGE =
            new WorkerResultRequest.UsageReport(new BigDecimal("0.40"), 100L, 50L, 0L, 0L);

    /** 회차 구현 성공 보고 (Task 6 워커 모양) — prUrl 없음, 회차 브랜치·새 끝. */
    private static WorkerResultRequest success(String headBranch, String headSha) {
        return new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                null, null, headBranch, headSha, "fix log",
                null, null, null, null, null,
                null, null, null, null, USAGE);
    }

    private static WorkerResultRequest failure(String headSha) {
        return new WorkerResultRequest("w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "claude exit=1",
                null, null, BRANCH, headSha, "fail log",
                null, null, null, null, null,
                null, null, null, null, USAGE);
    }

    private List<TaskStatusHistory> histories() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    // ── 구현 결과 ─────────────────────────────────────────

    @Test
    void 회차_성공보고는_배포대기로_env유지() {
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, success(BRANCH, "new9999").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
        assertThat(t.getHeadSha()).isEqualTo("new9999");
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(t.getPrUrl()).isEqualTo(PR_URL);
        assertThat(t.getPrNumber()).isEqualTo(3);
        assertThat(t.getImplementationLog()).isEqualTo("fix log");
        assertThat(t.getEnvVars()).extracting(EnvVar::key).containsExactly("A");
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);   // 재배포가 끝나야 닫힌다
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        assertThat(t.getFailureReason()).isNull();

        List<TaskStatusHistory> rows = histories();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getFromStatus()).isEqualTo("구현중");
        assertThat(rows.get(0).getToStatus()).isEqualTo("PR생성");
        assertThat(rows.get(0).getActorType()).isEqualTo("worker");
        assertThat(rows.get(0).getActorId()).isEqualTo("w1");
        assertThat(rows.get(0).getReason()).isEqualTo("수정 2회차 구현 완료: new9999");
        assertThat(rows.get(1).getFromStatus()).isEqualTo("PR생성");
        assertThat(rows.get(1).getToStatus()).isEqualTo("배포대기");
        assertThat(rows.get(1).getActorType()).isEqualTo("system");
        assertThat(rows.get(1).getReason()).isEqualTo("수정 2회차 구현 완료 → 자동 재배포");
        verify(stageUsageRepo).accumulate(eq(42L), eq(TaskStageUsage.STAGE_IMPLEMENTATION), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void 회차_성공보고의_커밋은_이력에_7자로_줄인다() {
        t(IMPLEMENTING);
        request(1, 1);

        svc.recordResult(42L, success(BRANCH, "abcdef1234567890abcdef1234567890abcdef12")
                .withImplementationAttempt(1).withFixAttempt(1));

        assertThat(histories().get(0).getReason()).isEqualTo("수정 1회차 구현 완료: abcdef1");
    }

    @Test
    void 회차_성공보고는_회차_브랜치와_새_커밋이_있어야_한다() {
        Task t = t(IMPLEMENTING);
        request(2, 1);

        assertThatThrownBy(() -> svc.recordResult(42L,
                success("netismaker/task-42-x-r2", "new9999").withImplementationAttempt(1).withFixAttempt(1)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> svc.recordResult(42L,
                success(BRANCH, " ").withImplementationAttempt(1).withFixAttempt(1)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(t.getStatus()).isEqualTo(IMPLEMENTING);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
    }

    @Test
    void 회차_성공보고도_다른_워커면_409() {
        Task t = t(IMPLEMENTING);
        t.setWorkerId("w2");
        request(2, 1);

        assertThatThrownBy(() -> svc.recordResult(42L,
                success(BRANCH, "new9999").withImplementationAttempt(1).withFixAttempt(1)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(t.getStatus()).isEqualTo(IMPLEMENTING);
    }

    @Test
    void 회차_실패보고는_구현실패_회차유지_headSha는_보낸값() {
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, failure("wip7777").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTATION_FAILED);
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);
        assertThat(t.getHeadSha()).isEqualTo("wip7777");   // 브랜치 끝 — 수정 중단 뒤 배포 기준
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(t.getPrUrl()).isEqualTo(PR_URL);
        assertThat(t.getFailureReason()).isEqualTo("claude exit=1");
        assertThat(t.getImplementationLog()).isEqualTo("fail log");
        TaskStatusHistory row = histories().get(0);
        assertThat(row.getToStatus()).isEqualTo("구현실패");
        assertThat(row.getReason()).isEqualTo("수정 2회차 구현 실패: claude exit=1");
        verify(stageUsageRepo).accumulate(eq(42L), eq(TaskStageUsage.STAGE_IMPLEMENTATION), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void 회차_실패보고에_커밋이_없으면_head는_그대로() {
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, failure(null).withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTATION_FAILED);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
    }

    // ── 늦은 보고 ─────────────────────────────────────────

    @Test
    void 이전_수정시도의_늦은보고는_무시() {
        Task t = t(IMPLEMENTING);
        request(2, 2);

        svc.recordResult(42L, success(BRANCH, "old1111").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTING);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        TaskStatusHistory row = histories().get(0);
        assertThat(row.getFromStatus()).isEqualTo("구현중");
        assertThat(row.getToStatus()).isEqualTo("구현중");
        assertThat(row.getReason()).isEqualTo("이전 수정 시도(1회째)의 늦은 보고 무시: PR생성 (브랜치: " + BRANCH + ")");
        // 이전 시도도 돈 시간은 실제로 쓴 것 — 사용량은 쌓는다(기존 늦은 보고와 같다)
        verify(stageUsageRepo).accumulate(eq(42L), eq(TaskStageUsage.STAGE_IMPLEMENTATION), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void 이전_수정시도의_늦은_실패도_재시도_대기를_건드리지_않는다() {
        Task t = t(APPROVED);   // 관리자가 같은 회차를 재시도해 대기 중
        request(2, 2);

        svc.recordResult(42L, failure("wip7777").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(APPROVED);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
    }

    @Test
    void 회차가_열려_있는데_회차_시도_번호가_없는_보고는_회차_전_구현의_것이라_무시() {
        // 재배포 전 첫 구현의 PR생성 보고가 dead-letter 재전송으로 다시 온 경우 — 받아들이면 옛 커밋을 자동 재배포한다
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                PR_URL, 3, BRANCH, OLD_SHA, "first log",
                null, null, null, null, null,
                null, null, null, null, USAGE).withImplementationAttempt(1));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTING);
        assertThat(histories().get(0).getReason())
                .isEqualTo("수정 회차 전 구현의 늦은 보고 무시: PR생성 (PR: " + PR_URL + ", 브랜치: " + BRANCH + ")");
        verifyNoInteractions(stageUsageRepo);   // 이미 받은 보고의 재전송 — 사용량을 두 번 쌓지 않는다
    }

    @Test
    void 닫힌_회차의_늦은보고는_무시() {
        Task t = t(PR_CREATED);   // 수정 중단으로 회차가 닫혔다
        t.setActiveFixRequestId(null);

        svc.recordResult(42L, success(BRANCH, "late555").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(PR_CREATED);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        assertThat(histories().get(0).getReason())
                .isEqualTo("닫힌 수정 회차의 늦은 보고 무시: PR생성 (브랜치: " + BRANCH + ")");
    }

    @Test
    void 이미_받은_회차_성공보고가_배포중에_다시_와도_배포중지로_읽지_않는다() {
        // 자동 재배포를 같은 워커가 잡은 뒤 성공 보고가 재전송되면 배포 단계의 PR생성(=배포 중지 성공)으로 읽혀 회차가 갇혔다
        Task t = t(DEPLOYING);
        t.setHeadSha("new9999");
        request(2, 1);

        svc.recordResult(42L, success(BRANCH, "new9999").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(DEPLOYING);
        assertThat(t.getActiveFixRequestId()).isEqualTo(502L);
        assertThat(histories().get(0).getReason())
                .isEqualTo("이미 끝난 수정 시도(1회째)의 늦은 보고 무시: PR생성 (브랜치: " + BRANCH + ")");
        verifyNoInteractions(stageUsageRepo);
        verify(deployLogStream, never()).finish(any());
    }

    @Test
    void 회수된_회차의_같은_시도_성공보고는_정합화해_자동_재배포한다() {
        Task t = t(IMPLEMENTATION_FAILED);
        t.setWorkerId(null);
        t.setFailureReason("Stale 회수: 처리 시간 초과(95분) (구현 중단)");
        request(2, 1);

        svc.recordResult(42L, success(BRANCH, "new9999").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
        assertThat(t.getHeadSha()).isEqualTo("new9999");
        assertThat(t.getFailureReason()).isNull();
        List<TaskStatusHistory> rows = histories();
        assertThat(rows.get(0).getFromStatus()).isEqualTo("구현실패");
        assertThat(rows.get(0).getReason()).isEqualTo("지각 보고 정합화: stale 회수 → 수정 2회차 구현 완료: new9999");
        assertThat(rows.get(1).getReason()).isEqualTo("수정 2회차 구현 완료 → 자동 재배포");
    }

    @Test
    void 회수된_회차의_같은_시도_실패보고는_기존처럼_409() {
        Task t = t(IMPLEMENTATION_FAILED);
        t.setWorkerId(null);
        request(2, 1);

        assertThatThrownBy(() -> svc.recordResult(42L, failure("wip7777").withImplementationAttempt(1).withFixAttempt(1)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
    }

    @Test
    void 회차가_없는_작업의_구현_결과는_예전과_같다() {
        Task t = t(IMPLEMENTING);
        t.setActiveFixRequestId(null);

        svc.recordResult(42L, new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/9", 9, "netismaker/task-42-x-r2", "abc1234", "log",
                null, null, null, null, null,
                null, null, null, null, null).withImplementationAttempt(1));

        assertThat(t.getStatus()).isEqualTo(PR_CREATED);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/acme/widgets/pull/9");
        verifyNoInteractions(fixRepo);
    }

    // ── 자동 재배포 결과 = 회차 닫기 ─────────────────────

    private static WorkerResultRequest deployed() {
        return WorkerResultRequest.deployed("w1", "https://task-42-win.micthebick.dev", "c1", 19001, "img", 5L, "dlog");
    }

    @Test
    void 회차_배포완료는_회차를_닫는다_DEPLOYED() {
        Task t = t(DEPLOYING);
        TaskFixRequest r = request(2, 1);

        svc.recordResult(42L, deployed());

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.DEPLOYED);
        assertThat(r.getClosedAt()).isNotNull();
        assertThat(histories().get(0).getReason())
                .isEqualTo("배포 완료: https://task-42-win.micthebick.dev (수정 2회차 닫힘)");
        verify(deployLogStream).finish(42L);
    }

    @Test
    void 회차_배포실패도_회차를_닫는다() {
        Task t = t(DEPLOYING);
        TaskFixRequest r = request(2, 1);

        svc.recordResult(42L, WorkerResultRequest.deployFailed("w1", "docker build 실패", "dlog"));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_FAILED);
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.DEPLOY_FAILED);
        assertThat(r.getClosedAt()).isNotNull();
        assertThat(t.getFailureReason()).isEqualTo("docker build 실패");
        assertThat(histories().get(0).getReason()).isEqualTo("docker build 실패 (수정 2회차 닫힘)");
    }

    @Test
    void 회차_기록이_없어도_배포가_끝나면_회차_표시는_지운다() {
        Task t = t(DEPLOYING);
        when(fixRepo.findById(502L)).thenReturn(Optional.empty());

        svc.recordResult(42L, deployed());

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(histories().get(0).getReason()).endsWith(" (수정 회차 닫힘)");
    }

    @Test
    void 지각_배포완료_정합화도_열린_회차를_닫는다() {
        // 지금은 배포실패로 가는 모든 경로가 회차를 닫지만, 표시가 남아 있으면 정합화 경로도 닫는다(GC 보호가 영원히 남지 않게)
        Task t = t(DEPLOY_FAILED);
        TaskFixRequest r = request(2, 1);

        svc.recordResult(42L, deployed());

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.DEPLOYED);
        assertThat(histories().get(0).getReason()).isEqualTo("지각 보고 정합화: stale 회수 → 배포완료 (수정 2회차 닫힘)");
    }

    @Test
    void 회차가_없는_배포_결과는_예전과_같다() {
        Task t = t(DEPLOYING);
        t.setActiveFixRequestId(null);

        svc.recordResult(42L, deployed());

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(histories().get(0).getReason()).isEqualTo("배포 완료: https://task-42-win.micthebick.dev");
        verifyNoInteractions(fixRepo);
    }
}
