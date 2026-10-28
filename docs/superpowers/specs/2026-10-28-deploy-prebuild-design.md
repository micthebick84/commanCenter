# 배포 전 워커 미리 빌드(레포별 설정) 설계

2026-10-28 · 초안(사용자가 질문 없이 진행을 요청 — 아래 결정은 표시한 것 외에는 설계자 판단) · 선행: `2026-09-23` 원격 Docker 배포, `2026-10-07-deploy-env-template-design.md`, `2026-10-14-close-artifact-delivery-design.md`

## 1. 왜

작업 #18(`netisdash_linaems_v6.8`, 2026-10-28)이 배포실패로 끝났다. 원인은 배포 때 Claude가 만든 Dockerfile의
빌드 단계(`RUN mvn -B package`)가 **원격 Docker(10.1.1.75) 안에서** 사내 라이브러리(`ojdbc7`·`hamon:metis`·
`hamon:DelegateProtocol`·`com.hamon:KeyGenerator`)를 받지 못한 것이다.

- pom의 저장소는 두 곳 다 쓸 수 없다. `repository.jspresso.org`는 이미 없는 호스트(Unknown host)이고, 사내 Nexus `10.1.3.195:8082`는 10.1.1.75와 워커 PC 어느 쪽에서도 접속되지 않는다(2026-10-28 `Test-NetConnection` 확인).
- 워커 PC의 `~/.m2`에는 그 라이브러리가 이미 있다(다른 PC에서 받아 둔 사본).

2026-10-28에 같은 커밋(`52a4416`)으로 수동 시험을 해서 다음을 확인했다.

| 단계 | 결과 |
|---|---|
| 워커 PC에서 `mvn -B -o -Daether.enhancedLocalRepository.trackingFilename=_none_ clean package -DskipTests` (JDK 8 corretto, Maven 3.9.11) | 31초, `hm-dash/target/hm-dash.war` 273MB |
| 그 war만 복사하는 Dockerfile(`FROM tomcat:8.5-jre8-temurin`, `COPY … webapps/ROOT.war`)로 원격 `docker build` | 성공 242초 — 대부분은 빌드 컨텍스트 ssh 전송 |
| 컨테이너 기동 | Tomcat 정상 기동, `GET /` 200 |
| 함정 | JRE 이미지에는 `jar`가 없다 → war를 이미지 안에서 풀지 말고 `ROOT.war`로 두어 Tomcat이 풀게 한다 |

이 경로를 제품 기능으로 만든다. **레포 카탈로그에서 켠 레포만** 배포 때 워커가 먼저 빌드하고, Dockerfile은
그 산출물만 담는다.

## 2. 목표 · 비목표 · 반목표

**목표**
- 관리자가 레포 카탈로그 항목에 "배포 전 워커에서 미리 빌드"를 켜고 빌드 명령·산출물 경로·도구 이름을 적으면,
  그 레포의 배포(재배포 포함)는 워커 PC에서 명령을 먼저 실행하고 산출물만 원격 Docker로 보낸다.
- 켜지 않은 레포의 배포는 지금과 바이트 단위로 같다(claim 바디의 새 필드는 `null`).
- 미리 빌드 실패는 배포실패로 끝나고, 화면의 배포 로그에서 빌드 출력 끝부분으로 원인을 볼 수 있다.

**비목표**
- 사내 Nexus 접근 개선(인프라 요청 — 별도). 이 기능은 Nexus가 열려도 그대로 쓸 수 있다.
- 구현 단계(구현 세션의 빌드 검증)나 종료 산출물 단계의 빌드 방식 변경.
- 워커가 가진 도구 목록을 화면에 선택지로 보여 주기(heartbeat 보고). 도구 이름은 텍스트로 입력한다.
- 배포 때 생성된 Dockerfile이 쓰는 환경변수를 배포 다이얼로그에 채우는 일(#18의 두 번째 문제 — 별도 과제, §10).
- `~/.m2` 사본을 최신으로 유지하는 일(운영 몫, §9).

**반목표** (이렇게 되면 실패)
- 켜지 않은 레포의 배포 흐름·로그·Dockerfile 생성 프롬프트가 바뀌는 것.
- 미리 빌드한 명령이 스택 비밀(`WORKER_API_KEY`·`GITLAB_TOKEN`·`SPRING_DATASOURCE_PASSWORD` 등)을 env로 보는 것.
- 산출물을 못 찾았는데 그냥 docker build로 넘어가 엉뚱한 이미지가 배포되는 것.
- 워커 PC의 경로(JDK·Maven 위치)가 API DB에 저장되는 것 — 경로는 워커 설정에만 둔다.

## 3. 확정된 결정

| 결정 | 내용 |
|---|---|
| 켜는 단위 | **레포 카탈로그 항목**(사용자 선택: "레포별 미리 빌드 설정 방식"). 작업별 override는 없다 |
| 설정값 | 켜기(bool) · 빌드 명령(텍스트) · 산출물 경로(상대 경로, 마지막 경로 조각에만 `*` 허용) · 도구 이름(선택) |
| 적용 시점 | 배포 claim 때 카탈로그의 **현재 값**을 싣는다(작업에 박제하지 않음) — 관리자가 고치면 다음 배포·재배포부터 반영. 카탈로그가 없는 작업(`repo_catalog_id` null)은 끔 |
| 도구(툴체인) | 워커 설정 `netis-maker.worker.deploy.prebuild.toolchains.<이름>`에 `java-home`과 PATH 앞에 붙일 폴더 목록. 카탈로그는 **이름만** 저장. 이름이 비면 워커 env 그대로 |
| 실행 위치 | deploy worktree 루트(`createForDeploy`가 만든 폴더), 명령은 Windows `cmd.exe /d /s /c`, 그 밖 `sh -c` |
| env | 워커 env − `StackSecretEnv.NAMES`(claude 자식·soffice와 같은 목록) + 툴체인의 `JAVA_HOME`·PATH 앞붙임 |
| 산출물 | 경로가 **정확히 1개** 일반 파일과 맞아야 한다. 0개·2개 이상·링크면 배포실패(맞은 이름 목록을 로그에). 맞은 파일을 worktree 루트 `.netis-prebuild/<원래 파일 이름>`으로 복사 |
| 빌드 컨텍스트 | Dockerfile을 **생성한 경우에만** 워커가 `.dockerignore`를 `.netis-prebuild/`만 남기도록 덮어쓴다(기존 파일은 로그에 남기고 교체). 레포에 Dockerfile이 있으면 레포의 `.dockerignore`·Dockerfile을 건드리지 않는다(레포 Dockerfile이 기준 — `.netis-prebuild/` 경로를 쓰도록 레포가 맞출 일) |
| Dockerfile 생성 | 생성 프롬프트에 "산출물은 `.netis-prebuild/<이름>`에 이미 있다 · 빌드 단계·빌드 도구 이미지 금지 · 런타임 이미지에 COPY만 · war는 Tomcat `webapps/ROOT.war`로 두고 이미지 안에서 풀지 말 것(JRE에 jar 없음)" 절을 덧붙인다. 끝의 빌드 금지 규칙(`DOCKERFILE_NO_BUILD_RULE`)은 그대로 |
| 시간 상한 | `DEPLOY_PREBUILD_TIMEOUT`(기본 20m). 넘으면 프로세스 트리째 종료 → 배포실패 `미리 빌드 시간 초과(N분)` |
| `배포중` 회수 판정 | API `app.task.deploy-stale-threshold-minutes` 기본값을 **15 → 70**으로 올린다. 지금도 워커 상한(Dockerfile 생성 10m + build 30m)이 15분보다 길어 오래 걸리는 정상 배포를 API가 먼저 `배포실패`로 회수할 수 있었다. 70 = 생성 10 + 미리 빌드 20 + build 30 + 기동 확인·여유 10. 프로세스가 달라 기동 검증으로 묶지 않고 두 yml에 서로를 가리키는 주석을 단다(설계자 판단) |
| 카탈로그 부분 갱신 | `prebuildEnabled`가 `null`인 PUT은 미리 빌드 4개 값을 **그대로 둔다**(`applyArtifact`의 `artifactEnabled==null` 규칙과 같음 — 활성화 토글 PUT이 설정을 지우지 않게). `false`면 끄고 값은 보존 |
| 비활성 카탈로그 | 카탈로그 `enabled=false`(새 등록에서 숨김)여도 배포 claim은 미리 빌드 설정을 **그대로 쓴다** — `enabled`는 등록용 표시다. 항목이 삭제돼 없으면 끔(API 로그에 `배포 claim: 카탈로그 항목 없음(id=N) — 미리 빌드 끔` 경고 한 줄 — 워커는 카탈로그를 모른다) |
| Dockerfile이 산출물을 안 쓰는 경우 | 레포 Dockerfile이든 생성한 Dockerfile이든 본문에 `.netis-prebuild`가 없으면 **경고 한 줄**만 남기고 진행한다(`[경고] Dockerfile이 .netis-prebuild/를 쓰지 않음 — 미리 빌드 산출물이 이미지에 들어가지 않을 수 있음`). 파서로 COPY를 검증하지 않는다 |
| 구버전 워커 | claim 가드를 두지 않는다. 모르는 필드를 버린 구버전 워커는 지금처럼 컨테이너 안 빌드를 시도한다(최악이 오늘과 같음). 배포 순서로 막는다(§8) |

## 4. 데이터 · API

### 4.1 마이그레이션 `V35__repo_catalog_prebuild.sql`

```sql
ALTER TABLE com.repo_catalog
    ADD COLUMN IF NOT EXISTS prebuild_enabled   BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS prebuild_command   TEXT,
    ADD COLUMN IF NOT EXISTS prebuild_artifact  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS prebuild_toolchain VARCHAR(50);
```

`RepoCatalogEntry`에 같은 이름의 필드, `RepoCatalogDto`(요청·응답)에 `prebuildEnabled`·`prebuildCommand`·
`prebuildArtifact`·`prebuildToolchain`.

### 4.2 검증 (`RepoCatalogService` 저장 시, 위반은 400 + 한국어 사유)

- 켜져 있으면 명령·산출물 경로 필수(trim 뒤 비어 있으면 거부). 꺼져 있으면 값은 저장만 하고 쓰지 않는다(다시 켤 때 재입력 불필요).
- 명령: 4000자 이하, 줄바꿈 금지(한 줄 명령 — 여러 단계는 `&&`).
- 산출물 경로: 500자 이하, 상대 경로, `/`와 `\` 둘 다 구분자로 읽고, `..` 조각·드라이브 문자·선행 구분자 금지, `*`는 마지막 조각에만.
- 도구 이름: 비거나 `[A-Za-z0-9._-]{1,50}`.
- 관리자 전용(카탈로그 수정 권한 그대로). 명령은 관리자가 쓴 그대로 워커 PC에서 실행된다는 점을 화면 문구로 알린다.

### 4.3 배포 claim

`WorkerTaskResponse`에 `PrebuildSpec prebuild` 필드를 **맨 끝에** 더한다(`record PrebuildSpec(String command, String artifact, String toolchain)`).
`WorkerService`의 배포 claim(현재 `WorkerTaskResponse.forDeploy(t)`)이 `task.repo_catalog_id`로 카탈로그를 읽어
켜져 있으면 채우고, 아니면 `null`. 다른 kind는 늘 `null`.

## 5. 워커

### 5.1 설정 (`application-worker.yml` · `WorkerProperties.Deploy`)

```yaml
deploy:
  prebuild:
    timeout: ${DEPLOY_PREBUILD_TIMEOUT:20m}
    toolchains:
      jdk8-maven:
        java-home: ${PREBUILD_JDK8_JAVA_HOME:}
        path: ${PREBUILD_JDK8_PATH:}      # PATH 앞에 붙일 폴더, ';'(Windows)·':'(그 밖) 구분 — 예: Maven bin
```

- `java-home`이 빈 툴체인은 "설정 안 됨"으로 본다(기동은 막지 않음). 기동 로그에 `미리 빌드 도구: jdk8-maven(java=…)`처럼 설정된 것만 한 줄.
- 툴체인을 더 두려면 yml에 항목을 더한다(env 이름 규칙은 이 항목처럼).

### 5.2 흐름 (`DeployService.deploy`, `prebuild != null`일 때)

1. deploy worktree 생성(지금과 같음).
2. **미리 빌드** — 로그 `[미리 빌드] $ <명령> (도구=<이름|워커 env>)`.
   - 도구 이름이 있는데 워커에 없거나 `java-home`이 비면 → 배포실패 `미리 빌드 도구 '<이름>'가 이 워커에 설정되지 않음`.
   - `ProcessRunner.runStreaming` + removeEnv=`StackSecretEnv.NAMES` + extraEnv(`JAVA_HOME`, `PATH`=앞붙임+기존). 출력은 배포 로그 스트림(logSink)에 그대로 — 화면 배포 로그가 지금처럼 실시간으로 보인다.
   - exit ≠ 0 → 배포실패 `미리 빌드 실패 (exit=N)`, 시간 초과 → `미리 빌드 시간 초과(N분)`. 로그 끝 8000자(지금 build 실패와 같은 규칙)를 `deploy_log`에 — **시간 초과 때도** 남긴다. `ProcessRunner`는 시간 초과 시 출력을 버리고 예외만 던지므로, `onLine` 콜백에서 워커가 직접 쌓은 버퍼로 꼬리를 만든다. 종료는 기존 `destroyTree`(자손 먼저 떠 두고 트리째) 그대로 — `cmd /c mvn …`의 java 자식까지 끝나는지 테스트 하나로 확인.
3. **산출물 수집** — 경로를 worktree 루트 기준으로 펼쳐 정확히 1개 일반 파일인지 확인 → `.netis-prebuild/<이름>`으로 복사. 로그 `[미리 빌드 산출물] hm-dash/target/hm-dash.war (273.5MB) → .netis-prebuild/hm-dash.war`. 실패 사유: `미리 빌드 산출물 없음: <경로>` / `미리 빌드 산출물이 여러 개: a, b, …(최대 5개)` / `미리 빌드 산출물이 일반 파일이 아님`.
4. Dockerfile 확인 — 없으면 생성(§5.3 프롬프트)하고, **생성이 끝난 뒤** `.dockerignore`를 덮어쓴다(생성 claude가 `.dockerignore`를 만들었어도 워커 것이 이긴다):
   ```
   *
   !.netis-prebuild/
   !.netis-prebuild/**
   ```
   로그 `[빌드 컨텍스트] .dockerignore를 미리 빌드 산출물만 보내도록 씀`(기존 파일이 있었으면 `(레포의 .dockerignore 교체)`). 있으면 지금처럼 `[기존 Dockerfile 사용]` — 레포의 `.dockerignore`는 그대로라 컨텍스트가 커질 수 있다(레포가 맞출 일). 어느 쪽이든 §3의 `.netis-prebuild` 미사용 경고 검사를 한다.
   - `.netis-prebuild/`는 `info/exclude`에 넣지 않는다 — deploy worktree는 커밋하지 않는다.
   - 산출물 경로는 마지막 조각만 와일드카드라 멀티모듈의 `*/target/*.war` 같은 형태는 지원하지 않는다(필요해지면 확장). `*-sources.war`처럼 둘 이상 맞으면 실패하므로 관리자가 경로를 좁힌다.
5. 이후(포트 파싱·env 주입·`target.deploy`)는 지금과 같다.

`prebuild == null`이면 1→4→5의 기존 흐름 그대로(로그 문구 포함).

### 5.3 Dockerfile 생성 프롬프트 덧붙임 (`DeployService.buildDockerfilePrompt`에 인자 추가)

미리 빌드가 켜졌을 때만 템플릿 뒤·`DOCKERFILE_NO_BUILD_RULE` 앞에 붙인다.

```
## 미리 빌드된 산출물 (필수)
- 워커가 이미 빌드했습니다: `.netis-prebuild/<이름>` (원래 위치 `<산출물 경로>`).
- Dockerfile에 빌드 단계(mvn·gradle·npm 등)와 빌드 도구 이미지를 넣지 마세요. 런타임 이미지 하나에 이 파일을 COPY만 하세요.
- 빌드 컨텍스트에는 `.netis-prebuild/`만 들어갑니다. 레포의 다른 파일을 COPY하지 마세요.
- war라면 Tomcat `webapps/ROOT.war`로 복사하고 이미지 안에서 풀지 마세요(JRE 이미지에는 jar 명령이 없습니다).
- 런타임 버전(JRE·Tomcat 등)은 레포의 빌드 설정(pom 등)을 읽고 맞추세요.
```

## 6. 화면 (`frontend/pages/admin/repo-catalog.vue` 등록·수정 다이얼로그)

산출물(`artifactEnabled`) 묶음 아래에 같은 모양의 묶음을 더한다.

- 토글 `배포 전 워커에서 미리 빌드` + 캡션 "사내 라이브러리처럼 원격 Docker 안에서 받을 수 없는 의존성이 있을 때. 명령은 워커 PC에서 운영자 계정으로 실행됩니다."
- 켜면 보이는 입력: `빌드 명령`(한 줄), `산출물 경로`(예시 placeholder `hm-dash/target/*.war`), `도구 이름`(placeholder `jdk8-maven`, 캡션 "워커 설정의 툴체인 이름. 비우면 워커 기본 환경").
- 목록·카드에는 켜진 항목에 작은 칩 `미리 빌드`.
- 서버 검증 오류는 기존 다이얼로그 오류 표시 방식 그대로.

## 7. 테스트

- **API**: 카탈로그 검증 단위 테스트(필수값·줄바꿈·`..`·절대경로·`*` 위치·도구 이름 형식·꺼짐일 때 값 보존·`prebuildEnabled=null` PUT이 값 유지), 배포 claim이 켜짐/꺼짐/카탈로그 없음/카탈로그 비활성에서 `prebuild`를 채우거나 `null`로 두는 테스트, `deploy-stale-threshold-minutes` 기본값 70, V35 적용 IT(Testcontainers, CI).
- **워커**: 산출물 펼치기 순수 함수(0·1·2개·링크·하위 폴더 `*`), PATH 앞붙임·`JAVA_HOME`·비밀 env 제거 조립, `.dockerignore` 쓰기(생성 시에만·기존 교체 로그), 프롬프트 조립(켜짐 때만 절 추가, 꺼짐 때 기존 문자열과 동일), 미설정 도구 오류. 실제 명령 실행은 `cmd /c echo`·`sh -c` 수준의 작은 시험(OS별 assume), 시간 초과 때 꼬리 로그가 남고 자식 프로세스까지 끝나는지, Dockerfile에 `.netis-prebuild`가 없을 때 경고.
- **프론트**: 다이얼로그 토글·입력 노출·바디 필드(vitest).
- **수동(배포 뒤)**: #18 카탈로그(id 9)에 아래 §9 값으로 켜고 재배포 → `배포완료`, 공개 URL 응답.

## 8. 배포 순서

API(V35, 카탈로그 기본값 꺼짐 — 아무 레포도 바뀌지 않음; `배포중` 판정 70분) → 워커(진행 중 배포가 없을 때 재기동, `public.env`에 툴체인 env) → 프론트(`.output` 재빌드) → 카탈로그에서 레포 켜기.

## 9. 운영 메모 (이 PC)

- 도구: JDK 8 `C:\Users\mic\.jdks\corretto-1.8.0_502`, Maven `C:\Users\mic\tools\apache-maven-3.9.11`(2026-10-28 Apache 아카이브에서 받아 SHA-512 대조, Temp 밖 — 예전 `%TEMP%` 사본은 정리로 지워졌다).
  `public.env`: `PREBUILD_JDK8_JAVA_HOME=C:\Users\mic\.jdks\corretto-1.8.0_502`, `PREBUILD_JDK8_PATH=C:\Users\mic\tools\apache-maven-3.9.11\bin`.
- #18 레포(`netisdash_linaems_v6.8`) 카탈로그 값: 명령 `mvn -B -o -Daether.enhancedLocalRepository.trackingFilename=_none_ clean package -DskipTests`, 산출물 `hm-dash/target/*.war`, 도구 `jdk8-maven`. `-o`(오프라인)와 trackingFilename 옵션은 `~/.m2`의 사내 jar가 다른 저장소 표식(`_remote.repositories`)을 달고 있어 거부되는 것을 피하려는 것.
- 남은 위험: 오프라인 `~/.m2` 사본에 없는 사내 라이브러리·버전이 필요해지면 미리 빌드가 실패한다(로그에 Maven의 `Could not resolve` 줄). Nexus 접근을 인프라에 요청해 두는 것이 근본 해결.
- 빌드 컨텍스트 전송: war 273MB 기준 약 4분(ssh). `build-timeout` 30분 안이다.
- 디스크: `.netis-prebuild/`와 `target/`은 deploy worktree 안이라 배포 뒤 worktree 정리 때 같이 지워진다.
- 종료 산출물을 켤 레포라면 카탈로그 `artifact_path`를 이미지 안 경로(`/usr/local/tomcat/webapps/ROOT.war`)로 둔다.

## 10. 범위 밖으로 남긴 것

- 배포 때 생성한 Dockerfile이 쓰는 env 이름(#18의 `JDBC_URL` 등)을 배포 다이얼로그 템플릿에 더하기 — 다이얼로그가 Dockerfile 생성보다 먼저 열리는 구조 문제. 별도 스펙.
- 미리 빌드 결과(산출물) 캐시·재사용 — 재배포마다 다시 빌드한다(31초 수준이라 이득이 작다).
- 워커별 도구 목록을 화면 선택지로 보여 주기.

## 11. 구현자 재량

- `PrebuildSpec`·툴체인 설정 record의 정확한 이름·위치, 산출물 펼치기 구현(`PathMatcher` 등), 로그 문구의 세부 표현(위 사유 문구의 앞부분은 유지).
- 명령 실행에 기존 `ProcessRunner.runStreaming`의 확장(removeEnv·extraEnv를 받는 스트리밍 변형)을 쓸지, 같은 모양의 새 메서드를 둘지.
- 프론트 묶음의 배치(산출물 묶음 위/아래).
