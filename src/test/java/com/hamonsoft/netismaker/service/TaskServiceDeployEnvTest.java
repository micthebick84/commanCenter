package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 배포 다이얼로그 미리 채움 조회 (스펙 2026-10-07 §6.2). */
class TaskServiceDeployEnvTest {

    private TaskRepository taskRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(TaskStatusHistoryRepository.class), mock(McpCatalogService.class),
                mock(RepoCatalogService.class), new ObjectMapper(), mock(InterviewService.class),
                mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class));
    }

    private static Task task(long id, String repo, String gitUrl) {
        Task t = Task.create(repo, "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", id);
        t.setGitUrl(gitUrl);
        return t;
    }

    @Test
    void saved_env_skips_the_previous_deploy_lookup() {
        Task t = task(42L, "owner/repo", null);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_URL", "jdbc:saved", false))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L);

        assertThat(r.rows()).extracting(Row::value).containsExactly("jdbc:saved");
        verify(taskRepo, never()).findRecentlyDeployedWithEnv(any(), any(), any());
    }

    @Test
    void first_deploy_uses_the_most_recent_candidate_on_the_same_host() {
        Task t = task(42L, "group/app", "https://gitlab.hamon.vip/group/app.git");
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("DB_URL", "DB", false, true))));
        Task github = task(40L, "group/app", null);   // 같은 경로지만 GitHub — 섞이면 안 된다
        github.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_URL", "jdbc:github", false))));
        Task gitlab = task(39L, "group/app", "https://gitlab.hamon.vip/group/app.git");
        gitlab.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_URL", "jdbc:gitlab", false))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        when(taskRepo.findRecentlyDeployedWithEnv("group/app", 42L, TaskStatus.DEPLOY_PENDING.dbValue()))
                .thenReturn(List.of(github, gitlab));

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L);

        assertThat(r.rows()).extracting(Row::value).containsExactly("jdbc:gitlab");
        assertThat(r.previousTaskId()).isEqualTo(39L);
    }

    @Test
    void no_candidate_leaves_template_values_empty() {
        Task t = task(42L, "owner/repo", null);
        t.setEnvTemplate(new ArrayList<>(List.of(new EnvTemplateItem("DB_URL", "DB", false, true))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        when(taskRepo.findRecentlyDeployedWithEnv(any(), any(), any())).thenReturn(List.of());

        DeployEnvSuggestionResponse r = service.deployEnvSuggestion(42L);

        assertThat(r.rows()).extracting(Row::key, Row::value).containsExactly(org.assertj.core.groups.Tuple.tuple("DB_URL", ""));
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void deleted_or_missing_task_is_404() {
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deployEnvSuggestion(42L))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
