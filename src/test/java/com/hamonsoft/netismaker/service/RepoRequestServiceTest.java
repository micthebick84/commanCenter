package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoRequestServiceTest {

    private static final String BASE = "https://gitlab.hamon.vip";

    private GitLabProjectClient client;
    private RequestBoardService board;
    private RequestPostRepository posts;
    private RepoCatalogRepository catalog;
    private RepoRequestService service;

    @BeforeEach
    void setUp() {
        client = mock(GitLabProjectClient.class);
        board = mock(RequestBoardService.class);
        posts = mock(RequestPostRepository.class);
        catalog = mock(RepoCatalogRepository.class);
        service = new RepoRequestService(client, board, posts, catalog, BASE);
        when(client.configured()).thenReturn(true);
        when(catalog.findByGitUrl(anyString())).thenReturn(Optional.empty());
    }

    private void repoPost(Long id, RequestKind kind, String title, String content, String hint) {
        RequestPost p = RequestPost.create(kind, title, content, hint, "user1");
        ReflectionTestUtils.setField(p, "id", id);
        when(posts.findById(id)).thenReturn(Optional.of(p));
    }

    private static GitLabProjectClient.GitLabProject proj(long id, String path) {
        return new GitLabProjectClient.GitLabProject(id, "p" + id, path, "main", "d" + id);
    }

    // ---- searchTerms ----

    @Test
    void searchTerms_extracts_ascii_tokens_skips_stopwords() {
        assertThat(RepoRequestService.searchTerms("collector repo", "넷이스 수집기 추가", "gitlab netis-agent 도"))
                .containsExactly("collector", "netis-agent");
    }

    @Test
    void searchTerms_korean_only_is_empty() {
        assertThat(RepoRequestService.searchTerms("수집기", "넷이스 수집기 추가", "내용입니다")).isEmpty();
    }

    @Test
    void searchTerms_dedupes_case_insensitively_and_caps_at_three() {
        assertThat(RepoRequestService.searchTerms("Alpha alpha", "beta gamma", "delta"))
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void searchTerms_null_safe() {
        assertThat(RepoRequestService.searchTerms(null, null, null)).isEmpty();
    }

    // ---- hintPath ----

    @Test
    void hintPath_full_url() {
        assertThat(RepoRequestService.hintPath("https://gitlab.hamon.vip/g/p.git", BASE)).contains("g/p");
    }

    @Test
    void hintPath_tree_suffix() {
        assertThat(RepoRequestService.hintPath("https://gitlab.hamon.vip/g/sub/p/-/tree/main", BASE))
                .contains("g/sub/p");
    }

    @Test
    void hintPath_bare_path() {
        assertThat(RepoRequestService.hintPath("g/sub/p", BASE)).contains("g/sub/p");
        assertThat(RepoRequestService.hintPath("/g/p.git", BASE)).contains("g/p");
    }

    @Test
    void hintPath_plain_word_empty() {
        assertThat(RepoRequestService.hintPath("collector", BASE)).isEmpty();
    }

    @Test
    void hintPath_blank_null_or_foreign_host_empty() {
        assertThat(RepoRequestService.hintPath(null, BASE)).isEmpty();
        assertThat(RepoRequestService.hintPath("   ", BASE)).isEmpty();
        assertThat(RepoRequestService.hintPath("/", BASE)).isEmpty();
        assertThat(RepoRequestService.hintPath("https://github.com/o/r", BASE)).isEmpty();
        assertThat(RepoRequestService.hintPath("https://gitlab.hamon.vip/", BASE)).isEmpty();
        assertThat(RepoRequestService.hintPath("a b/c", BASE)).isEmpty();
    }

    // ---- candidates ----

    @Test
    void candidates_put_hint_first_dedupe_limit5() {
        repoPost(8L, RequestKind.REPO, "collector", "내용", "g/p");
        when(client.findByPath("g/p")).thenReturn(Optional.of(proj(1, "g/p")));
        when(client.search("collector", 5)).thenReturn(List.of(
                proj(1, "g/p"), proj(2, "g/b"), proj(3, "g/c"), proj(4, "g/d"), proj(5, "g/e"), proj(6, "g/f")));

        List<RequestBoardDto.RepoCandidate> out = service.candidates(8L);

        assertThat(out).extracting(RequestBoardDto.RepoCandidate::id).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void candidates_marks_registered() {
        repoPost(8L, RequestKind.REPO, "collector", "내용", null);
        when(client.search("collector", 5)).thenReturn(List.of(proj(1, "g/a"), proj(2, "g/b")));
        when(catalog.findByGitUrl("https://gitlab.hamon.vip/g/b.git"))
                .thenReturn(Optional.of(mock(RepoCatalogEntry.class)));

        List<RequestBoardDto.RepoCandidate> out = service.candidates(8L);

        assertThat(out).extracting(RequestBoardDto.RepoCandidate::registered).containsExactly(false, true);
        assertThat(out.get(0).pathWithNamespace()).isEqualTo("g/a");
        assertThat(out.get(0).defaultBranch()).isEqualTo("main");
    }

    @Test
    void candidates_korean_only_does_not_call_gitlab() {
        repoPost(8L, RequestKind.REPO, "수집기 추가", "내용입니다", "수집기");

        assertThat(service.candidates(8L)).isEmpty();

        verify(client, never()).search(anyString(), anyInt());
        verify(client, never()).findByPath(anyString());
    }

    @Test
    void candidates_korean_only_but_unconfigured_is_503() {
        repoPost(8L, RequestKind.REPO, "수집기", "내용", null);
        when(client.configured()).thenReturn(false);

        assertThatThrownBy(() -> service.candidates(8L))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void candidates_hint_path_not_found_is_ignored() {
        repoPost(8L, RequestKind.REPO, "collector", "내용", "g/p");
        when(client.findByPath("g/p")).thenReturn(Optional.empty());
        when(client.search("collector", 5)).thenReturn(List.of(proj(2, "g/b")));

        assertThat(service.candidates(8L)).extracting(RequestBoardDto.RepoCandidate::id).containsExactly(2L);
    }

    @Test
    void candidates_non_repo_request_conflicts() {
        repoPost(8L, RequestKind.FEATURE, "collector", "내용", null);

        assertThatThrownBy(() -> service.candidates(8L))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("REPO 추가 요청이 아닙니다");
                });
        verify(client, never()).search(anyString(), anyInt());
    }

    @Test
    void candidates_missing_request_404() {
        when(posts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.candidates(99L))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("요청을 찾을 수 없습니다");
                });
    }

    // ---- search ----

    @Test
    void search_short_query_400() {
        assertThatThrownBy(() -> service.search(" a "))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("검색어를 2자 이상 입력하세요");
                });
        assertThatThrownBy(() -> service.search(null)).isInstanceOf(TaskException.class);
        verify(client, never()).search(anyString(), anyInt());
    }

    @Test
    void search_trims_and_limits_to_10_and_marks_registered() {
        when(client.search("collector", 10)).thenReturn(List.of(proj(1, "g/a"), proj(2, "g/b")));
        when(catalog.findByGitUrl("https://gitlab.hamon.vip/g/a.git"))
                .thenReturn(Optional.of(mock(RepoCatalogEntry.class)));

        List<RequestBoardDto.RepoCandidate> out = service.search("  collector ");

        assertThat(out).extracting(RequestBoardDto.RepoCandidate::registered).containsExactly(true, false);
    }

    // ---- addRepo ----

    @Test
    void addRepo_builds_url_from_base_not_gitlab_response() {
        GitLabProjectClient.GitLabProject p = proj(42, "netis/netis-collector");
        when(client.findById(42L)).thenReturn(Optional.of(p));
        RequestBoardDto.Detail detail = mock(RequestBoardDto.Detail.class);
        when(board.completeRepoRequest(eq(5L), any(), anyString(), eq("admin1"))).thenReturn(detail);

        RequestBoardDto.Detail out = service.addRepo(5L, 42L, "admin1");

        assertThat(out).isSameAs(detail);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(board).completeRepoRequest(eq(5L), eq(p), url.capture(), eq("admin1"));
        assertThat(url.getValue()).isEqualTo("https://gitlab.hamon.vip/netis/netis-collector.git");
    }

    @Test
    void addRepo_project_not_found_404() {
        when(client.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addRepo(5L, 42L, "admin1"))
                .isInstanceOfSatisfying(TaskException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("GitLab 프로젝트를 찾을 수 없습니다");
                });
        verify(board, never()).completeRepoRequest(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void addRepo_on_done_request_conflicts() {
        TaskException conflict = TaskException.conflict("이미 처리된 요청입니다");
        doThrow(conflict).when(board).assertOpenRepoRequest(5L);

        assertThatThrownBy(() -> service.addRepo(5L, 42L, "admin1")).isSameAs(conflict);
        verify(client, never()).findById(anyLong());
    }

    @Test
    void gitUrlFor_strips_trailing_slash_of_base() {
        RepoRequestService s = new RepoRequestService(client, board, posts, catalog, "https://gitlab.hamon.vip//");
        assertThat(s.gitUrlFor("g/p")).isEqualTo("https://gitlab.hamon.vip/g/p.git");
    }
}
