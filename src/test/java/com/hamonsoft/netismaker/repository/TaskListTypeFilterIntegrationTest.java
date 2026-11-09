package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.service.TaskTypeFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 목록 타입 필터 쿼리 (스펙 2026-11-03 §4.3) — `findAllActive`(관리자)·`findByRequester`(요청자) 둘 다
 * "타입 없음"(IS NULL)·특정 타입·필터 없음을 실제 DB에서 가르고, 상태 조건과 AND로 걸린다.
 *
 * Testcontainers 기반 (RUN_TESTCONTAINERS=true 일 때만 실행, 기본은 skip — 이 PC에는 Docker가 없어 CI에서만 돈다).
 * 어노테이션·정리 순서는 ImplementationModeFlowIntegrationTest와 같다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskListTypeFilterIntegrationTest {

    private static final Pageable PAGE = PageRequest.of(0, 50);
    private static final TaskTypeFilter NONE = TaskTypeFilter.parse("NONE");

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

    private Task saved(String requester, TaskType type, TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "목록 " + type, "d", requester, 3, List.of(), "claude-opus-5-5", "high");
        t.setTaskType(type);
        t.setStatus(status);
        return taskRepo.save(t);
    }

    private List<Long> admin(TaskStatus status, TaskTypeFilter f) {
        return taskRepo.findAllActive(status, f.any(), f.type(), PAGE).map(Task::getId).getContent();
    }

    private List<Long> requester(String user, TaskStatus status, TaskTypeFilter f) {
        return taskRepo.findByRequester(user, status, f.any(), f.type(), PAGE).map(Task::getId).getContent();
    }

    @Test
    void 관리자_쿼리_필터없음_NONE_특정타입() {
        Long bug = saved("user1", TaskType.BUG_FIX, TaskStatus.PENDING).getId();
        Long none = saved("user1", null, TaskStatus.PENDING).getId();
        Long db = saved("user1", TaskType.DB_WORK, TaskStatus.PENDING).getId();

        assertThat(admin(null, TaskTypeFilter.ANY)).containsExactlyInAnyOrder(bug, none, db);
        assertThat(admin(null, NONE)).containsExactly(none);
        assertThat(admin(null, TaskTypeFilter.parse("BUG_FIX"))).containsExactly(bug);
        assertThat(admin(null, TaskTypeFilter.parse("DB_WORK"))).containsExactly(db);
        assertThat(admin(null, TaskTypeFilter.parse("NEW_FEATURE"))).isEmpty();
    }

    @Test
    void 요청자_쿼리_필터없음_NONE_특정타입_그리고_남의_작업은_빠진다() {
        Long bug = saved("user1", TaskType.BUG_FIX, TaskStatus.PENDING).getId();
        Long none = saved("user1", null, TaskStatus.PENDING).getId();
        Long db = saved("user1", TaskType.DB_WORK, TaskStatus.PENDING).getId();
        Long othersBug = saved("user2", TaskType.BUG_FIX, TaskStatus.PENDING).getId();
        Long othersNone = saved("user2", null, TaskStatus.PENDING).getId();

        assertThat(requester("user1", null, TaskTypeFilter.ANY)).containsExactlyInAnyOrder(bug, none, db);
        assertThat(requester("user1", null, NONE)).containsExactly(none);
        assertThat(requester("user1", null, TaskTypeFilter.parse("BUG_FIX"))).containsExactly(bug);
        assertThat(requester("user1", null, TaskTypeFilter.parse("DB_WORK"))).containsExactly(db);
        assertThat(requester("user1", null, TaskTypeFilter.parse("NEW_FEATURE"))).isEmpty();
        // 같은 필터를 관리자 쿼리로 보면 남의 작업도 들어온다
        assertThat(admin(null, NONE)).containsExactlyInAnyOrder(none, othersNone);
        assertThat(admin(null, TaskTypeFilter.parse("BUG_FIX"))).containsExactlyInAnyOrder(bug, othersBug);
    }

    @Test
    void 상태_조건과_AND로_걸린다() {
        Long bugPending = saved("user1", TaskType.BUG_FIX, TaskStatus.PENDING).getId();
        Long bugApproved = saved("user1", TaskType.BUG_FIX, TaskStatus.APPROVED).getId();
        Long noneApproved = saved("user1", null, TaskStatus.APPROVED).getId();
        Long dbPending = saved("user1", TaskType.DB_WORK, TaskStatus.PENDING).getId();

        for (boolean asAdmin : new boolean[] {true, false}) {
            assertThat(list(asAdmin, TaskStatus.APPROVED, TaskTypeFilter.ANY))
                    .containsExactlyInAnyOrder(bugApproved, noneApproved);
            assertThat(list(asAdmin, TaskStatus.APPROVED, TaskTypeFilter.parse("BUG_FIX")))
                    .containsExactly(bugApproved);
            assertThat(list(asAdmin, TaskStatus.APPROVED, NONE)).containsExactly(noneApproved);
            assertThat(list(asAdmin, TaskStatus.PENDING, NONE)).isEmpty();
            assertThat(list(asAdmin, TaskStatus.PENDING, TaskTypeFilter.ANY))
                    .containsExactlyInAnyOrder(bugPending, dbPending);
            assertThat(list(asAdmin, TaskStatus.APPROVED, TaskTypeFilter.parse("DB_WORK"))).isEmpty();
        }
    }

    private List<Long> list(boolean asAdmin, TaskStatus status, TaskTypeFilter f) {
        return asAdmin ? admin(status, f) : requester("user1", status, f);
    }
}
