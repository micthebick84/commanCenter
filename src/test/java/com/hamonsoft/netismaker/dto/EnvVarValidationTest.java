package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeployRequest/EnvVar 빈 검증 — key/value 길이 제한이 @Valid 중첩으로 적용되는지 단위 검증.
 */
class EnvVarValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void valid_env_passes() {
        DeployRequest req = new DeployRequest(List.of(
                new EnvVar("SPRING_DATASOURCE_URL", "jdbc:postgresql://host.docker.internal:5432/netis", false)));
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void overlong_key_violates() {
        String longKey = "K".repeat(201);
        DeployRequest req = new DeployRequest(List.of(new EnvVar(longKey, "v", false)));
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void overlong_value_violates() {
        String longVal = "v".repeat(4001);
        DeployRequest req = new DeployRequest(List.of(new EnvVar("K", longVal, true)));
        assertThat(validator.validate(req)).isNotEmpty();
    }
}
