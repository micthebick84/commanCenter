package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskServiceRequesterEmailTest {

    private TaskService service;

    @BeforeEach
    void setUp() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        RepoCatalogService repoCatalogService = mock(RepoCatalogService.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), repoCatalogService, new ObjectMapper(),
                mock(InterviewService.class), mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class));
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(service, "maxRetry", 3);
        when(taskRepo.countActiveByRequester(any())).thenReturn(0L);
        when(repoCatalogService.resolveForRegistration(1L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(1L, "alias", "https://github.com/x/y.git", "github", "x/y", null));
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void 등록_때_JWT_메일_주소를_작업에_저장한다() {
        Task t = service.create(new TaskCreateRequest(1L, "main", "제목", "설명"), "user1", "user1@hamonsoft.co.kr");
        assertThat(t.getRequesterEmail()).isEqualTo("user1@hamonsoft.co.kr");
    }

    @Test
    void 기존_2인자_등록은_주소_없이_저장한다() {
        Task t = service.create(new TaskCreateRequest(1L, "main", "제목", "설명"), "user1");
        assertThat(t.getRequesterEmail()).isNull();
    }
}
