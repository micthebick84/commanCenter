package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 종료 노트 폴더 값 규칙 (스펙 2026-10-28 close-note-folder §3-1). */
class NoteFolderPathTest {

    @Test
    void 앞뒤_공백과_끝_구분자를_뺀다() {
        assertThat(NoteFolderPath.normalize("  HamonSoft/동부병원/ ")).isEqualTo("HamonSoft/동부병원");
    }

    @Test
    void 역슬래시는_슬래시로_바꾼다() {
        assertThat(NoteFolderPath.normalize("HamonSoft\\Netis-플랫폼공통\\netisweb_v6.8"))
                .isEqualTo("HamonSoft/Netis-플랫폼공통/netisweb_v6.8");
    }

    @Test
    void 공백_폴더_이름은_그대로_둔다() {
        assertThat(NoteFolderPath.normalize("HamonSoft/경기도교육청 노후망")).isEqualTo("HamonSoft/경기도교육청 노후망");
    }

    @Test
    void 비면_null() {
        assertThat(NoteFolderPath.normalize(null)).isNull();
        assertThat(NoteFolderPath.normalize("   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "a/../b", "a//b", "/a", "C:/a", "a/./b", ".", "HamonSoft/a:b", "a/b?", "a/b*", "a/<b>", "a/b|c", "a/\"b\"", "HamonSoft /동부병원", "HamonSoft/ 동부병원", "HamonSoft./x", "a/b\u0007c"})
    void 볼트_밖이나_이상한_경로는_거부한다(String raw) {
        assertThatThrownBy(() -> NoteFolderPath.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("노트 폴더는 볼트 안 상대 경로여야 합니다");
    }

    @Test
    void 오백자를_넘으면_거부한다() {
        assertThatThrownBy(() -> NoteFolderPath.normalize("x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("노트 폴더가 너무 깁니다(500자)");
    }
}
