package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.DbType;

/** 저장 전/후 접속 테스트 (스펙 2026-10-02 §5.2). 서비스 단위 테스트에서 목으로 갈아끼우려고 인터페이스로 둔다. */
public interface DbConnectionTester {

    record Target(DbType dbType, String host, int port, String databaseName, String username, String password) {
        @Override
        public String toString() {
            return "Target[" + dbType + " " + host + ":" + port + "/" + databaseName + " user=" + username + "]";
        }
    }

    record Result(boolean ok, String message) {}

    Result test(Target target);
}
