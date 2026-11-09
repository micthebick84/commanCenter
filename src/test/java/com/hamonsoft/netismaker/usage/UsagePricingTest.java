package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageTokens;
import com.hamonsoft.netismaker.usage.UsageProperties.LongContext;
import com.hamonsoft.netismaker.usage.UsageProperties.ModelPrice;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 가격표 바인딩·키 풀이·비용 계산. 단가는 모두 가짜 값(claude-test-*)이다. */
class UsagePricingTest {

    private static UsageProperties bind(Resource yml) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("usage.yml", yml);
        for (PropertySource<?> ps : loaded) {
            env.getPropertySources().addLast(ps);
        }
        return Binder.get(env).bind("app.usage", UsageProperties.class).orElseGet(() -> new UsageProperties(null, null));
    }

    private static UsageProperties bindText(String yml) throws Exception {
        return bind(new ByteArrayResource(yml.getBytes(StandardCharsets.UTF_8)));
    }

    private static ModelPrice price(String input, String output, String w5, String w1, String read) {
        return new ModelPrice(new BigDecimal(input), new BigDecimal(output), new BigDecimal(w5),
                new BigDecimal(w1), new BigDecimal(read), null, null);
    }

    @Test
    void 가격표_바인딩() throws Exception {
        UsageProperties p = bindText("""
                app.usage.prices.claude-test-5-5: {input: 3, output: 15, cache-write-5m: 3.75, cache-write-1h: 6, cache-read: 0.3, long-context: {threshold: 200000, input: 6}}
                """);
        assertThat(p.prices()).containsOnlyKeys("claude-test-5-5");
        ModelPrice m = p.prices().get("claude-test-5-5");
        assertThat(m.cacheWrite1h()).isEqualByComparingTo("6");
        assertThat(m.longContext().threshold()).isEqualTo(200000L);
        assertThat(m.longContext().output()).isNull();
    }

    @Test
    void 날짜_접미사를_떼고_찾는다() {
        UsageProperties p = new UsageProperties(null, Map.of(
                "claude-test-4-5", price("1", "5", "1.25", "2", "0.1"),
                "claude-test-3-0", price("1", "5", "1.25", "2", "0.1"),
                "claude-test-3-0-20250101", price("2", "6", "2", "3", "0.2")));
        UsagePriceBook book = new UsagePriceBook(p);
        assertThat(book.resolveKey("claude-test-4-5-20251001")).contains("claude-test-4-5");
        assertThat(book.resolveKey("claude-test-4-5")).contains("claude-test-4-5");
        assertThat(book.resolveKey("claude-test-3-0-20250101")).contains("claude-test-3-0-20250101");
        assertThat(book.resolveKey("<synthetic>")).isEqualTo(Optional.empty());
        assertThat(book.resolveKey(null)).isEmpty();
    }

    @Test
    void 다섯_칸을_각각의_단가로() {
        ModelPrice m = price("3", "15", "3.75", "6", "0.3");
        UsageTokens t = new UsageTokens(1_000_000, 2_000_000, 1_000_000, 1_000_000, 10_000_000);
        assertThat(UsageCostCalculator.cost(m, t, false, false)).isEqualByComparingTo("45.75");
    }

    @Test
    void fast_배수() {
        UsageTokens t = new UsageTokens(1_000_000, 0, 0, 0, 0);
        ModelPrice withMultiplier = new ModelPrice(new BigDecimal("3"), new BigDecimal("15"), new BigDecimal("3.75"),
                new BigDecimal("6"), new BigDecimal("0.3"), new BigDecimal("6"), null);
        assertThat(UsageCostCalculator.cost(withMultiplier, t, true, false)).isEqualByComparingTo("18");
        assertThat(UsageCostCalculator.cost(withMultiplier, t, false, false)).isEqualByComparingTo("3");
        assertThat(UsageCostCalculator.cost(price("3", "15", "3.75", "6", "0.3"), t, true, false)).isNull();
    }

    @Test
    void 구간_단가() {
        ModelPrice m = new ModelPrice(new BigDecimal("3"), new BigDecimal("15"), new BigDecimal("3.75"),
                new BigDecimal("6"), new BigDecimal("0.3"), null,
                new LongContext(200_000L, new BigDecimal("6"), null, null, null, null));
        UsageTokens t = new UsageTokens(1_000_000, 1_000_000, 0, 0, 0);
        assertThat(UsageCostCalculator.cost(m, t, false, false)).isEqualByComparingTo("18");
        // input은 구간 단가 6, output은 구간 단가가 null이라 기본 15
        assertThat(UsageCostCalculator.cost(m, t, false, true)).isEqualByComparingTo("21");
        assertThat(new UsagePriceBook(new UsageProperties(null, Map.of("k", m))).thresholds()).containsExactly(200_000L);
    }

    @Test
    void 반올림하지_않는다() {
        ModelPrice m = price("3", "15", "3.75", "6", "0.3");
        assertThat(UsageCostCalculator.cost(m, new UsageTokens(1, 0, 0, 0, 0), false, false))
                .isEqualByComparingTo("0.000003");
    }

    @Test
    void 단가가_빠지면_기동_실패() {
        assertThatThrownBy(() -> bindText("""
                app.usage.prices.claude-test-5-5: {input: 3, cache-write-5m: 3.75, cache-write-1h: 6, cache-read: 0.3}
                """))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("app.usage.prices.claude-test-5-5");
    }

    @Test
    void count_from_형식() throws Exception {
        assertThatThrownBy(() -> new UsageProperties("2026/10/01", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.usage.count-from");
        UsageProperties empty = new UsageProperties(null, null);
        assertThat(empty.countFromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(empty.prices()).isEmpty();
    }

    @Test
    void 본_yml_가격표가_바인딩된다() throws Exception {
        UsageProperties p = bind(new ClassPathResource("application.yml"));
        assertThat(p.countFromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.prices()).isNotEmpty();
    }

    @Test
    void 본_yml_가격표는_정책의_다섯_모델을_모두_가진다() throws Exception {
        UsageProperties p = bind(new ClassPathResource("application.yml"));
        assertThat(p.prices()).containsKeys("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5",
                "claude-sonnet-5", "claude-haiku-4-5");
    }

    @Test
    void 본_yml_sonnet_5_5_캐시_읽기_단가는_sonnet_5와_같은_0_20() throws Exception {
        UsageProperties p = bind(new ClassPathResource("application.yml"));
        assertThat(p.prices().get("claude-sonnet-5-5").cacheRead()).isEqualByComparingTo("0.20");
        assertThat(p.prices().get("claude-sonnet-5").cacheRead()).isEqualByComparingTo("0.20");
    }
}
