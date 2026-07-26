package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
        String description
) {}
