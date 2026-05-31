-- V10: 배포 로그 청크 (스트리밍 중 임시 보존). 종료 시 deploy_log로 통합 후 DELETE → 진행중 task만 잔존.
CREATE TABLE IF NOT EXISTS com.task_deploy_log_chunk (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT      NOT NULL,
    seq        INT         NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_deploy_log_chunk_task_seq
    ON com.task_deploy_log_chunk(task_id, seq);
