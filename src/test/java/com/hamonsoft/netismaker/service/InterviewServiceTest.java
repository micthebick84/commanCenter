package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InterviewServiceTest {

    InterviewSessionRepository sessionRepo;
    InterviewTurnRepository turnRepo;
    InterviewPlanRepository planRepo;
    McpCatalogService mcpCatalogService;
    TaskRepository taskRepo;
    TaskAnalysisRepository analysisRepo;
    TaskStatusHistoryRepository historyRepo;
    InterviewService service;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(InterviewSessionRepository.class);
        turnRepo = mock(InterviewTurnRepository.class);
        planRepo = mock(InterviewPlanRepository.class);
        mcpCatalogService = mock(McpCatalogService.class);
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new InterviewService(sessionRepo, turnRepo, planRepo,
                mcpCatalogService, taskRepo, analysisRepo, historyRepo);
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 3);
        ReflectionTestUtils.setField(service, "maxRetry", 3);
        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(turnRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(planRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 999L);
            return t;
        });
        when(analysisRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private CreateInterviewRequest req() {
        return new CreateInterviewRequest("owner/repo", "main", "제목", "기능 요구", List.of());
    }

    @Test
    void create_persists_queued_session() {
        when(sessionRepo.countActiveByRequester("u1")).thenReturn(0L);
        when(mcpCatalogService.resolveByIds(any())).thenReturn(List.of());
        InterviewSession s = service.create(req(), "u1");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getRequesterId()).isEqualTo("u1");
        verify(sessionRepo).save(any());
    }

    @Test
    void create_over_limit_throws_too_many() {
        when(sessionRepo.countActiveByRequester("u1")).thenReturn(3L);
        assertThatThrownBy(() -> service.create(req(), "u1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("한도");
    }
}
