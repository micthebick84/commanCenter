-- V18: 인터뷰 시점을 등록 → 관리자 승인으로 이동.
-- task.status는 CHECK 제약 없는 VARCHAR(30)이라 새 상태(승인대기/인터뷰중/입력대기/플랜승인대기)
-- 추가에 DDL이 필요 없다. 여기서는 뷰/인덱스/데이터 정리만 한다.

-- 1) 배포 시점의 고아 세션 마감: task_id 없는 비종료 세션은 확정할 곳이 없다.
UPDATE com.interview_session
   SET status = '취소됨', updated_at = now()
 WHERE task_id IS NULL
   AND status IN ('인터뷰대기', '인터뷰중', '입력대기', '플랜완료');

-- 2) task별 최신 세션 조회 인덱스 (작업 상세가 매 조회마다 사용).
CREATE INDEX IF NOT EXISTS idx_interview_session_task
    ON com.interview_session(task_id, created_at DESC);

-- 3) 큐 통계 뷰에 인터뷰 카운터 3개 추가.
--    CREATE OR REPLACE는 컬럼 추가를 거부하므로 DROP 후 재생성 (V15/V16과 동일 패턴).
--    기존 awaiting_approval(분석완료 + 미승인)과 이름이 겹치지 않게 pending_approval을 쓴다.
DROP VIEW IF EXISTS com.task_queue_stats;
CREATE VIEW com.task_queue_stats AS
SELECT
    COUNT(*) FILTER (WHERE status = '작업대기' AND deleted_at IS NULL)              AS pending,
    COUNT(*) FILTER (WHERE status = '분석중'   AND deleted_at IS NULL)              AS in_progress,
    COUNT(*) FILTER (WHERE status = '분석완료' AND deleted_at IS NULL
                     AND NOT EXISTS (SELECT 1 FROM com.task_analysis a
                                     WHERE a.task_id = com.task.id AND a.approved)) AS awaiting_approval,
    COUNT(*) FILTER (WHERE status = '구현대기' AND deleted_at IS NULL)              AS approved,
    COUNT(*) FILTER (WHERE status = '구현중'   AND deleted_at IS NULL)              AS implementing,
    COUNT(*) FILTER (WHERE status = 'PR생성'   AND deleted_at IS NULL)              AS pr_created,
    COUNT(*) FILTER (WHERE status = '구현실패' AND deleted_at IS NULL)              AS implementation_failed,
    COUNT(*) FILTER (WHERE status = '분석실패' AND deleted_at IS NULL)              AS failed,
    COUNT(*) FILTER (WHERE status = '디자인대기' AND deleted_at IS NULL)            AS design_pending,
    COUNT(*) FILTER (WHERE status = '디자인중'   AND deleted_at IS NULL)            AS designing,
    COUNT(*) FILTER (WHERE status = '디자인승인대기' AND deleted_at IS NULL)        AS design_review,
    COUNT(*) FILTER (WHERE status = '디자인실패' AND deleted_at IS NULL)            AS design_failed,
    COUNT(*) FILTER (WHERE status = '배포대기' AND deleted_at IS NULL)              AS deploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중'   AND deleted_at IS NULL)              AS deploying,
    COUNT(*) FILTER (WHERE status = '배포완료' AND deleted_at IS NULL)              AS deployed,
    COUNT(*) FILTER (WHERE status = '배포실패' AND deleted_at IS NULL)              AS deploy_failed,
    COUNT(*) FILTER (WHERE status = '배포중단됨' AND deleted_at IS NULL)            AS deploy_lost,
    COUNT(*) FILTER (WHERE status = '배포중지대기' AND deleted_at IS NULL)          AS undeploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중지중'   AND deleted_at IS NULL)          AS undeploying,
    COUNT(*) FILTER (WHERE status = '승인대기' AND deleted_at IS NULL)              AS pending_approval,
    COUNT(*) FILTER (WHERE status IN ('인터뷰중', '입력대기') AND deleted_at IS NULL) AS interviewing,
    COUNT(*) FILTER (WHERE status = '플랜승인대기' AND deleted_at IS NULL)          AS plan_review,
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
