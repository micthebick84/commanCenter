package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** env 템플릿 정규화 — 워커 파싱 결과와 API 수신값 공용 (스펙 2026-10-07 §3.1). */
class EnvTemplateItemTest {

    @Test
    void null_list_and_null_items_become_empty() {
        assertThat(EnvTemplateItem.sanitize(null)).isEmpty();
        assertThat(EnvTemplateItem.sanitize(Arrays.asList(null, null))).isEmpty();
    }

    @Test
    void null_fields_default_to_empty_strings() {
        EnvTemplateItem item = new EnvTemplateItem(null, null, false, false);
        assertThat(item.key()).isEmpty();
        assertThat(item.description()).isEmpty();
    }

    @Test
    void keys_are_trimmed_and_invalid_names_dropped() {
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(List.of(
                new EnvTemplateItem("  JWT_SECRET ", "서명 키", true, true),
                new EnvTemplateItem("1BAD", "", false, false),
                new EnvTemplateItem("HAS-DASH", "", false, false),
                new EnvTemplateItem("HAS SPACE", "", false, false),
                new EnvTemplateItem("", "", false, false),
                new EnvTemplateItem("_private", "", false, false)));

        assertThat(out).extracting(EnvTemplateItem::key).containsExactly("JWT_SECRET", "_private");
        assertThat(out.get(0)).isEqualTo(new EnvTemplateItem("JWT_SECRET", "서명 키", true, true));
    }

    @Test
    void duplicate_keys_keep_the_first_occurrence() {
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(List.of(
                new EnvTemplateItem("DB_URL", "첫 번째", false, true),
                new EnvTemplateItem("DB_URL", "두 번째", true, false),
                new EnvTemplateItem("db_url", "대소문자 다름", false, false)));

        assertThat(out).extracting(EnvTemplateItem::key).containsExactly("DB_URL", "db_url");
        assertThat(out.get(0).description()).isEqualTo("첫 번째");
    }

    @Test
    void long_keys_and_descriptions_are_bounded() {
        String longKey = "K" + "x".repeat(128);   // 129자 — 상한(128) 초과
        String desc = "가".repeat(EnvTemplateItem.MAX_DESCRIPTION + 50);
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(List.of(
                new EnvTemplateItem(longKey, "", false, false),
                new EnvTemplateItem("OK", "  " + desc + "  ", false, false)));

        assertThat(out).extracting(EnvTemplateItem::key).containsExactly("OK");
        assertThat(out.get(0).description()).hasSize(EnvTemplateItem.MAX_DESCRIPTION);
    }

    @Test
    void at_most_fifty_items_are_kept() {
        List<EnvTemplateItem> many = new ArrayList<>();
        IntStream.range(0, EnvTemplateItem.MAX_ITEMS + 10)
                .forEach(i -> many.add(new EnvTemplateItem("VAR_" + i, "", false, false)));

        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(many);

        assertThat(out).hasSize(EnvTemplateItem.MAX_ITEMS);
        assertThat(out.get(EnvTemplateItem.MAX_ITEMS - 1).key()).isEqualTo("VAR_49");
    }

    @Test
    void sanitized_list_is_mutable_for_jpa() {
        List<EnvTemplateItem> out = EnvTemplateItem.sanitize(null);
        out.add(new EnvTemplateItem("A", "", false, false));   // Hibernate가 다룰 수 있게 가변 목록
        assertThat(out).hasSize(1);
    }
}
