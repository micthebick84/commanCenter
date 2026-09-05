-- V22: 질문 탭 채팅형 UI + Claude Code 구독 사용량
--      (docs/superpowers/specs/2026-09-05-question-chat-ui-design.md §5.1)

-- 1) 세션별 컨텍스트 상태 — 마지막 턴의 컨텍스트 토큰/창 크기. null = 미보고(구버전 인터뷰 서비스).
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS context_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS context_window BIGINT;

-- 2) Claude Code 구독 사용량 — SDK rate_limit_event 스냅샷. limit_type당 1행 upsert (계정 1개 공유 전제).
CREATE TABLE IF NOT EXISTS com.claude_rate_limit (
    limit_type       VARCHAR(20)  PRIMARY KEY,               -- five_hour | seven_day | seven_day_opus | seven_day_sonnet | overage
    status           VARCHAR(20)  NOT NULL,                  -- allowed | allowed_warning | rejected
    utilization      NUMERIC(6,4) NOT NULL DEFAULT 0,        -- 0..1 분수
    resets_at        TIMESTAMPTZ,
    is_using_overage BOOLEAN      NOT NULL DEFAULT false,
    reported_by      VARCHAR(50)  NOT NULL,                  -- 보고한 인터뷰 서비스 WORKER_ID
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
