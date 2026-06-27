# 워커 silent-loss 관측성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결과 보고 유실(silent loss)을 관측 가능하게 만든다 — 유실 카운트를 heartbeat→admin UI로 노출 + 유실 페이로드를 로컬 dead-letter 파일에 보존.

**Architecture:** `ResultReporter`의 모든 최종 실패 경로를 단일 `fail()`로 합류시켜 주입된 `LostReportListener` 콜백을 호출(호출부 무수정). 콜백 구현 `SilentLossTracker`가 in-memory 카운트 누적 + dead-letter JSONL append. 카운트는 heartbeat 페이로드에 실려 백엔드→`workers.vue`까지 표면화.

**Tech Stack:** Java 21, Spring Boot 3.4.1, JPA/Flyway(PostgreSQL `com` 스키마), JUnit 5 + Mockito + AssertJ, Nuxt 3 + Quasar(frontend), Gradle.

## Global Constraints

- 테스트: 대상 단위테스트만 실행(전체 `./gradlew test`는 로컬 Docker 비호환 Testcontainers 통합테스트 포함 → CI 전용).
- 신규 설정 키/컬럼은 기본값 보유: `dead-letter-dir`는 record 컴팩트 생성자 기본값, `lost_report_count`는 `NOT NULL DEFAULT 0`(무중단).
- 구버전 워커 호환: `WorkerHeartbeatRequest.lostReportCount`는 nullable, 백엔드는 `null→0` 매핑.
- `ResultReporter`는 **절대 throw 안 함** 불변 유지(리스너 호출도 try/catch로 감쌈).
- 카운트 의미: 워커 프로세스 시작 이후 누적, 재시작 시 0(in-memory). dead-letter 파일이 durable 기록.
- 최신 Flyway 버전 V12 → 신규 V13. `TaskStatusHistory.log` 등 기존 시그니처 불변.
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- frontend 스타일: ESLint+Prettier(single quote, semicolon, 2-space, `<script setup lang="ts">`).

## File Structure

- `workerdaemon/ResultReporter.java` (수정) — `LostReportListener` nested interface + 단일 `fail()` 합류 + 리스너 주입.
- `workerdaemon/SilentLossTracker.java` (생성) — 카운트 + dead-letter JSONL writer.
- `workerdaemon/WorkerProperties.java` (수정) — `deadLetterDir` 필드.
- `workerdaemon/WorkerMainLoop.java` (수정) — `SilentLossTracker` 주입 + heartbeat에 count 전달.
- `dto/WorkerHeartbeatRequest.java` (수정) — `lostReportCount` 필드.
- `entity/WorkerHeartbeat.java` (수정) — `lostReportCount` 컬럼.
- `service/WorkerService.java` (수정) — heartbeat 매핑.
- `controller/WorkerHealthController.java` (수정) — `toView`에 노출.
- `resources/db/migration/V13__worker_heartbeat_lost_report_count.sql` (생성).
- `resources/application-worker.yml` (수정) — `dead-letter-dir` 키.
- `frontend/pages/admin/workers.vue` (수정) — 컬럼 + 강조.
- 테스트: `ResultReporterTest`(확장), `SilentLossTrackerTest`(생성), `WorkerServiceHeartbeatTest`(생성).

---

### Task 1: ResultReporter 리스너 훅 + SilentLossTracker + dead-letter

워커 결과 보고 유실을 카운트하고 로컬 파일에 보존하는 핵심. heartbeat 배선(Task 2)과 분리되어 독립 테스트 가능(`currentCount()`는 만들되 아직 heartbeat에 연결 안 함).

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ResultReporter.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/SilentLossTracker.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java`
- Modify: `src/main/resources/application-worker.yml`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ResultReporterTest.java` (기존 확장)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/SilentLossTrackerTest.java` (생성)

**Interfaces:**
- Produces: `ResultReporter.LostReportListener { void onLost(Long taskId, WorkerResultRequest req, boolean permanent, String reason); }` (nested `@FunctionalInterface`).
- Produces: `SilentLossTracker implements ResultReporter.LostReportListener` with `int currentCount()`.
- Produces: `WorkerProperties.deadLetterDir()` accessor (default `${user.home}/netis-maker/dead-letter`).
- Consumes: 기존 `ResultReporter(Poster,int,long,Sleeper)` 4-arg 테스트 생성자(no-op 리스너로 위임).

- [ ] **Step 1: ResultReporter — 리스너 추가 테스트 작성 (failing)**

`ResultReporterTest.java`에 5-arg 생성자 기반 헬퍼와 테스트를 추가한다. 기존 import에 더해 필요시 `java.util.concurrent.atomic.AtomicReference` 사용. 기존 `REQ`/`reporter(...)` 헬퍼는 그대로 두고, 리스너 캡처용 테스트를 추가:

```java
    // --- silent-loss 리스너 훅 ---

    private record Lost(Long taskId, String status, boolean permanent, String reason) {}

    /** 리스너를 캡처하는 5-arg reporter. */
    private ResultReporter reporterWithListener(ResultReporter.Poster poster, java.util.List<Lost> sink) {
        return new ResultReporter(poster, 2, 1L, ms -> {},
                (taskId, req, permanent, reason) ->
                        sink.add(new Lost(taskId, req.status().dbValue(), permanent, reason)));
    }

    @Test
    void listener_invoked_on_4xx_final_failure_permanent_true() {
        java.util.List<Lost> sink = new java.util.ArrayList<>();
        boolean ok = reporterWithListener((id, r) -> {
            throw new HttpClientErrorException(HttpStatus.CONFLICT);
        }, sink).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).permanent()).isTrue();
        assertThat(sink.get(0).taskId()).isEqualTo(1L);
        assertThat(sink.get(0).status()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
    }

    @Test
    void listener_invoked_on_exhausted_5xx_permanent_false() {
        java.util.List<Lost> sink = new java.util.ArrayList<>();
        boolean ok = reporterWithListener((id, r) -> {
            throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
        }, sink).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).permanent()).isFalse(); // 재시도 소진은 transient
    }

    @Test
    void listener_invoked_on_unexpected_exception_permanent_true() {
        java.util.List<Lost> sink = new java.util.ArrayList<>();
        boolean ok = reporterWithListener((id, r) -> {
            throw new IllegalStateException("boom");
        }, sink).reportTerminal(1L, REQ);
        assertThat(ok).isFalse();
        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).permanent()).isTrue();
    }

    @Test
    void listener_not_invoked_on_success() {
        java.util.List<Lost> sink = new java.util.ArrayList<>();
        boolean ok = reporterWithListener((id, r) -> { /* success */ }, sink).reportTerminal(1L, REQ);
        assertThat(ok).isTrue();
        assertThat(sink).isEmpty();
    }
```

필요한 import 추가: `import org.springframework.web.client.HttpServerErrorException;` (없으면), `import com.hamonsoft.netismaker.entity.TaskStatus;` (이미 있을 수 있음 — REQ가 TaskStatus.PR_CREATED 사용).

- [ ] **Step 2: Run → COMPILE FAIL (5-arg ctor / LostReportListener 없음)**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.ResultReporterTest'`
Expected: 컴파일 실패 — 5-arg 생성자와 `LostReportListener` 미존재.

- [ ] **Step 3: ResultReporter 리팩터 — 리스너 + 단일 fail()**

`ResultReporter.java`를 아래로 교체(내부 로직만 변경, 클래스/패키지/기존 `Poster`·`Sleeper` 유지):

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 *  워커 결과 보고기 — 재시도 + 성공보고 비전파 + 유실 신호.
 *
 *  - 5xx/연결오류(transient): 지수 백오프로 재시도.
 *  - 4xx(HttpClientErrorException): 영구 오류 → 재시도 무의미, 즉시 포기.
 *  - 최종 실패해도 예외를 던지지 않는다(false 반환). 성공 보고의 일시적 백엔드 장애가
 *    상위 catch에서 실패 보고로 뒤집히는 것을 방지(task16 false-failure 재발방지).
 *  - 최종 실패 시 lossListener.onLost(...) 1회 호출(유실 카운트/dead-letter용). 리스너 예외도 삼킴.
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

    /** 결과 보고 최종 실패(유실) 리스너. permanent=true면 4xx/직렬화 등 영구, false면 transient 소진. */
    @FunctionalInterface
    public interface LostReportListener {
        void onLost(Long taskId, WorkerResultRequest req, boolean permanent, String reason);
    }

    private static final LostReportListener NOOP = (t, r, p, why) -> { };

    private final Poster poster;
    private final int maxRetries;
    private final long backoffMs;
    private final Sleeper sleeper;
    private final LostReportListener lossListener;

    public ResultReporter(WorkerHttpClient http, WorkerProperties props, SilentLossTracker tracker) {
        this(http::postResult, props.resultReportMaxRetries(), props.resultReportBackoffMs(), Thread::sleep, tracker);
    }

    ResultReporter(Poster poster, int maxRetries, long backoffMs, Sleeper sleeper) {
        this(poster, maxRetries, backoffMs, sleeper, NOOP);
    }

    ResultReporter(Poster poster, int maxRetries, long backoffMs, Sleeper sleeper, LostReportListener lossListener) {
        this.poster = poster;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
        this.sleeper = sleeper;
        this.lossListener = lossListener == null ? NOOP : lossListener;
    }

    /** 결과 보고. 성공 true, 최종 실패 false. 절대 throw 안 함. */
    public boolean reportTerminal(Long taskId, WorkerResultRequest req) {
        RestClientException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                poster.post(taskId, req);
                return true;
            } catch (HttpClientErrorException e) {
                return fail(taskId, req, true, "4xx " + e.getStatusCode());
            } catch (RestClientException e) {
                last = e;
                log.warn("결과 보고 실패 (시도 {}/{}) task={} status={}: {}",
                        attempt + 1, maxRetries + 1, taskId, req.status(), e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        sleeper.sleep(backoffMs * (1L << attempt)); // 지수 백오프
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return fail(taskId, req, false, "보고 대기 중 인터럽트");
                    }
                }
            } catch (Exception e) {
                // RestClientException 외 예기치 못한 예외(NPE/직렬화 등)는 transient가 아님 → 재시도 무의미.
                return fail(taskId, req, true, "예기치 못한 예외 " + e.getClass().getSimpleName());
            }
        }
        return fail(taskId, req, false, "재시도 소진(" + (maxRetries + 1) + "회)"
                + (last == null ? "" : " " + last.getMessage()));
    }

    /** 최종 실패 처리: ERROR 로그 + 유실 리스너 1회 호출. 절대 throw 안 함. false 반환. */
    private boolean fail(Long taskId, WorkerResultRequest req, boolean permanent, String reason) {
        log.error("결과 보고 최종 실패(SILENT_LOSS) — task={} status={} permanent={} reason={}",
                taskId, req.status().dbValue(), permanent, reason);
        try {
            lossListener.onLost(taskId, req, permanent, reason);
        } catch (Exception e) {
            log.error("silent-loss 리스너 처리 실패 task={} (무시)", taskId, e);
        }
        return false;
    }
}
```

(`HttpClientErrorException`을 별도 catch로 먼저 잡으므로 4xx는 재시도 없이 즉시 fail. `HttpClientErrorException extends RestClientException`이라 순서 중요.)

- [ ] **Step 4: SilentLossTracker 테스트 작성 (failing)**

Create `src/test/java/com/hamonsoft/netismaker/workerdaemon/SilentLossTrackerTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SilentLossTrackerTest {

    private static final WorkerResultRequest REQ = new WorkerResultRequest(
            "mac-worker-1", TaskStatus.PR_CREATED, null, null, null, 1L, null,
            "https://x/pull/1", 1, "netismaker/task-9", "sha", "log",
            null, null, null, null, null);

    private SilentLossTracker tracker(Path dir) {
        return new SilentLossTracker(dir.toString(), "mac-worker-1");
    }

    @Test
    void on_lost_increments_count_and_writes_jsonl(@TempDir Path dir) throws Exception {
        SilentLossTracker t = tracker(dir);
        t.onLost(9L, REQ, false, "재시도 소진(6회)");

        assertThat(t.currentCount()).isEqualTo(1);

        Path file = dir.resolve("mac-worker-1.jsonl");
        assertThat(Files.exists(file)).isTrue();
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);

        JsonNode entry = new ObjectMapper().readTree(lines.get(0));
        assertThat(entry.get("taskId").asLong()).isEqualTo(9L);
        assertThat(entry.get("status").asText()).isEqualTo(TaskStatus.PR_CREATED.dbValue());
        assertThat(entry.get("permanent").asBoolean()).isFalse();
        assertThat(entry.get("reason").asText()).contains("재시도 소진");
        assertThat(entry.has("ts")).isTrue();
    }

    @Test
    void multiple_losses_accumulate(@TempDir Path dir) {
        SilentLossTracker t = tracker(dir);
        t.onLost(1L, REQ, true, "4xx 409");
        t.onLost(2L, REQ, false, "소진");
        assertThat(t.currentCount()).isEqualTo(2);
    }

    @Test
    void creates_dead_letter_dir_if_absent(@TempDir Path dir) {
        Path nested = dir.resolve("sub/dead-letter");
        SilentLossTracker t = new SilentLossTracker(nested.toString(), "mac-worker-1");
        t.onLost(1L, REQ, true, "x");
        assertThat(Files.exists(nested.resolve("mac-worker-1.jsonl"))).isTrue();
    }
}
```

- [ ] **Step 5: Run → COMPILE FAIL (SilentLossTracker 없음)**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.SilentLossTrackerTest'`
Expected: 컴파일 실패.

- [ ] **Step 6: SilentLossTracker 구현**

Create `src/main/java/com/hamonsoft/netismaker/workerdaemon/SilentLossTracker.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  결과 보고 유실 추적기 (ResultReporter.LostReportListener 구현).
 *
 *  - 워커 프로세스 시작 이후 유실 건수 누적(in-memory). heartbeat가 currentCount()를 실어 보냄.
 *  - 유실된 결과 페이로드를 로컬 dead-letter 파일(<dir>/<workerId>.jsonl)에 한 줄씩 append(durable 기록, 수동 재투입용).
 *  - 절대 throw 안 함: 파일 I/O 실패는 로그만.
 */
@Component
@Profile("worker")
@Slf4j
public class SilentLossTracker implements ResultReporter.LostReportListener {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger lostCount = new AtomicInteger();
    private final Path deadLetterFile;

    public SilentLossTracker(WorkerProperties props) {
        this(props.deadLetterDir(), props.id());
    }

    SilentLossTracker(String deadLetterDir, String workerId) {
        this.deadLetterFile = Path.of(deadLetterDir, workerId + ".jsonl");
    }

    @Override
    public synchronized void onLost(Long taskId, WorkerResultRequest req, boolean permanent, String reason) {
        lostCount.incrementAndGet();
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("ts", OffsetDateTime.now().toString());
            node.put("taskId", taskId);
            node.put("status", req.status().dbValue());
            node.put("permanent", permanent);
            node.put("reason", reason);
            node.set("result", mapper.valueToTree(req));

            Files.createDirectories(deadLetterFile.getParent());
            Files.writeString(deadLetterFile, mapper.writeValueAsString(node) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.error("dead-letter 기록 실패 task={} (카운트는 증가됨)", taskId, e);
        }
    }

    /** 워커 시작 이후 누적 유실 건수. */
    public int currentCount() {
        return lostCount.get();
    }
}
```

- [ ] **Step 7: WorkerProperties — deadLetterDir 추가**

`WorkerProperties.java` record 컴포넌트에 `reposDir` 다음 줄(또는 적절 위치)로 추가:

```java
        String reposDir,
        String deadLetterDir,
```

컴팩트 생성자에 기본값 추가(예: `reposDir` 기본값 근처):

```java
        if (deadLetterDir == null || deadLetterDir.isBlank()) {
            deadLetterDir = System.getProperty("user.home") + "/netis-maker/dead-letter";
        }
```

(참고: `reposDir`는 `${REPOS_DIR:...}`로 yml에서 기본값을 받으므로 컴팩트 생성자에 reposDir 기본 처리가 없을 수 있음 — deadLetterDir는 위 블록을 명시적으로 추가.)

- [ ] **Step 8: application-worker.yml — dead-letter-dir 키 추가**

`netis-maker.worker:` 블록의 `repos-dir:` 줄 다음에 추가:

```yaml
    repos-dir: ${REPOS_DIR:${user.home}/netis-maker/repos}
    dead-letter-dir: ${DEAD_LETTER_DIR:${user.home}/netis-maker/dead-letter}
```

- [ ] **Step 9: Run 두 테스트 클래스 → PASS**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.ResultReporterTest' --tests 'com.hamonsoft.netismaker.workerdaemon.SilentLossTrackerTest'`
Expected: ResultReporterTest 9개(기존 5 + 신규 4) PASS, SilentLossTrackerTest 3개 PASS. 출력 pristine.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ResultReporter.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/SilentLossTracker.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java \
        src/main/resources/application-worker.yml \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/ResultReporterTest.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/SilentLossTrackerTest.java
git commit -m "feat(worker): 결과보고 유실 추적 (ResultReporter 콜백 + SilentLossTracker dead-letter)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: heartbeat 표면화 (DTO/엔티티/마이그레이션/서비스/컨트롤러/워커루프)

Task 1의 `SilentLossTracker.currentCount()`를 heartbeat에 실어 백엔드 DB·`/api/workers/health`까지 노출.

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerHeartbeatRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/WorkerHeartbeat.java`
- Create: `src/main/resources/db/migration/V13__worker_heartbeat_lost_report_count.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (`heartbeat`)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/WorkerHealthController.java` (`toView`)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java` (`sendHeartbeat` + ctor)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceHeartbeatTest.java` (생성)

**Interfaces:**
- Consumes: `SilentLossTracker.currentCount()` (Task 1).
- Produces: `WorkerHeartbeatRequest.lostReportCount()` (nullable Integer); `WorkerHeartbeat.getLostReportCount()/setLostReportCount(Integer)`.

- [ ] **Step 1: WorkerService.heartbeat 매핑 테스트 작성 (failing)**

Create `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceHeartbeatTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerHeartbeatRequest;
import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerServiceHeartbeatTest {

    private WorkerHeartbeatRepository heartbeatRepo;
    private WorkerService service;

    @BeforeEach
    void setUp() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskAnalysisRepository analysisRepo = mock(TaskAnalysisRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        DeployLogStreamService deployLogStream = mock(DeployLogStreamService.class);
        service = new WorkerService(taskRepo, analysisRepo, historyRepo, heartbeatRepo, deployLogStream);
        when(heartbeatRepo.findById(any())).thenReturn(Optional.empty());
        when(heartbeatRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private WorkerHeartbeatRequest req(Integer lostReportCount) {
        return new WorkerHeartbeatRequest("mac-worker-1", "host", "0.1.0", true, "ok",
                List.of("local-db"), lostReportCount);
    }

    @Test
    void heartbeat_persists_lost_report_count() {
        service.heartbeat(req(3));
        ArgumentCaptor<WorkerHeartbeat> cap = ArgumentCaptor.forClass(WorkerHeartbeat.class);
        verify(heartbeatRepo).save(cap.capture());
        assertThat(cap.getValue().getLostReportCount()).isEqualTo(3);
    }

    @Test
    void heartbeat_null_lost_report_count_maps_to_zero() {
        service.heartbeat(req(null));
        ArgumentCaptor<WorkerHeartbeat> cap = ArgumentCaptor.forClass(WorkerHeartbeat.class);
        verify(heartbeatRepo).save(cap.capture());
        assertThat(cap.getValue().getLostReportCount()).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run → COMPILE FAIL (DTO 7th 인자 / getLostReportCount 없음)**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceHeartbeatTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: WorkerHeartbeatRequest — lostReportCount 추가**

`WorkerHeartbeatRequest.java`를 교체:

```java
public record WorkerHeartbeatRequest(
        @NotBlank @Size(max = 50) String workerId,
        @Size(max = 100) String hostname,
        @Size(max = 50) String version,
        Boolean claudeSessionOk,
        @Size(max = 20) String vpnStatus,
        List<String> mcps,
        Integer lostReportCount
) {}
```

- [ ] **Step 4: WorkerHeartbeat 엔티티 — 컬럼 추가**

`WorkerHeartbeat.java`의 `mcps` 필드 위(또는 아래)에 추가:

```java
    @Column(name = "lost_report_count", nullable = false)
    private Integer lostReportCount = 0;
```

- [ ] **Step 5: V13 마이그레이션 생성**

Create `src/main/resources/db/migration/V13__worker_heartbeat_lost_report_count.sql`:

```sql
-- 워커 결과보고 유실 카운트 (silent-loss 관측성). 기존 row 안전을 위해 NOT NULL DEFAULT 0.
ALTER TABLE com.worker_heartbeat
    ADD COLUMN IF NOT EXISTS lost_report_count INT NOT NULL DEFAULT 0;
```

- [ ] **Step 6: WorkerService.heartbeat — 매핑 추가**

`heartbeat(...)` 메서드의 `h.setMcps(...)` 다음 줄에 추가:

```java
        h.setMcps(req.mcps() == null ? List.of() : req.mcps());
        h.setLostReportCount(req.lostReportCount() == null ? 0 : req.lostReportCount());
        h.setLastSeenAt(OffsetDateTime.now());
```

- [ ] **Step 7: Run heartbeat 테스트 → PASS**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceHeartbeatTest'`
Expected: 2개 PASS.

- [ ] **Step 8: WorkerHealthController.toView — 노출**

`toView`의 `view.put("mcps", ...)` 다음 줄에 추가:

```java
        view.put("mcps", h.getMcps() == null ? java.util.List.of() : h.getMcps());
        view.put("lostReportCount", h.getLostReportCount());
```

- [ ] **Step 9: WorkerMainLoop — SilentLossTracker 주입 + heartbeat 전달**

`WorkerMainLoop.java`:

(a) 필드 + 생성자 파라미터 추가. `private final ResultReporter reporter;` 다음에:

```java
    private final SilentLossTracker silentLossTracker;
```

생성자 시그니처에 `SilentLossTracker silentLossTracker,` 추가(예: `ResultReporter reporter,` 다음), 본문에 `this.silentLossTracker = silentLossTracker;` 추가.

(b) `sendHeartbeat()`의 `WorkerHeartbeatRequest` 생성에 7번째 인자 추가:

```java
            http.heartbeat(new WorkerHeartbeatRequest(
                    props.id(), hostname(), props.version(), null, null, mcps.getServerNames(),
                    silentLossTracker.currentCount()));
```

- [ ] **Step 10: 백엔드 컴파일 + 대상 단위테스트 회귀**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceHeartbeatTest' --tests 'com.hamonsoft.netismaker.service.WorkerServiceDeployTest' --tests 'com.hamonsoft.netismaker.workerdaemon.ResultReporterTest' --tests 'com.hamonsoft.netismaker.workerdaemon.SilentLossTrackerTest'`
Expected: 전체 컴파일 성공(WorkerMainLoop이 SilentLossTracker 주입으로 빌드) + 대상 테스트 전부 PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/WorkerHeartbeatRequest.java \
        src/main/java/com/hamonsoft/netismaker/entity/WorkerHeartbeat.java \
        src/main/resources/db/migration/V13__worker_heartbeat_lost_report_count.sql \
        src/main/java/com/hamonsoft/netismaker/service/WorkerService.java \
        src/main/java/com/hamonsoft/netismaker/controller/WorkerHealthController.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java \
        src/test/java/com/hamonsoft/netismaker/service/WorkerServiceHeartbeatTest.java
git commit -m "feat(worker): 유실 카운트를 heartbeat→admin API로 표면화 (V13)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 프론트엔드 — admin/workers.vue '유실 보고' 컬럼

`/api/workers/health`의 `lostReportCount`를 워커 대시보드 테이블에 표시(>0 빨강 강조).

**Files:**
- Modify: `frontend/pages/admin/workers.vue`

**Interfaces:**
- Consumes: `/api/workers/health` 응답의 `lostReportCount: number` (Task 2).

- [ ] **Step 1: WorkerHealth 인터페이스에 필드 추가**

`interface WorkerHealth { ... }`의 `mcps: string[]` 다음에 추가:

```ts
  mcps: string[]
  lostReportCount: number
```

- [ ] **Step 2: columns 배열에 컬럼 추가**

`:columns="[ ... ]"`의 `mcps` 컬럼 정의 다음(또는 `vpnStatus` 다음)에 추가:

```ts
        { name: 'mcps', label: 'MCP 도구', field: 'mcps', align: 'left' },
        { name: 'lostReportCount', label: '유실 보고', field: 'lostReportCount', align: 'center' },
```

- [ ] **Step 3: 유실>0 강조 body-cell 템플릿 추가**

`<template #body-cell-mcps="props">...</template>` 블록 다음에 추가(기존 q-td 패턴 따름):

```vue
      <template #body-cell-lostReportCount="props">
        <q-td :props="props">
          <span
            :class="props.row.lostReportCount > 0 ? 'text-red-9 text-weight-bold' : 'text-grey-6'"
          >
            {{ props.row.lostReportCount ?? 0 }}
          </span>
        </q-td>
      </template>
```

- [ ] **Step 4: Lint 통과 확인**

Run: `cd frontend && npm run lint`
Expected: workers.vue 관련 에러 없음(자동 fix 후 클린). (사전 존재하던 무관 경고는 무시.)

- [ ] **Step 5: Commit**

```bash
git add frontend/pages/admin/workers.vue
git commit -m "feat(front): 워커 대시보드에 유실 보고 카운트 컬럼 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 범위 밖 (후속)
- dead-letter 자동 재투입(replay) + admin UI에서 dead-letter 조회/재시도 버튼.
- 외부 webhook/Slack 알림(현재 코드에 알림 인프라 없음).
- dead-letter 파일 로테이션 정책.
