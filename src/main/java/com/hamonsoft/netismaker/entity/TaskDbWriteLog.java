package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 구현 단계 쓰기 SQL 기록 (스펙 2026-11-03 §6.5) — 바로 실행·승인 대기·거부·실패 모두 남긴다.
 * connectionName은 기록 시점의 연결 이름이라 연결을 지워 connectionId가 NULL이 된 뒤에도 보여 줄 수 있다.
 * attempt·fixRequestId는 그 시점 작업의 구현 시도 번호·수정 회차 id(수정 회차가 attempt를 같이 쓰기 때문).
 * 이 객체는 sqlText(전문)를 담는다 — toString에 싣지 않는다.
 */
@Entity
@Table(name = "task_db_write_log", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDbWriteLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 연결을 지우면 NULL이 된다(ON DELETE SET NULL). */
    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "connection_name", nullable = false, length = 200)
    private String connectionName;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "fix_request_id")
    private Long fixRequestId;

    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WriteClassification classification;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "affected_rows")
    private Long affectedRows;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** 관리자가 승인 대기를 실행하거나 실행 안 함으로 바꾼 시각, 세션이 바로 실행한 행은 실행을 마친 시각. */
    @Column(name = "executed_at")
    private OffsetDateTime executedAt;

    /** 승인 대기를 처리한 관리자. 세션이 바로 실행한 행은 NULL. */
    @Column(name = "executed_by", length = 100)
    private String executedBy;

    public static TaskDbWriteLog create(Long taskId, Long connectionId, String connectionName, int attempt,
                                        Long fixRequestId, String sqlText, WriteClassification classification) {
        TaskDbWriteLog l = new TaskDbWriteLog();
        l.taskId = taskId;
        l.connectionId = connectionId;
        l.connectionName = connectionName;
        l.attempt = attempt;
        l.fixRequestId = fixRequestId;
        l.sqlText = sqlText;
        l.classification = classification;
        l.createdAt = OffsetDateTime.now();
        return l;
    }

    @Override
    public String toString() {
        return "TaskDbWriteLog{id=" + id + ", taskId=" + taskId + ", connectionId=" + connectionId
                + ", attempt=" + attempt + ", classification=" + classification + "}";
    }
}
