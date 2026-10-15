package com.hamonsoft.netismaker.dbmcp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 인터뷰 서비스 db/result.ts와 같은 JSON 모양·잘림 규칙 (스펙 2026-10-14 §6.3). */
class ResultFormatterTest {

    @Test
    void renders_same_json_shape_as_ts() {
        QueryResult r = new QueryResult(List.of("id", "name"), List.of(List.of(1, "a")), false);
        assertThat(ResultFormatter.format(r))
                .isEqualTo("{\"columns\":[\"id\",\"name\"],\"rows\":[[1,\"a\"]],\"rowCount\":1,\"truncated\":false}");
    }

    @Test
    void nulls_and_big_numbers() {
        QueryResult r = new QueryResult(List.of("a", "b", "c"),
                List.of(Arrays.asList(null, 9007199254740993L, new BigDecimal("1.50"))), false);
        assertThat(ResultFormatter.format(r)).contains("[[null,\"9007199254740993\",\"1.50\"]]");
    }

    @Test
    void clips_long_cells() {
        String big = "x".repeat(DbMcpLimits.MAX_CELL_CHARS + 5);
        QueryResult r = new QueryResult(List.of("t"), List.of(List.of(big)), false);
        assertThat(ResultFormatter.format(r)).contains("…(+5자)");
    }

    @Test
    void pre_clipped_text_passes_through() {
        QueryResult r = new QueryResult(List.of("t"), List.of(List.of(new QueryResult.PreClipped("ab…(+9자)"))), false);
        assertThat(ResultFormatter.format(r)).contains("\"ab…(+9자)\"");
    }

    @Test
    void row_cap_marks_truncated() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(List.of(i));
        String text = ResultFormatter.format(new QueryResult(List.of("i"), rows, false), 3, DbMcpLimits.MAX_RESULT_CHARS);
        assertThat(text).contains("\"rowCount\":3").contains("\"truncated\":true");
    }

    @Test
    void char_cap_drops_rows_until_it_fits() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(List.of("y".repeat(100)));
        String text = ResultFormatter.format(new QueryResult(List.of("v"), rows, false), 200, 1000);
        assertThat(text.length()).isLessThanOrEqualTo(1000);
        assertThat(text).contains("\"truncated\":true");
    }
}
