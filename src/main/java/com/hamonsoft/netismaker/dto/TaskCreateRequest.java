package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TaskCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$",
                 message = "github_repo는 'owner/repo' 형식이어야 합니다")
        @Size(max = 255)
        String githubRepo,

        @Size(max = 255)
        String githubBranch,

        @NotBlank
        @Size(max = 500)
        String title,

        @NotBlank
        String description,

        /** 카탈로그에서 선택된 추가 MCP id들. null/빈 배열 허용. */
        List<Long> mcpCatalogIds
) {}
