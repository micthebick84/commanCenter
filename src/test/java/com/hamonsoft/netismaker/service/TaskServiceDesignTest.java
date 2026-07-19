package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 승인 라우팅 + 디자인 승인/반려/재시도 — Task 6.
 *
 * 실 Postgres 필요. RUN_TESTCONTAINERS=true에서만 실행.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskServiceDesignTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach
    void cleanUp() {
        // FK 위반 방지: task_design/task_analysis/interview_session(자식) 먼저 삭제, 그 다음 task(부모) 삭제
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** 지정 상태의 task를 저장. */
    private Task taskWithStatus(TaskStatus status, boolean designRequested) {
        Task t = Task.create("hamonsoft/netis-backend", "main", "T-" + System.nanoTime(),
                "설명", "user1", 3, List.of(), "claude-opus-4-8", "high");
        t.setDesignRequested(designRequested);
        t.setStatus(status);
        return taskRepo.save(t);
    }

    @Test
    void design_requested_작업은_분석_승인시_디자인대기로_간다() {
        Task t = taskWithStatus(TaskStatus.COMPLETED, true);
        analysisRepo.save(TaskAnalysis.create(t.getId(), "# 분석", "[]", "log", 100L));

        taskService.approve(t.getId(), "admin1");

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);

        TaskAnalysis a = analysisRepo.findById(t.getId()).orElseThrow();
        assertThat(a.isApproved()).isTrue();
    }

    @Test
    void 일반_작업은_분석_승인시_기존대로_구현대기로_간다() {
        Task t = taskWithStatus(TaskStatus.COMPLETED, false);
        analysisRepo.save(TaskAnalysis.create(t.getId(), "# 분석", "[]", "log", 100L));

        taskService.approve(t.getId(), "admin1");

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.APPROVED);
    }

    @Test
    void 디자인_승인은_구현대기로_전이하고_승인자를_기록한다() {
        Task t = taskWithStatus(TaskStatus.DESIGN_REVIEW, true);
        designRepo.save(TaskDesign.create(t.getId(), "# 디자인", "[]", null, null, "log", 100L));

        taskService.approveDesign(t.getId(), "admin1");

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.APPROVED);

        TaskDesign d = designRepo.findById(t.getId()).orElseThrow();
        assertThat(d.isApproved()).isTrue();
        assertThat(d.getApprovedBy()).isEqualTo("admin1");
    }

    @Test
    void 디자인_반려는_피드백을_누적하고_디자인대기로_재큐잉한다() {
        Task t = taskWithStatus(TaskStatus.DESIGN_REVIEW, true);
        t.setWorkerId("w1");
        taskRepo.save(t);
        designRepo.save(TaskDesign.create(t.getId(), "# 디자인", "[]", null, null, "log", 100L));

        taskService.rejectDesign(t.getId(), "admin1", "색상이 어둡습니다");

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(reloaded.getWorkerId()).isNull();

        TaskDesign d = designRepo.findById(t.getId()).orElseThrow();
        assertThat(d.getRejectCount()).isEqualTo(1);
        assertThat(d.getFeedbackHistoryJson()).contains("색상이 어둡습니다");
    }

    @Test
    void 반려_3회_도달_후_반려는_409() {
        Task t = taskWithStatus(TaskStatus.DESIGN_REVIEW, true);
        TaskDesign d = TaskDesign.create(t.getId(), "# 디자인", "[]", null, null, "log", 100L);
        d.setRejectCount(3);
        designRepo.save(d);

        assertThatThrownBy(() -> taskService.rejectDesign(t.getId(), "admin1", "또 반려"))
                .isInstanceOf(TaskException.class)
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }

    @Test
    void 디자인승인대기가_아니면_디자인_승인_반려_모두_409() {
        Task t = taskWithStatus(TaskStatus.COMPLETED, true);
        designRepo.save(TaskDesign.create(t.getId(), "# 디자인", "[]", null, null, "log", 100L));

        assertThatThrownBy(() -> taskService.approveDesign(t.getId(), "admin1"))
                .isInstanceOf(TaskException.class)
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));

        assertThatThrownBy(() -> taskService.rejectDesign(t.getId(), "admin1", "피드백"))
                .isInstanceOf(TaskException.class)
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }

    @Test
    void 디자인실패는_retry로_디자인대기_재큐잉된다() {
        Task t = taskWithStatus(TaskStatus.DESIGN_FAILED, true);
        t.setFailureReason("DesignSync 인증 실패");
        taskRepo.save(t);

        taskService.retry(t.getId(), "user1", false);

        Task reloaded = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(reloaded.getFailureReason()).isNull();
    }
}
