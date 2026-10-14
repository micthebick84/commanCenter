package com.hamonsoft.netismaker.dbmcp;

import java.util.List;

/** 실행 결과. rows는 상한까지만, 넘쳤으면 truncated. 셀은 JDBC 값 또는 PreClipped. */
public record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {
    /** 실행기가 이미 잘라 둔 텍스트(CLOB 등) — 포매터가 다시 자르지 않는다. */
    public record PreClipped(String text) {}
}
