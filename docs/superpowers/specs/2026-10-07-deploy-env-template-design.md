# 배포 환경변수 이름 자동 채움 설계

2026-10-07 · 브레인스토밍 승인 반영본 · 선행: `2026-05-30-deploy-env-injection-design.md`

## 1. 목표

구현이 끝난 작업을 배포할 때, 배포 다이얼로그에 **필요한 환경변수 이름이 미리 채워져** 있어 관리자는
접속정보(값)만 넣고 배포할 수 있게 한다.

현재 다이얼로그(`frontend/pages/tasks/[id].vue`)는 KEY와 value를 모두 손으로 입력한다. 미리 채워지는 것은
그 작업에 이미 저장된 `task.env_vars`뿐이라 **첫 배포는 항상 빈 칸**이다. 선행 설계(§6)는 "레포 단위 env
템플릿"을 범위 밖으로 뒀는데, 이번에 두 출처를 더해 이 공백을 메운다.

- **A. 구현 시 추출** — 구현 세션의 Claude가 "컨테이너 실행에 필요한 환경변수 이름 목록"을 파일로 남기고,
  워커가 커밋 전에 회수해 API에 보고한다. 작업별 **env 템플릿**으로 저장한다.
- **B. 같은 레포의 직전 배포** — 같은 레포에서 가장 최근에 배포 요청된 다른 작업의 env(KEY와 값, 비밀 값 포함)로
  빈 값을 채운다.

## 2. 확정된 결정

| 결정 | 내용 |
|---|---|
| A 추출 방식 | **Claude가 목록 파일 작성** (`.netis-deploy-env.json`). 정적 스캔(yml 자리표시자·`.env.example` 등)은 하지 않는다 — Spring relaxed binding처럼 자리표시자 없이 env로 덮는 경우·설명·비밀 여부를 못 잡는다 |
| 파일 내용 | **이름·설명·비밀·필수만.** 값은 쓰지 않는다 |
| 추출 실패 | 구현을 실패시키지 않는다. 빈 템플릿 + 경고 로그 → B·수동 입력은 그대로 동작 |
| B 비밀 값 | **비밀 값도 그대로 복사**한다(사용자 선택). 평문 저장·관리자 전용 입력이라는 선행 설계 전제를 그대로 따른다 |
| 비관리자 응답 | 관리자가 아닌 사용자가 받는 작업 응답(`TaskResponse.envVars`)에서는 **비밀 값을 빈 문자열로** 비운다. B가 다른 작업의 비밀 값을 옮겨 오므로, 같은 레포의 다른 사용자 작업에 비밀이 노출되는 것을 막는다. 관리자 응답은 지금과 같다 |
| KEY 표시 | 서버가 미리 채운 행의 KEY는 **고정 라벨**(편집 불가)로 보여 준다. 행 삭제·"변수 추가"(KEY 직접 입력)는 그대로 |
| 빈 값 행 | **보내지 않는다.** 빈 문자열이 앱 기본값을 덮어 부팅을 깨는 것을 막는다(동작 변경) |
| 필수 미입력 | 막지 않는다. 경고 문구 + 주황 "그래도 배포" 버튼(DB 접속정보 창과 같은 규칙) |

## 3. 데이터 모델

### 3.1 `EnvTemplateItem` (신규, JSONB 항목)

`EnvVar`와 같은 패키지(`entity`)·같은 패턴의 record.

```java
public record EnvTemplateItem(String key, String description, boolean secret, boolean required) {
    public EnvTemplateItem {
        if (key == null) key = "";
        if (description == null) description = "";
    }

    /** 워커 파싱 결과와 API 수신값 모두에 적용하는 정규화. */
    public static List<EnvTemplateItem> sanitize(List<EnvTemplateItem> raw) { ... }
}
```

`sanitize` 규칙 (워커·API 양쪽에서 같은 함수를 쓴다):

1. `null` 목록 → 빈 목록, `null` 항목 → 버림
2. KEY는 앞뒤 공백 제거 후 `[A-Za-z_][A-Za-z0-9_]{0,127}`에 맞지 않으면 버림
3. 같은 KEY가 또 나오면 처음 것만 남김(대소문자 구분 — 환경변수 의미와 같음)
4. 설명은 앞뒤 공백 제거, 300자 초과분은 잘라 냄
5. 최대 50개(초과분 버림)

### 3.2 `com.task.env_template` 컬럼 (Flyway V25)

```sql
-- V25__task_env_template.sql
ALTER TABLE com.task
    ADD COLUMN env_template JSONB NOT NULL DEFAULT '[]'::jsonb;
```

`Task` 엔티티에 `env_vars`와 같은 매핑(`@JdbcTypeCode(SqlTypes.JSON)` + `@Setter`)으로 `List<EnvTemplateItem> envTemplate`
필드를 추가한다. 기존 작업은 `[]`(템플릿 없음).

## 4. 워커 — 목록 파일 받기

### 4.1 프롬프트 지시

`WorkerMainLoop.fillImplementationPrompt`가 템플릿을 채운 **뒤에 고정 블록을 덧붙인다**. yml 템플릿과 기본 템플릿
(`defaultImplementationPrompt`) 양쪽에 같은 지시가 들어가고, 운영자가 템플릿을 바꿔도 빠지지 않는다.

```
## 배포 환경변수 목록 (마지막 단계 — 필수)
구현을 마친 뒤, 이 프로젝트를 컨테이너로 실행할 때 외부에서 넣어 줘야 하는 환경변수의 **이름**을
현재 디렉터리(레포 루트)의 `.netis-deploy-env.json`에 아래 형식으로 쓰세요.
이 파일은 워커가 읽은 뒤 지우며 커밋되지 않습니다.

{"vars":[{"key":"SPRING_DATASOURCE_URL","description":"DB 접속 JDBC URL","secret":false,"required":true}]}

- 값은 절대 쓰지 마세요(이름·설명만). 레포에 있는 실제 비밀번호·토큰을 옮겨 적지 마세요.
- 대상: DB 접속(URL·계정·암호), 시크릿(JWT 키 등), 외부 API 주소·키처럼 배포 환경마다 달라지는 값.
- 코드에 안전한 기본값이 있어 비워 둬도 실행되면 required=false.
- Spring Boot는 설정 키를 relaxed binding 환경변수 이름으로 쓰세요(spring.datasource.url → SPRING_DATASOURCE_URL).
- 암호·토큰·키는 secret=true.
- description은 한국어 한 줄.
- 필요한 환경변수가 없으면 {"vars":[]}를 쓰세요.
```

### 4.2 회수 (`DeployEnvManifest`, 신규 — `workerdaemon` 패키지)

```java
/** worktree 루트의 .netis-deploy-env.json을 읽고 지운다. 예외를 던지지 않는다. */
static List<EnvTemplateItem> harvest(Path worktreeRoot)
```

- 파일이 없으면 빈 목록(경고 없음 — 구버전 프롬프트·지시 무시 모두 여기로).
- 있으면 읽은 뒤 **파싱 성공 여부와 무관하게 지운다**. 삭제 실패는 경고 로그(파일엔 값이 없으므로 커밋돼도 비밀 유출은 아님).
- 형식: `{"vars":[...]}`. 최상위가 배열이면 그 배열을 목록으로 받는다(관대). 모르는 필드(예: `value`)는 무시한다.
- JSON이 깨졌거나 형식이 다르면 빈 목록 + 경고 로그.
- 결과는 `EnvTemplateItem.sanitize`를 거친다.

### 4.3 호출 위치

`processImplementation`에서 Claude 실행 성공(exit 0) → `.design` 삭제 → **`harvest`** → 커밋·푸시 순서.
커밋(`git add -A`) 전에 지워야 PR에 섞이지 않는다. 회수한 목록은 성공 보고(`PR_CREATED`)에 실어 보낸다.
커밋·PR 실패 경로는 기존대로 `IMPLEMENTATION_FAILED`(템플릿 미보고).

## 5. API — 수신·저장

- `WorkerResultRequest`에 마지막 컴포넌트 `List<EnvTemplateItem> envTemplate`(nullable)를 추가한다. 다른 보고는 `null`.
- `masked()`는 각 항목의 `description`을 `GitRemotes.mask`로 가린다(자유 텍스트 규칙과 동일).
- `WorkerService.recordResult`의 `PR_CREATED` 두 경로(정상 · 지각 보고 정합화)에서
  `req.envTemplate() != null`이면 `t.setEnvTemplate(EnvTemplateItem.sanitize(req.envTemplate()))`.
  `null`(구버전 워커)이면 손대지 않는다.

## 6. API — 미리 채울 값 계산

### 6.1 엔드포인트

`GET /api/tasks/{id}/deploy-env` — `ROLE_ADMIN` 한정(`@PreAuthorize`), 삭제된 작업은 404. 상태는 따지지 않는다(읽기 전용).

```json
{
  "rows": [
    {"key": "SPRING_DATASOURCE_URL", "value": "jdbc:postgresql://…", "secret": false,
     "description": "DB 접속 JDBC URL", "required": true,
     "source": "TEMPLATE", "valueFromPrevious": true}
  ],
  "templateCount": 3,
  "previousTaskId": 41
}
```

- `source` — KEY의 출처: `SAVED`(이 작업에 저장된 env) · `TEMPLATE`(구현 시 추출) · `PREVIOUS`(직전 배포에만 있던 KEY)
- `valueFromPrevious` — 값을 직전 배포에서 가져왔는지
- `templateCount` — 이 작업의 env 템플릿 항목 수
- `previousTaskId` — 값이나 KEY를 하나라도 직전 배포에서 가져왔을 때만 그 작업 id, 아니면 `null`

### 6.2 직전 배포 찾기

"같은 레포에서 가장 최근에 **배포 요청**된, env가 비어 있지 않은 다른 작업". 배포 중지는 `deployed_at`을 지우므로
(`WorkerService.recordDeployResult`) 그 컬럼 대신 상태 이력의 `배포대기` 진입 시각을 쓴다. env는 배포·재배포 요청
때만 저장되므로 "env가 비어 있지 않음" = "배포 요청된 적 있음"이다.

```sql
-- TaskRepository (native)
SELECT t.* FROM com.task t
WHERE t.github_repo = :githubRepo
  AND t.id <> :excludeId
  AND t.deleted_at IS NULL
  AND t.env_vars <> '[]'::jsonb
ORDER BY (SELECT MAX(h.at) FROM com.task_status_history h
          WHERE h.task_id = t.id AND h.to_status = :deployPending) DESC NULLS LAST,
         t.id DESC
LIMIT 20
```

`:deployPending` = `TaskStatus.DEPLOY_PENDING.dbValue()`. 결과를 순서대로 보며 **호스트가 같은**
(`RepoRef.fromSnapshot(githubRepo, gitUrl).host()`) 첫 작업을 고른다 — GitHub `a/b`와 GitLab `a/b`를 섞지 않는다.
이력 조회는 기존 인덱스 `idx_status_history_task(task_id, at DESC)`를 탄다.

### 6.3 합치는 규칙 (`DeployEnvSuggester`, 순수 함수 — `service` 패키지)

입력: `saved`(이 작업의 `env_vars`), `template`(이 작업의 `env_template`), `previous`(직전 배포 작업의 `env_vars`, 없으면 빈 목록).
KEY가 빈 항목은 모든 입력에서 무시한다.

**저장된 env가 있으면** (재배포, 또는 전에 배포 요청한 작업):
1. `saved` 순서대로 행 생성 — 값·비밀은 저장값, `source=SAVED`. 같은 KEY가 템플릿에 있으면 설명·필수를 붙이고 비밀은 OR.
2. 템플릿에만 있는 KEY를 뒤에 덧붙임 — 값 `""`, `source=TEMPLATE`.
3. `previous`는 보지 않는다(직전 배포 조회도 하지 않음). `previousTaskId=null`.

**저장된 env가 없으면** (첫 배포):
1. 템플릿 순서대로 행 생성 — `source=TEMPLATE`, 설명·필수는 템플릿. 같은 KEY가 `previous`에 있으면 값을 가져오고
   (`valueFromPrevious=true`) 비밀은 OR.
2. `previous`에만 있는 KEY를 뒤에 덧붙임 — 값·비밀은 직전 배포, 설명 `""`, 필수 `false`, `source=PREVIOUS`, `valueFromPrevious=true`.
3. 2에서 하나라도 가져왔거나 1에서 값을 하나라도 가져왔으면 `previousTaskId`를 채운다.

직전 배포의 값이 빈 문자열(공백뿐 포함)이면 값을 가져온 것으로 보지 않는다 — 템플릿 행은 빈 값·`valueFromPrevious=false`,
직전 배포에만 있던 KEY는 이름만 덧붙인다(이때도 KEY를 가져왔으므로 `previousTaskId`는 채운다).

템플릿도 직전 배포도 없으면 `rows=[]` — 다이얼로그는 지금처럼 빈 상태에서 "변수 추가"로 시작한다.

## 7. 비관리자 응답의 비밀 값 비우기

- `TaskResponse.redactSecretValues()` — `secret=true`인 `envVars` 항목의 값을 `""`로 바꾼 복사본.
- `TaskController`에서 **`@PreAuthorize` 관리자 전용이 아닌** 엔드포인트(등록 2개·목록·상세·취소·재시도)는 응답 직전에
  `isAdmin`이 아니면 이 복사본을 돌려준다. 관리자 전용 엔드포인트(승인·배포·재배포·중지 등)는 그대로.
- 워커 페이로드(`WorkerTaskResponse.forDeploy`)는 바뀌지 않는다 — 주입에는 실제 값이 필요하다.
- 화면은 이미 비밀 값을 `••••••`로 표시하므로 사용자 화면은 달라지지 않는다.

## 8. 화면

### 8.1 구성

- 배포 다이얼로그를 `components/tasks/DeployEnvDialog.vue`로 분리한다(`[id].vue`가 커서 함께 정리).
  데스크톱·모바일(`TaskDetailMobile`의 배포 행동)이 지금처럼 같은 다이얼로그를 연다.
- 판정은 `composables/deployEnvForm.ts` 순수 함수: 응답 → 행 변환, 저장 env → 행 변환(대체 경로), 전송 형식 변환
  (KEY trim · 빈 KEY/빈 값 제외), 필수 미입력 목록, 상단 안내 문구.
- 제목은 지금과 같다(`배포 — 환경변수` / `재배포 — 환경변수`).

### 8.2 동작

- 열 때 `GET /api/tasks/{id}/deploy-env`를 부른다(불러오는 동안 로딩 표시). 실패하면 경고 알림 후 지금처럼
  `task.envVars`(관리자 응답이라 값이 온전함)로 채운다.
- 서버가 준 행: KEY는 고정 라벨, 아래에 설명(있으면)과 표식("필수", "직전 배포 값"). 값 입력·비밀 토글·삭제 가능.
- "변수 추가"로 만든 행: 지금처럼 KEY·값을 모두 입력.
- 상단 안내: 템플릿이 있으면 "구현 시 추출한 변수 N개", 직전 배포를 썼으면 "task #M 배포에서 값을 가져왔습니다".
  둘 다 없으면 기존 안내 문구(컨테이너에 `-e`로 주입…).
- 전송: 빈 KEY·빈 값 행은 빼고 `{ envVars }`로 `deploy`/`redeploy` 호출(엔드포인트·바디 형식 불변).
- 필수인데 값이 빈 행이 있으면 "필수 변수 N개가 비어 있습니다: A, B" 경고 + 버튼을 주황 "그래도 배포"/"그래도 재배포"로.

## 9. 하위 호환 · 배포 순서

- 구버전 워커 + 신버전 API: `envTemplate` 없음 → 템플릿 빈 상태, B·수동 입력만 동작.
- 신버전 워커 + 구버전 API: 모르는 필드는 Jackson 기본 설정(`FAIL_ON_UNKNOWN_PROPERTIES=false`)으로 무시된다.
  워커 dead-letter에 남은 구버전 보고도 `envTemplate=null`로 읽힌다.
- 권장 순서: **API(마이그레이션 V25 포함) → 워커 → 프론트 재빌드**. 기존 작업은 템플릿이 없으므로 B만 적용된다.
- 이 기능 이전에 PR이 만들어진 작업은 템플릿이 없다(재추출하지 않음).

## 10. 범위 밖

- 정적 스캔(yml 자리표시자·`.env.example` 등)으로 템플릿 보강
- DB 접속정보 묶음 입력(호스트·포트·계정 → `SPRING_DATASOURCE_URL` 조립)
- 비밀 값 암호화(선행 설계와 동일하게 평문 저장)
- 이미 PR이 생성된 작업의 템플릿 재추출
- 레포 카탈로그 단위 env 프리셋 편집 화면

## 11. 테스트

**백엔드 단위**
- `EnvTemplateItemTest` — sanitize: 잘못된 KEY·중복·50개 상한·설명 300자·null 처리
- `DeployEnvManifestTest` — 파일 없음 / 정상(파싱 + 삭제) / 깨진 JSON(빈 목록 + 삭제) / 최상위 배열 / `value` 필드 무시
- `WorkerMainLoopPlanSectionTest`(확장) — yml·기본 템플릿 모두 `.netis-deploy-env.json` 지시 포함
- `WorkerResultRequest` 마스킹 — `envTemplate` 설명의 인증 URL이 가려짐
- `DeployEnvSuggesterTest` — §6.3 두 갈래, 비밀 OR, 빈 KEY 무시, `previousTaskId` 채움 조건
- `WorkerService` — `PR_CREATED` 정상·지각 정합화 경로 저장, `null`이면 유지
- `TaskService` 미리 채움 — 저장 env가 있으면 직전 배포 조회 안 함, 호스트 다른 후보 건너뜀
- `TaskResponse.redactSecretValues` — 비밀만 비움

**통합(Testcontainers, CI)**
- 직전 배포 native 쿼리 — 레포·삭제·빈 env·자기 자신 제외, `배포대기` 이력 최신순
- `GET /deploy-env` 관리자 200 / 일반 사용자 403, 일반 사용자 상세 응답의 비밀 값 비움

**프론트(vitest)**
- `deployEnvForm` 순수 함수
- `DeployEnvDialog` — 고정 라벨·설명·표식, 빈 값 제외 전송, 필수 미입력 시 "그래도 배포", API 실패 시 대체 경로
- 기존 `task-detail-progress`/`task-detail-mobile` 테스트 유지(다이얼로그 제목)
- 실화면: 1280/390px에서 다이얼로그 확인(가로 스크롤 없음)
