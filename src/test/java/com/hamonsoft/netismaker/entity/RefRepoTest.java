package com.hamonsoft.netismaker.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 질문 세션 참조 repo 값 타입 — 고정 커밋·실패 사유 갱신 규칙과 JSONB 모양 (스펙 2026-10-22 §3.2, §4.5). */
class RefRepoTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    private static RefRepo fresh() {
        return new RefRepo(12L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5", null, null, null);
    }

    @Test
    void pinned_sets_sha_once_and_clears_error() {
        RefRepo r = new RefRepo(12L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5", null, null, "권한 없음");
        RefRepo p = r.pinned("a".repeat(40), "2026-10-22T10:00+09:00");
        assertThat(p.sha()).isEqualTo("a".repeat(40));
        assertThat(p.error()).isNull();
        assertThat(p.pinned("b".repeat(40), "later")).isSameAs(p);
    }

    @Test
    void pinned_records_the_time_and_changes_nothing_else() {
        RefRepo p = fresh().pinned(SHA_A, "2026-10-22T10:00+09:00");
        assertThat(p).isEqualTo(new RefRepo(12L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5",
                SHA_A, "2026-10-22T10:00+09:00", null));
    }

    @Test
    void pinned_clears_the_error_of_an_already_pinned_ref_without_moving_the_pin() {
        // 성공 보고는 이미 고정된 참조의 낡은 오류도 지운다 (스펙 §4.5). 고정 커밋·시각은 그대로.
        RefRepo r = new RefRepo(12L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5",
                SHA_A, "2026-10-22T10:00+09:00", "일시적인 네트워크 오류");
        RefRepo p = r.pinned(SHA_B, "later");
        assertThat(p).isNotSameAs(r);
        assertThat(p.sha()).isEqualTo(SHA_A);
        assertThat(p.fetchedAt()).isEqualTo("2026-10-22T10:00+09:00");
        assertThat(p.error()).isNull();
        assertThat(p).isEqualTo(new RefRepo(12L, "옛앱", "g/old", "https://x/g/old.git", "release/6.5",
                SHA_A, "2026-10-22T10:00+09:00", null));
        assertThat(p.pinned(SHA_B, "even later")).isSameAs(p);   // 이제 고정 + 오류 없음 → 그대로
    }

    @Test
    void with_error_records_the_reason_and_keeps_the_pin() {
        RefRepo pinned = fresh().pinned(SHA_A, "2026-10-22T10:00+09:00");
        RefRepo failed = pinned.withError("브랜치를 찾을 수 없습니다");
        assertThat(failed.error()).isEqualTo("브랜치를 찾을 수 없습니다");
        assertThat(failed.sha()).isEqualTo(SHA_A);
        assertThat(failed.fetchedAt()).isEqualTo("2026-10-22T10:00+09:00");
        assertThat(pinned.error()).isNull();   // 원본은 불변
    }

    @Test
    void matches_by_catalog_and_branch_and_blank_branch_matches_any() {
        RefRepo r = new RefRepo(12L, "옛앱", "g/old", "u", "main", null, null, null);
        assertThat(r.matches(12L, "main")).isTrue();
        assertThat(r.matches(12L, "dev")).isFalse();
        assertThat(r.matches(12L, " ")).isTrue();
        assertThat(r.matches(13L, "main")).isFalse();
    }

    @Test
    void matches_treats_a_null_branch_as_blank_and_compares_branch_case_sensitively() {
        RefRepo r = new RefRepo(12L, "옛앱", "g/old", "u", "main", null, null, null);
        assertThat(r.matches(12L, null)).isTrue();
        assertThat(r.matches(13L, null)).isFalse();
        assertThat(r.matches(12L, "Main")).isFalse();   // git 브랜치 이름은 대소문자를 구분한다
    }

    @Test
    void label_is_alias_and_branch() {
        assertThat(fresh().label()).isEqualTo("옛앱(release/6.5)");
    }

    @Test
    void json_omits_unset_fields_and_round_trips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode unset = mapper.readTree(mapper.writeValueAsString(fresh()));
        assertThat(unset.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("catalogId", "alias", "path", "gitUrl", "branch");

        RefRepo pinned = fresh().pinned(SHA_A, "2026-10-22T10:00+09:00");
        JsonNode set = mapper.readTree(mapper.writeValueAsString(pinned));
        assertThat(set.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("catalogId", "alias", "path", "gitUrl", "branch", "sha", "fetchedAt");
        assertThat(mapper.readValue(mapper.writeValueAsString(pinned), RefRepo.class)).isEqualTo(pinned);

        RefRepo failed = fresh().withError("권한 없음");
        assertThat(mapper.readValue(mapper.writeValueAsString(failed), RefRepo.class)).isEqualTo(failed);
    }
}
