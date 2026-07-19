-- V16: 디자인 구간 (분석 승인 후 → 구현 전, opt-in)
ALTER TABLE com.task ADD COLUMN design_requested boolean NOT NULL DEFAULT false;

ALTER TABLE com.repo_catalog ADD COLUMN design_system_project_id varchar(100);
ALTER TABLE com.repo_catalog ADD COLUMN design_output_project_id varchar(100);

CREATE TABLE com.task_design (
    task_id          bigint PRIMARY KEY REFERENCES com.task(id),
    design_markdown  text NOT NULL,
    mockup_files     jsonb NOT NULL DEFAULT '[]',
    design_project_id varchar(100),
    design_url       varchar(500),
    feedback_history jsonb NOT NULL DEFAULT '[]',
    reject_count     int NOT NULL DEFAULT 0,
    approved         boolean NOT NULL DEFAULT false,
    approved_by      varchar(20),
    approved_at      timestamptz,
    claude_log       text,
    duration_ms      bigint,
    completed_at     timestamptz NOT NULL
);

-- 큐 통계 뷰에 디자인 카운터 추가 (CREATE OR REPLACE는 컬럼 추가 거부 → DROP 재생성, V15와 동일 패턴)
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
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
