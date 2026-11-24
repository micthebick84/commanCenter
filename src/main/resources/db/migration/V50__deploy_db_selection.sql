-- 배포 DB 연결 고르기·배포 설정 덮어쓰기 (스펙 2026-11-24-deploy-db-connection-select)
ALTER TABLE com.repo_catalog ADD COLUMN IF NOT EXISTS deploy_config JSONB;
ALTER TABLE com.task ADD COLUMN IF NOT EXISTS deploy_db_selection JSONB NOT NULL DEFAULT '[]';
