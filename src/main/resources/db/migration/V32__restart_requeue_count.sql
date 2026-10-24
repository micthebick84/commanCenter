-- 재기동 자동 재큐 횟수 (스펙 2026-10-23 §3.4). 작업은 구현 재큐만, 인터뷰 세션은 턴 단위로 센다.
ALTER TABLE com.task ADD COLUMN restart_requeue_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE com.interview_session ADD COLUMN restart_requeue_count INTEGER NOT NULL DEFAULT 0;
