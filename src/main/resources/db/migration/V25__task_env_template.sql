-- 구현 시 추출한 배포 환경변수 템플릿 [{key, description, secret, required}] — 값은 담지 않는다.
-- 스펙 docs/superpowers/specs/2026-10-07-deploy-env-template-design.md §3.2. 기존 row는 빈 배열(템플릿 없음).
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS env_template JSONB NOT NULL DEFAULT '[]'::jsonb;
