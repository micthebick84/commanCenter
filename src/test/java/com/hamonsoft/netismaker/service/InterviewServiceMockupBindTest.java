package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 인터뷰 목업 묶기·버리기 (스펙 2026-11-03 §5) — 턴이 질문·계획으로 끝나면 안 묶인 목업을 그 턴에 묶고,
 * 세션이 RUNNING을 떠나 같은 턴이 끝나지 못하는 다섯 경로(재큐·실패·취소·만료·작업 삭제)에서는 버린다.
 * 하네스는 {@code InterviewServiceTest}의 {@code session(id, status)}와 같다.
 */
class InterviewServiceMockupBindTest {

    InterviewSessionRepository sessionRepo;
    InterviewTurnRepository turnRepo;
    InterviewPlanRepository planRepo;
    TaskRepository taskRepo;
    TaskStatusHistoryRepository historyRepo;
    InterviewMockupService mockupService;
    InterviewService service;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(InterviewSessionRepository.class);
        turnRepo = mock(InterviewTurnRepository.class);
        planRepo = mock(InterviewPlanRepository.class);
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = newService();
        mockupService = mock(InterviewMockupService.class);
        service.setInterviewMockupService(mockupService);
        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(turnRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(planRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private InterviewService newService() {
        return new InterviewService(sessionRepo, turnRepo, planRepo,
                taskRepo, mock(TaskAnalysisRepository.class), historyRepo, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class),
                mock(QuestionAttachmentRepository.class),
                new InterviewWorkPaths(System.getProperty("user.home") + "/netis-maker/interviews"));
    }

    private InterviewSession session(long id, InterviewStatus status) {
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(status);
        when(sessionRepo.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        return s;
    }

    private InterviewSession running(long id) {
        InterviewSession s = session(id, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        s.setClaimedAt(OffsetDateTime.now());
        return s;
    }

    private static WorkerQuestionRequest question(String kind) {
        return new WorkerQuestionRequest("어떤 화면인가요? 목업 1을 보세요", "sess-1", kind, BigDecimal.ZERO,
                null, null, null, null, null, null);
    }

    private static WorkerPlanRequest plan() {
        return new WorkerPlanRequest("# 설계", "# 플랜 — 목업 1을 따른다", "[]", BigDecimal.ZERO,
                null, null, null, null, 1000L);
    }

    @Test
    void 질문_보고가_NULL_목업을_그_질문_턴에_묶는다() {
        running(5L);
        when(turnRepo.findMaxSeq(5L)).thenReturn(6);

        InterviewTurn turn = service.recordQuestion(5L, "w1", question("question"));

        assertThat(turn.getSeq()).isEqualTo(7);
        verify(mockupService).bindUnbound(5L, 7, "question");
        verify(mockupService, never()).discardUnbound(anyLong());
    }

    @Test
    void design_kind_질문_보고는_design으로() {
        running(5L);
        when(turnRepo.findMaxSeq(5L)).thenReturn(6);

        service.recordQuestion(5L, "w1", question("design"));

        verify(mockupService).bindUnbound(5L, 7, "design");
    }

    @Test
    void kind가_비면_question_턴으로_묶는다() {
        running(5L);
        when(turnRepo.findMaxSeq(5L)).thenReturn(6);

        service.recordQuestion(5L, "w1", question(null));

        verify(mockupService).bindUnbound(5L, 7, "question");
    }

    @Test
    void 계획_보고는_design_턴_seq로() {
        running(5L);
        when(turnRepo.findMaxSeq(5L)).thenReturn(8);

        service.recordPlan(5L, "w1", plan());

        verify(mockupService).bindUnbound(5L, 9, "design");
        verify(mockupService, never()).discardUnbound(anyLong());
    }

    @Test
    void 질문세션은_묶지_않는다() {
        InterviewSession q = InterviewSession.createQuestion("owner/repo", "main", "T", "q?", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(q, "id", 5L);
        q.setStatus(InterviewStatus.RUNNING);
        q.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(5L)).thenReturn(Optional.of(q));
        when(turnRepo.findMaxSeq(5L)).thenReturn(6);

        service.recordQuestion(5L, "w1", question("question"));
        verify(mockupService, never()).bindUnbound(anyLong(), anyInt(), any());

        // 질문 세션은 목업을 받지 않으므로(업로드 404) 끝날 때 버릴 것도 없다 — 버리기도 부르지 않는다
        q.setStatus(InterviewStatus.RUNNING);
        q.setWorkerId("w1");
        service.failFromWorker(5L, "w1", "타임아웃");
        verify(mockupService, never()).discardUnbound(anyLong());
    }

    @Test
    void 다섯_종결_경로가_NULL_목업을_버린다() {
        // ① 재기동 재큐 (RUNNING → QUEUED)
        running(5L);
        assertThat(service.releaseForRestart(5L, "w1").orElseThrow().released()).isTrue();
        verifyDiscardedOnce();

        // ② doFail — 재기동 상한 소진(RUNNING → FAILED)
        running(5L).setRestartRequeueCount(InterviewService.MAX_RESTART_RELEASES);
        assertThat(service.releaseForRestart(5L, "w1").orElseThrow().released()).isFalse();
        verifyDiscardedOnce();

        // ② doFail — 워커 실패 보고·시스템(스윕) 실패
        running(5L);
        service.failFromWorker(5L, "w1", "SDK 턴 타임아웃");
        verifyDiscardedOnce();
        running(5L);
        service.fail(5L, "system", "stale RUNNING 회수");
        verifyDiscardedOnce();

        // ③ 사용자 취소
        running(5L);
        service.cancel(5L, "u1", false);
        verifyDiscardedOnce();

        // ④ 만료
        session(5L, InterviewStatus.AWAITING_INPUT);
        service.expire(5L, "idle TTL 초과");
        verifyDiscardedOnce();

        // ⑤ 작업 삭제로 열린 세션 닫기
        InterviewSession open = running(5L);
        when(sessionRepo.findOpenByTaskId(77L)).thenReturn(List.of(open));
        service.closeOpenSessionsForTask(77L, "admin");
        verifyDiscardedOnce();

        verify(mockupService, never()).bindUnbound(anyLong(), anyInt(), any());
    }

    private void verifyDiscardedOnce() {
        verify(mockupService).discardUnbound(5L);
        clearInvocations(mockupService);
    }

    @Test
    void 정합_대상이_아니면_버리지_않는다() {
        // 다른 워커가 잡은 세션 — releaseForRestart는 아무것도 바꾸지 않는다
        running(5L).setWorkerId("w2");
        assertThat(service.releaseForRestart(5L, "w1")).isEmpty();
        verify(mockupService, never()).discardUnbound(anyLong());
    }

    @Test
    void mockupSummaries는_목업_서비스_목록을_돌려준다() {
        InterviewMockupSummary m = new InterviewMockupSummary(1L, 1, "list-a", 1, "목록 A안", null,
                7, "question", OffsetDateTime.now());
        when(mockupService.list(5L)).thenReturn(List.of(m));

        assertThat(service.mockupSummaries(5L)).containsExactly(m);
    }

    @Test
    void 목업_서비스가_없으면_묶기_버리기_조회_모두_하지_않는다() {
        // setter를 부르지 않은 구성(기존 단위 시험·목업 서비스가 없는 컨텍스트) — 보고·종결이 지금처럼 끝나고 조회는 빈 목록
        InterviewService bare = newService();
        InterviewSession s = running(5L);
        when(turnRepo.findMaxSeq(5L)).thenReturn(6);
        bare.recordQuestion(5L, "w1", question("question"));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        s = running(5L);
        bare.recordPlan(5L, "w1", plan());
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
        s = running(5L);
        bare.failFromWorker(5L, "w1", "타임아웃");
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.FAILED);

        assertThat(bare.mockupSummaries(5L)).isEmpty();
    }
}
