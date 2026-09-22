package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 워커가 API로 올리는 자유 텍스트는 보고 경계에서 전부 마스킹된다.
 * (log_/claude 로그·배포 로그·분석/디자인 마크다운 모두 작업 상세 화면에 그대로 렌더된다.)
 */
class WorkerResultRequestMaskTest {

    private static final String DIRTY =
            "fatal: Authentication failed for 'https://oauth2:glpat-X@gitlab.hamon.vip/g/p.git'";
    private static final String CLEAN =
            "fatal: Authentication failed for 'https://***@gitlab.hamon.vip/g/p.git'";

    @Test
    void analysis_result_masks_markdown_subtasks_log_and_reason() {
        var masked = new WorkerResultRequest("w1", TaskStatus.COMPLETED,
                DIRTY, DIRTY, DIRTY, 10L, DIRTY,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null).masked();

        assertThat(masked.markdownResult()).isEqualTo(CLEAN);
        assertThat(masked.subtasksJson()).isEqualTo(CLEAN);
        assertThat(masked.claudeLog()).isEqualTo(CLEAN);
        assertThat(masked.failureReason()).isEqualTo(CLEAN);
        assertThat(masked.durationMs()).isEqualTo(10L);
    }

    @Test
    void implementation_result_masks_the_log_but_keeps_identifiers_and_pr_url() {
        var masked = new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/7", 7, "netismaker/task-1", "abc123", DIRTY,
                null, null, null, null, null,
                null, null, null, null, null).masked();

        assertThat(masked.implementationLog()).isEqualTo(CLEAN);
        assertThat(masked.prUrl()).isEqualTo("https://github.com/acme/widgets/pull/7");
        assertThat(masked.prNumber()).isEqualTo(7);
        assertThat(masked.headBranch()).isEqualTo("netismaker/task-1");
        assertThat(masked.headSha()).isEqualTo("abc123");
        assertThat(masked.workerId()).isEqualTo("w1");
        assertThat(masked.status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void deployed_masks_the_deploy_log_but_keeps_deploy_url_and_container_fields() {
        var masked = WorkerResultRequest
                .deployed("w1", "https://task-1.micthebick.dev", "c0ffee", 19001, "netis-task-1:latest", 5L, DIRTY)
                .masked();

        assertThat(masked.deployLog()).isEqualTo(CLEAN);
        assertThat(masked.deployUrl()).isEqualTo("https://task-1.micthebick.dev");
        assertThat(masked.deployContainerId()).isEqualTo("c0ffee");
        assertThat(masked.deployHostPort()).isEqualTo(19001);
        assertThat(masked.deployImage()).isEqualTo("netis-task-1:latest");
    }

    @Test
    void deploy_failed_masks_reason_and_log() {
        var masked = WorkerResultRequest.deployFailed("w1", DIRTY, DIRTY).masked();

        assertThat(masked.failureReason()).isEqualTo(CLEAN);
        assertThat(masked.deployLog()).isEqualTo(CLEAN);
    }

    @Test
    void undeployed_masks_the_undeploy_log() {
        var masked = WorkerResultRequest.undeployed("w1", DIRTY).masked();

        assertThat(masked.deployLog()).isEqualTo(CLEAN);
        assertThat(masked.status()).isEqualTo(TaskStatus.PR_CREATED);
    }

    @Test
    void design_review_masks_markdown_mockups_and_log_but_keeps_design_url_and_project_id() {
        var usage = new WorkerResultRequest.UsageReport(new java.math.BigDecimal("1.25"), 1L, 2L, 3L, 4L);
        var masked = WorkerResultRequest.designReview("w1", DIRTY, DIRTY, "proj-1",
                "https://claude.ai/design/proj-1", DIRTY, 7L, usage).masked();

        assertThat(masked.designMarkdown()).isEqualTo(CLEAN);
        assertThat(masked.mockupFilesJson()).isEqualTo(CLEAN);
        assertThat(masked.claudeLog()).isEqualTo(CLEAN);
        assertThat(masked.designUrl()).isEqualTo("https://claude.ai/design/proj-1");
        assertThat(masked.designProjectId()).isEqualTo("proj-1");
        assertThat(masked.usage()).isSameAs(usage);
    }

    @Test
    void design_failed_masks_reason_and_log() {
        var masked = WorkerResultRequest.designFailed("w1", DIRTY, DIRTY, null).masked();

        assertThat(masked.failureReason()).isEqualTo(CLEAN);
        assertThat(masked.claudeLog()).isEqualTo(CLEAN);
        assertThat(masked.usage()).isNull();
    }

    @Test
    void null_text_fields_stay_null() {
        var masked = new WorkerResultRequest("w1", TaskStatus.FAILED,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null).masked();

        assertThat(masked.markdownResult()).isNull();
        assertThat(masked.subtasksJson()).isNull();
        assertThat(masked.claudeLog()).isNull();
        assertThat(masked.failureReason()).isNull();
        assertThat(masked.implementationLog()).isNull();
        assertThat(masked.deployLog()).isNull();
        assertThat(masked.designMarkdown()).isNull();
        assertThat(masked.mockupFilesJson()).isNull();
        assertThat(masked.durationMs()).isNull();
        assertThat(masked.usage()).isNull();
    }

    @Test
    void a_payload_without_credentials_is_value_equal_after_masking() {
        var clean = new WorkerResultRequest("w1", TaskStatus.COMPLETED,
                "## 분석\n평범한 마크다운", "[]", "claude log", 10L, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);

        assertThat(clean.masked()).isEqualTo(clean);
    }
}
