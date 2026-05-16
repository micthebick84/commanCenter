package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 *  작업 큐 메인 엔티티. DESIGN §7 com.task와 1:1 매핑.
 *
 *  상태 전이는 TaskService에서 일관 처리. 직접 setStatus 호출 금지.
 */
@Entity
@Table(name = "task", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_repo", nullable = false, length = 255)
    private String githubRepo;

    @Column(name = "github_branch", nullable = false, length = 255)
    private String githubBranch;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 30)
    @Convert(converter = TaskStatusConverter.class)
    @Setter   // 서비스 레이어에서만 변경
    private TaskStatus status;

    @Column(name = "requester_id", nullable = false, length = 20)
    private String requesterId;

    @Column(name = "retry_count", nullable = false)
    @Setter
    private int retryCount;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry;

    @Column(name = "worker_id", length = 50)
    @Setter
    private String workerId;

    @Column(name = "claimed_at")
    @Setter
    private OffsetDateTime claimedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    @Setter
    private String failureReason;

    @Column(name = "deleted_at")
    @Setter
    private OffsetDateTime deletedAt;

    /** 작업 등록 시 선택된 추가 MCP 스펙 스냅샷. 워커가 claude -p에 주입. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mcps_extra", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<TaskMcpSpec> mcpsExtra = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private OffsetDateTime updatedAt;

    public static Task create(String githubRepo, String githubBranch, String title,
                              String description, String requesterId, int maxRetry,
                              List<TaskMcpSpec> mcpsExtra) {
        Task t = new Task();
        t.githubRepo = githubRepo;
        t.githubBranch = githubBranch == null || githubBranch.isBlank() ? "main" : githubBranch;
        t.title = title;
        t.description = description;
        t.requesterId = requesterId;
        t.status = TaskStatus.PENDING;
        t.retryCount = 0;
        t.maxRetry = maxRetry;
        t.mcpsExtra = mcpsExtra == null ? new ArrayList<>() : mcpsExtra;
        OffsetDateTime now = OffsetDateTime.now();
        t.createdAt = now;
        t.updatedAt = now;
        return t;
    }

    public boolean isOwnedBy(String userId) {
        return requesterId.equals(userId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
