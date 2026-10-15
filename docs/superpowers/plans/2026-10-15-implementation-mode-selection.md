# 구현 방식 선택(직접·리뷰·SDD)·경과 시간·부분 작업 보존·구현 재시도 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인터뷰 플랜 확정 때 관리자가 작업마다 구현 방식(직접·리뷰·SDD)을 고르면 워커가 그 방식으로 구현하고, 구현중 경과 시간/상한 표시·중단 시 WIP 브랜치 보존·관리자 구현 재시도를 제공한다.

**Architecture:** API는 작업에 방식·시도 번호·시도별 상한을 저장하고(V29), 확정·재시도 API로 방식을 정하며, claim 응답의 `implementation` 블록과 결과 보고의 시도 번호로 워커와 주고받는다. 워커는 `claude -p` 한 세션에 방식별 지시(리뷰 절, 또는 SDD 절 + `--plugin-dir`)를 주고, 공통으로 에이전트 커밋 수용·git 작성자 env·임시 파일 exclude·중단 시 WIP push를 한다. 프론트는 확정 패널·재시도 다이얼로그의 방식 피커와 상세·목록의 경과 표시를 더한다.

**Tech Stack:** Spring Boot 3.4 (Java 21, JPA `ddl-auto: validate`, Flyway, JUnit 5 + Mockito + AssertJ) · Nuxt 3 + Quasar 2.19 (vitest + @vue/test-utils, happy-dom) · PowerShell 5.1 / bash 기동 스크립트

**Spec:** `docs/superpowers/specs/2026-10-15-implementation-mode-selection-design.md`

## Global Constraints

- 방식 값은 `DIRECT`·`REVIEW`·`SDD`(대소문자 구분, 공백은 무시), 화면 이름 "직접"·"리뷰"·"SDD". 화면 기본값 `REVIEW`, API는 값이 없으면 `DIRECT`, 모르는 값이면 400 "알 수 없는 구현 방식: …".
- 상한/판정(분): 직접 45/60, 리뷰 80/95, SDD 240/260 — 키 `app.task.implementation-timeout-minutes`·`implementation-review-timeout-minutes`·`implementation-sdd-timeout-minutes`, `app.task.implementation-stale-threshold-minutes`(기존)·`implementation-review-stale-threshold-minutes`·`implementation-sdd-stale-threshold-minutes`. 방식마다 `0 < 상한 < 판정`이 아니면 API가 뜨지 않는다.
- 워커 임시 파일: `.netis/`(계획 `.netis/plan/task-{id}-plan.md`, 설계 `.netis/plan/task-{id}-design.md`, 리뷰 메모 `.netis/review-notes.md`), `.design/`, `.netis-deploy-env.json` — `info/exclude`에 등록한다.
- 리뷰 메모는 PR/MR 본문 `## 리뷰 메모` 절에 20,000자까지(`…(이하 생략)`), `GitRemotes.mask` 적용, 심볼릭 링크는 읽지 않는다.
- 재시도 브랜치: 2회째부터 `{prefix}task-{id}-{slug}-r{n}`(slug가 비면 `{prefix}task-{id}-r{n}`).
- 직접 방식의 실제(yml) 프롬프트 문구는 바뀌지 않는다 — 커밋 문구는 `{commit_rule}` 치환값으로 그대로 들어간다.
- 리뷰 수정 범위 문구: "Critical·Important 가운데 계획 범위 안의 결함(깨짐·조용한 오작동·계획 미이행)만 … 계획에 없는 동작 규칙 변경(입력 검증 강화, 정규화 추가 등)은 하지 마세요".
- Java: 한국어 주석·커밋 메시지. 단위 테스트는 플레인 JUnit + Mockito(`@SpringBootTest` 통합 테스트는 `RUN_TESTCONTAINERS=true`일 때만 — 이 PC에는 Docker가 없어 CI에서만 돈다). 이 PC에서 `AttachmentCleanupJobTest`·`AttachmentStorageTest`의 심볼릭 링크 테스트 3건은 원래 실패한다(회귀 아님).
- Java 테스트 명령(Git Bash, worktree 루트에서): `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "<패키지 포함 클래스명>"` — `*Interview*` 같은 글롭은 쓰지 말 것(디렉터리로 확장돼 실패).
- 프론트: 작은따옴표·세미콜론 없음·2-space·trailing comma. `npm run lint-prettier`를 `frontend/` 전체에 돌리지 말 것. 클래스명에 Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`) 금지. `defineProps<T>()`의 T에 `~/`에서 가져온 타입을 쓰지 말 것(인라인 타입으로). `q-dialog` 안 콘텐츠 루트는 `<div>`(q-card는 div로 렌더된다).
- 프론트 테스트는 worktree의 `frontend/node_modules`를 메인 체크아웃 `frontend/node_modules`로의 **정션**으로 만든 뒤 `frontend`에서 `npx vitest run <파일>`. `npm ci`·`npm install`은 금지(라이브 node_modules가 바뀐다). worktree를 지우기 전에 `cmd //c "rmdir frontend\\node_modules"`로 정션만 먼저 지운다.
- 작업 위치는 worktree `C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\implementation-mode`(브랜치 `feature/implementation-mode`). 본 체크아웃은 라이브라 빌드·테스트 금지. 이 격리 세션은 git 텍스트가 섞인 복잡한 셸을 거부하므로 명령은 하나씩 단순하게 쓴다(`git add`와 `git commit`도 따로).
- 커밋 메시지: Write 도구로 `C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt`에 쓰고 `git commit -F`로 커밋한다. 내용 = 각 태스크 커밋 단계의 `# 제목:` 뒤 따옴표 안 문구(첫 줄) + 빈 줄 + 바뀐 것 한두 줄 + 빈 줄 + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **옛 API의 claim(`implementation` 블록 없음)** — 워커는 지금과 똑같이 직접·1회째·`IMPLEMENTATION_TIMEOUT`(45분)으로 돌아야 한다. → Task 13 `old_api_payload_runs_direct_first_attempt_with_the_worker_timeout`.
2. **재시도 직후 도착한 이전 시도의 늦은 보고** — 상태·PR 정보는 그대로, 이력과 사용량만 남아야 한다(구현실패 지각 정합화도 같은 시도일 때만). → Task 6 테스트 3건.
3. **리뷰 메모에 토큰이 섞인 URL·아주 긴 메모·심볼릭 링크** — PR/MR 본문에서 가려지고 20,000자에서 잘리며, 링크 대상은 읽히지 않아야 한다. → Task 10 `review_notes_are_masked_trimmed_and_capped`, `a_symlinked_review_notes_file_is_not_read`.
4. **계획의 코드 블록 안 `### 작업 N`(예시 마크다운)** — SDD용 변환이 건드리지 않고 Task 수에도 세지 않아야 한다. → Task 10 `korean_task_headings_outside_code_blocks_become_task_headings`.
5. **상한 값이 없는 구현중 작업**(새 API 배포 전에 claim된 작업) — 화면은 "N분 경과"만 보여 주고 깨지지 않아야 한다. → Task 15 `elapsedLabel` 테스트.

---

## File Structure

| 구분 | 파일 | 책임 |
|---|---|---|
| DB | `src/main/resources/db/migration/V29__task_implementation_mode.sql` (생성) | 방식·시도 번호·시도별 상한 컬럼 |
| API | `entity/ImplementationMode.java` (생성) | 방식 enum, 화면 이름, 워커용 관대 해석 |
| API | `entity/Task.java` (수정) | 필드 3개 |
| API | `service/ImplementationModePolicy.java` (생성) | 방식별 상한·판정 설정, 기동 검사, 요청 값 해석(400) |
| API | `controller/InterviewController.java`, `service/InterviewService.java` (수정) | 확정 바디의 방식 → 작업 저장·이력 |
| API | `dto/TaskResponse.java` (수정) | 응답 필드 4개 |
| API | `dto/WorkerTaskResponse.java` (수정) | claim의 `implementation` 블록 |
| API | `service/WorkerService.java` (수정) | claim 때 상한 기록, 시도 번호가 다른 늦은 보고 무시 |
| API | `service/StaleTaskRecoveryJob.java` (수정) | 방식별 구현중 멈춤 판정 |
| API | `dto/ImplementationRetryRequest.java` (생성), `service/TaskService.java`, `controller/TaskController.java` (수정) | 구현 재시도 |
| API | `dto/WorkerResultRequest.java` (수정) | 결과 보고의 시도 번호 |
| 워커 | `workerdaemon/ClaudeExecAdapter.java` (수정) | 추가 CLI 인자·자식 env, `modelUsage` 토큰 합산 |
| 워커 | `workerdaemon/GitOpsService.java` (수정) | 에이전트 커밋 수용, 부분 작업 WIP push |
| 워커 | `workerdaemon/WorktreeService.java` (수정) | 시도 번호 브랜치, 임시 파일 `info/exclude` |
| 워커 | `workerdaemon/ImplementationScratch.java` (생성) | `.netis/` 계획·설계 파일, 제목 변환, 리뷰 메모 회수, 임시 파일 삭제 |
| 워커 | `workerdaemon/SuperpowersPlugin.java` (생성), `workerdaemon/ImplementationModeResolver.java` (생성) | 플러그인 경로, 대신 진행 판정 |
| 워커 | `workerdaemon/ImplementationPrompts.java` (생성), `src/main/resources/prompts/implementation-review-section.md`·`implementation-reviewer.md` (생성) | 방식별 플랜 절·커밋 지시·PR 방식 줄 |
| 워커 | `workerdaemon/WorkerMainLoop.java`, `src/main/resources/application-worker.yml` (수정) | 구현 흐름 배선, `{commit_rule}`, 플러그인 키 |
| 스크립트 | `scripts/start-public.ps1`, `scripts/start-all.sh` (수정) | 워커에 `SUPERPOWERS_PLUGIN_PATH` 전달 |
| 프론트 | `composables/implementationMode.ts`·`composables/useNowTick.ts` (생성) | 방식 목록·이름·경과 문구, 30초 시계 |
| 프론트 | `components/ImplementationModePicker.vue`·`components/tasks/ImplementationRetryDialog.vue` (생성) | 방식 피커, 재시도 다이얼로그 |
| 프론트 | `components/InterviewPanel.vue`, `pages/tasks/[id].vue`, `pages/tasks/index.vue`, `components/tasks/TaskDetailMobile.vue`, `components/tasks/TaskCardCompact.vue`, `composables/taskStages.ts` (수정) | 확정·표시·재시도 배선 |
| 문서 | `CLAUDE.md` (수정) | 상태머신·구현 단계·코드 표·env |

---

### Task 0: 시작 준비 (코드 변경 없음)

- [ ] **Step 1: main 최신화** — 이 브랜치는 `7ca25a5`에서 갈라졌고 그 뒤 main에 PR #94(com."user" 자동 생성, 마이그레이션 없음)가 들어왔다. worktree 루트에서:

```bash
git fetch origin
git merge --no-edit origin/main
```

Expected: 충돌 없이 병합(이 브랜치에는 스펙·계획 문서만 있다). `src/main/resources/db/migration/`의 마지막 번호가 여전히 `V28`인지 확인한다 — 다른 번호가 생겼으면 Task 1의 파일명을 그다음 번호로 바꾼다.

- [ ] **Step 2: 프론트 테스트용 정션** (PowerShell, 한 번만):

```powershell
New-Item -ItemType Junction -Path "C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\implementation-mode\frontend\node_modules" -Target "C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules"
```

Expected: 정션 생성. (gitignore 대상이라 커밋에 안 들어간다. worktree를 지우기 전에 `cmd //c "rmdir frontend\\node_modules"`로 정션만 먼저 지울 것.)

---

### Task 1: 방식 enum·정책·DB 컬럼

**Files:**
- Create: `src/main/resources/db/migration/V29__task_implementation_mode.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/ImplementationMode.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java` (필드 추가 — `designRequested` 필드 바로 아래)
- Create: `src/main/java/com/hamonsoft/netismaker/service/ImplementationModePolicy.java`
- Modify: `src/main/resources/application.yml` (`app.task` 블록)
- Test: `src/test/java/com/hamonsoft/netismaker/entity/ImplementationModeTest.java`, `src/test/java/com/hamonsoft/netismaker/service/ImplementationModePolicyTest.java`

**Interfaces:**
- Produces: `enum ImplementationMode { DIRECT, REVIEW, SDD; String label(); static ImplementationMode fromWireOrDirect(String raw) }`
- Produces: `Task#getImplementationMode()/setImplementationMode(ImplementationMode)`, `getImplementationAttempt()/setImplementationAttempt(int)`, `getImplementationTimeoutMinutes()/setImplementationTimeoutMinutes(Integer)` — 새 작업 기본값 `DIRECT`·`1`·`null`
- Produces: `ImplementationModePolicy(int directTimeout, int reviewTimeout, int sddTimeout, int directStale, int reviewStale, int sddStale)`, `static ImplementationModePolicy defaults()`, `int timeoutMinutes(ImplementationMode)`, `int staleThresholdMinutes(ImplementationMode)`(null이면 DIRECT), `static ImplementationMode parse(String raw, ImplementationMode whenBlank)`(모르는 값 → `TaskException` 400)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/entity/ImplementationModeTest.java`:

```java
package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구현 방식 enum (스펙 2026-10-15 §4). */
class ImplementationModeTest {

    @Test
    void labels_are_the_screen_names() {
        assertThat(ImplementationMode.DIRECT.label()).isEqualTo("직접");
        assertThat(ImplementationMode.REVIEW.label()).isEqualTo("리뷰");
        assertThat(ImplementationMode.SDD.label()).isEqualTo("SDD");
    }

    @Test
    void wire_values_from_an_older_or_newer_api_fall_back_to_direct() {
        assertThat(ImplementationMode.fromWireOrDirect(null)).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("  ")).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("TURBO")).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("review")).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationMode.fromWireOrDirect("SDD")).isEqualTo(ImplementationMode.SDD);
        assertThat(ImplementationMode.fromWireOrDirect(" REVIEW ")).isEqualTo(ImplementationMode.REVIEW);
    }

    @Test
    void a_new_task_starts_direct_first_attempt_without_a_recorded_timeout() {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of(), null, null);
        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
    }
}
```

`src/test/java/com/hamonsoft/netismaker/service/ImplementationModePolicyTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 방식별 구현 상한·멈춤 판정 설정 (스펙 2026-10-15 §5.4). */
class ImplementationModePolicyTest {

    @Test
    void defaults_match_the_spec_table() {
        ImplementationModePolicy p = ImplementationModePolicy.defaults();
        assertThat(p.timeoutMinutes(ImplementationMode.DIRECT)).isEqualTo(45);
        assertThat(p.timeoutMinutes(ImplementationMode.REVIEW)).isEqualTo(80);
        assertThat(p.timeoutMinutes(ImplementationMode.SDD)).isEqualTo(240);
        assertThat(p.staleThresholdMinutes(ImplementationMode.DIRECT)).isEqualTo(60);
        assertThat(p.staleThresholdMinutes(ImplementationMode.REVIEW)).isEqualTo(95);
        assertThat(p.staleThresholdMinutes(ImplementationMode.SDD)).isEqualTo(260);
    }

    @Test
    void a_null_mode_is_treated_as_direct() {
        ImplementationModePolicy p = ImplementationModePolicy.defaults();
        assertThat(p.timeoutMinutes(null)).isEqualTo(45);
        assertThat(p.staleThresholdMinutes(null)).isEqualTo(60);
    }

    @Test
    void a_timeout_that_is_not_below_its_stale_threshold_refuses_to_boot() {
        assertThatThrownBy(() -> new ImplementationModePolicy(45, 95, 240, 60, 95, 260))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVIEW");
        assertThatThrownBy(() -> new ImplementationModePolicy(0, 80, 240, 60, 95, 260))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIRECT");
    }

    @Test
    void parse_uses_the_fallback_for_blank_and_rejects_unknown_values_with_400() {
        assertThat(ImplementationModePolicy.parse(null, ImplementationMode.DIRECT)).isEqualTo(ImplementationMode.DIRECT);
        assertThat(ImplementationModePolicy.parse(" ", null)).isNull();
        assertThat(ImplementationModePolicy.parse("SDD", ImplementationMode.DIRECT)).isEqualTo(ImplementationMode.SDD);
        assertThat(ImplementationModePolicy.parse(" REVIEW ", ImplementationMode.DIRECT)).isEqualTo(ImplementationMode.REVIEW);
        for (String bad : new String[]{"sdd", "FAST"}) {
            assertThatThrownBy(() -> ImplementationModePolicy.parse(bad, ImplementationMode.DIRECT))
                    .isInstanceOf(TaskException.class)
                    .hasMessageContaining(bad)
                    .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.entity.ImplementationModeTest" --tests "com.hamonsoft.netismaker.service.ImplementationModePolicyTest"`
Expected: 컴파일 실패 — `ImplementationMode`·`ImplementationModePolicy`·`getImplementationMode` 없음.

- [ ] **Step 3: 마이그레이션 작성**

`src/main/resources/db/migration/V29__task_implementation_mode.sql`:

```sql
-- 구현 방식 선택·구현 재시도 (스펙 2026-10-15 §4). 기존 작업은 직접·1회째 — 지금 동작 그대로.
-- implementation_timeout_minutes는 claim(구현대기 → 구현중) 때 그 방식의 상한을 기록한 값(그 전에는 NULL).
ALTER TABLE com.task
    ADD COLUMN implementation_mode            VARCHAR(10) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN implementation_attempt         INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN implementation_timeout_minutes INTEGER,
    ADD CONSTRAINT ck_task_implementation_mode    CHECK (implementation_mode IN ('DIRECT', 'REVIEW', 'SDD')),
    ADD CONSTRAINT ck_task_implementation_attempt CHECK (implementation_attempt >= 1);
```

- [ ] **Step 4: enum 작성**

`src/main/java/com/hamonsoft/netismaker/entity/ImplementationMode.java`:

```java
package com.hamonsoft.netismaker.entity;

/**
 * 구현 방식 (스펙 2026-10-15 §1). DB·API·워커가 enum 이름을 그대로 주고받는다.
 *  DIRECT — 한 세션이 계획대로 직접 구현(기존 동작)
 *  REVIEW — 직접 구현 + 마지막에 리뷰 서브에이전트 1회, 계획 범위 안 중대 문제만 수정
 *  SDD    — superpowers subagent-driven-development로 계획 작업마다 서브에이전트가 구현·리뷰
 */
public enum ImplementationMode {
    DIRECT("직접"),
    REVIEW("리뷰"),
    SDD("SDD");

    private final String label;

    ImplementationMode(String label) {
        this.label = label;
    }

    /** 화면·이력·PR 본문에 쓰는 이름. */
    public String label() {
        return label;
    }

    /** 워커가 claim 응답 값을 읽을 때 — null·공백·모르는 값(다른 버전 API)은 지금 동작인 DIRECT로 본다. */
    public static ImplementationMode fromWireOrDirect(String raw) {
        if (raw == null || raw.isBlank()) return DIRECT;
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return DIRECT;
        }
    }
}
```

- [ ] **Step 5: Task 엔티티 필드 추가**

`src/main/java/com/hamonsoft/netismaker/entity/Task.java` — `designRequested` 필드 선언(`private boolean designRequested;`) 바로 다음 줄에 추가:

```java

    /** 구현 방식 (스펙 2026-10-15 §4). 플랜 확정·구현 재시도 때 정한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "implementation_mode", nullable = false, length = 10)
    @Setter
    private ImplementationMode implementationMode = ImplementationMode.DIRECT;

    /** 구현 시도 번호 — 구현 재시도마다 +1. 2회째부터 브랜치 이름에 -rN, 결과 보고의 시도 번호와 대조한다. */
    @Column(name = "implementation_attempt", nullable = false)
    @Setter
    private int implementationAttempt = 1;

    /** 이번 시도의 구현 상한(분) — claim 때 방식별 설정값으로 기록, 화면 경과 표시용. 그 전에는 null. */
    @Column(name = "implementation_timeout_minutes")
    @Setter
    private Integer implementationTimeoutMinutes;
```

(`jakarta.persistence.*`를 이미 import하므로 `@Enumerated`·`EnumType`은 추가 import가 필요 없다. `ImplementationMode`는 같은 패키지다.)

- [ ] **Step 6: 정책 클래스 작성**

`src/main/java/com/hamonsoft/netismaker/service/ImplementationModePolicy.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 방식별 구현 상한·멈춤 판정 (스펙 2026-10-15 §5.4). 상한은 claim 때 작업에 기록해 워커에 보내고(워커가 claude를 끊는 시간),
 * 판정은 StaleTaskRecoveryJob이 쓴다. 워커가 먼저 끊고 보고할 수 있게 방식마다 0 < 상한 < 판정이어야 하며,
 * 아니면 API가 뜨지 않는다.
 */
@Component
@Profile("api")
public class ImplementationModePolicy {

    private final Map<ImplementationMode, Integer> timeouts = new EnumMap<>(ImplementationMode.class);
    private final Map<ImplementationMode, Integer> staleThresholds = new EnumMap<>(ImplementationMode.class);

    public ImplementationModePolicy(
            @Value("${app.task.implementation-timeout-minutes:45}") int directTimeout,
            @Value("${app.task.implementation-review-timeout-minutes:80}") int reviewTimeout,
            @Value("${app.task.implementation-sdd-timeout-minutes:240}") int sddTimeout,
            @Value("${app.task.implementation-stale-threshold-minutes:60}") int directStale,
            @Value("${app.task.implementation-review-stale-threshold-minutes:95}") int reviewStale,
            @Value("${app.task.implementation-sdd-stale-threshold-minutes:260}") int sddStale) {
        timeouts.put(ImplementationMode.DIRECT, directTimeout);
        timeouts.put(ImplementationMode.REVIEW, reviewTimeout);
        timeouts.put(ImplementationMode.SDD, sddTimeout);
        staleThresholds.put(ImplementationMode.DIRECT, directStale);
        staleThresholds.put(ImplementationMode.REVIEW, reviewStale);
        staleThresholds.put(ImplementationMode.SDD, sddStale);
        for (ImplementationMode m : ImplementationMode.values()) {
            int timeout = timeouts.get(m);
            int stale = staleThresholds.get(m);
            if (timeout <= 0 || timeout >= stale) {
                throw new IllegalStateException("구현 상한은 0보다 크고 멈춤 판정보다 작아야 한다 ("
                        + m.name() + ": 상한 " + timeout + "분, 판정 " + stale + "분)");
            }
        }
    }

    /** 설정 없이 쓰는 기본값(45/80/240, 60/95/260) — setter 주입 전 단위 테스트용. */
    public static ImplementationModePolicy defaults() {
        return new ImplementationModePolicy(45, 80, 240, 60, 95, 260);
    }

    public int timeoutMinutes(ImplementationMode mode) {
        return timeouts.get(mode == null ? ImplementationMode.DIRECT : mode);
    }

    public int staleThresholdMinutes(ImplementationMode mode) {
        return staleThresholds.get(mode == null ? ImplementationMode.DIRECT : mode);
    }

    /** 확정·재시도 바디 값 해석 — null·공백이면 whenBlank, enum 이름(대소문자 구분)이 아니면 400. */
    public static ImplementationMode parse(String raw, ImplementationMode whenBlank) {
        if (raw == null || raw.isBlank()) return whenBlank;
        try {
            return ImplementationMode.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "알 수 없는 구현 방식: " + raw);
        }
    }
}
```

- [ ] **Step 7: 설정 키 추가**

`src/main/resources/application.yml`의 `app.task` 블록에서 `implementation-stale-threshold-minutes: 60` 줄 바로 다음에 추가(들여쓰기 4칸, 기존 줄과 같게):

```yaml
    implementation-review-stale-threshold-minutes: 95   # 방식별 구현중 멈춤 판정 — 직접은 위 값 (스펙 2026-10-15 §5.4)
    implementation-sdd-stale-threshold-minutes: 260
    implementation-timeout-minutes: 45                  # 방식별 워커 claude 상한 — claim 때 작업에 기록해 보낸다
    implementation-review-timeout-minutes: 80
    implementation-sdd-timeout-minutes: 240
```

- [ ] **Step 8: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.entity.ImplementationModeTest" --tests "com.hamonsoft.netismaker.service.ImplementationModePolicyTest"`
Expected: PASS (7 tests)

- [ ] **Step 9: 커밋**

```bash
git add src/main/resources/db/migration/V29__task_implementation_mode.sql src/main/java/com/hamonsoft/netismaker/entity/ImplementationMode.java src/main/java/com/hamonsoft/netismaker/entity/Task.java src/main/java/com/hamonsoft/netismaker/service/ImplementationModePolicy.java src/main/resources/application.yml src/test/java/com/hamonsoft/netismaker/entity/ImplementationModeTest.java src/test/java/com/hamonsoft/netismaker/service/ImplementationModePolicyTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(api): 구현 방식 enum·방식별 상한 정책·V29 컬럼 (스펙 2026-10-15 §4·§5.4)"
```

---

### Task 2: 플랜 확정에 구현 방식

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (`confirm`)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java` (`confirm`, `InterviewConfirmRequest`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceConfirmModeTest.java` (생성)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java` (테스트 1개 추가 — CI 전용)

**Interfaces:**
- Consumes: `ImplementationMode`, `ImplementationModePolicy.parse` (Task 1)
- Produces: `InterviewService#confirm(Long sessionId, String adminId, boolean designRequested, ImplementationMode mode)` — 기존 3인자 `confirm`은 `DIRECT`로 위임
- Produces: `InterviewController.InterviewConfirmRequest(Boolean designRequested, String implementationMode)`

- [ ] **Step 1: 실패하는 단위 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/InterviewServiceConfirmModeTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 플랜 확정이 고른 구현 방식을 작업에 저장하고 이력 사유에 남긴다 (스펙 2026-10-15 §5.1). */
class InterviewServiceConfirmModeTest {

    private TaskStatusHistoryRepository historyRepo;
    private InterviewService service;
    private Task task;

    @BeforeEach
    void setUp() {
        InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
        InterviewPlanRepository planRepo = mock(InterviewPlanRepository.class);
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new InterviewService(sessionRepo, mock(InterviewTurnRepository.class), planRepo,
                taskRepo, analysisRepo, historyRepo, mock(TaskAttachmentRepository.class),
                mock(AttachmentStorage.class), mock(TaskStageUsageRepository.class),
                mock(QuestionAttachmentRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(analysisRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        InterviewSession s = InterviewSession.create("owner/repo", "main", "T", "d", "u1",
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(s, "id", 25L);
        s.setStatus(InterviewStatus.PLAN_READY);
        s.setTaskId(13L);
        when(sessionRepo.findByIdForUpdate(25L)).thenReturn(Optional.of(s));

        task = Task.create("owner/repo", "main", "T", "d", "u1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(task, "id", 13L);
        task.setStatus(TaskStatus.INTERVIEW_REVIEW);
        when(taskRepo.findActiveByIdForUpdate(13L)).thenReturn(Optional.of(task));
        when(planRepo.findById(25L)).thenReturn(Optional.of(
                InterviewPlan.create(25L, "# 설계", "# 플랜", "[]", 1000L, null)));
    }

    private String lastHistoryReason() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void confirm_stores_the_chosen_mode_and_names_it_in_history() {
        service.confirm(25L, "admin", false, ImplementationMode.REVIEW);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(task.getImplementationAttempt()).isEqualTo(1);
        assertThat(lastHistoryReason()).isEqualTo("플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 리뷰)");
    }

    @Test
    void the_design_queue_keeps_the_mode_for_the_later_implementation() {
        service.confirm(25L, "admin", true, ImplementationMode.SDD);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DESIGN_PENDING);
        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.SDD);
    }

    @Test
    void the_three_argument_confirm_and_a_null_mode_stay_direct() {
        service.confirm(25L, "admin", false);

        assertThat(task.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
        assertThat(lastHistoryReason()).endsWith("구현 방식: 직접)");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.InterviewServiceConfirmModeTest"`
Expected: 컴파일 실패 — 4인자 `confirm` 없음.

- [ ] **Step 3: 서비스 구현**

`InterviewService.java` — `confirm` 메서드 머리를 바꾼다. 기존:

```java
    @Transactional
    public Long confirm(Long sessionId, String adminId, boolean designRequested) {
        InterviewSession s = requireSessionForUpdate(sessionId);
```

새로:

```java
    @Transactional
    public Long confirm(Long sessionId, String adminId, boolean designRequested) {
        return confirm(sessionId, adminId, designRequested, ImplementationMode.DIRECT);
    }

    /** mode: 확정 때 고른 구현 방식 — null이면 직접 (스펙 2026-10-15 §5.1). */
    @Transactional
    public Long confirm(Long sessionId, String adminId, boolean designRequested, ImplementationMode mode) {
        InterviewSession s = requireSessionForUpdate(sessionId);
```

같은 메서드 안에서 기존:

```java
        t.setDesignRequested(designRequested);
```

새로:

```java
        t.setDesignRequested(designRequested);
        t.setImplementationMode(mode == null ? ImplementationMode.DIRECT : mode);
```

그리고 이력 사유 기존:

```java
                "플랜 확정 → " + to.dbValue() + " (interview_session " + sessionId + ")"));
```

새로:

```java
                "플랜 확정 → " + to.dbValue() + " (interview_session " + sessionId
                        + ", 구현 방식: " + t.getImplementationMode().label() + ")"));
```

(`InterviewService`는 `com.hamonsoft.netismaker.entity.*`를 import하므로 추가 import 없음.)

- [ ] **Step 4: 컨트롤러 구현**

`InterviewController.java`의 `confirm` 본문 기존:

```java
        Long taskId = interviewService.confirm(id, adminId,
                body != null && Boolean.TRUE.equals(body.designRequested()));
```

새로(잘못된 방식은 상태를 건드리기 전에 400):

```java
        ImplementationMode mode = ImplementationModePolicy.parse(
                body == null ? null : body.implementationMode(), ImplementationMode.DIRECT);
        Long taskId = interviewService.confirm(id, adminId,
                body != null && Boolean.TRUE.equals(body.designRequested()), mode);
```

레코드 기존:

```java
    /** confirm 요청 바디 — designRequested 미지정 시 false 취급. */
    public record InterviewConfirmRequest(Boolean designRequested) {}
```

새로:

```java
    /**
     * confirm 요청 바디 — designRequested 미지정 시 false, implementationMode 미지정 시 DIRECT
     * (옛 화면·다른 호출자는 지금과 같음, 스펙 2026-10-15 §5.1).
     */
    public record InterviewConfirmRequest(Boolean designRequested, String implementationMode) {}
```

import 추가: `com.hamonsoft.netismaker.entity.ImplementationMode`, `com.hamonsoft.netismaker.service.ImplementationModePolicy`.

- [ ] **Step 5: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.InterviewServiceConfirmModeTest" --tests "com.hamonsoft.netismaker.service.InterviewServiceTest"`
Expected: PASS

- [ ] **Step 6: HTTP 계약 통합 테스트 추가 (CI 전용)**

`InterviewApiIntegrationTest.java`의 `등록시_designRequested를_넘기면_task에_반영된다` 테스트 바로 아래에 추가:

```java
    @Test
    void 등록시_implementationMode를_넘기면_task에_반영되고_모르는_값은_400() throws Exception {
        long sid = startSession("user1", "제목");
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1"));
        mvc.perform(post("/worker/interviews/" + sid + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[]\",\"costUsd\":0.1,\"durationMs\":1000}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/interviews/" + sid + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"implementationMode\":\"FAST\"}"))
                .andExpect(status().isBadRequest());

        String resp = mvc.perform(post("/api/interviews/" + sid + "/confirm").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"implementationMode\":\"SDD\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long taskId = json.readTree(resp).get("taskId").asLong();

        assertThat(taskRepo.findById(taskId).orElseThrow().getImplementationMode())
                .isEqualTo(com.hamonsoft.netismaker.entity.ImplementationMode.SDD);
    }
```

로컬에서는 `RUN_TESTCONTAINERS`가 없어 건너뛴다(컴파일만 확인). CI가 돌린다.

- [ ] **Step 7: 컴파일 확인 후 커밋**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java src/test/java/com/hamonsoft/netismaker/service/InterviewServiceConfirmModeTest.java src/test/java/com/hamonsoft/netismaker/controller/InterviewApiIntegrationTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(api): 플랜 확정 바디에 구현 방식 — 작업 저장·이력 사유 (스펙 2026-10-15 §5.1)"
```

---

### Task 3: 작업 응답·claim 페이로드·claim 때 상한 기록

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (setter 주입 + `claimNextTask`)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/TaskResponseImplementationModeTest.java` (생성)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseImplementationTest.java` (생성)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceImplementationPlanTest.java` (테스트 2개 추가)

**Interfaces:**
- Consumes: `Task` 필드 3개, `ImplementationModePolicy` (Task 1)
- Produces: `TaskResponse` 컴포넌트 끝에 `String implementationMode, int implementationAttempt, OffsetDateTime claimedAt, Integer implementationTimeoutMinutes`
- Produces: `WorkerTaskResponse.ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes)`와 정규 컴포넌트 끝의 `ImplementationSpec implementation`(IMPLEMENTATION만 non-null)
- Produces: `WorkerService#setImplementationModePolicy(ImplementationModePolicy)`(package-private, `@Autowired`) — claim이 `APPROVED → IMPLEMENTING`일 때 `task.implementationTimeoutMinutes = policy.timeoutMinutes(mode)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/dto/TaskResponseImplementationModeTest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 작업 응답의 구현 방식·시도 번호·시작 시각·상한 (스펙 2026-10-15 §5.2). */
class TaskResponseImplementationModeTest {

    private static final OffsetDateTime CLAIMED = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");

    private static Task implementing() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.IMPLEMENTING);
        t.setImplementationMode(ImplementationMode.SDD);
        t.setImplementationAttempt(2);
        t.setImplementationTimeoutMinutes(240);
        t.setClaimedAt(CLAIMED);
        return t;
    }

    @Test
    void exposes_mode_attempt_claim_time_and_the_recorded_timeout() {
        TaskResponse r = TaskResponse.of(implementing(), null);

        assertThat(r.implementationMode()).isEqualTo("SDD");
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(r.claimedAt()).isEqualTo(CLAIMED);
        assertThat(r.implementationTimeoutMinutes()).isEqualTo(240);
    }

    @Test
    void list_cost_and_viewer_redaction_keep_the_new_fields() {
        TaskResponse r = TaskResponse.of(implementing(), null);
        for (TaskResponse copy : List.of(TaskResponse.withTotalCost(r, BigDecimal.ONE), r.redactSecretValues())) {
            assertThat(copy.implementationMode()).isEqualTo("SDD");
            assertThat(copy.implementationAttempt()).isEqualTo(2);
            assertThat(copy.claimedAt()).isEqualTo(CLAIMED);
            assertThat(copy.implementationTimeoutMinutes()).isEqualTo(240);
        }
    }

    @Test
    void a_task_never_claimed_for_implementation_is_direct_without_a_timeout() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), null, null);
        ReflectionTestUtils.setField(t, "id", 8L);

        TaskResponse r = TaskResponse.of(t, null);

        assertThat(r.implementationMode()).isEqualTo("DIRECT");
        assertThat(r.implementationAttempt()).isEqualTo(1);
        assertThat(r.implementationTimeoutMinutes()).isNull();
    }
}
```

`src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseImplementationTest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** claim 응답의 구현 지시 블록 (스펙 2026-10-15 §5.3). */
class WorkerTaskResponseImplementationTest {

    private final ObjectMapper lenient = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Task task() {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setImplementationMode(ImplementationMode.REVIEW);
        t.setImplementationAttempt(3);
        t.setImplementationTimeoutMinutes(80);
        return t;
    }

    @Test
    void implementation_claims_carry_mode_attempt_and_timeout_through_db_grant_and_json() throws Exception {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(task(), null, null, "# 플랜");

        assertThat(r.implementation()).isEqualTo(new WorkerTaskResponse.ImplementationSpec("REVIEW", 3, 80));
        assertThat(r.withDbMcp(new DbMcpGrant(null, List.of(), List.of())).implementation())
                .isEqualTo(r.implementation());
        WorkerTaskResponse back = lenient.readValue(lenient.writeValueAsString(r), WorkerTaskResponse.class);
        assertThat(back.implementation()).isEqualTo(r.implementation());
        assertThat(back.planMarkdown()).isEqualTo("# 플랜");
    }

    @Test
    void other_kinds_and_old_payloads_have_no_implementation_block() throws Exception {
        assertThat(WorkerTaskResponse.forDeploy(task()).implementation()).isNull();
        WorkerTaskResponse old = lenient.readValue(
                "{\"id\":7,\"githubRepo\":\"acme/widgets\",\"githubBranch\":\"main\",\"kind\":\"IMPLEMENTATION\"}",
                WorkerTaskResponse.class);
        assertThat(old.implementation()).isNull();
    }
}
```

`WorkerServiceImplementationPlanTest.java` — import에 `com.hamonsoft.netismaker.entity.ImplementationMode`를 더하고 클래스 끝에 추가:

```java
    @Test
    void implementation_claim_records_and_carries_the_policy_timeout_for_the_mode() {
        Task t = approvedTask();
        t.setImplementationMode(ImplementationMode.SDD);
        t.setImplementationAttempt(2);
        when(sessionRepo.findByTaskIdOrderByCreatedAtDesc(3L)).thenReturn(List.of());

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(t.getImplementationTimeoutMinutes()).isEqualTo(240);
        assertThat(r.implementation()).isEqualTo(new WorkerTaskResponse.ImplementationSpec("SDD", 2, 240));
    }

    @Test
    void the_injected_policy_decides_the_timeout() {
        service.setImplementationModePolicy(new ImplementationModePolicy(30, 70, 200, 60, 95, 260));
        Task t = approvedTask();
        t.setImplementationMode(ImplementationMode.REVIEW);
        when(sessionRepo.findByTaskIdOrderByCreatedAtDesc(3L)).thenReturn(List.of());

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(r.implementation().timeoutMinutes()).isEqualTo(70);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.dto.TaskResponseImplementationModeTest" --tests "com.hamonsoft.netismaker.dto.WorkerTaskResponseImplementationTest" --tests "com.hamonsoft.netismaker.service.WorkerServiceImplementationPlanTest"`
Expected: 컴파일 실패 — `implementationMode()`·`implementation()`·`ImplementationSpec`·`setImplementationModePolicy` 없음.

- [ ] **Step 3: TaskResponse 구현**

1. 레코드 컴포넌트 끝 — 기존:

```java
        /** 승인 때 고른 DB 연결 id (스펙 2026-10-14 §4). */
        List<Long> dbConnectionIds
) {
```

새로:

```java
        /** 승인 때 고른 DB 연결 id (스펙 2026-10-14 §4). */
        List<Long> dbConnectionIds,
        /** 구현 방식 DIRECT|REVIEW|SDD (스펙 2026-10-15 §5.2). */
        String implementationMode,
        /** 구현 시도 번호(1부터). */
        int implementationAttempt,
        /** 워커가 마지막으로 가져간 시각 — 결과 보고 뒤에도 남으므로 화면은 구현중일 때만 쓴다. */
        OffsetDateTime claimedAt,
        /** 이번 시도의 구현 상한(분) — claim 때 기록, 그 전에는 null. */
        Integer implementationTimeoutMinutes
) {
```

2. `withTotalCost`의 마지막 인자 `r.dbConnectionIds()` 뒤에 `,`를 붙이고 다음 4줄을 넣는다:

```java
                r.implementationMode(),
                r.implementationAttempt(),
                r.claimedAt(),
                r.implementationTimeoutMinutes()
```

3. `redactSecretValues()`의 생성자 호출 마지막 줄 — 기존:

```java
                attachments, stageUsage, totalCostUsd, totalTokens, close, repoCatalogId, dbConnectionIds);
```

새로:

```java
                attachments, stageUsage, totalCostUsd, totalTokens, close, repoCatalogId, dbConnectionIds,
                implementationMode, implementationAttempt, claimedAt, implementationTimeoutMinutes);
```

4. `build(...)`의 마지막 인자 — 기존:

```java
                t.getDbConnectionIds() == null ? List.of() : List.copyOf(t.getDbConnectionIds())
        );
```

새로:

```java
                t.getDbConnectionIds() == null ? List.of() : List.copyOf(t.getDbConnectionIds()),
                (t.getImplementationMode() == null ? ImplementationMode.DIRECT : t.getImplementationMode()).name(),
                t.getImplementationAttempt(),
                t.getClaimedAt(),
                t.getImplementationTimeoutMinutes()
        );
```

import 추가: `com.hamonsoft.netismaker.entity.ImplementationMode`.

- [ ] **Step 4: WorkerTaskResponse 구현**

1. 정규 컴포넌트 끝 — 기존:

```java
        /** IMPLEMENTATION·DESIGN 전용: DB MCP 지시 (스펙 2026-10-14 §6.4). WorkerController가 claim 커밋 뒤 채운다. */
        DbMcpGrant dbMcp
) {
```

새로:

```java
        /** IMPLEMENTATION·DESIGN 전용: DB MCP 지시 (스펙 2026-10-14 §6.4). WorkerController가 claim 커밋 뒤 채운다. */
        DbMcpGrant dbMcp,
        /**
         * IMPLEMENTATION 전용: 구현 방식·시도 번호·상한 (스펙 2026-10-15 §5.3). 다른 kind·구버전 API는 null —
         * 워커는 직접·1회째·자기 설정 상한으로 돈다.
         */
        ImplementationSpec implementation
) {
    /** 구현 지시 블록. mode는 ImplementationMode 이름, timeoutMinutes는 claim 때 작업에 기록한 상한. */
    public record ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes) {}

    /** 구현 지시(implementation) 도입 전 29개 인자 정규 생성자 — 기존 팩토리·테스트 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact, DbMcpGrant dbMcp) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, dbMcp, null);
    }
```

(기존 23·27·28인자 생성자는 그대로 둔다 — 28인자 생성자의 `this(... closeArtifact, null)`은 이제 위 29인자 생성자로 이어진다.)

2. `withDbMcp` — 기존 본문 끝 `gitUrl, repoHost, planMarkdown, prNumber, prUrl, closeProgress, closeContext, closeArtifact, grant);`를:

```java
                gitUrl, repoHost, planMarkdown, prNumber, prUrl, closeProgress, closeContext, closeArtifact, grant,
                implementation);
```

3. `forImplementation(Task t, TaskAnalysis a, TaskDesign d, String planMarkdown)` 본문을 다음으로 바꾼다:

```java
    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d, String planMarkdown) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(), a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of(), t.getModel(), t.getEffort(),
                d == null ? null : d.getDesignMarkdown(),
                d == null ? null : d.getMockupFilesJson(),
                null,
                d == null ? null : d.getDesignUrl(),
                null, null, t.getGitUrl(), hostOf(t), planMarkdown,
                null, null, null, null, null, null,
                implementationSpecOf(t));
    }

    private static ImplementationSpec implementationSpecOf(Task t) {
        ImplementationMode mode = t.getImplementationMode() == null ? ImplementationMode.DIRECT : t.getImplementationMode();
        return new ImplementationSpec(mode.name(), t.getImplementationAttempt(), t.getImplementationTimeoutMinutes());
    }
```

import 추가: `com.hamonsoft.netismaker.entity.ImplementationMode`.

- [ ] **Step 5: WorkerService — 정책 주입과 claim 때 상한 기록**

`setEvents` setter 바로 아래에 추가:

```java

    /** 방식별 구현 상한 (스펙 2026-10-15 §5.4). 생성자를 바꾸지 않으려고 setter 주입 — 단위 테스트에서는 기본값. */
    private ImplementationModePolicy implementationModePolicy = ImplementationModePolicy.defaults();

    @Autowired
    void setImplementationModePolicy(ImplementationModePolicy policy) {
        this.implementationModePolicy = policy;
    }
```

`claimNextTask`의 기존:

```java
        } else if (from == TaskStatus.APPROVED) {
            t.setStatus(TaskStatus.IMPLEMENTING);
```

새로:

```java
        } else if (from == TaskStatus.APPROVED) {
            t.setStatus(TaskStatus.IMPLEMENTING);
            // 이번 시도의 상한을 기록 — claim 응답과 작업 화면이 같은 값을 쓴다 (스펙 2026-10-15 §5.3)
            t.setImplementationTimeoutMinutes(implementationModePolicy.timeoutMinutes(t.getImplementationMode()));
```

- [ ] **Step 6: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.dto.*" --tests "com.hamonsoft.netismaker.service.WorkerServiceImplementationPlanTest"`
Expected: PASS (기존 dto 테스트 포함)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/test/java/com/hamonsoft/netismaker/dto/TaskResponseImplementationModeTest.java src/test/java/com/hamonsoft/netismaker/dto/WorkerTaskResponseImplementationTest.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceImplementationPlanTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(api): 작업 응답·claim에 구현 방식·시도 번호·상한 — claim 때 상한 기록 (스펙 2026-10-15 §5.2·§5.3)"
```

---

### Task 4: 방식별 구현중 멈춤 판정

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java`

**Interfaces:**
- Consumes: `ImplementationModePolicy#staleThresholdMinutes` (Task 1), `Task#getImplementationMode` (Task 1)
- Produces: 생성자 `StaleTaskRecoveryJob(TaskRepository, TaskStatusHistoryRepository, WorkerHeartbeatRepository, DeployLogStreamService, ImplementationModePolicy)` — `implementationStaleThresholdMinutes` 필드는 없어진다

- [ ] **Step 1: 테스트 수정·추가**

`StaleTaskRecoveryJobTest.setUp()`에서 기존:

```java
        job = new StaleTaskRecoveryJob(taskRepo, historyRepo, heartbeatRepo, deployLogStream);
        ReflectionTestUtils.setField(job, "analysisStaleThresholdMinutes", 5);
        ReflectionTestUtils.setField(job, "implementationStaleThresholdMinutes", 60);
```

새로(구현 판정은 정책이 맡는다):

```java
        job = new StaleTaskRecoveryJob(taskRepo, historyRepo, heartbeatRepo, deployLogStream,
                ImplementationModePolicy.defaults());
        ReflectionTestUtils.setField(job, "analysisStaleThresholdMinutes", 5);
```

import에 `com.hamonsoft.netismaker.entity.ImplementationMode`를 더하고 클래스 끝에 추가:

```java
    @Test
    void an_sdd_implementation_gets_the_longer_ceiling() {
        Task t = task(10L, TaskStatus.IMPLEMENTING, "w1", 100, 0);
        t.setImplementationMode(ImplementationMode.SDD);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5)));

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
    }

    @Test
    void a_review_implementation_past_its_ceiling_is_recovered() {
        Task t = task(11L, TaskStatus.IMPLEMENTING, "w1", 100, 0);
        t.setImplementationMode(ImplementationMode.REVIEW);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5)));

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getFailureReason()).contains("처리 시간 초과(95분)");
    }

    @Test
    void an_sdd_implementation_past_260_minutes_is_recovered() {
        Task t = task(12L, TaskStatus.IMPLEMENTING, "w1", 261, 0);
        t.setImplementationMode(ImplementationMode.SDD);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5)));

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getFailureReason()).contains("처리 시간 초과(260분)");
    }

    @Test
    void a_direct_implementation_keeps_the_60_minute_ceiling() {
        Task t = task(13L, TaskStatus.IMPLEMENTING, "w1", 61, 0);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5)));

        job.recoverStale();

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getFailureReason()).contains("처리 시간 초과(60분)");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.StaleTaskRecoveryJobTest"`
Expected: 컴파일 실패 — 5인자 생성자 없음.

- [ ] **Step 3: 구현**

`StaleTaskRecoveryJob.java`:
1. 다음 필드 선언 3줄(주석 없는 `@Value` + 필드)을 지운다:

```java
    @Value("${app.task.implementation-stale-threshold-minutes:60}")
    private int implementationStaleThresholdMinutes;
```

2. 생성자를 바꾼다:

```java
    private final ImplementationModePolicy implementationModePolicy;

    public StaleTaskRecoveryJob(TaskRepository taskRepo,
                                TaskStatusHistoryRepository historyRepo,
                                WorkerHeartbeatRepository heartbeatRepo,
                                DeployLogStreamService deployLogStream,
                                ImplementationModePolicy implementationModePolicy) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.deployLogStream = deployLogStream;
        this.implementationModePolicy = implementationModePolicy;
    }
```

3. `ceilingMin` 계산의 구현 줄 — 기존 `case IMPLEMENTING -> implementationStaleThresholdMinutes;`를:

```java
                // 방식별 판정 — SDD는 몇 시간 돈다 (스펙 2026-10-15 §5.4)
                case IMPLEMENTING -> implementationModePolicy.staleThresholdMinutes(t.getImplementationMode());
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.StaleTaskRecoveryJobTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(api): 구현중 멈춤 판정을 방식별로(60/95/260분) (스펙 2026-10-15 §5.4)"
```

---

### Task 5: 구현 재시도 API

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/ImplementationRetryRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java` (`retryImplementation` — `retry` 메서드 바로 아래)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` (엔드포인트 — `retry` 엔드포인트 바로 아래)
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceImplementationRetryTest.java` (생성)
- Test: `src/test/java/com/hamonsoft/netismaker/controller/TaskImplementationRetryApiIntegrationTest.java` (생성 — CI 전용)

**Interfaces:**
- Consumes: `ImplementationModePolicy.parse` (Task 1), `Task` 필드 (Task 1)
- Produces: `TaskService#retryImplementation(Long taskId, String adminId, String rawMode) → Task`
- Produces: `POST /api/tasks/{id}/implementation/retry` (ROLE_ADMIN) 바디 `ImplementationRetryRequest(String implementationMode)` → `TaskResponse`

- [ ] **Step 1: 실패하는 단위 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/TaskServiceImplementationRetryTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 구현실패 → 구현대기, 방식 다시 고르기 (스펙 2026-10-15 §5.5). */
class TaskServiceImplementationRetryTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), mock(RepoCatalogService.class), new ObjectMapper(),
                mock(InterviewService.class), mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class), mock(DbConnectionService.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task failed(ImplementationMode mode) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setImplementationMode(mode);
        t.setFailureReason("claude exec 실패: claude timeout after PT4H");
        t.setHeadBranch("netismaker/task-42-t");
        t.setHeadSha("abc1234");
        t.setImplementationLog("log");
        t.setImplementationTimeoutMinutes(240);
        t.setWorkerId("w1");
        t.setClaimedAt(OffsetDateTime.now());
        when(taskRepo.findActiveByIdForUpdate(42L)).thenReturn(Optional.of(t));
        return t;
    }

    private String historyReason() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void retry_bumps_the_attempt_requeues_and_clears_the_failed_attempt() {
        Task t = failed(ImplementationMode.REVIEW);

        service.retryImplementation(42L, "admin", null);

        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
        assertThat(t.getImplementationAttempt()).isEqualTo(2);
        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getHeadBranch()).isNull();
        assertThat(t.getHeadSha()).isNull();
        assertThat(t.getImplementationLog()).isNull();
        assertThat(t.getImplementationTimeoutMinutes()).isNull();
        assertThat(t.getWorkerId()).isNull();
        assertThat(t.getClaimedAt()).isNull();
        assertThat(historyReason())
                .isEqualTo("구현 재시도 (2회째, 구현 방식: 리뷰, 이전 브랜치: netismaker/task-42-t)");
    }

    @Test
    void retry_can_switch_the_mode() {
        Task t = failed(ImplementationMode.SDD);
        t.setHeadBranch(null);

        service.retryImplementation(42L, "admin", "REVIEW");

        assertThat(t.getImplementationMode()).isEqualTo(ImplementationMode.REVIEW);
        assertThat(historyReason()).isEqualTo("구현 재시도 (2회째, 구현 방식: SDD → 리뷰)");
    }

    @Test
    void only_failed_implementations_can_be_retried() {
        Task t = failed(ImplementationMode.DIRECT);
        t.setStatus(TaskStatus.PR_CREATED);

        assertThatThrownBy(() -> service.retryImplementation(42L, "admin", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(t.getImplementationAttempt()).isEqualTo(1);
    }

    @Test
    void an_unknown_mode_is_400_before_touching_the_task() {
        assertThatThrownBy(() -> service.retryImplementation(42L, "admin", "FAST"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(taskRepo, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void a_missing_or_deleted_task_is_404() {
        when(taskRepo.findActiveByIdForUpdate(43L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryImplementation(43L, "admin", null))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceImplementationRetryTest"`
Expected: 컴파일 실패 — `retryImplementation` 없음.

- [ ] **Step 3: DTO·서비스 구현**

`src/main/java/com/hamonsoft/netismaker/dto/ImplementationRetryRequest.java`:

```java
package com.hamonsoft.netismaker.dto;

/** 구현 재시도 바디 (스펙 2026-10-15 §5.5). implementationMode가 null·공백이면 지금 방식 유지. */
public record ImplementationRetryRequest(String implementationMode) {}
```

`TaskService.java` — `retry(...)` 메서드 바로 아래에 추가(`ImplementationMode`·`ImplementationModePolicy` import가 없으면 추가: `com.hamonsoft.netismaker.entity.ImplementationMode`, 같은 패키지인 `ImplementationModePolicy`는 import 불필요):

```java
    /**
     * 구현실패 → 구현대기 (스펙 2026-10-15 §5.5). 관리자 한정(컨트롤러 @PreAuthorize). 시도 번호를 올려 워커가 베이스부터
     * 새 브랜치(-rN)로 다시 구현한다 — 이어하기 아님. rawMode가 null·공백이면 지금 방식 유지, 모르는 값이면 400.
     * 이전 실패 사유는 직전 이력에, 이전 브랜치는 이 이력 사유에 남는다.
     */
    @Transactional
    public Task retryImplementation(Long taskId, String adminId, String rawMode) {
        ImplementationMode requested = ImplementationModePolicy.parse(rawMode, null);
        // 비관적 락(FOR UPDATE) — 연타 재시도의 시도 번호 이중 증가와 claim 경합 방지.
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.IMPLEMENTATION_FAILED) {
            throw TaskException.conflict("구현실패 상태에서만 구현을 재시도할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        ImplementationMode before = t.getImplementationMode();
        ImplementationMode after = requested == null ? before : requested;
        String previousBranch = t.getHeadBranch();
        TaskStatus from = t.getStatus();

        t.setImplementationAttempt(t.getImplementationAttempt() + 1);
        t.setImplementationMode(after);
        t.setStatus(TaskStatus.APPROVED);
        t.setFailureReason(null);
        t.setHeadBranch(null);
        t.setHeadSha(null);
        t.setPrUrl(null);
        t.setPrNumber(null);
        t.setImplementationLog(null);
        t.setImplementationTimeoutMinutes(null);
        t.setWorkerId(null);
        t.setClaimedAt(null);
        t.setUpdatedAt(OffsetDateTime.now());

        String modeText = before == after ? after.label() : before.label() + " → " + after.label();
        String reason = "구현 재시도 (" + t.getImplementationAttempt() + "회째, 구현 방식: " + modeText
                + (previousBranch == null || previousBranch.isBlank() ? "" : ", 이전 브랜치: " + previousBranch) + ")";
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.APPROVED, "user", adminId, reason));
        return t;
    }
```

- [ ] **Step 4: 컨트롤러 구현**

`TaskController.java` — `retry` 엔드포인트 바로 아래에 추가(import: `com.hamonsoft.netismaker.dto.ImplementationRetryRequest`):

```java
    /** 구현실패 → 구현대기, 방식 다시 고르기 (스펙 2026-10-15 §5.5). */
    @PostMapping("/{id}/implementation/retry")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse retryImplementation(@PathVariable Long id,
                                            @RequestBody(required = false) ImplementationRetryRequest body,
                                            JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.retryImplementation(id, adminId, body == null ? null : body.implementationMode());
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }
```

- [ ] **Step 5: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.TaskServiceImplementationRetryTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: HTTP 통합 테스트 (CI 전용)**

`src/test/java/com/hamonsoft/netismaker/controller/TaskImplementationRetryApiIntegrationTest.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/tasks/{id}/implementation/retry — 관리자 한정, 시도 번호·방식 반영 (스펙 2026-10-15 §5.5). */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskImplementationRetryApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long failedTask() {
        Task t = taskService.create(new TaskCreateRequest(1L, "main", "재시도", "설명"), "user1");
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setHeadBranch("netismaker/task-" + t.getId() + "-x");
        taskRepo.save(t);
        return t.getId();
    }

    @Test
    void only_admins_can_retry() throws Exception {
        long id = failedTask();
        mvc.perform(post("/api/tasks/" + id + "/implementation/retry").with(userJwt("user1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_retry_requeues_with_the_new_mode_and_attempt() throws Exception {
        long id = failedTask();
        mvc.perform(post("/api/tasks/" + id + "/implementation/retry").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"implementationMode\":\"SDD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.implementationMode").value("SDD"))
                .andExpect(jsonPath("$.implementationAttempt").value(2));
    }

    @Test
    void an_unknown_mode_is_400() throws Exception {
        long id = failedTask();
        mvc.perform(post("/api/tasks/" + id + "/implementation/retry").with(adminJwt("admin"))
                        .contentType(APPLICATION_JSON).content("{\"implementationMode\":\"FAST\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 7: 컴파일 확인 후 커밋**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/ImplementationRetryRequest.java src/main/java/com/hamonsoft/netismaker/service/TaskService.java src/main/java/com/hamonsoft/netismaker/controller/TaskController.java src/test/java/com/hamonsoft/netismaker/service/TaskServiceImplementationRetryTest.java src/test/java/com/hamonsoft/netismaker/controller/TaskImplementationRetryApiIntegrationTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(api): 구현 재시도 — 구현실패 → 구현대기, 시도 번호 증가·방식 다시 고르기 (스펙 2026-10-15 §5.5)"
```

---

### Task 6: 결과 보고의 시도 번호 — 이전 시도의 늦은 보고 무시

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (`recordResult` 맨 앞)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceImplementationAttemptTest.java` (생성)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestJsonTest.java` (테스트 1개 추가)

**Interfaces:**
- Consumes: `Task#getImplementationAttempt` (Task 1)
- Produces: `WorkerResultRequest` 정규 컴포넌트 끝 `Integer implementationAttempt`, 기존 23인자 생성자(호환), `WorkerResultRequest#withImplementationAttempt(Integer) → WorkerResultRequest`, `masked()`가 시도 번호 보존

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/WorkerServiceImplementationAttemptTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 재시도 뒤 이전 시도의 늦은 보고는 상태를 바꾸지 않는다 (스펙 2026-10-15 §5.6). */
class WorkerServiceImplementationAttemptTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskStageUsageRepository stageUsageRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        stageUsageRepo = mock(TaskStageUsageRepository.class);
        service = new WorkerService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                mock(RepoCatalogRepository.class), historyRepo, stageUsageRepo,
                mock(WorkerHeartbeatRepository.class), mock(DeployLogStreamService.class),
                mock(InterviewSessionRepository.class), mock(InterviewPlanRepository.class));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task task(TaskStatus status, int attempt) {
        Task t = Task.create("acme/widgets", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(status);
        t.setImplementationAttempt(attempt);
        t.setWorkerId("w1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        return t;
    }

    private static WorkerResultRequest prCreated(Integer attempt) {
        return new WorkerResultRequest("w1", TaskStatus.PR_CREATED,
                null, null, null, 10L, null,
                "https://github.com/acme/widgets/pull/9", 9, "netismaker/task-7-t", "abc1234", "log",
                null, null, null, null, null,
                null, null, null, null,
                new WorkerResultRequest.UsageReport(new BigDecimal("1.50"), 100L, 50L, 0L, 0L))
                .withImplementationAttempt(attempt);
    }

    private static WorkerResultRequest implementationFailed(Integer attempt) {
        return new WorkerResultRequest("w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "claude exit=1",
                null, null, "netismaker/task-7-t", null, null,
                null, null, null, null, null,
                null, null, null, null, null)
                .withImplementationAttempt(attempt);
    }

    private String lastHistoryReason() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void a_late_report_from_an_earlier_attempt_only_leaves_history_and_usage() {
        Task t = task(TaskStatus.IMPLEMENTING, 2);

        service.recordResult(7L, prCreated(1));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
        assertThat(t.getPrUrl()).isNull();
        assertThat(lastHistoryReason()).isEqualTo("이전 시도(1회째)의 늦은 보고 무시: PR생성");
        verify(stageUsageRepo).accumulate(eq(7L), eq(TaskStageUsage.STAGE_IMPLEMENTATION), any(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void a_late_failure_after_the_retry_was_queued_is_accepted_quietly() {
        Task t = task(TaskStatus.APPROVED, 2);

        service.recordResult(7L, implementationFailed(1));   // 예전에는 409 → dead-letter

        assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
    }

    @Test
    void stale_reconciliation_only_happens_for_the_same_attempt() {
        Task t = task(TaskStatus.IMPLEMENTATION_FAILED, 2);

        service.recordResult(7L, prCreated(1));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(t.getPrUrl()).isNull();
    }

    @Test
    void the_current_attempt_is_recorded_as_before() {
        Task t = task(TaskStatus.IMPLEMENTING, 2);

        service.recordResult(7L, prCreated(2));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/acme/widgets/pull/9");
    }

    @Test
    void an_old_worker_without_an_attempt_keeps_the_existing_behavior() {
        Task t = task(TaskStatus.IMPLEMENTING, 2);

        service.recordResult(7L, prCreated(null));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
    }
}
```

`WorkerResultRequestJsonTest.java` 끝에 추가:

```java
    @Test
    void the_implementation_attempt_survives_json_and_masking_and_old_reports_read_null() throws Exception {
        var lenient = json.copy().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        WorkerResultRequest req = WorkerResultRequest.closed("w1", null).withImplementationAttempt(3);

        WorkerResultRequest back = lenient.readValue(json.writeValueAsString(req), WorkerResultRequest.class);

        assertThat(back.implementationAttempt()).isEqualTo(3);
        assertThat(req.masked().implementationAttempt()).isEqualTo(3);
        assertThat(json.readValue("{\"workerId\":\"w1\",\"status\":\"PR_CREATED\"}", WorkerResultRequest.class)
                .implementationAttempt()).isNull();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceImplementationAttemptTest" --tests "com.hamonsoft.netismaker.dto.WorkerResultRequestJsonTest"`
Expected: 컴파일 실패 — `withImplementationAttempt`·`implementationAttempt()` 없음.

- [ ] **Step 3: WorkerResultRequest 구현**

1. 정규 컴포넌트 끝 — 기존:

```java
        // 구현 성공 시 추출한 배포 env 템플릿 (스펙 2026-10-07 §5 — null이면 미보고/구버전 워커)
        List<EnvTemplateItem> envTemplate
) {
```

새로:

```java
        // 구현 성공 시 추출한 배포 env 템플릿 (스펙 2026-10-07 §5 — null이면 미보고/구버전 워커)
        List<EnvTemplateItem> envTemplate,
        // 구현 결과(PR생성·구현실패)의 시도 번호 (스펙 2026-10-15 §5.6 — null이면 구버전 워커·다른 단계)
        Integer implementationAttempt
) {
    /** 시도 번호 도입 전 23개 인자 정규 생성자 — 기존 호출부·테스트 호환. Jackson은 정규 생성자(24개)로 읽는다. */
    public WorkerResultRequest(String workerId, TaskStatus status,
                               String markdownResult, String subtasksJson, String claudeLog,
                               Long durationMs, String failureReason,
                               String prUrl, Integer prNumber, String headBranch, String headSha,
                               String implementationLog,
                               String deployUrl, String deployContainerId, Integer deployHostPort,
                               String deployImage, String deployLog,
                               String designMarkdown, String mockupFilesJson, String designProjectId,
                               String designUrl,
                               UsageReport usage, List<EnvTemplateItem> envTemplate) {
        this(workerId, status, markdownResult, subtasksJson, claudeLog, durationMs, failureReason,
                prUrl, prNumber, headBranch, headSha, implementationLog,
                deployUrl, deployContainerId, deployHostPort, deployImage, deployLog,
                designMarkdown, mockupFilesJson, designProjectId, designUrl, usage, envTemplate, null);
    }

    /** 시도 번호만 바꾼 사본 — 워커가 구현 결과를 보낼 때 붙인다. */
    public WorkerResultRequest withImplementationAttempt(Integer attempt) {
        return new WorkerResultRequest(workerId, status, markdownResult, subtasksJson, claudeLog, durationMs,
                failureReason, prUrl, prNumber, headBranch, headSha, implementationLog,
                deployUrl, deployContainerId, deployHostPort, deployImage, deployLog,
                designMarkdown, mockupFilesJson, designProjectId, designUrl, usage, envTemplate, attempt);
    }
```

(기존 22인자 생성자의 `this(..., usage, null)`은 이제 위 23인자 생성자로 이어진다 — 그대로 둔다.)

2. `masked()`의 마지막 줄 `usage, maskTemplate(envTemplate));`를:

```java
                usage, maskTemplate(envTemplate), implementationAttempt);
```

- [ ] **Step 4: WorkerService.recordResult 구현**

`recordResult`에서 `TaskStatus current = t.getStatus();` 줄 바로 다음에 추가:

```java

        // 재시도 뒤 이전 시도의 늦은 보고(스펙 2026-10-15 §5.6) — 상태는 그대로 두고 이력·사용량만 남긴다.
        // 지각 정합화·in-flight 가드보다 먼저 본다(이전 시도가 새 시도의 상태를 덮거나 409로 dead-letter에 쌓이지 않게).
        if (isEarlierImplementationAttempt(t, req)) {
            historyRepo.save(TaskStatusHistory.log(t.getId(), current, current, "worker", req.workerId(),
                    "이전 시도(" + req.implementationAttempt() + "회째)의 늦은 보고 무시: " + req.status().dbValue()));
            accumulateUsage(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION, req);
            return;
        }
```

그리고 `applyEnvTemplate` 메서드 바로 위에 추가:

```java
    /** 구현 결과인데 시도 번호가 작업의 현재 시도와 다르면 이전 시도의 보고다. 번호가 없으면(구버전 워커) 판단하지 않는다. */
    private static boolean isEarlierImplementationAttempt(Task t, WorkerResultRequest req) {
        if (req.implementationAttempt() == null) return false;
        if (req.status() != TaskStatus.PR_CREATED && req.status() != TaskStatus.IMPLEMENTATION_FAILED) return false;
        return req.implementationAttempt() != t.getImplementationAttempt();
    }
```

- [ ] **Step 5: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceImplementationAttemptTest" --tests "com.hamonsoft.netismaker.dto.*" --tests "com.hamonsoft.netismaker.service.WorkerServiceStageUsageTest"`
Expected: PASS

- [ ] **Step 6: API 전체 단위 테스트 회귀 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test`
Expected: 실패는 이 PC 고유의 심볼릭 링크 테스트 3건(`AttachmentCleanupJobTest`·`AttachmentStorageTest`)뿐. 다른 실패가 있으면 고친 뒤 진행.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceImplementationAttemptTest.java src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestJsonTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(api): 결과 보고의 시도 번호 — 이전 시도의 늦은 보고는 이력·사용량만 (스펙 2026-10-15 §5.6)"
```

---

### Task 7: claude 실행 — 추가 CLI 인자·자식 env·`modelUsage` 토큰

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapter.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapterTest.java` (테스트 4개 추가)

**Interfaces:**
- Produces: `public record ClaudeExecAdapter.ExecOptions(List<String> extraArgs, Map<String, String> env)` + `ExecOptions.NONE`
- Produces: `ExecResult exec(String prompt, File dir, Duration timeout, List<TaskMcpSpec> extras, boolean skip, String model, String effort, ExecOptions options)` — 기존 7인자는 `NONE`으로 위임
- Produces: `static List<String> buildCommand(String claudePath, boolean skip, String model, String effort, List<String> extraArgs, List<String> mcpArgs)` — extraArgs는 `--model/--effort` 뒤, mcpArgs 앞. 기존 5인자는 `List.of()`로 위임
- Produces: `static ProcessBuilder processBuilder(List<String> cmd, File dir, Map<String, String> extraEnv)`
- Produces: `parseEnvelope`가 `modelUsage`(모델별, 서브에이전트 포함)가 있으면 그 토큰 합계를 쓴다

- [ ] **Step 1: 실패하는 테스트 작성**

`ClaudeExecAdapterTest.java` 끝(마지막 `}` 앞)에 추가:

```java
    @Test
    void buildCommand_puts_extra_args_after_model_and_before_mcp_args() {
        List<String> cmd = ClaudeExecAdapter.buildCommand("/bin/claude", true, "claude-opus-5-5", "high",
                List.of("--plugin-dir", "C:/sp/6.4.1"),
                List.of("--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a"));
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--output-format", "json",
                "--setting-sources", "project", "--dangerously-skip-permissions",
                "--model", "claude-opus-5-5", "--effort", "high",
                "--plugin-dir", "C:/sp/6.4.1",
                "--mcp-config", "/tmp/x", "--strict-mcp-config", "--allowedTools", "mcp__a");
    }

    @Test
    void process_builder_adds_the_extra_env_on_top_of_the_worker_env() {
        ProcessBuilder pb = ClaudeExecAdapter.processBuilder(List.of("claude"), new java.io.File("."),
                java.util.Map.of("GIT_AUTHOR_NAME", "netisMaker", "GIT_COMMITTER_EMAIL", "netismaker@hamonsoft.local"));
        assertThat(pb.environment())
                .containsEntry("GIT_AUTHOR_NAME", "netisMaker")
                .containsEntry("GIT_COMMITTER_EMAIL", "netismaker@hamonsoft.local");
        assertThat(pb.directory()).isEqualTo(new java.io.File("."));
    }

    /** 2026-10-15 헤드리스 SDD 실측 결과 JSON — usage는 메인 세션 것뿐이고 modelUsage에 서브에이전트가 들어 있다. */
    @Test
    void parseEnvelope_sums_model_usage_so_subagent_tokens_count() {
        String raw = """
                {"type":"result","result":"끝","total_cost_usd":1.8929,
                 "usage":{"input_tokens":50,"output_tokens":18219,"cache_creation_input_tokens":63011,"cache_read_input_tokens":1381990},
                 "modelUsage":{
                   "claude-opus-5-5":{"inputTokens":58,"outputTokens":25320,"cacheReadInputTokens":1453215,"cacheCreationInputTokens":93089,"costUSD":1.4518},
                   "claude-haiku-4-5-20251001":{"inputTokens":236,"outputTokens":10917,"cacheReadInputTokens":614504,"cacheCreationInputTokens":42292,"costUSD":0.1691},
                   "claude-sonnet-5-5":{"inputTokens":24,"outputTokens":8780,"cacheReadInputTokens":207346,"cacheCreationInputTokens":57085,"costUSD":0.2720}}}
                """;
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).isEqualTo("끝");
        assertThat(p.usage().costUsd()).isEqualByComparingTo("1.8929");
        assertThat(p.usage().inputTokens()).isEqualTo(58 + 236 + 24);
        assertThat(p.usage().outputTokens()).isEqualTo(25320 + 10917 + 8780);
        assertThat(p.usage().cacheReadTokens()).isEqualTo(1453215L + 614504L + 207346L);
        assertThat(p.usage().cacheCreationTokens()).isEqualTo(93089L + 42292L + 57085L);
    }

    @Test
    void parseEnvelope_with_an_empty_model_usage_falls_back_to_usage() {
        String raw = "{\"type\":\"result\",\"result\":\"ok\",\"total_cost_usd\":0.1,\"modelUsage\":{},"
                + "\"usage\":{\"input_tokens\":7,\"output_tokens\":3,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}";
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.usage().inputTokens()).isEqualTo(7);
        assertThat(p.usage().outputTokens()).isEqualTo(3);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ClaudeExecAdapterTest"`
Expected: 컴파일 실패 — 6인자 `buildCommand`·`processBuilder` 없음.

- [ ] **Step 3: 구현**

1. import에 `java.util.Map` 추가.

2. `NO_TOOLS_ARGS` 상수 바로 아래에 추가:

```java

    /**
     * 구현 세션 실행 옵션 (스펙 2026-10-15 §6.1·§6.4) — extraArgs는 SDD의 --plugin-dir, env는 에이전트 커밋용 git 작성자.
     */
    public record ExecOptions(List<String> extraArgs, Map<String, String> env) {
        public static final ExecOptions NONE = new ExecOptions(List.of(), Map.of());

        public ExecOptions {
            extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            env = env == null ? Map.of() : Map.copyOf(env);
        }
    }
```

3. 기존 `buildCommand` 5인자 메서드 전체(주석 포함)를 다음으로 바꾼다:

```java
    /**
     * claude 명령 조립. --setting-sources project 고정. --model/--effort는 -p(및 skip) 뒤,
     * mcpArgs(--allowedTools variadic) 앞. blank면 생략.
     */
    static List<String> buildCommand(String claudePath, boolean dangerouslySkipPermissions,
                                     String model, String effort, List<String> mcpArgs) {
        return buildCommand(claudePath, dangerouslySkipPermissions, model, effort, List.of(), mcpArgs);
    }

    /** extraArgs(SDD의 --plugin-dir 등)는 --model/--effort 뒤, mcpArgs 앞 — --allowedTools가 variadic이라 맨 뒤여야 한다. */
    static List<String> buildCommand(String claudePath, boolean dangerouslySkipPermissions,
                                     String model, String effort, List<String> extraArgs, List<String> mcpArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(claudePath);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        // 운영자 ~/.claude/settings.json(플러그인·훅·permissions) 차단, 대상 레포 CLAUDE.md는 유지.
        // 생략하면 CLI가 user/project/local을 전부 읽는다 — --strict-mcp-config는 MCP만 막음(2026-10-01 프로브).
        cmd.add("--setting-sources");
        cmd.add("project");
        if (dangerouslySkipPermissions) cmd.add("--dangerously-skip-permissions");
        if (model != null && !model.isBlank()) { cmd.add("--model"); cmd.add(model); }
        if (effort != null && !effort.isBlank()) { cmd.add("--effort"); cmd.add(effort); }
        cmd.addAll(extraArgs);
        cmd.addAll(mcpArgs);
        return cmd;
    }
```

4. 기존 7인자 `exec(...)` 메서드(Javadoc 아래 `public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras, boolean dangerouslySkipPermissions, String model, String effort)`)의 본문을 위임으로 바꾸고, 그 아래에 8인자 메서드를 둔다:

```java
    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras,
                           boolean dangerouslySkipPermissions, String model, String effort)
            throws InterruptedException, IOException {
        return exec(prompt, workingDir, timeout, extras, dangerouslySkipPermissions, model, effort, ExecOptions.NONE);
    }

    /** 구현 세션용 — options.extraArgs를 명령에 넣고 options.env를 자식 프로세스 env에 더한다 (스펙 2026-10-15 §6.1). */
    public ExecResult exec(String prompt, File workingDir, Duration timeout, List<TaskMcpSpec> extras,
                           boolean dangerouslySkipPermissions, String model, String effort, ExecOptions options)
            throws InterruptedException, IOException {
        long start = System.currentTimeMillis();
        ExecOptions opts = options == null ? ExecOptions.NONE : options;
        WorkerMcpSupport.TaskClaudeArgs mcpArgs = mcp.buildClaudeArgsForTask(extras);
        try {
            // 프롬프트는 stdin으로 전달. 이유:
            // 1) --allowedTools <tools...>가 variadic이라 뒤에 위치한 prompt arg를 삼킴
            // 2) ARG_MAX(~256KB)를 넘는 큰 프롬프트도 안전
            // 3) ps에 프롬프트 본문이 노출되지 않음 (PAT가 들어있을 경우 보호)
            return run(buildCommand(resolvedClaudePath, dangerouslySkipPermissions, model, effort,
                            opts.extraArgs(), mcpArgs.args()),
                    prompt, workingDir, timeout, start, opts.env());
        } finally {
            Path tmp = mcpArgs.tempConfigPath();
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); }
                catch (Exception e) { log.warn("임시 MCP config 삭제 실패: {} ({})", tmp, e.getMessage()); }
            }
        }
    }
```

5. `run(...)`의 시그니처와 첫 세 줄 — 기존:

```java
    private ExecResult run(List<String> cmd, String prompt, File workingDir, Duration timeout, long start)
            throws InterruptedException, IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workingDir);            // redirectErrorStream(true) 제거!
        Process p = pb.start();
```

새로:

```java
    private ExecResult run(List<String> cmd, String prompt, File workingDir, Duration timeout, long start,
                           Map<String, String> env)
            throws InterruptedException, IOException {
        Process p = processBuilder(cmd, workingDir, env).start();
```

그리고 `run(...)` 메서드 바로 아래에 추가:

```java

    /** 자식 env = 워커 env + extraEnv(같은 키는 extraEnv가 이김). stderr는 따로 읽는다 — redirectErrorStream 쓰지 말 것. */
    static ProcessBuilder processBuilder(List<String> cmd, File workingDir, Map<String, String> extraEnv) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workingDir);
        pb.environment().putAll(extraEnv);
        return pb;
    }
```

6. `execWithoutTools`의 `run(...)` 호출 끝을 `prompt, workingDir, timeout, System.currentTimeMillis(), Map.of());`로 바꾼다.

7. `parseEnvelope`의 usage 추출 블록 — 기존:

```java
            Usage usage = null;
            var u = node.path("usage");
            if (node.path("total_cost_usd").isNumber() || u.isObject()) {
                usage = new Usage(
                        node.path("total_cost_usd").isNumber()
                                ? node.path("total_cost_usd").decimalValue()
                                : java.math.BigDecimal.ZERO,
                        u.path("input_tokens").asLong(0),
                        u.path("output_tokens").asLong(0),
                        u.path("cache_creation_input_tokens").asLong(0),
                        u.path("cache_read_input_tokens").asLong(0));
            }
```

새로:

```java
            Usage usage = null;
            var u = node.path("usage");
            var modelUsage = node.path("modelUsage");
            if (node.path("total_cost_usd").isNumber() || u.isObject()) {
                java.math.BigDecimal cost = node.path("total_cost_usd").isNumber()
                        ? node.path("total_cost_usd").decimalValue()
                        : java.math.BigDecimal.ZERO;
                // usage는 메인 세션 것뿐 — 모델별 합계(modelUsage)에는 서브에이전트가 들어 있다 (스펙 2026-10-15 §6.8)
                usage = modelUsage.isObject() && modelUsage.size() > 0
                        ? sumModelUsage(cost, modelUsage)
                        : new Usage(cost,
                                u.path("input_tokens").asLong(0),
                                u.path("output_tokens").asLong(0),
                                u.path("cache_creation_input_tokens").asLong(0),
                                u.path("cache_read_input_tokens").asLong(0));
            }
```

그리고 `parseEnvelope` 메서드 바로 아래(클래스 끝 `}` 앞)에 추가:

```java

    private static Usage sumModelUsage(java.math.BigDecimal cost, com.fasterxml.jackson.databind.JsonNode modelUsage) {
        long in = 0, out = 0, cacheCreation = 0, cacheRead = 0;
        for (com.fasterxml.jackson.databind.JsonNode m : modelUsage) {
            in += m.path("inputTokens").asLong(0);
            out += m.path("outputTokens").asLong(0);
            cacheCreation += m.path("cacheCreationInputTokens").asLong(0);
            cacheRead += m.path("cacheReadInputTokens").asLong(0);
        }
        return new Usage(cost, in, out, cacheCreation, cacheRead);
    }
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ClaudeExecAdapterTest" --tests "com.hamonsoft.netismaker.workerdaemon.ClaudeExecAdapterKillTreeTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapter.java src/test/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapterTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(worker): claude 실행 옵션(추가 인자·자식 env)·modelUsage 토큰 합산 (스펙 2026-10-15 §6.1·§6.8)"
```

---

### Task 8: git — 에이전트 커밋 수용·부분 작업 보존

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/GitOpsService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/GitOpsServicePushTest.java` (테스트 6개 추가)

**Interfaces:**
- Produces: `String commitAndPush(File dir, RepoRef ref, String branch, String message, String baseSha)` — baseSha가 있으면 에이전트 커밋도 받아들인다. 기존 4인자는 `baseSha=null`(예전처럼 변경 필수)로 위임
- Produces: `PartialWork preservePartialWork(File dir, RepoRef ref, String branch, String baseSha, String wipMessage)` — 예외 없음
- Produces: `public record GitOpsService.PartialWork(int commits, String headSha, String error)` + `boolean pushed()`

- [ ] **Step 1: 실패하는 테스트 작성**

`GitOpsServicePushTest.java` 끝에 추가:

```java
    private static String head(Path dir) throws Exception {
        return ProcessRunner.requireSuccess(dir.toFile(), List.of("git", "rev-parse", "HEAD"), 30).trim();
    }

    private static String remoteSha(Path bare, String branch) throws Exception {
        return ProcessRunner.requireSuccess(bare.toFile(), List.of("git", "rev-parse", "refs/heads/" + branch), 30).trim();
    }

    @Test
    void commits_the_agent_already_made_are_pushed_even_when_the_tree_is_clean() throws Exception {
        Path bare = bareRemote("r4");
        Path clone = workingClone(bare, "work4", "netismaker/task-4");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        String sha = new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-4", "feat: 구현", base);

        assertThat(sha).isEqualTo(head(clone));
        assertThat(remoteSha(bare, "netismaker/task-4")).isEqualTo(sha);
    }

    @Test
    void leftover_changes_after_agent_commits_get_one_more_commit() throws Exception {
        Path bare = bareRemote("r5");
        Path clone = workingClone(bare, "work5", "netismaker/task-5");
        String base = head(clone);
        git(clone.toFile(), "add", "-A");
        git(clone.toFile(), "-c", "user.name=agent", "-c", "user.email=a@a", "commit", "-m", "Task 1");
        Files.writeString(clone.resolve("extra.txt"), "남은 변경");
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        new GitOpsService(props()).commitAndPush(clone.toFile(), ref, "netismaker/task-5", "feat: 구현", base);

        assertThat(ProcessRunner.requireSuccess(clone.toFile(),
                List.of("git", "rev-list", "--count", base + "..HEAD"), 30).trim()).isEqualTo("2");
        assertThat(ProcessRunner.requireSuccess(clone.toFile(), List.of("git", "status", "--porcelain"), 30)).isBlank();
    }

    @Test
    void with_a_base_and_nothing_at_all_it_is_still_nothing_to_commit() throws Exception {
        Path bare = bareRemote("r6");
        Path clone = tmp.resolve("work6");
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        assertThatThrownBy(() -> new GitOpsService(props())
                .commitAndPush(clone.toFile(), ref, "netismaker/task-6", "feat: 구현", head(clone)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nothing to commit");
    }

    @Test
    void partial_work_is_committed_as_wip_and_pushed() throws Exception {
        Path bare = bareRemote("r7");
        Path clone = workingClone(bare, "work7", "netismaker/task-7");   // impl.txt 미커밋
        String base = head(clone);
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props()).preservePartialWork(clone.toFile(), ref,
                "netismaker/task-7", base, "wip: 구현 중단(시간 초과) — task #7");

        assertThat(w.pushed()).isTrue();
        assertThat(w.commits()).isEqualTo(1);
        assertThat(w.headSha()).isEqualTo(head(clone));
        assertThat(remoteSha(bare, "netismaker/task-7")).isEqualTo(w.headSha());
        assertThat(ProcessRunner.requireSuccess(clone.toFile(), List.of("git", "log", "-1", "--format=%s"), 30).trim())
                .isEqualTo("wip: 구현 중단(시간 초과) — task #7");
    }

    @Test
    void nothing_to_preserve_reports_zero_commits_without_pushing() throws Exception {
        Path bare = bareRemote("r8");
        Path clone = tmp.resolve("work8");
        git(tmp.toFile(), "clone", bare.toUri().toString(), clone.toString());
        RepoRef ref = new RepoRef("github", "acme/widgets", bare.toUri().toString());

        GitOpsService.PartialWork w = new GitOpsService(props())
                .preservePartialWork(clone.toFile(), ref, "netismaker/task-8", head(clone), "wip");

        assertThat(w.commits()).isZero();
        assertThat(w.pushed()).isFalse();
        assertThat(w.error()).isNull();
        assertThat(ProcessRunner.run(bare.toFile(), List.of("git", "rev-parse", "--verify", "refs/heads/netismaker/task-8"), 30)
                .exitCode()).isNotZero();
    }

    @Test
    void a_failed_partial_push_keeps_the_commit_and_masks_the_error() throws Exception {
        Path bare = bareRemote("r9");
        Path clone = workingClone(bare, "work9", "netismaker/task-9");
        String base = head(clone);
        RepoRef ref = RepoRef.fromSnapshot("g/p", "https://gitlab.invalid.example/g/p.git");

        GitOpsService.PartialWork w = new GitOpsService(props())
                .preservePartialWork(clone.toFile(), ref, "netismaker/task-9", base, "wip");

        assertThat(w.commits()).isEqualTo(1);
        assertThat(w.pushed()).isFalse();
        assertThat(w.error()).isNotBlank().doesNotContain("glpat-SECRETTOKEN");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.GitOpsServicePushTest"`
Expected: 컴파일 실패 — 5인자 `commitAndPush`·`preservePartialWork`·`PartialWork` 없음.

- [ ] **Step 3: 구현**

`GitOpsService.java`의 기존 `commitAndPush` 메서드 전체(Javadoc 포함, `return sha;`와 닫는 `}`까지)를 다음으로 바꾼다:

```java
    /** 예전 동작 — 변경이 꼭 있어야 커밋한다(에이전트 커밋을 모르는 호출부용). */
    public String commitAndPush(File worktreeDir, RepoRef ref, String branchName, String commitMessage)
            throws IOException, InterruptedException {
        return commitAndPush(worktreeDir, ref, branchName, commitMessage, null);
    }

    /**
     * worktree에 있는 변경을 commit + push.
     *
     * baseSha가 있으면 에이전트가 이미 만든 커밋(SDD 태스크 커밋 등)도 받아들인다 (스펙 2026-10-15 §6.1): 남은 변경만
     * 한 커밋으로 더하고, 베이스 이후 커밋도 변경도 없을 때만 nothing to commit. baseSha가 null이면 예전처럼 변경 필수.
     *
     * push는 origin이 아니라 ref의 인증 URL로 보낸다 — 캐시/워크트리 config에 자격증명을
     * 남기지 않기 위해(GitRepoCache 불변식) origin은 평문 URL이기 때문.
     *
     * @return push한 commit의 SHA
     */
    public String commitAndPush(File worktreeDir, RepoRef ref, String branchName, String commitMessage,
                                String baseSha) throws IOException, InterruptedException {
        boolean changes = hasChanges(worktreeDir);
        if (!changes && (baseSha == null || commitsSince(worktreeDir, baseSha) == 0)) {
            throw new IOException("nothing to commit — 구현 시도 후 파일 변경이 없음");
        }
        if (changes) commitAll(worktreeDir, commitMessage);
        push(worktreeDir, ref, branchName);
        return headSha(worktreeDir);
    }

    /**
     * 중단된 구현(시간 초과·비정상 종료)의 남은 변경을 WIP 커밋으로 묶고, 베이스 이후 커밋이 있으면 작업 브랜치를
     * push한다 (스펙 2026-10-15 §6.6). PR/MR은 만들지 않는다. 실패는 예외 대신 결과로 돌려준다 — 이미 실패 보고 중이다.
     */
    public PartialWork preservePartialWork(File worktreeDir, RepoRef ref, String branchName, String baseSha,
                                           String wipMessage) {
        int commits;
        String sha;
        try {
            if (hasChanges(worktreeDir)) commitAll(worktreeDir, wipMessage);
            commits = commitsSince(worktreeDir, baseSha);
            if (commits == 0) return new PartialWork(0, null, null);
            sha = headSha(worktreeDir);
        } catch (Exception e) {
            return new PartialWork(0, null, GitRemotes.mask(String.valueOf(e.getMessage())));
        }
        try {
            push(worktreeDir, ref, branchName);
            return new PartialWork(commits, sha, null);
        } catch (Exception e) {
            return new PartialWork(commits, sha, GitRemotes.mask(String.valueOf(e.getMessage())));
        }
    }

    /** 중단 보존 결과. commits = 베이스 이후 커밋 수, error = 마스킹한 실패 메시지(없으면 null). */
    public record PartialWork(int commits, String headSha, String error) {
        public boolean pushed() {
            return commits > 0 && error == null;
        }
    }

    private void commitAll(File worktreeDir, String message) throws IOException, InterruptedException {
        ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "add", "-A"), GIT_TIMEOUT_SECONDS);
        List<String> commitCmd = List.of(
                "git",
                "-c", "user.name=" + props.gitUserName(),
                "-c", "user.email=" + props.gitUserEmail(),
                "commit", "-m", message
        );
        ProcessRunner.requireSuccess(worktreeDir, commitCmd, GIT_TIMEOUT_SECONDS);
    }

    private void push(File worktreeDir, RepoRef ref, String branchName) throws IOException, InterruptedException {
        try {
            // credential.helper=(빈 값): 운영자 머신의 헬퍼가 인증 URL의 토큰을 저장하지 못하게 한다.
            ProcessRunner.requireSuccess(worktreeDir,
                    List.of("git", "-c", "credential.helper=",
                            "push", remotes.authenticatedUrl(ref),
                            "HEAD:refs/heads/" + branchName),
                    GIT_TIMEOUT_SECONDS);
        } catch (IOException e) {
            // ProcessRunner가 이미 마스킹하지만, 이 호출부는 토큰이 확실히 실리는 유일한 push라
            // 방어적으로 한 번 더 가린다(mask는 멱등). cause로 원본을 달지는 않는다.
            throw new IOException(GitRemotes.mask(e.getMessage()));
        }
    }

    private static String headSha(File worktreeDir) throws IOException, InterruptedException {
        return ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "rev-parse", "HEAD"), GIT_TIMEOUT_SECONDS).trim();
    }

    private static int commitsSince(File worktreeDir, String baseSha) throws IOException, InterruptedException {
        String out = ProcessRunner.requireSuccess(worktreeDir,
                List.of("git", "rev-list", "--count", baseSha + "..HEAD"), GIT_TIMEOUT_SECONDS).trim();
        return Integer.parseInt(out);
    }
```

클래스 Javadoc의 흐름 1)을 `1) git status --porcelain → 변경도 베이스 이후 커밋도 없으면 IOException("nothing to commit")`으로 고친다.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.GitOpsServicePushTest"`
Expected: PASS (9 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/GitOpsService.java src/test/java/com/hamonsoft/netismaker/workerdaemon/GitOpsServicePushTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(worker): 에이전트 커밋 수용·중단 시 WIP 커밋 push (스펙 2026-10-15 §6.1·§6.6)"
```

---

### Task 9: worktree — 시도 번호 브랜치·임시 파일 `info/exclude`

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeServiceTest.java` (테스트 3개 추가)

**Interfaces:**
- Produces: `CreatedWorktree create(File repoCacheDir, String repoKey, String baseBranch, long taskId, String title, int attempt)` — 기존 5인자는 `attempt=1`로 위임
- Produces: `static String branchName(String prefix, long taskId, String slug, int attempt)`
- Produces: `boolean ensureWorkerExcludes(File worktreeDir)` + `static final List<String> WORKER_EXCLUDES = List.of(".netis/", ".design/", ".netis-deploy-env.json")`

- [ ] **Step 1: 실패하는 테스트 작성**

`WorktreeServiceTest.java` 끝에 추가:

```java
    @Test
    void branch_names_get_an_attempt_suffix_from_the_second_try() {
        assertThat(WorktreeService.branchName("netismaker/", 13, "srtc", 1)).isEqualTo("netismaker/task-13-srtc");
        assertThat(WorktreeService.branchName("netismaker/", 13, "srtc", 2)).isEqualTo("netismaker/task-13-srtc-r2");
        assertThat(WorktreeService.branchName("netismaker/", 13, "", 3)).isEqualTo("netismaker/task-13-r3");
    }

    @Test
    void a_retry_attempt_checks_out_the_suffixed_branch() throws Exception {
        WorkerProperties p = props();
        GitRepoCache cache = new GitRepoCache(p);
        RepoRef ref = new RepoRef("github", "acme/widgets", bareRemote().toUri().toString());
        File repoDir = cache.ensureFresh(ref, "main").dir();
        WorktreeService worktrees = new WorktreeService(p, cache);

        WorktreeService.CreatedWorktree wt = worktrees.create(repoDir, GitRemotes.localKey(ref), "main", 9, "retry", 2);

        assertThat(wt.branchName()).isEqualTo("netismaker/task-9-retry-r2");
        assertThat(ProcessRunner.requireSuccess(wt.dir(), List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), 30).trim())
                .isEqualTo("netismaker/task-9-retry-r2");
    }

    @Test
    void worker_scratch_files_are_excluded_from_commits_and_registered_once() throws Exception {
        WorkerProperties p = props();
        GitRepoCache cache = new GitRepoCache(p);
        RepoRef ref = new RepoRef("github", "acme/widgets", bareRemote().toUri().toString());
        File repoDir = cache.ensureFresh(ref, "main").dir();
        WorktreeService worktrees = new WorktreeService(p, cache);
        Path dir = worktrees.create(repoDir, GitRemotes.localKey(ref), "main", 10, "scratch").dir().toPath();

        assertThat(worktrees.ensureWorkerExcludes(dir.toFile())).isTrue();
        assertThat(worktrees.ensureWorkerExcludes(dir.toFile())).isTrue();   // 두 번째는 중복 없이

        Files.createDirectories(dir.resolve(".netis/plan"));
        Files.writeString(dir.resolve(".netis/plan/task-10-plan.md"), "plan");
        Files.createDirectories(dir.resolve(".design"));
        Files.writeString(dir.resolve(".design/a.html"), "<p/>");
        Files.writeString(dir.resolve(".netis-deploy-env.json"), "{}");
        Files.writeString(dir.resolve("real.txt"), "x");
        String status = ProcessRunner.requireSuccess(dir.toFile(),
                List.of("git", "status", "--porcelain", "--untracked-files=all"), 30);
        assertThat(status).contains("real.txt").doesNotContain(".netis").doesNotContain(".design");

        String raw = ProcessRunner.requireSuccess(dir.toFile(),
                List.of("git", "rev-parse", "--git-path", "info/exclude"), 30).trim();
        Path exclude = Path.of(raw).isAbsolute() ? Path.of(raw) : dir.resolve(raw);
        assertThat(Files.readAllLines(exclude).stream().filter(".netis/"::equals).count()).isEqualTo(1);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceTest"`
Expected: 컴파일 실패 — `branchName`·6인자 `create`·`ensureWorkerExcludes` 없음.

- [ ] **Step 3: 구현**

1. import에 `java.nio.file.StandardOpenOption` 추가. 클래스 Javadoc의 브랜치 컨벤션 줄을 `netismaker/task-{id}-{slug-of-title}[-r{시도 번호}]  (2회째부터, 스펙 2026-10-15 §6.1)`로 고친다.

2. `GIT_TIMEOUT_SECONDS` 상수 아래에 추가:

```java

    /** 워커 임시 파일 — 에이전트 커밋(SDD 태스크 커밋 포함)에 섞이지 않게 info/exclude에 둔다 (스펙 2026-10-15 §6.1). */
    static final List<String> WORKER_EXCLUDES = List.of(".netis/", ".design/", ".netis-deploy-env.json");
```

3. 기존 5인자 `create`(Javadoc 아래 메서드 본문)를 다음 두 메서드로 바꾼다(Javadoc은 6인자 쪽으로 옮기고 `@param attempt 구현 시도 번호 — 2 이상이면 브랜치 이름에 -r{attempt}`를 더한다):

```java
    public CreatedWorktree create(File repoCacheDir, String repoKey,
                                  String baseBranch, long taskId, String title)
            throws IOException, InterruptedException {
        return create(repoCacheDir, repoKey, baseBranch, taskId, title, 1);
    }

    public CreatedWorktree create(File repoCacheDir, String repoKey,
                                  String baseBranch, long taskId, String title, int attempt)
            throws IOException, InterruptedException {
        return repos.withRepoLock(repoKey,
                () -> doCreate(repoCacheDir, repoKey, baseBranch, taskId, title, attempt));
    }

    /**
     * {prefix}task-{id}[-{slug}][-r{attempt}] — 재시도는 새 브랜치로 (이전 시도가 push한 브랜치와 겹치면
     * 강제 아닌 push가 거부된다). deleteTaskBranches의 'task-{id}-' 접두 매칭이 그대로 덮는다.
     */
    static String branchName(String prefix, long taskId, String slug, int attempt) {
        return prefix + "task-" + taskId
                + (slug.isEmpty() ? "" : "-" + slug)
                + (attempt >= 2 ? "-r" + attempt : "");
    }
```

4. `doCreate`의 시그니처와 첫 세 줄 — 기존:

```java
    private CreatedWorktree doCreate(File repoCacheDir, String repoKey,
                                     String baseBranch, long taskId, String title)
            throws IOException, InterruptedException {
        String slug = sanitizeForBranch(title);
        String branchName = props.branchPrefix() + "task-" + taskId
                + (slug.isEmpty() ? "" : "-" + slug);
```

새로:

```java
    private CreatedWorktree doCreate(File repoCacheDir, String repoKey,
                                     String baseBranch, long taskId, String title, int attempt)
            throws IOException, InterruptedException {
        String branchName = branchName(props.branchPrefix(), taskId, sanitizeForBranch(title), attempt);
```

5. `doCreate` 메서드 바로 아래에 추가:

```java

    /**
     * WORKER_EXCLUDES를 레포 info/exclude에 없는 줄만 더한다(worktree면 공용 git 디렉터리의 파일 — 워커 전용 이름이라
     * 다른 작업에도 무해). 실패는 경고만 남기고 false — 호출부가 SDD를 리뷰로 대신 진행한다 (스펙 §6.5).
     */
    public boolean ensureWorkerExcludes(File worktreeDir) {
        try {
            String raw = ProcessRunner.requireSuccess(worktreeDir,
                    List.of("git", "rev-parse", "--git-path", "info/exclude"), GIT_TIMEOUT_SECONDS).trim();
            Path exclude = Path.of(raw);
            if (!exclude.isAbsolute()) exclude = worktreeDir.toPath().resolve(exclude);
            Files.createDirectories(exclude.getParent());
            String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
            List<String> lines = existing.lines().map(String::trim).toList();
            List<String> missing = WORKER_EXCLUDES.stream().filter(e -> !lines.contains(e)).toList();
            if (missing.isEmpty()) return true;
            StringBuilder add = new StringBuilder();
            if (!existing.isEmpty() && !existing.endsWith("\n")) add.append('\n');
            add.append("# netisMaker 워커 임시 파일 — 구현 커밋에서 제외\n");
            missing.forEach(m -> add.append(m).append('\n'));
            Files.writeString(exclude, add.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            log.warn("info/exclude 기록 실패 (SDD 요청은 리뷰로 대신 진행): {}", e.getMessage());
            return false;
        }
    }
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorktreeServiceDiscardTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeServiceTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(worker): 재시도 브랜치 -rN·워커 임시 파일 info/exclude (스펙 2026-10-15 §6.1)"
```

---

### Task 10: `.netis/` 임시 파일 — 계획·설계 파일, 제목 변환, 리뷰 메모, 정리

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationScratch.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationScratchTest.java`

**Interfaces:**
- Consumes: `RepoCacheCleanupJob.deleteTree(Path)` (기존, 링크 비추종), `GitRemotes.mask` (기존)
- Produces: `static String planPath(long taskId)` → `.netis/plan/task-{id}-plan.md`, `static String designPath(long taskId)` → `.netis/plan/task-{id}-design.md`, `static final String REVIEW_NOTES = ".netis/review-notes.md"`, `static final int REVIEW_NOTES_MAX_CHARS = 20_000`
- Produces: `static void write(Path worktree, String relPath, String content) throws IOException`
- Produces: `static String normalizeTaskHeadings(String plan)`, `static int countTaskHeadings(String plan)`
- Produces: `static Optional<String> readReviewNotes(Path worktree)`
- Produces: `static void deleteRoot(Path worktree)`(`.netis/`만), `static void deleteAll(Path worktree)`(`.netis/`·`.design/`·`.netis-deploy-env.json`)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationScratchTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 구현 세션의 워커 임시 파일 (스펙 2026-10-15 §6.3·§6.4·§6.6·§6.7). */
class ImplementationScratchTest {

    @TempDir Path wt;

    @Test
    void korean_task_headings_outside_code_blocks_become_task_headings() {
        String plan = String.join("\n",
                "# 계획",
                "### 작업 1: 공통 모듈",
                "본문 — 작업 2는 나중에",
                "```markdown",
                "### 작업 9: 예시 안의 제목은 그대로",
                "```",
                "#### 작업 12: 깊은 제목",
                "### 작업 내용 정리",
                "### Task 3: 이미 영어",
                "");

        String out = ImplementationScratch.normalizeTaskHeadings(plan);

        assertThat(out).contains("### Task 1: 공통 모듈")
                .contains("본문 — 작업 2는 나중에")
                .contains("### 작업 9: 예시 안의 제목은 그대로")
                .contains("#### Task 12: 깊은 제목")
                .contains("### 작업 내용 정리")
                .contains("### Task 3: 이미 영어")
                .endsWith("\n");
        assertThat(ImplementationScratch.countTaskHeadings(out)).isEqualTo(3);
        assertThat(ImplementationScratch.countTaskHeadings(plan)).isEqualTo(1);
        assertThat(ImplementationScratch.countTaskHeadings(null)).isZero();
    }

    @Test
    void plan_files_are_written_under_netis_plan() throws Exception {
        ImplementationScratch.write(wt, ImplementationScratch.planPath(13), "# 플랜");

        assertThat(Files.readString(wt.resolve(".netis/plan/task-13-plan.md"))).isEqualTo("# 플랜");
        assertThat(ImplementationScratch.designPath(13)).isEqualTo(".netis/plan/task-13-design.md");
    }

    @Test
    void review_notes_are_masked_trimmed_and_capped() throws Exception {
        assertThat(ImplementationScratch.readReviewNotes(wt)).isEmpty();

        ImplementationScratch.write(wt, ImplementationScratch.REVIEW_NOTES, "  \n ");
        assertThat(ImplementationScratch.readReviewNotes(wt)).isEmpty();

        ImplementationScratch.write(wt, ImplementationScratch.REVIEW_NOTES,
                "결론: 수정 2건\nclone https://oauth2:glpat-SECRET@gitlab.example/g/p.git\n");
        assertThat(ImplementationScratch.readReviewNotes(wt)).hasValueSatisfying(n -> assertThat(n)
                .startsWith("결론: 수정 2건")
                .doesNotContain("glpat-SECRET")
                .contains("***@gitlab.example"));

        ImplementationScratch.write(wt, ImplementationScratch.REVIEW_NOTES, "가".repeat(20_500));
        String capped = ImplementationScratch.readReviewNotes(wt).orElseThrow();
        assertThat(capped).hasSize(20_000 + "\n\n…(이하 생략)".length()).endsWith("…(이하 생략)");
    }

    @Test
    void a_symlinked_review_notes_file_is_not_read() throws Exception {
        Path secret = Files.writeString(wt.resolve("secret.txt"), "id_rsa");
        Files.createDirectories(wt.resolve(".netis"));
        try {
            Files.createSymbolicLink(wt.resolve(ImplementationScratch.REVIEW_NOTES), secret);
        } catch (Exception e) {
            assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경");
        }

        assertThat(ImplementationScratch.readReviewNotes(wt)).isEmpty();
    }

    @Test
    void delete_root_removes_only_netis() throws Exception {
        ImplementationScratch.write(wt, ImplementationScratch.planPath(1), "p");
        Files.createDirectories(wt.resolve(".design"));

        ImplementationScratch.deleteRoot(wt);

        assertThat(wt.resolve(".netis")).doesNotExist();
        assertThat(wt.resolve(".design")).exists();
    }

    @Test
    void delete_all_removes_every_worker_scratch_file_but_nothing_else() throws Exception {
        ImplementationScratch.write(wt, ImplementationScratch.planPath(1), "p");
        Files.createDirectories(wt.resolve(".design"));
        Files.writeString(wt.resolve(".design/a.html"), "x");
        Files.writeString(wt.resolve(".netis-deploy-env.json"), "{}");
        Files.writeString(wt.resolve("keep.txt"), "k");

        ImplementationScratch.deleteAll(wt);

        assertThat(wt.resolve(".netis")).doesNotExist();
        assertThat(wt.resolve(".design")).doesNotExist();
        assertThat(wt.resolve(".netis-deploy-env.json")).doesNotExist();
        assertThat(wt.resolve("keep.txt")).exists();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationScratchTest"`
Expected: 컴파일 실패 — `ImplementationScratch` 없음.

- [ ] **Step 3: 구현**

`src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationScratch.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 구현 세션의 워커 임시 파일 (스펙 2026-10-15 §6) — .netis/ 아래 계획·설계 파일(리뷰어·SDD가 읽음)과 리뷰 메모(PR 본문으로
 * 회수), 그리고 커밋·WIP 보존 전에 지우는 정리. 이 이름들은 WorktreeService.WORKER_EXCLUDES로 커밋에서도 빠진다.
 */
@Slf4j
final class ImplementationScratch {

    static final String ROOT = ".netis";
    static final String REVIEW_NOTES = ".netis/review-notes.md";
    static final int REVIEW_NOTES_MAX_CHARS = 20_000;

    /** SDD task-brief(awk)가 찾는 제목 — `^#+[ \t]+Task[ \t]+N`. */
    private static final Pattern TASK_HEADING = Pattern.compile("^#+[ \\t]+Task[ \\t]+[0-9]+");
    /** 인터뷰 플랜의 한국어 제목 `### 작업 N`. */
    private static final Pattern KOREAN_TASK_HEADING = Pattern.compile("^(#+[ \\t]+)작업([ \\t]+[0-9]+)");

    private ImplementationScratch() {}

    static String planPath(long taskId) {
        return ROOT + "/plan/task-" + taskId + "-plan.md";
    }

    static String designPath(long taskId) {
        return ROOT + "/plan/task-" + taskId + "-design.md";
    }

    static void write(Path worktree, String relPath, String content) throws IOException {
        Path target = worktree.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** 코드 블록(```) 밖의 `### 작업 N` 제목을 `### Task N`으로 — SDD 스크립트는 `Task N`만 찾는다 (스펙 §6.4). */
    static String normalizeTaskHeadings(String plan) {
        if (plan == null) return "";
        String[] lines = plan.split("\n", -1);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence) lines[i] = KOREAN_TASK_HEADING.matcher(lines[i]).replaceFirst("$1Task$2");
        }
        return String.join("\n", lines);
    }

    /** task-brief와 같은 규칙(코드 블록 밖)으로 센 `Task N` 제목 수 — 0이면 SDD가 태스크를 못 찾는다. */
    static int countTaskHeadings(String plan) {
        if (plan == null) return 0;
        int count = 0;
        boolean inFence = false;
        for (String line : plan.split("\n", -1)) {
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && TASK_HEADING.matcher(line).find()) count++;
        }
        return count;
    }

    /**
     * 리뷰 메모 회수 — 없거나 비었거나 읽지 못하면 empty. 심볼릭 링크는 따라가지 않는다(에이전트가 다른 파일을 가리키게
     * 해 PR 본문으로 빼내지 못하게). 인증 URL은 가리고 상한에서 자른다 (스펙 §6.7).
     */
    static Optional<String> readReviewNotes(Path worktree) {
        Path notes = worktree.resolve(REVIEW_NOTES);
        try {
            if (!Files.isRegularFile(notes, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            String text = GitRemotes.mask(Files.readString(notes, StandardCharsets.UTF_8)).strip();
            if (text.isEmpty()) return Optional.empty();
            if (text.length() > REVIEW_NOTES_MAX_CHARS) {
                text = text.substring(0, REVIEW_NOTES_MAX_CHARS) + "\n\n…(이하 생략)";
            }
            return Optional.of(text);
        } catch (Exception e) {
            log.warn("리뷰 메모 읽기 실패 (PR 본문에서 생략): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 성공 경로 — .netis/만 지운다(.design/·배포 env 목록은 기존 코드가 따로 처리). 링크는 따라가지 않는다. */
    static void deleteRoot(Path worktree) {
        deleteTreeQuietly(worktree.resolve(ROOT));
    }

    /** 중단 경로(WIP 보존 전) — 워커 임시 파일 전부. */
    static void deleteAll(Path worktree) {
        deleteTreeQuietly(worktree.resolve(ROOT));
        deleteTreeQuietly(worktree.resolve(".design"));
        try {
            Files.deleteIfExists(worktree.resolve(DeployEnvManifest.FILE_NAME));
        } catch (IOException e) {
            log.warn("배포 env 목록 삭제 실패: {}", e.getMessage());
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(dir);
        if (d.failures() > 0) log.warn("구현 임시 파일 삭제 미완료: {} ({}개 항목 실패)", dir, d.failures());
    }
}
```

(`DeployEnvManifest.FILE_NAME`은 `.netis-deploy-env.json` — `WorkerMainLoopDeployEnvTest`가 쓰는 기존 상수다.)

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationScratchTest"`
Expected: PASS (심볼릭 링크 테스트는 권한이 없으면 SKIPPED)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationScratch.java src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationScratchTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(worker): 구현 임시 파일 — 계획·설계 파일, Task N 제목 변환, 리뷰 메모 회수 (스펙 2026-10-15 §6)"
```

---

### Task 11: SDD 플러그인 경로·대신 진행 판정

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/SuperpowersPlugin.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeResolver.java`
- Modify: `src/main/resources/application-worker.yml` (`netis-maker.worker` 아래 키 1개)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/SuperpowersPluginTest.java`, `src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeResolverTest.java`

**Interfaces:**
- Produces: `@Component SuperpowersPlugin(String configuredPath)`(`@Value("${netis-maker.worker.superpowers-plugin-path:}")`), `Optional<Path> sddReadyPath()`, `static final String SDD_SKILL = "skills/subagent-driven-development/SKILL.md"`
- Produces: `ImplementationModeResolver.resolve(ImplementationMode requested, Inputs in) → Resolution`, `record Inputs(boolean pluginReady, boolean excludesReady, boolean hasPlan, boolean planFileWritten, int taskHeadings)`, `record Resolution(ImplementationMode effective, String fallbackReason)` + `boolean fellBack()`, 이유 상수 `NO_PLUGIN`·`NO_EXCLUDE`·`NO_PLAN`·`PLAN_WRITE_FAILED`·`NO_TASK_HEADINGS`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/workerdaemon/SuperpowersPluginTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** SDD에 붙일 superpowers 플러그인 경로 (스펙 2026-10-15 §6.4·§6.9). */
class SuperpowersPluginTest {

    @TempDir Path tmp;

    @Test
    void a_plugin_dir_with_the_sdd_skill_is_ready() throws Exception {
        Files.createDirectories(tmp.resolve("skills/subagent-driven-development"));
        Files.writeString(tmp.resolve(SuperpowersPlugin.SDD_SKILL), "# SDD");

        assertThat(new SuperpowersPlugin(tmp.toString()).sddReadyPath()).contains(tmp);
        assertThat(new SuperpowersPlugin("  " + tmp + "  ").sddReadyPath()).contains(tmp);
    }

    @Test
    void blank_missing_or_incomplete_paths_are_not_ready() {
        assertThat(new SuperpowersPlugin("").sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(null).sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(tmp.resolve("nope").toString()).sddReadyPath()).isEmpty();
        assertThat(new SuperpowersPlugin(tmp.toString()).sddReadyPath()).isEmpty();   // SKILL.md 없음
    }
}
```

`src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeResolverTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.entity.ImplementationMode;
import org.junit.jupiter.api.Test;

import static com.hamonsoft.netismaker.entity.ImplementationMode.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 준비가 안 되면 한 단계 낮은 방식으로 (스펙 2026-10-15 §6.5). */
class ImplementationModeResolverTest {

    private static final ImplementationModeResolver.Inputs READY =
            new ImplementationModeResolver.Inputs(true, true, true, true, 3);

    private static ImplementationModeResolver.Resolution resolve(ImplementationMode m, boolean plugin,
                                                                 boolean exclude, boolean plan,
                                                                 boolean written, int headings) {
        return ImplementationModeResolver.resolve(m,
                new ImplementationModeResolver.Inputs(plugin, exclude, plan, written, headings));
    }

    @Test
    void ready_modes_run_as_requested() {
        assertThat(ImplementationModeResolver.resolve(DIRECT, READY)).isEqualTo(new ImplementationModeResolver.Resolution(DIRECT, null));
        assertThat(ImplementationModeResolver.resolve(REVIEW, READY)).isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, null));
        assertThat(ImplementationModeResolver.resolve(SDD, READY)).isEqualTo(new ImplementationModeResolver.Resolution(SDD, null));
        assertThat(ImplementationModeResolver.resolve(null, READY).effective()).isEqualTo(DIRECT);
        assertThat(ImplementationModeResolver.resolve(SDD, READY).fellBack()).isFalse();
    }

    @Test
    void sdd_falls_back_to_review_with_the_first_missing_piece() {
        assertThat(resolve(SDD, false, true, true, true, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_PLUGIN));
        assertThat(resolve(SDD, true, false, true, true, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_EXCLUDE));
        assertThat(resolve(SDD, true, true, false, false, 0))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_PLAN));
        assertThat(resolve(SDD, true, true, true, true, 0))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, ImplementationModeResolver.NO_TASK_HEADINGS));
    }

    @Test
    void an_unwritable_plan_file_drops_review_and_sdd_to_direct() {
        assertThat(resolve(SDD, true, true, true, false, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(DIRECT, ImplementationModeResolver.PLAN_WRITE_FAILED));
        assertThat(resolve(REVIEW, true, true, true, false, 3))
                .isEqualTo(new ImplementationModeResolver.Resolution(DIRECT, ImplementationModeResolver.PLAN_WRITE_FAILED));
    }

    @Test
    void review_without_a_plan_still_reviews_against_the_requirements() {
        assertThat(resolve(REVIEW, false, false, false, false, 0))
                .isEqualTo(new ImplementationModeResolver.Resolution(REVIEW, null));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.SuperpowersPluginTest" --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationModeResolverTest"`
Expected: 컴파일 실패 — 두 클래스 없음.

- [ ] **Step 3: 구현**

`src/main/java/com/hamonsoft/netismaker/workerdaemon/SuperpowersPlugin.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * SDD 구현에 --plugin-dir로 붙일 superpowers 플러그인 경로 (스펙 2026-10-15 §6.4·§6.9). 기동 스크립트가 인터뷰 서비스와
 * 같은 SUPERPOWERS_PLUGIN_PATH를 넘긴다. 경로 아래 skills/subagent-driven-development/SKILL.md가 있어야 SDD 가능 —
 * 없으면 SDD 요청은 리뷰로 대신 진행한다. WorkerProperties 레코드에 넣지 않은 것은 그 레코드를 위치 인자로 만드는
 * 테스트가 12곳이라서다.
 */
@Component
@Profile("worker")
@Slf4j
public class SuperpowersPlugin {

    static final String SDD_SKILL = "skills/subagent-driven-development/SKILL.md";

    private final String configured;
    private final Path ready;

    public SuperpowersPlugin(@Value("${netis-maker.worker.superpowers-plugin-path:}") String configuredPath) {
        this.configured = configuredPath == null ? "" : configuredPath.trim();
        this.ready = resolve(this.configured);
    }

    @PostConstruct
    void logStatus() {
        if (ready != null) {
            log.info("SDD 플러그인: {}", ready);
        } else if (configured.isEmpty()) {
            log.warn("SDD 플러그인 없음(SUPERPOWERS_PLUGIN_PATH 미설정) — SDD 요청은 리뷰로 대신 진행");
        } else {
            log.warn("SDD 플러그인 없음({} 아래 {} 없음) — SDD 요청은 리뷰로 대신 진행", configured, SDD_SKILL);
        }
    }

    /** SDD를 돌릴 수 있는 플러그인 경로 — 준비가 안 됐으면 empty. */
    public Optional<Path> sddReadyPath() {
        return Optional.ofNullable(ready);
    }

    static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) return null;
        try {
            Path dir = Path.of(configured.trim());
            return Files.isRegularFile(dir.resolve(SDD_SKILL)) ? dir : null;
        } catch (Exception e) {
            return null;
        }
    }
}
```

`src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeResolver.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.entity.ImplementationMode;

/**
 * 요청한 구현 방식을 실제로 쓸 수 있는지 판정 — 준비가 안 되면 한 단계 낮춘다(SDD → 리뷰 → 직접, 스펙 2026-10-15 §6.5).
 * 실패로 끝내지 않는 이유: 구현실패에는 재시도 외에 다른 길이 없고, 낮은 방식도 같은 계획을 구현한다.
 */
final class ImplementationModeResolver {

    static final String NO_PLUGIN = "플러그인 경로 없음";
    static final String NO_EXCLUDE = "info/exclude 기록 실패";
    static final String NO_PLAN = "계획 본문 없음";
    static final String PLAN_WRITE_FAILED = "계획 파일 쓰기 실패";
    static final String NO_TASK_HEADINGS = "계획에서 Task N 제목을 찾지 못함";

    /** 워커가 모은 준비 결과. taskHeadings는 SDD용으로 변환한 계획의 `Task N` 제목 수. */
    record Inputs(boolean pluginReady, boolean excludesReady, boolean hasPlan, boolean planFileWritten,
                  int taskHeadings) {}

    /** effective = 실제로 쓸 방식, fallbackReason = 낮췄을 때 이유(아니면 null). */
    record Resolution(ImplementationMode effective, String fallbackReason) {
        boolean fellBack() {
            return fallbackReason != null;
        }
    }

    private ImplementationModeResolver() {}

    static Resolution resolve(ImplementationMode requested, Inputs in) {
        ImplementationMode mode = requested == null ? ImplementationMode.DIRECT : requested;
        // 리뷰·SDD 모두 계획 파일을 읽는다 — 본문이 있는데 파일을 못 썼으면 직접까지 내려간다
        boolean planFileBroken = in.hasPlan() && !in.planFileWritten();
        if (mode == ImplementationMode.SDD) {
            String reason = !in.pluginReady() ? NO_PLUGIN
                    : !in.excludesReady() ? NO_EXCLUDE
                    : !in.hasPlan() ? NO_PLAN
                    : planFileBroken ? PLAN_WRITE_FAILED
                    : in.taskHeadings() == 0 ? NO_TASK_HEADINGS
                    : null;
            if (reason == null) return new Resolution(ImplementationMode.SDD, null);
            if (planFileBroken) return new Resolution(ImplementationMode.DIRECT, PLAN_WRITE_FAILED);
            return new Resolution(ImplementationMode.REVIEW, reason);
        }
        if (mode == ImplementationMode.REVIEW && planFileBroken) {
            return new Resolution(ImplementationMode.DIRECT, PLAN_WRITE_FAILED);
        }
        return new Resolution(mode, null);
    }
}
```

`src/main/resources/application-worker.yml` — `implementation-timeout: ${IMPLEMENTATION_TIMEOUT:45m}` 줄과 그 위 주석을 다음으로 바꾼다(들여쓰기 4칸):

```yaml
    # 구현 claude 상한 — API가 방식별 상한을 claim에 실어 보내면 그 값을 쓰고(스펙 2026-10-15 §5.4), 이 값은 옛 API 대비용
    implementation-timeout: ${IMPLEMENTATION_TIMEOUT:45m}
    # SDD 구현용 superpowers 플러그인 경로 (스펙 2026-10-15 §6.9) — SuperpowersPlugin이 읽는다. 비면 SDD 요청은 리뷰로 대신 진행
    superpowers-plugin-path: ${SUPERPOWERS_PLUGIN_PATH:}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.SuperpowersPluginTest" --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationModeResolverTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerDaemonConstructorContractTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerPropertiesYamlBindingTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/SuperpowersPlugin.java src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeResolver.java src/main/resources/application-worker.yml src/test/java/com/hamonsoft/netismaker/workerdaemon/SuperpowersPluginTest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeResolverTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(worker): SDD 플러그인 경로·준비 실패 시 낮은 방식 판정 (스펙 2026-10-15 §6.5·§6.9)"
```

---

### Task 12: 방식별 구현 프롬프트

**Files:**
- Create: `src/main/resources/prompts/implementation-review-section.md`
- Create: `src/main/resources/prompts/implementation-reviewer.md`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationPrompts.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java` (`fillImplementationPrompt` 6인자, `defaultImplementationPrompt`, `renderPlanSection` Javadoc)
- Modify: `src/main/resources/application-worker.yml` (구현 템플릿 커밋 문구 → `{commit_rule}`, 주석)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationPromptsTest.java` (생성), `WorkerMainLoopPlanSectionTest.java` (테스트 2개 추가)

**Interfaces:**
- Consumes: `ImplementationScratch.planPath/designPath` (Task 10), `ImplementationModeResolver` 이유 상수 (Task 11), `WorkerMainLoop.renderPlanSection` (기존)
- Produces: `ImplementationPrompts.DIRECT_COMMIT_RULE`, `SDD_COMMIT_RULE`, `static String commitRule(ImplementationMode effective)`, `static String planSection(WorkerTaskResponse task, ImplementationMode effective, String baseSha, boolean designWritten)`, `static String modeLine(ImplementationMode requested, ImplementationMode effective, int attempt, String fallbackReason)`, `static String withRo(String word)`
- Produces: `WorkerMainLoop.fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName, String planSection, String commitRule)` — 기존 4인자는 직접 방식 조각으로 위임

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationPromptsTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.hamonsoft.netismaker.entity.ImplementationMode.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 방식별 구현 프롬프트 조각 (스펙 2026-10-15 §6.2~§6.4·§6.7). */
class ImplementationPromptsTest {

    private static final String PLAN = "# 계획\n\n### 작업 1: 섹션\n";

    private static WorkerTaskResponse task(String plan) {
        Task t = Task.create("owner/repo", "main", "제목", "요구사항 본문", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 13L);
        return WorkerTaskResponse.forImplementation(t, null, null, plan);
    }

    @Test
    void direct_is_exactly_the_existing_plan_section_and_commit_rule() {
        assertThat(ImplementationPrompts.planSection(task(PLAN), DIRECT, "base1", false))
                .isEqualTo(WorkerMainLoop.renderPlanSection(task(PLAN)));
        assertThat(ImplementationPrompts.commitRule(DIRECT))
                .isEqualTo("의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)");
        assertThat(ImplementationPrompts.commitRule(REVIEW)).isEqualTo(ImplementationPrompts.DIRECT_COMMIT_RULE);
    }

    @Test
    void review_keeps_direct_implementation_and_adds_one_opus_review_with_the_scope_rule() {
        String s = ImplementationPrompts.planSection(task(PLAN), REVIEW, "abc1234", false);

        assertThat(s).contains("### 작업 1: 섹션")
                .contains("리뷰만 아래 \"마지막 리뷰\"대로 서브에이전트 하나에 맡깁니다")
                .contains("model opus")
                .contains("계획 범위 안의 결함")
                .contains("계획에 없는 동작 규칙 변경")
                .contains(".netis/review-notes.md")
                .contains("The approved plan is in `.netis/plan/task-13-plan.md`. Read it in full.")
                .contains("git diff abc1234")
                .doesNotContain("__REVIEWER__").doesNotContain("__REQUIREMENTS__").doesNotContain("__BASE_SHA__")
                .doesNotContain("MIT License").doesNotContain("<!--");
    }

    @Test
    void review_without_a_plan_reviews_against_the_requirements() {
        String s = ImplementationPrompts.planSection(task(null), REVIEW, "abc1234", false);

        assertThat(s).doesNotContain("확정된 구현 계획")
                .contains("There is no plan file")
                .contains("요구사항 원문");
    }

    @Test
    void sdd_points_at_the_plan_file_and_forbids_push_finishing_and_questions() {
        String s = ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", true);

        assertThat(s).contains("`.netis/plan/task-13-plan.md`")
                .contains("`.netis/plan/task-13-design.md`")
                .contains("superpowers:subagent-driven-development")
                .contains("새 worktree나 브랜치를 만들지 말고")
                .contains("git push")
                .contains("finishing-a-development-branch")
                .contains("Ruling")
                .contains("계획 범위 안의 결함")
                .contains(".netis/review-notes.md")
                .doesNotContain("### 작업 1: 섹션");   // 본문은 넣지 않고 경로만
        assertThat(ImplementationPrompts.planSection(task(PLAN), SDD, "abc1234", false)).doesNotContain("-design.md");
        assertThat(ImplementationPrompts.commitRule(SDD)).contains("태스크별 커밋").contains("push는 하지 마세요");
    }

    @Test
    void mode_line_names_attempt_and_fallback_with_korean_particles() {
        assertThat(ImplementationPrompts.modeLine(REVIEW, REVIEW, 1, null)).isEqualTo("- **구현 방식**: 리뷰\n");
        assertThat(ImplementationPrompts.modeLine(SDD, SDD, 2, null)).isEqualTo("- **구현 방식**: SDD (2회째)\n");
        assertThat(ImplementationPrompts.modeLine(SDD, REVIEW, 1, ImplementationModeResolver.NO_PLUGIN))
                .isEqualTo("- **구현 방식**: 리뷰 (SDD 요청 — 플러그인 경로 없음으로 리뷰로 진행)\n");
        assertThat(ImplementationPrompts.modeLine(SDD, DIRECT, 3, ImplementationModeResolver.PLAN_WRITE_FAILED))
                .isEqualTo("- **구현 방식**: 직접 (3회째) (SDD 요청 — 계획 파일 쓰기 실패로 직접으로 진행)\n");
    }

    @Test
    void with_ro_follows_the_final_consonant() {
        assertThat(ImplementationPrompts.withRo("리뷰")).isEqualTo("리뷰로");
        assertThat(ImplementationPrompts.withRo("직접")).isEqualTo("직접으로");
        assertThat(ImplementationPrompts.withRo("서울")).isEqualTo("서울로");
        assertThat(ImplementationPrompts.withRo("SDD")).isEqualTo("SDD로");
    }
}
```

`WorkerMainLoopPlanSectionTest.java` 끝에 추가:

```java
    @Test
    void both_templates_take_the_commit_rule_and_keep_the_direct_wording() {
        for (String tpl : List.of(workerYmlTemplate(), WorkerMainLoop.defaultImplementationPrompt())) {
            assertThat(tpl).contains("{commit_rule}");
            String prompt = WorkerMainLoop.fillImplementationPrompt(tpl, implementationTask(PLAN),
                    "35c9110", "netismaker/task-3-readme");
            assertThat(prompt).doesNotContain("{commit_rule}")
                    .contains("의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)");
        }
    }

    @Test
    void the_six_argument_fill_uses_the_given_plan_section_and_commit_rule() {
        String prompt = WorkerMainLoop.fillImplementationPrompt(workerYmlTemplate(), implementationTask(PLAN),
                "35c9110", "netismaker/task-3-readme", "\n## SDD 절\n", ImplementationPrompts.SDD_COMMIT_RULE);

        assertThat(prompt).contains("## SDD 절")
                .contains(ImplementationPrompts.SDD_COMMIT_RULE)
                .doesNotContain("### 작업 1: 섹션 삽입")
                .endsWith(WorkerMainLoop.DEPLOY_ENV_INSTRUCTION);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationPromptsTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopPlanSectionTest"`
Expected: 컴파일 실패 — `ImplementationPrompts`·6인자 `fillImplementationPrompt` 없음.

- [ ] **Step 3: 리소스 작성**

`src/main/resources/prompts/implementation-review-section.md` (첫 줄은 빈 줄):

````markdown

## 마지막 리뷰 (구현과 테스트를 마친 뒤 1회)
1. 서브에이전트 하나(Agent 도구, model opus)에 전체 변경 리뷰를 맡기세요. 리뷰는 이 한 번뿐입니다 — 리뷰어를 더 띄우거나 재리뷰를 받지 마세요.
2. 리뷰어 프롬프트에는 아래 "리뷰어 지시"를 그대로 넣고, [DESCRIPTION] 자리에 구현한 내용 요약을 채우세요.
3. 리뷰 결과의 Critical·Important 가운데 계획 범위 안의 결함(깨짐·조용한 오작동·계획 미이행)만 이 세션에서 고치고 테스트를 다시 돌리세요. 계획에 없는 동작 규칙 변경(입력 검증 강화, 정규화 추가 등)은 하지 마세요.
4. `.netis/review-notes.md`에 리뷰 결론 한 줄, 고친 것, 고치지 않은 지적(Minor 포함)과 그 이유, "요청자 확인 필요" 항목을 한국어로 쓰세요. 이 파일은 워커가 PR/MR 본문에 붙인 뒤 지우며 커밋되지 않습니다.
5. 커밋하지 마세요. 리뷰어도 커밋이 아니라 작업 트리 변경을 봅니다.

### 리뷰어 지시

```
__REVIEWER__
```
````

`src/main/resources/prompts/implementation-reviewer.md` — 머리 주석(라이선스)은 프롬프트에 넣을 때 빠진다:

```text
<!--
superpowers 6.4.1 skills/requesting-code-review/code-reviewer.md 기반 사본 — 2026-10-15 실측판과 같은 세 곳만 고쳤다:
계획 위치(__REQUIREMENTS__), 커밋되지 않은 변경을 __BASE_SHA__ 기준으로 리뷰, 읽기 전용. 이 주석은 프롬프트에 넣을 때 뺀다.

MIT License

Copyright (c) 2025 Jesse Vincent

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
-->
You are a Senior Code Reviewer with expertise in software architecture,
design patterns, and best practices. Your job is to review completed work
against its plan or requirements and identify issues before they cascade.

## What Was Implemented

[DESCRIPTION]

## Requirements / Plan

__REQUIREMENTS__

## Changes to Review

The work is NOT committed. Review the uncommitted changes in this working
tree against base commit __BASE_SHA__:

    git status --porcelain
    git diff __BASE_SHA__

`git diff` does not show untracked files: read every file that
`git status --porcelain` lists as `??` in full.

## The spec is a vision document

The spec says what the software must do. It does not enumerate every
input, environment, or condition the software will meet. For behavior
the spec is silent on, judge by what a reasonable person using this
software would expect: a reasonable person's expectation is a
requirement, and a spec's silence is not permission. Grade such
findings by their effect on that person, not by whether the spec
mentions the trigger.

## Declined to judge

Before your verdict, list every behavior you considered and set aside
as outside the plan or spec, one line each, with the reason. The
executor rules on each line; nothing you set aside is dropped
silently. An empty list means you set nothing aside.

## Read-Only Review

Your review is read-only on this checkout. Do not create, modify, or
delete any file, and do not change the index, HEAD, or branch state
(no git add, commit, stash, or checkout). Use git status, git diff,
and file reads only.

## You Do Not Dispatch Subagents

Do all of this review yourself. Never spawn a subagent to review part
of the diff, and never spawn another reviewer for a second opinion.
This process already provides every review seat the work gets; a
reviewer you spawn duplicates one of them at full cost, and its
verdict counts for nothing. If the diff feels too large for one
pass, review it in passes yourself and say so in your report.

## What to Check

**Plan alignment:**
- Does the implementation match the plan / requirements?
- Are deviations justified improvements, or problematic departures?
- Is all planned functionality present?

**Code quality:**
- Clean separation of concerns?
- Proper error handling?
- Type safety where applicable?
- DRY without premature abstraction?
- Edge cases handled?

**Architecture:**
- Sound design decisions?
- Reasonable scalability and performance?
- Security concerns?
- Integrates cleanly with surrounding code?

**Testing:**
- Tests verify real behavior, not mocks?
- Edge cases covered?
- Integration tests where they matter?
- All tests passing?

**Production readiness:**
- Migration strategy if schema changed?
- Backward compatibility considered?
- Documentation complete?
- No obvious bugs?

## Calibration

Categorize issues by actual severity. Not everything is Critical.
Acknowledge what was done well before listing issues — accurate praise
helps the implementer trust the rest of the feedback.

If you find significant deviations from the plan, flag them specifically
so the implementer can confirm whether the deviation was intentional.
If you find issues with the plan itself rather than the implementation,
say so.

## Output Format

### Strengths
[What's well done? Be specific.]

### Issues

#### Critical (Must Fix)
[Bugs, security issues, data loss risks, broken functionality]

#### Important (Should Fix)
[Architecture problems, missing features, poor error handling, test gaps]

#### Minor (Nice to Have)
[Code style, optimization opportunities, documentation polish]

For each issue:
- File:line reference
- What's wrong
- Why it matters
- How to fix (if not obvious)

### Recommendations
[Improvements for code quality, architecture, or process]

### Assessment

**Ready to merge?** [Yes | No | With fixes]

**Reasoning:** [1-2 sentence technical assessment]

## Critical Rules

**DO:**
- Categorize by actual severity
- Be specific (file:line, not vague)
- Explain WHY each issue matters
- Acknowledge strengths
- Give a clear verdict

**DON'T:**
- Say "looks good" without checking
- Mark nitpicks as Critical
- Give feedback on code you didn't actually read
- Be vague ("improve error handling")
- Avoid giving a clear verdict
```

(파일 내용은 `<!--`부터 마지막 `- Avoid giving a clear verdict`까지 — 위 코드 블록의 바깥 펜스는 파일에 넣지 않는다.)

- [ ] **Step 4: ImplementationPrompts 작성**

`src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationPrompts.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 방식별 구현 프롬프트 조각 (스펙 2026-10-15 §6.2~§6.4·§6.7). 직접 방식은 WorkerMainLoop.renderPlanSection 그대로다.
 * 리뷰 방식의 리뷰어 지시는 superpowers code-reviewer.md(MIT) 사본 리소스 — 플러그인 없이도 돈다.
 */
final class ImplementationPrompts {

    static final String DIRECT_COMMIT_RULE = "의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)";
    static final String SDD_COMMIT_RULE =
            "태스크별 커밋은 SDD 흐름대로 하세요 — push는 하지 마세요. 끝난 뒤 남은 변경과 push는 워커가 처리합니다";

    private static final String REVIEW_SECTION = resource("/prompts/implementation-review-section.md");
    private static final String REVIEWER = stripLeadingComment(resource("/prompts/implementation-reviewer.md"));

    private ImplementationPrompts() {}

    /** {commit_rule} — SDD만 태스크별 커밋, 나머지는 기존 문구(라이브 yml과 같은 글자). */
    static String commitRule(ImplementationMode effective) {
        return effective == ImplementationMode.SDD ? SDD_COMMIT_RULE : DIRECT_COMMIT_RULE;
    }

    /** {plan_section}. designWritten = SDD에 설계 파일을 알릴지(실제로 썼을 때만). */
    static String planSection(WorkerTaskResponse task, ImplementationMode effective, String baseSha,
                              boolean designWritten) {
        return switch (effective) {
            case DIRECT -> WorkerMainLoop.renderPlanSection(task);
            case REVIEW -> reviewPlanSection(task, baseSha);
            case SDD -> sddPlanSection(task, designWritten);
        };
    }

    private static String reviewPlanSection(WorkerTaskResponse task, String baseSha) {
        boolean hasPlan = task.planMarkdown() != null && !task.planMarkdown().isBlank();
        String planBlock = hasPlan ? """

                ## 확정된 구현 계획 (인터뷰에서 승인됨 — 이 계획을 기준으로 구현)
                아래는 요청자와의 인터뷰로 확정된 구현 계획입니다. 작업 순서·범위·파일 위치는 이 계획을 따르세요.
                - 계획 안의 git commit / push 단계는 실행하지 마세요 — 커밋은 워커가 마지막에 한 번에 처리합니다.
                - 계획의 확인·검증 단계(테스트, grep 등)는 가능하면 수행하고 결과를 출력에 남기세요.
                - 구현은 계획이 가리키는 서브에이전트/실행 스킬(subagent-driven-development 등) 없이 이 세션에서 직접 하세요. 리뷰만 아래 "마지막 리뷰"대로 서브에이전트 하나에 맡깁니다.

                """ + task.planMarkdown() + "\n"
                : "\n계획 본문이 없는 작업입니다 — 리뷰어 지시의 [DESCRIPTION]에 구현 요약과 함께 위 \"요구사항\" 원문을 그대로 넣으세요.\n";
        String requirements = hasPlan
                ? "The approved plan is in `" + ImplementationScratch.planPath(task.id()) + "`. Read it in full."
                : "There is no plan file. The task requirements are quoted under [DESCRIPTION] above; review against them.";
        String reviewer = REVIEWER.replace("__REQUIREMENTS__", requirements).replace("__BASE_SHA__", baseSha);
        return planBlock + REVIEW_SECTION.replace("__REVIEWER__", reviewer);
    }

    private static String sddPlanSection(WorkerTaskResponse task, boolean designWritten) {
        String design = designWritten
                ? " 설계 문서(스펙)는 `" + ImplementationScratch.designPath(task.id()) + "`입니다."
                : "";
        return """

                ## 확정된 구현 계획 (인터뷰에서 승인됨 — 서브에이전트 방식으로 구현)
                요청자와의 인터뷰로 확정된 구현 계획이 `%s` 파일에 있습니다(git 추적 제외 경로).%s
                - superpowers:subagent-driven-development 스킬로 이 계획 파일을 처음부터 끝까지 실행하세요. brainstorming·writing-plans는 이미 끝났으니 다시 하지 마세요.
                - 현재 디렉터리가 이미 이 작업 전용 git worktree이고, 체크아웃된 브랜치가 작업 브랜치입니다. 새 worktree나 브랜치를 만들지 말고 여기서 진행하세요.
                - 태스크별 커밋은 계획대로 해도 됩니다. 단 git push, PR/MR 생성, 브랜치 병합·삭제·리베이스는 하지 마세요 — 워커가 마지막에 처리합니다.
                - 마지막 superpowers:finishing-a-development-branch 단계는 실행하지 마세요(사람에게 선택을 묻는 단계입니다). 최종 리뷰까지 끝나면 결과를 요약해 출력하고 종료하세요.
                - 이 세션은 비대화식이라 질문에 답할 사람이 없습니다. 사람에게 묻지 말고, 판단이 필요하면 SDD 규칙대로 원장에 Ruling으로 남기고 계속하세요.
                - 리뷰 지적은 Critical·Important 가운데 계획 범위 안의 결함(깨짐·조용한 오작동·계획 미이행)만 고치세요. 계획에 없는 동작 규칙 변경(입력 검증 강화, 정규화 추가 등)은 하지 마세요.
                - 끝내기 전에 원장의 Ruling·보류(parked) 항목과 고치지 않은 지적을 이유와 함께 `.netis/review-notes.md`에 한국어로 정리하세요. 이 파일은 워커가 PR/MR 본문에 붙인 뒤 지우며 커밋되지 않습니다.
                """.formatted(ImplementationScratch.planPath(task.id()), design);
    }

    /** PR/MR 본문 방식 줄 (스펙 §6.7) — 2회째부터 회차, 낮춰서 진행했으면 요청 방식과 이유. */
    static String modeLine(ImplementationMode requested, ImplementationMode effective, int attempt,
                           String fallbackReason) {
        StringBuilder line = new StringBuilder("- **구현 방식**: ").append(effective.label());
        if (attempt >= 2) line.append(" (").append(attempt).append("회째)");
        if (fallbackReason != null && requested != null && requested != effective) {
            line.append(" (").append(requested.label()).append(" 요청 — ")
                    .append(withRo(fallbackReason)).append(' ')
                    .append(withRo(effective.label())).append(" 진행)");
        }
        return line.append('\n').toString();
    }

    /** 조사 '(으)로' — 받침 없음·ㄹ받침·한글 아님이면 '로', 그 밖의 받침이면 '으로'. */
    static String withRo(String word) {
        if (word == null || word.isEmpty()) return "";
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return word + "로";
        int finalConsonant = (last - 0xAC00) % 28;
        return word + (finalConsonant == 0 || finalConsonant == 8 ? "로" : "으로");
    }

    private static String resource(String path) {
        try (InputStream in = ImplementationPrompts.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("프롬프트 리소스 없음: " + path);
            // Windows 작업본(core.autocrlf)에서 빌드해도 프롬프트 줄바꿈은 LF로
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 리소스 머리의 `<!-- … -->`(출처·라이선스)를 뺀다 — 프롬프트에는 리뷰어 지시만. */
    static String stripLeadingComment(String text) {
        if (!text.startsWith("<!--")) return text;
        int end = text.indexOf("-->");
        if (end < 0) return text;
        String rest = text.substring(end + 3);
        return rest.startsWith("\n") ? rest.substring(1) : rest;
    }
}
```

- [ ] **Step 5: 템플릿·WorkerMainLoop 수정**

`application-worker.yml` 구현 템플릿:
- 위 주석 `# 구현 prompt 템플릿. {branch_name}/{analysis_markdown}/{subtasks_json}/{design_section}/{plan_section} 추가 placeholder.`를 `# 구현 prompt 템플릿. {branch_name}/{analysis_markdown}/{subtasks_json}/{design_section}/{plan_section}/{commit_rule} 추가 placeholder.`로.
- 템플릿 안의 `      - 의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)` 줄을 `      - {commit_rule}`로.

`WorkerMainLoop.java`:
1. `defaultImplementationPrompt()` 안의 `- 각 subtask 완료마다 의미 있는 변경을 누적하세요` 줄을 `- {commit_rule}`로.
2. 기존 `fillImplementationPrompt`(4인자, 주석 포함)를 다음으로 바꾼다:

```java
    /** 직접 방식 조각으로 채운다 — 기존 호출부·테스트용. */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName) {
        return fillImplementationPrompt(tpl, task, baseSha, branchName,
                renderPlanSection(task), ImplementationPrompts.DIRECT_COMMIT_RULE);
    }

    /**
     * 구현 프롬프트 템플릿의 placeholder를 채운다(yml 템플릿·기본 템플릿 공용). planSection·commitRule은 방식별
     * (ImplementationPrompts, 스펙 2026-10-15 §6). 끝에 배포 env 목록 지시를 붙인다.
     * {commit_rule}은 계획·디자인 본문을 넣기 전에 치환한다(본문 안의 같은 글자를 건드리지 않게).
     */
    static String fillImplementationPrompt(String tpl, WorkerTaskResponse task, String baseSha, String branchName,
                                           String planSection, String commitRule) {
        return tpl
                .replace("{github_repo}", task.githubRepo())
                .replace("{github_branch}", task.githubBranch())
                .replace("{commit_sha}", baseSha)
                .replace("{branch_name}", branchName)
                .replace("{title}", task.title())
                .replace("{description}", task.description())
                .replace("{analysis_markdown}", task.analysisMarkdown() == null ? "" : task.analysisMarkdown())
                .replace("{subtasks_json}", task.subtasksJson() == null ? "[]" : task.subtasksJson())
                .replace("{commit_rule}", commitRule)
                .replace("{design_section}", renderDesignSection(task))
                .replace("{plan_section}", planSection)
                + DEPLOY_ENV_INSTRUCTION;
    }
```

3. `renderPlanSection`의 Javadoc을 다음으로 바꾼다(본문은 그대로):

```java
    /**
     * 직접 방식의 {plan_section} — 인터뷰에서 확정된 플랜이 있으면 구현 프롬프트에 삽입할 블록, 없으면 빈 문자열
     * (인터뷰 없이 온 작업 불변). writing-plans 플랜에는 "git commit" 단계가 들어 있는데, 직접·리뷰 방식은 워커가 마지막에
     * 한 번에 커밋하므로 커밋 단계는 건너뛰라고 지시한다. 플랜 머리의 "REQUIRED SUB-SKILL: subagent-driven-development"는
     * 따르지 말라고 한다 — 서브에이전트 방식은 관리자가 고르는 별도 방식(SDD)이고, 그때는 ImplementationPrompts가
     * 플러그인(--plugin-dir)과 SDD 전용 지시를 준다 (스펙 2026-10-15 §6.2~§6.4).
     */
```

- [ ] **Step 6: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationPromptsTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopPlanSectionTest" --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDeployEnvTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/prompts/implementation-review-section.md src/main/resources/prompts/implementation-reviewer.md src/main/java/com/hamonsoft/netismaker/workerdaemon/ImplementationPrompts.java src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java src/main/resources/application-worker.yml src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationPromptsTest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopPlanSectionTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(worker): 방식별 구현 프롬프트 — 리뷰 절·SDD 절·{commit_rule} (스펙 2026-10-15 §6.2~§6.4)"
```

---

### Task 13: 워커 구현 흐름 배선

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopImplementationModeTest.java` (생성)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeImmediateDiscardTest.java`, `WorkerMainLoopDeployEnvTest.java` (생성자·스텁 갱신)

**Interfaces:**
- Consumes: Task 6 `WorkerResultRequest#withImplementationAttempt`, Task 7 `ExecOptions`·8인자 `exec`, Task 8 5인자 `commitAndPush`·`preservePartialWork`·`PartialWork`, Task 9 6인자 `create`·`ensureWorkerExcludes`, Task 10 `ImplementationScratch`, Task 11 `SuperpowersPlugin`·`ImplementationModeResolver`, Task 12 `ImplementationPrompts`·6인자 `fillImplementationPrompt`, Task 3 `WorkerTaskResponse#implementation()`
- Produces: `WorkerMainLoop` 생성자 맨 뒤 인자 `SuperpowersPlugin superpowers`; `static int implementationAttemptOf(WorkerTaskResponse)`; `static Duration implementationTimeoutOf(WorkerTaskResponse, Duration fallback)`; `static String renderPrBody(WorkerTaskResponse, long durationMs, String headSha, String modeLine, String reviewNotes)`; 구현 결과 보고(성공·실패 모두)에 시도 번호

- [ ] **Step 1: 실패하는 흐름 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopImplementationModeTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 방식별 구현 흐름 배선 (스펙 2026-10-15 §6). */
class WorkerMainLoopImplementationModeTest {

    @TempDir Path tmp;

    private WorkerHttpClient http;
    private ClaudeExecAdapter claude;
    private WorktreeService worktrees;
    private GitOpsService gitOps;
    private ResultReporter reporter;
    private GitRepoCache repos;
    private Path wt;
    private Path pluginDir;

    private static WorkerProperties props() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        return new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        http = mock(WorkerHttpClient.class);
        repos = mock(GitRepoCache.class);
        claude = mock(ClaudeExecAdapter.class);
        worktrees = mock(WorktreeService.class);
        gitOps = mock(GitOpsService.class);
        reporter = mock(ResultReporter.class);
        wt = Files.createDirectories(tmp.resolve("wt"));
        pluginDir = tmp.resolve("superpowers");
        Files.createDirectories(pluginDir.resolve("skills/subagent-driven-development"));
        Files.writeString(pluginDir.resolve(SuperpowersPlugin.SDD_SKILL), "# SDD");

        when(repos.ensureFresh(any(), eq("main"))).thenReturn(new GitRepoCache.CheckedOutRepo(tmp.toFile(), "base123"));
        when(worktrees.create(any(), anyString(), eq("main"), eq(7L), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    int attempt = inv.getArgument(5);
                    return new WorktreeService.CreatedWorktree(wt.toFile(),
                            "netismaker/task-7-add-login" + (attempt >= 2 ? "-r" + attempt : ""));
                });
        when(worktrees.ensureWorkerExcludes(any())).thenReturn(true);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())).thenReturn("head456");
        when(gitOps.createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PrInfo("https://github.com/acme/widgets/pull/3", 3));
    }

    private WorkerMainLoop loop(String pluginPath) {
        return new WorkerMainLoop(props(), http, repos, claude, mock(PromptResultParser.class),
                mock(WorkerMcpSupport.class), worktrees, gitOps, mock(DeployService.class), reporter,
                mock(SilentLossTracker.class), mock(DesignResultHarvester.class), mock(TaskCloser.class),
                new SuperpowersPlugin(pluginPath));
    }

    /** 인터뷰 플랜이 있는 구현 작업. mode가 null이면 claim에 implementation 블록이 없다(옛 API). */
    private void claims(ImplementationMode mode, int attempt, Integer timeoutMinutes, String plan) {
        WorkerTaskResponse task;
        if (mode == null) {
            task = new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                    WorkerTaskResponse.Kind.IMPLEMENTATION, List.of(), "# 설계 문서", "[]", null, null, List.of(),
                    null, null, null, null, null, null, null, null, null, null, plan);
        } else {
            Task t = Task.create("acme/widgets", "main", "Add login", "desc", "user1", 3, List.of(),
                    "claude-opus-5-5", "high");
            ReflectionTestUtils.setField(t, "id", 7L);
            t.setImplementationMode(mode);
            t.setImplementationAttempt(attempt);
            t.setImplementationTimeoutMinutes(timeoutMinutes);
            task = WorkerTaskResponse.forImplementation(t,
                    TaskAnalysis.create(7L, "# 설계 문서", "[]", null, 1000L), null, plan);
        }
        when(http.nextTask()).thenReturn(Optional.of(task));
    }

    private record Exec(String prompt, Duration timeout, ClaudeExecAdapter.ExecOptions options) {}

    private AtomicReference<Exec> claudeSucceeds(Consumer<Path> sideEffect) throws Exception {
        AtomicReference<Exec> seen = new AtomicReference<>();
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seen.set(new Exec(inv.getArgument(0), inv.getArgument(2), inv.getArgument(7)));
                    sideEffect.accept(wt);
                    return new ClaudeExecAdapter.ExecResult(0, "done", 10, null);
                });
        return seen;
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkerResultRequest reported() {
        ArgumentCaptor<WorkerResultRequest> cap = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(7L), cap.capture());
        return cap.getValue();
    }

    private String prBody() throws Exception {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), body.capture());
        return body.getValue();
    }

    @Test
    void old_api_payload_runs_direct_first_attempt_with_the_worker_timeout() throws Exception {
        claims(null, 0, null, "### 작업 1: A");
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop("").pollAndProcess();

        verify(worktrees).create(any(), anyString(), eq("main"), eq(7L), anyString(), eq(1));
        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(45));
        assertThat(exec.get().options().extraArgs()).isEmpty();
        assertThat(exec.get().options().env())
                .containsEntry("GIT_AUTHOR_NAME", "netisMaker")
                .containsEntry("GIT_COMMITTER_EMAIL", "netismaker@hamonsoft.local");
        assertThat(exec.get().prompt()).contains("이 세션에서 직접 구현하세요").doesNotContain("마지막 리뷰");
        assertThat(wt.resolve(".netis")).doesNotExist();
        assertThat(reported().implementationAttempt()).isEqualTo(1);
        assertThat(prBody()).contains("- **구현 방식**: 직접\n");
    }

    @Test
    void sdd_with_a_ready_plugin_adds_plugin_dir_and_hands_over_a_normalized_plan_file() throws Exception {
        claims(ImplementationMode.SDD, 2, 240, "# 계획\n### 작업 1: A\n### 작업 2: B\n");
        AtomicReference<String> planAtExec = new AtomicReference<>();
        AtomicReference<Exec> exec = claudeSucceeds(dir -> {
            try {
                planAtExec.set(Files.readString(dir.resolve(".netis/plan/task-7-plan.md")));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            write(dir.resolve(".netis/review-notes.md"), "Ruling: 범위 밖 정규화는 보류");
        });
        AtomicBoolean netisAtCommit = new AtomicBoolean(true);
        when(gitOps.commitAndPush(any(), any(), anyString(), anyString(), eq("base123"))).thenAnswer(inv -> {
            netisAtCommit.set(Files.exists(wt.resolve(".netis")));
            return "head456";
        });

        loop(pluginDir.toString()).pollAndProcess();

        verify(worktrees).create(any(), anyString(), eq("main"), eq(7L), anyString(), eq(2));
        assertThat(exec.get().timeout()).isEqualTo(Duration.ofMinutes(240));
        assertThat(exec.get().options().extraArgs()).containsExactly("--plugin-dir", pluginDir.toString());
        assertThat(exec.get().prompt())
                .contains("superpowers:subagent-driven-development 스킬로")
                .contains(ImplementationPrompts.SDD_COMMIT_RULE)
                .contains("`.netis/plan/task-7-design.md`");
        assertThat(planAtExec.get()).contains("### Task 1: A").contains("### Task 2: B");
        assertThat(netisAtCommit.get()).isFalse();
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.implementationAttempt()).isEqualTo(2);
        assertThat(prBody()).contains("- **구현 방식**: SDD (2회째)\n")
                .contains("## 리뷰 메모\nRuling: 범위 밖 정규화는 보류");
    }

    @Test
    void sdd_without_a_plugin_falls_back_to_review_and_says_so() throws Exception {
        claims(ImplementationMode.SDD, 1, 240, "### 작업 1: A");
        AtomicReference<Exec> exec = claudeSucceeds(dir -> { });

        loop("").pollAndProcess();

        assertThat(exec.get().options().extraArgs()).isEmpty();
        assertThat(exec.get().prompt()).contains("마지막 리뷰").doesNotContain("subagent-driven-development 스킬로");
        assertThat(prBody()).contains("- **구현 방식**: 리뷰 (SDD 요청 — 플러그인 경로 없음으로 리뷰로 진행)\n");
    }

    @Test
    void review_notes_go_into_the_pr_body_masked_and_never_into_the_commit() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A");
        claudeSucceeds(dir -> write(dir.resolve(".netis/review-notes.md"),
                "결론: Important 1건 수정\n참고 https://oauth2:glpat-SECRET@gitlab.example/g/p.git"));

        loop("").pollAndProcess();

        assertThat(prBody()).contains("## 리뷰 메모\n결론: Important 1건 수정").doesNotContain("glpat-SECRET");
        assertThat(wt.resolve(".netis")).doesNotExist();
    }

    @Test
    void a_timeout_preserves_partial_work_and_reports_the_pushed_branch() throws Exception {
        claims(ImplementationMode.SDD, 2, 240, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IOException("claude timeout after PT4H"));
        when(gitOps.preservePartialWork(any(), any(), anyString(), eq("base123"), anyString()))
                .thenReturn(new GitOpsService.PartialWork(2, "wip999", null));

        loop(pluginDir.toString()).pollAndProcess();

        ArgumentCaptor<String> wip = ArgumentCaptor.forClass(String.class);
        verify(gitOps).preservePartialWork(any(), any(), eq("netismaker/task-7-add-login-r2"), eq("base123"),
                wip.capture());
        assertThat(wip.getValue()).isEqualTo("wip: 구현 중단(시간 초과) — task #7");
        WorkerResultRequest r = reported();
        assertThat(r.status()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
        assertThat(r.failureReason()).contains("claude timeout after PT4H")
                .contains("지금까지 작업(커밋 2개)은 브랜치 `netismaker/task-7-add-login-r2`에 올려 둠");
        assertThat(r.headBranch()).isEqualTo("netismaker/task-7-add-login-r2");
        assertThat(r.headSha()).isEqualTo("wip999");
        assertThat(r.implementationAttempt()).isEqualTo(2);
        verify(gitOps, never()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void a_non_zero_exit_reports_a_failed_partial_push() throws Exception {
        claims(ImplementationMode.REVIEW, 1, 80, "### 작업 1: A");
        when(claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenReturn(new ClaudeExecAdapter.ExecResult(1, "boom", 10, null));
        when(gitOps.preservePartialWork(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new GitOpsService.PartialWork(1, "wip1", "push 거부"));

        loop("").pollAndProcess();

        WorkerResultRequest r = reported();
        assertThat(r.failureReason()).contains("claude exit=1")
                .contains("부분 작업 push 실패(worktree에만 남음): push 거부");
        assertThat(r.headSha()).isNull();
        ArgumentCaptor<String> wip = ArgumentCaptor.forClass(String.class);
        verify(gitOps).preservePartialWork(any(), any(), anyString(), anyString(), wip.capture());
        assertThat(wip.getValue()).startsWith("wip: 구현 중단(비정상 종료)");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopImplementationModeTest"`
Expected: 컴파일 실패 — 14인자 `WorkerMainLoop` 생성자 없음.

- [ ] **Step 3: WorkerMainLoop 구현**

1. import 추가: `com.hamonsoft.netismaker.entity.ImplementationMode`, `java.nio.file.Path`, `java.time.Duration`, `java.util.Map`.

2. 필드·생성자 — `private final TaskCloser closer;` 아래에 `private final SuperpowersPlugin superpowers;`를 두고, 생성자 매개변수 끝 `TaskCloser closer) {`를 `TaskCloser closer, SuperpowersPlugin superpowers) {`로, 본문 끝에 `this.superpowers = superpowers;`를 더한다.

3. 클래스 Javadoc의 IMPLEMENTATION 흐름을 다음으로 바꾼다:

```java
 *   IMPLEMENTATION 흐름 (구현 방식은 claim의 implementation 블록 — 스펙 2026-10-15):
 *     1) git fetch/clone (베이스 브랜치 최신화)
 *     2) WorktreeService.create → 새 브랜치 worktree (재시도는 -rN) + 워커 임시 파일 info/exclude
 *     3) 방식 준비(계획·설계 파일, 대신 진행 판정) → 방식별 구현 prompt + claude -p exec (worktree에서)
 *        → 실패하면 남은 변경을 WIP 커밋으로 push(부분 작업 보존)
 *        → 성공하면 리뷰 메모 회수, .netis/·목업 삭제, 배포 env 목록(.netis-deploy-env.json) 회수·삭제
 *     4) GitOpsService.commitAndPush(에이전트 커밋 수용) → headSha
 *     5) GitOpsService.createDraftPr → prUrl/prNumber (본문에 방식 줄·리뷰 메모)
 *     6) POST result (status=PR_CREATED 또는 IMPLEMENTATION_FAILED, 시도 번호 포함)
```

4. `pollAndProcess`의 catch 안 구현 실패 보고를 다음으로:

```java
                case IMPLEMENTATION -> safePostImplementationFailure(task.id(), implementationAttemptOf(task),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        null, null, null);
```

5. `processImplementation` 메서드 전체를 다음으로 바꾼다:

```java
    private void processImplementation(WorkerTaskResponse task) throws Exception {
        WorkerTaskResponse.ImplementationSpec spec = task.implementation();
        ImplementationMode requested = ImplementationMode.fromWireOrDirect(spec == null ? null : spec.mode());
        int attempt = implementationAttemptOf(task);

        // 1. 베이스 브랜치 최신화 (worktree add 시 origin/{base} 참조)
        GitRepoCache.CheckedOutRepo repo;
        try {
            repo = repos.ensureFresh(task.repoRef(), task.githubBranch());
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "레포 fetch 실패: " + e.getMessage(), null, null, null);
            return;
        }

        // 2. worktree + 새 브랜치 생성 — 재시도(2회째부터)는 -rN 브랜치 (스펙 2026-10-15 §6.1)
        WorktreeService.CreatedWorktree wt;
        try {
            wt = worktrees.create(repo.dir(), GitRemotes.localKey(task.repoRef()),
                    task.githubBranch(), task.id(), task.title(), attempt);
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "worktree 생성 실패: " + e.getMessage(), null, null, null);
            return;
        }
        Path wtPath = wt.dir().toPath();

        // 승인된 디자인이 있으면 목업을 워크트리에 배치 (참조용 — 커밋 전 삭제)
        File designDir = null;
        if (task.mockupFilesJson() != null && !task.mockupFilesJson().isBlank()) {
            try {
                designDir = new File(wt.dir(), ".design");
                writeMockupFiles(designDir, task.mockupFilesJson());
                java.nio.file.Files.writeString(new File(designDir, "DESIGN.md").toPath(),
                        nvl(task.designMarkdown(), ""));
            } catch (Exception e) {
                log.warn("목업 배치 실패 (디자인 없이 진행): {}", e.getMessage());
                designDir = null;
            }
        }

        // 3. 구현 방식 준비 — 임시 파일 exclude, 계획·설계 파일, 대신 진행 판정 (스펙 2026-10-15 §6.1·§6.5)
        boolean excludesReady = worktrees.ensureWorkerExcludes(wt.dir());
        boolean hasPlan = task.planMarkdown() != null && !task.planMarkdown().isBlank();
        String planText = requested == ImplementationMode.SDD && hasPlan
                ? ImplementationScratch.normalizeTaskHeadings(task.planMarkdown())
                : task.planMarkdown();
        boolean planWritten = false;
        boolean designWritten = false;
        if (requested != ImplementationMode.DIRECT && hasPlan) {
            planWritten = writeScratch(wtPath, ImplementationScratch.planPath(task.id()), planText);
            if (requested == ImplementationMode.SDD
                    && task.analysisMarkdown() != null && !task.analysisMarkdown().isBlank()) {
                designWritten = writeScratch(wtPath, ImplementationScratch.designPath(task.id()),
                        task.analysisMarkdown());
            }
        }
        ImplementationModeResolver.Resolution mode = ImplementationModeResolver.resolve(requested,
                new ImplementationModeResolver.Inputs(superpowers.sddReadyPath().isPresent(), excludesReady,
                        hasPlan, planWritten, hasPlan ? ImplementationScratch.countTaskHeadings(planText) : 0));
        if (mode.fellBack()) {
            log.warn("구현 방식 대신 진행: task={} {} → {} ({})",
                    task.id(), requested, mode.effective(), mode.fallbackReason());
        }
        String modeLine = ImplementationPrompts.modeLine(requested, mode.effective(), attempt, mode.fallbackReason());
        String fallbackNote = mode.fellBack() ? "\n" + modeLine.strip() : "";

        // 4. 구현 prompt + claude exec (worktree에서) — 비대화식 권한 우회는 worktree 격리 전제(기존과 같음)
        String prompt = renderImplementationPrompt(task, repo.commitSha(), wt.branchName(),
                ImplementationPrompts.planSection(task, mode.effective(), repo.commitSha(), designWritten),
                ImplementationPrompts.commitRule(mode.effective()))
                + DbMcpWorkerSupport.promptSection(task.dbMcp());
        List<String> extraArgs = mode.effective() == ImplementationMode.SDD
                ? List.of("--plugin-dir", superpowers.sddReadyPath().orElseThrow().toString())
                : List.of();
        ClaudeExecAdapter.ExecResult exec;
        try {
            exec = claude.exec(prompt, wt.dir(), implementationTimeoutOf(task, props.implementationTimeout()),
                    DbMcpWorkerSupport.withDbServers(task.mcpsExtra(), task.dbMcp(), props.apiBaseUrl()),
                    true, task.model(), task.effort(),
                    new ClaudeExecAdapter.ExecOptions(extraArgs, gitAuthorEnv()));
        } catch (Exception e) {
            String why = e.getMessage() != null && e.getMessage().startsWith("claude timeout") ? "시간 초과" : "실행 오류";
            PartialOutcome kept = preservePartialWork(task, wt, repo.commitSha(), why);
            safePostImplementationFailure(task.id(), attempt,
                    "claude exec 실패: " + e.getMessage() + kept.reasonSuffix() + fallbackNote,
                    wt.branchName(), kept.headSha(), null);
            return;
        }
        if (exec.exitCode() != 0) {
            PartialOutcome kept = preservePartialWork(task, wt, repo.commitSha(), "비정상 종료");
            String tail = tail(exec.stdout(), 4000);
            safePostImplementationFailure(task.id(), attempt,
                    "claude exit=" + exec.exitCode() + "\n" + tail + kept.reasonSuffix() + fallbackNote,
                    wt.branchName(), kept.headSha(), exec.stdout(), usageOf(exec));
            return;
        }

        // 리뷰 메모는 PR 본문으로 회수하고 .netis/(계획·설계·메모)는 커밋 전에 지운다 — exclude가 실패했어도 안 섞이게
        Optional<String> reviewNotes = ImplementationScratch.readReviewNotes(wtPath);
        ImplementationScratch.deleteRoot(wtPath);

        // 목업 참조 파일은 커밋 대상에서 제외
        if (designDir != null) deleteDesignDir(designDir.toPath());

        // 배포 env 목록(.netis-deploy-env.json)도 커밋 전에 회수·삭제 — 없거나 틀려도 구현은 계속 (스펙 2026-10-07 §4.3)
        List<EnvTemplateItem> envTemplate = DeployEnvManifest.harvest(wtPath);

        // 5. 변경 commit + push — 에이전트가 만든 커밋(SDD 태스크 커밋)도 받아들인다
        String headSha;
        try {
            String commitMsg = "feat: " + task.title()
                    + "\n\n" + "task #" + task.id()
                    + "\n\nCo-Authored-By: netisMaker <" + props.gitUserEmail() + ">";
            headSha = gitOps.commitAndPush(wt.dir(), task.repoRef(), wt.branchName(), commitMsg, repo.commitSha());
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "commit/push 실패: " + e.getMessage() + fallbackNote,
                    wt.branchName(), null, exec.stdout(), usageOf(exec));
            return;
        }

        // 6. Draft PR/MR
        GitOpsService.PrInfo pr;
        try {
            String body = renderPrBody(task, exec.durationMs(), headSha, modeLine, reviewNotes.orElse(null));
            pr = gitOps.createDraftPr(wt.dir(), task.repoRef(),
                    task.githubBranch(), wt.branchName(),
                    task.title(), body);
        } catch (Exception e) {
            safePostImplementationFailure(task.id(), attempt,
                    "PR/MR 생성 실패: " + e.getMessage() + fallbackNote,
                    wt.branchName(), headSha, exec.stdout(), usageOf(exec));
            return;
        }

        // 7. 성공 보고 — 시도 번호로 이전 시도의 늦은 보고와 구분 (스펙 2026-10-15 §5.6)
        reporter.reportTerminal(task.id(), new WorkerResultRequest(
                props.id(),
                TaskStatus.PR_CREATED,
                null, null, null, exec.durationMs(), null,
                pr.url(), pr.number(), wt.branchName(), headSha, exec.stdout(),
                null, null, null, null, null,
                null, null, null, null,
                usageOf(exec),
                envTemplate
        ).withImplementationAttempt(attempt));
        log.info("구현 완료 + PR 생성: task={} pr=#{} {} (방식 {}, {}회째, 배포 env 템플릿 {}개)",
                task.id(), pr.number(), pr.url(), mode.effective(), attempt, envTemplate.size());
        // 산출물은 원격 브랜치·PR에 있다 — worktree는 더 쓰지 않으므로 바로 정리(실패해도 결과 무관, 주기 정리가 재시도)
        worktrees.discard(GitRemotes.localKey(task.repoRef()), WorktreeKind.TASK, task.id());
    }

    /** claim의 시도 번호 — 없거나(옛 API) 1보다 작으면 1. */
    static int implementationAttemptOf(WorkerTaskResponse task) {
        WorkerTaskResponse.ImplementationSpec s = task.implementation();
        return s == null || s.attempt() == null || s.attempt() < 1 ? 1 : s.attempt();
    }

    /** claim의 상한(분) — 없으면(옛 API) 워커 설정 IMPLEMENTATION_TIMEOUT. */
    static Duration implementationTimeoutOf(WorkerTaskResponse task, Duration fallback) {
        WorkerTaskResponse.ImplementationSpec s = task.implementation();
        return s != null && s.timeoutMinutes() != null && s.timeoutMinutes() > 0
                ? Duration.ofMinutes(s.timeoutMinutes())
                : fallback;
    }

    /** 에이전트 커밋(SDD 태스크 커밋·실수 커밋)도 워커와 같은 작성자로 — 이 PC엔 전역 git 작성자가 없다 (스펙 §6.1). */
    private Map<String, String> gitAuthorEnv() {
        return Map.of(
                "GIT_AUTHOR_NAME", props.gitUserName(), "GIT_AUTHOR_EMAIL", props.gitUserEmail(),
                "GIT_COMMITTER_NAME", props.gitUserName(), "GIT_COMMITTER_EMAIL", props.gitUserEmail());
    }

    private static boolean writeScratch(Path worktree, String relPath, String content) {
        try {
            ImplementationScratch.write(worktree, relPath, content);
            return true;
        } catch (Exception e) {
            log.warn("구현 임시 파일 쓰기 실패 {}: {}", relPath, e.getMessage());
            return false;
        }
    }

    /** claude가 중간에 끝났을 때 — 임시 파일을 지우고 남은 변경을 WIP 커밋으로 push (스펙 2026-10-15 §6.6). */
    private PartialOutcome preservePartialWork(WorkerTaskResponse task, WorktreeService.CreatedWorktree wt,
                                               String baseSha, String why) {
        ImplementationScratch.deleteAll(wt.dir().toPath());
        GitOpsService.PartialWork w = gitOps.preservePartialWork(wt.dir(), task.repoRef(), wt.branchName(), baseSha,
                "wip: 구현 중단(" + why + ") — task #" + task.id());
        return PartialOutcome.of(w, wt.branchName());
    }

    /** 실패 사유에 붙일 문구와 보고할 head SHA(push했을 때만). */
    record PartialOutcome(String reasonSuffix, String headSha) {
        static PartialOutcome of(GitOpsService.PartialWork w, String branch) {
            if (w == null) return new PartialOutcome("", null);
            if (w.pushed()) {
                return new PartialOutcome(" — 지금까지 작업(커밋 " + w.commits() + "개)은 브랜치 `" + branch
                        + "`에 올려 둠", w.headSha());
            }
            if (w.error() != null) {
                return new PartialOutcome((w.commits() > 0
                        ? " — 부분 작업 push 실패(worktree에만 남음): "
                        : " — 부분 작업 보존 실패(worktree에만 남음): ") + w.error(), null);
            }
            return new PartialOutcome("", null);
        }
    }
```

6. 기존 `renderImplementationPrompt(WorkerTaskResponse task, String baseSha, String branchName)`를 다음으로 바꾼다:

```java
    private String renderImplementationPrompt(WorkerTaskResponse task, String baseSha, String branchName,
                                              String planSection, String commitRule) {
        String tpl = props.implementationPromptTemplate();
        if (tpl == null || tpl.isBlank()) {
            tpl = defaultImplementationPrompt();
        }
        return fillImplementationPrompt(tpl, task, baseSha, branchName, planSection, commitRule);
    }
```

7. `renderPrBody`를 `static`으로 바꾸고 시그니처·본문을 다음으로:

```java
    static String renderPrBody(WorkerTaskResponse task, long durationMs, String headSha,
                               String modeLine, String reviewNotes) {
        boolean gitlab = task.repoRef().isGitlab();
        boolean hasDesign = task.designMarkdown() != null && !task.designMarkdown().isBlank();
        // 마크다운 링크를 깨는 값(공백, ')', 개행 등) 방어 — 형태가 이상하면 링크만 생략
        boolean hasDesignUrl = task.designUrl() != null
                && task.designUrl().matches("https?://[^\\s)]+");
        String designLine = !hasDesign ? ""
                : "- **디자인**: 확정 디자인 기반 구현"
                + (hasDesignUrl ? " — [Claude Design 목업](" + task.designUrl() + ")" : "")
                + " (작업 상세의 디자인 카드 참고)\n";
        return "## netisMaker 자동 생성 " + (gitlab ? "MR" : "PR") + "\n\n"
                + "- **Task**: #" + task.id() + " " + task.title() + "\n"
                + "- **베이스**: `" + task.githubBranch() + "`\n"
                + "- **구현 SHA**: `" + headSha.substring(0, Math.min(7, headSha.length())) + "`\n"
                + (modeLine == null ? "" : modeLine)
                + designLine
                + "- **소요시간**: " + (durationMs / 1000) + "초\n\n"
                + "## 사전 분석\n"
                + (task.analysisMarkdown() == null ? "" : task.analysisMarkdown())
                + "\n\n"
                + (reviewNotes == null ? "" : "## 리뷰 메모\n" + reviewNotes + "\n\n")
                + "---\n"
                + "🤖 Generated with netisMaker by Claude Code.\n"
                + (gitlab ? "리뷰 후 Draft 표시를 해제하세요.\n" : "리뷰 후 Ready for review로 전환하세요.\n");
    }
```

8. 두 `safePostImplementationFailure` 오버로드를 다음으로 바꾼다:

```java
    private void safePostImplementationFailure(Long taskId, int attempt, String reason,
                                               String headBranch, String headSha, String log_) {
        safePostImplementationFailure(taskId, attempt, reason, headBranch, headSha, log_, null);
    }

    private void safePostImplementationFailure(Long taskId, int attempt, String reason,
                                               String headBranch, String headSha, String log_,
                                               WorkerResultRequest.UsageReport usage) {
        reporter.reportTerminal(taskId, new WorkerResultRequest(
                props.id(), TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, safeReason(reason),
                null, null, headBranch, headSha, log_,
                null, null, null, null, null,
                null, null, null, null, usage).withImplementationAttempt(attempt));
    }
```

- [ ] **Step 4: 기존 흐름 테스트 갱신**

`WorktreeImmediateDiscardTest.java`와 `WorkerMainLoopDeployEnvTest.java` 둘 다:
- `new WorkerMainLoop(props(), http, repos, claude, ..., mock(TaskCloser.class))` 호출 끝에 `, new SuperpowersPlugin("")` 인자를 더한다.
- `worktrees.create(any(), eq("acme/widgets"), eq("main"), eq(7L), eq("Add login"))` → `worktrees.create(any(), eq("acme/widgets"), eq("main"), eq(7L), eq("Add login"), eq(1))`
- `claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any())` → `claude.exec(anyString(), any(), any(), anyList(), anyBoolean(), any(), any(), any())`
- `gitOps.commitAndPush(any(), any(), anyString(), anyString())`(스텁과 `verify(..., never())` 모두) → `gitOps.commitAndPush(any(), any(), anyString(), anyString(), any())`

- [ ] **Step 5: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.*"`
Expected: PASS (워커 패키지 전체 — 이 PC 고유 실패 3건은 workerdaemon 밖이다)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopImplementationModeTest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeImmediateDiscardTest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopDeployEnvTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(worker): 방식별 구현 흐름 — 대신 진행·SDD 플러그인·부분 작업 보존·시도 번호 보고 (스펙 2026-10-15 §6)"
```

---

### Task 14: 기동 스크립트 — 워커에 `SUPERPOWERS_PLUGIN_PATH`

**Files:**
- Modify: `scripts/start-public.ps1`
- Modify: `scripts/start-all.sh`

**Interfaces:**
- Consumes: Task 11 `netis-maker.worker.superpowers-plugin-path: ${SUPERPOWERS_PLUGIN_PATH:}`
- Produces: 두 스크립트가 워커 env에 `SUPERPOWERS_PLUGIN_PATH`를 넘긴다(못 찾으면 경고만)

- [ ] **Step 1: Windows 스크립트**

`scripts/start-public.ps1`은 ASCII만 쓰므로(PowerShell 5.1이 BOM 없는 UTF-8을 깨뜨린다) 새 문구도 영어로 쓴다.

1. `# ---- 4) worker + interview service (optional) ----` 블록 안, `$claudeCli = $claudeCli -replace '\\', '/'` 줄 바로 다음에 추가:

```powershell

    # superpowers plugin - interview service (brainstorming/writing-plans) and worker SDD implementation share it
    $sp = $env:SUPERPOWERS_PLUGIN_PATH
    if (-not $sp) {
        $spRoot = Join-Path $env:USERPROFILE '.claude\plugins\cache\claude-plugins-official\superpowers'
        $spDir = Get-ChildItem $spRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'skills\brainstorming') } |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending | Select-Object -First 1
        if ($spDir) { $sp = $spDir.FullName }
    }
    if ($sp) { $sp = $sp -replace '\\', '/' }
```

2. `$workerEnv = @{ ... }` 해시테이블 닫는 `}` 바로 다음 줄(`Start-Service-Proc 'worker' ...` 앞)에 추가:

```powershell
        if ($sp) { $workerEnv['SUPERPOWERS_PLUGIN_PATH'] = $sp }
        else { Write-Host "[warn] superpowers plugin not found - SDD implementation requests fall back to REVIEW (set SUPERPOWERS_PLUGIN_PATH)" }
```

3. 인터뷰 서비스 블록(`if (Test-PidAlive 'interview') { ... } else {` 안)의 첫 일곱 줄 — 위에서 이미 계산했으므로 지운다:

```powershell
        $sp = $env:SUPERPOWERS_PLUGIN_PATH
        if (-not $sp) {
            $spRoot = Join-Path $env:USERPROFILE '.claude\plugins\cache\claude-plugins-official\superpowers'
            $spDir = Get-ChildItem $spRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path (Join-Path $_.FullName 'skills\brainstorming') } |
                Sort-Object Name -Descending | Select-Object -First 1
            if ($spDir) { $sp = $spDir.FullName }
        }
```

그 바로 뒤 `if (-not $sp) { Write-Host "[FAIL] interview - superpowers plugin not found ..."; $failed += 'interview' } else {`는 그대로 두고, 그 안 환경 해시테이블의 `SUPERPOWERS_PLUGIN_PATH = ($sp -replace '\\', '/')`를 `SUPERPOWERS_PLUGIN_PATH = $sp`로 바꾼다(이미 `/`로 바꿔 둠).

- [ ] **Step 2: macOS 스크립트**

`scripts/start-all.sh`의 `# ── 2) 워커 N개 ──` 줄 바로 위에 추가(`set -euo pipefail` 아래라 실패하는 파이프를 `|| true`로 막는다):

```bash
# superpowers 플러그인 — 워커의 SDD 구현용 (스펙 2026-10-15 §6.9). 인터뷰 서비스는 자기 .env 값을 쓴다.
if [ -z "${SUPERPOWERS_PLUGIN_PATH:-}" ]; then
  sp_root="$HOME/.claude/plugins/cache/claude-plugins-official/superpowers"
  SUPERPOWERS_PLUGIN_PATH="$(ls -d "$sp_root"/*/ 2>/dev/null | sed 's:/*$::' | sort -V | tail -n 1 || true)"
fi
if [ -n "${SUPERPOWERS_PLUGIN_PATH:-}" ]; then
  echo "● SDD 플러그인: $SUPERPOWERS_PLUGIN_PATH"
else
  echo "● SDD 플러그인 없음 — SDD 구현 요청은 리뷰로 대신 진행 (SUPERPOWERS_PLUGIN_PATH)"
fi

```

그리고 워커 `nohup env \` 목록의 `${CLAUDE_CLI:+CLAUDE_CLI="$CLAUDE_CLI"} \` 줄 바로 다음에 추가:

```bash
    ${SUPERPOWERS_PLUGIN_PATH:+SUPERPOWERS_PLUGIN_PATH="$SUPERPOWERS_PLUGIN_PATH"} \
```

- [ ] **Step 3: 문법 확인**

Run (PowerShell): `$errs = $null; [System.Management.Automation.Language.Parser]::ParseFile("$PWD\scripts\start-public.ps1", [ref]$null, [ref]$errs) | Out-Null; $errs.Count`
Expected: `0`

Run (Git Bash): `bash -n scripts/start-all.sh`
Expected: 출력 없음(exit 0)

- [ ] **Step 4: 커밋**

```bash
git add scripts/start-public.ps1 scripts/start-all.sh
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "chore(scripts): 워커에도 SUPERPOWERS_PLUGIN_PATH 전달 — SDD 구현용 (스펙 2026-10-15 §6.9)"
```

---

### Task 15: 화면 — 방식 피커와 "구현 진행"

**Files:**
- Create: `frontend/composables/implementationMode.ts`, `frontend/composables/useNowTick.ts`
- Create: `frontend/components/ImplementationModePicker.vue`
- Modify: `frontend/components/InterviewPanel.vue`
- Test: `frontend/composables/implementationMode.spec.ts`, `frontend/components/ImplementationModePicker.spec.ts` (생성), `frontend/components/InterviewPanel.spec.ts` (갱신·추가)

**Interfaces:**
- Produces (`composables/implementationMode.ts`): `type ImplementationMode = 'DIRECT' | 'REVIEW' | 'SDD'`, `DEFAULT_IMPLEMENTATION_MODE`, `IMPLEMENTATION_MODES`, `implementationModeLabel(mode)`, `implementationModeDescription(mode)`, `showsImplementationMode(status)`, `elapsedLabel(task, now) → { text, warn } | null`
- Produces (`composables/useNowTick.ts`): `useNowTick(intervalMs = 30_000): Ref<number | null>` — 마운트 전(SSR·하이드레이션)에는 null
- Produces (`ImplementationModePicker.vue`): props `modelValue: 'DIRECT' | 'REVIEW' | 'SDD'`, emit `update:modelValue`, 옵션 `data-test="impl-mode-{값}"`, 설명 `data-test="impl-mode-description"`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/composables/implementationMode.spec.ts`:

```ts
import { describe, it, expect } from 'vitest'
import {
  DEFAULT_IMPLEMENTATION_MODE,
  IMPLEMENTATION_MODES,
  elapsedLabel,
  implementationModeDescription,
  implementationModeLabel,
  showsImplementationMode,
} from './implementationMode'

describe('implementationMode (스펙 2026-10-15 §7)', () => {
  it('방식 목록·이름·설명·기본값', () => {
    expect(IMPLEMENTATION_MODES.map((m) => m.value)).toEqual(['DIRECT', 'REVIEW', 'SDD'])
    expect(DEFAULT_IMPLEMENTATION_MODE).toBe('REVIEW')
    expect(implementationModeLabel('REVIEW')).toBe('리뷰')
    expect(implementationModeLabel('SDD')).toBe('SDD')
    expect(implementationModeLabel(undefined)).toBe('직접')
    expect(implementationModeDescription('SDD')).toContain('최대 4시간')
  })

  it('확정 이후 상태에서만 방식을 보여 준다', () => {
    expect(showsImplementationMode('APPROVED')).toBe(true)
    expect(showsImplementationMode('DESIGN_PENDING')).toBe(true)
    expect(showsImplementationMode('CLOSED')).toBe(true)
    expect(showsImplementationMode('INTERVIEW_REVIEW')).toBe(false)
    expect(showsImplementationMode('AWAITING_APPROVAL')).toBe(false)
  })

  it('경과 문구 — 구현중·시작 시각·마운트 뒤에만, 상한의 80%부터 경고', () => {
    const now = Date.parse('2026-10-15T10:30:00Z')
    const base = { status: 'IMPLEMENTING', claimedAt: '2026-10-15T10:00:00Z', implementationTimeoutMinutes: 80 }
    expect(elapsedLabel(base, now)).toEqual({ text: '30분 경과 / 상한 80분', warn: false })
    expect(elapsedLabel({ ...base, implementationTimeoutMinutes: 36 }, now)?.warn).toBe(true)
    expect(elapsedLabel({ ...base, implementationTimeoutMinutes: 38 }, now)?.warn).toBe(false)
    expect(elapsedLabel(base, null)).toBeNull()
    expect(elapsedLabel({ ...base, status: 'PR_CREATED' }, now)).toBeNull()
    expect(elapsedLabel({ ...base, claimedAt: null }, now)).toBeNull()
  })

  it('상한이 없는 구현중 작업(새 API 전에 시작)은 경과만 보여 준다', () => {
    const now = Date.parse('2026-10-15T10:05:00Z')
    expect(elapsedLabel({ status: 'IMPLEMENTING', claimedAt: '2026-10-15T10:00:00Z', implementationTimeoutMinutes: null }, now))
      .toEqual({ text: '5분 경과', warn: false })
  })
})
```

`frontend/components/ImplementationModePicker.spec.ts`:

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ImplementationModePicker from './ImplementationModePicker.vue'

describe('ImplementationModePicker (스펙 2026-10-15 §7)', () => {
  it('세 방식을 보여 주고 고른 방식의 설명을 붙인다', () => {
    const w = mount(ImplementationModePicker, { props: { modelValue: 'REVIEW' } })
    for (const v of ['DIRECT', 'REVIEW', 'SDD']) {
      expect(w.find(`[data-test="impl-mode-${v}"]`).exists()).toBe(true)
    }
    expect(w.find('[data-test="impl-mode-description"]').text()).toContain('Opus 리뷰 1회')
  })

  it('누르면 update:modelValue를 낸다', async () => {
    const w = mount(ImplementationModePicker, { props: { modelValue: 'REVIEW' } })
    await w.find('[data-test="impl-mode-SDD"]').trigger('click')
    expect(w.emitted('update:modelValue')?.[0]).toEqual(['SDD'])
  })
})
```

`frontend/components/InterviewPanel.spec.ts`:
- 확정 바디 기대값 3곳을 바꾼다: `body: { designRequested: false },` → `body: { designRequested: false, implementationMode: 'REVIEW' },`(2곳), `body: { designRequested: true },` → `body: { designRequested: true, implementationMode: 'REVIEW' },`(1곳).
- `describe('InterviewPanel — confirm flow', ...)` 안 끝에 추가:

```ts
  it('sends the chosen implementation mode (default REVIEW, switchable to SDD)', async () => {
    const w = await mountPanel(9)
    const es = FakeEventSource.last()
    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()
    expect(w.find('[data-test="impl-mode-description"]').text()).toContain('리뷰 1회')

    await w.find('[data-test="impl-mode-SDD"]').trigger('click')
    await flushPromises()
    useApiMock.mockResolvedValueOnce({ taskId: 125 })
    await w.find('[data-test="confirm"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/confirm', {
      method: 'POST',
      body: { designRequested: false, implementationMode: 'SDD' },
    })
    w.unmount()
  })

  it('hides the mode picker for read-only viewers', async () => {
    const w = await mountPanel(9, { statusName: null, turns: [], plan: null }, { readonly: true })
    expect(w.find('[data-test="impl-mode-picker"]').exists()).toBe(false)
    w.unmount()
  })
```

- [ ] **Step 2: 실패 확인**

Run (`frontend`에서): `npx vitest run composables/implementationMode.spec.ts components/ImplementationModePicker.spec.ts components/InterviewPanel.spec.ts`
Expected: FAIL — 모듈·컴포넌트 없음, 확정 바디에 `implementationMode` 없음.

- [ ] **Step 3: composable 작성**

`frontend/composables/implementationMode.ts`:

```ts
// 구현 방식 (스펙 2026-10-15 §7) — 서버 enum 이름을 그대로 주고받는다. 화면 기본값은 리뷰.

export type ImplementationMode = 'DIRECT' | 'REVIEW' | 'SDD'

export const DEFAULT_IMPLEMENTATION_MODE: ImplementationMode = 'REVIEW'

export const IMPLEMENTATION_MODES: ReadonlyArray<{ value: ImplementationMode; label: string; description: string }> = [
  { value: 'DIRECT', label: '직접', description: '리뷰 없이 바로 구현 — 가장 빠르고 저렴' },
  { value: 'REVIEW', label: '리뷰', description: '구현 후 Opus 리뷰 1회, 중대 문제만 수정 — 구현 비용 약 1.6~1.8배(실측 2건)' },
  { value: 'SDD', label: 'SDD', description: '작업마다 서브에이전트가 구현·리뷰 — 가장 느리고 비쌈, 최대 4시간 워커 점유' },
]

export function implementationModeLabel(mode: string | null | undefined): string {
  return IMPLEMENTATION_MODES.find((m) => m.value === mode)?.label ?? '직접'
}

export function implementationModeDescription(mode: string | null | undefined): string {
  return IMPLEMENTATION_MODES.find((m) => m.value === mode)?.description ?? ''
}

// 플랜 확정(구현 진행) 이후 상태 — 이때부터 방식이 의미 있다.
const POST_CONFIRM_STATUSES = [
  'APPROVED', 'IMPLEMENTING', 'PR_CREATED', 'IMPLEMENTATION_FAILED',
  'DESIGN_PENDING', 'DESIGNING', 'DESIGN_REVIEW', 'DESIGN_FAILED',
  'DEPLOY_PENDING', 'DEPLOYING', 'DEPLOYED', 'DEPLOY_FAILED', 'DEPLOY_LOST', 'UNDEPLOY_PENDING', 'UNDEPLOYING',
  'CLOSE_REVIEW', 'CLOSE_PENDING', 'CLOSING', 'CLOSED', 'CLOSE_FAILED',
]

export function showsImplementationMode(status: string): boolean {
  return POST_CONFIRM_STATUSES.includes(status)
}

/**
 * "23분 경과 / 상한 80분" — 구현중이고 시작 시각이 있을 때만. 상한의 80% 이상이면 warn.
 * now가 null(SSR·마운트 전)이면 null — 시간에 따라 바뀌는 문구가 서버 HTML과 어긋나지 않게.
 */
export function elapsedLabel(
  task: { status: string; claimedAt?: string | null; implementationTimeoutMinutes?: number | null },
  now: number | null,
): { text: string; warn: boolean } | null {
  if (now == null || task.status !== 'IMPLEMENTING' || !task.claimedAt) return null
  const started = Date.parse(task.claimedAt)
  if (Number.isNaN(started)) return null
  const minutes = Math.max(0, Math.floor((now - started) / 60000))
  const limit = task.implementationTimeoutMinutes
  if (!limit) return { text: `${minutes}분 경과`, warn: false }
  return { text: `${minutes}분 경과 / 상한 ${limit}분`, warn: minutes >= limit * 0.8 }
}
```

`frontend/composables/useNowTick.ts`:

```ts
import type { Ref } from 'vue'

/**
 * 마운트된 뒤에만 값이 생기는 현재 시각(ms) — intervalMs마다 갱신 (스펙 2026-10-15 §7 경과 시간).
 * SSR·하이드레이션 동안은 null이라 시간에 따라 바뀌는 문구가 서버 HTML과 어긋나지 않는다.
 */
export function useNowTick(intervalMs = 30_000): Ref<number | null> {
  const now = ref<number | null>(null)
  let timer: ReturnType<typeof setInterval> | null = null
  onMounted(() => {
    now.value = Date.now()
    timer = setInterval(() => {
      now.value = Date.now()
    }, intervalMs)
  })
  onUnmounted(() => {
    if (timer) clearInterval(timer)
  })
  return now
}
```

- [ ] **Step 4: 피커 작성**

`frontend/components/ImplementationModePicker.vue`:

```vue
<script setup lang="ts">
// 구현 방식 고르기 (스펙 2026-10-15 §7) — 인터뷰 패널 "구현 진행"과 구현 재시도 다이얼로그가 같이 쓴다.
import { IMPLEMENTATION_MODES, implementationModeDescription } from '~/composables/implementationMode'

const props = defineProps<{ modelValue: 'DIRECT' | 'REVIEW' | 'SDD' }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: 'DIRECT' | 'REVIEW' | 'SDD'): void }>()

const options = IMPLEMENTATION_MODES.map((m) => ({
  label: m.label,
  value: m.value,
  attrs: { 'data-test': `impl-mode-${m.value}` },
}))
</script>

<template>
  <div class="impl-mode-picker" data-test="impl-mode-picker">
    <div class="row items-center no-wrap" style="gap: 8px">
      <span class="text-caption text-grey-8">구현 방식</span>
      <q-btn-toggle
        :model-value="props.modelValue"
        :options="options"
        dense
        no-caps
        unelevated
        toggle-color="primary"
        color="grey-3"
        text-color="grey-9"
        @update:model-value="(v) => emit('update:modelValue', v)"
      />
    </div>
    <div class="text-caption text-grey-7 q-mt-xs" data-test="impl-mode-description">
      {{ implementationModeDescription(props.modelValue) }}
    </div>
  </div>
</template>
```

- [ ] **Step 5: 인터뷰 패널 배선**

`frontend/components/InterviewPanel.vue`:
1. import에 추가:

```ts
import ImplementationModePicker from '~/components/ImplementationModePicker.vue'
import { DEFAULT_IMPLEMENTATION_MODE, type ImplementationMode } from '~/composables/implementationMode'
```

2. `const designRequested = ref(false)` 바로 다음에:

```ts
// 구현 방식 — 화면 기본은 리뷰, API는 값이 없으면 직접 (스펙 2026-10-15 §3)
const implementationMode = ref<ImplementationMode>(DEFAULT_IMPLEMENTATION_MODE)
```

3. `confirm()`의 바디 `body: { designRequested: designRequested.value },`를 `body: { designRequested: designRequested.value, implementationMode: implementationMode.value },`로.

4. 템플릿의 확정 줄 — 기존:

```html
          <div v-if="!readonly" class="row items-center justify-end q-mt-md q-gutter-sm">
            <q-toggle
              v-model="designRequested"
              dense
              label="디자인 단계 포함"
            >
              <q-tooltip>분석 승인 후 화면 목업을 생성해 승인받습니다</q-tooltip>
            </q-toggle>
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
          </div>
```

새로:

```html
          <div v-if="!readonly" class="q-mt-md">
            <ImplementationModePicker v-model="implementationMode" class="q-mb-sm" />
            <div class="row items-center justify-end q-gutter-sm">
              <q-toggle
                v-model="designRequested"
                dense
                label="디자인 단계 포함"
              >
                <q-tooltip>분석 승인 후 화면 목업을 생성해 승인받습니다</q-tooltip>
              </q-toggle>
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
            </div>
          </div>
```

- [ ] **Step 6: 통과 확인**

Run (`frontend`에서): `npx vitest run composables/implementationMode.spec.ts components/ImplementationModePicker.spec.ts components/InterviewPanel.spec.ts`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add frontend/composables/implementationMode.ts frontend/composables/useNowTick.ts frontend/components/ImplementationModePicker.vue frontend/components/InterviewPanel.vue frontend/composables/implementationMode.spec.ts frontend/components/ImplementationModePicker.spec.ts frontend/components/InterviewPanel.spec.ts
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(front): 구현 진행에 구현 방식 피커(기본 리뷰) (스펙 2026-10-15 §7)"
```

---

### Task 16: 화면 — 방식 표시·경과 시간

**Files:**
- Modify: `frontend/pages/tasks/[id].vue` (인터페이스·스크립트·헤더·`<style scoped>`)
- Modify: `frontend/components/tasks/TaskDetailMobile.vue`, `frontend/components/tasks/TaskCardCompact.vue`, `frontend/pages/tasks/index.vue`
- Modify: `frontend/test/fixtures/task.ts`, `frontend/scripts/hydration-smoke.mjs`
- Test: `frontend/test/task-detail-implementation-mode.spec.ts`, `frontend/test/tasks-index-elapsed.spec.ts` (생성), `frontend/components/tasks/TaskCardCompact.spec.ts` (추가)

**Interfaces:**
- Consumes: Task 15 `elapsedLabel`·`implementationModeLabel`·`showsImplementationMode`·`useNowTick`; Task 3 응답 필드 `implementationMode`·`implementationAttempt`·`claimedAt`·`implementationTimeoutMinutes`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/test/task-detail-implementation-mode.spec.ts`:

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const implementing = (minutesAgo: number, timeout: number | null) => ({
  ...baseTask,
  status: 'IMPLEMENTING',
  statusLabel: '구현중',
  implementation: null,
  implementationMode: 'SDD',
  implementationAttempt: 2,
  claimedAt: new Date(Date.now() - minutesAgo * 60000 - 5000).toISOString(),
  implementationTimeoutMinutes: timeout,
})

describe('pages/tasks/[id] — 구현 방식·경과 시간 (스펙 2026-10-15 §7)', () => {
  beforeEach(() => { authStub.isAdmin = true })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('데스크톱: 구현중이면 경과/상한과 방식 칩(회차 포함)을 보여 준다', async () => {
    useApiMock.mockResolvedValue(implementing(10, 240))
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="impl-elapsed"]').text()).toBe('10분 경과 / 상한 240분')
    expect(w.find('[data-test="impl-elapsed"]').classes()).not.toContain('impl-elapsed--warn')
    expect(w.find('[data-test="impl-mode-chip"]').text()).toContain('구현 방식: SDD · 2회째')
    w.unmount()
  })

  it('상한의 80%를 넘으면 경고 표시', async () => {
    useApiMock.mockResolvedValue(implementing(200, 240))
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="impl-elapsed"]').classes()).toContain('impl-elapsed--warn')
    w.unmount()
  })

  it('확정 전에는 방식 칩이 없고, 구현중이 아니면 경과도 없다', async () => {
    useApiMock.mockResolvedValue({
      ...baseTask, status: 'INTERVIEW_REVIEW', statusLabel: '플랜승인대기', implementation: null,
      implementationMode: 'DIRECT', implementationAttempt: 1, claimedAt: null, implementationTimeoutMinutes: null,
    })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="impl-mode-chip"]').exists()).toBe(false)
    expect(w.find('[data-test="impl-elapsed"]').exists()).toBe(false)
    w.unmount()
  })

  it('모바일: 정보 섹션에 구현 방식 줄', async () => {
    useApiMock.mockResolvedValue({ ...baseTask, implementationMode: 'REVIEW', implementationAttempt: 1 })
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="section-info"] .q-item').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="info-impl-mode"]').text()).toContain('리뷰')
    w.unmount()
  })
})
```

`frontend/test/tasks-index-elapsed.spec.ts`:

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }) }),
})

const implementing = {
  id: 7, githubRepo: 'org/repo', repoAlias: 'Repo', githubBranch: 'main', title: '구현중 작업', description: 'd',
  status: 'IMPLEMENTING', statusLabel: '구현중', requesterId: 'user1', retryCount: 0, maxRetry: 3, failureReason: null,
  designRequested: false, createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z', implementation: null,
  totalCostUsd: null, claimedAt: new Date(Date.now() - 12 * 60000 - 5000).toISOString(), implementationTimeoutMinutes: 80,
}

describe('pages/tasks/index — 구현중 경과 시간 (스펙 2026-10-15 §7)', () => {
  it('보드 카드에 경과/상한을 보여 준다', async () => {
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/tasks') return Promise.resolve({ content: [implementing], totalElements: 1, totalPages: 1 })
      if (url === '/api/repo-catalog') return Promise.resolve([])
      return Promise.resolve(null)
    })
    const w = mount(PageWrapper)
    await flushPromises()
    expect(w.find('[data-test="board-elapsed"]').text()).toBe('12분 경과 / 상한 80분')
    w.unmount()
  })
})
```

`frontend/components/tasks/TaskCardCompact.spec.ts` — import에 `nextTick`(`import { nextTick } from 'vue'`)을 더하고 describe 끝에 추가:

```ts
  it('구현중이면 경과/상한 줄을 보여 준다(마운트 뒤)', async () => {
    const w = mount(TaskCardCompact, {
      props: {
        task: {
          ...base, status: 'IMPLEMENTING', statusLabel: '구현중', implementation: null,
          claimedAt: new Date(Date.now() - 3 * 60000 - 5000).toISOString(), implementationTimeoutMinutes: 80,
        },
        isAdmin: true,
      },
    })
    await nextTick()
    expect(w.find('[data-test="card-elapsed"]').text()).toContain('3분 경과 / 상한 80분')
    w.unmount()
  })

  it('구현중이 아니면 경과 줄이 없다', () => {
    const w = mount(TaskCardCompact, { props: { task: base, isAdmin: false } })
    expect(w.find('[data-test="card-elapsed"]').exists()).toBe(false)
    w.unmount()
  })
```

- [ ] **Step 2: 실패 확인**

Run (`frontend`에서): `npx vitest run test/task-detail-implementation-mode.spec.ts test/tasks-index-elapsed.spec.ts components/tasks/TaskCardCompact.spec.ts`
Expected: FAIL — `impl-elapsed`·`impl-mode-chip`·`info-impl-mode`·`board-elapsed`·`card-elapsed` 없음.

- [ ] **Step 3: 작업 상세(데스크톱 헤더) 구현**

`frontend/pages/tasks/[id].vue`:
1. import에 추가:

```ts
import { elapsedLabel, implementationModeLabel, showsImplementationMode } from '~/composables/implementationMode'
import { useNowTick } from '~/composables/useNowTick'
```

2. `interface TaskResponse`의 `totalTokens: number | null` 다음 줄에 추가:

```ts
  implementationMode?: string
  implementationAttempt?: number
  claimedAt?: string | null
  implementationTimeoutMinutes?: number | null
```

3. `const action = computed(...)` 줄 바로 다음에 추가:

```ts
// 구현 방식·경과 시간 (스펙 2026-10-15 §7) — 경과는 마운트 뒤 30초마다 다시 계산
const nowTick = useNowTick()
const elapsed = computed(() => (task.value ? elapsedLabel(task.value, nowTick.value) : null))
const modeChip = computed(() => {
  const t = task.value
  if (!t || !showsImplementationMode(t.status)) return null
  const attempt = t.implementationAttempt ?? 1
  return `구현 방식: ${implementationModeLabel(t.implementationMode)}${attempt >= 2 ? ` · ${attempt}회째` : ''}`
})
```

4. 템플릿 헤더의 `<span :class="statusClass(task.status)">{{ task.statusLabel }}</span>` 바로 다음에 추가:

```html
        <span
          v-if="elapsed"
          class="q-ml-sm text-caption impl-elapsed"
          :class="{ 'impl-elapsed--warn': elapsed.warn }"
          data-test="impl-elapsed"
        >{{ elapsed.text }}</span>
```

같은 헤더의 데스크톱 칩 `<q-chip dense size="sm" outline icon="tune" :label="task.effort" />` 바로 다음에 추가:

```html
          <q-chip v-if="modeChip" dense size="sm" outline icon="account_tree" :label="modeChip" data-test="impl-mode-chip" />
```

5. 파일 끝 `<style scoped>` 블록 안에 추가:

```css
.impl-elapsed {
  color: #757575;
}
.impl-elapsed--warn {
  color: #ef6c00;
  font-weight: 600;
}
```

- [ ] **Step 4: 모바일 상세 구현**

`frontend/components/tasks/TaskDetailMobile.vue`:
1. import에 `import { implementationModeLabel, showsImplementationMode } from '~/composables/implementationMode'`.
2. `DetailTask` 인터페이스의 `dbConnectionIds?: number[]` 다음 줄에 `implementationMode?: string`과 `implementationAttempt?: number`를 더한다.
3. `fmtTokens` 함수 바로 위에 추가:

```ts
// 구현 방식 (스펙 2026-10-15 §7) — 플랜 확정 이후에만
const modeText = computed(() => {
  if (!showsImplementationMode(props.task.status)) return null
  const attempt = props.task.implementationAttempt ?? 1
  return implementationModeLabel(props.task.implementationMode) + (attempt >= 2 ? ` · ${attempt}회째` : '')
})
```

4. 정보 섹션의 `<div><span class="k">모델</span>{{ task.model }} · {{ task.effort }}</div>` 바로 다음에 추가:

```html
              <div v-if="modeText" data-test="info-impl-mode"><span class="k">구현</span>{{ modeText }}</div>
```

- [ ] **Step 5: 모바일 카드·데스크톱 보드 구현**

`frontend/components/tasks/TaskCardCompact.vue`:
1. import에 `import { elapsedLabel } from '~/composables/implementationMode'`와 `import { useNowTick } from '~/composables/useNowTick'`.
2. `CardTask` 인터페이스의 `deployment?: ...` 다음 줄에 `claimedAt?: string | null`과 `implementationTimeoutMinutes?: number | null`.
3. `const deployHost = computed(...)` 바로 위에:

```ts
// 구현중 경과 시간 (스펙 2026-10-15 §7) — 마운트 뒤 30초마다
const nowTick = useNowTick()
const elapsed = computed(() => elapsedLabel(props.task, nowTick.value))
```

4. 템플릿의 `step-row` `</div>` 바로 다음(`<div class="card-meta">` 앞)에:

```html
    <div v-if="elapsed" class="card-elapsed" :class="{ 'card-elapsed--warn': elapsed.warn }" data-test="card-elapsed">
      <q-icon name="schedule" size="14px" />
      <span>{{ elapsed.text }}</span>
    </div>
```

5. `<style scoped>` 끝에:

```css
.card-elapsed {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #757575;
}
.card-elapsed--warn {
  color: #ef6c00;
  font-weight: 600;
}
```

`frontend/pages/tasks/index.vue`:
1. import에 `import { elapsedLabel } from '~/composables/implementationMode'`와 `import { useNowTick } from '~/composables/useNowTick'`.
2. 파일 위쪽 `interface TaskResponse`의 `totalCostUsd: number | null` 다음 줄에 `claimedAt?: string | null`과 `implementationTimeoutMinutes?: number | null`.
3. `function costBadge(...)` 바로 위에:

```ts
// 구현중 경과 시간 (스펙 2026-10-15 §7) — 마운트 뒤 30초마다
const nowTick = useNowTick()
function elapsedOf(t: TaskResponse) {
  return elapsedLabel(t, nowTick.value)
}
```

4. 보드 카드의 `<span v-if="costBadge(c.task)" class="card-cost">{{ costBadge(c.task) }}</span>` 바로 다음에:

```html
                    <span
                      v-if="elapsedOf(c.task)"
                      class="card-cost"
                      :class="{ 'board-elapsed--warn': elapsedOf(c.task)!.warn }"
                      data-test="board-elapsed"
                    >{{ elapsedOf(c.task)!.text }}</span>
```

5. `<style scoped>`(없으면 파일 끝에 `<style scoped>`를 만든다)에:

```css
.board-elapsed--warn {
  color: #ef6c00;
  font-weight: 600;
}
```

- [ ] **Step 6: 픽스처·하이드레이션 스모크 목 갱신**

`frontend/test/fixtures/task.ts`의 `baseTask` 끝(`totalTokens: 123000,` 다음)에:

```ts
  implementationMode: 'REVIEW',
  implementationAttempt: 1,
  claimedAt: null,
  implementationTimeoutMinutes: null,
```

`frontend/scripts/hydration-smoke.mjs`의 `task = (id, status, statusLabel, extra = {}) => ({ ... })` 기본값에서 `totalCostUsd: null, totalTokens: null, ...extra,`를 `totalCostUsd: null, totalTokens: null, implementationMode: 'DIRECT', implementationAttempt: 1, claimedAt: null, implementationTimeoutMinutes: null, ...extra,`로 바꾸고, `TASKS` 배열 끝에 구현중 작업 하나를 더한다(경과 문구는 마운트 뒤에만 그려지므로 하이드레이션 불일치가 없어야 한다):

```js
  task(9, 'IMPLEMENTING', '구현중', { implementationMode: 'REVIEW', claimedAt: NOW, implementationTimeoutMinutes: 80 }),
```

(이미 id 9가 있으면 쓰지 않는 다음 번호로.)

- [ ] **Step 7: 통과 확인**

Run (`frontend`에서): `npx vitest run test/task-detail-implementation-mode.spec.ts test/tasks-index-elapsed.spec.ts components/tasks/TaskCardCompact.spec.ts test/task-detail-progress.spec.ts test/task-detail-mobile.spec.ts test/tasks-index-usage.spec.ts test/tasks-index-mobile.spec.ts`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add "frontend/pages/tasks/[id].vue" frontend/components/tasks/TaskDetailMobile.vue frontend/components/tasks/TaskCardCompact.vue frontend/pages/tasks/index.vue frontend/test/fixtures/task.ts frontend/scripts/hydration-smoke.mjs frontend/test/task-detail-implementation-mode.spec.ts frontend/test/tasks-index-elapsed.spec.ts frontend/components/tasks/TaskCardCompact.spec.ts
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(front): 구현 방식 표시·구현중 경과/상한 (스펙 2026-10-15 §7)"
```

---

### Task 17: 화면 — 구현 재시도

**Files:**
- Create: `frontend/components/tasks/ImplementationRetryDialog.vue`
- Modify: `frontend/composables/taskStages.ts` (`NextActionKind`, `nextAction`의 `IMPLEMENTATION_FAILED`)
- Modify: `frontend/pages/tasks/[id].vue` (`onAction`, 다이얼로그)
- Test: `frontend/composables/taskStages.spec.ts` (갱신), `frontend/test/task-detail-implementation-retry.spec.ts` (생성)

**Interfaces:**
- Consumes: Task 5 `POST /api/tasks/{id}/implementation/retry` 바디 `{ implementationMode }`, Task 15 `ImplementationModePicker`
- Produces: `NextActionKind`에 `'retry-implementation'`; `ImplementationRetryDialog` props `modelValue: boolean`, `taskId: string`, `currentMode: string`, emits `update:modelValue`·`submitted`; `data-test="impl-retry-dialog"`·`impl-retry-submit`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/composables/taskStages.spec.ts` — 기존 줄

```ts
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, true)?.primary).toBeUndefined()
```

을 다음으로 바꾸고, 그 `it(...)` 제목의 `구현실패: 문구만`을 `구현실패: 관리자는 구현 재시도`로 고친다:

```ts
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, true)?.primary?.kind).toBe('retry-implementation')
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, false)?.primary).toBeUndefined()
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, false)?.text).toContain('관리자 확인이 필요합니다')
```

`frontend/test/task-detail-implementation-retry.spec.ts`:

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const failed = {
  ...baseTask, status: 'IMPLEMENTATION_FAILED', statusLabel: '구현실패', implementation: null,
  failureReason: 'claude exec 실패: claude timeout after PT4H — 지금까지 작업(커밋 2개)은 브랜치 `netismaker/task-42-x`에 올려 둠',
  implementationMode: 'SDD', implementationAttempt: 1,
}

describe('pages/tasks/[id] — 구현 재시도 (스펙 2026-10-15 §5.5·§7)', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('관리자: 다음 할 일 "구현 재시도" → 방식 다시 고르기 → 재시도 API', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(failed)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()

    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-test="impl-retry-dialog"]')).not.toBeNull()
    ;(document.body.querySelector('[data-test="impl-mode-REVIEW"]') as HTMLElement).click()
    await flushPromises()
    useApiMock.mockClear()
    useApiMock.mockResolvedValue(failed)
    ;(document.body.querySelector('[data-test="impl-retry-submit"]') as HTMLElement).click()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', {
      method: 'POST',
      body: { implementationMode: 'REVIEW' },
    })
    w.unmount()
  })

  it('요청자: 문구만 있고 재시도 버튼은 없다', async () => {
    authStub.isAdmin = false
    useApiMock.mockResolvedValue(failed)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="next-action-text"]').text()).toContain('관리자 확인이 필요합니다')
    expect(w.find('[data-test="next-action-primary"]').exists()).toBe(false)
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run (`frontend`에서): `npx vitest run composables/taskStages.spec.ts test/task-detail-implementation-retry.spec.ts`
Expected: FAIL — 관리자 주 행동 없음, 다이얼로그 없음.

- [ ] **Step 3: 다음 할 일 구현**

`frontend/composables/taskStages.ts`:
1. `NextActionKind`의 마지막 줄 `| 'request-close' | 'withdraw-close' | 'approve-close' | 'retry-close'`를 `| 'request-close' | 'withdraw-close' | 'approve-close' | 'retry-close' | 'retry-implementation'`로.
2. `nextAction`의

```ts
    case 'IMPLEMENTATION_FAILED':
      return { text: '구현 실패 — 사유를 확인하세요' }
```

를 다음으로:

```ts
    case 'IMPLEMENTATION_FAILED':
      // 관리자는 방식을 다시 골라 처음부터 재시도 (스펙 2026-10-15 §5.5)
      return isAdmin
        ? { text: '구현 실패 — 사유와 보존된 브랜치를 확인하고 재시도하세요', primary: { label: '구현 재시도', icon: 'refresh', kind: 'retry-implementation' } }
        : { text: '구현 실패 — 관리자 확인이 필요합니다' }
```

- [ ] **Step 4: 다이얼로그 작성**

`frontend/components/tasks/ImplementationRetryDialog.vue`:

```vue
<script setup lang="ts">
// 구현 재시도 (스펙 2026-10-15 §5.5·§7) — 방식을 다시 골라 구현실패 작업을 구현대기로 돌린다(관리자).
import { useQuasar } from 'quasar'
import ImplementationModePicker from '~/components/ImplementationModePicker.vue'
import type { ImplementationMode } from '~/composables/implementationMode'

const props = defineProps<{ modelValue: boolean; taskId: string; currentMode: string }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted'): void
}>()

const $q = useQuasar()
const mode = ref<ImplementationMode>('DIRECT')
const submitting = ref(false)

// 열 때마다 지금 방식으로 시작
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      mode.value = (['DIRECT', 'REVIEW', 'SDD'].includes(props.currentMode) ? props.currentMode : 'DIRECT') as ImplementationMode
    }
  },
  { immediate: true },
)

async function submit() {
  if (submitting.value) return
  submitting.value = true
  try {
    await useApi(`/api/tasks/${props.taskId}/implementation/retry`, {
      method: 'POST',
      body: { implementationMode: mode.value },
    })
    $q.notify({ type: 'positive', message: '구현 재시도 — 구현 큐에 다시 넣었습니다' })
    emit('update:modelValue', false)
    emit('submitted')
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '구현 재시도 실패' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <q-dialog
    :model-value="modelValue"
    :maximized="$q.screen.lt.md"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <q-card style="width: min(480px, 100vw)" data-test="impl-retry-dialog">
      <q-card-section class="text-h6">구현 재시도</q-card-section>
      <q-card-section class="q-pt-none text-caption text-grey-8">
        베이스에서 처음부터 새 브랜치(시도 번호 접미사)로 다시 구현합니다. 이전 실패 사유와 브랜치는 진행 이력에 남아 있습니다.
      </q-card-section>
      <q-card-section>
        <ImplementationModePicker v-model="mode" />
      </q-card-section>
      <q-card-actions align="right">
        <q-btn flat label="취소" @click="emit('update:modelValue', false)" />
        <q-btn
          unelevated
          color="primary"
          label="구현 재시도"
          :loading="submitting"
          data-test="impl-retry-submit"
          @click="submit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>
```

- [ ] **Step 5: 상세 페이지 배선**

`frontend/pages/tasks/[id].vue`:
1. import에 `import ImplementationRetryDialog from '~/components/tasks/ImplementationRetryDialog.vue'`.
2. `const showApprove = ref(false)` 바로 다음에 `const showImplRetry = ref(false)`.
3. `onAction`의 `case 'retry-close': return retryClose()` 다음에 `case 'retry-implementation': return void (showImplRetry.value = true)`.
4. 템플릿의 `<ApproveDialog v-model="showApprove" :task="task" @approved="onApproved" />` 바로 다음에:

```html
      <ImplementationRetryDialog
        v-model="showImplRetry"
        :task-id="String(task.id)"
        :current-mode="task.implementationMode ?? 'DIRECT'"
        @submitted="refresh"
      />
```

- [ ] **Step 6: 통과 확인**

Run (`frontend`에서): `npx vitest run composables/taskStages.spec.ts test/task-detail-implementation-retry.spec.ts components/tasks/TaskCardCompact.spec.ts test/task-detail-progress.spec.ts`
Expected: PASS

- [ ] **Step 7: 프론트 전체 회귀**

Run (`frontend`에서): `npx vitest run`
Expected: 전부 PASS

- [ ] **Step 8: 커밋**

```bash
git add frontend/components/tasks/ImplementationRetryDialog.vue frontend/composables/taskStages.ts "frontend/pages/tasks/[id].vue" frontend/composables/taskStages.spec.ts frontend/test/task-detail-implementation-retry.spec.ts
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "feat(front): 구현실패 — 관리자 구현 재시도(방식 다시 고르기) (스펙 2026-10-15 §5.5·§7)"
```

---

### Task 18: CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 상태머신** — "## 작업 상태머신"의 첫 코드 블록에서 `[승인대기] ← (인터뷰 실패/만료/취소)` 줄 다음에 한 줄 추가:

```
[구현실패] ──(관리자 구현 재시도, 방식 다시 고름 — 시도 번호 +1)──→ [구현대기]
```

- [ ] **Step 2: 구현 단계 설명** — `- **구현**: 같은 워커가 APPROVED를 claim → …` 불릿 끝에 이어 붙인다:

```markdown
 **구현 방식(2026-10-15, 스펙 `docs/superpowers/specs/2026-10-15-implementation-mode-selection-design.md`)**: 플랜 확정("구현 진행")·구현 재시도 때 관리자가 `task.implementation_mode`(DIRECT 직접 / REVIEW 리뷰(화면 기본) / SDD)를 고른다(API는 값이 없으면 DIRECT). 리뷰 = 같은 세션이 끝에 Opus 리뷰 서브에이전트 1회(계획 범위 안 Critical·Important만 수정, 나머지는 PR 본문 `## 리뷰 메모`), SDD = `--plugin-dir <SUPERPOWERS_PLUGIN_PATH>` + superpowers SDD로 계획 작업마다 서브에이전트(계획 파일 `.netis/plan/task-{id}-plan.md`, `### 작업 N`→`### Task N`). 준비가 안 되면 SDD→리뷰→직접으로 낮춰 진행하고 PR 본문에 이유(`ImplementationModeResolver`). 상한/판정은 API `app.task.implementation-{,review-,sdd-}timeout-minutes`(45/80/240)·`…stale-threshold-minutes`(60/95/260, 방식마다 상한<판정이 아니면 기동 실패) — claim 때 `implementation_timeout_minutes`에 기록해 보내고 화면이 "N분 경과 / 상한 M분"으로 쓴다. 워커는 에이전트 커밋을 받아들이고(`commitAndPush` baseSha), 임시 파일(`.netis/`·`.design/`·`.netis-deploy-env.json`)은 `info/exclude`, 에이전트 커밋 작성자는 `GIT_AUTHOR_*`/`GIT_COMMITTER_*` env. claude가 중간에 끝나면 남은 변경을 `wip:` 커밋으로 작업 브랜치에 push(PR 없음, 실패 사유에 브랜치). 결과 토큰은 `modelUsage` 합계(서브에이전트 포함).
```

- [ ] **Step 3: 실패 시 설명** — `- **실패 시**: …` 불릿 끝에 이어 붙인다:

```markdown
 관리자는 `POST /api/tasks/{id}/implementation/retry`(상세 "구현 재시도", 바디 `implementationMode`)로 방식을 다시 골라 처음부터 다시 구현한다 — `implementation_attempt`+1 → 브랜치 `…-r{n}`, 결과 보고의 시도 번호가 다르면(이전 시도의 늦은 보고) 상태는 그대로 두고 이력·사용량만 남긴다.
```

- [ ] **Step 4: 사전 조건 표** — "## 구현 단계 워커 사전 조건" 표 끝에 행 추가:

```markdown
| superpowers 플러그인 | SDD 구현(`--plugin-dir`) | `SUPERPOWERS_PLUGIN_PATH` — 기동 스크립트가 인터뷰 서비스와 같은 값을 워커에도 넘긴다. 없으면 SDD 요청은 리뷰로 대신 진행 |
```

- [ ] **Step 5: 자주 보는 코드 표** — 표 끝에 행 추가:

```markdown
| 구현 방식(직접·리뷰·SDD)·구현 재시도·경과 시간 | API `entity/ImplementationMode.java`, `service/ImplementationModePolicy.java`, `TaskService.retryImplementation`, `WorkerService`(claim 상한 기록·늦은 보고 무시), `StaleTaskRecoveryJob`(방식별 판정); 워커 `workerdaemon/ImplementationPrompts.java`·`ImplementationModeResolver.java`·`ImplementationScratch.java`·`SuperpowersPlugin.java`, 리소스 `prompts/implementation-review-section.md`·`implementation-reviewer.md`(superpowers code-reviewer.md MIT 사본); 프론트 `composables/implementationMode.ts`·`useNowTick.ts`, `components/ImplementationModePicker.vue`, `components/tasks/ImplementationRetryDialog.vue` |
```

- [ ] **Step 6: 커밋**

```bash
git add CLAUDE.md
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "docs(claude-md): 구현 방식·재시도·상한·플러그인 경로 (스펙 2026-10-15)"
```

---

### Task 19: 로컬 claude 스모크 (실제 실행, 기본 건너뜀)

실측(2026-10-15)에는 워커의 MCP 인자(`--mcp-config`·`--strict-mcp-config`·`--allowedTools` variadic)가 없었다. 그 조합에서 Agent·Skill 도구가 실제로 쓰이는지, 커밋 작성자·임시 파일·사용량이 기대대로인지 실제 claude로 한 번씩 확인한다. 구독 사용량이 든다(실측 연습 계획 기준 리뷰 약 $0.7, SDD 약 $1.9 상당).

**Files:**
- Create: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeClaudeSmokeTest.java`

- [ ] **Step 1: 스모크 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실제 claude로 리뷰·SDD 구현을 한 번씩 돌리는 로컬 스모크 (스펙 2026-10-15 §9) — 구독 사용량이 들어 기본은 건너뛴다.
 * 워커 명령 조합(MCP 인자 포함)·실제 git(file:// 원격)·실제 worktree로 끝까지 가고, PR/MR 생성만 막는다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_CLAUDE_SMOKE", matches = "true")
class ImplementationModeClaudeSmokeTest {

    @TempDir Path tmp;

    private static final String PLAN = String.join("\n",
            "# hello 구현 계획",
            "",
            "### 작업 1: hello.txt 만들기",
            "- [ ] 레포 루트에 `hello.txt`를 만들고 내용은 `hello` 한 줄로 한다.",
            "- [ ] 확인: `cat hello.txt`가 `hello`를 출력한다.",
            "",
            "### 작업 2: README에 한 줄 추가",
            "- [ ] `README.md` 끝에 `- hello.txt: 인사 파일` 한 줄을 추가한다.",
            "- [ ] 확인: `grep -c \"hello.txt\" README.md`가 1이다.",
            "");

    private record Run(WorkerResultRequest report, String prBody, Path bare, String branch) {}

    @Test
    void review_mode_runs_end_to_end_with_the_real_worker_command() throws Exception {
        Run r = run(ImplementationMode.REVIEW);
        assertThat(r.report().status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.prBody()).contains("- **구현 방식**: 리뷰");
        assertCleanBranch(r);
    }

    @Test
    void sdd_mode_runs_end_to_end_with_the_real_worker_command() throws Exception {
        Run r = run(ImplementationMode.SDD);
        assertThat(r.report().status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(r.prBody()).contains("- **구현 방식**: SDD");
        assertCleanBranch(r);
    }

    private Run run(ImplementationMode mode) throws Exception {
        Path bare = bareRemote();
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        WorkerProperties props = new WorkerProperties("smoke-1", null, null, null, 0, 0, 0, 0, 0,
                tmp.resolve("repos").toString(), null, System.getenv("CLAUDE_CLI"), null, null, null, null,
                tmp.resolve("worktrees").toString(), null, null, null, workerYmlTemplate(), null, null, null,
                deploy, new WorkerProperties.RepoCacheCleanup(false, null, 30), null);
        GitRepoCache cache = new GitRepoCache(props);
        WorktreeService worktrees = new WorktreeService(props, cache);
        GitOpsService gitOps = spy(new GitOpsService(props));
        doReturn(new GitOpsService.PrInfo("https://example.invalid/pr/1", 1))
                .when(gitOps).createDraftPr(any(), any(), anyString(), anyString(), anyString(), anyString());
        WorkerMcpSupport mcp = mock(WorkerMcpSupport.class);
        Path emptyMcp = Files.writeString(tmp.resolve("mcp.json"), "{\"mcpServers\":{}}");
        when(mcp.buildClaudeArgsForTask(any())).thenReturn(new WorkerMcpSupport.TaskClaudeArgs(
                List.of("--mcp-config", emptyMcp.toString(), "--strict-mcp-config",
                        "--allowedTools", "mcp__smoke__noop"), null));
        ClaudeExecAdapter claude = new ClaudeExecAdapter(props, mcp);
        claude.init();
        WorkerHttpClient http = mock(WorkerHttpClient.class);
        ResultReporter reporter = mock(ResultReporter.class);

        Task t = Task.create("acme/hello", "main", "hello 파일", "hello.txt를 만들고 README에 한 줄", "user1", 3,
                List.of(), "claude-sonnet-5-5", "medium");
        ReflectionTestUtils.setField(t, "id", 901L);
        t.setGitUrl(bare.toUri().toString());
        t.setImplementationMode(mode);
        t.setImplementationTimeoutMinutes(mode == ImplementationMode.SDD ? 60 : 30);
        when(http.nextTask()).thenReturn(Optional.of(WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(901L, "# 설계\nhello 파일을 만든다.", "[]", null, 1L), null, PLAN)));

        new WorkerMainLoop(props, http, cache, claude, mock(PromptResultParser.class), mcp, worktrees, gitOps,
                mock(DeployService.class), reporter, mock(SilentLossTracker.class), mock(DesignResultHarvester.class),
                mock(TaskCloser.class), new SuperpowersPlugin(System.getenv("SUPERPOWERS_PLUGIN_PATH")))
                .pollAndProcess();

        ArgumentCaptor<WorkerResultRequest> report = ArgumentCaptor.forClass(WorkerResultRequest.class);
        verify(reporter).reportTerminal(eq(901L), report.capture());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gitOps, atMostOnce()).createDraftPr(any(), any(), anyString(), anyString(), anyString(), body.capture());
        return new Run(report.getValue(), body.getAllValues().isEmpty() ? "" : body.getValue(), bare,
                report.getValue().headBranch());
    }

    private static void assertCleanBranch(Run r) throws Exception {
        String files = ProcessRunner.requireSuccess(r.bare().toFile(),
                List.of("git", "ls-tree", "-r", "--name-only", "refs/heads/" + r.branch()), 30);
        assertThat(files).contains("hello.txt")
                .doesNotContain(".netis").doesNotContain(".superpowers").doesNotContain(".design");
        String authors = ProcessRunner.requireSuccess(r.bare().toFile(),
                List.of("git", "log", "--format=%an <%ae>", "main..refs/heads/" + r.branch()), 30);
        assertThat(authors.lines().toList()).isNotEmpty()
                .allMatch(a -> a.equals("netisMaker <netismaker@hamonsoft.local>"));
        assertThat(r.report().usage()).isNotNull();
        assertThat(r.report().usage().outputTokens()).isPositive();
    }

    private Path bareRemote() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed"));
        git(seed.toFile(), "init", "-b", "main");
        Files.writeString(seed.resolve("README.md"), "# hello\n");
        git(seed.toFile(), "add", "-A");
        git(seed.toFile(), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-m", "init");
        Path bare = tmp.resolve("remote.git");
        git(tmp.toFile(), "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    private static void git(File dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.requireSuccess(dir, cmd, 60);
    }

    @SuppressWarnings("unchecked")
    private static String workerYmlTemplate() {
        try (InputStream in = ImplementationModeClaudeSmokeTest.class.getResourceAsStream("/application-worker.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> worker = (Map<String, Object>) ((Map<String, Object>) root.get("netis-maker")).get("worker");
            return (String) worker.get("implementation-prompt-template");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: 기본 빌드에서는 건너뛰는지 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationModeClaudeSmokeTest"`
Expected: 2 tests SKIPPED

- [ ] **Step 3: 실제로 돌리기** — Claude Code 세션 안이면 중첩 세션 판정을 피하려고 `CLAUDE*`·`ANTHROPIC*`·`MCP_*` 환경변수를 지운 Git Bash에서(지운 뒤 필요한 값만 다시 준다):

```bash
for v in $(env | grep -E '^(CLAUDE|ANTHROPIC|MCP_)[A-Z0-9_]*=' | cut -d= -f1); do unset "$v"; done; RUN_CLAUDE_SMOKE=true CLAUDE_CLI=C:/Users/mic/.local/bin/claude.exe SUPERPOWERS_PLUGIN_PATH=C:/Users/mic/.claude/plugins/cache/claude-plugins-official/superpowers/6.4.1 JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --no-daemon --tests "com.hamonsoft.netismaker.workerdaemon.ImplementationModeClaudeSmokeTest"
```

Expected: 2 tests PASS(합계 수십 분). 실패하면 `build/reports/tests/test/index.html`과 `~/.claude/projects/` 아래 해당 worktree 세션 기록에서 Agent·Skill 호출 여부를 확인한다. MCP 인자 때문에 Agent·Skill 도구가 막히면 그 사실을 보고하고 진행을 멈춘다(설계 변경 필요).

- [ ] **Step 4: 커밋**

```bash
git add src/test/java/com/hamonsoft/netismaker/workerdaemon/ImplementationModeClaudeSmokeTest.java
git commit -F C:/Users/mic/AppData/Local/Temp/impl-mode-commit.txt   # 제목: "test(worker): 실제 claude로 리뷰·SDD 구현 스모크(RUN_CLAUDE_SMOKE) (스펙 2026-10-15 §9)"
```

---

### 마무리 (구현 뒤 — 이 계획 밖의 운영 절차)

- 전체 검증: `./gradlew test`(이 PC 고유 실패 3건 외 PASS), `frontend`에서 `npx vitest run`, PR의 CI 3개(백엔드 Testcontainers 포함, 프론트 하이드레이션 스모크).
- PR 생성 → CI 통과 → 병합 뒤 라이브 반영 순서: **API**(V29) → **워커**(진행 중 구현이 없을 때, 기동 스크립트가 `SUPERPOWERS_PLUGIN_PATH` 전달 — `.run\worker.log`에 `SDD 플러그인: …`) → **프론트**(`.output` 재빌드). 재기동 절차는 메모리 `live-stack-launch-via-scheduled-task`.
- 라이브 확인(사용자가 확정 클릭): 작은 실제 작업을 리뷰·SDD로 각 1회 — PR/MR 본문 방식 줄·리뷰 메모, 커밋 작성자, MR에 `.netis/`·`.superpowers/` 없음, 구현 단계 사용량, 소요 시간 대 상한, 상세·목록 경과 표시.
- 정리: 이 기능이 대체한 미커밋 worktree `.claude/worktrees/plan-section-comment`(브랜치 `docs/plan-section-subagent-rationale`)를 지운다(사용자 승인됨). 이 worktree를 지우기 전에 `cmd //c "rmdir frontend\\node_modules"`로 정션부터 지운다.
