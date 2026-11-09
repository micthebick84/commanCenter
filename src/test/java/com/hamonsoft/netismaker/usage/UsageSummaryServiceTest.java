package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageSummaryResponse;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.CollectorStatus;
import com.hamonsoft.netismaker.dto.UsageTokens;
import com.hamonsoft.netismaker.service.TaskException;
import com.hamonsoft.netismaker.usage.SubscriptionFeeShare.FeeEntry;
import com.hamonsoft.netismaker.usage.UsageProperties.ModelPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기간·창·구독료 몫·배수·직전 비교. 시계는 UTC zone으로 고정한다 — 서울 변환이 빠지면 KST JVM에서도 깨지게.
 * 단가는 가짜 값(claude-test-5-5 입력 $10 / 100만 토큰)이다.
 */
class UsageSummaryServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long M = 1_000_000;

    private ClaudeUsageReportRepository repo;
    private UsageSubscriptionFeeService fees;
    private UsagePriceBook book;

    @BeforeEach
    void setUp() {
        repo = mock(ClaudeUsageReportRepository.class);
        fees = mock(UsageSubscriptionFeeService.class);
        book = new UsagePriceBook(new UsageProperties("2026-10-01", Map.of("claude-test-5-5",
                new ModelPrice(new BigDecimal("10"), new BigDecimal("50"), new BigDecimal("12.5"),
                        new BigDecimal("20"), new BigDecimal("1"), null, null))));
        when(repo.earliestEventAt(any())).thenReturn(Optional.empty());
        when(repo.collectors()).thenReturn(List.of());
    }

    private UsageSummaryService at(String instant) {
        return new UsageSummaryService(repo, book, fees, new UsageProperties("2026-10-01", Map.of()),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private void feesFrom(String from, String monthly) {
        when(fees.entries()).thenReturn(List.of(new FeeEntry(LocalDate.parse(from), new BigDecimal(monthly))));
    }

    /** 입력 토큰 n백만 개 = $10 × n. */
    private static List<UsageAggRow> spent(LocalDateTime bucket, long millions) {
        return List.of(new UsageAggRow(bucket, "SYMPHONY", "claude-test-5-5", null, List.of(),
                new UsageTokens(millions * M, 0, 0, 0, 0)));
    }

    private List<UsageWindow> windows(int calls) {
        ArgumentCaptor<UsageWindow> w = ArgumentCaptor.forClass(UsageWindow.class);
        verify(repo, times(calls)).aggregate(w.capture(), anyString(), anyList());
        return w.getAllValues();
    }

    @Test
    void 진행중_달은_지금까지_구독료로_나눈_속도() {
        feesFrom("2026-10-01", "300");
        when(repo.aggregate(any(), any(), any()))
                .thenReturn(spent(LocalDateTime.of(2026, 11, 3, 0, 0), 10), List.of());

        // 11-16 12:00 KST — 11월 15.5일이 지났다
        UsageSummaryResponse r = at("2026-11-16T03:00:00Z").summary("month", null);

        assertThat(r.inProgress()).isTrue();
        assertThat(r.cost()).isEqualTo(new BigDecimal("100.0000"));
        assertThat(r.feeShare()).isEqualTo(new BigDecimal("155.0000"));
        assertThat(r.fullPeriodFeeShare()).isEqualTo(new BigDecimal("300.0000"));
        assertThat(r.ratio()).isEqualTo(new BigDecimal("0.65"));
        assertThat(r.feeConfigured()).isTrue();
        assertThat(r.feeMissing()).isFalse();
        assertThat(r.period()).isEqualTo("month");
        assertThat(r.countFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(r.series()).hasSize(30);
        assertThat(r.series().get(2).cost()).isEqualTo(new BigDecimal("100.0000"));

        UsageWindow current = windows(2).get(0);
        assertThat(current.from().toInstant()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(current.to().toInstant()).isEqualTo(Instant.parse("2026-11-16T03:00:00Z"));
    }

    @Test
    void 직전_기간은_같은_경과_구간() {
        feesFrom("2026-10-01", "310");
        when(repo.aggregate(any(), any(), any()))
                .thenReturn(spent(LocalDateTime.of(2026, 11, 3, 0, 0), 10), spent(LocalDateTime.of(2026, 10, 2, 0, 0), 3));

        UsageSummaryResponse r = at("2026-11-16T03:00:00Z").summary("month", null);

        UsageWindow prev = windows(2).get(1);
        assertThat(prev.from().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00+09:00").toInstant());
        assertThat(prev.to().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-16T12:00+09:00").toInstant());
        verify(repo, times(2)).aggregate(any(), eq("day"), eq(List.of()));

        // 10월 15.5일 × 310/31 = 155, 비용 30 → 0.19(0.1935…)
        assertThat(r.previous().start()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00+09:00"));
        assertThat(r.previous().until()).isEqualTo(OffsetDateTime.parse("2026-10-16T12:00+09:00"));
        assertThat(r.previous().cost()).isEqualTo(new BigDecimal("30.0000"));
        assertThat(r.previous().feeShare()).isEqualTo(new BigDecimal("155.0000"));
        assertThat(r.previous().ratio()).isEqualTo(new BigDecimal("0.19"));
    }

    @Test
    void 서울_자정_경계() {
        feesFrom("2026-10-01", "300");
        // 2026-10-31T15:30Z = 서울 11-01 00:30
        UsageSummaryService s = at("2026-10-31T15:30:00Z");

        UsageSummaryResponse month = s.summary(null, null);
        assertThat(month.period()).isEqualTo("month");
        assertThat(month.date()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(month.start()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00+09:00"));
        assertThat(month.end()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00+09:00"));
        assertThat(month.inProgress()).isTrue();

        UsageSummaryResponse day = s.summary("day", null);
        assertThat(day.date()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(day.series().get(0).at()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00+09:00"));

        List<UsageWindow> w = windows(4);
        assertThat(w.get(0).from().toInstant()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(w.get(0).to().toInstant()).isEqualTo(Instant.parse("2026-10-31T15:30:00Z"));
        assertThat(w.get(2).from().toInstant()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
    }

    @Test
    void 구독료_없음_일부없음_시작일이전() {
        UsageSummaryService s = at("2026-11-16T03:00:00Z");

        // 구독료 행 없음
        when(fees.entries()).thenReturn(List.of());
        when(repo.aggregate(any(), any(), any())).thenReturn(spent(LocalDateTime.of(2026, 10, 3, 0, 0), 1));
        UsageSummaryResponse none = s.summary("month", "2026-10-15");
        assertThat(none.ratio()).isNull();
        assertThat(none.feeConfigured()).isFalse();
        assertThat(none.cost()).isEqualTo(new BigDecimal("10.0000"));

        // 10-16부터 → 10월은 앞 15일에 구독료가 없다
        feesFrom("2026-10-16", "310");
        UsageSummaryResponse partial = s.summary("month", "2026-10-15");
        assertThat(partial.ratio()).isNull();
        assertThat(partial.feeMissing()).isTrue();
        assertThat(partial.feeConfigured()).isTrue();
        assertThat(partial.feeShare()).isEqualTo(new BigDecimal("160.0000"));

        // 세는 시작일(10-01) 이전 달 — 창이 비어 조회하지 않는다
        feesFrom("2026-10-01", "300");
        clearInvocations(repo);
        UsageSummaryResponse sept = s.summary("month", "2026-09-10");
        verify(repo, never()).aggregate(any(), any(), any());
        assertThat(sept.cost()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(sept.feeShare()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(sept.ratio()).isNull();
        assertThat(sept.feeMissing()).isFalse();
        assertThat(sept.inProgress()).isFalse();
    }

    @Test
    void 끝난_기간() {
        feesFrom("2026-10-01", "310");
        when(repo.aggregate(any(), any(), any())).thenReturn(spent(LocalDateTime.of(2026, 10, 3, 0, 0), 62));

        UsageSummaryResponse r = at("2026-11-16T03:00:00Z").summary("month", "2026-10-05");

        assertThat(r.inProgress()).isFalse();
        assertThat(r.feeShare()).isEqualTo(new BigDecimal("310.0000"));
        assertThat(r.fullPeriodFeeShare()).isEqualTo(r.feeShare());
        assertThat(r.ratio()).isEqualTo(new BigDecimal("2.00"));
        assertThat(r.date()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r.start()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00+09:00"));

        UsageWindow current = windows(1).get(0);
        assertThat(current.to().toInstant()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00+09:00").toInstant());

        // 직전 = 9월 전체. 세는 시작일 이전이라 비어 있고 조회하지 않는다
        assertThat(r.previous().start()).isEqualTo(OffsetDateTime.parse("2026-09-01T00:00+09:00"));
        assertThat(r.previous().until()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00+09:00"));
        assertThat(r.previous().cost()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(r.previous().feeShare()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(r.previous().ratio()).isNull();
    }

    @Test
    void 응답_시각은_모두_서울() {
        feesFrom("2026-10-01", "300");
        when(repo.aggregate(any(), any(), any())).thenReturn(List.of());
        when(repo.collectors()).thenReturn(List.of(
                new CollectorStatus("pc-a", OffsetDateTime.parse("2026-11-16T02:00:00Z"),
                        OffsetDateTime.parse("2026-11-16T01:00:00Z"), 0),
                new CollectorStatus("pc-b", OffsetDateTime.parse("2026-11-16T02:30:00Z"), null, 3)));
        when(repo.earliestEventAt(any())).thenReturn(Optional.of(OffsetDateTime.parse("2026-10-03T00:12:00Z")));

        UsageSummaryResponse r = at("2026-11-16T03:00:00Z").summary("week", null);

        List<OffsetDateTime> times = new ArrayList<>(List.of(r.start(), r.end(), r.previous().start(), r.previous().until(),
                r.coverage().earliestEventAt()));
        r.series().forEach(p -> times.add(p.at()));
        r.coverage().collectors().forEach(c -> {
            times.add(c.lastReportedAt());
            if (c.lastEventAt() != null) times.add(c.lastEventAt());
        });
        assertThat(times).hasSize(5 + 7 + 3);
        assertThat(times).allSatisfy(t -> assertThat(t.getOffset()).isEqualTo(KST));

        assertThat(r.coverage().earliestEventAt()).isEqualTo(OffsetDateTime.parse("2026-10-03T09:12+09:00"));
        assertThat(r.coverage().collectors()).extracting(CollectorStatus::machine).containsExactly("pc-a", "pc-b");
        assertThat(r.coverage().collectors().get(1).lastEventAt()).isNull();
        assertThat(r.coverage().collectors().get(1).unreadableLines()).isEqualTo(3);

        // 가장 이른 기록은 세는 시작일 서울 자정 이후에서 찾는다
        ArgumentCaptor<OffsetDateTime> notBefore = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).earliestEventAt(notBefore.capture());
        assertThat(notBefore.getValue().toInstant()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
    }

    @Test
    void 미등록_모델은_이번_기간_행에서_경고() {
        feesFrom("2026-10-01", "300");
        when(repo.aggregate(any(), any(), any())).thenReturn(
                List.of(new UsageAggRow(LocalDateTime.of(2026, 11, 3, 0, 0), "DIRECT", "claude-x-1", null, List.of(),
                        new UsageTokens(5, 0, 0, 0, 0))),
                List.of(new UsageAggRow(LocalDateTime.of(2026, 10, 3, 0, 0), "DIRECT", "claude-old-1", null, List.of(),
                        new UsageTokens(7, 0, 0, 0, 0))));

        UsageSummaryResponse r = at("2026-11-16T03:00:00Z").summary("month", null);

        assertThat(r.coverage().unpricedModels()).extracting(UsageSummaryResponse.UnpricedModel::model)
                .containsExactly("claude-x-1");
    }

    @Test
    void 잘못된_기간과_날짜는_400() {
        UsageSummaryService s = at("2026-11-16T03:00:00Z");

        assertThatThrownBy(() -> s.summary("year", null))
                .isInstanceOf(TaskException.class)
                .hasMessage("알 수 없는 기간: year")
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> s.summary("month", "2026-13-01"))
                .isInstanceOf(TaskException.class)
                .hasMessage("날짜 형식이 틀렸습니다: 2026-13-01")
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> s.csv("year", null)).isInstanceOf(TaskException.class);

        verify(repo, never()).aggregate(any(), any(), any());
    }

    @Test
    void CSV_파일_이름과_같은_창() {
        when(repo.aggregate(any(), any(), any())).thenReturn(spent(LocalDateTime.of(2026, 10, 31, 23, 0), 1));

        UsageSummaryService.CsvFile f = at("2026-11-16T03:00:00Z").csv("day", "2026-10-31");

        assertThat(f.fileName()).isEqualTo("claude-usage-day-2026-10-31.csv");
        String body = new String(f.body(), StandardCharsets.UTF_8);
        assertThat(body).startsWith((char) 0xFEFF + "날짜,");
        assertThat(body).contains("\n2026-10-31 23:00,SYMPHONY,claude-test-5-5,1000000,0,0,0,0,10.0000\n");

        ArgumentCaptor<UsageWindow> w = ArgumentCaptor.forClass(UsageWindow.class);
        verify(repo).aggregate(w.capture(), eq("hour"), anyList());
        assertThat(w.getValue().from().toInstant()).isEqualTo(Instant.parse("2026-10-30T15:00:00Z"));
        assertThat(w.getValue().to().toInstant()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
    }
}
