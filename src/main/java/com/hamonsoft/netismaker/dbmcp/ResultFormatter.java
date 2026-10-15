package com.hamonsoft.netismaker.dbmcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 도구 결과 텍스트 — 인터뷰 서비스 db/result.ts의 Java 이식. 형식이 같아야 단계마다 에이전트가 같은 모양을 본다. */
public final class ResultFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResultFormatter() {}

    static String clip(String s) {
        int max = DbMcpLimits.MAX_CELL_CHARS;
        return s.length() > max ? s.substring(0, max) + "…(+" + (s.length() - max) + "자)" : s;
    }

    /** JSON에 안전한 셀 값. 큰 정수·소수는 정밀도 보존을 위해 문자열(TS의 bigint 처리와 같은 이유). */
    static Object toCell(Object v) {
        if (v == null) return null;
        if (v instanceof QueryResult.PreClipped p) return p.text();
        if (v instanceof String s) return clip(s);
        if (v instanceof Boolean || v instanceof Integer || v instanceof Short || v instanceof Byte
                || v instanceof Double || v instanceof Float) return v;
        if (v instanceof Long || v instanceof BigInteger || v instanceof BigDecimal) return v.toString();
        if (v instanceof Timestamp ts) return ts.toInstant().toString();
        if (v instanceof byte[] b) return "<binary " + b.length + " bytes>";
        return clip(String.valueOf(v));
    }

    public static String format(QueryResult r) {
        return format(r, DbMcpLimits.MAX_ROWS, DbMcpLimits.MAX_RESULT_CHARS);
    }

    public static String format(QueryResult r, int maxRows, int maxChars) {
        boolean truncated = r.truncated() || r.rows().size() > maxRows;
        List<List<Object>> rows = r.rows().subList(0, Math.min(maxRows, r.rows().size())).stream()
                .map(row -> row.stream().map(ResultFormatter::toCell).toList())
                .toList();
        String text = render(r.columns(), rows, truncated);
        while (text.length() > maxChars && !rows.isEmpty()) {
            int keep = (int) Math.floor(rows.size() * ((double) maxChars / text.length()));
            rows = rows.subList(0, Math.min(rows.size() - 1, Math.max(0, keep)));
            truncated = true;
            text = render(r.columns(), rows, truncated);
        }
        return text;
    }

    private static String render(List<String> columns, List<List<Object>> rows, boolean truncated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", columns);
        m.put("rows", rows);
        m.put("rowCount", rows.size());
        m.put("truncated", truncated);
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("결과 직렬화 실패", e);
        }
    }
}
