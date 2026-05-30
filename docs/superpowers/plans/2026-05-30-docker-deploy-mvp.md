# Docker 배포 (MVP, 워커 로컬) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR생성 이후 단계로 "Docker 배포"를 추가 — admin이 배포 버튼을 누르면 워커가 PR head 브랜치를 빌드해 로컬 Docker에 컨테이너로 띄우고 접속 URL을 돌려준다.

**Architecture:** 기존 분석/구현 워커 파이프라인(상태머신 + SKIP LOCKED claim + kind 분기)을 그대로 확장한다. 배포 상태 5개를 `TaskStatus`에 추가하고, 워커는 `kind=DEPLOY/UNDEPLOY`를 claim해 `DeployService`로 처리한다. Docker 호출은 `DeployTarget` 인터페이스 뒤에 숨겨 `LocalDockerTarget`만 구현하고, 나중에 `RemoteSshDockerTarget`을 같은 인터페이스로 끼울 수 있게 둔다.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Flyway, PostgreSQL(`com` 스키마), JPA, JUnit5+Mockito+AssertJ, Docker CLI(`/usr/local/bin/docker`), Nuxt3+Quasar.

---

## File Structure

**Backend (api 프로파일):**
- `entity/TaskStatus.java` (수정) — 배포 상태 5개 추가
- `db/migration/V7__deploy_pipeline.sql` (생성) — task 컬럼 + 뷰 카운터 + 인덱스
- `entity/Task.java` (수정) — deploy_* 필드
- `repository/TaskRepository.java` (수정) — claim 쿼리에 배포 상태 추가
- `service/TaskService.java` (수정) — deploy/redeploy/undeploy 전이
- `controller/TaskController.java` (수정) — 3개 엔드포인트
- `service/WorkerService.java` (수정) — claim/result 배포 분기
- `dto/WorkerTaskResponse.java` (수정) — Kind DEPLOY/UNDEPLOY + factory
- `dto/WorkerResultRequest.java` (수정) — deploy 결과 필드 + factory
- `dto/TaskResponse.java` (수정) — DeploymentView
- `dto/QueueStats.java` (수정) + `repository/QueueStatsRepository.java` (수정) — 배포 카운터

**Worker 프로파일:**
- `workerdaemon/deploy/DeployTarget.java` (생성) — 인터페이스 + DeploySpec/DeployResult/DeployStatus
- `workerdaemon/deploy/PortAllocator.java` (생성) — 포트 할당 (순수 로직)
- `workerdaemon/deploy/DockerfileSupport.java` (생성) — EXPOSE 파싱 (순수 로직)
- `workerdaemon/deploy/LocalDockerTarget.java` (생성) — docker build/run/stop
- `workerdaemon/DeployService.java` (생성) — 오케스트레이션
- `workerdaemon/WorktreeService.java` (수정) — createForDeploy
- `workerdaemon/WorkerProperties.java` (수정) — Deploy 설정
- `workerdaemon/WorkerMainLoop.java` (수정) — kind 분기
- `resources/application-worker.yml` (수정) — deploy 설정

**Frontend:**
- `frontend/pages/tasks/[id].vue` (수정) — 버튼 + URL + statusClass
- `frontend/components/QueueStatsBar.vue` (수정) — 카운터 카드 4개

**Tests:**
- `service/TaskServiceDeployTest.java` (생성)
- `workerdaemon/deploy/PortAllocatorTest.java` (생성)
- `workerdaemon/deploy/DockerfileSupportTest.java` (생성)
- `service/WorkerServiceDeployTest.java` (생성)

---

## Task 1: TaskStatus 배포 상태 + Flyway V7

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/TaskStatus.java`
- Create: `src/main/resources/db/migration/V7__deploy_pipeline.sql`

- [ ] **Step 1: TaskStatus enum에 배포 상태 5개 추가**

`TaskStatus.java`에서 `CANCELLED("취소됨");` 줄을 아래로 교체:

```java
    PR_CREATED("PR생성"),
    IMPLEMENTATION_FAILED("구현실패"),
    DEPLOY_PENDING("배포대기"),
    DEPLOYING("배포중"),
    DEPLOYED("배포완료"),
    DEPLOY_FAILED("배포실패"),
    UNDEPLOY_PENDING("배포중지대기"),
    CANCELLED("취소됨");
```

(`IMPLEMENTATION_FAILED("구현실패"),` 의 세미콜론을 콤마로 바꾸고 그 사이에 5개를 삽입하는 것에 주의.)

상단 javadoc 상태머신 다이어그램 끝(취소 블록 위)에 배포 흐름을 추가:

```java
 *  배포 단계 (PR생성 + admin 배포 시 진입):
 *   [PR생성] ──(배포)──→ [배포대기] ──(워커 claim)──→ [배포중] ──┬→ [배포완료]
 *                                                                └→ [배포실패]
 *   [배포완료|배포실패] ──(재배포)──→ [배포대기]
 *   [배포완료|배포실패] ──(중지)──→ [배포중지대기] ──(워커)──→ [PR생성]
```

- [ ] **Step 2: V7 마이그레이션 작성**

`src/main/resources/db/migration/V7__deploy_pipeline.sql` 생성:

```sql
-- V7: PR생성 → 배포 파이프라인
-- PR생성된 task를 admin이 배포하면 워커가 head 브랜치를 빌드해 로컬 docker에 띄움.
-- CHECK 제약 없는 VARCHAR(30)이라 신규 상태 라벨은 그냥 INSERT 가능.

-- 1) task에 배포 결과 컬럼 추가 (nullable — 배포 안 거친 task는 NULL)
ALTER TABLE com.task
    ADD COLUMN IF NOT EXISTS deploy_url          VARCHAR(500),
    ADD COLUMN IF NOT EXISTS deploy_container_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS deploy_host_port    INT,
    ADD COLUMN IF NOT EXISTS deploy_image        VARCHAR(200),
    ADD COLUMN IF NOT EXISTS deployed_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deploy_log          TEXT;

-- 2) 배포 큐 claim 인덱스 (배포대기 + 배포중지대기 둘 다 FIFO claim 대상)
CREATE INDEX IF NOT EXISTS idx_task_deploy_pending ON com.task(created_at)
    WHERE status IN ('배포대기', '배포중지대기') AND deleted_at IS NULL;

-- 3) 큐 통계 뷰 갱신 — 배포 카운터 4개 추가.
--    V6의 avg_duration_ms(task_analysis.duration_ms 기반) 정의는 그대로 유지.
--    CREATE OR REPLACE VIEW는 컬럼 변경을 거부하므로 DROP 후 재생성.
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
    (SELECT AVG(a.duration_ms)::float8
       FROM com.task_analysis a
       JOIN com.task t2 ON t2.id = a.task_id
      WHERE t2.deleted_at IS NULL
        AND a.duration_ms IS NOT NULL)                                              AS avg_duration_ms
FROM com.task;
```

- [ ] **Step 3: API 빌드 + 부팅으로 마이그레이션 검증**

Run: `./gradlew compileJava` → BUILD SUCCESSFUL
그 다음 (PostgreSQL 떠 있는 상태에서) API 부팅으로 Flyway 적용 확인:
Run: `./scripts/stop-all.sh && ./scripts/start-all.sh 0` (워커 0개, API만)
Expected: `.run/api.log`에 `Migrating schema "com" to version "7 - deploy pipeline"` 및 `Started NetisMakerApplication`.
검증: `psql`로 `SELECT deploy_pending, deploying, deployed, deploy_failed FROM com.task_queue_stats;` → 0,0,0,0.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/TaskStatus.java \
        src/main/resources/db/migration/V7__deploy_pipeline.sql
git commit -m "feat(deploy): TaskStatus 배포 상태 5개 + V7 마이그레이션"
```

---

## Task 2: Task 엔티티 배포 필드

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java`

- [ ] **Step 1: 배포 컬럼 필드 추가**

`Task.java`에서 `implementation_log` 필드 블록 바로 뒤(`createdAt` 선언 앞)에 추가:

```java
    /** 배포 접속 URL. DEPLOYED 시 set. 예: http://localhost:19000 */
    @Column(name = "deploy_url", length = 500)
    @Setter
    private String deployUrl;

    /** 실행 중인 docker 컨테이너 ID (또는 이름 netis-task-{id}). */
    @Column(name = "deploy_container_id", length = 100)
    @Setter
    private String deployContainerId;

    /** 호스트에 게시된 포트. */
    @Column(name = "deploy_host_port")
    @Setter
    private Integer deployHostPort;

    /** 빌드된 이미지 태그 netis-task-{id}:{shortsha}. */
    @Column(name = "deploy_image", length = 200)
    @Setter
    private String deployImage;

    @Column(name = "deployed_at")
    @Setter
    private OffsetDateTime deployedAt;

    /** docker build/run 출력 tail (실패 디버깅 + 성공 기록). */
    @Column(name = "deploy_log", columnDefinition = "TEXT")
    @Setter
    private String deployLog;
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/Task.java
git commit -m "feat(deploy): Task 엔티티에 배포 메타 필드 추가"
```

---

## Task 3: TaskRepository claim 쿼리 확장

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java`

- [ ] **Step 1: claim 쿼리 IN 절에 배포 상태 추가**

`findClaimableForUpdateSkipLocked`의 `@Query` 내 `WHERE t.status IN (...)` 를 교체:

```java
    @Query("""
        SELECT t FROM Task t
        WHERE t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.APPROVED,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOY_PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.UNDEPLOY_PENDING)
          AND t.deletedAt IS NULL
        ORDER BY t.createdAt ASC
    """)
    List<Task> findClaimableForUpdateSkipLocked(Pageable pageable);
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/repository/TaskRepository.java
git commit -m "feat(deploy): claim 쿼리에 배포대기/배포중지대기 추가"
```

---

## Task 4: TaskService 배포 전이 (TDD)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployTest.java`

> 참고: 기존 `TaskService` 생성자 의존성은 `taskRepo`, `analysisRepo`, `historyRepo` 등. 테스트는 `StaleTaskRecoveryJobTest` 패턴(Mockito + ReflectionTestUtils)을 따른다. `Task.create(...)`의 현재 시그니처는 7-arg(마지막 `List<TaskMcpSpec> mcpsExtra`)이므로 `java.util.List.of()`를 넘긴다.

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployTest.java` 생성:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 배포 전이 단위 테스트 — deploy/redeploy/undeploy 상태 가드.
 */
class TaskServiceDeployTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskStatusHistoryRepository historyRepo;
    private TaskService service;

    private Task taskWithStatus(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of());
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setStatus(status);
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        service = new TaskService(taskRepo, analysisRepo, historyRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void deploy_from_pr_created_moves_to_deploy_pending() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        Task result = service.deploy(42L, "admin");

        assertThat(result.getStatus()).isEqualTo(TaskStatus.DEPLOY_PENDING);
        assertThat(result.getWorkerId()).isNull();
        verify(historyRepo).save(any());
    }

    @Test
    void deploy_from_wrong_status_throws() {
        Task t = taskWithStatus(TaskStatus.COMPLETED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.deploy(42L, "admin"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("PR생성");
    }

    @Test
    void redeploy_from_deployed_moves_to_deploy_pending() {
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        assertThat(service.redeploy(42L, "admin").getStatus())
                .isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void redeploy_from_deploy_failed_is_allowed() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_FAILED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        assertThat(service.redeploy(42L, "admin").getStatus())
                .isEqualTo(TaskStatus.DEPLOY_PENDING);
    }

    @Test
    void undeploy_from_deployed_moves_to_undeploy_pending() {
        Task t = taskWithStatus(TaskStatus.DEPLOYED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        assertThat(service.undeploy(42L, "admin").getStatus())
                .isEqualTo(TaskStatus.UNDEPLOY_PENDING);
    }

    @Test
    void undeploy_from_pr_created_throws() {
        Task t = taskWithStatus(TaskStatus.PR_CREATED);
        when(taskRepo.findActiveById(42L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.undeploy(42L, "admin"))
                .isInstanceOf(TaskException.class);
    }
}
```

> 주의: 위 테스트는 `new TaskService(taskRepo, analysisRepo, historyRepo)`를 가정한다. 실제 `TaskService` 생성자 인자 개수/순서를 파일 상단에서 확인하고, 추가 의존성이 있으면 그것도 `mock(...)`으로 넘겨라.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.TaskServiceDeployTest'`
Expected: 컴파일 에러 또는 FAIL (`deploy`/`redeploy`/`undeploy` 메서드 없음)

- [ ] **Step 3: TaskService에 배포 전이 메서드 추가**

`TaskService.java`의 `retry(...)` 메서드 뒤(클래스 닫는 `}` 직전)에 추가:

```java
    /** PR생성 → 배포대기. admin 한정. 워커가 다음 폴링에 claim해 배포 수행. */
    @Transactional
    public Task deploy(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.PR_CREATED) {
            throw TaskException.conflict("PR생성 상태에서만 배포할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        return toDeployPending(t, adminId, "관리자 배포 요청 → 배포 큐 진입");
    }

    /** 배포완료/배포실패 → 배포대기 (기존 컨테이너는 배포 시 stop 후 교체). */
    @Transactional
    public Task redeploy(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DEPLOYED && t.getStatus() != TaskStatus.DEPLOY_FAILED) {
            throw TaskException.conflict("배포완료/배포실패 상태에서만 재배포할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        return toDeployPending(t, adminId, "관리자 재배포 요청 → 배포 큐 진입");
    }

    /** 배포완료/배포실패 → 배포중지대기. 워커가 claim해 컨테이너 stop 후 PR생성 복귀. */
    @Transactional
    public Task undeploy(Long taskId, String adminId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.DEPLOYED && t.getStatus() != TaskStatus.DEPLOY_FAILED) {
            throw TaskException.conflict("배포완료/배포실패 상태에서만 중지할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.UNDEPLOY_PENDING);
        t.setClaimedAt(null);
        t.setWorkerId(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.UNDEPLOY_PENDING,
                "user", adminId, "관리자 배포 중지 요청"));
        return t;
    }

    private Task toDeployPending(Task t, String adminId, String reason) {
        TaskStatus from = t.getStatus();
        t.setStatus(TaskStatus.DEPLOY_PENDING);
        t.setClaimedAt(null);
        t.setWorkerId(null);
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, TaskStatus.DEPLOY_PENDING,
                "user", adminId, reason));
        return t;
    }
```

(`TaskStatusHistory`, `OffsetDateTime`는 이미 import되어 있음 — approve/retry가 사용 중.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.TaskServiceDeployTest'`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/TaskService.java \
        src/test/java/com/hamonsoft/netismaker/service/TaskServiceDeployTest.java
git commit -m "feat(deploy): TaskService deploy/redeploy/undeploy 전이 + 테스트"
```

---

## Task 5: TaskController 배포 엔드포인트

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java`

- [ ] **Step 1: deploy/redeploy/undeploy 엔드포인트 추가**

`TaskController.java`의 `retry(...)` 메서드 뒤(클래스 닫는 `}` 직전)에 추가:

```java
    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse deploy(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.deploy(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    @PostMapping("/{id}/redeploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse redeploy(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.redeploy(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }

    @PostMapping("/{id}/undeploy")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse undeploy(@PathVariable Long id, JwtAuthenticationToken auth) {
        String adminId = AuthContext.requireUserId(auth);
        taskService.undeploy(id, adminId);
        Task t = taskService.getForView(id, adminId, true);
        return TaskResponse.of(t, taskService.getAnalysis(id).orElse(null));
    }
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/TaskController.java
git commit -m "feat(deploy): deploy/redeploy/undeploy API 엔드포인트"
```

---

## Task 6: WorkerTaskResponse — DEPLOY/UNDEPLOY kind

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java`

- [ ] **Step 1: 레코드에 headBranch/headSha 추가 + Kind 확장 + factory**

`WorkerTaskResponse.java` 전체를 교체:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.List;

/**
 * 워커가 작업을 claim했을 때 받는 페이로드.
 *
 *  kind=ANALYSIS       → 분석 prompt 실행 (analysis/headBranch/headSha null)
 *  kind=IMPLEMENTATION → worktree에서 구현 prompt 실행 (분석 산출물 동봉)
 *  kind=DEPLOY         → head 브랜치(headBranch@headSha)를 빌드해 docker 배포
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
        String headSha
) {
    public enum Kind { ANALYSIS, IMPLEMENTATION, DEPLOY, UNDEPLOY }

    public static WorkerTaskResponse forAnalysis(Task t) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.ANALYSIS,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                null, null, null, null
        );
    }

    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(),
                a == null ? "[]" : a.getSubtasksJson(),
                null, null
        );
    }

    public static WorkerTaskResponse forDeploy(Task t) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DEPLOY,
                List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha()
        );
    }

    public static WorkerTaskResponse forUndeploy(Task t) {
        return new WorkerTaskResponse(
                t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.UNDEPLOY,
                List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha()
        );
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java
git commit -m "feat(deploy): WorkerTaskResponse에 DEPLOY/UNDEPLOY kind + head 정보"
```

---

## Task 7: WorkerResultRequest — 배포 결과 필드

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java`

- [ ] **Step 1: 배포 필드 + 정적 팩토리 추가**

`WorkerResultRequest.java` 전체를 교체:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 워커가 분석/구현/배포 후 백엔드에 업로드하는 결과 페이로드.
 *
 *   status=COMPLETED             → markdownResult, subtasksJson 필수 (분석)
 *   status=FAILED                → failureReason 필수 (분석)
 *   status=PR_CREATED            → prUrl, headBranch 필수 (구현) / 또는 UNDEPLOY 성공 복귀
 *   status=IMPLEMENTATION_FAILED → failureReason 필수 (구현)
 *   status=DEPLOYED              → deployUrl, deployContainerId, deployHostPort 필수 (배포)
 *   status=DEPLOY_FAILED         → failureReason 필수 (배포)
 */
public record WorkerResultRequest(
        @NotNull String workerId,
        @NotNull TaskStatus status,
        // 분석
        String markdownResult,
        String subtasksJson,
        String claudeLog,
        Long durationMs,
        String failureReason,
        // 구현
        String prUrl,
        Integer prNumber,
        String headBranch,
        String headSha,
        String implementationLog,
        // 배포
        String deployUrl,
        String deployContainerId,
        Integer deployHostPort,
        String deployImage,
        String deployLog
) {
    /** 배포 성공 보고. */
    public static WorkerResultRequest deployed(String workerId, String deployUrl,
                                               String containerId, int hostPort,
                                               String image, Long durationMs, String deployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.DEPLOYED,
                null, null, null, durationMs, null,
                null, null, null, null, null,
                deployUrl, containerId, hostPort, image, deployLog);
    }

    /** 배포 실패 보고. */
    public static WorkerResultRequest deployFailed(String workerId, String reason, String deployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.DEPLOY_FAILED,
                null, null, null, null, reason,
                null, null, null, null, null,
                null, null, null, null, deployLog);
    }

    /** 배포 중지(undeploy) 성공 → PR생성 복귀 보고. */
    public static WorkerResultRequest undeployed(String workerId, String undeployLog) {
        return new WorkerResultRequest(workerId, TaskStatus.PR_CREATED,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, undeployLog);
    }
}
```

> 주의: 기존 `new WorkerResultRequest(...)` 호출처(WorkerMainLoop의 분석/구현 보고, WorkerService 등)는 12-arg였다가 17-arg가 된다. 컴파일 에러가 나는 모든 호출처에 배포 5필드로 `null`을 추가해야 한다. Task 8과 Task 15에서 해당 호출처를 함께 수정한다.

- [ ] **Step 2: 컴파일 (호출처 에러 확인용)**

Run: `./gradlew compileJava`
Expected: 기존 호출처(WorkerMainLoop 등)에서 "constructor ... cannot be applied" 에러 — Task 8/15에서 수정 예정. 이 단계는 에러 위치 파악용.

- [ ] **Step 3: Commit (호출처 수정 전, DTO만)**

이 태스크는 단독 컴파일이 깨지므로 Task 8과 묶어 커밋한다. 여기서는 커밋하지 말고 Task 8로 진행.

---

## Task 8: WorkerService 배포 claim + result 분기 (TDD)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDeployTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDeployTest.java` 생성:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import com.hamonsoft.netismaker.repository.WorkerHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 배포 claim/result 분기 단위 테스트.
 */
class WorkerServiceDeployTest {

    private TaskRepository taskRepo;
    private TaskAnalysisRepository analysisRepo;
    private TaskStatusHistoryRepository historyRepo;
    private WorkerHeartbeatRepository heartbeatRepo;
    private WorkerService service;

    private Task taskWithStatus(TaskStatus status) {
        Task t = Task.create("owner/repo", "main", "T", "desc", "user1", 3, List.of());
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(status);
        t.setHeadBranch("netismaker/task-7");
        t.setHeadSha("abcdef1234567890");
        return t;
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        analysisRepo = mock(TaskAnalysisRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        heartbeatRepo = mock(WorkerHeartbeatRepository.class);
        service = new WorkerService(taskRepo, analysisRepo, historyRepo, heartbeatRepo);
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void claim_deploy_pending_yields_deploy_kind_and_sets_deploying() {
        Task t = taskWithStatus(TaskStatus.DEPLOY_PENDING);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class)))
                .thenReturn(List.of(t));

        Optional<WorkerTaskResponse> claimed = service.claimNextTask("mac-worker-1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().kind()).isEqualTo(WorkerTaskResponse.Kind.DEPLOY);
        assertThat(claimed.get().headBranch()).isEqualTo("netismaker/task-7");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYING);
    }

    @Test
    void claim_undeploy_pending_yields_undeploy_kind_and_sets_deploying() {
        Task t = taskWithStatus(TaskStatus.UNDEPLOY_PENDING);
        when(taskRepo.findClaimableForUpdateSkipLocked(any(Pageable.class)))
                .thenReturn(List.of(t));

        Optional<WorkerTaskResponse> claimed = service.claimNextTask("mac-worker-1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().kind()).isEqualTo(WorkerTaskResponse.Kind.UNDEPLOY);
        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYING);
    }

    @Test
    void record_deployed_sets_url_and_status() {
        Task t = taskWithStatus(TaskStatus.DEPLOYING);
        t.setWorkerId("mac-worker-1");
        when(taskRepo.findById(7L)).thenReturn(Optional.of(t));

        service.recordResult(7L, WorkerResultRequest.deployed(
                "mac-worker-1", "http://localhost:19000", "cid123", 19000,
                "netis-task-7:abcdef1", 5000L, "build ok"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.DEPLOYED);
        assertThat(t.getDeployUrl()).isEqualTo("http://localhost:19000");
        assertThat(t.getDeployHostPort()).isEqualTo(19000);
        assertThat(t.getDeployedAt()).isNotNull();
    }

    @Test
    void record_undeployed_returns_to_pr_created_and_clears_deploy_meta() {
        Task t = taskWithStatus(TaskStatus.DEPLOYING);
        t.setWorkerId("mac-worker-1");
        t.setDeployUrl("http://localhost:19000");
        t.setDeployHostPort(19000);
        when(taskRepo.findById(7L)).thenReturn(Optional.of(t));

        service.recordResult(7L, WorkerResultRequest.undeployed("mac-worker-1", "stopped"));

        assertThat(t.getStatus()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(t.getDeployUrl()).isNull();
        assertThat(t.getDeployHostPort()).isNull();
    }
}
```

> 주의: `new WorkerService(...)` 인자는 실제 생성자(`taskRepo, analysisRepo, historyRepo, heartbeatRepo`) 순서를 파일에서 재확인 후 맞춘다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceDeployTest'`
Expected: FAIL (claim이 DEPLOY_PENDING에서 예외, recordResult가 DEPLOYING 거부)

- [ ] **Step 3: claimNextTask에 배포 분기 추가**

`WorkerService.claimNextTask`의 상태 전이 if-블록을 교체:

```java
        if (from == TaskStatus.PENDING) {
            t.setStatus(TaskStatus.IN_PROGRESS);
        } else if (from == TaskStatus.APPROVED) {
            t.setStatus(TaskStatus.IMPLEMENTING);
        } else if (from == TaskStatus.DEPLOY_PENDING || from == TaskStatus.UNDEPLOY_PENDING) {
            t.setStatus(TaskStatus.DEPLOYING);
        } else {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "claim 후보가 처리 가능한 상태가 아님: " + from);
        }
```

그리고 같은 메서드 끝의 반환 분기를 교체:

```java
        if (t.getStatus() == TaskStatus.IMPLEMENTING) {
            TaskAnalysis a = analysisRepo.findById(t.getId()).orElse(null);
            return Optional.of(WorkerTaskResponse.forImplementation(t, a));
        }
        if (from == TaskStatus.DEPLOY_PENDING) {
            return Optional.of(WorkerTaskResponse.forDeploy(t));
        }
        if (from == TaskStatus.UNDEPLOY_PENDING) {
            return Optional.of(WorkerTaskResponse.forUndeploy(t));
        }
        return Optional.of(WorkerTaskResponse.forAnalysis(t));
```

- [ ] **Step 4: recordResult에 DEPLOYING 분기 추가**

`WorkerService.recordResult`에서 현재 상태 가드를 교체:

```java
        TaskStatus current = t.getStatus();
        if (current != TaskStatus.IN_PROGRESS
                && current != TaskStatus.IMPLEMENTING
                && current != TaskStatus.DEPLOYING) {
            throw new TaskException(HttpStatus.CONFLICT,
                    "현재 처리중 상태가 아닙니다 (현재: " + current.dbValue() + ")");
        }
```

그리고 worker_id 일치 검증 블록 바로 뒤(기존 "단계별 허용 상태 검증" 주석 앞)에 배포 전용 처리 early-return을 삽입:

```java
        // 배포 단계는 별도 처리 (분석/구현 검증 로직과 분리).
        if (current == TaskStatus.DEPLOYING) {
            recordDeployResult(t, req);
            return;
        }
```

`recordResult` 메서드 끝(클래스 닫기 전)에 헬퍼 추가:

```java
    private void recordDeployResult(Task t, WorkerResultRequest req) {
        TaskStatus from = t.getStatus();
        String reason;
        switch (req.status()) {
            case DEPLOYED -> {
                if (req.deployUrl() == null || req.deployUrl().isBlank()) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "배포완료 시 deployUrl 필수");
                }
                t.setStatus(TaskStatus.DEPLOYED);
                t.setDeployUrl(req.deployUrl());
                t.setDeployContainerId(req.deployContainerId());
                t.setDeployHostPort(req.deployHostPort());
                t.setDeployImage(req.deployImage());
                t.setDeployedAt(OffsetDateTime.now());
                t.setDeployLog(req.deployLog());
                reason = "배포 완료: " + req.deployUrl();
            }
            case DEPLOY_FAILED -> {
                t.setStatus(TaskStatus.DEPLOY_FAILED);
                t.setFailureReason(req.failureReason() == null ? "원인 미상" : req.failureReason());
                t.setDeployLog(req.deployLog());
                reason = t.getFailureReason();
            }
            case PR_CREATED -> {
                // undeploy 성공: 배포 메타 클리어 후 PR생성 복귀
                t.setStatus(TaskStatus.PR_CREATED);
                t.setDeployUrl(null);
                t.setDeployContainerId(null);
                t.setDeployHostPort(null);
                t.setDeployImage(null);
                t.setDeployedAt(null);
                t.setDeployLog(req.deployLog());
                reason = "배포 중지 → PR생성 복귀";
            }
            default -> throw new TaskException(HttpStatus.BAD_REQUEST,
                    "배포 단계에서 허용되지 않는 status: " + req.status());
        }
        t.setUpdatedAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), from, t.getStatus(),
                "worker", req.workerId(), reason));
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.service.WorkerServiceDeployTest'`
Expected: 4 tests PASS

- [ ] **Step 6: Commit (WorkerResultRequest DTO도 함께)**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/WorkerResultRequest.java \
        src/main/java/com/hamonsoft/netismaker/service/WorkerService.java \
        src/test/java/com/hamonsoft/netismaker/service/WorkerServiceDeployTest.java
git commit -m "feat(deploy): WorkerService 배포 claim/result 분기 + DTO + 테스트"
```

---

## Task 9: TaskResponse DeploymentView + QueueStats 카운터

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/QueueStats.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/QueueStatsRepository.java`

- [ ] **Step 1: TaskResponse에 DeploymentView 추가**

`TaskResponse.java`의 레코드 필드 목록 끝(`ImplementationView implementation` 뒤)에 필드 추가 — 콤마 처리 주의:

```java
        AnalysisView analysis,
        ImplementationView implementation,
        DeploymentView deployment
) {
```

`ImplementationView` 레코드 정의 뒤에 새 레코드 추가:

```java
    /** DEPLOYED 또는 배포 시도 이후에만 의미 있음. null은 배포 이력 없음. */
    public record DeploymentView(
            String deployUrl,
            Integer deployHostPort,
            String deployImage,
            OffsetDateTime deployedAt,
            String deployLog
    ) {}
```

`of(Task t, TaskAnalysis a)` 안에서 `iv` 생성 뒤, `return new TaskResponse(...)` 앞에 추가:

```java
        boolean hasDeploy = t.getDeployUrl() != null || t.getDeployLog() != null
                || t.getStatus() == TaskStatus.DEPLOYING
                || t.getStatus() == TaskStatus.DEPLOY_PENDING
                || t.getStatus() == TaskStatus.UNDEPLOY_PENDING;
        DeploymentView dv = !hasDeploy ? null : new DeploymentView(
                t.getDeployUrl(),
                t.getDeployHostPort(),
                t.getDeployImage(),
                t.getDeployedAt(),
                t.getDeployLog()
        );
```

그리고 `return new TaskResponse(...)`의 마지막 인자 `iv` 뒤에 `, dv` 추가:

```java
                av,
                iv,
                dv
        );
```

- [ ] **Step 2: QueueStats 레코드에 배포 카운터 추가**

`QueueStats.java` 전체 교체:

```java
package com.hamonsoft.netismaker.dto;

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
        Double avgDurationMs
) {}
```

- [ ] **Step 3: QueueStatsRepository 쿼리/매핑 갱신**

`QueueStatsRepository.fetch()` 전체 교체:

```java
    public QueueStats fetch() {
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT pending, in_progress, awaiting_approval,
                       approved, implementing, pr_created, implementation_failed, failed,
                       deploy_pending, deploying, deployed, deploy_failed,
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
                row[12] == null ? null : ((Number) row[12]).doubleValue()
        );
    }
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java \
        src/main/java/com/hamonsoft/netismaker/dto/QueueStats.java \
        src/main/java/com/hamonsoft/netismaker/repository/QueueStatsRepository.java
git commit -m "feat(deploy): TaskResponse DeploymentView + QueueStats 배포 카운터"
```

---

## Task 10: DeployTarget 인터페이스 + 순수 헬퍼 (TDD)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PortAllocator.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupport.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PortAllocatorTest.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupportTest.java`

- [ ] **Step 1: DeployTarget 인터페이스 + 레코드 생성**

`.../workerdaemon/deploy/DeployTarget.java`:

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.nio.file.Path;
import java.util.Map;

/**
 * 배포 호스트 추상화. 워커는 docker를 직접 호출하지 않고 이 인터페이스만 사용한다.
 *
 *  MVP 구현: {@link LocalDockerTarget} (워커 로컬 docker 데몬).
 *  확장: RemoteSshDockerTarget / RegistryDeployTarget — 같은 인터페이스로 교체.
 *        설정 netis-maker.worker.deploy.target 으로 주입 선택.
 */
public interface DeployTarget {

    /** 빌드 + 실행. hostPort는 타깃이 할당해 DeployResult로 반환. */
    DeployResult deploy(DeploySpec spec) throws Exception;

    /** 컨테이너 중지 + 제거 (멱등 — 없으면 무시). */
    void stop(String containerName) throws Exception;

    /** 컨테이너 상태 조회 (보고용). */
    DeployStatus status(String containerName);

    /**
     * @param contextDir    빌드 컨텍스트(Dockerfile 포함 worktree)
     * @param imageName     예: netis-task-7:abcdef1
     * @param containerName 예: netis-task-7
     * @param containerPort 컨테이너 내부 LISTEN 포트
     * @param env           컨테이너 환경변수
     * @param labels        docker 라벨 (예: netis-maker.task=7)
     */
    record DeploySpec(
            Path contextDir,
            String imageName,
            String containerName,
            int containerPort,
            Map<String, String> env,
            Map<String, String> labels
    ) {}

    /** @param log build+run 합본 출력 tail. */
    record DeployResult(String url, String containerId, int hostPort, String image, String log) {}

    enum DeployStatus { RUNNING, STOPPED, UNKNOWN }
}
```

- [ ] **Step 2: PortAllocator + DockerfileSupport 테스트 작성**

`.../deploy/PortAllocatorTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.*;

class PortAllocatorTest {

    @Test
    void picks_first_free_port_in_range() {
        IntPredicate allFree = p -> true;
        int port = PortAllocator.allocate(19000, 19099, Set.of(), allFree);
        assertThat(port).isEqualTo(19000);
    }

    @Test
    void skips_ports_marked_in_use() {
        IntPredicate allFree = p -> true;
        int port = PortAllocator.allocate(19000, 19099, Set.of(19000, 19001), allFree);
        assertThat(port).isEqualTo(19002);
    }

    @Test
    void skips_ports_not_bindable() {
        IntPredicate freeExcept19000 = p -> p != 19000;
        int port = PortAllocator.allocate(19000, 19099, Set.of(), freeExcept19000);
        assertThat(port).isEqualTo(19001);
    }

    @Test
    void throws_when_no_port_available() {
        IntPredicate noneFree = p -> false;
        assertThatThrownBy(() -> PortAllocator.allocate(19000, 19001, Set.of(), noneFree))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

`.../deploy/DockerfileSupportTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfileSupportTest {

    @Test
    void parses_simple_expose() {
        assertThat(DockerfileSupport.parseExposedPort("FROM x\nEXPOSE 8080\n"))
                .hasValue(8080);
    }

    @Test
    void parses_expose_with_protocol() {
        assertThat(DockerfileSupport.parseExposedPort("EXPOSE 3000/tcp"))
                .hasValue(3000);
    }

    @Test
    void takes_first_when_multiple() {
        assertThat(DockerfileSupport.parseExposedPort("EXPOSE 8080\nEXPOSE 9090"))
                .hasValue(8080);
    }

    @Test
    void empty_when_no_expose() {
        assertThat(DockerfileSupport.parseExposedPort("FROM x\nRUN echo hi")).isEmpty();
    }

    @Test
    void ignores_commented_expose() {
        assertThat(DockerfileSupport.parseExposedPort("# EXPOSE 8080\nEXPOSE 5000"))
                .hasValue(5000);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.*'`
Expected: 컴파일 에러 (PortAllocator/DockerfileSupport 없음)

- [ ] **Step 4: PortAllocator 구현**

`.../deploy/PortAllocator.java`:

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * [from, to] 범위에서 사용 가능한 첫 호스트 포트를 고른다.
 * 이미 docker가 게시한 포트(inUse) + OS bind 불가 포트를 건너뛴다.
 */
public final class PortAllocator {

    private PortAllocator() {}

    /** 실제 OS bind 테스트 기반 할당. */
    public static int allocate(int from, int to, Set<Integer> inUse) {
        return allocate(from, to, inUse, PortAllocator::isBindable);
    }

    /** 테스트 주입용 — isFree 술어로 bindable 판정 대체. */
    static int allocate(int from, int to, Set<Integer> inUse, IntPredicate isFree) {
        for (int p = from; p <= to; p++) {
            if (inUse.contains(p)) continue;
            if (isFree.test(p)) return p;
        }
        throw new IllegalStateException(
                "사용 가능한 포트가 범위에 없음: " + from + "-" + to);
    }

    static boolean isBindable(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
```

- [ ] **Step 5: DockerfileSupport 구현**

`.../deploy/DockerfileSupport.java`:

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.util.Optional;

/**
 * Dockerfile 파싱 헬퍼 (순수 함수).
 */
public final class DockerfileSupport {

    private DockerfileSupport() {}

    /**
     * 첫 번째 비주석 EXPOSE 지시어의 첫 포트를 반환. 없으면 empty.
     * "EXPOSE 8080", "EXPOSE 3000/tcp" 형태 지원.
     */
    public static Optional<Integer> parseExposedPort(String dockerfileContent) {
        if (dockerfileContent == null) return Optional.empty();
        for (String raw : dockerfileContent.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#")) continue;
            if (!line.regionMatches(true, 0, "EXPOSE", 0, 6)) continue;
            String rest = line.substring(6).trim();
            if (rest.isEmpty()) continue;
            String firstToken = rest.split("\\s+")[0];
            String portStr = firstToken.split("/")[0];
            try {
                return Optional.of(Integer.parseInt(portStr));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.deploy.*'`
Expected: 9 tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DeployTarget.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/PortAllocator.java \
        src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupport.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/PortAllocatorTest.java \
        src/test/java/com/hamonsoft/netismaker/workerdaemon/deploy/DockerfileSupportTest.java
git commit -m "feat(deploy): DeployTarget 인터페이스 + PortAllocator/DockerfileSupport + 테스트"
```

---

## Task 11: WorkerProperties Deploy 설정

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java`
- Modify: `src/main/resources/application-worker.yml`

- [ ] **Step 1: WorkerProperties에 Deploy 중첩 레코드 추가**

`WorkerProperties.java`의 레코드 필드 목록 끝(`Duration implementationTimeout` 뒤)에 추가하고 — 콤마 주의 — Deploy 레코드를 정의:

```java
        String implementationPromptTemplate,
        Duration implementationTimeout,
        // 배포 단계
        Deploy deploy
) {
    /** 배포 설정. target=local 만 MVP 구현. */
    public record Deploy(
            String target,
            String portRange,           // 예: "19000-19099"
            int defaultContainerPort,
            String publicHost,
            Duration buildTimeout,
            String dockerfilePromptTemplate
    ) {
        public Deploy {
            if (target == null || target.isBlank()) target = "local";
            if (portRange == null || portRange.isBlank()) portRange = "19000-19099";
            if (defaultContainerPort <= 0) defaultContainerPort = 8080;
            if (publicHost == null || publicHost.isBlank()) publicHost = "localhost";
            if (buildTimeout == null) buildTimeout = Duration.ofMinutes(10);
        }
        public int portFrom() { return Integer.parseInt(portRange.split("-")[0].trim()); }
        public int portTo()   { return Integer.parseInt(portRange.split("-")[1].trim()); }
    }
```

그리고 기존 compact 생성자(`public WorkerProperties {`) 끝에 deploy 기본값 추가:

```java
        if (implementationTimeout == null) implementationTimeout = Duration.ofMinutes(45);
        if (deploy == null) deploy = new Deploy(null, null, 0, null, null, null);
    }
```

- [ ] **Step 2: application-worker.yml에 deploy 설정 추가**

`application-worker.yml`의 `netis-maker.worker:` 블록 안(implementation 관련 설정 옆)에 추가. 기존 들여쓰기에 맞춰 `worker:` 하위로:

```yaml
    deploy:
      target: ${DEPLOY_TARGET:local}
      port-range: ${DEPLOY_PORT_RANGE:19000-19099}
      default-container-port: ${DEPLOY_DEFAULT_PORT:8080}
      public-host: ${DEPLOY_PUBLIC_HOST:localhost}
      build-timeout: 10m
      dockerfile-prompt-template: |
        당신은 DevOps 엔지니어입니다. 현재 디렉토리는 '{github_repo}'의 '{branch}' 브랜치 체크아웃입니다.
        이 프로젝트를 컨테이너로 실행하기 위한 production용 Dockerfile을 현재 디렉토리 루트에 생성하세요.

        지침:
        - 프로젝트 타입(빌드 도구/런타임)을 먼저 감지하세요 (예: Gradle/Maven+JDK, Node, Python 등).
        - 멀티스테이지 빌드로 최종 이미지를 작게 유지하세요.
        - 애플리케이션이 LISTEN하는 포트를 EXPOSE 지시어로 반드시 명시하세요.
        - 외부 DB/서비스 의존은 환경변수로 주입받도록 두고, 빌드 시 필수 단계만 수행하세요.
        - Dockerfile 외 다른 파일은 만들지 마세요.
        - 완료 직전에 생성한 Dockerfile 전체 내용을 출력하세요.
```

- [ ] **Step 3: 컴파일 + 워커 부팅 검증**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL
(설정 바인딩은 Task 15 후 워커 부팅에서 함께 검증.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerProperties.java \
        src/main/resources/application-worker.yml
git commit -m "feat(deploy): WorkerProperties.Deploy 설정 + application-worker.yml"
```

---

## Task 12: WorktreeService.createForDeploy

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java`

- [ ] **Step 1: head 브랜치 체크아웃용 메서드 추가**

`WorktreeService.java`의 `remove(...)` 메서드 앞(또는 `create` 뒤)에 추가:

```java
    /**
     * 배포용 worktree 생성. 구현용 create와 달리 새 브랜치를 만들지 않고
     * 기존 head 브랜치(origin/{headBranch})를 detached 체크아웃한다 (빌드만 필요).
     * 멱등: 기존 deploy worktree 있으면 강제 제거 후 재생성.
     *
     * @param repoCacheDir GitRepoCache.ensureFresh가 반환한 디렉토리 (origin/{headBranch} fetch 상태)
     * @param githubRepo   "owner/repo"
     * @param headBranch   배포 대상 브랜치 (PR head)
     * @param taskId       worktree 경로 구성용
     * @return 생성된 worktree 디렉토리 (브랜치명은 의미 없어 dir만 사용)
     */
    public File createForDeploy(File repoCacheDir, String githubRepo,
                                String headBranch, long taskId)
            throws IOException, InterruptedException {
        return repos.withRepoLock(githubRepo,
                () -> doCreateForDeploy(repoCacheDir, githubRepo, headBranch, taskId));
    }

    private File doCreateForDeploy(File repoCacheDir, String githubRepo,
                                   String headBranch, long taskId)
            throws IOException, InterruptedException {
        Path worktreeDir = Paths.get(props.worktreeRoot(), githubRepo, "deploy-" + taskId);
        Files.createDirectories(worktreeDir.getParent());

        if (Files.exists(worktreeDir)) {
            log.warn("기존 deploy worktree 발견, 강제 제거: {}", worktreeDir);
            try {
                ProcessRunner.run(repoCacheDir, List.of("git", "worktree", "remove", "--force",
                        worktreeDir.toString()), GIT_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.warn("worktree remove 실패 (계속): {}", e.getMessage());
            }
            deleteRecursively(worktreeDir.toFile());
        }

        ProcessRunner.requireSuccess(repoCacheDir,
                List.of("git", "worktree", "add", "--force", "--detach",
                        worktreeDir.toString(), "origin/" + headBranch),
                GIT_TIMEOUT_SECONDS);

        log.info("deploy worktree 생성: task={} branch={} dir={}", taskId, headBranch, worktreeDir);
        return worktreeDir.toFile();
    }
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorktreeService.java
git commit -m "feat(deploy): WorktreeService.createForDeploy (head 브랜치 detached 체크아웃)"
```

---

## Task 13: LocalDockerTarget 구현

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java`

- [ ] **Step 1: LocalDockerTarget 작성**

`.../deploy/LocalDockerTarget.java`:

```java
package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.ProcessRunner;
import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 워커 로컬 docker 데몬으로 build/run/stop. DeployTarget MVP 구현.
 *
 *  target=local 일 때만 빈 등록 (기본값). 멀티 워커가 같은 머신이면 docker 데몬을
 *  공유하므로 컨테이너 stop/replace가 어느 워커에서나 일관 동작.
 */
@Component
@Profile("worker")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "netis-maker.worker.deploy", name = "target", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalDockerTarget implements DeployTarget {

    private static final String DOCKER = "docker";

    private final WorkerProperties.Deploy cfg;
    private final long buildTimeoutSec;

    public LocalDockerTarget(WorkerProperties props) {
        this.cfg = props.deploy();
        this.buildTimeoutSec = cfg.buildTimeout().toSeconds();
    }

    @Override
    public DeployResult deploy(DeploySpec spec) throws Exception {
        StringBuilder logBuf = new StringBuilder();

        // 1. build
        logBuf.append("$ docker build -t ").append(spec.imageName()).append('\n');
        logBuf.append(ProcessRunner.requireSuccess(spec.contextDir().toFile(),
                List.of(DOCKER, "build", "-t", spec.imageName(), "."),
                buildTimeoutSec));

        // 2. 기존 동일 컨테이너 제거 (교체)
        try {
            ProcessRunner.run(spec.contextDir().toFile(),
                    List.of(DOCKER, "rm", "-f", spec.containerName()), 60);
        } catch (Exception ignore) { /* 없으면 무시 */ }

        // 3. 포트 할당
        int hostPort = PortAllocator.allocate(cfg.portFrom(), cfg.portTo(), dockerPublishedPorts());

        // 4. run
        List<String> run = new ArrayList<>(List.of(
                DOCKER, "run", "-d",
                "--name", spec.containerName(),
                "-p", hostPort + ":" + spec.containerPort(),
                "--label", "netis-maker.task=" + label(spec, "netis-maker.task")));
        spec.labels().forEach((k, v) -> { run.add("--label"); run.add(k + "=" + v); });
        spec.env().forEach((k, v) -> { run.add("-e"); run.add(k + "=" + v); });
        run.add(spec.imageName());

        logBuf.append("\n$ ").append(String.join(" ", run)).append('\n');
        String runOut = ProcessRunner.requireSuccess(spec.contextDir().toFile(), run, 120);
        logBuf.append(runOut);
        String containerId = runOut.trim();

        String url = "http://" + cfg.publicHost() + ":" + hostPort;
        log.info("배포 완료: container={} url={}", spec.containerName(), url);
        return new DeployResult(url, containerId, hostPort, spec.imageName(),
                tail(logBuf.toString(), 8000));
    }

    @Override
    public void stop(String containerName) throws Exception {
        ProcessRunner.run(new File("."),
                List.of(DOCKER, "rm", "-f", containerName), 60);
        log.info("컨테이너 중지/제거: {}", containerName);
    }

    @Override
    public DeployStatus status(String containerName) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "inspect", "-f", "{{.State.Running}}", containerName), 30);
            if (r.exitCode() != 0) return DeployStatus.STOPPED;
            return r.stdout().trim().equals("true") ? DeployStatus.RUNNING : DeployStatus.STOPPED;
        } catch (Exception e) {
            return DeployStatus.UNKNOWN;
        }
    }

    /** 현재 docker가 호스트에 게시 중인 포트 집합 (할당 충돌 회피). */
    private Set<Integer> dockerPublishedPorts() {
        Set<Integer> ports = new HashSet<>();
        try {
            ProcessRunner.Result r = ProcessRunner.run(new File("."),
                    List.of(DOCKER, "ps", "--format", "{{.Ports}}"), 30);
            if (r.exitCode() != 0) return ports;
            // 예: "0.0.0.0:19000->8080/tcp, :::19000->8080/tcp"
            for (String line : r.stdout().split("\\R")) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile(":(\\d+)->").matcher(line);
                while (m.find()) ports.add(Integer.parseInt(m.group(1)));
            }
        } catch (Exception e) {
            log.warn("docker ps 포트 조회 실패 (계속): {}", e.getMessage());
        }
        return ports;
    }

    private static String label(DeploySpec spec, String key) {
        return spec.labels().getOrDefault(key, "");
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
```

> 참고: `--label netis-maker.task=...` 는 DeployService가 spec.labels에 `netis-maker.task` 키로 넣어주므로, 위 코드의 별도 첫 `--label` 라인은 중복이다. 단순화를 위해 첫 `--label netis-maker.task=...` 줄을 제거하고 라벨은 전적으로 `spec.labels().forEach(...)`에 맡겨라. (아래 Step 2에서 수정.)

- [ ] **Step 2: 라벨 중복 제거**

위 `run` 리스트 초기화에서 `"--label", "netis-maker.task=" + label(...)` 항목과 `label(...)` 헬퍼를 제거하고 다음으로 교체:

```java
        List<String> run = new ArrayList<>(List.of(
                DOCKER, "run", "-d",
                "--name", spec.containerName(),
                "-p", hostPort + ":" + spec.containerPort()));
        spec.labels().forEach((k, v) -> { run.add("--label"); run.add(k + "=" + v); });
        spec.env().forEach((k, v) -> { run.add("-e"); run.add(k + "=" + v); });
        run.add(spec.imageName());
```

그리고 `private static String label(...)` 메서드 삭제.

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/deploy/LocalDockerTarget.java
git commit -m "feat(deploy): LocalDockerTarget (로컬 docker build/run/stop + 포트 할당)"
```

---

## Task 14: DeployService 오케스트레이션

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java`

- [ ] **Step 1: DeployService 작성**

`.../workerdaemon/DeployService.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerfileSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 배포 단계 오케스트레이션 (워커):
 *   1) head 브랜치 fetch + deploy worktree 체크아웃
 *   2) Dockerfile 있으면 사용, 없으면 claude로 생성
 *   3) EXPOSE 포트 파싱
 *   4) DeployTarget.deploy 호출 (build + run)
 *
 * docker 직접 호출 없음 — 전부 DeployTarget 뒤로. 원격 호스트로 교체해도 이 클래스 불변.
 */
@Component
@Profile("worker")
@Slf4j
public class DeployService {

    private final WorkerProperties props;
    private final GitRepoCache repos;
    private final WorktreeService worktrees;
    private final ClaudeExecAdapter claude;
    private final DeployTarget target;

    public DeployService(WorkerProperties props, GitRepoCache repos,
                         WorktreeService worktrees, ClaudeExecAdapter claude,
                         DeployTarget target) {
        this.props = props;
        this.repos = repos;
        this.worktrees = worktrees;
        this.claude = claude;
        this.target = target;
    }

    /** 배포 수행. 실패 시 DeployException(로그 포함). */
    public DeployTarget.DeployResult deploy(WorkerTaskResponse task) throws DeployException {
        if (task.headBranch() == null || task.headBranch().isBlank()) {
            throw new DeployException("head 브랜치 정보 없음 (PR생성 안 된 task?)", null);
        }
        StringBuilder log = new StringBuilder();
        try {
            // 1. head 브랜치 fetch + worktree
            GitRepoCache.CheckedOutRepo repo = repos.ensureFresh(task.githubRepo(), task.headBranch());
            File wt = worktrees.createForDeploy(repo.dir(), task.githubRepo(),
                    task.headBranch(), task.id());
            Path dockerfile = wt.toPath().resolve("Dockerfile");

            // 2. Dockerfile 결정
            if (!Files.exists(dockerfile)) {
                log.append("[Dockerfile 없음 → claude 생성]\n");
                generateDockerfile(task, wt, log);
                if (!Files.exists(dockerfile)) {
                    throw new DeployException("claude가 Dockerfile을 생성하지 못함", log.toString());
                }
            } else {
                log.append("[기존 Dockerfile 사용]\n");
            }

            // 3. 컨테이너 포트
            int containerPort = DockerfileSupport.parseExposedPort(Files.readString(dockerfile))
                    .orElse(props.deploy().defaultContainerPort());
            log.append("[containerPort=").append(containerPort).append("]\n");

            // 4. build + run
            String shortSha = task.headSha() == null ? "latest"
                    : task.headSha().substring(0, Math.min(7, task.headSha().length()));
            DeployTarget.DeploySpec spec = new DeployTarget.DeploySpec(
                    wt.toPath(),
                    "netis-task-" + task.id() + ":" + shortSha,
                    "netis-task-" + task.id(),
                    containerPort,
                    Map.of(),
                    Map.of("netis-maker.task", String.valueOf(task.id())));

            DeployTarget.DeployResult r = target.deploy(spec);
            return new DeployTarget.DeployResult(r.url(), r.containerId(), r.hostPort(),
                    r.image(), log + r.log());
        } catch (DeployException e) {
            throw e;
        } catch (Exception e) {
            throw new DeployException(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    log.toString());
        }
    }

    /** 컨테이너 중지. */
    public void undeploy(long taskId) throws DeployException {
        try {
            target.stop("netis-task-" + taskId);
        } catch (Exception e) {
            throw new DeployException("컨테이너 중지 실패: " + e.getMessage(), null);
        }
    }

    private void generateDockerfile(WorkerTaskResponse task, File worktree, StringBuilder log)
            throws Exception {
        String tpl = props.deploy().dockerfilePromptTemplate();
        if (tpl == null || tpl.isBlank()) {
            tpl = """
                  현재 디렉토리 프로젝트를 컨테이너로 실행할 production용 Dockerfile을
                  현재 디렉토리 루트에 생성하세요. 멀티스테이지 빌드, EXPOSE 포트 명시,
                  Dockerfile 외 파일 생성 금지. 완료 후 내용을 출력하세요.
                  """;
        }
        String prompt = tpl
                .replace("{github_repo}", task.githubRepo())
                .replace("{branch}", task.headBranch());
        ClaudeExecAdapter.ExecResult exec = claude.exec(
                prompt, worktree, props.deploy().buildTimeout(), java.util.List.of(), true);
        log.append(tail(exec.stdout(), 2000)).append('\n');
        if (exec.exitCode() != 0) {
            throw new DeployException("Dockerfile 생성 claude exit=" + exec.exitCode(),
                    log.toString());
        }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    /** 배포 실패를 로그와 함께 전달. */
    public static class DeployException extends Exception {
        private final String deployLog;
        public DeployException(String message, String deployLog) {
            super(message);
            this.deployLog = deployLog;
        }
        public String getDeployLog() { return deployLog; }
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java
git commit -m "feat(deploy): DeployService 오케스트레이션 (worktree+Dockerfile+target)"
```

---

## Task 15: WorkerMainLoop 배포/중지 분기

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java`

- [ ] **Step 1: DeployService 의존성 주입**

`WorkerMainLoop`의 필드/생성자에 `DeployService deployService`를 추가:

필드 영역(`private final GitOpsService gitOps;` 뒤):

```java
    private final DeployService deployService;
```

생성자 시그니처 마지막 인자로 추가하고 본문에 대입:

```java
    public WorkerMainLoop(WorkerProperties props,
                          WorkerHttpClient http,
                          GitRepoCache repos,
                          ClaudeExecAdapter claude,
                          PromptResultParser parser,
                          WorkerMcpSupport mcps,
                          WorktreeService worktrees,
                          GitOpsService gitOps,
                          DeployService deployService) {
        this.props = props;
        this.http = http;
        this.repos = repos;
        this.claude = claude;
        this.parser = parser;
        this.mcps = mcps;
        this.worktrees = worktrees;
        this.gitOps = gitOps;
        this.deployService = deployService;
    }
```

- [ ] **Step 2: pollAndProcess의 kind 분기 확장**

`pollAndProcess` 내 `try { ... }` 분기를 교체:

```java
        try {
            switch (task.kind()) {
                case IMPLEMENTATION -> processImplementation(task);
                case DEPLOY -> processDeploy(task);
                case UNDEPLOY -> processUndeploy(task);
                default -> processAnalysis(task);
            }
        } catch (Throwable t) {
            log.error("작업 처리 중 예외 task={}", task.id(), t);
            switch (task.kind()) {
                case IMPLEMENTATION -> safePostImplementationFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        null, null, null);
                case DEPLOY, UNDEPLOY -> safePostDeployFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage(), null);
                default -> safePostAnalysisFailure(task.id(),
                        "처리 중 예외: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
```

- [ ] **Step 3: processDeploy / processUndeploy / safePostDeployFailure 추가**

`processImplementation(...)` 메서드 뒤에 추가:

```java
    private void processDeploy(WorkerTaskResponse task) {
        long start = System.currentTimeMillis();
        DeployService.DeployResult result;
        try {
            result = toResult(deployService.deploy(task));
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

    private void processUndeploy(WorkerTaskResponse task) {
        try {
            deployService.undeploy(task.id());
        } catch (DeployService.DeployException e) {
            // 중지 실패도 배포실패로 보고 (컨테이너 상태 불명)
            safePostDeployFailure(task.id(), "중지 실패: " + e.getMessage(), e.getDeployLog());
            return;
        }
        http.postResult(task.id(), WorkerResultRequest.undeployed(
                props.id(), "container netis-task-" + task.id() + " 중지/제거"));
        log.info("배포 중지 완료: task={}", task.id());
    }

    private void safePostDeployFailure(Long taskId, String reason, String deployLog) {
        try {
            http.postResult(taskId, WorkerResultRequest.deployFailed(props.id(), reason, deployLog));
        } catch (RestClientException e) {
            log.error("배포 실패 보고도 실패함 task={} reason={}", taskId, reason, e);
        }
    }

    // DeployTarget.DeployResult → 호출부 가독성용 얇은 어댑터 (필드 동일)
    private static DeployService.DeployResult toResult(
            com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget.DeployResult r) {
        return new DeployService.DeployResult(r.url(), r.containerId(), r.hostPort(), r.image(), r.log());
    }
```

> 위 `toResult`/`DeployService.DeployResult` 는 불필요한 중복이다. 단순화: `processDeploy`가 `DeployTarget.DeployResult`를 직접 쓰도록 바꾸고 `toResult`와 `DeployService.DeployResult` 가정을 제거한다. 아래 Step 4로 교체.

- [ ] **Step 4: processDeploy 단순화 (DeployTarget.DeployResult 직접 사용)**

Step 3에서 넣은 `processDeploy`와 `toResult`를 아래로 교체 (DeployService.deploy가 `DeployTarget.DeployResult`를 반환하므로 그대로 사용):

```java
    private void processDeploy(WorkerTaskResponse task) {
        long start = System.currentTimeMillis();
        com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget.DeployResult result;
        try {
            result = deployService.deploy(task);
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

그리고 Step 3에서 넣은 `toResult` 메서드는 삭제.

> 시간 측정에 `System.currentTimeMillis()`를 쓴다 (워커는 일반 런타임이라 제약 없음 — 워크플로 스크립트 제약과 무관).

- [ ] **Step 5: import 추가 확인**

`WorkerMainLoop.java` 상단 import에 다음이 있는지 확인하고 없으면 추가:

```java
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
```

(이미 `WorkerResultRequest`를 분석/구현에서 쓰므로 존재할 것. `DeployService`는 같은 패키지라 import 불필요.)

- [ ] **Step 6: 전체 컴파일 + 테스트**

Run: `./gradlew build -x test` 로 패키징 확인 후
Run: `./gradlew test`
Expected: 전체 BUILD SUCCESSFUL, 기존 + 신규 테스트 PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerMainLoop.java
git commit -m "feat(deploy): WorkerMainLoop DEPLOY/UNDEPLOY 처리 분기"
```

---

## Task 16: 프론트 — tasks/[id].vue 배포 UI

**Files:**
- Modify: `frontend/pages/tasks/[id].vue`

- [ ] **Step 1: 타입 + statusClass에 배포 상태 추가**

`statusClass` 함수의 매핑 객체에 배포 상태 5개 추가 (`CANCELLED:` 줄 앞):

```javascript
      DEPLOY_PENDING: 'status-chip status-deploy-pending',
      DEPLOYING: 'status-chip status-deploying',
      DEPLOYED: 'status-chip status-deployed',
      DEPLOY_FAILED: 'status-chip status-deploy-failed',
      UNDEPLOY_PENDING: 'status-chip status-deploying',
```

파일 상단 타입 정의(`interface TaskDetail` 부근, `implementation: ImplementationView | null` 뒤)에 추가:

```typescript
  deployment: DeploymentView | null
```

그리고 `ImplementationView` 인터페이스 근처에 추가:

```typescript
interface DeploymentView {
  deployUrl: string | null
  deployHostPort: number | null
  deployImage: string | null
  deployedAt: string | null
  deployLog: string | null
}
```

- [ ] **Step 2: deploy/redeploy/undeploy 액션 함수 추가**

`approve()` 함수 뒤에 추가 (`useApi`, `task`, refetch 패턴은 approve와 동일):

```javascript
async function deploy() {
  await useApi(`/api/tasks/${taskId.value}/deploy`, { method: 'POST' })
  await refresh()
}
async function redeploy() {
  await useApi(`/api/tasks/${taskId.value}/redeploy`, { method: 'POST' })
  await refresh()
}
async function undeploy() {
  $q.dialog({
    title: '배포 중지',
    message: '실행 중인 컨테이너를 중지하고 제거합니다. 계속할까요?',
    cancel: true,
    persistent: true,
  }).onOk(async () => {
    await useApi(`/api/tasks/${taskId.value}/undeploy`, { method: 'POST' })
    await refresh()
  })
}
```

> 주의: `approve()`가 데이터 갱신에 쓰는 함수명을 확인하라 (`refresh()` 또는 `refetch()` 또는 `await useAsyncData ... refresh`). 같은 함수를 사용한다. `$q`는 `const $q = useQuasar()`가 이미 있는지 확인하고 없으면 `<script setup>` 상단에 추가한다.

- [ ] **Step 3: 배포 버튼/URL 카드 템플릿 추가**

구현(PR) 카드 블록(`v-if="task.implementation"` 카드) 뒤에 배포 카드를 추가:

```html
      <!-- 배포 카드 -->
      <q-card
        v-if="task.status === 'PR_CREATED' || task.deployment"
        flat
        bordered
        class="q-mb-md"
      >
        <q-card-section class="row items-center q-gutter-sm">
          <q-icon name="rocket_launch" color="primary" size="sm" />
          <div class="text-subtitle1">배포</div>
          <q-space />

          <!-- PR생성 상태: 배포 버튼 -->
          <q-btn
            v-if="auth.isAdmin && task.status === 'PR_CREATED'"
            color="primary"
            icon="rocket_launch"
            label="배포"
            :loading="false"
            @click="deploy"
          />

          <!-- 배포중/배포대기: 진행 표시 -->
          <q-chip
            v-if="task.status === 'DEPLOYING' || task.status === 'DEPLOY_PENDING' || task.status === 'UNDEPLOY_PENDING'"
            color="orange"
            text-color="white"
          >{{ task.statusLabel }}</q-chip>

          <!-- 배포완료/실패: 재배포 + 중지 -->
          <template v-if="auth.isAdmin && (task.status === 'DEPLOYED' || task.status === 'DEPLOY_FAILED')">
            <q-btn color="primary" icon="refresh" label="재배포" @click="redeploy" />
            <q-btn
              v-if="task.status === 'DEPLOYED'"
              color="negative"
              icon="stop"
              label="중지"
              outline
              @click="undeploy"
            />
          </template>
        </q-card-section>

        <q-card-section v-if="task.deployment && task.deployment.deployUrl">
          <div class="text-caption text-grey-7">접속 URL</div>
          <a :href="task.deployment.deployUrl" target="_blank" rel="noopener">{{
            task.deployment.deployUrl
          }}</a>
          <div class="text-caption q-mt-xs">
            포트 <code>{{ task.deployment.deployHostPort }}</code>
            · 이미지 <code>{{ task.deployment.deployImage }}</code>
            <span v-if="task.deployment.deployedAt">
              · {{ new Date(task.deployment.deployedAt).toLocaleString() }}</span
            >
          </div>
        </q-card-section>

        <q-card-section v-if="task.status === 'DEPLOY_FAILED'">
          <q-banner class="bg-red-1 text-red-9">배포 실패: {{ task.failureReason }}</q-banner>
          <pre
            v-if="task.deployment && task.deployment.deployLog"
            class="deploy-log"
          >{{ task.deployment.deployLog }}</pre>
        </q-card-section>
      </q-card>
```

- [ ] **Step 4: deploy-log 스타일 추가**

`<style scoped>` 블록에 추가 (구현 로그 `pre` 스타일이 있으면 그것을 재사용해도 됨):

```css
.deploy-log {
  max-height: 240px;
  overflow: auto;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 8px;
  border-radius: 4px;
  font-size: 0.75rem;
  white-space: pre-wrap;
}
.status-deploy-pending { background: #ede7f6; color: #5e35b1; }
.status-deploying { background: #fff3e0; color: #e65100; }
.status-deployed { background: #e0f2f1; color: #00695c; }
.status-deploy-failed { background: #fce4ec; color: #ad1457; }
```

- [ ] **Step 5: 프론트 lint + 타입 확인**

Run: `cd frontend && npm run lint`
Expected: 에러 없음 (warning 허용)

- [ ] **Step 6: Commit**

```bash
git add frontend/pages/tasks/\[id\].vue
git commit -m "feat(deploy): 작업 상세에 배포/재배포/중지 버튼 + 접속 URL"
```

---

## Task 17: 프론트 — QueueStatsBar 배포 카운터

**Files:**
- Modify: `frontend/components/QueueStatsBar.vue`

- [ ] **Step 1: 인터페이스 + 카드 추가**

`QueueStats` 인터페이스에 필드 추가 (`failed: number` 뒤):

```typescript
  deployPending: number
  deploying: number
  deployed: number
  deployFailed: number
```

`<template>`에서 `분석실패` stat 블록 뒤(평균 소요 앞)에 카드 4개 추가:

```html
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포대기</div>
        <div class="stat-value text-deep-purple-9">{{ data.deployPending }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포중</div>
        <div class="stat-value text-orange-9">{{ data.deploying }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포완료</div>
        <div class="stat-value text-teal-9">{{ data.deployed }}</div>
      </div>
      <q-separator vertical />
      <div class="stat">
        <div class="stat-label">배포실패</div>
        <div class="stat-value text-pink-9">{{ data.deployFailed }}</div>
      </div>
```

- [ ] **Step 2: lint**

Run: `cd frontend && npm run lint`
Expected: 에러 없음

- [ ] **Step 3: Commit**

```bash
git add frontend/components/QueueStatsBar.vue
git commit -m "feat(deploy): QueueStatsBar에 배포 카운터 4개 추가"
```

---

## Task 18: 엔드투엔드 수동 검증

**Files:** (없음 — 실행 검증만)

> 전제: PostgreSQL 가동, `docker` 데몬 가동, PR생성(`PR_CREATED`) 상태인 task가 최소 1건 존재(없으면 기존 task #7/#8/#9 중 PR생성된 것 사용). admin 로그인.

- [ ] **Step 1: 전체 빌드 + 스택 기동**

```bash
./gradlew build
./scripts/stop-all.sh
CORS_ALLOWED_ORIGINS='http://localhost:3001,http://10.1.3.19:3001' ./scripts/start-all.sh 1
./scripts/status.sh
```
Expected: API + worker-1 ready, `:8090` LISTEN, health UP.

- [ ] **Step 2: PR생성 task 배포 트리거 (UI 또는 API)**

브라우저 `:3001`에서 PR생성 task 상세 → "배포" 클릭.
또는 API로:
```bash
# (JWT 필요) 관리자 토큰으로:
curl -X POST http://localhost:8090/api/tasks/<ID>/deploy -H "Authorization: Bearer <admin-jwt>"
```
Expected: task 상태가 `배포대기` → 곧 `배포중`으로 전이.

- [ ] **Step 3: 워커 배포 로그 + 컨테이너 확인**

```bash
tail -f .run/worker-1.log    # "배포 완료: task=.. url=http://localhost:190xx" 대기
docker ps --filter "label=netis-maker.task"
```
Expected: `netis-task-<ID>` 컨테이너 RUNNING, 포트 매핑 `190xx->...`.

- [ ] **Step 4: 접속 URL 동작 확인**

```bash
curl -i http://localhost:<할당포트>/    # 또는 브라우저
```
Expected: 앱 응답(상태코드 무관, 연결되면 성공). task 상세에 접속 URL 링크 표시.

- [ ] **Step 5: 재배포 + 중지 확인**

UI에서 "재배포" → 새 컨테이너로 교체(같은 이름, `docker ps`로 재생성된 created 시각 확인).
UI에서 "중지" → 확인 다이얼로그 → `docker ps`에서 컨테이너 사라짐, task 상태 `PR생성` 복귀, deploy_url 제거.

- [ ] **Step 6: 통계 바 확인**

`:3001` 작업 목록 상단 QueueStatsBar에 배포대기/배포중/배포완료/배포실패 카운터가 표시되는지 확인.

- [ ] **Step 7: 옵시디언 진행 기록 + 최종 커밋**

```bash
git add -A
git commit -m "feat(deploy): Docker 배포 MVP 엔드투엔드 검증 완료"
git pull --rebase origin main && git push origin main
```

---

## Self-Review

**1. Spec coverage:**
- 상태머신 5개 → Task 1 ✓
- DB 모델(컬럼+뷰) → Task 1/9 ✓
- 워커 DEPLOY/UNDEPLOY 흐름 → Task 6/8/14/15 ✓
- DeployTarget 추상화 + LocalDocker → Task 10/13 ✓
- 포트 자동할당 → Task 10(PortAllocator)/13(docker ps) ✓
- Dockerfile 자동생성 → Task 14(generateDockerfile) ✓
- API deploy/redeploy/undeploy → Task 5 ✓
- 프론트 버튼/URL/카운터 → Task 16/17 ✓
- 설정(application-worker.yml) → Task 11 ✓

**2. Placeholder scan:** 없음. 모든 코드 블록은 실제 구현. (Task 4/8의 "생성자 인자 재확인" 주석은 검증 지시이지 placeholder 아님.)

**3. Type consistency:**
- `WorkerResultRequest.deployed/deployFailed/undeployed` 팩토리 시그니처 ↔ WorkerMainLoop 호출 일치 ✓
- `DeployTarget.DeployResult(url, containerId, hostPort, image, log)` ↔ DeployService/LocalDockerTarget/WorkerMainLoop 일치 ✓
- `WorkerTaskResponse.forDeploy/forUndeploy` (11-arg 레코드) ↔ 모든 factory 일치 ✓
- QueueStats 13필드 ↔ Repository 13컬럼 매핑 ↔ QueueStatsBar 인터페이스 일치 ✓
- TaskStatus dbValue 한글 ↔ V7 뷰 FILTER 문자열 일치 (배포대기/배포중/배포완료/배포실패/배포중지대기) ✓

**알려진 단순화/위임 (구현 시 확인):**
- `TaskService`/`WorkerService` 생성자 인자: 테스트에서 실제 시그니처 재확인 후 mock 정렬.
- 프론트 `refresh()` 함수명 및 `$q` 선언: 기존 approve 흐름과 동일하게 맞춤.
- DEPLOYING 상태의 stale 회수는 MVP 범위 외(빌드 타임아웃이 워커 레벨에서 hang 방지). 필요 시 후속.
