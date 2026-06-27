-- 워커 결과보고 유실 카운트 (silent-loss 관측성). 기존 row 안전을 위해 NOT NULL DEFAULT 0.
ALTER TABLE com.worker_heartbeat
    ADD COLUMN IF NOT EXISTS lost_report_count INT NOT NULL DEFAULT 0;
