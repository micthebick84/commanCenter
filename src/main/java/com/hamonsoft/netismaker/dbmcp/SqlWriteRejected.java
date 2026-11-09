package com.hamonsoft.netismaker.dbmcp;

/** 쓰기 SQL을 분석할 수 없거나 받지 않는 문법이다 — 메시지는 세션에 그대로 돌려줄 한국어 사유. */
public class SqlWriteRejected extends Exception {

    private static final long serialVersionUID = 1L;

    public SqlWriteRejected(String reason) {
        super(reason);
    }
}
