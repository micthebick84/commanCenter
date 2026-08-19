package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewTaskMirrorTest {

    @Autowired private TaskService taskService;
    @Autowired private InterviewService interviewService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    private Long taskId;
    private Long sid;

    @BeforeEach void seed() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "RBAC", "역할 기반 권한"), "user1");
        taskId = t.getId();
        taskService.approve(taskId, "admin", null);
        sid = sessionRepo.findAll().get(0).getId();
    }

    private TaskStatus taskStatus() {
        return taskRepo.findById(taskId).orElseThrow().getStatus();
    }

    private void askQuestion() {
        interviewService.claim("iw-1");
        interviewService.recordQuestion(sid, "iw-1",
                new WorkerQuestionRequest("권한 모델은 RBAC인가요?", "sess-1", "question", new BigDecimal("0.01")));
    }

    @Test
    void question_moves_task_to_input_waiting() {
        askQuestion();
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_INPUT);
    }

    @Test
    void answer_moves_task_back_to_interviewing() {
        askQuestion();
        int seq = interviewService.getResponse(sid, "admin", true).turns().get(0).seq();

        interviewService.submitAnswer(sid, "admin", true, new AnswerRequest("RBAC 맞습니다", seq));

        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEWING);
    }

    @Test
    void duplicate_answer_is_a_no_op_for_the_task_too() {
        askQuestion();
        int seq = interviewService.getResponse(sid, "admin", true).turns().get(0).seq();
        interviewService.submitAnswer(sid, "admin", true, new AnswerRequest("RBAC 맞습니다", seq));
        // 재큐된 세션을 다시 잡아 질문을 하나 더 던진 뒤, 1차 답변을 재제출한다.
        interviewService.claim("iw-1");
        interviewService.recordQuestion(sid, "iw-1",
                new WorkerQuestionRequest("다음 질문", "sess-1", "question", BigDecimal.ZERO));
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_INPUT);

        interviewService.submitAnswer(sid, "admin", true, new AnswerRequest("RBAC 맞습니다", seq));

        // 중복 답변은 no-op — 여전히 입력대기여야 한다 (멱등성 회귀 지점)
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_INPUT);
    }

    @Test
    void plan_moves_task_to_plan_review() {
        interviewService.claim("iw-1");
        interviewService.recordPlan(sid, "iw-1", new WorkerPlanRequest(
                "# 설계", "# 플랜", "[]", new BigDecimal("0.4"), 1000L));

        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_REVIEW);
    }

    @Test
    void failure_returns_the_task_to_awaiting_approval() {
        interviewService.claim("iw-1");
        interviewService.fail(sid, "stale-recovery", "워커 사망");

        assertThat(taskStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void expiry_returns_the_task_to_awaiting_approval() {
        askQuestion();
        interviewService.expire(sid, "idle TTL 초과");

        assertThat(taskStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void cancel_returns_the_task_to_awaiting_approval() {
        interviewService.cancel(sid, "admin", true);

        assertThat(taskStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void re_approval_after_failure_creates_a_second_session() {
        interviewService.claim("iw-1");
        interviewService.fail(sid, "stale-recovery", "워커 사망");

        taskService.approve(taskId, "admin", null);

        assertThat(sessionRepo.findAll()).hasSize(2);
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEWING);
    }
}
