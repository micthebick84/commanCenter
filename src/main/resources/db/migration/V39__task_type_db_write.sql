-- 작업 타입 (스펙 2026-11-03 §4.2·§7). NULL = 타입 없음 — 기존 행은 NULL로 두고 지금 동작 그대로.
-- 값: NEW_FEATURE(신규 개발) | FEATURE_CHANGE(기능 수정) | BUG_FIX(버그 픽스) | DB_WORK(DB 작업).
-- 제약에 이름을 붙인다(V29의 ck_task_implementation_mode와 같은 방식) — 이름 없는 인라인 CHECK면
-- 모르는 값을 거절한 오류 메시지에서 어느 제약인지 알 수 없다.
ALTER TABLE com.task ADD COLUMN task_type VARCHAR(20), ADD CONSTRAINT ck_task_task_type CHECK (task_type IS NULL OR task_type IN ('NEW_FEATURE','FEATURE_CHANGE','BUG_FIX','DB_WORK'));
