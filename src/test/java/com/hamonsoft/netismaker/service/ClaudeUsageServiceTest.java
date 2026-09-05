package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ClaudeUsageResponse;
import com.hamonsoft.netismaker.dto.WorkerRateLimitRequest;
import com.hamonsoft.netismaker.entity.ClaudeRateLimit;
import com.hamonsoft.netismaker.repository.ClaudeRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeUsageServiceTest {

    private ClaudeRateLimitRepository repo;
    private ClaudeUsageService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClaudeRateLimitRepository.class);
        service = new ClaudeUsageService(repo);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static WorkerRateLimitRequest req(String type, String util, String resetsAt, Boolean overage) {
        return new WorkerRateLimitRequest(type, "allowed", new BigDecimal(util),
                resetsAt == null ? null : OffsetDateTime.parse(resetsAt), overage);
    }

    @Test
    void record_inserts_new_row_keyed_by_limit_type() {
        when(repo.findById("five_hour")).thenReturn(Optional.empty());
        ClaudeRateLimit row = service.record(req("five_hour", "0.42", "2026-09-05T07:00:00Z", null), "iw-1");
        assertThat(row.getLimitType()).isEqualTo("five_hour");
        assertThat(row.getStatus()).isEqualTo("allowed");
        assertThat(row.getUtilization()).isEqualByComparingTo("0.42");
        assertThat(row.getResetsAt()).isEqualTo(OffsetDateTime.parse("2026-09-05T07:00:00Z"));
        assertThat(row.isUsingOverage()).isFalse();          // null → false
        assertThat(row.getReportedBy()).isEqualTo("iw-1");
        assertThat(row.getUpdatedAt()).isNotNull();
        verify(repo).save(row);
    }

    @Test
    void record_updates_existing_row_in_place() {
        ClaudeRateLimit existing = new ClaudeRateLimit();
        existing.setLimitType("seven_day");
        existing.setStatus("allowed");
        existing.setUtilization(new BigDecimal("0.10"));
        existing.setReportedBy("old");
        existing.setUpdatedAt(OffsetDateTime.now().minusHours(1));
        when(repo.findById("seven_day")).thenReturn(Optional.of(existing));

        ClaudeRateLimit row = service.record(req("seven_day", "0.63", null, true), "iw-2");

        assertThat(row).isSameAs(existing);
        assertThat(row.getUtilization()).isEqualByComparingTo("0.63");
        assertThat(row.getResetsAt()).isNull();
        assertThat(row.isUsingOverage()).isTrue();
        assertThat(row.getReportedBy()).isEqualTo("iw-2");
        assertThat(row.getUpdatedAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void snapshot_maps_rows_in_repository_order() {
        ClaudeRateLimit a = new ClaudeRateLimit();
        a.setLimitType("five_hour");
        a.setStatus("allowed");
        a.setUtilization(new BigDecimal("0.42"));
        a.setReportedBy("iw-1");
        a.setUpdatedAt(OffsetDateTime.now());
        when(repo.findAllByOrderByLimitTypeAsc()).thenReturn(List.of(a));

        ClaudeUsageResponse res = service.snapshot();

        assertThat(res.limits()).hasSize(1);
        assertThat(res.limits().get(0).limitType()).isEqualTo("five_hour");
        assertThat(res.limits().get(0).utilization()).isEqualByComparingTo("0.42");
        assertThat(res.limits().get(0).usingOverage()).isFalse();
    }
}
