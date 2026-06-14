package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;

/** 사용자 답변. replyToSeq는 idempotency 키(중복 답변 무시 기준). */
public record AnswerRequest(
        @NotBlank String answer,
        Integer replyToSeq
) {}
