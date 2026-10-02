-- 질문 세션 DB 접속정보 (스펙 docs/superpowers/specs/2026-10-02-question-db-mcp-design.md §4).
-- REPO = 관리자 공용(그 레포를 고르는 모든 사용자), USER = 개인(본인만). 비밀번호는 AES-256-GCM 암호문만 저장.
CREATE TABLE IF NOT EXISTS com.db_connection (
    id               BIGSERIAL PRIMARY KEY,
    scope            VARCHAR(10)  NOT NULL,
    repo_catalog_id  BIGINT       NOT NULL REFERENCES com.repo_catalog(id) ON DELETE CASCADE,
    owner_user_id    VARCHAR(20),
    name             VARCHAR(100) NOT NULL,
    db_type          VARCHAR(20)  NOT NULL,
    host             VARCHAR(255) NOT NULL,
    port             INTEGER      NOT NULL,
    database_name    VARCHAR(255) NOT NULL,
    username         VARCHAR(255) NOT NULL,
    password_enc     TEXT         NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    created_by       VARCHAR(20),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_db_connection_scope CHECK (scope IN ('REPO','USER')),
    CONSTRAINT ck_db_connection_owner CHECK ((scope = 'USER') = (owner_user_id IS NOT NULL)),
    CONSTRAINT ck_db_connection_type  CHECK (db_type IN ('POSTGRESQL','MYSQL','MARIADB','ORACLE'))
);
CREATE INDEX IF NOT EXISTS idx_db_connection_repo ON com.db_connection(repo_catalog_id);

-- 세션은 id만 보관(스펙 §4 — 비밀번호/암호문을 세션 행마다 복제하지 않는다). 인터뷰 세션은 항상 [].
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS db_connection_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
