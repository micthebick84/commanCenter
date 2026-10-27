package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StackSecretEnvTest {

    @Test
    void 스택_비밀은_대소문자_구분_없이_빠지고_나머지는_남는다() {
        Map<String, String> env = new HashMap<>(Map.of(
                "GITLAB_TOKEN", "glpat-x", "worker_api_key", "k", "Spring_Datasource_Password", "pw",
                "NETISMAKER_DB_SECRET_KEY", "key", "PATH", "C:/bin", "CLAUDE_CODE_OAUTH_TOKEN", "keep",
                "NPM_TOKEN", "keep", "DOCKER_HOST", "ssh://x"));

        StackSecretEnv.strip(env);

        assertThat(env).containsOnlyKeys("PATH", "CLAUDE_CODE_OAUTH_TOKEN", "NPM_TOKEN", "DOCKER_HOST");
    }

    @Test
    void 목록은_인터뷰_서비스_childEnv_ts와_같다() {
        assertThat(StackSecretEnv.NAMES).containsExactly(
                "WORKER_API_KEY", "SPRING_DATASOURCE_PASSWORD", "GITLAB_TOKEN", "GITHUB_PAT", "GH_TOKEN", "GITHUB_TOKEN",
                "CLOUDFLARE_API_TOKEN", "NETISMAKER_DB_SECRET_KEY", "NETISMAKER_DB_SECRET_SALT", "MAIL_PASSWORD",
                "PGPASSWORD");
    }
}
