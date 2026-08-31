# 단계별 토큰 사용량·비용 추적 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업의 각 단계(인터뷰·분석·디자인·구현)별 Claude 토큰 사용량+비용을 수집·누적 저장하고, 작업 상세에 단계별로·작업 목록에 총합으로 표시한다.

**Architecture:** 워커의 `claude -p`를 `--output-format json` envelope로 전환(하위 파서 무변경, 파싱 실패 시 plain-text fallback), 신규 `com.task_stage_usage` 테이블에 원자적 누적 upsert. 인터뷰 서비스는 기존 costUsd 경로에 토큰 4종을 추가하고 confirm 시 INTERVIEW 행으로 이관.

**Tech Stack:** Java 21 / Spring Boot 3.4.1 / JPA + Flyway / Testcontainers(gate: `RUN_TESTCONTAINERS=true`), TypeScript + vitest (interview-service), Nuxt 3 + Quasar + vitest (frontend)

**Spec:** `docs/superpowers/specs/2026-08-31-stage-usage-cost-design.md`

## Global Constraints

- 마이그레이션 파일명은 `V21__stage_usage.sql` — 시작 전 `ls src/main/resources/db/migration/`로 V21 미존재 확인, 있으면 다음 번호 사용.
- 모든 엔티티는 `@Table(schema = "com")` 명시 (기존 컨벤션).
- stage 값은 CHECK 제약 없는 VARCHAR: `INTERVIEW | ANALYSIS | DESIGN | IMPLEMENTATION` (V11 status 컨벤션 동일).
- 수집 실패(envelope 파싱 불가, usage 결손)는 본 파이프라인을 절대 실패시키지 않는다 — 경고 로그 후 usage 미수집으로 진행.
- 커밋 메시지는 한국어, 기존 관행(`feat:`/`fix:`/`test:` prefix).
- Java 통합 테스트는 `@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")` + `@SpringBootTest` + `@ContextConfiguration(initializers = TestcontainersConfig.class)` 패턴.
- 프론트 코드: single quotes, semicolons 없음(기존 파일 스타일 따름), `<script setup lang="ts">`.
- 모든 경로는 레포 루트 `/Users/micthebick/IdeaProjects/netismaker` 기준.

---

### Task 1: V21 마이그레이션 + TaskStageUsage 엔티티/리포지토리

**Files:**
- Create: `src/main/resources/db/migration/V21__stage_usage.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/TaskStageUsage.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/TaskStageUsageRepository.java`
- Test: `src/test/java/com/hamonsoft/netismaker/repository/TaskStageUsageRepositoryTest.java`

**Interfaces:**
- Consumes: `com.task` 테이블, `Task.create(...)` (테스트 시드)
- Produces: `TaskStageUsageRepository.accumulate(Long taskId, String stage, BigDecimal costUsd, long inputTokens, long outputTokens, long cacheCreationTokens, long cacheReadTokens)` (누적 upsert), `findByTaskIdOrderByStageAsc(Long)`, `sumCostByTaskIds(List<Long>) → List<CostTotal{getTaskId(), getTotalCostUsd()}>`, 상수 `TaskStageUsage.STAGE_INTERVIEW/STAGE_ANALYSIS/STAGE_DESIGN/STAGE_IMPLEMENTATION`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- V21: 단계별 토큰/비용 누적 (docs/superpowers/specs/2026-08-31-stage-usage-cost-design.md §3)
-- stage: INTERVIEW | ANALYSIS | DESIGN | IMPLEMENTATION (CHECK 제약 없는 VARCHAR — 기존 컨벤션)
CREATE TABLE IF NOT EXISTS com.task_stage_usage (
    task_id  BIGINT      NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    stage    VARCHAR(20) NOT NULL,
    cost_usd              NUMERIC(12,6) NOT NULL DEFAULT 0,
    input_tokens          BIGINT NOT NULL DEFAULT 0,
    output_tokens         BIGINT NOT NULL DEFAULT 0,
    cache_creation_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens     BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, stage)
);

-- 인터뷰 세션 토큰 누적 (total_cost_usd와 동일 방식으로 턴마다 누적)
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS input_tokens          BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS output_tokens         BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_creation_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_read_tokens     BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 2: 실패하는 리포지토리 테스트 작성**

`TaskAttachmentRepositoryTest`와 동일 패턴. 핵심: 같은 (task_id, stage)에 2회 accumulate → 합산 검증.

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStageUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskStageUsageRepositoryTest {

    @Autowired private TaskStageUsageRepository usageRepo;
    @Autowired private TaskRepository taskRepo;

    @BeforeEach void clean() {
        usageRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task savedTask() {
        return taskRepo.save(Task.create("acme/widgets", "main", "제목", "설명",
                "user1", 3, new ArrayList<>(), null, null));
    }

    @Test
    @Transactional
    void 같은_단계에_두_번_accumulate하면_합산된다() {
        Task t = savedTask();
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION,
                new BigDecimal("0.100000"), 1000, 200, 50, 3000);
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_IMPLEMENTATION,
                new BigDecimal("0.250000"), 500, 100, 0, 1000);

        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        TaskStageUsage u = rows.get(0);
        assertThat(u.getCostUsd()).isEqualByComparingTo("0.350000");
        assertThat(u.getInputTokens()).isEqualTo(1500);
        assertThat(u.getOutputTokens()).isEqualTo(300);
        assertThat(u.getCacheCreationTokens()).isEqualTo(50);
        assertThat(u.getCacheReadTokens()).isEqualTo(4000);
    }

    @Test
    @Transactional
    void sumCostByTaskIds는_task별_총합을_반환한다() {
        Task t = savedTask();
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_ANALYSIS,
                new BigDecimal("0.100000"), 10, 5, 0, 0);
        usageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_DESIGN,
                new BigDecimal("0.200000"), 20, 10, 0, 0);

        var totals = usageRepo.sumCostByTaskIds(List.of(t.getId()));
        assertThat(totals).hasSize(1);
        assertThat(totals.get(0).getTaskId()).isEqualTo(t.getId());
        assertThat(totals.get(0).getTotalCostUsd()).isEqualByComparingTo("0.300000");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인 (컴파일 에러)**

Run: `cd /Users/micthebick/IdeaProjects/netismaker && ./gradlew compileTestJava`
Expected: FAIL — `TaskStageUsage`, `TaskStageUsageRepository` 미존재

- [ ] **Step 4: 엔티티 작성**

```java
package com.hamonsoft.netismaker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 단계별 토큰/비용 누적 (스펙 §3). 쓰기는 전부 TaskStageUsageRepository.accumulate
 * (네이티브 ON CONFLICT upsert) — 이 엔티티는 조회 전용 매핑이다.
 */
@Entity
@Table(name = "task_stage_usage", schema = "com")
@IdClass(TaskStageUsage.Key.class)
@Getter
public class TaskStageUsage {

    public static final String STAGE_INTERVIEW = "INTERVIEW";
    public static final String STAGE_ANALYSIS = "ANALYSIS";
    public static final String STAGE_DESIGN = "DESIGN";
    public static final String STAGE_IMPLEMENTATION = "IMPLEMENTATION";

    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Id
    @Column(name = "stage", length = 20)
    private String stage;

    @Column(name = "cost_usd", nullable = false)
    private BigDecimal costUsd;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cache_creation_tokens", nullable = false)
    private long cacheCreationTokens;

    @Column(name = "cache_read_tokens", nullable = false)
    private long cacheReadTokens;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 복합 PK. equals/hashCode 필수 (JPA IdClass 계약). */
    public static class Key implements Serializable {
        private Long taskId;
        private String stage;

        public Key() {}
        public Key(Long taskId, String stage) { this.taskId = taskId; this.stage = stage; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return java.util.Objects.equals(taskId, k.taskId)
                    && java.util.Objects.equals(stage, k.stage);
        }
        @Override public int hashCode() { return java.util.Objects.hash(taskId, stage); }
    }
}
```

- [ ] **Step 5: 리포지토리 작성**

```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskStageUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface TaskStageUsageRepository extends JpaRepository<TaskStageUsage, TaskStageUsage.Key> {

    /** 누적 upsert — 동시 보고에도 PK 충돌 시 원자적 가산 (스펙 §3). */
    @Modifying
    @Query(value = """
            INSERT INTO com.task_stage_usage
                (task_id, stage, cost_usd, input_tokens, output_tokens,
                 cache_creation_tokens, cache_read_tokens, updated_at)
            VALUES (:taskId, :stage, :costUsd, :inputTokens, :outputTokens,
                    :cacheCreationTokens, :cacheReadTokens, now())
            ON CONFLICT (task_id, stage) DO UPDATE SET
                cost_usd              = task_stage_usage.cost_usd + EXCLUDED.cost_usd,
                input_tokens          = task_stage_usage.input_tokens + EXCLUDED.input_tokens,
                output_tokens         = task_stage_usage.output_tokens + EXCLUDED.output_tokens,
                cache_creation_tokens = task_stage_usage.cache_creation_tokens + EXCLUDED.cache_creation_tokens,
                cache_read_tokens     = task_stage_usage.cache_read_tokens + EXCLUDED.cache_read_tokens,
                updated_at            = now()
            """, nativeQuery = true)
    void accumulate(@Param("taskId") Long taskId, @Param("stage") String stage,
                    @Param("costUsd") BigDecimal costUsd,
                    @Param("inputTokens") long inputTokens,
                    @Param("outputTokens") long outputTokens,
                    @Param("cacheCreationTokens") long cacheCreationTokens,
                    @Param("cacheReadTokens") long cacheReadTokens);

    List<TaskStageUsage> findByTaskIdOrderByStageAsc(Long taskId);

    interface CostTotal {
        Long getTaskId();
        BigDecimal getTotalCostUsd();
    }

    @Query(value = """
            SELECT task_id AS taskId, SUM(cost_usd) AS totalCostUsd
            FROM com.task_stage_usage WHERE task_id IN (:taskIds) GROUP BY task_id
            """, nativeQuery = true)
    List<CostTotal> sumCostByTaskIds(@Param("taskIds") List<Long> taskIds);
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'TaskStageUsageRepositoryTest'`
Expected: PASS (2 tests). 주의: `@Modifying` 네이티브 쿼리는 `@Transactional` 컨텍스트 필요 — 테스트 메서드의 `@Transactional`이 그 역할.

- [ ] **Step 7: 전체 게이트 회귀 확인 + 커밋**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: 전체 PASS (V21이 다른 통합 테스트를 깨지 않음)

```bash
git add src/main/resources/db/migration/V21__stage_usage.sql \
        src/main/java/com/hamonsoft/netismaker/entity/TaskStageUsage.java \
        src/main/java/com/hamonsoft/netismaker/repository/TaskStageUsageRepository.java \
        src/test/java/com/hamonsoft/netismaker/repository/TaskStageUsageRepositoryTest.java
git commit -m "feat: 단계별 토큰/비용 누적 테이블 + 리포지토리 (V21)"
```

---

### Task 2: ClaudeExecAdapter — JSON envelope 전환

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapter.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapterTest.java`

**Interfaces:**
- Consumes: 없음 (독립)
- Produces:
  - `ClaudeExecAdapter.Usage(BigDecimal costUsd, long inputTokens, long outputTokens, long cacheCreationTokens, long cacheReadTokens)` — record
  - `ClaudeExecAdapter.ExecResult(int exitCode, String stdout, long durationMs, Usage usage)` — `usage`는 null 가능(파싱 실패/usage 결손). **`stdout()`의 의미는 불변**: envelope의 `result` 텍스트(기존 plain stdout과 동일 내용)
  - `static ClaudeExecAdapter.Parsed parseEnvelope(String raw)` — `Parsed(String resultText, Usage usage)`, 패키지 프라이빗 static (테스트용)

- [ ] **Step 1: 실패하는 단위 테스트 추가 (기존 ClaudeExecAdapterTest에)**

기존 buildCommand 테스트 3개의 기대값에 `--output-format`, `json`을 `-p` 바로 뒤(skip-permissions보다 뒤)에 추가하고, envelope 파서 테스트를 추가:

```java
    @Test
    void buildCommand_includes_output_format_json() {
        List<String> cmd = ClaudeExecAdapter.buildCommand(
                "/bin/claude", false, null, null, List.of());
        assertThat(cmd).containsExactly("/bin/claude", "-p", "--output-format", "json");
    }

    @Test
    void parseEnvelope_extracts_result_text_and_usage() {
        String raw = """
                {"type":"result","subtype":"success","is_error":false,
                 "result":"## 1. 요구사항 요약\\n내용",
                 "total_cost_usd":0.4231,
                 "usage":{"input_tokens":1200,"output_tokens":340,
                          "cache_creation_input_tokens":50,"cache_read_input_tokens":9000}}
                """;
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).startsWith("## 1. 요구사항 요약");
        assertThat(p.usage()).isNotNull();
        assertThat(p.usage().costUsd()).isEqualByComparingTo("0.4231");
        assertThat(p.usage().inputTokens()).isEqualTo(1200);
        assertThat(p.usage().outputTokens()).isEqualTo(340);
        assertThat(p.usage().cacheCreationTokens()).isEqualTo(50);
        assertThat(p.usage().cacheReadTokens()).isEqualTo(9000);
    }

    @Test
    void parseEnvelope_falls_back_to_raw_on_plain_text() {
        String raw = "## 1. 요구사항 요약\n그냥 텍스트";
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).isEqualTo(raw);
        assertThat(p.usage()).isNull();
    }

    @Test
    void parseEnvelope_missing_usage_fields_default_to_zero() {
        String raw = "{\"type\":\"result\",\"result\":\"ok\",\"total_cost_usd\":0.1}";
        ClaudeExecAdapter.Parsed p = ClaudeExecAdapter.parseEnvelope(raw);
        assertThat(p.resultText()).isEqualTo("ok");
        assertThat(p.usage().inputTokens()).isZero();
        assertThat(p.usage().cacheReadTokens()).isZero();
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'ClaudeExecAdapterTest'`
Expected: FAIL — `parseEnvelope`/`Parsed`/`Usage` 미존재 + buildCommand 기대값 불일치

- [ ] **Step 3: 구현**

`buildCommand`: `cmd.add("-p");` 바로 다음에 (skip-permissions 분기보다 **앞에**):

```java
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        if (dangerouslySkipPermissions) cmd.add("--dangerously-skip-permissions");
```

주의: 기존 테스트 3개의 containsExactly 기대값도 `-p` 뒤에 `"--output-format", "json"` 순서로 갱신 (Step 1에서 이미 반영).

record/파서 추가 (클래스 하단, ExecResult 자리 교체):

```java
    /** claude -p --output-format json envelope의 usage (스펙 §4.1). */
    public record Usage(java.math.BigDecimal costUsd, long inputTokens, long outputTokens,
                        long cacheCreationTokens, long cacheReadTokens) {}

    /** stdout = envelope의 result 텍스트 (기존 plain stdout과 동일 내용). usage는 null 가능. */
    public record ExecResult(int exitCode, String stdout, long durationMs, Usage usage) {}

    record Parsed(String resultText, Usage usage) {}

    private static final com.fasterxml.jackson.databind.ObjectMapper ENVELOPE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * envelope 해제. 형식이 안 맞으면 raw 그대로 + usage=null (fallback — 수집 실패가
     * 본 파이프라인을 죽이면 안 된다, 스펙 §7).
     */
    static Parsed parseEnvelope(String raw) {
        if (raw == null || raw.isBlank()) return new Parsed(raw == null ? "" : raw, null);
        try {
            var node = ENVELOPE_MAPPER.readTree(raw.trim());
            if (!node.isObject() || !node.path("result").isTextual()) {
                return new Parsed(raw, null);
            }
            var u = node.path("usage");
            Usage usage = new Usage(
                    node.path("total_cost_usd").isNumber()
                            ? node.path("total_cost_usd").decimalValue()
                            : java.math.BigDecimal.ZERO,
                    u.path("input_tokens").asLong(0),
                    u.path("output_tokens").asLong(0),
                    u.path("cache_creation_input_tokens").asLong(0),
                    u.path("cache_read_input_tokens").asLong(0));
            return new Parsed(node.path("result").asText(), usage);
        } catch (Exception e) {
            return new Parsed(raw, null);
        }
    }
```

`exec(...)` 본문 수정 — stderr 분리 + envelope 해제:

```java
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workingDir);            // redirectErrorStream(true) 제거!
            Process p = pb.start();
            try (OutputStream stdin = p.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread reader = drain(p.getInputStream(), out);
            Thread errReader = drain(p.getErrorStream(), err);

            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("claude timeout after " + timeout);
            }
            reader.join(2000);
            errReader.join(2000);

            Parsed parsed = parseEnvelope(out.toString());
            if (parsed.usage() == null) {
                log.warn("claude envelope 파싱 실패 — usage 미수집 (stdout {} bytes)", out.length());
            }
            String resultText = parsed.resultText();
            // 실패 진단: exit != 0이면 stderr tail을 결과 텍스트에 덧붙인다 (기존 stdout 병합 대체)
            if (p.exitValue() != 0 && !err.isEmpty()) {
                String tail = err.length() > 2000 ? "…" + err.substring(err.length() - 2000) : err.toString();
                resultText = resultText + "\n[stderr]\n" + tail;
            }
            return new ExecResult(p.exitValue(), resultText, durationMs, parsed.usage());
```

drain 헬퍼 (기존 인라인 reader 스레드를 대체, 클래스 내 private static):

```java
    private static Thread drain(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sink.append(line).append('\n');
            } catch (IOException e) {
                log.warn("claude 출력 읽기 실패: {}", e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
```

- [ ] **Step 4: 테스트 통과 + 컴파일 파급 확인**

Run: `./gradlew test --tests 'ClaudeExecAdapterTest'` → PASS
Run: `./gradlew compileJava compileTestJava`
Expected: `ExecResult` 생성자 4-인자화로 컴파일 에러가 나는 곳이 있으면 확인 — `grep -rn 'new ClaudeExecAdapter.ExecResult\|new ExecResult(' src/`로 전수 조사 후 `usage` 인자(테스트 더블은 `null`) 추가. WorkerMainLoop의 배선은 Task 3에서 하므로 여기서는 컴파일만 맞춘다.

- [ ] **Step 5: 단위 테스트 전체 회귀 + 커밋**

Run: `./gradlew test` (Testcontainers 게이트 제외 전체)
Expected: PASS

```bash
git add -A src/main/java/com/hamonsoft/netismaker/workerdaemon/ClaudeExecAdapter.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/
git commit -m "feat: 워커 claude -p JSON envelope 전환 — usage 파싱 + stderr 분리 + fallback"
```

---

### Task 3: WorkerResultRequest.usage + WorkerMainLoop 배선

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/WorkerResultRequestUsageTest.java` (신규)

**Interfaces:**
- Consumes: `ClaudeExecAdapter.ExecResult.usage()`, `ClaudeExecAdapter.Usage` (Task 2)
- Produces: `WorkerResultRequest`의 **마지막** 컴포넌트로 `UsageReport usage` (null 가능) 추가.
  `WorkerResultRequest.UsageReport(BigDecimal costUsd, Long inputTokens, Long outputTokens, Long cacheCreationTokens, Long cacheReadTokens)` + `static UsageReport UsageReport.from(ClaudeExecAdapter.Usage u)`는 **만들지 않는다** (dto→workerdaemon 역참조 금지). 대신 WorkerMainLoop에 `private static WorkerResultRequest.UsageReport usageOf(ClaudeExecAdapter.ExecResult exec)` 헬퍼.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerResultRequestUsageTest {

    @Test
    void 팩토리는_usage를_보존한다() {
        var usage = new WorkerResultRequest.UsageReport(
                new BigDecimal("0.42"), 100L, 20L, 5L, 900L);
        WorkerResultRequest req = WorkerResultRequest.designReview(
                "w1", "# D", "[]", "p", "u", "log", 5L, usage);
        assertThat(req.usage()).isEqualTo(usage);
        assertThat(req.status()).isEqualTo(TaskStatus.DESIGN_REVIEW);
    }

    @Test
    void 배포_팩토리의_usage는_null이다() {
        WorkerResultRequest req = WorkerResultRequest.deployed(
                "w1", "http://u", "cid", 80, "img", 5L, "log");
        assertThat(req.usage()).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'WorkerResultRequestUsageTest'`
Expected: FAIL (컴파일 — UsageReport 미존재)

- [ ] **Step 3: DTO 수정**

`WorkerResultRequest` record에 마지막 컴포넌트 `UsageReport usage` 추가 + 중첩 record 정의:

```java
        // 디자인
        String designMarkdown,
        String mockupFilesJson,
        String designProjectId,
        String designUrl,
        // 사용량 (전 phase 공용, 스펙 §4.1 — null이면 미수집/구버전 워커)
        UsageReport usage
) {
    /** 단계 1회 실행분의 토큰/비용. 백엔드가 task_stage_usage에 누적한다. */
    public record UsageReport(java.math.BigDecimal costUsd, Long inputTokens, Long outputTokens,
                              Long cacheCreationTokens, Long cacheReadTokens) {
        /** 모든 필드가 null/0이면 기록할 것이 없다. */
        public boolean isEmpty() {
            return (costUsd == null || costUsd.signum() == 0)
                    && zero(inputTokens) && zero(outputTokens)
                    && zero(cacheCreationTokens) && zero(cacheReadTokens);
        }
        private static boolean zero(Long v) { return v == null || v == 0L; }
    }
```

기존 팩토리 수정:
- `deployed`/`deployFailed`/`undeployed`: 마지막 인자 `null` (배포는 Claude 미사용)
- `designReview`/`designFailed`: 시그니처에 `UsageReport usage` 파라미터 추가, 마지막 인자로 전달
- 모든 팩토리의 위치 인자 나열 끝에 usage 슬롯 반영

- [ ] **Step 4: WorkerMainLoop 배선**

헬퍼 추가:

```java
    /** ExecResult → 보고 usage. envelope 파싱 실패(usage null)면 null — 수집 생략. */
    private static WorkerResultRequest.UsageReport usageOf(ClaudeExecAdapter.ExecResult exec) {
        ClaudeExecAdapter.Usage u = exec == null ? null : exec.usage();
        if (u == null) return null;
        return new WorkerResultRequest.UsageReport(u.costUsd(),
                u.inputTokens(), u.outputTokens(), u.cacheCreationTokens(), u.cacheReadTokens());
    }
```

배선 지점 (모든 실행 누적 — 실패 보고에도 exec가 있으면 usage를 싣는다, 스펙 §1):
1. `processAnalysis` 성공 보고 `new WorkerResultRequest(...)`: 마지막 인자 `usageOf(exec)`
2. `processAnalysis`의 exit!=0 / 파싱 실패 경로: `safePostAnalysisFailure(task.id(), reason)` → 오버로드 `safePostAnalysisFailure(Long taskId, String reason, WorkerResultRequest.UsageReport usage)` 신설, exec가 존재하는 호출부는 `usageOf(exec)` 전달. exec 이전 실패(레포 fetch, exec 예외)는 기존 2-인자 유지(내부적으로 usage=null 위임).
3. `processImplementation`: 성공 보고 + `safePostImplementationFailure` 동일 방식 오버로드 (exit!=0, commit/push 실패, PR 실패 경로 — exec 확보 이후 경로 전부 `usageOf(exec)`).
4. `processDesign`: `WorkerResultRequest.designReview(..., usageOf(exec))`; `safePostDesignFailure` 오버로드 — exit!=0·harvest 실패 경로에 `usageOf(exec)` 전달.
5. `run()` 최상단 catch의 safePost들: 기존 시그니처 유지 (usage=null).

- [ ] **Step 5: 컴파일 전수 확인**

Run: `grep -rn 'new WorkerResultRequest(' src/ | grep -v UsageReport`
모든 위치 인자 생성 지점(WorkerMainLoop, 서비스/DTO 테스트)에 마지막 `null` 또는 usage 추가.
Run: `./gradlew compileJava compileTestJava` → 에러 0

- [ ] **Step 6: 테스트 + 커밋**

Run: `./gradlew test` → PASS (designReview 팩토리 시그니처 변경으로 `WorkerServiceDesignResultTest` 등 호출부 갱신 포함)

```bash
git add -A src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java src/test/java/
git commit -m "feat: 워커 결과 보고에 usage(토큰/비용) 탑재 — 성공·실패 보고 공통"
```

---

### Task 4: WorkerService — stage 매핑 + 누적 upsert

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceStageUsageTest.java` (신규)

**Interfaces:**
- Consumes: `TaskStageUsageRepository.accumulate(...)` (Task 1), `WorkerResultRequest.usage()` (Task 3)
- Produces: `recordResult`가 보고 status에 따라 `task_stage_usage`에 누적하는 동작 (신규 메서드 노출 없음)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`WorkerServiceDesignResultTest` 패턴. 시나리오: 구현 실패 보고(usage 포함) → 재시도 세팅 → PR_CREATED 보고(usage 포함) → IMPLEMENTATION 행에 합산 확인 + 분석/디자인 각 1건.

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.*;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class WorkerServiceStageUsageTest {

    @Autowired private WorkerService workerService;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskStageUsageRepository usageRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;

    private static final WorkerResultRequest.UsageReport USAGE_A =
            new WorkerResultRequest.UsageReport(new BigDecimal("0.10"), 1000L, 200L, 0L, 500L);
    private static final WorkerResultRequest.UsageReport USAGE_B =
            new WorkerResultRequest.UsageReport(new BigDecimal("0.25"), 400L, 100L, 10L, 0L);

    @BeforeEach void clean() {
        usageRepo.deleteAll();
        historyRepo.deleteAll();
        analysisRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private Task taskIn(TaskStatus status) {
        Task t = Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                List.of(), "claude-opus-4-8", "high");
        t.setStatus(status);
        t.setWorkerId("w1");
        return taskRepo.save(t);
    }

    @Test
    void 구현_실패_후_성공_보고는_IMPLEMENTATION에_누적된다() {
        Task t = taskIn(TaskStatus.IMPLEMENTING);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.IMPLEMENTATION_FAILED,
                null, null, null, null, "실패",
                null, null, "b", null, "log",
                null, null, null, null, null,
                null, null, null, null, USAGE_A));

        // 재시도 흉내: 상태/워커 되돌림
        Task again = taskRepo.findById(t.getId()).orElseThrow();
        again.setStatus(TaskStatus.IMPLEMENTING);
        again.setWorkerId("w1");
        taskRepo.save(again);

        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.PR_CREATED,
                null, null, null, 5L, null,
                "http://pr", 1, "b", "sha", "log",
                null, null, null, null, null,
                null, null, null, null, USAGE_B));

        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(TaskStageUsage.STAGE_IMPLEMENTATION);
        assertThat(rows.get(0).getCostUsd()).isEqualByComparingTo("0.35");
        assertThat(rows.get(0).getInputTokens()).isEqualTo(1400);
    }

    @Test
    void 분석_완료_보고는_ANALYSIS에_기록된다() {
        Task t = taskIn(TaskStatus.IN_PROGRESS);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.COMPLETED,
                "## 1. x\n## 2. y\n## 3. z", "[]", "log", 5L, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, USAGE_A));
        List<TaskStageUsage> rows = usageRepo.findByTaskIdOrderByStageAsc(t.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(TaskStageUsage.STAGE_ANALYSIS);
    }

    @Test
    void usage_없는_보고는_행을_만들지_않는다() {
        Task t = taskIn(TaskStatus.IN_PROGRESS);
        workerService.recordResult(t.getId(), new WorkerResultRequest(
                "w1", TaskStatus.FAILED,
                null, null, null, null, "이유",
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null));
        assertThat(usageRepo.findByTaskIdOrderByStageAsc(t.getId())).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'WorkerServiceStageUsageTest'`
Expected: FAIL — usage 행 미생성 (rows 0건)

- [ ] **Step 3: 구현**

`WorkerService`에 `TaskStageUsageRepository stageUsageRepo` 주입(필드+생성자). 헬퍼:

```java
    /** 보고 usage를 stage에 누적. usage 없음/전부 0이면 no-op (스펙 §7). */
    private void accumulateUsage(Long taskId, String stage, WorkerResultRequest req) {
        WorkerResultRequest.UsageReport u = req.usage();
        if (u == null || u.isEmpty()) return;
        stageUsageRepo.accumulate(taskId, stage,
                u.costUsd() == null ? java.math.BigDecimal.ZERO : u.costUsd(),
                nz(u.inputTokens()), nz(u.outputTokens()),
                nz(u.cacheCreationTokens()), nz(u.cacheReadTokens()));
    }
    private static long nz(Long v) { return v == null ? 0L : v; }
```

호출 지점 (status→stage 매핑, 스펙 §4.1):

| 위치 | stage |
|---|---|
| 지각 정합화 IMPLEMENTATION_FAILED→PR_CREATED 분기 (return 전) | `STAGE_IMPLEMENTATION` |
| 지각 정합화 FAILED→COMPLETED 분기 | `STAGE_ANALYSIS` |
| 지각 정합화 DESIGN_PENDING→DESIGN_REVIEW 분기 (recordDesignResult 내부에서 처리되므로 별도 호출 불필요) | — |
| switch `case COMPLETED`, `case FAILED` | `STAGE_ANALYSIS` |
| switch `case PR_CREATED`, `case IMPLEMENTATION_FAILED` | `STAGE_IMPLEMENTATION` |
| `recordDesignResult`의 `case DESIGN_REVIEW`, `case DESIGN_FAILED` (검증 통과 후) | `STAGE_DESIGN` |
| `recordDeployResult` (배포/중지) | 호출하지 않음 — undeploy 복귀형 PR_CREATED 포함 |

각 지점에서 상태 전이 코드 **다음**, `historyRepo.save(...)` 전후 아무 곳에 `accumulateUsage(t.getId(), TaskStageUsage.STAGE_X, req);` 1줄. 검증 예외(throw) 경로보다 뒤에 두어 잘못된 보고는 누적하지 않는다.

- [ ] **Step 4: 통과 확인 + 게이트 회귀**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: 전체 PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/WorkerService.java \
        src/test/java/com/hamonsoft/netismaker/service/WorkerServiceStageUsageTest.java
git commit -m "feat: 워커 보고 usage를 단계별로 누적 upsert (분석/구현/디자인, 실패 포함)"
```

---

### Task 5: 인터뷰 서비스(TS) — 토큰 추출·보고

**Files:**
- Modify: `netismaker-interview-service/src/runner/messageRelay.ts`
- Modify: `netismaker-interview-service/src/types.ts`
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts`
- Modify: `netismaker-interview-service/test/fixtures/sdkMessages.ts`
- Test: `netismaker-interview-service/test/messageRelay.test.ts`, `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: SDK result 메시지 `usage: {input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens, total_cost_usd}`
- Produces: `RelayResult`에 `inputTokens/outputTokens/cacheCreationTokens/cacheReadTokens: number` 추가; `WorkerQuestionRequest`/`WorkerPlanRequest`(types.ts)에 동명 4필드 추가 (flat — 기존 costUsd와 나란히)

- [ ] **Step 1: 실패하는 테스트 — fixture에 토큰 추가 + relay 기대값 확장**

`test/fixtures/sdkMessages.ts`의 result 메시지 3곳을 다음 형태로 확장 (기존 total_cost_usd 유지):

```ts
    { type: 'result', subtype: 'success',
      usage: { total_cost_usd: 0.12, input_tokens: 1000, output_tokens: 250,
               cache_creation_input_tokens: 30, cache_read_input_tokens: 8000 },
      duration_ms: 800 },
```

(0.31/5400 결과와 0.2/900 결과도 임의의 서로 다른 토큰 값으로 동일 확장)

`test/messageRelay.test.ts` 첫 테스트에 추가:

```ts
    expect(out.inputTokens).toBe(1000);
    expect(out.outputTokens).toBe(250);
    expect(out.cacheCreationTokens).toBe(30);
    expect(out.cacheReadTokens).toBe(8000);
```

- [ ] **Step 2: 실패 확인**

Run: `cd netismaker-interview-service && npm test`
Expected: FAIL — `inputTokens` undefined

- [ ] **Step 3: 구현**

`messageRelay.ts` — `RelayResult`:

```ts
export interface RelayResult {
  sessionId: string | null;
  assistantText: string;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  durationMs: number;
  completed: boolean;
}
```

relay()의 result 분기:

```ts
    } else if (msg.type === 'result') {
      const usage = msg.usage as
        | {
            total_cost_usd?: number;
            input_tokens?: number;
            output_tokens?: number;
            cache_creation_input_tokens?: number;
            cache_read_input_tokens?: number;
          }
        | undefined;
      costUsd = usage?.total_cost_usd ?? 0;
      inputTokens = usage?.input_tokens ?? 0;
      outputTokens = usage?.output_tokens ?? 0;
      cacheCreationTokens = usage?.cache_creation_input_tokens ?? 0;
      cacheReadTokens = usage?.cache_read_input_tokens ?? 0;
      durationMs = (msg.duration_ms as number) ?? 0;
      completed = true;
    }
```

(함수 상단에 `let inputTokens = 0;` 등 4개 선언, return에 포함)

`types.ts` — 두 요청 인터페이스에 costUsd 아래 4필드 추가:

```ts
  /** SHADOW cost for this turn (quota accounting, not dollars). */
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
```

`interviewRunner.ts` — 인터뷰 턴: `costUsd` 합산과 동일한 패턴으로 4변수 합산(result → second/retry 재할당 시 동일 대입), `postPlan`/`postQuestion` body에 4필드 전달. 질문 턴(`runQuestionTurn`): `result.inputTokens` 등 직접 전달.

- [ ] **Step 4: 통과 확인**

Run: `npm test` → PASS. `interviewRunner.test.ts`의 postQuestion/postPlan body 검증이 있으면 4필드 기대값 추가.
Run: `npm run lint` → 클린

- [ ] **Step 5: 커밋**

```bash
git add netismaker-interview-service/src netismaker-interview-service/test
git commit -m "feat: 인터뷰 서비스 — SDK result에서 토큰 4종 추출·보고 (costUsd 경로 확장)"
```

---

### Task 6: 인터뷰(Java) — 토큰 수신·세션 누적·confirm 이관

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerQuestionRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerPlanRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerApiIntegrationTest.java` (테스트 추가)

**Interfaces:**
- Consumes: Task 5의 JSON body(4필드), `TaskStageUsageRepository.accumulate` + `TaskStageUsage.STAGE_INTERVIEW` (Task 1)
- Produces: `InterviewSession`의 `getInputTokens()/getOutputTokens()/getCacheCreationTokens()/getCacheReadTokens()` (long), confirm 시 `task_stage_usage(INTERVIEW)` 행

- [ ] **Step 1: 실패하는 통합 테스트 추가 (InterviewWorkerApiIntegrationTest에)**

```java
    @Test
    void question_보고는_토큰을_세션에_누적한다() throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey)
                .param("workerId", "iw-1")).andExpect(status().isOk());

        mvc.perform(post("/worker/interviews/" + sid + "/question")
                        .header("X-Worker-API-Key", apiKey).param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"content":"Q1","claudeSessionId":"cs-1","kind":"question",
                                 "costUsd":0.12,"inputTokens":1000,"outputTokens":250,
                                 "cacheCreationTokens":30,"cacheReadTokens":8000}
                                """))
                .andExpect(status().isNoContent());

        InterviewSession s = sessionRepo.findById(sid).orElseThrow();
        assertThat(s.getInputTokens()).isEqualTo(1000);
        assertThat(s.getOutputTokens()).isEqualTo(250);
        assertThat(s.getCacheCreationTokens()).isEqualTo(30);
        assertThat(s.getCacheReadTokens()).isEqualTo(8000);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests 'InterviewWorkerApiIntegrationTest'`
Expected: FAIL — `getInputTokens()` 미존재 (컴파일)

- [ ] **Step 3: DTO/엔티티/서비스 구현**

DTO (둘 다, costUsd 뒤에):

```java
public record WorkerQuestionRequest(
        String content,
        String claudeSessionId,
        String kind,
        BigDecimal costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationTokens,
        Long cacheReadTokens
) {}
```

(`WorkerPlanRequest`도 동일하게 costUsd 뒤 4필드 — durationMs는 마지막 유지)

`InterviewSession` — `totalCostUsd` 필드 아래:

```java
    @Column(name = "input_tokens", nullable = false)
    @Setter
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    @Setter
    private long outputTokens;

    @Column(name = "cache_creation_tokens", nullable = false)
    @Setter
    private long cacheCreationTokens;

    @Column(name = "cache_read_tokens", nullable = false)
    @Setter
    private long cacheReadTokens;
```

`InterviewService` — `addCost`를 `addUsage`로 확장 (기존 addCost는 삭제, 호출부 2곳 교체):

```java
    private void addUsage(InterviewSession s, BigDecimal cost,
                          Long in, Long out, Long cc, Long cr) {
        if (cost != null) {
            BigDecimal base = s.getTotalCostUsd() == null ? BigDecimal.ZERO : s.getTotalCostUsd();
            s.setTotalCostUsd(base.add(cost));
        }
        s.setInputTokens(s.getInputTokens() + nz(in));
        s.setOutputTokens(s.getOutputTokens() + nz(out));
        s.setCacheCreationTokens(s.getCacheCreationTokens() + nz(cc));
        s.setCacheReadTokens(s.getCacheReadTokens() + nz(cr));
    }
    private static long nz(Long v) { return v == null ? 0L : v; }
```

호출부: `recordQuestion` → `addUsage(s, req.costUsd(), req.inputTokens(), req.outputTokens(), req.cacheCreationTokens(), req.cacheReadTokens());` / `recordPlan` 동일.

`confirm(...)` — `TaskStageUsageRepository stageUsageRepo` 주입 후, `historyRepo.save(...)` 다음·`s.setStatus(REGISTERED)` 전에:

```java
        // 인터뷰 사용량 이관 (스펙 §4.2) — 세션당 confirm 1회(PLAN_READY 가드)라 이중 누적 없음.
        if ((s.getTotalCostUsd() != null && s.getTotalCostUsd().signum() > 0)
                || s.getInputTokens() > 0 || s.getOutputTokens() > 0
                || s.getCacheCreationTokens() > 0 || s.getCacheReadTokens() > 0) {
            stageUsageRepo.accumulate(t.getId(), TaskStageUsage.STAGE_INTERVIEW,
                    s.getTotalCostUsd() == null ? BigDecimal.ZERO : s.getTotalCostUsd(),
                    s.getInputTokens(), s.getOutputTokens(),
                    s.getCacheCreationTokens(), s.getCacheReadTokens());
        }
```

- [ ] **Step 4: confirm 이관 테스트 추가**

기존 confirm을 검증하는 테스트 파일을 찾아(`grep -rn 'confirm(' src/test/java --include='*.java' -l`) 같은 파일 또는 `InterviewApiIntegrationTest`에: PLAN_READY 세션(taskId 연결, totalCostUsd/토큰 세팅) → confirm → `usageRepo.findByTaskIdOrderByStageAsc(taskId)`에 INTERVIEW 행 1건 + 값 일치 assert.

- [ ] **Step 5: 통과 + 게이트 회귀 + 커밋**

Run: `RUN_TESTCONTAINERS=true ./gradlew test` → PASS
주의: `new WorkerQuestionRequest(`/`new WorkerPlanRequest(` 기존 호출부(테스트) 전수 갱신 — `grep -rn 'new WorkerQuestionRequest(\|new WorkerPlanRequest(' src/`.

```bash
git add -A src/main/java src/test/java
git commit -m "feat: 인터뷰 토큰 수신·세션 누적 + confirm 시 INTERVIEW 단계 이관"
```

---

### Task 7: API 노출 — TaskResponse.stageUsage/총합 + 목록 배치 조회

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/TaskResponseUsageTest.java` (신규)

**Interfaces:**
- Consumes: `TaskStageUsageRepository` (Task 1)
- Produces:
  - `TaskResponse` 신규 컴포넌트(마지막에): `List<StageUsageView> stageUsage`(항상 non-null, 목록은 []), `BigDecimal totalCostUsd`(null=미수집), `Long totalTokens`(input+output 합, null=미수집)
  - `TaskResponse.StageUsageView(String stage, BigDecimal costUsd, long inputTokens, long outputTokens, long cacheCreationTokens, long cacheReadTokens)`
  - `TaskResponse.ofWithUsage(Task, TaskAnalysis, TaskDesign, Long, List<TaskAttachment>, List<TaskStageUsage>)` — 상세용
  - `TaskResponse.withTotalCost(TaskResponse, BigDecimal)` — 목록용 재조립 헬퍼
  - `TaskService.getStageUsage(Long taskId): List<TaskStageUsage>`, `TaskService.costTotals(List<Long> ids): Map<Long, BigDecimal>`

- [ ] **Step 1: 실패하는 단위 테스트**

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResponseUsageTest {

    private Task task() {
        return Task.create("acme/widgets", "main", "제목", "설명", "user1", 3,
                List.of(), null, null);
    }

    @Test
    void 기본_팩토리는_빈_stageUsage와_null_총합을_반환한다() {
        TaskResponse r = TaskResponse.of(task(), null);
        assertThat(r.stageUsage()).isEmpty();
        assertThat(r.totalCostUsd()).isNull();
        assertThat(r.totalTokens()).isNull();
    }

    @Test
    void withTotalCost는_목록_총합만_채운다() {
        TaskResponse r = TaskResponse.withTotalCost(
                TaskResponse.of(task(), null), new BigDecimal("1.23"));
        assertThat(r.totalCostUsd()).isEqualByComparingTo("1.23");
        assertThat(r.stageUsage()).isEmpty();
    }
}
```

(ofWithUsage의 view 매핑·합산 검증은 엔티티 인스턴스 생성이 필요 — `TaskStageUsage`는 조회 전용이라 세터가 없다. **ofWithUsage 검증은 Step 4의 통합 테스트에서 수행**하고, 단위 테스트는 위 2건으로 한정.)

- [ ] **Step 2: 실패 확인 → 구현**

Run: `./gradlew test --tests 'TaskResponseUsageTest'` → FAIL (컴파일)

`TaskResponse` 수정 방침: 기존 마스터 팩토리 `of(t, a, d, interviewSessionId, attachments)`의 마지막 `return new TaskResponse(...)`를 private 헬퍼로 추출해 usage 3필드를 파라미터로 받게 하고, 공개 API를 다음처럼 구성:

```java
    /** 상세 응답 — usage rows를 뷰로 변환하고 총합 계산. rows 비면 총합 null (미수집, 스펙 §7). */
    public static TaskResponse ofWithUsage(Task t, TaskAnalysis a, TaskDesign d,
                                           Long interviewSessionId,
                                           List<TaskAttachment> attachments,
                                           List<com.hamonsoft.netismaker.entity.TaskStageUsage> usage) {
        List<StageUsageView> views = usage == null ? List.of()
                : usage.stream().map(u -> new StageUsageView(u.getStage(), u.getCostUsd(),
                        u.getInputTokens(), u.getOutputTokens(),
                        u.getCacheCreationTokens(), u.getCacheReadTokens())).toList();
        BigDecimal totalCost = views.isEmpty() ? null
                : views.stream().map(StageUsageView::costUsd)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        Long totalTokens = views.isEmpty() ? null
                : views.stream().mapToLong(v -> v.inputTokens() + v.outputTokens()).sum();
        return build(t, a, d, interviewSessionId, attachments, views, totalCost, totalTokens);
    }

    /** 목록 응답 — 총비용만 채워 재조립 (stageUsage는 [] 유지). */
    public static TaskResponse withTotalCost(TaskResponse r, BigDecimal totalCostUsd) { ... }
```

- `StageUsageView` record + `import java.math.BigDecimal;` 추가.
- record 컴포넌트는 `attachments` 뒤에 `List<StageUsageView> stageUsage, BigDecimal totalCostUsd, Long totalTokens`.
- `withTotalCost`는 모든 컴포넌트를 복사한 새 인스턴스 생성 (record라 wither 없음 — 전체 나열).
- 기존 `of(...)` 체인은 `build(..., List.of(), null, null)`로 위임.

`TaskService`:

```java
    @Transactional(readOnly = true)
    public List<TaskStageUsage> getStageUsage(Long taskId) {
        return stageUsageRepo.findByTaskIdOrderByStageAsc(taskId);
    }

    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> costTotals(List<Long> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        return stageUsageRepo.sumCostByTaskIds(taskIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        TaskStageUsageRepository.CostTotal::getTaskId,
                        TaskStageUsageRepository.CostTotal::getTotalCostUsd));
    }
```

(`TaskStageUsageRepository stageUsageRepo` 필드+생성자 주입. `new TaskService(`를 grep해 수동 생성 테스트가 있으면 인자 추가.)

`TaskController`:
- 목록(GET, 라인 ~87): 
```java
        Page<Task> page = taskService.list(userId, isAdmin, mine, status, pageable);
        Map<Long, BigDecimal> totals = taskService.costTotals(
                page.getContent().stream().map(Task::getId).toList());
        return page.map(t -> {
            TaskResponse base = TaskResponse.of(t, null);
            BigDecimal total = totals.get(t.getId());
            return total == null ? base : TaskResponse.withTotalCost(base, total);
        });
```
- 상세(GET /{id}, 라인 ~98): 기존 `TaskResponse.of(t, analysis, design, sessionId, attachments)` 호출을 `TaskResponse.ofWithUsage(t, ..., taskService.getStageUsage(id))`로 교체. **상세 엔드포인트 1곳만** — 다른 `TaskResponse.of` 호출부(액션 응답들)는 그대로 둔다.

- [ ] **Step 3: 단위 테스트 통과 확인**

Run: `./gradlew test --tests 'TaskResponseUsageTest'` → PASS

- [ ] **Step 4: 상세/목록 통합 테스트**

`WorkerServiceStageUsageTest` 옆에 `TaskUsageApiIntegrationTest` (컨트롤러 MockMvc, InterviewWorkerApiIntegrationTest 패턴): task 시드 + `usageRepo.accumulate` 2단계 → `GET /tasks/{id}` → `$.stageUsage.length() == 2`, `$.totalCostUsd`, `$.totalTokens` 검증; `GET /tasks` → 해당 행의 `totalCostUsd` 존재 + usage 없는 task는 null. (인증 헤더/시큐리티 관행은 기존 컨트롤러 테스트에서 복사.)

Run: `RUN_TESTCONTAINERS=true ./gradlew test` → 전체 PASS

- [ ] **Step 5: 커밋**

```bash
git add -A src/main/java src/test/java
git commit -m "feat: 작업 API에 단계별 usage + 총합 노출 (상세 stageUsage, 목록 totalCostUsd)"
```

---

### Task 8: 프론트 — 상세 페이지 단계 chip + 헤더 총합

**Files:**
- Modify: `frontend/pages/tasks/[id].vue`
- Test: `frontend/test/task-detail-usage.spec.ts` (신규, `task-detail-attachments.spec.ts` 모델)

**Interfaces:**
- Consumes: Task 7의 상세 응답 `stageUsage[]`, `totalCostUsd`, `totalTokens`
- Produces: 없음 (말단 UI)

- [ ] **Step 1: 실패하는 spec 작성**

`task-detail-attachments.spec.ts`를 복사해 fixture를 usage 중심으로 교체:

```ts
const taskFixture = {
  /* attachments spec의 기본 필드 전부 복사 후 아래만 변경/추가 */
  status: 'PR_CREATED',
  statusLabel: 'PR생성',
  stageUsage: [
    { stage: 'INTERVIEW', costUsd: 0.5, inputTokens: 12300, outputTokens: 4500,
      cacheCreationTokens: 100, cacheReadTokens: 90000 },
    { stage: 'IMPLEMENTATION', costUsd: 0.42, inputTokens: 8000, outputTokens: 2000,
      cacheCreationTokens: 0, cacheReadTokens: 50000 },
  ],
  totalCostUsd: 0.92,
  totalTokens: 26800,
}
```

테스트 2건:

```ts
it('usage가 있으면 헤더 총합 chip과 단계 chip을 렌더링한다', async () => {
  useApiMock.mockResolvedValueOnce(taskFixture)
  const w = mount(PageWrapper, mountOpts)
  await flushPromises()
  expect(w.text()).toContain('$0.92')
  expect(w.text()).toContain('26.8k')
  expect(w.text()).toContain('$0.42')
})

it('usage가 없으면 usage chip을 렌더링하지 않는다', async () => {
  useApiMock.mockResolvedValueOnce({ ...taskFixture, stageUsage: [], totalCostUsd: null, totalTokens: null })
  const w = mount(PageWrapper, mountOpts)
  await flushPromises()
  expect(w.text()).not.toContain('$0.92')
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npm test -- task-detail-usage`
Expected: FAIL

- [ ] **Step 3: 구현**

`[id].vue` script에 인터페이스+헬퍼 추가:

```ts
interface StageUsageView {
  stage: string
  costUsd: number
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
}
// TaskResponse에: stageUsage: StageUsageView[]  / totalCostUsd: number | null / totalTokens: number | null

const usageByStage = computed<Record<string, StageUsageView>>(() =>
  Object.fromEntries((task.value?.stageUsage ?? []).map((u) => [u.stage, u])),
)

function fmtTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}
function fmtCost(c: number): string {
  return '$' + (c > 0 && c < 0.01 ? c.toFixed(4) : c.toFixed(2))
}
```

템플릿:
1. **헤더 총합** — 기존 model/effort chip 옆 (`icon="smart_toy"` chip 근처):
```html
        <q-chip v-if="task.totalCostUsd != null" dense size="sm" outline
                icon="paid" color="primary"
                :label="`${fmtTokens(task.totalTokens ?? 0)} 토큰 · ${fmtCost(task.totalCostUsd)}`" />
```
2. **단계 chip** — 재사용 마크업 (stage 인자만 다름):
```html
        <q-chip v-if="usageByStage[STAGE]" dense size="sm" outline icon="bolt"
                :label="`${fmtTokens(usageByStage[STAGE].inputTokens)} 입력 · ${fmtTokens(usageByStage[STAGE].outputTokens)} 출력 · ${fmtCost(usageByStage[STAGE].costUsd)}`">
          <q-tooltip>
            캐시 생성 {{ fmtTokens(usageByStage[STAGE].cacheCreationTokens) }} ·
            캐시 읽기 {{ fmtTokens(usageByStage[STAGE].cacheReadTokens) }}
          </q-tooltip>
        </q-chip>
```
   배치 anchor (grep으로 위치 특정):
   - `'INTERVIEW'` → `<q-card-section class="text-h6">대화형 분석</q-card-section>` 제목 옆 (section을 `row items-center`로 감싸거나 제목 뒤 인라인)
   - `'ANALYSIS'` → 분석 결과 카드 제목 (`grep -n '분석' frontend/pages/tasks/[id].vue`로 확인)
   - `'DESIGN'` → DesignReviewCard 사용부 상단 (카드 바깥 래퍼)
   - `'IMPLEMENTATION'` → PR chip이 있는 구현 카드 섹션 (`label=\`PR #\``인 q-chip 근처)

- [ ] **Step 4: 통과 확인 + lint**

Run: `npm test` → 전체 PASS (기존 attachments spec 포함 — fixture에 stageUsage 필드가 없어도 `?? []` 처리로 통과해야 함)
Run: `npm run lint` → 클린

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/tasks/\[id\].vue frontend/test/task-detail-usage.spec.ts
git commit -m "feat: 작업 상세 — 단계별 토큰/비용 chip + 헤더 총합"
```

---

### Task 9: 프론트 — 목록 총비용 배지

**Files:**
- Modify: `frontend/pages/tasks/index.vue`
- Test: `frontend/test/tasks-index-usage.spec.ts` (신규, `questions-index.spec.ts`의 mock/mount 관행 모델)

**Interfaces:**
- Consumes: 목록 응답 `totalCostUsd` (Task 7)
- Produces: 없음 (말단 UI)

- [ ] **Step 1: 실패하는 spec 작성**

`questions-index.spec.ts`의 mount/mock 보일러플레이트를 복사해 tasks/index.vue 대상으로:
- fixture: `content: [{ …기존 TaskResponse 목록 필드…, status: 'PR_CREATED', totalCostUsd: 1.23 }, { …, status: 'IMPLEMENTING', totalCostUsd: 0.5 }, { …, status: 'PR_CREATED', totalCostUsd: null }]`
- assert: `$1.23` 노출 / 진행 중(`IMPLEMENTING`) task와 null task에는 미노출 (`expect(w.text()).not.toContain('$0.50')`)

- [ ] **Step 2: 실패 확인**

Run: `npm test -- tasks-index-usage` → FAIL

- [ ] **Step 3: 구현**

`index.vue`:

```ts
// interface TaskResponse에 추가:
//   totalCostUsd: number | null

// 진행 중(…중) 상태 — 배지 제외 대상 (스펙 §6: 성공·실패 종결 상태만 표시)
const RUNNING_STATUSES = [
  'IN_PROGRESS', 'IMPLEMENTING', 'DESIGNING', 'DEPLOYING', 'UNDEPLOYING', 'INTERVIEWING',
]
function costBadge(t: TaskResponse): string | null {
  if (t.totalCostUsd == null || t.totalCostUsd === 0) return null
  if (RUNNING_STATUSES.includes(t.status)) return null
  const c = t.totalCostUsd
  return '$' + (c < 0.01 ? c.toFixed(4) : c.toFixed(2))
}
```

카드 마크업 — `card-status` span 다음:

```html
                    <span v-if="costBadge(c.task)" class="card-cost">{{ costBadge(c.task) }}</span>
```

스타일 블록(`.card-status` 정의 근처):

```css
.card-cost { font-size: 11px; font-weight: 600; color: #2e7d32; padding: 2px 6px; border-radius: 4px; background: rgba(46, 125, 50, 0.08); }
```

- [ ] **Step 4: 통과 + lint + 커밋**

Run: `npm test` → PASS, `npm run lint` → 클린

```bash
git add frontend/pages/tasks/index.vue frontend/test/tasks-index-usage.spec.ts
git commit -m "feat: 작업 목록 — 종결 작업 총비용 배지"
```

---

### Task 10: 전체 검증 스위트 + 마무리

**Files:**
- Modify: 없음 (검증 전용; 발견된 회귀만 수정)

**Interfaces:**
- Consumes: Task 1–9 전부
- Produces: 그린 상태의 3개 스위트

- [ ] **Step 1: Java 전체 (게이트 포함)**

Run: `cd /Users/micthebick/IdeaProjects/netismaker && RUN_TESTCONTAINERS=true ./gradlew clean test`
Expected: PASS. 실패 시 superpowers:systematic-debugging으로 원인 규명 후 수정 (테스트 삭제/완화 금지).

- [ ] **Step 2: 인터뷰 서비스 + 프론트**

Run: `cd netismaker-interview-service && npm test && npm run lint`
Run: `cd ../frontend && npm test && npm run lint`
Expected: 전부 PASS/클린

- [ ] **Step 3: 빌드 산출물 확인**

Run: `cd .. && ./gradlew build -x test && cd netismaker-interview-service && npm run build`
Expected: 성공 (프론트 `.output` 재빌드는 배포 시점 — start-all.sh 런북 참고)

- [ ] **Step 4: 잔여 변경 커밋 + 요약**

```bash
git status --short   # 미커밋 잔여분 확인 후 논리 단위로 커밋
git log --oneline main@{u}..HEAD 2>/dev/null || git log --oneline -12
```

배포 순서 메모 (커밋 아님, 보고용): DB 마이그레이션은 백엔드 기동 시 Flyway 자동 적용 → 백엔드/워커(동일 아티팩트) → 인터뷰 서비스 순. 구버전 워커의 usage 없는 보고는 no-op으로 안전 (스펙 §7).

---

## 자체 리뷰 체크 결과 (계획 작성 시점)

- 스펙 §3(스키마)→Task 1, §4.1(워커)→Task 2·3·4, §4.2(인터뷰)→Task 5·6, §5(API)→Task 7, §6(프론트)→Task 8·9, §7(에러)→각 Task fallback/no-op 스텝, §8(테스트)→각 Task 테스트 스텝. §9 제외 범위는 어느 Task에도 없음 — 커버리지 OK.
- envelope 필드명(`cache_creation_input_tokens`/`cache_read_input_tokens` → 컬럼 `cache_creation_tokens`/`cache_read_tokens`) 매핑은 Task 2 파서·Task 5 relay에서 일관.
- `TaskStageUsage`는 세터 없는 조회 전용 — 모든 쓰기는 native upsert 경유 (Task 1 주석 명시).
