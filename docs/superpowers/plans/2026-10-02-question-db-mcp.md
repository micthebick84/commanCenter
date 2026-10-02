# 질문 세션 DB 접속정보 → stdio MCP 자동 연동 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 질문 세션에서 레포에 묶인 DB 접속정보(PostgreSQL·MySQL·MariaDB·Oracle)를 고르면, 에이전트가 MCP Toolbox를 stdio로 붙여 그 DB를 읽기 전용으로 조회한다.

**Architecture:** 접속정보는 `com.db_connection`에 AES-GCM 암호문으로 저장하고(레포 공용 REPO + 개인 USER), 세션에는 id만 둔다. claim 때 API가 복호화한 접속정보를 내부 API로 인터뷰 서비스에 넘기고, 러너가 턴마다 임시 env 파일을 쓴 뒤 `mcpServers`에 런처(`node dbMcpLauncher.mjs <파일>`)만 넣는다 — 명령줄·`mcps_extra`·로그에 비밀번호가 남지 않는다. 런처가 Toolbox(`--prebuilt <db> --stdio`)를 실행하고, 질문 게이트(`canUseTool`)가 `execute_sql`의 SQL을 방언별로 검사해 단일 조회문만 통과시킨다.

**Tech Stack:** Spring Boot 3(JPA·Flyway·spring-security-crypto `Encryptors.delux`), JDBC 드라이버 4종, Node 20 + `@anthropic-ai/claude-agent-sdk` 0.2.117 + vitest, Nuxt 3 + Quasar + vitest, Google MCP Toolbox for Databases v1.13.1.

**Spec:** `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md`

## Global Constraints

- 작업 위치: worktree `C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\question-db-mcp`, 브랜치 `feat/question-db-mcp`. **본 체크아웃(`commanCenter/`)에서 빌드·테스트 금지** — 라이브 `bootRun`이 그 `build/classes`를 쓴다.
- JDK: `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1`. Gradle 테스트는 `--tests`에 **클래스 FQN을 명시**(`"*Interview*"` 글롭은 Git Bash에서 `netismaker-interview-service` 디렉터리로 확장돼 실패).
- Docker 없음 → `RUN_TESTCONTAINERS=true` 통합 테스트는 이 PC에서 skip(CI에서 돈다). 단위 테스트를 1차 검증으로 삼는다.
- 인터뷰 서비스 vitest는 이 PC에서 **기존 5건이 원래 실패**(claudeCli 경로 구분자 1, QUESTION 게이트 2, attachmentRoot Read 게이트 2). 그 5건 외 실패만 회귀로 본다.
- CRLF: `core.autocrlf=true`. Git Bash `sed -i` 뒤에는 `file <경로>`로 줄끝을 확인하고 필요하면 `sed -i 's/\r$//; s/$/\r/'`로 되돌린다(Edit/Write 도구는 그대로 써도 된다).
- Toolbox 버전 **v1.13.1 고정**. Agent SDK **0.2.117 유지**.
- DB 종류 enum 값은 정확히 `POSTGRESQL` | `MYSQL` | `MARIADB` | `ORACLE`. 기본 포트 5432/3306/3306/1521.
- MCP 서버 이름은 `db-<접속정보 id>`. MCP 카탈로그 이름에서 `db-` 접두사는 예약(거부).
- 세션당 DB 연결 선택 상한 **3**. 접속 테스트 타임아웃 **5초**.
- 설정 키: `app.db-secret.key` ← env `NETISMAKER_DB_SECRET_KEY`, `app.db-secret.salt` ← env `NETISMAKER_DB_SECRET_SALT`(hex, 짝수 길이 16자 이상). 둘 중 하나라도 없거나 salt 형식이 틀리면 **기능만 꺼진다**(부팅 실패 금지).
- 비밀번호(평문·암호문)는 **브라우저로 가는 어떤 응답·로그·예외 메시지·claude CLI 명령줄·`mcps_extra`에도 나가면 안 된다.** claim 응답(내부 API)만 예외.
- 임시 env 파일: `DB_MCP_TMP_DIR`(기본 `os.tmpdir()/netismaker-dbmcp`), `mode 0o600`, `flag 'wx'`, 턴 종료 `finally`에서 삭제, 서비스 기동 시 1시간 넘은 파일 정리.
- 프론트: 작은따옴표 + 세미콜론 없음, 문자열은 한국어 하드코딩, 클래스명에 Quasar 반응형 예약어(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`) 금지, `q-dialog` 안 루트 요소는 `<div>`, 모바일 분기는 `$q.screen.lt.md`. `npm run lint-prettier`를 `frontend/` 전체에 돌리지 말 것.
- DB 기능이 꺼져 있으면(`GET /api/db-connections` → `enabled:false`) 프론트는 DB 버튼을 숨기고 요청 바디에 `dbConnectionIds`를 **싣지 않는다**(기존 바디 그대로 — 기존 테스트가 정확 일치로 검증한다).
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **저장 비밀번호 유출 경로** — 편집 권한이 없는 사용자가 공용(REPO) 접속정보 id와 **자기 서버 host**로 `POST /api/db-connections/test`를 보내면 API가 저장된 비밀번호로 그 host에 접속을 시도한다. 기대: 403, tester 호출 없음. → Task 5 `test_by_id_requires_edit_permission`.
2. **JDBC URL 파라미터 주입** — host/DB명에 `db?allowLoadLocalInfile=true`, `h/evil`, `h:1/x` 같은 값을 넣으면 드라이버 옵션이 바뀐다. 기대: 400(DTO 패턴 거부). → Task 6 `DbConnectionDtoValidationTest`.
3. **claim 보강 실패로 세션이 RUNNING에 묶임** — claim 트랜잭션이 커밋된 뒤 접속정보 조회가 예외를 던지면 응답이 500이 되고 세션이 stale 회수 때까지 멈춘다. 기대: 200 + `dbNotices` 안내, DB 서버 없이 턴 진행. → Task 8 `claim_survives_db_resolution_failure`.
4. **문자열·주석 경계 우회** — `SELECT 'a\'; DELETE …`(PG 표준 문자열), MySQL `/*! DELETE */` 실행 주석, PG에서 `#`(연산자) 뒤 숨기기, `$$…$$` 안 `;`, MySQL `--x`(주석 아님). 기대: 전부 거부 또는 숨긴 부분까지 검사. → Task 9 우회 사례 표.
5. **턴 실패 시 임시 파일 잔존 / 런처 오류 메시지의 비밀번호 노출** — SDK 쿼리가 throw해도 env 파일이 지워져야 하고, 런처가 깨진 파일을 읽을 때 JSON.parse 오류(본문 일부를 담음)가 stderr로 나가면 안 된다. 기대: `finally` 정리, 고정 문구 오류. → Task 11 `readSpec_error_never_echoes_content`, Task 12 `cleans_up_env_files_when_query_throws`.

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
- Create `src/sdk/dbMcp.ts` — prebuilt/env 매핑, 임시 파일 준비·정리·청소
- Create `src/launcher/dbMcpLauncher.mjs` + `src/launcher/dbMcpLauncher.d.mts` — Toolbox 실행 런처(tsx 없이 실행 가능해야 함)
- Create `scripts/install-toolbox.ps1`, `scripts/spikeToolbox.mjs`
- Modify `src/types.ts`, `src/config.ts`, `src/sdk/permissions.ts`, `src/sdk/sessionOptions.ts`, `src/runner/interviewRunner.ts`, `src/index.ts`, `.env.example`

**프론트 (`frontend/`)**
- Create `composables/dbConnections.ts`, `components/DbConnectionDialog.vue`, `components/DbConnectionPicker.vue`, `components/DbConnectionAdminDialog.vue`
- Modify `pages/questions/index.vue`, `pages/questions/[id].vue`, `components/InterviewPanel.vue`, `composables/questions.ts`, `pages/admin/repo-catalog.vue`

**문서**: `CLAUDE.md`, 스펙 §8.1·§11 갱신

---

### Task 0: worktree 준비

**Files:** 없음(환경)

- [ ] **Step 1: node_modules 정션 연결** (새 worktree에는 node_modules가 없다)

```powershell
cd C:\Users\mic\NetisMaker\commanCenter\.claude\worktrees\question-db-mcp
cmd /c mklink /J frontend\node_modules ..\..\..\frontend\node_modules
cmd /c mklink /J netismaker-interview-service\node_modules ..\..\..\netismaker-interview-service\node_modules
```
Expected: `Junction created for …` 두 줄. ⚠️ 나중에 지울 때는 `cmd /c rmdir frontend\node_modules`(정션만 제거). `rm -rf`/`Remove-Item -Recurse`는 원본 node_modules를 지운다.

- [ ] **Step 2: 기준선 테스트**

```bash
cd /c/Users/mic/NetisMaker/commanCenter/.claude/worktrees/question-db-mcp/netismaker-interview-service && npx vitest run 2>&1 | tail -5
cd ../frontend && npx vitest run 2>&1 | tail -5
```
Expected: 인터뷰 서비스는 실패 5건(Global Constraints의 기존 실패), 프론트는 전부 통과. 숫자를 기록해 두고 이후 회귀 판단에 쓴다.

---

### Task 1: Toolbox 설치 + 스파이크(스펙 §11 미검증 가정 확인)

**Files:**
- Create: `netismaker-interview-service/scripts/install-toolbox.ps1`
- Create: `netismaker-interview-service/scripts/spikeToolbox.mjs`
- Modify: `.gitignore` (루트) — `netismaker-interview-service/bin/` 추가
- Modify: `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md` §11 (결과 기록)

**Interfaces:**
- Produces: `netismaker-interview-service/bin/toolbox.exe`(git 제외), 확정된 도구 입력 파라미터 이름(Task 10의 `DB_SQL_PARAMS` 상수), PG read-only 파라미터 적용 여부(Task 11의 `PG_READ_ONLY_QUERY_PARAMS` 유지/삭제)

- [ ] **Step 1: 배포 URL 확인** — 저장소 README의 설치 절에서 Windows 바이너리 URL을 찾는다.

```bash
gh api repos/googleapis/genai-toolbox/contents/README.md --jq .content | base64 -d | grep -n "storage.googleapis.com" | head
curl -sI https://storage.googleapis.com/genai-toolbox/v1.13.1/windows/amd64/toolbox.exe | head -1
```
Expected: `HTTP/1.1 200 OK`(또는 `HTTP/2 200`). 404면 README에 나온 실제 경로(버킷 이름이 `mcp-toolbox`로 바뀌었을 수 있음)로 Step 2의 `$url`을 고친다.

- [ ] **Step 2: 설치 스크립트 작성**

`netismaker-interview-service/scripts/install-toolbox.ps1`:
```powershell
# MCP Toolbox for Databases 바이너리를 bin/toolbox.exe로 받는다 (스펙 2026-10-02 §6.1).
# 버전 고정 — 올릴 때는 스펙 §2와 이 기본값을 같이 바꾼다. 인터뷰 서비스 env TOOLBOX_PATH가 이 파일을 가리킨다.
param([string]$Version = '1.13.1')
$ErrorActionPreference = 'Stop'
$dir = Join-Path $PSScriptRoot '..\bin'
New-Item -ItemType Directory -Force $dir | Out-Null
$out = Join-Path $dir 'toolbox.exe'
$url = "https://storage.googleapis.com/genai-toolbox/v$Version/windows/amd64/toolbox.exe"
Write-Host "다운로드: $url"
Invoke-WebRequest -Uri $url -OutFile $out
& $out --version
Write-Host "설치 완료: $out  (인터뷰 서비스 .env에 TOOLBOX_PATH=$out)"
```
루트 `.gitignore` 끝에 추가:
```
netismaker-interview-service/bin/
```

- [ ] **Step 3: 설치 실행** — 파일 다운로드이므로 실행 전에 사용자에게 "toolbox.exe(약 100MB대, storage.googleapis.com) 다운로드" 허락을 받는다.

```powershell
powershell -ExecutionPolicy Bypass -File netismaker-interview-service\scripts\install-toolbox.ps1
```
Expected: 마지막 줄 근처에 `1.13.1` 버전 출력.

- [ ] **Step 4: 스파이크 스크립트 작성**

`netismaker-interview-service/scripts/spikeToolbox.mjs`:
```js
// 스파이크(스펙 2026-10-02 §11): Toolbox를 stdio로 띄워 initialize → tools/list → (선택) execute_sql 1회.
// 사용: node scripts/spikeToolbox.mjs <toolbox.exe> <prebuilt> ["SQL"]   — 접속 env(POSTGRES_* 등)는 셸에서 준다.
// stdout에 JSON-RPC가 아닌 줄이 섞이면 [NON-JSON STDOUT]로 표시한다(stdio 오염 확인용). 일회성 도구.
import { spawn } from 'node:child_process'

const [, , toolbox, prebuilt, sql] = process.argv
const child = spawn(toolbox, ['--prebuilt', prebuilt, '--stdio'], { stdio: ['pipe', 'pipe', 'inherit'], env: process.env })
let buf = ''
const pending = new Map()
child.stdout.on('data', (d) => {
  buf += d
  let i
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim()
    buf = buf.slice(i + 1)
    if (!line) continue
    let msg
    try { msg = JSON.parse(line) } catch { console.log('[NON-JSON STDOUT]', line.slice(0, 200)); continue }
    const cb = pending.get(msg.id)
    if (cb) { pending.delete(msg.id); cb(msg) }
  }
})
let id = 0
const call = (method, params) => new Promise((res) => {
  const myId = ++id
  pending.set(myId, res)
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: myId, method, params }) + '\n')
})
const init = await call('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'spike', version: '0' } })
console.log('initialize:', JSON.stringify(init.result?.serverInfo ?? init.error))
child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n')
const tools = await call('tools/list', {})
for (const t of tools.result?.tools ?? []) console.log('tool', t.name, JSON.stringify(Object.keys(t.inputSchema?.properties ?? {})))
if (sql) {
  const r = await call('tools/call', { name: 'execute_sql', arguments: { sql } })
  console.log('execute_sql:', JSON.stringify(r.result ?? r.error).slice(0, 500))
}
child.kill()
```

- [ ] **Step 5: PostgreSQL 스파이크 실행** — 로컬 PostgreSQL에 API가 쓰는 계정으로 붙는다(값은 `public.env`의 DB 항목에서 가져오고 **출력·로그에 비밀번호를 남기지 않는다**). Git Bash:

```bash
cd /c/Users/mic/NetisMaker/commanCenter/.claude/worktrees/question-db-mcp/netismaker-interview-service
export POSTGRES_HOST=localhost POSTGRES_PORT=5432 POSTGRES_DATABASE=<DB명> POSTGRES_USER=<계정>
read -s POSTGRES_PASSWORD; export POSTGRES_PASSWORD
export POSTGRES_QUERY_PARAMS='options=-c%20default_transaction_read_only%3Don'
node scripts/spikeToolbox.mjs bin/toolbox.exe postgres "SHOW default_transaction_read_only"
node scripts/spikeToolbox.mjs bin/toolbox.exe postgres "CREATE TEMP TABLE spike_x(a int)"
```
Expected(확인할 것):
- `[NON-JSON STDOUT]` 줄이 **없다**(있으면 Toolbox가 stdout에 로그를 섞는 것 → 스펙 §11-4 실패, 런처에서 Toolbox 로그 옵션을 찾아 stderr로 보내야 함 — 해결 전 다음 Task로 넘어가지 말고 사용자에게 보고).
- `tool execute_sql ["sql"]`, `tool list_tables [...]` 같은 줄 — 각 도구의 입력 키를 기록.
- 첫 SQL 결과에 `on`이 보이면 read-only 파라미터 적용(§11-1 통과). 둘째 SQL은 `read-only transaction` 오류여야 한다. `off`면 Task 11에서 `PG_READ_ONLY_QUERY_PARAMS`를 빼고 스펙 §8.2 "2선"을 삭제한다.

- [ ] **Step 6: MySQL·Oracle 파라미터 이름 확인** — 로컬 DB가 없으므로 문서로 확인한다.

```bash
for p in mysql/mysql-execute-sql oracle/oracle-execute-sql; do gh api "repos/googleapis/genai-toolbox/contents/docs/en/resources/tools/$p.md" --jq .content 2>/dev/null | base64 -d | grep -n -i -A3 "parameter\|\"sql\"\|sql:" | head -15; done
gh api repos/googleapis/genai-toolbox/contents/internal/prebuiltconfigs/tools/mysql.yaml --jq .content | base64 -d | grep -n -A6 "name: get_query_plan"
gh api repos/googleapis/genai-toolbox/contents/internal/prebuiltconfigs/tools/oracledb.yaml --jq .content | base64 -d | grep -n -A12 "name: get_query_plan"
```
Expected: execute_sql 입력 키 = `sql`(3종 공통), get_query_plan = MySQL `sql_statement` / Oracle `query`. 문서 경로가 다르면 `gh api repos/googleapis/genai-toolbox/git/trees/main?recursive=1 --jq '.tree[].path' | grep execute-sql`로 찾는다. 다르게 나오면 Task 10의 `DB_SQL_PARAMS`를 그 값으로 쓴다.

- [ ] **Step 7: 스펙 §11에 결과 기록** — 각 항목 끝에 `→ 2026-10-02 확인: …`(통과/실패와 실제 값) 한 줄씩 덧붙인다. MariaDB(§11-2)·Oracle EZConnect(§11-5)·SDK stdio(§11-6)는 "라이브 스모크(Task 16)에서 확인"으로 적는다.

- [ ] **Step 8: Commit**

```bash
git add .gitignore netismaker-interview-service/scripts/install-toolbox.ps1 netismaker-interview-service/scripts/spikeToolbox.mjs docs/superpowers/specs/2026-10-02-question-db-mcp-design.md
git commit -m "chore(interview): MCP Toolbox v1.13.1 설치 스크립트 + stdio 스파이크 결과

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
 * MariaDB는 Toolbox에서 mysql prebuilt를 쓰지만, 접속 테스트는 MariaDB 드라이버로 한다.
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
- Modify: `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md` §8.1 (방언 전달로 바뀐 점)
- Test: `netismaker-interview-service/test/permissions.test.ts`

**Interfaces:**
- Consumes: `checkReadOnlySql`, `SqlDialect`(Task 9)
- Produces: `buildCanUseTool(repoDir: string, kind?: SessionKind, attachmentRoot?: string | null, dbServers?: Record<string, SqlDialect>): CanUseTool`; 상수 `DB_SQL_PARAMS: Record<string, string[]>`

- [ ] **Step 1: 실패하는 테스트 작성** — `test/permissions.test.ts` 끝에 추가:

```ts
describe('canUseTool — kind=QUESTION DB 서버 게이트 (스펙 2026-10-02 §8)', () => {
  const q = buildCanUseTool('/tmp/repo', 'QUESTION', null, { 'db-3': 'postgres', 'db-7': 'mysql' });

  it('execute_sql은 조회문만 통과', async () => {
    expect((await q('mcp__db-3__execute_sql', { sql: 'SELECT * FROM users' })).behavior).toBe('allow');
    const r = await q('mcp__db-3__execute_sql', { sql: 'UPDATE users SET a = 1' });
    expect(r.behavior).toBe('deny');
    expect(r.message).toContain('읽기 전용');
    expect(r.message).toContain('UPDATE');
  });

  it('방언을 서버별로 적용한다 — # 주석은 mysql 서버에서만', async () => {
    expect((await q('mcp__db-7__execute_sql', { sql: 'SELECT 1 # 주석' })).behavior).toBe('allow');
    expect((await q('mcp__db-3__execute_sql', { sql: 'SELECT 1 # 1; DELETE FROM t' })).behavior).toBe('deny');
  });

  it('get_query_plan은 SQL 인자를 같은 방식으로 검사, list_*는 허용, 그 외·SQL 없음은 거부', async () => {
    expect((await q('mcp__db-7__get_query_plan', { sql_statement: 'SELECT 1' })).behavior).toBe('allow');
    expect((await q('mcp__db-7__get_query_plan', { sql_statement: 'DELETE FROM t' })).behavior).toBe('deny');
    expect((await q('mcp__db-3__list_tables', { table_names: '' })).behavior).toBe('allow');
    expect((await q('mcp__db-3__list_active_queries', {})).behavior).toBe('allow');
    expect((await q('mcp__db-3__long_running_transactions', {})).behavior).toBe('deny');
    expect((await q('mcp__db-3__execute_sql', {})).behavior).toBe('deny');
  });

  it('등록되지 않은 db-N 서버 이름은 거부(이름 흉내 차단), 기존 서버 규칙은 그대로', async () => {
    expect((await q('mcp__db-99__execute_sql', { sql: 'SELECT 1' })).behavior).toBe('deny');
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
/**
 * DB 서버(`db-<id>`, Toolbox) 도구별 SQL 인자 이름 (스펙 2026-10-02 §8.1 — Task 1 스파이크로 확인한 값).
 * 목록 순서대로 첫 문자열 인자를 검사한다.
 */
export const DB_SQL_PARAMS: Record<string, string[]> = {
  execute_sql: ['sql'],
  get_query_plan: ['sql_statement', 'query', 'sql'],
};

function dbToolGate(toolName: string, tool: string, input: Record<string, unknown>, dialect: SqlDialect): PermissionResult {
  const params = DB_SQL_PARAMS[tool];
  if (params) {
    const key = params.find((k) => typeof input[k] === 'string');
    if (!key) return { behavior: 'deny', message: `질문 세션 DB 도구 입력에 SQL이 없습니다: ${toolName}` };
    const r = checkReadOnlySql(String(input[key]), dialect);
    return r.ok
      ? { behavior: 'allow' }
      : { behavior: 'deny', message: `질문 세션 DB 도구는 읽기 전용입니다 — ${r.reason}. SELECT 계열 단일 문장만 실행할 수 있습니다.` };
  }
  if (tool.startsWith('list_')) return { behavior: 'allow' };
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
  // DB 서버(스펙 2026-10-02 §8.1)는 Obsidian/동사 규칙보다 먼저 — execute_sql의 'execute'가 동사 denylist에 걸리므로.
  const dialect = dbServers[server];
  if (dialect) return dbToolGate(toolName, tool, input, dialect);
  if (/^db-\d+$/.test(server)) return deny; // 이번 턴에 붙이지 않은 db-N — 다른 소스가 이름을 흉내 낸 서버
```
`questionMcpGate` Javadoc 목록 맨 앞에 한 줄: `*  - DB 서버(dbServers에 등록된 db-<id>)는 dbToolGate — SQL 인자를 방언별 읽기 전용 검사(sqlReadOnly.ts).`

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

스펙 §8.1 첫 줄을 다음으로 바꾼다:
```
- 시그니처에 `input`과 `dbServers: Record<serverName, 'postgres'|'mysql'|'oracle'>` 추가 — 러너가 이번 턴에 붙인 DB 서버와 방언을 넘긴다(구현 계획 Task 10: 방언에 따라 주석·인용 규칙이 달라 공통 규칙으로는 우회가 생긴다). `dbServers`에 없는 `db-N`은 거부.
```
그리고 §8.1 `execute_sql` 줄의 괄호 설명(“게이트는 서버명에서 DB 종류를 모르므로…”)을 삭제한다.

- [ ] **Step 4: 통과 확인**

```bash
cd netismaker-interview-service && npx vitest run test/permissions.test.ts
```
Expected: 새 4건 PASS, 기존 실패는 Windows 기존 2건뿐.

- [ ] **Step 5: Commit**

```bash
git add netismaker-interview-service/src/sdk/permissions.ts netismaker-interview-service/test/permissions.test.ts docs/superpowers/specs/2026-10-02-question-db-mcp-design.md
git commit -m "feat(interview): 질문 게이트 DB 서버 분기 — execute_sql 방언별 읽기 전용 검사

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 타입·설정·dbMcp(임시 파일)·런처

**Files:**
- Modify: `netismaker-interview-service/src/types.ts`
- Modify: `netismaker-interview-service/src/config.ts`
- Modify: `netismaker-interview-service/.env.example`
- Create: `netismaker-interview-service/src/sdk/dbMcp.ts`
- Create: `netismaker-interview-service/src/launcher/dbMcpLauncher.mjs`
- Create: `netismaker-interview-service/src/launcher/dbMcpLauncher.d.mts`
- Test: `netismaker-interview-service/test/dbMcp.test.ts`, `netismaker-interview-service/test/dbMcpLauncher.test.ts`, `netismaker-interview-service/test/config.test.ts`

**Interfaces:**
- Consumes: `SqlDialect`(Task 9)
- Produces:
  - `types.ts`: `export type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE'`; `export interface DbConnectionRef { serverName; label; dbType: DbType; host; port: number; database; username; password }`; `InterviewClaimResponse`에 `dbConnectionIds?: number[]; dbConnections?: DbConnectionRef[]; dbNotices?: string[]`
  - `config.ts`: `Config.toolboxPath?: string`, `Config.dbMcpTmpDir: string`
  - `dbMcp.ts`: `prebuiltFor(t)`, `dialectFor(t)`, `PG_READ_ONLY_QUERY_PARAMS`, `envFor(ref)`, `DB_MCP_LAUNCHER_PATH`, `interface StdioServer { type: 'stdio'; command: string; args: string[] }`, `interface PreparedDbMcp { servers: Record<string, StdioServer>; dialects: Record<string, SqlDialect>; files: string[]; labels: Array<{ serverName: string; label: string }>; notices: string[] }`, `prepareDbMcpServers(claim, deps: { toolboxPath?: string; tmpDir: string; launcherPath?: string; nodePath?: string }): PreparedDbMcp`, `cleanupDbMcpFiles(files: string[]): void`, `sweepStaleDbMcpFiles(dir: string, maxAgeMs?: number, now?: number): number`
  - 런처: `PASSTHROUGH_ENV: string[]`, `childEnv(parentEnv, dbEnv)`, `readSpec(file)`, `launch(file, env, spawnFn?)`

- [ ] **Step 1: 실패하는 테스트 작성**

`netismaker-interview-service/test/dbMcp.test.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { existsSync, mkdtempSync, readFileSync, statSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  cleanupDbMcpFiles, dialectFor, envFor, PG_READ_ONLY_QUERY_PARAMS, prebuiltFor, prepareDbMcpServers, sweepStaleDbMcpFiles,
} from '../src/sdk/dbMcp.js';
import type { DbConnectionRef, InterviewClaimResponse } from '../src/types.js';
import { questionClaim } from './fixtures/claims.js';

const ref = (over: Partial<DbConnectionRef> = {}): DbConnectionRef => ({
  serverName: 'db-7', label: '운영 DB (PostgreSQL)', dbType: 'POSTGRESQL', host: 'db.local', port: 5432,
  database: 'app', username: 'reader', password: 'Pw-secret', ...over,
});
const claimWith = (refs: DbConnectionRef[], notices: string[] = []): InterviewClaimResponse =>
  ({ ...questionClaim, dbConnections: refs, dbNotices: notices });
const fakeToolbox = () => { const d = mkdtempSync(join(tmpdir(), 'tbx-')); const p = join(d, 'toolbox.exe'); writeFileSync(p, ''); return p; };

describe('dbMcp 매핑', () => {
  it('prebuilt·방언', () => {
    expect(['POSTGRESQL', 'MYSQL', 'MARIADB', 'ORACLE'].map((t) => prebuiltFor(t as never))).toEqual(['postgres', 'mysql', 'mysql', 'oracledb']);
    expect(dialectFor('MARIADB')).toBe('mysql');
    expect(dialectFor('ORACLE')).toBe('oracle');
  });

  it('env — DB별 Toolbox 변수 이름', () => {
    expect(envFor(ref())).toEqual({
      POSTGRES_HOST: 'db.local', POSTGRES_PORT: '5432', POSTGRES_DATABASE: 'app', POSTGRES_USER: 'reader',
      POSTGRES_PASSWORD: 'Pw-secret', POSTGRES_QUERY_PARAMS: PG_READ_ONLY_QUERY_PARAMS,
    });
    expect(envFor(ref({ dbType: 'MARIADB', port: 3307 }))).toEqual({
      MYSQL_HOST: 'db.local', MYSQL_PORT: '3307', MYSQL_DATABASE: 'app', MYSQL_USER: 'reader', MYSQL_PASSWORD: 'Pw-secret',
    });
    expect(envFor(ref({ dbType: 'ORACLE', port: 1521, database: 'ORCLPDB1' }))).toEqual({
      ORACLE_CONNECTION_STRING: 'db.local:1521/ORCLPDB1', ORACLE_USERNAME: 'reader', ORACLE_PASSWORD: 'Pw-secret', ORACLE_USE_OCI: 'false',
    });
  });
});

describe('prepareDbMcpServers', () => {
  it('연결마다 0600 파일을 쓰고 명령줄에는 런처+파일 경로만 둔다', () => {
    const tmp = mkdtempSync(join(tmpdir(), 'dbmcp-'));
    const p = prepareDbMcpServers(claimWith([ref()], ['참고 1']), { toolboxPath: fakeToolbox(), tmpDir: tmp, launcherPath: '/l.mjs', nodePath: '/node' });
    const s = p.servers['db-7']!;
    expect(s.type).toBe('stdio');
    expect(s.command).toBe('/node');
    expect(s.args[0]).toBe('/l.mjs');
    expect(JSON.stringify(p.servers)).not.toContain('Pw-secret');
    expect(p.dialects).toEqual({ 'db-7': 'postgres' });
    expect(p.labels).toEqual([{ serverName: 'db-7', label: '운영 DB (PostgreSQL)' }]);
    expect(p.notices).toEqual(['참고 1']);
    expect(p.files).toHaveLength(1);
    const spec = JSON.parse(readFileSync(p.files[0]!, 'utf8'));
    expect(spec.prebuilt).toBe('postgres');
    expect(spec.env.POSTGRES_PASSWORD).toBe('Pw-secret');
    if (process.platform !== 'win32') expect(statSync(p.files[0]!).mode & 0o777).toBe(0o600);
    cleanupDbMcpFiles(p.files);
    expect(existsSync(p.files[0]!)).toBe(false);
  });

  it('Toolbox가 없으면 서버 없이 안내만', () => {
    const p = prepareDbMcpServers(claimWith([ref()]), { toolboxPath: undefined, tmpDir: mkdtempSync(join(tmpdir(), 'dbmcp-')) });
    expect(p.servers).toEqual({});
    expect(p.notices).toEqual(['DB 도구를 사용할 수 없습니다(서버에 Toolbox 미설치)']);
  });

  it('연결이 없으면 아무 파일도 만들지 않는다', () => {
    const tmp = mkdtempSync(join(tmpdir(), 'dbmcp-'));
    expect(prepareDbMcpServers(claimWith([]), { toolboxPath: fakeToolbox(), tmpDir: tmp }).files).toEqual([]);
    expect(prepareDbMcpServers({ ...questionClaim }, { toolboxPath: fakeToolbox(), tmpDir: tmp }).files).toEqual([]);
  });

  it('serverName이 db-<숫자>가 아니면 건너뛴다(파일 이름 경로 탈출 방지)', () => {
    const p = prepareDbMcpServers(claimWith([ref({ serverName: '../evil' })]), { toolboxPath: fakeToolbox(), tmpDir: mkdtempSync(join(tmpdir(), 'dbmcp-')) });
    expect(p.files).toEqual([]);
    expect(p.notices[0]).toContain('잘못된 DB 서버 이름');
  });

  it('cleanup은 없는 파일에도 조용하다', () => {
    expect(() => cleanupDbMcpFiles([join(tmpdir(), 'nope-' + Date.now() + '.json')])).not.toThrow();
  });

  it('sweep은 1시간 넘은 json만 지운다', () => {
    const tmp = mkdtempSync(join(tmpdir(), 'dbmcp-'));
    const old = join(tmp, '1-a-db-1.json'); const fresh = join(tmp, '1-b-db-1.json'); const other = join(tmp, 'keep.txt');
    for (const f of [old, fresh, other]) writeFileSync(f, '{}');
    const now = Date.now();
    utimesSync(old, (now - 2 * 3600_000) / 1000, (now - 2 * 3600_000) / 1000);
    utimesSync(other, (now - 2 * 3600_000) / 1000, (now - 2 * 3600_000) / 1000);
    expect(sweepStaleDbMcpFiles(tmp, 3600_000, now)).toBe(1);
    expect(existsSync(old)).toBe(false);
    expect(existsSync(fresh)).toBe(true);
    expect(existsSync(other)).toBe(true);
    expect(sweepStaleDbMcpFiles(join(tmp, 'missing'))).toBe(0);
  });
});
```

`netismaker-interview-service/test/dbMcpLauncher.test.ts`:
```ts
import { describe, expect, it, vi } from 'vitest';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { childEnv, launch, readSpec } from '../src/launcher/dbMcpLauncher.mjs';

const LAUNCHER = fileURLToPath(new URL('../src/launcher/dbMcpLauncher.mjs', import.meta.url));
const tmpFile = (content: string) => { const f = join(mkdtempSync(join(tmpdir(), 'ln-')), 'spec.json'); writeFileSync(f, content); return f; };

describe('dbMcpLauncher', () => {
  it('childEnv는 OS 필수 변수와 DB env만 남긴다', () => {
    const env = childEnv({ PATH: '/bin', SYSTEMROOT: 'C:\\Windows', GITHUB_PAT: 'ghp_x', WORKER_API_KEY: 'k' }, { MYSQL_HOST: 'h' });
    expect(env).toEqual({ PATH: '/bin', SYSTEMROOT: 'C:\\Windows', MYSQL_HOST: 'h' });
  });

  it('readSpec_error_never_echoes_content', () => {
    // Review Focus 5: JSON.parse 오류 메시지는 본문 일부를 담는다
    const f = tmpFile('{"env":{"POSTGRES_PASSWORD":"Pw-secret"');
    expect(() => readSpec(f)).toThrowError('런처 파일을 읽을 수 없습니다');
    try { readSpec(f); } catch (e) { expect(String(e)).not.toContain('Pw-secret'); }
    expect(() => readSpec(tmpFile('{"toolboxPath":1}'))).toThrowError('런처 파일 형식이 올바르지 않습니다');
  });

  it('launch는 Toolbox를 --prebuilt <p> --stdio, stdio inherit로 띄운다', () => {
    const f = tmpFile(JSON.stringify({ toolboxPath: '/t/toolbox', prebuilt: 'mysql', env: { MYSQL_HOST: 'h' } }));
    const spawnFn = vi.fn().mockReturnValue({ on: vi.fn() });
    launch(f, { PATH: '/bin', GITHUB_PAT: 'x' }, spawnFn as never);
    expect(spawnFn).toHaveBeenCalledWith('/t/toolbox', ['--prebuilt', 'mysql', '--stdio'],
      { stdio: 'inherit', env: { PATH: '/bin', MYSQL_HOST: 'h' }, windowsHide: true });
  });

  it('직접 실행 — 자식 종료 코드를 그대로 돌려준다, 깨진 파일은 1 + 고정 문구', () => {
    // 가짜 Toolbox = node 자신: '--prebuilt'를 모르는 옵션으로 보고 0이 아닌 코드로 끝난다.
    const f = tmpFile(JSON.stringify({ toolboxPath: process.execPath, prebuilt: 'x', env: {} }));
    const r = spawnSync(process.execPath, [LAUNCHER, f], { encoding: 'utf8' });
    expect(r.status).not.toBe(0);
    const bad = spawnSync(process.execPath, [LAUNCHER, tmpFile('{"x":"Pw-secret"')], { encoding: 'utf8' });
    expect(bad.status).toBe(1);
    expect(bad.stderr).toContain('[dbMcpLauncher]');
    expect(bad.stderr).not.toContain('Pw-secret');
  });
});
```

`test/config.test.ts` 끝에 추가(파일의 기존 `loadConfig` 호출 방식에 맞춰 필수 env 객체를 재사용한다):
```ts
describe('loadConfig — DB MCP (스펙 2026-10-02 §6.1)', () => {
  const base = { API_BASE_URL: 'http://x', WORKER_API_KEY: 'k', WORKER_ID: 'w', SUPERPOWERS_PLUGIN_PATH: '/sp' };
  it('TOOLBOX_PATH는 선택, DB_MCP_TMP_DIR 기본값은 tmpdir 아래', async () => {
    const { loadConfig } = await import('../src/config.js');
    const c = loadConfig(base);
    expect(c.toolboxPath).toBeUndefined();
    expect(c.dbMcpTmpDir).toMatch(/netismaker-dbmcp$/);
    const c2 = loadConfig({ ...base, TOOLBOX_PATH: 'C:/t/toolbox.exe', DB_MCP_TMP_DIR: 'C:/tmp/x' });
    expect(c2.toolboxPath).toBe('C:/t/toolbox.exe');
    expect(c2.dbMcpTmpDir).toBe('C:/tmp/x');
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd netismaker-interview-service && npx vitest run test/dbMcp.test.ts test/dbMcpLauncher.test.ts test/config.test.ts
```
Expected: FAIL(모듈 없음).

- [ ] **Step 3: 타입·설정**

`src/types.ts` — `McpSpec` 인터페이스 아래에:
```ts
/** Java DbType (스펙 2026-10-02 §4). */
export type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE';

/**
 * Java InterviewClaimResponse.DbConnectionRef — 복호화된 DB 접속정보(질문 세션 claim 전용).
 * ⚠️ password 평문: 로그·오류 메시지·SDK options(명령줄)에 넣지 말 것 — dbMcp.ts가 임시 파일로만 넘긴다.
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

`src/config.ts`:
- 상단에 `import { tmpdir } from 'node:os';`, `import { join } from 'node:path';`
- `Config`에:
```ts
  /** MCP Toolbox 바이너리 경로(선택, 스펙 2026-10-02 §6.1). 없으면 질문 세션 DB 도구를 붙이지 않는다. */
  toolboxPath?: string;
  /** 턴별 DB 접속정보 임시 파일 디렉터리. 기본 os.tmpdir()/netismaker-dbmcp. */
  dbMcpTmpDir: string;
```
- `loadConfig` 반환 객체 끝에:
```ts
    toolboxPath: env.TOOLBOX_PATH || undefined,
    dbMcpTmpDir: env.DB_MCP_TMP_DIR || join(tmpdir(), 'netismaker-dbmcp'),
```

`.env.example` 끝에:
```
# 질문 세션 DB 도구(스펙 2026-10-02): MCP Toolbox 바이너리. scripts/install-toolbox.ps1이 bin/toolbox.exe로 받는다.
# 비우면 DB 연결을 골라도 DB 도구 없이 답한다(프롬프트에 안내).
# TOOLBOX_PATH=C:\Users\mic\NetisMaker\commanCenter\netismaker-interview-service\bin\toolbox.exe
# 턴별 DB 접속정보 임시 파일 위치(기본 OS 임시 폴더\netismaker-dbmcp). 턴이 끝나면 지워진다.
# DB_MCP_TMP_DIR=
```

- [ ] **Step 4: dbMcp.ts**

`src/sdk/dbMcp.ts`:
```ts
import { existsSync, mkdirSync, readdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import type { DbConnectionRef, DbType, InterviewClaimResponse } from '../types.js';
import type { SqlDialect } from './sqlReadOnly.js';

/**
 * 질문 세션 DB MCP 준비 (스펙 2026-10-02 §6.2). SDK가 options.mcpServers를 통째로 claude CLI 명령줄(--mcp-config)에
 * 싣기 때문에 비밀번호를 env로 넣지 않는다 — 접속정보는 턴마다 임시 파일(0600)에 쓰고, mcpServers에는
 * `node dbMcpLauncher.mjs <파일>`만 둔다. 러너가 턴 종료 finally에서 cleanupDbMcpFiles로 지운다.
 */

/** 런처는 tsx 없이 돌아야 한다(claude CLI가 cwd=레포 체크아웃에서 실행) — 그래서 .mjs. */
export const DB_MCP_LAUNCHER_PATH = fileURLToPath(new URL('../launcher/dbMcpLauncher.mjs', import.meta.url));

/** PG 세션 기본 read-only (스펙 §8.2 2선). Task 1 스파이크에서 적용이 확인되지 않으면 이 상수와 사용처를 지운다. */
export const PG_READ_ONLY_QUERY_PARAMS = 'options=' + encodeURIComponent('-c default_transaction_read_only=on');

const SERVER_NAME = /^db-\d+$/;

export interface StdioServer {
  type: 'stdio';
  command: string;
  args: string[];
}

export interface PreparedDbMcp {
  servers: Record<string, StdioServer>;
  dialects: Record<string, SqlDialect>;
  files: string[];
  labels: Array<{ serverName: string; label: string }>;
  notices: string[];
}

export interface DbMcpDeps {
  toolboxPath?: string;
  tmpDir: string;
  /** 테스트용 주입 — 기본 DB_MCP_LAUNCHER_PATH / process.execPath. */
  launcherPath?: string;
  nodePath?: string;
}

export function prebuiltFor(t: DbType): string {
  if (t === 'POSTGRESQL') return 'postgres';
  if (t === 'ORACLE') return 'oracledb';
  return 'mysql'; // MYSQL, MARIADB
}

export function dialectFor(t: DbType): SqlDialect {
  if (t === 'POSTGRESQL') return 'postgres';
  if (t === 'ORACLE') return 'oracle';
  return 'mysql';
}

/** Toolbox prebuilt 설정의 env 이름 (internal/prebuiltconfigs/tools/*.yaml, v1.13.1). */
export function envFor(ref: DbConnectionRef): Record<string, string> {
  const port = String(ref.port);
  switch (ref.dbType) {
    case 'POSTGRESQL':
      return {
        POSTGRES_HOST: ref.host, POSTGRES_PORT: port, POSTGRES_DATABASE: ref.database, POSTGRES_USER: ref.username,
        POSTGRES_PASSWORD: ref.password, POSTGRES_QUERY_PARAMS: PG_READ_ONLY_QUERY_PARAMS,
      };
    case 'ORACLE':
      return {
        ORACLE_CONNECTION_STRING: `${ref.host}:${port}/${ref.database}`, ORACLE_USERNAME: ref.username,
        ORACLE_PASSWORD: ref.password, ORACLE_USE_OCI: 'false',
      };
    default:
      return {
        MYSQL_HOST: ref.host, MYSQL_PORT: port, MYSQL_DATABASE: ref.database, MYSQL_USER: ref.username,
        MYSQL_PASSWORD: ref.password,
      };
  }
}

export function prepareDbMcpServers(claim: InterviewClaimResponse, deps: DbMcpDeps): PreparedDbMcp {
  const refs = Array.isArray(claim.dbConnections) ? claim.dbConnections : [];
  const notices = Array.isArray(claim.dbNotices) ? [...claim.dbNotices] : [];
  const prepared: PreparedDbMcp = { servers: {}, dialects: {}, files: [], labels: [], notices };
  if (refs.length === 0) return prepared;
  if (!deps.toolboxPath || !existsSync(deps.toolboxPath)) {
    notices.push('DB 도구를 사용할 수 없습니다(서버에 Toolbox 미설치)');
    return prepared;
  }
  mkdirSync(deps.tmpDir, { recursive: true });
  try {
    for (const ref of refs) {
      if (!SERVER_NAME.test(ref.serverName)) {
        notices.push(`잘못된 DB 서버 이름이라 건너뜁니다: ${ref.label}`);
        continue;
      }
      const file = join(deps.tmpDir, `${claim.sessionId}-${randomUUID()}-${ref.serverName}.json`);
      prepared.files.push(file); // write 전에 — 부분 쓰기도 정리 대상
      writeFileSync(
        file,
        JSON.stringify({ toolboxPath: deps.toolboxPath, prebuilt: prebuiltFor(ref.dbType), env: envFor(ref) }),
        { mode: 0o600, flag: 'wx' },
      );
      prepared.servers[ref.serverName] = {
        type: 'stdio',
        command: deps.nodePath ?? process.execPath,
        args: [deps.launcherPath ?? DB_MCP_LAUNCHER_PATH, file],
      };
      prepared.dialects[ref.serverName] = dialectFor(ref.dbType);
      prepared.labels.push({ serverName: ref.serverName, label: ref.label });
    }
  } catch (e) {
    cleanupDbMcpFiles(prepared.files);
    throw e;
  }
  return prepared;
}

export function cleanupDbMcpFiles(files: string[]): void {
  for (const f of files) {
    try {
      unlinkSync(f);
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'ENOENT') {
        // eslint-disable-next-line no-console
        console.warn(`[dbMcp] 임시 파일 삭제 실패: ${f} (${(e as NodeJS.ErrnoException).code ?? 'unknown'})`);
      }
    }
  }
}

/** 크래시 잔여물 정리 — 서비스 기동 시 1회. 지운 개수. 디렉터리가 없으면 0. */
export function sweepStaleDbMcpFiles(dir: string, maxAgeMs = 3_600_000, now = Date.now()): number {
  let names: string[];
  try {
    names = readdirSync(dir);
  } catch {
    return 0;
  }
  let removed = 0;
  for (const name of names) {
    if (!name.endsWith('.json')) continue;
    const f = join(dir, name);
    try {
      if (now - statSync(f).mtimeMs > maxAgeMs) {
        unlinkSync(f);
        removed++;
      }
    } catch {
      // 다른 프로세스가 먼저 지웠거나 잠김 — 다음 기동 때 다시 본다
    }
  }
  return removed;
}
```

- [ ] **Step 5: 런처**

`src/launcher/dbMcpLauncher.mjs`:
```js
// 질문 세션 DB MCP 런처 (스펙 2026-10-02 §6.3). claude CLI가 stdio MCP 서버로 실행한다:
//   node dbMcpLauncher.mjs <접속정보 파일>
// 파일({toolboxPath, prebuilt, env})을 읽어 Toolbox를 `--prebuilt <p> --stdio`로 띄우고 stdio를 그대로 물려준다.
// 자식 env는 OS 필수 변수 + DB env만 — 인터뷰 서비스의 GITHUB_PAT·WORKER_API_KEY 등은 넘기지 않는다.
// 파일은 지우지 않는다(CLI가 턴 도중 MCP를 재기동해도 다시 읽게) — 삭제는 러너 책임.
// 오류 메시지는 고정 문구만: JSON.parse 오류는 본문 일부(=비밀번호)를 담을 수 있다.
// tsx 없이 실행돼야 하므로 순수 JS(.mjs). 타입은 dbMcpLauncher.d.mts.
import { readFileSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { pathToFileURL } from 'node:url'

export const PASSTHROUGH_ENV = ['PATH', 'Path', 'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'windir', 'TEMP', 'TMP', 'HOME', 'USERPROFILE']

export function childEnv(parentEnv, dbEnv) {
  const out = {}
  for (const k of PASSTHROUGH_ENV) if (parentEnv[k] !== undefined) out[k] = parentEnv[k]
  return { ...out, ...dbEnv }
}

export function readSpec(file) {
  let spec
  try {
    spec = JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    throw new Error('런처 파일을 읽을 수 없습니다')
  }
  if (!spec || typeof spec.toolboxPath !== 'string' || typeof spec.prebuilt !== 'string'
      || typeof spec.env !== 'object' || spec.env === null) {
    throw new Error('런처 파일 형식이 올바르지 않습니다')
  }
  return spec
}

export function launch(file, env, spawnFn = spawn) {
  const spec = readSpec(file)
  return spawnFn(spec.toolboxPath, ['--prebuilt', spec.prebuilt, '--stdio'], {
    stdio: 'inherit',
    env: childEnv(env, spec.env),
    windowsHide: true,
  })
}

const isMain = process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMain) {
  let child
  try {
    if (!process.argv[2]) throw new Error('접속정보 파일 경로 인자가 없습니다')
    child = launch(process.argv[2], process.env)
  } catch (e) {
    process.stderr.write(`[dbMcpLauncher] 시작 실패: ${e instanceof Error ? e.message : 'unknown'}\n`)
    process.exit(1)
  }
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => child.kill(sig))
  child.on('error', (e) => {
    process.stderr.write(`[dbMcpLauncher] Toolbox 실행 실패: ${e.code ?? 'unknown'}\n`)
    process.exit(1)
  })
  child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)))
}
```

`src/launcher/dbMcpLauncher.d.mts`:
```ts
import type { ChildProcess, spawn } from 'node:child_process';

export declare const PASSTHROUGH_ENV: string[];
export declare function childEnv(
  parentEnv: Record<string, string | undefined>,
  dbEnv: Record<string, string>,
): Record<string, string>;
export declare function readSpec(file: string): { toolboxPath: string; prebuilt: string; env: Record<string, string> };
export declare function launch(
  file: string,
  env: Record<string, string | undefined>,
  spawnFn?: typeof spawn,
): ChildProcess;
```
`tsconfig.json`의 `include`는 `src/**/*.ts`라 `.d.mts`는 import 시 자동 해석된다. `npm run build`가 TS7016(선언 없음)을 내면 `include`에 `"src/**/*.d.mts"`를 추가한다.

- [ ] **Step 6: 통과 확인 + 타입 검사**

```bash
cd netismaker-interview-service && npx vitest run test/dbMcp.test.ts test/dbMcpLauncher.test.ts test/config.test.ts && npx tsc -p tsconfig.json --noEmit
```
Expected: 전부 PASS, tsc 오류 0.

- [ ] **Step 7: Commit**

```bash
git add netismaker-interview-service/src/types.ts netismaker-interview-service/src/config.ts netismaker-interview-service/.env.example netismaker-interview-service/src/sdk/dbMcp.ts netismaker-interview-service/src/launcher netismaker-interview-service/test/dbMcp.test.ts netismaker-interview-service/test/dbMcpLauncher.test.ts netismaker-interview-service/test/config.test.ts netismaker-interview-service/tsconfig.json
git commit -m "feat(interview): DB MCP 임시 파일 준비·정리 + Toolbox 런처

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 세션 옵션·러너·부팅에 연결

**Files:**
- Modify: `netismaker-interview-service/src/sdk/sessionOptions.ts`
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts` (`RunnerDeps`, `questionPromptFor`, `runQuestionTurn`, 새 `dbSection`)
- Modify: `netismaker-interview-service/src/index.ts`
- Test: `netismaker-interview-service/test/sessionOptions.test.ts`, `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: `prepareDbMcpServers`, `cleanupDbMcpFiles`, `sweepStaleDbMcpFiles`, `PreparedDbMcp`(Task 11), `buildCanUseTool(..., dbServers)`(Task 10)
- Produces: `SessionOptionsInput.dbMcpServers?: Record<string, unknown>`, `SessionOptionsInput.dbDialects?: Record<string, SqlDialect>`; `RunnerDeps.toolboxPath?`, `RunnerDeps.dbMcpTmpDir?`, `RunnerDeps.prepareDbMcp?`, `RunnerDeps.cleanupDbMcp?`; `export function dbSection(db: Pick<PreparedDbMcp, 'labels' | 'notices'>): string`

- [ ] **Step 1: 실패하는 테스트 작성**

`test/sessionOptions.test.ts` 끝에:
```ts
describe('buildOptions — DB MCP (스펙 2026-10-02 §6.4)', () => {
  const dbServer = { type: 'stdio', command: '/node', args: ['/l.mjs', '/tmp/f.json'] };

  it('db 서버를 마지막에 머지(같은 이름이면 db가 이긴다), QUESTION 사전승인은 여전히 없음', () => {
    const o = buildOptions({
      ...base, claudeSessionId: null, sessionKind: 'QUESTION',
      mcpsBase: { 'db-7': { type: 'http', url: 'http://fake' }, obsidian: { type: 'http', url: 'http://o' } },
      dbMcpServers: { 'db-7': dbServer },
      dbDialects: { 'db-7': 'mysql' },
    });
    expect((o.mcpServers as Record<string, unknown>)['db-7']).toEqual(dbServer);
    expect(Object.keys(o.mcpServers as object)).toContain('obsidian');
    expect(o.allowedTools).toEqual([]);
  });

  it('canUseTool이 dbDialects를 받아 DB 쓰기를 막는다', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', dbMcpServers: { 'db-7': dbServer }, dbDialects: { 'db-7': 'mysql' } });
    const gate = o.canUseTool as (n: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
    expect((await gate('mcp__db-7__execute_sql', { sql: 'SELECT 1' })).behavior).toBe('allow');
    expect((await gate('mcp__db-7__execute_sql', { sql: 'DELETE FROM t' })).behavior).toBe('deny');
  });

  it('명령줄로 가는 options 어디에도 비밀번호가 없다(파일 경로만)', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', dbMcpServers: { 'db-7': dbServer } });
    expect(JSON.stringify(o.mcpServers)).not.toMatch(/PASSWORD/i);
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
    const prepared = {
      servers: { 'db-7': { type: 'stdio' as const, command: '/node', args: ['/l.mjs', '/tmp/77-x-db-7.json'] } },
      dialects: { 'db-7': 'mysql' as const },
      files: ['/tmp/77-x-db-7.json'],
      labels: [{ serverName: 'db-7', label: '운영 DB (MySQL)' }],
      notices: ['DB 연결 #9를 사용할 수 없습니다 — 삭제되었거나 비활성화됨'],
    };

    it('DB 서버를 붙이고 프롬프트 앞에 서버↔DB 매핑·안내를 넣고, 끝나면 파일을 지운다', async () => {
      const client = makeClient();
      const { fakeQuery, captured } = capturing(() => questionStream());
      const prepareDbMcp = vi.fn().mockReturnValue(prepared);
      const cleanupDbMcp = vi.fn();
      const runner = new InterviewRunner(client as never, fakeQuery as never,
        { ...deps, toolboxPath: '/t/toolbox.exe', dbMcpTmpDir: '/tmp', prepareDbMcp, cleanupDbMcp } as never);
      await runner.run(dbClaim);

      expect(prepareDbMcp).toHaveBeenCalledWith(dbClaim, { toolboxPath: '/t/toolbox.exe', tmpDir: '/tmp' });
      expect((captured.options.mcpServers as Record<string, unknown>)['db-7']).toEqual(prepared.servers['db-7']);
      expect(captured.prompt.startsWith('사용 가능한 DB 도구')).toBe(true);
      expect(captured.prompt).toContain('mcp__db-7__* : 운영 DB (MySQL)');
      expect(captured.prompt).toContain('참고: DB 연결 #9를 사용할 수 없습니다');
      expect(captured.prompt).toContain('로그인은 어디서 처리되나요?');
      expect(captured.prompt).not.toContain('Pw-secret');
      expect(cleanupDbMcp).toHaveBeenCalledWith(prepared.files);
    });

    it('cleans_up_env_files_when_query_throws', async () => {
      // Review Focus 5
      const client = makeClient();
      const fakeQuery = vi.fn(() => { throw new Error('boom'); });
      const cleanupDbMcp = vi.fn();
      const runner = new InterviewRunner(client as never, fakeQuery as never,
        { ...deps, prepareDbMcp: vi.fn().mockReturnValue(prepared), cleanupDbMcp } as never);
      await runner.run(dbClaim);
      expect(cleanupDbMcp).toHaveBeenCalledWith(prepared.files);
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
   * 이번 턴의 DB MCP 서버(스펙 2026-10-02 §6.4) — dbMcp.prepareDbMcpServers 결과. stdio {command,args}만(비밀번호 없음).
   * merge 순서 base → extras → db(마지막 우선).
   */
  dbMcpServers?: Record<string, unknown>;
  /** DB 서버별 SQL 방언 — QUESTION 게이트가 execute_sql을 검사할 때 쓴다(permissions.ts dbToolGate). */
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
- `buildOptions` Javadoc의 `- MCP:` 항목 끝에 한 문장: `질문 세션 DB 서버(dbMcpServers)는 마지막에 머지 — 런처 경로만 담기므로 --mcp-config 명령줄에 비밀번호가 실리지 않는다.`

- [ ] **Step 4: interviewRunner.ts**

- import 추가:
```ts
import { cleanupDbMcpFiles, prepareDbMcpServers, type PreparedDbMcp } from '../sdk/dbMcp.js';
```
- `RunnerDeps`에:
```ts
  /** MCP Toolbox 경로(선택) — 질문 세션 DB 도구 (스펙 2026-10-02 §6). */
  toolboxPath?: string;
  /** DB 접속정보 임시 파일 디렉터리 (config.dbMcpTmpDir). */
  dbMcpTmpDir?: string;
  /** 테스트용 주입 — 기본 dbMcp.prepareDbMcpServers / cleanupDbMcpFiles. */
  prepareDbMcp?: typeof prepareDbMcpServers;
  cleanupDbMcp?: typeof cleanupDbMcpFiles;
```
- `questionPromptFor` 위에 추가:
```ts
/**
 * DB 도구 안내 (스펙 2026-10-02 §6.4). 서버마다 도구 이름이 같으므로(execute_sql) 서버↔DB 매핑을 매 턴 알려 준다.
 * 연결도 안내도 없으면 빈 문자열 — 프롬프트가 기존과 같다.
 */
export function dbSection(db: Pick<PreparedDbMcp, 'labels' | 'notices'>): string {
  if (db.labels.length === 0 && db.notices.length === 0) return '';
  const lines: string[] = [];
  if (db.labels.length > 0) {
    lines.push('사용 가능한 DB 도구 (읽기 전용 — SELECT 계열 단일 문장만 실행됩니다):');
    for (const l of db.labels) lines.push(`- mcp__${l.serverName}__* : ${l.label}`);
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
    // DB MCP(스펙 2026-10-02 §6.4): 턴마다 임시 파일을 쓰고, 성공·실패와 무관하게 finally에서 지운다.
    const db = (this.deps.prepareDbMcp ?? prepareDbMcpServers)(claim, {
      toolboxPath: this.deps.toolboxPath,
      tmpDir: this.deps.dbMcpTmpDir ?? '',
    });
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
      (this.deps.cleanupDbMcp ?? cleanupDbMcpFiles)(db.files);
    }
```
`tmpDir`가 빈 문자열인데 연결이 있으면 `prepareDbMcpServers`가 cwd에 파일을 만들 수 있다 → `index.ts`가 항상 `cfg.dbMcpTmpDir`를 넘기므로 운영 경로에서는 비지 않는다. 테스트 외 경로에서 비지 않도록 Step 5에서 반드시 넘긴다.

- [ ] **Step 5: index.ts**

- import에 `import { sweepStaleDbMcpFiles } from './sdk/dbMcp.js';`
- `const mcpsBase = loadBaseMcpServers();` 아래에:
```ts
  // 지난 실행이 크래시로 남긴 DB 접속정보 임시 파일 정리 (스펙 2026-10-02 §6.2).
  const swept = sweepStaleDbMcpFiles(cfg.dbMcpTmpDir);
```
- `new InterviewRunner(client, realQuery, { … })` 객체에 `toolboxPath: cfg.toolboxPath,`와 `dbMcpTmpDir: cfg.dbMcpTmpDir,` 추가.
- 기동 로그 문자열 끝 `]` 뒤에 ` toolbox=${cfg.toolboxPath ? 'on' : 'off'}${swept ? ` dbmcp-swept=${swept}` : ''}` 추가.

- [ ] **Step 6: 통과 확인 + 전체**

```bash
cd netismaker-interview-service && npx vitest run 2>&1 | tail -8 && npx tsc -p tsconfig.json --noEmit
```
Expected: 실패는 Task 0 기준선의 기존 5건뿐, tsc 오류 0.

- [ ] **Step 7: Commit**

```bash
git add netismaker-interview-service/src/sdk/sessionOptions.ts netismaker-interview-service/src/runner/interviewRunner.ts netismaker-interview-service/src/index.ts netismaker-interview-service/test/sessionOptions.test.ts netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat(interview): 질문 턴에 DB MCP 서버 부착 + 프롬프트 안내 + finally 정리

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
**질문 세션 DB 연결(2026-10-02, 스펙 `docs/superpowers/specs/2026-10-02-question-db-mcp-design.md`)**: 레포별 DB 접속정보(`com.db_connection`, REPO=관리자 공용·USER=개인, 비밀번호는 `DbSecretCipher` AES-GCM 암호문 — 키 `NETISMAKER_DB_SECRET_KEY`+`NETISMAKER_DB_SECRET_SALT`, 없으면 기능만 꺼짐)를 질문 등록·`ask` 바디 `dbConnectionIds`(MCP와 같은 null/[] 규칙, 상한 3)로 고르면, claim 때 `InterviewWorkerController`가 복호화해 `dbConnections`로 싣고 러너가 턴마다 임시 파일(`DB_MCP_TMP_DIR`, 0600, finally 삭제) + `node dbMcpLauncher.mjs <파일>` stdio 서버(`db-<id>`)로 MCP Toolbox(`TOOLBOX_PATH`, v1.13.1)를 붙인다. SDK가 `mcpServers`를 `--mcp-config` 명령줄에 싣기 때문에 **env에 비밀번호를 넣지 말 것**. 질문 게이트는 `db-<id>` 서버의 `execute_sql`/`get_query_plan`을 `sqlReadOnly.ts`로 방언별 검사(단일 조회문만). MCP 카탈로그 이름 `db-*`는 예약. 배포 순서: API → 인터뷰 서비스(+`install-toolbox.ps1`) → 프론트.
```
"자주 보는 코드" 표에 행 추가:
```
| 질문 세션 DB 접속정보 (CRUD·권한·선택 검증·claim 복호화 · JDBC 접속 테스트 · 암호화) | `service/DbConnectionService.java`, `controller/DbConnectionController.java`, `service/JdbcDbConnectionTester.java`, `service/DbSecretCipher.java`, `entity/DbConnection.java`, 마이그레이션 `V24__db_connection.sql` |
| 질문 세션 DB MCP (임시 파일·런처·읽기 전용 SQL 게이트) | `netismaker-interview-service/src/sdk/dbMcp.ts`, `src/launcher/dbMcpLauncher.mjs`, `src/sdk/sqlReadOnly.ts`, `src/sdk/permissions.ts`(`dbToolGate`), 설치 `scripts/install-toolbox.ps1` |
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
  2. 인터뷰 서비스 `.env`에 `TOOLBOX_PATH=<본 체크아웃>\netismaker-interview-service\bin\toolbox.exe`(본 체크아웃에서 `install-toolbox.ps1` 실행).
  3. API → 인터뷰 서비스 재기동(작업 스케줄러 `netisMaker-public` 경유), 프론트는 `NUXT_IGNORE_LOCK=1 npm run build` 후 재기동.
  4. API 로그에 `DB 접속정보 기능 꺼짐`이 **없는지**, 인터뷰 서비스 로그에 `toolbox=on`이 있는지 확인.

- [ ] **Step 5: 라이브 스모크 (공개 주소 `app-win`에서 시작)** — DB 종류마다:
  1. 새 질문에서 레포 선택 → DB 버튼 → 새 접속 추가 → 접속 테스트 `접속 성공` → 저장.
  2. "테이블 목록과 주문 건수 알려줘" 질문 → 답변에 실제 테이블·집계가 나오는지.
  3. "주문 1번 상태를 취소로 UPDATE 해줘" → 활동 스트림/답변에 게이트 거부(읽기 전용) 문구.
  4. 대화 중 DB 해제 → `DB 연결 변경: 없음(전부 해제)` 노트 즉시 표시 → 다음 답변에서 DB 도구 미사용.
  5. 턴 진행 중 PowerShell로 명령줄 점검:
     ```powershell
     Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'claude.exe','node.exe','toolbox.exe' } | Select-Object Name, CommandLine | Format-List
     ```
     어떤 명령줄에도 비밀번호가 없어야 한다(런처는 파일 경로만).
  6. 턴이 끝난 뒤 `DB_MCP_TMP_DIR`(기본 `$env:TEMP\netismaker-dbmcp`)이 비었는지.
  결과를 스펙 §11-2·5·6에 `→ 라이브 확인: …`으로 적고 커밋한다.

- [ ] **Step 6: 정리** — worktree 정션 제거(`cmd /c rmdir frontend\node_modules`, `cmd /c rmdir netismaker-interview-service\node_modules`). worktree 자체 삭제는 PR 머지 후 사용자 확인을 받아서.
