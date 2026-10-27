-- 작업 참조 repo (스펙 2026-10-24 §3.1). source_question_id는 기록용 — interview_session.task_id와 순환이라 FK를 걸지 않는다.
ALTER TABLE com.task ADD COLUMN ref_repos JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE com.task ADD COLUMN source_question_id BIGINT;
