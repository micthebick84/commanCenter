package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpGrantService;
import com.hamonsoft.netismaker.dbmcp.DbWriteService;
import com.hamonsoft.netismaker.service.WorkerCapabilities;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** next-task의 supports=mockups (스펙 2026-11-03 §7) — 없는 워커는 목업을 고른 구현·디자인 대기를 받지 못한다(큐 필터). */
class WorkerControllerMockupsTest {

    private final WorkerService workerService = mock(WorkerService.class);
    private final WorkerController controller = new WorkerController(workerService,
            mock(DbMcpGrantService.class), mock(DbWriteService.class));

    @Test
    void supports에_mockups가_있으면_넷째_true() {
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("w1", "refRepos,fixRounds,dbWrite,mockups", null);

        verify(workerService).claimNextTask("w1", new WorkerCapabilities(true, true, true, true), null);
    }

    @Test
    void 옛_supports는_mockups_false() {
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("old", "refRepos,fixRounds,dbWrite", null);

        verify(workerService).claimNextTask("old", new WorkerCapabilities(true, true, true, false), null);
    }

    @Test
    void mockups_토큰은_정확히_일치해야_한다() {
        assertThat(WorkerController.SUPPORTS_MOCKUPS).isEqualTo("mockups");
        when(workerService.claimNextTask(anyString(), any(WorkerCapabilities.class), any())).thenReturn(Optional.empty());

        controller.nextTask("a", " refRepos , mockups ", null);
        controller.nextTask("b", "Mockups", null);
        controller.nextTask("c", "mockupsX", null);

        verify(workerService).claimNextTask("a", new WorkerCapabilities(true, false, false, true), null);
        verify(workerService).claimNextTask("b", new WorkerCapabilities(false, false, false, false), null);
        verify(workerService).claimNextTask("c", new WorkerCapabilities(false, false, false, false), null);
    }
}
