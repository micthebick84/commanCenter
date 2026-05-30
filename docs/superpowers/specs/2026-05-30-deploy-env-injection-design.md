# 배포 환경변수 주입 (env/DB injection) 설계

> 작성일: 2026-05-30 · 상태: 승인됨 · 선행: `2026-05-30-docker-deploy-mvp-design.md`

## 1. 목표

Docker 배포 MVP는 컨테이너에 환경변수를 전혀 주입하지 못한다(`DeploySpec.env = Map.of()`).
그 결과 netis7.0처럼 **외부 DB 접속 정보(`SPRING_DATASOURCE_URL` 등)와 시크릿(`JWT_SECRET`)을
env로 요구하는 앱**은 컨테이너에서 부팅 직후 크래시하여 "배포실패"로 끝난다(헬스체크가 잡아냄).

이 기능은 task별로 환경변수 key=value 목록을 UI에서 입력받아 컨테이너 `docker run -e`로 주입한다.
이로써 실제 DB에 붙는 앱을 워커 로컬에 띄울 수 있게 된다.

## 2. 확정된 설계 결정 (사용자 선택)

| 항목 | 결정 |
|---|---|
| **env 입력 방식** | task별 UI에서 `key=value` 직접 입력 (자유 입력) |
| **시크릿 저장** | **평문 저장** + UI 마스킹 표시 (암호화 없음) — 사내 내부 도구 전제 |
| **DB 연결 방식** | **전적으로 env로 처리.** `host.docker.internal` 자동 매핑·자동 DB 주입 없음. 사용자가 URL/계정/암호를 env로 직접 입력 |

> ⚠️ **보안 경계 (의도된 결정):** 시크릿은 DB에 평문 저장되고, UI는 마스킹 *표시만* 한다.
> 값 자체는 브라우저로 평문 전달되며(마스킹은 password 스타일 표시 + 보기 토글),
> 컨테이너에는 `-e KEY=VALUE`로 평문 주입된다. 사내 폐쇄망 내부 도구라는 전제 하의
> 의도적 선택이며, 암호화/시크릿 스토어 연동은 명시적으로 범위 밖이다.

## 3. 데이터 모델

### 3.1 `EnvVar` (신규, JSONB 항목)

`mcps_extra`(`TaskMcpSpec`) 패턴을 그대로 따른다. record + compact constructor 기본값.

```java
public record EnvVar(String key, String value, boolean secret) {
    public EnvVar {
        if (key == null) key = "";
        if (value == null) value = "";
    }
}
```

- `key`: 환경변수 이름 (예: `JWT_SECRET`, `SPRING_DATASOURCE_URL`)
- `value`: 값 (평문)
- `secret`: true면 UI에서 마스킹 표시 — 저장/주입 동작에는 영향 없음

### 3.2 `com.task.env_vars` 컬럼 (Flyway V8)

```sql
-- V8__task_env_vars.sql
ALTER TABLE com.task
    ADD COLUMN env_vars JSONB NOT NULL DEFAULT '[]'::jsonb;
```

기존 row는 `[]`로 안전(`NOT NULL DEFAULT`, V2 `worker_heartbeat.mcps` 선례와 동일).
큐 통계 뷰는 env_vars를 참조하지 않으므로 뷰 재생성 불필요.

### 3.3 `Task` 엔티티 필드

`mcpsExtra`와 동일한 매핑 패턴:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "env_vars", nullable = false, columnDefinition = "jsonb")
@Setter
private List<EnvVar> envVars = new ArrayList<>();
```

`Task.create(...)`는 변경하지 않는다(env는 생성 후 배포 시점에 set). 신규 task는 빈 리스트로 시작.

## 4. 데이터 흐름

```
[배포 다이얼로그]  admin이 key/value/secret 행 입력
       │  POST /tasks/{id}/deploy  body: { envVars:[{key,value,secret}, ...] }
       ▼
[TaskService.deploy]  envVars를 task.env_vars에 저장 → DEPLOY_PENDING 전이
       ▼
[WorkerService.claimNextTask]  WorkerTaskResponse.forDeploy(t)  ← envVars 포함
       ▼
[DeployService.deploy]  task.envVars() → Map<String,String> 구성 → DeploySpec.env
       ▼
[LocalDockerTarget.deploy]  spec.env().forEach → docker run -e KEY=VALUE  (★기존 코드, 변경 없음)
```

핵심: **LocalDockerTarget은 이미 `spec.env()`를 `-e`로 주입한다**(line 64). 따라서 워커단 변경은
"`DeployService`가 `Map.of()` 대신 task의 env로 채운다" 한 곳뿐이다.

## 5. 컴포넌트별 변경

### 5.1 백엔드

| 변경 | 파일 | 내용 |
|---|---|---|
| 마이그레이션 | `db/migration/V8__task_env_vars.sql` | `env_vars JSONB NOT NULL DEFAULT '[]'` |
| 신규 record | `entity/EnvVar.java` | `(key, value, secret)` |
| 엔티티 필드 | `entity/Task.java` | `List<EnvVar> envVars` + `@Setter` |
| 배포 요청 DTO | `dto/DeployRequest.java` (신규) | `record DeployRequest(List<EnvVar> envVars) {}` — 전체 null/빈 허용 |
| 서비스 | `service/TaskService.java` | `deploy(id, adminId, List<EnvVar>)`, `redeploy(id, adminId, List<EnvVar>)` — 전이 직전 `t.setEnvVars(...)`. null이면 기존 값 유지(재배포 재사용) |
| 컨트롤러 | `controller/TaskController.java` | `deploy`/`redeploy`에 `@RequestBody(required=false) DeployRequest` 추가 |
| 워커 페이로드 | `dto/WorkerTaskResponse.java` | 컴포넌트에 `List<EnvVar> envVars` 추가, `forDeploy`에서 `t.getEnvVars()` 전달. 그 외 팩토리(`forAnalysis`/`forImplementation`/`forUndeploy`)는 `List.of()` |
| 응답 노출 | `dto/TaskResponse.java` | 상세에 `List<EnvVar> envVars` 노출(평문 그대로 — UI가 마스킹). 리스트 뷰엔 미포함 |
| 배포 오케스트레이션 | `workerdaemon/DeployService.java` | `DeploySpec`의 `Map.of()` → `task.envVars()`를 `Map<String,String>`(key→value)로 변환. 빈 key 제외 |

> `undeploy`는 env와 무관 — 변경 없음.
> `WorkerTaskResponse` record 컴포넌트 추가 시 모든 팩토리 호출부 인자 수가 바뀌므로 컴파일러가 누락을 잡아준다.

### 5.2 프론트엔드 (`frontend/pages/tasks/[id].vue`)

- **배포 다이얼로그**: 기존 `deploy()` 버튼 클릭 → `$q.dialog`(또는 인라인 카드)로 env 편집 UI.
  - 동적 행: `[ key 입력 ] [ value 입력 ] [secret 토글] [행 삭제]` + "행 추가" 버튼
  - task에 저장된 `envVars`로 pre-fill
  - `secret=true` 행의 value는 `type="password"` + 눈 아이콘 보기 토글
  - 확인 시 `POST /tasks/{id}/deploy` body `{ envVars }`
- **재배포**: 저장된 env로 pre-fill된 동일 다이얼로그(수정 가능). body 동봉
- **표시**: 배포 카드에 현재 env 요약(secret은 `••••••`). 비밀 아닌 값은 그대로
- 인터페이스: `EnvVar { key: string; value: string; secret: boolean }` 추가

### 5.3 설정/문서

- `application-worker.yml`: 변경 불필요(주입 경로는 코드 레벨). DB 자동 매핑 없음 결정에 따라 신규 설정 없음
- `CLAUDE.md`: 배포 섹션에 "env는 task별 입력·평문 저장" 한 줄 메모(선택)

## 6. 범위 경계 (YAGNI)

**포함**
- task별 env CRUD(배포 다이얼로그에서)
- 평문 저장 + UI 마스킹 표시
- 컨테이너 `-e` 주입
- 재배포 시 저장된 env 재사용

**제외 (명시적)**
- 시크릿 암호화·외부 시크릿 스토어(Vault 등)
- 레포/사용자 단위 env 프리셋·템플릿
- `host.docker.internal` 자동 매핑, DB 접속 자동 구성
- 작업 등록(create) 단계 env 입력 — 배포 시점 입력만으로 충분(필요 시 후속)

## 7. 테스트 / 검증

### 7.1 단위
- `TaskServiceDeployEnvTest`
  - `deploy`에 envVars 전달 시 `task.env_vars` 저장 + `DEPLOY_PENDING` 전이
  - `deploy(envVars=null)`이면 기존 env_vars 유지
  - `redeploy`도 동일하게 갱신/유지
- `EnvVar` 직렬화 round-trip(JSONB) — `mcps_extra` 테스트와 동형
- `WorkerTaskResponse.forDeploy`가 task의 envVars를 그대로 담는지

### 7.2 통합
- 기존 배포 단위/워커 테스트(`TaskServiceDeployTest`, `WorkerServiceDeployTest`)가 시그니처 변경 후에도 green

### 7.3 e2e (최종 성공 기준)
netis7.0 task(PR생성 상태)에 실제 값 주입 후 배포:
```
SPRING_DATASOURCE_URL = jdbc:postgresql://host.docker.internal:5432/postgres
SPRING_DATASOURCE_USERNAME = postgres
SPRING_DATASOURCE_PASSWORD = ntflow   (secret=true)
JWT_SECRET = <값>                      (secret=true)
```
→ 컨테이너가 헬스체크 grace 기간(15s) 동안 생존 = **배포완료**, 접속 URL로 앱 응답 확인.
(MVP에서 H2 미존재로 크래시하던 케이스가 실제 DB 연결로 해소되는 것을 확인)

## 8. 리스크

| 리스크 | 완화 |
|---|---|
| `host.docker.internal`가 호스트 PostgreSQL에 닿지 않음 | macOS Docker Desktop은 기본 지원. 사용자가 URL을 직접 제어하므로 환경에 맞게 조정 가능 |
| 평문 시크릿이 `deploy_log`·`docker inspect`에 노출 | `LocalDockerTarget`이 `docker run` 명령을 deploy_log에 그대로 기록하므로 `-e KEY=VALUE`의 값이 평문으로 남는다. **평문 저장 결정과 일관되므로 MVP에선 마스킹하지 않는다**(DeployTarget이 secret 여부를 모르고, 마스킹하려면 인터페이스 확장 필요 — 범위 밖) |
| record 컴포넌트 추가로 다수 호출부 깨짐 | 컴파일 에러로 전부 드러남, 기계적 수정 |
