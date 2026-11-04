package com.hamonsoft.netismaker.dbmcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 구현 단계 개발 DB 쓰기 설정 (스펙 2026-11-03 §6.1·§6.2). yml의 app.db-write.*
 *  enabled              전역 스위치(§6.1 조건 1). 기본 꺼짐 — 배포한 뒤 DB_WRITE_ENABLED=true로 켠다
 *  maxUpdateDeleteRows  남의 테이블 UPDATE/DELETE 한 문장의 영향 행 상한
 *  maxInsertRows        남의 테이블 INSERT(INSERT…SELECT 포함) 한 문장의 영향 행 상한
 * 상한은 남의 테이블에만 건다(그 작업이 만든 테이블에는 없다). 문장 제한시간 30초는 DbMcpLimits.STATEMENT_TIMEOUT_SECONDS.
 */
@ConfigurationProperties("app.db-write")
public record DbWriteProperties(
        boolean enabled,
        @DefaultValue("1000") int maxUpdateDeleteRows,
        @DefaultValue("10000") int maxInsertRows) {
}
