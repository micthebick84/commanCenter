# 워커 결과보고 silent-loss 관측성 설계

- 날짜: 2026-06-27
- 상태: 설계 승인됨(옵션2: 카운트+UI+dead-letter), 구현 플랜 작성 전
- 선행: PR #13(`fix/worker-result-false-failure`)의 `ResultReporter` 위에 쌓임. 브랜치 `feat/worker-silent-loss-observability`.

## 배경 / 문제

PR #13에서 `ResultReporter.reportTerminal`은 결과 보고가 끝내 실패하면 예외를 던지지 않고 `false`를 반환한다(never-throw 보장). 그런데 `WorkerMainLoop`의 호출 7곳(분석 COMPLETED, 구현 PR_CREATED, 배포 DEPLOYED, undeploy, 실패 helper 3) 중 **반환값을 검사하는 곳이 하나도 없다**. 따라서 보고가 최종 실패하면:

- 워커는 작업을 실제로 수행했지만 백엔드는 그 결과를 영영 받지 못함
- ERROR 로그만 남고(누군가 로그를 봐야 인지) **아무 알림 없이 결과가 증발**(silent loss)

PR #13의 API 정합화는 `IMPLEMENTATION_FAILED → PR_CREATED`(구현 단계)만 사후 구제한다. **분석(COMPLETED)·배포(DEPLOYED) 결과 보고가 유실되면 복구 경로가 없다.**

## 조사로 확정된 제약 (병렬 조사 결과)

- **워커는 HTTP 서버가 없음**(`application-worker.yml` `web-application-type: none`) → 워커에서 Prometheus/actuator `/metrics` 엔드포인트 노출 불가. 기존 Micrometer 계측 0건.
- 워커 상태를 운영자에게 보여주는 **유일한 기존 통로 = heartbeat → `frontend/pages/admin/workers.vue`**. heartbeat 필드 추가 비용 낮음(`NOT NULL DEFAULT` 무중단 마이그레이션 패턴, 최신 버전 V12 → V13).
- 워커는 `~/netis-maker/` 하위에 로컬 파일 영속 패턴 보유(repos, worktrees, worker-mcp.json). 로컬 dead-letter 파일이 자연스러움.
- 최소 침습 통합점 = `ResultReporter` 내부 최종 실패 지점에 콜백 주입 → 호출부 7곳 무수정으로 모든 유실 경로 포착, 테스트 용이.

## 목표

결과 보고 유실을 **관측 가능**하게 만든다(알림/메트릭 + 사후 추적):
1. 워커가 유실 건수를 누적해 heartbeat로 실어 보내고 admin UI에 노출.
2. 유실된 결과 페이로드를 로컬 dead-letter 파일에 보존(수동 재투입/감사용).
3. 구조화 ERROR 로그 마커.

집계 대상: **성공·실패 보고 유실 모두**(status를 함께 기록해 구분). 비목표: 자동 재투입(replay), 외부 webhook/Slack(요청 없음).

## 설계

### 1. `ResultReporter` — 단일 실패 지점 + 콜백 훅
대상: `workerdaemon/ResultReporter.java`

`reportTerminal`의 여러 `return false` 경로(4xx / 재시도 소진 5xx / 예기치 못한 예외 / interrupt)를 **단일 `fail(...)` 헬퍼**로 합류시킨다. `fail`은 ERROR 로그 + 주입된 리스너 호출 후 false 반환.

- 신규 nested 인터페이스: `@FunctionalInterface LostReportListener { void onLost(Long taskId, WorkerResultRequest req, boolean permanent, String reason); }`
- 필드 `private final LostReportListener lossListener;` (Spring ctor에서 `SilentLossTracker` 주입). 기존 4-arg 테스트 생성자는 no-op 리스너로 위임(기존 테스트 무수정), 신규 5-arg 테스트 생성자에 리스너 주입.
- `fail`은 리스너 호출을 try/catch로 감싸 리스너 예외도 삼킴(never-throw 불변 유지).
- 4xx→permanent=true, 예기치 못한 예외→permanent=true, 재시도 소진/interrupt→permanent=false.
- 기존 5개 테스트는 동작 동일(성공 true / 5xx 재시도 / 소진 false / 4xx 즉시 false / 예외 false) — 회귀.

### 2. `SilentLossTracker` — 카운트 + dead-letter
대상: `workerdaemon/SilentLossTracker.java` (신규, `@Component @Profile("worker")`, `implements ResultReporter.LostReportListener`)

- `private final AtomicInteger lostCount = new AtomicInteger();` (워커 프로세스 시작 이후 누적; 재시작 시 0)
- `onLost(...)`:
  1. `lostCount.incrementAndGet()`
  2. dead-letter 파일에 JSON 한 줄 append: `<deadLetterDir>/<workerId>.jsonl`. 엔트리 = `{ts, taskId, status(dbValue), permanent, reason, result(WorkerResultRequest 전체)}`. Jackson `ObjectMapper`로 직렬화.
  3. 디렉터리 없으면 생성. `IOException`은 로그만(절대 throw 안 함).
- `int currentCount()` — heartbeat가 읽음.
- dead-letter 루트는 `WorkerProperties.deadLetterDir`.

### 3. `WorkerProperties` — dead-letter 경로
대상: `workerdaemon/WorkerProperties.java`

record 컴포넌트 `String deadLetterDir` 추가 + 컴팩트 생성자 기본값 `${user.home}/netis-maker/dead-letter` (reposDir/worktreeRoot 패턴 동일). `application-worker.yml`에 `dead-letter-dir: ${DEAD_LETTER_DIR:${user.home}/netis-maker/dead-letter}` 키 추가.

### 4. heartbeat 표면화
- `dto/WorkerHeartbeatRequest.java`: `Integer lostReportCount` 추가(nullable — 구버전 워커 호환).
- `workerdaemon/WorkerMainLoop.java` `sendHeartbeat`: 생성자에 `SilentLossTracker` 주입, heartbeat 요청에 `silentLossTracker.currentCount()` 전달.
- `entity/WorkerHeartbeat.java`: `@Column(name="lost_report_count", nullable=false) private Integer lostReportCount = 0;`
- `db/migration/V13__worker_heartbeat_lost_report_count.sql`: `ALTER TABLE com.worker_heartbeat ADD COLUMN IF NOT EXISTS lost_report_count INT NOT NULL DEFAULT 0;`
- `service/WorkerService.java` `heartbeat`: `h.setLostReportCount(req.lostReportCount() == null ? 0 : req.lostReportCount());`
- `controller/WorkerHealthController.java` `toView`: `view.put("lostReportCount", h.getLostReportCount());`
- `frontend/pages/admin/workers.vue`: `WorkerHealth` 인터페이스에 `lostReportCount: number` 추가, columns에 `{ name:'lostReportCount', label:'유실 보고', field:'lostReportCount', align:'center' }`, `>0`이면 빨강 강조하는 body-cell 템플릿.

## 데이터 / 인터페이스 영향
- DB: `worker_heartbeat`에 `lost_report_count INT NOT NULL DEFAULT 0` 1컬럼(무중단).
- 워커→API heartbeat 페이로드에 nullable 정수 1필드 추가(구버전 호환).
- 신규 설정 키 `dead-letter-dir`(기본값 보유).
- dead-letter 파일은 로컬(`~/netis-maker/dead-letter/`), git/DB 무관.

## 테스트 계획 (TDD)
1. `ResultReporterTest`(기존 클래스 확장):
   - 리스너가 4xx 최종실패 시 호출(permanent=true) / 5xx 재시도 소진 시 호출(permanent=false) / 예기치 못한 예외 시 호출(permanent=true).
   - **성공 시 리스너 미호출.**
   - 기존 5개 테스트 회귀 통과(4-arg 생성자 no-op 리스너).
2. `SilentLossTrackerTest`(신규):
   - `onLost` 1회 → `currentCount()==1` + dead-letter 파일에 JSON 1줄(파싱 가능, taskId/status/reason 포함). `@TempDir`로 격리.
   - 2회 호출 → count==2, 2줄.
   - 디렉터리 부재 시 자동 생성.
3. `WorkerServiceHeartbeatTest`(신규 또는 기존 확장):
   - heartbeat 요청 `lostReportCount=3` → 엔티티에 3 저장. `null` → 0 저장.
4. 프론트: `npm run lint` 통과(workers.vue 변경 lint/prettier).

## 범위 밖 (후속)
- dead-letter 자동 재투입(replay) / admin UI에서 dead-letter 조회·재시도.
- 외부 webhook/Slack 알림.
- dead-letter 파일 로테이션 정책.
