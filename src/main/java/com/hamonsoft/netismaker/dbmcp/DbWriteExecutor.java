package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;

import java.sql.SQLException;

/**
 * 구현 단계 쓰기 SQL을 실행할 연결을 연다 (스펙 2026-11-03 §6.4). 호출마다 새 연결 — 읽기 실행기와 같은 구조.
 * 분류·판정은 호출자({@link DbWriteService})가 한다. ⚠️ ref에 평문 비밀번호.
 */
public interface DbWriteExecutor {

    DbWriteSession open(DbConnectionRef ref) throws SQLException;
}
