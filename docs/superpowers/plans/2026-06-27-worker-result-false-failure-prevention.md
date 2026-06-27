# 구현결과 false-failure 재발방지 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 일시적 백엔드 장애/heartbeat 블립이 이미 성공한 구현을 "구현실패"로 만들지 않도록 다층 방어를 추가한다.

**Architecture:** 세 개의 독립 변경 단위 — (1) API가 회수된 작업의 늦은 PR_CREATED 보고를 받아 정합화, (2) heartbeat 회수 임계를 단계별로 상향, (3) 워커 결과 보고를 재시도+성공보고-비전파로 견고화. 어느 하나만으로도 부분 방어가 되고, 셋이 합쳐지면 회수가 와도 워커의 늦은 보고가 복구한다.

**Tech Stack:** Java 21, Spring Boot 3.4.1, JUnit 5 + Mockito + AssertJ, Gradle.

## Global Constraints

- 빌드/테스트: `./gradlew test` (H2 인메모리, Testcontainers 테스트는 로컬 docker 비호환이라 무관).
- 신규 설정 키는 반드시 기본값 보유(record 컴팩트 생성자 또는 yml `:default`) → 기존 배포 안전.
- DB 스키마 변경 없음. `task_status_history` 전이 기록만 추가.
- 상태 한글 dbValue/`TaskStatus` enum, `TaskStatusHistory.log(Long taskId, TaskStatus from, TaskStatus to, String actorType, String actorId, String reason)` 시그니처 준수.
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## File Structure

- `service/WorkerService.java` (수정) — `recordResult`에 정합화 분기.
- `service/WorkerServiceReconcileTest.java` (생성) — 정합화 단위 테스트.
- `service/StaleTaskRecoveryJob.java` (수정) — 단계별 worker-dead 임계.
- `service/StaleTaskRecoveryJobTest.java` (수정) — 신규 필드 setUp + 케이스.
- `resources/application.yml` (수정) — 단계별 worker-dead 임계 키.
- `workerdaemon/ResultReporter.java` (생성) — 재시도 + 성공보고 비전파 보고기.
- `workerdaemon/ResultReporterTest.java` (생성) — 재시도 단위 테스트.
- `workerdaemon/WorkerProperties.java` (수정) — 보고 재시도 설정 필드.
- `workerdaemon/WorkerHttpClient.java` (변경 없음 — `postResult` 그대로 사용).
- `workerdaemon/WorkerMainLoop.java` (수정) — 모든 결과 보고를 `ResultReporter` 경유.
- `resources/application-worker.yml` (수정) — 보고 재시도 키.

---

### Task 1: API 멱등 지각 수신 (정합화)

회수로 `IMPLEMENTATION_FAILED`가 된 작업에 워커가 늦게 보낸 `PR_CREATED`(PR 메타 유효)를 받아 `PR_CREATED`로 되돌린다.

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (`recordResult`, line 121~)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceReconcileTest.java` (생성)

**Interfaces:**
- Consumes: `WorkerService.recordResult(Long taskId, WorkerResultRequest req)`; `TaskStatusHistory.log(...)`; `Task` setters; `TaskStatus.IMPLEMENTATION_FAILED/PR_CREATED`.
- Produces: 정합화 후 `task.status == PR_CREATED`, pr 메타 채움, `failureReason == null`, history 1건(from=IMPLEMENTATION_FAILED, to=PR_CREATED).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceReconcileTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.exception.TaskException;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerServiceReconcileTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        WorkerHeartbeatRepository heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        DeployLogStreamService deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task failedTask() {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(t, "id", 16L);
        t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
        t.setFailureReason("Stale 회수: 워커 mac-worker-1 응답 없음(heartbeat) (구현 중단)");
        return t;
    }

    private WorkerResultRequest prCreated(String prUrl, String headBranch) {
        return new WorkerResultRequest("mac-worker-1", TaskStatus.PR_CREATED,
                null, null, null, 123L, null,
                prUrl, 10, headBranch, "abcdef1", "impl log",
                null, null, null, null, null);
    }

    @Test
    void late_pr_created_on_stale_failed_task_reconciles_to_pr_created() {
        Task t = failedTask();
        when(taskRepo.findById(16L)).thenReturn(Optional.of(t));

        service.recordResult(16L, prCreated("https://github.com/o/r/pull/10", "netismaker/task-16"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getPrUrl()).isEqualTo("https://github.com/o/r/pull/10");
        assertThat(t.getHeadBranch()).isEqualTo("netismaker/task-16");
        assertThat(t.getFailureReason()).isNull();
        verify(historyRepo).save(any());
    }

    @Test
    void late_pr_created_missing_pr_meta_is_rejected_as_conflict() {
        Task t = failedTask();
        when(taskRepo.findById(16L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.recordResult(16L, prCreated(null, null)))
                .isInstanceOf(TaskException.class);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceReconcileTest'`
Expected: `late_pr_created_on_stale_failed_task_reconciles_to_pr_created` FAILs — recordResult throws CONFLICT(409) because current status is IMPLEMENTATION_FAILED (not in-flight).

- [ ] **Step 3: Write minimal implementation**

In `WorkerService.recordResult`, insert the reconcile branch immediately after `TaskStatus current = t.getStatus();` (currently line 125), BEFORE the strict in-flight guard (`if (current != TaskStatus.IN_PROGRESS ...`):

```java
        TaskStatus current = t.getStatus();

        // 지각 보고 정합화: stale 회수로 IMPLEMENTATION_FAILED가 됐지만 워커가 실제로는
        // PR 생성까지 성공한 경우, 늦게 도착한 PR_CREATED 보고를 받아 정합화한다.
        // (PR 메타가 유효할 때만 — undeploy 복귀(prUrl 없는 PR_CREATED)와 구분됨)
        if (current == TaskStatus.IMPLEMENTATION_FAILED
                && req.status() == TaskStatus.PR_CREATED
                && req.prUrl() != null && !req.prUrl().isBlank()
                && req.headBranch() != null && !req.headBranch().isBlank()) {
            t.setStatus(TaskStatus.PR_CREATED);
            t.setPrUrl(req.prUrl());
            t.setPrNumber(req.prNumber());
            t.setHeadBranch(req.headBranch());
            t.setHeadSha(req.headSha());
            t.setImplementationLog(req.implementationLog());
            t.setFailureReason(null);
            t.setUpdatedAt(OffsetDateTime.now());
            historyRepo.save(TaskStatusHistory.log(t.getId(),
                    TaskStatus.IMPLEMENTATION_FAILED, TaskStatus.PR_CREATED,
                    "worker", req.workerId(), "지각 보고 정합화: stale 회수 → PR_CREATED"));
            return;
        }

        if (current != TaskStatus.IN_PROGRESS
```

(`OffsetDateTime`, `TaskStatus`, `TaskStatusHistory` 는 이미 import됨.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceReconcileTest'`
Expected: PASS (2 tests). The missing-pr-meta case still 409s because it falls through to the strict guard.

- [ ] **Step 5: Regression — existing worker-service tests still pass**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceDeployTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/WorkerService.java \
        src/test/java/com/hamonsoft/netismaker/service/WorkerServiceReconcileTest.java
git commit -m "fix(api): stale 회수된 구현작업의 지각 PR_CREATED 보고를 정합화

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: heartbeat 회수 임계 단계별 상향

긴 구현/배포 중 짧은 heartbeat 블립으로 in-flight 작업이 회수되지 않도록 worker-dead 임계를 단계별로 분리한다. 60분 절대 백스톱은 불변.

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java`
- Modify: `src/main/resources/application.yml` (app.task 섹션, line 73~)
- Test: `src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java` (수정)

**Interfaces:**
- Consumes: `StaleTaskRecoveryJob.recoverStale()`; `@Value` 필드 `implementationWorkerDeadThresholdSeconds`, `deployWorkerDeadThresholdSeconds`.
- Produces: IMPLEMENTING/DEPLOYING/UNDEPLOYING 작업의 heartbeat 신선도 판정에 단계별 임계 적용.

- [ ] **Step 1: Update existing test setUp + add new cases (failing)**

In `StaleTaskRecoveryJobTest.setUp()`, after the existing `ReflectionTestUtils.setField(job, "workerDeadThresholdSeconds", 60);` line, add:

```java
        ReflectionTestUtils.setField(job, "implementationWorkerDeadThresholdSeconds", 300);
        ReflectionTestUtils.setField(job, "deployWorkerDeadThresholdSeconds", 180);
```

Then add two new test methods to the class:

```java
    @Test
    void implementing_short_heartbeat_gap_not_recovered() {
        // 구현 작업: heartbeat 90초 전 (< 300초 임계) → 회수 안 됨
        Task t = task(10L, TaskStatus.IMPLEMENTING, "w1", 5, 0);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 90)));
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTING);
        verifyNoInteractions(historyRepo);
    }

    @Test
    void implementing_long_heartbeat_gap_recovered() {
        // 구현 작업: heartbeat 320초 전 (> 300초 임계) → 회수
        Task t = task(11L, TaskStatus.IMPLEMENTING, "w1", 6, 0);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 320)));
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.StaleTaskRecoveryJobTest'`
Expected: `implementing_short_heartbeat_gap_not_recovered` FAILs — with current single 60초 임계, 90초 gap이 dead로 판정되어 회수됨. (`implementing_long_heartbeat_gap_recovered` 는 우연히 통과할 수 있음.)

- [ ] **Step 3: Add @Value fields**

In `StaleTaskRecoveryJob.java`, after the existing `workerDeadThresholdSeconds` field (line 55-56), add:

```java
    @Value("${app.task.implementation-worker-dead-threshold-seconds:300}")
    private int implementationWorkerDeadThresholdSeconds;

    @Value("${app.task.deploy-worker-dead-threshold-seconds:180}")
    private int deployWorkerDeadThresholdSeconds;
```

- [ ] **Step 4: Use phase-specific dead threshold in recoverStale**

In `recoverStale()`, the per-task loop currently computes one global `workerDeadBefore` outside the loop (line 75) and uses it for every task. Move the dead-threshold selection INSIDE the loop, per task status.

Delete the outside-loop line 75:

```java
        OffsetDateTime workerDeadBefore = now.minusSeconds(workerDeadThresholdSeconds);
```

Then inside the `for (Task t : inflight)` loop, replace the lines:

```java
            // 갓 claim한 작업은 워커가 첫 heartbeat 보낼 시간을 준다 (오탐 방지).
            boolean claimedLongEnough = claimedAt != null && claimedAt.isBefore(workerDeadBefore);
            OffsetDateTime hb = workerId == null ? null : lastSeen.get(workerId);
            boolean workerDead = claimedLongEnough && (hb == null || hb.isBefore(workerDeadBefore));
```

with:

```java
            // 단계별 worker-dead 임계 — 긴 구현/배포 중 짧은 heartbeat 블립으로 인한 오탐 회수 방지.
            int deadSec = switch (from) {
                case IMPLEMENTING -> implementationWorkerDeadThresholdSeconds;
                case DEPLOYING, UNDEPLOYING -> deployWorkerDeadThresholdSeconds;
                default -> workerDeadThresholdSeconds; // IN_PROGRESS (분석)
            };
            OffsetDateTime workerDeadBefore = now.minusSeconds(deadSec);
            // 갓 claim한 작업은 워커가 첫 heartbeat 보낼 시간을 준다 (오탐 방지).
            boolean claimedLongEnough = claimedAt != null && claimedAt.isBefore(workerDeadBefore);
            OffsetDateTime hb = workerId == null ? null : lastSeen.get(workerId);
            boolean workerDead = claimedLongEnough && (hb == null || hb.isBefore(workerDeadBefore));
```

(`from` 은 루프 첫 줄 `TaskStatus from = t.getStatus();` 로 이미 선언됨. `switch`는 line 92의 `ceilingMin` switch 보다 위에 온다.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.StaleTaskRecoveryJobTest'`
Expected: PASS (all, including the 2 new + 9 existing). 기존 `dead_worker_deploying_*`(hb 300초 > 180초 deploy 임계) 및 `dead_worker_in_progress_*`(hb 300초 > 60초 분석 임계) 회귀 통과.

- [ ] **Step 6: Add config keys to application.yml**

In `src/main/resources/application.yml`, in the `app.task:` block, after `worker-dead-threshold-seconds: 60` add:

```yaml
    worker-dead-threshold-seconds: 60          # 분석 단계 heartbeat 사망 판정
    implementation-worker-dead-threshold-seconds: 300   # 구현: 긴 작업 중 블립 허용
    deploy-worker-dead-threshold-seconds: 180           # 배포/중지
```

(기존 `worker-dead-threshold-seconds: 60` 줄은 위 주석 포함 형태로 교체.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java \
        src/main/resources/application.yml \
        src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java
git commit -m "fix(api): heartbeat 회수 임계를 단계별로 상향 (구현 300s/배포 180s)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 워커 결과 보고 복원력 (ResultReporter)

결과 보고를 재시도하고, 성공 보고의 최종 실패는 rethrow하지 않아 성공이 실패 보고로 뒤집히지 않게 한다.

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ResultReporter.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ResultReporterTest.java` (생성)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java`
- Modify: `src/main/resources/application-worker.yml`

**Interfaces:**
- Produces: `ResultReporter.reportTerminal(Long taskId, WorkerResultRequest req) -> boolean` (성공 true / 최종 실패 false, **절대 throw 안 함**). 4xx(`HttpClientErrorException`)는 영구 오류로 재시도 안 함; 5xx/연결오류는 지수 백오프 재시도.
- Consumes: `WorkerHttpClient.postResult(Long, WorkerResultRequest)`; `WorkerProperties.resultReportMaxRetries()`, `resultReportBackoffMs()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/hamonsoft/netismaker/workerdaemon/ResultReporterTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResultReporterTest {

    private static final WorkerResultRequest REQ = new WorkerResultRequest(
            "w1", TaskStatus.PR_CREATED, null, null, null, 1L, null,
            "https://x/pull/1", 1, "netismaker/task-1", "sha", "log",
            null, null, null, null, null);

    /** maxRetries=2, backoff=1ms, no-op sleeper. */
    private ResultReporter reporter(ResultReporter.Poster poster) {
        return new ResultReporter(poster, 2, 1L, ms -> { /* no sleep */ });
    }

    @Test
    void success_on_first_attempt_returns_true_and_calls_once() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> calls.incrementAndGet()).reportTerminal(1L, REQ);
        assertThat(ok).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void transient_5xx_twice_then_success_retries_and_returns_true() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> {
            if (calls.incrementAndGet() <= 2) {
                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }).reportTerminal(1L, REQ);
        assertThat(ok).isTrue();
        assertThat(calls.get()).isEqualTo(3); // 2 실패 + 1 성공
    }

    @Test
    void persistent_5xx_exhausts_retries_returns_false_without_throwing() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> {
            calls.incrementAndGet();
            throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
        }).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(calls.get()).isEqualTo(3); // maxRetries(2) + 최초 1 = 3 시도
    }

    @Test
    void permanent_4xx_not_retried_returns_false() {
        AtomicInteger calls = new AtomicInteger();
        boolean ok = reporter((id, r) -> {
            calls.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.CONFLICT);
        }).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(calls.get()).isEqualTo(1); // 4xx는 재시도 안 함
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.ResultReporterTest'`
Expected: COMPILE FAIL — `ResultReporter` 클래스 없음.

- [ ] **Step 3: Create ResultReporter**

Create `src/main/java/com/hamonsoft/netismaker/workerdaemon/ResultReporter.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 *  워커 결과 보고기 — 재시도 + 성공보고 비전파.
 *
 *  - 5xx/연결오류(transient): 지수 백오프로 재시도.
 *  - 4xx(HttpClientErrorException): 영구 오류 → 재시도 무의미, 즉시 포기.
 *  - 최종 실패해도 예외를 던지지 않는다(false 반환). 성공 보고의 일시적 백엔드 장애가
 *    상위 catch에서 실패 보고로 뒤집히는 것을 방지(task16 false-failure 재발방지).
 */
@Component
@Profile("worker")
@Slf4j
public class ResultReporter {

    /** 단일 시도 보고. 실패 시 RestClientException 전파. */
    @FunctionalInterface
    public interface Poster { void post(Long taskId, WorkerResultRequest req); }

    /** 테스트 주입용 sleep 추상화. */
    @FunctionalInterface
    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private final Poster poster;
    private final int maxRetries;
    private final long backoffMs;
    private final Sleeper sleeper;

    public ResultReporter(WorkerHttpClient http, WorkerProperties props) {
        this(http::postResult, props.resultReportMaxRetries(), props.resultReportBackoffMs(), Thread::sleep);
    }

    ResultReporter(Poster poster, int maxRetries, long backoffMs, Sleeper sleeper) {
        this.poster = poster;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
        this.sleeper = sleeper;
    }

    /** 결과 보고. 성공 true, 최종 실패 false. 절대 throw 안 함. */
    public boolean reportTerminal(Long taskId, WorkerResultRequest req) {
        RestClientException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                poster.post(taskId, req);
                return true;
            } catch (RestClientException e) {
                last = e;
                if (e instanceof HttpClientErrorException) {
                    log.error("결과 보고 영구 실패(4xx) — task={} status={} {}", taskId, req.status(), e.getMessage());
                    return false; // 4xx 재시도 무의미
                }
                log.warn("결과 보고 실패 (시도 {}/{}) task={} status={}: {}",
                        attempt + 1, maxRetries + 1, taskId, req.status(), e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        sleeper.sleep(backoffMs * (1L << attempt)); // 지수 백오프
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("결과 보고 최종 실패 — task={} status={} (포기, rethrow 안 함)", taskId, req.status(), last);
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.ResultReporterTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Add retry config to WorkerProperties**

In `WorkerProperties.java`, add two record components. After `long heartbeatIntervalSeconds,` (line 14) add:

```java
        long heartbeatIntervalSeconds,
        int resultReportMaxRetries,
        long resultReportBackoffMs,
```

In the `public WorkerProperties {` compact constructor (after the `heartbeatIntervalSeconds` default, line 72), add defaults:

```java
        if (heartbeatIntervalSeconds <= 0) heartbeatIntervalSeconds = 10;
        if (resultReportMaxRetries <= 0) resultReportMaxRetries = 5;
        if (resultReportBackoffMs <= 0) resultReportBackoffMs = 2000;
```

- [ ] **Step 6: Route all WorkerMainLoop result reports through ResultReporter**

In `WorkerMainLoop.java`:

(a) Add field + constructor param. After `private final DeployService deployService;` add:

```java
    private final ResultReporter reporter;
```

In the constructor signature, add `ResultReporter reporter,` after `DeployService deployService,`, and in the body add `this.reporter = reporter;`.

(b) Replace the 4 SUCCESS report calls `http.postResult(...)` with `reporter.reportTerminal(...)`:
- line ~155 (analysis COMPLETED)
- line ~244 (impl PR_CREATED)
- line ~267 (deploy DEPLOYED)
- line ~280 (undeploy PR_CREATED)

Each is a straight rename of the method call (same args). Example for the implementation success block:

```java
        // 6. 성공 보고
        reporter.reportTerminal(task.id(), new WorkerResultRequest(
                props.id(),
                TaskStatus.PR_CREATED,
                null, null, null, exec.durationMs(), null,
                pr.url(), pr.number(), wt.branchName(), headSha, exec.stdout(),
                null, null, null, null, null
        ));
```

(c) Simplify the 3 `safePost*` failure helpers to use the reporter (it already swallows). Replace their bodies:

```java
    private void safePostDeployFailure(Long taskId, String reason, String deployLog) {
        reporter.reportTerminal(taskId, WorkerResultRequest.deployFailed(props.id(), reason, deployLog));
    }

    private void safePostAnalysisFailure(Long taskId, String reason) {
        reporter.reportTerminal(taskId, new WorkerResultRequest(
                props.id(), TaskStatus.FAILED,
                null, null, null, null, reason,
                null, null, null, null, null,
                null, null, null, null, null));
    }

    private void safePostImplementationFailure(Long taskId, String reason,
                                               String headBranch, String headSha, String log_) {
        reporter.reportTerminal(taskId, new WorkerResultRequest(
                props.id(), TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, reason,
                null, null, headBranch, headSha, log_,
                null, null, null, null, null));
    }
```

(The now-unused `RestClientException` import on the `safePost*` methods stays needed for `pollAndProcess`/`sendHeartbeat`/`nextTask` — do not remove the import.)

- [ ] **Step 7: Add config keys to application-worker.yml**

In `src/main/resources/application-worker.yml`, under `netis-maker.worker:`, after `heartbeat-interval-seconds: 10` add:

```yaml
    heartbeat-interval-seconds: 10
    # 결과 보고 재시도 — 일시적 백엔드 장애(풀 고갈 등)에도 성공/실패 보고가 닿도록.
    result-report-max-retries: 5
    result-report-backoff-ms: 2000
```

- [ ] **Step 8: Run full test suite + compile**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — all tests pass (new ResultReporterTest, WorkerServiceReconcileTest, StaleTaskRecoveryJobTest, plus existing). WorkerMainLoop compiles with the new ResultReporter dependency.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ResultReporter.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/ResultReporterTest.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java \
        src/main/resources/application-worker.yml
git commit -m "fix(worker): 결과 보고 재시도 + 성공보고 비전파 (false-failure 방지)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 범위 밖 (후속)

task16 기존 레코드는 워커가 사라져 자가 치유 불가. 패치 머지 후 일회성 데이터 보정으로 살릴 수 있음:
`UPDATE com.task SET status='PR생성', pr_url='https://github.com/micthebick84/netis7.0/pull/10', pr_number=10, head_branch='netismaker/task-16', failure_reason=NULL WHERE id=16;`
(상태 dbValue 정확값은 `TaskStatus.PR_CREATED.dbValue()` 확인 후 적용.)
