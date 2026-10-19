package com.hamonsoft.netismaker.entity;

/** 요청 게시판 요청 종류. DB에는 이름(FEATURE|MCP|REPO)을 그대로 저장한다. */
public enum RequestKind {
    FEATURE("기능 추가"),
    MCP("MCP 추가"),
    REPO("REPO 추가");

    private final String label;

    RequestKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
