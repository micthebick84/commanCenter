package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

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

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private OffsetDateTime updatedAt;

    public static Task create(String githubRepo, String githubBranch, String title,
                              String description, Long requesterId, int maxRetry) {
        Task t = new Task();
        t.githubRepo = githubRepo;
        t.githubBranch = githubBranch == null || githubBranch.isBlank() ? "main" : githubBranch;
        t.title = title;
        t.description = description;
        t.requesterId = requesterId;
        t.status = TaskStatus.PENDING;
        t.retryCount = 0;
        t.maxRetry = maxRetry;
        OffsetDateTime now = OffsetDateTime.now();
        t.createdAt = now;
        t.updatedAt = now;
        return t;
    }

    public boolean isOwnedBy(Long userId) {
        return requesterId.equals(userId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
