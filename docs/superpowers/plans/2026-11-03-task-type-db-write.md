# 작업 타입 + 구현 단계 개발 DB 쓰기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업에 타입(신규 개발·기능 수정·버그 픽스·DB 작업)을 붙인다. 타입에 따라 인터뷰·구현 지시, 커밋/PR 앞머리, 목록 표시가 달라진다. 구현 단계에서는 관리자가 허용한 공용 개발 DB에 분류된 쓰기 SQL을 실행하고, 실행 내역을 기록과 PR 스크립트로 남긴다. DB 작업은 배포 없이 PR생성에서 종료할 수 있다.

**Architecture:** 세 Part로 나눈다. 각 Part가 끝난 상태로 병합·배포해도 앞 동작이 깨지지 않는다.
- **A:** `task.task_type`(NULL = 타입 없음)을 등록·승인·목록·인터뷰 claim·구현 claim(`ImplementationSpec.taskType`)에 싣는다.
- **B:** API가 자체 토큰 분석으로 SQL을 바로 실행/승인 대기/거부로 나누고, 쓰기·소유 기록을 남긴다. `execute` 도구는 6조건이 모두 참일 때만 나타난다. 전역 스위치 기본값이 false라서 B는 꺼진 채로 배포된다. 워커는 커밋 직전에 쓰기 기록으로 SQL 스크립트 파일을 만든다.
- **C:** DB 작업에 맞춰 종료 요청 관문·복귀 후보·산출물 모드·배포 중지 단계를 연다.

**Tech Stack:** Java 21 · Spring Boot 3 · JPA/Flyway(PostgreSQL `com`) · JDBC(PG·MySQL/MariaDB·Oracle) · Mockito/JUnit 5 · Testcontainers · TypeScript(인터뷰 서비스, vitest) · Nuxt 3 + Quasar(vitest, hydration smoke)

**Spec:** `docs/superpowers/specs/2026-11-03-task-type-db-write-design.md` 전부를 구현한다.
- 이 계획의 줄 번호는 이 worktree(#122 head `c87f82d` 위) 기준이다.
- 설계의 줄 번호(main `b991069` 기준)와 다르면 Task의 Files를 따른다.

경로 줄임: `M/`=`src/main/java/com/hamonsoft/netismaker/`, `T/`=`src/test/java/com/hamonsoft/netismaker/`, `R/`=`src/main/resources/`, `F/`=`frontend/`, `I/`=`netismaker-interview-service/`.

## Global Constraints

- **선행:** #122(V38)가 main에 병합된 뒤 시작하고, 이 브랜치를 `origin/main`으로 rebase한다. 병합 전이면 시작하지 않는다.
- **마이그레이션:** Task 1이 `R/db/migration/V39__task_type_db_write.sql`을 만든다.
  - Task 8은 시작할 때 V39가 담긴 커밋이 `origin/main`에 있는지 본다(`git branch -r --contains`). 없으면 V39에 덧붙인다.
  - 있으면 V39는 고정이다(체크섬은 배포가 아니라 main 병합 때 굳는다). 이때 `R/db/migration`의 가장 큰 번호 + 1로 `V{n}__db_write.sql`을 새로 만든다. V40은 간단 인터뷰 설계가 예정해 둔 번호라 고정하지 않는다(`2026-11-03-interview-superpowers-toggle-design.md:6`).
  - `ddl-auto: validate`(`R/application.yml:34`)이므로 엔티티 칸과 마이그레이션은 같은 Task에서 바꾼다.
- **타입 값:** `NEW_FEATURE` 신규 개발 `feat` · `FEATURE_CHANGE` 기능 수정 `feat` · `BUG_FIX` 버그 픽스 `fix` · `DB_WORK` DB 작업 `db` · NULL 타입 없음.
  - API는 String으로 받아 trim 후 대소문자 구분으로 파싱한다. 모르는 값은 400 `알 수 없는 작업 타입: {값}`이다.
  - 워커·인터뷰 서비스는 모르는 값을 타입 없음으로 읽는다.
  - 응답의 `"taskType": null`은 그대로 나간다. TS·프론트는 `undefined`·`null`을 둘 다 타입 없음으로 본다.
- **타입 없음은 바이트까지 지금과 같다:** kickoff(`KICKOFF_GOLDEN`, `I/test/interviewRunner.test.ts:1189-1199`), 구현 프롬프트, 커밋 `feat: {제목}`, 앞머리 없는 PR 제목, 키를 생략한 등록·승인 바디. 브랜치 이름은 바꾸지 않는다. 수정 회차 커밋은 늘 `fix: 수정 N회차 — 제목`이다.
- **쓰기 설정:**
  - `app.db-write.enabled: ${DB_WRITE_ENABLED:false}`
  - `max-update-delete-rows: 1000`, `max-insert-rows: 10000`. 남의 테이블에만 적용한다.
  - 문장 30초(`DbMcpLimits.STATEMENT_TIMEOUT_SECONDS`)
- **쓰기 조건:** 이 순서로 보고, 걸리면 다음 사유 문구를 그대로 쓴다.
  1. `개발 DB 쓰기 스위치가 꺼져 있습니다`
  2. `구현 단계에서만 쓸 수 있습니다`
  3. `이 작업이 고른 연결이 아닙니다`
  4. `쓰기 허용 연결이 아닙니다` — allow_write·REPO·enabled
  5. `자동 마이그레이션 레포입니다 — 마이그레이션 파일로 만드세요`
  6. `이 워커는 DB 쓰기를 지원하지 않습니다`
- **읽기 경로는 바꾸지 않는다:** `SqlReadOnlyChecker.check`, TS `sqlReadOnly.ts`, `JdbcReadOnlyQueryExecutor`, 인터뷰 서비스 DB 코드.
- **외부 DB 실행:**
  - 앱 DB 트랜잭션 밖에서 한다(`DbConnectionService.java:105-108`).
  - 비밀번호·접속정보는 로그·응답·error 칸에 남기지 않는다(`DbMcpTools.safeMessage`).
  - 앱 로그에는 SQL 앞 200자만 남긴다.
- **스크립트 경로:** `{db_script_dir 또는 db/symphony}/task-<id>.sql`(워커가 기록으로 만든다). 쓰기 도구 없이 이전 기록이 있을 때 세션이 쓰는 파일은 `task-<id>-manual.sql`이다(Task 14).
- **화면:**
  - `confirmDialog($q)`만 쓴다.
  - Quasar 반응형 이름을 클래스로 쓰지 않는다.
  - `defineProps<T>()`의 T를 `~/`에서 가져오지 않는다.
  - 등록 창 문구에 '모델'·'MCP'를 넣지 않는다(`F/test/tasks-form-repo-select.spec.ts:113-125`).
- **시험:**
  - 이 worktree에서만 돌리고 `gradlew --stop`은 금지다.
  - 단위 시험은 `.\gradlew.bat test --tests <FQCN>`으로 돌린다. Testcontainers 시험은 CI가 돌린다(이 PC에 Docker 없음).
  - **(CI) 표시 시험**은 `@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")` + `@SpringBootTest` + `@ContextConfiguration(initializers = TestcontainersConfig.class)`를 단다(`ImplementationModeFlowIntegrationTest.java:45-48`). 새 initializer는 만들지 않는다.
    - 그 Task의 Step 4에서는 `.\gradlew.bat compileTestJava`가 성공하고, 로컬 리포트에서 그 클래스가 skipped인지만 본다.
    - 실제 실행 확인은 Task 21 Step 7(CI)에서 한다.
  - **frontend**는 junction 없이 이 worktree에서 `npm ci`를 한다(이 PC는 Node 24/npm 11). 라이브 `frontend/node_modules`에는 `@nuxt/eslint-config`·`playwright-core`가 없다(`F/package.json:20,30`).
    - 이미 걸린 junction은 `cmd /c rmdir frontend\node_modules`로만 걷는다. `Remove-Item -Recurse`는 링크 대상(라이브 체크아웃)까지 지울 수 있어 쓰지 않는다.
  - **netismaker-interview-service**만 라이브 체크아웃 `node_modules`로 `New-Item -ItemType Junction`을 건다(lock 파일이 같다).
  - frontend `npm ci` 뒤 `cd frontend; npm run lint` 경고 수를 기준선으로 PR 초안에 적는다.
- **커밋:** 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. 쓰기 허용 연결을 접속 대상은 그대로 두고 저장하는 경우(이름 변경, 활성 토글의 같은 값 재전송, host 공백 차이). `allow_write`와 소유 기록이 남아야 한다(Task 8).
2. 승인 대기 SQL "실행"을 두 번 누르거나 두 관리자가 동시에 누르는 경우. 한 번만 실행하고 두 번째는 409로 막아야 한다(Task 8 CI 시험이 실제 DB의 `transition`을, Task 12가 서비스 흐름을 본다).
3. 대상 레포 `.gitignore`가 `db/`·`*.sql`을 무시하는 경우. 워커가 쓴 파일이든 세션이 쓴 파일이든, 성공 경로와 wip 경로 모두에서 스크립트 파일을 강제로 추가해 커밋해야 한다(Task 5·15, 실제 git).
4. 따옴표·대소문자·스키마로 소유 테이블을 흉내 낸 문장(`DROP TABLE "T_New"`, `other_schema.t_new`, Oracle `"t_new"`). 남의 테이블로 보고 거부해야 한다(Task 7).
5. 재시도(`-rN`)라 `head_branch`가 비었는데, 다른 열린 작업이 옛 브랜치 `netismaker/task-42-…`를 기준으로 쓰는 경우. 소유 예외가 꺼져야 한다(Task 11).

---

# Part A — 작업 타입 (§4·§5)

### Task 1: `TaskType`·`task.task_type`·응답 칸

**Files:**
- Create: `M/entity/TaskType.java`, `R/db/migration/V39__task_type_db_write.sql`
- Modify: `M/entity/Task.java:132-136` 근처
- Modify: `M/dto/TaskResponse.java:21-77` — 끝 컴포넌트를 추가하고, 생성하는 다섯 곳 `:213`·`:257`·`:267`·`:284`·`:334`을 같이 고친다.
- Test: `T/entity/TaskTypeTest.java`, `T/dto/TaskResponseTaskTypeTest.java`, `T/controller/TaskTypeMigrationIntegrationTest.java`(CI, `ImplementationModeFlowIntegrationTest.java:99-117` 하네스)

**Interfaces:**
- Produces:
  - `enum TaskType { NEW_FEATURE("신규 개발","feat"), FEATURE_CHANGE("기능 수정","feat"), BUG_FIX("버그 픽스","fix"), DB_WORK("DB 작업","db") }`
    - `String label()`, `String commitPrefix()`
    - `static TaskType parseRequest(String)` — null·공백이면 null. 모르는 값은 `TaskException(BAD_REQUEST)`(`ImplementationModePolicy.java:61-68` 모양).
    - `static TaskType fromWireOrNull(String)`
    - `static String labelOf(TaskType)`, `static String commitPrefixOf(TaskType)`, `static String prTitle(TaskType, String title)`
  - `Task.getTaskType()`/`setTaskType(TaskType)` — `@Enumerated(STRING) @Column(name="task_type", length=20) @Setter`
  - `TaskResponse.taskType()` — 끝 `String` 컴포넌트

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 라벨과_앞머리() { assertThat(TaskType.BUG_FIX.label()).isEqualTo("버그 픽스"); assertThat(TaskType.DB_WORK.commitPrefix()).isEqualTo("db"); assertThat(TaskType.FEATURE_CHANGE.commitPrefix()).isEqualTo("feat"); }
@Test void 타입없음() { assertThat(TaskType.labelOf(null)).isEqualTo("타입 없음"); assertThat(TaskType.commitPrefixOf(null)).isEqualTo("feat");
    assertThat(TaskType.prTitle(null, "로그인")).isEqualTo("로그인"); assertThat(TaskType.prTitle(TaskType.BUG_FIX, "로그인")).isEqualTo("fix: 로그인"); }
@Test void 요청값_파싱() { assertThat(TaskType.parseRequest("  ")).isNull(); assertThat(TaskType.parseRequest(" DB_WORK ")).isEqualTo(TaskType.DB_WORK);
    assertThatThrownBy(() -> TaskType.parseRequest("db_work")).hasMessageContaining("알 수 없는 작업 타입: db_work"); assertThat(TaskType.fromWireOrNull("QA_WORK")).isNull(); }
@Test void 응답과_사본_네곳이_taskType_유지()     // withTotalCost·withRefRepos·withFix·redactSecretValues → "BUG_FIX"; 타입 없음 → null
@Test void 모르는_값은_이름있는_제약으로_거절()    // (CI) task_type='QA' → 메시지에 "ck_task_task_type"; NULL 허용
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.entity.TaskTypeTest --tests com.hamonsoft.netismaker.dto.TaskResponseTaskTypeTest` → 컴파일 실패
- [ ] **Step 3: 구현** — V39는 한국어 머리 주석(스펙 §7) 아래에 다음 한 문장을 둔다.
  `ALTER TABLE com.task ADD COLUMN task_type VARCHAR(20), ADD CONSTRAINT ck_task_task_type CHECK (task_type IS NULL OR task_type IN ('NEW_FEATURE','FEATURE_CHANGE','BUG_FIX','DB_WORK'));`
  - **설계와 다름:** 설계는 이름 없는 인라인 CHECK다. 여기서는 V29처럼 이름 붙은 제약을 쓴다(`V29__task_implementation_mode.sql:7`). 기존 시험이 제약 이름으로 거절을 확인하기 때문이다(`ImplementationModeFlowIntegrationTest.java:112-114`).
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS, `.\gradlew.bat compileJava compileTestJava` 성공
- [ ] **Step 5: 커밋** — `feat(type): 작업 타입 enum과 task.task_type`

---

### Task 2: 등록·승인 API의 타입

**Files:**
- Modify: `M/dto/TaskCreateRequest.java:15-42`, `M/dto/ApproveRequest.java:15-31`, `M/service/TaskService.java:248-276`(`register`)·`:538-566`(`startInterview`)
- Test: `T/service/TaskServiceTaskTypeTest.java`(`TaskServiceApproveDbTest.java:31-45` 하네스), `T/dto/TaskCreateRequestJsonTest.java`(추가)

**Interfaces:**
- Consumes: Task 1 `parseRequest`·`labelOf`
- Produces:
  - `TaskCreateRequest(…, Long sourceQuestionId, String taskType)` — 6인자 호환 생성자를 둔다. multipart `meta`도 같은 레코드다(`TaskController.java:100-110`).
  - `ApproveRequest(…, @Valid List<RefRepoInput> refRepos, String taskType)` — 5인자 호환 생성자를 둔다. null이면 기존 값을 유지한다.
  - 이력(같은 상태 행): `타입 변경: {labelOf(이전)} → {labelOf(새)}`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 등록_바디의_타입을_저장()                 // "BUG_FIX" → BUG_FIX, 없으면 null
@Test void 모르는_타입은_저장_전에_400()              // "bug_fix" → 400, verify(taskRepo, never()).save(any())
@Test void 승인에서_바꾸면_이력()                    // NEW_FEATURE→"DB_WORK": "타입 변경: 신규 개발 → DB 작업"; null→BUG_FIX: "타입 변경: 타입 없음 → 버그 픽스"
@Test void 같은_값이나_null이면_이력없는_no_op()
@Test void 승인_타입이_틀리면_세션을_만들지_않는다()   // verify(interviewService, never()).createForTask(any(), any(), any(), any())
@Test void 레거시_분석완료_승인은_타입_무시()
@Test void meta_JSON의_taskType_바인딩()
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.TaskServiceTaskTypeTest --tests com.hamonsoft.netismaker.dto.TaskCreateRequestJsonTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 다음 세 자리를 기존 규약에 맞춰 넣는다.
  - `register`: 저장 전 검증(`:244-247`)에서 파싱한다.
  - `startInterview`: 검증 블록(`:539-547`)에서 파싱한다.
  - 반영: `createForTask`(`:555`) 앞, 이력은 참조 변경 이력(`:562-565`) 옆에 둔다.
  - **설계와 다름 ①:** 설계의 "승인 뒤 변경 불가"는 승인 밖에 변경 경로를 두지 않는 것으로 구현한다. 인터뷰가 실패·만료·취소되면 승인대기로 돌아가므로(`InterviewService.java:46-48`) 재승인 때 다시 바꿀 수 있다.
  - **설계와 다름 ②:** null은 유지라는 기존 규약(`ApproveRequest.java:7-14`)을 따르므로 "타입 없음"으로 되돌리는 경로는 없다.
  - **설계와 다름 ③:** 같은 값이 오면 아무것도 하지 않는다(`TaskService.java:534` 선례).
- [ ] **Step 4: 통과 확인** — Step 2 명령과 `--tests "com.hamonsoft.netismaker.service.TaskService*" --tests com.hamonsoft.netismaker.dto.RefRepoInputValidationTest` PASS
- [ ] **Step 5: 커밋** — `feat(type): 등록·승인에서 작업 타입 받기와 변경 이력`

---

### Task 3: 목록 `type` 필터

**Files:**
- Create: `M/service/TaskTypeFilter.java`
- Modify: `M/controller/TaskController.java:112-127`, `M/service/TaskService.java:442-449`, `M/repository/TaskRepository.java:42-60`
- Modify(시험 호출 맞춤):
  - 스텁: `T/controller/TaskControllerRefReposTest.java:66,121`, `TaskControllerSecretRedactionTest.java:54,72,83`, `TaskFixControllerTest.java:180,184`
- Test: `T/service/TaskTypeFilterTest.java`, `T/repository/TaskListTypeFilterIntegrationTest.java`(CI)

**Interfaces:**
- Consumes: Task 1 `TaskType`
- Produces:
  - `record TaskTypeFilter(boolean any, TaskType type)`
    - `ANY`
    - `static TaskTypeFilter parse(String)` — 매핑:
      - null·공백 → `ANY`
      - `"NONE"` → `(false, null)`
      - enum 이름 → `(false, X)`
      - 그 밖 → 400 `알 수 없는 작업 타입 필터: {값}`
  - `TaskService.list(String viewerId, boolean isAdmin, boolean mineOnly, TaskStatus status, TaskTypeFilter type, Pageable)`
  - `TaskController.list(TaskStatus status, @RequestParam(required=false) String type, boolean mine, Pageable, JwtAuthenticationToken)`
  - `TaskRepository.findByRequester(String, TaskStatus, boolean anyType, TaskType type, Pageable)`
  - `TaskRepository.findAllActive(TaskStatus, boolean anyType, TaskType type, Pageable)`
    - 두 쿼리에 `AND (:anyType = true OR (:type IS NULL AND t.taskType IS NULL) OR t.taskType = :type)`를 넣는다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 필터_파싱() { assertThat(TaskTypeFilter.parse("NONE")).isEqualTo(new TaskTypeFilter(false, null)); assertThat(TaskTypeFilter.parse(null)).isEqualTo(TaskTypeFilter.ANY);
    assertThatThrownBy(() -> TaskTypeFilter.parse("bug")).hasMessageContaining("알 수 없는 작업 타입 필터: bug"); }
// (CI) BUG_FIX·NULL·DB_WORK 3건 → 필터 없음 3, NONE 1, BUG_FIX 1; status와 AND; 관리자·요청자 쿼리 둘 다
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.TaskTypeFilterTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 컨트롤러가 `TaskTypeFilter.parse(type)`를 부른다.
  - **설계와 다름:** 설계(§4.3)는 "타입 없음"의 와이어 값을 정하지 않았다. `NONE`으로 정한다.
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.TaskTypeFilterTest --tests "com.hamonsoft.netismaker.controller.Task*Controller*Test"` PASS
- [ ] **Step 5: 커밋** — `feat(type): 작업 목록 type 필터`

---

### Task 4: 인터뷰 — claim `taskType`과 타입별 kickoff

**Files:**
- Modify: `M/dto/InterviewClaimResponse.java:18-77,149-203`(끝 컴포넌트, `of`·`with*` 세 곳), `M/service/InterviewService.java:197-222`
- Create: `I/src/runner/kickoffByType.ts`
- Modify: `I/src/types.ts:102-170`, `I/src/runner/interviewRunner.ts:151-177`
- Test: `T/dto/InterviewClaimResponseTest.java`, `T/service/InterviewServiceTest.java`(추가), `I/test/kickoffByType.test.ts`, `I/test/interviewRunner.test.ts`(추가)

**Interfaces:**
- Consumes: Task 1 `Task.getTaskType()`
- Produces:
  - Java
    - `InterviewClaimResponse.taskType()`(String)
    - `withTaskType(String)`
  - TS 타입
    - `export type TaskTypeWire = 'NEW_FEATURE'|'FEATURE_CHANGE'|'BUG_FIX'|'DB_WORK'`
    - claim 칸 `taskType?: TaskTypeWire | null`
  - TS 함수
    - `export function kickoffLead(claim: Pick<InterviewClaimResponse,'taskType'|'githubRepo'|'githubBranch'>): string`
    - `export function taskTypeGuide(taskType: unknown): string` — 타입 없음·NEW_FEATURE·모르는 값이면 `''`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 작업_인터뷰_claim은_작업_타입()     // taskRepo.findActiveById(77L) → BUG_FIX → "BUG_FIX"; 질문 세션 → null
@Test void with사본이_taskType_유지_JSON()     // withDb·withRefRepos·withRefsCleared, "\"taskType\":\"BUG_FIX\""
```
프롬프트 단언은 `I/test/interviewRunner.test.ts`의 `describe('작업 인터뷰 (스펙 2026-10-24 §5)')`(`:1160`) 안에 둔다. `KICKOFF_GOLDEN`(`:1189`)·`noDb`(`:1183`)가 그 describe의 지역 값이기 때문이다. `KICKOFF_GOLDEN` 바로 아래에 describe 범위 헬퍼를 하나 둔다. 기존 `it` 안의 지역 `promptOf`(`:1245`, 참조·`withDb`)는 그대로 둔다.

```ts
const kickoffOf = async (claim: InterviewClaimResponse, db = noDb()): Promise<string> => {
  const { fakeQuery, captured } = capturing(() => questionStream());
  await runnerWith(makeClient(), fakeQuery, vi.fn(), { createDbMcp: vi.fn().mockReturnValue(db) }).run(claim);
  return captured.prompt;
};
it('타입 없음·NEW_FEATURE·모르는 값은 골든', async () => { for (const t of [undefined, null, 'NEW_FEATURE', 'QA_WORK']) expect(await kickoffOf({ ...freshClaim, taskType: t } as any)).toBe(KICKOFF_GOLDEN) })
it('버그 픽스 kickoff', async () => { const p = await kickoffOf({ ...freshClaim, taskType: 'BUG_FIX' })
  expect(p.startsWith(`I want to fix a bug in the repo at ${freshClaim.githubRepo} (branch ${freshClaim.githubBranch}).\n`)).toBe(true)
  for (const s of ['어느 화면에서, 무엇을 했을 때, 어떤 오류가 뜨는지', '재현 → 원인 → 수정 → 같은 오류가 다시 안 나는지 확인', '`# <기능> 구현 계획`', '### 작업 N:', 'PLANNING-ONLY']) expect(p).toContain(s) })
it('기능 수정·DB 작업')   // 'I want to change an existing feature in the repo at' + '바꾸면 안 되는 것' / 'I want to change database tables or data used by the repo at' + '[바로 실행]'·'[승인 대기]'
it('resume은 타입과 무관', async () => expect(await kickoffOf({ ...resumeClaim, taskType: 'BUG_FIX' })).toBe(resumeClaim.lastAnswer))
// I/test/kickoffByType.test.ts — 순수 함수만: kickoffLead 세 타입 첫 문장, taskTypeGuide(undefined|null|'NEW_FEATURE'|'QA_WORK') === ''
```

- [ ] **Step 2: 실패 확인**
  - `.\gradlew.bat test --tests com.hamonsoft.netismaker.dto.InterviewClaimResponseTest --tests com.hamonsoft.netismaker.service.InterviewServiceTest` → 컴파일 실패
  - `cd netismaker-interview-service; npx vitest run test/kickoffByType.test.ts test/interviewRunner.test.ts` → FAIL
- [ ] **Step 3: 구현**
  - **Java:** claim 조립 끝(`:218-221`)에 `.withTaskType(…)`을 붙인다. 값은 `taskId`가 null이면 null, 아니면 `taskRepo.findActiveById(…).map(Task::getTaskType).map(Enum::name).orElse(null)`이다.
    - **설계와 다름:** 세션에 타입 칸을 두지 않는다. claim 때 작업 행에서 읽는다(`InterviewService.java:269-271` 선례). 그래서 V39는 `interview_session`을 건드리지 않는다.
  - **TS:**
    - `:160` 첫 문장을 `kickoffLead`로 바꾼다.
    - `attachmentSection` 다음, `IMPORTANT —` 앞에 `taskTypeGuide`를 넣는다.
    - PLANNING-ONLY, 계획 형식(`planHarvest.ts:15-16`), brainstorming 줄은 그대로 둔다.
    - 타입 절(`kickoffLead`·`taskTypeGuide`, Task 13의 `writableDbLine`)과 진행 방식 문구(PLANNING-ONLY 문단의 `(via brainstorming → writing-plans)`, 마지막 `Use the brainstorming skill…` 줄)는 서로 섞지 않는다. 후속 간단 인터뷰 설계가 진행 방식 문구만 바꾼다(`2026-11-03-interview-superpowers-toggle-design.md:64`).
    - 타입 판정은 리터럴 비교로 한다.
  - **첫 문장**
    - FEATURE_CHANGE: `I want to change an existing feature in the repo at {repo} (branch {branch}).\n`
    - BUG_FIX: `I want to fix a bug in the repo at {repo} (branch {branch}).\n`
    - DB_WORK: `I want to change database tables or data used by the repo at {repo} (branch {branch}).\n`
  - **지시 문단** (각각 끝에 `\n\n`)
    - FEATURE_CHANGE: `작업 타입: 기능 수정. 먼저 코드를 읽어 지금 어떻게 동작하는지 확인하고, 무엇을 어떻게 바꿀지와 바꾸면 안 되는 것(유지할 동작)부터 질문하세요.`
    - BUG_FIX: `작업 타입: 버그 픽스. 어느 화면에서, 무엇을 했을 때, 어떤 오류가 뜨는지부터 질문하세요. 첨부(캡처·로그)가 있으면 먼저 보고, 코드 읽기와 DB 조회 도구로 원인을 좁혀 보세요. 계획의 작업은 재현 → 원인 → 수정 → 같은 오류가 다시 안 나는지 확인(가능하면 회귀 테스트) 순서로 쓰세요.`
    - DB_WORK: `작업 타입: DB 작업. 어떤 테이블·데이터를 어떻게 바꿀지부터 질문하세요. 계획에는 실행할 SQL을 순서대로 적고, 문장마다 [바로 실행] 또는 [승인 대기]를 표시하세요. [바로 실행]: 새 테이블·인덱스·시퀀스·뷰, NULL 허용이거나 DEFAULT 있는 컬럼 추가, INSERT, WHERE 있는 UPDATE/DELETE. [승인 대기]: 남의 테이블 컬럼 삭제·이름·타입·NULL 여부 변경, NOT NULL이면서 DEFAULT 없는 컬럼 추가, 제약·유니크 인덱스·트리거·프로시저. 기존 테이블 DROP·TRUNCATE와 조건 없는 UPDATE/DELETE는 거부됩니다.`
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS. `npx vitest run; npx tsc --noEmit -p tsconfig.json` PASS(기존 골든 단언 `:1255,1290,1292,1335` 포함).
- [ ] **Step 5: 커밋** — `feat(type): 인터뷰 claim 타입과 타입별 kickoff`

---

### Task 5: 워커 — 타입 절, 커밋·PR 앞머리

**Files:**
- Modify: `M/dto/WorkerTaskResponse.java:110-115,271-275`, `M/workerdaemon/ImplementationPrompts.java`
- Modify: `M/workerdaemon/WorkerMainLoop.java`
  - `:394-399` 프롬프트 조립
  - `:444-446` 커밋
  - `:460-462` PR 제목
  - `:911-973` 렌더·fill
  - `:1021-1046` 기본 템플릿
  - `:432-447` 성공 경로 커밋 전, `:754-760` wip 경로(세션이 쓴 스크립트 파일 강제 추가)
- Modify: `R/application-worker.yml:126-151`, `M/workerdaemon/GitOpsService.java`(`forceAdd`)
- Test: `T/workerdaemon/WorkerMainLoopTaskTypeTest.java`(`WorkerMainLoopImplementationModeTest` 하네스), `ImplementationPromptsTaskTypeTest.java`, `WorkerMainLoopPlanSectionTest.java`(추가), `T/dto/WorkerTaskResponseImplementationTest.java`(추가), `T/workerdaemon/GitOpsServicePushTest.java`(추가, 실제 git)

**Interfaces:**
- Consumes: Task 1 `fromWireOrNull`·`commitPrefixOf`·`prTitle`
- Produces:
  - `record ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes, FixRoundSpec fix, String taskType)` — 4인자 호환 생성자를 추가한다.
  - `static String ImplementationPrompts.taskTypeSection(TaskType type, boolean writeTools, boolean migrationManaged, String scriptPath)` — Part A는 `(type,false,false,defaultScriptPath(id))`로 부른다.
  - `static String ImplementationPrompts.defaultScriptPath(long taskId)` → `"db/symphony/task-{id}.sql"`
  - `static String WorkerMainLoop.fillImplementationPrompt(…, String planSection, String commitRule, String taskTypeSection)` — 6인자판은 `""`로 위임한다.
  - `static TaskType WorkerMainLoop.taskTypeOf(WorkerTaskResponse)`
  - `GitOpsService.forceAdd(File dir, String relPath)` — `git add -f -- <relPath>`. `.gitignore`가 그 경로를 무시해도 커밋·wip에 들어간다.
  - 세션이 쓴 스크립트 파일: 성공·wip 두 경로 모두, 커밋 판단 **전에** `defaultScriptPath(id)` 파일이 worktree에 있으면 `forceAdd`한다. 타입과 무관하다. 성공 경로는 기존 commit `try` 안에서 하므로 실패하면 `commit/push 실패: …`이다. wip 경로는 실패해도 경고만 남기고 `gitOps.preservePartialWork`로 간다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 버그픽스는_커밋과_PR제목이_fix()  // commitMsg startsWith "fix: Add login\n\ntask #42"; createDraftPr title "fix: Add login"; DB_WORK → "db: …"
@Test void 타입없음과_모르는_타입은_feat_PR제목_그대로()
@Test void 수정회차는_타입과_무관()            // "fix: 수정 2회차 — Add login…", 프롬프트에 "## 작업 타입" 없음
@Test void 타입절_문구() {
    assertThat(ImplementationPrompts.taskTypeSection(TaskType.BUG_FIX, false, false, "db/symphony/task-42.sql")).isEqualTo(
        "## 작업 타입: 버그 픽스\n먼저 계획의 재현 방법으로 오류를 재현하고 원인을 확인한 뒤 고치세요. 고친 뒤 같은 오류가 다시 나지 않는지 확인하세요(가능하면 회귀 테스트 추가).\n");
    assertThat(ImplementationPrompts.taskTypeSection(TaskType.DB_WORK, false, false, "db/symphony/task-42.sql")).isEqualTo(
        "## 작업 타입: DB 작업\n이 구현에는 개발 DB 쓰기 도구가 없습니다. 계획의 SQL을 실행하지 말고 `db/symphony/task-42.sql` 파일에 실행 순서대로 적으세요(문장마다 끝에 `;`). 코드 변경은 필요할 때만 하세요.\n");
    assertThat(ImplementationPrompts.taskTypeSection(TaskType.FEATURE_CHANGE, false, false, "x")).isEmpty(); }   // null·NEW_FEATURE도
@Test void 두_템플릿_모두_task_type_section()           // yml(SnakeYAML)·defaultImplementationPrompt()
@Test void 본문보다_먼저_치환_끝은_DEPLOY_ENV()          // planSection 속 "{task_type_section}" 글자는 남음
@Test void 옛_페이로드는_taskType_null()
@Test void 세션이_쓴_스크립트는_커밋전에_강제추가()    // 파일 있음 → InOrder(forceAdd(wt,"db/symphony/task-42.sql"), commitAndPush); 없으면 forceAdd never
@Test void wip경로도_세션_스크립트를_강제추가()        // claude 시간 초과 → InOrder(forceAdd, gitOps.preservePartialWork)
@Test void 무시된_파일도_forceAdd면_커밋과_wip에_들어간다()   // GitOpsServicePushTest, .gitignore "*.sql\ndb/": forceAdd → commitAndPush / preservePartialWork 각각 git show --name-only HEAD에 경로
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.*TaskType*" --tests com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopPlanSectionTest --tests com.hamonsoft.netismaker.workerdaemon.GitOpsServicePushTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - **템플릿:** 두 템플릿 모두 `{plan_section}` 다음 줄에 `{task_type_section}`을 둔다. 치환은 `{commit_rule}` 바로 뒤에 한다(`:956`).
  - **PR 제목:** 앞머리는 호출부(`:462`)에서 붙인다. 생성기 시험(`GitHubPrCreatorTest.java:60`, `GitLabMrCreatorTest.java:70`)은 그대로 통과한다.
  - **설계와 다름 ①:** 설계는 `WorkerTaskResponse` 최상위에 `taskType`을 두지만, 여기서는 `ImplementationSpec`에 둔다. 구현 claim에서만 쓰는 값이고, 32칸 정규 생성자와 호환 생성자 6개를 바꾸는 연쇄를 피하려는 것이다(`WorkerTaskResponse.java:25-207`).
  - **설계와 다름 ②:** Part A에는 쓰기 도구가 없다. 그래서 DB 작업 지시는 §6.6의 "쓰기를 못 쓸 때" 문구를 쓴다.
  - 세션이 쓴 파일의 강제 추가를 Part A에 둔다. `git status --porcelain`(`GitOpsService.java:51-54`)·`git add -A`(`:141-143`)는 무시된 파일을 보지 않는다. 그래서 강제 추가가 없으면 Part A만 배포했을 때 무시 규칙이 있는 레포의 DB 작업이 "nothing to commit"(`:76-78`)으로 실패한다.
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.*" --tests "com.hamonsoft.netismaker.dto.WorkerTaskResponse*"` PASS
- [ ] **Step 5: 커밋** — `feat(type): 구현 타입 절과 커밋·PR 제목 앞머리`

---

### Task 6: 프론트 — 타입 선택·칩·필터

**Files:**
- Create: `F/composables/taskType.ts` + `.spec.ts`
- Modify: `F/pages/tasks/index.vue`
  - 상태·데이터: `:30-51`, `:63-76`, `:233-240`, `:402-415`, `:493-512`
  - 템플릿: `:570-601`(상태 select 뒤), `:661-672`(카드 칩), `:786-931`(등록 창 맨 앞)
- Modify: `F/components/tasks/TaskListMobile.vue:24-54,94-162`, `TaskCardCompact.vue:15-31,91-96`, `TaskDetailMobile.vue:14-43,123-136`, `F/pages/tasks/[id].vue:115-157,717-737`
- Modify: `F/components/ApproveDialog.vue:161,212-237`, `F/components/tasks/ApproveTaskSummary.vue:10-25`, `F/scripts/hydration-smoke.mjs:54-91`(task 1 `taskType:'NEW_FEATURE'`), `F/test/tasks-form-attachments.spec.ts:46-55`(`fillDraft`)
- Test: `F/test/tasks-form-task-type.spec.ts`, `F/test/tasks-index-type-filter.spec.ts`, `F/test/task-detail-type-chip.spec.ts`(`task-detail-mobile.spec.ts` 하네스), `F/components/ApproveDialog.spec.ts`·`F/components/tasks/TaskCardCompact.spec.ts`(추가)

**Interfaces:**
- Consumes: Task 1~3의 `taskType`·`type`(`NONE`)
- Produces(`taskType.ts`):
  - `TaskTypeValue`
  - `TASK_TYPES: {value,label}[]`
  - `taskTypeLabel(v): string` — null이면 `'타입 없음'`, 모르는 값이면 그 값 그대로
  - `TASK_TYPE_FILTERS` — `null`='전체 타입', 네 타입, `'NONE'`='타입 없음'
  - `matchesTypeFilter(task: {taskType?: string|null}, filter: string|null): boolean`
- data-test: `form-task-type`, `task-filter-type`, `mobile-type-filter`, `approve-task-type`, `task-type-chip`(타입 없음이면 칩 없음)

- [ ] **Step 1: 실패하는 시험 작성**

```ts
it('타입을 고르기 전에는 등록 버튼이 막힌다')
it('등록 바디에 taskType', () => expect(postBody()).toEqual({ repoCatalogId: 1, githubBranch: 'main', title: 't', description: 'd', taskType: 'BUG_FIX' }))
it('fromQuestion·fromTask 미리 채움은 타입을 비워 둔다')
it('데스크톱 타입 필터는 type 파라미터', () => expect(useApiMock).toHaveBeenCalledWith('/api/tasks', expect.objectContaining({ params: expect.objectContaining({ type: 'NONE' }) })))
it('모바일은 type을 보내지 않고 화면에서 거른다')
it('승인 다이얼로그는 현재 타입을 미리 고르고 바꿨을 때만 바디에')   // 그대로면 키 없음(ApproveDialog.spec.ts:150 유지)
it('카드 칩 라벨, 타입 없음이면 칩 없음')
it('상세 칩: 1280px([id].vue)·390px(TaskDetailMobile) 모두 task-type-chip 텍스트 "버그 픽스", taskType null이면 칩 없음')   // task-detail-type-chip.spec
// taskType.spec: matchesTypeFilter({taskType:null},'NONE'), ({},'NONE'), ({taskType:'BUG_FIX'},null) 모두 true
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run composables/taskType.spec.ts test/tasks-form-task-type.spec.ts test/tasks-index-type-filter.spec.ts test/task-detail-type-chip.spec.ts components/ApproveDialog.spec.ts components/tasks/TaskCardCompact.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - 바디: 조건부 스프레드로 싣는다(`index.vue:509`).
  - 등록 창: 타입 select를 맨 앞에 둔다. `branch-select` → 참조 → 제목 순서를 유지해야 한다(`tasks-form-refs.spec.ts:215-216`).
  - 목록 필터: 상태 select 뒤에 둔다. `tasks-index-mobile.spec.ts:236`이 첫 `QSelect`를 상태 select로 잡는다.
  - 배지 조회(`F/layouts/default.vue:40-42`)에는 type을 넣지 않는다.
  - **설계와 다름 ①:** 모바일 타입 필터는 서버에 보내지 않고 화면에서 거른다. 리뷰 파인딩 11(`index.vue:66-75`)과 같은 처리다.
  - **설계와 다름 ②:** 설계는 (null)의 화면 이름을 '타입 없음'으로 두고 네 곳에 칩을 단다고 했다(설계 §4.1·§4.3). 여기서는 타입 없음이면 칩을 그리지 않는다. 기존 작업 전부에 같은 '타입 없음' 칩이 붙어 카드가 덮이지 않게 하려는 것이다. '타입 없음'이라는 이름은 필터 select(`TASK_TYPE_FILTERS`)와 승인 이력(`labelOf`)에서 쓴다.
- [ ] **Step 4: 통과 확인** — `npx vitest run` 전체 PASS
- [ ] **Step 5: 커밋** — `feat(type): 등록·승인 타입 선택과 목록 칩·필터`

---

# Part B — 구현 단계 개발 DB 쓰기 (§5.1·§5.2의 DB 부분, §6.1~§6.8)

이 Part는 `DB_WRITE_ENABLED`를 켜지 않은 채로 배포된다. 스위치가 없으면 `execute` 도구와 쓰기 안내가 어디에도 나타나지 않는다. 옛 워커와 옛 인터뷰 서비스는 새 칸을 무시한다.

### Task 7: SQL 쓰기 분석기·분류기 (순수)

**Files:**
- Create: `M/entity/DbObjectType.java`
- Create: `M/dbmcp/` 아래 `SqlWriteTokenizer`(package-private), `SqlWriteParser`, `SqlWriteRejected`, `WriteStatement`, `WriteKind`, `AlterAction`, `DbObjectRef`, `DbObjectName`, `Ownership`, `SqlWriteClassifier`, `WriteDecision`
- Modify: `M/dbmcp/SqlReadOnlyChecker.java:22-72`
  - 금지 함수 판정을 package-private `static boolean isForbiddenFunction(String lowerWord)`로 꺼낸다.
  - `check` 규칙은 바꾸지 않으므로 TS 쪽은 그대로 둔다.
- Test: `T/dbmcp/SqlWriteClassifierTableTest.java` + `src/test/resources/dbmcp/sql-write-cases.txt`, `SqlWriteClassifierReasonTest.java`, `DbObjectNameTest.java`

**Interfaces:**
- Produces:
  - `enum DbObjectType { TABLE, VIEW, SEQUENCE }`
  - `record DbObjectRef(String schema, boolean schemaQuoted, String name, boolean nameQuoted)`
  - `record DbObjectName(String schema, String name)` + `static normalize(SqlDialect d, DbObjectRef r, String defaultSchema, boolean mysqlLowerCase)`
    - 비인용 이름: PG는 소문자, Oracle은 대문자, MySQL은 `mysqlLowerCase`일 때만 소문자
    - 인용 이름은 그대로 둔다.
    - 스키마를 생략하면 `defaultSchema`를 쓴다.
  - `enum WriteKind { CREATE_TABLE, CREATE_INDEX, CREATE_UNIQUE_INDEX, CREATE_SEQUENCE, CREATE_VIEW, COMMENT_ON, ALTER_TABLE, RENAME_TABLE, DROP_TABLE, DROP_VIEW, DROP_SEQUENCE, TRUNCATE, INSERT, UPDATE, DELETE, CREATE_TRIGGER, CREATE_ROUTINE }`
  - `enum AlterAction { ADD_COLUMN, ADD_COLUMN_NOT_NULL_NO_DEFAULT, ADD_COLUMN_WITH_CONSTRAINT, DROP_COLUMN, RENAME_COLUMN, ALTER_COLUMN, ADD_CONSTRAINT, DROP_CONSTRAINT, RENAME_TABLE }`
  - `record WriteStatement(WriteKind kind, List<DbObjectRef> targets, List<AlterAction> actions, boolean ifNotExists, boolean orReplace, boolean hasWhere, DbObjectRef renameTo, String executableSql)`
    - `executableSql`은 끝 `;` 하나를 뗀 문장이다. 트리거·루틴은 원문 그대로 둔다.
  - `SqlWriteRejected extends Exception` — 메시지가 한국어 사유다.
  - `static WriteStatement SqlWriteParser.parse(String sql, SqlDialect d) throws SqlWriteRejected`
  - 소유 정보
    - `record Ownership(Set<OwnedObject> owned, String dependentNote)` — `dependentNote`가 있으면 소유가 없는 것으로 본다.
    - `record OwnedObject(DbObjectType type, DbObjectName name)`
    - `static Ownership none()`
  - 판정 결과: `record WriteDecision(Verdict verdict, String reason, Integer rowCap)`, `enum Verdict { EXECUTE, DEFER, REJECT }`
  - `SqlWriteClassifier`
    - `static WriteDecision classify(WriteStatement, Function<DbObjectRef,DbObjectName> names, Ownership, int maxUpdateDeleteRows, int maxInsertRows)`
    - `static WriteDecision decide(String sql, SqlDialect, Function<…> names, Ownership, int, int)` — parse 거부는 REJECT로 돌려준다.

- [ ] **Step 1: 실패하는 시험 작성** — `SqlWriteClassifierTableTest`(`@ParameterizedTest @MethodSource`)는 아래 표 파일을 읽는다.
  - 줄 형식: `방언 ¦ 소유 ¦ 기대 ¦ SQL`
  - 방언: `PG`, `MY`(MySQL·MariaDB), `OR`. `ALL`은 세 방언으로 펼친다.
  - 소유: 비인용 이름이다. `v_`로 시작하면 VIEW, 그 밖은 TABLE.
  - 기대: `EXECUTE[/rowCap]`, `DEFER`, `REJECT`
  - 기본 스키마: PG `public`, MY `app`(lowerCase), OR `APP`
  - 실패 메시지는 `.as(방언+" | "+sql)`로 단다.

```text
# 바로 실행 — CREATE TABLE·INDEX·SEQUENCE·VIEW(새 것)
ALL ¦ - ¦ EXECUTE ¦ CREATE TABLE t_new (id INT)
PG ¦ - ¦ EXECUTE ¦ CREATE TABLE IF NOT EXISTS t_new (id int)
OR ¦ - ¦ EXECUTE ¦ CREATE TABLE t_new (id NUMBER(10) PRIMARY KEY);
ALL ¦ - ¦ EXECUTE ¦ CREATE INDEX ix_legacy_a ON legacy (a)
ALL ¦ - ¦ EXECUTE ¦ CREATE SEQUENCE seq_new
ALL ¦ - ¦ EXECUTE ¦ CREATE VIEW v_new AS SELECT a FROM legacy
# 바로 실행 — COMMENT ON (MySQL에는 없는 문법 → 거부)
PG ¦ - ¦ EXECUTE ¦ COMMENT ON TABLE legacy IS '설명'
OR ¦ - ¦ EXECUTE ¦ COMMENT ON COLUMN legacy.a IS '설명'
MY ¦ - ¦ REJECT ¦ COMMENT ON TABLE legacy IS '설명'
# 바로 실행 — 남의 테이블 ADD COLUMN(NULL 허용 또는 DEFAULT)
PG ¦ - ¦ EXECUTE ¦ ALTER TABLE legacy ADD COLUMN c text
PG ¦ - ¦ EXECUTE ¦ ALTER TABLE legacy ADD COLUMN c int NOT NULL DEFAULT 0
MY ¦ - ¦ EXECUTE ¦ ALTER TABLE legacy ADD c VARCHAR(10) NULL
MY ¦ - ¦ EXECUTE ¦ ALTER TABLE legacy ADD COLUMN c INT NOT NULL DEFAULT 0
OR ¦ - ¦ EXECUTE ¦ ALTER TABLE legacy ADD (c VARCHAR2(10))
OR ¦ - ¦ EXECUTE ¦ ALTER TABLE legacy ADD c NUMBER DEFAULT 0 NOT NULL
# 바로 실행 — INSERT(남의 테이블 10000행) · WHERE 있는 UPDATE/DELETE(1000행)
ALL ¦ - ¦ EXECUTE/10000 ¦ INSERT INTO legacy (a) VALUES ('[테스트] 장비')
ALL ¦ - ¦ EXECUTE/10000 ¦ INSERT INTO legacy (a) SELECT a FROM other_t
ALL ¦ - ¦ EXECUTE/10000 ¦ INSERT INTO legacy (a) VALUES ('x');
PG ¦ - ¦ EXECUTE/10000 ¦ INSERT INTO legacy (id, a) VALUES (nextval('seq_new'), 'x')
ALL ¦ - ¦ EXECUTE/1000 ¦ UPDATE legacy SET a = 'x' WHERE id = 3
ALL ¦ - ¦ EXECUTE/1000 ¦ DELETE FROM legacy WHERE id = 3
# 바로 실행 — 그 작업의 테이블: DROP·TRUNCATE·ALTER 전부·조건 없는 UPDATE/DELETE(상한 없음)
ALL ¦ t_new ¦ EXECUTE ¦ DROP TABLE t_new
PG ¦ t_new ¦ EXECUTE ¦ TRUNCATE t_new
MY ¦ t_new ¦ EXECUTE ¦ TRUNCATE TABLE t_new
OR ¦ t_new ¦ EXECUTE ¦ TRUNCATE TABLE t_new
ALL ¦ t_new ¦ EXECUTE ¦ ALTER TABLE t_new DROP COLUMN a
PG ¦ t_new ¦ EXECUTE ¦ ALTER TABLE t_new ALTER COLUMN a TYPE bigint
MY ¦ t_new ¦ EXECUTE ¦ ALTER TABLE t_new CHANGE a b BIGINT
OR ¦ t_new ¦ EXECUTE ¦ ALTER TABLE t_new MODIFY (a NUMBER(12))
ALL ¦ t_new ¦ EXECUTE ¦ ALTER TABLE t_new ADD CONSTRAINT uq_t_new_a UNIQUE (a)
ALL ¦ t_new ¦ EXECUTE ¦ CREATE UNIQUE INDEX ux_t_new_a ON t_new (a)
ALL ¦ t_new ¦ EXECUTE ¦ UPDATE t_new SET a = 'x'
ALL ¦ t_new ¦ EXECUTE ¦ DELETE FROM t_new
ALL ¦ t_new ¦ EXECUTE ¦ ALTER TABLE t_new RENAME TO t_new2
PG ¦ v_new ¦ EXECUTE ¦ CREATE OR REPLACE VIEW v_new AS SELECT a FROM legacy
# 승인 대기 — 남의 테이블 DROP COLUMN · RENAME COLUMN
ALL ¦ - ¦ DEFER ¦ ALTER TABLE legacy DROP COLUMN a
MY ¦ - ¦ DEFER ¦ ALTER TABLE legacy DROP a
OR ¦ - ¦ DEFER ¦ ALTER TABLE legacy DROP (a)
ALL ¦ - ¦ DEFER ¦ ALTER TABLE legacy RENAME COLUMN a TO b
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy RENAME a TO b
MY ¦ - ¦ DEFER ¦ ALTER TABLE legacy CHANGE a b INT
# 승인 대기 — 컬럼 타입·NULL 여부 변경
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy ALTER COLUMN a TYPE bigint
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy ALTER COLUMN a SET NOT NULL
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy ALTER a DROP NOT NULL
MY ¦ - ¦ DEFER ¦ ALTER TABLE legacy MODIFY a BIGINT NOT NULL
MY ¦ - ¦ DEFER ¦ ALTER TABLE legacy MODIFY COLUMN a BIGINT
OR ¦ - ¦ DEFER ¦ ALTER TABLE legacy MODIFY (a NUMBER(12))
OR ¦ - ¦ DEFER ¦ ALTER TABLE legacy MODIFY a NOT NULL
# 승인 대기 — NOT NULL이면서 DEFAULT 없는 ADD COLUMN
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD COLUMN c int NOT NULL
MY ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD COLUMN c INT NOT NULL
OR ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD c NUMBER NOT NULL
# 승인 대기 — 제약 추가(ADD COLUMN 인라인 UNIQUE·REFERENCES·CHECK 포함)
ALL ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD CONSTRAINT uq_legacy_a UNIQUE (a)
ALL ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD CONSTRAINT fk_legacy_b FOREIGN KEY (b) REFERENCES t2 (id)
ALL ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD CONSTRAINT ck_legacy_a CHECK (a > 0)
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD COLUMN c int UNIQUE
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD COLUMN c int REFERENCES t2 (id)
PG ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD COLUMN c int CHECK (c > 0)
MY ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD UNIQUE (a)
MY ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD c INT UNIQUE
OR ¦ - ¦ DEFER ¦ ALTER TABLE legacy ADD (c NUMBER CHECK (c > 0))
# 승인 대기 — 남의 테이블 UNIQUE INDEX · TRIGGER(자기 테이블도) · PROCEDURE/FUNCTION · 남의 뷰 OR REPLACE
ALL ¦ - ¦ DEFER ¦ CREATE UNIQUE INDEX ux_legacy_a ON legacy (a)
PG ¦ - ¦ DEFER ¦ CREATE TRIGGER tr_legacy AFTER INSERT ON legacy FOR EACH ROW EXECUTE FUNCTION f_audit()
MY ¦ - ¦ DEFER ¦ CREATE TRIGGER tr_legacy BEFORE INSERT ON legacy FOR EACH ROW SET NEW.a = 'x'
OR ¦ t_new ¦ DEFER ¦ CREATE OR REPLACE TRIGGER tr_t BEFORE INSERT ON t_new FOR EACH ROW BEGIN NULL; END;
PG ¦ - ¦ DEFER ¦ CREATE OR REPLACE FUNCTION f_x() RETURNS int AS $$ SELECT 1; $$ LANGUAGE sql
MY ¦ - ¦ DEFER ¦ CREATE PROCEDURE p_x() BEGIN UPDATE legacy SET a = 1; END
OR ¦ - ¦ DEFER ¦ CREATE OR REPLACE PROCEDURE p_x AS BEGIN NULL; END;
ALL ¦ - ¦ DEFER ¦ CREATE OR REPLACE VIEW v_legacy AS SELECT a FROM legacy
# 거부 — 남의 테이블 DROP·TRUNCATE·테이블 이름 변경
ALL ¦ - ¦ REJECT ¦ DROP TABLE legacy
ALL ¦ - ¦ REJECT ¦ DROP TABLE IF EXISTS legacy
PG ¦ t_new ¦ REJECT ¦ DROP TABLE t_new, legacy
OR ¦ - ¦ REJECT ¦ DROP TABLE legacy CASCADE CONSTRAINTS
ALL ¦ - ¦ REJECT ¦ DROP VIEW v_legacy
PG ¦ - ¦ REJECT ¦ TRUNCATE legacy
MY ¦ - ¦ REJECT ¦ TRUNCATE TABLE legacy
OR ¦ - ¦ REJECT ¦ TRUNCATE TABLE legacy
ALL ¦ - ¦ REJECT ¦ ALTER TABLE legacy RENAME TO legacy2
MY ¦ - ¦ REJECT ¦ RENAME TABLE legacy TO legacy2
OR ¦ - ¦ REJECT ¦ RENAME legacy TO legacy2
# 거부 — 남의 테이블 WHERE 없는 UPDATE/DELETE
ALL ¦ - ¦ REJECT ¦ UPDATE legacy SET a = 'x'
ALL ¦ - ¦ REJECT ¦ DELETE FROM legacy
# 거부 — GRANT/REVOKE · 사용자·역할 · DROP DATABASE/SCHEMA
ALL ¦ - ¦ REJECT ¦ GRANT SELECT ON legacy TO bob
ALL ¦ - ¦ REJECT ¦ REVOKE SELECT ON legacy FROM bob
ALL ¦ - ¦ REJECT ¦ CREATE USER bob IDENTIFIED BY x
ALL ¦ - ¦ REJECT ¦ ALTER USER bob IDENTIFIED BY y
ALL ¦ - ¦ REJECT ¦ DROP ROLE r_app
PG ¦ - ¦ REJECT ¦ CREATE ROLE r_app
PG ¦ - ¦ REJECT ¦ DROP SCHEMA app CASCADE
MY ¦ - ¦ REJECT ¦ DROP DATABASE app
# 거부 — ALTER SYSTEM·세션·권한 변경
PG ¦ - ¦ REJECT ¦ ALTER SYSTEM SET work_mem = '1GB'
PG ¦ - ¦ REJECT ¦ SET ROLE postgres
PG ¦ - ¦ REJECT ¦ SET search_path TO other
MY ¦ - ¦ REJECT ¦ SET GLOBAL max_connections = 1
OR ¦ - ¦ REJECT ¦ ALTER SESSION SET CURRENT_SCHEMA = other
OR ¦ - ¦ REJECT ¦ ALTER SYSTEM SET processes = 10
# 거부 — 여러 문장 · 분석 불가
ALL ¦ - ¦ REJECT ¦ INSERT INTO legacy (a) VALUES ('x'); DROP TABLE legacy
ALL ¦ - ¦ REJECT ¦ SELECT * FROM legacy
ALL ¦ - ¦ REJECT ¦ MERGE INTO legacy l USING t2 s ON (l.id = s.id) WHEN MATCHED THEN UPDATE SET a = s.a
ALL ¦ - ¦ REJECT ¦ CALL p_x()
PG ¦ t_new ¦ REJECT ¦ WITH d AS (DELETE FROM legacy RETURNING *) INSERT INTO t_new SELECT * FROM d
PG ¦ - ¦ REJECT ¦ DO $$ BEGIN DELETE FROM legacy; END $$
PG ¦ - ¦ REJECT ¦ INSERT INTO legacy (a) SELECT pg_terminate_backend(1)
MY ¦ - ¦ REJECT ¦ UPDATE legacy l JOIN t2 s ON l.id = s.id SET l.a = s.a WHERE s.id = 1
MY ¦ - ¦ REJECT ¦ /*!50000 DROP TABLE legacy */ INSERT INTO t_new VALUES (1)
MY ¦ - ¦ REJECT ¦ INSERT INTO legacy (a) VALUES ('a\'); DROP TABLE legacy; --')
OR ¦ - ¦ REJECT ¦ INSERT INTO legacy (a) VALUES (q'[x]')
# Review Focus 4 — 따옴표·대소문자·스키마
PG ¦ t_new ¦ EXECUTE ¦ DROP TABLE T_NEW
PG ¦ t_new ¦ EXECUTE ¦ DROP TABLE public."t_new"
PG ¦ t_new ¦ REJECT ¦ DROP TABLE "T_New"
PG ¦ t_new ¦ REJECT ¦ DROP TABLE other_schema.t_new
OR ¦ t_new ¦ EXECUTE ¦ DROP TABLE "T_NEW"
OR ¦ t_new ¦ REJECT ¦ DROP TABLE "t_new"
MY ¦ t_new ¦ EXECUTE ¦ DROP TABLE `t_new`
```

```java
@Test void 남의_컬럼삭제_사유() { assertThat(decide("ALTER TABLE legacy DROP COLUMN a", POSTGRES, none()).reason()).isEqualTo("남의 테이블 컬럼 삭제는 승인 대기로 기록됨 — 옛 컬럼은 두고 새 컬럼을 쓰세요"); }
@Test void 남의_테이블_DROP_사유()   // "남의 테이블은 지울 수 없습니다(DROP) — 그 작업이 만든 테이블만 지울 수 있습니다"
@Test void 의존작업이면_소유예외_꺼짐()   // owned t_new + dependentNote "작업 #25" → DROP TABLE t_new REJECT, DROP COLUMN DEFER, reason contains "작업 #25"
@Test void 빈_문장과_10만자_초과는_거부()
@Test void 여러_ALTER_동작은_가장_엄한_분류()   // ADD COLUMN c text, DROP COLUMN a → DEFER
@Test void MySQL_대소문자_구분_서버는_그대로()   // normalize(MYSQL, ref("Foo"), "app", false).name()=="Foo"
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.dbmcp.SqlWriteClassifier*" --tests com.hamonsoft.netismaker.dbmcp.DbObjectNameTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - **분석기:** JSqlParser 대신 자체 토큰 분석을 쓴다(§10 빌더 몫). 의존성에 SQL 파서가 없고(`build.gradle:36-74`), 화이트리스트 문법만 받고 나머지를 거부해야 fail closed가 된다.
  - **토크나이저**
    - 토큰 종류: 단어, 인용 식별자(인용 여부 보존), 문자열, 숫자, 기호, PG 달러 본문.
    - 주석은 버린다.
    - 거부 규칙은 읽기 스캐너(`SqlReadOnlyChecker.java:119-201`)와 같다: MySQL `/*!`, Oracle `q'`, 백슬래시 문자열, 닫히지 않은 따옴표.
  - **문장 단위 규칙**
    - `CREATE [OR REPLACE] [DEFINER = …] TRIGGER|PROCEDURE|FUNCTION|PACKAGE` 머리는 본문을 분석하지 않는다.
    - 그 밖의 문장에서 끝이 아닌 최상위 `;`가 있으면 거부한다.
    - 다음은 분석 불가로 거부한다: `WITH`·`MERGE`·`CALL`·`DO`·`SELECT`·`SET`·`RESET`으로 시작하는 문장, 다중 테이블 UPDATE/DELETE.
    - `isForbiddenFunction`에 걸리면 거부한다. 단 `nextval`·`setval`은 허용한다.
  - **ALTER TABLE 동작 분해**
    - 최상위 쉼표로 동작을 나눈다. Oracle `ADD/DROP/MODIFY (…)`는 괄호 안 항목마다 따로 본다.
    - `ADD [COLUMN] 열정의`:
      - 인라인 `UNIQUE|REFERENCES|CHECK|PRIMARY KEY|CONSTRAINT`가 있으면 `ADD_COLUMN_WITH_CONSTRAINT`
      - 없고 `NOT NULL`이면서 `DEFAULT`가 없으면 `ADD_COLUMN_NOT_NULL_NO_DEFAULT`
      - 그 밖은 `ADD_COLUMN`
    - `ADD CONSTRAINT|UNIQUE|PRIMARY KEY|FOREIGN KEY|CHECK` → `ADD_CONSTRAINT`
    - `DROP [COLUMN]` → `DROP_COLUMN`
    - `DROP CONSTRAINT|INDEX|KEY|PRIMARY KEY|FOREIGN KEY` → `DROP_CONSTRAINT`
    - `RENAME COLUMN a TO b`(PG는 `RENAME a TO b`도) → `RENAME_COLUMN`
    - `RENAME [TO|AS] x` → `RENAME_TABLE`
    - PG `ALTER [COLUMN]`, MySQL `MODIFY`·`CHANGE`·`ALTER … SET DEFAULT`, Oracle `MODIFY` → `ALTER_COLUMN`
    - 그 밖은 `분석할 수 없는 ALTER TABLE 동작: {단어}`로 거부한다. MySQL `ADD INDEX`는 "CREATE INDEX를 쓰세요"로 거부한다.
  - **동작이 여럿이면** 가장 엄한 결과를 쓴다.
  - **설계와 다름:**
    - 트리거는 자기 테이블에 걸어도 승인 대기다(본문이 남의 테이블을 바꿀 수 있다).
    - 소유하지 않은 `OR REPLACE VIEW`는 새 뷰여도 승인 대기다(사유: "새 뷰면 CREATE VIEW를 쓰세요").
    - 남의 테이블에서 제약을 지우거나 기본값을 바꾸면 승인 대기다.
    - MySQL의 `COMMENT ON`은 거부한다.
    - MariaDB는 MYSQL 방언으로 처리한다(`SqlDialect.java:13`).
- [ ] **Step 4: 통과 확인** — Step 2 명령과 `SqlReadOnlyCheckerTest` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 쓰기 SQL 분석·분류기(방언별 분류표)`

---

### Task 8: 쓰기 데이터 기초와 연결 쓰기 허용

**Files:**
- Modify: `R/db/migration/V39__task_type_db_write.sql`(덧붙임) 또는 Create: `R/db/migration/V{n}__db_write.sql`(Global Constraints "마이그레이션"이 고른다). 아래 "V39 덧붙임"은 그 파일을 말한다.
- Modify: `R/application.yml:163` 근처, `M/NetisMakerApplication.java:15`
- Create: `M/entity/WriteClassification.java`·`TaskDbObject.java`·`TaskDbWriteLog.java`, `M/repository/TaskDbObjectRepository.java`·`TaskDbWriteLogRepository.java`, `M/dbmcp/DbWriteProperties.java`
- Modify: `M/entity/DbConnection.java:17-90`, `RepoCatalogEntry.java:89-92`, `TaskDbMcpToken.java:11-46`
- Modify: `M/service/DbConnectionService.java:42-103,176-204`, `M/dto/DbConnectionDto.java:28-37`, `M/controller/DbConnectionController.java:31-59`, `T/service/DbConnectionServiceTest.java:38,86,181,216`(생성자)
- Test: `T/service/DbConnectionServiceAllowWriteTest.java`, `T/controller/DbConnectionAllowWriteSecurityTest.java`, `T/repository/DbWriteSchemaIntegrationTest.java`(CI)

**Interfaces:**
- Consumes: Task 7 `DbObjectType`
- Produces:
  - V39 덧붙임 — 기존 테이블 칸
    - `db_connection.allow_write BOOLEAN NOT NULL DEFAULT false` + `ck_db_connection_allow_write_repo CHECK (allow_write = false OR scope = 'REPO')`
    - `repo_catalog.db_script_dir VARCHAR(255)`, `db_migration_managed BOOLEAN NOT NULL DEFAULT false`
    - `task_db_mcp_token.write_granted BOOLEAN NOT NULL DEFAULT false`
  - V39 덧붙임 — `task_db_object`
    - `id`
    - `connection_id`(→ db_connection, ON DELETE CASCADE)
    - `schema_name`·`object_name` VARCHAR(128)
    - `object_type`(CHECK TABLE·VIEW·SEQUENCE)
    - `task_id`(→ task, CASCADE)
    - `created_at`
    - `UNIQUE(connection_id, schema_name, object_name, object_type)`, 인덱스 `(connection_id)`
  - V39 덧붙임 — `task_db_write_log`
    - `id`
    - `task_id`(CASCADE)
    - `connection_id`(ON DELETE SET NULL)
    - `connection_name VARCHAR(200) NOT NULL`
    - `attempt INT NOT NULL`, `fix_request_id BIGINT`
    - `sql_text TEXT NOT NULL`
    - `classification VARCHAR(20)`(CHECK 5개), `reason`, `affected_rows BIGINT`, `error`
    - `created_at`, `executed_at`, `executed_by VARCHAR(100)`
    - 인덱스 `(task_id, created_at)`
  - `enum WriteClassification { EXECUTED, DEFERRED, DISMISSED, REJECTED, FAILED }`
  - `TaskDbWriteLogRepository`
    - `findByTaskIdOrderByCreatedAtAscIdAsc(Long)`
    - `existsByTaskIdAndClassificationIn(Long, Collection<WriteClassification>)`
    - `@Modifying int transition(Long id, Long taskId, WriteClassification from, WriteClassification to, String executedBy)`
  - `TaskDbObjectRepository`
    - `findByConnectionIdAndTaskId`
    - `findByConnectionIdAndSchemaNameAndObjectNameAndObjectType`
    - `deleteByConnectionId(Long)`
  - `@ConfigurationProperties("app.db-write") record DbWriteProperties(boolean enabled, @DefaultValue("1000") int maxUpdateDeleteRows, @DefaultValue("10000") int maxInsertRows)`
  - 엔티티 칸: `DbConnection.allowWrite`, `RepoCatalogEntry.dbScriptDir`·`dbMigrationManaged`, `TaskDbMcpToken.writeGranted`(각 getter·setter)
  - `DbConnectionService`
    - 생성자: `(…, RepoCatalogRepository, TaskDbObjectRepository, DbWriteProperties)`
    - `setAllowWrite(Long id, boolean allow)` — USER 연결이면 400 `개인 연결에는 쓰기 허용을 켤 수 없습니다`
    - `static boolean sameTarget(DbConnection, UpsertRequest)` — strip 뒤 dbType·host·port·databaseName·username 비교
    - `static String claimLabel(DbConnection)` = `name + " (" + dbType.getLabel() + ")"`
  - `DbConnectionDto`
    - `View.allowWrite`
    - `ListResponse(enabled, items, boolean writeEnabled)` + 2인자 호환. `writeEnabled` = 스위치 켜짐 && 자동 마이그레이션 아님.
    - `AllowWriteRequest(@NotNull Boolean allowWrite)`
  - `PUT /api/db-connections/{id}/allow-write`(`@PreAuthorize("hasAuthority('ROLE_ADMIN')")`) → `View`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 관리자는_REPO_연결의_쓰기허용을_켠다()   // USER → 400
@Test void 접속대상이_바뀌면_쓰기허용을_끄고_소유기록을_지운다()   // verify(dbObjects).deleteByConnectionId(7L)
@Test void 이름만_바꾸거나_같은값_재전송이나_공백만_다르면_유지()  // (Review Focus 1) allowWrite true, deleteByConnectionId never
@Test void 일반_등록_바디로는_켤_수_없다()
@Test void 목록_writeEnabled는_스위치와_자동마이그레이션()
@Test void allow_write는_관리자_전용()             // 리플렉션 @PreAuthorize(RepoCatalogSecuritySurfaceTest 방식)
// (CI) USER allow_write=true → ck_db_connection_allow_write_repo 거절; 연결 삭제 → log.connection_id NULL, connection_name 유지
// (CI, Review Focus 2) DEFERRED 행(task 42) → transition(id,42,DEFERRED,EXECUTED,"a")==1 → 같은 호출 다시 ==0
//      → taskId 43으로 ==0(IDOR) → 이제 EXECUTED인 행에 transition(id,42,DEFERRED,DISMISSED,"a")==0
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.DbConnectionServiceAllowWriteTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 먼저 Global Constraints "마이그레이션"대로 덧붙일 파일을 정한다. `update`(`:83-97`)는 기존 값을 덮어쓰기 **전에** `sameTarget`을 계산한다.
  - **설계와 다름**
    1. 토큰 행에 `write_granted`를 둔다(§7에 없음). 조건 6을 실행 시점에 확인하려면 필요하다.
    2. 기록에 `fix_request_id`를 둔다. 수정 회차가 attempt를 같이 쓰기 때문이다(`Task.java:337-345`).
    3. 기록에 `connection_name`을 둔다. 연결을 지운 뒤에도 표시하기 위해서다.
    4. 쓰기 허용은 관리자 전용 경로로만 바꾼다. 관리 화면 토글은 일반 PUT으로 전체 바디를 보내므로 그 경로로는 바꿀 수 없게 한다(`DbConnectionAdminDialog.vue:37-47`).
    5. `writeEnabled`를 둬서 승인 다이얼로그가 쓰기 가능 여부를 알 수 있게 한다.
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.service.DbConnectionService*" --tests "com.hamonsoft.netismaker.controller.DbConnection*"` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 쓰기 기록·소유 테이블과 연결 쓰기 허용`

---

### Task 9: 레포 카탈로그 `dbScriptDir`·`dbMigrationManaged`

**Files:**
- Create: `M/util/DbScriptDir.java`
- Modify: `M/dto/RepoCatalogDto.java:32-107`, `M/service/RepoCatalogService.java:57-102,245-277`
- Test: `T/util/DbScriptDirTest.java`, `T/service/RepoCatalogServiceDbWriteTest.java`, `T/dto/RepoCatalogRequestValidationTest.java`(추가)

**Interfaces:**
- Consumes: Task 8 엔티티 칸
- Produces:
  - `DbScriptDir.DEFAULT = "db/symphony"`
  - `static String normalize(String)` — 빈 값이면 null을 돌려준다. `\`는 `/`로 바꾸고 앞뒤 `/`를 뗀다. 다음은 `IllegalArgumentException("DB 스크립트 폴더는 레포 안 상대 경로여야 합니다")`:
    - 절대경로, 드라이브 문자
    - `..`
    - 255자 초과
  - `static String resolve(String dir, long taskId)` → `{dir|DEFAULT}/task-{id}.sql`
  - `UpsertRequest` 끝에 `Boolean dbMigrationManaged, String dbScriptDir`를 더하고 12인자 호환 생성자를 둔다. `View`에도 두 칸을 더한다.
  - `RepoCatalogService.applyDbWrite(RepoCatalogEntry, UpsertRequest)`
    - null이면 유지한다.
    - `dbScriptDir`가 `""`이면 비운다.
    - normalize가 실패하면 400

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 정규화() { assertThat(DbScriptDir.normalize(" db\\sql/ ")).isEqualTo("db/sql"); assertThat(DbScriptDir.resolve(null, 42)).isEqualTo("db/symphony/task-42.sql");
    for (String bad : List.of("/abs", "C:/x", "../up", "a/../b")) assertThatThrownBy(() -> DbScriptDir.normalize(bad)).isInstanceOf(IllegalArgumentException.class); }
@Test void 활성_토글_5칸_바디는_두_칸을_유지()   // repo-catalog.vue:174-190 모양
@Test void 빈문자열은_비우고_잘못된_값은_400()
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.util.DbScriptDirTest --tests com.hamonsoft.netismaker.service.RepoCatalogServiceDbWriteTest` → 컴파일 실패
- [ ] **Step 3: 구현** — `create`·`update` 모두 `applyNoteFolder` 다음에 `applyDbWrite`를 부른다(`:69-71`, `:97-99`).
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.service.RepoCatalogService*" --tests "com.hamonsoft.netismaker.util.*" --tests com.hamonsoft.netismaker.dto.RepoCatalogRequestValidationTest` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 카탈로그 DB 스크립트 폴더·자동 마이그레이션 표시`

---

### Task 10: 쓰기 판정·토큰 쓰기 허가·TTL·grant·`supports=dbWrite`

**Files:**
- Create: `M/dbmcp/DbWriteGate.java`
- Modify: `M/dbmcp/DbMcpTokenService.java:43-84`, `M/dto/DbMcpGrant.java:9-22`, `M/dbmcp/DbMcpGrantService.java:37-63`
- Modify: `M/service/WorkerCapabilities.java:11-20`, `M/controller/WorkerController.java:48-51,73-88,113-134`
- Modify(시험 호출 맞춤): `T/controller/WorkerControllerDbMcpTest.java:37,44` — 스텁을 `grantFor(5L, false)`·`grantFor(anyLong(), anyBoolean())`로
- Test: `T/dbmcp/DbWriteGateTest.java`, `T/dbmcp/DbMcpTokenServiceTest.java`·`DbMcpGrantServiceTest.java`(추가), `T/controller/WorkerControllerDbWriteTest.java`

**Interfaces:**
- Consumes: Task 8 설정·칸
- Produces:
  - `@Component @Profile("api") DbWriteGate(DbWriteProperties, DbConnectionRepository, RepoCatalogRepository)`
    - `record Check(boolean allowed, String reason)`
    - `Check check(Task t, boolean workerSupportsWrite, DbConnection snapshot)` — 1~6 순서·문구. 조건 4는 넘겨받은 행만 본다(null이면 조건 4 거부).
    - `Check check(Task t, boolean workerSupportsWrite, long connectionId)` — 연결을 읽어 위 오버로드에 위임한다(grant·인터뷰용)
    - `List<String> writableLabels(Task t, List<? extends Number> ids)` — 조건 1·3·4·5, `claimLabel`
    - `boolean migrationManaged(Task t)`
  - `DbMcpTokenService`
    - `issue(Task t, boolean writeGranted)` — 1인자는 false
    - `Optional<TokenAuth> authenticateWithGrant(String)`
    - `record TokenAuth(Task task, boolean writeGranted)` — `authenticate`는 이것으로 위임
    - `IMPLEMENTATION_TTL_MARGIN = Duration.ofMinutes(30)` — 상태가 IMPLEMENTING이고 상한이 있으면, 만료 = `max(ttl, 상한+30분)`
  - `DbMcpGrant.Server(name, path, label, dbType, boolean writable)` + 4인자 호환(false)
  - `DbMcpGrantService.grantFor(Long taskId, boolean workerSupportsWrite)` — 1인자는 false. 생성자에 `DbWriteGate`를 받는다.
  - `record WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite)`
    - 2인자 호환(dbWrite=false)
    - `ALL`은 셋 다 true
    - `needsFilteredQueue()`는 그대로
  - `WorkerController.SUPPORTS_DB_WRITE = "dbWrite"`, `withDbMcp(WorkerTaskResponse, WorkerCapabilities)`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 조건마다_거부사유()   // 스위치 off / DESIGNING / 선택 밖 / allowWrite false / USER / disabled / migrationManaged / worker false
@Test void 인터뷰용_label은_단계와_워커를_보지_않는다()   // INTERVIEWING → ["개발DB (PostgreSQL)"]
@Test void SDD_구현토큰은_상한더하기_30분()   // Clock.fixed, timeout 240 → now+270m; DESIGNING → now+2h
@Test void 쓰기허가_왕복()                   // issue(t,true) → authenticateWithGrant().writeGranted()
@Test void grant는_연결별_writable과_토큰허가()   // 하나만 허용 → [true,false], writeGranted true; DESIGNING → 전부 false
@Test void supports에_dbWrite가_있으면_grantFor_true()   // "refRepos,fixRounds,dbWrite" → grantFor(42L,true)
@Test void dbWrite는_큐를_거르지_않는다()      // new WorkerCapabilities(true,true,false).needsFilteredQueue()==false
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.dbmcp.DbWriteGateTest --tests com.hamonsoft.netismaker.controller.WorkerControllerDbWriteTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 각 서버의 `writable`은 `workerSupportsWrite && gate.check(t, true, id).allowed()`이다. 토큰은 `issue(t, anyWritable)`로 발급한다.
  - **설계와 다름:** 조건 6은 큐 필터가 아니다. caps를 `withDbMcp`로 넘기기만 하므로 옛 워커도 작업은 받는다(`WorkerController.java:77-81`, `WorkerCapabilities.java:17-19`).
  - TTL 여유 30분은 이 계획에서 정한 값이다.
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.dbmcp.*" --tests "com.hamonsoft.netismaker.controller.WorkerController*" --tests "com.hamonsoft.netismaker.service.WorkerService*"` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 쓰기 조건 판정·토큰 쓰기 허가·구현 TTL·supports=dbWrite`

---

### Task 11: 쓰기 실행기와 MCP `execute`

**Files:**
- Create: `M/dbmcp/DbWriteExecutor.java`·`DbWriteSession.java`·`JdbcWriteExecutor.java`·`DbWriteService.java`
- Modify: `M/dbmcp/CatalogSql.java:12-20`, `DbMcpTools.java:39-52`, `M/controller/DbMcpController.java:43-127`, `M/repository/TaskRepository.java:87-97` 근처, `M/service/DbConnectionService.java:173-203` 근처
- Modify(시험 호출 맞춤): `T/controller/DbMcpControllerTest.java:36,39` — 5인자 생성자, 스텁을 `authenticateWithGrant("good")` → `Optional.of(new TokenAuth(t, false))`로
- Test: `T/dbmcp/DbWriteServiceTest.java`, `JdbcWriteExecutorTest.java`(H2 + mock Connection), `T/controller/DbMcpControllerExecuteTest.java`(`DbMcpControllerTest.java:36` 하네스), `T/dbmcp/JdbcWriteExecutorPostgresIntegrationTest.java`(CI, `@Value("${spring.datasource.url}")`)

**Interfaces:**
- Consumes: Task 7 분류기, Task 8 저장소·설정, Task 10 `check`·`TokenAuth`
- Produces:
  - `interface DbWriteExecutor { DbWriteSession open(InterviewClaimResponse.DbConnectionRef) throws SQLException; }`
  - `interface DbWriteSession extends AutoCloseable`
    - `defaultSchema()`, `lowerCaseNames()`(MySQL `@@lower_case_table_names`≠0)
    - `exists(DbObjectName)`, `long executeUpdate(String)`(30초), `commit()`
    - `close()` — 커밋하지 않았으면 rollback을 close보다 먼저 한다.
  - `JdbcWriteExecutor`(`@Profile("api")`)
    - package-private `(ConnectionOpener)` 생성자
    - 연결 설정: autoCommit·readOnly 둘 다 false
    - `JdbcReadOnlyQueryExecutor.openJdbc`·`propsFor`를 재사용한다.
  - `CatalogSql.objectExists(SqlDialect, String schema, String name)` — 정확 비교로 찾는다. PG는 `pg_class`+`pg_namespace`, MySQL은 `information_schema.tables`, Oracle은 `all_objects`.
  - `DbConnectionService`(쓰기용, 판정과 접속이 같은 행을 보게)
    - `Optional<DbConnection> findForWrite(long id)` — 한 번 읽은 행
    - `Optional<InterviewClaimResponse.DbConnectionRef> writeRefOf(DbConnection snapshot)` — 그 행에서 복호화한다. 다시 읽지 않는다. 기능이 꺼졌거나 복호화 실패면 empty.
  - `DbWriteService(DbWriteGate, DbConnectionService, DbWriteExecutor, TaskDbObjectRepository, TaskDbWriteLogRepository, TaskRepository, DbWriteProperties)`
    - `record ExecuteResult(WriteClassification classification, String reason, Long affectedRows, Long logId, String error)`
    - `ExecuteResult execute(Task task, boolean tokenWriteGranted, long connectionId, String sql)`
    - `Ownership ownershipFor(Task t, long connectionId)`
    - 기록 행: `attempt`·`fix_request_id`는 작업 값, `connection_name` = `DbConnectionService.claimLabel(snapshot)`(기록 시점 값). 연결 행이 없으면 `"#" + connectionId`.
  - `List<Task> TaskRepository.findOpenByRepo(String githubRepo, Long excludeId)` — 삭제되지 않았고 CLOSED·CANCELLED가 아닌 작업
  - `DbMcpTools`
    - `toolDefinitions(String label, boolean writable)`
      - `execute` 입력: `{sql}`
      - 설명: `공용 개발 DB에 쓰기 SQL 한 문장을 실행합니다. 서버가 분류해 바로 실행·승인 대기·거부합니다.`
    - `static String formatWriteResult(ExecuteResult)` — `분류:`·`이유:`·`영향 행:`·`기록: #id`
  - `DbMcpController(DbMcpTokenService, DbConnectionService, DbMcpTools, DbWriteGate, DbWriteService)`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 바로실행은_기록을_먼저_쓰고_실행뒤_완료()   // InOrder: save(EXECUTED, executedAt null) → executeUpdate → commit → save(executedAt, affectedRows 3); attempt·fixRequestId는 작업 값
@Test void 기록_사전저장이_실패하면_실행하지_않는다()   // executeUpdate never, error "기록 저장 실패 — 실행하지 않았습니다"
@Test void 남의테이블_상한초과는_롤백후_거부()          // 1001 → commit never, REJECTED "영향 행 상한 초과: 1001 > 1000"
@Test void SQL오류는_FAILED_비밀번호없음()
@Test void 새_테이블은_소유기록()                     // exists false → objects.save(TABLE, public.t_new, 42)
@Test void 이미_있으면_실행하지_않는다()              // REJECTED startsWith "이미 있음: public.legacy"; exists 예외 → "존재 확인 실패"
@Test void 소유_OR_REPLACE_VIEW는_존재확인없이_실행()   // owned v_new, CREATE OR REPLACE VIEW v_new … → exists never, EXECUTED, objects.save never
@Test void 판정과_접속은_한번_읽은_연결행으로()        // findForWrite(7) 1회, resolveForClaim never; gate.check와 writeRefOf가 같은 행 객체(isSameAs) → executor.open(그 ref); connection_name "개발DB (PostgreSQL)"
@Test void 소유_DROP은_기록삭제_소유_RENAME은_이름변경()
@Test void 조건불충족은_기록만_남기고_접속안함()        // executor.open never, REJECTED "쓰기 허용 연결이 아닙니다"
@Test void 승인대기는_실행없이_DEFERRED()
@Test void 재시도중_옛브랜치_기준_의존작업이면_소유예외_꺼짐()   // (Review Focus 5) headBranch null; 작업 25 "netismaker/task-42-login" 같은 호스트 → DROP TABLE t_new REJECT "작업 #25"; CANCELLED·다른 호스트면 무시
@Test void 커밋없이_닫으면_rollback이_close보다_먼저()   // JdbcWriteExecutorTest: InOrder(rollback, close), setQueryTimeout(30)
@Test void 쓰기가능일때만_execute가_보이고_직접호출은_isError와_REJECTED_기록()   // DbMcpControllerExecuteTest
// (CI) PG: CREATE TABLE·commit 뒤 exists true; 상한 초과 UPDATE는 close 뒤 행 그대로
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.dbmcp.DbWriteServiceTest --tests com.hamonsoft.netismaker.dbmcp.JdbcWriteExecutorTest --tests com.hamonsoft.netismaker.controller.DbMcpControllerExecuteTest` → 컴파일 실패
- [ ] **Step 3: 구현** — `execute`는 트랜잭션 없이 다음 순서로 돈다.
  1. 길이를 1~100000자로 검사한다(`DbMcpTools.java:87-89` 문구).
  2. `findForWrite`로 연결 행을 **한 번** 읽고 `gate.check(task, tokenWriteGranted, 그 행)`
  3. `parse`
  4. `writeRefOf(같은 행)` — 결과가 없으면 FAILED `연결 정보를 풀 수 없습니다`. `resolveForClaim`은 다시 읽으므로 쓰지 않는다.
  5. `open`
  6. `ownershipFor`
  7. `classify`
  8. `orReplace`가 아닌 `CREATE TABLE`/`VIEW`/`SEQUENCE`에만 `exists` 확인과 소유 기록을 한다. 소유 `OR REPLACE VIEW`는 확인·기록 없이 실행한다.
  9. 기록 행을 먼저 저장한다.
  10. `executeUpdate` 후 상한을 검사한다.
  11. `commit`, 소유 반영, 기록 완료.
  12. `close`
  - **컨트롤러:** `tools/list`와 `execute`는 같은 판정을 쓴다. `authenticateWithGrant`로 받은 `writeGranted`가 참이고 `gate.check(task, true, id).allowed()`이면 쓰기 가능이다.
  - **의존 작업:** `findOpenByRepo` 결과 중 다음을 모두 만족하면 `dependentNote = "작업 #" + ids`다.
    - 호스트가 같다(`WorkerService.java:1008-1010`).
    - `githubBranch`가 이 작업 `headBranch`와 같거나, 마지막 경로 조각이 `task-{id}-`로 시작한다.
  - **설계와 다름 ①:** `findActiveByBaseBranch`를 쓰지 않는다. 이 메서드는 CANCELLED를 포함하고(`TaskRepository.java:93`), `head_branch`가 비는 재시도(`Task.java:321`)에서는 옛 브랜치를 못 찾는다.
  - **설계와 다름 ②:** 반목표 "실행된 SQL이 기록에서 빠짐"을 막으려고, 기록 행을 실행 **전에** EXECUTED·`executed_at` NULL로 쓴다. 이 저장이 실패하면 실행하지 않는다.
  - 판정(2)과 접속(4)을 같은 행으로 하는 이유: 따로 읽으면 그 사이 관리자가 접속 대상을 바꿨을 때, 이미 통과한 판정으로 새 대상에 쓴다(반목표 "허용 안 된 연결에 쓰기").
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.dbmcp.*" --tests "com.hamonsoft.netismaker.controller.DbMcpController*"` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 쓰기 실행기와 MCP execute 도구`

---

### Task 12: DB 변경 조회·관리자 실행·실행 안 함 API

**Files:**
- Create: `M/dto/DbChangeView.java`(API·워커 공용), `M/controller/TaskDbChangeController.java`(`TaskFixController` 관례)
- Modify: `M/dbmcp/DbWriteService.java`, `M/controller/WorkerController.java`
- Test: `T/dbmcp/DbWriteServiceDeferredTest.java`, `T/controller/TaskDbChangeControllerTest.java`, `T/controller/TaskDbChangeFlowIntegrationTest.java`(CI)

**Interfaces:**
- Consumes: Task 8 `transition`, Task 11 서비스·실행기·`findForWrite`·`writeRefOf`
- Produces:
  - `record DbChangeView(Long id, Long connectionId, String connectionName, int attempt, Long fixRequestId, String sqlText, String classification, String reason, Long affectedRows, String error, OffsetDateTime createdAt, OffsetDateTime executedAt, String executedBy)` + `of(TaskDbWriteLog)`
  - `DbWriteService`
    - 생성자 끝에 `TaskStatusHistoryRepository`를 더한다(Task 11 생성자 + 이 칸).
    - `List<DbChangeView> list(Long taskId)` — 시간순
    - `DbChangeView executeDeferred(Long taskId, Long logId, String adminId)` — 갱신된 행
    - `DbChangeView dismiss(Long taskId, Long logId, String adminId)` — 갱신된 행
  - 엔드포인트
    - `GET /api/tasks/{id}/db-changes` — `getForView` 권한
    - `POST …/db-changes/{logId}/execute`, `…/dismiss` — 관리자, 응답 본문은 `DbChangeView`
    - `GET /worker/tasks/{id}/db-changes`
  - 이력(같은 상태 행)
    - `DB 변경 실행: #{logId} ({연결 이름})`
    - `DB 변경 실행 안 함: #{logId}`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 승인대기만_실행()                // 그 밖 → 409 "승인 대기 항목만 실행할 수 있습니다"
@Test void 두번_누르면_한번만_실행()          // (Review Focus 2) transition(13,42,DEFERRED,EXECUTED,"admin")→0 → 409 "이미 처리된 항목입니다", executeUpdate never
@Test void 다시_분류해_거부면_관리자도_409()   // "거부 분류는 관리자도 실행할 수 없습니다", transition never
@Test void 쓰기허용_꺼짐_스위치_off_USER_비활성은_409()   // transition never, executor.open never(DEFERRED 그대로)
@Test void 성공하면_EXECUTED_실행자_이력()     // "DB 변경 실행: #13 (개발DB (PostgreSQL))"
@Test void 실행실패는_승인대기로_되돌리고_오류저장_409()
@Test void 실행안함은_DISMISSED와_이력()
@Test void 목록은_등록자와_관리자만()           // 남의 작업 403
// (CI) dismiss 뒤 GET에 DISMISSED; 비관리자 execute 403
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.dbmcp.DbWriteServiceDeferredTest --tests com.hamonsoft.netismaker.controller.TaskDbChangeControllerTest` → 컴파일 실패
- [ ] **Step 3: 구현** — `executeDeferred` 순서. 선점(5) 전 단계는 상태를 바꾸지 않으므로 거기서 409가 나면 되돌릴 것이 없다.
  1. 행이 이 작업의 DEFERRED인지 본다.
  2. `findForWrite`로 연결 행을 한 번 읽고, 스위치·enabled·REPO·allowWrite를 그 행으로 확인한다.
  3. `writeRefOf(같은 행)`로 연다.
  4. 다시 분류한다. 거부면 409.
  5. `transition`으로 선점한다. 0이면 409.
  6. 상한 없이 실행하고 commit한다.
  7. 실패하면 DEFERRED로 되돌리고 error를 남긴 뒤 409.
  - 세션은 항상 닫는다.
  - 병합 여부·작업 상태로는 막지 않는다(§6.5).
  - **설계와 다름:** 설계는 관리자 실행 때 쓰기 허용을 다시 확인하라고 정하지 않았다. 반목표 "허용 안 된 연결에 쓰기"를 막으려고 다시 확인한다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): DB 변경 조회와 관리자 실행·실행 안 함`

---

### Task 13: claim 재료 — 구현 `DbWriteSpec`, 인터뷰 `writableDbConnections`

**Files:**
- Modify: `M/dto/WorkerTaskResponse.java:110-115,236-275`, `M/service/WorkerService.java:118-122`(setter)·`:292-297`·`:362-373`
- Modify: `M/dto/InterviewClaimResponse.java`(컴포넌트·사본 4곳·compact 정규화), `M/service/InterviewService.java:93-107,197-222`, `M/controller/InterviewWorkerController.java:69-81`
- Modify: `I/src/types.ts`, `I/src/runner/kickoffByType.ts`, `I/src/runner/interviewRunner.ts:151-177`
- Test: `T/service/WorkerServiceDbWriteSpecTest.java`, `T/dto/WorkerTaskResponseImplementationTest.java`, `T/service/InterviewServiceTest.java`, `T/controller/InterviewWorkerControllerClaimDbTest.java`, `I/test/kickoffByType.test.ts`(추가, `writableDbLine` 순수 함수), `I/test/interviewRunner.test.ts`(추가, Task 4의 `kickoffOf`가 있는 describe 안)

**Interfaces:**
- Consumes: Task 9 `DbScriptDir.resolve`, Task 10 `writableLabels`·`migrationManaged`, Task 4 kickoff
- Produces:
  - `record WorkerTaskResponse.DbWriteSpec(String scriptPath, boolean migrationManaged, boolean priorWrites)`
  - `ImplementationSpec(…, String taskType, DbWriteSpec db)` — 5인자 호환 생성자 추가
  - `forImplementation(…, FixRoundSpec, ImplementationMode, DbWriteSpec db)` — 6인자판은 null로 위임
  - `WorkerService`
    - `DbWriteSpec dbWriteSpecFor(Task)`
    - `@Autowired setDbWriteLogRepository(…)`, `setDbWriteGate(…)` — 단위 시험에서 null이면 둘 다 false 취급
  - 인터뷰 claim
    - `InterviewClaimResponse.writableDbConnections()`(`List<String>`, null → `List.of()`) + `withWritableDbConnections(List<String>)`
    - `InterviewService.setDbWriteGate(DbWriteGate)`
  - TS
    - claim 칸 `writableDbConnections?: string[]`
    - `export function writableDbLine(names: unknown): string`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 구현claim은_스크립트경로_자동마이그레이션_이전쓰기()  // "sql/dev" → "sql/dev/task-42.sql", 없으면 "db/symphony/task-42.sql"; EXECUTED·DEFERRED 존재 → priorWrites
@Test void 옛_페이로드는_db_null()
@Test void 인터뷰claim_쓰기연결_label()            // ["개발DB (PostgreSQL)"]; 질문 세션 → []
@Test void 사본4곳이_writableDbConnections_유지()
@Test void DB조회_실패경로는_쓰기목록도_비운다()     // InterviewWorkerController 예외 경로
```
```ts
// interviewRunner.test.ts '작업 인터뷰' describe 안 (Task 4의 kickoffOf)
it('쓰기 가능 연결이 있으면 한 줄, 없으면 골든', async () => {
  expect(await kickoffOf({ ...freshClaim, writableDbConnections: [] })).toBe(KICKOFF_GOLDEN)
  expect(await kickoffOf({ ...freshClaim, writableDbConnections: ['개발DB (PostgreSQL)'] })).toContain('구현 단계에서는 다음 DB 연결의 공용 개발 DB에 변경이 직접 적용됩니다: 개발DB (PostgreSQL).') })
// kickoffByType.test.ts — writableDbLine(undefined|[]|'x') === '', writableDbLine(['a','b'])에 'a, b.'
```

- [ ] **Step 2: 실패 확인**
  - `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.WorkerServiceDbWriteSpecTest --tests com.hamonsoft.netismaker.controller.InterviewWorkerControllerClaimDbTest` → 컴파일 실패
  - `cd netismaker-interview-service; npx vitest run test/kickoffByType.test.ts test/interviewRunner.test.ts` → FAIL
- [ ] **Step 3: 구현** — `writableDbLine` 문구를 `taskTypeGuide` 바로 뒤에 넣는다.
  `구현 단계에서는 다음 DB 연결의 공용 개발 DB에 변경이 직접 적용됩니다: {이름들}. 남의 테이블 컬럼 삭제·변경 등은 승인 대기로 남습니다. DB 변경이 필요하면 계획에 DB 변경 단계를 넣으세요. 이 인터뷰에서는 읽기 전용입니다.\n\n`
  - **설계와 다름**
    1. 판정 조건에 조건 5(§6.8)와 REPO·enabled 재확인을 넣는다. 설계 §5.1은 조건 1·3·4만 적었다.
    2. 값은 이름이 아니라 claim DB 절과 같은 label을 쓴다(`DbConnectionService.java:199-201`, `interviewRunner.ts:218`).
    3. 계산은 `taskId`·`repoCatalogId`를 아는 `InterviewService.claim`에서 한다.
- [ ] **Step 4: 통과 확인** — Step 2 명령, `--tests "com.hamonsoft.netismaker.service.*Service*" --tests "com.hamonsoft.netismaker.dto.*"`, `npx vitest run; npx tsc --noEmit -p tsconfig.json` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 구현·인터뷰 claim에 DB 쓰기 재료`

---

### Task 14: 워커 — DB 절 문구·이미 적용된 SQL 절·사전 조회

**Files:**
- Modify: `M/workerdaemon/DbMcpWorkerSupport.java:29-43`, `WorkerHttpClient.java:166-191` 근처, `ImplementationScratch.java:21-95`(`DB_NOTES`), `ImplementationPrompts.java`(`manualScriptPath`·`sessionScriptPath`)
- Modify: `M/workerdaemon/FixRoundPrompts.java:45-118` — `:116`을 `promptSection(task.dbMcp(), true)`로 바꾸고 그 뒤에 `dbExtra`를 붙인다.
- Modify: `M/workerdaemon/WorkerMainLoop.java`
  - `:338-399` 사전 조회·프롬프트 — `:398`을 `promptSection(task.dbMcp(), true)`로
  - `:432`·`:583` 리뷰 메모 회수 자리 — DB 메모를 붙인다
  - `:497-554` 회차
  - `:816` 디자인은 1인자 그대로 둔다
- Test: `T/workerdaemon/DbMcpWorkerSupportTest.java`·`ImplementationPromptsTaskTypeTest.java`(추가), `WorkerMainLoopDbPromptTest.java`, `WorkerHttpClientDbChangesTest.java`

**Interfaces:**
- Consumes: Task 12 `DbChangeView`·워커 GET, Task 13 `ImplementationSpec.db`, Task 10 `Server.writable`, Task 5 `taskTypeSection`
- Produces:
  - `DbMcpWorkerSupport`
    - `promptSection(DbMcpGrant, boolean allowWriteBlock)` — 1인자는 false(디자인만 1인자를 쓴다)
    - `anyWritable(DbMcpGrant)`
    - `migrationManagedSection(DbWriteSpec)` — `migrationManaged`가 거짓이면 `""`
    - `appliedSqlSection(List<DbChangeView>)` — EXECUTED·DEFERRED만, 20 000자 상한
  - `WorkerHttpClient.dbChanges(long taskId)` → `List<DbChangeView>` — 본문이 null이면 `IllegalStateException`(`requireActiveBody` 모양)
  - `FixRoundPrompts.render(…, refs, String dbExtra)` — 7인자는 `""`. `dbExtra`에는 `appliedSqlSection`과 `migrationManagedSection`만 담는다. DB 절 자체는 `render`가 2인자로 넣는다.
  - `ImplementationScratch.DB_NOTES = ".netis/db-notes.md"`, `static Optional<String> readDbNotes(Path worktree)` — 상한은 `REVIEW_NOTES_MAX_CHARS`와 같다.
  - `ImplementationPrompts`
    - `static String manualScriptPath(String scriptPath)` — 끝 `.sql`을 `-manual.sql`로 바꾼다(`db/symphony/task-42-manual.sql`).
    - `static String sessionScriptPath(DbWriteSpec db, boolean writeTools)` — 쓰기 도구가 없고 `priorWrites`면 `manualScriptPath(db.scriptPath())`, 아니면 `db.scriptPath()`. `taskTypeSection`의 `scriptPath` 인자로 쓴다.
  - `WorkerMainLoop`
    - `static DbWriteSpec dbSpecOf(WorkerTaskResponse)` — 없으면 `(defaultScriptPath(id), false, false)`
    - `static boolean needsDbLog(WorkerTaskResponse)` = `!migrationManaged && (anyWritable || priorWrites)`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 쓰기연결이_없으면_지금_문구_그대로()   // writable=false 서버도 기존 결과와 같음
@Test void 쓰기연결_절() { String s = DbMcpWorkerSupport.promptSection(grantWith(writable("db-12","개발 (PostgreSQL)")), true);
    assertThat(s).contains("## 개발 DB 쓰기 (함께 쓰는 개발 DB)").contains("- mcp__db-12__query / list_tables / describe_table / execute : 개발 (PostgreSQL)")
      .contains("옛 컬럼은 두고 새 컬럼을 추가해").contains("[테스트]").contains("레포에 마이그레이션 파일을 만들지 마세요")
      .contains(".netis/db-notes.md").contains("자동 마이그레이션 레포로 보임 — 카탈로그 설정 확인 필요").doesNotContain("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다"); }
@Test void 디자인_프롬프트에는_쓰기절이_없다()
@Test void 자동마이그레이션_절() { assertThat(DbMcpWorkerSupport.migrationManagedSection(new DbWriteSpec("db/symphony/task-42.sql", true, false)))
      .contains("## DB 변경 (자동 마이그레이션 레포)").contains("레포의 마이그레이션 관례대로 파일로 만드세요").contains("가데이터도 관례가 허용하면")
      .contains("시험 컨테이너가 뜰 때 적용됩니다").contains("옛 컬럼은 두고 새 컬럼을 추가해").contains("옛 컬럼 삭제는 이번 변경에 넣지 말고 병합 뒤 별도 작업으로");
    assertThat(DbMcpWorkerSupport.migrationManagedSection(new DbWriteSpec("x", false, false))).isEmpty(); }
@Test void 이미적용된_SQL_절()                 // "## 이미 적용된 SQL (다시 실행하지 마세요)", "- [실행됨] #12 개발 (PostgreSQL): CREATE TABLE t_new", "- [승인 대기] #13"; REJECTED 제외
@Test void DB작업_타입절_세_갈래()             // (DB_WORK,true,false) "`execute` 도구로 한 문장씩"; (DB_WORK,false,true) "마이그레이션 관례대로"
@Test void 이전쓰기가_있는데_쓰기도구가_없으면_manual_경로()   // sessionScriptPath(("db/symphony/task-42.sql",false,true), false) == "db/symphony/task-42-manual.sql"; (…, true) → 원래 경로
@Test void 이전쓰기가_있으면_사전조회해_절을_넣는다()
@Test void 사전조회_실패면_claude없이_구현실패() // reason startsWith "DB 변경 기록 조회 실패", claude.exec never
@Test void 쓰기도_이전기록도_없으면_조회안함()
@Test void 자동마이그레이션_레포는_연결이_없어도_절이_붙는다()   // dbMcp null, BUG_FIX, migrationManaged true → 프롬프트에 "## DB 변경 (자동 마이그레이션 레포)"
@Test void 수정회차도_DB절과_이미적용된_SQL절()   // 쓰기 서버 → "## 개발 DB 쓰기", doesNotContain("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다")
@Test void 수정회차도_자동마이그레이션_절()
@Test void DB메모는_리뷰메모_뒤에_붙는다()       // review-notes "R" + db-notes "D" → PR 본문 "## 리뷰 메모\nR\n\nD"; db-notes만 있으면 "## 리뷰 메모\nD"
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.workerdaemon.DbMcpWorkerSupportTest --tests com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDbPromptTest` → FAIL
- [ ] **Step 3: 구현**
  - **DB 절:** 읽기 전용 서버는 지금 문구 그대로 둔다. 쓰기 서버는 새 절에 §6.7 순서대로 쓴다. 안내는 맨 끝에 둔다.
  - **붙는 순서:** DB 절 → `migrationManagedSection` → `appliedSqlSection` → 참조 절. 일반 구현과 회차(`dbExtra`) 모두 같다.
    - `migrationManagedSection`은 `db.migrationManaged`가 참이면 타입, DB 연결 선택 여부와 상관없이 붙는다.
    - 자동 마이그레이션 레포에는 쓰기 연결이 없으므로(조건 5) 옛 컬럼 기준은 이 절이 전한다. 서버가 막지 못하는 레포에서 반목표 "다른 사람 화면 깨짐"을 막는 유일한 지시다.
  - **`migrationManagedSection` 문구**(설계 §6.8):
    ```text

    ## DB 변경 (자동 마이그레이션 레포)
    이 레포는 앱이 뜰 때 마이그레이션 파일을 DB에 자동 적용합니다. DB 변경이 필요하면 레포의 마이그레이션 관례대로 파일로 만드세요. 가데이터도 관례가 허용하면 그 방식으로 넣으세요. 개발 DB에는 시험 컨테이너가 뜰 때 적용됩니다 — DB에 직접 실행하지 마세요.
    - 같은 DB를 다른 사람도 씁니다. 기존 컬럼을 지우거나 이름·타입을 바꿔야 하면 옛 컬럼은 두고 새 컬럼을 추가해 그쪽을 쓰세요. 옛 컬럼 삭제는 이번 변경에 넣지 말고 병합 뒤 별도 작업으로 남기세요.
    ```
  - **사전 조회:**
    - worktree 생성 뒤, 프롬프트 조립 전에 `needsDbLog`일 때만 한다. 참조 준비 뒤라서 `WorkerMainLoopRefReposTest.java:233-236`이 유지된다.
    - 실패하면 `safePostImplementationFailure(task, attempt, "DB 변경 기록 조회 실패: " + msg, wt.branchName(), null, null)`로 끝낸다. 회차도 같다(`:1139-1150`).
    - 최대 id(없으면 0)를 기준 id로 Task 15에 넘긴다.
  - **설계와 다름 ①:** 세션은 PR 본문을 쓸 수 없다. 본문은 `renderPrBody`(`:1065-1103`)만 만든다. 그래서 "PR 본문에 남겨라"는 `.netis/db-notes.md`에 남기라는 지시로 바꾼다.
    - 워커는 `readReviewNotes` 결과 뒤에 `readDbNotes`를 빈 줄로 이어 붙여 `## 리뷰 메모`로 옮긴다. 회차에서는 구현 로그로 보낸다(`:582-589`).
    - `.netis/review-notes.md`를 쓰지 않는 이유: 리뷰·SDD 지시가 같은 파일에 "쓰세요"라고 해서(`prompts/implementation-review-section.md:6`, `prompts/fix-round-review-section.md:6`, `ImplementationPrompts.java:99`) 리뷰어가 덮어쓰면 경고가 사라진다. 그 지시를 고치면 타입 없음 구현 프롬프트의 바이트가 바뀐다.
  - **설계와 다름 ②:** 이전 쓰기 기록이 있는데 이번 구현에 쓰기 도구가 없으면, DB 작업 지시의 파일 경로를 `manualScriptPath`로 준다. 같은 경로를 주면 세션이 적은 새 SQL을 Task 15가 누적 기록으로 덮어써 PR에서 사라진다. 설계 §6.6은 이 경우를 정하지 않았다.
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.*"` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 워커 DB 쓰기 안내·이미 적용된 SQL 절`

---

### Task 15: 워커 — 스크립트 파일·성공 판정·실패 경로·PR 본문 절

**Files:**
- Create: `M/workerdaemon/DbScriptFile.java`(package-private)
- Modify: `M/workerdaemon/GitOpsService.java:190-194`(`hasCommitsSince`)
- Modify: `M/workerdaemon/WorkerMainLoop.java`
  - `:431-462` 성공
  - `:594-602` 회차
  - `:745-786` `preservePartialWork`
  - `:1049-1103` PR 본문
- Modify: `M/workerdaemon/WorkerHttpClient.java:123` → `SUPPORTS = "refRepos,fixRounds,dbWrite"`
- Test: `T/workerdaemon/DbScriptFileTest.java`, `WorkerMainLoopDbScriptTest.java`, `GitOpsServicePushTest.java`(추가, 실제 git), `WorkerHttpClientNextTaskTest.java:49-61`

**Interfaces:**
- Consumes: Task 14 기준 id·`needsDbLog`·`dbSpecOf`·`dbChanges`·`manualScriptPath`, Task 5 `taskTypeOf`·`GitOpsService.forceAdd`·세션 파일 강제 추가 자리
- Produces:
  - `DbScriptFile`
    - `static Optional<String> render(long taskId, List<DbChangeView>)` — EXECUTED·DEFERRED가 없으면 empty
    - `static Path write(Path worktree, String relPath, String content) throws IOException` — worktree 밖이면 `스크립트 경로가 worktree 밖입니다: …`
    - `static boolean wroteSince(List<DbChangeView>, long baselineId)`
    - `static boolean hasWork(TaskType type, boolean codeChanged, boolean wroteThisAttempt, boolean scriptWritten)` — DB_WORK면 `code||script`, 그 밖이면 `code||wrote`
  - `GitOpsService.hasCommitsSince(File dir, String baseSha)` — private `commitsSince`에 위임
  - 세션 파일 강제 추가(Task 5)의 대상을 `defaultScriptPath(id)`에서 `dbSpecOf(task).scriptPath()`와 `manualScriptPath(그 경로)` 둘로 넓힌다.
  - `static String WorkerMainLoop.dbChangeSection(List<DbChangeView>, String scriptPath, String sessionPath, boolean writeTools, boolean migrationManaged, TaskType)`
    - `scriptPath`는 워커 누적 파일(`- 스크립트 파일:` 줄)이다.
    - `sessionPath`는 `sessionScriptPath(db, writeTools)`이다. DB 작업에 쓰기 도구가 없으면 `- 개발 DB 미적용 — 수동 적용 필요: \`{sessionPath}\`` 줄에 쓴다.
  - `renderPrBody(…, refWarnings, String dbSection)` — 예산(`:1088-1090`)에 dbSection을 포함한다. 기존 오버로드는 `""`.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 렌더_순서와_절() { assertThat(DbScriptFile.render(42, entries).orElseThrow()).startsWith("-- Symphony 작업 #42 DB 변경 기록 (워커가 생성 — 직접 고치지 마세요)\n")
      .contains("-- [실행됨] 시도 1 · 개발 (PostgreSQL)").contains("\n-- 병합 뒤 실행(승인 대기)\n").doesNotContain("REJECTED 문장").doesNotContain("DISMISSED 문장"); }
@Test void 경로탈출_거부()                           // "../x.sql", "/abs.sql"
@Test void 커밋전에_스크립트를_쓰고_강제추가()          // InOrder(forceAdd(wt,"db/symphony/task-42.sql"), commitAndPush)
@Test void DB작업은_누적기록_파일만으로_성공()          // hasChanges·hasCommitsSince false, 새 기록 없음 → commitAndPush·createDraftPr
@Test void 다른타입_이번시도_쓰기도_코드도_없으면_변경없음()   // BUG_FIX → "nothing to commit", 파일 안 씀
@Test void 다른타입_이번시도_쓰기가_있으면_성공()
@Test void 기록이_없으면_세션이_쓴_파일을_덮어쓰지_않는다()
@Test void 세션이_쓴_무시대상_스크립트도_강제추가()      // (Review Focus 3) 기록 없음, DB_WORK, 세션 파일만 있음 → forceAdd → hasChanges true → commitAndPush·createDraftPr
@Test void 이전기록과_세션_manual_파일이_함께_커밋된다()  // 쓰기 도구 없음 + 기록 있음 → task-42.sql은 워커가 렌더, task-42-manual.sql은 그대로 두고 둘 다 forceAdd
@Test void claude실패_wip에도_스크립트()              // (Review Focus 3) InOrder(write, forceAdd(wt,"db/symphony/task-42.sql"), gitOps.preservePartialWork)
@Test void wip경로_조회실패는_사유끝에_SQL_스크립트_파일_미반영()
@Test void 성공경로_사후조회_실패는_wip보존_구현실패()   // "DB 변경 기록 조회 실패 — SQL 스크립트 파일 미반영"
@Test void 자동마이그레이션_레포는_조회도_파일도_없다()
@Test void 수정회차도_누적파일로_다시쓴다()             // baseSha tip
@Test void PR본문_DB변경절과_예산()   // "## DB 변경\n- 개발 DB에 실행한 SQL: 2건\n- 병합 뒤 실행(승인 대기): 1건\n", "- 스크립트 파일: `db/symphony/task-42.sql`"; 승인 대기 50 000자여도 ≤ 60 000
@Test void DB작업_쓰기도구없으면_개발DB_미적용_문구()     // "개발 DB 미적용 — 수동 적용 필요"
@Test void hasCommitsSince는_베이스_이후_커밋이_있을때만_참()   // GitOpsServicePushTest, 실제 git (무시된 파일의 실제 git 확인은 Task 5)
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.workerdaemon.DbScriptFileTest --tests com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopDbScriptTest --tests com.hamonsoft.netismaker.workerdaemon.GitOpsServicePushTest` → FAIL
- [ ] **Step 3: 구현**
  - **성공 경로**(일반·회차). 기존 정리를 마친 뒤 이 순서로 한다.
    1. worktree에 이미 있는 세션 파일(스크립트 경로·manual 경로)을 `forceAdd`한다(Task 5 자리).
    2. 워커가 파일을 쓰기 **전에** `codeChanged = hasChanges || hasCommitsSince(wt, baseSha)`를 구한다. 1 덕분에 무시되는 세션 파일도 여기서 보인다.
    3. `needsDbLog`면 사후 조회를 한다. 실패하면 실패 경로로 간다.
    4. `hasWork`가 거짓이면 기존 commit try 안에서 `IOException("nothing to commit — 구현 시도 후 파일 변경이 없음")`을 던진다. 회차는 기존 "변경 없음" 변환(`:600-602`)을 그대로 탄다.
    5. 렌더한 내용이 있으면 스크립트 경로에 `write`한 뒤 `forceAdd`한다. manual 경로는 건드리지 않는다.
    6. `commitAndPush`
  - **실패 경로:** `preservePartialWork` 5인자(`:754`)에서 `deleteAll` 다음에 같은 조회·쓰기를 하고 `write` 뒤 `forceAdd`한다. 그다음 세션 파일 강제 추가(1)를 하고 `gitOps.preservePartialWork`를 부른다. `git add -A`(`GitOpsService.java:141-143`)는 무시된 파일을 담지 않기 때문이다. 조회가 실패하면 `reasonSuffix`에 ` — SQL 스크립트 파일 미반영`을 붙인다.
  - 스크립트 파일을 `WORKER_EXCLUDES`(`WorktreeService.java:80`)에 넣지 않는다.
  - **설계와 다름:**
    - "이번 시도의 쓰기"를 attempt 번호 대신 claude 실행 전 기준 id로 판정한다. 회차가 attempt를 공유하기 때문이다(`WorkerTaskResponse.java:107-108`).
    - 성공 경로에서 사후 조회가 실패하면 wip로 보존하고 구현실패로 처리한다.
    - 회차는 PR을 새로 만들지 않으므로 PR 본문 DB 절을 넣지 않는다.
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.*"` PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 워커 SQL 스크립트 파일·성공 판정·PR DB 변경 절·supports=dbWrite`

---

### Task 16: 프론트 — 연결 쓰기 허용·카탈로그 칸·안내 문구

**Files:**
- Modify: `F/composables/dbConnections.ts:5-69`, `F/components/DbConnectionAdminDialog.vue:37-90`, `DbConnectionDialog.vue:123-159`, `F/pages/admin/repo-catalog.vue:12-31,86-190,252-272,338-347,432-506`
- Test: `F/test/db-connection-admin-dialog.spec.ts`, `db-connection-dialog.spec.ts`, `repo-catalog-admin.spec.ts`, `admin-catalog-cards-mobile.spec.ts`(추가)

**Interfaces:**
- Consumes: Task 8·9
- Produces:
  - `DbConnectionView.allowWrite`
  - `DbConnectionList.writeEnabled?`
  - `setDbConnectionAllowWrite(id: number, allowWrite: boolean): Promise<DbConnectionView>`
  - data-test: `db-allow-write-{id}`, `db-dialog-caption`, `form-db-migration-managed`, `form-db-script-dir`, `catalog-db-migration-chip`

- [ ] **Step 1: 실패하는 시험 작성**

```ts
it('쓰기 허용 토글은 allow-write 경로', () => expect(useApiMock).toHaveBeenCalledWith('/api/db-connections/7/allow-write', { method: 'PUT', body: { allowWrite: true } }))
it('활성 토글 바디는 그대로')               // :27-30 유지
it('개인·공용 연결 안내가 다르다')          // USER '개인 연결은 늘 읽기 전용으로만 쓰입니다' / REPO "관리자가 '쓰기 허용'을 켠 공용 연결은 구현 단계에서 쓰기가 가능합니다"
it('저장 뒤 쓰기 허용이 꺼졌으면 알린다')    // '접속 대상이 바뀌어 쓰기 허용을 껐습니다'
it('카탈로그 저장은 바꾼 칸만')             // dbMigrationManaged만 → dbScriptDir 키 없음
it('카탈로그 활성 토글 바디에는 새 칸 없음')  // admin-catalog-cards-mobile.spec.ts:82-88 유지
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run test/db-connection-admin-dialog.spec.ts test/db-connection-dialog.spec.ts test/repo-catalog-admin.spec.ts test/admin-catalog-cards-mobile.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - 안내(`:156-159`)는 `scope`로 나눈다.
  - 스크립트 폴더 칸은 placeholder를 `db/symphony`로 둔다. `noteFolder`처럼 바꿨을 때만 바디에 싣는다(`:155-157`).
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 연결 쓰기 허용 토글·카탈로그 DB 칸 화면`

---

### Task 17: 프론트 — 승인 다이얼로그·작업 상세 DB 변경·종료 승인 경고

**Files:**
- Create: `F/composables/taskDbChanges.ts` + `.spec.ts`, `F/components/tasks/TaskDbChanges.vue`
- Modify: `F/components/DbConnectionPicker.vue:12-21,69-77`, `ApproveDialog.vue:106-132,303-315`, `F/pages/tasks/[id].vue:168-184,396-399,896,1053-1067,1273`
- Modify: `F/pages/tasks/index.vue:163-185`, `F/components/tasks/TaskDetailMobile.vue:45-51,244`, `F/scripts/hydration-smoke.mjs:125-151`(`/^\/api\/tasks\/\d+\/db-changes$/` — 작업 1은 EXECUTED 1·DEFERRED 1, 나머지는 `[]`)
- Test: `F/test/task-detail-db-changes.spec.ts`(`task-detail-fix.spec.ts` 하네스), `F/components/ApproveDialog.spec.ts`, `F/test/tasks-index-mobile.spec.ts`, `F/test/db-connection-picker.spec.ts`(추가)

**Interfaces:**
- Consumes: Task 12, Task 16, Task 6
- Produces:
  - `taskDbChanges.ts`
    - `DbChangeView`(Task 12 칸)
    - `DB_CHANGE_LABELS = { EXECUTED:'실행됨', DEFERRED:'승인 대기', DISMISSED:'실행 안 함', REJECTED:'거부됨', FAILED:'실패' }`
    - `deferredCount(list: unknown): number`
    - `deferredWarning(n: number): string` — 0이면 `''`, 아니면 `` ` 승인 대기 SQL ${n}건 — 병합 뒤 실행 필요` ``
  - `TaskDbChanges.vue`
    - 속성 `{ taskId: string; isAdmin: boolean; refreshKey: number }`, 이벤트 `changed`
    - 응답이 배열이 아니거나 비면 그리지 않는다(`FixRequestHistory.vue:9-27`).
  - `DbConnectionPicker.showWrite?` → `db-write-badge`
  - `TaskDetailMobile.dbWritable?`
  - data-test: `db-change-row-{id}`, `db-change-execute-{id}`, `db-change-dismiss-{id}`, `approve-db-caption`, `approve-db-work-warning`, `task-db-caption`

- [ ] **Step 1: 실패하는 시험 작성**

```ts
it('DB 변경 카드는 연결별·시간순 분류 라벨')
it('승인 대기는 관리자에게만 실행·실행 안 함')
it('실행은 권장 문구 확인 뒤 POST', () => { /* 확인 메시지 contains 'PR 병합 뒤 실행을 권장합니다 — 같은 DB를 쓰는 다른 화면이 아직 옛 컬럼을 쓸 수 있습니다' */
  expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/db-changes/13/execute', { method: 'POST' }) })
it('실행 안 함은 POST …/dismiss')
it('응답이 배열이 아니면 그리지 않는다')        // task-detail-close.spec.ts:33 목
it('종료 승인 확인창에 승인 대기 SQL 2건')      // [id].vue·index.vue; tasks-index-mobile.spec.ts:145 문장 뒤에 붙음
it('건수 조회 실패면 경고 없이 승인')          // close/approve 바디 { method:'POST', body: undefined } 유지
it('승인 다이얼로그 쓰기 배지·캡션·DB 작업 경고')  // '구현 단계에서는' / '구현 때 개발 DB에 바로 적용할 수 없습니다 — SQL은 스크립트 파일로만 남습니다'
it('질문 화면 연결 목록에는 쓰기 배지가 없다')
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run test/task-detail-db-changes.spec.ts composables/taskDbChanges.spec.ts components/ApproveDialog.spec.ts test/tasks-index-mobile.spec.ts test/db-connection-picker.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - **카드 위치:** 데스크톱은 구현 결과 카드 뒤, 모바일은 `FixRequestHistory` 옆이다.
  - **"읽기 전용" 문구:** `[id].vue:1055`·`TaskDetailMobile.vue:244`·`ApproveDialog.vue:304-306`은 쓰기 가능 연결이 있을 때만 바꾼다.
  - **기본 선택:** `:127-132`는 그대로 둔다.
  - **설계와 다름:** 설계의 "연결 칩"은 실제로는 `DbConnectionPicker` 체크박스 목록이다(`:69-77`). 이 목록은 질문 화면(`pages/questions/index.vue:357`)도 쓰므로 `showWrite`로 표시 여부를 나눈다.
- [ ] **Step 4: 통과 확인** — `npx vitest run` 전체 PASS
- [ ] **Step 5: 커밋** — `feat(dbwrite): 작업 상세 DB 변경 카드·승인 다이얼로그 쓰기 표시·종료 승인 경고`

---

# Part C — DB 작업의 배포 없는 종료 (§6.9)

### Task 18: API — PR생성 종료 요청·복귀·산출물 모드

**Files:**
- Modify: `M/service/TaskCloseService.java:24-25,38,59-71,80-89,117-120,158-174`, `M/entity/TaskStatus.java:42-46`
- Test: `T/service/TaskCloseServiceDbWorkTest.java`(`TaskCloseServiceTest` 하네스), `T/controller/TaskCloseFlowIntegrationTest.java`(추가, CI)

**Interfaces:**
- Consumes: Task 1 `getTaskType()`
- Produces:
  - `static boolean TaskCloseService.canRequestClose(Task)` = `REQUESTABLE.contains(status) || (status==PR_CREATED && taskType==DB_WORK)`
  - `static boolean TaskCloseService.neverDeployed(Task)` = `deployUrl == null && deployedAt == null` — Task 19 `TaskCloser.neverDeployed(CloseContext)`와 같은 기준
  - 복귀 후보 `RETURNABLE = EnumSet.of(DEPLOYED, DEPLOY_LOST, PR_CREATED)`
  - 관문 메시지: `배포완료 또는 배포중단됨 상태에서만 종료를 요청할 수 있습니다 (DB 작업은 PR생성에서도 가능)`
  - `TaskFixService.REQUESTABLE`(`:46-47`)은 바꾸지 않는다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void DB작업은_PR생성에서_종료요청()       // CLOSE_REVIEW, 이력 PR_CREATED→CLOSE_REVIEW "결과 확인 완료 → 종료 요청"
@Test void 다른타입_PR생성은_409()             // BUG_FIX·null, 메시지 contains "배포완료"
@Test void 철회_반려_종료취소는_PR생성으로()
@Test void 이력을_못찾으면_배포흔적없는_DB작업은_PR생성_있으면_배포중단됨()
@Test void DB작업_종료승인은_산출물모드_null()   // 배포 흔적 없음: 스위치·카탈로그가 켜져 있어도 null, 이력에 "(산출물 보관)" 없음
@Test void 배포한_DB작업은_산출물모드_그대로()    // deployUrl·deployedAt 있음 → ARTIFACT_REQUIRED, "(산출물 보관)"
@Test void 수정요청_가능상태는_그대로()          // {DEPLOYED, DEPLOY_LOST, DEPLOY_FAILED}
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.TaskCloseServiceDbWorkTest` → FAIL
- [ ] **Step 3: 구현**
  - 관문(`:62`)은 `canRequestClose`, 복귀(`:167`)는 `RETURNABLE`로 나눈다.
  - 산출물 모드는 DB 작업이면서 `neverDeployed`일 때만 null이다(`approve`, `:80-89`).
    - 설계 §6.9의 근거는 "배포 이미지가 없으므로"다. 그래서 보조 '배포'로 배포까지 한 DB 작업은 지금처럼 카탈로그·스위치를 따른다.
    - PR생성은 구현 직후거나 배포 중지 복귀 뒤라 배포 칸이 늘 비어 있다(`WorkerService.java:859-866`). 그래서 PR생성에서 종료를 요청한 DB 작업은 늘 null이다.
  - **설계와 다름:** 이력을 못 찾았을 때의 대체값(`:169,173`)이 설계에 없다. `neverDeployed`인 DB 작업은 `PR_CREATED`로 돌린다. 그렇게 하지 않으면 `DEPLOY_LOST`에 갇힌다(`DeployReconcileJob.java:56-67`).
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.service.TaskClose*" --tests com.hamonsoft.netismaker.service.TaskFixServiceTest --tests com.hamonsoft.netismaker.service.WorkerServiceCloseTest` PASS
- [ ] **Step 5: 커밋** — `feat(close): DB 작업 PR생성에서 종료 요청·복귀·산출물 없음`

---

### Task 19: 워커 — 띄운 적 없는 배포 중지는 건너뜀

**Files:**
- Modify: `M/workerdaemon/TaskCloser.java:110-128`
- Test: `T/workerdaemon/TaskCloserTest.java`(추가, `:46` `task(progress)`에 deployUrl null 변형)

**Interfaces:**
- Produces: `static boolean TaskCloser.neverDeployed(CloseContext c)` = `c != null && c.deployUrl() == null && c.deployedAt() == null`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 배포흔적이_없으면_배포중지를_부르지_않고_UNDEPLOYED()  // verify(deploy, never()).undeploy(7L); 로그 "배포 중지: 띄운 컨테이너 없음 — 건너뜀"
@Test void closeContext가_null이거나_배포URL이_있으면_지금처럼()   // :144-156 순서 유지
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.workerdaemon.TaskCloserTest` → FAIL
- [ ] **Step 3: 구현** — `neverDeployed`이면 `step` 없이 `progress(task, UNDEPLOYED, null, null)`만 보낸다.
  - 배포 순서 제약은 없다. 옛 워커는 `docker rm -f`를 그대로 통과한다(`LocalDockerTarget.java:236-241`).
  - **설계와 다름:** 타입이 아니라 `closeContext`의 배포 흔적(`CloseContext.java:24-25`)으로 판단한다. `WorkerTaskResponse`를 건드리지 않기 위해서다.
- [ ] **Step 4: 통과 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.TaskCloser*" --tests com.hamonsoft.netismaker.entity.CloseStepTest` PASS
- [ ] **Step 5: 커밋** — `feat(close): 띄운 컨테이너가 없으면 배포 중지 건너뜀`

---

### Task 20: 프론트 — DB 작업 PR생성의 "확인 완료(종료 요청)"

**Files:**
- Modify: `F/composables/taskStages.ts:411-428,445-486`(`attentionGroup` `:361-367`은 그대로), `F/pages/tasks/[id].vue:391-407,758-764,939-1009`
- Modify: `F/components/tasks/TaskActionSheet.vue:14-30,102-121`, `TaskListMobile.vue:9-17,211-219`, `TaskCardCompact.vue:15-45`, `F/pages/tasks/index.vue`(`request-close`·`viewerId`)
- Modify: `F/scripts/hydration-smoke.mjs:68-91`(task 11 = PR생성·DB_WORK), `:34-50` `AUTH_PATHS`에 `/tasks/11`(관리자 상세의 '확인 완료 — 종료 요청'·`deploy-secondary`를 SSR·하이드레이션에서 그린다)
- Test: `F/composables/taskStages.spec.ts`, `F/test/task-detail-close.spec.ts`, `F/components/tasks/TaskActionSheet.spec.ts`, `F/test/tasks-index-mobile.spec.ts`(추가)

**Interfaces:**
- Consumes: Task 18, Task 6
- Produces:
  - 순수 함수의 task 인자에 `taskType?: string|null`을 더한다(`stageSteps`는 `deployment?`도).
  - `nextAction`
    - 조건: PR생성·DB_WORK, 관리자 또는 등록자
    - 결과: `{ text: 'DB 작업 — 개발 DB 반영을 확인했으면 종료를 요청하세요', primary: { label: '확인 완료 — 종료 요청', icon: 'task_alt', kind: 'request-close' } }`
  - `attentionGroup`은 바꾸지 않는다. PR생성·DB_WORK는 지금처럼 `'done'`이다.
  - `stageSteps`: deploy가 done이고 DB_WORK이며 `deployment`가 없으면 `'skipped'`
  - `TaskActionSheet`: 속성 `viewerId?`, 이벤트 `request-close`. `TaskListMobile`은 이 값을 넘기고 이벤트를 위로 올린다.
  - data-test: `deploy-secondary`, `bar-more-deploy`, `sheet-request-close`

- [ ] **Step 1: 실패하는 시험 작성**

```ts
it('DB 작업 PR생성: 관리자·등록자는 종료 요청', () => { expect(nextAction({ status: 'PR_CREATED', taskType: 'DB_WORK' }, true)?.primary?.kind).toBe('request-close')
  expect(nextAction({ status: 'PR_CREATED', taskType: 'DB_WORK' }, false, true)?.primary?.kind).toBe('request-close')
  expect(nextAction({ status: 'PR_CREATED', taskType: 'BUG_FIX' }, true)?.primary?.kind).toBe('deploy') })
it('attentionGroup: DB 작업 PR생성도 배포완료처럼 done')   // const t = { status: 'PR_CREATED', taskType: 'DB_WORK' }; attentionGroup(t, true|false) === 'done'(변수로 넘겨 초과 속성 검사를 피한다); 기존 ['PR_CREATED', false, 'done'] 유지(:168-180)
it('stageSteps: 배포 없이 종료한 DB 작업은 deploy skipped, 배포했으면 done')
it('관리자 상세: 주 행동 종료 요청 + 보조 배포')    // deploy-secondary, 390px bar-more-deploy
it('DB 작업 종료 요청 문구')                     // '개발 DB 반영 결과를 확인했나요?' → POST /api/tasks/42/close-request
it('모바일 시트에서 등록자가 종료 요청')            // sheet-request-close → confirm-dialog-ok → POST
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run composables/taskStages.spec.ts test/task-detail-close.spec.ts components/tasks/TaskActionSheet.spec.ts test/tasks-index-mobile.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - 보조 배포 버튼은 `canAdminRequestCloseLost` 패턴(`[id].vue:407,761,977`)을 따른다.
  - 종료 요청 문구: `개발 DB 반영 결과를 확인했나요? 승인되면 PR을 병합하고 노트 작성·브랜치 정리를 진행합니다.`
  - **설계와 다름 ①:** 설계 §6.9는 `attentionGroup`에도 반영하라고 했지만 바꾸지 않는다.
    - 같은 "확인 완료 — 종료 요청"을 기다리는 배포완료가 `DONE_STATUSES`(`taskStages.ts:359`)라 'done'이다. DB 작업 PR생성도 그와 같게 둔다.
    - 이 함수에는 소유자 인자가 없다(`:361`). 그리고 전역 하단 배지 수(`layouts/default.vue:40-44`)에도 쓰인다. 'attention'으로 두면 관계없는 사용자의 배지까지 늘어난다.
    - 다음 할 일은 `nextAction`과 상세 버튼이 보여 준다.
  - **설계와 다름 ②:** "배포 건너뜀"은 `deployment == null`로 근사한다(`TaskResponse.java:319-322`).
- [ ] **Step 4: 통과 확인** — `npx vitest run` 전체 PASS(`layout-default.spec.ts:210,225` 포함)
- [ ] **Step 5: 커밋** — `feat(close): DB 작업 PR생성 종료 요청 화면`

---

### Task 21: 문서·배포 순서·마지막 검증

**Files:**
- Modify: `CLAUDE.md`
  - `:118` 종료 다이어그램에 `[PR생성(DB 작업)]`
  - "작업 상태머신" 아래 단락 두 개
  - "자주 보는 코드" 표 두 줄
- Modify: V39 머리 주석 — V24의 낡은 주석(`V24__db_connection.sql:25` "인터뷰 세션은 항상 []")을 바로잡는 한 줄

- [ ] **Step 1: 전체 시험**
  - `.\gradlew.bat test` → PASS(Testcontainers는 건너뜀)
  - `cd netismaker-interview-service; npx vitest run; npx tsc --noEmit -p tsconfig.json` → PASS
  - `cd frontend; npx vitest run; npx nuxi prepare; npm run typecheck` → PASS, 오류 0
- [ ] **Step 2: 하이드레이션 스모크** — Chrome이 설치돼 있는지 확인한다(없으면 `$env:SMOKE_BROWSER_CHANNEL='msedge'`). `cd frontend; npm run build; npm run test:hydration` → 경고·예외 0, "목 응답이 없어 null로 답한 API" 없음
- [ ] **Step 3: lint** — `cd frontend; npm run lint` → 오류 0, 경고 수가 기준선 이하
- [ ] **Step 4: CLAUDE.md** — 다음을 적는다.
  - 타입 값·앞머리
  - 쓰기 6조건·분류 요약, `DB_WRITE_ENABLED`·상한 키
  - 엔드포인트 5개, `supports=dbWrite`
  - 스크립트 경로, 자동 마이그레이션 표시
  - DB 작업의 배포 없는 종료
  - 아래 배포 순서
- [ ] **Step 5: PR 본문에 배포 순서(§9)를 적는다.** 실행자는 배포하지 않는다. 배포는 사용자 승인 뒤 운영자가 한다.
  1. **API:** V39를 적용하고 `DB_WRITE_ENABLED` 없이 배포한다(`.\scripts\stop-public.ps1 -Only api` → `Start-ScheduledTask -TaskName netisMaker-public`). 옛 워커와 인터뷰 서비스는 새 칸을 무시한다.
  2. **워커 3대**(이 PC, 10.1.1.75, Mac): 진행 중 구현이 없을 때 jar를 갱신하고 재기동한다. claim에 `supports=refRepos,fixRounds,dbWrite`가 실리는지 확인한다.
  3. **인터뷰 서비스:** 순서 자유.
  4. **프론트:** `.output`을 재빌드한다.
  5. **쓰기 켜기:**
     - 계정에 CREATE·ALTER·INSERT·UPDATE·DELETE 권한이 있는지 확인한다.
     - 연결 하나만 "쓰기 허용"을 켠다. 어느 연결로 할지, 시험 작업으로 할지는 사용자와 정한다.
     - `public.env`에 `DB_WRITE_ENABLED=true`를 넣고 API만 재기동한다.
     - 첫 작업은 관리자가 지켜본다.
- [ ] **Step 6: 커밋** — `docs(dbwrite): 작업 타입·개발 DB 쓰기 흐름과 배포 순서`
- [ ] **Step 7: CI 확인** — 사용자 승인 뒤 draft PR을 push한다.
  - push 전에 여섯 (CI) 클래스가 모두 `RUN_TESTCONTAINERS` 애너테이션을 다는지 본다: `TaskTypeMigrationIntegrationTest`·`TaskListTypeFilterIntegrationTest`·`DbWriteSchemaIntegrationTest`·`JdbcWriteExecutorPostgresIntegrationTest`·`TaskDbChangeFlowIntegrationTest`·`TaskCloseFlowIntegrationTest`. 확인 명령은 `rg -l 'named = "RUN_TESTCONTAINERS"' src/test` → 여섯 파일이 모두 나와야 한다.
  - CI `Backend` 잡이 성공해야 한다. CI는 `RUN_TESTCONTAINERS: 'true'`로 돌리므로(`.github/workflows/ci.yml:22`) 성공이면 여섯 클래스가 실행되어 통과한 것이다. 리포트 아티팩트는 실패 때만 올라간다(`:33`).
  - 실패하면 `backend-test-reports`에서 원인을 본다.
  - `Frontend`·`Interview service` 잡도 성공해야 한다. PR 본문에 run id를 적는다.
