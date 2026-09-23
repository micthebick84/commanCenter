package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRouteTest {

    @Test
    void slug_uses_task_id() {
        assertThat(PublicRoute.slug(7)).isEqualTo("task-7");
    }

    @Test
    void publicUrl_is_https_subdomain() {
        assertThat(PublicRoute.publicUrl(7, "micthebick.dev"))
                .isEqualTo("https://task-7.micthebick.dev");
    }

    @Test
    void dockerLabels_emit_enable_router_and_service() {
        List<String> labels = PublicRoute.dockerLabels(7, 8080, "micthebick.dev");
        assertThat(labels).containsExactly(
                "traefik.enable=true",
                "traefik.http.routers.task-7.rule=Host(`task-7.micthebick.dev`)",
                "traefik.http.services.task-7.loadbalancer.server.port=8080");
    }

    @Test
    void dockerLabels_use_parsed_container_port() {
        List<String> labels = PublicRoute.dockerLabels(3, 3000, "micthebick.dev");
        assertThat(labels.get(2))
                .isEqualTo("traefik.http.services.task-3.loadbalancer.server.port=3000");
    }

    @Test
    void suffix_is_appended_to_slug_host_and_url() {
        assertThat(PublicRoute.slug(7, "-win")).isEqualTo("task-7-win");
        assertThat(PublicRoute.hostname(7, "micthebick.dev", "-win")).isEqualTo("task-7-win.micthebick.dev");
        assertThat(PublicRoute.publicUrl(7, "micthebick.dev", "-win")).isEqualTo("https://task-7-win.micthebick.dev");
    }

    @Test
    void dockerLabels_with_suffix_use_suffixed_router_service_and_host() {
        assertThat(PublicRoute.dockerLabels(7, 8080, "micthebick.dev", "-win")).containsExactly(
                "traefik.enable=true",
                "traefik.http.routers.task-7-win.rule=Host(`task-7-win.micthebick.dev`)",
                "traefik.http.services.task-7-win.loadbalancer.server.port=8080");
    }

    @Test
    void empty_or_null_suffix_matches_legacy_output() {
        assertThat(PublicRoute.dockerLabels(7, 8080, "micthebick.dev", ""))
                .isEqualTo(PublicRoute.dockerLabels(7, 8080, "micthebick.dev"));
        assertThat(PublicRoute.publicUrl(7, "micthebick.dev", null)).isEqualTo("https://task-7.micthebick.dev");
    }

    @Test
    void taskIdOf_parses_only_matching_hostnames() {
        assertThat(PublicRoute.taskIdOf("task-42-win.micthebick.dev", "micthebick.dev", "-win"))
                .isEqualTo(OptionalLong.of(42));
        assertThat(PublicRoute.taskIdOf("TASK-42-WIN.micthebick.dev", "micthebick.dev", "-win"))
                .isEqualTo(OptionalLong.of(42));                                     // DNS는 대소문자 무시
        assertThat(PublicRoute.taskIdOf("task-42.micthebick.dev", "micthebick.dev", "-win")).isEmpty(); // 맥 스택 이름
        assertThat(PublicRoute.taskIdOf("app-win.micthebick.dev", "micthebick.dev", "-win")).isEmpty();
        assertThat(PublicRoute.taskIdOf("task-42-win.other.dev", "micthebick.dev", "-win")).isEmpty();
        assertThat(PublicRoute.taskIdOf("task-42-winx.micthebick.dev", "micthebick.dev", "-win")).isEmpty();
        assertThat(PublicRoute.taskIdOf(null, "micthebick.dev", "-win")).isEmpty();
    }
}
