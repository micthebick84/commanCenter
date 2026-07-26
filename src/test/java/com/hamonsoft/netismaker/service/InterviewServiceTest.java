package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
import com.hamonsoft.netismaker.dto.InterviewSummary;
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
    RepoCatalogService repoCatalogService;
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
        repoCatalogService = mock(RepoCatalogService.class);
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new InterviewService(sessionRepo, turnRepo, planRepo,
                mcpCatalogService, repoCatalogService, taskRepo, analysisRepo, historyRepo);
        // default: resolve returns a stub repo for any id
        when(repoCatalogService.resolveForRegistration(any())).thenReturn(
                new RepoCatalogService.ResolvedRepo(1L, "owner/repo-alias",
                        "https://github.com/owner/repo.git", "github", "owner/repo", null));
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 3);
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
        return new CreateInterviewRequest(1L, "main", "제목", "기능 요구", List.of(), null, null);
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
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-4-8", "high");
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

    @Test
    void submitAnswer_moves_awaiting_to_queued_and_logs_user_turn() {
        InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(10L)).thenReturn(2);  // last question at seq 2
        service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("좌측 패널에 추가", 2));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        verify(turnRepo).save(any());
    }

    @Test
    void submitAnswer_duplicate_replyToSeq_is_ignored() {
        InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
        // an answer turn already exists at seq 3 replying to question seq 2 (reply_to_seq=2)
        com.hamonsoft.netismaker.entity.InterviewTurn existing =
                com.hamonsoft.netismaker.entity.InterviewTurn.of(10L, 3, "user", "answer", "이전 답변", 2);
        when(turnRepo.findBySessionIdOrderBySeqAsc(10L)).thenReturn(List.of(existing));
        service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("중복", 2));
        // no new turn, status unchanged (still AWAITING_INPUT)
        verify(turnRepo, never()).save(any());
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
    }

    @Test
    void submitAnswer_duplicate_after_requeue_is_idempotent_noop() {
        // 회귀 가드: 1차 답변으로 이미 QUEUED가 된 뒤(그 답변 턴 reply_to_seq=2 존재) 동일 답변을
        // 재제출하면 conflict가 아니라 no-op이어야 한다 — idempotency 검사가 status 가드보다 우선.
        // (e2e InterviewAnswerIdempotencyTest와 동일 시나리오를 Docker 없이 잠금.)
        InterviewSession s = session(10L, InterviewStatus.QUEUED);
        when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
        com.hamonsoft.netismaker.entity.InterviewTurn existing =
                com.hamonsoft.netismaker.entity.InterviewTurn.of(10L, 3, "user", "answer", "이전 답변", 2);
        when(turnRepo.findBySessionIdOrderBySeqAsc(10L)).thenReturn(List.of(existing));
        InterviewSession result = service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("중복 재시도", 2));
        verify(turnRepo, never()).save(any());
        assertThat(result.getStatus()).isEqualTo(InterviewStatus.QUEUED); // no-op, 상태 유지
    }

    @Test
    void submitAnswer_to_expired_session_throws_conflict() {
        InterviewSession s = session(10L, InterviewStatus.EXPIRED);
        when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("늦은 답변", 1)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("입력대기");
    }

    @Test
    void submitAnswer_by_non_owner_throws_forbidden() {
        InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.submitAnswer(10L, "intruder", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("x", 1)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("권한");
    }

    @Test
    void cancel_from_awaiting_input_moves_to_cancelled() {
        InterviewSession s = session(11L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveById(11L)).thenReturn(Optional.of(s));
        service.cancel(11L, "u1", false);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
    }

    @Test
    void cancel_from_registered_throws() {
        InterviewSession s = session(11L, InterviewStatus.REGISTERED);
        when(sessionRepo.findActiveById(11L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.cancel(11L, "u1", false))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void expire_from_awaiting_input_moves_to_expired() {
        InterviewSession s = session(12L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveById(12L)).thenReturn(Optional.of(s));
        service.expire(12L, "idle TTL 초과");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.EXPIRED);
    }

    @Test
    void expire_from_running_throws() {
        InterviewSession s = session(12L, InterviewStatus.RUNNING);
        when(sessionRepo.findActiveById(12L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.expire(12L, "x"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("입력대기");
    }

    @Test
    void listActiveForRequester_maps_sessions_to_summaries() {
        InterviewSession s = session(30L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveByRequester("u1")).thenReturn(List.of(s));

        List<InterviewSummary> out = service.listActiveForRequester("u1");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo(30L);
        assertThat(out.get(0).status()).isEqualTo("입력대기");        // 한글 dbValue (표시)
        assertThat(out.get(0).statusName()).isEqualTo("AWAITING_INPUT"); // 영문 enum (로직)
        assertThat(out.get(0).title()).isEqualTo("T");
        verify(sessionRepo).findActiveByRequester("u1");
    }

    @Test
    void create_applies_default_model_and_effort_when_blank() {
        when(sessionRepo.countActiveByRequester("u1")).thenReturn(0L);
        when(mcpCatalogService.resolveByIds(any())).thenReturn(List.of());
        InterviewSession s = service.create(req(), "u1");   // req() sends model=null, effort=null
        assertThat(s.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(s.getEffort()).isEqualTo("high");
    }

    @Test
    void create_rejects_incompatible_model_effort() {
        when(sessionRepo.countActiveByRequester("u1")).thenReturn(0L);
        var bad = new com.hamonsoft.netismaker.dto.CreateInterviewRequest(
                1L, "main", "제목", "내용", List.of(), "claude-haiku-4-5", "max");
        assertThatThrownBy(() -> service.create(bad, "u1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("effort");
    }

}
