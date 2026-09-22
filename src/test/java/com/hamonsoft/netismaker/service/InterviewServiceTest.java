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
    TaskStageUsageRepository stageUsageRepo;
    QuestionAttachmentRepository questionAttachmentRepo;
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
        stageUsageRepo = mock(TaskStageUsageRepository.class);
        questionAttachmentRepo = mock(QuestionAttachmentRepository.class);
        service = new InterviewService(sessionRepo, turnRepo, planRepo,
                taskRepo, analysisRepo, historyRepo, attachmentRepo, attachmentStorage, stageUsageRepo,
                questionAttachmentRepo);
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
    void claim_assigns_flattened_work_dir_for_gitlab_session() {
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        ReflectionTestUtils.setField(s, "githubRepo", "product/netis/web/package/netis-v7.0");
        s.setGitUrl("https://gitlab.hamon.vip/product/netis/web/package/netis-v7.0.git");
        when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w1");

        assertThat(s.getWorkDir())
                .endsWith("/netis-maker/interviews/_gitlab/product+netis+web+package+netis-v7.0/session-1");
        assertThat(resp.get().repoHost()).isEqualTo("gitlab");
        assertThat(resp.get().gitUrl()).isEqualTo(s.getGitUrl());
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
                "어떤 화면에 추가하나요?", "sess-abc", "question", new BigDecimal("0.01"), null, null, null, null, null, null));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getWorkerId()).isNull();
        assertThat(s.getClaudeSessionId()).isEqualTo("sess-abc");
        assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0.01");
        verify(turnRepo).save(any());
    }

    @Test
    void recordQuestion_stores_context_snapshot_when_reported() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(2L)).thenReturn(null);
        service.recordQuestion(2L, "w1", new com.hamonsoft.netismaker.dto.WorkerQuestionRequest(
                "AuthController입니다", "sess-abc", "question", new BigDecimal("0.01"),
                4L, 120L, 30000L, 46000L, 76004L, 200000L));
        assertThat(s.getContextTokens()).isEqualTo(76004L);
        assertThat(s.getContextWindow()).isEqualTo(200000L);
    }

    /** 구버전 인터뷰 서비스(컨텍스트 미보고)가 보고해도 이전 스냅샷을 지우지 않는다. */
    @Test
    void recordQuestion_keeps_previous_context_when_not_reported() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        s.setContextTokens(50000L);
        s.setContextWindow(200000L);
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(2L)).thenReturn(null);
        service.recordQuestion(2L, "w1", new com.hamonsoft.netismaker.dto.WorkerQuestionRequest(
                "답변", "sess-abc", "question", BigDecimal.ZERO, null, null, null, null, null, null));
        assertThat(s.getContextTokens()).isEqualTo(50000L);
        assertThat(s.getContextWindow()).isEqualTo(200000L);
    }

    @Test
    void recordQuestion_from_wrong_status_throws() {
        InterviewSession s = session(2L, InterviewStatus.QUEUED);
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordQuestion(2L, "w1",
                new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null, null, null, null, null, null, null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("인터뷰중");
    }

    @Test
    void recordQuestion_wrong_worker_throws() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordQuestion(2L, "w2",
                new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null, null, null, null, null, null, null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("다른 워커");
    }

    @Test
    void recordPlan_moves_running_to_plan_ready_and_persists_plan() {
        InterviewSession s = session(3L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(3L)).thenReturn(Optional.of(s));
        service.recordPlan(3L, "w1", new WorkerPlanRequest("# 설계", "# 플랜", "[]",
                new BigDecimal("0.05"), null, null, null, null, 4321L));
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
                new com.hamonsoft.netismaker.dto.AnswerRequest("중복 재시도", 2)).session();
        verify(turnRepo, never()).save(any());
        assertThat(result.getStatus()).isEqualTo(InterviewStatus.QUEUED); // no-op, 상태 유지
    }

    @Test
    void submitAnswer_returns_the_new_answer_turn_seq() {
        // 스펙 2026-09-13 §5.2: AnswerOutcome.turnSeq = 방금 붙인 user answer 턴의 seq (첨부 링크 키).
        InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(10L)).thenReturn(2);
        InterviewService.AnswerOutcome out = service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("답", 2));
        assertThat(out.session()).isSameAs(s);
        assertThat(out.turnSeq()).isEqualTo(3);
    }

    @Test
    void submitAnswer_duplicate_returns_null_turn_seq() {
        InterviewSession s = session(10L, InterviewStatus.QUEUED);
        when(sessionRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(10L)).thenReturn(List.of(
                com.hamonsoft.netismaker.entity.InterviewTurn.of(10L, 3, "user", "answer", "이전 답변", 2)));
        InterviewService.AnswerOutcome out = service.submitAnswer(10L, "u1", false,
                new com.hamonsoft.netismaker.dto.AnswerRequest("중복", 2));
        assertThat(out.session()).isSameAs(s);
        assertThat(out.turnSeq()).isNull();
    }

    @Test
    void appendSystemNote_appends_a_system_note_turn_at_next_seq() {
        when(turnRepo.findMaxSeq(10L)).thenReturn(4);
        com.hamonsoft.netismaker.entity.InterviewTurn t = service.appendSystemNote(10L, "MCP 도구 변경: ctx7");
        assertThat(t.getSessionId()).isEqualTo(10L);
        assertThat(t.getSeq()).isEqualTo(5);
        assertThat(t.getRole()).isEqualTo("system");
        assertThat(t.getKind()).isEqualTo("note");
        assertThat(t.getContent()).isEqualTo("MCP 도구 변경: ctx7");
        verify(turnRepo).save(t);
    }

    private static com.hamonsoft.netismaker.entity.QuestionAttachment qAtt(long id, long sid, Integer turnSeq,
                                                                            String name, String rel, String txt) {
        var a = com.hamonsoft.netismaker.entity.QuestionAttachment.create(sid, turnSeq, name, rel,
                "application/octet-stream", 10L, txt, "u1");
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    @Test
    void claim_on_question_session_carries_attachment_root_kickoff_and_per_turn_attachments() {
        InterviewSession s = questionSession(1L, InterviewStatus.QUEUED);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of(
                com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 0, "assistant", "question", "답1", null),
                com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 1, "user", "answer", "추가?", 0)));
        when(questionAttachmentRepo.findBySessionIdAndTurnSeqIsNullOrderByIdAsc(1L)).thenReturn(List.of(
                qAtt(11L, 1L, null, "설계.docx", "question-1/create/1-설계.docx", "question-1/create/1-설계.docx.txt")));
        when(questionAttachmentRepo.findBySessionIdAndTurnSeqIsNotNullOrderByIdAsc(1L)).thenReturn(List.of(
                qAtt(12L, 1L, 1, "로그.txt", "question-1/1/1-로그.txt", null)));
        when(attachmentStorage.absolutePathOf(any())).thenAnswer(i -> "/abs/" + i.getArgument(0));
        when(attachmentStorage.questionRootRelative(1L)).thenReturn("question-1");

        var resp = service.claim("w1").orElseThrow();

        assertThat(resp.attachmentRoot()).isEqualTo("/abs/question-1");
        assertThat(resp.attachments()).hasSize(1);
        assertThat(resp.attachments().get(0).fileName()).isEqualTo("설계.docx");
        assertThat(resp.attachments().get(0).absolutePath()).isEqualTo("/abs/question-1/create/1-설계.docx");
        assertThat(resp.attachments().get(0).extractedTextPath()).isEqualTo("/abs/question-1/create/1-설계.docx.txt");
        assertThat(resp.turns().get(0).attachments()).isEmpty();
        assertThat(resp.turns().get(1).attachments()).hasSize(1);
        assertThat(resp.turns().get(1).attachments().get(0).id()).isEqualTo(12L);
        assertThat(resp.turns().get(1).attachments().get(0).absolutePath()).isEqualTo("/abs/question-1/1/1-로그.txt");
        assertThat(resp.turns().get(1).attachments().get(0).extractedTextPath()).isNull();
        verify(attachmentRepo, never()).findByTaskIdOrderByIdAsc(any());   // task 첨부 경로는 안 탄다
    }

    @Test
    void claim_on_interview_session_keeps_task_attachment_path_and_null_attachment_root() {
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        s.setTaskId(77L);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of(
                com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 0, "assistant", "question", "q", null)));
        when(attachmentRepo.findByTaskIdOrderByIdAsc(77L)).thenReturn(List.of());

        var resp = service.claim("w1").orElseThrow();

        assertThat(resp.attachmentRoot()).isNull();
        assertThat(resp.attachments()).isEmpty();
        assertThat(resp.turns().get(0).attachments()).isNotNull().isEmpty();
        verify(attachmentRepo).findByTaskIdOrderByIdAsc(77L);
        verifyNoInteractions(questionAttachmentRepo);
    }

    @Test
    void getResponse_on_question_session_exposes_mcp_ids_and_attachment_views_without_paths() {
        InterviewSession s = questionSession(1L, InterviewStatus.AWAITING_INPUT);
        s.setMcpCatalogIds(new java.util.ArrayList<>(List.of(3L, 4L)));
        when(sessionRepo.findActiveById(1L)).thenReturn(Optional.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of(
                com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 0, "assistant", "question", "답1", null),
                com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 1, "user", "answer", "추가?", 0)));
        when(planRepo.findById(1L)).thenReturn(Optional.empty());
        when(questionAttachmentRepo.findBySessionIdOrderByIdAsc(1L)).thenReturn(List.of(
                qAtt(11L, 1L, null, "설계.docx", "question-1/create/1-설계.docx", "question-1/create/1-설계.docx.txt"),
                qAtt(12L, 1L, 1, "로그.txt", "question-1/1/1-로그.txt", null)));

        var r = service.getResponse(1L, "u1", false);

        assertThat(r.mcpCatalogIds()).containsExactly(3L, 4L);
        assertThat(r.attachments()).hasSize(1);
        assertThat(r.attachments().get(0).id()).isEqualTo(11L);
        assertThat(r.attachments().get(0).fileName()).isEqualTo("설계.docx");
        assertThat(r.attachments().get(0).sizeBytes()).isEqualTo(10L);
        assertThat(r.turns().get(0).attachments()).isEmpty();
        assertThat(r.turns().get(1).attachments()).hasSize(1);
        assertThat(r.turns().get(1).attachments().get(0).fileName()).isEqualTo("로그.txt");
    }

    @Test
    void getResponse_on_interview_session_has_empty_attachment_fields_and_skips_question_repo() {
        InterviewSession s = session(1L, InterviewStatus.AWAITING_INPUT);
        when(sessionRepo.findActiveById(1L)).thenReturn(Optional.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(planRepo.findById(1L)).thenReturn(Optional.empty());

        var r = service.getResponse(1L, "u1", false);

        assertThat(r.mcpCatalogIds()).isNotNull().isEmpty();
        assertThat(r.attachments()).isNotNull().isEmpty();
        verifyNoInteractions(questionAttachmentRepo);
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
                new BigDecimal("0.01"), null, null, null, null, null, null));
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
                new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ONE, null, null, null, null, 1L)))
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
