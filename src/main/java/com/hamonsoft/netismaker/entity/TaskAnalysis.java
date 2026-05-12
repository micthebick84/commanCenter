package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 분석 산출물 (1 task : 1 analysis).
 * 분석실패의 경우 markdown_result에 빈 문자열 + failure_reason에 task 쪽 기록.
 */
@Entity
@Table(name = "task_analysis", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskAnalysis {

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "markdown_result", nullable = false, columnDefinition = "TEXT")
    private String markdownResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subtasks_json", nullable = false, columnDefinition = "jsonb")
    private String subtasksJson;

    @Column(name = "claude_log", columnDefinition = "TEXT")
    private String claudeLog;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(nullable = false)
    @Setter
    private boolean approved;

    @Column(name = "approved_by", length = 20)
    @Setter
    private String approvedBy;

    @Column(name = "approved_at")
    @Setter
    private OffsetDateTime approvedAt;

    @Column(name = "completed_at", nullable = false)
    private OffsetDateTime completedAt;

    public static TaskAnalysis create(long taskId, String markdownResult, String subtasksJson,
                                      String claudeLog, Long durationMs) {
        TaskAnalysis a = new TaskAnalysis();
        a.taskId = taskId;
        a.markdownResult = markdownResult;
        a.subtasksJson = subtasksJson == null ? "[]" : subtasksJson;
        a.claudeLog = claudeLog;
        a.durationMs = durationMs;
        a.approved = false;
        a.completedAt = OffsetDateTime.now();
        return a;
    }
}
