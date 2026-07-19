package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

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

        /** 카탈로그에서 선택된 추가 MCP id들. null/빈 배열 허용. */
        List<Long> mcpCatalogIds,

        /** Claude 모델 id. blank면 서버 기본값(claude-opus-4-8). */
        String model,

        /** 추론 effort. blank면 서버 기본값(high). */
        String effort,

        /** 디자인 단계 포함 여부. null이면 false. */
        Boolean designRequested
) {
    /** 기존 7-arg 호출처(테스트 등) 호환용 — designRequested=null(=false)로 위임. */
    public TaskCreateRequest(Long repoCatalogId, String githubBranch, String title, String description,
                             List<Long> mcpCatalogIds, String model, String effort) {
        this(repoCatalogId, githubBranch, title, description, mcpCatalogIds, model, effort, null);
    }
}
