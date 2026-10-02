# 질문 세션 — DB 접속정보 → 내장 DB MCP 자동 연동 설계

- 날짜: 2026-10-02
- 상태: 사용자 승인됨 (brainstorming: 조사 → 질의 2건 → 접근안 3개 비교 → "A: Toolbox + 런처 + 턴별 임시 env 파일" 채택 → 섹션 1~5 승인)
- **개정 2026-10-02 (사용자 승인)**: MCP 실행부를 Toolbox stdio에서 **인터뷰 서비스 내장 SDK MCP 서버**(`createSdkMcpServer`, 드라이버 `pg`·`mysql2`·`oracledb`)로 교체했다. 데이터·API·REPO/USER 범위·프론트(§4·5·7)는 그대로다. 바뀐 곳: §2·§2.1·§3·§6·§8·§9·§10·§11·§12·§13. 이유: 비밀번호가 임시 파일·자식 프로세스 env로 나가지 않고, 4종 모두 DB 트랜잭션 수준 읽기 전용을 걸 수 있으며, 외부 바이너리와 Toolbox 관련 미검증 가정(도구 파라미터 이름·stdout 오염·MariaDB 호환·Oracle 접속 문자열)이 사라진다.
- 범위: netisMaker 백엔드(Java) + 인터뷰 서비스(Node) + 프론트엔드(Nuxt)
- 선행 스펙: `2026-08-30-question-sessions-design.md`(Q&A·질문 게이트), `2026-09-13-question-mcp-attachments-design.md`(대화 중 MCP 변경·system note·SSE `note`)

## 1. 목표

질문 세션(`InterviewSession.kind=QUESTION`)에서 선택한 레포(프로젝트)가 쓰는 DB의 **접속정보만 입력하면**, 에이전트가 그 DB를 조회하는 MCP 도구를 자동으로 받는다(인터뷰 서비스 프로세스 안에서 도는 SDK MCP 서버).

- 지원 DB: **PostgreSQL, MySQL, MariaDB, Oracle**
- 접속정보 입력 주체: 관리자(레포 공용) + **사용자(질문 등록 폼에서 직접 입력, 본인 전용으로 저장)**
- 전제: 인터뷰 서비스가 도는 현재 PC에서 대상 DB로 네트워크 접속이 된다.
- 읽기 전용: DB 쓰기(INSERT/UPDATE/DDL 등)는 질문 세션에서 막는다.

## 2. 확정된 정책 결정

| 결정 | 내용 |
|---|---|
| MCP 서버 | **인터뷰 서비스 내장 SDK MCP 서버** — Agent SDK `createSdkMcpServer` + `tool()`(zod). 연결마다 서버 `db-<id>` 1개, 도구 3개(`query`·`list_tables`·`describe_table`). 드라이버: PostgreSQL `pg`(행 상한은 서버 커서 `DECLARE`/`FETCH`), MySQL·MariaDB `mysql2`, Oracle `oracledb` thin 모드(Instant Client 불필요, Oracle 12.1 이상) |
| 비밀 전달 | claim이 복호화된 접속정보를 싣고, 러너가 **메모리에서만** 드라이버에 넘긴다. CLI에는 `{type:"sdk", name}`만 전달되므로(§2.1) 명령줄·임시 파일·자식 프로세스 env·`mcps_extra`·로그 어디에도 비밀번호가 없다 |
| 사용자 입력분 보관 | **"내 접속정보"로 암호화 저장**(본인만 보임, 레포 단위로 묶여 같은 레포 선택 시 재사용) |
| 읽기 전용 방식 | **3중** — (1) SQL 검사(게이트와 도구 핸들러 양쪽, 단일 조회문만), (2) 4종 모두 **READ ONLY 트랜잭션 안에서 실행하고 항상 ROLLBACK**, (3) 문장 타임아웃 30초·결과 200행·셀 2KB 상한. 폼에 "읽기 전용 계정 권장" 안내 — 최종 방어선은 여전히 DB 계정 권한 |
| 반영 시점 | 대화 중 변경은 **다음 질문부터** (MCP 변경과 동일 패턴, system note 턴 + SSE `note`) |

### 2.1 조사로 확인한 사실 (2026-10-02)

- Agent SDK 0.2.117 `McpServerConfig`는 `McpStdioServerConfig`를 포함한다. 운영자 `~/.claude.json`의 stdio 설정은 `mcpBase.ts`가 이미 verbatim 전달 중.
- **SDK는 `options.mcpServers`를 통째로 `--mcp-config <JSON>` 인자로 claude CLI 명령줄에 넘긴다**(`sdk.mjs`: `l.push("--mcp-config",D$({mcpServers:W4}))`). → stdio 서버 `env`에 비밀번호를 넣으면 프로세스 목록에 노출된다.
- **단, `type:"sdk"` 서버는 예외다**(`sdk.mjs` `setMcpServers`: `if(U.type==="sdk"&&"instance"in U)X[G]=U.instance; … W[G]={type:"sdk",name:G}`). 인스턴스는 SDK 프로세스에 남고 CLI에는 이름만 간다. 도구 호출은 SDK↔CLI 제어 메시지로 오가며 `canUseTool`을 거친다(§11-1에서 실측). → 내장 서버를 택한 직접적 이유.
- SDK 0.2.117 `createSdkMcpServer` 주석: 호출이 60초를 넘으면 `CLAUDE_CODE_STREAM_CLOSE_TIMEOUT` 조정이 필요하다 — 문장 타임아웃 30초로 그 안에 든다.
- SDK는 `zod ^4`를 peer dependency로 요구한다(현재 node_modules에 4.4.3이 호이스팅돼 있지만 직접 의존성으로 선언한다).
- 현재 질문 게이트(`permissions.ts` `MCP_MUTATING_VERBS`)는 이름 기반 동사 denylist라 `query` 같은 중립 이름에 DML을 넣으면 통과한다 → DB 서버 전용 게이트 필요(§8).
- 현재 MCP 카탈로그는 `sse|http`만 허용(`McpCatalogDto.UpsertRequest`), `TaskMcpSpec`은 `{name,url,transport}`뿐 — **이 스펙은 카탈로그를 확장하지 않고** 별도 경로(`db_connection`)로 간다. 카탈로그에 stdio를 열면 관리자가 임의 `command`를 등록하는 RCE 표면이 생긴다.
- 볼트에 기록된 기존 MariaDB MCP의 문제(BigInt 직렬화 오류, `%` 포함 SQL 파싱 깨짐, COUNT에 `CAST` 필요)는 결과 직렬화를 직접 하므로 생기지 않는다(§6.3).

## 3. 아키텍처 요점

```
[등록]  질문 폼 ─(레포 선택)─▶ GET /api/db-connections?repoCatalogId= (REPO 공용 + 내 USER)
        ─(새 접속 추가)─▶ POST /api/db-connections/test (JDBC SELECT 1) → POST /api/db-connections (암호화 저장)
        ─send─▶ POST /api/questions {dbConnectionIds} → interview_session.db_connection_ids (id만)

[턴]    claim ─▶ InterviewService.claim: QUESTION이면 db_connection_ids → 현재 행 조회·복호화
        ─▶ InterviewClaimResponse.dbConnections[{serverName,label,dbType,host,port,database,username,password}] + dbNotices
        ─▶ runQuestionTurn: createDbMcp(claim) — 연결마다 createSdkMcpServer('db-<id>') (DB 접속은 첫 도구 호출 때)
        ─▶ mcpServers[db-<id>] = {type:'sdk', name, instance}   (CLI에는 {type:'sdk', name}만 전달)
        ─▶ 도구 호출마다 canUseTool → questionMcpGate → dbToolGate(sqlReadOnly 검사)
        ─▶ 도구 핸들러: sqlReadOnly 재검사 → READ ONLY 트랜잭션 + 타임아웃 → 실행 → ROLLBACK → 상한 적용·직렬화
        ─▶ 턴 종료 finally: 열린 커넥션 전부 close
```

단일 호스트 전제(API·인터뷰 서비스·대상 DB 접근이 같은 PC)는 기존 첨부 기능과 같다.

## 4. 데이터 모델 — `V24__db_connection.sql`

```sql
CREATE TABLE IF NOT EXISTS com.db_connection (
    id               BIGSERIAL PRIMARY KEY,
    scope            VARCHAR(10)  NOT NULL,          -- 'REPO'(관리자 공용) | 'USER'(개인)
    repo_catalog_id  BIGINT       NOT NULL REFERENCES com.repo_catalog(id) ON DELETE CASCADE,
    owner_user_id    VARCHAR(20),                    -- USER일 때만. FK 없음(question_attachment.uploaded_by 선례 — H2 호환)
    name             VARCHAR(100) NOT NULL,          -- 표시명. 예: '운영 DB'
    db_type          VARCHAR(20)  NOT NULL,          -- 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE'
    host             VARCHAR(255) NOT NULL,
    port             INTEGER      NOT NULL,
    database_name    VARCHAR(255) NOT NULL,          -- Oracle은 서비스명
    username         VARCHAR(255) NOT NULL,
    password_enc     TEXT         NOT NULL,          -- AES-256-GCM 암호문(hex). 평문 저장 금지
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    created_by       VARCHAR(20),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_db_connection_scope CHECK (scope IN ('REPO','USER')),
    CONSTRAINT ck_db_connection_owner CHECK ((scope = 'USER') = (owner_user_id IS NOT NULL)),
    CONSTRAINT ck_db_connection_type  CHECK (db_type IN ('POSTGRESQL','MYSQL','MARIADB','ORACLE'))
);
CREATE INDEX IF NOT EXISTS idx_db_connection_repo ON com.db_connection(repo_catalog_id);

ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS db_connection_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
```

- 세션에는 **id만** 둔다. `mcps_extra`처럼 스펙을 박제하지 않는 이유: 박제하면 비밀번호(또는 암호문)가 세션 행마다 영구 복제된다. 대가로 감사·재현성은 "그 시점의 접속정보"가 아니라 "현재 행"이 기준이 된다(삭제되면 다음 턴부터 빠짐 — §9).
- 개인 접속정보도 `repo_catalog_id`에 묶는다(사용자 결정: "같은 레포로 다음 질문을 등록할 때 다시 고를 수 있음").
- 엔티티 `DbConnection`의 `passwordEnc`는 `@JsonIgnore` 대상이 아니라 **DTO로만 노출**하는 기존 패턴을 따르고, 어떤 View DTO에도 비밀번호·암호문 필드를 두지 않는다.

### 4.1 암호화

- `DbSecretCipher`(`@Component`): `org.springframework.security.crypto.encrypt.Encryptors.delux(key, salt)`(AES-256-GCM + PBKDF2, 결과 hex — `stronger`의 hex 인코딩판, `spring-boot-starter-security`에 이미 포함).
- 설정: `app.db-secret.key` ← env `NETISMAKER_DB_SECRET_KEY`, `app.db-secret.salt` ← env `NETISMAKER_DB_SECRET_SALT`(hex, 짝수 길이 16자 이상 — 기존 `app.*` 설정 접두와 통일). Windows 스택은 `public.env`.
- 키나 salt가 비어 있으면 `isEnabled()=false` — **부팅 실패 금지**. 기능만 꺼진다(§9).
- 키 교체 도구는 범위 밖. 키를 바꾸면 기존 행은 복호화 실패 → "다시 저장 필요"(§9).

## 5. 백엔드 계약

### 5.1 엔드포인트 (`DbConnectionController`, `@Profile("api")`)

| 메서드 | 경로 | 권한 | 내용 |
|---|---|---|---|
| GET | `/api/db-connections?repoCatalogId=` | 로그인 | 그 레포의 `REPO`(enabled) + 내 `USER`. 관리자는 `REPO` 비활성 포함 |
| POST | `/api/db-connections` | 로그인(`scope=REPO`는 관리자) | 생성. `USER`면 `owner_user_id`=요청자 |
| PUT | `/api/db-connections/{id}` | 소유자(USER) / 관리자(REPO) | 수정. **password blank = 기존 유지** |
| DELETE | `/api/db-connections/{id}` | 소유자 / 관리자 | 삭제 |
| POST | `/api/db-connections/test` | 로그인(`{id}`로 저장값을 쓰면 그 항목의 편집 권한 필요 — 남의 저장 비밀번호로 임의 host에 접속시키는 경로 차단, 아니면 403) | `{id}` 또는 저장 전 폼 값 `{dbType,host,port,databaseName,username,password}`. `{id}`+password 생략 시 저장값 사용 |

- 기능 꺼짐(키 없음)이면 전부 **503** `"DB 접속정보 기능이 꺼져 있습니다(NETISMAKER_DB_SECRET_KEY)"`. GET은 503 대신 `{enabled:false, items:[]}`로 응답해 폼이 섹션을 숨길 수 있게 한다.
- 다른 사람의 `USER` 행을 id로 건드리면 **404**(존재 노출 방지, `requireViewable` 선례).
- `DbConnectionView(id, scope, repoCatalogId, name, dbType, host, port, databaseName, username, enabled, mine)` — 비밀번호 필드 없음.
- 요청 검증: `name` 1~100, `host` 1~255(공백·제어문자 금지), `port` 1~65535, `databaseName`·`username` 1~255, `password` 생성 시 필수(최대 1000), `dbType` enum.

### 5.2 접속 테스트

- `DbConnectionTester`: JDBC URL 조립 → `DriverManager.getConnection` (connect/login timeout 5초) → `SELECT 1`(Oracle `SELECT 1 FROM DUAL`).
  - PostgreSQL `jdbc:postgresql://h:p/db` · MySQL `jdbc:mysql://h:p/db` · MariaDB `jdbc:mariadb://h:p/db` · Oracle `jdbc:oracle:thin:@//h:p/service`
- 의존성 추가(`runtimeOnly`): `com.mysql:mysql-connector-j`, `org.mariadb.jdbc:mariadb-java-client`, `com.oracle.database.jdbc:ojdbc11`. postgres 드라이버는 기존.
- 응답 `{ok, message}`. 실패 메시지는 예외 메시지에서 비밀번호 문자열을 치환(`maskSecrets` 계열)해 내보낸다.
- 이 엔드포인트는 로그인 사용자가 API 호스트에서 임의 host:port로 접속을 시도하게 하는 표면이다. 내부 도구 전제로 허용하되, 4종 드라이버만·5초 타임아웃·요청자 id와 대상 host:port를 INFO 로그로 남긴다.

### 5.3 질문 요청·응답

- `QuestionCreateRequest` += `List<Long> dbConnectionIds`(null/빈 = 없음).
- `QuestionAskRequest` += `List<Long> dbConnectionIds` — **null = 유지, 빈 리스트 = 전부 해제**(mcpCatalogIds와 동일 규칙).
- `QuestionService`
  - `create`: `resolveDbConnections(ownerId, repoCatalogId, ids)` 검증 → `setDbConnectionIds`.
  - `ask`: `applyMcpChange` 다음에 `applyDbChange(s, ids)` — 집합이 같으면 검증 없이 no-op, 다르면 검증 → 갱신 → `appendSystemNote("DB 연결 변경: 운영 DB, 분석 DB")`/`"DB 연결 변경: 없음(전부 해제)"`. `AskResult`에 `dbNote` 추가, 컨트롤러가 `pushNote`.
  - 검증 기준은 **세션 소유자**(관리자가 남의 세션에 ask해도 소유자가 볼 수 있는 항목만): 같은 `repo_catalog_id`, `REPO`이거나 `USER && owner=세션 소유자`, `enabled`, 기능 켜짐. 위반 시 400. 개수 상한 3.
- `InterviewResponse` += `Long repoCatalogId`(대화 화면이 DB 목록을 조회하는 키), `List<Long> dbConnectionIds`, `List<DbConnectionChip> dbConnections`(`{id, name, dbType}`, 현재 행 기준 — 삭제된 id는 칩에서 빠짐).

### 5.4 claim

- `InterviewClaimResponse` += `List<DbConnectionRef> dbConnections`(non-null), `List<String> dbNotices`(non-null).
  - `DbConnectionRef(String serverName, String label, String dbType, String host, int port, String database, String username, String password)`.
  - `serverName = "db-" + id`, `label = name + " (" + 표시용 종류 + ")"`.
- claim 응답에 `dbConnectionIds`(세션 값)를 싣고, `InterviewWorkerController.claim`이 `DbConnectionService.resolveForClaim(ids)`로 `dbConnections`·`dbNotices`를 채운다(`InterviewService` 의존성 무변경). `kind == QUESTION`일 때만 채운다. INTERVIEW는 항상 빈 목록.
  - id별로 현재 행 조회 → 없음/비활성/기능 꺼짐/복호화 실패면 건너뛰고 `dbNotices`에 사유 한 줄(예: `"DB 연결 '운영 DB'를 복호화할 수 없습니다 — 접속정보를 다시 저장해야 합니다"`).
- **claim 응답 본문은 Java·Node 양쪽 모두 로그로 남기지 않는다.** `DbConnectionRef.toString()`은 password를 `****`로 오버라이드한다(레코드 기본 toString 노출 방지). 테스트로 고정.
- MCP 카탈로그 `UpsertRequest.name`에 `db-` 접두사 금지 검증 추가(서버 이름 충돌 방지). 운영자 `~/.claude.json` base 이름과의 충돌은 DB 쪽이 우선(뒤에 merge).

## 6. 인터뷰 서비스 계약

### 6.1 타입·의존성

- `types.ts`: `DbType`, `DbConnectionRef` 인터페이스, `InterviewClaimResponse.dbConnectionIds?: number[]`, `dbConnections?: DbConnectionRef[]`, `dbNotices?: string[]` (optional — 구버전 백엔드 호환, `Array.isArray` 가드).
- `package.json` dependencies 추가: `pg`, `mysql2`, `oracledb`, `zod`(^4 — SDK peer). 드라이버 타입 패키지는 두지 않는다(변수 모듈명 동적 import + 최소 구조 타입). 설정(env)은 추가하지 않는다 — 상한은 코드 상수(§6.3).
- 드라이버는 연결 종류별로 **변수 모듈명 동적 import**한다(`loadDriver('pg')` 등). 로드 실패는 그 도구 호출의 오류 텍스트로만 드러나고 서비스 기동·다른 DB에는 영향이 없다.
- SDK MCP 서버는 **스트리밍 입력 모드**(prompt가 AsyncIterable)에서만 동작한다 — `runQuestionTurn`은 이미 `questionPromptFor` async generator를 쓴다.

### 6.2 `src/sdk/db/` (신규)

| 파일 | 역할 |
|---|---|
| `limits.ts` | 상수 `DB_LIMITS = { statementTimeoutMs: 30_000, connectTimeoutMs: 10_000, maxRows: 200, maxCatalogRows: 1000, maxCellChars: 2000, maxResultChars: 60_000 }` |
| `result.ts` | `toCell(v)`·`formatResult(r)` — 직렬화·상한(§6.3) |
| `adapters.ts` | `interface DbSession { run(sql, params?, maxRows?): Promise<QueryResult>; close(): Promise<void> }`, `QueryResult = { columns: string[]; rows: unknown[][]; truncated: boolean }`, `openSession(ref, drivers?)` — 종류별 구현(아래) |
| `catalog.ts` | `list_tables`·`describe_table`용 방언별 **파라미터 바인딩** SQL |
| `../dbMcp.ts` | `createDbMcp(claim, deps?)` — 서버·도구 조립, 커넥션 수명 |

**`openSession` 종류별** — 모든 `run`은 같은 틀: 트랜잭션을 읽기 전용으로 열고 → 사용자 SQL(끝 `;` 제거) → **성공·실패와 무관하게 ROLLBACK**. 한 세션 안의 `run`은 프라미스 체인으로 직렬화한다(CLI가 같은 서버 도구를 병렬 호출해도 한 커넥션에 트랜잭션이 겹치지 않게).

- **PostgreSQL** (`pg.Client`): 접속 옵션 `connectionTimeoutMillis`, `application_name: 'netismaker-question'`, `options: '-c default_transaction_read_only=on'`.
  `BEGIN TRANSACTION READ ONLY` → `SET LOCAL statement_timeout = 30000` →
  첫 키워드가 `SELECT|WITH|VALUES|TABLE`이면 `DECLARE netis_q NO SCROLL CURSOR FOR <sql>` + `FETCH FORWARD <maxRows+1> FROM netis_q`(행 수 상한을 DB 쪽에서 — 큰 테이블 `SELECT *`가 메모리로 다 오지 않는다), `SHOW|EXPLAIN`과 바인딩 파라미터가 있는 조회(카탈로그 — 자체 LIMIT, DECLARE는 파라미터를 못 받는다)는 그대로 실행 → `ROLLBACK`. `rowMode: 'array'`, 유휴 중 끊김 대비 `error` 리스너.
- **MySQL·MariaDB** (`mysql2` 콜백 API): 접속 옵션 `connectTimeout`, `multipleStatements: false`, `supportBigNumbers: true`, `bigNumberStrings: true`, `dateStrings: true`, `flags: ['-LOCAL_FILES']`. 접속 직후 `SET SESSION TRANSACTION READ ONLY`, 서버 측 타임아웃 `SET SESSION max_execution_time = 30000`(MySQL)·`SET SESSION max_statement_time = 30`(MariaDB) — 둘 다 시도하고 모르는 변수 오류는 무시.
  `START TRANSACTION READ ONLY` → `query({ sql, rowsAsArray: true, timeout: 30000 })`의 `result` 이벤트로 받다가 `maxRows+1`행이 차면 **커넥션을 destroy**(남은 행 전송 중단)하고 다음 `run`에서 다시 접속 → 정상 종료면 `ROLLBACK`.
- **Oracle** (`oracledb` thin — `initOracleClient`를 부르지 않는다): `getConnection({ user, password, connectString: 'host:port/service', connectTimeout })`, `callTimeout = 30000`.
  `ROLLBACK`(이전 트랜잭션 정리) → `SET TRANSACTION READ ONLY` → `execute(sql, params, { outFormat: OUT_FORMAT_ARRAY, maxRows: maxRows+1, fetchTypeHandler })`(NUMBER·CLOB·DATE를 문자열로) → `ROLLBACK`.

**`createDbMcp(claim, deps?)`** → `PreparedDbMcp { servers: Record<string, McpSdkServerConfigWithInstance>; tools: Record<string, SdkMcpToolDefinition[]>(테스트·진단용); dialects: Record<string, SqlDialect>; labels: Array<{serverName,label}>; notices: string[]; close(): Promise<void> }`

- `serverName`이 `^db-\d+$`가 아니면 건너뛰고 notice.
- 연결마다 `createSdkMcpServer({ name: serverName, tools: [query, list_tables, describe_table] })`. **DB 접속은 첫 도구 호출 때**(lazy) — DB를 안 쓰는 턴은 접속하지 않는다.
- 도구:
  - `query({ sql })` — 설명에 "읽기 전용, SELECT 계열 단일 문장, 최대 200행, 30초"를 적는다. 핸들러가 `checkReadOnlySql(sql, dialect)`를 **다시** 검사(게이트가 우회돼도 막히게) → `session.run` → `formatResult`.
  - `list_tables({ schema? })` — 테이블·뷰 목록(스키마 생략 시 PG는 시스템 스키마 제외 전체, MySQL은 현재 DB, Oracle은 현재 사용자). 최대 1000행.
  - `describe_table({ table, schema? })` — 컬럼(이름·타입·널·기본값·주석)·PK·인덱스. Oracle은 이름을 대문자로 비교.
  - `schema`·`table` zod 검증: 1~128자, 제어문자 금지. SQL에는 **바인딩 파라미터로만** 들어간다(문자열 조립 금지).
- 오류: 접속 실패·SQL 거부·타임아웃·드라이버 로드 실패는 `{ isError: true, content: [{ type: 'text', text }] }`로 모델에 돌려준다(턴은 계속). `text`는 `safeMessage(e, ref)` — 비밀번호 문자열을 `****`로 치환하고 500자로 자른다. 오류 객체·스택을 로그에 남길 때도 같은 함수를 거친다.
- `close()`: 열린 세션을 모두 닫는다. 실패는 경고 로그(서버 이름만)로 삼킨다.
- 테스트용 주입: `deps.openSession`(기본 `adapters.openSession`).

### 6.3 결과 형식

- 도구 결과는 JSON 텍스트 한 덩어리: `{"columns":[…],"rows":[[…]],"rowCount":n,"truncated":bool}`.
- `toCell`: `null`/`undefined` → `null`,`bigint`·Decimal 객체 → 문자열, `Buffer`/`Uint8Array` → `"<binary N bytes>"`, `Date` → ISO 문자열, 그 밖의 객체(pg json 등) → `JSON.stringify`, 문자열이 2000자를 넘으면 앞 2000자 + `…(+N자)`.
- `maxRows`보다 많이 오면 `maxRows`행만 담고 `truncated: true`. 전체 텍스트가 60000자를 넘으면 뒤 행부터 버리고 `truncated: true`.
- 볼트에 기록된 기존 MariaDB MCP의 BigInt 직렬화 오류·`%` 파싱 문제는 이 경로에서 생기지 않는다(드라이버 결과를 우리가 직렬화하고, SQL은 드라이버에 그대로 넘긴다).

### 6.4 `sessionOptions.ts` / `interviewRunner.ts`

- `SessionOptionsInput` += `dbMcpServers?: Record<string, unknown>`, `dbDialects?: Record<string, SqlDialect>`. merge 순서: `mcpsBase` → `mcpsExtra` → `dbMcpServers`(마지막 우선). `canUseTool`에 `dbDialects` 전달(§8.1).
- `runQuestionTurn`:
  1. `const db = createDbMcp(claim)`
  2. `try { query({prompt: questionPromptFor(claim, dbSection(db)), options: buildOptions({..., dbMcpServers: db.servers, dbDialects: db.dialects})}) … } finally { await db.close() }`
- `dbSection`: 연결이 1개 이상이거나 notice가 있으면 매 턴 프롬프트 앞에 붙인다.
  ```
  사용 가능한 DB 도구 (읽기 전용 — SELECT 계열 단일 문장, 최대 200행):
  - mcp__db-3__query / list_tables / describe_table : 운영 DB (PostgreSQL)
  - mcp__db-7__query / list_tables / describe_table : 분석 DB (Oracle)
  참고: DB 연결 '구 DB'를 사용할 수 없습니다 — 삭제되었거나 비활성화됨
  ```
  서버마다 도구 이름이 같으므로 서버↔DB 매핑을 모델에게 알려 주는 것이 목적.
- 활동 스트림(`messageRelay.ts`)은 MCP 입력을 요약하지 않으므로 SQL·접속정보가 UI로 나가지 않는다(변경 없음).

## 7. 프론트엔드 계약

- `composables/dbConnections.ts`: `DbConnectionView`, `list(repoCatalogId)`, `create/update/remove/test`.
- `components/DbConnectionPicker.vue`: v-model `number[]`, prop `repoCatalogId`. 섹션 "공용"(REPO) / "내 접속정보"(USER) 체크 목록, 항목마다 종류 배지·`host:port/db`. 하단 "새 접속 추가". 상한 3 초과 시 notify 후 거부.
- `components/DbConnectionDialog.vue`(`AdminFormDialog` 패턴): 종류 select(바꾸면 port 기본값 5432/3306/3306/1521 — 사용자가 고친 값은 유지), host, port, DB명(Oracle 선택 시 라벨 "서비스명"), user, password(`type=password`, 수정 모드에서는 비워 두면 유지 — placeholder로 안내, 값은 다시 채우지 않음), "접속 테스트" 버튼(결과 inline), 저장. 상단 안내 `"읽기 전용 계정 사용을 권장합니다. 쓰기 SQL은 질문 세션에서 차단되지만 완전하지 않습니다."`
- `pages/questions/index.vue`: 레포 선택 후 MCP 버튼 옆에 "DB" `q-btn + q-badge + q-menu + DbConnectionPicker`. 레포가 바뀌면 목록 재조회, 그 레포의 `REPO` enabled 항목을 기본 체크(사용자가 해제 가능). `enabled:false` 응답이면 버튼 숨김. 생성 바디(JSON/multipart meta 모두)에 `dbConnectionIds`.
- `pages/questions/[id].vue`: 같은 버튼(`detail.dbConnectionIds`로 1회 시딩, `:disable="!awaiting"`, 툴팁 "대화 중 변경 — 다음 질문부터 적용"), `send({..., dbConnectionIds})`. 헤더 아래 DB 칩(`detail.dbConnections`).
- `pages/admin/repo-catalog.vue`: 레포 카드에 "DB 접속(공용)" 영역 — 목록·추가·수정·삭제·활성 토글(`scope=REPO`로 같은 다이얼로그 재사용).
- `InterviewPanel.vue` `AskExtra` += `dbConnectionIds?: number[]`. 페이지는 DB 기능이 켜져 있을 때(`GET /api/db-connections` 응답 `enabled:true`)만 `dbConnectionIds`를 바디에 싣는다 — 꺼져 있으면 바디가 기존과 동일.
- 문자열은 한국어 하드코딩(i18n 없음). 클래스명에 Quasar 예약어(xs/sm/md/lg/xl) 금지.

## 8. 게이트 — 읽기 전용

### 8.1 `questionMcpGate(toolName, input, dbServers)`

- 시그니처에 `input`과 `dbServers: Record<serverName, 'postgres'|'mysql'|'oracle'>` 추가 — 러너가 이번 턴에 붙인 DB 서버와 방언을 넘긴다(방언에 따라 주석·인용 규칙이 달라 공통 규칙으로는 우회가 생긴다).
- 서버가 `dbServers`에 있으면 `dbToolGate(tool, input, dialect)`로 분기(Obsidian·동사 규칙보다 먼저):
  - `query` → `checkReadOnlySql(String(input.sql ?? ''), dialect)` 통과 시 allow
  - `list_tables`·`describe_table` → allow (입력은 바인딩 파라미터로만 쓰인다 — §6.2)
  - 그 외 → deny
- `dbServers`에 없는 `db-N` 서버 이름은 deny(다른 소스가 이름을 흉내 낸 서버).
- 도구 핸들러도 같은 검사를 한 번 더 한다(§6.2) — 게이트는 1차, 핸들러는 우회 방지.
- INTERVIEW 게이트는 변경 없음(INTERVIEW에는 DB 서버 자체가 붙지 않는다 — §5.4).

### 8.2 `src/sdk/sqlReadOnly.ts` (신규)

`checkReadOnlySql(sql, dialect: 'postgres'|'mysql'|'oracle'): {ok: true} | {ok: false, reason: string}` — 주석·인용 규칙은 방언별(`#` 주석은 mysql만, `$tag$`는 postgres만, 백틱은 mysql만, 백슬래시가 든 문자열은 전부 거부). 금지 함수·접두사(5)는 방언 구분 없이 합쳐 적용한다(과차단 쪽 — 다른 DB 함수 이름을 열 이름으로 쓰는 경우만 오탐).

1. **정규화**: `--…\n`, `/* … */`, MySQL `#…\n` 주석 제거; `'…'`(`''` 이스케이프 포함), PostgreSQL `$tag$…$tag$`, `E'…'` 문자열을 빈 리터럴로 치환; `"…"`·`` `…` `` 식별자는 `x`로 치환. 닫히지 않은 리터럴/주석 → 거부.
2. **단일 문장**: 정규화 결과에서 끝의 공백·`;` 하나를 뗀 뒤 `;`가 남아 있으면 거부. 빈 문장 거부.
3. **첫 키워드** ∈ `SELECT WITH EXPLAIN SHOW DESC DESCRIBE VALUES TABLE`(대소문자 무시). 아니면 거부.
4. **금지 단어**(단어 경계 = `[A-Za-z0-9_$]` 밖, 대소문자 무시) — 하나라도 있으면 거부:
   `INSERT UPDATE DELETE MERGE UPSERT CREATE ALTER DROP TRUNCATE RENAME GRANT REVOKE COMMIT ROLLBACK SAVEPOINT CALL EXEC EXECUTE DO COPY LOCK UNLOCK SET RESET INTO LOAD HANDLER PREPARE DEALLOCATE LISTEN NOTIFY VACUUM ANALYZE REINDEX CLUSTER REFRESH DISCARD KILL SHUTDOWN PURGE FLUSH OPTIMIZE REPAIR`
   - `ANALYZE` 금지로 PostgreSQL `EXPLAIN ANALYZE`(실제 실행)도 막힌다 — 의도적.
   - `INTO` 금지로 `SELECT … INTO`, `INTO OUTFILE/DUMPFILE` 차단. `FOR UPDATE`는 `UPDATE`로 차단.
   - `REPLACE`는 넣지 않는다 — 문자열 함수 `REPLACE()`가 흔하고, MySQL `REPLACE` 문은 3(첫 키워드)에서 이미 막힌다.
   - 알려진 오탐: `SHOW CHARACTER SET`(`SET`). 허용한다.
5. **금지 함수/패키지**(식별자 매칭):
   - PostgreSQL: `pg_terminate_backend pg_cancel_backend pg_reload_conf pg_rotate_logfile set_config pg_read_file pg_read_binary_file pg_ls_dir pg_stat_file pg_sleep nextval setval` 및 접두 `lo_`, `dblink`, `pg_advisory`
   - MySQL/MariaDB: `load_file sleep benchmark get_lock release_lock`
   - Oracle: 접두 `dbms_`, `utl_`
6. 거부 사유는 모델이 고쳐 쓸 수 있게 구체적으로: `"질문 세션 DB 도구는 읽기 전용입니다 — 금지 키워드 UPDATE. SELECT 계열 단일 문장만 실행할 수 있습니다."`
7. **구현 리뷰로 추가된 규칙(Task 9)**: 비ASCII 문자는 식별자 문자로 본다(PG에서 `é$$`는 식별자 — 달러 인용으로 오인하면 뒤 SQL이 숨는다). Oracle `q'…'`/`nq'…'` 대체 인용 리터럴은 거부한다. 문자열로 받은 SQL을 실행하는 PG 함수(접두 `query_to_`·`cursor_to_`·`pg_logical_`, `ts_stat`·`ts_rewrite`·`pg_notify`·`pg_sleep_for`·`pg_sleep_until`)도 금지 — 문자열 리터럴은 검사 전에 비워지므로 안의 SQL을 볼 수 없다. 같은 부류의 사용자 정의 함수·`table_to_xml` 같은 읽기 함수는 막지 않는다(최종 방어선은 읽기 전용 계정).

**한계(스펙에 명시)**: 이 검사는 최선의 노력이다. 2선 = **READ ONLY 트랜잭션 + 항상 ROLLBACK**(4종 모두, §6.2). 2선이 못 막는 것과 그 대비:
- Oracle DDL은 암묵 COMMIT으로 읽기 전용 트랜잭션을 끝낸 뒤 실행된다 → 1선(첫 키워드·금지 단어 `CREATE ALTER DROP TRUNCATE …`)이 막는다. SELECT 안에서는 DDL을 쓸 수 없다.
- MySQL 읽기 전용 트랜잭션은 TEMPORARY 테이블 쓰기를 허용한다 → 1선이 `CREATE`·`INSERT`로 막고, 남아도 세션 종료 때 사라진다.
- 자율 트랜잭션(Oracle `PRAGMA AUTONOMOUS_TRANSACTION`) 등 사용자 정의 함수의 부작용 → 최종 방어선 = **읽기 전용 DB 계정**(폼 안내).
오탐(정상 SELECT 거부)은 열 이름이 금지 단어 그대로인 경우(`"update"` 같은 따옴표 식별자는 1에서 치환되어 통과, 비인용 `set` 열은 거부됨) 정도로 허용한다.

## 9. 오류 처리

| 상황 | 처리 |
|---|---|
| 키/salt 미설정 | API 503(GET은 `enabled:false`), 폼 DB 버튼 숨김, claim은 빈 목록 + `dbNotices` "DB 접속정보 기능이 꺼져 있습니다" |
| 복호화 실패(키 변경 등) | 그 항목만 제외 + `dbNotices` "다시 저장 필요" + WARN 로그(id만) |
| 세션이 참조하던 행 삭제/비활성 | 다음 claim부터 제외 + `dbNotices`. 세션의 `db_connection_ids`는 그대로(다시 켜면 복귀) |
| 드라이버 로드 실패(설치 누락) | 그 도구 호출만 오류 텍스트 → 모델이 답변에서 사용자에게 알림 |
| DB 접속 실패/타임아웃 | 도구 결과 `isError` 텍스트(비밀번호 치환) → 모델이 답변에서 사용자에게 알림. 턴은 계속 |
| 결과 과다 | 200행·셀 2000자·전체 60000자 상한, `truncated: true` — 모델이 조건·LIMIT을 좁혀 다시 조회 |
| 커넥션 close 실패 | 경고 로그(서버 이름만), 턴 결과에는 영향 없음 |
| 게이트 거부 | 사유 메시지로 모델이 재시도. 반복 거부는 기존 턴 상한이 자연 상한 |
| ask 검증 실패(볼 수 없는/비활성 id) | 400, 세션 불변 |

## 10. 테스트 계획

- **Java**
  - `DbSecretCipherTest`: 왕복, 다른 키로 복호화 실패, 키 없으면 disabled.
  - `DbConnectionServiceTest`: 보이는 범위(REPO 전체·USER 본인), 남의 USER id → 404, REPO 생성은 관리자만, PUT password blank 유지.
  - `QuestionServiceTest`: create 시 저장, ask 같은 집합 no-op(검증 미호출), 다른 집합 → note, 빈 리스트 해제 note, 세션 소유자 기준 검증(관리자 actor), 다른 레포 id 400, 상한 3.
  - `InterviewServiceTest`: claim — QUESTION만 `dbConnections`, 삭제/비활성/복호화 실패 → 제외 + notice, INTERVIEW는 빈 목록.
  - JSON 직렬화: `DbConnectionView`·`InterviewResponse`·목록 응답에 password/암호문 키가 없음. `DbConnectionRef.toString()` 마스킹.
  - 통합(Testcontainers postgres): `/api/db-connections/test` 성공·인증 실패(메시지에 비밀번호 없음), CRUD ACL.
  - `McpCatalogDto` `db-` 접두 이름 거부.
- **인터뷰 서비스(vitest)**
  - `sqlReadOnly.test.ts`: 표 형태 50개 안팎 — 허용(SELECT/WITH/EXPLAIN/SHOW/DESC, 끝 `;`, 주석 속 `UPDATE`, 문자열 속 `DELETE`, `updated_at` 열), 거부(DML/DDL, 다중 문장, `SELECT … INTO`, `FOR UPDATE`, `EXPLAIN ANALYZE`, `WITH x AS (DELETE …)`, `pg_read_file`, `dbms_*`, 닫히지 않은 주석/문자열, `$$` 안에 `;` 숨기기 우회 시도).
  - `permissions.test.ts`: `mcp__db-3__query` SELECT allow / UPDATE deny, 방언별 규칙(`#` 주석은 mysql만), `list_tables`·`describe_table` allow, 미지 도구·미등록 `db-N` deny, 비 DB 서버 기존 동작 회귀.
  - `dbResult.test.ts`: `toCell` 형 변환(bigint·Buffer·Date·객체·긴 문자열), 행 상한·전체 길이 상한에서 `truncated`.
  - `dbAdapters.test.ts`(가짜 드라이버): 종류별 문장 순서(READ ONLY 시작 → 타임아웃 → SQL → ROLLBACK), SQL 오류 때도 ROLLBACK, PG 커서 분기(SELECT는 DECLARE/FETCH, SHOW는 직접), MySQL 상한 초과 시 destroy 후 재접속, Oracle `maxRows`, 끝 `;` 제거, 같은 세션 동시 `run` 직렬화.
  - `dbMcp.test.ts`: 서버 형태(`type:'sdk'`, 이름 `db-<id>`), 인스턴스를 뺀 config JSON에 비밀번호 없음, 잘못된 serverName 건너뜀, lazy 접속(도구 호출 전엔 openSession 미호출), 핸들러가 DML을 다시 거부(게이트 우회 가정), 오류 텍스트에 비밀번호 없음, `close()`가 열린 세션만 닫고 실패를 삼킴.
  - `sessionOptions.test.ts`: merge 순서(db가 마지막), QUESTION allowedTools 여전히 `[]`.
  - `interviewRunner.test.ts`: dbSection 프롬프트, 성공·예외 모두 finally에서 `close()`.
- **프론트(vitest)**: Picker(기본 체크·상한·레포 변경 재조회), Dialog(종류별 port 기본값, 수정 모드 password 미채움, 테스트 결과 표시), questions index/detail 바디에 `dbConnectionIds`, `enabled:false`면 버튼 숨김.
- **라이브 스모크**: 4종 DB 각각 — 접속 테스트 성공 → 질문 1건(테이블 목록·간단 집계 SELECT 답변) → "이 행을 UPDATE 해줘" 요청 시 게이트 거부 메시지 확인 → 대화 중 DB 해제 시 note + 다음 턴 init 도구 목록에서 사라짐. 실행 중 `Get-CimInstance Win32_Process`로 claude.exe·node.exe 명령줄에 비밀번호가 없는지 확인(CLI에는 `{type:"sdk",name}`만).

## 11. 구현 시 먼저 확인할 것 (미검증 가정)

1. SDK 0.2.117 + CLI 2.1.284에서 `type:'sdk'` 서버가 `strictMcpConfig:true`·`settingSources:[]` 아래 붙고, 그 도구 호출이 **`canUseTool`을 거치는지**(deny하면 핸들러가 안 불리는지). 스파이크 1회(`scripts/spikeSdkMcp.ts`, DB 없이 가짜 도구).
   → 2026-10-02 확인: **도달**. `scripts/spikeSdkMcp.ts` 실측 — init `mcp_servers=[{"name":"db-1","status":"connected","source":"sdk"}]`, tools=`[mcp__db-1__query]`; `canUseTool`이 `SELECT 42`·`DELETE FROM t` 두 호출 모두에 불렸고, deny한 DELETE는 핸들러에 안 닿음(handler=`[SELECT 42]`). 실행 중 claude.exe 명령줄은 `--setting-sources= --strict-mcp-config --permission-mode default` 였고 `--mcp-config` 인자는 없음(sdk 서버는 CLI 인자가 아니라 제어 프로토콜로 붙는다 — 외부 MCP가 명령줄에 안 보이는 것이 정상).
2. 로컬 PostgreSQL에서 PG 어댑터: `DECLARE … CURSOR` + `FETCH`로 행 상한, 읽기 전용 트랜잭션이 `CREATE TEMP TABLE`을 거부하는지.
   → Task 11에서 확인.
3. MariaDB(대상 버전)에서 `START TRANSACTION READ ONLY`·`max_statement_time`, MySQL에서 `max_execution_time` — 라이브 스모크.
   → 라이브 스모크(Task 16)에서 확인.
4. Oracle thin 모드 접속(대상 서버 12.1 이상), `SET TRANSACTION READ ONLY` — 라이브 스모크.
   → 라이브 스모크(Task 16)에서 확인.
5. `mysql2` 스트림 중간 destroy 뒤 다음 호출 재접속 — 단위 테스트 + 라이브.
   → Task 11에서 확인.

## 12. 범위 밖

- 인터뷰(INTERVIEW)·디자인/구현 워커에 DB MCP 연동(`WorkerMcpSupport` 확장은 후속).
- 키 교체(재암호화) 도구, 접속정보 사용 감사 로그.
- SSH 터널·Oracle Wallet·SSL 클라이언트 인증서 등 고급 접속 옵션(필요 시 `options` 컬럼으로 확장).
- 결과 이어보기(커서 유지·페이지 넘김) — 모델이 `LIMIT/OFFSET`(Oracle `FETCH FIRST`)을 넣어 다시 조회한다.
- 상한 값(200행·30초 등)의 설정화 — 코드 상수로 시작하고 필요해지면 env로 뺀다.

## 13. 배포 순서·운영

1. `public.env`에 `NETISMAKER_DB_SECRET_KEY`·`NETISMAKER_DB_SECRET_SALT` 추가(분실 시 저장된 접속정보 전부 재입력 — 백업 위치를 운영 노트에 기록).
2. 인터뷰 서비스 본 체크아웃에서 `npm ci`(새 의존성 `pg`·`mysql2`·`oracledb`·`zod`). 외부 바이너리·추가 env 없음.
3. API(V24 마이그레이션) → 인터뷰 서비스 → 프론트 `.output` 재빌드. 필드가 전부 additive/optional이라 순서 비민감. 재기동 전 진행 중 세션 없는지 DB 확인(기존 운영 규칙).
