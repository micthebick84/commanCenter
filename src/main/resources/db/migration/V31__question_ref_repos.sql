-- 질문 세션 참조 repo (스펙 2026-10-22). 선택 당시 스냅샷 + 런타임 상태(고정 커밋·실패 사유).
ALTER TABLE com.interview_session
    ADD COLUMN ref_repos JSONB NOT NULL DEFAULT '[]'::jsonb;
