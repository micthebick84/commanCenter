package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DEPLOY claim의 deployOverrides 칸 (스펙 2026-11-24 §7.3). */
class WorkerTaskResponseDeployOverridesTest {

    private static Task task() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        return t;
    }

    @Test
    void 기존_팩토리는_null() {
        assertThat(WorkerTaskResponse.forDeploy(task(), null).deployOverrides()).isNull();
    }

    @Test
    void 서른여섯_인자_생성자는_null() {
        WorkerTaskResponse r = WorkerTaskResponse.forClose(task(), null);
        WorkerTaskResponse viaThirtySix = new WorkerTaskResponse(r.id(), r.githubRepo(), r.githubBranch(), r.title(),
                r.description(), r.kind(), r.mcpsExtra(), r.analysisMarkdown(), r.subtasksJson(), r.headBranch(),
                r.headSha(), r.envVars(), r.model(), r.effort(), r.designMarkdown(), r.mockupFilesJson(),
                r.feedbackHistoryJson(), r.designUrl(), r.designSystemProjectId(), r.designOutputProjectId(),
                r.gitUrl(), r.repoHost(), r.planMarkdown(), r.prNumber(), r.prUrl(), r.closeProgress(),
                r.closeContext(), r.closeArtifact(), r.dbMcp(), r.implementation(), r.refRepos(), r.prebuild(),
                r.interviewMockups(), r.excludedMockupNumbers(), r.closePatch(), r.closeKeepBranch());

        assertThat(viaThirtySix.deployOverrides()).isNull();
        assertThat(viaThirtySix).isEqualTo(r);
    }

    @Test
    void withDeployOverrides가_나머지_칸을_보존() {
        Task t = task();
        t.setCloseKeepBranch(true);
        WorkerTaskResponse base = WorkerTaskResponse.forClose(t, null);
        DeployOverrides o = new DeployOverrides(List.of(new EnvVar("A", "1", false)), List.of(), "요약", null);

        WorkerTaskResponse withO = base.withDeployOverrides(o);

        assertThat(withO.deployOverrides()).isEqualTo(o);
        assertThat(withO.closeKeepBranch()).isTrue();
        assertThat(withO.withDeployOverrides(null)).isEqualTo(base);
    }
}
