package com.hamonsoft.netismaker.entity;

/** 개발 요청의 우선순위. DB에는 이름을 그대로 저장한다. */
public enum RequestPriority {
    HIGH("높음"),
    NORMAL("보통"),
    LOW("낮음");

    private final String label;

    RequestPriority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
