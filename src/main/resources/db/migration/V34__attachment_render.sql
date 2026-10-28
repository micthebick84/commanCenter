-- 첨부 pptx 슬라이드 렌더링 칸 + 작업 첨부의 추출 텍스트·질문 첨부 승계 출처 (스펙 2026-10-27 §4)
ALTER TABLE com.task_attachment
    ADD COLUMN render_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN render_dir VARCHAR(500),
    ADD COLUMN render_page_count INT,
    ADD COLUMN render_total_pages INT,
    ADD COLUMN render_media_count INT,
    ADD COLUMN render_error VARCHAR(500),
    ADD COLUMN render_started_at TIMESTAMPTZ,
    ADD COLUMN render_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN extracted_text_path VARCHAR(500),
    ADD COLUMN source_question_attachment_id BIGINT;

ALTER TABLE com.question_attachment
    ADD COLUMN render_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN render_dir VARCHAR(500),
    ADD COLUMN render_page_count INT,
    ADD COLUMN render_total_pages INT,
    ADD COLUMN render_media_count INT,
    ADD COLUMN render_error VARCHAR(500),
    ADD COLUMN render_started_at TIMESTAMPTZ,
    ADD COLUMN render_attempts INT NOT NULL DEFAULT 0;

-- 렌더링 큐(PENDING·RUNNING) 조회용 부분 인덱스
CREATE INDEX idx_task_attachment_render_active ON com.task_attachment (id)
    WHERE render_status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_question_attachment_render_active ON com.question_attachment (id)
    WHERE render_status IN ('PENDING', 'RUNNING');
