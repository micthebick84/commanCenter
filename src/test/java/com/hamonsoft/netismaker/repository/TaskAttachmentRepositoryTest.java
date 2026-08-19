package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskAttachmentRepositoryTest {

    @Autowired private TaskAttachmentRepository attachmentRepo;
    @Autowired private TaskRepository taskRepo;

    @BeforeEach void clean() {
        attachmentRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task savedTask() {
        Task t = Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                new ArrayList<>(), null, null);
        return taskRepo.save(t);
    }

    @Test
    void 저장_후_taskId로_순서대로_조회된다() {
        Task t = savedTask();
        attachmentRepo.save(TaskAttachment.create(t.getId(), "요구사항.pdf",
                "task-" + t.getId() + "/1-요구사항.pdf", "application/pdf", 1234L, "user1"));
        attachmentRepo.save(TaskAttachment.create(t.getId(), "화면.png",
                "task-" + t.getId() + "/2-화면.png", "image/png", 99L, "user1"));

        List<TaskAttachment> found = attachmentRepo.findByTaskIdOrderByIdAsc(t.getId());

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getOriginalFilename()).isEqualTo("요구사항.pdf");
        assertThat(found.get(0).getStoredPath()).startsWith("task-" + t.getId() + "/1-");
        assertThat(found.get(1).getSizeBytes()).isEqualTo(99L);
        assertThat(found.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void contentType은_null을_허용한다() {
        Task t = savedTask();
        TaskAttachment saved = attachmentRepo.save(TaskAttachment.create(
                t.getId(), "raw.bin", "task-" + t.getId() + "/1-raw.bin", null, 1L, "user1"));
        assertThat(attachmentRepo.findById(saved.getId()).orElseThrow().getContentType()).isNull();
    }
}
