# 작업 종료 단계 — 사용자 확인 → 관리자 승인 → 병합·배포 중지·노트·브랜치 정리 설계

- 날짜: 2026-10-13
- 상태: 사용자 검토 대기 (brainstorming: 질의 5건 → 설계 3절 각각 승인)
- 범위: netisMaker 백엔드(API·워커 데몬) + 프론트. 인터뷰 서비스 변경 없음. wiki 연계는 제품 밖 예약 작업(이 PC 설정)

## 1. 문제

`배포완료`가 사실상 마지막 상태다. 작업이 정말 끝났는지 표시할 방법이 없고, 그 결과로 다음이 남는다.

- 구현 PR/MR은 **draft로 생성된 채** 남는다(`GitHubPrCreator`·`GitLabMrCreator` 모두 draft). 병합하는 단계가 없다.
- 배포 컨테이너(공개 배포면 DNS 레코드까지)가 계속 돈다.
- 원격 head 브랜치가 남는다.
- 작업 기록(인터뷰 플랜·구현 결과·배포 이력·비용)은 DB에만 있고, 사람이 읽는 작업정리(옵시디언 `HamonSoft/netisMaker/`)는 손으로 따로 쓴다.

## 2. 확정된 결정 (사용자 답변)

| # | 질문 | 결정 |
|---|---|---|
| 1 | 종료와 PR 병합의 관계 | **종료 = 병합.** 확인되면 Symphony가 PR/MR을 병합하고 정리한다 |
| 2 | 배포 | **종료 시 배포 중지** |
| 3 | wiki 연계 | 종료 작업은 **옵시디언 노트만**. `wiki:ingest`·`wiki:compile`은 **하루 1회 예약 작업**이 모아서 한다 |
| 4 | 누가 종료하나 | **등록자 "확인 완료" → `종료승인대기` → 관리자 승인**으로 실행(기존 승인 흐름과 같은 모양) |
| 5 | 병합 불가 시 | **아무것도 건드리지 않고 `종료실패`**(사유 분류) → 관리자 재시도. 병합 이후 단계 실패는 진행을 기록하고 재시도 때 남은 단계부터 |
| 6 | 노트 생성 | **`claude -p` 1회로 요약 노트**(worklog 노트 형식). 재료는 DB에서 |

기각한 대안과 이유:

- 사람이 직접 병합하고 Symphony는 병합 확인 후 종료만(질문 1-B) / 병합 여부 무관 종료(1-C, 미병합 코드 유실 위험).
- 종료 작업 안에서 ingest·compile까지(3-B): 워커는 직렬 큐라 compile(문서·인덱스 다수 갱신)이 다음 구현을 붙잡고, 그러려면 워커의 `--setting-sources project` 격리를 풀어야 한다. llm-wiki는 사용자 설정(`~/.claude/settings.json` `"wiki@llm-wiki": true`)의 플러그인, `/worklog`는 사용자 스킬이라 현재 워커 `claude -p`에는 로드되지 않는다.
- CI 대기 중이면 워커가 재확인하며 기다림(5-B) / 호스트 auto-merge(5-C, 레포별 설정·GitLab 차이).
- LLM 없는 템플릿 노트(6-B·C).

## 3. 상태와 흐름

새 상태 5개. DB 값은 기존처럼 한글(VARCHAR(30)).

| enum | DB 값 | 의미 |
|---|---|---|
| `CLOSE_REVIEW` | 종료승인대기 | 등록자가 결과를 확인하고 종료를 요청함 |
| `CLOSE_PENDING` | 종료대기 | 관리자 승인 — 워커 큐 진입 |
| `CLOSING` | 종료중 | 워커가 claim해 실행 중 |
| `CLOSED` | 작업종료 | 끝(전이 없음) |
| `CLOSE_FAILED` | 종료실패 | 사유 기록, 관리자 재시도 대기 |

```
[배포완료|배포중단됨] ──(등록자 "확인 완료")──→ [종료승인대기]
[종료승인대기] ──(관리자 승인)──→ [종료대기] ──(워커 claim, kind=CLOSE)──→ [종료중] ──┬→ [작업종료]
                                                                                     └→ [종료실패]
[종료승인대기] ──(관리자 반려 / 등록자 철회)──→ 직전 상태(배포완료|배포중단됨)
[종료실패] ──(관리자 재시도)──→ [종료대기]
[종료실패] ──(관리자 종료 취소, MERGED 전만)──→ 직전 상태(배포완료|배포중단됨)
[종료중] ──(stale 복구)──→ [종료대기]
```

- 진입은 `배포완료`·`배포중단됨`에서만. `배포중단됨`(컨테이너 소실)도 결과 확인은 끝났을 수 있어 포함. 배포 없이 `PR생성`에서 바로 닫는 경로는 범위 밖.
- 반려·철회의 복귀 상태는 `task_status_history`에서 `종료승인대기`로 들어오기 직전 상태를 읽는다(배포완료 또는 배포중단됨만 허용, 그 외면 배포중단됨 — 컨테이너 생존을 장담할 수 없으므로).
- `종료승인대기` 동안 `DeployReconcileJob`은 이 작업을 건드리지 않는다(`DeployReconcileJob`의 관측 대상은 `배포완료`·`배포중단됨`뿐 — 2026-10-13 확인). 재배포·배포 중지 API도 이 상태를 허용 목록에 두지 않으므로 막힌다.
- `종료중` stale 복구는 배포 단계와 같은 방식으로 `StaleTaskRecoveryJob`이 `종료대기`로 되돌린다. 단계 진행(§4)이 기록되어 있으므로 재claim은 남은 단계부터.
- `QueueStats`(V9에서 배포중지 반영한 것과 같이)와 상태 이력 라벨에 새 상태를 넣는다.

## 4. 워커 종료 작업 (kind=CLOSE)

`WorkerService.claim`이 `CLOSE_PENDING → CLOSING`으로 claim하고 `kind=CLOSE`를 돌려준다. claim 응답에는 기존 필드(레포·PR·head_branch·head_sha) + `closeProgress` + 노트 재료 `closeContext`를 싣는다. `WorkerMainLoop`는 `case CLOSE -> processClose(task)`.

4단계를 순서대로 실행하고, 각 단계가 끝날 때마다 `POST /worker/tasks/{id}/close-progress {step}`로 보고한다(API가 `task.close_progress`에 누적). `closeProgress`에 이미 있는 단계는 건너뛴다. 모든 단계는 재실행해도 결과가 같다(멱등).

### ① 병합 — `MERGED`

- 새 인터페이스 `MergeRequestCloser`(GitHub=`gh`, GitLab=REST) — `MergeRequestCreator`와 같은 `supports(RepoRef)` 분기. 메서드: `status`, `merge`, `deleteBranch`.
- 먼저 PR/MR 상태 조회. **이미 병합됨**(사람이 직접 병합했거나 이전 시도에서 병합 후 보고 전 죽음)이면 병합 커밋을 읽어 성공 처리.
- draft 해제 후 병합:
  - GitHub: `gh pr ready N` → `gh pr merge N --<method> --match-head-commit <head_sha>` (GH_TOKEN 전달은 `GitHubPrCreator`와 같게).
  - GitLab: `PUT /projects/:id/merge_requests/:iid`로 draft 해제 → `PUT …/merge`(`sha=<head_sha>`, `merge_when_pipeline_succeeds=false`).
- 병합 방식: 워커 설정 `CLOSE_MERGE_METHOD`(`merge`|`squash`|`rebase`, 기본 `merge`).
- **head 고정**: `head_sha`는 구현 시점 커밋이다. 그 뒤 브랜치에 커밋이 더 올라왔으면 확인되지 않은 코드이므로 병합을 거부한다(호스트가 sha 불일치로 거절).
- 실패 사유 분류(`close_failure_reason`, 사람이 읽는 한국어): CI 대기 중 / CI 실패 / 충돌 / head 불일치 / 권한·보호 규칙 / 기타(마스킹한 원문 일부). 이 단계 전에는 아무것도 바꾸지 않았으므로 그대로 재시도 가능.
- 성공 시 병합 커밋 SHA를 진행 보고에 실어 `task.merge_commit_sha`에 저장.

### ② 배포 중지 — `UNDEPLOYED`

- 기존 `DeployService.undeploy(taskId)` 재사용(공개 배포면 `DnsRegisteringDeployTarget`이 DNS도 정리). 컨테이너가 이미 없으면 성공(구현 시 `target.stop` 부재 처리 확인).

### ③ 옵시디언 노트 — `NOTE_WRITTEN`

- 재료(`closeContext`): 제목·설명, 확정 플랜(`task_analysis`), 구현 결과 요약(`implementation_log`에서 결과 섹션), PR URL·병합 커밋, 배포 이력(주소·헬스체크·기간), 상태 이력, 단계별 비용(`task_stage_usage`). API가 조립해 claim에 싣는다.
- `claude -p`를 **레포 밖 빈 폴더** `~/netis-maker/close/task-{id}`에서 1회 실행. 프롬프트(설정 `close.note-prompt-template`, 기본값 코드 내장)는 worklog 노트 형식(배경·한 일·결정과 근거·검증·남은 일, 한국어)으로 **마크다운 본문만 stdout에 출력**하게 한다. 모델은 설정 `CLOSE_NOTE_MODEL`(기본 sonnet), 타임아웃 10분.
- **도구 없이 실행하고 파일은 워커가 쓴다.** (§9 검토 요청 1) 노트 재료에는 사용자 입력(설명·플랜)이 섞이므로, 쓰기 도구를 쥔 세션이 볼트의 다른 위치를 고치는 경로를 없앤다. 내장 도구 비활성 + MCP 미주입(정확한 CLI 플래그는 계획 단계에서 실측).
- 워커가 볼트에 직접 쓴다: `{CLOSE_OBSIDIAN_VAULT_DIR}/HamonSoft/netisMaker/<제목 슬러그>-작업{id}-YYYY-MM-DD.md`(작업 id로 같은 날 같은 제목 충돌 회피, 같은 경로가 있으면 덮어씀 — 재시도 멱등). 프런트매터에 `task_id`, `pr`, `merge_commit`, `closed` 날짜, 태그 `netismaker/작업종료`.
- 같은 폴더의 `netisMaker-진행상황-YYYY-MM-DD.md`에 `- [[노트 이름]] — 작업 {id} 종료(병합 {sha7})` 한 줄을 덧붙인다(없으면 만든다, 같은 줄이 있으면 생략 — 멱등).
- 비밀 마스킹: 프롬프트에 규칙을 넣고, 워커가 파일에 쓰기 전 `GitRemotes.mask`/`maskSecrets`를 한 번 더 통과시킨다.
- 노트 경로(볼트 상대)를 진행 보고에 실어 `task.close_note_path`에 저장.
- `CLOSE_OBSIDIAN_VAULT_DIR` 미설정이거나 폴더가 없으면 ③이 남은 경우 ① **전에** 바로 `종료실패`(되돌릴 수 없는 병합 전에 설정 누락을 잡는다). 쓰기 실패·claude 실패는 `종료실패`(병합·배포 중지는 이미 끝났으므로 재시도는 ③부터).

### ④ 브랜치·worktree 정리 — `BRANCH_DELETED`

- 원격 브랜치 삭제: GitHub `DELETE /repos/{o}/{r}/git/refs/heads/{branch}`(gh api), GitLab `DELETE /projects/:id/repository/branches/:branch`. 404는 성공(이미 없음 — 호스트 설정상 병합 시 자동 삭제되는 레포 포함).
- 로컬: `WorktreeService.discardUnderLock`로 `task-`·`deploy-`·`design-` worktree(성공분은 2026-10-02 정책으로 대부분 이미 없음) 제거, 레포 캐시의 로컬 브랜치 삭제, `~/netis-maker/close/task-{id}` 제거. 로컬 정리 실패는 경고만(주기 정리 잡이 회수).

4단계가 끝나면 `WorkerResultRequest.closed(...)`로 `작업종료` 보고, 단계 실패는 `closeFailed(reason)`로 `종료실패` 보고. 워커가 API로 보내는 텍스트는 기존처럼 `WorkerResultRequest.masked()`를 거친다.

## 5. API

`TaskController`(기존 디자인 승인·반려와 같은 모양):

| 엔드포인트 | 권한 | 전이 |
|---|---|---|
| `POST /api/tasks/{id}/close-request` | 등록자 본인 또는 ADMIN | 배포완료\|배포중단됨 → 종료승인대기 |
| `POST /api/tasks/{id}/close-request/withdraw` | 등록자 본인 또는 ADMIN | 종료승인대기 → 직전 상태 |
| `POST /api/tasks/{id}/close/approve` | ADMIN | 종료승인대기 → 종료대기 |
| `POST /api/tasks/{id}/close/reject` (바디 `reason`) | ADMIN | 종료승인대기 → 직전 상태 (반려 사유를 이력에 기록) |
| `POST /api/tasks/{id}/close/retry` | ADMIN | 종료실패 → 종료대기 (`close_failure_reason` 비움, `close_progress` 유지) |
| `POST /api/tasks/{id}/close/cancel` | ADMIN | 종료실패 → 직전 상태 (`close_progress`에 `MERGED`가 없을 때만 — 실패 사유·진행 비움, 이력 "종료 취소(병합 전)"; 병합 뒤면 409, 재시도로 마무리) |

워커 측: claim 응답 `kind=CLOSE` + `closeProgress` + `closeContext`, `POST /worker/tasks/{id}/close-progress`(X-Worker-API-Key), 결과 보고에 `작업종료`/`종료실패` 분기.

`TaskResponse`에 `closeProgress`, `closeNotePath`, `mergeCommitSha`, `closeFailureReason` 추가.

DB 마이그레이션 `V26__task_close_phase.sql`: `task`에 `close_progress VARCHAR(100)`(완료 단계 콤마 목록), `close_note_path VARCHAR(500)`, `merge_commit_sha VARCHAR(40)`, `close_failure_reason TEXT`. 상태 컬럼은 CHECK 제약 없는 문자열이라 값 추가만으로 충분(2026-10-13 확인).

## 6. 프론트

- `composables/taskStages.ts`에 '종료' 단계 신설: 종료승인대기·종료대기·종료중·종료실패, `작업종료`는 terminal(취소됨과 같은 취급). `STATUS_STAGE_COVERAGE` 테스트가 누락을 막는다. 상태 칩·`TaskProgressStepper`·`TaskHistoryTimeline` 라벨 추가.
- 버튼(`TaskNextAction`, 모바일 `TaskActionSheet`):
  - 등록자: 배포완료·배포중단됨 → "확인 완료(종료 요청)", 종료승인대기 → "철회".
  - 관리자: 종료승인대기 → "종료 승인"/"반려", 종료실패 → "재시도".
  - 확인은 `confirmDialog($q)` — 문구에 "PR을 main에 병합하고, 배포를 중지하고, 브랜치를 삭제합니다. 되돌릴 수 없습니다" 명시.
- 상세: 종료 4단계 체크(✔/진행 중/실패), 실패 사유, 병합 커밋, 옵시디언 노트 링크(`obsidian://open?vault=<볼트 이름>&file=<상대경로>`, 볼트 이름은 런타임 설정 `public.obsidianVault`, 비어 있으면 경로 텍스트만).
- 목록: `작업종료`는 기본 숨김, 필터로 표시(취소됨과 동일).
- 하이드레이션 스모크(`scripts/hydration-smoke.mjs`)의 `apiResponse` 목에 새 필드 반영.

## 7. wiki 예약 작업 (제품 밖)

- Claude 데스크톱 예약 작업으로 평일 1회(18:30). 일반 세션이므로 llm-wiki 플러그인이 로드된다.
- 프롬프트 요지: "`HamonSoft/netisMaker/`에서 오늘 생성·수정된 노트 중 `netis-platform` 토픽 raw에 같은 sha가 없는 것을 `wiki:ingest`로 들이고, 하나라도 들였으면 `wiki:compile --wiki netis-platform`. 없으면 아무것도 하지 않고 종료."
- 구현 PR 병합 후 등록한다(코드 변경 없음).

## 8. 테스트

- 백엔드 단위: 상태 전이·권한(등록자/타인/관리자), 반려·철회 복귀 상태, claim `kind=CLOSE`와 `closeContext` 조립, `close-progress` 누적·중복 무시, retry가 진행을 유지.
- `MergeRequestCloser` GitHub/GitLab: 명령·요청 조립(method, head sha), 응답 분류(이미 병합·CI 대기·CI 실패·충돌·sha 불일치), 브랜치 삭제 404=성공. 기존 `GitHubPrCreator`/`GitLabMrCreator` 테스트 방식(`ProcessRunner`·HTTP 목) 재사용.
- 워커 `processClose`: 진행이 있는 단계 건너뛰기, ③ 노트 파일 경로·프런트매터·진행상황 줄 멱등, ③ 실패 시 ④ 미실행.
- 프론트 vitest: 단계 커버리지, 역할·상태별 버튼 노출.

배포 순서: 워커 → API(V26) → 프론트 `.output` 재빌드 → 예약 작업 등록. (구버전 API는 `kind=CLOSE`를 내지 않으므로 워커 먼저가 안전.)

## 9. 검토 요청

1. **노트 쓰기 주체 변경**: 설계 대화에선 "`claude -p`가 Obsidian MCP로 쓴다"였으나, 문서에서는 "`claude -p`는 도구 없이 본문만 출력하고 워커가 볼트 파일에 쓴다"로 바꿨다. 이유: 노트 재료에 사용자 입력이 섞여 프롬프트 인젝션으로 볼트 다른 곳을 고칠 수 있는 경로를 없애고, 파일 이름·진행상황 추기를 결정론으로 만들기 위해. 대가: 워커가 볼트와 같은 PC에 있어야 한다(현재 그렇다 — `CLOSE_OBSIDIAN_VAULT_DIR` 설정 필요). MCP 방식이 낫다면 되돌린다.
2. **`head_sha` 기준 병합**: 배포는 `origin/<head_branch>` 끝을 체크아웃하므로, 구현 후 브랜치에 사람이 커밋을 더 올렸다면 병합이 거부된다(`종료실패: head 불일치`). 그 경우 처리는 관리자 수동(범위 밖).

## 10. 구현 메모(계획 단계에서 확정)

계획·구현 중 위 설계에서 바뀌거나 확정된 점:

- 종료 실패 사유는 새 컬럼 대신 기존 `failure_reason`에 담는다(배포실패와 같은 관례).
- 큐 통계 뷰(`task_queue_stats`)는 바꾸지 않는다(YAGNI).
- 작업종료는 "기본 숨김"이 아니라 취소됨과 같은 terminal 단계(있을 때만 표시) + 상태 필터로 다룬다(§6의 "기본 숨김" 정정).
- GitHub/GitLab 모두 병합이 거부되면 해제했던 draft를 되돌린다.
- 종료 4상태(종료승인대기·종료대기·종료중·종료실패)를 GC 보호 목록에 넣는다(반려 후 배포완료 복귀 시 컨테이너 보존).
- 브랜치 삭제의 "이미 없음" 판정은 GitHub "Reference does not exist", GitLab 404+"Branch Not Found"일 때만 한다(권한 없음 404를 삭제 성공으로 오인하지 않기).
- GitLab 병합 PUT이 200이어도 state가 merged가 아니면 최대 5회×2초 재조회하고, 끝내 아니면 "병합 진행 중" 종료실패로 끝낸다(제목 원복 안 함 — 재시도 시 병합됨으로 성공).
- 병합 실패·예외 시 draft를 원복한다(GitHub `gh pr ready --undo`, GitLab 제목 원복). 원복 실패는 사유에 "draft 원복 실패"로 표기한다.
- 등록자 판정은 `task.requesterId`(JWT username)와 로그인 사용자 username을 비교한다(숫자 user_id 아님).
- 노트 본문이 비거나 코드펜스뿐이면 노트 단계 실패로 본다. 노트 단계가 실패해도 claude 사용량은 종료실패 보고에 실린다.
- 노트 날짜는 쓰는 날 기준이다 — 노트 저장 직후 진행 보고 전에 워커가 죽고 다음 날 재시도하면 날짜만 다른 노트가 하나 더 생길 수 있다(드묾, 수동 삭제).
