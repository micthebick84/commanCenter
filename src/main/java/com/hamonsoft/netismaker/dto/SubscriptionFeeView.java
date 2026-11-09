package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** createdAt은 서울(+09:00)로 바꿔 내보낸다. */
public record SubscriptionFeeView(Long id, LocalDate effectiveFrom, BigDecimal monthlyFee, String currency,
                                  String createdBy, OffsetDateTime createdAt) {}
