package com.hamonsoft.netismaker.dbmcp;

/** 분석기가 받는 쓰기 문장 종류 — 이 밖의 문장은 모두 거부한다 (스펙 2026-11-03 §6.2). */
public enum WriteKind {
    CREATE_TABLE, CREATE_INDEX, CREATE_UNIQUE_INDEX, CREATE_SEQUENCE, CREATE_VIEW, COMMENT_ON,
    ALTER_TABLE, RENAME_TABLE, DROP_TABLE, DROP_VIEW, DROP_SEQUENCE, TRUNCATE,
    INSERT, UPDATE, DELETE,
    /** 본문을 분석하지 않는다 — 늘 승인 대기. */
    CREATE_TRIGGER,
    /** PROCEDURE·FUNCTION·PACKAGE — 본문을 분석하지 않는다, 늘 승인 대기. */
    CREATE_ROUTINE
}
