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
        String effort
) {}
