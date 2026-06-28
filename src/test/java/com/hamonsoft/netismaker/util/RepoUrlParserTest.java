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
}
