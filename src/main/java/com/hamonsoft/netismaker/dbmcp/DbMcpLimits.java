package com.hamonsoft.netismaker.dbmcp;

/** DB 도구 상한 — 인터뷰 서비스 db/limits.ts와 같은 값 (스펙 2026-10-02 §6.2·§6.3, 2026-10-14 §6.3). 바꿀 땐 양쪽 같이. */
public final class DbMcpLimits {
    public static final int STATEMENT_TIMEOUT_SECONDS = 30;
    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    public static final int MAX_ROWS = 200;
    public static final int MAX_CATALOG_ROWS = 1000;
    public static final int MAX_CELL_CHARS = 2000;
    public static final int MAX_RESULT_CHARS = 60_000;

    private DbMcpLimits() {}
}
