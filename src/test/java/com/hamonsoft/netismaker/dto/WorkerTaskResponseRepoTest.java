package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTaskResponseRepoTest {

    @Test
    void gitlab_task_carries_git_url_and_host_in_every_claim_kind() {
        Task t = Task.create("product/netis/web/package/netis-v7.0", "develop", "t", "d", "admin",
                3, List.of(), "claude-opus-5", "high");
        t.setGitUrl("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");

        for (WorkerTaskResponse r : new WorkerTaskResponse[]{
                WorkerTaskResponse.forAnalysis(t), WorkerTaskResponse.forImplementation(t, null, null),
                WorkerTaskResponse.forDeploy(t), WorkerTaskResponse.forUndeploy(t),
                WorkerTaskResponse.forDesign(t, null, null, null, null)}) {
            assertThat(r.gitUrl()).isEqualTo("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
            assertThat(r.repoHost()).isEqualTo("gitlab");
            assertThat(r.repoRef().isGitlab()).isTrue();
        }
    }

    @Test
    void legacy_task_without_git_url_is_github() {
        Task t = Task.create("acme/widgets", "main", "t", "d", "admin", 3, List.of(), "claude-opus-5", "high");
        WorkerTaskResponse r = WorkerTaskResponse.forAnalysis(t);
        assertThat(r.gitUrl()).isNull();
        assertThat(r.repoHost()).isEqualTo("github");
        assertThat(r.repoRef().gitUrl()).isEqualTo("https://github.com/acme/widgets.git");
    }

    @Test
    void worker_side_deserialization_without_new_fields_falls_back_to_github() {
        WorkerTaskResponse r = new WorkerTaskResponse(1L, "acme/widgets", "main", "t", "d",
                WorkerTaskResponse.Kind.ANALYSIS, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        assertThat(r.repoRef().host()).isEqualTo("github");
    }
}
