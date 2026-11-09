package com.hamonsoft.netismaker.dbmcp;

/** ALTER TABLE의 동작 하나 — 최상위 쉼표(Oracle 괄호 목록은 항목)마다 하나씩 나온다 (스펙 2026-11-03 §6.2). */
public enum AlterAction {
    /** NULL 허용이거나 DEFAULT가 있는 컬럼 추가 — 남의 테이블에도 바로 실행. */
    ADD_COLUMN,
    ADD_COLUMN_NOT_NULL_NO_DEFAULT,
    /** 인라인 UNIQUE·REFERENCES·CHECK·PRIMARY KEY·CONSTRAINT가 붙은 컬럼 추가. */
    ADD_COLUMN_WITH_CONSTRAINT,
    DROP_COLUMN,
    RENAME_COLUMN,
    /** 타입·NULL 여부·기본값 변경(PG ALTER [COLUMN], MySQL MODIFY·CHANGE·ALTER, Oracle MODIFY). */
    ALTER_COLUMN,
    ADD_CONSTRAINT,
    /** 제약·인덱스·키 삭제. */
    DROP_CONSTRAINT,
    RENAME_TABLE
}
