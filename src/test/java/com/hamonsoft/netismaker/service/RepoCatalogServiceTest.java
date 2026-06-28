package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.BranchListResponse;
import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RepoCatalogServiceTest {

    private RepoCatalogRepository repo;
    private GitRefService gitRefService;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        gitRefService = mock(GitRefService.class);
        service = new RepoCatalogService(repo, gitRefService);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private RepoCatalogEntry entry(Long id, String alias, boolean enabled, String host, String ownerRepo) {
        RepoCatalogEntry e = RepoCatalogEntry.create(alias,
                "https://github.com/" + (ownerRepo == null ? "x/y" : ownerRepo) + ".git",
                host, ownerRepo, null, null, "admin");
        e.setEnabled(enabled);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    void create_derives_owner_repo_and_host_from_full_url() {
        when(repo.findByAlias("Netis7.0")).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "Netis7.0", "https://github.com/micthebick84/netis7.0.git", null, "데모", true);

        RepoCatalogEntry saved = service.create(req, "admin");

        assertThat(saved.getHost()).isEqualTo("github");
        assertThat(saved.getOwnerRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(saved.getGitUrl()).isEqualTo("https://github.com/micthebick84/netis7.0.git");
        assertThat(saved.getAlias()).isEqualTo("Netis7.0");
    }

    @Test
    void create_accepts_bare_owner_repo_input() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "별칭", "owner/repo", null, null, true);

        RepoCatalogEntry saved = service.create(req, "admin");

        assertThat(saved.getOwnerRepo()).isEqualTo("owner/repo");
        assertThat(saved.getGitUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void create_rejects_duplicate_alias_with_409() {
        when(repo.findByAlias("Netis7.0")).thenReturn(Optional.of(entry(1L, "Netis7.0", true, "github", "a/b")));
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "Netis7.0", "https://github.com/a/b.git", null, null, true);

        assertThatThrownBy(() -> service.create(req, "admin"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("이미 존재");
    }

    @Test
    void create_rejects_unparseable_url_with_400() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "x", "not a url", null, null, true);

        assertThatThrownBy(() -> service.create(req, "admin"))
                .isInstanceOf(TaskException.class)
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void resolveForRegistration_returns_owner_repo_for_github_entry() {
        when(repo.findById(7L)).thenReturn(Optional.of(entry(7L, "Netis7.0", true, "github", "micthebick84/netis7.0")));

        RepoCatalogService.ResolvedRepo r = service.resolveForRegistration(7L);

        assertThat(r.ownerRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(r.alias()).isEqualTo("Netis7.0");
        assertThat(r.catalogId()).isEqualTo(7L);
    }

    @Test
    void resolveForRegistration_rejects_disabled_entry() {
        when(repo.findById(7L)).thenReturn(Optional.of(entry(7L, "X", false, "github", "a/b")));
        assertThatThrownBy(() -> service.resolveForRegistration(7L))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("비활성");
    }

    @Test
    void resolveForRegistration_rejects_non_github_entry() {
        when(repo.findById(7L)).thenReturn(Optional.of(entry(7L, "GL", true, "other", null)));
        assertThatThrownBy(() -> service.resolveForRegistration(7L))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("GitHub");
    }

    @Test
    void resolveForRegistration_rejects_missing_id() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveForRegistration(99L))
                .isInstanceOf(TaskException.class);
    }

    // ── NEW TESTS ──────────────────────────────────────────────────────────

    @Test
    void resolveForRegistration_rejects_null_id() {
        assertThatThrownBy(() -> service.resolveForRegistration(null))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void create_rejects_duplicate_gitUrl_with_409() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.of(entry(2L, "other", true, "github", "a/b")));
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest(
                "새별칭", "https://github.com/a/b.git", null, null, true);

        assertThatThrownBy(() -> service.create(req, "admin"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("이미 등록");
    }

    @Test
    void check_non_github_returns_false_without_calling_gitRefService() {
        when(repo.findById(1L)).thenReturn(Optional.of(entry(1L, "GL", true, "other", null)));

        RepoCatalogDto.CheckResult result = service.check(1L);

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("GitHub");
        verify(gitRefService, never()).listBranches(any());
    }

    @Test
    void check_catches_task_exception_from_git_ref_service() {
        when(repo.findById(1L)).thenReturn(Optional.of(entry(1L, "A", true, "github", "a/b")));
        when(gitRefService.listBranches("a/b"))
                .thenThrow(new TaskException(HttpStatus.BAD_GATEWAY, "rate limit"));

        RepoCatalogDto.CheckResult result = service.check(1L);

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("rate limit");
    }

    @Test
    void check_success_returns_branch_count() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(entry(1L, "A", true, "github", "a/b")));
        BranchListResponse branchList = new BranchListResponse(
                "a/b",
                "main",
                List.of(
                        new BranchListResponse.BranchEntry("main", "abc123"),
                        new BranchListResponse.BranchEntry("develop", "def456")
                ),
                OffsetDateTime.now()
        );
        when(gitRefService.listBranches("a/b")).thenReturn(branchList);

        RepoCatalogDto.CheckResult result = service.check(1L);

        assertThat(result.reachable()).isTrue();
        assertThat(result.defaultBranch()).isEqualTo("main");
        assertThat(result.branchCount()).isEqualTo(2);
        assertThat(result.error()).isNull();
    }
}
