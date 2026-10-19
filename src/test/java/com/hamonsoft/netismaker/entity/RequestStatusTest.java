package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.hamonsoft.netismaker.entity.RequestStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class RequestStatusTest {

    @Test
    void db_values_round_trip() {
        for (RequestStatus s : RequestStatus.values()) {
            assertThat(RequestStatus.fromDb(s.dbValue())).isEqualTo(s);
        }
        assertThat(RequestStatus.fromDb("검토중")).isEqualTo(REVIEWING);
    }

    @Test
    void open_statuses() {
        assertThat(RECEIVED.isOpen()).isTrue();
        assertThat(REVIEWING.isOpen()).isTrue();
        assertThat(DONE.isOpen()).isFalse();
        assertThat(REJECTED.isOpen()).isFalse();
    }

    @Test
    void transition_table() {
        Map<RequestStatus, Set<RequestStatus>> allowed = Map.of(
                RECEIVED, Set.of(REVIEWING, DONE, REJECTED),
                REVIEWING, Set.of(DONE, REJECTED, RECEIVED),
                DONE, Set.of(REVIEWING),
                REJECTED, Set.of(REVIEWING));
        for (RequestStatus from : RequestStatus.values()) {
            for (RequestStatus to : RequestStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(allowed.get(from).contains(to));
            }
        }
    }
}
