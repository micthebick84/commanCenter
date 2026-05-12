-- netisMaker V1 schema
-- DESIGN §7 rev6 그대로 구현
-- com 스키마는 netis-backend와 공유 (com."user" FK)

-- 작업 큐
CREATE TABLE IF NOT EXISTS com.task (
    id              BIGSERIAL PRIMARY KEY,
    github_repo     VARCHAR(255) NOT NULL,
    github_branch   VARCHAR(255) NOT NULL DEFAULT 'main',
    title           VARCHAR(500) NOT NULL,
    description     TEXT NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT '작업대기',
                    -- 작업대기 | 분석중 | 분석완료 | 분석실패 | 취소됨
    requester_id    VARCHAR(20) NOT NULL REFERENCES com."user"(user_id),
    retry_count     INT NOT NULL DEFAULT 0,
    max_retry       INT NOT NULL DEFAULT 3,
    worker_id       VARCHAR(50),
    claimed_at      TIMESTAMPTZ,
    failure_reason  TEXT,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_pending ON com.task(created_at)
    WHERE status = '작업대기' AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_task_requester ON com.task(requester_id, status)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_task_in_progress ON com.task(claimed_at)
    WHERE status = '분석중' AND deleted_at IS NULL;

-- 분석 산출물 (task : task_analysis = 1 : 1)
CREATE TABLE IF NOT EXISTS com.task_analysis (
    task_id         BIGINT PRIMARY KEY REFERENCES com.task(id) ON DELETE CASCADE,
    markdown_result TEXT NOT NULL,
    subtasks_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    claude_log      TEXT,
    duration_ms     BIGINT,
    approved        BOOLEAN NOT NULL DEFAULT false,
    approved_by     VARCHAR(20) REFERENCES com."user"(user_id),
    approved_at     TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_analysis_pending_approval ON com.task_analysis(task_id)
    WHERE approved = false;

-- 상태 전이 감사 로그
CREATE TABLE IF NOT EXISTS com.task_status_history (
    id              BIGSERIAL PRIMARY KEY,
    task_id         BIGINT NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    from_status     VARCHAR(30),
    to_status       VARCHAR(30) NOT NULL,
    actor_type      VARCHAR(20) NOT NULL,
                    -- 'user' | 'worker' | 'system'
    actor_id        VARCHAR(100),
    reason          TEXT,
    at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_status_history_task ON com.task_status_history(task_id, at DESC);

-- 워커 헬스체크 (V1 단일 워커지만 확장 대비)
CREATE TABLE IF NOT EXISTS com.worker_heartbeat (
    worker_id           VARCHAR(50) PRIMARY KEY,
    last_seen_at        TIMESTAMPTZ NOT NULL,
    hostname            VARCHAR(100),
    version             VARCHAR(50),
    claude_session_ok   BOOLEAN,
    vpn_status          VARCHAR(20)
);

-- 큐 통계 뷰 (DESIGN §7 그대로)
CREATE OR REPLACE VIEW com.task_queue_stats AS
SELECT
    COUNT(*) FILTER (WHERE status = '작업대기' AND deleted_at IS NULL)         AS pending,
    COUNT(*) FILTER (WHERE status = '분석중'   AND deleted_at IS NULL)         AS in_progress,
    COUNT(*) FILTER (WHERE status = '분석완료' AND deleted_at IS NULL
                     AND NOT EXISTS (SELECT 1 FROM com.task_analysis a
                                     WHERE a.task_id = com.task.id AND a.approved)) AS awaiting_approval,
    COUNT(*) FILTER (WHERE status = '분석실패' AND deleted_at IS NULL)         AS failed,
    AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) * 1000)
        FILTER (WHERE status IN ('분석완료', '분석실패'))                       AS avg_duration_ms
FROM com.task;
