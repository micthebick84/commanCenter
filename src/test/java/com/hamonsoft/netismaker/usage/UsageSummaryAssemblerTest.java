package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageSummaryResponse.CategoryUsage;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.ModelUsage;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.SeriesPoint;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.UnpricedModel;
import com.hamonsoft.netismaker.dto.UsageTokens;
import com.hamonsoft.netismaker.usage.UsageProperties.LongContext;
import com.hamonsoft.netismaker.usage.UsageProperties.ModelPrice;
import com.hamonsoft.netismaker.usage.UsageSummaryAssembler.Assembled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 집계 행 → 합계·범주·모델·경고·그래프·CSV. 단가는 모두 가짜 값(claude-test-*)이다. */
class UsageSummaryAssemblerTest {

    private static final UsagePriceBook BOOK = new UsagePriceBook(new UsageProperties(null, Map.of(
            "claude-test-5-5", price("3", "15", null, null),
            "claude-test-fast", price("3", "15", "2", null),
            "claude-test-lc", price("3", "15", null,
                    new LongContext(200_000L, new BigDecimal("6"), new BigDecimal("22.5"), null, null, null)),
            "claude-test-lc-small", new ModelPrice(new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("1.25"),
                    new BigDecimal("2"), new BigDecimal("0.1"), null,
                    new LongContext(100_000L, new BigDecimal("2"), null, null, null, null)))));

    /** 가격표의 서로 다른 기준값 = [100000, 200000]. */
    private static final List<Long> THRESHOLDS = BOOK.thresholds();

    private static final LocalDateTime B23 = LocalDateTime.of(2026, 10, 31, 23, 0);
    private static final LocalDateTime B05 = LocalDateTime.of(2026, 10, 31, 5, 0);
    private static final long M = 1_000_000;
    private static final String BOM = String.valueOf((char) 0xFEFF);

    private static ModelPrice price(String input, String output, String fast, LongContext lc) {
        return new ModelPrice(new BigDecimal(input), new BigDecimal(output), new BigDecimal("3.75"),
                new BigDecimal("6"), new BigDecimal("0.3"), fast == null ? null : new BigDecimal(fast), lc);
    }

    private static UsageAggRow row(LocalDateTime bucket, String category, String model, String speed, UsageTokens t) {
        return new UsageAggRow(bucket, category, model, speed, List.of(false, false), t);
    }

    private static UsageAggRow row(String model, List<Boolean> over, UsageTokens t) {
        return new UsageAggRow(B23, "SYMPHONY", model, null, over, t);
    }

    private static UsageTokens input(long n) {
        return new UsageTokens(n, 0, 0, 0, 0);
    }

    private static UsageTokens output(long n) {
        return new UsageTokens(0, n, 0, 0, 0);
    }

    private static ModelUsage model(Assembled a, String name) {
        return a.byModel().stream().filter(m -> m.model().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void 범주별_모델별_합과_비용() {
        Assembled a = UsageSummaryAssembler.assemble(List.of(
                row(B23, "SYMPHONY", "claude-test-5-5", "standard", input(M)),
                row(B23, "DIRECT", "claude-test-5-5", null, output(M)),
                row(B23, "DIRECT", "claude-x-1", null, input(M))), BOOK, THRESHOLDS);

        assertThat(a.cost()).isEqualByComparingTo("18");
        assertThat(a.tokens()).isEqualTo(new UsageTokens(2 * M, M, 0, 0, 0));
        assertThat(a.byCategory()).extracting(CategoryUsage::category).containsExactly("SYMPHONY", "DIRECT");
        assertThat(a.byCategory().get(0).cost()).isEqualByComparingTo("3");
        assertThat(a.byCategory().get(1).cost()).isEqualByComparingTo("15");
        assertThat(a.byCategory().get(1).tokens()).isEqualTo(new UsageTokens(M, M, 0, 0, 0));
        // speed "standard"와 null은 같은 행, 가격 있는 모델이 먼저
        assertThat(a.byModel()).extracting(ModelUsage::model).containsExactly("claude-test-5-5", "claude-x-1");
        assertThat(model(a, "claude-test-5-5").cost()).isEqualByComparingTo("18");
        assertThat(model(a, "claude-test-5-5").tokens()).isEqualTo(new UsageTokens(M, M, 0, 0, 0));
        assertThat(model(a, "claude-x-1").cost()).isNull();
        assertThat(a.costByBucket()).containsOnlyKeys(B23);
        assertThat(a.costByBucket().get(B23)).isEqualByComparingTo("18");
    }

    @Test
    void 범주는_행이_없어도_두_칸() {
        Assembled a = UsageSummaryAssembler.assemble(List.of(), BOOK, THRESHOLDS);

        assertThat(a.byCategory()).extracting(CategoryUsage::category).containsExactly("SYMPHONY", "DIRECT");
        assertThat(a.byCategory()).allSatisfy(c -> {
            assertThat(c.cost()).isEqualByComparingTo("0");
            assertThat(c.tokens()).isEqualTo(UsageTokens.ZERO);
        });
        assertThat(a.cost()).isEqualByComparingTo("0");
        assertThat(a.byModel()).isEmpty();
        assertThat(a.unpriced()).isEmpty();
    }

    @Test
    void 가격_미등록은_비용을_비우고_경고() {
        Assembled a = UsageSummaryAssembler.assemble(List.of(
                row(B23, "DIRECT", "claude-x-1", null, input(1_000)),
                row(B05, "DIRECT", "claude-x-1", null, output(500)),
                row(B23, "SYMPHONY", "<synthetic>", null, input(10)),
                row(B23, "SYMPHONY", "claude-y-0", null, UsageTokens.ZERO)), BOOK, THRESHOLDS);

        assertThat(a.cost()).isEqualByComparingTo("0");
        assertThat(a.costByBucket()).isEmpty();
        assertThat(a.tokens().input()).isEqualTo(1_010);
        assertThat(model(a, "claude-x-1").cost()).isNull();
        assertThat(a.unpriced()).containsExactly(new UnpricedModel("claude-x-1", new UsageTokens(1_000, 500, 0, 0, 0)));
        // 토큰 합 0인 모델은 표에도 싣지 않는다
        assertThat(a.byModel()).extracting(ModelUsage::model).containsExactlyInAnyOrder("claude-x-1", "<synthetic>");
    }

    @Test
    void fast_배수가_없으면_fast_라벨로_미등록() {
        Assembled a = UsageSummaryAssembler.assemble(List.of(
                row(B23, "SYMPHONY", "claude-test-5-5", "fast", input(M)),
                row(B23, "SYMPHONY", "claude-test-fast", "FAST", input(M)),
                row(B23, "SYMPHONY", "claude-test-5-5", "standard", input(M))), BOOK, THRESHOLDS);

        assertThat(model(a, "claude-test-5-5 (fast)").cost()).isNull();
        assertThat(model(a, "claude-test-fast (fast)").cost()).isEqualByComparingTo("6");
        assertThat(model(a, "claude-test-5-5").cost()).isEqualByComparingTo("3");
        assertThat(a.cost()).isEqualByComparingTo("9");
        assertThat(a.unpriced()).extracting(UnpricedModel::model).containsExactly("claude-test-5-5 (fast)");
    }

    @Test
    void 구간_단가는_그_모델의_기준_칸() {
        assertThat(THRESHOLDS).containsExactly(100_000L, 200_000L);

        Assembled a = UsageSummaryAssembler.assemble(List.of(
                // 기준 200000 → over.get(1). 100000만 넘은 행은 기본 단가 3, 둘 다 넘은 행은 6
                row("claude-test-lc", List.of(true, false), input(150_000)),
                row("claude-test-lc", List.of(true, true), input(250_000)),
                // 기준 100000 → over.get(0). 구간 단가 2
                row("claude-test-lc-small", List.of(true, false), input(150_000))), BOOK, THRESHOLDS);

        assertThat(model(a, "claude-test-lc").cost()).isEqualByComparingTo("1.95");
        assertThat(model(a, "claude-test-lc-small").cost()).isEqualByComparingTo("0.3");
        assertThat(a.cost()).isEqualByComparingTo("2.25");
    }

    @Test
    void series는_빈_버킷도_0() {
        List<SeriesPoint> day = UsageSummaryAssembler.series(
                UsagePeriod.containing(UsagePeriodUnit.DAY, LocalDate.of(2026, 10, 31)), Map.of(B23, new BigDecimal("1.5")));

        assertThat(day).hasSize(24);
        assertThat(day.get(0).at()).isEqualTo(OffsetDateTime.parse("2026-10-31T00:00+09:00"));
        assertThat(day.get(0).cost()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(day.get(23).at()).isEqualTo(OffsetDateTime.parse("2026-10-31T23:00+09:00"));
        assertThat(day.get(23).cost()).isEqualTo(new BigDecimal("1.5000"));
        assertThat(day).allSatisfy(p -> assertThat(p.at().getOffset()).isEqualTo(ZoneOffset.ofHours(9)));

        List<SeriesPoint> feb = UsageSummaryAssembler.series(
                UsagePeriod.containing(UsagePeriodUnit.MONTH, LocalDate.of(2026, 2, 10)), Map.of());
        assertThat(feb).hasSize(28);
        assertThat(feb.get(27).at()).isEqualTo(OffsetDateTime.parse("2026-02-28T00:00+09:00"));
    }

    @Test
    void CSV() {
        String csv = UsageSummaryAssembler.csv(List.of(
                row(B23, "DIRECT", "claude-x-1", null, input(1_000)),
                row(B23, "SYMPHONY", "claude-test-5-5", "standard", input(M)),
                row(B23, "SYMPHONY", "claude-test-5-5", null, output(M)),
                row(B05, "DIRECT", "claude-test-5-5", null, new UsageTokens(0, 0, 0, 0, M)),
                row(B05, "SYMPHONY", "<synthetic>", null, UsageTokens.ZERO)), BOOK, THRESHOLDS, UsagePeriodUnit.DAY);

        assertThat(csv).startsWith(BOM + "날짜,구분,모델,입력,출력,캐시쓰기5분,캐시쓰기1시간,캐시읽기,비용USD\n");
        assertThat(csv).isEqualTo(BOM + "날짜,구분,모델,입력,출력,캐시쓰기5분,캐시쓰기1시간,캐시읽기,비용USD\n"
                + "2026-10-31 05:00,DIRECT,claude-test-5-5,0,0,0,0,1000000,0.3000\n"
                + "2026-10-31 23:00,SYMPHONY,claude-test-5-5,1000000,1000000,0,0,0,18.0000\n"
                + "2026-10-31 23:00,DIRECT,claude-x-1,1000,0,0,0,0,\n");
    }

    @Test
    void CSV_주월은_일별이고_모델_칸은_감싼다() {
        LocalDateTime oct31 = LocalDateTime.of(2026, 10, 31, 0, 0);
        String csv = UsageSummaryAssembler.csv(List.of(
                row(oct31, "DIRECT", "claude-x,\"y\"", null, input(1)),
                row(oct31, "DIRECT", "=cmd", null, input(1))), BOOK, THRESHOLDS, UsagePeriodUnit.MONTH);

        assertThat(csv.lines().skip(1).toList()).containsExactly(
                "2026-10-31,DIRECT,'=cmd,1,0,0,0,0,",
                "2026-10-31,DIRECT,\"claude-x,\"\"y\"\"\",1,0,0,0,0,");
    }
}
