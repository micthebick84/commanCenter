# worktree 보존 정책 — 성공 즉시 정리 + 7일 주기 정리 설계

- 날짜: 2026-10-02 (설계 대화 2026-10-01)
- 상태: 사용자 승인됨 (brainstorming: 질의 2건 → 접근안 3개 비교 → ① "작업 끝 즉시 정리 + 새벽 정리 잡(API 진행 중 조회)" 채택 → 설계 3절 각각 승인)
- 범위: netisMaker 백엔드(Java) — 워커 데몬 + API 1개. 프론트·인터뷰 서비스 변경 없음
- 해결하는 백로그: `TODOS.md` V1.1 "worktree 보존 정책 (구현 성공·배포 worktree 정리)"
- 선행: PR #61 `RepoCacheCleanupJob`(레포 캐시 정리) — worktree가 붙은 캐시는 지우지 않으므로 이 정책이 있어야 캐시가 회수된다

## 1. 문제

`~/netis-maker/worktrees/{localKey}/{task-N|deploy-N|design-N}`은 지우는 곳이 없다.

- 구현(`task-N`): `WorktreeService` 주석 "Phase 1은 보존" — PR 생성 후에도 남는다.
- 배포(`deploy-N`): 배포 후에도 남는다.
- 디자인(`design-N`): 성공 시에만 수확 후 제거, 실패분은 남는다.

결과: `RepoCacheCleanupJob`은 살아 있는 worktree가 붙은 캐시를 절대 지우지 않으므로, 구현·배포를 한 번이라도 거친 캐시(운영 캐시 대부분)가 회수되지 않는다. worktree 자체도 체크아웃 크기만큼 디스크를 쓴다.

## 2. 핵심 관찰 — 성공한 worktree는 작업이 끝나는 순간 쓸모가 없다

코드 확인(2026-10-01):

| worktree | 작업 후 쓰는 곳 | 근거 |
|---|---|---|
| `task-N` | 없음 | `WorkerMainLoop.processImplementation`이 commit + **push** 후 PR을 만든다. 산출물은 원격 브랜치에 있다. 재시도는 `WorktreeService.create`가 기존 것을 강제 제거 후 재생성 |
| `deploy-N` | 없음 | `docker build`의 빌드 컨텍스트일 뿐 — 컨테이너는 이미지로 돈다(바인드 마운트 없음). `DeployReconcileJob`·`DockerGcJob`은 컨테이너 상태만 본다. 재배포는 `createForDeploy`가 강제 제거 후 재생성 |
| `design-N` | 없음 | 산출물은 수확되어 DB로 간다(이미 성공 시 제거 중) |

서버는 PR/MR 병합·종료 상태를 어디에서도 추적하지 않는다(웹훅·폴링 없음). 그래서 TODOS 초안의 "PR 병합 후 N일"은 채택하지 않는다 — 결정할 것은 PR 상태가 아니라 **디버그용 보존 기간**이다.

## 3. 확정된 정책 결정 (사용자 답변)

| 결정 | 내용 |
|---|---|
| 성공한 worktree | **작업이 성공하면 그 자리에서 제거**(사용자가 성공분을 열어 보지 않고, 같은 worktree에서 이어서 작업하는 기능 계획도 없음) |
| 실패한 worktree | 디버그용으로 보존하되 **만든 지 7일**이 지나면 주기 정리가 제거(설정값) |
| 진행 중 보호 | 주기 정리는 API에서 **진행 중 작업 목록**을 받아 그 작업의 worktree를 건너뛴다. 조회 실패면 그 회차는 아무것도 지우지 않는다(fail-closed) |
| 기각한 대안 | `git worktree lock`으로 진행 중 표시(워커가 죽으면 lock이 영구 잔존 → "오래된 lock 무시" 규칙이 또 필요) / 주기 정리만(성공분이 최대 7일 남아 결정 1과 어긋남) / PR 상태 기반(PR 상태 추적 인프라가 없음) |

## 4. 설계

### 4.1 작업 끝 즉시 정리

| 작업 | 성공 시 | 실패 시 |
|---|---|---|
| 구현 `task-N` | `PR_CREATED` 보고 **뒤** worktree 제거 + 레포 캐시의 로컬 브랜치(`{branch-prefix}task-N…`) 삭제(이미 원격에 푸시됨) | 보존(변경 없음) |
| 배포 `deploy-N` | `DeployService.deploy` 안에서 `target.deploy` 성공 직후 제거 — 만든 곳이 지운다 | 보존 |
| 디자인 `design-N` | 현행 유지(수확 후 제거) | 보존 |
| 배포 중지 | 변경 없음(컨테이너만 제거; 성공한 배포의 worktree는 이미 없다) | — |

- **제거는 best-effort.** 제거 실패는 경고 로그만 남기고 작업 결과(보고·상태)에 영향을 주지 않는다. 남은 폴더는 4.2가 7일 뒤 치운다.
- **`WorktreeService.remove`를 레포 락 안으로.** 지금은 생성만 `withRepoLock` 안이고 제거는 락 밖이라 같은 레포의 다른 worktree 생성과 `.git/worktrees/` 메타 수정이 겹칠 수 있다. 제거 절차(4.1·4.2 공통):
  1. `git worktree remove --force <dir>` (캐시 디렉터리에서)
  2. 폴더가 남아 있으면 심볼릭 링크를 따라가지 않는 재귀 삭제(기존 `deleteRecursively`와 같은 규칙)
  3. `git worktree prune`
  4. 구현 worktree면 로컬 브랜치 삭제 — 브랜치명은 `{branch-prefix}task-{id}`이거나 `{branch-prefix}task-{id}-{slug}`(`WorktreeService.doCreate`와 같은 규칙). 다른 작업 번호의 접두 일치(예: `task-1`이 `task-12-…`)를 지우지 않게 정확히 매칭한다.

### 4.2 새벽 정리 잡 `WorktreeCleanupJob` (worker 프로파일)

**설정** — `WorkerProperties.WorktreeCleanup`(기존 `RepoCacheCleanup` 레코드와 같은 형태, `configureTasks`로 정규화된 cron 등록)

| 설정(환경변수) | 기본 | 뜻 |
|---|---|---|
| `netis-maker.worker.worktree-cleanup.enabled` (`WORKTREE_CLEANUP_ENABLED`) | `true` | `false`면 잡 no-op |
| `…worktree-cleanup.cron` (`WORKTREE_CLEANUP_CRON`) | `0 30 2 * * *` | 6필드 cron, `-`=끔. 잘못된 식은 바인딩 때 설정 이름을 밝혀 부팅 실패 |
| `…worktree-cleanup.retention-days` (`WORKTREE_CLEANUP_RETENTION_DAYS`) | `7` | 만든 지 이 일수가 지난 worktree 제거. **0 이하는 부팅 실패**(레포 캐시 정리의 "≤0이면 조용히 30일" 불일치를 반복하지 않는다) |

02:30은 03:00 레포 캐시 정리보다 앞이다 — 같은 밤에 worktree를 치운 캐시가 회수 후보가 된다(캐시의 "30일 미사용" 조건은 별개).

**한 회차**

1. **잡 단위 락** `worktree-root/.worktree-cleanup.lock`(`RepoCacheCleanupJob`의 `.repo-cache-cleanup.lock`과 같은 방식). 다른 프로세스가 잡고 있으면 회차 건너뜀.
2. **진행 중 조회** `GET /worker/active-worktree-tasks`(4.3). 실패(네트워크·401·404 포함)면 **회차 전체 건너뜀** — 로그 한 줄.
3. **후보 열거** `worktree-root/{a}/{b}/{kind}-{id}` 중 정확히 이 형태만. `kind ∈ {task, deploy, design}`, `id`는 10진 양의 정수. 이름이 다르거나 심볼릭 링크(어느 단계든)인 것은 손대지 않는다. `{a}/{b}`가 곧 `localKey`(GitHub `owner/repo`, GitLab `_gitlab/{경로의 '/'→'+'}`) — 둘 다 2단계.
4. **1차 판정(락 밖)** — `WorktreeCleanupPlanner`(순수 함수):
   - `ACTIVE`: id가 진행 중 목록에 있음 → 건너뜀
   - `TOO_NEW`: 나이 < retention-days → 건너뜀. 나이 기준 시각 = max(폴더 mtime, 폴더 안 `.git` 파일 mtime)
   - `UNKNOWN`: mtime을 읽을 수 없음 → 건너뜀
   - `REMOVE`: 그 외
5. **레포 락 안 재확인 후 제거** — `GitRepoCache.tryWithRepoLock(localKey, …)`. 락을 못 잡으면 `BUSY`(이번 회차 건너뜀). 락 안에서 나이를 다시 판정(그사이 재시도로 재생성됐으면 mtime이 새것 → `TOO_NEW`). 통과하면 4.1 제거 절차.
6. **캐시 부재** — `repos-dir/{localKey}/.git`이 없으면 git 명령 없이 폴더만 재귀 삭제(prune·브랜치 삭제 생략).
7. **회차 로그** `worktree 정리 완료 — 삭제 {n} / 진행 중 {n} / 기간 미달 {n} / 사용 중(락) {n} / 판단 불가 {n} · 실패 {n}`, 삭제한 경로는 한 줄씩. 실패(삭제 중 예외)는 경로와 사유를 남기고 다음 후보로 진행.

**왜 나이만으로 충분한가(진행 중 목록과의 이중 보호)**: worktree는 작업 시작 때 만들어지고 작업은 시간 단위 타임아웃 안에 끝난다. stale 회수가 살아 있는 느린 워커의 작업을 대기로 되돌려 진행 중 목록에서 빠져도, 그 worktree는 7일보다 훨씬 새것이라 `TOO_NEW`로 보호된다.

### 4.3 API `GET /worker/active-worktree-tasks`

- 위치·인증: `WorkerController`(`/worker`, `ROLE_WORKER` = `X-Worker-API-Key`), 기존 `GET /worker/deployed-tasks`와 같은 패턴.
- 응답: `[{ "id": 12, "status": "IMPLEMENTING" }, …]` — 새 DTO `ActiveWorktreeTaskSummary(Long id, TaskStatus status)`.
- 포함 상태: `IMPLEMENTING`(구현중) · `DESIGNING`(디자인중) · `DEPLOYING`(배포중) · `UNDEPLOYING`(배포중지중) — 워커가 claim한 뒤 worktree를 실제로 쓰는 상태만. `APPROVED`·`DEPLOY_PENDING` 등 대기 상태는 아직 worktree를 안 쓰고, 재시도 시 어차피 재생성한다.
- 워커 구분 없이 DB 전체 기준(같은 PC 다른 워커의 작업도 보호). soft-delete 여부와 무관(진행 중이면 보호).
- 워커가 죽어도 `StaleTaskRecoveryJob`이 네 상태를 모두 회수하므로(2026-10-02 코드 확인) 목록에 영구히 남지 않는다.

## 5. 구성 요소

| 파일 | 역할 |
|---|---|
| `workerdaemon/WorktreeService.java` | `remove`를 레포 락 안으로 + 제거 절차(4.1) 통합 + 구현 브랜치 삭제 옵션. 클래스 주석의 "Phase 1은 보존" 갱신 |
| `workerdaemon/WorkerMainLoop.java` | 구현 성공 보고 뒤 `worktrees.remove(…)`(best-effort) |
| `workerdaemon/DeployService.java` | `target.deploy` 성공 직후 deploy worktree 제거(best-effort) |
| `workerdaemon/WorktreeCleanupPlanner.java` (신규) | 이름 해석·판정 순수 함수 |
| `workerdaemon/WorktreeCleanupJob.java` (신규) | 스케줄·잡 락·열거·레포 락·제거·로그 |
| `workerdaemon/WorkerProperties.java` | `WorktreeCleanup` 레코드 + 바인딩 검증 |
| `workerdaemon/WorkerHttpClient.java` | `activeWorktreeTasks()` |
| `controller/WorkerController.java`, `service/WorkerService.java`, `dto/ActiveWorktreeTaskSummary.java` (신규) | API |
| `application-worker.yml` | 설정 3개 + 레포 캐시 정리의 "⚠️ 구현 성공·배포 worktree는 지우는 곳이 없다" 경고 정정 |
| `workerdaemon/RepoCacheCleanupJob.java` | 클래스 주석의 한계 문구 정정 |
| `CLAUDE.md`, `TODOS.md` | 환경 메모·자주 보는 코드 갱신, TODOS 항목을 완료로 |

## 6. 테스트

- **`WorktreeCleanupPlannerTest`**: 이름 해석(`task-`/`deploy-`/`design-` + 숫자만; `task-`, `task-01x`, `task--1`, `Task-1`, `task-1.bak` 거부), `ACTIVE`, 7일 경계(정확히 7일 = 제거, 7일 − 1초 = 보존), mtime 읽기 실패 = `UNKNOWN`, max(폴더, `.git`) 규칙.
- **`WorktreeCleanupJobTest`** (임시 디렉터리 + 실제 git으로 캐시·worktree 생성, 기존 `RepoCacheCleanupJobTest` 방식):
  - 오래된 `task-N` → 폴더 제거 + `git worktree list`에서 사라짐 + 로컬 브랜치 삭제(다른 번호 `task-1N…` 브랜치는 남음)
  - 진행 중 / 기간 미달 / 레포 락 점유 → 남음
  - API 조회 실패 → 아무것도 안 지움
  - 캐시 없는 고아 폴더 → 직접 삭제
  - 형태가 다른 폴더·심볼릭 링크 → 손대지 않음(Windows 심볼릭 링크 권한 없으면 기존 관례대로 skip)
  - 잡 락 점유 시 회차 건너뜀, `enabled=false`면 no-op
- **즉시 정리**: `WorkerMainLoop` 구현 성공 → `remove` 호출(보고 뒤 순서), 구현 실패 경로 → 호출 안 함, `remove` 예외에도 성공 보고 유지. `DeployService` 배포 성공 → 제거, 배포 실패 → 보존.
- **`WorktreeService` 제거 절차**: 실제 git으로 remove → prune → 브랜치 삭제, 접두 일치 브랜치 보호.
- **API**: 네 상태만 반환(다른 상태·대기 상태 제외), 워커 키 없으면 401.
- **설정 바인딩**: 잘못된 cron·retention-days ≤ 0 → 바인딩 실패 메시지에 설정 이름, `-`는 끔.

## 7. 배포·하위 호환

1. **API 먼저, 워커 나중.** 새 워커가 옛 API를 만나면 404 → 조회 실패로 보고 정리 회차를 건너뛴다(fail-closed). 순서가 바뀌어도 잘못 지우지 않는다. 옛 워커는 새 API를 부르지 않으므로 영향 없음.
2. **라이브(Windows 스택)**: API·워커만 재기동(프론트 무관). 재기동 직후부터 성공한 작업은 즉시 정리된다. 첫 02:30 회차에 2026-09-22 생성 `task-1`(7일 초과)이 지워지고, 2026-10-01 생성 `deploy-1`은 2026-10-08 회차에 지워진다.
3. 처음부터 지우는 것이 걱정되면 `WORKTREE_CLEANUP_ENABLED=false`로 배포하고, `worktree-root` 아래 worktree 목록과 수정 시각을 직접 확인한 뒤 켠다(끈 동안은 즉시 정리만 동작하고 회차 로그는 없다). dry-run 모드는 두지 않는다(레포 캐시·첨부 정리와 같은 선택).

## 8. 범위 밖

- PR/MR 병합·종료 상태 추적.
- 인터뷰 서비스의 체크아웃(별도 경로, 별도 수명).
- 레포 캐시 정리의 `REPO_CACHE_CLEANUP_UNUSED_DAYS ≤ 0` 처리 정정(PR #61 검토 지적 #4) — 이 설계는 새 설정에서만 같은 실수를 피한다.
