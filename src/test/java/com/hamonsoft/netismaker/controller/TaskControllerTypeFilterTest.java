package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
import com.hamonsoft.netismaker.service.DeployLogStreamService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.service.TaskFixService;
import com.hamonsoft.netismaker.service.TaskService;
import com.hamonsoft.netismaker.service.TaskTypeFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 작업 목록 type 쿼리 파라미터 (스펙 2026-11-03 §4.3) — 컨트롤러가 값을 {@link TaskTypeFilter}로 해석해 서비스에 넘긴다.
 * 없으면 필터 없음, 모르는 값은 서비스를 부르기 전에 400. 컨트롤러를 직접 만들어 확인한다(TaskControllerRefReposTest와 같은 구성).
 */
class TaskControllerTypeFilterTest {

    private static final Pageable PAGE = PageRequest.of(0, 20);

    private TaskService taskService;
    private TaskController controller;
    private JwtAuthenticationToken user;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        controller = new TaskController(taskService, mock(DeployLogStreamService.class), mock(InterviewService.class),
                mock(TaskFixService.class));
        Task task = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 5L);
        when(taskService.list(anyString(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(task)));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("username", "user1").build();
        user = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void type_파라미터가_없으면_필터_없음() {
        controller.list(null, null, false, PAGE, user);

        verify(taskService).list("user1", false, false, null, TaskTypeFilter.ANY, PAGE);
    }

    @Test
    void enum_이름은_그_타입만_고르고_상태와_함께_넘어간다() {
        controller.list(TaskStatus.APPROVED, "DB_WORK", true, PAGE, user);

        verify(taskService).list("user1", false, true, TaskStatus.APPROVED,
                new TaskTypeFilter(false, TaskType.DB_WORK), PAGE);
    }

    @Test
    void NONE은_타입_없음만() {
        controller.list(null, "NONE", false, PAGE, user);

        verify(taskService).list("user1", false, false, null, new TaskTypeFilter(false, null), PAGE);
    }

    @Test
    void 모르는_값은_서비스를_부르기_전에_400() {
        assertThatThrownBy(() -> controller.list(null, "bug", false, PAGE, user))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("알 수 없는 작업 타입 필터: bug");
                });

        verify(taskService, never()).list(any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }
}
