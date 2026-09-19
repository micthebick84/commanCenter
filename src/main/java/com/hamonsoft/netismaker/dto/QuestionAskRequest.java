package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 추가 질문(POST /api/questions/{id}/ask). answer/replyToSeq는 AnswerRequest와 같은 의미
 * (replyToSeq = idempotency 키). model/effort는 선택 — 값이 있으면 ModelEffortPolicy로 조합을 검증한 뒤
 * 세션 값을 갱신해 <b>다음 claim(턴)부터</b> 적용된다(대화 중 모델·effort 변경, 스펙 2026-09-05 §2 개정).
 * null/blank는 "현재 값 유지" — 등록 시와 달리 기본값으로 되돌리지 않는다.
 *
 * mcpCatalogIds(스펙 2026-09-13 §5.1): 대화 중 MCP 변경. <b>null = 유지, 빈 리스트 = 전부 해제</b> —
 * 문자열의 blank=유지 규칙과 비대칭이니 주의. 현재 선택과 집합이 같으면 검증 없는 no-op.
 */
public record QuestionAskRequest(
        @NotBlank String answer,
        Integer replyToSeq,
        String model,
        String effort,
        List<Long> mcpCatalogIds
) {
    /** MCP 변경 없이(유지) 답변만 — 기존 호출처/테스트 호환. */
    public QuestionAskRequest(String answer, Integer replyToSeq, String model, String effort) {
        this(answer, replyToSeq, model, effort, null);
    }

    /** 상태 전이(InterviewService.submitAnswer)에는 답변 부분만 넘긴다. */
    public AnswerRequest toAnswerRequest() {
        return new AnswerRequest(answer, replyToSeq);
    }
}
