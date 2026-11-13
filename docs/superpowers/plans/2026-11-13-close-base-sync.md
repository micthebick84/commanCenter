# 기준 브랜치 반영 회차 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 병합 전 `종료실패`에서 관리자가 버튼 하나로 작업 브랜치에 기준 브랜치를 반영하고(충돌은 Claude가 해결) 자동 재배포까지 가게 하며, GitLab의 병합 가능 여부 계산 중 실패와 막다른 실패 문구를 없앤다.

**Architecture:** 수정 요청 회차(`task_fix_request` + `task.active_fix_request_id`)에 종류 `kind`(`FIX`|`BASE_SYNC`)를 더한다. API는 `종료실패`에서 `BASE_SYNC` 회차를 열어 `구현대기`로 보내고, 워커는 회차 claim의 `kind`를 보고 새 길(`syncBaseRound`)로 가서 병합·충돌 해결·이어서 push를 한 뒤 기존 회차 성공 보고를 그대로 쓴다. 자동 재배포·회차 닫기·늦은 보고 판정·GC 보호는 기존 회차 코드를 탄다.

**Tech Stack:** Spring Boot(Java 21, JPA, Flyway, PostgreSQL), 워커 데몬(같은 jar, git CLI + claude CLI), Nuxt 3 + Quasar(vitest), JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-13-close-base-sync-design.md` — 실행자는 계획과 함께 읽는다. 절 번호(§)는 이 문서를 가리킨다.

## Global Constraints

- 작업 위치는 이 worktree(`.claude/worktrees/close-base-sync`, 브랜치 `feature/close-base-sync`)뿐이다. 본 체크아웃(`commanCenter` 루트)은 라이브 스택이 돌고 있으니 거기서 빌드·시험·`npm ci`·`gradlew --stop`을 하지 않는다.
- 마이그레이션 번호는 `V45`(지금 최대 V44). PR 병합 직전 `origin/main`의 최대 번호를 다시 보고 겹치면 올린다.
- 배포로 확인한 커밋만 병합한다: `BASE_SYNC` 회차의 **실패는 `task.head_sha`를 바꾸지 않는다**(§5.5).
- force push·rebase 금지. push는 `git push <url> HEAD:refs/heads/<branch>`(기존 `GitOpsService.push`)만.
- 캐시 unshallow 금지. 깊이는 50 → 500까지만(§6-2).
- 실패 시 wip 커밋을 올리지 않는다(§6).
- 기존 `FIX` 회차의 프롬프트·보고 바디·이력 문구는 `kind` 한 칸 외에 바이트까지 그대로다 — 기존 회차 시험이 수정 없이(생성자 인자 추가 제외) 통과해야 한다.
- 상한: `app.task.base-sync-max-rounds` 기본 3(중단·변경 없음 포함해 센다). 수정 요청 상한과 따로.
- 병합 가능 여부 대기: 2000ms 간격 최대 10회(§7).
- 워커 claim 토큰: `refRepos,fixRounds,dbWrite,mockups,baseSync`.
- 토큰·인증 URL은 로그·예외·실패 사유에 나가면 안 된다(`GitRemotes.mask`, 기존 git 통로 재사용).
- 프론트 코드 스타일: 작은따옴표·세미콜론 없음. `npm run lint-prettier`를 전체에 돌리지 않는다. 확인창은 `confirmDialog($q)`(`window.confirm` 금지).
- 시험 실행: 백엔드 `./gradlew test --tests '<클래스 FQCN>'`(JAVA_HOME=JDK 21, Git Bash에서 `*`가 든 패턴은 폴더로 확장되니 클래스명을 다 쓴다). 프론트는 `frontend/`에서 `npx vitest run <파일>` — `frontend/node_modules`가 없으면 본 체크아웃 `frontend/node_modules`로의 **정션**을 만든다(PowerShell `New-Item -ItemType Junction`). ⚠️ worktree를 지우기 전에 정션 링크만 먼저 지운다.
- Testcontainers 시험은 이 PC에서 건너뛴다(CI가 돌린다) — 작성은 하되 로컬 통과를 기대하지 않는다.
- 커밋은 작업(Task) 끝마다. 메시지 끝에 `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`. push·PR은 사용자가 말할 때만.

## Review Focus

1. 사람이 브랜치에 직접 push한 상태(브랜치 끝 ≠ `head_sha`)에서 반영 회차가 실패 → `반영 중단`: `head_sha`가 그대로여서 다음 종료가 `head 불일치`로 거부돼야 한다 → Task 4·5 시험.
2. 충돌 파일이 Markdown이고 본문에 `=======`(제목 밑줄) 줄이 있다: 충돌 표식으로 오판해 실패시키면 안 된다 → Task 6 시험.
3. 충돌 파일 경로에 공백·한글이 있다: 목록이 깨지거나 따옴표가 붙은 채 프롬프트·검사에 들어가면 안 된다 → Task 6 시험.
4. 바이너리 파일 충돌: Claude를 돌리지 않고 바로 실패해야 한다 → Task 6·7 시험.
5. `baseSync`를 모르는 구버전 워커: `BASE_SYNC` 회차는 못 받고 `FIX` 회차·다른 종류는 그대로 받아야 한다 → Task 3 시험.

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/resources/db/migration/V45__fix_request_kind.sql` (새) | `task_fix_request.kind` 칸 |
| `entity/FixRequestKind.java` (새) | `FIX`, `BASE_SYNC` |
| `entity/TaskFixRequest.java`, `entity/FixOutcome.java` | `kind` 필드·`baseSync(...)` 팩토리, `NO_CHANGE`·`SYNCED` |
| `repository/TaskFixRequestRepository.java` | kind별 조회·집계 |
| `service/FixRounds.java` | kind별 이름, 복귀 상태 판정 |
| `service/TaskFixService.java` | 수정 요청 상한이 `FIX`만 셈, `BASE_SYNC` 중단 |
| `service/TaskCloseService.java`, `controller/TaskCloseController.java` | `syncBase` 열기 |
| `service/WorkerCapabilities.java`, `controller/WorkerController.java`, `repository/TaskRepository.java` | `baseSync` claim 가드 |
| `dto/WorkerTaskResponse.java`(`FixRoundSpec.kind`), `dto/FixRequestView.java`, `dto/TaskResponse.java`(`FixView.activeKind`) | 전달 칸 |
| `service/WorkerService.java` | claim에 `kind`, 결과 보고 갈래 |
| `service/TaskService.java` | `BASE_SYNC` 재시도는 방식 무시 |
| `workerdaemon/GitRepoCache.java` | 두 브랜치 받기 + 공통 조상 확보 |
| `workerdaemon/BaseSyncMerger.java` (새) | worktree 안의 병합·충돌 목록·검사·병합 커밋 |
| `workerdaemon/BaseSyncPrompts.java` (새) | 충돌 해결 프롬프트·이름·커밋 메시지 |
| `workerdaemon/GitOpsService.java` | `pushHead` 공개 |
| `workerdaemon/WorkerMainLoop.java`, `workerdaemon/WorkerHttpClient.java` | `syncBaseRound`, claim 토큰 |
| `workerdaemon/GitLabMrCloser.java`, `workerdaemon/GitHubPrCloser.java` | 계산 대기, 문구 |
| `frontend/composables/taskFix.ts`, `frontend/pages/tasks/[id].vue`, `frontend/components/tasks/FixRequestHistory.vue`, `frontend/components/tasks/ImplementationRetryDialog.vue`, `frontend/scripts/hydration-smoke.mjs` | 버튼·확인창·이력·재시도/중단 문구 |
| `CLAUDE.md`, `TODOS.md`, `src/main/resources/application.yml` | 문서·설정 |

Java 경로는 모두 `src/main/java/com/hamonsoft/netismaker/` 아래, 시험은 `src/test/java/com/hamonsoft/netismaker/` 아래 같은 패키지.

---

### Task 1: 회차 종류 `kind` — 데이터·이름·집계

**Files:**
- Create: `src/main/resources/db/migration/V45__fix_request_kind.sql`, `entity/FixRequestKind.java`
- Modify: `entity/TaskFixRequest.java`, `entity/FixOutcome.java`, `repository/TaskFixRequestRepository.java`, `service/FixRounds.java`, `service/TaskFixService.java`(:91, :112, :203), `service/WorkerService.java`(`openFixRoundOf` :372-381), `dto/FixRequestView.java`, `dto/TaskResponse.java`(`FixView`), `dto/WorkerTaskResponse.java`(`FixRoundSpec`)
- Test: `service/FixRoundsTest`, `service/TaskFixServiceTest`, `service/WorkerServiceFixRoundClaimTest`, `dto/WorkerTaskResponseFixRoundTest`, `dto/TaskResponseFixTest`

**Interfaces:**
- Produces:
  - `enum FixRequestKind { FIX, BASE_SYNC }`
  - `TaskFixRequest.getKind()/setKind(FixRequestKind)` — `@Enumerated(STRING)`, 칸 `kind`, NOT NULL, 필드 기본값 `FIX`
  - `static TaskFixRequest TaskFixRequest.baseSync(Long taskId, int round, String baseBranch, TaskStatus fromStatus, String adminId)` — `kind=BASE_SYNC`, `status=APPROVED`, `implementationMode=DIRECT`, `body="기준 브랜치 " + baseBranch + " 반영"`, `requestedBy=decidedBy=adminId`, `decidedAt=now`
  - `FixOutcome`에 `NO_CHANGE`, `SYNCED` 추가
  - Repository: `long countByTaskIdAndStatusAndKind(Long taskId, FixRequestStatus status, FixRequestKind kind)`, `long countByTaskIdAndKind(Long taskId, FixRequestKind kind)`, `List<TaskFixRequest> findByTaskIdAndStatusAndKindAndRoundLessThanOrderByRoundAsc(Long taskId, FixRequestStatus status, FixRequestKind kind, Integer round)`
  - `static boolean FixRounds.isBaseSync(TaskFixRequest r)` (null이면 false)
  - `FixRounds.label(r)`: `BASE_SYNC`면 `"기준 브랜치 반영 " + round + "회차"`(round null이면 `"기준 브랜치 반영 회차"`), `FIX`는 지금 그대로
  - `static TaskStatus FixRounds.baseSyncReturnStatus(TaskFixRequest r, Task t)` — `r.getFromStatus()`가 `DEPLOYED`·`DEPLOY_LOST`·`PR_CREATED`면 그 값, 아니면(`r == null` 포함) `t.getTaskType() == DB_WORK && TaskCloseService.neverDeployed(t) ? PR_CREATED : DEPLOY_LOST`
  - `FixRoundSpec(Long requestId, int round, int attempt, String body, List<String> previousBodies, String headBranch, String headSha, String kind)` + 7인자 호환 생성자(`kind="FIX"`)
  - `FixRequestView`에 `String kind` 컴포넌트(맨 끝), `TaskResponse.FixView(Integer activeRound, String activeMode, int approvedRounds, int maxRounds, String activeKind)`

- [ ] **Step 1: 실패하는 시험 작성**

`FixRoundsTest`에 추가:

```java
@Test void label_기준브랜치반영() {
    TaskFixRequest r = TaskFixRequest.baseSync(1L, 2, "main", TaskStatus.DEPLOYED, "admin");
    assertThat(FixRounds.label(r)).isEqualTo("기준 브랜치 반영 2회차");
    assertThat(FixRounds.isBaseSync(r)).isTrue();
    assertThat(FixRounds.isOpen(r)).isTrue();
    assertThat(r.getBody()).isEqualTo("기준 브랜치 main 반영");
    assertThat(r.getImplementationMode()).isEqualTo(ImplementationMode.DIRECT);
}
@Test void baseSyncReturnStatus_PR생성을_그대로_돌려준다() { /* from=PR_CREATED → PR_CREATED */ }
@Test void baseSyncReturnStatus_모르는_값이면_배포한적없는_DB작업은_PR생성_그밖은_배포중단됨() { /* from=CLOSE_FAILED: DB_WORK+deployUrl/deployedAt null → PR_CREATED; 그 밖 → DEPLOY_LOST */ }
@Test void 기존_pending_행의_kind는_FIX() { assertThat(TaskFixRequest.pending(1L, "b", TaskStatus.DEPLOYED, "u").getKind()).isEqualTo(FixRequestKind.FIX); }
```

`TaskFixServiceTest`: 기존 상한 시험의 목을 `countByTaskIdAndStatusAndKind(id, APPROVED, FIX)`로 바꾸고, 새 시험 `수정요청_상한은_반영회차를_세지_않는다`(목: `…AndKind(…, FIX)` = 2 → request 성공, `countByTaskIdAndStatus`는 호출되지 않음 `verify(fixRepo, never())`). `fixViewOf`가 열린 회차의 `activeKind`를 `"BASE_SYNC"`/`"FIX"`로, 없으면 null로 주는 시험.

`WorkerServiceFixRoundClaimTest`: claim의 `implementation.fix.kind`가 `FIX` 회차는 `"FIX"`, `BASE_SYNC` 회차는 `"BASE_SYNC"`이고, `BASE_SYNC` 회차의 `previousBodies`는 빈 목록, `FIX` 회차의 `previousBodies` 조회는 `…AndKind…(…, FIX, round)`를 쓴다.

`WorkerTaskResponseFixRoundTest`: 7인자 생성자의 `kind()`가 `"FIX"`.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.service.FixRoundsTest' --tests 'com.hamonsoft.netismaker.service.TaskFixServiceTest'` → 컴파일 오류(없는 심볼).

- [ ] **Step 3: 구현**

`V45__fix_request_kind.sql`:

```sql
-- 회차 종류: FIX(수정 요청) | BASE_SYNC(기준 브랜치 반영, 스펙 2026-11-13). 기존 행은 FIX.
ALTER TABLE com.task_fix_request
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'FIX';
ALTER TABLE com.task_fix_request
    ADD CONSTRAINT ck_task_fix_request_kind CHECK (kind IN ('FIX', 'BASE_SYNC'));
```

- `TaskFixService`의 `countByTaskIdAndStatus(taskId, APPROVED)` 세 곳을 `…AndKind(…, FixRequestKind.FIX)`로.
- `WorkerService.openFixRoundOf`: `BASE_SYNC`면 `previous = List.of()`, 아니면 kind=FIX 조회. `FixRoundSpec` 끝에 `r.getKind().name()`.
- `FixRequestView.of`가 `r.getKind().name()`을 싣는다.

- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.hamonsoft.netismaker.service.WorkerServiceFixRoundClaimTest' --tests 'com.hamonsoft.netismaker.dto.WorkerTaskResponseFixRoundTest' --tests 'com.hamonsoft.netismaker.dto.TaskResponseFixTest'` → PASS. `new FixRequestView(`·`new TaskResponse.FixView(`·`new .*FixRoundSpec(`를 grep해 깨진 직접 생성 지점이 없는지 `./gradlew compileTestJava`로 확인.

- [ ] **Step 5: 커밋** — `feat: 수정 회차에 종류(kind) 추가 — 기준 브랜치 반영 준비`

---

### Task 2: 열기 — `POST /api/tasks/{id}/close/sync-base`

**Files:**
- Modify: `service/TaskCloseService.java`, `controller/TaskCloseController.java`, `src/main/resources/application.yml`(`app.task.base-sync-max-rounds: 3`, `fix-request-max-rounds` 옆)
- Test: 새 `service/TaskCloseServiceBaseSyncTest`(Mockito — `TaskCloseServiceTest`의 생성 방식을 따른다), `controller/TaskCloseFlowIntegrationTest`에 한 건(Testcontainers)

**Interfaces:**
- Consumes: Task 1의 `TaskFixRequest.baseSync`, `countByTaskIdAndKind`, `FixRounds.label`
- Produces: `@Transactional public Task TaskCloseService.syncBase(Long taskId, String adminId)`; 엔드포인트 `POST /api/tasks/{id}/close/sync-base`(`ROLE_ADMIN`, `cancel`과 같은 모양으로 `TaskResponse` 반환). 생성자에 `TaskFixRequestRepository fixRepo`와 `@Value("${app.task.base-sync-max-rounds:3}") int baseSyncMaxRounds` 추가.

- [ ] **Step 1: 실패하는 시험 작성** (`TaskCloseServiceBaseSyncTest`)

```java
@Test void 종료실패_병합전이면_회차를_열고_구현대기로() {
    // given: status=CLOSE_FAILED, closeProgress=null, failureReason="충돌: …", closePatchMode="REQUIRED",
    //        headBranch="netismaker/task-42-x", headSha="8269d0f", githubBranch="main", activeFixRequestId=null,
    //        이력: 배포완료 → 종료승인대기, fixRepo.countByTaskIdAndKind(42, BASE_SYNC)=0, save가 id=7을 채움
    Task t = service.syncBase(42L, "admin");
    assertThat(t.getStatus()).isEqualTo(TaskStatus.APPROVED);
    assertThat(t.getActiveFixRequestId()).isEqualTo(7L);
    assertThat(t.getFailureReason()).isNull();
    assertThat(t.getCloseProgress()).isNull();
    assertThat(t.getClosePatchMode()).isNull();
    assertThat(t.getImplementationLog()).isNull();
    assertThat(t.getImplementationTimeoutMinutes()).isNull();
    assertThat(t.getRestartRequeueCount()).isZero();
    assertThat(t.getHeadSha()).isEqualTo("8269d0f");            // 그대로
    // 저장된 회차: kind=BASE_SYNC, round=1, fromStatus=DEPLOYED, status=APPROVED, mode=DIRECT
    // 이력 사유: "종료 취소(병합 전) → 기준 브랜치 반영 1회차 → 구현대기"
}
@Test void 병합된_뒤에는_409() { /* closeProgress="MERGED" → "이미 병합되어 기준 브랜치를 반영할 수 없습니다 — 재시도로 마무리하세요" */ }
@Test void 종료실패가_아니면_409() { /* "종료실패 상태에서만 기준 브랜치를 반영할 수 있습니다 (현재: 배포완료)" */ }
@Test void 열린_회차가_있으면_409() { /* "이미 진행 중인 회차가 있습니다" */ }
@Test void 브랜치_정보가_없거나_기준_브랜치와_같으면_409() { /* "반영할 작업 브랜치·커밋 정보가 없습니다" / "작업 브랜치가 기준 브랜치(main)와 같습니다" */ }
@Test void 상한_3회를_다_쓰면_409() { /* count=3 → "기준 브랜치 반영은 작업당 3회까지입니다 — GitLab에서 직접 해결하세요" */ }
@Test void 두번째_회차의_번호는_2() { /* count=1 → round=2 */ }
@Test void DB작업_PR생성에서_종료하던_작업은_fromStatus가_PR생성() { }
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.service.TaskCloseServiceBaseSyncTest'` → 컴파일 오류.

- [ ] **Step 3: `syncBase` 구현** — 순서: 잠금 조회 → 상태 검사 → MERGED 검사 → 열린 회차 검사 → 브랜치 검사 → 상한 검사 → `from = statusBeforeReview(t)` → `cancel`과 같은 세 칸 비움 → 회차 저장(`round = count + 1`) → `activeFixRequestId`·`implementationLog=null`·`implementationTimeoutMinutes=null`·`restartRequeueCount=0` → `move(t, APPROVED, "user", adminId, 사유)`. 컨트롤러는 `cancel` 메서드 바로 아래에 같은 모양으로. 클래스 javadoc 엔드포인트 목록에 한 줄.

- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.hamonsoft.netismaker.service.TaskCloseServiceTest' --tests 'com.hamonsoft.netismaker.service.TaskCloseServiceDbWorkTest'` → PASS(생성자 인자 추가로 깨진 기존 시험 생성부를 고친다).

- [ ] **Step 5: 커밋** — `feat: 종료실패에서 기준 브랜치 반영 회차 열기 API`

---

### Task 3: claim 가드 — `supports=baseSync`

**Files:**
- Modify: `service/WorkerCapabilities.java`, `controller/WorkerController.java`(:52-64, :92-103), `repository/TaskRepository.java`(:197-223), `service/WorkerService.java`(:249, :254)
- Test: `service/WorkerCapabilitiesTest`, `controller/WorkerControllerSupportsTest`, `repository/TaskClaimFixRoundSupportIntegrationTest`(Testcontainers)

**Interfaces:**
- Produces: `record WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite, boolean mockups, boolean baseSync)` — `ALL`은 전부 true, 기존 4·3·2인자 생성자는 `baseSync=false`로 위임, `needsFilteredQueue()`는 `!(refRepos && fixRounds && mockups && baseSync)`. `WorkerController.SUPPORTS_BASE_SYNC = "baseSync"`. `findClaimableAmongForUpdateSkipLocked(statuses, supportsRefRepos, supportsFixRounds, supportsMockups, supportsBaseSync)`.

- [ ] **Step 1: 실패하는 시험 작성**

`WorkerCapabilitiesTest`: 4인자 생성자의 `baseSync()`가 false이고 `needsFilteredQueue()`가 true; `ALL.needsFilteredQueue()`는 false.
`WorkerControllerSupportsTest`: `supports=refRepos,fixRounds,dbWrite,mockups,baseSync` → 서비스에 `baseSync=true`인 caps, 토큰이 없으면 false.
`TaskClaimFixRoundSupportIntegrationTest`(Review Focus 5):

```java
@Test void baseSync를_모르는_워커는_반영회차를_못_받고_FIX회차는_받는다() {
    // 구현대기 2건: A(active → kind=BASE_SYNC 회차, 먼저 생성), B(active → kind=FIX 회차)
    // findClaimableAmongForUpdateSkipLocked([APPROVED], true, true, true, false) → B
    // (…, true, true, true, true) → A
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerCapabilitiesTest' --tests 'com.hamonsoft.netismaker.controller.WorkerControllerSupportsTest'` → 컴파일 오류.

- [ ] **Step 3: 구현** — 네이티브 쿼리에 조건 하나(파라미터 `includeBaseSync`):

```sql
AND (CAST(:includeBaseSync AS boolean)
     OR NOT (t.status = :approved AND EXISTS (
         SELECT 1 FROM com.task_fix_request r
         WHERE r.id = t.active_fix_request_id AND r.kind = 'BASE_SYNC')))
```

`TaskRepositoryClaimableStatusesTest`가 대조하는 상태 목록은 바뀌지 않는다. `WorkerCapabilities` javadoc에 `baseSync` 설명 한 줄(구버전 워커는 `kind`를 버리고 빈 수정 요청으로 Claude를 돌린다).

- [ ] **Step 4: 통과 확인** — Step 2 명령 → PASS, `./gradlew compileTestJava` 성공.

- [ ] **Step 5: 커밋** — `feat: baseSync를 모르는 워커에 기준 브랜치 반영 회차를 넘기지 않음`

---

### Task 4: 결과 보고 갈래 (API)

**Files:**
- Modify: `service/WorkerService.java`(`fixRoundSucceeded` :837, `fixRoundFailed` :870)
- Test: `service/WorkerServiceFixRoundResultTest`

**Interfaces:**
- Consumes: `FixRounds.isBaseSync`, `FixRounds.baseSyncReturnStatus`, `FixRounds.close(t, req, outcome, now)`, `TaskCloseService.neverDeployed(t)`
- Produces: 없음(동작만).

- [ ] **Step 1: 실패하는 시험 작성** — 공통 준비: `IMPLEMENTING`, 열린 `BASE_SYNC` 회차(id 7, attempt 1, from=DEPLOYED), `head_branch="b"`, `head_sha="aaa1111"`, 보고는 `fixRequestId=7, fixAttempt=1, headBranch="b"`.

```java
@Test void 반영_성공_새_커밋이면_head갱신_자동재배포() {
    // PR_CREATED, headSha="bbb2222", deployUrl 있음
    // → status=DEPLOY_PENDING, headSha="bbb2222", activeFixRequestId 그대로(닫는 것은 배포 보고),
    //   이력 마지막 사유 "기준 브랜치 반영 1회차 구현 완료 → 자동 재배포"
}
@Test void 반영할_것이_없으면_직전상태로_회차는_NO_CHANGE() {
    // PR_CREATED, headSha="AAA1111"(대소문자만 다름)
    // → status=DEPLOYED, headSha 그대로, activeFixRequestId=null, 회차 outcome=NO_CHANGE,
    //   이력 사유 "기준 브랜치가 이미 반영돼 있음 → 배포완료 (기준 브랜치 반영 1회차 닫힘)", workerId·claimedAt null
}
@Test void 배포한_적_없는_DB작업은_재배포_없이_PR생성_회차는_SYNCED() {
    // taskType=DB_WORK, deployUrl=null, deployedAt=null, from=PR_CREATED, headSha="bbb2222"
    // → status=PR_CREATED, headSha="bbb2222", activeFixRequestId=null, outcome=SYNCED
}
@Test void 반영_실패는_head_sha를_바꾸지_않는다() {                     // Review Focus 1
    // IMPLEMENTATION_FAILED 보고, headSha="ccc3333"(사람이 올린 끝), failureReason="기준 브랜치 반영 1회차: 충돌 해결 실패 — …"
    // → status=IMPLEMENTATION_FAILED, headSha="aaa1111", activeFixRequestId=7
}
@Test void 닫힌_반영회차의_늦은_보고는_무시() { /* activeFixRequestId=null, fixRequestId=7 → 상태 그대로, 이력만 */ }
@Test void FIX회차_실패는_지금처럼_head_sha를_브랜치_끝으로() { /* 기존 동작 고정 */ }
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceFixRoundResultTest'` → 새 시험 FAIL.

- [ ] **Step 3: 구현** — `fixRoundSucceeded`: 브랜치·headSha 검증 뒤 `FixRounds.isBaseSync(round)`이면 (a) `req.headSha().strip().equalsIgnoreCase(t.getHeadSha())` → 구현 로그·사용량 기록, `FixRounds.close(…, NO_CHANGE, now)` + 저장, 상태 = `baseSyncReturnStatus`, 점유·실패 사유 비움 (b) `TaskCloseService.neverDeployed(t)` → head 갱신, `close(…, SYNCED)`, 상태 `PR_CREATED`, 이력 `"기준 브랜치 반영 n회차 완료: {sha7} → PR생성 (… 닫힘)"` (c) 그 밖 → 기존 길 그대로. `fixRoundFailed`: `isBaseSync(round)`이면 `setHeadSha`를 건너뛴다. 비교는 `setHeadSha` 전에 한다.

- [ ] **Step 4: 통과 확인** — Step 2 명령 → 전부 PASS.

- [ ] **Step 5: 커밋** — `feat: 기준 브랜치 반영 회차 결과 보고 — 변경 없음·DB 작업·실패 시 head_sha 유지`

---

### Task 5: 반영 중단·재시도 (API)

**Files:**
- Modify: `service/TaskFixService.java`(`abort` :157-175), `service/TaskService.java`(`requeueFixRound` :788-804)
- Test: `service/TaskFixServiceTest`, `service/TaskServiceFixRoundRetryTest`

**Interfaces:**
- Consumes: `FixRounds.isBaseSync`, `FixRounds.baseSyncReturnStatus`, `FixRounds.openRequest`
- Produces: 없음(동작만).

- [ ] **Step 1: 실패하는 시험 작성**

```java
// TaskFixServiceTest
@Test void 반영회차_중단은_직전상태로_돌아간다() {                        // Review Focus 1의 뒷부분
    // IMPLEMENTATION_FAILED, 열린 BASE_SYNC(from=DEPLOYED, round 1), deployContainerId·deployUrl 있음, headSha="aaa1111"
    Task t = service.abort(42L, "admin");
    assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYED);          // UNDEPLOY_PENDING·PR_CREATED 아님
    assertThat(t.getHeadSha()).isEqualTo("aaa1111");
    assertThat(t.getActiveFixRequestId()).isNull();
    assertThat(t.getFailureReason()).isNull();
    // 회차 outcome=ABORTED, 이력 "기준 브랜치 반영 1회차 중단 → 배포완료"
}
@Test void FIX회차_중단은_지금처럼_배포중지대기_또는_PR생성() { /* 기존 동작 고정 */ }
// TaskServiceFixRoundRetryTest
@Test void 반영회차_재시도는_요청_방식을_무시하고_DIRECT() {
    // 열린 BASE_SYNC, rawMode="REVIEW" → 회차 implementationMode=DIRECT, attempt+1, status=APPROVED
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.service.TaskFixServiceTest' --tests 'com.hamonsoft.netismaker.service.TaskServiceFixRoundRetryTest'` → 새 시험 FAIL.

- [ ] **Step 3: 구현** — `abort`: 닫기 전에 `FixRounds.openRequest(fixRepo, t)`로 kind를 읽고, `BASE_SYNC`면 `closeActive(…, ABORTED)` 뒤 `move(t, FixRounds.baseSyncReturnStatus(r, t), adminId, label + " 중단 → " + 상태.dbValue())`. 그 밖은 기존 코드. `requeueFixRound`: `FixRounds.isBaseSync(r)`이면 `after = ImplementationMode.DIRECT`(`parseFixMode` 호출 없음 — SDD가 와도 400이 아니다).

- [ ] **Step 4: 통과 확인** — Step 2 명령 → PASS.

- [ ] **Step 5: 커밋** — `feat: 기준 브랜치 반영 회차의 중단·재시도`

---

### Task 6: 워커 git — 두 브랜치 받기와 병합 (`BaseSyncMerger`)

**Files:**
- Create: `workerdaemon/BaseSyncMerger.java`
- Modify: `workerdaemon/GitRepoCache.java`(`fetchPatchCommits` :208 근처), `workerdaemon/GitOpsService.java`
- Test: 새 `workerdaemon/BaseSyncMergerTest`(실제 git + `@TempDir` — `WorktreeServiceFixBranchTest`의 bare 원격 준비를 따른다), `workerdaemon/GitRepoCacheTest`에 받기 시험

**Interfaces:**
- Produces:
  - `public BaseSyncRepo GitRepoCache.fetchForBaseSync(RepoRef ref, String headBranch, String baseBranch) throws IOException, InterruptedException` — 스스로 레포 락을 잡는다. `record BaseSyncRepo(File dir, String baseRef)`; `baseRef = "refs/netis-refs/" + baseBranch`. 공통 조상이 없으면 `GitRepoCache.NoCommonAncestorException extends IOException`(메시지 `공통 조상을 찾지 못함(이력 500커밋 초과)`).
  - `BaseSyncMerger`(상태 없음, `@Component` — git 작성자는 `WorkerProperties`의 `gitUserName()/gitUserEmail()`):
    - `enum Outcome { UP_TO_DATE, CLEAN, CONFLICTED }`
    - `record Started(Outcome outcome, List<String> conflicted, List<String> binaryConflicted, String indexSnapshot)`
    - `Started start(File worktreeDir, String baseRef) throws IOException, InterruptedException`
    - `List<String> unresolved(File worktreeDir, List<String> conflicted) throws IOException, InterruptedException`
    - `List<String> strayChanges(File worktreeDir, Started started) throws IOException, InterruptedException`
    - `String commitMerge(File worktreeDir, List<String> conflicted, String message) throws IOException, InterruptedException` — 새 HEAD sha
  - `public String GitOpsService.pushHead(File worktreeDir, RepoRef ref, String branchName) throws IOException, InterruptedException` — 기존 private `push` 호출 뒤 `headSha` 반환.

**알고리즘(시그니처와 시험만으로 정해지지 않는 부분):**

- `fetchForBaseSync`: 캐시가 없으면 `fetchOnly`와 같은 방식으로 만든다. 깊이 `d ∈ REF_FALLBACK_DEPTHS(50, 500)` 순서로 — 한 번의 `git fetch --no-tags --depth=d <authUrl> +refs/heads/<head>:refs/remotes/origin/<head> +refs/heads/<base>:refs/netis-refs/<base>`(`refGit` 통로) 뒤 `git merge-base refs/remotes/origin/<head> refs/netis-refs/<base>`가 exit 0이면 반환. 둘 다 실패면 `NoCommonAncestorException`. fetch 자체의 실패는 그대로 `IOException`(마스킹된 메시지).
- `start`: ① `git merge-base --is-ancestor <baseRef> HEAD` exit 0 → `UP_TO_DATE`(병합 실행 없음). ② `git -c user.name=… -c user.email=… merge --no-ff --no-commit <baseRef>`. ③ 병합 안 된 경로 = `git diff --name-only --diff-filter=U -z`(NUL 분리 — 공백·한글 경로를 따옴표 없이). 비었고 exit 0 → `CLEAN`. 비었는데 exit≠0 → `IOException("git merge 실패: " + 출력 끝 500자)`. 있으면 `CONFLICTED`. ④ `binaryConflicted` = 충돌 경로 중 `git diff --numstat -- <path>`의 추가·삭제가 `-`인 것(또는 파일 앞 8000바이트에 NUL). ⑤ `indexSnapshot` = `git ls-files -s -z` 출력에서 충돌 경로 항목을 뺀 문자열.
- `unresolved`: `git diff --name-only --diff-filter=U -z` 결과 ∪ 충돌 파일 중 줄 머리가 `<<<<<<< ` 또는 `>>>>>>> `인 줄이 있는 것. **`=======`만 있는 줄은 보지 않는다**(Markdown 제목 밑줄). 사라진 파일(한쪽 삭제를 받아들임)은 미해결이 아니다.
- `strayChanges`: (`git ls-files -s -z`에서 충돌 경로를 뺀 것이 `indexSnapshot`과 달라진 경로) ∪ (`git diff --name-only -z`) ∪ (`git ls-files --others --exclude-standard -z`) 에서 충돌 경로를 뺀 목록. 워커 exclude(`/.netis/` 등)는 `--exclude-standard`가 걸러 준다.
- `commitMerge`: `git add -A -- <conflicted…>`(목록이 비면 생략) → `git -c user.name=… -c user.email=… commit --no-edit -m <message>` → `git rev-parse HEAD`. 트리가 HEAD와 같아도 병합 중이면 커밋된다.

- [ ] **Step 1: 실패하는 시험 작성** (`BaseSyncMergerTest` — 준비 헬퍼: bare 원격에 `main`과 작업 브랜치 `task`를 만들고 worktree를 `task` 끝에 detached로 둔다)

```java
@Test void 기준브랜치가_이미_조상이면_UP_TO_DATE() { assertThat(merger.start(wt, BASE).outcome()).isEqualTo(Outcome.UP_TO_DATE); }
@Test void 겹치지_않는_변경이면_CLEAN이고_병합커밋의_부모는_둘() {
    Started s = merger.start(wt, BASE);
    assertThat(s.outcome()).isEqualTo(Outcome.CLEAN);
    String sha = merger.commitMerge(wt, List.of(), "merge: 기준 브랜치 main 반영 — 제목\n\ntask #42");
    assertThat(git(wt, "rev-list", "--parents", "-n", "1", sha).split(" ")).hasSize(3);
    assertThat(git(wt, "log", "-1", "--format=%an <%ae>")).isEqualTo("netisMaker <worker@example.com>");
}
@Test void 같은_파일을_양쪽이_고치면_CONFLICTED와_파일목록() {
    // main과 task가 둘 다 PLAN.md 1행을 바꿈
    assertThat(s.outcome()).isEqualTo(Outcome.CONFLICTED);
    assertThat(s.conflicted()).containsExactly("PLAN.md");
    assertThat(merger.unresolved(wt, s.conflicted())).containsExactly("PLAN.md");
}
@Test void 표식을_지우면_해결로_보고_제목_밑줄_등호줄은_표식이_아니다() {          // Review Focus 2
    // PLAN.md를 "제목\n=======\n본문\n"으로 덮어씀(add 안 함)
    assertThat(merger.unresolved(wt, List.of("PLAN.md"))).isEmpty();
    String sha = merger.commitMerge(wt, List.of("PLAN.md"), "merge: …");
    assertThat(git(wt, "status", "--porcelain")).isEmpty();
}
@Test void 표식이_남아_있으면_미해결() { /* "<<<<<<< HEAD" 줄을 남김 → containsExactly("PLAN.md") */ }
@Test void 공백과_한글이_든_경로도_그대로_나온다() {                                 // Review Focus 3
    // "docs/작업 기록.md" 충돌
    assertThat(s.conflicted()).containsExactly("docs/작업 기록.md");
}
@Test void 바이너리_충돌은_binaryConflicted에_잡힌다() {                             // Review Focus 4
    // 양쪽이 img.bin(NUL 포함)을 다르게 바꿈
    assertThat(s.binaryConflicted()).containsExactly("img.bin");
}
@Test void 충돌파일_밖을_고치면_strayChanges에_나온다() {
    // 충돌 뒤 README.md 수정(미스테이징), other.txt 수정 후 git add, new.txt 생성
    assertThat(merger.strayChanges(wt, s)).containsExactlyInAnyOrder("README.md", "other.txt", "new.txt");
}
@Test void 충돌파일만_고치면_strayChanges는_비어_있다() { }
```

`GitRepoCacheTest`:

```java
@Test void fetchForBaseSync_깊이1_캐시에서도_공통조상을_확보한다() {
    // 원격: main에 3커밋 뒤 task 분기 + 각자 2커밋. 캐시는 --depth=1 clone
    BaseSyncRepo r = cache.fetchForBaseSync(ref, "task", "main");
    assertThat(r.baseRef()).isEqualTo("refs/netis-refs/main");
    assertThat(exit(r.dir(), "merge-base", "refs/remotes/origin/task", r.baseRef())).isZero();
    assertThat(git(r.dir(), "rev-parse", "HEAD")).isEqualTo(cacheHeadBefore);       // 캐시 HEAD 그대로
}
@Test void fetchForBaseSync_무관한_이력이면_NoCommonAncestorException() { /* base가 orphan 브랜치 */ }
```

`GitOpsServicePushTest`: `pushHead`가 원격 브랜치 끝을 HEAD로 올리고 그 sha를 돌려주며, 원격이 앞서 있으면 기존 push와 같은 예외.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.BaseSyncMergerTest'` → 컴파일 오류.

- [ ] **Step 3: 구현** — 위 인터페이스·알고리즘대로. git 실행은 `ProcessRunner.run`/`requireSuccess`(시간 상한은 `GitRepoCache.GIT_TIMEOUT_SECONDS`와 같은 값), NUL 분리 출력은 `\0`로 나눈다.

- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.hamonsoft.netismaker.workerdaemon.GitRepoCacheTest' --tests 'com.hamonsoft.netismaker.workerdaemon.GitOpsServicePushTest'` → PASS.

- [ ] **Step 5: 커밋** — `feat: 워커 — 기준 브랜치 병합기(BaseSyncMerger)와 공통 조상 확보`

---

### Task 7: 워커 흐름 — `syncBaseRound`와 프롬프트

**Files:**
- Create: `workerdaemon/BaseSyncPrompts.java`
- Modify: `workerdaemon/WorkerMainLoop.java`(분기 :318-327, 생성자에 `BaseSyncMerger`), `workerdaemon/WorkerHttpClient.java`(:143)
- Test: 새 `workerdaemon/WorkerMainLoopBaseSyncTest`(Mockito — `WorkerMainLoopFixRoundTest`의 연결 방식 그대로, `BaseSyncMerger`도 mock), 새 `workerdaemon/BaseSyncPromptsTest`, `workerdaemon/WorkerHttpClientNextTaskTest`, `controller/InterviewConfirmMockupFlowIntegrationTest`(:154 `SUPPORTS_ALL` 사본)

**Interfaces:**
- Consumes: Task 1 `FixRoundSpec.kind()`, Task 6 전부, 기존 `worktrees.createOnBranch`, `gitOps.headSha`, `worktrees.ensureWorkerExcludes`, `claude.exec(prompt, dir, limit, extras, true, model, effort, ExecOptions)`, `safePostImplementationFailure(task, attempt, reason, headBranch, headSha, log, usage)`, `withFixMarks`, `reporter.reportTerminal`, `isUsableFixBranch`, `isPushRejected`, `PUSH_REJECTED_NOTE`, `FixRoundPrompts.remoteAhead/remoteAheadNote`
- Produces:
  - `static boolean BaseSyncPrompts.isBaseSync(FixRoundSpec fix)` — `fix != null && "BASE_SYNC".equals(fix.kind())`
  - `static String BaseSyncPrompts.label(FixRoundSpec fix)` — `"기준 브랜치 반영 " + fix.round() + "회차"`
  - `static String BaseSyncPrompts.commitMessage(WorkerTaskResponse task, String gitUserEmail)` — `"merge: 기준 브랜치 " + task.githubBranch() + " 반영 — " + task.title() + "\n\ntask #" + task.id() + "\n\nCo-Authored-By: netisMaker <" + email + ">"`
  - `static String BaseSyncPrompts.render(WorkerTaskResponse task, FixRoundSpec fix, String tipSha, List<String> conflicted)`
  - `WorkerHttpClient.SUPPORTS = "refRepos,fixRounds,dbWrite,mockups,baseSync"`
  - `private void WorkerMainLoop.syncBaseRound(WorkerTaskResponse task, FixRoundSpec fix, int attempt, Duration claudeLimit) throws Exception`

**프롬프트 본문(고정 문구 — `render`가 만드는 글, `%s`는 차례로 작업 브랜치·끝 커밋 7자·기준 브랜치·충돌 파일 목록(`- 경로` 줄들)):**

```text
당신은 git 병합 충돌을 푸는 전문가입니다. 현재 디렉토리는 이 작업 전용 git worktree이고, 작업 브랜치 '%s'(끝 커밋 %s)에
기준 브랜치 '%s'를 병합하다가 충돌로 멈춘 상태입니다. 충돌하지 않은 변경은 이미 병합되어 스테이징돼 있습니다.

## 할 일 — 아래 충돌 파일의 충돌만 푸세요
%s
- 충돌 표식(<<<<<<<, =======, >>>>>>>)이 있는 곳만 고치고, 표식은 모두 지우세요.
- 양쪽 변경의 의도를 모두 살리세요: 이 작업의 변경(아래 "이 작업")과 기준 브랜치에 먼저 들어온 다른 작업의 변경 둘 다 남아야 합니다.
- 작업 기록 문서(PLAN.md처럼 작업마다 통째로 다시 쓰는 파일)는 한쪽을 버리지 마세요. 기준 브랜치 쪽 내용을 그대로 두고 그 아래에 이 작업의 내용을 덧붙이세요.
- 위 목록에 없는 파일은 건드리지 마세요. 리팩터링·이름 바꾸기·포맷 정리도 하지 마세요.
- git add는 해도 되지만 commit·push·rebase·reset·checkout·switch·`merge --abort`는 하지 마세요 — 워커가 마지막에 병합 커밋을 만들고 push합니다.
- 현재 디렉토리에서 직접 파일을 편집하세요 (cd 금지).
- 어느 쪽을 살려야 할지 판단할 수 없는 충돌은 고치지 말고 그대로 둔 채 그 이유를 출력에 남기세요.
- 종료 직전에 `git status`로 상태를 출력하세요.

## 이 작업 (참고용 — 다시 구현하지 마세요)
```

그 뒤에 `### 제목`·`### 요구사항`·(있으면) `### 확정된 구현 계획`을 `FixRoundPrompts`의 "원래 작업"과 같은 모양으로 붙인다. 리뷰 절·배포 env 지시·DB 절·참조 절·디자인·목업은 붙이지 않는다.

**`syncBaseRound` 순서(실패 보고의 사유는 모두 `label + ": "`로 시작, 보고의 `headBranch=branch`):**

1. `isUsableFixBranch` 실패 → `"…: 반영할 브랜치가 비었거나 기준 브랜치와 같음(…) — 진행하지 않음"`, headSha null.
2. `repos.fetchForBaseSync(task.repoRef(), branch, task.githubBranch())` — `NoCommonAncestorException` → `"…: 공통 조상을 찾지 못함(이력 500커밋 초과) — GitLab에서 직접 해결하세요"`, 그 밖 → `"…: 레포 fetch 실패: " + 메시지`.
3. `worktrees.createOnBranch` → `tip = gitOps.headSha(wt.dir())` → `worktrees.ensureWorkerExcludes`. `remoteAhead(tip, fix.headSha())`면 구현 로그 머리에 `remoteAheadNote` 한 줄.
4. `merger.start(wt.dir(), baseRef)`:
   - `UP_TO_DATE` → push·Claude 없이 성공 보고(`headSha = tip`, 로그 `"기준 브랜치 " + base + "가 이미 반영돼 있습니다."`).
   - `CLEAN` → `commitMerge(wt, List.of(), message)` → `gitOps.pushHead` → 성공 보고(로그 `"충돌 없이 병합했습니다."`).
   - `CONFLICTED`이고 `binaryConflicted`가 비지 않음 → Claude 없이 실패 `"…: 바이너리 파일 충돌 — " + 목록 + " — GitLab에서 직접 해결하세요"`, headSha=tip.
   - `CONFLICTED` → 5.
5. `claude.exec(BaseSyncPrompts.render(…), wt.dir(), claudeLimit, List.of(), true, task.model(), task.effort(), new ExecOptions(List.of(), gitAuthorEnv(…)))` — 작업별 MCP·DB 서버를 넘기지 않는다(워커 기본 MCP 합본은 `ClaudeExecAdapter`가 늘 붙이는 그대로 — 스펙 §6-5의 "MCP 없음"은 작업 extras·DB 기준으로 구현한다). 예외/exit≠0 → 실패 `"…: claude exec 실패: …"` / `"…: claude exit=N\n" + tail(stdout, 4000)`, headSha=tip, 사용량 포함.
6. `unresolved`가 비지 않음 → 실패 `"…: 충돌 해결 실패 — 남은 충돌 파일: a, b"`. `strayChanges`가 비지 않음 → 실패 `"…: 충돌 파일 밖을 수정함 — a, b"`. 둘 다 구현 로그에 Claude 출력 포함.
7. `commitMerge(wt, conflicted, message)` → `gitOps.pushHead`. push 거절(`isPushRejected`) → 실패 `"…: push 실패: …" + PUSH_REJECTED_NOTE`.
8. 성공 보고: `implementFixRound`의 6단계와 같은 `WorkerResultRequest`(상태 `PR_CREATED`, `headBranch=branch`, 새 `headSha`, `envTemplate=null`, `refWarnings=null`, `withFixMarks`), 구현 로그 = `"[" + label + "] 충돌 파일: a, b\n\n" + Claude 출력`. 그 뒤 `worktrees.discard(repoKey, WorktreeKind.TASK, task.id())`. 실패 경로에서는 worktree를 남긴다.

어느 실패 경로에서도 `gitOps.preservePartialWork`·`commitAndPush`를 부르지 않는다.

- [ ] **Step 1: 실패하는 시험 작성**

`BaseSyncPromptsTest`: `render` 결과가 위 고정 문구(값 대입 후)로 **시작**하고 `- PLAN.md`·`- docs/작업 기록.md` 줄, `### 제목`, 계획 본문을 담으며 `리뷰`·`.netis-deploy-env.json`·`mcp__db-`를 담지 않는다. `commitMessage`·`label`·`isBaseSync`(kind null·`"FIX"` → false) 값 시험.

`WorkerMainLoopBaseSyncTest`(claim: `fix = new FixRoundSpec(7L, 1, 1, "기준 브랜치 main 반영", List.of(), BRANCH, TIP, "BASE_SYNC")`):

```java
@Test void 이미_반영돼_있으면_Claude와_push_없이_tip으로_성공보고() {
    // merger.start → UP_TO_DATE
    // verify(claude, never()).exec(…); verify(gitOps, never()).pushHead(…);
    // 보고: status=PR_CREATED, headSha=TIP, fixRequestId=7, fixAttempt=1
}
@Test void 충돌_없으면_Claude_없이_병합커밋_push_성공보고() { /* CLEAN → commitMerge(wt, [], msg), pushHead → "merged1" */ }
@Test void 충돌이면_Claude가_풀고_병합커밋_push() {
    // CONFLICTED ["PLAN.md"], unresolved → [], strayChanges → []
    // 프롬프트에 "- PLAN.md", extras는 빈 목록, 구현 로그가 "[기준 브랜치 반영 1회차] 충돌 파일: PLAN.md"로 시작
    // verify(merger).commitMerge(eq(wt), eq(List.of("PLAN.md")), startsWith("merge: 기준 브랜치 main 반영 — "));
    // verify(worktrees).discard(…)
}
@Test void 충돌이_남으면_구현실패이고_push하지_않는다() {
    // unresolved → ["PLAN.md"] → failureReason "기준 브랜치 반영 1회차: 충돌 해결 실패 — 남은 충돌 파일: PLAN.md", headSha=TIP
    // verify(gitOps, never()).pushHead(…); verify(gitOps, never()).preservePartialWork(…); verify(worktrees, never()).discard(…)
}
@Test void 충돌파일_밖을_고치면_구현실패() { /* strayChanges → ["README.md"] → "…: 충돌 파일 밖을 수정함 — README.md" */ }
@Test void 바이너리_충돌은_Claude_없이_구현실패() { /* Review Focus 4 */ }
@Test void 공통조상이_없으면_구현실패() { /* fetchForBaseSync → NoCommonAncestorException */ }
@Test void push가_거절되면_구현실패_사유에_거절_안내() { }
@Test void FIX회차는_지금처럼_implementFixRound로_간다() { /* kind="FIX" → verify(merger, never()).start(…), verify(repos).fetchOnly(…) */ }
```

`WorkerHttpClientNextTaskTest`·`InterviewConfirmMockupFlowIntegrationTest`: 기대 문자열을 `refRepos,fixRounds,dbWrite,mockups,baseSync`로.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopBaseSyncTest' --tests 'com.hamonsoft.netismaker.workerdaemon.BaseSyncPromptsTest'` → 컴파일 오류.

- [ ] **Step 3: 구현** — 분기: `if (fix != null)` 블록 안에서 `BaseSyncPrompts.isBaseSync(fix) ? syncBaseRound(task, fix, attempt, claudeLimit) : implementFixRound(…)`(`finally`의 참조 worktree 정리는 공통). `WorkerMainLoop` 클래스 javadoc의 회차 설명 아래에 반영 회차 한 단락. 생성자 인자가 늘어 깨지는 기존 시험 생성부(`WorkerMainLoopFixRoundTest` 등 `new WorkerMainLoop(`)를 고친다.

- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.hamonsoft.netismaker.workerdaemon.WorkerMainLoopFixRoundTest' --tests 'com.hamonsoft.netismaker.workerdaemon.WorkerHttpClientNextTaskTest' --tests 'com.hamonsoft.netismaker.workerdaemon.FixRoundPromptsTest'` → PASS.

- [ ] **Step 5: 커밋** — `feat: 워커 — 기준 브랜치 반영 회차 실행(syncBaseRound)`

---

### Task 8: 종료 병합 — 계산 대기와 문구

**Files:**
- Modify: `workerdaemon/GitLabMrCloser.java`(:45-46, :83-112, :222-232), `workerdaemon/GitHubPrCloser.java`(:80-86), `TODOS.md`
- Test: `workerdaemon/GitLabMrCloserTest`, GitHub 쪽 기존 시험 클래스(`GitHubPrCloserTest` — 문구 단언)

**Interfaces:**
- Produces(패키지 안 상수): `GitLabMrCloser.MERGEABILITY_POLL_ATTEMPTS = 10`, `MERGEABILITY_POLL_MILLIS = 2000`, `static final Set<String> COMPUTING = Set.of("checking", "unchecked", "preparing")`, `MergeRequestCloser.CONFLICT_REFUSED = "충돌: 기준 브랜치와 충돌합니다 — 작업 상세의 '기준 브랜치 반영'으로 해결하세요"`, `MergeRequestCloser.HEAD_MISMATCH_HINT = " — '종료 취소' 뒤 재배포(또는 '기준 브랜치 반영')로 새 커밋을 확인하고 다시 종료하세요"`, 거부 문구 `"병합 가능 여부 확인 중 — 잠시 뒤 재시도하세요"`.

- [ ] **Step 1: `COMPUTING` 값 확인** — 사내 GitLab 버전을 `GET {GITLAB_BASE_URL}/api/v4/version`으로 읽고(토큰은 `PRIVATE-TOKEN` 헤더로만, 출력에 남기지 않는다) 그 버전의 Merge requests API 문서 "Merge status" 표에서 "아직 계산 중"을 뜻하는 `detailed_merge_status` 값을 확인해 `COMPUTING`을 확정한다. `checking`은 작업 42 실패 로그(2026-11-13 14:51:14 `병합 거부(checking) (HTTP 422)`)로 확인된 값이다. 문서와 다르면 문서를 따르고 커밋 메시지에 버전을 적는다.

- [ ] **Step 2: 실패하는 시험 작성** (`GitLabMrCloserTest` — 기존 `route`·`mr(...)`·`sleeps` 헬퍼 사용)

```java
@Test void 계산중이면_기다렸다가_병합한다() {
    // GET MR 큐: checking, checking, mergeable → PUT merge 200 merged
    assertThat(closer.merge(ref(), 10, SHA, "merge")).isEqualTo(MERGE_SHA);
    assertThat(sleeps).containsExactly(2000L, 2000L);
}
@Test void 계산이_안_끝나면_병합요청_없이_거부() {
    // GET MR: 계속 checking
    assertThatThrownBy(() -> closer.merge(ref(), 10, SHA, "merge"))
        .isInstanceOf(CloseRefusedException.class).hasMessage("병합 가능 여부 확인 중 — 잠시 뒤 재시도하세요");
    assertThat(sleeps).hasSize(10);
    assertThat(seen).noneMatch(s -> s.startsWith("PUT"));
}
@Test void 계산이_끝나_충돌이면_새_문구로_거부() {
    // GET MR: checking → conflicted()
    // hasMessage("충돌: 기준 브랜치와 충돌합니다 — 작업 상세의 '기준 브랜치 반영'으로 해결하세요")
}
@Test void draft면_해제_뒤에_계산중을_기다리고_충돌이면_제목을_되돌린다() {
    // GET MR: draft=true, detailed=draft_status, has_conflicts=false
    // PUT title 200 (draft=false, detailed=checking) → GET: checking → conflicted()
    // → 충돌 거부, seen 마지막이 원래 제목으로의 PUT, "PUT …/merge" 없음
}
@Test void head_불일치_문구에_다음_행동_안내() { /* endsWith(HEAD_MISMATCH_HINT) */ }
@Test void classify_계산중과_리베이스() {
    assertThat(GitLabMrCloser.classify("checking")).isEqualTo("병합 가능 여부 확인 중");
    assertThat(GitLabMrCloser.classify("need_rebase")).isEqualTo("리베이스 필요 — 작업 상세의 '기준 브랜치 반영'으로 해결하세요");
    assertThat(GitLabMrCloser.classify("conflict")).isEqualTo("충돌");
}
```

- [ ] **Step 3: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.GitLabMrCloserTest'` → 새 시험 FAIL.

- [ ] **Step 4: 구현** — `merge`에서 sha 검사 뒤 `mr = awaitMergeability(base, mr)`(`COMPUTING`인 동안 `sleeper.sleep(2000)` 후 재조회, 10회 뒤에도 계산 중이면 거부). draft였다면 해제 PUT 응답 본문으로 같은 대기를 한 번 더 하고, 그 뒤 계산 중이거나 `has_conflicts`면 **제목을 원복한 뒤** 거부(기존 `restoreTitle`의 실패 안내 덧붙임 규칙 그대로). target 브랜치 없음 판정은 지금 자리 그대로. GitHub: 충돌 문구를 `CONFLICT_REFUSED`로, head 불일치 두 곳(GitLab :85·GitHub :80)에 `HEAD_MISMATCH_HINT`. `TODOS.md`에 한 줄: `GitHubPrCloser — mergeStateStatus UNKNOWN(계산 중) 대기 없음, GitLab은 2026-11-13에 추가`.

- [ ] **Step 5: 통과 확인** — Step 3 명령 + `--tests 'com.hamonsoft.netismaker.workerdaemon.GitHubPrCloserTest'` → PASS(옛 문구를 단언하던 기존 시험은 새 문구로 고친다).

- [ ] **Step 6: 커밋** — `fix: 종료 병합 — GitLab 병합 가능 여부 계산을 기다리고 실패 문구가 다음 행동을 안내`

---

### Task 9: 화면

**Files:**
- Modify: `frontend/composables/taskFix.ts`, `frontend/pages/tasks/[id].vue`(:399-408, :458-465, :583, :594-599, :651-658, :860-868, :1087-1106, :1483-1491, :1660-1668), `frontend/components/tasks/FixRequestHistory.vue`(:35-39, :58-74), `frontend/components/tasks/ImplementationRetryDialog.vue`(:17-25, :36, :59-65, :89-99), `frontend/scripts/hydration-smoke.mjs`(:86, :105, :173-176)
- Test: `frontend/composables/taskFix.spec.ts`, `frontend/test/task-detail-close.spec.ts`, `frontend/test/task-detail-fix.spec.ts`, `frontend/test/task-detail-implementation-retry.spec.ts`

**Interfaces:**
- Consumes: API `FixView.activeKind`, `FixRequestView.kind`, `POST /api/tasks/{id}/close/sync-base`
- Produces (`taskFix.ts`):
  - `type FixKind = 'FIX' | 'BASE_SYNC'`; `FixView.activeKind: FixKind | null`; `FixRequestView.kind: FixKind`
  - `canSyncBase(task: { status: string; close?: { progress?: string[] | null } | null; fix?: FixView | null }, isAdmin: boolean): boolean` — `isAdmin && status === 'CLOSE_FAILED' && !progress.includes('MERGED') && fix?.activeRound == null`
  - `isBaseSyncRound(task: { fix?: FixView | null }): boolean` — `activeRound != null && activeKind === 'BASE_SYNC'`
  - `fixRoundTitle(r: { kind?: FixKind | null; round: number | null }): string` — `BASE_SYNC`면 `기준 브랜치 반영 ${round}회차`, 그 밖은 `${round}회차`
  - `fixOutcomeLabel(outcome, kind?: FixKind | null)` — `BASE_SYNC`: `DEPLOYED '재배포됨'`·`DEPLOY_FAILED '배포 실패'`·`NO_CHANGE '변경 없음'`·`SYNCED '반영됨'`·`ABORTED '중단'`; `FIX`·생략은 지금 값
  - `SYNC_BASE_CONFIRM(base: string): string` — `종료를 취소하고 작업 브랜치에 기준 브랜치(${base})를 반영합니다. 충돌이 있으면 Claude가 해결하고, 끝나면 자동으로 다시 배포됩니다. 배포 결과를 확인한 뒤 종료를 다시 요청해야 합니다.`
  - `BASE_SYNC_ABORT_CONFIRM = '기준 브랜치 반영을 중단하고 종료 요청 전 상태로 되돌립니다. 작업 브랜치는 바뀌지 않습니다.'`

- [ ] **Step 1: 실패하는 시험 작성**

`taskFix.spec.ts`: `canSyncBase`(관리자·CLOSE_FAILED·병합 전 → true; `progress: ['MERGED']`·비관리자·다른 상태·열린 회차 → false), `isBaseSyncRound`, `fixRoundTitle`, `fixOutcomeLabel('NO_CHANGE', 'BASE_SYNC') === '변경 없음'`, `fixOutcomeLabel('ABORTED') === '수정 중단'`(기존 유지).

`task-detail-close.spec.ts`:

```ts
it('병합 전 종료실패에서 관리자에게 기준 브랜치 반영 버튼이 보이고, 확인하면 close/sync-base를 부른다', async () => {
  // task: status CLOSE_FAILED, close.progress [], githubBranch 'main', fix: { activeRound: null, activeKind: null, … }
  // expect(find('[data-test="close-sync-base"]').exists()).toBe(true)
  // 클릭 → confirmDialog 문구 === SYNC_BASE_CONFIRM('main') → POST '/api/tasks/42/close/sync-base'
})
it('병합된 뒤에는 버튼이 없다', …)          // progress ['MERGED']
it('비관리자에게는 버튼이 없다', …)
```

`task-detail-fix.spec.ts`: 열린 `BASE_SYNC` 회차에서 ① 배너 문구 `기준 브랜치 반영 1회차 진행 중 — 끝나면 자동으로 다시 배포됩니다` ② `IMPLEMENTATION_FAILED`의 중단 버튼 라벨 `반영 중단`·확인 문구 `BASE_SYNC_ABORT_CONFIRM` ③ 이력 카드에서 `kind: 'BASE_SYNC', round: 1, outcome: 'NO_CHANGE'` 행이 `기준 브랜치 반영 1회차`·`변경 없음`으로 보이고 방식 칩이 없다.

`task-detail-implementation-retry.spec.ts`: `fixKind='BASE_SYNC'`면 제목 `기준 브랜치 반영 1회차 재시도`, 안내 `기준 브랜치 반영을 다시 시도합니다 (같은 브랜치)`, 방식 선택(`ImplementationModePicker`)이 없고 제출 바디에 `implementationMode`가 없다.

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run composables/taskFix.spec.ts test/task-detail-close.spec.ts test/task-detail-fix.spec.ts test/task-detail-implementation-retry.spec.ts` → 새 시험 FAIL.

- [ ] **Step 3: 구현**
  - `[id].vue`: `canSyncBaseNow = computed(() => !!task.value && canSyncBase(task.value, auth.isAdmin))`, `syncBase()`(= `confirmDialog($q, …)` 뒤 `closeAction('close/sync-base', '기준 브랜치 반영을 시작했습니다')`), `CLOSE_ACTION_NAMES`에 `'close/sync-base': '기준 브랜치 반영'`. 데스크톱 버튼은 종료 취소 버튼(:860) 옆 `data-test="close-sync-base"` 라벨 `기준 브랜치 반영`, 모바일 `bar-more` 메뉴 항목 `data-test="bar-more-close-sync-base"`(종료 취소 항목 위). `fixBannerText`·`abortFix`·중단 버튼 라벨·메뉴 라벨은 `isBaseSyncRound(task)`로 가른다. `ImplementationRetryDialog`에 `:fix-kind="task.fix?.activeKind ?? null"`.
  - `FixRequestHistory.vue`: 행 제목을 `fixRoundTitle(r)`로, 결과 칩을 `fixOutcomeLabel(r.outcome, r.kind)`로, `OUTCOME_COLOR`에 `NO_CHANGE: '#616161'`, `SYNCED: '#2e7d32'`, `BASE_SYNC` 행은 방식 칩 숨김.
  - `ImplementationRetryDialog.vue`: prop `fixKind?: FixKind | null`, `BASE_SYNC`면 방식 선택 숨김·바디에서 `implementationMode` 생략.
  - `hydration-smoke.mjs`: fix 목에 `activeKind: null`, fix-requests 목 행에 `kind: 'FIX'`.

- [ ] **Step 4: 통과 확인** — Step 2 명령 → PASS. 이어서 `npx vitest run`(전체) → PASS, `npm run lint` → 오류 0·경고 수 증가 없음.

- [ ] **Step 5: 커밋** — `feat: 작업 상세 — 기준 브랜치 반영 버튼·이력·재시도/중단 문구`

---

### Task 10: 문서·전체 확인

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: `CLAUDE.md` 갱신** — ① "작업 상태머신"의 수정 요청 그림 아래에 스펙 §4 그림 ② "수정 요청" 절 끝에 `기준 브랜치 반영(2026-11-13, 스펙 …)` 단락: 열기 조건·`kind`·상한 3(따로 셈)·`supports=baseSync` 가드·워커 흐름 한 문단·실패는 `head_sha`를 바꾸지 않음·`반영 중단`은 직전 상태·배포 순서(API V45 → 워커 3대 → 프론트) ③ "종료" 절의 병합 설명에 계산 대기(2초×10) 한 문장 ④ "자주 보는 코드" 표에 한 행(`TaskCloseService.syncBase`, `FixRounds`, `WorkerService`(결과 갈래), `workerdaemon/BaseSyncMerger`·`BaseSyncPrompts`·`WorkerMainLoop.syncBaseRound`·`GitRepoCache.fetchForBaseSync`, `GitLabMrCloser`, 프론트 `composables/taskFix.ts`).

- [ ] **Step 2: 백엔드 전체 시험** — `./gradlew test` → 실패 0(이 PC에서 건너뛰는 Testcontainers·심볼릭 링크 시험은 skipped). 부하로 `ProcessRunnerTimeoutTest`가 "Failed to close extension context"로 실패하면 단독 재실행으로 확인.

- [ ] **Step 3: 프론트 전체** — `cd frontend && npx vitest run` → PASS.

- [ ] **Step 4: graphify 갱신** — `graphify update .`(레포 규칙). 명령이 없으면 건너뛰고 보고에 적는다.

- [ ] **Step 5: 커밋** — `docs: CLAUDE.md — 기준 브랜치 반영 회차`

---

## 배포(이 계획의 범위 밖 — 사용자 승인 뒤 운영자가)

PR 병합 → 본 체크아웃 `git pull --ff-only` → API(V45) → 워커 3대(이 PC·10.1.1.75·Mac, 진행 중 구현·배포·종료가 없을 때 — claim URL에 `baseSync` 확인) → 프론트(`.output` 재빌드). 첫 실사용은 작업 42(아직 `종료실패`면)로, 관리자가 지켜본다.
