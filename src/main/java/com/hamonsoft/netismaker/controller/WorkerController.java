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
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.service.WorkerService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 *  워커 API (X-Worker-API-Key 인증).
 *
 *   POST /worker/heartbeat        ─► worker_heartbeat upsert
 *   GET  /worker/next-task         ─► PENDING/APPROVED 작업 1건 또는 204 (supports=refRepos 없으면 참조 있는 구현은 제외, kinds로 종류 제한)
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

    /** next-task의 supports 토큰 — 참조 repo를 아는 새 워커가 보낸다. */
    private static final String SUPPORTS_REF_REPOS = "refRepos";

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

    /**
     * supports: 워커가 아는 기능 목록(쉼표 구분). {@value #SUPPORTS_REF_REPOS}가 없으면(구버전 워커) 참조 repo가 있는
     * 구현대기는 넘기지 않는다 (스펙 2026-10-24 §4.7).
     * kinds: 이 워커가 받을 작업 종류(쉼표 구분, {@link WorkerTaskResponse.Kind} 이름). 없거나 비면 전부 —
     * 다른 PC의 워커가 볼트·미리 빌드 도구가 필요한 종료·배포를 잡지 않게 구현·디자인만 받을 때 쓴다.
     */
    @GetMapping("/next-task")
    public ResponseEntity<WorkerTaskResponse> nextTask(@RequestParam String workerId,
                                                       @RequestParam(required = false) String supports,
                                                       @RequestParam(required = false) String kinds) {
        Set<WorkerTaskResponse.Kind> accepted = parseKinds(kinds);
        Optional<WorkerTaskResponse> claimed = (accepted == null
                ? workerService.claimNextTask(workerId, supportsRefRepos(supports))
                : workerService.claimNextTask(workerId, supportsRefRepos(supports), accepted))
                .map(this::withDbMcp);
        return claimed.map(ResponseEntity::ok)
                      .orElseGet(() -> ResponseEntity.noContent().build());
    }

    ResponseEntity<WorkerTaskResponse> nextTask(String workerId, String supports) {
        return nextTask(workerId, supports, null);
    }

    /** kinds를 쉼표로 나눠 종류 이름으로 읽는다(대소문자 무시). null·빈 값이면 null(=전부), 모르는 이름이면 400. */
    static Set<WorkerTaskResponse.Kind> parseKinds(String kinds) {
        if (kinds == null || kinds.isBlank()) return null;
        Set<WorkerTaskResponse.Kind> out = EnumSet.noneOf(WorkerTaskResponse.Kind.class);
        for (String token : kinds.split(",")) {
            String name = token.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            try {
                out.add(WorkerTaskResponse.Kind.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new TaskException(HttpStatus.BAD_REQUEST, "모르는 작업 종류: " + token.trim());
            }
        }
        if (out.isEmpty()) return null;
        return out;
    }

    /** supports를 쉼표로 나눠 앞뒤 공백을 지운 토큰 중 {@value #SUPPORTS_REF_REPOS}와 정확히 같은 것이 있는지. */
    static boolean supportsRefRepos(String supports) {
        if (supports == null) return false;
        for (String token : supports.split(",")) {
            if (SUPPORTS_REF_REPOS.equals(token.trim())) return true;
        }
        return false;
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
