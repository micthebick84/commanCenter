package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V33 task.ref_repos(jsonb)·task.source_question_id 매핑 (스펙 2026-10-24 §3.1). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskRefReposIntegrationTest {

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

    // catalog id=1 = V14 seed (alias 'Netis7.0')
    @Test
    void 작업_참조와_출처_질문이_JSONB로_왕복한다() {
        RefRepo ref = new RefRepo(11L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5",
                "0123456789abcdef0123456789abcdef01234567", "2026-10-24T10:00+09:00", "권한 없음");
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "참조", "설명"), "user1");
        t.setRefRepos(new ArrayList<>(List.of(ref)));
        t.setSourceQuestionId(77L);
        taskRepo.saveAndFlush(t);

        Task back = taskRepo.findById(t.getId()).orElseThrow();

        assertThat(back.getRefRepos()).containsExactly(ref);
        assertThat(back.getSourceQuestionId()).isEqualTo(77L);
    }

    @Test
    void 새_작업은_빈_배열과_null이다() {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "참조 없음", "설명"), "user1");

        Task back = taskRepo.findById(t.getId()).orElseThrow();

        assertThat(back.getRefRepos()).isEmpty();
        assertThat(back.getSourceQuestionId()).isNull();
    }
}
