package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageSummaryResponse;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.Coverage;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.CollectorStatus;
import com.hamonsoft.netismaker.dto.UsageSummaryResponse.PreviousUsage;
import com.hamonsoft.netismaker.usage.SubscriptionFeeShare.FeeEntry;
import com.hamonsoft.netismaker.usage.SubscriptionFeeShare.FeeShare;
import com.hamonsoft.netismaker.usage.UsageSummaryAssembler.Assembled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static com.hamonsoft.netismaker.usage.UsagePeriod.SEOUL;
import static com.hamonsoft.netismaker.usage.UsageSummaryAssembler.money;

/**
 * 관리자 사용량 요약·CSV(스펙 2026-11-03 §6). 기간은 서울 날짜 기준이고 응답 시각은 모두 +09:00이다.
 * 진행 중인 기간의 비용·구독료 몫은 지금까지의 창으로 세고(배수 = 지금까지의 속도), 직전 기간은 같은 경과 구간과 비교한다.
 */
@Service
@Profile("api")
public class UsageSummaryService {

    /** 내려받을 CSV 파일 이름과 본문(UTF-8). */
    public record CsvFile(String fileName, byte[] body) {}

    private final ClaudeUsageReportRepository repo;
    private final UsagePriceBook book;
    private final UsageSubscriptionFeeService fees;
    private final UsageProperties properties;
    private final Clock clock;

    @Autowired
    public UsageSummaryService(ClaudeUsageReportRepository repo, UsagePriceBook book,
                               UsageSubscriptionFeeService fees, UsageProperties properties) {
        this(repo, book, fees, properties, Clock.systemUTC());
    }

    UsageSummaryService(ClaudeUsageReportRepository repo, UsagePriceBook book,
                        UsageSubscriptionFeeService fees, UsageProperties properties, Clock clock) {
        this.repo = repo;
        this.book = book;
        this.fees = fees;
        this.properties = properties;
        this.clock = clock;
    }

    /** period가 없으면 month, date가 없으면 서울 오늘. 형식이 틀리면 400(조회 전에 거른다). */
    public UsageSummaryResponse summary(String period, String date) {
        Request req = request(period, date);
        LocalDate countFrom = properties.countFromDate();
        UsagePeriod p = req.period();
        boolean inProgress = p.inProgress(req.now());
        UsageWindow window = costWindow(p, countFrom, req.now());
        List<Long> thresholds = book.thresholds();
        List<FeeEntry> feeEntries = fees.entries();

        List<UsageAggRow> rows = aggregate(window, p.unit(), thresholds);
        Assembled cur = UsageSummaryAssembler.assemble(rows, book, thresholds);
        FeeShare share = SubscriptionFeeShare.between(feeEntries, window);
        FeeShare fullShare = SubscriptionFeeShare.between(feeEntries, p.fullWindow(countFrom));

        UsageWindow prevWindow = p.previousComparable(countFrom, req.now());
        Assembled prev = UsageSummaryAssembler.assemble(aggregate(prevWindow, p.unit(), thresholds), book, thresholds);
        FeeShare prevShare = SubscriptionFeeShare.between(feeEntries, prevWindow);
        PreviousUsage previous = new PreviousUsage(seoul(p.previous().startAt()), seoul(prevWindow.to()),
                money(prev.cost()), money(prevShare.share()), ratio(prev.cost(), prevShare));

        Coverage coverage = new Coverage(
                repo.collectors().stream()
                        .map(c -> new CollectorStatus(c.machine(), seoul(c.lastReportedAt()), seoul(c.lastEventAt()),
                                c.unreadableLines()))
                        .toList(),
                repo.earliestEventAt(countFrom.atStartOfDay(SEOUL).toOffsetDateTime())
                        .map(UsageSummaryService::seoul)
                        .orElse(null),
                cur.unpriced());

        return new UsageSummaryResponse(
                p.unit().wire(), req.date(), seoul(p.startAt()), seoul(p.endAt()), countFrom, inProgress,
                cur.tokens(), money(cur.cost()), cur.byCategory(), cur.byModel(),
                share.anyFee(), share.missingDays(), money(share.share()), money(fullShare.share()),
                ratio(cur.cost(), share),
                previous, UsageSummaryAssembler.series(p, cur.costByBucket()), coverage);
    }

    /** summary와 같은 창의 일별(일 기간은 시간별) 행. 파일 이름은 claude-usage-{period}-{기간 시작일}.csv. */
    public CsvFile csv(String period, String date) {
        Request req = request(period, date);
        UsagePeriod p = req.period();
        UsageWindow window = costWindow(p, properties.countFromDate(), req.now());
        List<Long> thresholds = book.thresholds();
        String body = UsageSummaryAssembler.csv(aggregate(window, p.unit(), thresholds), book, thresholds, p.unit());
        return new CsvFile("claude-usage-" + p.unit().wire() + "-" + p.start() + ".csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private record Request(LocalDate date, UsagePeriod period, ZonedDateTime now) {}

    private Request request(String period, String date) {
        UsagePeriodUnit unit = UsagePeriodUnit.parse(period);
        LocalDate day = UsagePeriod.parseDate(date, clock);
        return new Request(day, UsagePeriod.containing(unit, day), clock.instant().atZone(SEOUL));
    }

    /** 진행 중이면 지금까지, 아니면 기간 전체. 구독료 몫도 같은 창으로 센다. */
    private static UsageWindow costWindow(UsagePeriod p, LocalDate countFrom, ZonedDateTime now) {
        return p.inProgress(now) ? p.elapsedWindow(countFrom, now) : p.fullWindow(countFrom);
    }

    private List<UsageAggRow> aggregate(UsageWindow w, UsagePeriodUnit unit, List<Long> thresholds) {
        return w.isEmpty() ? List.of() : repo.aggregate(w, unit.bucketUnit(), thresholds);
    }

    /**
     * 비용 ÷ 구독료 몫(반올림 전 값끼리, 결과 2자리). 구독료 이력이 없거나, 창에 구독료 없는 날이 섞였거나,
     * 몫이 0(빈 창)이면 null.
     */
    private static BigDecimal ratio(BigDecimal cost, FeeShare share) {
        if (!share.anyFee() || share.missingDays() || share.share().signum() == 0) {
            return null;
        }
        return cost.divide(share.share(), 2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime seoul(ZonedDateTime t) {
        return t.withZoneSameInstant(SEOUL).toOffsetDateTime();
    }

    private static OffsetDateTime seoul(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(SEOUL).toOffsetDateTime();
    }
}
