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
        return j;
    }

    private InterviewSession session(long id, InterviewStatus status, OffsetDateTime claimedAt,
                                     OffsetDateTime lastActivity) {
        InterviewSession s = InterviewSession.create("o/r", "main", "T", "d", "u1", List.of());
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
