package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.SubscriptionFeeRequest;
import com.hamonsoft.netismaker.dto.SubscriptionFeeView;
import com.hamonsoft.netismaker.entity.UsageSubscriptionFee;
import com.hamonsoft.netismaker.repository.UsageSubscriptionFeeRepository;
import com.hamonsoft.netismaker.usage.SubscriptionFeeShare.FeeEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageSubscriptionFeeServiceTest {

    private UsageSubscriptionFeeRepository repo;
    private UsageSubscriptionFeeService service;

    @BeforeEach
    void setUp() {
        repo = mock(UsageSubscriptionFeeRepository.class);
        service = new UsageSubscriptionFeeService(repo);
        when(repo.save(any(UsageSubscriptionFee.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static UsageSubscriptionFee row(String from, String fee) {
        UsageSubscriptionFee f = new UsageSubscriptionFee();
        f.setEffectiveFrom(LocalDate.parse(from));
        f.setMonthlyFee(new BigDecimal(fee));
        f.setCurrency("USD");
        f.setCreatedBy("x");
        f.setCreatedAt(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        return f;
    }

    @Test
    void 새_적용시작일은_추가() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        when(repo.findByEffectiveFrom(d)).thenReturn(Optional.empty());

        service.save(new SubscriptionFeeRequest(d, new BigDecimal("200")), "a");

        ArgumentCaptor<UsageSubscriptionFee> c = ArgumentCaptor.forClass(UsageSubscriptionFee.class);
        verify(repo, times(1)).save(c.capture());
        UsageSubscriptionFee saved = c.getValue();
        assertThat(saved.getEffectiveFrom()).isEqualTo(d);
        assertThat(saved.getMonthlyFee()).isEqualByComparingTo("200");
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getCreatedBy()).isEqualTo("a");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void 같은_적용시작일은_덮어쓴다() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        UsageSubscriptionFee existing = row("2026-10-01", "200");
        OffsetDateTime before = existing.getCreatedAt();
        when(repo.findByEffectiveFrom(d)).thenReturn(Optional.of(existing));

        service.save(new SubscriptionFeeRequest(d, new BigDecimal("250")), "b");

        ArgumentCaptor<UsageSubscriptionFee> c = ArgumentCaptor.forClass(UsageSubscriptionFee.class);
        verify(repo, times(1)).save(c.capture());
        assertThat(c.getValue()).isSameAs(existing);
        assertThat(existing.getMonthlyFee()).isEqualByComparingTo("250");
        assertThat(existing.getCreatedBy()).isEqualTo("b");
        assertThat(existing.getCreatedAt()).isNotNull().isAfter(before);
    }

    @Test
    void 이력은_적용시작일_최신순() {
        when(repo.findAllByOrderByEffectiveFromDesc())
                .thenReturn(List.of(row("2026-11-01", "300"), row("2026-10-01", "200")));

        List<SubscriptionFeeView> list = service.list();

        assertThat(list).extracting(SubscriptionFeeView::effectiveFrom)
                .containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1));
        assertThat(list.get(0).monthlyFee()).isEqualByComparingTo("300");
        assertThat(list.get(0).currency()).isEqualTo("USD");
        assertThat(list.get(0).createdBy()).isEqualTo("x");
        // 저장값이 UTC여도 서울(+09:00)로 내보낸다
        assertThat(list.get(0).createdAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(list.get(0).createdAt().toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant());

        List<FeeEntry> entries = service.entries();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(entries.get(0).monthlyFee()).isEqualByComparingTo("300");
        assertThat(entries.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        verify(repo, never()).save(any());
    }
}
