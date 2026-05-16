package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.BranchListResponse;
import com.hamonsoft.netismaker.service.GitRefService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub 레포 메타 조회 — 작업 등록 폼 지원용.
 *
 *   GET /api/repos/branches?repo=owner/repo
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

    public RepoController(GitRefService git) {
        this.git = git;
    }

    @GetMapping("/branches")
    @PreAuthorize("isAuthenticated()")
    public BranchListResponse branches(@RequestParam String repo) {
        return git.listBranches(repo);
    }
}
