package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse.DbConnectionRef;

import java.sql.SQLException;
import java.util.List;

/** 읽기 전용으로 한 문장을 실행한다. 호출자가 SqlReadOnlyChecker를 먼저 통과시킨다. ⚠️ ref에 평문 비밀번호. */
public interface ReadOnlyQueryExecutor {
    QueryResult run(DbConnectionRef ref, String sql, List<String> params, int maxRows) throws SQLException;
}
