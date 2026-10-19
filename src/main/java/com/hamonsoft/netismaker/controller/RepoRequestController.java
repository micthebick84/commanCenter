package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.RequestBoardDto;
import com.hamonsoft.netismaker.service.RepoRequestService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REPO 추가 요청 관리자 API (스펙 2026-10-19). 전부 관리자 전용.
 *
 *   GET  /api/admin/requests/{id}/repo-candidates — ADMIN (요청 내용에서 추측한 사내 GitLab 후보, 최대 5개)
 *   GET  /api/admin/gitlab-projects?search=       — ADMIN (사내 GitLab 프로젝트 직접 검색, 최대 10개)
 *   POST /api/admin/requests/{id}/add-repo        — ADMIN (후보 하나를 카탈로그에 등록하고 처리완료로 닫음)
 *
 * GitLab 미설정은 503, 조회 실패는 502(서비스·클라이언트가 던진다). 요청 상세·목록 등 다른 경로에는 영향이 없다.
 */
@RestController
@Profile("api")
public class RepoRequestController {

    private final RepoRequestService service;

    public RepoRequestController(RepoRequestService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/requests/{id}/repo-candidates")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public List<RequestBoardDto.RepoCandidate> candidates(@PathVariable Long id) {
        return service.candidates(id);
    }

    @GetMapping("/api/admin/gitlab-projects")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public List<RequestBoardDto.RepoCandidate> search(@RequestParam(name = "search", required = false) String search) {
        return service.search(search);
    }

    @PostMapping("/api/admin/requests/{id}/add-repo")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RequestBoardDto.Detail addRepo(@PathVariable Long id,
                                          @RequestBody @Valid RequestBoardDto.AddRepoRequest req,
                                          JwtAuthenticationToken auth) {
        return service.addRepo(id, req.gitlabProjectId(), AuthContext.requireUserId(auth));
    }
}
