package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InterviewStaleRecoveryJobTest {

    private final InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
    private final InterviewService interviewService = mock(InterviewService.class);
    private final InterviewStreamService stream = mock(InterviewStreamService.class);

    private InterviewStaleRecoveryJob job() {
        InterviewStaleRecoveryJob j = new InterviewStaleRecoveryJob(sessionRepo, interviewService, stream);
        ReflectionTestUtils.setField(j, "idleTtlMinutes", 1440);
        ReflectionTestUtils.setField(j, "staleRunningMinutes", 60);
        ReflectionTestUtils.setField(j, "workerDeadThresholdSeconds", 60);
        ReflectionTestUtils.setField(j, "queuedTtlMinutes", 60L);
        return j;
    }

    private InterviewSession session(long id, InterviewStatus status, OffsetDateTime claimedAt,
                                     OffsetDateTime lastActivity) {
        InterviewSession s = InterviewSession.create("o/r", "main", "T", "d", "u1", List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(status);
        s.setClaimedAt(claimedAt);
        s.setLastActivityAt(lastActivity);
        return s;
    }

    @Test
    void running_with_stale_claimed_at_is_failed() {
        OffsetDateTime old = OffsetDateTime.now().minusMinutes(120);
        InterviewSession s = session(1L, InterviewStatus.RUNNING, old, old);
        when(sessionRepo.findStaleRunning(any())).thenReturn(List.of(s));
        when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of());

        job().recover();

        verify(interviewService).fail(eq(1L), eq("stale-recovery"), contains("초과"));
        verify(stream).pushStatus(1L, InterviewStatus.FAILED);
        verify(stream).finish(1L);
    }

    @Test
    void awaiting_input_past_idle_ttl_is_expired() {
        OffsetDateTime old = OffsetDateTime.now().minusMinutes(2000);
        InterviewSession s = session(2L, InterviewStatus.AWAITING_INPUT, null, old);
        when(sessionRepo.findStaleRunning(any())).thenReturn(List.of());
        when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of(s));

        job().recover();

        verify(interviewService).expire(eq(2L), contains("idle"));
        verify(stream).pushStatus(2L, InterviewStatus.EXPIRED);
        verify(stream).finish(2L);
    }

    @Test
    void queued_past_ttl_is_expired() {
        // 인터뷰 서비스가 죽어 있으면 세션이 QUEUED에 무기한 체류 — TTL 스윕이 만료시켜야 한다.
        OffsetDateTime old = OffsetDateTime.now().minusMinutes(120);
        InterviewSession s = session(5L, InterviewStatus.QUEUED, null, old);
        when(sessionRepo.findStaleRunning(any())).thenReturn(List.of());
        when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of());
        when(sessionRepo.findStaleQueued(any())).thenReturn(List.of(s));

        job().recover();

        verify(interviewService).expire(eq(5L), contains("인터뷰대기"));
        verify(stream).pushStatus(5L, InterviewStatus.EXPIRED);
        verify(stream).finish(5L);
    }

    @Test
    void fresh_queued_is_untouched_and_cutoff_honors_ttl() {
        // 신선한 QUEUED는 후보 조회에 안 잡힌다 — 스윕은 아무것도 하지 않아야 하고,
        // 후보 조회 cutoff는 now - queuedTtlMinutes(60분)여야 한다.
        when(sessionRepo.findStaleRunning(any())).thenReturn(List.of());
        when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of());
        when(sessionRepo.findStaleQueued(any())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now();
        job().recover();
        OffsetDateTime after = OffsetDateTime.now();

        org.mockito.ArgumentCaptor<OffsetDateTime> cutoff =
                org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sessionRepo).findStaleQueued(cutoff.capture());
        org.assertj.core.api.Assertions.assertThat(cutoff.getValue())
                .isAfterOrEqualTo(before.minusMinutes(60))
                .isBeforeOrEqualTo(after.minusMinutes(60));
        verify(interviewService, never()).expire(any(), any());
        verifyNoInteractions(stream);
    }

    @Test
    void one_conflicting_queued_session_does_not_abort_the_sweep() {
        // 스윕 조회와 전이 사이에 claim이 선점하면 expire가 conflict를 던진다 — 다음 세션은 계속.
        OffsetDateTime old = OffsetDateTime.now().minusMinutes(120);
        InterviewSession a = session(6L, InterviewStatus.QUEUED, null, old);
        InterviewSession b = session(7L, InterviewStatus.QUEUED, null, old);
        when(sessionRepo.findStaleRunning(any())).thenReturn(List.of());
        when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of());
        when(sessionRepo.findStaleQueued(any())).thenReturn(List.of(a, b));
        doThrow(TaskException.conflict("이미 인터뷰중")).when(interviewService).expire(eq(6L), any());

        job().recover();

        verify(interviewService).expire(eq(7L), any()); // 두 번째 세션도 처리됨
        verify(stream, never()).pushStatus(eq(6L), any());
        verify(stream).pushStatus(7L, InterviewStatus.EXPIRED);
    }

    @Test
    void one_failing_session_does_not_abort_the_sweep() {
        OffsetDateTime old = OffsetDateTime.now().minusMinutes(120);
        InterviewSession a = session(1L, InterviewStatus.RUNNING, old, old);
        InterviewSession b = session(3L, InterviewStatus.RUNNING, old, old);
        when(sessionRepo.findStaleRunning(any())).thenReturn(List.of(a, b));
        when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(interviewService).fail(eq(1L), any(), any());

        job().recover();

        verify(interviewService).fail(eq(3L), any(), any()); // 두 번째 세션도 처리됨
    }
}
