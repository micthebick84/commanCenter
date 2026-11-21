-- 질문 세션 대화 중 제안(스펙 2026-11-22 §6.2): 답변 중 Claude가 남긴 DB 연결·참조 레포 제안과 그 결과.
CREATE TABLE com.question_suggestion (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
    turn_seq         INT NOT NULL,
    kind             VARCHAR(20) NOT NULL,
    db_connection_id BIGINT REFERENCES com.db_connection(id) ON DELETE SET NULL,
    repo_catalog_id  BIGINT REFERENCES com.repo_catalog(id) ON DELETE SET NULL,
    reason           VARCHAR(200) NOT NULL,
    targets          JSONB NOT NULL DEFAULT '[]',
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decided_at       TIMESTAMPTZ,
    decided_by       VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_question_suggestion_kind CHECK (kind IN ('DB', 'REF_REPO')),
    CONSTRAINT ck_question_suggestion_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DISMISSED', 'SUPERSEDED'))
);
CREATE INDEX ix_question_suggestion_session ON com.question_suggestion(session_id, id);
