package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 수정 요청 등록 바디 (스펙 2026-11-03 §6.1). implementationMode(DIRECT|REVIEW)는 관리자가 낸 요청에서만 쓰인다 —
 * 등록자의 요청은 승인 때 관리자가 방식을 고르므로 무시한다. 비면 REVIEW.
 */
public record FixRequestCreateRequest(
        @NotBlank(message = "수정 요청 내용은 필수입니다")
        @Size(max = 20000, message = "수정 요청 내용은 20000자 이하여야 합니다")
        String body,
        String implementationMode
) {}
