package com.hamonsoft.netismaker.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 작업 등록 폼에서 GitHub 레포의 브랜치 목록을 채우기 위한 응답.
 *
 * defaultBranch는 git ls-remote --symref HEAD의 결과 (예: "main", "master").
 * branches는 refs/heads/* 만 (태그·풀리퀘스트·notes 제외).
 */
public record BranchListResponse(
        String repo,
        String defaultBranch,
        List<BranchEntry> branches,
        OffsetDateTime fetchedAt
) {
    public record BranchEntry(String name, String sha) {}
}
