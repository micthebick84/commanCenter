package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 워커가 보고하는 질문 턴. kind: question | design | gate | note.
 * contextTokens/contextWindow는 스펙 2026-09-05 §4.2 — 구버전 러너는 안 보내므로 null 허용.
 * refStates는 스펙 2026-10-22 §4.5 — 이번 턴에 참조 repo를 준비한 결과. 구버전 러너·참조 없는 질문은 null이나 [].
 */
public record WorkerQuestionRequest(
        String content,
        String claudeSessionId,
        String kind,
        BigDecimal costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationTokens,
        Long cacheReadTokens,
        /** 마지막 최상위 assistant 메시지의 input+cache_creation+cache_read. null = 미보고. */
        Long contextTokens,
        /** 주 모델의 컨텍스트 창 크기. null = 미보고. */
        Long contextWindow,
        /** 참조 repo 준비 결과 (스펙 2026-10-22 §4.5). null/빈 목록이면 세션의 참조를 바꾸지 않는다. */
        List<RefState> refStates
) {
    /**
     * refStates 도입 전 10개 인자 생성자 — 구버전 러너·기존 호출부 호환.
     * Jackson은 레코드의 정규 생성자(11개)를 쓰므로 역직렬화와 무관하다(WorkerQuestionRequestJsonTest).
     */
    public WorkerQuestionRequest(String content, String claudeSessionId, String kind, BigDecimal costUsd,
                                 Long inputTokens, Long outputTokens, Long cacheCreationTokens,
                                 Long cacheReadTokens, Long contextTokens, Long contextWindow) {
        this(content, claudeSessionId, kind, costUsd, inputTokens, outputTokens, cacheCreationTokens,
                cacheReadTokens, contextTokens, contextWindow, null);
    }

    /**
     * 참조 repo 하나의 준비 결과 (스펙 2026-10-22 §4.5) — 세션의 현재 ref_repos에서 catalogId+branch가 같은 원소에만 반영한다.
     * sha = 이번 턴에 확인한 HEAD 커밋(성공), error = 자격증명을 가린 실패 사유(실패). error가 있으면 sha는 무시한다.
     */
    public record RefState(Long catalogId, String branch, String sha, String error) {}
}
