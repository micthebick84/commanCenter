package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private QuestionService service;

    @BeforeEach
    void setUp() {
        interviewService = mock(InterviewService.class);
        sessionRepo = mock(InterviewSessionRepository.class);
        turnRepo = mock(InterviewTurnRepository.class);
        repoCatalog = mock(RepoCatalogService.class);
        mcpCatalog = mock(McpCatalogService.class);
        service = new QuestionService(interviewService, sessionRepo, turnRepo, repoCatalog, mcpCatalog, 3, 10);
        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repoCatalog.resolveForRegistration(1L)).thenReturn(REPO);
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
        assertThatThrownBy(() -> service.ask(5L, "user1", false, new AnswerRequest("더 자세히?", 9)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        verify(interviewService, never()).submitAnswer(anyLong(), anyString(), anyBoolean(), any());
    }

    @Test
    void ask_below_cap_delegates_to_submitAnswer_with_real_isAdmin() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(9L);
        AnswerRequest req = new AnswerRequest("더 자세히?", 9);
        service.ask(5L, "user1", false, req);
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        verify(interviewService).submitAnswer(5L, "user1", false, req);
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
