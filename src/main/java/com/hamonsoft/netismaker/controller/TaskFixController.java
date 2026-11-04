package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.FixRequestApproveRequest;
import com.hamonsoft.netismaker.dto.FixRequestCreateRequest;
import com.hamonsoft.netismaker.dto.FixRequestRejectRequest;
import com.hamonsoft.netismaker.dto.FixRequestView;
import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.TaskFixService;
import com.hamonsoft.netismaker.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 배포 뒤 수정 요청 (스펙 2026-11-03 §6). 상세 응답과 같은 TaskResponse를 돌려준다(분석·디자인 뷰·회차 현황은 생략 —
 * 화면은 응답 뒤 재조회, TaskCloseController와 같다).
 *
 *   POST /{id}/fix-requests           등록자 본인 또는 관리자 (바디 body, 관리자만 implementationMode)
 *   POST /{id}/fix-requests/approve   ROLE_ADMIN (바디 implementationMode 선택)
 *   POST /{id}/fix-requests/reject    ROLE_ADMIN (바디 reason)
 *   POST /{id}/fix-requests/withdraw  등록자 본인 또는 관리자
 *   POST /{id}/fix-requests/abort     ROLE_ADMIN — 수정 회차의 구현실패 → PR생성(회차 닫힘, §7.1; 이전 배포가 떠 있으면 배포중지대기)
 *   GET  /{id}/fix-requests           등록자 본인 또는 관리자 — 수정 요청 기록, 최신순
 */
@RestController
@RequestMapping("/api/tasks")
@Profile("api")
public class TaskFixController {

    private final TaskFixService fixService;
    private final TaskService taskService;

    public TaskFixController(TaskFixService fixService, TaskService taskService) {
        this.fixService = fixService;
        this.taskService = taskService;
    }

    @PostMapping("/{id}/fix-requests")
    public TaskResponse request(@PathVariable Long id, @RequestBody @Valid FixRequestCreateRequest body,
                                JwtAuthenticationToken auth) {
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = fixService.request(id, AuthContext.requireUserId(auth), isAdmin,
                body.body(), body.implementationMode());
        return view(t, isAdmin);
    }

    @PostMapping("/{id}/fix-requests/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse approve(@PathVariable Long id,
                                @RequestBody(required = false) @Valid FixRequestApproveRequest body,
                                JwtAuthenticationToken auth) {
        return view(fixService.approve(id, AuthContext.requireUserId(auth),
                body == null ? null : body.implementationMode()), true);
    }

    @PostMapping("/{id}/fix-requests/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse reject(@PathVariable Long id, @RequestBody @Valid FixRequestRejectRequest body,
                               JwtAuthenticationToken auth) {
        return view(fixService.reject(id, AuthContext.requireUserId(auth), body.reason()), true);
    }

    @PostMapping("/{id}/fix-requests/withdraw")
    public TaskResponse withdraw(@PathVariable Long id, JwtAuthenticationToken auth) {
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = fixService.withdraw(id, AuthContext.requireUserId(auth), isAdmin);
        return view(t, isAdmin);
    }

    /** 수정 회차의 구현실패 → PR생성, 회차 닫힘 (스펙 §7.1). 바디 없음. */
    @PostMapping("/{id}/fix-requests/abort")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse abort(@PathVariable Long id, JwtAuthenticationToken auth) {
        return view(fixService.abort(id, AuthContext.requireUserId(auth)), true);
    }

    /** ACL = 작업 조회와 같다(소유자 또는 관리자, 삭제된 작업 404) — TaskController.history와 같은 방식. */
    @GetMapping("/{id}/fix-requests")
    public List<FixRequestView> list(@PathVariable Long id, JwtAuthenticationToken auth) {
        taskService.getForView(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
        return fixService.list(id).stream().map(FixRequestView::of).toList();
    }

    /** 관리자가 아니면 비밀 env 값을 비운다 — TaskController.forViewer 그대로. */
    private static TaskResponse view(Task t, boolean isAdmin) {
        return TaskController.forViewer(TaskResponse.of(t, null), isAdmin);
    }
}
