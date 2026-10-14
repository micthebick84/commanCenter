# 작업(Task) — DB 연결 선택 → 인터뷰·디자인·구현에서 읽기 전용 DB 조회 설계

- 날짜: 2026-10-14
- 상태: 사용자 승인됨 (brainstorming: 조사 → 질의 2건(적용 단계 "인터뷰 + 디자인·구현", 선택 위치 "승인 다이얼로그") → 워커 전달 방안 3개 비교 → "방안 1: API 서버가 DB MCP 엔드포인트 호스팅" 채택 → 1~3부 승인)
- 범위: netisMaker 백엔드(Java, API + 워커 데몬) + 인터뷰 서비스(Node) + 프론트엔드(Nuxt)
- 선행 스펙: `2026-10-02-question-db-mcp-design.md`(질문 세션 DB 접속정보·내장 DB MCP — 이 스펙은 그 §12 "인터뷰·디자인/구현 워커에 DB MCP 연동(후속)"을 구현한다)

## 1. 목표

작업 승인 때 레포의 DB 연결(공용·내 접속정보)을 골라 두면, 그 작업의 **분석 인터뷰·디자인·구현** 단계에서 에이전트가 해당 DB를 **읽기 전용으로** 조회할 수 있다.

- 지원 DB·접속정보 관리·REPO/USER 범위는 질문 스펙(2026-10-02)을 그대로 따른다.
- **DB 비밀번호는 API 서버(와 인터뷰 서비스 메모리) 밖으로 나가지 않는다** — 구현·디자인 워커와 그 `claude -p`에는 평문 비밀번호가 전달되지 않는다.
- DB 쓰기는 모든 단계에서 막는다(서버 측 강제).

## 2. 확정된 정책 결정

| 결정 | 내용 |
|---|---|
| 적용 단계 | 분석 인터뷰(INTERVIEW) + 디자인(DESIGNING) + 구현(IMPLEMENTING) |
| 선택 위치 | **승인 다이얼로그**(모델·effort·MCP 아래). 등록 창에는 넣지 않는다 |
| 인터뷰 실행 | 질문과 같은 **인터뷰 서비스 내장 SDK MCP**(`createSdkMcpServer`, `db-<id>`) 재사용 |
| 디자인·구현 실행 | **API 서버가 DB MCP를 HTTP로 호스팅**(`POST /mcp/db/{connectionId}`). 워커 `claude -p`에는 URL + **작업 전용 단기 토큰**만 담은 mcp-config를 준다 |
| 비밀 전달 | 접속정보는 MCP 호출마다 API 서버가 현재 행을 조회·복호화해 메모리에서만 쓴다. 워커 claim 응답·mcp-config·자식 env·로그 어디에도 비밀번호가 없다 |
| 읽기 전용 | 질문과 같은 3중 방어 — (1) SQL 검사(단일 조회문만), (2) READ ONLY 트랜잭션 + 항상 ROLLBACK, (3) 30초·200행·셀 2000자·결과 60000자 상한. 디자인·구현 경로는 이 검사가 **전부 API 서버에서** 일어나므로 `--dangerously-skip-permissions`로도 우회 불가 |
| 저장 | Task·InterviewSession 모두 **id만 저장**(`db_connection_ids` JSONB). 실행 시점의 현재 행으로 해석 — 관리자가 끄거나 고치면 다음 호출부터 반영 |
| 실패 정책 | DB 문제로 작업을 실패시키지 않는다 — 못 쓰는 연결은 빼고 사유를 프롬프트·이력에 남긴 채 진행 |

### 2.1 방안 비교 (채택: 방안 1)

| 방안 | 요지 | 탈락 사유 |
|---|---|---|
| **1. API 호스팅 HTTP MCP** | API 서버가 MCP 엔드포인트, 워커엔 토큰만 | — (채택) 비밀이 API를 안 떠남, 원격 워커에도 동일, 읽기 전용이 서버 강제 |
| 2. 인터뷰 서비스 코드로 stdio MCP | TS 어댑터 재사용, 접속정보를 env/임시 mcp-config로 | 비밀번호가 디스크·자식 env로 나감(질문 스펙 §2.1이 금지한 경로), 워커 PC에 node·인터뷰 서비스 코드 필요 |
| 3. 워커 JVM 로컬 MCP | 워커 메모리에서 호스팅 | 워커가 평문 비밀번호를 받아야 하고 워커 PC가 DB에 닿아야 함. 구현량은 방안 1과 비슷 |

전제: DB 접속은 API 호스트에서 나간다. 현재 API·인터뷰 서비스가 같은 PC라 질문이 쓰는 네트워크 조건과 동일하다.

## 3. 데이터

마이그레이션 `V28__task_db_connection.sql`:

```sql
ALTER TABLE com.task ADD COLUMN db_connection_ids JSONB NOT NULL DEFAULT '[]';

CREATE TABLE com.task_db_mcp_token (
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex
    stage       VARCHAR(32) NOT NULL,          -- 발급 당시 TaskStatus.name() (IMPLEMENTING/DESIGNING)
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_task_db_mcp_token_task ON com.task_db_mcp_token(task_id, issued_at DESC);
```

- `Task.dbConnectionIds` — `@JdbcTypeCode(SqlTypes.JSON) List<Long>` (InterviewSession과 같은 매핑).
- 토큰 평문은 저장하지 않는다(해시만). 정리: 만료되었거나 대체된(최신이 아닌) 행을 `DbMcpTokenService.cleanup()`이 삭제한다(`netismaker.db-mcp.token-cleanup-cron`, 기본 매일 03:40).

## 4. API·서비스 (선택·저장)

- `ApproveRequest`에 `List<Long> dbConnectionIds` 추가(null = 선택 없음으로 취급).
- `TaskService.approve`(`AWAITING_APPROVAL` → `startInterview`):
  - `dbConnectionService.validateSelection(task.repoCatalogId, task.ownerId, ids)` — 기준은 **작업 등록자**. 기능 꺼짐·3개 초과·다른 레포·비활성·남의 USER 행이면 400.
  - 통과하면 `task.dbConnectionIds`에 저장하고 `InterviewService.createForTask`가 세션 `dbConnectionIds`에 복사.
- `TaskResponse`에는 `repoCatalogId`·`dbConnectionIds`만 추가한다. 칩 라벨은 프론트가 `/api/db-connections` 목록으로 만든다(보이지 않는 연결은 `DB #id`).
- 인터뷰 설정 프리필(마지막 인터뷰 설정 불러오기) 응답에 `dbConnectionIds` 포함.

## 5. 분석 인터뷰 (인터뷰 서비스)

- `InterviewWorkerController.withDb`: `kind == QUESTION` 조건을 `QUESTION || INTERVIEW`로 넓힌다. `InterviewClaimResponse.of()`도 INTERVIEW일 때 세션의 `dbConnectionIds`를 싣는다.
- 러너 `interviewRunner.ts` INTERVIEW 분기(`runTurn`): 질문 분기와 같이 `createDbMcp(claim)` → `buildOptions({..., dbMcpServers, dbDialects})` → 프롬프트에 `dbSection(db)` → `finally db.close()`.
- 권한 `permissions.ts` INTERVIEW 게이트: 현재 `mcp__*` 전부 allow → `mcp__db-N__*`는 질문과 같은 `dbToolGate`로 보내고 등록되지 않은 `db-N`은 deny. 그 밖의 `mcp__*`는 기존대로 allow. 이 게이트를 거치도록 INTERVIEW의 `allowedTools`(사전 승인)에는 붙인 DB 서버를 넣지 않는다 — 넣으면 `canUseTool`/`dbToolGate`를 건너뛴다.

## 6. 디자인·구현 — API 호스팅 DB MCP

### 6.1 엔드포인트

- `POST /mcp/db/{connectionId}` — 연결마다 MCP 서버 1개. 워커 쪽 서버 이름 `db-<id>` → 도구 이름 `mcp__db-<id>__query` 등(인터뷰와 같은 체계).
- 프로토콜: MCP Streamable HTTP의 **stateless JSON 응답** 부분만 구현(SSE 없음, 세션 id 없음).
  - `initialize` → `protocolVersion`(요청값 수용, 모르면 서버 지원 최신), `capabilities: {tools: {}}`, `serverInfo`
  - `notifications/initialized` 등 notification → `202 Accepted`, 본문 없음
  - `ping` → `{}`
  - `tools/list` → `query`·`list_tables`·`describe_table`(입력 스키마는 인터뷰 서비스 `dbMcp.ts`와 같은 필드)
  - `tools/call` → `{content:[{type:"text", text}], isError}`
  - 알 수 없는 메서드 → JSON-RPC `-32601`. GET·DELETE → `405`.
- 보안 체인: 새 `SecurityFilterChain`(`securityMatcher("/mcp/**")`, stateless, CSRF off). Spring Security 단에서는 permitAll이고 인증은 컨트롤러가 `Authorization: Bearer <토큰>`으로만 한다(산출물 1회용 내려받기와 같은 패턴). **워커 API 키(`X-Worker-API-Key`)는 이 경로에서 쓰지 않는다** — 넓은 권한 키를 claude 프로세스에 넘기지 않기 위해.

### 6.2 작업 토큰

- 발급: `WorkerService.claimNextTask`에서 APPROVED→IMPLEMENTING, DESIGN_PENDING→DESIGNING 전이 시, `task.dbConnectionIds`가 비어 있지 않으면 32바이트 `SecureRandom` → base64url 토큰. 해시(SHA-256 hex)만 `task_db_mcp_token`에 저장.
- `expires_at` = 발급 시각 + `netismaker.db-mcp.token-ttl`(API 설정, 기본 `2h` — 워커 구현 타임아웃 45m·디자인 30m보다 넉넉하게).
- 유효 조건(모두 충족해야 함, 아니면 `401`):
  1. 해시가 일치하는 행이 그 작업의 **가장 최근 토큰**이다(재claim하면 이전 토큰 자동 무효).
  2. 작업의 현재 상태가 행의 `stage`와 같다(IMPLEMENTING/DESIGNING을 벗어나면 즉시 무효).
  3. `expires_at`이 지나지 않았다.
- `connectionId`가 그 작업의 `dbConnectionIds`에 없으면 `404`.
- 연결 해석: 호출마다 `dbConnectionService.resolveForClaim(List.of(connectionId))` — 실행 중 연결을 끄면 다음 호출부터 `isError`로 거부.

### 6.3 읽기 전용 실행 (Java)

- `SqlReadOnlyChecker` — 인터뷰 서비스 `src/sdk/sqlReadOnly.ts`를 Java로 이식. 주석·문자열 리터럴 제거 후 단일 문장·조회문(SELECT/WITH/EXPLAIN 등 TS와 같은 허용 목록)만 허용, 다중 문장·쓰기 키워드 거부. **TS 테스트 케이스를 그대로 옮겨** 두 구현의 판정을 맞춘다.
- `ReadOnlyQueryExecutor` — JDBC:
  - `DriverManager`로 연결(풀 없음, 호출당 1연결 — 호출 빈도가 낮다), 접속 타임아웃 10초
  - `setAutoCommit(false)` + `setReadOnly(true)` + 3종 공통 `SET TRANSACTION READ ONLY`(인터뷰 서비스 `db/adapters.ts`와 같은 문장)
  - `Statement.setQueryTimeout(30)`, `setMaxRows(201)`(200 초과 여부 판정), 셀 2000자·결과 60000자에서 자르고 잘림 표시
  - `finally`에서 항상 `rollback()` 후 close
  - `list_tables`·`describe_table`은 방언별 메타 조회(인터뷰 서비스와 같은 쿼리 또는 `DatabaseMetaData`)
- 결과 텍스트 형식은 인터뷰 서비스 도구 출력과 맞춘다(에이전트가 단계마다 다른 형식을 보지 않도록).
- 오류 구분: 인자 검증 오류(SQL 거부·필수 인자 누락 등)는 private `InvalidToolInput` 타입으로 던져 그 메시지를 그대로 `isError`로 돌려준다. 그 밖의 모든 예외(드라이버가 던지는 `IllegalArgumentException` 포함)는 비밀번호를 마스킹하는 "DB 오류" 경로로 보낸다.
- 감사 로그: 호출마다 `taskId`·`connectionId`·도구·SQL 앞 200자(로그 줄에 SQL 머리 포함)·결과 길이(`chars`, 행 수 대신)·소요 ms를 INFO로(`db-mcp task=… conn=…`). 결과값·접속정보는 남기지 않는다.

### 6.4 워커 데몬

- `WorkerTaskResponse`(forImplementation·forDesign)에 `dbMcp` 추가: `{token, servers:[{name:"db-12", path:"/mcp/db/12", label, dbType}], notices:[...]}`. DB 미선택이면 `null`.
- `WorkerMcpSupport.buildClaudeArgsForTask`: 기존 임시 mcp-config에 서버마다 `{"type":"http","url": apiBaseUrl + path, "headers":{"Authorization":"Bearer <token>"}}`를 합치고 `--allowedTools`에 `mcp__db-12` 추가. 토큰 헤더는 `TaskMcpSpec.headers`(DB에는 저장하지 않음, `@JsonInclude(NON_NULL)`)로 실어 mcp-config에 쓴다. 임시 파일은 기존대로 `finally`에서 삭제. 파일에는 토큰만 있으며, 새더라도 그 작업 실행 중·선택된 DB·읽기 전용으로만 쓸 수 있다.
- 구현·디자인 프롬프트에 DB 안내 섹션: 연결 이름·종류·도구 사용법·읽기 전용이라는 점, `notices`(사용할 수 없는 연결과 사유).

### 6.5 공개 노출

cloudflared 터널이 API를 통째로 공개하면 `/mcp/db/**`도 외부에서 닿는다. 토큰이 256비트·작업 실행 중에만 유효해 실질 위험은 낮지만, 터널 설정에서 `/mcp/**`를 막을 수 있으면 막는다(워커는 내부 `apiBaseUrl`로 접근). 구현 시 터널 설정을 확인해 결정하고 결과를 PR에 적는다.

**확인 결과 (2026-10-14)**: 이 PC의 터널(`C:\Users\mic\.cloudflared\config.yml`)은 호스트별 ingress로 `auth-win`→`localhost:9000`, `app-win`→`localhost:3001`(Nuxt), `*.micthebick.dev`→`10.1.1.75:18080`(배포 Traefik), 그 외 404뿐이고 **API(8090)를 직접 노출하는 규칙은 없다**. 공개 경로는 Nuxt `routeRules`(`frontend/nuxt.config.ts`)가 `/api/**`·`/oauth2/**`만 8090·9000으로 프록시하는 것뿐이라 `/mcp/**`는 공개 호스트에서 API로 전달되지 않는다. 따라서 터널·프론트 설정 변경은 필요 없다. 단 8090은 `0.0.0.0`에 바인딩돼 사내망에서는 직접 닿을 수 있다(토큰 인증이 막는다). 이후 `/api/**` 밖의 경로를 프록시하는 규칙을 추가할 때 `/mcp/**`가 포함되지 않게 주의한다.

## 7. 프론트엔드

- `ApproveDialog.vue`: `McpPicker` 아래 `DbConnectionPicker`(질문과 같은 컴포넌트).
  - 목록: `fetchDbConnections(task.repoCatalogId)`. `enabled:false`면 피커 숨김.
  - 기본 선택: REPO·enabled 최대 3개. 마지막 인터뷰 설정을 프리필할 때는 그 `dbConnectionIds`가 우선.
  - "새 접속 추가"(USER) → 저장 후 목록 재조회·자동 선택(질문 화면과 같음).
  - 요청 바디 `{model, effort, mcpCatalogIds, dbConnectionIds}` — 기능 꺼짐이면 `dbConnectionIds` 생략.
  - 400 응답 메시지는 다이얼로그에 표시.
  - DB 목록을 불러오는 동안은 승인 버튼을 비활성화하고, 늦게 도착한 응답은 로드 시퀀스로 무시한다(레포·작업이 바뀐 뒤의 응답이 선택을 덮어쓰지 않게).
- 작업 상세(`pages/tasks/[id].vue`·모바일 상세): DB 칩(`이름 · 종류`) 표시. 라벨은 `/api/db-connections` 목록에서 만들고, 폴링 때마다가 아니라 레포 또는 `dbConnectionIds`가 바뀔 때만 다시 조회한다.

## 8. 오류 처리

| 지점 | 동작 |
|---|---|
| 승인 시 잘못된 선택 | 400 + 사유, 다이얼로그 표시 |
| 인터뷰 claim | 못 쓰는 연결은 빼고 `dbNotices`, 프롬프트 DB 섹션에 "사용할 수 없는 연결: …(사유)". 인터뷰는 계속 |
| 디자인·구현 claim | 토큰 발급·연결 해석 실패해도 claim 성공, DB 없이(또는 일부만) 진행. `notices`를 프롬프트에, 작업 이력에 "DB 연결 일부 사용 불가" 메모. DB 기능 통째 꺼짐도 같음 |
| MCP 인증 실패·만료 | HTTP 401 |
| 선택 안 된 연결 | HTTP 404 |
| SQL 거부·접속 실패·타임아웃 | `tools/call` 결과 `isError:true` 텍스트(에이전트가 읽고 쿼리를 고칠 수 있게). 드라이버 예외 메시지는 다듬어 전달하고 비밀번호가 섞이지 않게 한다 |

## 9. 테스트

- 백엔드 단위
  - `SqlReadOnlyChecker` — TS `sqlReadOnly` 테스트 케이스 이식, 판정 일치
  - 토큰 서비스 — 발급·해시 저장, 최신 토큰만 유효, 상태 변경 시 무효, 만료
  - MCP 컨트롤러 — `initialize`/`tools/list`/`tools/call` 형식, notification 202, 토큰 없음·오류·만료 401, 선택 안 된 연결 404, 쓰기 SQL `isError`, GET 405
  - 승인 — `dbConnectionIds` 저장·세션 복사, 잘못된 선택 400
  - claim — IMPLEMENTING·DESIGNING claim에 `dbMcp`(토큰·서버 목록), 미선택이면 `null`; INTERVIEW claim에 `dbConnections`
- 백엔드 JDBC — H2로 READ ONLY·ROLLBACK·행/셀 상한. 실DB(PG·MySQL·Oracle)는 Testcontainers(이 PC는 skip → CI)
- 워커 — `WorkerMcpSupport` http 서버·헤더·`allowedTools`, 프롬프트 DB 섹션
- 인터뷰 서비스(vitest) — INTERVIEW claim + DB → `createDbMcp`·`close` 호출, INTERVIEW 게이트에서 `db-N` 쓰기 SQL deny·미등록 `db-N` deny
- 프론트(vitest) — 승인 다이얼로그 DB 피커(공용 기본 체크, 프리필, 기능 꺼짐 숨김, 바디 포함), 작업 상세 칩
- 라이브 검증(병합 후) — 실제 사내 DB 연결로 작업 승인 → 인터뷰에서 테이블 조회 → 구현 단계 claude 로그에 `mcp__db-N__query` 호출, 쓰기 SQL 거부 확인

## 10. 범위 밖

- 등록 창에서 DB 선택, 구현 도중 DB 변경 UI
- 쓰기 권한 DB
- 인터뷰 서비스 DB MCP를 Java MCP로 통일(TS·Java에 같은 검사 로직이 생기는 부담은 공통 테스트 케이스로 맞추는 선에서 멈춘다)

## 11. 영향 파일 (예상)

- 백엔드: `db/migration/V28__task_db_connection.sql`, `entity/Task.java`, 신규 `entity/TaskDbMcpToken.java`·`repository/TaskDbMcpTokenRepository.java`, `dto/ApproveRequest.java`, `dto/TaskResponse.java`, `dto/WorkerTaskResponse.java`, `dto/InterviewClaimResponse.java`, `service/TaskService.java`, `service/InterviewService.java`, `service/WorkerService.java`, 신규 `service/dbmcp/`(`DbMcpTokenService`, `SqlReadOnlyChecker`, `ReadOnlyQueryExecutor`), 신규 `controller/DbMcpController.java`, `config/SecurityConfig.java`, `controller/InterviewWorkerController.java`, 정리 잡 1곳
- 워커 데몬: `workerdaemon/WorkerMcpSupport.java`, `workerdaemon/WorkerMainLoop.java`(프롬프트 DB 섹션), 프롬프트 빌더
- 인터뷰 서비스: `src/runner/interviewRunner.ts`, `src/sdk/permissions.ts`
- 프론트: `components/ApproveDialog.vue`, `pages/tasks/[id].vue`, `components/tasks/TaskDetailMobile.vue`
