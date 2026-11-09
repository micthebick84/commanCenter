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
        Long sourceQuestionId,

        /**
         * 작업 타입 (스펙 2026-11-03 §4.2) — {@code TaskType} enum 이름 그대로(NEW_FEATURE·FEATURE_CHANGE·BUG_FIX·DB_WORK).
         * 문자열로 받아 서비스가 저장 전에 해석한다(앞뒤 공백은 자르고 대소문자 구분, 모르는 값은 400).
         * null·공백 = 타입 없음 — 키가 없는 옛 화면·다른 호출자를 막지 않는다.
         */
        String taskType
) {
    /** 타입 도입 전 6인자(참조·출처 질문) — 기존 호출처/테스트 호환. 타입 없음. */
    public TaskCreateRequest(Long repoCatalogId, String githubBranch, String title, String description,
                             List<RefRepoInput> refRepos, Long sourceQuestionId) {
        this(repoCatalogId, githubBranch, title, description, refRepos, sourceQuestionId, null);
    }

    /** 참조·출처 질문 없는 등록 — 기존 호출처/테스트 호환. */
    public TaskCreateRequest(Long repoCatalogId, String githubBranch, String title, String description) {
        this(repoCatalogId, githubBranch, title, description, null, null, null);
    }
}
