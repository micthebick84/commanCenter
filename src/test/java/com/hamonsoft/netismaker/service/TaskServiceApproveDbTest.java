package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 승인 때 DB 연결 선택 (스펙 2026-10-14 §4) — 검증 기준은 작업 등록자, 세션 생성 전에 저장. */
class TaskServiceApproveDbTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final InterviewService interviewService = mock(InterviewService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final McpCatalogService mcpCatalog = mock(McpCatalogService.class);
    private TaskService service;
    private Task task;

    @BeforeEach
    void setUp() {
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(TaskStatusHistoryRepository.class), mcpCatalog, mock(RepoCatalogService.class),
                new ObjectMapper(), interviewService, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class), db,
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class), mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.hamonsoft.netismaker.repository.QuestionAttachmentRepository.class), 200);
        task = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(task, "id", 5L);
        task.setStatus(TaskStatus.AWAITING_APPROVAL);
        task.setRepoCatalogId(9L);
        when(taskRepo.findActiveByIdForUpdate(5L)).thenReturn(Optional.of(task));
        when(mcpCatalog.resolveExtras(any())).thenReturn(List.of());
    }

    @Test
    void approve_validates_against_requester_and_saves_before_session() {
        when(db.validateSelection(9L, "user1", List.of(3L, 4L))).thenReturn(List.of(3L, 4L));

        service.approve(5L, "admin", new ApproveRequest(List.of(), null, null, List.of(3L, 4L)));

        assertThat(task.getDbConnectionIds()).containsExactly(3L, 4L);
        InOrder o = inOrder(db, interviewService);
        o.verify(db).validateSelection(9L, "user1", List.of(3L, 4L));
        o.verify(interviewService).createForTask(eq(task), anyString(), anyString(), anyList());
    }

    @Test
    void invalid_selection_is_400_and_no_session() {
        when(db.validateSelection(anyLong(), anyString(), anyList()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: 7"));

        assertThatThrownBy(() -> service.approve(5L, "admin", new ApproveRequest(List.of(), null, null, List.of(7L))))
                .isInstanceOf(TaskException.class);
        verify(interviewService, never()).createForTask(any(), any(), any(), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void legacy_three_arg_request_means_no_db() {
        when(db.validateSelection(9L, "user1", null)).thenReturn(new java.util.ArrayList<>());
        service.approve(5L, "admin", new ApproveRequest(List.of(), null, null));
        assertThat(task.getDbConnectionIds()).isEmpty();
    }
}
