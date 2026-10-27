package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.RefRepo;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구현 claim의 참조 repo (스펙 2026-10-24 §4.7) — 고정 커밋을 싣고 폴더(dir)는 워커가 계산한다. */
class WorkerTaskResponseRefReposTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private final ObjectMapper lenient = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Task t = taskWithRefs();

    private static Task taskWithRefs() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setRefRepos(List.of(new RefRepo(11L, "옛앱", "g/old", "https://gitlab.hamon.vip/g/old.git", "release/6.5",
                SHA, "t", null)));
        return t;
    }

    @Test
    void implementation_claim_carries_pinned_refs_without_dirs() throws Exception {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(t, null, null, "# 플랜");

        assertThat(r.refRepos()).containsExactly(new WorkerTaskResponse.TaskRefRepo(11L, "옛앱", "g/old",
                "https://gitlab.hamon.vip/g/old.git", "gitlab", "release/6.5", SHA));
        assertThat(r.withDbMcp(new DbMcpGrant(null, List.of(), List.of())).refRepos()).isEqualTo(r.refRepos());
        assertThat(lenient.readValue(lenient.writeValueAsString(r), WorkerTaskResponse.class).refRepos())
                .isEqualTo(r.refRepos());
        // 폴더 경로는 워커 설정(worktree-root)으로 워커가 계산한다 — 페이로드에 싣지 않는다
        assertThat(lenient.writeValueAsString(r)).doesNotContain("\"dir\"");
    }

    @Test
    void a_github_ref_without_a_git_url_is_github() {
        t.setRefRepos(List.of(new RefRepo(12L, "공통 UI", "acme/ui", null, "main", SHA, "t", null)));

        assertThat(WorkerTaskResponse.forImplementation(t, null, null).refRepos())
                .extracting(WorkerTaskResponse.TaskRefRepo::repoHost).containsExactly("github");
    }

    @Test
    void other_kinds_old_payloads_and_the_30_arg_constructor_have_an_empty_list() throws Exception {
        assertThat(WorkerTaskResponse.forDeploy(t).refRepos()).isEmpty();
        assertThat(WorkerTaskResponse.forUndeploy(t).refRepos()).isEmpty();
        assertThat(WorkerTaskResponse.forDesign(t, null, null, null, null).refRepos()).isEmpty();
        assertThat(WorkerTaskResponse.forClose(t, null).refRepos()).isEmpty();
        assertThat(WorkerTaskResponse.forAnalysis(t).refRepos()).isEmpty();

        WorkerTaskResponse old = lenient.readValue(
                "{\"id\":7,\"githubRepo\":\"acme/widgets\",\"githubBranch\":\"main\",\"kind\":\"IMPLEMENTATION\"}",
                WorkerTaskResponse.class);
        assertThat(old.refRepos()).isNotNull().isEmpty();

        WorkerTaskResponse thirty = new WorkerTaskResponse(7L, "acme/widgets", "main", "T", "d",
                WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), "", "[]", null, null, List.of(), null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45));
        assertThat(thirty.refRepos()).isNotNull().isEmpty();
        assertThat(thirty.implementation()).isEqualTo(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45));
    }
}
