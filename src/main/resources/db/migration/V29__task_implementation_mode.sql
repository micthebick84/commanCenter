-- 구현 방식 선택·구현 재시도 (스펙 2026-10-15 §4). 기존 작업은 직접·1회째 — 지금 동작 그대로.
-- implementation_timeout_minutes는 claim(구현대기 → 구현중) 때 그 방식의 상한을 기록한 값(그 전에는 NULL).
ALTER TABLE com.task
    ADD COLUMN implementation_mode            VARCHAR(10) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN implementation_attempt         INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN implementation_timeout_minutes INTEGER,
    ADD CONSTRAINT ck_task_implementation_mode    CHECK (implementation_mode IN ('DIRECT', 'REVIEW', 'SDD')),
    ADD CONSTRAINT ck_task_implementation_attempt CHECK (implementation_attempt >= 1);
