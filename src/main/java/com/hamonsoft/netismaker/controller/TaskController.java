package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.DeployRequest;
import com.hamonsoft.netismaker.dto.RejectDesignRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.service.DeployLogStreamService;
import com.hamonsoft.netismaker.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;

/**
 *  사용자/관리자 작업 API. DESIGN §12 표면 그대로.
 *
 *  권한:
 *    GET/POST/cancel/delete/retry — JWT(USER 또는 ADMIN)
 *    POST /approve                — ROLE_ADMIN 한정 (@PreAuthorize)
 */
@RestController
@RequestMapping("/api/tasks")
@Profile("api")
public class TaskController {

    private final TaskService taskService;
    private final DeployLogStreamService deployLogStream;

    public TaskController(TaskService taskService, DeployLogStreamService deployLogStream) {
        this.taskService = taskService;
        this.deployLogStream = deployLogStream;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@RequestBody @Valid TaskCreateRequest req,
                                               JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        Task t = taskService.create(req, userId);
        TaskResponse body = TaskResponse.of(t, null);
        return ResponseEntity.created(URI.create("/api/tasks/" + t.getId())).body(body);
    }

    @GetMapping
    public Page<TaskResponse> list(@RequestParam(required = false) TaskStatus status,
                                   @RequestParam(defaultValue = "false") boolean mine,
                                   @PageableDefault(size = 20) Pageable pageable,
                                   JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        return taskService.list(userId, isAdmin, mine, status, pageable)
                .map(t -> TaskResponse.of(t, null));
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = taskService.getForView(id, userId, isAdmin);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null));
    }

    @PostMapping("/{id}/cancel")
    public TaskResponse cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = taskService.cancel(id, userId, isAdmin);
        return TaskResponse.of(t, null);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        taskService.softDelete(id, userId, isAdmin);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse approve(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.approve(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    @PostMapping("/{id}/design/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse approveDesign(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.approveDesign(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null));
    }

    @PostMapping("/{id}/design/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse rejectDesign(@PathVariable Long id,
                                     @RequestBody @Valid RejectDesignRequest body,
                                     JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.rejectDesign(id, adminId, body.feedback());
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null));
    }

    @PostMapping("/{id}/retry")
    public TaskResponse retry(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = taskService.retry(id, userId, isAdmin);
        return TaskResponse.of(t, null);
    }

    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse deploy(@PathVariable Long id,
                               @Valid @RequestBody(required = false) DeployRequest body,
                               JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.deploy(id, adminId, body == null ? null : body.envVars());
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    @PostMapping("/{id}/redeploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse redeploy(@PathVariable Long id,
                                 @Valid @RequestBody(required = false) DeployRequest body,
                                 JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.redeploy(id, adminId, body == null ? null : body.envVars());
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    @PostMapping("/{id}/undeploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse undeploy(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.undeploy(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    @GetMapping(value = "/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logsStream(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        taskService.getForView(id, userId, isAdmin); // 접근 권한 검증 (없으면 예외)
        return deployLogStream.subscribe(id);
    }
}
