package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskServiceApproveInterviewTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        analysisRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task register() {
        return taskService.create(new TaskCreateRequest(1L, "main", "RBAC 추가", "역할 기반 권한"), "user1");
    }

    @Test
    void approving_a_pending_task_starts_an_interview_session() {
        Task t = register();

        taskService.approve(t.getId(), "admin", new ApproveRequest(List.of(), "claude-opus-5", "max"));

        Task after = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.INTERVIEWING);
        assertThat(after.getModel()).isEqualTo("claude-opus-5");
        assertThat(after.getEffort()).isEqualTo("max");
        // 인터뷰 워커 소유권은 세션 컬럼이 갖는다 — task 쪽은 비워 둬야 StaleTaskRecoveryJob과 섞이지 않는다
        assertThat(after.getWorkerId()).isNull();
        assertThat(after.getClaimedAt()).isNull();

        List<InterviewSession> sessions = sessionRepo.findAll();
        assertThat(sessions).hasSize(1);
        InterviewSession s = sessions.get(0);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getTaskId()).isEqualTo(t.getId());
        assertThat(s.getRequesterId()).isEqualTo("user1");        // 원 요청자 보존
        assertThat(s.getGithubRepo()).isEqualTo(after.getGithubRepo());
        assertThat(s.getGithubBranch()).isEqualTo("main");
        assertThat(s.getModel()).isEqualTo("claude-opus-5");
        assertThat(s.getEffort()).isEqualTo("max");
    }

    @Test
    void approve_body_is_optional_and_falls_back_to_defaults() {
        Task t = register();

        taskService.approve(t.getId(), "admin", null);

        InterviewSession s = sessionRepo.findAll().get(0);
        assertThat(s.getModel()).isEqualTo(ModelEffortPolicy.DEFAULT_MODEL);
        assertThat(s.getEffort()).isEqualTo(ModelEffortPolicy.DEFAULT_EFFORT);
    }

    @Test
    void double_approve_is_rejected_and_creates_no_second_session() {
        Task t = register();
        taskService.approve(t.getId(), "admin", null);

        assertThatThrownBy(() -> taskService.approve(t.getId(), "admin", null))
                .isInstanceOf(TaskException.class);

        assertThat(sessionRepo.findAll()).hasSize(1);
    }

    @Test
    void legacy_completed_task_still_goes_straight_to_the_implementation_queue() {
        Task t = register();
        // 레거시 자동분석 경로 재현: 분석완료 + 분석 결과 존재
        t.setStatus(TaskStatus.COMPLETED);
        taskRepo.save(t);
        analysisRepo.save(TaskAnalysis.create(t.getId(), "# 분석", "[]", null, 1000L));

        taskService.approve(t.getId(), "admin", null);

        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(sessionRepo.findAll()).isEmpty();
    }

    /**
     * 동시 승인(더블클릭) 재현. findActiveByIdForUpdate의 FOR UPDATE(SKIP LOCKED 없음)
     * 없이 이 테스트를 돌리면 두 스레드가 모두 AWAITING_APPROVAL을 읽어 세션을 2개
     * 만들어낸다 — 실제 Postgres 트랜잭션으로 재현되는 결정적 테스트(락이 있으면
     * 두 번째 호출은 첫 번째 커밋을 기다렸다가 갱신된 상태를 보고 409로 실패한다).
     */
    @Test
    void concurrent_double_approve_creates_only_one_session() throws Exception {
        Task t = register();

        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    taskService.approve(t.getId(), "admin", null);
                    return true;
                } catch (TaskException e) {
                    return false;
                }
            }));
        }
        start.countDown();

        int successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(15, TimeUnit.SECONDS)) successes++;
        }
        pool.shutdown();

        assertThat(successes).as("정확히 한 번만 승인이 성공해야 함").isEqualTo(1);
        assertThat(sessionRepo.findAll()).as("세션은 정확히 1개만 생성돼야 함").hasSize(1);
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.INTERVIEWING);
    }

    @Test
    void invalid_model_effort_combination_is_rejected_before_any_session_exists() {
        Task t = register();

        assertThatThrownBy(() -> taskService.approve(t.getId(), "admin",
                new ApproveRequest(List.of(), "claude-haiku-4-5", "max")))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("effort");

        assertThat(sessionRepo.findAll()).isEmpty();
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void unknown_mcp_catalog_id_is_rejected_before_any_session_exists() {
        Task t = register();

        assertThatThrownBy(() -> taskService.approve(t.getId(), "admin",
                new ApproveRequest(List.of(999_999L), null, null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("MCP");

        assertThat(sessionRepo.findAll()).isEmpty();
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }
}
