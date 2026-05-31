package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.ContainerInfo;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.GcPlan;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.ImageInfo;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerGcPlannerTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-05-31T12:00:00Z");

    @Test
    void running_container_and_its_image_are_kept() {
        var containers = List.of(new ContainerInfo("netis-task-7", "running", now, "netis-task-7:aaa111"));
        var images = List.of(new ImageInfo("netis-task-7:aaa111", "img1")); // 최신순
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1);
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }

    @Test
    void stopped_orphan_past_grace_is_removed() {
        var containers = List.of(
                new ContainerInfo("netis-task-7", "exited", now.minusMinutes(120), "netis-task-7:aaa111"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1);
        assertThat(plan.containersToRemove()).containsExactly("netis-task-7");
    }

    @Test
    void stopped_orphan_within_grace_is_kept() {
        var containers = List.of(
                new ContainerInfo("netis-task-7", "exited", now.minusMinutes(10), "netis-task-7:aaa111"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1);
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
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1);
        assertThat(plan.imagesToRemove()).containsExactlyInAnyOrder("netis-task-7:bbb222", "netis-task-7:aaa111");
    }

    @Test
    void non_owned_resources_are_ignored() {
        var containers = List.of(new ContainerInfo("postgres", "exited", now.minusDays(10), "postgres:16"));
        var images = List.of(new ImageInfo("redis:7", "ix"));
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1);
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }
}
