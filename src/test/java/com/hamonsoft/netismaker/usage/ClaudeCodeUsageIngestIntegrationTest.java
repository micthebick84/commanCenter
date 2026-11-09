package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchResponse;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 사용량 원장 upsert·수집기 표·제약 이름 (스펙 2026-11-03 §4). CI에서만 돈다. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class ClaudeCodeUsageIngestIntegrationTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00+09:00");

    @Autowired private ClaudeCodeUsageIngestService ingest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM com.claude_usage_event");
        jdbc.update("DELETE FROM com.claude_usage_collector");
        jdbc.update("DELETE FROM com.usage_subscription_fee");
    }

    private static ClaudeCodeUsageEvent ev(String key, OffsetDateTime at, String model, long out) {
        return new ClaudeCodeUsageEvent(key, at, "SYMPHONY", model, null, null, null, "s1", false, 1, out, 0, 0, 0);
    }

    private ClaudeCodeUsageBatchResponse send(ClaudeCodeUsageEvent... events) {
        return ingest.ingest(new ClaudeCodeUsageBatchRequest("pc", 0, List.of(events)));
    }

    private long rows() {
        return jdbc.queryForObject("SELECT count(*) FROM com.claude_usage_event", Long.class);
    }

    @Test
    void 처음은_inserted_같은_묶음은_변화없음() {
        var batch = new ClaudeCodeUsageEvent[] {ev("a", T0, "m", 1), ev("b", T0, "m", 2), ev("c", T0, "m", 3)};

        assertThat(send(batch)).isEqualTo(new ClaudeCodeUsageBatchResponse(3, 3, 0, 0));
        assertThat(send(batch)).isEqualTo(new ClaudeCodeUsageBatchResponse(3, 0, 0, 0));
        assertThat(rows()).isEqualTo(3);
    }

    @Test
    void 큰_값만_칸별로_올린다() {
        send(ev("k", T0, "first", 10));

        assertThat(send(ev("k", T0.plusHours(1), "second", 250)).updated()).isEqualTo(1);
        assertThat(send(ev("k", T0.plusHours(2), "third", 100)).updated()).isZero();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT output_tokens, model, occurred_at FROM com.claude_usage_event WHERE event_key = 'k'");
        assertThat(row.get("output_tokens")).isEqualTo(250L);
        assertThat(row.get("model")).isEqualTo("first");
        assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(T0.toInstant());
    }

    @Test
    void 수집기_표() {
        var empty = ingest.ingest(new ClaudeCodeUsageBatchRequest("pc", 2, List.of()));
        assertThat(empty).isEqualTo(new ClaudeCodeUsageBatchResponse(0, 0, 0, 0));
        Map<String, Object> c = jdbc.queryForMap("SELECT * FROM com.claude_usage_collector WHERE machine = 'pc'");
        assertThat(c.get("last_event_at")).isNull();
        assertThat(c.get("unreadable_lines")).isEqualTo(2);

        send(ev("a", T0.plusHours(5), "m", 1));
        assertThat(lastEventAt()).isEqualTo(T0.plusHours(5).toInstant());

        send(ev("b", T0.plusHours(1), "m", 1));
        assertThat(lastEventAt()).isEqualTo(T0.plusHours(5).toInstant());
    }

    private java.time.Instant lastEventAt() {
        return jdbc.queryForObject("SELECT last_event_at FROM com.claude_usage_collector WHERE machine = 'pc'",
                java.sql.Timestamp.class).toInstant();
    }

    @Test
    void 제약_이름() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com.claude_usage_event (event_key, occurred_at, machine, category, model)
                VALUES ('x', now(), 'pc', 'X', 'm')"""))
                .hasMessageContaining("ck_claude_usage_event_category");

        String insert = "INSERT INTO com.usage_subscription_fee (effective_from, monthly_fee, created_by) VALUES ('2026-11-01', 200, 'admin')";
        jdbc.update(insert);
        assertThatThrownBy(() -> jdbc.update(insert))
                .hasMessageContaining("uq_usage_subscription_fee_effective_from");
    }
}
