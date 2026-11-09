-- Claude Code 사용량 원장·수집기 상태·구독료 (스펙 2026-11-03 claude-usage-cost-report §4).
-- 이벤트 한 건 = 어시스턴트 응답 한 번(message.id:requestId). 같은 키는 토큰 칸별 최댓값으로만 올린다.
-- category: SYMPHONY | DIRECT. 내용(message.content)·cwd·경로는 저장하지 않는다.
CREATE TABLE com.claude_usage_event (
    id                    BIGSERIAL PRIMARY KEY,
    event_key             VARCHAR(200) NOT NULL,
    occurred_at           TIMESTAMPTZ  NOT NULL,
    machine               VARCHAR(100) NOT NULL,
    category              VARCHAR(20)  NOT NULL,
    model                 VARCHAR(100) NOT NULL,
    speed                 VARCHAR(20),
    service_tier          VARCHAR(30),
    entrypoint            VARCHAR(30),
    session_id            VARCHAR(100),
    is_sidechain          BOOLEAN      NOT NULL DEFAULT false,
    input_tokens          BIGINT       NOT NULL DEFAULT 0,
    output_tokens         BIGINT       NOT NULL DEFAULT 0,
    cache_write_5m_tokens BIGINT       NOT NULL DEFAULT 0,
    cache_write_1h_tokens BIGINT       NOT NULL DEFAULT 0,
    cache_read_tokens     BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_claude_usage_event_event_key UNIQUE (event_key),
    CONSTRAINT ck_claude_usage_event_category CHECK (category IN ('SYMPHONY', 'DIRECT'))
);
CREATE INDEX idx_claude_usage_event_occurred_at ON com.claude_usage_event(occurred_at);

-- 수집 PC별 마지막 보고 상태.
CREATE TABLE com.claude_usage_collector (
    machine          VARCHAR(100) PRIMARY KEY,
    last_reported_at TIMESTAMPTZ NOT NULL,
    last_event_at    TIMESTAMPTZ,
    unreadable_lines INTEGER     NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 구독료(월, USD). 같은 적용 시작일은 덮어쓴다. created_by는 기록용이라 FK를 두지 않는다.
CREATE TABLE com.usage_subscription_fee (
    id             BIGSERIAL PRIMARY KEY,
    effective_from DATE          NOT NULL,
    monthly_fee    NUMERIC(12,2) NOT NULL,
    currency       VARCHAR(3)    NOT NULL DEFAULT 'USD',
    created_by     VARCHAR(100)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_usage_subscription_fee_effective_from UNIQUE (effective_from),
    CONSTRAINT ck_usage_subscription_fee_monthly_fee CHECK (monthly_fee > 0),
    CONSTRAINT ck_usage_subscription_fee_currency CHECK (currency IN ('USD'))
);
