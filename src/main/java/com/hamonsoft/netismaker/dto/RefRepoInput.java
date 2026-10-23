package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.Size;

/**
 * 질문 세션에 붙일 읽기 전용 참조 repo 한 개의 요청 값 (스펙 2026-10-22 §4.1) — 카탈로그 id + 고른 브랜치.
 * branch가 비면 카탈로그 기본 브랜치를 쓰고, 그것도 없으면 400이다. 검증과 스냅샷 해석은
 * {@code RepoCatalogService.checkRefShape}·{@code resolveReferences}가 맡는다.
 * branch 길이 상한은 대상 브랜치({@code QuestionCreateRequest.githubBranch})와 같은 255자다 — 요청 DTO의
 * refRepos 목록에 @Valid가 걸려 있어야 이 상한이 적용된다.
 */
public record RefRepoInput(Long repoCatalogId, @Size(max = 255) String branch) {
}
