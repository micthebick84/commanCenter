package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.hamonsoft.netismaker.util.PatchFileNames.zipName;
import static org.assertj.core.api.Assertions.assertThat;

class PatchFileNamesTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 10);

    @Test
    void 공백은_밑줄_하나로() {
        assertThat(zipName(D, "로그인 오류 수정", 7)).isEqualTo("20261110_로그인_오류_수정.zip");
    }

    @Test
    void 파일_이름에_못_쓰는_문자는_지운다() {
        assertThat(zipName(D, "a/b:c*?\"<>|\\d", 7)).isEqualTo("20261110_abcd.zip");
    }

    @Test
    void 비었거나_null이면_task_id로_대체() {
        assertThat(zipName(D, "   ", 7)).isEqualTo("20261110_task-7.zip");
        assertThat(zipName(D, null, 7)).isEqualTo("20261110_task-7.zip");
    }

    @Test
    void 코드포인트_60개까지만() {
        assertThat(zipName(D, "가".repeat(100), 7)).isEqualTo("20261110_" + "가".repeat(60) + ".zip");
        String emoji = "😀".repeat(70);   // 서로게이트 쌍도 한 글자로 센다
        assertThat(zipName(D, emoji, 7)).isEqualTo("20261110_" + "😀".repeat(60) + ".zip");
    }

    @Test
    void 제어_문자는_제거() {
        assertThat(zipName(D, "x\ty\n", 7)).isEqualTo("20261110_xy.zip");
    }

    @Test
    void 점만_남으면_대체() {
        assertThat(zipName(D, "..", 7)).isEqualTo("20261110_task-7.zip");
    }
}
