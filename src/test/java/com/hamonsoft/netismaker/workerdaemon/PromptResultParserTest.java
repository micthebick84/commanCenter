package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * ★★★ DESIGN §11 load-bearing 파서. 모든 케이스 망라.
 */
class PromptResultParserTest {

    private final PromptResultParser parser = new PromptResultParser();

    private static final String VALID_OUTPUT = """
            ## 1. 요구사항 요약
            로그인 페이지가 깨져서 사용자가 접속을 못 합니다.

            ## 2. 영향 범위
            - 수정 예상 파일: `src/pages/login.vue`, `src/stores/auth.ts`
            - 추가 예상 파일: 없음
            - 영향 받는 모듈/패키지: nuxt_practice의 auth

            ## 3. 접근 방법
            세션 쿠키 만료 처리 분기 추가.

            ## 4. Subtask 분해
            - **세션 쿠키 만료 분기 추가**: undefined 반환 대신 /login 리다이렉트
              - 대상 파일: `src/stores/auth.ts`
              - 예상 LoC: 약 5줄
              - 위험도: L
              - 설명: 만료 검증 추가
            - **로그인 페이지 에러 표시**: 실패 시 빨간 메시지 노출
              - 대상 파일: `src/pages/login.vue`
              - 예상 LoC: 약 20줄
              - 위험도: M
              - 설명: 사용자 피드백 강화

            ## 5. 위험 요소 및 미해결 질문
            - 리다이렉트 시 원래 페이지 복귀 로직?
            """;

    @Test
    void valid_5_sections_parses_2_subtasks() {
        PromptResultParser.ParseResult r = parser.parse(VALID_OUTPUT);
        assertThat(r.markdown()).isEqualTo(VALID_OUTPUT);
        assertThat(r.subtasksJson()).contains("세션 쿠키 만료 분기 추가");
        assertThat(r.subtasksJson()).contains("로그인 페이지 에러 표시");
        assertThat(r.subtasksJson()).contains("\"risk\":\"L\"");
        assertThat(r.subtasksJson()).contains("\"risk\":\"M\"");
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void empty_input_throws() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(PromptResultParser.ParseException.class)
                .hasMessageContaining("비어있음");
    }

    @Test
    void missing_section_1_throws() {
        String missing1 = VALID_OUTPUT.replace("## 1. 요구사항 요약", "요구사항 요약");
        assertThatThrownBy(() -> parser.parse(missing1))
                .isInstanceOf(PromptResultParser.ParseException.class)
                .hasMessageContaining("## 1.");
    }

    @Test
    void missing_section_3_throws() {
        String missing3 = VALID_OUTPUT.replace("## 3. 접근 방법", "(섹션 없음)");
        assertThatThrownBy(() -> parser.parse(missing3))
                .isInstanceOf(PromptResultParser.ParseException.class)
                .hasMessageContaining("## 3.");
    }

    @Test
    void missing_section_4_warning_only_subtasks_empty() {
        String s = """
                ## 1. 요약
                ## 2. 범위
                ## 3. 접근
                ## 5. 위험
                """;
        PromptResultParser.ParseResult r = parser.parse(s);
        assertThat(r.subtasksJson()).isEqualTo("[]");
        assertThat(r.warnings()).anyMatch(w -> w.contains("## 4."));
    }

    @Test
    void missing_section_5_warning_only() {
        String s = """
                ## 1. 요약
                ## 2. 범위
                ## 3. 접근
                ## 4. Subtask 분해
                - **테스트**: 설명
                  - 위험도: L
                """;
        PromptResultParser.ParseResult r = parser.parse(s);
        assertThat(r.subtasksJson()).contains("테스트");
        assertThat(r.warnings()).anyMatch(w -> w.contains("## 5."));
    }

    @Test
    void korean_heading_variants_still_parse() {
        // 한국어 헤딩 텍스트가 변경돼도 번호만 보면 OK
        String s = """
                ## 1. (한글 변형)
                요약 내용

                ## 2. 영향 범위
                내용

                ## 3. 구현 방법
                내용

                ## 4. 세부 작업
                - **태스크 1**: 요약
                  - 위험도: H
                """;
        PromptResultParser.ParseResult r = parser.parse(s);
        assertThat(r.subtasksJson()).contains("태스크 1");
    }

    @Test
    void english_only_output_gets_korean_ratio_warning() {
        String englishOnly = """
                ## 1. Summary
                The login page is broken.
                ## 2. Impact
                src/pages/login.vue
                ## 3. Approach
                Add null check.
                ## 4. Subtasks
                - **Add null check**: in auth.ts
                  - 위험도: L
                ## 5. Risks
                None.
                """;
        PromptResultParser.ParseResult r = parser.parse(englishOnly);
        assertThat(r.warnings()).anyMatch(w -> w.contains("한국어 비율"));
    }

    @Test
    void section_4_no_subtasks_gives_warning() {
        String s = """
                ## 1. 요약
                ## 2. 범위
                ## 3. 접근
                ## 4. Subtask 분해
                (해당 없음)
                ## 5. 위험
                """;
        PromptResultParser.ParseResult r = parser.parse(s);
        assertThat(r.subtasksJson()).isEqualTo("[]");
        assertThat(r.warnings()).anyMatch(w -> w.contains("subtask 0개"));
    }
}
