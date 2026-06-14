# Conversational Analysis — Backend Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development — every task below is a strict red→green→commit loop. Write the failing test first, run it and observe the exact failure, write the minimal code shown, run it green, then commit. Do not batch steps. Do not skip the "see it fail" run.

> **OWNERSHIP — this plan (Phase 1, domain) is the SOLE creator of the shared domain.** Plan 02 (Phase 2, API/SSE) and plan 03 (Phase 3, SDK worker) **REUSE — never re-Create — every artifact below.** The artifacts plan 02 MUST reuse (import, not redefine):
> - **Entities:** `InterviewSession`, `InterviewTurn`, `InterviewPlan`
> - **Enum + converter:** `InterviewStatus`, `InterviewStatusConverter`
> - **Migration:** `V11__interview.sql` (single migration — **no V12**; `work_dir` + `reply_to_seq` ship in V11 from the start)
> - **Repositories:** `InterviewSessionRepository`, `InterviewTurnRepository`, `InterviewPlanRepository`
> - **Service:** `InterviewService` (all transition methods: `create`/`claim`/`recordQuestion`/`submitAnswer`/`recordPlan`/`cancel`/`expire`/`fail`/`register`)
> - **DTOs (canonical — defined here):** `CreateInterviewRequest`, `AnswerRequest`, `WorkerQuestionRequest`, `WorkerPlanRequest`, `InterviewClaimResponse`
>
> Plan 02 only Creates: controllers, `InterviewStreamService` (SSE), the scheduled stale-recovery/idle-TTL job, and the register-from-controller wiring. Plan 03 only Creates: the SDK interview service (clone/prepare repo at `work_dir`, run/resume the SDK session). If you find yourself authoring any artifact in the bullet list above inside plan 02 or 03, STOP — it already exists here.

**Goal:** Add the persistence + service domain for the conversational-analysis interview feature to `netisMaker`: a single `V11__interview.sql` Flyway migration (3 tables in the `com` schema — **including `interview_session.work_dir` and `interview_turn.reply_to_seq` from the start; there is no V12**), three JPA entities (`InterviewSession`, `InterviewTurn`, `InterviewPlan`) with a Korean-dbValue status enum + AttributeConverter mirroring `TaskStatus`/`TaskStatusConverter` (converter is `autoApply = false` with an explicit `@Convert` on the entity field — see Task 1), repositories (including a `FOR UPDATE SKIP LOCKED` claim query ordered by `last_activity_at ASC` and an in-flight/stale finder mirroring `TaskRepository`), and an `InterviewService` that owns every state transition (create → claim → recordQuestion → submitAnswer → recordPlan → cancel → expire → fail) plus the `register`-to-Task promotion. Unit tests cover every transition guard and claim concurrency. **No controllers, no SSE, no SDK service, no scheduler** — those are Phase 2 (plan 02) and Phase 3 (plan 03), which REUSE everything created here.

**Architecture:** The interview is an independent `interview_session` aggregate (the Task enum is **UNCHANGED** — interview state lives entirely in `interview_session.status`). `InterviewService` is the single entry point for all status transitions, mirroring `TaskService`: every mutation is `@Transactional`, guarded against the current status, and logged implicitly through entity timestamps (interview has no separate history table; the append-only `interview_turn` log is the audit trail). Worker claim uses pessimistic-write `FOR UPDATE SKIP LOCKED` exactly like `TaskRepository.findClaimableForUpdateSkipLocked`, ordered by `last_activity_at ASC` (oldest-waiting first, so a just-answered re-queued session is picked up promptly). On the **first** claim the service deterministically assigns + persists `interview_session.work_dir` (the repo checkout path `~/netis-maker/interviews/{owner}/{repo}/session-{id}`); every subsequent resume claim returns the stored value unchanged so the SDK worker (plan 03) reuses the identical cwd Claude Code's session store is keyed on. Register promotes a `PLAN_READY` session into a `Task(COMPLETED)` + `TaskAnalysis` prefill via the Task repositories, then sets `interview_session.task_id` and transitions to `REGISTERED`.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Gradle, Spring Data JPA (Hibernate), Flyway (PostgreSQL `com` schema, `baseline-version: 0`), Lombok, JUnit 5 + Mockito + AssertJ. PostgreSQL is the only datastore touched here. The `api` Spring profile hosts `InterviewService` (same as `TaskService`/`WorkerService`).

---

## File Structure

| File | Create/Modify | Responsibility |
|---|---|---|
| `src/main/resources/db/migration/V11__interview.sql` | Create | 3 tables `com.interview_session` (incl. `work_dir`), `com.interview_turn` (incl. `reply_to_seq`), `com.interview_plan` + indexes, mirroring V1 conventions (VARCHAR(30) status, no CHECK, TIMESTAMPTZ DEFAULT now()). **Single migration — no V12.** |
| `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatus.java` | Create | Status enum with Korean `dbValue`, mirroring `TaskStatus` (8 states) |
| `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatusConverter.java` | Create | `AttributeConverter<InterviewStatus,String>`, `@Converter(autoApply = false)` (explicit `@Convert` on the entity field — avoids clashing with `TaskStatusConverter`), mirroring `TaskStatusConverter` |
| `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java` | Create | Queue+claim aggregate root entity (incl. `workDir`), mirroring `Task` (factory `create`, `@Setter` only on service-mutated fields, `isOwnedBy`) |
| `src/main/java/com/hamonsoft/netismaker/entity/InterviewTurn.java` | Create | Append-only Q&A log entity (incl. `replyToSeq`), mirroring `TaskStatusHistory` (static factory `of(sessionId, seq, role, kind, content, replyToSeq)`) |
| `src/main/java/com/hamonsoft/netismaker/entity/InterviewPlan.java` | Create | 1:1 satellite plan entity keyed by `sessionId`, mirroring `TaskAnalysis` (static factory `create`) |
| `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java` | Create | CRUD + `countActiveByRequester`, `findActiveById`, claim `FOR UPDATE SKIP LOCKED`, `findInFlightClaimed`, mirroring `TaskRepository` |
| `src/main/java/com/hamonsoft/netismaker/repository/InterviewTurnRepository.java` | Create | Turn persistence + `findBySessionIdOrderBySeqAsc` + `findMaxSeq` |
| `src/main/java/com/hamonsoft/netismaker/repository/InterviewPlanRepository.java` | Create | 1:1 plan persistence keyed by sessionId |
| `src/main/java/com/hamonsoft/netismaker/dto/CreateInterviewRequest.java` | Create | User create payload `{githubRepo,githubBranch,title,description,mcpCatalogIds}` mirroring `TaskCreateRequest` validation |
| `src/main/java/com/hamonsoft/netismaker/dto/AnswerRequest.java` | Create | `{answer,replyToSeq}` for `submitAnswer` |
| `src/main/java/com/hamonsoft/netismaker/dto/WorkerQuestionRequest.java` | Create | `{content,claudeSessionId,kind,costUsd}` for worker question report |
| `src/main/java/com/hamonsoft/netismaker/dto/WorkerPlanRequest.java` | Create | `{designMarkdown,planMarkdown,planJson,costUsd,durationMs}` for worker plan report |
| `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java` | Create | **Canonical** claim payload returned to worker: `sessionId, githubRepo, githubBranch, title, description, claudeSessionId, currentPhase, workDir, lastAnswer, replyToSeq, mcpsExtra, turns` (session meta + resume context + work_dir + last user answer + replyToSeq + turn log) |
| `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` | Create | Single entry point for all interview state transitions + register→Task promotion |
| `src/test/java/com/hamonsoft/netismaker/entity/InterviewStatusConverterTest.java` | Create | Round-trip enum↔Korean dbValue test |
| `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java` | Create | Unit tests for every transition guard + register mapping (Mockito) |
| `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceClaimTest.java` | Create | Claim transition + in-flight finder unit tests (Mockito) |
| `src/test/java/com/hamonsoft/netismaker/repository/InterviewClaimConcurrencyTest.java` | Create | Testcontainers concurrency test: two threads claim, only one wins per session (SKIP LOCKED) |

---

### Task 1: Status enum + converter (mirror TaskStatus / TaskStatusConverter)

**Files:**
- Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatus.java`
- Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatusConverter.java`
- Create `src/test/java/com/hamonsoft/netismaker/entity/InterviewStatusConverterTest.java`

- [ ] **Step 1: Write the failing converter round-trip test**
  Create `src/test/java/com/hamonsoft/netismaker/entity/InterviewStatusConverterTest.java`:
  ```java
  package com.hamonsoft.netismaker.entity;

  import org.junit.jupiter.api.Test;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  class InterviewStatusConverterTest {

      private final InterviewStatusConverter converter = new InterviewStatusConverter();

      @Test
      void every_status_round_trips_through_korean_db_value() {
          for (InterviewStatus s : InterviewStatus.values()) {
              String db = converter.convertToDatabaseColumn(s);
              assertThat(db).isEqualTo(s.dbValue());
              assertThat(converter.convertToEntityAttribute(db)).isEqualTo(s);
          }
      }

      @Test
      void db_values_are_the_expected_korean_labels() {
          assertThat(InterviewStatus.QUEUED.dbValue()).isEqualTo("인터뷰대기");
          assertThat(InterviewStatus.RUNNING.dbValue()).isEqualTo("인터뷰중");
          assertThat(InterviewStatus.AWAITING_INPUT.dbValue()).isEqualTo("입력대기");
          assertThat(InterviewStatus.PLAN_READY.dbValue()).isEqualTo("플랜완료");
          assertThat(InterviewStatus.REGISTERED.dbValue()).isEqualTo("등록됨");
          assertThat(InterviewStatus.CANCELLED.dbValue()).isEqualTo("취소됨");
          assertThat(InterviewStatus.EXPIRED.dbValue()).isEqualTo("만료됨");
          assertThat(InterviewStatus.FAILED.dbValue()).isEqualTo("인터뷰실패");
      }

      @Test
      void null_passes_through_both_directions() {
          assertThat(converter.convertToDatabaseColumn(null)).isNull();
          assertThat(converter.convertToEntityAttribute(null)).isNull();
      }

      @Test
      void unknown_db_value_throws() {
          assertThatThrownBy(() -> InterviewStatus.fromDb("없는상태"))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("Unknown interview status");
      }
  }
  ```

- [ ] **Step 2: Run the test, see it fail to compile**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.entity.InterviewStatusConverterTest'
  ```
  Expected: compilation failure — `cannot find symbol: class InterviewStatus` / `class InterviewStatusConverter`.

- [ ] **Step 3: Create the enum**
  Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatus.java`:
  ```java
  package com.hamonsoft.netismaker.entity;

  /**
   * 인터뷰 세션 상태 머신. DESIGN(대화형 분석) §5 그대로.
   *
   *   [인터뷰대기] ──(워커 claim)──→ [인터뷰중] ──┬─→ [입력대기] (질문 emit, 사람 답변 대기, 워커 반납)
   *                                              ├─→ [플랜완료] (writing-plans 완료)
   *                                              └─→ [인터뷰실패]
   *   [입력대기] ──(답변 도착)──→ [인터뷰대기] (재큐)
   *   [입력대기] ──→ [만료됨] | [취소됨]
   *   [플랜완료] ──(작업 등록)──→ [등록됨] (terminal, task 생성)
   *   [플랜완료] ──→ [취소됨]
   *
   * Task의 상태 enum(TaskStatus)은 변경하지 않는다. 인터뷰 상태는 전부 여기에 있다.
   * DB 값은 한글 그대로 저장 (VARCHAR(30)). Java enum 이름과 분리.
   */
  public enum InterviewStatus {
      QUEUED("인터뷰대기"),
      RUNNING("인터뷰중"),
      AWAITING_INPUT("입력대기"),
      PLAN_READY("플랜완료"),
      REGISTERED("등록됨"),
      CANCELLED("취소됨"),
      EXPIRED("만료됨"),
      FAILED("인터뷰실패");

      private final String dbValue;

      InterviewStatus(String dbValue) {
          this.dbValue = dbValue;
      }

      public String dbValue() {
          return dbValue;
      }

      public static InterviewStatus fromDb(String value) {
          for (InterviewStatus s : values()) {
              if (s.dbValue.equals(value)) return s;
          }
          throw new IllegalArgumentException("Unknown interview status: " + value);
      }
  }
  ```

- [ ] **Step 4: Create the converter**
  Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatusConverter.java`:
  ```java
  package com.hamonsoft.netismaker.entity;

  import jakarta.persistence.AttributeConverter;
  import jakarta.persistence.Converter;

  /**
   * InterviewStatus enum ↔ DB VARCHAR(30) 한글 문자열.
   * TaskStatusConverter와 동일 패턴 — Java enum 이름과 DB 값이 달라 @Enumerated 못 씀.
   *
   * autoApply = false: TaskStatusConverter도 String 컬럼에 붙는 AttributeConverter라
   * autoApply로 켜면 두 컨버터가 모든 String/enum 컬럼에 경합한다. 따라서 자동 적용을 끄고
   * InterviewSession.status 필드에 명시적 @Convert(converter = InterviewStatusConverter.class)로만 적용.
   */
  @Converter(autoApply = false)
  public class InterviewStatusConverter implements AttributeConverter<InterviewStatus, String> {

      @Override
      public String convertToDatabaseColumn(InterviewStatus status) {
          return status == null ? null : status.dbValue();
      }

      @Override
      public InterviewStatus convertToEntityAttribute(String dbValue) {
          return dbValue == null ? null : InterviewStatus.fromDb(dbValue);
      }
  }
  ```

- [ ] **Step 5: Run the test, see it pass**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.entity.InterviewStatusConverterTest'
  ```
  Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 6: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git checkout -b feat/interview-backend-domain && git add src/main/java/com/hamonsoft/netismaker/entity/InterviewStatus.java src/main/java/com/hamonsoft/netismaker/entity/InterviewStatusConverter.java src/test/java/com/hamonsoft/netismaker/entity/InterviewStatusConverterTest.java && git commit -m "feat(interview): InterviewStatus enum + AttributeConverter (Korean dbValues)

TaskStatus/TaskStatusConverter 패턴 미러링. Task enum은 변경 없음.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 2: V11 migration (3 tables in com schema)

**Files:**
- Create `src/main/resources/db/migration/V11__interview.sql`

- [ ] **Step 1: Write the migration**
  Mirrors V1 conventions: `VARCHAR(30)` status with no CHECK constraint, `TIMESTAMPTZ NOT NULL DEFAULT now()`, FK to `com.task` (nullable), CASCADE on the turn child, `jsonb` for snapshot/plan columns with `NOT NULL DEFAULT` for the freeze pattern.
  Create `src/main/resources/db/migration/V11__interview.sql`:
  ```sql
  -- V11: 대화형 분석 (Conversational Analysis) — 인터뷰 세션 3테이블 (com 스키마)
  -- DESIGN(대화형 분석) §7 그대로. status는 CHECK 제약 없는 VARCHAR(30) (기존 컨벤션).
  -- Task 상태머신은 변경하지 않음 — 인터뷰 상태는 전부 interview_session.status에.

  -- 인터뷰 세션: 큐 + 클레임 (com.task 미러)
  CREATE TABLE IF NOT EXISTS com.interview_session (
      id                BIGSERIAL PRIMARY KEY,
      requester_id      VARCHAR(20)  NOT NULL REFERENCES com."user"(user_id),
      github_repo       VARCHAR(255) NOT NULL,
      github_branch     VARCHAR(255) NOT NULL DEFAULT 'main',
      title             VARCHAR(500) NOT NULL,
      description       TEXT         NOT NULL,
      status            VARCHAR(30)  NOT NULL DEFAULT '인터뷰대기',
                        -- 인터뷰대기 | 인터뷰중 | 입력대기 | 플랜완료 | 등록됨 | 취소됨 | 만료됨 | 인터뷰실패
      claude_session_id VARCHAR(100),
      work_dir          VARCHAR(500),
                        -- resume cwd = 레포 체크아웃 경로. 첫 claim 시 Java가 결정/저장,
                        -- 이후 resume claim마다 동일 값 반환 (Claude Code 세션 스토어가 cwd 종속).
      worker_id         VARCHAR(50),
      claimed_at        TIMESTAMPTZ,
      commit_sha        VARCHAR(40),
      current_phase     VARCHAR(20),
                        -- brainstorming | writing-plans
      total_cost_usd    NUMERIC(12,6) NOT NULL DEFAULT 0,
      mcps_extra        JSONB        NOT NULL DEFAULT '[]'::jsonb,
      task_id           BIGINT       REFERENCES com.task(id),
      created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
      updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
      last_activity_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
  );

  -- 워커 claim 큐: QUEUED를 last_activity_at ASC로 (FOR UPDATE SKIP LOCKED 대상)
  CREATE INDEX IF NOT EXISTS idx_interview_queued ON com.interview_session(last_activity_at)
      WHERE status = '인터뷰대기';
  -- 요청자별 active 카운트 + ACL 조회
  CREATE INDEX IF NOT EXISTS idx_interview_requester ON com.interview_session(requester_id, status);
  -- stale 회수: in-flight(인터뷰중) 스캔
  CREATE INDEX IF NOT EXISTS idx_interview_running ON com.interview_session(claimed_at)
      WHERE status = '인터뷰중';

  -- 추가전용 Q&A 로그 (com.task_status_history 미러)
  CREATE TABLE IF NOT EXISTS com.interview_turn (
      id           BIGSERIAL PRIMARY KEY,
      session_id   BIGINT      NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
      seq          INT         NOT NULL,
      role         VARCHAR(20) NOT NULL,
                   -- assistant | user | system
      kind         VARCHAR(20) NOT NULL,
                   -- question | answer | design | gate | note
      content      TEXT        NOT NULL,
      reply_to_seq INT,
                   -- user answer가 응답하는 question 턴의 seq. idempotency 키 + UI 스레딩.
                   --   question/design/note 턴은 null.
      created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
  );

  CREATE INDEX IF NOT EXISTS idx_interview_turn_session_seq
      ON com.interview_turn(session_id, seq);

  -- 1:1 위성 플랜 (com.task_analysis 미러)
  CREATE TABLE IF NOT EXISTS com.interview_plan (
      session_id      BIGINT PRIMARY KEY REFERENCES com.interview_session(id) ON DELETE CASCADE,
      design_markdown TEXT,
      plan_markdown   TEXT,
      plan_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
      duration_ms     BIGINT,
      total_cost_usd  NUMERIC(12,6),
      completed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  ```

- [ ] **Step 2: Validate the migration applies cleanly via Testcontainers Flyway**
  The app already runs Flyway against a fresh Postgres container in integration tests. Confirm the SQL parses and applies by running the existing Testcontainers boot (only runs when `RUN_TESTCONTAINERS=true`):
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.TaskApiIntegrationTest'
  ```
  Expected: `BUILD SUCCESSFUL` — Flyway runs V1..V11 with no error (the new tables apply after V10). If Docker is unavailable, fall back to a SQL lint by eye-checking against V1/V5 (same DDL style); the concurrency test in Task 9 is the authoritative migration validation.

- [ ] **Step 3: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/resources/db/migration/V11__interview.sql && git commit -m "feat(interview): V11 migration — interview_session/turn/plan (com schema)

V1/V5 컨벤션 미러링: VARCHAR(30) status (CHECK 없음), TIMESTAMPTZ DEFAULT now(),
jsonb NOT NULL DEFAULT, partial 인덱스 (queued/running). task FK nullable.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 3: InterviewSession entity (mirror Task)

**Files:**
- Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java`

- [ ] **Step 1: Write the failing factory + ownership test**
  Append to a new test file `src/test/java/com/hamonsoft/netismaker/entity/InterviewSessionTest.java`:
  ```java
  package com.hamonsoft.netismaker.entity;

  import org.junit.jupiter.api.Test;

  import java.util.List;

  import static org.assertj.core.api.Assertions.assertThat;

  class InterviewSessionTest {

      @Test
      void create_initializes_queued_with_defaults() {
          InterviewSession s = InterviewSession.create(
                  "owner/repo", "  ", "제목", "기능 요구", "user1",
                  List.of(new TaskMcpSpec("ctx7", "https://x", "http")));
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
          assertThat(s.getGithubBranch()).isEqualTo("main");            // blank → main
          assertThat(s.getRequesterId()).isEqualTo("user1");
          assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0");
          assertThat(s.getMcpsExtra()).hasSize(1);
          assertThat(s.getCreatedAt()).isNotNull();
          assertThat(s.getLastActivityAt()).isNotNull();
          assertThat(s.getTaskId()).isNull();
          assertThat(s.getClaudeSessionId()).isNull();
          assertThat(s.getWorkDir()).isNull();   // 첫 claim 전엔 미배정
      }

      @Test
      void is_owned_by_matches_requester() {
          InterviewSession s = InterviewSession.create("o/r", "main", "t", "d", "u1", List.of());
          assertThat(s.isOwnedBy("u1")).isTrue();
          assertThat(s.isOwnedBy("u2")).isFalse();
      }
  }
  ```

- [ ] **Step 2: Run, see it fail to compile**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.entity.InterviewSessionTest'
  ```
  Expected: compilation failure — `cannot find symbol: class InterviewSession`.

- [ ] **Step 3: Create the entity**
  Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java`:
  ```java
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
                                            List<TaskMcpSpec> mcpsExtra) {
          InterviewSession s = new InterviewSession();
          s.githubRepo = githubRepo;
          s.githubBranch = githubBranch == null || githubBranch.isBlank() ? "main" : githubBranch;
          s.title = title;
          s.description = description;
          s.requesterId = requesterId;
          s.status = InterviewStatus.QUEUED;
          s.totalCostUsd = BigDecimal.ZERO;
          s.mcpsExtra = mcpsExtra == null ? new ArrayList<>() : mcpsExtra;
          OffsetDateTime now = OffsetDateTime.now();
          s.createdAt = now;
          s.updatedAt = now;
          s.lastActivityAt = now;
          return s;
      }

      public boolean isOwnedBy(String userId) {
          return requesterId.equals(userId);
      }
  }
  ```

- [ ] **Step 4: Run, see it pass**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.entity.InterviewSessionTest'
  ```
  Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java src/test/java/com/hamonsoft/netismaker/entity/InterviewSessionTest.java && git commit -m "feat(interview): InterviewSession entity (Task 패턴 미러링)

factory create()→QUEUED, blank branch→main, @Setter는 서비스 변경 필드만, isOwnedBy.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 4: InterviewTurn + InterviewPlan entities

**Files:**
- Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewTurn.java`
- Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewPlan.java`

- [ ] **Step 1: Write the failing factory test**
  Create `src/test/java/com/hamonsoft/netismaker/entity/InterviewTurnPlanTest.java`:
  ```java
  package com.hamonsoft.netismaker.entity;

  import org.junit.jupiter.api.Test;

  import java.math.BigDecimal;

  import static org.assertj.core.api.Assertions.assertThat;

  class InterviewTurnPlanTest {

      @Test
      void turn_of_sets_all_fields() {
          InterviewTurn t = InterviewTurn.of(7L, 3, "assistant", "question", "어떤 화면에 추가하나요?", null);
          assertThat(t.getSessionId()).isEqualTo(7L);
          assertThat(t.getSeq()).isEqualTo(3);
          assertThat(t.getRole()).isEqualTo("assistant");
          assertThat(t.getKind()).isEqualTo("question");
          assertThat(t.getContent()).isEqualTo("어떤 화면에 추가하나요?");
          assertThat(t.getReplyToSeq()).isNull();   // question 턴은 reply_to_seq 없음
          assertThat(t.getCreatedAt()).isNotNull();
      }

      @Test
      void turn_of_answer_records_reply_to_seq() {
          InterviewTurn t = InterviewTurn.of(7L, 4, "user", "answer", "좌측 패널에 추가", 3);
          assertThat(t.getKind()).isEqualTo("answer");
          assertThat(t.getReplyToSeq()).isEqualTo(3);   // seq 3 question에 대한 답변
      }

      @Test
      void plan_create_sets_all_fields_and_defaults_json() {
          InterviewPlan p = InterviewPlan.create(7L, "# 설계", "# 플랜", null,
                  1234L, new BigDecimal("0.42"));
          assertThat(p.getSessionId()).isEqualTo(7L);
          assertThat(p.getDesignMarkdown()).isEqualTo("# 설계");
          assertThat(p.getPlanMarkdown()).isEqualTo("# 플랜");
          assertThat(p.getPlanJson()).isEqualTo("[]");   // null → "[]"
          assertThat(p.getDurationMs()).isEqualTo(1234L);
          assertThat(p.getTotalCostUsd()).isEqualByComparingTo("0.42");
          assertThat(p.getCompletedAt()).isNotNull();
      }
  }
  ```

- [ ] **Step 2: Run, see it fail to compile**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.entity.InterviewTurnPlanTest'
  ```
  Expected: compilation failure — `cannot find symbol: class InterviewTurn` / `class InterviewPlan`.

- [ ] **Step 3: Create InterviewTurn (mirror TaskStatusHistory)**
  Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewTurn.java`:
  ```java
  package com.hamonsoft.netismaker.entity;

  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Getter;
  import lombok.NoArgsConstructor;

  import java.time.OffsetDateTime;

  /**
   * 인터뷰 Q&A 추가전용 로그 (com.task_status_history 미러).
   * role: assistant | user | system,  kind: question | answer | design | gate | note.
   */
  @Entity
  @Table(name = "interview_turn", schema = "com")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class InterviewTurn {

      @Id
      @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;

      @Column(name = "session_id", nullable = false)
      private Long sessionId;

      @Column(nullable = false)
      private int seq;

      @Column(nullable = false, length = 20)
      private String role;

      @Column(nullable = false, length = 20)
      private String kind;

      @Column(nullable = false, columnDefinition = "TEXT")
      private String content;

      /** user answer가 응답하는 question 턴의 seq. idempotency 키 + UI 스레딩. 그 외 턴은 null. */
      @Column(name = "reply_to_seq")
      private Integer replyToSeq;

      @Column(name = "created_at", nullable = false)
      private OffsetDateTime createdAt;

      public static InterviewTurn of(Long sessionId, int seq, String role, String kind,
                                     String content, Integer replyToSeq) {
          InterviewTurn t = new InterviewTurn();
          t.sessionId = sessionId;
          t.seq = seq;
          t.role = role;
          t.kind = kind;
          t.content = content;
          t.replyToSeq = replyToSeq;
          t.createdAt = OffsetDateTime.now();
          return t;
      }
  }
  ```

- [ ] **Step 4: Create InterviewPlan (mirror TaskAnalysis)**
  Create `src/main/java/com/hamonsoft/netismaker/entity/InterviewPlan.java`:
  ```java
  package com.hamonsoft.netismaker.entity;

  import jakarta.persistence.*;
  import lombok.AccessLevel;
  import lombok.Getter;
  import lombok.NoArgsConstructor;
  import org.hibernate.annotations.JdbcTypeCode;
  import org.hibernate.type.SqlTypes;

  import java.math.BigDecimal;
  import java.time.OffsetDateTime;

  /**
   * 인터뷰 산출물 (1 session : 1 plan). TaskAnalysis 미러.
   * writing-plans 완료 시 design+plan harvest 결과를 영속화. 등록 시 TaskAnalysis로 프리필.
   */
  @Entity
  @Table(name = "interview_plan", schema = "com")
  @Getter
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  public class InterviewPlan {

      @Id
      @Column(name = "session_id")
      private Long sessionId;

      @Column(name = "design_markdown", columnDefinition = "TEXT")
      private String designMarkdown;

      @Column(name = "plan_markdown", columnDefinition = "TEXT")
      private String planMarkdown;

      @JdbcTypeCode(SqlTypes.JSON)
      @Column(name = "plan_json", nullable = false, columnDefinition = "jsonb")
      private String planJson;

      @Column(name = "duration_ms")
      private Long durationMs;

      @Column(name = "total_cost_usd")
      private BigDecimal totalCostUsd;

      @Column(name = "completed_at", nullable = false)
      private OffsetDateTime completedAt;

      public static InterviewPlan create(long sessionId, String designMarkdown, String planMarkdown,
                                         String planJson, Long durationMs, BigDecimal totalCostUsd) {
          InterviewPlan p = new InterviewPlan();
          p.sessionId = sessionId;
          p.designMarkdown = designMarkdown;
          p.planMarkdown = planMarkdown;
          p.planJson = planJson == null ? "[]" : planJson;
          p.durationMs = durationMs;
          p.totalCostUsd = totalCostUsd;
          p.completedAt = OffsetDateTime.now();
          return p;
      }
  }
  ```

- [ ] **Step 5: Run, see it pass**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.entity.InterviewTurnPlanTest'
  ```
  Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 6: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/entity/InterviewTurn.java src/main/java/com/hamonsoft/netismaker/entity/InterviewPlan.java src/test/java/com/hamonsoft/netismaker/entity/InterviewTurnPlanTest.java && git commit -m "feat(interview): InterviewTurn + InterviewPlan entities

InterviewTurn=task_status_history 미러(of factory), InterviewPlan=task_analysis 미러(create, planJson null→'[]').

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 5: Repositories (claim FOR UPDATE SKIP LOCKED + in-flight finder)

**Files:**
- Create `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java`
- Create `src/main/java/com/hamonsoft/netismaker/repository/InterviewTurnRepository.java`
- Create `src/main/java/com/hamonsoft/netismaker/repository/InterviewPlanRepository.java`

> No standalone unit test for repository interfaces (Spring Data generates impls; queries are exercised by the service unit tests in Tasks 6–8 via mocks, and by the Testcontainers concurrency test in Task 9). This task is verified by compilation + the downstream tests.

- [ ] **Step 1: Create InterviewSessionRepository (mirror TaskRepository)**
  Mirrors `TaskRepository`: JPQL `countActiveByRequester`, `findActiveById` (no soft-delete column here, so just by id), claim `@Lock(PESSIMISTIC_WRITE)` + `QueryHints` skip-locked over `QUEUED` FIFO, and `findInFlightClaimed` over `RUNNING`.
  Create `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java`:
  ```java
  package com.hamonsoft.netismaker.repository;

  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import jakarta.persistence.LockModeType;
  import org.springframework.data.domain.Pageable;
  import org.springframework.data.jpa.repository.JpaRepository;
  import org.springframework.data.jpa.repository.Lock;
  import org.springframework.data.jpa.repository.Query;
  import org.springframework.data.jpa.repository.QueryHints;
  import org.springframework.data.repository.query.Param;

  import java.util.List;
  import java.util.Optional;

  public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

      /**
       * 요청자별 미완료(=terminal 아님) 인터뷰 카운트. 동시 인터뷰 한도 검증용.
       * terminal = REGISTERED/CANCELLED/EXPIRED/FAILED.
       */
      @Query("""
          SELECT COUNT(s) FROM InterviewSession s
          WHERE s.requesterId = :requesterId
            AND s.status IN (com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED,
                             com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING,
                             com.hamonsoft.netismaker.entity.InterviewStatus.AWAITING_INPUT,
                             com.hamonsoft.netismaker.entity.InterviewStatus.PLAN_READY)
      """)
      long countActiveByRequester(@Param("requesterId") String requesterId);

      /** 단건 조회 (인터뷰는 soft-delete 없음 — id로 직접). */
      @Query("SELECT s FROM InterviewSession s WHERE s.id = :id")
      Optional<InterviewSession> findActiveById(@Param("id") Long id);

      /**
       * 워커가 다음에 처리할 인터뷰 1건을 atomic claim.
       * SELECT FOR UPDATE SKIP LOCKED. 동시 워커가 있어도 1개만 잡음.
       *
       * 순서 = last_activity_at ASC: 가장 오래 대기한 세션 우선. 답변 직후 재큐된 세션은
       * last_activity_at이 갱신되어 자연히 뒤로 가므로 신규/오래된 세션이 굶지 않는다.
       * claim 후보 = QUEUED (신규 또는 답변 후 재큐). claude_session_id null이면 신규,
       * 있으면 resume. TaskRepository.findClaimableForUpdateSkipLocked와 동일 패턴.
       */
      @Lock(LockModeType.PESSIMISTIC_WRITE)
      @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
      @Query("""
          SELECT s FROM InterviewSession s
          WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED
          ORDER BY s.lastActivityAt ASC
      """)
      List<InterviewSession> findClaimableForUpdateSkipLocked(Pageable pageable);

      /**
       * Stale 회수 잡: 워커가 claim해 처리중인(in-flight = RUNNING) 모든 세션.
       * 회수 여부는 heartbeat + claimed_at으로 판정 (TaskRepository.findInFlightClaimed 미러).
       */
      @Query("""
          SELECT s FROM InterviewSession s
          WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING
            AND s.workerId IS NOT NULL
      """)
      List<InterviewSession> findInFlightClaimed();

      /** 본인/관리자 목록 조회 (status 필터 옵션). */
      @Query("""
          SELECT s FROM InterviewSession s
          WHERE s.requesterId = :requesterId
            AND (:status IS NULL OR s.status = :status)
          ORDER BY s.createdAt DESC
      """)
      List<InterviewSession> findByRequester(@Param("requesterId") String requesterId,
                                             @Param("status") InterviewStatus status);
  }
  ```

- [ ] **Step 2: Create InterviewTurnRepository**
  Create `src/main/java/com/hamonsoft/netismaker/repository/InterviewTurnRepository.java`:
  ```java
  package com.hamonsoft.netismaker.repository;

  import com.hamonsoft.netismaker.entity.InterviewTurn;
  import org.springframework.data.jpa.repository.JpaRepository;
  import org.springframework.data.jpa.repository.Query;
  import org.springframework.data.repository.query.Param;

  import java.util.List;

  public interface InterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {

      /** 세션의 전체 턴을 순서대로. claim 컨텍스트 + 상세 조회용. */
      List<InterviewTurn> findBySessionIdOrderBySeqAsc(Long sessionId);

      /** 다음 seq 계산용. 턴이 없으면 null. */
      @Query("SELECT MAX(t.seq) FROM InterviewTurn t WHERE t.sessionId = :sessionId")
      Integer findMaxSeq(@Param("sessionId") Long sessionId);
  }
  ```

- [ ] **Step 3: Create InterviewPlanRepository**
  Create `src/main/java/com/hamonsoft/netismaker/repository/InterviewPlanRepository.java`:
  ```java
  package com.hamonsoft.netismaker.repository;

  import com.hamonsoft.netismaker.entity.InterviewPlan;
  import org.springframework.data.jpa.repository.JpaRepository;

  public interface InterviewPlanRepository extends JpaRepository<InterviewPlan, Long> {
  }
  ```

- [ ] **Step 4: Compile (no test yet — interfaces only)**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew compileJava
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java src/main/java/com/hamonsoft/netismaker/repository/InterviewTurnRepository.java src/main/java/com/hamonsoft/netismaker/repository/InterviewPlanRepository.java && git commit -m "feat(interview): repositories (claim FOR UPDATE SKIP LOCKED + in-flight finder)

TaskRepository 패턴 미러링: countActiveByRequester, findActiveById,
findClaimableForUpdateSkipLocked(QUEUED FIFO, PESSIMISTIC_WRITE skip-locked),
findInFlightClaimed(RUNNING). turn=findBySessionIdOrderBySeqAsc/findMaxSeq.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 6: DTOs + InterviewService skeleton + create()

**Files:**
- Create `src/main/java/com/hamonsoft/netismaker/dto/CreateInterviewRequest.java`
- Create `src/main/java/com/hamonsoft/netismaker/dto/AnswerRequest.java`
- Create `src/main/java/com/hamonsoft/netismaker/dto/WorkerQuestionRequest.java`
- Create `src/main/java/com/hamonsoft/netismaker/dto/WorkerPlanRequest.java`
- Create `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java`
- Create `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`
- Create `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`

- [ ] **Step 1: Write the failing create() test**
  Create `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java` (sets up mocks the same way `TaskServiceDeployTest` does; reuses `McpCatalogService` for `resolveMcpExtras` and `@Value` fields via `ReflectionTestUtils`):
  ```java
  package com.hamonsoft.netismaker.service;

  import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
  import com.hamonsoft.netismaker.entity.*;
  import com.hamonsoft.netismaker.repository.*;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.test.util.ReflectionTestUtils;

  import java.util.List;
  import java.util.Optional;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;
  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.Mockito.*;

  class InterviewServiceTest {

      InterviewSessionRepository sessionRepo;
      InterviewTurnRepository turnRepo;
      InterviewPlanRepository planRepo;
      McpCatalogService mcpCatalogService;
      TaskRepository taskRepo;
      TaskAnalysisRepository analysisRepo;
      TaskStatusHistoryRepository historyRepo;
      InterviewService service;

      @BeforeEach
      void setUp() {
          sessionRepo = mock(InterviewSessionRepository.class);
          turnRepo = mock(InterviewTurnRepository.class);
          planRepo = mock(InterviewPlanRepository.class);
          mcpCatalogService = mock(McpCatalogService.class);
          taskRepo = mock(TaskRepository.class);
          analysisRepo = mock(TaskAnalysisRepository.class);
          historyRepo = mock(TaskStatusHistoryRepository.class);
          service = new InterviewService(sessionRepo, turnRepo, planRepo,
                  mcpCatalogService, taskRepo, analysisRepo, historyRepo);
          ReflectionTestUtils.setField(service, "userConcurrentLimit", 3);
          ReflectionTestUtils.setField(service, "maxRetry", 3);
          when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
          when(turnRepo.save(any())).thenAnswer(i -> i.getArgument(0));
          when(planRepo.save(any())).thenAnswer(i -> i.getArgument(0));
          when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
          when(taskRepo.save(any())).thenAnswer(i -> {
              Task t = i.getArgument(0);
              ReflectionTestUtils.setField(t, "id", 999L);
              return t;
          });
          when(analysisRepo.save(any())).thenAnswer(i -> i.getArgument(0));
      }

      private CreateInterviewRequest req() {
          return new CreateInterviewRequest("owner/repo", "main", "제목", "기능 요구", List.of());
      }

      @Test
      void create_persists_queued_session() {
          when(sessionRepo.countActiveByRequester("u1")).thenReturn(0L);
          when(mcpCatalogService.resolveByIds(any())).thenReturn(List.of());
          InterviewSession s = service.create(req(), "u1");
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
          assertThat(s.getRequesterId()).isEqualTo("u1");
          verify(sessionRepo).save(any());
      }

      @Test
      void create_over_limit_throws_too_many() {
          when(sessionRepo.countActiveByRequester("u1")).thenReturn(3L);
          assertThatThrownBy(() -> service.create(req(), "u1"))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("한도");
      }
  }
  ```

- [ ] **Step 2: Run, see it fail to compile**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewServiceTest'
  ```
  Expected: compilation failure — `cannot find symbol: class InterviewService` / `class CreateInterviewRequest`.

- [ ] **Step 3: Create the DTOs**
  Create `src/main/java/com/hamonsoft/netismaker/dto/CreateInterviewRequest.java` (mirrors `TaskCreateRequest` validation):
  ```java
  package com.hamonsoft.netismaker.dto;

  import jakarta.validation.constraints.NotBlank;
  import jakarta.validation.constraints.Pattern;
  import jakarta.validation.constraints.Size;

  import java.util.List;

  public record CreateInterviewRequest(
          @NotBlank
          @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$",
                   message = "github_repo는 'owner/repo' 형식이어야 합니다")
          @Size(max = 255)
          String githubRepo,

          @Size(max = 255)
          String githubBranch,

          @NotBlank
          @Size(max = 500)
          String title,

          @NotBlank
          String description,

          /** 카탈로그에서 선택된 추가 MCP id들. null/빈 배열 허용. */
          List<Long> mcpCatalogIds
  ) {}
  ```
  Create `src/main/java/com/hamonsoft/netismaker/dto/AnswerRequest.java`:
  ```java
  package com.hamonsoft.netismaker.dto;

  import jakarta.validation.constraints.NotBlank;

  /** 사용자 답변. replyToSeq는 idempotency 키(중복 답변 무시 기준). */
  public record AnswerRequest(
          @NotBlank String answer,
          Integer replyToSeq
  ) {}
  ```
  Create `src/main/java/com/hamonsoft/netismaker/dto/WorkerQuestionRequest.java`:
  ```java
  package com.hamonsoft.netismaker.dto;

  import java.math.BigDecimal;

  /** 워커가 보고하는 질문 턴. kind: question | design | gate | note. */
  public record WorkerQuestionRequest(
          String content,
          String claudeSessionId,
          String kind,
          BigDecimal costUsd
  ) {}
  ```
  Create `src/main/java/com/hamonsoft/netismaker/dto/WorkerPlanRequest.java`:
  ```java
  package com.hamonsoft.netismaker.dto;

  import java.math.BigDecimal;

  /** 워커가 writing-plans 완료 시 보고하는 산출물. */
  public record WorkerPlanRequest(
          String designMarkdown,
          String planMarkdown,
          String planJson,
          BigDecimal costUsd,
          Long durationMs
  ) {}
  ```
  Create `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java` (**canonical** claim payload — plan 02/03 REUSE this record verbatim; mirrors `WorkerTaskResponse` static-factory style). Field/JSON keys are fixed by the cross-plan contract: `sessionId, githubRepo, githubBranch, title, description, claudeSessionId, currentPhase, workDir, lastAnswer, replyToSeq, mcpsExtra, turns`:
  ```java
  package com.hamonsoft.netismaker.dto;

  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewTurn;
  import com.hamonsoft.netismaker.entity.TaskMcpSpec;

  import java.util.List;

  /**
   * 워커가 인터뷰를 claim했을 때 받는 페이로드 (canonical — plan 02/03가 그대로 재사용).
   * claudeSessionId null → brainstorming 신규 시작, 있으면 resume.
   * workDir = resume 시 반드시 동일하게 써야 하는 cwd(레포 체크아웃 경로, 스파이크 02).
   * lastAnswer = resume 시 주입할 마지막 사용자 답변, replyToSeq = 그 답변이 응답한 question seq.
   * turns = 누적 컨텍스트.
   */
  public record InterviewClaimResponse(
          long sessionId,
          String githubRepo,
          String githubBranch,
          String title,
          String description,
          String claudeSessionId,
          String currentPhase,
          String workDir,
          String lastAnswer,
          Integer replyToSeq,
          List<TaskMcpSpec> mcpsExtra,
          List<Turn> turns
  ) {
      public record Turn(int seq, String role, String kind, String content, Integer replyToSeq) {}

      public static InterviewClaimResponse of(InterviewSession s, List<InterviewTurn> turns) {
          String lastAnswer = null;
          Integer replyToSeq = null;
          for (int i = turns.size() - 1; i >= 0; i--) {
              InterviewTurn t = turns.get(i);
              if ("user".equals(t.getRole()) && "answer".equals(t.getKind())) {
                  lastAnswer = t.getContent();
                  replyToSeq = t.getReplyToSeq();
                  break;
              }
          }
          List<Turn> mapped = turns.stream()
                  .map(t -> new Turn(t.getSeq(), t.getRole(), t.getKind(), t.getContent(), t.getReplyToSeq()))
                  .toList();
          return new InterviewClaimResponse(
                  s.getId(), s.getGithubRepo(), s.getGithubBranch(),
                  s.getTitle(), s.getDescription(),
                  s.getClaudeSessionId(), s.getCurrentPhase(), s.getWorkDir(),
                  lastAnswer, replyToSeq,
                  s.getMcpsExtra() == null ? List.of() : List.copyOf(s.getMcpsExtra()),
                  mapped
          );
      }
  }
  ```

- [ ] **Step 4: Create InterviewService with create() (mirror TaskService)**
  Create `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`. Establishes the constructor + `@Value` limits + `resolveMcpExtras` (copied verbatim from `TaskService` so register/create share the MCP resolution contract) + the `appendTurn` seq helper + `create`:
  ```java
  package com.hamonsoft.netismaker.service;

  import com.hamonsoft.netismaker.dto.AnswerRequest;
  import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
  import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
  import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
  import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
  import com.hamonsoft.netismaker.entity.*;
  import com.hamonsoft.netismaker.repository.*;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.context.annotation.Profile;
  import org.springframework.data.domain.PageRequest;
  import org.springframework.http.HttpStatus;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  import java.math.BigDecimal;
  import java.time.OffsetDateTime;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Optional;

  /**
   * 인터뷰 세션 상태 전이의 단일 진입점. 모든 상태 변경은 여기서 (TaskService 패턴).
   *
   *   create        ─► QUEUED
   *   claim         ─► QUEUED → RUNNING (워커, SKIP LOCKED)
   *   recordQuestion─► RUNNING → AWAITING_INPUT (워커가 질문 emit, 반납)
   *   submitAnswer  ─► AWAITING_INPUT → QUEUED (사용자 답변, 재큐; idempotent)
   *   recordPlan    ─► RUNNING → PLAN_READY (writing-plans 완료)
   *   register      ─► PLAN_READY → REGISTERED (+ Task(COMPLETED)+TaskAnalysis 프리필)
   *   cancel        ─► QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY → CANCELLED
   *   expire        ─► AWAITING_INPUT → EXPIRED (idle TTL)
   *   fail          ─► QUEUED/RUNNING/AWAITING_INPUT → FAILED
   *
   * Task 상태머신(TaskStatus)은 변경하지 않는다.
   */
  @Service
  @Profile("api")
  public class InterviewService {

      private final InterviewSessionRepository sessionRepo;
      private final InterviewTurnRepository turnRepo;
      private final InterviewPlanRepository planRepo;
      private final McpCatalogService mcpCatalogService;
      private final TaskRepository taskRepo;
      private final TaskAnalysisRepository analysisRepo;
      private final TaskStatusHistoryRepository historyRepo;

      @Value("${app.interview.user-concurrent-limit:3}")
      private int userConcurrentLimit;

      @Value("${app.task.max-retry:3}")
      private int maxRetry;

      public InterviewService(InterviewSessionRepository sessionRepo,
                              InterviewTurnRepository turnRepo,
                              InterviewPlanRepository planRepo,
                              McpCatalogService mcpCatalogService,
                              TaskRepository taskRepo,
                              TaskAnalysisRepository analysisRepo,
                              TaskStatusHistoryRepository historyRepo) {
          this.sessionRepo = sessionRepo;
          this.turnRepo = turnRepo;
          this.planRepo = planRepo;
          this.mcpCatalogService = mcpCatalogService;
          this.taskRepo = taskRepo;
          this.analysisRepo = analysisRepo;
          this.historyRepo = historyRepo;
      }

      @Transactional
      public InterviewSession create(CreateInterviewRequest req, String requesterId) {
          long active = sessionRepo.countActiveByRequester(requesterId);
          if (active >= userConcurrentLimit) {
              throw TaskException.tooManyRequests(
                      "동시에 진행할 수 있는 인터뷰 한도(" + userConcurrentLimit + ")를 초과했습니다");
          }
          List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
          InterviewSession s = InterviewSession.create(req.githubRepo(), req.githubBranch(),
                  req.title(), req.description(), requesterId, extras);
          return sessionRepo.save(s);
      }

      /** 카탈로그 id 리스트 → snapshot 스펙. 비활성/누락 id는 거절. TaskService와 동일 규칙. */
      private List<TaskMcpSpec> resolveMcpExtras(List<Long> catalogIds) {
          if (catalogIds == null || catalogIds.isEmpty()) return new ArrayList<>();
          List<McpCatalogEntry> entries = mcpCatalogService.resolveByIds(catalogIds);
          if (entries.size() != catalogIds.size()) {
              throw new TaskException(HttpStatus.BAD_REQUEST,
                      "존재하지 않는 MCP 카탈로그 id 포함. 요청=" + catalogIds.size()
                              + " 매칭=" + entries.size());
          }
          for (McpCatalogEntry e : entries) {
              if (!e.isEnabled()) {
                  throw new TaskException(HttpStatus.BAD_REQUEST,
                          "비활성화된 MCP 카탈로그 항목: " + e.getName());
              }
          }
          List<TaskMcpSpec> out = new ArrayList<>(entries.size());
          for (McpCatalogEntry e : entries) {
              out.add(new TaskMcpSpec(e.getName(), e.getUrl(), e.getTransport()));
          }
          return out;
      }

      /** session_id의 다음 seq. 턴이 없으면 0. */
      private int nextSeq(Long sessionId) {
          Integer max = turnRepo.findMaxSeq(sessionId);
          return max == null ? 0 : max + 1;
      }

      /** assistant/system 턴 (reply_to_seq 없음). */
      private InterviewTurn appendTurn(Long sessionId, String role, String kind, String content) {
          return appendTurn(sessionId, role, kind, content, null);
      }

      /** user answer 턴은 replyToSeq를 함께 기록 (idempotency 키 + UI 스레딩). */
      private InterviewTurn appendTurn(Long sessionId, String role, String kind, String content,
                                       Integer replyToSeq) {
          return turnRepo.save(
                  InterviewTurn.of(sessionId, nextSeq(sessionId), role, kind, content, replyToSeq));
      }
  }
  ```

- [ ] **Step 5: Run, see it pass**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewServiceTest'
  ```
  Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 6: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/dto/CreateInterviewRequest.java src/main/java/com/hamonsoft/netismaker/dto/AnswerRequest.java src/main/java/com/hamonsoft/netismaker/dto/WorkerQuestionRequest.java src/main/java/com/hamonsoft/netismaker/dto/WorkerPlanRequest.java src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java && git commit -m "feat(interview): DTOs + InterviewService.create() (TaskService 패턴)

CreateInterviewRequest/AnswerRequest/WorkerQuestionRequest/WorkerPlanRequest/InterviewClaimResponse.
create()→QUEUED + 요청자별 동시 한도 가드. resolveMcpExtras는 TaskService 규칙 동일.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 7: Worker-facing transitions — claim, recordQuestion, recordPlan, fail, heartbeat

**Files:**
- Modify `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`
- Modify `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`

- [ ] **Step 1: Write failing tests for claim / recordQuestion / recordPlan / fail**
  Add to `InterviewServiceTest.java` (a helper `session(...)` plus four transition tests):
  ```java
      private InterviewSession session(long id, InterviewStatus status) {
          InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1", List.of());
          ReflectionTestUtils.setField(s, "id", id);
          s.setStatus(status);
          return s;
      }

      @Test
      void claim_moves_queued_to_running_and_sets_worker_and_assigns_work_dir() {
          InterviewSession s = session(1L, InterviewStatus.QUEUED);
          when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
          when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
          Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w1");
          assertThat(resp).isPresent();
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.RUNNING);
          assertThat(s.getWorkerId()).isEqualTo("w1");
          assertThat(s.getClaimedAt()).isNotNull();
          // 첫 claim → 결정적 work_dir 배정 + 응답에 포함
          assertThat(s.getWorkDir()).endsWith("/netis-maker/interviews/owner/repo/session-1");
          assertThat(resp.get().workDir()).isEqualTo(s.getWorkDir());
          assertThat(resp.get().sessionId()).isEqualTo(1L);
      }

      @Test
      void claim_resume_keeps_existing_work_dir() {
          InterviewSession s = session(1L, InterviewStatus.QUEUED);
          s.setWorkDir("/preset/path/session-1");
          when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(s));
          when(turnRepo.findBySessionIdOrderBySeqAsc(1L)).thenReturn(List.of());
          Optional<com.hamonsoft.netismaker.dto.InterviewClaimResponse> resp = service.claim("w2");
          assertThat(s.getWorkDir()).isEqualTo("/preset/path/session-1");   // resume → 불변
          assertThat(resp.get().workDir()).isEqualTo("/preset/path/session-1");
      }

      @Test
      void claim_empty_queue_returns_empty() {
          when(sessionRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of());
          assertThat(service.claim("w1")).isEmpty();
      }

      @Test
      void recordQuestion_moves_running_to_awaiting_input_and_releases_worker() {
          InterviewSession s = session(2L, InterviewStatus.RUNNING);
          s.setWorkerId("w1");
          when(sessionRepo.findActiveById(2L)).thenReturn(Optional.of(s));
          when(turnRepo.findMaxSeq(2L)).thenReturn(null);
          service.recordQuestion(2L, "w1", new com.hamonsoft.netismaker.dto.WorkerQuestionRequest(
                  "어떤 화면에 추가하나요?", "sess-abc", "question", new BigDecimal("0.01")));
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
          assertThat(s.getWorkerId()).isNull();
          assertThat(s.getClaudeSessionId()).isEqualTo("sess-abc");
          assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0.01");
          verify(turnRepo).save(any());
      }

      @Test
      void recordQuestion_from_wrong_status_throws() {
          InterviewSession s = session(2L, InterviewStatus.QUEUED);
          when(sessionRepo.findActiveById(2L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.recordQuestion(2L, "w1",
                  new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null)))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("인터뷰중");
      }

      @Test
      void recordQuestion_wrong_worker_throws() {
          InterviewSession s = session(2L, InterviewStatus.RUNNING);
          s.setWorkerId("w1");
          when(sessionRepo.findActiveById(2L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.recordQuestion(2L, "w2",
                  new com.hamonsoft.netismaker.dto.WorkerQuestionRequest("q", "s", "question", null)))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("다른 워커");
      }

      @Test
      void recordPlan_moves_running_to_plan_ready_and_persists_plan() {
          InterviewSession s = session(3L, InterviewStatus.RUNNING);
          s.setWorkerId("w1");
          when(sessionRepo.findActiveById(3L)).thenReturn(Optional.of(s));
          service.recordPlan(3L, "w1", new WorkerPlanRequest("# 설계", "# 플랜", "[]",
                  new BigDecimal("0.05"), 4321L));
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
          assertThat(s.getWorkerId()).isNull();
          assertThat(s.getTotalCostUsd()).isEqualByComparingTo("0.05");
          verify(planRepo).save(any());
      }

      @Test
      void fail_from_running_moves_to_failed() {
          InterviewSession s = session(4L, InterviewStatus.RUNNING);
          s.setWorkerId("w1");
          when(sessionRepo.findActiveById(4L)).thenReturn(Optional.of(s));
          service.fail(4L, "w1", "clone 실패");
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.FAILED);
          assertThat(s.getWorkerId()).isNull();
      }

      @Test
      void fail_from_terminal_throws() {
          InterviewSession s = session(4L, InterviewStatus.REGISTERED);
          when(sessionRepo.findActiveById(4L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.fail(4L, "w1", "x"))
                  .isInstanceOf(TaskException.class);
      }
  ```

- [ ] **Step 2: Run, see it fail**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewServiceTest'
  ```
  Expected: compilation failure — `cannot find symbol: method claim / recordQuestion / recordPlan / fail`.

- [ ] **Step 3: Add the four worker-facing methods + heartbeat + cost helper to InterviewService**
  Insert before the closing brace of `InterviewService`:
  ```java
      /**
       * QUEUED 1건을 atomic claim → RUNNING. 없으면 Optional.empty.
       * SELECT FOR UPDATE SKIP LOCKED, last_activity_at ASC (TaskRepository.claim 패턴).
       *
       * 첫 claim 시 work_dir를 결정적 경로로 배정/영속화하고,
       * resume claim에서는 저장된 값을 그대로 반환 (Claude Code 세션 스토어가 cwd 종속, 스파이크 02).
       */
      @Transactional
      public Optional<InterviewClaimResponse> claim(String workerId) {
          List<InterviewSession> candidates =
                  sessionRepo.findClaimableForUpdateSkipLocked(PageRequest.of(0, 1));
          if (candidates.isEmpty()) return Optional.empty();
          InterviewSession s = candidates.get(0);
          if (s.getStatus() != InterviewStatus.QUEUED) {
              throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                      "claim 후보가 처리 가능한 상태가 아님: " + s.getStatus());
          }
          s.setStatus(InterviewStatus.RUNNING);
          s.setWorkerId(workerId);
          s.setClaimedAt(OffsetDateTime.now());
          // 첫 claim에만 work_dir 배정. resume이면 기존 값 유지.
          if (s.getWorkDir() == null || s.getWorkDir().isBlank()) {
              s.setWorkDir(deriveWorkDir(s.getGithubRepo(), s.getId()));
          }
          touch(s);
          List<InterviewTurn> turns = turnRepo.findBySessionIdOrderBySeqAsc(s.getId());
          return Optional.of(InterviewClaimResponse.of(s, turns));
      }

      /**
       * 결정적 체크아웃 경로: ~/netis-maker/interviews/{owner}/{repo}/session-{id}.
       * 단일 호스트/공유 FS 전제. 같은 세션은 resume마다 항상 같은 경로 → 동일 cwd 보장.
       */
      private String deriveWorkDir(String githubRepo, Long sessionId) {
          String[] parts = githubRepo.split("/", 2);
          String owner = parts.length == 2 ? parts[0] : "_";
          String repo = parts.length == 2 ? parts[1] : githubRepo;
          String home = System.getProperty("user.home");
          return home + "/netis-maker/interviews/" + owner + "/" + repo + "/session-" + sessionId;
      }

      /**
       * 워커가 질문(평문) emit → AWAITING_INPUT, 워커 반납.
       * turn(role=assistant) 저장 + claude_session_id 캡처 + 비용 누적.
       */
      @Transactional
      public InterviewTurn recordQuestion(Long sessionId, String workerId, WorkerQuestionRequest req) {
          InterviewSession s = requireSession(sessionId);
          if (s.getStatus() != InterviewStatus.RUNNING) {
              throw TaskException.conflict("인터뷰중 상태에서만 질문을 보고할 수 있습니다 (현재: "
                      + s.getStatus().dbValue() + ")");
          }
          requireWorker(s, workerId);
          if (req.claudeSessionId() != null && !req.claudeSessionId().isBlank()) {
              s.setClaudeSessionId(req.claudeSessionId());
          }
          addCost(s, req.costUsd());
          String kind = req.kind() == null || req.kind().isBlank() ? "question" : req.kind();
          InterviewTurn turn = appendTurn(sessionId, "assistant", kind,
                  req.content() == null ? "" : req.content());
          s.setStatus(InterviewStatus.AWAITING_INPUT);
          s.setWorkerId(null);
          s.setClaimedAt(null);
          s.setCurrentPhase("brainstorming");
          touch(s);
          return turn;
      }

      /**
       * writing-plans 완료 → PLAN_READY. design+plan 영속화, 비용 누적, 워커 반납.
       */
      @Transactional
      public InterviewPlan recordPlan(Long sessionId, String workerId, WorkerPlanRequest req) {
          InterviewSession s = requireSession(sessionId);
          if (s.getStatus() != InterviewStatus.RUNNING) {
              throw TaskException.conflict("인터뷰중 상태에서만 플랜을 보고할 수 있습니다 (현재: "
                      + s.getStatus().dbValue() + ")");
          }
          requireWorker(s, workerId);
          addCost(s, req.costUsd());
          InterviewPlan plan = planRepo.save(InterviewPlan.create(sessionId,
                  req.designMarkdown(), req.planMarkdown(), req.planJson(),
                  req.durationMs(), s.getTotalCostUsd()));
          appendTurn(sessionId, "assistant", "design",
                  req.designMarkdown() == null ? "" : req.designMarkdown());
          s.setStatus(InterviewStatus.PLAN_READY);
          s.setWorkerId(null);
          s.setClaimedAt(null);
          s.setCurrentPhase("writing-plans");
          touch(s);
          return plan;
      }

      /** 워커가 idle heartbeat 시 last_activity 갱신 (회수 오탐 방지용 best-effort). */
      @Transactional
      public void heartbeat(Long sessionId, String workerId) {
          InterviewSession s = requireSession(sessionId);
          requireWorker(s, workerId);
          s.setClaimedAt(OffsetDateTime.now());
          touch(s);
      }

      /** clone/SDK/parse/비용상한 등 오류 → FAILED. terminal 상태에선 거부. */
      @Transactional
      public InterviewSession fail(Long sessionId, String actor, String reason) {
          InterviewSession s = requireSession(sessionId);
          InterviewStatus st = s.getStatus();
          if (st != InterviewStatus.QUEUED && st != InterviewStatus.RUNNING
                  && st != InterviewStatus.AWAITING_INPUT) {
              throw TaskException.conflict("진행중 인터뷰만 실패 처리할 수 있습니다 (현재: "
                      + st.dbValue() + ")");
          }
          appendTurn(sessionId, "system", "note", "인터뷰 실패: " + (reason == null ? "원인 미상" : reason));
          s.setStatus(InterviewStatus.FAILED);
          s.setWorkerId(null);
          s.setClaimedAt(null);
          touch(s);
          return s;
      }

      private InterviewSession requireSession(Long sessionId) {
          return sessionRepo.findActiveById(sessionId).orElseThrow(TaskException::notFound);
      }

      private void requireWorker(InterviewSession s, String workerId) {
          if (s.getWorkerId() != null && !s.getWorkerId().equals(workerId)) {
              throw TaskException.conflict("다른 워커가 잡은 인터뷰입니다 (소유: " + s.getWorkerId() + ")");
          }
      }

      private void addCost(InterviewSession s, BigDecimal cost) {
          if (cost != null) {
              BigDecimal base = s.getTotalCostUsd() == null ? BigDecimal.ZERO : s.getTotalCostUsd();
              s.setTotalCostUsd(base.add(cost));
          }
      }

      private void touch(InterviewSession s) {
          OffsetDateTime now = OffsetDateTime.now();
          s.setUpdatedAt(now);
          s.setLastActivityAt(now);
      }
  ```

- [ ] **Step 4: Run, see it pass**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewServiceTest'
  ```
  Expected: `BUILD SUCCESSFUL`, all tests in the class pass (create x2 + claim/recordQuestion/recordPlan/fail group).

- [ ] **Step 5: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java && git commit -m "feat(interview): worker transitions — claim/recordQuestion/recordPlan/heartbeat/fail

claim QUEUED→RUNNING(SKIP LOCKED), recordQuestion RUNNING→AWAITING_INPUT(워커 반납+session_id 캡처+비용),
recordPlan RUNNING→PLAN_READY(plan 영속), fail→FAILED. worker 소유권/상태 가드 + 비용 누적.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 8: User-facing transitions — submitAnswer (idempotent), cancel, expire, register→Task

**Files:**
- Modify `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`
- Modify `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`

- [ ] **Step 1: Write failing tests for submitAnswer / cancel / expire / register**
  Add to `InterviewServiceTest.java`:
  ```java
      @Test
      void submitAnswer_moves_awaiting_to_queued_and_logs_user_turn() {
          InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
          when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
          when(turnRepo.findMaxSeq(10L)).thenReturn(2);  // last question at seq 2
          service.submitAnswer(10L, "u1", false,
                  new com.hamonsoft.netismaker.dto.AnswerRequest("좌측 패널에 추가", 2));
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
          verify(turnRepo).save(any());
      }

      @Test
      void submitAnswer_duplicate_replyToSeq_is_ignored() {
          InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
          when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
          // an answer turn already exists at seq 3 replying to question seq 2 (reply_to_seq=2)
          com.hamonsoft.netismaker.entity.InterviewTurn existing =
                  com.hamonsoft.netismaker.entity.InterviewTurn.of(10L, 3, "user", "answer", "이전 답변", 2);
          when(turnRepo.findBySessionIdOrderBySeqAsc(10L)).thenReturn(List.of(existing));
          service.submitAnswer(10L, "u1", false,
                  new com.hamonsoft.netismaker.dto.AnswerRequest("중복", 2));
          // no new turn, status unchanged (still AWAITING_INPUT)
          verify(turnRepo, never()).save(any());
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
      }

      @Test
      void submitAnswer_to_expired_session_throws_conflict() {
          InterviewSession s = session(10L, InterviewStatus.EXPIRED);
          when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.submitAnswer(10L, "u1", false,
                  new com.hamonsoft.netismaker.dto.AnswerRequest("늦은 답변", 1)))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("입력대기");
      }

      @Test
      void submitAnswer_by_non_owner_throws_forbidden() {
          InterviewSession s = session(10L, InterviewStatus.AWAITING_INPUT);
          when(sessionRepo.findActiveById(10L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.submitAnswer(10L, "intruder", false,
                  new com.hamonsoft.netismaker.dto.AnswerRequest("x", 1)))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("권한");
      }

      @Test
      void cancel_from_awaiting_input_moves_to_cancelled() {
          InterviewSession s = session(11L, InterviewStatus.AWAITING_INPUT);
          when(sessionRepo.findActiveById(11L)).thenReturn(Optional.of(s));
          service.cancel(11L, "u1", false);
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
      }

      @Test
      void cancel_from_registered_throws() {
          InterviewSession s = session(11L, InterviewStatus.REGISTERED);
          when(sessionRepo.findActiveById(11L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.cancel(11L, "u1", false))
                  .isInstanceOf(TaskException.class);
      }

      @Test
      void expire_from_awaiting_input_moves_to_expired() {
          InterviewSession s = session(12L, InterviewStatus.AWAITING_INPUT);
          when(sessionRepo.findActiveById(12L)).thenReturn(Optional.of(s));
          service.expire(12L, "idle TTL 초과");
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.EXPIRED);
      }

      @Test
      void expire_from_running_throws() {
          InterviewSession s = session(12L, InterviewStatus.RUNNING);
          when(sessionRepo.findActiveById(12L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.expire(12L, "x"))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("입력대기");
      }

      @Test
      void register_creates_completed_task_and_analysis_then_marks_registered() {
          InterviewSession s = session(20L, InterviewStatus.PLAN_READY);
          when(sessionRepo.findActiveById(20L)).thenReturn(Optional.of(s));
          InterviewPlan plan = InterviewPlan.create(20L, "# 설계", "# 플랜",
                  "[{\"title\":\"sub1\"}]", 1000L, new BigDecimal("0.5"));
          when(planRepo.findById(20L)).thenReturn(Optional.of(plan));

          Long taskId = service.register(20L, "u1", false);

          assertThat(taskId).isEqualTo(999L);
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.REGISTERED);
          assertThat(s.getTaskId()).isEqualTo(999L);
          // task saved as COMPLETED
          org.mockito.ArgumentCaptor<Task> taskCap = org.mockito.ArgumentCaptor.forClass(Task.class);
          verify(taskRepo).save(taskCap.capture());
          assertThat(taskCap.getValue().getStatus()).isEqualTo(TaskStatus.COMPLETED);
          // analysis prefilled: markdown_result = design_markdown ONLY (합본 X), subtasks_json = plan_json, claude_log = null
          org.mockito.ArgumentCaptor<TaskAnalysis> aCap = org.mockito.ArgumentCaptor.forClass(TaskAnalysis.class);
          verify(analysisRepo).save(aCap.capture());
          assertThat(aCap.getValue().getMarkdownResult()).isEqualTo("# 설계");
          assertThat(aCap.getValue().getMarkdownResult()).doesNotContain("# 플랜");
          assertThat(aCap.getValue().getSubtasksJson()).isEqualTo("[{\"title\":\"sub1\"}]");
          assertThat(aCap.getValue().getClaudeLog()).isNull();
          // history logged: null → COMPLETED
          verify(historyRepo).save(any());
      }

      @Test
      void register_not_plan_ready_throws() {
          InterviewSession s = session(20L, InterviewStatus.AWAITING_INPUT);
          when(sessionRepo.findActiveById(20L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.register(20L, "u1", false))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("플랜완료");
      }

      @Test
      void register_by_non_owner_throws_forbidden() {
          InterviewSession s = session(20L, InterviewStatus.PLAN_READY);
          when(sessionRepo.findActiveById(20L)).thenReturn(Optional.of(s));
          assertThatThrownBy(() -> service.register(20L, "intruder", false))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("권한");
      }
  ```

- [ ] **Step 2: Run, see it fail**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewServiceTest'
  ```
  Expected: compilation failure — `cannot find symbol: method submitAnswer / cancel / expire / register`.

- [ ] **Step 3: Add submitAnswer / cancel / expire / register to InterviewService**
  Insert before the `requireSession` helper. `register` builds a `Task` via `Task.create(...)`, force-sets its status to `COMPLETED` (the interview already did the analysis), persists a `TaskAnalysis` via `TaskAnalysis.create(taskId, designMarkdown, planJson, null, durationMs)` — **`markdown_result` = `design_markdown` ONLY (no design+plan composite), `subtasks_json` = `plan_json`, `claude_log` = `null`** — links `task_id`, and logs the `null → COMPLETED` history row exactly like the worker analysis path does:
  ```java
      /**
       * 사용자 답변 → AWAITING_INPUT → QUEUED 재큐. idempotent:
       * 같은 replyToSeq에 응답하는 user answer 턴이 이미 있으면 무시 (reply_to_seq 컬럼이 키).
       */
      @Transactional
      public InterviewSession submitAnswer(Long sessionId, String actorId, boolean isAdmin, AnswerRequest req) {
          InterviewSession s = requireSession(sessionId);
          requireOwner(s, actorId, isAdmin);
          if (s.getStatus() != InterviewStatus.AWAITING_INPUT) {
              throw TaskException.conflict("입력대기 상태에서만 답변할 수 있습니다 (현재: "
                      + s.getStatus().dbValue() + ")");
          }
          // idempotency: 같은 replyToSeq에 대한 user answer 턴이 이미 있으면 중복 → 무시
          if (req.replyToSeq() != null) {
              boolean already = turnRepo.findBySessionIdOrderBySeqAsc(sessionId).stream()
                      .anyMatch(t -> "user".equals(t.getRole()) && "answer".equals(t.getKind())
                              && req.replyToSeq().equals(t.getReplyToSeq()));
              if (already) return s; // no-op, 상태 유지
          }
          appendTurn(sessionId, "user", "answer", req.answer(), req.replyToSeq());
          s.setStatus(InterviewStatus.QUEUED);
          touch(s);
          return s;
      }

      /** 사용자 취소 — QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY에서만. */
      @Transactional
      public InterviewSession cancel(Long sessionId, String actorId, boolean isAdmin) {
          InterviewSession s = requireSession(sessionId);
          requireOwner(s, actorId, isAdmin);
          InterviewStatus st = s.getStatus();
          if (st != InterviewStatus.QUEUED && st != InterviewStatus.RUNNING
                  && st != InterviewStatus.AWAITING_INPUT && st != InterviewStatus.PLAN_READY) {
              throw TaskException.conflict("진행중 인터뷰만 취소할 수 있습니다 (현재: " + st.dbValue() + ")");
          }
          appendTurn(sessionId, "system", "note", "사용자 취소");
          s.setStatus(InterviewStatus.CANCELLED);
          s.setWorkerId(null);
          s.setClaimedAt(null);
          touch(s);
          return s;
      }

      /** idle TTL 초과 → EXPIRED. AWAITING_INPUT(사람 미복귀) 한정. */
      @Transactional
      public InterviewSession expire(Long sessionId, String reason) {
          InterviewSession s = requireSession(sessionId);
          if (s.getStatus() != InterviewStatus.AWAITING_INPUT) {
              throw TaskException.conflict("입력대기 상태에서만 만료할 수 있습니다 (현재: "
                      + s.getStatus().dbValue() + ")");
          }
          appendTurn(sessionId, "system", "note", "만료: " + (reason == null ? "idle TTL 초과" : reason));
          s.setStatus(InterviewStatus.EXPIRED);
          touch(s);
          return s;
      }

      /**
       * "작업 등록" — PLAN_READY → REGISTERED. Task(COMPLETED) + TaskAnalysis 프리필 생성.
       * 기존 승인 게이트(COMPLETED→APPROVED)는 유지(거버넌스). task는 인터뷰 완료 후에만 생성.
       * 반환: 생성된 taskId.
       */
      @Transactional
      public Long register(Long sessionId, String actorId, boolean isAdmin) {
          InterviewSession s = requireSession(sessionId);
          requireOwner(s, actorId, isAdmin);
          if (s.getStatus() != InterviewStatus.PLAN_READY) {
              throw TaskException.conflict("플랜완료 상태에서만 작업 등록할 수 있습니다 (현재: "
                      + s.getStatus().dbValue() + ")");
          }
          InterviewPlan plan = planRepo.findById(sessionId)
                  .orElseThrow(() -> TaskException.conflict("인터뷰 플랜이 없습니다"));

          // Task(COMPLETED) 생성 — 인터뷰가 분석을 대체. mcps_extra 스냅샷 승계.
          Task t = Task.create(s.getGithubRepo(), s.getGithubBranch(), s.getTitle(),
                  s.getDescription(), s.getRequesterId(), maxRetry,
                  new ArrayList<>(s.getMcpsExtra() == null ? List.of() : s.getMcpsExtra()));
          t.setStatus(TaskStatus.COMPLETED);
          Task saved = taskRepo.save(t);

          // TaskAnalysis 프리필 (계약 고정):
          //   markdown_result = design_markdown ONLY (합본 X),
          //   subtasks_json   = plan_json,
          //   claude_log      = null,
          //   duration_ms     = plan.durationMs.
          TaskAnalysis a = TaskAnalysis.create(saved.getId(), plan.getDesignMarkdown(),
                  plan.getPlanJson(), null, plan.getDurationMs());
          analysisRepo.save(a);

          historyRepo.save(TaskStatusHistory.log(saved.getId(), null, TaskStatus.COMPLETED,
                  "system", actorId, "대화형 분석 등록 (interview_session " + sessionId + ")"));

          // 세션 마감.
          s.setTaskId(saved.getId());
          s.setStatus(InterviewStatus.REGISTERED);
          touch(s);
          return saved.getId();
      }

      private void requireOwner(InterviewSession s, String actorId, boolean isAdmin) {
          if (!s.isOwnedBy(actorId) && !isAdmin) {
              throw TaskException.forbidden();
          }
      }
  ```

- [ ] **Step 4: Run, see it pass**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewServiceTest'
  ```
  Expected: `BUILD SUCCESSFUL`, all tests in the class pass (every transition + register mapping covered).

- [ ] **Step 5: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java && git commit -m "feat(interview): user transitions — submitAnswer(idempotent)/cancel/expire/register

submitAnswer AWAITING_INPUT→QUEUED(reply_to_seq idempotency), cancel→CANCELLED, expire→EXPIRED,
register PLAN_READY→REGISTERED + Task(COMPLETED)+TaskAnalysis 프리필(markdown_result=design_markdown만, subtasks_json=plan_json, claude_log=null).
소유권/상태 가드. task는 인터뷰 완료 후에만 생성(성공기준 #4).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 9: Claim concurrency test (Testcontainers, FOR UPDATE SKIP LOCKED)

**Files:**
- Create `src/test/java/com/hamonsoft/netismaker/repository/InterviewClaimConcurrencyTest.java`

> This is the authoritative proof that the migration applies and the claim query is genuinely skip-locked. It runs only under `RUN_TESTCONTAINERS=true` (same gate as `TaskApiIntegrationTest`), against a real Postgres container where `FOR UPDATE SKIP LOCKED` actually takes effect (H2 cannot honor skip-locked semantics, so this must be a container test).

- [ ] **Step 1: Write the failing concurrency test**
  Create `src/test/java/com/hamonsoft/netismaker/repository/InterviewClaimConcurrencyTest.java`. Seeds `com."user"` (FK) the same way `TestcontainersConfig`'s `init-test-schema.sql` provides it, creates N QUEUED sessions, then fires N concurrent `claim` calls and asserts each session is claimed by exactly one worker (no double-claim).
  ```java
  package com.hamonsoft.netismaker.repository;

  import com.hamonsoft.netismaker.TestcontainersConfig;
  import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
  import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.service.InterviewService;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.test.context.ContextConfiguration;

  import java.util.List;
  import java.util.Optional;
  import java.util.Set;
  import java.util.concurrent.*;

  import static org.assertj.core.api.Assertions.assertThat;

  /**
   * 인터뷰 claim 동시성 — FOR UPDATE SKIP LOCKED가 같은 세션을 두 워커에 안 준다.
   * 실 Postgres 필요(H2는 skip-locked 미지원). RUN_TESTCONTAINERS=true에서만 실행.
   */
  @EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
  @SpringBootTest
  @ContextConfiguration(initializers = TestcontainersConfig.class)
  class InterviewClaimConcurrencyTest {

      @Autowired private InterviewService service;
      @Autowired private InterviewSessionRepository sessionRepo;

      @BeforeEach
      void clean() {
          sessionRepo.deleteAll();
      }

      @Test
      void concurrent_claims_never_double_assign_a_session() throws Exception {
          int n = 8;
          // init-test-schema.sql seeds com."user"(user_id='testuser'); reuse it as requester.
          for (int i = 0; i < n; i++) {
              service.create(new CreateInterviewRequest(
                      "owner/repo" + i, "main", "T" + i, "desc", List.of()), "testuser");
          }

          ExecutorService pool = Executors.newFixedThreadPool(n);
          List<Future<Optional<InterviewClaimResponse>>> futures = new java.util.ArrayList<>();
          CountDownLatch start = new CountDownLatch(1);
          for (int i = 0; i < n; i++) {
              final String worker = "w" + i;
              futures.add(pool.submit(() -> {
                  start.await();
                  return service.claim(worker);
              }));
          }
          start.countDown();

          Set<Long> claimedIds = ConcurrentHashMap.newKeySet();
          int claims = 0;
          for (Future<Optional<InterviewClaimResponse>> f : futures) {
              Optional<InterviewClaimResponse> r = f.get(10, TimeUnit.SECONDS);
              if (r.isPresent()) {
                  claims++;
                  // 같은 세션이 두 번 claim되지 않았는지 — add가 false면 중복
                  assertThat(claimedIds.add(r.get().sessionId())).as("세션 중복 claim").isTrue();
              }
          }
          pool.shutdown();

          // n개 세션, n개 워커 → 정확히 n번 claim, 전부 RUNNING
          assertThat(claims).isEqualTo(n);
          assertThat(sessionRepo.findAll())
                  .allMatch(s -> s.getStatus() == InterviewStatus.RUNNING);
      }
  }
  ```

- [ ] **Step 2: Run the test, see it pass (container required)**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.repository.InterviewClaimConcurrencyTest'
  ```
  Expected: `BUILD SUCCESSFUL` — exactly 8 claims, no session claimed twice, all sessions RUNNING. (This run also re-validates V11 applies in a fresh DB.) If `init-test-schema.sql` does not already seed a `com."user"` row with `user_id='testuser'`, add that row to `src/test/resources/init-test-schema.sql` first (the existing `TaskApiIntegrationTest` relies on the same seed, so confirm the seed user id and use it as the requester here).

- [ ] **Step 3: Verify the seed user id matches**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && grep -n 'INSERT INTO com."user"\|user_id' src/test/resources/init-test-schema.sql
  ```
  Expected: a row defining the seed `user_id`. If it differs from `testuser`, update the `service.create(..., "<seedUserId>")` argument in the test to match before re-running Step 2.

- [ ] **Step 4: Commit**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/test/java/com/hamonsoft/netismaker/repository/InterviewClaimConcurrencyTest.java && git commit -m "test(interview): claim 동시성 — FOR UPDATE SKIP LOCKED 무중복 검증 (Testcontainers)

8 워커 x 8 QUEUED 세션 동시 claim → 정확히 8 claim, 세션 중복 0, 전부 RUNNING.
실 Postgres 필요(H2 skip-locked 미지원), RUN_TESTCONTAINERS=true 게이트.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 10: Full regression + branch wrap-up

**Files:**
- (none — verification only)

- [ ] **Step 1: Run the full unit suite (no container)**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test
  ```
  Expected: `BUILD SUCCESSFUL`. All interview unit tests (`InterviewStatusConverterTest`, `InterviewSessionTest`, `InterviewTurnPlanTest`, `InterviewServiceTest`) pass and no existing test regressed. The container-gated tests (`InterviewClaimConcurrencyTest`, `TaskApiIntegrationTest`) are skipped (env not set) — confirmed by `tests skipped` in the report.

- [ ] **Step 2: Run the full suite with containers (authoritative)**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test
  ```
  Expected: `BUILD SUCCESSFUL`, including the V11 migration applying in a fresh Postgres container and the claim-concurrency test passing. If Docker is unavailable, document that Step 2 must be run in CI and rely on Step 1 + the Task 9 container run.

- [ ] **Step 3: Confirm clean working tree and push the branch**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git status --short && git log --oneline -6 && git push -u origin feat/interview-backend-domain
  ```
  Expected: empty `git status`, six interview commits in the log, branch pushed. (Open the PR per the repo's normal flow; the SDK service and controllers/SSE land in Phase 2.)

---

## Notes for the executing agent

- **Task enum is UNCHANGED.** Do not touch `TaskStatus`/`TaskStatusConverter`/`Task`. All interview state lives in `InterviewStatus`/`interview_session.status`.
- **`@Setter` discipline mirrors `Task`:** only fields the service mutates are `@Setter`; `id`/`requesterId`/`createdAt`/factory-set fields are immutable. Never call `setStatus` outside `InterviewService`.
- **Exception type is reused:** `TaskException` (with `notFound`/`forbidden`/`conflict`/`tooManyRequests`) is the existing project-wide business exception mapped by `GlobalExceptionHandler`. Do not introduce a new exception class.
- **`resolveMcpExtras` is intentionally duplicated** from `TaskService` (not extracted) to keep this plan's diff self-contained and match the existing per-service style; if a later refactor consolidates it, that is out of scope here.
- **Cost type:** `total_cost_usd`/`costUsd` use `BigDecimal` (NUMERIC) — never `double` — to match the `NUMERIC(12,6)` column and avoid rounding drift in accumulated session cost.
- **`register` writes `null → COMPLETED` history** through the existing `TaskStatusHistory.log` + `TaskStatusHistoryRepository`, so the promoted Task appears in the standard task audit trail and the existing admin approve gate (`COMPLETED → APPROVED`) works unchanged.
- **`register` TaskAnalysis mapping is contract-fixed:** `TaskAnalysis.create(taskId, designMarkdown, planJson, null, durationMs)` — `markdown_result` is the **design markdown ONLY** (the existing analysis-result section parser consumes a single markdown doc; do NOT splice in `plan_markdown`), `subtasks_json` is `plan_json`, `claude_log` is `null`. `plan_markdown` stays in `interview_plan` for UI display but is not copied into the Task.
- **Converter is `@Converter(autoApply = false)`** + explicit `@Convert(converter = InterviewStatusConverter.class)` on `InterviewSession.status`. `TaskStatusConverter` is also an `AttributeConverter<…,String>`; if both were `autoApply = true` Hibernate would try to apply them to the same String/enum columns and clash. Keep auto-apply off and convert explicitly.
- **`work_dir` is resume-critical (spike 02):** Claude Code's session store is keyed on the process `cwd` (`~/.claude/projects/<cwd-hash>/`), so a resume MUST run in the identical directory. `InterviewService.claim` assigns the deterministic path `~/netis-maker/interviews/{owner}/{repo}/session-{id}` on the **first** claim, persists it, and returns the stored value on every later resume claim. Plan 03's SDK service uses this exact path as `options.cwd` and is responsible for cloning/preparing the repo there before the first turn and before any resume. Single host / shared filesystem only.
- **`reply_to_seq` is the answer idempotency key:** `submitAnswer` records it on the `user/answer` turn and treats a second answer for the same `reply_to_seq` as a no-op (re-queue is idempotent against double-submits / SSE retries). Question/design/note turns leave it null.
- **REUSE, do not re-Create (cross-plan):** the entities, enum, converter, `V11__interview.sql`, repositories, `InterviewService`, and the five canonical DTOs above are owned by **this** plan. Plan 02 imports `InterviewClaimResponse`/`AnswerRequest`/`WorkerQuestionRequest`/`WorkerPlanRequest`/`CreateInterviewRequest` and calls `InterviewService.{create,claim,recordQuestion,submitAnswer,recordPlan,cancel,expire,fail,register}` — it adds only controllers, `InterviewStreamService` (SSE), and the scheduled stale-recovery/idle-TTL job (which calls `expire`/`fail`). Plan 03 adds only the SDK worker. The SSE `status` event payload is the **English** `InterviewStatus.name()` (e.g. `AWAITING_INPUT`, `PLAN_READY`), never the Korean `dbValue()` — but that mapping lives in plan 02's stream service, not here.

Files authored by this plan (all absolute under `/Users/micthebick/IdeaProjects/netisMaker`):
- `src/main/resources/db/migration/V11__interview.sql`
- `src/main/java/com/hamonsoft/netismaker/entity/{InterviewStatus,InterviewStatusConverter,InterviewSession,InterviewTurn,InterviewPlan}.java`
- `src/main/java/com/hamonsoft/netismaker/repository/{InterviewSessionRepository,InterviewTurnRepository,InterviewPlanRepository}.java`
- `src/main/java/com/hamonsoft/netismaker/dto/{CreateInterviewRequest,AnswerRequest,WorkerQuestionRequest,WorkerPlanRequest,InterviewClaimResponse}.java`
- `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`
- `src/test/java/com/hamonsoft/netismaker/entity/{InterviewStatusConverterTest,InterviewSessionTest,InterviewTurnPlanTest}.java`
- `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`
- `src/test/java/com/hamonsoft/netismaker/repository/InterviewClaimConcurrencyTest.java`
