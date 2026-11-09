package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 구현(수정 회차 포함)·디자인 claim에 인터뷰 목업 파일과 제외 번호를 싣는다 (스펙 2026-11-03 §7). */
class WorkerServiceInterviewMockupClaimTest {

    private TaskRepository taskRepo;
    private TaskFixRequestRepository fixRepo;
    private InterviewMockupService mockups;
    private WorkerService svc;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        fixRepo = mock(TaskFixRequestRepository.class);
        mockups = mock(InterviewMockupService.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        svc = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, mock(TaskStageUsageRepository.class),
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        svc.setFixRequestRepository(fixRepo);
        svc.setInterviewMockupService(mockups);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static WorkerTaskResponse.InterviewMockupFile file(int n, String html) {
        return new WorkerTaskResponse.InterviewMockupFile(n, "list-a", 1, "A안", html);
    }

    private Task task(TaskStatus status, List<Long> ids, List<Integer> excluded) {
        Task t = Task.create("a/b", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        t.setImplementationMode(ImplementationMode.DIRECT);
        t.setImplementationAttempt(1);
        t.setImplementationMockupIds(new ArrayList<>(ids));
        t.setImplementationMockupExcluded(new ArrayList<>(excluded));
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));
        return t;
    }

    @Test
    void 구현_claim에_목업파일_n순과_제외번호() {
        task(TaskStatus.APPROVED, List.of(12L, 10L), List.of(2));
        when(mockups.filesFor(List.of(12L, 10L))).thenReturn(List.of(file(3, "<html>A</html>"), file(5, "<html>B</html>")));

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.IMPLEMENTATION);
        assertThat(r.interviewMockups()).extracting(WorkerTaskResponse.InterviewMockupFile::n).containsExactly(3, 5);
        assertThat(r.interviewMockups().get(0).html()).isEqualTo("<html>A</html>");
        assertThat(r.excludedMockupNumbers()).containsExactly(2);
    }

    @Test
    void 디자인_claim에도_실린다() {
        task(TaskStatus.DESIGN_PENDING, List.of(12L), List.of());
        when(mockups.filesFor(List.of(12L))).thenReturn(List.of(file(3, "<html>A</html>")));

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DESIGN);
        assertThat(r.interviewMockups()).extracting(WorkerTaskResponse.InterviewMockupFile::n).containsExactly(3);
        assertThat(r.excludedMockupNumbers()).isEmpty();
    }

    @Test
    void 배포_claim은_목업을_싣지_않는다() {
        task(TaskStatus.DEPLOY_PENDING, List.of(12L), List.of(2));

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        assertThat(r.interviewMockups()).isEmpty();
        assertThat(r.excludedMockupNumbers()).isEmpty();
        verify(mockups, never()).filesFor(anyList());
    }

    @Test
    void 모두_해제한_작업도_claim에_제외번호() {
        task(TaskStatus.APPROVED, List.of(), List.of(2));

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.interviewMockups()).isEmpty();
        assertThat(r.excludedMockupNumbers()).containsExactly(2);
        verify(mockups, never()).filesFor(anyList());
    }

    @Test
    void 목업도_제외번호도_없으면_조회하지_않는다() {
        task(TaskStatus.APPROVED, List.of(), List.of());

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.interviewMockups()).isEmpty();
        assertThat(r.excludedMockupNumbers()).isEmpty();
        verifyNoInteractions(mockups);
    }

    @Test
    void 수정회차_claim에도_목업() {
        Task t = task(TaskStatus.APPROVED, List.of(12L), List.of());
        t.setHeadBranch("netismaker/task-42-x");
        t.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        t.setActiveFixRequestId(502L);
        TaskFixRequest req = TaskFixRequest.pending(42L, "고쳐 주세요", TaskStatus.DEPLOYED, "user1");
        ReflectionTestUtils.setField(req, "id", 502L);
        req.setStatus(FixRequestStatus.APPROVED);
        req.setRound(1);
        req.setImplementationMode(ImplementationMode.DIRECT);
        req.setAttempt(1);
        when(fixRepo.findById(502L)).thenReturn(Optional.of(req));
        when(mockups.filesFor(List.of(12L))).thenReturn(List.of(file(3, "<html>A</html>")));

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.implementation().fix()).isNotNull();
        assertThat(r.interviewMockups()).extracting(WorkerTaskResponse.InterviewMockupFile::n).containsExactly(3);
    }

    /** JSONB에서 읽은 List&lt;Long&gt;은 Integer 원소를 담아 올 수 있다 — 저장소는 Long으로 불러야 한다. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void Integer_원소_ids도_Long으로_조회한다() {
        InterviewMockupRepository repo = mock(InterviewMockupRepository.class);
        svc.setInterviewMockupService(new InterviewMockupService(mock(InterviewSessionRepository.class), repo));
        List<Long> ids = (List) new ArrayList<>(List.of(12, 10));
        Task t = task(TaskStatus.APPROVED, List.of(), List.of());
        t.setImplementationMockupIds(ids);
        InterviewMockup m10 = InterviewMockup.create(1L, 5, "list-b", 1, "B안", null, "<html>B</html>");
        ReflectionTestUtils.setField(m10, "id", 10L);
        InterviewMockup m12 = InterviewMockup.create(1L, 3, "list-a", 1, "A안", null, "<html>A</html>");
        ReflectionTestUtils.setField(m12, "id", 12L);
        when(repo.findByIdInAndDiscardedFalseOrderByNAsc(List.of(12L, 10L))).thenReturn(List.of(m12, m10));

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.interviewMockups()).extracting(WorkerTaskResponse.InterviewMockupFile::n).containsExactly(3, 5);
        assertThat(r.interviewMockups().get(1).html()).isEqualTo("<html>B</html>");
    }

    @Test
    void filesFor는_없는_행을_빼고_n순으로_돌려준다() {
        InterviewMockupRepository repo = mock(InterviewMockupRepository.class);
        InterviewMockupService real = new InterviewMockupService(mock(InterviewSessionRepository.class), repo);
        InterviewMockup m = InterviewMockup.create(1L, 4, "list-a", 2, "A안", null, "<html>A</html>");
        ReflectionTestUtils.setField(m, "id", 12L);
        when(repo.findByIdInAndDiscardedFalseOrderByNAsc(List.of(12L, 99L))).thenReturn(List.of(m));

        List<WorkerTaskResponse.InterviewMockupFile> files = real.filesFor(List.of(12L, 99L));

        assertThat(files).containsExactly(new WorkerTaskResponse.InterviewMockupFile(4, "list-a", 2, "A안", "<html>A</html>"));
        assertThat(real.filesFor(List.of())).isEmpty();
    }

    @Test
    void 목업_서비스를_주입하지_않은_단위_구성은_목업_없이_응답한다() {
        svc.setInterviewMockupService(null);
        task(TaskStatus.APPROVED, List.of(12L), List.of(2));

        WorkerTaskResponse r = svc.claimNextTask("w1", WorkerCapabilities.ALL, null).orElseThrow();

        assertThat(r.interviewMockups()).isEmpty();
        assertThat(r.excludedMockupNumbers()).isEmpty();
    }
}
