# 질문 세션 DB 접속정보 → 내장 DB MCP 자동 연동 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 질문 세션에서 레포에 묶인 DB 접속정보(PostgreSQL·MySQL·MariaDB·Oracle)를 고르면, 에이전트가 인터뷰 서비스에 내장된 DB MCP 서버로 그 DB를 읽기 전용으로 조회한다.

**Architecture:** 접속정보는 `com.db_connection`에 AES-GCM 암호문으로 저장하고(레포 공용 REPO + 개인 USER), 세션에는 id만 둔다. claim 때 API가 복호화한 접속정보를 내부 API로 인터뷰 서비스에 넘기고, 러너가 턴마다 연결별 SDK MCP 서버(`createSdkMcpServer`, 이름 `db-<id>`, 도구 `query`·`list_tables`·`describe_table`)를 같은 프로세스 안에 만든다 — CLI에는 `{type:"sdk", name}`만 가므로 명령줄·파일·자식 env·`mcps_extra`·로그에 비밀번호가 남지 않는다. 읽기 전용은 3중: 질문 게이트(`canUseTool`)와 도구 핸들러가 SQL을 방언별로 검사하고, 드라이버(`pg`·`mysql2`·`oracledb` thin)가 READ ONLY 트랜잭션 안에서 실행한 뒤 항상 ROLLBACK하며, 30초·200행·셀 2000자 상한을 건다.

**Tech Stack:** Spring Boot 3(JPA·Flyway·spring-security-crypto `Encryptors.delux`), JDBC 드라이버 4종, Node 20 + `@anthropic-ai/claude-agent-sdk` 0.2.117(`createSdkMcpServer`·`tool`) + `zod` 4 + `pg`·`mysql2`·`oracledb` + vitest, Nuxt 3 + Quasar + vitest.

**Spec:** `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md`

## Global Constraints

- 작업 위치: worktree `C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\question-db-mcp`, 브랜치 `feat/question-db-mcp`. **본 체크아웃(`commanCenter/`)에서 빌드·테스트 금지** — 라이브 `bootRun`이 그 `build/classes`를 쓴다.
- JDK: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1`. Gradle 테스트는 `--tests`에 **클래스 FQN을 명시**(`"*Interview*"` 글롭은 Git Bash에서 `netismaker-interview-service` 디렉터리로 확장돼 실패).
- Docker 없음 → `RUN_TESTCONTAINERS=true` 통합 테스트는 이 PC에서 skip(CI에서 돈다). 단위 테스트를 1차 검증으로 삼는다.
- 인터뷰 서비스 vitest는 이 PC에서 **기존 5건이 원래 실패**(claudeCli 경로 구분자 1, QUESTION 게이트 2, attachmentRoot Read 게이트 2). 그 5건 외 실패만 회귀로 본다.
- CRLF: `core.autocrlf=true`. Git Bash `sed -i` 뒤에는 `file <경로>`로 줄끝을 확인하고 필요하면 `sed -i 's/\r$//; s/$/\r/'`로 되돌린다(Edit/Write 도구는 그대로 써도 된다).
- Agent SDK **0.2.117 유지**. 인터뷰 서비스 새 의존성은 `pg`·`mysql2`·`oracledb`·`zod`(^4, SDK peer)뿐 — 외부 바이너리 없음. 드라이버는 **변수 모듈명 동적 import**(`const load = (m: string) => import(m)`)로 불러 타입 패키지 없이 컴파일되고, 설치가 빠져도 그 도구 호출만 실패한다.
- DB 종류 enum 값은 정확히 `POSTGRESQL` | `MYSQL` | `MARIADB` | `ORACLE`. 기본 포트 5432/3306/3306/1521.
- MCP 서버 이름은 `db-<접속정보 id>`. MCP 카탈로그 이름에서 `db-` 접두사는 예약(거부).
- 세션당 DB 연결 선택 상한 **3**. 접속 테스트 타임아웃 **5초**.
- 설정 키: `app.db-secret.key` ← env `NETISMAKER_DB_SECRET_KEY`, `app.db-secret.salt` ← env `NETISMAKER_DB_SECRET_SALT`(hex, 짝수 길이 16자 이상). 둘 중 하나라도 없거나 salt 형식이 틀리면 **기능만 꺼진다**(부팅 실패 금지).
- 비밀번호(평문·암호문)는 **브라우저로 가는 어떤 응답·로그·예외 메시지·claude CLI 명령줄·`mcps_extra`에도 나가면 안 된다.** claim 응답(내부 API)만 예외.
- DB 도구 상한(코드 상수 `DB_LIMITS`): 문장 30초, 접속 10초, 결과 200행(카탈로그 1000행), 셀 2000자, 전체 60000자. DB 접속은 첫 도구 호출 때(lazy), 턴 종료 `finally`에서 `close()`.
- 비밀번호가 SDK `options`에 들어가는 형태는 `{type:'sdk', name, instance}`뿐이어야 한다 — `type:'stdio'` 서버에 접속정보를 넣지 말 것(SDK가 `--mcp-config` 명령줄에 싣는다).
- 프론트: 작은따옴표 + 세미콜론 없음, 문자열은 한국어 하드코딩, 클래스명에 Quasar 반응형 예약어(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`) 금지, `q-dialog` 안 루트 요소는 `<div>`, 모바일 분기는 `$q.screen.lt.md`. `npm run lint-prettier`를 `frontend/` 전체에 돌리지 말 것.
- DB 기능이 꺼져 있으면(`GET /api/db-connections` → `enabled:false`) 프론트는 DB 버튼을 숨기고 요청 바디에 `dbConnectionIds`를 **싣지 않는다**(기존 바디 그대로 — 기존 테스트가 정확 일치로 검증한다).
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **저장 비밀번호 유출 경로** — 편집 권한이 없는 사용자가 공용(REPO) 접속정보 id와 **자기 서버 host**로 `POST /api/db-connections/test`를 보내면 API가 저장된 비밀번호로 그 host에 접속을 시도한다. 기대: 403, tester 호출 없음. → Task 5 `test_by_id_requires_edit_permission`.
2. **JDBC URL 파라미터 주입** — host/DB명에 `db?allowLoadLocalInfile=true`, `h/evil`, `h:1/x` 같은 값을 넣으면 드라이버 옵션이 바뀐다. 기대: 400(DTO 패턴 거부). → Task 6 `DbConnectionDtoValidationTest`.
3. **claim 보강 실패로 세션이 RUNNING에 묶임** — claim 트랜잭션이 커밋된 뒤 접속정보 조회가 예외를 던지면 응답이 500이 되고 세션이 stale 회수 때까지 멈춘다. 기대: 200 + `dbNotices` 안내, DB 서버 없이 턴 진행. → Task 8 `claim_survives_db_resolution_failure`.
4. **문자열·주석 경계 우회** — `SELECT 'a\'; DELETE …`(PG 표준 문자열), MySQL `/*! DELETE */` 실행 주석, PG에서 `#`(연산자) 뒤 숨기기, `$$…$$` 안 `;`, MySQL `--x`(주석 아님). 기대: 전부 거부 또는 숨긴 부분까지 검사. → Task 9 우회 사례 표.
5. **턴 실패 시 커넥션 잔존 / DB 오류 메시지의 비밀번호 노출** — SDK 쿼리가 throw해도 열린 DB 커넥션이 닫혀야 하고, 드라이버 오류 메시지(접속 문자열·비밀번호를 담을 수 있음)가 도구 결과·로그로 나가면 안 된다. 기대: `finally` close, `safeMessage` 치환. → Task 11 `error_text_never_contains_password`, Task 12 `closes_db_sessions_when_query_throws`.
6. **게이트 우회 시 쓰기** — `canUseTool`이 어떤 이유로 allow해도(규칙 버그·SDK 변경) 쓰기가 실행되면 안 된다. 기대: 핸들러가 SQL을 다시 거부하고, 통과해도 READ ONLY 트랜잭션이 막는다. → Task 11 `handler_rejects_dml_even_if_gate_allowed`, 어댑터 문장 순서 테스트.

---

## File Structure

**Java (API)**
- Create `src/main/resources/db/migration/V24__db_connection.sql` — 테이블 + `interview_session.db_connection_ids`
- Create `src/main/java/com/hamonsoft/netismaker/entity/DbType.java` — 종류·기본 포트·JDBC URL·프로브 SQL
- Create `src/main/java/com/hamonsoft/netismaker/entity/DbConnectionScope.java` — REPO/USER
- Create `src/main/java/com/hamonsoft/netismaker/entity/DbConnection.java` — 엔티티(비밀번호는 암호문만)
- Create `src/main/java/com/hamonsoft/netismaker/repository/DbConnectionRepository.java`
- Create `src/main/java/com/hamonsoft/netismaker/service/DbSecretCipher.java` — AES-GCM, 키 없으면 disabled
- Create `src/main/java/com/hamonsoft/netismaker/service/DbConnectionTester.java` + `JdbcDbConnectionTester.java` — JDBC 접속 테스트
- Create `src/main/java/com/hamonsoft/netismaker/dto/DbConnectionDto.java` — View/ListResponse/UpsertRequest/TestRequest/TestResult/Chip
- Create `src/main/java/com/hamonsoft/netismaker/service/DbConnectionService.java` — CRUD·권한·선택 검증·칩·claim 해석
- Create `src/main/java/com/hamonsoft/netismaker/controller/DbConnectionController.java`
- Modify `entity/InterviewSession.java`, `dto/QuestionCreateRequest.java`, `dto/QuestionAskRequest.java`, `service/QuestionService.java`, `controller/QuestionController.java`, `dto/InterviewResponse.java`, `dto/InterviewClaimResponse.java`, `controller/InterviewWorkerController.java`, `dto/McpCatalogDto.java`, `build.gradle`, `src/main/resources/application.yml`

**인터뷰 서비스 (`netismaker-interview-service/`)**
- Create `src/sdk/sqlReadOnly.ts` — 방언별 읽기 전용 SQL 검사
- Create `src/sdk/db/limits.ts` — 상한 상수
- Create `src/sdk/db/result.ts` — 셀 변환·결과 직렬화·상한
- Create `src/sdk/db/adapters.ts` — 종류별 읽기 전용 세션(PG·MySQL/MariaDB·Oracle), 드라이버 로더
- Create `src/sdk/db/catalog.ts` — list_tables/describe_table 방언별 바인딩 SQL
- Create `src/sdk/dbMcp.ts` — 연결별 SDK MCP 서버·도구 3개·lazy 접속·close
- Create `scripts/spikeSdkMcp.ts`, `scripts/spikeDbAdapter.ts` — 스파이크(일회성)
- Modify `package.json`/`package-lock.json`, `src/types.ts`, `src/sdk/permissions.ts`, `src/sdk/sessionOptions.ts`, `src/runner/interviewRunner.ts`

**프론트 (`frontend/`)**
- Create `composables/dbConnections.ts`, `components/DbConnectionDialog.vue`, `components/DbConnectionPicker.vue`, `components/DbConnectionAdminDialog.vue`
- Modify `pages/questions/index.vue`, `pages/questions/[id].vue`, `components/InterviewPanel.vue`, `composables/questions.ts`, `pages/admin/repo-catalog.vue`

**문서**: `CLAUDE.md`, 스펙 §8.1·§11 갱신

---

### Task 0: worktree 준비

**Files:** 없음(환경)

- [ ] **Step 1: node_modules 준비** (새 worktree에는 node_modules가 없다)

프론트는 본 체크아웃 것을 정션으로 빌려 쓴다. **인터뷰 서비스는 정션 금지** — Task 1에서 의존성을 추가하는데, 정션이면 라이브가 쓰는 본 체크아웃 node_modules가 바뀐다. 인터뷰 서비스는 worktree 안에 따로 설치한다(npm 레지스트리 다운로드 — 실행 전 사용자에게 알린다).

```powershell
cd C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\question-db-mcp
cmd /c mklink /J frontend\node_modules ..\..\..\frontend\node_modules
cd netismaker-interview-service; npm ci; cd ..
```
Expected: `Junction created for …` 한 줄, `npm ci`가 `added N packages`로 끝난다. ⚠️ 나중에 프론트 정션을 지울 때는 `cmd /c rmdir frontend\node_modules`(정션만 제거). `rm -rf`/`Remove-Item -Recurse`는 원본 node_modules를 지운다.

- [ ] **Step 2: 기준선 테스트**

```bash
cd /c/Users/mic/NetisMaker/commanCenter/.claude/worktrees/question-db-mcp/netismaker-interview-service && npx vitest run 2>&1 | tail -5
cd ../frontend && npx vitest run 2>&1 | tail -5
```
Expected: 인터뷰 서비스는 실패 5건(Global Constraints의 기존 실패), 프론트는 전부 통과. 숫자를 기록해 두고 이후 회귀 판단에 쓴다.

---

### Task 1: 의존성 추가 + SDK MCP 스파이크(스펙 §11-1)

**Files:**
- Modify: `netismaker-interview-service/package.json`, `netismaker-interview-service/package-lock.json`
- Create: `netismaker-interview-service/scripts/spikeSdkMcp.ts`
- Modify: `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md` §11 (결과 기록)

**Interfaces:**
- Produces: 의존성 `pg`·`mysql2`·`oracledb`·`zod`(Task 11이 사용). 스펙 §11-1 판정 — **미도달이면 Task 10 이후로 넘어가지 말고 사용자에게 보고**(내장 서버 도구가 `canUseTool`을 안 거치면 게이트 설계가 무너진다. 핸들러 재검사·READ ONLY가 남지만 설계 재검토 대상).

- [ ] **Step 1: 의존성 추가** — npm 레지스트리 다운로드이므로 실행 전 사용자에게 알린다.

```bash
cd /c/Users/mic/NetisMaker/commanCenter/.claude/worktrees/question-db-mcp/netismaker-interview-service
npm install pg@^8 mysql2@^3 oracledb@^6 zod@^4
node -e "for (const m of ['pg','mysql2','oracledb','zod']) console.log(m, require(m + '/package.json').version)"
```
Expected: 네 줄 버전 출력. `oracledb`는 6.x(thin 모드 기본). `git diff package.json`에 dependencies 4개만 추가됐는지 확인.

- [ ] **Step 2: 스파이크 스크립트 작성**

`netismaker-interview-service/scripts/spikeSdkMcp.ts`:
```ts
/**
 * 스파이크(스펙 2026-10-02 §11-1) — 내장 SDK MCP 서버(type:'sdk')가 질문 세션 격리 옵션 아래 붙고,
 * 그 도구 호출이 canUseTool을 거치는지(deny면 핸들러가 안 불리는지) 실측한다. DB 없음 — 가짜 도구.
 * 실행: cd netismaker-interview-service && node --import tsx scripts/spikeSdkMcp.ts
 * 판정: 마지막 줄 RESULT: 도달(exit 0) / 미도달(exit 1). 일회성 도구.
 */
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';

const handled: string[] = [];
const gated: string[] = [];
const server = createSdkMcpServer({
  name: 'db-1',
  tools: [
    tool('query', '스파이크용 가짜 DB 조회', { sql: z.string() }, async ({ sql }) => {
      handled.push(sql);
      return { content: [{ type: 'text', text: '{"columns":["n"],"rows":[[42]],"rowCount":1,"truncated":false}' }] };
    }),
  ],
});

async function* prompt(): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield {
    type: 'user',
    message: {
      role: 'user',
      content: 'mcp__db-1__query 도구를 정확히 두 번 호출하세요: 먼저 sql="SELECT 42", 다음 sql="DELETE FROM t". 거부돼도 다시 시도하지 말고 결과를 한 줄로 보고하세요.',
    },
  };
}

async function run(): Promise<void> {
  const stream = realQuery({
    prompt: prompt(),
    options: {
      pathToClaudeCodeExecutable: resolveClaudeCli(process.env.CLAUDE_CLI),
      plugins: [],
      settingSources: [],
      strictMcpConfig: true,
      allowedTools: [], // QUESTION 구성과 동일 — 사전승인 없음
      cwd: process.cwd(),
      permissionMode: 'default',
      mcpServers: { 'db-1': server },
      canUseTool: async (toolName: string, input: Record<string, unknown>) => {
        gated.push(`${toolName} ${JSON.stringify(input)}`);
        const sql = String(input.sql ?? '');
        return sql.toUpperCase().startsWith('SELECT')
          ? { behavior: 'allow' as const, updatedInput: input }
          : { behavior: 'deny' as const, message: 'spike: 읽기 전용' };
      },
    } as never,
  });
  for await (const msg of stream) {
    const m = msg as { type: string; subtype?: string; tools?: string[]; mcp_servers?: unknown };
    if (m.type === 'system' && m.subtype === 'init') {
      console.log('[init] mcp_servers=', JSON.stringify(m.mcp_servers), 'tools=', (m.tools ?? []).filter((t) => t.startsWith('mcp__')));
    }
  }
  console.log('[gate]', gated);
  console.log('[handler]', handled);
  const ok = gated.some((g) => g.startsWith('mcp__db-1__query')) && handled.includes('SELECT 42') && !handled.some((s) => s.startsWith('DELETE'));
  console.log(ok ? 'RESULT: 도달 — sdk 서버 도구가 canUseTool을 경유하고, deny는 핸들러를 막는다' : 'RESULT: 미도달 — 보고 후 중단');
  process.exit(ok ? 0 : 1);
}

void run();
```
(`realQuery` 옵션 타입이 `settingSources`/`strictMcpConfig`를 이미 받으면 `as never`는 지운다 — `sessionOptions.ts`가 같은 키를 쓰고 있다.)

- [ ] **Step 3: 스파이크 실행** — claude 구독 호출 1회(소량).

```bash
cd /c/Users/mic/NetisMaker/commanCenter/.claude/worktrees/question-db-mcp/netismaker-interview-service
node --import tsx scripts/spikeSdkMcp.ts
```
Expected: `[init]`의 tools에 `mcp__db-1__query`, `[gate]`에 두 호출, `[handler]`에는 `SELECT 42`만, 마지막 줄 `RESULT: 도달`. 실행 중 다른 PowerShell에서 `Get-CimInstance Win32_Process -Filter "Name='claude.exe'" | Select-Object CommandLine`로 `--mcp-config`에 `{"type":"sdk","name":"db-1"}`만 있는지도 본다(가능하면).

- [ ] **Step 4: 스펙 §11-1에 결과 기록** — 항목 끝에 `→ 2026-10-02 확인: …`(도달/미도달, init 도구 목록, 명령줄 확인 여부) 한 줄. §11-3·4는 "라이브 스모크(Task 16)에서 확인", §11-2·5는 "Task 11에서 확인"으로 적는다.

- [ ] **Step 5: Commit**

```bash
git add netismaker-interview-service/package.json netismaker-interview-service/package-lock.json netismaker-interview-service/scripts/spikeSdkMcp.ts docs/superpowers/specs/2026-10-02-question-db-mcp-design.md
git commit -m "chore(interview): DB 드라이버·zod 의존성 + SDK MCP 게이트 스파이크 결과

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 데이터 모델 (V24 + DbType + DbConnection + 세션 필드)

**Files:**
- Create: `src/main/resources/db/migration/V24__db_connection.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/DbType.java`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/DbConnectionScope.java`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/DbConnection.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/DbConnectionRepository.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java` (mcpCatalogIds 필드 바로 아래)
- Test: `src/test/java/com/hamonsoft/netismaker/entity/DbTypeTest.java`, `src/test/java/com/hamonsoft/netismaker/repository/DbConnectionRepositoryTest.java`

**Interfaces:**
- Produces:
  - `enum DbType { POSTGRESQL, MYSQL, MARIADB, ORACLE; String getLabel(); int getDefaultPort(); String jdbcUrl(String host, int port, String database); String probeSql(); }`
  - `enum DbConnectionScope { REPO, USER }`
  - `DbConnection.create(DbConnectionScope scope, Long repoCatalogId, String ownerUserId, String name, DbType dbType, String host, int port, String databaseName, String username, String passwordEnc, String createdBy)`; getter/setter 전부(Lombok), `boolean isOwnedBy(String userId)`, `toString()`은 암호문 제외
  - `DbConnectionRepository.findByRepoCatalogIdOrderByScopeAscNameAsc(Long repoCatalogId): List<DbConnection>`
  - `InterviewSession.getDbConnectionIds(): List<Long>` / `setDbConnectionIds(List<Long>)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/entity/DbTypeTest.java`:
```java
package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbTypeTest {

    @Test
    void jdbc_url_per_type() {
        assertThat(DbType.POSTGRESQL.jdbcUrl("db.local", 5432, "app")).isEqualTo("jdbc:postgresql://db.local:5432/app");
        assertThat(DbType.MYSQL.jdbcUrl("db.local", 3306, "app")).isEqualTo("jdbc:mysql://db.local:3306/app");
        assertThat(DbType.MARIADB.jdbcUrl("db.local", 3307, "app")).isEqualTo("jdbc:mariadb://db.local:3307/app");
        assertThat(DbType.ORACLE.jdbcUrl("ora", 1521, "ORCLPDB1")).isEqualTo("jdbc:oracle:thin:@//ora:1521/ORCLPDB1");
    }

    @Test
    void default_ports_labels_and_probe_sql() {
        assertThat(List.of(DbType.values()).stream().map(DbType::getDefaultPort).toList())
                .containsExactly(5432, 3306, 3306, 1521);
        assertThat(DbType.MARIADB.getLabel()).isEqualTo("MariaDB");
        assertThat(DbType.ORACLE.probeSql()).isEqualTo("SELECT 1 FROM DUAL");
        assertThat(DbType.MYSQL.probeSql()).isEqualTo("SELECT 1");
    }

    @Test
    void db_connection_to_string_never_contains_ciphertext() {
        DbConnection c = DbConnection.create(DbConnectionScope.USER, 1L, "user1", "운영 DB", DbType.POSTGRESQL,
                "db.local", 5432, "app", "reader", "CIPHERTEXT-abc", "user1");
        assertThat(c.toString()).doesNotContain("CIPHERTEXT").contains("운영 DB");
        assertThat(c.isOwnedBy("user1")).isTrue();
        assertThat(c.isOwnedBy("user2")).isFalse();
        assertThat(c.isEnabled()).isTrue();
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.entity.DbTypeTest
```
Expected: 컴파일 실패(`DbType` 없음).

- [ ] **Step 3: 마이그레이션 작성**

`src/main/resources/db/migration/V24__db_connection.sql`:
```sql
-- 질문 세션 DB 접속정보 (스펙 docs/superpowers/specs/2026-10-02-question-db-mcp-design.md §4).
-- REPO = 관리자 공용(그 레포를 고르는 모든 사용자), USER = 개인(본인만). 비밀번호는 AES-256-GCM 암호문만 저장.
CREATE TABLE IF NOT EXISTS com.db_connection (
    id               BIGSERIAL PRIMARY KEY,
    scope            VARCHAR(10)  NOT NULL,
    repo_catalog_id  BIGINT       NOT NULL REFERENCES com.repo_catalog(id) ON DELETE CASCADE,
    owner_user_id    VARCHAR(20),
    name             VARCHAR(100) NOT NULL,
    db_type          VARCHAR(20)  NOT NULL,
    host             VARCHAR(255) NOT NULL,
    port             INTEGER      NOT NULL,
    database_name    VARCHAR(255) NOT NULL,
    username         VARCHAR(255) NOT NULL,
    password_enc     TEXT         NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    created_by       VARCHAR(20),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_db_connection_scope CHECK (scope IN ('REPO','USER')),
    CONSTRAINT ck_db_connection_owner CHECK ((scope = 'USER') = (owner_user_id IS NOT NULL)),
    CONSTRAINT ck_db_connection_type  CHECK (db_type IN ('POSTGRESQL','MYSQL','MARIADB','ORACLE'))
);
CREATE INDEX IF NOT EXISTS idx_db_connection_repo ON com.db_connection(repo_catalog_id);

-- 세션은 id만 보관(스펙 §4 — 비밀번호/암호문을 세션 행마다 복제하지 않는다). 인터뷰 세션은 항상 [].
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS db_connection_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
```

- [ ] **Step 4: enum·엔티티·레포지토리 작성**

`src/main/java/com/hamonsoft/netismaker/entity/DbType.java`:
```java
package com.hamonsoft.netismaker.entity;

/**
 * 질문 세션 DB 접속정보 종류 (스펙 2026-10-02 §2). 값 이름은 DB CHECK 제약·프론트·인터뷰 서비스와 공유 — 바꾸지 말 것.
 * MariaDB는 인터뷰 서비스에서 mysql2 드라이버를 쓰지만, 접속 테스트(JDBC)는 MariaDB 드라이버로 한다.
 */
public enum DbType {
    POSTGRESQL("PostgreSQL", 5432),
    MYSQL("MySQL", 3306),
    MARIADB("MariaDB", 3306),
    ORACLE("Oracle", 1521);

    private final String label;
    private final int defaultPort;

    DbType(String label, int defaultPort) {
        this.label = label;
        this.defaultPort = defaultPort;
    }

    public String getLabel() { return label; }

    public int getDefaultPort() { return defaultPort; }

    /** host·database는 DTO 패턴 검증을 통과한 값만 들어온다(URL 파라미터 주입 차단 — DbConnectionDto 주석). */
    public String jdbcUrl(String host, int port, String database) {
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database;
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + database;
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        };
    }

    public String probeSql() {
        return this == ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }
}
```

`src/main/java/com/hamonsoft/netismaker/entity/DbConnectionScope.java`:
```java
package com.hamonsoft.netismaker.entity;

/** REPO = 관리자 공용(레포를 고르는 모든 사용자), USER = 개인(owner_user_id 본인만). */
public enum DbConnectionScope { REPO, USER }
```

`src/main/java/com/hamonsoft/netismaker/entity/DbConnection.java`:
```java
package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 질문 세션 DB 접속정보 (스펙 2026-10-02 §4). passwordEnc = DbSecretCipher 암호문 — 평문은 claim 시에만 복호화한다.
 * 브라우저로는 DbConnectionDto.View만 내보낸다(암호문 필드 없음). toString도 암호문을 뺀다.
 */
@Entity
@Table(name = "db_connection", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DbConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DbConnectionScope scope;

    @Column(name = "repo_catalog_id", nullable = false)
    private Long repoCatalogId;

    @Column(name = "owner_user_id", length = 20)
    private String ownerUserId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "db_type", nullable = false, length = 20)
    private DbType dbType;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "database_name", nullable = false, length = 255)
    private String databaseName;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "password_enc", nullable = false, columnDefinition = "TEXT")
    private String passwordEnc;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", length = 20)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static DbConnection create(DbConnectionScope scope, Long repoCatalogId, String ownerUserId, String name,
                                      DbType dbType, String host, int port, String databaseName, String username,
                                      String passwordEnc, String createdBy) {
        DbConnection c = new DbConnection();
        c.scope = scope;
        c.repoCatalogId = repoCatalogId;
        c.ownerUserId = ownerUserId;
        c.name = name;
        c.dbType = dbType;
        c.host = host;
        c.port = port;
        c.databaseName = databaseName;
        c.username = username;
        c.passwordEnc = passwordEnc;
        c.enabled = true;
        c.createdBy = createdBy;
        OffsetDateTime now = OffsetDateTime.now();
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public boolean isOwnedBy(String userId) {
        return scope == DbConnectionScope.USER && userId != null && userId.equals(ownerUserId);
    }

    @Override
    public String toString() {
        return "DbConnection{id=" + id + ", scope=" + scope + ", name=" + name + ", dbType=" + dbType
                + ", host=" + host + ":" + port + "/" + databaseName + "}";
    }
}
```

`src/main/java/com/hamonsoft/netismaker/repository/DbConnectionRepository.java`:
```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.DbConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DbConnectionRepository extends JpaRepository<DbConnection, Long> {
    /** 레포의 전체 접속정보(REPO 먼저, 이름순). 보이는 범위 필터는 DbConnectionService.canView. */
    List<DbConnection> findByRepoCatalogIdOrderByScopeAscNameAsc(Long repoCatalogId);
}
```

`InterviewSession.java` — `mcpCatalogIds` 필드 선언 바로 아래에 추가:
```java
    /**
     * 선택한 DB 접속정보 id (스펙 2026-10-02 §4). 내용을 박제하지 않는다 — claim 때 현재 행을 조회·복호화한다
     * (비밀번호/암호문을 세션 행마다 복제하지 않기 위해). 인터뷰 세션은 항상 [].
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "db_connection_ids", nullable = false, columnDefinition = "jsonb")
    @Setter
    private List<Long> dbConnectionIds = new ArrayList<>();
```

- [ ] **Step 5: 레포지토리 통합 테스트 작성** (CI에서 돈다 — 로컬은 skip)

`src/test/java/com/hamonsoft/netismaker/repository/DbConnectionRepositoryTest.java`:
```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V24 com.db_connection — 조회 순서, owner CHECK, 세션 db_connection_ids jsonb 왕복. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@ContextConfiguration(initializers = TestcontainersConfig.class)
class DbConnectionRepositoryTest {

    @Autowired private DbConnectionRepository repo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    private Long repoId;

    @BeforeEach void setUp() {
        repo.deleteAll();
        sessionRepo.deleteAll();
        repoId = repoCatalogRepo.save(RepoCatalogEntry.create("DB테스트레포", "https://github.com/acme/dbtest.git",
                "github", "acme/dbtest", "main", null, "admin1")).getId();
    }

    @Test
    void REPO가_먼저_이름순으로_조회된다() {
        repo.save(DbConnection.create(DbConnectionScope.USER, repoId, "user1", "가 개인", DbType.MYSQL,
                "h", 3306, "app", "u", "enc", "user1"));
        repo.save(DbConnection.create(DbConnectionScope.REPO, repoId, null, "나 공용", DbType.POSTGRESQL,
                "h", 5432, "app", "u", "enc", "admin1"));
        assertThat(repo.findByRepoCatalogIdOrderByScopeAscNameAsc(repoId))
                .extracting(DbConnection::getName).containsExactly("나 공용", "가 개인");
    }

    @Test
    void USER인데_owner가_없으면_CHECK_위반() {
        assertThatThrownBy(() -> repo.saveAndFlush(DbConnection.create(DbConnectionScope.USER, repoId, null, "x",
                DbType.ORACLE, "h", 1521, "svc", "u", "enc", "user1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 세션_db_connection_ids_왕복() {
        InterviewSession s = InterviewSession.createQuestion("acme/dbtest", "main", "t", "q", "user1",
                List.of(), "claude-opus-5-5", "high");
        s.setDbConnectionIds(List.of(3L, 9L));
        Long id = sessionRepo.save(s).getId();
        assertThat(sessionRepo.findById(id).orElseThrow().getDbConnectionIds())
                .extracting(Number::longValue).containsExactly(3L, 9L);
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.entity.DbTypeTest --tests com.hamonsoft.netismaker.repository.DbConnectionRepositoryTest
```
Expected: `DbTypeTest` 3건 PASS, `DbConnectionRepositoryTest`는 SKIPPED(로컬). `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V24__db_connection.sql src/main/java/com/hamonsoft/netismaker/entity/DbType.java src/main/java/com/hamonsoft/netismaker/entity/DbConnectionScope.java src/main/java/com/hamonsoft/netismaker/entity/DbConnection.java src/main/java/com/hamonsoft/netismaker/repository/DbConnectionRepository.java src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java src/test/java/com/hamonsoft/netismaker/entity/DbTypeTest.java src/test/java/com/hamonsoft/netismaker/repository/DbConnectionRepositoryTest.java
git commit -m "feat(db-mcp): V24 db_connection 테이블·엔티티 + 세션 db_connection_ids

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: DbSecretCipher (AES-GCM, 키 없으면 꺼짐)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/DbSecretCipher.java`
- Modify: `src/main/resources/application.yml` (`app:` 아래, `attachment:` 블록 앞)
- Test: `src/test/java/com/hamonsoft/netismaker/service/DbSecretCipherTest.java`

**Interfaces:**
- Produces: `DbSecretCipher(String key, String salt)`; `boolean isEnabled()`; `String encrypt(String plain)`(꺼져 있으면 `IllegalStateException`); `Optional<String> tryDecrypt(String enc)`(꺼짐·키 불일치·손상 → empty)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/DbSecretCipherTest.java`:
```java
package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbSecretCipherTest {

    private static final String SALT = "0123456789abcdef";

    @Test
    void roundtrip_and_ciphertext_is_randomized() {
        DbSecretCipher c = new DbSecretCipher("k3y-for-test", SALT);
        assertThat(c.isEnabled()).isTrue();
        String a = c.encrypt("p@ss");
        String b = c.encrypt("p@ss");
        assertThat(a).isNotEqualTo(b).doesNotContain("p@ss");
        assertThat(c.tryDecrypt(a)).contains("p@ss");
    }

    @Test
    void other_key_cannot_decrypt() {
        String enc = new DbSecretCipher("key-A", SALT).encrypt("secret");
        assertThat(new DbSecretCipher("key-B", SALT).tryDecrypt(enc)).isEmpty();
        assertThat(new DbSecretCipher("key-A", SALT).tryDecrypt("not-hex!!")).isEmpty();
    }

    @Test
    void blank_key_or_salt_disables_without_throwing_at_construction() {
        for (DbSecretCipher c : new DbSecretCipher[] {
                new DbSecretCipher("", SALT), new DbSecretCipher("k", ""), new DbSecretCipher(null, null)}) {
            assertThat(c.isEnabled()).isFalse();
            assertThatThrownBy(() -> c.encrypt("x")).isInstanceOf(IllegalStateException.class);
            assertThat(c.tryDecrypt("abcd")).isEmpty();
        }
    }

    @Test
    void non_hex_or_short_salt_disables() {
        assertThat(new DbSecretCipher("k", "not-hex-salt-value").isEnabled()).isFalse();
        assertThat(new DbSecretCipher("k", "abcdef").isEnabled()).isFalse();     // 16자 미만
        assertThat(new DbSecretCipher("k", "0123456789abcdef0").isEnabled()).isFalse(); // 홀수 길이
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.DbSecretCipherTest
```
Expected: 컴파일 실패(`DbSecretCipher` 없음).

- [ ] **Step 3: 구현**

`src/main/java/com/hamonsoft/netismaker/service/DbSecretCipher.java`:
```java
package com.hamonsoft.netismaker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DB 접속정보 비밀번호 암복호화 (스펙 2026-10-02 §4.1). Encryptors.delux = AES-256-GCM + PBKDF2, 결과 hex.
 * 키(app.db-secret.key)나 salt(app.db-secret.salt, hex 짝수 길이 16자 이상)가 없거나 틀리면 기능만 끈다 —
 * 부팅을 막지 않는다. 키를 바꾸면 기존 행은 tryDecrypt가 empty → "다시 저장 필요"(키 교체 도구는 범위 밖).
 */
@Component
@Profile("api")
public class DbSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(DbSecretCipher.class);
    private static final String SALT_PATTERN = "^(?:[0-9a-fA-F]{2}){8,}$";

    private final TextEncryptor encryptor;   // null = 꺼짐

    public DbSecretCipher(@Value("${app.db-secret.key:}") String key,
                          @Value("${app.db-secret.salt:}") String salt) {
        this.encryptor = build(key, salt);
    }

    private static TextEncryptor build(String key, String salt) {
        if (key == null || key.isBlank() || salt == null || salt.isBlank()) {
            log.info("DB 접속정보 기능 꺼짐 — NETISMAKER_DB_SECRET_KEY/NETISMAKER_DB_SECRET_SALT 미설정");
            return null;
        }
        if (!salt.matches(SALT_PATTERN)) {
            log.warn("NETISMAKER_DB_SECRET_SALT가 hex(짝수 길이 16자 이상)가 아님 — DB 접속정보 기능 꺼짐");
            return null;
        }
        return Encryptors.delux(key, salt);
    }

    public boolean isEnabled() {
        return encryptor != null;
    }

    public String encrypt(String plain) {
        if (encryptor == null) throw new IllegalStateException("DB 접속정보 암호화 키가 설정되지 않았습니다");
        return encryptor.encrypt(plain);
    }

    /** 꺼짐·키 불일치·손상된 암호문이면 empty. 예외 메시지는 로그로도 내보내지 않는다(호출자가 id만 남긴다). */
    public Optional<String> tryDecrypt(String enc) {
        if (encryptor == null || enc == null) return Optional.empty();
        try {
            return Optional.of(encryptor.decrypt(enc));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
```

`application.yml` — `app:` 블록 안 `attachment:` 바로 위에(들여쓰기 2칸) 추가:
```yaml
  db-secret:                        # 질문 세션 DB 접속정보 암호화 (스펙 2026-10-02 §4.1). 둘 다 있어야 기능이 켜진다
    key: ${NETISMAKER_DB_SECRET_KEY:}
    salt: ${NETISMAKER_DB_SECRET_SALT:}   # hex, 짝수 길이 16자 이상
```

- [ ] **Step 4: 통과 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.DbSecretCipherTest
```
Expected: 4건 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/DbSecretCipher.java src/main/resources/application.yml src/test/java/com/hamonsoft/netismaker/service/DbSecretCipherTest.java
git commit -m "feat(db-mcp): DbSecretCipher — AES-GCM, 키 없으면 기능만 꺼짐

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: JDBC 접속 테스터 + 드라이버

**Files:**
- Modify: `build.gradle` (`runtimeOnly 'org.postgresql:postgresql'` 아래)
- Create: `src/main/java/com/hamonsoft/netismaker/service/DbConnectionTester.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/JdbcDbConnectionTester.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/JdbcDbConnectionTesterTest.java`

**Interfaces:**
- Consumes: `DbType.jdbcUrl`, `DbType.probeSql` (Task 2)
- Produces:
  - `interface DbConnectionTester { record Target(DbType dbType, String host, int port, String databaseName, String username, String password); record Result(boolean ok, String message); Result test(Target t); }` — `Target.toString()`은 비밀번호 제외
  - `JdbcDbConnectionTester.propsFor(Target): Properties` (package-private static), `mask(String msg, String secret): String` (package-private static)

- [ ] **Step 1: 드라이버 추가** — `build.gradle`:

```gradle
    // 질문 세션 DB 접속 테스트용 JDBC 드라이버 (스펙 2026-10-02 §5.2). 버전은 Spring Boot BOM 관리.
    runtimeOnly 'com.mysql:mysql-connector-j'
    runtimeOnly 'org.mariadb.jdbc:mariadb-java-client'
    runtimeOnly 'com.oracle.database.jdbc:ojdbc11'
```
확인:
```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew dependencies --configuration runtimeClasspath | grep -E "mysql-connector-j|mariadb-java-client|ojdbc11"
```
Expected: 세 줄(버전이 붙어 해석됨). `FAILED`가 보이면 BOM에 없는 것 — 해당 좌표에 Maven Central 최신 안정 버전을 명시한다.

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/JdbcDbConnectionTesterTest.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.DbType;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDbConnectionTesterTest {

    private static DbConnectionTester.Target target(DbType t, int port) {
        return new DbConnectionTester.Target(t, "127.0.0.1", port, "app", "reader", "S3cr3t-pw");
    }

    @Test
    void props_carry_credentials_and_timeouts_per_driver() {
        Properties pg = JdbcDbConnectionTester.propsFor(target(DbType.POSTGRESQL, 5432));
        assertThat(pg.getProperty("user")).isEqualTo("reader");
        assertThat(pg.getProperty("password")).isEqualTo("S3cr3t-pw");
        assertThat(pg.getProperty("connectTimeout")).isEqualTo("5");
        assertThat(JdbcDbConnectionTester.propsFor(target(DbType.MYSQL, 3306)).getProperty("connectTimeout")).isEqualTo("5000");
        assertThat(JdbcDbConnectionTester.propsFor(target(DbType.MARIADB, 3306)).getProperty("connectTimeout")).isEqualTo("5000");
        assertThat(JdbcDbConnectionTester.propsFor(target(DbType.ORACLE, 1521)).getProperty("oracle.net.CONNECT_TIMEOUT")).isEqualTo("5000");
    }

    @Test
    void mask_replaces_every_occurrence_of_the_secret() {
        assertThat(JdbcDbConnectionTester.mask("bad pw S3cr3t-pw for S3cr3t-pw", "S3cr3t-pw"))
                .isEqualTo("bad pw **** for ****");
        assertThat(JdbcDbConnectionTester.mask("msg", "")).isEqualTo("msg");
    }

    @Test
    void target_to_string_hides_password() {
        assertThat(target(DbType.ORACLE, 1521).toString()).doesNotContain("S3cr3t-pw").contains("127.0.0.1:1521/app");
    }

    @Test
    void closed_port_fails_fast_without_leaking_password() {
        // 포트 1은 닫혀 있다 — 드라이버가 즉시 connection refused. 결과 메시지에 비밀번호가 없어야 한다.
        DbConnectionTester.Result r = new JdbcDbConnectionTester().test(target(DbType.POSTGRESQL, 1));
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).startsWith("접속 실패: ").doesNotContain("S3cr3t-pw");
    }
}
```

- [ ] **Step 3: 실패 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.JdbcDbConnectionTesterTest
```
Expected: 컴파일 실패.

- [ ] **Step 4: 구현**

`src/main/java/com/hamonsoft/netismaker/service/DbConnectionTester.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.DbType;

/** 저장 전/후 접속 테스트 (스펙 2026-10-02 §5.2). 서비스 단위 테스트에서 목으로 갈아끼우려고 인터페이스로 둔다. */
public interface DbConnectionTester {

    record Target(DbType dbType, String host, int port, String databaseName, String username, String password) {
        @Override
        public String toString() {
            return "Target[" + dbType + " " + host + ":" + port + "/" + databaseName + " user=" + username + "]";
        }
    }

    record Result(boolean ok, String message) {}

    Result test(Target target);
}
```

`src/main/java/com/hamonsoft/netismaker/service/JdbcDbConnectionTester.java`:
```java
package com.hamonsoft.netismaker.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * JDBC 접속 → 프로브 SQL 1회 (스펙 2026-10-02 §5.2). 타임아웃 5초. 비밀번호는 URL이 아니라 Properties로만 넘기고,
 * 실패 메시지에서 비밀번호 문자열을 가린다. DriverManager.setLoginTimeout(전역)은 쓰지 않는다.
 */
@Component
@Profile("api")
public class JdbcDbConnectionTester implements DbConnectionTester {

    @Override
    public Result test(Target t) {
        String url = t.dbType().jdbcUrl(t.host(), t.port(), t.databaseName());
        try (Connection c = DriverManager.getConnection(url, propsFor(t));
             Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            st.execute(t.dbType().probeSql());
            return new Result(true, "접속 성공");
        } catch (SQLException | RuntimeException e) {
            return new Result(false, "접속 실패: " + mask(String.valueOf(e.getMessage()), t.password()));
        }
    }

    static Properties propsFor(Target t) {
        Properties p = new Properties();
        p.setProperty("user", t.username());
        p.setProperty("password", t.password());
        switch (t.dbType()) {
            case POSTGRESQL -> {
                p.setProperty("connectTimeout", "5");
                p.setProperty("loginTimeout", "5");
                p.setProperty("socketTimeout", "10");
            }
            case MYSQL, MARIADB -> {
                p.setProperty("connectTimeout", "5000");
                p.setProperty("socketTimeout", "10000");
            }
            case ORACLE -> {
                p.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
                p.setProperty("oracle.jdbc.ReadTimeout", "10000");
            }
        }
        return p;
    }

    static String mask(String msg, String secret) {
        if (secret == null || secret.isEmpty()) return msg;
        return msg.replace(secret, "****");
    }
}
```

- [ ] **Step 5: 통과 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.JdbcDbConnectionTesterTest
```
Expected: 4건 PASS(마지막 테스트가 5초 넘게 걸리면 타임아웃 속성이 안 먹은 것 — 확인).

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/java/com/hamonsoft/netismaker/service/DbConnectionTester.java src/main/java/com/hamonsoft/netismaker/service/JdbcDbConnectionTester.java src/test/java/com/hamonsoft/netismaker/service/JdbcDbConnectionTesterTest.java
git commit -m "feat(db-mcp): JDBC 접속 테스터 + MySQL/MariaDB/Oracle 드라이버

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: DbConnectionService + DTO

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/DbConnectionDto.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/DbConnectionService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java` — 이 Task에서는 중첩 레코드 `DbConnectionRef`만 추가(필드 추가는 Task 8)
- Test: `src/test/java/com/hamonsoft/netismaker/service/DbConnectionServiceTest.java`

**Interfaces:**
- Consumes: `DbConnection`, `DbConnectionScope`, `DbType`, `DbConnectionRepository`(Task 2), `DbSecretCipher`(Task 3), `DbConnectionTester`(Task 4), `RepoCatalogRepository.existsById`
- Produces:
  - `DbConnectionDto.View(long id, String scope, long repoCatalogId, String name, String dbType, String host, int port, String databaseName, String username, boolean enabled, boolean mine)` + `static View of(DbConnection c, String viewerId)`
  - `DbConnectionDto.ListResponse(boolean enabled, List<View> items)`
  - `DbConnectionDto.UpsertRequest(DbConnectionScope scope, Long repoCatalogId, String name, DbType dbType, String host, Integer port, String databaseName, String username, String password, Boolean enabled)`
  - `DbConnectionDto.TestRequest(Long id, DbType dbType, String host, Integer port, String databaseName, String username, String password)`
  - `DbConnectionDto.TestResult(boolean ok, String message)`, `DbConnectionDto.Chip(long id, String name, String dbType)`
  - `InterviewClaimResponse.DbConnectionRef(String serverName, String label, String dbType, String host, int port, String database, String username, String password)` — toString 마스킹
  - `DbConnectionService`: `boolean isEnabled()`, `ListResponse list(Long repoCatalogId, String viewerId, boolean isAdmin)`, `DbConnection create(UpsertRequest, String actorId, boolean isAdmin)`, `DbConnection update(Long id, UpsertRequest, String actorId, boolean isAdmin)`, `void delete(Long id, String actorId, boolean isAdmin)`, `TestResult test(TestRequest, String actorId, boolean isAdmin)`, `List<Long> validateSelection(Long repoCatalogId, String ownerId, List<Long> ids)`, `List<Chip> chipsFor(List<? extends Number> ids)`, `ClaimDb resolveForClaim(List<? extends Number> ids)`; `record ClaimDb(List<InterviewClaimResponse.DbConnectionRef> refs, List<String> notices)`; 상수 `MAX_SELECTION = 3`, `SERVER_PREFIX = "db-"`

- [ ] **Step 1: DTO + DbConnectionRef 작성** (테스트가 참조하므로 먼저)

`src/main/java/com/hamonsoft/netismaker/dto/DbConnectionDto.java`:
```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DB 접속정보 API DTO (스펙 2026-10-02 §5.1). 브라우저로 나가는 View/ListResponse/Chip에는 비밀번호·암호문 필드가 없다.
 * host/databaseName/username 패턴은 JDBC URL 파라미터 주입(`db?allowLoadLocalInfile=true`, `host/evil`)을 막는다 —
 * DbType.jdbcUrl이 문자열 연결로 URL을 만든다. 요청 레코드의 toString은 비밀번호를 뺀다.
 */
public final class DbConnectionDto {

    public static final String HOST_PATTERN = "^[A-Za-z0-9._-]+$";
    public static final String DB_NAME_PATTERN = "^[A-Za-z0-9_$#.-]+$";
    public static final String USERNAME_PATTERN = "^[A-Za-z0-9_$#.@-]+$";

    private DbConnectionDto() {}

    public record View(long id, String scope, long repoCatalogId, String name, String dbType, String host, int port,
                       String databaseName, String username, boolean enabled, boolean mine) {
        public static View of(DbConnection c, String viewerId) {
            return new View(c.getId(), c.getScope().name(), c.getRepoCatalogId(), c.getName(), c.getDbType().name(),
                    c.getHost(), c.getPort(), c.getDatabaseName(), c.getUsername(), c.isEnabled(), c.isOwnedBy(viewerId));
        }
    }

    /** enabled=false면 기능 꺼짐(키 미설정) — 프론트가 DB 버튼을 숨긴다. */
    public record ListResponse(boolean enabled, List<View> items) {}

    /** 수정(PUT)에서는 scope·repoCatalogId를 무시한다(이동 불가). password는 생성 시 필수, 수정 시 blank = 기존 유지. */
    public record UpsertRequest(
            @NotNull DbConnectionScope scope,
            @NotNull Long repoCatalogId,
            @NotBlank @Size(max = 100) String name,
            @NotNull DbType dbType,
            @NotBlank @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "host에는 영문·숫자·.-_만 쓸 수 있습니다") String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @NotBlank @Size(max = 255) @Pattern(regexp = DB_NAME_PATTERN, message = "DB/서비스명에 쓸 수 없는 문자가 있습니다") String databaseName,
            @NotBlank @Size(max = 255) @Pattern(regexp = USERNAME_PATTERN, message = "사용자명에 쓸 수 없는 문자가 있습니다") String username,
            @Size(max = 1000) String password,
            Boolean enabled
    ) {
        @Override
        public String toString() {
            return "UpsertRequest[scope=" + scope + ", repo=" + repoCatalogId + ", name=" + name + ", " + dbType
                    + " " + host + ":" + port + "/" + databaseName + " user=" + username + "]";
        }
    }

    /** id가 있으면 저장된 값에 폼 값을 덮어쓴다(password blank = 저장값). id가 없으면 모든 필드 필수(서비스 검증). */
    public record TestRequest(
            Long id,
            DbType dbType,
            @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "host에는 영문·숫자·.-_만 쓸 수 있습니다") String host,
            @Min(1) @Max(65535) Integer port,
            @Size(max = 255) @Pattern(regexp = DB_NAME_PATTERN, message = "DB/서비스명에 쓸 수 없는 문자가 있습니다") String databaseName,
            @Size(max = 255) @Pattern(regexp = USERNAME_PATTERN, message = "사용자명에 쓸 수 없는 문자가 있습니다") String username,
            @Size(max = 1000) String password
    ) {
        @Override
        public String toString() {
            return "TestRequest[id=" + id + ", " + dbType + " " + host + ":" + port + "/" + databaseName + " user=" + username + "]";
        }
    }

    public record TestResult(boolean ok, String message) {}

    /** 질문 화면 칩 — 현재 행 기준(삭제된 id는 빠진다). dbType은 enum 이름. */
    public record Chip(long id, String name, String dbType) {}
}
```

`InterviewClaimResponse.java` — 레코드 본문 안, `AttachmentRef` 레코드 아래에 추가:
```java
    /**
     * 복호화된 DB 접속정보 (스펙 2026-10-02 §5.4) — 질문 세션 claim에서만, 내부 워커 API로만 나간다.
     * ⚠️ password 평문 포함: 이 레코드(와 이를 담은 claim 응답)를 로그·예외 메시지에 넣지 말 것. toString은 가린다.
     */
    public record DbConnectionRef(String serverName, String label, String dbType, String host, int port,
                                  String database, String username, String password) {
        @Override
        public String toString() {
            return "DbConnectionRef[" + serverName + " " + dbType + " " + host + ":" + port + "/" + database
                    + " user=" + username + " password=****]";
        }
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/service/DbConnectionServiceTest.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DbConnectionServiceTest {

    private static final String SALT = "0123456789abcdef";
    private DbConnectionRepository repo;
    private RepoCatalogRepository repoCatalogRepo;
    private DbConnectionTester tester;
    private DbSecretCipher cipher;
    private DbConnectionService service;

    @BeforeEach
    void setUp() {
        repo = mock(DbConnectionRepository.class);
        repoCatalogRepo = mock(RepoCatalogRepository.class);
        tester = mock(DbConnectionTester.class);
        cipher = new DbSecretCipher("test-key", SALT);
        service = new DbConnectionService(repo, cipher, tester, repoCatalogRepo);
        when(repo.save(any())).thenAnswer(i -> {
            DbConnection c = i.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", 42L);
            return c;
        });
        when(repoCatalogRepo.existsById(1L)).thenReturn(true);
        when(tester.test(any())).thenReturn(new DbConnectionTester.Result(true, "접속 성공"));
    }

    private DbConnection row(long id, DbConnectionScope scope, String owner, long repoId, boolean enabled) {
        DbConnection c = DbConnection.create(scope, repoId, owner, "conn" + id, DbType.POSTGRESQL, "db.local", 5432,
                "app", "reader", cipher.encrypt("pw" + id), "x");
        ReflectionTestUtils.setField(c, "id", id);
        c.setEnabled(enabled);
        when(repo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private static DbConnectionDto.UpsertRequest upsert(DbConnectionScope scope, String password) {
        return new DbConnectionDto.UpsertRequest(scope, 1L, "운영 DB", DbType.MYSQL, "db.local", 3306, "app",
                "reader", password, null);
    }

    private static void assertStatus(Runnable r, HttpStatus status) {
        assertThatThrownBy(r::run).isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(status));
    }

    @Test
    void list_shows_repo_rows_and_only_my_user_rows() {
        DbConnection pub = row(1, DbConnectionScope.REPO, null, 1, true);
        DbConnection off = row(2, DbConnectionScope.REPO, null, 1, false);
        DbConnection mine = row(3, DbConnectionScope.USER, "user1", 1, true);
        DbConnection other = row(4, DbConnectionScope.USER, "user2", 1, true);
        when(repo.findByRepoCatalogIdOrderByScopeAscNameAsc(1L)).thenReturn(List.of(pub, off, mine, other));

        DbConnectionDto.ListResponse user = service.list(1L, "user1", false);
        assertThat(user.enabled()).isTrue();
        assertThat(user.items()).extracting(DbConnectionDto.View::id).containsExactly(1L, 3L);
        assertThat(user.items().get(1).mine()).isTrue();

        // 관리자는 비활성 REPO도 보지만 남의 USER는 못 본다
        assertThat(service.list(1L, "admin1", true).items()).extracting(DbConnectionDto.View::id).containsExactly(1L, 2L);
    }

    @Test
    void list_when_disabled_returns_enabled_false() {
        service = new DbConnectionService(repo, new DbSecretCipher("", ""), tester, repoCatalogRepo);
        assertThat(service.list(1L, "user1", false)).isEqualTo(new DbConnectionDto.ListResponse(false, List.of()));
        assertStatus(() -> service.create(upsert(DbConnectionScope.USER, "pw"), "user1", false), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void create_user_row_sets_owner_and_stores_only_ciphertext() {
        DbConnection c = service.create(upsert(DbConnectionScope.USER, "pl@in"), "user1", false);
        assertThat(c.getOwnerUserId()).isEqualTo("user1");
        assertThat(c.getPasswordEnc()).isNotEqualTo("pl@in");
        assertThat(cipher.tryDecrypt(c.getPasswordEnc())).contains("pl@in");
    }

    @Test
    void create_repo_row_requires_admin_and_has_no_owner() {
        assertStatus(() -> service.create(upsert(DbConnectionScope.REPO, "pw"), "user1", false), HttpStatus.FORBIDDEN);
        assertThat(service.create(upsert(DbConnectionScope.REPO, "pw"), "admin1", true).getOwnerUserId()).isNull();
    }

    @Test
    void create_rejects_blank_password_and_unknown_repo() {
        assertStatus(() -> service.create(upsert(DbConnectionScope.USER, " "), "user1", false), HttpStatus.BAD_REQUEST);
        DbConnectionDto.UpsertRequest otherRepo = new DbConnectionDto.UpsertRequest(DbConnectionScope.USER, 99L, "x",
                DbType.MYSQL, "h", 3306, "app", "u", "pw", null);
        assertStatus(() -> service.create(otherRepo, "user1", false), HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_blank_password_keeps_ciphertext_and_other_users_row_is_404() {
        DbConnection mine = row(3, DbConnectionScope.USER, "user1", 1, true);
        String before = mine.getPasswordEnc();
        service.update(3L, upsert(DbConnectionScope.USER, ""), "user1", false);
        assertThat(mine.getPasswordEnc()).isEqualTo(before);
        assertThat(mine.getDbType()).isEqualTo(DbType.MYSQL);

        row(4, DbConnectionScope.USER, "user2", 1, true);
        assertStatus(() -> service.update(4L, upsert(DbConnectionScope.USER, ""), "user1", false), HttpStatus.NOT_FOUND);
        row(1, DbConnectionScope.REPO, null, 1, true);
        assertStatus(() -> service.update(1L, upsert(DbConnectionScope.REPO, ""), "user1", false), HttpStatus.FORBIDDEN);
    }

    @Test
    void test_by_id_requires_edit_permission() {
        // Review Focus 1: 남의 공용 접속정보 id + 자기 host로 저장 비밀번호를 빼내는 경로
        row(1, DbConnectionScope.REPO, null, 1, true);
        DbConnectionDto.TestRequest req = new DbConnectionDto.TestRequest(1L, null, "attacker.example", null, null, null, null);
        assertStatus(() -> service.test(req, "user1", false), HttpStatus.FORBIDDEN);
        verify(tester, never()).test(any());
    }

    @Test
    void test_by_id_merges_form_values_with_stored_password() {
        row(3, DbConnectionScope.USER, "user1", 1, true);
        service.test(new DbConnectionDto.TestRequest(3L, null, "new-host", 6543, null, null, ""), "user1", false);
        ArgumentCaptor<DbConnectionTester.Target> cap = ArgumentCaptor.forClass(DbConnectionTester.Target.class);
        verify(tester).test(cap.capture());
        assertThat(cap.getValue()).isEqualTo(new DbConnectionTester.Target(DbType.POSTGRESQL, "new-host", 6543,
                "app", "reader", "pw3"));
    }

    @Test
    void test_unsaved_requires_all_fields() {
        assertStatus(() -> service.test(new DbConnectionDto.TestRequest(null, DbType.MYSQL, "h", 3306, "app", "u", null),
                "user1", false), HttpStatus.BAD_REQUEST);
        assertThat(service.test(new DbConnectionDto.TestRequest(null, DbType.MYSQL, "h", 3306, "app", "u", "pw"),
                "user1", false).ok()).isTrue();
    }

    @Test
    void validate_selection_rules() {
        row(1, DbConnectionScope.REPO, null, 1, true);
        row(3, DbConnectionScope.USER, "user1", 1, true);
        row(4, DbConnectionScope.USER, "user2", 1, true);
        row(5, DbConnectionScope.REPO, null, 2, true);
        row(6, DbConnectionScope.REPO, null, 1, false);
        row(7, DbConnectionScope.USER, "user1", 1, true);
        row(8, DbConnectionScope.USER, "user1", 1, true);
        when(repo.findAllById(any())).thenAnswer(i -> {
            List<DbConnection> out = new java.util.ArrayList<>();
            for (Long id : (Iterable<Long>) i.getArgument(0)) repo.findById(id).ifPresent(out::add);
            return out;
        });

        assertThat(service.validateSelection(1L, "user1", null)).isEmpty();
        assertThat(service.validateSelection(1L, "user1", List.of(3L, 1L, 3L))).containsExactly(3L, 1L);
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(4L)), HttpStatus.BAD_REQUEST); // 남의 USER
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(5L)), HttpStatus.BAD_REQUEST); // 다른 레포
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(6L)), HttpStatus.BAD_REQUEST); // 비활성
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(99L)), HttpStatus.BAD_REQUEST); // 없음
        assertStatus(() -> service.validateSelection(1L, "user1", List.of(1L, 3L, 7L, 8L)), HttpStatus.BAD_REQUEST); // 상한 3
    }

    @Test
    void chips_keep_request_order_and_skip_missing() {
        row(1, DbConnectionScope.REPO, null, 1, true);
        row(3, DbConnectionScope.USER, "user1", 1, true);
        when(repo.findAllById(any())).thenAnswer(i -> List.of(repo.findById(1L).orElseThrow(), repo.findById(3L).orElseThrow()));
        assertThat(service.chipsFor(List.of(3, 99, 1)))
                .containsExactly(new DbConnectionDto.Chip(3, "conn3", "POSTGRESQL"), new DbConnectionDto.Chip(1, "conn1", "POSTGRESQL"));
    }

    @Test
    void resolve_for_claim_decrypts_and_reports_unusable_rows() {
        row(1, DbConnectionScope.REPO, null, 1, true);
        DbConnection broken = row(2, DbConnectionScope.REPO, null, 1, true);
        broken.setPasswordEnc(new DbSecretCipher("other-key", SALT).encrypt("x"));
        row(6, DbConnectionScope.REPO, null, 1, false);

        DbConnectionService.ClaimDb db = service.resolveForClaim(List.of(1L, 2L, 6L, 99L));
        assertThat(db.refs()).hasSize(1);
        var ref = db.refs().get(0);
        assertThat(ref.serverName()).isEqualTo("db-1");
        assertThat(ref.label()).isEqualTo("conn1 (PostgreSQL)");
        assertThat(ref.password()).isEqualTo("pw1");
        assertThat(db.notices()).hasSize(3);
        assertThat(db.notices().get(0)).contains("conn2").contains("다시 저장");
        assertThat(String.join("\n", db.notices())).doesNotContain("pw");
    }

    @Test
    void resolve_for_claim_when_disabled_gives_single_notice() {
        service = new DbConnectionService(repo, new DbSecretCipher("", ""), tester, repoCatalogRepo);
        DbConnectionService.ClaimDb db = service.resolveForClaim(List.of(1L));
        assertThat(db.refs()).isEmpty();
        assertThat(db.notices()).containsExactly("DB 접속정보 기능이 꺼져 있어 DB 도구를 붙이지 않았습니다");
        assertThat(service.resolveForClaim(List.of()).notices()).isEmpty();
    }
}
```
`TaskException`에 `getStatus()`가 없으면 Step 3 전에 `src/main/java/com/hamonsoft/netismaker/service/TaskException.java`를 열어 상태 접근자 이름을 확인하고 `assertStatus`를 그 이름으로 바꾼다.

- [ ] **Step 3: 실패 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.DbConnectionServiceTest
```
Expected: 컴파일 실패(`DbConnectionService` 없음).

- [ ] **Step 4: 구현**

`src/main/java/com/hamonsoft/netismaker/service/DbConnectionService.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DB 접속정보 (스펙 2026-10-02 §5). 보이는 범위: REPO = 그 레포를 고르는 모든 사용자(비활성은 관리자만),
 * USER = 본인만(관리자도 남의 USER는 못 본다 — 존재 노출 방지로 404). 편집: REPO = 관리자, USER = 본인.
 * 저장된 비밀번호를 쓰는 접속 테스트(id 지정)는 편집 권한자만 — 다른 host로 저장 비밀번호를 보내는 경로 차단.
 */
@Service
@Profile("api")
public class DbConnectionService {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionService.class);
    public static final int MAX_SELECTION = 3;
    public static final String SERVER_PREFIX = "db-";

    private final DbConnectionRepository repo;
    private final DbSecretCipher cipher;
    private final DbConnectionTester tester;
    private final RepoCatalogRepository repoCatalogRepo;

    public DbConnectionService(DbConnectionRepository repo, DbSecretCipher cipher, DbConnectionTester tester,
                               RepoCatalogRepository repoCatalogRepo) {
        this.repo = repo;
        this.cipher = cipher;
        this.tester = tester;
        this.repoCatalogRepo = repoCatalogRepo;
    }

    /** claim 보강 결과 — refs는 복호화된 접속정보(내부 API 전용), notices는 프롬프트에 붙일 안내. */
    public record ClaimDb(List<InterviewClaimResponse.DbConnectionRef> refs, List<String> notices) {}

    public boolean isEnabled() {
        return cipher.isEnabled();
    }

    @Transactional(readOnly = true)
    public DbConnectionDto.ListResponse list(Long repoCatalogId, String viewerId, boolean isAdmin) {
        if (!cipher.isEnabled()) return new DbConnectionDto.ListResponse(false, List.of());
        List<DbConnectionDto.View> items = repo.findByRepoCatalogIdOrderByScopeAscNameAsc(repoCatalogId).stream()
                .filter(c -> canView(c, viewerId, isAdmin))
                .map(c -> DbConnectionDto.View.of(c, viewerId))
                .toList();
        return new DbConnectionDto.ListResponse(true, items);
    }

    @Transactional
    public DbConnection create(DbConnectionDto.UpsertRequest req, String actorId, boolean isAdmin) {
        requireEnabled();
        if (req.scope() == DbConnectionScope.REPO && !isAdmin) throw TaskException.forbidden();
        if (!repoCatalogRepo.existsById(req.repoCatalogId())) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "존재하지 않는 레포 카탈로그 id: " + req.repoCatalogId());
        }
        if (isBlank(req.password())) throw new TaskException(HttpStatus.BAD_REQUEST, "비밀번호를 입력하세요");
        DbConnection c = DbConnection.create(req.scope(), req.repoCatalogId(),
                req.scope() == DbConnectionScope.USER ? actorId : null,
                req.name().strip(), req.dbType(), req.host().strip(), req.port(), req.databaseName().strip(),
                req.username().strip(), cipher.encrypt(req.password()), actorId);
        if (req.enabled() != null) c.setEnabled(req.enabled());
        return repo.save(c);
    }

    @Transactional
    public DbConnection update(Long id, DbConnectionDto.UpsertRequest req, String actorId, boolean isAdmin) {
        requireEnabled();
        DbConnection c = requireEditable(id, actorId, isAdmin);
        c.setName(req.name().strip());
        c.setDbType(req.dbType());
        c.setHost(req.host().strip());
        c.setPort(req.port());
        c.setDatabaseName(req.databaseName().strip());
        c.setUsername(req.username().strip());
        if (!isBlank(req.password())) c.setPasswordEnc(cipher.encrypt(req.password()));
        if (req.enabled() != null) c.setEnabled(req.enabled());
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }

    @Transactional
    public void delete(Long id, String actorId, boolean isAdmin) {
        requireEnabled();
        repo.delete(requireEditable(id, actorId, isAdmin));
    }

    @Transactional(readOnly = true)
    public DbConnectionDto.TestResult test(DbConnectionDto.TestRequest req, String actorId, boolean isAdmin) {
        requireEnabled();
        DbConnectionTester.Target target;
        if (req.id() != null) {
            DbConnection c = requireEditable(req.id(), actorId, isAdmin);
            String pw = isBlank(req.password())
                    ? cipher.tryDecrypt(c.getPasswordEnc()).orElseThrow(() -> new TaskException(HttpStatus.BAD_REQUEST,
                            "저장된 비밀번호를 복호화할 수 없습니다 — 비밀번호를 다시 입력하세요"))
                    : req.password();
            target = new DbConnectionTester.Target(
                    req.dbType() != null ? req.dbType() : c.getDbType(),
                    isBlank(req.host()) ? c.getHost() : req.host().strip(),
                    req.port() != null ? req.port() : c.getPort(),
                    isBlank(req.databaseName()) ? c.getDatabaseName() : req.databaseName().strip(),
                    isBlank(req.username()) ? c.getUsername() : req.username().strip(),
                    pw);
        } else {
            if (req.dbType() == null || isBlank(req.host()) || req.port() == null || isBlank(req.databaseName())
                    || isBlank(req.username()) || isBlank(req.password())) {
                throw new TaskException(HttpStatus.BAD_REQUEST, "접속 테스트에 필요한 값이 비어 있습니다");
            }
            target = new DbConnectionTester.Target(req.dbType(), req.host().strip(), req.port(),
                    req.databaseName().strip(), req.username().strip(), req.password());
        }
        log.info("DB 접속 테스트 요청자={} 대상={}", actorId, target);   // Target.toString은 비밀번호 제외
        DbConnectionTester.Result r = tester.test(target);
        return new DbConnectionDto.TestResult(r.ok(), r.message());
    }

    /** 질문 등록/추가 질문의 선택 검증 — 기준은 세션 소유자(ownerId). 중복 제거, 순서 유지. 위반 시 400. */
    @Transactional(readOnly = true)
    public List<Long> validateSelection(Long repoCatalogId, String ownerId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        requireEnabled();
        List<Long> distinct = ids.stream().filter(Objects::nonNull).map(Number::longValue).distinct().toList();
        if (distinct.size() > MAX_SELECTION) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "DB 연결은 최대 " + MAX_SELECTION + "개까지 선택할 수 있습니다");
        }
        Map<Long, DbConnection> found = byId(distinct);
        for (Long id : distinct) {
            DbConnection c = found.get(id);
            boolean ok = c != null && Objects.equals(c.getRepoCatalogId(), repoCatalogId) && c.isEnabled()
                    && (c.getScope() == DbConnectionScope.REPO || c.isOwnedBy(ownerId));
            if (!ok) throw new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: " + id);
        }
        return new ArrayList<>(distinct);
    }

    @Transactional(readOnly = true)
    public List<DbConnectionDto.Chip> chipsFor(List<? extends Number> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Long> longs = ids.stream().filter(Objects::nonNull).map(Number::longValue).toList();
        Map<Long, DbConnection> found = byId(longs);
        List<DbConnectionDto.Chip> out = new ArrayList<>();
        for (Long id : longs) {
            DbConnection c = found.get(id);
            if (c != null) out.add(new DbConnectionDto.Chip(id, c.getName(), c.getDbType().name()));
        }
        return out;
    }

    /** claim 보강 (스펙 §5.4). 사용할 수 없는 항목은 빼고 안내 문구만 남긴다 — 예외를 던지지 않는다. */
    @Transactional(readOnly = true)
    public ClaimDb resolveForClaim(List<? extends Number> ids) {
        if (ids == null || ids.isEmpty()) return new ClaimDb(List.of(), List.of());
        if (!cipher.isEnabled()) {
            return new ClaimDb(List.of(), List.of("DB 접속정보 기능이 꺼져 있어 DB 도구를 붙이지 않았습니다"));
        }
        List<InterviewClaimResponse.DbConnectionRef> refs = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        for (Number n : ids) {
            if (n == null) continue;
            long id = n.longValue();
            Optional<DbConnection> found = repo.findById(id);
            if (found.isEmpty() || !found.get().isEnabled()) {
                notices.add("DB 연결 #" + id + "를 사용할 수 없습니다 — 삭제되었거나 비활성화됨");
                continue;
            }
            DbConnection c = found.get();
            Optional<String> pw = cipher.tryDecrypt(c.getPasswordEnc());
            if (pw.isEmpty()) {
                log.warn("DB 접속정보 복호화 실패 id={}", id);
                notices.add("DB 연결 '" + c.getName() + "'를 복호화할 수 없습니다 — 접속정보를 다시 저장해야 합니다");
                continue;
            }
            refs.add(new InterviewClaimResponse.DbConnectionRef(SERVER_PREFIX + id,
                    c.getName() + " (" + c.getDbType().getLabel() + ")", c.getDbType().name(),
                    c.getHost(), c.getPort(), c.getDatabaseName(), c.getUsername(), pw.get()));
        }
        return new ClaimDb(refs, notices);
    }

    static boolean canView(DbConnection c, String viewerId, boolean isAdmin) {
        if (c.getScope() == DbConnectionScope.REPO) return c.isEnabled() || isAdmin;
        return c.isOwnedBy(viewerId);
    }

    static boolean canEdit(DbConnection c, String viewerId, boolean isAdmin) {
        return c.getScope() == DbConnectionScope.REPO ? isAdmin : c.isOwnedBy(viewerId);
    }

    private DbConnection requireEditable(Long id, String actorId, boolean isAdmin) {
        DbConnection c = repo.findById(id).filter(x -> canView(x, actorId, isAdmin))
                .orElseThrow(TaskException::notFound);
        if (!canEdit(c, actorId, isAdmin)) throw TaskException.forbidden();
        return c;
    }

    private Map<Long, DbConnection> byId(List<Long> ids) {
        Map<Long, DbConnection> m = new HashMap<>();
        for (DbConnection c : repo.findAllById(ids)) m.put(c.getId(), c);
        return m;
    }

    private void requireEnabled() {
        if (!cipher.isEnabled()) {
            throw new TaskException(HttpStatus.SERVICE_UNAVAILABLE, "DB 접속정보 기능이 꺼져 있습니다(NETISMAKER_DB_SECRET_KEY)");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 5: 통과 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.DbConnectionServiceTest
```
Expected: 12건 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/DbConnectionDto.java src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java src/main/java/com/hamonsoft/netismaker/service/DbConnectionService.java src/test/java/com/hamonsoft/netismaker/service/DbConnectionServiceTest.java
git commit -m "feat(db-mcp): DbConnectionService — 권한·선택 검증·claim 해석 + DTO

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: DbConnectionController + 검증·직렬화 테스트

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/controller/DbConnectionController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/dto/DbConnectionDtoValidationTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/DbConnectionApiIntegrationTest.java`

**Interfaces:**
- Consumes: `DbConnectionService`, `DbConnectionDto`(Task 5), `AuthContext.requireUserId/isAdmin`
- Produces: `GET /api/db-connections?repoCatalogId=` → `ListResponse`; `POST /api/db-connections` → 201 `View`; `PUT /api/db-connections/{id}` → `View`; `DELETE /api/db-connections/{id}` → 204; `POST /api/db-connections/test` → `TestResult`

- [ ] **Step 1: 실패하는 검증 테스트 작성**

`src/test/java/com/hamonsoft/netismaker/dto/DbConnectionDtoValidationTest.java`:
```java
package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbConnectionDtoValidationTest {

    private final Validator v = Validation.buildDefaultValidatorFactory().getValidator();

    private static DbConnectionDto.UpsertRequest req(String host, String db, String user) {
        return new DbConnectionDto.UpsertRequest(DbConnectionScope.USER, 1L, "운영", DbType.MYSQL, host, 3306, db, user, "pw", null);
    }

    @Test
    void accepts_ordinary_values() {
        assertThat(v.validate(req("db-01.corp.local", "app_db", "reader@corp"))).isEmpty();
        assertThat(v.validate(req("10.1.3.2", "ORCLPDB1.world", "C##READER"))).isEmpty();
    }

    @Test
    void rejects_jdbc_url_parameter_injection() {
        // Review Focus 2
        for (String[] bad : new String[][] {
                {"db.local", "app?allowLoadLocalInfile=true", "u"},
                {"db.local/evil", "app", "u"},
                {"db.local:1", "app", "u"},
                {"db.local", "app&x=1", "u"},
                {"db.local", "app", "u;drop"},
                {"db local", "app", "u"},
        }) {
            assertThat(v.validate(req(bad[0], bad[1], bad[2]))).as(String.join(" | ", bad)).isNotEmpty();
        }
        assertThat(v.validate(new DbConnectionDto.TestRequest(null, DbType.MYSQL, "h/evil", 3306, "app", "u", "pw"))).isNotEmpty();
    }

    @Test
    void to_string_and_json_never_carry_password() throws Exception {
        assertThat(req("h", "app", "u").toString()).doesNotContain("pw");
        assertThat(new DbConnectionDto.TestRequest(1L, null, null, null, null, null, "s3cret").toString()).doesNotContain("s3cret");

        DbConnection c = DbConnection.create(DbConnectionScope.USER, 1L, "user1", "운영", DbType.POSTGRESQL, "h", 5432,
                "app", "u", "CIPHER-xyz", "user1");
        ReflectionTestUtils.setField(c, "id", 7L);
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(new DbConnectionDto.ListResponse(true, List.of(DbConnectionDto.View.of(c, "user1"))));
        assertThat(json).doesNotContain("password").doesNotContain("CIPHER").contains("\"mine\":true");
    }
}
```

- [ ] **Step 2: 실패 확인 → 통과 확인** — Task 5의 DTO가 이미 있으므로 바로 통과해야 한다(패턴이 빠져 있으면 실패).

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.dto.DbConnectionDtoValidationTest
```
Expected: 3건 PASS. `rejects_jdbc_url_parameter_injection`이 실패하면 `DbConnectionDto`의 패턴 상수를 고친다.

- [ ] **Step 3: 컨트롤러 작성**

`src/main/java/com/hamonsoft/netismaker/controller/DbConnectionController.java`:
```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.service.DbConnectionService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/**
 * DB 접속정보 API (스펙 2026-10-02 §5.1). JWT 인증만 — 권한(REPO=관리자, USER=본인)은 DbConnectionService가 가른다.
 *
 *   GET    /api/db-connections?repoCatalogId=   — 그 레포의 공용 + 내 접속정보 ({enabled, items})
 *   POST   /api/db-connections                  — 생성 (scope=REPO는 관리자)
 *   PUT    /api/db-connections/{id}             — 수정 (password blank = 유지)
 *   DELETE /api/db-connections/{id}             — 삭제
 *   POST   /api/db-connections/test             — 접속 테스트 (id 지정 시 편집 권한 필요)
 */
@RestController
@RequestMapping("/api/db-connections")
@Profile("api")
public class DbConnectionController {

    private final DbConnectionService service;

    public DbConnectionController(DbConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public DbConnectionDto.ListResponse list(@RequestParam Long repoCatalogId, JwtAuthenticationToken auth) {
        return service.list(repoCatalogId, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DbConnectionDto.View create(@RequestBody @Valid DbConnectionDto.UpsertRequest req, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        return DbConnectionDto.View.of(service.create(req, userId, AuthContext.isAdmin(auth)), userId);
    }

    @PutMapping("/{id}")
    public DbConnectionDto.View update(@PathVariable Long id, @RequestBody @Valid DbConnectionDto.UpsertRequest req,
                                       JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        return DbConnectionDto.View.of(service.update(id, req, userId, AuthContext.isAdmin(auth)), userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, JwtAuthenticationToken auth) {
        service.delete(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @PostMapping("/test")
    public DbConnectionDto.TestResult test(@RequestBody @Valid DbConnectionDto.TestRequest req, JwtAuthenticationToken auth) {
        return service.test(req, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }
}
```

- [ ] **Step 4: 통합 테스트 작성** (CI 전용)

`src/test/java/com/hamonsoft/netismaker/controller/DbConnectionApiIntegrationTest.java`:
```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DB 접속정보 API 표면 (스펙 2026-10-02 §5.1·§10). 레포 카탈로그 id 1 = V14 시드 'Netis7.0'. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest(properties = {"app.db-secret.key=it-key", "app.db-secret.salt=0123456789abcdef"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class DbConnectionApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private DbConnectionRepository repo;

    private static final String USER_BODY = "{\"scope\":\"USER\",\"repoCatalogId\":1,\"name\":\"내 DB\",\"dbType\":\"POSTGRESQL\","
            + "\"host\":\"127.0.0.1\",\"port\":1,\"databaseName\":\"app\",\"username\":\"reader\",\"password\":\"Pw-123456\"}";

    @BeforeEach void clean() { repo.deleteAll(); }

    private static RequestPostProcessor user(String id) {
        return jwt().jwt(b -> b.claim("username", id).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin1").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void user_creates_own_row_and_password_never_comes_back() throws Exception {
        mvc.perform(post("/api/db-connections").with(user("user1")).contentType(APPLICATION_JSON).content(USER_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().string(not(containsString("Pw-123456"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(jsonPath("$.mine").value(true));
        mvc.perform(get("/api/db-connections").param("repoCatalogId", "1").with(user("user1")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/api/db-connections").param("repoCatalogId", "1").with(user("user2")))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void repo_scope_needs_admin() throws Exception {
        String body = USER_BODY.replace("\"USER\"", "\"REPO\"");
        mvc.perform(post("/api/db-connections").with(user("user1")).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/db-connections").with(admin()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/db-connections").param("repoCatalogId", "1").with(user("user2")))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void test_endpoint_reports_failure_without_password() throws Exception {
        mvc.perform(post("/api/db-connections/test").with(user("user1")).contentType(APPLICATION_JSON)
                        .content("{\"dbType\":\"POSTGRESQL\",\"host\":\"127.0.0.1\",\"port\":1,\"databaseName\":\"app\","
                                + "\"username\":\"reader\",\"password\":\"Pw-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(content().string(not(containsString("Pw-123456"))));
    }

    @Test
    void injection_in_host_is_400() throws Exception {
        mvc.perform(post("/api/db-connections").with(user("user1")).contentType(APPLICATION_JSON)
                        .content(USER_BODY.replace("127.0.0.1", "127.0.0.1/evil")))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 5: 컴파일·단위 테스트 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.dto.DbConnectionDtoValidationTest --tests com.hamonsoft.netismaker.controller.DbConnectionApiIntegrationTest
```
Expected: Validation 3건 PASS, 통합 테스트 SKIPPED, `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/DbConnectionController.java src/test/java/com/hamonsoft/netismaker/dto/DbConnectionDtoValidationTest.java src/test/java/com/hamonsoft/netismaker/controller/DbConnectionApiIntegrationTest.java
git commit -m "feat(db-mcp): /api/db-connections CRUD·접속 테스트 API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 질문 등록·추가 질문·상세에 DB 선택 연결

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/QuestionCreateRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/QuestionAskRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/QuestionService.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/QuestionController.java` (`doAsk`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/QuestionServiceTest.java`, `src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java`

**Interfaces:**
- Consumes: `DbConnectionService.validateSelection`, `chipsFor`, `DbConnectionDto.Chip`(Task 5), `InterviewSession.get/setDbConnectionIds`(Task 2)
- Produces:
  - `QuestionCreateRequest(... List<Long> mcpCatalogIds, List<Long> dbConnectionIds)` + 기존 7-인자 생성자 유지
  - `QuestionAskRequest(String answer, Integer replyToSeq, String model, String effort, List<Long> mcpCatalogIds, List<Long> dbConnectionIds)` + 기존 4·5-인자 생성자 유지
  - `QuestionService.AskResult(InterviewSession session, InterviewTurn mcpNote, InterviewTurn dbNote)` + 2-인자 생성자 유지
  - `InterviewResponse` 끝에 `Long repoCatalogId, List<Long> dbConnectionIds, List<DbConnectionDto.Chip> dbConnections` + `InterviewResponse withDbConnections(List<DbConnectionDto.Chip> chips)`

- [ ] **Step 1: 생성자 호출처 확인**

```bash
grep -rn "new InterviewResponse(\|new QuestionService(\|new QuestionService.AskResult(\|new AskResult(" src | grep -v "^src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java"
```
Expected: `QuestionServiceTest`의 `new QuestionService(` 1곳, `QuestionService` 안 `new AskResult(` 1곳. 그 외 `new InterviewResponse(`가 나오면 Step 4에서 같이 고친다.

- [ ] **Step 2: 실패하는 테스트 작성**

`QuestionServiceTest.java` — 필드·`setUp` 수정:
```java
    private DbConnectionService dbConnections;
```
`setUp()`에서 `service = new QuestionService(...)` 줄을 다음으로 교체:
```java
        dbConnections = mock(DbConnectionService.class);
        when(dbConnections.validateSelection(any(), any(), any())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(2);
            return ids == null ? new ArrayList<Long>() : new ArrayList<>(ids);
        });
        service = new QuestionService(interviewService, sessionRepo, turnRepo, repoCatalog, mcpCatalog,
                attachmentStorage, tika, attachmentRepo, dbConnections, 3, 10);
```
파일 끝(마지막 `}` 앞)에 테스트 추가:
```java
    // ── DB 연결 (스펙 2026-10-02 §5.3) ────────────────────────────────

    private static QuestionCreateRequest reqWithDb(List<Long> dbIds) {
        return new QuestionCreateRequest(1L, "dev", "t", "q", null, null, null, dbIds);
    }

    private InterviewSession askableSession() {
        InterviewSession s = InterviewSession.createQuestion("micthebick84/netis7.0", "main", "t", "q", "user1",
                new ArrayList<>(), "claude-sonnet-5", "medium");
        ReflectionTestUtils.setField(s, "id", 5L);
        s.setRepoCatalogId(1L);
        s.setDbConnectionIds(new ArrayList<>(List.of(7L)));
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(1L);
        when(interviewService.submitAnswer(eq(5L), anyString(), anyBoolean(), any())).thenReturn(answered(s, 4));
        when(interviewService.appendSystemNote(eq(5L), anyString()))
                .thenAnswer(i -> InterviewTurn.of(5L, 9, "system", "note", i.getArgument(1), null));
        return s;
    }

    @Test
    void create_saves_db_ids_validated_against_requester_and_repo() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(reqWithDb(List.of(7L, 8L)), "user1");
        verify(dbConnections).validateSelection(1L, "user1", List.of(7L, 8L));
        assertThat(s.getDbConnectionIds()).containsExactly(7L, 8L);
    }

    @Test
    void create_db_validation_failure_creates_no_session() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        when(dbConnections.validateSelection(any(), any(), any()))
                .thenThrow(new TaskException(HttpStatus.BAD_REQUEST, "선택할 수 없는 DB 연결입니다: 9"));
        assertThatThrownBy(() -> service.create(reqWithDb(List.of(9L)), "user1")).isInstanceOf(TaskException.class);
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void ask_same_db_set_is_noop_without_validation() {
        InterviewSession s = askableSession();
        QuestionService.AskResult r = service.ask(5L, "user1", false,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(7L)), null);
        assertThat(r.dbNote()).isNull();
        verify(dbConnections, never()).validateSelection(any(), any(), any());
        assertThat(s.getDbConnectionIds()).containsExactly(7L);
    }

    @Test
    void ask_changed_db_set_validates_against_session_owner_and_appends_note() {
        InterviewSession s = askableSession();
        when(dbConnections.chipsFor(List.of(8L))).thenReturn(List.of(new DbConnectionDto.Chip(8, "분석 DB", "ORACLE")));
        QuestionService.AskResult r = service.ask(5L, "admin1", true,
                new QuestionAskRequest("추가", 3, null, null, null, List.of(8L)), null);
        verify(dbConnections).validateSelection(1L, "user1", List.of(8L));   // actor(admin1)가 아니라 소유자(user1)
        assertThat(s.getDbConnectionIds()).containsExactly(8L);
        assertThat(r.dbNote().getContent()).isEqualTo("DB 연결 변경: 분석 DB");
    }

    @Test
    void ask_empty_db_list_clears_with_note_and_null_keeps() {
        InterviewSession s = askableSession();
        QuestionService.AskResult keep = service.ask(5L, "user1", false,
                new QuestionAskRequest("a", 3, null, null, null, null), null);
        assertThat(keep.dbNote()).isNull();
        QuestionService.AskResult clear = service.ask(5L, "user1", false,
                new QuestionAskRequest("b", 3, null, null, null, List.of()), null);
        assertThat(s.getDbConnectionIds()).isEmpty();
        assertThat(clear.dbNote().getContent()).isEqualTo("DB 연결 변경: 없음(전부 해제)");
    }
```
상단 import에 추가: `import com.hamonsoft.netismaker.dto.DbConnectionDto;`

`InterviewResponseTest.java` 끝에 추가(기존 테스트가 세션을 만드는 방식을 그대로 따른다 — 파일 상단 헬퍼가 있으면 그것을 쓰고, 없으면 아래처럼):
```java
    @Test
    void carries_repo_catalog_id_and_db_ids_and_chips_are_filled_by_with() {
        InterviewSession s = InterviewSession.createQuestion("a/b", "main", "t", "q", "user1",
                new java.util.ArrayList<>(), "claude-sonnet-5", "medium");
        s.setRepoCatalogId(1L);
        s.setDbConnectionIds(new java.util.ArrayList<>(java.util.List.of(7L)));
        InterviewResponse r = InterviewResponse.of(s, java.util.List.of(), null);
        assertThat(r.repoCatalogId()).isEqualTo(1L);
        assertThat(r.dbConnectionIds()).containsExactly(7L);
        assertThat(r.dbConnections()).isEmpty();
        InterviewResponse filled = r.withDbConnections(java.util.List.of(new DbConnectionDto.Chip(7, "운영", "MYSQL")));
        assertThat(filled.dbConnections()).extracting(DbConnectionDto.Chip::name).containsExactly("운영");
        assertThat(filled.id()).isEqualTo(r.id());
    }
```
`InterviewSession.create*`가 `status`/`createdAt` 없이 `of`에서 NPE를 내면, 이 파일의 기존 테스트가 세션을 만드는 헬퍼를 그대로 재사용한다.

- [ ] **Step 3: 실패 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.QuestionServiceTest --tests com.hamonsoft.netismaker.dto.InterviewResponseTest
```
Expected: 컴파일 실패(생성자·필드 없음).

- [ ] **Step 4: 구현**

`QuestionCreateRequest.java` — 마지막 컴포넌트 `List<Long> mcpCatalogIds` 뒤에 추가하고 호환 생성자:
```java
        List<Long> mcpCatalogIds,
        /** 선택한 DB 접속정보 id (스펙 2026-10-02 §5.3). null/빈 = 없음. 세션 소유자 기준 검증. */
        List<Long> dbConnectionIds
) {
    /** DB 선택 없는 등록 — 기존 호출처/테스트 호환. */
    public QuestionCreateRequest(Long repoCatalogId, String githubBranch, String title, String question,
                                 String model, String effort, List<Long> mcpCatalogIds) {
        this(repoCatalogId, githubBranch, title, question, model, effort, mcpCatalogIds, null);
    }
}
```
(기존 `) {}`를 위 블록으로 바꾼다.)

`QuestionAskRequest.java` — 컴포넌트와 생성자:
```java
public record QuestionAskRequest(
        @NotBlank String answer,
        Integer replyToSeq,
        String model,
        String effort,
        List<Long> mcpCatalogIds,
        /** 대화 중 DB 연결 변경 (스펙 2026-10-02 §5.3) — mcpCatalogIds와 같은 규칙: null = 유지, 빈 리스트 = 전부 해제. */
        List<Long> dbConnectionIds
) {
    /** MCP 변경 없이(유지) 답변만 — 기존 호출처/테스트 호환. */
    public QuestionAskRequest(String answer, Integer replyToSeq, String model, String effort) {
        this(answer, replyToSeq, model, effort, null, null);
    }

    /** DB 변경 없이 — 기존 호출처/테스트 호환. */
    public QuestionAskRequest(String answer, Integer replyToSeq, String model, String effort, List<Long> mcpCatalogIds) {
        this(answer, replyToSeq, model, effort, mcpCatalogIds, null);
    }

    /** 상태 전이(InterviewService.submitAnswer)에는 답변 부분만 넘긴다. */
    public AnswerRequest toAnswerRequest() {
        return new AnswerRequest(answer, replyToSeq);
    }
}
```
클래스 Javadoc 끝에 한 줄 추가: `* dbConnectionIds(스펙 2026-10-02 §5.3): mcpCatalogIds와 같은 null/빈 리스트 규칙.`

`InterviewResponse.java`:
- import 추가 없음(같은 패키지 `DbConnectionDto`).
- 레코드 컴포넌트 끝(`List<AttachmentView> attachments` 뒤)에:
```java
        List<AttachmentView> attachments,
        /** 세션의 레포 카탈로그 id — 대화 화면이 DB 접속정보 목록을 조회하는 키 (스펙 2026-10-02 §5.3). 구버전 세션은 null. */
        Long repoCatalogId,
        /** 선택한 DB 접속정보 id. 항상 non-null. */
        List<Long> dbConnectionIds,
        /** 칩 표시용 {id,name,dbType} — 현재 행 기준. QuestionService.get이 withDbConnections로 채운다. 항상 non-null. */
        List<DbConnectionDto.Chip> dbConnections
```
- compact 생성자에 추가:
```java
        dbConnectionIds = dbConnectionIds == null ? List.of() : ((List<?>) dbConnectionIds).stream()
                .map(o -> ((Number) o).longValue()).toList();
        dbConnections = dbConnections == null ? List.of() : List.copyOf(dbConnections);
```
(jsonb 역직렬화가 `List<Long>` 안에 `Integer`를 담아 오므로 `Number`로 정규화한다 — `QuestionService.idSet` 주석과 같은 이유. ⚠️ `dbConnectionIds.stream().map(n -> …)`처럼 `Long` 타입 람다로 받으면 람다 진입 시 `Long` 캐스트에서 `ClassCastException`이 나므로 반드시 `List<?>`로 받아 `Object`에서 캐스트한다.)
- `of(...)` 5-인자 본문의 `return new InterviewResponse(` 마지막 인자 `s.getMcpCatalogIds(), attachments);`를 다음으로:
```java
                s.getMcpCatalogIds(), attachments,
                s.getRepoCatalogId(), s.getDbConnectionIds(), List.of());
```
- 레코드 안에 메서드 추가:
```java
    /** 칩만 바꾼 사본 (QuestionService.get이 DbConnectionService.chipsFor 결과로 채운다). */
    public InterviewResponse withDbConnections(List<DbConnectionDto.Chip> chips) {
        return new InterviewResponse(id, githubRepo, githubBranch, title, description, status, statusName, currentPhase,
                workDir, taskId, model, effort, turns, plan, createdAt, updatedAt, kind, totalCostUsd, contextTokens,
                contextWindow, mcpCatalogIds, attachments, repoCatalogId, dbConnectionIds, chips);
    }
```

`QuestionService.java`:
- import 추가: `import com.hamonsoft.netismaker.dto.DbConnectionDto;`
- 필드·생성자: `private final DbConnectionService dbConnectionService;`를 `attachmentRepo` 아래에, 생성자 파라미터 `QuestionAttachmentRepository attachmentRepo,` 다음 줄에 `DbConnectionService dbConnectionService,` 추가, 본문에 `this.dbConnectionService = dbConnectionService;`.
- `AskResult` 교체:
```java
    /**
     * ask 결과 (스펙 2026-09-13 §5.2, 2026-10-02 §5.3). mcpNote/dbNote = MCP·DB 선택이 실제로 바뀌었을 때의
     * system note 턴, 아니면 null.
     */
    public record AskResult(InterviewSession session, InterviewTurn mcpNote, InterviewTurn dbNote) {
        public AskResult(InterviewSession session, InterviewTurn mcpNote) {
            this(session, mcpNote, null);
        }
    }
```
- `create(QuestionCreateRequest, String)`에서 `ModelEffortPolicy.validate(model, effort);` 바로 아래에:
```java
        // DB 선택 검증도 세션 생성 전에 (스펙 2026-10-02 §5.3) — 기준은 등록자(=세션 소유자)와 선택 레포
        List<Long> dbIds = dbConnectionService.validateSelection(repo.catalogId(), requesterId, req.dbConnectionIds());
```
  그리고 `s.setMcpCatalogIds(...)` 줄 아래에 `s.setDbConnectionIds(dbIds);`
- `ask(...)` 5-인자에서 `InterviewTurn note = applyMcpChange(s, req.mcpCatalogIds());` 아래에 `InterviewTurn dbNote = applyDbChange(s, req.dbConnectionIds());`, 반환을 `return new AskResult(s, note, dbNote);`로. Javadoc 순서 문구의 `→ applyMcpChange` 뒤에 `→ applyDbChange` 추가.
- `applyMcpChange` 아래에 메서드 추가:
```java
    /**
     * 대화 중 DB 연결 변경 (스펙 2026-10-02 §5.3) — applyMcpChange와 같은 규칙. ids == null → 유지, 같은 집합 → 검증 없는
     * no-op(사후 비활성화된 항목이 무관한 질문을 막지 않게), 다르면 세션 소유자 기준 검증(400) → 갱신 → system note.
     * 다음 claim이 현재 행을 다시 조회하므로 러너는 턴마다 새 목록으로 조립한다.
     */
    private InterviewTurn applyDbChange(InterviewSession s, List<Long> ids) {
        if (ids == null) return null;
        if (idSet(ids).equals(idSet(s.getDbConnectionIds()))) return null;
        List<Long> validated = dbConnectionService.validateSelection(s.getRepoCatalogId(), s.getRequesterId(), ids);
        s.setDbConnectionIds(validated);
        List<DbConnectionDto.Chip> chips = dbConnectionService.chipsFor(validated);
        String note = chips.isEmpty()
                ? "DB 연결 변경: 없음(전부 해제)"
                : "DB 연결 변경: " + chips.stream().map(DbConnectionDto.Chip::name).collect(Collectors.joining(", "));
        return interviewService.appendSystemNote(s.getId(), note);
    }
```
- `get(...)` 교체:
```java
    @Transactional(readOnly = true)
    public InterviewResponse get(Long id, String viewerId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        InterviewResponse r = interviewService.getResponse(id, viewerId, isAdmin);
        return r.withDbConnections(dbConnectionService.chipsFor(r.dbConnectionIds()));
    }
```

`QuestionController.doAsk` — `if (note != null) …` 줄 아래에:
```java
        InterviewTurn dbNote = result.dbNote();
        if (dbNote != null) interviewStream.pushNote(id, dbNote.getSeq(), dbNote.getContent());
```
그리고 `ask` 메서드 Javadoc의 `{model, effort, mcpCatalogIds}`를 `{model, effort, mcpCatalogIds, dbConnectionIds}`로.

- [ ] **Step 5: 통과 확인 (질문 관련 단위 테스트 전체)**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.service.QuestionServiceTest --tests com.hamonsoft.netismaker.dto.InterviewResponseTest --tests com.hamonsoft.netismaker.dto.QuestionSummaryResponseTest
```
Expected: 기존 테스트 + 새 6건 전부 PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/QuestionCreateRequest.java src/main/java/com/hamonsoft/netismaker/dto/QuestionAskRequest.java src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java src/main/java/com/hamonsoft/netismaker/service/QuestionService.java src/main/java/com/hamonsoft/netismaker/controller/QuestionController.java src/test/java/com/hamonsoft/netismaker/service/QuestionServiceTest.java src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java
git commit -m "feat(db-mcp): 질문 등록·추가 질문에 DB 선택 + 변경 note + 상세 칩

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: claim 보강 + MCP 카탈로그 `db-` 예약

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/McpCatalogDto.java` (`UpsertRequest.name`)
- Modify: `src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerControllerFailTest.java` (생성자)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/InterviewClaimResponseTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerControllerClaimDbTest.java`, `src/test/java/com/hamonsoft/netismaker/dto/McpCatalogNameReservationTest.java`

**Interfaces:**
- Consumes: `DbConnectionService.resolveForClaim`, `ClaimDb`, `InterviewClaimResponse.DbConnectionRef`(Task 5)
- Produces: `InterviewClaimResponse` 끝 컴포넌트 `List<Long> dbConnectionIds, List<DbConnectionRef> dbConnections, List<String> dbNotices` + `InterviewClaimResponse withDb(List<DbConnectionRef> refs, List<String> notices)`; `InterviewWorkerController(InterviewService, InterviewStreamService, DbConnectionService)`. 와이어(JSON) 키: `dbConnectionIds`, `dbConnections[].{serverName,label,dbType,host,port,database,username,password}`, `dbNotices`

- [ ] **Step 1: 실패하는 테스트 작성**

`InterviewClaimResponseTest.java` 끝에 추가(파일의 기존 세션 생성 헬퍼를 쓸 수 있으면 쓴다):
```java
    @Test
    void db_ids_only_for_question_and_with_db_fills_refs() throws Exception {
        InterviewSession q = InterviewSession.createQuestion("a/b", "main", "t", "q", "user1",
                new java.util.ArrayList<>(), "claude-sonnet-5", "medium");
        q.setDbConnectionIds(new java.util.ArrayList<>(java.util.List.of(7L)));
        InterviewClaimResponse c = InterviewClaimResponse.of(q, java.util.List.of(), java.util.List.of());
        assertThat(c.dbConnectionIds()).containsExactly(7L);
        assertThat(c.dbConnections()).isEmpty();
        assertThat(c.dbNotices()).isEmpty();

        var ref = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "Pw-9");
        InterviewClaimResponse filled = c.withDb(java.util.List.of(ref), java.util.List.of("참고"));
        assertThat(filled.dbConnections()).containsExactly(ref);
        assertThat(filled.dbNotices()).containsExactly("참고");
        assertThat(filled.sessionId()).isEqualTo(c.sessionId());
        assertThat(filled.toString()).doesNotContain("Pw-9");
        // 워커 계약: JSON에는 평문 password가 실려야 한다(내부 API 전용)
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(filled);
        assertThat(json).contains("\"password\":\"Pw-9\"").contains("\"serverName\":\"db-7\"");
    }

    @Test
    void interview_kind_never_carries_db_ids() {
        InterviewSession i = InterviewSession.create("a/b", "main", "t", "d", "user1",
                new java.util.ArrayList<>(), "claude-sonnet-5", "medium");
        i.setDbConnectionIds(new java.util.ArrayList<>(java.util.List.of(7L)));
        assertThat(InterviewClaimResponse.of(i, java.util.List.of(), java.util.List.of()).dbConnectionIds()).isEmpty();
    }
```

`src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerControllerClaimDbTest.java`:
```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.InterviewClaimResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.service.DbConnectionService;
import com.hamonsoft.netismaker.service.InterviewService;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** claim 응답의 DB 접속정보 보강 (스펙 2026-10-02 §5.4) — QUESTION만, 보강 실패가 claim을 깨지 않는다. */
class InterviewWorkerControllerClaimDbTest {

    private final InterviewService interviewService = mock(InterviewService.class);
    private final DbConnectionService db = mock(DbConnectionService.class);
    private final InterviewWorkerController controller =
            new InterviewWorkerController(interviewService, mock(InterviewStreamService.class), db);

    private static InterviewClaimResponse claimOf(boolean question, List<Long> dbIds) {
        InterviewSession s = question
                ? InterviewSession.createQuestion("a/b", "main", "t", "q", "user1", new ArrayList<>(), "claude-sonnet-5", "medium")
                : InterviewSession.create("a/b", "main", "t", "q", "user1", new ArrayList<>(), "claude-sonnet-5", "medium");
        s.setDbConnectionIds(new ArrayList<>(dbIds));
        return InterviewClaimResponse.of(s, List.of(), List.of());
    }

    @Test
    void question_claim_with_db_ids_is_enriched() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of(7L))));
        var ref = new InterviewClaimResponse.DbConnectionRef("db-7", "운영 (MySQL)", "MYSQL", "h", 3306, "app", "u", "pw");
        when(db.resolveForClaim(List.of(7L))).thenReturn(new DbConnectionService.ClaimDb(List.of(ref), List.of()));
        assertThat(controller.claim("w1").getBody().dbConnections()).containsExactly(ref);
    }

    @Test
    void no_db_ids_skips_lookup() {
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of())));
        controller.claim("w1");
        verify(db, never()).resolveForClaim(any());
    }

    @Test
    void claim_survives_db_resolution_failure() {
        // Review Focus 3: claim 트랜잭션은 이미 커밋됨 — 여기서 500이면 세션이 RUNNING에 묶인다
        when(interviewService.claim("w1")).thenReturn(Optional.of(claimOf(true, List.of(7L))));
        when(db.resolveForClaim(any())).thenThrow(new RuntimeException("db down"));
        var body = controller.claim("w1").getBody();
        assertThat(body.dbConnections()).isEmpty();
        assertThat(body.dbNotices()).containsExactly("DB 접속정보를 불러오지 못해 DB 도구 없이 답합니다");
    }
}
```

`src/test/java/com/hamonsoft/netismaker/dto/McpCatalogNameReservationTest.java`:
```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MCP 카탈로그 이름의 'db-' 접두사는 DB 연결 서버용 예약 (스펙 2026-10-02 §5.4). */
class McpCatalogNameReservationTest {

    private final Validator v = Validation.buildDefaultValidatorFactory().getValidator();

    private static McpCatalogDto.UpsertRequest req(String name) {
        return new McpCatalogDto.UpsertRequest(name, "표시", "https://mcp.example/sse", "sse", null, true);
    }

    @Test
    void db_prefix_is_rejected_but_similar_names_pass() {
        assertThat(v.validate(req("db-1"))).isNotEmpty();
        assertThat(v.validate(req("db-tools"))).isNotEmpty();
        assertThat(v.validate(req("mydb-1"))).isEmpty();
        assertThat(v.validate(req("dbtools"))).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.dto.InterviewClaimResponseTest --tests com.hamonsoft.netismaker.controller.InterviewWorkerControllerClaimDbTest --tests com.hamonsoft.netismaker.dto.McpCatalogNameReservationTest
```
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`InterviewClaimResponse.java`:
- 컴포넌트 끝(`String repoHost` 뒤)에:
```java
        String repoHost,
        /** 세션이 선택한 DB 접속정보 id (스펙 2026-10-02 §5.4). QUESTION만, INTERVIEW는 []. */
        List<Long> dbConnectionIds,
        /** 복호화된 접속정보 — InterviewWorkerController가 withDb로 채운다. ⚠️ 평문 비밀번호 포함, 로그 금지. */
        List<DbConnectionRef> dbConnections,
        /** 사용할 수 없는 연결 등 프롬프트에 붙일 안내 (비밀번호 없음). */
        List<String> dbNotices
```
- compact 생성자에 추가:
```java
        dbConnectionIds = dbConnectionIds == null ? List.of() : ((List<?>) dbConnectionIds).stream()
                .map(o -> ((Number) o).longValue()).toList();
        dbConnections = dbConnections == null ? List.of() : List.copyOf(dbConnections);
        dbNotices = dbNotices == null ? List.of() : List.copyOf(dbNotices);
```
- `of(...)` 5-인자의 `return new InterviewClaimResponse(` 마지막 줄 `s.getGitUrl(), RepoRef.fromSnapshot(...).host());`를:
```java
                s.getGitUrl(), RepoRef.fromSnapshot(s.getGithubRepo(), s.getGitUrl()).host(),
                s.isQuestion() ? s.getDbConnectionIds() : List.of(), List.of(), List.of());
```
- 메서드 추가:
```java
    /** DB 접속정보만 채운 사본 (스펙 §5.4). */
    public InterviewClaimResponse withDb(List<DbConnectionRef> refs, List<String> notices) {
        return new InterviewClaimResponse(sessionId, githubRepo, githubBranch, title, description, claudeSessionId,
                currentPhase, workDir, lastAnswer, replyToSeq, mcpsExtra, turns, model, effort, totalCostUsd,
                attachments, kind, attachmentRoot, gitUrl, repoHost, dbConnectionIds, refs, notices);
    }
```

`InterviewWorkerController.java`:
- import 추가: `com.hamonsoft.netismaker.service.DbConnectionService`, `org.slf4j.Logger`, `org.slf4j.LoggerFactory`, `java.util.List`
- 필드 `private static final Logger log = LoggerFactory.getLogger(InterviewWorkerController.class);`, `private final DbConnectionService dbConnectionService;`
- 생성자에 세 번째 파라미터 `DbConnectionService dbConnectionService` 추가·대입.
- `claim` 교체 + 헬퍼:
```java
    @PostMapping("/claim")
    public ResponseEntity<InterviewClaimResponse> claim(@RequestParam String workerId) {
        Optional<InterviewClaimResponse> claimed = interviewService.claim(workerId).map(this::withDb);
        return claimed.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 질문 세션이면 DB 접속정보를 복호화해 싣는다 (스펙 2026-10-02 §5.4). claim 트랜잭션은 이미 커밋됐으므로
     * 여기서 실패해도 응답을 깨지 않는다 — 500이면 세션이 RUNNING에 묶여 stale 회수까지 멈춘다.
     * ⚠️ 이 응답 본문(평문 비밀번호 포함)을 로그로 남기지 말 것.
     */
    private InterviewClaimResponse withDb(InterviewClaimResponse c) {
        if (!"QUESTION".equals(c.kind()) || c.dbConnectionIds().isEmpty()) return c;
        try {
            DbConnectionService.ClaimDb db = dbConnectionService.resolveForClaim(c.dbConnectionIds());
            return c.withDb(db.refs(), db.notices());
        } catch (RuntimeException e) {
            log.warn("claim DB 접속정보 조회 실패 session={}: {}", c.sessionId(), e.getClass().getSimpleName());
            return c.withDb(List.of(), List.of("DB 접속정보를 불러오지 못해 DB 도구 없이 답합니다"));
        }
    }
```

`InterviewWorkerControllerFailTest.java` — 생성자 호출을:
```java
            new InterviewWorkerController(interviewService, stream, mock(com.hamonsoft.netismaker.service.DbConnectionService.class));
```

`McpCatalogDto.UpsertRequest.name` — 기존 `@Pattern` 아래에 한 줄 추가:
```java
            @Pattern(regexp = "^(?!db-).*$", message = "'db-'로 시작하는 이름은 DB 연결 서버용으로 예약되어 있습니다")
```
(Jakarta `@Pattern`은 반복 가능 — 두 패턴이 모두 적용된다.)

- [ ] **Step 4: 통과 확인**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests com.hamonsoft.netismaker.dto.InterviewClaimResponseTest --tests com.hamonsoft.netismaker.controller.InterviewWorkerControllerClaimDbTest --tests com.hamonsoft.netismaker.controller.InterviewWorkerControllerFailTest --tests com.hamonsoft.netismaker.dto.McpCatalogNameReservationTest --tests com.hamonsoft.netismaker.service.InterviewServiceTest
```
Expected: 전부 PASS.

- [ ] **Step 5: Java 전체 단위 테스트**

```bash
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test
```
Expected: `BUILD SUCCESSFUL`(Testcontainers 테스트는 SKIPPED).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java src/main/java/com/hamonsoft/netismaker/dto/McpCatalogDto.java src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerControllerFailTest.java src/test/java/com/hamonsoft/netismaker/dto/InterviewClaimResponseTest.java src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerControllerClaimDbTest.java src/test/java/com/hamonsoft/netismaker/dto/McpCatalogNameReservationTest.java
git commit -m "feat(db-mcp): claim에 DB 접속정보 보강 + MCP 카탈로그 db- 예약

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 읽기 전용 SQL 검사 (`sqlReadOnly.ts`)

**Files:**
- Create: `netismaker-interview-service/src/sdk/sqlReadOnly.ts`
- Test: `netismaker-interview-service/test/sqlReadOnly.test.ts`

**Interfaces:**
- Produces: `export type SqlDialect = 'postgres' | 'mysql' | 'oracle'`; `export type SqlCheck = { ok: true } | { ok: false; reason: string }`; `export function checkReadOnlySql(sql: string, dialect: SqlDialect): SqlCheck`; `export function stripSql(sql: string, dialect: SqlDialect): string | { error: string }`

- [ ] **Step 1: 실패하는 테스트 작성**

`netismaker-interview-service/test/sqlReadOnly.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { checkReadOnlySql, type SqlDialect } from '../src/sdk/sqlReadOnly.js';

const ok = (sql: string, d: SqlDialect) => expect(checkReadOnlySql(sql, d), `${d}: ${sql}`).toEqual({ ok: true });
const no = (sql: string, d: SqlDialect) => expect(checkReadOnlySql(sql, d).ok, `${d}: ${sql}`).toBe(false);
const ALL: SqlDialect[] = ['postgres', 'mysql', 'oracle'];

describe('checkReadOnlySql — 허용', () => {
  it('조회문 기본형 (3 방언 공통)', () => {
    for (const d of ALL) {
      for (const sql of [
        'SELECT 1',
        'select * from users where id = 3;',
        '  WITH t AS (SELECT 1 AS a) SELECT a FROM t',
        'SELECT updated_at, created_by, set_id FROM orders',
        "SELECT 'DELETE FROM x; DROP TABLE y' AS note",
        'SELECT REPLACE(name, \'a\', \'b\') FROM t',
        'EXPLAIN SELECT * FROM t',
        "SELECT * FROM t WHERE name = 'it''s'",
        'SELECT /* UPDATE 주석 */ 1',
        'SELECT 1 -- DELETE 주석\n',
      ]) ok(sql, d);
    }
  });

  it('방언별 조회문', () => {
    ok('SHOW TABLES', 'mysql');
    ok('DESC users', 'mysql');
    ok('SELECT `select` FROM `order`', 'mysql');
    ok('SELECT 1 # 주석 DELETE', 'mysql');
    ok('SHOW search_path', 'postgres');
    ok('VALUES (1), (2)', 'postgres');
    ok('TABLE users', 'postgres');
    ok('SELECT $$a;b$$', 'postgres');
    ok('SELECT "update" FROM t', 'postgres');
    ok('SELECT * FROM dual', 'oracle');
    ok('SELECT "DELETE" FROM t', 'oracle');
  });
});

describe('checkReadOnlySql — 거부', () => {
  it('DML/DDL/권한/트랜잭션', () => {
    for (const d of ALL) {
      for (const sql of [
        'DELETE FROM t',
        'UPDATE t SET a = 1',
        'INSERT INTO t VALUES (1)',
        'MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN UPDATE SET a = 1',
        'CREATE TABLE x (a int)',
        'ALTER TABLE t ADD c int',
        'DROP TABLE t',
        'TRUNCATE t',
        'GRANT SELECT ON t TO u',
        'COMMIT',
        'CALL p()',
        '',
        '   ',
      ]) no(sql, d);
    }
  });

  it('조회문 모양 안에 숨긴 쓰기', () => {
    for (const d of ALL) {
      for (const sql of [
        'SELECT 1; DELETE FROM t',
        'SELECT 1;; ',
        'WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x',
        'SELECT * INTO new_t FROM t',
        'SELECT * FROM t FOR UPDATE',
        'EXPLAIN ANALYZE SELECT 1',
        'SELECT 1 /* 닫히지 않은 주석',
        "SELECT 'unterminated",
        "SELECT 'a\\' ; DELETE FROM t; --'",   // Review Focus 4: 백슬래시가 든 문자열은 거부
      ]) no(sql, d);
    }
  });

  it('부작용·파일 접근 함수', () => {
    no("SELECT pg_terminate_backend(123)", 'postgres');
    no("SELECT set_config('x', 'y', false)", 'postgres');
    no("SELECT pg_read_file('/etc/passwd')", 'postgres');
    no("SELECT lo_import('/tmp/x')", 'postgres');
    no("SELECT dblink_exec('...')", 'postgres');
    no("SELECT nextval('seq')", 'postgres');
    no('SELECT pg_sleep(100)', 'postgres');
    no("SELECT LOAD_FILE('/etc/passwd')", 'mysql');
    no('SELECT SLEEP(100)', 'mysql');
    no('SELECT BENCHMARK(1e9, MD5(1))', 'mysql');
    no('SELECT DBMS_PIPE.RECEIVE_MESSAGE(1) FROM dual', 'oracle');
    no("SELECT UTL_HTTP.REQUEST('http://x') FROM dual", 'oracle');
  });

  it('방언별 주석·인용 우회 (Review Focus 4)', () => {
    no('SELECT 1 /*! ; DELETE FROM t */', 'mysql');        // MySQL 실행 주석
    no('SELECT 1 /*M! DELETE FROM t */', 'mysql');         // MariaDB 실행 주석
    no('SELECT 1 # x\n; DELETE FROM t', 'mysql');           // # 주석 뒤 줄바꿈 다음은 검사
    no('SELECT 1 # 1; DELETE FROM t', 'postgres');          // PG에서 #은 연산자 — 숨김 금지
    no('SELECT 1--1; DELETE FROM t', 'mysql');              // MySQL '--' 뒤 공백 없으면 주석 아님
    no('SELECT $a$ ; DELETE FROM t; $a$', 'mysql');         // MySQL은 $$ 문자열이 없다
    no('SELECT $$x', 'postgres');                           // 닫히지 않은 달러 인용
    no('SELECT `a` ; DELETE FROM t', 'mysql');
    no('SELECT "a" ; DELETE FROM t', 'postgres');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd netismaker-interview-service && npx vitest run test/sqlReadOnly.test.ts
```
Expected: FAIL(모듈 없음).

- [ ] **Step 3: 구현**

`netismaker-interview-service/src/sdk/sqlReadOnly.ts`:
```ts
/**
 * 질문 세션 DB 도구의 읽기 전용 SQL 검사 (스펙 2026-10-02 §8.2). 최선의 노력 — 최종 방어선은 읽기 전용 DB 계정이다.
 *
 * 원칙: 우리 파서와 DB 파서가 경계를 다르게 볼 수 있는 곳에서는 항상 "더 많이 보이는 쪽"(=거부 쪽)을 택한다.
 *  - 문자열 리터럴은 `''` 이스케이프만 인정하고, 안에 백슬래시가 있으면 거부한다 — MySQL(백슬래시 이스케이프)과
 *    PG 표준 문자열(백슬래시 = 일반 문자)이 끝 위치를 다르게 보기 때문(`'a\' ; DELETE …`).
 *  - `#` 주석은 mysql만(PG에서는 연산자), `--`는 mysql에서 뒤에 공백·제어문자가 있을 때만 주석.
 *  - `$tag$…$tag$`는 postgres만, 백틱 식별자는 mysql만. MySQL 실행 주석 `/*! */`·`/*M! */`은 거부.
 *  - 블록 주석은 중첩을 인정하지 않는다(PG는 중첩 허용 — 우리가 더 일찍 닫으므로 나머지가 검사 대상이 된다).
 */
export type SqlDialect = 'postgres' | 'mysql' | 'oracle';
export type SqlCheck = { ok: true } | { ok: false; reason: string };

const LEADING = new Set(['SELECT', 'WITH', 'EXPLAIN', 'SHOW', 'DESC', 'DESCRIBE', 'VALUES', 'TABLE']);

const FORBIDDEN_WORDS = new Set([
  'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'UPSERT', 'CREATE', 'ALTER', 'DROP', 'TRUNCATE', 'RENAME', 'GRANT', 'REVOKE',
  'COMMIT', 'ROLLBACK', 'SAVEPOINT', 'CALL', 'EXEC', 'EXECUTE', 'DO', 'COPY', 'LOCK', 'UNLOCK', 'SET', 'RESET', 'INTO',
  'LOAD', 'HANDLER', 'PREPARE', 'DEALLOCATE', 'LISTEN', 'NOTIFY', 'VACUUM', 'ANALYZE', 'ANALYSE', 'REINDEX', 'CLUSTER',
  'REFRESH', 'DISCARD', 'KILL', 'SHUTDOWN', 'PURGE', 'FLUSH', 'OPTIMIZE', 'REPAIR',
]);

const FORBIDDEN_FUNCS = new Set([
  'pg_terminate_backend', 'pg_cancel_backend', 'pg_reload_conf', 'pg_rotate_logfile', 'set_config', 'pg_read_file',
  'pg_read_binary_file', 'pg_ls_dir', 'pg_stat_file', 'pg_sleep', 'nextval', 'setval',
  'load_file', 'sleep', 'benchmark', 'get_lock', 'release_lock',
]);
const FORBIDDEN_PREFIXES = ['lo_', 'dblink', 'pg_advisory', 'dbms_', 'utl_'];

function isIdentChar(ch: string | undefined): boolean {
  return ch !== undefined && /[A-Za-z0-9_$#]/.test(ch);
}

/** 주석·문자열·따옴표 식별자를 공백/자리표시자로 바꾼 SQL. 위험·모호한 형태면 { error }. */
export function stripSql(sql: string, dialect: SqlDialect): string | { error: string } {
  let out = '';
  let i = 0;
  const n = sql.length;
  while (i < n) {
    const ch = sql[i]!;
    const next = sql[i + 1];
    // -- 주석 (mysql은 '-- ' 형태만)
    if (ch === '-' && next === '-') {
      const after = sql[i + 2];
      if (dialect !== 'mysql' || after === undefined || /\s/.test(after)) {
        const e = sql.indexOf('\n', i);
        i = e === -1 ? n : e;
        out += ' ';
        continue;
      }
    }
    if (ch === '#' && dialect === 'mysql') {
      const e = sql.indexOf('\n', i);
      i = e === -1 ? n : e;
      out += ' ';
      continue;
    }
    if (ch === '/' && next === '*') {
      if (dialect === 'mysql' && (sql[i + 2] === '!' || (sql[i + 2] === 'M' && sql[i + 3] === '!'))) {
        return { error: 'MySQL 실행 주석(/*! */)은 허용하지 않습니다' };
      }
      const e = sql.indexOf('*/', i + 2);
      if (e === -1) return { error: '닫히지 않은 주석' };
      i = e + 2;
      out += ' ';
      continue;
    }
    if (ch === "'" || (ch === '"' && dialect === 'mysql')) {
      const end = scanQuoted(sql, i, ch);
      if (typeof end !== 'number') return end;
      i = end;
      out += "''";
      continue;
    }
    if (ch === '"' || (ch === '`' && dialect === 'mysql')) {
      const end = scanQuoted(sql, i, ch);
      if (typeof end !== 'number') return end;
      i = end;
      out += ' x ';
      continue;
    }
    if (ch === '$' && dialect === 'postgres' && !isIdentChar(sql[i - 1])) {
      const m = /^\$([A-Za-z_][A-Za-z0-9_]*)?\$/.exec(sql.slice(i));
      if (m) {
        const tag = m[0];
        const e = sql.indexOf(tag, i + tag.length);
        if (e === -1) return { error: '닫히지 않은 달러 인용' };
        i = e + tag.length;
        out += "''";
        continue;
      }
    }
    out += ch;
    i++;
  }
  return out;
}

/** quote로 시작하는 인용의 끝 다음 인덱스. 연속 quote 두 개는 이스케이프. 백슬래시가 있으면 거부. */
function scanQuoted(sql: string, start: number, quote: string): number | { error: string } {
  let j = start + 1;
  while (j < sql.length) {
    const c = sql[j]!;
    if (c === '\\') return { error: '백슬래시가 든 문자열/식별자는 허용하지 않습니다' };
    if (c === quote) {
      if (sql[j + 1] === quote) { j += 2; continue; }
      return j + 1;
    }
    j++;
  }
  return { error: '닫히지 않은 문자열/식별자' };
}

export function checkReadOnlySql(sql: string, dialect: SqlDialect): SqlCheck {
  const stripped = stripSql(sql, dialect);
  if (typeof stripped !== 'string') return { ok: false, reason: stripped.error };
  const body = stripped.trim().replace(/;\s*$/, '').trim();
  if (body.length === 0) return { ok: false, reason: '빈 문장' };
  if (body.includes(';')) return { ok: false, reason: '여러 문장은 실행할 수 없습니다' };
  const words = body.match(/[A-Za-z_][A-Za-z0-9_$#]*/g) ?? [];
  const first = (words[0] ?? '').toUpperCase();
  if (!LEADING.has(first)) return { ok: false, reason: `조회문만 허용됩니다(시작 키워드 ${first || '없음'})` };
  for (const w of words) {
    const upper = w.toUpperCase();
    if (FORBIDDEN_WORDS.has(upper)) return { ok: false, reason: `금지 키워드 ${upper}` };
    const lower = w.toLowerCase();
    if (FORBIDDEN_FUNCS.has(lower) || FORBIDDEN_PREFIXES.some((p) => lower.startsWith(p))) {
      return { ok: false, reason: `금지 함수 ${lower}` };
    }
  }
  return { ok: true };
}
```
주의: `SELECT 1;; `는 끝의 `;` 하나만 떼므로 `;`가 남아 거부된다(의도). `SELECT * FROM dual`(Oracle)은 허용 — `DUAL`은 금지 단어가 아니다.

- [ ] **Step 4: 통과 확인**

```bash
cd netismaker-interview-service && npx vitest run test/sqlReadOnly.test.ts
```
Expected: 전부 PASS. 실패 사례가 있으면 해당 규칙을 고치되, **허용 쪽으로 완화할 때는 Review Focus 4 사례가 계속 거부되는지** 다시 돌린다.

- [ ] **Step 5: Commit**

```bash
git add netismaker-interview-service/src/sdk/sqlReadOnly.ts netismaker-interview-service/test/sqlReadOnly.test.ts
git commit -m "feat(interview): 방언별 읽기 전용 SQL 검사

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 질문 게이트에 DB 도구 분기

**Files:**
- Modify: `netismaker-interview-service/src/sdk/permissions.ts` (`questionMcpGate`, `buildCanUseTool`)
- Test: `netismaker-interview-service/test/permissions.test.ts`

**Interfaces:**
- Consumes: `checkReadOnlySql`, `SqlDialect`(Task 9)
- Produces: `buildCanUseTool(repoDir: string, kind?: SessionKind, attachmentRoot?: string | null, dbServers?: Record<string, SqlDialect>): CanUseTool`; 상수 `DB_READ_TOOLS: ReadonlySet<string>`(= `list_tables`, `describe_table`) — 도구 이름은 Task 11 `dbMcp.ts`와 같아야 한다

- [ ] **Step 1: 실패하는 테스트 작성** — `test/permissions.test.ts` 끝에 추가:

```ts
describe('canUseTool — kind=QUESTION DB 서버 게이트 (스펙 2026-10-02 §8)', () => {
  const q = buildCanUseTool('/tmp/repo', 'QUESTION', null, { 'db-3': 'postgres', 'db-7': 'mysql' });

  it('query는 조회문만 통과', async () => {
    expect((await q('mcp__db-3__query', { sql: 'SELECT * FROM users' })).behavior).toBe('allow');
    const r = await q('mcp__db-3__query', { sql: 'UPDATE users SET a = 1' });
    expect(r.behavior).toBe('deny');
    expect(r.message).toContain('읽기 전용');
    expect(r.message).toContain('UPDATE');
  });

  it('방언을 서버별로 적용한다 — # 주석은 mysql 서버에서만', async () => {
    expect((await q('mcp__db-7__query', { sql: 'SELECT 1 # 주석' })).behavior).toBe('allow');
    expect((await q('mcp__db-3__query', { sql: 'SELECT 1 # 1; DELETE FROM t' })).behavior).toBe('deny');
  });

  it('list_tables·describe_table은 허용, 그 외 도구·SQL 없음은 거부', async () => {
    expect((await q('mcp__db-3__list_tables', {})).behavior).toBe('allow');
    expect((await q('mcp__db-7__describe_table', { table: 'orders' })).behavior).toBe('allow');
    expect((await q('mcp__db-3__execute_sql', { sql: 'SELECT 1' })).behavior).toBe('deny');
    expect((await q('mcp__db-3__query', {})).behavior).toBe('deny');
  });

  it('등록되지 않은 db-N 서버 이름은 거부(이름 흉내 차단), 기존 서버 규칙은 그대로', async () => {
    expect((await q('mcp__db-99__query', { sql: 'SELECT 1' })).behavior).toBe('deny');
    expect((await q('mcp__local-db__execute_sql', { sql: 'select 1' })).behavior).toBe('deny');
    expect((await q('mcp__local-db__query', { sql: 'select 1' })).behavior).toBe('allow');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd netismaker-interview-service && npx vitest run test/permissions.test.ts
```
Expected: 새 describe 4건 FAIL(기존 Windows 실패 2건은 그대로).

- [ ] **Step 3: 구현** — `src/sdk/permissions.ts`:

상단 import에 추가:
```ts
import { checkReadOnlySql, type SqlDialect } from './sqlReadOnly.js';
```
`questionMcpGate` 위에 추가:
```ts
/** 내장 DB 서버(`db-<id>`, dbMcp.ts) 도구 중 SQL 인자 없이 허용하는 것 (스펙 2026-10-02 §8.1). 입력은 바인딩 파라미터로만 쓰인다. */
export const DB_READ_TOOLS: ReadonlySet<string> = new Set(['list_tables', 'describe_table']);

function dbToolGate(toolName: string, tool: string, input: Record<string, unknown>, dialect: SqlDialect): PermissionResult {
  if (tool === 'query') {
    if (typeof input.sql !== 'string') return { behavior: 'deny', message: `질문 세션 DB 도구 입력에 SQL이 없습니다: ${toolName}` };
    const r = checkReadOnlySql(input.sql, dialect);
    return r.ok
      ? { behavior: 'allow' }
      : { behavior: 'deny', message: `질문 세션 DB 도구는 읽기 전용입니다 — ${r.reason}. SELECT 계열 단일 문장만 실행할 수 있습니다.` };
  }
  if (DB_READ_TOOLS.has(tool)) return { behavior: 'allow' };
  return { behavior: 'deny', message: `질문 세션에서 허용되지 않은 DB 도구입니다: ${toolName}` };
}
```
`questionMcpGate` 시그니처와 서버 분기:
```ts
function questionMcpGate(
  toolName: string,
  input: Record<string, unknown>,
  dbServers: Record<string, SqlDialect>,
): PermissionResult {
```
그리고 `if (server.length === 0 || tool.length === 0) return deny;` 바로 아래에:
```ts
  // 내장 DB 서버(스펙 2026-10-02 §8.1)는 Obsidian/동사 규칙보다 먼저 — 'query'는 동사 denylist에 안 걸려 SQL 검사 없이 통과하므로.
  const dialect = dbServers[server];
  if (dialect) return dbToolGate(toolName, tool, input, dialect);
  if (/^db-\d+$/.test(server)) return deny; // 이번 턴에 붙이지 않은 db-N — 다른 소스가 이름을 흉내 낸 서버
```
`questionMcpGate` Javadoc 목록 맨 앞에 한 줄: `*  - 내장 DB 서버(dbServers에 등록된 db-<id>)는 dbToolGate — query의 SQL을 방언별 읽기 전용 검사(sqlReadOnly.ts), 도구 핸들러가 한 번 더 검사한다.`

`buildCanUseTool`:
```ts
export function buildCanUseTool(
  repoDir: string,
  kind: SessionKind = 'INTERVIEW',
  attachmentRoot: string | null = null,
  dbServers: Record<string, SqlDialect> = {},
): CanUseTool {
```
QUESTION 분기의 `if (toolName.startsWith('mcp__')) return questionMcpGate(toolName);`를:
```ts
      if (toolName.startsWith('mcp__')) return questionMcpGate(toolName, input, dbServers);
```
(`input`의 타입이 `Record<string, unknown>`이 아니면 이 파일의 다른 게이트가 쓰는 타입 그대로 넘긴다.)

- [ ] **Step 4: 통과 확인**

```bash
cd netismaker-interview-service && npx vitest run test/permissions.test.ts
```
Expected: 새 4건 PASS, 기존 실패는 Windows 기존 2건뿐.

- [ ] **Step 5: Commit**

```bash
git add netismaker-interview-service/src/sdk/permissions.ts netismaker-interview-service/test/permissions.test.ts
git commit -m "feat(interview): 질문 게이트 DB 서버 분기 — query 방언별 읽기 전용 검사

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 타입 + 내장 DB MCP(결과·어댑터·카탈로그·서버)

**Files:**
- Modify: `netismaker-interview-service/src/types.ts`
- Create: `netismaker-interview-service/src/sdk/db/limits.ts`, `src/sdk/db/result.ts`, `src/sdk/db/adapters.ts`, `src/sdk/db/catalog.ts`
- Create: `netismaker-interview-service/src/sdk/dbMcp.ts`
- Create: `netismaker-interview-service/scripts/spikeDbAdapter.ts`
- Test: `netismaker-interview-service/test/dbResult.test.ts`, `test/dbAdapters.test.ts`, `test/dbMcp.test.ts`

**Interfaces:**
- Consumes: `checkReadOnlySql`, `stripSql`, `SqlDialect`(Task 9), 의존성(Task 1)
- Produces:
  - `types.ts`: `export type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE'`; `export interface DbConnectionRef { serverName; label; dbType: DbType; host; port: number; database; username; password }`; `InterviewClaimResponse`에 `dbConnectionIds?: number[]; dbConnections?: DbConnectionRef[]; dbNotices?: string[]`
  - `db/limits.ts`: `DB_LIMITS`
  - `db/result.ts`: `interface QueryResult { columns: string[]; rows: unknown[][]; truncated: boolean }`, `toCell(v)`, `formatResult(r, maxRows?)`
  - `db/adapters.ts`: `interface DbSession { run(sql, params?, maxRows?): Promise<QueryResult>; close(): Promise<void> }`, `interface DriverLoader { pg(); mysql(); oracle() }`, `realDrivers`, `loadDriver(name)`, `withoutTrailingSemicolon(sql)`, `openSession(ref, drivers?)`
  - `db/catalog.ts`: `catalogSql(dialect, kind: 'tables'|'columns'|'indexes', schema: string|null, table?): { sql; params }`
  - `dbMcp.ts`: `dialectFor(t)`, `safeMessage(e, ref)`, `dbToolsFor(ref, dialect, getSession): SdkMcpToolDefinition[]`, `interface PreparedDbMcp { servers: Record<string, McpSdkServerConfigWithInstance>; tools: Record<string, SdkMcpToolDefinition[]>; dialects: Record<string, SqlDialect>; labels: Array<{ serverName: string; label: string }>; notices: string[]; close(): Promise<void> }`, `createDbMcp(claim, deps?: { openSession?: (ref) => Promise<DbSession> }): PreparedDbMcp`

- [ ] **Step 1: 실패하는 테스트 작성**

`netismaker-interview-service/test/dbResult.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { formatResult, toCell } from '../src/sdk/db/result.js';
import { DB_LIMITS } from '../src/sdk/db/limits.js';

describe('toCell (스펙 2026-10-02 §6.3)', () => {
  it('형 변환', () => {
    expect(toCell(null)).toBeNull();
    expect(toCell(undefined)).toBeNull();
    expect(toCell(12345678901234567890n)).toBe('12345678901234567890');
    expect(toCell(3)).toBe(3);
    expect(toCell(true)).toBe(true);
    expect(toCell(Buffer.from([1, 2, 3]))).toBe('<binary 3 bytes>');
    expect(toCell(new Date('2026-10-02T01:02:03Z'))).toBe('2026-10-02T01:02:03.000Z');
    expect(toCell({ a: 1 })).toBe('{"a":1}');
  });

  it('긴 문자열은 앞 maxCellChars자 + 남은 길이', () => {
    const s = 'x'.repeat(DB_LIMITS.maxCellChars + 5);
    expect(toCell(s)).toBe('x'.repeat(DB_LIMITS.maxCellChars) + '…(+5자)');
  });
});

describe('formatResult', () => {
  it('JSON 한 덩어리 — columns/rows/rowCount/truncated', () => {
    const t = formatResult({ columns: ['n'], rows: [[1n], [2n]], truncated: false });
    expect(JSON.parse(t)).toEqual({ columns: ['n'], rows: [['1'], ['2']], rowCount: 2, truncated: false });
  });

  it('행 상한을 넘으면 자르고 truncated', () => {
    const rows = Array.from({ length: 5 }, (_, i) => [i]);
    expect(JSON.parse(formatResult({ columns: ['n'], rows, truncated: false }, 3))).toMatchObject({ rowCount: 3, truncated: true });
    expect(JSON.parse(formatResult({ columns: ['n'], rows: [[1]], truncated: true }))).toMatchObject({ truncated: true });
  });

  it('전체 길이 상한을 넘으면 뒤 행부터 버린다', () => {
    const big = 'y'.repeat(DB_LIMITS.maxCellChars);
    const rows = Array.from({ length: 100 }, () => [big]);
    const t = formatResult({ columns: ['c'], rows, truncated: false }, 100);
    expect(t.length).toBeLessThanOrEqual(DB_LIMITS.maxResultChars);
    const parsed = JSON.parse(t);
    expect(parsed.truncated).toBe(true);
    expect(parsed.rowCount).toBeGreaterThan(0);
    expect(parsed.rowCount).toBeLessThan(100);
  });
});
```

`netismaker-interview-service/test/dbAdapters.test.ts`:
```ts
import { EventEmitter } from 'node:events';
import { describe, expect, it } from 'vitest';
import { loadDriver, openSession, type DriverLoader } from '../src/sdk/db/adapters.js';
import { catalogSql } from '../src/sdk/db/catalog.js';
import type { DbConnectionRef } from '../src/types.js';

const ref = (over: Partial<DbConnectionRef> = {}): DbConnectionRef => ({
  serverName: 'db-7', label: '운영 DB', dbType: 'POSTGRESQL', host: 'db.local', port: 5432,
  database: 'app', username: 'reader', password: 'Pw-secret', ...over,
});
const tick = () => new Promise((r) => setImmediate(r));

type PgReply = { fields?: Array<{ name: string }>; rows?: unknown[][] } | Error;
function fakePg(reply: (text: string) => PgReply = () => ({})) {
  const log: string[] = [];
  const configs: Array<Record<string, unknown>> = [];
  const loader = {
    pg: async () => ({
      Client: class {
        constructor(cfg: Record<string, unknown>) { configs.push(cfg); }
        on() { return this; }
        async connect() { log.push('CONNECT'); }
        async query(q: { text: string }) {
          await tick(); // 직렬화 테스트: 비동기 사이에 다른 run이 끼어들 틈을 만든다
          log.push(q.text);
          const r = reply(q.text);
          if (r instanceof Error) throw r;
          return r;
        }
        async end() { log.push('END'); }
      },
    }),
  } as unknown as DriverLoader;
  return { loader, log, configs };
}

type MyReply = { fields?: string[]; rows?: unknown[][]; error?: Error };
function fakeMysql(reply: (sql: string) => MyReply = () => ({})) {
  const log: string[] = [];
  const configs: Array<Record<string, unknown>> = [];
  const loader = {
    mysql: async () => ({
      createConnection: (cfg: Record<string, unknown>) => {
        configs.push(cfg);
        const conn = new EventEmitter() as EventEmitter & Record<string, unknown>;
        conn.query = (opts: { sql: string }) => {
          const q = new EventEmitter();
          log.push(opts.sql);
          setImmediate(() => {
            const r = reply(opts.sql);
            if (r.error) { q.emit('error', r.error); return; }
            if (r.fields) q.emit('fields', r.fields.map((name) => ({ name })));
            for (const row of r.rows ?? []) q.emit('result', row);
            q.emit('end');
          });
          return q;
        };
        conn.destroy = () => { log.push('DESTROY'); };
        conn.end = (cb?: () => void) => { log.push('END'); cb?.(); };
        return conn;
      },
    }),
  } as unknown as DriverLoader;
  return { loader, log, configs };
}

function fakeOracle(rows: unknown[][]) {
  const log: string[] = [];
  const configs: Array<Record<string, unknown>> = [];
  const conn = {
    callTimeout: 0,
    async execute(sql: string, _binds: unknown[], opts?: { maxRows?: number }) {
      log.push(sql);
      if (opts) log.push(`maxRows=${opts.maxRows}`);
      return { metaData: [{ name: 'N' }], rows };
    },
    async rollback() { log.push('ROLLBACK'); },
    async close() { log.push('CLOSE'); },
  };
  const loader = {
    oracle: async () => ({
      getConnection: async (cfg: Record<string, unknown>) => { configs.push(cfg); return conn; },
      OUT_FORMAT_ARRAY: 4001, STRING: 'STR', DB_TYPE_NUMBER: 'NUM', DB_TYPE_CLOB: 'CLOB', DB_TYPE_NCLOB: 'NCLOB',
      DB_TYPE_DATE: 'DATE', DB_TYPE_TIMESTAMP: 'TS', DB_TYPE_TIMESTAMP_TZ: 'TSTZ', DB_TYPE_TIMESTAMP_LTZ: 'TSLTZ',
    }),
  } as unknown as DriverLoader;
  return { loader, log, configs, conn };
}

describe('PostgreSQL 세션 (스펙 2026-10-02 §6.2)', () => {
  it('SELECT는 READ ONLY 트랜잭션 + 커서 FETCH(maxRows+1) + ROLLBACK, 끝 ; 제거', async () => {
    const pg = fakePg((t) => (t.startsWith('FETCH') ? { fields: [{ name: 'n' }], rows: [[1], [2], [3]] } : {}));
    const s = await openSession(ref(), pg.loader);
    const r = await s.run('SELECT * FROM t;', [], 2);
    expect(pg.log).toEqual([
      'CONNECT',
      'BEGIN TRANSACTION READ ONLY',
      'SET LOCAL statement_timeout = 30000',
      'DECLARE netis_q NO SCROLL CURSOR FOR SELECT * FROM t',
      'FETCH FORWARD 3 FROM netis_q',
      'ROLLBACK',
    ]);
    expect(r).toEqual({ columns: ['n'], rows: [[1], [2]], truncated: true });
    expect(pg.configs[0]).toMatchObject({ options: '-c default_transaction_read_only=on', connectionTimeoutMillis: 10000, password: 'Pw-secret' });
  });

  it('SHOW·EXPLAIN과 바인딩 파라미터가 있는 조회는 커서 없이 직접', async () => {
    const pg = fakePg();
    const s = await openSession(ref(), pg.loader);
    await s.run('SHOW search_path');
    await s.run('SELECT 1 WHERE $1::text IS NULL', [null]);
    expect(pg.log.filter((l) => l.startsWith('DECLARE'))).toEqual([]);
    expect(pg.log).toContain('SHOW search_path');
  });

  it('SQL 오류에도 ROLLBACK', async () => {
    const pg = fakePg((t) => (t.startsWith('DECLARE') ? new Error('boom') : {}));
    const s = await openSession(ref(), pg.loader);
    await expect(s.run('SELECT x')).rejects.toThrow('boom');
    expect(pg.log.at(-1)).toBe('ROLLBACK');
  });

  it('같은 세션의 동시 run은 트랜잭션이 겹치지 않는다', async () => {
    const pg = fakePg();
    const s = await openSession(ref(), pg.loader);
    await Promise.all([s.run('SHOW a'), s.run('SHOW b')]);
    const body = pg.log.slice(1);
    expect(body).toEqual([
      'BEGIN TRANSACTION READ ONLY', 'SET LOCAL statement_timeout = 30000', 'SHOW a', 'ROLLBACK',
      'BEGIN TRANSACTION READ ONLY', 'SET LOCAL statement_timeout = 30000', 'SHOW b', 'ROLLBACK',
    ]);
  });
});

describe('MySQL·MariaDB 세션', () => {
  const my = (over: Partial<DbConnectionRef> = {}) => ref({ dbType: 'MARIADB', port: 3306, ...over });

  it('접속 직후 세션 READ ONLY + 타임아웃 2종, run은 START TRANSACTION READ ONLY … ROLLBACK', async () => {
    const m = fakeMysql((sql) => (sql === 'SELECT 1' ? { fields: ['1'], rows: [[1]] } : {}));
    const s = await openSession(my(), m.loader);
    const r = await s.run('SELECT 1;');
    expect(m.log).toEqual([
      'SET SESSION TRANSACTION READ ONLY',
      'SET SESSION max_execution_time = 30000',
      'SET SESSION max_statement_time = 30',
      'START TRANSACTION READ ONLY',
      'SELECT 1',
      'ROLLBACK',
    ]);
    expect(r).toEqual({ columns: ['1'], rows: [[1]], truncated: false });
    expect(m.configs[0]).toMatchObject({ multipleStatements: false, flags: ['-LOCAL_FILES'], bigNumberStrings: true, connectTimeout: 10000 });
  });

  it('모르는 타임아웃 변수 오류는 무시한다', async () => {
    const m = fakeMysql((sql) => (sql.includes('max_execution_time') ? { error: new Error('Unknown system variable') } : {}));
    const s = await openSession(my(), m.loader);
    await expect(s.run('SELECT 1')).resolves.toMatchObject({ truncated: false });
  });

  it('READ ONLY 설정이 실패하면 쿼리를 실행하지 않는다', async () => {
    const m = fakeMysql((sql) => (sql === 'SET SESSION TRANSACTION READ ONLY' ? { error: new Error('denied') } : {}));
    const s = await openSession(my(), m.loader);
    await expect(s.run('SELECT 1')).rejects.toThrow('denied');
    expect(m.log).not.toContain('SELECT 1');
    expect(m.log).toContain('DESTROY');
  });

  it('행 상한을 넘으면 커넥션을 버리고(ROLLBACK 없이) 다음 run에서 다시 접속', async () => {
    const m = fakeMysql((sql) => (sql === 'SELECT n FROM t' ? { fields: ['n'], rows: [[1], [2], [3], [4], [5]] } : {}));
    const s = await openSession(my(), m.loader);
    const r = await s.run('SELECT n FROM t', [], 2);
    expect(r).toEqual({ columns: ['n'], rows: [[1], [2]], truncated: true });
    expect(m.log.slice(-2)).toEqual(['SELECT n FROM t', 'DESTROY']);
    await s.run('SELECT n FROM t', [], 10);
    expect(m.configs).toHaveLength(2);
  });

  it('SQL 오류면 ROLLBACK 후 오류', async () => {
    const m = fakeMysql((sql) => (sql === 'SELECT bad' ? { error: new Error('syntax') } : {}));
    const s = await openSession(my(), m.loader);
    await expect(s.run('SELECT bad')).rejects.toThrow('syntax');
    expect(m.log.at(-1)).toBe('ROLLBACK');
  });
});

describe('Oracle 세션', () => {
  it('ROLLBACK → SET TRANSACTION READ ONLY → execute(maxRows+1) → ROLLBACK, EZConnect·callTimeout', async () => {
    const o = fakeOracle([[1], [2], [3]]);
    const s = await openSession(ref({ dbType: 'ORACLE', port: 1521, database: 'ORCLPDB1' }), o.loader);
    const r = await s.run('SELECT n FROM dual;', [], 2);
    expect(o.log).toEqual(['ROLLBACK', 'SET TRANSACTION READ ONLY', 'SELECT n FROM dual', 'maxRows=3', 'ROLLBACK']);
    expect(r).toEqual({ columns: ['N'], rows: [[1], [2]], truncated: true });
    expect(o.configs[0]).toMatchObject({ connectString: 'db.local:1521/ORCLPDB1', user: 'reader' });
    expect(o.conn.callTimeout).toBe(30000);
    await s.close();
    expect(o.log.at(-1)).toBe('CLOSE');
  });
});

describe('드라이버 로더·카탈로그', () => {
  it('없는 드라이버는 고정 문구 오류', async () => {
    await expect(loadDriver('netismaker-no-such-driver')).rejects.toThrow('DB 드라이버를 불러올 수 없습니다(netismaker-no-such-driver)');
  });

  it('카탈로그 SQL은 값을 바인딩 파라미터로만 넘긴다', () => {
    expect(catalogSql('postgres', 'tables', null)).toMatchObject({ params: [null] });
    const c = catalogSql('oracle', 'columns', null, "x' OR '1'='1");
    expect(c.params).toEqual([null, "x' OR '1'='1"]);
    expect(c.sql).not.toContain("x' OR");
    expect(c.sql).toContain('UPPER(:2)');
    expect(catalogSql('mysql', 'indexes', 'app', 'orders').params).toEqual(['app', 'orders']);
  });
});
```

`netismaker-interview-service/test/dbMcp.test.ts`:
```ts
import { describe, expect, it, vi } from 'vitest';
import { createDbMcp, dbToolsFor, safeMessage } from '../src/sdk/dbMcp.js';
import type { DbSession } from '../src/sdk/db/adapters.js';
import type { DbConnectionRef, InterviewClaimResponse } from '../src/types.js';
import { questionClaim } from './fixtures/claims.js';

const ref = (over: Partial<DbConnectionRef> = {}): DbConnectionRef => ({
  serverName: 'db-7', label: '운영 DB (PostgreSQL)', dbType: 'POSTGRESQL', host: 'db.local', port: 5432,
  database: 'app', username: 'reader', password: 'Pw-secret', ...over,
});
const claimWith = (refs: DbConnectionRef[], notices: string[] = []): InterviewClaimResponse =>
  ({ ...questionClaim, dbConnections: refs, dbNotices: notices });
const fakeSession = (): DbSession & { run: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn> } => ({
  run: vi.fn(async () => ({ columns: ['n'], rows: [[1]], truncated: false })),
  close: vi.fn(async () => undefined),
});
const call = async (tools: ReturnType<typeof dbToolsFor>, name: string, args: Record<string, unknown>) => {
  const t = tools.find((x) => x.name === name);
  if (!t) throw new Error(`도구 없음: ${name}`);
  return (await t.handler(args as never, {})) as { content: Array<{ text: string }>; isError?: boolean };
};

describe('createDbMcp (스펙 2026-10-02 §6.2)', () => {
  it('연결마다 type:sdk 서버 — 인스턴스를 뺀 설정(CLI로 가는 부분)에 비밀번호 없음', () => {
    const p = createDbMcp(claimWith([ref()], ['참고 1']));
    const s = p.servers['db-7']!;
    expect(s.type).toBe('sdk');
    expect(s.name).toBe('db-7');
    expect(s.instance).toBeDefined();
    const { instance: _instance, ...wire } = s;
    expect(JSON.stringify(wire)).not.toContain('Pw-secret');
    expect(p.tools['db-7']!.map((t) => t.name)).toEqual(['query', 'list_tables', 'describe_table']);
    expect(p.dialects).toEqual({ 'db-7': 'postgres' });
    expect(p.labels).toEqual([{ serverName: 'db-7', label: '운영 DB (PostgreSQL)' }]);
    expect(p.notices).toEqual(['참고 1']);
  });

  it('연결이 없거나 필드가 없으면 빈 결과, 잘못된 serverName은 건너뛰고 안내', () => {
    expect(createDbMcp(claimWith([])).servers).toEqual({});
    expect(createDbMcp({ ...questionClaim }).servers).toEqual({});
    const p = createDbMcp(claimWith([ref({ serverName: '../evil' })]));
    expect(p.servers).toEqual({});
    expect(p.notices[0]).toContain('잘못된 DB 서버 이름');
  });

  it('DB 접속은 첫 도구 호출 때 한 번, close는 열린 세션만 닫는다', async () => {
    const session = fakeSession();
    const openSession = vi.fn(async () => session);
    const p = createDbMcp(claimWith([ref(), ref({ serverName: 'db-8', dbType: 'MYSQL' })]), { openSession });
    expect(openSession).not.toHaveBeenCalled();
    await call(p.tools['db-7']!, 'query', { sql: 'SELECT 1' });
    await call(p.tools['db-7']!, 'list_tables', {});
    expect(openSession).toHaveBeenCalledTimes(1);
    await p.close();
    expect(session.close).toHaveBeenCalledTimes(1);
  });

  it('접속 실패 뒤에는 다음 호출이 다시 접속을 시도한다, close 실패는 삼킨다', async () => {
    const session = fakeSession();
    session.close.mockRejectedValue(new Error('close boom'));
    const openSession = vi.fn().mockRejectedValueOnce(new Error('refused')).mockResolvedValue(session);
    const p = createDbMcp(claimWith([ref()]), { openSession });
    expect((await call(p.tools['db-7']!, 'query', { sql: 'SELECT 1' })).isError).toBe(true);
    expect((await call(p.tools['db-7']!, 'query', { sql: 'SELECT 1' })).isError).toBeUndefined();
    expect(openSession).toHaveBeenCalledTimes(2);
    await expect(p.close()).resolves.toBeUndefined();
  });
});

describe('dbToolsFor — 도구 핸들러', () => {
  it('query: 결과 JSON, 상한 200행으로 실행', async () => {
    const session = fakeSession();
    const tools = dbToolsFor(ref(), 'postgres', async () => session);
    const r = await call(tools, 'query', { sql: 'SELECT 1' });
    expect(session.run).toHaveBeenCalledWith('SELECT 1', [], 200);
    expect(JSON.parse(r.content[0]!.text)).toMatchObject({ rowCount: 1, truncated: false });
  });

  it('handler_rejects_dml_even_if_gate_allowed', async () => {
    // Review Focus 6: 게이트가 allow해도 핸들러가 다시 막는다 — 세션을 열지도 않는다
    const getSession = vi.fn();
    const tools = dbToolsFor(ref(), 'mysql', getSession);
    for (const sql of ['DELETE FROM t', 'SELECT 1; DROP TABLE t', 'SELECT 1 /*! ; DELETE FROM t */']) {
      const r = await call(tools, 'query', { sql });
      expect(r.isError).toBe(true);
      expect(r.content[0]!.text).toContain('읽기 전용');
    }
    expect(getSession).not.toHaveBeenCalled();
  });

  it('error_text_never_contains_password', async () => {
    // Review Focus 5
    const tools = dbToolsFor(ref(), 'postgres', async () => { throw new Error('auth failed for reader using Pw-secret'); });
    const r = await call(tools, 'query', { sql: 'SELECT 1' });
    expect(r.isError).toBe(true);
    expect(r.content[0]!.text).not.toContain('Pw-secret');
    expect(r.content[0]!.text).toContain('****');
  });

  it('list_tables·describe_table은 카탈로그 SQL을 바인딩으로 실행(카탈로그 상한 1000행)', async () => {
    const session = fakeSession();
    const tools = dbToolsFor(ref({ dbType: 'MYSQL' }), 'mysql', async () => session);
    await call(tools, 'list_tables', { schema: 'app' });
    expect(session.run.mock.calls[0]![1]).toEqual(['app']);
    expect(session.run.mock.calls[0]![2]).toBe(1000);
    const d = await call(tools, 'describe_table', { table: 'orders' });
    expect(session.run.mock.calls[1]![1]).toEqual([null, 'orders']);
    expect(session.run.mock.calls[2]![1]).toEqual([null, 'orders']);
    expect(d.content[0]!.text).toContain('컬럼:');
    expect(d.content[0]!.text).toContain('인덱스:');
  });
});

describe('safeMessage', () => {
  it('비밀번호 치환 + 500자 제한', () => {
    expect(safeMessage(new Error('x Pw-secret y Pw-secret'), { password: 'Pw-secret' })).toBe('x **** y ****');
    expect(safeMessage('z'.repeat(600), { password: 'p' }).length).toBeLessThanOrEqual(501);
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd netismaker-interview-service && npx vitest run test/dbResult.test.ts test/dbAdapters.test.ts test/dbMcp.test.ts
```
Expected: FAIL(모듈 없음).

- [ ] **Step 3: 타입**

`src/types.ts` — `McpSpec` 인터페이스 아래에:
```ts
/** Java DbType (스펙 2026-10-02 §4). */
export type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE';

/**
 * Java InterviewClaimResponse.DbConnectionRef — 복호화된 DB 접속정보(질문 세션 claim 전용).
 * ⚠️ password 평문: 로그·오류 메시지·SDK options의 stdio 서버 설정(명령줄로 나간다)에 넣지 말 것 —
 * dbMcp.ts가 메모리에서만 드라이버에 넘긴다(type:'sdk' 서버는 CLI에 이름만 간다).
 */
export interface DbConnectionRef {
  serverName: string;
  label: string;
  dbType: DbType;
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
}
```
`InterviewClaimResponse` 끝(`repoHost?` 아래)에:
```ts
  /**
   * DB 접속정보 (스펙 2026-10-02 §5.4) — QUESTION만. 구버전 백엔드는 필드가 없다 → Array.isArray 가드.
   * dbConnections는 평문 비밀번호를 담는다(위 DbConnectionRef 주의).
   */
  dbConnectionIds?: number[];
  dbConnections?: DbConnectionRef[];
  dbNotices?: string[];
```

- [ ] **Step 4: limits.ts · result.ts**

`src/sdk/db/limits.ts`:
```ts
/** 질문 세션 DB 도구 상한 (스펙 2026-10-02 §6.2·§6.3). 설정화는 범위 밖(§12) — 바꿀 때는 스펙과 같이 바꾼다. */
export const DB_LIMITS = {
  statementTimeoutMs: 30_000,
  connectTimeoutMs: 10_000,
  maxRows: 200,
  maxCatalogRows: 1000,
  maxCellChars: 2000,
  maxResultChars: 60_000,
} as const;
```

`src/sdk/db/result.ts`:
```ts
import { DB_LIMITS } from './limits.js';

/** 어댑터 한 번 실행 결과. rows는 상한까지만, 넘쳤으면 truncated. */
export interface QueryResult {
  columns: string[];
  rows: unknown[][];
  truncated: boolean;
}

function clip(s: string): string {
  const max = DB_LIMITS.maxCellChars;
  return s.length > max ? `${s.slice(0, max)}…(+${s.length - max}자)` : s;
}

/**
 * 셀 값을 JSON에 안전한 값으로 (스펙 §6.3). bigint를 숫자로 바꾸지 않는다(정밀도) — 문자열.
 * 기존 MariaDB MCP의 BigInt 직렬화 오류가 이 경로에서 생기지 않게 하는 곳.
 */
export function toCell(v: unknown): unknown {
  if (v === null || v === undefined) return null;
  if (typeof v === 'bigint') return v.toString();
  if (typeof v === 'string') return clip(v);
  if (typeof v === 'number' || typeof v === 'boolean') return v;
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? String(v) : v.toISOString();
  if (v instanceof Uint8Array) return `<binary ${v.byteLength} bytes>`; // Buffer 포함
  try {
    return clip(JSON.stringify(v) ?? String(v));
  } catch {
    return clip(String(v));
  }
}

/** 도구 결과 텍스트 — 행 상한·전체 길이 상한을 넘으면 잘라 truncated 표시. */
export function formatResult(r: QueryResult, maxRows: number = DB_LIMITS.maxRows): string {
  let truncated = r.truncated || r.rows.length > maxRows;
  let rows = r.rows.slice(0, maxRows).map((row) => row.map(toCell));
  const render = () => JSON.stringify({ columns: r.columns, rows, rowCount: rows.length, truncated });
  let text = render();
  while (text.length > DB_LIMITS.maxResultChars && rows.length > 0) {
    const keep = Math.floor(rows.length * (DB_LIMITS.maxResultChars / text.length));
    rows = rows.slice(0, Math.min(rows.length - 1, Math.max(0, keep)));
    truncated = true;
    text = render();
  }
  return text;
}
```

- [ ] **Step 5: adapters.ts**

`src/sdk/db/adapters.ts`:
```ts
import type { DbConnectionRef } from '../../types.js';
import { stripSql } from '../sqlReadOnly.js';
import { DB_LIMITS } from './limits.js';
import type { QueryResult } from './result.js';

/**
 * 질문 세션 DB 도구의 종류별 읽기 전용 세션 (스펙 2026-10-02 §6.2).
 * 모든 run: 읽기 전용 트랜잭션 시작 → 사용자 SQL(끝 ';' 제거) → 성공·실패와 무관하게 ROLLBACK.
 * 한 세션의 run은 직렬화한다 — CLI가 같은 서버 도구를 병렬 호출해도 한 커넥션에 트랜잭션이 겹치지 않게.
 * ⚠️ ref.password는 드라이버 설정 객체에만 넣는다. 오류 메시지는 dbMcp.safeMessage를 거쳐 나간다.
 */
export interface DbSession {
  /** params는 바인딩 파라미터(카탈로그 조회 전용). maxRows를 넘는 행은 버리고 truncated. */
  run(sql: string, params?: unknown[], maxRows?: number): Promise<QueryResult>;
  close(): Promise<void>;
}

/* 드라이버 최소 구조 타입 — 타입 패키지 없이 컴파일하고, 테스트가 가짜를 주입한다. */
export interface PgClient {
  on(event: 'error', cb: (e: Error) => void): unknown;
  connect(): Promise<unknown>;
  query(q: { text: string; values?: unknown[]; rowMode: 'array' }): Promise<{ fields?: Array<{ name: string }>; rows?: unknown[][] }>;
  end(): Promise<void>;
}
export interface PgModule {
  Client: new (cfg: Record<string, unknown>) => PgClient;
}
export interface MysqlQuery {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  on(event: string, cb: (...args: any[]) => void): MysqlQuery;
}
export interface MysqlConnection {
  query(opts: { sql: string; values?: unknown[]; rowsAsArray?: boolean; timeout?: number }): MysqlQuery;
  on(event: 'error', cb: (e: Error) => void): unknown;
  end(cb?: (e?: Error) => void): void;
  destroy(): void;
}
export interface MysqlModule {
  createConnection(cfg: Record<string, unknown>): MysqlConnection;
}
export interface OracleConnection {
  callTimeout: number;
  execute(sql: string, binds: unknown[], opts?: Record<string, unknown>): Promise<{ metaData?: Array<{ name: string }>; rows?: unknown[][] }>;
  rollback(): Promise<void>;
  close(): Promise<void>;
}
export interface OracleModule {
  getConnection(cfg: Record<string, unknown>): Promise<OracleConnection>;
  OUT_FORMAT_ARRAY: unknown;
  STRING: unknown;
  DB_TYPE_NUMBER: unknown;
  DB_TYPE_CLOB: unknown;
  DB_TYPE_NCLOB: unknown;
  DB_TYPE_DATE: unknown;
  DB_TYPE_TIMESTAMP: unknown;
  DB_TYPE_TIMESTAMP_TZ: unknown;
  DB_TYPE_TIMESTAMP_LTZ: unknown;
}

export interface DriverLoader {
  pg(): Promise<PgModule>;
  mysql(): Promise<MysqlModule>;
  oracle(): Promise<OracleModule>;
}

/** 변수 모듈명 동적 import — 타입 패키지 없이 컴파일되고, 설치가 빠지면 그 도구 호출만 실패한다. */
export async function loadDriver<T>(name: string): Promise<T> {
  try {
    const mod = (await import(name)) as { default?: T };
    return (mod.default ?? mod) as T;
  } catch {
    throw new Error(`DB 드라이버를 불러올 수 없습니다(${name}) — 인터뷰 서비스에서 npm ci를 확인하세요`);
  }
}

export const realDrivers: DriverLoader = {
  pg: () => loadDriver<PgModule>('pg'),
  mysql: () => loadDriver<MysqlModule>('mysql2'),
  oracle: () => loadDriver<OracleModule>('oracledb'),
};

/** sqlReadOnly가 끝 ';' 하나를 허용한다 — Oracle은 ';'가 있으면 ORA-00933, 커서 DECLARE 안에서도 문법 오류. */
export function withoutTrailingSemicolon(sql: string): string {
  return sql.trim().replace(/;\s*$/, '').trimEnd();
}

function limited(columns: string[], rows: unknown[][], maxRows: number): QueryResult {
  return { columns, rows: rows.slice(0, maxRows), truncated: rows.length > maxRows };
}

function serialized(s: DbSession): DbSession {
  let chain: Promise<unknown> = Promise.resolve();
  return {
    run(sql, params, maxRows) {
      const p = chain.then(() => s.run(sql, params, maxRows));
      chain = p.catch(() => undefined);
      return p;
    },
    close: () => chain.then(() => s.close()),
  };
}

export async function openSession(ref: DbConnectionRef, drivers: DriverLoader = realDrivers): Promise<DbSession> {
  switch (ref.dbType) {
    case 'POSTGRESQL':
      return openPostgres(ref, drivers);
    case 'ORACLE':
      return openOracle(ref, drivers);
    default:
      return openMysql(ref, drivers); // MYSQL, MARIADB
  }
}

// ── PostgreSQL ────────────────────────────────────────────────────────────

const PG_CURSOR_LEADING = new Set(['SELECT', 'WITH', 'VALUES', 'TABLE']);

function firstWord(sql: string): string {
  const stripped = stripSql(sql, 'postgres');
  const body = typeof stripped === 'string' ? stripped : sql;
  return (/[A-Za-z_]+/.exec(body)?.[0] ?? '').toUpperCase();
}

async function openPostgres(ref: DbConnectionRef, drivers: DriverLoader): Promise<DbSession> {
  const pg = await drivers.pg();
  const client = new pg.Client({
    host: ref.host,
    port: ref.port,
    database: ref.database,
    user: ref.username,
    password: ref.password,
    connectionTimeoutMillis: DB_LIMITS.connectTimeoutMs,
    application_name: 'netismaker-question',
    options: '-c default_transaction_read_only=on',
  });
  client.on('error', () => undefined); // 유휴 중 끊김 — 리스너가 없으면 프로세스가 죽는다. 다음 run이 오류로 드러낸다.
  await client.connect();
  const q = (text: string, values?: unknown[]) => client.query({ text, values, rowMode: 'array' });
  return serialized({
    async run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      const body = withoutTrailingSemicolon(sql);
      await q('BEGIN TRANSACTION READ ONLY');
      try {
        await q(`SET LOCAL statement_timeout = ${DB_LIMITS.statementTimeoutMs}`);
        // 행 수 상한을 DB 쪽에서 — 큰 테이블 SELECT *가 메모리로 다 오지 않게. DECLARE는 바인딩 파라미터를
        // 받지 못하므로 params가 있는 조회(카탈로그 — 자체 LIMIT)와 SHOW/EXPLAIN은 직접 실행한다.
        let r: { fields?: Array<{ name: string }>; rows?: unknown[][] };
        if (params.length === 0 && PG_CURSOR_LEADING.has(firstWord(body))) {
          await q(`DECLARE netis_q NO SCROLL CURSOR FOR ${body}`);
          r = await q(`FETCH FORWARD ${maxRows + 1} FROM netis_q`);
        } else {
          r = await q(body, params);
        }
        return limited((r.fields ?? []).map((f) => f.name), r.rows ?? [], maxRows);
      } finally {
        await q('ROLLBACK').catch(() => undefined);
      }
    },
    close: () => client.end(),
  });
}

// ── MySQL · MariaDB ───────────────────────────────────────────────────────

function mysqlControl(c: MysqlConnection, sql: string): Promise<void> {
  return new Promise((resolve, reject) => {
    c.query({ sql }).on('error', reject).on('end', () => resolve());
  });
}

function mysqlRows(
  c: MysqlConnection,
  sql: string,
  values: unknown[],
  maxRows: number,
): Promise<{ columns: string[]; rows: unknown[][]; overflow: boolean }> {
  return new Promise((resolve, reject) => {
    const columns: string[] = [];
    const rows: unknown[][] = [];
    let done = false;
    c.query({ sql, values, rowsAsArray: true, timeout: DB_LIMITS.statementTimeoutMs })
      .on('fields', (fields: Array<{ name: string }> | undefined) => {
        if (columns.length === 0 && Array.isArray(fields)) columns.push(...fields.map((f) => f.name));
      })
      .on('result', (row: unknown) => {
        if (done || !Array.isArray(row)) return; // OK 패킷(비행 결과)은 무시
        if (rows.length >= maxRows) {
          done = true;
          resolve({ columns, rows, overflow: true });
          return;
        }
        rows.push(row);
      })
      .on('error', (e: Error) => {
        if (done) return;
        done = true;
        reject(e);
      })
      .on('end', () => {
        if (done) return;
        done = true;
        resolve({ columns, rows, overflow: false });
      });
  });
}

async function openMysql(ref: DbConnectionRef, drivers: DriverLoader): Promise<DbSession> {
  const mysql = await drivers.mysql();
  let conn: MysqlConnection | null = null;
  const drop = (c: MysqlConnection) => {
    c.destroy();
    if (conn === c) conn = null;
  };
  const connect = async (): Promise<MysqlConnection> => {
    const c = mysql.createConnection({
      host: ref.host,
      port: ref.port,
      database: ref.database,
      user: ref.username,
      password: ref.password,
      connectTimeout: DB_LIMITS.connectTimeoutMs,
      multipleStatements: false,
      supportBigNumbers: true,
      bigNumberStrings: true,
      dateStrings: true,
      flags: ['-LOCAL_FILES'], // LOAD DATA LOCAL INFILE 차단
    });
    c.on('error', () => undefined); // 유휴 중 끊김 — 리스너가 없으면 프로세스가 죽는다.
    try {
      await mysqlControl(c, 'SET SESSION TRANSACTION READ ONLY');
    } catch (e) {
      c.destroy();
      throw e;
    }
    // 서버 측 문장 타임아웃 — MySQL(5.7.8+, SELECT만)과 MariaDB(10.1+)의 변수 이름이 달라 둘 다 시도, 모르는 변수 오류는 무시.
    await mysqlControl(c, `SET SESSION max_execution_time = ${DB_LIMITS.statementTimeoutMs}`).catch(() => undefined);
    await mysqlControl(c, `SET SESSION max_statement_time = ${DB_LIMITS.statementTimeoutMs / 1000}`).catch(() => undefined);
    return c;
  };
  return serialized({
    async run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      const c = conn ?? (conn = await connect());
      try {
        await mysqlControl(c, 'START TRANSACTION READ ONLY');
      } catch (e) {
        drop(c);
        throw e;
      }
      let r: { columns: string[]; rows: unknown[][]; overflow: boolean };
      try {
        r = await mysqlRows(c, withoutTrailingSemicolon(sql), params, maxRows);
      } catch (e) {
        await mysqlControl(c, 'ROLLBACK').catch(() => drop(c));
        throw e;
      }
      // 상한 초과: 남은 행 전송을 끊으려고 커넥션째 버린다(트랜잭션도 함께 사라짐). 다음 run이 다시 접속한다.
      if (r.overflow) drop(c);
      else await mysqlControl(c, 'ROLLBACK').catch(() => drop(c));
      return { columns: r.columns, rows: r.rows, truncated: r.overflow };
    },
    async close() {
      const c = conn;
      conn = null;
      if (c) await new Promise<void>((resolve) => c.end(() => resolve()));
    },
  });
}

// ── Oracle (thin — initOracleClient를 부르지 않는다) ──────────────────────

async function openOracle(ref: DbConnectionRef, drivers: DriverLoader): Promise<DbSession> {
  const ora = await drivers.oracle();
  const conn = await ora.getConnection({
    user: ref.username,
    password: ref.password,
    connectString: `${ref.host}:${ref.port}/${ref.database}`,
    connectTimeout: Math.ceil(DB_LIMITS.connectTimeoutMs / 1000), // 초 단위
  });
  conn.callTimeout = DB_LIMITS.statementTimeoutMs;
  const asString = new Set([
    ora.DB_TYPE_NUMBER, ora.DB_TYPE_CLOB, ora.DB_TYPE_NCLOB, ora.DB_TYPE_DATE,
    ora.DB_TYPE_TIMESTAMP, ora.DB_TYPE_TIMESTAMP_TZ, ora.DB_TYPE_TIMESTAMP_LTZ,
  ]);
  const fetchTypeHandler = (m: { dbType?: unknown }) => (asString.has(m.dbType) ? { type: ora.STRING } : undefined);
  return serialized({
    async run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      await conn.rollback(); // SET TRANSACTION은 트랜잭션의 첫 문장이어야 한다
      await conn.execute('SET TRANSACTION READ ONLY', []);
      try {
        const r = await conn.execute(withoutTrailingSemicolon(sql), params, {
          outFormat: ora.OUT_FORMAT_ARRAY,
          maxRows: maxRows + 1,
          fetchTypeHandler,
        });
        return limited((r.metaData ?? []).map((m) => m.name), r.rows ?? [], maxRows);
      } finally {
        await conn.rollback().catch(() => undefined);
      }
    },
    close: () => conn.close(),
  });
}
```

- [ ] **Step 6: catalog.ts**

`src/sdk/db/catalog.ts`:
```ts
import type { SqlDialect } from '../sqlReadOnly.js';
import { DB_LIMITS } from './limits.js';

/**
 * list_tables·describe_table용 방언별 SQL (스펙 2026-10-02 §6.2).
 * 사용자 값(schema·table)은 바인딩 파라미터로만 — 문자열 조립 금지. schema가 null이면 기본 스키마.
 * 파라미터 순서: tables = [schema], columns·indexes = [schema, table].
 */
export type CatalogKind = 'tables' | 'columns' | 'indexes';

const LIMIT = DB_LIMITS.maxCatalogRows + 1;

const SQL: Record<SqlDialect, Record<CatalogKind, string>> = {
  postgres: {
    tables:
      `SELECT table_schema, table_name, table_type FROM information_schema.tables ` +
      `WHERE ($1::text IS NULL AND table_schema NOT IN ('pg_catalog', 'information_schema') AND table_schema NOT LIKE 'pg_toast%') ` +
      `OR table_schema = $1 ORDER BY table_schema, table_name LIMIT ${LIMIT}`,
    columns:
      `SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, ` +
      `col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position) AS comment ` +
      `FROM information_schema.columns c WHERE c.table_schema = COALESCE($1::text, current_schema()) AND c.table_name = $2 ` +
      `ORDER BY c.ordinal_position LIMIT ${LIMIT}`,
    indexes:
      `SELECT indexname, indexdef FROM pg_indexes ` +
      `WHERE schemaname = COALESCE($1::text, current_schema()) AND tablename = $2 ORDER BY indexname LIMIT ${LIMIT}`,
  },
  mysql: {
    tables:
      `SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.TABLES ` +
      `WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) ORDER BY TABLE_NAME LIMIT ${LIMIT}`,
    columns:
      `SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT FROM information_schema.COLUMNS ` +
      `WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION LIMIT ${LIMIT}`,
    indexes:
      `SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS ` +
      `WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX LIMIT ${LIMIT}`,
  },
  // Oracle은 비인용 식별자가 대문자로 저장된다 — 이름을 UPPER로 비교(따옴표로 만든 소문자 이름은 못 찾는다, 스펙 §6.2).
  // 행 상한은 execute maxRows가 건다.
  oracle: {
    tables:
      `SELECT owner, object_name, object_type FROM all_objects ` +
      `WHERE owner = NVL(UPPER(:1), USER) AND object_type IN ('TABLE', 'VIEW') ORDER BY object_name`,
    columns:
      `SELECT c.column_name, c.data_type, c.data_length, c.nullable, c.data_default, m.comments FROM all_tab_columns c ` +
      `LEFT JOIN all_col_comments m ON m.owner = c.owner AND m.table_name = c.table_name AND m.column_name = c.column_name ` +
      `WHERE c.owner = NVL(UPPER(:1), USER) AND c.table_name = UPPER(:2) ORDER BY c.column_id`,
    indexes:
      `SELECT i.index_name, i.uniqueness, ic.column_position, ic.column_name FROM all_indexes i ` +
      `JOIN all_ind_columns ic ON ic.index_owner = i.owner AND ic.index_name = i.index_name ` +
      `WHERE i.table_owner = NVL(UPPER(:1), USER) AND i.table_name = UPPER(:2) ORDER BY i.index_name, ic.column_position`,
  },
};

export function catalogSql(
  dialect: SqlDialect,
  kind: CatalogKind,
  schema: string | null,
  table?: string,
): { sql: string; params: unknown[] } {
  return { sql: SQL[dialect][kind], params: kind === 'tables' ? [schema] : [schema, table ?? ''] };
}
```

- [ ] **Step 7: dbMcp.ts**

`src/sdk/dbMcp.ts`:
```ts
import {
  createSdkMcpServer,
  tool,
  type McpSdkServerConfigWithInstance,
  type SdkMcpToolDefinition,
} from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import type { DbConnectionRef, DbType, InterviewClaimResponse } from '../types.js';
import { catalogSql } from './db/catalog.js';
import { DB_LIMITS } from './db/limits.js';
import { formatResult } from './db/result.js';
import { openSession as realOpenSession, type DbSession } from './db/adapters.js';
import { checkReadOnlySql, type SqlDialect } from './sqlReadOnly.js';

/**
 * 질문 세션 내장 DB MCP (스펙 2026-10-02 §6.2). 연결마다 SDK MCP 서버 `db-<id>`를 이 프로세스 안에 만든다.
 * SDK는 type:'sdk' 서버를 CLI에 {type:'sdk', name}으로만 넘기므로(스펙 §2.1) 접속정보가 명령줄·파일·자식 env로 나가지 않는다.
 * DB 접속은 첫 도구 호출 때(lazy), 러너가 턴 종료 finally에서 close()한다.
 */

const SERVER_NAME = /^db-\d+$/;
// eslint-disable-next-line no-control-regex
const IDENT = z.string().min(1).max(128).regex(/^[^\u0000-\u001f\u007f]+$/, '제어문자는 쓸 수 없습니다');

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };
const textResult = (text: string, isError = false): ToolResult => ({
  content: [{ type: 'text', text }],
  ...(isError ? { isError: true } : {}),
});

export interface PreparedDbMcp {
  servers: Record<string, McpSdkServerConfigWithInstance>;
  /** 서버별 도구 정의 — 테스트·진단용(핸들러 직접 호출). */
  tools: Record<string, SdkMcpToolDefinition[]>;
  dialects: Record<string, SqlDialect>;
  labels: Array<{ serverName: string; label: string }>;
  notices: string[];
  close(): Promise<void>;
}

export interface DbMcpDeps {
  /** 테스트용 주입 — 기본 adapters.openSession. */
  openSession?: (ref: DbConnectionRef) => Promise<DbSession>;
}

export function dialectFor(t: DbType): SqlDialect {
  if (t === 'POSTGRESQL') return 'postgres';
  if (t === 'ORACLE') return 'oracle';
  return 'mysql'; // MYSQL, MARIADB
}

/** 드라이버 오류 메시지에서 비밀번호를 지우고 500자로 자른다. 도구 결과·로그로 나가는 모든 오류 문구가 거친다. */
export function safeMessage(e: unknown, ref: Pick<DbConnectionRef, 'password'>): string {
  let msg = e instanceof Error ? e.message : String(e);
  if (ref.password) msg = msg.split(ref.password).join('****');
  return msg.length > 500 ? `${msg.slice(0, 500)}…` : msg;
}

/** 한 연결의 도구 3개. getSession은 lazy 접속(첫 호출 때 연다). */
export function dbToolsFor(
  ref: DbConnectionRef,
  dialect: SqlDialect,
  getSession: () => Promise<DbSession>,
): SdkMcpToolDefinition[] {
  const guarded = async (fn: (s: DbSession) => Promise<string>): Promise<ToolResult> => {
    try {
      return textResult(await fn(await getSession()));
    } catch (e) {
      return textResult(`DB 오류: ${safeMessage(e, ref)}`, true);
    }
  };
  return [
    tool(
      'query',
      `${ref.label} 읽기 전용 조회. SELECT 계열 단일 문장만 실행되고, 결과는 최대 ${DB_LIMITS.maxRows}행, ` +
        `${DB_LIMITS.statementTimeoutMs / 1000}초 제한이다. 큰 테이블은 WHERE·LIMIT으로 좁혀서 조회할 것.`,
      { sql: z.string().min(1).max(100_000) },
      async ({ sql }) => {
        // 게이트(permissions.ts)가 1차, 여기는 우회 방지(Review Focus 6). 통과해도 어댑터가 READ ONLY 트랜잭션으로 실행한다.
        const check = checkReadOnlySql(sql, dialect);
        if (!check.ok) {
          return textResult(`읽기 전용 도구입니다 — ${check.reason}. SELECT 계열 단일 문장만 실행할 수 있습니다.`, true);
        }
        return guarded(async (s) => formatResult(await s.run(sql, [], DB_LIMITS.maxRows)));
      },
    ),
    tool(
      'list_tables',
      `${ref.label}의 테이블·뷰 목록. schema를 생략하면 기본 스키마(PostgreSQL은 시스템 스키마 제외 전체).`,
      { schema: IDENT.optional() },
      async ({ schema }) =>
        guarded(async (s) => {
          const c = catalogSql(dialect, 'tables', schema ?? null);
          return formatResult(await s.run(c.sql, c.params, DB_LIMITS.maxCatalogRows), DB_LIMITS.maxCatalogRows);
        }),
    ),
    tool(
      'describe_table',
      `${ref.label}의 테이블 컬럼(이름·타입·널·기본값·주석)과 인덱스.`,
      { table: IDENT, schema: IDENT.optional() },
      async ({ table, schema }) =>
        guarded(async (s) => {
          const c = catalogSql(dialect, 'columns', schema ?? null, table);
          const i = catalogSql(dialect, 'indexes', schema ?? null, table);
          const cols = await s.run(c.sql, c.params, DB_LIMITS.maxCatalogRows);
          const idx = await s.run(i.sql, i.params, DB_LIMITS.maxCatalogRows);
          return `컬럼:\n${formatResult(cols, DB_LIMITS.maxCatalogRows)}\n인덱스:\n${formatResult(idx, DB_LIMITS.maxCatalogRows)}`;
        }),
    ),
  ] as SdkMcpToolDefinition[];
}

export function createDbMcp(claim: InterviewClaimResponse, deps: DbMcpDeps = {}): PreparedDbMcp {
  const refs = Array.isArray(claim.dbConnections) ? claim.dbConnections : [];
  const notices = Array.isArray(claim.dbNotices) ? [...claim.dbNotices] : [];
  const open = deps.openSession ?? ((ref: DbConnectionRef) => realOpenSession(ref));
  const opened: Array<{ serverName: string; session: Promise<DbSession> }> = [];
  const prepared: PreparedDbMcp = {
    servers: {},
    tools: {},
    dialects: {},
    labels: [],
    notices,
    async close() {
      for (const o of opened) {
        try {
          await (await o.session).close();
        } catch (e) {
          // 접속 자체가 실패했거나 닫기 실패 — 턴 결과에는 영향 없음. 서버 이름만 남긴다(메시지에 접속정보가 섞일 수 있다).
          // eslint-disable-next-line no-console
          console.warn(`[dbMcp] 커넥션 종료 건너뜀: ${o.serverName} (${(e as { code?: string }).code ?? 'error'})`);
        }
      }
    },
  };
  for (const ref of refs) {
    if (!SERVER_NAME.test(ref.serverName)) {
      notices.push(`잘못된 DB 서버 이름이라 건너뜁니다: ${ref.label}`);
      continue;
    }
    const dialect = dialectFor(ref.dbType);
    let current: Promise<DbSession> | null = null;
    const getSession = (): Promise<DbSession> => {
      if (!current) {
        const p = open(ref);
        current = p;
        opened.push({ serverName: ref.serverName, session: p });
        p.catch(() => {
          if (current === p) current = null; // 접속 실패 — 다음 호출이 다시 시도
        });
      }
      return current;
    };
    const tools = dbToolsFor(ref, dialect, getSession);
    prepared.servers[ref.serverName] = createSdkMcpServer({ name: ref.serverName, version: '1.0.0', tools });
    prepared.tools[ref.serverName] = tools;
    prepared.dialects[ref.serverName] = dialect;
    prepared.labels.push({ serverName: ref.serverName, label: ref.label });
  }
  return prepared;
}
```
주의: 접속 실패한 프라미스도 `opened`에 남는다 — `close()`가 `await`에서 실패를 잡아 경고만 남긴다(테스트 "close 실패는 삼킨다"). `tool()`의 zod 검증은 SDK MCP 계층에서 일어나므로 핸들러를 직접 부르는 테스트는 검증을 거치지 않는다(의도 — 검증은 SDK 책임).

- [ ] **Step 8: 통과 확인 + 타입 검사**

```bash
cd netismaker-interview-service && npx vitest run test/dbResult.test.ts test/dbAdapters.test.ts test/dbMcp.test.ts && npx tsc -p tsconfig.json --noEmit
```
Expected: 전부 PASS, tsc 오류 0. `tool()` 반환 타입이 `SdkMcpToolDefinition<…>`라 배열 캐스트가 필요 없으면 `as SdkMcpToolDefinition[]`를 지운다.

- [ ] **Step 9: 로컬 PostgreSQL 실측(스펙 §11-2)** — 어댑터를 실제 DB에 붙여 본다. 접속값은 `public.env`의 DB 항목에서 가져오되 **출력·로그에 비밀번호를 남기지 않는다**.

`netismaker-interview-service/scripts/spikeDbAdapter.ts`:
```ts
/**
 * 스파이크(스펙 2026-10-02 §11-2·5) — openSession을 실제 DB에 붙여 읽기 전용·행 상한을 확인한다. 일회성 도구.
 * 실행(Git Bash): SPIKE_DB_TYPE=POSTGRESQL SPIKE_DB_HOST=localhost SPIKE_DB_PORT=5432 SPIKE_DB_NAME=<DB> SPIKE_DB_USER=<계정> \
 *   bash -c 'read -s SPIKE_DB_PASSWORD; export SPIKE_DB_PASSWORD; node --import tsx scripts/spikeDbAdapter.ts'
 */
import { openSession } from '../src/sdk/db/adapters.js';
import type { DbType } from '../src/types.js';

const e = process.env;
const ref = {
  serverName: 'db-0', label: 'spike', dbType: (e.SPIKE_DB_TYPE ?? 'POSTGRESQL') as DbType, host: e.SPIKE_DB_HOST ?? 'localhost',
  port: Number(e.SPIKE_DB_PORT ?? 5432), database: e.SPIKE_DB_NAME ?? '', username: e.SPIKE_DB_USER ?? '', password: e.SPIKE_DB_PASSWORD ?? '',
};
const mask = (m: string) => (ref.password ? m.split(ref.password).join('****') : m);
const s = await openSession(ref);
const probes: Array<[string, string]> = ref.dbType === 'POSTGRESQL'
  ? [['SELECT 1 AS n', '1행'], ['SELECT generate_series(1, 500) AS n', '200행 + truncated'],
     ['SHOW default_transaction_read_only', 'on'], ['CREATE TEMP TABLE spike_x(a int)', 'read-only 오류']]
  : ref.dbType === 'ORACLE'
    ? [['SELECT 1 AS n FROM dual', '1행'], ['SELECT level AS n FROM dual CONNECT BY level <= 500', '200행 + truncated'],
       ['CREATE TABLE spike_x(a int)', '오류(1선이 막는 대상 — 2선만으로는 DDL이 실행될 수 있음: 테스트 계정에서만)']]
    : [['SELECT 1 AS n', '1행'], ['SELECT @@session.transaction_read_only AS ro', '1'],
       ['SELECT 1 FROM information_schema.COLUMNS a, information_schema.COLUMNS b LIMIT 500', '200행 + truncated → 재접속'],
       ['SELECT 2 AS n', '1행(재접속 확인)']];
for (const [sql, expect] of probes) {
  try {
    const r = await s.run(sql);
    console.log(`[ok] ${sql} → rows=${r.rows.length} truncated=${r.truncated} first=${JSON.stringify(r.rows[0])}  (기대: ${expect})`);
  } catch (err) {
    console.log(`[err] ${sql} → ${mask(err instanceof Error ? err.message : String(err))}  (기대: ${expect})`);
  }
}
await s.close();
```
⚠️ Oracle의 `CREATE TABLE` 프로브는 **읽기 전용이 아닌 계정으로는 돌리지 말 것**(스펙 §8.2 한계 — 2선은 DDL을 못 막는다). 이 스크립트는 어댑터를 직접 부르므로 1선(SQL 검사)을 거치지 않는다.

```bash
cd /c/Users/mic/NetisMaker/commanCenter/.claude/worktrees/question-db-mcp/netismaker-interview-service
SPIKE_DB_TYPE=POSTGRESQL SPIKE_DB_HOST=localhost SPIKE_DB_PORT=5432 SPIKE_DB_NAME=<DB명> SPIKE_DB_USER=<계정> \
  bash -c 'read -s SPIKE_DB_PASSWORD; export SPIKE_DB_PASSWORD; node --import tsx scripts/spikeDbAdapter.ts'
```
Expected: 4줄이 각 기대와 일치 — 특히 `CREATE TEMP TABLE`이 `[err] … read-only transaction`. 어긋나면 다음 Task로 넘어가지 말고 보고한다. 결과를 스펙 §11-2 끝에 `→ 2026-10-02 확인: …`으로 적는다.

- [ ] **Step 10: Commit**

```bash
git add netismaker-interview-service/src/types.ts netismaker-interview-service/src/sdk/db netismaker-interview-service/src/sdk/dbMcp.ts netismaker-interview-service/scripts/spikeDbAdapter.ts netismaker-interview-service/test/dbResult.test.ts netismaker-interview-service/test/dbAdapters.test.ts netismaker-interview-service/test/dbMcp.test.ts docs/superpowers/specs/2026-10-02-question-db-mcp-design.md
git commit -m "feat(interview): 내장 DB MCP — 읽기 전용 어댑터(PG·MySQL/MariaDB·Oracle)·카탈로그·결과 상한

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 세션 옵션·러너에 연결

**Files:**
- Modify: `netismaker-interview-service/src/sdk/sessionOptions.ts`
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts` (`RunnerDeps`, `questionPromptFor`, `runQuestionTurn`, 새 `dbSection`)
- Test: `netismaker-interview-service/test/sessionOptions.test.ts`, `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: `createDbMcp`, `PreparedDbMcp`(Task 11), `buildCanUseTool(..., dbServers)`(Task 10)
- Produces: `SessionOptionsInput.dbMcpServers?: Record<string, unknown>`, `SessionOptionsInput.dbDialects?: Record<string, SqlDialect>`; `RunnerDeps.createDbMcp?: typeof createDbMcp`; `export function dbSection(db: Pick<PreparedDbMcp, 'labels' | 'notices'>): string`

- [ ] **Step 1: 실패하는 테스트 작성**

`test/sessionOptions.test.ts` 끝에:
```ts
describe('buildOptions — DB MCP (스펙 2026-10-02 §6.4)', () => {
  const dbServer = { type: 'sdk', name: 'db-7', instance: {} };

  it('db 서버를 마지막에 머지(같은 이름이면 db가 이긴다), QUESTION 사전승인은 여전히 없음', () => {
    const o = buildOptions({
      ...base, claudeSessionId: null, sessionKind: 'QUESTION',
      mcpsBase: { 'db-7': { type: 'http', url: 'http://fake' }, obsidian: { type: 'http', url: 'http://o' } },
      dbMcpServers: { 'db-7': dbServer },
      dbDialects: { 'db-7': 'mysql' },
    });
    expect((o.mcpServers as Record<string, unknown>)['db-7']).toBe(dbServer); // 인스턴스를 복사하지 않고 그대로
    expect(Object.keys(o.mcpServers as object)).toContain('obsidian');
    expect(o.allowedTools).toEqual([]);
  });

  it('canUseTool이 dbDialects를 받아 DB 쓰기를 막는다', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', dbMcpServers: { 'db-7': dbServer }, dbDialects: { 'db-7': 'mysql' } });
    const gate = o.canUseTool as (n: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
    expect((await gate('mcp__db-7__query', { sql: 'SELECT 1' })).behavior).toBe('allow');
    expect((await gate('mcp__db-7__query', { sql: 'DELETE FROM t' })).behavior).toBe('deny');
  });
});
```
(`canUseTool`의 실제 호출 시그니처가 3번째 인자(옵션)를 요구하면 테스트에서 `{ signal: new AbortController().signal }`를 넘긴다 — 같은 파일의 기존 canUseTool 테스트 방식을 따른다.)

`test/interviewRunner.test.ts` — `describe('InterviewRunner kind=QUESTION …')` 블록 안 끝에:
```ts
  describe('DB MCP (스펙 2026-10-02 §6.4)', () => {
    const dbClaim = {
      ...questionClaim,
      dbConnections: [{ serverName: 'db-7', label: '운영 DB (MySQL)', dbType: 'MYSQL', host: 'h', port: 3306, database: 'app', username: 'u', password: 'Pw-secret' }],
      dbNotices: ['DB 연결 #9를 사용할 수 없습니다 — 삭제되었거나 비활성화됨'],
    } as never;
    const preparedFor = () => ({
      servers: { 'db-7': { type: 'sdk' as const, name: 'db-7', instance: {} } },
      tools: {},
      dialects: { 'db-7': 'mysql' as const },
      labels: [{ serverName: 'db-7', label: '운영 DB (MySQL)' }],
      notices: ['DB 연결 #9를 사용할 수 없습니다 — 삭제되었거나 비활성화됨'],
      close: vi.fn().mockResolvedValue(undefined),
    });

    it('DB 서버를 붙이고 프롬프트 앞에 서버↔DB 매핑·안내를 넣고, 끝나면 close한다', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const prepared = preparedFor();
      const createDbMcp = vi.fn().mockReturnValue(prepared);
      const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, createDbMcp } as never);
      await runner.run(dbClaim);

      expect(createDbMcp).toHaveBeenCalledWith(dbClaim);
      expect((captured.options.mcpServers as Record<string, unknown>)['db-7']).toBe(prepared.servers['db-7']);
      expect(captured.prompt.startsWith('사용 가능한 DB 도구')).toBe(true);
      expect(captured.prompt).toContain('mcp__db-7__query / list_tables / describe_table : 운영 DB (MySQL)');
      expect(captured.prompt).toContain('참고: DB 연결 #9를 사용할 수 없습니다');
      expect(captured.prompt).toContain('로그인은 어디서 처리되나요?');
      expect(captured.prompt).not.toContain('Pw-secret');
      expect(prepared.close).toHaveBeenCalledTimes(1);
    });

    it('closes_db_sessions_when_query_throws', async () => {
      // Review Focus 5
      const client = makeClient();
      const fakeQuery = vi.fn(() => { throw new Error('boom'); });
      const prepared = preparedFor();
      const runner = new InterviewRunner(client as never, fakeQuery as never,
        { ...deps, createDbMcp: vi.fn().mockReturnValue(prepared) } as never);
      await runner.run(dbClaim);
      expect(prepared.close).toHaveBeenCalledTimes(1);
      expect(client.fail).toHaveBeenCalled();
    });

    it('DB 연결이 없으면 프롬프트·옵션이 기존과 같다', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
      await runner.run(questionClaim);
      expect(captured.prompt.startsWith('당신은')).toBe(true);
      expect(Object.keys((captured.options.mcpServers ?? {}) as object)).not.toContain('db-7');
    });
  });
```

- [ ] **Step 2: 실패 확인**

```bash
cd netismaker-interview-service && npx vitest run test/sessionOptions.test.ts test/interviewRunner.test.ts
```
Expected: 새 테스트 FAIL.

- [ ] **Step 3: sessionOptions.ts**

- import 추가: `import type { SqlDialect } from './sqlReadOnly.js';`
- `SessionOptionsInput`에:
```ts
  /**
   * 이번 턴의 내장 DB MCP 서버(스펙 2026-10-02 §6.4) — dbMcp.createDbMcp 결과({type:'sdk', name, instance}).
   * SDK가 CLI에는 이름만 넘긴다. merge 순서 base → extras → db(마지막 우선).
   */
  dbMcpServers?: Record<string, unknown>;
  /** DB 서버별 SQL 방언 — QUESTION 게이트가 query를 검사할 때 쓴다(permissions.ts dbToolGate). */
  dbDialects?: Record<string, SqlDialect>;
```
- `buildOptions` 첫 줄 merged를:
```ts
  const merged: Record<string, unknown> = {
    ...(input.mcpsBase ?? {}),
    ...(toMcpServers(input.mcpsExtra) ?? {}),
    ...(input.dbMcpServers ?? {}),
  };
```
- `canUseTool:` 줄을:
```ts
    canUseTool: buildCanUseTool(input.workDir, input.sessionKind ?? 'INTERVIEW', input.attachmentRoot ?? null, input.dbDialects ?? {}),
```
- `buildOptions` Javadoc의 `- MCP:` 항목 끝에 한 문장: `질문 세션 내장 DB 서버(dbMcpServers, type:'sdk')는 마지막에 머지 — SDK가 CLI에 이름만 넘기므로 --mcp-config 명령줄에 접속정보가 실리지 않는다. stdio 서버에 접속정보를 넣지 말 것.`

- [ ] **Step 4: interviewRunner.ts**

- import 추가:
```ts
import { createDbMcp, type PreparedDbMcp } from '../sdk/dbMcp.js';
```
- `RunnerDeps`에:
```ts
  /** 테스트용 주입 — 기본 dbMcp.createDbMcp (질문 세션 내장 DB 도구, 스펙 2026-10-02 §6). */
  createDbMcp?: (claim: InterviewClaimResponse) => PreparedDbMcp;
```
- `questionPromptFor` 위에 추가:
```ts
/**
 * DB 도구 안내 (스펙 2026-10-02 §6.4). 서버마다 도구 이름이 같으므로(query 등) 서버↔DB 매핑을 매 턴 알려 준다.
 * 연결도 안내도 없으면 빈 문자열 — 프롬프트가 기존과 같다.
 */
export function dbSection(db: Pick<PreparedDbMcp, 'labels' | 'notices'>): string {
  if (db.labels.length === 0 && db.notices.length === 0) return '';
  const lines: string[] = [];
  if (db.labels.length > 0) {
    lines.push('사용 가능한 DB 도구 (읽기 전용 — SELECT 계열 단일 문장, 최대 200행):');
    for (const l of db.labels) lines.push(`- mcp__${l.serverName}__query / list_tables / describe_table : ${l.label}`);
  }
  for (const n of db.notices) lines.push(`참고: ${n}`);
  return lines.join('\n') + '\n\n';
}
```
- `questionPromptFor` 시그니처를 `async function* questionPromptFor(claim: InterviewClaimResponse, prefix = ''): AsyncIterable<UserTurn>`로 바꾸고, fresh 분기 `yield userTurn(` 첫 인자 맨 앞에 `prefix +`를, resume 분기를:
```ts
    const section = questionAttachmentSection(lastAnswerAttachments(claim), '이 메시지에 첨부된 파일:');
    const body = section ? `${claim.lastAnswer ?? ''}\n\n${section}` : (claim.lastAnswer ?? '');
    yield userTurn(prefix + body);
```
- `runQuestionTurn` 본문 교체:
```ts
    const onActivity = (e: ActivityInput) => poster.push(e);
    const onRateLimit = (info: RateLimitInfo) => this.rateLimits.report(info);
    // 내장 DB MCP(스펙 2026-10-02 §6.4): 서버는 턴마다 새로 만들고(DB 접속은 첫 도구 호출 때),
    // 성공·실패와 무관하게 finally에서 열린 커넥션을 닫는다.
    const db = (this.deps.createDbMcp ?? createDbMcp)(claim);
    try {
      const stream: AsyncIterable<SdkMessage> = this.query({
        prompt: questionPromptFor(claim, dbSection(db)),
        options: buildOptions({
          superpowersPluginPath: this.deps.superpowersPluginPath,
          workDir: claim.workDir,
          claudeCliPath: this.deps.claudeCliPath,
          claudeSessionId: claim.claudeSessionId,
          mcpsExtra: claim.mcpsExtra,
          mcpsBase: this.deps.mcpsBase,
          model: claim.model,
          effort: claim.effort,
          abortController: controller,
          sessionKind: 'QUESTION',
          // 세션 첨부 디렉토리 — Read 게이트의 두 번째 허용 루트 (스펙 2026-09-13 §6). 구버전 Java는 필드 없음 → null.
          attachmentRoot: claim.attachmentRoot ?? null,
          dbMcpServers: db.servers,
          dbDialects: db.dialects,
        }),
      });
      const result = await relay(stream, { onActivity, onRateLimit, workDir: claim.workDir });
      guard.add(result.costUsd);
      // trailing 활동 배치가 답변보다 늦게 도착하지 않도록 확정 POST 전에 큐를 비운다 (인터뷰 경로와 동일).
      await poster.stop();
      await this.client.postQuestion(claim.sessionId, {
        content: result.assistantText,
        claudeSessionId: result.sessionId ?? claim.claudeSessionId ?? '',
        kind: 'question',
        costUsd: result.costUsd,
        inputTokens: result.inputTokens,
        outputTokens: result.outputTokens,
        cacheCreationTokens: result.cacheCreationTokens,
        cacheReadTokens: result.cacheReadTokens,
        // 컨텍스트 스냅샷 (스펙 2026-09-05 §4.2) — 구버전 Java는 미지 필드를 무시한다.
        contextTokens: result.contextTokens,
        contextWindow: result.contextWindow,
      });
    } finally {
      await db.close();
    }
```
`runQuestionTurn`의 기존 본문이 위와 다르면(필드 추가 등) 기존 줄을 그대로 두고 `db` 생성·`prompt` 인자·`dbMcpServers`/`dbDialects` 두 줄·`try/finally`만 얹는다. `db.close()`는 실패를 삼키므로(Task 11) finally에서 원래 예외를 가리지 않는다.

- [ ] **Step 5: 통과 확인 + 전체**

```bash
cd netismaker-interview-service && npx vitest run 2>&1 | tail -8 && npx tsc -p tsconfig.json --noEmit
```
Expected: 실패는 Task 0 기준선의 기존 5건뿐, tsc 오류 0.

- [ ] **Step 6: Commit**

```bash
git add netismaker-interview-service/src/sdk/sessionOptions.ts netismaker-interview-service/src/runner/interviewRunner.ts netismaker-interview-service/test/sessionOptions.test.ts netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat(interview): 질문 턴에 내장 DB MCP 부착 + 프롬프트 안내 + finally close

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: 프론트 — 컴포저블 + 접속정보 다이얼로그

**Files:**
- Create: `frontend/composables/dbConnections.ts`
- Create: `frontend/components/DbConnectionDialog.vue`
- Test: `frontend/test/db-connection-dialog.spec.ts`

**Interfaces:**
- Produces:
  - `type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE'`, `type DbScope = 'REPO' | 'USER'`
  - `interface DbConnectionView { id; scope: DbScope; repoCatalogId; name; dbType: DbType; host; port; databaseName; username; enabled; mine }`, `interface DbConnectionList { enabled: boolean; items: DbConnectionView[] }`, `interface DbConnectionChip { id: number; name: string; dbType: string }`
  - `DB_TYPES: Array<{ value: DbType; label: string; defaultPort: number }>`, `MAX_DB_SELECTION = 3`, `dbTypeLabel(t: string): string`
  - `fetchDbConnections(repoCatalogId: number): Promise<DbConnectionList>`(실패·이상 응답 → `{enabled:false, items:[]}`), `saveDbConnection(input, id?)`, `deleteDbConnection(id)`, `testDbConnection(body)`
  - `<DbConnectionDialog v-model :scope :repo-catalog-id :editing @saved>` — expose `{ form, save, runTest, testResult }`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/test/db-connection-dialog.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import DbConnectionDialog from '../components/DbConnectionDialog.vue'
import { fetchDbConnections } from '../composables/dbConnections'
import { useApiMock } from './mocks/nuxt'

const editing = {
  id: 7, scope: 'USER', repoCatalogId: 1, name: '내 DB', dbType: 'MYSQL', host: 'h', port: 3306,
  databaseName: 'app', username: 'u', enabled: true, mine: true,
}

describe('DbConnectionDialog (스펙 2026-10-02 §7)', () => {
  beforeEach(() => {
    useApiMock.mockReset()
    useApiMock.mockResolvedValue({ id: 9 })
  })

  it('종류를 바꾸면 손대지 않은 포트는 기본값으로, Oracle이면 라벨이 서비스명', async () => {
    const w = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing: null } })
    await flushPromises()
    const vm = w.vm as any
    expect(vm.form.port).toBe(5432)
    vm.form.dbType = 'ORACLE'
    await flushPromises()
    expect(vm.form.port).toBe(1521)
    expect(vm.dbNameLabel).toBe('서비스명')
    vm.onPortInput(1600)
    vm.form.dbType = 'MYSQL'
    await flushPromises()
    expect(vm.form.port).toBe(1600) // 사용자가 고친 포트는 유지
    w.unmount()
  })

  it('새로 저장하면 POST, 수정은 PUT이고 비밀번호는 다시 채우지 않는다', async () => {
    const w = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing: null } })
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.form, { name: '운영', host: 'db.local', databaseName: 'app', username: 'reader', password: 'pw' })
    await vm.save()
    expect(useApiMock).toHaveBeenCalledWith('/api/db-connections', {
      method: 'POST',
      body: { scope: 'USER', repoCatalogId: 1, name: '운영', dbType: 'POSTGRESQL', host: 'db.local', port: 5432, databaseName: 'app', username: 'reader', password: 'pw' },
    })
    expect(w.emitted('saved')).toBeTruthy()
    w.unmount()

    const e = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing } })
    await flushPromises()
    const evm = e.vm as any
    expect(evm.form.password).toBe('')
    expect(evm.canSubmit).toBe(true) // 수정은 비밀번호 없이 저장 가능(=유지)
    await evm.save()
    expect(useApiMock).toHaveBeenLastCalledWith('/api/db-connections/7', expect.objectContaining({ method: 'PUT' }))
    e.unmount()
  })

  it('수정 중 접속 테스트는 id를 함께 보내고 결과를 보여 준다', async () => {
    useApiMock.mockResolvedValueOnce({ ok: false, message: '접속 실패: refused' })
    const w = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing } })
    await flushPromises()
    const vm = w.vm as any
    await vm.runTest()
    expect(useApiMock).toHaveBeenCalledWith('/api/db-connections/test', {
      method: 'POST',
      body: { id: 7, dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', password: '' },
    })
    expect(vm.testResult).toEqual({ ok: false, message: '접속 실패: refused' })
    w.unmount()
  })

  it('fetchDbConnections는 실패·이상 응답을 꺼짐으로 본다', async () => {
    useApiMock.mockRejectedValueOnce(new Error('503'))
    expect(await fetchDbConnections(1)).toEqual({ enabled: false, items: [] })
    useApiMock.mockResolvedValueOnce(null)
    expect(await fetchDbConnections(1)).toEqual({ enabled: false, items: [] })
    useApiMock.mockResolvedValueOnce({ enabled: true, items: [editing] })
    expect((await fetchDbConnections(1)).items).toHaveLength(1)
    expect(useApiMock).toHaveBeenLastCalledWith('/api/db-connections?repoCatalogId=1')
  })
})
```

- [ ] **Step 2: 실패 확인**

```bash
cd frontend && npx vitest run test/db-connection-dialog.spec.ts
```
Expected: FAIL(파일 없음).

- [ ] **Step 3: 컴포저블**

`frontend/composables/dbConnections.ts`:
```ts
// 질문 세션 DB 접속정보 (스펙 2026-10-02 §7). 비밀번호는 서버가 절대 돌려주지 않는다 — 수정 폼은 비워 두면 유지.
export type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE'
export type DbScope = 'REPO' | 'USER'

export interface DbConnectionView {
  id: number
  scope: DbScope
  repoCatalogId: number
  name: string
  dbType: DbType
  host: string
  port: number
  databaseName: string
  username: string
  enabled: boolean
  mine: boolean
}

export interface DbConnectionList {
  enabled: boolean
  items: DbConnectionView[]
}

export interface DbConnectionChip {
  id: number
  name: string
  dbType: string
}

export interface DbConnectionInput {
  scope: DbScope
  repoCatalogId: number
  name: string
  dbType: DbType
  host: string
  port: number
  databaseName: string
  username: string
  password: string
  enabled?: boolean
}

export const DB_TYPES: Array<{ value: DbType; label: string; defaultPort: number }> = [
  { value: 'POSTGRESQL', label: 'PostgreSQL', defaultPort: 5432 },
  { value: 'MYSQL', label: 'MySQL', defaultPort: 3306 },
  { value: 'MARIADB', label: 'MariaDB', defaultPort: 3306 },
  { value: 'ORACLE', label: 'Oracle', defaultPort: 1521 },
]

export const MAX_DB_SELECTION = 3

export function dbTypeLabel(t: string): string {
  return DB_TYPES.find((d) => d.value === t)?.label ?? t
}

export async function fetchDbConnections(repoCatalogId: number): Promise<DbConnectionList> {
  try {
    const r = await useApi<DbConnectionList>(`/api/db-connections?repoCatalogId=${repoCatalogId}`)
    return r && typeof r === 'object' && Array.isArray(r.items) ? r : { enabled: false, items: [] }
  } catch {
    return { enabled: false, items: [] }
  }
}

export function saveDbConnection(input: DbConnectionInput, id?: number) {
  return id == null
    ? useApi<DbConnectionView>('/api/db-connections', { method: 'POST', body: input })
    : useApi<DbConnectionView>(`/api/db-connections/${id}`, { method: 'PUT', body: input })
}

export function deleteDbConnection(id: number) {
  return useApi(`/api/db-connections/${id}`, { method: 'DELETE' })
}

export function testDbConnection(body: Record<string, unknown>) {
  return useApi<{ ok: boolean; message: string }>('/api/db-connections/test', { method: 'POST', body })
}
```

- [ ] **Step 4: 다이얼로그**

`frontend/components/DbConnectionDialog.vue`:
```vue
<script setup lang="ts">
// DB 접속정보 추가/수정 (스펙 2026-10-02 §7). AdminFormDialog 셸 재사용(모바일 전체화면).
// 수정 모드에서 비밀번호는 다시 채우지 않는다 — 비워 두고 저장하면 서버가 기존 값을 유지한다.
import { useQuasar } from 'quasar'
import AdminFormDialog from '~/components/AdminFormDialog.vue'
import {
  DB_TYPES, saveDbConnection, testDbConnection, type DbConnectionView, type DbScope, type DbType,
} from '~/composables/dbConnections'

const props = defineProps<{
  modelValue: boolean
  scope: DbScope
  repoCatalogId: number
  editing: DbConnectionView | null
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [view: DbConnectionView]
}>()

const $q = useQuasar()
const form = reactive({
  name: '',
  dbType: 'POSTGRESQL' as DbType,
  host: '',
  port: 5432,
  databaseName: '',
  username: '',
  password: '',
})
const portTouched = ref(false)
const submitting = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)

function reset() {
  const e = props.editing
  Object.assign(form, e
    ? { name: e.name, dbType: e.dbType, host: e.host, port: e.port, databaseName: e.databaseName, username: e.username, password: '' }
    : { name: '', dbType: 'POSTGRESQL', host: '', port: 5432, databaseName: '', username: '', password: '' })
  portTouched.value = !!e
  testResult.value = null
}
watch(() => props.modelValue, (open) => { if (open) reset() }, { immediate: true })
watch(() => form.dbType, (t) => {
  if (!portTouched.value) form.port = DB_TYPES.find((d) => d.value === t)?.defaultPort ?? form.port
})
function onPortInput(v: string | number | null) {
  form.port = Number(v)
  portTouched.value = true
}

const dbNameLabel = computed(() => (form.dbType === 'ORACLE' ? '서비스명' : 'DB 이름'))
const canSubmit = computed(() =>
  !!form.name.trim() && !!form.host.trim() && form.port > 0 && !!form.databaseName.trim() && !!form.username.trim()
  && (!!props.editing || !!form.password),
)

function fields() {
  return {
    dbType: form.dbType, host: form.host.trim(), port: form.port,
    databaseName: form.databaseName.trim(), username: form.username.trim(), password: form.password,
  }
}

async function runTest() {
  testing.value = true
  try {
    testResult.value = await testDbConnection(props.editing ? { id: props.editing.id, ...fields() } : fields())
  } catch (e: any) {
    testResult.value = { ok: false, message: e?.data?.message ?? '접속 테스트 요청 실패' }
  } finally {
    testing.value = false
  }
}

async function save() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const saved = await saveDbConnection(
      { scope: props.scope, repoCatalogId: props.repoCatalogId, name: form.name.trim(), ...fields() },
      props.editing?.id,
    )
    emit('saved', saved)
    emit('update:modelValue', false)
    $q.notify({ type: 'positive', message: 'DB 접속정보를 저장했습니다' })
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? 'DB 접속정보 저장 실패' })
  } finally {
    submitting.value = false
  }
}

defineExpose({ form, save, runTest, testResult, dbNameLabel, canSubmit, onPortInput })
</script>

<template>
  <AdminFormDialog
    :model-value="modelValue"
    :title="editing ? 'DB 접속정보 수정' : 'DB 접속정보 추가'"
    submit-label="저장"
    :submitting="submitting"
    :can-submit="canSubmit"
    @update:model-value="emit('update:modelValue', $event)"
    @submit="save"
  >
    <template #default="{ mobile }">
      <div class="column q-gutter-sm">
        <q-banner dense class="bg-amber-1 text-grey-9 db-warn">
          <template #avatar><q-icon name="shield" color="amber-9" /></template>
          읽기 전용 계정 사용을 권장합니다. 쓰기 SQL은 질문 세션에서 차단되지만 완전하지 않습니다.
        </q-banner>
        <q-input v-model="form.name" label="이름" :dense="mobile" outlined data-test="db-name" />
        <q-select
          v-model="form.dbType"
          :options="DB_TYPES"
          option-value="value"
          option-label="label"
          emit-value
          map-options
          label="종류"
          :dense="mobile"
          outlined
          data-test="db-type"
        />
        <div class="row q-col-gutter-sm">
          <q-input v-model="form.host" class="col-8" label="호스트" :dense="mobile" outlined data-test="db-host" />
          <q-input
            :model-value="form.port"
            class="col-4"
            type="number"
            label="포트"
            :dense="mobile"
            outlined
            data-test="db-port"
            @update:model-value="onPortInput"
          />
        </div>
        <q-input v-model="form.databaseName" :label="dbNameLabel" :dense="mobile" outlined data-test="db-database" />
        <q-input v-model="form.username" label="사용자" :dense="mobile" outlined autocomplete="off" data-test="db-username" />
        <q-input
          v-model="form.password"
          type="password"
          label="비밀번호"
          :placeholder="editing ? '비워 두면 기존 비밀번호 유지' : ''"
          :dense="mobile"
          outlined
          autocomplete="new-password"
          data-test="db-password"
        />
        <div class="row items-center q-gutter-sm">
          <q-btn flat no-caps icon="cable" label="접속 테스트" color="primary" :loading="testing" data-test="db-test" @click="runTest" />
          <span
            v-if="testResult"
            :class="testResult.ok ? 'text-positive' : 'text-negative'"
            class="text-caption"
            data-test="db-test-result"
          >{{ testResult.message }}</span>
        </div>
      </div>
    </template>
  </AdminFormDialog>
</template>

<style scoped>
.db-warn {
  border-radius: 6px;
}
</style>
```

- [ ] **Step 5: 통과 확인**

```bash
cd frontend && npx vitest run test/db-connection-dialog.spec.ts
```
Expected: 4건 PASS. `useApi`가 전역 자동 임포트로 잡히지 않으면 `test/setup.ts`가 `useApiMock`을 전역에 넣는 방식(기존 컴포저블 테스트와 동일)을 확인한다.

- [ ] **Step 6: Commit**

```bash
git add frontend/composables/dbConnections.ts frontend/components/DbConnectionDialog.vue frontend/test/db-connection-dialog.spec.ts
git commit -m "feat(front): DB 접속정보 컴포저블 + 추가/수정 다이얼로그

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: 프론트 — 선택 메뉴 + 새 질문 화면

**Files:**
- Create: `frontend/components/DbConnectionPicker.vue`
- Modify: `frontend/pages/questions/index.vue`
- Test: `frontend/test/db-connection-picker.spec.ts`, `frontend/test/questions-index.spec.ts`

**Interfaces:**
- Consumes: `DbConnectionView`, `MAX_DB_SELECTION`, `dbTypeLabel`, `fetchDbConnections`, `DbConnectionDialog`(Task 13)
- Produces: `<DbConnectionPicker v-model="number[]" :items :repo-catalog-id @reload>` — expose `{ toggle }`; index 페이지 expose에 `db` 추가

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/test/db-connection-picker.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import DbConnectionPicker from '../components/DbConnectionPicker.vue'

const item = (id: number, scope: 'REPO' | 'USER', enabled = true) => ({
  id, scope, repoCatalogId: 1, name: `conn${id}`, dbType: 'POSTGRESQL', host: 'h', port: 5432,
  databaseName: 'app', username: 'u', enabled, mine: scope === 'USER',
})

describe('DbConnectionPicker', () => {
  it('공용/내 접속정보로 나눠 보이고 토글하며 상한 3을 넘기지 않는다', async () => {
    const w = mount(DbConnectionPicker, {
      props: { modelValue: [1], items: [item(1, 'REPO'), item(2, 'REPO'), item(3, 'USER'), item(4, 'USER')], repoCatalogId: 1 },
    })
    await flushPromises()
    expect(w.find('[data-test="db-section-repo"]').text()).toContain('conn1')
    expect(w.find('[data-test="db-section-user"]').text()).toContain('conn3')
    const vm = w.vm as any
    vm.toggle(2); vm.toggle(3)
    const emitted = w.emitted('update:modelValue')!
    expect(emitted.at(-1)![0]).toEqual([1, 2, 3])
    await w.setProps({ modelValue: [1, 2, 3] })
    vm.toggle(4)
    expect(w.emitted('update:modelValue')!.length).toBe(emitted.length) // 4번째는 거부
    vm.toggle(1)
    expect(w.emitted('update:modelValue')!.at(-1)![0]).toEqual([2, 3])
    w.unmount()
  })

  it('비활성 항목은 고를 수 없다(관리자 목록)', async () => {
    const w = mount(DbConnectionPicker, { props: { modelValue: [], items: [item(5, 'REPO', false)], repoCatalogId: 1 } })
    ;(w.vm as any).toggle(5)
    expect(w.emitted('update:modelValue')).toBeFalsy()
    w.unmount()
  })
})
```

`frontend/test/questions-index.spec.ts` — `stubApi`의 분기 목록에 추가:
```ts
    if (url === '/api/db-connections?repoCatalogId=1') return Promise.resolve({
      enabled: true,
      items: [
        { id: 7, scope: 'REPO', repoCatalogId: 1, name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', enabled: true, mine: false },
        { id: 8, scope: 'USER', repoCatalogId: 1, name: '내 DB', dbType: 'ORACLE', host: 'h', port: 1521, databaseName: 'svc', username: 'u', enabled: true, mine: true },
      ],
    })
```
`describe` 안 끝에 테스트 추가:
```ts
  it('레포를 고르면 DB 버튼이 생기고 공용 연결이 기본 선택되어 등록 바디에 실린다 (스펙 2026-10-02 §7)', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    expect(w.find('[data-test="db-button"]').exists()).toBe(false)
    const vm = w.vm as any
    vm.draft.repoCatalogId = 1
    vm.onRepoSelected(1)
    await flushPromises()
    expect(w.find('[data-test="db-button"]').exists()).toBe(true)
    expect(vm.draft.dbConnectionIds).toEqual([7])
    Object.assign(vm.draft, { githubBranch: 'main', question: 'DB에 주문 테이블 있어?' })
    await vm.submit()
    await flushPromises()
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')
    expect(call![1].body.dbConnectionIds).toEqual([7])
    w.unmount()
  })

  it('DB 기능이 꺼져 있으면 버튼이 없고 바디에 dbConnectionIds를 싣지 않는다', async () => {
    useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
      if (url.startsWith('/api/db-connections')) return Promise.resolve({ enabled: false, items: [] })
      if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
      if (url === '/api/repo-catalog') return Promise.resolve([{ id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'a/b', defaultBranch: 'main' }])
      return Promise.resolve(null)
    })
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.draft.repoCatalogId = 1
    vm.onRepoSelected(1)
    await flushPromises()
    expect(w.find('[data-test="db-button"]').exists()).toBe(false)
    Object.assign(vm.draft, { githubBranch: 'main', question: 'q' })
    await vm.submit()
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')
    expect('dbConnectionIds' in call![1].body).toBe(false)
    w.unmount()
  })
```

- [ ] **Step 2: 실패 확인**

```bash
cd frontend && npx vitest run test/db-connection-picker.spec.ts test/questions-index.spec.ts
```
Expected: 새 테스트 FAIL, 기존 테스트 PASS.

- [ ] **Step 3: 선택 메뉴**

`frontend/components/DbConnectionPicker.vue`:
```vue
<script setup lang="ts">
// DB 연결 선택 (스펙 2026-10-02 §7) — q-menu 안에서 쓴다. 공용(REPO)·내 접속정보(USER) 체크 목록 + "새 접속 추가".
// 상한 MAX_DB_SELECTION. 비활성 항목(관리자에게만 보임)은 고를 수 없다. 새로 저장하면 reload를 올리고 바로 선택한다.
import { useQuasar } from 'quasar'
import DbConnectionDialog from '~/components/DbConnectionDialog.vue'
import { MAX_DB_SELECTION, dbTypeLabel, type DbConnectionView } from '~/composables/dbConnections'

const props = defineProps<{ items: DbConnectionView[]; repoCatalogId: number }>()
const model = defineModel<number[]>({ default: () => [] })
const emit = defineEmits<{ reload: [] }>()

const $q = useQuasar()
const dialogOpen = ref(false)
const repoItems = computed(() => props.items.filter((i) => i.scope === 'REPO'))
const userItems = computed(() => props.items.filter((i) => i.scope === 'USER'))

function toggle(id: number) {
  const it = props.items.find((i) => i.id === id)
  if (!it || !it.enabled) return
  const next = [...model.value]
  const idx = next.indexOf(id)
  if (idx >= 0) next.splice(idx, 1)
  else {
    if (next.length >= MAX_DB_SELECTION) {
      $q.notify({ type: 'warning', message: `DB 연결은 최대 ${MAX_DB_SELECTION}개까지 고를 수 있습니다` })
      return
    }
    next.push(id)
  }
  model.value = next
}

function onSaved(v: DbConnectionView) {
  emit('reload')
  if (!model.value.includes(v.id) && model.value.length < MAX_DB_SELECTION) model.value = [...model.value, v.id]
}

defineExpose({ toggle })
</script>

<template>
  <div class="db-picker">
    <div class="picker-label">공용</div>
    <div data-test="db-section-repo">
      <div v-if="repoItems.length === 0" class="text-caption text-grey-6 q-px-sm">등록된 공용 접속정보가 없습니다</div>
      <q-item v-for="i in repoItems" :key="i.id" dense tag="label" :disable="!i.enabled">
        <q-item-section side>
          <q-checkbox :model-value="model.includes(i.id)" :disable="!i.enabled" dense @update:model-value="toggle(i.id)" />
        </q-item-section>
        <q-item-section>
          <q-item-label>{{ i.name }} <q-badge outline color="grey-7" :label="dbTypeLabel(i.dbType)" /></q-item-label>
          <q-item-label caption>{{ i.host }}:{{ i.port }}/{{ i.databaseName }}</q-item-label>
        </q-item-section>
      </q-item>
    </div>
    <div class="picker-label q-mt-sm">내 접속정보</div>
    <div data-test="db-section-user">
      <div v-if="userItems.length === 0" class="text-caption text-grey-6 q-px-sm">아직 없습니다</div>
      <q-item v-for="i in userItems" :key="i.id" dense tag="label">
        <q-item-section side>
          <q-checkbox :model-value="model.includes(i.id)" dense @update:model-value="toggle(i.id)" />
        </q-item-section>
        <q-item-section>
          <q-item-label>{{ i.name }} <q-badge outline color="grey-7" :label="dbTypeLabel(i.dbType)" /></q-item-label>
          <q-item-label caption>{{ i.host }}:{{ i.port }}/{{ i.databaseName }}</q-item-label>
        </q-item-section>
      </q-item>
    </div>
    <q-btn flat dense no-caps icon="add" label="새 접속 추가" color="primary" class="q-mt-sm" data-test="db-add" @click="dialogOpen = true" />
    <DbConnectionDialog v-model="dialogOpen" scope="USER" :repo-catalog-id="repoCatalogId" :editing="null" @saved="onSaved" />
  </div>
</template>

<style scoped>
.db-picker {
  min-width: 280px;
  padding: 8px;
}
.picker-label {
  font-size: 12px;
  font-weight: 600;
  color: #616161;
  padding: 0 8px 4px;
}
</style>
```

- [ ] **Step 4: 새 질문 화면** — `frontend/pages/questions/index.vue`:

- import 추가:
```ts
import DbConnectionPicker from '~/components/DbConnectionPicker.vue'
import { MAX_DB_SELECTION, fetchDbConnections, type DbConnectionView } from '~/composables/dbConnections'
```
- `draft`에 `dbConnectionIds: [] as number[],` 추가.
- `files` 선언 아래에:
```ts
// DB 연결(스펙 2026-10-02 §7): 레포를 고를 때마다 목록을 새로 받고 공용(REPO) 활성 항목을 기본 선택한다.
// 기능이 꺼져 있으면(enabled=false) 버튼을 숨기고 등록 바디에도 싣지 않는다.
const db = reactive({ enabled: false, items: [] as DbConnectionView[] })
async function loadDb(repoId: number, seedDefaults: boolean) {
  const r = await fetchDbConnections(repoId)
  if (draft.repoCatalogId !== repoId) return // 늦게 온 이전 레포 응답
  db.enabled = r.enabled
  db.items = r.items
  const ids = new Set(r.items.map((i) => i.id))
  draft.dbConnectionIds = seedDefaults
    ? r.items.filter((i) => i.scope === 'REPO' && i.enabled).slice(0, MAX_DB_SELECTION).map((i) => i.id)
    : draft.dbConnectionIds.filter((id) => ids.has(id))
}
function reloadDb() {
  if (draft.repoCatalogId !== null) loadDb(draft.repoCatalogId, false)
}
```
- `onRepoSelected` 맨 앞(`resetBranchState()` 아래)에:
```ts
  db.enabled = false
  db.items = []
  draft.dbConnectionIds = []
```
  그리고 `if (entry.host === 'other') {…}` 블록 뒤, `loadBranches(...)` 앞에 `loadDb(entry.id, true)`.
- `submit()`의 `meta` 객체 끝에 `...(db.enabled ? { dbConnectionIds: draft.dbConnectionIds } : {}),`.
- `defineExpose({ draft, submit, onRepoSelected })` → `defineExpose({ draft, submit, onRepoSelected, db })`.
- 템플릿 `#tools`의 MCP `q-btn` 닫는 태그 뒤에:
```vue
            <q-btn
              v-if="db.enabled && draft.repoCatalogId !== null"
              flat
              dense
              no-caps
              icon="storage"
              :label="$q.screen.xs ? undefined : 'DB'"
              aria-label="DB 연결"
              data-test="db-button"
            >
              <q-badge v-if="draft.dbConnectionIds.length" color="primary" floating>
                {{ draft.dbConnectionIds.length }}
              </q-badge>
              <q-menu>
                <DbConnectionPicker
                  v-model="draft.dbConnectionIds"
                  :items="db.items"
                  :repo-catalog-id="draft.repoCatalogId"
                  @reload="reloadDb"
                />
              </q-menu>
            </q-btn>
```

- [ ] **Step 5: 통과 확인**

```bash
cd frontend && npx vitest run test/db-connection-picker.spec.ts test/questions-index.spec.ts test/questions-shell.spec.ts
```
Expected: 전부 PASS(기존 `toHaveBeenCalledWith` 정확 일치 테스트도 그대로 통과 — DB가 꺼진 상태라 바디 불변).

- [ ] **Step 6: Commit**

```bash
git add frontend/components/DbConnectionPicker.vue frontend/pages/questions/index.vue frontend/test/db-connection-picker.spec.ts frontend/test/questions-index.spec.ts
git commit -m "feat(front): 새 질문 화면 DB 연결 선택(공용 기본 선택·개인 추가)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: 프론트 — 대화 화면 + 관리자 공용 접속정보

**Files:**
- Modify: `frontend/composables/questions.ts` (`QuestionDetail`)
- Modify: `frontend/components/InterviewPanel.vue` (`AskExtra`, `sendAnswer`)
- Modify: `frontend/pages/questions/[id].vue`
- Create: `frontend/components/DbConnectionAdminDialog.vue`
- Modify: `frontend/pages/admin/repo-catalog.vue`
- Test: `frontend/test/questions-detail.spec.ts`, `frontend/test/db-connection-admin-dialog.spec.ts`

**Interfaces:**
- Consumes: Task 13·14의 컴포저블·컴포넌트
- Produces: `QuestionDetail.repoCatalogId?: number | null`, `dbConnectionIds?: number[]`, `dbConnections?: DbConnectionChip[]`; `AskExtra.dbConnectionIds?: number[]`; `<DbConnectionAdminDialog v-model :repo="{ id, alias } | null">`

- [ ] **Step 1: 실패하는 테스트 작성**

`frontend/test/questions-detail.spec.ts` 끝에 새 describe:
```ts
describe('pages/questions/[id] — DB 연결 (스펙 2026-10-02 §7)', () => {
  const dbDetail = { ...detail, repoCatalogId: 1, dbConnectionIds: [7], dbConnections: [{ id: 7, name: '운영', dbType: 'MYSQL' }] }
  beforeEach(() => {
    authStub.accessToken = 'jwt'
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3' ? Promise.resolve(dbDetail)
        : url === '/api/db-connections?repoCatalogId=1' ? Promise.resolve({
          enabled: true,
          items: [{ id: 7, scope: 'REPO', repoCatalogId: 1, name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', enabled: true, mine: false }],
        })
          : url === '/api/usage/claude' ? Promise.resolve({ limits: [] })
            : Promise.resolve(null),
    )
  })

  it('세션 값으로 시딩된 DB 버튼·헤더 칩을 보이고, 보낼 때 dbConnectionIds를 싣는다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.find('[data-test="db-button"] .q-badge').text()).toBe('1')
    expect(w.find('[data-test="session-db-chips"]').text()).toContain('운영')
    ;(w.vm as any).pickedDb.splice(0)
    await sendText(w, 'DB 없이 다시')
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
    expect(call![1].body.dbConnectionIds).toEqual([])
    w.unmount()
  })
})
```
(`sendText`는 이 파일에 이미 있는 헬퍼다. 없으면 기존 테스트의 textarea 입력 + Enter 절차를 그대로 쓴다.)

`frontend/test/db-connection-admin-dialog.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import DbConnectionAdminDialog from '../components/DbConnectionAdminDialog.vue'
import { useApiMock } from './mocks/nuxt'

const row = (id: number, scope: 'REPO' | 'USER', enabled = true) => ({
  id, scope, repoCatalogId: 1, name: `c${id}`, dbType: 'POSTGRESQL', host: 'h', port: 5432,
  databaseName: 'app', username: 'u', enabled, mine: false,
})

describe('DbConnectionAdminDialog — 레포 공용 접속정보 관리', () => {
  beforeEach(() => {
    useApiMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      url === '/api/db-connections?repoCatalogId=1'
        ? Promise.resolve({ enabled: true, items: [row(1, 'REPO'), row(2, 'REPO', false), row(3, 'USER')] })
        : Promise.resolve({}),
    )
  })

  it('공용(REPO)만 보이고, 활성 토글은 비밀번호 없이 PUT한다', async () => {
    const w = mount(DbConnectionAdminDialog, { props: { modelValue: true, repo: { id: 1, alias: 'Netis7.0' } } })
    await flushPromises()
    const vm = w.vm as any
    expect(vm.rows.map((r: any) => r.id)).toEqual([1, 2])
    await vm.toggleEnabled(vm.rows[0])
    expect(useApiMock).toHaveBeenCalledWith('/api/db-connections/1', {
      method: 'PUT',
      body: { scope: 'REPO', repoCatalogId: 1, name: 'c1', dbType: 'POSTGRESQL', host: 'h', port: 5432, databaseName: 'app', username: 'u', password: '', enabled: false },
    })
    w.unmount()
  })

  it('기능이 꺼져 있으면 안내만 보인다', async () => {
    useApiMock.mockImplementation(() => Promise.resolve({ enabled: false, items: [] }))
    const w = mount(DbConnectionAdminDialog, { props: { modelValue: true, repo: { id: 1, alias: 'Netis7.0' } } })
    await flushPromises()
    expect((w.vm as any).featureEnabled).toBe(false)
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

```bash
cd frontend && npx vitest run test/questions-detail.spec.ts test/db-connection-admin-dialog.spec.ts
```
Expected: 새 테스트 FAIL.

- [ ] **Step 3: 타입·패널**

`composables/questions.ts` — `QuestionDetail`의 `mcpCatalogIds?: number[]` 아래에:
```ts
  /** 레포 카탈로그 id — DB 접속정보 목록 조회 키 (스펙 2026-10-02 §5.3). 구버전 세션은 null. */
  repoCatalogId?: number | null
  /** 선택한 DB 접속정보 id — 대화 화면 DB 선택 시딩. */
  dbConnectionIds?: number[]
  /** 헤더 칩용 {id,name,dbType} — 현재 행 기준. */
  dbConnections?: Array<{ id: number; name: string; dbType: string }>
```

`components/InterviewPanel.vue`:
- `type AskExtra = { … }`에 `dbConnectionIds?: number[];` 추가.
- `if (Array.isArray(extra?.mcpCatalogIds)) body.mcpCatalogIds = extra.mcpCatalogIds` 아래에:
```ts
    // DB 연결도 같은 규칙 — 페이지가 기능이 켜져 있을 때만 넘긴다(스펙 2026-10-02 §7)
    if (Array.isArray(extra?.dbConnectionIds)) body.dbConnectionIds = extra.dbConnectionIds
```

- [ ] **Step 4: 대화 화면** — `pages/questions/[id].vue`:

- import 추가:
```ts
import DbConnectionPicker from '~/components/DbConnectionPicker.vue'
import { dbTypeLabel, fetchDbConnections, type DbConnectionView } from '~/composables/dbConnections'
```
- `pickedMcp` 선언 아래에:
```ts
// 대화 중 DB 연결 변경(스펙 2026-10-02 §7): MCP와 같은 원리 — 세션 값으로 1회 시드, 기능이 켜져 있을 때만 전송.
const pickedDb = ref<number[]>([])
const dbState = reactive({ enabled: false, items: [] as DbConnectionView[] })
async function loadDb(repoId: number) {
  const r = await fetchDbConnections(repoId)
  dbState.enabled = r.enabled
  dbState.items = r.items
}
```
- `watch(detail, …)` 안 `pickedMcp.value = …` 아래에:
```ts
  pickedDb.value = [...(d.dbConnectionIds ?? [])]
  if (d.repoCatalogId) loadDb(d.repoCatalogId)
```
- `SendFn` 타입 객체에 `dbConnectionIds?: number[]` 추가, `onSend`의 `send({ … })` 객체 끝에 `...(dbState.enabled ? { dbConnectionIds: pickedDb.value } : {}),`.
- `reloadDb` 함수:
```ts
function reloadDb() {
  if (detail.value?.repoCatalogId) loadDb(detail.value.repoCatalogId)
}
```
- 템플릿: 등록 첨부 칩 줄(`data-test="session-attachments"`) 바로 위에:
```vue
    <div
      v-if="detail?.dbConnections?.length"
      class="row items-center session-attachments"
      data-test="session-db-chips"
    >
      <q-chip
        v-for="c in detail.dbConnections"
        :key="c.id"
        dense
        size="sm"
        icon="storage"
        color="teal-1"
        text-color="teal-10"
        :label="`${c.name} · ${dbTypeLabel(c.dbType)}`"
      />
    </div>
```
  그리고 `#tools`의 MCP `q-btn` 닫는 태그 뒤에:
```vue
              <q-btn
                v-if="dbState.enabled"
                flat
                dense
                no-caps
                icon="storage"
                :label="$q.screen.xs ? undefined : 'DB'"
                aria-label="DB 연결"
                :disable="!awaiting"
                data-test="db-button"
              >
                <q-badge v-if="pickedDb.length" color="primary" floating>{{ pickedDb.length }}</q-badge>
                <q-tooltip>대화 중 변경 — 다음 질문부터 적용</q-tooltip>
                <q-menu>
                  <DbConnectionPicker
                    v-model="pickedDb"
                    :items="dbState.items"
                    :repo-catalog-id="detail?.repoCatalogId ?? 0"
                    @reload="reloadDb"
                  />
                </q-menu>
              </q-btn>
```
- 페이지에 `defineExpose`가 있으면 `pickedDb`를 추가하고, 없으면 기존 테스트가 `(w.vm as any).pickedMcp`로 접근하는 방식(스크립트 셋업 변수 노출)과 같아 추가 작업이 없다.

- [ ] **Step 5: 관리자 다이얼로그**

`frontend/components/DbConnectionAdminDialog.vue`:
```vue
<script setup lang="ts">
// 레포 공용(REPO) DB 접속정보 관리 (스펙 2026-10-02 §7) — 관리 > 레포 카탈로그에서 연다.
// 목록 API는 관리자에게 비활성 REPO도 돌려준다. USER 행은 여기서 다루지 않는다(본인만 관리).
import { useQuasar } from 'quasar'
import DbConnectionDialog from '~/components/DbConnectionDialog.vue'
import {
  deleteDbConnection, dbTypeLabel, fetchDbConnections, saveDbConnection, type DbConnectionView,
} from '~/composables/dbConnections'

const props = defineProps<{ modelValue: boolean; repo: { id: number; alias: string } | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const $q = useQuasar()
const rows = ref<DbConnectionView[]>([])
const featureEnabled = ref(true)
const editOpen = ref(false)
const editing = ref<DbConnectionView | null>(null)

async function load() {
  if (!props.repo) return
  const r = await fetchDbConnections(props.repo.id)
  featureEnabled.value = r.enabled
  rows.value = r.items.filter((i) => i.scope === 'REPO')
}
watch(() => [props.modelValue, props.repo?.id], ([open]) => { if (open) load() }, { immediate: true })

function openAdd() {
  editing.value = null
  editOpen.value = true
}
function openEdit(r: DbConnectionView) {
  editing.value = r
  editOpen.value = true
}

async function toggleEnabled(r: DbConnectionView) {
  try {
    await saveDbConnection({
      scope: 'REPO', repoCatalogId: r.repoCatalogId, name: r.name, dbType: r.dbType, host: r.host, port: r.port,
      databaseName: r.databaseName, username: r.username, password: '', enabled: !r.enabled,
    }, r.id)
    await load()
  } catch (e: any) {
    $q.notify({ type: 'negative', message: e?.data?.message ?? '변경 실패' })
  }
}

function remove(r: DbConnectionView) {
  $q.dialog({ title: '삭제', message: `'${r.name}' 접속정보를 삭제할까요? 이 연결을 고른 질문 세션은 다음 질문부터 DB 도구 없이 답합니다.`, cancel: true })
    .onOk(async () => {
      try {
        await deleteDbConnection(r.id)
        await load()
      } catch (e: any) {
        $q.notify({ type: 'negative', message: e?.data?.message ?? '삭제 실패' })
      }
    })
}

defineExpose({ rows, featureEnabled, toggleEnabled, load })
</script>

<template>
  <q-dialog :model-value="modelValue" :maximized="$q.screen.lt.md" @update:model-value="emit('update:modelValue', $event)">
    <div class="db-admin-card">
      <q-card flat>
        <q-card-section class="row items-center no-wrap">
          <div class="text-subtitle1 col">DB 접속(공용) — {{ repo?.alias }}</div>
          <q-btn flat round dense icon="close" aria-label="닫기" @click="emit('update:modelValue', false)" />
        </q-card-section>
        <q-card-section v-if="!featureEnabled" class="text-grey-8" data-test="db-admin-disabled">
          DB 접속정보 기능이 꺼져 있습니다 (NETISMAKER_DB_SECRET_KEY / NETISMAKER_DB_SECRET_SALT 미설정).
        </q-card-section>
        <template v-else>
          <q-list separator>
            <q-item v-if="rows.length === 0"><q-item-section class="text-grey-6">등록된 공용 접속정보가 없습니다</q-item-section></q-item>
            <q-item v-for="r in rows" :key="r.id" :class="{ 'text-grey-6': !r.enabled }">
              <q-item-section>
                <q-item-label>{{ r.name }} <q-badge outline color="grey-7" :label="dbTypeLabel(r.dbType)" /></q-item-label>
                <q-item-label caption>{{ r.host }}:{{ r.port }}/{{ r.databaseName }} · {{ r.username }}</q-item-label>
              </q-item-section>
              <q-item-section side class="row no-wrap items-center">
                <q-toggle :model-value="r.enabled" dense @update:model-value="toggleEnabled(r)" />
                <q-btn flat dense icon="edit" color="primary" aria-label="수정" @click="openEdit(r)" />
                <q-btn flat dense icon="delete" color="negative" aria-label="삭제" @click="remove(r)" />
              </q-item-section>
            </q-item>
          </q-list>
          <q-card-actions align="right">
            <q-btn flat no-caps icon="add" label="공용 접속 추가" color="primary" data-test="db-admin-add" @click="openAdd" />
          </q-card-actions>
        </template>
      </q-card>
      <DbConnectionDialog
        v-if="repo"
        v-model="editOpen"
        scope="REPO"
        :repo-catalog-id="repo.id"
        :editing="editing"
        @saved="load"
      />
    </div>
  </q-dialog>
</template>

<style scoped>
.db-admin-card {
  width: min(640px, 100vw);
  background: white;
}
</style>
```

`pages/admin/repo-catalog.vue`:
- import: `import DbConnectionAdminDialog from '~/components/DbConnectionAdminDialog.vue'`
- 스크립트에:
```ts
// 레포별 공용 DB 접속정보 (스펙 2026-10-02 §7)
const dbAdminOpen = ref(false)
const dbAdminRepo = ref<{ id: number; alias: string } | null>(null)
function openDbAdmin(e: RepoEntry) {
  dbAdminRepo.value = { id: e.id, alias: e.alias }
  dbAdminOpen.value = true
}
```
- 카드 `#actions`의 `연결 확인` 버튼 뒤에:
```vue
          <q-btn flat no-caps icon="storage" label="DB 접속" color="primary" data-test="card-db" @click="openDbAdmin(e)" />
```
- 테이블 `#body-cell-actions`의 `check` 버튼 뒤에:
```vue
          <q-btn flat dense icon="storage" color="primary" data-test="db" @click="openDbAdmin(props.row)">
            <q-tooltip>DB 접속(공용)</q-tooltip>
          </q-btn>
```
- 템플릿 루트 끝(마지막 다이얼로그 뒤)에 `<DbConnectionAdminDialog v-model="dbAdminOpen" :repo="dbAdminRepo" />`

- [ ] **Step 6: 통과 확인 + 프론트 전체**

```bash
cd frontend && npx vitest run 2>&1 | tail -6
```
Expected: 전부 PASS(Task 0 기준선 + 새 테스트).

- [ ] **Step 7: Commit**

```bash
git add frontend/composables/questions.ts frontend/components/InterviewPanel.vue "frontend/pages/questions/[id].vue" frontend/components/DbConnectionAdminDialog.vue frontend/pages/admin/repo-catalog.vue frontend/test/questions-detail.spec.ts frontend/test/db-connection-admin-dialog.spec.ts
git commit -m "feat(front): 대화 중 DB 연결 변경·헤더 칩 + 관리자 레포 공용 접속정보

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: 문서 + 전체 검증 + 라이브 스모크

**Files:**
- Modify: `CLAUDE.md` (핵심 설계 제약, 자주 보는 코드 표)
- Modify: `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md` §11 (라이브 확인 결과)

- [ ] **Step 1: CLAUDE.md** — "핵심 설계 제약"의 질문 세션 항목 끝에 문장 추가:

```
**질문 세션 DB 연결(2026-10-02, 스펙 `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md`)**: 레포별 DB 접속정보(`com.db_connection`, REPO=관리자 공용·USER=개인, 비밀번호는 `DbSecretCipher` AES-GCM 암호문 — 키 `NETISMAKER_DB_SECRET_KEY`+`NETISMAKER_DB_SECRET_SALT`, 없으면 기능만 꺼짐)를 질문 등록·`ask` 바디 `dbConnectionIds`(MCP와 같은 null/[] 규칙, 상한 3)로 고르면, claim 때 `InterviewWorkerController`가 복호화해 `dbConnections`로 싣고 러너가 턴마다 연결별 **내장 SDK MCP 서버**(`createSdkMcpServer`, `db-<id>`, 도구 `query`·`list_tables`·`describe_table`)를 만든다(`src/sdk/dbMcp.ts`, 드라이버 `pg`·`mysql2`·`oracledb` thin — 접속은 첫 도구 호출 때, 턴 종료 finally에서 close). SDK는 `type:'sdk'` 서버를 CLI에 이름만 넘기지만 stdio 서버는 `--mcp-config` 명령줄에 통째로 싣는다 — **접속정보를 stdio 서버 env에 넣지 말 것**. 읽기 전용은 3중: 질문 게이트(`dbToolGate`)와 도구 핸들러가 `sqlReadOnly.ts`로 방언별 검사(단일 조회문만) + 어댑터가 READ ONLY 트랜잭션 안에서 실행 후 항상 ROLLBACK + 30초·200행·셀 2000자 상한(`db/limits.ts`). MCP 카탈로그 이름 `db-*`는 예약. 배포 순서: API → 인터뷰 서비스(`npm ci`) → 프론트.
```
"자주 보는 코드" 표에 행 추가:
```
| 질문 세션 DB 접속정보 (CRUD·권한·선택 검증·claim 복호화 · JDBC 접속 테스트 · 암호화) | `service/DbConnectionService.java`, `controller/DbConnectionController.java`, `service/JdbcDbConnectionTester.java`, `service/DbSecretCipher.java`, `entity/DbConnection.java`, 마이그레이션 `V24__db_connection.sql` |
| 질문 세션 내장 DB MCP (서버·도구 · 읽기 전용 어댑터 · 카탈로그 · 결과 상한 · SQL 게이트) | `netismaker-interview-service/src/sdk/dbMcp.ts`, `src/sdk/db/{adapters,catalog,result,limits}.ts`, `src/sdk/sqlReadOnly.ts`, `src/sdk/permissions.ts`(`dbToolGate`) |
| DB 연결 선택·접속정보 다이얼로그·관리자 공용 관리 | `frontend/components/DbConnectionPicker.vue`, `DbConnectionDialog.vue`, `DbConnectionAdminDialog.vue`, `frontend/composables/dbConnections.ts` |
```

- [ ] **Step 2: 전체 테스트**

```bash
cd /c/Users/mic/NetisMaker/commanCenter/.claude/worktrees/question-db-mcp
JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test 2>&1 | tail -5
cd netismaker-interview-service && npx vitest run 2>&1 | tail -5 && npx tsc -p tsconfig.json --noEmit && cd ..
cd frontend && npx vitest run 2>&1 | tail -5 && cd ..
```
Expected: Java `BUILD SUCCESSFUL`, 인터뷰 서비스 실패는 기존 5건뿐 + tsc 0, 프론트 전부 PASS. 하나라도 어긋나면 출력 그대로 보고하고 고친다.

- [ ] **Step 3: Commit + push + PR**

```bash
git add CLAUDE.md
git commit -m "docs: 질문 세션 DB 연결 — CLAUDE.md 설계 제약·자주 보는 코드

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin feat/question-db-mcp
```
PR 생성은 사용자 확인 후(`gh pr create`, 본문 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`).

- [ ] **Step 4: 라이브 반영 (사용자와 함께, 머지 후)** — 재기동 전 진행 중 질문/인터뷰 세션이 없는지 DB로 확인한다.
  1. 키 생성(값은 출력하지 말고 바로 `public.env`에 쓴다):
     ```powershell
     $k = node -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))"; $s = node -e "process.stdout.write(require('crypto').randomBytes(16).toString('hex'))"
     ```
     `public.env`에 `NETISMAKER_DB_SECRET_KEY=$k`, `NETISMAKER_DB_SECRET_SALT=$s` 추가(먼저 `public.env.bak-<시각>` 백업). **키를 잃으면 저장된 접속정보를 전부 다시 입력해야 한다** — 백업 위치를 운영 노트에 남긴다.
  2. 본 체크아웃 인터뷰 서비스에서 `npm ci`(새 의존성 `pg`·`mysql2`·`oracledb`·`zod` — 레지스트리 다운로드, 실행 전 사용자에게 알린다). 인터뷰 서비스 env 추가 없음.
  3. API → 인터뷰 서비스 재기동(작업 스케줄러 `netisMaker-public` 경유), 프론트는 `NUXT_IGNORE_LOCK=1 npm run build` 후 재기동.
  4. API 로그에 `DB 접속정보 기능 꺼짐`이 **없는지** 확인.

- [ ] **Step 5: 라이브 스모크 (공개 주소 `app-win`에서 시작)** — DB 종류마다:
  1. 새 질문에서 레포 선택 → DB 버튼 → 새 접속 추가 → 접속 테스트 `접속 성공` → 저장.
  2. "테이블 목록과 주문 건수 알려줘" 질문 → 답변에 실제 테이블·집계가 나오는지.
  3. "주문 1번 상태를 취소로 UPDATE 해줘" → 활동 스트림/답변에 게이트 거부(읽기 전용) 문구.
  4. 대화 중 DB 해제 → `DB 연결 변경: 없음(전부 해제)` 노트 즉시 표시 → 다음 답변에서 DB 도구 미사용.
  5. 턴 진행 중 PowerShell로 명령줄 점검:
     ```powershell
     Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'claude.exe','node.exe' } | Select-Object Name, CommandLine | Format-List
     ```
     어떤 명령줄에도 비밀번호·호스트 계정이 없어야 한다(claude.exe의 `--mcp-config`에는 `{"type":"sdk","name":"db-N"}`만).
  6. "큰 테이블 전체 보여줘"(행 200 초과) → 답변이 잘림을 언급하고 조건을 좁혀 다시 조회하는지, 인터뷰 서비스 메모리가 튀지 않는지.
  결과를 스펙 §11-3·4·5에 `→ 라이브 확인: …`으로 적고 커밋한다.

- [ ] **Step 6: 정리** — 프론트 정션 제거(`cmd /c rmdir frontend\node_modules`). 인터뷰 서비스 node_modules는 worktree 안의 실제 폴더라 worktree와 함께 지워진다. worktree 자체 삭제는 PR 머지 후 사용자 확인을 받아서.
