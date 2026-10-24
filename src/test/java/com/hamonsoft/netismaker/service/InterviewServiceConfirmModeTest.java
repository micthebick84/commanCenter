package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 플랜 확정이 고른 구현 방식을 작업에 저장하고 이력 사유에 남긴다 (스펙 2026-10-15 §5.1).
 * 확정 때 세션의 참조 중 고정 커밋이 있는 것만 작업으로 넘기고 이력에 남긴다 (스펙 2026-10-24 §4.6).
 */
class InterviewServiceConfirmModeTest {

    private TaskStatusHistoryRepository historyRepo;
    private InterviewService service;
    private InterviewSession session;
    private Task task;

    @BeforeEach
    void setUp() {
        InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
        InterviewPlanRepository planRepo = mock(InterviewPlanRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new InterviewService(sessionRepo, mock(InterviewTurnRepository.class), planRepo,
                taskRepo, analysisRepo, historyRepo, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class),
                mock(QuestionAttachmentRepository.class),
                new InterviewWorkPaths(System.getProperty("user.home") + "/netis-maker/interviews"));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(analysisRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        session = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(session, "id", 25L);
        session.setStatus(InterviewStatus.PLAN_READY);
        session.setTaskId(13L);
        when(sessionRepo.findByIdForUpdate(25L)).thenReturn(Optional.of(session));

        task = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 13L);
        task.setStatus(TaskStatus.INTERVIEW_REVIEW);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(task));
        when(planRepo.findById(25L)).thenReturn(Optional.of(
                InterviewPlan.create(25L, "# 설계", "# 플랜", "[]", 1000L, null)));
    }

    private String lastHistoryReason() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue().getReason();
    }

    private List<TaskStatusHistory> savedHistory() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    // ── 확정 때 참조 repo 복사 (스펙 2026-10-24 §3.2·§4.6) ─────────────────

    private static final RefRepo OLD_APP_PINNED = new RefRepo(11L, "옛앱", "g/old-app",
            "https://gitlab.hamon.vip/g/old-app.git", "release/6.5", "a".repeat(40), "2026-10-24T10:00+09:00",
            "마지막 턴 실패");
    private static final RefRepo BATCH_PINNED = new RefRepo(13L, "배치 서버", "acme/batch",
            "https://github.com/acme/batch.git", "master", "0123456789abcdef".repeat(4), "2026-10-24T10:05+09:00", null);
    private static final RefRepo COMMON_UI_UNFETCHED = new RefRepo(12L, "공통 UI", "acme/ui",
            "https://github.com/acme/ui.git", "main", null, null, "권한 없음");

    @Test
    void confirm_copies_only_pinned_refs_without_errors_and_logs_them() {
        session.setRefRepos(new ArrayList<>(List.of(OLD_APP_PINNED, COMMON_UI_UNFETCHED)));

        service.confirm(25L, "admin", false, ImplementationMode.DIRECT);

        assertThat(task.getRefRepos()).containsExactly(OLD_APP_PINNED.withError(null));
        assertThat(task.getRefRepos()).isNotSameAs(session.getRefRepos());
        List<TaskStatusHistory> rows = savedHistory();
        // 기존 확정 이력 문구는 그대로, 참조는 그다음 별도 행(같은 상태)
        assertThat(rows).extracting(TaskStatusHistory::getReason).containsExactly(
                "플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 직접)",
                "구현 참조: 옛앱(release/6.5)@aaaaaaa / 제외(인터뷰에서 받지 못함): 공통 UI(main)");
        assertThat(rows.get(1).getFromStatus()).isEqualTo(TaskStatus.APPROVED.dbValue());
        assertThat(rows.get(1).getToStatus()).isEqualTo(TaskStatus.APPROVED.dbValue());
        assertThat(lastHistoryReason())
                .isEqualTo("구현 참조: 옛앱(release/6.5)@aaaaaaa / 제외(인터뷰에서 받지 못함): 공통 UI(main)");
    }

    @Test
    void confirm_with_every_ref_pinned_lists_them_in_order_without_an_exclusion_part() {
        session.setRefRepos(new ArrayList<>(List.of(BATCH_PINNED, OLD_APP_PINNED)));

        service.confirm(25L, "admin", true, ImplementationMode.DIRECT);

        assertThat(task.getRefRepos()).containsExactly(BATCH_PINNED, OLD_APP_PINNED.withError(null));
        assertThat(lastHistoryReason()).isEqualTo("구현 참조: 배치 서버(master)@0123456, 옛앱(release/6.5)@aaaaaaa");
    }

    @Test
    void confirm_with_no_pinned_ref_logs_none() {
        task.setRefRepos(new ArrayList<>(List.of(COMMON_UI_UNFETCHED)));   // 승인 때 값 — 확정이 덮어쓴다
        session.setRefRepos(new ArrayList<>(List.of(COMMON_UI_UNFETCHED)));

        service.confirm(25L, "admin", false, ImplementationMode.DIRECT);

        assertThat(task.getRefRepos()).isNotNull().isEmpty();
        assertThat(lastHistoryReason()).isEqualTo("구현 참조: 없음 / 제외(인터뷰에서 받지 못함): 공통 UI(main)");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);   // 확정은 막지 않는다
    }

    @Test
    void confirm_without_session_refs_empties_task_refs_and_adds_no_row() {
        // 인터뷰 중 모두 뺐으면 승인 때 값이 남지 않게 []로 덮는다 — 참조 없는 작업이면 이력은 지금과 같다
        task.setRefRepos(new ArrayList<>(List.of(OLD_APP_PINNED)));

        service.confirm(25L, "admin", false, ImplementationMode.DIRECT);

        assertThat(task.getRefRepos()).isNotNull().isEmpty();
        verify(historyRepo, times(1)).save(any());
        assertThat(lastHistoryReason()).isEqualTo("플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 직접)");
    }

    @Test
    void confirm_stores_the_chosen_mode_and_names_it_in_history() {
        service.confirm(25L, "admin", false, ImplementationMode.REVIEW);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(task.getImplementationAttempt()).isEqualTo(1);
        assertThat(lastHistoryReason()).isEqualTo("플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 리뷰)");
    }

    @Test
    void the_design_queue_keeps_the_mode_for_the_later_implementation() {
        service.confirm(25L, "admin", true, ImplementationMode.SDD);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.SDD);
    }

    @Test
    void the_three_argument_confirm_stays_direct() {
        // 새 작업의 기본값이 이미 DIRECT라 그대로 두면 서비스가 쓰지 않아도 통과한다 — 다른 방식에서 시작한다.
        task.setImplementationMode(ImplementationMode.SDD);

        service.confirm(25L, "admin", false);

        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(lastHistoryReason()).endsWith("구현 방식: 직접)");
    }

    @Test
    void a_null_mode_is_stored_as_direct() {
        // 위와 같은 이유로 SDD에서 시작해, null이 정말 DIRECT로 덮어써지는지 본다.
        task.setImplementationMode(ImplementationMode.SDD);

        service.confirm(25L, "admin", false, null);

        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(lastHistoryReason()).endsWith("구현 방식: 직접)");
    }
}
