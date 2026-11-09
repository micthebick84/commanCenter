package com.hamonsoft.netismaker.usage;

import java.time.ZonedDateTime;

/** 집계·구독료 몫을 셀 반열린 구간 [from, to). from이 to보다 늦거나 같으면 빈 창이다. */
public record UsageWindow(ZonedDateTime from, ZonedDateTime to) {

    public boolean isEmpty() {
        return !from.isBefore(to);
    }
}
