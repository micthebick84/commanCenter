-- 요청 게시판 (스펙 docs/superpowers/specs/2026-10-19-request-board-design.md §5).
-- 사용자가 기능 추가 / MCP 추가 / REPO 추가를 요청하고 관리자가 답글·상태로 처리한다.
-- V29는 PR #95(V29__task_implementation_mode.sql)가 쓰므로 비워 둔다.
CREATE TABLE IF NOT EXISTS com.request_post (
    id                      BIGSERIAL PRIMARY KEY,
    kind                    VARCHAR(16)  NOT NULL,            -- FEATURE | MCP | REPO
    title                   VARCHAR(200) NOT NULL,
    content                 TEXT         NOT NULL,
    repo_hint               VARCHAR(500),                     -- REPO일 때만, 선택
    status                  VARCHAR(16)  NOT NULL,            -- 접수 | 검토중 | 처리완료 | 반려
    requester_id            VARCHAR(20)  NOT NULL REFERENCES com."user"(user_id),
    repo_catalog_id         BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    repo_git_url            VARCHAR(1000),                    -- 등록 당시 스냅샷(카탈로그 삭제 후에도 표시)
    last_requester_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_admin_activity_at  TIMESTAMPTZ,
    requester_read_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    admin_read_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_request_post_requester ON com.request_post(requester_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_request_post_status    ON com.request_post(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS com.request_comment (
    id          BIGSERIAL PRIMARY KEY,
    request_id  BIGINT      NOT NULL REFERENCES com.request_post(id) ON DELETE CASCADE,
    kind        VARCHAR(16) NOT NULL,                         -- COMMENT | SYSTEM
    author_id   VARCHAR(20) REFERENCES com."user"(user_id),   -- SYSTEM 기록도 행위자(관리자) id를 남긴다
    author_admin BOOLEAN    NOT NULL DEFAULT false,           -- 작성 시점 관리자 여부 박제
    body        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_request_comment_request ON com.request_comment(request_id, created_at);
