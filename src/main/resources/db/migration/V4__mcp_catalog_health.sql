-- MCP 카탈로그 헬스 상태. 수동 테스트 + 5분 주기 스케줄러가 갱신.
-- status enum: HEALTHY (SSE OK), DEGRADED (HTTP OK + 잘못된 content-type 또는 4xx), DOWN (5xx/timeout/network)
ALTER TABLE com.mcp_catalog
    ADD COLUMN IF NOT EXISTS last_check_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_check_status  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS last_check_error   TEXT;
