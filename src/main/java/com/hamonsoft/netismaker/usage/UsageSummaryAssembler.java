package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageSummaryResponse.CategoryUsage;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.ModelUsage;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.SeriesPoint;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.UnpricedModel;
import com.hamonsoft.netismaker.dto.UsageTokens;
import com.hamonsoft.netismaker.usage.UsageProperties.LongContext;
import com.hamonsoft.netismaker.usage.UsageProperties.ModelPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 집계 행 → 응답 조각과 CSV(순수 함수). 행 하나의 비용은 rowCost 한 곳에서만 계산해 화면 합계와 CSV가 갈라지지 않게 한다.
 * 비용은 가격이 있는 행만 더하고, 미등록 행(가격표에 없는 모델, fast 배수가 없는 fast 행)은 토큰에만 넣고 경고로 알린다.
 */
public final class UsageSummaryAssembler {

    /** 범주 표시·CSV 정렬 순서. */
    static final List<String> CATEGORIES = List.of("SYMPHONY", "DIRECT");

    private static final String SYNTHETIC = "<synthetic>";
    /** UTF-8 BOM — 엑셀이 한글 CSV를 UTF-8로 읽게 한다. */
    private static final char BOM = 0xFEFF;
    private static final String CSV_HEADER = BOM + "날짜,구분,모델,입력,출력,캐시쓰기5분,캐시쓰기1시간,캐시읽기,비용USD\n";
    private static final DateTimeFormatter CSV_HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
    private static final DateTimeFormatter CSV_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 가격 있는 모델이 먼저(비용 큰 순), 미등록은 뒤. 같으면 토큰 많은 순, 이름 순. */
    private static final Comparator<ModelUsage> MODEL_ORDER = Comparator
            .comparing(ModelUsage::cost, Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
            .thenComparing((ModelUsage m) -> m.tokens().total(), Comparator.reverseOrder())
            .thenComparing(ModelUsage::model);

    /**
     * cost·costByBucket은 반올림 전 값이다(ratio는 반올림 전 값으로 나눈다 — 응답에는 money로 4자리).
     * byCategory·byModel은 응답에 그대로 싣는 값(4자리)이다.
     */
    public record Assembled(UsageTokens tokens, BigDecimal cost, List<CategoryUsage> byCategory,
                            List<ModelUsage> byModel, Map<LocalDateTime, BigDecimal> costByBucket,
                            List<UnpricedModel> unpriced) {}

    private UsageSummaryAssembler() {}

    public static Assembled assemble(List<UsageAggRow> rows, UsagePriceBook book, List<Long> thresholds) {
        UsageTokens tokens = UsageTokens.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        Map<String, Sum> categories = new LinkedHashMap<>();
        CATEGORIES.forEach(c -> categories.put(c, new Sum()));
        Map<ModelKey, Sum> models = new HashMap<>();
        Map<LocalDateTime, BigDecimal> byBucket = new HashMap<>();
        for (UsageAggRow r : rows) {
            BigDecimal c = rowCost(r, book, thresholds);
            tokens = tokens.plus(r.tokens());
            categories.computeIfAbsent(r.category(), k -> new Sum()).add(r.tokens(), c);
            models.computeIfAbsent(ModelKey.of(r), k -> new Sum()).add(r.tokens(), c);
            if (c != null) {
                cost = cost.add(c);
                byBucket.merge(r.bucket(), c, BigDecimal::add);
            }
        }
        List<CategoryUsage> byCategory = categories.entrySet().stream()
                .map(e -> new CategoryUsage(e.getKey(), e.getValue().tokens, money(e.getValue().cost)))
                .toList();
        // 토큰 합이 0인 모델은 표·경고에 싣지 않는다
        List<Map.Entry<ModelKey, Sum>> used = models.entrySet().stream()
                .filter(e -> e.getValue().tokens.total() > 0)
                .toList();
        List<ModelUsage> byModel = used.stream()
                .map(e -> new ModelUsage(e.getKey().label(), e.getValue().tokens, e.getValue().pricedCost()))
                .sorted(MODEL_ORDER)
                .toList();
        List<UnpricedModel> unpriced = used.stream()
                .filter(e -> !e.getValue().priced && !SYNTHETIC.equals(e.getKey().model()))
                .map(e -> new UnpricedModel(e.getKey().label(), e.getValue().tokens))
                .sorted(Comparator.comparingLong((UnpricedModel u) -> u.tokens().total()).reversed()
                        .thenComparing(UnpricedModel::model))
                .toList();
        return new Assembled(tokens, cost, byCategory, byModel, Map.copyOf(byBucket), unpriced);
    }

    /** 기간의 모든 칸(일 = 24시간, 주·월 = 날짜)을 서울 시각으로 낸다. 기록이 없는 칸은 0. */
    public static List<SeriesPoint> series(UsagePeriod p, Map<LocalDateTime, BigDecimal> costByBucket) {
        return p.buckets().stream()
                .map(at -> new SeriesPoint(at.toOffsetDateTime(),
                        money(costByBucket.getOrDefault(at.toLocalDateTime(), BigDecimal.ZERO))))
                .toList();
    }

    /**
     * 근거 자료 CSV(UTF-8 BOM, 줄 끝 \n). 행은 시각·구분·모델 순이고 토큰이 0인 조합은 뺀다.
     * 일 기간은 시간별(yyyy-MM-dd HH:00), 주·월은 일별(yyyy-MM-dd). 미등록 모델의 비용 칸은 비운다.
     */
    public static String csv(List<UsageAggRow> rows, UsagePriceBook book, List<Long> thresholds, UsagePeriodUnit unit) {
        record Line(LocalDateTime bucket, String category, ModelKey model) {}
        Map<Line, Sum> lines = new HashMap<>();
        for (UsageAggRow r : rows) {
            lines.computeIfAbsent(new Line(r.bucket(), r.category(), ModelKey.of(r)), k -> new Sum())
                    .add(r.tokens(), rowCost(r, book, thresholds));
        }
        DateTimeFormatter when = unit == UsagePeriodUnit.DAY ? CSV_HOUR : CSV_DAY;
        Comparator<Line> order = Comparator.comparing(Line::bucket)
                .thenComparingInt(l -> categoryOrder(l.category()))
                .thenComparing(Line::category)
                .thenComparing(l -> l.model().label());
        StringBuilder out = new StringBuilder(CSV_HEADER);
        lines.entrySet().stream()
                .filter(e -> e.getValue().tokens.total() > 0)
                .sorted(Map.Entry.comparingByKey(order))
                .forEach(e -> {
                    Line l = e.getKey();
                    UsageTokens t = e.getValue().tokens;
                    BigDecimal c = e.getValue().pricedCost();
                    out.append(when.format(l.bucket())).append(',')
                            .append(cell(l.category())).append(',')
                            .append(cell(l.model().label())).append(',')
                            .append(t.input()).append(',')
                            .append(t.output()).append(',')
                            .append(t.cacheWrite5m()).append(',')
                            .append(t.cacheWrite1h()).append(',')
                            .append(t.cacheRead()).append(',')
                            .append(c == null ? "" : c.toPlainString()).append('\n');
                });
        return out.toString();
    }

    /** 비용·구독료 몫의 응답 자릿수(USD 소수 4자리). */
    static BigDecimal money(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    /** fast = speed가 "fast"(대소문자 무시). 그 밖의 값과 null은 standard. */
    static boolean isFast(String speed) {
        return "fast".equalsIgnoreCase(speed);
    }

    /**
     * 집계 행 하나의 비용 — assemble과 csv가 같이 쓴다. 키 풀이 → fast 판정 → 그 모델의 구간 기준값 칸 → 계산.
     * 가격 미등록(가격표에 없는 모델, fast인데 배수 없음)이면 null.
     */
    private static BigDecimal rowCost(UsageAggRow r, UsagePriceBook book, List<Long> thresholds) {
        Optional<String> key = book.resolveKey(r.model());
        if (key.isEmpty()) {
            return null;
        }
        ModelPrice price = book.price(key.get());
        return UsageCostCalculator.cost(price, r.tokens(), isFast(r.speed()), overThreshold(r, price, thresholds));
    }

    /** 그 모델의 long-context 기준값이 thresholds의 몇 번째인지 찾아 over 칸으로 판정한다. */
    private static boolean overThreshold(UsageAggRow r, ModelPrice price, List<Long> thresholds) {
        LongContext lc = price.longContext();
        if (lc == null || lc.threshold() == null) {
            return false;
        }
        int i = thresholds.indexOf(lc.threshold());
        if (i < 0 || i >= r.over().size()) {
            throw new IllegalArgumentException("구간 기준값 " + lc.threshold() + "의 초과 칸이 집계 행에 없습니다");
        }
        return Boolean.TRUE.equals(r.over().get(i));
    }

    private static int categoryOrder(String category) {
        int i = CATEGORIES.indexOf(category);
        return i < 0 ? CATEGORIES.size() : i;
    }

    /**
     * CSV 칸 하나. 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고(RFC 4180),
     * =·+·-·@·탭·CR로 시작하면 앞에 '를 붙여 스프레드시트가 수식으로 실행하지 않게 한다(모델 이름은 워커 PC의 기록 파일에서 온다).
     */
    private static String cell(String v) {
        String s = !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** 모델 표·CSV의 한 줄 이름. fast면 "{모델} (fast)". */
    private record ModelKey(String model, boolean fast) {
        static ModelKey of(UsageAggRow r) {
            return new ModelKey(r.model(), isFast(r.speed()));
        }

        String label() {
            return fast ? model + " (fast)" : model;
        }
    }

    /** 토큰·비용 누계. cost는 가격이 있는 행만 더하고, priced는 미등록 행이 하나도 없었는지다. */
    private static final class Sum {
        UsageTokens tokens = UsageTokens.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        boolean priced = true;

        void add(UsageTokens t, BigDecimal c) {
            tokens = tokens.plus(t);
            if (c == null) {
                priced = false;
            } else {
                cost = cost.add(c);
            }
        }

        /** 미등록이 섞였으면 null(같은 모델·fast 조합은 모두 같은 판정이라 섞이지 않는다). */
        BigDecimal pricedCost() {
            return priced ? money(cost) : null;
        }
    }
}
