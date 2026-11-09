package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dbmcp.DbWriteService;
import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.service.TaskService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 구현 단계 개발 DB 쓰기 기록의 조회·승인 대기 처리 (스펙 2026-11-03 §6.5). 처리 응답은 갱신된 기록 한 건(DbChangeView) —
 * 화면은 그 행으로 목록을 고친다.
 *
 *   GET  /{id}/db-changes                        등록자 본인 또는 관리자 — 시간순
 *   POST /{id}/db-changes/{logId}/execute        ROLE_ADMIN — 승인 대기 SQL을 같은 연결로 실행(실패·이미 처리됨·거부 분류는 409)
 *   POST /{id}/db-changes/{logId}/dismiss        ROLE_ADMIN — 승인 대기 SQL을 실행 안 함으로 정리
 */
@RestController
@RequestMapping("/api/tasks")
@Profile("api")
public class TaskDbChangeController {

    private final DbWriteService writes;
    private final TaskService taskService;

    public TaskDbChangeController(DbWriteService writes, TaskService taskService) {
        this.writes = writes;
        this.taskService = taskService;
    }

    /** ACL = 작업 조회와 같다(소유자 또는 관리자, 삭제된 작업 404) — TaskFixController.list와 같은 방식. */
    @GetMapping("/{id}/db-changes")
    public List<DbChangeView> list(@PathVariable Long id, JwtAuthenticationToken auth) {
        taskService.getForView(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
        return writes.list(id);
    }

    @PostMapping("/{id}/db-changes/{logId}/execute")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public DbChangeView execute(@PathVariable Long id, @PathVariable Long logId, JwtAuthenticationToken auth) {
        return writes.executeDeferred(id, logId, AuthContext.requireUserId(auth));
    }

    @PostMapping("/{id}/db-changes/{logId}/dismiss")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public DbChangeView dismiss(@PathVariable Long id, @PathVariable Long logId, JwtAuthenticationToken auth) {
        return writes.dismiss(id, logId, AuthContext.requireUserId(auth));
    }
}
