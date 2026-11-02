package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerServiceHeartbeatTest {

    private WorkerHeartbeatRepository heartbeatRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        TaskDesignRepository designRepo = mock(TaskDesignRepository.class);
        RepoCatalogRepository repoCatalogRepo = mock(RepoCatalogRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        var stageUsageRepo = mock(com.hamonsoft.netismaker.repository.TaskStageUsageRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        DeployLogStreamService deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, designRepo, repoCatalogRepo, historyRepo, stageUsageRepo, heartbeatRepo, deployLogStream,
                mock(com.hamonsoft.netismaker.repository.InterviewSessionRepository.class),
                mock(com.hamonsoft.netismaker.repository.InterviewPlanRepository.class));
        when(heartbeatRepo.findById(any())).thenReturn(Optional.empty());
        when(heartbeatRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private WorkerHeartbeatRequest req(Integer lostReportCount) {
        return new WorkerHeartbeatRequest("mac-worker-1", "host", "0.1.0", true, "ok",
                List.of("local-db"), lostReportCount, null, null);
    }

    @Test
    void heartbeat_persists_lost_report_count() {
        service.heartbeat(req(3));
        ArgumentCaptor<WorkerHeartbeat> cap = ArgumentCaptor.forClass(WorkerHeartbeat.class);
        verify(heartbeatRepo).save(cap.capture());
        assertThat(cap.getValue().getLostReportCount()).isEqualTo(3);
    }

    @Test
    void heartbeat_null_lost_report_count_maps_to_zero() {
        service.heartbeat(req(null));
        ArgumentCaptor<WorkerHeartbeat> cap = ArgumentCaptor.forClass(WorkerHeartbeat.class);
        verify(heartbeatRepo).save(cap.capture());
        assertThat(cap.getValue().getLostReportCount()).isEqualTo(0);
    }

    @Test
    void heartbeat_persists_claude_auth_status() {
        OffsetDateTime exp = OffsetDateTime.parse("2026-10-31T02:23:45.460Z");
        service.heartbeat(new WorkerHeartbeatRequest("win-worker-1", "host", "0.1.0", false, null,
                List.of(), 0, "CREDENTIALS", exp));
        ArgumentCaptor<WorkerHeartbeat> cap = ArgumentCaptor.forClass(WorkerHeartbeat.class);
        verify(heartbeatRepo).save(cap.capture());
        assertThat(cap.getValue().getClaudeSessionOk()).isFalse();
        assertThat(cap.getValue().getClaudeAuthMethod()).isEqualTo("CREDENTIALS");
        assertThat(cap.getValue().getClaudeAuthExpiresAt()).isEqualTo(exp);
    }

    @Test
    void heartbeat_from_old_worker_clears_claude_auth_fields() {
        // 구버전 워커는 세 칸을 보내지 않는다 — 예전 판정값이 남아 있으면 안 된다
        WorkerHeartbeat existing = new WorkerHeartbeat();
        existing.setWorkerId("mac-worker-1");
        existing.setClaudeSessionOk(false);
        existing.setClaudeAuthMethod("CREDENTIALS");
        existing.setClaudeAuthExpiresAt(OffsetDateTime.parse("2026-10-31T02:23:45.460Z"));
        when(heartbeatRepo.findById("mac-worker-1")).thenReturn(Optional.of(existing));

        service.heartbeat(new WorkerHeartbeatRequest("mac-worker-1", "host", "0.1.0", null, null,
                List.of(), 0, null, null));

        assertThat(existing.getClaudeSessionOk()).isNull();
        assertThat(existing.getClaudeAuthMethod()).isNull();
        assertThat(existing.getClaudeAuthExpiresAt()).isNull();
    }
}
