package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.RepoCatalogDto;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.service.RepoCatalogService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 *  레포 카탈로그 API.
 *
 *   GET    /api/repo-catalog               — 인증 사용자 모두 (활성 항목만) — 등록 폼 드롭다운용
 *   GET    /api/admin/repo-catalog         — ADMIN (전체)
 *   POST   /api/admin/repo-catalog         — ADMIN (등록)
 *   PUT    /api/admin/repo-catalog/{id}    — ADMIN (수정)
 *   DELETE /api/admin/repo-catalog/{id}    — ADMIN (삭제)
 *   POST   /api/admin/repo-catalog/{id}/check — ADMIN (라이브 ls-remote 도달성 확인)
 *
 *  사용자가 작업/인터뷰 등록 시 활성 카탈로그에서 선택, 선택 항목은 owner/repo·git_url·alias 스냅샷으로 박제.
 */
@RestController
@Profile("api")
public class RepoCatalogController {

    private final RepoCatalogService service;

    public RepoCatalogController(RepoCatalogService service) {
        this.service = service;
    }

    @GetMapping("/api/repo-catalog")
    @PreAuthorize("isAuthenticated()")
    public List<RepoCatalogDto.View> listForUsers() {
        return service.listEnabled().stream().map(RepoCatalogDto.View::of).toList();
    }

    @GetMapping("/api/admin/repo-catalog")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public List<RepoCatalogDto.View> listForAdmins() {
        return service.listAll().stream().map(RepoCatalogDto.View::of).toList();
    }

    @PostMapping("/api/admin/repo-catalog")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public RepoCatalogDto.View create(@RequestBody @Valid RepoCatalogDto.UpsertRequest req,
                                      JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        RepoCatalogEntry saved = service.create(req, adminId);
        return RepoCatalogDto.View.of(saved);
    }

    @PutMapping("/api/admin/repo-catalog/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RepoCatalogDto.View update(@PathVariable Long id,
                                      @RequestBody @Valid RepoCatalogDto.UpsertRequest req) {
        return RepoCatalogDto.View.of(service.update(id, req));
    }

    @DeleteMapping("/api/admin/repo-catalog/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/api/admin/repo-catalog/{id}/check")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RepoCatalogDto.CheckResult check(@PathVariable Long id) {
        return service.check(id);
    }
}
