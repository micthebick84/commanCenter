# 대화형 분석 — Backend API/SSE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:test-driven-development` — every task is RED → GREEN → COMMIT. Write the failing test first, run it, see it fail for the stated reason, then write the minimal implementation, run it, see it pass, commit. Do not skip the RED step. Also load `superpowers:verification-before-completion` before claiming any task done.

> **OWNERSHIP — READ FIRST (LOCKED CONTRACT v2):** Phase 1 (plan `…-01-backend-domain.md`) is the **sole creator** of: the `InterviewSession`/`InterviewTurn`/`InterviewPlan` entities; the `InterviewStatus` enum; `InterviewStatusConverter`; `V11__interview.sql`; the repositories; `InterviewService` (all state transitions — `create`/`claim`/`recordQuestion`/`recordPlan`/`submitAnswer`/`cancel`/`expire`/`fail`/`register`/`heartbeat`); and the **shared DTO records** `CreateInterviewRequest`, `AnswerRequest`, `WorkerQuestionRequest`, `WorkerPlanRequest`, and `InterviewClaimResponse`. **This plan (Phase 2) MUST NOT re-create any of those.** Phase 2 only *creates*: the two controllers (`InterviewController`, `InterviewWorkerController`), `InterviewStreamService`, `InterviewResponse` / `InterviewCreatedResponse` / `RegisterResponse` (Phase-2-only view DTOs), and `InterviewStaleRecoveryJob`. Everything else is **reused** — Task 0 verifies the Phase 1 surface exists before any code is written.

**Goal:** Add the netisMaker backend API surface for *conversational analysis* (대화형 분석) on top of the Phase 1 domain: user-facing REST endpoints (`/api/interviews`) for creating/reading/streaming/answering/registering/cancelling interview sessions, worker-facing REST endpoints (`/worker/interviews`) for the interview-worker pull/claim loop (claim takes `?workerId=` as a **query param**), an in-memory SSE fan-out (`InterviewStreamService`) mirroring `DeployLogStreamService`, and a scheduled stale-recovery + idle-TTL expire job (`InterviewStaleRecoveryJob`) mirroring `StaleTaskRecoveryJob`. The user-facing `register` endpoint delegates to Phase 1's `InterviewService.register`, which promotes the session to a `Task` at `COMPLETED` with a prefilled `TaskAnalysis`. **This plan assumes Phase 1 already exists** (entities, status enum, converter, repositories, `InterviewService` transitions, the shared DTOs above, and `V11__interview.sql`) — it wires the controllers, view DTOs, SSE service, the scheduler, security tests, and integration tests on top of that foundation.

**Architecture:** Two Spring Security filter chains already split `/worker/**` (X-Worker-API-Key → `ROLE_WORKER`) from `/api/**` (OAuth2 JWT → `ROLE_USER`/`ROLE_ADMIN`). We add `InterviewController` (JWT) and `InterviewWorkerController` (API-Key) following the exact patterns of `TaskController` and `WorkerController`. SSE uses an `SseEmitter` registry keyed by sessionId, replay-on-connect from `interview_turn`/`interview_plan`, plus a keepalive ping — identical mechanics to `DeployLogStreamService`. The worker claim path delegates to Phase 1's `InterviewService.claim(workerId)` (which already does `FOR UPDATE SKIP LOCKED`, `ORDER BY last_activity_at ASC`, assigns/persists `work_dir`, returns `InterviewClaimResponse`). Registration delegates to Phase 1's `InterviewService.register(...)` which calls `Task.create(...)` + sets status `COMPLETED` + persists `TaskAnalysis.create(taskId, designMarkdown, planJson, null, durationMs)` (design-only `markdownResult`, `subtasksJson = planJson`, `claudeLog = null`), snapshots `mcps_extra`, and links `interview_session.task_id`, so the existing admin-approval → implement → PR → deploy pipeline takes over unchanged.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Gradle, Spring Data JPA (PostgreSQL `com` schema), Spring Security OAuth2 Resource Server + custom `OncePerRequestFilter`, Flyway, Lombok, JUnit 5 + Spring MockMvc + Testcontainers (PostgreSQL 16). All new server code lives under profile `api`. Korean status labels are stored as `VARCHAR(30)` via Phase 1's `InterviewStatusConverter` (`@Converter(autoApply = false)`, applied with explicit `@Convert` on the entity field). SSE `status` events use the **English** `InterviewStatus.name()` (NOT the Korean `dbValue()`).

---

## File Structure

**Create (Phase-2-only artifacts):**
- `src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java` — session detail view (status, turns, plan) returned by GET/POST user endpoints. **Phase-2 view DTO** (not a shared/worker DTO).
- `src/main/java/com/hamonsoft/netismaker/dto/InterviewCreatedResponse.java` — `{sessionId}` returned by create.
- `src/main/java/com/hamonsoft/netismaker/dto/RegisterResponse.java` — `{taskId}` returned by register.
- `src/main/java/com/hamonsoft/netismaker/dto/PlanReadyEvent.java` — SSE `plan_ready` payload object `{designMarkdown, planMarkdown, planJson}` (serialized to JSON for the event data).
- `src/main/java/com/hamonsoft/netismaker/service/InterviewStreamService.java` — in-memory SSE registry: `question`/`design`/`plan_ready`/`status`/`done` events + replay-on-connect + keepalive ping (mirrors `DeployLogStreamService`). `status` payload = `InterviewStatus.name()` (English enum); `plan_ready` payload = JSON object.
- `src/main/java/com/hamonsoft/netismaker/service/InterviewStaleRecoveryJob.java` — scheduled stale-recovery + idle-TTL expire (mirrors `StaleTaskRecoveryJob`): RUNNING with stale `claimed_at` → `InterviewService.fail` (re-queue/fail); AWAITING_INPUT past idle TTL → `InterviewService.expire`.
- `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java` — user REST: create/get/stream/answer/register/cancel (JWT, ACL).
- `src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java` — worker REST: claim (`?workerId=`)/question/plan/heartbeat/fail (API-Key).
- `src/test/java/com/hamonsoft/netismaker/service/InterviewStreamServiceTest.java` — SSE replay + push + ping + done unit test (asserts `status` event carries English enum name, `plan_ready` carries the JSON object).
- `src/test/java/com/hamonsoft/netismaker/service/InterviewStaleRecoveryJobTest.java` — stale RUNNING → fail, idle AWAITING_INPUT → expire (mirrors `StaleTaskRecoveryJobTest`).
- `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java` — user-side integration tests (ACL, concurrency gate, SSE content-type, register mapping).
- `src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerApiIntegrationTest.java` — worker-side integration tests (claim via `?workerId=`, question→AWAITING_INPUT, answer idempotency, plan→PLAN_READY).
- `src/test/java/com/hamonsoft/netismaker/controller/InterviewAnswerIdempotencyTest.java` — end-to-end duplicate-answer dedupe by `replyToSeq`.
- `src/test/java/com/hamonsoft/netismaker/controller/InterviewSecuritySurfaceTest.java` — JWT/API-Key deny-path guards.

**Reuse from Phase 1 (do NOT create — verified in Task 0):**
- `entity/InterviewStatus.java`, `entity/InterviewStatusConverter.java`
- `entity/InterviewSession.java`, `entity/InterviewTurn.java`, `entity/InterviewPlan.java`
- `repository/InterviewSessionRepository.java`, `repository/InterviewTurnRepository.java`, `repository/InterviewPlanRepository.java`
- `service/InterviewService.java` (all transitions: `create`/`claim`/`recordQuestion`/`recordPlan`/`submitAnswer`/`cancel`/`expire`/`fail`/`heartbeat`/`register`)
- `dto/CreateInterviewRequest.java`, `dto/AnswerRequest.java`, `dto/WorkerQuestionRequest.java`, `dto/WorkerPlanRequest.java`, `dto/InterviewClaimResponse.java`
- `db/migration/V11__interview.sql` (includes `work_dir` and `interview_turn.reply_to_seq` from the start — **no V12**)

**Modify:**
- `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` — only if Task 0 finds a thin gap the controllers/scheduler require (e.g. a read-only `getResponse(id, viewerId, isAdmin)` view helper or a `getForView` ACL helper not already present). Every state transition already exists in Phase 1; do **not** redefine them.
- `src/main/resources/application-api.yml` — add `app.interview.*` config defaults (concurrency limit, SSE ping interval, stale/idle thresholds).
- `src/test/resources/init-test-schema.sql` — only if Flyway `V11` is not auto-applied in the Testcontainers harness (see Task 0); otherwise untouched.

---

### Task 0: Verify-and-reuse the Phase 1 baseline (NO re-creation)

> **This task creates no production code.** It confirms every Phase 1 artifact this plan depends on exists with the exact shape the contract requires, and records the precise method/field/JSON names the controllers and scheduler will call. If any artifact is missing or mis-shaped, **STOP** — Phase 1 must be completed/corrected first (do not re-create it here).

**Files:**
- Read: `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatus.java` (Phase 1)
- Read: `src/main/java/com/hamonsoft/netismaker/entity/InterviewStatusConverter.java` (Phase 1)
- Read: `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java` (Phase 1)
- Read: `src/main/java/com/hamonsoft/netismaker/entity/InterviewTurn.java` (Phase 1)
- Read: `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java`, `InterviewTurnRepository.java`, `InterviewPlanRepository.java` (Phase 1)
- Read: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (Phase 1)
- Read: `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java`, `CreateInterviewRequest.java`, `AnswerRequest.java`, `WorkerQuestionRequest.java`, `WorkerPlanRequest.java` (Phase 1)
- Read: `src/main/resources/db/migration/V11__interview.sql` (Phase 1)
- Modify (conditional): `src/test/resources/init-test-schema.sql`

- [ ] **Step 1: Confirm the Phase 1 baseline compiles.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew compileJava compileTestJava -q
  ```
  Expect: `BUILD SUCCESSFUL`. If it fails because `InterviewService`/entities/DTOs are missing, **STOP** — Phase 1 is not done; do not proceed and do not re-create them here.

- [ ] **Step 2: Verify the shared DTOs exist with the contract shapes.** Read each and confirm:
  - `CreateInterviewRequest{githubRepo, githubBranch, title, description, mcpCatalogIds}`
  - `AnswerRequest{answer, replyToSeq}`
  - `WorkerQuestionRequest{content, claudeSessionId, kind, costUsd}`
  - `WorkerPlanRequest{designMarkdown, planMarkdown, planJson, costUsd, durationMs}`
  - `InterviewClaimResponse` — the claim payload (record). Record its **exact** field names + JSON keys the worker controller returns: `sessionId(long), githubRepo, githubBranch, title, description, claudeSessionId(nullable), currentPhase, workDir, lastAnswer(nullable), replyToSeq(nullable Integer), mcpsExtra, turns(list)`. If Phase 1's `InterviewClaimResponse` differs (e.g. uses `id`/`lastUserAnswer` or lacks `workDir`/`replyToSeq`), that is a Phase 1 bug per the LOCKED CONTRACT — flag it and have Phase 1 fix it; **do not** create a parallel DTO in Phase 2.

- [ ] **Step 3: Verify the `InterviewStatus` enum + converter.** Confirm `InterviewStatus` has exactly the 8 values `QUEUED/RUNNING/AWAITING_INPUT/PLAN_READY/REGISTERED/CANCELLED/EXPIRED/FAILED` with `dbValue()`/`fromDb()` and Korean labels (`인터뷰대기`/`인터뷰중`/`입력대기`/`플랜완료`/`등록됨`/`취소됨`/`만료됨`/`인터뷰실패`). Confirm `InterviewStatusConverter` is annotated `@Converter(autoApply = false)` and that `InterviewSession.status` carries an explicit `@Convert(converter = InterviewStatusConverter.class)` (so it never clashes with `TaskStatusConverter`). The SSE service in Task 3 uses `InterviewStatus.name()` (English) for `status` events — confirm `name()` yields `AWAITING_INPUT`, `PLAN_READY`, etc.

- [ ] **Step 4: Record the `InterviewService` public surface the controllers + scheduler will call.** Confirm these signatures exist (add only a thin read-only view helper if genuinely missing — never redefine a transition):
  ```text
  InterviewSession create(CreateInterviewRequest req, String requesterId)              // QUEUED + concurrency gate
  Optional<InterviewClaimResponse> claim(String workerId)                              // SKIP LOCKED, assigns work_dir
  InterviewTurn recordQuestion(Long sessionId, String workerId, WorkerQuestionRequest) // RUNNING→AWAITING_INPUT, blank-guards claude_session_id
  InterviewPlan recordPlan(Long sessionId, String workerId, WorkerPlanRequest)         // RUNNING→PLAN_READY
  void heartbeat(Long sessionId, String workerId)                                      // last_activity_at touch
  InterviewSession fail(Long sessionId, String actor, String reason)                   // *→FAILED (terminal-guarded)
  InterviewSession submitAnswer(Long id, String actorId, boolean isAdmin, AnswerRequest) // AWAITING_INPUT→QUEUED, idempotent by replyToSeq
  InterviewSession cancel(Long id, String actorId, boolean isAdmin)                     // →CANCELLED
  InterviewSession expire(Long sessionId, String reason)                               // AWAITING_INPUT→EXPIRED
  Long register(Long id, String actorId, boolean isAdmin)                              // PLAN_READY→REGISTERED + Task(COMPLETED)+TaskAnalysis
  ```
  Also record the `InterviewSession` getters the view DTO needs: `getId`, `getRequesterId`, `getGithubRepo`, `getGithubBranch`, `getTitle`, `getDescription`, `getStatus`, `getCurrentPhase`, `getClaudeSessionId`, `getWorkDir`, `getTaskId`, `getMcpsExtra`, `getClaimedAt`, `getWorkerId`, `getCreatedAt`, `getUpdatedAt`, `getLastActivityAt`; and `InterviewTurn` getters `getSeq`, `getRole`, `getKind`, `getContent`, `getReplyToSeq`, `getCreatedAt`. Confirm the Phase 1 repository read method name (`InterviewTurnRepository.findBySessionIdOrderBySeqAsc(Long)`) and `InterviewPlanRepository.findById(Long)`.

- [ ] **Step 5: Confirm the contract details Phase 1 must already satisfy (verification only — do not edit Phase 1 here):**
  - `register` builds `TaskAnalysis` via `TaskAnalysis.create(taskId, designMarkdown, planJson, null, durationMs)` — `markdownResult` is the **design markdown only** (no composite), `claudeLog = null`, `subtasksJson = planJson`. If Phase 1 currently composes design+plan or passes a non-null `claudeLog`, flag it for the Phase 1 fix (contract-locked); Phase 2 does not re-implement `register`.
  - `recordQuestion` **blank-guards** `claude_session_id`: it sets it only when `req.claudeSessionId()` is non-null AND non-blank (never stores `""`).
  - `claim` assigns + persists a deterministic `work_dir` on first claim (e.g. `~/netis-maker/interviews/{owner}/{repo}/session-{id}`) and returns the stored value on every resume claim.

- [ ] **Step 6: Ensure the Testcontainers schema has the interview tables (conditional).** Check whether the integration-test harness applies Flyway `V11`. If `init-test-schema.sql` pre-creates only `com."user"`/`com.task` and Flyway applies the rest, **no edit is needed**. If Flyway is disabled in tests, append the three tables to `init-test-schema.sql` after the `com."user"`/`com.task` seed — mirroring V11 exactly, **including `work_dir` and `reply_to_seq`** (NO separate V12):
  ```sql
  -- 대화형 분석 (Phase 1 V11 미러). Flyway 미적용 환경 대비 최소 스키마.
  CREATE TABLE IF NOT EXISTS com.interview_session (
      id                BIGSERIAL PRIMARY KEY,
      requester_id      VARCHAR(20)  NOT NULL REFERENCES com."user"(user_id),
      github_repo       VARCHAR(255) NOT NULL,
      github_branch     VARCHAR(255) NOT NULL DEFAULT 'main',
      title             VARCHAR(500) NOT NULL,
      description       TEXT         NOT NULL,
      status            VARCHAR(30)  NOT NULL DEFAULT '인터뷰대기',
      claude_session_id VARCHAR(100),
      work_dir          VARCHAR(500),
      worker_id         VARCHAR(50),
      claimed_at        TIMESTAMPTZ,
      commit_sha        VARCHAR(40),
      current_phase     VARCHAR(20),
      total_cost_usd    NUMERIC(12,6) NOT NULL DEFAULT 0,
      mcps_extra        JSONB        NOT NULL DEFAULT '[]'::jsonb,
      task_id           BIGINT       REFERENCES com.task(id),
      created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
      updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
      last_activity_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
  );
  CREATE TABLE IF NOT EXISTS com.interview_turn (
      id           BIGSERIAL PRIMARY KEY,
      session_id   BIGINT      NOT NULL REFERENCES com.interview_session(id) ON DELETE CASCADE,
      seq          INT         NOT NULL,
      role         VARCHAR(20) NOT NULL,
      kind         VARCHAR(20) NOT NULL,
      content      TEXT        NOT NULL,
      reply_to_seq INT,
      created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE INDEX IF NOT EXISTS idx_interview_turn ON com.interview_turn(session_id, seq);
  CREATE TABLE IF NOT EXISTS com.interview_plan (
      session_id      BIGINT PRIMARY KEY REFERENCES com.interview_session(id) ON DELETE CASCADE,
      design_markdown TEXT,
      plan_markdown   TEXT,
      plan_json       JSONB NOT NULL DEFAULT '[]'::jsonb,
      duration_ms     BIGINT,
      total_cost_usd  NUMERIC(12,6),
      completed_at    TIMESTAMPTZ
  );
  ```

- [ ] **Step 7: Commit the baseline-verification edit (only if Step 6 added one).**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/test/resources/init-test-schema.sql && git commit -m "test: interview 테이블 테스트 스키마 시드 (V11 미러, work_dir/reply_to_seq 포함)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```
  (If no edit was needed, skip this commit and note that Flyway V11 applies in tests.)

---

### Task 1: Phase-2 view DTOs (`InterviewResponse`, `InterviewCreatedResponse`, `RegisterResponse`, `PlanReadyEvent`)

> These are the **only** Phase-2 DTOs. The request DTOs (`CreateInterviewRequest`, `AnswerRequest`, `WorkerQuestionRequest`, `WorkerPlanRequest`) and the worker claim payload (`InterviewClaimResponse`) are **Phase 1's** — do NOT create them here.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/InterviewCreatedResponse.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/RegisterResponse.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/PlanReadyEvent.java`
- Create: `src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java`

- [ ] **Step 1: Write the failing factory test.** Create `InterviewResponseTest.java`:
  ```java
  package com.hamonsoft.netismaker.dto;

  import com.hamonsoft.netismaker.entity.InterviewTurn;
  import org.junit.jupiter.api.Test;

  import java.util.List;

  import static org.assertj.core.api.Assertions.assertThat;

  class InterviewResponseTest {

      @Test
      void plan_ready_event_carries_all_three_fields() {
          var ev = new PlanReadyEvent("# 설계", "# 플랜", "[{\"title\":\"A\"}]");
          assertThat(ev.designMarkdown()).isEqualTo("# 설계");
          assertThat(ev.planMarkdown()).isEqualTo("# 플랜");
          assertThat(ev.planJson()).isEqualTo("[{\"title\":\"A\"}]");
      }

      @Test
      void turn_view_maps_seq_role_kind_content() {
          // Phase 1 팩토리 시그니처: InterviewTurn.of(sessionId, seq, role, kind, content, replyToSeq)
          InterviewTurn t = InterviewTurn.of(1L, 2, "assistant", "question", "어떤 인증?", null);
          var tv = new InterviewResponse.TurnView(
                  t.getSeq(), t.getRole(), t.getKind(), t.getContent(), t.getReplyToSeq(), t.getCreatedAt());
          assertThat(tv.seq()).isEqualTo(2);
          assertThat(tv.role()).isEqualTo("assistant");
          assertThat(tv.kind()).isEqualTo("question");
      }
  }
  ```

- [ ] **Step 2: Run & see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.dto.InterviewResponseTest' -q
  ```
  Expect: compile failure `cannot find symbol: class PlanReadyEvent` / `class InterviewResponse`.

- [ ] **Step 3: Create `InterviewCreatedResponse` and `RegisterResponse`:**
  ```java
  package com.hamonsoft.netismaker.dto;

  public record InterviewCreatedResponse(Long sessionId) {}
  ```
  ```java
  package com.hamonsoft.netismaker.dto;

  public record RegisterResponse(Long taskId) {}
  ```

- [ ] **Step 4: Create `PlanReadyEvent`** — the SSE `plan_ready` payload object (serialized to JSON for the event data):
  ```java
  package com.hamonsoft.netismaker.dto;

  /**
   * SSE event:plan_ready 페이로드 객체 (LOCKED CONTRACT v2).
   * bare string이 아니라 {designMarkdown, planMarkdown, planJson} JSON 객체로 전달.
   * 프론트가 우측 패널(설계 + 플랜 + 잘게 쪼갠 태스크)을 한 번에 채운다.
   */
  public record PlanReadyEvent(
          String designMarkdown,
          String planMarkdown,
          String planJson
  ) {}
  ```

- [ ] **Step 5: Create `InterviewResponse`** (session detail view; mirrors `TaskResponse.of` mapping style). Reads `InterviewSession` + its turns + optional `InterviewPlan`. Getter names match the Phase 1 entities recorded in Task 0 Step 4:
  ```java
  package com.hamonsoft.netismaker.dto;

  import com.hamonsoft.netismaker.entity.InterviewPlan;
  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewTurn;

  import java.time.OffsetDateTime;
  import java.util.List;

  /** 인터뷰 세션 상세 뷰 (status + turns + plan). status는 한글 dbValue로 노출(프론트 표시용). */
  public record InterviewResponse(
          Long id,
          String githubRepo,
          String githubBranch,
          String title,
          String description,
          String status,        // 한글 dbValue (사용자 표시). SSE status 이벤트는 영문 enum name 사용.
          String currentPhase,
          String workDir,
          Long taskId,
          List<TurnView> turns,
          PlanView plan,
          OffsetDateTime createdAt,
          OffsetDateTime updatedAt
  ) {
      public record TurnView(int seq, String role, String kind, String content,
                             Integer replyToSeq, OffsetDateTime createdAt) {}

      public record PlanView(String designMarkdown, String planMarkdown, String planJson,
                             Long durationMs, OffsetDateTime completedAt) {}

      public static InterviewResponse of(InterviewSession s, List<InterviewTurn> turns, InterviewPlan plan) {
          List<TurnView> tvs = turns.stream()
                  .map(t -> new TurnView(t.getSeq(), t.getRole(), t.getKind(), t.getContent(),
                          t.getReplyToSeq(), t.getCreatedAt()))
                  .toList();
          PlanView pv = plan == null ? null : new PlanView(
                  plan.getDesignMarkdown(), plan.getPlanMarkdown(), plan.getPlanJson(),
                  plan.getDurationMs(), plan.getCompletedAt());
          return new InterviewResponse(
                  s.getId(), s.getGithubRepo(), s.getGithubBranch(), s.getTitle(), s.getDescription(),
                  s.getStatus().dbValue(), s.getCurrentPhase(), s.getWorkDir(), s.getTaskId(),
                  tvs, pv, s.getCreatedAt(), s.getUpdatedAt());
      }
  }
  ```

- [ ] **Step 6: Run & see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.dto.InterviewResponseTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 7: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java src/main/java/com/hamonsoft/netismaker/dto/InterviewCreatedResponse.java src/main/java/com/hamonsoft/netismaker/dto/RegisterResponse.java src/main/java/com/hamonsoft/netismaker/dto/PlanReadyEvent.java src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java && git commit -m "feat(interview): Phase-2 view DTO (InterviewResponse/Created/Register/PlanReadyEvent)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 2: `InterviewStreamService` — SSE fan-out with replay + keepalive ping

> Mirrors `DeployLogStreamService` exactly: in-memory `Map<Long, List<SseEmitter>>` registry, replay-on-connect (here from `interview_turn` + `interview_plan`), live push, and `done`/complete on terminal. Adds a keepalive `ping` emitted on subscribe and a `pushStatus`/`pushQuestion`/`pushDesign`/`pushPlanReady`/`finish` API matching the five SSE event names. **LOCKED CONTRACT v2:** the `status` event payload is the **English** `InterviewStatus.name()` (e.g. `AWAITING_INPUT`, `PLAN_READY`), NOT the Korean `dbValue`; the `plan_ready` event payload is a **JSON object** `{designMarkdown, planMarkdown, planJson}` (serialized via `ObjectMapper`), NOT a bare string.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/InterviewStreamService.java`
- Create: `src/test/java/com/hamonsoft/netismaker/service/InterviewStreamServiceTest.java`

- [ ] **Step 1: Write the failing unit test.** Create `InterviewStreamServiceTest.java` (uses a fake repo so no DB; verifies replay, live push, ping, done, and that `status`/`plan_ready` carry the contract payloads). The `InterviewTurnRepository.findBySessionIdOrderBySeqAsc` method + the `InterviewTurn.of(sessionId, seq, role, kind, content, replyToSeq)` factory are Phase 1's (confirmed in Task 0):
  ```java
  package com.hamonsoft.netismaker.service;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.entity.InterviewTurn;
  import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
  import org.junit.jupiter.api.Test;
  import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

  import java.util.List;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.when;

  class InterviewStreamServiceTest {

      private final InterviewTurnRepository turnRepo = mock(InterviewTurnRepository.class);
      private final ObjectMapper json = new ObjectMapper();

      /** SseEmitter.send를 가로채 이벤트 이름을 수집하기는 어려우므로,
       *  서비스가 NPE 없이 동작하고 done 후 레지스트리에서 제거되는지를 검증한다. */
      @Test
      void subscribe_replays_existing_turns_then_registers_live() {
          when(turnRepo.findBySessionIdOrderBySeqAsc(42L))
                  .thenReturn(List.of(turn(1, "assistant", "question", "어떤 인증?")));
          var svc = new InterviewStreamService(turnRepo, json);

          SseEmitter e = svc.subscribe(42L);
          assertThat(e).isNotNull();
          // 라이브 푸시가 등록된 emitter에 도달 — 예외 없이 호출되면 통과
          svc.pushQuestion(42L, "다음 질문?");
          svc.pushStatus(42L, InterviewStatus.AWAITING_INPUT);          // 영문 enum name 전달
          svc.pushPlanReady(42L, "# 설계", "# 플랜", "[]");              // JSON 객체 전달
      }

      @Test
      void finish_sends_done_and_clears_registry() {
          when(turnRepo.findBySessionIdOrderBySeqAsc(99L)).thenReturn(List.of());
          var svc = new InterviewStreamService(turnRepo, json);
          svc.subscribe(99L);
          svc.finish(99L);
          // finish 후 두 번째 push는 구독자 없음 — 예외 없이 no-op
          svc.pushStatus(99L, InterviewStatus.REGISTERED);
          assertThat(svc.subscriberCount(99L)).isZero();
      }

      private static InterviewTurn turn(int seq, String role, String kind, String content) {
          return InterviewTurn.of(1L, seq, role, kind, content, null); // Phase 1 팩토리 시그니처
      }
  }
  ```

- [ ] **Step 2: Run & see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewStreamServiceTest' -q
  ```
  Expect: compile failure `cannot find symbol: class InterviewStreamService`.

- [ ] **Step 3: Create the service.** Create `InterviewStreamService.java`:
  ```java
  package com.hamonsoft.netismaker.service;

  import com.fasterxml.jackson.core.JsonProcessingException;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.hamonsoft.netismaker.dto.PlanReadyEvent;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.entity.InterviewTurn;
  import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.context.annotation.Profile;
  import org.springframework.scheduling.annotation.Scheduled;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;
  import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

  import java.io.IOException;
  import java.util.List;
  import java.util.Map;
  import java.util.concurrent.ConcurrentHashMap;
  import java.util.concurrent.CopyOnWriteArrayList;

  /**
   * 인터뷰 SSE 스트리밍 (api 프로파일). DeployLogStreamService 미러.
   *
   *  이벤트: question | design | plan_ready | status | done
   *  - subscribe: 접속 시 keepalive ping + 기존 turn replay 후 live 구독.
   *  - pushQuestion/pushDesign/pushPlanReady/pushStatus: 라이브 fan-out.
   *  - finish: done 이벤트 + emitter complete (세션 terminal 시).
   *
   *  LOCKED CONTRACT v2:
   *    status 페이로드 = InterviewStatus.name() (영문 enum, 예 "AWAITING_INPUT") — 한글 dbValue 아님.
   *    plan_ready 페이로드 = {designMarkdown, planMarkdown, planJson} JSON 객체 — bare string 아님.
   *
   *  api 단일 인스턴스 전제 — emitter 레지스트리 in-memory.
   */
  @Service
  @Profile("api")
  @Slf4j
  public class InterviewStreamService {

      private final InterviewTurnRepository turnRepo;
      private final ObjectMapper json;
      private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

      public InterviewStreamService(InterviewTurnRepository turnRepo, ObjectMapper json) {
          this.turnRepo = turnRepo;
          this.json = json;
      }

      @Transactional(readOnly = true)
      public SseEmitter subscribe(Long sessionId) {
          SseEmitter emitter = new SseEmitter(0L); // 무제한 타임아웃; 종료는 finish가 close
          try {
              // 0) keepalive ping — 프록시/브라우저가 연결을 살아있게 유지
              emitter.send(SseEmitter.event().comment("ping"));
              // 1) 지금까지의 assistant turn replay (재연결 시 누락 복구)
              for (InterviewTurn t : turnRepo.findBySessionIdOrderBySeqAsc(sessionId)) {
                  if ("user".equals(t.getRole())) continue; // 사용자 답변은 클라가 이미 가짐
                  String event = "design".equals(t.getKind()) ? "design" : "question";
                  emitter.send(SseEmitter.event().name(event)
                          .data(t.getContent() == null ? "" : t.getContent()));
              }
          } catch (IOException e) {
              emitter.completeWithError(e);
              return emitter;
          }
          // 2) live 구독 등록
          emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
          emitter.onCompletion(() -> remove(sessionId, emitter));
          emitter.onTimeout(() -> remove(sessionId, emitter));
          emitter.onError(ex -> remove(sessionId, emitter));
          return emitter;
      }

      public void pushQuestion(Long sessionId, String content) { send(sessionId, "question", content); }
      public void pushDesign(Long sessionId, String content)   { send(sessionId, "design", content); }

      /** status 페이로드 = 영문 enum name (예 "AWAITING_INPUT"). 한글 dbValue 절대 아님. */
      public void pushStatus(Long sessionId, InterviewStatus status) {
          send(sessionId, "status", status.name());
      }

      /** plan_ready 페이로드 = {designMarkdown, planMarkdown, planJson} JSON 객체. */
      public void pushPlanReady(Long sessionId, String designMarkdown, String planMarkdown, String planJson) {
          try {
              String payload = json.writeValueAsString(
                      new PlanReadyEvent(designMarkdown, planMarkdown, planJson));
              send(sessionId, "plan_ready", payload);
          } catch (JsonProcessingException e) {
              log.error("plan_ready 직렬화 실패 session={}", sessionId, e);
          }
      }

      /** 키프얼라이브 핑 — 스케줄러가 주기적으로 호출 (idle 연결 유지). */
      public void ping(Long sessionId) {
          List<SseEmitter> subs = emitters.get(sessionId);
          if (subs == null) return;
          for (SseEmitter e : subs) {
              try { e.send(SseEmitter.event().comment("ping")); }
              catch (Exception ex) { remove(sessionId, e); }
          }
      }

      public void finish(Long sessionId) {
          List<SseEmitter> subs = emitters.remove(sessionId);
          if (subs != null) {
              for (SseEmitter e : subs) {
                  try {
                      e.send(SseEmitter.event().name("done").data("end"));
                      e.complete();
                  } catch (Exception ignore) { /* 이미 닫힘 */ }
              }
          }
      }

      public int subscriberCount(Long sessionId) {
          List<SseEmitter> subs = emitters.get(sessionId);
          return subs == null ? 0 : subs.size();
      }

      /** 30초마다 모든 활성 세션에 핑 — proxy idle 타임아웃/EventSource 끊김 방지. */
      @Scheduled(fixedRateString = "${app.interview.sse-ping-interval-ms:30000}")
      public void keepAlive() {
          for (Long sessionId : emitters.keySet()) ping(sessionId);
      }

      private void send(Long sessionId, String event, String data) {
          List<SseEmitter> subs = emitters.get(sessionId);
          if (subs == null) return;
          for (SseEmitter e : subs) {
              try { e.send(SseEmitter.event().name(event).data(data == null ? "" : data)); }
              catch (Exception ex) { remove(sessionId, e); }
          }
      }

      private void remove(Long sessionId, SseEmitter e) {
          List<SseEmitter> subs = emitters.get(sessionId);
          if (subs != null) subs.remove(e);
      }
  }
  ```
  > `InterviewTurnRepository.findBySessionIdOrderBySeqAsc(Long)` is the Phase 1 repository method (mirrors `TaskDeployLogChunkRepository.findByTaskIdOrderBySeqAsc`, confirmed in Task 0). `@EnableScheduling` is already active (`StaleTaskRecoveryJob` uses `@Scheduled`), so `keepAlive` fires without extra config.

- [ ] **Step 4: Run & see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewStreamServiceTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/service/InterviewStreamService.java src/test/java/com/hamonsoft/netismaker/service/InterviewStreamServiceTest.java && git commit -m "feat(interview): InterviewStreamService SSE fan-out (replay + keepalive; status=영문 enum, plan_ready=JSON 객체)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 3: `InterviewController` — user REST (create/get/stream/answer/register/cancel)

> Mirrors `TaskController`: `@RestController @RequestMapping("/api/interviews") @Profile("api")`, `AuthContext.requireUserId(auth)` + `AuthContext.isAdmin(auth)`, SSE endpoint returns `SseEmitter` with `produces = TEXT_EVENT_STREAM_VALUE` after an ACL check, `@RequestBody @Valid`. The SSE `?access_token=` query param already works via the existing `bearerTokenResolver()` in `SecurityConfig` (no security change needed — `/api/interviews/**` is matched by `apiFilterChain`). All state transitions are **Phase 1's** `InterviewService` methods; the controller only orchestrates them + pushes SSE.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java`
- Modify (conditional): `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (add only a read-only `getResponse(id, viewerId, isAdmin)` / `getForView(id, viewerId, isAdmin)` view helper if Phase 1 didn't expose one — see Task 0 Step 4; do NOT redefine any transition)
- Create: `src/main/java/com/hamonsoft/netismaker/dto/...` — none (DTOs from Task 1 / Phase 1)
- Modify: `src/main/resources/application-api.yml`
- Create: `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test** (gated on `RUN_TESTCONTAINERS`, mirrors `TaskApiIntegrationTest`). Create `InterviewApiIntegrationTest.java`:
  ```java
  package com.hamonsoft.netismaker.controller;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.hamonsoft.netismaker.TestcontainersConfig;
  import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
  import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.http.HttpHeaders;
  import org.springframework.security.core.authority.SimpleGrantedAuthority;
  import org.springframework.test.context.ContextConfiguration;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.request.RequestPostProcessor;

  import java.util.List;

  import static org.hamcrest.Matchers.containsString;
  import static org.springframework.http.MediaType.APPLICATION_JSON;
  import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
  @SpringBootTest
  @AutoConfigureMockMvc
  @ContextConfiguration(initializers = TestcontainersConfig.class)
  class InterviewApiIntegrationTest {

      @Autowired private MockMvc mvc;
      @Autowired private ObjectMapper json;
      @Autowired private InterviewSessionRepository sessionRepo;

      @BeforeEach void clean() { sessionRepo.deleteAll(); }

      private static RequestPostProcessor userJwt(String userId) {
          return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                  .authorities(new SimpleGrantedAuthority("ROLE_USER"));
      }
      private static RequestPostProcessor adminJwt(String userId) {
          return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                  .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
      }
      private String body(String repo, String title, String desc) throws Exception {
          return json.writeValueAsString(new CreateInterviewRequest(repo, "main", title, desc, List.of()));
      }

      @Test
      void POST_interviews_creates_QUEUED_session() throws Exception {
          mvc.perform(post("/api/interviews").with(userJwt("user1"))
                          .contentType(APPLICATION_JSON).content(body("hamonsoft/netis-backend", "RBAC", "권한 추가")))
                  .andExpect(status().isCreated())
                  .andExpect(header().exists("Location"))
                  .andExpect(jsonPath("$.sessionId").exists());
      }

      @Test
      void POST_interviews_invalid_repo_returns_400() throws Exception {
          mvc.perform(post("/api/interviews").with(userJwt("user1"))
                          .contentType(APPLICATION_JSON).content(body("bad-repo", "제목", "내용")))
                  .andExpect(status().isBadRequest())
                  .andExpect(jsonPath("$.fields.githubRepo").exists());
      }

      @Test
      void GET_interview_other_user_returns_403() throws Exception {
          String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                          .contentType(APPLICATION_JSON).content(body("a/b", "제목", "내용")))
                  .andReturn().getResponse().getHeader("Location");
          mvc.perform(get(loc).with(userJwt("user2"))).andExpect(status().isForbidden());
      }

      @Test
      void GET_interview_admin_can_see_anyone() throws Exception {
          String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                          .contentType(APPLICATION_JSON).content(body("a/b", "제목", "내용")))
                  .andReturn().getResponse().getHeader("Location");
          mvc.perform(get(loc).with(adminJwt("admin1")))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.status").value("인터뷰대기"));
      }

      @Test
      void GET_stream_owner_returns_text_event_stream() throws Exception {
          String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                          .contentType(APPLICATION_JSON).content(body("a/b", "제목", "내용")))
                  .andReturn().getResponse().getHeader("Location");
          mvc.perform(get(loc + "/stream").with(userJwt("user1")))
                  .andExpect(status().isOk())
                  .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/event-stream")));
      }

      @Test
      void GET_stream_non_owner_returns_403() throws Exception {
          String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                          .contentType(APPLICATION_JSON).content(body("a/b", "제목", "내용")))
                  .andReturn().getResponse().getHeader("Location");
          mvc.perform(get(loc + "/stream").with(userJwt("user2"))).andExpect(status().isForbidden());
      }

      @Test
      void POST_register_not_plan_ready_returns_409() throws Exception {
          String loc = mvc.perform(post("/api/interviews").with(userJwt("user1"))
                          .contentType(APPLICATION_JSON).content(body("a/b", "제목", "내용")))
                  .andReturn().getResponse().getHeader("Location");
          mvc.perform(post(loc + "/register").with(userJwt("user1"))).andExpect(status().isConflict());
      }
  }
  ```

- [ ] **Step 2: Run & see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.InterviewApiIntegrationTest' -q
  ```
  Expect: compile failure `cannot find symbol: class InterviewController`.

- [ ] **Step 3: Ensure a read-only view helper exists on `InterviewService` (only if Phase 1 lacks it).** The transitions (`create`, `submitAnswer`, `cancel`, `register`) are already in Phase 1 — do NOT redefine them. The controller needs a read path that enforces ACL and assembles the `InterviewResponse`. If Phase 1 already exposes `getResponse(id, viewerId, isAdmin)` / `getForView(...)`, use it. Otherwise add these thin read-only methods (they create no new transition):
  ```java
  @Transactional(readOnly = true)
  public InterviewSession getForView(Long id, String viewerId, boolean isAdmin) {
      InterviewSession s = sessionRepo.findById(id).orElseThrow(TaskException::notFound);
      if (!isAdmin && !s.getRequesterId().equals(viewerId)) throw TaskException.forbidden();
      return s;
  }

  @Transactional(readOnly = true)
  public InterviewResponse getResponse(Long id, String viewerId, boolean isAdmin) {
      InterviewSession s = getForView(id, viewerId, isAdmin);
      return InterviewResponse.of(s,
              turnRepo.findBySessionIdOrderBySeqAsc(id),
              planRepo.findById(id).orElse(null));
  }
  ```
  > `sessionRepo`/`turnRepo`/`planRepo` are the Phase 1 fields already injected into `InterviewService`. `TaskException.notFound()`/`forbidden()` are the existing helpers used by `TaskService`.

- [ ] **Step 4: Create the controller.** Create `InterviewController.java`:
  ```java
  package com.hamonsoft.netismaker.controller;

  import com.hamonsoft.netismaker.dto.AnswerRequest;
  import com.hamonsoft.netismaker.dto.CreateInterviewRequest;
  import com.hamonsoft.netismaker.dto.InterviewCreatedResponse;
  import com.hamonsoft.netismaker.dto.InterviewResponse;
  import com.hamonsoft.netismaker.dto.RegisterResponse;
  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.service.InterviewService;
  import com.hamonsoft.netismaker.service.InterviewStreamService;
  import jakarta.validation.Valid;
  import org.springframework.context.annotation.Profile;
  import org.springframework.http.MediaType;
  import org.springframework.http.ResponseEntity;
  import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
  import org.springframework.web.bind.annotation.*;
  import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

  import java.net.URI;

  /**
   * 대화형 분석 사용자 API (JWT, ROLE_USER/ADMIN). DESIGN §8 표면 그대로.
   * ACL: 소유자 또는 관리자만 조회/스트림/답변/등록/취소.
   * 동시성 게이트(요청자별 active 한도)는 Phase 1 InterviewService.create에서 검증.
   * 모든 상태 전이는 Phase 1 InterviewService 메서드 위임 — 컨트롤러는 SSE push만 추가.
   */
  @RestController
  @RequestMapping("/api/interviews")
  @Profile("api")
  public class InterviewController {

      private final InterviewService interviewService;
      private final InterviewStreamService interviewStream;

      public InterviewController(InterviewService interviewService, InterviewStreamService interviewStream) {
          this.interviewService = interviewService;
          this.interviewStream = interviewStream;
      }

      @PostMapping
      public ResponseEntity<InterviewCreatedResponse> create(@RequestBody @Valid CreateInterviewRequest req,
                                                             JwtAuthenticationToken auth) {
          String userId = AuthContext.requireUserId(auth);
          InterviewSession s = interviewService.create(req, userId);
          return ResponseEntity.created(URI.create("/api/interviews/" + s.getId()))
                  .body(new InterviewCreatedResponse(s.getId()));
      }

      @GetMapping("/{id}")
      public InterviewResponse get(@PathVariable Long id, JwtAuthenticationToken auth) {
          String userId = AuthContext.requireUserId(auth);
          return interviewService.getResponse(id, userId, AuthContext.isAdmin(auth));
      }

      @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
      public SseEmitter stream(@PathVariable Long id, JwtAuthenticationToken auth) {
          String userId = AuthContext.requireUserId(auth);
          interviewService.getForView(id, userId, AuthContext.isAdmin(auth)); // ACL 검증 (없으면 예외)
          return interviewStream.subscribe(id);
      }

      @PostMapping("/{id}/answer")
      public InterviewResponse answer(@PathVariable Long id, @RequestBody @Valid AnswerRequest req,
                                      JwtAuthenticationToken auth) {
          String userId = AuthContext.requireUserId(auth);
          boolean isAdmin = AuthContext.isAdmin(auth);
          interviewService.submitAnswer(id, userId, isAdmin, req);
          interviewStream.pushStatus(id, InterviewStatus.QUEUED); // 영문 enum name
          return interviewService.getResponse(id, userId, isAdmin);
      }

      @PostMapping("/{id}/register")
      public RegisterResponse register(@PathVariable Long id, JwtAuthenticationToken auth) {
          String userId = AuthContext.requireUserId(auth);
          Long taskId = interviewService.register(id, userId, AuthContext.isAdmin(auth));
          interviewStream.pushStatus(id, InterviewStatus.REGISTERED);
          interviewStream.finish(id); // terminal → done 이벤트
          return new RegisterResponse(taskId);
      }

      @PostMapping("/{id}/cancel")
      public InterviewResponse cancel(@PathVariable Long id, JwtAuthenticationToken auth) {
          String userId = AuthContext.requireUserId(auth);
          boolean isAdmin = AuthContext.isAdmin(auth);
          interviewService.cancel(id, userId, isAdmin);
          interviewStream.pushStatus(id, InterviewStatus.CANCELLED);
          interviewStream.finish(id); // terminal → done 이벤트
          return interviewService.getResponse(id, userId, isAdmin);
      }
  }
  ```
  > `interviewService.register` is **Phase 1's** full implementation (PLAN_READY → REGISTERED + Task(COMPLETED) + `TaskAnalysis.create(taskId, designMarkdown, planJson, null, durationMs)` + mcps snapshot + `task_id` link). The 409 test in Step 1 passes because Phase 1's `register` already guards non-`PLAN_READY` with `TaskException.conflict`. Do NOT re-implement `register` here.

- [ ] **Step 5: Add the `app.interview.*` config defaults.** In `src/main/resources/application-api.yml` (or wherever `app.task.*` lives — grep `app.task.user-concurrent-limit`), add under `app:`:
  ```yaml
  app:
    interview:
      user-concurrent-limit: 3
      sse-ping-interval-ms: 30000
      idle-ttl-minutes: 1440          # AWAITING_INPUT 무응답 → EXPIRED (24h)
      stale-running-minutes: 60       # RUNNING claimed_at 초과 → FAILED 회수
      stale-check-interval-ms: 60000  # InterviewStaleRecoveryJob 주기
  ```

- [ ] **Step 6: Run & see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.InterviewApiIntegrationTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, all 7 tests pass. (If Docker is unavailable, run `./gradlew compileTestJava` and confirm it compiles; flag that the integration tests require `RUN_TESTCONTAINERS=true` + Docker.)

- [ ] **Step 7: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/main/resources/application-api.yml src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java && git commit -m "feat(interview): 사용자 REST 컨트롤러 (create/get/stream/answer/register/cancel) + ACL + SSE push

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 4: `InterviewWorkerController` — worker REST (claim via ?workerId=, question/plan/heartbeat/fail)

> Mirrors `WorkerController`: `@RestController @RequestMapping("/worker/interviews") @PreAuthorize("hasAuthority('ROLE_WORKER')") @Profile("api")`. Auth is the existing `WorkerApiKeyFilter` (it already matches all `/worker/**`). **Claim takes `?workerId=` as a query param** (`@RequestParam String workerId`), exactly like `WorkerController.nextTask`. All transitions delegate to **Phase 1's** `InterviewService` (`claim`/`recordQuestion`/`recordPlan`/`heartbeat`/`fail`); the controller adds the SSE push. `recordQuestion` returns the persisted `InterviewTurn` so the controller can route `design` vs `question` SSE events; `recordPlan` returns the `InterviewPlan` so the controller can push the `plan_ready` object.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java`
- Create: `src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerApiIntegrationTest.java`

> No `InterviewService` change expected — `claim`/`recordQuestion`/`recordPlan`/`heartbeat`/`fail` are all Phase 1's (verified in Task 0 Step 4). The worker heartbeat/fail bodies reuse Phase 1's call shape: `heartbeat(sessionId, workerId)` and `fail(sessionId, actor, reason)` take primitives, so the worker controller passes `workerId`/`reason` as request params or a small inline body — no new DTO records.

- [ ] **Step 1: Write the failing integration test.** Create `InterviewWorkerApiIntegrationTest.java`:
  ```java
  package com.hamonsoft.netismaker.controller;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.hamonsoft.netismaker.TestcontainersConfig;
  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
  import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.test.context.ContextConfiguration;
  import org.springframework.test.web.servlet.MockMvc;

  import java.util.List;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.http.MediaType.APPLICATION_JSON;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
  @SpringBootTest
  @AutoConfigureMockMvc
  @ContextConfiguration(initializers = TestcontainersConfig.class)
  class InterviewWorkerApiIntegrationTest {

      @Autowired private MockMvc mvc;
      @Autowired private ObjectMapper json;
      @Autowired private InterviewSessionRepository sessionRepo;
      @Autowired private InterviewTurnRepository turnRepo;
      @Value("${app.worker.api-key}") private String apiKey;

      private Long sid;

      @BeforeEach void seed() {
          sessionRepo.deleteAll();
          InterviewSession s = InterviewSession.create("hamonsoft/netis-backend", "main",
                  "RBAC", "권한 추가", "user1", List.of());
          sid = sessionRepo.save(s).getId();
      }

      @Test
      void claim_returns_session_and_sets_RUNNING() throws Exception {
          mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                          .param("workerId", "iw-1"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.sessionId").value(sid))
                  .andExpect(jsonPath("$.githubRepo").value("hamonsoft/netis-backend"))
                  .andExpect(jsonPath("$.workDir").exists());  // claim이 work_dir 할당/반환
          assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.RUNNING);
      }

      @Test
      void claim_no_queued_returns_204() throws Exception {
          sessionRepo.deleteAll();
          mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                          .param("workerId", "iw-1"))
                  .andExpect(status().isNoContent());
      }

      @Test
      void question_persists_turn_and_sets_AWAITING_INPUT() throws Exception {
          mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
          mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                          .param("workerId", "iw-1")
                          .contentType(APPLICATION_JSON)
                          .content("{\"content\":\"어떤 인증을 쓰나요?\",\"claudeSessionId\":\"sess-1\",\"kind\":\"question\",\"costUsd\":0.02}"))
                  .andExpect(status().isNoContent());
          var s = sessionRepo.findById(sid).orElseThrow();
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
          assertThat(s.getClaudeSessionId()).isEqualTo("sess-1");
          assertThat(turnRepo.findBySessionIdOrderBySeqAsc(sid)).hasSize(1);
      }

      @Test
      void question_blank_claude_session_id_is_not_stored() throws Exception {
          mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
          mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                          .param("workerId", "iw-1")
                          .contentType(APPLICATION_JSON)
                          .content("{\"content\":\"질문\",\"claudeSessionId\":\"   \",\"kind\":\"question\"}"))
                  .andExpect(status().isNoContent());
          // blank-guard: 빈/공백 claude_session_id는 저장하지 않음
          assertThat(sessionRepo.findById(sid).orElseThrow().getClaudeSessionId()).isNull();
      }

      @Test
      void plan_persists_and_sets_PLAN_READY() throws Exception {
          mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
          mvc.perform(post("/worker/interviews/" + sid + "/plan").header("X-Worker-API-Key", apiKey)
                          .param("workerId", "iw-1")
                          .contentType(APPLICATION_JSON)
                          .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[{\\\"task\\\":\\\"a\\\"}]\",\"costUsd\":0.5,\"durationMs\":12000}"))
                  .andExpect(status().isNoContent());
          assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.PLAN_READY);
      }

      @Test
      void claim_requires_api_key_returns_401_or_403() throws Exception {
          mvc.perform(post("/worker/interviews/claim").param("workerId", "iw-1"))
                  .andExpect(status().is4xxClientError());
      }
  }
  ```

- [ ] **Step 2: Run & see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.InterviewWorkerApiIntegrationTest' -q
  ```
  Expect: compile failure `cannot find symbol: class InterviewWorkerController`.

- [ ] **Step 3: Create the controller.** Create `InterviewWorkerController.java`. Delegates to Phase 1 transitions and pushes SSE. Claim is `@RequestParam String workerId`:
  ```java
  package com.hamonsoft.netismaker.controller;

  import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
  import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
  import com.hamonsoft.netismaker.dto.WorkerQuestionRequest;
  import com.hamonsoft.netismaker.entity.InterviewPlan;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.entity.InterviewTurn;
  import com.hamonsoft.netismaker.service.InterviewService;
  import com.hamonsoft.netismaker.service.InterviewStreamService;
  import jakarta.validation.Valid;
  import org.springframework.context.annotation.Profile;
  import org.springframework.http.HttpStatus;
  import org.springframework.http.ResponseEntity;
  import org.springframework.security.access.prepost.PreAuthorize;
  import org.springframework.web.bind.annotation.*;

  import java.util.Optional;

  /**
   * 인터뷰 워커 API (X-Worker-API-Key 인증, WorkerController 미러).
   *
   *   POST /worker/interviews/claim?workerId=…   ─► QUEUED 세션 1건 claim (SKIP LOCKED) 또는 204
   *                                                  (claim이 work_dir 할당/반환 — Phase 1)
   *   POST /worker/interviews/{id}/question       ─► assistant turn 저장 + SSE question|design/status + AWAITING_INPUT
   *   POST /worker/interviews/{id}/plan           ─► interview_plan 저장 + SSE plan_ready(객체)/status + PLAN_READY
   *   POST /worker/interviews/{id}/heartbeat?workerId=… ─► last_activity_at 갱신
   *   POST /worker/interviews/{id}/fail?workerId=…&reason=… ─► FAILED + SSE status/done
   */
  @RestController
  @RequestMapping("/worker/interviews")
  @PreAuthorize("hasAuthority('ROLE_WORKER')")
  @Profile("api")
  public class InterviewWorkerController {

      private final InterviewService interviewService;
      private final InterviewStreamService interviewStream;

      public InterviewWorkerController(InterviewService interviewService, InterviewStreamService interviewStream) {
          this.interviewService = interviewService;
          this.interviewStream = interviewStream;
      }

      @PostMapping("/claim")
      public ResponseEntity<InterviewClaimResponse> claim(@RequestParam String workerId) {
          Optional<InterviewClaimResponse> claimed = interviewService.claim(workerId);
          return claimed.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
      }

      @PostMapping("/{id}/question")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void question(@PathVariable Long id, @RequestParam String workerId,
                           @RequestBody @Valid WorkerQuestionRequest req) {
          InterviewTurn turn = interviewService.recordQuestion(id, workerId, req);
          if ("design".equals(turn.getKind())) interviewStream.pushDesign(id, turn.getContent());
          else interviewStream.pushQuestion(id, turn.getContent());
          interviewStream.pushStatus(id, InterviewStatus.AWAITING_INPUT); // 영문 enum name
      }

      @PostMapping("/{id}/plan")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void plan(@PathVariable Long id, @RequestParam String workerId,
                       @RequestBody @Valid WorkerPlanRequest req) {
          InterviewPlan plan = interviewService.recordPlan(id, workerId, req);
          // plan_ready = {designMarkdown, planMarkdown, planJson} JSON 객체 (LOCKED CONTRACT v2)
          interviewStream.pushPlanReady(id,
                  plan.getDesignMarkdown(), plan.getPlanMarkdown(), plan.getPlanJson());
          interviewStream.pushStatus(id, InterviewStatus.PLAN_READY);
      }

      @PostMapping("/{id}/heartbeat")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void heartbeat(@PathVariable Long id, @RequestParam String workerId) {
          interviewService.heartbeat(id, workerId);
      }

      @PostMapping("/{id}/fail")
      @ResponseStatus(HttpStatus.NO_CONTENT)
      public void fail(@PathVariable Long id, @RequestParam String workerId,
                       @RequestParam(required = false) String reason) {
          interviewService.fail(id, workerId, reason);
          interviewStream.pushStatus(id, InterviewStatus.FAILED);
          interviewStream.finish(id);
      }
  }
  ```
  > `recordQuestion` returns the persisted `InterviewTurn`, `recordPlan` returns the persisted `InterviewPlan` (Phase 1 signatures — see Task 0). If Phase 1's `claim` query param differs, conform to it; the contract mandates `?workerId=`.

- [ ] **Step 4: Run & see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.InterviewWorkerApiIntegrationTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, all 6 tests pass (claim/204/question→AWAITING_INPUT/blank-guard/plan→PLAN_READY/401).

- [ ] **Step 5: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerApiIntegrationTest.java && git commit -m "feat(interview): 워커 REST 컨트롤러 (claim?workerId=/question/plan/heartbeat/fail) + SSE push

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 5: `InterviewStaleRecoveryJob` — scheduled stale-recovery + idle-TTL expire

> **New task (LOCKED CONTRACT v2 missing-task).** Mirrors `StaleTaskRecoveryJob`: a `@Scheduled @Component @Profile("api")` job that scans in-flight/idle interview sessions and recovers them, wiring **Phase 1's** `InterviewService.fail` and `InterviewService.expire` (defined in plan 01). Two recovery paths:
> - **RUNNING with stale `claimed_at`** (worker died / hung past `stale-running-minutes`) → `InterviewService.fail(id, "stale-recovery", reason)` (re-queue is unnecessary because `fail` is terminal; the user can resubmit). This mirrors the analysis-path "IN_PROGRESS → FAILED" backstop.
> - **AWAITING_INPUT past idle TTL** (`idle-ttl-minutes` since `last_activity_at`) → `InterviewService.expire(id, "idle TTL 초과")`.
> Both transitions push the terminal SSE status + `done` via `InterviewStreamService`, and each runs in its own try/catch so one bad session doesn't abort the sweep.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/InterviewStaleRecoveryJob.java`
- Modify (conditional): `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java` (add the two finder queries below if Phase 1 didn't already provide a running/awaiting finder — these are read-only derived/`@Query` methods, not transitions)
- Create: `src/test/java/com/hamonsoft/netismaker/service/InterviewStaleRecoveryJobTest.java`

- [ ] **Step 1: Write the failing test** (mirrors `StaleTaskRecoveryJobTest`; pure Mockito, no DB). Create `InterviewStaleRecoveryJobTest.java`:
  ```java
  package com.hamonsoft.netismaker.service;

  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
  import org.junit.jupiter.api.Test;
  import org.springframework.test.util.ReflectionTestUtils;

  import java.time.OffsetDateTime;
  import java.util.List;

  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.ArgumentMatchers.eq;
  import static org.mockito.Mockito.*;

  class InterviewStaleRecoveryJobTest {

      private final InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
      private final InterviewService interviewService = mock(InterviewService.class);
      private final InterviewStreamService stream = mock(InterviewStreamService.class);

      private InterviewStaleRecoveryJob job() {
          InterviewStaleRecoveryJob j = new InterviewStaleRecoveryJob(sessionRepo, interviewService, stream);
          ReflectionTestUtils.setField(j, "idleTtlMinutes", 1440);
          ReflectionTestUtils.setField(j, "staleRunningMinutes", 60);
          ReflectionTestUtils.setField(j, "workerDeadThresholdSeconds", 60);
          return j;
      }

      private InterviewSession session(long id, InterviewStatus status, OffsetDateTime claimedAt,
                                       OffsetDateTime lastActivity) {
          InterviewSession s = InterviewSession.create("o/r", "main", "T", "d", "u1", List.of());
          ReflectionTestUtils.setField(s, "id", id);
          s.setStatus(status);
          s.setClaimedAt(claimedAt);
          s.setLastActivityAt(lastActivity);
          return s;
      }

      @Test
      void running_with_stale_claimed_at_is_failed() {
          OffsetDateTime old = OffsetDateTime.now().minusMinutes(120);
          InterviewSession s = session(1L, InterviewStatus.RUNNING, old, old);
          when(sessionRepo.findStaleRunning(any())).thenReturn(List.of(s));
          when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of());

          job().recover();

          verify(interviewService).fail(eq(1L), eq("stale-recovery"), contains("초과"));
          verify(stream).pushStatus(1L, InterviewStatus.FAILED);
          verify(stream).finish(1L);
      }

      @Test
      void awaiting_input_past_idle_ttl_is_expired() {
          OffsetDateTime old = OffsetDateTime.now().minusMinutes(2000);
          InterviewSession s = session(2L, InterviewStatus.AWAITING_INPUT, null, old);
          when(sessionRepo.findStaleRunning(any())).thenReturn(List.of());
          when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of(s));

          job().recover();

          verify(interviewService).expire(eq(2L), contains("idle"));
          verify(stream).pushStatus(2L, InterviewStatus.EXPIRED);
          verify(stream).finish(2L);
      }

      @Test
      void one_failing_session_does_not_abort_the_sweep() {
          OffsetDateTime old = OffsetDateTime.now().minusMinutes(120);
          InterviewSession a = session(1L, InterviewStatus.RUNNING, old, old);
          InterviewSession b = session(3L, InterviewStatus.RUNNING, old, old);
          when(sessionRepo.findStaleRunning(any())).thenReturn(List.of(a, b));
          when(sessionRepo.findIdleAwaitingInput(any())).thenReturn(List.of());
          doThrow(new RuntimeException("boom")).when(interviewService).fail(eq(1L), any(), any());

          job().recover();

          verify(interviewService).fail(eq(3L), any(), any()); // 두 번째 세션도 처리됨
      }
  }
  ```

- [ ] **Step 2: Run & see it fail.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewStaleRecoveryJobTest' -q
  ```
  Expect: compile failure `cannot find symbol: class InterviewStaleRecoveryJob` (and `findStaleRunning`/`findIdleAwaitingInput` if not yet on the repo).

- [ ] **Step 3: Add the two read-only finder queries to `InterviewSessionRepository`** (only if Phase 1 didn't already provide equivalents — these are reads, not transitions):
  ```java
  /** RUNNING이면서 claimed_at이 cutoff 이전(=stale) — 회수 후보. */
  @Query("""
      SELECT s FROM InterviewSession s
      WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING
        AND s.claimedAt IS NOT NULL AND s.claimedAt < :cutoff
  """)
  List<InterviewSession> findStaleRunning(@Param("cutoff") OffsetDateTime cutoff);

  /** AWAITING_INPUT이면서 last_activity_at이 cutoff 이전(=idle TTL 초과) — 만료 후보. */
  @Query("""
      SELECT s FROM InterviewSession s
      WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.AWAITING_INPUT
        AND s.lastActivityAt < :cutoff
  """)
  List<InterviewSession> findIdleAwaitingInput(@Param("cutoff") OffsetDateTime cutoff);
  ```

- [ ] **Step 4: Create the job.** Create `InterviewStaleRecoveryJob.java`:
  ```java
  package com.hamonsoft.netismaker.service;

  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.context.annotation.Profile;
  import org.springframework.scheduling.annotation.Scheduled;
  import org.springframework.stereotype.Component;

  import java.time.OffsetDateTime;
  import java.util.List;

  /**
   * 인터뷰 stale 회수 + idle 만료 잡 (api 프로파일). StaleTaskRecoveryJob 미러.
   *
   *  매 stale-check-interval-ms마다:
   *    1) RUNNING + claimed_at이 stale-running-minutes 초과 → InterviewService.fail (워커 사망/행업 백스톱)
   *    2) AWAITING_INPUT + last_activity_at이 idle-ttl-minutes 초과 → InterviewService.expire (사람 미복귀)
   *
   *  전이는 전부 Phase 1 InterviewService에 위임. 각 세션을 독립 try/catch로 처리해
   *  한 세션 오류가 전체 sweep을 중단하지 않게 한다.
   */
  @Component
  @Profile("api")
  @Slf4j
  public class InterviewStaleRecoveryJob {

      private final InterviewSessionRepository sessionRepo;
      private final InterviewService interviewService;
      private final InterviewStreamService stream;

      @Value("${app.interview.idle-ttl-minutes:1440}")
      private int idleTtlMinutes;

      @Value("${app.interview.stale-running-minutes:60}")
      private int staleRunningMinutes;

      @Value("${app.task.worker-dead-threshold-seconds:60}")
      private int workerDeadThresholdSeconds;

      public InterviewStaleRecoveryJob(InterviewSessionRepository sessionRepo,
                                       InterviewService interviewService,
                                       InterviewStreamService stream) {
          this.sessionRepo = sessionRepo;
          this.interviewService = interviewService;
          this.stream = stream;
      }

      @Scheduled(fixedRateString = "${app.interview.stale-check-interval-ms:60000}")
      public void recover() {
          OffsetDateTime now = OffsetDateTime.now();

          // 1) RUNNING stale → FAILED. claimed_at이 staleRunningMinutes 초과(워커 첫 heartbeat 시간 보장은 cutoff에 내포).
          OffsetDateTime runningCutoff = now.minusMinutes(staleRunningMinutes);
          for (InterviewSession s : sessionRepo.findStaleRunning(runningCutoff)) {
              Long id = s.getId();
              try {
                  interviewService.fail(id, "stale-recovery",
                          "처리 시간 초과(" + staleRunningMinutes + "분) — 워커 사망/행업 회수");
                  stream.pushStatus(id, InterviewStatus.FAILED);
                  stream.finish(id);
                  log.warn("Stale 회수: interview={} RUNNING → FAILED", id);
              } catch (Exception e) {
                  log.error("Stale 회수 실패: interview={}", id, e);
              }
          }

          // 2) AWAITING_INPUT idle → EXPIRED.
          OffsetDateTime idleCutoff = now.minusMinutes(idleTtlMinutes);
          for (InterviewSession s : sessionRepo.findIdleAwaitingInput(idleCutoff)) {
              Long id = s.getId();
              try {
                  interviewService.expire(id, "idle TTL 초과(" + idleTtlMinutes + "분)");
                  stream.pushStatus(id, InterviewStatus.EXPIRED);
                  stream.finish(id);
                  log.warn("Idle 만료: interview={} AWAITING_INPUT → EXPIRED", id);
              } catch (Exception e) {
                  log.error("Idle 만료 실패: interview={}", id, e);
              }
          }
      }
  }
  ```
  > `InterviewService.fail(Long, String, String)` and `InterviewService.expire(Long, String)` are **Phase 1's** transitions (verified in Task 0 Step 4). The job only orchestrates them + pushes SSE; it owns no state transition itself. `workerDeadThresholdSeconds` is read for parity with `StaleTaskRecoveryJob` but the running cutoff already enforces the grace window via `staleRunningMinutes`; keep the field to allow a future heartbeat-aware refinement.

- [ ] **Step 5: Run & see it pass.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewStaleRecoveryJobTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 6: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/main/java/com/hamonsoft/netismaker/service/InterviewStaleRecoveryJob.java src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java src/test/java/com/hamonsoft/netismaker/service/InterviewStaleRecoveryJobTest.java && git commit -m "feat(interview): InterviewStaleRecoveryJob — RUNNING stale→fail, AWAITING_INPUT idle→expire (StaleTaskRecoveryJob 미러)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 6: REGISTER → Task promotion verification (Phase 1 owns the impl; Phase 2 asserts the contract end-to-end)

> **Phase 1 owns `InterviewService.register`** (PLAN_READY → REGISTERED + Task at COMPLETED + prefilled TaskAnalysis). This task does **not** re-implement it — it adds a Phase-2 service-level test that **locks the LOCKED CONTRACT v2 register shape** so a Phase 1 refactor can't silently break it: `TaskAnalysis.create(taskId, designMarkdown, planJson, null, durationMs)` → `markdownResult = design_markdown ONLY` (no composite), `claudeLog = null`, `subtasksJson = planJson`; Task created at `COMPLETED`; `mcps_extra` snapshotted; `interview_session.task_id` linked; session → `REGISTERED`. If the test fails because Phase 1 composes design+plan or passes a non-null `claudeLog`, fix it **in Phase 1** (it is the owner) — do not add a parallel register in Phase 2.

**Files:**
- Create: `src/test/java/com/hamonsoft/netismaker/service/InterviewRegisterContractTest.java`
- Modify (conditional, in Phase 1 only): `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (if the test reveals a contract violation in Phase 1's `register`)

- [ ] **Step 1: Write the failing/locking test** (service-level, Testcontainers). Create `InterviewRegisterContractTest.java`:
  ```java
  package com.hamonsoft.netismaker.service;

  import com.hamonsoft.netismaker.TestcontainersConfig;
  import com.hamonsoft.netismaker.dto.WorkerPlanRequest;
  import com.hamonsoft.netismaker.entity.*;
  import com.hamonsoft.netismaker.repository.*;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.test.context.ContextConfiguration;

  import java.math.BigDecimal;
  import java.util.List;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
  @SpringBootTest
  @ContextConfiguration(initializers = TestcontainersConfig.class)
  class InterviewRegisterContractTest {

      @Autowired private InterviewService interviewService;
      @Autowired private InterviewSessionRepository sessionRepo;
      @Autowired private TaskRepository taskRepo;
      @Autowired private TaskAnalysisRepository analysisRepo;

      private Long sid;

      @BeforeEach void seed() {
          sessionRepo.deleteAll();
          taskRepo.deleteAll();
          InterviewSession s = InterviewSession.create("hamonsoft/netis-backend", "feat/rbac",
                  "RBAC 추가", "역할 기반 권한", "user1",
                  List.of(new TaskMcpSpec("ctx7", "https://ctx7", "http")));
          sid = sessionRepo.save(s).getId();
      }

      private void toPlanReady() {
          interviewService.claim("iw-1");                              // QUEUED → RUNNING (work_dir 할당)
          interviewService.recordPlan(sid, "iw-1", new WorkerPlanRequest(
                  "# 설계 문서", "# 구현 플랜", "[{\"title\":\"A\"},{\"title\":\"B\"}]",
                  new BigDecimal("0.42"), 30000L));                    // RUNNING → PLAN_READY
      }

      @Test
      void register_creates_completed_task_with_design_only_analysis() {
          toPlanReady();
          Long taskId = interviewService.register(sid, "user1", false);

          Task t = taskRepo.findById(taskId).orElseThrow();
          assertThat(t.getStatus()).isEqualTo(TaskStatus.COMPLETED);
          assertThat(t.getGithubRepo()).isEqualTo("hamonsoft/netis-backend");
          assertThat(t.getGithubBranch()).isEqualTo("feat/rbac");
          assertThat(t.getRequesterId()).isEqualTo("user1");
          assertThat(t.getMcpsExtra()).extracting(TaskMcpSpec::name).containsExactly("ctx7");

          TaskAnalysis a = analysisRepo.findById(taskId).orElseThrow();
          // LOCKED CONTRACT v2: markdownResult = design_markdown ONLY (합본 아님)
          assertThat(a.getMarkdownResult()).isEqualTo("# 설계 문서");
          assertThat(a.getSubtasksJson()).isEqualTo("[{\"title\":\"A\"},{\"title\":\"B\"}]");
          assertThat(a.getClaudeLog()).isNull();      // claudeLog = null
          assertThat(a.isApproved()).isFalse();       // 관리자 승인 게이트 유지

          InterviewSession s = sessionRepo.findById(sid).orElseThrow();
          assertThat(s.getStatus()).isEqualTo(InterviewStatus.REGISTERED);
          assertThat(s.getTaskId()).isEqualTo(taskId);
      }

      @Test
      void register_before_plan_ready_is_rejected() {
          assertThatThrownBy(() -> interviewService.register(sid, "user1", false))
                  .isInstanceOf(TaskException.class)
                  .hasMessageContaining("플랜완료");
      }

      @Test
      void register_is_idempotent_after_first_call() {
          toPlanReady();
          Long first = interviewService.register(sid, "user1", false);
          assertThatThrownBy(() -> interviewService.register(sid, "user1", false))
                  .isInstanceOf(TaskException.class);
          assertThat(taskRepo.count()).isEqualTo(1);
          assertThat(taskRepo.findById(first)).isPresent();
      }

      @Test
      void register_non_owner_is_forbidden() {
          toPlanReady();
          assertThatThrownBy(() -> interviewService.register(sid, "user2", false))
                  .isInstanceOf(TaskException.class);
      }
  }
  ```

- [ ] **Step 2: Run.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.service.InterviewRegisterContractTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, 4 tests pass against Phase 1's `register`. If `register_creates_completed_task_with_design_only_analysis` fails (composite markdown or non-null claudeLog), the fix is in **Phase 1's** `InterviewService.register` — change the `TaskAnalysis.create` call to `TaskAnalysis.create(saved.getId(), plan.getDesignMarkdown(), plan.getPlanJson(), null, plan.getDurationMs())` and drop the `composeAnalysisMarkdown` helper. Re-run until green.

- [ ] **Step 3: Verify the controller-level register 409 path still passes** (Task 3 test):
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.InterviewApiIntegrationTest' -q
  ```
  Expect: still `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/test/java/com/hamonsoft/netismaker/service/InterviewRegisterContractTest.java && git commit -m "test(interview): REGISTER → Task(COMPLETED) 계약 고정 (design-only markdown, claudeLog=null, subtasks=planJson)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 7: Answer idempotency — explicit worker round-trip test through the HTTP surface

> DESIGN §8/§12 + Phase 1 `submitAnswer` require duplicate answer submissions to be deduped by `replyToSeq`. Phase 1 implemented the dedupe; this task proves it end-to-end through the HTTP surface and locks the behavior.

**Files:**
- Create: `src/test/java/com/hamonsoft/netismaker/controller/InterviewAnswerIdempotencyTest.java`

- [ ] **Step 1: Write the idempotency test.** Create `InterviewAnswerIdempotencyTest.java`:
  ```java
  package com.hamonsoft.netismaker.controller;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.hamonsoft.netismaker.TestcontainersConfig;
  import com.hamonsoft.netismaker.entity.InterviewSession;
  import com.hamonsoft.netismaker.entity.InterviewStatus;
  import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
  import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.security.core.authority.SimpleGrantedAuthority;
  import org.springframework.test.context.ContextConfiguration;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.request.RequestPostProcessor;

  import java.util.List;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.springframework.http.MediaType.APPLICATION_JSON;
  import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
  @SpringBootTest
  @AutoConfigureMockMvc
  @ContextConfiguration(initializers = TestcontainersConfig.class)
  class InterviewAnswerIdempotencyTest {

      @Autowired private MockMvc mvc;
      @Autowired private ObjectMapper json;
      @Autowired private InterviewSessionRepository sessionRepo;
      @Autowired private InterviewTurnRepository turnRepo;
      @Value("${app.worker.api-key}") private String apiKey;

      private Long sid;

      private static RequestPostProcessor userJwt(String u) {
          return jwt().jwt(b -> b.claim("username", u).claim("authorities", List.of("ROLE_USER")))
                  .authorities(new SimpleGrantedAuthority("ROLE_USER"));
      }

      @BeforeEach void seed() {
          sessionRepo.deleteAll();
          sid = sessionRepo.save(InterviewSession.create("a/b", "main", "T", "D", "user1", List.of())).getId();
      }

      @Test
      void duplicate_answer_with_same_replyToSeq_is_accepted_once() throws Exception {
          // claim?workerId= → question (seq=1, AWAITING_INPUT)
          mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"));
          mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                  .param("workerId", "iw-1")
                  .contentType(APPLICATION_JSON)
                  .content("{\"content\":\"Q1\",\"claudeSessionId\":\"s1\",\"kind\":\"question\"}"));

          String answer = json.writeValueAsString(new com.hamonsoft.netismaker.dto.AnswerRequest("내 답변", 1));

          // 1st answer → 200, re-queues to QUEUED, adds user turn
          mvc.perform(post("/api/interviews/" + sid + "/answer").with(userJwt("user1"))
                  .contentType(APPLICATION_JSON).content(answer)).andExpect(status().isOk());

          long afterFirst = turnRepo.findBySessionIdOrderBySeqAsc(sid).stream()
                  .filter(t -> "user".equals(t.getRole())).count();

          // 2nd identical answer (same replyToSeq=1) → no new user turn
          mvc.perform(post("/api/interviews/" + sid + "/answer").with(userJwt("user1"))
                  .contentType(APPLICATION_JSON).content(answer)).andExpect(status().isOk());

          long afterSecond = turnRepo.findBySessionIdOrderBySeqAsc(sid).stream()
                  .filter(t -> "user".equals(t.getRole())).count();

          assertThat(afterFirst).isEqualTo(1);
          assertThat(afterSecond).isEqualTo(1); // 중복 무시
          assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.QUEUED);
      }
  }
  ```

- [ ] **Step 2: Run.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.InterviewAnswerIdempotencyTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`. If the second `answer` flips status or adds a turn, the dedupe is in **Phase 1's** `submitAnswer` (the `replyToSeq` guard) — fix it there, not in Phase 2. Re-run until green.

- [ ] **Step 3: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/test/java/com/hamonsoft/netismaker/controller/InterviewAnswerIdempotencyTest.java && git commit -m "test(interview): 답변 idempotency end-to-end (replyToSeq 중복 무시)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 8: SecurityConfig + actuator sanity for the new SSE/worker surface (verification, no new production code)

> The existing `apiFilterChain` matches `/api/**` and the `workerFilterChain` matches `/worker/**`, so the new endpoints inherit auth automatically. The SSE `?access_token=` already works via `bearerTokenResolver().setAllowUriQueryParameter(true)`. `WorkerApiKeyFilter.shouldNotFilter` already covers `/worker/interviews/**` because it only checks the `/worker/` prefix. This task asserts those facts with a small test so a future security refactor can't silently break the interview surface — no production code should be needed.

**Files:**
- Read: `src/main/java/com/hamonsoft/netismaker/config/SecurityConfig.java`
- Read: `src/main/java/com/hamonsoft/netismaker/config/WorkerApiKeyFilter.java`
- Create: `src/test/java/com/hamonsoft/netismaker/controller/InterviewSecuritySurfaceTest.java`

- [ ] **Step 1: Write the deny-path test.** Create `InterviewSecuritySurfaceTest.java`:
  ```java
  package com.hamonsoft.netismaker.controller;

  import com.hamonsoft.netismaker.TestcontainersConfig;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.test.context.ContextConfiguration;
  import org.springframework.test.web.servlet.MockMvc;

  import static org.springframework.http.MediaType.APPLICATION_JSON;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
  @SpringBootTest
  @AutoConfigureMockMvc
  @ContextConfiguration(initializers = TestcontainersConfig.class)
  class InterviewSecuritySurfaceTest {

      @Autowired private MockMvc mvc;

      @Test
      void api_interviews_without_jwt_is_401() throws Exception {
          mvc.perform(post("/api/interviews").contentType(APPLICATION_JSON)
                          .content("{\"githubRepo\":\"a/b\",\"title\":\"t\",\"description\":\"d\"}"))
                  .andExpect(status().isUnauthorized());
      }

      @Test
      void worker_interviews_claim_without_api_key_is_rejected() throws Exception {
          mvc.perform(post("/worker/interviews/claim").param("workerId", "iw-1"))
                  .andExpect(status().is4xxClientError());
      }

      @Test
      void api_interviews_stream_without_token_is_401() throws Exception {
          mvc.perform(get("/api/interviews/1/stream")).andExpect(status().isUnauthorized());
      }
  }
  ```

- [ ] **Step 2: Run.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.controller.InterviewSecuritySurfaceTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, 3 tests pass with zero production changes. If any fails, it reveals a real gap — investigate the matching filter chain (do NOT loosen `denyAll`; the interview paths are under `/api/**` and `/worker/**` which are already matched).

- [ ] **Step 3: Commit.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && git add src/test/java/com/hamonsoft/netismaker/controller/InterviewSecuritySurfaceTest.java && git commit -m "test(interview): 보안 표면 가드 (JWT/API-Key 미인증 거부)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
  ```

---

### Task 9: Full-suite regression run

**Files:** (none — verification only)

- [ ] **Step 1: Run the full interview test set + the existing suite to confirm no regressions.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test --tests 'com.hamonsoft.netismaker.*Interview*' --tests 'com.hamonsoft.netismaker.controller.TaskApiIntegrationTest' -q
  ```
  Expect: `BUILD SUCCESSFUL`, all interview tests + the existing task integration tests pass (proves the two security chains and the shared `com` schema still coexist).

- [ ] **Step 2: Run a build to confirm the whole module compiles and packages.**
  ```bash
  cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew build -x test -q && echo OK
  ```
  Expect: `OK`.

---

## Done criteria

All of the following pass with Docker available:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker && RUN_TESTCONTAINERS=true ./gradlew test -q
```
- `InterviewResponseTest`, `InterviewStreamServiceTest`, `InterviewStaleRecoveryJobTest` (no Docker needed) — green.
- `InterviewApiIntegrationTest` (create/get ACL/stream ACL/register-409), `InterviewWorkerApiIntegrationTest` (claim via `?workerId=`/204/question→AWAITING_INPUT/blank-guard/plan→PLAN_READY/401), `InterviewRegisterContractTest` (design-only `markdownResult`, `claudeLog=null`, `subtasksJson=planJson`, COMPLETED task + mcps snapshot + `task_id` link + PLAN_READY guard + idempotent + ACL), `InterviewAnswerIdempotencyTest`, `InterviewSecuritySurfaceTest` — green.
- The pre-existing `TaskApiIntegrationTest` still green (two security chains + shared `com` schema coexist).
- **No Phase 1 artifact is re-created by this plan** — `InterviewStatus`, `InterviewStatusConverter`, the three entities, the repositories, `InterviewService`'s transitions, the request DTOs, `InterviewClaimResponse`, and `V11__interview.sql` are all reused (Task 0).
- **No V12 migration** — `work_dir` and `interview_turn.reply_to_seq` live in `V11` from the start.
- SSE `status` events carry the **English** `InterviewStatus.name()`; `plan_ready` events carry the JSON object `{designMarkdown, planMarkdown, planJson}` — enforced by `InterviewStreamService` + `InterviewWorkerController`.
- `register` produces a Task at `COMPLETED` with `TaskAnalysis.create(taskId, designMarkdown, planJson, null, durationMs)` (design-only markdown) — enforced by `InterviewRegisterContractTest`.
- The `InterviewStaleRecoveryJob` wires Phase 1's `InterviewService.fail`/`expire` for RUNNING-stale / AWAITING_INPUT-idle recovery — enforced by `InterviewStaleRecoveryJobTest`.
