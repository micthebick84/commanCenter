package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbConnectionDtoValidationTest {

    private final Validator v = Validation.buildDefaultValidatorFactory().getValidator();

    private static DbConnectionDto.UpsertRequest req(String host, String db, String user) {
        return new DbConnectionDto.UpsertRequest(DbConnectionScope.USER, 1L, "운영", DbType.MYSQL, host, 3306, db, user, "pw", null);
    }

    @Test
    void accepts_ordinary_values() {
        assertThat(v.validate(req("db-01.corp.local", "app_db", "reader@corp"))).isEmpty();
        assertThat(v.validate(req("10.1.3.2", "ORCLPDB1.world", "C##READER"))).isEmpty();
    }

    @Test
    void rejects_jdbc_url_parameter_injection() {
        // Review Focus 2
        for (String[] bad : new String[][] {
                {"db.local", "app?allowLoadLocalInfile=true", "u"},
                {"db.local/evil", "app", "u"},
                {"db.local:1", "app", "u"},
                {"db.local", "app&x=1", "u"},
                {"db.local", "app", "u;drop"},
                {"db local", "app", "u"},
        }) {
            assertThat(v.validate(req(bad[0], bad[1], bad[2]))).as(String.join(" | ", bad)).isNotEmpty();
        }
        assertThat(v.validate(new DbConnectionDto.TestRequest(null, DbType.MYSQL, "h/evil", 3306, "app", "u", "pw"))).isNotEmpty();
    }

    @Test
    void to_string_and_json_never_carry_password() throws Exception {
        assertThat(req("h", "app", "u").toString()).doesNotContain("pw");
        assertThat(new DbConnectionDto.TestRequest(1L, null, null, null, null, null, "s3cret").toString()).doesNotContain("s3cret");

        DbConnection c = DbConnection.create(DbConnectionScope.USER, 1L, "user1", "운영", DbType.POSTGRESQL, "h", 5432,
                "app", "u", "CIPHER-xyz", "user1");
        ReflectionTestUtils.setField(c, "id", 7L);
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(new DbConnectionDto.ListResponse(true, List.of(DbConnectionDto.View.of(c, "user1"))));
        assertThat(json).doesNotContain("password").doesNotContain("CIPHER").contains("\"mine\":true");
    }
}
