package com.hamonsoft.netismaker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 사용자 답변. replyToSeq는 idempotency 키(중복 답변 무시 기준).
 * refRepos(스펙 2026-10-24 §4.3): 작업 인터뷰 답변 중 참조 repo 변경 — 질문 ask와 같은 규칙(null = 유지, 빈 리스트 = 전부 해제,
 * 현재와 같은 (카탈로그 id + 브랜치) 집합이면 검증 없는 no-op). @Valid가 있어야 원소의 @Size(branch 255자)가 적용된다.
 */
public record AnswerRequest(
        @NotBlank String answer,
        Integer replyToSeq,
        @Valid
        List<RefRepoInput> refRepos
) {
    /** 참조 변경 없이(유지) 답변만 — 질문 ask(QuestionAskRequest.toAnswerRequest)·기존 호출처 호환. */
    public AnswerRequest(String answer, Integer replyToSeq) {
        this(answer, replyToSeq, null);
    }
}
