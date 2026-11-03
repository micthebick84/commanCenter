# 수정 요청(같은 브랜치 이어 구현 + 자동 재배포) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포가 끝난 작업에 등록자·관리자가 "수정 요청"을 내면, 관리자 승인 뒤 같은 브랜치 위에서 이어 구현하고, 끝나면 저장된 env로 자동 재배포한다.

**Architecture:** API에 수정 요청 기록(`com.task_fix_request`)과 새 상태 `수정승인대기`를 더한다. 승인되면 작업이 `구현대기`로 가고 `task.active_fix_request_id`가 열린 회차를 가리킨다. 구현 claim에 회차 정보(`FixRoundSpec`)를 싣고, 새 워커만(`supports=fixRounds`) 받는다. 워커는 `origin/{head_branch}`에서 worktree를 만들어 고치고 같은 브랜치에 fast-forward push만 한다. API는 성공 보고를 받으면 `PR생성`에 머물지 않고 `배포대기`로 넘기고, 배포 결과가 오면 회차를 닫는다.

**Tech Stack:** Java 21 · Spring Boot 3 · JPA/Flyway(PostgreSQL, 스키마 `com`) · Mockito/JUnit 5 · Testcontainers · Nuxt 3 + Quasar · vitest

**Spec:** `docs/superpowers/specs/2026-11-03-deploy-e2e-reverify-design.md` — 이 계획은 §4, §6, §7, §8(수정 요청 부분), §9(수정 요청 화면), §10의 `fix-request-max-rounds`, §11·§12 중 수정 요청 부분을 구현한다. 재검증(§5, 카탈로그 재검증 칸, `task_verification`, 재검증 워커, 재검증 결과로 본문 미리 채우기)은 **다음 계획**이다.

## Global Constraints

- 새 상태 DB 값은 `수정승인대기`(enum `FIX_REVIEW`). 컬럼은 VARCHAR(30)이고 CHECK 제약이 없어서 상태 값 마이그레이션은 필요 없다.
- 수정 요청을 받는 상태: `배포완료`·`배포중단됨`·`배포실패`. 그 밖에 `head_branch`·`head_sha`가 있어야 하고, 열린 회차가 없어야 하며, 소프트 삭제되지 않은 작업이어야 한다.
- 수정 요청 권한: 등록자·관리자. 등록자 요청 → `수정승인대기` → 관리자 승인. 관리자 요청은 바로 승인한다.
- 회차 상한 `app.task.fix-request-max-rounds`(기본 3). 승인된 요청 수로 센다. 반려·철회는 세지 않는다.
- 수정 회차 구현 방식은 `DIRECT`·`REVIEW`만 된다. 기본 `REVIEW`, `SDD`는 400.
- 작업의 `implementation_mode`·`implementation_attempt`·브랜치 `-rN` 규칙은 수정 회차에서 바꾸지 않는다.
- 워커 push는 `HEAD:refs/heads/{head_branch}`만 한다. `--force` 금지, PR/MR 새로 만들기 금지.
- 자동 재배포는 저장된 `task.envVars`를 그대로 쓴다. 이력 사유는 `수정 {n}회차 구현 완료 → 자동 재배포`.
- 수정 회차가 열려 있는 동안(`수정승인대기`, 회차의 `구현대기`·`구현중`·`구현실패`·`배포대기`·`배포중`)에는 컨테이너 GC 보호와 소프트 삭제 금지를 건다.
- 화면 확인 창은 `confirmDialog($q)`를 쓴다. `window.confirm`은 금지.
- 시험은 이 worktree에서만 돌린다(라이브 main 체크아웃 금지). `gradlew --stop` 금지.
  - 단일 클래스 실행: `.\gradlew.bat test --tests <FQCN>`
  - Testcontainers 시험(`RUN_TESTCONTAINERS=true`)은 이 PC에 Docker가 없어서 CI(Backend)에서 돈다.
  - 프론트: `cd frontend; npx vitest run <spec>`. `npm run lint` 경고를 늘리지 않는다.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.

## Review Focus

1. 사람이 배포 뒤 MR에 직접 push해 원격 브랜치 끝이 `head_sha`보다 앞선 경우. 워커는 원격 끝 위에서 고치고, 그 사실을 구현 로그에 남겨야 한다(Task 6 시험).
2. Claude가 "고칠 게 없다"며 커밋을 하나도 만들지 않은 경우. `구현실패` 사유가 "변경 없음"으로 분명해야 하고, 관리자가 `수정 중단`으로 빠져나올 수 있어야 한다(Task 6·Task 5 시험).
3. 등록자가 수정 요청을 두 번 연달아 보낸 경우. 두 번째는 409이고 요청 기록은 하나만 남는다(Task 2 시험).
4. 수정 회차 중 워커가 재기동되거나 hung된 시도의 늦은 보고가 온 경우. 브랜치·PR 정보가 지워지지 않고, 이전 시도 보고는 무시된다(Task 5 시험).
5. 직접 구현은 DIRECT(판정 60분)로 했는데 수정 회차를 REVIEW(상한 80분)로 돌리는 경우. stale 판정이 작업 방식이 아니라 회차 방식을 따라야 한다. 그러지 않으면 정상 구현이 상한 전에 회수된다(Task 5 시험).

---

### Task 1: 수정 요청 데이터·상태 기초

**Files:**
- Create: `src/main/resources/db/migration/V38__task_fix_request.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/TaskFixRequest.java`, `entity/FixRequestStatus.java`, `entity/FixOutcome.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/TaskFixRequestRepository.java`
- Modify: `entity/TaskStatus.java:55-84`(+ 상단 상태도 주석), `entity/Task.java`(필드)
- Test: `src/test/java/com/hamonsoft/netismaker/entity/TaskStatusFixReviewTest.java`

**Interfaces:**
- Produces:
  - `TaskStatus.FIX_REVIEW` (dbValue `"수정승인대기"`)
  - `Task.getActiveFixRequestId()` / `setActiveFixRequestId(Long)`
  - `enum FixRequestStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }`
  - `enum FixOutcome { DEPLOYED, DEPLOY_FAILED, ABORTED }`
  - `TaskFixRequest` 필드(모두 getter·setter): `Long id`, `Long taskId`, `Integer round`, `String body`, `FixRequestStatus status`, `ImplementationMode implementationMode`, `TaskStatus fromStatus`, `String requestedBy`, `OffsetDateTime requestedAt`, `String decidedBy`, `OffsetDateTime decidedAt`, `String decisionReason`, `int attempt`(기본 1), `FixOutcome outcome`, `OffsetDateTime closedAt`
  - 정적 생성자 `TaskFixRequest.pending(Long taskId, String body, TaskStatus fromStatus, String requestedBy)`
  - `TaskFixRequestRepository extends JpaRepository<TaskFixRequest, Long>`:
    - `List<TaskFixRequest> findByTaskIdOrderByIdDesc(Long taskId)`
    - `long countByTaskIdAndStatus(Long taskId, FixRequestStatus status)`
    - `Optional<TaskFixRequest> findFirstByTaskIdAndStatusOrderByIdDesc(Long taskId, FixRequestStatus status)`
    - `List<TaskFixRequest> findByTaskIdAndStatusAndRoundLessThanOrderByRoundAsc(Long taskId, FixRequestStatus status, Integer round)`(이전 회차 본문)

- [ ] **Step 1: 실패하는 시험 작성** — `TaskStatusFixReviewTest`(`TaskStatusCloseTest`를 본뜸)

```java
@Test void 수정승인대기_DB값_왕복() {
    assertThat(TaskStatus.FIX_REVIEW.dbValue()).isEqualTo("수정승인대기");
    assertThat(TaskStatus.fromDb("수정승인대기")).isEqualTo(TaskStatus.FIX_REVIEW);
}
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.entity.TaskStatusFixReviewTest` → 컴파일 실패(`FIX_REVIEW` 없음)
- [ ] **Step 3: 구현**
  - `TaskStatus`에 `FIX_REVIEW("수정승인대기")`를 넣고 상태도 주석에 spec §4 흐름을 더한다.
  - `V38__task_fix_request.sql`은 `V28__task_db_connection.sql` 모양을 따르고, 첫 줄에 spec 주석을 단다.
    - `ALTER TABLE com.task ADD COLUMN active_fix_request_id BIGINT;`
    - `CREATE TABLE com.task_fix_request` 칸: `id BIGSERIAL PK`, `task_id BIGINT NOT NULL REFERENCES com.task(id) ON DELETE CASCADE`, `round INTEGER`, `body TEXT NOT NULL`, `status VARCHAR(20) NOT NULL`, `implementation_mode VARCHAR(10)`, `from_status VARCHAR(30) NOT NULL`, `requested_by VARCHAR(100) NOT NULL`, `requested_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `decided_by VARCHAR(100)`, `decided_at TIMESTAMPTZ`, `decision_reason TEXT`, `attempt INTEGER NOT NULL DEFAULT 1`, `outcome VARCHAR(20)`, `closed_at TIMESTAMPTZ`
    - `CREATE INDEX idx_task_fix_request_task ON com.task_fix_request(task_id, id DESC);`
  - 엔티티 매핑: `status`·`implementationMode`·`outcome`은 `@Enumerated(STRING)`. `fromStatus`는 `TaskStatusConverter`가 자동 적용된다.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `.\gradlew.bat compileJava compileTestJava` 성공
- [ ] **Step 5: 커밋** — `feat(fix): 수정 요청 기록 테이블과 수정승인대기 상태`

---

### Task 2: 수정 요청·승인·반려·철회 API

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/TaskFixService.java`, `controller/TaskFixController.java`
- Create: `dto/FixRequestCreateRequest.java`(`record(String body, String implementationMode)`), `dto/FixRequestApproveRequest.java`(`record(String implementationMode)`), `dto/FixRequestRejectRequest.java`(`record(String reason)`), `dto/FixRequestView.java`
- Modify: `dto/TaskResponse.java`(새 칸 `FixView fix` — 헤더·`withTotalCost`·`withRefRepos`·`redactSecretValues`·`build` 다섯 곳), 상세 응답을 만드는 `TaskController`의 `GET /api/tasks/{id}` 경로(`withFix`), `src/main/resources/application.yml`(`app.task.fix-request-max-rounds: 3`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskFixServiceTest.java`, `controller/TaskFixFlowIntegrationTest.java`(Testcontainers, `TaskCloseFlowIntegrationTest`를 본뜸)

**Interfaces:**
- Consumes: Task 1의 엔티티·리포지토리, `TaskStatusHistory.log(...)`, `TaskRepository.findActiveByIdForUpdate`
- Produces:
  - `TaskFixService`
    - `Task request(Long taskId, String actorId, boolean isAdmin, String body, String rawMode)`
    - `Task approve(Long taskId, String adminId, String rawMode)`
    - `Task reject(Long taskId, String adminId, String reason)`
    - `Task withdraw(Long taskId, String actorId, boolean isAdmin)`
    - `List<TaskFixRequest> list(Long taskId)`
    - `FixView fixViewOf(Task t)`
    - `static ImplementationMode parseFixMode(String raw)`(null·공백 → `REVIEW`, `DIRECT`/`REVIEW` → 그 값, `SDD`·그 밖 → `TaskException` 400)
    - `static final Set<TaskStatus> REQUESTABLE = EnumSet.of(DEPLOYED, DEPLOY_LOST, DEPLOY_FAILED)`
  - `TaskResponse.FixView(Integer activeRound, String activeMode, int approvedRounds, int maxRounds)` + `TaskResponse withFix(FixView)`
  - 엔드포인트(`/api/tasks`, 응답은 `TaskCloseController.view`와 같은 `TaskResponse`):
    - `POST /{id}/fix-requests` — 본문 `FixRequestCreateRequest`, 권한은 등록자·관리자
    - `POST /{id}/fix-requests/approve` — 관리자, 본문 `FixRequestApproveRequest`
    - `POST /{id}/fix-requests/reject` — 관리자, 본문 `FixRequestRejectRequest`
    - `POST /{id}/fix-requests/withdraw` — 등록자·관리자
    - `GET /{id}/fix-requests` — 등록자·관리자, `List<FixRequestView>`
  - `FixRequestView(Long id, Integer round, String status, String implementationMode, String body, String requestedBy, OffsetDateTime requestedAt, String decidedBy, OffsetDateTime decidedAt, String decisionReason, int attempt, String outcome, OffsetDateTime closedAt)`

- [ ] **Step 1: 실패하는 시험 작성** — `TaskFixServiceTest`. Mockito이고 `TaskCloseServiceTest`의 `task(status)` 헬퍼·id 42를 본뜬다. 승인 한도는 생성자 인자 `3`.

```java
@Test void 등록자_요청은_수정승인대기로() {
    Task t = task(DEPLOYED); t.setHeadBranch("netismaker/task-42-x"); t.setHeadSha("abc1234");
    Task out = svc.request(42L, OWNER, false, "장기분석 화면 로딩이 끝나지 않음", null);
    assertThat(out.getStatus()).isEqualTo(FIX_REVIEW);
    verify(fixRepo).save(argThat(r -> r.getStatus() == PENDING && r.getFromStatus() == DEPLOYED));
}
@Test void 관리자_요청은_바로_구현대기_1회차_리뷰() {   // round=1, mode=REVIEW, activeFixRequestId=저장된 id, 이력 "수정 1회차 승인 (구현 방식: 리뷰) → 구현대기"
@Test void 배포실패에서도_요청된다()
@Test void PR생성_상태는_409()
@Test void 브랜치_없으면_409()
@Test void 열린_회차가_있으면_409()            // activeFixRequestId != null
@Test void 수정승인대기에서_다시_요청하면_409()  // Review Focus 3
@Test void 승인_3회_뒤에는_409_새작업_안내()      // 메시지에 "새 작업"
@Test void SDD는_400()
@Test void 남의_작업은_403()
@Test void 승인은_회차를_승인수_더하기_1로()        // countByTaskIdAndStatus(APPROVED)=1 → round 2
@Test void 반려는_요청전_상태로_사유필수()          // fromStatus=DEPLOY_LOST로 돌아감, 빈 사유 400
@Test void 철회는_등록자가_요청전_상태로()
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.TaskFixServiceTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - 모든 메서드는 `findActiveByIdForUpdate`로 잠근다. 상태 이동 헬퍼는 `TaskCloseService.move`와 같은 모양으로 둔다(actorType `"user"`).
  - 이력 사유:
    - `수정 요청 → 수정승인대기`
    - `수정 {n}회차 승인 (구현 방식: {label}) → 구현대기`
    - `수정 요청 반려: {reason}`
    - `수정 요청 철회`
  - 승인 때 작업의 `failureReason`·`implementationLog`·`implementationTimeoutMinutes`·`workerId`·`claimedAt`을 비운다. 브랜치·`head_sha`·PR 칸은 건드리지 않는다.
  - 본문 길이 상한 20 000자(넘으면 400).
  - 컨트롤러는 `TaskCloseController`처럼 `AuthContext`를 쓴다. 관리자 엔드포인트에는 `@PreAuthorize("hasAuthority('ROLE_ADMIN')")`를 붙인다.
- [ ] **Step 4: 통과 확인** — 위 클래스 PASS. `TaskFixFlowIntegrationTest`(등록자 요청 → 관리자 승인 → `GET /api/tasks/42`의 `fix.activeRound == 1`, `status == APPROVED`)는 CI에서 확인한다.
- [ ] **Step 5: 커밋** — `feat(fix): 수정 요청·승인·반려·철회 API`

---

### Task 3: 회차 동안 컨테이너 보호·소프트 삭제 금지

**Files:**
- Modify: `repository/TaskRepository.java:243-257`(`findDeployReconcilable`), `service/TaskService.java:469-477`(`softDelete` 분기)
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceFixRoundGuardTest.java`, `repository/TaskRepositoryFixRoundProtectionIntegrationTest.java`(Testcontainers)

**Interfaces:**
- Consumes: `Task.getActiveFixRequestId()`, `TaskStatus.FIX_REVIEW`
- Produces: `findDeployReconcilable()`이 `FIX_REVIEW`이거나 `active_fix_request_id IS NOT NULL`인 작업도 돌려준다(GC 보호). reconcile은 지금처럼 `배포완료`·`배포중단됨`에만 작용한다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 수정승인대기는_삭제_409()
@Test void 열린_회차의_구현실패는_삭제_409()      // IMPLEMENTATION_FAILED + activeFixRequestId=7
@Test void 회차없는_구현실패는_지금처럼_삭제된다()
// Integration: 수정승인대기·회차 구현중 작업이 findDeployReconcilable()에 포함, 회차 없는 구현중은 미포함
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.TaskServiceFixRoundGuardTest` → FAIL
- [ ] **Step 3: 구현** — `softDelete`에 `FIX_REVIEW`와 "`activeFixRequestId != null`이면 409(`수정 회차 진행 중에는 삭제할 수 없습니다`)"를 더한다. `findDeployReconcilable` JPQL에 `OR t.activeFixRequestId IS NOT NULL`과 `FIX_REVIEW`를 더하고 Javadoc 이유를 한 줄 보탠다.
- [ ] **Step 4: 통과 확인** — 단위 시험 PASS(통합은 CI)
- [ ] **Step 5: 커밋** — `feat(fix): 수정 회차 동안 배포 컨테이너 보호·삭제 금지`

---

### Task 4: 구현 claim에 회차 싣기 + 구버전 워커 차단

**Files:**
- Modify: `dto/WorkerTaskResponse.java:106`(`ImplementationSpec`), `:223-241`(`forImplementation`·`implementationSpecOf`)
- Modify: `service/WorkerService.java:160-257`(`claimNextTask` 오버로드·`claim`)
- Modify: `controller/WorkerController.java:48,70-111`
- Modify: `repository/TaskRepository.java:146-188`(native claim 쿼리)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceFixRoundClaimTest.java`, `controller/WorkerControllerSupportsTest.java`(있으면 거기에 더한다), `repository/TaskClaimFixRoundSupportIntegrationTest.java`(Testcontainers, `TaskClaimRefSupportIntegrationTest`를 본뜸)

**Interfaces:**
- Consumes: Task 1·2(`TaskFixRequestRepository`, 승인된 요청의 `implementationMode`·`round`·`attempt`)
- Produces:
  - `record WorkerTaskResponse.FixRoundSpec(Long requestId, int round, int attempt, String body, List<String> previousBodies, String headBranch, String headSha)`
  - `record ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes, FixRoundSpec fix)` + 3인자 생성자(`fix=null`)
  - `WorkerTaskResponse.forImplementation(Task t, TaskAnalysis a, TaskDesign d, String planMarkdown, FixRoundSpec fix)` — 4인자판은 `fix=null`로 위임
  - `record WorkerCapabilities(boolean refRepos, boolean fixRounds)`(`service` 패키지)
  - `WorkerService.claimNextTask(String workerId, WorkerCapabilities caps, Set<WorkerTaskResponse.Kind> kinds /*null=전부*/)`. 기존 오버로드의 뜻은 그대로 둔다: 1인자 = 둘 다 true, `(id, boolean refRepos)` = fixRounds false.
  - `WorkerController.SUPPORTS_FIX_ROUNDS = "fixRounds"`, `static boolean supports(String supports, String token)`(쉼표 분리·trim·정확히 일치). `supportsRefRepos`는 이 함수로 위임한다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 회차_작업_claim은_fix를_싣는다() {
    // active 요청: round 2, attempt 1, body "대상장비 탭 갱신 안 됨", mode REVIEW, 이전 승인 1회차 body "장기분석 로딩"
    WorkerTaskResponse r = svc.claimNextTask("w1", new WorkerCapabilities(true, true), null);
    assertThat(r.implementation().mode()).isEqualTo("REVIEW");
    assertThat(r.implementation().fix().round()).isEqualTo(2);
    assertThat(r.implementation().fix().previousBodies()).containsExactly("장기분석 로딩");
    assertThat(r.implementation().fix().headBranch()).isEqualTo("netismaker/task-42-x");
    assertThat(r.implementation().timeoutMinutes()).isEqualTo(80);   // REVIEW 상한(작업 방식 DIRECT여도)
}
@Test void 회차_없는_구현_claim은_fix가_null()
@Test void supports_토큰_파싱()   // "refRepos,fixRounds" → 둘 다 true, "refRepos" → fixRounds false, null → 둘 다 false
// Integration: fixRounds=false면 active_fix_request_id 있는 구현대기는 claim 안 됨, 다른 작업은 됨
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests com.hamonsoft.netismaker.service.WorkerServiceFixRoundClaimTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - native claim 쿼리에 `AND (:includeFixRounds OR NOT (t.status = :approved AND t.active_fix_request_id IS NOT NULL))`를 더한다.
  - kinds가 null인 경로도 `CLAIMABLE_STATUSES` 전체로 같은 native 쿼리를 쓰게 모은다. 정렬·`FOR UPDATE SKIP LOCKED`는 기존과 같게 둔다.
  - `claim()`의 APPROVED 분기에서 열린 회차가 있으면 `policy.timeoutMinutes(회차 방식)`로 상한을 정한다. 그다음 `FixRoundSpec`을 만든다(이전 본문은 `round` 미만의 승인 요청).
  - 컨트롤러는 `supports`를 `WorkerCapabilities`로 바꿔 넘긴다.
- [ ] **Step 4: 통과 확인** — 단위 시험 PASS. 기존 `WorkerServiceKindsTest`·`WorkerServiceRefReposTest`·`TaskRepositoryClaimableStatusesTest`도 PASS.
- [ ] **Step 5: 커밋** — `feat(fix): 구현 claim에 수정 회차 싣기·fixRounds 지원 워커만`

---

### Task 5: 결과 처리 — 자동 재배포·회차 닫기·재시도·재큐·중단

**Files:**
- Modify: `dto/WorkerResultRequest.java`(`Integer fixAttempt` + `withFixAttempt(Integer)` — `withImplementationAttempt` 모양)
- Modify: `service/WorkerService.java:313-613`(`recordResult`의 늦은 보고 판정·PR_CREATED·IMPLEMENTATION_FAILED·`recordDeployResult`)
- Modify: `entity/Task.java`(`prepareFixRoundRequeue()`)
- Modify: `service/TaskService.java:682-730`(`retryImplementation`)
- Modify: `service/TaskRecovery.java:64-117`(IMPLEMENTING 재큐·DEPLOYING 실패)
- Modify: `service/StaleTaskRecoveryJob.java:119-126`(구현 판정 방식)
- Create: `service/FixRounds.java`(회차 닫기 헬퍼)
- Modify: `service/TaskFixService.java`(`abort`), `controller/TaskFixController.java`(`POST /{id}/fix-requests/abort`, 관리자)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceFixRoundResultTest.java`, `TaskServiceFixRoundRetryTest.java`, `TaskRecoveryFixRoundTest.java`, `StaleTaskRecoveryJobFixRoundTest.java`, `TaskFixServiceTest.java`(abort 추가)

**Interfaces:**
- Consumes: Task 1~4
- Produces:
  - `Task.prepareFixRoundRequeue()` — 상태 `APPROVED`. `failureReason`·`implementationLog`·`implementationTimeoutMinutes`·`workerId`·`claimedAt`를 비운다. `headBranch`·`headSha`·`prUrl`·`prNumber`·`implementationAttempt`는 그대로 둔다.
  - `FixRounds.close(Task t, TaskFixRequest req, FixOutcome outcome, OffsetDateTime now)` — 요청에 `outcome`·`closedAt`을 넣고 `t.setActiveFixRequestId(null)`
  - `TaskFixService.abort(Long taskId, String adminId)` → `Task`
  - 워커가 보내는 성공 보고(Task 6): `status=PR_CREATED`, `headBranch=회차 브랜치`, `headSha=새 끝`, `prUrl=null`, `.withImplementationAttempt(a).withFixAttempt(f)`

- [ ] **Step 1: 실패하는 시험 작성**

```java
// WorkerServiceFixRoundResultTest (WorkerServiceImplementationAttemptTest 하네스)
@Test void 회차_성공보고는_배포대기로_env유지() {
    // 회차 2, IMPLEMENTING, envVars=[A=1], prUrl ".../pull/3"
    svc.recordResult(42L, success("netismaker/task-42-x", "new9999").withImplementationAttempt(1).withFixAttempt(1));
    assertThat(t.getStatus()).isEqualTo(DEPLOY_PENDING);
    assertThat(t.getHeadSha()).isEqualTo("new9999");
    assertThat(t.getPrUrl()).isEqualTo(".../pull/3");
    assertThat(t.getEnvVars()).extracting(EnvVar::name).containsExactly("A");
    // 이력 2줄: IMPLEMENTING→PR_CREATED "수정 2회차 구현 완료: new9999"(7자), PR_CREATED→DEPLOY_PENDING "수정 2회차 구현 완료 → 자동 재배포"(actorType system)
}
@Test void 회차_실패보고는_구현실패_회차유지_headSha는_보낸값()
@Test void 이전_수정시도의_늦은보고는_무시()        // 요청 attempt 2, 보고 fixAttempt 1 → 상태 그대로, 같은 상태 이력 "이전 수정 시도(1회째)의 늦은 보고 무시"
@Test void 회차_배포완료는_회차를_닫는다_DEPLOYED()  // activeFixRequestId null, 요청 outcome DEPLOYED
@Test void 회차_배포실패도_회차를_닫는다()
// TaskServiceFixRoundRetryTest
@Test void 회차_구현재시도는_브랜치와_attempt를_지키고_수정시도만_올린다()  // implementationAttempt 1 그대로, headBranch 그대로, 요청 attempt 2, 이력 "수정 2회차 구현 재시도 (2회째, 구현 방식: 리뷰)"
@Test void 회차_구현재시도에_SDD는_400()
// TaskRecoveryFixRoundTest
@Test void 재기동_재큐는_회차면_prepareFixRoundRequeue()   // headBranch·prUrl 보존, 요청 attempt +1
@Test void 회차_배포중_회수는_배포실패와_회차닫기()
// StaleTaskRecoveryJobFixRoundTest
@Test void 회차_구현중_판정은_회차_방식을_따른다()   // 작업 DIRECT, 회차 REVIEW, 경과 70분 → 회수 안 함(REVIEW 판정 95분)
// TaskFixServiceTest
@Test void 수정중단은_회차_구현실패에서만_PR생성으로()   // outcome ABORTED, activeFixRequestId null, 이력 "수정 n회차 중단 → PR생성"; 회차 없는 구현실패는 409
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.service.*FixRound*"` → FAIL
- [ ] **Step 3: 구현**
  - `recordResult` 맨 앞의 늦은 보고 판정에 회차 판정을 더한다. 열린 요청의 `attempt`와 `req.fixAttempt()`가 다르면 기존 문구 형식으로 무시한다.
  - PR_CREATED 처리: 열린 회차면 `prUrl` 필수를 건너뛰고 `headSha`·`implementationLog`만 바꾼 뒤 두 이력을 남기고 `DEPLOY_PENDING`으로 간다. `toDeployPending`과 같은 칸 정리를 하고, actorType은 `"system"`.
  - IMPLEMENTATION_FAILED 처리: 열린 회차에서 `req.headSha()`가 있으면 `task.headSha`로 저장한다(브랜치 끝 — 수정 중단 뒤 배포 기준).
  - `recordDeployResult`와 `TaskRecovery`의 DEPLOYING 실패 경로에서 열린 회차가 있으면 `FixRounds.close`를 부르고 이력 사유 끝에 ` (수정 {n}회차 닫힘)`을 붙인다.
  - `retryImplementation`과 `TaskRecovery` IMPLEMENTING 재큐: 열린 회차면 `prepareFixRoundRequeue()`를 부르고 요청 `attempt+1`을 한다. 재시도의 방식은 `parseFixMode`로 정하고, null이면 요청의 방식을 그대로 둔다.
- [ ] **Step 4: 통과 확인** — 위 명령 PASS. 기존 `WorkerServiceImplementationAttemptTest`·`TaskServiceImplementationRetryTest`·`WorkerStartupServiceTest`도 PASS.
- [ ] **Step 5: 커밋** — `feat(fix): 수정 회차 결과 처리·자동 재배포·재시도·중단`

---

### Task 6: 워커 — 같은 브랜치에서 이어 구현

**Files:**
- Modify: `workerdaemon/WorktreeService.java`(`createOnBranch`)
- Modify: `workerdaemon/GitOpsService.java`(`headSha(File)`)
- Create: `workerdaemon/FixRoundPrompts.java`(package-private), `src/main/resources/prompts/fix-round-review-section.md`
- Modify: `workerdaemon/WorkerMainLoop.java:256-479`(`processImplementation` 분기 + `implementFixRound`)
- Modify: `workerdaemon/WorkerHttpClient.java:122`(`SUPPORTS = "refRepos,fixRounds"`)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorktreeServiceFixBranchTest.java`(실제 git, `WorktreeServiceTest`의 bare remote 하네스), `WorkerMainLoopFixRoundTest.java`(`WorkerMainLoopImplementationModeTest` 하네스), `WorkerHttpClientNextTaskTest.java`(기대 query 수정)

**Interfaces:**
- Consumes: Task 4의 `ImplementationSpec.fix()`(`FixRoundSpec`), Task 5의 보고 형식
- Produces:
  - `WorktreeService.CreatedWorktree createOnBranch(File repoCacheDir, String repoKey, String headBranch, long taskId)`
    - 레포 락 안에서 `WorktreeKind.TASK` 경로에 `git worktree add --force --detach {dir} origin/{headBranch}`를 돌린다.
    - 반환값의 `branchName = headBranch`.
  - `GitOpsService.headSha(File dir)` → `git rev-parse HEAD`
  - `FixRoundPrompts.render(WorkerTaskResponse task, FixRoundSpec fix, String tipSha, boolean remoteAhead, ImplementationMode mode, String commitRule)` → 프롬프트 본문
    - 끝에 `DbMcpWorkerSupport.promptSection`·`RefRepoPrompts.promptSection`·`DEPLOY_ENV_INSTRUCTION`을 지금처럼 이어 붙인다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
// WorktreeServiceFixBranchTest
@Test void createOnBranch는_원격_브랜치_끝에서_detached()     // HEAD == origin/feature 끝
@Test void 같은_브랜치로_fast_forward_push된다()                // commitAndPush(dir, ref, "feature", msg, tip) 뒤 원격 feature 끝 == 새 커밋, 부모 == tip
@Test void 원격이_앞서_있으면_push가_거절된다()                // 생성 뒤 원격에 다른 커밋 push → commitAndPush가 IOException
// WorkerMainLoopFixRoundTest
@Test void 회차는_createOnBranch를_쓰고_create는_안_부른다()
@Test void 프롬프트에_수정본문과_이어고치기_규칙()   // contains "대상장비 탭 갱신 안 됨", "처음부터 다시 짜지 말고", 이전 회차 본문
@Test void PR을_새로_만들지_않고_성공보고()          // never createDraftPr; 보고 status PR_CREATED, headBranch "netismaker/task-42-x", headSha "head456", prUrl null, fixAttempt 1
@Test void 원격끝이_앞서면_그_위에서_고치고_로그에_남긴다()   // headSha(wt)="tip777" != fix.headSha "abc1234" → 프롬프트·implementationLog에 "원격 브랜치 끝이 tip777"; commitAndPush baseSha "tip777" (Review Focus 1)
@Test void 변경이_없으면_구현실패_사유가_변경없음()     // commitAndPush가 "nothing to commit" IOException → 실패 보고 사유에 "변경 없음" (Review Focus 2)
@Test void claude_실패면_wip_보존하고_fixAttempt를_실어_실패보고()
@Test void SDD가_와도_리뷰로_돈다()
```

- [ ] **Step 2: 실패 확인** — `.\gradlew.bat test --tests "com.hamonsoft.netismaker.workerdaemon.*FixRound*" --tests com.hamonsoft.netismaker.workerdaemon.WorktreeServiceFixBranchTest` → 컴파일 실패
- [ ] **Step 3: 구현** — `processImplementation`에서 참조 repo 준비 뒤 `spec.fix() != null`이면 `implementFixRound`로 간다.
  1. `repos.fetchOnly(ref, fix.headBranch())` → `createOnBranch` → `tip = gitOps.headSha(wt)`. `remoteAhead = !tip.startsWith(fix.headSha())`.
  2. `ensureWorkerExcludes` 다음, 방식이 REVIEW면 `fix-round-review-section.md`를 붙인다. 그 안의 리뷰 범위는 "이번 수정 요청 본문"이다.
  3. `claude.exec`(기존 인자 그대로).
  4. 실패면 `preservePartialWork(task, wt, tip, why)`를 부르고, 실패 보고에 `withFixAttempt`를 단다.
  5. 성공이면 `.netis/`·`.design/` 정리와 `DeployEnvManifest.harvest`를 한 뒤 `commitAndPush(wt, ref, fix.headBranch(), "fix: 수정 {n}회차 — {title}\n\ntask #{id}\n\nCo-Authored-By: netisMaker <…>", tip)`. `createDraftPr`는 부르지 않는다.
  6. 성공 보고를 보내고 `discard`. "nothing to commit"은 사유 `수정 {n}회차: 변경 없음 — Claude가 고칠 곳을 찾지 못함`으로 바꾼다.
  - `SUPPORTS` 상수를 바꾼다.
- [ ] **Step 4: 통과 확인** — 위 명령 PASS. 기존 `WorkerMainLoopImplementationModeTest`·`WorkerMainLoopRefReposTest`·`WorktreeServiceTest`·`GitOpsServicePushTest`도 PASS.
- [ ] **Step 5: 커밋** — `feat(fix): 워커가 같은 브랜치에서 수정 회차 구현`

---

### Task 7: 화면 — 수정 요청·승인·반려·철회·중단·이력

**Files:**
- Modify: `frontend/composables/taskStages.ts`(`STAGES` 배포 단계에 `FIX_REVIEW`, `CHIP`, `ADMIN_GATE_STATUSES`, `DEPLOY_ACTIVE_STATUSES`, `NextActionKind`·`nextAction`)
- Create: `frontend/composables/taskFix.ts`, `frontend/components/tasks/FixRequestDialog.vue`, `frontend/components/tasks/FixRequestHistory.vue`
- Modify: `frontend/pages/tasks/[id].vue`(버튼·모바일 더 보기 메뉴·핸들러·이력 카드), `frontend/components/tasks/ImplementationRetryDialog.vue`(회차 문구·방식 제한), `frontend/pages/tasks/index.vue`(상태 필터 `FIX_REVIEW`, `fromTask` 미리 채우기), 타입(`TaskResponse`에 `fix`)
- Test: `frontend/test/task-detail-fix.spec.ts`(`task-detail-close.spec.ts` 하네스), `frontend/composables/taskStages.spec.ts`, `frontend/composables/taskFix.spec.ts`

**Interfaces:**
- Consumes: Task 2·5의 엔드포인트, `TaskResponse.fix = { activeRound, activeMode, approvedRounds, maxRounds }`
- Produces:
  - `taskFix.ts`
    - `canRequestFix(task, isAdmin, isOwner): boolean` — 상태 ∈ {DEPLOYED, DEPLOY_LOST, DEPLOY_FAILED} && (admin||owner) && `implementation?.headBranch` && `fix.activeRound == null` && `approvedRounds < maxRounds`
    - `fixLimitReached(task): boolean`
    - `canAbortFix(task, isAdmin): boolean` — admin && `IMPLEMENTATION_FAILED` && `fix.activeRound != null`
    - `followUpPrefillQuery(task): { fromTask: string }`
  - `NextActionKind`에 `approve-fix`·`withdraw-fix`를 더한다.
    - `FIX_REVIEW` admin → `{label:'수정 승인', kind:'approve-fix'}`, owner → `{label:'수정 요청 철회', kind:'withdraw-fix'}`
  - `FixRequestDialog.vue` 속성 `{ modelValue: boolean; taskId: string; isAdmin: boolean; nextRound: number; maxRounds: number; initialBody?: string }`, 이벤트 `update:modelValue`·`submitted`
    - `POST /api/tasks/{id}/fix-requests` `{ body, implementationMode }`
    - 방식 선택은 관리자만 보이고 기본 `REVIEW`, 선택지는 리뷰·직접
  - `FixRequestHistory.vue` 속성 `{ taskId: string; refreshKey: number }` → `GET /api/tasks/{id}/fix-requests`

- [ ] **Step 1: 실패하는 시험 작성**

```ts
it('등록자는 배포완료에서 수정 요청을 낸다', async () => { /* [data-test="fix-request-open"] → 본문 입력 → [data-test="fix-request-submit"] → useApiMock('/api/tasks/42/fix-requests', {method:'POST', body:{body:'대상장비 탭 갱신 안 됨', implementationMode: undefined}}) */ })
it('관리자는 수정승인대기에서 리뷰 기본으로 승인한다')   // next-action-primary → 방식 radio 기본 REVIEW → POST .../fix-requests/approve {implementationMode:'REVIEW'}
it('관리자 반려는 사유를 받는다')                        // bar-more-fix-reject → prompt → POST .../reject {reason}
it('등록자는 수정승인대기에서 철회한다')                  // next-action-primary(withdraw-fix) → confirm-dialog-ok → POST .../withdraw
it('상한에 닿으면 버튼 대신 새 작업 등록 링크')            // approvedRounds 3/3 → no fix-request-open, has fix-request-new-task (href에 fromTask=42)
it('회차 구현실패에서 관리자는 수정 중단을 고를 수 있다')  // fix-abort → confirm-dialog-ok → POST .../abort
// taskStages.spec: FIX_REVIEW 칩 색 존재, nextAction(admin/owner), DEPLOY_ACTIVE_STATUSES 포함
```

- [ ] **Step 2: 실패 확인** — `cd frontend; npx vitest run test/task-detail-fix.spec.ts composables/taskStages.spec.ts composables/taskFix.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - **수정 요청 버튼**
    - 데스크톱: 배포 카드 옆 보조 버튼 `fix-request-open`
    - 모바일: 하단 바 "더 보기"에 `bar-more-fix-request`
    - 다이얼로그 제목은 `수정 요청 ({n}/{max})`. 안내 문구: "같은 브랜치에서 이어 고친 뒤 자동으로 다시 배포해요. 등록자 요청은 관리자 승인 뒤 시작돼요."
  - **승인**: `$q.dialog`의 `options: { type: 'radio', model: 'REVIEW', items: [리뷰, 직접] }`
  - **반려**: `rejectClose`처럼 prompt를 띄운다.
  - **철회·중단**: `confirmDialog`를 쓴다.
  - **새 작업 등록**: `/tasks?fromTask={id}`. `index.vue`는 `fromQuestion`처럼 작업을 읽어 아래 값으로 등록 폼을 채운다.
    - 레포·브랜치: 원래 작업과 같게
    - 제목: `[작업 {id} 후속] {원제목}`
    - 설명 첫 줄: `원래 작업: #{id}`(+ 작업종료 전이면 "원래 작업의 변경은 아직 병합 전"을 덧붙인다)
  - **구현 재시도 창**: `fix.activeRound`가 있으면 제목을 `수정 {n}회차 구현 재시도 (같은 브랜치)`로 바꾸고 방식은 리뷰·직접만 보인다.
  - **이력 카드**: 작업 상세에 `FixRequestHistory`를 둔다. 요청이 하나라도 있을 때만 보인다.
- [ ] **Step 4: 통과 확인** — 위 명령 PASS. `npx vitest run` 전체 PASS, `npm run lint` 경고 수가 늘지 않음.
- [ ] **Step 5: 커밋** — `feat(fix): 작업 상세 수정 요청 화면`

---

### Task 8: 문서와 마무리 확인

**Files:**
- Modify: `CLAUDE.md`(상태 머신 블록에 `수정승인대기`·회차 흐름 추가, "수정 요청(2026-11-03, 스펙 …)" 한 단락: 엔드포인트·`fix-request-max-rounds`·`supports=fixRounds`·배포 순서 API(V38) → 이 PC 워커 → 원격 워커 jar → 프론트)
- Modify: `entity/TaskStatus.java` 주석(Task 1에서 했으면 확인만)

- [ ] **Step 1: 전체 시험** — `.\gradlew.bat test` PASS(Testcontainers 시험은 건너뜀으로 표시됨), `cd frontend; npx vitest run` PASS
- [ ] **Step 2: CLAUDE.md 갱신** — 위 내용. 표·예시는 기존 단락 길이에 맞춘다.
- [ ] **Step 3: 커밋** — `docs(fix): 수정 요청 흐름과 배포 순서`
