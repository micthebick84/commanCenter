package com.hamonsoft.netismaker.dbmcp;

import com.hamonsoft.netismaker.entity.DbType;

/** 읽기 전용 검사·카탈로그 SQL의 방언 (인터뷰 서비스 sqlReadOnly.ts의 SqlDialect와 같은 셋). */
public enum SqlDialect {
    POSTGRES, MYSQL, ORACLE;

    public static SqlDialect of(DbType t) {
        return switch (t) {
            case POSTGRESQL -> POSTGRES;
            case ORACLE -> ORACLE;
            case MYSQL, MARIADB -> MYSQL;
        };
    }
}
