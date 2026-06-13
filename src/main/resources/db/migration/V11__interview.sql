-- V11: 대화형 분석 (Conversational Analysis) — 인터뷰 세션 3테이블 (com 스키마)
-- DESIGN(대화형 분석) §7 그대로. status는 CHECK 제약 없는 VARCHAR(30) (기존 컨벤션).
-- Task 상태머신은 변경하지 않음 — 인터뷰 상태는 전부 interview_session.status에.

-- 인터뷰 세션: 큐 + 클레임 (com.task 미러)
CREATE TABLE IF NOT EXISTS com.interview_session (
    id                BIGSERIAL PRIMARY KEY,
    requester_id      VARCHAR(20)  NOT NULL REFERENCES com."user"(user_id),
    github_repo       VARCHAR(255) NOT NULL,
    github_branch     VARCHAR(255) NOT NULL DEFAULT 'main',
    title             VARCHAR(500) NOT NULL,
    description       TEXT         NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT '인터뷰대기',
                      -- 인터뷰대기 | 인터뷰중 | 입력대기 | 플랜완료 | 등록됨 | 취소됨 | 만료됨 | 인터뷰실패
    claude_session_id VARCHAR(100),
    work_dir          VARCHAR(500),
                      -- resume cwd = 레포 체크아웃 경로. 첫 claim 시 Java가 결정/저장,
                      -- 이후 resume claim마다 동일 값 반환 (Claude Code 세션 스토어가 cwd 종속).
    worker_id         VARCHAR(50),
    claimed_at        TIMESTAMPTZ,
    commit_sha        VARCHAR(40),
    current_phase     VARCHAR(20),
                      -- brainstorming | writing-plans
    total_cost_usd    NUMERIC(12,6) NOT NULL DEFAULT 0,
    mcps_extra        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    task_id           BIGINT       REFERENCES com.task(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_activity_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 워커 claim 큐: QUEUED를 last_activity_at ASC로 (FOR UPDATE SKIP LOCKED 대상)
CREATE INDEX IF NOT EXISTS idx_interview_queued ON com.interview_session(last_activity_at)
    WHERE status = '인터뷰대기';
-- 요청자별 active 카운트 + ACL 조회
CREATE INDEX IF NOT EXISTS idx_interview_requester ON com.interview_session(requester_id, status);
-- stale 회수: in-flight(인터뷰중) 스캔
CREATE INDEX IF NOT EXISTS idx_interview_running ON com.interview_session(claimed_at)
    WHERE status = '인터뷰중';

-- 추가전용 Q&A 로그 (com.task_status_history 미러)
CREATE TABLE IF NOT EXISTS com.interview_turn (
    id           BIGSERIAL PRIMARY KEY,
    session_id   BIGINT      NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
    seq          INT         NOT NULL,
    role         VARCHAR(20) NOT NULL,
                 -- assistant | user | system
    kind         VARCHAR(20) NOT NULL,
                 -- question | answer | design | gate | note
    content      TEXT        NOT NULL,
    reply_to_seq INT,
                 -- user answer가 응답하는 question 턴의 seq. idempotency 키 + UI 스레딩.
                 --   question/design/note 턴은 null.
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_interview_turn_session_seq
    ON com.interview_turn(session_id, seq);

-- 1:1 위성 플랜 (com.task_analysis 미러)
CREATE TABLE IF NOT EXISTS com.interview_plan (
    session_id      BIGINT PRIMARY KEY REFERENCES com.interview_session(id) ON DELETE CASCADE,
    design_markdown TEXT,
    plan_markdown   TEXT,
    plan_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
    duration_ms     BIGINT,
    total_cost_usd  NUMERIC(12,6),
    completed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
