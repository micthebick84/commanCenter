package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * getHistory — DB에서 상위 limit건만 가져오고, 같은 at 행은 id 내림차순으로 안정 정렬한다
 * (프론트 TaskHistoryTimeline의 "최신순" 전제). RUN_TESTCONTAINERS=true 전용.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskServiceHistoryTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll(); // task_status_history는 FK ON DELETE CASCADE로 함께 지워진다
    }

    // catalog id=1 = V14 seed (alias 'Netis7.0'). 등록이 '작업 등록' 이력 1행을 남긴다.
    private Task createTask() {
        return taskService.create(new TaskCreateRequest(1L, "main", "이력", "설명"), "user1");
    }

    /** 같은 at으로 이력 n행을 기록하고 id를 기록 순서대로 돌려준다 — log()는 at=now()라 리플렉션으로 고정. */
    private List<Long> logAtSameInstant(Long taskId, OffsetDateTime at, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            TaskStatusHistory h = TaskStatusHistory.log(taskId, TaskStatus.AWAITING_APPROVAL,
                    TaskStatus.AWAITING_APPROVAL, "system", "batch", "동률-" + i);
            ReflectionTestUtils.setField(h, "at", at);
            ids.add(historyRepo.save(h).getId());
        }
        return ids;
    }

    @Test
    void 같은_at의_행은_나중에_기록된_id가_먼저_온다() {
        Task t = createTask();
        // 등록 이력보다 뒤, 같은 밀리초에 몰린 전이 5건
        OffsetDateTime at = OffsetDateTime.now().plusMinutes(1).truncatedTo(ChronoUnit.MILLIS);
        List<Long> ids = logAtSameInstant(t.getId(), at, 5);

        List<TaskStatusHistory> rows = taskService.getHistory(t.getId(), 200);

        assertThat(rows).extracting(TaskStatusHistory::getId)
                .startsWith(ids.get(4), ids.get(3), ids.get(2), ids.get(1), ids.get(0))
                .hasSize(6);
        assertThat(rows.get(5).getReason()).isEqualTo("작업 등록"); // 가장 오래된 등록 이력이 맨 끝
    }

    @Test
    void limit만큼_최신_이력만_돌려준다() {
        Task t = createTask();
        OffsetDateTime at = OffsetDateTime.now().plusMinutes(1).truncatedTo(ChronoUnit.MILLIS);
        List<Long> ids = logAtSameInstant(t.getId(), at, 5);

        List<TaskStatusHistory> rows = taskService.getHistory(t.getId(), 3);

        assertThat(rows).extracting(TaskStatusHistory::getId)
                .containsExactly(ids.get(4), ids.get(3), ids.get(2));
    }
}
