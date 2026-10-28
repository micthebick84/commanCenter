-- 레포별 종료 노트 폴더(볼트 상대 경로, 스펙 2026-10-28 close-note-folder §5). 비면 첫 종료 때 워커가 정해 기록한다.
ALTER TABLE com.repo_catalog
    ADD COLUMN IF NOT EXISTS note_folder VARCHAR(500);
