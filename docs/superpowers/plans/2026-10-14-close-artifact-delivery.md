# 종료 산출물 보관·내려받기·등록자 메일 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업 종료 때 배포 이미지에서 WAR·JAR를 꺼내 Symphony 서버에 보관하고, 로그인한 등록자·관리자만 1회용 주소로 내려받게 하며, 작업종료 뒤 등록자에게만 메일을 보낸다.

**Architecture:** 종료 단계 enum에 `ARTIFACT_UPLOADED`를 넣고, 작업마다 `close_artifact_mode`(종료 승인 때 결정)로 필요한 단계를 정해 기존 작업과 호환한다. 워커 `ArtifactPublisher`가 `docker create/cp`로 꺼내 JDK HttpClient로 API에 스트리밍 업로드하고, API `CloseArtifactService`가 트랜잭션 밖에서 파일을 받아 `ArtifactStore`(`attachments/task-{id}/artifact/`)에 둔다. 내려받기는 `ArtifactDownloadTokens`(60초 1회용) + `permitAll` GET, 메일은 작업종료 커밋 뒤 이벤트 → 단일 스레드 실행기 → `CloseMailService`(앱 비밀번호 SMTP).

**Tech Stack:** Java 21 / Spring Boot 3.4(JPA, Flyway, Security, Mail) / JUnit5 + Mockito + AssertJ / docker CLI / JDK HttpClient / Nuxt 3 + Quasar / vitest

**Spec:** `docs/superpowers/specs/2026-10-14-close-artifact-delivery-design.md`

## 스펙과 다른 점 (Task 16에서 스펙 §13 구현 메모로 남긴다)

1. 메일 비동기는 `@Async`/`@EnableAsync` 대신 `CloseMailListener` 안의 단일 스레드 실행기. `ThreadPoolTaskExecutor` 빈을 등록하면 Spring Boot 기본 `applicationTaskExecutor`(`@ConditionalOnMissingBean(Executor)`)가 꺼지기 때문.
2. 저장 경로 규칙은 `AttachmentStorage`를 고치지 않고 새 `ArtifactStore`에 둔다. 루트는 같은 `app.attachment.dir`이라 정리 잡이 `task-{id}`째 지운다.
3. 업로드는 **트랜잭션 밖에서** 받는다: 검사 tx(잠금 없음) → 파일 쓰기 → 기록 tx(`FOR UPDATE` 후 재검사). Hikari `leak-detection-threshold: 30000`과 행 잠금을 254MB 수신 동안 잡지 않기 위해.
4. 모바일 버튼은 기존 종료 카드 관례(카드는 데스크톱·모바일 공용, 관리자 보조 버튼은 하단 '더 보기' 메뉴)를 따른다. `TaskDetailMobile`·액션 시트는 바꾸지 않는다.
5. '산출물 없이 마무리' 사유 입력은 화면에서 생략한다(서버는 선택 사유를 받는다).
6. 카탈로그 목록 카드의 켜짐 표시는 생략한다(수정 폼에서 확인).
7. `management.health.mail.enabled=false`를 더한다 — 메일 서버 접속 실패가 `/actuator/health`를 DOWN으로 만들지 않게.
8. 하이드레이션 스모크 목은 `close: null`이라 바꾸지 않는다.

## Global Constraints

- 모든 사용자 노출 문자열·주석·커밋 메시지는 한국어(코드 식별자는 영어). 상태 DB 값은 기존 한글 그대로.
- `close_artifact_mode` 값: `null` | `REQUIRED` | `SKIPPED` (`CloseStep.ARTIFACT_REQUIRED`, `CloseStep.ARTIFACT_SKIPPED`). `close_mail_status` 값: `SENT` | `FAILED` | `NO_ADDRESS` | `DISABLED`.
- 산출물 파일 이름: `{레포 경로 마지막 조각}-task-{id}-{head_sha 7자|latest}.{war|jar}`, 허용 문자 `[A-Za-z0-9._-]{1,200}`이고 `.war`·`.jar`로 끝난다. 저장 위치 `{app.attachment.dir}/task-{id}/artifact/{이름}`.
- 이미지 이름 `netis-task-{id}:{head_sha 7자|latest}` — `DeployService.imageName`. 임시 컨테이너 `netis-artifact-{id}`.
- 내려받기 토큰 TTL 60초, 1회용. 경로 `/api/artifact-downloads/{token}`(GET만 `permitAll`).
- 업로드 상한 기본 1024MB(`CLOSE_ARTIFACT_MAX_SIZE_MB`). 워커 꺼내기·업로드 타임아웃 기본 10분. 자동 탐색 후보 폴더 기본 `/usr/local/tomcat/webapps,/app`.
- 상태 전이·행 변경은 `findActiveByIdForUpdate`(FOR UPDATE) 뒤 재판정하고 `TaskStatusHistory.log(...)`를 남긴다(기존 락 정책).
- 토큰·비밀번호는 로그·예외·`failure_reason`·상태 이력·노트에 나가면 안 된다 → `GitRemotes.mask(...)`.
- 프론트: 작은따옴표·세미콜론 없음·`<script setup lang="ts">`·2-space. 확인창은 `confirmDialog($q, ...)`. Quasar 반응형 헬퍼 이름을 클래스명에 쓰지 말 것. `prettier`를 `frontend/` 전체에 돌리지 말 것.
- 작업 위치: worktree `C:/Users/mic/NetisMaker/commanCenter/.claude/worktrees/close-artifact-delivery`(브랜치 `feature/close-artifact-delivery`). 본 체크아웃은 라이브라 거기서 테스트하지 않는다.
- 백엔드 테스트(Git Bash): `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "<클래스명>"`. 이 PC는 Docker가 없어 Testcontainers 통합 테스트는 skip — CI(`RUN_TESTCONTAINERS=true`)에서 돈다. 이 PC에서 원래 실패하는 `AttachmentCleanupJobTest`·`AttachmentStorageTest`(심볼릭 링크)는 회귀가 아니다.
- 프론트 테스트: Task 13 Step 0의 node_modules 정션을 만든 뒤 `frontend` 폴더에서 `npx vitest run <파일>`.
- CRLF: 저장소는 LF, 작업본은 CRLF(`core.autocrlf=true`).
- 커밋: 이 저장소는 작성자(`khlee84`)가 설정돼 있다. 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **기능이 꺼진 상태와 이 기능 전에 승인된 종료** — 모드가 `null`이면 지금처럼 4단계로 작업종료돼야 한다. 배포 직후 종료대기·종료실패로 남아 있던 작업이 새 단계를 요구하며 영원히 작업종료가 안 되면 안 된다(Task 1 테스트 `산출물_모드가_없으면_4단계로_작업종료`).
2. **254MB 수신 동안 DB 커넥션·행 잠금을 잡지 않음** — 본문을 읽는 동안 `findActiveByIdForUpdate`가 아직 불리지 않아야 한다(Task 4 테스트 `본문을_읽는_동안에는_행을_잠그지_않는다`). 잡으면 Hikari 누수 경보와 같은 작업의 다른 전이가 멈춘다.
3. **재시도 멱등** — 업로드 뒤 진행 보고 전에 워커가 죽은 재시도는 이미지를 다시 꺼내지 않고(Task 10 `이미_보관돼_있으면_docker를_부르지_않는다`), 같은 이름·해시 재전송은 본문을 버리고 200이어야 한다(Task 4 `같은_파일을_다시_보내면_본문을_버리고_기존_정보를_준다`).
4. **내려받기 권한과 1회성** — 다른 일반 사용자는 403, 같은 토큰 두 번째 사용과 만료 토큰은 404, 인증 없는 GET은 토큰 경로에서만 열린다(Task 6 단위 + Task 12 통합).
5. **메일이 종료를 막지 않음** — SMTP 오류여도 작업종료는 유지되고 상태 `FAILED`가 남으며, 메일 헬스 체크가 `/actuator/health`를 DOWN으로 만들지 않아야 한다(Task 8 단위 + Task 12 통합의 접속 거부 SMTP).

---

## File Structure

**API (profile `api`)**
- Modify `entity/CloseStep.java` — 5단계 + `required(mode)`·`allDone(csv, mode)` + 모드 상수.
- Modify `entity/Task.java` — 9개 컬럼. Modify `entity/RepoCatalogEntry.java` — 2개 컬럼.
- Create `resources/db/migration/V27__close_artifact.sql`.
- Create `service/CloseArtifactProperties.java` — `app.close.artifact.*`. Modify `NetisMakerApplication.java`, `resources/application.yml`.
- Modify `service/TaskCloseService.java` — 승인 때 모드 결정, `skipArtifact`, 종료 요청 때 메일 주소 갱신.
- Modify `controller/TaskCloseController.java` — skip-artifact·mail resend, 요청 때 email 전달. Create `dto/SkipArtifactRequest.java`.
- Modify `controller/AuthContext.java` — `email(auth)`. Modify `service/TaskService.java`·`controller/TaskController.java` — 등록 때 email 저장.
- Create `dto/ArtifactInfo.java`, `service/ArtifactStore.java`, `service/CloseArtifactService.java`, `controller/WorkerArtifactController.java`.
- Create `dto/CloseArtifactSpec.java`, `service/CloseLinks.java`. Modify `dto/WorkerTaskResponse.java`, `dto/CloseContext.java`, `service/WorkerService.java`.
- Create `service/ArtifactDownloadTokens.java`, `service/ArtifactDownloadService.java`, `controller/CloseArtifactController.java`, `controller/ArtifactDownloadController.java`. Modify `config/SecurityConfig.java`.
- Modify `dto/TaskResponse.java` — `CloseView` 확장.
- Modify `build.gradle`. Create `service/CloseCompletedEvent.java`, `service/CloseMailService.java`, `service/CloseMailListener.java`.
- Modify `dto/RepoCatalogDto.java`, `service/RepoCatalogService.java`.

**워커 (profile `worker`)**
- Create `workerdaemon/ArtifactProperties.java`; Modify `resources/application-worker.yml`.
- Modify `workerdaemon/DeployService.java` — `imageName`.
- Modify `workerdaemon/WorkerHttpClient.java` — `currentArtifact`·`uploadArtifact`.
- Create `workerdaemon/ArtifactPublisher.java`.
- Modify `workerdaemon/TaskCloser.java`, `workerdaemon/CloseNoteWriter.java`.

**프론트**
- Modify `frontend/composables/taskClose.ts`(+spec), `frontend/pages/tasks/[id].vue`, `frontend/test/task-detail-close.spec.ts`, `frontend/pages/admin/repo-catalog.vue`, `frontend/test/repo-catalog-admin.spec.ts`.

**문서·운영**
- Modify `CLAUDE.md`, `scripts/install-autostart.ps1`, 스펙 §13.

(경로의 `entity/`·`service/` 등은 `src/main/java/com/hamonsoft/netismaker/` 아래, 테스트는 `src/test/java/com/hamonsoft/netismaker/` 아래.)

---

### Task 1: 종료 5단계·필요한 단계 규칙·V27 컬럼

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/CloseStep.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java` (merge_commit_sha 필드 뒤)
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java` (design_output_project_id 필드 뒤)
- Create: `src/main/resources/db/migration/V27__close_artifact.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (`recordCloseProgress`, `recordCloseResult`)
- Test: `src/test/java/com/hamonsoft/netismaker/entity/CloseStepTest.java`, `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceCloseTest.java`

**Interfaces:**
- Produces: `CloseStep.ARTIFACT_UPLOADED`, `CloseStep.ARTIFACT_REQUIRED = "REQUIRED"`, `CloseStep.ARTIFACT_SKIPPED = "SKIPPED"`, `static EnumSet<CloseStep> required(String artifactMode)`, `static boolean allDone(String csv, String artifactMode)`(1인자 `allDone(String)`은 삭제). `Task`의 getter/setter `requesterEmail`, `closeArtifactMode`, `artifactName`, `artifactSize`(Long), `artifactSha256`, `artifactSource`, `artifactUploadedAt`(OffsetDateTime), `closeMailStatus`, `closeMailAt`(OffsetDateTime). `RepoCatalogEntry`의 `artifactEnabled`(boolean), `artifactPath`(String).

- [ ] **Step 1: 실패하는 테스트 작성**

`CloseStepTest.java`의 마지막 테스트(`allDone은_4단계가_모두_있을때만_true`)를 아래 두 개로 바꾼다:

```java
    @Test
    void allDone은_모드에_따라_필요한_단계가_다르다() {
        String four = "BRANCH_DELETED,NOTE_WRITTEN,UNDEPLOYED,MERGED";
        assertThat(CloseStep.allDone("MERGED,UNDEPLOYED,NOTE_WRITTEN", null)).isFalse();
        assertThat(CloseStep.allDone(four, null)).isTrue();
        assertThat(CloseStep.allDone(four, "SKIPPED")).isTrue();
        assertThat(CloseStep.allDone(four, "REQUIRED")).isFalse();
        assertThat(CloseStep.allDone(four + ",ARTIFACT_UPLOADED", "REQUIRED")).isTrue();
    }

    @Test
    void append는_산출물_단계를_배포_중지와_노트_사이에_둔다() {
        assertThat(CloseStep.append("MERGED,UNDEPLOYED,NOTE_WRITTEN", CloseStep.ARTIFACT_UPLOADED))
                .isEqualTo("MERGED,UNDEPLOYED,ARTIFACT_UPLOADED,NOTE_WRITTEN");
    }
```

`WorkerServiceCloseTest.java` 끝(마지막 `}` 앞)에 추가:

```java
    @Test
    void 산출물_모드가_없으면_4단계로_작업종료() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseProgress("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        service.recordResult(7L, WorkerResultRequest.closed("w1", null));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSED);
    }

    @Test
    void 산출물_모드_REQUIRED면_산출물_단계_없이는_작업종료를_받지_않는다() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.recordResult(7L, WorkerResultRequest.closed("w1", null)))
                .isInstanceOf(TaskException.class).hasMessageContaining("종료 단계");

        t.setCloseProgress("MERGED,UNDEPLOYED,ARTIFACT_UPLOADED,NOTE_WRITTEN,BRANCH_DELETED");
        service.recordResult(7L, WorkerResultRequest.closed("w1", null));
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSED);
    }

    @Test
    void 산출물_진행_보고는_파일이_저장된_뒤에만_받는다() {
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.recordCloseProgress(7L,
                new CloseProgressRequest("w1", CloseStep.ARTIFACT_UPLOADED, null, null)))
                .isInstanceOf(TaskException.class).hasMessageContaining("산출물");

        t.setArtifactName("repo-task-7-abcdef1.war");
        service.recordCloseProgress(7L, new CloseProgressRequest("w1", CloseStep.ARTIFACT_UPLOADED, null, null));
        assertThat(t.getCloseProgress()).isEqualTo("ARTIFACT_UPLOADED");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseStepTest" --tests "WorkerServiceCloseTest"`
Expected: 컴파일 실패 — `ARTIFACT_UPLOADED`, `allDone(String, String)`, `setCloseArtifactMode`, `setArtifactName` 없음.

- [ ] **Step 3: `CloseStep` 구현**

`CloseStep.java` 전체를 바꾼다:

```java
package com.hamonsoft.netismaker.entity;

import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * 작업 종료(kind=CLOSE) 단계. 선언 순서 = 실행 순서 = task.close_progress 기록 순서
 * (스펙 2026-10-13 §4, 2026-10-14 §3). 산출물 단계는 close_artifact_mode가 REQUIRED인 작업에만 필요하다.
 */
public enum CloseStep {
    MERGED, UNDEPLOYED, ARTIFACT_UPLOADED, NOTE_WRITTEN, BRANCH_DELETED;

    /** task.close_artifact_mode 값. null = 해당 없음(기능 꺼짐·레포 꺼짐·이 기능 전 승인). */
    public static final String ARTIFACT_REQUIRED = "REQUIRED";
    public static final String ARTIFACT_SKIPPED = "SKIPPED";

    /** null·빈 값은 빈 집합, 모르는 이름은 무시(구버전·수동 편집 방어). */
    public static EnumSet<CloseStep> parse(String csv) {
        EnumSet<CloseStep> out = EnumSet.noneOf(CloseStep.class);
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String name = part.trim();
            for (CloseStep s : values()) {
                if (s.name().equals(name)) out.add(s);
            }
        }
        return out;
    }

    /** 중복 없이 enum 선언 순서로 다시 쓴다. */
    public static String append(String csv, CloseStep step) {
        EnumSet<CloseStep> set = parse(csv);
        set.add(step);
        return set.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /** 이 작업이 작업종료가 되려면 끝나야 하는 단계. 산출물 단계는 모드가 REQUIRED일 때만. */
    public static EnumSet<CloseStep> required(String artifactMode) {
        EnumSet<CloseStep> s = EnumSet.allOf(CloseStep.class);
        if (!ARTIFACT_REQUIRED.equals(artifactMode)) s.remove(ARTIFACT_UPLOADED);
        return s;
    }

    public static boolean allDone(String csv, String artifactMode) {
        return parse(csv).containsAll(required(artifactMode));
    }
}
```

- [ ] **Step 4: 엔티티 컬럼**

`Task.java`의 `private String mergeCommitSha;` 다음에 추가:

```java

    /** 종료 메일 수신 주소 — 등록 때 JWT email, 등록자의 종료 요청 때 갱신 (스펙 2026-10-14 §7). */
    @Column(name = "requester_email", length = 320)
    @Setter
    private String requesterEmail;

    /** 종료 산출물 모드 null | REQUIRED | SKIPPED — 종료 승인 때 정한다 (CloseStep.ARTIFACT_*). */
    @Column(name = "close_artifact_mode", length = 10)
    @Setter
    private String closeArtifactMode;

    /** 보관한 산출물 파일 이름. 파일은 ArtifactStore가 attachments/task-{id}/artifact/ 아래에 둔다. */
    @Column(name = "artifact_name", length = 255)
    @Setter
    private String artifactName;

    @Column(name = "artifact_size")
    @Setter
    private Long artifactSize;

    @Column(name = "artifact_sha256", length = 64)
    @Setter
    private String artifactSha256;

    /** 꺼낸 곳 — {이미지}:{이미지 안 경로}. */
    @Column(name = "artifact_source", length = 500)
    @Setter
    private String artifactSource;

    @Column(name = "artifact_uploaded_at")
    @Setter
    private OffsetDateTime artifactUploadedAt;

    /** 종료 메일 결과 SENT | FAILED | NO_ADDRESS | DISABLED. 메일 대상이 아니면 null. */
    @Column(name = "close_mail_status", length = 20)
    @Setter
    private String closeMailStatus;

    @Column(name = "close_mail_at")
    @Setter
    private OffsetDateTime closeMailAt;
```

`RepoCatalogEntry.java`의 `private String designOutputProjectId;` 다음에 추가(클래스에 `@Getter @Setter`가 있다):

```java

    /** 종료 때 배포 이미지에서 산출물을 꺼내 보관할지 (스펙 2026-10-14 §3). */
    @Column(name = "artifact_enabled", nullable = false)
    private boolean artifactEnabled;

    /** 이미지 안 산출물 경로. null이면 워커가 후보 폴더에서 하나일 때만 자동 선택. */
    @Column(name = "artifact_path", length = 500)
    private String artifactPath;
```

- [ ] **Step 5: 마이그레이션**

`V27__close_artifact.sql`:

```sql
-- 종료 산출물 보관·내려받기·등록자 메일 (스펙 2026-10-14 §8). 모두 nullable 또는 기본값 — 기존 행은 그대로.
ALTER TABLE com.task
    ADD COLUMN requester_email      VARCHAR(320),
    ADD COLUMN close_artifact_mode  VARCHAR(10),
    ADD COLUMN artifact_name        VARCHAR(255),
    ADD COLUMN artifact_size        BIGINT,
    ADD COLUMN artifact_sha256      VARCHAR(64),
    ADD COLUMN artifact_source      VARCHAR(500),
    ADD COLUMN artifact_uploaded_at TIMESTAMPTZ,
    ADD COLUMN close_mail_status    VARCHAR(20),
    ADD COLUMN close_mail_at        TIMESTAMPTZ;

ALTER TABLE com.repo_catalog
    ADD COLUMN artifact_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN artifact_path    VARCHAR(500);
```

- [ ] **Step 6: `WorkerService` 진행·결과**

`recordCloseProgress`의 `switch (req.step())`에서 `case UNDEPLOYED -> { ... }` 블록 다음에 추가:

```java
            case ARTIFACT_UPLOADED -> {
                // 파일이 먼저 저장돼 있어야 한다 — PUT /worker/tasks/{id}/artifact (스펙 2026-10-14 §5)
                if (t.getArtifactName() == null) {
                    throw new TaskException(HttpStatus.BAD_REQUEST, "산출물이 아직 저장되지 않았습니다");
                }
            }
```

`recordCloseResult`의 `case CLOSED` 첫 줄을 바꾼다:

```java
                if (!CloseStep.allDone(t.getCloseProgress(), t.getCloseArtifactMode())) {
```

메서드 위 주석 `/** 종료 결과 보고. CLOSED는 4단계가 모두 보고된 뒤에만 받는다 …` 를 `/** 종료 결과 보고. CLOSED는 이 작업에 필요한 단계(CloseStep.required)가 모두 보고된 뒤에만 받는다 — 단계가 빠진 채 끝났다고 믿지 않는다. */`로 고친다.

- [ ] **Step 7: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseStepTest" --tests "WorkerServiceCloseTest" --tests "TaskCloserTest" --tests "TaskResponseCloseTest"`
Expected: PASS(기존 종료 테스트 포함).

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/CloseStep.java src/main/java/com/hamonsoft/netismaker/entity/Task.java src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java src/main/resources/db/migration/V27__close_artifact.sql src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/test/java/com/hamonsoft/netismaker/entity/CloseStepTest.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceCloseTest.java
git commit -m "feat(close): 산출물 단계와 작업별 필요 단계 규칙, V27 컬럼

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 종료 승인 때 산출물 모드 결정 + 산출물 없이 마무리

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/CloseArtifactProperties.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/NetisMakerApplication.java`
- Modify: `src/main/resources/application.yml` (`app:` 끝)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskCloseService.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/SkipArtifactRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskCloseController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/TaskCloseServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `CloseStep.ARTIFACT_*`, `Task.closeArtifactMode`, `RepoCatalogEntry.artifactEnabled`.
- Produces: `record CloseArtifactProperties(boolean enabled, Long maxSizeMb)` + `long maxBytes()`. `TaskCloseService(TaskRepository, TaskStatusHistoryRepository, RepoCatalogRepository, CloseArtifactProperties)`, `Task skipArtifact(Long taskId, String adminId, String reason)`. `POST /api/tasks/{id}/close/skip-artifact`(ADMIN, 바디 `{reason}` 선택).

- [ ] **Step 1: 실패하는 테스트 작성**

`TaskCloseServiceTest.java`:
- import 추가: `com.hamonsoft.netismaker.entity.RepoCatalogEntry`, `com.hamonsoft.netismaker.repository.RepoCatalogRepository`, `org.mockito.ArgumentCaptor`.
- 필드 추가: `private RepoCatalogRepository catalogRepo;`
- `setUp()`의 `service = new TaskCloseService(taskRepo, historyRepo);`를 다음으로 바꾼다:

```java
        catalogRepo = mock(RepoCatalogRepository.class);
        service = new TaskCloseService(taskRepo, historyRepo, catalogRepo, new CloseArtifactProperties(false, null));
```

클래스 끝에 추가:

```java
    private TaskCloseService enabledService() {
        return new TaskCloseService(taskRepo, historyRepo, catalogRepo, new CloseArtifactProperties(true, null));
    }

    private RepoCatalogEntry catalog(boolean artifactEnabled) {
        RepoCatalogEntry e = RepoCatalogEntry.create("W", "https://github.com/owner/repo.git", "github",
                "owner/repo", null, null, "admin");
        e.setArtifactEnabled(artifactEnabled);
        return e;
    }

    @Test
    void 승인은_전역_스위치와_카탈로그가_모두_켜졌을때만_산출물_모드를_REQUIRED로_둔다() {
        Task t = task(TaskStatus.CLOSE_REVIEW);
        t.setRepoCatalogId(5L);
        RepoCatalogEntry e = catalog(true);
        when(catalogRepo.findById(5L)).thenReturn(Optional.of(e));

        assertThat(service.approve(42L, "admin").getCloseArtifactMode()).isNull();   // 전역 스위치 꺼짐

        t.setStatus(TaskStatus.CLOSE_REVIEW);
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isEqualTo("REQUIRED");

        t.setStatus(TaskStatus.CLOSE_REVIEW);
        e.setArtifactEnabled(false);
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isNull();
    }

    @Test
    void 카탈로그_id가_없으면_git_url로_찾고_그래도_없으면_해당_없음() {
        Task t = task(TaskStatus.CLOSE_REVIEW);
        t.setGitUrl("https://github.com/owner/repo.git");
        when(catalogRepo.findByGitUrl("https://github.com/owner/repo.git")).thenReturn(Optional.of(catalog(true)));
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isEqualTo("REQUIRED");

        task(TaskStatus.CLOSE_REVIEW);   // gitUrl·catalogId 없는 새 작업
        assertThat(enabledService().approve(42L, "admin").getCloseArtifactMode()).isNull();
    }

    @Test
    void 산출물_단계에서_멈춘_종료실패는_산출물_없이_마무리한다() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED,UNDEPLOYED");
        t.setFailureReason("산출물: 이미지 없음(netis-task-42:abcdef1)");

        Task r = service.skipArtifact(42L, "admin", "이미지를 수동으로 지웠음");

        assertThat(r.getStatus()).isEqualTo(TaskStatus.CLOSE_PENDING);
        assertThat(r.getCloseArtifactMode()).isEqualTo("SKIPPED");
        assertThat(r.getFailureReason()).isNull();
        assertThat(r.getCloseProgress()).isEqualTo("MERGED,UNDEPLOYED");
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo).save(cap.capture());
        assertThat(cap.getValue().getReason()).isEqualTo("산출물 없이 마무리: 이미지를 수동으로 지웠음");
    }

    @Test
    void 산출물_없이_마무리는_조건이_안_맞으면_409() {
        Task t = task(TaskStatus.CLOSE_FAILED);
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED");                              // 배포 중지 전
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null)).isInstanceOf(TaskException.class);

        t.setCloseProgress("MERGED,UNDEPLOYED,ARTIFACT_UPLOADED"); // 이미 올림
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null)).isInstanceOf(TaskException.class);

        t.setCloseProgress("MERGED,UNDEPLOYED");
        t.setCloseArtifactMode(null);                              // 산출물 대상 아님
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null)).isInstanceOf(TaskException.class);

        t.setCloseArtifactMode("REQUIRED");
        t.setStatus(TaskStatus.CLOSING);                           // 실패 상태 아님
        assertThatThrownBy(() -> service.skipArtifact(42L, "admin", null))
                .isInstanceOf(TaskException.class).hasMessageContaining("종료실패");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "TaskCloseServiceTest"`
Expected: 컴파일 실패 — `CloseArtifactProperties`, 4인자 생성자, `skipArtifact` 없음.

- [ ] **Step 3: 설정**

`CloseArtifactProperties.java`:

```java
package com.hamonsoft.netismaker.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 종료 산출물 보관 설정 (스펙 2026-10-14 §3·§5).
 *  enabled      전역 스위치. 켜도 레포 카탈로그에서 artifact_enabled인 레포만 대상(기본 꺼짐)
 *  max-size-mb  워커 업로드 상한(기본 1024)
 */
@ConfigurationProperties(prefix = "app.close.artifact")
public record CloseArtifactProperties(boolean enabled, Long maxSizeMb) {

    public CloseArtifactProperties {
        if (maxSizeMb == null || maxSizeMb <= 0) maxSizeMb = 1024L;
    }

    public long maxBytes() {
        return maxSizeMb * 1024L * 1024L;
    }
}
```

`NetisMakerApplication.java`의 `@EnableConfigurationProperties({WorkerProperties.class, CloseProperties.class})`를 `@EnableConfigurationProperties({WorkerProperties.class, CloseProperties.class, CloseArtifactProperties.class})`로 바꾸고 `import com.hamonsoft.netismaker.service.CloseArtifactProperties;`를 더한다.

`application.yml`의 `app:` 아래 `attachment:` 블록(… `orphan-grace-hours: 24` 줄) 바로 다음, `logging:` 앞에 2칸 들여쓰기로 추가:

```yaml
  close:
    artifact:                             # 종료 산출물 보관 (스펙 2026-10-14). 켜도 카탈로그에서 켠 레포만 대상
      enabled: ${CLOSE_ARTIFACT_ENABLED:false}
      max-size-mb: ${CLOSE_ARTIFACT_MAX_SIZE_MB:1024}
```

- [ ] **Step 4: `TaskCloseService` 구현**

import 추가: `com.hamonsoft.netismaker.entity.RepoCatalogEntry`, `com.hamonsoft.netismaker.repository.RepoCatalogRepository`, `java.util.Optional`.

클래스 주석의 전이 목록에 한 줄 추가:

```java
 *   skipArtifact ─► 종료실패(산출물 단계에서 멈춤) → 종료대기, 모드 SKIPPED  (관리자, 스펙 2026-10-14 §3)
```

필드·생성자를 바꾼다:

```java
    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final RepoCatalogRepository catalogRepo;
    private final CloseArtifactProperties artifactProps;

    public TaskCloseService(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo,
                            RepoCatalogRepository catalogRepo, CloseArtifactProperties artifactProps) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.catalogRepo = catalogRepo;
        this.artifactProps = artifactProps;
    }
```

`approve`를 바꾼다:

```java
    @Transactional
    public Task approve(Long taskId, String adminId) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        requireReview(t, "승인");
        t.setFailureReason(null);
        // 산출물 모드는 승인 때 한 번 정한다 — 재시도·stale 재큐는 바꾸지 않는다 (스펙 2026-10-14 §3)
        t.setCloseArtifactMode(artifactRequired(t) ? CloseStep.ARTIFACT_REQUIRED : null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId, "관리자 종료 승인 → 종료 큐 진입"
                + (t.getCloseArtifactMode() == null ? "" : " (산출물 보관)"));
    }

    /** 전역 스위치 + 작업 레포의 카탈로그 항목(repo_catalog_id, 없으면 git_url)이 둘 다 켜졌을 때만. */
    boolean artifactRequired(Task t) {
        if (!artifactProps.enabled()) return false;
        Optional<RepoCatalogEntry> e;
        if (t.getRepoCatalogId() != null) e = catalogRepo.findById(t.getRepoCatalogId());
        else if (t.getGitUrl() != null) e = catalogRepo.findByGitUrl(t.getGitUrl());
        else e = Optional.empty();
        return e.map(RepoCatalogEntry::isArtifactEnabled).orElse(false);
    }
```

`cancel` 메서드 다음에 추가:

```java
    /**
     * 산출물 단계에서 멈춘 종료실패를 산출물 없이 이어 간다 (스펙 2026-10-14 §3). 병합 뒤라 종료 취소(409)가 안 되고,
     * 이미지가 아주 없어지면 재시도도 성공할 수 없어서 둔 출구. 워커는 다음 claim에서 노트·브랜치 정리만 한다.
     */
    @Transactional
    public Task skipArtifact(Long taskId, String adminId, String reason) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (t.getStatus() != TaskStatus.CLOSE_FAILED) {
            throw TaskException.conflict("종료실패 상태에서만 산출물 없이 마무리할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        EnumSet<CloseStep> done = CloseStep.parse(t.getCloseProgress());
        if (!CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())
                || !done.contains(CloseStep.MERGED) || !done.contains(CloseStep.UNDEPLOYED)
                || done.contains(CloseStep.ARTIFACT_UPLOADED)) {
            throw TaskException.conflict("산출물 단계에서 멈춘 종료만 산출물 없이 마무리할 수 있습니다");
        }
        t.setCloseArtifactMode(CloseStep.ARTIFACT_SKIPPED);
        t.setFailureReason(null);
        return move(t, TaskStatus.CLOSE_PENDING, "user", adminId,
                "산출물 없이 마무리" + (isBlank(reason) ? "" : ": " + reason.trim()));
    }
```

- [ ] **Step 5: 컨트롤러**

`SkipArtifactRequest.java`:

```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.Size;

/** POST /api/tasks/{id}/close/skip-artifact 바디 — 사유는 선택(상태 이력에 남는다). */
public record SkipArtifactRequest(@Size(max = 500) String reason) {}
```

`TaskCloseController.java` — 클래스 주석 목록에 `*   POST /{id}/close/skip-artifact     ROLE_ADMIN (산출물 단계에서 멈춘 종료실패만, 바디 reason 선택)`을 더하고 `cancel` 다음에 추가(import `com.hamonsoft.netismaker.dto.SkipArtifactRequest`):

```java
    @PostMapping("/{id}/close/skip-artifact")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public TaskResponse skipArtifact(@PathVariable Long id,
                                     @RequestBody(required = false) @Valid SkipArtifactRequest body,
                                     JwtAuthenticationToken auth) {
        return view(closeService.skipArtifact(id, AuthContext.requireUserId(auth),
                body == null ? null : body.reason()), true);
    }
```

- [ ] **Step 6: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "TaskCloseServiceTest"`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/CloseArtifactProperties.java src/main/java/com/hamonsoft/netismaker/NetisMakerApplication.java src/main/resources/application.yml src/main/java/com/hamonsoft/netismaker/service/TaskCloseService.java src/main/java/com/hamonsoft/netismaker/dto/SkipArtifactRequest.java src/main/java/com/hamonsoft/netismaker/controller/TaskCloseController.java src/test/java/com/hamonsoft/netismaker/service/TaskCloseServiceTest.java
git commit -m "feat(close): 종료 승인 때 산출물 모드 결정, 산출물 없이 마무리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 등록자 메일 주소 저장

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/AuthContext.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java` (`create` 두 개)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` (`create`, `createMultipart`)
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskCloseService.java` (`requestClose`)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskCloseController.java` (`request`)
- Test: Create `src/test/java/com/hamonsoft/netismaker/controller/AuthContextEmailTest.java`, Create `src/test/java/com/hamonsoft/netismaker/service/TaskServiceRequesterEmailTest.java`, Modify `TaskCloseServiceTest.java`

**Interfaces:**
- Produces: `AuthContext.email(JwtAuthenticationToken)` → String|null. `TaskService.create(TaskCreateRequest, String requesterId, String requesterEmail)`, `TaskService.create(TaskCreateRequest, List<MultipartFile>, String requesterId, String requesterEmail)`(기존 2·3인자 판은 null로 위임). `TaskCloseService.requestClose(Long, String actorId, boolean isAdmin, String actorEmail)`(기존 3인자 판은 null로 위임).

- [ ] **Step 1: 실패하는 테스트 작성**

`AuthContextEmailTest.java`:

```java
package com.hamonsoft.netismaker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextEmailTest {

    private static JwtAuthenticationToken token(Consumer<Jwt.Builder> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").claim("username", "user1");
        claims.accept(b);
        return new JwtAuthenticationToken(b.build());
    }

    @Test
    void email_클레임을_다듬어_돌려준다() {
        assertThat(AuthContext.email(token(b -> b.claim("email", "  a@hamonsoft.co.kr ")))).isEqualTo("a@hamonsoft.co.kr");
    }

    @Test
    void 없거나_비었거나_너무_길면_null() {
        assertThat(AuthContext.email(token(b -> {}))).isNull();
        assertThat(AuthContext.email(token(b -> b.claim("email", " ")))).isNull();
        assertThat(AuthContext.email(token(b -> b.claim("email", "a".repeat(321))))).isNull();
    }
}
```

`TaskServiceRequesterEmailTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskServiceRequesterEmailTest {

    private TaskService service;

    @BeforeEach
    void setUp() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        TaskStatusHistoryRepository historyRepo = mock(TaskStatusHistoryRepository.class);
        RepoCatalogService repoCatalogService = mock(RepoCatalogService.class);
        service = new TaskService(taskRepo, mock(TaskAnalysisRepository.class), mock(TaskDesignRepository.class),
                historyRepo, mock(McpCatalogService.class), repoCatalogService, new ObjectMapper(),
                mock(InterviewService.class), mock(TaskAttachmentRepository.class), mock(AttachmentStorage.class),
                mock(TaskStageUsageRepository.class));
        ReflectionTestUtils.setField(service, "userConcurrentLimit", 5);
        ReflectionTestUtils.setField(service, "maxRetry", 3);
        when(taskRepo.countActiveByRequester(any())).thenReturn(0L);
        when(repoCatalogService.resolveForRegistration(1L)).thenReturn(
                new RepoCatalogService.ResolvedRepo(1L, "alias", "https://github.com/x/y.git", "github", "x/y", null));
        when(taskRepo.save(any())).thenAnswer(i -> {
            Task t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 1L);
            return t;
        });
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void 등록_때_JWT_메일_주소를_작업에_저장한다() {
        Task t = service.create(new TaskCreateRequest(1L, "main", "제목", "설명"), "user1", "user1@hamonsoft.co.kr");
        assertThat(t.getRequesterEmail()).isEqualTo("user1@hamonsoft.co.kr");
    }

    @Test
    void 기존_2인자_등록은_주소_없이_저장한다() {
        Task t = service.create(new TaskCreateRequest(1L, "main", "제목", "설명"), "user1");
        assertThat(t.getRequesterEmail()).isNull();
    }
}
```

`TaskCloseServiceTest.java` 끝에 추가:

```java
    @Test
    void 등록자_본인의_종료_요청은_메일_주소를_갱신하고_관리자의_대리_요청은_건드리지_않는다() {
        Task t = task(TaskStatus.DEPLOYED);
        service.requestClose(42L, "user1", false, "new@hamonsoft.co.kr");
        assertThat(t.getRequesterEmail()).isEqualTo("new@hamonsoft.co.kr");

        Task u = task(TaskStatus.DEPLOYED);
        u.setRequesterEmail("keep@hamonsoft.co.kr");
        service.requestClose(42L, "admin", true, "admin@hamonsoft.co.kr");
        assertThat(u.getRequesterEmail()).isEqualTo("keep@hamonsoft.co.kr");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "AuthContextEmailTest" --tests "TaskServiceRequesterEmailTest" --tests "TaskCloseServiceTest"`
Expected: 컴파일 실패 — `AuthContext.email`, 3인자 `create`, 4인자 `requestClose` 없음.

- [ ] **Step 3: 구현**

`AuthContext.java`의 `isAdmin` 앞에 추가:

```java
    /** JWT email 클레임(netis-auth가 넣는다) — 종료 메일 수신 주소. 없거나 비었거나 320자 초과면 null. */
    public static String email(JwtAuthenticationToken auth) {
        Object raw = auth.getToken().getClaim("email");
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() || s.length() > 320 ? null : s;
    }
```

`TaskService.java` — 기존 `public Task create(TaskCreateRequest req, String requesterId) {`를 아래로 바꾸고, 본문의 `t.setRepoCatalogId(repo.catalogId());` 다음 줄에 `t.setRequesterEmail(requesterEmail);`를 넣는다:

```java
    public Task create(TaskCreateRequest req, String requesterId) {
        return create(req, requesterId, null);
    }

    /** requesterEmail: JWT email — 종료 메일 수신 주소 (스펙 2026-10-14 §7). null이면 저장하지 않는다. */
    @Transactional
    public Task create(TaskCreateRequest req, String requesterId, String requesterEmail) {
```

(첫 메서드 위의 기존 `@Transactional`은 그대로 둔다.) 파일 첨부 판도 같은 방식으로 바꾸고, 본문의 `Task saved = create(req, requesterId);`를 `Task saved = create(req, requesterId, requesterEmail);`로 고친다:

```java
    public Task create(TaskCreateRequest req, List<MultipartFile> files, String requesterId) {
        return create(req, files, requesterId, null);
    }

    @Transactional
    public Task create(TaskCreateRequest req, List<MultipartFile> files, String requesterId, String requesterEmail) {
```

`TaskController.java`: `taskService.create(req, userId)` → `taskService.create(req, userId, AuthContext.email(auth))`, `taskService.create(req, files, userId)` → `taskService.create(req, files, userId, AuthContext.email(auth))`.

`TaskCloseService.java` — `requestClose`를 바꾼다:

```java
    @Transactional
    public Task requestClose(Long taskId, String actorId, boolean isAdmin) {
        return requestClose(taskId, actorId, isAdmin, null);
    }

    /** actorEmail: 요청자의 JWT email. 등록자 본인의 요청이면 수신 주소를 갱신한다 (스펙 2026-10-14 §7). */
    @Transactional
    public Task requestClose(Long taskId, String actorId, boolean isAdmin, String actorEmail) {
        Task t = lockOwned(taskId, actorId, isAdmin);
        if (!REQUESTABLE.contains(t.getStatus())) {
            throw TaskException.conflict("배포완료 또는 배포중단됨 상태에서만 종료를 요청할 수 있습니다 (현재: "
                    + t.getStatus().dbValue() + ")");
        }
        if (t.getPrNumber() == null || isBlank(t.getHeadBranch()) || isBlank(t.getHeadSha())) {
            throw TaskException.conflict("PR 정보(번호·브랜치·커밋)가 없어 종료할 수 없습니다");
        }
        if (actorEmail != null && t.isOwnedBy(actorId)) t.setRequesterEmail(actorEmail);
        return move(t, TaskStatus.CLOSE_REVIEW, "user", actorId, "결과 확인 완료 → 종료 요청");
    }
```

`TaskCloseController.request`의 `closeService.requestClose(id, AuthContext.requireUserId(auth), isAdmin)`를 `closeService.requestClose(id, AuthContext.requireUserId(auth), isAdmin, AuthContext.email(auth))`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "AuthContextEmailTest" --tests "TaskServiceRequesterEmailTest" --tests "TaskCloseServiceTest" --tests "TaskServiceAttachmentRollbackTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/AuthContext.java src/main/java/com/hamonsoft/netismaker/service/TaskService.java src/main/java/com/hamonsoft/netismaker/controller/TaskController.java src/main/java/com/hamonsoft/netismaker/service/TaskCloseService.java src/main/java/com/hamonsoft/netismaker/controller/TaskCloseController.java src/test/java/com/hamonsoft/netismaker/controller/AuthContextEmailTest.java src/test/java/com/hamonsoft/netismaker/service/TaskServiceRequesterEmailTest.java src/test/java/com/hamonsoft/netismaker/service/TaskCloseServiceTest.java
git commit -m "feat(close): 등록·종료 요청 때 등록자 메일 주소 저장

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: API 산출물 저장 (워커 업로드 수신)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/ArtifactInfo.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/ArtifactStore.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/CloseArtifactService.java`
- Create: `src/main/java/com/hamonsoft/netismaker/controller/WorkerArtifactController.java`
- Test: Create `src/test/java/com/hamonsoft/netismaker/service/ArtifactStoreTest.java`, Create `src/test/java/com/hamonsoft/netismaker/service/CloseArtifactServiceTest.java`

**Interfaces:**
- Consumes: Task 1 컬럼, Task 2 `CloseArtifactProperties.maxBytes()`.
- Produces: `record ArtifactInfo(String name, long sizeBytes, String sha256)`(API·워커 공용). `ArtifactStore`: `static boolean validName(String)`, `Path dir(long)`, `Path file(long, String)`, `ArtifactInfo write(long taskId, String name, InputStream body, String expectedSha256, long maxBytes) throws IOException`(예외 `ArtifactStore.TooLarge`, `ArtifactStore.HashMismatch`), `boolean exists(long, String)`. `CloseArtifactService`: `ArtifactInfo receive(Long taskId, String workerId, String name, String source, String sha256, InputStream body)`, `Optional<ArtifactInfo> current(Long taskId)`. 엔드포인트 `PUT /worker/tasks/{id}/artifact?workerId=&name=&source=`(헤더 `X-Artifact-Sha256`, 본문 octet-stream), `GET /worker/tasks/{id}/artifact`.

- [ ] **Step 1: 실패하는 테스트 작성**

`ArtifactStoreTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStoreTest {

    @TempDir Path root;

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void 이름은_허용_문자이고_war나_jar로_끝나야_한다() {
        assertThat(ArtifactStore.validName("netis7.0-task-9-abcdef1.jar")).isTrue();
        assertThat(ArtifactStore.validName("w-task-1-abcdef1.war")).isTrue();
        assertThat(ArtifactStore.validName("../x.war")).isFalse();
        assertThat(ArtifactStore.validName("a b.war")).isFalse();
        assertThat(ArtifactStore.validName("a.zip")).isFalse();
        assertThat(ArtifactStore.validName(null)).isFalse();
    }

    @Test
    void 해시가_맞으면_확정하고_폴더의_다른_파일을_지운다() throws Exception {
        ArtifactStore store = new ArtifactStore(root.toString());
        Path dir = Files.createDirectories(root.resolve("task-7/artifact"));
        Files.writeString(dir.resolve("old-task-7-1111111.war"), "old");
        byte[] body = "WAR-BYTES".getBytes(StandardCharsets.UTF_8);

        ArtifactInfo info = store.write(7L, "w-task-7-abcdef1.war", new ByteArrayInputStream(body), sha(body), 1024);

        assertThat(info).isEqualTo(new ArtifactInfo("w-task-7-abcdef1.war", body.length, sha(body)));
        assertThat(Files.readAllBytes(dir.resolve("w-task-7-abcdef1.war"))).isEqualTo(body);
        try (var s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("w-task-7-abcdef1.war");
        }
        assertThat(store.exists(7L, "w-task-7-abcdef1.war")).isTrue();
    }

    @Test
    void 해시가_다르면_임시_파일을_지우고_거절한다() throws Exception {
        ArtifactStore store = new ArtifactStore(root.toString());
        assertThatThrownBy(() -> store.write(7L, "w-task-7-abcdef1.war",
                new ByteArrayInputStream("A".getBytes()), "0".repeat(64), 1024))
                .isInstanceOf(ArtifactStore.HashMismatch.class);
        try (var s = Files.list(root.resolve("task-7/artifact"))) {
            assertThat(s).isEmpty();
        }
    }

    @Test
    void 상한을_넘으면_임시_파일을_지우고_거절한다() throws Exception {
        ArtifactStore store = new ArtifactStore(root.toString());
        byte[] body = new byte[2048];
        assertThatThrownBy(() -> store.write(7L, "w-task-7-abcdef1.war", new ByteArrayInputStream(body), sha(body), 1024))
                .isInstanceOf(ArtifactStore.TooLarge.class);
        try (var s = Files.list(root.resolve("task-7/artifact"))) {
            assertThat(s).isEmpty();
        }
    }
}
```

`CloseArtifactServiceTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CloseArtifactServiceTest {

    @TempDir Path root;
    private TaskRepository taskRepo;
    private ArtifactStore store;
    private CloseArtifactService service;
    private Task t;
    private boolean lockedDuringRead;

    private final byte[] body = "WAR-BYTES".getBytes(StandardCharsets.UTF_8);
    private String sha;

    @BeforeEach
    void setUp() throws Exception {
        taskRepo = mock(TaskRepository.class);
        store = new ArtifactStore(root.toString());
        service = new CloseArtifactService(taskRepo, store, new CloseArtifactProperties(true, null),
                mock(PlatformTransactionManager.class));
        t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseArtifactMode("REQUIRED");
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        sha = ArtifactStoreTest.sha(body);
    }

    private InputStream tracking(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                lockedDuringRead |= mockingDetails(taskRepo).getInvocations().stream()
                        .anyMatch(i -> i.getMethod().getName().equals("findActiveByIdForUpdate"));
                return super.read(b, off, len);
            }
        };
    }

    @Test
    void 받은_파일을_저장하고_작업에_기록한다() {
        ArtifactInfo info = service.receive(7L, "w1", "repo-task-7-abcdef1.war", "netis-task-7:abcdef1:/app/x.war",
                sha, new ByteArrayInputStream(body));

        assertThat(info.sizeBytes()).isEqualTo(body.length);
        assertThat(t.getArtifactName()).isEqualTo("repo-task-7-abcdef1.war");
        assertThat(t.getArtifactSize()).isEqualTo((long) body.length);
        assertThat(t.getArtifactSha256()).isEqualTo(sha);
        assertThat(t.getArtifactSource()).isEqualTo("netis-task-7:abcdef1:/app/x.war");
        assertThat(t.getArtifactUploadedAt()).isNotNull();
        assertThat(Files.exists(root.resolve("task-7/artifact/repo-task-7-abcdef1.war"))).isTrue();
    }

    @Test
    void 본문을_읽는_동안에는_행을_잠그지_않는다() {
        service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha, tracking(body));
        assertThat(lockedDuringRead).isFalse();
        var order = inOrder(taskRepo);
        order.verify(taskRepo).findActiveById(7L);
        order.verify(taskRepo).findActiveByIdForUpdate(7L);
    }

    @Test
    void 같은_파일을_다시_보내면_본문을_버리고_기존_정보를_준다() {
        service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha, new ByteArrayInputStream(body));
        reset(taskRepo);
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));

        ArtifactInfo again = service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha.toUpperCase(),
                new ByteArrayInputStream(body));

        assertThat(again.name()).isEqualTo("repo-task-7-abcdef1.war");
        verify(taskRepo, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void 상태_워커_모드가_안_맞으면_409() {
        t.setWorkerId("w2");
        assertThatThrownBy(() -> service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha,
                new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        t.setWorkerId("w1");
        t.setCloseArtifactMode(null);
        assertThatThrownBy(() -> service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha,
                new ByteArrayInputStream(body))).isInstanceOf(TaskException.class).hasMessageContaining("산출물 보관 대상");
        t.setCloseArtifactMode("REQUIRED");
        t.setStatus(TaskStatus.CLOSE_FAILED);
        assertThatThrownBy(() -> service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha,
                new ByteArrayInputStream(body))).isInstanceOf(TaskException.class).hasMessageContaining("종료중");
    }

    @Test
    void 이름_해시_형식이_틀리거나_해시가_다르거나_상한을_넘으면_4xx() {
        assertThatThrownBy(() -> service.receive(7L, "w1", "../x.war", null, sha, new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.receive(7L, "w1", "a.war", null, "xyz", new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.receive(7L, "w1", "a.war", null, "0".repeat(64), new ByteArrayInputStream(body)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        CloseArtifactService tiny = new CloseArtifactService(taskRepo, store, new CloseArtifactProperties(true, 1L),
                mock(PlatformTransactionManager.class));
        byte[] big = new byte[2 * 1024 * 1024];
        assertThatThrownBy(() -> tiny.receive(7L, "w1", "a.war", null, ArtifactStoreTest.sha(big), new ByteArrayInputStream(big)))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(t.getArtifactName()).isNull();
    }

    @Test
    void 현재_산출물은_기록과_파일이_모두_있을때만() {
        assertThat(service.current(7L)).isEmpty();
        service.receive(7L, "w1", "repo-task-7-abcdef1.war", null, sha, new ByteArrayInputStream(body));
        assertThat(service.current(7L)).contains(new ArtifactInfo("repo-task-7-abcdef1.war", body.length, sha));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "ArtifactStoreTest" --tests "CloseArtifactServiceTest"`
Expected: 컴파일 실패 — 클래스 없음.

- [ ] **Step 3: 구현**

`ArtifactInfo.java`:

```java
package com.hamonsoft.netismaker.dto;

/** 보관한 종료 산출물 — 워커 업로드 응답·조회 (스펙 2026-10-14 §5). API·워커가 함께 쓴다. */
public record ArtifactInfo(String name, long sizeBytes, String sha256) {}
```

`ArtifactStore.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 종료 산출물 파일 (스펙 2026-10-14 §5). 위치 {app.attachment.dir}/task-{id}/artifact/{이름} —
 * 작업을 소프트 삭제하면 30일 뒤 AttachmentCleanupJob이 task-{id} 폴더째 지운다(새 정리 코드 없음).
 */
@Component
@Profile("api")
public class ArtifactStore {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    private final Path root;

    public ArtifactStore(@Value("${app.attachment.dir:${user.home}/netis-maker/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && (name.endsWith(".war") || name.endsWith(".jar"))
                && !name.startsWith(".");
    }

    public Path dir(long taskId) {
        return root.resolve("task-" + taskId).resolve("artifact");
    }

    public Path file(long taskId, String name) {
        if (!validName(name)) throw new IllegalArgumentException("산출물 이름이 올바르지 않습니다: " + name);
        return dir(taskId).resolve(name);
    }

    public boolean exists(long taskId, String name) {
        return validName(name) && Files.isRegularFile(file(taskId, name));
    }

    public static final class TooLarge extends IOException {
        TooLarge(long maxBytes) {
            super("산출물이 상한(" + (maxBytes / 1024 / 1024) + "MB)을 넘습니다");
        }
    }

    public static final class HashMismatch extends IOException {
        HashMismatch(String expected, String actual) {
            super("sha256 불일치 (보낸 값 " + expected + ", 받은 값 " + actual + ")");
        }
    }

    /**
     * 본문을 {이름}.part로 받으며 sha256·크기를 센다. 상한을 넘거나 해시가 다르면 .part를 지우고 예외.
     * 맞으면 {이름}으로 옮기고 같은 폴더의 다른 파일을 지운다(작업당 하나).
     */
    public ArtifactInfo write(long taskId, String name, InputStream body, String expectedSha256, long maxBytes)
            throws IOException {
        Path target = file(taskId, name);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(name + ".part");
        MessageDigest md = sha256();
        long total = 0;
        try (InputStream in = body; OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf, 0, buf.length)) != -1) {
                total += n;
                if (total > maxBytes) throw new TooLarge(maxBytes);
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(part);
            throw new HashMismatch(expectedSha256, actual);
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(target.getParent())) {
            for (Path p : ds) {
                if (!p.getFileName().equals(target.getFileName())) Files.deleteIfExists(p);
            }
        }
        return new ArtifactInfo(name, total, actual);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`CloseArtifactService.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 워커의 종료 산출물 업로드 수신 (스펙 2026-10-14 §5).
 * 254MB 본문을 받는 동안 DB 커넥션·행 잠금을 잡지 않도록 ① 검사 tx(잠금 없음) → ② 파일 쓰기(tx 밖) →
 * ③ 기록 tx(FOR UPDATE 후 재검사)로 나눈다. Hikari leak-detection-threshold(30초)에도 걸리지 않는다.
 */
@Service
@Profile("api")
public class CloseArtifactService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final TaskRepository taskRepo;
    private final ArtifactStore store;
    private final CloseArtifactProperties props;
    private final TransactionTemplate tx;

    public CloseArtifactService(TaskRepository taskRepo, ArtifactStore store, CloseArtifactProperties props,
                                PlatformTransactionManager txManager) {
        this.taskRepo = taskRepo;
        this.store = store;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public ArtifactInfo receive(Long taskId, String workerId, String name, String source, String sha256,
                                InputStream body) {
        if (!ArtifactStore.validName(name)) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "산출물 이름은 [A-Za-z0-9._-]이고 .war 또는 .jar로 끝나야 합니다: " + name);
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "X-Artifact-Sha256 헤더는 64자 16진수여야 합니다");
        }
        if (source != null && source.length() > 500) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "source가 너무 깁니다");
        }
        // ① 검사 — 잠그지 않는다
        Task snapshot = tx.execute(s -> guard(taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound),
                workerId));
        if (name.equals(snapshot.getArtifactName()) && sha256.equalsIgnoreCase(snapshot.getArtifactSha256())
                && store.exists(taskId, name)) {
            drain(body);   // 재시도 멱등 — 같은 파일이 이미 있다
            return info(snapshot);
        }
        // ② 파일 쓰기 — 트랜잭션 밖
        ArtifactInfo saved;
        try {
            saved = store.write(taskId, name, body, sha256, props.maxBytes());
        } catch (ArtifactStore.TooLarge e) {
            throw new TaskException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        } catch (ArtifactStore.HashMismatch e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new TaskException(HttpStatus.INTERNAL_SERVER_ERROR, "산출물 저장 실패: " + e.getMessage());
        }
        // ③ 기록 — 잠그고 다시 검사(그사이 종료실패·다른 워커로 바뀌었으면 거절, 파일은 다음 시도가 덮어쓴다)
        return tx.execute(s -> {
            Task t = guard(taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound), workerId);
            OffsetDateTime now = OffsetDateTime.now();
            t.setArtifactName(saved.name());
            t.setArtifactSize(saved.sizeBytes());
            t.setArtifactSha256(saved.sha256());
            t.setArtifactSource(source);
            t.setArtifactUploadedAt(now);
            t.setUpdatedAt(now);
            return saved;
        });
    }

    /** 워커 재시도용 — 기록과 파일이 모두 있을 때만. */
    @Transactional(readOnly = true)
    public Optional<ArtifactInfo> current(Long taskId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getArtifactName() == null || !store.exists(taskId, t.getArtifactName())) return Optional.empty();
        return Optional.of(info(t));
    }

    private static Task guard(Task t, String workerId) {
        if (t.getStatus() != TaskStatus.CLOSING) {
            throw TaskException.conflict("종료중 상태가 아닙니다 (현재: " + t.getStatus().dbValue() + ")");
        }
        if (!Objects.equals(workerId, t.getWorkerId())) {
            throw TaskException.conflict("다른 워커가 잡은 작업입니다 (소유: " + t.getWorkerId() + ")");
        }
        if (!CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())) {
            throw TaskException.conflict("산출물 보관 대상이 아닙니다");
        }
        return t;
    }

    private static ArtifactInfo info(Task t) {
        return new ArtifactInfo(t.getArtifactName(), t.getArtifactSize() == null ? 0 : t.getArtifactSize(),
                t.getArtifactSha256());
    }

    private static void drain(InputStream body) {
        try (InputStream in = body) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignore) {
            // 버릴 본문 — 끊겨도 응답은 같다
        }
    }
}
```

`WorkerArtifactController.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.service.CloseArtifactService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 워커의 종료 산출물 업로드 (스펙 2026-10-14 §5, X-Worker-API-Key 인증).
 *   PUT /worker/tasks/{id}/artifact?workerId=&name=&source=  본문 octet-stream, 헤더 X-Artifact-Sha256
 *   GET /worker/tasks/{id}/artifact                          보관돼 있으면 200, 아니면 404
 * multipart가 아니라 원시 본문이라 spring.servlet.multipart 상한(25MB)과 무관하다.
 */
@RestController
@RequestMapping("/worker/tasks/{id}/artifact")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class WorkerArtifactController {

    private final CloseArtifactService artifacts;

    public WorkerArtifactController(CloseArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @PutMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ArtifactInfo upload(@PathVariable Long id, @RequestParam String workerId, @RequestParam String name,
                               @RequestParam(required = false) String source,
                               @RequestHeader("X-Artifact-Sha256") String sha256,
                               HttpServletRequest request) throws IOException {
        return artifacts.receive(id, workerId, name, source, sha256, request.getInputStream());
    }

    @GetMapping
    public ResponseEntity<ArtifactInfo> current(@PathVariable Long id) {
        return artifacts.current(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "ArtifactStoreTest" --tests "CloseArtifactServiceTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/ArtifactInfo.java src/main/java/com/hamonsoft/netismaker/service/ArtifactStore.java src/main/java/com/hamonsoft/netismaker/service/CloseArtifactService.java src/main/java/com/hamonsoft/netismaker/controller/WorkerArtifactController.java src/test/java/com/hamonsoft/netismaker/service/ArtifactStoreTest.java src/test/java/com/hamonsoft/netismaker/service/CloseArtifactServiceTest.java
git commit -m "feat(close): 워커 산출물 업로드 수신 — 트랜잭션 밖 저장, 해시·상한 검사

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: claim 페이로드 — 산출물 지시·노트 재료

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/CloseArtifactSpec.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/CloseLinks.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/CloseContext.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java`
- Modify: `src/main/resources/application.yml` (`app:` 끝)
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServiceCloseTest.java`, Create `src/test/java/com/hamonsoft/netismaker/service/CloseLinksTest.java`

**Interfaces:**
- Consumes: Task 1 컬럼·상수.
- Produces: `record CloseArtifactSpec(String path)`. `WorkerTaskResponse`의 28번째 컴포넌트 `CloseArtifactSpec closeArtifact` + 27인자 호환 생성자 + `forClose(Task, CloseContext, CloseArtifactSpec)`. `CloseContext`의 새 컴포넌트 `String artifactName, Long artifactSizeBytes, boolean artifactSkipped, String taskUrl` + 8인자 호환 생성자 + `of(..., String taskUrl)`. `CloseLinks.taskUrl(String base, Long taskId)` → String|null.

- [ ] **Step 1: 실패하는 테스트 작성**

`CloseLinksTest.java`:

```java
package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloseLinksTest {

    @Test
    void 기준_주소의_끝_슬래시를_정리해_작업_화면_주소를_만든다() {
        assertThat(CloseLinks.taskUrl("https://app-win.micthebick.dev/", 7L)).isEqualTo("https://app-win.micthebick.dev/tasks/7");
        assertThat(CloseLinks.taskUrl(" https://a.test ", 7L)).isEqualTo("https://a.test/tasks/7");
    }

    @Test
    void 기준_주소가_비면_null() {
        assertThat(CloseLinks.taskUrl("", 7L)).isNull();
        assertThat(CloseLinks.taskUrl(null, 7L)).isNull();
    }
}
```

`WorkerServiceCloseTest.java`:
- import 추가: `com.hamonsoft.netismaker.entity.RepoCatalogEntry`.
- 필드 추가: `private RepoCatalogRepository catalogRepo;`
- `setUp()`의 생성자 호출에서 `mock(RepoCatalogRepository.class)`를 `catalogRepo`로 바꾸고, 그 앞에 `catalogRepo = mock(RepoCatalogRepository.class);`를 넣는다.
- 끝에 추가:

```java
    @Test
    void 산출물_모드면_claim에_산출물_지시와_작업_주소를_싣는다() {
        Task t = task(TaskStatus.CLOSE_PENDING);
        t.setCloseProgress("MERGED,UNDEPLOYED");
        t.setCloseArtifactMode("REQUIRED");
        t.setRepoCatalogId(3L);
        RepoCatalogEntry e = RepoCatalogEntry.create("W", "https://github.com/owner/repo.git", "github",
                "owner/repo", null, null, "admin");
        e.setArtifactPath(" /usr/local/tomcat/webapps/ROOT.war ");
        when(catalogRepo.findById(3L)).thenReturn(Optional.of(e));
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://app-win.micthebick.dev/");
        when(taskRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(t));

        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();

        assertThat(r.closeArtifact().path()).isEqualTo("/usr/local/tomcat/webapps/ROOT.war");
        assertThat(r.closeContext().taskUrl()).isEqualTo("https://app-win.micthebick.dev/tasks/7");
        assertThat(r.closeContext().artifactSkipped()).isFalse();
    }

    @Test
    void 산출물_단계가_끝났거나_모드가_아니면_지시가_없고_건너뛴_작업은_표시한다() {
        Task t = task(TaskStatus.CLOSE_PENDING);
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED,UNDEPLOYED,ARTIFACT_UPLOADED");
        t.setArtifactName("repo-task-7-abcdef1.war");
        t.setArtifactSize(1024L);
        when(taskRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(t));
        WorkerTaskResponse r = service.claimNextTask("w1").orElseThrow();
        assertThat(r.closeArtifact()).isNull();
        assertThat(r.closeContext().artifactName()).isEqualTo("repo-task-7-abcdef1.war");
        assertThat(r.closeContext().artifactSizeBytes()).isEqualTo(1024L);

        Task u = task(TaskStatus.CLOSE_PENDING);
        u.setCloseArtifactMode("SKIPPED");
        when(taskRepo.findClaimableForUpdateSkipLocked(any())).thenReturn(List.of(u));
        WorkerTaskResponse s = service.claimNextTask("w1").orElseThrow();
        assertThat(s.closeArtifact()).isNull();
        assertThat(s.closeContext().artifactSkipped()).isTrue();
        assertThat(s.closeContext().taskUrl()).isNull();   // 기준 주소 미설정
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseLinksTest" --tests "WorkerServiceCloseTest"`
Expected: 컴파일 실패 — `CloseLinks`, `closeArtifact()`, `taskUrl()` 없음.

- [ ] **Step 3: 새 클래스**

`CloseArtifactSpec.java`:

```java
package com.hamonsoft.netismaker.dto;

/**
 * CLOSE claim의 산출물 지시 (스펙 2026-10-14 §3). 모드가 REQUIRED이고 산출물 단계가 남았을 때만 실린다.
 * path = 카탈로그의 이미지 안 경로, null이면 워커가 후보 폴더에서 하나일 때만 자동 선택.
 */
public record CloseArtifactSpec(String path) {}
```

`CloseLinks.java`:

```java
package com.hamonsoft.netismaker.service;

/** 메일·노트에 쓰는 Symphony 링크 (스펙 2026-10-14 §4·§7). */
public final class CloseLinks {

    private CloseLinks() {}

    /** {base}/tasks/{id}. base(APP_PUBLIC_BASE_URL)가 비면 null — 호출부는 링크 없이 안내 문구만 쓴다. */
    public static String taskUrl(String base, Long taskId) {
        if (base == null || base.isBlank() || taskId == null) return null;
        return base.strip().replaceAll("/+$", "") + "/tasks/" + taskId;
    }
}
```

`application.yml`의 Task 2에서 넣은 `close:` 블록 다음(같은 2칸 들여쓰기)에 추가:

```yaml
  # 메일·노트 링크의 기준 주소(예: https://app-win.micthebick.dev). 비면 링크 없이 안내 문구만
  public-base-url: ${APP_PUBLIC_BASE_URL:}
```

- [ ] **Step 4: `WorkerTaskResponse`**

클래스 주석의 `kind=CLOSE` 줄 끝을 `(prNumber/headSha/closeProgress/closeContext/closeArtifact 동봉)`으로 고친다. 컴포넌트 목록의 마지막 `CloseContext closeContext` 를 아래로 바꾼다:

```java
        CloseContext closeContext,
        /** CLOSE 전용: 산출물 지시. null이면 워커가 산출물 단계를 건너뛴다 (스펙 2026-10-14 §3). */
        CloseArtifactSpec closeArtifact
```

23인자 생성자의 `this(...)` 끝 `null, null, null, null);`를 `null, null, null, null, null);`로 바꾸고, 그 생성자 다음에 추가:

```java
    /** 산출물 지시(closeArtifact) 도입 전 27개 인자 생성자 — 기존 테스트·호출부 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, null);
    }
```

`forClose`를 바꾼다:

```java
    /** 종료 claim 페이로드. headSha는 병합을 고정할 구현 시점 커밋(확인한 코드만 병합). */
    public static WorkerTaskResponse forClose(Task t, CloseContext ctx) {
        return forClose(t, ctx, null);
    }

    public static WorkerTaskResponse forClose(Task t, CloseContext ctx, CloseArtifactSpec artifact) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.CLOSE, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(), List.of(), null, null,
                null, null, null, null, null, null, t.getGitUrl(), hostOf(t), null,
                t.getPrNumber(), t.getPrUrl(), t.getCloseProgress(), ctx, artifact);
    }
```

- [ ] **Step 5: `CloseContext`**

import 추가: `com.hamonsoft.netismaker.entity.CloseStep`. 컴포넌트 목록 끝 `List<UsageLine> usage` 를 아래로 바꾼다:

```java
        List<UsageLine> usage,
        /** 보관한 산출물 이름·크기(재시도에서 이미 올렸을 때 노트에 쓴다). 없으면 null. */
        String artifactName,
        Long artifactSizeBytes,
        /** 관리자가 '산출물 없이 마무리'를 눌렀다 — 노트에 '없음'을 쓴다. */
        boolean artifactSkipped,
        /** 작업 화면 주소({APP_PUBLIC_BASE_URL}/tasks/{id}). 설정이 비면 null. */
        String taskUrl
```

record 본문 첫머리(상수 앞)에 호환 생성자를 넣는다:

```java
    /** 산출물 필드 도입 전 8개 인자 생성자 — 기존 테스트 호환. */
    public CloseContext(String planMarkdown, String analysisMarkdown, String implementationTail, String mergeCommitSha,
                        String deployUrl, OffsetDateTime deployedAt, List<HistoryLine> history, List<UsageLine> usage) {
        this(planMarkdown, analysisMarkdown, implementationTail, mergeCommitSha, deployUrl, deployedAt, history, usage,
                null, null, false, null);
    }
```

`of(...)`를 둘로 바꾼다:

```java
    /** @param historyOldestFirst 시간순(오래된 것 먼저) */
    public static CloseContext of(Task t, String planMarkdown, TaskAnalysis a,
                                  List<TaskStatusHistory> historyOldestFirst, List<TaskStageUsage> usage) {
        return of(t, planMarkdown, a, historyOldestFirst, usage, null);
    }

    /** @param taskUrl 노트의 내려받기 안내에 쓰는 작업 화면 주소(설정이 비면 null) */
    public static CloseContext of(Task t, String planMarkdown, TaskAnalysis a,
                                  List<TaskStatusHistory> historyOldestFirst, List<TaskStageUsage> usage,
                                  String taskUrl) {
        return new CloseContext(
                head(planMarkdown, PLAN_MAX),
                a == null ? null : head(a.getMarkdownResult(), ANALYSIS_MAX),
                tail(t.getImplementationLog(), IMPL_TAIL_MAX),
                t.getMergeCommitSha(),
                t.getDeployUrl(),
                t.getDeployedAt(),
                historyOldestFirst.stream().map(h -> new HistoryLine(h.getAt(), h.getFromStatus(), h.getToStatus(),
                        h.getActorId() == null ? h.getActorType() : h.getActorId(), h.getReason())).toList(),
                usage.stream().map(u -> new UsageLine(u.getStage(), u.getCostUsd(),
                        u.getInputTokens(), u.getOutputTokens())).toList(),
                t.getArtifactName(),
                t.getArtifactSize(),
                CloseStep.ARTIFACT_SKIPPED.equals(t.getCloseArtifactMode()),
                taskUrl);
    }
```

- [ ] **Step 6: `WorkerService` claim**

import 추가: `com.hamonsoft.netismaker.dto.CloseArtifactSpec`, `com.hamonsoft.netismaker.entity.RepoCatalogEntry`, `org.springframework.beans.factory.annotation.Value`(이미 있으면 생략). 필드 선언부(`MAX_NOTE_PATH_LENGTH` 다음)에 추가:

```java
    /** 메일·노트 링크 기준 주소 (스펙 2026-10-14 §4). 단위 테스트에서는 null. */
    @Value("${app.public-base-url:}")
    private String publicBaseUrl;
```

`claimNextTask`의 `return Optional.of(WorkerTaskResponse.forClose(t, closeContext(t)));`를 `return Optional.of(WorkerTaskResponse.forClose(t, closeContext(t), closeArtifactSpec(t)));`로 바꾸고, `closeContext(Task t)`의 `return CloseContext.of(...)` 를 마지막 인자 `CloseLinks.taskUrl(publicBaseUrl, t.getId())`를 더한 6인자 호출로 바꾼다:

```java
        return CloseContext.of(t, registeredPlanMarkdown(t.getId()), a, history,
                stageUsageRepo.findByTaskIdOrderByStageAsc(t.getId()), CloseLinks.taskUrl(publicBaseUrl, t.getId()));
```

`closeContext` 메서드 다음에 추가:

```java
    /** 산출물 모드가 REQUIRED이고 아직 안 올렸을 때만 지시를 싣는다 — 워커는 null이면 단계를 건너뛴다. */
    private CloseArtifactSpec closeArtifactSpec(Task t) {
        if (!CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())) return null;
        if (CloseStep.parse(t.getCloseProgress()).contains(CloseStep.ARTIFACT_UPLOADED)) return null;
        Optional<RepoCatalogEntry> e;
        if (t.getRepoCatalogId() != null) e = repoCatalogRepo.findById(t.getRepoCatalogId());
        else if (t.getGitUrl() != null) e = repoCatalogRepo.findByGitUrl(t.getGitUrl());
        else e = Optional.empty();
        String path = e.map(RepoCatalogEntry::getArtifactPath).map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        return new CloseArtifactSpec(path);
    }
```

- [ ] **Step 7: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseLinksTest" --tests "WorkerServiceCloseTest" --tests "WorkerTaskResponseRepoTest" --tests "CloseContextTest" --tests "CloseNotePromptTest" --tests "TaskCloserTest"`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/CloseArtifactSpec.java src/main/java/com/hamonsoft/netismaker/service/CloseLinks.java src/main/java/com/hamonsoft/netismaker/dto/WorkerTaskResponse.java src/main/java/com/hamonsoft/netismaker/dto/CloseContext.java src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/main/resources/application.yml src/test/java/com/hamonsoft/netismaker/service/CloseLinksTest.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceCloseTest.java
git commit -m "feat(close): 종료 claim에 산출물 지시와 노트 재료(산출물·작업 주소) 싣기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 1회용 내려받기

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/ArtifactDownloadTokens.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/ArtifactDownloadService.java`
- Create: `src/main/java/com/hamonsoft/netismaker/controller/CloseArtifactController.java`
- Create: `src/main/java/com/hamonsoft/netismaker/controller/ArtifactDownloadController.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/config/SecurityConfig.java` (`apiFilterChain`)
- Test: Create `src/test/java/com/hamonsoft/netismaker/service/ArtifactDownloadTokensTest.java`, Create `src/test/java/com/hamonsoft/netismaker/service/ArtifactDownloadServiceTest.java`

**Interfaces:**
- Consumes: Task 4 `ArtifactStore`.
- Produces: `ArtifactDownloadTokens.TTL`(Duration 60초), `String issue(long taskId)`, `OptionalLong redeem(String token)`. `ArtifactDownloadService.Link(String url, long expiresInSec)`, `Download(Path file, String name, long size)`, `Link link(Long taskId, String actorId, boolean isAdmin)`, `Download redeem(String token)`. `POST /api/tasks/{id}/artifact/download-link` → `{url, expiresInSec}`, `GET /api/artifact-downloads/{token}`(인증 없음).

- [ ] **Step 1: 실패하는 테스트 작성**

`ArtifactDownloadTokensTest.java`:

```java
package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactDownloadTokensTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-14T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void 토큰은_한_번만_쓴다() {
        ArtifactDownloadTokens tokens = new ArtifactDownloadTokens(clock);
        String t = tokens.issue(7L);
        assertThat(t).matches("[A-Za-z0-9_-]{43}");
        assertThat(tokens.redeem(t)).hasValue(7L);
        assertThat(tokens.redeem(t)).isEmpty();
    }

    @Test
    void 60초가_지나면_쓸_수_없다() {
        ArtifactDownloadTokens tokens = new ArtifactDownloadTokens(clock);
        String t = tokens.issue(7L);
        now.set(now.get().plusSeconds(60));
        assertThat(tokens.redeem(t)).isEmpty();
        assertThat(tokens.redeem(null)).isEmpty();
        assertThat(tokens.redeem("nope")).isEmpty();
    }
}
```

`ArtifactDownloadServiceTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactDownloadServiceTest {

    @TempDir Path root;
    private Task t;
    private ArtifactDownloadService service;

    @BeforeEach
    void setUp() throws Exception {
        TaskRepository taskRepo = mock(TaskRepository.class);
        t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.CLOSED);
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));
        service = new ArtifactDownloadService(taskRepo, new ArtifactStore(root.toString()), new ArtifactDownloadTokens());
    }

    private void storeFile() throws Exception {
        Path dir = Files.createDirectories(root.resolve("task-7/artifact"));
        Files.writeString(dir.resolve("repo-task-7-abcdef1.war"), "WAR");
        t.setArtifactName("repo-task-7-abcdef1.war");
    }

    @Test
    void 등록자와_관리자는_1회용_주소를_받고_그_주소로_파일을_꺼낸다() throws Exception {
        storeFile();
        ArtifactDownloadService.Link link = service.link(7L, "user1", false);
        assertThat(link.url()).startsWith("/api/artifact-downloads/");
        assertThat(link.expiresInSec()).isEqualTo(60);
        String token = link.url().substring("/api/artifact-downloads/".length());

        ArtifactDownloadService.Download d = service.redeem(token);
        assertThat(d.name()).isEqualTo("repo-task-7-abcdef1.war");
        assertThat(d.size()).isEqualTo(3);
        assertThat(Files.readString(d.file())).isEqualTo("WAR");
        assertThatThrownBy(() -> service.redeem(token))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(service.link(7L, "admin", true).url()).startsWith("/api/artifact-downloads/");
    }

    @Test
    void 다른_사용자는_403_기록이_없으면_404_파일이_정리됐으면_410() throws Exception {
        assertThatThrownBy(() -> service.link(7L, "user1", false))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        t.setArtifactName("repo-task-7-abcdef1.war");
        assertThatThrownBy(() -> service.link(7L, "user1", false))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE));
        storeFile();
        assertThatThrownBy(() -> service.link(7L, "user2", false))
                .isInstanceOfSatisfying(TaskException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "ArtifactDownloadTokensTest" --tests "ArtifactDownloadServiceTest"`
Expected: 컴파일 실패 — 클래스 없음.

- [ ] **Step 3: 구현**

`ArtifactDownloadTokens.java`:

```java
package com.hamonsoft.netismaker.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 산출물 1회용 내려받기 토큰 (스펙 2026-10-14 §6). API 메모리에만 둔다 — 재기동하면 사라지고 버튼을 다시 누르면 된다.
 * URL에 JWT를 싣지 않으려고 둔 장치라 수명이 짧다(60초).
 */
@Component
@Profile("api")
public class ArtifactDownloadTokens {

    public static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(long taskId, Instant expiresAt) {}

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ArtifactDownloadTokens() {
        this(Clock.systemUTC());
    }

    ArtifactDownloadTokens(Clock clock) {
        this.clock = clock;
    }

    public String issue(long taskId) {
        Instant now = clock.instant();
        tokens.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokens.put(token, new Entry(taskId, now.plus(TTL)));
        return token;
    }

    /** 1회용 — 꺼내면서 지운다. 없거나 만료면 empty. */
    public OptionalLong redeem(String token) {
        if (token == null) return OptionalLong.empty();
        Entry e = tokens.remove(token);
        if (e == null || !e.expiresAt().isAfter(clock.instant())) return OptionalLong.empty();
        return OptionalLong.of(e.taskId());
    }
}
```

`ArtifactDownloadService.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 산출물 내려받기 (스펙 2026-10-14 §6) — 권한 확인 뒤 1회용 주소, 그 주소로 파일. */
@Service
@Profile("api")
public class ArtifactDownloadService {

    public record Link(String url, long expiresInSec) {}

    public record Download(Path file, String name, long size) {}

    private final TaskRepository taskRepo;
    private final ArtifactStore store;
    private final ArtifactDownloadTokens tokens;

    public ArtifactDownloadService(TaskRepository taskRepo, ArtifactStore store, ArtifactDownloadTokens tokens) {
        this.taskRepo = taskRepo;
        this.store = store;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public Link link(Long taskId, String actorId, boolean isAdmin) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getArtifactName() == null) throw new TaskException(HttpStatus.NOT_FOUND, "보관한 산출물이 없습니다");
        if (!t.isOwnedBy(actorId) && !isAdmin) throw TaskException.forbidden();
        if (!store.exists(taskId, t.getArtifactName())) {
            throw new TaskException(HttpStatus.GONE, "보관 파일이 정리되었습니다");
        }
        return new Link("/api/artifact-downloads/" + tokens.issue(taskId), ArtifactDownloadTokens.TTL.toSeconds());
    }

    @Transactional(readOnly = true)
    public Download redeem(String token) {
        long taskId = tokens.redeem(token).orElseThrow(TaskException::notFound);
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (t.getArtifactName() == null || !store.exists(taskId, t.getArtifactName())) throw TaskException.notFound();
        Path f = store.file(taskId, t.getArtifactName());
        try {
            return new Download(f, t.getArtifactName(), Files.size(f));
        } catch (IOException e) {
            throw TaskException.notFound();
        }
    }
}
```

(`link`는 기록이 없을 때 404를 권한 검사보다 먼저 낸다 — 기록 존재 여부는 비밀이 아니고, 테스트 순서와 같다.)

`CloseArtifactController.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.service.ArtifactDownloadService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** POST /api/tasks/{id}/artifact/download-link — 등록자 본인 또는 관리자 (스펙 2026-10-14 §6). */
@RestController
@RequestMapping("/api/tasks")
@Profile("api")
public class CloseArtifactController {

    private final ArtifactDownloadService downloads;

    public CloseArtifactController(ArtifactDownloadService downloads) {
        this.downloads = downloads;
    }

    @PostMapping("/{id}/artifact/download-link")
    public ArtifactDownloadService.Link downloadLink(@PathVariable Long id, JwtAuthenticationToken auth) {
        return downloads.link(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }
}
```

`ArtifactDownloadController.java`:

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.service.ArtifactDownloadService;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * GET /api/artifact-downloads/{token} — 인증 없음(SecurityConfig permitAll). 토큰이 곧 권한이다:
 * 60초 1회용, 권한 확인은 발급(download-link) 때 끝났다 (스펙 2026-10-14 §6).
 */
@RestController
@RequestMapping("/api/artifact-downloads")
@Profile("api")
public class ArtifactDownloadController {

    private final ArtifactDownloadService downloads;

    public ArtifactDownloadController(ArtifactDownloadService downloads) {
        this.downloads = downloads;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Resource> download(@PathVariable String token) {
        ArtifactDownloadService.Download d = downloads.redeem(token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(d.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.name(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(d.file()));
    }
}
```

`SecurityConfig.apiFilterChain`의 `.requestMatchers("/actuator/health", "/error").permitAll()` 다음 줄에 추가(import `org.springframework.http.HttpMethod`):

```java
                        // 산출물 1회용 내려받기 — 토큰이 권한(발급 때 등록자·관리자 확인, 스펙 2026-10-14 §6)
                        .requestMatchers(HttpMethod.GET, "/api/artifact-downloads/*").permitAll()
```

클래스 주석의 `나머지 ──► permitAll (actuator/health, error)` 줄을 `나머지 ──► permitAll (actuator/health, error, GET /api/artifact-downloads/{token})`으로 고친다.

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "ArtifactDownloadTokensTest" --tests "ArtifactDownloadServiceTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/ArtifactDownloadTokens.java src/main/java/com/hamonsoft/netismaker/service/ArtifactDownloadService.java src/main/java/com/hamonsoft/netismaker/controller/CloseArtifactController.java src/main/java/com/hamonsoft/netismaker/controller/ArtifactDownloadController.java src/main/java/com/hamonsoft/netismaker/config/SecurityConfig.java src/test/java/com/hamonsoft/netismaker/service/ArtifactDownloadTokensTest.java src/test/java/com/hamonsoft/netismaker/service/ArtifactDownloadServiceTest.java
git commit -m "feat(close): 산출물 60초 1회용 내려받기 주소

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 작업 응답에 산출물·메일 표시

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java` (`CloseView`, `closeViewOf`)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/TaskResponseCloseTest.java`

**Interfaces:**
- Produces: `TaskResponse.CloseView(List<String> progress, String mergeCommitSha, String notePath, String artifactMode, ArtifactView artifact, MailView mail)`, `ArtifactView(String name, long sizeBytes, String sha256, OffsetDateTime uploadedAt)`, `MailView(String status, OffsetDateTime at)`. JSON `close.artifactMode`, `close.artifact`, `close.mail`.

- [ ] **Step 1: 실패하는 테스트 작성**

`TaskResponseCloseTest.java` 끝에 추가:

```java
    @Test
    void 산출물_모드_파일_메일을_보여준다() {
        Task t = task(TaskStatus.CLOSED);
        t.setCloseArtifactMode("REQUIRED");
        t.setArtifactName("repo-task-1-abcdef1.war");
        t.setArtifactSize(266_000_000L);
        t.setArtifactSha256("ab".repeat(32));
        t.setCloseMailStatus("FAILED");
        TaskResponse.CloseView v = TaskResponse.of(t, null).close();
        assertThat(v.artifactMode()).isEqualTo("REQUIRED");
        assertThat(v.artifact().name()).isEqualTo("repo-task-1-abcdef1.war");
        assertThat(v.artifact().sizeBytes()).isEqualTo(266_000_000L);
        assertThat(v.mail().status()).isEqualTo("FAILED");
    }

    @Test
    void 산출물이_없으면_artifact와_mail은_null() {
        TaskResponse.CloseView v = TaskResponse.of(task(TaskStatus.CLOSE_REVIEW), null).close();
        assertThat(v.artifactMode()).isNull();
        assertThat(v.artifact()).isNull();
        assertThat(v.mail()).isNull();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "TaskResponseCloseTest"`
Expected: 컴파일 실패 — `artifactMode()` 없음.

- [ ] **Step 3: 구현**

`TaskResponse.java`의 `CloseView` record와 `closeViewOf`를 바꾼다:

```java
    /**
     * progress는 끝난 CloseStep 이름 — enum 순서. artifactMode는 null | REQUIRED | SKIPPED,
     * artifact·mail은 없으면 null (스펙 2026-10-14 §8). 수신 주소는 싣지 않는다.
     */
    public record CloseView(List<String> progress, String mergeCommitSha, String notePath,
                            String artifactMode, ArtifactView artifact, MailView mail) {}

    public record ArtifactView(String name, long sizeBytes, String sha256, OffsetDateTime uploadedAt) {}

    public record MailView(String status, OffsetDateTime at) {}

    private static final java.util.Set<TaskStatus> CLOSE_PHASE = java.util.EnumSet.of(
            TaskStatus.CLOSE_REVIEW, TaskStatus.CLOSE_PENDING, TaskStatus.CLOSING,
            TaskStatus.CLOSED, TaskStatus.CLOSE_FAILED);

    private static CloseView closeViewOf(Task t) {
        boolean recorded = t.getCloseProgress() != null || t.getMergeCommitSha() != null || t.getCloseNotePath() != null
                || t.getArtifactName() != null;
        if (!recorded && !CLOSE_PHASE.contains(t.getStatus())) return null;
        return new CloseView(
                com.hamonsoft.netismaker.entity.CloseStep.parse(t.getCloseProgress()).stream().map(Enum::name).toList(),
                t.getMergeCommitSha(), t.getCloseNotePath(),
                t.getCloseArtifactMode(),
                t.getArtifactName() == null ? null : new ArtifactView(t.getArtifactName(),
                        t.getArtifactSize() == null ? 0 : t.getArtifactSize(), t.getArtifactSha256(),
                        t.getArtifactUploadedAt()),
                t.getCloseMailStatus() == null ? null : new MailView(t.getCloseMailStatus(), t.getCloseMailAt()));
    }
```

(`OffsetDateTime`은 `AttachmentView` 때문에 이미 import돼 있다. 없으면 `java.time.OffsetDateTime`을 import한다.)

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "TaskResponseCloseTest" --tests "TaskResponse*"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/TaskResponse.java src/test/java/com/hamonsoft/netismaker/dto/TaskResponseCloseTest.java
git commit -m "feat(close): 작업 응답 종료 묶음에 산출물 모드·파일·메일 상태

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 등록자 메일

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yml` (`spring.mail`, `management.health.mail`, `app.close.mail`)
- Create: `src/main/java/com/hamonsoft/netismaker/service/CloseCompletedEvent.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/CloseMailService.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/CloseMailListener.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/WorkerService.java` (이벤트 발행)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskCloseController.java` (다시 보내기)
- Test: Create `src/test/java/com/hamonsoft/netismaker/service/CloseMailServiceTest.java`, Modify `WorkerServiceCloseTest.java`

**Interfaces:**
- Consumes: Task 1 컬럼, Task 5 `CloseLinks.taskUrl`.
- Produces: `record CloseCompletedEvent(Long taskId)`. `CloseMailService` 상수 `SENT`, `FAILED`, `NO_ADDRESS`, `DISABLED`, `String send(Long taskId)`(대상 아니면 null), `String resend(Long taskId)`. `WorkerService.setEvents(ApplicationEventPublisher)`(패키지 전용, `@Autowired`). `POST /api/tasks/{id}/close/mail/resend`(ADMIN) → `{status}`.

- [ ] **Step 1: 의존성·설정**

`build.gradle`의 `implementation 'org.springframework.boot:spring-boot-starter-validation'` 다음 줄에 추가:

```groovy
    implementation 'org.springframework.boot:spring-boot-starter-mail'
```

`application.yml` — `spring:` 아래 `servlet:` 블록 다음(`security:` 앞)에 2칸 들여쓰기로 추가:

```yaml
  mail:                      # 종료 등록자 메일 (스펙 2026-10-14 §7). MAIL_USERNAME이 비면 발송 기능 꺼짐(DISABLED)
    host: ${MAIL_HOST:smtp.gmail.com}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}   # Gmail 앱 비밀번호
    properties:
      "[mail.smtp.auth]": true
      "[mail.smtp.starttls.enable]": true
      "[mail.smtp.starttls.required]": true
      "[mail.smtp.connectiontimeout]": 10000
      "[mail.smtp.timeout]": 10000
      "[mail.smtp.writetimeout]": 10000
```

`management:` 아래(`endpoint:` 블록 다음)에 추가:

```yaml
  health:
    mail:
      enabled: false         # 메일 서버 접속 실패가 /actuator/health를 DOWN으로 만들지 않게
```

Task 2의 `app.close:` 블록에 `mail:`을 더한다(`artifact:`와 같은 4칸 들여쓰기):

```yaml
    mail:
      from: ${MAIL_FROM:}    # 비면 MAIL_USERNAME
```

- [ ] **Step 2: 실패하는 테스트 작성**

`CloseMailServiceTest.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloseMailServiceTest {

    private TaskRepository taskRepo;
    private TaskStatusHistoryRepository historyRepo;
    private JavaMailSender sender;
    private JdbcTemplate jdbc;
    private Task t;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        historyRepo = mock(TaskStatusHistoryRepository.class);
        sender = mock(JavaMailSender.class);
        jdbc = mock(JdbcTemplate.class);
        t = Task.create("owner/repo", "main", "로그인\n수정", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 7L);
        t.setStatus(TaskStatus.CLOSED);
        t.setCloseArtifactMode("REQUIRED");
        t.setArtifactName("repo-task-7-abcdef1.war");
        t.setArtifactSize(266_000_000L);
        t.setHeadSha("abcdef1234567890");
        t.setMergeCommitSha("a05196e0000");
        t.setRepoAlias("동부병원");
        t.setRequesterEmail("user1@hamonsoft.co.kr");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));
        when(taskRepo.findActiveById(7L)).thenReturn(Optional.of(t));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private CloseMailService service(String username) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return new CloseMailService(taskRepo, historyRepo, p, jdbc, username, "", "https://app.test/");
    }

    private String lastHistory() {
        ArgumentCaptor<TaskStatusHistory> cap = ArgumentCaptor.forClass(TaskStatusHistory.class);
        verify(historyRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue().getReason();
    }

    @Test
    void 등록자에게만_보내고_보냄을_남긴다() {
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("SENT");

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        SimpleMailMessage m = cap.getValue();
        assertThat(m.getTo()).containsExactly("user1@hamonsoft.co.kr");
        assertThat(m.getCc()).isNull();
        assertThat(m.getFrom()).isEqualTo("bot@hamonsoft.co.kr");
        assertThat(m.getSubject()).isEqualTo("[Symphony] 작업 7 종료 — 로그인 수정");
        assertThat(m.getText()).contains("레포: 동부병원").contains("산출물: repo-task-7-abcdef1.war (253.7 MB)")
                .contains("배포 커밋: abcdef1   병합 커밋: a05196e")
                .contains("로그인 후 내려받기: https://app.test/tasks/7")
                .contains("작업을 삭제하면 30일 뒤 파일도 지워집니다.");
        assertThat(t.getCloseMailStatus()).isEqualTo("SENT");
        assertThat(t.getCloseMailAt()).isNotNull();
        assertThat(lastHistory()).isEqualTo("메일 발송: user1@hamonsoft.co.kr");
    }

    @Test
    void SMTP_오류면_FAILED를_남기고_예외를_내지_않는다() {
        doThrow(new MailSendException("Connection refused")).when(sender).send(any(SimpleMailMessage.class));
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("FAILED");
        assertThat(t.getCloseMailStatus()).isEqualTo("FAILED");
        assertThat(t.getStatus()).isEqualTo(TaskStatus.CLOSED);
        assertThat(lastHistory()).startsWith("메일 실패: ");
    }

    @Test
    void 설정이_없으면_DISABLED_주소가_없으면_NO_ADDRESS() {
        assertThat(service("").send(7L)).isEqualTo("DISABLED");
        verify(sender, never()).send(any(SimpleMailMessage.class));

        t.setRequesterEmail(null);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("user1"))).thenReturn(List.of());
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("NO_ADDRESS");
        assertThat(lastHistory()).isEqualTo("메일 건너뜀: 수신 주소 없음");
    }

    @Test
    void 저장된_주소가_없으면_사용자_테이블의_주소로_보낸다() {
        t.setRequesterEmail(null);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("user1"))).thenReturn(List.of("u1@hamonsoft.co.kr"));
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isEqualTo("SENT");
        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        assertThat(cap.getValue().getTo()).containsExactly("u1@hamonsoft.co.kr");
    }

    @Test
    void 산출물_없이_마무리했거나_대상이_아니면_아무것도_하지_않는다() {
        t.setCloseArtifactMode("SKIPPED");
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isNull();
        t.setCloseArtifactMode(null);
        assertThat(service("bot@hamonsoft.co.kr").send(7L)).isNull();
        verify(sender, never()).send(any(SimpleMailMessage.class));
        verify(historyRepo, never()).save(any());
        assertThat(t.getCloseMailStatus()).isNull();
    }

    @Test
    void 다시_보내기는_작업종료이고_산출물이_있을때만() {
        t.setStatus(TaskStatus.CLOSE_FAILED);
        assertThatThrownBy(() -> service("bot@hamonsoft.co.kr").resend(7L)).isInstanceOf(TaskException.class);
        t.setStatus(TaskStatus.CLOSED);
        assertThat(service("bot@hamonsoft.co.kr").resend(7L)).isEqualTo("SENT");
    }
}
```

`WorkerServiceCloseTest.java` 끝에 추가(import `org.springframework.context.ApplicationEventPublisher`):

```java
    @Test
    void 작업종료가_기록되면_메일_이벤트를_발행한다() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        service.setEvents(events);
        Task t = task(TaskStatus.CLOSING);
        t.setWorkerId("w1");
        t.setCloseProgress("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED");
        when(taskRepo.findActiveByIdForUpdate(7L)).thenReturn(Optional.of(t));

        service.recordResult(7L, WorkerResultRequest.closed("w1", null));

        verify(events).publishEvent(new CloseCompletedEvent(7L));
    }
```

- [ ] **Step 3: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseMailServiceTest" --tests "WorkerServiceCloseTest"`
Expected: 컴파일 실패 — `CloseMailService`, `CloseCompletedEvent`, `setEvents` 없음.

- [ ] **Step 4: 구현**

`CloseCompletedEvent.java`:

```java
package com.hamonsoft.netismaker.service;

/** 작업종료 전이가 기록됐다 — 커밋 뒤 등록자 메일의 신호 (스펙 2026-10-14 §7). */
public record CloseCompletedEvent(Long taskId) {}
```

`CloseMailService.java`:

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.CloseStep;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 종료 등록자 메일 (스펙 2026-10-14 §7). 산출물이 보관된 작업종료만, 등록자에게만(참조 없음), 앱 비밀번호 SMTP.
 * 실패해도 작업종료를 되돌리지 않는다 — close_mail_status와 상태 이력에 남기고 관리자가 다시 보낸다.
 */
@Service
@Profile("api")
@Slf4j
public class CloseMailService {

    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String NO_ADDRESS = "NO_ADDRESS";
    public static final String DISABLED = "DISABLED";

    private final TaskRepository taskRepo;
    private final TaskStatusHistoryRepository historyRepo;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final JdbcTemplate jdbc;
    private final String username;
    private final String from;
    private final String publicBaseUrl;

    public CloseMailService(TaskRepository taskRepo, TaskStatusHistoryRepository historyRepo,
                            ObjectProvider<JavaMailSender> mailSender, JdbcTemplate jdbc,
                            @Value("${spring.mail.username:}") String username,
                            @Value("${app.close.mail.from:}") String from,
                            @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.taskRepo = taskRepo;
        this.historyRepo = historyRepo;
        this.mailSender = mailSender;
        this.jdbc = jdbc;
        this.username = username;
        this.from = from;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** 작업종료 직후(CloseMailListener)와 다시 보내기가 함께 쓴다. 대상이 아니면 null — 아무것도 남기지 않는다. */
    @Transactional
    public String send(Long taskId) {
        Task t = taskRepo.findActiveByIdForUpdate(taskId).orElseThrow(TaskException::notFound);
        if (!eligible(t)) return null;
        String status;
        String note;
        JavaMailSender sender = mailSender.getIfAvailable();
        if (username == null || username.isBlank() || sender == null) {
            status = DISABLED;
            note = "메일 건너뜀: 메일 설정 없음";
        } else {
            String to = recipient(t);
            if (to == null) {
                status = NO_ADDRESS;
                note = "메일 건너뜀: 수신 주소 없음";
            } else {
                try {
                    sender.send(message(t, to));
                    status = SENT;
                    note = "메일 발송: " + to;
                } catch (MailException e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    status = FAILED;
                    note = "메일 실패: " + GitRemotes.mask(msg.length() > 300 ? msg.substring(0, 300) : msg);
                    log.warn("종료 메일 실패: task={} {}", taskId, note);
                }
            }
        }
        t.setCloseMailStatus(status);
        t.setCloseMailAt(OffsetDateTime.now());
        historyRepo.save(TaskStatusHistory.log(t.getId(), t.getStatus(), t.getStatus(), "system", "mail", note));
        return status;
    }

    /** 관리자 다시 보내기 — 작업종료 + 산출물 모드 + 산출물 기록이 있어야 한다. */
    @Transactional
    public String resend(Long taskId) {
        Task t = taskRepo.findActiveById(taskId).orElseThrow(TaskException::notFound);
        if (!eligible(t)) {
            throw TaskException.conflict("작업종료이고 산출물이 보관된 작업만 메일을 다시 보낼 수 있습니다");
        }
        return send(taskId);
    }

    private static boolean eligible(Task t) {
        return t.getStatus() == TaskStatus.CLOSED
                && CloseStep.ARTIFACT_REQUIRED.equals(t.getCloseArtifactMode())
                && t.getArtifactName() != null;
    }

    /** 저장된 주소(등록·종료 요청 때 JWT email) → 없으면 com."user".email(netis-backend 공용 테이블). */
    String recipient(Task t) {
        if (t.getRequesterEmail() != null && !t.getRequesterEmail().isBlank()) return t.getRequesterEmail().trim();
        try {
            List<String> rows = jdbc.queryForList("SELECT email FROM com.\"user\" WHERE user_id = ?", String.class,
                    t.getRequesterId());
            return rows.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).findFirst().orElse(null);
        } catch (DataAccessException e) {
            log.warn("종료 메일 수신 주소 조회 실패: task={} {}", t.getId(), e.getMessage());
            return null;
        }
    }

    private SimpleMailMessage message(Task t, String to) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from == null || from.isBlank() ? username : from);
        m.setTo(to);
        m.setSubject(subject(t));
        m.setText(body(t));
        return m;
    }

    static String subject(Task t) {
        String s = ("[Symphony] 작업 " + t.getId() + " 종료 — " + (t.getTitle() == null ? "" : t.getTitle()))
                .replaceAll("[\\r\\n]+", " ");
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private String body(Task t) {
        String repo = t.getRepoAlias() != null ? t.getRepoAlias() : t.getGithubRepo();
        String mb = String.format(Locale.ROOT, "%.1f",
                (t.getArtifactSize() == null ? 0 : t.getArtifactSize()) / 1024.0 / 1024.0);
        String link = CloseLinks.taskUrl(publicBaseUrl, t.getId());
        return "작업 " + t.getId() + " \"" + t.getTitle() + "\"이 종료되었습니다.\n\n"
                + "레포: " + repo + "\n"
                + "산출물: " + t.getArtifactName() + " (" + mb + " MB)\n"
                + "배포 커밋: " + sha7(t.getHeadSha()) + "   병합 커밋: " + sha7(t.getMergeCommitSha()) + "\n\n"
                + (link == null ? "Symphony에 로그인해 작업 " + t.getId() + " 화면에서 내려받으세요."
                                : "로그인 후 내려받기: " + link) + "\n\n"
                + "작업을 삭제하면 30일 뒤 파일도 지워집니다.\n";
    }

    private static String sha7(String sha) {
        return sha == null ? "-" : sha.substring(0, Math.min(7, sha.length()));
    }
}
```

`CloseMailListener.java`:

```java
package com.hamonsoft.netismaker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 작업종료 커밋 뒤 메일을 1회 보낸다 (스펙 2026-10-14 §7). SMTP(최대 10초)가 워커 결과 보고 응답을 붙잡지 않도록
 * 전용 단일 스레드에서 돈다. @EnableAsync + 실행기 빈은 Boot 기본 applicationTaskExecutor를 꺼서 쓰지 않는다.
 */
@Component
@Profile("api")
@Slf4j
public class CloseMailListener implements DisposableBean {

    private final CloseMailService mail;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "close-mail");
        t.setDaemon(true);
        return t;
    });

    public CloseMailListener(CloseMailService mail) {
        this.mail = mail;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClosed(CloseCompletedEvent e) {
        executor.submit(() -> {
            try {
                mail.send(e.taskId());
            } catch (RuntimeException ex) {
                log.warn("종료 메일 처리 실패: task={} {}", e.taskId(), ex.getMessage());
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
```

`WorkerService.java` — import `org.springframework.beans.factory.annotation.Autowired`, `org.springframework.context.ApplicationEventPublisher`. 필드부에 추가:

```java
    /** 작업종료 → 커밋 뒤 메일(CloseMailListener). 생성자를 바꾸지 않으려고 setter 주입 — 단위 테스트에서는 null. */
    private ApplicationEventPublisher events;

    @Autowired
    void setEvents(ApplicationEventPublisher events) {
        this.events = events;
    }
```

`recordCloseResult`의 `case CLOSED` 블록 끝(`reason = ...;` 다음)에 추가:

```java
                if (events != null) events.publishEvent(new CloseCompletedEvent(t.getId()));
```

`TaskCloseController.java` — 필드·생성자에 `CloseMailService mail`을 더하고(import `com.hamonsoft.netismaker.service.CloseMailService`, `java.util.Map`), 주석 목록에 `*   POST /{id}/close/mail/resend      ROLE_ADMIN (작업종료 + 산출물 보관 작업)`을 더한 뒤 `skipArtifact` 다음에 추가:

```java
    @PostMapping("/{id}/close/mail/resend")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public Map<String, String> resendMail(@PathVariable Long id) {
        return Map.of("status", mail.resend(id));
    }
```

생성자는 다음이 된다:

```java
    private final TaskCloseService closeService;
    private final CloseMailService mail;

    public TaskCloseController(TaskCloseService closeService, CloseMailService mail) {
        this.closeService = closeService;
        this.mail = mail;
    }
```

- [ ] **Step 5: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseMailServiceTest" --tests "WorkerServiceCloseTest"`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add build.gradle src/main/resources/application.yml src/main/java/com/hamonsoft/netismaker/service/CloseCompletedEvent.java src/main/java/com/hamonsoft/netismaker/service/CloseMailService.java src/main/java/com/hamonsoft/netismaker/service/CloseMailListener.java src/main/java/com/hamonsoft/netismaker/service/WorkerService.java src/main/java/com/hamonsoft/netismaker/controller/TaskCloseController.java src/test/java/com/hamonsoft/netismaker/service/CloseMailServiceTest.java src/test/java/com/hamonsoft/netismaker/service/WorkerServiceCloseTest.java
git commit -m "feat(close): 작업종료 뒤 등록자 메일과 관리자 다시 보내기

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 레포 카탈로그 산출물 설정

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java` (`create`, `update`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `RepoCatalogEntry.artifactEnabled/artifactPath`.
- Produces: `RepoCatalogDto.View`의 끝 컴포넌트 `boolean artifactEnabled, String artifactPath`. `UpsertRequest`의 끝 컴포넌트 `Boolean artifactEnabled, @Size(max = 500) String artifactPath` + 5인자 호환 생성자. 규칙: `artifactEnabled`가 null이면 두 값 모두 유지.

- [ ] **Step 1: 실패하는 테스트 작성**

`RepoCatalogServiceTest.java` 끝에 추가:

```java
    @Test
    void 산출물_설정을_저장하고_null이면_유지한다() {
        when(repo.findByAlias(any())).thenReturn(Optional.empty());
        when(repo.findByGitUrl(any())).thenReturn(Optional.empty());
        RepoCatalogEntry saved = service.create(new RepoCatalogDto.UpsertRequest(
                "동부병원", "owner/netisdash", null, null, true, true, " /usr/local/tomcat/webapps/ROOT.war "), "admin");
        assertThat(saved.isArtifactEnabled()).isTrue();
        assertThat(saved.getArtifactPath()).isEqualTo("/usr/local/tomcat/webapps/ROOT.war");

        ReflectionTestUtils.setField(saved, "id", 9L);
        when(repo.findById(9L)).thenReturn(Optional.of(saved));
        service.update(9L, new RepoCatalogDto.UpsertRequest("동부병원", "owner/netisdash", null, null, false));
        assertThat(saved.isArtifactEnabled()).isTrue();          // 활성화 토글은 산출물 설정을 건드리지 않는다
        service.update(9L, new RepoCatalogDto.UpsertRequest("동부병원", "owner/netisdash", null, null, true, false, ""));
        assertThat(saved.isArtifactEnabled()).isFalse();
        assertThat(saved.getArtifactPath()).isNull();
        assertThat(RepoCatalogDto.View.of(saved).artifactEnabled()).isFalse();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "RepoCatalogServiceTest"`
Expected: 컴파일 실패 — 7인자 `UpsertRequest`, `View.artifactEnabled()` 없음.

- [ ] **Step 3: 구현**

`RepoCatalogDto.View` — 컴포넌트 끝 `OffsetDateTime updatedAt` 를 `OffsetDateTime updatedAt, boolean artifactEnabled, String artifactPath`로 바꾸고, 두 `of` 팩토리의 `new View(...)` 끝 `e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());`를 `e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), e.isArtifactEnabled(), e.getArtifactPath());`로 바꾼다.

`UpsertRequest` — 마지막 `Boolean enabled` 를 아래로 바꾸고 호환 생성자를 넣는다:

```java
            Boolean enabled,

            /** 종료 산출물 보관 (스펙 2026-10-14 §3). null이면 산출물 설정(경로 포함)을 바꾸지 않는다. */
            Boolean artifactEnabled,

            @Size(max = 500)
            String artifactPath
    ) {
        /** 산출물 설정 도입 전 5개 인자 — 기존 호출부·테스트 호환(산출물 설정 유지). */
        public UpsertRequest(String alias, String gitUrl, String defaultBranch, String description, Boolean enabled) {
            this(alias, gitUrl, defaultBranch, description, enabled, null, null);
        }
    }
```

`RepoCatalogService` — `create`의 `if (req.enabled() != null) e.setEnabled(req.enabled());` 다음과 `update`의 같은 줄 다음에 각각 추가:

```java
        applyArtifact(e, req);
```

클래스 끝(`blankToNull` 앞)에 추가:

```java
    /** artifactEnabled가 null이면(활성화 토글 등) 산출물 설정을 그대로 둔다. */
    private static void applyArtifact(RepoCatalogEntry e, RepoCatalogDto.UpsertRequest req) {
        if (req.artifactEnabled() == null) return;
        e.setArtifactEnabled(req.artifactEnabled());
        e.setArtifactPath(blankToNull(req.artifactPath()));
    }
```

- [ ] **Step 4: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "RepoCatalogServiceTest" --tests "RepoCatalog*"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/RepoCatalogDto.java src/main/java/com/hamonsoft/netismaker/service/RepoCatalogService.java src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServiceTest.java
git commit -m "feat(catalog): 레포별 종료 산출물 보관 켜기·이미지 안 경로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 워커 — 산출물 꺼내기·업로드

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactProperties.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/NetisMakerApplication.java`
- Modify: `src/main/resources/application-worker.yml` (`close:` 아래)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClient.java`
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisher.java`
- Test: Create `src/test/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisherTest.java`, Create `src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClientArtifactTest.java`

**Interfaces:**
- Consumes: Task 4 `ArtifactInfo`, Task 5 `CloseArtifactSpec`·`WorkerTaskResponse.closeArtifact()`.
- Produces: `record ArtifactProperties(List<String> searchDirs, Duration copyTimeout, Duration uploadTimeout)`. `DeployService.imageName(long taskId, String headSha)`. `WorkerHttpClient.currentArtifact(Long)` → `Optional<ArtifactInfo>`, `uploadArtifact(Long taskId, String name, String source, Path file, String sha256, Duration timeout)` → `ArtifactInfo`(throws IOException, InterruptedException). `ArtifactPublisher.publish(WorkerTaskResponse)` → `ArtifactPublisher.Published(ArtifactInfo info, boolean reused)`, throws `ArtifactPublisher.ArtifactException`(메시지 = 종료실패 사유), InterruptedException. `static String fileName(WorkerTaskResponse, String path)`, `static List<String> candidates(String stdout)`.

- [ ] **Step 1: 실패하는 테스트 작성**

`ArtifactPublisherTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.dto.CloseArtifactSpec;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtifactPublisherTest {

    @TempDir Path tmp;
    private WorkerHttpClient http;
    private WorkerProperties props;
    private final List<List<String>> calls = new ArrayList<>();
    private String findOut = "";
    private int inspectExit = 0;
    private int cpExit = 0;

    private static final String SHA = "abcdef1234567890abcdef1234567890abcdef12";
    private static final byte[] WAR = "WAR-BYTES".getBytes();

    @BeforeEach
    void setUp() throws Exception {
        http = mock(WorkerHttpClient.class);
        props = mock(WorkerProperties.class);
        when(props.reposDir()).thenReturn(tmp.resolve("repos").toString());
        when(http.currentArtifact(7L)).thenReturn(Optional.empty());
        when(http.uploadArtifact(eq(7L), anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(i -> new ArtifactInfo(i.getArgument(1), WAR.length, i.getArgument(4)));
    }

    private ArtifactPublisher publisher() {
        ArtifactProperties cfg = new ArtifactProperties(null, null, null);
        return new ArtifactPublisher(props, cfg, http, (args, timeout) -> {
            calls.add(List.copyOf(args));
            switch (args.get(0)) {
                case "image" -> { return new ProcessRunner.Result(inspectExit, inspectExit == 0 ? "sha256:x" : "No such image"); }
                case "run" -> { return new ProcessRunner.Result(1, findOut); }
                case "cp" -> {
                    if (cpExit == 0) Files.write(Path.of(args.get(2)), WAR);
                    return new ProcessRunner.Result(cpExit, cpExit == 0 ? "" : "Could not find the file");
                }
                default -> { return new ProcessRunner.Result(0, ""); }
            }
        });
    }

    private WorkerTaskResponse task(String path, String repo) {
        return new WorkerTaskResponse(7L, repo, "main", "t", "d", WorkerTaskResponse.Kind.CLOSE, List.of(), null, null,
                "netismaker/task-7", SHA, List.of(), null, null, null, null, null, null, null, null, null, "github",
                null, 3, "https://github.com/" + repo + "/pull/3", "MERGED,UNDEPLOYED", null, new CloseArtifactSpec(path));
    }

    @Test
    void 이미_보관돼_있으면_docker를_부르지_않는다() throws Exception {
        when(http.currentArtifact(7L)).thenReturn(Optional.of(new ArtifactInfo("w-task-7-abcdef1.war", 9, "ab")));
        ArtifactPublisher.Published p = publisher().publish(task(null, "acme/w"));
        assertThat(p.reused()).isTrue();
        assertThat(calls).isEmpty();
        verify(http, never()).uploadArtifact(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 카탈로그_경로면_find_없이_꺼내_올리고_임시_컨테이너를_지운다() throws Exception {
        ArtifactPublisher.Published p = publisher().publish(task("/usr/local/tomcat/webapps/ROOT.war", "acme/w"));

        assertThat(calls).containsExactly(
                List.of("image", "inspect", "--format", "{{.Id}}", "netis-task-7:abcdef1"),
                List.of("rm", "-f", "netis-artifact-7"),
                List.of("create", "--name", "netis-artifact-7", "--entrypoint", "true", "netis-task-7:abcdef1"),
                List.of("cp", "netis-artifact-7:/usr/local/tomcat/webapps/ROOT.war", calls.get(3).get(2)),
                List.of("rm", "-f", "netis-artifact-7"));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WAR));
        verify(http).uploadArtifact(eq(7L), eq("w-task-7-abcdef1.war"),
                eq("netis-task-7:abcdef1:/usr/local/tomcat/webapps/ROOT.war"), any(Path.class), eq(sha),
                eq(Duration.ofMinutes(10)));
        assertThat(p.reused()).isFalse();
        assertThat(tmp.resolve("close/task-7/artifact")).doesNotExist();
    }

    @Test
    void 경로가_없으면_find_결과가_하나일때_그것을_쓴다() throws Exception {
        findOut = "find: '/usr/local/tomcat/webapps': No such file or directory\n/app/netis.jar\n";
        publisher().publish(task(null, "micthebick84/netis-v7.0"));
        assertThat(calls.get(1)).containsExactly("run", "--rm", "--entrypoint", "find", "netis-task-7:abcdef1",
                "/usr/local/tomcat/webapps", "/app", "-maxdepth", "2", "-type", "f",
                "(", "-name", "*.war", "-o", "-name", "*.jar", ")");
        verify(http).uploadArtifact(eq(7L), eq("netis-v7.0-task-7-abcdef1.jar"), anyString(), any(), anyString(), any());
    }

    @Test
    void find_결과가_0개나_2개면_카탈로그_경로_지정을_안내한다() {
        findOut = "/app/a.jar\n/app/b.jar\n";
        assertThatThrownBy(() -> publisher().publish(task(null, "acme/w")))
                .isInstanceOf(ArtifactPublisher.ArtifactException.class)
                .hasMessageContaining("레포 카탈로그").hasMessageContaining("/app/a.jar, /app/b.jar");
        findOut = "exec: \"find\": executable file not found";
        assertThatThrownBy(() -> publisher().publish(task(null, "acme/w")))
                .hasMessageContaining("후보: 없음");
    }

    @Test
    void 이미지가_없으면_산출물_없이_마무리를_안내한다() {
        inspectExit = 1;
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .isInstanceOf(ArtifactPublisher.ArtifactException.class)
                .hasMessage("산출물: 이미지 없음(netis-task-7:abcdef1) — 재시도하거나 '산출물 없이 마무리'");
    }

    @Test
    void 꺼내기가_실패해도_임시_컨테이너는_지운다() {
        cpExit = 1;
        assertThatThrownBy(() -> publisher().publish(task("/app/x.war", "acme/w")))
                .hasMessageStartingWith("산출물: 이미지에서 꺼내기 실패(/app/x.war)");
        assertThat(calls.get(calls.size() - 1)).containsExactly("rm", "-f", "netis-artifact-7");
        assertThat(tmp.resolve("close/task-7/artifact")).doesNotExist();
    }

    @Test
    void war나_jar가_아닌_경로는_거절한다() {
        assertThatThrownBy(() -> publisher().publish(task("/app/x.ear", "acme/w")))
                .hasMessageContaining(".war 또는 .jar");
    }

    @Test
    void 파일_이름은_경로_마지막_조각을_정규화한다() {
        assertThat(ArtifactPublisher.fileName(task(null, "group/sub/my proj"), "/app/x.war"))
                .isEqualTo("my_proj-task-7-abcdef1.war");
        assertThat(ArtifactPublisher.candidates("x\n/a/b.war\n/a/b.war\n/c.txt\n")).containsExactly("/a/b.war");
    }
}
```

`WorkerHttpClientArtifactTest.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkerHttpClientArtifactTest {

    @TempDir Path tmp;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private WorkerHttpClient client(int status, String response, AtomicReference<String> seen) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/worker/tasks/7/artifact", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            seen.set(ex.getRequestMethod() + " " + ex.getRequestURI() + " key=" + ex.getRequestHeaders().getFirst("X-Worker-API-Key")
                    + " sha=" + ex.getRequestHeaders().getFirst("X-Artifact-Sha256") + " body=" + new String(body, StandardCharsets.UTF_8));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.apiBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(props.apiKey()).thenReturn("k1");
        when(props.id()).thenReturn("w1");
        return new WorkerHttpClient(props);
    }

    @Test
    void 파일을_원시_본문으로_올리고_응답을_읽는다() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkerHttpClient c = client(200, "{\"name\":\"w-task-7-abcdef1.war\",\"sizeBytes\":3,\"sha256\":\"ab\"}", seen);
        Path f = Files.writeString(tmp.resolve("x.war"), "WAR");

        ArtifactInfo info = c.uploadArtifact(7L, "w-task-7-abcdef1.war", "img:/app/x.war", f, "ab", Duration.ofSeconds(10));

        assertThat(info).isEqualTo(new ArtifactInfo("w-task-7-abcdef1.war", 3, "ab"));
        assertThat(seen.get()).startsWith("PUT /worker/tasks/7/artifact?workerId=w1&name=w-task-7-abcdef1.war&source=")
                .contains("key=k1").contains("sha=ab").endsWith("body=WAR");
    }

    @Test
    void 거절되면_상태와_본문으로_예외() throws Exception {
        WorkerHttpClient c = client(409, "{\"message\":\"종료중 상태가 아닙니다\"}", new AtomicReference<>());
        Path f = Files.writeString(tmp.resolve("x.war"), "WAR");
        assertThatThrownBy(() -> c.uploadArtifact(7L, "w-task-7-abcdef1.war", null, f, "ab", Duration.ofSeconds(10)))
                .hasMessageContaining("409").hasMessageContaining("종료중 상태가 아닙니다");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "ArtifactPublisherTest" --tests "WorkerHttpClientArtifactTest"`
Expected: 컴파일 실패 — `ArtifactPublisher`, `ArtifactProperties`, `uploadArtifact` 없음.

- [ ] **Step 3: 설정·이미지 이름**

`ArtifactProperties.java`:

```java
package com.hamonsoft.netismaker.workerdaemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 종료 산출물 꺼내기 설정 (스펙 2026-10-14 §4). CloseProperties와 분리 — 기존 생성자 호출부를 건드리지 않는다.
 *  search-dirs     카탈로그 경로가 없을 때 이미지 안에서 *.war·*.jar를 찾는 폴더(쉼표 구분)
 *  copy-timeout    docker cp 상한, upload-timeout API 업로드 상한
 */
@ConfigurationProperties(prefix = "netis-maker.worker.close.artifact")
public record ArtifactProperties(List<String> searchDirs, Duration copyTimeout, Duration uploadTimeout) {

    public ArtifactProperties {
        List<String> dirs = searchDirs == null ? List.of()
                : searchDirs.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        searchDirs = dirs.isEmpty() ? List.of("/usr/local/tomcat/webapps", "/app") : dirs;
        if (copyTimeout == null) copyTimeout = Duration.ofMinutes(10);
        if (uploadTimeout == null) uploadTimeout = Duration.ofMinutes(10);
    }
}
```

`NetisMakerApplication.java`의 `@EnableConfigurationProperties({...})` 목록에 `ArtifactProperties.class`를 더하고 `import com.hamonsoft.netismaker.workerdaemon.ArtifactProperties;`를 넣는다.

`application-worker.yml`의 `close:` 블록 마지막 줄 `note-timeout: ${CLOSE_NOTE_TIMEOUT:10m}` 다음에(같은 6칸 들여쓰기) 추가:

```yaml
      artifact:                      # 종료 산출물 꺼내기 (스펙 2026-10-14 §4)
        search-dirs: ${CLOSE_ARTIFACT_SEARCH_DIRS:/usr/local/tomcat/webapps,/app}
        copy-timeout: ${CLOSE_ARTIFACT_COPY_TIMEOUT:10m}
        upload-timeout: ${CLOSE_ARTIFACT_UPLOAD_TIMEOUT:10m}
```

`DeployService.java` — `undeploy` 위에 추가:

```java
    /** 배포 이미지 이름 netis-task-{id}:{head_sha 7자|latest} — 종료 산출물 단계(ArtifactPublisher)가 같은 이름을 쓴다. */
    public static String imageName(long taskId, String headSha) {
        String shortSha = headSha == null ? "latest" : headSha.substring(0, Math.min(7, headSha.length()));
        return "netis-task-" + taskId + ":" + shortSha;
    }
```

같은 파일 `deploy`의 `String shortSha = task.headSha() == null ? "latest" : task.headSha().substring(0, Math.min(7, task.headSha().length()));` 두 줄을 지우고, `DeploySpec` 생성의 `"netis-task-" + task.id() + ":" + shortSha,`를 `imageName(task.id(), task.headSha()),`로 바꾼다.

- [ ] **Step 4: `WorkerHttpClient`**

import 추가: `com.fasterxml.jackson.databind.ObjectMapper`, `com.hamonsoft.netismaker.dto.ArtifactInfo`, `org.springframework.web.util.UriComponentsBuilder`, `java.io.IOException`, `java.net.URI`, `java.net.http.HttpClient`, `java.net.http.HttpRequest`, `java.net.http.HttpResponse`, `java.nio.file.Path`, `java.time.Duration`. 필드 추가:

```java
    /** 산출물 업로드 전용 — RestClient는 본문을 버퍼링할 수 있어 254MB를 파일에서 바로 흘리려고 JDK HttpClient를 쓴다. */
    private final HttpClient uploader = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
```

클래스 끝에 추가:

```java
    /** 이미 보관된 종료 산출물. 404면 empty — 재시도에서 이미지를 다시 꺼내지 않으려고 먼저 묻는다 (스펙 2026-10-14 §4). */
    public Optional<ArtifactInfo> currentArtifact(Long taskId) {
        try {
            return Optional.ofNullable(http.get().uri("/worker/tasks/{id}/artifact", taskId)
                    .retrieve().body(ArtifactInfo.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** 종료 산출물 업로드 — PUT 원시 본문(octet-stream) + X-Artifact-Sha256. 2xx가 아니면 상태·본문으로 IOException. */
    public ArtifactInfo uploadArtifact(Long taskId, String name, String source, Path file, String sha256,
                                       Duration timeout) throws IOException, InterruptedException {
        URI uri = UriComponentsBuilder.fromUriString(props.apiBaseUrl())
                .path("/worker/tasks/{id}/artifact")
                .queryParam("workerId", props.id())
                .queryParam("name", name)
                .queryParam("source", source)
                .encode()
                .buildAndExpand(taskId)
                .toUri();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("X-Worker-API-Key", props.apiKey())
                .header("X-Artifact-Sha256", sha256)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        HttpResponse<String> res = uploader.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            String body = res.body() == null ? "" : res.body();
            throw new IOException("산출물 업로드 거부(" + res.statusCode() + "): "
                    + com.hamonsoft.netismaker.git.GitRemotes.mask(body.length() > 300 ? body.substring(0, 300) : body));
        }
        return mapper.readValue(res.body(), ArtifactInfo.class);
    }
```

(`source`가 null이면 `queryParam("source", (Object) null)`은 `source` 키만 붙는다 — 서버는 빈 값을 null처럼 다룬다. 테스트는 `startsWith(...&source=)`로 확인한다.)

- [ ] **Step 5: `ArtifactPublisher`**

```java
package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.ArtifactInfo;
import com.hamonsoft.netismaker.dto.CloseArtifactSpec;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 종료 산출물 꺼내기·업로드 (스펙 2026-10-14 §4). 배포 이미지에서 docker create/cp로 WAR·JAR를 꺼내
 * API에 올린다. docker는 워커 프로세스의 DOCKER_HOST(원격 데몬)를 물려받아 파일이 원격에서 워커 PC로 복사된다.
 */
@Component
@Profile("worker")
@Slf4j
public class ArtifactPublisher {

    /** docker CLI 실행 seam(테스트용). args에 "docker"는 넣지 않는다. */
    interface Docker {
        ProcessRunner.Result run(List<String> args, long timeoutSeconds) throws IOException, InterruptedException;
    }

    /** 사람이 읽는 실패 — 메시지가 그대로 종료실패 사유가 된다. */
    public static final class ArtifactException extends Exception {
        public ArtifactException(String message) {
            super(message);
        }
    }

    public record Published(ArtifactInfo info, boolean reused) {}

    private static final long SHORT_TIMEOUT_SECONDS = 120;

    private final WorkerProperties props;
    private final ArtifactProperties cfg;
    private final WorkerHttpClient http;
    private final Docker docker;

    @Autowired
    public ArtifactPublisher(WorkerProperties props, ArtifactProperties cfg, WorkerHttpClient http) {
        this(props, cfg, http, (args, timeout) -> {
            List<String> cmd = new ArrayList<>();
            cmd.add("docker");
            cmd.addAll(args);
            return ProcessRunner.run(null, cmd, timeout);
        });
    }

    ArtifactPublisher(WorkerProperties props, ArtifactProperties cfg, WorkerHttpClient http, Docker docker) {
        this.props = props;
        this.cfg = cfg;
        this.http = http;
        this.docker = docker;
    }

    public Published publish(WorkerTaskResponse task) throws ArtifactException, InterruptedException {
        Optional<ArtifactInfo> existing;
        try {
            existing = http.currentArtifact(task.id());
        } catch (RuntimeException e) {
            throw new ArtifactException("산출물: 보관 여부 조회 실패: " + GitRemotes.mask(String.valueOf(e.getMessage())));
        }
        if (existing.isPresent()) return new Published(existing.get(), true);   // 업로드 뒤 진행 보고 전에 죽은 재시도

        String image = DeployService.imageName(task.id(), task.headSha());
        inspect(image);
        String path = resolvePath(task, image);
        String name = fileName(task, path);
        Path work = Paths.get(props.reposDir()).resolveSibling("close").resolve("task-" + task.id()).resolve("artifact");
        try {
            Files.createDirectories(work);
            Path local = work.resolve(name);
            copyOut(task.id(), image, path, local);
            String sha = sha256(local);
            ArtifactInfo info = http.uploadArtifact(task.id(), name, image + ":" + path, local, sha, cfg.uploadTimeout());
            return new Published(info, false);
        } catch (IOException e) {
            throw new ArtifactException("산출물: " + GitRemotes.mask(String.valueOf(e.getMessage())));
        } finally {
            RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(work);
            if (d.failures() > 0) log.warn("산출물 작업 폴더 정리 실패 {}건: {}", d.failures(), work);
        }
    }

    private void inspect(String image) throws ArtifactException, InterruptedException {
        ProcessRunner.Result r = run(List.of("image", "inspect", "--format", "{{.Id}}", image), SHORT_TIMEOUT_SECONDS);
        if (r.exitCode() != 0) {
            throw new ArtifactException("산출물: 이미지 없음(" + image + ") — 재시도하거나 '산출물 없이 마무리'");
        }
    }

    private String resolvePath(WorkerTaskResponse task, String image) throws ArtifactException, InterruptedException {
        CloseArtifactSpec spec = task.closeArtifact();
        String path;
        if (spec != null && spec.path() != null && !spec.path().isBlank()) {
            path = spec.path().trim();
        } else {
            List<String> args = new ArrayList<>(List.of("run", "--rm", "--entrypoint", "find", image));
            args.addAll(cfg.searchDirs());
            args.addAll(List.of("-maxdepth", "2", "-type", "f", "(", "-name", "*.war", "-o", "-name", "*.jar", ")"));
            List<String> found = candidates(run(args, SHORT_TIMEOUT_SECONDS).stdout());
            if (found.size() != 1) {
                throw new ArtifactException("산출물: 경로를 정할 수 없습니다 — 레포 카탈로그에 이미지 안 산출물 경로를 지정하세요(후보: "
                        + (found.isEmpty() ? "없음" : String.join(", ", found)) + ")");
            }
            path = found.get(0);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".war") && !lower.endsWith(".jar")) {
            throw new ArtifactException("산출물: .war 또는 .jar만 보관합니다: " + path);
        }
        return path;
    }

    private void copyOut(long taskId, String image, String path, Path local) throws ArtifactException, InterruptedException {
        String box = "netis-artifact-" + taskId;
        run(List.of("rm", "-f", box), SHORT_TIMEOUT_SECONDS);   // 지난 시도의 잔여 — 없으면 실패하지만 무시
        try {
            ProcessRunner.Result c = run(List.of("create", "--name", box, "--entrypoint", "true", image), SHORT_TIMEOUT_SECONDS);
            if (c.exitCode() != 0) throw new ArtifactException("산출물: 임시 컨테이너 생성 실패: " + tail(c.stdout()));
            ProcessRunner.Result cp = run(List.of("cp", box + ":" + path, local.toString()), cfg.copyTimeout().toSeconds());
            if (cp.exitCode() != 0 || !Files.isRegularFile(local)) {
                throw new ArtifactException("산출물: 이미지에서 꺼내기 실패(" + path + "): " + tail(cp.stdout()));
            }
        } finally {
            try {
                run(List.of("rm", "-f", box), SHORT_TIMEOUT_SECONDS);
            } catch (ArtifactException e) {
                log.warn("임시 컨테이너 정리 실패: {} {}", box, e.getMessage());
            }
        }
    }

    private ProcessRunner.Result run(List<String> args, long timeoutSeconds) throws ArtifactException, InterruptedException {
        try {
            return docker.run(args, timeoutSeconds);
        } catch (IOException e) {
            throw new ArtifactException("산출물: docker " + args.get(0) + " 실패: " + GitRemotes.mask(String.valueOf(e.getMessage())));
        }
    }

    /** {레포 경로 마지막 조각}-task-{id}-{sha7}.{war|jar}, 허용 밖 문자는 '_' (API ArtifactStore.validName과 같은 규칙). */
    static String fileName(WorkerTaskResponse task, String path) {
        String repo = task.repoRef().path();
        String last = repo.substring(repo.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (last.isBlank() || last.startsWith(".")) last = "repo" + last;
        if (last.length() > 100) last = last.substring(0, 100);
        String sha = task.headSha() == null ? "latest" : task.headSha().substring(0, Math.min(7, task.headSha().length()));
        String ext = path.toLowerCase(Locale.ROOT).endsWith(".jar") ? "jar" : "war";
        return last + "-task-" + task.id() + "-" + sha + "." + ext;
    }

    /** find 출력에서 산출물 경로 줄만 — 없는 폴더 오류("find: ...")·빈 줄은 버린다. 종료 코드는 보지 않는다. */
    static List<String> candidates(String stdout) {
        if (stdout == null) return List.of();
        return stdout.lines().map(String::strip)
                .filter(l -> l.startsWith("/") && (l.endsWith(".war") || l.endsWith(".jar")))
                .distinct().toList();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        String m = GitRemotes.mask(s.strip());
        return m.length() <= 300 ? m : "…" + m.substring(m.length() - 300);
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "ArtifactPublisherTest" --tests "WorkerHttpClientArtifactTest" --tests "DeployService*" --tests "WorkerHttpClient*"`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactProperties.java src/main/java/com/hamonsoft/netismaker/NetisMakerApplication.java src/main/resources/application-worker.yml src/main/java/com/hamonsoft/netismaker/workerdaemon/DeployService.java src/main/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClient.java src/main/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisher.java src/test/java/com/hamonsoft/netismaker/workerdaemon/ArtifactPublisherTest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/WorkerHttpClientArtifactTest.java
git commit -m "feat(worker): 배포 이미지에서 산출물을 꺼내 API로 스트리밍 업로드

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 워커 — 종료 단계에 산출물 연결, 노트에 산출물 줄

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/CloseNoteWriter.java` (`NoteMeta`, `write`, `frontmatter`)
- Modify: `src/main/java/com/hamonsoft/netismaker/workerdaemon/TaskCloser.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/CloseNoteWriterTest.java`, `src/test/java/com/hamonsoft/netismaker/workerdaemon/TaskCloserTest.java`

**Interfaces:**
- Consumes: Task 10 `ArtifactPublisher`, Task 5 `CloseContext.artifactName/artifactSizeBytes/artifactSkipped/taskUrl`, `WorkerTaskResponse.closeArtifact()`.
- Produces: `CloseNoteWriter.NoteMeta(long taskId, String title, String prUrl, String mergeCommitSha, String artifact, String artifactLine)` + 4인자 호환 생성자. `TaskCloser`의 package-private 11인자 생성자 `(…, CloseNoteWriter noteWriter, ArtifactPublisher artifacts, Clock clock)`(기존 10인자 판은 `artifacts = null`로 위임).

- [ ] **Step 1: 실패하는 테스트 작성**

`CloseNoteWriterTest.java` 끝에 추가:

```java
    @Test
    void 산출물이_있으면_프런트매터와_본문_끝에_남긴다() throws Exception {
        CloseNoteWriter.NoteMeta m = new CloseNoteWriter.NoteMeta(12, "제목", null, "abcdef1234",
                "w-task-12-abcdef1.war", "산출물: w-task-12-abcdef1.war (253.7 MB) — Symphony 작업 화면에서 로그인 후 내려받기: https://a.test/tasks/12");
        CloseNoteWriter.Written w = writer.write(vault, "N", m, "# 본문", day);
        String text = Files.readString(w.file());
        assertThat(text).contains("merge_commit: abcdef1234\nartifact: w-task-12-abcdef1.war\n")
                .endsWith("# 본문\n\n산출물: w-task-12-abcdef1.war (253.7 MB) — Symphony 작업 화면에서 로그인 후 내려받기: https://a.test/tasks/12\n");
    }
```

`TaskCloserTest.java`:
- import 추가: `com.hamonsoft.netismaker.dto.ArtifactInfo`, `com.hamonsoft.netismaker.dto.CloseArtifactSpec`.
- 필드 추가: `private ArtifactPublisher pub;`
- 클래스 끝에 추가:

```java
    private WorkerTaskResponse artifactTask(String progress, boolean skipped) {
        CloseContext ctx = new CloseContext(null, null, null, null, "http://h:1", null, List.of(), List.of(),
                null, null, skipped, "https://a.test/tasks/7");
        return new WorkerTaskResponse(7L, "acme/w", "main", "로그인 수정", "d", WorkerTaskResponse.Kind.CLOSE,
                List.of(), null, null, "netismaker/task-7", SHA, List.of(), null, null, null, null, null, null,
                null, null, null, "github", null, 3, "https://github.com/acme/w/pull/3", progress, ctx,
                skipped ? null : new CloseArtifactSpec(null));
    }

    private TaskCloser closerWithPublisher() throws Exception {
        pub = mock(ArtifactPublisher.class);
        when(pub.publish(any())).thenReturn(new ArtifactPublisher.Published(
                new ArtifactInfo("w-task-7-abcdef1.war", 2L * 1024 * 1024, "ab".repeat(32)), false));
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.id()).thenReturn("w1");
        when(props.reposDir()).thenReturn(tmp.resolve("repos").toString());
        CloseProperties cp = new CloseProperties(tmp.resolve("vault").toString(), "HamonSoft/netisMaker", "merge",
                "sonnet", Duration.ofMinutes(1), null);
        return new TaskCloser(props, cp, http, deploy, worktrees, claude, reporter, List.of(mr), new CloseNoteWriter(),
                pub, Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 산출물_모드면_다섯_단계를_순서대로_하고_노트에_산출물을_남긴다() throws Exception {
        closerWithPublisher().process(artifactTask(null, false));

        assertThat(reportedSteps()).containsExactly(CloseStep.MERGED, CloseStep.UNDEPLOYED,
                CloseStep.ARTIFACT_UPLOADED, CloseStep.NOTE_WRITTEN, CloseStep.BRANCH_DELETED);
        assertThat(terminal().status()).isEqualTo(TaskStatus.CLOSED);
        String note = Files.readString(tmp.resolve("vault/HamonSoft/netisMaker/로그인-수정-작업7-2026-10-13.md"));
        assertThat(note).contains("artifact: w-task-7-abcdef1.war")
                .contains("산출물: w-task-7-abcdef1.war (2.0 MB) — Symphony 작업 화면에서 로그인 후 내려받기: https://a.test/tasks/7");
    }

    @Test
    void 산출물이_실패하면_노트를_쓰지_않고_종료실패() throws Exception {
        TaskCloser c = closerWithPublisher();
        when(pub.publish(any())).thenThrow(new ArtifactPublisher.ArtifactException(
                "산출물: 이미지 없음(netis-task-7:abcdef1) — 재시도하거나 '산출물 없이 마무리'"));

        c.process(artifactTask(null, false));

        assertThat(reportedSteps()).containsExactly(CloseStep.MERGED, CloseStep.UNDEPLOYED);
        verify(claude, never()).execWithoutTools(any(), any(), any(), any());
        WorkerResultRequest r = terminal();
        assertThat(r.status()).isEqualTo(TaskStatus.CLOSE_FAILED);
        assertThat(r.failureReason()).startsWith("산출물: 이미지 없음");
    }

    @Test
    void 산출물_지시가_없으면_업로더를_부르지_않고_건너뛴_작업은_노트에_없음을_쓴다() throws Exception {
        closerWithPublisher().process(artifactTask("MERGED,UNDEPLOYED", true));

        verify(pub, never()).publish(any());
        assertThat(reportedSteps()).containsExactly(CloseStep.NOTE_WRITTEN, CloseStep.BRANCH_DELETED);
        String note = Files.readString(tmp.resolve("vault/HamonSoft/netisMaker/로그인-수정-작업7-2026-10-13.md"));
        assertThat(note).contains("artifact: 없음(관리자 건너뜀)").contains("산출물: 없음(관리자가 산출물 없이 마무리)");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseNoteWriterTest" --tests "TaskCloserTest"`
Expected: 컴파일 실패 — 6인자 `NoteMeta`, 11인자 `TaskCloser` 없음.

- [ ] **Step 3: `CloseNoteWriter`**

`NoteMeta`를 바꾼다:

```java
    /**
     * artifact = 프런트매터 값(파일 이름 또는 '없음(관리자 건너뜀)'), artifactLine = 본문 끝 한 줄. 둘 다 null이면 산출물 표시 없음.
     */
    public record NoteMeta(long taskId, String title, String prUrl, String mergeCommitSha,
                           String artifact, String artifactLine) {
        /** 산출물 정보 도입 전 4개 인자 — 기존 호출부·테스트 호환. */
        public NoteMeta(long taskId, String title, String prUrl, String mergeCommitSha) {
            this(taskId, title, prUrl, mergeCommitSha, null, null);
        }
    }
```

`write`의 `Files.writeString(file, frontmatter(meta, date) + clean(body) + "\n", StandardCharsets.UTF_8);`를 아래로 바꾼다:

```java
        String tail = meta.artifactLine() == null ? "" : "\n\n" + maskSecrets(meta.artifactLine());
        Files.writeString(file, frontmatter(meta, date) + clean(body) + tail + "\n", StandardCharsets.UTF_8);
```

`frontmatter`의 `+ "merge_commit: " + … + "\n"` 줄 다음에 추가:

```java
                + (m.artifact() == null ? "" : "artifact: " + m.artifact() + "\n")
```

- [ ] **Step 4: `TaskCloser`**

import 추가: `com.hamonsoft.netismaker.dto.ArtifactInfo`, `com.hamonsoft.netismaker.dto.CloseContext`, `java.util.Locale`.

클래스 주석 첫 줄들을 아래로 바꾼다:

```java
/**
 * 작업 종료(kind=CLOSE) 오케스트레이션 (스펙 2026-10-13 §4, 2026-10-14 §4).
 *   ① 병합 → ② 배포 중지 → [③ 산출물 업로드 — claim에 closeArtifact가 있을 때만] → ④ 옵시디언 노트
 *   → ⑤ 원격 브랜치 삭제 (+ 로컬 worktree 정리, 실패는 경고만)
```

필드에 `private final ArtifactPublisher artifacts;`를 더하고 생성자 셋을 아래로 바꾼다:

```java
    @Autowired
    public TaskCloser(WorkerProperties props, CloseProperties closeProps, WorkerHttpClient http,
                      DeployService deployService, WorktreeService worktrees, ClaudeExecAdapter claude,
                      ResultReporter reporter, ArtifactPublisher artifacts) {
        this(props, closeProps, http, deployService, worktrees, claude, reporter,
                List.of(new GitHubPrCloser(props.githubPat()), new GitLabMrCloser(props.gitlabToken())),
                new CloseNoteWriter(), artifacts, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    /** 산출물 업로더 없는 테스트용 — closeArtifact가 있는 claim은 산출물 단계에서 종료실패한다. */
    TaskCloser(WorkerProperties props, CloseProperties closeProps, WorkerHttpClient http,
               DeployService deployService, WorktreeService worktrees, ClaudeExecAdapter claude,
               ResultReporter reporter, List<MergeRequestCloser> closers, CloseNoteWriter noteWriter, Clock clock) {
        this(props, closeProps, http, deployService, worktrees, claude, reporter, closers, noteWriter, null, clock);
    }

    TaskCloser(WorkerProperties props, CloseProperties closeProps, WorkerHttpClient http,
               DeployService deployService, WorktreeService worktrees, ClaudeExecAdapter claude,
               ResultReporter reporter, List<MergeRequestCloser> closers, CloseNoteWriter noteWriter,
               ArtifactPublisher artifacts, Clock clock) {
        this.props = props;
        this.closeProps = closeProps;
        this.http = http;
        this.deployService = deployService;
        this.worktrees = worktrees;
        this.claude = claude;
        this.reporter = reporter;
        this.closers = closers;
        this.noteWriter = noteWriter;
        this.artifacts = artifacts;
        this.clock = clock;
    }
```

`process`에서 `UNDEPLOYED` 블록 다음, 노트 블록 앞에 추가하고 노트 호출을 바꾼다:

```java
            ArtifactInfo uploaded = null;
            if (task.closeArtifact() != null && !done.contains(CloseStep.ARTIFACT_UPLOADED)) {
                uploaded = publishArtifact(task);
                progress(task, CloseStep.ARTIFACT_UPLOADED, null, null);
            }
            if (!done.contains(CloseStep.NOTE_WRITTEN)) {
                NoteOutcome n = writeNote(task, mergeSha, uploaded);
                usage = n.usage();
                progress(task, CloseStep.NOTE_WRITTEN, null, n.relativePath());
            }
```

(기존 `if (!done.contains(CloseStep.NOTE_WRITTEN)) { NoteOutcome n = writeNote(task, mergeSha); … }` 블록을 위 블록으로 대체한다.)

`writeNote` 시그니처를 `private NoteOutcome writeNote(WorkerTaskResponse task, String mergeSha, ArtifactInfo uploaded) throws StepFailed {`로 바꾸고, 그 안의 `new CloseNoteWriter.NoteMeta(task.id(), task.title(), task.prUrl(), mergeSha)`를 아래로 바꾼다:

```java
                    noteMeta(task, mergeSha, uploaded)
```

`writeNote` 다음에 추가:

```java
    /** 노트 메타 — 산출물은 이번에 올렸으면 그 값, 아니면 claim의 기록, 건너뛴 작업이면 '없음'. */
    static CloseNoteWriter.NoteMeta noteMeta(WorkerTaskResponse task, String mergeSha, ArtifactInfo uploaded) {
        CloseContext ctx = task.closeContext();
        String artifact = null;
        String line = null;
        if (ctx != null && ctx.artifactSkipped()) {
            artifact = "없음(관리자 건너뜀)";
            line = "산출물: 없음(관리자가 산출물 없이 마무리)";
        } else {
            String name = uploaded != null ? uploaded.name() : ctx == null ? null : ctx.artifactName();
            if (name != null) {
                Long size = uploaded != null ? Long.valueOf(uploaded.sizeBytes()) : ctx.artifactSizeBytes();
                String url = ctx == null ? null : ctx.taskUrl();
                artifact = name;
                line = "산출물: " + name
                        + (size == null ? "" : String.format(Locale.ROOT, " (%.1f MB)", size / 1024.0 / 1024.0))
                        + (url == null ? "" : " — Symphony 작업 화면에서 로그인 후 내려받기: " + url);
            }
        }
        return new CloseNoteWriter.NoteMeta(task.id(), task.title(), task.prUrl(), mergeSha, artifact, line);
    }

    private ArtifactInfo publishArtifact(WorkerTaskResponse task) throws StepFailed {
        if (artifacts == null) throw new StepFailed("산출물: 워커에 산출물 업로더가 없습니다");
        try {
            ArtifactPublisher.Published p = artifacts.publish(task);
            log.info("산출물 {}: task={} {} {}B", p.reused() ? "이미 보관됨" : "보관", task.id(),
                    p.info().name(), p.info().sizeBytes());
            return p.info();
        } catch (ArtifactPublisher.ArtifactException e) {
            throw new StepFailed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailed("산출물 중단됨");
        }
    }
```

- [ ] **Step 5: 통과 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests "CloseNoteWriterTest" --tests "TaskCloserTest" --tests "WorkerMainLoop*"`
Expected: PASS(기존 종료 테스트 포함).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/workerdaemon/CloseNoteWriter.java src/main/java/com/hamonsoft/netismaker/workerdaemon/TaskCloser.java src/test/java/com/hamonsoft/netismaker/workerdaemon/CloseNoteWriterTest.java src/test/java/com/hamonsoft/netismaker/workerdaemon/TaskCloserTest.java
git commit -m "feat(worker): 종료 단계에 산출물 업로드 연결, 노트에 산출물 줄

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 통합 테스트 — 산출물 흐름 전체 (CI)

**Files:**
- Create: `src/test/java/com/hamonsoft/netismaker/controller/CloseArtifactFlowIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1~9의 API 전부.

- [ ] **Step 1: 테스트 작성**

```java
package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.WorkerResultRequest;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 종료 산출물 흐름 통합 테스트 (스펙 2026-10-14). Testcontainers — RUN_TESTCONTAINERS=true 일 때만.
 * SMTP는 127.0.0.1:1(접속 거부)이라 메일은 FAILED가 되어야 하고, 그래도 작업종료와 /actuator/health는 정상이어야 한다.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = {
        "app.close.artifact.enabled=true",
        "app.public-base-url=https://app.test",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1",
        "spring.mail.username=bot@hamonsoft.co.kr"
})
class CloseArtifactFlowIntegrationTest {

    private static final Path ATTACHMENTS;
    static {
        try {
            ATTACHMENTS = Files.createTempDirectory("close-artifact-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.attachment.dir", ATTACHMENTS::toString);   // 운영자 실제 첨부 루트를 건드리지 않는다
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private RepoCatalogRepository catalogRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor user(String id, String email) {
        return jwt().jwt(b -> {
                    b.claim("username", id).claim("authorities", List.of("ROLE_USER"));
                    if (email != null) b.claim("email", email);
                })
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private void progress(Long id, String step) throws Exception {
        mvc.perform(post("/worker/tasks/" + id + "/close-progress").header("X-Worker-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"w1\",\"step\":\"" + step + "\"}"))
                .andExpect(status().isNoContent());
    }

    private Task awaitMail(Long id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Task t = taskRepo.findById(id).orElseThrow();
            if (t.getCloseMailStatus() != null) return t;
            Thread.sleep(100);
        }
        throw new AssertionError("메일 결과가 기록되지 않았다");
    }

    @Test
    void 승인부터_업로드_내려받기_메일까지() throws Exception {
        long nano = System.nanoTime();
        RepoCatalogEntry cat = RepoCatalogEntry.create("산출물-" + nano, "https://github.com/owner/repo-" + nano + ".git",
                "github", "owner/repo-" + nano, null, null, "admin");
        cat.setArtifactEnabled(true);
        cat.setArtifactPath("/usr/local/tomcat/webapps/ROOT.war");
        cat = catalogRepo.save(cat);

        Task t = Task.create("owner/repo-" + nano, "main", "산출물 흐름", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.DEPLOYED);
        t.setPrNumber(3);
        t.setPrUrl("https://github.com/owner/repo/pull/3");
        t.setHeadBranch("netismaker/task-x");
        t.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        t.setRepoCatalogId(cat.getId());
        Long id = taskRepo.save(t).getId();

        mvc.perform(post("/api/tasks/" + id + "/close-request").with(user("user1", "user1@hamonsoft.co.kr")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/close/approve").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.close.artifactMode").value("REQUIRED"));

        String claim = mvc.perform(get("/worker/next-task").param("workerId", "w1").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeArtifact.path").value("/usr/local/tomcat/webapps/ROOT.war"))
                .andExpect(jsonPath("$.closeContext.taskUrl").value("https://app.test/tasks/" + id))
                .andReturn().getResponse().getContentAsString();
        assertThat(claim).contains("\"kind\":\"CLOSE\"");

        progress(id, "MERGED");
        progress(id, "UNDEPLOYED");
        mvc.perform(post("/worker/tasks/" + id + "/close-progress").header("X-Worker-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"w1\",\"step\":\"ARTIFACT_UPLOADED\"}"))
                .andExpect(status().isBadRequest());   // 파일 없이 진행 보고 거절

        byte[] war = "WAR-BYTES-FOR-IT".getBytes();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(war));
        String name = "repo-task-" + id + "-0123456.war";
        mvc.perform(put("/worker/tasks/" + id + "/artifact").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "w1").param("name", name).param("source", "netis-task-" + id + ":0123456:/x.war")
                        .header("X-Artifact-Sha256", sha).contentType(MediaType.APPLICATION_OCTET_STREAM).content(war))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeBytes").value(war.length));
        mvc.perform(get("/worker/tasks/" + id + "/artifact").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));

        progress(id, "ARTIFACT_UPLOADED");
        progress(id, "NOTE_WRITTEN");
        progress(id, "BRANCH_DELETED");
        mvc.perform(post("/worker/tasks/" + id + "/result").header("X-Worker-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(WorkerResultRequest.closed("w1", null))))
                .andExpect(status().isNoContent());

        // 내려받기 — 다른 사용자 403, 등록자는 1회용 주소, 두 번째는 404, 인증 없이 열린다
        mvc.perform(post("/api/tasks/" + id + "/artifact/download-link").with(user("user2", null)))
                .andExpect(status().isForbidden());
        String body = mvc.perform(post("/api/tasks/" + id + "/artifact/download-link").with(user("user1", null)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode link = mapper.readTree(body);
        assertThat(link.get("expiresInSec").asLong()).isEqualTo(60);
        String url = link.get("url").asText();
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(name)))
                .andExpect(content().bytes(war));
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get("/api/artifact-downloads/nope")).andExpect(status().isNotFound());

        // 메일 — 접속 거부라 FAILED, 작업종료는 유지, health는 UP
        Task done = awaitMail(id);
        assertThat(done.getStatus()).isEqualTo(TaskStatus.CLOSED);
        assertThat(done.getCloseMailStatus()).isEqualTo("FAILED");
        assertThat(done.getRequesterEmail()).isEqualTo("user1@hamonsoft.co.kr");
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void 산출물_단계에서_멈추면_산출물_없이_마무리로_종료대기가_된다() throws Exception {
        Task t = Task.create("owner/repo", "main", "건너뛰기", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.CLOSE_FAILED);
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED,UNDEPLOYED");
        t.setFailureReason("산출물: 이미지 없음");
        Long id = taskRepo.save(t).getId();

        mvc.perform(post("/api/tasks/" + id + "/close/skip-artifact").with(user("user1", null)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/" + id + "/close/skip-artifact").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"이미지 수동 삭제\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSE_PENDING"))
                .andExpect(jsonPath("$.close.artifactMode").value("SKIPPED"));
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL. (이 PC에서는 `RUN_TESTCONTAINERS`가 없어 테스트가 skip된다 — CI에서 돈다.)

- [ ] **Step 3: 커밋**

```bash
git add src/test/java/com/hamonsoft/netismaker/controller/CloseArtifactFlowIntegrationTest.java
git commit -m "test(close): 산출물 업로드·1회용 내려받기·메일 실패·건너뛰기 통합 테스트

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: 프론트 — 종료 단계·산출물 표시 규칙

**Files:**
- Modify: `frontend/composables/taskClose.ts`
- Test: `frontend/composables/taskClose.spec.ts`

**Interfaces:**
- Produces: `CloseView`에 선택 필드 `artifactMode?: 'REQUIRED' | 'SKIPPED' | null`, `artifact?: CloseArtifactView | null`, `mail?: CloseMailView | null`. `CloseStepState`에 `'skipped'`. `CLOSE_STEPS`에 `ARTIFACT_UPLOADED`. `canSkipArtifact(close, status)`, `formatMb(bytes)`, `MAIL_STATUS_LABEL`, `startArtifactDownload(taskId, api, go?)`.

- [ ] **Step 0: node_modules 정션 (한 번만)**

PowerShell:

```powershell
New-Item -ItemType Junction -Path "C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\close-artifact-delivery\frontend\node_modules" -Target "C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules"
```

(worktree를 지우기 전에 `cmd //c "rmdir frontend\\node_modules"`로 링크만 먼저 지운다.)

- [ ] **Step 1: 실패하는 테스트 작성**

`taskClose.spec.ts`의 import를 `import { closeSteps, obsidianLink, canSkipArtifact, formatMb, startArtifactDownload, MAIL_STATUS_LABEL } from './taskClose'`로 바꾸고 끝에 추가:

```ts
describe('closeSteps — 산출물 모드 (스펙 2026-10-14)', () => {
  const base = { mergeCommitSha: null, notePath: null }
  it('모드가 없으면 기존 4단계', () => {
    expect(closeSteps({ ...base, progress: [], artifactMode: null }, 'CLOSE_REVIEW').map((s) => s.key))
      .toEqual(['MERGED', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED'])
  })
  it('REQUIRED면 배포 중지와 노트 사이에 산출물 업로드가 있고, 거기서 멈추면 failed', () => {
    const s = closeSteps({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')
    expect(s.map((x) => x.key)).toEqual(['MERGED', 'UNDEPLOYED', 'ARTIFACT_UPLOADED', 'NOTE_WRITTEN', 'BRANCH_DELETED'])
    expect(s[2]!.label).toBe('산출물 업로드')
    expect(s[2]!.state).toBe('failed')
  })
  it('SKIPPED면 산출물 단계는 건너뜀이고 다음 단계가 current', () => {
    const s = closeSteps({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'SKIPPED' }, 'CLOSING')
    expect(s[2]!.state).toBe('skipped')
    expect(s[3]!.state).toBe('current')
  })
})

describe('canSkipArtifact', () => {
  const base = { mergeCommitSha: null, notePath: null }
  it('산출물 단계에서 멈춘 종료실패만', () => {
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')).toBe(true)
    expect(canSkipArtifact({ ...base, progress: ['MERGED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')).toBe(false)
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED', 'ARTIFACT_UPLOADED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')).toBe(false)
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: null }, 'CLOSE_FAILED')).toBe(false)
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'REQUIRED' }, 'CLOSING')).toBe(false)
    expect(canSkipArtifact(null, 'CLOSE_FAILED')).toBe(false)
  })
})

describe('산출물 표시·내려받기', () => {
  it('크기는 MB 소수 1자리, 메일 상태는 한국어', () => {
    expect(formatMb(265_911_624)).toBe('253.6 MB')
    expect(MAIL_STATUS_LABEL.FAILED).toBe('메일 실패')
  })
  it('download-link를 POST하고 받은 주소로 이동한다', async () => {
    const api = vi.fn().mockResolvedValue({ url: '/api/artifact-downloads/tok' })
    const go = vi.fn()
    await startArtifactDownload(42, api, go)
    expect(api).toHaveBeenCalledWith('/api/tasks/42/artifact/download-link', { method: 'POST' })
    expect(go).toHaveBeenCalledWith('/api/artifact-downloads/tok')
  })
})
```

파일 첫 줄 import를 `import { describe, it, expect, vi } from 'vitest'`로 바꾼다.

- [ ] **Step 2: 실패 확인**

Run(frontend): `npx vitest run composables/taskClose.spec.ts`
Expected: FAIL — `canSkipArtifact` 등 export 없음.

- [ ] **Step 3: 구현**

`taskClose.ts`의 `CloseView`부터 `closeSteps` 끝까지를 아래로 바꾼다(`obsidianLink`는 그대로):

```ts
// 작업 종료 카드 표시 규칙 (스펙 2026-10-13 §6, 2026-10-14 §9). 단계 이름은 서버 CloseStep과 같다.

export interface CloseArtifactView {
  name: string
  sizeBytes: number
  sha256: string
  uploadedAt: string | null
}

export interface CloseMailView {
  status: 'SENT' | 'FAILED' | 'NO_ADDRESS' | 'DISABLED'
  at: string | null
}

export interface CloseView {
  progress: string[]
  mergeCommitSha: string | null
  notePath: string | null
  artifactMode?: 'REQUIRED' | 'SKIPPED' | null
  artifact?: CloseArtifactView | null
  mail?: CloseMailView | null
}

export type CloseStepState = 'done' | 'current' | 'failed' | 'future' | 'skipped'

export const CLOSE_STEPS = [
  { key: 'MERGED', label: 'PR 병합' },
  { key: 'UNDEPLOYED', label: '배포 중지' },
  { key: 'ARTIFACT_UPLOADED', label: '산출물 업로드' },
  { key: 'NOTE_WRITTEN', label: '옵시디언 노트' },
  { key: 'BRANCH_DELETED', label: '브랜치 삭제' },
]

/** 산출물 단계는 모드가 있을 때만 보이고, SKIPPED면 '건너뜀'이다. */
export function closeSteps(close: CloseView | null, status: string) {
  const mode = close?.artifactMode ?? null
  const steps = CLOSE_STEPS.filter((s) => s.key !== 'ARTIFACT_UPLOADED' || mode !== null)
  const done = new Set(close?.progress ?? [])
  const skipped = (key: string) => key === 'ARTIFACT_UPLOADED' && mode === 'SKIPPED'
  const nextIdx = steps.findIndex((s) => !done.has(s.key) && !skipped(s.key))
  return steps.map((s, i) => {
    let state: CloseStepState = 'future'
    if (done.has(s.key)) state = 'done'
    else if (skipped(s.key)) state = 'skipped'
    else if (i === nextIdx && status === 'CLOSING') state = 'current'
    else if (i === nextIdx && status === 'CLOSE_FAILED') state = 'failed'
    return { ...s, state }
  })
}

/** 산출물 단계에서 멈춘 종료실패인가 — 서버 TaskCloseService.skipArtifact와 같은 조건. */
export function canSkipArtifact(close: CloseView | null, status: string): boolean {
  if (!close || status !== 'CLOSE_FAILED' || close.artifactMode !== 'REQUIRED') return false
  const done = new Set(close.progress)
  return done.has('MERGED') && done.has('UNDEPLOYED') && !done.has('ARTIFACT_UPLOADED')
}

export const MAIL_STATUS_LABEL: Record<string, string> = {
  SENT: '메일 보냄',
  FAILED: '메일 실패',
  NO_ADDRESS: '수신 주소 없음',
  DISABLED: '메일 설정 없음',
}

export function formatMb(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 1회용 내려받기 주소를 받아 브라우저가 바로 저장하게 한다 — 254MB를 blob으로 메모리에 올리지 않는다(스펙 2026-10-14 §6).
 * 서버가 주는 '/api/…' 상대 경로를 그대로 쓴다(useInterviewStream과 같은 /api 관례). go는 테스트 seam.
 */
export async function startArtifactDownload(
  taskId: number | string,
  api: (url: string, opts: { method: 'POST' }) => Promise<{ url: string }>,
  go: (url: string) => void = (u) => window.location.assign(u),
) {
  const res = await api(`/api/tasks/${taskId}/artifact/download-link`, { method: 'POST' })
  go(res.url)
}
```

(파일 첫 줄의 기존 주석 `// 작업 종료 카드 표시 규칙 (스펙 2026-10-13 §6). …`은 위 주석으로 바뀐다.)

- [ ] **Step 4: 통과 확인**

Run(frontend): `npx vitest run composables/taskClose.spec.ts`
Expected: PASS(기존 4단계 테스트 포함).

- [ ] **Step 5: 커밋**

```bash
git add frontend/composables/taskClose.ts frontend/composables/taskClose.spec.ts
git commit -m "feat(front): 종료 단계에 산출물 업로드·건너뜀, 1회용 내려받기 헬퍼

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: 프론트 — 작업 상세 종료 카드

**Files:**
- Modify: `frontend/pages/tasks/[id].vue`
- Test: `frontend/test/task-detail-close.spec.ts`

**Interfaces:**
- Consumes: Task 13의 `canSkipArtifact`, `formatMb`, `MAIL_STATUS_LABEL`, `startArtifactDownload`. 서버 `close/skip-artifact`, `close/mail/resend`, `artifact/download-link`.
- Produces: data-test `close-artifact`, `close-artifact-download`, `close-mail`, `close-mail-resend`, `close-skip-artifact`, `bar-more-close-skip-artifact`.

- [ ] **Step 1: 실패하는 테스트 작성**

`task-detail-close.spec.ts`의 `describe` 끝(마지막 `})` 앞)에 추가:

```ts
  const artifact = { name: 'w-task-42-abcdef1.war', sizeBytes: 265_911_624, sha256: 'ab', uploadedAt: null }

  it('산출물 줄: 등록자에게 내려받기가 보이고 누르면 download-link를 POST한다 — 다른 사용자에게는 없다', async () => {
    setMe('alice')
    let w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', requesterId: 'alice', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'SENT', at: null } },
    })
    expect(w.find('[data-test="close-artifact"]').text()).toContain('w-task-42-abcdef1.war')
    expect(w.find('[data-test="close-artifact"]').text()).toContain('253.6 MB')
    expect(w.find('[data-test="close-mail"]').text()).toContain('메일 보냄')
    useApiMock.mockClear()
    useApiMock.mockResolvedValueOnce({ url: '/api/artifact-downloads/tok' })
    await w.find('[data-test="close-artifact-download"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/artifact/download-link', { method: 'POST' })
    w.unmount()
    document.body.innerHTML = ''

    setMe('bob')
    w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', requesterId: 'alice', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null },
    })
    expect(w.find('[data-test="close-artifact"]').exists()).toBe(true)
    expect(w.find('[data-test="close-artifact-download"]').exists()).toBe(false)
    w.unmount()
  })

  it('메일 실패면 관리자에게 다시 보내기가 보이고 close/mail/resend를 POST한다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'FAILED', at: null } },
    })
    expect(w.find('[data-test="close-mail"]').text()).toContain('메일 실패')
    useApiMock.mockClear()
    await w.find('[data-test="close-mail-resend"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/mail/resend', { method: 'POST', body: undefined })
    w.unmount()
  })

  it('산출물 단계에서 멈춘 종료실패: 관리자에게 산출물 없이 마무리가 보이고 확인 뒤 POST한다', async () => {
    authStub.isAdmin = true
    let w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: '산출물: 이미지 없음', deployment,
      close: { progress: ['MERGED', 'UNDEPLOYED'], mergeCommitSha: 'abcdef1234', notePath: null, artifactMode: 'REQUIRED' },
    })
    expect(w.find('[data-test="close-step-ARTIFACT_UPLOADED"]').exists()).toBe(true)
    const btn = w.find('[data-test="close-skip-artifact"]')
    expect(btn.exists()).toBe(true)
    useApiMock.mockClear()
    await btn.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('산출물 없이 노트·브랜치 정리를 이어 갑니다. 메일은 보내지 않습니다.')
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/skip-artifact', { method: 'POST', body: undefined })
    w.unmount()
    document.body.innerHTML = ''

    w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: '노트 실패', deployment,
      close: { progress: ['MERGED', 'UNDEPLOYED', 'ARTIFACT_UPLOADED'], mergeCommitSha: 'abcdef1234', notePath: null, artifactMode: 'REQUIRED' },
    })
    expect(w.find('[data-test="close-skip-artifact"]').exists()).toBe(false)
    w.unmount()
  })
```

- [ ] **Step 2: 실패 확인**

Run(frontend): `npx vitest run test/task-detail-close.spec.ts`
Expected: FAIL — `close-artifact` 등 요소 없음.

- [ ] **Step 3: 스크립트**

import 줄 `import { closeSteps, obsidianLink, type CloseView } from '~/composables/taskClose'`를 아래로 바꾼다:

```ts
import {
  closeSteps,
  obsidianLink,
  canSkipArtifact,
  formatMb,
  startArtifactDownload,
  MAIL_STATUS_LABEL,
  type CloseView,
} from '~/composables/taskClose'
```

`const noteHref = computed(...)` 줄 다음에 추가:

```ts
// 종료 산출물 (스펙 2026-10-14 §9) — 내려받기는 등록자 본인·관리자, 다시 보내기·산출물 없이 마무리는 관리자
const canDownloadArtifact = computed(() => !!task.value?.close?.artifact && (auth.isAdmin || isOwner.value))
const canResendMail = computed(
  () =>
    auth.isAdmin &&
    task.value?.status === 'CLOSED' &&
    !!task.value.close?.artifact &&
    ['FAILED', 'NO_ADDRESS', 'DISABLED'].includes(task.value.close?.mail?.status ?? ''),
)
const canSkipArtifactClose = computed(
  () => auth.isAdmin && !!task.value && canSkipArtifact(task.value.close, task.value.status),
)

async function downloadArtifact() {
  try {
    await startArtifactDownload(taskId.value, (u, o) => useApi<{ url: string }>(u, o))
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '내려받기 실패' })
  }
}

async function resendMail() {
  await closeAction('close/mail/resend', '메일 다시 보내기 완료')
}

async function skipArtifact() {
  if (!(await confirmDialog($q, '산출물 없이 노트·브랜치 정리를 이어 갑니다. 메일은 보내지 않습니다.', { title: '산출물 없이 마무리' }))) return
  await closeAction('close/skip-artifact', '산출물 없이 마무리 — 종료 큐 등록')
}
```

- [ ] **Step 4: 템플릿**

종료 카드 헤더의 `종료 취소` 버튼(`data-test="close-cancel"`) 다음에 추가:

```html
          <q-btn
            v-if="!$q.screen.lt.md && canSkipArtifactClose"
            outline
            color="warning"
            icon="skip_next"
            label="산출물 없이 마무리"
            data-test="close-skip-artifact"
            @click="skipArtifact"
          />
```

단계 아이콘의 두 식을 `skipped` 상태를 넣어 바꾼다:

```html
              <q-icon
                :name="s.state === 'done' ? 'check_circle' : s.state === 'failed' ? 'error' : s.state === 'current' ? 'autorenew' : s.state === 'skipped' ? 'remove_circle_outline' : 'radio_button_unchecked'"
                :color="s.state === 'done' ? 'positive' : s.state === 'failed' ? 'negative' : s.state === 'current' ? 'orange' : 'grey-5'"
              />
              <span>{{ s.label }}<template v-if="s.state === 'skipped'"> (건너뜀)</template></span>
```

(기존 `<span>{{ s.label }}</span>`을 위 `span`으로 바꾼다.)

노트 줄(`data-test="close-note"` div) 다음에 추가:

```html
          <div v-if="task.close.artifact" class="q-mt-sm row items-center" style="gap: 8px" data-test="close-artifact">
            <q-icon name="inventory_2" color="grey-7" />
            <span class="text-caption">산출물 <code>{{ task.close.artifact.name }}</code> · {{ formatMb(task.close.artifact.sizeBytes) }}</span>
            <q-btn
              v-if="canDownloadArtifact"
              dense
              flat
              color="primary"
              icon="download"
              label="내려받기"
              data-test="close-artifact-download"
              @click="downloadArtifact"
            />
          </div>
          <div v-if="task.close.mail" class="q-mt-xs row items-center" style="gap: 8px" data-test="close-mail">
            <q-chip dense square :color="task.close.mail.status === 'SENT' ? 'positive' : 'warning'" text-color="white">
              {{ MAIL_STATUS_LABEL[task.close.mail.status] ?? task.close.mail.status }}
            </q-chip>
            <q-btn
              v-if="canResendMail"
              dense
              flat
              color="primary"
              icon="forward_to_inbox"
              label="메일 다시 보내기"
              data-test="close-mail-resend"
              @click="resendMail"
            />
          </div>
```

하단 '더 보기' 메뉴의 `bar-more-close-cancel` 항목 다음에 추가:

```html
                  <q-item v-if="canSkipArtifactClose" v-close-popup clickable class="text-warning" data-test="bar-more-close-skip-artifact" @click="skipArtifact">
                    <q-item-section avatar><q-icon name="skip_next" color="warning" /></q-item-section>
                    <q-item-section>산출물 없이 마무리</q-item-section>
                  </q-item>
```

- [ ] **Step 5: 통과 확인**

Run(frontend): `npx vitest run test/task-detail-close.spec.ts composables/taskClose.spec.ts test/task-detail-mobile.spec.ts`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add "frontend/pages/tasks/[id].vue" frontend/test/task-detail-close.spec.ts
git commit -m "feat(front): 종료 카드에 산출물 내려받기·메일 상태·산출물 없이 마무리

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: 프론트 — 레포 카탈로그 폼

**Files:**
- Modify: `frontend/pages/admin/repo-catalog.vue`
- Test: `frontend/test/repo-catalog-admin.spec.ts`

**Interfaces:**
- Consumes: Task 9의 `View.artifactEnabled/artifactPath`, `UpsertRequest.artifactEnabled/artifactPath`.
- Produces: data-test `form-artifact-enabled`, `form-artifact-path`.

- [ ] **Step 1: 실패하는 테스트로 바꾸기**

`repo-catalog-admin.spec.ts`의 `POSTs a new entry with alias + gitUrl` 테스트에서 기대 `body`를 아래로 바꾼다:

```ts
      body: {
        alias: 'Netis7.0',
        gitUrl: 'https://github.com/micthebick84/netis7.0.git',
        defaultBranch: null,
        description: null,
        enabled: true,
        artifactEnabled: false,
        artifactPath: null,
      },
```

같은 `describe` 끝에 추가:

```ts
  it('산출물 보관을 켜면 경로 입력이 보이고 함께 보낸다', async () => {
    const w = await mountPage([])
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()
    const aliasInput = document.querySelector('[data-test="form-alias"]') as HTMLInputElement
    const urlInput = document.querySelector('[data-test="form-giturl"]') as HTMLInputElement
    aliasInput.value = '동부병원'
    aliasInput.dispatchEvent(new Event('input'))
    urlInput.value = 'owner/netisdash'
    urlInput.dispatchEvent(new Event('input'))
    expect(document.querySelector('[data-test="form-artifact-path"]')).toBeNull()
    ;(document.querySelector('[data-test="form-artifact-enabled"]') as HTMLElement).click()
    await flushPromises()
    const pathInput = document.querySelector('[data-test="form-artifact-path"]') as HTMLInputElement
    pathInput.value = '/usr/local/tomcat/webapps/ROOT.war'
    pathInput.dispatchEvent(new Event('input'))
    await flushPromises()

    useApiMock.mockResolvedValueOnce({})
    useApiMock.mockResolvedValueOnce([])
    ;(document.querySelector('[data-test="form-submit"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog', expect.objectContaining({
      method: 'POST',
      body: expect.objectContaining({ artifactEnabled: true, artifactPath: '/usr/local/tomcat/webapps/ROOT.war' }),
    }))
    w.unmount()
  })
```

- [ ] **Step 2: 실패 확인**

Run(frontend): `npx vitest run test/repo-catalog-admin.spec.ts`
Expected: FAIL — body에 산출물 필드 없음, `form-artifact-enabled` 없음.

- [ ] **Step 3: 구현**

`RepoEntry` 인터페이스 끝(`updatedAt: string` 다음)에 추가:

```ts
  artifactEnabled: boolean
  artifactPath: string | null
```

`form` reactive에 `artifactEnabled: false,`와 `artifactPath: '',`를 더한다. `openCreate`에 `form.artifactEnabled = false`·`form.artifactPath = ''`, `openEdit`에 `form.artifactEnabled = e.artifactEnabled ?? false`·`form.artifactPath = e.artifactPath ?? ''`를 더한다. `submit`의 `body`에 두 줄을 더한다:

```ts
      artifactEnabled: form.artifactEnabled,
      artifactPath: form.artifactPath || null,
```

`toggleEnabled`의 `body`는 **바꾸지 않는다** — `artifactEnabled`가 빠지면 서버가 산출물 설정을 그대로 둔다(Task 9 규칙). `admin-catalog-cards-mobile.spec.ts`의 카드 토글 PUT 기대값도 그대로 통과한다.

폼 템플릿의 `<q-toggle v-model="form.enabled" … />` 다음에 추가:

```html
        <q-toggle
          v-model="form.artifactEnabled"
          label="종료 때 산출물 보관 (WAR·JAR, 등록자 메일)"
          color="primary"
          data-test="form-artifact-enabled"
        />
        <q-input
          v-if="form.artifactEnabled"
          v-model="form.artifactPath"
          label="이미지 안 산출물 경로 (선택)"
          placeholder="비우면 자동: /usr/local/tomcat/webapps·/app에서 하나일 때"
          outlined
          :dense="!mobile"
          autocapitalize="off"
          autocorrect="off"
          spellcheck="false"
          data-test="form-artifact-path"
        />
```

- [ ] **Step 4: 통과 확인**

Run(frontend): `npx vitest run test/repo-catalog-admin.spec.ts test/admin-catalog-cards-mobile.spec.ts test/admin-form-dialog-mobile.spec.ts`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/admin/repo-catalog.vue frontend/test/repo-catalog-admin.spec.ts
git commit -m "feat(front): 레포 카탈로그 폼에 산출물 보관 켜기·경로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: 문서·운영 + 전체 검증

**Files:**
- Modify: `CLAUDE.md` (작업 상태머신의 `- **종료**` 항목)
- Modify: `scripts/install-autostart.ps1` (public.env 템플릿)
- Modify: `docs/superpowers/specs/2026-10-14-close-artifact-delivery-design.md` (§13 추가)

- [ ] **Step 1: `CLAUDE.md`**

`- **종료**(\`TaskCloseService\`/…` 항목 끝(`배포 순서: 워커 → API(V26) → 프론트.`) 뒤에 이어 붙인다:

```markdown
 **종료 산출물(2026-10-14, 스펙 `docs/superpowers/specs/2026-10-14-close-artifact-delivery-design.md`)**: 관리자 종료 승인 때 `CLOSE_ARTIFACT_ENABLED`(API) + 카탈로그 `artifact_enabled`가 둘 다 켜졌으면 `task.close_artifact_mode=REQUIRED` — 그때만 ③ 산출물 업로드(`ARTIFACT_UPLOADED`, 병합·배포 중지 뒤·노트 앞)가 필요 단계가 된다(`CloseStep.required(mode)`; null이면 기존 4단계). 워커 `ArtifactPublisher`가 `netis-task-{id}:{sha7}` 이미지에서 `docker create --entrypoint true`+`docker cp`로 꺼내(경로는 카탈로그 `artifact_path`, 비면 `CLOSE_ARTIFACT_SEARCH_DIRS`에서 하나일 때만) `PUT /worker/tasks/{id}/artifact`(원시 본문, `X-Artifact-Sha256`)로 올리고, API는 **트랜잭션 밖에서** `attachments/task-{id}/artifact/`에 받는다(작업 소프트 삭제 30일 뒤 첨부 정리 잡이 함께 삭제). 내려받기는 `POST /api/tasks/{id}/artifact/download-link`(등록자·관리자) → 60초 1회용 `GET /api/artifact-downloads/{token}`(이 GET만 permitAll). 업로드가 영구 실패하면 관리자 `POST …/close/skip-artifact`(모드 SKIPPED, 메일 없음). 작업종료 커밋 뒤 `CloseMailListener`(단일 스레드)가 등록자에게만 메일(앱 비밀번호 SMTP, `MAIL_*`, 링크 기준 `APP_PUBLIC_BASE_URL`) — 결과 `close_mail_status`, 실패는 `…/close/mail/resend`. 수신 주소는 등록·종료 요청 때 JWT `email`, 없으면 `com."user".email`. `management.health.mail.enabled=false`. 배포 순서: API(V27, 기능 꺼진 채) → 워커 → 프론트 → `public.env`에 메일·`CLOSE_ARTIFACT_ENABLED=true` → 카탈로그에서 레포 켜기.
```

- [ ] **Step 2: `install-autostart.ps1`**

템플릿 배열의 `'#CLOSE_MERGE_METHOD=merge'` 줄 다음에 추가(작은따옴표 문자열, 기존 줄과 같은 들여쓰기):

```powershell
        ''
        '# Close artifact + requester mail (API). Turn on per repo in the repo catalog after setting these.'
        '#CLOSE_ARTIFACT_ENABLED=true'
        '#APP_PUBLIC_BASE_URL=https://app-win.micthebick.dev'
        '# Gmail SMTP with an app password (2-step verification on the sender account). Empty MAIL_USERNAME = mail off.'
        '#MAIL_USERNAME=sender@hamonsoft.co.kr'
        '#MAIL_PASSWORD='
        '#MAIL_FROM='
```

- [ ] **Step 3: 스펙 §13**

스펙 파일 끝에 추가:

```markdown

## 13. 구현 메모(계획 단계에서 확정)

- 메일 비동기는 `@Async`/`@EnableAsync` 대신 `CloseMailListener` 안의 단일 스레드 실행기 — 실행기 빈을 등록하면 Spring Boot 기본 `applicationTaskExecutor`가 꺼진다.
- 저장 경로 규칙은 새 `ArtifactStore`(루트는 같은 `app.attachment.dir`)에 둔다. `AttachmentStorage`는 바꾸지 않는다.
- 업로드는 검사 tx(잠금 없음) → 파일 쓰기(tx 밖) → 기록 tx(FOR UPDATE 후 재검사)로 나눈다 — Hikari 누수 경보(30초)와 행 잠금을 254MB 수신 동안 잡지 않는다.
- 모바일 버튼은 기존 종료 카드 관례(카드는 공용, 관리자 보조 버튼은 하단 '더 보기')를 따른다. `TaskDetailMobile`·액션 시트는 바꾸지 않는다.
- '산출물 없이 마무리' 사유 입력은 화면에서 생략(서버는 선택 사유를 받는다). 카탈로그 목록의 켜짐 표시도 생략.
- `management.health.mail.enabled=false` — 메일 서버 접속 실패가 `/actuator/health`를 DOWN으로 만들지 않게.
- 워커 설정은 `CloseProperties`를 바꾸지 않고 `ArtifactProperties`(`netis-maker.worker.close.artifact.*`)로 분리.
```

- [ ] **Step 4: 전체 검증**

Run: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test`
Expected: BUILD SUCCESSFUL. 이 PC에서 원래 실패하던 `AttachmentCleanupJobTest`·`AttachmentStorageTest`(심볼릭 링크)만 실패하면 회귀가 아니다 — 그 밖의 실패는 고친다.

Run(frontend): `npx vitest run`
Expected: PASS.

Run(frontend): `npm run build && npm run test:hydration`
Expected: 빌드 성공, 하이드레이션 불일치 0건(약 2분).

- [ ] **Step 5: 커밋**

```bash
git add CLAUDE.md scripts/install-autostart.ps1 docs/superpowers/specs/2026-10-14-close-artifact-delivery-design.md
git commit -m "docs: 종료 산출물·메일 운영 안내, 스펙 구현 메모

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 라이브 확인 (병합·배포 뒤, 사용자와 함께)

1. 배포 순서대로 반영: API(V27) → 워커 → 프론트 `.output` → `public.env`(`MAIL_*`, `APP_PUBLIC_BASE_URL`, `CLOSE_ARTIFACT_ENABLED=true`) → API 재기동.
2. Windows 워커의 `docker cp`가 `C:\…` 로컬 경로를 로컬로 인식하는지(드라이브 문자 콜론) 첫 실제 종료에서 확인 — 실패하면 워커 로그의 `산출물: 이미지에서 꺼내기 실패` 사유를 본다.
3. 카탈로그에서 WAR 레포 하나를 켜고(경로 `/usr/local/tomcat/webapps/ROOT.war`) 실제 작업 하나를 종료 → 기능 의도서 완료 기준(파일 크기·해시, 등록자 메일, 상세·노트 링크, 재시도 멱등, 메일 실패 시 작업종료 유지, 꺼진 상태 4단계)을 차례로 확인.
