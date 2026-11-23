package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 질문 화면에 내려 주는 제안 한 건 (스펙 2026-11-22 §6.2) — 스냅샷(InterviewResponse.suggestions)과 SSE suggestion 이벤트 공용.
 * dbOptions는 PENDING인 DB 제안에만 채운다(세션 요청자 기준 선택지, 보는 사람 기준이 아니다). repoAlias·defaultBranch는 참조 제안에만,
 * 카탈로그 행이 지워졌으면 null. 접속정보·카탈로그 description은 담지 않는다.
 */
public record SuggestionView(
        long id,
        int turnSeq,
        String kind,
        String status,
        String reason,
        List<String> targets,
        Long dbConnectionId,
        Long repoCatalogId,
        String repoAlias,
        String defaultBranch,
        List<DbConnectionDto.Chip> dbOptions,
        int dbSlotsLeft,
        int refSlotsLeft
) {
    public SuggestionView {
        targets = targets == null ? List.of() : List.copyOf(targets);
        dbOptions = dbOptions == null ? List.of() : List.copyOf(dbOptions);
    }
}
