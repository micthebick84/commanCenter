package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 구현 성공(PR_CREATED) 보고의 배포 env 템플릿 저장 (스펙 2026-10-07 §5). */
class WorkerServiceEnvTemplateTest {

    private TaskRepository taskRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo,
                mock(com.hamonsoft.netismaker.repository.TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(com.hamonsoft.netismaker.repository.InterviewSessionRepository.class),
                mock(com.hamonsoft.netismaker.repository.InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task taskIn(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 16L);
        t.setStatus(status);
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));
        return t;
    }

    private static WorkerResultRequest prCreated(List<EnvTemplateItem> template) {
        return new WorkerResultRequest("mac-worker-1", TaskStatus.PR_CREATED,
                null, null, null, 123L, null,
                "https://github.com/o/r/pull/10", 10, "netismaker/task-16", "abcdef1", "impl log",
                null, null, null, null, null,
                null, null, null, null, null,
                template);
    }

    @Test
    void pr_created_stores_the_sanitized_template() {
        Task t = taskIn(TaskStatus.IMPLEMENTING);

        service.recordResult(16L, prCreated(List.of(
                new EnvTemplateItem(" JWT_SECRET ", "서명 키", true, true),
                new EnvTemplateItem("BAD-KEY", "", false, false))));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).containsExactly(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }

    @Test
    void an_old_worker_without_template_leaves_it_untouched() {
        Task t = taskIn(TaskStatus.IMPLEMENTING);
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("KEEP", "", false, false))));

        service.recordResult(16L, prCreated(null));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).extracting(EnvTemplateItem::key).containsExactly("KEEP");
    }

    @Test
    void late_pr_created_reconciliation_also_stores_the_template() {
        Task t = taskIn(TaskStatus.IMPLEMENTATION_FAILED);

        service.recordResult(16L, prCreated(List.of(new EnvTemplateItem("DB_URL", "", false, true))));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).extracting(EnvTemplateItem::key).containsExactly("DB_URL");
    }

    @Test
    void undeploy_return_to_pr_created_does_not_touch_the_template() {
        Task t = taskIn(TaskStatus.UNDEPLOYING);
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("KEEP", "", false, false))));

        service.recordResult(16L, new WorkerResultRequest("mac-worker-1", TaskStatus.PR_CREATED,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, "undeploy log",
                null, null, null, null, null,
                List.of(new EnvTemplateItem("OTHER", "", false, false))));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getEnvTemplate()).extracting(EnvTemplateItem::key).containsExactly("KEEP");
    }
}
