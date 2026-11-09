package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbWriteService;
import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB 변경 조회·관리자 실행·실행 안 함 엔드포인트 (스펙 2026-11-03 §6.5) — 컨트롤러를 직접 만들어 인자 전달·권한 어노테이션·
 * 조회 권한을 로컬에서 확인한다(실제 DB 경로·403 왕복은 TaskDbChangeFlowIntegrationTest, CI).
 */
class TaskDbChangeControllerTest {

    private DbWriteService writes;
    private TaskService taskService;
    private TaskDbChangeController controller;
    private final JwtAuthenticationToken user = auth("user1", "ROLE_USER");
    private final JwtAuthenticationToken admin = auth("admin", "ROLE_ADMIN");

    private static JwtAuthenticationToken auth(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("username", userId).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    private static DbChangeView view(long id, String classification) {
        return new DbChangeView(id, 7L, "개발DB (PostgreSQL)", 1, null, "ALTER TABLE t DROP COLUMN c", classification,
                null, null, null, OffsetDateTime.parse("2026-11-04T00:00:00Z"), null, null);
    }

    @BeforeEach
    void setUp() {
        writes = mock(DbWriteService.class);
        taskService = mock(TaskService.class);
        controller = new TaskDbChangeController(writes, taskService);
    }

    @Test
    void 목록은_등록자와_관리자만() {
        when(taskService.getForView(42L, "user1", false)).thenReturn(mock(Task.class));
        when(writes.list(42L)).thenReturn(List.of(view(12L, "EXECUTED"), view(13L, "DEFERRED")));

        assertThat(controller.list(42L, user)).extracting(DbChangeView::id).containsExactly(12L, 13L);
        verify(taskService).getForView(42L, "user1", false);

        // 관리자는 남의 작업도 본다 — 권한 판정은 작업 조회(getForView)와 같다
        when(taskService.getForView(42L, "admin", true)).thenReturn(mock(Task.class));
        controller.list(42L, admin);
        verify(taskService).getForView(42L, "admin", true);

        // 남의 작업은 403, 없거나 지워진 작업은 404 — 기록을 읽지 않는다
        when(taskService.getForView(43L, "user1", false)).thenThrow(TaskException.forbidden());
        assertThatThrownBy(() -> controller.list(43L, user))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        when(taskService.getForView(44L, "user1", false)).thenThrow(TaskException.notFound());
        assertThatThrownBy(() -> controller.list(44L, user))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(writes, never()).list(43L);
        verify(writes, never()).list(44L);
    }

    @Test
    void 실행과_실행안함은_관리자_id와_함께_서비스에_넘기고_갱신된_행을_돌려준다() {
        when(writes.executeDeferred(42L, 13L, "admin")).thenReturn(view(13L, "EXECUTED"));
        when(writes.dismiss(42L, 14L, "admin")).thenReturn(view(14L, "DISMISSED"));

        DbChangeView executed = controller.execute(42L, 13L, admin);
        DbChangeView dismissed = controller.dismiss(42L, 14L, admin);

        assertThat(executed.classification()).isEqualTo("EXECUTED");
        assertThat(dismissed.classification()).isEqualTo("DISMISSED");
        verify(writes).executeDeferred(42L, 13L, "admin");
        verify(writes).dismiss(42L, 14L, "admin");
        verify(writes, never()).list(anyLong());
    }

    @Test
    void 실행과_실행안함은_ROLE_ADMIN_전용이고_목록은_아니다() throws Exception {
        for (String name : List.of("execute", "dismiss")) {
            Method m = TaskDbChangeController.class.getMethod(name, Long.class, Long.class, JwtAuthenticationToken.class);
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
            assertThat(pre).as(name).isNotNull();
            assertThat(pre.value()).as(name).isEqualTo("hasAuthority('ROLE_ADMIN')");
            assertThat(m.getAnnotation(PostMapping.class).value()).as(name)
                    .containsExactly("/{id}/db-changes/{logId}/" + name);
        }
        Method list = TaskDbChangeController.class.getMethod("list", Long.class, JwtAuthenticationToken.class);
        assertThat(list.isAnnotationPresent(PreAuthorize.class)).isFalse();   // 조회 권한은 getForView가 본다
        assertThat(list.getAnnotation(GetMapping.class).value()).containsExactly("/{id}/db-changes");
        assertThat(TaskDbChangeController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/tasks");
    }

    @Test
    void 서비스의_409는_그대로_나간다() {
        when(writes.executeDeferred(42L, 13L, "admin")).thenThrow(TaskException.conflict("이미 처리된 항목입니다"));

        assertThatThrownBy(() -> controller.execute(42L, 13L, admin))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("이미 처리된 항목입니다");
                });
        verify(writes, never()).dismiss(anyLong(), anyLong(), anyString());
        verify(taskService, never()).getForView(anyLong(), anyString(), anyBoolean());   // 관리자 경로는 서비스가 작업을 직접 본다
    }
}
