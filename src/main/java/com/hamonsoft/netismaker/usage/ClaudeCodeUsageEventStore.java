package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

/** 사용량 원장·수집기 표 쓰기. 묶음을 한 문장으로 upsert한다(호출자가 키 중복을 이미 합쳤다). */
@Repository
@Profile("api")
public class ClaudeCodeUsageEventStore {

    public record UpsertCounts(int inserted, int updated) {}

    private static final String ROW = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPSERT_HEAD = """
            INSERT INTO com.claude_usage_event AS e
                (event_key, occurred_at, machine, category, model, speed, service_tier, entrypoint, session_id,
                 is_sidechain, input_tokens, output_tokens, cache_write_5m_tokens, cache_write_1h_tokens, cache_read_tokens)
            VALUES\s""";

    private static final String UPSERT_TAIL = """

            ON CONFLICT (event_key) DO UPDATE SET
                input_tokens          = GREATEST(e.input_tokens, EXCLUDED.input_tokens),
                output_tokens         = GREATEST(e.output_tokens, EXCLUDED.output_tokens),
                cache_write_5m_tokens = GREATEST(e.cache_write_5m_tokens, EXCLUDED.cache_write_5m_tokens),
                cache_write_1h_tokens = GREATEST(e.cache_write_1h_tokens, EXCLUDED.cache_write_1h_tokens),
                cache_read_tokens     = GREATEST(e.cache_read_tokens, EXCLUDED.cache_read_tokens)
            WHERE EXCLUDED.input_tokens > e.input_tokens
               OR EXCLUDED.output_tokens > e.output_tokens
               OR EXCLUDED.cache_write_5m_tokens > e.cache_write_5m_tokens
               OR EXCLUDED.cache_write_1h_tokens > e.cache_write_1h_tokens
               OR EXCLUDED.cache_read_tokens > e.cache_read_tokens
            RETURNING (xmax = 0) AS inserted
            """;

    private static final String TOUCH_SQL = """
            INSERT INTO com.claude_usage_collector AS c (machine, last_reported_at, last_event_at, unreadable_lines, updated_at)
            VALUES (?, now(), ?, ?, now())
            ON CONFLICT (machine) DO UPDATE SET
                last_reported_at = now(),
                last_event_at    = GREATEST(c.last_event_at, EXCLUDED.last_event_at),
                unreadable_lines = EXCLUDED.unreadable_lines,
                updated_at       = now()
            """;

    private final JdbcTemplate jdbc;

    public ClaudeCodeUsageEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UpsertCounts upsert(String machine, List<ClaudeCodeUsageEvent> events) {
        if (events == null || events.isEmpty()) {
            return new UpsertCounts(0, 0);
        }
        StringBuilder sql = new StringBuilder(UPSERT_HEAD);
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(ROW);
        }
        sql.append(UPSERT_TAIL);
        String text = sql.toString();
        List<Boolean> flags = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(text);
            int p = 1;
            for (ClaudeCodeUsageEvent ev : events) {
                ps.setString(p++, ev.eventKey());
                ps.setObject(p++, ev.occurredAt(), Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setString(p++, machine);
                ps.setString(p++, ev.category());
                ps.setString(p++, ev.model());
                p = setNullableString(ps, p, ev.speed());
                p = setNullableString(ps, p, ev.serviceTier());
                p = setNullableString(ps, p, ev.entrypoint());
                p = setNullableString(ps, p, ev.sessionId());
                ps.setBoolean(p++, Boolean.TRUE.equals(ev.sidechain()));
                ps.setLong(p++, ev.inputTokens());
                ps.setLong(p++, ev.outputTokens());
                ps.setLong(p++, ev.cacheWrite5mTokens());
                ps.setLong(p++, ev.cacheWrite1hTokens());
                ps.setLong(p++, ev.cacheReadTokens());
            }
            return ps;
        }, (rs, i) -> rs.getBoolean(1));
        int inserted = (int) flags.stream().filter(Boolean::booleanValue).count();
        return new UpsertCounts(inserted, flags.size() - inserted);
    }

    /** 빈 묶음에도 부른다. last_event_at은 더 늦은 값만 남는다(GREATEST는 NULL을 무시한다). */
    public void touchCollector(String machine, OffsetDateTime lastEventAt, int unreadableLines) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(TOUCH_SQL);
            ps.setString(1, machine);
            if (lastEventAt == null) {
                ps.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(2, lastEventAt, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            ps.setInt(3, unreadableLines);
            return ps;
        });
    }

    private static int setNullableString(PreparedStatement ps, int p, String v) throws SQLException {
        if (v == null) {
            ps.setNull(p, Types.VARCHAR);
        } else {
            ps.setString(p, v);
        }
        return p + 1;
    }
}
