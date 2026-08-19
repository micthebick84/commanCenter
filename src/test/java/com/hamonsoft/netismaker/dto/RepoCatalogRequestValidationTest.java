package com.hamonsoft.netismaker.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepoCatalogRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void init() { factory = Validation.buildDefaultValidatorFactory(); validator = factory.getValidator(); }
    @AfterAll static void close() { factory.close(); }

    @Test
    void task_create_requires_repo_catalog_id() {
        TaskCreateRequest req = new TaskCreateRequest(null, "main", "t", "d");
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void task_create_valid_with_repo_catalog_id() {
        TaskCreateRequest req = new TaskCreateRequest(7L, "main", "t", "d");
        assertThat(validator.validate(req)).isEmpty();
    }

}
