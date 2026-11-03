package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskAttachmentRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStageUsageRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수정 회차 동안 소프트 삭제 금지 (스펙 2026-11-03 §4·§8). 회차가 열린 동안은 이전에 배포된 컨테이너를
 * 사용자가 계속 보고 있으므로(GC 보호 목록과 같은 이유) 작업을 삭제하면 컨테이너가 추적 불가 고아가 된다.
 */
class TaskServiceFixRoundGuardTest {

    private TaskRepository taskRepo;
    private TaskService service;

    private Task taskWithStatus(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), mock(RepoCatalogService.class), new ObjectMapper(),
                mock(InterviewService.class), mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class), mock(DbConnectionService.class),
                mock(TikaExtractionService.class), mock(com.hamonsoft.netismaker.service.render.RenderTargetPolicy.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.hamonsoft.netismaker.repository.QuestionAttachmentRepository.class), 200);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void 수정승인대기는_삭제_409() {
        Task t = taskWithStatus(TaskStatus.FIX_REVIEW);
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.softDelete(42L, "admin", true))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("수정");
        assertThat(t.getDeletedAt()).isNull();
    }

    @Test
    void 열린_회차의_구현실패는_삭제_409() {
        // 회차 구현이 실패해도(구현실패) 회차는 아직 열려 있고 이전 배포 컨테이너가 살아 있다.
        Task t = taskWithStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setActiveFixRequestId(7L);
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.softDelete(42L, "admin", true))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("수정 회차 진행 중에는 삭제할 수 없습니다");
        assertThat(t.getDeletedAt()).isNull();
    }

    @Test
    void 회차없는_구현실패는_지금처럼_삭제된다() {
        Task t = taskWithStatus(TaskStatus.IMPLEMENTATION_FAILED);
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));

        service.softDelete(42L, "admin", true);

        assertThat(t.getDeletedAt()).isNotNull();
    }
}
