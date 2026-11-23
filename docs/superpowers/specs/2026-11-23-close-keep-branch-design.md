# 병합하지 않고 종료 — 작업 브랜치·PR 유지 (설계)

- 날짜: 2026-11-23
- 상태: 설계 (사용자 승인 대기)
- 관련: 작업 종료 단계 스펙 `2026-10-13-task-close-phase-design.md`, 종료 산출물 `2026-10-14-close-artifact-delivery-design.md`, 부분 패치 `2026-11-10-close-partial-patch-design.md`, 기준 브랜치 반영 `2026-11-13-close-base-sync-design.md`

## 1. 왜

배포까지 끝난 작업 중에는 **Symphony 밖에서 사람이 이어서 처리해야 하는** 것이 있다. 그런 작업은 분리된 작업 브랜치가 남아 있어야 거기서 계속할 수 있는데, 지금 종료는 PR(GitLab MR)을 기준 브랜치에 병합하고 작업 브랜치를 지우는 길 하나뿐이다. 그래서 그런 작업은 `배포완료`·`배포중단됨`에 머물 수밖에 없었다.

사용자 요청(2026-11-23): 관리자가 종료를 승인할 때 **"병합하지 않고 종료"**를 고르면, PR과 작업 브랜치는 그대로 둔 채 나머지 마무리(배포 중지·산출물·노트)만 하고 작업을 `작업종료`로 닫는다. 종료 노트는 병합해서 종료할 때와 마찬가지로 그 브랜치에 작업된 내용을 정리한다.

## 2. 목표 · 비목표 · 실패 조건

**목표**
- 관리자가 종료 승인 때 종료 방식(병합하고 종료 / 병합하지 않고 종료)을 고른다. 기본값은 지금과 같은 "병합하고 종료".
- "병합하지 않고 종료"는 병합 단계와 브랜치 삭제 단계를 건너뛰고, 배포 중지·산출물·노트는 그대로 한다.
- 화면·노트·이력에서 이 작업이 병합되지 않았고 브랜치가 남아 있다는 것이 보인다.

**비목표**
- 등록자가 종료 요청 때 방식을 고르는 것(사용자 결정: 관리자 승인 때만 — 설계 방식 A).
- 종료 뒤 남은 PR을 Symphony가 추적·병합하는 것. 종료 뒤 PR·브랜치는 사람 몫이다.
- PR을 닫는(close) 동작. PR은 열린 채 둔다.
- 다른 브랜치로 병합하기(종료 때 대상 브랜치 바꾸기).

**실패로 보는 것(기능이 돌아도)**
- "병합하지 않고 종료"를 골랐는데 PR이 병합되거나, 작업 브랜치가 지워지거나, PR이 닫히는 것.
- 아무것도 고르지 않은 관리자의 종료가 지금과 다르게 동작하는 것.
- 모드를 모르는 옛 워커가 그 작업을 받아 병합해 버리는 것.

## 3. 방식 (고려한 것과 고른 것)

| 방식 | 내용 | 장단점 |
|---|---|---|
| **A. 관리자가 종료 승인 때 고른다 (채택)** | 종료 승인 확인창(목록 카드·상세 두 곳)을 선택창으로 바꾼다 | 되돌릴 수 없는 병합 여부를 관리자 한 곳에서 정한다. 바꾸는 화면이 적다. 등록자 사정은 말로 전해야 한다 |
| B. 등록자가 요청 때 고르고 관리자가 확인·변경 | 종료 요청 창에도 선택을 넣고 승인창은 미리 고름 | 손댈 화면이 두 배(상세·모바일 시트·DB 작업 PR생성 경로), 요청 뒤 바꾸려면 철회 후 재요청 |
| C. 승인 없이 바로 닫는 별도 버튼 | — | 관리자 승인 단계를 건너뛰므로 뺐다 |

사용자가 A를 골랐다(2026-11-23).

## 4. 동작

### 4.1 단계

지금 종료 단계: `MERGED → PATCH_BUILT → UNDEPLOYED → ARTIFACT_UPLOADED → NOTE_WRITTEN → BRANCH_DELETED`.

"병합하지 않고 종료"(이하 **브랜치 유지 종료**)에서:

| 단계 | 브랜치 유지 종료 | 이유 |
|---|---|---|
| MERGED | **건너뜀** | 요구 그대로 |
| PATCH_BUILT | **건너뜀**(모드를 정하지 않음) | 부분 패치는 병합 커밋과 그 부모로 전후를 빌드한다 — 병합 커밋이 없다 |
| UNDEPLOYED | 그대로 | 띄운 적 없으면 지금처럼 실행 없이 완료 기록 |
| ARTIFACT_UPLOADED | 그대로(모드가 REQUIRED일 때) | 산출물은 배포 이미지 `netis-task-{id}:{sha7}`에서 꺼내므로 병합과 무관 |
| NOTE_WRITTEN | 그대로 | 내용만 §4.4처럼 "병합 안 함·브랜치 유지"를 밝힌다 |
| BRANCH_DELETED | **건너뜀** | 작업 브랜치를 지우면 GitHub는 PR을 자동으로 닫고 GitLab MR은 원본 브랜치가 없어 병합할 수 없게 된다. 이 브랜치를 기준으로 쓰는 다른 작업의 기준 브랜치 옮기기(`retargetDependents`·`rebaseDependents`)도 하지 않는다 — 브랜치가 남아 있으므로 |

로컬 worktree 정리(`cleanupLocal`)는 지금처럼 한다(원격 브랜치·PR은 건드리지 않는다).

### 4.2 데이터

- 새 칸 `com.task.close_keep_branch BOOLEAN NOT NULL DEFAULT false` (마이그레이션 `V49__close_keep_branch.sql` — 병합 직전 `origin/main`의 가장 큰 번호를 다시 본다). false = 지금과 같은 병합 종료.
- 값은 **종료 승인 때 한 번** 정한다(산출물·부분 패치 모드와 같은 규칙). 재시도·stale 재큐는 바꾸지 않는다. 종료 취소(§4.3)는 false로 되돌린다 — 다음 승인 때 다시 고른다.
- `CloseStep.required(artifactMode, patchMode)`에 브랜치 유지 여부를 더해 브랜치 유지면 `MERGED`·`BRANCH_DELETED`를 뺀다. `allDone`도 같은 인자를 받는다(작업종료 보고 검증 `WorkerService`가 쓴다).

### 4.3 API

- `POST /api/tasks/{id}/close/approve`(관리자)에 **선택 바디** `{ "keepBranch": true }`. 바디가 없거나 `keepBranch`가 false·null이면 지금과 같은 병합 종료(옛 화면·다른 호출자 호환).
- 브랜치 유지로 승인하면 `close_keep_branch=true`, `close_patch_mode=null`(부분 패치 판정을 하지 않음), 산출물 모드는 지금 규칙 그대로. 이력 사유 끝에 ` (병합 안 함·브랜치 유지)`.
- 워커 진행 보고(`POST /worker/tasks/{id}/close-progress`)에서 브랜치 유지 작업의 `MERGED`·`BRANCH_DELETED` 보고는 409로 거절한다(옛 워커·버그 방어).
- **종료 취소**(`close/cancel`): 지금은 "MERGED가 없으면 되돌릴 수 없는 일이 없다"를 근거로 허용한다. 브랜치 유지 종료는 MERGED가 영영 없으므로 근거가 달라진다 — **완료 단계가 하나도 없을 때만** 허용하고, 하나라도 있으면 409 `이미 진행된 단계가 있어 종료를 취소할 수 없습니다 — 재시도로 마무리하세요`. (배포 중지 뒤 취소하면 컨테이너 없이 `배포완료`로 돌아가 배포 정합이 꼬인다.)
- **산출물 없이 마무리**(`close/skip-artifact`): 지금은 `MERGED`·`UNDEPLOYED` 완료 + 산출물 미완료를 요구한다(`TaskCloseService.skipArtifact`, 화면 `canSkipArtifact`). 브랜치 유지 작업이면 `MERGED` 조건만 빼고 나머지는 그대로 둔다 — 그러지 않으면 배포 중지 뒤 산출물에서 영구 실패한 브랜치 유지 종료는 재시도·취소·산출물 없이 마무리가 모두 막혀 빠져나올 길이 없다. (빌더 검토 반영, 설계자 결정)
- **기준 브랜치 반영**(`close/sync-base`): 브랜치 유지 작업에는 409 `병합하지 않는 종료에는 기준 브랜치 반영이 필요 없습니다`(병합 충돌이 날 일이 없다).
- 응답 `TaskResponse`의 종료 뷰(`close`)에 `keepBranch: boolean`.

### 4.4 워커

- claim(`WorkerTaskResponse`)에 `closeKeepBranch`(boolean, CLOSE에서만 의미).
- `TaskCloser.process`: `closeKeepBranch`면 병합 블록과 브랜치 삭제 블록(그 앞의 `retargetDependents` 포함)을 건너뛴다. 병합 전 확인(`requirePatchReady`, rebase 병합 검사)도 돌지 않는다.
- **작업 브랜치가 남아 있는지 확인**(설계자 결정, 빌더 검토 반영): `UNDEPLOYED`가 아직 없으면 그보다 **먼저** `GitOpsService.remoteBranchTip(ref, headBranch)`로 원격에 작업 브랜치가 있는지 본다. 없거나 읽지 못하면 단계 실패 `브랜치 유지: 작업 브랜치 {head_branch}를 원격에서 찾지 못했습니다 — 브랜치가 남아 있는지 확인하세요`로 끝낸다(아무 단계도 안 했으니 종료 취소·재시도 둘 다 된다). 이 기능의 목적이 "밖에서 이어 쓸 브랜치가 남아 있음"이라, 사람이 이미 브랜치를 지운 작업을 `브랜치 유지`로 잘못 표시하지 않게 한다. PR/MR 상태(이미 병합·닫힘)까지는 조회하지 않는다 — 종료 뒤 PR은 사람 몫이고(비목표), 상태 조회는 GitHub·GitLab 두 구현이 더 필요하다.
- 종료 노트(`CloseNotePrompt`·`CloseNoteWriter`):
  - 프롬프트의 `병합 커밋: {merge_sha}` 자리에 `병합 안 함 — 작업 브랜치 {head_branch}와 PR 유지(Symphony 밖에서 이어서 처리)`가 들어간다. 노트 본문은 병합 종료와 같은 재료(플랜·분석·구현 로그 끝·이력·비용)로 그 브랜치에 작업된 내용을 정리한다.
  - 프런트매터: `merge_commit:`은 비우고 `branch_kept: <head_branch>` 한 줄을 더한다.
  - 진행상황 노트 줄: `— 작업 #N 종료(병합 안 함 · 브랜치 유지)`.
- **구버전 워커 막기**: 새 워커는 next-task `supports`에 `closeKeepBranch`를 더한다(`WorkerHttpClient.SUPPORTS`). 이를 모르는 워커에는 `close_keep_branch=true`인 `종료대기`를 내주지 않는다(`WorkerCapabilities`·`TaskRepository.findClaimableAmong` — 기존 `baseSync` 가드와 같은 방식). 옛 워커는 모르는 칸을 버리고 병합해 버리기 때문이다. 종료는 이 PC 워커만 받는다(원격 워커는 `WORKER_KINDS=IMPLEMENTATION,DESIGN`).

### 4.5 화면

- **종료 승인 선택창**: 목록(`pages/tasks/index.vue`의 `applyMove`, `close/approve`)과 상세(`pages/tasks/[id].vue`의 `approveClose`)가 같은 선택창을 쓴다. 지금의 `confirmDialog` 대신 Quasar Dialog의 radio `options`(또는 작은 공용 함수/컴포넌트)로:
  ```
  종료 승인 — 작업 #N
  종료 방식
  (●) 병합하고 종료 — PR을 {기준 브랜치}에 병합 · 배포 중지 · 노트 · 브랜치 삭제
  ( ) 병합하지 않고 종료 — 브랜치·PR 유지 · 배포 중지 · 노트
  병합·배포 중지·브랜치 삭제는 되돌릴 수 없습니다.
  (승인 대기 SQL 경고 한 줄 — 있을 때만, 지금과 같음)
                                   [취소] [종료 승인]
  ```
  확인 버튼 글자는 하는 일 이름(`confirmDialog` 규칙). 내장 브라우저에서도 동작해야 하므로 `window.confirm` 금지(Quasar Dialog는 됨).
- **종료 단계 표시**(`composables/taskClose.ts`의 `closeSteps`): 브랜치 유지면 `PR 병합`·`브랜치 삭제`를 `건너뜀`으로 보인다(산출물·패치 SKIPPED와 같은 표시).
- **표시 칩**: 작업종료(또는 종료 진행 중) 작업이 브랜치 유지면 상세·카드에 `미병합 종료 · 브랜치 유지` 칩. PR 링크는 그대로 보인다(열린 PR로 이어진다).
- **문구 정정**(같은 화면이라 함께): 상세의 종료 요청·승인 확인 문구가 "PR을 main에 병합"이라고 적혀 있는데 실제 병합 대상은 작업의 기준 브랜치다 → `기준 브랜치({githubBranch})`로 고친다.
- 새 API는 없다(기존 경로에 바디만 더함) — 하이드레이션 스모크 목은 응답 `close.keepBranch`만 더하면 된다.

## 5. 제약

- `commanCenter/CLAUDE.md` 규칙: 프론트 작은따옴표·세미콜론 없음, `$q.screen.lt.md` 분기, `confirmDialog($q)` 계열(네이티브 confirm 금지), 새 응답 칸이면 `hydration-smoke.mjs` 목 갱신.
- 마이그레이션은 `NOT NULL DEFAULT`로 기존 행 안전.
- 구버전 워커·구 API·구 화면이 섞여도 병합이 일어나면 안 된다(§7).

## 6. 시험

- API 단위: 승인 바디 없음/false → 지금과 같음(close_keep_branch false, 패치 판정 그대로) / true → keep, 패치 null, 이력 문구. 진행 보고 MERGED·BRANCH_DELETED 409. `allDone`이 keep이면 MERGED·BRANCH_DELETED 없이 참. 취소: keep + 진행 없음 허용 / keep + UNDEPLOYED 있음 409. sync-base keep 409. skip-artifact: keep + UNDEPLOYED 완료 + 산출물 미완료면 MERGED 없이 허용(병합 종료는 지금 조건 그대로).
- 워커 단위(브랜치 확인): keep이고 원격 브랜치가 없으면 배포 중지 전에 실패하고 undeploy를 부르지 않는다.
- claim 가드: `closeKeepBranch` 없는 워커는 keep 종료대기를 못 받고 일반 종료대기는 받는다(Testcontainers 통합 — CI).
- 워커 단위: keep이면 merge·deleteBranch·retargetDependents를 부르지 않고 UNDEPLOYED·NOTE_WRITTEN을 보고한 뒤 CLOSED. 노트 프런트매터 `branch_kept`, 프롬프트 문구, 진행상황 줄.
- 프론트 vitest: 선택창 기본값 병합 → 바디 없음(또는 false), 브랜치 유지 선택 → `{ keepBranch: true }`. `closeSteps` keep 표시. 칩. `canSkipArtifact`가 keep이면 MERGED 없이 참.
- 라이브 확인(배포 뒤): 배포완료 작업 하나를 브랜치 유지로 종료 → GitLab MR 열림·브랜치 존재, 노트 내용, 화면 표시.

## 7. 배포 순서

이 PC 워커 → API(V49) → 프론트(`.output` 재빌드). 어느 순서로 어긋나도 병합이 잘못 일어나지 않는다:
- 새 API + 옛 워커: 옛 워커는 `closeKeepBranch`를 안 보내므로 keep 종료대기를 받지 못한다(대기로 남음). 일반 종료는 그대로.
- 옛 API + 새 워커: claim에 칸이 없으면 false(병합 종료) — 옛 API에는 keep 작업이 있을 수 없다.
- 옛 화면 + 새 API: 바디 없이 승인 → 병합 종료(지금과 같음).

원격 워커 두 대는 종료를 받지 않으므로 jar를 바꾸지 않아도 된다(다음 갱신 때 함께). 라이브 재기동은 작업 스케줄러 절차(`stop-public.ps1 -Only …` → `Start-ScheduledTask netisMaker-public`), 워커는 진행 중 종료·배포가 없을 때.

## 8. 설계자가 정한 것 (사용자가 확인할 것)

사용자가 정한 것: 방식 A(관리자 승인 때 선택), 노트는 병합 종료와 같은 내용, PR은 열린 채 유지. 아래는 설계자가 정했다.

1. 기본값은 "병합하고 종료" — 고르지 않으면 지금과 똑같다.
2. 부분 패치는 브랜치 유지 종료에서 하지 않는다(병합 커밋이 없어 만들 수 없음). 산출물은 그대로 한다.
3. 브랜치 유지 종료의 종료 취소는 아무 단계도 안 했을 때만, 산출물 없이 마무리는 병합 조건 없이 허용.
4. 배포 중지 전에 작업 브랜치가 원격에 있는지만 확인하고, PR 상태는 조회하지 않는다.
5. 옛 워커가 이 작업을 받지 못하게 `supports=closeKeepBranch` 가드를 둔다.
6. 상세 화면의 "PR을 main에 병합" 문구를 실제 대상인 기준 브랜치 이름으로 고친다.

## 9. 빌더에게 맡긴 것

- 선택창 구현 형태(Quasar Dialog radio options를 감싼 함수 vs 작은 컴포넌트) — 목록·상세가 같은 것을 쓰면 된다.
- 종료 메일(`CloseMailListener`) 본문에 병합을 단정하는 문구가 있으면 브랜치 유지일 때 맞춘다.
- `WorkerCapabilities` 생성자 호환 방식(기존 패턴대로 인자 추가 + 옛 생성자 유지).
- 칩 색·문구 세부(위 문구를 기준으로).
