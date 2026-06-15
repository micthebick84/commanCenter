-- 작업 등록 시 선택한 Claude 모델 + effort. 기존 row 안전(NOT NULL DEFAULT, mcps_extra/env_vars 패턴).
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS model  VARCHAR(64) NOT NULL DEFAULT 'claude-opus-4-8',
    ADD COLUMN IF NOT EXISTS effort VARCHAR(16) NOT NULL DEFAULT 'high';

ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS model  VARCHAR(64) NOT NULL DEFAULT 'claude-opus-4-8',
    ADD COLUMN IF NOT EXISTS effort VARCHAR(16) NOT NULL DEFAULT 'high';
