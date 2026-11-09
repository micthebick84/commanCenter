package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.SubscriptionFeeRequest;
import com.hamonsoft.netismaker.dto.SubscriptionFeeView;
import com.hamonsoft.netismaker.usage.UsageSubscriptionFeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구독료 저장이 실제 표(V42)·엔티티와 맞는지(ddl-auto validate 포함) — CI(Testcontainers)에서만 돈다. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class UsageSubscriptionFeeIntegrationTest {

    @Autowired private UsageSubscriptionFeeService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM com.claude_usage_event");
        jdbc.update("DELETE FROM com.claude_usage_collector");
        jdbc.update("DELETE FROM com.usage_subscription_fee");
    }

    @Test
    void 같은_날짜_두번이면_한_행_목록은_최신순() {
        service.save(new SubscriptionFeeRequest(LocalDate.of(2026, 10, 1), new BigDecimal("200")), "a");
        service.save(new SubscriptionFeeRequest(LocalDate.of(2026, 11, 1), new BigDecimal("300")), "a");
        service.save(new SubscriptionFeeRequest(LocalDate.of(2026, 10, 1), new BigDecimal("250")), "b");

        List<SubscriptionFeeView> list = service.list();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(list.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(list.get(1).monthlyFee()).isEqualByComparingTo("250");
        assertThat(list.get(1).createdBy()).isEqualTo("b");
        assertThat(list.get(1).createdAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
    }
}
