-- 요청 게시판 "개발 요청"(스펙 2026-11-19 §6): 이슈 유형·우선순위·희망일·대상 레포·연결 작업 + 첨부.
-- request_post.kind는 V30에서 CHECK 없는 VARCHAR(16)이라 DEV를 그대로 담는다.
ALTER TABLE com.request_post
    ADD COLUMN issue_type VARCHAR(20),
    ADD COLUMN priority VARCHAR(10),
    ADD COLUMN due_date DATE,
    ADD COLUMN target_repo_catalog_id BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    ADD COLUMN task_id BIGINT;
ALTER TABLE com.request_post
    ADD CONSTRAINT ck_request_post_issue_type CHECK (issue_type IS NULL OR issue_type IN ('BUG','DEV','INQUIRY')),
    ADD CONSTRAINT ck_request_post_priority CHECK (priority IS NULL OR priority IN ('HIGH','NORMAL','LOW'));

CREATE TABLE com.request_attachment (
    id            BIGSERIAL PRIMARY KEY,
    request_id    BIGINT NOT NULL REFERENCES com.request_post(id) ON DELETE CASCADE,
    ordinal       INT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_path   TEXT NOT NULL,
    mime_type     VARCHAR(255),
    size_bytes    BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_request_attachment_request ON com.request_attachment(request_id);

-- 개발 요청에서 만든 작업의 출처(기록용, FK 없음 — 스펙 §5.4)
ALTER TABLE com.task ADD COLUMN source_request_id BIGINT;
