# 구현결과 false-failure 재발방지 설계

- 날짜: 2026-06-27
- 상태: 설계 승인됨, 구현 플랜 작성 전

## 배경 / 문제

task16(`장비관리 화면 개발`)은 워커(mac-worker-1)가 구현·커밋·푸시·PR 생성까지 **전부 성공**(PR #10, 브랜치 `netismaker/task-16`)했는데도 DB에는 `구현실패`로 기록됐다. `failure_reason = "Stale 회수: 워커 mac-worker-1 응답 없음(heartbeat) (구현 중단)"`, `pr_url/head_branch/head_sha/implementation_log` 모두 null.

근본 원인은 그날(2026-06-22) 아직 살아있던 SSE/OSIV HikariCP 풀 고갈 버그(같은 날 19:32 KST에 PR #11로 수정, task16은 15:19~15:35 KST 실행)로 백엔드 DB 엔드포인트가 줄줄이 500을 낸 것이다. 그 결과:

1. 워커의 `POST /worker/heartbeat`(DB write)가 실패 → `worker_heartbeat.last_seen_at` 미갱신 → 60초 후 `StaleTaskRecoveryJob`이 `workerDead=true`로 판정해 task16을 `IMPLEMENTATION_FAILED`로 회수.
2. 워커의 최종 `POST /worker/result(PR_CREATED)` 성공 보고도 실패 → DB에 PR 메타 미기록.

루트 풀 누수는 이미 고쳐졌지만, 아래 세 가지 구조적 취약점은 그대로 남아 동일한 false-failure를 재발시킬 수 있다.

### 코드 레벨 취약점

- `WorkerMainLoop.processImplementation`의 성공 보고 `http.postResult(...PR_CREATED)`가 try/catch 밖(`WorkerMainLoop.java:244`). 예외 시 `pollAndProcess`의 `catch(Throwable)`로 떨어져 **성공한 작업을 `safePostImplementationFailure`로 보고**(그마저 실패).
- `WorkerService.recordResult`는 작업이 in-flight 상태가 아니면 무조건 409 거부(`WorkerService.java:126-132`) → 회수 뒤 도착한 늦은 성공 보고가 버려짐.
- `StaleTaskRecoveryJob`의 `worker-dead-threshold-seconds:60`이 단계 무관 적용 → 10~60분 걸리는 구현 중 60초 heartbeat 블립에도 in-flight 작업을 죽임.

## 목표

일시적 백엔드 장애/heartbeat 블립이 **이미 성공한 구현을 실패로 만들지 않도록** 다층 방어를 추가한다. 진짜 죽은 워커는 60분 절대 백스톱이 계속 잡는다.

비목표: SSE/OSIV 풀 누수 자체(이미 수정됨), 분석/배포 단계의 결과 의미 변경, task16 기존 레코드 보정(범위 밖, 후속).

## 설계

세 개의 독립 변경 단위. 어느 하나만으로도 부분 방어가 되며, 셋이 합쳐지면 회수가 와도 워커의 늦은 보고가 정합화로 복구한다.

### 1. 워커 — 성공 보고 복원력

대상: `workerdaemon/WorkerHttpClient.java`, `workerdaemon/WorkerMainLoop.java`, `workerdaemon/WorkerProperties.java`, `application-worker.yml`

- `WorkerHttpClient`에 `postResultWithRetry(taskId, req)` 추가: `RestClientException`에 대해 지수 백오프 재시도.
  - 설정 신규: `netis-maker.worker.result-report-max-retries`(기본 5), `result-report-backoff-ms`(기본 2000, 지수 증가). 총 재시도 윈도우가 수 분에 달해 짧은 백엔드 장애를 견딘다.
- **성공 보고(PR_CREATED / COMPLETED / DEPLOYED)의 재시도 최종 실패는 rethrow하지 않는다** — `log.error`로 크게 남기고 삼킨다. 그래야 `pollAndProcess`의 `catch(Throwable)`가 발동해 성공을 실패 보고로 뒤집는 일이 없다. (API 지각 수신(2번)이 최종 안전망.)
- 분석/구현/배포 3경로의 *성공* 보고는 모두 `postResultWithRetry` 경유. 실패 보고 헬퍼(`safePostImplementationFailure` 등)도 동일 재시도 헬퍼 위에서 동작하되 기존처럼 최종 실패는 로그만.
- 실패 보고가 성공 보고 경로로부터 트리거되지 않도록 `processImplementation`/`processAnalysis`/`processDeploy`의 최종 보고 호출을 broad try 밖으로 분리(또는 성공 플래그로 catch에서 실패 보고 생략).

### 2. API — 멱등 지각 수신 (정합화)

대상: `service/WorkerService.java`

`recordResult`의 strict in-flight 가드(`126-132`) 앞에 정합화 분기 추가:

- 조건: `req.status() == PR_CREATED` && `current == IMPLEMENTATION_FAILED` && `req.prUrl()` 유효 && `req.headBranch()` 유효.
- 동작: `status=PR_CREATED`로 되돌리고 `prUrl/prNumber/headBranch/headSha/implementationLog` 채움. `failureReason`은 클리어. `task_status_history`에 `from=IMPLEMENTATION_FAILED, to=PR_CREATED, actor_type=worker, actor_id=req.workerId(), reason="지각 보고 정합화: stale 회수 → PR_CREATED"` 1건 기록.
- 소유 검증: 회수로 `worker_id=null`이 되므로 `workerId` 일치 검사는 생략. PR 메타 유효성으로 대체(워커는 실제 PR 생성 시에만 PR_CREATED 보고하므로 위조 위험 낮음).
- `current`가 `IMPLEMENTATION_FAILED`가 아닌 다른 비-inflight 상태면 기존 409 유지(재클레임된 새 사이클은 정상 경로가 처리).
- 정합화 분기는 분석/배포 단계로 확대하지 않음(YAGNI; 구현 단계가 관측된 유일 사례).

### 3. heartbeat 회수 임계 단계별 상향

대상: `service/StaleTaskRecoveryJob.java`, `application.yml`

- 설정 신규/유지:
  - `app.task.worker-dead-threshold-seconds: 60` (분석, 유지)
  - `app.task.implementation-worker-dead-threshold-seconds: 300` (구현, 신규)
  - `app.task.deploy-worker-dead-threshold-seconds: 180` (배포, 신규)
- `recoverStale()`에서 작업의 `from` 상태별로 dead 임계 선택 후 `workerDeadBefore = now.minusSeconds(deadSec)` 계산. `claimedLongEnough`와 heartbeat 신선도 판정 모두 이 값을 사용.
- 60분 절대 백스톱(`hungBackstop`, `implementation-stale-threshold-minutes:60`)은 **변경 없음** — 진짜 죽은 워커는 백스톱이 회수.

## 데이터 / 인터페이스 영향

- DB 스키마 변경 없음. `task_status_history`에 정합화 전이 1건 추가될 뿐.
- 워커→API 결과 페이로드(`WorkerResultRequest`) 구조 불변.
- 신규 설정 키만 추가(기본값 보유 → 기존 배포 안전).

## 테스트 계획 (TDD)

1. `WorkerService.recordResult`
   - IMPLEMENTATION_FAILED 상태 + PR_CREATED 보고 → PR_CREATED로 정합화 + history 1건 + 메타 채움 + failureReason 클리어.
   - 정상 IMPLEMENTING + PR_CREATED → 기존대로 PR_CREATED (회귀).
   - IMPLEMENTATION_FAILED + PR_CREATED인데 prUrl/headBranch 누락 → 정합화 조건 미충족이라 strict 가드로 떨어져 **409**(in-flight 아님). 정합화는 PR 메타가 유효할 때만 발동.
   - COMPLETED/그 외 상태에 대한 late 보고는 여전히 409.
2. `StaleTaskRecoveryJob`
   - 구현 작업 heartbeat 90초 stale → 회수 안 됨.
   - 분석 작업 heartbeat 90초 stale → 회수됨.
   - 구현 작업 heartbeat >300초 stale → 회수됨.
   - 구현 작업 claimedAt 60분 초과(heartbeat 신선) → 백스톱 회수 유지.
3. 워커 `postResultWithRetry`
   - RestClientException 후 재시도하여 결국 성공.
   - 성공 보고 재시도 소진 → 예외 미전파, 실패 보고 미발동.

## 범위 밖 (후속 제안)

- task16 기존 레코드는 워커가 사라져 자가 치유 불가. 패치 머지 후 일회성 데이터 보정으로 `status=PR_CREATED`, `pr_url=https://github.com/micthebick84/netis7.0/pull/10`, `head_branch=netismaker/task-16` 채워 살릴 수 있음.
