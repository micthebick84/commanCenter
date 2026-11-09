package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.usage.SubscriptionFeeShare.FeeEntry;
import com.hamonsoft.netismaker.usage.SubscriptionFeeShare.FeeShare;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 날짜별 구독료 몫. 기대값은 계획 표기(월 구독료 ÷ 그 달 일수 × 겹친 비율)대로 scale 10 HALF_UP으로 만든다. */
class SubscriptionFeeShareTest {

    private static FeeEntry fee(String effectiveFrom, String monthlyFee) {
        return new FeeEntry(LocalDate.parse(effectiveFrom), new BigDecimal(monthlyFee));
    }

    private static ZonedDateTime kst(int y, int m, int d, int h) {
        return ZonedDateTime.of(y, m, d, h, 0, 0, 0, UsagePeriod.SEOUL);
    }

    private static UsageWindow days(String from, String toExclusive) {
        return new UsageWindow(LocalDate.parse(from).atStartOfDay(UsagePeriod.SEOUL),
                LocalDate.parse(toExclusive).atStartOfDay(UsagePeriod.SEOUL));
    }

    /** 월 구독료 ÷ 그 달 일수(scale 10, HALF_UP). */
    private static BigDecimal daily(String monthlyFee, int daysInMonth) {
        return new BigDecimal(monthlyFee).divide(BigDecimal.valueOf(daysInMonth), 10, RoundingMode.HALF_UP);
    }

    @Test
    void 날짜별로_그달_일수로_나눈다() {
        FeeShare s = SubscriptionFeeShare.between(List.of(fee("2026-10-01", "200")), days("2026-10-30", "2026-11-02"));

        assertThat(s.share()).isEqualByComparingTo(
                daily("200", 31).multiply(BigDecimal.valueOf(2)).add(daily("200", 30)));
        assertThat(s.missingDays()).isFalse();
        assertThat(s.anyFee()).isTrue();
    }

    @Test
    void 달_중간에_바뀐_구독료() {
        BigDecimal expected = daily("100", 31).multiply(BigDecimal.valueOf(15))
                .add(daily("200", 31).multiply(BigDecimal.valueOf(16)));
        UsageWindow october = days("2026-10-01", "2026-11-01");

        FeeShare ascending = SubscriptionFeeShare.between(
                List.of(fee("2026-10-01", "100"), fee("2026-10-16", "200")), october);
        assertThat(ascending.share()).isEqualByComparingTo(expected);
        assertThat(ascending.missingDays()).isFalse();

        // Ruling F1: 구독료 서비스는 최신순으로 넘긴다 — 순서와 무관하게 같은 값
        FeeShare latestFirst = SubscriptionFeeShare.between(
                List.of(fee("2026-10-16", "200"), fee("2026-10-01", "100")), october);
        assertThat(latestFirst.share()).isEqualByComparingTo(expected);
        assertThat(latestFirst.missingDays()).isFalse();
    }

    @Test
    void 구독료_없는_날() {
        UsageWindow october = days("2026-10-01", "2026-11-01");

        FeeShare partial = SubscriptionFeeShare.between(List.of(fee("2026-10-16", "200")), october);
        assertThat(partial.missingDays()).isTrue();
        assertThat(partial.anyFee()).isTrue();
        assertThat(partial.share()).isEqualByComparingTo(daily("200", 31).multiply(BigDecimal.valueOf(16)));

        FeeShare none = SubscriptionFeeShare.between(List.of(), october);
        assertThat(none.anyFee()).isFalse();
        assertThat(none.share()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(none.missingDays()).isTrue();

        // Ruling F2: anyFee는 창이 아니라 목록 기준 — 첫 구독료 이전 달에도 true
        FeeShare beforeFirst = SubscriptionFeeShare.between(List.of(fee("2026-11-01", "300")), october);
        assertThat(beforeFirst.anyFee()).isTrue();
        assertThat(beforeFirst.missingDays()).isTrue();
        assertThat(beforeFirst.share()).isEqualByComparingTo(BigDecimal.ZERO);

        // 빈 창(시작일 이전 기간)은 몫 0, 빠진 날 없음
        FeeShare empty = SubscriptionFeeShare.between(List.of(fee("2026-10-01", "300")),
                new UsageWindow(kst(2026, 10, 1, 0), kst(2026, 10, 1, 0)));
        assertThat(empty.share()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(empty.missingDays()).isFalse();
        assertThat(empty.anyFee()).isTrue();
    }

    @Test
    void 오늘은_지난_시간_비율() {
        List<FeeEntry> fees = List.of(fee("2026-11-01", "300"));

        FeeShare today = SubscriptionFeeShare.between(fees, new UsageWindow(kst(2026, 11, 3, 0), kst(2026, 11, 3, 6)));
        assertThat(today.share()).isEqualByComparingTo("2.5");

        // UTC로 준 같은 창(KST 11-03 00:00~06:00)도 서울 날짜로 나눈다
        UsageWindow utc = new UsageWindow(Instant.parse("2026-11-02T15:00:00Z").atZone(ZoneOffset.UTC),
                Instant.parse("2026-11-02T21:00:00Z").atZone(ZoneOffset.UTC));
        assertThat(SubscriptionFeeShare.between(fees, utc).share()).isEqualByComparingTo("2.5");

        // 자정을 걸친 창은 날마다 그달 일수로 나눈 몫 × 겹친 비율
        FeeShare acrossMidnight = SubscriptionFeeShare.between(List.of(fee("2026-10-01", "300")),
                new UsageWindow(kst(2026, 10, 31, 18), kst(2026, 11, 1, 6)));
        BigDecimal quarter = new BigDecimal("0.25");
        assertThat(acrossMidnight.share()).isEqualByComparingTo(
                daily("300", 31).multiply(quarter).add(daily("300", 30).multiply(quarter)));
        assertThat(acrossMidnight.missingDays()).isFalse();
    }
}
