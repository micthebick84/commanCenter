# 재기동 내성 최소안 — 기동 시 정합 · 고아 claude 방지 · Windows 스크립트 보강 설계

- 날짜: 2026-10-23
- 상태: 사용자 승인 대기 (brainstorming: graceful shutdown 검토 보고서 → 최소안 결정 질문 3개 → M1·M2·M3 설명 확인 → 검토자 점검 반영: 미전송 보고 제외 · API 기동 유예 · 인터뷰 상한은 턴 단위)
- 범위: netisMaker 백엔드(API·워커 데몬, 같은 Spring Boot 앱) + 인터뷰 서비스(Node) + Windows 기동·정지 스크립트(`scripts/start-public.ps1`·`stop-public.ps1`). 프론트엔드와 맥 스크립트는 바꾸지 않는다.
- 근거 보고서: 2026-10-23 "Symphony 라이브 스택 graceful shutdown 검토"(세션 공유 노트 `graceful-shutdown-review.md`). 이 문서는 그 보고서 §4.4의 **최소안 M1·M2·M3**만 다룬다.

## 1. 목표와 이유

Windows 라이브 스택의 정지는 전부 `taskkill /T /F`(강제 종료)다. 그래서 워커·인터뷰 서비스가 처리하던 일이 중간에 끊긴다. 끊긴 뒤에는 아래 문제가 생긴다.

1. **워커가 같은 이름(`WORKER_ID`, 라이브 `win-worker-1`)으로 다시 뜨면 죽은 작업이 오래 고착된다.**
   - heartbeat가 10초 안에 다시 오므로 `StaleTaskRecoveryJob`의 "워커 사망" 판정이 걸리지 않는다(`service/StaleTaskRecoveryJob.java:112-114`).
   - 처리 시간 상한(구현 직접 60·리뷰 95·SDD 260분)까지 `구현중`으로 남는다. 새 워커는 그 작업을 모르고 다른 일을 한다.
2. **인터뷰 서비스가 다시 뜨면, 하던 세션이 10분 뒤 실패로 끝난다.**
   - RUNNING 세션이 heartbeat 두절 600초 뒤 FAILED가 된다(`service/InterviewStaleRecoveryJob.java:92-113`).
   - 실패는 되돌릴 수 없어 사용자가 인터뷰를 처음부터 다시 해야 한다. RUNNING → QUEUED 전이가 없다(`service/InterviewService.java:29-38`).
3. **claude가 고아로 남을 수 있다.**
   - 워커가 claude 실행을 기다리다 인터럽트되면 claude를 죽이지 않고 빠져나간다(`workerdaemon/ClaudeExecAdapter.java:225-229`, 시간 초과 때만 트리 정리).
   - JVM이 Gradle 데몬 밑에 있어 스크립트의 트리 kill이 JVM에 직접 닿지 않으면, 데몬이 JVM만 죽인다. 이때 그 밑의 claude.exe가 부모 없이 계속 돌며 토큰을 쓴다.
4. **스크립트가 진짜 프로세스를 모른다.**
   - `.run/*.pid`는 cmd 래퍼 pid다. 살아 있는지를 `Get-Process -Id`로만 보므로 pid 재사용에 속는다(`scripts/start-public.ps1:95-101`, `scripts/stop-public.ps1:20-31`).
   - worker·interview에는 포트가 없어 정지 후 남은 프로세스를 정리하는 보강도 없다.

이 최소안은 **종료가 어떤 식으로 일어나든**(사람의 재기동, 크래시, PC 재부팅) 다시 뜬 직후 상태를 바로 맞추고, 고아 claude를 막고, 스크립트가 진짜 JVM·node를 다루게 한다. 정상 종료 절차(드레인·자기 종료)는 이 다음 단계다.

### 1.1 이번에 하지 않는 것

- 드레인 모드(새 claim 중단 → 마무리 → 자기 종료), `stop-public.ps1 -Graceful`
- `bootRun` → `java -jar` 전환, API graceful shutdown(SSE 정리·메일 큐 대기)
- 인터뷰 서비스의 결과 보고 재시도(`postQuestion`/`postPlan`)
- 중단된 구현 시도의 미커밋 변경 살리기(기동 때 남은 worktree를 WIP 커밋하기)
- 고아 claude **자동 종료**(알리기만 한다, §5.5)
- 맥 스크립트(`start-all.sh`·`stop-all.sh`) — 사용자 결정
- 프론트엔드 화면 변경

### 1.2 절대 안 되는 것

- 사용자가 직접 띄운 claude(터미널·데스크톱 앱)를 스크립트가 죽이는 것
- 새 워커·인터뷰 서비스가 예전 API(새 엔드포인트 없음)를 만나 영영 작업을 안 잡는 것
- 지금 stale 회수 잡의 판정·전이가 바뀌는 것(heartbeat 두절·시간 초과 경로는 그대로). 예외는 API 기동 직후 "워커 사망" 판정 유예 하나뿐이다(§3.7)
- 워커가 이미 끝냈지만 보고가 아직 API에 닿지 않은 작업(미전송 보고)을 기동 정합이 재큐하는 것(§3.2)
- 기존 `.run/<이름>.pid` 파일(cmd 래퍼 pid)의 의미가 바뀌어, 지금 쓰는 수동 재기동 절차(`taskkill /PID <래퍼> /T /F` → pid 삭제 → 작업 스케줄러)가 깨지는 것
- 라이브 본 체크아웃에서 빌드·테스트하는 것(구현은 `.claude/worktrees/graceful-shutdown-minimal`, 브랜치 `feature/graceful-shutdown-minimal`)

## 2. 결정

| # | 질문 | 결정 | 누가 |
|---|---|---|---|
| D1 | 워커 재기동으로 중단된 구현의 다음 상태 | **승인됨으로 자동 재큐** — 시도 번호 +1(재시도 버튼과 같은 처리) | 사용자 |
| D2 | D1의 상한 | 같은 작업은 재기동 자동 재큐 **2회까지**, 3번째는 `구현실패` | 설계(제안, 사용자 확인) |
| D3 | 인터뷰 서비스 재기동으로 중단된 턴 | **인터뷰대기(QUEUED)로 돌려 같은 답변으로 다시 실행**, 상한 2회, 3번째는 실패 | 사용자 |
| D4 | 맥 스크립트 | 손대지 않음. Windows 스크립트만 | 사용자 |
| D5 | 구현 외 단계의 재기동 회수 | 지금 stale 회수 전이를 그대로 따른다(분석 재시도·디자인/배포중지/종료 재큐·배포 실패) | 설계 판단 |
| D6 | 중단된 구현 시도의 미커밋 변경 | 살리지 않는다. 다음 claim이 같은 경로의 worktree를 강제 제거한다(`workerdaemon/WorktreeService.java:100-110`) | 설계 판단 |
| D7 | 첫 인터뷰 턴(Claude 세션 아직 없음)이 중단된 경우 | 시작 안내(kickoff)부터 다시 | 설계 판단 |
| D8 | 고아 claude 처리 | 정지 스크립트가 목록만 보여 준다(죽이지 않음) | 설계 판단 |
| D9 | 한 서비스만 정지하는 옵션 | `stop-public.ps1 -Only <이름…>` 추가 | 설계 판단 |
| D10 | 레포 캐시의 짧은 git 조회(`GitRepoCache.capture`)도 인터럽트 때 자식 정리 | 같이 고친다 | 설계 판단 |
| D11 | 보고를 잃어 dead-letter에 대기 중인 작업 | 워커가 기동 알림에 그 taskId 목록을 실어 보내고, API는 그 작업을 건드리지 않는다. 재전송이 늦은 보고 정합으로 마무리한다 | 설계 판단(검토자 지적) |
| D12 | API가 오래 내려가 있던 뒤(PC 재부팅·긴 정지) stale 잡이 워커보다 먼저 "워커 사망"으로 판정하는 문제 | API 기동 뒤 단계별 사망 임계만큼은 "워커 사망" 판정을 쉰다(인터뷰 잡의 기동 유예와 같은 방식). 재부팅 뒤에도 워커가 다시 뜨면 자동 재큐된다 | 설계 판단(검토자 지적, D1의 취지를 긴 정지까지 넓힘) |
| D13 | 인터뷰 재기동 재큐 상한의 단위 | **턴 단위**. 그 턴이 정상으로 끝나면(질문·플랜 보고) 횟수를 0으로 되돌린다. 며칠짜리 인터뷰가 배포 재기동 몇 번으로 실패하지 않게 하려는 것이다 | 설계 판단(검토자 지적) |

## 3. M1 — 기동 시 정합

### 3.1 원리

워커는 한 프로세스가 한 번에 한 작업만 처리한다(`workerdaemon/WorkerMainLoop.java:106-148`, 스케줄 메서드는 자기 자신과 겹치지 않음). 따라서 워커가 **첫 claim 전에** "방금 떴다"고 알리는 시점에, 그 `workerId`로 잡혀 있는 진행 중 작업은 모두 이전 프로세스의 것이고 이미 죽었다. 인터뷰 서비스도 같은 성질이다(동시 N턴이지만 claim은 한 프로세스가 한다).

**전제:** `WORKER_ID`는 동시에 떠 있는 프로세스마다 다르다(라이브 `win-worker-1`·`interview-worker-1`, 맥 `worker-N`). 같은 이름이 두 개 뜨면 살아 있는 쪽의 작업이 회수된다(§7 위험). M3의 pid 확인 강화가 실수로 인한 중복 기동을 줄인다.

### 3.2 API — 워커 기동 알림

- `POST /worker/startup`, 본문 `{ "workerId": "...", "pendingTaskIds": [ ... ] }`. 인증은 다른 `/worker/*`와 같은 워커 API 키다.
  - `pendingTaskIds`는 워커의 dead-letter 대기 보고(`SilentLossTracker.snapshotPending()`)의 taskId 목록이다. 없으면 빈 배열이다.
  - 이 작업들은 워커가 이미 끝냈는데 보고만 아직 닿지 않았을 수 있다. 재큐하면 끝난 구현을 처음부터 다시 하고, 앞의 PR이 고아가 된다(늦은 PR_CREATED는 시도 번호가 달라 무시됨). 그래서 **건드리지 않는다**.
  - 이 작업들은 `DeadLetterReplayJob`의 재전송이 서버의 늦은 보고 정합(`WorkerService.recordResult`)으로 마무리한다. 재전송마저 실패하면 기존 stale 잡이 처리한다.
- 응답 `200 { "recovered": [ { "taskId", "from", "to" } ], "skipped": [taskIds] }`. 워커가 로그로 남긴다.
- 처리는 한 트랜잭션이다.
  1. `worker_id = :workerId`이고 진행 중 상태(분석중·디자인중·구현중·배포중·배포중지중·종료중)인 작업을 행 잠금(FOR UPDATE)으로 읽는다. 삭제된 작업과 `pendingTaskIds`에 든 작업은 제외한다(`findInFlightClaimed`와 같은 조건 + `workerId`).
  2. 작업마다 아래 전이를 적용하고 `worker_id`·`claimed_at`을 비운다.
  3. 이력(`task_status_history`)은 행위자 종류 `system`, 행위자 `worker-restart`로 남긴다. 사유에는 "워커 {workerId} 재기동으로 중단"을 넣는다.

| 상태 | 전이 | 비고 |
|---|---|---|
| 분석중 | 재시도 여유 있으면 분석대기(`retry_count`+1), 없으면 분석실패 | 지금 stale 회수와 같은 규칙 |
| 디자인중 | 디자인대기 | 같음 |
| 구현중 | **재기동 재큐 횟수 < 2**: 승인됨, 시도 번호 +1, 재기동 재큐 횟수 +1. **아니면**: 구현실패, 사유 "워커 재기동으로 중단(자동 재큐 2회 소진)" | 승인됨 전이 때 지우는 값은 `TaskService.retryImplementation`(`service/TaskService.java:487-499`)과 같다: `failure_reason`, `head_branch`, `head_sha`, `pr_url`, `pr_number`, `implementation_log`, `implementation_timeout_minutes`. 구현 방식은 그대로 둔다 |
| 배포중 | 배포실패, 부분 배포 로그 보존·스트림 종료 | `StaleTaskRecoveryJob.java:139-146`과 같음 |
| 배포중지중 | 배포중지대기, 스트림 종료 | 같음 |
| 종료중 | 종료대기(`close_progress` 유지) | 같음 |

- 전이 코드는 `StaleTaskRecoveryJob`과 공유한다. 회수 전이를 별도 컴포넌트(예: `TaskRecovery`)로 빼고, stale 잡과 기동 정합이 같은 메서드를 부른다. stale 잡의 구현중 전이(→ 구현실패)는 바뀌지 않는다. **기동 정합 경로에서만** 구현중이 재큐된다.
- 승인됨 재큐의 이력 사유 예: "워커 win-worker-1 재기동으로 중단 → 자동 재큐 (3회째 시도, 재기동 재큐 1/2)".
- 재기동 재큐 횟수는 사람이 `구현 재시도`(`retryImplementation`)를 누르면 0으로 되돌린다. 사람이 한 번 본 작업이므로 상한을 새로 센다. 재기동 재큐로 정합화된 작업이 다시 claim되면 시도 번호가 올라가 있으므로, 죽은 시도의 늦은 보고는 기존 시도 번호 대조(스펙 2026-10-15 §5.6)로 걸러진다.

### 3.3 API — 인터뷰 서비스 기동 알림

- `POST /worker/interviews/startup`, 본문 `{ "workerId": "..." }`. 인증은 `/worker/interviews/*`와 같다.
- 응답 `200 { "released": [ids], "failed": [ids] }`.
- `worker_id = :workerId`이고 RUNNING인 세션(질문 세션 포함)을 행 잠금으로 읽어 세션마다 처리한다. 상태 전이는 `InterviewService`가 맡는다(단일 진입점 원칙).
  - **재기동 재큐 횟수 < 2 → 새 전이 `release`**: RUNNING → QUEUED.
    - `worker_id`·`claimed_at`을 비운다.
    - `last_activity_at`을 지금으로 갱신한다(`touch`). 그래야 QUEUED 체류 60분 만료가 되돌린 시점부터 다시 센다.
    - 재기동 재큐 횟수를 +1 한다.
    - 소유 task가 있으면 `인터뷰중`으로 미러링한다(`submitAnswer`와 같은 미러, `InterviewService.java:432-434`).
    - 시스템 노트 "인터뷰 서비스가 다시 시작돼 이 단계를 다시 진행합니다."를 남긴다. SSE로 노트와 상태(QUEUED)를 밀어 준다. 스트림은 닫지 않는다(세션이 계속 진행되므로).
  - 재기동 재큐 횟수는 **턴 단위**다(D13). 그 턴이 정상으로 끝나 질문(`recordQuestion`)이나 플랜(`recordPlan`)이 기록되면 0으로 되돌린다. 같은 턴이 연달아 끊길 때만 상한에 걸린다.
  - **아니면**: 기존 `fail` 전이. 사유 "인터뷰 서비스 재기동으로 중단(자동 재개 2회 소진)". 노트·상태(FAILED)를 밀고 스트림을 끝낸다(`InterviewStaleRecoveryJob.java:80-85`와 같은 순서). task는 기존 규칙대로 승인대기로 간다.
- 다시 claim되면 기존 claim 응답(`InterviewService.claim`, `:154-174`)이 그대로 쓰인다. `claudeSessionId`가 있으면 resume하며 마지막 답변(`role=user, kind=answer`)이 다시 들어간다. 없으면(첫 턴) kickoff부터 다시 돈다. 체크아웃(`work_dir`)은 러너가 턴마다 다시 준비한다.
- `InterviewService` 클래스 주석의 전이 목록에 `release ─► RUNNING → QUEUED (인터뷰 서비스 재기동 정합)`을 추가한다.

### 3.4 DB — V32

- `com.task.restart_requeue_count int not null default 0`
- `com.interview_session.restart_requeue_count int not null default 0`
- 번호는 구현 시점의 최신 마이그레이션 다음 번호다(2026-10-23 main 기준 V31이 마지막). 엔티티 `Task`·`InterviewSession`에 필드를 추가한다. 테스트(H2 `test` 프로필)에서도 마이그레이션이 통과해야 한다.

### 3.5 워커 — 첫 claim 전 알림

- `WorkerHttpClient.startup(pendingTaskIds)`를 추가한다.
- `WorkerMainLoop`에 "기동 알림 완료" 플래그를 둔다. `pollAndProcess`는 `nextTask` 전에 플래그를 확인한다.
  - 아직이면 그 시점의 dead-letter 대기 목록(`SilentLossTracker.snapshotPending()`)의 taskId를 모아 `startup()`을 부른다.
  - 성공하면 회수 목록을 로그(INFO)로 남기고 플래그를 켠다.
  - **404면**(예전 API) WARN 로그 "API가 /worker/startup을 모른다 — 기동 정합 건너뜀"을 남기고 플래그를 켠다. 막히지 않게 하려는 것이다.
  - 그 밖의 실패(연결 거부·5xx·타임아웃)는 WARN 로그만 남기고 이번 틱을 건너뛴다. 다음 틱(5초)에 다시 시도한다. **알림이 성공하거나 404를 받기 전에는 claim하지 않는다.**
- heartbeat와 다른 스케줄 잡은 이 플래그와 무관하게 돈다.

### 3.6 인터뷰 서비스 — 첫 claim 전 알림

- `JavaApiClient.startup()`을 추가한다.
- `ClaimLoop.start()`는 while 루프 전에 알림을 성공(또는 404)할 때까지 `pollIntervalMs` 간격으로 다시 시도한다. 그동안 `stop()`이 불리면 바로 끝난다. 결과는 한 줄 로그로 남긴다(`[claim-loop] startup: released=[…] failed=[…]`). 404는 경고 한 줄 후 진행한다.

### 3.7 API — stale 잡의 기동 유예와 행 잠금

- **문제(D12):** `StaleTaskRecoveryJob`은 API 기동 직후 바로 돈다. API가 내려가 있는 동안에는 워커 heartbeat가 기록될 수 없다.
  - 그래서 정지가 단계별 사망 임계(구현 300초 등)보다 길면, 워커가 다시 뜨기 전에 진행 중 작업이 "워커 사망"으로 회수된다. 구현은 구현실패가 된다.
  - PC 재부팅(로그온 작업이 API → 프론트 → 워커 순으로 띄움)이 대개 여기에 해당한다. API만 오래 내려갔다 올라와도 살아 있는 워커의 작업이 같은 이유로 회수된다.
- **변경:**
  - API 기동 시각을 기억한다(`InterviewStaleRecoveryJob.startedAt`과 같은 방식).
  - 작업마다 그 상태의 사망 임계(`deadSec`)만큼 API가 떠 있기 전에는 "워커 사망" 판정(`workerDead`)을 하지 않는다.
  - 처리 시간 상한(`hungBackstop`) 판정은 유예 없이 그대로 한다.
  - 그 사이에 워커가 다시 뜨면 기동 정합(§3.2)이 처리한다. 워커가 끝내 안 뜨면 유예가 끝난 뒤 지금처럼 회수된다.
- **행 잠금:** stale 잡도 진행 중 작업을 행 잠금(FOR UPDATE SKIP LOCKED)으로 읽는다. 기동 정합과 같은 행을 동시에 바꿔 한쪽 변경이 덮어써지는 일을 막는다. 잠긴 행은 다음 회차에 다시 판정한다.

## 4. M2 — 고아 claude 방지

- `ClaudeExecAdapter.run`(`workerdaemon/ClaudeExecAdapter.java:212-246`)에서 `p.waitFor(...)`가 `InterruptedException`을 던지면, `destroyTree(p)`(`:265-268`)를 부른 뒤 인터럽트를 다시 던진다.
  - 이 메서드를 쓰는 모든 경로(구현·분석·디자인·Dockerfile 생성·종료 노트, `execWithoutTools` 포함)가 같이 보호된다.
  - 출력 읽기 스레드는 프로세스가 죽으면 끝난다.
- `GitRepoCache.capture`(`workerdaemon/GitRepoCache.java:306-315`)도 인터럽트 때 `destroyForcibly`한 뒤 다시 던진다(D10).
- 이 수정은 워커가 정상 절차로 내려갈 때(맥의 SIGTERM, 이후 드레인 단계) 효과를 낸다. Windows 강제 종료 때의 고아는 M3가 트리 정지로 막는다.

## 5. M3 — Windows 스크립트 보강

공통 제약: 두 스크립트는 **ASCII만** 쓴다(PowerShell 5.1이 BOM 없는 UTF-8을 잘못 읽음, 스크립트 머리 주석). 대상 이름은 `auth, api, front, worker, interview`다. cloudflared는 지금처럼 이름으로만 확인하고 기록·정지 대상에서 뺀다.

### 5.1 기록 파일 `.run/<이름>.json`

- 기존 `.run/<이름>.pid`(cmd 래퍼 pid 한 줄)는 **그대로 쓴다**. 옆에 json을 하나 더 쓴다.

```json
{ "name": "worker", "wrapperPid": 24452, "wrapperStart": 134055181880000000,
  "appPid": 5304, "appStart": 134055182150000000, "appImage": "java.exe" }
```

- 시작 시각은 `(Get-Process -Id <pid>).StartTime.ToFileTimeUtc()`(정수)다. 비교는 정확히 같은 값인지로 한다.
- `appPid`를 못 찾으면 `null`로 쓰고 경고 한 줄을 남긴다. 그 서비스는 예전 방식(래퍼만)으로 다룬다.
- json은 그 서비스를 새로 띄웠을 때만 쓴다. 포트가 이미 열려 있거나 살아 있어서 건너뛴 서비스의 json은 건드리지 않는다.

### 5.2 실제 프로세스 찾기 (`start-public.ps1`)

서비스가 준비된 뒤(기존 `Wait-Log`/`Wait-Port` 성공 뒤) 찾는다.

| 서비스 | 찾는 방법 |
|---|---|
| api / auth / front | 그 포트(8090 / 9000 / 3001)에서 LISTEN 중인 프로세스(`Get-NetTCPConnection … OwningProcess`) |
| worker | `Win32_Process`에서 `java.exe`이면서 명령줄에 `$Root\build\classes\java\main`과 `--spring.profiles.active=worker`가 모두 들어 있는 것. 여럿이면 래퍼 시작 이후에 뜬 것 중 가장 최근 것 |
| interview | 래퍼(cmd)의 직계 자식 `node.exe`(`ParentProcessId` = 래퍼 pid) |

- worktree에서 돌리는 테스트 JVM은 classpath 경로가 달라 섞이지 않는다.
- api JVM은 포트로 찾으므로 명령줄 구분이 필요 없다.

### 5.3 살아 있는지 확인 (`Test-PidAlive`)

1. json이 있고 `appPid`가 있으면: 그 pid의 프로세스가 있고 `StartTime`이 `appStart`와 같을 때만 살아 있다.
2. 아니면(예전 방식으로 뜬 서비스, `appPid` null): pid 파일의 프로세스가 `cmd.exe`이고 명령줄에 `\.run\<이름>.log`가 들어 있을 때만 살아 있다. 이 규칙은 머지 직후 지금 떠 있는 서비스에도 바로 적용된다.

### 5.4 정지 (`stop-public.ps1`)

- 정지 순서는 지금처럼 `interview, worker, front, api, auth`다. 이름마다:
  1. json이 있고 `appPid`의 시작 시각이 맞으면 `taskkill /PID <appPid> /T /F`. JVM 밑의 claude·git·docker 자식까지 같이 정리된다.
  2. pid 파일의 래퍼가 §5.3의 2번 규칙에 맞으면 `taskkill /PID <래퍼> /T /F`. 맞지 않으면 "[gone]"으로 보고 죽이지 않는다. 지금처럼 남의 프로세스를 죽이는 일을 막는다.
  3. pid 파일과 json을 지운다.
- 그다음 기존 포트 정리(9000/8090/3001)를 한다. `-Only`일 때는 고른 이름의 포트만 정리한다.
- 출력 형식은 지금의 `[stop] 이름 (pid …)` / `[gone] 이름`을 유지하고, appPid를 같이 보여 준다.

### 5.5 고아 claude 알림

정지가 끝난 뒤 `Win32_Process`에서 아래를 모두 만족하는 프로세스를 찾아 **목록만** 보여 준다(pid, 시작 시각, 명령줄 앞 200자, 직접 정리할 때 쓸 `taskkill` 명령 예시).

- 이름이 `claude.exe`이고 실행 경로가 스택이 쓰는 claude CLI와 같다(`CLAUDE_CLI`, 없으면 `%USERPROFILE%\.local\bin\claude.exe`. `start-public.ps1`과 같은 규칙으로 `public.env`를 읽어 결정)
- 부모 pid의 프로세스가 없거나, 있어도 그 claude보다 나중에 시작됐다(pid 재사용)

죽이지 않는 이유: 사용자가 터미널에서 직접 띄운 claude도 같은 실행 파일이라 이 스택이 띄운 것인지 확실히 가릴 수 없다(§1.2). 데스크톱 앱의 claude는 경로가 달라 목록에도 안 나온다.

### 5.6 한 서비스만 정지 `-Only`

- `.\scripts\stop-public.ps1 -Only worker`, `-Only worker,interview`처럼 쓴다. 대상 이름 외의 값이면 오류로 끝난다. `-KeepWorkers`와 같이 쓰면 오류다.
- 단건 재기동 절차가 "`stop-public.ps1 -Only <이름>` → `Start-ScheduledTask -TaskName netisMaker-public`"이 된다. 작업 스케줄러는 이미 떠 있는 서비스를 건너뛴다.
- 스크립트 머리 주석의 Usage에 추가한다.

## 6. 검증

- **M1 API**(worktree, `@ActiveProfiles("test")` H2 관례):
  - 기동 알림이 단계별 전이를 정확히 적용하는지
  - 구현중의 재큐 상한(0→1→2→구현실패)과 지우는 필드
  - `retryImplementation`이 횟수를 0으로 되돌리는지
  - 다른 `workerId`의 작업과 삭제된 작업은 건드리지 않는지
  - `pendingTaskIds`에 든 작업은 건드리지 않는지
  - stale 잡의 기동 유예: 기동 직후에는 heartbeat가 낡아도 회수하지 않고, 유예가 지나면 회수하고, 처리 시간 상한은 유예와 무관한지
  - 인터뷰 `release`(상한·`last_activity_at` 갱신·task 미러·노트), 질문·플랜 기록 때 횟수가 0이 되는지
  - 상한 소진 시 FAILED
  - 질문 세션도 같은 처리를 받는지
  - 엔드포인트 인증(워커 키 없으면 401/403)
- **M1 워커**: 알림 성공 전에는 `nextTask`를 부르지 않는지, 404면 계속 진행하는지, 5xx면 다음 틱에 다시 시도하는지(단위 테스트).
- **M1 인터뷰 서비스**(vitest): `ClaimLoop`가 알림 성공 전에 claim하지 않는지, 404면 진행하는지, 재시도 중 `stop()`이면 끝나는지.
- **M2**: 오래 도는 가짜 명령을 실행하는 스레드를 인터럽트하면 그 프로세스(와 자식)가 없어지는지. Windows에서도 돌 수 있게 만든다.
- **M3**: 라이브 스택에서는 시험하지 않는다.
  - 두 스크립트를 PowerShell 5.1로 파싱 확인한다.
  - pid 찾기·시작 시각 비교·트리 정지·고아 알림은 임시 `cmd /c node -e "setInterval(()=>{},1000)"` 같은 가짜 프로세스와 임시 `.run` 폴더로 따로 시험한다. 시험용 래퍼 함수나 `-RunDir` 같은 매개변수를 두는 방식은 구현자가 정한다.
  - 라이브 반영은 §8 순서로 사람이 확인한다.

## 7. 위험

- **같은 `WORKER_ID` 중복 기동:** 살아 있는 프로세스의 작업이 회수된다. 구현은 시도 번호가 올라가 늦은 보고가 거부된다. 분석·디자인·배포·종료는 재큐된 작업을 살아 있는 쪽이 다시 claim하면 늦은 보고가 받아들여질 수 있다. 중복 기동을 줄이는 것이 M3의 pid 확인이다.
- **인터뷰 재실행의 대화 기록 중복:** 중단된 턴의 일부가 Claude 세션 기록에 남은 채로 같은 답변이 한 번 더 들어간다. 대개 그대로 이어지지만, 모델이 "이미 답했다"고 볼 수 있다. 중단된 턴에 쓴 비용은 기록되지 않는다.
- **토큰 재소모:** 자동 재큐는 중단된 구현·인터뷰 턴을 처음부터 다시 돈다(SDD 최대 4시간 분량). 상한 2회가 그 반복을 묶는다.
- **기동 유예 동안의 늦은 회수:** API 기동 뒤 최대 사망 임계(구현·디자인 300초)까지는 정말 죽은 워커의 작업도 회수가 늦어진다. 지금도 그 정도는 기다리므로 차이는 작다.
- **미전송 보고 제외의 빈틈:** dead-letter 파일이 지워졌거나 손상됐다면 그 작업은 재큐된다(끝난 구현을 다시 하게 됨).
- **pid 찾기 실패:** 찾지 못하면 예전 방식(래퍼 트리만)으로 떨어진다. 그때는 JVM이 트리 밖이면 고아 claude가 남을 수 있고, 정지 후 알림 목록에 나온다.

## 8. 라이브 반영 순서

1. PR 머지 → 본 체크아웃 `git pull --ff-only`.
2. **API 먼저 재기동한다.** 새 엔드포인트와 V32 마이그레이션은 API 기동 때 적용된다. 재기동 전 진행 중 작업 확인은 지금 관행대로 한다.
   - 새 API + 예전 워커: 예전 워커는 알림을 안 보낼 뿐 동작은 지금과 같다.
3. 워커·인터뷰 서비스를 재기동한다. 첫 claim 전에 알림을 보낸다.
   - 새 워커 + 예전 API: 404를 받고 그냥 진행한다.
4. 스크립트는 첫 정지 때 json이 없으므로 예전 방식(+ 래퍼 명령줄 확인)으로 동작한다. 그 뒤 기동부터 json이 생긴다.
5. 프론트 재빌드는 필요 없다.
6. 운영 메모(작업 스케줄러 기동 절차)의 단건 재기동 절차를 `-Only`로 바꾼다.

## 9. 구현자에게 맡기는 것

- `TaskRecovery`(가칭) 컴포넌트 이름·위치, 리포지토리 쿼리 이름
- 응답 DTO 이름, 로그 문구의 세부 표현(위 사유 문구의 뜻은 유지)
- json 파일을 쓰고 읽는 PowerShell 함수 구성
- M3 시험 방법의 세부(§6)
