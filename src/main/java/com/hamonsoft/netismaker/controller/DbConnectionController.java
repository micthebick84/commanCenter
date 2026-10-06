package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.service.DbConnectionService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/**
 * DB 접속정보 API (스펙 2026-10-02 §5.1). JWT 인증만 — 권한(REPO=관리자, USER=본인)은 DbConnectionService가 가른다.
 *
 *   GET    /api/db-connections?repoCatalogId=   — 그 레포의 공용 + 내 접속정보 ({enabled, items})
 *   POST   /api/db-connections                  — 생성 (scope=REPO는 관리자)
 *   PUT    /api/db-connections/{id}             — 수정 (password blank = 유지)
 *   DELETE /api/db-connections/{id}             — 삭제
 *   POST   /api/db-connections/test             — 접속 테스트 (id 지정 시 편집 권한 필요)
 */
@RestController
@RequestMapping("/api/db-connections")
@Profile("api")
public class DbConnectionController {

    private final DbConnectionService service;

    public DbConnectionController(DbConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public DbConnectionDto.ListResponse list(@RequestParam Long repoCatalogId, JwtAuthenticationToken auth) {
        return service.list(repoCatalogId, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DbConnectionDto.View create(@RequestBody @Valid DbConnectionDto.UpsertRequest req, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        return DbConnectionDto.View.of(service.create(req, userId, AuthContext.isAdmin(auth)), userId);
    }

    @PutMapping("/{id}")
    public DbConnectionDto.View update(@PathVariable Long id, @RequestBody @Valid DbConnectionDto.UpsertRequest req,
                                       JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        return DbConnectionDto.View.of(service.update(id, req, userId, AuthContext.isAdmin(auth)), userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, JwtAuthenticationToken auth) {
        service.delete(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PostMapping("/test")
    public DbConnectionDto.TestResult test(@RequestBody @Valid DbConnectionDto.TestRequest req, JwtAuthenticationToken auth) {
        return service.test(req, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }
}
