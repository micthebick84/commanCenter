-- 정정: V24__db_connection.sql:25의 "인터뷰 세션은 항상 []" 주석은 낡았다 — 작업 인터뷰 세션은 승인 때 작업이 고른 연결 id를 복사해 받는다
-- (V28, InterviewService.createForTask). 적용이 끝난 V24는 Flyway 체크섬 때문에 고치지 않고 여기에 바로잡는다.
-- 작업 타입 (스펙 2026-11-03 §4.2·§7). NULL = 타입 없음 — 기존 행은 NULL로 두고 지금 동작 그대로.
-- 값: NEW_FEATURE(신규 개발) | FEATURE_CHANGE(기능 수정) | BUG_FIX(버그 픽스) | DB_WORK(DB 작업).
-- 제약에 이름을 붙인다(V29의 ck_task_implementation_mode와 같은 방식) — 이름 없는 인라인 CHECK면
-- 모르는 값을 거절한 오류 메시지에서 어느 제약인지 알 수 없다.
ALTER TABLE com.task ADD COLUMN task_type VARCHAR(20), ADD CONSTRAINT ck_task_task_type CHECK (task_type IS NULL OR task_type IN ('NEW_FEATURE','FEATURE_CHANGE','BUG_FIX','DB_WORK'));

-- ── 구현 단계 개발 DB 쓰기 (스펙 2026-11-03 §6.1·§6.3·§6.5·§7) ─────────────────────────────────────────────
-- 전역 스위치(app.db-write.enabled)가 꺼져 있으면 아래 칸·표가 있어도 쓰기는 일어나지 않는다. 기존 행은 모두 "쓰기 안 함"으로 둔다.

-- 연결 단위 쓰기 허용. 레포 공용(REPO) 연결에만 둘 수 있다 — 개인(USER) 연결은 소유자가 접속 대상을 언제든 바꿀 수 있고
-- 관리자가 볼 수도 없다(§6.1 조건 4). 서비스가 막아도 DB가 한 번 더 막는다.
ALTER TABLE com.db_connection
    ADD COLUMN allow_write BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_db_connection_allow_write_repo CHECK (allow_write = false OR scope = 'REPO');

-- 레포별 SQL 스크립트 폴더(비면 기본 db/symphony)와 자동 마이그레이션 표시(§6.6·§6.8).
ALTER TABLE com.repo_catalog
    ADD COLUMN db_script_dir         VARCHAR(255),
    ADD COLUMN db_migration_managed  BOOLEAN NOT NULL DEFAULT false;

-- 토큰이 발급될 때 쓰기가 허가됐는지(§6.1 조건 6 — 쓰기를 지원하는 워커가 받은 구현 단계 토큰만). 실행 시점에 다시 확인한다.
ALTER TABLE com.task_db_mcp_token
    ADD COLUMN write_granted BOOLEAN NOT NULL DEFAULT false;

-- 그 작업이 새로 만든 테이블·뷰·시퀀스 (§6.3). 소유 판단은 이 기록만 본다.
-- 연결을 지우면 같이 지운다(옛 소유 기록이 다른 DB의 같은 이름 객체에 적용되지 않게). 작업을 지우면 기록도 지운다.
CREATE TABLE com.task_db_object (
    id             BIGSERIAL PRIMARY KEY,
    connection_id  BIGINT       NOT NULL REFERENCES com.db_connection(id) ON DELETE CASCADE,
    schema_name    VARCHAR(128) NOT NULL,
    object_name    VARCHAR(128) NOT NULL,
    object_type    VARCHAR(20)  NOT NULL,
    task_id        BIGINT       NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_task_db_object_type CHECK (object_type IN ('TABLE','VIEW','SEQUENCE')),
    CONSTRAINT uq_task_db_object UNIQUE (connection_id, schema_name, object_name, object_type)
);
CREATE INDEX idx_task_db_object_connection ON com.task_db_object(connection_id);

-- 쓰기 SQL 기록 (§6.5). 거부·승인 대기·실패도 남긴다.
-- connection_name은 기록 시점의 연결 이름 — 연결을 지운 뒤(connection_id → NULL)에도 표시하기 위해 따로 둔다.
-- fix_request_id: 수정 회차가 attempt를 같이 쓰므로(PR #122) 어느 회차의 기록인지 구분한다. 기록용이라 FK는 두지 않는다.
-- classification: EXECUTED 바로 실행 · DEFERRED 승인 대기 · DISMISSED 실행 안 함 · REJECTED 거부 · FAILED 실패.
-- executed_at·executed_by: 승인 대기 행을 관리자가 실행하거나 실행 안 함으로 바꾼 시각·사람(세션이 바로 실행한 행은 executed_by가 NULL).
CREATE TABLE com.task_db_write_log (
    id               BIGSERIAL PRIMARY KEY,
    task_id          BIGINT       NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    connection_id    BIGINT       REFERENCES com.db_connection(id) ON DELETE SET NULL,
    connection_name  VARCHAR(200) NOT NULL,
    attempt          INTEGER      NOT NULL,
    fix_request_id   BIGINT,
    sql_text         TEXT         NOT NULL,
    classification   VARCHAR(20),
    reason           TEXT,
    affected_rows    BIGINT,
    error            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    executed_at      TIMESTAMPTZ,
    executed_by      VARCHAR(100),
    CONSTRAINT ck_task_db_write_log_classification
        CHECK (classification IN ('EXECUTED','DEFERRED','DISMISSED','REJECTED','FAILED'))
);
CREATE INDEX idx_task_db_write_log_task ON com.task_db_write_log(task_id, created_at);
