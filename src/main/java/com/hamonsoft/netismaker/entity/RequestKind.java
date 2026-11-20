package com.hamonsoft.netismaker.entity;

/**
 * 요청 게시판 요청 종류. DB에는 이름(DEV|REPO|FEATURE|MCP)을 그대로 저장한다.
 * REPO의 DB 값은 그대로 두고 라벨만 "사이트 추가"다. FEATURE·MCP는 기존 행 표시를 위해 남기되 새로 만들 수 없다.
 */
public enum RequestKind {
    DEV("개발 요청"),
    REPO("사이트 추가"),
    FEATURE("기능 추가"),
    MCP("MCP 추가");

    private final String label;

    RequestKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 새로 등록할 수 있는 종류(DEV·REPO). */
    public boolean creatable() {
        return this == DEV || this == REPO;
    }

    /** 로그인한 모든 사용자에게 공개되는 종류(DEV만). 그 밖은 본인·관리자만. */
    public boolean publicToAll() {
        return this == DEV;
    }
}
