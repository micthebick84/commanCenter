package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.service.TaskException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static com.hamonsoft.netismaker.usage.UsagePeriodUnit.DAY;
import static com.hamonsoft.netismaker.usage.UsagePeriodUnit.MONTH;
import static com.hamonsoft.netismaker.usage.UsagePeriodUnit.WEEK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 서울 시각 기간·창. 시각은 UTC로 넣어 JVM 시간대(CI는 UTC, 이 PC는 KST)와 무관하게 서울 변환을 본다. */
class UsagePeriodTest {

    private static final LocalDate COUNT_FROM = LocalDate.of(2026, 10, 1);

    private static ZonedDateTime kst(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, UsagePeriod.SEOUL);
    }

    private static ZonedDateTime utc(String instant) {
        return Instant.parse(instant).atZone(ZoneOffset.UTC);
    }

    private static LocalDate date(int y, int m, int d) {
        return LocalDate.of(y, m, d);
    }

    @Test
    void 주는_월요일_시작() {
        assertThat(UsagePeriod.containing(WEEK, date(2026, 11, 5)))
                .isEqualTo(new UsagePeriod(WEEK, date(2026, 11, 2), date(2026, 11, 9)));
        assertThat(UsagePeriod.containing(WEEK, date(2026, 11, 2)))
                .isEqualTo(new UsagePeriod(WEEK, date(2026, 11, 2), date(2026, 11, 9)));
        assertThat(UsagePeriod.containing(WEEK, date(2026, 11, 8)))
                .isEqualTo(new UsagePeriod(WEEK, date(2026, 11, 2), date(2026, 11, 9)));
    }

    @Test
    void 월과_일() {
        assertThat(UsagePeriod.containing(MONTH, date(2026, 2, 14)))
                .isEqualTo(new UsagePeriod(MONTH, date(2026, 2, 1), date(2026, 3, 1)));
        assertThat(UsagePeriod.containing(DAY, date(2026, 2, 14)))
                .isEqualTo(new UsagePeriod(DAY, date(2026, 2, 14), date(2026, 2, 15)));

        assertThat(UsagePeriod.containing(MONTH, date(2026, 3, 20)).previous())
                .isEqualTo(new UsagePeriod(MONTH, date(2026, 2, 1), date(2026, 3, 1)));
        assertThat(UsagePeriod.containing(WEEK, date(2026, 11, 5)).previous())
                .isEqualTo(new UsagePeriod(WEEK, date(2026, 10, 26), date(2026, 11, 2)));
        assertThat(UsagePeriod.containing(DAY, date(2026, 3, 1)).previous())
                .isEqualTo(new UsagePeriod(DAY, date(2026, 2, 28), date(2026, 3, 1)));
    }

    @Test
    void 경계는_서울_자정() {
        UsagePeriod nov = UsagePeriod.containing(MONTH, date(2026, 11, 5));

        assertThat(nov.startAt().toInstant()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(nov.endAt().toInstant()).isEqualTo(Instant.parse("2026-11-30T15:00:00Z"));
        assertThat(nov.startAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));

        // (Review Focus 1) 2026-10-31T15:30Z = KST 11-01 00:30 → 11월이 진행 중이고 10월은 끝났다
        ZonedDateTime now = utc("2026-10-31T15:30:00Z");
        assertThat(nov.inProgress(now)).isTrue();
        assertThat(nov.previous().inProgress(now)).isFalse();
        assertThat(nov.inProgress(utc("2026-10-31T15:00:00Z"))).isTrue();
        assertThat(nov.inProgress(utc("2026-10-31T14:59:59Z"))).isFalse();
        assertThat(nov.inProgress(utc("2026-11-30T15:00:00Z"))).isFalse();
    }

    @Test
    void 파싱() {
        assertThat(UsagePeriodUnit.parse(null)).isEqualTo(MONTH);
        assertThat(UsagePeriodUnit.parse("day")).isEqualTo(DAY);
        assertThat(UsagePeriodUnit.parse("week")).isEqualTo(WEEK);
        assertThat(UsagePeriodUnit.parse("month")).isEqualTo(MONTH);
        assertThat(DAY.wire()).isEqualTo("day");
        assertThat(DAY.bucketUnit()).isEqualTo("hour");
        assertThat(WEEK.bucketUnit()).isEqualTo("day");
        assertThat(MONTH.bucketUnit()).isEqualTo("day");

        assertThatThrownBy(() -> UsagePeriodUnit.parse("year"))
                .isInstanceOf(TaskException.class)
                .hasMessage("알 수 없는 기간: year")
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> UsagePeriodUnit.parse("MONTH"))
                .isInstanceOf(TaskException.class)
                .hasMessage("알 수 없는 기간: MONTH");

        Clock clock = Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), ZoneOffset.UTC);
        assertThat(UsagePeriod.parseDate(null, clock)).isEqualTo(date(2026, 11, 1));
        assertThat(UsagePeriod.parseDate("  ", clock)).isEqualTo(date(2026, 11, 1));
        assertThat(UsagePeriod.parseDate("2026-10-15", clock)).isEqualTo(date(2026, 10, 15));
        assertThatThrownBy(() -> UsagePeriod.parseDate("2026-13-01", clock))
                .isInstanceOf(TaskException.class)
                .hasMessage("날짜 형식이 틀렸습니다: 2026-13-01")
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 같은_경과_구간() {
        UsagePeriod nov = UsagePeriod.containing(MONTH, date(2026, 11, 16));
        ZonedDateTime now = utc("2026-11-16T03:00:00Z"); // KST 11-16 12:00

        assertThat(nov.inProgress(now)).isTrue();

        UsageWindow elapsed = nov.elapsedWindow(COUNT_FROM, now);
        assertThat(elapsed.from().toInstant()).isEqualTo(kst(2026, 11, 1, 0, 0).toInstant());
        assertThat(elapsed.to().toInstant()).isEqualTo(kst(2026, 11, 16, 12, 0).toInstant());
        assertThat(elapsed.to().getOffset()).isEqualTo(ZoneOffset.ofHours(9));

        UsageWindow prev = nov.previousComparable(COUNT_FROM, now);
        assertThat(prev.from().toInstant()).isEqualTo(kst(2026, 10, 1, 0, 0).toInstant());
        assertThat(prev.to().toInstant()).isEqualTo(kst(2026, 10, 16, 12, 0).toInstant());
    }

    @Test
    void 직전_비교는_직전_기간_끝까지() {
        UsagePeriod mar = UsagePeriod.containing(MONTH, date(2027, 3, 31));
        ZonedDateTime now = kst(2027, 3, 31, 12, 0);

        UsageWindow prev = mar.previousComparable(COUNT_FROM, now);
        assertThat(prev.from().toInstant()).isEqualTo(kst(2027, 2, 1, 0, 0).toInstant());
        assertThat(prev.to().toInstant()).isEqualTo(kst(2027, 3, 1, 0, 0).toInstant());

        // 끝난 기간이면 직전 기간 전체
        UsagePeriod nov = UsagePeriod.containing(MONTH, date(2026, 11, 1));
        UsageWindow done = nov.previousComparable(COUNT_FROM, kst(2026, 12, 5, 9, 0));
        assertThat(done.from().toInstant()).isEqualTo(kst(2026, 10, 1, 0, 0).toInstant());
        assertThat(done.to().toInstant()).isEqualTo(kst(2026, 11, 1, 0, 0).toInstant());
    }

    @Test
    void count_from이_아래_경계() {
        UsagePeriod week = UsagePeriod.containing(WEEK, date(2026, 9, 28));
        UsageWindow full = week.fullWindow(COUNT_FROM);
        assertThat(full.from().toInstant()).isEqualTo(kst(2026, 10, 1, 0, 0).toInstant());
        assertThat(full.to().toInstant()).isEqualTo(kst(2026, 10, 5, 0, 0).toInstant());
        assertThat(full.isEmpty()).isFalse();

        ZonedDateTime now = kst(2026, 10, 3, 6, 0);
        UsageWindow elapsed = week.elapsedWindow(COUNT_FROM, now);
        assertThat(elapsed.from().toInstant()).isEqualTo(kst(2026, 10, 1, 0, 0).toInstant());
        assertThat(elapsed.to().toInstant()).isEqualTo(now.toInstant());
        // 직전 주(09-21~09-28)는 시작일 전이라 비교 구간도 빈다
        assertThat(week.previousComparable(COUNT_FROM, now).isEmpty()).isTrue();

        assertThat(UsagePeriod.containing(MONTH, date(2026, 9, 10)).fullWindow(COUNT_FROM).isEmpty()).isTrue();
        assertThat(UsagePeriod.containing(MONTH, date(2026, 8, 10)).fullWindow(COUNT_FROM).isEmpty()).isTrue();
    }

    @Test
    void 버킷() {
        List<ZonedDateTime> hours = UsagePeriod.containing(DAY, date(2026, 11, 1)).buckets();
        assertThat(hours).hasSize(24);
        assertThat(hours.get(0)).isEqualTo(kst(2026, 11, 1, 0, 0));
        assertThat(hours.get(23)).isEqualTo(kst(2026, 11, 1, 23, 0));

        List<ZonedDateTime> feb = UsagePeriod.containing(MONTH, date(2026, 2, 14)).buckets();
        assertThat(feb).hasSize(28);
        assertThat(feb.get(0)).isEqualTo(kst(2026, 2, 1, 0, 0));
        assertThat(feb.get(27)).isEqualTo(kst(2026, 2, 28, 0, 0));

        List<ZonedDateTime> week = UsagePeriod.containing(WEEK, date(2026, 11, 5)).buckets();
        assertThat(week).hasSize(7);
        assertThat(week.get(0)).isEqualTo(kst(2026, 11, 2, 0, 0));
        assertThat(week.get(6)).isEqualTo(kst(2026, 11, 8, 0, 0));
    }
}
