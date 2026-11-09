package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.usage.UsageProperties.ModelPrice;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 기록의 모델 이름을 가격표 키로 풀고 단가를 돌려준다. */
@Component
@Profile("api")
public class UsagePriceBook {

    private static final Pattern DATE_SUFFIX = Pattern.compile("-[0-9]{8}$");

    private final UsageProperties properties;

    public UsagePriceBook(UsageProperties properties) {
        this.properties = properties;
    }

    /** 정확히 일치하는 키를 먼저 찾고, 없으면 끝의 -YYYYMMDD를 떼고 다시 찾는다. */
    public Optional<String> resolveKey(String model) {
        if (model == null) {
            return Optional.empty();
        }
        if (properties.prices().containsKey(model)) {
            return Optional.of(model);
        }
        String stripped = DATE_SUFFIX.matcher(model).replaceFirst("");
        return properties.prices().containsKey(stripped) ? Optional.of(stripped) : Optional.empty();
    }

    public ModelPrice price(String key) {
        return properties.prices().get(key);
    }

    /** long-context.threshold의 서로 다른 값, 오름차순. */
    public List<Long> thresholds() {
        return properties.prices().values().stream()
                .map(ModelPrice::longContext)
                .filter(lc -> lc != null && lc.threshold() != null)
                .map(UsageProperties.LongContext::threshold)
                .distinct()
                .sorted()
                .toList();
    }
}
