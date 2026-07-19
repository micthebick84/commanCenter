package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.DeployLogChunkRequest;
import com.hamonsoft.netismaker.dto.DeployedTaskSummary;
import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerRuntimeStatusRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.service.WorkerService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 *  워커 API (X-Worker-API-Key 인증).
 *
 *   POST /worker/heartbeat        ─► worker_heartbeat upsert
 *   GET  /worker/next-task         ─► PENDING/APPROVED 작업 1건 또는 204
 *   POST /worker/tasks/{id}/result ─► 분석/구현 완료/실패 보고
 *   GET  /worker/deployed-tasks    ─► 배포 런타임 정합 대상(배포완료+배포중단됨) 목록
 *   POST /worker/tasks/{id}/runtime-status ─► 컨테이너 생존 관측 보고 (reconcile)
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

    @PostMapping("/tasks/{id}/deploy-log")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deployLog(@PathVariable Long id, @RequestBody @Valid DeployLogChunkRequest req) {
        workerService.appendDeployLog(id, req);
    }

    @GetMapping("/deployed-tasks")
    public List<DeployedTaskSummary> deployedTasks() {
        return workerService.listDeployReconcilable();
    }

    @PostMapping("/tasks/{id}/runtime-status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void runtimeStatus(@PathVariable Long id, @RequestBody @Valid WorkerRuntimeStatusRequest req) {
        workerService.recordRuntimeStatus(id, req);
    }
}
