-- V7: PR생성 → 배포 파이프라인
-- PR생성된 task를 admin이 배포하면 워커가 head 브랜치를 빌드해 로컬 docker에 띄움.
-- CHECK 제약 없는 VARCHAR(30)이라 신규 상태 라벨은 그냥 INSERT 가능.

-- 1) task에 배포 결과 컬럼 추가 (nullable — 배포 안 거친 task는 NULL)
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS deploy_url          VARCHAR(500),
    ADD COLUMN IF NOT EXISTS deploy_container_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS deploy_host_port    INT,
    ADD COLUMN IF NOT EXISTS deploy_image        VARCHAR(200),
    ADD COLUMN IF NOT EXISTS deployed_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deploy_log          TEXT;

-- 2) 배포 큐 claim 인덱스 (배포대기 + 배포중지대기 둘 다 FIFO claim 대상)
CREATE INDEX IF NOT EXISTS idx_task_deploy_pending ON com.task(created_at)
    WHERE status IN ('배포대기', '배포중지대기') AND deleted_at IS NULL;

-- 3) 큐 통계 뷰 갱신 — 배포 카운터 4개 추가.
--    V6의 avg_duration_ms(task_analysis.duration_ms 기반) 정의는 그대로 유지.
--    CREATE OR REPLACE VIEW는 컬럼 변경을 거부하므로 DROP 후 재생성.
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
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
