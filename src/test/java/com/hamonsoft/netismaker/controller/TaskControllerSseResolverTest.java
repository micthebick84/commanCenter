package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.config.CorsConfig;
import com.hamonsoft.netismaker.config.SecurityConfig;
import com.hamonsoft.netismaker.config.WorkerApiKeyFilter;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.DeployLogStreamService;
import com.hamonsoft.netismaker.service.TaskService;
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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 16: ?access_token= 쿼리 파라미터 인증 검증.
 *
 * EventSource는 Authorization 헤더를 전송할 수 없으므로 SecurityConfig의
 * DefaultBearerTokenResolver.setAllowUriQueryParameter(true) 설정이 SSE 스트림 구독에 필수다.
 * 이 테스트는 헤더 없이 ?access_token= 만으로 인증이 통과되는지 확인한다.
 *
 * @WebMvcTest + @MockBean JwtDecoder 조합으로 Testcontainers 없이 실행 가능.
 */
@WebMvcTest(controllers = TaskController.class)
@Import({SecurityConfig.class, CorsConfig.class, WorkerApiKeyFilter.class})
@AutoConfigureMockMvc
class TaskControllerSseResolverTest {

    @Autowired
    private MockMvc mvc;

    /** Spring Security OAuth2 Resource Server가 사용하는 JwtDecoder를 스텁으로 교체. */
    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private TaskService taskService;

    @MockBean
    private DeployLogStreamService deployLogStreamService;

    /**
     * ?access_token= 쿼리 파라미터에 토큰을 전달하면 Authorization 헤더 없이도
     * 인증이 통과되고 SSE 응답(200 text/event-stream)이 반환되어야 한다.
     *
     * 이 동작은 SecurityConfig.bearerTokenResolver()에서
     * DefaultBearerTokenResolver.setAllowUriQueryParameter(true) 로 활성화된다.
     * 해당 설정이 제거되면 401 Unauthorized가 반환되어 이 테스트가 실패한다.
     */
    @Test
    void access_token_query_param_authenticates_sse_request() throws Exception {
        // 스텁 JWT: owner=user1, ROLE_USER
        Jwt stubJwt = Jwt.withTokenValue("test-token-value")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .subject("user1")
                .claim("username", "user1")
                .claim("user_id", "user1")
                .claim("authorities", List.of("ROLE_USER"))
                .build();
        when(jwtDecoder.decode("test-token-value")).thenReturn(stubJwt);

        // TaskService: 작업 조회 성공 (소유자 = user1)
        Task stubTask = mock(Task.class);
        when(stubTask.isOwnedBy("user1")).thenReturn(true);
        when(stubTask.getStatus()).thenReturn(TaskStatus.DEPLOYING);
        when(taskService.getForView(eq(42L), anyString(), anyBoolean())).thenReturn(stubTask);

        // DeployLogStreamService: 빈 SseEmitter 반환
        when(deployLogStreamService.subscribe(42L)).thenReturn(new SseEmitter(0L));

        // Authorization 헤더 없이 ?access_token= 쿼리 파라미터로만 요청.
        // SSE는 비동기 응답이므로 asyncStarted 여부로 인증 통과를 확인한다.
        // (401이면 동기적으로 즉시 반환되고 asyncStarted=false; 200+async이면 resolver가 동작한 것.)
        mvc.perform(get("/api/tasks/42/logs/stream")
                        .queryParam("access_token", "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    /**
     * ?access_token= 없이 Authorization 헤더도 없으면 401이어야 한다.
     * (기본 보안이 유지됨을 보장.)
     */
    @Test
    void no_token_returns_401() throws Exception {
        mvc.perform(get("/api/tasks/42/logs/stream"))
                .andExpect(status().isUnauthorized());
    }
}
