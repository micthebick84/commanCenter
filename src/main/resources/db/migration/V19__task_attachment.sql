-- 작업 첨부파일 메타 (파일 본체는 디스크: app.attachment.dir 하위, 스펙 2026-08-16 §4)
CREATE TABLE IF NOT EXISTS com.task_attachment (
    id                BIGSERIAL PRIMARY KEY,
    task_id           BIGINT NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    stored_path       VARCHAR(500) NOT NULL,
    content_type      VARCHAR(100),
    size_bytes        BIGINT NOT NULL,
    uploaded_by       VARCHAR(20) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_attachment_task ON com.task_attachment(task_id);
