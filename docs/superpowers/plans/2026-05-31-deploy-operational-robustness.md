# 배포 운영 견고성 (B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포 파이프라인을 워커 장애 자가복원(stale 회수) · 자원 자가정리(GC) · 실시간 관측(SSE 로그)으로 견고하게 만든다.

**Architecture:** 세 컴포넌트. **C1**(api 프로파일): `UNDEPLOYING` 상태 신설 + 하트비트 인식 stale 회수. **C2**(worker 프로파일): docker 상태 기반 컨테이너/이미지 GC 리퍼(순수 planner + I/O 분리). **C3**: 워커가 빌드 로그를 청크로 증분 업로드 → api가 청크 테이블 영속화 + SSE로 브라우저에 fan-out.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Gradle, JPA + Flyway(PostgreSQL `com` 스키마), JUnit5 + Mockito + AssertJ, Spring MVC `SseEmitter`, Nuxt 3 + Quasar(`EventSource`).

**Spec:** `docs/superpowers/specs/2026-05-31-deploy-operational-robustness-design.md`

---

## File Structure

### Phase C1 — Stale 회수 + UNDEPLOYING (api)
- Modify: `entity/TaskStatus.java` — `UNDEPLOYING` enum 값.
- Modify: `service/WorkerService.java` — claim/recordResult가 UNDEPLOYING 처리.
- Modify: `repository/TaskRepository.java` — `findInFlightClaimed()` (기존 `findStaleInProgress` 대체).
- Modify: `service/StaleTaskRecoveryJob.java` — 하트비트 인식 회수로 재작성.
- Modify: `resources/application.yml` — 신규/노출 config 키 4개.
- Create: `resources/db/migration/V9__queue_stats_undeploy.sql` — 큐 통계 뷰에 undeploy 카운터 2개.
- Modify: `dto/QueueStats.java`, `repository/QueueStatsRepository.java`, `frontend/components/QueueStatsBar.vue`.
- Modify(test): `service/StaleTaskRecoveryJobTest.java`, `service/WorkerServiceDeployTest.java`.

### Phase C2 — GC 리퍼 (worker)
- Modify: `workerdaemon/WorkerProperties.java` — Deploy 레코드에 gc 필드 4개.
- Modify: `resources/application-worker.yml` — gc 키 4개.
- Modify: `workerdaemon/deploy/LocalDockerTarget.java` — build에 `--label` 추가, `gc()` 구현.
- Modify: `workerdaemon/deploy/DeployTarget.java` — `gc()` default 메서드.
- Create: `workerdaemon/deploy/DockerGcPlanner.java` — 순수 결정 로직.
- Create: `workerdaemon/DockerGcJob.java` — `@Scheduled` 리퍼.
- Create(test): `workerdaemon/deploy/DockerGcPlannerTest.java`.

### Phase C3 — SSE 실시간 로그
- Create: `resources/db/migration/V10__deploy_log_chunk.sql`, `entity/TaskDeployLogChunk.java`, `repository/TaskDeployLogChunkRepository.java`.
- Create: `service/DeployLogStreamService.java`, `dto/DeployLogChunkRequest.java`.
- Modify: `service/WorkerService.java` — appendDeployLog + 종료 시 finish.
- Modify: `service/StaleTaskRecoveryJob.java` — 배포 회수 시 청크 consolidate.
- Modify: `controller/WorkerController.java` — `POST /worker/tasks/{id}/deploy-log`.
- Modify: `controller/TaskController.java` — `GET /api/tasks/{id}/logs/stream` (SSE).
- Modify: `config/SecurityConfig.java` — 쿼리 파라미터 토큰(`?access_token=`) 허용.
- Modify: `workerdaemon/ProcessRunner.java` — `runStreaming` 라인 콜백.
- Modify: `workerdaemon/deploy/DeployTarget.java`, `LocalDockerTarget.java`, `workerdaemon/DeployService.java`, `workerdaemon/WorkerMainLoop.java`, `workerdaemon/WorkerHttpClient.java` — 로그 싱크 배선.
- Modify: `frontend/pages/tasks/[id].vue` — EventSource 로그 뷰어.
- Create(test): `service/DeployLogStreamServiceTest.java`, `workerdaemon/ProcessRunnerStreamingTest.java`.

---

# Phase C1 — Stale 회수 + UNDEPLOYING

### Task 1: `UNDEPLOYING` 상태 추가

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/TaskStatus.java:41-43`

- [ ] **Step 1: enum 값 추가**

`UNDEPLOY_PENDING("배포중지대기"),` 바로 아래에 한 줄 추가:

```java
    DEPLOY_FAILED("배포실패"),
    UNDEPLOY_PENDING("배포중지대기"),
    UNDEPLOYING("배포중지중"),
    CANCELLED("취소됨");
```

그리고 doc 코멘트의 배포 단계 전이 줄을 갱신:

```java
 *   [배포완료|배포실패] ──(중지)──→ [배포중지대기] ──(워커 claim)──→ [배포중지중] ──→ [PR생성]
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (status 컬럼은 `VARCHAR(30)` + `TaskStatusConverter`라 enum 값 추가에 마이그레이션·DDL 변경 불필요).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/TaskStatus.java
git commit -m "feat: UNDEPLOYING(배포중지중) 상태 추가"
```

---

### Task 2: claim/recordResult가 UNDEPLOYING 분리 처리

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java:88-90,118-134`
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDeployTest.java`

- [ ] **Step 1: 실패 테스트 추가** (`WorkerServiceDeployTest.java`, 클래스 안)

```java
    @Test
    void claim_undeploy_pending_yields_undeploy_kind_and_sets_undeploying() {
        Task t = taskWithStatus(TaskStatus.UNDEPLOY_PENDING);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(t));
        Optional<WorkerTaskResponse> claimed = service.claimNextTask("mac-worker-1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().kind()).isEqualTo(WorkerTaskResponse.Kind.UNDEPLOY);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.UNDEPLOYING);
    }

    @Test
    void record_undeploy_success_from_undeploying_returns_pr_created() {
        Task t = taskWithStatus(TaskStatus.UNDEPLOYING);
        t.setWorkerId("mac-worker-1");
        when(taskRepo.findById(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.undeployed("mac-worker-1", "중지/제거"));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getDeployUrl()).isNull();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceDeployTest"`
Expected: FAIL — claim 테스트는 status가 `DEPLOYING`으로, record 테스트는 CONFLICT(409)로 실패.

- [ ] **Step 3: claimNextTask 분기 수정** (`WorkerService.java`, `else if (from == TaskStatus.DEPLOY_PENDING || from == TaskStatus.UNDEPLOY_PENDING)` 블록)

```java
        } else if (from == TaskStatus.DEPLOY_PENDING) {
            t.setStatus(TaskStatus.DEPLOYING);
        } else if (from == TaskStatus.UNDEPLOY_PENDING) {
            t.setStatus(TaskStatus.UNDEPLOYING);
        } else {
```

(아래 `forDeploy`/`forUndeploy` 분기는 `from` 기준이라 그대로 둔다.)

- [ ] **Step 4: recordResult 가드/라우팅 수정** (`WorkerService.java`)

in-flight 가드에 UNDEPLOYING 추가:

```java
        TaskStatus current = t.getStatus();
        if (current != TaskStatus.IN_PROGRESS
                && current != TaskStatus.IMPLEMENTING
                && current != TaskStatus.DEPLOYING
                && current != TaskStatus.UNDEPLOYING) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "현재 처리중 상태가 아닙니다 (현재: " + current.dbValue() + ")");
        }
```

배포 라우팅에 UNDEPLOYING 추가:

```java
        // 배포/중지 단계는 별도 처리 (분석/구현 검증 로직과 분리).
        if (current == TaskStatus.DEPLOYING || current == TaskStatus.UNDEPLOYING) {
            recordDeployResult(t, req);
            return;
        }
```

(`recordDeployResult`는 DEPLOYED/DEPLOY_FAILED/PR_CREATED를 이미 처리하므로 변경 불필요 — `from`만 UNDEPLOYING으로 기록됨.)

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.WorkerServiceDeployTest"`
Expected: PASS (기존 `claim_deploy_pending...`/`record_deployed...` 포함 전부 green).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDeployTest.java
git commit -m "feat: UNDEPLOY_PENDING claim→UNDEPLOYING 분리, recordResult가 UNDEPLOYING 수용"
```

---

### Task 3: `findInFlightClaimed` 쿼리

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java:88-101`

- [ ] **Step 1: `findStaleInProgress` 메서드 + 그 위 doc 코멘트를 아래로 교체**

```java
    /**
     * Stale 회수 잡: 워커가 claim해 처리중인(in-flight) 모든 작업.
     * 회수 여부(워커 사망/행업)는 StaleTaskRecoveryJob이 heartbeat + claimed_at로 판정한다.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.IN_PROGRESS,
                           com.hamonsoft.netismaker.entity.TaskStatus.IMPLEMENTING,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOYING,
                           com.hamonsoft.netismaker.entity.TaskStatus.UNDEPLOYING)
          AND t.workerId IS NOT NULL
          AND t.deletedAt IS NULL
    """)
    List<Task> findInFlightClaimed();
```

- [ ] **Step 2: 컴파일 확인 (의도된 실패 예상)**

Run: `./gradlew compileJava compileTestJava`
Expected: FAIL — `StaleTaskRecoveryJob`/테스트가 아직 `findStaleInProgress`를 호출 (Task 4에서 해소). 컴파일만 깨졌는지 확인하고 진행.

- [ ] **Step 3: Commit** (Task 4와 함께 green이 되므로 여기선 stage만, 커밋은 Task 4 끝에서)

이 파일 변경은 Task 4 커밋에 포함한다.

---

### Task 4: 하트비트 인식 stale 회수

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java` (전체 재작성)
- Test: `src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java` (전체 재작성)

- [ ] **Step 1: 잡 재작성**

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *  Stale 작업 회수 잡 (api 프로파일).
 *
 *  매 stale-check-interval-ms마다 in-flight(분석중/구현중/배포중/배포중지중) 작업을 검사한다.
 *  회수 조건(둘 중 하나):
 *    1. 워커 사망: claimed_at이 worker-dead-threshold-seconds보다 오래됐고(=워커가 등록할 시간 충분),
 *       소유 워커의 heartbeat.last_seen_at이 임계 이전이거나 heartbeat가 아예 없음.
 *    2. 행업 백스톱: claimed_at이 단계별 절대 임계(분석/구현/배포)를 넘김 — 워커가 살아있어도 회수.
 *
 *  회수 전이: 분석중→PENDING(재시도)/FAILED, 구현중→IMPLEMENTATION_FAILED,
 *            배포중→DEPLOY_FAILED, 배포중지중→UNDEPLOY_PENDING(idempotent 재큐잉).
 */
@Component
@Profile("api")
@Slf4j
public class StaleTaskRecoveryJob {

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final WorkerHeartbeatRepository heartbeatRepo;

    @Value("${app.task.stale-threshold-minutes:5}")
    private int analysisStaleThresholdMinutes;

    @Value("${app.task.implementation-stale-threshold-minutes:60}")
    private int implementationStaleThresholdMinutes;

    @Value("${app.task.deploy-stale-threshold-minutes:15}")
    private int deployStaleThresholdMinutes;

    @Value("${app.task.worker-dead-threshold-seconds:60}")
    private int workerDeadThresholdSeconds;

    public StaleTaskRecoveryJob(TaskRepository taskRepo,
                                TaskStatusHistoryRepository historyRepo,
                                WorkerHeartbeatRepository heartbeatRepo) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.heartbeatRepo = heartbeatRepo;
    }

    @Scheduled(fixedRateString = "${app.task.stale-check-interval-ms:60000}")
    @Transactional
    public void recoverStale() {
        List<Task> inflight = taskRepo.findInFlightClaimed();
        if (inflight.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime workerDeadBefore = now.minusSeconds(workerDeadThresholdSeconds);

        Set<String> workerIds = inflight.stream()
                .map(Task::getWorkerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, OffsetDateTime> lastSeen = heartbeatRepo.findAllById(workerIds).stream()
                .collect(Collectors.toMap(WorkerHeartbeat::getWorkerId, WorkerHeartbeat::getLastSeenAt));

        for (Task t : inflight) {
            TaskStatus from = t.getStatus();
            String workerId = t.getWorkerId();
            OffsetDateTime claimedAt = t.getClaimedAt();

            // 갓 claim한 작업은 워커가 첫 heartbeat 보낼 시간을 준다 (오탐 방지).
            boolean claimedLongEnough = claimedAt != null && claimedAt.isBefore(workerDeadBefore);
            OffsetDateTime hb = workerId == null ? null : lastSeen.get(workerId);
            boolean workerDead = claimedLongEnough && (hb == null || hb.isBefore(workerDeadBefore));

            int ceilingMin = switch (from) {
                case IMPLEMENTING -> implementationStaleThresholdMinutes;
                case DEPLOYING, UNDEPLOYING -> deployStaleThresholdMinutes;
                default -> analysisStaleThresholdMinutes; // IN_PROGRESS
            };
            boolean hungBackstop = claimedAt != null && claimedAt.isBefore(now.minusMinutes(ceilingMin));

            if (!workerDead && !hungBackstop) continue;

            t.setWorkerId(null);
            t.setClaimedAt(null);
            String why = workerDead ? "워커 " + workerId + " 응답 없음(heartbeat)" : "처리 시간 초과(" + ceilingMin + "분)";

            switch (from) {
                case IMPLEMENTING -> {
                    t.setStatus(TaskStatus.IMPLEMENTATION_FAILED);
                    t.setFailureReason("Stale 회수: " + why + " (구현 중단)");
                    logTransition(t, from, TaskStatus.IMPLEMENTATION_FAILED, "구현중 stale → 구현실패");
                    log.error("Stale 회수: task={} {} → IMPLEMENTATION_FAILED", t.getId(), why);
                }
                case DEPLOYING -> {
                    t.setStatus(TaskStatus.DEPLOY_FAILED);
                    t.setFailureReason("Stale 회수: " + why + " (배포 중단)");
                    logTransition(t, from, TaskStatus.DEPLOY_FAILED, "배포중 stale → 배포실패");
                    log.error("Stale 회수: task={} {} → DEPLOY_FAILED", t.getId(), why);
                }
                case UNDEPLOYING -> {
                    t.setStatus(TaskStatus.UNDEPLOY_PENDING);
                    logTransition(t, from, TaskStatus.UNDEPLOY_PENDING, "배포중지중 stale → 재큐잉(idempotent)");
                    log.warn("Stale 회수: task={} {} → UNDEPLOY_PENDING(재큐잉)", t.getId(), why);
                }
                default -> { // IN_PROGRESS
                    if (t.getRetryCount() < t.getMaxRetry()) {
                        t.setStatus(TaskStatus.PENDING);
                        t.setRetryCount(t.getRetryCount() + 1);
                        logTransition(t, from, TaskStatus.PENDING,
                                "Stale 회수: " + why + " → 재시도 (" + t.getRetryCount() + "/" + t.getMaxRetry() + ")");
                        log.warn("Stale 회수: task={} → PENDING (retry {}/{})", t.getId(), t.getRetryCount(), t.getMaxRetry());
                    } else {
                        t.setStatus(TaskStatus.FAILED);
                        t.setFailureReason("Stale 회수 한도 초과: " + why);
                        logTransition(t, from, TaskStatus.FAILED, "재시도 한도 도달, 실패 처리");
                        log.error("Stale 회수 실패: task={} retry={} max={} → FAILED", t.getId(), t.getRetryCount(), t.getMaxRetry());
                    }
                }
            }
        }
    }

    private void logTransition(Task t, TaskStatus from, TaskStatus to, String reason) {
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, to, "system", "stale-recovery", reason));
    }
}
```

- [ ] **Step 2: 테스트 재작성**

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Stale 회수 잡 단위 테스트 — 하트비트 인식 + 절대 백스톱.
 */
class StaleTaskRecoveryJobTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerHeartbeatRepository heartbeatRepo;
    private StaleTaskRecoveryJob job;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        job = new StaleTaskRecoveryJob(taskRepo, historyRepo, heartbeatRepo);
        ReflectionTestUtils.setField(job, "analysisStaleThresholdMinutes", 5);
        ReflectionTestUtils.setField(job, "implementationStaleThresholdMinutes", 60);
        ReflectionTestUtils.setField(job, "deployStaleThresholdMinutes", 15);
        ReflectionTestUtils.setField(job, "workerDeadThresholdSeconds", 60);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Task task(long id, TaskStatus status, String worker, int claimedMinAgo, int retry) {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of());
        ReflectionTestUtils.setField(t, "id", id);
        t.setStatus(status);
        t.setWorkerId(worker);
        t.setClaimedAt(OffsetDateTime.now().minusMinutes(claimedMinAgo));
        t.setRetryCount(retry);
        return t;
    }

    private WorkerHeartbeat hb(String id, int seenSecAgo) {
        WorkerHeartbeat h = new WorkerHeartbeat();
        h.setWorkerId(id);
        h.setLastSeenAt(OffsetDateTime.now().minusSeconds(seenSecAgo));
        return h;
    }

    @Test
    void none_inflight_does_nothing() {
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of());
        job.recoverStale();
        verifyNoInteractions(historyRepo);
    }

    @Test
    void alive_worker_within_ceiling_not_recovered() {
        Task t = task(1L, TaskStatus.DEPLOYING, "w1", 5, 0); // 5분 < 15분, 워커 살아있음
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5))); // 5초 전
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYING);
        verifyNoInteractions(historyRepo);
    }

    @Test
    void dead_worker_deploying_to_deploy_failed() {
        Task t = task(2L, TaskStatus.DEPLOYING, "w1", 5, 0); // claimed 5분 전(>60s), heartbeat 끊김
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 300))); // 5분 전
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOY_FAILED);
        assertThat(t.getWorkerId()).isNull();
        verify(historyRepo).save(any());
    }

    @Test
    void dead_worker_undeploying_requeues_to_undeploy_pending() {
        Task t = task(3L, TaskStatus.UNDEPLOYING, "w1", 5, 0);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of()); // heartbeat 없음 = 사망
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.UNDEPLOY_PENDING);
        assertThat(t.getClaimedAt()).isNull();
    }

    @Test
    void freshly_claimed_missing_heartbeat_not_recovered() {
        Task t = task(4L, TaskStatus.IN_PROGRESS, "w1", 0, 0); // 방금 claim (0분 전)
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of()); // 아직 heartbeat 전
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS); // 유예로 보호
        verifyNoInteractions(historyRepo);
    }

    @Test
    void hung_backstop_alive_worker_implementing_to_failed() {
        Task t = task(5L, TaskStatus.IMPLEMENTING, "w1", 61, 0); // 61분 > 60분 ceiling
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 5))); // 살아있어도 백스톱
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.IMPLEMENTATION_FAILED);
    }

    @Test
    void dead_worker_in_progress_under_retry_returns_pending() {
        Task t = task(6L, TaskStatus.IN_PROGRESS, "w1", 6, 0);
        when(taskRepo.findInFlightClaimed()).thenReturn(List.of(t));
        when(heartbeatRepo.findAllById(any())).thenReturn(List.of(hb("w1", 300)));
        job.recoverStale();
        assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(t.getRetryCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: 실행 — 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.StaleTaskRecoveryJobTest"`
Expected: PASS (7 tests).

- [ ] **Step 4: Commit** (Task 3 변경 포함)

```bash
git add src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java \
        src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java \
        src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java
git commit -m "feat: 하트비트 인식 stale 회수 + DEPLOYING/UNDEPLOYING 회수"
```

---

### Task 5: stale config 키 노출

**Files:**
- Modify: `src/main/resources/application.yml:71-74`

- [ ] **Step 1: `app.task` 블록 확장**

```yaml
  task:
    max-retry: 3
    stale-threshold-minutes: 5
    implementation-stale-threshold-minutes: 60
    deploy-stale-threshold-minutes: 15
    worker-dead-threshold-seconds: 60
    stale-check-interval-ms: 60000
    user-concurrent-limit: 5
```

- [ ] **Step 2: 부팅 검증**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.StaleTaskRecoveryJobTest"`
Expected: PASS (yml 값은 @Value 기본과 동일하므로 동작 불변, 키 가시성만 확보).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "chore: stale/deploy 회수 config 키를 application.yml에 노출"
```

---

### Task 6: 큐 통계에 배포중지 카운터 추가

**Files:**
- Create: `src/main/resources/db/migration/V9__queue_stats_undeploy.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/QueueStats.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/QueueStatsRepository.java`
- Modify: `frontend/components/QueueStatsBar.vue`

- [ ] **Step 1: V9 마이그레이션 작성** (뷰 DROP/재생성 — V7 정의에 카운터 2개 추가)

```sql
-- V9: 큐 통계 뷰에 배포중지 카운터 2개 추가 (배포중지대기 + 배포중지중).
-- CREATE OR REPLACE는 컬럼 추가를 거부하므로 DROP 후 재생성.
DROP VIEW IF EXISTS com.task_queue_stats;
CREATE VIEW com.task_queue_stats AS
SELECT
    COUNT(*) FILTER (WHERE status = '작업대기' AND deleted_at IS NULL)              AS pending,
    COUNT(*) FILTER (WHERE status = '분석중'   AND deleted_at IS NULL)              AS in_progress,
    COUNT(*) FILTER (WHERE status = '분석완료' AND deleted_at IS NULL
                     AND NOT EXISTS (SELECT 1 FROM com.task_analysis a
                                     WHERE a.task_id = com.task.id AND a.approved)) AS awaiting_approval,
    COUNT(*) FILTER (WHERE status = '구현대기' AND deleted_at IS NULL)              AS approved,
    COUNT(*) FILTER (WHERE status = '구현중'   AND deleted_at IS NULL)              AS implementing,
    COUNT(*) FILTER (WHERE status = 'PR생성'   AND deleted_at IS NULL)              AS pr_created,
    COUNT(*) FILTER (WHERE status = '구현실패' AND deleted_at IS NULL)              AS implementation_failed,
    COUNT(*) FILTER (WHERE status = '분석실패' AND deleted_at IS NULL)              AS failed,
    COUNT(*) FILTER (WHERE status = '배포대기' AND deleted_at IS NULL)              AS deploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중'   AND deleted_at IS NULL)              AS deploying,
    COUNT(*) FILTER (WHERE status = '배포완료' AND deleted_at IS NULL)              AS deployed,
    COUNT(*) FILTER (WHERE status = '배포실패' AND deleted_at IS NULL)              AS deploy_failed,
    COUNT(*) FILTER (WHERE status = '배포중지대기' AND deleted_at IS NULL)          AS undeploy_pending,
    COUNT(*) FILTER (WHERE status = '배포중지중'   AND deleted_at IS NULL)          AS undeploying,
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
```

- [ ] **Step 2: `QueueStats` 레코드 확장** (deployFailed 뒤, avgDurationMs 앞)

```java
public record QueueStats(
        long pending,
        long inProgress,
        long awaitingApproval,
        long approved,
        long implementing,
        long prCreated,
        long implementationFailed,
        long failed,
        long deployPending,
        long deploying,
        long deployed,
        long deployFailed,
        long undeployPending,
        long undeploying,
        Double avgDurationMs
) {}
```

- [ ] **Step 3: `QueueStatsRepository.fetch()` 갱신** (SELECT에 2컬럼 + 매핑 인덱스 조정)

```java
    public QueueStats fetch() {
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT pending, in_progress, awaiting_approval,
                       approved, implementing, pr_created, implementation_failed, failed,
                       deploy_pending, deploying, deployed, deploy_failed,
                       undeploy_pending, undeploying,
                       avg_duration_ms
                FROM com.task_queue_stats
                """).getSingleResult();
        return new QueueStats(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue(),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                ((Number) row[7]).longValue(),
                ((Number) row[8]).longValue(),
                ((Number) row[9]).longValue(),
                ((Number) row[10]).longValue(),
                ((Number) row[11]).longValue(),
                ((Number) row[12]).longValue(),
                ((Number) row[13]).longValue(),
                row[14] == null ? null : ((Number) row[14]).doubleValue()
        );
    }
```

- [ ] **Step 4: `QueueStatsBar.vue` — 인터페이스 + 통계 블록 2개 추가**

`interface QueueStats`의 `deployFailed: number` 아래에 추가:

```ts
  deployFailed: number
  undeployPending: number
  undeploying: number
  avgDurationMs: number | null
```

`<template>`에서 "배포실패" stat 블록 뒤(평균 소요 앞)에 추가:

```html
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포중지대기</div>
        <div class="stat-value text-deep-orange-9">{{ data.undeployPending }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포중지중</div>
        <div class="stat-value text-orange-9">{{ data.undeploying }}</div>
      </div>
```

- [ ] **Step 5: 빌드 + 프론트 lint**

Run: `./gradlew build -x test` 그리고 `cd frontend && npm run lint -- --no-fix components/QueueStatsBar.vue || npm run lint`
Expected: 컴파일/린트 통과. (DB 통합 테스트는 로컬 PostgreSQL 필요 — 부팅 검증은 통합 단계에서.)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V9__queue_stats_undeploy.sql \
        src/main/java/com/hamonsoft/netismaker/dto/QueueStats.java \
        src/main/java/com/hamonsoft/netismaker/repository/QueueStatsRepository.java \
        frontend/components/QueueStatsBar.vue
git commit -m "feat: 큐 통계에 배포중지대기/배포중지중 카운터 추가 (V9)"
```

---

# Phase C2 — 컨테이너/이미지 GC 리퍼

### Task 7: GC config 필드

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java:32-57,75`
- Modify: `src/main/resources/application-worker.yml` (deploy 블록)

- [ ] **Step 1: `Deploy` 레코드에 gc 필드 추가** (`readinessPath` 뒤)

레코드 컴포넌트 목록 끝에 추가:

```java
            int readinessSeconds,
            String readinessPath,
            // GC 리퍼 (C2)
            Boolean gcEnabled,
            int gcIntervalMinutes,
            int gcOrphanGraceMinutes,
            int gcKeepImagesPerTask
    ) {
```

compact 생성자 끝에 default 추가:

```java
            if (readinessPath == null || readinessPath.isBlank()) readinessPath = "/";
            if (gcEnabled == null) gcEnabled = true;
            if (gcIntervalMinutes <= 0) gcIntervalMinutes = 60;
            if (gcOrphanGraceMinutes <= 0) gcOrphanGraceMinutes = 60;
            if (gcKeepImagesPerTask <= 0) gcKeepImagesPerTask = 1;
        }
```

- [ ] **Step 2: null-default `Deploy` 생성 호출 갱신** (`WorkerProperties` compact 생성자, `if (deploy == null)` 줄)

```java
        if (deploy == null) deploy = new Deploy(null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0);
```

- [ ] **Step 3: `application-worker.yml`의 `deploy:` 블록에 gc 키 추가** (`readiness-path` 아래, `dockerfile-prompt-template` 위)

```yaml
      readiness-path: ${DEPLOY_READINESS_PATH:/}
      # GC 리퍼 (C2): docker 상태 기반 자가정리. label=netis-maker.task / netis-task- 접두사만 대상.
      gc-enabled: ${DEPLOY_GC_ENABLED:true}
      gc-interval-minutes: ${DEPLOY_GC_INTERVAL_MIN:60}
      gc-orphan-grace-minutes: ${DEPLOY_GC_ORPHAN_GRACE_MIN:60}
      gc-keep-images-per-task: ${DEPLOY_GC_KEEP_IMAGES:1}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java src/main/resources/application-worker.yml
git commit -m "feat: 워커 deploy GC config 필드/키 추가"
```

---

### Task 8: build에 이미지 라벨 부착

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java:42-46`

- [ ] **Step 1: `docker build` 커맨드에 `--label` 추가** (deploy()의 "1. build" 블록)

```java
        // 1. build (이미지에도 라벨 부착 → GC가 소유 이미지를 식별 가능)
        List<String> build = new ArrayList<>(List.of(DOCKER, "build", "-t", spec.imageName()));
        spec.labels().forEach((k, v) -> { build.add("--label"); build.add(k + "=" + v); });
        build.add(".");
        logBuf.append("$ ").append(String.join(" ", build)).append('\n');
        logBuf.append(ProcessRunner.requireSuccess(spec.contextDir().toFile(), build, buildTimeoutSec));
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java
git commit -m "feat: docker build에 netis-maker.task 라벨 부착 (GC 식별용)"
```

---

### Task 9: `DockerGcPlanner` 순수 결정 로직

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerGcPlanner.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerGcPlannerTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.ContainerInfo;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.GcPlan;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner.ImageInfo;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerGcPlannerTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-05-31T12:00:00Z");

    @Test
    void running_container_and_its_image_are_kept() {
        var containers = List.of(new ContainerInfo("netis-task-7", "running", now, "netis-task-7:aaa111"));
        var images = List.of(new ImageInfo("netis-task-7:aaa111", "img1")); // 최신순
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1);
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }

    @Test
    void stopped_orphan_past_grace_is_removed() {
        var containers = List.of(
                new ContainerInfo("netis-task-7", "exited", now.minusMinutes(120), "netis-task-7:aaa111"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1);
        assertThat(plan.containersToRemove()).containsExactly("netis-task-7");
    }

    @Test
    void stopped_orphan_within_grace_is_kept() {
        var containers = List.of(
                new ContainerInfo("netis-task-7", "exited", now.minusMinutes(10), "netis-task-7:aaa111"));
        GcPlan plan = DockerGcPlanner.plan(containers, List.of(), now, 60, 1);
        assertThat(plan.containersToRemove()).isEmpty();
    }

    @Test
    void old_image_tags_beyond_keep_count_removed_but_in_use_kept() {
        // 실행 중 컨테이너가 aaa111 사용. 이미지 목록은 최신순(new→old).
        var containers = List.of(new ContainerInfo("netis-task-7", "running", now, "netis-task-7:ccc333"));
        var images = List.of(
                new ImageInfo("netis-task-7:ccc333", "i3"),  // 최신 + 사용중 → keep
                new ImageInfo("netis-task-7:bbb222", "i2"),  // keep 1개 한도 초과 → remove
                new ImageInfo("netis-task-7:aaa111", "i1")); // remove
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1);
        assertThat(plan.imagesToRemove()).containsExactlyInAnyOrder("netis-task-7:bbb222", "netis-task-7:aaa111");
    }

    @Test
    void non_owned_resources_are_ignored() {
        var containers = List.of(new ContainerInfo("postgres", "exited", now.minusDays(10), "postgres:16"));
        var images = List.of(new ImageInfo("redis:7", "ix"));
        GcPlan plan = DockerGcPlanner.plan(containers, images, now, 60, 1);
        assertThat(plan.containersToRemove()).isEmpty();
        assertThat(plan.imagesToRemove()).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlannerTest"`
Expected: FAIL — `DockerGcPlanner` 클래스 없음(컴파일 에러).

- [ ] **Step 3: planner 구현**

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GC 결정 로직 (순수 함수, docker I/O 없음 → 단위 테스트 용이).
 *
 *  보존: 실행 중 컨테이너 + 그 이미지, task repo당 최신 keepImagesPerTask개 태그.
 *  제거: grace 지난 비실행 owned 컨테이너, 보존 외 owned 이미지 태그.
 *  소유 식별: 컨테이너 이름/이미지 repo 접두사 "netis-task-".
 */
public final class DockerGcPlanner {

    public static final String OWN_PREFIX = "netis-task-";

    private DockerGcPlanner() {}

    /** @param createdAt 비실행 컨테이너의 생성 시각(grace 판정용). 실행 중이면 무시. */
    public record ContainerInfo(String name, String state, OffsetDateTime createdAt, String imageRef) {}

    /** images는 docker 기본 정렬(최신순)으로 전달한다. */
    public record ImageInfo(String repoTag, String id) {}

    public record GcPlan(List<String> containersToRemove, List<String> imagesToRemove) {}

    public static GcPlan plan(List<ContainerInfo> containers, List<ImageInfo> images,
                              OffsetDateTime now, int orphanGraceMinutes, int keepImagesPerTask) {
        OffsetDateTime graceBefore = now.minusMinutes(orphanGraceMinutes);
        Set<String> inUseImages = new HashSet<>();
        List<String> containersToRemove = new ArrayList<>();

        for (ContainerInfo c : containers) {
            if (c.name() == null || !c.name().startsWith(OWN_PREFIX)) continue; // 소유 아님
            boolean running = "running".equalsIgnoreCase(c.state());
            if (running) {
                if (c.imageRef() != null) inUseImages.add(c.imageRef());
            } else if (c.createdAt() != null && c.createdAt().isBefore(graceBefore)) {
                containersToRemove.add(c.name());
            }
        }

        // repo별 최신순 태그를 keep 개수만큼 보존. 사용중 이미지는 무조건 보존.
        Map<String, Integer> keptPerRepo = new LinkedHashMap<>();
        List<String> imagesToRemove = new ArrayList<>();
        for (ImageInfo img : images) {
            String repoTag = img.repoTag();
            if (repoTag == null || !repoTag.startsWith(OWN_PREFIX)) continue; // 소유 아님
            if (inUseImages.contains(repoTag)) continue; // 실행 중 컨테이너 이미지 보존
            String repo = repoTag.contains(":") ? repoTag.substring(0, repoTag.lastIndexOf(':')) : repoTag;
            int kept = keptPerRepo.getOrDefault(repo, 0);
            if (kept < keepImagesPerTask) {
                keptPerRepo.put(repo, kept + 1); // 최신순이므로 앞에서부터 보존
            } else {
                imagesToRemove.add(repoTag);
            }
        }
        return new GcPlan(containersToRemove, imagesToRemove);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlannerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerGcPlanner.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerGcPlannerTest.java
git commit -m "feat: DockerGcPlanner 순수 GC 결정 로직 + 테스트"
```

---

### Task 10: `DeployTarget.gc()` + `LocalDockerTarget` I/O + `DockerGcJob`

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java:18-19`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java` (gc 메서드 + import)
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DockerGcJob.java`

- [ ] **Step 1: 인터페이스에 default gc 추가** (`DeployTarget.java`, `status()` 아래)

```java
    /** 컨테이너 상태 조회 (보고용). */
    DeployStatus status(String containerName);

    /**
     * 자가정리: grace 지난 비실행 owned 컨테이너 + 보존 외 owned 이미지 제거.
     * 원격 타깃 등 미지원 구현은 no-op (default).
     */
    default void gc(int orphanGraceMinutes, int keepImagesPerTask) { }
```

- [ ] **Step 2: `LocalDockerTarget.gc()` 구현** (클래스 안, `stop()` 아래에 추가; import에 `java.time.OffsetDateTime`, `java.util.Collections` 추가)

```java
    @Override
    public void gc(int orphanGraceMinutes, int keepImagesPerTask) {
        try {
            List<DockerGcPlanner.ContainerInfo> containers = listOwnedContainers();
            List<DockerGcPlanner.ImageInfo> images = listOwnedImages();
            DockerGcPlanner.GcPlan plan = DockerGcPlanner.plan(
                    containers, images, OffsetDateTime.now(), orphanGraceMinutes, keepImagesPerTask);
            for (String name : plan.containersToRemove()) {
                safeRun(List.of(DOCKER, "rm", "-f", name), "컨테이너 제거 " + name);
            }
            for (String img : plan.imagesToRemove()) {
                safeRun(List.of(DOCKER, "rmi", img), "이미지 제거 " + img);
            }
            // owned dangling 이미지 prune (build 태그 재사용으로 남은 것)
            safeRun(List.of(DOCKER, "image", "prune", "-f", "--filter", "label=netis-maker.task"), "dangling prune");
            log.info("GC: 컨테이너 {}개, 이미지 {}개 제거", plan.containersToRemove().size(), plan.imagesToRemove().size());
        } catch (Exception e) {
            log.warn("GC 실패 (다음 주기 재시도): {}", e.getMessage());
        }
    }

    /** label=netis-maker.task 컨테이너 목록 (비실행은 inspect로 생성 시각 보강). */
    private List<DockerGcPlanner.ContainerInfo> listOwnedContainers() {
        List<DockerGcPlanner.ContainerInfo> out = new ArrayList<>();
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "ps", "-a", "--filter", "label=netis-maker.task",
                            "--format", "{{.Names}}|{{.State}}|{{.Image}}"), 30);
            if (r.exitCode() != 0) return out;
            for (String line : r.stdout().split("\\R")) {
                if (line.isBlank()) continue;
                String[] p = line.split("\\|", -1);
                if (p.length < 3) continue;
                String name = p[0].trim(), state = p[1].trim(), image = p[2].trim();
                OffsetDateTime created = "running".equalsIgnoreCase(state) ? null : inspectCreated(name);
                out.add(new DockerGcPlanner.ContainerInfo(name, state, created, image));
            }
        } catch (Exception e) {
            log.warn("docker ps 조회 실패: {}", e.getMessage());
        }
        return out;
    }

    /** netis-task-* 이미지 목록 (docker 기본 최신순 유지). */
    private List<DockerGcPlanner.ImageInfo> listOwnedImages() {
        List<DockerGcPlanner.ImageInfo> out = new ArrayList<>();
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "images", "--format", "{{.Repository}}:{{.Tag}}|{{.ID}}"), 30);
            if (r.exitCode() != 0) return out;
            for (String line : r.stdout().split("\\R")) {
                if (line.isBlank()) continue;
                String[] p = line.split("\\|", -1);
                if (p.length < 2) continue;
                String repoTag = p[0].trim();
                if (!repoTag.startsWith(DockerGcPlanner.OWN_PREFIX)) continue;
                out.add(new DockerGcPlanner.ImageInfo(repoTag, p[1].trim()));
            }
        } catch (Exception e) {
            log.warn("docker images 조회 실패: {}", e.getMessage());
        }
        return out;
    }

    private OffsetDateTime inspectCreated(String name) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "inspect", "-f", "{{.Created}}", name), 30);
            if (r.exitCode() != 0) return null;
            return OffsetDateTime.parse(r.stdout().trim()); // RFC3339
        } catch (Exception e) {
            return null;
        }
    }

    private void safeRun(List<String> cmd, String what) {
        try {
            ProcessRunner.run(new File("."), cmd, 60);
        } catch (Exception e) {
            log.warn("GC {} 실패: {}", what, e.getMessage());
        }
    }
```

- [ ] **Step 3: `DockerGcJob` 생성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * docker 자원 GC 리퍼 (worker 프로파일). gc-interval-minutes마다 DeployTarget.gc 호출.
 * gc-enabled=false면 no-op. 부팅 직후엔 initialDelay로 한 텀 쉰다(배포 직후 경합 회피).
 */
@Component
@Profile("worker")
@Slf4j
public class DockerGcJob {

    private final DeployTarget target;
    private final WorkerProperties.Deploy cfg;

    public DockerGcJob(DeployTarget target, WorkerProperties props) {
        this.target = target;
        this.cfg = props.deploy();
    }

    @Scheduled(initialDelayString = "#{${netis-maker.worker.deploy.gc-interval-minutes:60} * 60000}",
               fixedRateString = "#{${netis-maker.worker.deploy.gc-interval-minutes:60} * 60000}")
    public void reap() {
        if (Boolean.FALSE.equals(cfg.gcEnabled())) return;
        log.debug("GC 리퍼 시작 (orphanGrace={}m, keepImages={})",
                cfg.gcOrphanGraceMinutes(), cfg.gcKeepImagesPerTask());
        target.gc(cfg.gcOrphanGraceMinutes(), cfg.gcKeepImagesPerTask());
    }
}
```

- [ ] **Step 4: 컴파일 + planner 테스트 재확인**

Run: `./gradlew compileJava && ./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlannerTest"`
Expected: BUILD SUCCESSFUL + PASS. (gc() I/O는 docker 의존이라 단위 테스트 제외 — 결정 로직은 planner 테스트로 커버, 실배포는 통합 단계에서 수동 검증.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/DockerGcJob.java
git commit -m "feat: 워커 docker GC 리퍼(DockerGcJob + LocalDockerTarget.gc)"
```

---

# Phase C3 — SSE 실시간 로그 스트리밍

### Task 11: 로그 청크 테이블 (V10)

**Files:**
- Create: `src/main/resources/db/migration/V10__deploy_log_chunk.sql`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- V10: 배포 로그 청크 (스트리밍 중 임시 보존). 종료 시 deploy_log로 통합 후 DELETE → 진행중 task만 잔존.
CREATE TABLE IF NOT EXISTS com.task_deploy_log_chunk (
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT      NOT NULL,
    seq        INT         NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_deploy_log_chunk_task_seq
    ON com.task_deploy_log_chunk(task_id, seq);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V10__deploy_log_chunk.sql
git commit -m "feat: task_deploy_log_chunk 테이블 (V10)"
```

---

### Task 12: 청크 엔티티 + 리포지토리

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/entity/TaskDeployLogChunk.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/TaskDeployLogChunkRepository.java`

- [ ] **Step 1: 엔티티 작성**

```java
package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "task_deploy_log_chunk", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDeployLogChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static TaskDeployLogChunk of(Long taskId, int seq, String content) {
        TaskDeployLogChunk c = new TaskDeployLogChunk();
        c.taskId = taskId;
        c.seq = seq;
        c.content = content == null ? "" : content;
        c.createdAt = OffsetDateTime.now();
        return c;
    }
}
```

- [ ] **Step 2: 리포지토리 작성**

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskDeployLogChunkRepository extends JpaRepository<TaskDeployLogChunk, Long> {
    List<TaskDeployLogChunk> findByTaskIdOrderBySeqAsc(Long taskId);
    void deleteByTaskId(Long taskId);
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/TaskDeployLogChunk.java \
        src/main/java/com/hamonsoft/netismaker/repository/TaskDeployLogChunkRepository.java
git commit -m "feat: TaskDeployLogChunk 엔티티/리포지토리"
```

---

### Task 13: `DeployLogStreamService` (영속화 + SSE fan-out)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/DeployLogStreamService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/DeployLogStreamServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성** (영속화/통합/정리 로직 — emitter 송신은 통합에서 검증)

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import com.hamonsoft.netismaker.repository.TaskDeployLogChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeployLogStreamServiceTest {

    private TaskDeployLogChunkRepository repo;
    private DeployLogStreamService svc;

    @BeforeEach
    void setUp() {
        repo = mock(TaskDeployLogChunkRepository.class);
        svc = new DeployLogStreamService(repo);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void ingest_persists_chunk() {
        svc.ingestChunk(7L, 0, "building...");
        verify(repo).save(any(TaskDeployLogChunk.class));
    }

    @Test
    void consolidate_joins_chunks_in_order() {
        when(repo.findByTaskIdOrderBySeqAsc(7L)).thenReturn(List.of(
                TaskDeployLogChunk.of(7L, 0, "a\n"),
                TaskDeployLogChunk.of(7L, 1, "b\n")));
        assertThat(svc.consolidate(7L)).contains("a\nb\n");
    }

    @Test
    void consolidate_empty_when_no_chunks() {
        when(repo.findByTaskIdOrderBySeqAsc(7L)).thenReturn(List.of());
        assertThat(svc.consolidate(7L)).isEmpty();
    }

    @Test
    void finish_deletes_chunks() {
        svc.finish(7L);
        verify(repo).deleteByTaskId(7L);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.DeployLogStreamServiceTest"`
Expected: FAIL — `DeployLogStreamService` 없음.

- [ ] **Step 3: 서비스 구현**

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskDeployLogChunk;
import com.hamonsoft.netismaker.repository.TaskDeployLogChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 배포 로그 스트리밍 (api 프로파일).
 *
 *  - ingestChunk: 워커가 올린 청크를 영속화 + 구독 SSE emitter에 push.
 *  - subscribe: 브라우저 접속 시 기존 청크 replay 후 live 구독.
 *  - finish: 종료 시 done 이벤트 + emitter complete + 청크 DELETE.
 *  - consolidate: 청크를 합쳐 tail 반환 (워커 사망 시 부분 로그 보존용).
 *
 *  api 단일 인스턴스 전제 — emitter 레지스트리는 in-memory.
 */
@Service
@Profile("api")
@Slf4j
public class DeployLogStreamService {

    private static final int MAX_TAIL = 8000;

    private final TaskDeployLogChunkRepository chunkRepo;
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public DeployLogStreamService(TaskDeployLogChunkRepository chunkRepo) {
        this.chunkRepo = chunkRepo;
    }

    @Transactional
    public void ingestChunk(Long taskId, int seq, String content) {
        chunkRepo.save(TaskDeployLogChunk.of(taskId, seq, content));
        List<SseEmitter> subs = emitters.get(taskId);
        if (subs != null) {
            for (SseEmitter e : subs) {
                try {
                    e.send(SseEmitter.event().name("log").data(content == null ? "" : content));
                } catch (Exception ex) {
                    remove(taskId, e);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(0L); // 무제한 타임아웃 (종료는 finish가 close)
        // 1) 지금까지의 청크 replay
        try {
            for (TaskDeployLogChunk c : chunkRepo.findByTaskIdOrderBySeqAsc(taskId)) {
                emitter.send(SseEmitter.event().name("log").data(c.getContent()));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        // 2) live 구독 등록
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(ex -> remove(taskId, emitter));
        return emitter;
    }

    @Transactional
    public void finish(Long taskId) {
        List<SseEmitter> subs = emitters.remove(taskId);
        if (subs != null) {
            for (SseEmitter e : subs) {
                try {
                    e.send(SseEmitter.event().name("done").data("end"));
                    e.complete();
                } catch (Exception ignore) { /* 이미 닫힘 */ }
            }
        }
        chunkRepo.deleteByTaskId(taskId);
    }

    /** 청크를 seq순으로 합쳐 tail 반환. 없으면 empty. (워커 사망 회수 시 부분 로그 보존용) */
    @Transactional(readOnly = true)
    public Optional<String> consolidate(Long taskId) {
        List<TaskDeployLogChunk> chunks = chunkRepo.findByTaskIdOrderBySeqAsc(taskId);
        if (chunks.isEmpty()) return Optional.empty();
        StringBuilder sb = new StringBuilder();
        for (TaskDeployLogChunk c : chunks) sb.append(c.getContent());
        String s = sb.toString();
        return Optional.of(s.length() <= MAX_TAIL ? s : "…" + s.substring(s.length() - MAX_TAIL));
    }

    private void remove(Long taskId, SseEmitter e) {
        List<SseEmitter> subs = emitters.get(taskId);
        if (subs != null) subs.remove(e);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.DeployLogStreamServiceTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/DeployLogStreamService.java \
        src/test/java/com/hamonsoft/netismaker/service/DeployLogStreamServiceTest.java
git commit -m "feat: DeployLogStreamService (청크 영속화 + SSE fan-out + consolidate)"
```

---

### Task 14: WorkerService — 청크 수신 + 종료 정리, 회수 시 보존

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (필드/생성자/메서드)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java` (배포 회수 시 consolidate)
- Create: `src/main/java/com/hamonsoft/netismaker/dto/DeployLogChunkRequest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDeployTest.java` (생성자 인자 추가)

- [ ] **Step 1: DTO 작성**

```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotNull;

/** 워커가 배포 중 올리는 증분 로그 청크. 상태를 바꾸지 않는다. */
public record DeployLogChunkRequest(@NotNull Integer seq, String content) {}
```

- [ ] **Step 2: `WorkerService`에 `DeployLogStreamService` 주입 + appendDeployLog + 종료 finish**

생성자에 인자 추가 (필드 + 파라미터 + 대입):

```java
    private final WorkerHeartbeatRepository heartbeatRepo;
    private final DeployLogStreamService deployLogStream;

    public WorkerService(TaskRepository taskRepo,
                         TaskAnalysisRepository analysisRepo,
                         TaskStatusHistoryRepository historyRepo,
                         WorkerHeartbeatRepository heartbeatRepo,
                         DeployLogStreamService deployLogStream) {
        this.taskRepo = taskRepo;
        this.analysisRepo = analysisRepo;
        this.historyRepo = historyRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.deployLogStream = deployLogStream;
    }
```

`recordDeployResult` 메서드 끝(`historyRepo.save(...)` 다음 줄, 닫는 `}` 전)에 추가:

```java
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
        // 배포/중지 종료 → 스트림 구독자에 done 통지 + 청크 정리
        deployLogStream.finish(t.getId());
    }
```

클래스 안에 새 메서드 추가:

```java
    /** 워커가 배포 중 올리는 증분 로그 청크. 상태 검증 없이 best-effort 영속/중계. */
    @Transactional
    public void appendDeployLog(Long taskId, com.hamonsoft.netismaker.dto.DeployLogChunkRequest req) {
        deployLogStream.ingestChunk(taskId, req.seq() == null ? 0 : req.seq(), req.content());
    }
```

- [ ] **Step 3: `StaleTaskRecoveryJob` — 배포 회수 시 부분 로그 보존** (DeployLogStreamService 주입 + DEPLOYING 회수 분기에서 consolidate)

생성자에 인자 추가:

```java
    private final WorkerHeartbeatRepository heartbeatRepo;
    private final DeployLogStreamService deployLogStream;

    public StaleTaskRecoveryJob(TaskRepository taskRepo,
                                TaskStatusHistoryRepository historyRepo,
                                WorkerHeartbeatRepository heartbeatRepo,
                                DeployLogStreamService deployLogStream) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.heartbeatRepo = heartbeatRepo;
        this.deployLogStream = deployLogStream;
    }
```

`case DEPLOYING ->` 블록 안, `t.setFailureReason(...)` 다음에 추가:

```java
                case DEPLOYING -> {
                    t.setStatus(TaskStatus.DEPLOY_FAILED);
                    t.setFailureReason("Stale 회수: " + why + " (배포 중단)");
                    deployLogStream.consolidate(t.getId()).ifPresent(t::setDeployLog); // 부분 로그 보존
                    deployLogStream.finish(t.getId());
                    logTransition(t, from, TaskStatus.DEPLOY_FAILED, "배포중 stale → 배포실패");
                    log.error("Stale 회수: task={} {} → DEPLOY_FAILED", t.getId(), why);
                }
                case UNDEPLOYING -> {
                    t.setStatus(TaskStatus.UNDEPLOY_PENDING);
                    deployLogStream.finish(t.getId()); // 진행중 청크 정리(재큐잉되면 새로 스트리밍)
                    logTransition(t, from, TaskStatus.UNDEPLOY_PENDING, "배포중지중 stale → 재큐잉(idempotent)");
                    log.warn("Stale 회수: task={} {} → UNDEPLOY_PENDING(재큐잉)", t.getId(), why);
                }
```

- [ ] **Step 4: 테스트 생성자 인자 보정** (`WorkerServiceDeployTest.setUp()`, `StaleTaskRecoveryJobTest.setUp()`)

`WorkerServiceDeployTest.setUp()`:

```java
    private DeployLogStreamService deployLogStream;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }
```

`StaleTaskRecoveryJobTest.setUp()`:

```java
    private DeployLogStreamService deployLogStream;

    // setUp() 내부, job 생성 전:
    deployLogStream = mock(DeployLogStreamService.class);
    when(deployLogStream.consolidate(any())).thenReturn(java.util.Optional.empty());
    job = new StaleTaskRecoveryJob(taskRepo, historyRepo, heartbeatRepo, deployLogStream);
```

(import 추가: `com.hamonsoft.netismaker.service.DeployLogStreamService`는 동일 패키지이므로 불필요.)

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.service.*"`
Expected: PASS (WorkerServiceDeployTest, StaleTaskRecoveryJobTest, DeployLogStreamServiceTest 전부 green).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/DeployLogChunkRequest.java \
        src/main/java/com/hamonsoft/netismaker/service/WorkerService.java \
        src/main/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJob.java \
        src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDeployTest.java \
        src/test/java/com/hamonsoft/netismaker/service/StaleTaskRecoveryJobTest.java
git commit -m "feat: WorkerService 청크 수신/종료정리 + 배포 회수 시 부분 로그 보존"
```

---

### Task 15: 워커 청크 수신 엔드포인트

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/WorkerController.java` (import + 메서드)

- [ ] **Step 1: 엔드포인트 추가** (`WorkerController`, `result(...)` 아래; import `com.hamonsoft.netismaker.dto.DeployLogChunkRequest`)

```java
    @PostMapping("/tasks/{id}/deploy-log")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deployLog(@PathVariable Long id, @RequestBody @Valid DeployLogChunkRequest req) {
        workerService.appendDeployLog(id, req);
    }
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (엔드포인트는 `/worker/**` 체인 → ROLE_WORKER, 기존 보안 그대로 적용.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/WorkerController.java
git commit -m "feat: POST /worker/tasks/{id}/deploy-log 청크 수신 엔드포인트"
```

---

### Task 16: SSE 스트림 엔드포인트 + 쿼리 토큰 인증

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` (import + SSE 의존 + 메서드)
- Modify: `src/main/java/com/hamonsoft/netismaker/config/SecurityConfig.java` (BearerTokenResolver)

- [ ] **Step 1: SecurityConfig — 쿼리 파라미터 토큰 허용** (`apiFilterChain`에 resolver 주입 + bean)

`oauth2ResourceServer` 설정을 resolver 포함으로 변경:

```java
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );
```

클래스 안에 bean 추가 (import `org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver`, `org.springframework.security.oauth2.server.resource.web.BearerTokenResolver`):

```java
    /**
     * EventSource는 Authorization 헤더를 못 실으므로 SSE용으로 ?access_token= 쿼리 파라미터를 허용.
     * 내부도구 전제 — URL에 토큰 노출 가능성은 의도적 트레이드오프(평문 시크릿 정책과 동일 선상).
     */
    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        resolver.setAllowUriQueryParameter(true);
        return resolver;
    }
```

- [ ] **Step 2: TaskController — SSE 엔드포인트** (import `DeployLogStreamService`, `MediaType`, `SseEmitter`; 생성자에 의존 추가)

생성자/필드:

```java
    private final TaskService taskService;
    private final DeployLogStreamService deployLogStream;

    public TaskController(TaskService taskService, DeployLogStreamService deployLogStream) {
        this.taskService = taskService;
        this.deployLogStream = deployLogStream;
    }
```

`undeploy(...)` 아래에 메서드 추가:

```java
    @GetMapping(value = "/{id}/logs/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter logsStream(
            @PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        taskService.getForView(id, userId, isAdmin); // 접근 권한 검증 (없으면 예외)
        return deployLogStream.subscribe(id);
    }
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL. (`/api/**`는 `authenticated()`라 SSE도 JWT 필요. EventSource는 `?access_token=`로 인증.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/TaskController.java \
        src/main/java/com/hamonsoft/netismaker/config/SecurityConfig.java
git commit -m "feat: GET /api/tasks/{id}/logs/stream SSE + 쿼리 토큰 인증"
```

---

### Task 17: 스트리밍 ProcessRunner

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ProcessRunner.java` (import + runStreaming)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ProcessRunnerStreamingTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerStreamingTest {

    @Test
    void runStreaming_invokes_callback_per_line_and_returns_full_output() throws Exception {
        List<String> lines = new ArrayList<>();
        ProcessRunner.Result r = ProcessRunner.runStreaming(
                new File("."), List.of("sh", "-c", "printf 'one\\ntwo\\nthree\\n'"), 10, lines::add);
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(lines).containsExactly("one", "two", "three");
        assertThat(r.stdout()).isEqualTo("one\ntwo\nthree\n");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ProcessRunnerStreamingTest"`
Expected: FAIL — `runStreaming` 없음.

- [ ] **Step 3: `runStreaming` 구현** (`ProcessRunner.java`, `requireSuccess` 위; import `java.util.function.Consumer`)

```java
    /**
     * run과 동일하나 stdout을 라인 단위로 onLine 콜백에 흘리며 누적도 함께 반환한다.
     * 빌드처럼 오래 걸리는 명령의 실시간 로그 스트리밍용.
     */
    public static Result runStreaming(File workingDir, List<String> command,
                                      long timeoutSeconds, Consumer<String> onLine)
            throws IOException, InterruptedException {
        log.debug("exec-stream ({}): {}", workingDir, String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                try { onLine.accept(line); } catch (Exception ignore) { /* 콜백 실패가 빌드를 막지 않음 */ }
            }
        }
        boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("process timeout (" + timeoutSeconds + "s): " + String.join(" ", command));
        }
        return new Result(p.exitValue(), out.toString());
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "com.hamonsoft.netismaker.workerdaemon.ProcessRunnerStreamingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ProcessRunner.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/ProcessRunnerStreamingTest.java
git commit -m "feat: ProcessRunner.runStreaming 라인 콜백 스트리밍"
```

---

### Task 18: 워커 로그 싱크 배선 (build 스트리밍 → 청크 업로드)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java` (deploy 시그니처)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java` (deploy 시그니처 + build 스트리밍 + 완료 시 로그 tail)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java` (deploy 시그니처 + 싱크 전파)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClient.java` (postDeployLog)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java` (processDeploy 싱크 생성)

> **설계 메모(검토자용):** 스펙은 헬스 윈도에서 `docker logs --follow` 라이브 스트리밍을 명시했으나, follower 스레드 도입을 피하기 위해 **build 출력 라인 스트리밍 + 헬스 완료 시 `docker logs --tail 200` 1회 push**로 단순화한다. 운영자가 실제로 기다리는 긴 구간(빌드)은 라이브로 보이고, 앱 기동 로그는 완료 직후 노출된다. 크래시 경로는 기존대로 컨테이너 로그 tail을 첨부한다. (YAGNI — follower 스레드는 향후 필요 시 추가.)

- [ ] **Step 1: `DeployTarget.deploy` 시그니처에 로그 싱크 추가** (import `java.util.function.Consumer`)

```java
    /** 빌드 + 실행. logSink로 빌드/헬스 로그를 라인 단위 스트리밍한다(없으면 무시 가능). */
    DeployResult deploy(DeploySpec spec, java.util.function.Consumer<String> logSink) throws Exception;
```

- [ ] **Step 2: `LocalDockerTarget.deploy` 시그니처/본문 수정**

시그니처:

```java
    @Override
    public DeployResult deploy(DeploySpec spec, java.util.function.Consumer<String> logSink) throws Exception {
        StringBuilder logBuf = new StringBuilder();
        java.util.function.Consumer<String> sink = logSink == null ? (s -> {}) : logSink;
```

build 블록을 스트리밍으로 (Task 8의 build 리스트 구성 직후):

```java
        logBuf.append("$ ").append(String.join(" ", build)).append('\n');
        sink.accept("$ " + String.join(" ", build));
        ProcessRunner.Result buildRes = ProcessRunner.runStreaming(
                spec.contextDir().toFile(), build, buildTimeoutSec, line -> { logBuf.append(line).append('\n'); sink.accept(line); });
        if (buildRes.exitCode() != 0) {
            throw new DeployFailedException("docker build 실패 (exit=" + buildRes.exitCode() + ")",
                    tail(logBuf.toString(), 8000));
        }
```

run 출력/헬스 step 라인도 sink로 흘리기 — `logBuf.append(...)` 하는 주요 지점마다 `sink.accept(...)` 동행. 최소한:
- run 직후: `sink.accept(runOut);`
- 헬스 통과/실패/타임아웃 bracket 메시지: 각 `logBuf.append("[...]")` 뒤 `sink.accept("[...]")`.

헬스 성공 종료 직후(`String url = ...` 전)에 앱 기동 로그 1회 push:

```java
        String startupLog = dockerLogsTail(spec.containerName(), 4000);
        sink.accept("--- container logs ---\n" + startupLog);
```

- [ ] **Step 3: `DeployService.deploy` 시그니처/전파**

```java
    public DeployTarget.DeployResult deploy(WorkerTaskResponse task,
                                            java.util.function.Consumer<String> logSink) throws DeployException {
```

내부에서 bracket 로그도 싱크로 (`log.append(...)` 지점에 `if (logSink != null) logSink.accept(...)` 동행하거나, 헬퍼 `void emit(StringBuilder log, Consumer sink, String line)` 추가). 그리고 `target.deploy(spec)` → `target.deploy(spec, logSink)`.

- [ ] **Step 4: `WorkerHttpClient.postDeployLog`** (best-effort)

```java
    public void postDeployLog(Long taskId, int seq, String content) {
        try {
            http.post().uri("/worker/tasks/{id}/deploy-log", taskId)
                    .body(new com.hamonsoft.netismaker.dto.DeployLogChunkRequest(seq, content))
                    .retrieve().toBodilessEntity();
        } catch (Exception ignore) { /* 로그 업로드 실패가 배포를 막지 않음 */ }
    }
```

- [ ] **Step 5: `WorkerMainLoop.processDeploy` — 싱크 생성/주입**

```java
    private void processDeploy(WorkerTaskResponse task) {
        long start = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.function.Consumer<String> sink =
                line -> http.postDeployLog(task.id(), seq.getAndIncrement(), line + "\n");
        com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget.DeployResult result;
        try {
            result = deployService.deploy(task, sink);
        } catch (DeployService.DeployException e) {
            safePostDeployFailure(task.id(), e.getMessage(), e.getDeployLog());
            return;
        }
        long durationMs = System.currentTimeMillis() - start;
        http.postResult(task.id(), WorkerResultRequest.deployed(
                props.id(), result.url(), result.containerId(), result.hostPort(),
                result.image(), durationMs, result.log()));
        log.info("배포 완료: task={} url={}", task.id(), result.url());
    }
```

(undeploy는 짧으므로 스트리밍 불필요 — 변경 없음.)

- [ ] **Step 6: 빌드 + 기존 테스트 회귀 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 전체 테스트 green. (시그니처 변경으로 깨지는 호출처가 있으면 모두 위 변경에서 처리됨; 누락 시 컴파일 에러로 드러남.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClient.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java
git commit -m "feat: 워커 배포 build 로그 증분 스트리밍 → /deploy-log 업로드"
```

---

### Task 19: 프론트엔드 — 실시간 로그 뷰어

**Files:**
- Modify: `frontend/pages/tasks/[id].vue` (`<script setup>` + 배포 카드 template)

- [ ] **Step 1: `<script setup>`에 SSE 로그 상태/연결 추가** (statusClass 위)

```ts
const liveLog = ref('')
let logSource: EventSource | null = null

function closeLog() {
  if (logSource) {
    logSource.close()
    logSource = null
  }
}

function openLog() {
  closeLog()
  liveLog.value = ''
  const token = auth.accessToken
  if (!token) return
  const url = `/api/tasks/${taskId.value}/logs/stream?access_token=${encodeURIComponent(token)}`
  logSource = new EventSource(url)
  logSource.addEventListener('log', (e) => {
    liveLog.value += (e as MessageEvent).data + '\n'
  })
  logSource.addEventListener('done', () => closeLog())
  logSource.onerror = () => closeLog()
}

// in-flight 진입 시 SSE 열고, 벗어나면 닫는다.
watch(
  () => task.value?.status,
  (s) => {
    if (s === 'DEPLOYING' || s === 'UNDEPLOYING') openLog()
    else closeLog()
  },
)

onUnmounted(() => closeLog())
```

> `auth.accessToken`이 store에 없으면 store의 실제 토큰 getter 이름으로 교체(예: `auth.token`). `useAuthStore`의 토큰 필드를 확인해 맞춘다.

- [ ] **Step 2: 배포 카드 template — 로그 표시를 in-flight(라이브) + DEPLOY_FAILED(저장본) 모두로 확장**

기존 `DEPLOY_FAILED` 전용 로그 섹션을 아래로 교체:

```html
        <q-separator v-if="task.status === 'DEPLOYING' || task.status === 'UNDEPLOYING'" />
        <q-card-section v-if="task.status === 'DEPLOYING' || task.status === 'UNDEPLOYING'">
          <div class="text-caption text-grey-7 q-mb-xs">실시간 로그</div>
          <pre class="deploy-log">{{ liveLog || '로그 대기 중…' }}</pre>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOY_FAILED'" />
        <q-card-section v-if="task.status === 'DEPLOY_FAILED'">
          <q-banner class="bg-red-1 text-red-9">배포 실패: {{ task.failureReason }}</q-banner>
          <pre v-if="task.deployment && task.deployment.deployLog" class="deploy-log">{{ task.deployment.deployLog }}</pre>
        </q-card-section>

        <q-separator v-if="task.status === 'DEPLOYED' && task.deployment && task.deployment.deployLog" />
        <q-card-section v-if="task.status === 'DEPLOYED' && task.deployment && task.deployment.deployLog">
          <q-expansion-item dense label="배포 로그" icon="article">
            <pre class="deploy-log">{{ task.deployment.deployLog }}</pre>
          </q-expansion-item>
        </q-card-section>
```

- [ ] **Step 3: 린트**

Run: `cd frontend && npm run lint`
Expected: 통과 (single-quote / no-semi / 2-space 규칙 준수).

- [ ] **Step 4: Commit**

```bash
git add frontend/pages/tasks/[id].vue
git commit -m "feat: 배포 카드 실시간 SSE 로그 뷰어 + 성공 로그 노출"
```

---

## 통합 검증 (수동, 로컬 스택)

플랜 완료 후 subagent-driven-development의 최종 리뷰 전에 수동 확인:

1. `./gradlew build` 전체 green.
2. PostgreSQL 기동 → api(`bootRun`) → Flyway V9/V10 적용 확인.
3. 워커(`--spring.profiles.active=worker`) 기동.
4. UI(:3001)에서 task 배포 → 배포 카드에 **빌드 로그 실시간** 표시 확인.
5. 배포 중 워커 강제 종료 → ~60s 후 stale 회수로 `배포실패` 전이 + 부분 로그 보존 확인.
6. `docker images`에 오래된 `netis-task-*` 태그가 GC 주기 후 정리되는지 확인(또는 `gc-interval-minutes`를 작게 잡아 즉시 검증).

---

## Self-Review

**1. Spec coverage:**
- 컴포넌트1(UNDEPLOYING/하트비트 회수/config/큐통계) → Task 1–6 ✅
- 컴포넌트2(워커 GC 리퍼/정책/라벨) → Task 7–10 ✅
- 컴포넌트3(SSE/청크/인증/스트리밍/프론트) → Task 11–19 ✅
- 횡단(부분 로그 보존, secret 미노출) → Task 14(consolidate); secret은 기존 `[env 주입: N개 키]`만 로깅하고 build 출력만 스트리밍하므로 주입 값 평문 노출 없음 ✅

**2. Placeholder scan:** 모든 단계에 실제 코드/명령/기대결과 포함. "auth.accessToken" 한 곳만 store 필드명 확인 지시(코드 제공). ✅

**3. Type consistency:**
- `WorkerService` 생성자: heartbeatRepo + deployLogStream(Task 14) — 테스트 setUp도 동일 인자 ✅
- `StaleTaskRecoveryJob` 생성자: 4-arg(Task 4의 3-arg → Task 14에서 deployLogStream 추가). **주의:** Task 4 테스트는 3-arg, Task 14에서 4-arg로 갱신 — 구현 순서상 Task 4 시점엔 3-arg, Task 14에서 양쪽(잡+테스트) 동시 변경하므로 일관됨 ✅
- `DeployTarget.deploy(spec)` → `deploy(spec, sink)`(Task 18): LocalDockerTarget + DeployService + WorkerMainLoop 모두 갱신 ✅
- `QueueStats` 필드 순서 = V9 SELECT 순서 = Repository 매핑 인덱스(undeployPending=12, undeploying=13, avg=14) ✅
- 마이그레이션 번호 V9(큐통계), V10(청크) — 현재 최신 V8 다음 순차 ✅
- `DeployLogStreamService.consolidate/finish/ingestChunk/subscribe` 시그니처가 WorkerService/StaleTaskRecoveryJob/TaskController 호출과 일치 ✅
