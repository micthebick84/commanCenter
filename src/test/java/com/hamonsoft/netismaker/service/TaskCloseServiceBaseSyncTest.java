package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.FixRequestKind;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 종료실패(병합 전)에서 "기준 브랜치 반영" 회차 열기 — {@link TaskCloseService#syncBase}. */
class TaskCloseServiceBaseSyncTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskFixRequestRepository fixRepo;
    private TaskCloseService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        fixRepo = mock(TaskFixRequestRepository.class);
        when(fixRepo.save(any(TaskFixRequest.class))).thenAnswer(i -> {
            TaskFixRequest r = i.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 7L);
            return r;
        });
        service = new TaskCloseService(taskRepo, historyRepo, mock(RepoCatalogRepository.class),
                new CloseArtifactProperties(false, null), new ClosePatchProperties(false, null), fixRepo, 3);
    }

    /** 종료실패·병합 전·열린 회차 없음인 기본 작업. 직전 상태 이력은 배포완료 → 종료승인대기. */
    private Task failedTask() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.CLOSE_FAILED);
        t.setCloseProgress(null);
        t.setFailureReason("충돌: …");
        t.setClosePatchMode("REQUIRED");
        t.setHeadBranch("netismaker/task-42-x");
        t.setHeadSha("8269d0f");
        t.setImplementationLog("이전 구현 로그");
        t.setImplementationTimeoutMinutes(45);
        t.setRestartRequeueCount(2);
        t.setWorkerId("w");
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        returnHistory(TaskStatus.DEPLOYED);
        return t;
    }

    private void returnHistory(TaskStatus beforeReview) {
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(eq(42L), any())).thenReturn(List.of(
                TaskStatusHistory.log(42L, beforeReview, TaskStatus.CLOSE_REVIEW, "user", "user1", "종료 요청")));
    }

    private TaskFixRequest savedRound() {
        ArgumentCaptor<TaskFixRequest> c = ArgumentCaptor.forClass(TaskFixRequest.class);
        verify(fixRepo).save(c.capture());
        return c.getValue();
    }

    private void assertConflict(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TaskException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo(message);
        });
        verify(fixRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 종료실패_병합전이면_회차를_열고_구현대기로() {
        failedTask();
        when(fixRepo.countByTaskIdAndKind(42L, FixRequestKind.BASE_SYNC)).thenReturn(0L);

        Task t = service.syncBase(42L, "admin");

        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.getActiveFixRequestId()).isEqualTo(7L);
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getCloseProgress()).isNull();
        assertThat(t.getClosePatchMode()).isNull();
        assertThat(t.getImplementationLog()).isNull();
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
        assertThat(t.getRestartRequeueCount()).isZero();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getHeadSha()).isEqualTo("8269d0f");
        assertThat(t.getHeadBranch()).isEqualTo("netismaker/task-42-x");

        TaskFixRequest r = savedRound();
        assertThat(r.getKind()).isEqualTo(FixRequestKind.BASE_SYNC);
        assertThat(r.getRound()).isEqualTo(1);
        assertThat(r.getFromStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(r.getStatus()).isEqualTo(FixRequestStatus.APPROVED);
        assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(r.getBody()).isEqualTo("기준 브랜치 main 반영");

        ArgumentCaptor<TaskStatusHistory> h = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(h.capture());
        assertThat(h.getValue().getFromStatus()).isEqualTo(TaskStatus.CLOSE_FAILED.dbValue());
        assertThat(h.getValue().getToStatus()).isEqualTo(TaskStatus.APPROVED.dbValue());
        assertThat(h.getValue().getReason()).isEqualTo("종료 취소(병합 전) → 기준 브랜치 반영 1회차 → 구현대기");
    }

    @Test
    void 병합된_뒤에는_409() {
        Task t = failedTask();
        t.setCloseProgress("MERGED");
        assertConflict(() -> service.syncBase(42L, "admin"),
                "이미 병합되어 기준 브랜치를 반영할 수 없습니다 — 재시도로 마무리하세요");
    }

    @Test
    void 종료실패가_아니면_409() {
        Task t = failedTask();
        t.setStatus(TaskStatus.DEPLOYED);
        assertConflict(() -> service.syncBase(42L, "admin"),
                "종료실패 상태에서만 기준 브랜치를 반영할 수 있습니다 (현재: 배포완료)");
    }

    @Test
    void 열린_회차가_있으면_409() {
        Task t = failedTask();
        t.setActiveFixRequestId(5L);
        assertConflict(() -> service.syncBase(42L, "admin"), "이미 진행 중인 회차가 있습니다");
    }

    @Test
    void 브랜치_정보가_없거나_기준_브랜치와_같으면_409() {
        Task t = failedTask();
        t.setHeadSha(null);
        assertConflict(() -> service.syncBase(42L, "admin"), "반영할 작업 브랜치·커밋 정보가 없습니다");

        t.setHeadSha("8269d0f");
        t.setHeadBranch(null);
        assertConflict(() -> service.syncBase(42L, "admin"), "반영할 작업 브랜치·커밋 정보가 없습니다");

        t.setHeadBranch("main");
        assertConflict(() -> service.syncBase(42L, "admin"), "작업 브랜치가 기준 브랜치(main)와 같습니다");
    }

    @Test
    void 상한_3회를_다_쓰면_409() {
        failedTask();
        when(fixRepo.countByTaskIdAndKind(42L, FixRequestKind.BASE_SYNC)).thenReturn(3L);
        assertConflict(() -> service.syncBase(42L, "admin"),
                "기준 브랜치 반영은 작업당 3회까지입니다 — GitLab에서 직접 해결하세요");
    }

    @Test
    void 두번째_회차의_번호는_2() {
        failedTask();
        when(fixRepo.countByTaskIdAndKind(42L, FixRequestKind.BASE_SYNC)).thenReturn(1L);

        service.syncBase(42L, "admin");

        assertThat(savedRound().getRound()).isEqualTo(2);
        ArgumentCaptor<TaskStatusHistory> h = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(h.capture());
        assertThat(h.getValue().getReason()).isEqualTo("종료 취소(병합 전) → 기준 브랜치 반영 2회차 → 구현대기");
    }

    @Test
    void DB작업_PR생성에서_종료하던_작업은_fromStatus가_PR생성() {
        failedTask();
        returnHistory(TaskStatus.PR_CREATED);
        when(fixRepo.countByTaskIdAndKind(42L, FixRequestKind.BASE_SYNC)).thenReturn(0L);

        service.syncBase(42L, "admin");

        assertThat(savedRound().getFromStatus()).isEqualTo(TaskStatus.PR_CREATED);
    }
}
