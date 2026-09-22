package com.hamonsoft.netismaker.git;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitRemotesTest {

    private static final RepoRef GH = RepoRef.fromSnapshot("acme/widgets", "https://github.com/acme/widgets.git");
    private static final RepoRef GL = RepoRef.fromSnapshot("product/netis/web/package/netis-v7.0",
            "https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");

    @Test
    void fromSnapshot_without_git_url_is_legacy_github() {
        RepoRef r = RepoRef.fromSnapshot("acme/widgets", null);
        assertThat(r.host()).isEqualTo("github");
        assertThat(r.gitUrl()).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(RepoRef.fromSnapshot("acme/widgets", "  ").host()).isEqualTo("github");
    }

    @Test
    void fromSnapshot_derives_host_from_git_url() {
        assertThat(GH.host()).isEqualTo("github");
        assertThat(GH.isGitlab()).isFalse();
        assertThat(GL.host()).isEqualTo("gitlab");
        assertThat(GL.isGitlab()).isTrue();
        assertThat(GL.apiBase()).isEqualTo("https://gitlab.hamon.vip");
        assertThat(RepoRef.fromSnapshot("g/p", "http://localhost:8929/g/p.git").apiBase())
                .isEqualTo("http://localhost:8929");
    }

    @Test
    void authenticatedUrl_injects_the_token_of_the_matching_host() {
        GitRemotes r = new GitRemotes("ghp_AAA", "glpat-BBB");
        assertThat(r.authenticatedUrl(GH)).isEqualTo("https://oauth2:ghp_AAA@github.com/acme/widgets.git");
        assertThat(r.authenticatedUrl(GL))
                .isEqualTo("https://oauth2:glpat-BBB@gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
    }

    @Test
    void authenticatedUrl_without_token_is_the_plain_url() {
        GitRemotes r = new GitRemotes("", null);
        assertThat(r.authenticatedUrl(GH)).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(r.authenticatedUrl(GL)).isEqualTo(GL.gitUrl());
        assertThat(r.tokenFor(GL)).isNull();
    }

    @Test
    void token_with_regex_special_chars_is_inserted_literally() {
        GitRemotes r = new GitRemotes(null, "a$1b\\c");
        assertThat(r.authenticatedUrl(GL)).startsWith("https://oauth2:a$1b\\c@gitlab.hamon.vip/");
    }

    @Test
    void localKey_keeps_github_as_is_and_flattens_gitlab_to_two_levels() {
        assertThat(GitRemotes.localKey(GH)).isEqualTo("acme/widgets");
        assertThat(GitRemotes.localKey(GL)).isEqualTo("_gitlab/product+netis+web+package+netis-v7.0");
    }

    @Test
    void mask_hides_credentials_in_urls() {
        assertThat(GitRemotes.mask("git clone https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git x"))
                .isEqualTo("git clone https://***@gitlab.hamon.vip/g/p.git x")
                .doesNotContain("SECRET");
        assertThat(GitRemotes.mask("a https://u:p1@h/x b https://u:p2@h/y"))
                .doesNotContain("p1").doesNotContain("p2");
        assertThat(GitRemotes.mask("no creds https://github.com/a/b.git")).isEqualTo("no creds https://github.com/a/b.git");
        assertThat(GitRemotes.mask(null)).isNull();
    }
}
