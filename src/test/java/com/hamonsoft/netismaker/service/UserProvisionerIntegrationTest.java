package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** com."user" 자동 생성 SQL — 실제 PostgreSQL(init-test-schema.sql의 com."user"). */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class UserProvisionerIntegrationTest {

    @Autowired UserProvisioner provisioner;
    @Autowired JdbcTemplate jdbc;

    @Test
    void creates_missing_user_row() {
        provisioner.ensureUser("jit_new1", "jit_new1@hamonsoft.co.kr");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_name, password, email FROM com.\"user\" WHERE user_id = 'jit_new1'");
        assertThat(row.get("user_name")).isEqualTo("jit_new1");
        assertThat(row.get("password")).isEqualTo(UserProvisioner.NO_LOGIN_PASSWORD);
        assertThat(row.get("email")).isEqualTo("jit_new1@hamonsoft.co.kr");
    }

    @Test
    void keeps_existing_row_untouched() {
        provisioner.ensureUser("user1", "changed@example.com");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_name, password, email FROM com.\"user\" WHERE user_id = 'user1'");
        assertThat(row.get("user_name")).isEqualTo("User 1");
        assertThat(row.get("password")).isEqualTo("x");
        assertThat(row.get("email")).isEqualTo("user1@hamonsoft.co.kr");
    }
}
