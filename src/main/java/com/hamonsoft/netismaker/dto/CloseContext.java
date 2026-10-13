package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 종료 노트 재료 (스펙 2026-10-13 §4 ③). 워커는 이것만으로 노트 프롬프트를 만든다 — DB를 직접 읽지 않는다.
 * 긴 본문은 여기서 자른다(프롬프트 크기 상한 겸 claim 응답 크기 상한).
 */
public record CloseContext(
        String planMarkdown,
        String analysisMarkdown,
        /** implementation_log의 끝부분(결과 요약이 보통 끝에 있다). */
        String implementationTail,
        String mergeCommitSha,
        String deployUrl,
        OffsetDateTime deployedAt,
        List<HistoryLine> history,
        List<UsageLine> usage
) {
    static final int PLAN_MAX = 12_000;
    static final int ANALYSIS_MAX = 8_000;
    static final int IMPL_TAIL_MAX = 8_000;

    /** 상태 이력 한 줄 — from/to는 DB 값(한글 상태명), 시간순(오래된 것 먼저). */
    public record HistoryLine(OffsetDateTime at, String from, String to, String actor, String reason) {}

    /** 단계별 사용량 한 줄. */
    public record UsageLine(String stage, BigDecimal costUsd, long inputTokens, long outputTokens) {}

    /** @param historyOldestFirst 시간순(오래된 것 먼저) */
    public static CloseContext of(Task t, String planMarkdown, TaskAnalysis a,
                                  List<TaskStatusHistory> historyOldestFirst, List<TaskStageUsage> usage) {
        return new CloseContext(
                head(planMarkdown, PLAN_MAX),
                a == null ? null : head(a.getMarkdownResult(), ANALYSIS_MAX),
                tail(t.getImplementationLog(), IMPL_TAIL_MAX),
                t.getMergeCommitSha(),
                t.getDeployUrl(),
                t.getDeployedAt(),
                historyOldestFirst.stream().map(h -> new HistoryLine(h.getAt(), h.getFromStatus(), h.getToStatus(),
                        h.getActorId() == null ? h.getActorType() : h.getActorId(), h.getReason())).toList(),
                usage.stream().map(u -> new UsageLine(u.getStage(), u.getCostUsd(),
                        u.getInputTokens(), u.getOutputTokens())).toList());
    }

    static String head(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--; // 서로게이트 쌍 한가운데서 자르지 않는다
        return s.substring(0, end) + "\n…(이하 생략)";
    }

    static String tail(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int from = s.length() - max;
        if (Character.isLowSurrogate(s.charAt(from))) from++;
        return "(앞부분 생략)…\n" + s.substring(from);
    }
}
