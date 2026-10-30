package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpGrantService;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** next-task kinds — 다른 PC의 워커가 받을 작업 종류를 고른다. 없으면 예전처럼 전부. */
class WorkerControllerKindsTest {

    private final WorkerService workerService = mock(WorkerService.class);
    private final WorkerController controller = new WorkerController(workerService, mock(DbMcpGrantService.class));

    @Test
    void parses_names_case_insensitively_and_ignores_blanks() {
        assertThat(WorkerController.parseKinds(" implementation , DESIGN,,"))
                .containsExactlyInAnyOrder(WorkerTaskResponse.Kind.IMPLEMENTATION, WorkerTaskResponse.Kind.DESIGN);
    }

    @Test
    void missing_or_blank_means_every_kind() {
        assertThat(WorkerController.parseKinds(null)).isNull();
        assertThat(WorkerController.parseKinds("  ")).isNull();
        assertThat(WorkerController.parseKinds(" , ")).isNull();
    }

    @Test
    void an_unknown_kind_is_a_bad_request() {
        assertThatThrownBy(() -> WorkerController.parseKinds("IMPLEMENTATION,BUILD"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("BUILD");
    }

    @Test
    void kinds_route_to_the_filtered_claim() {
        when(workerService.claimNextTask(anyString(), anyBoolean(), any())).thenReturn(Optional.empty());

        assertThat(controller.nextTask("remote-1", "refRepos", "IMPLEMENTATION,DESIGN").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        verify(workerService).claimNextTask("remote-1", true,
                EnumSet.of(WorkerTaskResponse.Kind.IMPLEMENTATION, WorkerTaskResponse.Kind.DESIGN));
        verify(workerService, never()).claimNextTask(anyString(), anyBoolean());
    }

    @Test
    void no_kinds_keeps_the_full_queue() {
        when(workerService.claimNextTask("w1", true)).thenReturn(Optional.empty());

        controller.nextTask("w1", "refRepos", null);

        verify(workerService).claimNextTask("w1", true);
        verify(workerService, never()).claimNextTask(anyString(), anyBoolean(), any());
    }
}
