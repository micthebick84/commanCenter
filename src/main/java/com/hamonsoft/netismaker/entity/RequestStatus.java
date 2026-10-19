package com.hamonsoft.netismaker.entity;

/**
 * 요청 게시판 상태 (스펙 2026-10-19 §5.2). 전이는 관리자만 한다.
 *
 *   [접수] ──→ [검토중] | [처리완료] | [반려]
 *   [검토중] ──→ [처리완료] | [반려] | [접수]
 *   [처리완료] ──→ [검토중]   (다시 열기)
 *   [반려] ──→ [검토중]       (다시 열기)
 *
 * DB 값은 한글 그대로 저장 (VARCHAR(16)). 같은 상태로의 요청은 서비스가 no-op으로 처리하므로
 * canTransitionTo는 false다.
 */
public enum RequestStatus {
    RECEIVED("접수"),
    REVIEWING("검토중"),
    DONE("처리완료"),
    REJECTED("반려");

    private final String dbValue;

    RequestStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static RequestStatus fromDb(String value) {
        for (RequestStatus s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown request status: " + value);
    }

    /** 아직 처리 중인 상태(접수·검토중). 사용자별 열린 요청 상한 계산에 쓴다. */
    public boolean isOpen() {
        return this == RECEIVED || this == REVIEWING;
    }

    public boolean canTransitionTo(RequestStatus next) {
        if (next == null) return false;
        return switch (this) {
            case RECEIVED -> next == REVIEWING || next == DONE || next == REJECTED;
            case REVIEWING -> next == DONE || next == REJECTED || next == RECEIVED;
            case DONE, REJECTED -> next == REVIEWING;
        };
    }
}
