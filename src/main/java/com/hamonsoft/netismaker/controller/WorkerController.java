package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbMcpGrantService;
import com.hamonsoft.netismaker.dto.ActiveWorktreeTaskSummary;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.DeployLogChunkRequest;
import com.hamonsoft.netismaker.dto.CloseProgressRequest;
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
 *   POST /worker/tasks/{id}/close-progress ─► 종료 단계(병합·배포 중지·노트·브랜치 정리) 완료 보고
 *   GET  /worker/deployed-tasks    ─► 배포 런타임 정합 대상(배포완료+배포중단됨) 목록
 *   GET  /worker/active-worktree-tasks ─► worktree 정리 보호 목록(구현중·디자인중·배포중·배포중지중, soft-delete 제외)
 *   POST /worker/tasks/{id}/runtime-status ─► 컨테이너 생존 관측 보고 (reconcile)
 */
@RestController
@RequestMapping("/worker")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class WorkerController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkerController.class);

    private final WorkerService workerService;
    private final DbMcpGrantService dbMcpGrantService;

    public WorkerController(WorkerService workerService, DbMcpGrantService dbMcpGrantService) {
        this.workerService = workerService;
        this.dbMcpGrantService = dbMcpGrantService;
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@RequestBody @Valid WorkerHeartbeatRequest req) {
        workerService.heartbeat(req);
    }

    @GetMapping("/next-task")
    public ResponseEntity<WorkerTaskResponse> nextTask(@RequestParam String workerId) {
        Optional<WorkerTaskResponse> claimed = workerService.claimNextTask(workerId).map(this::withDbMcp);
        return claimed.map(ResponseEntity::ok)
                      .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 구현·디자인 claim에 DB MCP 지시를 붙인다 (스펙 2026-10-14 §6.4). claim 트랜잭션은 이미 커밋됐으므로
     * 여기서 실패해도 claim 응답을 깨지 않는다 — DB 없이 진행.
     */
    private WorkerTaskResponse withDbMcp(WorkerTaskResponse r) {
        if (r.kind() != WorkerTaskResponse.Kind.IMPLEMENTATION && r.kind() != WorkerTaskResponse.Kind.DESIGN) return r;
        try {
            DbMcpGrant g = dbMcpGrantService.grantFor(r.id());
            return g == null ? r : r.withDbMcp(g);
        } catch (RuntimeException e) {
            log.warn("claim DB MCP 준비 실패 task={}: {}", r.id(), e.getClass().getSimpleName());
            return r.withDbMcp(new DbMcpGrant(null, List.of(), List.of("DB 연결을 준비하지 못해 DB 도구 없이 진행합니다")));
        }
    }

    @PostMapping("/tasks/{id}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@PathVariable Long id, @RequestBody @Valid WorkerResultRequest req) {
        workerService.recordResult(id, req);
    }

    @PostMapping("/tasks/{id}/close-progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeProgress(@PathVariable Long id, @RequestBody @Valid CloseProgressRequest req) {
        workerService.recordCloseProgress(id, req);
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

    @GetMapping("/active-worktree-tasks")
    public List<ActiveWorktreeTaskSummary> activeWorktreeTasks() {
        return workerService.listWorktreeActive();
    }

    @PostMapping("/tasks/{id}/runtime-status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void runtimeStatus(@PathVariable Long id, @RequestBody @Valid WorkerRuntimeStatusRequest req) {
        workerService.recordRuntimeStatus(id, req);
    }
}
