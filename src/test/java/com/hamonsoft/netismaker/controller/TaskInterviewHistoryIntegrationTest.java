package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 과거 인터뷰 이력 목록 API — GET /api/tasks/{id}/interviews.
 *
 * 재승인으로 세션이 여러 개 쌓이는 task에서 전체 세션을 최신순으로 발견(discovery)할 수 있어야 한다.
 * 개별 transcript는 기존 GET /api/interviews/{id}가 터미널 세션도 그대로 반환한다(회귀 고정 포함).
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskInterviewHistoryIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach
    void clean() {
        // 공유 컨테이너 — 자식(task_design/interview_session) 먼저 삭제 (FK 순서)
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // catalog id=1 corresponds to the V14 seed entry (alias 'Netis7.0').
    private Task createTask(String requester, String title) {
        return taskService.create(new TaskCreateRequest(1L, "main", title, "설명"), requester);
    }

    /** 승인 → 그 승인으로 태어난 세션 id (task의 최신 세션). */
    private long approveAndGetSessionId(Long taskId) {
        taskService.approve(taskId, "admin", null);
        return sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(taskId).orElseThrow().getId();
    }

    @Test
    void 취소_재승인으로_쌓인_세션이_최신순으로_전부_조회된다() throws Exception {
        Task t = createTask("user1", "이력 조회");
        long sid1 = approveAndGetSessionId(t.getId());

        // 세션1 취소 → task는 승인대기로 복귀 → 재승인으로 세션2 생성
        mvc.perform(post("/api/interviews/" + sid1 + "/cancel").with(adminJwt("admin")))
                .andExpect(status().isOk());
        long sid2 = approveAndGetSessionId(t.getId());

        mvc.perform(get("/api/tasks/" + t.getId() + "/interviews").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // createdAt DESC: 최신(세션2, QUEUED)이 먼저
                .andExpect(jsonPath("$[0].id").value(sid2))
                .andExpect(jsonPath("$[0].statusName").value("QUEUED"))
                .andExpect(jsonPath("$[0].status").value("인터뷰대기"))
                .andExpect(jsonPath("$[1].id").value(sid1))
                .andExpect(jsonPath("$[1].statusName").value("CANCELLED"))
                .andExpect(jsonPath("$[1].status").value("취소됨"))
                // 목록은 경량 — turns/plan을 싣지 않는다
                .andExpect(jsonPath("$[0].turns").doesNotExist())
                .andExpect(jsonPath("$[0].plan").doesNotExist());
    }

    @Test
    void 비소유자는_403_소유자와_관리자는_200() throws Exception {
        Task t = createTask("user1", "ACL 확인");
        approveAndGetSessionId(t.getId());

        mvc.perform(get("/api/tasks/" + t.getId() + "/interviews").with(userJwt("user2")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tasks/" + t.getId() + "/interviews").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/tasks/" + t.getId() + "/interviews").with(adminJwt("admin1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void 소프트삭제된_task는_404() throws Exception {
        Task t = createTask("user1", "삭제 후 조회");
        approveAndGetSessionId(t.getId());
        taskService.softDelete(t.getId(), "user1", false);

        mvc.perform(get("/api/tasks/" + t.getId() + "/interviews").with(userJwt("user1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 취소된_세션의_transcript는_여전히_조회된다() throws Exception {
        // 회귀 고정: GET /api/interviews/{id}는 터미널 세션도 turns 포함 200을 반환한다.
        Task t = createTask("user1", "터미널 열람");
        long sid = approveAndGetSessionId(t.getId());
        mvc.perform(post("/api/interviews/" + sid + "/cancel").with(adminJwt("admin")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/interviews/" + sid).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("CANCELLED"))
                .andExpect(jsonPath("$.status").value("취소됨"))
                // cancel이 system/note 턴('사용자 취소')을 남긴다 — turns가 함께 온다
                .andExpect(jsonPath("$.turns", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.turns[0].role").value("system"));
    }
}
