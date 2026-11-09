package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/usage/summary 응답(관리자). 필드 이름은 프론트 usageReport.ts와 맞춘다.
 * 시각은 모두 서울(+09:00). 비용·구독료 몫은 USD 소수 4자리, ratio는 2자리.
 * cost·byCategory[].cost·series·previous.cost는 가격이 있는 행만 더한다 — 미등록 모델은 토큰에만 들어가고
 * coverage.unpricedModels로 알린다.
 *
 * @param date               요청 날짜(없으면 서울 오늘)
 * @param start              기간 시작(세는 시작일로 자르지 않은 값)
 * @param feeConfigured      구독료 이력이 하나라도 있음(기간과 무관)
 * @param feeMissing         비용 창 안에 적용 구독료가 없는 날이 있음
 * @param feeShare           비용 창(진행 중이면 지금까지)의 구독료 몫
 * @param fullPeriodFeeShare 기간 전체의 구독료 몫
 * @param ratio              cost ÷ feeShare. 구독료가 없거나 빠진 날이 있거나 몫이 0이면 null
 */
public record UsageSummaryResponse(
        String period,
        LocalDate date,
        OffsetDateTime start,
        OffsetDateTime end,
        LocalDate countFrom,
        boolean inProgress,
        UsageTokens tokens,
        BigDecimal cost,
        List<CategoryUsage> byCategory,
        List<ModelUsage> byModel,
        boolean feeConfigured,
        boolean feeMissing,
        BigDecimal feeShare,
        BigDecimal fullPeriodFeeShare,
        BigDecimal ratio,
        PreviousUsage previous,
        List<SeriesPoint> series,
        Coverage coverage
) {

    /** 늘 SYMPHONY·DIRECT 두 행. */
    public record CategoryUsage(String category, UsageTokens tokens, BigDecimal cost) {}

    /** model은 fast면 "{모델} (fast)". 가격 미등록이면 cost가 null. */
    public record ModelUsage(String model, UsageTokens tokens, BigDecimal cost) {}

    /** 직전 기간 [start, until) — 진행 중이면 같은 경과 길이까지. */
    public record PreviousUsage(OffsetDateTime start, OffsetDateTime until, BigDecimal cost, BigDecimal feeShare,
                                BigDecimal ratio) {}

    /** 그래프 한 칸(일 기간은 시간, 주·월은 날짜)의 시작 시각과 비용. */
    public record SeriesPoint(OffsetDateTime at, BigDecimal cost) {}

    /** earliestEventAt은 세는 시작일 이후의 가장 이른 기록. */
    public record Coverage(List<CollectorStatus> collectors, OffsetDateTime earliestEventAt,
                           List<UnpricedModel> unpricedModels) {}

    /** 수집 PC별 마지막 보고. unreadableLines는 마지막 회차의 못 읽은 줄 수. */
    public record CollectorStatus(String machine, OffsetDateTime lastReportedAt, OffsetDateTime lastEventAt,
                                  int unreadableLines) {}

    public record UnpricedModel(String model, UsageTokens tokens) {}
}
