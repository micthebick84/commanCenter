# 인터뷰 시점 이동 (등록 → 관리자 승인) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 등록 시점에 강제되던 대화형 인터뷰를 관리자 승인 시점으로 옮겨, 승인된 작업만 claude 토큰을 소모하고 요구사항 정교화를 관리자가 주도하게 한다.

**Architecture:** `TaskStatus`에 인터뷰 구간 4개 상태(`승인대기`/`인터뷰중`/`입력대기`/`플랜승인대기`)를 추가하고, `interview_session`을 task 하위 리소스로 만든다(`task_id`를 생성 시점에 채움). 승인 API가 세션을 만들고, 세션 전이는 `InterviewService`가 task 상태로 미러링하며, 플랜 확정이 기존 task를 구현 큐로 보낸다. 인터뷰 워커(Node)와 `/worker/interviews/*` 계약은 변경하지 않는다.

**Tech Stack:** Java 21 / Spring Boot 3.4.1 / Gradle / PostgreSQL + Flyway / JUnit5 + AssertJ + Testcontainers / Nuxt 3 + Quasar + vitest

**Spec:** `docs/superpowers/specs/2026-07-27-interview-at-approval-design.md`

## Global Constraints

- 브랜치 `spec/interview-at-approval`에서 이어서 작업한다 (스펙 커밋이 이미 있음).
- 백엔드 테스트 실행: `./gradlew test`. Testcontainers 테스트는 `RUN_TESTCONTAINERS=true ./gradlew test`로만 실행된다(`@EnabledIfEnvironmentVariable`).
- 모든 task 상태 전이는 `TaskService` 또는 `InterviewService`를 통해서만 하고, 반드시 `TaskStatusHistory.log(...)`를 남긴다.
- DB 상태값은 **한글 문자열**로 저장한다. Java enum 이름과 DB 값은 분리되어 있다(`TaskStatus.dbValue()`).
- `task.status`는 CHECK 제약 없는 `VARCHAR(30)`이므로 새 상태 추가에 DDL이 필요 없다.
- Flyway 신규 마이그레이션은 `V18__interview_at_approval.sql` 하나만 쓴다.
- 프론트엔드 코드 스타일: **작은따옴표 + 세미콜론 없음**, 2-space, trailing comma, `<script setup lang="ts">`.
- **`npm run lint-prettier`를 `frontend/` 전체에 실행 금지** (줄바꿈 churn). 필요하면 단일 파일만.
- 프론트 테스트: `cd frontend && npx vitest run <파일>`.
- 인터뷰 워커(`netismaker-interview-service/`)와 `/worker/interviews/*` 컨트롤러는 **수정 금지**. 이 계약이 깨지지 않는 것이 설계의 핵심 안전장치다.

---

## File Structure

**신규**
- `src/main/resources/db/migration/V18__interview_at_approval.sql` — 큐 통계 뷰 재생성 + 인덱스 + 고아 세션 마감
- `src/main/java/com/hamonsoft/netismaker/dto/ApproveRequest.java` — 승인 시 관리자가 고른 model/effort/MCP
- `src/test/java/com/hamonsoft/netismaker/entity/TaskStatusInterviewTest.java`
- `src/test/java/com/hamonsoft/netismaker/repository/QueueStatsInterviewCountersTest.java`
- `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRegistrationTest.java`
- `src/test/java/com/hamonsoft/netismaker/service/TaskServiceApproveInterviewTest.java`
- `src/test/java/com/hamonsoft/netismaker/service/InterviewTaskMirrorTest.java`
- `src/test/java/com/hamonsoft/netismaker/service/InterviewConfirmContractTest.java` (기존 `InterviewRegisterContractTest.java` 대체)
- `frontend/components/McpPicker.vue` — MCP 카탈로그 칩 선택 (index.vue에서 추출)
- `frontend/components/ApproveDialog.vue` — 승인 다이얼로그 (model/effort/MCP)
- `frontend/components/ApproveDialog.spec.ts`

**수정**
- `entity/TaskStatus.java` — 상태 4개 + 다이어그램 javadoc
- `dto/TaskCreateRequest.java` — 4필드로 축소
- `dto/TaskResponse.java` — `interviewSessionId` 추가
- `dto/QueueStats.java`, `repository/QueueStatsRepository.java` — 카운터 3개
- `repository/TaskRepository.java` — `countActiveByRequester`에 새 상태 4개
- `repository/InterviewSessionRepository.java` — task별 조회 2개 추가, `findActiveByRequester` 제거
- `service/TaskService.java` — create/cancel/approve/softDelete
- `service/InterviewService.java` — createForTask/mirrorTask/confirm, create·listActive 제거
- `controller/TaskController.java` — approve body, get 응답에 세션 id
- `controller/InterviewController.java` — confirm으로 개명, ROLE_ADMIN, create/active 제거
- `frontend/pages/tasks/index.vue` — 등록 폼 축소, 인터뷰 임베드 제거
- `frontend/pages/tasks/[id].vue` — 승인 버튼 + 인터뷰 패널
- `frontend/components/InterviewPanel.vue` — confirm + readonly
- `frontend/components/QueueStatsBar.vue` — 타일 3개
- `CLAUDE.md` — 상태머신 섹션

**삭제**
- `dto/CreateInterviewRequest.java`, `dto/InterviewCreatedResponse.java`, `dto/InterviewSummary.java`
- `frontend/composables/interviewResume.ts` + `interviewResume.spec.ts`
- `src/test/java/com/hamonsoft/netismaker/repository/InterviewActiveQueryTest.java`

---

### Task 1: TaskStatus에 인터뷰 구간 4개 상태 추가

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/TaskStatus.java`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/TaskStatusInterviewTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `TaskStatus.AWAITING_APPROVAL("승인대기")`, `TaskStatus.INTERVIEWING("인터뷰중")`, `TaskStatus.INTERVIEW_INPUT("입력대기")`, `TaskStatus.INTERVIEW_REVIEW("플랜승인대기")` — 이후 모든 태스크가 이 이름을 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/hamonsoft/netismaker/entity/TaskStatusInterviewTest.java`:

```java
package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusInterviewTest {

    @Test
    void interview_statuses_have_korean_db_values() {
        assertThat(TaskStatus.AWAITING_APPROVAL.dbValue()).isEqualTo("승인대기");
        assertThat(TaskStatus.INTERVIEWING.dbValue()).isEqualTo("인터뷰중");
        assertThat(TaskStatus.INTERVIEW_INPUT.dbValue()).isEqualTo("입력대기");
        assertThat(TaskStatus.INTERVIEW_REVIEW.dbValue()).isEqualTo("플랜승인대기");
    }

    @Test
    void interview_statuses_round_trip_from_db() {
        assertThat(TaskStatus.fromDb("승인대기")).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(TaskStatus.fromDb("인터뷰중")).isEqualTo(TaskStatus.INTERVIEWING);
        assertThat(TaskStatus.fromDb("입력대기")).isEqualTo(TaskStatus.INTERVIEW_INPUT);
        assertThat(TaskStatus.fromDb("플랜승인대기")).isEqualTo(TaskStatus.INTERVIEW_REVIEW);
    }

    @Test
    void db_values_stay_unique_and_fit_varchar30() {
        long distinct = Arrays.stream(TaskStatus.values()).map(TaskStatus::dbValue).distinct().count();
        assertThat(distinct).isEqualTo(TaskStatus.values().length);
        assertThat(Arrays.stream(TaskStatus.values()).allMatch(s -> s.dbValue().length() <= 30)).isTrue();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*TaskStatusInterviewTest'`
Expected: 컴파일 실패 — `cannot find symbol: variable AWAITING_APPROVAL`

- [ ] **Step 3: 상태 추가**

`entity/TaskStatus.java`의 enum 상수 목록 맨 앞(`PENDING` 위)에 추가:

```java
    AWAITING_APPROVAL("승인대기"),
    PENDING("작업대기"),
```

그리고 `CANCELLED` 앞에 추가:

```java
    INTERVIEWING("인터뷰중"),
    INTERVIEW_INPUT("입력대기"),
    INTERVIEW_REVIEW("플랜승인대기"),
    CANCELLED("취소됨");
```

- [ ] **Step 4: javadoc 다이어그램 갱신**

`TaskStatus.java` 클래스 javadoc 맨 위(`분석 단계:` 블록 앞)에 인터뷰 구간을 추가한다:

```java
/**
 * 작업 상태 머신.
 *
 *  인터뷰 단계 (등록 → 관리자 승인 → 플랜 확정):
 *   [승인대기] ──(관리자 승인)──→ [인터뷰중] ⇄ [입력대기]
 *                                     │
 *                               (플랜 생성)
 *                                     ↓
 *                             [플랜승인대기] ──(구현 진행)──→ [구현대기] | [디자인대기]
 *   [인터뷰중|입력대기|플랜승인대기] ──(실패/만료/취소)──→ [승인대기]
 *
 *  분석 단계 (레거시 자동분석 경로 — API로만 진입):
 *   [작업대기] ──→ [분석중] ──→ [분석완료]
 *   ...(이하 기존 주석 유지)
 */
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*TaskStatusInterviewTest'`
Expected: PASS (3 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/TaskStatus.java \
        src/test/java/com/hamonsoft/netismaker/entity/TaskStatusInterviewTest.java
git commit -m "feat: TaskStatus에 인터뷰 구간 4개 상태 추가"
```

---

### Task 2: V18 마이그레이션 + 큐 통계 카운터 3개

**Files:**
- Create: `src/main/resources/db/migration/V18__interview_at_approval.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/QueueStats.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/QueueStatsRepository.java`
- Test: `src/test/java/com/hamonsoft/netismaker/repository/QueueStatsInterviewCountersTest.java`

**Interfaces:**
- Consumes: Task 1의 `TaskStatus` 상수
- Produces: `QueueStats.pendingApproval()`, `QueueStats.interviewing()`, `QueueStats.planReview()` — Task 12 프론트가 같은 이름의 JSON 필드를 읽는다. 인덱스 `idx_interview_session_task`는 Task 8이 쓴다.

- [ ] **Step 1: 마이그레이션 작성**

Create `src/main/resources/db/migration/V18__interview_at_approval.sql`:

```sql
-- V18: 인터뷰 시점을 등록 → 관리자 승인으로 이동.
-- task.status는 CHECK 제약 없는 VARCHAR(30)이라 새 상태(승인대기/인터뷰중/입력대기/플랜승인대기)
-- 추가에 DDL이 필요 없다. 여기서는 뷰/인덱스/데이터 정리만 한다.

-- 1) 배포 시점의 고아 세션 마감: task_id 없는 비종료 세션은 확정할 곳이 없다.
UPDATE com.interview_session
   SET status = '취소됨', updated_at = now()
 WHERE task_id IS NULL
   AND status IN ('인터뷰대기', '인터뷰중', '입력대기', '플랜완료');

-- 2) task별 최신 세션 조회 인덱스 (작업 상세가 매 조회마다 사용).
CREATE INDEX IF NOT EXISTS idx_interview_session_task
    ON com.interview_session(task_id, created_at DESC);

-- 3) 큐 통계 뷰에 인터뷰 카운터 3개 추가.
--    CREATE OR REPLACE는 컬럼 추가를 거부하므로 DROP 후 재생성 (V15/V16과 동일 패턴).
--    기존 awaiting_approval(분석완료 + 미승인)과 이름이 겹치지 않게 pending_approval을 쓴다.
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
    COUNT(*) FILTER (WHERE status = '승인대기' AND deleted_at IS NULL)              AS pending_approval,
    COUNT(*) FILTER (WHERE status IN ('인터뷰중', '입력대기') AND deleted_at IS NULL) AS interviewing,
    COUNT(*) FILTER (WHERE status = '플랜승인대기' AND deleted_at IS NULL)          AS plan_review,
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
```

- [ ] **Step 2: 실패하는 테스트 작성**

Create `src/test/java/com/hamonsoft/netismaker/repository/QueueStatsInterviewCountersTest.java`:

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.QueueStats;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QueueStatsInterviewCountersTest {

    @Autowired private QueueStatsRepository statsRepo;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private void save(TaskStatus status) {
        Task t = Task.create("hamonsoft/netis-backend", "main", "제목", "설명", "user1", 3,
                List.of(), null, null);
        t.setStatus(status);
        taskRepo.save(t);
    }

    @Test
    void interview_counters_are_exposed() {
        save(TaskStatus.AWAITING_APPROVAL);
        save(TaskStatus.INTERVIEWING);
        save(TaskStatus.INTERVIEW_INPUT);
        save(TaskStatus.INTERVIEW_REVIEW);

        QueueStats s = statsRepo.fetch();

        assertThat(s.pendingApproval()).isEqualTo(1);
        assertThat(s.interviewing()).isEqualTo(2);   // 인터뷰중 + 입력대기
        assertThat(s.planReview()).isEqualTo(1);
        assertThat(s.pending()).isZero();            // 레거시 '작업대기'와 분리됨
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*QueueStatsInterviewCountersTest'`
Expected: 컴파일 실패 — `cannot find symbol: method pendingApproval()`

- [ ] **Step 4: QueueStats에 필드 3개 추가**

`dto/QueueStats.java`에서 `undeploying` 다음, `avgDurationMs` 앞에 추가:

```java
        long undeploying,
        long pendingApproval,
        long interviewing,
        long planReview,
        Double avgDurationMs
```

- [ ] **Step 5: QueueStatsRepository 쿼리/매핑 확장**

`repository/QueueStatsRepository.java`의 SELECT에 컬럼을 추가하고 인덱스를 이어붙인다:

```java
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT pending, in_progress, awaiting_approval,
                       approved, implementing, pr_created, implementation_failed, failed,
                       design_pending, designing, design_review, design_failed,
                       deploy_pending, deploying, deployed, deploy_failed, deploy_lost,
                       undeploy_pending, undeploying,
                       pending_approval, interviewing, plan_review,
                       avg_duration_ms
                FROM com.task_queue_stats
                """).getSingleResult();
```

그리고 생성자 인자에서 `row[18]` 다음에 세 줄을 넣고 avg 인덱스를 옮긴다:

```java
                ((Number) row[18]).longValue(),
                ((Number) row[19]).longValue(),
                ((Number) row[20]).longValue(),
                ((Number) row[21]).longValue(),
                row[22] == null ? null : ((Number) row[22]).doubleValue()
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*QueueStatsInterviewCountersTest'`
Expected: PASS

- [ ] **Step 7: 전체 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add src/main/resources/db/migration/V18__interview_at_approval.sql \
        src/main/java/com/hamonsoft/netismaker/dto/QueueStats.java \
        src/main/java/com/hamonsoft/netismaker/repository/QueueStatsRepository.java \
        src/test/java/com/hamonsoft/netismaker/repository/QueueStatsInterviewCountersTest.java
git commit -m "feat: V18 마이그레이션 + 큐 통계에 인터뷰 카운터 3개"
```

---

### Task 3: 등록을 승인대기로 (폼 축소 · 한도 · 취소 확대)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskCreateRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java:76-97` (`create`), `:150-165` (`cancel`)
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java:22-30` (`countActiveByRequester`)
- Modify: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRepoCatalogTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/TaskApiIntegrationTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/TaskDesignFlowIntegrationTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/RepoCatalogDeleteSnapshotTest.java`, `src/test/java/com/hamonsoft/netismaker/dto/RepoCatalogRequestValidationTest.java` (생성자 인자 축소)
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRegistrationTest.java`

**Interfaces:**
- Consumes: `TaskStatus.AWAITING_APPROVAL` (Task 1)
- Produces: `new TaskCreateRequest(Long repoCatalogId, String githubBranch, String title, String description)` — 4-arg 레코드. `TaskService.create(TaskCreateRequest, String)`는 시그니처 그대로이며 `승인대기` task를 반환한다.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRegistrationTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskServiceRegistrationTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    /** catalog id=1 = V14 시드 항목 (alias 'Netis7.0'). */
    private TaskCreateRequest req(String title) {
        return new TaskCreateRequest(1L, "main", title, "설명");
    }

    @Test
    void create_starts_in_awaiting_approval_without_model_or_mcps() {
        Task t = taskService.create(req("작업 A"), "user1");

        assertThat(t.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        assertThat(t.getMcpsExtra()).isEmpty();
        assertThat(t.isDesignRequested()).isFalse();
        assertThat(t.getModel()).isEqualTo(ModelEffortPolicy.DEFAULT_MODEL);
        assertThat(t.getEffort()).isEqualTo(ModelEffortPolicy.DEFAULT_EFFORT);
        assertThat(historyRepo.findAll()).anySatisfy(h ->
                assertThat(h.getToStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL));
    }

    @Test
    void awaiting_approval_counts_against_the_user_limit() {
        for (int i = 0; i < 5; i++) taskService.create(req("작업 " + i), "user1");

        assertThatThrownBy(() -> taskService.create(req("여섯번째"), "user1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("한도");
    }

    @Test
    void requester_can_cancel_while_awaiting_approval() {
        Task t = taskService.create(req("작업 A"), "user1");

        Task cancelled = taskService.cancel(t.getId(), "user1", false);

        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskServiceRegistrationTest'`
Expected: 컴파일 실패 — `TaskCreateRequest` 4-arg 생성자 없음

- [ ] **Step 3: TaskCreateRequest 축소**

Replace the whole body of `dto/TaskCreateRequest.java`:

```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 작업 등록 요청. 모델/effort/MCP/디자인 여부는 등록에서 받지 않는다 —
 * 관리자가 승인(=인터뷰 시작) 시점에 ApproveRequest로 결정한다.
 * (구 클라이언트가 그 필드들을 보내도 Jackson이 무시한다.)
 */
public record TaskCreateRequest(
        @NotNull(message = "repoCatalogId는 필수입니다 (레포 카탈로그에서 선택)")
        Long repoCatalogId,

        @Size(max = 255)
        String githubBranch,

        @NotBlank
        @Size(max = 500)
        String title,

        @NotBlank
        String description
) {}
```

- [ ] **Step 4: TaskService.create 수정**

`service/TaskService.java`의 `create`를 교체:

```java
    @Transactional
    public Task create(TaskCreateRequest req, String requesterId) {
        long active = taskRepo.countActiveByRequester(requesterId);
        if (active >= userConcurrentLimit) {
            throw TaskException.tooManyRequests(
                    "동시에 보유할 수 있는 미완료 작업 한도(" + userConcurrentLimit + ")를 초과했습니다");
        }
        RepoCatalogService.ResolvedRepo repo = repoCatalogService.resolveForRegistration(req.repoCatalogId());
        // 모델/effort/MCP는 승인 시점에 관리자가 결정 — 등록은 서버 기본값으로 시작한다.
        Task t = Task.create(repo.ownerRepo(), req.githubBranch(), req.title(),
                             req.description(), requesterId, maxRetry,
                             new ArrayList<>(), null, null);
        t.setStatus(TaskStatus.AWAITING_APPROVAL);
        t.setGitUrl(repo.gitUrl());
        t.setRepoAlias(repo.alias());
        t.setRepoCatalogId(repo.catalogId());
        Task saved = taskRepo.save(t);
        historyRepo.save(TaskStatusHistory.log(saved.getId(), null, TaskStatus.AWAITING_APPROVAL,
                "user", requesterId, "작업 등록"));
        return saved;
    }
```

`resolveMcpExtras`와 `ModelEffortPolicy` import는 그대로 둔다 — Task 4의 승인 경로가 쓴다.

- [ ] **Step 5: cancel을 승인대기까지 확대**

`service/TaskService.java`의 `cancel`에서 상태 가드를 교체:

```java
        if (t.getStatus() != TaskStatus.PENDING && t.getStatus() != TaskStatus.AWAITING_APPROVAL) {
            throw TaskException.conflict("승인대기/작업대기 상태에서만 취소할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
```

- [ ] **Step 6: 사용자 동시 한도에 새 상태 반영**

`repository/TaskRepository.java`의 `countActiveByRequester` JPQL `status IN (...)` 목록에 4개 추가:

```java
          AND t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.AWAITING_APPROVAL,
                           com.hamonsoft.netismaker.entity.TaskStatus.INTERVIEWING,
                           com.hamonsoft.netismaker.entity.TaskStatus.INTERVIEW_INPUT,
                           com.hamonsoft.netismaker.entity.TaskStatus.INTERVIEW_REVIEW,
                           com.hamonsoft.netismaker.entity.TaskStatus.PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.IN_PROGRESS,
                           com.hamonsoft.netismaker.entity.TaskStatus.COMPLETED,
                           com.hamonsoft.netismaker.entity.TaskStatus.FAILED)
```

- [ ] **Step 7: 기존 테스트의 생성자 호출 축소**

아래 파일에서 `new TaskCreateRequest(...)` 호출을 4-arg로 줄인다. 뒤쪽 인자(mcpCatalogIds/model/effort/designRequested)는 그냥 삭제한다:

- `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRepoCatalogTest.java`
- `src/test/java/com/hamonsoft/netismaker/controller/TaskApiIntegrationTest.java`
- `src/test/java/com/hamonsoft/netismaker/controller/TaskDesignFlowIntegrationTest.java`
- `src/test/java/com/hamonsoft/netismaker/controller/RepoCatalogDeleteSnapshotTest.java`
- `src/test/java/com/hamonsoft/netismaker/dto/RepoCatalogRequestValidationTest.java`

찾기: `grep -rn "new TaskCreateRequest(" src/test`

이 과정에서 "등록 직후 상태가 PENDING"을 단언하는 테스트가 있으면 `AWAITING_APPROVAL`로 고친다.
`designRequested=true`로 등록하던 테스트가 있으면(주로 `TaskDesignFlowIntegrationTest`) 등록 후
`taskRepo`로 직접 `t.setDesignRequested(true)` + `t.setStatus(TaskStatus.COMPLETED)`를 세팅해
**레거시 승인 경로**를 그대로 검증하도록 바꾼다 — 그 테스트의 관심사는 디자인 큐이지 등록 폼이 아니다.

- [ ] **Step 8: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskServiceRegistrationTest' --tests '*TaskServiceRepoCatalogTest' --tests '*TaskApiIntegrationTest' --tests '*TaskDesignFlowIntegrationTest'`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat: 작업 등록을 승인대기 상태로 (모델/MCP는 승인 시 결정)"
```

---

### Task 4: 승인 = 인터뷰 시작

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/ApproveRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (`createForTask` 추가)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java:198-226` (`approve`)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java:91-98`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceApproveInterviewTest.java`

**Interfaces:**
- Consumes: `TaskStatus.AWAITING_APPROVAL`/`INTERVIEWING` (Task 1)
- Produces:
  - `record ApproveRequest(List<Long> mcpCatalogIds, String model, String effort)`
  - `InterviewSession InterviewService.createForTask(Task t, String model, String effort, List<TaskMcpSpec> extras)` — `task_id`가 채워진 QUEUED 세션 반환
  - `void TaskService.approve(Long taskId, String adminId, ApproveRequest req)` + 레거시 2-arg 오버로드

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/hamonsoft/netismaker/service/TaskServiceApproveInterviewTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.ApproveRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskServiceApproveInterviewTest {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task register() {
        return taskService.create(new TaskCreateRequest(1L, "main", "RBAC 추가", "역할 기반 권한"), "user1");
    }

    @Test
    void approving_a_pending_task_starts_an_interview_session() {
        Task t = register();

        taskService.approve(t.getId(), "admin", new ApproveRequest(List.of(), "claude-opus-4-8", "max"));

        Task after = taskRepo.findById(t.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.INTERVIEWING);
        assertThat(after.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(after.getEffort()).isEqualTo("max");
        // 인터뷰 워커 소유권은 세션 컬럼이 갖는다 — task 쪽은 비워 둬야 StaleTaskRecoveryJob과 섞이지 않는다
        assertThat(after.getWorkerId()).isNull();
        assertThat(after.getClaimedAt()).isNull();

        List<InterviewSession> sessions = sessionRepo.findAll();
        assertThat(sessions).hasSize(1);
        InterviewSession s = sessions.get(0);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getTaskId()).isEqualTo(t.getId());
        assertThat(s.getRequesterId()).isEqualTo("user1");        // 원 요청자 보존
        assertThat(s.getGithubRepo()).isEqualTo(after.getGithubRepo());
        assertThat(s.getGithubBranch()).isEqualTo("main");
        assertThat(s.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(s.getEffort()).isEqualTo("max");
    }

    @Test
    void approve_body_is_optional_and_falls_back_to_defaults() {
        Task t = register();

        taskService.approve(t.getId(), "admin", null);

        InterviewSession s = sessionRepo.findAll().get(0);
        assertThat(s.getModel()).isEqualTo(ModelEffortPolicy.DEFAULT_MODEL);
        assertThat(s.getEffort()).isEqualTo(ModelEffortPolicy.DEFAULT_EFFORT);
    }

    @Test
    void double_approve_is_rejected_and_creates_no_second_session() {
        Task t = register();
        taskService.approve(t.getId(), "admin", null);

        assertThatThrownBy(() -> taskService.approve(t.getId(), "admin", null))
                .isInstanceOf(TaskException.class);

        assertThat(sessionRepo.findAll()).hasSize(1);
    }

    @Test
    void legacy_completed_task_still_goes_straight_to_the_implementation_queue() {
        Task t = register();
        // 레거시 자동분석 경로 재현: 분석완료 + 분석 결과 존재
        t.setStatus(TaskStatus.COMPLETED);
        taskRepo.save(t);
        analysisRepo.save(TaskAnalysis.create(t.getId(), "# 분석", "[]", null, 1000L));

        taskService.approve(t.getId(), "admin", null);

        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(sessionRepo.findAll()).isEmpty();
    }

    @Test
    void invalid_model_effort_combination_is_rejected_before_any_session_exists() {
        Task t = register();

        assertThatThrownBy(() -> taskService.approve(t.getId(), "admin",
                new ApproveRequest(List.of(), "claude-haiku-4-5", "max")))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("effort");

        assertThat(sessionRepo.findAll()).isEmpty();
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void unknown_mcp_catalog_id_is_rejected_before_any_session_exists() {
        Task t = register();

        assertThatThrownBy(() -> taskService.approve(t.getId(), "admin",
                new ApproveRequest(List.of(999_999L), null, null)))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("MCP");

        assertThat(sessionRepo.findAll()).isEmpty();
        assertThat(taskRepo.findById(t.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }
}
```

import 목록에 `TaskAnalysis`와 `TaskAnalysisRepository`를 넣고, 필드에
`@Autowired private TaskAnalysisRepository analysisRepo;`를 다른 `@Autowired` 필드들과 함께 선언한다.
`clean()`에서 `analysisRepo.deleteAll()`도 `taskRepo.deleteAll()` 앞에 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskServiceApproveInterviewTest'`
Expected: 컴파일 실패 — `ApproveRequest` 없음

- [ ] **Step 3: ApproveRequest DTO 생성**

Create `src/main/java/com/hamonsoft/netismaker/dto/ApproveRequest.java`:

```java
package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * 관리자 승인 요청 바디. 승인 = 인터뷰 시작이므로 인터뷰가 쓸 모델/effort/MCP를 여기서 정한다.
 * 전부 optional — 없으면 ModelEffortPolicy 기본값 + MCP 없음.
 * 레거시 경로(분석완료 → 구현대기) 승인에서는 무시된다.
 */
public record ApproveRequest(
        List<Long> mcpCatalogIds,
        String model,
        String effort
) {}
```

- [ ] **Step 4: InterviewService.createForTask 추가**

`service/InterviewService.java`의 기존 `create(CreateInterviewRequest, String)` 아래에 추가:

```java
    /**
     * 관리자 승인 → 해당 task의 인터뷰 세션 생성(QUEUED).
     * task 필드를 스냅샷으로 복사한다 — 워커는 세션만 보고 일하므로 계약이 바뀌지 않는다.
     * task 상태 전이는 호출자(TaskService)가 담당한다.
     */
    @Transactional
    public InterviewSession createForTask(Task t, String model, String effort,
                                          List<TaskMcpSpec> extras) {
        InterviewSession s = InterviewSession.create(t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), t.getRequesterId(),
                new ArrayList<>(extras == null ? List.of() : extras), model, effort);
        s.setGitUrl(t.getGitUrl());
        s.setRepoAlias(t.getRepoAlias());
        s.setRepoCatalogId(t.getRepoCatalogId());
        s.setTaskId(t.getId());
        return sessionRepo.save(s);
    }
```

- [ ] **Step 5: TaskService.approve를 분기 구조로 교체**

`service/TaskService.java`에 `InterviewService`를 주입한다(생성자 파라미터 + 필드 추가). `TaskService → InterviewService` 단방향이라 순환 참조가 없다 — `InterviewService`는 리포지토리만 의존한다.

`approve`를 아래로 교체:

```java
    /**
     * 관리자 승인. 상태에 따라 두 의미로 갈린다.
     *
     *  승인대기 → 인터뷰 세션 생성 + task=인터뷰중  (현행 경로)
     *  분석완료 → analysis.approved + task=구현대기|디자인대기 (레거시 자동분석 경로)
     */
    @Transactional
    public void approve(Long taskId, String adminId, ApproveRequest req) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        switch (t.getStatus()) {
            case AWAITING_APPROVAL -> startInterview(t, adminId, req);
            case COMPLETED -> approveAnalysis(t, adminId);
            default -> throw TaskException.conflict(
                    "승인대기 또는 분석완료 상태에서만 승인할 수 있습니다 (현재: "
                            + t.getStatus().dbValue() + ")");
        }
    }

    /** 레거시 2-arg 호출부(테스트 등) 호환 — 바디 없는 승인. */
    @Transactional
    public void approve(Long taskId, String adminId) {
        approve(taskId, adminId, null);
    }

    /** 승인대기 → 인터뷰중. 세션을 만들고 모델/effort/MCP 선택을 task에도 박제한다. */
    private void startInterview(Task t, String adminId, ApproveRequest req) {
        List<TaskMcpSpec> extras = resolveMcpExtras(req == null ? null : req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req == null ? null : req.model());
        String effort = ModelEffortPolicy.resolveEffort(req == null ? null : req.effort());
        ModelEffortPolicy.validate(model, effort);   // 검증이 먼저 — 실패 시 세션이 생기면 안 된다

        t.setModel(model);
        t.setEffort(effort);
        t.setMcpsExtra(new ArrayList<>(extras));
        t.setFailureReason(null);
        interviewService.createForTask(t, model, effort, extras);

        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.INTERVIEWING);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.INTERVIEWING,
                "user", adminId, "관리자 승인 → 인터뷰 시작"));
    }

    /** 레거시: 분석완료 + admin 승인 → 구현/디자인 큐. 기존 동작 그대로. */
    private void approveAnalysis(Task t, String adminId) {
        TaskAnalysis a = analysisRepo.findById(t.getId())
                .orElseThrow(() -> TaskException.conflict("분석 결과가 없습니다"));
        boolean toDesign = t.isDesignRequested();
        String reason;
        if (!a.isApproved()) {
            a.setApproved(true);
            a.setApprovedBy(adminId);
            a.setApprovedAt(OffsetDateTime.now());
            reason = toDesign ? "관리자 승인 → 디자인 큐 진입" : "관리자 승인 → 구현 큐 진입";
        } else {
            reason = toDesign ? "이미 승인된 작업 → 디자인 큐 재진입" : "이미 승인된 작업 → 구현 큐 재진입";
        }
        TaskStatus from = t.getStatus();
        TaskStatus to = toDesign ? TaskStatus.DESIGN_PENDING : TaskStatus.APPROVED;
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", adminId,
                reason + (to == TaskStatus.DESIGN_PENDING ? " (디자인 구간)" : "")));
    }
```

`import com.hamonsoft.netismaker.dto.ApproveRequest;`를 추가한다.
기존 `approve`가 `TaskAnalysis`를 반환했으므로, 그 반환값을 쓰는 호출부가 있으면 컴파일 오류가 난다 — 컨트롤러는 무시하고 있으니 다음 스텝에서 함께 정리한다.

- [ ] **Step 6: 컨트롤러가 승인 바디를 받도록 수정**

`controller/TaskController.java`:

```java
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse approve(@PathVariable Long id,
                                @RequestBody(required = false) ApproveRequest body,
                                JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.approve(id, adminId, body);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }
```

`import com.hamonsoft.netismaker.dto.ApproveRequest;` 추가.

- [ ] **Step 7: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskServiceApproveInterviewTest' --tests '*TaskServiceDesignTest'`
Expected: PASS (`TaskServiceDesignTest`가 레거시 승인 경로 회귀를 지켜준다)

- [ ] **Step 8: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat: 관리자 승인이 인터뷰 세션을 생성하도록 (레거시 분석완료 승인은 유지)"
```

---

### Task 5: 세션 전이를 task 상태로 미러링

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (`recordQuestion`, `submitAnswer`, `recordPlan`, `fail`, `expire`, `cancel`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/InterviewTaskMirrorTest.java`

**Interfaces:**
- Consumes: `TaskStatus.INTERVIEWING/INTERVIEW_INPUT/INTERVIEW_REVIEW/AWAITING_APPROVAL` (Task 1), `InterviewService.createForTask` (Task 4)
- Produces: 세션 전이 시 `task.status`가 함께 움직인다는 불변식. Task 6의 `confirm`이 `INTERVIEW_REVIEW`를 전제로 동작한다.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/hamonsoft/netismaker/service/InterviewTaskMirrorTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewTaskMirrorTest {

    @Autowired private TaskService taskService;
    @Autowired private InterviewService interviewService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    private Long taskId;
    private Long sid;

    @BeforeEach void seed() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "RBAC", "역할 기반 권한"), "user1");
        taskId = t.getId();
        taskService.approve(taskId, "admin", null);
        sid = sessionRepo.findAll().get(0).getId();
    }

    private TaskStatus taskStatus() {
        return taskRepo.findById(taskId).orElseThrow().getStatus();
    }

    private void askQuestion() {
        interviewService.claim("iw-1");
        interviewService.recordQuestion(sid, "iw-1",
                new WorkerQuestionRequest("권한 모델은 RBAC인가요?", "sess-1", "question", new BigDecimal("0.01")));
    }

    @Test
    void question_moves_task_to_input_waiting() {
        askQuestion();
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_INPUT);
    }

    @Test
    void answer_moves_task_back_to_interviewing() {
        askQuestion();
        int seq = interviewService.getResponse(sid, "admin", true).turns().get(0).seq();

        interviewService.submitAnswer(sid, "admin", true, new AnswerRequest("RBAC 맞습니다", seq));

        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEWING);
    }

    @Test
    void duplicate_answer_is_a_no_op_for_the_task_too() {
        askQuestion();
        int seq = interviewService.getResponse(sid, "admin", true).turns().get(0).seq();
        interviewService.submitAnswer(sid, "admin", true, new AnswerRequest("RBAC 맞습니다", seq));
        // 재큐된 세션을 다시 잡아 질문을 하나 더 던진 뒤, 1차 답변을 재제출한다.
        interviewService.claim("iw-1");
        interviewService.recordQuestion(sid, "iw-1",
                new WorkerQuestionRequest("다음 질문", "sess-1", "question", BigDecimal.ZERO));
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_INPUT);

        interviewService.submitAnswer(sid, "admin", true, new AnswerRequest("RBAC 맞습니다", seq));

        // 중복 답변은 no-op — 여전히 입력대기여야 한다 (멱등성 회귀 지점)
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_INPUT);
    }

    @Test
    void plan_moves_task_to_plan_review() {
        interviewService.claim("iw-1");
        interviewService.recordPlan(sid, "iw-1", new WorkerPlanRequest(
                "# 설계", "# 플랜", "[]", new BigDecimal("0.4"), 1000L));

        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEW_REVIEW);
    }

    @Test
    void failure_returns_the_task_to_awaiting_approval() {
        interviewService.claim("iw-1");
        interviewService.fail(sid, "stale-recovery", "워커 사망");

        assertThat(taskStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void expiry_returns_the_task_to_awaiting_approval() {
        askQuestion();
        interviewService.expire(sid, "idle TTL 초과");

        assertThat(taskStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void cancel_returns_the_task_to_awaiting_approval() {
        interviewService.cancel(sid, "admin", true);

        assertThat(taskStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
    }

    @Test
    void re_approval_after_failure_creates_a_second_session() {
        interviewService.claim("iw-1");
        interviewService.fail(sid, "stale-recovery", "워커 사망");

        taskService.approve(taskId, "admin", null);

        assertThat(sessionRepo.findAll()).hasSize(2);
        assertThat(taskStatus()).isEqualTo(TaskStatus.INTERVIEWING);
    }
}
```

레코드 인자 순서(2026-07-27 기준): `WorkerQuestionRequest(content, claudeSessionId, kind, costUsd)`,
`AnswerRequest(answer, replyToSeq)`, `WorkerPlanRequest(designMarkdown, planMarkdown, planJson, costUsd, durationMs)`.
사용하지 않는 `InterviewSession` import는 넣지 않는다.

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewTaskMirrorTest'`
Expected: FAIL — `expected: INTERVIEW_INPUT but was: INTERVIEWING`

- [ ] **Step 3: 미러링 헬퍼 추가**

`service/InterviewService.java`의 private 헬퍼 영역(`touch` 근처)에 추가:

```java
    /**
     * 세션 전이를 소유 task 상태로 미러링한다. 관리자가 행동해야 하는 구간(입력대기/플랜승인대기)을
     * 작업 목록에서 바로 식별하기 위한 것. task_id가 없는 레거시 세션은 조용히 무시한다.
     */
    private void mirrorTask(InterviewSession s, TaskStatus to, String actorId, String reason) {
        if (s.getTaskId() == null) return;
        Task t = taskRepo.findActiveById(s.getTaskId()).orElse(null);
        if (t == null || t.getStatus() == to) return;
        TaskStatus from = t.getStatus();
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "system", actorId, reason));
    }
```

- [ ] **Step 4: 전이 6곳에 미러링 삽입**

각 메서드에서 세션 상태를 바꾼 직후, `return` 앞에 한 줄씩 넣는다:

```java
    // recordQuestion — s.setStatus(InterviewStatus.AWAITING_INPUT); ... 다음
    mirrorTask(s, TaskStatus.INTERVIEW_INPUT, workerId, "인터뷰 질문 도착 → 관리자 답변 대기");

    // submitAnswer — appendTurn + s.setStatus(InterviewStatus.QUEUED); 다음
    //   ※ 멱등 no-op(early return) 뒤에 있어야 한다. 중복 답변이 상태를 흔들면 안 된다.
    mirrorTask(s, TaskStatus.INTERVIEWING, actorId, "관리자 답변 → 인터뷰 재개");

    // recordPlan — s.setStatus(InterviewStatus.PLAN_READY); 다음
    mirrorTask(s, TaskStatus.INTERVIEW_REVIEW, workerId, "플랜 생성 완료 → 확정 대기");

    // fail — s.setStatus(InterviewStatus.FAILED); 다음
    mirrorTask(s, TaskStatus.AWAITING_APPROVAL, actor, "인터뷰 실패 → 승인대기 복귀");

    // expire — s.setStatus(InterviewStatus.EXPIRED); 다음
    mirrorTask(s, TaskStatus.AWAITING_APPROVAL, "system", "인터뷰 만료 → 승인대기 복귀");

    // cancel — s.setStatus(InterviewStatus.CANCELLED); 다음
    mirrorTask(s, TaskStatus.AWAITING_APPROVAL, actorId, "인터뷰 취소 → 승인대기 복귀");
```

클래스 javadoc의 상태 전이 목록에도 미러링을 한 줄로 적어둔다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewTaskMirrorTest' --tests '*InterviewAnswerIdempotencyTest' --tests '*InterviewStaleRecoveryJobTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat: 인터뷰 세션 전이를 task 상태로 미러링 (실패/만료/취소는 승인대기 복귀)"
```

---

### Task 6: 플랜 확정 (register → confirm)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java:319-370` (`register` → `confirm`)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java`
- Delete: `src/test/java/com/hamonsoft/netismaker/service/InterviewRegisterContractTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/InterviewConfirmContractTest.java`

**Interfaces:**
- Consumes: Task 5의 미러링(`INTERVIEW_REVIEW`)
- Produces: `Long InterviewService.confirm(Long sessionId, String adminId, boolean designRequested)` → 갱신된 taskId. HTTP: `POST /api/interviews/{id}/confirm` (ROLE_ADMIN), body `{ "designRequested": boolean }`, 응답 `{ "taskId": n }`.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/hamonsoft/netismaker/service/InterviewConfirmContractTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class InterviewConfirmContractTest {

    @Autowired private TaskService taskService;
    @Autowired private InterviewService interviewService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;

    private Long taskId;
    private Long sid;

    @BeforeEach void seed() {
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        analysisRepo.deleteAll();
        taskRepo.deleteAll();
        Task t = taskService.create(new TaskCreateRequest(1L, "feat/rbac", "RBAC 추가", "역할 기반 권한"), "user1");
        taskId = t.getId();
        taskService.approve(taskId, "admin", null);
        sid = sessionRepo.findAll().get(0).getId();
    }

    private void toPlanReady() {
        interviewService.claim("iw-1");
        interviewService.recordPlan(sid, "iw-1", new WorkerPlanRequest(
                "# 설계 문서", "# 구현 플랜", "[{\"title\":\"A\"},{\"title\":\"B\"}]",
                new BigDecimal("0.42"), 30000L));
    }

    @Test
    void confirm_updates_the_existing_task_and_prefills_analysis() throws Exception {
        toPlanReady();

        Long returned = interviewService.confirm(sid, "admin", false);

        assertThat(returned).isEqualTo(taskId);
        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);   // 구현대기
        assertThat(taskRepo.findAll()).hasSize(1);                   // task를 새로 만들지 않는다

        TaskAnalysis a = analysisRepo.findById(taskId).orElseThrow();
        // LOCKED CONTRACT v2: markdownResult = design_markdown ONLY (합본 아님)
        assertThat(a.getMarkdownResult()).isEqualTo("# 설계 문서");
        ObjectMapper om = new ObjectMapper();
        assertThat(om.readTree(a.getSubtasksJson()))
                .isEqualTo(om.readTree("[{\"title\":\"A\"},{\"title\":\"B\"}]"));
        assertThat(a.getClaudeLog()).isNull();
        assertThat(a.getDurationMs()).isEqualTo(30000L);
        // 확정이 곧 승인 — 별도 승인 게이트가 뒤에 남지 않는다
        assertThat(a.isApproved()).isTrue();
        assertThat(a.getApprovedBy()).isEqualTo("admin");

        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REGISTERED);
    }

    @Test
    void confirm_with_design_requested_goes_to_the_design_queue() {
        toPlanReady();

        interviewService.confirm(sid, "admin", true);

        Task t = taskRepo.findById(taskId).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(t.isDesignRequested()).isTrue();
    }

    @Test
    void confirm_before_plan_ready_is_rejected() {
        assertThatThrownBy(() -> interviewService.confirm(sid, "admin", false))
                .isInstanceOf(TaskException.class);

        assertThat(taskRepo.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.INTERVIEWING);
    }

    @Test
    void confirm_twice_is_rejected() {
        toPlanReady();
        interviewService.confirm(sid, "admin", false);

        assertThatThrownBy(() -> interviewService.confirm(sid, "admin", false))
                .isInstanceOf(TaskException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewConfirmContractTest'`
Expected: 컴파일 실패 — `cannot find symbol: method confirm(...)`

- [ ] **Step 3: register를 confirm으로 교체**

`service/InterviewService.java`에서 `register(...)` 전체를 아래로 교체:

```java
    /**
     * 플랜 확정 — 세션 PLAN_READY + task 플랜승인대기 → task 구현대기|디자인대기, 세션 REGISTERED.
     *
     * TaskAnalysis 프리필 계약(고정):
     *   markdown_result = design_markdown ONLY (합본 X)
     *   subtasks_json   = plan_json
     *   claude_log      = null
     *   duration_ms     = plan.durationMs
     * 확정이 곧 승인이므로 approved=true로 저장한다 (별도 승인 게이트 없음).
     */
    @Transactional
    public Long confirm(Long sessionId, String adminId, boolean designRequested) {
        InterviewSession s = requireSession(sessionId);
        if (s.getStatus() != InterviewStatus.PLAN_READY) {
            throw TaskException.conflict("플랜완료 상태에서만 확정할 수 있습니다 (현재: "
                    + s.getStatus().dbValue() + ")");
        }
        if (s.getTaskId() == null) {
            throw TaskException.conflict("작업에 연결되지 않은 세션입니다");
        }
        Task t = taskRepo.findActiveById(s.getTaskId()).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.INTERVIEW_REVIEW) {
            throw TaskException.conflict("플랜승인대기 상태에서만 확정할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        InterviewPlan plan = planRepo.findById(sessionId)
                .orElseThrow(() -> TaskException.conflict("인터뷰 플랜이 없습니다"));

        TaskAnalysis a = TaskAnalysis.create(t.getId(), plan.getDesignMarkdown(),
                plan.getPlanJson(), null, plan.getDurationMs());
        a.setApproved(true);
        a.setApprovedBy(adminId);
        a.setApprovedAt(OffsetDateTime.now());
        analysisRepo.save(a);

        t.setDesignRequested(designRequested);
        TaskStatus from = t.getStatus();
        TaskStatus to = designRequested ? TaskStatus.DESIGN_PENDING : TaskStatus.APPROVED;
        t.setStatus(to);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "user", adminId,
                "플랜 확정 → " + to.dbValue() + " (interview_session " + sessionId + ")"));

        s.setStatus(InterviewStatus.REGISTERED);
        touch(s);
        return t.getId();
    }
```

클래스 javadoc의 `register ─► ...` 줄을 `confirm ─► PLAN_READY → REGISTERED (기존 task 갱신: analysis 프리필 + 구현/디자인 큐)`로 고친다.

- [ ] **Step 4: 컨트롤러 엔드포인트 교체 + 권한 잠금**

`controller/InterviewController.java`에서 `register` 핸들러를 교체하고 `answer`/`cancel`에 관리자 가드를 붙인다:

```java
    @PostMapping("/{id}/answer")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public InterviewResponse answer(@PathVariable Long id, @RequestBody @Valid AnswerRequest req,
                                    JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        interviewService.submitAnswer(id, adminId, true, req);
        interviewStream.pushStatus(id, InterviewStatus.QUEUED);
        return interviewService.getResponse(id, adminId, true);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public RegisterResponse confirm(@PathVariable Long id,
                                    @RequestBody(required = false) InterviewConfirmRequest body,
                                    JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        Long taskId = interviewService.confirm(id, adminId,
                body != null && Boolean.TRUE.equals(body.designRequested()));
        interviewStream.pushStatus(id, InterviewStatus.REGISTERED);
        interviewStream.finish(id); // terminal → done 이벤트
        return new RegisterResponse(taskId);
    }

    /** confirm 요청 바디 — designRequested 미지정 시 false 취급. */
    public record InterviewConfirmRequest(Boolean designRequested) {}

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public InterviewResponse cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        interviewService.cancel(id, adminId, true);
        interviewStream.pushStatus(id, InterviewStatus.CANCELLED);
        interviewStream.finish(id);
        return interviewService.getResponse(id, adminId, true);
    }
```

`import org.springframework.security.access.prepost.PreAuthorize;` 추가. 기존 `InterviewRegisterRequest` record는 삭제한다.

- [ ] **Step 5: 구 계약 테스트 삭제 + 통합 테스트 경로 수정**

```bash
git rm src/test/java/com/hamonsoft/netismaker/service/InterviewRegisterContractTest.java
```

`src/test/.../controller/InterviewApiIntegrationTest.java`와 `InterviewAnswerIdempotencyTest.java`에서
`/register` → `/confirm`으로 바꾸고, `answer`/`confirm`/`cancel` 호출의 `userJwt("user1")`를
`adminJwt("admin")`으로 바꾼다. (세션을 만드는 부분은 Task 7에서 손본다 — 지금은 컴파일만 통과하면 된다.)

- [ ] **Step 6: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewConfirmContractTest' --tests '*InterviewAnswerIdempotencyTest'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A src/main src/test
git commit -m "feat: 플랜 확정(confirm)이 기존 task를 구현/디자인 큐로 보내도록"
```

---

### Task 7: 사용자 세션 생성 경로 제거

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java` (create/listActive 제거)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (`create`, `listActiveForRequester`, `resolveMcpExtras`, 미사용 의존성 제거)
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java` (`findActiveByRequester` 제거)
- Delete: `dto/CreateInterviewRequest.java`, `dto/InterviewCreatedResponse.java`, `dto/InterviewSummary.java`
- Delete: `src/test/java/com/hamonsoft/netismaker/repository/InterviewActiveQueryTest.java`
- Modify: `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java`, `InterviewSecuritySurfaceTest.java`, `service/InterviewServiceTest.java`
- Delete: `frontend/composables/interviewResume.ts`, `frontend/composables/interviewResume.spec.ts`

**Interfaces:**
- Consumes: Task 4의 `TaskService.approve` (세션 생성의 유일한 출구)
- Produces: `/api/interviews`에는 `GET /{id}`, `GET /{id}/stream`, `POST /{id}/answer`, `POST /{id}/confirm`, `POST /{id}/cancel`만 남는다.

- [ ] **Step 1: 남아 있는 사용처 확인**

Run:
```bash
grep -rn "CreateInterviewRequest\|InterviewCreatedResponse\|InterviewSummary\|listActiveForRequester\|findActiveByRequester\|interviews/active" src frontend --exclude-dir=node_modules --exclude-dir=.nuxt
```
Expected: 아래 스텝에서 지울 파일들만 나온다.

- [ ] **Step 2: 통합 테스트를 새 진입점으로 재작성**

`InterviewApiIntegrationTest`의 세션 생성 헬퍼를 "task 등록 → 관리자 승인"으로 바꾼다:

```java
    @Autowired private com.hamonsoft.netismaker.service.TaskService taskService;

    /** 세션은 이제 승인에서만 태어난다 — 등록 후 승인해서 세션 id를 얻는다. */
    private long startSession(String requester, String title) throws Exception {
        var t = taskService.create(
                new com.hamonsoft.netismaker.dto.TaskCreateRequest(1L, "main", title, "설명"), requester);
        taskService.approve(t.getId(), "admin", null);
        return sessionRepo.findAll().stream()
                .filter(s -> t.getId().equals(s.getTaskId()))
                .findFirst().orElseThrow().getId();
    }
```

- `POST_interviews_creates_QUEUED_session`, 동시 한도 테스트, `/active` 테스트는 삭제한다
  (엔드포인트가 사라졌으므로 검증 대상이 없다).
- 나머지 테스트는 `startSession(...)`으로 세션을 준비하고, 조회/스트림은 `userJwt`, 답변/확정/취소는
  `adminJwt`로 호출한다.
- 요청자 403 케이스를 추가한다:

```java
    @Test
    void requester_cannot_answer_admin_only_interview() throws Exception {
        long sid = startSession("user1", "권한 확인");
        mvc.perform(post("/api/interviews/" + sid + "/answer").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"네\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requester_can_still_read_the_interview() throws Exception {
        long sid = startSession("user1", "권한 확인");
        mvc.perform(get("/api/interviews/" + sid).with(userJwt("user1")))
                .andExpect(status().isOk());
    }
```

`InterviewSecuritySurfaceTest`에서 `POST /api/interviews` 401 케이스를 `POST /api/interviews/1/confirm` 401로 바꾼다.
`InterviewServiceTest`에서 `create(CreateInterviewRequest, ...)`/`listActiveForRequester`를 쓰는 테스트는
`createForTask`/삭제로 정리한다.

- [ ] **Step 3: 테스트 실패 확인 (아직 구 API가 남아 있어 컴파일 실패)**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewApiIntegrationTest'`
Expected: 컴파일 실패 또는 404 — 다음 스텝에서 본체를 정리한다.

- [ ] **Step 4: 컨트롤러/서비스/리포지토리에서 구 경로 제거**

- `InterviewController`: `create` 핸들러, `listActive` 핸들러, 관련 import(`CreateInterviewRequest`, `InterviewCreatedResponse`, `InterviewSummary`, `URI`, `List`) 삭제.
- `InterviewService`: `create(CreateInterviewRequest, String)`, `listActiveForRequester`, `resolveMcpExtras`, `userConcurrentLimit` 필드와 `@Value("${app.interview.user-concurrent-limit:3}")` 삭제. 그 결과 미사용이 되는 생성자 의존성 `McpCatalogService`, `RepoCatalogService`를 제거한다.
- `InterviewSessionRepository`: `findActiveByRequester` 삭제. `countActiveByRequester`도 사용처가 사라지면 함께 삭제한다.

- [ ] **Step 5: DTO/테스트/프론트 컴포저블 삭제**

```bash
git rm src/main/java/com/hamonsoft/netismaker/dto/CreateInterviewRequest.java \
       src/main/java/com/hamonsoft/netismaker/dto/InterviewCreatedResponse.java \
       src/main/java/com/hamonsoft/netismaker/dto/InterviewSummary.java \
       src/test/java/com/hamonsoft/netismaker/repository/InterviewActiveQueryTest.java \
       frontend/composables/interviewResume.ts \
       frontend/composables/interviewResume.spec.ts
```

- [ ] **Step 6: 백엔드 전체 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: PASS. 특히 `InterviewWorkerApiIntegrationTest`가 **무수정으로** 통과해야 한다 — 워커 계약 불변의 증거다.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "refactor: 사용자 인터뷰 생성/재개 디스커버리 API 제거 (세션은 승인에서만 생성)"
```

---

### Task 8: 세션 정리 가드 + 작업 상세에 세션 id 노출

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (`closeOpenSessionsForTask`, `latestSessionIdForTask`)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java:168-185` (`softDelete`)
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` (`get`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRegistrationTest.java` (케이스 추가)

**Interfaces:**
- Consumes: Task 5의 미러링, Task 7 이후의 세션 API 표면
- Produces:
  - `Optional<Long> InterviewService.latestSessionIdForTask(Long taskId)`
  - `void InterviewService.closeOpenSessionsForTask(Long taskId, String actorId)`
  - `TaskResponse.interviewSessionId` (JSON 필드) — Task 11 프론트가 읽는다.

- [ ] **Step 1: 실패하는 테스트 추가**

`TaskServiceRegistrationTest`에 추가:

```java
    @Autowired private InterviewService interviewService;

    @Test
    void deleting_an_interviewing_task_closes_its_open_session() {
        Task t = taskService.create(req("작업 A"), "user1");
        taskService.approve(t.getId(), "admin", null);
        Long sid = sessionRepo.findAll().get(0).getId();

        taskService.softDelete(t.getId(), "admin", true);

        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus())
                .isEqualTo(com.hamonsoft.netismaker.entity.InterviewStatus.CANCELLED);
        assertThat(taskRepo.findActiveById(t.getId())).isEmpty();
    }

    @Test
    void latest_session_id_is_the_most_recent_one() {
        Task t = taskService.create(req("작업 A"), "user1");
        taskService.approve(t.getId(), "admin", null);
        Long first = sessionRepo.findAll().get(0).getId();
        interviewService.cancel(first, "admin", true);          // → 승인대기 복귀
        taskService.approve(t.getId(), "admin", null);           // 재승인 → 두 번째 세션

        Long latest = interviewService.latestSessionIdForTask(t.getId()).orElseThrow();

        assertThat(latest).isGreaterThan(first);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskServiceRegistrationTest'`
Expected: 컴파일 실패 — `latestSessionIdForTask` 없음

- [ ] **Step 3: 리포지토리 조회 2개 추가**

`repository/InterviewSessionRepository.java`:

```java
    /** task의 최신 세션 1건 (재승인으로 세션이 여러 개일 수 있다). */
    Optional<InterviewSession> findTopByTaskIdOrderByCreatedAtDesc(Long taskId);

    /** task에 붙은 비종료 세션들 — 삭제 시 정리 대상. */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.taskId = :taskId
          AND s.status IN (com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED,
                           com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING,
                           com.hamonsoft.netismaker.entity.InterviewStatus.AWAITING_INPUT,
                           com.hamonsoft.netismaker.entity.InterviewStatus.PLAN_READY)
    """)
    List<InterviewSession> findOpenByTaskId(@Param("taskId") Long taskId);
```

- [ ] **Step 4: InterviewService에 조회/정리 메서드 추가**

```java
    /** task 상세가 열 세션 — 최신 1건. */
    @Transactional(readOnly = true)
    public Optional<Long> latestSessionIdForTask(Long taskId) {
        return sessionRepo.findTopByTaskIdOrderByCreatedAtDesc(taskId).map(InterviewSession::getId);
    }

    /**
     * task 삭제 시 열린 세션을 닫는다. 워커가 이미 잡고 있어도 다음 보고에서 상태 가드에 걸려
     * 조용히 실패하므로(고아 컨테이너 같은 부작용 없음) 차단 대신 정리로 처리한다.
     * task 상태는 이미 삭제 대상이므로 미러링하지 않는다.
     */
    @Transactional
    public void closeOpenSessionsForTask(Long taskId, String actorId) {
        for (InterviewSession s : sessionRepo.findOpenByTaskId(taskId)) {
            appendTurn(s.getId(), "system", "note", "작업 삭제로 인터뷰 취소 (" + actorId + ")");
            s.setStatus(InterviewStatus.CANCELLED);
            s.setWorkerId(null);
            s.setClaimedAt(null);
            touch(s);
        }
    }
```

- [ ] **Step 5: softDelete에서 세션 정리 호출**

`service/TaskService.java`의 `softDelete`에서 배포 가드 `switch` **뒤**, `deletedAt` 설정 앞에 추가:

```java
        interviewService.closeOpenSessionsForTask(t.getId(), actorId);
```

- [ ] **Step 6: TaskResponse에 interviewSessionId 추가**

`dto/TaskResponse.java`:
- 레코드 컴포넌트 목록 맨 끝(`DeploymentView deployment` 다음)에 `Long interviewSessionId` 추가
- 기존 `of(Task, TaskAnalysis)` / `of(Task, TaskAnalysis, TaskDesign)`은 `interviewSessionId = null`로 위임하고, 3-arg 뒤에 세션 id를 받는 4-arg 오버로드를 만든다:

```java
    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d) {
        return of(t, a, d, null);
    }

    public static TaskResponse of(Task t, TaskAnalysis a, TaskDesign d, Long interviewSessionId) {
        // ...기존 본문 그대로...
        return new TaskResponse(
                // ...기존 인자 그대로...
                dv,
                interviewSessionId
        );
    }
```

- [ ] **Step 7: 상세 조회에서 세션 id 채우기**

`controller/TaskController.java`의 `get`:

```java
    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        Task t = taskService.getForView(id, userId, isAdmin);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null),
                taskService.getDesign(id).orElse(null),
                interviewService.latestSessionIdForTask(id).orElse(null));
    }
```

`InterviewService`를 컨트롤러 생성자에 주입한다. 목록(`list`)은 그대로 둔다 — 목록은 상태만으로 충분하다.

- [ ] **Step 8: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: PASS (전체)

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "feat: 삭제 시 열린 인터뷰 세션 정리 + 작업 상세에 최신 세션 id 노출"
```

---

### Task 9: 프론트 — 등록 폼 축소

**Files:**
- Modify: `frontend/pages/tasks/index.vue`
- Test: `frontend/test/tasks-form-repo-select.spec.ts` (기존 스펙 갱신)

**Interfaces:**
- Consumes: `POST /api/tasks` 4필드 계약 (Task 3)
- Produces: 등록 다이얼로그는 폼 1단계만 남는다. `McpPicker`/모델 셀렉트는 Task 10에서 승인 다이얼로그로 이동한다.

- [ ] **Step 1: 실패하는 테스트 갱신**

`frontend/test/tasks-form-repo-select.spec.ts` 맨 아래에 describe 블록을 추가한다. 파일 상단의
`PageWrapper`와 `useApiMock`을 그대로 재사용한다. `<script setup>` 바인딩은 dev 빌드에서
`wrapper.vm`으로 접근 가능하므로 draft를 직접 채운다(QSelect를 DOM으로 조작하지 않아 안정적):

```ts
describe('tasks form — 등록 payload', () => {
  it('모델/effort/MCP 없이 4필드만 보낸다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()

    const vm = w.findComponent(TasksIndex).vm as any
    vm.draft.repoCatalogId = 1
    vm.draft.githubBranch = 'main'
    vm.draft.title = 'RBAC 추가'
    vm.draft.description = '역할 기반 권한'

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    await vm.submit()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks', {
      method: 'POST',
      body: {
        repoCatalogId: 1,
        githubBranch: 'main',
        title: 'RBAC 추가',
        description: '역할 기반 권한',
      },
    })

    w.unmount()
  })

  it('등록 다이얼로그에 모델·MCP 선택 UI가 없다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()

    const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
    await addBtn!.trigger('click')
    await flushPromises()

    const dialog = document.querySelector('.q-dialog')!
    expect(dialog.textContent).not.toContain('Claude 모델')
    expect(dialog.textContent).not.toContain('MCP')

    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run test/tasks-form-repo-select.spec.ts`
Expected: FAIL — 현재 폼에는 '작업 등록' 버튼이 없고 payload에 model/effort/mcpCatalogIds가 들어간다

- [ ] **Step 3: 스크립트에서 인터뷰 흐름 제거**

`frontend/pages/tasks/index.vue`의 `<script setup>`에서 삭제:
- `import { decideResume, type InterviewSummary } from '~/composables/interviewResume'`
- `import { MODEL_OPTIONS, DEFAULT_MODEL, DEFAULT_EFFORT, effortsForModel, coerceEffort } from '~/composables/modelEffort'`
- `dialogPhase`, `interviewSessionId`, `starting`, `resumeCandidates`, `showResumePicker`
- `startInterview()`, `openInterview()`, `discoverActiveInterviews()`, `onMounted(discoverActiveInterviews)`, `onRegistered()`
- MCP 관련: `availableMcps`, `aliveWorkerCount`, `mcpsLoading`, `loadAvailableMcps()`, `catalog`, `catalogLoading`, `loadCatalog()`, `selectedCatalogIds`, `toggleCatalog()`, `statusDotColor()`, `selectedHasDown`, `CatalogEntry` 인터페이스

`draft`에서 `model`/`effort`를 빼고, `openCreate()`에서 관련 초기화와 `loadAvailableMcps()`/`loadCatalog()` 호출을 지운다:

```ts
const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  title: '',
  description: '',
})

function openCreate() {
  draft.repoCatalogId = null
  draft.githubBranch = ''
  draft.title = ''
  draft.description = ''
  resetBranchState()
  showCreate.value = true
  loadRepoCatalog()
}
```

`submit()`의 body를 4필드로 줄인다:

```ts
    await useApi('/api/tasks', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        title: draft.title,
        description: draft.description,
      },
    })
```

- [ ] **Step 4: 템플릿에서 2단계 구조 제거**

- `<q-dialog v-model="showCreate">` 안의 모델/effort 셀렉트 블록, MCP `q-expansion-item` 블록 전체 삭제
- `dialogPhase` 조건부 렌더링 제거 → 카드 액션은 항상 폼 버튼:

```html
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="closeDialog" />
          <q-btn
            unelevated
            color="primary"
            icon="add_task"
            label="작업 등록"
            :loading="submitting"
            :disable="!canSubmit"
            @click="submit"
          />
        </q-card-actions>
```

- `<InterviewPanel ... />`를 감싼 `q-card-section` 삭제
- 진행 중 인터뷰 선택 다이얼로그(`showResumePicker`) 전체 삭제
- 다이얼로그 타이틀에서 `dialogPhase === 'interview' ? ... : ...` 삼항을 제거하고 고정 문구로

`closeDialog()`에 남아 있는 `interviewSessionId`/`dialogPhase` 초기화도 지운다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd frontend && npx vitest run test/tasks-form-repo-select.spec.ts`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add frontend/pages/tasks/index.vue frontend/test/tasks-form-repo-select.spec.ts
git commit -m "feat(front): 등록 다이얼로그를 폼 1단계로 축소"
```

---

### Task 10: 프론트 — McpPicker 추출 + ApproveDialog

**Files:**
- Create: `frontend/components/McpPicker.vue`
- Create: `frontend/components/ApproveDialog.vue`
- Test: `frontend/components/ApproveDialog.spec.ts`

**Interfaces:**
- Consumes: `POST /api/tasks/{id}/approve` body `{ mcpCatalogIds, model, effort }` (Task 4)
- Produces:
  - `<McpPicker v-model="selectedIds" />` — 선택된 카탈로그 id 배열
  - `<ApproveDialog v-model="show" :task-id="n" @approved="fn" />` — 승인 성공 시 `approved` emit

- [ ] **Step 1: 실패하는 테스트 작성**

Create `frontend/components/ApproveDialog.spec.ts` — `useApi`는 `test/setup.ts`가 전역으로
`useApiMock`에 연결해 두었고, Quasar 컴포넌트/Notify도 거기서 등록된다:

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ApproveDialog from './ApproveDialog.vue'
import { useApiMock } from '../test/mocks/nuxt'

describe('ApproveDialog', () => {
  it('승인 시 모델·effort·MCP 선택을 함께 보낸다', async () => {
    useApiMock.mockResolvedValue([]) // McpPicker의 GET /api/mcp-catalog
    const w = mount(ApproveDialog, {
      props: { modelValue: true, taskId: 7 },
      attachTo: document.body,
    })
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    await (w.vm as any).approve()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/7/approve', {
      method: 'POST',
      body: { model: 'claude-opus-4-8', effort: 'high', mcpCatalogIds: [] },
    })
    expect(w.emitted('approved')).toBeTruthy()

    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('MCP를 고르면 선택 id가 payload에 실린다', async () => {
    useApiMock.mockResolvedValue([
      {
        id: 3,
        name: 'ctx7',
        displayName: 'Context7',
        url: 'https://ctx7',
        transport: 'http',
        description: null,
        lastCheckStatus: 'HEALTHY',
      },
    ])
    const w = mount(ApproveDialog, {
      props: { modelValue: true, taskId: 7 },
      attachTo: document.body,
    })
    await flushPromises()

    const picker = w.findComponent({ name: 'McpPicker' })
    ;(picker.vm as any).toggle(3)
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    await (w.vm as any).approve()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/7/approve', {
      method: 'POST',
      body: { model: 'claude-opus-4-8', effort: 'high', mcpCatalogIds: [3] },
    })

    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })
})
```

`findComponent({ name: 'McpPicker' })`가 동작하려면 `McpPicker.vue`에 이름이 필요하다 —
`<script setup>` 파일은 파일명에서 이름이 유추되므로 별도 선언 없이 동작한다.

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run components/ApproveDialog.spec.ts`
Expected: FAIL — 파일 없음

- [ ] **Step 3: McpPicker 작성**

Create `frontend/components/McpPicker.vue` — `index.vue`에서 걷어낸 카탈로그 UI를 그대로 옮긴다:

```vue
<script setup lang="ts">
interface CatalogEntry {
  id: number
  name: string
  displayName: string
  url: string
  transport: string
  description: string | null
  lastCheckStatus: string | null
}

const model = defineModel<number[]>({ default: () => [] })

const catalog = ref<CatalogEntry[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    catalog.value = await useApi<CatalogEntry[]>('/api/mcp-catalog')
  } catch {
    catalog.value = []
  } finally {
    loading.value = false
  }
}

function toggle(id: number) {
  const next = [...model.value]
  const idx = next.indexOf(id)
  if (idx >= 0) next.splice(idx, 1)
  else next.push(id)
  model.value = next
}

function dotColor(s: string | null): string {
  if (!s) return 'grey-5'
  return { HEALTHY: 'positive', DEGRADED: 'warning', DOWN: 'negative' }[s] ?? 'grey-5'
}

const selectedHasDown = computed(() =>
  catalog.value.some((c) => model.value.includes(c.id) && c.lastCheckStatus === 'DOWN'),
)

onMounted(load)
defineExpose({ toggle })   // 테스트에서 칩 클릭 대신 직접 호출 (기본 접힘 상태라 DOM에 없음)
</script>

<template>
  <q-expansion-item
    icon="extension"
    label="이 인터뷰에 추가할 MCP 도구"
    :caption="
      catalog.length === 0
        ? '관리자 카탈로그 비어있음'
        : `${model.length}개 선택 · 활성 ${catalog.length}개 중`
    "
    header-class="text-grey-9 bg-grey-2"
    dense
  >
    <q-banner v-if="catalog.length === 0" class="bg-grey-1 text-grey-8 q-mt-sm" dense>
      <template #avatar><q-icon name="info" /></template>
      관리자가 등록한 SSE MCP 카탈로그가 없습니다.
    </q-banner>
    <div v-else class="q-pa-sm">
      <q-chip
        v-for="entry in catalog"
        :key="entry.id"
        clickable
        :color="model.includes(entry.id) ? 'indigo-6' : 'grey-3'"
        :text-color="model.includes(entry.id) ? 'white' : 'grey-9'"
        :icon="model.includes(entry.id) ? 'check' : 'add'"
        @click="toggle(entry.id)"
      >
        <q-badge
          rounded
          :color="dotColor(entry.lastCheckStatus)"
          class="q-mr-xs"
          style="min-height: 8px; min-width: 8px; padding: 0"
        />
        {{ entry.displayName }}
        <q-tooltip>
          <div><strong>{{ entry.name }}</strong> ({{ entry.transport }})</div>
          <div style="max-width: 360px; word-break: break-all">{{ entry.url }}</div>
          <div class="q-mt-xs">헬스: <strong>{{ entry.lastCheckStatus ?? 'UNKNOWN' }}</strong></div>
        </q-tooltip>
      </q-chip>
      <div
        v-if="selectedHasDown"
        class="text-caption text-negative q-mt-sm row items-center q-gutter-xs"
      >
        <q-icon name="warning" size="14px" />
        <span>DOWN 상태 MCP가 포함됨 — 연결 실패해도 인터뷰는 진행되지만 해당 도구는 사용 안 됨.</span>
      </div>
    </div>
  </q-expansion-item>
</template>
```

- [ ] **Step 4: ApproveDialog 작성**

Create `frontend/components/ApproveDialog.vue`:

```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'
import {
  MODEL_OPTIONS,
  DEFAULT_MODEL,
  DEFAULT_EFFORT,
  effortsForModel,
  coerceEffort,
} from '~/composables/modelEffort'
import McpPicker from '~/components/McpPicker.vue'

const props = defineProps<{ taskId: number }>()
const show = defineModel<boolean>({ required: true })
const emit = defineEmits<{ (e: 'approved'): void }>()

const $q = useQuasar()
const model = ref(DEFAULT_MODEL)
const effort = ref(DEFAULT_EFFORT)
const mcpCatalogIds = ref<number[]>([])
const approving = ref(false)

const effortOptions = computed(() => effortsForModel(model.value))
watch(model, (m) => {
  effort.value = coerceEffort(m, effort.value)
})

async function approve() {
  approving.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/approve`, {
      method: 'POST',
      body: {
        model: model.value,
        effort: effort.value,
        mcpCatalogIds: mcpCatalogIds.value,
      },
    })
    $q.notify({ type: 'positive', message: '승인 완료 — 인터뷰가 시작됩니다' })
    show.value = false
    emit('approved')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '승인 실패' })
  } finally {
    approving.value = false
  }
}

defineExpose({ approve })
</script>

<template>
  <q-dialog v-model="show">
    <q-card style="min-width: 480px">
      <q-card-section class="text-h6">승인 — 인터뷰 시작</q-card-section>
      <q-card-section class="q-pt-none text-grey-8">
        승인하면 관리자와의 대화형 분석이 시작됩니다. 여기서 고른 모델·도구로 인터뷰가 진행됩니다.
      </q-card-section>
      <q-card-section class="q-gutter-md">
        <div class="row q-col-gutter-md">
          <q-select
            v-model="model"
            :options="MODEL_OPTIONS"
            emit-value
            map-options
            outlined
            dense
            label="Claude 모델"
            class="col"
          />
          <q-select
            v-model="effort"
            :options="effortOptions"
            emit-value
            map-options
            outlined
            dense
            label="Effort"
            class="col"
            :hint="model === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : ''"
          />
        </div>
        <McpPicker v-model="mcpCatalogIds" />
      </q-card-section>
      <q-card-actions align="right">
        <q-btn v-close-popup flat label="취소" />
        <q-btn
          data-test="approve-submit"
          unelevated
          color="primary"
          icon="forum"
          label="승인하고 인터뷰 시작"
          :loading="approving"
          @click="approve"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd frontend && npx vitest run components/ApproveDialog.spec.ts`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add frontend/components/McpPicker.vue frontend/components/ApproveDialog.vue \
        frontend/components/ApproveDialog.spec.ts
git commit -m "feat(front): 승인 다이얼로그 + MCP 선택 컴포넌트 추출"
```

---

### Task 11: 프론트 — 작업 상세에 인터뷰 패널

**Files:**
- Modify: `frontend/components/InterviewPanel.vue`
- Modify: `frontend/pages/tasks/[id].vue`
- Modify: `frontend/components/InterviewPanel.spec.ts`

**Interfaces:**
- Consumes: `TaskResponse.interviewSessionId` (Task 8), `POST /api/interviews/{id}/confirm` (Task 6), `<ApproveDialog>` (Task 10)
- Produces: `<InterviewPanel :session-id="n" :readonly="bool" @confirmed="fn" />`

- [ ] **Step 1: 실패하는 테스트 추가**

`frontend/components/InterviewPanel.spec.ts` 맨 아래에 추가한다. 기존 `mountPanel(sessionId, snapshot)`
헬퍼는 props에 `sessionId`만 넘기므로, readonly 케이스는 직접 mount한다:

```ts
describe('InterviewPanel — 확정 / 읽기 전용', () => {
  it('플랜 완료 후 확정하면 /confirm을 호출한다', async () => {
    const w = await mountPanel(9, {
      statusName: 'PLAN_READY',
      turns: [],
      plan: { designMarkdown: '# 설계', planMarkdown: '# 플랜', planJson: '[]' },
    })

    useApiMock.mockResolvedValueOnce({ taskId: 42 })
    await w.find('[data-test="confirm"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: false },
    })
    expect(w.emitted('confirmed')).toBeTruthy()
    w.unmount()
  })

  it('readonly면 답변 입력·확정 버튼을 렌더하지 않는다', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce({
      statusName: 'AWAITING_INPUT',
      turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }],
      plan: null,
    })
    const w = mount(InterviewPanel, { props: { sessionId: 9, readonly: true } })
    await flushPromises()

    expect(w.text()).toContain('범위는?')          // 대화는 보인다
    expect(w.find('textarea').exists()).toBe(false) // 입력창은 없다
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false)
    expect(w.find('[data-test="confirm"]').exists()).toBe(false)
    w.unmount()
  })
})
```

파일 상단 import에 `authStub`이 없으면 `import { authStub, useApiMock } from '../test/mocks/nuxt'`로
보강한다(이미 있으면 그대로). 스냅샷 필드명(`statusName`/`turns`/`plan`)은 기존 헬퍼가 쓰는 것과 같다.

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts`
Expected: FAIL

- [ ] **Step 3: InterviewPanel 수정**

- props/emit 교체:

```ts
const props = defineProps<{
  sessionId: number
  model?: string
  effort?: string
  readonly?: boolean
}>()
const emit = defineEmits<{ (e: 'confirmed', taskId: number): void; (e: 'close'): void }>()
```

- `register()` → `confirm()`:

```ts
const confirming = ref(false)

async function confirm() {
  if (status.value !== 'PLAN_READY' || confirming.value) return
  confirming.value = true
  try {
    const res = await useApi<{ taskId: number }>(`/api/interviews/${props.sessionId}/confirm`, {
      method: 'POST',
      body: { designRequested: designRequested.value },
    })
    $q.notify({ type: 'positive', message: '확정 완료 — 구현 큐에 진입했습니다' })
    emit('confirmed', res.taskId)
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '확정 실패' })
  } finally {
    confirming.value = false
  }
}
```

- 템플릿의 버튼:

```html
            <q-btn
              data-test="confirm"
              unelevated
              color="positive"
              icon="task_alt"
              label="구현 진행"
              :loading="confirming"
              :disable="status !== 'PLAN_READY' || confirming"
              @click="confirm"
            />
```

- 읽기 전용 처리: 답변 입력 영역, 전송 버튼, 확정 버튼, 디자인 토글, 취소 버튼을 감싼 노드에 `v-if="!readonly"`를 건다. 대화 로그/설계 섹션/플랜 표시는 그대로 둔다.

- [ ] **Step 4: 작업 상세에 배선**

`frontend/pages/tasks/[id].vue`:

- 스크립트 상단 import 추가:

```ts
import ApproveDialog from '~/components/ApproveDialog.vue'
import InterviewPanel from '~/components/InterviewPanel.vue'
```

- `TaskResponse` 인터페이스에 `interviewSessionId: number | null` 추가
- `approve()` 함수를 다이얼로그 오픈으로 교체:

```ts
const showApprove = ref(false)

function openApprove() {
  showApprove.value = true
}

function onApproved() {
  refresh()
}

function onInterviewConfirmed() {
  refresh()
}

const isInterviewPhase = computed(() =>
  ['INTERVIEWING', 'INTERVIEW_INPUT', 'INTERVIEW_REVIEW'].includes(task.value?.status ?? ''),
)
```

- 템플릿에 카드 하나 추가(분석 결과 카드 위):

```html
      <q-card v-if="task.status === 'AWAITING_APPROVAL'" flat bordered class="q-mb-md">
        <q-card-section class="row items-center">
          <div class="text-h6">승인 대기</div>
          <q-space />
          <q-btn
            v-if="auth.isAdmin"
            data-test="approve"
            unelevated
            color="primary"
            icon="forum"
            label="승인 — 인터뷰 시작"
            @click="openApprove"
          />
        </q-card-section>
        <q-separator />
        <q-card-section class="text-grey-8">
          관리자가 승인하면 대화형 분석(인터뷰)이 시작됩니다. 승인 전에는 워커 자원을 쓰지 않습니다.
        </q-card-section>
      </q-card>

      <q-card v-if="isInterviewPhase && task.interviewSessionId" flat bordered class="q-mb-md">
        <q-card-section class="text-h6">대화형 분석</q-card-section>
        <q-separator />
        <q-card-section class="q-pa-none">
          <InterviewPanel
            :session-id="task.interviewSessionId"
            :readonly="!auth.isAdmin"
            @confirmed="onInterviewConfirmed"
          />
        </q-card-section>
      </q-card>

      <ApproveDialog v-model="showApprove" :task-id="task.id" @approved="onApproved" />
```

- 기존 분석 결과 카드의 승인 버튼(레거시 `분석완료` 상태용)은 그대로 둔다 — 레거시 경로가 살아 있다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts \
        "frontend/pages/tasks/[id].vue"
git commit -m "feat(front): 작업 상세에서 승인·인터뷰·확정을 처리"
```

---

### Task 12: 프론트 상태 표기 + 문서 + 최종 게이트

**Files:**
- Modify: `frontend/pages/tasks/index.vue` (상태 필터), `frontend/pages/tasks/[id].vue` (`statusClass`), `frontend/components/QueueStatsBar.vue`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-07-27-interview-at-approval-design.md` (확정=승인 처리 명시)

**Interfaces:**
- Consumes: `QueueStats.pendingApproval/interviewing/planReview` (Task 2), 새 TaskStatus 4개 (Task 1)
- Produces: 없음 (마무리)

- [ ] **Step 1: 상태 필터 옵션 추가**

`frontend/pages/tasks/index.vue`의 `q-select :options` 배열 맨 앞(전체 다음)에 추가:

```ts
          { label: '승인대기', value: 'AWAITING_APPROVAL' },
          { label: '인터뷰중', value: 'INTERVIEWING' },
          { label: '입력대기', value: 'INTERVIEW_INPUT' },
          { label: '플랜승인대기', value: 'INTERVIEW_REVIEW' },
```

- [ ] **Step 2: 상태 칩 클래스 매핑 추가**

`frontend/pages/tasks/[id].vue`의 `statusClass` 맵에 추가(기존 클래스 재사용 — 새 CSS 없음):

```ts
      AWAITING_APPROVAL: 'status-chip status-pending',
      INTERVIEWING: 'status-chip status-in-progress',
      INTERVIEW_INPUT: 'status-chip status-pending',
      INTERVIEW_REVIEW: 'status-chip status-completed',
```

목록(`index.vue`)에도 동일한 상태 칩 매핑이 있으면 같은 4줄을 추가한다.

- [ ] **Step 3: 큐 통계 타일 추가**

`frontend/components/QueueStatsBar.vue`는 폴링 결과를 `data`로 바인딩한다. `작업대기` 타일 **앞**에
3개를 추가한다(기존 타일 마크업 순서 그대로 — 라벨 먼저, 값 나중, 사이에 `q-separator`):

```html
      <div class="stat">
        <div class="stat-label">승인대기</div>
        <div class="stat-value text-grey-8">{{ data.pendingApproval }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">인터뷰중</div>
        <div class="stat-value text-teal-9">{{ data.interviewing }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">플랜승인대기</div>
        <div class="stat-value text-indigo-9">{{ data.planReview }}</div>
      </div>
      <q-separator vertical />
```

`QueueStats` 인터페이스에도 `pendingApproval: number`, `interviewing: number`, `planReview: number`를
`undeploying` 다음에 추가한다.

- [ ] **Step 4: CLAUDE.md 상태머신 갱신**

`CLAUDE.md`의 "## 작업 상태머신 (V1.1 — 구현 파이프라인)" 섹션 제목을 `## 작업 상태머신`으로 바꾸고 다이어그램 앞에 인터뷰 구간을 넣는다:

```
[승인대기] ──(관리자 승인 = 인터뷰 시작)──→ [인터뷰중] ⇄ [입력대기] ──→ [플랜승인대기]
                                                                          │ (구현 진행)
                                                                          ↓
                                                        [구현대기] 또는 [디자인대기]
[승인대기] ← (인터뷰 실패/만료/취소)

레거시 자동분석: [작업대기] → [분석중] → [분석완료] ──(승인)──→ [구현대기]/[디자인대기]  (API 전용)
```

그리고 아래 항목을 갱신한다:
- **등록**: 사용자는 레포/브랜치/제목/설명만 입력. 모델·effort·MCP는 관리자가 승인 시 결정.
- **승인**: `/tasks/{id}/approve`가 상태에 따라 분기 — `승인대기`면 인터뷰 세션 생성, `분석완료`면 기존 구현 큐 진입.
- **확정**: `/interviews/{sid}/confirm`이 `TaskAnalysis`를 프리필하고(approved=true) 구현/디자인 큐로 보냄.
- "자주 보는 코드" 표에 `승인 다이얼로그 | frontend/components/ApproveDialog.vue` 행 추가.

- [ ] **Step 5: 스펙에 확정=승인 처리 반영**

`docs/superpowers/specs/2026-07-27-interview-at-approval-design.md`의 confirm 계약 블록에 한 줄 추가:

```
+ TaskAnalysis.approved=true / approvedBy=관리자 (확정이 곧 승인 — 뒤에 별도 게이트 없음)
```

- [ ] **Step 6: 전체 게이트 실행**

Run:
```bash
RUN_TESTCONTAINERS=true ./gradlew test
cd frontend && npx vitest run && cd ..
```
Expected: 백엔드 전체 PASS, 프론트 전체 PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "feat(front): 인터뷰 구간 상태 표기 + 문서 갱신"
```

---

## 검증 체크리스트 (수동)

구현 완료 후 실제 스택에서 확인한다 (`./scripts/start-public.sh` → `./scripts/start-all.sh`):

- [ ] 일반 사용자로 작업 등록 → 목록에 `승인대기`로 보이고 워커 로그에 아무 활동이 없다
- [ ] 관리자로 작업 상세 → "승인 — 인터뷰 시작" → 모델/MCP 선택 → 승인
- [ ] 상태가 `인터뷰중` → 질문 도착 시 `입력대기`로 바뀌고 목록에서도 보인다
- [ ] 답변 후 다시 `인터뷰중`, 플랜 생성 후 `플랜승인대기`
- [ ] 요청자 계정으로 같은 상세를 열면 대화는 보이고 입력창은 없다
- [ ] "구현 진행" → `구현대기` → 워커가 claim → PR 생성까지 기존과 동일하게 흐른다
- [ ] 디자인 토글을 켜고 확정하면 `디자인대기`로 간다
