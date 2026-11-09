package com.hamonsoft.netismaker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Claude 구독료(월, USD) 이력. effectiveFrom부터 적용되고, 같은 적용 시작일은 덮어쓴다.
 * createdAt은 DB DEFAULT에 기대지 않고 서비스가 Java에서 넣는다(JPA INSERT는 null을 명시한다).
 */
@Entity
@Table(name = "usage_subscription_fee", schema = "com")
@Getter
@Setter
@NoArgsConstructor
public class UsageSubscriptionFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "monthly_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyFee;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
