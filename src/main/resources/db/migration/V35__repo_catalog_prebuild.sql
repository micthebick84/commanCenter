-- 레포별 배포 전 미리 빌드 설정 (스펙 2026-10-28 §4.1). 도구는 이름만 저장하고 경로는 워커 설정에 둔다.
ALTER TABLE com.repo_catalog
    ADD COLUMN IF NOT EXISTS prebuild_enabled   BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS prebuild_command   TEXT,
    ADD COLUMN IF NOT EXISTS prebuild_artifact  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS prebuild_toolchain VARCHAR(50);
