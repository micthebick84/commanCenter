-- V41: 인터뷰 목업 — 인터뷰 중 에이전트가 만든 화면 목업을 세션에 쌓고, 계획 확정 때 고른 목업을 작업(구현)에 넘긴다.
--      (docs/superpowers/specs/2026-11-03-interview-mockups-design.md §5·§7)

-- 1) 목업 표. 세션 행은 지우지 않으므로(인터뷰 체크아웃만 끝난 지 7일 뒤 정리) 목업 행도 남고, 세션을 지우면 함께 지운다.
CREATE TABLE com.interview_mockup (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
    n           INTEGER NOT NULL,            -- 세션 안에서 올라온 순서 번호(버전마다 새로 매김). 버린 행의 번호는 빈 번호로 남는다(재사용 안 함)
    name        VARCHAR(64) NOT NULL,        -- 에이전트가 정한 짧은 이름 ^[a-z0-9][a-z0-9-]{0,63}$ — 같은 이름으로 다시 올리면 새 버전
    version     INTEGER NOT NULL,            -- 이름별 1부터
    title       VARCHAR(80) NOT NULL,        -- 줄바꿈·제어문자를 공백으로 바꾼 한 줄 제목
    description VARCHAR(120),                -- 카드 제목 아래 설명 한 줄(선택)
    html        TEXT NOT NULL,               -- 한 건 200KB(UTF-8 바이트)까지 — API가 검사. 목록·SSE에는 싣지 않는다
    size_bytes  INTEGER NOT NULL,            -- html의 UTF-8 바이트 수
    turn_seq    INTEGER,                     -- NULL = 아직 어느 턴(질문·계획)에도 안 묶임(턴 도중 올라온 목업). 그 턴이 끝날 때 API가 묶는다
    turn_kind   VARCHAR(20),                 -- 묶은 턴의 종류(question | design). 계획 턴은 말풍선이 없어 화면이 이 값으로 알아본다
    discarded   BOOLEAN NOT NULL DEFAULT false,  -- 끊긴 턴(재기동 재큐·턴 실패)에서 안 묶인 채 남은 목업을 버렸다는 표시 — 목록·묶기·상한·확정 검증에서 뺀다
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_interview_mockup_n UNIQUE (session_id, n)
);

-- 이름·버전은 버리지 않은 행에만 유일하게 건다(설계와 다름 — 설계는 전체 행). 끊긴 턴의 목업을 버린 뒤 같은 이름을 다시 올리면
-- v1이 다시 가능해야 화면에 "(v2)"로만 보이는 일이 없다. 제약 대신 부분 유일 인덱스라 이름을 붙여 오류 메시지로 알아본다.
CREATE UNIQUE INDEX uq_interview_mockup_name_version
    ON com.interview_mockup (session_id, name, version) WHERE NOT discarded;

-- 2) 작업이 구현에 넘기기로 한 목업 (§7). 확정 때 관리자가 고른 interview_mockup id — 세션 행이 남으므로 복사하지 않고 id만 참조한다.
ALTER TABLE com.task ADD COLUMN implementation_mockup_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 3) 계획이 가리켰는데 넘기지 않은 목업 번호(n) 목록 — 확정 때 API가 "계획이 가리킨 번호 중 그 세션에 실제로 있는데 안 넘긴 것"으로 채운다.
--    구현 프롬프트가 "계획의 목업 N 중 전달되지 않은 것은 찾지 말고 계획 글만 따르라"를 넣는 근거. 기존 행은 모두 빈 목록.
ALTER TABLE com.task ADD COLUMN implementation_mockup_excluded JSONB NOT NULL DEFAULT '[]'::jsonb;
