package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.McpCatalogDto;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.service.McpCatalogService;
import com.hamonsoft.netismaker.service.McpHealthService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 *  MCP 카탈로그 API.
 *
 *   GET    /api/mcp-catalog                 — 인증 사용자 모두 (활성화된 항목만)
 *   GET    /api/admin/mcp-catalog           — ADMIN (전체)
 *   POST   /api/admin/mcp-catalog           — ADMIN (등록)
 *   PUT    /api/admin/mcp-catalog/{id}      — ADMIN (수정)
 *   DELETE /api/admin/mcp-catalog/{id}      — ADMIN (삭제)
 *
 *  사용자가 작업 등록 시 활성 카탈로그에서 선택, 선택된 항목은 task.mcps_extra에 스냅샷으로 박제.
 */
@RestController
@Profile("api")
public class McpCatalogController {

    private final McpCatalogService service;
    private final McpHealthService healthService;

    public McpCatalogController(McpCatalogService service, McpHealthService healthService) {
        this.service = service;
        this.healthService = healthService;
    }

    @GetMapping("/api/mcp-catalog")
    @PreAuthorize("isAuthenticated()")
    public List<McpCatalogDto.View> listForUsers() {
        return service.listEnabled().stream().map(McpCatalogDto.View::of).toList();
    }

    @GetMapping("/api/admin/mcp-catalog")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public List<McpCatalogDto.View> listForAdmins() {
        return service.listAll().stream().map(McpCatalogDto.View::of).toList();
    }

    @PostMapping("/api/admin/mcp-catalog")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public McpCatalogDto.View create(@RequestBody @Valid McpCatalogDto.UpsertRequest req,
                                     JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        McpCatalogEntry saved = service.create(req, adminId);
        return McpCatalogDto.View.of(saved);
    }

    @PutMapping("/api/admin/mcp-catalog/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public McpCatalogDto.View update(@PathVariable Long id,
                                     @RequestBody @Valid McpCatalogDto.UpsertRequest req) {
        return McpCatalogDto.View.of(service.update(id, req));
    }

    @DeleteMapping("/api/admin/mcp-catalog/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 수동 헬스 체크 — 즉시 probe + DB 갱신 + 결과 반환. */
    @PostMapping("/api/admin/mcp-catalog/{id}/check")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public Map<String, Object> check(@PathVariable Long id) {
        McpHealthService.ProbeResult r = healthService.checkAndPersist(id);
        return Map.of(
                "status", r.status(),
                "error", r.error() == null ? "" : r.error(),
                "elapsedMs", r.elapsedMs()
        );
    }
}
