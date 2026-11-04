package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.config.CorsConfig;
import com.hamonsoft.netismaker.config.SecurityConfig;
import com.hamonsoft.netismaker.config.WorkerApiKeyFilter;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.service.TaskService;
import com.hamonsoft.netismaker.service.UserProvisioner;
import com.hamonsoft.netismaker.service.applog.AppLogStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 앱 로그 구독 엔드포인트 (스펙 2026-11-03 §4) — 인증·권한·SSE 시작.
 * {@link TaskControllerSseResolverTest}와 같은 모양(@WebMvcTest + 스텁 JwtDecoder)이고 Testcontainers 없이 돈다.
 * 403·404는 JSON 본문 없이 상태만 나간다(EventSource는 본문을 못 읽고, produces=text/event-stream과 Map 본문의 상성이 확인된 바 없다).
 */
@WebMvcTest(controllers = TaskAppLogController.class)
@Import({SecurityConfig.class, CorsConfig.class, WorkerApiKeyFilter.class})
@AutoConfigureMockMvc
class TaskAppLogControllerTest {

    private static final String URL = "/api/tasks/42/app-logs/stream";
    private static final String TOKEN = "test-token-value";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private UserProvisioner userProvisioner;

    @MockBean
    private TaskService taskService;

    @MockBean
    private AppLogStreamService appLogs;

    @BeforeEach
    void stubJwt() {
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .subject("user1")
                .claim("username", "user1")
                .claim("user_id", "user1")
                .claim("authorities", List.of("ROLE_USER"))
                .build();
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt);
    }

    @Test
    void access_token_쿼리로_200_비동기() throws Exception {
        Task stubTask = mock(Task.class);
        when(stubTask.getStatus()).thenReturn(TaskStatus.DEPLOYED);
        when(taskService.getForView(eq(42L), eq("user1"), eq(false))).thenReturn(stubTask);
        when(appLogs.subscribe(stubTask)).thenReturn(new SseEmitter(0L));

        // SSE는 비동기 응답이라 asyncStarted로 구독 시작을 본다 (401·403이면 동기로 끝나 asyncStarted=false).
        mvc.perform(get(URL).queryParam("access_token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        verify(appLogs).subscribe(stubTask);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
        verify(appLogs, never()).subscribe(any(Task.class));
    }

    @Test
    void 남의_작업은_SSE_Accept여도_403_본문없음() throws Exception {
        when(taskService.getForView(eq(42L), eq("user1"), anyBoolean())).thenThrow(TaskException.forbidden());

        mvc.perform(get(URL).queryParam("access_token", TOKEN).header("Accept", "text/event-stream"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
        verify(appLogs, never()).subscribe(any(Task.class));
    }

    @Test
    void 없는_작업은_404() throws Exception {
        when(taskService.getForView(eq(42L), eq("user1"), anyBoolean())).thenThrow(TaskException.notFound());

        mvc.perform(get(URL).queryParam("access_token", TOKEN).header("Accept", "text/event-stream"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verify(appLogs, never()).subscribe(any(Task.class));
    }
}
