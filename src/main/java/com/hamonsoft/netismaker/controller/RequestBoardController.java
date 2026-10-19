package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestStatus;
import com.hamonsoft.netismaker.service.RequestBoardService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 요청 게시판 API (스펙 2026-10-19). 남의 요청은 존재 자체를 숨겨 404로 답한다(서비스 규칙).
 *
 *   GET    /api/requests?all=&kind=&status=&includeUnread= — 인증. 관리자이고 all=true면 전체(필터 적용), 그 밖에는 내 요청만
 *   POST   /api/requests                                   — 인증 (201)
 *   GET    /api/requests/{id}                              — 인증 (본인 또는 관리자, 열람 시 읽음 처리)
 *   PUT    /api/requests/{id}                              — 인증 (본인, 접수 상태에서만)
 *   DELETE /api/requests/{id}                              — 인증 (본인, 접수 상태·관리자 답글 없을 때만) (204)
 *   POST   /api/requests/{id}/comments                     — 인증 (본인 또는 관리자) (201)
 *   POST   /api/admin/requests/{id}/status                 — ADMIN (상태 변경)
 *   GET    /api/admin/requests/summary                     — ADMIN (배지용 건수)
 */
@RestController
@Profile("api")
public class RequestBoardController {

    private final RequestBoardService service;

    public RequestBoardController(RequestBoardService service) {
        this.service = service;
    }

    @GetMapping("/api/requests")
    @PreAuthorize("isAuthenticated()")
    public List<RequestBoardDto.ListItem> list(
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) RequestKind kind,
            @RequestParam(required = false) Set<RequestStatus> status,
            @RequestParam(required = false, defaultValue = "true") boolean includeUnread,
            JwtAuthenticationToken auth) {
        // 비관리자가 보낸 all=true는 무시한다.
        if (all && AuthContext.isAdmin(auth)) {
            return service.listForAdmin(kind, status, includeUnread);
        }
        return service.listMine(AuthContext.requireUserId(auth));
    }

    @PostMapping("/api/requests")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestBoardDto.ListItem create(@RequestBody @Valid RequestBoardDto.CreateRequest req,
                                           JwtAuthenticationToken auth) {
        return service.create(req, AuthContext.requireUserId(auth));
    }

    @GetMapping("/api/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    public RequestBoardDto.Detail get(@PathVariable Long id, JwtAuthenticationToken auth) {
        return service.getAndMarkRead(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PutMapping("/api/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    public RequestBoardDto.Detail update(@PathVariable Long id,
                                         @RequestBody @Valid RequestBoardDto.UpdateRequest req,
                                         JwtAuthenticationToken auth) {
        return service.update(id, req, AuthContext.requireUserId(auth));
    }

    @DeleteMapping("/api/requests/{id}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
        service.cancel(id, AuthContext.requireUserId(auth));
    }

    @PostMapping("/api/requests/{id}/comments")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestBoardDto.CommentView addComment(@PathVariable Long id,
                                                  @RequestBody @Valid RequestBoardDto.CommentRequest req,
                                                  JwtAuthenticationToken auth) {
        return service.addComment(id, req, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PostMapping("/api/admin/requests/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RequestBoardDto.Detail changeStatus(@PathVariable Long id,
                                               @RequestBody @Valid RequestBoardDto.StatusChangeRequest req,
                                               JwtAuthenticationToken auth) {
        return service.changeStatus(id, req, AuthContext.requireUserId(auth));
    }

    @GetMapping("/api/admin/requests/summary")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RequestBoardDto.Summary summary() {
        return service.summary();
    }
}
