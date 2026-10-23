package com.hamonsoft.netismaker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 질문 세션 등록 요청. 승인 게이트가 없으므로 모델/effort/MCP를 등록자가 여기서 정한다
 * (스펙 2026-08-30 §2). model/effort/mcpCatalogIds는 optional — 없으면 ModelEffortPolicy 기본값 + MCP 없음.
 * refRepos(스펙 2026-10-22 §4.1)도 optional — null/빈 목록이면 참조 repo 없음.
 */
public record QuestionCreateRequest(
        @NotNull(message = "repoCatalogId는 필수입니다 (레포 카탈로그에서 선택)")
        Long repoCatalogId,

        @Size(max = 255)
        String githubBranch,

        /** 선택. blank면 서버가 질문 첫 줄로 생성한다 (QuestionService.deriveTitle, 스펙 2026-09-05 §2). */
        @Size(max = 500)
        String title,

        /** 질문 본문 — InterviewSession.description에 저장되고 킥오프 프롬프트에 그대로 삽입되므로 길이 상한 필요. */
        @NotBlank
        @Size(max = 20000)
        String question,

        String model,
        String effort,
        List<Long> mcpCatalogIds,
        /** 선택한 DB 접속정보 id (스펙 2026-10-02 §5.3). null/빈 = 없음. 세션 소유자 기준 검증. */
        List<Long> dbConnectionIds,

        /**
         * 읽기 전용 참조 repo (스펙 2026-10-22 §4.1) — 카탈로그 id + 브랜치, 최대 3개, 대상과 다른 repo만.
         * null/빈 = 없음. @Valid가 있어야 원소의 @Size(branch 255자)가 적용된다.
         */
        @Valid
        List<RefRepoInput> refRepos
) {
    /** 참조 repo 없는 등록 — 기존 호출처/테스트 호환. */
    public QuestionCreateRequest(Long repoCatalogId, String githubBranch, String title, String question,
                                 String model, String effort, List<Long> mcpCatalogIds,
                                 List<Long> dbConnectionIds) {
        this(repoCatalogId, githubBranch, title, question, model, effort, mcpCatalogIds, dbConnectionIds, null);
    }

    /** DB·참조 선택 없는 등록 — 기존 호출처/테스트 호환. */
    public QuestionCreateRequest(Long repoCatalogId, String githubBranch, String title, String question,
                                 String model, String effort, List<Long> mcpCatalogIds) {
        this(repoCatalogId, githubBranch, title, question, model, effort, mcpCatalogIds, null, null);
    }
}
