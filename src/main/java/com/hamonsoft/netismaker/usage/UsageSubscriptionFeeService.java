package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.SubscriptionFeeRequest;
import com.hamonsoft.netismaker.dto.SubscriptionFeeView;
import com.hamonsoft.netismaker.entity.UsageSubscriptionFee;
import com.hamonsoft.netismaker.repository.UsageSubscriptionFeeRepository;
import com.hamonsoft.netismaker.usage.SubscriptionFeeShare.FeeEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static com.hamonsoft.netismaker.usage.UsagePeriod.SEOUL;

/** 구독료 이력(관리자 입력). 같은 적용 시작일은 금액·작성자·시각을 바꾼다. */
@Service
@Profile("api")
public class UsageSubscriptionFeeService {

    private final UsageSubscriptionFeeRepository repo;

    public UsageSubscriptionFeeService(UsageSubscriptionFeeRepository repo) {
        this.repo = repo;
    }

    /** 적용 시작일 최신순. */
    @Transactional(readOnly = true)
    public List<SubscriptionFeeView> list() {
        return repo.findAllByOrderByEffectiveFromDesc().stream().map(UsageSubscriptionFeeService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<FeeEntry> entries() {
        return repo.findAllByOrderByEffectiveFromDesc().stream()
                .map(f -> new FeeEntry(f.getEffectiveFrom(), f.getMonthlyFee()))
                .toList();
    }

    @Transactional
    public SubscriptionFeeView save(SubscriptionFeeRequest req, String userId) {
        UsageSubscriptionFee row = repo.findByEffectiveFrom(req.effectiveFrom()).orElseGet(() -> {
            UsageSubscriptionFee n = new UsageSubscriptionFee();
            n.setEffectiveFrom(req.effectiveFrom());
            n.setCurrency("USD");
            return n;
        });
        row.setMonthlyFee(req.monthlyFee());
        row.setCreatedBy(userId);
        row.setCreatedAt(OffsetDateTime.now(SEOUL));
        return view(repo.save(row));
    }

    private static SubscriptionFeeView view(UsageSubscriptionFee f) {
        OffsetDateTime at = f.getCreatedAt() == null ? null : f.getCreatedAt().atZoneSameInstant(SEOUL).toOffsetDateTime();
        return new SubscriptionFeeView(f.getId(), f.getEffectiveFrom(), f.getMonthlyFee(), f.getCurrency(),
                f.getCreatedBy(), at);
    }
}
