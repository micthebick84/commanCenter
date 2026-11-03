package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수정 회차 동안 배포 컨테이너 GC 보호 (스펙 2026-11-03 §4·§8) — findDeployReconcilable() JPQL.
 * 수정승인대기이거나 active_fix_request_id가 채워진 작업은 어느 상태든 돌려줘야 하고, 회차 없는 구현중은 제외돼야 한다.
 * RUN_TESTCONTAINERS=true 전용.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskRepositoryFixRoundProtectionIntegrationTest {

    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** catalog id=1 = V14 seed (alias 'Netis7.0'). */
    private Task task(TaskStatus status, Long activeFixRequestId) {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "수정 회차 보호", "설명"), "user1");
        t.setStatus(status);
        t.setActiveFixRequestId(activeFixRequestId);
        return taskRepo.saveAndFlush(t);
    }

    @Test
    void 수정승인대기와_열린_회차_작업은_GC_보호_목록에_포함되고_회차_없는_구현중은_제외된다() {
        Task fixReview = task(TaskStatus.FIX_REVIEW, null);                  // 요청만 올라온 상태 — 회차 id는 승인 때 채워진다
        Task roundImplementing = task(TaskStatus.IMPLEMENTING, 7L);          // 회차 구현중
        Task roundPrCreated = task(TaskStatus.PR_CREATED, 7L);               // 회차 PR 생성
        Task roundImplFailed = task(TaskStatus.IMPLEMENTATION_FAILED, 7L);   // 회차 구현 실패 — 회차는 아직 열려 있다
        Task deployed = task(TaskStatus.DEPLOYED, null);                     // 기존 보호 대상은 그대로
        Task plainImplementing = task(TaskStatus.IMPLEMENTING, null);        // 회차 없는 구현중 — 컨테이너가 없다
        Task plainPrCreated = task(TaskStatus.PR_CREATED, null);

        List<Task> found = taskRepo.findDeployReconcilable();

        assertThat(found).extracting(Task::getId)
                .contains(fixReview.getId(), roundImplementing.getId(), roundPrCreated.getId(),
                        roundImplFailed.getId(), deployed.getId())
                .doesNotContain(plainImplementing.getId(), plainPrCreated.getId());
    }

    @Test
    void 삭제된_작업은_회차가_열려_있어도_제외된다() {
        Task deletedRound = task(TaskStatus.IMPLEMENTING, 7L);
        Task deletedFixReview = task(TaskStatus.FIX_REVIEW, null);
        for (Task t : List.of(deletedRound, deletedFixReview)) {
            ReflectionTestUtils.setField(t, "deletedAt", OffsetDateTime.now());
            taskRepo.saveAndFlush(t);
        }

        assertThat(taskRepo.findDeployReconcilable()).extracting(Task::getId)
                .doesNotContain(deletedRound.getId(), deletedFixReview.getId());
    }
}
