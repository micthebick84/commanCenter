package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectDesignRequest(
        @NotBlank(message = "반려 피드백은 필수입니다")
        @Size(max = 4000)
        String feedback
) {}
