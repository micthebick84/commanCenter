package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerServiceReconcileTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskStatusHistoryRepository historyRepo;
    private DeployLogStreamService deployLogStream;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        TaskDesignRepository designRepo = mock(TaskDesignRepository.class);
        RepoCatalogRepository repoCatalogRepo = mock(RepoCatalogRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        WorkerHeartbeatRepository heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, designRepo, repoCatalogRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task failedTask() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 16L);
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setFailureReason("Stale 회수: 워커 mac-worker-1 응답 없음(heartbeat) (구현 중단)");
        return t;
    }

    /** stale 회수가 만든 상태 fixture — 회수는 workerId를 null로 비운다. */
    private Task taskIn(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 16L);
        t.setStatus(status);
        return t;
    }

    private WorkerResultRequest prCreated(String prUrl, String headBranch) {
        return new WorkerResultRequest("mac-worker-1", TaskStatus.PR_CREATED,
                null, null, null, 123L, null,
                prUrl, 10, headBranch, "abcdef1", "impl log",
                null, null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void late_pr_created_on_stale_failed_task_reconciles_to_pr_created() {
        Task t = failedTask();
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        service.recordResult(16L, prCreated("https://github.com/o/r/pull/10", "netismaker/task-16"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/o/r/pull/10");
        assertThat(t.getHeadBranch()).isEqualTo("netismaker/task-16");
        assertThat(t.getFailureReason()).isNull();
        org.mockito.ArgumentCaptor<com.hamonsoft.netismaker.entity.TaskStatusHistory> cap =
                org.mockito.ArgumentCaptor.forClass(com.hamonsoft.netismaker.entity.TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED.dbValue());
        assertThat(cap.getValue().getToStatus()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(cap.getValue().getActorType()).isEqualTo("worker");
        assertThat(cap.getValue().getActorId()).isEqualTo("mac-worker-1");
    }

    @Test
    void late_pr_created_missing_pr_meta_is_rejected_as_conflict() {
        Task t = failedTask();
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, prCreated(null, null)))
                .isInstanceOf(TaskException.class);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }

    // ────────────────── 지각 디자인 정합화 (DESIGN_PENDING → DESIGN_REVIEW) ──────────────────

    private WorkerResultRequest lateDesign(String designMarkdown, String mockupFilesJson) {
        return WorkerResultRequest.designReview("mac-worker-1", designMarkdown, mockupFilesJson,
                null, null, "claude log", 700_000L, null);
    }

    @Test
    void late_design_review_on_stale_requeued_task_reconciles_to_design_review() {
        // stale 회수가 DESIGNING → DESIGN_PENDING 재큐한 뒤 도착한 완성 디자인 보고 —
        // 재생성 사이클(수 분 + claude 비용) 대신 산출물을 수용해야 한다.
        Task t = taskIn(TaskStatus.DESIGN_PENDING);
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        service.recordResult(16L, lateDesign("# 디자인 문서", "[{\"path\":\"a.html\"}]"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DESIGN_REVIEW);
        org.mockito.ArgumentCaptor<com.hamonsoft.netismaker.entity.TaskStatusHistory> cap =
                org.mockito.ArgumentCaptor.forClass(com.hamonsoft.netismaker.entity.TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromStatus()).isEqualTo(TaskStatus.DESIGN_PENDING.dbValue());
        assertThat(cap.getValue().getToStatus()).isEqualTo(TaskStatus.DESIGN_REVIEW.dbValue());
        assertThat(cap.getValue().getReason()).contains("지각 보고 정합화");
    }

    @Test
    void late_design_review_with_blank_artifacts_is_rejected_as_conflict() {
        // 산출물이 비면 정합화 분기 불충족 → in-flight 가드에서 409 (재큐 상태 유지)
        Task t = taskIn(TaskStatus.DESIGN_PENDING);
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, lateDesign(" ", "[]")))
                .isInstanceOf(TaskException.class);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
    }

    @Test
    void late_design_review_after_reclaim_by_another_worker_is_rejected() {
        // 재큐분을 다른 워커가 이미 claim(DESIGNING, w2)했으면 이전 워커의 지각 보고는 거절 —
        // 진행 중인 재생성이 이긴다.
        Task t = taskIn(TaskStatus.DESIGNING);
        t.setWorkerId("w2");
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, lateDesign("# 디자인", "[]")))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("다른 워커");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DESIGNING);
    }

    // ───────────────────────── 지각 분석 정합화 (FAILED → COMPLETED) ─────────────────────────

    private WorkerResultRequest lateCompleted(String markdown) {
        return new WorkerResultRequest("mac-worker-1", TaskStatus.COMPLETED,
                markdown, "[]", "claude log", 456L, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
    }

    @Test
    void late_completed_on_stale_failed_task_reconciles_to_completed() {
        Task t = taskIn(TaskStatus.FAILED);
        t.setFailureReason("Stale 회수 한도 초과: 워커 응답 없음");
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        service.recordResult(16L, lateCompleted("## 분석 결과"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(t.getFailureReason()).isNull();

        org.mockito.ArgumentCaptor<com.hamonsoft.netismaker.entity.TaskAnalysis> analysisCap =
                org.mockito.ArgumentCaptor.forClass(com.hamonsoft.netismaker.entity.TaskAnalysis.class);
        verify(analysisRepo).save(analysisCap.capture());
        assertThat(analysisCap.getValue().getTaskId()).isEqualTo(16L);
        assertThat(analysisCap.getValue().getMarkdownResult()).isEqualTo("## 분석 결과");

        org.mockito.ArgumentCaptor<com.hamonsoft.netismaker.entity.TaskStatusHistory> cap =
                org.mockito.ArgumentCaptor.forClass(com.hamonsoft.netismaker.entity.TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromStatus()).isEqualTo(TaskStatus.FAILED.dbValue());
        assertThat(cap.getValue().getToStatus()).isEqualTo(TaskStatus.COMPLETED.dbValue());
        assertThat(cap.getValue().getActorType()).isEqualTo("worker");
        assertThat(cap.getValue().getActorId()).isEqualTo("mac-worker-1");
    }

    @Test
    void late_completed_with_blank_markdown_is_rejected_as_conflict() {
        Task t = taskIn(TaskStatus.FAILED);
        t.setFailureReason("Stale 회수 한도 초과");
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, lateCompleted("  ")))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.FAILED);
        verify(analysisRepo, never()).save(any());
    }

    @Test
    void late_completed_on_pending_task_is_rejected_as_conflict() {
        // PENDING은 정합화 대상이 아님 — stale 회수가 재큐한 뒤 재클레임과 경합하므로 재분석에 맡긴다.
        Task t = taskIn(TaskStatus.PENDING);
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, lateCompleted("## 분석 결과")))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        verify(analysisRepo, never()).save(any());
    }

    // ───────────────────────── 지각 배포 정합화 (DEPLOY_FAILED → DEPLOYED) ─────────────────────────

    private WorkerResultRequest lateDeployed(String deployUrl) {
        return new WorkerResultRequest("mac-worker-1", TaskStatus.DEPLOYED,
                null, null, null, 789L, null,
                null, null, null, null, null,
                deployUrl, "cid-abc", 19001, "netis-task-16:1", "deploy log",
                null, null, null, null, null);
    }

    @Test
    void late_deployed_on_stale_deploy_failed_task_reconciles_to_deployed() {
        Task t = taskIn(TaskStatus.DEPLOY_FAILED);
        t.setFailureReason("Stale 회수: 워커 응답 없음 (배포 중단)");
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        service.recordResult(16L, lateDeployed("http://localhost:19001"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(t.getDeployUrl()).isEqualTo("http://localhost:19001");
        assertThat(t.getDeployContainerId()).isEqualTo("cid-abc");
        assertThat(t.getDeployHostPort()).isEqualTo(19001);
        assertThat(t.getDeployImage()).isEqualTo("netis-task-16:1");
        assertThat(t.getDeployedAt()).isNotNull();
        assertThat(t.getFailureReason()).isNull();
        verify(deployLogStream).finish(16L);

        org.mockito.ArgumentCaptor<com.hamonsoft.netismaker.entity.TaskStatusHistory> cap =
                org.mockito.ArgumentCaptor.forClass(com.hamonsoft.netismaker.entity.TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromStatus()).isEqualTo(TaskStatus.DEPLOY_FAILED.dbValue());
        assertThat(cap.getValue().getToStatus()).isEqualTo(TaskStatus.DEPLOYED.dbValue());
        assertThat(cap.getValue().getActorType()).isEqualTo("worker");
        assertThat(cap.getValue().getActorId()).isEqualTo("mac-worker-1");
    }

    @Test
    void late_deployed_without_deploy_url_is_rejected_as_conflict() {
        Task t = taskIn(TaskStatus.DEPLOY_FAILED);
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, lateDeployed(null)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_FAILED);
        verify(deployLogStream, never()).finish(any());
    }

    @Test
    void deployed_report_while_deploying_follows_normal_flow_not_reconciliation() {
        // 정합화 분기 미적용 — in-flight 가드 통과 후 기존 recordDeployResult 흐름 회귀 확인.
        Task t = taskIn(TaskStatus.DEPLOYING);
        t.setWorkerId("mac-worker-1");
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        service.recordResult(16L, lateDeployed("http://localhost:19001"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(t.getDeployUrl()).isEqualTo("http://localhost:19001");
        verify(deployLogStream).finish(16L);

        org.mockito.ArgumentCaptor<com.hamonsoft.netismaker.entity.TaskStatusHistory> cap =
                org.mockito.ArgumentCaptor.forClass(com.hamonsoft.netismaker.entity.TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getFromStatus()).isEqualTo(TaskStatus.DEPLOYING.dbValue());
    }

    @Test
    void late_deployed_on_deploy_lost_task_is_rejected_as_conflict() {
        // DEPLOY_LOST는 DEPLOYED에서만 진입하는 상태 — 지각 배포 보고의 정합화 대상이 아니다.
        Task t = taskIn(TaskStatus.DEPLOY_LOST);
        when(taskRepo.findActiveByIdForUpdate(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, lateDeployed("http://localhost:19001")))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_LOST);
        verify(deployLogStream, never()).finish(any());
    }
}
