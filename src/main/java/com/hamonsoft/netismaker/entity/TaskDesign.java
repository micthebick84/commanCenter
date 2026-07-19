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
 * 디자인 산출물 (1 task : 1 design). design_requested=true 작업의 디자인 구간 결과.
 * 반려 시 row를 유지하고 feedback_history/reject_count만 누적, 재생성 결과로 덮어쓴다.
 * mockup_files 형식: [{"path","title","html"}] — DB가 진실원본 (Claude Design 업로드는 비치명).
 */
@Entity
@Table(name = "task_design", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDesign {

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "design_markdown", nullable = false, columnDefinition = "TEXT")
    @Setter
    private String designMarkdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mockup_files", nullable = false, columnDefinition = "jsonb")
    @Setter
    private String mockupFilesJson;

    @Column(name = "design_project_id", length = 100)
    @Setter
    private String designProjectId;

    @Column(name = "design_url", length = 500)
    @Setter
    private String designUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feedback_history", nullable = false, columnDefinition = "jsonb")
    @Setter
    private String feedbackHistoryJson = "[]";

    @Column(name = "reject_count", nullable = false)
    @Setter
    private int rejectCount;

    @Column(nullable = false)
    @Setter
    private boolean approved;

    @Column(name = "approved_by", length = 20)
    @Setter
    private String approvedBy;

    @Column(name = "approved_at")
    @Setter
    private OffsetDateTime approvedAt;

    @Column(name = "claude_log", columnDefinition = "TEXT")
    @Setter
    private String claudeLog;

    @Column(name = "duration_ms")
    @Setter
    private Long durationMs;

    @Column(name = "completed_at", nullable = false)
    @Setter
    private OffsetDateTime completedAt;

    public static TaskDesign create(long taskId, String designMarkdown, String mockupFilesJson,
                                    String designProjectId, String designUrl,
                                    String claudeLog, Long durationMs) {
        TaskDesign d = new TaskDesign();
        d.taskId = taskId;
        d.designMarkdown = designMarkdown;
        d.mockupFilesJson = mockupFilesJson == null ? "[]" : mockupFilesJson;
        d.designProjectId = designProjectId;
        d.designUrl = designUrl;
        d.claudeLog = claudeLog;
        d.durationMs = durationMs;
        d.feedbackHistoryJson = "[]";
        d.rejectCount = 0;
        d.approved = false;
        d.completedAt = OffsetDateTime.now();
        return d;
    }
}
