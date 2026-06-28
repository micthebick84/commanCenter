package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
