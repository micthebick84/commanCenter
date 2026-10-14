package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpGrantService;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class WorkerControllerDbMcpTest {

    private final WorkerService workerService = mock(WorkerService.class);
    private final DbMcpGrantService grants = mock(DbMcpGrantService.class);
    private final WorkerController controller = new WorkerController(workerService, grants);

    private static WorkerTaskResponse implementation() {
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 5L);
        return WorkerTaskResponse.forImplementation(t, null, null);
    }

    @Test
    void implementation_claim_carries_grant() {
        when(workerService.claimNextTask("w1")).thenReturn(Optional.of(implementation()));
        DbMcpGrant g = new DbMcpGrant("tok", List.of(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영", "MYSQL")), List.of());
        when(grants.grantFor(5L)).thenReturn(g);
        assertThat(controller.nextTask("w1").getBody().dbMcp()).isEqualTo(g);
    }

    @Test
    void claim_survives_grant_failure() {
        when(workerService.claimNextTask("w1")).thenReturn(Optional.of(implementation()));
        when(grants.grantFor(anyLong())).thenThrow(new RuntimeException("db down"));
        var body = controller.nextTask("w1").getBody();
        assertThat(body.dbMcp().token()).isNull();
        assertThat(body.dbMcp().notices()).containsExactly("DB 연결을 준비하지 못해 DB 도구 없이 진행합니다");
    }

    @Test
    void analysis_claim_is_untouched() {
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 6L);
        when(workerService.claimNextTask("w1")).thenReturn(Optional.of(WorkerTaskResponse.forAnalysis(t)));
        assertThat(controller.nextTask("w1").getBody().dbMcp()).isNull();
        verifyNoInteractions(grants);
    }
}
