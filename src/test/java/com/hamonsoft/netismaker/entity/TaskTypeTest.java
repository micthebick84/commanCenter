package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 작업 타입 enum (스펙 2026-11-03 §4.1·§4.2). */
class TaskTypeTest {

    @Test
    void 라벨과_앞머리() {
        assertThat(TaskType.NEW_FEATURE.label()).isEqualTo("신규 개발");
        assertThat(TaskType.FEATURE_CHANGE.label()).isEqualTo("기능 수정");
        assertThat(TaskType.BUG_FIX.label()).isEqualTo("버그 픽스");
        assertThat(TaskType.DB_WORK.label()).isEqualTo("DB 작업");
        assertThat(TaskType.NEW_FEATURE.commitPrefix()).isEqualTo("feat");
        assertThat(TaskType.FEATURE_CHANGE.commitPrefix()).isEqualTo("feat");
        assertThat(TaskType.BUG_FIX.commitPrefix()).isEqualTo("fix");
        assertThat(TaskType.DB_WORK.commitPrefix()).isEqualTo("db");
    }

    @Test
    void 타입없음() {
        assertThat(TaskType.labelOf(null)).isEqualTo("타입 없음");
        assertThat(TaskType.labelOf(TaskType.DB_WORK)).isEqualTo("DB 작업");
        // 타입이 없으면 커밋은 지금처럼 feat, PR 제목은 앞머리 없이 제목 그대로
        assertThat(TaskType.commitPrefixOf(null)).isEqualTo("feat");
        assertThat(TaskType.commitPrefixOf(TaskType.DB_WORK)).isEqualTo("db");
        assertThat(TaskType.prTitle(null, "로그인")).isEqualTo("로그인");
        assertThat(TaskType.prTitle(TaskType.BUG_FIX, "로그인")).isEqualTo("fix: 로그인");
        assertThat(TaskType.prTitle(TaskType.NEW_FEATURE, "로그인")).isEqualTo("feat: 로그인");
        assertThat(TaskType.prTitle(TaskType.DB_WORK, "로그인")).isEqualTo("db: 로그인");
    }

    @Test
    void 요청값_파싱() {
        // 없음·공백은 null(= 타입 없음 또는 유지), 앞뒤 공백은 자르고, 대소문자는 구분한다
        assertThat(TaskType.parseRequest(null)).isNull();
        assertThat(TaskType.parseRequest("  ")).isNull();
        assertThat(TaskType.parseRequest(" DB_WORK ")).isEqualTo(TaskType.DB_WORK);
        assertThat(TaskType.parseRequest("NEW_FEATURE")).isEqualTo(TaskType.NEW_FEATURE);
        assertThatThrownBy(() -> TaskType.parseRequest("db_work"))
                .hasMessageContaining("알 수 없는 작업 타입: db_work");
        assertThatThrownBy(() -> TaskType.parseRequest("QA_WORK"))
                .hasMessageContaining("알 수 없는 작업 타입: QA_WORK");
    }

    @Test
    void 요청값이_틀리면_400() {
        assertThatThrownBy(() -> TaskType.parseRequest("QA_WORK"))
                .isInstanceOfSatisfying(com.hamonsoft.netismaker.service.TaskException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    @Test
    void 워커와_인터뷰_쪽은_모르는_값을_타입없음으로_읽는다() {
        assertThat(TaskType.fromWireOrNull(null)).isNull();
        assertThat(TaskType.fromWireOrNull("")).isNull();
        assertThat(TaskType.fromWireOrNull("QA_WORK")).isNull();
        assertThat(TaskType.fromWireOrNull("bug_fix")).isNull();
        assertThat(TaskType.fromWireOrNull("BUG_FIX")).isEqualTo(TaskType.BUG_FIX);
        assertThat(TaskType.fromWireOrNull(" DB_WORK ")).isEqualTo(TaskType.DB_WORK);
    }
}
