package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.dto.QuestionAskRequest;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionServiceTest {

    private static final RepoCatalogService.ResolvedRepo REPO = new RepoCatalogService.ResolvedRepo(
            1L, "Netis7.0", "https://github.com/micthebick84/netis7.0.git", "github",
            "micthebick84/netis7.0", "main");

    private InterviewService interviewService;
    private InterviewSessionRepository sessionRepo;
    private InterviewTurnRepository turnRepo;
    private RepoCatalogService repoCatalog;
    private McpCatalogService mcpCatalog;
    private AttachmentStorage attachmentStorage;
    private TikaExtractionService tika;
    private QuestionAttachmentRepository attachmentRepo;
    private DbConnectionService dbConnections;
    private QuestionService service;

    @BeforeEach
    void setUp() {
        interviewService = mock(InterviewService.class);
        sessionRepo = mock(InterviewSessionRepository.class);
        turnRepo = mock(InterviewTurnRepository.class);
        repoCatalog = mock(RepoCatalogService.class);
        mcpCatalog = mock(McpCatalogService.class);
        attachmentStorage = mock(AttachmentStorage.class);
        tika = mock(TikaExtractionService.class);
        attachmentRepo = mock(QuestionAttachmentRepository.class);
        dbConnections = mock(DbConnectionService.class);
        when(dbConnections.validateSelection(any(), any(), any())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(2);
            return ids == null ? new ArrayList<Long>() : new ArrayList<>(ids);
        });
        service = new QuestionService(interviewService, sessionRepo, turnRepo, repoCatalog, mcpCatalog,
                attachmentStorage, tika, attachmentRepo, dbConnections, 3, 10);
        when(sessionRepo.save(any())).thenAnswer(i -> {
            InterviewSession s = i.getArgument(0);
            if (s.getId() == null) ReflectionTestUtils.setField(s, "id", 5L);   // IDENTITY 흉내 — 첨부 경로에 id 필요
            return s;
        });
        when(attachmentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repoCatalog.resolveForRegistration(1L)).thenReturn(REPO);
        when(tika.extractText(any(), any())).thenReturn(Optional.empty());
        when(attachmentStorage.relativePathForQuestion(anyLong(), any(), anyInt(), any())).thenAnswer(i ->
                "question-" + i.getArgument(0) + "/" + (i.getArgument(1) == null ? "create" : i.getArgument(1))
                        + "/" + i.getArgument(2) + "-" + i.getArgument(3));
        when(attachmentStorage.extractedTextRelativePath(any())).thenAnswer(i -> i.getArgument(0) + ".txt");
        when(attachmentStorage.resolve(any())).thenAnswer(i -> Path.of("/abs", (String) i.getArgument(0)));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain", content.getBytes());
    }

    private static InterviewService.AnswerOutcome answered(InterviewSession s, Integer turnSeq) {
        return new InterviewService.AnswerOutcome(s, turnSeq);
    }

    private static QuestionCreateRequest req(String model, String effort, List<Long> mcpIds) {
        return new QuestionCreateRequest(1L, "dev", "인증 흐름", "로그인은 어디서 처리되나요?", model, effort, mcpIds);
    }

    private static QuestionCreateRequest reqWithTitle(String title, String question) {
        return new QuestionCreateRequest(1L, "dev", title, question, null, null, null);
    }

    @Test
    void create_builds_question_session_from_catalog_repo_and_selected_model() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(req("claude-sonnet-5", "medium", null), "user1");
        assertThat(s.getKind()).isEqualTo(InterviewKind.QUESTION);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getGithubRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(s.getGithubBranch()).isEqualTo("dev");
        assertThat(s.getTitle()).isEqualTo("인증 흐름");
        assertThat(s.getDescription()).isEqualTo("로그인은 어디서 처리되나요?");
        assertThat(s.getRequesterId()).isEqualTo("user1");
        assertThat(s.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(s.getEffort()).isEqualTo("medium");
        assertThat(s.getGitUrl()).isEqualTo(REPO.gitUrl());
        assertThat(s.getRepoAlias()).isEqualTo("Netis7.0");
        assertThat(s.getRepoCatalogId()).isEqualTo(1L);
        assertThat(s.getTaskId()).isNull();
        assertThat(s.getMcpsExtra()).isEmpty();
        verify(sessionRepo).save(s);
    }

    @Test
    void create_blank_model_effort_fall_back_to_policy_defaults() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(req("", null, List.of()), "user1");
        assertThat(s.getModel()).isEqualTo(ModelEffortPolicy.DEFAULT_MODEL);
        assertThat(s.getEffort()).isEqualTo(ModelEffortPolicy.DEFAULT_EFFORT);
    }

    @Test
    void create_snapshots_selected_mcp_catalog_entries() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        when(mcpCatalog.resolveExtras(List.of(9L))).thenReturn(List.of(new TaskMcpSpec("ctx7", "https://ctx7", "http")));
        InterviewSession s = service.create(req(null, null, List.of(9L)), "user1");
        assertThat(s.getMcpsExtra()).hasSize(1);
        assertThat(s.getMcpsExtra().get(0).name()).isEqualTo("ctx7");
    }

    @Test
    void create_over_active_limit_throws_429_without_saving() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(3L);
        assertThatThrownBy(() -> service.create(req(null, null, null), "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void create_invalid_effort_for_model_throws_400_before_saving() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        assertThatThrownBy(() -> service.create(req("claude-haiku-4-5", "max", null), "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void create_derives_title_from_first_line_when_blank() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(
                reqWithTitle(null, "  로그인은   어디서 처리되나요?\n두 번째 줄은 제목에 안 들어간다"), "user1");
        assertThat(s.getTitle()).isEqualTo("로그인은 어디서 처리되나요?");
        // 본문(킥오프 프롬프트에 그대로 삽입)은 원문 유지
        assertThat(s.getDescription()).isEqualTo("  로그인은   어디서 처리되나요?\n두 번째 줄은 제목에 안 들어간다");
    }

    @Test
    void create_truncates_derived_title_to_60_chars_with_ellipsis() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(reqWithTitle("   ", "가".repeat(70)), "user1");
        assertThat(s.getTitle()).hasSize(61).startsWith("가".repeat(60)).endsWith("…");
    }

    @Test
    void deriveTitle_cuts_on_code_point_boundary_so_no_lone_surrogate_is_left() {
        String title = QuestionService.deriveTitle(null, "가".repeat(59) + "😀😀 더");
        assertThat(title).isEqualTo("가".repeat(59) + "😀…");
        assertThat(title.chars().filter(c -> Character.isSurrogate((char) c)).count()).isEqualTo(2);   // 이모지 한 개 = 서로게이트 쌍
    }

    @Test
    void deriveTitle_drops_markdown_markers_from_the_first_line() {
        assertThat(QuestionService.deriveTitle(null, "## 로그인 흐름 질문")).isEqualTo("로그인 흐름 질문");
        assertThat(QuestionService.deriveTitle(null, "> > 인용한 에러 원인?")).isEqualTo("인용한 에러 원인?");
        assertThat(QuestionService.deriveTitle(null, "- `AuthFilter`는 어디서 등록돼?")).isEqualTo("AuthFilter는 어디서 등록돼?");
        assertThat(QuestionService.deriveTitle(null, "1. 첫 번째 질문")).isEqualTo("첫 번째 질문");
        // 코드 펜스·기호만 있는 줄은 건너뛰고 다음 줄을 쓴다
        assertThat(QuestionService.deriveTitle(null, "```java\nfoo();\n```")).isEqualTo("foo();");
        assertThat(QuestionService.deriveTitle(null, "#\n---\n실제 질문")).isEqualTo("실제 질문");
        // 문장 중간의 기호는 그대로
        assertThat(QuestionService.deriveTitle(null, "C# 코드 - 차이?")).isEqualTo("C# 코드 - 차이?");
    }

    @Test
    void create_keeps_explicit_title_trimmed() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(reqWithTitle("  인증 흐름  ", "질문 본문"), "user1");
        assertThat(s.getTitle()).isEqualTo("인증 흐름");
    }

    @Test
    void ask_at_turn_cap_throws_400_and_does_not_delegate() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(10L);
        assertThatThrownBy(() -> service.ask(5L, "user1", false, new QuestionAskRequest("더 자세히?", 9, null, null)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        verify(interviewService, never()).submitAnswer(anyLong(), anyString(), anyBoolean(), any());
    }

    @Test
    void ask_below_cap_delegates_to_submitAnswer_with_real_isAdmin() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(9L);
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any()))
                .thenReturn(answered(questionSession("claude-opus-5", "high"), 10));
        service.ask(5L, "user1", false, new QuestionAskRequest("더 자세히?", 9, null, null));
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        // 상태 전이에는 답변 부분만 AnswerRequest로 넘긴다 (record 동등성)
        verify(interviewService).submitAnswer(5L, "user1", false, new AnswerRequest("더 자세히?", 9));
    }

    private static InterviewSession questionSession(String model, String effort) {
        return InterviewSession.createQuestion("micthebick84/netis7.0", "main", "인증 흐름",
                "로그인은 어디서 처리되나요?", "user1", null, model, effort);
    }

    @Test
    void ask_with_model_and_effort_applies_them_to_the_session_after_requeue() {
        // 대화 중 모델·effort 변경(스펙 2026-09-05 §2 개정): 세션 값이 바뀌어야 다음 claim이 새 값을 싣는다.
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-opus-5", "high");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        InterviewSession out = service.ask(5L, "user1", false,
                new QuestionAskRequest("이번 건 싸게 답해줘", 3, "claude-haiku-4-5", "low"));

        assertThat(out).isSameAs(s);
        assertThat(s.getModel()).isEqualTo("claude-haiku-4-5");
        assertThat(s.getEffort()).isEqualTo("low");
        verify(interviewService).submitAnswer(5L, "user1", false, new AnswerRequest("이번 건 싸게 답해줘", 3));
    }

    @Test
    void ask_with_only_effort_keeps_the_current_model_instead_of_policy_default() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-sonnet-5", "medium");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        service.ask(5L, "user1", false, new QuestionAskRequest("더?", 3, null, "xhigh"));

        assertThat(s.getModel()).isEqualTo("claude-sonnet-5");   // resolveModel(null)=opus로 리셋되면 안 된다
        assertThat(s.getEffort()).isEqualTo("xhigh");
    }

    @Test
    void ask_with_only_model_validates_the_combination_with_the_current_effort() {
        // 세션 effort가 max인데 Haiku로만 바꾸면 Haiku는 max를 지원하지 않으므로 400 — 조합은 항상 함께 검증한다.
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-opus-5", "max");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("더?", 3, "claude-haiku-4-5", "")))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(s.getModel()).isEqualTo("claude-opus-5");
        assertThat(s.getEffort()).isEqualTo("max");
    }

    @Test
    void ask_with_unsupported_model_throws_400_and_leaves_session_untouched() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-sonnet-5", "medium");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("더?", 3, "claude-fable-5", "high")))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(s.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(s.getEffort()).isEqualTo("medium");
    }

    @Test
    void ask_with_unchanged_model_effort_is_a_noop_even_for_a_legacy_model_no_longer_selectable() {
        // 프론트는 픽커의 현재 값을 항상 동봉한다 — 과거 세션에 박제된 fable-5처럼 목록에서 빠진 모델도
        // "변경 없음"이면 검증 없이 그대로 이어간다(ModelEffortPolicy 주석: 박제 값은 검증을 타지 않는다).
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-fable-5", "high");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        service.ask(5L, "user1", false, new QuestionAskRequest("더?", 3, "claude-fable-5", "high"));

        assertThat(s.getModel()).isEqualTo("claude-fable-5");
        assertThat(s.getEffort()).isEqualTo("high");
    }

    @Test
    void ask_without_model_effort_leaves_the_session_values_alone() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-sonnet-5", "medium");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        service.ask(5L, "user1", false, new QuestionAskRequest("더?", 3, "", null));

        assertThat(s.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(s.getEffort()).isEqualTo("medium");
    }

    // ── 대화 중 MCP 변경 (스펙 2026-09-13 §5.2 applyMcpChange) ──────────────────

    private static InterviewSession questionSessionWithMcp(List<Long> ids, List<TaskMcpSpec> extras) {
        InterviewSession s = questionSession("claude-opus-5", "high");
        if (s.getId() == null) ReflectionTestUtils.setField(s, "id", 5L);   // applyMcpChange는 s.getId()로 노트를 단다
        s.setMcpCatalogIds(new ArrayList<>(ids));
        s.setMcpsExtra(new ArrayList<>(extras));
        return s;
    }

    @Test
    void create_seeds_mcp_catalog_ids_alongside_the_extras_snapshot() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        when(mcpCatalog.resolveExtras(List.of(9L, 4L))).thenReturn(List.of(
                new TaskMcpSpec("ctx7", "https://ctx7", "http"), new TaskMcpSpec("obs", "https://obs", "sse")));
        InterviewSession s = service.create(req(null, null, List.of(9L, 4L)), "user1");
        assertThat(s.getMcpCatalogIds()).containsExactly(9L, 4L);
        assertThat(s.getMcpsExtra()).hasSize(2);
    }

    @Test
    void create_without_mcp_ids_leaves_an_empty_non_null_list() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(req(null, null, null), "user1");
        assertThat(s.getMcpCatalogIds()).isNotNull().isEmpty();
    }

    @Test
    void ask_with_same_mcp_set_in_different_order_is_a_noop_without_resolving() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSessionWithMcp(List.of(1L, 2L), List.of(
                new TaskMcpSpec("a", "https://a", "sse"), new TaskMcpSpec("b", "https://b", "sse")));
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        QuestionService.AskResult out = service.ask(5L, "user1", false,
                new QuestionAskRequest("더?", 3, null, null, List.of(2L, 1L)), null);

        assertThat(out.mcpNote()).isNull();
        assertThat(s.getMcpCatalogIds()).containsExactly(1L, 2L);
        assertThat(s.getMcpsExtra()).hasSize(2);
        verify(mcpCatalog, never()).resolveExtras(any());
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_with_a_different_mcp_set_validates_updates_snapshot_and_appends_a_note() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSessionWithMcp(List.of(1L), List.of(new TaskMcpSpec("a", "https://a", "sse")));
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));
        when(mcpCatalog.resolveExtras(List.of(2L, 3L))).thenReturn(List.of(
                new TaskMcpSpec("ctx7", "https://c", "http"), new TaskMcpSpec("obsidian", "https://o", "sse")));
        InterviewTurn note = InterviewTurn.of(5L, 5, "system", "note", "MCP 도구 변경: ctx7, obsidian", null);
        when(interviewService.appendSystemNote(5L, "MCP 도구 변경: ctx7, obsidian")).thenReturn(note);

        QuestionService.AskResult out = service.ask(5L, "user1", false,
                new QuestionAskRequest("더?", 3, null, null, List.of(2L, 3L)), null);

        assertThat(out.session()).isSameAs(s);
        assertThat(out.mcpNote()).isSameAs(note);
        assertThat(s.getMcpCatalogIds()).containsExactly(2L, 3L);
        assertThat(s.getMcpsExtra()).extracting(TaskMcpSpec::name).containsExactly("ctx7", "obsidian");
        verify(interviewService).appendSystemNote(5L, "MCP 도구 변경: ctx7, obsidian");
    }

    @Test
    void ask_with_an_invalid_mcp_id_throws_400_and_leaves_the_session_untouched() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSessionWithMcp(List.of(1L), List.of(new TaskMcpSpec("a", "https://a", "sse")));
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));
        when(mcpCatalog.resolveExtras(List.of(99L)))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "존재하지 않는 MCP 카탈로그 id 포함"));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("더?", 3, null, null, List.of(99L)), null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(s.getMcpCatalogIds()).containsExactly(1L);
        assertThat(s.getMcpsExtra()).extracting(TaskMcpSpec::name).containsExactly("a");
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_with_null_mcp_ids_keeps_the_current_selection() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSessionWithMcp(List.of(1L), List.of(new TaskMcpSpec("a", "https://a", "sse")));
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        QuestionService.AskResult out = service.ask(5L, "user1", false,
                new QuestionAskRequest("더?", 3, null, null, null), null);

        assertThat(out.mcpNote()).isNull();
        assertThat(s.getMcpCatalogIds()).containsExactly(1L);
        verify(mcpCatalog, never()).resolveExtras(any());
    }

    @Test
    void ask_with_empty_mcp_list_clears_everything_and_notes_it() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSessionWithMcp(List.of(1L), List.of(new TaskMcpSpec("a", "https://a", "sse")));
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));
        when(mcpCatalog.resolveExtras(List.of())).thenReturn(new ArrayList<>());
        InterviewTurn note = InterviewTurn.of(5L, 5, "system", "note", "MCP 도구 변경: 없음(전부 해제)", null);
        when(interviewService.appendSystemNote(5L, "MCP 도구 변경: 없음(전부 해제)")).thenReturn(note);

        QuestionService.AskResult out = service.ask(5L, "user1", false,
                new QuestionAskRequest("더?", 3, null, null, List.of()), null);

        assertThat(out.mcpNote()).isSameAs(note);
        assertThat(s.getMcpCatalogIds()).isEmpty();
        assertThat(s.getMcpsExtra()).isEmpty();
    }

    // ── 첨부 (스펙 2026-09-13 §5.2 writeAttachments) ──────────────────────────

    @Test
    void create_with_files_writes_them_under_the_create_subdir_with_null_turn_seq() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        MockMultipartFile f1 = file("요구사항.txt", "A");
        MockMultipartFile f2 = file("데이터.csv", "a,b");

        InterviewSession s = service.create(req(null, null, null), List.of(f1, f2), "user1");

        verify(attachmentStorage).validate(List.of(f1, f2));
        verify(attachmentStorage).write("question-5/create/1-요구사항.txt", f1);
        verify(attachmentStorage).write("question-5/create/2-데이터.csv", f2);
        ArgumentCaptor<QuestionAttachment> cap = ArgumentCaptor.forClass(QuestionAttachment.class);
        verify(attachmentRepo, times(2)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(a -> {
            assertThat(a.getSessionId()).isEqualTo(s.getId());
            assertThat(a.getTurnSeq()).isNull();
            assertThat(a.getUploadedBy()).isEqualTo("user1");
            assertThat(a.getExtractedTextPath()).isNull();
        });
        assertThat(cap.getAllValues().get(0).getStoredPath()).isEqualTo("question-5/create/1-요구사항.txt");
        assertThat(cap.getAllValues().get(1).getOriginalFilename()).isEqualTo("데이터.csv");
    }

    @Test
    void create_with_an_office_file_writes_the_tika_sidecar_and_links_it() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        MockMultipartFile docx = new MockMultipartFile("files", "설계.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x".getBytes());
        when(tika.extractText(Path.of("/abs/question-5/create/1-설계.docx"), "설계.docx"))
                .thenReturn(Optional.of("추출된 본문"));

        service.create(req(null, null, null), List.of(docx), "user1");

        verify(attachmentStorage).writeText("question-5/create/1-설계.docx.txt", "추출된 본문");
        ArgumentCaptor<QuestionAttachment> cap = ArgumentCaptor.forClass(QuestionAttachment.class);
        verify(attachmentRepo).save(cap.capture());
        assertThat(cap.getValue().getExtractedTextPath()).isEqualTo("question-5/create/1-설계.docx.txt");
    }

    @Test
    void ask_with_files_links_them_to_the_new_answer_turn_seq() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-opus-5", "high");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));
        MockMultipartFile f1 = file("로그.txt", "err");

        service.ask(5L, "user1", false, new QuestionAskRequest("이 로그 봐줘", 3, null, null), List.of(f1));

        verify(attachmentStorage).validate(List.of(f1));
        verify(attachmentStorage).write("question-5/4/1-로그.txt", f1);
        ArgumentCaptor<QuestionAttachment> cap = ArgumentCaptor.forClass(QuestionAttachment.class);
        verify(attachmentRepo).save(cap.capture());
        assertThat(cap.getValue().getTurnSeq()).isEqualTo(4);
        assertThat(cap.getValue().getSessionId()).isEqualTo(5L);
    }

    @Test
    void ask_duplicate_submission_drops_the_files_silently() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-opus-5", "high");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, null));

        service.ask(5L, "user1", false, new QuestionAskRequest("중복", 3, null, null), List.of(file("a.txt", "A")));

        verify(attachmentStorage, never()).write(any(), any());
        verify(attachmentRepo, never()).save(any());
    }

    @Test
    void ask_with_invalid_files_fails_before_submitAnswer_and_writes_nothing() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        MockMultipartFile bad = file("evil.exe", "x");
        doThrow(new TaskException(HttpStatus.BAD_REQUEST, "허용되지 않는 파일 형식입니다: .exe"))
                .when(attachmentStorage).validate(List.of(bad));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("x", 3, null, null), List.of(bad)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(interviewService, never()).submitAnswer(anyLong(), anyString(), anyBoolean(), any());
        verify(attachmentStorage, never()).write(any(), any());
    }

    @Test
    void ask_cleans_up_already_written_files_including_sidecars_when_a_later_write_fails() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-opus-5", "high");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));
        MockMultipartFile docx = new MockMultipartFile("files", "a.docx", "application/octet-stream", "x".getBytes());
        MockMultipartFile f2 = file("b.txt", "B");
        when(tika.extractText(Path.of("/abs/question-5/4/1-a.docx"), "a.docx")).thenReturn(Optional.of("본문"));
        doThrow(new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "디스크 오류(테스트)"))
                .when(attachmentStorage).write(eq("question-5/4/2-b.txt"), eq(f2));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("x", 3, null, null), List.of(docx, f2)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("디스크 오류");

        verify(attachmentStorage).deleteQuietly("question-5/4/1-a.docx");
        verify(attachmentStorage).deleteQuietly("question-5/4/1-a.docx.txt");
        verify(attachmentStorage).deleteQuietly("question-5/4/2-b.txt");
        verify(attachmentStorage, times(3)).deleteQuietly(any());
    }

    @Test
    void ask_order_is_model_then_mcp_then_files_so_a_400_leaves_no_orphan_files() {
        // 모델 검증 400이 파일 쓰기보다 먼저 — 고아 파일 없음 (스펙 §3).
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        InterviewSession s = questionSession("claude-opus-5", "max");
        when(interviewService.submitAnswer(eq(5L), eq("user1"), eq(false), any())).thenReturn(answered(s, 4));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("x", 3, "claude-haiku-4-5", null), List.of(file("a.txt", "A"))))
                .isInstanceOf(TaskException.class);
        verify(attachmentStorage, never()).write(any(), any());
    }

    @Test
    void getAttachment_hides_rows_of_other_sessions_as_404() {
        QuestionAttachment mine = QuestionAttachment.create(5L, null, "a.txt", "question-5/create/1-a.txt",
                "text/plain", 1L, null, "user1");
        QuestionAttachment other = QuestionAttachment.create(6L, null, "b.txt", "question-6/create/1-b.txt",
                "text/plain", 1L, null, "user2");
        when(attachmentRepo.findById(1L)).thenReturn(Optional.of(mine));
        when(attachmentRepo.findById(2L)).thenReturn(Optional.of(other));
        when(attachmentRepo.findById(3L)).thenReturn(Optional.empty());

        assertThat(service.getAttachment(5L, 1L)).isSameAs(mine);
        assertThatThrownBy(() -> service.getAttachment(5L, 2L))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getAttachment(5L, 3L))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.resolveAttachmentPath(mine)).isEqualTo(Path.of("/abs/question-5/create/1-a.txt"));
    }

    @Test
    void list_non_admin_ignores_all_flag() {
        service.list("user1", false, true);
        verify(sessionRepo).findByKindAndRequesterIdOrderByCreatedAtDesc(InterviewKind.QUESTION, "user1");
        verify(sessionRepo, never()).findByKindOrderByCreatedAtDesc(any());
    }

    @Test
    void list_admin_with_all_uses_kind_wide_query() {
        service.list("admin1", true, true);
        verify(sessionRepo).findByKindOrderByCreatedAtDesc(InterviewKind.QUESTION);
    }

    @Test
    void close_checks_kind_then_delegates_to_cancel() {
        service.close(5L, "user1", false);
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        verify(interviewService).cancel(eq(5L), eq("user1"), eq(false));
    }

    // ── 첨부 파일 부재 응답 (AttachmentCleanupJob이 지울 수 있는 경우만 410) ─────────────

    private QuestionAttachment attachmentOfSession(InterviewStatus status) {
        InterviewSession s = mock(InterviewSession.class);
        when(s.getStatus()).thenReturn(status);
        when(sessionRepo.findById(5L)).thenReturn(Optional.of(s));
        return QuestionAttachment.create(5L, null, "a.txt", "question-5/create/1-a.txt",
                "text/plain", 1, null, "user1");
    }

    @Test
    void requireAttachmentFile_returns_the_path_when_the_file_exists() throws Exception {
        Path existing = java.nio.file.Files.createTempFile("qatt", ".txt");
        try {
            when(attachmentStorage.resolve("question-5/create/1-a.txt")).thenReturn(existing);
            assertThat(service.requireAttachmentFile(attachmentOfSession(InterviewStatus.AWAITING_INPUT)))
                    .isEqualTo(existing);
        } finally {
            java.nio.file.Files.deleteIfExists(existing);
        }
    }

    @Test
    void requireAttachmentFile_missing_file_of_closed_question_is_410() {
        for (InterviewStatus closed : List.of(InterviewStatus.CANCELLED, InterviewStatus.EXPIRED, InterviewStatus.FAILED)) {
            QuestionAttachment att = attachmentOfSession(closed);
            assertThatThrownBy(() -> service.requireAttachmentFile(att))
                    .isInstanceOf(TaskException.class)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).as("%s", closed).isEqualTo(HttpStatus.GONE));
        }
    }

    @Test
    void requireAttachmentFile_missing_file_of_open_question_is_404_not_retention_410() {
        // 진행 중·답변 완료 질문의 파일은 정리 잡이 절대 지우지 않는다 — 없으면 실제 유실이므로 "보존 기간" 안내(410) 금지
        for (InterviewStatus open : List.of(InterviewStatus.QUEUED, InterviewStatus.RUNNING, InterviewStatus.AWAITING_INPUT)) {
            QuestionAttachment att = attachmentOfSession(open);
            assertThatThrownBy(() -> service.requireAttachmentFile(att))
                    .isInstanceOf(TaskException.class)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).as("%s", open).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ── DB 연결 (스펙 2026-10-02 §5.3) ────────────────────────────────

    private static QuestionCreateRequest reqWithDb(List<Long> dbIds) {
        return new QuestionCreateRequest(1L, "dev", "t", "q", null, null, null, dbIds);
    }

    private InterviewSession askableSession() {
        InterviewSession s = InterviewSession.createQuestion("micthebick84/netis7.0", "main", "t", "q", "user1",
                new ArrayList<>(), "claude-sonnet-5", "medium");
        ReflectionTestUtils.setField(s, "id", 5L);
        s.setRepoCatalogId(1L);
        s.setDbConnectionIds(new ArrayList<>(List.of(7L)));
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        when(interviewService.submitAnswer(eq(5L), anyString(), anyBoolean(), any())).thenReturn(answered(s, 4));
        when(interviewService.appendSystemNote(eq(5L), anyString()))
                .thenAnswer(i -> InterviewTurn.of(5L, 9, "system", "note", i.getArgument(1), null));
        return s;
    }

    @Test
    void create_saves_db_ids_validated_against_requester_and_repo() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(reqWithDb(List.of(7L, 8L)), "user1");
        verify(dbConnections).validateSelection(1L, "user1", List.of(7L, 8L));
        assertThat(s.getDbConnectionIds()).containsExactly(7L, 8L);
    }

    @Test
    void create_db_validation_failure_creates_no_session() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        when(dbConnections.validateSelection(any(), any(), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: 9"));
        assertThatThrownBy(() -> service.create(reqWithDb(List.of(9L)), "user1")).isInstanceOf(TaskException.class);
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void ask_same_db_set_is_noop_without_validation() {
        InterviewSession s = askableSession();
        QuestionService.AskResult r = service.ask(5L, "user1", false,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(7L)), null);
        assertThat(r.dbNote()).isNull();
        verify(dbConnections, never()).validateSelection(any(), any(), any());
        assertThat(s.getDbConnectionIds()).containsExactly(7L);
    }

    @Test
    void ask_changed_db_set_validates_against_session_owner_and_appends_note() {
        InterviewSession s = askableSession();
        when(dbConnections.chipsFor(List.of(8L))).thenReturn(List.of(new DbConnectionDto.Chip(8, "분석 DB", "ORACLE")));
        QuestionService.AskResult r = service.ask(5L, "admin1", true,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(8L)), null);
        verify(dbConnections).validateSelection(1L, "user1", List.of(8L));   // actor(admin1)가 아니라 소유자(user1)
        assertThat(s.getDbConnectionIds()).containsExactly(8L);
        assertThat(r.dbNote().getContent()).isEqualTo("DB 연결 변경: 분석 DB");
    }

    @Test
    void ask_empty_db_list_clears_with_note_and_null_keeps() {
        InterviewSession s = askableSession();
        QuestionService.AskResult keep = service.ask(5L, "user1", false,
                new QuestionAskRequest("a", 3, null, null, null, null), null);
        assertThat(keep.dbNote()).isNull();
        QuestionService.AskResult clear = service.ask(5L, "user1", false,
                new QuestionAskRequest("b", 3, null, null, null, List.of()), null);
        assertThat(s.getDbConnectionIds()).isEmpty();
        assertThat(clear.dbNote().getContent()).isEqualTo("DB 연결 변경: 없음(전부 해제)");
    }

    @Test
    void ask_clear_note_is_based_on_validated_ids_not_on_chips() {
        // 선택은 유효하지만 칩이 비어 보이는 경우(행이 사이에 사라짐 등)에도 "전부 해제"라고 거짓 안내하면 안 된다.
        askableSession();
        when(dbConnections.chipsFor(List.of(8L))).thenReturn(List.of());
        QuestionService.AskResult r = service.ask(5L, "user1", false,
                new QuestionAskRequest("c", 3, null, null, null, List.of(8L)), null);
        assertThat(r.dbNote().getContent()).doesNotContain("전부 해제");
    }

    // 세션에 이미 있던 id는 사후 비활성·삭제여도 유지·해제할 수 있어야 한다 — 새로 추가되는 id만 검증한다.

    @Test
    void ask_keeps_already_selected_stale_id_and_validates_only_added_ids() {
        InterviewSession s = askableSession();   // 현재 [7] — 7은 이제 비활성이라 validateSelection이 거부한다고 가정
        when(dbConnections.validateSelection(any(), any(), eq(List.of(7L))))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: 7"));
        when(dbConnections.chipsFor(List.of(7L, 8L))).thenReturn(List.of(
                new DbConnectionDto.Chip(7, "옛 DB", "MYSQL"), new DbConnectionDto.Chip(8, "분석 DB", "ORACLE")));
        QuestionService.AskResult r = service.ask(5L, "user1", false,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(7L, 8L)), null);
        verify(dbConnections).validateSelection(1L, "user1", List.of(8L));   // 추가분만
        verify(dbConnections, never()).validateSelection(any(), any(), eq(List.of(7L, 8L)));
        assertThat(s.getDbConnectionIds()).containsExactly(7L, 8L);
        assertThat(r.dbNote().getContent()).isEqualTo("DB 연결 변경: 옛 DB, 분석 DB");
    }

    @Test
    void ask_invalid_added_db_id_is_rejected_even_when_stale_id_is_kept() {
        InterviewSession s = askableSession();
        when(dbConnections.validateSelection(any(), any(), eq(List.of(9L))))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: 9"));
        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(7L, 9L)), null))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(s.getDbConnectionIds()).containsExactly(7L);
    }

    @Test
    void ask_removing_stale_db_id_succeeds_without_validation() {
        InterviewSession s = askableSession();
        s.setDbConnectionIds(new ArrayList<>(List.of(7L, 8L)));   // 7은 비활성이라 가정 — 해제는 검증 없이 통과해야 한다
        when(dbConnections.chipsFor(List.of(8L))).thenReturn(List.of(new DbConnectionDto.Chip(8, "분석 DB", "ORACLE")));
        QuestionService.AskResult r = service.ask(5L, "user1", false,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(8L)), null);
        verify(dbConnections, never()).validateSelection(any(), any(), any());
        assertThat(s.getDbConnectionIds()).containsExactly(8L);
        assertThat(r.dbNote().getContent()).isEqualTo("DB 연결 변경: 분석 DB");
    }

    @Test
    void ask_db_cap_applies_to_resulting_set_including_kept_ids() {
        InterviewSession s = askableSession();
        s.setDbConnectionIds(new ArrayList<>(List.of(7L, 8L, 9L)));
        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(7L, 8L, 9L, 10L)), null))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(s.getDbConnectionIds()).containsExactly(7L, 8L, 9L);
    }

    // ── 참조 repo (스펙 2026-10-22 §4.1·§4.2) ──────────────────────────────────

    /** 참조로 쓰는 카탈로그 11·12·13·14 = 대상(1)과 다른 레포. alias는 시스템 메모 문구 검증에 쓴다. */
    private static final Map<Long, String> REF_ALIASES =
            Map.of(11L, "옛앱", 12L, "배치 서버", 13L, "공통 UI", 14L, "분석 도구");
    private static final String SHA_A = "a".repeat(40);

    /** 방금 고른 참조 — 러너가 아직 받지 않아 sha·fetchedAt·error가 없다. */
    private static RefRepo ref(long catalogId, String branch) {
        return new RefRepo(catalogId, REF_ALIASES.get(catalogId), "grp/app-" + catalogId,
                "https://gitlab.example/grp/app-" + catalogId + ".git", branch, null, null, null);
    }

    /** 러너가 커밋을 고정하고 한 번 실패도 보고한 참조 — 유지될 때 세 상태 필드가 모두 보존되는지 보는 용도. */
    private static RefRepo stateful(long catalogId, String branch) {
        return ref(catalogId, branch).pinned(SHA_A, "2026-10-22T10:00:00+09:00").withError("네트워크 오류");
    }

    private static RefRepoInput in(long catalogId, String branch) {
        return new RefRepoInput(catalogId, branch);
    }

    private static QuestionCreateRequest createReq(List<RefRepoInput> refs) {
        return new QuestionCreateRequest(1L, "dev", "t", "q", null, null, null, null, refs);
    }

    private static QuestionAskRequest askReq(List<RefRepoInput> refs) {
        return new QuestionAskRequest("추가", 3, null, null, null, null, refs);
    }

    /** 새로 들어오는 짝은 입력 그대로 스냅샷이 된다고 가정하는 목(실제 해석은 RepoCatalogServiceTest가 검증). */
    @SuppressWarnings("unchecked")
    private void stubResolveAsFreshSnapshots() {
        when(repoCatalog.resolveReferences(eq(1L), any())).thenAnswer(i ->
                ((List<RefRepoInput>) i.getArgument(1)).stream().map(r -> ref(r.repoCatalogId(), r.branch())).toList());
    }

    /** ask 가능한 질문 세션(askableSession) + 현재 참조. */
    private InterviewSession askableSessionWithRefs(RefRepo... refs) {
        InterviewSession s = askableSession();
        s.setRefRepos(new ArrayList<>(List.of(refs)));
        return s;
    }

    private static void assertBadRequest(Throwable t, String message) {
        assertThat(t).isInstanceOfSatisfying(TaskException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void create_saves_reference_snapshots_resolved_against_the_target() {
        when(sessionRepo.countActiveQuestionsByRequester("u1")).thenReturn(0L);
        List<RefRepoInput> refs = List.of(in(11L, "release/6.5"));
        when(repoCatalog.resolveReferences(eq(1L), any())).thenReturn(List.of(ref(11L, "release/6.5")));   // 불변 리스트

        InterviewSession s = service.create(createReq(refs), "u1");

        verify(repoCatalog).resolveReferences(1L, refs);   // 대상(요청의 repoCatalogId=1)을 기준으로, 요청 그대로
        assertThat(s.getRefRepos()).extracting(RefRepo::catalogId).containsExactly(11L);
        assertThat(s.getRefRepos()).containsExactly(ref(11L, "release/6.5"));
        // 엔티티가 들고 있는 목록은 가변이어야 한다 — 불변 결과를 그대로 넣지 않았다
        assertThatCode(() -> s.getRefRepos().add(ref(12L, "main"))).doesNotThrowAnyException();
        verify(sessionRepo).save(s);
    }

    @Test
    void create_without_references_stores_an_empty_list() {
        when(sessionRepo.countActiveQuestionsByRequester("u1")).thenReturn(0L);
        InterviewSession s = service.create(createReq(null), "u1");
        assertThat(s.getRefRepos()).isNotNull().isEmpty();
    }

    @Test
    void create_reference_failure_creates_no_session() {
        when(sessionRepo.countActiveQuestionsByRequester("u1")).thenReturn(0L);
        when(repoCatalog.resolveReferences(any(), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "대상 레포는 참조로 고를 수 없습니다"));

        assertThatThrownBy(() -> service.create(createReq(List.of(in(1L, "main"))), "u1"))
                .satisfies(t -> assertBadRequest(t, "대상 레포는 참조로 고를 수 없습니다"));
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void ask_with_null_refs_keeps_current_selection() {
        InterviewSession s = askableSessionWithRefs(stateful(11L, "main"));

        QuestionService.AskResult r = service.ask(5L, "user1", false, askReq(null), null);

        assertThat(r.refNote()).isNull();
        assertThat(s.getRefRepos()).containsExactly(stateful(11L, "main"));
        verify(repoCatalog, never()).resolveReferences(any(), any());
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_with_same_ref_set_in_any_order_is_a_noop_without_resolving() {
        // 세션 [11 main, 12 dev], 입력 [12 dev, 11 main] — 순서만 다르다
        InterviewSession s = askableSessionWithRefs(stateful(11L, "main"), ref(12L, "dev"));

        QuestionService.AskResult r = service.ask(5L, "user1", false,
                askReq(List.of(in(12L, "dev"), in(11L, "main"))), null);

        assertThat(r.refNote()).isNull();
        verify(repoCatalog, never()).resolveReferences(any(), any());
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
        assertThat(s.getRefRepos()).containsExactly(stateful(11L, "main"), ref(12L, "dev"));   // 순서·상태 그대로
    }

    @Test
    void ask_empty_list_when_nothing_is_selected_is_a_noop() {
        InterviewSession s = askableSessionWithRefs();

        QuestionService.AskResult r = service.ask(5L, "user1", false, askReq(List.of()), null);

        assertThat(r.refNote()).isNull();
        assertThat(s.getRefRepos()).isEmpty();
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_keeps_state_of_retained_refs_and_resolves_only_added_ones() {
        // 세션 [11 main — 커밋 고정·오류 기록 있음], 입력 [11 main, 12 master]
        RefRepo retained = stateful(11L, "main");
        InterviewSession s = askableSessionWithRefs(retained);
        stubResolveAsFreshSnapshots();

        QuestionService.AskResult r = service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(12L, "master"))), null);

        verify(repoCatalog).resolveReferences(1L, List.of(in(12L, "master")));   // 유지되는 짝은 다시 검증하지 않는다
        assertThat(s.getRefRepos()).containsExactly(retained, ref(12L, "master"));
        assertThat(s.getRefRepos().get(0)).isSameAs(retained);   // 기존 원소 그대로 — sha·fetchedAt·error 보존
        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
        verify(interviewService).appendSystemNote(5L, "참조 repo 변경: ＋ 배치 서버(master)");
        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: ＋ 배치 서버(master)");
    }

    @Test
    void ask_note_lists_additions_then_removals() {
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"), ref(12L, "dev"));
        stubResolveAsFreshSnapshots();

        QuestionService.AskResult r = service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(13L, "master"))), null);

        assertThat(s.getRefRepos()).containsExactly(ref(11L, "main"), ref(13L, "master"));
        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: ＋ 공통 UI(master), − 배치 서버(dev)");
    }

    @Test
    void ask_removal_only_note_has_just_the_minus_part() {
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"), ref(12L, "dev"));

        QuestionService.AskResult r = service.ask(5L, "user1", false, askReq(List.of(in(12L, "dev"))), null);

        assertThat(s.getRefRepos()).containsExactly(ref(12L, "dev"));
        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: − 옛앱(main)");
    }

    @Test
    void ask_branch_change_counts_as_remove_plus_add_and_clears_state() {
        // 세션 [11 main — 커밋 고정·오류 있음], 입력 [11 dev]
        InterviewSession s = askableSessionWithRefs(stateful(11L, "main"));
        stubResolveAsFreshSnapshots();

        QuestionService.AskResult r = service.ask(5L, "user1", false, askReq(List.of(in(11L, "dev"))), null);

        verify(repoCatalog).resolveReferences(1L, List.of(in(11L, "dev")));
        assertThat(s.getRefRepos()).containsExactly(ref(11L, "dev"));
        assertThat(s.getRefRepos().get(0).sha()).isNull();
        assertThat(s.getRefRepos().get(0).fetchedAt()).isNull();
        assertThat(s.getRefRepos().get(0).error()).isNull();
        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: ＋ 옛앱(dev), − 옛앱(main)");
    }

    @Test
    void ask_empty_list_clears_all_and_notes_it() {
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"), ref(12L, "dev"));

        QuestionService.AskResult r = service.ask(5L, "user1", false, askReq(List.of()), null);

        assertThat(s.getRefRepos()).isEmpty();
        verify(interviewService).appendSystemNote(5L, "참조 repo 변경: 없음(전부 해제)");
        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: 없음(전부 해제)");
    }

    @Test
    void ask_result_follows_the_input_order_mixing_retained_and_new_refs() {
        RefRepo retained = stateful(11L, "main");
        InterviewSession s = askableSessionWithRefs(retained);
        stubResolveAsFreshSnapshots();

        service.ask(5L, "user1", false,
                askReq(List.of(in(12L, "main"), in(11L, "main"), in(13L, "main"))), null);

        verify(repoCatalog).resolveReferences(1L, List.of(in(12L, "main"), in(13L, "main")));
        assertThat(s.getRefRepos()).containsExactly(ref(12L, "main"), retained, ref(13L, "main"));
    }

    @Test
    void ask_swap_at_the_cap_is_allowed_because_the_cap_applies_to_the_result() {
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"), ref(12L, "main"), ref(13L, "main"));
        stubResolveAsFreshSnapshots();

        QuestionService.AskResult r = service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(12L, "main"), in(14L, "main"))), null);

        assertThat(s.getRefRepos()).extracting(RefRepo::catalogId).containsExactly(11L, 12L, 14L);
        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: ＋ 분석 도구(main), − 공통 UI(main)");
    }

    @Test
    void ask_over_the_cap_is_400_and_changes_nothing() {
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"), ref(12L, "main"), ref(13L, "main"));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(12L, "main"), in(13L, "main"), in(14L, "main"))), null))
                .satisfies(t -> assertBadRequest(t, "참조 repo는 최대 3개까지 고를 수 있습니다"));

        assertThat(s.getRefRepos()).extracting(RefRepo::catalogId).containsExactly(11L, 12L, 13L);
        verify(repoCatalog, never()).resolveReferences(any(), any());
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_target_repo_as_a_reference_is_400_and_changes_nothing() {
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"));   // 대상 = 카탈로그 1

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(1L, "main"))), null))
                .satisfies(t -> assertBadRequest(t, "대상 레포는 참조로 고를 수 없습니다"));

        assertThat(s.getRefRepos()).containsExactly(ref(11L, "main"));
        verify(repoCatalog, never()).resolveReferences(any(), any());
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_same_repo_twice_is_400_and_changes_nothing() {
        // [11 main, 11 dev] — 11 dev는 새로 들어오는 짝이라 모양 검사에서 걸린다
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(11L, "dev"))), null))
                .satisfies(t -> assertBadRequest(t, "같은 레포를 참조로 두 번 고를 수 없습니다"));

        assertThat(s.getRefRepos()).containsExactly(ref(11L, "main"));
        verify(repoCatalog, never()).resolveReferences(any(), any());
    }

    @Test
    void ask_input_hitting_the_same_current_ref_twice_is_a_duplicate_not_the_same_set() {
        // 세션 [11 main, 12 dev]에 입력 [11 main, 11 main] — 개수는 같지만 집합이 다르다(12가 빠진다).
        // "같은 집합" no-op으로 조용히 넘기면 안 되고, 중복으로 400이어야 한다(스펙 §4.1의 4번).
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"), ref(12L, "dev"));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(11L, "main"))), null))
                .satisfies(t -> assertBadRequest(t, "같은 레포를 참조로 두 번 고를 수 없습니다"));

        assertThat(s.getRefRepos()).containsExactly(ref(11L, "main"), ref(12L, "dev"));
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    // 아래 두 케이스는 kept(Set)가 중복 적중을 합쳐서 current와 같은 크기가 되는 경우다. no-op 판정의
    // `kept.size() == slots.size()` 절이 없으면 중복이 "같은 집합"으로 조용히 새어 나간다. 바로 위 테스트(세션 [11,12] + 입력
    // [11,11])는 kept(1) ≠ current(2)라 다른 절에서 이미 걸려서 이 절을 고정하지 못한다.

    @Test
    void ask_input_repeating_the_only_current_ref_is_a_duplicate_400_not_a_noop() {
        // 세션 [11 main]에 입력 [11 main, 11 main] — 두 입력이 같은 현재 원소를 가리켜 kept={11}, current도 1개
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(11L, "main"))), null))
                .satisfies(t -> assertBadRequest(t, "같은 레포를 참조로 두 번 고를 수 없습니다"));

        assertThat(s.getRefRepos()).containsExactly(ref(11L, "main"));
        verify(repoCatalog, never()).resolveReferences(any(), any());
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_input_repeating_one_of_several_current_refs_is_a_duplicate_400_not_a_noop() {
        // 세션 [11 main, 12 dev]에 입력 [11 main, 12 dev, 12 dev] — 12가 두 번 적중해 kept={11,12}, current도 2개
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"), ref(12L, "dev"));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(12L, "dev"), in(12L, "dev"))), null))
                .satisfies(t -> assertBadRequest(t, "같은 레포를 참조로 두 번 고를 수 없습니다"));

        assertThat(s.getRefRepos()).containsExactly(ref(11L, "main"), ref(12L, "dev"));
        verify(repoCatalog, never()).resolveReferences(any(), any());
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_invalid_added_reference_is_rejected_and_leaves_refs_untouched() {
        InterviewSession s = askableSessionWithRefs(stateful(11L, "main"));
        when(repoCatalog.resolveReferences(eq(1L), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "비활성화된 레포 카탈로그 항목: 배치 서버"));

        assertThatThrownBy(() -> service.ask(5L, "user1", false,
                askReq(List.of(in(11L, "main"), in(12L, "main"))), null))
                .satisfies(t -> assertBadRequest(t, "비활성화된 레포 카탈로그 항목: 배치 서버"));

        assertThat(s.getRefRepos()).containsExactly(stateful(11L, "main"));
        verify(interviewService, never()).appendSystemNote(anyLong(), anyString());
    }

    @Test
    void ask_strips_input_branches_so_a_padded_name_matches_the_stored_ref() {
        InterviewSession s = askableSessionWithRefs(stateful(11L, "main"));

        QuestionService.AskResult r = service.ask(5L, "user1", false, askReq(List.of(in(11L, " main "))), null);

        assertThat(r.refNote()).isNull();   // " main " == main → 빼고 다시 넣기가 아니라 no-op
        verify(repoCatalog, never()).resolveReferences(any(), any());
        assertThat(s.getRefRepos()).containsExactly(stateful(11L, "main"));
    }

    @Test
    void ask_resolves_a_new_ref_with_the_stripped_branch() {
        askableSessionWithRefs();
        stubResolveAsFreshSnapshots();

        service.ask(5L, "user1", false, askReq(List.of(in(12L, "  master\t"))), null);

        verify(repoCatalog).resolveReferences(1L, List.of(in(12L, "master")));
    }

    @Test
    void ask_blank_branch_for_an_already_selected_repo_keeps_that_ref() {
        // RefRepo.matches: branch가 null·blank면 카탈로그 id만 비교한다 — strip이 null을 ""로 바꾸지 않는지도 함께 본다
        InterviewSession s = askableSessionWithRefs(stateful(11L, "main"));

        QuestionService.AskResult nullBranch = service.ask(5L, "user1", false, askReq(List.of(in(11L, null))), null);
        QuestionService.AskResult blankBranch = service.ask(5L, "user1", false, askReq(List.of(in(11L, "  "))), null);

        assertThat(nullBranch.refNote()).isNull();
        assertThat(blankBranch.refNote()).isNull();
        assertThat(s.getRefRepos()).containsExactly(stateful(11L, "main"));
        verify(repoCatalog, never()).resolveReferences(any(), any());
    }

    @Test
    void ask_appends_the_reference_note_after_the_db_note() {
        InterviewSession s = askableSessionWithRefs(ref(11L, "main"));
        when(dbConnections.chipsFor(List.of(8L))).thenReturn(List.of(new DbConnectionDto.Chip(8, "분석 DB", "ORACLE")));

        QuestionService.AskResult r = service.ask(5L, "user1", false,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(8L), List.of()), null);

        InOrder order = inOrder(interviewService);
        order.verify(interviewService).appendSystemNote(5L, "DB 연결 변경: 분석 DB");
        order.verify(interviewService).appendSystemNote(5L, "참조 repo 변경: 없음(전부 해제)");
        assertThat(r.dbNote().getContent()).isEqualTo("DB 연결 변경: 분석 DB");
        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: 없음(전부 해제)");
        assertThat(s.getRefRepos()).isEmpty();
    }

    @Test
    void ask_result_keeps_the_legacy_two_and_three_arg_constructors() {
        InterviewSession s = questionSession("claude-opus-5", "high");
        InterviewTurn mcp = InterviewTurn.of(5L, 5, "system", "note", "m", null);
        InterviewTurn db = InterviewTurn.of(5L, 6, "system", "note", "d", null);

        QuestionService.AskResult two = new QuestionService.AskResult(s, mcp);
        QuestionService.AskResult three = new QuestionService.AskResult(s, mcp, db);

        assertThat(two.mcpNote()).isSameAs(mcp);
        assertThat(two.dbNote()).isNull();
        assertThat(two.refNote()).isNull();
        assertThat(three.dbNote()).isSameAs(db);
        assertThat(three.refNote()).isNull();
    }
}
