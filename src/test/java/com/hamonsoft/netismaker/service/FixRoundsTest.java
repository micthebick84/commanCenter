package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 수정 회차 헬퍼 — 열린 회차 판정과 회차 닫기 (스펙 2026-11-03 §4). */
class FixRoundsTest {

    private static TaskFixRequest approved(long id, Integer round) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "본문", TaskStatus.DEPLOYED, "user1");
        ReflectionTestUtils.setField(r, "id", id);
        r.setStatus(FixRequestStatus.APPROVED);
        r.setRound(round);
        r.setImplementationMode(ImplementationMode.REVIEW);
        return r;
    }

    private static Task flagged(Long requestId) {
        Task t = Task.create("a/b", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setActiveFixRequestId(requestId);
        return t;
    }

    @Test
    void 열린_회차는_승인됐고_회차_번호가_있고_아직_닫히지_않은_요청() {
        assertThat(FixRounds.isOpen(approved(1L, 1))).isTrue();
        assertThat(FixRounds.isOpen(null)).isFalse();
        assertThat(FixRounds.isOpen(approved(1L, null))).isFalse();
        TaskFixRequest pending = approved(1L, 1);
        pending.setStatus(FixRequestStatus.PENDING);
        assertThat(FixRounds.isOpen(pending)).isFalse();
        for (FixOutcome outcome : FixOutcome.values()) {
            TaskFixRequest closed = approved(1L, 1);
            closed.setOutcome(outcome);
            assertThat(FixRounds.isOpen(closed)).as(outcome.name()).isFalse();
        }
    }

    @Test
    void closeActive는_열린_회차를_닫아_저장하고_돌려준다() {
        TaskFixRequestRepository repo = mock(TaskFixRequestRepository.class);
        TaskFixRequest r = approved(502L, 2);
        when(repo.findById(502L)).thenReturn(Optional.of(r));
        Task t = flagged(502L);

        TaskFixRequest closed = FixRounds.closeActive(repo, t, FixOutcome.DEPLOYED);

        assertThat(closed).isSameAs(r);
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.DEPLOYED);
        assertThat(r.getClosedAt()).isNotNull();
        assertThat(t.getActiveFixRequestId()).isNull();
        verify(repo).save(r);
        assertThat(FixRounds.closedSuffix(closed)).isEqualTo(" (수정 2회차 닫힘)");
    }

    @Test
    void closeActive는_이미_닫힌_요청의_결과를_덮지_않고_표시만_지운다() {
        TaskFixRequestRepository repo = mock(TaskFixRequestRepository.class);
        TaskFixRequest r = approved(502L, 2);
        r.setOutcome(FixOutcome.ABORTED);
        when(repo.findById(502L)).thenReturn(Optional.of(r));
        Task t = flagged(502L);

        TaskFixRequest closed = FixRounds.closeActive(repo, t, FixOutcome.DEPLOYED);

        assertThat(closed).isNull();
        assertThat(r.getOutcome()).isEqualTo(FixOutcome.ABORTED);
        assertThat(t.getActiveFixRequestId()).isNull();
        verify(repo, never()).save(any());
        assertThat(FixRounds.closedSuffix(closed)).isEqualTo(" (수정 회차 닫힘)");
    }

    @Test
    void 짧은_커밋은_앞_7자() {
        assertThat(FixRounds.shortSha("0123456789abcdef")).isEqualTo("0123456");
        assertThat(FixRounds.shortSha(" new9999 ")).isEqualTo("new9999");
        assertThat(FixRounds.shortSha(null)).isEmpty();
    }
}
