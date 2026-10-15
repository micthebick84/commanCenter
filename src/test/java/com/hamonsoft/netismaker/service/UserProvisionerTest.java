package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserProvisionerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MutableClock clock = new MutableClock();
    private final UserProvisioner provisioner = new UserProvisioner(jdbc, clock);

    @Test
    void inserts_once_per_username() {
        provisioner.ensureUser("newbie", "newbie@hamonsoft.co.kr");
        provisioner.ensureUser("newbie", "newbie@hamonsoft.co.kr");

        verify(jdbc, times(1)).update(UserProvisioner.INSERT_SQL,
                "newbie", "newbie", UserProvisioner.NO_LOGIN_PASSWORD, "newbie@hamonsoft.co.kr");
    }

    @Test
    void skips_username_longer_than_user_id_column() {
        provisioner.ensureUser("a".repeat(21), "x@hamonsoft.co.kr");

        verifyNoInteractions(jdbc);
    }

    @Test
    void skips_blank_username() {
        provisioner.ensureUser(" ", null);

        verifyNoInteractions(jdbc);
    }

    @Test
    void db_failure_backs_off_for_five_minutes_then_retries() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenReturn(1);

        assertThatCode(() -> provisioner.ensureUser("flaky", null)).doesNotThrowAnyException();
        clock.advance(Duration.ofMinutes(4));
        provisioner.ensureUser("flaky", null);
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));

        clock.advance(Duration.ofMinutes(2));
        provisioner.ensureUser("flaky", null);
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));

        provisioner.ensureUser("flaky", null);
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void non_data_access_exception_does_not_propagate_and_backs_off() {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> provisioner.ensureUser("odd", null)).doesNotThrowAnyException();
        provisioner.ensureUser("odd", null);

        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-15T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
