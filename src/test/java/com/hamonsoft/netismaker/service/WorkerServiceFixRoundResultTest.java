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
        return failure(BRANCH, headSha);
    }

    private static WorkerResultRequest failure(String headBranch, String headSha) {
        return new WorkerResultRequest("w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "claude exit=1",
                null, null, headBranch, headSha, "fail log",
                null, null, null, null, null,
                null, null, null, null, USAGE);
    }

    /** 다른 요청(id)의 승인된 기록 — 다른·닫힌 회차의 보고가 어느 요청에서 왔는지 볼 때. */
    private TaskFixRequest otherRequest(long id, int round, int attempt, com.hamonsoft.netismaker.entity.FixOutcome outcome) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "앞 회차", DEPLOYED, "user1");
        ReflectionTestUtils.setField(r, "id", id);
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(ImplementationMode.REVIEW);
        r.setAttempt(attempt);
        r.setOutcome(outcome);
        when(fixRepo.findById(id)).thenReturn(Optional.of(r));
        return r;
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

    /** 회차 성공 보고 + 워커가 거둔 배포 env 템플릿(null = 구버전 워커). */
    private static WorkerResultRequest successWithTemplate(List<com.hamonsoft.netismaker.entity.EnvTemplateItem> template) {
        return new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                null, null, BRANCH, "new9999", "fix log",
                null, null, null, null, null,
                null, null, null, null, USAGE,
                template).withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(502L);
    }

    @Test
    void 회차_성공보고의_배포_env_템플릿을_보통_구현처럼_저장한다() {
        Task t = t(IMPLEMENTING);
        t.setEnvTemplate(new ArrayList<>(List.of(new com.hamonsoft.netismaker.entity.EnvTemplateItem("OLD", "", false, false))));
        request(2, 1);

        svc.recordResult(42L, successWithTemplate(List.of(
                new com.hamonsoft.netismaker.entity.EnvTemplateItem(" JWT_SECRET ", "서명 키", true, true),
                new com.hamonsoft.netismaker.entity.EnvTemplateItem("BAD-KEY", "", false, false))));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
        // 정리(sanitize)한 값 — 공백 제거·잘못된 이름 버림. 자동 재배포가 쓰는 저장 env(envVars)는 그대로
        assertThat(t.getEnvTemplate()).containsExactly(
                new com.hamonsoft.netismaker.entity.EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
        assertThat(t.getEnvVars()).extracting(EnvVar::key).containsExactly("A");
    }

    @Test
    void 회차_성공보고에_템플릿이_없으면_기존_템플릿을_둔다() {
        Task t = t(IMPLEMENTING);
        t.setEnvTemplate(new ArrayList<>(List.of(new com.hamonsoft.netismaker.entity.EnvTemplateItem("KEEP", "", false, false))));
        request(2, 1);

        svc.recordResult(42L, successWithTemplate(null));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
        assertThat(t.getEnvTemplate()).extracting(com.hamonsoft.netismaker.entity.EnvTemplateItem::key)
                .containsExactly("KEEP");
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

    @Test
    void 회차_실패보고의_브랜치가_회차_브랜치가_아니면_커밋을_버린다() {
        // 다른 브랜치의 커밋을 head_sha로 두면 수정 중단 뒤 배포(회차 브랜치 끝)·종료 병합(head_sha 고정)이 어긋난다
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, failure("netismaker/task-42-x-r2", "other77").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTATION_FAILED);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        assertThat(t.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(t.getFailureReason()).isEqualTo("claude exit=1");
    }

    @Test
    void 회차_실패보고에_브랜치가_없어도_커밋을_버린다() {
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, failure(null, "other77").withImplementationAttempt(1).withFixAttempt(1));

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
        // 어느 요청의 몇 번째 시도인지 모른다 — 이미 반영된 보고의 재전송일 수 있어 사용량을 다시 쌓지 않는다
        verifyNoInteractions(stageUsageRepo);
    }

    // ── 요청 id로 회차를 가른다 (회차 안 시도 번호는 회차마다 1부터) ─────

    @Test
    void 다른_회차의_늦은보고는_시도번호가_같아도_무시() {
        // 1회차(#502)를 중단하고 연 2회차(#503)의 1회째가 진행 중인데 1회차 1회째의 보고가 늦게 왔다
        Task t = t(IMPLEMENTING);
        t.setActiveFixRequestId(503L);
        otherRequest(503L, 2, 1, null);
        otherRequest(502L, 1, 1, com.hamonsoft.netismaker.entity.FixOutcome.ABORTED);

        svc.recordResult(42L, success(BRANCH, "late555")
                .withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(502L));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTING);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        assertThat(t.getActiveFixRequestId()).isEqualTo(503L);
        TaskStatusHistory row = histories().get(0);
        assertThat(row.getToStatus()).isEqualTo("구현중");
        assertThat(row.getReason()).isEqualTo("다른 수정 회차(요청 #502)의 늦은 보고 무시: PR생성 (브랜치: " + BRANCH + ")");
        // #502의 마지막 시도 — 그 결과는 이미 반영됐을 수 있어 쌓지 않는다
        verifyNoInteractions(stageUsageRepo);
    }

    @Test
    void 다른_회차의_밀린_시도_보고는_사용량을_쌓는다() {
        Task t = t(IMPLEMENTING);
        t.setActiveFixRequestId(503L);
        otherRequest(503L, 2, 1, null);
        otherRequest(502L, 1, 2, com.hamonsoft.netismaker.entity.FixOutcome.DEPLOYED);   // #502는 2회째에서 끝났다

        svc.recordResult(42L, failure("wip7777").withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(502L));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTING);
        verify(stageUsageRepo).accumulate(eq(42L), eq(TaskStageUsage.STAGE_IMPLEMENTATION), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void 회차가_없을때_요청id를_단_보고는_닫힌_회차의_것() {
        Task t = t(DEPLOYED);
        t.setActiveFixRequestId(null);
        otherRequest(502L, 1, 1, com.hamonsoft.netismaker.entity.FixOutcome.DEPLOYED);

        svc.recordResult(42L, success(BRANCH, "new9999")
                .withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(502L));

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        assertThat(histories().get(0).getReason())
                .isEqualTo("닫힌 수정 회차(요청 #502)의 늦은 보고 무시: PR생성 (브랜치: " + BRANCH + ")");
        verifyNoInteractions(stageUsageRepo);   // 배포까지 간 회차의 마지막 시도 = 받은 성공 보고의 재전송
    }

    @Test
    void 요청id와_시도번호가_모두_맞으면_받는다() {
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, success(BRANCH, "new9999")
                .withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(502L));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
        assertThat(t.getHeadSha()).isEqualTo("new9999");
    }

    @Test
    void 요청id가_맞아도_이전_시도면_무시() {
        Task t = t(IMPLEMENTING);
        request(2, 2);

        svc.recordResult(42L, success(BRANCH, "old1111")
                .withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(502L));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTING);
        assertThat(histories().get(0).getReason()).startsWith("이전 수정 시도(1회째)의 늦은 보고 무시");
    }

    @Test
    void 구현대기에_온_같은_시도번호_보고는_다른_회차의_것으로_무시() {
        // 요청 id를 싣지 않은 보고 — 열린 회차는 아직 claim 전이라 이 회차의 1회째 결과일 수 없다
        Task t = t(APPROVED);
        request(2, 1);

        svc.recordResult(42L, success(BRANCH, "late555").withImplementationAttempt(1).withFixAttempt(1));

        assertThat(t.getStatus()).isEqualTo(APPROVED);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        assertThat(histories().get(0).getReason())
                .isEqualTo("다른 수정 회차의 늦은 보고 무시: PR생성 (브랜치: " + BRANCH + ")");
        verifyNoInteractions(stageUsageRepo);
    }

    @Test
    void 배포중지중에_온_회차_보고는_배포중지_성공으로_읽지_않는다() {
        // 수정 중단이 이전 배포가 떠 있는 작업을 배포중지대기로 보낸 뒤의 모양 — 회차는 이미 닫혀(표시 없음) 보통 배포 중지와 같다
        Task t = t(UNDEPLOYING);
        t.setActiveFixRequestId(null);
        t.setDeployUrl("https://task-42-win.micthebick.dev");
        t.setDeployContainerId("c1");

        svc.recordResult(42L, success(BRANCH, "late555").withImplementationAttempt(1).withFixAttempt(1));
        svc.recordResult(42L, success(BRANCH, "late555").withImplementationAttempt(1).withFixAttempt(1)
                .withFixRequestId(502L));

        assertThat(t.getStatus()).isEqualTo(UNDEPLOYING);
        assertThat(t.getDeployUrl()).isEqualTo("https://task-42-win.micthebick.dev");
        assertThat(t.getDeployContainerId()).isEqualTo("c1");
        List<TaskStatusHistory> rows = histories();
        assertThat(rows.get(0).getReason()).startsWith("닫힌 수정 회차의 늦은 보고 무시");
        assertThat(rows.get(1).getReason()).startsWith("닫힌 수정 회차(요청 #502)의 늦은 보고 무시");
        verify(deployLogStream, never()).finish(any());

        // 표지 없는 PR생성은 지금처럼 배포 중지 성공 — 배포 칸을 비우고 PR생성으로
        svc.recordResult(42L, WorkerResultRequest.undeployed("w1", "stopped"));
        assertThat(t.getStatus()).isEqualTo(PR_CREATED);
        assertThat(t.getDeployUrl()).isNull();
        assertThat(t.getDeployContainerId()).isNull();
        assertThat(t.getActiveFixRequestId()).isNull();
        verify(fixRepo, never()).save(any());   // 닫힌 회차의 기록을 다시 건드리지 않는다
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

    // ── 기준 브랜치 반영 회차 ─────────────────────────────

    /** 기준 브랜치 반영 회차 #7 (1회차, 직전 상태 from)을 열어 둔 작업 — head_sha는 aaa1111. */
    private Task baseSyncTask(TaskStatus from) {
        Task t = t(IMPLEMENTING);
        t.setHeadSha("aaa1111");
        t.setActiveFixRequestId(7L);
        t.setDeployUrl("https://task-42-win.micthebick.dev");
        t.setDeployedAt(java.time.OffsetDateTime.now());
        syncRound = TaskFixRequest.baseSync(42L, 1, "main", from, "admin");
        ReflectionTestUtils.setField(syncRound, "id", 7L);
        when(fixRepo.findById(7L)).thenReturn(Optional.of(syncRound));
        return t;
    }

    private TaskFixRequest syncRound;

    private static WorkerResultRequest syncReport(WorkerResultRequest r) {
        return r.withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(7L);
    }

    @Test
    void 반영_성공_새_커밋이면_head갱신_자동재배포() {
        Task t = baseSyncTask(DEPLOYED);

        svc.recordResult(42L, syncReport(success(BRANCH, "bbb2222")));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
        assertThat(t.getHeadSha()).isEqualTo("bbb2222");
        assertThat(t.getActiveFixRequestId()).isEqualTo(7L);   // 닫는 것은 배포 보고
        assertThat(syncRound.getOutcome()).isNull();
        List<TaskStatusHistory> rows = histories();
        assertThat(rows.get(rows.size() - 1).getReason()).isEqualTo("기준 브랜치 반영 1회차 구현 완료 → 자동 재배포");
    }

    @Test
    void 반영할_것이_없으면_직전상태로_회차는_NO_CHANGE() {
        Task t = baseSyncTask(DEPLOYED);

        svc.recordResult(42L, syncReport(success(BRANCH, "AAA1111")));

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(t.getHeadSha()).isEqualTo("aaa1111");
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(syncRound.getOutcome()).isEqualTo(FixOutcome.NO_CHANGE);
        assertThat(syncRound.getClosedAt()).isNotNull();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getImplementationLog()).isEqualTo("fix log");
        verify(fixRepo).save(syncRound);
        verify(stageUsageRepo).accumulate(eq(42L), eq(TaskStageUsage.STAGE_IMPLEMENTATION), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
        List<TaskStatusHistory> rows = histories();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getFromStatus()).isEqualTo("구현중");
        assertThat(rows.get(0).getToStatus()).isEqualTo("배포완료");
        assertThat(rows.get(0).getReason()).isEqualTo("기준 브랜치가 이미 반영돼 있음 → 배포완료 (기준 브랜치 반영 1회차 닫힘)");
    }

    @Test
    void 배포한_적_없는_DB작업은_재배포_없이_PR생성_회차는_SYNCED() {
        Task t = baseSyncTask(PR_CREATED);
        t.setTaskType(com.hamonsoft.netismaker.entity.TaskType.DB_WORK);
        t.setDeployUrl(null);
        t.setDeployedAt(null);

        svc.recordResult(42L, syncReport(success(BRANCH, "bbb2222")));

        assertThat(t.getStatus()).isEqualTo(PR_CREATED);
        assertThat(t.getHeadSha()).isEqualTo("bbb2222");
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(syncRound.getOutcome()).isEqualTo(FixOutcome.SYNCED);
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        List<TaskStatusHistory> rows = histories();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getToStatus()).isEqualTo("PR생성");
        assertThat(rows.get(0).getReason()).isEqualTo("기준 브랜치 반영 1회차 완료: bbb2222 → PR생성 (기준 브랜치 반영 1회차 닫힘)");
    }

    @Test
    void 구현실패로_회수된_반영회차의_늦은_성공은_정합화된다() {
        Task t = baseSyncTask(DEPLOYED);
        t.setStatus(IMPLEMENTATION_FAILED);
        t.setWorkerId(null);
        t.setFailureReason("Stale 회수: 처리 시간 초과(95분) (구현 중단)");

        svc.recordResult(42L, syncReport(success(BRANCH, "bbb2222")));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
        assertThat(t.getHeadSha()).isEqualTo("bbb2222");
        assertThat(t.getFailureReason()).isNull();
        assertThat(histories().get(0).getReason()).startsWith("지각 보고 정합화: stale 회수 → ");

        // 같은 커밋이면 반영할 것이 없던 것 — 직전 상태로 돌아가고 회차는 NO_CHANGE
        Task t2 = baseSyncTask(DEPLOYED);
        t2.setStatus(IMPLEMENTATION_FAILED);
        t2.setWorkerId(null);
        t2.setFailureReason("Stale 회수: 처리 시간 초과(95분) (구현 중단)");
        clearInvocations(historyRepo);

        svc.recordResult(42L, syncReport(success(BRANCH, "aaa1111")));

        assertThat(t2.getStatus()).isEqualTo(DEPLOYED);
        assertThat(syncRound.getOutcome()).isEqualTo(FixOutcome.NO_CHANGE);
        assertThat(t2.getActiveFixRequestId()).isNull();
        assertThat(histories().get(0).getReason()).startsWith("지각 보고 정합화: stale 회수 → ");
    }

    @Test
    void 반영_실패는_head_sha를_바꾸지_않는다() {
        Task t = baseSyncTask(DEPLOYED);

        svc.recordResult(42L, syncReport(new WorkerResultRequest("w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "기준 브랜치 반영 1회차: 충돌 해결 실패 — a.java",
                null, null, BRANCH, "ccc3333", "fail log",
                null, null, null, null, null,
                null, null, null, null, USAGE)));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTATION_FAILED);
        assertThat(t.getHeadSha()).isEqualTo("aaa1111");
        assertThat(t.getActiveFixRequestId()).isEqualTo(7L);
        assertThat(t.getFailureReason()).startsWith("기준 브랜치 반영 1회차: 충돌 해결 실패");
    }

    @Test
    void 닫힌_반영회차의_늦은_보고는_무시() {
        Task t = baseSyncTask(DEPLOYED);
        t.setStatus(DEPLOYED);
        t.setActiveFixRequestId(null);
        syncRound.setOutcome(FixOutcome.NO_CHANGE);

        svc.recordResult(42L, syncReport(success(BRANCH, "bbb2222")));

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(t.getHeadSha()).isEqualTo("aaa1111");
        assertThat(histories().get(0).getReason()).startsWith("닫힌 수정 회차(요청 #7)의 늦은 보고 무시");
    }

    @Test
    void FIX회차_실패는_지금처럼_head_sha를_브랜치_끝으로() {
        Task t = t(IMPLEMENTING);
        request(2, 1);

        svc.recordResult(42L, failure("wip7777").withImplementationAttempt(1).withFixAttempt(1).withFixRequestId(502L));

        assertThat(t.getStatus()).isEqualTo(IMPLEMENTATION_FAILED);
        assertThat(t.getHeadSha()).isEqualTo("wip7777");
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
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);   // 빌드한 커밋을 싣지 않은 예전 워커 — head_sha 그대로
        assertThat(histories().get(0).getReason()).isEqualTo("배포 완료: https://task-42-win.micthebick.dev");
        verifyNoInteractions(fixRepo);
    }

    @Test
    void 이미_닫힌_요청을_가리키면_결과를_덮지_않고_표시만_지운다() {
        Task t = t(DEPLOYING);
        TaskFixRequest r = request(2, 1);
        r.setOutcome(FixOutcome.ABORTED);   // 표시가 닫힌 요청을 가리키는 깨진 데이터

        svc.recordResult(42L, deployed());

        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.ABORTED);
        assertThat(histories().get(0).getReason()).endsWith(" (수정 회차 닫힘)");
        verify(fixRepo, never()).save(any());
    }

    // ── 배포가 빌드한 커밋 = head_sha (회차와 무관) ─────────

    private static WorkerResultRequest deployedBuilt(String builtSha) {
        return WorkerResultRequest.deployed("w1", "https://task-42-win.micthebick.dev", "c1", 19001, "img", 5L, "dlog",
                builtSha);
    }

    @Test
    void 배포완료가_빌드한_커밋을_실으면_head_sha를_바꾸고_이력에_남긴다() {
        Task t = t(DEPLOYING);
        t.setActiveFixRequestId(null);   // 보통 작업

        svc.recordResult(42L, deployedBuilt("fedcba9876543210fedcba9876543210fedcba98"));

        assertThat(t.getHeadSha()).isEqualTo("fedcba9876543210fedcba9876543210fedcba98");
        assertThat(histories().get(0).getReason())
                .isEqualTo("배포 완료: https://task-42-win.micthebick.dev (head_sha 0123456→fedcba9)");
    }

    @Test
    void 빌드한_커밋이_같으면_이력에_붙이지_않는다() {
        Task t = t(DEPLOYING);
        t.setActiveFixRequestId(null);

        svc.recordResult(42L, deployedBuilt(OLD_SHA));

        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        assertThat(histories().get(0).getReason()).isEqualTo("배포 완료: https://task-42-win.micthebick.dev");
    }

    @Test
    void 빌드한_커밋이_비어_있으면_head_sha는_그대로() {
        Task t = t(DEPLOYING);
        t.setActiveFixRequestId(null);

        svc.recordResult(42L, deployedBuilt("  "));

        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
        assertThat(histories().get(0).getReason()).isEqualTo("배포 완료: https://task-42-win.micthebick.dev");
    }

    @Test
    void 회차_자동_재배포도_빌드한_커밋을_남기고_회차를_닫는다() {
        Task t = t(DEPLOYING);
        t.setHeadSha("new9999");
        request(2, 1);

        svc.recordResult(42L, deployedBuilt("abcdef1234567890abcdef1234567890abcdef12"));

        assertThat(t.getHeadSha()).isEqualTo("abcdef1234567890abcdef1234567890abcdef12");
        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(histories().get(0).getReason())
                .isEqualTo("배포 완료: https://task-42-win.micthebick.dev (head_sha new9999→abcdef1) (수정 2회차 닫힘)");
    }

    @Test
    void 지각_배포완료_정합화도_빌드한_커밋을_남긴다() {
        Task t = t(DEPLOY_FAILED);
        t.setActiveFixRequestId(null);

        svc.recordResult(42L, deployedBuilt("fedcba9876543210fedcba9876543210fedcba98"));

        assertThat(t.getStatus()).isEqualTo(DEPLOYED);
        assertThat(t.getHeadSha()).isEqualTo("fedcba9876543210fedcba9876543210fedcba98");
        assertThat(histories().get(0).getReason())
                .isEqualTo("지각 보고 정합화: stale 회수 → 배포완료 (head_sha 0123456→fedcba9)");
    }

    @Test
    void 배포실패는_빌드한_커밋을_실어도_head_sha를_바꾸지_않는다() {
        Task t = t(DEPLOYING);
        t.setActiveFixRequestId(null);

        svc.recordResult(42L, new WorkerResultRequest("w1", TaskStatus.DEPLOY_FAILED,
                null, null, null, null, "docker build 실패",
                null, null, null, "fedcba9876543210fedcba9876543210fedcba98", null,
                null, null, null, null, "dlog",
                null, null, null, null, null));

        assertThat(t.getStatus()).isEqualTo(DEPLOY_FAILED);
        assertThat(t.getHeadSha()).isEqualTo(OLD_SHA);
    }
}
