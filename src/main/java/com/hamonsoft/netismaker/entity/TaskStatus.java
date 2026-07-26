package com.hamonsoft.netismaker.entity;

/**
 * 작업 상태 머신.
 *
 *  인터뷰 단계 (등록 → 관리자 승인 → 플랜 확정):
 *   [승인대기] ──(관리자 승인)──→ [인터뷰중] ⇄ [입력대기]
 *                                     │
 *                               (플랜 생성)
 *                                     ↓
 *                             [플랜승인대기] ──(구현 진행)──→ [구현대기] | [디자인대기]
 *   [인터뷰중|입력대기|플랜승인대기] ──(실패/만료/취소)──→ [승인대기]
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
 *   [배포완료|배포실패|배포중단됨] ──(재배포)──→ [배포대기]
 *   [배포완료|배포실패|배포중단됨] ──(중지)──→ [배포중지대기] ──(워커 claim)──→ [배포중지중] ──→ [PR생성]
 *
 *  배포 후 런타임 정합 (DeployReconcileJob이 컨테이너 생존을 주기 관측):
 *   [배포완료] ──(컨테이너 소실 감지)──→ [배포중단됨]
 *   [배포중단됨] ──(컨테이너 복구 감지)──→ [배포완료]
 *
 *  디자인 단계 (design_requested=true인 작업이 분석 승인 시 진입):
 *   [분석완료] ──(승인)──→ [디자인대기] ──(워커 claim)──→ [디자인중] ──┬→ [디자인승인대기]
 *                                                                      └→ [디자인실패] ──(retry)──→ [디자인대기]
 *   [디자인승인대기] ──(승인)──→ [구현대기]
 *   [디자인승인대기] ──(반려, 최대 3회)──→ [디자인대기]
 *
 *  취소:
 *   [작업대기] ──→ [취소됨] (본인, PENDING 한정)
 *
 * DB 값은 한글 그대로 저장 (VARCHAR(30)). Enum 이름과 분리되어 있으니
 * Java enum 이름 변경이 DB 호환을 깨지 않음.
 */
public enum TaskStatus {
    AWAITING_APPROVAL("승인대기"),
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
    DEPLOY_LOST("배포중단됨"),
    UNDEPLOY_PENDING("배포중지대기"),
    UNDEPLOYING("배포중지중"),
    DESIGN_PENDING("디자인대기"),
    DESIGNING("디자인중"),
    DESIGN_REVIEW("디자인승인대기"),
    DESIGN_FAILED("디자인실패"),
    INTERVIEWING("인터뷰중"),
    INTERVIEW_INPUT("입력대기"),
    INTERVIEW_REVIEW("플랜승인대기"),
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
