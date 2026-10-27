package com.hamonsoft.netismaker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 작업 등록 요청. 모델/effort/MCP/디자인 여부는 등록에서 받지 않는다 —
 * 관리자가 승인(=인터뷰 시작) 시점에 ApproveRequest로 결정한다.
 * (구 클라이언트가 그 필드들을 보내도 Jackson이 무시한다.)
 */
public record TaskCreateRequest(
        @NotNull(message = "repoCatalogId는 필수입니다 (레포 카탈로그에서 선택)")
        Long repoCatalogId,

        @Size(max = 255)
        String githubBranch,

        @NotBlank
        @Size(max = 500)
        String title,

        @NotBlank
        String description,

        /**
         * 읽기 전용 참조 repo (스펙 2026-10-24 §4.1) — 카탈로그 id + 브랜치, 최대 3개, 대상과 다른 repo만.
         * null/빈 = 없음. @Valid가 있어야 원소의 @Size(branch 255자)가 적용된다.
         */
        @Valid
        List<RefRepoInput> refRepos,

        /** "이 대화로 작업 등록"의 출처 질문 세션 id (스펙 2026-10-24 §4.1). null = 없음. */
        Long sourceQuestionId
) {
    /** 참조·출처 질문 없는 등록 — 기존 호출처/테스트 호환. */
    public TaskCreateRequest(Long repoCatalogId, String githubBranch, String title, String description) {
        this(repoCatalogId, githubBranch, title, description, null, null);
    }
}
