package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoUrlParserTest {

    @Test
    void parses_https_url_with_git_suffix() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://github.com/micthebick84/netis7.0.git");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/micthebick84/netis7.0.git");
    }

    @Test
    void parses_https_url_without_git_suffix() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://github.com/owner/repo");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void parses_scp_style_ssh_url() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("git@github.com:owner/repo.git");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void parses_bare_owner_repo() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("owner/repo");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void trims_whitespace_and_trailing_slash() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("  https://github.com/owner/repo/  ");
        assertThat(p.ownerRepo()).isEqualTo("owner/repo");
        assertThat(p.host()).isEqualTo("github");
        assertThat(p.canonicalUrl()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void non_github_https_url_kept_as_other_with_null_owner_repo() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://gitlab.com/group/sub/proj.git");
        assertThat(p.host()).isEqualTo("other");
        assertThat(p.ownerRepo()).isNull();
        assertThat(p.canonicalUrl()).isEqualTo("https://gitlab.com/group/sub/proj.git");
    }

    @Test
    void blank_input_throws() {
        assertThatThrownBy(() -> RepoUrlParser.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void garbage_input_throws() {
        assertThatThrownBy(() -> RepoUrlParser.parse("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final String GL = "https://gitlab.hamon.vip";

    @Test
    void gitlab_https_url_with_nested_groups() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse(
                "https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git", GL);
        assertThat(p.host()).isEqualTo("gitlab");
        assertThat(p.ownerRepo()).isEqualTo("product/netis/web/package/netis-v7.0");
        assertThat(p.canonicalUrl())
                .isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
    }

    @Test
    void gitlab_url_without_dot_git_and_with_trailing_slash_is_normalized() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://gitlab.hamon.vip/group/proj/", GL);
        assertThat(p.host()).isEqualTo("gitlab");
        assertThat(p.ownerRepo()).isEqualTo("group/proj");
        assertThat(p.canonicalUrl()).isEqualTo("https://gitlab.hamon.vip/group/proj.git");
    }

    @Test
    void gitlab_scp_and_ssh_urls_are_canonicalized_to_base_url() {
        assertThat(RepoUrlParser.parse("git@gitlab.hamon.vip:group/sub/proj.git", GL).canonicalUrl())
                .isEqualTo("https://gitlab.hamon.vip/group/sub/proj.git");
        assertThat(RepoUrlParser.parse("ssh://git@gitlab.hamon.vip:2222/group/sub/proj.git", GL).ownerRepo())
                .isEqualTo("group/sub/proj");
    }

    @Test
    void gitlab_host_match_is_case_insensitive_and_base_trailing_slash_ignored() {
        RepoUrlParser.Parsed p = RepoUrlParser.parse("https://GitLab.Hamon.VIP/group/proj.git",
                "https://gitlab.hamon.vip/");
        assertThat(p.host()).isEqualTo("gitlab");
        assertThat(p.canonicalUrl()).isEqualTo("https://gitlab.hamon.vip/group/proj.git");
    }

    @Test
    void other_gitlab_host_stays_other_when_base_url_differs_or_is_blank() {
        assertThat(RepoUrlParser.parse("https://gitlab.com/group/sub/proj.git", GL).host()).isEqualTo("other");
        assertThat(RepoUrlParser.parse("https://gitlab.hamon.vip/group/proj.git", "").host()).isEqualTo("other");
        assertThat(RepoUrlParser.parse("https://gitlab.hamon.vip/group/proj.git").host()).isEqualTo("other");
    }

    @Test
    void gitlab_single_segment_or_bad_segment_is_rejected() {
        assertThatThrownBy(() -> RepoUrlParser.parse("https://gitlab.hamon.vip/onlyone.git", GL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepoUrlParser.parse("https://gitlab.hamon.vip/group/../proj.git", GL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepoUrlParser.parse("https://gitlab.hamon.vip/group/pr+oj.git", GL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void github_inputs_are_unaffected_by_gitlab_base_url() {
        assertThat(RepoUrlParser.parse("owner/repo", GL).host()).isEqualTo("github");
        assertThat(RepoUrlParser.parse("https://github.com/owner/repo.git", GL).canonicalUrl())
                .isEqualTo("https://github.com/owner/repo.git");
    }
}
