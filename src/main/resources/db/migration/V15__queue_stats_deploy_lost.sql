-- V15: 큐 통계 뷰에 배포중단됨(DEPLOY_LOST) 카운터 추가.
-- 배포완료였다가 컨테이너 소실이 감지된 task가 대시보드에서 사라지지 않게 한다.
-- CREATE OR REPLACE는 컬럼 추가를 거부하므로 DROP 후 재생성 (V9와 동일 패턴).
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
    COUNT(*) FILTER (WHERE status = '배포대기' AND deleted_at IS NULL)              AS deploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중'   AND deleted_at IS NULL)              AS deploying,
    COUNT(*) FILTER (WHERE status = '배포완료' AND deleted_at IS NULL)              AS deployed,
    COUNT(*) FILTER (WHERE status = '배포실패' AND deleted_at IS NULL)              AS deploy_failed,
    COUNT(*) FILTER (WHERE status = '배포중단됨' AND deleted_at IS NULL)            AS deploy_lost,
    COUNT(*) FILTER (WHERE status = '배포중지대기' AND deleted_at IS NULL)          AS undeploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중지중'   AND deleted_at IS NULL)          AS undeploying,
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
