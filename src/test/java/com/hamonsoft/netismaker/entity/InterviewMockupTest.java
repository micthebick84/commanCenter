package com.hamonsoft.netismaker.entity;

import com.hamonsoft.netismaker.dto.InterviewMockupView;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 인터뷰 목업 값 규칙 (스펙 2026-11-03 §5) — 크기는 UTF-8 바이트, 처음에는 어느 턴에도 안 묶인 버리지 않은 행, View는 html을 로그에 싣지 않는다. */
class InterviewMockupTest {

    @Test
    void 크기는_UTF8_바이트() {
        // "가"는 UTF-8로 3바이트 — 글자 수(10)가 아니다. 상한 판정이 바이트 기준이어야 하기 때문
        assertThat(InterviewMockup.create(5L, 1, "a", 1, "t", null, "가".repeat(10)).getSizeBytes()).isEqualTo(30);
    }

    @Test
    void 만들면_묶이지_않은_버리지_않은_행이다() {
        InterviewMockup m = InterviewMockup.create(5L, 3, "list-filter-a", 2, "A안", "필터가 위에 있는 안", "<p>x</p>");

        assertThat(m.getSessionId()).isEqualTo(5L);
        assertThat(m.getN()).isEqualTo(3);
        assertThat(m.getName()).isEqualTo("list-filter-a");
        assertThat(m.getVersion()).isEqualTo(2);
        assertThat(m.getTitle()).isEqualTo("A안");
        assertThat(m.getDescription()).isEqualTo("필터가 위에 있는 안");
        assertThat(m.getHtml()).isEqualTo("<p>x</p>");
        assertThat(m.getSizeBytes()).isEqualTo(8);
        assertThat(m.getTurnSeq()).as("질문·계획 턴으로 끝나기 전에는 묶이지 않는다").isNull();
        assertThat(m.getTurnKind()).isNull();
        assertThat(m.isDiscarded()).isFalse();
        assertThat(m.getCreatedAt()).isNotNull();
    }

    @Test
    void View_toString에_html이_없다() {
        InterviewMockupView v = new InterviewMockupView(1L, 5L, 9L, 1, "a", 1, "t", null, null, null,
                OffsetDateTime.now(), "<p>SECRET</p>");

        assertThat(v.toString()).doesNotContain("SECRET").contains("htmlLength=13");
    }

    @Test
    void View_toString은_html이_null이어도_안전하다() {
        InterviewMockupView v = new InterviewMockupView(1L, 5L, 9L, 1, "a", 1, "t", null, null, null,
                OffsetDateTime.now(), null);

        assertThat(v.toString()).contains("htmlLength=0");
    }
}
