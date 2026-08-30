package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 질문 세션 등록 요청. 승인 게이트가 없으므로 모델/effort/MCP를 등록자가 여기서 정한다
 * (스펙 2026-08-30 §2). model/effort/mcpCatalogIds는 optional — 없으면 ModelEffortPolicy 기본값 + MCP 없음.
 */
public record QuestionCreateRequest(
        @NotNull(message = "repoCatalogId는 필수입니다 (레포 카탈로그에서 선택)")
        Long repoCatalogId,

        @Size(max = 255)
        String githubBranch,

        @NotBlank
        @Size(max = 500)
        String title,

        /** 질문 본문 — InterviewSession.description에 저장. */
        @NotBlank
        String question,

        String model,
        String effort,
        List<Long> mcpCatalogIds
) {}
