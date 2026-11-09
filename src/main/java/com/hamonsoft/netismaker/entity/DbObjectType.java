package com.hamonsoft.netismaker.entity;

/**
 * 구현 단계 DB 쓰기가 "그 작업이 만든 것"으로 기록하는 객체 종류 (스펙 2026-11-03 §6.3).
 * 값 이름은 task_db_object.object_type CHECK와 같다 — 바꾸지 말 것.
 */
public enum DbObjectType {
    TABLE, VIEW, SEQUENCE
}
