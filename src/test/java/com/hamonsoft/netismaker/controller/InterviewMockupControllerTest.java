package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.InterviewMockupView;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.repository.InterviewMockupRepository;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.service.GlobalExceptionHandler;
import com.hamonsoft.netismaker.service.InterviewMockupService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.TaskException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인터뷰 목업 조회 API (스펙 2026-11-03 §5) — 목록·단건 모두 세션 조회 권한({@code InterviewController.get}과 같은 순서)을 따르고,
 * 단건은 JSON으로만 응답한다(text/html 금지). 앞쪽 시험은 서비스를 목으로 두고 권한 순서만, 뒤쪽 시험은 진짜
 * {@link InterviewMockupService}를 저장소 목 위에 얹고 standalone MockMvc로 와이어(JSON·상태 코드)를 본다.
 */
class InterviewMockupControllerTest {

    private static final long SID = 5L;
    private static final long MID = 9L;
    private static final String HTML = "<!doctype html><html><body><h1>목록 A안</h1></body></html>";

    private static JwtAuthenticationToken jwt(String username, String authority) {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("username", username).build(),
                List.of(new SimpleGrantedAuthority(authority)));
    }

    private static JwtAuthenticationToken user(String username) {
        return jwt(username, "ROLE_USER");
    }

    private static JwtAuthenticationToken admin() {
        return jwt("admin", "ROLE_ADMIN");
    }

    // ── 권한 (서비스 목) ───────────────────────────────────────────────────────

    private final InterviewService interviewService = mock(InterviewService.class);
    private final InterviewMockupService mockupService = mock(InterviewMockupService.class);
    private final InterviewMockupController controller = new InterviewMockupController(interviewService, mockupService);

    @Test
    void 목록과_단건은_세션_조회_권한을_따른다() {
        // getForView가 403을 던지면 그대로 나가고, 목업 서비스는 부르지 않는다
        when(interviewService.getForView(SID, "u2", false)).thenThrow(TaskException.forbidden());
        assertThatThrownBy(() -> controller.list(SID, user("u2")))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.get(SID, MID, user("u2")))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(mockupService);

        // 질문 세션은 requireKind가 404 — 권한 확인에도 목업 서비스에도 가지 않는다
        InterviewService questionGuard = mock(InterviewService.class);
        when(questionGuard.requireKind(SID, InterviewKind.INTERVIEW)).thenThrow(TaskException.notFound());
        InterviewMockupService untouched = mock(InterviewMockupService.class);
        InterviewMockupController guarded = new InterviewMockupController(questionGuard, untouched);
        assertThatThrownBy(() -> guarded.list(SID, admin()))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> guarded.get(SID, MID, admin()))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(questionGuard, never()).getForView(anyLong(), anyString(), anyBoolean());
        verifyNoInteractions(untouched);
    }

    @Test
    void 권한이_있으면_requireKind_getForView_서비스_순서로_부르고_소유자_관리자_구분을_넘긴다() {
        InterviewMockupSummary summary = new InterviewMockupSummary(MID, 1, "list-a", 1, "목록 A안", null,
                3, "question", OffsetDateTime.now());
        when(mockupService.list(SID)).thenReturn(List.of(summary));
        InterviewMockupView view = new InterviewMockupView(MID, SID, 77L, 1, "list-a", 1, "목록 A안", null, 3,
                "question", OffsetDateTime.now(), HTML);
        when(mockupService.get(SID, MID)).thenReturn(view);

        assertThat(controller.list(SID, user("u1"))).containsExactly(summary);
        assertThat(controller.get(SID, MID, admin())).isSameAs(view);

        InOrder o = inOrder(interviewService, mockupService);
        o.verify(interviewService).requireKind(SID, InterviewKind.INTERVIEW);
        o.verify(interviewService).getForView(SID, "u1", false);
        o.verify(mockupService).list(SID);
        o.verify(interviewService).requireKind(SID, InterviewKind.INTERVIEW);
        o.verify(interviewService).getForView(SID, "admin", true);
        o.verify(mockupService).get(SID, MID);
    }

    // ── 단건 와이어 (진짜 서비스 + 저장소 목) ─────────────────────────────────

    private InterviewSessionRepository sessionRepo;
    private InterviewMockupRepository repo;
    private MockMvc mvc;

    @BeforeEach
    void setUpWire() {
        sessionRepo = mock(InterviewSessionRepository.class);
        repo = mock(InterviewMockupRepository.class);
        InterviewMockupService real = new InterviewMockupService(sessionRepo, repo);
        mvc = MockMvcBuilders
                .standaloneSetup(new InterviewMockupController(mock(InterviewService.class), real)) // 권한 통과(목은 아무것도 안 던진다)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private InterviewSession session(long id, Long taskId) {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setTaskId(taskId);
        when(sessionRepo.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private InterviewMockup mockup(long id, long sessionId) {
        InterviewMockup m = InterviewMockup.create(sessionId, 1, "list-a", 2, "목록 A안", "필터를 위에 둔다", HTML);
        ReflectionTestUtils.setField(m, "id", id);
        ReflectionTestUtils.setField(m, "turnSeq", 3);
        ReflectionTestUtils.setField(m, "turnKind", "question");
        return m;
    }

    @Test
    void 단건은_html과_taskId_createdAt() throws Exception {
        session(SID, 77L);
        InterviewMockup m = mockup(MID, SID);
        when(repo.findByIdAndSessionIdAndDiscardedFalse(MID, SID)).thenReturn(Optional.of(m));

        mvc.perform(get("/api/interviews/" + SID + "/mockups/" + MID).principal(user("u1")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(MID))
                .andExpect(jsonPath("$.sessionId").value(SID))
                .andExpect(jsonPath("$.taskId").value(77))     // 새 탭 "인터뷰로" 링크·부제에 쓴다 — 세션에서 읽는다
                .andExpect(jsonPath("$.n").value(1))
                .andExpect(jsonPath("$.name").value("list-a"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.title").value("목록 A안"))
                .andExpect(jsonPath("$.description").value("필터를 위에 둔다"))
                .andExpect(jsonPath("$.turnSeq").value(3))
                .andExpect(jsonPath("$.turnKind").value("question"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.html").value(HTML));
    }

    @Test
    void 단건은_text_html_요청에도_JSON만_낸다() throws Exception {
        // produces = application/json — HTML 본문을 text/html로 내보내면 앱 출처로 실행된다. 406으로 막는다
        session(SID, 77L);
        when(repo.findByIdAndSessionIdAndDiscardedFalse(MID, SID)).thenReturn(Optional.of(mockup(MID, SID)));

        mvc.perform(get("/api/interviews/" + SID + "/mockups/" + MID).accept(MediaType.TEXT_HTML)
                        .principal(user("u1")))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void 작업이_없는_세션의_단건은_taskId가_null() throws Exception {
        session(SID, null);
        when(repo.findByIdAndSessionIdAndDiscardedFalse(MID, SID)).thenReturn(Optional.of(mockup(MID, SID)));

        mvc.perform(get("/api/interviews/" + SID + "/mockups/" + MID).principal(user("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").doesNotExist())
                .andExpect(jsonPath("$.html").value(HTML));
    }

    @Test
    void 다른세션_id와_버린_목업은_404() throws Exception {
        session(SID, 77L);
        session(6L, 88L);

        // 다른 세션의 id — 목업 9는 세션 5의 것인데 세션 6 경로로 부른다
        when(repo.findByIdAndSessionIdAndDiscardedFalse(MID, SID)).thenReturn(Optional.of(mockup(MID, SID)));
        mvc.perform(get("/api/interviews/6/mockups/" + MID).principal(user("u1")))
                .andExpect(status().isNotFound());

        // 버린 목업 — 행은 있지만(findById가 돌려줘도) 버리지 않은 행만 보는 조회라 비어 있다. 존재를 숨긴다
        InterviewMockup discarded = mockup(10L, SID);
        ReflectionTestUtils.setField(discarded, "discarded", true);
        when(repo.findById(10L)).thenReturn(Optional.of(discarded));
        when(repo.findByIdAndSessionIdAndDiscardedFalse(10L, SID)).thenReturn(Optional.empty());
        mvc.perform(get("/api/interviews/" + SID + "/mockups/10").principal(user("u1")))
                .andExpect(status().isNotFound());

        // 아예 없는 id
        mvc.perform(get("/api/interviews/" + SID + "/mockups/999").principal(user("u1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 서비스_get은_세션_작업id를_싣고_본문_html은_toString에_안_나온다() {
        session(SID, 77L);
        when(repo.findByIdAndSessionIdAndDiscardedFalse(MID, SID)).thenReturn(Optional.of(mockup(MID, SID)));

        InterviewMockupView v = new InterviewMockupService(sessionRepo, repo).get(SID, MID);

        assertThat(v.taskId()).isEqualTo(77L);
        assertThat(v.sessionId()).isEqualTo(SID);
        assertThat(v.html()).isEqualTo(HTML);
        assertThat(v.createdAt()).isNotNull();
        assertThat(v.toString()).doesNotContain("목록 A안</h1>").doesNotContain("<html>");
        verify(repo, never()).findById(any());   // 목업은 세션·버림 조건이 든 조회로만 읽는다
    }
}
