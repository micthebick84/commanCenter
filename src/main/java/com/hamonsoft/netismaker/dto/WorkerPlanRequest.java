package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 워커가 writing-plans 완료 시 보고하는 산출물.
 * refStates는 스펙 2026-10-24 §4.5 — 계획 턴에 참조 repo를 준비한 결과(질문 보고의 refStates와 같은 모양).
 * 참조 없는 인터뷰·구버전 러너는 보내지 않는다(null — 세션의 참조를 바꾸지 않는다).
 * claudeSessionId는 설계 2026-11-03-interview-superpowers-toggle §4.1 — 간단 인터뷰(superpowers 끔) 러너만 싣는다.
 * 첫 턴에 바로 계획이 나온 세션은 질문 보고가 없어 세션 id가 저장되지 않으므로, 계획 보고가 저장해야 "계획 고쳐 달라기"가
 * 같은 Claude 대화를 잇는다. 켬·구버전 러너는 보내지 않는다(null — 세션 값 유지).
 */
public record WorkerPlanRequest(
        String designMarkdown,
        String planMarkdown,
        String planJson,
        BigDecimal costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationTokens,
        Long cacheReadTokens,
        Long durationMs,
        /** 참조 repo 준비 결과 (스펙 2026-10-24 §4.5). null/빈 목록이면 세션의 참조를 바꾸지 않는다. */
        List<WorkerQuestionRequest.RefState> refStates,
        /** 이 턴의 Claude 세션 id (설계 2026-11-03-interview-superpowers-toggle §4.1). null/공백이면 세션 값을 바꾸지 않는다. */
        String claudeSessionId
) {
    /**
     * claudeSessionId 도입 전 10개 인자 생성자 — 기존 호출부 호환(세션 id 없음).
     * Jackson은 레코드의 정규 생성자(11개)를 쓰므로 역직렬화와 무관하다(WorkerPlanRequestJsonTest).
     */
    public WorkerPlanRequest(String designMarkdown, String planMarkdown, String planJson, BigDecimal costUsd,
                             Long inputTokens, Long outputTokens, Long cacheCreationTokens,
                             Long cacheReadTokens, Long durationMs,
                             List<WorkerQuestionRequest.RefState> refStates) {
        this(designMarkdown, planMarkdown, planJson, costUsd, inputTokens, outputTokens, cacheCreationTokens,
                cacheReadTokens, durationMs, refStates, null);
    }

    /** refStates 도입 전 9개 인자 생성자 — 구버전 러너·기존 호출부 호환. */
    public WorkerPlanRequest(String designMarkdown, String planMarkdown, String planJson, BigDecimal costUsd,
                             Long inputTokens, Long outputTokens, Long cacheCreationTokens,
                             Long cacheReadTokens, Long durationMs) {
        this(designMarkdown, planMarkdown, planJson, costUsd, inputTokens, outputTokens, cacheCreationTokens,
                cacheReadTokens, durationMs, null, null);
    }
}
