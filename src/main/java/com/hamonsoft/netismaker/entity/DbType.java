package com.hamonsoft.netismaker.entity;

/**
 * 질문 세션 DB 접속정보 종류 (스펙 2026-10-02 §2). 값 이름은 DB CHECK 제약·프론트·인터뷰 서비스와 공유 — 바꾸지 말 것.
 * MariaDB는 인터뷰 서비스에서 mysql2 드라이버를 쓰지만, 접속 테스트(JDBC)는 MariaDB 드라이버로 한다.
 */
public enum DbType {
    POSTGRESQL("PostgreSQL", 5432),
    MYSQL("MySQL", 3306),
    MARIADB("MariaDB", 3306),
    ORACLE("Oracle", 1521);

    private final String label;
    private final int defaultPort;

    DbType(String label, int defaultPort) {
        this.label = label;
        this.defaultPort = defaultPort;
    }

    public String getLabel() { return label; }

    public int getDefaultPort() { return defaultPort; }

    /** host·database는 DTO 패턴 검증을 통과한 값만 들어온다(URL 파라미터 주입 차단 — DbConnectionDto 주석). */
    public String jdbcUrl(String host, int port, String database) {
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database;
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + database;
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        };
    }

    public String probeSql() {
        return this == ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }
}
