package com.hamonsoft.netismaker.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

import static com.hamonsoft.netismaker.usage.UsagePeriod.SEOUL;

/**
 * 창에 해당하는 구독료 몫. 창에 걸친 서울 날짜마다
 * (그날 적용되는 월 구독료 ÷ 그 달 일수) × (그날 중 창과 겹친 비율)을 더한다.
 */
public final class SubscriptionFeeShare {

    /** effectiveFrom부터 적용되는 월 구독료(USD). */
    public record FeeEntry(LocalDate effectiveFrom, BigDecimal monthlyFee) {}

    /**
     * missingDays = 창과 겹치는데 적용 구독료가 없는 날이 있음(그날 몫은 0).
     * anyFee = 구독료 이력이 하나라도 있음(창과 무관).
     */
    public record FeeShare(BigDecimal share, boolean missingDays, boolean anyFee) {}

    private static final int SCALE = 10;

    private SubscriptionFeeShare() {}

    /** 적용 구독료는 effectiveFrom ≤ 그날인 행 가운데 effectiveFrom이 가장 늦은 행이다(입력 순서 무관). */
    public static FeeShare between(List<FeeEntry> fees, UsageWindow w) {
        List<FeeEntry> latestFirst = fees.stream()
                .sorted(Comparator.comparing(FeeEntry::effectiveFrom).reversed())
                .toList();
        BigDecimal share = BigDecimal.ZERO;
        boolean missingDays = false;
        if (!w.isEmpty()) {
            ZonedDateTime from = w.from().withZoneSameInstant(SEOUL);
            ZonedDateTime to = w.to().withZoneSameInstant(SEOUL);
            for (LocalDate day = from.toLocalDate(); day.atStartOfDay(SEOUL).isBefore(to); day = day.plusDays(1)) {
                FeeEntry fee = applicable(latestFirst, day);
                if (fee == null) {
                    missingDays = true;
                    continue;
                }
                ZonedDateTime dayStart = day.atStartOfDay(SEOUL);
                ZonedDateTime dayEnd = day.plusDays(1).atStartOfDay(SEOUL);
                BigDecimal overlap = nanos(dayStart.isBefore(from) ? from : dayStart, dayEnd.isAfter(to) ? to : dayEnd);
                BigDecimal daily = fee.monthlyFee()
                        .divide(BigDecimal.valueOf(day.lengthOfMonth()), SCALE, RoundingMode.HALF_UP);
                share = share.add(daily.multiply(overlap).divide(nanos(dayStart, dayEnd), SCALE, RoundingMode.HALF_UP));
            }
        }
        return new FeeShare(share, missingDays, !fees.isEmpty());
    }

    private static FeeEntry applicable(List<FeeEntry> latestFirst, LocalDate day) {
        for (FeeEntry fee : latestFirst) {
            if (!fee.effectiveFrom().isAfter(day)) {
                return fee;
            }
        }
        return null;
    }

    private static BigDecimal nanos(ZonedDateTime from, ZonedDateTime to) {
        return BigDecimal.valueOf(Duration.between(from, to).toNanos());
    }
}
