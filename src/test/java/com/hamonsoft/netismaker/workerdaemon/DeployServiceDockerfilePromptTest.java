package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dockerfile 생성 프롬프트는 빌드·실행을 금지한다(2026-10-08 작업 9): 생성 claude가 검증하려고 `docker build`를
 * 직접 돌려(워커의 DOCKER_HOST로 원격 빌드) 10분 timeout에 걸렸다. 규칙은 코드에서 덧붙인다 — 운영자가 yml
 * 템플릿을 바꿔도 빠지지 않게(배포 env 목록 지시와 같은 방식).
 */
class DeployServiceDockerfilePromptTest {

    @SuppressWarnings("unchecked")
    private static String workerYmlTemplate() {
        try (InputStream in = DeployServiceDockerfilePromptTest.class.getResourceAsStream("/application-worker.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> worker = (Map<String, Object>) ((Map<String, Object>) root.get("netis-maker")).get("worker");
            Map<String, Object> deploy = (Map<String, Object>) worker.get("deploy");
            return (String) deploy.get("dockerfile-prompt-template");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void the_live_and_default_templates_both_end_with_the_no_build_rule() {
        for (String tpl : Arrays.asList(workerYmlTemplate(), null, "")) {
            String prompt = DeployService.buildDockerfilePrompt(tpl, "acme/widgets", "netismaker/task-9");

            assertThat(prompt).endsWith(DeployService.DOCKERFILE_NO_BUILD_RULE);
            assertThat(prompt).contains("Dockerfile");
            assertThat(prompt).doesNotContain("{github_repo}").doesNotContain("{branch}");
        }
        assertThat(DeployService.DOCKERFILE_NO_BUILD_RULE)
                .contains("docker build").contains("docker run").contains("워커가 빌드");
    }

    @Test
    void the_rule_forbids_buildkit_only_syntax_for_the_legacy_builder() {
        // 2026-10-08 작업 9 3차 재배포: 검증 빌드를 막자 claude가 빌드 환경을 모른 채 `RUN --mount=type=cache`를 써,
        // buildx 없는 워커의 구형 빌더가 "the --mount option requires BuildKit"으로 실패했다.
        String rule = DeployService.DOCKERFILE_NO_BUILD_RULE;

        assertThat(rule).contains("BuildKit");
        assertThat(rule).contains("RUN --mount").contains("COPY --link").contains("--chmod").contains("heredoc");
    }

    @Test
    void placeholders_are_filled() {
        String prompt = DeployService.buildDockerfilePrompt(workerYmlTemplate(), "acme/widgets", "netismaker/task-9");
        assertThat(prompt).contains("acme/widgets").contains("netismaker/task-9");
    }
}
