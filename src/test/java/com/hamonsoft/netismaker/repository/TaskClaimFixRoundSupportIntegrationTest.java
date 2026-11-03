package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskService;
import com.hamonsoft.netismaker.service.WorkerCapabilities;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fixRounds를 알리지 않은 워커(구버전)는 수정 회차가 열린 구현대기를 claim하지 못한다 (스펙 2026-11-03 §7) —
 * 네이티브 claim 쿼리의 active_fix_request_id 조건. 어느 claim 경로(전체 큐·종류를 고른 큐)에서도 같아야 하고,
 * 회차 안의 다른 상태(자동 재배포 등)는 막지 않는다. RUN_TESTCONTAINERS=true 전용 (이 PC에는 Docker가 없어 CI가 돌린다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskClaimFixRoundSupportIntegrationTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final WorkerCapabilities OLD = new WorkerCapabilities(true, false);   // 참조는 알지만 회차는 모른다
    private static final WorkerCapabilities NEW = new WorkerCapabilities(true, true);
    private static final WorkerCapabilities OLDEST = new WorkerCapabilities(false, false);

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskFixRequestRepository fixRepo;
    @Autowired private TaskService taskService;
    @Autowired private WorkerService workerService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        fixRepo.deleteAll();
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** catalog id=1 = V14 seed. 생성 시각을 minutesAgo분 전으로 당겨 claim 순서(FIFO)를 정한다. */
    private Task task(TaskStatus status, int minutesAgo) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "회차 claim", "설명"), "user1");
        t.setStatus(status);
        t.setHeadBranch("netismaker/task-" + t.getId() + "-x");
        t.setHeadSha(SHA);
        t = taskRepo.saveAndFlush(t);
        jdbc.update("UPDATE com.task SET created_at = now() - make_interval(mins => CAST(? AS integer)) WHERE id = ?",
                minutesAgo, t.getId());
        return t;
    }

    /** 수정 회차가 열린 작업 — 승인된 요청(round, mode) 하나를 만들고 작업이 그 요청을 가리키게 한다. */
    private Task withOpenRound(Task t, int round, ImplementationMode mode, String body) {
        TaskFixRequest r = TaskFixRequest.pending(t.getId(), body, TaskStatus.DEPLOYED, "user1");
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(mode);
        r = fixRepo.saveAndFlush(r);
        t.setActiveFixRequestId(r.getId());
        return taskRepo.saveAndFlush(t);
    }

    private Task withRef(Task t) {
        t.setRefRepos(new ArrayList<>(List.of(new RefRepo(11L, "옛앱", "g/old",
                "https://gitlab.hamon.vip/g/old.git", "release/6.5", SHA, "2026-10-24T10:00+09:00", null))));
        return taskRepo.saveAndFlush(t);
    }

    @Test
    void fixRounds를_모르는_워커는_회차가_열린_구현대기를_건너뛴다() {
        Task fix = withOpenRound(task(TaskStatus.APPROVED, 60), 1, ImplementationMode.REVIEW, "탭 갱신 안 됨");
        Task pending = task(TaskStatus.PENDING, 10);

        WorkerTaskResponse old = workerService.claimNextTask("old-worker", OLD, null).orElseThrow();
        assertThat(old.id()).isEqualTo(pending.getId());
        assertThat(workerService.claimNextTask("old-worker", OLD, null)).isEmpty();   // 회차 구현대기는 대기로 남는다
        assertThat(taskRepo.findById(fix.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.APPROVED);

        WorkerTaskResponse fresh = workerService.claimNextTask("new-worker", NEW, null).orElseThrow();
        assertThat(fresh.id()).isEqualTo(fix.getId());
        assertThat(fresh.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(fresh.implementation().mode()).isEqualTo("REVIEW");
        assertThat(fresh.implementation().timeoutMinutes()).isEqualTo(80);
        WorkerTaskResponse.FixRoundSpec spec = fresh.implementation().fix();
        assertThat(spec.round()).isEqualTo(1);
        assertThat(spec.body()).isEqualTo("탭 갱신 안 됨");
        assertThat(spec.previousBodies()).isEmpty();
        assertThat(spec.headBranch()).isEqualTo("netismaker/task-" + fix.getId() + "-x");
        assertThat(spec.headSha()).isEqualTo(SHA);
        assertThat(taskRepo.findById(fix.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
    }

    @Test
    void 회차_없는_구현대기는_fixRounds를_몰라도_가져간다() {
        Task plain = task(TaskStatus.APPROVED, 30);

        WorkerTaskResponse r = workerService.claimNextTask("old-worker", OLD, null).orElseThrow();

        assertThat(r.id()).isEqualTo(plain.getId());
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(r.implementation().fix()).isNull();
    }

    @Test
    void 종류를_고른_워커에게도_같은_가드가_걸린다() {
        Task fix = withOpenRound(task(TaskStatus.APPROVED, 50), 1, ImplementationMode.DIRECT, "수정");
        Task design = task(TaskStatus.DESIGN_PENDING, 20);
        var kinds = EnumSet.of(WorkerTaskResponse.Kind.IMPLEMENTATION, WorkerTaskResponse.Kind.DESIGN);

        // 더 오래된 회차 구현대기를 건너뛰고 뒤의 디자인대기를 잡는다 — 큐 머리가 막히지 않는다
        assertThat(workerService.claimNextTask("remote-1", OLD, kinds).orElseThrow().id()).isEqualTo(design.getId());
        assertThat(workerService.claimNextTask("remote-1", OLD, kinds)).isEmpty();
        assertThat(workerService.claimNextTask("remote-1", true, kinds)).isEmpty();   // (id, boolean, kinds) = fixRounds 모름

        WorkerTaskResponse fresh = workerService.claimNextTask("remote-2", NEW, kinds).orElseThrow();
        assertThat(fresh.id()).isEqualTo(fix.getId());
        assertThat(fresh.implementation().mode()).isEqualTo("DIRECT");
        assertThat(fresh.implementation().timeoutMinutes()).isEqualTo(45);
    }

    @Test
    void 예전_오버로드는_모두_회차를_모르는_워커로_본다() {
        withOpenRound(task(TaskStatus.APPROVED, 50), 1, ImplementationMode.REVIEW, "수정");

        assertThat(workerService.claimNextTask("w", true)).isEmpty();
        assertThat(workerService.claimNextTask("w", false)).isEmpty();
        assertThat(workerService.claimNextTask("w", true, EnumSet.of(WorkerTaskResponse.Kind.IMPLEMENTATION))).isEmpty();
        // 1인자 = 둘 다 아는 워커 — 기존 전체 큐(JPQL)
        assertThat(workerService.claimNextTask("w")).isPresent();
    }

    @Test
    void 회차_안의_자동_재배포와_종료_같은_다른_상태는_막지_않는다() {
        Task deploy = withOpenRound(task(TaskStatus.DEPLOY_PENDING, 30), 1, ImplementationMode.REVIEW, "수정");
        Task undeploy = withOpenRound(task(TaskStatus.UNDEPLOY_PENDING, 20), 2, ImplementationMode.REVIEW, "수정");

        WorkerTaskResponse first = workerService.claimNextTask("old-worker", OLD, null).orElseThrow();
        WorkerTaskResponse second = workerService.claimNextTask("old-worker", OLD, null).orElseThrow();

        assertThat(first.id()).isEqualTo(deploy.getId());
        assertThat(first.kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        assertThat(second.id()).isEqualTo(undeploy.getId());
        assertThat(second.kind()).isEqualTo(WorkerTaskResponse.Kind.UNDEPLOY);
    }

    private TaskFixRequest request(Task t, Integer round, FixRequestStatus status, ImplementationMode mode, String body) {
        TaskFixRequest r = TaskFixRequest.pending(t.getId(), body, TaskStatus.DEPLOYED, "user1");
        r.setStatus(status);
        r.setRound(round);
        r.setImplementationMode(mode);
        return fixRepo.saveAndFlush(r);
    }

    @Test
    void 이전_회차_본문은_오래된_회차_순으로_싣고_반려_철회_요청은_뺀다() {
        Task t = task(TaskStatus.APPROVED, 40);
        request(t, 1, FixRequestStatus.APPROVED, ImplementationMode.REVIEW, "장기분석 로딩");
        request(t, null, FixRequestStatus.REJECTED, null, "반려된 요청");
        request(t, 2, FixRequestStatus.APPROVED, ImplementationMode.REVIEW, "대상장비 탭");
        TaskFixRequest third = request(t, 3, FixRequestStatus.APPROVED, ImplementationMode.DIRECT, "세 번째");
        t.setActiveFixRequestId(third.getId());
        taskRepo.saveAndFlush(t);

        WorkerTaskResponse r = workerService.claimNextTask("new-worker", NEW, null).orElseThrow();

        assertThat(r.implementation().fix().round()).isEqualTo(3);
        assertThat(r.implementation().fix().previousBodies()).containsExactly("장기분석 로딩", "대상장비 탭");
    }

    @Test
    void 참조와_회차가_둘_다_있으면_둘_다_아는_워커만_가져간다() {
        Task both = withRef(withOpenRound(task(TaskStatus.APPROVED, 30), 1, ImplementationMode.REVIEW, "수정"));

        assertThat(workerService.claimNextTask("a", OLD, null)).isEmpty();               // 참조만 안다
        assertThat(workerService.claimNextTask("b", new WorkerCapabilities(false, true), null)).isEmpty();   // 회차만 안다
        assertThat(workerService.claimNextTask("c", OLDEST, null)).isEmpty();
        WorkerTaskResponse r = workerService.claimNextTask("d", NEW, null).orElseThrow();
        assertThat(r.id()).isEqualTo(both.getId());
        assertThat(r.refRepos()).hasSize(1);
        assertThat(r.implementation().fix()).isNotNull();
    }

    @Test
    void 요청_기록이_없는_회차_표시는_새_브랜치를_따지_않고_구현실패가_된다() {
        Task broken = task(TaskStatus.APPROVED, 30);
        broken.setActiveFixRequestId(987_654L);   // 가리키는 요청이 없다 (수동 편집 등)
        taskRepo.saveAndFlush(broken);
        Task next = task(TaskStatus.PENDING, 10);

        assertThat(workerService.claimNextTask("new-worker", NEW, null)).isEmpty();
        assertThat(taskRepo.findById(broken.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        // 큐 머리가 치워졌으니 다음 poll은 뒤의 작업을 잡는다
        assertThat(workerService.claimNextTask("new-worker", NEW, null).orElseThrow().id()).isEqualTo(next.getId());
    }
}
