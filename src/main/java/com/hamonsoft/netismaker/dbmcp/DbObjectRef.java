package com.hamonsoft.netismaker.dbmcp;

/**
 * 쓰기 SQL에 적힌 객체 이름 그대로 — 따옴표 여부를 남겨 방언 규칙으로 정규화한다({@link DbObjectName#normalize}).
 * schema가 null이면 생략된 것이다. 인용 이름의 text는 겹따옴표를 하나로 푼 값이다.
 */
public record DbObjectRef(String schema, boolean schemaQuoted, String name, boolean nameQuoted) {
}
