package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.FixRequestKind;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskFixRequestRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.hamonsoft.netismaker.entity.FixRequestStatus.APPROVED;
import static com.hamonsoft.netismaker.entity.FixRequestStatus.PENDING;
import static com.hamonsoft.netismaker.entity.TaskStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 수정 요청·승인·반려·철회 (스펙 2026-11-03 §6). Mockito — TaskCloseServiceTest와 같은 구성(작업 id 42).
 * 승인 한도는 생성자 인자 3.
 */
class TaskFixServiceTest {

    private static final String OWNER = "user1";
    private static final String ADMIN = "admin";
    private static final long SAVED_ID = 900L;
    // 정적 import한 APPROVED는 FixRequestStatus 쪽이라 작업 상태 구현대기(TaskStatus.APPROVED)는 따로 부른다
    private static final TaskStatus APPROVED_STATUS = TaskStatus.APPROVED;

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskFixRequestRepository fixRepo;
    private TaskFixService svc;

    private Task task(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", OWNER, 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        t.setHeadBranch("netismaker/task-42-x");
        t.setHeadSha("abc1234abc1234abc1234abc1234abc1234abcd");
        t.setPrNumber(7);
        t.setPrUrl("https://github.com/owner/repo/pull/7");
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        return t;
    }

    /** 승인 대기 중인 요청 하나를 저장소가 돌려주게 한다. */
    private TaskFixRequest pending(TaskStatus from) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "장기분석 화면 로딩이 끝나지 않음", from, OWNER);
        ReflectionTestUtils.setField(r, "id", 77L);
        when(fixRepo.findFirstByTaskIdAndStatusOrderByIdDesc(42L, PENDING)).thenReturn(Optional.of(r));
        return r;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        fixRepo = mock(TaskFixRequestRepository.class);
        // IDENTITY 채번 흉내 — 저장하면 id가 채워진다
        when(fixRepo.save(any())).thenAnswer(i -> {
            TaskFixRequest r = i.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", SAVED_ID);
            return r;
        });
        svc = new TaskFixService(taskRepo, historyRepo, fixRepo, 3);
    }

    private TaskStatusHistory savedHistory() {
        ArgumentCaptor<TaskStatusHistory> c = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(c.capture());
        return c.getValue();
    }

    private TaskFixRequest savedFix() {
        ArgumentCaptor<TaskFixRequest> c = ArgumentCaptor.forClass(TaskFixRequest.class);
        verify(fixRepo).save(c.capture());
        return c.getValue();
    }

    // ── 요청 ─────────────────────────────────────────────

    @Test
    void 등록자_요청은_수정승인대기로() {
        task(DEPLOYED);
        Task out = svc.request(42L, OWNER, false, "장기분석 화면 로딩이 끝나지 않음", null);
        assertThat(out.getStatus()).isEqualTo(FIX_REVIEW);
        verify(fixRepo).save(argThat(r -> r.getStatus() == PENDING && r.getFromStatus() == DEPLOYED));
        assertThat(out.getActiveFixRequestId()).isNull();        // 회차는 승인 때 연다
        TaskStatusHistory h = savedHistory();
        assertThat(h.getToStatus()).isEqualTo("수정승인대기");
        assertThat(h.getReason()).isEqualTo("수정 요청 → 수정승인대기");
        assertThat(h.getActorType()).isEqualTo("user");
    }

    @Test
    void 등록자가_고른_구현_방식은_무시된다() {
        task(DEPLOYED);
        svc.request(42L, OWNER, false, "본문", "DIRECT");
        assertThat(savedFix().getImplementationMode()).isNull();   // 방식은 관리자가 승인 때 고른다
    }

    @Test
    void 관리자_요청은_바로_구현대기_1회차_리뷰() {
        Task t = task(DEPLOYED);
        t.setWorkerId("w");
        t.setFailureReason("이전 사유");
        Task out = svc.request(42L, ADMIN, true, "장기분석 화면 로딩이 끝나지 않음", null);

        assertThat(out.getStatus()).isEqualTo(APPROVED_STATUS);
        assertThat(out.getActiveFixRequestId()).isEqualTo(SAVED_ID);
        assertThat(out.getWorkerId()).isNull();
        TaskFixRequest r = savedFix();
        assertThat(r.getStatus()).isEqualTo(APPROVED);
        assertThat(r.getRound()).isEqualTo(1);
        assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(r.getFromStatus()).isEqualTo(DEPLOYED);
        assertThat(r.getDecidedBy()).isEqualTo(ADMIN);
        assertThat(r.getDecidedAt()).isNotNull();
        TaskStatusHistory h = savedHistory();
        assertThat(h.getFromStatus()).isEqualTo("배포완료");
        assertThat(h.getToStatus()).isEqualTo("구현대기");
        assertThat(h.getReason()).isEqualTo("수정 1회차 승인 (구현 방식: 리뷰) → 구현대기");
    }

    @Test
    void 관리자는_직접_방식을_고를_수_있다() {
        task(DEPLOYED);
        svc.request(42L, ADMIN, true, "본문", "DIRECT");
        assertThat(savedFix().getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(savedHistory().getReason()).isEqualTo("수정 1회차 승인 (구현 방식: 직접) → 구현대기");
    }

    @Test
    void 관리자_요청의_회차는_이미_승인된_수_더하기_1() {
        task(DEPLOYED);
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(2L);
        svc.request(42L, ADMIN, true, "본문", null);
        assertThat(savedFix().getRound()).isEqualTo(3);
    }

    @Test
    void 수정요청_상한은_반영회차를_세지_않는다() {
        task(DEPLOYED);
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(2L);
        svc.request(42L, ADMIN, true, "본문", null);
        assertThat(savedFix().getRound()).isEqualTo(3);
        verify(fixRepo, never()).countByTaskIdAndStatus(any(), any());
    }

    @Test
    void 배포실패에서도_요청된다() {
        task(DEPLOY_FAILED);
        assertThat(svc.request(42L, OWNER, false, "본문", null).getStatus()).isEqualTo(FIX_REVIEW);
        verify(fixRepo).save(argThat(r -> r.getFromStatus() == DEPLOY_FAILED));
    }

    @Test
    void 배포중단됨에서도_요청된다() {
        task(DEPLOY_LOST);
        assertThat(svc.request(42L, OWNER, false, "본문", null).getStatus()).isEqualTo(FIX_REVIEW);
    }

    @Test
    void PR생성_상태는_409() {
        task(PR_CREATED);
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("배포완료");
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 브랜치_없으면_409() {
        Task t = task(DEPLOYED);
        t.setHeadBranch(null);
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("브랜치");
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 이어_고칠_브랜치가_베이스_브랜치면_409() {
        // 회차는 head_branch에 이어 push한다 — 베이스(main)를 가리키면 베이스에 직접 커밋하게 된다. 등록자·관리자 모두
        for (boolean admin : new boolean[]{false, true}) {
            Task t = task(DEPLOYED);
            t.setHeadBranch(" main ");
            assertThatThrownBy(() -> svc.request(42L, admin ? ADMIN : OWNER, admin, "본문", null))
                    .as(admin ? "관리자" : "등록자")
                    .isInstanceOf(TaskException.class)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                    .hasMessageContaining("베이스 브랜치(main)");
            assertThat(t.getStatus()).isEqualTo(DEPLOYED);
            assertThat(t.getActiveFixRequestId()).isNull();
        }
        verify(fixRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 커밋_정보가_없어도_409() {
        Task t = task(DEPLOYED);
        t.setHeadSha(" ");
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 열린_회차가_있으면_409() {
        Task t = task(DEPLOYED);
        t.setActiveFixRequestId(5L);
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("진행 중");
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 수정승인대기에서_다시_요청하면_409() {
        task(FIX_REVIEW);
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> svc.request(42L, ADMIN, true, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(fixRepo, never()).save(any());   // 요청 기록은 늘지 않는다
    }

    @Test
    void 승인_3회_뒤에는_409_새작업_안내() {
        task(DEPLOYED);
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(3L);
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("새 작업");
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 승인_2회까지는_더_요청할_수_있다() {
        task(DEPLOYED);
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(2L);
        assertThat(svc.request(42L, OWNER, false, "본문", null).getStatus()).isEqualTo(FIX_REVIEW);
    }

    @Test
    void SDD는_400() {
        task(DEPLOYED);
        assertThatThrownBy(() -> svc.request(42L, ADMIN, true, "본문", "SDD"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 남의_작업은_403() {
        task(DEPLOYED);
        assertThatThrownBy(() -> svc.request(42L, "someone", false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 삭제된_작업은_404() {
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "본문", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void 본문이_비면_400() {
        task(DEPLOYED);
        for (String blank : new String[]{null, "", "   \n"}) {
            assertThatThrownBy(() -> svc.request(42L, OWNER, false, blank, null))
                    .isInstanceOf(TaskException.class)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 본문은_20000자까지() {
        task(DEPLOYED);
        assertThat(svc.request(42L, OWNER, false, "가".repeat(20_000), null).getStatus()).isEqualTo(FIX_REVIEW);
        assertThatThrownBy(() -> svc.request(42L, OWNER, false, "가".repeat(20_001), null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 승인 ─────────────────────────────────────────────

    @Test
    void 승인은_회차를_승인수_더하기_1로() {
        Task t = task(FIX_REVIEW);
        TaskFixRequest r = pending(DEPLOY_LOST);
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(1L);

        Task out = svc.approve(42L, ADMIN, "DIRECT");

        assertThat(out.getStatus()).isEqualTo(APPROVED_STATUS);
        assertThat(r.getStatus()).isEqualTo(APPROVED);
        assertThat(r.getRound()).isEqualTo(2);
        assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(r.getDecidedBy()).isEqualTo(ADMIN);
        assertThat(r.getDecidedAt()).isNotNull();
        assertThat(t.getActiveFixRequestId()).isEqualTo(77L);
        TaskStatusHistory h = savedHistory();
        assertThat(h.getFromStatus()).isEqualTo("수정승인대기");
        assertThat(h.getToStatus()).isEqualTo("구현대기");
        assertThat(h.getReason()).isEqualTo("수정 2회차 승인 (구현 방식: 직접) → 구현대기");
    }

    @Test
    void 승인_방식을_안_고르면_리뷰() {
        task(FIX_REVIEW);
        TaskFixRequest r = pending(DEPLOYED);
        svc.approve(42L, ADMIN, null);
        assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(savedHistory().getReason()).isEqualTo("수정 1회차 승인 (구현 방식: 리뷰) → 구현대기");
    }

    @Test
    void 승인은_직전_구현의_흔적만_비우고_브랜치와_PR은_그대로() {
        Task t = task(FIX_REVIEW);
        pending(DEPLOYED);
        t.setFailureReason("이전 실패");
        t.setImplementationLog("이전 로그");
        t.setImplementationTimeoutMinutes(45);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now());
        int attemptBefore = t.getImplementationAttempt();
        ImplementationMode modeBefore = t.getImplementationMode();

        svc.approve(42L, ADMIN, null);

        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getImplementationLog()).isNull();
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        // 이어 갈 브랜치·커밋·PR 칸과 작업의 구현 방식·시도 번호는 건드리지 않는다
        assertThat(t.getHeadBranch()).isEqualTo("netismaker/task-42-x");
        assertThat(t.getHeadSha()).isEqualTo("abc1234abc1234abc1234abc1234abc1234abcd");
        assertThat(t.getPrNumber()).isEqualTo(7);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/owner/repo/pull/7");
        assertThat(t.getImplementationAttempt()).isEqualTo(attemptBefore);
        assertThat(t.getImplementationMode()).isEqualTo(modeBefore);
    }

    @Test
    void 승인은_재기동_재큐_횟수를_새_회차로_되돌린다() {
        Task t = task(FIX_REVIEW);
        pending(DEPLOYED);
        t.setRestartRequeueCount(2);
        svc.approve(42L, ADMIN, null);
        assertThat(t.getRestartRequeueCount()).isZero();
    }

    @Test
    void 승인_SDD는_400() {
        task(FIX_REVIEW);
        pending(DEPLOYED);
        assertThatThrownBy(() -> svc.approve(42L, ADMIN, "SDD"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 수정승인대기가_아니면_승인_반려_철회_모두_409() {
        task(DEPLOYED);
        for (Runnable call : List.<Runnable>of(
                () -> svc.approve(42L, ADMIN, null),
                () -> svc.reject(42L, ADMIN, "사유"),
                () -> svc.withdraw(42L, OWNER, false))) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(TaskException.class)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                    .hasMessageContaining("수정승인대기");
        }
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 대기_요청_기록이_없으면_승인은_409() {
        task(FIX_REVIEW);
        when(fixRepo.findFirstByTaskIdAndStatusOrderByIdDesc(42L, PENDING)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.approve(42L, ADMIN, null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 승인_때도_한도를_넘으면_409() {
        task(FIX_REVIEW);
        pending(DEPLOYED);
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(3L);
        assertThatThrownBy(() -> svc.approve(42L, ADMIN, null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("새 작업");
    }

    // ── 반려·철회 ───────────────────────────────────────

    @Test
    void 반려는_요청전_상태로_사유필수() {
        Task t = task(FIX_REVIEW);
        TaskFixRequest r = pending(DEPLOY_LOST);

        assertThatThrownBy(() -> svc.reject(42L, ADMIN, "  "))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(t.getStatus()).isEqualTo(FIX_REVIEW);

        Task out = svc.reject(42L, ADMIN, " 재현이 안 됨 ");

        assertThat(out.getStatus()).isEqualTo(DEPLOY_LOST);             // 요청 전 상태
        assertThat(r.getStatus()).isEqualTo(FixRequestStatus.REJECTED);
        assertThat(r.getDecidedBy()).isEqualTo(ADMIN);
        assertThat(r.getDecisionReason()).isEqualTo("재현이 안 됨");
        assertThat(r.getRound()).isNull();                              // 회차는 승인 때만 확정
        assertThat(t.getActiveFixRequestId()).isNull();
        TaskStatusHistory h = savedHistory();
        assertThat(h.getFromStatus()).isEqualTo("수정승인대기");
        assertThat(h.getToStatus()).isEqualTo("배포중단됨");
        assertThat(h.getReason()).isEqualTo("수정 요청 반려: 재현이 안 됨");
    }

    @Test
    void 철회는_등록자가_요청전_상태로() {
        task(FIX_REVIEW);
        TaskFixRequest r = pending(DEPLOY_FAILED);

        Task out = svc.withdraw(42L, OWNER, false);

        assertThat(out.getStatus()).isEqualTo(DEPLOY_FAILED);
        assertThat(r.getStatus()).isEqualTo(FixRequestStatus.WITHDRAWN);
        assertThat(r.getDecidedBy()).isEqualTo(OWNER);
        assertThat(r.getDecidedAt()).isNotNull();
        assertThat(savedHistory().getReason()).isEqualTo("수정 요청 철회");
    }

    @Test
    void 철회는_남의_작업이면_403_관리자는_대신_철회() {
        task(FIX_REVIEW);
        pending(DEPLOYED);
        assertThatThrownBy(() -> svc.withdraw(42L, "someone", false))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(svc.withdraw(42L, ADMIN, true).getStatus()).isEqualTo(DEPLOYED);
    }

    @Test
    void 대기_요청_기록이_없으면_보수적으로_배포중단됨으로() {
        task(FIX_REVIEW);
        when(fixRepo.findFirstByTaskIdAndStatusOrderByIdDesc(42L, PENDING)).thenReturn(Optional.empty());
        assertThat(svc.withdraw(42L, OWNER, false).getStatus()).isEqualTo(DEPLOY_LOST);
    }

    // ── 목록·보기·방식 해석 ─────────────────────────────

    @Test
    void 목록은_작업의_요청을_최신순으로() {
        TaskFixRequest a = TaskFixRequest.pending(42L, "a", DEPLOYED, OWNER);
        when(fixRepo.findByTaskIdOrderByIdDesc(42L)).thenReturn(List.of(a));
        assertThat(svc.list(42L)).containsExactly(a);
    }

    @Test
    void 열린_회차가_없으면_보기는_회차_없음() {
        Task t = task(DEPLOYED);
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(2L);
        TaskResponse.FixView v = svc.fixViewOf(t);
        assertThat(v.activeRound()).isNull();
        assertThat(v.activeMode()).isNull();
        assertThat(v.approvedRounds()).isEqualTo(2);
        assertThat(v.maxRounds()).isEqualTo(3);
        assertThat(v.activeKind()).isNull();
    }

    @Test
    void 열린_회차의_종류가_보기에_실린다() {
        Task t = task(IMPLEMENTING);
        t.setActiveFixRequestId(77L);
        TaskFixRequest r = TaskFixRequest.baseSync(42L, 1, "main", DEPLOYED, ADMIN);
        when(fixRepo.findById(77L)).thenReturn(Optional.of(r));
        assertThat(svc.fixViewOf(t).activeKind()).isEqualTo("BASE_SYNC");

        TaskFixRequest fix = TaskFixRequest.pending(42L, "a", DEPLOYED, OWNER);
        fix.setStatus(APPROVED);
        fix.setRound(1);
        when(fixRepo.findById(77L)).thenReturn(Optional.of(fix));
        assertThat(svc.fixViewOf(t).activeKind()).isEqualTo("FIX");
    }

    @Test
    void 열린_회차가_있으면_보기에_회차와_방식() {
        Task t = task(IMPLEMENTING);
        t.setActiveFixRequestId(77L);
        TaskFixRequest r = TaskFixRequest.pending(42L, "a", DEPLOYED, OWNER);
        r.setStatus(APPROVED);
        r.setRound(2);
        r.setImplementationMode(ImplementationMode.DIRECT);
        when(fixRepo.findById(77L)).thenReturn(Optional.of(r));
        when(fixRepo.countByTaskIdAndStatusAndKind(42L, APPROVED, FixRequestKind.FIX)).thenReturn(2L);

        TaskResponse.FixView v = svc.fixViewOf(t);

        assertThat(v.activeRound()).isEqualTo(2);
        assertThat(v.activeMode()).isEqualTo("DIRECT");
        assertThat(v.approvedRounds()).isEqualTo(2);
    }

    @Test
    void 방식_해석() {
        assertThat(TaskFixService.parseFixMode(null)).isEqualTo(ImplementationMode.REVIEW);
        assertThat(TaskFixService.parseFixMode("  ")).isEqualTo(ImplementationMode.REVIEW);
        assertThat(TaskFixService.parseFixMode("DIRECT")).isEqualTo(ImplementationMode.DIRECT);
        assertThat(TaskFixService.parseFixMode(" REVIEW ")).isEqualTo(ImplementationMode.REVIEW);
        for (String bad : new String[]{"SDD", "sdd", "FAST"}) {
            assertThatThrownBy(() -> TaskFixService.parseFixMode(bad))
                    .isInstanceOf(TaskException.class)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void 요청_가능_상태는_배포완료_배포중단됨_배포실패() {
        assertThat(TaskFixService.REQUESTABLE).containsExactlyInAnyOrder(DEPLOYED, DEPLOY_LOST, DEPLOY_FAILED);
    }

    // ── 수정 중단 (§7.1) ─────────────────────────────────

    /** 열린 회차의 승인된 요청 #77 — 작업이 가리킨다. */
    private TaskFixRequest openRound(Task t, int round) {
        TaskFixRequest r = TaskFixRequest.pending(42L, "본문", DEPLOYED, OWNER);
        ReflectionTestUtils.setField(r, "id", 77L);
        r.setStatus(APPROVED);
        r.setRound(round);
        r.setImplementationMode(ImplementationMode.REVIEW);
        t.setActiveFixRequestId(77L);
        when(fixRepo.findById(77L)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void 수정중단은_회차_구현실패에서만_PR생성으로() {
        Task t = task(IMPLEMENTATION_FAILED);   // 배포 칸(컨테이너 id·주소)이 없다 — 떠 있는 배포가 없으면 바로 PR생성
        assertThat(t.getDeployContainerId()).isNull();
        assertThat(t.getDeployUrl()).isNull();
        t.setFailureReason("claude exit=1");
        t.setWorkerId("w1");
        t.setHeadSha("wip7777");   // 실패 보고가 남긴 브랜치 끝 — 그대로 둔다(이후 배포 기준)
        TaskFixRequest r = openRound(t, 2);

        Task out = svc.abort(42L, ADMIN);

        assertThat(out.getStatus()).isEqualTo(PR_CREATED);
        assertThat(out.getActiveFixRequestId()).isNull();
        assertThat(out.getFailureReason()).isNull();
        assertThat(out.getWorkerId()).isNull();
        assertThat(out.getHeadBranch()).isEqualTo("netismaker/task-42-x");
        assertThat(out.getHeadSha()).isEqualTo("wip7777");
        assertThat(out.getPrUrl()).isEqualTo("https://github.com/owner/repo/pull/7");
        assertThat(r.getOutcome()).isEqualTo(com.hamonsoft.netismaker.entity.FixOutcome.ABORTED);
        assertThat(r.getClosedAt()).isNotNull();
        assertThat(r.getStatus()).isEqualTo(APPROVED);   // 승인된 회차로 센다(실패한 회차)
        TaskStatusHistory h = savedHistory();
        assertThat(h.getFromStatus()).isEqualTo("구현실패");
        assertThat(h.getToStatus()).isEqualTo("PR생성");
        assertThat(h.getActorType()).isEqualTo("user");
        assertThat(h.getActorId()).isEqualTo(ADMIN);
        assertThat(h.getReason()).isEqualTo("수정 2회차 중단 → PR생성");
    }

    @Test
    void 이전_배포가_떠_있으면_수정중단은_배포중지대기로() {
        // 회차가 닫히면 GC 보호가 풀리고 PR생성은 중지·보호 대상이 아니다 — 컨테이너·공개 주소가 추적 없이 남지 않게 배포 중지 길로 보낸다
        Task t = task(IMPLEMENTATION_FAILED);
        t.setFailureReason("claude exit=1");
        t.setWorkerId("w1");
        t.setHeadSha("wip7777");
        t.setDeployUrl("https://task-42-win.micthebick.dev");
        t.setDeployContainerId("c0ffee");
        t.setDeployHostPort(19001);
        TaskFixRequest r = openRound(t, 2);

        Task out = svc.abort(42L, ADMIN);

        assertThat(out.getStatus()).isEqualTo(UNDEPLOY_PENDING);
        assertThat(out.getActiveFixRequestId()).isNull();          // 회차는 지금 닫는다 — 중지 길은 이 작업을 회차로 다루지 않는다
        assertThat(out.getFailureReason()).isNull();
        assertThat(out.getWorkerId()).isNull();
        assertThat(out.getClaimedAt()).isNull();
        assertThat(out.getHeadSha()).isEqualTo("wip7777");
        // 배포 칸은 워커가 컨테이너를 내린 뒤 PR생성 보고가 비운다(관리자 배포 중지와 같은 길)
        assertThat(out.getDeployContainerId()).isEqualTo("c0ffee");
        assertThat(out.getDeployUrl()).isEqualTo("https://task-42-win.micthebick.dev");
        assertThat(r.getOutcome()).isEqualTo(com.hamonsoft.netismaker.entity.FixOutcome.ABORTED);
        assertThat(r.getClosedAt()).isNotNull();
        verify(fixRepo).save(r);
        TaskStatusHistory h = savedHistory();
        assertThat(h.getFromStatus()).isEqualTo("구현실패");
        assertThat(h.getToStatus()).isEqualTo("배포중지대기");
        assertThat(h.getActorType()).isEqualTo("user");
        assertThat(h.getActorId()).isEqualTo(ADMIN);
        assertThat(h.getReason()).isEqualTo("수정 2회차 중단 → 배포 중지");
    }

    @Test
    void 배포_주소만_남아_있어도_수정중단은_배포중지대기로() {
        Task t = task(IMPLEMENTATION_FAILED);
        t.setDeployUrl("http://10.1.1.75:19001");
        openRound(t, 1);

        assertThat(svc.abort(42L, ADMIN).getStatus()).isEqualTo(UNDEPLOY_PENDING);
        assertThat(savedHistory().getReason()).isEqualTo("수정 1회차 중단 → 배포 중지");
    }

    @Test
    void 회차_기록이_없어도_이전_배포가_떠_있으면_배포중지대기로() {
        Task t = task(IMPLEMENTATION_FAILED);
        t.setActiveFixRequestId(77L);
        t.setDeployContainerId("c0ffee");
        when(fixRepo.findById(77L)).thenReturn(Optional.empty());

        Task out = svc.abort(42L, ADMIN);

        assertThat(out.getStatus()).isEqualTo(UNDEPLOY_PENDING);
        assertThat(out.getActiveFixRequestId()).isNull();
        assertThat(savedHistory().getReason())
                .isEqualTo("열린 수정 회차 기록이 없어(요청 #77) 회차 표시만 지움 — 수정 중단 → 배포 중지");
    }

    @Test
    void 회차_없는_구현실패는_수정중단_409() {
        Task t = task(IMPLEMENTATION_FAILED);

        assertThatThrownBy(() -> svc.abort(42L, ADMIN))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(t.getStatus()).isEqualTo(IMPLEMENTATION_FAILED);
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 회차_구현실패가_아니면_수정중단_409() {
        for (TaskStatus s : new TaskStatus[]{APPROVED_STATUS, IMPLEMENTING, DEPLOY_PENDING, DEPLOYING, DEPLOYED}) {
            Task t = task(s);
            TaskFixRequest r = openRound(t, 1);
            assertThatThrownBy(() -> svc.abort(42L, ADMIN))
                    .as(s.name())
                    .isInstanceOf(TaskException.class)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(t.getActiveFixRequestId()).isEqualTo(77L);
            assertThat(r.getOutcome()).isNull();
        }
        verify(historyRepo, never()).save(any());
    }

    @Test
    void 회차_기록을_못_찾으면_수정중단이_회차_표시만_지우고_PR생성으로() {
        // claim이 깨진 회차를 구현실패로 치운 경우(WorkerService.failBrokenFixRound) — 재시도는 409라 이 길이 유일한 출구다.
        // 그 구현은 시작도 안 했으므로 브랜치·head_sha는 회차 전 그대로다.
        Task t = task(IMPLEMENTATION_FAILED);
        t.setActiveFixRequestId(77L);
        when(fixRepo.findById(77L)).thenReturn(Optional.empty());

        Task out = svc.abort(42L, ADMIN);

        assertThat(out.getStatus()).isEqualTo(PR_CREATED);
        assertThat(out.getActiveFixRequestId()).isNull();
        assertThat(savedHistory().getReason()).isEqualTo("열린 수정 회차 기록이 없어(요청 #77) 회차 표시만 지움 — 수정 중단 → PR생성");
        verify(fixRepo, never()).save(any());
    }

    @Test
    void 승인되지_않은_요청을_가리키면_요청은_건드리지_않고_회차_표시만_지운다() {
        Task t = task(IMPLEMENTATION_FAILED);
        TaskFixRequest r = openRound(t, 1);
        r.setStatus(FixRequestStatus.REJECTED);

        svc.abort(42L, ADMIN);

        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(t.getStatus()).isEqualTo(PR_CREATED);
        assertThat(r.getOutcome()).isNull();
    }

    @Test
    void 이미_닫힌_요청을_가리키면_그_결과를_덮지_않는다() {
        Task t = task(IMPLEMENTATION_FAILED);
        TaskFixRequest r = openRound(t, 1);
        r.setOutcome(com.hamonsoft.netismaker.entity.FixOutcome.DEPLOY_FAILED);

        svc.abort(42L, ADMIN);

        assertThat(t.getActiveFixRequestId()).isNull();
        assertThat(t.getStatus()).isEqualTo(PR_CREATED);
        assertThat(r.getOutcome()).isEqualTo(com.hamonsoft.netismaker.entity.FixOutcome.DEPLOY_FAILED);
        assertThat(savedHistory().getReason()).startsWith("열린 수정 회차 기록이 없어(요청 #77)");
    }
}
