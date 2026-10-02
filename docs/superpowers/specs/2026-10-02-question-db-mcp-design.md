# 질문 세션 — DB 접속정보 → stdio MCP 자동 연동 설계

- 날짜: 2026-10-02
- 상태: 사용자 승인됨 (brainstorming: 조사 → 질의 2건 → 접근안 3개 비교 → "A: Toolbox + 런처 + 턴별 임시 env 파일" 채택 → 섹션 1~5 승인)
- 범위: netisMaker 백엔드(Java) + 인터뷰 서비스(Node) + 프론트엔드(Nuxt)
- 선행 스펙: `2026-08-30-question-sessions-design.md`(Q&A·질문 게이트), `2026-09-13-question-mcp-attachments-design.md`(대화 중 MCP 변경·system note·SSE `note`)

## 1. 목표

질문 세션(`InterviewSession.kind=QUESTION`)에서 선택한 레포(프로젝트)가 쓰는 DB의 **접속정보만 입력하면**, 에이전트가 그 DB를 조회하는 MCP 도구를 stdio 방식으로 자동으로 받는다.

- 지원 DB: **PostgreSQL, MySQL, MariaDB, Oracle**
- 접속정보 입력 주체: 관리자(레포 공용) + **사용자(질문 등록 폼에서 직접 입력, 본인 전용으로 저장)**
- 전제: 인터뷰 서비스가 도는 현재 PC에서 대상 DB로 네트워크 접속이 된다.
- 읽기 전용: DB 쓰기(INSERT/UPDATE/DDL 등)는 질문 세션에서 막는다.

## 2. 확정된 정책 결정

| 결정 | 내용 |
|---|---|
| MCP 서버 | **Google MCP Toolbox for Databases** 단일 바이너리(v1.13.1 고정, 2026-09-25 릴리스). prebuilt 설정 `postgres`·`mysql`·`oracledb`로 4종 커버(MariaDB는 MySQL 프로토콜 호환이라 `mysql`). Oracle은 `useOCI=false` 기본 = 순수 Go 드라이버, Instant Client 불필요 |
| 비밀 전달 | **A안** — claim이 복호화된 접속정보를 싣고, 러너가 턴마다 임시 env 파일을 쓰고, `mcpServers`에는 런처 + 파일 경로만 넣는다. 명령줄·`mcps_extra`·로그에 비밀번호 없음 |
| 사용자 입력분 보관 | **"내 접속정보"로 암호화 저장**(본인만 보임, 레포 단위로 묶여 같은 레포 선택 시 재사용) |
| 읽기 전용 방식 | **SQL 검사 + 계정 권장** — 게이트가 `execute_sql`의 SQL을 검사(단일 조회문만), PostgreSQL은 세션 read-only 파라미터 추가, 폼에 "읽기 전용 계정 권장" 안내. 최종 방어선은 DB 계정 권한 |
| 반영 시점 | 대화 중 변경은 **다음 질문부터** (MCP 변경과 동일 패턴, system note 턴 + SSE `note`) |

### 2.1 조사로 확인한 사실 (2026-10-02)

- Agent SDK 0.2.117 `McpServerConfig`는 `McpStdioServerConfig`를 포함한다. 운영자 `~/.claude.json`의 stdio 설정은 `mcpBase.ts`가 이미 verbatim 전달 중.
- **SDK는 `options.mcpServers`를 통째로 `--mcp-config <JSON>` 인자로 claude CLI 명령줄에 넘긴다**(`sdk.mjs`: `l.push("--mcp-config",D$({mcpServers:W4}))`). → `env`에 비밀번호를 넣으면 프로세스 목록에 노출된다. A안을 택한 직접적 이유.
- 현재 질문 게이트(`permissions.ts` `MCP_MUTATING_VERBS`)는 `execute`/`run` 세그먼트를 막으므로 Toolbox의 `execute_sql`이 그대로는 거부된다 → DB 서버 전용 게이트 필요(§8).
- 현재 MCP 카탈로그는 `sse|http`만 허용(`McpCatalogDto.UpsertRequest`), `TaskMcpSpec`은 `{name,url,transport}`뿐 — **이 스펙은 카탈로그를 확장하지 않고** 별도 경로(`db_connection`)로 간다. 카탈로그에 stdio를 열면 관리자가 임의 `command`를 등록하는 RCE 표면이 생긴다.
- Toolbox prebuilt env 이름(저장소 `internal/prebuiltconfigs/tools/*.yaml`):
  - postgres: `POSTGRES_HOST`·`POSTGRES_PORT`·`POSTGRES_DATABASE`·`POSTGRES_USER`·`POSTGRES_PASSWORD`·`POSTGRES_QUERY_PARAMS`
  - mysql: `MYSQL_HOST`·`MYSQL_PORT`·`MYSQL_DATABASE`·`MYSQL_USER`·`MYSQL_PASSWORD`·`MYSQL_QUERY_PARAMS` (queryTimeout 30s 고정)
  - oracledb: `ORACLE_CONNECTION_STRING`·`ORACLE_USERNAME`·`ORACLE_PASSWORD`·`ORACLE_WALLET`·`ORACLE_USE_OCI`
  - 공통 도구: `execute_sql`, `list_tables`, 그 외 `list_*`·`get_query_plan`(mysql·oracle) 등. prebuilt에는 읽기 전용 옵션이 없다.

## 3. 아키텍처 요점

```
[등록]  질문 폼 ─(레포 선택)─▶ GET /api/db-connections?repoCatalogId= (REPO 공용 + 내 USER)
        ─(새 접속 추가)─▶ POST /api/db-connections/test (JDBC SELECT 1) → POST /api/db-connections (암호화 저장)
        ─send─▶ POST /api/questions {dbConnectionIds} → interview_session.db_connection_ids (id만)

[턴]    claim ─▶ InterviewService.claim: QUESTION이면 db_connection_ids → 현재 행 조회·복호화
        ─▶ InterviewClaimResponse.dbConnections[{serverName,label,dbType,host,port,database,username,password}] + dbNotices
        ─▶ runQuestionTurn: 연결마다 {tmp}/{sid}-{nonce}-{server}.json 쓰기
        ─▶ mcpServers[db-<id>] = {type:'stdio', command:node, args:[dbMcpLauncher.js, 파일경로]}
        ─▶ claude CLI가 런처 spawn → 런처가 파일 읽고 toolbox --prebuilt <p> --stdio (DB env만) spawn, stdio 중계
        ─▶ 도구 호출마다 canUseTool → questionMcpGate → dbToolGate(sqlReadOnly 검사)
        ─▶ 턴 종료 finally: 임시 파일 삭제
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

- `DbSecretCipher`(`@Component`): `org.springframework.security.crypto.encrypt.Encryptors.stronger(key, salt)`(AES-256-GCM, `spring-boot-starter-security`에 이미 포함).
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
| POST | `/api/db-connections/test` | 로그인 | `{id}` 또는 저장 전 폼 값 `{dbType,host,port,databaseName,username,password}`. `{id}`+password 생략 시 저장값 사용 |

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

### 6.1 타입·설정

- `types.ts`: `DbConnectionRef` 인터페이스, `InterviewClaimResponse.dbConnections?: DbConnectionRef[]`, `dbNotices?: string[]` (optional — 구버전 백엔드 호환, `Array.isArray` 가드).
- `config.ts`: `toolboxPath`(env `TOOLBOX_PATH`), `dbMcpTmpDir`(env `DB_MCP_TMP_DIR`, 기본 `join(os.tmpdir(), 'netismaker-dbmcp')`).
- Toolbox 설치: `scripts/install-toolbox.sh`(또는 `.ps1`)가 v1.13.1 Windows/amd64 바이너리를 `netismaker-interview-service/bin/toolbox.exe`로 받는다(`.gitignore`). npm `@toolbox-sdk/server`(1.13.1)는 런타임 다운로드 지연·버전 드리프트 때문에 쓰지 않는다.

### 6.2 `src/sdk/dbMcp.ts` (신규)

- `prebuiltFor(dbType)`: `POSTGRESQL→postgres`, `MYSQL|MARIADB→mysql`, `ORACLE→oracledb`.
- `envFor(ref)`:
  - PostgreSQL: `POSTGRES_HOST/PORT/DATABASE/USER/PASSWORD` + `POSTGRES_QUERY_PARAMS` = 세션 read-only 옵션(`options=-c default_transaction_read_only=on`을 URL 인코딩)
  - MySQL·MariaDB: `MYSQL_HOST/PORT/DATABASE/USER/PASSWORD`
  - Oracle: `ORACLE_CONNECTION_STRING=host:port/database`, `ORACLE_USERNAME`, `ORACLE_PASSWORD`, `ORACLE_USE_OCI=false`
- `prepareDbMcpServers(claim, deps): Promise<{servers: Record<string, McpStdioServerConfig>, files: string[], notices: string[]}>`
  - `toolboxPath`가 없거나 파일이 없으면 servers 빈 객체 + notice `"DB 도구를 사용할 수 없습니다(서버에 Toolbox 미설치)"`.
  - 연결마다 `{tmpDir}/{sessionId}-{randomUUID}-{serverName}.json`에 `{toolboxPath, prebuilt, env}`를 `mode 0o600`, `flag 'wx'`로 쓴다.
  - `servers[serverName] = {type:'stdio', command: process.execPath, args: [launcherPath, filePath]}`.
- `cleanupDbMcpFiles(files)`: `unlink` 실패는 경고만.
- `sweepStaleDbMcpFiles(tmpDir, maxAgeMs = 1h)`: 서비스 기동 시 1회(크래시 잔여물).

### 6.3 `src/dbMcpLauncher.ts` (신규, `dist/dbMcpLauncher.js`)

- 인자 1개(파일 경로) → JSON 읽기 → `spawn(toolboxPath, ['--prebuilt', prebuilt, '--stdio'], {stdio: 'inherit', env: {...pick(process.env, ['PATH','SYSTEMROOT','SystemRoot','TEMP','TMP','WINDIR']), ...env}, windowsHide: true})`.
- 자식 종료 코드로 종료, SIGINT/SIGTERM은 자식에게 전달. 파일 파싱 실패·spawn 실패는 stderr에 사유(비밀번호 제외) 출력 후 exit 1.
- 런처는 파일을 지우지 않는다(CLI가 턴 도중 MCP를 재기동해도 다시 읽을 수 있게). 삭제는 러너 책임.
- 인터뷰 서비스 프로세스 env(`GITHUB_PAT`, `GITLAB_TOKEN`, 내부 API 토큰 등)는 위 pick 목록 밖이라 Toolbox로 넘어가지 않는다.

### 6.4 `sessionOptions.ts` / `interviewRunner.ts`

- `SessionOptionsInput` += `dbMcpServers?: Record<string, unknown>`. merge 순서: `mcpsBase` → `mcpsExtra` → `dbMcpServers`(마지막 우선).
- `runQuestionTurn`:
  1. `const db = await prepareDbMcpServers(claim, deps)`
  2. `try { query({prompt: dbSection(claim, db.notices) + questionPromptFor(claim), options: buildOptions({..., dbMcpServers: db.servers})}) … } finally { await cleanupDbMcpFiles(db.files) }`
- `dbSection`: 연결이 1개 이상이거나 notice가 있으면 매 턴 프롬프트 앞에 붙인다.
  ```
  사용 가능한 DB 도구 (읽기 전용 — SELECT 계열 단일 문장만 실행됩니다):
  - mcp__db-3__* : 운영 DB (PostgreSQL)
  - mcp__db-7__* : 분석 DB (Oracle)
  참고: DB 연결 '구 DB'를 사용할 수 없습니다 — 삭제되었거나 비활성화됨
  ```
  각 서버의 도구 이름이 같으므로(`execute_sql`) 서버↔DB 매핑을 모델에게 알려 주는 것이 목적.
- 임시 파일 쓰기 실패는 예외로 턴 실패(기존 실패 경로 — 사용자에게 오류 턴).

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

### 8.1 `questionMcpGate(toolName, input)`

- 시그니처에 `input` 추가(`buildCanUseTool` QUESTION 분기에서 이미 받고 있음).
- 서버명이 `^db-\d+$`이면 `dbToolGate(tool, input)`로 분기(Obsidian 분기보다 먼저):
  - `execute_sql` → `checkReadOnlySql(String(input.sql ?? ''))` 통과 시 allow (게이트는 서버명에서 DB 종류를 모르므로 금지 목록은 4종 공통)
  - `get_query_plan` → `input.sql_statement ?? input.query ?? input.sql`을 같은 검사
  - `list_` 접두 → allow
  - 그 외 → deny(미지 도구 기본 거부 — Obsidian allowlist와 같은 취지)
- INTERVIEW 게이트는 변경 없음(INTERVIEW에는 DB 서버 자체가 붙지 않는다 — §5.4).

### 8.2 `src/sdk/sqlReadOnly.ts` (신규)

`checkReadOnlySql(sql): {ok: true} | {ok: false, reason: string}`

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

**한계(스펙에 명시)**: 이 검사는 최선의 노력이다. 사용자 정의 함수의 부작용, DB별 방언의 새 구문은 못 막는다. 2선 = PostgreSQL `default_transaction_read_only`(세션에서 `SET`으로 되돌릴 수 있으나 `SET`은 4에서 금지), 최종 방어선 = **읽기 전용 DB 계정**(폼 안내). 오탐(정상 SELECT 거부)은 열 이름이 금지 단어 그대로인 경우(`"update"` 같은 따옴표 식별자는 1에서 치환되어 통과, 비인용 `set` 열은 거부됨) 정도로 허용한다.

## 9. 오류 처리

| 상황 | 처리 |
|---|---|
| 키/salt 미설정 | API 503(GET은 `enabled:false`), 폼 DB 버튼 숨김, claim은 빈 목록 + `dbNotices` "DB 접속정보 기능이 꺼져 있습니다" |
| 복호화 실패(키 변경 등) | 그 항목만 제외 + `dbNotices` "다시 저장 필요" + WARN 로그(id만) |
| 세션이 참조하던 행 삭제/비활성 | 다음 claim부터 제외 + `dbNotices`. 세션의 `db_connection_ids`는 그대로(다시 켜면 복귀) |
| Toolbox 미설치 | DB 서버 미부착 + 프롬프트 안내 |
| DB 접속 실패/타임아웃 | Toolbox 도구 오류가 모델에게 전달 → 답변에서 사용자에게 알림(별도 처리 없음) |
| 임시 파일 쓰기 실패 | 턴 실패(기존 경로) |
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
  - `permissions.test.ts`: `mcp__db-3__execute_sql` SELECT allow / UPDATE deny, `list_tables` allow, 미지 도구 deny, 비 DB 서버 기존 동작 회귀.
  - `dbMcp.test.ts`: DB별 prebuilt·env 매핑, 파일 0600·`wx`, servers 형태, toolbox 없음 → notice, cleanup 실패 내성, stale sweep.
  - `dbMcpLauncher.test.ts`: env pick(비밀 env 미전달), 종료 코드 전달, 잘못된 파일 → exit 1·stderr에 비밀번호 없음.
  - `sessionOptions.test.ts`: merge 순서(db가 마지막), QUESTION allowedTools 여전히 `[]`.
  - `interviewRunner.test.ts`: dbSection 프롬프트, 성공·예외 모두 finally cleanup.
- **프론트(vitest)**: Picker(기본 체크·상한·레포 변경 재조회), Dialog(종류별 port 기본값, 수정 모드 password 미채움, 테스트 결과 표시), questions index/detail 바디에 `dbConnectionIds`, `enabled:false`면 버튼 숨김.
- **라이브 스모크**: 4종 DB 각각 — 접속 테스트 성공 → 질문 1건(테이블 목록·간단 집계 SELECT 답변) → "이 행을 UPDATE 해줘" 요청 시 게이트 거부 메시지 확인 → 대화 중 DB 해제 시 note + 다음 턴 init 도구 목록에서 사라짐. 실행 중 `Get-CimInstance Win32_Process`로 claude.exe·node(런처)·toolbox.exe 명령줄에 비밀번호가 없는지 확인. 턴 종료 후 `DB_MCP_TMP_DIR`이 비었는지 확인.

## 11. 구현 시 먼저 확인할 것 (미검증 가정)

1. Toolbox `POSTGRES_QUERY_PARAMS`가 pgx 연결 문자열에 그대로 붙어 `options=-c default_transaction_read_only=on`이 먹는지(안 먹으면 2선 없이 진행하고 스펙 갱신).
2. Toolbox `mysql` 소스로 MariaDB(대상 버전) 접속·`list_tables` 동작.
3. 각 prebuilt의 `execute_sql`/`get_query_plan` 입력 파라미터 이름(`sql`, `sql_statement`, `query`) — `tools/list` 응답으로 확정 후 게이트 상수화.
4. Toolbox v1.13.1 Windows/amd64 바이너리 배포 URL과 `--prebuilt … --stdio` 조합이 Windows에서 stdout 오염(로그 출력) 없이 동작하는지.
5. Oracle `ORACLE_CONNECTION_STRING`이 `host:port/service` 형식(EZConnect)을 받는지.
6. SDK 0.2.117 + CLI 2.1.284에서 `type:'stdio'` 서버가 `strictMcpConfig:true`·`settingSources:[]` 아래 정상 기동하는지(스파이크 1회, `spikes/06-stdio-db-mcp.ts`).

## 12. 범위 밖

- 인터뷰(INTERVIEW)·디자인/구현 워커에 DB MCP 연동(`WorkerMcpSupport` 확장은 후속).
- 키 교체(재암호화) 도구, 접속정보 사용 감사 로그.
- SSH 터널·Oracle Wallet·SSL 클라이언트 인증서 등 고급 접속 옵션(필요 시 `options` 컬럼으로 확장).
- 쿼리 결과 행 수 제한(Toolbox 기본 동작에 맡김; 컨텍스트 과다는 후속 관찰).

## 13. 배포 순서·운영

1. `public.env`에 `NETISMAKER_DB_SECRET_KEY`·`NETISMAKER_DB_SECRET_SALT` 추가(분실 시 저장된 접속정보 전부 재입력 — 백업 위치를 운영 노트에 기록).
2. Toolbox 설치 스크립트 실행 → `TOOLBOX_PATH` 설정.
3. API(V24 마이그레이션) → 인터뷰 서비스 → 프론트 `.output` 재빌드. 필드가 전부 additive/optional이라 순서 비민감. 재기동 전 진행 중 세션 없는지 DB 확인(기존 운영 규칙).
