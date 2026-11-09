package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.service.TaskException;
import org.springframework.http.HttpStatus;

/** 사용량 보기 단위. wire = 요청 값, bucketUnit = 그래프 칸(SQL date_trunc 단위). */
public enum UsagePeriodUnit {
    DAY("day", "hour"),
    WEEK("week", "day"),
    MONTH("month", "day");

    private final String wire;
    private final String bucketUnit;

    UsagePeriodUnit(String wire, String bucketUnit) {
        this.wire = wire;
        this.bucketUnit = bucketUnit;
    }

    public String wire() {
        return wire;
    }

    public String bucketUnit() {
        return bucketUnit;
    }

    /** null이면 MONTH. 소문자 day·week·month 밖이면 400. */
    public static UsagePeriodUnit parse(String raw) {
        if (raw == null) {
            return MONTH;
        }
        for (UsagePeriodUnit unit : values()) {
            if (unit.wire.equals(raw)) {
                return unit;
            }
        }
        throw new TaskException(HttpStatus.BAD_REQUEST, "알 수 없는 기간: " + raw);
    }
}
