package com.hamonsoft.netismaker.entity;

/**
 * 작업 상태 머신.
 *
 *  분석 단계:
 *   [작업대기] ──→ [분석중] ──→ [분석완료]
 *                      │
 *                      └ (실패) → [분석실패] ──(retry)──→ [작업대기]
 *
 *  구현 단계 (분석완료 + admin 승인 시 진입):
 *   [분석완료] ──(승인)──→ [구현대기] ──(워커 claim)──→ [구현중]
 *                                                          │
 *                                                          ├ (PR 생성) → [PR생성]
 *                                                          └ (실패)    → [구현실패]
 *
 *  배포 단계 (PR생성 + admin 배포 시 진입):
 *   [PR생성] ──(배포)──→ [배포대기] ──(워커 claim)──→ [배포중] ──┬→ [배포완료]
 *                                                                └→ [배포실패]
 *   [배포완료|배포실패] ──(재배포)──→ [배포대기]
 *   [배포완료|배포실패] ──(중지)──→ [배포중지대기] ──(워커)──→ [PR생성]
 *
 *  취소:
 *   [작업대기] ──→ [취소됨] (본인, PENDING 한정)
 *
 * DB 값은 한글 그대로 저장 (VARCHAR(30)). Enum 이름과 분리되어 있으니
 * Java enum 이름 변경이 DB 호환을 깨지 않음.
 */
public enum TaskStatus {
    PENDING("작업대기"),
    IN_PROGRESS("분석중"),
    COMPLETED("분석완료"),
    FAILED("분석실패"),
    APPROVED("구현대기"),
    IMPLEMENTING("구현중"),
    PR_CREATED("PR생성"),
    IMPLEMENTATION_FAILED("구현실패"),
    DEPLOY_PENDING("배포대기"),
    DEPLOYING("배포중"),
    DEPLOYED("배포완료"),
    DEPLOY_FAILED("배포실패"),
    UNDEPLOY_PENDING("배포중지대기"),
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
