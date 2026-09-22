-- V23: 질문 세션 — 대화 중 MCP 변경 + 채팅 첨부파일
--      (docs/superpowers/specs/2026-09-13-question-mcp-attachments-design.md §4)

-- 1) 선택한 MCP 카탈로그 id 스냅샷. mcps_extra(런타임 계약)와 별개 — 프론트 픽커 시딩 + applyMcpChange의 집합 비교용.
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS mcp_catalog_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 2) 질문 세션 첨부 메타 (파일 본체는 app.attachment.dir 하위 question-{sid}/{create|turnSeq}/{순번}-{이름}).
--    task_attachment 미재사용: task_id NOT NULL + 등록 1회 불변 모델이라 메시지 단위 추가와 맞지 않음.
CREATE TABLE IF NOT EXISTS com.question_attachment (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
    turn_seq            INTEGER,                 -- NULL = 등록 시(킥오프), 아니면 그 답변 turn의 seq
    original_filename   VARCHAR(255) NOT NULL,
    stored_path         VARCHAR(500) NOT NULL,   -- 루트 기준 상대경로
    content_type        VARCHAR(100),
    size_bytes          BIGINT NOT NULL,
    extracted_text_path VARCHAR(500),            -- Tika sidecar(.txt) 상대경로. 오피스 문서 + 추출 성공 시만
    uploaded_by         VARCHAR(20) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_question_attachment_session ON com.question_attachment(session_id);
