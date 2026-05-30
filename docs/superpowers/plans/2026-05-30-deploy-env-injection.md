# 배포 환경변수 주입 (env/DB injection) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포 task에 환경변수 key=value 목록을 UI에서 입력받아 컨테이너에 `docker run -e`로 주입한다(특히 DB 접속 정보·`JWT_SECRET`).

**Architecture:** `com.task.env_vars`(JSONB)에 `EnvVar(key,value,secret)` 리스트를 저장(mcps_extra 패턴). 배포 다이얼로그가 `POST /tasks/{id}/deploy` body로 env를 전달 → `TaskService`가 영속 → `WorkerTaskResponse.forDeploy`가 워커로 전달 → `DeployService`가 `DeploySpec.env` Map을 채움 → `LocalDockerTarget`이 기존 `-e KEY=VALUE` 주입 로직 사용. 시크릿은 평문 저장 + UI 마스킹.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Hibernate `@JdbcTypeCode(SqlTypes.JSON)` / Flyway / JUnit5+Mockito / Nuxt 3 + Quasar + Pinia.

**Spec:** `docs/superpowers/specs/2026-05-30-deploy-env-injection-design.md`

---

## File Structure

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `db/migration/V8__task_env_vars.sql` | env_vars 컬럼 추가 | 신규 |
| `entity/EnvVar.java` | JSONB 항목 record `(key,value,secret)` | 신규 |
| `entity/Task.java` | `envVars` 필드 매핑 | 수정 |
| `dto/DeployRequest.java` | 배포 요청 body `(envVars)` | 신규 |
| `service/TaskService.java` | `deploy/redeploy`가 envVars 영속 | 수정 |
| `controller/TaskController.java` | deploy/redeploy가 `@RequestBody` 수신 | 수정 |
| `dto/WorkerTaskResponse.java` | `envVars` 컴포넌트 + 팩토리 | 수정 |
| `workerdaemon/DeployService.java` | `DeploySpec.env` 채움 | 수정 |
| `dto/TaskResponse.java` | `envVars` 노출(상세 pre-fill용) | 수정 |
| `frontend/pages/tasks/[id].vue` | env 편집 다이얼로그 + 마스킹 | 수정 |
| 관련 테스트 | 단위 테스트 | 신규/수정 |

---

## Task 1: EnvVar record + V8 마이그레이션 + Task 엔티티 필드

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/entity/EnvVar.java`
- Create: `src/main/resources/db/migration/V8__task_env_vars.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java`

- [ ] **Step 1: `EnvVar` record 작성**

`src/main/java/com/hamonsoft/netismaker/entity/EnvVar.java`:

```java
package com.hamonsoft.netismaker.entity;

/**
 * 배포 task별 환경변수 (snapshot). task.env_vars (jsonb) 배열의 한 요소.
 *
 * secret=true는 UI 마스킹 표시 용도이며 저장/주입 동작에는 영향이 없다(평문 저장).
 */
public record EnvVar(String key, String value, boolean secret) {
    public EnvVar {
        if (key == null) key = "";
        if (value == null) value = "";
    }
}
```

- [ ] **Step 2: V8 마이그레이션 작성**

`src/main/resources/db/migration/V8__task_env_vars.sql`:

```sql
-- 배포 컨테이너에 주입할 환경변수 목록 (key/value/secret). mcps_extra와 동일 패턴.
-- 기존 row는 빈 배열로 안전 (NOT NULL DEFAULT).
ALTER TABLE com.task
    ADD COLUMN env_vars JSONB NOT NULL DEFAULT '[]'::jsonb;
```

- [ ] **Step 3: Task 엔티티에 envVars 필드 추가**

`src/main/java/com/hamonsoft/netismaker/entity/Task.java` — `mcpsExtra` 필드(라인 73-77) 바로 아래에 추가:

```java
    /** 배포 시 컨테이너에 주입할 환경변수 (key/value/secret). 배포 다이얼로그에서 set. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "env_vars", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<EnvVar> envVars = new ArrayList<>();
```

(이미 `import java.util.ArrayList; import java.util.List; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes;`가 존재하므로 import 추가 불필요. `Task.create(...)`는 변경하지 않는다.)

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/EnvVar.java \
        src/main/resources/db/migration/V8__task_env_vars.sql \
        src/main/java/com/hamonsoft/netismaker/entity/Task.java
git commit -m "feat: task env_vars 컬럼 + EnvVar 엔티티 (배포 환경변수 주입)"
```

---

## Task 2: TaskService deploy/redeploy가 envVars 영속

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java:217-236`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployTest.java`

- [ ] **Step 1: 실패 테스트 작성 — deploy가 envVars를 저장**

`TaskServiceDeployTest.java`의 `import java.util.List;` 아래에 `import com.hamonsoft.netismaker.entity.EnvVar;`를 추가하고, 클래스 안에 테스트 추가:

```java
    @Test
    void deploy_persists_env_vars() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        List<EnvVar> env = List.of(new EnvVar("JWT_SECRET", "s3cr3t", true));
        Task result = service.deploy(42L, "admin", env);
        assertThat(result.getStatus()).isEqualTo(TaskStatus.DEPLOY_PENDING);
        assertThat(result.getEnvVars()).hasSize(1);
        assertThat(result.getEnvVars().get(0).key()).isEqualTo("JWT_SECRET");
    }

    @Test
    void deploy_with_null_env_keeps_existing() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        t.setEnvVars(new java.util.ArrayList<>(List.of(new EnvVar("A", "1", false))));
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));
        Task result = service.deploy(42L, "admin", null);
        assertThat(result.getEnvVars()).hasSize(1);
        assertThat(result.getEnvVars().get(0).key()).isEqualTo("A");
    }
```

- [ ] **Step 2: 기존 deploy/redeploy 호출부를 3-arg로 수정**

같은 파일에서 기존 2-arg 호출을 모두 `null` 3번째 인자로 갱신:
- 라인 52: `Task result = service.deploy(42L, "admin");` → `Task result = service.deploy(42L, "admin", null);`
- 라인 62: `assertThatThrownBy(() -> service.deploy(42L, "admin"))` → `assertThatThrownBy(() -> service.deploy(42L, "admin", null))`
- 라인 71: `assertThat(service.redeploy(42L, "admin").getStatus())` → `assertThat(service.redeploy(42L, "admin", null).getStatus())`
- 라인 79: `assertThat(service.redeploy(42L, "admin").getStatus())` → `assertThat(service.redeploy(42L, "admin", null).getStatus())`

(`undeploy(42L, "admin")` 호출은 시그니처 변경 없음 — 그대로 둔다.)

- [ ] **Step 3: 테스트 실패 확인 (컴파일 에러)**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceDeployTest"`
Expected: 컴파일 실패 — `deploy(long,String,List)`/`redeploy(...)` 메서드 없음

- [ ] **Step 4: TaskService.deploy/redeploy 시그니처 변경**

`src/main/java/com/hamonsoft/netismaker/service/TaskService.java` 라인 216-236을 아래로 교체:

```java
    /** PR생성 → 배포대기. admin 한정. 워커가 다음 폴링에 claim해 배포 수행. */
    @Transactional
    public Task deploy(Long taskId, String adminId, java.util.List<com.hamonsoft.netismaker.entity.EnvVar> envVars) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.PR_CREATED) {
            throw TaskException.conflict("PR생성 상태에서만 배포할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (envVars != null) t.setEnvVars(new java.util.ArrayList<>(envVars));
        return toDeployPending(t, adminId, "관리자 배포 요청 → 배포 큐 진입");
    }

    /** 배포완료/배포실패 → 배포대기 (기존 컨테이너는 배포 시 stop 후 교체). */
    @Transactional
    public Task redeploy(Long taskId, String adminId, java.util.List<com.hamonsoft.netismaker.entity.EnvVar> envVars) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DEPLOYED && t.getStatus() != TaskStatus.DEPLOY_FAILED) {
            throw TaskException.conflict("배포완료/배포실패 상태에서만 재배포할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (envVars != null) t.setEnvVars(new java.util.ArrayList<>(envVars));
        return toDeployPending(t, adminId, "관리자 재배포 요청 → 배포 큐 진입");
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceDeployTest"`
Expected: PASS (기존 + 신규 2개)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/TaskService.java \
        src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployTest.java
git commit -m "feat: TaskService.deploy/redeploy가 envVars 영속"
```

---

## Task 3: DeployRequest DTO + TaskController body 수신

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/DeployRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java:100-116`

- [ ] **Step 1: DeployRequest record 작성**

`src/main/java/com/hamonsoft/netismaker/dto/DeployRequest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;

import java.util.List;

/** 배포/재배포 요청 body. 전체 또는 envVars가 null/빈 배열이면 기존 env 유지. */
public record DeployRequest(List<EnvVar> envVars) {}
```

- [ ] **Step 2: TaskController deploy/redeploy에 body 추가**

`src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` 라인 100-116을 교체. `deploy`/`redeploy` 메서드에 `@RequestBody(required = false) DeployRequest body`를 추가하고 서비스 호출에 `body == null ? null : body.envVars()` 전달:

```java
    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse deploy(@PathVariable Long id,
                               @org.springframework.web.bind.annotation.RequestBody(required = false)
                               com.hamonsoft.netismaker.dto.DeployRequest body,
                               JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.deploy(id, adminId, body == null ? null : body.envVars());
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    @PostMapping("/{id}/redeploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse redeploy(@PathVariable Long id,
                                 @org.springframework.web.bind.annotation.RequestBody(required = false)
                                 com.hamonsoft.netismaker.dto.DeployRequest body,
                                 JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.redeploy(id, adminId, body == null ? null : body.envVars());
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }
```

(`undeploy` 메서드 라인 118-125는 변경 없음.)

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/DeployRequest.java \
        src/main/java/com/hamonsoft/netismaker/controller/TaskController.java
git commit -m "feat: deploy/redeploy 엔드포인트가 envVars body 수신"
```

---

## Task 4: WorkerTaskResponse에 envVars 전달

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseEnvTest.java` (신규)

- [ ] **Step 1: 실패 테스트 작성 — forDeploy가 envVars를 담는다**

`src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseEnvTest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTaskResponseEnvTest {

    @Test
    void for_deploy_carries_env_vars() {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of());
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        t.setHeadBranch("netismaker/task-7");
        t.setHeadSha("abc1234");
        t.setEnvVars(new java.util.ArrayList<>(List.of(new EnvVar("JWT_SECRET", "x", true))));

        WorkerTaskResponse r = WorkerTaskResponse.forDeploy(t);

        assertThat(r.kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        assertThat(r.envVars()).hasSize(1);
        assertThat(r.envVars().get(0).key()).isEqualTo("JWT_SECRET");
    }

    @Test
    void for_analysis_has_empty_env_vars() {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of());
        ReflectionTestUtils.setField(t, "id", 8L);
        WorkerTaskResponse r = WorkerTaskResponse.forAnalysis(t);
        assertThat(r.envVars()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dto.WorkerTaskResponseEnvTest"`
Expected: 컴파일 실패 — `envVars()` 메서드 없음

- [ ] **Step 3: WorkerTaskResponse에 envVars 컴포넌트 추가**

`src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java` 전체를 교체:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.List;

/**
 * 워커가 작업을 claim했을 때 받는 페이로드.
 *
 *  kind=ANALYSIS       → 분석 prompt 실행 (analysis/headBranch/headSha null)
 *  kind=IMPLEMENTATION → worktree에서 구현 prompt 실행 (분석 산출물 동봉)
 *  kind=DEPLOY         → head 브랜치(headBranch@headSha)를 빌드해 docker 배포 (envVars 주입)
 *  kind=UNDEPLOY       → 컨테이너 netis-task-{id} 중지 (id만 사용)
 */
public record WorkerTaskResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        Kind kind,
        List<TaskMcpSpec> mcpsExtra,
        String analysisMarkdown,
        String subtasksJson,
        String headBranch,
        String headSha,
        List<EnvVar> envVars
) {
    public enum Kind { ANALYSIS, IMPLEMENTATION, DEPLOY, UNDEPLOY }

    public static WorkerTaskResponse forAnalysis(Task t) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.ANALYSIS,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                null, null, null, null, List.of()
        );
    }

    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(),
                a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of()
        );
    }

    public static WorkerTaskResponse forDeploy(Task t) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DEPLOY,
                List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(),
                t.getEnvVars() == null ? List.of() : List.copyOf(t.getEnvVars())
        );
    }

    public static WorkerTaskResponse forUndeploy(Task t) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.UNDEPLOY,
                List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(),
                List.of()
        );
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.dto.WorkerTaskResponseEnvTest"`
Expected: PASS (2개)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java \
        src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseEnvTest.java
git commit -m "feat: WorkerTaskResponse가 배포 envVars 전달"
```

---

## Task 5: DeployService가 DeploySpec.env를 채움

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java:73-82`

- [ ] **Step 1: env Map 구성 + DeploySpec에 주입**

`src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java`의 `deploy(...)` 안, `String shortSha = ...` 줄(라인 73)부터 `DeploySpec spec = ...` 블록(라인 75-81)을 아래로 교체:

```java
            String shortSha = task.headSha() == null ? "latest"
                    : task.headSha().substring(0, Math.min(7, task.headSha().length()));

            // env_vars → docker -e 주입용 Map. 빈 key는 제외. LocalDockerTarget이 spec.env()를 -e로 푼다.
            java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
            if (task.envVars() != null) {
                for (com.hamonsoft.netismaker.entity.EnvVar ev : task.envVars()) {
                    if (ev.key() != null && !ev.key().isBlank()) env.put(ev.key(), ev.value());
                }
            }
            log.append("[env 주입: ").append(env.size()).append("개 키]\n");

            DeployTarget.DeploySpec spec = new DeployTarget.DeploySpec(
                    wt.toPath(),
                    "netis-task-" + task.id() + ":" + shortSha,
                    "netis-task-" + task.id(),
                    containerPort,
                    env,
                    Map.of("netis-maker.task", String.valueOf(task.id())));
```

(`Map.of()` import는 그대로 사용. 시크릿 값 마스킹은 하지 않는다 — spec §8 결정.)

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java
git commit -m "feat: DeployService가 task envVars를 컨테이너에 주입"
```

---

## Task 6: TaskResponse가 envVars 노출

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java`

- [ ] **Step 1: import 추가**

`src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java` 상단 import에 추가:

```java
import com.hamonsoft.netismaker.entity.EnvVar;
```

- [ ] **Step 2: 레코드 컴포넌트에 envVars 추가**

`List<TaskMcpSpec> mcpsExtra,` 줄 바로 아래에 추가:

```java
        List<EnvVar> envVars,
```

- [ ] **Step 3: of()에서 envVars 전달**

`of(...)` 반환문에서 `t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),` 줄 바로 아래에 추가:

```java
                t.getEnvVars() == null ? List.of() : List.copyOf(t.getEnvVars()),
```

(컴포넌트 순서와 생성자 인자 순서가 일치해야 함 — mcpsExtra 다음, createdAt 앞.)

- [ ] **Step 4: 컴파일 + 전체 테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 통합 테스트 포함 green (TaskApiIntegrationTest가 TaskResponse를 역직렬화한다면 신규 필드는 무시되거나 빈 배열로 채워져 통과)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java
git commit -m "feat: TaskResponse가 envVars 노출 (배포 다이얼로그 pre-fill용)"
```

---

## Task 7: 프론트엔드 env 편집 다이얼로그 + 마스킹

**Files:**
- Modify: `frontend/pages/tasks/[id].vue`

> 이 프로젝트의 프론트는 단위 테스트 프레임워크가 없다. 검증은 `npm run lint` + 수동 동작 확인.

- [ ] **Step 1: EnvVar 인터페이스 + TaskResponse 필드 추가**

`frontend/pages/tasks/[id].vue` `<script setup>`의 `interface DeploymentView { ... }` 정의 위에 추가:

```ts
interface EnvVar {
  key: string
  value: string
  secret: boolean
}
```

그리고 `interface TaskResponse { ... }`의 `deployment: DeploymentView | null` 줄 위(또는 mcpsExtra 부근)에 추가:

```ts
  envVars: EnvVar[]
```

- [ ] **Step 2: 다이얼로그 상태 + 핸들러 추가**

`const $q = useQuasar()` 아래(또는 `deploy()` 함수 부근)에 추가:

```ts
const envDialog = ref(false)
const envMode = ref<'deploy' | 'redeploy'>('deploy')
const envRows = ref<EnvVar[]>([])
const envReveal = ref<Record<number, boolean>>({})

function openDeployDialog(mode: 'deploy' | 'redeploy') {
  envMode.value = mode
  envRows.value = (task.value?.envVars ?? []).map((e) => ({ ...e }))
  envReveal.value = {}
  envDialog.value = true
}

function addEnvRow() {
  envRows.value.push({ key: '', value: '', secret: false })
}

function removeEnvRow(i: number) {
  envRows.value.splice(i, 1)
}

async function submitDeploy() {
  const endpoint = envMode.value === 'deploy' ? 'deploy' : 'redeploy'
  const envVars = envRows.value
    .map((r) => ({ key: r.key.trim(), value: r.value, secret: r.secret }))
    .filter((r) => r.key !== '')
  try {
    await useApi(`/api/tasks/${taskId.value}/${endpoint}`, {
      method: 'POST',
      body: { envVars },
    })
    $q.notify({
      type: 'positive',
      message: envMode.value === 'deploy' ? '배포 큐 등록' : '재배포 큐 등록',
    })
    envDialog.value = false
    refresh()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '배포 실패' })
  }
}
```

- [ ] **Step 3: 기존 deploy()/redeploy() 함수 제거**

`async function deploy() { ... }` (라인 92-100)과 `async function redeploy() { ... }` (라인 102-110)을 삭제한다. (`undeploy()`는 유지.) 위 Step 2의 `openDeployDialog`/`submitDeploy`가 대체한다.

- [ ] **Step 4: 배포/재배포 버튼이 다이얼로그를 열도록 변경**

배포 카드 template에서:
- 라인 305 `@click="deploy"` → `@click="openDeployDialog('deploy')"`
- 라인 320 `@click="redeploy"` → `@click="openDeployDialog('redeploy')"`

- [ ] **Step 5: env 편집 다이얼로그 마크업 추가**

배포 `<q-card>` 닫는 태그(`</q-card>`, 라인 354) 바로 아래에 추가:

```vue
      <q-dialog v-model="envDialog">
        <q-card style="min-width: 480px; max-width: 90vw">
          <q-card-section class="row items-center">
            <div class="text-h6">
              {{ envMode === 'deploy' ? '배포' : '재배포' }} — 환경변수
            </div>
            <q-space />
            <q-btn flat round dense icon="close" v-close-popup />
          </q-card-section>
          <q-card-section class="text-caption text-grey-7">
            컨테이너에 <code>-e KEY=VALUE</code>로 주입됩니다. DB 접속 정보·시크릿을 여기에
            입력하세요. (예: <code>SPRING_DATASOURCE_URL</code>, <code>JWT_SECRET</code>)
            비밀 값은 마스킹 표시되지만 평문 저장됩니다.
          </q-card-section>
          <q-card-section class="q-gutter-sm">
            <div
              v-for="(row, i) in envRows"
              :key="i"
              class="row items-center q-gutter-xs no-wrap"
            >
              <q-input
                v-model="row.key"
                dense
                outlined
                placeholder="KEY"
                style="flex: 1"
              />
              <q-input
                v-model="row.value"
                dense
                outlined
                placeholder="value"
                style="flex: 2"
                :type="row.secret && !envReveal[i] ? 'password' : 'text'"
              >
                <template v-if="row.secret" #append>
                  <q-icon
                    :name="envReveal[i] ? 'visibility_off' : 'visibility'"
                    class="cursor-pointer"
                    @click="envReveal[i] = !envReveal[i]"
                  />
                </template>
              </q-input>
              <q-toggle v-model="row.secret" label="비밀" dense />
              <q-btn flat round dense icon="delete" color="grey" @click="removeEnvRow(i)" />
            </div>
            <q-btn flat dense icon="add" label="변수 추가" @click="addEnvRow" />
          </q-card-section>
          <q-card-actions align="right">
            <q-btn flat label="취소" v-close-popup />
            <q-btn
              unelevated
              color="primary"
              :label="envMode === 'deploy' ? '배포' : '재배포'"
              @click="submitDeploy"
            />
          </q-card-actions>
        </q-card>
      </q-dialog>
```

- [ ] **Step 6: (선택) 현재 env 요약 표시**

배포 카드 안, `deployLog` 섹션(라인 347-353) 위에 현재 저장된 env 요약을 추가(secret 마스킹):

```vue
        <q-separator v-if="task.envVars && task.envVars.length" />
        <q-card-section v-if="task.envVars && task.envVars.length">
          <div class="text-caption text-grey-7 q-mb-xs">환경변수</div>
          <div v-for="(e, i) in task.envVars" :key="i" class="text-body2">
            <code>{{ e.key }}</code> =
            <span v-if="e.secret" class="text-grey">••••••</span>
            <code v-else>{{ e.value }}</code>
          </div>
        </q-card-section>
```

- [ ] **Step 7: lint 통과 확인**

Run: `cd frontend && npm run lint`
Expected: 에러 없음 (Prettier/ESLint auto-fix 후 클린)

- [ ] **Step 8: Commit**

```bash
git add frontend/pages/tasks/\[id\].vue
git commit -m "feat: 배포 환경변수 편집 다이얼로그 + 시크릿 마스킹"
```

---

## Task 8: 전체 빌드 검증 + e2e 수동 확인

**Files:** 없음 (검증 전용)

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 신규/수정 단위 테스트 + 기존 통합 테스트 모두 green

- [ ] **Step 2: 스택 기동**

Run: `bash scripts/start-all.sh` (PostgreSQL/netis-auth 선행 가정)
Expected: API :8090, worker, nuxt :3001 기동. V8 마이그레이션 적용 로그 확인

- [ ] **Step 3: e2e — netis7.0에 DB+시크릿 주입 배포**

PR생성 상태의 netis7.0 task 상세에서 "배포" 클릭 → 다이얼로그에 입력:
```
SPRING_DATASOURCE_URL = jdbc:postgresql://host.docker.internal:5432/postgres
SPRING_DATASOURCE_USERNAME = postgres
SPRING_DATASOURCE_PASSWORD = ntflow   (비밀 토글 ON)
JWT_SECRET = <적절한 값>               (비밀 토글 ON)
```
→ 배포 → 워커가 claim → 헬스체크 grace(15s) 통과 시 **배포완료**.
Expected: 상태 `배포완료`, 접속 URL로 앱 응답. (MVP에서 H2 미존재로 크래시하던 케이스가 실제 DB 연결로 해소)

- [ ] **Step 4: 검증 결과 기록**

배포완료/접속 확인 후 옵시디언 진행 노트에 결과 한 줄 append.
배포 실패 시 `deploy_log`(컨테이너 로그)로 원인 진단 후 env 보정·재배포.

---

## 비고: 호출부 영향 점검

- `taskService.deploy(`/`redeploy(` 2-arg 호출부는 `TaskController`(Task 3에서 수정)와 `TaskServiceDeployTest`(Task 2에서 수정) 뿐. 다른 곳에서 호출하지 않음 — 변경 시 `grep -rn "\.deploy(\|\.redeploy(" src/`로 재확인.
- `WorkerTaskResponse` 팩토리(`forAnalysis/forImplementation/forDeploy/forUndeploy`)는 record 컴포넌트 추가 시 모두 Task 4에서 갱신됨. 직접 생성자 호출부는 없음.
