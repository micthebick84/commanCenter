package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.BranchListResponse;
import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.util.RepoUrlParser;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class RepoCatalogServiceTest {

    private RepoCatalogRepository repo;
    private GitRefService gitRefService;
    private RepoCatalogService service;

    @BeforeEach
    void setUp() {
        repo = mock(RepoCatalogRepository.class);
        gitRefService = mock(GitRefService.class);
        service = new RepoCatalogService(repo, gitRefService, "https://gitlab.hamon.vip");
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
        // entry() always builds a github.com git_url (even when host="other"), and production
        // code re-parses git_url on use — so that row would now resolve as github. Use a row
        // whose git_url is a genuinely unsupported host instead.
        RepoCatalogEntry e = RepoCatalogEntry.create("GL", "https://bitbucket.org/a/b.git",
                "other", null, null, null, "admin");
        e.setEnabled(true);
        ReflectionTestUtils.setField(e, "id", 7L);
        when(repo.findById(7L)).thenReturn(Optional.of(e));
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
        // Same rationale as resolveForRegistration_rejects_non_github_entry above: entry()'s
        // github.com git_url would re-parse as github, so use a genuinely unsupported host.
        RepoCatalogEntry e = RepoCatalogEntry.create("GL", "https://bitbucket.org/a/b.git",
                "other", null, null, null, "admin");
        e.setEnabled(true);
        ReflectionTestUtils.setField(e, "id", 1L);
        when(repo.findById(1L)).thenReturn(Optional.of(e));

        RepoCatalogDto.CheckResult result = service.check(1L);

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("GitHub");
        verify(gitRefService, never()).listBranches(any(RepoRef.class));
    }

    @Test
    void check_catches_task_exception_from_git_ref_service() {
        when(repo.findById(1L)).thenReturn(Optional.of(entry(1L, "A", true, "github", "a/b")));
        when(gitRefService.listBranches(argThat((RepoRef r) -> !r.isGitlab() && r.path().equals("a/b"))))
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
        when(gitRefService.listBranches(argThat((RepoRef r) -> !r.isGitlab() && r.path().equals("a/b"))))
                .thenReturn(branchList);

        RepoCatalogDto.CheckResult result = service.check(1L);

        assertThat(result.reachable()).isTrue();
        assertThat(result.defaultBranch()).isEqualTo("main");
        assertThat(result.branchCount()).isEqualTo(2);
        assertThat(result.error()).isNull();
    }

    // ── GitLab (Task 4) ────────────────────────────────────────────────────

    private RepoCatalogEntry gitlabEntry(Long id, String storedHost, String storedOwnerRepo) {
        RepoCatalogEntry e = RepoCatalogEntry.create("Netis7",
                "https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git",
                storedHost, storedOwnerRepo, "develop", null, "admin");
        e.setEnabled(true);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    void create_gitlab_url_stores_gitlab_host_and_full_path() {
        when(repo.findByAlias("Netis7")).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogDto.UpsertRequest req = new RepoCatalogDto.UpsertRequest("Netis7",
                "git@gitlab.hamon.vip:product/netis/web/package/netis-v7.0.git", null, null, true);

        RepoCatalogEntry saved = service.create(req, "admin");

        assertThat(saved.getHost()).isEqualTo("gitlab");
        assertThat(saved.getOwnerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
        assertThat(saved.getGitUrl())
                .isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
    }

    @Test
    void resolveForRegistration_accepts_gitlab_even_when_row_was_stored_as_other() {
        when(repo.findById(9L)).thenReturn(Optional.of(gitlabEntry(9L, "other", null)));

        RepoCatalogService.ResolvedRepo r = service.resolveForRegistration(9L);

        assertThat(r.host()).isEqualTo("gitlab");
        assertThat(r.ownerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
        assertThat(r.gitUrl()).isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
        assertThat(r.defaultBranch()).isEqualTo("develop");
    }

    @Test
    void toView_reports_reparsed_host_and_path() {
        RepoCatalogDto.View v = service.toView(gitlabEntry(9L, "other", null));
        assertThat(v.host()).isEqualTo("gitlab");
        assertThat(v.ownerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
    }

    @Test
    void check_gitlab_calls_listBranches_with_gitlab_ref() {
        when(repo.findById(9L)).thenReturn(Optional.of(gitlabEntry(9L, "gitlab", "product/netis/web/package/netis-v7.0")));
        when(gitRefService.listBranches(any(RepoRef.class))).thenReturn(new BranchListResponse(
                "product/netis/web/package/netis-v7.0", "develop",
                List.of(new BranchListResponse.BranchEntry("develop", "abc")), OffsetDateTime.now()));

        RepoCatalogDto.CheckResult result = service.check(9L);

        assertThat(result.reachable()).isTrue();
        verify(gitRefService).listBranches(argThat((RepoRef ref) -> ref.isGitlab()
                && ref.path().equals("product/netis/web/package/netis-v7.0")));
    }

    @Test
    void refOf_returns_ref_for_enabled_entry_and_rejects_other_host() {
        when(repo.findById(9L)).thenReturn(Optional.of(gitlabEntry(9L, "gitlab", "x/y")));
        assertThat(service.refOf(9L).isGitlab()).isTrue();

        RepoCatalogEntry bitbucket = RepoCatalogEntry.create("BB", "https://bitbucket.org/a/b.git",
                "other", null, null, null, "admin");
        ReflectionTestUtils.setField(bitbucket, "id", 10L);
        when(repo.findById(10L)).thenReturn(Optional.of(bitbucket));
        assertThatThrownBy(() -> service.refOf(10L))
                .isInstanceOf(TaskException.class).hasMessageContaining("지원하지 않는 호스트");
    }

    @Test
    void 산출물_설정을_저장하고_null이면_유지한다() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogEntry saved = service.create(new RepoCatalogDto.UpsertRequest(
                "동부병원", "owner/netisdash", null, null, true, true, " /usr/local/tomcat/webapps/ROOT.war "), "admin");
        assertThat(saved.isArtifactEnabled()).isTrue();
        assertThat(saved.getArtifactPath()).isEqualTo("/usr/local/tomcat/webapps/ROOT.war");

        ReflectionTestUtils.setField(saved, "id", 9L);
        when(repo.findById(9L)).thenReturn(Optional.of(saved));
        service.update(9L, new RepoCatalogDto.UpsertRequest("동부병원", "owner/netisdash", null, null, false));
        assertThat(saved.isArtifactEnabled()).isTrue();          // 활성화 토글은 산출물 설정을 건드리지 않는다
        service.update(9L, new RepoCatalogDto.UpsertRequest("동부병원", "owner/netisdash", null, null, true, false, ""));
        assertThat(saved.isArtifactEnabled()).isFalse();
        assertThat(saved.getArtifactPath()).isNull();
        assertThat(RepoCatalogDto.View.of(saved).artifactEnabled()).isFalse();
    }

    // ── 참조 repo (질문 세션 읽기 전용 참조, 스펙 2026-10-22 §4.1) ──────────────────────

    /** in(id) = 브랜치 main으로 고른 참조 입력. */
    private static RefRepoInput in(long catalogId) {
        return new RefRepoInput(catalogId, "main");
    }

    /** RepoCatalogService.create가 저장하는 모양 그대로(RepoUrlParser로 파싱한 값)의 카탈로그 행. */
    private static RepoCatalogEntry refRow(Long id, String alias, String gitUrl, String defaultBranch, boolean enabled) {
        RepoUrlParser.Parsed p = RepoUrlParser.parse(gitUrl, "https://gitlab.hamon.vip");
        RepoCatalogEntry e = RepoCatalogEntry.create(alias, p.canonicalUrl(), p.host(), p.ownerRepo(),
                defaultBranch, null, "admin");
        e.setEnabled(enabled);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    /** 10 = 공통 UI(GitHub, 기본 main) · 11 = Netis6.5(사내 GitLab, 기본 release/6.5) · 12 = 배치 서버(기본 브랜치 없음). 대상은 1. */
    private void stubRefCatalog() {
        when(repo.findById(10L)).thenReturn(Optional.of(
                refRow(10L, "공통 UI", "https://github.com/acme/ui.git", "main", true)));
        when(repo.findById(11L)).thenReturn(Optional.of(
                refRow(11L, "Netis6.5", "https://gitlab.hamon.vip/product/netis/web/package/netis-v6.5.git",
                        "release/6.5", true)));
        when(repo.findById(12L)).thenReturn(Optional.of(
                refRow(12L, "배치 서버", "https://github.com/acme/batch.git", null, true)));
    }

    /** 400(BAD_REQUEST) TaskException이고 문구가 정확히 같다. */
    private static void assertBadRequest(ThrowingCallable call, String message) {
        assertThatThrownBy(call)
                .isInstanceOf(TaskException.class)
                .hasMessage(message)
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void resolveReferences_null_or_empty_is_empty() {
        assertThat(service.resolveReferences(1L, null)).isEmpty();
        assertThat(service.resolveReferences(1L, List.of())).isEmpty();
    }

    @Test
    void resolveReferences_rejects_more_than_three() {
        // 13은 없는 카탈로그 id다 — 상한 검사가 조회보다 먼저라서 "존재하지 않는 id"가 아니라 상한 문구가 나와야 한다
        assertBadRequest(() -> service.resolveReferences(1L, List.of(in(10), in(11), in(12), in(13))),
                "참조 repo는 최대 3개까지 고를 수 있습니다");
        verify(repo, never()).findById(any());
    }

    @Test
    void resolveReferences_rejects_the_target() {
        assertBadRequest(() -> service.resolveReferences(10L, List.of(in(10))),
                "대상 레포는 참조로 고를 수 없습니다");
        verify(repo, never()).findById(any());
    }

    @Test
    void resolveReferences_rejects_duplicates() {
        // 브랜치가 달라도 같은 레포는 한 번만
        assertBadRequest(() -> service.resolveReferences(1L, List.of(in(10), new RefRepoInput(10L, "dev"))),
                "같은 레포를 참조로 두 번 고를 수 없습니다");
        verify(repo, never()).findById(any());
    }

    @Test
    void resolveReferences_blank_branch_uses_catalog_default() {
        stubRefCatalog();
        assertThat(service.resolveReferences(1L, List.of(new RefRepoInput(11L, " "))).get(0).branch())
                .isEqualTo("release/6.5");
    }

    @Test
    void resolveReferences_empty_or_null_branch_also_uses_catalog_default() {
        stubRefCatalog();
        assertThat(service.resolveReferences(1L, List.of(new RefRepoInput(11L, ""))).get(0).branch())
                .isEqualTo("release/6.5");
        assertThat(service.resolveReferences(1L, List.of(new RefRepoInput(10L, null))).get(0).branch())
                .isEqualTo("main");
    }

    @Test
    void resolveReferences_blank_branch_without_default_is_400() {
        stubRefCatalog();
        assertBadRequest(() -> service.resolveReferences(1L, List.of(new RefRepoInput(12L, null))),
                "참조 브랜치를 지정하세요");
        assertBadRequest(() -> service.resolveReferences(1L, List.of(new RefRepoInput(12L, "  "))),
                "참조 브랜치를 지정하세요");
    }

    @Test
    void resolveReferences_explicit_branch_wins_over_catalog_default_and_is_stripped() {
        stubRefCatalog();
        List<RefRepo> r = service.resolveReferences(1L,
                List.of(new RefRepoInput(11L, " hotfix/6.5.3 "), new RefRepoInput(12L, "master")));
        assertThat(r).extracting(RefRepo::branch).containsExactly("hotfix/6.5.3", "master");
    }

    @Test
    void resolveReferences_rejects_unknown_or_disabled_catalog() {
        stubRefCatalog();
        when(repo.findById(13L)).thenReturn(Optional.of(
                refRow(13L, "꺼진 레포", "https://github.com/acme/off.git", "main", false)));

        // resolveForRegistration과 같은 400 — 없는 id, 비활성 항목
        assertBadRequest(() -> service.resolveReferences(1L, List.of(in(99))), "존재하지 않는 레포 카탈로그 id: 99");
        assertBadRequest(() -> service.resolveReferences(1L, List.of(in(13))), "비활성화된 레포 카탈로그 항목: 꺼진 레포");
        // 하나라도 거절되면 전체가 실패한다 — 앞의 정상 항목만 돌려주지 않는다
        assertBadRequest(() -> service.resolveReferences(1L, List.of(in(10), in(99))), "존재하지 않는 레포 카탈로그 id: 99");
    }

    @Test
    void resolveReferences_rejects_unsupported_host() {
        RepoCatalogEntry bitbucket = refRow(14L, "BB", "https://bitbucket.org/a/b.git", "main", true);
        when(repo.findById(14L)).thenReturn(Optional.of(bitbucket));

        assertThatThrownBy(() -> service.resolveReferences(1L, List.of(in(14))))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("지원하지 않는 호스트")
                .satisfies(ex -> assertThat(((TaskException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void resolveReferences_snapshots_alias_path_url_and_branch_in_input_order() {
        stubRefCatalog();
        List<RefRepo> r = service.resolveReferences(1L, List.of(new RefRepoInput(11L, "release/6.5"), in(10)));
        assertThat(r).extracting(RefRepo::catalogId).containsExactly(11L, 10L);
        assertThat(r.get(0).sha()).isNull();
        // 별칭·경로(GitLab은 전체 경로)·URL·브랜치를 박제하고, 러너가 채우는 sha·fetchedAt·error는 비어 있다
        assertThat(r).containsExactly(
                new RefRepo(11L, "Netis6.5", "product/netis/web/package/netis-v6.5",
                        "https://gitlab.hamon.vip/product/netis/web/package/netis-v6.5.git", "release/6.5",
                        null, null, null),
                new RefRepo(10L, "공통 UI", "acme/ui", "https://github.com/acme/ui.git", "main",
                        null, null, null));
    }

    @Test
    void resolveReferences_accepts_exactly_three() {
        stubRefCatalog();
        assertThat(service.resolveReferences(1L, List.of(in(10), in(11), new RefRepoInput(12L, "master"))))
                .extracting(RefRepo::catalogId).containsExactly(10L, 11L, 12L);
    }

    @Test
    void resolveReferences_ignores_null_elements() {
        stubRefCatalog();
        // 널 원소는 세지도 검사하지도 않는다 — 널 3개가 끼어 있어도 실제 3개는 상한 안이다
        List<RefRepoInput> withNulls = Arrays.asList(null, in(10), null, in(11), null, new RefRepoInput(12L, "master"));
        assertThat(service.resolveReferences(1L, withNulls))
                .extracting(RefRepo::catalogId).containsExactly(10L, 11L, 12L);
        assertThat(service.resolveReferences(1L, Arrays.asList(null, null))).isEmpty();
    }

    @Test
    void checkRefShape_is_static_and_never_touches_the_catalog() {
        RepoCatalogService.checkRefShape(1L, null);
        RepoCatalogService.checkRefShape(1L, List.of());
        RepoCatalogService.checkRefShape(1L, List.of(in(10), in(11), in(12)));
        RepoCatalogService.checkRefShape(1L, Arrays.asList(null, in(10), null));
        verifyNoInteractions(repo);
        assertThat(RepoCatalogService.MAX_REF_REPOS).isEqualTo(3);
    }

    @Test
    void checkRefShape_checks_cap_then_target_then_duplicates() {
        // 상한이 먼저 — 대상이 끼어 있어도 4개면 상한 문구
        assertBadRequest(() -> RepoCatalogService.checkRefShape(10L, List.of(in(10), in(11), in(12), in(13))),
                "참조 repo는 최대 3개까지 고를 수 있습니다");
        // 대상이 중복보다 먼저 — 대상을 두 번 골라도 대상 문구
        assertBadRequest(() -> RepoCatalogService.checkRefShape(10L, List.of(in(10), in(10))),
                "대상 레포는 참조로 고를 수 없습니다");
        assertBadRequest(() -> RepoCatalogService.checkRefShape(1L, List.of(in(11), in(10), in(11))),
                "같은 레포를 참조로 두 번 고를 수 없습니다");
    }

    @Test
    void checkRefShape_compares_catalog_ids_by_value() {
        // 박싱된 Long은 127을 넘으면 == 가 거짓이다 — 카탈로그 id가 커져도 대상·중복 검사가 새면 안 된다
        assertBadRequest(() -> RepoCatalogService.checkRefShape(1000L, List.of(in(1000))),
                "대상 레포는 참조로 고를 수 없습니다");
        assertBadRequest(() -> RepoCatalogService.checkRefShape(1L, List.of(in(2000), in(2000))),
                "같은 레포를 참조로 두 번 고를 수 없습니다");
    }

    @Test
    void checkRefShape_leaves_missing_ids_to_resolveForRegistration() {
        // id가 없는 항목은 같은 레포로 세지 않는다 — 대상 id가 null이어도 마찬가지
        RepoCatalogService.checkRefShape(null, List.of(new RefRepoInput(null, "a"), new RefRepoInput(null, "b")));
        assertBadRequest(() -> service.resolveReferences(1L, List.of(new RefRepoInput(null, "a"))),
                "repoCatalogId가 필요합니다");
    }
}
