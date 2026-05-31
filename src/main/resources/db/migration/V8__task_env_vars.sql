-- 배포 컨테이너에 주입할 환경변수 목록 (key/value/secret). mcps_extra와 동일 패턴.
-- 기존 row는 빈 배열로 안전 (NOT NULL DEFAULT).
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS env_vars JSONB NOT NULL DEFAULT '[]'::jsonb;
