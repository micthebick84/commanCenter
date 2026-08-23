package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QUEUED TTL 엔드투엔드: 인터뷰 서비스가 죽어 세션이 QUEUED에 체류하면
 * InterviewStaleRecoveryJob이 세션을 EXPIRED로 만료시키고 task를 승인대기로 복귀시킨다.
 * 만료된 세션은 이후 claim 후보에서도 사라져야 한다.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewQueuedTtlIntegrationTest {

    @Autowired private TaskService taskService;
    @Autowired private InterviewService interviewService;
    @Autowired private InterviewStaleRecoveryJob interviewStaleRecoveryJob;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskRepository taskRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    @Test
    void stale_queued_session_is_expired_and_task_returns_to_awaiting_approval() {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "RBAC 추가", "역할 기반 권한"), "user1");
        taskService.approve(t.getId(), "admin", null); // 세션 생성(QUEUED) + task 인터뷰중
        InterviewSession s = sessionRepo.findAll().get(0);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);

        // 인터뷰 서비스 미가동 상황 재현: 대기 시작 시각(lastActivityAt)을 TTL(60분) 이전으로 백데이트
        s.setLastActivityAt(OffsetDateTime.now().minusMinutes(120));
        sessionRepo.save(s);

        interviewStaleRecoveryJob.recover();

        InterviewSession after = sessionRepo.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(InterviewStatus.EXPIRED);
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.AWAITING_APPROVAL); // task 미러 복귀 → 재승인 가능
        assertThat(interviewService.claim("w")).as("만료된 세션은 claim 후보가 아니어야 함").isEmpty();
    }

    @Test
    void fresh_queued_session_survives_the_sweep() {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "메뉴 정리", "설명"), "user1");
        taskService.approve(t.getId(), "admin", null);
        InterviewSession s = sessionRepo.findAll().get(0);

        interviewStaleRecoveryJob.recover();

        assertThat(sessionRepo.findById(s.getId()).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.QUEUED);
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.INTERVIEWING);
    }
}
