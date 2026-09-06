package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 추가 질문(POST /api/questions/{id}/ask). answer/replyToSeq는 AnswerRequest와 같은 의미
 * (replyToSeq = idempotency 키). model/effort는 선택 — 값이 있으면 ModelEffortPolicy로 조합을 검증한 뒤
 * 세션 값을 갱신해 <b>다음 claim(턴)부터</b> 적용된다(대화 중 모델·effort 변경, 스펙 2026-09-05 §2 개정).
 * null/blank는 "현재 값 유지" — 등록 시와 달리 기본값으로 되돌리지 않는다.
 */
public record QuestionAskRequest(
        @NotBlank String answer,
        Integer replyToSeq,
        String model,
        String effort
) {
    /** 상태 전이(InterviewService.submitAnswer)에는 답변 부분만 넘긴다. */
    public AnswerRequest toAnswerRequest() {
        return new AnswerRequest(answer, replyToSeq);
    }
}
