# 병합하지 않고 종료(브랜치·PR 유지) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 종료 승인 때 "병합하지 않고 종료"를 고르면 PR·작업 브랜치를 남긴 채 배포 중지·산출물·노트만 하고 작업종료로 닫는다.

**Architecture:** 승인 바디 `keepBranch` → `task.close_keep_branch`(V49) → `CloseStep.required`가 MERGED·BRANCH_DELETED를 뺀다 → claim `closeKeepBranch` → `TaskCloser`가 브랜치 존재 확인 뒤 병합·삭제를 건너뛴다. 옛 워커는 `supports=closeKeepBranch` 가드로 이 작업을 받지 못한다. 화면은 종료 승인 확인창을 radio 선택창으로 바꾼다.

**Tech Stack:** Java 21 / Spring Boot / JPA·Flyway(PostgreSQL), JUnit 5·Mockito·Testcontainers, Nuxt 3 + Quasar, vitest.

**Spec:** `docs/superpowers/specs/2026-11-23-close-keep-branch-design.md`

## Global Constraints

- 기본값은 지금과 같은 병합 종료 — 바디 없음/`keepBranch` false·null이면 동작이 바이트까지 같아야 한다(기존 시험이 그대로 통과).
- 새 칸: `com.task.close_keep_branch BOOLEAN NOT NULL DEFAULT false`, 파일 `V49__close_keep_branch.sql`(병합 직전 `origin/main` 최대 번호 재확인).
- supports 토큰 이름: `closeKeepBranch`. `WorkerHttpClient.SUPPORTS` = `"refRepos,fixRounds,dbWrite,mockups,baseSync,closeKeepBranch"`.
- 문구(그대로): 이력 접미사 ` (병합 안 함·브랜치 유지)` / 취소 거절 `이미 진행된 단계가 있어 종료를 취소할 수 없습니다 — 재시도로 마무리하세요` / 반영 거절 `병합하지 않는 종료에는 기준 브랜치 반영이 필요 없습니다` / 워커 실패 `브랜치 유지: 작업 브랜치 {head_branch}를 원격에서 찾지 못했습니다 — 브랜치가 남아 있는지 확인하세요` / 노트 프롬프트 `병합 안 함 — 작업 브랜치 {head_branch}와 PR 유지(Symphony 밖에서 이어서 처리)` / 노트 프런트매터 `branch_kept: <head_branch>` / 진행상황 줄 `(병합 안 함 · 브랜치 유지)` / 칩 `미병합 종료 · 브랜치 유지` / 선택지 `병합하고 종료 — PR을 {기준 브랜치}에 병합 · 배포 중지 · 노트 · 브랜치 삭제`, `병합하지 않고 종료 — 브랜치·PR 유지 · 배포 중지 · 노트`, 확인 버튼 `종료 승인`.
- 프론트: 작은따옴표·세미콜론 없음, 네이티브 `window.confirm` 금지(Quasar Dialog), `$q.screen.lt.md` 분기.
- 백엔드 시험은 이 PC에서 `cmd /c "cd /d <worktree> && set JAVA_HOME=C:\Users\mic\.jdks\jdk-21.0.12.1+1 && .\gradlew.bat test --no-daemon --tests <클래스>"`(라이브 스택이 Gradle 데몬을 쓰므로 `--no-daemon`, `gradlew --stop` 금지). Testcontainers 시험은 이 PC에서 건너뛰고 CI가 돌린다. 프론트는 worktree `frontend/node_modules`를 본 체크아웃으로의 정션으로 두고 `npx vitest run <파일>`(worktree에서 `npm ci` 금지).

## Review Focus

1. 옛 화면(바디 없는 `POST close/approve`) → 병합 종료 그대로(close_keep_branch false). — Task 2 시험 `approve_바디없음_병합종료`.
2. 브랜치 유지 종료 재시도·stale 재큐 → 모드 유지(병합하지 않음). — Task 2 시험 `retry_브랜치유지_모드유지`, Task 4 시험(재claim에서도 merge 미호출).
3. 원격 브랜치를 읽지 못함(네트워크 오류 포함) → 배포 중지 전에 실패, 종료 취소 가능. — Task 4 `브랜치없음_배포중지전실패`.
4. 옛 워커(supports에 `closeKeepBranch` 없음)가 claim → 브랜치 유지 종료대기는 건너뛰고 일반 종료대기는 받음. — Task 3 통합 시험.
5. 배포한 적 없는 DB 작업을 브랜치 유지로 종료 → 배포 중지는 실행 없이 완료, 노트 후 작업종료. — Task 4 `DB작업_배포없음_브랜치유지`.

---

### Task 1: 데이터 칸과 종료 단계 규칙

**Files:**
- Create: `src/main/resources/db/migration/V49__close_keep_branch.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/Task.java` (종료 칸들 옆), `src/main/java/com/hamonsoft/netismaker/entity/CloseStep.java`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/CloseStepTest.java`

**Interfaces:**
- Produces: `Task.isCloseKeepBranch(): boolean`, `Task.setCloseKeepBranch(boolean)` (Lombok, `@Column(name = "close_keep_branch", nullable = false) private boolean closeKeepBranch`). `CloseStep.required(String artifactMode, String patchMode, boolean keepBranch)`, `CloseStep.allDone(String csv, String artifactMode, String patchMode, boolean keepBranch)`. 기존 2·3인자 `required`·`allDone`은 `keepBranch=false`로 위임해 남긴다.

- [ ] **Step 1: 실패하는 시험** — `CloseStepTest`에 `required_브랜치유지면_병합과_브랜치삭제를_뺀다`(`required(null, null, true)` == `{UNDEPLOYED, NOTE_WRITTEN}`; `required("REQUIRED", null, true)`에 `ARTIFACT_UPLOADED` 포함), `allDone_브랜치유지_병합없이_완료`(`allDone("UNDEPLOYED,NOTE_WRITTEN", null, null, true)` 참, 같은 csv에 `keepBranch=false`는 거짓).
- [ ] **Step 2: 실행해 실패 확인** — `--tests '*CloseStepTest'` → 컴파일 실패(메서드 없음).
- [ ] **Step 3: 구현** — 마이그레이션 `ALTER TABLE com.task ADD COLUMN IF NOT EXISTS close_keep_branch BOOLEAN NOT NULL DEFAULT false;` + 엔티티 칸 + `CloseStep` 오버로드.
- [ ] **Step 4: 통과 확인** — `--tests '*CloseStepTest'` PASS.
- [ ] **Step 5: 커밋** — `feat: 종료 브랜치 유지 칸(V49)과 단계 규칙`.

### Task 2: API — 승인 선택·종료 규칙·응답

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/ApproveCloseRequest.java`
- Modify: `controller/TaskCloseController.java`(approve), `service/TaskCloseService.java`(approve·cancel·syncBase·skipArtifact), `service/WorkerService.java`(close-progress 검증 1072행 부근, 종료 결과 `allDone` 1191행 부근), `dto/TaskResponse.java`(`CloseView`)
- Test: `service/TaskCloseServiceTest.java`, `service/TaskCloseServiceBaseSyncTest.java`, `service/WorkerServiceCloseTest.java`, 컨트롤러 시험이 있으면 그 파일(없으면 `TaskCloseServiceTest`만)

**Interfaces:**
- Consumes: Task 1의 `isCloseKeepBranch`, `CloseStep.allDone(…, keepBranch)`.
- Produces: `record ApproveCloseRequest(Boolean keepBranch)`; 컨트롤러 `approve(@PathVariable Long id, @RequestBody(required = false) ApproveCloseRequest body, …)`; `TaskCloseService.approve(Long taskId, String adminId, boolean keepBranch)`(기존 2인자는 false로 위임). `TaskResponse.CloseView`에 마지막 칸 `boolean keepBranch`(JSON `close.keepBranch`).

- [ ] **Step 1: 실패하는 시험** — `TaskCloseServiceTest`:
  - `approve_바디없음_병합종료`: `approve(id, admin)` 뒤 `isCloseKeepBranch()==false`, 패치 판정 기존과 같음, 이력 사유에 `병합 안 함` 없음.
  - `approve_브랜치유지`: 패치 스위치·카탈로그를 켠 상태에서 `approve(id, admin, true)` → keep true, `closePatchMode==null`, 산출물 모드는 기존 판정 그대로, 이력 사유가 ` (병합 안 함·브랜치 유지)`로 끝남.
  - `cancel_브랜치유지_진행없음_허용`(keep, progress null → 직전 상태, keep false로 되돌림) / `cancel_브랜치유지_단계있음_409`(progress `UNDEPLOYED` → 409, 메시지는 Global Constraints 문구).
  - `skipArtifact_브랜치유지_병합없이_허용`(keep, artifactMode REQUIRED, progress `UNDEPLOYED`, CLOSE_FAILED → CLOSE_PENDING, SKIPPED). 병합 종료의 기존 조건 시험은 그대로.
  - `retry_브랜치유지_모드유지`(keep 그대로).
  - `TaskCloseServiceBaseSyncTest`: `syncBase_브랜치유지_409`(문구 그대로).
  - `WorkerServiceCloseTest`: `progress_브랜치유지_MERGED_409`, `progress_브랜치유지_BRANCH_DELETED_409`(이때 `rebaseDependents`가 불리지 않음), `closed_브랜치유지_병합없이_완료`(progress `UNDEPLOYED,NOTE_WRITTEN` + CLOSED 보고 → 작업종료).
- [ ] **Step 2: 실패 확인** — `--tests '*TaskCloseService*' --tests '*WorkerServiceCloseTest'` → 실패.
- [ ] **Step 3: 구현** — 위 시그니처. `approve`의 패치 판정은 `keepBranch ? null : (patchRequired(t) ? REQUIRED : null)`. `cancel`: keep이면 `progress` 비었을 때만, 아니면 기존 MERGED 규칙. `skipArtifact`: `!done.contains(MERGED)` 검사를 `!t.isCloseKeepBranch() && …`로. `syncBase`: keep이면 409(상태 검사 다음). 진행 보고: keep이고 step이 MERGED·BRANCH_DELETED면 `TaskException(CONFLICT, "병합하지 않는 종료입니다 — " + step + " 보고를 받지 않습니다")`. 종료 결과: `allDone(…, t.isCloseKeepBranch())`. `CloseView` 생성에 `t.isCloseKeepBranch()`.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 그리고 `--tests '*Close*'` 전체 PASS(기존 시험 회귀 없음).
- [ ] **Step 5: 커밋** — `feat: 종료 승인 브랜치 유지 선택과 종료 규칙(API)`.

### Task 3: claim — 모드 전달과 옛 워커 가드

**Files:**
- Modify: `service/WorkerCapabilities.java`, `controller/WorkerController.java`(SUPPORTS 상수·caps 생성 102행 부근), `repository/TaskRepository.java`(`findClaimableAmong`·`findClaimableAmongForUpdateSkipLocked`), `service/WorkerService.java`(caps 필터 248·254행, `forClose` 호출 343행), `dto/WorkerTaskResponse.java`
- Test: `service/WorkerCapabilitiesTest.java`, Create `repository/TaskClaimCloseKeepBranchSupportIntegrationTest.java`(기존 `TaskClaimFixRoundSupportIntegrationTest` 패턴, Testcontainers — CI), `service/WorkerServiceCloseTest.java`(claim 칸)

**Interfaces:**
- Consumes: Task 1의 칸.
- Produces: `WorkerCapabilities(boolean refRepos, boolean fixRounds, boolean dbWrite, boolean mockups, boolean baseSync, boolean closeKeepBranch)` + 기존 5인자 생성자는 `closeKeepBranch=false`로 위임, `ALL`은 전부 true, `needsFilteredQueue()`에 `closeKeepBranch` 포함. `WorkerController.SUPPORTS_CLOSE_KEEP_BRANCH = "closeKeepBranch"`. 네이티브 쿼리 파라미터 `includeCloseKeepBranch`와 조건 `AND (CAST(:includeCloseKeepBranch AS boolean) OR NOT (t.status = :closePending AND t.close_keep_branch))`, 래퍼 `findClaimableAmongForUpdateSkipLocked(statuses, supportsRefRepos, supportsFixRounds, supportsMockups, supportsBaseSync, supportsCloseKeepBranch)`(기존 5인자 래퍼는 false 위임). `WorkerTaskResponse`에 마지막 칸 `Boolean closeKeepBranch`(기존 정규 생성자는 null로 위임하는 호환 생성자로 남김), `forClose(t, ctx, artifact, patch)`가 `t.isCloseKeepBranch()`를 싣는다.

- [ ] **Step 1: 실패하는 시험** — `WorkerCapabilitiesTest`: `closeKeepBranch만_모르면_필터큐`(`new WorkerCapabilities(true,true,true,true,true,false).needsFilteredQueue()` 참, `ALL` 거짓). 통합 시험: keep 종료대기 A(먼저 생성)·일반 종료대기 B → `supportsCloseKeepBranch=false` claim이 B, `true`면 A. `WorkerServiceCloseTest`: keep 작업 claim 응답 `closeKeepBranch()==true`, 일반은 false.
- [ ] **Step 2: 실패 확인** — `--tests '*WorkerCapabilitiesTest' --tests '*WorkerServiceCloseTest'` 실패.
- [ ] **Step 3: 구현** — 위 Interfaces. `TaskRepositoryClaimableStatusesTest`가 그대로 통과하는지 확인(상태 목록은 안 바뀜).
- [ ] **Step 4: 통과 확인** — 같은 명령 + `--tests '*Claim*' --tests '*WorkerController*'` PASS(통합 시험은 이 PC에서 건너뜀).
- [ ] **Step 5: 커밋** — `feat: 종료 브랜치 유지 claim 칸과 구버전 워커 가드`.

### Task 4: 워커 — 병합·삭제 건너뛰기, 브랜치 확인, 노트·메일

**Files:**
- Modify: `workerdaemon/WorkerHttpClient.java`(SUPPORTS), `workerdaemon/TaskCloser.java`(`process` 141~182행), `workerdaemon/CloseNotePrompt.java`(`render`), `workerdaemon/CloseNoteWriter.java`(`NoteMeta`·`frontmatter`·`appendProgress`), `service/CloseMailService.java`(200행 부근 병합 커밋 줄)
- Test: `workerdaemon/TaskCloserTest.java`, `workerdaemon/CloseNotePromptTest.java`, `workerdaemon/CloseNoteWriterTest.java`, `workerdaemon/WorkerHttpClientNextTaskTest.java`, `service/CloseMailServiceTest.java`

**Interfaces:**
- Consumes: Task 3의 `WorkerTaskResponse.closeKeepBranch()`; `GitOpsService.remoteBranchTip(RepoRef, String): String`(없거나 못 읽으면 null).
- Produces: `CloseNoteWriter.NoteMeta`에 `String branchKept`(null이면 병합 종료 — 기존 생성자는 null 위임).

- [ ] **Step 1: 실패하는 시험**
  - `TaskCloserTest`: `브랜치유지_병합과_삭제를_건너뛴다`(closeKeepBranch true, remoteBranchTip이 sha → closer.merge·deleteBranch·retargetDependents 미호출, 보고 순서 UNDEPLOYED→NOTE_WRITTEN→CLOSED). `브랜치없음_배포중지전실패`(remoteBranchTip null → closeFailed 사유가 Global Constraints 문구, undeploy 미호출, progress 보고 없음). `재claim_UNDEPLOYED완료면_브랜치확인안함`(progress `UNDEPLOYED` → remoteBranchTip 미호출, 노트부터). `DB작업_배포없음_브랜치유지`(neverDeployed → undeploy 실행 없이 UNDEPLOYED 보고 후 CLOSED). `병합종료_기존동작`(false/null이면 merge·deleteBranch 호출 — 기존 시험이 덮으면 추가 불필요).
  - `CloseNotePromptTest`: keep이면 `{merge_sha}` 자리가 `병합 안 함 — 작업 브랜치 <head>와 PR 유지(Symphony 밖에서 이어서 처리)`.
  - `CloseNoteWriterTest`: `branchKept`가 있으면 프런트매터에 `merge_commit: ` 빈 값과 `branch_kept: <head>`, 진행상황 줄이 `— 작업 #N 종료(병합 안 함 · 브랜치 유지)`. 없으면 기존 그대로.
  - `WorkerHttpClientNextTaskTest`: next-task URL의 `supports=refRepos,fixRounds,dbWrite,mockups,baseSync,closeKeepBranch`.
  - `CloseMailServiceTest`: keep 작업 메일 본문의 병합 커밋 줄이 `병합 커밋: 병합 안 함(브랜치 유지)`.
- [ ] **Step 2: 실패 확인** — `--tests '*TaskCloserTest' --tests '*CloseNote*' --tests '*WorkerHttpClientNextTaskTest' --tests '*CloseMailServiceTest'` 실패.
- [ ] **Step 3: 구현** — `process`: `boolean keep = Boolean.TRUE.equals(task.closeKeepBranch())`. keep이면 패치·rebase 검사·병합 블록을 건너뛰고, `!done.contains(UNDEPLOYED)`이면 그 앞에서 `gitOps.remoteBranchTip(ref, task.headBranch())`가 null이면 `StepFailed`(문구 그대로). 브랜치 삭제 블록(그 안의 `retargetDependents`)은 `!keep`일 때만. `writeNote`가 keep이면 `NoteMeta.branchKept = task.headBranch()`, 프롬프트 render에 keep 정보 전달(`render(template, t, mergeSha)`가 `t.closeKeepBranch()`를 보고 `merge_sha` 값을 바꾼다). 로그 `작업 종료 완료: task={} merge={}`는 keep이면 `merge=없음(브랜치 유지)`.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + `--tests '*workerdaemon*'` 회귀 없음.
- [ ] **Step 5: 커밋** — `feat: 워커 종료 브랜치 유지(병합·삭제 건너뛰기, 브랜치 확인, 노트·메일 문구)`.

### Task 5: 화면 — 종료 승인 선택창·표시·버튼 규칙, 문서

**Files:**
- Create: `frontend/composables/closeApprove.ts`, `frontend/composables/closeApprove.spec.ts`
- Modify: `frontend/composables/taskClose.ts`(`CloseView`·`closeSteps`·`canSkipArtifact`), `frontend/composables/taskFix.ts`(`canSyncBase` 175행), `frontend/pages/tasks/[id].vue`(`requestClose`·`approveClose` 443~458행, `canCancelClose` 471행, 종료 단계 카드 근처 칩), `frontend/pages/tasks/index.vue`(`applyMove` 181~205행), 작업종료 카드 칩이 그려지는 컴포넌트(`components/tasks/TaskCardCompact.vue`·`TaskDetailMobile.vue` 중 종료 표시가 있는 곳), `frontend/scripts/hydration-smoke.mjs`(작업 응답 목 `close.keepBranch: false`), `CLAUDE.md`(작업 종료 문단에 브랜치 유지 종료·배포 순서)
- Test: `frontend/composables/taskClose.spec.ts`, `frontend/composables/taskFix.spec.ts`(있으면, 없으면 `taskClose.spec.ts`에), `frontend/test/task-detail-close.spec.ts`, `frontend/test/tasks-index-mobile.spec.ts`(종료 승인 경로)

**Interfaces:**
- Consumes: 응답 `close.keepBranch: boolean`(Task 2), 승인 바디 `{ keepBranch: true }`.
- Produces: `closeApprove.ts` — `export type CloseMode = 'MERGE' | 'KEEP_BRANCH'`; `export function closeApproveBody(mode: CloseMode): { keepBranch: true } | undefined`(MERGE면 undefined → 기존처럼 바디 없음); `export function chooseCloseMode($q: QVueGlobals, opts: { taskId: number; baseBranch: string | null; extraWarning?: string }): Promise<CloseMode | null>`(Quasar `$q.dialog` + `options: { type: 'radio', model: 'MERGE', items: [...] }`, 제목 `종료 승인 — 작업 #N`, 본문에 `병합·배포 중지·브랜치 삭제는 되돌릴 수 없습니다.` + extraWarning, ok `종료 승인`, cancel `취소`, persistent, 취소면 null). `taskClose.ts` — `CloseView.keepBranch?: boolean`, `export function isKeepBranchClose(close: CloseView | null): boolean`, `export const KEEP_BRANCH_CHIP = '미병합 종료 · 브랜치 유지'`.

- [ ] **Step 1: 실패하는 시험**
  - `closeApprove.spec.ts`: `closeApproveBody('MERGE')`는 undefined, `('KEEP_BRANCH')`는 `{ keepBranch: true }`. `chooseCloseMode`가 `$q.dialog`에 넘기는 옵션(라디오 두 항목의 label이 Global Constraints 문구, 기준 브랜치 이름 치환, 기본 model `MERGE`)을 가짜 `$q`로 확인하고 onOk('KEEP_BRANCH') → 'KEEP_BRANCH', onCancel → null.
  - `taskClose.spec.ts`: keep이면 `closeSteps`의 `MERGED`·`BRANCH_DELETED` state가 `skipped`; `canSkipArtifact`가 keep + `UNDEPLOYED`만 있어도 참.
  - `canSyncBase`: keep이면 거짓.
  - `task-detail-close.spec.ts`: 종료 승인에서 브랜치 유지를 고르면 `POST /api/tasks/{id}/close/approve` 바디가 `{ keepBranch: true }`, 병합을 고르면 바디 없음. 확인 문구에 `main` 대신 작업의 `githubBranch`가 나온다. keep 작업종료 상세에 칩 문구가 보인다. keep이고 progress에 `UNDEPLOYED`가 있으면 종료 취소 버튼이 없다.
  - `tasks-index-mobile.spec.ts`(또는 목록 종료 승인 시험이 있는 파일): 목록에서 종료 승인 → 선택창 → 브랜치 유지면 바디 `{ keepBranch: true }`.
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run composables/closeApprove.spec.ts composables/taskClose.spec.ts test/task-detail-close.spec.ts test/tasks-index-mobile.spec.ts` 실패.
- [ ] **Step 3: 구현** — 위 Interfaces. `[id].vue`의 `approveClose`와 `index.vue`의 `applyMove`(path가 `close/approve`일 때만)가 `chooseCloseMode` → `useApi(url, { method: 'POST', body: closeApproveBody(mode) })`. `close/retry`는 지금 확인창 그대로. `canCancelClose`: keep이면 `progress.length === 0`, 아니면 기존 MERGED 규칙. `requestClose`·`approveClose` 문구의 `main`을 `기준 브랜치(${task.githubBranch})`로. 하이드레이션 스모크 목에 `close.keepBranch`. `CLAUDE.md` 작업 종료 문단 끝에 한 문단(무엇·바디·칸 V49·supports 가드·배포 순서 워커→API→프론트, 스펙 경로).
- [ ] **Step 4: 통과 확인** — 같은 vitest 명령 PASS, 이어서 `npx vitest run` 전체 PASS.
- [ ] **Step 5: 커밋** — `feat: 종료 승인 선택창(병합하지 않고 종료)·표시·버튼 규칙` (CLAUDE.md 포함).

### 마무리 (계획 밖 운영 단계, 사용자 승인 뒤)

- 전체 `gradlew test --no-daemon`(이 PC는 Testcontainers 건너뜀) + 프론트 `npx vitest run` → 푸시 → PR → CI 3개 통과 → 병합(사용자 승인).
- 라이브 반영: 진행 중 종료·배포 없음 확인 → 이 PC 워커(`stop-public.ps1 -Only worker` → 예약 작업) → API(V49, `-Only api`) → 프론트 `.output` 재빌드. 원격 워커 jar는 그대로.
- 라이브 확인: 배포완료 작업 하나를 브랜치 유지로 종료 → MR 열림·브랜치 존재·노트·화면 칩.
