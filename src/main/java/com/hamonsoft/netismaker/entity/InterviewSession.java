package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 *  인터뷰 세션 큐 메인 엔티티. DESIGN(대화형 분석) §7 com.interview_session과 1:1 매핑.
 *
 *  Task와 동일 패턴: 상태 전이는 InterviewService에서만. 직접 setStatus 호출 금지.
 *  Task 상태머신(TaskStatus)은 변경하지 않음 — 인터뷰 상태는 전부 여기에.
 */
@Entity
@Table(name = "interview_session", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false, length = 20)
    private String requesterId;

    @Column(name = "github_repo", nullable = false, length = 255)
    private String githubRepo;

    @Column(name = "github_branch", nullable = false, length = 255)
    private String githubBranch;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 30)
    @Convert(converter = InterviewStatusConverter.class)
    @Setter   // 서비스 레이어에서만 변경
    private InterviewStatus status;

    /** 세션 종류. QUESTION은 플랜/등록 전이 불가, 인터뷰 서비스가 Q&A 모드로 실행 (스펙 2026-08-30 §4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private InterviewKind kind = InterviewKind.INTERVIEW;

    /** SDK 세션 resume 키. null이면 brainstorming 신규 시작, 있으면 resume. */
    @Column(name = "claude_session_id", length = 100)
    @Setter
    private String claudeSessionId;

    /**
     * resume cwd = 레포 체크아웃 경로. 첫 claim 시 InterviewService가 결정/영속화,
     * 이후 모든 resume claim에 동일 값 전달. Claude Code 세션 스토어가 cwd에 종속이라
     * (스파이크 02) resume 워커는 반드시 동일 cwd를 써야 한다. 단일 호스트/공유 FS 전제.
     */
    @Column(name = "work_dir", length = 500)
    @Setter
    private String workDir;

    @Column(name = "worker_id", length = 50)
    @Setter
    private String workerId;

    @Column(name = "claimed_at")
    @Setter
    private OffsetDateTime claimedAt;

    /** clone HEAD sha. 워커 claim 후 set. */
    @Column(name = "commit_sha", length = 40)
    @Setter
    private String commitSha;

    /** brainstorming / writing-plans. */
    @Column(name = "current_phase", length = 20)
    @Setter
    private String currentPhase;

    @Column(name = "total_cost_usd", nullable = false)
    @Setter
    private BigDecimal totalCostUsd = BigDecimal.ZERO;

    /** 인터뷰별 추가 MCP 스펙 스냅샷 (기존 freeze 패턴, Task.mcpsExtra와 동일). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mcps_extra", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<TaskMcpSpec> mcpsExtra = new ArrayList<>();

    /** 작업 등록 시 선택된 Claude 모델. 워커가 claude --model에 사용.
     *  기본값은 ModelEffortPolicy.DEFAULT_MODEL과 반드시 일치해야 한다(둘 다 바꿀 것). */
    @Column(name = "model", nullable = false, length = 64)
    @Setter
    private String model = "claude-opus-5";

    /** 추론 effort (low/medium/high/xhigh/max). 워커가 claude --effort에 사용. */
    @Column(name = "effort", nullable = false, length = 16)
    @Setter
    private String effort = "high";

    @Column(name = "git_url", columnDefinition = "TEXT")
    @Setter
    private String gitUrl;

    @Column(name = "repo_alias", length = 100)
    @Setter
    private String repoAlias;

    @Column(name = "repo_catalog_id")
    @Setter
    private Long repoCatalogId;

    /** 등록 시 생성된 Task id. PLAN_READY → REGISTERED 전이에서 set. */
    @Column(name = "task_id")
    @Setter
    private Long taskId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private OffsetDateTime updatedAt;

    /** idle TTL 만료 판정 기준. 질문 emit / 답변 도착 시 갱신. */
    @Column(name = "last_activity_at", nullable = false)
    @Setter
    private OffsetDateTime lastActivityAt;

    public static InterviewSession create(String githubRepo, String githubBranch, String title,
                                          String description, String requesterId,
                                          List<TaskMcpSpec> mcpsExtra, String model, String effort) {
        InterviewSession s = new InterviewSession();
        s.githubRepo = githubRepo;
        s.githubBranch = githubBranch == null || githubBranch.isBlank() ? "main" : githubBranch;
        s.title = title;
        s.description = description;
        s.requesterId = requesterId;
        s.status = InterviewStatus.QUEUED;
        s.totalCostUsd = BigDecimal.ZERO;
        s.mcpsExtra = mcpsExtra == null ? new ArrayList<>() : mcpsExtra;
        // blank이면 필드 초기자 기본값(s.model/s.effort) 유지 — 리터럴 중복 제거.
        if (model != null && !model.isBlank()) s.model = model;
        if (effort != null && !effort.isBlank()) s.effort = effort;
        OffsetDateTime now = OffsetDateTime.now();
        s.createdAt = now;
        s.updatedAt = now;
        s.lastActivityAt = now;
        return s;
    }

    /**
     * 질문 세션(Q&A). taskId 없음, description = 질문 본문. 나머지 컬럼은 인터뷰와 의미 동일
     * (레포/브랜치/모델/effort/MCP 스냅샷) — claim 응답 전파 경로도 그대로.
     */
    public static InterviewSession createQuestion(String githubRepo, String githubBranch, String title,
                                                  String question, String requesterId,
                                                  List<TaskMcpSpec> mcpsExtra, String model, String effort) {
        InterviewSession s = create(githubRepo, githubBranch, title, question, requesterId,
                mcpsExtra, model, effort);
        s.kind = InterviewKind.QUESTION;
        return s;
    }

    public boolean isQuestion() {
        return kind == InterviewKind.QUESTION;
    }

    public boolean isOwnedBy(String userId) {
        return requesterId.equals(userId);
    }
}
