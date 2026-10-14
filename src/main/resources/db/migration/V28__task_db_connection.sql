-- 작업 DB 연결 선택 (스펙 2026-10-14 §3). id만 저장 — 접속정보는 실행 시점의 현재 행으로 해석한다.
ALTER TABLE com.task
    ADD COLUMN db_connection_ids JSONB NOT NULL DEFAULT '[]';

-- 디자인·구현 단계 DB MCP 작업 토큰. 평문은 저장하지 않는다(SHA-256 hex). stage = 발급 당시 TaskStatus.name().
CREATE TABLE com.task_db_mcp_token (
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    stage       VARCHAR(32) NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_task_db_mcp_token_task ON com.task_db_mcp_token(task_id, id DESC);
