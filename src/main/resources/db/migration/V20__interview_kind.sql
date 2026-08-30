-- V20: 질문 세션(Q&A) — 인터뷰 세션 테이블을 kind 컬럼으로 공유한다
-- (docs/superpowers/specs/2026-08-30-question-sessions-design.md §4).
-- 기존 row는 DEFAULT 'INTERVIEW'로 안전. 값: INTERVIEW | QUESTION (Java enum 이름 그대로).
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'INTERVIEW';

-- 질문 목록(본인/전체, 최신순) 조회 인덱스.
CREATE INDEX IF NOT EXISTS idx_interview_session_kind_requester
    ON com.interview_session(kind, requester_id, created_at DESC);
