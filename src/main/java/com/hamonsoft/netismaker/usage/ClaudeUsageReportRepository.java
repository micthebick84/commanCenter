package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.UsageSummaryResponse.CollectorStatus;
import com.hamonsoft.netismaker.dto.UsageTokens;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 사용량 원장·수집기 표 읽기(관리자 집계). 시각은 DB가 준 그대로 돌려주고 서울 변환은 서비스가 한다. */
@Repository
@Profile("api")
public class ClaudeUsageReportRepository {

    private static final String PROMPT_TOKENS =
            "(input_tokens + cache_write_5m_tokens + cache_write_1h_tokens + cache_read_tokens)";

    private static final String COLLECTORS_SQL = """
            SELECT machine, last_reported_at, last_event_at, unreadable_lines
            FROM com.claude_usage_collector
            ORDER BY machine""";

    private static final String EARLIEST_SQL =
            "SELECT min(occurred_at) FROM com.claude_usage_event WHERE occurred_at >= ?";

    private final JdbcTemplate jdbc;

    public ClaudeUsageReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 창 [from, to)의 이벤트를 서울 시각 버킷(bucketUnit = date_trunc 단위)·구분·모델·speed·기준값별 초과 여부로 묶는다.
     * 범위 필터는 occurred_at 그대로라 인덱스를 탄다. 창이 비면 쿼리 없이 빈 목록.
     */
    public List<UsageAggRow> aggregate(UsageWindow w, String bucketUnit, List<Long> thresholds) {
        if (w.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT date_trunc(?, occurred_at AT TIME ZONE 'Asia/Seoul') AS bucket, category, model, speed");
        for (int i = 0; i < thresholds.size(); i++) {
            sql.append(",\n       ").append(PROMPT_TOKENS).append(" > ? AS over_").append(i);
        }
        sql.append("""
                ,
                       SUM(input_tokens) AS input_sum, SUM(output_tokens) AS output_sum,
                       SUM(cache_write_5m_tokens) AS cache_write_5m_sum, SUM(cache_write_1h_tokens) AS cache_write_1h_sum,
                       SUM(cache_read_tokens) AS cache_read_sum
                FROM com.claude_usage_event
                WHERE occurred_at >= ? AND occurred_at < ?
                GROUP BY bucket, category, model, speed""");
        for (int i = 0; i < thresholds.size(); i++) {
            sql.append(", over_").append(i);
        }
        int n = thresholds.size();
        return jdbc.query(sql.toString(), ps -> {
            int p = 1;
            ps.setString(p++, bucketUnit);
            for (Long t : thresholds) {
                ps.setLong(p++, t);
            }
            ps.setObject(p++, w.from().toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setObject(p, w.to().toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE);
        }, (rs, row) -> {
            List<Boolean> over = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                over.add(rs.getBoolean("over_" + i));
            }
            return new UsageAggRow(
                    rs.getObject("bucket", LocalDateTime.class),
                    rs.getString("category"),
                    rs.getString("model"),
                    rs.getString("speed"),
                    List.copyOf(over),
                    new UsageTokens(rs.getLong("input_sum"), rs.getLong("output_sum"), rs.getLong("cache_write_5m_sum"),
                            rs.getLong("cache_write_1h_sum"), rs.getLong("cache_read_sum")));
        });
    }

    /** 수집 PC별 마지막 보고, machine 순. */
    public List<CollectorStatus> collectors() {
        return jdbc.query(COLLECTORS_SQL, (rs, row) -> new CollectorStatus(
                rs.getString("machine"),
                rs.getObject("last_reported_at", OffsetDateTime.class),
                rs.getObject("last_event_at", OffsetDateTime.class),
                rs.getInt("unreadable_lines")));
    }

    /** notBefore 이후 가장 이른 이벤트 시각. */
    public Optional<OffsetDateTime> earliestEventAt(OffsetDateTime notBefore) {
        OffsetDateTime min = jdbc.query(EARLIEST_SQL,
                ps -> ps.setObject(1, notBefore, Types.TIMESTAMP_WITH_TIMEZONE),
                rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null);
        return Optional.ofNullable(min);
    }
}
