package com.hamonsoft.netismaker.entity;

/**
 * 작업 상태 머신 (DESIGN §2 rev6).
 *
 *   [작업대기] ──→ [분석중] ──→ [분석완료] (+ approved by ADMIN)
 *       │            │
 *       ▼            ▼ (실패)
 *   [취소됨]      [분석실패] ──(retry)──→ [작업대기]
 *
 * DB 값은 한글 그대로 저장 (VARCHAR(30)). Enum 이름과 분리되어 있으니
 * Java enum 이름 변경이 DB 호환을 깨지 않음.
 */
public enum TaskStatus {
    PENDING("작업대기"),
    IN_PROGRESS("분석중"),
    COMPLETED("분석완료"),
    FAILED("분석실패"),
    CANCELLED("취소됨");

    private final String dbValue;

    TaskStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static TaskStatus fromDb(String value) {
        for (TaskStatus s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown status: " + value);
    }
}
