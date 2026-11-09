package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.FixRequestApproveRequest;
import com.hamonsoft.netismaker.dto.FixRequestCreateRequest;
import com.hamonsoft.netismaker.dto.FixRequestRejectRequest;
import com.hamonsoft.netismaker.dto.FixRequestView;
import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.DeployLogStreamService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.service.TaskFixService;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수정 요청 엔드포인트 (스펙 2026-11-03 §6) — 컨트롤러를 직접 만들어 인자 전달·권한 어노테이션·응답 가림·상세 응답의 fix를
 * 로컬에서 확인한다(권한 403·실제 DB 경로는 TaskFixFlowIntegrationTest, CI).
 */
class TaskFixControllerTest {

    private static final TaskResponse.FixView FIX = new TaskResponse.FixView(1, "REVIEW", 1, 3);

    private TaskFixService fixService;
    private TaskService taskService;
    private TaskFixController controller;
    private Task task;
    private final JwtAuthenticationToken user = auth("user1", "ROLE_USER");
    private final JwtAuthenticationToken admin = auth("admin", "ROLE_ADMIN");

    private static JwtAuthenticationToken auth(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("username", userId).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }

    @BeforeEach
    void setUp() {
        fixService = mock(TaskFixService.class);
        taskService = mock(TaskService.class);
        controller = new TaskFixController(fixService, taskService);
        task = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.FIX_REVIEW);
        task.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_PASSWORD", "pw", true))));
    }

    @Test
    void 요청은_본문과_방식을_서비스에_넘기고_비관리자_응답은_비밀을_가린다() {
        when(fixService.request(5L, "user1", false, "고쳐 주세요", "DIRECT")).thenReturn(task);

        TaskResponse r = controller.request(5L, new FixRequestCreateRequest("고쳐 주세요", "DIRECT"), user);

        assertThat(r.status()).isEqualTo(TaskStatus.FIX_REVIEW);
        assertThat(r.envVars()).extracting(EnvVar::value).containsExactly("");
        assertThat(r.fix()).isNull();   // 회차 현황은 상세 응답에서만
    }

    @Test
    void 관리자_요청은_관리자로_넘기고_비밀을_그대로_둔다() {
        when(fixService.request(5L, "admin", true, "고쳐 주세요", null)).thenReturn(task);
        TaskResponse r = controller.request(5L, new FixRequestCreateRequest("고쳐 주세요", null), admin);
        assertThat(r.envVars()).extracting(EnvVar::value).containsExactly("pw");
    }

    @Test
    void 승인은_바디가_없어도_된다() throws Exception {
        when(fixService.approve(5L, "admin", null)).thenReturn(task);
        controller.approve(5L, null, admin);
        verify(fixService).approve(5L, "admin", null);

        when(fixService.approve(5L, "admin", "DIRECT")).thenReturn(task);
        controller.approve(5L, new FixRequestApproveRequest("DIRECT"), admin);
        verify(fixService).approve(5L, "admin", "DIRECT");

        Method m = TaskFixController.class.getMethod("approve", Long.class, FixRequestApproveRequest.class,
                JwtAuthenticationToken.class);
        assertThat(m.getParameters()[1].getAnnotation(RequestBody.class).required()).isFalse();
        assertThat(m.getParameters()[1].isAnnotationPresent(jakarta.validation.Valid.class)).isTrue();
    }

    @Test
    void 반려는_사유를_넘긴다() {
        when(fixService.reject(5L, "admin", "재현 안 됨")).thenReturn(task);
        controller.reject(5L, new FixRequestRejectRequest("재현 안 됨"), admin);
        verify(fixService).reject(5L, "admin", "재현 안 됨");
    }

    @Test
    void 철회는_호출자와_관리자_여부를_넘긴다() {
        when(fixService.withdraw(5L, "user1", false)).thenReturn(task);
        controller.withdraw(5L, user);
        verify(fixService).withdraw(5L, "user1", false);
    }

    @Test
    void 수정_중단은_관리자를_넘기고_관리자_응답이다() {
        task.setStatus(TaskStatus.PR_CREATED);
        when(fixService.abort(5L, "admin")).thenReturn(task);

        TaskResponse r = controller.abort(5L, admin);

        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.envVars()).extracting(EnvVar::value).containsExactly("pw");
        verify(fixService).abort(5L, "admin");
    }

    @Test
    void 승인과_반려는_관리자_전용이고_요청_철회_조회는_아니다() {
        for (String name : new String[]{"approve", "reject", "abort"}) {
            Method m = method(name);
            assertThat(m.getAnnotation(PreAuthorize.class)).as(name).isNotNull();
            assertThat(m.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
        }
        for (String name : new String[]{"request", "withdraw", "list"}) {
            assertThat(method(name).getAnnotation(PreAuthorize.class)).as(name).isNull();
        }
    }

    private static Method method(String name) {
        return Arrays.stream(TaskFixController.class.getDeclaredMethods())
                .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void 기록_조회는_작업_조회_권한을_먼저_확인한다() {
        TaskFixRequest req = TaskFixRequest.pending(5L, "본문", TaskStatus.DEPLOYED, "user1");
        when(fixService.list(5L)).thenReturn(List.of(req));

        List<FixRequestView> out = controller.list(5L, user);

        assertThat(out).extracting(FixRequestView::body).containsExactly("본문");
        verify(taskService).getForView(5L, "user1", false);
    }

    @Test
    void 남의_작업_기록은_조회할_수_없다() {
        when(taskService.getForView(eq(5L), eq("someone"), anyBoolean())).thenThrow(TaskException.forbidden());
        assertThatThrownBy(() -> controller.list(5L, auth("someone", "ROLE_USER")))
                .isInstanceOf(TaskException.class);
        verify(fixService, never()).list(any());
    }

    // ── 작업 상세·목록의 fix ────────────────────────────

    @Test
    void 작업_상세는_회차_현황을_싣고_목록은_싣지_않는다() {
        TaskService ts = mock(TaskService.class);
        TaskFixService fs = mock(TaskFixService.class);
        TaskController tc = new TaskController(ts, mock(DeployLogStreamService.class), mock(InterviewService.class), fs);
        task.setStatus(TaskStatus.APPROVED);
        when(ts.getForView(eq(5L), anyString(), anyBoolean())).thenReturn(task);
        when(ts.list(anyString(), anyBoolean(), anyBoolean(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(task)));
        when(fs.fixViewOf(task)).thenReturn(FIX);

        TaskResponse detail = tc.get(5L, user);
        TaskResponse listed = tc.list(null, null, false, PageRequest.of(0, 20), user).getContent().get(0);

        assertThat(detail.fix()).isEqualTo(FIX);
        assertThat(detail.envVars()).extracting(EnvVar::value).containsExactly("");   // 비관리자 가림은 그대로
        assertThat(listed.fix()).isNull();
        verify(fs).fixViewOf(task);
    }
}
