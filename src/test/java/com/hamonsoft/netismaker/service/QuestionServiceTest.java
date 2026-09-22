package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.QuestionAskRequest;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import com.hamonsoft.netismaker.repository.QuestionAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
        service = new QuestionService(interviewService, sessionRepo, turnRepo, repoCatalog, mcpCatalog,
                attachmentStorage, tika, attachmentRepo, 3, 10);
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
}
