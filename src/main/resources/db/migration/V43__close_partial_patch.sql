-- 종료 부분 패치 (스펙 2026-11-10 close-partial-patch). 종료 때 기준 커밋 대비 변경 파일만 묶은 패치를 보관한다.
-- 모두 NULL 허용 또는 기본값이 있어 기존 행은 그대로 안전하다(close_patch_mode NULL = 기능 해당 없음).
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS close_patch_mode VARCHAR(20),
    ADD COLUMN IF NOT EXISTS patch_base_sha VARCHAR(40),
    ADD COLUMN IF NOT EXISTS patch_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS patch_size BIGINT,
    ADD COLUMN IF NOT EXISTS patch_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS patch_file_count INTEGER,
    ADD COLUMN IF NOT EXISTS patch_empty BOOLEAN,
    ADD COLUMN IF NOT EXISTS patch_uploaded_at TIMESTAMPTZ;

ALTER TABLE com.repo_catalog
    ADD COLUMN IF NOT EXISTS patch_enabled BOOLEAN NOT NULL DEFAULT FALSE;
