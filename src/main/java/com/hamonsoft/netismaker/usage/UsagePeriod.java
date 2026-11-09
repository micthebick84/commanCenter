package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.service.TaskException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 서울 날짜 기준 기간 [start, endExclusive). 경계는 서울 자정이고 주는 ISO 월요일에 시작한다.
 * 창 메서드는 모두 아래 경계를 세는 시작일(countFrom) 서울 자정으로 자른다 — 구독료 몫에도 같은 창을 쓴다.
 */
public record UsagePeriod(UsagePeriodUnit unit, LocalDate start, LocalDate endExclusive) {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** null·공백이면 서울 오늘. 형식이 틀리면 400. */
    public static LocalDate parseDate(String raw, Clock clock) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.ofInstant(clock.instant(), SEOUL);
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "날짜 형식이 틀렸습니다: " + raw);
        }
    }

    public static UsagePeriod containing(UsagePeriodUnit unit, LocalDate date) {
        return switch (unit) {
            case DAY -> new UsagePeriod(unit, date, date.plusDays(1));
            case WEEK -> {
                LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new UsagePeriod(unit, monday, monday.plusWeeks(1));
            }
            case MONTH -> {
                LocalDate first = date.withDayOfMonth(1);
                yield new UsagePeriod(unit, first, first.plusMonths(1));
            }
        };
    }

    public UsagePeriod previous() {
        return containing(unit, start.minusDays(1));
    }

    public ZonedDateTime startAt() {
        return start.atStartOfDay(SEOUL);
    }

    public ZonedDateTime endAt() {
        return endExclusive.atStartOfDay(SEOUL);
    }

    /** startAt ≤ now < endAt. */
    public boolean inProgress(ZonedDateTime now) {
        return !now.isBefore(startAt()) && now.isBefore(endAt());
    }

    /** 기간 전체 [max(startAt, countFrom 자정), endAt). */
    public UsageWindow fullWindow(LocalDate countFrom) {
        return clipped(countFrom, startAt(), endAt());
    }

    /** 지금까지 [max(startAt, countFrom 자정), min(endAt, now)). */
    public UsageWindow elapsedWindow(LocalDate countFrom, ZonedDateTime now) {
        return clipped(countFrom, startAt(), earlier(endAt(), now.withZoneSameInstant(SEOUL)));
    }

    /**
     * 직전 기간의 비교 구간. 진행 중이면 직전 기간 시작부터 같은 경과 길이까지(직전 기간 끝을 넘지 않는다),
     * 끝났거나 아직 오지 않은 기간이면 직전 기간 전체다.
     */
    public UsageWindow previousComparable(LocalDate countFrom, ZonedDateTime now) {
        UsagePeriod prev = previous();
        if (!inProgress(now)) {
            return prev.fullWindow(countFrom);
        }
        ZonedDateTime sameElapsed = prev.startAt().plus(Duration.between(startAt(), now));
        return clipped(countFrom, prev.startAt(), earlier(prev.endAt(), sameElapsed));
    }

    /** 그래프 칸의 시작 시각(서울). DAY는 24개 시간 칸, WEEK·MONTH는 날짜 칸. */
    public List<ZonedDateTime> buckets() {
        List<ZonedDateTime> out = new ArrayList<>();
        if (unit == UsagePeriodUnit.DAY) {
            for (ZonedDateTime t = startAt(); t.isBefore(endAt()); t = t.plusHours(1)) {
                out.add(t);
            }
        } else {
            for (LocalDate d = start; d.isBefore(endExclusive); d = d.plusDays(1)) {
                out.add(d.atStartOfDay(SEOUL));
            }
        }
        return out;
    }

    private static UsageWindow clipped(LocalDate countFrom, ZonedDateTime from, ZonedDateTime to) {
        ZonedDateTime floor = countFrom.atStartOfDay(SEOUL);
        return new UsageWindow(from.isBefore(floor) ? floor : from, to);
    }

    private static ZonedDateTime earlier(ZonedDateTime a, ZonedDateTime b) {
        return b.isBefore(a) ? b : a;
    }
}
