# 종료 부분 패치 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업을 종료할 때 그 작업이 바꾼 배포 파일만(자바는 `.class`) 배포 폴더 구조로 담은 zip을 만들어 보관하고, 작업 목록·상세의 버튼으로 내려받게 한다.

**Architecture:** 종료 단계에 `PATCH_BUILT`(병합 직후)를 더한다. 워커가 병합 전·후 커밋을 카탈로그 미리 빌드 명령으로 각각 빌드하고, 결과 WAR/zip을 풀지 않은 채 항목 해시로 비교해 바뀐 항목만 zip으로 묶어 API에 올린다. API는 산출물과 같은 방식(트랜잭션 밖 파일 쓰기, 1회용 토큰)으로 보관·내려받기를 제공한다.

**Tech Stack:** Java 21 · Spring Boot(API·워커 프로파일) · Flyway · JUnit 5 + Mockito + AssertJ · Nuxt 3 + Quasar + vitest. 새 의존성 없음(`java.util.zip`만 사용).

**Spec:** `docs/superpowers/specs/2026-11-10-close-partial-patch-design.md`

## Global Constraints

- 작업 위치는 worktree `.claude/worktrees/close-partial-patch`(브랜치 `feature/close-partial-patch`)뿐이다. 본 체크아웃(`C:\Users\mic\NetisMaker\commanCenter`)은 라이브가 돌므로 거기서 빌드·시험하지 않는다. `gradlew --stop` 금지.
- 백엔드 시험: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests '<클래스 전체 이름>'`. 단위 시험은 Spring 없이 JUnit 5 + Mockito + AssertJ, package-private 클래스.
- 프론트 시험: `cd frontend && npx vitest run <경로>`. worktree에 `frontend/node_modules`가 없으면 본 체크아웃 것으로 정션을 건다(`cmd //c "mklink /J frontend\\node_modules ..\\..\\..\\frontend\\node_modules"`). 작은따옴표·세미콜론 없음. `defineProps<T>()`의 T를 `~/`에서 가져오지 않는다.
- 마이그레이션은 `V43__close_partial_patch.sql`. 병합 직전 `origin/main`의 최대 번호를 다시 확인한다. 시각 칸은 `TIMESTAMPTZ`(스펙 §9의 TIMESTAMP 표기는 기존 관례에 맞춰 이렇게 읽는다).
- 패치 모드 값은 `"REQUIRED"`·`"SKIPPED"`·null. 종료 단계 이름은 `PATCH_BUILT`, 순서는 `MERGED, PATCH_BUILT, UNDEPLOYED, ARTIFACT_UPLOADED, NOTE_WRITTEN, BRANCH_DELETED`.
- 고정 문구(그대로 쓴다): `부분 패치는 rebase 병합을 지원하지 않습니다` · `패치 기준 커밋을 확인할 수 없습니다: {사유}` · `패치 전·후 빌드 결과의 구성이 다릅니다` · `부분 패치는 미리 빌드 명령이 있어야 켤 수 있습니다` · 화면 `부분 패치` · `변경 없음` · `패치 없이 마무리` · `부분 패치 만들기`.
- 부분 패치를 켜지 않은 작업(`close_patch_mode` null)의 종료 흐름·claim·응답은 지금과 같아야 한다(새 칸은 null).
- 토큰·비밀은 실패 사유·로그에 그대로 싣지 않는다(`GitRemotes.mask`).
- 스펙과 다르게 정한 것 두 가지(이 계획이 우선): ① zip 파일 이름은 **API가 업로드를 받을 때** 정한다(디스크에는 고정 이름 `patch.zip`, 내려받기 이름만 `patch_name`) — 한글 이름을 워커 요청 파라미터·디스크 경로에 싣지 않으려는 것. ② 비교 대상은 zip 형식(`.war`·`.jar`·`.zip`)만 지원하고 `.tar.gz`·`.tgz`는 `tar.gz 묶음은 부분 패치를 지원하지 않습니다`로 단계 실패(새 의존성 없이 가려는 것, 지금 대상 레포는 모두 war·zip).

## Review Focus

1. **내용은 같고 시각만 다른 jar** — 전·후 빌드에서 `WEB-INF/lib`의 jar 수백 개가 바이트로는 다르다. 패치에 하나도 실리면 안 된다(Task 5 `nestedJarWithOnlyTimestampDifferenceIsEqual`).
2. **기준 커밋을 믿을 수 없는 병합** — 사람이 미리 병합했거나 병합 직전에 기준 브랜치에 다른 push가 끼었다. 틀린 범위의 패치 대신 단계 실패여야 한다(Task 7 `PatchBaseTest`).
3. **업로드는 됐는데 진행 보고가 실패한 재시도** — 다시 20분 빌드하지 않고 이어 가야 한다(Task 8 `retryAfterUploadSkipsBuild`).
4. **이상한 작업 제목** — 한글·`/`·`:`·공백뿐·100자 넘는 제목으로도 내려받을 수 있는 파일 이름이 나와야 한다(Task 2 `PatchFileNamesTest`).
5. **악의적·깨진 압축 항목 이름** — `../`나 절대 경로 항목이 든 산출물로 워커 PC의 다른 경로에 쓰면 안 된다(Task 5 `entryEscapingRootIsRejected`).

---

## File Structure

API(`src/main/java/com/hamonsoft/netismaker/`)
- `entity/CloseStep.java` — `PATCH_BUILT`와 패치 모드를 받는 `required`/`allDone`.
- `entity/Task.java`, `entity/RepoCatalogEntry.java` — 새 칸.
- `util/PatchFileNames.java` — 내려받기 파일 이름 규칙(한 곳).
- `service/ClosePatchProperties.java` — `app.close.patch.*`.
- `service/TaskCloseService.java` — 대상 판정, `skipPatch`.
- `dto/ClosePatchSpec.java`, `dto/PatchInfo.java` — claim 재료, 업로드 결과.
- `service/PatchStore.java`, `service/ClosePatchService.java`, `controller/WorkerPatchController.java` — 업로드·보관.
- `service/ArtifactDownloadTokens.java`·`ArtifactDownloadService.java`, `controller/CloseArtifactController.java` — 토큰에 종류, 패치 링크.
- `service/WorkerService.java`, `dto/CloseProgressRequest.java`, `dto/WorkerTaskResponse.java`, `dto/TaskResponse.java` — claim·진행 보고·응답.
- `service/StaleTaskRecoveryJob.java`, `application.yml` — 판정 시간.

워커(`workerdaemon/patch/` 새 패키지)
- `PatchTree.java` — 스캔 결과(모양 + 경로→해시).
- `PatchScanner.java` — 산출물 → `PatchTree`, 고른 항목 꺼내기.
- `PatchDiff.java` — 두 `PatchTree` 비교.
- `PatchZipWriter.java` — zip·`PATCH-INFO.txt`·`DELETED.txt`.
- `PatchManifestStore.java` — "후" 해시 목록 보관.
- `PatchBase.java` — "전" 커밋 검증(순수 함수).
- `PatchBuilder.java` — 전체 흐름.
- `ClosePatchWorkerProperties.java` — `netis-maker.worker.close.patch.*`.
- 고치는 곳: `DeployPrebuilder`, `WorktreeKind`, `WorktreeService`, `GitRepoCache`, `GitOpsService`, `WorkerHttpClient`, `TaskCloser`, `application-worker.yml`.

프론트(`frontend/`)
- `composables/taskClose.ts`, `composables/useOneTimeDownload.ts`(새), `pages/tasks/[id].vue`, `pages/tasks/index.vue`, `components/tasks/TaskCardCompact.vue`, `pages/admin/repo-catalog.vue`, `scripts/hydration-smoke.mjs`.

---

### Task 1: DB·엔티티·종료 단계·카탈로그 칸

**Files:**
- Create: `src/main/resources/db/migration/V43__close_partial_patch.sql`
- Modify: `entity/CloseStep.java`, `entity/Task.java`, `entity/RepoCatalogEntry.java`, `dto/RepoCatalogDto.java`, `service/RepoCatalogService.java`, `service/WorkerService.java:1117`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/CloseStepTest.java`(있으면 추가), `service/RepoCatalogServicePatchTest.java`(새)

**Interfaces:**
- Produces:
  - `CloseStep.PATCH_BUILT`; `CloseStep.PATCH_REQUIRED = "REQUIRED"`, `PATCH_SKIPPED = "SKIPPED"`
  - `static EnumSet<CloseStep> required(String artifactMode, String patchMode)`, `static boolean allDone(String csv, String artifactMode, String patchMode)` — 기존 1·2인자 형태는 지운다(호출부는 `WorkerService.java:1117` 하나).
  - `Task`: `closePatchMode`(VARCHAR 20), `patchBaseSha`(40), `patchName`(255), `patchSize`(Long), `patchSha256`(64), `patchFileCount`(Integer), `patchEmpty`(Boolean), `patchUploadedAt`(OffsetDateTime).
  - `RepoCatalogEntry.patchEnabled`(boolean, `patch_enabled NOT NULL DEFAULT FALSE`); `RepoCatalogDto.View.patchEnabled`(boolean, 맨 끝), `UpsertRequest.patchEnabled`(Boolean, 맨 끝 — 기존 14인자 형태는 호환 생성자로 남긴다).

- [ ] **Step 1: 실패하는 시험 쓰기**

```java
// CloseStepTest
@Test void patchStepRequiredOnlyWhenModeRequired() {
    assertThat(CloseStep.required(null, null)).doesNotContain(CloseStep.PATCH_BUILT, CloseStep.ARTIFACT_UPLOADED);
    assertThat(CloseStep.required(null, "REQUIRED")).contains(CloseStep.PATCH_BUILT);
    assertThat(CloseStep.required(null, "SKIPPED")).doesNotContain(CloseStep.PATCH_BUILT);
    assertThat(CloseStep.allDone("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED", null, "REQUIRED")).isFalse();
    assertThat(CloseStep.allDone("MERGED,UNDEPLOYED,NOTE_WRITTEN,BRANCH_DELETED", null, null)).isTrue();
}
@Test void appendKeepsDeclaredOrder() {
    assertThat(CloseStep.append("MERGED,UNDEPLOYED", CloseStep.PATCH_BUILT)).isEqualTo("MERGED,PATCH_BUILT,UNDEPLOYED");
}
// RepoCatalogServicePatchTest (RepoCatalogServicePrebuildTest와 같은 준비)
@Test void enablingPatchWithoutPrebuildCommandIsRejected()   // 400, 메시지 "부분 패치는 미리 빌드 명령이 있어야 켤 수 있습니다"
@Test void clearingPrebuildCommandWhilePatchEnabledIsRejected() // 같은 400
@Test void nullPatchEnabledKeepsCurrentValue()
@Test void patchCanBeEnabledWhenPrebuildDisabledButCommandPresent()
```

- [ ] **Step 2: 시험이 실패하는지 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.entity.CloseStepTest' --tests 'com.hamonsoft.netismaker.service.RepoCatalogServicePatchTest'` → 컴파일 실패.
- [ ] **Step 3: 구현** — V43(위 칸 전부, `ADD COLUMN IF NOT EXISTS`, 머리 주석에 스펙 날짜), 엔티티 칸, `CloseStep`, `RepoCatalogService.applyPatch(RepoCatalogEntry e, RepoCatalogDto.UpsertRequest req)`(`applyPrebuild` **뒤에** 호출 — null이면 유지, 적용 뒤 `e.isPatchEnabled()`인데 `prebuildCommand`가 비면 400). `applyPrebuild`는 지금 "켤 때만" 명령을 검증하므로, 미리 빌드가 꺼져 있어도 명령 값은 저장되는지 확인하고 그대로 둔다. `WorkerService:1117`은 `allDone(progress, artifactMode, t.getClosePatchMode())`.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests 'com.hamonsoft.netismaker.service.WorkerServiceCloseTest' --tests 'com.hamonsoft.netismaker.service.RepoCatalogServicePrebuildTest'` 모두 PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 종료 단계 PATCH_BUILT·패치 칸(V43)·카탈로그 부분 패치 켜기"`

---

### Task 2: 대상 판정·건너뛰기·파일 이름·stale 판정 (API)

**Files:**
- Create: `service/ClosePatchProperties.java`, `util/PatchFileNames.java`
- Modify: `service/TaskCloseService.java`, `controller/TaskCloseController.java`, `service/StaleTaskRecoveryJob.java`, `src/main/resources/application.yml`, `NetisMakerApplication.java`(properties 등록)
- Test: `util/PatchFileNamesTest.java`(새), `service/TaskCloseServiceTest.java`, `service/StaleTaskThresholdConfigTest.java`, `service/StaleTaskRecoveryJobTest.java`(있는 종료 시험 옆)

**Interfaces:**
- Consumes: Task 1의 `Task.closePatchMode`, `CloseStep.PATCH_*`, `RepoCatalogEntry.isPatchEnabled()`.
- Produces:
  - `@ConfigurationProperties(prefix="app.close.patch") record ClosePatchProperties(boolean enabled, Long maxSizeMb)` + `long maxBytes()`(기본 1024). yml: `app.close.patch.enabled: ${CLOSE_PATCH_ENABLED:false}`, `max-size-mb: ${CLOSE_PATCH_MAX_SIZE_MB:1024}`.
  - `TaskCloseService.patchRequired(Task t)`(package-private): 스위치 켜짐 + 카탈로그 `patchEnabled` + 카탈로그 `prebuildCommand` 비어 있지 않음 + `taskType != DB_WORK`.
  - `approve`가 `t.setClosePatchMode(patchRequired(t) ? PATCH_REQUIRED : null)`를 하고 이력 사유 끝에 ` (부분 패치)`를 붙인다(산출물 문구 뒤).
  - `@Transactional public Task skipPatch(Long taskId, String adminId, String reason)`: 상태 `CLOSE_FAILED` + 모드 REQUIRED + `PATCH_BUILT` 미완료일 때만(아니면 409), 모드 `SKIPPED` → `CLOSE_PENDING`, 이력 `패치 없이 마무리`(+사유).
  - `POST /api/tasks/{id}/close/skip-patch`(관리자, 바디는 `skip-artifact`와 같은 `reason`).
  - `PatchFileNames.zipName(LocalDate date, String title, long taskId) : String`.
  - `app.task.close-patch-stale-threshold-minutes: 110`; `StaleTaskRecoveryJob`의 CLOSING 판정은 모드 REQUIRED이고 `PATCH_BUILT`가 진행에 없을 때 110, 아니면 기존 60.

- [ ] **Step 1: 실패하는 시험 쓰기**

```java
// PatchFileNamesTest
assertThat(zipName(LocalDate.of(2026,11,10), "로그인 오류 수정", 7)).isEqualTo("20261110_로그인_오류_수정.zip");
assertThat(zipName(d, "a/b:c*?\"<>|\\d", 7)).isEqualTo("20261110_abcd.zip");
assertThat(zipName(d, "   ", 7)).isEqualTo("20261110_task-7.zip");
assertThat(zipName(d, null, 7)).isEqualTo("20261110_task-7.zip");
assertThat(zipName(d, "가".repeat(100), 7)).isEqualTo("20261110_" + "가".repeat(60) + ".zip");
assertThat(zipName(d, "x\ty\n", 7)).isEqualTo("20261110_xy.zip");          // 제어 문자 제거
assertThat(zipName(d, "..", 7)).isEqualTo("20261110_task-7.zip");           // 점만 남으면 대체
// TaskCloseServiceTest
approveSetsPatchModeWhenSwitchCatalogAndCommandPresent()
approveLeavesPatchModeNullForDbWork() / WhenSwitchOff() / WhenCatalogOff() / WhenCommandBlank()
skipPatchMovesToClosePendingWithSkippedMode()
skipPatchRejectedWhenPatchAlreadyBuilt()   // 409
// StaleTaskThresholdConfigTest: application.yml의 close-patch-stale-threshold-minutes == 110
// StaleTaskRecoveryJobTest: closingWithPatchPendingUses110Minutes(), closingAfterPatchBuiltUses60Minutes()
```

- [ ] **Step 2: 실패 확인** — 위 네 시험 클래스 실행 → 컴파일 실패.
- [ ] **Step 3: 구현** — 제목 규칙: `\ / : * ? " < > |`와 제어 문자(`Character.isISOControl`) 제거 → 공백 연속을 `_` 하나로 → 앞뒤 `_`·`.` 제거 → 코드포인트 60개까지 → 비면 `task-{id}`. yml의 새 키 옆에 워커 `DEPLOY_PREBUILD_TIMEOUT`을 가리키는 주석(`application.yml:152`의 배포 판정 주석과 같은 식).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 종료 승인 때 부분 패치 대상 판정·패치 없이 마무리·파일 이름 규칙"`

---

### Task 3: claim·진행 보고·업로드·보관·내려받기·응답 (API)

**Files:**
- Create: `dto/ClosePatchSpec.java`, `dto/PatchInfo.java`, `service/PatchStore.java`, `service/ClosePatchService.java`, `controller/WorkerPatchController.java`
- Modify: `dto/WorkerTaskResponse.java`, `dto/CloseProgressRequest.java`, `service/WorkerService.java`, `service/ArtifactDownloadTokens.java`, `service/ArtifactDownloadService.java`, `controller/CloseArtifactController.java`, `dto/TaskResponse.java`
- Test: `service/PatchStoreTest.java`, `service/ClosePatchServiceTest.java`, `service/ArtifactDownloadTokensTest.java`, `service/ArtifactDownloadServiceTest.java`, `service/WorkerServiceCloseTest.java`, `dto/TaskResponseCloseViewTest.java`(기존 CloseView 시험 옆)

**Interfaces:**
- Consumes: Task 1 칸, Task 2 `ClosePatchProperties`·`PatchFileNames`.
- Produces:
  - `record ClosePatchSpec(String command, String toolchain, String artifact, String baseSha)` — 카탈로그 `prebuildCommand`·`prebuildToolchain`·`prebuildArtifact`와 `task.patchBaseSha`.
  - `WorkerTaskResponse`에 맨 끝 컴포넌트 `ClosePatchSpec closePatch`; `forClose(Task, CloseContext, CloseArtifactSpec, ClosePatchSpec)`(기존 2·3인자 형태는 null로 위임). `WorkerService.closePatchSpec(Task)`: 모드 REQUIRED이고 `PATCH_BUILT` 미완료일 때만 non-null.
  - `CloseProgressRequest`에 `String baseSha, Boolean patchEmpty, Integer patchFileCount`를 끝에 더하고 기존 5·4인자 생성자를 남긴다.
  - `recordCloseProgress`: `MERGED`에서 `baseSha`가 있으면(40자 넘으면 400) `patchBaseSha`에 기록. `PATCH_BUILT`: 모드가 REQUIRED가 아니면 409; `patchEmpty == true`면 `patchEmpty=true`·`patchFileCount=0`; 아니면 `patchName`이 없을 때 400(업로드 먼저), `patchEmpty=false`.
  - `record PatchInfo(String name, long sizeBytes, String sha256, int fileCount)`.
  - `PatchStore`(`@Component @Profile("api")`, 같은 `app.attachment.dir`): `Path file(long taskId)` = `task-{id}/patch/patch.zip`; `boolean exists(long)`; `long write(long taskId, InputStream body, String expectedSha256, long maxBytes) throws IOException`(`.part` → 해시·크기 확인 → 원자 이동, `ArtifactStore.TooLarge`·`HashMismatch` 재사용).
  - `ClosePatchService.receive(Long taskId, String workerId, String sha256, int fileCount, InputStream body) : PatchInfo` — `CloseArtifactService.receive`와 같은 3단계. guard: 상태 CLOSING·담당 워커·패치 모드 REQUIRED(각 409). 기록 때 `patchName = PatchFileNames.zipName(LocalDate.now(서울), task.getTitle(), id)`, 크기·sha·개수·`patchUploadedAt`. 같은 sha가 이미 있으면 본문을 버리고 기존 값을 돌려준다. `Optional<PatchInfo> current(Long taskId)`.
  - `PUT /worker/tasks/{id}/patch?workerId=&fileCount=`(octet-stream, `X-Artifact-Sha256`) → `PatchInfo`; `GET /worker/tasks/{id}/patch` → 200 `PatchInfo` | 404. `ROLE_WORKER`.
  - `ArtifactDownloadTokens`: `enum Kind { ARTIFACT, PATCH }`, `String issue(long taskId, Kind kind)`, `Optional<Ticket> redeem(String token)`, `record Ticket(long taskId, Kind kind)`. 기존 `issue(long)`·`OptionalLong redeem`은 지우고 호출부를 고친다.
  - `ArtifactDownloadService.patchLink(Long taskId, String actorId, boolean isAdmin) : Link`(404 작업 없음 → 403 → 404 `patchName` 없음 → 410 파일 없음); `redeem`은 종류에 따라 `ArtifactStore` 또는 `PatchStore` 파일과 이름(`patchName`)을 돌려준다.
  - `POST /api/tasks/{id}/patch/download-link` → `Link`. 내려받기 GET 주소는 기존 `/api/artifact-downloads/{token}` 그대로.
  - `TaskResponse.CloseView`에 끝 컴포넌트 `String patchMode, PatchView patch`; `record PatchView(String name, Long sizeBytes, Integer fileCount, boolean empty, OffsetDateTime uploadedAt)` — `patchName != null`이거나 `patchEmpty == true`일 때만 non-null. `closeViewOf`의 "기록이 있나" 판정에 패치 모드도 넣는다.

- [ ] **Step 1: 실패하는 시험 쓰기**

```java
// ArtifactDownloadTokensTest
tokenCarriesKind()                      // issue(1, PATCH) → redeem → Ticket(1, PATCH), 두 번째 redeem은 empty
// ArtifactDownloadServiceTest
patchLinkRedeemsToPatchFileWithKoreanName()   // Download.name == "20261110_로그인_오류_수정.zip", file == store.file(id)
artifactTokenNeverReturnsPatchFile()
patchLinkIs404WhenNoPatch() / 403ForOtherUser() / 410WhenFileGone()
// PatchStoreTest
writeRejectsHashMismatchAndLeavesNoFile(), writeReplacesPreviousFile(), writeRejectsOverMaxBytes()
// ClosePatchServiceTest (CloseArtifactServiceTest와 같은 준비)
receiveRecordsNameSizeShaCount(), receiveRejectsWhenNotClosing()/WrongWorker()/ModeNotRequired() // 409
receiveSameShaTwiceIsIdempotent()
// WorkerServiceCloseTest
claimCarriesClosePatchSpecOnlyWhenRequiredAndNotBuilt()
mergedProgressStoresBaseSha(), patchBuiltWithoutUploadIs400(), patchBuiltEmptySetsEmptyFlag()
patchBuiltWhenModeNullIs409()
// TaskResponseCloseViewTest
closeViewHasPatchWhenUploaded(), closeViewHasEmptyPatch(), closeViewPatchNullWhenModeNull()
```

- [ ] **Step 2: 실패 확인** — 위 클래스들 실행 → 컴파일 실패.
- [ ] **Step 3: 구현** — 위 Interfaces대로. `SecurityConfig`는 바꾸지 않는다(GET 주소 그대로). 첨부 정리 잡은 `task-{id}` 폴더째 지우므로 손대지 않는다.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests 'com.hamonsoft.netismaker.service.CloseArtifactServiceTest' --tests 'com.hamonsoft.netismaker.workerdaemon.TaskCloserTest'` PASS(기존 산출물 흐름 회귀 없음).
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 부분 패치 업로드·보관·1회용 내려받기와 claim·진행 보고·응답 칸"`

---

### Task 4: 통합 시험 (API, Testcontainers)

**Files:**
- Create: `src/test/java/com/hamonsoft/netismaker/controller/ClosePatchFlowIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1~3 전부. `CloseArtifactFlowIntegrationTest`의 준비(어노테이션·`TestcontainersConfig`·`@TestPropertySource`)를 그대로 따르고 `app.close.patch.enabled=true`를 더한다.

- [ ] **Step 1: 시험 쓰기** — 한 흐름: 카탈로그 `patchEnabled` + 명령 설정 → 배포완료 작업 종료 승인(이력에 `(부분 패치)`) → claim에 `closePatch` → `MERGED`(baseSha) → `PUT /worker/tasks/{id}/patch` → `PATCH_BUILT` → 나머지 단계 → `closed` 보고 → `GET /api/tasks/{id}`의 `close.patch.name`이 `yyyyMMdd_…zip` → `POST …/patch/download-link` → GET 토큰 주소가 올린 바이트와 같고 두 번째 GET은 404/410. 두 번째 흐름: `PATCH_BUILT` 없이 `closed` 보고 → 작업종료가 되지 않음 → `skip-patch` → 종료.
- [ ] **Step 2: 실행** — 이 PC는 Testcontainers를 건너뛴다(`RUN_TESTCONTAINERS` 없음). 컴파일만 확인: `./gradlew compileTestJava`. CI `Backend` 잡이 돌린다.
- [ ] **Step 3: Commit** — `git commit -m "test(patch): 부분 패치 종료 흐름 통합 시험"`

---

### Task 5: 산출물 스캔·비교 (워커, 순수 로직)

**Files:**
- Create: `workerdaemon/patch/PatchTree.java`, `PatchScanner.java`, `PatchDiff.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/patch/PatchScannerTest.java`, `PatchDiffTest.java`

**Interfaces:**
- Produces:
  - `record PatchTree(String shape, SortedMap<String, String> hashes)` — 키는 패치 안 배포 경로(`patch/` 접두 없음), 값은 해시.
  - `final class PatchScanner { PatchScanner(List<String> ignoreGlobs); PatchTree scan(Path artifact) throws IOException, PatchException; void extract(Path artifact, Collection<String> paths, Path destDir) throws IOException, PatchException; }`
  - `class PatchException extends Exception`(메시지가 곧 실패 사유).
  - `record PatchDelta(List<String> added, List<String> changed, List<String> deleted) { boolean isEmpty(); int fileCount(); /* added+changed */ }`
  - `static PatchDelta PatchDiff.diff(PatchTree before, PatchTree after) throws PatchException` — `shape`가 다르면 `패치 전·후 빌드 결과의 구성이 다릅니다`.
- 규칙(스펙 §6.3·§6.4):
  - 대상이 `.war`/`.jar`: `shape = "single:war"`/`"single:jar"`, 경로 = 항목 이름.
  - 대상이 `.zip`: 안의 `.war`마다 경로 = `<war 파일 이름에서 확장자 뺀 것>/<항목 이름>`, war가 아닌 파일은 `<묶음 안 경로>`. `shape = "bundle:" + 정렬한 war 이름 목록`.
  - `.tar.gz`·`.tgz`: `tar.gz 묶음은 부분 패치를 지원하지 않습니다`.
  - 일반 항목 해시 = 내용 SHA-256. **중첩 `.jar` 항목**(war 안, 깊이 1) 해시 = 그 jar의 (무시 패턴을 뺀 항목 이름 + 내용 SHA-256)을 이름순으로 이어 붙인 것의 SHA-256 — 시각·순서·압축 방식 무시. 중첩 jar는 풀지 않고 통째로 한 경로다.
  - 무시 패턴은 **각 압축 파일 안의 항목 이름**에 건다(war 항목에도, 중첩 jar 안 항목에도; 묶음 접두는 붙이지 않고 비교). glob은 `FileSystems.getDefault().getPathMatcher("glob:…")` 대신 직접 구현한 `**`·`*` 매칭(구분자 `/` 고정 — Windows에서 `\` 문제 회피). 디렉터리 항목은 건너뛴다.
  - 항목 이름이 절대 경로이거나 정규화 뒤 `..`로 밖을 가리키면 `scan`·`extract` 모두 `PatchException("압축 항목 경로가 잘못됐습니다: …")`.
  - `extract`는 `paths`의 각 경로를 `destDir/<경로>`에 쓴다(묶음이면 war 안에서 꺼낸다, 중첩 jar는 jar 파일 그대로).

- [ ] **Step 1: 실패하는 시험 쓰기** — 시험은 `@TempDir`에 `ZipOutputStream`으로 작은 war·jar·zip을 만들어 쓴다(도우미 `zip(Path, Map<String, byte[]>, long entryTime)`).

```java
// PatchScannerTest
singleWarPathsAreEntryNames()                 // {"WEB-INF/classes/A.class","index.jsp"} , shape "single:war"
directoriesAreSkipped()
ignoredEntriesAreAbsent()                     // META-INF/MANIFEST.MF, META-INF/maven/g/a/pom.properties
nestedJarWithOnlyTimestampDifferenceIsEqual() // 같은 내용, 다른 시각·순서의 lib jar → 두 scan의 해시가 같다
nestedJarWithChangedClassDiffers()
nestedJarIgnoresManifestInside()              // jar 안 MANIFEST.MF만 다르면 같다
bundleZipPrefixesWarBaseName()                // "hm-dash-daegu/WEB-INF/…", shape "bundle:[hm-dash-daegu.war, hm-dash-sangju.war]"
bundleNonWarFileKeepsItsPath()                // "README.txt"
tarGzIsRejectedWithMessage()
entryEscapingRootIsRejected()                 // "../evil", "/abs"
extractWritesOnlyRequestedPaths()             // 단일 war, 묶음 둘 다; 중첩 jar는 바이트 그대로
// PatchDiffTest
addedChangedDeletedAreSeparatedAndSorted()
identicalTreesGiveEmptyDelta()
innerClassFilesAreListedIndividually()        // A$1.class 추가 → added에 포함
shapeMismatchThrowsFixedMessage()
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.patch.*'` → 컴파일 실패.
- [ ] **Step 3: 구현** — `ZipFile`로 읽는다(중첩 압축은 `ZipInputStream`으로 스트림 처리, 임시 파일 없이). 큰 war(260MB)라 항목을 메모리에 통째로 올리지 않고 64KB 버퍼로 해시한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 산출물 항목 해시 스캔과 전·후 비교"`

---

### Task 6: zip 만들기·해시 목록 보관 (워커)

**Files:**
- Create: `workerdaemon/patch/PatchZipWriter.java`, `PatchManifestStore.java`
- Test: `workerdaemon/patch/PatchZipWriterTest.java`, `PatchManifestStoreTest.java`

**Interfaces:**
- Consumes: Task 5 `PatchTree`, `PatchDelta`.
- Produces:
  - `record PatchInfoDoc(long taskId, String title, String repoAlias, String beforeSha, String afterSha, OffsetDateTime createdAt, String command, PatchDelta delta, List<String> sourceChanges)`
  - `static void PatchZipWriter.write(Path zip, Path patchDir, PatchInfoDoc doc) throws IOException` — zip 맨 위에 `PATCH-INFO.txt`·`DELETED.txt`, 그 아래 `patch/<경로>`(`patchDir` 아래 파일 전부, 이름순, 구분자 `/`).
  - `static String PatchZipWriter.infoText(PatchInfoDoc doc)` — UTF-8. 줄 순서: `작업: #{id} {title}` / `레포: {alias}` / `패치 전 커밋: {40자}` / `패치 후 커밋: {40자}` / `※ 이 패치는 '패치 전' 커밋 상태의 배포본에만 적용한다.` / `만든 시각: {ISO, +09:00}` / `빌드 명령: {command}` / `추가 N · 변경 N · 삭제 N` / `[추가]`·`[변경]`·`[삭제]` 목록 / `[소스 변경(참고)]` 목록.
  - `DELETED.txt`: 삭제 경로 한 줄에 하나, 없으면 `(없음)` 한 줄.
  - `final class PatchManifestStore { PatchManifestStore(Path dir, int keep); Optional<PatchTree> load(String repoKey, String commit, String key); void save(String repoKey, String commit, String key, PatchTree tree); static String key(String command, String toolchain, String artifact, List<String> ignore); }` — 파일 `{dir}/{repoKey의 '/'→'+'}/{commit}-{key 앞 16자}.json`(Jackson), 저장 뒤 그 레포 폴더에서 최근 `keep`개(수정 시각)만 남긴다. 읽기 실패·형식 오류는 `Optional.empty()`(예외 없음). `key`는 네 값을 `\n`으로 이은 것의 SHA-256 hex.

- [ ] **Step 1: 실패하는 시험 쓰기**

```java
// PatchZipWriterTest
zipHasInfoDeletedAndPatchTree()        // 항목 이름 집합 == {"PATCH-INFO.txt","DELETED.txt","patch/WEB-INF/classes/A.class","patch/jsp/login.jsp"}
deletedTxtSaysNoneWhenEmpty()          // "(없음)"
infoTextHasWarningAndBothCommits()     // 경고 줄 그대로, 40자 sha 둘, "추가 1 · 변경 1 · 삭제 0"
entryNamesUseForwardSlashOnWindows()
// PatchManifestStoreTest
saveThenLoadRoundTrips()
differentKeyMisses()
corruptFileLoadsAsEmpty()
keepsOnlyNewestN()                     // keep=2로 3개 저장 → 가장 오래된 것 없음
keyChangesWhenIgnoreListChanges()
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.patch.PatchZipWriterTest' --tests '…PatchManifestStoreTest'`.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 패치 zip·명세·삭제 목록과 후 해시 목록 보관"`

---

### Task 7: 빌드·git 재료 (워커)

**Files:**
- Create: `workerdaemon/patch/PatchBase.java`
- Modify: `workerdaemon/DeployPrebuilder.java`, `WorktreeKind.java`, `WorktreeService.java`, `GitRepoCache.java`, `GitOpsService.java`
- Test: `workerdaemon/patch/PatchBaseTest.java`, `DeployPrebuilderTest.java`, `WorktreeKindTest.java`, `GitOpsServicePatchTest.java`(새, 임시 git 레포), `WorktreeCleanupPlannerTest.java`(새 종류 인식)

**Interfaces:**
- Produces:
  - `static String PatchBase.resolve(String mergeSha, String recordedBase, List<String> parentsOfAfter) throws PatchException` — "전" sha를 돌려준다. 규칙: `mergeSha` 비면 `패치 기준 커밋을 확인할 수 없습니다: 병합 커밋이 기록되지 않았습니다`; 부모가 없으면 `…: 병합 커밋의 부모를 읽지 못했습니다`; `recordedBase`가 있으면 `parents.get(0)`와 같아야 하고 다르면 `…: 병합 직전 기준 브랜치 끝(abc1234)과 병합 커밋의 부모(def5678)가 다릅니다`(앞 7자); `recordedBase`가 없으면 부모가 정확히 2개일 때만 `parents.get(0)`, 아니면 `…: 병합 직전 기준 브랜치를 기록하지 못했고 병합 커밋이 아닙니다`.
  - `DeployPrebuilder.buildOnly(WorkerTaskResponse.PrebuildSpec spec, Path worktree, Consumer<String> sink) : Path throws PrebuildException` — 명령을 `run`과 같은 방식으로 돌린 뒤 비교 대상 파일 경로를 돌려준다: `.netis-artifact/` 바로 아래 파일이 하나면 그것(2개 이상이면 예외), 없으면 `resolveArtifact(worktree, spec.artifact())`. 스테이징(복사)은 하지 않는다. `run`은 공통 부분을 같이 쓰도록 고치되 동작은 그대로. `boolean toolchainConfigured(String name)`(public) 추가.
  - `WorktreeKind.PATCH_BEFORE("patch-before-")`, `PATCH_AFTER("patch-after-")` — `parse`가 `patch-before-12`를 `(PATCH_BEFORE, 12)`로 읽고 기존 세 종류 판정은 그대로.
  - `WorktreeService.createAtCommit(File repoCacheDir, String repoKey, WorktreeKind kind, long taskId, String sha) : File` — 레포 락 안에서 `git worktree add --force --detach <dir> <sha>`.
  - `GitRepoCache.fetchPatchCommits(RepoRef ref, String baseBranch, String afterSha) : File` — 레포 락 안에서 `fetch --depth=2 origin <afterSha>`, 거부되면 `fetch --depth=50 origin <baseBranch>`; 그 뒤 `afterSha`가 캐시에 없으면 `IOException("병합 커밋을 받지 못했습니다")`. 캐시의 `origin/*`·HEAD는 건드리지 않는다(참조 받기와 같은 무프롬프트 git — `refGit`/`runRefGit` 재사용). 캐시 폴더를 돌려준다.
  - `GitOpsService.parents(File dir, String sha) : List<String>`(`git rev-list --parents -n 1 <sha>`의 둘째 칸부터), `diffNameStatus(File dir, String before, String after) : List<String>`(`git diff --name-status <before> <after>` 줄 그대로), `remoteBranchTip(RepoRef ref, String branch) : String`(`git ls-remote <인증 URL> refs/heads/<branch>`의 sha, 못 읽으면 null — 예외·출력은 `GitRemotes.mask`).

- [ ] **Step 1: 실패하는 시험 쓰기**

```java
// PatchBaseTest
recordedBaseEqualToFirstParentIsAccepted()            // merge 커밋(부모 2)·squash 커밋(부모 1) 둘 다
recordedBaseDifferentFromFirstParentFails()           // 메시지에 두 sha 앞 7자
noRecordedBaseAndTwoParentsUsesFirstParent()
noRecordedBaseAndSingleParentFails()
blankMergeShaFails() / emptyParentsFails()
// DeployPrebuilderTest
buildOnlyReturnsDeliveryFileWhenPresent()             // .netis-artifact/x.zip
buildOnlyFallsBackToArtifactPattern()
buildOnlyDoesNotCreateStageDir()                      // .netis-prebuild 없음
buildOnlyFailsWithTwoDeliveryFiles()
// WorktreeKindTest
parsesPatchKinds(); existingKindsUnchanged()          // "task-3","deploy-3","design-3"
// GitOpsServicePatchTest (@TempDir에 git init → 커밋 2개 + merge 커밋)
parentsOfMergeCommitHasTwo(); parentsOfRootIsEmpty(); diffNameStatusListsChangedFile()
```

- [ ] **Step 2: 실패 확인** — 위 클래스 실행.
- [ ] **Step 3: 구현.** `WorktreeCleanupPlanner`가 새 종류를 기존 7일 규칙으로 다루는지 시험으로 확인한다.
- [ ] **Step 4: 통과 확인** — 위 명령 + `--tests 'com.hamonsoft.netismaker.workerdaemon.WorktreeCleanupPlannerTest' --tests '…DeployServiceTest'` PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 패치용 빌드(스테이징 없음)·전 커밋 검증·커밋 받기와 worktree 종류"`

---

### Task 8: 패치 빌더와 종료 연결 (워커)

**Files:**
- Create: `workerdaemon/patch/PatchBuilder.java`, `workerdaemon/patch/ClosePatchWorkerProperties.java`
- Modify: `workerdaemon/TaskCloser.java`, `WorkerHttpClient.java`, `src/main/resources/application-worker.yml`, `NetisMakerApplication.java`
- Test: `workerdaemon/patch/PatchBuilderTest.java`, `workerdaemon/TaskCloserTest.java`, `workerdaemon/WorkerHttpClientPatchTest.java`

**Interfaces:**
- Consumes: Task 3 `ClosePatchSpec`·`PatchInfo`·`CloseProgressRequest`(새 칸)·`task.closePatch()`, Task 5~7 전부.
- Produces:
  - `@ConfigurationProperties(prefix="netis-maker.worker.close.patch") record ClosePatchWorkerProperties(List<String> ignore, Duration uploadTimeout, Integer keepManifests)` — 기본 `["META-INF/MANIFEST.MF","META-INF/maven/**"]`, 10m, 20. yml: `ignore: ${CLOSE_PATCH_IGNORE:META-INF/MANIFEST.MF,META-INF/maven/**}`, `upload-timeout: ${CLOSE_PATCH_UPLOAD_TIMEOUT:10m}`. `prebuild.timeout` 주석에 API `close-patch-stale-threshold-minutes`를 가리키는 줄을 더한다.
  - `WorkerHttpClient.uploadPatch(Long taskId, int fileCount, Path file, String sha256, Duration timeout) : PatchInfo`, `currentPatch(Long taskId) : Optional<PatchInfo>`(404 → empty).
  - `PatchBuilder`(`@Component @Profile("worker")`): `record Outcome(boolean empty, int fileCount)`; `Outcome build(WorkerTaskResponse task, String mergeSha) throws PatchException, InterruptedException`. 테스트용 package-private 생성자는 빌드(`BuildFn: (PrebuildSpec, Path) -> Path`)와 git(`GitFn`)을 인터페이스로 받는다.
  - `PatchBuilder.build` 순서:
    1. `http.currentPatch(id)`가 있으면 빌드 없이 `Outcome(false, info.fileCount())`.
    2. `cache.fetchPatchCommits(ref, task.githubBranch(), mergeSha)` → `git.parents` → `PatchBase.resolve(mergeSha, task.closePatch().baseSha(), parents)` = before.
    3. `key = PatchManifestStore.key(command, toolchain, artifact, ignore)`; `beforeTree = manifests.load(repoKey, before, key)` — 없으면 `PATCH_BEFORE` worktree에서 `buildOnly` → `scanner.scan`.
    4. `PATCH_AFTER` worktree에서 `buildOnly` → `afterTree = scan`.
    5. `delta = PatchDiff.diff(beforeTree, afterTree)`; `manifests.save(repoKey, mergeSha, key, afterTree)`(delta가 비어도 저장).
    6. 비었으면 `Outcome(true, 0)`. 아니면 `scanner.extract(afterArtifact, added+changed, work/patch)` → `PatchZipWriter.write`(`PatchInfoDoc`: 제목 `task.title()`, 별칭 `task.closeContext().repoAlias()`(없으면 `task.githubRepo()`), 시각은 서울 시간대, `sourceChanges = git.diffNameStatus(cacheDir, before, mergeSha)` — 못 읽으면 빈 목록) → sha256 → `http.uploadPatch` → `Outcome(false, delta.fileCount())`.
    7. finally: 두 worktree `discard`, 작업 폴더(`reposDir` 형제 `close/task-{id}/patch`) 삭제. 빌드는 전 → 후 차례로(동시 실행 없음).
  - 예외 변환: `PrebuildException` → `PatchException("패치 빌드 실패(전|후): " + 메시지 + 로그 끝)`, `IOException` → `PatchException`(마스킹).
  - `TaskCloser.process` 변경(패치 대상 = `task.closePatch() != null && !done.contains(PATCH_BUILT)`):
    - 병합 전 확인(`requireVault()` 옆, `MERGED` 미완료일 때): `closeProps.mergeMethod()`가 `rebase`이고 호스트가 GitHub이면 `StepFailed("부분 패치는 rebase 병합을 지원하지 않습니다")`; `!prebuilder.toolchainConfigured(spec.toolchain())`이면 `StepFailed("부분 패치 빌드 도구가 워커에 설정되지 않았습니다: " + 이름)`.
    - 병합 직전 `baseSha = gitOps.remoteBranchTip(ref, task.githubBranch())`(null 허용) → 병합 → `MERGED` 진행 보고에 `baseSha` 포함.
    - `MERGED` 다음, `UNDEPLOYED` 앞: `Outcome o = step("부분 패치", () -> patchBuilder.build(task, mergeSha))` → `PATCH_BUILT` 진행 보고(`patchEmpty = o.empty()`, `patchFileCount = o.fileCount()`).
    - 재시도 claim(`MERGED` 완료)에서는 `mergeSha`가 `closeContext().mergeCommitSha()`에서 온다.
  - `TaskCloser` 생성자에 `PatchBuilder`, `DeployPrebuilder`, `GitOpsService`를 더하고 기존 테스트 생성자는 null 허용 형태로 남긴다(패치 대상이 아니면 쓰지 않는다).

- [ ] **Step 1: 실패하는 시험 쓰기**

```java
// PatchBuilderTest (가짜 BuildFn이 @TempDir에 작은 war를 만들어 돌려준다, http·manifests는 mock/실물)
buildsBothSidesAndUploadsOnlyChangedEntries()   // 업로드한 zip에 patch/WEB-INF/classes/A.class만, fileCount 1
emptyDeltaUploadsNothing()                      // uploadPatch 호출 없음, Outcome(true, 0), manifests.save는 호출
cachedBeforeManifestSkipsBeforeBuild()          // BuildFn 호출 1회(after만)
retryAfterUploadSkipsBuild()                    // currentPatch 있음 → BuildFn 0회
baseMismatchFailsBeforeAnyBuild()               // PatchBase 메시지, BuildFn 0회
shapeMismatchFailsWithFixedMessage()
beforeBuildFailureIsReportedAsBefore()          // 메시지에 "(전)"
worktreesAndWorkDirAreRemovedOnFailure()
// TaskCloserTest
patchStepRunsRightAfterMergeAndBeforeUndeploy() // 진행 보고 순서 MERGED, PATCH_BUILT, UNDEPLOYED, …
mergedProgressCarriesBaseSha()
rebaseMethodFailsBeforeMergeWhenPatchRequired() // merge 호출 없음, 고정 문구
missingToolchainFailsBeforeMerge()
patchFailureReportsCloseFailedAndKeepsMerged()
noPatchSpecLeavesFlowUnchanged()                // 기존 진행 보고 순서·바디 그대로(baseSha null)
// WorkerHttpClientPatchTest (WorkerHttpClientArtifactTest와 같은 준비)
uploadPatchSendsShaHeaderAndFileCount(); currentPatch404IsEmpty()
```

- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.workerdaemon.*'` PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 종료 때 전·후 빌드를 비교해 부분 패치를 만들어 올린다"`

---

### Task 9: 화면 — 상세·목록 버튼, 카탈로그 체크

**Files:**
- Create: `frontend/composables/useOneTimeDownload.ts`
- Modify: `frontend/composables/taskClose.ts`, `frontend/pages/tasks/[id].vue`, `frontend/pages/tasks/index.vue`, `frontend/components/tasks/TaskCardCompact.vue`, `frontend/pages/admin/repo-catalog.vue`, `frontend/scripts/hydration-smoke.mjs`
- Test: `frontend/composables/taskClose.spec.ts`, `frontend/test/task-detail-close.spec.ts`, `frontend/test/tasks-index-patch.spec.ts`(새), `frontend/components/tasks/TaskCardCompact.spec.ts`, `frontend/test/repo-catalog-admin.spec.ts`

**Interfaces:**
- Consumes: Task 3 응답 `close.patchMode`, `close.patch { name, sizeBytes, fileCount, empty, uploadedAt }`, `POST /api/tasks/{id}/patch/download-link`, `POST /api/tasks/{id}/close/skip-patch`; Task 1 카탈로그 `patchEnabled`.
- Produces(`taskClose.ts`):
  - `export interface ClosePatchView { name: string | null; sizeBytes: number | null; fileCount: number | null; empty: boolean; uploadedAt: string | null }`; `CloseView`에 `patchMode?: 'REQUIRED' | 'SKIPPED' | null`, `patch?: ClosePatchView | null`.
  - `CLOSE_STEPS`에 `{ key: 'PATCH_BUILT', label: '부분 패치' }`를 `MERGED` 다음에. `closeSteps`는 `patchMode`가 null이면 이 단계를 숨기고 `SKIPPED`면 `'skipped'`.
  - `export function canSkipPatch(close: CloseView | null, status: string): boolean` — `CLOSE_FAILED` + `patchMode === 'REQUIRED'` + 진행에 `PATCH_BUILT` 없음.
  - `export type PatchButtonState = 'download' | 'empty' | 'none'`; `export function patchButtonState(close: CloseView | null | undefined): PatchButtonState` — `patch?.empty` → `'empty'`, `patch?.name` → `'download'`, 그 밖 `'none'`.
  - `export async function startPatchDownload(taskId, api, go?)` — `startArtifactDownload`와 같은 모양, 주소만 `/api/tasks/${taskId}/patch/download-link`.
- Produces(`useOneTimeDownload.ts`): `export function useOneTimeDownload(start: (id: number | string) => Promise<void>, cooldownMs = 2000): { busy: Ref<boolean>; run: (id: number | string) => Promise<void> }` — 진행 중·쿨다운 중 재호출은 무시, 언마운트 때 타이머 정리. `[id].vue`의 산출물 내려받기도 이것으로 바꾼다(동작 동일: 410이면 `보관 파일이 정리되었습니다`).
- 화면 규칙:
  - 상세: 종료 카드에 `data-test="close-patch"` 줄 — `download`면 파일 이름·`formatMb`·`파일 N개`와 버튼 `부분 패치`(`data-test="close-patch-download"`, 등록자·관리자), `empty`면 글자 `변경 없음`. `canSkipPatch`면 관리자에게 `패치 없이 마무리`(데스크톱 `data-test="close-skip-patch"`, 모바일 더보기 `bar-more-close-skip-patch`) → `confirmDialog($q, …)` → `closeAction('close/skip-patch', '패치 없이 마무리 — 종료 큐 등록')`.
  - 목록 데스크톱(`index.vue`의 인라인 카드, `card-foot`): 상태 `CLOSED`이고 `patchButtonState`가 `download`면 작은 버튼 `부분 패치`(`data-test="card-patch-download"`, `@click.stop`), `empty`면 회색 글자 `변경 없음`(`data-test="card-patch-empty"`). 등록자·관리자에게만. `index.vue`의 `TaskResponse`에 `close?: CloseView | null`.
  - 목록 모바일(`TaskCardCompact`): `CardTask`에 `close?: { patch?: { name: string | null; empty: boolean } | null } | null`(로컬 선언), 같은 버튼·글자를 `card-meta` 줄에. 버튼은 `emit('patch')`(`@click.stop`) → `TaskListMobile`이 `patch(task)`로 올리고 `index.vue`가 내려받는다.
  - 카탈로그: 체크 `부분 패치 만들기`(`data-test="form-patch-enabled"`)를 미리 빌드 묶음 **밖**에 두고, `prebuildCommand`가 비면 비활성 + 안내 `미리 빌드 명령이 있어야 켤 수 있습니다`. 명령 입력칸은 `prebuildEnabled || patchEnabled`일 때 보이게 넓힌다. 저장 바디에 `patchEnabled`를 늘 싣는다. 목록 칩 `부분 패치`(`data-test="catalog-patch-chip"`).
  - `hydration-smoke.mjs`: `TASKS`에 `status: 'CLOSED'`·`close.patch`가 있는 작업 하나, 경로 목록에 그 상세.

- [ ] **Step 1: 실패하는 시험 쓰기**

```ts
// taskClose.spec.ts
it('패치 모드가 없으면 부분 패치 단계를 숨긴다')        // closeSteps(...).map(s => s.key) 에 PATCH_BUILT 없음
it('패치 단계는 병합 다음이다')                          // ['MERGED','PATCH_BUILT','UNDEPLOYED', …]
it('SKIPPED면 건너뜀으로 보인다')
it('canSkipPatch는 종료실패 + REQUIRED + 미완료일 때만')
it('patchButtonState: 파일 있음 download / empty / none')
it('startPatchDownload는 patch/download-link를 부른다')
// task-detail-close.spec.ts
it('패치 파일이 있으면 부분 패치 버튼을 보이고 누르면 내려받는다')
it('변경 없음이면 버튼 대신 글자를 보인다')
it('관리자에게 패치 없이 마무리를 보이고 skip-patch를 부른다')
it('연속으로 두 번 눌러도 링크 요청은 한 번이다')
// tasks-index-patch.spec.ts
it('작업종료 카드에 부분 패치 버튼이 있고 카드 이동 없이 내려받는다')
it('패치가 없는 작업종료 카드에는 아무것도 없다')
it('다른 사람의 작업이면 버튼을 보이지 않는다')
// TaskCardCompact.spec.ts
it('patch 버튼은 open을 내지 않고 patch를 낸다')
// repo-catalog-admin.spec.ts
it('미리 빌드 명령이 비면 부분 패치 체크가 비활성이다')
it('저장 바디에 patchEnabled를 싣는다')
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run composables/taskClose.spec.ts test/task-detail-close.spec.ts test/tasks-index-patch.spec.ts components/tasks/TaskCardCompact.spec.ts test/repo-catalog-admin.spec.ts`.
- [ ] **Step 3: 구현.** Quasar 반응형 헬퍼 이름을 클래스로 쓰지 않는다.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 이어서 `npx vitest run`(전체)과 `npm run lint`(오류 0, 경고가 늘지 않음).
- [ ] **Step 5: Commit** — `git commit -m "feat(patch): 작업 목록·상세의 부분 패치 버튼과 카탈로그 켜기"`

---

### Task 10: 실제 레포 확인·문서·전체 시험

**Files:**
- Create: `src/test/java/com/hamonsoft/netismaker/workerdaemon/patch/PatchRealArtifactCheck.java`
- Modify: `CLAUDE.md`(종료 문단·자주 보는 코드 표), `docs/superpowers/specs/2026-11-10-close-partial-patch-design.md`(상태 줄, 계획에서 달라진 두 가지)

- [ ] **Step 1: 실제 산출물 확인 시험** — `@EnabledIfEnvironmentVariable(named = "PATCH_CHECK_A", matches = ".+")`. env `PATCH_CHECK_A`·`PATCH_CHECK_B`(같은 커밋을 두 번 빌드한 산출물 경로)를 기본 무시 패턴으로 scan → `PatchDiff.diff(a, b).isEmpty()`가 참. 거짓이면 다른 경로 목록을 실패 메시지에 싣는다.
- [ ] **Step 2: 실제 레포로 실행** — 카탈로그 13(출입국 대시보드)이나 5(제2철도)의 워커 레포 캐시에서 임시 worktree 둘을 같은 커밋으로 만들고, 카탈로그의 미리 빌드 명령을 `public.env`의 `PREBUILD_JDK8_JAVA_HOME`·`PREBUILD_JDK8_PATH`로 각각 돌려 war 두 개를 얻는다(라이브 워커가 배포·종료 중이 아닐 때, `~/.m2` 경합을 피하려고 차례로). `PATCH_CHECK_A=… PATCH_CHECK_B=… ./gradlew test --tests '…PatchRealArtifactCheck'` → PASS. 차이가 남으면 그 경로를 보고 무시 패턴 기본값(Task 8 yml·properties, 스펙 §6.4)에 더하고 다시 돌린다. 이어서 자바 파일 하나만 고친 커밋과 그 부모로 같은 일을 해 `diff`에 기대한 `.class`(와 내부 클래스)만 나오는지 눈으로 확인하고 결과를 커밋 메시지에 적는다. 임시 worktree는 지운다.
- [ ] **Step 3: CLAUDE.md** — 종료 문단에 부분 패치 절(대상 판정 3조건, 단계 순서, 전·후 빌드 비교, 검증 실패·구성 불일치는 종료실패 → `패치 없이 마무리`, 보관 위치 `attachments/task-{id}/patch/patch.zip`, 내려받기, 판정 110분, 배포 순서 워커 → API(V43, 스위치 꺼진 채) → 프론트 → `CLOSE_PATCH_ENABLED=true` → 카탈로그), "자주 보는 코드" 표에 한 줄.
- [ ] **Step 4: 전체 시험** — `./gradlew test`(전부 PASS, Testcontainers 시험은 건너뜀으로 표시), `cd frontend && npx vitest run`, `npm run build && npm run test:hydration`.
- [ ] **Step 5: Commit** — `git commit -m "docs(patch): 부분 패치 운영 규칙과 실제 레포 확인 시험"`
