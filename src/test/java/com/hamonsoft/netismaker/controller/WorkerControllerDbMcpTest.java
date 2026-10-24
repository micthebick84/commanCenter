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
        when(workerService.claimNextTask("w1", false)).thenReturn(Optional.of(implementation()));
        DbMcpGrant g = new DbMcpGrant("tok", List.of(new DbMcpGrant.Server("db-12", "/mcp/db/12", "운영", "MYSQL")), List.of());
        when(grants.grantFor(5L)).thenReturn(g);
        assertThat(controller.nextTask("w1", null).getBody().dbMcp()).isEqualTo(g);
    }

    @Test
    void claim_survives_grant_failure() {
        when(workerService.claimNextTask("w1", false)).thenReturn(Optional.of(implementation()));
        when(grants.grantFor(anyLong())).thenThrow(new RuntimeException("db down"));
        var body = controller.nextTask("w1", null).getBody();
        assertThat(body.dbMcp().token()).isNull();
        assertThat(body.dbMcp().notices()).containsExactly("DB 연결을 준비하지 못해 DB 도구 없이 진행합니다");
    }

    @Test
    void analysis_claim_is_untouched() {
        Task t = Task.create("a/b", "main", "t", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 6L);
        when(workerService.claimNextTask("w1", false)).thenReturn(Optional.of(WorkerTaskResponse.forAnalysis(t)));
        assertThat(controller.nextTask("w1", null).getBody().dbMcp()).isNull();
        verifyNoInteractions(grants);
    }

    /** 새 워커는 next-task에 supports=refRepos를 붙인다 (스펙 2026-10-24 §4.7). 토큰은 정확히 같아야 한다. */
    @Test
    void supports_parsing() {
        assertThat(WorkerController.supportsRefRepos("refRepos")).isTrue();
        assertThat(WorkerController.supportsRefRepos(" x , refRepos")).isTrue();
        assertThat(WorkerController.supportsRefRepos("refRepos,x")).isTrue();
        assertThat(WorkerController.supportsRefRepos(null)).isFalse();
        assertThat(WorkerController.supportsRefRepos("")).isFalse();
        assertThat(WorkerController.supportsRefRepos("refrepos")).isFalse();
        assertThat(WorkerController.supportsRefRepos("refReposX")).isFalse();
        assertThat(WorkerController.supportsRefRepos("x refRepos")).isFalse();
    }

    @Test
    void next_task_passes_ref_support_through() {
        when(workerService.claimNextTask("w1", true)).thenReturn(Optional.empty());

        assertThat(controller.nextTask("w1", "refRepos").getStatusCode().value()).isEqualTo(204);

        verify(workerService).claimNextTask("w1", true);
        verify(workerService, never()).claimNextTask("w1", false);
    }
}
