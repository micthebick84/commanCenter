-- 질문 레포 추천(스펙 2026-11-20 §6.4·§7): 추천 키워드 + 추천 기록.
ALTER TABLE com.repo_catalog ADD COLUMN route_hint TEXT;

CREATE TABLE com.question_route_log (
    id                          BIGSERIAL PRIMARY KEY,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id                     VARCHAR(20) NOT NULL,
    question_excerpt            TEXT NOT NULL,
    state                       VARCHAR(20) NOT NULL,
    model                       VARCHAR(50),
    candidates                  JSONB NOT NULL DEFAULT '[]',
    mentions_target             DOUBLE PRECISION,
    target_listed               DOUBLE PRECISION,
    needs_db                    DOUBLE PRECISION,
    recommended_repo_catalog_id BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    chosen_repo_catalog_id      BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    session_id                  BIGINT REFERENCES com.interview_session(id) ON DELETE SET NULL,
    chosen_at                   TIMESTAMPTZ,
    latency_ms                  INT
);
CREATE INDEX ix_question_route_log_created ON com.question_route_log(created_at);
