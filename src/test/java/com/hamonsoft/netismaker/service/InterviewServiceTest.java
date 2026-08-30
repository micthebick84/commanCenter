package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
    TaskRepository taskRepo;
    TaskAnalysisRepository analysisRepo;
    TaskStatusHistoryRepository historyRepo;
    TaskAttachmentRepository attachmentRepo;
    AttachmentStorage attachmentStorage;
    InterviewService service;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(InterviewSessionRepository.class);
        turnRepo = mock(InterviewTurnRepository.class);
        planRepo = mock(InterviewPlanRepository.class);
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        attachmentRepo = mock(TaskAttachmentRepository.class);
        attachmentStorage = mock(AttachmentStorage.class);
        service = new InterviewService(sessionRepo, turnRepo, planRepo,
                taskRepo, analysisRepo, historyRepo, attachmentRepo, attachmentStorage);
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

    private InterviewSession session(long id, InterviewStatus status) {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(status);
        return s;
    }

    private InterviewSession questionSession(long id, InterviewStatus status) {
        InterviewSession s = InterviewSession.createQuestion("owner/repo", "main", "T", "q?", "u1",
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
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
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
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordQuestion(2L, "w1",
                new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("인터뷰중");
    }

    @Test
    void recordQuestion_wrong_worker_throws() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordQuestion(2L, "w2",
                new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("다른 워커");
    }

    @Test
    void recordPlan_moves_running_to_plan_ready_and_persists_plan() {
        InterviewSession s = session(3L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(3L)).thenReturn(Optional.of(s));
        service.recordPlan(3L, "w1", new WorkerPlanRequest("# 설계", "# 플랜", "[]",
                new BigDecimal("0.05"), 4321L));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
        assertThat(s.getWorkerId()).isNull();
        assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0.05");
        verify(planRepo).save(any());
    }

    @Test
    void heartbeat_refreshes_last_activity_but_not_claimed_at() {
        // 회귀 가드: heartbeat가 claimedAt(턴 시작 시각)을 갱신하면 SDK 행업 시
        // wall-clock 스윕이 영영 발동하지 않는다 — 생존 신호는 lastActivityAt에만.
        InterviewSession s = session(5L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        java.time.OffsetDateTime turnStart = java.time.OffsetDateTime.now().minusMinutes(10);
        java.time.OffsetDateTime staleActivity = java.time.OffsetDateTime.now().minusMinutes(10);
        s.setClaimedAt(turnStart);
        s.setLastActivityAt(staleActivity);
        when(sessionRepo.findByIdForUpdate(5L)).thenReturn(Optional.of(s));

        service.heartbeat(5L, "w1");

        assertThat(s.getClaimedAt()).isEqualTo(turnStart);           // 불변 — wall-clock 앵커
        assertThat(s.getLastActivityAt()).isAfter(staleActivity);    // 생존 신호 갱신
    }

    @Test
    void fail_from_running_moves_to_failed() {
        InterviewSession s = session(4L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(4L)).thenReturn(Optional.of(s));
        service.fail(4L, "w1", "clone 실패");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.FAILED);
        assertThat(s.getWorkerId()).isNull();
    }

    @Test
    void failFromWorker_on_running_owned_session_moves_to_failed() {
        InterviewSession s = session(6L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(s));
        service.failFromWorker(6L, "w1", "SDK 턴 wall-clock 타임아웃(30분) 초과 — 행업 회수");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.FAILED);
        assertThat(s.getWorkerId()).isNull();
    }

    @Test
    void failFromWorker_on_awaiting_input_throws_conflict_and_preserves_the_question() {
        // 경계 레이스 가드: postQuestion이 먼저 도착해 AWAITING_INPUT이 된 세션에
        // 지각한 턴 타임아웃 fail이 오면 방금 전달된 질문을 파괴하지 말고 409로 거절해야 한다.
        InterviewSession s = session(6L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.failFromWorker(6L, "w1", "타임아웃"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("워커");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
    }

    @Test
    void failFromWorker_by_non_owner_throws_conflict() {
        // 다른 워커가 재클레임한 세션을 이전 워커의 지각 fail이 죽이면 안 된다.
        InterviewSession s = session(6L, InterviewStatus.RUNNING);
        s.setWorkerId("w2");
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.failFromWorker(6L, "w1", "타임아웃"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("다른 워커");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.RUNNING);
    }

    @Test
    void fail_from_terminal_throws() {
        InterviewSession s = session(4L, InterviewStatus.REGISTERED);
        when(sessionRepo.findByIdForUpdate(4L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.fail(4L, "w1", "x"))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void submitAnswer_moves_awaiting_to_queued_and_logs_user_turn() {
        InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(10L)).thenReturn(2);  // last question at seq 2
        service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("좌측 패널에 추가", 2));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        verify(turnRepo).save(any());
    }

    @Test
    void submitAnswer_duplicate_replyToSeq_is_ignored() {
        InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(s));
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
        when(sessionRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(s));
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
        when(sessionRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("늦은 답변", 1)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("입력대기");
    }

    @Test
    void submitAnswer_by_non_owner_throws_forbidden() {
        InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.submitAnswer(10L, "intruder", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("x", 1)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("권한");
    }

    @Test
    void cancel_from_awaiting_input_moves_to_cancelled() {
        InterviewSession s = session(11L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findByIdForUpdate(11L)).thenReturn(Optional.of(s));
        service.cancel(11L, "u1", false);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
    }

    @Test
    void cancel_from_registered_throws() {
        InterviewSession s = session(11L, InterviewStatus.REGISTERED);
        when(sessionRepo.findByIdForUpdate(11L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.cancel(11L, "u1", false))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void expire_from_awaiting_input_moves_to_expired() {
        InterviewSession s = session(12L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findByIdForUpdate(12L)).thenReturn(Optional.of(s));
        service.expire(12L, "idle TTL 초과");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.EXPIRED);
    }

    @Test
    void expire_from_queued_moves_to_expired_and_returns_task_to_awaiting_approval() {
        // 인터뷰 서비스 미가동으로 QUEUED에 체류한 세션도 만료 가능해야 한다 (QUEUED TTL).
        InterviewSession s = session(13L, InterviewStatus.QUEUED);
        s.setTaskId(77L);
        Task t = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 77L);
        t.setStatus(TaskStatus.INTERVIEWING);
        when(sessionRepo.findByIdForUpdate(13L)).thenReturn(Optional.of(s));
        when(taskRepo.findActiveByIdForUpdate(77L)).thenReturn(Optional.of(t));

        service.expire(13L, "인터뷰대기 60분 초과(인터뷰 서비스 미처리)");

        assertThat(s.getStatus()).isEqualTo(InterviewStatus.EXPIRED);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL); // task 미러 복귀
        verify(turnRepo).save(any());     // system note 턴
        verify(historyRepo).save(any());  // task 히스토리
    }

    @Test
    void expire_from_running_throws() {
        InterviewSession s = session(12L, InterviewStatus.RUNNING);
        when(sessionRepo.findByIdForUpdate(12L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.expire(12L, "x"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void expire_from_terminal_throws() {
        InterviewSession s = session(12L, InterviewStatus.REGISTERED);
        when(sessionRepo.findByIdForUpdate(12L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.expire(12L, "x"))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void recordQuestion_on_question_session_does_not_set_current_phase_and_touches_no_task() {
        InterviewSession s = questionSession(5L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(5L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(5L)).thenReturn(null);
        service.recordQuestion(5L, "w1", new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("답변입니다", "sess-q", "question",
                new BigDecimal("0.01")));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getCurrentPhase()).isNull();
        assertThat(s.getClaudeSessionId()).isEqualTo("sess-q");
        verify(taskRepo, never()).findActiveByIdForUpdate(any()); // taskId null → mirrorTask no-op
    }

    @Test
    void recordPlan_on_question_session_throws_400_and_keeps_running() {
        InterviewSession s = questionSession(6L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordPlan(6L, "w1",
                new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ONE, 1L)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.RUNNING);
        verify(planRepo, never()).save(any());
    }

    @Test
    void confirm_on_question_session_throws_400_regardless_of_status() {
        InterviewSession s = questionSession(7L, InterviewStatus.PLAN_READY);
        when(sessionRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.confirm(7L, "admin", false))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(taskRepo, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void requireKind_returns_session_on_match_and_404_on_mismatch() {
        InterviewSession s = questionSession(8L, InterviewStatus.QUEUED);
        when(sessionRepo.findActiveById(8L)).thenReturn(Optional.of(s));
        assertThat(service.requireKind(8L, InterviewKind.QUESTION)).isSameAs(s);
        assertThatThrownBy(() -> service.requireKind(8L, InterviewKind.INTERVIEW))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

}
