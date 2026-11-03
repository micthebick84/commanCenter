# 작업(Task) — 작업 타입(신규 개발·기능 수정·버그 픽스·DB 작업)과 구현 단계 개발 DB 쓰기 설계

- 날짜: 2026-11-03
- 상태: 사용자 승인됨 2026-11-03 (brainstorming: 코드 현황 조사 → 질의 6건 확인 → 검토자 4관점 확인 → 질의 2건(DB 작업은 배포 없이 종료 허용, 자동 마이그레이션 레포는 다르게) — 아래 §3)
- 범위: netisMaker 백엔드(API + 워커 데몬) + 인터뷰 서비스(프롬프트만) + 프론트엔드(Nuxt)
- 선행 조건: PR #122(수정 요청, `feat/fix-request-same-branch`, V38) 병합 뒤 구현한다. 이 브랜치는 #122 head(`c87f82d`) 위에 있다 — #122가 main에 병합되면 main으로 rebase한다. 같은 파일(`Task.java`·`TaskResponse.java`·`TaskService.java`·`WorkerMainLoop.java`·`WorkerTaskResponse.java`·`tasks/index.vue`·`tasks/[id].vue`)을 고치고, 이 기능의 마이그레이션은 **V39**다.
- 표기: "(Claude 판단)"은 사용자가 정하지 않았고 설계자가 정한 것이다. 빌더가 더 나은 근거가 있으면 바꿔도 되지만, 바꾸면 PR 본문에 적는다.

## 1. 목표

1. 작업을 등록할 때 **타입**을 고른다: 신규 개발 · 기능 수정 · 버그 픽스 · DB 작업.
   타입은 권한을 가르지 않는다. 작업의 성격을 나타내며, 인터뷰·구현 지시와 커밋·PR 제목, 목록 표시에 쓰인다.
2. **구현 단계**에서 구현 세션이 함께 쓰는 **개발 DB**에 테이블 스크립트를 실행하고 가데이터를 넣을 수 있게 한다. 네 타입 모두 해당한다.
   - 이유: 새 화면·기능을 만들면 테이블이 생기거나 바뀐다. 그런데 지금은 DB가 읽기 전용이라 코드만 만들어진다. 그 결과 "코드에는 새 컬럼이 있는데 함께 쓰는 개발 DB에는 없어서 화면 진입 시 SQL 오류"가 반복됐다(사용자가 겪은 주된 충돌).
   - 핵심: 코드를 바꾸는 그 시점에 DB도 같이 맞춘다.
3. 개발 DB는 **다른 개발자와 다른 작업도 함께 쓴다.** 그래서 다른 사람의 화면을 깨뜨리는 변경은 바로 실행하지 않고 관리자 승인을 거친다(§6.2).

## 2. 배경 — 현재 동작 (main `b991069`)

- **타입 개념이 없다.** 엔티티·DTO·화면·프롬프트 어디에도 없다. 등록 바디는 `TaskCreateRequest`(repoCatalogId, githubBranch, title, description, refRepos, sourceQuestionId)뿐이다.
- **인터뷰 시작 문구가 하나로 고정돼 있다.** "I want to add a feature…"(`netismaker-interview-service/src/runner/interviewRunner.ts:160` 부근)로 시작하고, 버그 픽스도 새 기능처럼 설계한다.
- **커밋 앞머리가 하나로 고정돼 있다.** 커밋은 `"feat: " + title`(`WorkerMainLoop.java:437`)이다. PR 제목은 앞머리 없이 제목 그대로다(`:453-455` → GitHub `--title title`(`GitHubPrCreator.java:86`), GitLab `"Draft: " + title`(`GitLabMrCreator.java:55`)). 브랜치는 `{prefix}task-{id}-{slug}[-rN]`이고 prefix는 전역 `netismaker/`다(`WorktreeService.java:123-132`, `application-worker.yml:85`).
- **DB 연결은 모든 단계에서 읽기 전용이다.** 막는 장치가 세 겹이다.
  1. SQL 검사: `SqlReadOnlyChecker.java:22-39`, TS `sqlReadOnly.ts`
  2. READ ONLY 트랜잭션과 항상 ROLLBACK: `JdbcReadOnlyQueryExecutor.java:48-55,77-86`
  3. 상한: `DbMcpLimits` 30초·200행
- **DB MCP의 구조**
  - 디자인·구현은 API가 호스팅하는 MCP(`POST /mcp/db/{id}`, `DbMcpController.java:49-78`)를 쓴다. 인증은 작업 토큰이다(`DbMcpTokenService`, TTL 기본 2h, `com.task_db_mcp_token.stage`).
  - 도구는 `query`·`list_tables`·`describe_table`(`DbMcpTools.java:39-52`)이다.
  - 인터뷰는 인터뷰 서비스 내장 SDK MCP(`dbMcp.ts`)를 쓴다.
  - 워커 쪽 프롬프트는 "쓰기는 서버가 거부"라고 고정 안내한다(`DbMcpWorkerSupport.java:29-43`).
- **연결과 작업**
  - `com.db_connection`(V24)에는 계정 칸이 하나뿐이고 쓰기 허용 표시가 없다. 질문 세션과 같은 연결을 공유한다.
  - 작업은 승인 때 연결을 최대 3개 고르고(`DbConnectionService.MAX_SELECTION`), 고른 연결은 `task.db_connection_ids`(V28)에 저장된다.
- **변경 없음 = 실패.** 구현 뒤 커밋할 파일이 없으면 "nothing to commit"으로 구현실패가 된다(`GitOpsService.java:76`).
- **구현 세션은 권한 확인 없이 돈다.** `--dangerously-skip-permissions`(`WorkerMainLoop.java:398-402`)이다. 그래서 쓰기 정책은 **서버(API)에서 강제**해야 한다.
- 화면에 "읽기 전용"이 고정 문구로 박힌 곳: `ApproveDialog.vue:305`, `tasks/[id].vue:947`, `TaskDetailMobile.vue:244`, `DbConnectionDialog.vue:158`.

## 3. 확정된 결정 (사용자 확인, 2026-11-03)

| 결정 | 내용 |
|---|---|
| 타입 | 신규 개발(아예 없던 기능) · 기능 수정(잘 동작하던 기능을 바꿈) · 버그 픽스(화면 진입 시 오류 등 망가진 상황) · DB 작업(테이블·데이터만 바꿈) |
| 타입의 역할 | 권한을 가르지 않는다. 작업 성격을 표시하고 진행 방식(인터뷰·구현 지시)을 바꾼다 |
| 고르는 사람 | 요청자가 등록할 때 반드시 고른다. 관리자는 승인할 때 바꿀 수 있다 |
| 기존 작업 | "타입 없음"으로 둔다 |
| 표시 | 작업 목록 카드에 타입 배지를 달고, 타입으로 걸러 볼 수 있게 한다 |
| 커밋·PR 제목 앞머리 | 신규 개발·기능 수정 = `feat`, 버그 픽스 = `fix`, DB 작업 = `db` |
| 타입별 인터뷰 | 버그 픽스는 어느 화면에서, 무엇을 했을 때, 어떤 오류가 나는지부터 묻는다. 계획은 재현 → 원인 → 수정 → 같은 오류가 다시 안 나는지 확인 순서다. 기능 수정은 지금 동작, 바꿀 것, 바꾸면 안 되는 것부터 묻는다. DB 작업은 어떤 테이블·데이터를 어떻게 바꿀지 묻는다. 신규 개발은 지금처럼 진행한다 |
| DB 쓰기 단계 | **구현 단계에서만** 연다. 인터뷰·디자인·질문 세션은 지금처럼 읽기 전용이다 |
| DB 쓰기 타입 | 네 타입 모두 |
| 대상 | 개발 DB. 다른 개발자와 다른 작업도 함께 쓴다 |
| 연결 단위 허가 | 관리자가 "쓰기 허용"으로 표시한 연결에만 쓴다. 표시가 없는 연결은 앞으로도 읽기만 한다 |
| 바로 실행 | 새 테이블, 컬럼 추가, 가데이터 넣기, 조건을 단 수정·삭제 |
| 옛 컬럼 처리 | 컬럼 삭제·이름 변경·타입 변경이 필요하면 세션은 옛 컬럼을 그대로 두고 새 컬럼을 추가해 그쪽을 쓴다. 옛 컬럼을 지우는 SQL은 "병합 뒤 실행"으로 남긴다. 작업 상세 화면에 **승인 대기**로 올라가고, 관리자가 실행 버튼을 누르면 반영된다 |
| 예외 | **그 작업이 직접 만든 테이블**은 다른 사람이 쓰지 않으므로 컬럼 삭제·변경을 포함해 바로 실행한다 |
| 막음 | 기존 테이블 삭제(DROP)와 비우기(TRUNCATE), 조건 없이 전체 행을 수정·삭제하는 것 |
| 기록 | 실행한 SQL을 모두 작업별로 기록하고 작업 상세에서 보여 준다. 같은 내용을 PR에 스크립트 파일로 남긴다 |
| DB 작업의 완료 | 코드 변경이 없어도 정상 완료다(구현실패가 아니다) |
| DB 작업의 종료 | 배포 없이 종료할 수 있다(PR생성에서 바로 종료 요청, §6.9) |
| 자동 마이그레이션 레포 | 앱이 뜰 때 테이블 스크립트를 자동 실행하는 레포(Flyway 등)는 다르게 다룬다(§6.8) |
| 레거시 | 레거시 기능은 거의 바뀌지 않는다. 충돌은 새로 추가되는 기능에서 생긴다 |

## 4. 작업 타입

### 4.1 값

| enum (`TaskType`) | 화면 이름 | 커밋·PR 앞머리 |
|---|---|---|
| `NEW_FEATURE` | 신규 개발 | `feat` |
| `FEATURE_CHANGE` | 기능 수정 | `feat` |
| `BUG_FIX` | 버그 픽스 | `fix` |
| `DB_WORK` | DB 작업 | `db` |
| (null) | 타입 없음 | 지금과 같음 — 커밋만 `feat`, PR 제목은 앞머리 없음 |

- PR 제목에 앞머리를 붙이는 것은 새 동작이다. 타입이 있으면 `<앞머리>: 제목`, GitLab은 `Draft: <앞머리>: 제목`이다. (Claude 판단 — 형식)
- PR #122 수정 회차의 커밋은 타입과 무관하게 #122대로 `fix: 수정 N회차 — 제목`을 유지한다. 회차는 PR을 새로 만들지 않으므로 PR 제목에는 영향이 없다. (Claude 판단)

### 4.2 저장·입력

- `com.task.task_type VARCHAR(20) NULL` + `CHECK (task_type IN ('NEW_FEATURE','FEATURE_CHANGE','BUG_FIX','DB_WORK'))`. 기존 행은 NULL로 둔다.
  - 선례는 V29 `implementation_mode`다. 다만 이번에는 "타입 없음"을 표현해야 해서 NULL을 허용한다.
- **등록**
  - `TaskCreateRequest.taskType`(선택 필드)을 추가한다. multipart 등록(`TaskController.java:97-107`)에도 같은 필드를 넣는다.
  - API는 값이 없으면 NULL로 저장한다. 옛 화면이나 다른 호출자를 막지 않기 위해서다. (Claude 판단)
  - **화면에서는 필수**다. 미리 골라 두는 값은 없다. 질문에서 넘어온 등록(fromQuestion)과 PR #122의 후속 등록(fromTask)도 요청자가 직접 고른다. (Claude 판단)
- **승인**
  - `ApproveRequest.taskType`(선택)을 추가한다. 값이 있으면 바꾸고 이력에 "타입 변경: A → B"를 남긴다.
  - 승인 다이얼로그는 현재 값을 미리 고른 select로 보여 준다.
  - 승인 뒤에는 바꿀 수 없다. (Claude 판단)
- **응답**: `TaskResponse.taskType`을 추가한다. 화면 라벨은 프론트가 매핑한다.

### 4.3 표시·필터

- 데스크톱 카드(`tasks/index.vue` 카드 영역), 모바일 카드(`TaskCardCompact.vue`), 상세 두 곳(`tasks/[id].vue`·`TaskDetailMobile.vue`)에 타입 칩을 단다.
- 목록 필터
  - 상태 select 옆에 타입 select("전체 타입" + 네 타입 + "타입 없음")를 둔다.
  - `GET /api/tasks`에 `type` 파라미터를 추가하고, `TaskRepository`의 목록 쿼리 두 개(`findByRequester`·`findAllActive`)에 조건을 넣는다.
  - 모바일 목록(`TaskListMobile.vue`)에도 같은 필터를 둔다.

## 5. 타입별 진행

### 5.1 인터뷰 (인터뷰 서비스)

- `InterviewClaimResponse`에 `taskType`(optional)을 추가하고, 인터뷰 서비스 `types.ts`에도 optional 필드로 둔다. 값이 없으면 지금 문구를 쓴다.
- kickoff 프롬프트(`interviewRunner.ts:157-172`)의 첫 문장과 인터뷰 지시를 타입별로 바꾼다. superpowers brainstorming → writing-plans 흐름과 정규 계획 형식(`planHarvest.ts`의 헤딩 규칙)은 그대로 둔다.
  - **신규 개발 / 타입 없음**: 지금과 같다.
  - **기능 수정**: 지금 어떻게 동작하는지(코드를 읽어 확인), 무엇을 어떻게 바꿀지, 바꾸면 안 되는 것부터 묻는다.
  - **버그 픽스**: 어느 화면에서, 무엇을 했을 때, 어떤 오류가 뜨는지부터 묻는다. 첨부(캡처·로그)가 있으면 먼저 본다. 읽기 도구와 DB 읽기로 원인을 좁혀 본다. 계획은 "재현 → 원인 → 수정 → 같은 오류가 다시 안 나는지 확인(가능하면 회귀 테스트)" 순서로 쓴다.
  - **DB 작업**: 어떤 테이블·데이터를 어떻게 바꿀지 묻는다. 계획에는 실행할 SQL을 순서대로 적고, 각 SQL이 §6.2의 어느 분류(바로 실행 / 승인 대기)에 해당하는지 표시한다.
- 구현 때 쓰기 가능한 연결이 있을 때만 "DB 변경이 필요하면 구현 단계에서 개발 DB에 직접 적용된다(§6.2 규칙)"고 알려, 계획에 DB 변경 단계를 넣게 한다. 쓰기 가능 여부는 claim의 `writableDbConnections`(optional, 연결 이름 목록)로 받는다. 이 값은 §6.1 조건 1·3·4를 claim 시점에 계산한 것이다. 목록이 비면 지금처럼 읽기 전용을 전제로 둔다. (Claude 판단)
- 인터뷰 단계의 DB는 지금처럼 읽기 전용이다. 인터뷰 서비스의 DB 코드(`dbMcp.ts`·`db/adapters.ts`·`sqlReadOnly.ts`)는 바꾸지 않는다.

### 5.2 구현 (워커)

- `WorkerTaskResponse`에 `taskType`을 추가한다.
- 구현 템플릿(`application-worker.yml:126-151`)에 `{task_type_section}` 자리를 두고, 타입별 지시를 넣는다.
  - **버그 픽스**: 먼저 재현하고 원인을 확인한 뒤 고친다. 같은 오류가 다시 나지 않음을 확인한다(가능하면 테스트).
  - **DB 작업**: 계획의 SQL을 `execute` 도구로 적용하고 결과를 확인한다. 코드 변경은 필요할 때만 한다.
  - **신규 개발 / 기능 수정 / 타입 없음**: 추가 지시 없음.
- DB 쓰기 안내(§6.7)는 타입과 무관하게, 쓰기 가능한 연결이 하나라도 있으면 들어간다.
- 커밋 메시지와 PR 제목 앞머리를 §4.1 표대로 바꾼다(`WorkerMainLoop.java:437,453-455`).
- **브랜치 이름은 바꾸지 않는다** (`netismaker/task-…` 유지). 바꾸면 브랜치 정리(`WorktreeService.java:493-501`, prefix 기준)와 늦은 보고 구분이 흔들린다. (Claude 판단)
- 구현 방식(직접·리뷰·SDD)과 무관하게 같은 규칙을 쓴다.
- **이전 시도가 있는 경우**: 구현 재시도(새 `-rN` 브랜치), 재기동 재큐, PR #122 수정 회차가 해당한다.
  - 워커가 claude를 돌리기 전에 `GET /worker/tasks/{id}/db-changes`로 이전 시도·회차의 쓰기 기록을 받는다. 그리고 "이미 적용된 SQL" 절을 넣는다. 지시는 "공용 개발 DB에 이미 반영돼 있으니 다시 실행하지 말고, `describe_table`·`query`로 지금 상태를 확인한 뒤 남은 것만 적용하라"이다.
  - 조회가 실패하면 쓰기 연결이 있는 작업은 claude를 돌리지 않고 구현실패(사유 `DB 변경 기록 조회 실패`, 재시도 가능)로 끝낸다. 같은 SQL이 두 번 실행되는 것을 막기 위해서다. (Claude 판단)
  - 수정 회차의 프롬프트는 #122가 코드(`FixRoundPrompts`)로 만든다. 그래서 타입 절은 넣지 않고, DB 절(§6.7)과 "이미 적용된 SQL" 절만 넣는다. (Claude 판단)

## 6. 구현 단계 개발 DB 쓰기

### 6.1 쓰기가 열리는 조건 (모두 참일 때만)

1. 전역 스위치 `DB_WRITE_ENABLED=true`(public.env, 기본 false). 배포한 뒤 켠다. (Claude 판단)
2. 토큰 단계가 **구현**이다. `task_db_mcp_token.stage`가 IMPLEMENTING인 토큰이다. 디자인 토큰은 읽기만 한다.
3. 그 작업이 승인 때 고른 연결(`task.db_connection_ids`)이다.
4. 연결에 **쓰기 허용**이 켜져 있다.
   - `db_connection.allow_write`이고, **레포 공용(REPO) 연결에만** 둔다. 관리자만 관리 화면(`DbConnectionAdminDialog.vue`)에서 켜고 끌 수 있다.
   - 개인(USER) 연결은 늘 false다. 관리자가 남의 개인 연결을 볼 수 없고(`DbConnectionService.java:206-213`), 소유자가 접속 대상을 언제든 바꿀 수 있기 때문이다. 일반 등록·수정 바디로는 이 값을 바꿀 수 없다. 쓰기를 판정할 때 `scope=REPO`를 다시 확인한다. (Claude 판단)
   - 쓰기 허용 연결의 접속 대상(db_type·host·port·database_name·username)을 바꾸면, 같은 트랜잭션에서 `allow_write`를 끄고 그 연결의 소유 기록(§6.3)을 지운다. 화면에는 "접속 대상이 바뀌어 쓰기 허용을 껐습니다"를 띄운다. 다른 DB(운영 DB 포함)로 쓰기가 새는 것과, 옛 소유 기록이 새 DB의 같은 이름 테이블에 적용되는 것을 막는다. (Claude 판단)
   - 연결 계정에 실제 쓰기 권한이 있어야 한다. 읽기 전용 계정이면 모든 `execute`가 `FAILED`가 된다(§9 5단계에서 확인).
5. 작업의 대상 레포가 자동 마이그레이션 레포가 아니다(§6.8).
6. 작업을 claim한 워커가 쓰기 지원을 알렸다(`supports=dbWrite`, 기존 supports 가드 `WorkerController.java:103-110`와 같은 방식). 옛 워커는 실행한 SQL을 PR 스크립트로 남기지 못한다(§6.6). 그래서 옛 워커에는 쓰기 도구를 주지 않는다. (Claude 판단)

조건을 만족하면 그 연결의 MCP 도구 목록에 `execute`가 추가된다. 도구 이름은 `mcp__db-<id>__execute`이며 기존 `--allowedTools mcp__db-<id>` 와일드카드(`WorkerMcpSupport.java:178-184`)가 그대로 덮는다. 조건이 하나라도 거짓이면 도구 목록에 없다. 직접 호출해도 서버가 거부한다.

읽기 경로(`query`·`list_tables`·`describe_table`, 인터뷰, 질문, 디자인)는 `allow_write`와 상관없이 지금처럼 READ ONLY + ROLLBACK을 유지한다.

### 6.2 SQL 분류 규칙

`execute`는 **한 번에 한 문장**만 받는다. 서버가 문장을 분석해 아래 셋 중 하나로 분류한다. 분석할 수 없거나 분류가 애매하면 **거부**한다(fail closed).

"그 작업의 테이블" = §6.3 소유 기록에 이 작업 id로 남은 테이블. 그 밖의 모든 테이블은 "남의 테이블"이다(레거시 + 다른 작업이 만든 것).

| 분류 | 문장 |
|---|---|
| **바로 실행** | `CREATE TABLE`(새 테이블, 소유 기록 남김) · `CREATE INDEX` · `CREATE SEQUENCE` · `CREATE VIEW`(새 뷰) · `COMMENT ON` · 남의 테이블에 `ALTER TABLE … ADD COLUMN`(NULL 허용이거나 DEFAULT가 있을 때만) · `INSERT` · `UPDATE`/`DELETE`(WHERE 있음, 영향 행 상한 이내) · **그 작업의 테이블에는 위 규칙과 무관하게 DROP·TRUNCATE·ALTER 전부와 조건 없는 UPDATE/DELETE** |
| **승인 대기** (실행하지 않고 기록, 관리자가 나중에 실행) | 남의 테이블에 `ALTER TABLE … DROP COLUMN` · 컬럼 이름 변경(`RENAME COLUMN`) · 컬럼 타입·NULL 여부 변경(`ALTER COLUMN … TYPE`/`MODIFY`/`SET NOT NULL`) · NOT NULL이면서 DEFAULT 없는 `ADD COLUMN` · 제약 추가(`ADD CONSTRAINT` UNIQUE·FK·CHECK, `ADD COLUMN`에 인라인으로 붙은 UNIQUE·REFERENCES·CHECK 포함) · 남의 테이블에 `CREATE UNIQUE INDEX` · 남의 테이블에 대한 `CREATE TRIGGER` · `CREATE`/`REPLACE` PROCEDURE·FUNCTION · 남의 뷰 `CREATE OR REPLACE VIEW` |
| **거부** | 남의 테이블에 `DROP TABLE`·`TRUNCATE`·테이블 이름 변경 · 남의 테이블에 WHERE 없는 `UPDATE`/`DELETE` · 영향 행 상한 초과 · `GRANT`/`REVOKE` · 사용자·역할(`CREATE/ALTER/DROP USER/ROLE`) · `DROP DATABASE/SCHEMA` · `ALTER SYSTEM`·세션·권한 변경 · 여러 문장 · 분석 불가 |

- 영향 행 상한은 남의 테이블에만 건다. UPDATE/DELETE는 한 문장 1000행, INSERT(INSERT…SELECT 포함)는 한 문장 10000행이고, 둘 다 설정값이다. 그 작업의 테이블에는 상한이 없다. DML은 트랜잭션 안에서 실행하고, 영향 행 수가 상한을 넘으면 ROLLBACK한 뒤 거부한다. (Claude 판단)
- "NOT NULL + DEFAULT 없는 ADD COLUMN", "제약 추가", "유니크 인덱스"가 승인 대기인 이유가 있다. 다른 사람 코드의 INSERT가 그 컬럼이나 제약 때문에 깨지기 때문이다. 사용자가 짚은 "다른 사람 화면을 깨뜨리지 않기" 기준을 Claude가 넓힌 것이다. (Claude 판단)
- 거부·승인 대기 응답에는 이유와 대안을 담아 세션이 다음 행동을 고를 수 있게 한다. 예: "남의 테이블 컬럼 삭제는 승인 대기로 기록됨 — 옛 컬럼은 두고 새 컬럼을 쓰세요".
- 방언: PostgreSQL · MySQL · MariaDB · Oracle(`db_connection.db_type`). 분석기는 빌더가 고른다(§10).

### 6.3 소유 테이블 기록

- 새 테이블 `com.task_db_object`(connection_id, schema_name, object_name, object_type, task_id, created_at, UNIQUE(connection_id, schema_name, object_name, object_type))를 둔다.
- `CREATE TABLE`/`CREATE VIEW`/`CREATE SEQUENCE`로 **새로 생긴 경우에만** 이 작업 id로 기록한다.
  - 실행 전에 같은 연결의 카탈로그에서 대상이 없는지 확인한다.
  - `IF NOT EXISTS` 문장이 이미 있는 대상 때문에 아무 일도 하지 않았으면 기록하지 않고, 결과에 "이미 있음"을 돌려준다. 이렇게 하지 않으면 레거시 테이블에 `CREATE TABLE IF NOT EXISTS`만 보내도 소유가 생겨 DROP이 열린다.
  - 확인이 애매하면 거부한다. (Claude 판단)
- **의존 작업이 있으면 소유 예외를 끈다.** 다른 열린 작업이 이 작업의 head 브랜치를 기준 브랜치로 쓰고 있으면(`TaskRepository.findActiveByBaseBranch`, 작업 25 ← 19 사례), 이 작업의 테이블에도 남의 테이블 규칙을 적용한다. 그 작업 코드가 이 테이블을 쓰기 때문이다. 승인 대기·거부 응답에는 그 이유를 담는다. (Claude 판단)
- 소유 판단은 이 기록만 본다. Symphony 이전에 사람이 만든 테이블은 모두 남의 테이블이다.
- 그 작업이 테이블을 DROP하면 기록을 지운다.
- 작업이 종료·취소되어도 기록은 남는다. 그래서 같은 작업의 재시도나 수정 요청은 계속 소유자이고, 다른 작업에는 남의 테이블이다. (Claude 판단)
- 이름 대소문자·따옴표·스키마 생략 시 기본 스키마 결정은 방언 규칙대로 정규화해서 비교한다.

### 6.4 실행

- 호출마다 연결을 새로 열고(지금 읽기 실행기와 같은 구조), `setReadOnly(false)`로 실행한다.
  - 성공하면 COMMIT하고, 실패하거나 상한을 넘으면 ROLLBACK한 뒤 닫는다.
  - Oracle은 autocommit이 꺼진 연결을 close할 때 커밋한다(`JdbcReadOnlyQueryExecutor.java:81` 주석). 그래서 롤백을 close보다 반드시 먼저 한다.
- DDL은 MySQL·MariaDB·Oracle에서 암묵 COMMIT된다. 되돌릴 수 없다는 전제로 분류 규칙이 막는다.
- 문장 제한시간 30초는 기존 상한(`DbMcpLimits`)과 같다.
- 결과: 영향 행 수(DML), 성공 여부, 분류, 기록 id를 돌려준다.
- 쓰기 토큰 TTL: 구현 단계 토큰은 TTL을 그 작업 구현 상한(방식별, SDD 240분) + 여유로 발급한다. 지금 기본 2h는 SDD보다 짧아 중간에 401이 날 수 있다. (Claude 판단)

### 6.5 기록과 화면

- 새 테이블 `com.task_db_write_log`의 칸:
  - id, task_id, connection_id, attempt(구현 시도 번호)
  - sql_text(전문)
  - classification(`EXECUTED`·`DEFERRED`·`DISMISSED`·`REJECTED`·`FAILED`), reason, affected_rows, error
  - created_at, executed_at, executed_by(세션이면 NULL, 관리자 실행이면 user_id)
- 거부된 문장도 남긴다. 세션이 무엇을 시도했는지 보기 위해서다.
- **작업 상세**(데스크톱·모바일)에 "DB 변경" 절을 둔다.
  - 연결별·시간순으로 실행됨 / 승인 대기 / 거부됨 / 실패 목록을 보여 준다.
  - 승인 대기 항목에는 관리자에게만 **실행** 버튼이 보인다.
    - 확인창은 `confirmDialog($q)`를 쓴다. `window.confirm`은 Claude 브라우저 창에서 동작하지 않는다.
    - 실행 전 "PR 병합 뒤 실행을 권장합니다 — 같은 DB를 쓰는 다른 화면이 아직 옛 컬럼을 쓸 수 있습니다"를 보여 준다.
    - 병합 여부로 막지는 않는다. (Claude 판단)
  - 관리자 실행은 API가 같은 연결로 한다. 결과는 기록 행을 `EXECUTED`로 바꾸고 이력에 남긴다. 이 경로는 분류 규칙 중 "승인 대기 → 실행"만 허용한다. 거부 분류는 관리자도 실행할 수 없다.
  - 승인 대기 항목에는 관리자에게 **실행 안 함** 버튼도 있다. 누르면 `DISMISSED`가 되고 이력에 남으며, 스크립트 파일(§6.6)의 "병합 뒤 실행" 절에서 빠진다. 재시도나 회차가 같은 문장을 다시 남겼을 때, 또는 취소된 작업의 것을 정리하는 데 쓴다. (Claude 판단)
  - 승인 대기가 남아 있는 작업을 관리자가 종료 승인할 때, 확인창에 "승인 대기 SQL N건 — 병합 뒤 실행 필요" 한 줄을 띄운다. 목록 배지나 필터는 두지 않는다. (Claude 판단)
- **승인 다이얼로그**: 지금처럼 레포 공용 연결을 미리 고르는 동작(`ApproveDialog.vue:127-131`)은 그대로 둔다. 연결 칩에 "쓰기" 표시를 달고, 고정 캡션 "읽기 전용으로 조회합니다"(`:305`)를 선택 상태에 맞게 바꾼다. 작업 단위로 쓰기를 끄는 칸은 두지 않는다. (Claude 판단)
- **쓰기를 못 쓰는 DB 작업**: 타입이 DB 작업인데 쓰기 허용 연결을 고르지 않았으면, 승인 다이얼로그에 "구현 때 개발 DB에 바로 적용할 수 없습니다 — SQL은 스크립트 파일로만 남습니다"를 경고로 띄운다. 승인은 막지 않는다. (Claude 판단)
- PR 본문에 "DB 변경" 절을 넣는다. 실행한 SQL 수, 승인 대기 SQL 목록, 스크립트 파일 경로를 적는다.
- 질문 세션의 DB 연결 화면 안내 문구(`DbConnectionDialog.vue:158`)를 고친다. "쓰기 허용을 켠 연결은 구현 단계에서 쓰기가 가능하다"는 내용이다. 나머지 "읽기 전용" 고정 문구도 쓰기 허용 연결이면 상황에 맞게 바꾼다.

### 6.6 PR 스크립트 파일

- **워커가** 구현 세션이 끝난 뒤, 커밋 직전에 API에서 그 작업의 쓰기 기록을 받아 SQL 파일을 만든다. 세션에게 맡기지 않는다. 기록과 파일이 어긋나지 않게 하기 위해서다. (Claude 판단)
  - 경로: `db/symphony/task-<id>.sql`. 레포 카탈로그에 `db_script_dir` 칸을 두어 레포별로 바꿀 수 있다. 칸이 비어 있으면 이 기본 경로를 쓴다. (Claude 판단)
  - 내용은 두 부분이다.
    1. 실행된 문장 전체(시간순, 시도 번호·연결 이름 주석).
    2. "-- 병합 뒤 실행(승인 대기)" 절의 승인 대기 문장들.
  - 이 파일은 운영 반영 때 사람이 읽고 쓰는 기록이다. 마이그레이션 도구(Flyway 등)의 자동 실행 폴더가 아니다.
- **"변경 없음 = 실패" 해소**: SQL이 하나라도 실행됐거나 승인 대기로 남았으면 이 파일이 생기므로 커밋할 것이 있다. 그래서 DB만 바꾼 작업도 PR까지 정상 진행된다.
  - SQL도 파일 변경도 없으면 지금처럼 구현실패다. 아무것도 안 한 구현은 실패가 맞다.
- 재시도(새 브랜치)와 수정 요청(같은 브랜치)이 있을 때는 파일을 **누적 기록 전체**로 다시 만든다.
- **실패 경로에서도 만든다.** claude 시간 초과나 비정상 종료로 남은 변경을 `wip:` 커밋으로 push하는 경로(`WorkerMainLoop.java:403-419` → `GitOpsService.preservePartialWork`)가 해당한다.
  - 특히 PR #122 수정 회차에서는 wip이 PR 브랜치에 그대로 올라가고, "수정 중단" 뒤 종료 때 그대로 병합된다.
  - 그래서 이 경로에서도 커밋 전에 같은 파일 생성 로직을 돌린다.
  - 기록 조회가 실패하면 wip은 그대로 올리고, 실패 사유 끝에 "SQL 스크립트 파일 미반영"을 붙인다. (Claude 판단)
- **성공 판정**: 재시도 새 브랜치는 base에서 시작한다. 그래서 이전 시도가 SQL을 실행했으면 이번 시도가 아무것도 안 해도 누적 파일 때문에 커밋할 것이 생긴다.
  - DB 작업은 누적 파일만으로도 성공으로 본다. 1회차에 SQL을 다 적용하고 PR 생성에서 실패한 경우, 재시도가 할 일은 파일뿐이기 때문이다.
  - 다른 타입은 이번 시도의 코드 변경이나 이번 시도(attempt) 번호의 쓰기 기록이 있어야 성공이다. 없으면 지금처럼 "변경 없음"으로 구현실패다. (Claude 판단)
- **쓰기를 못 쓸 때**: 쓰기 도구가 없는 구현이 해당한다(연결 미선택, 쓰기 허용 없음, 스위치 꺼짐, 옛 워커).
  - 이때 DB 작업 지시는 "SQL을 실행하지 말고 같은 경로 파일(`db/symphony/task-<id>.sql`)에 직접 적어라"로 바뀐다. PR 본문에는 "개발 DB 미적용 — 수동 적용 필요"를 적는다.
  - 그 작업의 쓰기 기록이 하나도 없으면 워커는 세션이 쓴 파일을 덮어쓰지 않는다. (Claude 판단)

### 6.7 구현 프롬프트의 DB 절

`DbMcpWorkerSupport`의 고정 문구("쓰기는 서버가 거부")를 연결별로 나눈다.

- **쓰기 가능한 연결**: 다음을 안내한다.
  - "함께 쓰는 개발 DB다"
  - "필요한 테이블·컬럼·가데이터는 `execute`로 직접 적용하라"
  - "남의 테이블 컬럼을 지우거나 바꿔야 하면 옛 컬럼을 두고 새 컬럼을 추가해 그쪽을 쓰고, 옛 컬럼 삭제는 `execute`로 보내 승인 대기로 남겨라"
  - "가데이터는 알아볼 수 있게 넣어라(예: 이름에 `[테스트]`)"
  - "적용 뒤 `query`로 결과를 확인하라"
  - "레포에 마이그레이션 파일을 만들지 마라. 이미 개발 DB에 적용됐고, 운영용 스크립트는 워커가 따로 만든다(§6.6). 레포에 앱 기동 때 스크립트를 자동 실행하는 장치(Flyway·Liquibase·`schema.sql` 자동 실행 등)가 보이면 DDL은 `execute`로 실행하지 말고, PR 본문에 '자동 마이그레이션 레포로 보임 — 카탈로그 설정 확인 필요'를 남겨라" (Claude 판단)
- **읽기만 되는 연결**: 지금 문구 그대로 둔다.

### 6.8 자동 마이그레이션 레포

앱이 뜰 때 레포의 테이블 스크립트를 DB에 자동 실행하는 레포가 대상이다(Flyway·Liquibase 등). 이런 레포에서 구현 세션이 개발 DB에 직접 적용하고 레포 관례대로 마이그레이션 파일까지 만들면, 시험 컨테이너가 뜰 때 같은 스크립트를 다시 돌려 기동이 실패하거나 이중으로 적용된다. 그래서 다르게 다룬다.

- 레포 카탈로그에 **자동 마이그레이션** 표시(`repo_catalog.db_migration_managed`, 기본 false)를 둔다. 관리자가 관리 화면(`/admin/repo-catalog`)에서 켠다. (Claude 판단)
- 켜진 레포의 작업
  - 구현 단계에 `execute` 도구를 주지 않는다. 쓰기 허용 연결을 골랐어도 마찬가지다(§6.1에 조건 추가). 인터뷰의 `writableDbConnections`도 비운다.
  - 구현 지시는 "DB 변경은 레포의 마이그레이션 관례대로 파일로 만들어라. 가데이터도 관례가 허용하면 그 방식으로 넣어라. 개발 DB에는 시험 컨테이너가 뜰 때 적용된다"로 바뀐다.
  - §6.6의 워커 스크립트 파일과 "DB 변경" 기록은 생기지 않는다. 레포의 마이그레이션 파일이 그 역할을 한다.
  - 다른 사람 화면을 깨뜨리지 않는 기준(옛 컬럼을 두고 새 컬럼 추가)은 같은 지시로 준다. 다만 서버가 강제할 수는 없다. 마이그레이션 파일은 PR 검토로 지킨다.
- 표시가 꺼진 레포에서 세션이 자동 마이그레이션 장치를 발견하면 §6.7대로 DDL 실행을 멈추고 PR 본문에 알린다. 관리자는 표시를 켜고 그 작업을 다시 돌릴지 정한다.

### 6.9 배포 없이 종료 (DB 작업)

지금 종료 요청은 배포완료·배포중단됨에서만 받는다(`TaskCloseService.REQUESTABLE`, `:38`). 앱 코드가 그대로인 DB 작업도 앱을 다시 빌드해서 띄워야 종료할 수 있는 셈인데, 이를 연다.

- 타입이 **DB 작업**인 작업은 **PR생성**에서도 종료를 요청할 수 있다. 등록자 "확인 완료"로 요청한 뒤 종료승인대기 → 관리자 승인 → 종료로 이어지는 흐름은 같다.
  - 다른 타입은 지금처럼 배포를 거친다. (Claude 판단 — 범위)
  - DB 작업도 PR생성에서 배포를 고를 수 있는 길은 그대로 둔다.
- 철회·반려하면 직전 상태(PR생성)로 돌아간다. 지금 `statusBeforeReview`가 이력에서 직전 상태를 찾는 방식을 그대로 쓰되, 후보에 PR생성을 더한다.
- **종료 단계**
  - 병합은 그대로 한다.
  - **배포 중지**: 띄운 컨테이너가 없으면 실행하지 않고 끝난 것으로 기록한다(`CloseStep.UNDEPLOYED`).
  - **산출물 업로드**: 배포 이미지가 없으므로, 배포한 적 없는 DB 작업은 산출물 모드를 꺼진 것(null)으로 둔다. 배포까지 한 DB 작업은 다른 작업과 같다. 종료 승인 때 `close_artifact_mode`를 정하는 곳에서 가른다. (구현 계획 검토 반영, 2026-11-04)
  - 노트와 브랜치 정리는 그대로다.
- **화면**
  - PR생성 상태의 DB 작업에는 다음 할 일로 "확인 완료(종료 요청)"가 보인다. 등록자와 관리자에게 보이고, "배포"(관리자)도 함께 남는다.
  - 데스크톱 상세 버튼, 모바일 시트, 스테퍼, `taskStages.ts`의 `nextAction`·`attentionGroup`에 반영한다. 스테퍼는 배포 단계를 "건너뜀"으로 그린다.
- PR #122의 수정 요청은 지금처럼 배포 상태에서만 열린다. PR생성에 머문 DB 작업을 고치려면 배포하거나 새 작업을 등록한다. (Claude 판단 — §11 비목표)

## 7. 데이터 모델 (V39)

```sql
ALTER TABLE com.task ADD COLUMN task_type VARCHAR(20) NULL
  CHECK (task_type IN ('NEW_FEATURE','FEATURE_CHANGE','BUG_FIX','DB_WORK'));
ALTER TABLE com.db_connection ADD COLUMN allow_write BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE com.repo_catalog ADD COLUMN db_script_dir VARCHAR(255) NULL;
ALTER TABLE com.repo_catalog ADD COLUMN db_migration_managed BOOLEAN NOT NULL DEFAULT false;
-- com.task_db_object, com.task_db_write_log (§6.3, §6.5) — 인덱스: write_log(task_id, created_at), db_object(connection_id)
```

정확한 칸 이름·인덱스·FK(ON DELETE)는 빌더가 정한다. 테이블 이름 `repo_catalog`는 실제 이름을 확인해서 쓴다.

## 8. 바뀌는 곳 (요약)

- **API**
  - `TaskType` enum, `Task.taskType`, `TaskCreateRequest`/`ApproveRequest`/`TaskResponse`/`WorkerTaskResponse`/`InterviewClaimResponse` 필드
  - 등록·승인 검증과 이력, 목록 필터
  - `DbConnection.allowWrite`와 관리자 API·화면(`DbConnectionAdminDialog.vue`)
  - `DbMcpTools`에 `execute`, 쓰기 분류기·실행기, 소유·기록 테이블과 리포지토리
  - 작업 상세용 `GET /api/tasks/{id}/db-changes`와 관리자 `POST …/db-changes/{logId}/execute`·`…/dismiss`
  - 워커용 `GET /worker/tasks/{id}/db-changes`
  - 인터뷰 claim `writableDbConnections`
  - 토큰 TTL
  - 레포 카탈로그 `db_script_dir`·`db_migration_managed`(검증·부분 갱신·관리 화면)
  - 종료: DB 작업의 PR생성 종료 요청, 철회·반려 복귀, 컨테이너 없는 배포 중지 단계, 산출물 모드
- **워커**
  - supports=dbWrite
  - 타입별 구현 지시, 커밋·PR 앞머리
  - DB 절 문구
  - 커밋 전 스크립트 파일 생성, PR 본문 절
- **인터뷰 서비스**: claim `taskType`(optional), 타입별 kickoff 문구
- **프론트**
  - 등록 다이얼로그 타입 select(필수)
  - 승인 다이얼로그 타입 select
  - 카드·상세 칩, 목록 필터(데스크톱·모바일)
  - 작업 상세 "DB 변경" 절(데스크톱·모바일), 연결 관리 쓰기 허용 토글, "읽기 전용" 문구 정리
  - 레포 카탈로그 "자동 마이그레이션" 토글과 "DB 스크립트 폴더"
  - DB 작업 PR생성의 "확인 완료(종료 요청)": 상세 버튼, 모바일 시트, 스테퍼, `taskStages.ts`
  - 종료 승인 확인창의 "승인 대기 SQL N건" 경고
  - hydration-smoke 목 갱신

## 9. 배포 순서

1. **API**
   - V39를 적용한다.
   - `DB_WRITE_ENABLED` 없이(=false) 배포한다. 타입과 표시는 바로 동작한다.
   - 옛 워커와 옛 인터뷰 서비스는 새 필드를 무시하고 지금처럼 동작한다.
2. **워커 3대**(이 PC, 10.1.1.75, Mac): 진행 중인 구현이 없을 때 jar를 갱신하고 재기동한다.
3. **인터뷰 서비스**: 타입별 kickoff를 넣는다. 순서는 자유다. 값이 없으면 지금 문구를 쓴다.
4. **프론트**: `.output`을 재빌드한다.
5. **쓰기 켜기**
   - 쓰기 허용을 켤 연결의 계정에 실제 쓰기 권한(CREATE·ALTER·INSERT·UPDATE·DELETE)이 있는지 확인한다. 읽기 전용 계정이면 계정을 바꾼다. 질문·인터뷰 세션도 같은 계정을 쓰지만, 그 경로는 코드 쪽 READ ONLY로 지킨다(§12).
   - 관리자가 개발 DB 연결에 "쓰기 허용"을 켠다. 첫 관찰은 연결 하나만 켜서 한다. 어느 연결로 할지와 시험 작업으로 할지 실제 작업으로 할지는 켤 때 사용자와 정한다.
   - public.env에 `DB_WRITE_ENABLED=true`를 넣고 API만 단건 재기동한다(`stop-public.ps1 -Only api` → 작업 스케줄러).
   - 첫 작업은 관리자가 지켜본다.

## 10. 빌더에게 맡기는 것

- SQL 분석기 선택(예: JSqlParser 같은 Java 라이브러리 vs 자체 토큰 분석)과 방언별 정규화. 조건은 두 가지다. §6.2 표를 시험 케이스로 만들 것, 애매하면 거부할 것.
- 각 테이블의 정확한 칸 이름·인덱스·FK.
- "DB 변경" 절의 화면 배치. 기존 상세 화면의 카드 스타일을 따른다.
- 영향 행 상한을 설정값으로 뺄지 여부.
- 테스트 범위. 분류기는 단위 시험(방언별 표)으로, 실행기는 PostgreSQL 통합 시험(기존 시험 인프라가 있으면)으로 한다.

## 11. 비목표 · 반목표

- **비목표**
  - 인터뷰·디자인·질문 세션의 DB 쓰기.
  - 운영 DB 쓰기.
  - 쓰기 전 자동 백업이나 되돌리기.
  - 작업 종료·취소 시 그 작업이 만든 테이블·가데이터 자동 정리.
  - 브랜치 이름 접두 변경.
  - 타입별 권한 차등.
  - DB 작업 외 타입의 배포 없는 종료.
  - PR생성에 머문 DB 작업의 수정 요청(PR #122는 배포 상태에서만 연다).
  - 자동 마이그레이션 레포의 서버 쪽 SQL 분류(마이그레이션 파일은 PR 검토로 지킨다).
- **반목표**(기술적으로 동작해도 실패인 것)
  - 같은 DB를 쓰는 다른 사람의 화면이 이 기능 때문에 깨지는 것.
  - 쓰기 허용을 켜지 않은 연결에 한 번이라도 쓰기가 일어나는 것.
  - 실행된 SQL이 기록이나 PR 스크립트 파일에서 빠지는 것.
  - 거부 분류를 관리자 실행으로 우회할 수 있는 것.

## 12. 위험

- **되돌릴 수 없는 변경**: 구현 세션은 권한 확인 없이 돌고, 첨부·레포 내용을 통한 프롬프트 인젝션이 쓰기 SQL로 이어질 수 있다. 막는 장치는 서버 쪽 분류(남의 테이블 보호, 거부 목록, 영향 행 상한)와 연결 단위 허가, 전역 스위치다. 그래도 그 작업 소유가 아닌 테이블에 INSERT·조건부 UPDATE/DELETE는 가능하다.
- **계정 공유**: 쓰기 허용 연결의 계정은 쓰기 권한이 있다. 질문·인터뷰 세션도 같은 계정을 쓰므로, 그 경로의 "읽기 전용 계정" 최종 방어선이 사라진다. 코드 쪽 READ ONLY 검사·트랜잭션·ROLLBACK은 유지된다.
- **분석기 오분류**: 방언별 문법(Oracle `MODIFY`, MySQL `CHANGE COLUMN`, 따옴표 식별자, 스키마 생략 등)에서 남의 테이블을 소유 테이블로, 또는 승인 대기를 바로 실행으로 잘못 분류할 수 있다. 방어는 fail closed와 방언별 시험이다.
- **8090 직접 접근**: `/mcp/**`는 공개 터널에 없지만 사내망에서 8090에 닿는다. 토큰 유출의 영향이 읽기에서 쓰기로 커진다.
- **PR #122와 충돌**: 같은 파일을 고친다. #122를 먼저 병합하고 이 브랜치를 그 위에서 시작한다.
