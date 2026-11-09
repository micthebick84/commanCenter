package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.UsageSubscriptionFee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UsageSubscriptionFeeRepository extends JpaRepository<UsageSubscriptionFee, Long> {

    List<UsageSubscriptionFee> findAllByOrderByEffectiveFromDesc();

    Optional<UsageSubscriptionFee> findByEffectiveFrom(LocalDate effectiveFrom);
}
