package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.service.WorkerService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 *  워커 API (X-Worker-API-Key 인증).
 *
 *   POST /worker/heartbeat        ─► worker_heartbeat upsert
 *   GET  /worker/next-task         ─► PENDING/APPROVED 작업 1건 또는 204
 *   POST /worker/tasks/{id}/result ─► 분석/구현 완료/실패 보고
 */
@RestController
@RequestMapping("/worker")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@RequestBody @Valid WorkerHeartbeatRequest req) {
        workerService.heartbeat(req);
    }

    @GetMapping("/next-task")
    public ResponseEntity<WorkerTaskResponse> nextTask(@RequestParam String workerId) {
        Optional<WorkerTaskResponse> claimed = workerService.claimNextTask(workerId);
        return claimed.map(ResponseEntity::ok)
                      .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/tasks/{id}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@PathVariable Long id, @RequestBody @Valid WorkerResultRequest req) {
        workerService.recordResult(id, req);
    }
}
