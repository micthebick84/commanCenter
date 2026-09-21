package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * dead-letter 재전송 잡 단위 테스트 — 엔트리별 1회 시도 / 4xx 거부 / 연결오류 사이클 중단 판정.
 * HTTP 실호출 없이 mock으로 검증한다.
 */
class DeadLetterReplayJobTest {

    private static final WorkerResultRequest REQ = new WorkerResultRequest(
            "mac-worker-1", TaskStatus.COMPLETED, "## 분석", "[]", null, 1L, null,
            null, null, null, null, null,
            null, null, null, null, null,
            null, null, null, null, null);

    private WorkerHttpClient http;
    private SilentLossTracker tracker;
    private DeadLetterReplayJob job;

    private static SilentLossTracker.PendingEntry entry(long taskId, int attempts) {
        return new SilentLossTracker.PendingEntry(taskId, REQ, attempts, "{\"taskId\":" + taskId + "}");
    }

    @BeforeEach
    void setUp() {
        http = mock(WorkerHttpClient.class);
        tracker = mock(SilentLossTracker.class);
        job = new DeadLetterReplayJob(http, tracker, 3);
    }

    @Test
    void successful_replay_resolves_entry() {
        SilentLossTracker.PendingEntry e = entry(9L, 0);
        when(tracker.snapshotPending()).thenReturn(List.of(e));

        job.replay();

        verify(http).postResult(9L, REQ);
        verify(tracker).resolve(e);
        verify(tracker, never()).reject(any(), anyInt());
    }

    @Test
    void client_error_rejects_entry_with_max_attempts() {
        SilentLossTracker.PendingEntry e = entry(9L, 1);
        when(tracker.snapshotPending()).thenReturn(List.of(e));
        doThrow(new HttpClientErrorException(HttpStatus.CONFLICT))
                .when(http).postResult(eq(9L), any());

        job.replay();

        verify(tracker).reject(e, 3);
        verify(tracker, never()).resolve(any());
    }

    @Test
    void connection_error_aborts_cycle_without_touching_remaining_entries() {
        SilentLossTracker.PendingEntry first = entry(9L, 0);
        SilentLossTracker.PendingEntry second = entry(10L, 0);
        when(tracker.snapshotPending()).thenReturn(List.of(first, second));
        doThrow(new ResourceAccessException("connection refused"))
                .when(http).postResult(anyLong(), any());

        job.replay();

        // 첫 엔트리에서 중단 — 두 번째 엔트리는 시도조차 하지 않는다 (다운된 백엔드 연타 방지)
        verify(http, times(1)).postResult(anyLong(), any());
        verify(http, never()).postResult(eq(10L), any());
        verify(tracker, never()).resolve(any());
        verify(tracker, never()).reject(any(), anyInt());
    }

    /**
     * 취약 버전이 남긴 dead-letter 라인은 마스킹되지 않은 토큰을 담고 있을 수 있다.
     * 재전송은 reportTerminal을 경유하지 않는 **두 번째 egress**이므로 여기서도 가린다
     * (mask는 멱등 — 이미 가려진 라인에는 영향 없음).
     */
    @Test
    void legacy_entry_is_masked_before_being_replayed() {
        WorkerResultRequest legacy = new WorkerResultRequest(
                "mac-worker-1", TaskStatus.IMPLEMENTATION_FAILED, null, null, null, 1L,
                "commit/push 실패: https://oauth2:glpat-X@gitlab.hamon.vip/g/p.git",
                null, null, null, null,
                "log: https://oauth2:glpat-X@gitlab.hamon.vip/g/p.git",
                null, null, null, null, null,
                null, null, null, null, null);
        SilentLossTracker.PendingEntry e =
                new SilentLossTracker.PendingEntry(9L, legacy, 0, "{\"taskId\":9}");
        when(tracker.snapshotPending()).thenReturn(List.of(e));

        job.replay();

        ArgumentCaptor<WorkerResultRequest> sent = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(http).postResult(eq(9L), sent.capture());
        assertThat(sent.getValue().failureReason())
                .contains("https://***@gitlab.hamon.vip").doesNotContain("glpat-X");
        assertThat(sent.getValue().implementationLog()).doesNotContain("glpat-X");
        verify(tracker).resolve(e);
    }

    @Test
    void empty_pending_makes_no_http_calls() {
        when(tracker.snapshotPending()).thenReturn(List.of());

        job.replay();

        verifyNoInteractions(http);
    }
}
