package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.dto.RefRepoInput;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest.RefState;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    RepoCatalogService repoCatalog;
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
                questionAttachmentRepo,
                new InterviewWorkPaths(System.getProperty("user.home") + "/netis-maker/interviews"));
        repoCatalog = mock(RepoCatalogService.class);
        service.setRepoCatalogService(repoCatalog);
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
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
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
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w1");

        assertThat(s.getWorkDir())
                .endsWith("/netis-maker/interviews/_gitlab/product+netis+web+package+netis-v7.0/session-1");
        assertThat(resp.get().repoHost()).isEqualTo("gitlab");
        assertThat(resp.get().gitUrl()).isEqualTo(s.getGitUrl());
    }

    @Test
    void claim_derives_work_dir_from_the_configured_checkout_root() {
        // 체크아웃 루트는 설정(app.interview.work-root) 하나 — claim이 user.home을 직접 읽지 않는다 (스펙 2026-10-22 §3.3)
        InterviewService custom = new InterviewService(sessionRepo, turnRepo, planRepo,
                taskRepo, analysisRepo, historyRepo, attachmentRepo, attachmentStorage, stageUsageRepo,
                questionAttachmentRepo, new InterviewWorkPaths("/data/interviews"));
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = custom.claim("w1");

        assertThat(s.getWorkDir()).isEqualTo("/data/interviews/owner/repo/session-1");
        assertThat(resp.get().workDir()).isEqualTo("/data/interviews/owner/repo/session-1");
    }

    @Test
    void claim_resume_keeps_existing_work_dir() {
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        s.setWorkDir("/preset/path/session-1");
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
        Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w2");
        assertThat(s.getWorkDir()).isEqualTo("/preset/path/session-1");   // resume → 불변
        assertThat(resp.get().workDir()).isEqualTo("/preset/path/session-1");
    }

    @Test
    void claim_empty_queue_returns_empty() {
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of());
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
    void fail_returns_the_reason_note_so_the_caller_can_push_it() {
        // 실패 사유는 DB에만 남으면 안 된다 — 호출자(컨트롤러/스윕)가 SSE로 밀 수 있게 턴을 돌려준다.
        InterviewSession s = session(6L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(s));

        InterviewService.FailOutcome out = service.failFromWorker(6L, "w1", "OAuth 세션 만료");

        assertThat(out.note().getRole()).isEqualTo("system");
        assertThat(out.note().getKind()).isEqualTo("note");
        assertThat(out.note().getContent()).isEqualTo("인터뷰 실패: OAuth 세션 만료");
    }

    @Test
    void fail_labels_the_note_by_session_kind() {
        // 질문 세션 배너는 '답변 실패'다 — 노트가 그 문구 그대로여야 배너가 접두사를 덧붙이지 않는다.
        InterviewSession s = questionSession(7L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(s));

        InterviewService.FailOutcome out = service.failFromWorker(7L, "w1", "OAuth 세션 만료");

        assertThat(out.note().getContent()).isEqualTo("답변 실패: OAuth 세션 만료");
    }

    @Test
    void fail_without_a_reason_still_carries_a_note() {
        InterviewSession s = session(6L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(s));

        InterviewService.FailOutcome out = service.failFromWorker(6L, "w1", null);

        assertThat(out.note().getContent()).isEqualTo("인터뷰 실패: 원인 미상");
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
    void getResponse_marks_attachments_expired_only_when_question_closed_and_file_gone(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path kept = java.nio.file.Files.writeString(dir.resolve("kept.txt"), "x");
        java.nio.file.Path gone = dir.resolve("gone.txt");
        when(attachmentStorage.resolve("question-1/create/kept.txt")).thenReturn(kept);
        when(attachmentStorage.resolve("question-1/1/gone.txt")).thenReturn(gone);
        when(questionAttachmentRepo.findBySessionIdOrderByIdAsc(1L)).thenReturn(List.of(
                qAtt(11L, 1L, null, "kept.txt", "question-1/create/kept.txt", null),
                qAtt(12L, 1L, 1, "gone.txt", "question-1/1/gone.txt", null)));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of(
                com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 0, "assistant", "question", "답1", null),
                com.hamonsoft.netismaker.entity.InterviewTurn.of(1L, 1, "user", "answer", "추가?", 0)));

        // 종료된 질문: 지워진 파일만 만료
        when(sessionRepo.findActiveById(1L)).thenReturn(Optional.of(questionSession(1L, InterviewStatus.CANCELLED)));
        var closed = service.getResponse(1L, "u1", false);
        assertThat(closed.attachments().get(0).expired()).isFalse();
        assertThat(closed.turns().get(1).attachments().get(0).expired()).isTrue();

        // 진행 중 질문: 파일이 없어도 만료로 표시하지 않는다(실제 유실은 다운로드가 404로 알린다) — 파일 검사도 하지 않는다
        clearInvocations(attachmentStorage);
        when(sessionRepo.findActiveById(1L)).thenReturn(Optional.of(questionSession(1L, InterviewStatus.AWAITING_INPUT)));
        var open = service.getResponse(1L, "u1", false);
        assertThat(open.turns().get(1).attachments().get(0).expired()).isFalse();
        verify(attachmentStorage, never()).resolve(any());
    }

    @Test
    void claim_on_question_session_carries_attachment_root_kickoff_and_per_turn_attachments() {
        InterviewSession s = questionSession(1L, InterviewStatus.QUEUED);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
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
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
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

    // ── 질문 세션 참조 repo: claim 페이로드·답변 보고 (스펙 2026-10-22 §4.4·§4.5) ─────────────────

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    private static RefRepo ref(long catalogId, String branch, String sha, String fetchedAt, String error) {
        return new RefRepo(catalogId, "참조" + catalogId, "acme/ref" + catalogId,
                "https://github.com/acme/ref" + catalogId + ".git", branch, sha, fetchedAt, error);
    }

    /** 참조 repo를 가진 RUNNING 질문 세션 + recordQuestion이 쓰는 목 준비. */
    private InterviewSession runningQuestionWithRefs(long id, RefRepo... refs) {
        InterviewSession s = questionSession(id, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        s.setRefRepos(new ArrayList<>(List.of(refs)));
        when(sessionRepo.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(id)).thenReturn(null);
        return s;
    }

    /** 답변 보고 뒤(AWAITING_INPUT) 세션을 다음 턴처럼 다시 RUNNING으로 돌린다. */
    private static void nextTurn(InterviewSession s) {
        s.setStatus(InterviewStatus.RUNNING);
        s.setWorkerId("w1");
    }

    private static WorkerQuestionRequest answerWith(RefState... states) {
        return new WorkerQuestionRequest("답변", "sess-q", "question", BigDecimal.ZERO,
                null, null, null, null, null, null, java.util.Arrays.asList(states));
    }

    @Test
    void claim_question_session_carries_reference_dirs_under_the_refs_root() {
        InterviewSession s = questionSession(1L, InterviewStatus.QUEUED);
        s.setRefRepos(new ArrayList<>(List.of(
                new RefRepo(11L, "Netis6.5(구)", "product/netis/web/package/netis-v6.5",
                        "https://gitlab.hamon.vip/product/netis/web/package/netis-v6.5.git",
                        "release/6.5", null, null, null),
                new RefRepo(12L, "공통 UI", "acme/ui", "https://github.com/acme/ui.git", "main",
                        "c".repeat(40), "2026-10-22T10:00:00+09:00", null))));
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        InterviewClaimResponse c = service.claim("w1").orElseThrow();

        String root = System.getProperty("user.home") + "/netis-maker/interviews";
        assertThat(c.refRepos()).extracting(InterviewClaimResponse.ClaimRefRepo::dir)
                .containsExactly(root + "/_refs/1/11", root + "/_refs/1/12");
        assertThat(c.refRepos().get(0).repoHost()).isEqualTo("gitlab");
        assertThat(c.refRepos().get(1).sha()).isEqualTo("c".repeat(40));
    }

    @Test
    void claim_question_session_maps_every_reference_field_in_selection_order() {
        InterviewSession s = questionSession(1L, InterviewStatus.QUEUED);
        s.setRefRepos(new ArrayList<>(List.of(
                ref(12L, "main", SHA_A, "2026-10-22T10:00:00+09:00", null),
                ref(11L, "release/6.5", null, null, "브랜치를 찾을 수 없음"))));
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        InterviewClaimResponse c = service.claim("w1").orElseThrow();

        String root = System.getProperty("user.home") + "/netis-maker/interviews";
        assertThat(c.refRepos()).containsExactly(
                new InterviewClaimResponse.ClaimRefRepo(12L, "참조12", "acme/ref12",
                        "https://github.com/acme/ref12.git", "github", "main", root + "/_refs/1/12", SHA_A),
                new InterviewClaimResponse.ClaimRefRepo(11L, "참조11", "acme/ref11",
                        "https://github.com/acme/ref11.git", "github", "release/6.5", root + "/_refs/1/11", null));
    }

    @Test
    void claim_question_session_without_refs_has_an_empty_list() {
        InterviewSession s = questionSession(1L, InterviewStatus.QUEUED);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        assertThat(service.claim("w1").orElseThrow().refRepos()).isNotNull().isEmpty();
    }

    // ── 작업 인터뷰 참조 repo: claim·계획 보고·답변 중 변경 (스펙 2026-10-24 §4.3·§4.4·§4.5) ─────────────────

    @Test
    void claim_interview_session_carries_reference_dirs() {
        // 2단계: INTERVIEW 세션도 질문과 같은 규칙({루트}/_refs/{세션 id}/{카탈로그 id})으로 참조를 싣는다
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        s.setRefRepos(new ArrayList<>(List.of(ref(11L, "main", null, null, null))));
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        InterviewClaimResponse c = service.claim("w1").orElseThrow();

        String root = System.getProperty("user.home") + "/netis-maker/interviews";
        assertThat(c.refRepos()).extracting(InterviewClaimResponse.ClaimRefRepo::dir)
                .containsExactly(root + "/_refs/1/11");
        assertThat(c.refRepos().get(0).alias()).isEqualTo("참조11");
        assertThat(c.refRepos().get(0).sha()).isNull();
        assertThat(c.refsCleared()).isFalse();
    }

    @Test
    void claim_interview_session_without_refs_has_an_empty_list_and_is_not_cleared() {
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());

        InterviewClaimResponse c = service.claim("w1").orElseThrow();

        assertThat(c.refRepos()).isNotNull().isEmpty();
        assertThat(c.refsCleared()).isFalse();
    }

    private static InterviewTurn note(int seq, String content) {
        return InterviewTurn.of(1L, seq, "system", "note", content, null);
    }

    /** 세션 1을 주어진 턴과 함께 claim해서 refsCleared를 돌려준다 — claim은 QUEUED에서만 되므로 세션은 호출마다 새로 만든다. */
    private boolean claimRefsCleared(InterviewSession s, InterviewTurn... turns) {
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of(turns));
        return service.claim("w1").orElseThrow().refsCleared();
    }

    @Test
    void claim_interview_marks_refs_cleared_only_when_the_last_ref_note_cleared_them() {
        InterviewTurn added = note(1, "참조 repo 변경: ＋ 옛앱(main)");
        InterviewTurn cleared = note(3, "참조 repo 변경: 없음(전부 해제)");
        InterviewTurn readded = note(5, "참조 repo 변경: ＋ 옛앱(main)");
        InterviewTurn mcpNote = note(6, "MCP 도구 변경: ctx7");
        InterviewTurn answerLookingLikeTheNote =
                InterviewTurn.of(1L, 7, "user", "answer", "참조 repo 변경: 없음(전부 해제)", 6);

        // 대화 중 모두 뺐다 — 참조 메모가 아닌 노트가 뒤에 와도 그대로
        assertThat(claimRefsCleared(session(1L, InterviewStatus.QUEUED), added, cleared)).isTrue();
        assertThat(claimRefsCleared(session(1L, InterviewStatus.QUEUED), added, cleared, mcpNote)).isTrue();
        // 참조를 고른 적 없음(메모 없음) — 프롬프트가 그대로여야 한다
        assertThat(claimRefsCleared(session(1L, InterviewStatus.QUEUED))).isFalse();
        // 마지막 참조 메모가 추가 — 목록이 비어 있어도 '모두 뺐다'가 아니다
        assertThat(claimRefsCleared(session(1L, InterviewStatus.QUEUED), added, cleared, readded)).isFalse();
        // system note가 아닌 턴의 같은 문장은 메모가 아니다
        assertThat(claimRefsCleared(session(1L, InterviewStatus.QUEUED), added, answerLookingLikeTheNote)).isFalse();
        // 참조가 남아 있으면 메모와 상관없이 false
        InterviewSession withRefs = session(1L, InterviewStatus.QUEUED);
        withRefs.setRefRepos(new ArrayList<>(List.of(ref(11L, "main", null, null, null))));
        assertThat(claimRefsCleared(withRefs, added, cleared)).isFalse();
        // 질문 세션은 같은 턴이어도 false — 질문 프롬프트는 바뀌지 않는다
        assertThat(claimRefsCleared(questionSession(1L, InterviewStatus.QUEUED), added, cleared)).isFalse();
    }

    private InterviewSession runningInterviewWithRefs(long id, RefRepo... refs) {
        InterviewSession s = session(id, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        s.setRefRepos(new ArrayList<>(List.of(refs)));
        when(sessionRepo.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        return s;
    }

    private static WorkerPlanRequest planWith(List<RefState> states) {
        return new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ZERO, null, null, null, null, 1000L, states);
    }

    @Test
    void recordPlan_pins_reported_ref_states() {
        // 마지막 답변에서 더한 참조가 계획 턴에 처음 받아져도 커밋이 고정돼야 한다 (스펙 §4.5)
        InterviewSession s = runningInterviewWithRefs(3L,
                ref(11L, "main", null, null, null),
                ref(12L, "release/6.5", null, null, null));

        service.recordPlan(3L, "w1", planWith(List.of(
                new RefState(11L, "main", SHA_A, null),
                new RefState(12L, "release/6.5", null, "권한 없음"))));

        assertThat(s.getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
        assertThat(s.getRefRepos().get(0).fetchedAt()).isNotNull();
        assertThat(s.getRefRepos().get(1).sha()).isNull();
        assertThat(s.getRefRepos().get(1).error()).isEqualTo("권한 없음");
    }

    @Test
    void recordPlan_without_ref_states_leaves_refs_untouched() {
        // 구버전 러너 — 9인자 생성자(refStates = null)
        InterviewSession s = runningInterviewWithRefs(3L, ref(11L, "main", null, null, "이전 오류"));
        List<RefRepo> before = s.getRefRepos();

        service.recordPlan(3L, "w1", new WorkerPlanRequest("# 설계", "# 플랜", "[]",
                new BigDecimal("0.05"), null, null, null, null, 4321L));

        assertThat(s.getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
        assertThat(s.getRefRepos()).isSameAs(before);
        assertThat(s.getRefRepos().get(0).error()).isEqualTo("이전 오류");
    }

    @Test
    void applyRefStates_pins_only_a_full_hex_sha() {
        // 화면이 '고정'으로 보여 준 참조가 구현에서 형식 오류로 실패하지 않게 — 40자·64자 16진수만 고정한다(워커 isFullSha와 같은 정의)
        for (String bad : List.of("abc", "--upload-pack=x", "a".repeat(39), "a".repeat(41), "g".repeat(40),
                "a".repeat(40) + "\n", "a".repeat(63), "a".repeat(65))) {
            InterviewSession q = runningQuestionWithRefs(5L, ref(11L, "main", null, null, "이전 오류"));
            service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", bad, null)));
            assertThat(q.getRefRepos().get(0).sha()).as("question %s", bad).isNull();
            assertThat(q.getRefRepos().get(0).error()).as("question %s", bad).isEqualTo("이전 오류");

            InterviewSession i = runningInterviewWithRefs(3L, ref(11L, "main", null, null, "이전 오류"));
            service.recordPlan(3L, "w1", planWith(List.of(new RefState(11L, "main", bad, null))));
            assertThat(i.getRefRepos().get(0).sha()).as("plan %s", bad).isNull();
            assertThat(i.getRefRepos().get(0).error()).as("plan %s", bad).isEqualTo("이전 오류");
        }
        for (String good : List.of("a".repeat(40), "0123456789ABCDEFabcd".repeat(2), "f".repeat(64))) {
            InterviewSession q = runningQuestionWithRefs(5L, ref(11L, "main", null, null, "이전 오류"));
            service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", good, null)));
            assertThat(q.getRefRepos().get(0).sha()).as("question %s", good).isEqualTo(good);
            assertThat(q.getRefRepos().get(0).error()).as("question %s", good).isNull();

            InterviewSession i = runningInterviewWithRefs(3L, ref(11L, "main", null, null, "이전 오류"));
            service.recordPlan(3L, "w1", planWith(List.of(new RefState(11L, "main", good, null))));
            assertThat(i.getRefRepos().get(0).sha()).as("plan %s", good).isEqualTo(good);
            assertThat(i.getRefRepos().get(0).error()).as("plan %s", good).isNull();
        }
    }

    private static RefRepoInput in(long catalogId, String branch) {
        return new RefRepoInput(catalogId, branch);
    }

    /** 대상 카탈로그 1에 참조를 가진 입력대기 INTERVIEW 세션 — 답변 턴은 seq 3, 그 뒤 메모는 seq 4. */
    private InterviewSession awaitingInterviewWithRefs(long id, RefRepo... refs) {
        InterviewSession s = session(id, InterviewStatus.AWAITING_INPUT);
        s.setRepoCatalogId(1L);
        s.setRefRepos(new ArrayList<>(List.of(refs)));
        when(sessionRepo.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(id)).thenReturn(2, 3);
        return s;
    }

    @Test
    void submitAnswerWithRefs_without_refs_is_a_plain_answer() {
        InterviewSession s = awaitingInterviewWithRefs(1L, ref(11L, "main", SHA_A, "2026-10-24T10:00+09:00", null));
        List<RefRepo> before = s.getRefRepos();

        InterviewService.AnswerResult r = service.submitAnswerWithRefs(1L, "admin", new AnswerRequest("답", 2));

        assertThat(r.refNote()).isNull();
        assertThat(r.outcome().session()).isSameAs(s);
        assertThat(r.outcome().turnSeq()).isEqualTo(3);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getRefRepos()).isSameAs(before);
        verify(turnRepo, never()).save(argThat(t -> "system".equals(t.getRole())));
        verifyNoInteractions(repoCatalog);
    }

    @Test
    void submitAnswerWithRefs_with_the_same_set_is_a_plain_answer_without_validation() {
        // 같은 (카탈로그 id + 브랜치) 집합 — 순서가 달라도 검증 없는 no-op (1단계 ask와 같은 규칙)
        InterviewSession s = awaitingInterviewWithRefs(1L,
                ref(11L, "main", SHA_A, "2026-10-24T10:00+09:00", null), ref(12L, "master", null, null, null));
        List<RefRepo> before = s.getRefRepos();

        InterviewService.AnswerResult r = service.submitAnswerWithRefs(1L, "admin",
                new AnswerRequest("답", 2, List.of(in(12, "master"), in(11, "main"))));

        assertThat(r.refNote()).isNull();
        assertThat(s.getRefRepos()).isSameAs(before);
        verifyNoInteractions(repoCatalog);
    }

    @Test
    void submitAnswerWithRefs_changes_refs_and_appends_the_note() {
        RefRepo oldApp = ref(11L, "main", SHA_A, "2026-10-24T10:00+09:00", null);
        InterviewSession s = awaitingInterviewWithRefs(1L, oldApp);
        RefRepo batch = new RefRepo(12L, "배치 서버", "acme/batch", "https://github.com/acme/batch.git",
                "master", null, null, null);
        when(repoCatalog.resolveReferences(1L, List.of(in(12, "master")))).thenReturn(List.of(batch));

        InterviewService.AnswerResult r = service.submitAnswerWithRefs(1L, "admin",
                new AnswerRequest("답", 3, List.of(in(11, "main"), in(12, "master"))));

        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: ＋ 배치 서버(master)");
        assertThat(r.refNote().getRole()).isEqualTo("system");
        assertThat(r.refNote().getKind()).isEqualTo("note");
        assertThat(r.refNote().getSeq()).isEqualTo(4);
        assertThat(r.outcome().turnSeq()).isEqualTo(3);
        assertThat(s.getRefRepos()).containsExactly(oldApp, batch);
        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);   // 유지되는 참조는 고정 커밋 그대로
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
    }

    @Test
    void submitAnswerWithRefs_clearing_every_ref_notes_it() {
        InterviewSession s = awaitingInterviewWithRefs(1L, ref(11L, "main", SHA_A, "2026-10-24T10:00+09:00", null));

        InterviewService.AnswerResult r = service.submitAnswerWithRefs(1L, "admin",
                new AnswerRequest("답", 2, List.of()));

        assertThat(r.refNote().getContent()).isEqualTo("참조 repo 변경: 없음(전부 해제)");
        assertThat(s.getRefRepos()).isEmpty();
    }

    @Test
    void submitAnswerWithRefs_rejection_propagates_and_adds_no_note() {
        InterviewSession s = awaitingInterviewWithRefs(1L, ref(11L, "main", SHA_A, "2026-10-24T10:00+09:00", null));
        List<RefRepo> before = s.getRefRepos();

        assertThatThrownBy(() -> service.submitAnswerWithRefs(1L, "admin", new AnswerRequest("답", 2,
                List.of(in(11, "main"), in(12, "main"), in(13, "main"), in(14, "main")))))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("참조 repo는 최대 3개까지 고를 수 있습니다");

        verify(turnRepo, never()).save(argThat(t -> "system".equals(t.getRole())));
        assertThat(s.getRefRepos()).isSameAs(before);
        verifyNoInteractions(repoCatalog);
    }

    // ── 질문 세션 참조 repo: 답변 보고 (스펙 2026-10-22 §4.5) ─────────────────

    @Test
    void recordQuestion_pins_sha_only_once() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", SHA_A, null)));

        RefRepo pinned = s.getRefRepos().get(0);
        assertThat(pinned.sha()).isEqualTo(SHA_A);
        assertThat(pinned.fetchedAt()).isNotNull();
        assertThat(java.time.OffsetDateTime.parse(pinned.fetchedAt())).isNotNull();   // ISO-8601 문자열
        assertThat(pinned.error()).isNull();

        // 다음 턴에 다른 커밋을 보고해도 처음 받은 커밋·시각을 유지한다
        nextTurn(s);
        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", SHA_B, null)));

        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
        assertThat(s.getRefRepos().get(0).fetchedAt()).isEqualTo(pinned.fetchedAt());
    }

    @Test
    void recordQuestion_records_and_then_clears_errors() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null, "권한 없음")));

        assertThat(s.getRefRepos().get(0).error()).isEqualTo("권한 없음");
        assertThat(s.getRefRepos().get(0).sha()).isNull();

        nextTurn(s);
        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", SHA_A, null)));

        assertThat(s.getRefRepos().get(0).error()).isNull();
        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
    }

    @Test
    void recordQuestion_clears_a_stale_error_on_an_already_pinned_ref_and_keeps_the_pin() {
        // 처음 받은 뒤 폴더가 사라져 다시 받다 실패 → error만 붙은 상태. 이후 성공 보고는 error만 지우고 고정 커밋은 그대로 둔다
        InterviewSession s = runningQuestionWithRefs(5L,
                ref(11L, "main", SHA_A, "2026-10-22T10:00:00+09:00", "네트워크 오류"));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", SHA_B, null)));

        RefRepo r = s.getRefRepos().get(0);
        assertThat(r.error()).isNull();
        assertThat(r.sha()).isEqualTo(SHA_A);
        assertThat(r.fetchedAt()).isEqualTo("2026-10-22T10:00:00+09:00");
    }

    @Test
    void recordQuestion_error_keeps_an_existing_pin() {
        InterviewSession s = runningQuestionWithRefs(5L,
                ref(11L, "main", SHA_A, "2026-10-22T10:00:00+09:00", null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null, "네트워크 오류")));

        RefRepo r = s.getRefRepos().get(0);
        assertThat(r.error()).isEqualTo("네트워크 오류");
        assertThat(r.sha()).isEqualTo(SHA_A);
        assertThat(r.fetchedAt()).isEqualTo("2026-10-22T10:00:00+09:00");
    }

    @Test
    void recordQuestion_error_wins_over_a_sha_in_the_same_state() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", SHA_A, "일부만 받음")));

        assertThat(s.getRefRepos().get(0).error()).isEqualTo("일부만 받음");
        assertThat(s.getRefRepos().get(0).sha()).isNull();
        assertThat(s.getRefRepos().get(0).fetchedAt()).isNull();
    }

    // ── 보고된 실패 사유는 저장 전에 한 번 더 가리고 500자로 자른다 (방어 겹치기 — 러너가 이미 가리고 200자로 줄여 보낸다) ──

    @Test
    void recordQuestion_masks_credentials_in_a_reported_error_before_storing_it() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null,
                "fatal: unable to access 'https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git/': 403")));

        assertThat(s.getRefRepos().get(0).error())
                .isEqualTo("fatal: unable to access 'https://***@gitlab.hamon.vip/g/p.git/': 403")
                .doesNotContain("glpat-SECRET");
    }

    @Test
    void recordQuestion_masks_every_credential_in_a_multi_line_error() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null,
                "Command failed: git clone https://oauth2:glpat-ONE@gitlab.hamon.vip/g/p.git /x\n"
                        + "fatal: could not read from remote https://user:pw-TWO@gitlab.hamon.vip/g/p.git")));

        assertThat(s.getRefRepos().get(0).error())
                .doesNotContain("glpat-ONE").doesNotContain("pw-TWO")
                .contains("https://***@gitlab.hamon.vip/g/p.git /x")
                .contains("https://***@gitlab.hamon.vip/g/p.git");
    }

    @Test
    void recordQuestion_cuts_an_error_longer_than_500_characters_to_500() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null, "x".repeat(600))));

        assertThat(s.getRefRepos().get(0).error()).hasSize(500).isEqualTo("x".repeat(500));
    }

    @Test
    void recordQuestion_keeps_an_error_of_exactly_500_characters_as_it_is() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null, "y".repeat(500))));

        assertThat(s.getRefRepos().get(0).error()).isEqualTo("y".repeat(500));
    }

    @Test
    void recordQuestion_masks_first_and_cuts_after_so_a_token_at_the_cut_is_not_left_half_visible() {
        // 가리기 전에 500자에서 자르면 `https://oauth2:glpat…`처럼 토큰이 반쪽으로 남고 mask(…@ 까지 있어야 가림)가 알아보지 못한다
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));
        String error = "a".repeat(480) + "https://oauth2:glpat-SECRET@gitlab.hamon.vip/g/p.git";

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null, error)));

        String stored = s.getRefRepos().get(0).error();
        assertThat(stored).hasSize(500).doesNotContain("oauth2").doesNotContain("glpat").doesNotContain("SECRET");
        assertThat(stored).startsWith("a".repeat(480) + "https://***@gitlab");
    }

    @Test
    void recordQuestion_does_not_leave_half_a_surrogate_pair_when_the_cut_splits_one() {
        // 이모지(서로게이트 쌍 2글자)가 499~500번째에 걸치면 앞 절반만 남아 JSONB 직렬화가 깨진다 — 그 반쪽은 버린다
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null, "x".repeat(499) + "😀" + "tail")));

        String stored = s.getRefRepos().get(0).error();
        assertThat(stored).isEqualTo("x".repeat(499));
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 1))).isFalse();
    }

    @Test
    void recordQuestion_keeps_a_whole_surrogate_pair_that_ends_exactly_at_500() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", null, "x".repeat(498) + "😀" + "tail")));

        assertThat(s.getRefRepos().get(0).error()).isEqualTo("x".repeat(498) + "😀");
    }

    @Test
    void recordQuestion_ignores_a_state_with_neither_sha_nor_error() {
        InterviewSession s = runningQuestionWithRefs(5L,
                ref(11L, "main", null, null, "이전 오류"));
        List<RefRepo> before = s.getRefRepos();

        service.recordQuestion(5L, "w1", answerWith(
                new RefState(11L, "main", null, null),
                new RefState(11L, "main", "  ", null)));

        assertThat(s.getRefRepos()).isSameAs(before);   // 바뀐 게 없으면 목록을 갈아 끼우지 않는다
        assertThat(s.getRefRepos().get(0).error()).isEqualTo("이전 오류");
        assertThat(s.getRefRepos().get(0).sha()).isNull();
    }

    @Test
    void recordQuestion_ignores_states_for_refs_no_longer_selected() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));
        List<RefRepo> before = s.getRefRepos();
        RefRepo original = before.get(0);

        service.recordQuestion(5L, "w1", answerWith(new RefState(12L, "main", SHA_A, null)));

        assertThat(s.getRefRepos()).isSameAs(before);
        assertThat(s.getRefRepos()).containsExactly(original);
    }

    @Test
    void recordQuestion_ignores_a_state_for_the_same_repo_on_another_branch() {
        // 같은 카탈로그 id를 다른 브랜치로 바꾸면 "빼고 다시 넣기" — 이전 브랜치 기준 보고가 새 원소를 건드리면 안 된다
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "release/6.5", null, null, null));
        List<RefRepo> before = s.getRefRepos();

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", SHA_A, null)));

        assertThat(s.getRefRepos()).isSameAs(before);
        assertThat(s.getRefRepos().get(0).sha()).isNull();
    }

    @Test
    void recordQuestion_applies_each_state_to_its_own_ref_and_keeps_the_order() {
        InterviewSession s = runningQuestionWithRefs(5L,
                ref(11L, "main", null, null, null),
                ref(12L, "release/6.5", null, null, null),
                ref(13L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(
                new RefState(12L, "release/6.5", null, "브랜치를 찾을 수 없음"),
                new RefState(11L, "main", SHA_A, null)));

        assertThat(s.getRefRepos()).extracting(RefRepo::catalogId).containsExactly(11L, 12L, 13L);
        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
        assertThat(s.getRefRepos().get(1).error()).isEqualTo("브랜치를 찾을 수 없음");
        assertThat(s.getRefRepos().get(2).sha()).isNull();
        assertThat(s.getRefRepos().get(2).error()).isNull();
    }

    @Test
    void recordQuestion_skips_null_state_entries() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        service.recordQuestion(5L, "w1", answerWith(null, new RefState(11L, "main", SHA_A, null)));

        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
    }

    @Test
    void recordQuestion_without_ref_states_leaves_refs_untouched() {
        // 구버전 러너 — 10인자 생성자(refStates = null)
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", SHA_A, "2026-10-22T10:00:00+09:00", "오류"));
        List<RefRepo> before = s.getRefRepos();
        RefRepo original = before.get(0);

        service.recordQuestion(5L, "w1", new WorkerQuestionRequest("답변", "sess-q", "question",
                BigDecimal.ZERO, null, null, null, null, null, null));

        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getRefRepos()).isSameAs(before);
        assertThat(s.getRefRepos()).containsExactly(original);
    }

    @Test
    void recordQuestion_with_an_empty_ref_state_list_leaves_refs_untouched() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));
        List<RefRepo> before = s.getRefRepos();

        service.recordQuestion(5L, "w1", answerWith());

        assertThat(s.getRefRepos()).isSameAs(before);
        assertThat(s.getRefRepos().get(0).sha()).isNull();
    }

    @Test
    void recordQuestion_does_not_replace_the_ref_list_when_the_pin_is_already_there() {
        // 이미 고정됐고 오류도 없으면 아무것도 바뀌지 않는다 — 불필요한 갱신(JSONB 쓰기)을 만들지 않는다
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", SHA_A, "2026-10-22T10:00:00+09:00", null));
        List<RefRepo> before = s.getRefRepos();

        service.recordQuestion(5L, "w1", answerWith(new RefState(11L, "main", SHA_B, null)));

        assertThat(s.getRefRepos()).isSameAs(before);
        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
    }

    @Test
    void recordQuestion_ref_states_do_not_disturb_the_turn_itself() {
        InterviewSession s = runningQuestionWithRefs(5L, ref(11L, "main", null, null, null));

        InterviewTurn turn = service.recordQuestion(5L, "w1", new WorkerQuestionRequest("답변입니다", "sess-q",
                "question", new BigDecimal("0.01"), null, null, null, null, null, null,
                List.of(new RefState(11L, "main", SHA_A, null))));

        assertThat(turn.getContent()).isEqualTo("답변입니다");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getWorkerId()).isNull();
        assertThat(s.getClaudeSessionId()).isEqualTo("sess-q");
        assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0.01");
        assertThat(s.getRefRepos().get(0).sha()).isEqualTo(SHA_A);
    }

    @Test
    void latestSessionForTask_reads_the_latest_session_once_for_its_id_and_a_copy_of_its_refs() {
        InterviewSession s = session(1L, InterviewStatus.RUNNING);
        RefRepo pinned = ref(11L, "main", SHA_A, "2026-10-24T10:00+09:00", null);
        RefRepo failed = ref(12L, "main", null, null, "권한 없음");
        s.setRefRepos(new ArrayList<>(List.of(pinned, failed)));
        when(sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(5L)).thenReturn(Optional.of(s));

        InterviewService.LatestSession latest = service.latestSessionForTask(5L).orElseThrow();
        s.getRefRepos().clear();

        assertThat(latest.id()).isEqualTo(s.getId());
        assertThat(latest.refRepos()).containsExactly(pinned, failed);   // 세션 목록과 따로인 사본
        verify(sessionRepo, times(1)).findTopByTaskIdOrderByCreatedAtDesc(5L);   // 한 번만 읽는다
        s.setRefRepos(null);
        assertThat(service.latestSessionForTask(5L)).get()
                .extracting(InterviewService.LatestSession::refRepos).isEqualTo(List.of());
        assertThat(service.latestSessionForTask(6L)).isEmpty();   // 세션 없음
    }

    @Test
    void createForTask_copies_the_task_refs_to_the_new_session() {
        Task t = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 5L);
        RefRepo pinned = ref(11L, "main", SHA_A, "2026-10-24T10:00+09:00", null);
        RefRepo fresh = ref(12L, "dev", null, null, null);
        t.setRefRepos(new ArrayList<>(List.of(pinned, fresh)));

        service.createForTask(t, "claude-opus-5-5", "high", List.of());

        ArgumentCaptor<InterviewSession> saved = ArgumentCaptor.forClass(InterviewSession.class);
        verify(sessionRepo).save(saved.capture());
        InterviewSession s = saved.getValue();
        assertThat(s.getTaskId()).isEqualTo(5L);
        assertThat(s.getRefRepos()).containsExactly(pinned, fresh);   // 같은 원소
        assertThat(s.getRefRepos()).isNotSameAs(t.getRefRepos());     // 다른 리스트 인스턴스 — 세션 쪽 변경이 작업으로 번지지 않는다
        s.getRefRepos().clear();
        assertThat(t.getRefRepos()).containsExactly(pinned, fresh);
    }

    @Test
    void createForTask_without_task_refs_gives_the_session_an_empty_list() {
        Task t = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), null, null);
        t.setRefRepos(null);

        InterviewSession s = service.createForTask(t, "claude-opus-5-5", "high", List.of());

        assertThat(s.getRefRepos()).isNotNull().isEmpty();
    }

    // ── 질문 대화 전문 — "이 대화로 작업 등록" (스펙 2026-10-24 §4.1) ─────────────────

    @Test
    void questionTranscript_rejects_missing_or_interview_sessions_with_404() {
        when(sessionRepo.findActiveById(1L)).thenReturn(Optional.empty());
        when(sessionRepo.findActiveById(2L)).thenReturn(Optional.of(session(2L, InterviewStatus.AWAITING_INPUT)));

        for (long id : new long[]{1L, 2L}) {
            assertThatThrownBy(() -> service.questionTranscript(id, "u1", true))
                    .isInstanceOfSatisfying(TaskException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verify(turnRepo, never()).findBySessionIdOrderBySeqAsc(any());
    }

    @Test
    void questionTranscript_rejects_other_users_with_403_but_admin_may_read() {
        when(sessionRepo.findActiveById(5L)).thenReturn(Optional.of(questionSession(5L, InterviewStatus.AWAITING_INPUT)));
        when(turnRepo.findBySessionIdOrderBySeqAsc(5L)).thenReturn(List.of());
        when(questionAttachmentRepo.findBySessionIdOrderByIdAsc(5L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.questionTranscript(5L, "u2", false))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.questionTranscript(5L, "admin", true)).startsWith("# 질문 #5 대화 전문 — T\n");
        assertThat(service.questionTranscript(5L, "u1", false)).startsWith("# 질문 #5 대화 전문 — T\n");
    }

    @ParameterizedTest
    @EnumSource(value = InterviewStatus.class, names = {"QUEUED", "RUNNING"})
    void questionTranscript_while_answering_is_409(InterviewStatus st) {
        when(sessionRepo.findActiveById(5L)).thenReturn(Optional.of(questionSession(5L, st)));

        assertThatThrownBy(() -> service.questionTranscript(5L, "u1", false))
                .isInstanceOfSatisfying(TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("답변이 끝난 뒤에 작업으로 등록할 수 있습니다");
        verify(turnRepo, never()).findBySessionIdOrderBySeqAsc(any());
    }

    @Test
    void questionTranscript_of_an_answered_or_closed_question_renders() {
        for (InterviewStatus st : List.of(InterviewStatus.AWAITING_INPUT, InterviewStatus.CANCELLED)) {
            when(sessionRepo.findActiveById(5L)).thenReturn(Optional.of(questionSession(5L, st)));
            when(turnRepo.findBySessionIdOrderBySeqAsc(5L)).thenReturn(List.of(
                    InterviewTurn.of(5L, 0, "assistant", "question", "AuthController에서 처리합니다", null)));
            when(questionAttachmentRepo.findBySessionIdOrderByIdAsc(5L)).thenReturn(List.of(
                    QuestionAttachment.create(5L, null, "spec.pdf", "question-5/create/1-spec.pdf",
                            "application/pdf", 3L, null, "u1")));

            String md = service.questionTranscript(5L, "u1", false);

            assertThat(md).contains("## 첫 질문\n\nq?\n\n첨부: spec.pdf\n")
                    .contains("### 답변 (턴 0)\n\nAuthController에서 처리합니다\n");
        }
    }

    // ── 렌더링 대기 claim · 슬라이드 그림 경로 (스펙 2026-10-27 §7) ─────────────────

    private static final String PPTX = "question-1/create/1-발표.pptx";

    /** 첨부 루트를 임시 폴더로 — resolve/absolutePathOf가 실제 경로를 돌려준다(목록·존재 확인용). */
    private void storageAt(java.nio.file.Path root) {
        when(attachmentStorage.resolve(any())).thenAnswer(i -> root.resolve((String) i.getArgument(0)));
        when(attachmentStorage.absolutePathOf(any()))
                .thenAnswer(i -> root.resolve((String) i.getArgument(0)).toString());
        when(attachmentStorage.renderDirRelativePath(any())).thenAnswer(i -> i.getArgument(0) + ".render");
        when(attachmentStorage.questionRootRelative(1L)).thenReturn("question-1");
    }

    private QuestionAttachment pptxAtt(AttachmentRendition r) {
        QuestionAttachment a = QuestionAttachment.create(1L, null, "발표.pptx", PPTX, null, 10L,
                PPTX + ".txt", "u1", r);
        ReflectionTestUtils.setField(a, "id", 11L);
        return a;
    }

    private InterviewClaimResponse.AttachmentRef claimSingleQuestionAttachment(AttachmentRendition r) {
        InterviewSession s = questionSession(1L, InterviewStatus.QUEUED);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(questionAttachmentRepo.findBySessionIdAndTurnSeqIsNullOrderByIdAsc(1L)).thenReturn(List.of(pptxAtt(r)));
        return service.claim("w1").orElseThrow().attachments().get(0);
    }

    private static AttachmentRendition done(String dir, int pages, int total, int media) {
        AttachmentRendition r = AttachmentRendition.of(RenderStatus.PENDING, null);
        r.markDone(dir, pages, total, media);
        return r;
    }

    @Test
    void DONE_첨부_경로(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        storageAt(root);
        java.nio.file.Path render = java.nio.file.Files.createDirectories(root.resolve(PPTX + ".render"));
        java.nio.file.Files.writeString(render.resolve("slide-001.png"), "1");
        java.nio.file.Files.writeString(render.resolve("slide-002.png"), "2");
        java.nio.file.Path media = java.nio.file.Files.createDirectories(render.resolve("media"));
        java.nio.file.Files.writeString(media.resolve("image2.png"), "b");
        java.nio.file.Files.writeString(media.resolve("image1.jpeg"), "a");

        var ref = claimSingleQuestionAttachment(done(PPTX + ".render", 2, 5, 2));

        assertThat(ref.slideImagePaths()).containsExactly(
                render.resolve("slide-001.png").toString(), render.resolve("slide-002.png").toString());
        assertThat(ref.slideTotalPages()).isEqualTo(5);
        assertThat(ref.mediaImagePaths()).containsExactly(
                media.resolve("image1.jpeg").toString(), media.resolve("image2.png").toString());
        assertThat(ref.renderNote()).isNull();
        assertThat(ref.extractedTextPath()).isEqualTo(root.resolve(PPTX + ".txt").toString());
        assertThat(ref.inheritedFromQuestionId()).isNull();
    }

    @Test
    void DONE인데_첫_슬라이드가_없으면_목록을_비우고_정리됨_안내(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        storageAt(root);   // 폴더 자체가 없다(첨부 정리 잡이 지움)

        var ref = claimSingleQuestionAttachment(done(PPTX + ".render", 3, 3, 0));

        assertThat(ref.slideImagePaths()).isEmpty();
        assertThat(ref.mediaImagePaths()).isEmpty();
        assertThat(ref.renderNote()).isEqualTo("슬라이드 그림 없음: 파일이 정리됨");
    }

    @Test
    void 실패_건너뜀_대기_없음_상태별_안내(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        storageAt(root);

        assertThat(claimSingleQuestionAttachment(AttachmentRendition.of(RenderStatus.FAILED, "변환 시간 초과(120초)"))
                .renderNote()).isEqualTo("슬라이드 그림 없음: 변환 시간 초과(120초)");
        assertThat(claimSingleQuestionAttachment(AttachmentRendition.of(RenderStatus.SKIPPED, "LibreOffice 없음"))
                .renderNote()).isEqualTo("슬라이드 그림 없음: LibreOffice 없음");
        for (RenderStatus st : List.of(RenderStatus.PENDING, RenderStatus.RUNNING)) {
            var ref = claimSingleQuestionAttachment(AttachmentRendition.of(st, null));
            assertThat(ref.renderNote()).isEqualTo("슬라이드 그림 준비 중(대기 시간 초과)");
            assertThat(ref.slideImagePaths()).isEmpty();
        }
        var none = claimSingleQuestionAttachment(AttachmentRendition.of(RenderStatus.NONE, null));
        assertThat(none.renderNote()).isNull();
        assertThat(none.slideImagePaths()).isEmpty();
        assertThat(none.mediaImagePaths()).isEmpty();
        assertThat(none.slideTotalPages()).isNull();
    }

    @Test
    void 작업_첨부는_추출본과_승계한_질문_id를_싣는다(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        storageAt(root);
        InterviewSession s = session(1L, InterviewStatus.QUEUED);
        s.setTaskId(77L);
        Task t = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 77L);
        ReflectionTestUtils.setField(t, "sourceQuestionId", 42L);
        when(taskRepo.findActiveById(77L)).thenReturn(Optional.of(t));
        TaskAttachment inherited = TaskAttachment.create(77L, "발표.pptx", "task-77/1-발표.pptx", null, 10L, "u1",
                "task-77/1-발표.pptx.txt", AttachmentRendition.of(RenderStatus.FAILED, "변환 실패"), 11L);
        ReflectionTestUtils.setField(inherited, "id", 21L);
        TaskAttachment own = TaskAttachment.create(77L, "a.txt", "task-77/2-a.txt", "text/plain", 1L, "u1");
        ReflectionTestUtils.setField(own, "id", 22L);
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of(s));
        when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(attachmentRepo.findByTaskIdOrderByIdAsc(77L)).thenReturn(List.of(inherited, own));

        var refs = service.claim("w1").orElseThrow().attachments();

        assertThat(refs.get(0).extractedTextPath()).isEqualTo(root.resolve("task-77/1-발표.pptx.txt").toString());
        assertThat(refs.get(0).inheritedFromQuestionId()).isEqualTo(42L);
        assertThat(refs.get(0).renderNote()).isEqualTo("슬라이드 그림 없음: 변환 실패");
        assertThat(refs.get(1).extractedTextPath()).isNull();
        assertThat(refs.get(1).inheritedFromQuestionId()).isNull();
        assertThat(refs.get(1).renderNote()).isNull();
    }

    @Test
    void claim은_대기_상한만큼_이전_시각을_cutoff로_넘기고_0이면_지금() {
        when(sessionRepo.findClaimableForUpdateSkipLocked(any(), any())).thenReturn(List.of());
        ArgumentCaptor<java.time.OffsetDateTime> cutoff = ArgumentCaptor.forClass(java.time.OffsetDateTime.class);

        java.time.OffsetDateTime before = java.time.OffsetDateTime.now();
        service.claim("w1");   // 기본 180초
        service.setRenderProperties(new com.hamonsoft.netismaker.config.RenderProperties(
                true, "", List.of("pptx"), 120, 30, 1600, 20, 0));
        service.claim("w1");
        java.time.OffsetDateTime after = java.time.OffsetDateTime.now();

        verify(sessionRepo, times(2)).findClaimableForUpdateSkipLocked(cutoff.capture(), any());
        assertThat(cutoff.getAllValues().get(0))
                .isBetween(before.minusSeconds(180), after.minusSeconds(180));
        assertThat(cutoff.getAllValues().get(1)).isBetween(before, after);
    }

}
