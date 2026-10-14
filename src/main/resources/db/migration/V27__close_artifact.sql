-- 종료 산출물 보관·내려받기·등록자 메일 (스펙 2026-10-14 §8). 모두 nullable 또는 기본값 — 기존 행은 그대로.
ALTER TABLE com.task
    ADD COLUMN requester_email      VARCHAR(320),
    ADD COLUMN close_artifact_mode  VARCHAR(10),
    ADD COLUMN artifact_name        VARCHAR(255),
    ADD COLUMN artifact_size        BIGINT,
    ADD COLUMN artifact_sha256      VARCHAR(64),
    ADD COLUMN artifact_source      VARCHAR(500),
    ADD COLUMN artifact_uploaded_at TIMESTAMPTZ,
    ADD COLUMN close_mail_status    VARCHAR(20),
    ADD COLUMN close_mail_at        TIMESTAMPTZ;

ALTER TABLE com.repo_catalog
    ADD COLUMN artifact_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN artifact_path    VARCHAR(500);
