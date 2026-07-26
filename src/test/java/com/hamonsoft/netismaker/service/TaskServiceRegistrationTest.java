package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskServiceRegistrationTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** catalog id=1 = V14 시드 항목 (alias 'Netis7.0'). */
    private TaskCreateRequest req(String title) {
        return new TaskCreateRequest(1L, "main", title, "설명");
    }

    @Test
    void create_starts_in_awaiting_approval_without_model_or_mcps() {
        Task t = taskService.create(req("작업 A"), "user1");

        assertThat(t.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(t.getMcpsExtra()).isEmpty();
        assertThat(t.isDesignRequested()).isFalse();
        assertThat(t.getModel()).isEqualTo(ModelEffortPolicy.DEFAULT_MODEL);
        assertThat(t.getEffort()).isEqualTo(ModelEffortPolicy.DEFAULT_EFFORT);
        assertThat(historyRepo.findAll()).anySatisfy(h ->
                assertThat(h.getToStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL.dbValue()));
    }

    @Test
    void awaiting_approval_counts_against_the_user_limit() {
        for (int i = 0; i < 5; i++) taskService.create(req("작업 " + i), "user1");

        assertThatThrownBy(() -> taskService.create(req("여섯번째"), "user1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("한도");
    }

    @Test
    void requester_can_cancel_while_awaiting_approval() {
        Task t = taskService.create(req("작업 A"), "user1");

        Task cancelled = taskService.cancel(t.getId(), "user1", false);

        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }
}
