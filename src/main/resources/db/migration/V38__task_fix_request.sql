-- 배포 후 수정 요청 — 같은 브랜치에서 이어 구현 (스펙 2026-11-03 §4·§6·§8).
-- 작업이 지금 진행 중인 수정 요청(회차)의 id. NULL = 수정 회차 없음. 기록용이라 FK는 두지 않는다(순환 참조 방지).
ALTER TABLE com.task
    ADD COLUMN active_fix_request_id BIGINT;

-- 수정 요청 기록. round는 승인 때 확정(승인 전·반려·철회는 NULL), attempt는 회차 안의 구현 시도 번호.
-- status: PENDING | APPROVED | REJECTED | WITHDRAWN, outcome: DEPLOYED | DEPLOY_FAILED | ABORTED (회차가 닫힌 뒤).
-- from_status는 요청 시점의 작업 상태(TaskStatus.dbValue — 반려·철회 때 되돌아갈 직전 상태).
CREATE TABLE com.task_fix_request (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT       NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    round               INTEGER,
    body                TEXT         NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    implementation_mode VARCHAR(10),
    from_status         VARCHAR(30)  NOT NULL,
    requested_by        VARCHAR(100) NOT NULL,
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_by          VARCHAR(100),
    decided_at          TIMESTAMPTZ,
    decision_reason     TEXT,
    attempt             INTEGER      NOT NULL DEFAULT 1,
    outcome             VARCHAR(20),
    closed_at           TIMESTAMPTZ
);
CREATE INDEX idx_task_fix_request_task ON com.task_fix_request(task_id, id DESC);
