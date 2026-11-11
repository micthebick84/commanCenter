-- 재전송을 포기해 워커 PC의 .dead.jsonl에 보관만 된 결과 보고 수 (워커 화면 '재전송 포기').
-- 구버전 워커·인터뷰 서비스는 보내지 않는다 → 0. 기존 row 안전을 위해 NOT NULL DEFAULT 0.
ALTER TABLE com.worker_heartbeat
    ADD COLUMN IF NOT EXISTS dead_report_count INT NOT NULL DEFAULT 0;
