package com.hamonsoft.netismaker.entity;

/** 개발 요청의 이슈 유형. DB에는 이름을 그대로 저장한다. */
public enum RequestIssueType {
    BUG("버그"),
    DEV("개발요청"),
    INQUIRY("기능문의");

    private final String label;

    RequestIssueType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
