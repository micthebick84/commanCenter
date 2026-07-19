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

    /** 작업 등록 시 선택된 Claude 모델. 워커가 claude --model에 사용. */
    @Column(name = "model", nullable = false, length = 64)
    @Setter
    private String model = "claude-opus-4-8";

    /** 추론 effort (low/medium/high/xhigh/max). 워커가 claude --effort에 사용. */
    @Column(name = "effort", nullable = false, length = 16)
    @Setter
    private String effort = "high";

    /** 배포 시 컨테이너에 주입할 환경변수 (key/value/secret). 배포 다이얼로그에서 set. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env_vars", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<EnvVar> envVars = new ArrayList<>();

    /** 디자인 단계 포함 여부 (등록 시 토글). true면 분석 승인 시 구현대기 대신 디자인대기로. */
    @Column(name = "design_requested", nullable = false)
    @Setter
    private boolean designRequested;

    /** 구현 결과 PR URL. PR_CREATED 시 set. */
    @Column(name = "pr_url", length = 500)
    @Setter
    private String prUrl;

    @Column(name = "pr_number")
    @Setter
    private Integer prNumber;

    /** 구현 시 푸시한 브랜치명 (예: netismaker/task-12-add-rbac). */
    @Column(name = "head_branch", length = 255)
    @Setter
    private String headBranch;

    @Column(name = "head_sha", length = 40)
    @Setter
    private String headSha;

    /** 구현 시 claude/git/gh 출력 합본. 디버그 + admin 검토용. */
    @Column(name = "implementation_log", columnDefinition = "TEXT")
    @Setter
    private String implementationLog;

    /** 작업 등록 시 선택된 레포 카탈로그의 전체 Git URL 스냅샷. */
    @Column(name = "git_url", columnDefinition = "TEXT")
    @Setter
    private String gitUrl;

    /** 작업 등록 시 선택된 레포 카탈로그의 한글 별칭 스냅샷 (목록/상세 표시용). */
    @Column(name = "repo_alias", length = 100)
    @Setter
    private String repoAlias;

    /** 선택된 레포 카탈로그 id (편의 FK — 소스 오브 트루스는 스냅샷). */
    @Column(name = "repo_catalog_id")
    @Setter
    private Long repoCatalogId;

    /** 배포 접속 URL. DEPLOYED 시 set. 예: http://localhost:19000 */
    @Column(name = "deploy_url", length = 500)
    @Setter
    private String deployUrl;

    /** 실행 중인 docker 컨테이너 ID (또는 이름 netis-task-{id}). */
    @Column(name = "deploy_container_id", length = 100)
    @Setter
    private String deployContainerId;

    /** 호스트에 게시된 포트. */
    @Column(name = "deploy_host_port")
    @Setter
    private Integer deployHostPort;

    /** 빌드된 이미지 태그 netis-task-{id}:{shortsha}. */
    @Column(name = "deploy_image", length = 200)
    @Setter
    private String deployImage;

    @Column(name = "deployed_at")
    @Setter
    private OffsetDateTime deployedAt;

    /** docker build/run 출력 tail (실패 디버깅 + 성공 기록). */
    @Column(name = "deploy_log", columnDefinition = "TEXT")
    @Setter
    private String deployLog;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private OffsetDateTime updatedAt;

    public static Task create(String githubRepo, String githubBranch, String title,
                              String description, String requesterId, int maxRetry,
                              List<TaskMcpSpec> mcpsExtra, String model, String effort) {
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
        // blank이면 필드 초기자 기본값(t.model/t.effort) 유지 — 리터럴 중복 제거.
        if (model != null && !model.isBlank()) t.model = model;
        if (effort != null && !effort.isBlank()) t.effort = effort;
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
