package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DeployLogChunkRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkerServiceDeployTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerHeartbeatRepository heartbeatRepo;
    private DeployLogStreamService deployLogStream;
    private WorkerService service;

    private Task taskWithStatus(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(status);
        t.setHeadBranch("netismaker/task-7");
        t.setHeadSha("abcdef1234567890");
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        TaskDesignRepository designRepo = mock(TaskDesignRepository.class);
        RepoCatalogRepository repoCatalogRepo = mock(RepoCatalogRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, designRepo, repoCatalogRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void claim_deploy_pending_yields_deploy_kind_and_sets_deploying() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_PENDING);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));
        Optional<WorkerTaskResponse> claimed = service.claimNextTask("mac-worker-1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        assertThat(claimed.get().headBranch()).isEqualTo("netismaker/task-7");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYING);
    }

    @Test
    void claim_undeploy_pending_yields_undeploy_kind_and_sets_undeploying() {
        Task t = taskWithStatus(TaskStatus.UNDEPLOY_PENDING);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class))).thenReturn(List.of(t));
        Optional<WorkerTaskResponse> claimed = service.claimNextTask("mac-worker-1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().kind()).isEqualTo(WorkerTaskResponse.Kind.UNDEPLOY);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.UNDEPLOYING);
    }

    @Test
    void record_undeploy_success_from_undeploying_returns_pr_created() {
        Task t = taskWithStatus(TaskStatus.UNDEPLOYING);
        t.setWorkerId("mac-worker-1");
        t.setDeployUrl("http://localhost:19000");
        t.setDeployHostPort(19000);
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.undeployed("mac-worker-1", "중지/제거"));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getDeployUrl()).isNull();
    }

    @Test
    void record_deployed_sets_url_and_status() {
        Task t = taskWithStatus(TaskStatus.DEPLOYING);
        t.setWorkerId("mac-worker-1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.deployed(
                "mac-worker-1", "http://localhost:19000", "cid123", 19000,
                "netis-task-7:abcdef1", 5000L, "build ok"));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(t.getDeployUrl()).isEqualTo("http://localhost:19000");
        assertThat(t.getDeployHostPort()).isEqualTo(19000);
        assertThat(t.getDeployedAt()).isNotNull();
        // finish must be called so SSE subscribers receive done event and chunks are cleaned up
        verify(deployLogStream).finish(7L);
    }

    @Test
    void record_undeployed_returns_to_pr_created_and_clears_deploy_meta() {
        Task t = taskWithStatus(TaskStatus.DEPLOYING);
        t.setWorkerId("mac-worker-1");
        t.setDeployUrl("http://localhost:19000");
        t.setDeployHostPort(19000);
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.undeployed("mac-worker-1", "stopped"));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getDeployUrl()).isNull();
        assertThat(t.getDeployHostPort()).isNull();
        // finish must be called so SSE subscribers receive done event and chunks are cleaned up
        verify(deployLogStream).finish(7L);
    }

    @Test
    void record_undeploy_result_calls_finish_on_stream() {
        Task t = taskWithStatus(TaskStatus.UNDEPLOYING);
        t.setWorkerId("mac-worker-1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.undeployed("mac-worker-1", "중지/제거"));
        // finish must be called for UNDEPLOYING result as well
        verify(deployLogStream).finish(7L);
    }

    @Test
    void append_deploy_log_delegates_to_ingest_chunk() {
        // appendDeployLog is a best-effort pass-through to deployLogStream.ingestChunk
        service.appendDeployLog(7L, new DeployLogChunkRequest(3, "step 3 done\n"));
        verify(deployLogStream).ingestChunk(eq(7L), eq(3), eq("step 3 done\n"));
    }
}
