package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.FixRequestKind;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskType;
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

    @Test
    void label_기준브랜치반영() {
        TaskFixRequest r = TaskFixRequest.baseSync(1L, 2, "main", TaskStatus.DEPLOYED, "admin");
        assertThat(FixRounds.label(r)).isEqualTo("기준 브랜치 반영 2회차");
        assertThat(FixRounds.isBaseSync(r)).isTrue();
        assertThat(FixRounds.isOpen(r)).isTrue();
        assertThat(r.getKind()).isEqualTo(FixRequestKind.BASE_SYNC);
        assertThat(r.getBody()).isEqualTo("기준 브랜치 main 반영");
        assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(r.getRequestedBy()).isEqualTo("admin");
        assertThat(r.getDecidedBy()).isEqualTo("admin");
        assertThat(r.getDecidedAt()).isNotNull();
        assertThat(r.getStatus()).isEqualTo(FixRequestStatus.APPROVED);
    }

    @Test
    void label_번호를_모르면_종류별_이름만() {
        TaskFixRequest sync = TaskFixRequest.baseSync(1L, 1, "main", TaskStatus.DEPLOYED, "admin");
        sync.setRound(null);
        assertThat(FixRounds.label(sync)).isEqualTo("기준 브랜치 반영 회차");
        assertThat(FixRounds.label(null)).isEqualTo("수정 회차");
        assertThat(FixRounds.label(approved(1L, 3))).isEqualTo("수정 3회차");
        assertThat(FixRounds.isBaseSync(null)).isFalse();
        assertThat(FixRounds.isBaseSync(approved(1L, 3))).isFalse();
    }

    @Test
    void 기존_pending_행의_kind는_FIX() {
        assertThat(TaskFixRequest.pending(1L, "b", TaskStatus.DEPLOYED, "u").getKind()).isEqualTo(FixRequestKind.FIX);
    }

    @Test
    void baseSyncReturnStatus_배포상태와_PR생성을_그대로_돌려준다() {
        Task t = flagged(1L);
        for (TaskStatus from : List.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, TaskStatus.PR_CREATED)) {
            TaskFixRequest r = TaskFixRequest.baseSync(42L, 1, "main", from, "admin");
            assertThat(FixRounds.baseSyncReturnStatus(r, t)).as(from.name()).isEqualTo(from);
        }
    }

    @Test
    void baseSyncReturnStatus_모르는_값이면_배포한적없는_DB작업은_PR생성_그밖은_배포중단됨() {
        TaskFixRequest r = TaskFixRequest.baseSync(42L, 1, "main", TaskStatus.CLOSE_FAILED, "admin");

        Task dbNever = flagged(1L);
        dbNever.setTaskType(TaskType.DB_WORK);
        assertThat(FixRounds.baseSyncReturnStatus(r, dbNever)).isEqualTo(TaskStatus.PR_CREATED);

        Task dbDeployed = flagged(1L);
        dbDeployed.setTaskType(TaskType.DB_WORK);
        dbDeployed.setDeployUrl("https://task-42.example");
        assertThat(FixRounds.baseSyncReturnStatus(r, dbDeployed)).isEqualTo(TaskStatus.DEPLOY_LOST);

        Task other = flagged(1L);
        other.setTaskType(TaskType.BUG_FIX);
        assertThat(FixRounds.baseSyncReturnStatus(r, other)).isEqualTo(TaskStatus.DEPLOY_LOST);
        assertThat(FixRounds.baseSyncReturnStatus(null, dbNever)).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(FixRounds.baseSyncReturnStatus(null, other)).isEqualTo(TaskStatus.DEPLOY_LOST);
    }
}
