package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.DeployLogStreamService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 작업 응답의 비밀 env 값 — 관리자가 아니면 비운다 (스펙 2026-10-07 §7). 컨트롤러를 직접 만들어
 * 엔드포인트별 적용을 로컬에서 검증한다(권한 403·실제 DB 경로는 TaskDeployEnvApiIntegrationTest, CI).
 */
class TaskControllerSecretRedactionTest {

    private TaskService taskService;
    private TaskController controller;
    private Task task;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        controller = new TaskController(taskService, mock(DeployLogStreamService.class), mock(InterviewService.class));
        task = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setEnvVars(new ArrayList<>(List.of(
                new EnvVar("DB_URL", "jdbc:x", false),
                new EnvVar("DB_PASSWORD", "pw", true))));
        when(taskService.getForView(eq(5L), anyString(), anyBoolean())).thenReturn(task);
        when(taskService.cancel(eq(5L), anyString(), anyBoolean())).thenReturn(task);
        when(taskService.retry(eq(5L), anyString(), anyBoolean())).thenReturn(task);
        when(taskService.list(anyString(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(task)));
    }

    private static JwtAuthenticationToken auth(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("username", userId).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    private static List<String> values(TaskResponse r) {
        return r.envVars().stream().map(EnvVar::value).toList();
    }

    @Test
    void requester_responses_blank_secret_values() {
        JwtAuthenticationToken user = auth("user1", "ROLE_USER");

        assertThat(values(controller.get(5L, user))).containsExactly("jdbc:x", "");
        assertThat(values(controller.list(null, false, PageRequest.of(0, 20), user).getContent().get(0)))
                .containsExactly("jdbc:x", "");
        assertThat(values(controller.cancel(5L, user))).containsExactly("jdbc:x", "");
        assertThat(values(controller.retry(5L, user))).containsExactly("jdbc:x", "");
    }

    @Test
    void admin_responses_keep_secret_values() {
        JwtAuthenticationToken admin = auth("admin", "ROLE_ADMIN");

        assertThat(values(controller.get(5L, admin))).containsExactly("jdbc:x", "pw");
        assertThat(values(controller.list(null, false, PageRequest.of(0, 20), admin).getContent().get(0)))
                .containsExactly("jdbc:x", "pw");
    }

    @Test
    void deploy_env_returns_the_service_suggestion() {
        var suggestion = new DeployEnvSuggestionResponse(List.of(), 0, null);
        when(taskService.deployEnvSuggestion(5L)).thenReturn(suggestion);

        assertThat(controller.deployEnv(5L)).isSameAs(suggestion);
    }
}
