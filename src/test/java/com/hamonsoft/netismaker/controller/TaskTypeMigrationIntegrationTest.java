package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V39 작업 타입 칸의 DB 제약 통합 테스트 (스펙 2026-11-03 §4.2·§7) — 모르는 값은 이름 있는 제약으로 거절하고,
 * NULL(타입 없음)과 네 값은 받는다. 엔티티 매핑이 ddl-auto: validate를 통과하는지도 컨텍스트 기동으로 본다.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 * 어노테이션·정리 순서는 ImplementationModeFlowIntegrationTest와 같다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskTypeMigrationIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void cleanTasks() {
        // interview_session.task_id FK는 non-cascade라 세션을 task보다 먼저 삭제
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task saved(TaskType type) {
        Task t = Task.create("owner/repo", "main", "타입 제약", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        t.setTaskType(type);
        return taskRepo.save(t);
    }

    @Test
    void 모르는_값은_이름있는_제약으로_거절() {
        Long id = saved(null).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE com.task SET task_type = 'QA' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_task_task_type");
        // 소문자도 다른 값이다
        assertThatThrownBy(() -> jdbc.update("UPDATE com.task SET task_type = 'bug_fix' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_task_task_type");
    }

    @Test
    void NULL과_네_값은_받고_엔티티로_다시_읽힌다() {
        // 기존 행처럼 타입이 없는 작업은 NULL 그대로
        Long none = saved(null).getId();
        assertThat(jdbc.queryForObject("SELECT task_type FROM com.task WHERE id = ?", String.class, none)).isNull();
        assertThat(taskRepo.findById(none).orElseThrow().getTaskType()).isNull();

        for (TaskType type : TaskType.values()) {
            Long id = saved(type).getId();
            assertThat(jdbc.queryForObject("SELECT task_type FROM com.task WHERE id = ?", String.class, id))
                    .isEqualTo(type.name());
            assertThat(taskRepo.findById(id).orElseThrow().getTaskType()).isEqualTo(type);
        }
    }
}
