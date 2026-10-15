package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private final UserProvisioner provisioner = new UserProvisioner(jdbc);

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
    void db_failure_does_not_throw_and_retries_next_time() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("down"))
                .thenReturn(1);

        assertThatCode(() -> provisioner.ensureUser("flaky", null)).doesNotThrowAnyException();
        provisioner.ensureUser("flaky", null);

        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }
}
