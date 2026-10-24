package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.WorkerStartupRequest;
import com.hamonsoft.netismaker.dto.WorkerStartupResponse;
import com.hamonsoft.netismaker.service.WorkerStartupService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerStartupControllerTest {

    @Test
    void null_body_means_no_pending() {
        WorkerStartupService service = mock(WorkerStartupService.class);
        WorkerStartupResponse empty = new WorkerStartupResponse(List.of(), List.of());
        when(service.reconcile(eq("w1"), any())).thenReturn(empty);
        WorkerStartupController controller = new WorkerStartupController(service);

        assertThat(controller.startup("w1", null)).isSameAs(empty);

        verify(service).reconcile("w1", List.of());
    }

    @Test
    void null_list_means_no_pending() {
        WorkerStartupService service = mock(WorkerStartupService.class);
        when(service.reconcile(eq("w1"), any())).thenReturn(new WorkerStartupResponse(List.of(), List.of()));
        WorkerStartupController controller = new WorkerStartupController(service);

        controller.startup("w1", new WorkerStartupRequest(null));

        verify(service).reconcile("w1", List.of());
    }
}
