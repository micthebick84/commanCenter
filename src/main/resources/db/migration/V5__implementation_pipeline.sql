-- V5: 분석 → 승인 → 구현 → PR 파이프라인
-- 분석완료된 task를 admin이 승인하면 워커가 worktree에서 구현 + PR 생성.
-- 기존 분석 흐름은 그대로. CHECK 제약 없는 VARCHAR라 신규 상태 라벨은 그냥 INSERT 가능.

-- 1) task에 PR 결과 컬럼 추가 (NOT NULL DEFAULT 없이 nullable — 구현 단계 안 거친 task는 NULL)
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS pr_url            VARCHAR(500),
    ADD COLUMN IF NOT EXISTS pr_number         INT,
    ADD COLUMN IF NOT EXISTS head_branch       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS head_sha          VARCHAR(40),
    ADD COLUMN IF NOT EXISTS implementation_log TEXT;

-- 2) 워커 claim 인덱스를 PENDING/APPROVED 둘 다 커버하도록 보강
--    기존 idx_task_pending(status='작업대기')은 분석 큐, 신규는 구현 큐.
CREATE INDEX IF NOT EXISTS idx_task_approved ON com.task(created_at)
    WHERE status = '구현대기' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_task_implementing ON com.task(claimed_at)
    WHERE status = '구현중' AND deleted_at IS NULL;

-- 3) 큐 통계 뷰 갱신 — 구현 단계 카운터 4개 추가
-- 기존 뷰는 컬럼 순서가 (pending, in_progress, awaiting_approval, failed, avg)였음.
-- CREATE OR REPLACE VIEW는 컬럼 순서 변경을 거부하므로 DROP 후 재생성.
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
    AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000)
        FILTER (WHERE status IN ('분석완료', '분석실패'))                            AS avg_duration_ms
FROM com.task;
