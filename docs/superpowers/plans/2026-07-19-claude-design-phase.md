# Claude Design 디자인 구간 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 분석 승인 후 → 구현 전 사이에 opt-in 디자인 구간을 추가한다. 워커가 Claude Design 디자인 시스템 기반 화면 목업을 자동 생성하고, 사용자가 netisMaker에서 승인/반려(최대 3회)한 뒤, 확정 디자인을 기준으로 구현한다.

**Architecture:** 배포 파이프라인 선례를 따르는 일급 구간: 새 `TaskStatus` 4개 + `Kind.DESIGN` + `processDesign()` + `com.task_design` 테이블. 워커 세션(claude -p) 안에서 DesignSync로 디자인 시스템 pull + 목업 업로드. 산출물 진실원본은 DB(`mockup_files` jsonb), Claude Design 업로드는 비치명.

**Tech Stack:** Spring Boot 3.4 (api/worker 프로파일), Flyway, JPA(H2 테스트), Nuxt3+Quasar, vitest.

**Spec:** `docs/superpowers/specs/2026-07-19-claude-design-phase-design.md`

## Global Constraints

- 마이그레이션은 **V16** (`V16__design_phase.sql`) — 스펙은 V15로 썼지만 V15는 이미 존재(queue_stats_deploy_lost). 신규 컬럼은 `NOT NULL DEFAULT ...` (CLAUDE.md 규칙).
- 스펙의 `POST /worker/tasks/{id}/design-result` 대신 **기존 `/worker/tasks/{id}/result` 재사용** (배포와 동일 패턴 — recordResult가 DESIGNING 분기 처리). 문서화된 스펙 정제.
- 한글 dbValue: 디자인대기/디자인중/디자인승인대기/디자인실패. 반려 한도 **3회**.
- 프론트 코드 스타일: single quote + 세미콜론 없음, 2-space (netisMaker/CLAUDE.md). `npm run lint-prettier` 전체 실행 금지.
- Java: Lombok, 기존 파일 컨벤션(한글 Javadoc) 유지.
- 커밋 메시지 한국어, 각 태스크마다 커밋.
- 백엔드 테스트: `./gradlew test` (H2). Testcontainers류 로컬 실행 금지(CI 전용 — 메모리 규칙).
- mockup_files JSON 형식(전 구간 공유 계약): `[{"path":"screens/main.html","title":"메인 화면","html":"<!doctype html>..."}]`

---

### Task 1: TaskStatus에 디자인 상태 4개 추가

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/TaskStatus.java`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/TaskStatusDesignTest.java`

**Interfaces:**
- Produces: `TaskStatus.DESIGN_PENDING("디자인대기")`, `DESIGNING("디자인중")`, `DESIGN_REVIEW("디자인승인대기")`, `DESIGN_FAILED("디자인실패")` — 이후 모든 태스크가 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusDesignTest {

    @Test
    void 디자인_상태_4개가_한글_dbValue와_왕복된다() {
        assertThat(TaskStatus.DESIGN_PENDING.dbValue()).isEqualTo("디자인대기");
        assertThat(TaskStatus.DESIGNING.dbValue()).isEqualTo("디자인중");
        assertThat(TaskStatus.DESIGN_REVIEW.dbValue()).isEqualTo("디자인승인대기");
        assertThat(TaskStatus.DESIGN_FAILED.dbValue()).isEqualTo("디자인실패");
        assertThat(TaskStatus.fromDb("디자인승인대기")).isEqualTo(TaskStatus.DESIGN_REVIEW);
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests TaskStatusDesignTest`. Expected: 컴파일 에러 (`DESIGN_PENDING` 없음).

- [ ] **Step 3: 구현** — `TaskStatus.java`의 enum 값에서 `CANCELLED("취소됨");` 직전(= `UNDEPLOYING("배포중지중"),` 다음 줄)에 추가:

```java
    DESIGN_PENDING("디자인대기"),
    DESIGNING("디자인중"),
    DESIGN_REVIEW("디자인승인대기"),
    DESIGN_FAILED("디자인실패"),
```

클래스 Javadoc(1~28행)의 상태도 갱신 — 구현 단계 다이어그램 아래에 추가:

```
 *  디자인 단계 (design_requested=true인 작업이 분석 승인 시 진입):
 *   [분석완료] ──(승인)──→ [디자인대기] ──(워커 claim)──→ [디자인중] ──┬→ [디자인승인대기]
 *                                                                      └→ [디자인실패] ──(retry)──→ [디자인대기]
 *   [디자인승인대기] ──(승인)──→ [구현대기]
 *   [디자인승인대기] ──(반려, 최대 3회)──→ [디자인대기]
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests TaskStatusDesignTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: TaskStatus 디자인 상태 4개 추가"`

---

### Task 2: V16 마이그레이션 + TaskDesign 엔티티 + Task/RepoCatalog 컬럼

**Files:**
- Create: `src/main/resources/db/migration/V16__design_phase.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/TaskDesign.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/TaskDesignRepository.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java` (designRequested 필드)
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java` (프로젝트 ID 2컬럼)
- Test: `src/test/java/com/hamonsoft/netismaker/repository/TaskDesignRepositoryTest.java`

**Interfaces:**
- Produces: `TaskDesign` (getter: getTaskId/getDesignMarkdown/getMockupFilesJson/getDesignProjectId/getDesignUrl/getFeedbackHistoryJson/getRejectCount/isApproved/...; static `TaskDesign.create(long taskId, String designMarkdown, String mockupFilesJson, String designProjectId, String designUrl, String claudeLog, Long durationMs)`), `TaskDesignRepository extends JpaRepository<TaskDesign, Long>`, `Task.isDesignRequested()/setDesignRequested(boolean)`, `RepoCatalogEntry.getDesignSystemProjectId()/getDesignOutputProjectId()` (+setter).

- [ ] **Step 1: 마이그레이션 작성** — `V16__design_phase.sql`:

```sql
-- V16: 디자인 구간 (분석 승인 후 → 구현 전, opt-in)
ALTER TABLE com.task ADD COLUMN design_requested boolean NOT NULL DEFAULT false;

ALTER TABLE com.repo_catalog ADD COLUMN design_system_project_id varchar(100);
ALTER TABLE com.repo_catalog ADD COLUMN design_output_project_id varchar(100);

CREATE TABLE com.task_design (
    task_id          bigint PRIMARY KEY REFERENCES com.task(id),
    design_markdown  text NOT NULL,
    mockup_files     jsonb NOT NULL DEFAULT '[]',
    design_project_id varchar(100),
    design_url       varchar(500),
    feedback_history jsonb NOT NULL DEFAULT '[]',
    reject_count     int NOT NULL DEFAULT 0,
    approved         boolean NOT NULL DEFAULT false,
    approved_by      varchar(20),
    approved_at      timestamptz,
    claude_log       text,
    duration_ms      bigint,
    completed_at     timestamptz NOT NULL
);

-- 큐 통계 뷰에 디자인 카운터 추가 (CREATE OR REPLACE는 컬럼 추가 거부 → DROP 재생성, V15와 동일 패턴)
DROP VIEW IF EXISTS com.task_queue_stats;
CREATE VIEW com.task_queue_stats AS
SELECT
    COUNT(*) FILTER (WHERE status = '작업대기' AND deleted_at IS NULL)              AS pending,
    COUNT(*) FILTER (WHERE status = '분석중'   AND deleted_at IS NULL)              AS in_progress,
    COUNT(*) FILTER (WHERE status = '분석완료' AND deleted_at IS NULL
                     AND NOT EXISTS (SELECT 1 FROM com.task_analysis a
                                     WHERE a.task_id = com.task.id AND a.approved)) AS awaiting_approval,
    COUNT(*) FILTER (WHERE status = '구현대기' AND deleted_at IS NULL)              AS approved,
    COUNT(*) FILTER (WHERE status = '구현중'   AND deleted_at IS NULL)              AS implementing,
    COUNT(*) FILTER (WHERE status = 'PR생성'   AND deleted_at IS NULL)              AS pr_created,
    COUNT(*) FILTER (WHERE status = '구현실패' AND deleted_at IS NULL)              AS implementation_failed,
    COUNT(*) FILTER (WHERE status = '분석실패' AND deleted_at IS NULL)              AS failed,
    COUNT(*) FILTER (WHERE status = '디자인대기' AND deleted_at IS NULL)            AS design_pending,
    COUNT(*) FILTER (WHERE status = '디자인중'   AND deleted_at IS NULL)            AS designing,
    COUNT(*) FILTER (WHERE status = '디자인승인대기' AND deleted_at IS NULL)        AS design_review,
    COUNT(*) FILTER (WHERE status = '디자인실패' AND deleted_at IS NULL)            AS design_failed,
    COUNT(*) FILTER (WHERE status = '배포대기' AND deleted_at IS NULL)              AS deploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중'   AND deleted_at IS NULL)              AS deploying,
    COUNT(*) FILTER (WHERE status = '배포완료' AND deleted_at IS NULL)              AS deployed,
    COUNT(*) FILTER (WHERE status = '배포실패' AND deleted_at IS NULL)              AS deploy_failed,
    COUNT(*) FILTER (WHERE status = '배포중단됨' AND deleted_at IS NULL)            AS deploy_lost,
    COUNT(*) FILTER (WHERE status = '배포중지대기' AND deleted_at IS NULL)          AS undeploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중지중'   AND deleted_at IS NULL)          AS undeploying,
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
```

주의: `QueueStatsService`/DTO가 뷰 컬럼을 SELECT하는 방식을 확인해서(`grep -rn "task_queue_stats" src/main`) 새 컬럼 4개(design_pending/designing/design_review/design_failed)를 DTO·쿼리에 추가한다. V9→V15 커밋(`git log --oneline --all -- '*queue_stats*'` 후 `git show`)이 뷰 컬럼 추가 시 함께 바꾼 파일 목록의 선례다 — 같은 파일들을 동일 패턴으로 수정.

- [ ] **Step 2: 실패하는 테스트 작성** — `TaskDesignRepositoryTest.java` (기존 repository 테스트와 동일한 어노테이션 구성을 `InterviewActiveQueryTest.java`에서 복사해 사용):

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskDesign;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// @DataJpaTest 구성/프로파일 어노테이션은 InterviewActiveQueryTest.java 상단과 동일하게 맞출 것
import static org.assertj.core.api.Assertions.assertThat;

class TaskDesignRepositoryTest /* extends 또는 어노테이션: InterviewActiveQueryTest와 동일 */ {

    @Autowired TaskDesignRepository designRepo;

    @Test
    void 저장_후_기본값과_필드가_유지된다() {
        TaskDesign d = TaskDesign.create(1L, "# 디자인",
                "[{\"path\":\"screens/main.html\",\"title\":\"메인\",\"html\":\"<html></html>\"}]",
                "proj-1", "https://claude.ai/design/proj-1", "log", 1000L);
        designRepo.saveAndFlush(d);
        TaskDesign found = designRepo.findById(1L).orElseThrow();
        assertThat(found.getRejectCount()).isZero();
        assertThat(found.isApproved()).isFalse();
        assertThat(found.getFeedbackHistoryJson()).isEqualTo("[]");
        assertThat(found.getDesignMarkdown()).isEqualTo("# 디자인");
    }
}
```

- [ ] **Step 3: 실패 확인** — Run: `./gradlew test --tests TaskDesignRepositoryTest`. Expected: 컴파일 에러.

- [ ] **Step 4: 구현** — `TaskDesign.java` (TaskAnalysis 미러):

```java
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
```

`TaskDesignRepository.java`:

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskDesign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskDesignRepository extends JpaRepository<TaskDesign, Long> {}
```

`Task.java` — `envVars` 필드 선언 다음에 추가:

```java
    /** 디자인 단계 포함 여부 (등록 시 토글). true면 분석 승인 시 구현대기 대신 디자인대기로. */
    @Column(name = "design_requested", nullable = false)
    @Setter
    private boolean designRequested;
```

`RepoCatalogEntry.java` — `enabled` 필드 다음에 추가 (클래스에 `@Setter` 이미 있음):

```java
    /** Claude Design 디자인 시스템 프로젝트 ID (입력). null이면 디자인 시 pull skip. */
    @Column(name = "design_system_project_id", length = 100)
    private String designSystemProjectId;

    /** Claude Design 목업 출력 프로젝트 ID. 워커가 최초 업로드 시 create_project 후 박제. */
    @Column(name = "design_output_project_id", length = 100)
    private String designOutputProjectId;
```

- [ ] **Step 5: 통과 확인** — Run: `./gradlew test --tests TaskDesignRepositoryTest`. Expected: PASS. 이어서 `./gradlew test` 전체 — queue stats 관련 기존 테스트가 깨지면 Step 1 주의사항의 DTO/쿼리 수정 누락.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: V16 디자인 구간 스키마 + TaskDesign 엔티티"`

---

### Task 3: 워커 DTO 확장 (Kind.DESIGN + 결과 필드)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseDesignTest.java`

**Interfaces:**
- Produces:
  - `WorkerTaskResponse` 레코드 끝에 5개 필드 추가: `String designMarkdown, String mockupFilesJson, String feedbackHistoryJson, String designSystemProjectId, String designOutputProjectId`
  - `Kind.DESIGN` enum 값
  - `WorkerTaskResponse.forDesign(Task t, TaskAnalysis a, TaskDesign prev, String designSystemProjectId, String designOutputProjectId)`
  - `WorkerTaskResponse.forImplementation(Task t, TaskAnalysis a, TaskDesign d)` — **시그니처 변경** (기존 2-arg 호출처: `WorkerService.claimNextTask` 1곳 — Task 4에서 수정; 다른 호출처는 `grep -rn "forImplementation" src/`로 확인해 `null` 추가)
  - `WorkerResultRequest` 레코드 끝에 4개 필드 추가: `String designMarkdown, String mockupFilesJson, String designProjectId, String designUrl` + 팩토리 `designReview(...)`, `designFailed(...)`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkerTaskResponseDesignTest {

    private Task task() {
        Task t = Task.create("owner/repo", "main", "제목", "설명", "user1", 3, null, null, null);
        return t;
    }

    @Test
    void forDesign은_kind_DESIGN과_프로젝트_ID를_담는다() {
        WorkerTaskResponse r = WorkerTaskResponse.forDesign(task(), null, null, "ds-proj", "out-proj");
        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DESIGN);
        assertThat(r.designSystemProjectId()).isEqualTo("ds-proj");
        assertThat(r.designOutputProjectId()).isEqualTo("out-proj");
        assertThat(r.feedbackHistoryJson()).isEqualTo("[]");
        assertThat(r.designMarkdown()).isNull();
    }

    @Test
    void designReview_팩토리는_필드를_채운다() {
        WorkerResultRequest r = WorkerResultRequest.designReview("w1", "# 디자인", "[]",
                "proj", "https://u", "log", 100L);
        assertThat(r.status()).isEqualTo(TaskStatus.DESIGN_REVIEW);
        assertThat(r.designMarkdown()).isEqualTo("# 디자인");
        assertThat(r.workerId()).isEqualTo("w1");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests WorkerTaskResponseDesignTest`. Expected: 컴파일 에러.

- [ ] **Step 3: 구현** — `WorkerTaskResponse.java`: 레코드 파라미터 끝(`String effort` 뒤)에 5개 필드 추가, `Kind`에 `DESIGN` 추가, import에 `TaskDesign` 추가. 기존 4개 팩토리의 생성자 호출 끝에 `null, null, null, null, null` 추가. 그리고:

```java
    /** 디자인 구간 claim 페이로드. prev가 있으면(반려 재실행) 이전 디자인 + 피드백 이력 동봉. */
    public static WorkerTaskResponse forDesign(Task t, TaskAnalysis a, TaskDesign prev,
                                               String designSystemProjectId,
                                               String designOutputProjectId) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DESIGN,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(), a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of(), t.getModel(), t.getEffort(),
                prev == null ? null : prev.getDesignMarkdown(),
                prev == null ? null : prev.getMockupFilesJson(),
                prev == null ? "[]" : prev.getFeedbackHistoryJson(),
                designSystemProjectId, designOutputProjectId);
    }
```

`forImplementation`은 3-arg로 변경 (승인된 디자인 동봉):

```java
    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(), a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of(), t.getModel(), t.getEffort(),
                d == null ? null : d.getDesignMarkdown(),
                d == null ? null : d.getMockupFilesJson(),
                null, null, null);
    }
```

레코드/Kind Javadoc에 `kind=DESIGN → 목업 생성 + Claude Design 업로드 (디자인 시스템/출력 프로젝트 ID 동봉)` 한 줄 추가.

`WorkerResultRequest.java`: 레코드 끝(`String deployLog` 뒤)에 `// 디자인` 주석과 4개 필드 추가. 기존 3개 팩토리 생성자 호출 끝에 `null, null, null, null` 추가. 새 팩토리:

```java
    /** 디자인 생성 성공 → 승인 대기 보고. */
    public static WorkerResultRequest designReview(String workerId, String designMarkdown,
                                                   String mockupFilesJson, String designProjectId,
                                                   String designUrl, String claudeLog, Long durationMs) {
        return new WorkerResultRequest(workerId, TaskStatus.DESIGN_REVIEW,
                null, null, claudeLog, durationMs, null,
                null, null, null, null, null,
                null, null, null, null, null,
                designMarkdown, mockupFilesJson, designProjectId, designUrl);
    }

    /** 디자인 생성 실패 보고. */
    public static WorkerResultRequest designFailed(String workerId, String reason, String claudeLog) {
        return new WorkerResultRequest(workerId, TaskStatus.DESIGN_FAILED,
                null, null, claudeLog, null, reason,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null);
    }
```

**컴파일 주도 정리:** `./gradlew compileJava compileTestJava`를 돌려 `WorkerResultRequest`/`WorkerTaskResponse` 위치 기반 생성자를 쓰는 모든 호출처(`WorkerMainLoop`의 `new WorkerResultRequest(...)` 3곳 등)에 트레일링 `null` 인자를 추가한다.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests WorkerTaskResponseDesignTest` PASS 후 `./gradlew test` 전체 PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 워커 DTO에 Kind.DESIGN + 디자인 결과 필드 추가"`

---

### Task 4: 클레임 큐 + stale 쿼리에 디자인 편입

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java` (claim/in-flight 쿼리)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (claim 분기)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDesignClaimTest.java` — **기존 통합 테스트 스타일 확인**: `TaskApiIntegrationTest.java` 상단 어노테이션(@SpringBootTest/@AutoConfigureMockMvc/프로파일)을 그대로 복사해 서비스 직접 주입 테스트로 작성.

**Interfaces:**
- Consumes: Task 3의 `forDesign`, Task 2의 `TaskDesignRepository`, `RepoCatalogEntry` 프로젝트 ID getter.
- Produces: `claimNextTask`가 `DESIGN_PENDING` 작업에 대해 `kind=DESIGN` 응답 반환 (`디자인대기→디자인중` 전이).

- [ ] **Step 1: 실패하는 테스트 작성** (핵심 시나리오만 — 전체 플로우는 Task 7 통합 테스트):

```java
package com.hamonsoft.netismaker.service;

// 어노테이션/셋업은 TaskApiIntegrationTest.java와 동일하게 구성 (H2 + api 프로파일)
// 필요한 리포지토리(TaskRepository, TaskDesignRepository, RepoCatalogRepository)와 WorkerService 주입

class WorkerServiceDesignClaimTest {

    // 헬퍼: Task를 DESIGN_PENDING 상태로 저장 (Task.create 후 setStatus/setDesignRequested,
    //        repoCatalogId 지정 시 RepoCatalogEntry.create + designSystemProjectId 세팅)

    @Test
    void 디자인대기_작업은_kind_DESIGN으로_claim되고_디자인중이_된다() {
        // given: DESIGN_PENDING task (repoCatalog에 designSystemProjectId="ds-1")
        // when: workerService.claimNextTask("w1")
        // then: kind()==DESIGN, designSystemProjectId()=="ds-1",
        //       DB task.status==DESIGNING, workerId=="w1"
    }

    @Test
    void 반려_이력이_있으면_이전_디자인과_피드백이_동봉된다() {
        // given: DESIGN_PENDING task + TaskDesign(rejectCount=1, feedbackHistoryJson=[...])
        // when: claim
        // then: response.designMarkdown()==이전 마크다운, feedbackHistoryJson() 포함
    }
}
```

(위 주석 시나리오를 실제 코드로 작성 — given 헬퍼는 이 테스트 파일 안에 private 메서드로 구현. `TaskRepository.save` 사용, 상태는 `t.setStatus(TaskStatus.DESIGN_PENDING)`.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests WorkerServiceDesignClaimTest`. Expected: FAIL (claim이 empty — DESIGN_PENDING이 쿼리에 없음).

- [ ] **Step 3: 구현**

`TaskRepository.findClaimableForUpdateSkipLocked` 쿼리의 IN 목록에 추가:

```
                           com.hamonsoft.netismaker.entity.TaskStatus.DESIGN_PENDING,
```

`findInFlightClaimed` 쿼리의 IN 목록에 추가:

```
                           com.hamonsoft.netismaker.entity.TaskStatus.DESIGNING,
```

`WorkerService`: 필드/생성자에 `TaskDesignRepository designRepo`, `RepoCatalogRepository repoCatalogRepo` 추가 (RepoCatalogRepository는 기존 존재 — `repository/` 참고). `claimNextTask`의 상태 분기에 추가:

```java
        } else if (from == TaskStatus.DESIGN_PENDING) {
            t.setStatus(TaskStatus.DESIGNING);
        } else if (from == TaskStatus.DEPLOY_PENDING) {
```

응답 분기(IMPLEMENTING 분기 위에):

```java
        if (t.getStatus() == TaskStatus.DESIGNING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            TaskDesign prev = designRepo.findById(t.getId()).orElse(null);
            String dsProjectId = null;
            String outProjectId = null;
            if (t.getRepoCatalogId() != null) {
                var cat = repoCatalogRepo.findById(t.getRepoCatalogId()).orElse(null);
                if (cat != null) {
                    dsProjectId = cat.getDesignSystemProjectId();
                    outProjectId = cat.getDesignOutputProjectId();
                }
            }
            return Optional.of(WorkerTaskResponse.forDesign(t, a, prev, dsProjectId, outProjectId));
        }
```

IMPLEMENTING 분기는 디자인 동봉으로 변경:

```java
        if (t.getStatus() == TaskStatus.IMPLEMENTING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            TaskDesign d = designRepo.findById(t.getId()).orElse(null);
            return Optional.of(WorkerTaskResponse.forImplementation(t, a,
                    (d != null && d.isApproved()) ? d : null));
        }
```

클래스 Javadoc의 claim 표에 `DESIGN_PENDING → DESIGNING (kind=DESIGN, 이전 디자인/피드백 동봉)` 추가.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests WorkerServiceDesignClaimTest` PASS, 전체 `./gradlew test` PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 디자인대기 claim 큐 편입 (kind=DESIGN)"`

---

### Task 5: recordResult 디자인 분기 (워커 보고 수신)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDesignResultTest.java` (Task 4 테스트와 동일 구성)

**Interfaces:**
- Consumes: Task 3의 `WorkerResultRequest.designReview/designFailed`.
- Produces: `recordResult`가 DESIGNING 상태에서 `DESIGN_REVIEW`/`DESIGN_FAILED` 보고 처리. 반려 재실행 시 기존 row의 `feedback_history`/`reject_count` 보존.

- [ ] **Step 1: 실패하는 테스트 작성** — 시나리오 4개를 실제 코드로:

```java
    @Test
    void 디자인_성공_보고는_task_design을_저장하고_디자인승인대기로_전이한다() {
        // given: DESIGNING task (workerId="w1")
        // when: recordResult(id, WorkerResultRequest.designReview("w1", "# D", "[{...}]", "p", "u", "log", 5L))
        // then: status==DESIGN_REVIEW, task_design.designMarkdown=="# D", designUrl=="u"
    }

    @Test
    void 반려_재실행_보고는_reject_count와_피드백_이력을_보존한다() {
        // given: DESIGNING task + 기존 TaskDesign(rejectCount=1, feedbackHistoryJson="[{\"feedback\":\"f1\"}]")
        // when: designReview 보고 (새 마크다운)
        // then: rejectCount==1 유지, feedbackHistoryJson 유지, designMarkdown 갱신
    }

    @Test
    void 디자인_실패_보고는_디자인실패와_사유를_기록한다() {
        // when: recordResult(id, WorkerResultRequest.designFailed("w1", "DesignSync 인증 실패", "log"))
        // then: status==DESIGN_FAILED, failureReason 포함
    }

    @Test
    void 성공_보고에_designMarkdown_없으면_400() {
        // designReview(..., designMarkdown=null ...) 직접 생성자 대신 new WorkerResultRequest로 구성
        // then: TaskException(HttpStatus.BAD_REQUEST)
    }

    @Test
    void 성공_보고시_카탈로그에_출력_프로젝트가_없으면_박제한다() {
        // given: task.repoCatalogId 지정, 카탈로그 designOutputProjectId==null
        // when: designReview 보고 (designProjectId="new-proj")
        // then: 카탈로그.designOutputProjectId=="new-proj"
    }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests WorkerServiceDesignResultTest`. Expected: FAIL (CONFLICT "현재 처리중 상태가 아닙니다").

- [ ] **Step 3: 구현** — `recordResult`에서:

허용 상태 검사에 DESIGNING 추가:

```java
        if (current != TaskStatus.IN_PROGRESS
                && current != TaskStatus.IMPLEMENTING
                && current != TaskStatus.DESIGNING
                && current != TaskStatus.DEPLOYING
                && current != TaskStatus.UNDEPLOYING) {
```

배포 위임 분기 옆에:

```java
        if (current == TaskStatus.DESIGNING) {
            recordDesignResult(t, req);
            return;
        }
```

새 private 메서드 (`recordDeployResult` 아래):

```java
    private void recordDesignResult(Task t, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case DESIGN_REVIEW -> {
                if (req.designMarkdown() == null || req.designMarkdown().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "디자인 완료 시 designMarkdown 필수");
                }
                if (req.mockupFilesJson() == null || req.mockupFilesJson().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "디자인 완료 시 mockupFilesJson 필수");
                }
                TaskDesign d = designRepo.findById(t.getId()).orElse(null);
                if (d == null) {
                    d = TaskDesign.create(t.getId(), req.designMarkdown(), req.mockupFilesJson(),
                            req.designProjectId(), req.designUrl(), req.claudeLog(), req.durationMs());
                } else {
                    // 반려 재실행: feedback_history/reject_count 보존, 산출물만 갱신
                    d.setDesignMarkdown(req.designMarkdown());
                    d.setMockupFilesJson(req.mockupFilesJson());
                    if (req.designProjectId() != null) d.setDesignProjectId(req.designProjectId());
                    if (req.designUrl() != null) d.setDesignUrl(req.designUrl());
                    d.setClaudeLog(req.claudeLog());
                    d.setDurationMs(req.durationMs());
                    d.setCompletedAt(OffsetDateTime.now());
                }
                designRepo.save(d);
                // 출력 프로젝트 최초 생성 시 카탈로그에 박제 (이후 작업이 재사용)
                if (req.designProjectId() != null && t.getRepoCatalogId() != null) {
                    repoCatalogRepo.findById(t.getRepoCatalogId())
                            .filter(c -> c.getDesignOutputProjectId() == null)
                            .ifPresent(c -> {
                                c.setDesignOutputProjectId(req.designProjectId());
                                c.setUpdatedAt(OffsetDateTime.now());
                            });
                }
                t.setStatus(TaskStatus.DESIGN_REVIEW);
                reason = "디자인 생성 완료 → 승인 대기";
            }
            case DESIGN_FAILED -> {
                t.setStatus(TaskStatus.DESIGN_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                reason = t.getFailureReason();
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "디자인 단계에서 허용되지 않는 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
    }
```

클래스 Javadoc의 recordResult 표에 `DESIGN_REVIEW/DESIGN_FAILED` 행 추가.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests WorkerServiceDesignResultTest` PASS, 전체 PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 디자인 결과 보고 처리 (recordDesignResult)"`

---

### Task 6: TaskService — 승인 라우팅 + 디자인 승인/반려 + 재시도

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceDesignTest.java` (Task 4 테스트와 동일 구성)

**Interfaces:**
- Produces: `TaskService.approve` — design_requested면 `DESIGN_PENDING`으로; `approveDesign(Long taskId, String adminId)`; `rejectDesign(Long taskId, String adminId, String feedback)` (한도 3); `retry` — `DESIGN_FAILED → DESIGN_PENDING` 허용. 상수 `MAX_DESIGN_REJECTS = 3`.

- [ ] **Step 1: 실패하는 테스트 작성** — 시나리오를 실제 코드로:

```java
    @Test
    void design_requested_작업은_분석_승인시_디자인대기로_간다() {
        // given: COMPLETED task (designRequested=true) + TaskAnalysis
        // when: taskService.approve(id, "admin")
        // then: status==DESIGN_PENDING, analysis.approved==true
    }

    @Test
    void 일반_작업은_분석_승인시_기존대로_구현대기로_간다() { /* designRequested=false → APPROVED */ }

    @Test
    void 디자인_승인은_구현대기로_전이하고_승인자를_기록한다() {
        // given: DESIGN_REVIEW task + TaskDesign
        // when: approveDesign(id, "admin")
        // then: status==APPROVED, design.approved==true, approvedBy=="admin"
    }

    @Test
    void 디자인_반려는_피드백을_누적하고_디자인대기로_재큐잉한다() {
        // when: rejectDesign(id, "admin", "색상이 어둡습니다")
        // then: status==DESIGN_PENDING, rejectCount==1,
        //       feedbackHistoryJson에 "색상이 어둡습니다" 포함, workerId==null
    }

    @Test
    void 반려_3회_도달_후_반려는_409() {
        // given: TaskDesign(rejectCount=3), status=DESIGN_REVIEW
        // expect: TaskException CONFLICT
    }

    @Test
    void 디자인승인대기가_아니면_디자인_승인_반려_모두_409() { /* COMPLETED 상태에서 시도 */ }

    @Test
    void 디자인실패는_retry로_디자인대기_재큐잉된다() {
        // given: DESIGN_FAILED task
        // when: taskService.retry(id, owner, false)
        // then: status==DESIGN_PENDING, failureReason==null
    }
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests TaskServiceDesignTest`. Expected: 컴파일 에러 (approveDesign 없음).

- [ ] **Step 3: 구현**

`TaskService`에 필드 `TaskDesignRepository designRepo`, `com.fasterxml.jackson.databind.ObjectMapper objectMapper`(스프링 빈 주입) 추가 + 상수:

```java
    static final int MAX_DESIGN_REJECTS = 3;
```

`approve()` 전이부 교체 (기존 `t.setStatus(TaskStatus.APPROVED);` 자리):

```java
        TaskStatus from = t.getStatus();
        TaskStatus to = t.isDesignRequested() ? TaskStatus.DESIGN_PENDING : TaskStatus.APPROVED;
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", adminId,
                reason + (to == TaskStatus.DESIGN_PENDING ? " (디자인 구간)" : "")));
```

(기존 reason 산출 로직은 유지 — "관리자 승인 → 구현 큐 진입" 문구는 `to`가 DESIGN_PENDING이면 "관리자 승인 → 디자인 큐 진입"으로 분기.)

새 메서드 (approve 아래):

```java
    /** 디자인승인대기 → 구현대기. admin 한정. */
    @Transactional
    public TaskDesign approveDesign(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DESIGN_REVIEW) {
            throw TaskException.conflict("디자인승인대기 상태에서만 승인할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskDesign d = designRepo.findById(taskId)
                .orElseThrow(() -> TaskException.conflict("디자인 결과가 없습니다"));
        d.setApproved(true);
        d.setApprovedBy(adminId);
        d.setApprovedAt(OffsetDateTime.now());
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.APPROVED);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.APPROVED,
                "user", adminId, "디자인 승인 → 구현 큐 진입"));
        return d;
    }

    /** 디자인승인대기 → 디자인대기 (피드백 반려, 최대 MAX_DESIGN_REJECTS회). admin 한정. */
    @Transactional
    public Task rejectDesign(Long taskId, String adminId, String feedback) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DESIGN_REVIEW) {
            throw TaskException.conflict("디자인승인대기 상태에서만 반려할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskDesign d = designRepo.findById(taskId)
                .orElseThrow(() -> TaskException.conflict("디자인 결과가 없습니다"));
        if (d.getRejectCount() >= MAX_DESIGN_REJECTS) {
            throw TaskException.conflict("반려 한도(" + MAX_DESIGN_REJECTS
                    + ")에 도달했습니다 — 승인 또는 삭제만 가능합니다");
        }
        d.setFeedbackHistoryJson(appendFeedback(d.getFeedbackHistoryJson(), feedback, adminId));
        d.setRejectCount(d.getRejectCount() + 1);
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.DESIGN_PENDING);
        t.setWorkerId(null);
        t.setClaimedAt(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DESIGN_PENDING,
                "user", adminId, "디자인 반려 (" + d.getRejectCount() + "/" + MAX_DESIGN_REJECTS + ")"));
        return t;
    }

    /** feedback_history jsonb 배열에 항목 append. 파싱 실패 시 새 배열로 시작 (방어). */
    private String appendFeedback(String historyJson, String feedback, String adminId) {
        try {
            var list = objectMapper.readValue(
                    historyJson == null || historyJson.isBlank() ? "[]" : historyJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
            list.add(java.util.Map.of(
                    "feedback", feedback,
                    "rejectedBy", adminId,
                    "rejectedAt", OffsetDateTime.now().toString()));
            return objectMapper.writeValueAsString(list);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "피드백 이력 직렬화 실패");
        }
    }
```

`retry()` 상태 검증부 교체:

```java
        if (t.getStatus() == TaskStatus.DESIGN_FAILED) {
            // 디자인 재시도: admin 수동 조작이므로 retryCount 소비 없음
            TaskStatus from = t.getStatus();
            t.setStatus(TaskStatus.DESIGN_PENDING);
            t.setFailureReason(null);
            t.setClaimedAt(null);
            t.setWorkerId(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DESIGN_PENDING,
                    isAdmin ? "system" : "user", actorId, "디자인 재시도"));
            return t;
        }
        if (t.getStatus() != TaskStatus.FAILED) {
            throw TaskException.conflict("분석실패/디자인실패 상태에서만 재시도할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests TaskServiceDesignTest` PASS, 전체 PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 디자인 승인/반려/재시도 + 승인 라우팅"`

---

### Task 7: API 표면 — 컨트롤러 + DTO + 등록 토글 + 통합 플로우 테스트

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/RejectDesignRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java` (designRequested + DesignView)
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskCreateRequest.java` (designRequested)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java` (create에서 세팅, getDesign 조회)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/TaskDesignFlowIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/tasks/{id}/design/approve`, `POST /api/tasks/{id}/design/reject {feedback}` (둘 다 ROLE_ADMIN). `TaskResponse.designRequested`(boolean) + `TaskResponse.DesignView(designMarkdown, mockupFilesJson, designProjectId, designUrl, rejectCount, feedbackHistoryJson, approved, approvedBy, approvedAt, completedAt)` — 상세 조회에서만 non-null. `TaskCreateRequest.designRequested`(Boolean, null=false).

- [ ] **Step 1: 실패하는 통합 테스트 작성** — `TaskApiIntegrationTest.java`의 MockMvc 셋업/JWT 헬퍼(RequestPostProcessor) 구성을 그대로 복사. 시나리오:

```java
    @Test
    void 디자인_전체_플로우() throws Exception {
        // 1) designRequested=true로 작업 생성 (POST /api/tasks) → 201, body.designRequested==true
        // 2) 워커 분석 claim + COMPLETED 보고 (기존 테스트의 /worker 헬퍼 재사용)
        // 3) admin 승인 (POST /{id}/approve) → 200, statusLabel=="디자인대기"
        // 4) 워커 claim (POST /worker/next-task) → kind=="DESIGN"
        // 5) 워커 designReview 보고 (POST /worker/tasks/{id}/result) → 상세 조회 statusLabel=="디자인승인대기",
        //    design.mockupFilesJson 포함
        // 6) admin 반려 (POST /{id}/design/reject, body {"feedback":"버튼이 너무 작음"}) → 200,
        //    statusLabel=="디자인대기", design.rejectCount==1
        // 7) 워커 재claim → designMarkdown(이전) + feedbackHistoryJson에 피드백 포함
        // 8) 워커 designReview 재보고 → 디자인승인대기
        // 9) admin 디자인 승인 (POST /{id}/design/approve) → statusLabel=="구현대기"
        // 10) 워커 claim → kind=="IMPLEMENTATION", designMarkdown 동봉
    }

    @Test
    void 디자인_반려는_admin_전용이다() throws Exception {
        // USER 토큰으로 POST /{id}/design/reject → 403
    }

    @Test
    void 피드백_없는_반려는_400() throws Exception { /* body {"feedback":"  "} → 400 */ }
```

(주석 시나리오를 실제 MockMvc 코드로 작성 — 기존 파일의 승인/워커 보고 테스트 메서드가 정확한 관용구를 보여준다.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests TaskDesignFlowIntegrationTest`. Expected: FAIL/컴파일 에러.

- [ ] **Step 3: 구현**

`RejectDesignRequest.java`:

```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectDesignRequest(
        @NotBlank(message = "반려 피드백은 필수입니다")
        @Size(max = 4000)
        String feedback
) {}
```

`TaskCreateRequest`: `effort` 뒤에 추가:

```java
        /** 디자인 단계 포함 여부. null이면 false. */
        Boolean designRequested
```

`TaskService.create`에서 `Task.create(...)` 호출 뒤에 (기존 gitUrl/repoAlias 세팅 부근):

```java
        t.setDesignRequested(Boolean.TRUE.equals(req.designRequested()));
```

`TaskService`에 조회 메서드 추가 (getAnalysis 옆, 동일 패턴):

```java
    @Transactional(readOnly = true)
    public Optional<TaskDesign> getDesign(Long taskId) {
        return designRepo.findById(taskId);
    }
```

`TaskResponse`: 레코드에 `boolean designRequested`(effort 뒤), `DesignView design`(analysis 뒤) 추가:

```java
    public record DesignView(
            String designMarkdown,
            String mockupFilesJson,
            String designProjectId,
            String designUrl,
            int rejectCount,
            String feedbackHistoryJson,
            boolean approved,
            String approvedBy,
            OffsetDateTime approvedAt,
            OffsetDateTime completedAt
    ) {}
```

기존 `of(Task t, TaskAnalysis a)`는 3-arg에 위임하도록 유지(목록/기존 호출처 무변경):

```java
    public static TaskResponse of(Task t, TaskAnalysis a) {
        return of(t, a, null);
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d) {
        // ... 기존 본문에 다음 추가:
        DesignView dv = (d == null) ? null : new DesignView(
                d.getDesignMarkdown(), d.getMockupFilesJson(), d.getDesignProjectId(),
                d.getDesignUrl(), d.getRejectCount(), d.getFeedbackHistoryJson(),
                d.isApproved(), d.getApprovedBy(), d.getApprovedAt(), d.getCompletedAt());
        // 생성자 인자에 t.isDesignRequested(), dv 추가
    }
```

`TaskController`: 상세 조회 `get()`을 디자인 동봉으로 변경:

```java
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null));
```

새 엔드포인트 (approve 아래):

```java
    @PostMapping("/{id}/design/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse approveDesign(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.approveDesign(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null));
    }

    @PostMapping("/{id}/design/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse rejectDesign(@PathVariable Long id,
                                     @RequestBody @Valid RejectDesignRequest body,
                                     JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.rejectDesign(id, adminId, body.feedback());
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null));
    }
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests TaskDesignFlowIntegrationTest` PASS, 전체 PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 디자인 승인/반려 API + 등록 토글 + 상세 DesignView"`

---

### Task 8: StaleTaskRecoveryJob — 디자인중 회수

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java`
- Test: 기존 stale 테스트 파일이 있으면(`grep -rn "StaleTaskRecovery" src/test`) 그 파일에 케이스 추가, 없으면 `src/test/java/com/hamonsoft/netismaker/service/StaleDesignRecoveryTest.java` 신규 (Task 4 구성).

**Interfaces:**
- Produces: `디자인중` stale 시 `디자인대기` 재큐잉 (UNDEPLOYING과 동일한 idempotent 패턴). 설정 `app.task.design-stale-threshold-minutes` (기본 30), `app.task.design-worker-dead-threshold-seconds` (기본 300).

- [ ] **Step 1: 실패하는 테스트 작성**

```java
    @Test
    void 디자인중_작업은_stale시_디자인대기로_재큐잉된다() {
        // given: DESIGNING task, workerId="dead-worker", claimedAt=now-2h, heartbeat 없음
        // when: staleTaskRecoveryJob.recoverStale()
        // then: status==DESIGN_PENDING, workerId==null
    }
```

- [ ] **Step 2: 실패 확인** — Expected: FAIL (findInFlightClaimed엔 Task 4에서 DESIGNING이 이미 포함 — 하지만 switch default가 분석 분기로 빠져 PENDING이 됨 → 단언 실패).

- [ ] **Step 3: 구현** — `StaleTaskRecoveryJob`에 필드 추가:

```java
    @Value("${app.task.design-stale-threshold-minutes:30}")
    private int designStaleThresholdMinutes;

    @Value("${app.task.design-worker-dead-threshold-seconds:300}")
    private int designWorkerDeadThresholdSeconds;
```

두 switch에 케이스 추가:

```java
            int deadSec = switch (from) {
                case IMPLEMENTING -> implementationWorkerDeadThresholdSeconds;
                case DESIGNING -> designWorkerDeadThresholdSeconds;
                case DEPLOYING, UNDEPLOYING -> deployWorkerDeadThresholdSeconds;
                default -> workerDeadThresholdSeconds;
            };
```

```java
            int ceilingMin = switch (from) {
                case IMPLEMENTING -> implementationStaleThresholdMinutes;
                case DESIGNING -> designStaleThresholdMinutes;
                case DEPLOYING, UNDEPLOYING -> deployStaleThresholdMinutes;
                default -> analysisStaleThresholdMinutes;
            };
```

회수 switch에 케이스 추가 (UNDEPLOYING 패턴):

```java
                case DESIGNING -> {
                    t.setStatus(TaskStatus.DESIGN_PENDING);
                    logTransition(t, from, TaskStatus.DESIGN_PENDING, "디자인중 stale → 재큐잉(idempotent)");
                    log.warn("Stale 회수: task={} {} → DESIGN_PENDING(재큐잉)", t.getId(), why);
                }
```

- [ ] **Step 4: 통과 확인** — 해당 테스트 + 전체 PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 디자인중 stale 회수 → 디자인대기 재큐잉"`

---

### Task 9: 인터뷰 경유 등록에 designRequested 적용

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java` (register body)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (register 시그니처)
- Modify: `frontend/components/InterviewPanel.vue` (등록 버튼 옆 토글)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java`에 케이스 추가

**Interfaces:**
- Produces: `POST /api/interviews/{id}/register` body `{"designRequested": true}` (optional) → 생성 Task의 `design_requested` 세팅.

- [ ] **Step 1: 실패하는 테스트 작성** — `InterviewApiIntegrationTest`의 기존 register 테스트를 복사해:

```java
    @Test
    void 등록시_designRequested를_넘기면_task에_반영된다() throws Exception {
        // 기존 register 테스트의 PLAN_READY 세션 셋업 재사용
        // POST /api/interviews/{id}/register body {"designRequested": true}
        // then: 생성된 task의 designRequested==true (taskRepo로 확인)
    }
```

- [ ] **Step 2: 실패 확인** — Expected: FAIL.

- [ ] **Step 3: 구현**

`InterviewController.register` — body 파라미터 추가 (record는 컨트롤러 파일 하단 또는 dto 패키지에):

```java
    public record InterviewRegisterRequest(Boolean designRequested) {}

    // 기존 시그니처에 추가:
    public RegisterResponse register(@PathVariable Long id,
                                     @RequestBody(required = false) InterviewRegisterRequest body,
                                     JwtAuthenticationToken auth) {
        ...
        Long taskId = interviewService.register(id, userId, AuthContext.isAdmin(auth),
                body != null && Boolean.TRUE.equals(body.designRequested()));
```

`InterviewService.register(Long sessionId, String actorId, boolean isAdmin, boolean designRequested)` — `t.setStatus(TaskStatus.COMPLETED);` 다음에:

```java
        t.setDesignRequested(designRequested);
```

(기존 3-arg 호출처가 테스트에 있으면 4-arg로 갱신.)

`InterviewPanel.vue` — `register()` 함수와 등록 버튼(`data-test="register"` 근처):

```ts
const designRequested = ref(false)
```

register()의 useApi 호출에 body 추가:

```ts
    const res = await useApi<{ taskId: number }>(`/api/interviews/${props.sessionId}/register`, {
      method: 'POST',
      body: { designRequested: designRequested.value },
    })
```

등록 버튼 바로 위 템플릿에 (PLAN_READY 상태 블록 안):

```vue
            <q-toggle
              v-model="designRequested"
              dense
              label="디자인 단계 포함"
            >
              <q-tooltip>분석 승인 후 화면 목업을 생성해 승인받습니다</q-tooltip>
            </q-toggle>
```

- [ ] **Step 4: 통과 확인** — 해당 테스트 + 전체 PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 인터뷰 등록 경로에 디자인 토글 적용"`

---

### Task 10: 워커 설정 — 디자인 프롬프트 템플릿 + 타임아웃 + createForDesign

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java`
- Modify: `src/main/resources/application-worker.yml`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java`

**Interfaces:**
- Produces: `props.designPromptTemplate()`, `props.designTimeout()` (기본 30m), `worktrees.createForDesign(File repoCacheDir, String githubRepo, String baseBranch, long taskId)` — `design-{id}` 경로 detached 체크아웃 (createForDeploy 미러 — 브랜치 불필요, 구현 worktree `task-{id}`와 경로 충돌 없음).

- [ ] **Step 1: WorkerProperties** — 레코드에 필드 추가 (`implementationTimeout` 뒤):

```java
        // 디자인 단계
        String designPromptTemplate,
        Duration designTimeout,
```

컴팩트 생성자에 기본값:

```java
        if (designTimeout == null) designTimeout = Duration.ofMinutes(30);
```

- [ ] **Step 2: WorktreeService.createForDesign** — `createForDeploy` 아래에 (동일 구조, 경로만 `design-`):

```java
    /**
     * 디자인용 worktree. 새 브랜치 없이 origin/{baseBranch} detached 체크아웃 (목업 생성만, push 없음).
     * 경로 design-{id} — 구현 worktree(task-{id})와 분리되어 재실행/후속 구현과 충돌 없음.
     */
    public File createForDesign(File repoCacheDir, String githubRepo,
                                String baseBranch, long taskId)
            throws IOException, InterruptedException {
        return repos.withRepoLock(githubRepo, () -> {
            Path worktreeDir = Paths.get(props.worktreeRoot(), githubRepo, "design-" + taskId);
            Files.createDirectories(worktreeDir.getParent());
            if (Files.exists(worktreeDir)) {
                log.warn("기존 design worktree 발견, 강제 제거: {}", worktreeDir);
                try {
                    ProcessRunner.run(repoCacheDir, List.of("git", "worktree", "remove", "--force",
                            worktreeDir.toString()), GIT_TIMEOUT_SECONDS);
                } catch (Exception e) {
                    log.warn("worktree remove 실패 (계속): {}", e.getMessage());
                }
                deleteRecursively(worktreeDir.toFile());
            }
            ProcessRunner.requireSuccess(repoCacheDir,
                    List.of("git", "worktree", "add", "--force", "--detach",
                            worktreeDir.toString(), "origin/" + baseBranch),
                    GIT_TIMEOUT_SECONDS);
            log.info("design worktree 생성: task={} dir={}", taskId, worktreeDir);
            return worktreeDir.toFile();
        });
    }
```

(람다에서 checked exception 처리 방식은 `withRepoLock`의 함수형 인터페이스 시그니처를 따를 것 — `doCreateForDeploy` 위임 방식과 동일하게 private 메서드로 분리해도 좋다.)

- [ ] **Step 3: application-worker.yml** — `implementation-prompt-template` 블록 아래에:

```yaml
    # 디자인 단계 — DesignSync로 디자인 시스템 pull + 목업 생성 + Claude Design 업로드
    design-timeout: ${DESIGN_TIMEOUT:30m}
    design-prompt-template: |
      당신은 UI/UX 디자이너입니다. 현재 디렉토리는 대상 레포의 읽기 전용 체크아웃입니다.
      코드를 수정하지 말고, 아래 작업의 화면 목업(디자인)만 생성하세요. 모든 산출물은 한국어.

      ## 작업
      {title}

      ## 요구사항
      {description}

      ## 사전 분석 결과
      {analysis_markdown}

      ## 분석된 subtasks (JSON)
      {subtasks_json}

      ## 이전 디자인 (반려된 경우에만 존재 — 백지 재생성 금지, 피드백만 반영해 수정)
      {previous_design_markdown}

      ## 반려 피드백 이력 (JSON — 최신 항목이 가장 중요)
      {feedback_history}

      ## 절차 (순서 엄수)
      1. **디자인 시스템 pull**: 디자인 시스템 프로젝트 ID가 '{design_system_project_id}'로 주어졌다면
         (값이 "없음"이 아니면) DesignSync 도구(list_files/get_file)로 해당 프로젝트의 토큰·컴포넌트를
         읽어 스타일 기준으로 삼으세요. **DesignSync 인증 실패 또는 프로젝트 접근 실패 시:
         즉시 중단하고 `.design-out/ERROR.txt`에 실패 사유를 쓴 뒤 종료하세요. 다른 파일을 만들지 마세요.**
         값이 "없음"이면 이 단계를 건너뛰고 범용적으로 깔끔한 스타일을 쓰세요.
      2. **기존 코드 참조**: 현재 디렉토리의 프론트엔드 코드(컴포넌트/스타일)를 훑어 기존 UI 관례를 파악하세요.
      3. **목업 생성**: 작업에 필요한 화면별로 self-contained HTML(인라인 CSS, 외부 요청 없음)을
         `.design-out/screens/` 아래에 만드세요 (예: `.design-out/screens/main.html`).
         각 파일은 실제 데이터 예시를 채운 완성된 화면이어야 합니다. 파일당 200KB 이하.
      4. **디자인 문서**: `.design-out/DESIGN.md`에 디자인 의사결정(레이아웃/색/타이포/컴포넌트 매핑,
         화면별 설명)을 정리하세요.
      5. **Claude Design 업로드 (비치명 — 실패해도 3·4 산출물은 유지)**:
         출력 프로젝트 ID가 '{design_output_project_id}'로 주어졌다면 그 프로젝트에,
         "없음"이면 DesignSync create_project로 "netisMaker — {github_repo} 목업" 프로젝트를 만들어
         `task-{task_id}/` 경로 프리픽스로 목업들을 write_files 업로드하세요.
      6. **결과 메타**: `.design-out/result.json`에 다음 JSON을 쓰세요 (업로드 실패 시 projectId/url은 null):
         {"screens":[{"path":"screens/main.html","title":"메인 화면"}],"designProjectId":"...","designUrl":"..."}
```

주의: 템플릿의 `{task_id}`/`{design_system_project_id}`/`{design_output_project_id}`/`{feedback_history}`/`{previous_design_markdown}` 치환은 Task 12의 renderDesignPrompt가 수행. null 값은 문자열 `"없음"`으로 치환.

- [ ] **Step 4: 빌드 확인** — Run: `./gradlew compileJava`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 디자인 워커 설정 (프롬프트 템플릿/타임아웃/worktree)"`

---

### Task 11: DesignResultHarvester — .design-out 수확 파서

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DesignResultHarvester.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/DesignResultHarvesterTest.java`

**Interfaces:**
- Produces: `harvest(File worktreeDir)` → `HarvestResult(String designMarkdown, String mockupFilesJson, String designProjectId, String designUrl)`. 실패 계약: `.design-out/ERROR.txt` 존재 → `HarvestException(사유)`; `DESIGN.md` 또는 screens/*.html 부재 → `HarvestException`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignResultHarvesterTest {

    private final DesignResultHarvester harvester = new DesignResultHarvester();

    private void write(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void 정상_산출물을_수확한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/DESIGN.md", "# 디자인 문서");
        write(wt, ".design-out/screens/main.html", "<html>메인</html>");
        write(wt, ".design-out/result.json",
                "{\"screens\":[{\"path\":\"screens/main.html\",\"title\":\"메인 화면\"}]," +
                "\"designProjectId\":\"p1\",\"designUrl\":\"https://u\"}");

        DesignResultHarvester.HarvestResult r = harvester.harvest(wt.toFile());

        assertThat(r.designMarkdown()).isEqualTo("# 디자인 문서");
        assertThat(r.designProjectId()).isEqualTo("p1");
        assertThat(r.mockupFilesJson()).contains("\"title\":\"메인 화면\"")
                .contains("<html>메인</html>");
    }

    @Test
    void result_json이_없어도_screens_디렉토리에서_수확한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/DESIGN.md", "# D");
        write(wt, ".design-out/screens/a.html", "<html>a</html>");
        DesignResultHarvester.HarvestResult r = harvester.harvest(wt.toFile());
        assertThat(r.designProjectId()).isNull();
        assertThat(r.mockupFilesJson()).contains("screens/a.html");
    }

    @Test
    void ERROR_txt가_있으면_사유와_함께_실패한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/ERROR.txt", "DesignSync 인증 실패");
        assertThatThrownBy(() -> harvester.harvest(wt.toFile()))
                .isInstanceOf(DesignResultHarvester.HarvestException.class)
                .hasMessageContaining("DesignSync 인증 실패");
    }

    @Test
    void DESIGN_md_없으면_실패한다(@TempDir Path wt) throws Exception {
        write(wt, ".design-out/screens/a.html", "<html></html>");
        assertThatThrownBy(() -> harvester.harvest(wt.toFile()))
                .isInstanceOf(DesignResultHarvester.HarvestException.class);
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests DesignResultHarvesterTest`. Expected: 컴파일 에러.

- [ ] **Step 3: 구현**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * claude 디자인 세션의 .design-out/ 산출물을 수확해 보고 페이로드로 변환.
 *
 *  계약 (design-prompt-template과 동기):
 *    ERROR.txt        → 존재하면 즉시 실패 (인증/pull 실패 fail-fast)
 *    DESIGN.md        → 필수. designMarkdown
 *    screens/*.html   → 1개 이상 필수. mockup_files의 html 원문
 *    result.json      → 선택. {screens:[{path,title}], designProjectId, designUrl}
 *                       title은 result.json 우선, 없으면 파일명 사용
 */
@Component
@Profile("worker")
public class DesignResultHarvester {

    private static final long MAX_MOCKUP_BYTES = 200 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    public HarvestResult harvest(File worktreeDir) throws HarvestException {
        Path out = worktreeDir.toPath().resolve(".design-out");
        try {
            Path error = out.resolve("ERROR.txt");
            if (Files.exists(error)) {
                throw new HarvestException("디자인 세션 중단: " + Files.readString(error).trim());
            }
            Path designMd = out.resolve("DESIGN.md");
            if (!Files.exists(designMd)) {
                throw new HarvestException(".design-out/DESIGN.md 없음 — 세션이 산출물을 만들지 못함");
            }
            Path screensDir = out.resolve("screens");
            List<Path> screens = new ArrayList<>();
            if (Files.isDirectory(screensDir)) {
                try (var s = Files.list(screensDir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".html"))
                     .sorted().forEach(screens::add);
                }
            }
            if (screens.isEmpty()) {
                throw new HarvestException(".design-out/screens/*.html 없음 — 목업이 생성되지 않음");
            }

            String designProjectId = null;
            String designUrl = null;
            java.util.Map<String, String> titles = new java.util.HashMap<>();
            Path resultJson = out.resolve("result.json");
            if (Files.exists(resultJson)) {
                JsonNode root = mapper.readTree(Files.readString(resultJson));
                designProjectId = root.path("designProjectId").isTextual()
                        ? root.get("designProjectId").asText() : null;
                designUrl = root.path("designUrl").isTextual()
                        ? root.get("designUrl").asText() : null;
                for (JsonNode s : root.path("screens")) {
                    if (s.path("path").isTextual() && s.path("title").isTextual()) {
                        titles.put(s.get("path").asText(), s.get("title").asText());
                    }
                }
            }

            ArrayNode mockups = mapper.createArrayNode();
            for (Path p : screens) {
                if (Files.size(p) > MAX_MOCKUP_BYTES) {
                    throw new HarvestException("목업 파일 200KB 초과: " + p.getFileName());
                }
                String rel = "screens/" + p.getFileName();
                ObjectNode node = mockups.addObject();
                node.put("path", rel);
                node.put("title", titles.getOrDefault(rel, p.getFileName().toString()));
                node.put("html", Files.readString(p));
            }

            return new HarvestResult(Files.readString(designMd),
                    mapper.writeValueAsString(mockups), designProjectId, designUrl);
        } catch (IOException e) {
            throw new HarvestException(".design-out 수확 실패: " + e.getMessage());
        }
    }

    public record HarvestResult(String designMarkdown, String mockupFilesJson,
                                String designProjectId, String designUrl) {}

    public static class HarvestException extends Exception {
        public HarvestException(String message) {
            super(message);
        }
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests DesignResultHarvesterTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: DesignResultHarvester (.design-out 수확 계약)"`

---

### Task 12: WorkerMainLoop.processDesign + 구현 전달

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java`

**Interfaces:**
- Consumes: Task 10의 `createForDesign`/`designPromptTemplate`/`designTimeout`, Task 11의 harvester, Task 3의 팩토리.
- Produces: kind=DESIGN 처리 전체 + 구현 프롬프트 `{design_section}` + 목업 워크트리 배치.

- [ ] **Step 1: 구현** (이 태스크는 외부 프로세스 의존이 커서 단위 테스트 대신 Task 14 라이브 스모크로 검증 — 컴파일과 전체 회귀 테스트는 필수)

생성자에 `DesignResultHarvester designHarvester` 주입 추가. `pollAndProcess`의 switch에:

```java
                case DESIGN -> processDesign(task);
```

예외 핸들러 switch에:

```java
                case DESIGN -> safePostDesignFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(), null);
```

새 메서드들 (`processImplementation` 아래):

```java
    private void processDesign(WorkerTaskResponse task) throws Exception {
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.githubRepo(), task.githubBranch());
        } catch (Exception e) {
            safePostDesignFailure(task.id(), "레포 fetch 실패: " + e.getMessage(), null);
            return;
        }

        File wt;
        try {
            wt = worktrees.createForDesign(repo.dir(), task.githubRepo(),
                    task.githubBranch(), task.id());
        } catch (Exception e) {
            safePostDesignFailure(task.id(), "design worktree 생성 실패: " + e.getMessage(), null);
            return;
        }

        String prompt = renderDesignPrompt(task);
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, wt, props.designTimeout(),
                    task.mcpsExtra() == null ? java.util.List.of() : task.mcpsExtra(),
                    true, task.model(), task.effort());
        } catch (Exception e) {
            safePostDesignFailure(task.id(), "claude exec 실패: " + e.getMessage(), null);
            return;
        }
        if (exec.exitCode() != 0) {
            safePostDesignFailure(task.id(),
                    "claude exit=" + exec.exitCode() + "\n" + tail(exec.stdout(), 4000),
                    exec.stdout());
            return;
        }

        DesignResultHarvester.HarvestResult harvest;
        try {
            harvest = designHarvester.harvest(wt);
        } catch (DesignResultHarvester.HarvestException e) {
            safePostDesignFailure(task.id(), e.getMessage(), exec.stdout());
            return;
        }

        reporter.reportTerminal(task.id(), WorkerResultRequest.designReview(
                props.id(), harvest.designMarkdown(), harvest.mockupFilesJson(),
                harvest.designProjectId(), harvest.designUrl(),
                exec.stdout(), exec.durationMs()));
        // 수확 완료 후 design worktree는 best-effort 정리 (산출물은 DB로 감 — 보존 불필요)
        worktrees.remove(repo.dir(), wt);
        log.info("디자인 생성 완료: task={} screens 포함, url={}", task.id(), harvest.designUrl());
    }

    private String renderDesignPrompt(WorkerTaskResponse task) {
        String tpl = props.designPromptTemplate();
        return tpl
                .replace("{github_repo}", task.githubRepo())
                .replace("{task_id}", String.valueOf(task.id()))
                .replace("{title}", task.title())
                .replace("{description}", task.description())
                .replace("{analysis_markdown}", nvl(task.analysisMarkdown(), ""))
                .replace("{subtasks_json}", nvl(task.subtasksJson(), "[]"))
                .replace("{previous_design_markdown}", nvl(task.designMarkdown(), "(없음 — 첫 생성)"))
                .replace("{feedback_history}", nvl(task.feedbackHistoryJson(), "[]"))
                .replace("{design_system_project_id}", nvl(task.designSystemProjectId(), "없음"))
                .replace("{design_output_project_id}", nvl(task.designOutputProjectId(), "없음"));
    }

    private static String nvl(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private void safePostDesignFailure(Long taskId, String reason, String log_) {
        reporter.reportTerminal(taskId, WorkerResultRequest.designFailed(props.id(), reason, log_));
    }
```

- [ ] **Step 2: 구현 전달** — `processImplementation`의 claude exec 직전(`String prompt = renderImplementationPrompt(...)` 앞)에 목업 배치:

```java
        // 승인된 디자인이 있으면 목업을 워크트리에 배치 (참조용 — 커밋 전 삭제)
        File designDir = null;
        if (task.mockupFilesJson() != null && !task.mockupFilesJson().isBlank()) {
            try {
                designDir = new File(wt.dir(), ".design");
                writeMockupFiles(designDir, task.mockupFilesJson());
            } catch (Exception e) {
                log.warn("목업 배치 실패 (디자인 없이 진행): {}", e.getMessage());
                designDir = null;
            }
        }
```

commit 직전(4단계 `gitOps.commitAndPush` 앞)에:

```java
        // 목업 참조 파일은 커밋 대상에서 제외
        if (designDir != null) deleteRecursively(designDir);
```

새 헬퍼 (파일 하단 private 유틸 옆):

```java
    /** mockup_files JSON([{path,title,html}])을 dir 아래 파일로 풀어놓는다. path는 dir 내부로 강제. */
    private void writeMockupFiles(File dir, String mockupFilesJson) throws java.io.IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(mockupFilesJson);
        java.nio.file.Path base = dir.toPath().toAbsolutePath().normalize();
        for (var node : root) {
            String rel = node.path("path").asText("");
            String html = node.path("html").asText("");
            if (rel.isBlank()) continue;
            java.nio.file.Path target = base.resolve(rel).normalize();
            if (!target.startsWith(base)) continue; // path traversal 방어
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, html);
        }
        // DESIGN.md도 함께 배치
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        if (!f.delete()) log.warn("파일 삭제 실패: {}", f);
    }
```

(`writeMockupFiles`에서 `task.designMarkdown()`도 `dir/DESIGN.md`로 쓰도록 designDir 배치 블록에서 함께 처리:)

```java
                java.nio.file.Files.writeString(new File(designDir, "DESIGN.md").toPath(),
                        nvl(task.designMarkdown(), ""));
```

- [ ] **Step 3: 프롬프트 전달** — `renderImplementationPrompt`에 치환 추가:

```java
                .replace("{design_section}", renderDesignSection(task));
```

새 메서드:

```java
    /** 승인된 디자인이 있으면 구현 프롬프트에 삽입할 블록, 없으면 빈 문자열 (기존 작업 불변). */
    private static String renderDesignSection(WorkerTaskResponse task) {
        if (task.designMarkdown() == null || task.designMarkdown().isBlank()) return "";
        return """

                ## 확정된 디자인 (반드시 이 디자인 기준으로 구현)
                아래 디자인 문서와 `.design/` 디렉토리의 화면 목업 HTML이 승인된 확정 디자인입니다.
                화면 구현 시 목업의 레이아웃·색·타이포·컴포넌트 구조를 그대로 따르세요.
                `.design/`은 참조용이며 커밋하지 마세요.

                """ + task.designMarkdown();
    }
```

`defaultImplementationPrompt()`와 `application-worker.yml`의 `implementation-prompt-template` 둘 다 `## 사전 분석 결과` 섹션 앞에 `{design_section}` 한 줄 추가. PR 본문(`renderPrBody`)에 디자인 링크 추가 — `구현 SHA` 행 아래:

```java
                + (task.designMarkdown() == null ? "" : "- **디자인**: 확정 디자인 기반 구현 (작업 상세의 디자인 카드 참고)\n")
```

- [ ] **Step 4: 확인** — Run: `./gradlew build`. Expected: BUILD SUCCESSFUL (전체 테스트 포함).
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 워커 processDesign + 구현 프롬프트 디자인 전달"`

---

### Task 13: 프론트 — 등록 토글 + 디자인 카드 + 상태 칩

**Files:**
- Modify: `frontend/pages/tasks/index.vue` (토글 + body)
- Create: `frontend/components/DesignReviewCard.vue`
- Modify: `frontend/pages/tasks/[id].vue` (칩 + 카드 통합)
- Test: `frontend/components/DesignReviewCard.spec.ts` (기존 `InterviewPanel` spec 파일의 셋업 임포트/마운트 관용구를 복사해 사용)

**Interfaces:**
- Consumes: Task 7 API (`design/approve`, `design/reject`, TaskResponse.design/designRequested).

- [ ] **Step 1: index.vue 토글** — `draft` reactive에 필드 추가 (line 53):

```ts
const draft = reactive({ repoCatalogId: null as number | null, githubBranch: '', title: '', description: '', model: DEFAULT_MODEL, effort: DEFAULT_EFFORT, designRequested: false })
```

리셋 부분(~line 255)에 `draft.designRequested = false` 추가. 생성 요청 body(~line 287 블록)에 `designRequested: draft.designRequested,` 추가. (~line 315 블록은 인터뷰 시작 — Task 9에서 처리했으므로 건드리지 않음.) effort 셀렉트(~line 663) 아래 템플릿에:

```vue
          <q-toggle
            v-model="draft.designRequested"
            dense
            label="디자인 단계 포함"
          >
            <q-tooltip>분석 승인 후 워커가 화면 목업을 생성하고, 승인해야 구현이 시작됩니다</q-tooltip>
          </q-toggle>
```

- [ ] **Step 2: DesignReviewCard.vue 작성** (전체 신규 — 프로젝트 스타일: single quote, no semi):

```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'

interface MockupFile {
  path: string
  title: string
  html: string
}

interface DesignView {
  designMarkdown: string
  mockupFilesJson: string
  designProjectId: string | null
  designUrl: string | null
  rejectCount: number
  feedbackHistoryJson: string
  approved: boolean
  approvedBy: string | null
  approvedAt: string | null
  completedAt: string
}

const props = defineProps<{
  taskId: string | number
  status: string
  design: DesignView
  isAdmin: boolean
}>()

const emit = defineEmits<{ (e: 'refresh'): void }>()

const $q = useQuasar()
const MAX_REJECTS = 3

const mockups = computed<MockupFile[]>(() => {
  try {
    return JSON.parse(props.design.mockupFilesJson) as MockupFile[]
  } catch {
    return []
  }
})

const feedbackHistory = computed<Array<{ feedback: string; rejectedBy: string; rejectedAt: string }>>(() => {
  try {
    return JSON.parse(props.design.feedbackHistoryJson)
  } catch {
    return []
  }
})

const selectedTab = ref(0)
const rejectDialog = ref(false)
const rejectFeedback = ref('')
const submitting = ref(false)

const rejectLimitReached = computed(() => props.design.rejectCount >= MAX_REJECTS)

async function approveDesign() {
  if (!confirm('이 디자인을 승인하시겠습니까?\n승인 즉시 이 디자인 기준으로 구현이 시작됩니다.')) return
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/design/approve`, { method: 'POST' })
    $q.notify({ type: 'positive', message: '디자인 승인 — 구현 큐에 진입했습니다' })
    emit('refresh')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '디자인 승인 실패' })
  } finally {
    submitting.value = false
  }
}

async function submitReject() {
  if (!rejectFeedback.value.trim()) return
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/design/reject`, {
      method: 'POST',
      body: { feedback: rejectFeedback.value.trim() },
    })
    $q.notify({ type: 'warning', message: '디자인 반려 — 피드백 반영해 재생성합니다' })
    rejectDialog.value = false
    rejectFeedback.value = ''
    emit('refresh')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '반려 실패' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <q-card flat bordered class="q-mb-md">
    <q-card-section class="row items-center q-gutter-sm">
      <div class="text-h6">디자인</div>
      <q-chip
        v-if="design.approved"
        color="positive"
        text-color="white"
        icon="check"
        label="승인됨"
        dense
      />
      <q-chip
        v-else-if="design.rejectCount > 0"
        color="warning"
        text-color="white"
        icon="replay"
        :label="`반려 ${design.rejectCount}/3`"
        dense
      />
      <q-space />
      <q-btn
        v-if="design.designUrl"
        flat
        dense
        icon="open_in_new"
        label="Claude Design에서 열기"
        type="a"
        :href="design.designUrl"
        target="_blank"
      />
      <q-chip v-else dense outline icon="cloud_off" label="업로드 실패 — 로컬 미리보기만" />
      <template v-if="isAdmin && status === 'DESIGN_REVIEW'">
        <q-btn
          unelevated
          color="positive"
          icon="check"
          label="디자인 승인"
          :loading="submitting"
          data-test="design-approve"
          @click="approveDesign"
        />
        <q-btn
          outline
          color="warning"
          icon="replay"
          label="반려"
          :disable="rejectLimitReached"
          data-test="design-reject"
          @click="rejectDialog = true"
        >
          <q-tooltip v-if="rejectLimitReached">
            반려 한도 도달 — 승인 또는 작업 삭제만 가능합니다
          </q-tooltip>
        </q-btn>
      </template>
    </q-card-section>

    <q-separator />

    <q-card-section v-if="mockups.length">
      <q-tabs v-model="selectedTab" dense align="left" class="q-mb-sm">
        <q-tab v-for="(m, i) in mockups" :key="m.path" :name="i" :label="m.title" />
      </q-tabs>
      <!-- sandbox: same-origin 차단으로 생성 HTML을 앱 컨텍스트에서 격리 -->
      <iframe
        :srcdoc="mockups[selectedTab]?.html"
        sandbox="allow-scripts"
        style="width: 100%; height: 600px; border: 1px solid #e0e0e0; border-radius: 4px; background: #fff"
        :title="mockups[selectedTab]?.title"
      />
    </q-card-section>

    <q-separator />

    <q-card-section>
      <pre style="white-space: pre-wrap; font-family: 'Pretendard', sans-serif">{{ design.designMarkdown }}</pre>
    </q-card-section>

    <template v-if="feedbackHistory.length">
      <q-separator />
      <q-card-section>
        <div class="text-subtitle2 q-mb-sm">반려 이력</div>
        <q-list dense bordered>
          <q-item v-for="(f, i) in feedbackHistory" :key="i">
            <q-item-section>
              <q-item-label>{{ f.feedback }}</q-item-label>
              <q-item-label caption>{{ f.rejectedBy }} · {{ f.rejectedAt }}</q-item-label>
            </q-item-section>
          </q-item>
        </q-list>
      </q-card-section>
    </template>

    <q-dialog v-model="rejectDialog" persistent>
      <q-card style="min-width: 480px">
        <q-card-section class="text-h6">디자인 반려</q-card-section>
        <q-card-section>
          <q-input
            v-model="rejectFeedback"
            type="textarea"
            outlined
            autofocus
            label="반려 피드백 (필수)"
            hint="워커가 이 피드백을 반영해 디자인을 수정합니다"
            :rules="[(v: string) => !!v?.trim() || '피드백을 입력하세요']"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn v-close-popup flat label="취소" />
          <q-btn
            unelevated
            color="warning"
            label="반려"
            :disable="!rejectFeedback.trim()"
            :loading="submitting"
            @click="submitReject"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-card>
</template>
```

- [ ] **Step 3: [id].vue 통합** — `statusClass` 맵의 `CANCELLED` 행 위에 추가:

```ts
      DESIGN_PENDING: 'status-chip status-approved',
      DESIGNING: 'status-chip status-implementing',
      DESIGN_REVIEW: 'status-chip status-completed',
      DESIGN_FAILED: 'status-chip status-failed',
```

TS 인터페이스(TaskResponse — 파일 상단): `designRequested: boolean`과 `design: DesignView | null` 필드 추가 (DesignView 인터페이스는 DesignReviewCard와 동일 형태로 선언하거나 카드에 위임하고 `any` 회피를 위해 로컬 선언). 템플릿에서 분석 카드(`<q-card v-if="task.analysis"`) **앞**에 삽입:

```vue
      <DesignReviewCard
        v-if="task.design"
        :task-id="task.id"
        :status="task.status"
        :design="task.design"
        :is-admin="auth.isAdmin"
        @refresh="refresh"
      />
```

`DESIGN_FAILED`일 때 재시도: 기존 실패 UX 위치를 확인(`grep -n "retry" frontend/pages/tasks/[id].vue`)해 FAILED 재시도 버튼의 `v-if` 조건에 `|| task.status === 'DESIGN_FAILED'`를 추가.

- [ ] **Step 4: vitest 작성/실행** — `DesignReviewCard.spec.ts`: 기존 `InterviewPanel` spec의 마운트 셋업을 복사해 3케이스: ① `status='DESIGN_REVIEW'` + `isAdmin=true` → `[data-test="design-approve"]`/`[data-test="design-reject"]` 렌더, ② `rejectCount=3` → reject 버튼 disabled, ③ `isAdmin=false` → 버튼 없음. Run: `cd frontend && npx vitest run components/DesignReviewCard.spec.ts`. Expected: PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: 프론트 디자인 카드(미리보기/승인/반려) + 등록 토글"`

---

### Task 14: 라이브 스모크 (수동 검증 체크리스트)

**사전 조건:** 워커 머신 claude CLI에서 `/design-login` 1회 완료 (Pro 이상 플랜). 레포 카탈로그에 디자인 시스템 프로젝트 ID 설정(선택). 스택 재기동: `./scripts/start-public.sh` → `WORKERS=1 ./scripts/start-all.sh` (public env 필수 — 메모리 런북).

- [ ] "디자인 단계 포함" 켜고 UI 작업 등록 → 분석 완료 → 승인 → 상태 `디자인대기` 확인
- [ ] 워커가 claim → `디자인중` → 수 분 내 `디자인승인대기`
- [ ] 작업 상세: 목업 iframe 렌더, DESIGN.md 표시, Claude Design 링크(또는 업로드 실패 뱃지) 확인 → claude.ai/design에서 목업 열람
- [ ] 반려 1회 (피드백 입력) → `디자인대기` 재큐잉 → 재생성 결과에 피드백 반영 확인, 반려 이력 표시
- [ ] 디자인 승인 → `구현대기` → 구현 → PR 생성. PR 브랜치의 변경이 목업 레이아웃을 따르는지, `.design/`이 커밋에 없는지 확인
- [ ] 토글 끈 일반 작업 1건: 기존 플로우(승인 → 즉시 구현대기) 회귀 없음 확인
- [ ] 통계 카드에 디자인 상태 카운터 표시 확인

문제 발견 시 해당 태스크로 돌아가 수정 후 재검증. 완료 후 superpowers:finishing-a-development-branch로 PR 생성.
