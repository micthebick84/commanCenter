package com.hamonsoft.netismaker.entity;

/**
 * 쓰기 SQL 한 건의 처리 결과 (스펙 2026-11-03 §6.2·§6.5). 값 이름은 task_db_write_log.classification CHECK와 같다 — 바꾸지 말 것.
 *
 * EXECUTED  바로 실행했다(관리자가 승인 대기를 실행한 것도 이 값으로 바뀐다)
 * DEFERRED  승인 대기 — 실행하지 않고 기록만 했다. 관리자가 나중에 실행하거나 실행 안 함으로 정한다
 * DISMISSED 관리자가 "실행 안 함"으로 정리했다
 * REJECTED  규칙으로 거부했다(관리자도 실행할 수 없다)
 * FAILED    실행했지만 DB가 오류를 돌려줬다
 */
public enum WriteClassification {
    EXECUTED, DEFERRED, DISMISSED, REJECTED, FAILED
}
