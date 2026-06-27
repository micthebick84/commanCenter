package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        DeployLogStreamService deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(heartbeatRepo.findById(any())).thenReturn(Optional.empty());
        when(heartbeatRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private WorkerHeartbeatRequest req(Integer lostReportCount) {
        return new WorkerHeartbeatRequest("mac-worker-1", "host", "0.1.0", true, "ok",
                List.of("local-db"), lostReportCount);
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
}
