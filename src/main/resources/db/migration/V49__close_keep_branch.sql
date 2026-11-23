-- 병합하지 않고 종료: 브랜치·PR 유지 (스펙 2026-11-23-close-keep-branch)
ALTER TABLE com.task ADD COLUMN IF NOT EXISTS close_keep_branch BOOLEAN NOT NULL DEFAULT false;
