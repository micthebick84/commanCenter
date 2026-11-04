package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbObjectType;

import java.sql.SQLException;

/**
 * 쓰기 연결 하나 — autocommit·readOnly를 끈 트랜잭션 (스펙 2026-11-03 §6.4).
 * {@link #close()}는 커밋하지 않은 변경이 있으면 rollback을 close보다 먼저 한다(Oracle JDBC는 autocommit 꺼진 연결을 close할 때 커밋한다).
 */
public interface DbWriteSession extends AutoCloseable {

    /** 이 연결의 기본 스키마 — DB가 돌려준 표기 그대로(PG current_schema(), MySQL DATABASE(), Oracle CURRENT_SCHEMA). 모르면 null. */
    String defaultSchema() throws SQLException;

    /** MySQL·MariaDB의 lower_case_table_names가 0이 아닌지. 다른 방언은 늘 false. */
    boolean lowerCaseNames() throws SQLException;

    /** 그 스키마에 그 이름이 쓰였는지(종류 무관) — CREATE 전에 "새로 생기는지" 확인한다. 이름은 정규화한 값이다. */
    boolean exists(DbObjectName name) throws SQLException;

    /** 그 스키마에 그 종류의 객체가 있는지 — 소유 기록이 가리키는 객체가 실제로 있는지 확인한다(Oracle 동의어는 세지 않는다). */
    boolean exists(DbObjectName name, DbObjectType type) throws SQLException;

    /**
     * DB가 그 스키마를 시스템(관리) 스키마로 표시하는지 — Oracle {@code all_users.oracle_maintained = 'Y'}(12c부터).
     * 다른 방언은 이름 규칙({@link WriteTargetScope})으로 충분해 조회하지 않고 늘 false. 스키마는 정규화한 값이다.
     */
    boolean systemSchema(String schema) throws SQLException;

    /** 한 문장 실행 — 문장 제한시간 {@value DbMcpLimits#STATEMENT_TIMEOUT_SECONDS}초. 반환 = 영향 행 수(DDL은 0). */
    long executeUpdate(String sql) throws SQLException;

    void commit() throws SQLException;

    /** 커밋하지 않은 변경은 롤백하고 닫는다. 실패는 로그만 남긴다 — 예외를 던지지 않는다. */
    @Override
    void close();
}
