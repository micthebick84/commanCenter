package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Source;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 배포 다이얼로그 미리 채움 규칙 (스펙 2026-10-07 §6.3). */
class DeployEnvSuggesterTest {

    private static EnvVar env(String key, String value, boolean secret) {
        return new EnvVar(key, value, secret);
    }

    private static EnvTemplateItem tpl(String key, String description, boolean secret, boolean required) {
        return new EnvTemplateItem(key, description, secret, required);
    }

    @Test
    void first_deploy_fills_template_keys_with_previous_values_and_appends_previous_only_keys() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(),
                List.of(tpl("DB_URL", "DB 접속 URL", false, true), tpl("JWT_SECRET", "서명 키", true, true)),
                List.of(env("DB_URL", "jdbc:postgresql://db/app", false), env("DB_PASSWORD", "pw", true)),
                41L);

        assertThat(r.rows()).containsExactly(
                new Row("DB_URL", "jdbc:postgresql://db/app", false, "DB 접속 URL", true, Source.TEMPLATE, true),
                new Row("JWT_SECRET", "", true, "서명 키", true, Source.TEMPLATE, false),
                new Row("DB_PASSWORD", "pw", true, "", false, Source.PREVIOUS, true));
        assertThat(r.templateCount()).isEqualTo(2);
        assertThat(r.previousTaskId()).isEqualTo(41L);
    }

    @Test
    void saved_env_wins_and_previous_is_ignored() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(env("DB_URL", "jdbc:saved", false), env("EXTRA", "x", false)),
                List.of(tpl("DB_URL", "DB 접속 URL", true, true), tpl("JWT_SECRET", "서명 키", true, true)),
                List.of(env("DB_URL", "jdbc:prev", false)),
                41L);

        assertThat(r.rows()).containsExactly(
                new Row("DB_URL", "jdbc:saved", true, "DB 접속 URL", true, Source.SAVED, false),   // 비밀은 OR
                new Row("EXTRA", "x", false, "", false, Source.SAVED, false),
                new Row("JWT_SECRET", "", true, "서명 키", true, Source.TEMPLATE, false));
        assertThat(r.templateCount()).isEqualTo(2);
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void secret_flag_is_ored_between_template_and_previous() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(), List.of(tpl("API_KEY", "", false, false)), List.of(env("API_KEY", "k", true)), 9L);

        assertThat(r.rows().get(0).secret()).isTrue();
    }

    @Test
    void blank_keys_are_ignored_everywhere_and_keys_are_trimmed() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(env("  ", "x", false)),     // 빈 KEY뿐 → 저장 env 없음으로 본다
                List.of(),
                List.of(env(" DB_URL ", "u", false), env("", "y", false)),
                5L);

        assertThat(r.rows()).containsExactly(new Row("DB_URL", "u", false, "", false, Source.PREVIOUS, true));
        assertThat(r.previousTaskId()).isEqualTo(5L);
    }

    @Test
    void previous_entries_with_empty_values_give_the_name_but_not_a_value() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(), List.of(tpl("DB_URL", "", false, true)),
                List.of(env("DB_URL", "", false), env("OLD_ONLY", "", false)), 7L);

        assertThat(r.rows()).containsExactly(
                new Row("DB_URL", "", false, "", true, Source.TEMPLATE, false),
                new Row("OLD_ONLY", "", false, "", false, Source.PREVIOUS, false));
        assertThat(r.previousTaskId()).isEqualTo(7L);   // KEY를 가져왔으므로
    }

    @Test
    void template_without_previous_has_no_previous_task_id() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(), List.of(tpl("A", "", false, false)), List.of(), 3L);

        assertThat(r.rows()).containsExactly(new Row("A", "", false, "", false, Source.TEMPLATE, false));
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void nothing_to_suggest_yields_no_rows() {
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(null, null, null, null);

        assertThat(r.rows()).isEmpty();
        assertThat(r.templateCount()).isZero();
        assertThat(r.previousTaskId()).isNull();
    }
}
