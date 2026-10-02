package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /worker/active-worktree-tasks — worktree 정리 잡 보호 목록(네 상태만) + 워커 키 인증. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class WorkerActiveWorktreeTasksIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach
    void clean() {
        // interview_session.task_id FK는 non-cascade라 세션을 task보다 먼저 삭제
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Long save(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", status.name(), "d", "user1", 3, List.of(), "claude-opus-4-8", "high");
        t.setStatus(status);
        return taskRepo.save(t).getId();
    }

    @Test
    void returns_only_the_four_worktree_in_use_statuses_and_skips_soft_deleted() throws Exception {
        List<Integer> expected = new ArrayList<>();
        for (TaskStatus s : TaskStatus.values()) {
            Long id = save(s);
            if (s == TaskStatus.IMPLEMENTING || s == TaskStatus.DESIGNING
                    || s == TaskStatus.DEPLOYING || s == TaskStatus.UNDEPLOYING) {
                expected.add(id.intValue());
            }
        }
        // soft-delete된 진행 중 작업은 상태가 안 바뀌어 영구 보호가 되므로 목록에서 빠져야 한다
        Long deletedId = save(TaskStatus.IMPLEMENTING);
        Task deleted = taskRepo.findById(deletedId).orElseThrow();
        deleted.setDeletedAt(java.time.OffsetDateTime.now());
        taskRepo.save(deleted);

        mvc.perform(get("/worker/active-worktree-tasks").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(expected.toArray())));
    }

    @Test
    void rejects_a_request_without_the_worker_key() throws Exception {
        mvc.perform(get("/worker/active-worktree-tasks"))
                .andExpect(status().is4xxClientError());
    }
}
