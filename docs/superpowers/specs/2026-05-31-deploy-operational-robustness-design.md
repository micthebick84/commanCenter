# 배포 운영 견고성 (B) — 설계 문서

> 작성일: 2026-05-31 · 브랜치: `feat/deploy-operational-robustness`

## 목표

배포 파이프라인이 **워커 장애·자원 누수·관측 부재**라는 세 가지 운영 결함에서
자가 복원(stale 회수)·자가 정리(GC)·실시간 관측(로그 스트리밍) 가능하도록 만든다.

세 컴포넌트는 하나의 spec/plan으로 다루되, 서로 독립적으로 구현·테스트 가능한 단위로 설계한다.

## 배경 / 현재 상태 (검증된 사실)

- **stale 회수**: `StaleTaskRecoveryJob`(`@Profile("api")`, `@Scheduled` 60초)은
  `task.claimed_at < threshold`만으로 staleness를 판정하며 `IN_PROGRESS`(5분)·`IMPLEMENTING`(60분)만 회수한다.
  `DEPLOYING`은 회수 대상이 **아니다** → 워커가 배포/중지 중 죽으면 `배포중`에 영구 정체(수동 DB 개입 필요).
  `worker_heartbeat.last_seen_at`(10초 주기 갱신)은 존재하나 회수 로직이 읽지 않는다.
- **상태 모호성**: `DEPLOY_PENDING`과 `UNDEPLOY_PENDING`이 claim 시 **둘 다 `DEPLOYING`으로** 전이된다.
  일단 `DEPLOYING`이 되면 "배포였는지 중지였는지" 구분이 사라져 회수 동작을 결정할 수 없다.
- **컨테이너/이미지**: 컨테이너명 `netis-task-{id}`(결정적, 라벨 `netis-maker.task={id}`),
  이미지 `netis-task-{id}:{shortSha}`. 컨테이너는 redeploy 시 `docker rm -f`로 교체되어 누적되지 않으나,
  **이미지는 SHA마다 쌓이고 `docker rmi`가 코드 어디에도 없다.** 배포 실패 시 죽은 컨테이너도 정리되지 않는다.
  docker는 **워커 호스트에만** 존재하고 GC 잡은 api 프로파일이라, **api는 docker를 만질 수 없다.**
- **로그**: 빌드/헬스 로그는 워커가 `StringBuilder`에 모아 배포 종료 시 **1회** POST(끝 8000자 truncate).
  스트리밍(SSE/WebSocket) 전무. 워커는 아웃바운드 전용. 프론트는 5초 폴링, `deploy_log`는
  **`DEPLOY_FAILED`일 때만** 표시(성공 로그는 저장되나 UI에 안 보임).

## 확정된 설계 결정

| # | 결정 | 선택 |
|---|------|------|
| 1 | 범위 | B 전체를 하나의 spec(3 컴포넌트) |
| 2 | DEPLOYING 모호성 | **`UNDEPLOYING` 상태 신설**로 배포/중지 분리 |
| 3 | stale 판정 신호 | **하트비트 인식 1차 + claimed_at 절대 백스톱 2차** (모든 in-flight 상태) |
| 4 | GC 위치 | **워커-side 리퍼**(DB 불필요, docker 상태만) |
| 5 | GC 정책 | **보수적 상태기반**(실행 이미지 + task당 최근 1태그 유지, 나머지 rmi + dangling prune, 비실행 orphan은 grace 후 제거) |
| 6 | 로그 전송 | **풀 SSE 푸시** |
| 7 | 로그 영속화 | **청크 테이블 + 종료 시 `deploy_log` 통합 후 청크 삭제** |

---

## 컴포넌트 1 — 배포 stale 회수 (api 프로파일)

### 1.1 상태머신 변경

- `TaskStatus.UNDEPLOYING` 신설. dbValue `"배포중지중"`.
  (status 컬럼은 VARCHAR(dbValue 저장)이라 enum 값 추가에 Flyway 마이그레이션 불필요.)
- `WorkerService.claimNextTask`:
  - `DEPLOY_PENDING → DEPLOYING` (유지, `forDeploy` 응답)
  - `UNDEPLOY_PENDING → UNDEPLOYING` (변경, `forUndeploy` 응답) — 기존엔 `DEPLOYING`으로 합쳐졌음
- `WorkerService.recordResult` 가드: 수락 상태 집합에 `UNDEPLOYING` 추가
  (`IN_PROGRESS, IMPLEMENTING, DEPLOYING, UNDEPLOYING`).
  - `DEPLOYING` → `recordDeployResult`: `DEPLOYED` / `DEPLOY_FAILED`
  - `UNDEPLOYING` → `recordDeployResult`: `PR_CREATED`(중지 성공, deploy_* 컬럼 null 처리) / `DEPLOY_FAILED`(중지 실패) — 현행 의미 유지
- `findClaimableForUpdateSkipLocked` 후보 상태 집합은 변경 없음(여전히 `DEPLOY_PENDING`, `UNDEPLOY_PENDING` 포함).
- 큐 통계(`/api/queue/stats`)와 `QueueStatsBar.vue`에 `undeployPending`·`undeploying` 카운터를 추가해
  신규 상태가 운영자에게 보이도록 한다(최소 추가).

### 1.2 하트비트 인식 회수 로직

`StaleTaskRecoveryJob`을 확장한다. (단계별 임계값과 신호 결합)

대상: in-flight 상태 `{IN_PROGRESS, IMPLEMENTING, DEPLOYING, UNDEPLOYING}` 중 `workerId != null`.

판정(둘 중 하나라도 참이면 회수):
1. **워커 사망(1차):** 소유 워커의 `worker_heartbeat.last_seen_at < now - workerDeadThresholdSeconds`(기본 60s).
   소유 워커의 heartbeat row가 아예 없으면 사망으로 간주.
2. **행업 백스톱(2차):** `claimed_at < now - stageCeiling`
   (분석 `stale-threshold-minutes`=5m / 구현 `implementation-stale-threshold-minutes`=60m /
   배포·중지 `deploy-stale-threshold-minutes`=15m). 워커가 살아있어도 회수.

회수 전이:
- `IN_PROGRESS` → `retryCount < maxRetry`면 `PENDING`(retry++) 아니면 `FAILED` (기존 동작 유지)
- `IMPLEMENTING` → `IMPLEMENTATION_FAILED` (기존 동작 유지, 재시도 없음)
- `DEPLOYING` → `DEPLOY_FAILED` (failureReason `"워커 중단으로 배포 회수"`)
- `UNDEPLOYING` → `UNDEPLOY_PENDING` (중지는 idempotent하므로 안전하게 재큐잉)

모든 회수에서 `workerId`/`claimedAt`을 null로 클리어하고
`TaskStatusHistory.log(taskId, from, to, "system", "stale-recovery", reason)` + `setUpdatedAt(now)`.

### 1.3 구현 방식

엔티티 간 JPA 관계 매핑이 없으므로 **2단계 처리**:
1. `TaskRepository.findInFlightClaimed()` — 상태 ∈ in-flight 집합 AND `workerId IS NOT NULL` AND `deletedAt IS NULL`.
2. 후보들의 `workerId` 집합으로 `WorkerHeartbeatRepository`에서 heartbeat map 조회.
3. 잡 본문(Java)에서 워커 사망/행업 판정 후 전이.

테스트 용이성을 위해 기존 `StaleTaskRecoveryJobTest` 패턴(Mockito + `ReflectionTestUtils.setField`로 `@Value` 주입)을
그대로 따르고, heartbeat repo를 추가 mock한다.

### 1.4 설정 (application.yml에 명시)

기존에 코드 default로만 존재하던 키도 함께 표면화한다.

```yaml
app:
  task:
    stale-threshold-minutes: 5              # 기존 (분석)
    implementation-stale-threshold-minutes: 60   # 기존 default를 yml로 노출
    deploy-stale-threshold-minutes: 15      # 신규 (배포/중지 백스톱)
    worker-dead-threshold-seconds: 60       # 신규 (하트비트 사망 판정)
    stale-check-interval-ms: 60000          # 기존 default를 yml로 노출
```

---

## 컴포넌트 2 — 컨테이너/이미지 GC (워커 프로파일 리퍼)

### 2.1 배치 / 인터페이스

- 신규 `@Profile("worker") @Scheduled` 잡(예: `workerdaemon/DockerGcJob`). **DB 접근 없이 docker 상태만** 사용.
- 실제 정리 로직은 `DeployTarget` 인터페이스에 `gc(GcPolicy)` (또는 `reap()`) 메서드를 추가하고
  `LocalDockerTarget`이 구현한다(원격 타깃 확장 시 일관성 확보). 잡은 `gc-enabled`가 false면 early-return.

### 2.2 정책 (보수적 상태기반)

- **이미지**:
  - `docker ps`로 실행 중 `netis-task-*` 컨테이너가 참조하는 이미지 → **유지**.
  - task repo(`netis-task-{id}`)당 가장 최근 생성 1개 태그(`gc-keep-images-per-task`, 기본 1) → **유지**.
  - 그 외 `netis-task-*` 태그 → `docker rmi`.
  - `docker image prune`(dangling) — 라벨/필터로 netisMaker 소유분만.
- **컨테이너**:
  - 실행 중 → 보존.
  - 비실행(exited/created/dead) `netis-task-*` orphan → 생성 후 `gc-orphan-grace-minutes`(기본 60m)
    지난 것만 `docker rm -f`.

### 2.3 소유 식별 / 안전장치

- 컨테이너: `--label netis-maker.task` (이미 적용됨) + 이름 접두사 `netis-task-`.
- 이미지: **현재 라벨이 없으므로** `LocalDockerTarget`의 `docker build`에 `--label netis-maker.task={id}` 추가.
  + repo 접두사 `netis-task-`로 식별.
- **`docker system prune` 절대 사용 금지.** 라벨/접두사 미매칭 자원은 절대 건드리지 않는다.
- in-flight 배포와의 경합 회피: reap은 grace 지난 비실행 자원만 대상으로 한다.

### 2.4 설정 (application-worker.yml, `netis-maker.worker.deploy.*`)

```yaml
gc-enabled: true            # 기본 활성
gc-interval-minutes: 60     # 리퍼 주기
gc-orphan-grace-minutes: 60 # 비실행 컨테이너 제거 유예
gc-keep-images-per-task: 1  # task repo당 유지 태그 수
```

`WorkerProperties.Deploy` 레코드에 위 4개 필드를 추가(compact 생성자에 default).

---

## 컴포넌트 3 — 실시간 로그 스트리밍 (SSE)

### 3.1 워커 → api (증분 업로드)

- **스트리밍 `ProcessRunner` 변형**: 프로세스 stdout(`redirectErrorStream=true`)을 line-by-line 읽어
  `Consumer<String>` 콜백 호출 + 누적(기존 최종 로그 반환과 호환). 적용 대상:
  - claude Dockerfile 생성
  - `docker build`
  - 헬스 윈도 동안 `docker logs --follow <container>`(앱 자체 기동 로그 실시간) — 헬스 완료/실패 시 종료
- 콜백은 청크를 배치(시간 ~500ms 또는 N라인 단위)하여 업로드. monotonic `seq`(워커 로컬 카운터).
- 신규 워커 콜백 엔드포인트 `POST /worker/tasks/{id}/deploy-log`
  (ROLE_WORKER, `X-Worker-API-Key`, **상태 무변경**). body `{ seq, content }`.
- 청크 업로드는 best-effort: 실패해도 배포 자체는 막지 않는다(로그만 손실).

### 3.2 api 영속화 + fan-out

- 신규 테이블 `com.task_deploy_log_chunk(id BIGSERIAL, task_id BIGINT, seq INT, content TEXT, created_at)` (**Flyway V9**).
  인덱스 `(task_id, seq)`.
- in-memory 레지스트리 `Map<Long, List<SseEmitter>>`(api 단일 인스턴스이므로 충분).
- 청크 ingest 처리: 청크 persist → 해당 task 구독 emitter에 push.
- **종료 시**(`recordDeployResult`에서 `DEPLOYED`/`DEPLOY_FAILED`/`PR_CREATED`):
  1. 청크들을 seq순 통합 → tail(8000자)을 `task.deploy_log`에 기록(기존 컬럼/UI 호환).
  2. 구독 emitter에 `done` 이벤트 전송 후 `complete()`.
  3. 해당 task의 청크 row 일괄 DELETE.
  ⇒ 청크 테이블은 **in-flight task만** 보유하여 작게 유지된다. 완료 후 조회는 기존 `deploy_log`.

### 3.3 api → 브라우저 (SSE)

- 신규 `GET /api/tasks/{id}/logs/stream` (`text/event-stream`, `SseEmitter`).
  - 접속 시 persist된 청크를 **seq순 replay** → 이후 live 구독(도중 접속·새로고침 안전).
  - 종료된 task에 접속하면 `deploy_log` 1회 전송 후 `done`·complete.
- emitter `onError`/`onTimeout`/`onCompletion` → 레지스트리에서 제거.
- **SSE 인증**: `EventSource`는 커스텀 헤더를 못 실으므로 access token을 **쿼리 파라미터**(`?token=`)로 받아
  기존 JWKS(issuer-uri localhost:9000)로 검증. (내부도구 전제 — URL 노출 가능성은 문서/주석에 명시.
  평문 시크릿 정책과 동일 선상의 의도적 트레이드오프.)

### 3.4 프론트엔드

- `tasks/[id].vue` 배포 카드에 로그 뷰어:
  - in-flight(`DEPLOYING`/`UNDEPLOYING`) 동안 `EventSource(/api/tasks/{id}/logs/stream?token=...)`로 라이브 표시.
  - `DEPLOYED`/`DEPLOY_FAILED` 후엔 `deploy_log` 표시(현재 "`DEPLOY_FAILED`일 때만" 렌더링을 대체 — 성공 로그도 노출).
  - `EventSource`는 컴포넌트 unmount 및 `done` 수신 시 close.
- 스타일은 기존 `<pre class="deploy-log">` / `q-expansion-item`(implementation-log) 패턴을 따른다.

---

## 횡단 관심사

- **에러 처리**: SSE emitter 예외/타임아웃 → 레지스트리 제거. 청크 업로드 실패는 워커 best-effort.
  GC 실패는 잡 단위 try/catch로 로깅 후 다음 주기 재시도.
- **테스트**:
  - C1: `StaleTaskRecoveryJobTest` 확장(heartbeat repo mock으로 워커 사망/행업 분기) + claim/recordResult 전이 테스트(`UNDEPLOY_PENDING→UNDEPLOYING`, `UNDEPLOYING→PR_CREATED/DEPLOY_FAILED`).
  - C2: reap 정책 단위테스트 — `ProcessRunner` mock으로 발행 docker 명령 인자 검증(유지/삭제 분기), 라벨/접두사 미매칭 보존, grace 경계.
  - C3: 청크 ingest→persist→fan-out, replay-from-seq, 종료 시 통합·삭제, SSE 토큰 인증(유효/무효) 단위테스트.
- **Flyway**: **V9** = `task_deploy_log_chunk` 테이블 + 인덱스. (UNDEPLOYING enum은 마이그레이션 불필요.
  배포 상태 부분 인덱스는 필요 시 V9에 포함.)
- **마스킹/시크릿 정책 준수**: 로그에 env_vars secret 값이 평문 노출될 수 있다.
  현 정책(내부도구·평문+UI 마스킹)을 유지하되, 배포 로그에 secret 값이 섞여 들어가지 않도록
  워커 로그 캡처 시 주입 env 값은 출력하지 않는다(기존 `[env 주입: N개 키]`처럼 키 개수만 로깅).

## 비범위 (Out of Scope)

- 원격 배포 호스트(`RemoteSshDockerTarget`/`RegistryDeployTarget`) — 별도 로드맵(A).
- 시크릿 암호화/시크릿 스토어 — 의도적으로 평문 유지(내부도구 전제).
- HTTPS/도메인 라우팅, 멀티 머신 컨테이너 추적, registry push.
- api 멀티 인스턴스 SSE fan-out(pub/sub) — 현재 api 단일 인스턴스 전제.
