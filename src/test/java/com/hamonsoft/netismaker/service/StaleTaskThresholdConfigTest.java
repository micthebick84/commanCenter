package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배포중 멈춤 판정은 워커 상한(dockerfile 10분 + 미리 빌드 20분 + build 30분)보다 길어야 한다 —
 * 15분이면 미리 빌드가 도는 정상 배포를 stale로 되돌린다 (스펙 2026-10-28 §3).
 */
class StaleTaskThresholdConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void application_yml의_배포중_판정은_70분() throws IOException {
        try (InputStream in = StaleTaskThresholdConfigTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> task = (Map<String, Object>) ((Map<String, Object>) root.get("app")).get("task");
            assertThat(task.get("deploy-stale-threshold-minutes")).isEqualTo(70);
        }
    }
}
