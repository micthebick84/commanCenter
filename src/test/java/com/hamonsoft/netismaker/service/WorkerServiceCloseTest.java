package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.CloseProgressRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerServiceCloseTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskStageUsageRepository stageUsageRepo;
    private WorkerService service;

    private Task task(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(status);
        t.setPrNumber(5);
        t.setPrUrl("https://github.com/owner/repo/pull/5");
        t.setHeadBranch("netismaker/task-7");
        t.setHeadSha("abcdef1234567890abcdef1234567890abcdef12");
        t.setDeployUrl("http://10.1.1.75:19001");
        t.setDeployedAt(java.time.OffsetDateTime.now().minusDays(1));
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        stageUsageRepo = mock(TaskStageUsageRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, stageUsageRepo, mock(WorkerHeartbeatRepository.class),
                mock(DeployLogStreamService.class), mock(InterviewSessionRepository.class),
                mock(InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.findByTaskIdOrderByAtDescIdDesc(anyLong(), any(Limit.class))).thenReturn(List.of(
                TaskStatusHistory.log(7L, TaskStatus.CLOSE_REVIEW, TaskStatus.CLOSE_PENDING, "user", "admin", "승인"),
                TaskStatusHistory.log(7L, TaskStatus.DEPLOYED, TaskStatus.CLOSE_REVIEW, "user", "user1", "요청")));
        when(stageUsageRepo.findByTaskIdOrderByStageAsc(7L)).thenReturn(List.of());
    }

    @Test
    void 종료대기를_claim하면_종료중_kind_CLOSE와_재료를_준다() {
        Task t = task(TaskStatus.CLOSE_PENDING);
        t.setCloseProgress("MERGED");
        t.setMergeCommitSha("feedface");
        when(taskRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(t));

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSING);
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.CLOSE);
        assertThat(r.prNumber()).isEqualTo(5);
        assertThat(r.headSha()).isEqualTo(t.getHeadSha());
        assertThat(r.closeProgress()).isEqualTo("MERGED");
        assertThat(r.closeContext().mergeCommitSha()).isEqualTo("feedface");
        assertThat(r.closeContext().deployUrl()).isEqualTo("http://10.1.1.75:19001");
        // 이력은 시간순(오래된 것 먼저)
        assertThat(r.closeContext().history()).extracting(com.hamonsoft.netismaker.dto.CloseContext.HistoryLine::to).containsExactly("종료승인대기", "종료대기");
    }

    @Test
    void 진행_보고는_단계를_누적하고_단계별_값을_저장한다() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));

        service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.MERGED, "cafe1234", null));
        service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.UNDEPLOYED, null, null));
        service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.NOTE_WRITTEN, null, "HamonSoft/netisMaker/a.md"));
        service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.MERGED, null, null)); // 중복·null 무해

        assertThat(t.getCloseProgress()).isEqualTo("MERGED,UNDEPLOYED,NOTE_WRITTEN");
        assertThat(t.getMergeCommitSha()).isEqualTo("cafe1234");
        assertThat(t.getDeployUrl()).isNull();
        assertThat(t.getDeployedAt()).isNull(); // 배포 중지 복귀(PR_CREATED)와 같은 규칙
        assertThat(t.getCloseNotePath()).isEqualTo("HamonSoft/netisMaker/a.md");
    }

    @Test
    void 진행_보고는_종료중이_아니거나_다른_워커면_409() {
        Task t = task(TaskStatus.CLOSE_PENDING);
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.MERGED, null, null)))
                .isInstanceOf(TaskException.class);
        t.setStatus(TaskStatus.CLOSING);
        t.setWorkerId("w2");
        assertThatThrownBy(() -> service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.MERGED, null, null)))
                .isInstanceOf(TaskException.class);
    }

    @Test
    void 작업종료_보고는_4단계가_다_끝났을때만_받는다() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseProgress("MERGED,UNDEPLOYED,NOTE_WRITTEN");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.recordResult(7L, WorkerResultRequest.closed("w1", null)))
                .isInstanceOf(TaskException.class);

        t.setCloseProgress("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED");
        var usage = new WorkerResultRequest.UsageReport(new BigDecimal("0.12"), 100L, 50L, 0L, 0L);
        t.setMergeCommitSha("cafe1234567890");
        service.recordResult(7L, WorkerResultRequest.closed("w1", usage));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSED);
        var captor = org.mockito.ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("작업 종료: 병합 cafe123");
        verify(stageUsageRepo).accumulate(eq(7L), eq("CLOSE"), any(), eq(100L), eq(50L), eq(0L), eq(0L));
    }

    @Test
    void 종료실패_보고는_사유를_남긴다() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.closeFailed("w1", "CI 대기 중: checks pending", null));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(t.getFailureReason()).startsWith("CI 대기 중");
    }

    @Test
    void 산출물_모드가_없으면_4단계로_작업종료() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseProgress("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.closed("w1", null));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSED);
    }

    @Test
    void 산출물_모드_REQUIRED면_산출물_단계_없이는_작업종료를_받지_않는다() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.recordResult(7L, WorkerResultRequest.closed("w1", null)))
                .isInstanceOf(TaskException.class).hasMessageContaining("종료 단계");

        t.setCloseProgress("MERGED,UNDEPLOYED,ARTIFACT_UPLOADED,NOTE_WRITTEN,BRANCH_DELETED");
        service.recordResult(7L, WorkerResultRequest.closed("w1", null));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSED);
    }

    @Test
    void 산출물_진행_보고는_파일이_저장된_뒤에만_받는다() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.recordCloseProgress(7L,
                new CloseProgressRequest("w1", CloseStep.ARTIFACT_UPLOADED, null, null)))
                .isInstanceOf(TaskException.class).hasMessageContaining("산출물");

        t.setArtifactName("repo-task-7-abcdef1.war");
        service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.ARTIFACT_UPLOADED, null, null));
        assertThat(t.getCloseProgress()).isEqualTo("ARTIFACT_UPLOADED");
    }
}
