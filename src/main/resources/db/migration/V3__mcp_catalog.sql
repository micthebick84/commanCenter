-- 관리자 큐레이션 SSE MCP 카탈로그.
-- 사용자가 작업 등록 시 select 할 수 있는 후보. 자유 URL 입력은 V1.x에서 의도적으로 미지원 (보안).
CREATE TABLE IF NOT EXISTS com.mcp_catalog (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL UNIQUE,
                                  -- mcp__<name> 으로 claude에 노출. [a-z0-9_-]+ 권장
    display_name    VARCHAR(100) NOT NULL,
    url             TEXT         NOT NULL,
                                  -- SSE 엔드포인트 URL (https 권장)
    transport       VARCHAR(20)  NOT NULL DEFAULT 'sse',
                                  -- 현재 'sse'만 허용. 'http' 등 향후 확장 대비
    description     TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_by      VARCHAR(20)  REFERENCES com."user"(user_id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mcp_catalog_enabled ON com.mcp_catalog(enabled) WHERE enabled = true;

-- 작업별 사용된 추가 MCP 스냅샷.
-- ids 만 저장하지 않고 스펙(name/url/transport) 통째로 박제 → 카탈로그가 나중에 변경/삭제돼도 감사·재현성 보존.
-- 형식: [{"name":"foo","url":"https://...","transport":"sse"}, ...]
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS mcps_extra jsonb NOT NULL DEFAULT '[]'::jsonb;
