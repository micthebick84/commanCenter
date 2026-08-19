package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.QueueStats;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QueueStatsInterviewCountersTest {

    @Autowired private QueueStatsRepository statsRepo;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private void save(TaskStatus status) {
        Task t = Task.create("hamonsoft/netis-backend", "main", "제목", "설명", "user1", 3,
                List.of(), null, null);
        t.setStatus(status);
        taskRepo.save(t);
    }

    @Test
    void interview_counters_are_exposed() {
        save(TaskStatus.AWAITING_APPROVAL);
        save(TaskStatus.INTERVIEWING);
        save(TaskStatus.INTERVIEW_INPUT);
        save(TaskStatus.INTERVIEW_REVIEW);

        QueueStats s = statsRepo.fetch();

        assertThat(s.pendingApproval()).isEqualTo(1);
        assertThat(s.interviewing()).isEqualTo(2);   // 인터뷰중 + 입력대기
        assertThat(s.planReview()).isEqualTo(1);
        assertThat(s.pending()).isZero();            // 레거시 '작업대기'와 분리됨
    }
}
