package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.RejectCloseRequest;
import com.hamonsoft.netismaker.dto.SkipArtifactRequest;
import com.hamonsoft.netismaker.dto.TaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.service.CloseMailService;
import com.hamonsoft.netismaker.service.TaskCloseService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 작업 종료 단계 (스펙 2026-10-13 §5). 상세 응답과 같은 TaskResponse를 돌려준다(분석·디자인 뷰는 생략 — 화면은 응답 뒤 재조회).
 *
 *   POST /{id}/close-request           등록자 본인 또는 관리자
 *   POST /{id}/close-request/withdraw  등록자 본인 또는 관리자
 *   POST /{id}/close/approve           ROLE_ADMIN
 *   POST /{id}/close/reject            ROLE_ADMIN (바디 reason)
 *   POST /{id}/close/retry             ROLE_ADMIN
 *   POST /{id}/close/cancel            ROLE_ADMIN (병합 전 종료실패만)
 *   POST /{id}/close/skip-artifact     ROLE_ADMIN (산출물 단계에서 멈춘 종료실패만, 바디 reason 선택)
 *   POST /{id}/close/mail/resend      ROLE_ADMIN (작업종료 + 산출물 보관 작업)
 */
@RestController
@RequestMapping("/api/tasks")
@Profile("api")
public class TaskCloseController {

    private final TaskCloseService closeService;
    private final CloseMailService mail;

    public TaskCloseController(TaskCloseService closeService, CloseMailService mail) {
        this.closeService = closeService;
        this.mail = mail;
    }

    @PostMapping("/{id}/close-request")
    public TaskResponse request(@PathVariable Long id, JwtAuthenticationToken auth) {
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = closeService.requestClose(id, AuthContext.requireUserId(auth), isAdmin, AuthContext.email(auth));
        return view(t, isAdmin);
    }

    @PostMapping("/{id}/close-request/withdraw")
    public TaskResponse withdraw(@PathVariable Long id, JwtAuthenticationToken auth) {
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = closeService.withdraw(id, AuthContext.requireUserId(auth), isAdmin);
        return view(t, isAdmin);
    }

    @PostMapping("/{id}/close/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse approve(@PathVariable Long id, JwtAuthenticationToken auth) {
        return view(closeService.approve(id, AuthContext.requireUserId(auth)), true);
    }

    @PostMapping("/{id}/close/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse reject(@PathVariable Long id, @RequestBody @Valid RejectCloseRequest body,
                               JwtAuthenticationToken auth) {
        return view(closeService.reject(id, AuthContext.requireUserId(auth), body.reason()), true);
    }

    @PostMapping("/{id}/close/retry")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse retry(@PathVariable Long id, JwtAuthenticationToken auth) {
        return view(closeService.retry(id, AuthContext.requireUserId(auth)), true);
    }

    @PostMapping("/{id}/close/cancel")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
        return view(closeService.cancel(id, AuthContext.requireUserId(auth)), true);
    }

    @PostMapping("/{id}/close/skip-artifact")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse skipArtifact(@PathVariable Long id,
                                     @RequestBody(required = false) @Valid SkipArtifactRequest body,
                                     JwtAuthenticationToken auth) {
        return view(closeService.skipArtifact(id, AuthContext.requireUserId(auth),
                body == null ? null : body.reason()), true);
    }

    @PostMapping("/{id}/close/mail/resend")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public Map<String, String> resendMail(@PathVariable Long id) {
        return Map.of("status", mail.resend(id));
    }

    /** 관리자가 아니면 비밀 env 값을 비운다 — TaskController.forViewer 그대로. */
    private static TaskResponse view(Task t, boolean isAdmin) {
        return TaskController.forViewer(TaskResponse.of(t, null), isAdmin);
    }
}
