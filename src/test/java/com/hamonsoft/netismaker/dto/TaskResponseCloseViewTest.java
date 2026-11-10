package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 종료 응답의 부분 패치 칸 (스펙 2026-11-10). */
class TaskResponseCloseViewTest {

    private Task task(TaskStatus s) {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setStatus(s);
        return t;
    }

    @Test
    void 올린_패치가_있으면_patch를_보여준다() {
        Task t = task(TaskStatus.CLOSING);
        t.setClosePatchMode("REQUIRED");
        t.setPatchName("20261110_T.zip");
        t.setPatchSize(1234L);
        t.setPatchFileCount(5);
        t.setPatchEmpty(false);
        OffsetDateTime at = OffsetDateTime.now();
        t.setPatchUploadedAt(at);

        TaskResponse.CloseView v = TaskResponse.of(t, null).close();

        assertThat(v.patchMode()).isEqualTo("REQUIRED");
        assertThat(v.patch()).isEqualTo(new TaskResponse.PatchView("20261110_T.zip", 1234L, 5, false, at));
    }

    @Test
    void 변경_없음이면_빈_patch를_보여준다() {
        Task t = task(TaskStatus.CLOSING);
        t.setClosePatchMode("REQUIRED");
        t.setPatchEmpty(true);
        t.setPatchFileCount(0);

        TaskResponse.CloseView v = TaskResponse.of(t, null).close();

        assertThat(v.patch()).isNotNull();
        assertThat(v.patch().empty()).isTrue();
        assertThat(v.patch().name()).isNull();
        assertThat(v.patch().fileCount()).isEqualTo(0);
    }

    @Test
    void 패치_모드가_없으면_patchMode도_patch도_null() {
        TaskResponse.CloseView v = TaskResponse.of(task(TaskStatus.CLOSE_REVIEW), null).close();
        assertThat(v.patchMode()).isNull();
        assertThat(v.patch()).isNull();
    }

    @Test
    void 종료_상태가_아닌_작업은_패치_모드만_있으면_close가_없다() {
        Task t = task(TaskStatus.DEPLOYED);
        t.setClosePatchMode("REQUIRED");
        assertThat(TaskResponse.of(t, null).close()).isNull();
    }
}
