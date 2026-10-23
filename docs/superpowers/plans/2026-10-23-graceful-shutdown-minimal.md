# 재기동 내성 최소안 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 워커·인터뷰 서비스가 어떻게 내려갔든 다시 뜨는 즉시 자기가 쥐고 있던 일을 정합(재큐·회수)하게 한다. claude가 고아로 남지 않게 하고, Windows 기동·정지 스크립트가 실제 JVM·node를 기록하고 다루게 한다.

**Architecture:**
- API에 기동 알림 엔드포인트 2개를 둔다.
  - `POST /worker/startup`: 새 `WorkerStartupController` → `WorkerStartupService` → `TaskRecovery`
  - `POST /worker/interviews/startup`: `InterviewWorkerController` → `InterviewService.releaseForRestart`
- 회수 전이는 `StaleTaskRecoveryJob`에서 `TaskRecovery`로 빼서 두 경로가 공유한다.
- 워커(`WorkerMainLoop`)와 인터뷰 서비스(`ClaimLoop`)는 알림이 성공(또는 404)하기 전에는 claim하지 않는다.
- 스크립트는 새 공용 모듈 `scripts/public-proc.ps1`의 함수로 `.run/<이름>.json`을 쓰고 읽는다.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / JUnit 5 + Mockito + AssertJ, Flyway(PostgreSQL), Node 20+ / TypeScript / vitest, Windows PowerShell 5.1

**Spec:** `docs/superpowers/specs/2026-10-23-graceful-shutdown-minimal-design.md`

## Global Constraints

- 모든 작업은 worktree `C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\graceful-shutdown-minimal`(브랜치 `feature/graceful-shutdown-minimal`)에서 한다.
  - 본 체크아웃 `C:\Users\mic\NetisMaker\commanCenter`에서는 빌드·테스트·npm 설치를 하지 않는다. 라이브 스택이 그 `build/classes`로 돈다.
- **`scripts/start-public.ps1`·`stop-public.ps1`을 실제로 실행하지 않는다.** 정지 스크립트의 포트 정리가 라이브 9000/8090/3001을 죽인다. 스크립트 검증은 Task 9·10의 헬퍼 테스트와 파싱 확인으로만 한다. 라이브 서비스를 재기동·정지하지 않는다.
- Gradle은 `JAVA_HOME`=JDK 21로 `./gradlew <task> --no-daemon`으로 돌린다. 재사용 가능한 데몬을 남기지 않기 위해서다. 라이브 `bootRun`이 남은 데몬을 다시 쓰면 래퍼 트리 구조가 바뀐다.
- 인터뷰 서비스 테스트용 `node_modules`는 본 체크아웃의 것을 정션으로 연결한다.
  - 연결: worktree의 `netismaker-interview-service`에서 `cmd /c mklink /J node_modules C:\Users\mic\NetisMaker\commanCenter\netismaker-interview-service\node_modules`
  - 끝나면 `cmd /c rmdir node_modules`로 정션만 지운다. 정션을 통해 재귀 삭제하지 않는다.
- Testcontainers 통합 테스트(`RUN_TESTCONTAINERS=true`)는 로컬에서 돌리지 않는다. 마이그레이션 V32는 CI가 검증한다. 로컬은 Mockito 단위 테스트로 확인한다.
- 마이그레이션 번호는 **V32**다. 시작 전에 `src/main/resources/db/migration`의 최신이 V31인지 확인하고, 다르면 다음 번호를 쓴다.
- 재기동 자동 재큐 상한은 **2**다(작업·인터뷰 세션 각각). 3번째 재기동 중단은 실패로 둔다.
- PowerShell 스크립트(`scripts/*.ps1`, `scripts/tests/*.ps1`)는 **ASCII만** 쓰고 5.1 문법만 쓴다(`&&`·삼항·`??` 없음).
- 엔드포인트 계약은 기존 관례를 따른다. `workerId`는 쿼리 파라미터(`@RequestParam String workerId`)다(`netismaker-interview-service/src/api/javaClient.ts:45`의 "LOCKED CONTRACT"). 스펙 §3.2·§3.3의 "본문 `{workerId}`"는 이 관례로 대신한다. 워커 알림의 `pendingTaskIds`만 본문이다.
- 문구는 그대로 쓴다.
  - 기동 정합 행위자: `worker-restart`(이력 actor_type `system`)
  - 작업 사유 접두: `워커 {workerId} 재기동으로 중단`
  - 구현 재큐 이력: `워커 {workerId} 재기동으로 중단 → 자동 재큐 ({attempt}회째 시도, 재기동 재큐 {count}/2)`
  - 구현 상한 소진 failure_reason: `워커 {workerId} 재기동으로 중단(자동 재큐 2회 소진)`
  - 인터뷰 재개 노트: `인터뷰 서비스가 다시 시작돼 이 단계를 다시 진행합니다.`
  - 인터뷰 상한 소진 사유(doFail에 넘김): `인터뷰 서비스 재기동으로 중단(자동 재개 2회 소진)`
  - 404 경고(워커): `API가 /worker/startup을 모른다 — 기동 정합 건너뜀`
- 커밋 메시지는 레포 관례(`feat:`/`fix:`/`docs:`/`test:` + 한국어)를 따르고 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.

## Review Focus

1. **API가 내려간 상태로 워커가 뜬다.** 기동 알림이 연결 거부·5xx로 실패하는 동안 `nextTask`를 한 번도 부르지 않아야 한다. API가 돌아오면 알림부터 보낸다. → Task 6 `does_not_poll_until_startup_succeeds`
2. **새 워커·인터뷰 서비스 + 예전 API(404).** 경고 한 줄 뒤 평소대로 claim해야 한다. 영영 멈추면 안 된다. → Task 6 `proceeds_when_api_does_not_know_startup`, Task 7 `proceeds after 404`
3. **보고가 dead-letter에 남은 채 재기동.** 그 작업은 재큐되지 않아야 한다. → Task 4 `pending_task_is_skipped`
4. **task가 없는 질문 세션이 재기동에 걸린다.** task 미러를 건너뛰고 QUEUED로 돌아가야 한다(NPE 없음). → Task 5 `question_session_released_without_task_mirror`
5. **변경 전 방식으로 떠 있던 라이브 서비스(json 없음)를 새 정지 스크립트로 끈다.** 래퍼 명령줄이 맞으면 끄고, pid가 엉뚱한 프로세스면 건드리지 않아야 한다. → Task 9 `legacy pid file` 테스트 2개

---

### Task 1: 재기동 재큐 횟수 컬럼과 구현 재큐 준비 메서드

**Files:**
- Create: `src/main/resources/db/migration/V32__restart_requeue_count.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java`(필드·메서드 추가)
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java`(필드 추가)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java:472-505`(`retryImplementation`이 새 메서드를 쓰고 횟수를 0으로)
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceImplementationRetryTest.java`

**Interfaces:**
- Produces:
  - `Task.getRestartRequeueCount(): int` / `setRestartRequeueCount(int)` (컬럼 `restart_requeue_count`)
  - `InterviewSession.getRestartRequeueCount(): int` / `setRestartRequeueCount(int)`
  - `Task.prepareImplementationRequeue(): void`
    - `implementation_attempt` +1, 상태 `APPROVED`
    - `failureReason`·`headBranch`·`headSha`·`prUrl`·`prNumber`·`implementationLog`·`implementationTimeoutMinutes`·`workerId`·`claimedAt`을 null로
    - `updatedAt`=now
    - 구현 방식과 재기동 재큐 횟수는 건드리지 않는다

- [ ] **Step 1: 실패하는 테스트 추가** — `TaskServiceImplementationRetryTest`에 `retry_resets_restart_requeue_count`
  - 구현실패 작업의 `restartRequeueCount`=2로 두고 `retryImplementation(id, "admin", null)` 호출
  - `assertThat(t.getRestartRequeueCount()).isZero()`
  - 기존 테스트가 확인하는 시도 번호 +1·필드 초기화도 그대로 통과해야 한다
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew test --no-daemon --tests "*TaskServiceImplementationRetryTest"` → 컴파일 실패(`getRestartRequeueCount` 없음)
- [ ] **Step 3: V32 작성**

```sql
-- 재기동 자동 재큐 횟수 (스펙 2026-10-23 §3.4). 작업은 구현 재큐만, 인터뷰 세션은 턴 단위로 센다.
ALTER TABLE com.task ADD COLUMN restart_requeue_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE com.interview_session ADD COLUMN restart_requeue_count INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 4: 엔티티 필드와 `Task.prepareImplementationRequeue()` 추가, `retryImplementation` 정리**
  - 두 엔티티 모두 `@Column(name = "restart_requeue_count", nullable = false) @Setter private int restartRequeueCount;`
  - `retryImplementation`은 `t.prepareImplementationRequeue()` 뒤에 구현 방식 설정과 `setRestartRequeueCount(0)`만 한다
- [ ] **Step 5: 테스트 통과 확인** — 같은 명령 → PASS
- [ ] **Step 6: 커밋** — `feat: 재기동 재큐 횟수 컬럼(V32)과 구현 재큐 준비 메서드`

### Task 2: 회수 전이를 `TaskRecovery`로 빼고 stale 잡이 행을 잠그게

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/TaskRecovery.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java`(잠금 쿼리 추가)
- Test: `src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java`, Create `src/test/java/com/hamonsoft/netismaker/service/TaskRecoveryTest.java`

**Interfaces:**
- Produces:
  - `public record RecoveryCause(String actorId, String label, boolean requeueImplementation)`(`TaskRecovery` 안)
    - `static RecoveryCause stale(String why)` → `("stale-recovery", "Stale 회수: " + why, false)`
    - `static RecoveryCause workerRestart(String workerId)` → `("worker-restart", "워커 " + workerId + " 재기동으로 중단", true)`
  - `@Component @Profile("api") TaskRecovery(TaskStatusHistoryRepository historyRepo, DeployLogStreamService deployLogStream)`
  - `public TaskStatus recover(Task t, RecoveryCause cause)`: 전이 후의 상태를 돌려준다. 호출자가 트랜잭션과 행 잠금을 쥔다.
  - `static final int MAX_RESTART_REQUEUES = 2`
  - `TaskRepository.findInFlightClaimedForUpdateSkipLocked(): List<Task>`: `findInFlightClaimed`와 같은 JPQL에 `@Lock(PESSIMISTIC_WRITE)` + lock timeout `-2` 힌트(`findClaimableForUpdateSkipLocked`와 같은 방식)
- `recover`의 동작:
  - `requeueImplementation=false`이면 지금 `StaleTaskRecoveryJob.java:128-178`의 전이·문구와 **바이트 단위로 같다**. 문구의 `"Stale 회수: " + why` 부분이 `cause.label()`이 된다.
  - 구현중 + `requeueImplementation=true`는 Task 4가 채운다. 이 Task에서는 stale 경로만 옮긴다.

- [ ] **Step 1: 테스트 이동·추가**
  - `StaleTaskRecoveryJobTest`가 `new StaleTaskRecoveryJob(taskRepo, heartbeatRepo, new TaskRecovery(historyRepo, deployLogStream), ImplementationModePolicy.defaults())` 형태로 만들고, `findInFlightClaimedForUpdateSkipLocked()`를 목킹하게 바꾼다.
  - 기존 단언(상태·failure_reason·이력 사유)은 하나도 바꾸지 않는다.
  - `TaskRecoveryTest.stale_cause_keeps_existing_messages`를 추가한다. 구현중·배포중·분석중(재시도 여유/소진)에 `RecoveryCause.stale("워커 w1 응답 없음(heartbeat)")`을 주고, 기존 문구 그대로인지 확인한다.
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*StaleTaskRecoveryJobTest" --tests "*TaskRecoveryTest"` → 컴파일 실패
- [ ] **Step 3: `TaskRecovery` 작성, stale 잡이 쓰게 바꾸기, 잠금 쿼리 추가**
  - stale 잡 생성자에서 `historyRepo`·`deployLogStream` 대신 `TaskRecovery`를 받는다.
  - `findInFlightClaimed()`를 다른 곳에서 쓰는지 grep한다. 쓰지 않으면 지운다.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `refactor: 회수 전이를 TaskRecovery로 분리하고 stale 잡이 SKIP LOCKED로 읽게`

### Task 3: stale 잡의 API 기동 유예

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java`

**Interfaces:**
- Produces: 필드 `private OffsetDateTime startedAt = OffsetDateTime.now();`(이름은 `InterviewStaleRecoveryJob.startedAt`과 같게). `workerDead`는 `!startedAt.isAfter(now.minusSeconds(deadSec))`일 때만 참이 될 수 있다. `hungBackstop`은 그대로다.

- [ ] **Step 1: 기존 테스트 setUp 고치고 새 테스트 추가**
  - `setUp`에 `ReflectionTestUtils.setField(job, "startedAt", OffsetDateTime.now().minusHours(1))`를 넣어 기존 테스트의 의미를 유지한다.
  - 추가:
    - `worker_dead_not_judged_during_boot_grace`: startedAt=now-60s, 구현중 claim 30분 전, heartbeat 10분 전 → 전이 없음(`verifyNoInteractions(historyRepo)`)
    - `worker_dead_judged_after_boot_grace`: startedAt=now-301s, 같은 데이터 → `IMPLEMENTATION_FAILED`
    - `hung_backstop_ignores_boot_grace`: startedAt=now-10s, 분석중 claim 6분 전, heartbeat 5초 전 → `PENDING`(재시도)
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*StaleTaskRecoveryJobTest"` → 새 테스트 1개 FAIL(`worker_dead_not_judged_during_boot_grace`)
- [ ] **Step 3: 유예 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `fix: stale 잡이 API 기동 직후에는 워커 사망 판정을 쉬게`

### Task 4: 워커 기동 정합 API (`POST /worker/startup`)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskRecovery.java`(구현중 재큐 분기)
- Create: `src/main/java/com/hamonsoft/netismaker/service/WorkerStartupService.java`
- Create: `src/main/java/com/hamonsoft/netismaker/controller/WorkerStartupController.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/WorkerStartupRequest.java`, `dto/WorkerStartupResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskRecoveryTest.java`, Create `service/WorkerStartupServiceTest.java`, Create `controller/WorkerStartupControllerTest.java`

**Interfaces:**
- Consumes: `Task.prepareImplementationRequeue()`·`restartRequeueCount`(Task 1), `TaskRecovery.recover`·`RecoveryCause.workerRestart`(Task 2)
- Produces:
  - `record WorkerStartupRequest(List<Long> pendingTaskIds)`: null은 빈 목록으로 본다
  - `record WorkerStartupResponse(List<Recovered> recovered, List<Long> skipped)`, `record Recovered(Long taskId, TaskStatus from, TaskStatus to)`. 워커도 같은 `dto` 클래스를 쓴다.
  - `TaskRepository.findInFlightClaimedByWorkerForUpdate(String workerId): List<Task>`: `findInFlightClaimed` 조건 + `t.workerId = :workerId`, `@Lock(PESSIMISTIC_WRITE)`(SKIP LOCKED 없음 — 기다렸다가 재판정)
  - `@Service @Profile("api") WorkerStartupService(TaskRepository taskRepo, TaskRecovery recovery)`
    - `@Transactional public WorkerStartupResponse reconcile(String workerId, Collection<Long> pendingTaskIds)`
  - `@RestController @RequestMapping("/worker") @PreAuthorize("hasAuthority('ROLE_WORKER')") @Profile("api") WorkerStartupController`
    - `@PostMapping("/startup") public WorkerStartupResponse startup(@RequestParam String workerId, @RequestBody(required = false) WorkerStartupRequest req)`
    - 회수 건수와 건너뛴 건수를 INFO 로그 한 줄로 남긴다
- `TaskRecovery.recover`의 구현중 + `requeueImplementation=true`:
  - `restartRequeueCount < MAX_RESTART_REQUEUES`이면 `prepareImplementationRequeue()`, 횟수 +1, 이력 `IMPLEMENTING → APPROVED`(문구는 Global Constraints) → `APPROVED` 반환
  - 아니면 `IMPLEMENTATION_FAILED`, failure_reason은 상한 소진 문구
  - 다른 상태는 stale과 같은 전이에 `cause.label()` 문구를 쓴다

- [ ] **Step 1: 실패하는 테스트**
  - `TaskRecoveryTest`
    - `restart_requeues_implementation_and_counts`: 횟수 0 → `APPROVED`, attempt 1→2, 횟수 1, headBranch null, 이력 사유 `워커 w1 재기동으로 중단 → 자동 재큐 (2회째 시도, 재기동 재큐 1/2)`
    - `restart_fails_implementation_after_two_requeues`: 횟수 2 → `IMPLEMENTATION_FAILED`, failure_reason `워커 w1 재기동으로 중단(자동 재큐 2회 소진)`
    - `restart_uses_stale_transitions_for_other_states`: 디자인중→`DESIGN_PENDING`, 종료중→`CLOSE_PENDING`, 배포중→`DEPLOY_FAILED`, 이력 actor `worker-restart`
  - `WorkerStartupServiceTest`
    - `recovers_all_inflight_of_the_worker`
    - `pending_task_is_skipped`: pendingTaskIds=[7] → 작업 7은 상태·workerId 그대로, `skipped=[7]`
    - `no_inflight_returns_empty`
  - `WorkerStartupControllerTest.null_body_means_no_pending`: `startup("w1", null)` → 서비스에 빈 목록 전달(목 검증)
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*TaskRecoveryTest" --tests "*WorkerStartupServiceTest" --tests "*WorkerStartupControllerTest"` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: 커밋** — `feat: 워커 기동 정합 API — 재기동으로 중단된 작업 즉시 회수, 구현은 자동 재큐`

### Task 5: 인터뷰 기동 정합 API (`POST /worker/interviews/startup`)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`(전이 `releaseForRestart`, 조회, `recordQuestion`·`recordPlan`에서 횟수 0, 클래스 주석 전이 목록)
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/InterviewStartupResponse.java`
- Test: Create `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceRestartTest.java`, Create `controller/InterviewWorkerControllerStartupTest.java`

**Interfaces:**
- Consumes: `InterviewSession.restartRequeueCount`(Task 1)
- Produces:
  - `InterviewSessionRepository.findRunningIdsByWorker(String workerId): List<Long>`: RUNNING이고 `workerId` 일치
  - `InterviewService.runningSessionIdsOf(String workerId): List<Long>`
  - `public record RestartOutcome(InterviewSession session, InterviewTurn note, boolean released)`
  - `@Transactional public Optional<RestartOutcome> releaseForRestart(Long sessionId, String workerId)`
    - `requireSessionForUpdate`로 잠근 뒤, RUNNING이 아니거나 `workerId`가 다르면 `Optional.empty()`
    - 횟수 < `MAX_RESTART_RELEASES`(=2): QUEUED, `workerId`·`claimedAt` null, 횟수 +1, `touch`, `appendTurn(id,"system","note", 재개 노트)`, `mirrorTask(s, INTERVIEWING, "worker-restart", "인터뷰 서비스 재기동 → 인터뷰 재개")` → `released=true`
    - 아니면 `doFail(s, "worker-restart", 상한 소진 사유)` → `released=false`
  - `record InterviewStartupResponse(List<Long> released, List<Long> failed)`
  - `@PostMapping("/startup") public InterviewStartupResponse startup(@RequestParam String workerId)`(`InterviewWorkerController`)
    - id마다 `releaseForRestart`를 부른다(세션별 트랜잭션)
    - released면 `stream.pushNote(id, note.getSeq(), note.getContent())` → `stream.pushStatus(id, QUEUED)`
    - failed면 pushNote → `pushStatus(id, FAILED)` → `stream.finish(id)`
    - 한 세션의 예외는 로그만 남기고 다음 세션으로 넘어간다(`InterviewStaleRecoveryJob`의 독립 try/catch와 같게)
  - `recordQuestion`·`recordPlan` 성공 경로에서 `s.setRestartRequeueCount(0)`

- [ ] **Step 1: 실패하는 테스트**
  - `InterviewServiceRestartTest`(목 리포지토리, 기존 `InterviewServiceTest`의 생성 방식을 따른다)
    - `release_requeues_running_session`: QUEUED, workerId null, 횟수 1, `lastActivityAt` 갱신, 노트 문구 일치, task가 `INTERVIEWING`으로 미러
    - `release_fails_after_two`: 횟수 2 → FAILED, 노트가 `인터뷰 실패: 인터뷰 서비스 재기동으로 중단(자동 재개 2회 소진)`
    - `release_ignores_other_worker_or_not_running` → empty
    - `question_session_released_without_task_mirror`: taskId null → QUEUED, task 리포지토리 호출 없음
    - `record_question_resets_restart_count`
  - `InterviewWorkerControllerStartupTest`
    - `pushes_note_and_queued_for_released`
    - `pushes_failed_and_finishes_for_failed`
    - `one_session_error_does_not_stop_others`
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*InterviewServiceRestartTest" --tests "*InterviewWorkerControllerStartupTest"` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests "*InterviewServiceTest"` → PASS
- [ ] **Step 5: 커밋** — `feat: 인터뷰 기동 정합 API — 재기동으로 중단된 턴을 인터뷰대기로 되돌림(턴당 2회)`

### Task 6: 워커가 첫 claim 전에 기동 알림

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClient.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java:106-115`
- Test: Create `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoopStartupTest.java`

**Interfaces:**
- Consumes: `WorkerStartupRequest`/`WorkerStartupResponse`(Task 4), `SilentLossTracker.snapshotPending()`(기존, `PendingEntry.taskId()`)
- Produces:
  - `WorkerHttpClient.startup(List<Long> pendingTaskIds): Optional<WorkerStartupResponse>`
    - `POST /worker/startup?workerId={id}`, 본문 `WorkerStartupRequest`
    - 404(`HttpClientErrorException.NotFound`)면 `Optional.empty()`, 그 밖의 실패는 예외 전파
  - `WorkerMainLoop`
    - 필드 `private volatile boolean startupReported;`
    - `boolean ensureStartupReported()`(package-private)
      - 이미 보냈으면 true
      - 대기 보고 taskId(중복 제거)로 `startup` 호출
      - 응답이 있으면 회수·건너뜀을 INFO 로그로, empty면 404 경고 문구로 남기고 true
      - `RestClientException`이면 WARN 로그 후 false
    - `pollAndProcess()` 맨 앞: `if (!ensureStartupReported()) return;`

- [ ] **Step 1: 실패하는 테스트**(`WorkerMainLoopImplementationModeTest`의 생성 방식을 따른다)
  - `does_not_poll_until_startup_succeeds`: startup이 `ResourceAccessException`을 두 번 던진 뒤 성공 → `pollAndProcess()` 3회 호출, `nextTask`는 3번째에만 1회
  - `proceeds_when_api_does_not_know_startup`: startup이 `Optional.empty()` → 첫 호출에서 `nextTask` 호출
  - `sends_pending_task_ids_once`: tracker 대기 항목 taskId 5, 5, 9 → startup 인자 `[5, 9]`, 두 번째 `pollAndProcess`에서는 startup 재호출 없음
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*WorkerMainLoopStartupTest"` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests "*WorkerMainLoop*"` → PASS
- [ ] **Step 5: 커밋** — `feat: 워커가 첫 claim 전에 기동 알림을 보낸다`

### Task 7: 인터뷰 서비스가 첫 claim 전에 기동 알림

**Files:**
- Modify: `netismaker-interview-service/src/api/javaClient.ts`
- Modify: `netismaker-interview-service/src/claimLoop.ts`
- Test: `netismaker-interview-service/test/claimLoop.test.ts`, `netismaker-interview-service/test/javaClient.test.ts`

**Interfaces:**
- Consumes: `POST /worker/interviews/startup?workerId=`(Task 5)
- Produces:
  - `JavaApiClient.startup(): Promise<{ released: number[]; failed: number[] } | null>`: 404면 `null`, 그 밖의 비정상 응답은 `HttpStatusError`를 던진다
  - `ClaimLoop.start()`
    - `running=true` 직후, while 루프 전에 알림을 성공(또는 null)할 때까지 `pollIntervalMs` 간격으로 다시 시도한다. `stop()`이면 즉시 끝낸다.
    - 성공 로그: `[claim-loop] startup: released=[…] failed=[…]`
    - null 로그: `[claim-loop] API가 /worker/interviews/startup을 모름 — 기동 정합 건너뜀`

- [ ] **Step 1: 실패하는 테스트**
  - `claimLoop.test.ts`의 기존 모든 client 목에 `startup: vi.fn().mockResolvedValue({ released: [], failed: [] })`를 추가한다.
  - 새 테스트:
    - `does not claim before startup succeeds`: startup이 두 번 reject 후 resolve → 그 전에 `claim` 미호출, 그 뒤 호출
    - `proceeds after 404`: startup이 `null` → claim 호출
    - `stop during startup retry ends start()`
  - `javaClient.test.ts`
    - `startup posts workerId query and returns body`
    - `startup returns null on 404`
- [ ] **Step 2: 실패 확인** — worktree의 `netismaker-interview-service`에서(정션 연결 후) `npx vitest run test/claimLoop.test.ts test/javaClient.test.ts` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `npx vitest run` 전체 → PASS, `npx tsc -p tsconfig.json --noEmit` → 오류 0
- [ ] **Step 5: 커밋** — `feat(interview): 첫 claim 전에 기동 알림을 보낸다`

### Task 8: 인터럽트 때 claude·git 자식 정리 (M2)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapter.java:212-246`(`run`을 package-private으로, 인터럽트 처리)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/GitRepoCache.java:306-315`(`capture`)
- Test: Create `src/test/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapterInterruptTest.java`

**Interfaces:**
- Produces:
  - `ClaudeExecAdapter.run(...)`: `p.waitFor`에서 `InterruptedException`이 나면 `destroyTree(p)` 후 같은 예외를 다시 던진다. 시그니처는 그대로, 접근만 package-private.
  - `GitRepoCache.capture`: `waitFor`가 인터럽트되면 `p.destroyForcibly()` 후 다시 던진다. private이라 별도 테스트 없이 리뷰로 확인한다(로컬 `git rev-parse` 한 줄).

- [ ] **Step 1: 실패하는 테스트** — `interrupt_while_waiting_kills_process_and_children`
  - `ClaudeExecAdapterKillTreeTest`의 부모+자식 명령(Windows `cmd /c ping -n 60 127.0.0.1 > NUL`, 그 외 `sh -c "sleep 60 & wait"`)을 별도 스레드에서 `adapter.run(cmd, "", tmpDir, Duration.ofMinutes(5), System.currentTimeMillis(), Map.of())`로 실행한다.
  - `ProcessHandle.current().children()`에서 그 부모를 찾은 뒤 스레드를 인터럽트한다.
  - 단언
    - 스레드가 5초 안에 끝나고 잡힌 예외가 `InterruptedException`이다
    - 부모와 자식 `isAlive()`가 5초 안에 false가 된다
  - 어댑터는 `new ClaudeExecAdapter(mock(WorkerProperties.class), mock(WorkerMcpSupport.class))`로 만든다
- [ ] **Step 2: 실패 확인** — `./gradlew test --no-daemon --tests "*ClaudeExecAdapterInterruptTest"` → FAIL(자식이 살아 있음)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests "*ClaudeExecAdapter*" --tests "*GitRepoCacheTest"` → PASS
- [ ] **Step 5: 커밋** — `fix: claude 실행 대기가 인터럽트되면 claude 프로세스 트리를 정리`

### Task 9: 스크립트 공용 모듈 `scripts/public-proc.ps1` (M3 헬퍼)

**Files:**
- Create: `scripts/public-proc.ps1`(dot-source용 함수 모음)
- Create: `scripts/tests/public-proc.test.ps1`(Pester 없이 단언 함수로 테스트, 실패 시 `exit 1`)

**Interfaces (Produces)** — 모든 함수는 예외 대신 `$null`/`$false`로 답한다.

| 함수 | 하는 일 |
|---|---|
| `Import-PublicEnv([string]$EnvFile)` | `start-public.ps1:56-65`의 KEY=VALUE 로더를 옮긴 것. 환경변수가 이긴다 |
| `Get-ProcStartTicks([int]$ProcessId)` | `[long]`(`StartTime.ToFileTimeUtc()`) 또는 `$null` |
| `Write-ProcRecord([string]$RunDir, [string]$Name, [int]$WrapperPid, $AppPid)` | `<Name>.json`을 쓴다. 필드: `name, wrapperPid, wrapperStart, appPid, appStart, appImage`. `$AppPid`가 null이면 app 필드 3개 null. ASCII 인코딩 |
| `Read-ProcRecord([string]$RunDir, [string]$Name)` | PSCustomObject 또는 `$null` |
| `Test-WrapperPid([string]$RunDir, [string]$Name, [int]$ProcessId)` | 그 pid가 `cmd.exe`이고 명령줄에 `Join-Path $RunDir "$Name.log"`가 들어 있으면(대소문자 무시) `$true`. 스펙 §5.3의 `\.run\<이름>.log`를 RunDir 전체 경로로 좁힌 것 |
| `Test-ServiceAlive([string]$RunDir, [string]$Name)` | 스펙 §5.3. json의 appPid+appStart가 맞으면 true. json이 없거나 appPid가 null이면 pid 파일 + `Test-WrapperPid` |
| `Select-WorkerJvm($Processes, [string]$Root, [datetime]$NotBefore)` | 순수 함수. `Name -eq 'java.exe'`, CommandLine에 `"$Root\build\classes\java\main"`과 `--spring.profiles.active=worker`가 모두 있고, `CreationDate -ge $NotBefore`인 것 중 가장 최근의 ProcessId |
| `Find-AppPid([string]$Name, [int]$WrapperPid, [string]$Root)` | api/auth/front는 포트 8090/9000/3001 LISTEN 소유자. worker는 `Select-WorkerJvm (Get-CimInstance Win32_Process) $Root <래퍼 시작 시각>`. interview는 `ParentProcessId -eq $WrapperPid`인 `node.exe` |
| `Stop-ServiceRecord([string]$RunDir, [string]$Name)` | 스펙 §5.4의 1~3단계. 출력 줄(`[stop] name (wrapper X, app Y)` / `[gone] name`) 배열을 돌려준다 |
| `Select-OrphanClaude($Processes, [string]$ClaudeExe)` | 순수 함수. `Name -eq 'claude.exe'`, `ExecutablePath`가 `$ClaudeExe`와 같고(대소문자 무시), 부모가 목록에 없거나 부모 CreationDate가 자기보다 늦은 것 |

- [ ] **Step 1: 실패하는 테스트 작성** — `scripts/tests/public-proc.test.ps1`
  - 맨 위에서 `. (Join-Path $PSScriptRoot '..\public-proc.ps1')`, 임시 `$RunDir`을 만든다.
  - 가짜 서비스: `Start-Process $env:ComSpec -ArgumentList "/c `"ping -n 120 127.0.0.1 > `"$RunDir\fake.log`" 2>&1`"" -WindowStyle Hidden -PassThru` → 래퍼, 그 직계 자식 `PING.EXE`가 app이다.
  - 테스트(각각 `Assert-True`/`Assert-Equal` 헬퍼로 판정하고 이름을 출력):
    - `record roundtrip`
    - `alive with matching start`
    - `not alive with mismatched appStart` (pid 재사용 흉내)
    - `stop kills app tree and wrapper, removes files`
    - `stop does not kill when appStart mismatches`
    - `legacy pid file: stops matching cmd wrapper` (json 없이 pid 파일만)
    - `legacy pid file: ignores unrelated pid` (pid 파일에 `$PID`(테스트 자신) → `[gone]`, 자신은 살아 있음)
    - `Select-WorkerJvm picks main checkout worker only` (가짜 객체: 워커 JVM, 같은 경로 api JVM, worktree 경로 워커 JVM, 오래된 워커 JVM)
    - `Select-OrphanClaude` 4가지 (부모 없음 → 포함 / 부모가 더 늦게 시작 → 포함 / 부모 살아 있음 → 제외 / 다른 실행 경로 → 제외)
  - 끝에서 남은 가짜 프로세스를 정리하고 임시 폴더를 지운다. 실패가 하나라도 있으면 `exit 1`, 아니면 `ALL PASS`와 `exit 0`.
- [ ] **Step 2: 실패 확인** — `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\public-proc.test.ps1` → 모듈이 없어 실패
- [ ] **Step 3: `scripts/public-proc.ps1` 구현**
- [ ] **Step 4: 통과 확인**
  - 같은 명령 → `ALL PASS`, 종료 코드 0
  - ASCII 확인: Git Bash에서 `grep -nP '[^\x00-\x7F]' scripts/public-proc.ps1 scripts/tests/public-proc.test.ps1` → 출력 없음
- [ ] **Step 5: 커밋** — `feat(scripts): 실제 프로세스 기록·확인·정지 공용 함수(public-proc.ps1)`

### Task 10: `start-public.ps1`·`stop-public.ps1`이 공용 모듈을 쓰게 (M3)

**Files:**
- Modify: `scripts/start-public.ps1`
- Modify: `scripts/stop-public.ps1`

**Interfaces:**
- Consumes: Task 9의 함수 전부
- Produces:
  - `start-public.ps1`
    - 머리에서 `. (Join-Path $PSScriptRoot 'public-proc.ps1')`
    - 설정 파일 로딩은 `Import-PublicEnv $EnvFile`로
    - 기존 `Test-PidAlive` 함수를 지우고 `Test-ServiceAlive $RunDir <이름>`으로 바꾼다
    - 각 서비스를 새로 띄운 경우에만, 준비 확인이 성공한 뒤 `Write-ProcRecord $RunDir <이름> <래퍼 pid> (Find-AppPid <이름> <래퍼 pid> <Root>)`
      - `Start-Service-Proc`의 반환값(래퍼 pid)을 `Out-Null` 대신 변수로 받는다
      - app pid가 null이면 `[warn] <이름>: real process not found - stop falls back to the wrapper`
      - auth의 Root는 `$AuthDir`이지만 포트로 찾으므로 쓰지 않는다
  - `stop-public.ps1`
    - `param([switch]$KeepWorkers, [string[]]$Only)`
    - `$Only` 값이 `interview, worker, front, api, auth` 밖이면 `throw`. `-KeepWorkers`와 같이 쓰면 `throw`.
    - 대상 이름마다 `Stop-ServiceRecord`, 출력 줄을 `Write-Host`
    - 포트 정리는 대상 이름의 포트만(지금 `$ports` 해시를 대상으로 거른다)
    - 마지막에 `Import-PublicEnv`(기본 `%USERPROFILE%\netis-maker\public.env`)로 `CLAUDE_CLI`를 정한다(없으면 `%USERPROFILE%\.local\bin\claude.exe`). `Select-OrphanClaude (Get-CimInstance Win32_Process) $exe` 결과가 있으면 `[warn] orphan claude: pid N started T  <명령줄 앞 200자>`와 `taskkill /PID N /T /F` 안내를 출력한다. 죽이지 않는다.
    - 머리 주석 Usage에 `-Only worker` / `-Only worker,interview` 예를 추가한다

- [ ] **Step 1: 두 스크립트 수정**
- [ ] **Step 2: 파싱·ASCII 확인**
  - `powershell -NoProfile -Command "$e=$null; foreach($f in 'scripts\start-public.ps1','scripts\stop-public.ps1'){ [void][System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $f), [ref]$null, [ref]$e); if($e){ $e; exit 1 } }; 'PARSE OK'"` → `PARSE OK`
  - `grep -nP '[^\x00-\x7F]' scripts/start-public.ps1 scripts/stop-public.ps1` → 출력 없음
- [ ] **Step 3: 헬퍼 테스트 재실행** — `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\public-proc.test.ps1` → `ALL PASS`
- [ ] **Step 4: 자체 검토** — 두 스크립트의 diff를 읽고 아래를 확인한다(실행은 금지, Global Constraints)
  - `-Only` 없이 부르면 정지 순서·포트 정리가 예전과 같다
  - json이 없을 때 `Stop-ServiceRecord`가 래퍼 명령줄 확인 경로로 간다
- [ ] **Step 5: 커밋** — `feat(scripts): 기동 때 실제 JVM·node 기록, 정지 -Only·고아 claude 알림`

### Task 11: 문서와 전체 검증

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-23-graceful-shutdown-minimal-design.md`(상태 줄)

- [ ] **Step 1: `CLAUDE.md` 갱신**
  - "작업 상태머신"의 `[구현실패]` 줄 아래에 한 줄 추가: `[구현중] ──(워커 재기동 = 기동 알림, 작업당 2회까지)──→ [구현대기] (시도 번호 +1, 넘으면 [구현실패])`
  - "다중 워커 운영" 끝에 한 문단 추가
    - 워커·인터뷰 서비스가 첫 claim 전에 `POST /worker/startup`·`/worker/interviews/startup`으로 이전 프로세스의 일을 정합한다(스펙 2026-10-23 경로)
    - 그래서 `WORKER_ID`가 프로세스마다 달라야 한다
    - 배포 순서: API(V32) → 워커·인터뷰 서비스. 새 쪽이 예전 API를 만나면 404 뒤 그냥 진행한다
  - Windows 단건 재기동: `.\scripts\stop-public.ps1 -Only <이름>` → `Start-ScheduledTask -TaskName netisMaker-public`
- [ ] **Step 2: 전체 테스트**
  - `./gradlew test --no-daemon` → BUILD SUCCESSFUL(Testcontainers 테스트는 건너뜀)
  - 인터뷰 서비스 `npx vitest run` → 전부 PASS
  - `scripts\tests\public-proc.test.ps1` → `ALL PASS`
- [ ] **Step 3: 스펙 상태 줄을 `사용자 승인됨 2026-10-23 · 구현됨`으로 바꾸고 커밋** — `docs: 재기동 정합·스크립트 운영 메모`
- [ ] **Step 4: 정션 정리** — worktree `netismaker-interview-service`에서 `cmd /c rmdir node_modules`(정션만 지움). `git status`에 `node_modules`가 없는지 확인한다.
