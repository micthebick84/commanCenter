package com.hamonsoft.netismaker.entity;

/**
 * 인터뷰 세션 상태 머신. DESIGN(대화형 분석) §5 그대로.
 *
 *   [인터뷰대기] ──(워커 claim)──→ [인터뷰중] ──┬─→ [입력대기] (질문 emit, 사람 답변 대기, 워커 반납)
 *                                              ├─→ [플랜완료] (writing-plans 완료)
 *                                              └─→ [인터뷰실패]
 *   [입력대기] ──(답변 도착)──→ [인터뷰대기] (재큐)
 *   [입력대기] ──→ [만료됨] | [취소됨]
 *   [인터뷰대기] ──(queued TTL 초과 = 인터뷰 서비스 미처리)──→ [만료됨]
 *   [플랜완료] ──(작업 등록)──→ [등록됨] (terminal, task 생성)
 *   [플랜완료] ──→ [취소됨]
 *
 * Task의 상태 enum(TaskStatus)은 변경하지 않는다. 인터뷰 상태는 전부 여기에 있다.
 * DB 값은 한글 그대로 저장 (VARCHAR(30)). Java enum 이름과 분리.
 */
public enum InterviewStatus {
    QUEUED("인터뷰대기"),
    RUNNING("인터뷰중"),
    AWAITING_INPUT("입력대기"),
    PLAN_READY("플랜완료"),
    REGISTERED("등록됨"),
    CANCELLED("취소됨"),
    EXPIRED("만료됨"),
    FAILED("인터뷰실패");

    private final String dbValue;

    InterviewStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static InterviewStatus fromDb(String value) {
        for (InterviewStatus s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown interview status: " + value);
    }
}
