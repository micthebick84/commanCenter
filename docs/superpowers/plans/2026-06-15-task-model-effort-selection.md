# 작업 등록 시 모델 + effort 선택 — 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업 등록 화면에서 Claude 모델·effort를 선택해 인터뷰(Agent SDK)와 분석/구현 워커(claude CLI) 양쪽에 반영한다.

**Architecture:** 기존 `mcpCatalogIds` 배선을 미러링하되 scalar라 카탈로그 resolve가 없다. 모델별 허용 effort + 기본값은 백엔드 `ModelEffortPolicy`와 프론트 `modelEffort.ts`가 단일 진실원천. 폼 선택 → POST → DTO → 정책 검증/보정 → 엔티티 컬럼 → claim payload → 워커(`--model`/`--effort`)·SDK(`model`/`effort`). 인터뷰 model/effort는 register()로 구현 Task에 승계.

**Tech Stack:** Spring Boot 3.4 / Java 21 (JPA, Flyway, JUnit5+Mockito+AssertJ, Testcontainers[CI 전용]), Nuxt 3 + Quasar (TypeScript, Vitest), `@anthropic-ai/claude-agent-sdk@0.2.117` (vitest).

**선행 스펙:** `docs/superpowers/specs/2026-06-15-task-model-effort-selection-design.md`
**브랜치:** `feat/task-model-effort` (base `origin/main`)

---

## 단일 진실원천 (양쪽 동일하게 유지)

```
모델 → 허용 effort
  claude-opus-4-8   : low, medium, high, xhigh, max
  claude-opus-4-7   : low, medium, high, xhigh, max
  claude-sonnet-4-6 : low, medium, high, xhigh, max
  claude-haiku-4-5  : low, medium, high
기본값: model = claude-opus-4-8, effort = high
```
> 참고: 이 맵은 claude-api 레퍼런스(현행) 기준. 설치된 SDK 0.2.117 JSDoc은 xhigh/max를 Opus 전용으로 표기(stale)하나, 실제 effort는 CLI/SDK가 모델별로 처리한다. 런타임이 특정 조합을 거부하면 맵 한 곳(백엔드 `ModelEffortPolicy` + 프론트 `modelEffort.ts`)만 고치면 된다.

## 테스트 실행 메모
- **로컬 실행 가능**: `ModelEffortPolicyTest`, `ClaudeExecAdapterTest`, `InterviewServiceTest`(Mockito), 프론트 Vitest, 인터뷰 SDK Vitest.
- **CI 전용(RUN_TESTCONTAINERS)**: 통합/리포지토리 테스트(로컬 docker 비호환 — 실행 금지, 컴파일만).
- 백엔드 단위: `./gradlew test --tests '*ModelEffortPolicyTest' --tests '*ClaudeExecAdapterTest' --tests '*InterviewServiceTest'`
- 컴파일 전체: `./gradlew compileJava compileTestJava`
- 인터뷰 SDK: `cd netismaker-interview-service && npx vitest run`
- 프론트: `cd frontend && npx vitest run <file>`

## 파일 구조

| 파일 | 책임 | 변경 |
|---|---|---|
| `src/main/java/.../service/ModelEffortPolicy.java` | 허용 맵·기본값·검증 (단일 진실원천) | 신규 |
| `src/main/java/.../dto/TaskCreateRequest.java`·`CreateInterviewRequest.java` | 등록 요청 | model/effort 필드 |
| `src/main/java/.../entity/Task.java`·`InterviewSession.java` | 엔티티 | model/effort 컬럼 + create() 인자 |
| `src/main/resources/db/migration/V12__task_model_effort.sql` | 스키마 | 신규(두 테이블) |
| `src/main/java/.../service/TaskService.java`·`InterviewService.java` | 생성/등록 | 정책 적용 + 전달 |
| `src/main/java/.../dto/WorkerTaskResponse.java`·`InterviewClaimResponse.java` | claim 페이로드 | model/effort |
| `src/main/java/.../dto/TaskResponse.java`·`InterviewResponse.java` | 읽기 DTO | model/effort(표시) |
| `src/main/java/.../workerdaemon/ClaudeExecAdapter.java` | claude 실행 | buildCommand 추출 + --model/--effort |
| `src/main/java/.../workerdaemon/WorkerMainLoop.java` | 워커 루프 | exec 호출 2곳 전달 |
| `netismaker-interview-service/src/{types,sdk/sessionOptions,runner/interviewRunner}.ts` | 인터뷰 SDK | model/effort 옵션 |
| `frontend/composables/modelEffort.ts` | 옵션·동적필터(순수) | 신규 |
| `frontend/pages/tasks/index.vue` | 등록 폼 | q-select 2개 + POST body |
| `frontend/pages/tasks/[id].vue`·`frontend/components/InterviewPanel.vue` | 표시 | model/effort 칩 |

---

## Task 1: 백엔드 — ModelEffortPolicy (허용 맵·기본값·검증)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/ModelEffortPolicy.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/ModelEffortPolicyTest.java`

- [ ] **Step 1: 실패 테스트 작성** — `ModelEffortPolicyTest.java`
```java
package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelEffortPolicyTest {

    @Test
    void resolve_blank_returns_defaults() {
        assertThat(ModelEffortPolicy.resolveModel(null)).isEqualTo("claude-opus-4-8");
        assertThat(ModelEffortPolicy.resolveModel("  ")).isEqualTo("claude-opus-4-8");
        assertThat(ModelEffortPolicy.resolveEffort(null)).isEqualTo("high");
        assertThat(ModelEffortPolicy.resolveEffort("")).isEqualTo("high");
    }

    @Test
    void resolve_keeps_given_values() {
        assertThat(ModelEffortPolicy.resolveModel("claude-haiku-4-5")).isEqualTo("claude-haiku-4-5");
        assertThat(ModelEffortPolicy.resolveEffort("low")).isEqualTo("low");
    }

    @Test
    void validate_accepts_opus_and_sonnet_with_max() {
        ModelEffortPolicy.validate("claude-opus-4-8", "max");   // no throw
        ModelEffortPolicy.validate("claude-opus-4-7", "xhigh");
        ModelEffortPolicy.validate("claude-sonnet-4-6", "max");
    }

    @Test
    void validate_accepts_haiku_with_high() {
        ModelEffortPolicy.validate("claude-haiku-4-5", "high");
        ModelEffortPolicy.validate("claude-haiku-4-5", "low");
    }

    @Test
    void validate_rejects_unknown_model() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("gpt-5", "high"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("모델");
    }

    @Test
    void validate_rejects_haiku_with_max_or_xhigh() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-haiku-4-5", "max"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("effort");
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-haiku-4-5", "xhigh"))
                .isInstanceOf(TaskException.class);
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `cannot find symbol: class ModelEffortPolicy`

- [ ] **Step 3: 최소 구현** — `ModelEffortPolicy.java`
```java
package com.hamonsoft.netismaker.service;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** 작업 등록 시 선택 가능한 모델 + effort의 단일 진실원천(허용 맵·기본값·검증). */
public final class ModelEffortPolicy {

    public static final String DEFAULT_MODEL = "claude-opus-4-8";
    public static final String DEFAULT_EFFORT = "high";

    private static final List<String> FULL = List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> LIMITED = List.of("low", "medium", "high");

    /** 모델 → 허용 effort. (claude-api 레퍼런스: Haiku만 low/medium/high) */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "claude-opus-4-8", FULL,
            "claude-opus-4-7", FULL,
            "claude-sonnet-4-6", FULL,
            "claude-haiku-4-5", LIMITED);

    private ModelEffortPolicy() {}

    public static String resolveModel(String model) {
        return (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
    }

    public static String resolveEffort(String effort) {
        return (effort == null || effort.isBlank()) ? DEFAULT_EFFORT : effort;
    }

    /** allowlist + effort×model 호환성. 위반 시 400. resolve로 보정한 값으로 호출할 것. */
    public static void validate(String model, String effort) {
        List<String> allowed = ALLOWED.get(model);
        if (allowed == null) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "지원하지 않는 모델입니다: " + model);
        }
        if (!allowed.contains(effort)) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    model + "은(는) effort '" + effort + "'를 지원하지 않습니다 (허용: " + allowed + ")");
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*ModelEffortPolicyTest'`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**
```bash
git add src/main/java/com/hamonsoft/netismaker/service/ModelEffortPolicy.java \
        src/test/java/com/hamonsoft/netismaker/service/ModelEffortPolicyTest.java
git commit -m "feat(model-effort): ModelEffortPolicy 허용 맵·기본값·검증 (단일 진실원천)

모델별 허용 effort(Haiku=low/medium/high, 나머지=5단계), 기본 opus-4-8/high,
allowlist+호환성 검증(위반 400).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 백엔드 — Create DTO에 model/effort 추가 (+ 테스트 생성자 갱신)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskCreateRequest.java`, `CreateInterviewRequest.java`
- Modify(compile): 레코드 생성자를 호출하는 테스트들

- [ ] **Step 1: 레코드에 필드 추가** — 두 파일 모두 `mcpCatalogIds` 다음에 추가
`TaskCreateRequest.java` (line 27 `List<Long> mcpCatalogIds` 뒤, `) {}` 앞):
```java
        /** 카탈로그에서 선택된 추가 MCP id들. null/빈 배열 허용. */
        List<Long> mcpCatalogIds,

        /** Claude 모델 id. blank면 서버 기본값(claude-opus-4-8). */
        String model,

        /** 추론 effort. blank면 서버 기본값(high). */
        String effort
) {}
```
`CreateInterviewRequest.java`도 동일하게 변경.

- [ ] **Step 2: 깨지는 생성자 호출 찾기**

Run: `grep -rn "new CreateInterviewRequest(\|new TaskCreateRequest(" src/test src/main`
Expected: 테스트들에서 5-인자 생성자 호출이 컴파일 깨짐(레코드 컴포넌트 7개로 증가).

- [ ] **Step 3: 생성자 호출 갱신** — 발견된 각 호출에 트레일링 인자 `, null, null` 추가(model/effort=null → 서비스가 기본값 보정).

알려진 위치:
- `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java`의 `body(...)` 헬퍼(약 line 47):
```java
        return json.writeValueAsString(
                new CreateInterviewRequest(repo, "main", title, desc, List.of(), null, null));
```
- `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`의 `req()` 헬퍼(존재 시): `new CreateInterviewRequest("owner/repo", "main", "제목", "기능 요구", List.of(), null, null)`.
- grep으로 나온 그 외 모든 `new TaskCreateRequest(...)`/`new CreateInterviewRequest(...)`에 `, null, null` 추가.

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 커밋**
```bash
git add src/main/java/com/hamonsoft/netismaker/dto/TaskCreateRequest.java \
        src/main/java/com/hamonsoft/netismaker/dto/CreateInterviewRequest.java \
        src/test
git commit -m "feat(model-effort): Create DTO에 model/effort 필드 추가

TaskCreateRequest·CreateInterviewRequest에 String model/effort 추가(blank 허용,
서비스가 기본값 보정). 레코드 arity 변경으로 깨진 테스트 생성자 갱신.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 백엔드 — 엔티티 컬럼 + create() 인자 + 서비스 정책 배선

**Files:**
- Modify: `entity/Task.java`, `entity/InterviewSession.java`, `service/TaskService.java`, `service/InterviewService.java`
- Modify(compile): `InterviewSession.create`/`Task.create` 직접 호출하는 테스트 헬퍼
- Test: `service/InterviewServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성** — `InterviewServiceTest.java`에 추가

기존 `req()` 헬퍼가 model/effort=null을 보내므로 기본값 보정을 검증. import는 이미 충분.
```java
    @Test
    void create_applies_default_model_and_effort_when_blank() {
        when(sessionRepo.countActiveByRequester("u1")).thenReturn(0L);
        when(mcpCatalogService.resolveByIds(any())).thenReturn(List.of());
        InterviewSession s = service.create(req(), "u1");   // req() sends model=null, effort=null
        assertThat(s.getModel()).isEqualTo("claude-opus-4-8");
        assertThat(s.getEffort()).isEqualTo("high");
    }

    @Test
    void create_rejects_incompatible_model_effort() {
        when(sessionRepo.countActiveByRequester("u1")).thenReturn(0L);
        var bad = new com.hamonsoft.netismaker.dto.CreateInterviewRequest(
                "owner/repo", "main", "제목", "내용", List.of(), "claude-haiku-4-5", "max");
        assertThatThrownBy(() -> service.create(bad, "u1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("effort");
    }

    @Test
    void register_forwards_model_and_effort_to_created_task() {
        InterviewSession s = session(20L, InterviewStatus.PLAN_READY);
        s.setModel("claude-sonnet-4-6");
        s.setEffort("medium");
        when(sessionRepo.findActiveById(20L)).thenReturn(java.util.Optional.of(s));
        InterviewPlan plan = InterviewPlan.create(20L, "# 설계", "# 플랜", "[]", 1000L, new java.math.BigDecimal("0.1"));
        when(planRepo.findById(20L)).thenReturn(java.util.Optional.of(plan));

        service.register(20L, "u1", false);

        org.mockito.ArgumentCaptor<Task> cap = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(taskRepo).save(cap.capture());
        assertThat(cap.getValue().getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(cap.getValue().getEffort()).isEqualTo("medium");
    }
```
> 참고: `session(...)` 헬퍼는 Step 3에서 `InterviewSession.create(...)`에 model/effort 인자가 추가되면 갱신해야 한다(아래).

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*InterviewServiceTest'`
Expected: FAIL/컴파일 실패 — `getModel()`/`setModel()` 미존재.

- [ ] **Step 3a: Task 엔티티** — `entity/Task.java`

`mcpsExtra` 필드(73-77) 아래에 추가:
```java
    /** 작업 등록 시 선택된 Claude 모델. 워커가 claude --model에 사용. */
    @Column(name = "model", nullable = false, length = 64)
    @Setter
    private String model = "claude-opus-4-8";

    /** 추론 effort (low/medium/high/xhigh/max). 워커가 claude --effort에 사용. */
    @Column(name = "effort", nullable = false, length = 16)
    @Setter
    private String effort = "high";
```
`create(...)` 팩토리(144-161) 시그니처 끝에 `String model, String effort` 추가, body에 대입:
```java
    public static Task create(String githubRepo, String githubBranch, String title,
                              String description, String requesterId, int maxRetry,
                              List<TaskMcpSpec> mcpsExtra, String model, String effort) {
        Task t = new Task();
        // ... 기존 대입 유지 ...
        t.mcpsExtra = mcpsExtra == null ? new ArrayList<>() : mcpsExtra;
        t.model = (model == null || model.isBlank()) ? "claude-opus-4-8" : model;
        t.effort = (effort == null || effort.isBlank()) ? "high" : effort;
        OffsetDateTime now = OffsetDateTime.now();
        t.createdAt = now;
        t.updatedAt = now;
        return t;
    }
```

- [ ] **Step 3b: InterviewSession 엔티티** — `entity/InterviewSession.java`

`mcpsExtra` 필드(88-92) 아래에 동일한 `model`/`effort` `@Column @Setter` 필드 추가(위와 동일 코드). `create(...)`(111-128) 시그니처 끝에 `String model, String effort` 추가, body에 동일 패턴 대입:
```java
        s.mcpsExtra = mcpsExtra == null ? new ArrayList<>() : mcpsExtra;
        s.model = (model == null || model.isBlank()) ? "claude-opus-4-8" : model;
        s.effort = (effort == null || effort.isBlank()) ? "high" : effort;
```

- [ ] **Step 3c: TaskService.create 배선** — `service/TaskService.java` (61-75)

`resolveMcpExtras` 다음, `Task.create` 호출 직전에 정책 적용 + 팩토리에 전달:
```java
        List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req.model());
        String effort = ModelEffortPolicy.resolveEffort(req.effort());
        ModelEffortPolicy.validate(model, effort);
        Task t = Task.create(req.githubRepo(), req.githubBranch(), req.title(),
                             req.description(), requesterId, maxRetry, extras, model, effort);
```

- [ ] **Step 3d: InterviewService.create 배선** — `service/InterviewService.java` (73-84)
```java
        List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req.model());
        String effort = ModelEffortPolicy.resolveEffort(req.effort());
        ModelEffortPolicy.validate(model, effort);
        InterviewSession s = InterviewSession.create(req.githubRepo(), req.githubBranch(),
                req.title(), req.description(), requesterId, extras, model, effort);
```

- [ ] **Step 3e: InterviewService.register 승계** — `service/InterviewService.java` (322-324)

`Task.create(...)` 호출 끝에 `s.getModel(), s.getEffort()` 추가:
```java
        Task t = Task.create(s.getGithubRepo(), s.getGithubBranch(), s.getTitle(),
                s.getDescription(), s.getRequesterId(), maxRetry,
                new ArrayList<>(s.getMcpsExtra() == null ? List.of() : s.getMcpsExtra()),
                s.getModel(), s.getEffort());
```

- [ ] **Step 3f: create() 직접 호출 테스트 헬퍼 갱신**

`InterviewServiceTest.session(...)` 헬퍼(약 78-83)의 `InterviewSession.create(...)`에 model/effort 추가:
```java
        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-4-8", "high");
```
또한 `grep -rn "InterviewSession.create(\|Task.create(" src/test`로 나오는 다른 직접 호출(예: `InterviewClaimConcurrencyTest`)에 `, "claude-opus-4-8", "high"` 추가. (main src의 `Task.create`/`InterviewSession.create` 호출은 Step 3c–3e에서 이미 갱신됨.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests '*InterviewServiceTest'`
Expected: PASS (기존 + 신규 3개).
Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 커밋**
```bash
git add src/main/java/com/hamonsoft/netismaker/entity/Task.java \
        src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java \
        src/main/java/com/hamonsoft/netismaker/service/TaskService.java \
        src/main/java/com/hamonsoft/netismaker/service/InterviewService.java \
        src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java \
        src/test
git commit -m "feat(model-effort): 엔티티 컬럼 + create 인자 + 서비스 정책 배선

Task·InterviewSession에 model/effort 컬럼·create 인자. TaskService/
InterviewService.create가 ModelEffortPolicy로 보정·검증 후 전달.
register가 인터뷰 model/effort를 구현 Task로 승계.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 백엔드 — Flyway V12 (두 테이블 컬럼 추가)

**Files:**
- Create: `src/main/resources/db/migration/V12__task_model_effort.sql`

- [ ] **Step 1: 마이그레이션 작성** (현재 최고 V11 → V12)
```sql
-- 작업 등록 시 선택한 Claude 모델 + effort. 기존 row 안전(NOT NULL DEFAULT, mcps_extra/env_vars 패턴).
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS model  VARCHAR(64) NOT NULL DEFAULT 'claude-opus-4-8',
    ADD COLUMN IF NOT EXISTS effort VARCHAR(16) NOT NULL DEFAULT 'high';

ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS model  VARCHAR(64) NOT NULL DEFAULT 'claude-opus-4-8',
    ADD COLUMN IF NOT EXISTS effort VARCHAR(16) NOT NULL DEFAULT 'high';
```

- [ ] **Step 2: 컴파일/리소스 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (마이그레이션 실행은 CI/런타임에서 검증; 로컬 docker 미실행.)

- [ ] **Step 3: 커밋**
```bash
git add src/main/resources/db/migration/V12__task_model_effort.sql
git commit -m "feat(model-effort): Flyway V12 — com.task·com.interview_session에 model/effort 컬럼

NOT NULL DEFAULT(claude-opus-4-8/high)로 기존 row 안전. 두 테이블 모두 추가.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 백엔드 — claim 페이로드에 model/effort

**Files:**
- Modify: `dto/WorkerTaskResponse.java`, `dto/InterviewClaimResponse.java`

- [ ] **Step 1: WorkerTaskResponse** — record에 필드 + analysis/implementation factory 매핑

record 컴포넌트 `List<EnvVar> envVars` 다음에 `String model, String effort` 추가. `forAnalysis(Task t)`·`forImplementation(Task t, TaskAnalysis a)`의 `new WorkerTaskResponse(...)` 끝에 `t.getModel(), t.getEffort()` 추가. `forDeploy`·`forUndeploy`는 claude 미실행이므로 `null, null` 전달:
```java
public record WorkerTaskResponse(
        Long id, String githubRepo, String githubBranch, String title, String description,
        Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
        String headBranch, String headSha, List<EnvVar> envVars,
        String model, String effort
) {
    public enum Kind { ANALYSIS, IMPLEMENTATION, DEPLOY, UNDEPLOY }

    public static WorkerTaskResponse forAnalysis(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.ANALYSIS,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                null, null, null, null, List.of(), t.getModel(), t.getEffort());
    }

    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(), a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of(), t.getModel(), t.getEffort());
    }

    public static WorkerTaskResponse forDeploy(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DEPLOY, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(),
                t.getEnvVars() == null ? List.of() : List.copyOf(t.getEnvVars()), null, null);
    }

    public static WorkerTaskResponse forUndeploy(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.UNDEPLOY, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(), List.of(), null, null);
    }
}
```

- [ ] **Step 2: InterviewClaimResponse** — record에 `String model, String effort` (turns 다음) + `of(...)` 매핑
```java
public record InterviewClaimResponse(
        long sessionId, String githubRepo, String githubBranch, String title, String description,
        String claudeSessionId, String currentPhase, String workDir, String lastAnswer,
        Integer replyToSeq, List<TaskMcpSpec> mcpsExtra, List<Turn> turns,
        String model, String effort
) {
    public record Turn(int seq, String role, String kind, String content, Integer replyToSeq) {}

    public static InterviewClaimResponse of(InterviewSession s, List<InterviewTurn> turns) {
        // ... lastAnswer/replyToSeq/mapped 계산 유지 ...
        return new InterviewClaimResponse(
                s.getId(), s.getGithubRepo(), s.getGithubBranch(), s.getTitle(), s.getDescription(),
                s.getClaudeSessionId(), s.getCurrentPhase(), s.getWorkDir(),
                lastAnswer, replyToSeq,
                s.getMcpsExtra() == null ? List.of() : List.copyOf(s.getMcpsExtra()),
                mapped, s.getModel(), s.getEffort());
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**
```bash
git add src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java \
        src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java
git commit -m "feat(model-effort): claim 페이로드에 model/effort 전달

WorkerTaskResponse(forAnalysis/forImplementation)·InterviewClaimResponse.of가
엔티티의 model/effort를 워커로 전달.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 백엔드 — 읽기 DTO에 model/effort (표시용)

**Files:**
- Modify: `dto/TaskResponse.java`, `dto/InterviewResponse.java`

- [ ] **Step 1: TaskResponse** — record에 `String model, String effort`(updatedAt 다음 등 적절한 위치) + `of(...)` 매핑 `t.getModel(), t.getEffort()`.

record 컴포넌트 목록의 `OffsetDateTime updatedAt` 다음에 추가하고, `of(Task t, TaskAnalysis a)`의 `new TaskResponse(...)`에 두 값 추가:
```java
public record TaskResponse(
        // ... 기존 컴포넌트 ...
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String model,
        String effort,
        AnalysisView analysis,
        ImplementationView implementation,
        DeploymentView deployment
) {
    // of(...) return의 ... t.getCreatedAt(), t.getUpdatedAt(), 다음에:
    //   t.getModel(), t.getEffort(), av, iv, dv);
}
```
> `new TaskResponse(...)` 인자 순서를 record 컴포넌트 순서와 정확히 일치시킬 것(model/effort를 updatedAt과 av 사이에 삽입).

- [ ] **Step 2: InterviewResponse** — record에 `String model, String effort`(taskId 다음) + `of(...)` 매핑
```java
public record InterviewResponse(
        Long id, String githubRepo, String githubBranch, String title, String description,
        String status, String currentPhase, String workDir, Long taskId,
        String model, String effort,
        List<TurnView> turns, PlanView plan, OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
    // of(...) return: ... s.getWorkDir(), s.getTaskId(), s.getModel(), s.getEffort(), tvs, pv, ...
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**
```bash
git add src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java \
        src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java
git commit -m "feat(model-effort): 읽기 DTO(TaskResponse/InterviewResponse)에 model/effort 노출

작업 상세·인터뷰에서 선택한 모델/effort 표시용.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 워커 CLI — ClaudeExecAdapter --model/--effort + WorkerMainLoop

**Files:**
- Modify: `workerdaemon/ClaudeExecAdapter.java`, `workerdaemon/WorkerMainLoop.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapterTest.java` (신규, 로컬)

- [ ] **Step 1: 실패 테스트 작성** — `ClaudeExecAdapterTest.java` (순수, 프로세스 미실행)
```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeExecAdapterTest {

    @Test
    void buildCommand_inserts_model_and_effort_after_p_before_mcp_args() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, "claude-opus-4-8", "high",
                List.of("--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly(
                "/bin/claude", "-p", "--model", "claude-opus-4-8", "--effort", "high",
                "--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_includes_skip_permissions_flag() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", true, "claude-sonnet-4-6", "medium", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly(
                "/bin/claude", "-p", "--dangerously-skip-permissions",
                "--model", "claude-sonnet-4-6", "--effort", "medium", "--allowedTools", "mcp__a");
    }

    @Test
    void buildCommand_omits_model_and_effort_when_blank() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, null, "  ", List.of("--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--allowedTools", "mcp__a");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*ClaudeExecAdapterTest'`
Expected: FAIL — `cannot find symbol: method buildCommand`.

- [ ] **Step 3: buildCommand 추출 + exec에 model/effort** — `ClaudeExecAdapter.java`

3a. 패키지-프라이빗 static `buildCommand` 추가(클래스 어디든, 예: exec 위):
```java
    /** claude 명령 조립. --model/--effort는 -p(및 skip) 뒤, mcpArgs(--allowedTools variadic) 앞. blank면 생략. */
    static List<String> buildCommand(String claudePath, boolean dangerouslySkipPermissions,
                                     String model, String effort, List<String> mcpArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(claudePath);
        cmd.add("-p");
        if (dangerouslySkipPermissions) cmd.add("--dangerously-skip-permissions");
        if (model != null && !model.isBlank()) { cmd.add("--model"); cmd.add(model); }
        if (effort != null && !effort.isBlank()) { cmd.add("--effort"); cmd.add(effort); }
        cmd.addAll(mcpArgs);
        return cmd;
    }
```

3b. 풀 overload(126-180)에 `String model, String effort` 파라미터 추가하고, 인라인 cmd 조립(136-140)을 buildCommand 호출로 교체:
```java
    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras,
                           boolean dangerouslySkipPermissions, String model, String effort)
            throws InterruptedException, IOException {
        long start = System.currentTimeMillis();
        WorkerMcpSupport.TaskClaudeArgs mcpArgs = mcp.buildClaudeArgsForTask(extras);
        try {
            List<String> cmd = buildCommand(resolvedClaudePath, dangerouslySkipPermissions,
                    model, effort, mcpArgs.args());
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workingDir)
                    .redirectErrorStream(true);
            // ... 기존 stdin write / waitFor / timeout / ExecResult 유지 ...
        } finally {
            // ... 기존 temp 정리 유지 ...
        }
    }
```

3c. 기존 delegating overload들이 새 풀 overload로 model/effort=null 전달하도록 갱신:
```java
    public ExecResult exec(String prompt, File workingDir, Duration timeout)
            throws InterruptedException, IOException {
        return exec(prompt, workingDir, timeout, List.of(), false, null, null);
    }
    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras)
            throws InterruptedException, IOException {
        return exec(prompt, workingDir, timeout, extras, false, null, null);
    }
    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras,
                           boolean dangerouslySkipPermissions) throws InterruptedException, IOException {
        return exec(prompt, workingDir, timeout, extras, dangerouslySkipPermissions, null, null);
    }
```

- [ ] **Step 4: WorkerMainLoop 2곳 전달** — `workerdaemon/WorkerMainLoop.java`

분석(131-134):
```java
            exec = claude.exec(prompt, repo.dir(), props.analysisTimeout(),
                    task.mcpsExtra() == null ? java.util.List.of() : task.mcpsExtra(),
                    false, task.model(), task.effort());
```
구현(195-199):
```java
            exec = claude.exec(prompt, wt.dir(), props.implementationTimeout(),
                    task.mcpsExtra() == null ? java.util.List.of() : task.mcpsExtra(),
                    true, task.model(), task.effort());
```

- [ ] **Step 5: 테스트 통과 + 컴파일**

Run: `./gradlew test --tests '*ClaudeExecAdapterTest'`
Expected: PASS (3 tests).
Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**
```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapter.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapterTest.java
git commit -m "feat(model-effort): 워커 claude 실행에 --model/--effort

ClaudeExecAdapter에 buildCommand 추출(테스트 가능) + model/effort 플래그(-p 뒤,
--allowedTools 앞, blank면 생략). WorkerMainLoop 분석/구현 2곳에서 task.model/effort 전달.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 인터뷰 SDK — model/effort 옵션 전달

**Files:**
- Modify: `netismaker-interview-service/src/types.ts`, `src/sdk/sessionOptions.ts`, `src/runner/interviewRunner.ts`
- Test: `netismaker-interview-service/test/sessionOptions.test.ts`

- [ ] **Step 1: 실패 테스트 작성** — `test/sessionOptions.test.ts`에 추가
```ts
  it('passes model and effort through to options when present', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, model: 'claude-sonnet-4-6', effort: 'medium' });
    expect(o.model).toBe('claude-sonnet-4-6');
    expect(o.effort).toBe('medium');
  });

  it('omits model/effort keys when absent', () => {
    const o = buildOptions({ ...base, claudeSessionId: null });
    expect('model' in o).toBe(false);
    expect('effort' in o).toBe(false);
  });
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/sessionOptions.test.ts`
Expected: FAIL — model/effort가 옵션에 없음.

- [ ] **Step 3: types.ts** — `InterviewClaimResponse`에 필드 추가(turns 다음, line 42 뒤):
```ts
    /** Prior turns (questions + answers) for context/logging */
    turns: InterviewTurn[];
    /** Java가 보내는 선택 모델/effort (NOT NULL — 기본 claude-opus-4-8/high). */
    model: string;
    effort: 'low' | 'medium' | 'high' | 'xhigh' | 'max';
  }
```

- [ ] **Step 4: sessionOptions.ts** — `SessionOptionsInput`에 옵션 필드 + buildOptions conditional-spread
```ts
export interface SessionOptionsInput {
  superpowersPluginPath: string;
  workDir: string;
  claudeCliPath: string;
  claudeSessionId: string | null;
  mcpsExtra?: unknown;
  /** 선택 모델 (blank/미지정이면 CLI 기본값). */
  model?: string;
  /** 추론 effort (blank/미지정이면 CLI 기본값). */
  effort?: string;
}
```
buildOptions return(49-58)에 spread 추가(mcpServers 다음, canUseTool 앞):
```ts
    ...(input.claudeSessionId ? { resume: input.claudeSessionId } : {}),
    ...(mcpServers ? { mcpServers } : {}),
    ...(input.model ? { model: input.model } : {}),
    ...(input.effort ? { effort: input.effort } : {}),
    canUseTool: buildCanUseTool(input.workDir),
```

- [ ] **Step 5: interviewRunner.ts** — buildOptions 호출 2곳(84-90, 115-121)에 전달
호출 1(84-90)에 추가:
```ts
      const options = buildOptions({
        superpowersPluginPath: this.deps.superpowersPluginPath,
        workDir: claim.workDir,
        claudeCliPath: this.deps.claudeCliPath,
        claudeSessionId: claim.claudeSessionId,
        mcpsExtra: claim.mcpsExtra,
        model: claim.model,
        effort: claim.effort,
      });
```
호출 2(115-121)에도 `model: claim.model, effort: claim.effort,` 추가(claudeSessionId는 기존 local `sessionId` 유지).

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd netismaker-interview-service && npx vitest run`
Expected: PASS (기존 + 신규 2). 타입 체크: `npx tsc -p tsconfig.json --noEmit` BUILD OK.

- [ ] **Step 7: 커밋**
```bash
git add netismaker-interview-service/src/types.ts \
        netismaker-interview-service/src/sdk/sessionOptions.ts \
        netismaker-interview-service/src/runner/interviewRunner.ts \
        netismaker-interview-service/test/sessionOptions.test.ts
git commit -m "feat(model-effort): 인터뷰 SDK가 claim의 model/effort를 옵션으로 전달

types InterviewClaimResponse + sessionOptions buildOptions(conditional-spread,
값 없으면 키 생략→CLI 기본값) + interviewRunner 2곳.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: 프론트 — modelEffort.ts (옵션·동적필터 순수 모듈)

**Files:**
- Create: `frontend/composables/modelEffort.ts`
- Test: `frontend/composables/modelEffort.spec.ts`

- [ ] **Step 1: 실패 테스트 작성** — `modelEffort.spec.ts`
```ts
import { describe, it, expect } from 'vitest'
import {
  MODEL_OPTIONS, DEFAULT_MODEL, DEFAULT_EFFORT, effortsForModel, coerceEffort,
} from './modelEffort'

describe('modelEffort', () => {
  it('defaults are opus-4-8 / high', () => {
    expect(DEFAULT_MODEL).toBe('claude-opus-4-8')
    expect(DEFAULT_EFFORT).toBe('high')
  })

  it('exposes the four models', () => {
    expect(MODEL_OPTIONS.map((m) => m.value)).toEqual([
      'claude-opus-4-8', 'claude-opus-4-7', 'claude-sonnet-4-6', 'claude-haiku-4-5',
    ])
  })

  it('haiku allows only low/medium/high; others allow all five', () => {
    expect(effortsForModel('claude-haiku-4-5')).toEqual(['low', 'medium', 'high'])
    expect(effortsForModel('claude-opus-4-8')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
    expect(effortsForModel('claude-sonnet-4-6')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
  })

  it('coerceEffort keeps a valid effort, downgrades an invalid one to high', () => {
    expect(coerceEffort('claude-opus-4-8', 'max')).toBe('max')
    expect(coerceEffort('claude-haiku-4-5', 'max')).toBe('high')  // max invalid for haiku
    expect(coerceEffort('claude-haiku-4-5', 'low')).toBe('low')
  })

  it('unknown model falls back to all five (server validates authoritatively)', () => {
    expect(effortsForModel('whatever')).toEqual(['low', 'medium', 'high', 'xhigh', 'max'])
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frontend && npx vitest run composables/modelEffort.spec.ts`
Expected: FAIL — `./modelEffort` 미존재.

- [ ] **Step 3: 최소 구현** — `frontend/composables/modelEffort.ts`
```ts
// 모델/effort 선택 옵션 + 모델별 허용 effort(단일 진실원천, 백엔드 ModelEffortPolicy와 동기화).
export const DEFAULT_MODEL = 'claude-opus-4-8'
export const DEFAULT_EFFORT = 'high'

export const MODEL_OPTIONS = [
  { label: 'Opus 4.8', value: 'claude-opus-4-8' },
  { label: 'Opus 4.7', value: 'claude-opus-4-7' },
  { label: 'Sonnet 4.6', value: 'claude-sonnet-4-6' },
  { label: 'Haiku 4.5', value: 'claude-haiku-4-5' },
]

const FULL = ['low', 'medium', 'high', 'xhigh', 'max']
const LIMITED = ['low', 'medium', 'high']

const MODEL_EFFORT_MAP: Record<string, string[]> = {
  'claude-opus-4-8': FULL,
  'claude-opus-4-7': FULL,
  'claude-sonnet-4-6': FULL,
  'claude-haiku-4-5': LIMITED,
}

/** 모델이 허용하는 effort 목록. 미지 모델은 FULL(서버가 권위 검증). */
export function effortsForModel(model: string): string[] {
  return MODEL_EFFORT_MAP[model] ?? FULL
}

/** 현재 effort가 모델에서 허용되면 유지, 아니면 기본값(high)으로 강등. */
export function coerceEffort(model: string, effort: string): string {
  return effortsForModel(model).includes(effort) ? effort : DEFAULT_EFFORT
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frontend && npx vitest run composables/modelEffort.spec.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: 커밋**
```bash
git -C /Users/micthebick/IdeaProjects/netisMaker add frontend/composables/modelEffort.ts frontend/composables/modelEffort.spec.ts
git -C /Users/micthebick/IdeaProjects/netisMaker commit -m "feat(model-effort): 프론트 modelEffort 옵션·동적필터 순수 모듈

MODEL_OPTIONS·모델별 허용 effort·effortsForModel/coerceEffort. 백엔드
ModelEffortPolicy와 동일 맵. 단위 테스트로 잠금.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: 프론트 — tasks/index.vue 폼 q-select + POST body + 패널 props

**Files:**
- Modify: `frontend/pages/tasks/index.vue`

- [ ] **Step 1: 스크립트 — import + draft + 동적 effort + openCreate + POST**

1a. `<script setup>` import 추가:
```ts
import { MODEL_OPTIONS, DEFAULT_MODEL, DEFAULT_EFFORT, effortsForModel, coerceEffort } from '~/composables/modelEffort'
```
1b. `draft`(line 50)에 model/effort 추가:
```ts
const draft = reactive({ githubRepo: '', githubBranch: '', title: '', description: '', model: DEFAULT_MODEL, effort: DEFAULT_EFFORT })
```
1c. 동적 effort 옵션 computed + 모델 변경 시 강등 watch (draft 근처):
```ts
const effortOptions = computed(() => effortsForModel(draft.model))
watch(() => draft.model, (m) => { draft.effort = coerceEffort(m, draft.effort) })
```
1d. `openCreate()`(237-249)에 기본값 reset 추가(`draft.description = ''` 다음):
```ts
  draft.model = DEFAULT_MODEL
  draft.effort = DEFAULT_EFFORT
```
1e. `startInterview()` POST body(297-303)와 `submit()` POST body(271-277) 양쪽에 추가(`mcpCatalogIds: selectedCatalogIds.value,` 다음):
```ts
        mcpCatalogIds: selectedCatalogIds.value,
        model: draft.model,
        effort: draft.effort,
```

- [ ] **Step 2: 템플릿 — q-select 2개 삽입** (description q-input 닫힘 line 574 뒤, MCP q-expansion-item line 576 앞)
```html
          <div class="row q-col-gutter-md">
            <div class="col">
              <q-select
                v-model="draft.model"
                :options="MODEL_OPTIONS"
                label="모델"
                emit-value
                map-options
                dense
                outlined
              />
            </div>
            <div class="col">
              <q-select
                v-model="draft.effort"
                :options="effortOptions"
                label="effort"
                dense
                outlined
                :hint="draft.model === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : ''"
              />
            </div>
          </div>
```
> effort 옵션은 문자열 배열(`effortOptions`)이라 emit-value/map-options 불필요(값=라벨).

- [ ] **Step 3: 템플릿 — InterviewPanel에 model/effort props 전달**

`<InterviewPanel ... :session-id="interviewSessionId" ...>` 사용처를 찾아 props 추가:
```html
            <InterviewPanel
              v-if="interviewSessionId"
              :session-id="interviewSessionId"
              :model="draft.model"
              :effort="draft.effort"
              ... (기존 @registered/@close 등 유지) ...
            />
```
(Task 11에서 InterviewPanel이 이 props로 칩 렌더.)

- [ ] **Step 4: 린트/타입 + 회귀**

Run: `cd frontend && npx vitest run`
Expected: 전체 PASS(회귀 없음; tasks/index.vue 단위 테스트 없음 — 로직은 Task 9 modelEffort로 검증).
> `npm run lint`는 이 레포에서 eslint 설정 부재로 동작 안 함(선재 조건) — 실행 불필요.

- [ ] **Step 5: 커밋**
```bash
git -C /Users/micthebick/IdeaProjects/netisMaker add frontend/pages/tasks/index.vue
git -C /Users/micthebick/IdeaProjects/netisMaker commit -m "feat(model-effort): 작업 등록 폼에 model/effort q-select + 동적 필터 + POST

모델 q-select + effort q-select(모델 변경 시 허용 목록 동적·비허용은 high로 강등),
draft/openCreate 기본값, startInterview/submit POST body, InterviewPanel props.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: 프론트 — 표시(작업 상세 + 인터뷰 패널 칩)

**Files:**
- Modify: `frontend/pages/tasks/[id].vue`, `frontend/components/InterviewPanel.vue`

- [ ] **Step 1: tasks/[id].vue — TaskResponse 인터페이스 + 칩**

1a. `TaskResponse` 인터페이스(44-63)에 추가(`updatedAt: string` 다음):
```ts
  model: string
  effort: string
```
1b. 헤더 상태 칩(line 269) 옆에 model/effort 칩 추가:
```html
        <span :class="statusClass(task.status)">{{ task.statusLabel }}</span>
        <q-chip dense size="sm" outline icon="smart_toy" :label="task.model" class="q-ml-sm" />
        <q-chip dense size="sm" outline icon="tune" :label="task.effort" />
```

- [ ] **Step 2: InterviewPanel.vue — props + 칩**

2a. `defineProps`(line 9)에 model/effort 추가:
```ts
const props = defineProps<{ sessionId: number; model?: string; effort?: string }>()
```
2b. status-bar의 status badge(line 180) 다음에 칩 추가:
```html
      <q-badge :color="status === 'PLAN_READY' ? 'positive' : 'primary'" :label="statusLabel" />
      <q-chip v-if="props.model" dense size="sm" outline icon="smart_toy" :label="props.model" />
      <q-chip v-if="props.effort" dense size="sm" outline icon="tune" :label="props.effort" />
```

- [ ] **Step 3: 회귀 확인**

Run: `cd frontend && npx vitest run`
Expected: 전체 PASS (InterviewPanel.spec.ts 포함 — 새 optional props는 기존 테스트 무영향).

- [ ] **Step 4: 커밋**
```bash
git -C /Users/micthebick/IdeaProjects/netisMaker add frontend/pages/tasks/\[id\].vue frontend/components/InterviewPanel.vue
git -C /Users/micthebick/IdeaProjects/netisMaker commit -m "feat(model-effort): 작업 상세·인터뷰 패널에 model/effort 칩 표시

TaskResponse 인터페이스 확장 + 상세 헤더 칩. InterviewPanel은 props로 칩 렌더.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: 풀스택 수동 스모크 + 최종 검증

**Files:** 없음 (실행 검증)

- [ ] **Step 1: 백엔드 단위 + 컴파일**

Run: `./gradlew test --tests '*ModelEffortPolicyTest' --tests '*ClaudeExecAdapterTest' --tests '*InterviewServiceTest'`
Expected: PASS.
Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: 프론트 + 인터뷰 SDK 전체 테스트**

Run: `cd frontend && npx vitest run` → 전체 PASS.
Run: `cd netismaker-interview-service && npx vitest run` → 전체 PASS.

- [ ] **Step 3: 풀스택 기동 + 시나리오** (PostgreSQL → netis-auth:9000 → api:8090 → 워커 → 프론트:3001)

1. 작업 등록 폼에서 **모델=Haiku 4.5 선택** → effort 드롭다운에 **xhigh/max가 사라지는지**, 현재 effort가 max였다면 high로 강등되는지 확인.
2. 모델=Opus 4.8 + effort=xhigh로 인터뷰 시작 → 인터뷰 패널 상단에 **모델/effort 칩**이 보이는지.
3. 인터뷰 진행 → 플랜완료 → '작업 등록' → 작업 상세에서 **모델/effort 칩**이 보이는지(인터뷰 선택값 승계).
4. (DB) `select model, effort from com.interview_session order by id desc limit 1;` 와 `com.task` 에 선택값이 저장됐는지.
5. (워커 로그/디버그) 분석/구현 시 `claude` 호출에 `--model <선택> --effort <선택>`이 들어갔는지 확인(`DEBUG`/명령 로깅 또는 `ps`).
6. (검증) 잘못된 조합을 강제로 POST(예: curl로 model=claude-haiku-4-5, effort=max) → **400** 반환 확인.
7. (회귀) model/effort 없이 보낸 레거시 요청 → 기본 opus-4-8/high로 동작.

- [ ] **Step 4: 최종 코드 리뷰** — 전체 브랜치 diff(`git diff origin/main HEAD -- src/ frontend/ netismaker-interview-service/`)에 대해 교차레이어 계약(폼→DTO→정책→엔티티→claim→워커/SDK) 일관성, register 승계, Flyway 양쪽 테이블, null/blank 가드를 검증. 발견 이슈는 해당 Task로 돌아가 수정.

---

## 자기 검토 (작성자 체크리스트 — 완료)

- **스펙 커버리지:** §4 정책→T1, §6.1 데이터→T3·T4, §6.2 API/검증→T1·T2·T3, §6.3 claim→T5, §6.4 워커CLI→T7, §6.5 SDK→T8, §6.6 프론트→T9·T10, §6.7 표시→T6·T11, §7 테스트→각 T+T12, §9 서브에이전트(현행 유지, 변경 없음), §10 YAGNI(env override·fallback-model 제외) 준수. 갭 없음.
- **플레이스홀더:** 없음 — 모든 step에 실제 코드/명령/기대출력.
- **타입 일관성:** `model`/`effort`(영문 모델 id·effort 문자열) 전 레이어 일관. `ModelEffortPolicy.resolveModel/resolveEffort/validate`·`effortsForModel/coerceEffort`·`buildCommand(claudePath,skip,model,effort,mcpArgs)` 시그니처 일관. Task.create/InterviewSession.create의 신규 인자 순서(...mcpsExtra, model, effort) 일관. claim DTO 필드명(model/effort) ↔ SDK types(model/effort) ↔ buildOptions 키(model/effort) 일치. 레코드 arity 변경(Create DTO·WorkerTaskResponse·InterviewClaimResponse·TaskResponse·InterviewResponse)에 따른 생성자/팩토리 호출 갱신을 각 Task에 명시.
