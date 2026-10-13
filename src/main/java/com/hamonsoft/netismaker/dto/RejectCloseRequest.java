package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectCloseRequest(
        @NotBlank(message = "반려 사유는 필수입니다")
        @Size(max = 2000)
        String reason
) {}
