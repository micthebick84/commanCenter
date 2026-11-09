package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import com.hamonsoft.netismaker.usage.ClaudeCodeUsageIngestService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 집계 SQL(서울 시각 버킷·불린 GROUP BY)·수집기 상태·CSV를 실제 PostgreSQL로 본다 — CI(Testcontainers)에서만 돈다.
 * 시각은 문자열이 아니라 OffsetDateTime으로 읽어 instant·offset을 비교한다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = {
        "app.usage.prices.claude-test-5-5.input=3",
        "app.usage.prices.claude-test-5-5.output=15",
        "app.usage.prices.claude-test-5-5.cache-write-5m=3.75",
        "app.usage.prices.claude-test-5-5.cache-write-1h=6",
        "app.usage.prices.claude-test-5-5.cache-read=0.3"
})
class UsageSummaryIntegrationTest {

    /** 서울 10-31 23:30 */
    private static final OffsetDateTime OCT31_2330 = OffsetDateTime.parse("2026-10-31T14:30:00Z");
    /** 서울 11-01 00:30 */
    private static final OffsetDateTime NOV01_0030 = OffsetDateTime.parse("2026-10-31T15:30:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private ClaudeCodeUsageIngestService ingest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM com.claude_usage_event");
        jdbc.update("DELETE FROM com.claude_usage_collector");
        jdbc.update("DELETE FROM com.usage_subscription_fee");
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "boss").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private void send(String machine, String key, OffsetDateTime at, long input) {
        ingest.ingest(new ClaudeCodeUsageBatchRequest(machine, 0, List.of(new ClaudeCodeUsageEvent(
                key, at, "SYMPHONY", "claude-test-5-5", null, null, null, "s1", false, input, 0, 0, 0, 0))));
    }

    private String summary(String period, String date) throws Exception {
        return mvc.perform(get("/api/usage/summary").param("period", period).param("date", date).with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static BigDecimal num(String json, String path) {
        Object v = JsonPath.read(json, path);
        return new BigDecimal(v.toString());
    }

    private static OffsetDateTime time(String json, String path) {
        return OffsetDateTime.parse(JsonPath.read(json, path));
    }

    @Test
    void 서울_시각으로_묶는다() throws Exception {
        send("pc", "k1", OCT31_2330, 1_000_000);
        send("pc", "k2", NOV01_0030, 2_000_000);

        String oct = summary("month", "2026-10-15");
        assertThat(num(oct, "$.tokens.input")).isEqualByComparingTo("1000000");
        assertThat(num(oct, "$.cost")).isEqualByComparingTo("3");
        assertThat(time(oct, "$.start").toInstant()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(time(oct, "$.start").getOffset()).isEqualTo(ZoneOffset.ofHours(9));

        String oct31 = summary("day", "2026-10-31");
        List<Object> series = JsonPath.read(oct31, "$.series");
        assertThat(series).hasSize(24);
        assertThat(num(oct31, "$.series[23].cost")).isEqualByComparingTo("3");
        assertThat(num(oct31, "$.series[0].cost")).isEqualByComparingTo("0");
        assertThat(time(oct31, "$.series[23].at").getOffset()).isEqualTo(ZoneOffset.ofHours(9));

        String nov01 = summary("day", "2026-11-01");
        assertThat(num(nov01, "$.series[0].cost")).isEqualByComparingTo("6");
        assertThat(num(nov01, "$.tokens.input")).isEqualByComparingTo("2000000");
    }

    @Test
    void 수집기_상태와_가장_이른_기록() throws Exception {
        send("pc-b", "k1", OCT31_2330, 1_000);
        // 세는 시작일(서울 10-01) 이전 기록 — 가장 이른 기록에서 빠진다
        send("pc-a", "old", OffsetDateTime.parse("2026-09-30T14:00:00Z"), 1_000);

        String json = summary("month", "2026-10-15");

        OffsetDateTime earliest = time(json, "$.coverage.earliestEventAt");
        assertThat(earliest.toInstant()).isEqualTo(OCT31_2330.toInstant());
        assertThat(earliest.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        List<String> machines = JsonPath.read(json, "$.coverage.collectors[*].machine");
        assertThat(machines).containsExactly("pc-a", "pc-b");
        assertThat(time(json, "$.coverage.collectors[0].lastReportedAt").getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(time(json, "$.coverage.collectors[1].lastEventAt").toInstant()).isEqualTo(OCT31_2330.toInstant());
    }

    @Test
    void CSV_본문() throws Exception {
        send("pc", "k1", OCT31_2330, 1_000_000);
        send("pc", "k2", NOV01_0030, 2_000_000);

        var res = mvc.perform(get("/api/usage/summary.csv").param("period", "day").param("date", "2026-10-31")
                        .with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(res.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(res.getHeader("Content-Disposition")).contains("claude-usage-day-2026-10-31.csv");
        List<String> lines = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith((char) 0xFEFF + "날짜,구분,모델,");
        assertThat(lines.get(1)).startsWith("2026-10-31 23:00,SYMPHONY,claude-test-5-5,");
    }
}
