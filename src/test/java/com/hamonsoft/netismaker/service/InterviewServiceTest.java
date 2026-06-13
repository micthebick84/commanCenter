package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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

    private InterviewSession session(long id, InterviewStatus status) {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1", List.of());
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(status);
        return s;
    }

    @Test
    void claim_moves_queued_to_running_and_sets_worker_and_assigns_work_dir() {
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
        Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w1");
        assertThat(resp).isPresent();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.RUNNING);
        assertThat(s.getWorkerId()).isEqualTo("w1");
        assertThat(s.getClaimedAt()).isNotNull();
        // 첫 claim → 결정적 work_dir 배정 + 응답에 포함
        assertThat(s.getWorkDir()).endsWith("/netis-maker/interviews/owner/repo/session-1");
        assertThat(resp.get().workDir()).isEqualTo(s.getWorkDir());
        assertThat(resp.get().sessionId()).isEqualTo(1L);
    }

    @Test
    void claim_resume_keeps_existing_work_dir() {
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        s.setWorkDir("/preset/path/session-1");
        when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
        Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w2");
        assertThat(s.getWorkDir()).isEqualTo("/preset/path/session-1");   // resume → 불변
        assertThat(resp.get().workDir()).isEqualTo("/preset/path/session-1");
    }

    @Test
    void claim_empty_queue_returns_empty() {
        when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of());
        assertThat(service.claim("w1")).isEmpty();
    }

    @Test
    void recordQuestion_moves_running_to_awaiting_input_and_releases_worker() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findActiveById(2L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(2L)).thenReturn(null);
        service.recordQuestion(2L, "w1", new com.hamonsoft.netismaker.dto.WorkerQuestionRequest(
                "어떤 화면에 추가하나요?", "sess-abc", "question", new BigDecimal("0.01")));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getWorkerId()).isNull();
        assertThat(s.getClaudeSessionId()).isEqualTo("sess-abc");
        assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0.01");
        verify(turnRepo).save(any());
    }

    @Test
    void recordQuestion_from_wrong_status_throws() {
        InterviewSession s = session(2L, InterviewStatus.QUEUED);
        when(sessionRepo.findActiveById(2L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordQuestion(2L, "w1",
                new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("인터뷰중");
    }

    @Test
    void recordQuestion_wrong_worker_throws() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findActiveById(2L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordQuestion(2L, "w2",
                new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("다른 워커");
    }

    @Test
    void recordPlan_moves_running_to_plan_ready_and_persists_plan() {
        InterviewSession s = session(3L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findActiveById(3L)).thenReturn(Optional.of(s));
        service.recordPlan(3L, "w1", new WorkerPlanRequest("# 설계", "# 플랜", "[]",
                new BigDecimal("0.05"), 4321L));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
        assertThat(s.getWorkerId()).isNull();
        assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0.05");
        verify(planRepo).save(any());
    }

    @Test
    void fail_from_running_moves_to_failed() {
        InterviewSession s = session(4L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findActiveById(4L)).thenReturn(Optional.of(s));
        service.fail(4L, "w1", "clone 실패");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.FAILED);
        assertThat(s.getWorkerId()).isNull();
    }

    @Test
    void fail_from_terminal_throws() {
        InterviewSession s = session(4L, InterviewStatus.REGISTERED);
        when(sessionRepo.findActiveById(4L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.fail(4L, "w1", "x"))
                .isInstanceOf(TaskException.class);
    }
}
