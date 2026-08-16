package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseAttachmentsTest {

    private Task task() {
        return Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                new ArrayList<>(), null, null);
    }

    @Test
    void 기존_오버로드는_빈_리스트를_기본값으로_가진다() {
        assertThat(TaskResponse.of(task(), null).attachments()).isEmpty();
        assertThat(TaskResponse.of(task(), null, null).attachments()).isEmpty();
        assertThat(TaskResponse.of(task(), null, null, null).attachments()).isEmpty();
    }

    @Test
    void canonical_of는_엔티티를_AttachmentView로_매핑한다() {
        TaskAttachment a = TaskAttachment.create(1L, "요구사항.pdf",
                "task-1/1-요구사항.pdf", "application/pdf", 1234L, "user1");
        TaskResponse r = TaskResponse.of(task(), null, null, null, List.of(a));
        assertThat(r.attachments()).hasSize(1);
        assertThat(r.attachments().get(0).fileName()).isEqualTo("요구사항.pdf");
        assertThat(r.attachments().get(0).sizeBytes()).isEqualTo(1234L);
        assertThat(r.attachments().get(0).contentType()).isEqualTo("application/pdf");
    }

    @Test
    void null_리스트를_넘겨도_빈_리스트로_정규화된다() {
        assertThat(TaskResponse.of(task(), null, null, null, null).attachments()).isEmpty();
    }
}
