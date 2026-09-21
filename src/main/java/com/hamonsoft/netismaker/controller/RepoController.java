package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.BranchListResponse;
import com.hamonsoft.netismaker.service.GitRefService;
import com.hamonsoft.netismaker.service.RepoCatalogService;
import com.hamonsoft.netismaker.service.TaskException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 레포 메타 조회 — 작업 등록 폼 지원용.
 *
 *   GET /api/repos/branches?repo=owner/repo
 *   GET /api/repos/branches?catalogId={id}
 *     → BranchListResponse { repo, defaultBranch, branches[], fetchedAt }
 *     · 잘못된 형식: 400
 *     · 레포 없음/비공개: 404
 *     · git 실행 오류: 502/504
 */
@RestController
@RequestMapping("/api/repos")
@Profile("api")
public class RepoController {

    private final GitRefService git;
    private final RepoCatalogService catalog;

    public RepoController(GitRefService git, RepoCatalogService catalog) {
        this.git = git;
        this.catalog = catalog;
    }

    @GetMapping("/branches")
    @PreAuthorize("isAuthenticated()")
    public BranchListResponse branches(@RequestParam(required = false) String repo,
                                       @RequestParam(required = false) Long catalogId) {
        if ((repo == null) == (catalogId == null)) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "repo 또는 catalogId 중 하나만 지정해야 합니다");
        }
        return catalogId != null ? git.listBranches(catalog.refOf(catalogId)) : git.listBranches(repo);
    }
}
