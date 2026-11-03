package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수정 회차 한 바퀴를 실제 DB로 (스펙 2026-11-03 §4·§7·§7.1) — 관리자 요청 → 회차 구현 claim → 같은 브랜치 성공 보고 → 자동 재배포 →
 * 배포완료(회차 닫힘), 그리고 구현실패 → 같은 회차 재시도(이전 시도의 늦은 보고 무시) → 다시 실패 → 수정 중단(PR생성, GC 보호 풀림).
 * 요청 기록의 시도 번호·결과가 저장되는지까지 본다. RUN_TESTCONTAINERS=true 전용 (이 PC에는 Docker가 없어 CI가 돌린다).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskFixRoundLifecycleIntegrationTest {

    private static final WorkerCapabilities NEW = new WorkerCapabilities(true, true);
    private static final String BRANCH = "netismaker/task-fix-x";
    private static final String BASE_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEW_SHA = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String WIP_SHA = "1111111111111111111111111111111111111111";
    private static final String PR_URL = "https://github.com/owner/repo/pull/3";

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskFixRequestRepository fixRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskFixService fixService;
    @Autowired private TaskService taskService;
    @Autowired private WorkerService workerService;

    @BeforeEach
    void clean() {
        fixRepo.deleteAll();
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** 배포된 작업 — 이어 갈 브랜치·커밋·PR과 저장된 env가 있다. */
    private Long deployedTask() {
        Task t = Task.create("owner/repo", "main", "수정 회차", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.DEPLOYED);
        t.setPrNumber(3);
        t.setPrUrl(PR_URL);
        t.setHeadBranch(BRANCH);
        t.setHeadSha(BASE_SHA);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("A", "1", false))));
        return taskRepo.save(t).getId();
    }

    private Task reload(Long id) {
        return taskRepo.findById(id).orElseThrow();
    }

    /** Task 6 워커 모양 — 회차 구현 결과에 claim의 fix.requestId·fix.attempt를 되돌려 붙인다. */
    private static WorkerResultRequest success(String sha, long requestId, int fixAttempt) {
        return new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                null, null, BRANCH, sha, "fix log",
                null, null, null, null, null,
                null, null, null, null, null)
                .withImplementationAttempt(1).withFixAttempt(fixAttempt).withFixRequestId(requestId);
    }

    private static WorkerResultRequest failure(String sha, long requestId, int fixAttempt) {
        return new WorkerResultRequest("w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "claude exit=1",
                null, null, BRANCH, sha, "fail log",
                null, null, null, null, null,
                null, null, null, null, null)
                .withImplementationAttempt(1).withFixAttempt(fixAttempt).withFixRequestId(requestId);
    }

    @Test
    void 회차_성공은_같은_env로_자동_재배포되고_배포완료에_회차가_닫힌다() {
        Long id = deployedTask();
        fixService.request(id, "admin", true, "대상장비 탭 갱신 안 됨", "DIRECT");
        Long requestId = reload(id).getActiveFixRequestId();
        assertThat(requestId).isNotNull();

        WorkerTaskResponse impl = workerService.claimNextTask("w1", NEW, null).orElseThrow();
        assertThat(impl.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(impl.implementation().fix().attempt()).isEqualTo(1);

        workerService.recordResult(id, success(NEW_SHA, requestId, 1));

        Task pending = reload(id);
        assertThat(pending.getStatus()).isEqualTo(TaskStatus.DEPLOY_PENDING);
        assertThat(pending.getHeadSha()).isEqualTo(NEW_SHA);
        assertThat(pending.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(pending.getPrUrl()).isEqualTo(PR_URL);
        assertThat(pending.getImplementationAttempt()).isEqualTo(1);
        assertThat(pending.getEnvVars()).extracting(EnvVar::key).containsExactly("A");
        assertThat(pending.getActiveFixRequestId()).isEqualTo(requestId);

        WorkerTaskResponse deploy = workerService.claimNextTask("w1", NEW, null).orElseThrow();
        assertThat(deploy.kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        // 배포는 브랜치 끝을 빌드한다 — 그사이 사람이 MR에 push했으면 그 커밋이 head_sha가 된다
        String built = "2222222222222222222222222222222222222222";
        workerService.recordResult(id, WorkerResultRequest.deployed("w1", "https://task-x-win.micthebick.dev",
                "c1", 19001, "img", 5L, "dlog", built));

        Task done = reload(id);
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(done.getHeadSha()).isEqualTo(built);
        assertThat(done.getActiveFixRequestId()).isNull();
        TaskFixRequest closed = fixRepo.findById(requestId).orElseThrow();
        assertThat(closed.getOutcome()).isEqualTo(FixOutcome.DEPLOYED);
        assertThat(closed.getClosedAt()).isNotNull();

        // 회차가 닫혔으니 다음 수정 요청을 받는다(2회차)
        fixService.request(id, "admin", true, "하나 더", null);
        assertThat(fixRepo.findById(reload(id).getActiveFixRequestId()).orElseThrow().getRound()).isEqualTo(2);
    }

    @Test
    void 회차_실패는_같은_회차로_재시도되고_수정_중단이_회차를_닫는다() {
        Long id = deployedTask();
        fixService.request(id, "admin", true, "로딩이 끝나지 않음", null);
        Long requestId = reload(id).getActiveFixRequestId();

        workerService.claimNextTask("w1", NEW, null).orElseThrow();
        workerService.recordResult(id, failure(WIP_SHA, requestId, 1));
        Task failed = reload(id);
        assertThat(failed.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(failed.getHeadSha()).isEqualTo(WIP_SHA);
        assertThat(failed.getActiveFixRequestId()).isEqualTo(requestId);

        taskService.retryImplementation(id, "admin", null);
        Task requeued = reload(id);
        assertThat(requeued.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(requeued.getHeadBranch()).isEqualTo(BRANCH);
        assertThat(requeued.getHeadSha()).isEqualTo(WIP_SHA);
        assertThat(requeued.getImplementationAttempt()).isEqualTo(1);
        assertThat(fixRepo.findById(requestId).orElseThrow().getAttempt()).isEqualTo(2);

        WorkerTaskResponse again = workerService.claimNextTask("w1", NEW, null).orElseThrow();
        assertThat(again.implementation().fix().attempt()).isEqualTo(2);
        assertThat(again.implementation().fix().headSha()).isEqualTo(WIP_SHA);

        // 1회째의 늦은 성공 보고는 무시된다
        workerService.recordResult(id, success(NEW_SHA, requestId, 1));
        assertThat(reload(id).getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);

        workerService.recordResult(id, failure(null, requestId, 2));
        assertThat(reload(id).getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);

        fixService.abort(id, "admin");

        Task aborted = reload(id);
        assertThat(aborted.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(aborted.getActiveFixRequestId()).isNull();
        assertThat(aborted.getHeadSha()).isEqualTo(WIP_SHA);
        assertThat(fixRepo.findById(requestId).orElseThrow().getOutcome()).isEqualTo(FixOutcome.ABORTED);
        // PR생성 + 회차 없음 → 배포 컨테이너 GC 보호도 풀린다
        assertThat(taskRepo.findDeployReconcilable()).extracting(Task::getId).doesNotContain(id);
    }
}
