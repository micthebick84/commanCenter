package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.ContainerInfo;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.GcPlan;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.ImageInfo;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DockerGcPlannerTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-05-31T12:00:00Z");

    @Test
    void running_container_and_its_image_are_kept() {
        var containers = List.of(new ContainerInfo("netis-task-7", "running", now, "netis-task-7:aaa111"));
        var images = List.of(new ImageInfo("netis-task-7:aaa111", "img1")); // 최신순
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1, Set.of());
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }

    @Test
    void stopped_orphan_past_grace_is_removed() {
        var containers = List.of(
                new ContainerInfo("netis-task-7", "exited", now.minusMinutes(120), "netis-task-7:aaa111"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1, Set.of());
        assertThat(plan.containersToRemove()).containsExactly("netis-task-7");
    }

    @Test
    void stopped_orphan_within_grace_is_kept() {
        var containers = List.of(
                new ContainerInfo("netis-task-7", "exited", now.minusMinutes(10), "netis-task-7:aaa111"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1, Set.of());
        assertThat(plan.containersToRemove()).isEmpty();
    }

    @Test
    void old_image_tags_beyond_keep_count_removed_but_in_use_kept() {
        // 실행 중 컨테이너가 aaa111 사용. 이미지 목록은 최신순(new→old).
        var containers = List.of(new ContainerInfo("netis-task-7", "running", now, "netis-task-7:ccc333"));
        var images = List.of(
                new ImageInfo("netis-task-7:ccc333", "i3"),  // 최신 + 사용중 → keep
                new ImageInfo("netis-task-7:bbb222", "i2"),  // keep 1개 한도 초과 → remove
                new ImageInfo("netis-task-7:aaa111", "i1")); // remove
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1, Set.of());
        assertThat(plan.imagesToRemove()).containsExactlyInAnyOrder("netis-task-7:bbb222", "netis-task-7:aaa111");
    }

    @Test
    void non_owned_resources_are_ignored() {
        var containers = List.of(new ContainerInfo("postgres", "exited", now.minusDays(10), "postgres:16"));
        var images = List.of(new ImageInfo("redis:7", "ix"));
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1, Set.of());
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }

    @Test
    void protected_stopped_container_and_its_image_survive_gc() {
        // DB가 배포완료/배포중단됨이라 믿는 컨테이너: 정지 + grace 초과여도 제거 금지,
        // 이미지도 보존해 docker start 복구가 가능해야 한다.
        var containers = List.of(
                new ContainerInfo("netis-task-13", "exited", now.minusDays(7), "netis-task-13:041caea"));
        var images = List.of(new ImageInfo("netis-task-13:041caea", "i1"));
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1, Set.of("netis-task-13"));
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }

    @Test
    void unprotected_stopped_sibling_is_still_removed_alongside_protected() {
        var containers = List.of(
                new ContainerInfo("netis-task-13", "exited", now.minusDays(7), "netis-task-13:aaa"),
                new ContainerInfo("netis-task-9", "exited", now.minusDays(7), "netis-task-9:bbb"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1, Set.of("netis-task-13"));
        assertThat(plan.containersToRemove()).containsExactly("netis-task-9");
    }

    @Test
    void restarting_container_is_treated_as_active() {
        // --restart 정책의 재시작 백오프 중(restarting)인 컨테이너는 docker가 살리는 중 — 제거 금지
        var containers = List.of(
                new ContainerInfo("netis-task-7", "restarting", now.minusMinutes(120), "netis-task-7:aaa111"));
        var images = List.of(new ImageInfo("netis-task-7:aaa111", "i1"));
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1, Set.of());
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }

    @Test
    void null_protected_set_behaves_as_empty() {
        var containers = List.of(
                new ContainerInfo("netis-task-7", "exited", now.minusMinutes(120), "netis-task-7:aaa111"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1, null);
        assertThat(plan.containersToRemove()).containsExactly("netis-task-7");
    }
}
