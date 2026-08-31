-- V21: 단계별 토큰/비용 누적 (docs/superpowers/specs/2026-08-31-stage-usage-cost-design.md §3)
-- stage: INTERVIEW | ANALYSIS | DESIGN | IMPLEMENTATION (CHECK 제약 없는 VARCHAR — 기존 컨벤션)
CREATE TABLE IF NOT EXISTS com.task_stage_usage (
    task_id  BIGINT      NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    stage    VARCHAR(20) NOT NULL,
    cost_usd              NUMERIC(12,6) NOT NULL DEFAULT 0,
    input_tokens          BIGINT NOT NULL DEFAULT 0,
    output_tokens         BIGINT NOT NULL DEFAULT 0,
    cache_creation_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens     BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, stage)
);

-- 인터뷰 세션 토큰 누적 (total_cost_usd와 동일 방식으로 턴마다 누적)
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS input_tokens          BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS output_tokens         BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_creation_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_read_tokens     BIGINT NOT NULL DEFAULT 0;
