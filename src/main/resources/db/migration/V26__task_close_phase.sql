-- 작업 종료 단계 (스펙 docs/superpowers/specs/2026-10-13-task-close-phase-design.md §5).
-- 상태 값(종료승인대기·종료대기·종료중·작업종료·종료실패)은 CHECK 제약 없는 status 컬럼에 그대로 들어간다.
-- 종료 실패 사유는 기존 failure_reason을 쓴다(배포실패와 같은 관례).
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS close_progress   VARCHAR(100),
    ADD COLUMN IF NOT EXISTS close_note_path  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS merge_commit_sha VARCHAR(40);
