-- 회차 종류: FIX(수정 요청) | BASE_SYNC(기준 브랜치 반영, 스펙 2026-11-13). 기존 행은 FIX.
ALTER TABLE com.task_fix_request
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'FIX';
ALTER TABLE com.task_fix_request
    ADD CONSTRAINT ck_task_fix_request_kind CHECK (kind IN ('FIX', 'BASE_SYNC'));
