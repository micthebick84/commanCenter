package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageTokens;
import com.hamonsoft.netismaker.usage.UsageProperties.LongContext;
import com.hamonsoft.netismaker.usage.UsageProperties.ModelPrice;

import java.math.BigDecimal;

/** 토큰 × 단가 ÷ 1,000,000을 칸마다 더한다. 반올림하지 않는다. */
public final class UsageCostCalculator {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private UsageCostCalculator() {}

    /** fast인데 fastMultiplier가 없으면 null(가격 미등록). */
    public static BigDecimal cost(ModelPrice p, UsageTokens t, boolean fast, boolean longContext) {
        LongContext lc = longContext ? p.longContext() : null;
        BigDecimal sum = slot(t.input(), pick(lc == null ? null : lc.input(), p.input()))
                .add(slot(t.output(), pick(lc == null ? null : lc.output(), p.output())))
                .add(slot(t.cacheWrite5m(), pick(lc == null ? null : lc.cacheWrite5m(), p.cacheWrite5m())))
                .add(slot(t.cacheWrite1h(), pick(lc == null ? null : lc.cacheWrite1h(), p.cacheWrite1h())))
                .add(slot(t.cacheRead(), pick(lc == null ? null : lc.cacheRead(), p.cacheRead())));
        if (!fast) {
            return sum;
        }
        return p.fastMultiplier() == null ? null : sum.multiply(p.fastMultiplier());
    }

    private static BigDecimal pick(BigDecimal override, BigDecimal base) {
        return override != null ? override : base;
    }

    private static BigDecimal slot(long tokens, BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(tokens)).divide(MILLION);
    }
}
