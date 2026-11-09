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

    private static RepoCatalogDto.UpsertRequest upsert(String dbScriptDir) {
        return new RepoCatalogDto.UpsertRequest("별칭", "owner/repo", null, null, true, null, null,
                null, null, null, null, null, true, dbScriptDir);
    }

    @Test
    void upsert_db_script_dir_up_to_255_chars() {
        assertThat(validator.validate(upsert("a".repeat(255)))).isEmpty();
        assertThat(validator.validate(upsert("a".repeat(256))))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("dbScriptDir");
    }

    @Test
    void upsert_compat_constructors_leave_db_write_fields_null() {
        // 활성 토글(5칸)·노트 폴더 도입 전(11칸)·도입 후(12칸) 호출부는 두 칸을 null(=유지)로 보낸다.
        RepoCatalogDto.UpsertRequest five = new RepoCatalogDto.UpsertRequest("별칭", "owner/repo", null, null, true);
        RepoCatalogDto.UpsertRequest eleven = new RepoCatalogDto.UpsertRequest("별칭", "owner/repo", null, null, true,
                null, null, null, null, null, null);
        RepoCatalogDto.UpsertRequest twelve = new RepoCatalogDto.UpsertRequest("별칭", "owner/repo", null, null, true,
                null, null, null, null, null, null, "HamonSoft/x");
        for (RepoCatalogDto.UpsertRequest r : new RepoCatalogDto.UpsertRequest[]{five, eleven, twelve}) {
            assertThat(r.dbMigrationManaged()).isNull();
            assertThat(r.dbScriptDir()).isNull();
            assertThat(validator.validate(r)).isEmpty();
        }
        assertThat(twelve.noteFolder()).isEqualTo("HamonSoft/x");
    }

}
