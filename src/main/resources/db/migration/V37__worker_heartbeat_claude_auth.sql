-- 워커·인터뷰 서비스가 보고하는 claude 로그인 판정 방식과 만료 시각(스펙 2026-11-02 claude-auth-heartbeat §4).
-- 구버전 워커는 보내지 않으므로 NULL 허용. claude_session_ok(V1)는 이제 판정값이 채워진다.
ALTER TABLE com.worker_heartbeat
    ADD COLUMN IF NOT EXISTS claude_auth_method VARCHAR(20),
    ADD COLUMN IF NOT EXISTS claude_auth_expires_at TIMESTAMPTZ;
