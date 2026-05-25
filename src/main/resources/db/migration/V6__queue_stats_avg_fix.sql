-- V6: task_queue_stats의 avg_duration_ms 계산 수정
--
-- 문제: V1/V5 뷰는 updated_at - created_at 평균을 썼는데,
--       task가 며칠 뒤 soft delete되거나 승인되면 updated_at이 갱신되어
--       평균이 분 단위가 아니라 일/시간 단위로 부풀려짐 (예: 11604분).
-- 수정: task_analysis.duration_ms (워커가 측정한 실제 claude exec 시간) 평균 사용.
--       deleted task는 제외.
--
-- 카운터들의 컬럼 정의/순서는 V5와 동일. avg_duration_ms 출처만 변경.
-- CREATE OR REPLACE는 컬럼 정의 변경 시 거부하므로 DROP 후 재생성.

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
    -- avg는 실제 claude 처리 시간 (워커가 측정). 삭제된 task 제외.
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
