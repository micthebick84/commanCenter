package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerTaskResponseDesignTest {

    private Task task() {
        Task t = Task.create("owner/repo", "main", "제목", "설명", "user1", 3, null, null, null);
        return t;
    }

    @Test
    void forDesign은_kind_DESIGN과_프로젝트_ID를_담는다() {
        WorkerTaskResponse r = WorkerTaskResponse.forDesign(task(), null, null, "ds-proj", "out-proj");
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DESIGN);
        assertThat(r.designSystemProjectId()).isEqualTo("ds-proj");
        assertThat(r.designOutputProjectId()).isEqualTo("out-proj");
        assertThat(r.feedbackHistoryJson()).isEqualTo("[]");
        assertThat(r.designMarkdown()).isNull();
    }

    @Test
    void designReview_팩토리는_필드를_채운다() {
        WorkerResultRequest r = WorkerResultRequest.designReview("w1", "# 디자인", "[]",
                "proj", "https://u", "log", 100L, null);
        assertThat(r.status()).isEqualTo(TaskStatus.DESIGN_REVIEW);
        assertThat(r.designMarkdown()).isEqualTo("# 디자인");
        assertThat(r.workerId()).isEqualTo("w1");
    }
}
