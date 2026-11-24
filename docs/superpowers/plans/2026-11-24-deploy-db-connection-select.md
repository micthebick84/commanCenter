# 배포 DB 연결 고르기 · 배포 설정 덮어쓰기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레포 카탈로그에 배포 설정(DB 자리·변수)을 정의하면 배포 창에서 등록된 DB 연결을 고르고 변수는 짧은 값만 넣으며, 워커가 env 또는 WAR 설정 파일 사본으로 매 배포 같게 적용한다.

**Architecture:** API는 카탈로그 `deploy_config`(JSONB)와 작업 `deploy_db_selection`(JSONB)을 저장하고, 배포 claim 직후(커밋 뒤) 연결을 복호화해 조립이 끝난 `deployOverrides`(env·파일 항목·요약·오류)를 실어 보낸다. 워커는 env는 `-e`로, 파일 항목은 미리 빌드 WAR의 원본 끝에 덧붙인 사본을 `docker create → docker cp → docker start`로 `/opt/netis-override`에 넣고, 생성 Dockerfile 끝에 Tomcat이 그 폴더를 먼저 읽게 하는 블록을 붙인다. 배포 설정이 없는 레포는 지금과 바이트까지 같다.

**Tech Stack:** Java 21 · Spring Boot · Hibernate 6 JSON(`@JdbcTypeCode(SqlTypes.JSON)`) · Flyway · JUnit 5/Mockito/AssertJ · Testcontainers(CI) · Nuxt 3 + Quasar · vitest · Docker CLI(원격 `DOCKER_HOST`) · Tomcat 7/8.5

**Spec:** `docs/superpowers/specs/2026-11-24-deploy-db-connection-select-design.md`

## Global Constraints

- 작업 위치: 이 worktree(`.claude/worktrees/deploy-db-connection-select`, 브랜치 `feature/deploy-db-connection-select`)에서만. **본 체크아웃(`C:\Users\mic\NetisMaker\commanCenter`)에서 빌드·테스트 금지** — 라이브 `bootRun`이 그 `build/`를 쓴다.
- 백엔드 시험: Git Bash에서 `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1 ./gradlew test --tests '<완전한 클래스 이름>' --no-daemon` (와일드카드 `*X*`는 폴더 이름으로 확장돼 실패한다). Testcontainers 시험(`@EnabledIfEnvironmentVariable(named="RUN_TESTCONTAINERS")`)은 이 PC에서 건너뛰고 CI가 돈다.
- 프론트 시험: 처음 한 번 `frontend/node_modules`를 본 체크아웃 `frontend/node_modules`로의 정션으로 만든다(`New-Item -ItemType Junction`). worktree를 지우기 전에 정션만 먼저 지운다(`[System.IO.Directory]::Delete(<정션>, $false)`). `npm ci`·`npm install` 금지. 실행: `cd frontend && npx vitest run <spec 경로>`.
- 프론트 스타일: 작은따옴표·세미콜론 없음. `prettier --write`를 frontend 전체에 돌리지 않는다.
- 마이그레이션 번호 **V50** — 병합 직전 `origin/main`의 가장 큰 번호를 다시 본다.
- 자리표시자는 정확히 `{host}` `{port}` `{database}` `{username}` `{password}` `{username:url}` `{password:url}`, 변수 형식은 `{값}`을 정확히 한 번.
- 설정 파일 대상 경로: `WEB-INF/classes/`로 시작, `.properties`로 끝, `..`·`\`·앞 `/` 금지.
- 컨테이너 안 사본 폴더: `/opt/netis-override` (그 아래 WAR와 같은 상대 경로, 예: `/opt/netis-override/WEB-INF/classes/config/jdbc_maria.properties`).
- 워커 지원 토큰: `deployOverrides`.
- 고정 문구: 드롭다운 첫 항목 `고르지 않음(WAR 기본값)`, 사본 덧붙임 주석 줄 `# Symphony 배포 덮어쓰기`, 배포 로그 줄 머리 `[DB 연결] `.
- DB 비밀번호는 `task` 행·`deploy_log`·배포 로그 스트림·`toString`·앱 로그(가림 후)·이미지 레이어에 남지 않는다.
- 배포 설정이 없는 레포(`deploy_config IS NULL`)는 화면·API 응답·claim·워커 동작이 지금과 같다 — 기존 시험이 바뀌지 않고 통과해야 한다.
- 커밋 메시지는 `feat:`/`test:`/`docs:` 앞머리 + 한국어, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **특수문자 비밀번호** — `=` `:` `\` `#` `!` `@` `%` 공백·한글이 든 비밀번호가 properties 사본과 URL(`{password:url}`)에서 그대로 복원돼야 한다. → Task 3·Task 10에 왕복 시험.
2. **생성 Dockerfile이 앞에서 `USER netis`로 바꾼 경우** — 붙이는 `RUN`이 root로 돌고 원래 USER로 되돌아가야 한다. → Task 11 시험.
3. **최종 단계 FROM 모양** — `tomcat:7.0.109-jdk8-openjdk-slim`, `docker.io/library/tomcat:8.5-jre8-temurin`, `FROM base AS run`(앞 단계 별칭), 태그 없음·`latest`. 판별 못 하면 배포 실패. → Task 11 시험.
4. **연결이 사라짐** — 창 제출 뒤(또는 몇 주 뒤 수정 회차 자동 재배포 때) 연결이 삭제·비활성·복호화 불가면 claim의 `error`로 배포실패, 메시지에 자리 이름. → Task 5·Task 13 시험.
5. **예전 평문 행과 이름 겹침** — 저장된 `SPRING_DATASOURCE_PASSWORD` 행이 남은 작업의 자동 재배포에서 고른 DB 값이 이긴다. "고르지 않음"을 저장한 작업의 재배포 창은 직전 배포 DB를 미리 고르지 않는다. → Task 5·Task 6 시험.

---

### Task 1: Tomcat 덮어쓰기 방식 사전 확인 (spike, 코드 없음)

설계 §14 "구현 전 확인". 결과가 Task 11·12의 Tomcat 블록과 cp 방식을 정한다. 원격 데몬에 시험 이미지·컨테이너를 만들고 끝에 지운다. 공용 개발 DB(latest)에 **없는 계정**으로 로그인 시도 1회 — 덮어쓰기가 읽혔다는 증거로만 쓴다(데이터 접근 없음).

**실행 주체: 메인 세션이 직접**(원격 Docker·ssh 맥락이 필요해 새 서브에이전트에 맡기지 않는다). Task 10–13을 맡기기 전에 끝낸다. 이미지 빌드·Step 1은 Git Bash(`MSYS_NO_PATHCONV=1`)로 해도 되지만, **`docker create`·`docker cp`·`docker start`는 워커와 같은 길 — PowerShell에서 `C:\Users\mic\.local\bin\docker.exe` + `$env:DOCKER_HOST='ssh://Administrator@10.1.1.75'` + Windows 절대 경로(`C:\…\ovr\.`)** 로 한다(워커는 Windows 내장 ssh 9.5를 쓰고 Git Bash는 다른 ssh다).

**Files:** scratchpad에만(`%TEMP%\dbo-spike\`). 레포에 커밋하지 않는다.

- [ ] **Step 1: 원본 설정 파일 꺼내기** — Git Bash, `export DOCKER_HOST=ssh://Administrator@10.1.1.75 MSYS_NO_PATHCONV=1`. `nginx:alpine` 단계에서 `netis-task-49:61940e8`의 `/usr/local/tomcat/webapps/ROOT.war`를 받아 `unzip -p … WEB-INF/classes/config/jdbc_maria.properties`로 `orig.properties`를 로컬 파일로 저장한다(화면에 출력하지 않는다 — 개발 DB 비밀번호가 들어 있다).
- [ ] **Step 2: 사본 만들기** — `orig.properties` 끝에 `# Symphony 배포 덮어쓰기`, `jdbc.url=jdbc:log4jdbc:mysql://other-db.example.invalid:53306/netis?useSSL=false`, `jdbc.username=symphony_spike`, `jdbc.password=x`를 덧붙여 `ovr/WEB-INF/classes/config/jdbc_maria.properties`로 둔다.
- [ ] **Step 3: Tomcat 7 시험(root 아닌 사용자)** — Dockerfile: `FROM netis-task-49:61940e8 AS src` / `FROM tomcat:7.0.109-jdk8-openjdk` / `RUN useradd -r -u 1001 netis && chown -R netis /usr/local/tomcat` / `COPY --from=src /usr/local/tomcat/webapps/ROOT.war /usr/local/tomcat/webapps/ROOT.war` / `RUN mkdir -p /opt/netis-override/WEB-INF/classes && chmod -R a+rX /opt/netis-override && sed -i 's#</Context>#<Loader className="org.apache.catalina.loader.VirtualWebappLoader" virtualClasspath="/opt/netis-override/WEB-INF/classes" searchVirtualFirst="true"/></Context>#' /usr/local/tomcat/conf/context.xml` / `USER netis` (#49 생성본처럼 root 아닌 사용자로 돈다). `docker build -t dbo-spike-t7 .` → (PowerShell) `docker create --name dbo-spike-t7 dbo-spike-t7` → `docker cp C:\…\ovr\. dbo-spike-t7:/opt/netis-override/` → `docker start dbo-spike-t7` → 90초 뒤 `{ docker logs dbo-spike-t7 2>&1; docker exec dbo-spike-t7 sh -c 'cat /usr/local/tomcat/logs/*.log'; } | grep -c symphony_spike`.
  Expected: 1 이상(`Access denied for user 'symphony_spike'`). 0이고 `Connection refused`만 있으면 WAR 원본(192.0.2.20)을 읽은 것 → 실패.
- [ ] **Step 4: 권한·원본 확인** — `docker exec dbo-spike-t7 ls -ln /opt/netis-override/WEB-INF/classes/config/` 결과의 mode·uid를 기록. 이미지 WAR 해시 `docker run --rm --entrypoint sha256sum dbo-spike-t7 /usr/local/tomcat/webapps/ROOT.war`가 src 이미지와 같은지 확인.
- [ ] **Step 5: Tomcat 8.5 시험** — Step 3을 `FROM tomcat:8.5-jre8-temurin`(이미지가 없으면 받는다 — 배포 때와 같은 일), `useradd`가 없으면 `adduser --system --uid 1001 netis`, `sed`는 `</Context>` 앞에 `<Resources><PreResources className="org.apache.catalina.webresources.DirResourceSet" base="/opt/netis-override/WEB-INF/classes" webAppMount="/WEB-INF/classes"/></Resources>`로 바꿔 반복. Expected 동일.
- [ ] **Step 6: 정리·기록** — `docker rm -f dbo-spike-t7 dbo-spike-t85`, `docker rmi dbo-spike-t7 dbo-spike-t85`. 결과(성공 여부·권한·바꾼 점)를 설계 문서 §14 끝에 "사전 확인 결과(날짜)" 몇 줄로 적는다. 실패한 쪽이 있으면 Task 11 블록을 그 결과로 바꾸고 계획 Task 11 Step 3의 문자열도 고친다.
- [ ] **Step 7: Commit** — `git add docs/superpowers/specs/2026-11-24-deploy-db-connection-select-design.md` / `git commit -m "docs: 배포 설정 덮어쓰기 Tomcat 사전 확인 결과"`

### Task 2: 저장 모양 — V50 · 레코드 · 엔티티 칸

**Files:**
- Create: `src/main/resources/db/migration/V50__deploy_db_selection.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/DeployConfig.java`, `entity/DeployDbChoice.java`
- Modify: `entity/RepoCatalogEntry.java`(칸 추가), `entity/Task.java`(칸 추가)
- Test: `src/test/java/com/hamonsoft/netismaker/migration/DeployDbSelectionMigrationTest.java`(Testcontainers)

**Interfaces:**
- Produces:
  - `record DeployConfig(List<DbSlot> databases, List<Variable> variables)` — `@JsonInclude(NON_NULL)`, null 목록은 빈 목록으로(compact constructor). 중첩: `record DbSlot(String slot, String label, List<DbType> dbTypes, Target target)`, `record Target(TargetKind kind, String path, LinkedHashMap<String,String> entries)`, `enum TargetKind { ENV, FILE }`, `record Variable(String key, String description, boolean secret, boolean required, String format, String filePath, String fileEntry)`(filePath·fileEntry가 null이면 같은 이름의 env). `boolean isEmpty()` = 두 목록 모두 빔.
  - `record DeployDbChoice(String slot, Long connectionId)` — `connectionId == null`은 "고르지 않음".
  - `RepoCatalogEntry.deployConfig`(`DeployConfig`, null 허용, getter/setter), `Task.deployDbSelection`(`List<DeployDbChoice>`, 기본 빈 목록, `@Setter`).

- [ ] **Step 1: V50 작성** — `ALTER TABLE com.repo_catalog ADD COLUMN IF NOT EXISTS deploy_config JSONB;` / `ALTER TABLE com.task ADD COLUMN IF NOT EXISTS deploy_db_selection JSONB NOT NULL DEFAULT '[]';` 머리 주석 `-- 배포 DB 연결 고르기·배포 설정 덮어쓰기 (스펙 2026-11-24-deploy-db-connection-select)`.
- [ ] **Step 2: 레코드·엔티티 칸** — 엔티티 칸은 기존 JSONB 패턴 그대로(`@JdbcTypeCode(SqlTypes.JSON) @Column(name=…, columnDefinition="jsonb")`, Task 쪽은 `nullable=false` + `new ArrayList<>()`).
- [ ] **Step 3: 왕복 시험 작성** — `DeployDbSelectionMigrationTest`(기존 `RequestBoardDevMigrationTest` 머리 그대로): `컬럼이_생긴다`(information_schema에 `repo_catalog.deploy_config` jsonb nullable, `task.deploy_db_selection` jsonb not null), `카탈로그_배포설정이_왕복된다`(DbSlot 1개 FILE 대상 entries 순서 `jdbc.url`→`jdbc.username`→`jdbc.password` 저장 후 다시 읽어 `isEqualTo`), `작업_DB선택이_왕복된다`(`[("main",43L),("extra",null)]`).
- [ ] **Step 4: 컴파일 확인** — `./gradlew compileJava compileTestJava --no-daemon` → `BUILD SUCCESSFUL`. (왕복 시험은 CI에서 돈다)
- [ ] **Step 5: Commit** — `feat: 배포 설정·배포 DB 선택 저장 칸(V50)`

### Task 3: 값 조립 순수 함수

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/service/deployconfig/DeployValueTemplates.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/deployconfig/DeployValueTemplatesTest.java`

**Interfaces:**
- Produces:
  - `record DbValues(String host, int port, String database, String username, String password)` (DeployValueTemplates 안 중첩, `toString`은 password를 `***`로)
  - `static String fillDb(String template, DbValues v)` — 일곱 자리표시자 치환, `:url`은 `URLEncoder.encode(s, UTF_8).replace("+", "%20")`.
  - `static Set<String> placeholders(String template)` — `{…}` 토큰 목록(검증용).
  - `static String wrapVariable(String format, String input)` — format null이면 input 그대로. `{값}` 앞뒤를 prefix/suffix로 나눠, input이 prefix로 시작하고 suffix로 끝나며 (prefix+suffix)가 비어 있지 않으면 그대로, 아니면 prefix+input+suffix.

- [ ] **Step 1: 실패 시험 작성** — `DeployValueTemplatesTest`:
  - `jdbc_url을_채운다`: `fillDb("jdbc:log4jdbc:mysql://{host}:{port}/{database}?useSSL=false", new DbValues("192.0.2.10",53306,"netis","netis","p"))` → `"jdbc:log4jdbc:mysql://192.0.2.10:53306/netis?useSSL=false"`
  - `url_자리표시자는_퍼센트_인코딩한다`: `fillDb("postgresql://{username:url}:{password:url}@{host}:{port}/{database}", new DbValues("192.0.2.30",55432,"netis","ne tis","p@ss:w/rd#%한"))` → `"postgresql://ne%20tis:p%40ss%3Aw%2Frd%23%25%ED%95%9C@192.0.2.30:55432/netis"`
  - `일반_자리표시자는_인코딩하지_않는다`: `fillDb("{password}", …"p@ss:w/rd")` → `"p@ss:w/rd"`
  - `placeholders는_중괄호_토큰을_돌려준다`: `"{host}:{port}/{x}"` → `{"host","port","x"}`
  - `짧은_값을_감싼다`: `wrapVariable("https://{값}.micthebick.dev","task-1")` → `"https://task-1.micthebick.dev"`
  - `이미_전체_값이면_그대로`: `wrapVariable("https://{값}.micthebick.dev","https://task-1.micthebick.dev")` → 같은 값
  - `형식이_없으면_그대로`: `wrapVariable(null,"abc")` → `"abc"`
  - `DbValues_toString에_비밀번호가_없다`: `toString()`에 `"p"`가 password로 들어 있지 않고 `***` 포함.
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'com.hamonsoft.netismaker.service.deployconfig.DeployValueTemplatesTest' --no-daemon` → 컴파일 실패.
- [ ] **Step 3: 구현** — 위 시그니처대로.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.
- [ ] **Step 5: Commit** — `feat: 배포 설정 값 조립 함수`

### Task 4: 카탈로그 배포 설정 검증 · 관리 API

**Files:**
- Create: `service/deployconfig/DeployConfigValidator.java`
- Modify: `dto/RepoCatalogDto.java`(`View`·`UpsertRequest`에 `DeployConfig deployConfig` 끝 칸 + 16인자 호환 생성자), `service/RepoCatalogService.java`(`applyDeployConfig`), `controller/RepoCatalogController.java`(일반 사용자 목록에서 `deployConfig` 비움)
- Test: `service/deployconfig/DeployConfigValidatorTest.java`, `service/RepoCatalogServiceDeployConfigTest.java`

**Interfaces:**
- Consumes: `DeployConfig`(Task 2), `DeployValueTemplates.placeholders`(Task 3)
- Produces:
  - `static DeployConfig DeployConfigValidator.normalize(DeployConfig in)` — 앞뒤 공백 제거·빈 설정이면 null 반환, 위반이면 `IllegalArgumentException`(한국어 메시지).
  - `static Set<String> DeployConfigValidator.envNames(DeployConfig c)` — 변수(파일 대상 아님) 이름 + DB ENV 대상 이름(대소문자 그대로). Task 6이 쓴다.
  - `static Set<String> DeployConfigValidator.dbEnvNames(DeployConfig c)` — DB ENV 대상 이름만. Task 6(숨김)·Task 7(거부)이 쓴다.
  - `RepoCatalogDto.View.withoutDeployConfig()`, `UpsertRequest.deployConfig()`(null = 유지).
- 규칙(메시지는 구현자 몫, 위반마다 400): 자리 이름 `^[a-z][a-z0-9-]{0,31}$`·중복 금지·`label` 1~40자·`dbTypes` 1개 이상·`target` 필수; ENV entries 이름 `^[A-Za-z_][A-Za-z0-9_]{0,199}$`; FILE `path`는 Global Constraints 규칙, entries 키 1~200자; DB 대상 값의 자리표시자는 일곱 개만; 변수 `key`는 ENV 이름 규칙(파일 대상이면 표시용 이름으로만 쓰되 같은 규칙), `format`은 `{값}` 정확히 한 번·다른 `{…}` 금지; 변수 `filePath`/`fileEntry`는 둘 다 있거나 둘 다 없음; **같은 env 이름 두 번 금지, 같은 (path, 항목) 두 번 금지**(DB·변수 통틀어); DB 자리 최대 3, 변수 최대 50.

- [ ] **Step 1: 실패 시험 작성** — `DeployConfigValidatorTest`: `넷이스67_예시가_통과한다`(설계 §8 JSON 그대로), `빈_설정은_null`, `경로가_classes_밖이면_거부`(`WEB-INF/web.xml`), `점점_경로_거부`(`WEB-INF/classes/../x.properties`), `모르는_자리표시자_거부`(`{schema}`), `형식에_값이_두번이면_거부`, `같은_env_이름_두번_거부`(변수 `SPRING_DATASOURCE_URL` + DB ENV 대상 같은 이름), `같은_파일항목_두번_거부`, `envNames는_변수와_DB_ENV대상_이름`. `RepoCatalogServiceDeployConfigTest`(기존 `RepoCatalogServiceRouteHintTest` 머리): `수정에서_null이면_유지`, `빈_설정을_보내면_비운다`(→ `getDeployConfig()==null`), `잘못된_설정은_400`(`TaskException` 상태 400), `일반_사용자_목록에는_배포설정이_없다`(`View.withoutDeployConfig().deployConfig()==null`).
- [ ] **Step 2: 실패 확인** — 두 클래스 `--tests` → 컴파일 실패.
- [ ] **Step 3: 구현** — `RepoCatalogService.create/update`의 apply 순서 끝(`applyRouteHint` 다음)에 `applyDeployConfig(e, req, creating)`: 수정이고 `req.deployConfig()==null`이면 그대로, 아니면 `normalize` 결과 저장(`IllegalArgumentException` → `badRequest`). `GET /api/repo-catalog`는 `View::withoutRouteHint` 뒤에 `View::withoutDeployConfig`.
- [ ] **Step 4: 통과 확인** — 두 클래스 + 기존 `RepoCatalogService*Test` 전부 `--tests 'com.hamonsoft.netismaker.service.RepoCatalogService*'`는 쓰지 말고 클래스별로 → PASS.
- [ ] **Step 5: Commit** — `feat: 레포 카탈로그 배포 설정 검증·저장`

### Task 5: 배포 덮어쓰기 조립 (순수) · claim DTO

**Files:**
- Create: `dto/DeployOverrides.java`, `service/deployconfig/DeployOverrideAssembler.java`
- Test: `service/deployconfig/DeployOverrideAssemblerTest.java`

**Interfaces:**
- Consumes: Task 2·3·4.
- Produces:
  - `record DeployOverrides(List<EnvVar> env, List<FileOverride> files, String summary, String error)` + `record FileOverride(String path, LinkedHashMap<String,String> entries)`. 두 레코드 `toString`은 값 없이 키·경로·개수만. `static DeployOverrides failed(String error)`.
  - `record ResolvedDb(long id, String name, DbType dbType, boolean enabled, DeployValueTemplates.DbValues values)` (Assembler 안 중첩, `toString`에 비밀번호 없음)
  - `static DeployOverrides DeployOverrideAssembler.assemble(DeployConfig cfg, List<EnvVar> saved, List<DeployDbChoice> selection, Function<Long, Optional<ResolvedDb>> resolver)` — cfg는 null이 아님(호출부가 거름). 선택의 자리가 cfg에 없고 `connectionId != null`이면 `error = "배포 DB 자리 '<slot>'이 카탈로그 배포 설정에 없습니다 — 배포 창에서 다시 고르세요"`(자리 이름을 바꾼 뒤 조용히 WAR 기본값으로 뜨지 않게).
- 조립 규칙(설계 §7.3): env 결과 = 저장 행(빈 키·빈 값 제외) → 변수(`wrapVariable`, 파일 대상 변수는 env에 넣지 않고 files로) → DB ENV 대상 순으로 같은 키를 덮음(순서 유지, 덮어쓴 키는 원래 자리). 변수 값은 저장 행의 같은 키 값(없거나 빈 값이면 넣지 않음). DB 자리: 선택이 없거나 `connectionId==null`이면 건너뜀. resolver가 빈 값 → `error = "배포 DB '<label>': 연결 #<id>를 찾을 수 없습니다(삭제·복호화 불가)"`, `enabled=false` → `"…: 연결 '<name>'이 비활성입니다"`, 종류가 `dbTypes` 밖 → `"…: 연결 '<name>'(<dbType>)은 이 자리의 DB 종류가 아닙니다"`. 첫 오류에서 멈추고 `failed(error)`. FILE 대상은 path별로 entries를 합쳐 `files`에. `summary`는 자리마다 `"<label> = <name> (<dbType> <host>:<port>/<database>) → <path 또는 'env'> <항목 수>항목"`을 `"; "`로 이음, 고른 자리가 없으면 null.

- [ ] **Step 1: 실패 시험 작성** — `DeployOverrideAssemblerTest`:
  - `파일대상_DB를_고르면_files에_세항목`(§8 예시, 연결 43 → `files.get(0).entries()`가 `jdbc.url`=`jdbc:log4jdbc:mysql://192.0.2.10:53306/netis?useSSL=false&…`, `jdbc.username`=`netis`, `jdbc.password`=`pw`, `env`는 저장 행 그대로)
  - `ENV대상_DB가_저장된_같은이름_행을_이긴다`(저장 `SPRING_DATASOURCE_PASSWORD=old` + ENV 대상 → 결과에 `old` 없음, `pw` 하나)
  - `고르지_않음이면_적용하지_않는다`(`connectionId=null` → files 빈, summary null)
  - `설정에_없는_자리를_고른_채면_error`(선택 `("old",43)`, cfg 자리는 `main`뿐 → `error()`에 `'old'`)
  - `연결이_없으면_error`(resolver 빈 값 → `error()`에 `배포 DB '주 DB'`, `env()`·`files()` 빈)
  - `비활성이면_error`, `종류가_다르면_error`(ORACLE을 MARIADB 자리에)
  - `변수는_짧은값을_감싼다`, `파일대상_변수는_files로`
  - `toString에_비밀번호가_없다`(`assemble(...).toString()`에 `"pw"` 미포함)
- [ ] **Step 2: 실패 확인** → 컴파일 실패.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** — `feat: 배포 덮어쓰기 조립`

### Task 6: 배포 창 미리 채움 확장 (`GET /api/tasks/{id}/deploy-env`)

**Files:**
- Modify: `dto/DeployEnvSuggestionResponse.java`(`Row`에 `String format` 끝 칸 + 7인자 호환 생성자, `Source`에 `CATALOG`, 응답에 `List<DbSlotView> databases` 끝 칸 + 3인자 호환 생성자), `service/DeployEnvSuggester.java`(오버로드), `service/DbConnectionService.java`(`deployChoices`), `service/TaskService.java`(`deployEnvSuggestion(Long, String)`), `repository/TaskRepository.java`(`findRecentlyDeployedWithInput`), `controller/TaskController.java`(`deployEnv`에 `JwtAuthenticationToken auth`)
- Test: `service/DeployEnvSuggesterCatalogTest.java`, `service/DbConnectionServiceDeployChoicesTest.java`, `service/TaskServiceDeployEnvCatalogTest.java`

**Interfaces:**
- Consumes: Task 2·4(`envNames`).
- Produces:
  - `record DbSlotView(String slot, String label, List<String> dbTypes, String targetSummary, Selected selected, List<Choice> choices)`, `record Selected(Long connectionId, Source from)`(from은 `SAVED`|`PREVIOUS`, "고르지 않음" 저장은 `connectionId=null, from=SAVED`), `record Choice(long id, String name, String dbType, String scope, String host, int port, String database)` — 모두 `DeployEnvSuggestionResponse` 안 중첩. 비밀번호 칸 없음.
  - `static DeployEnvSuggestionResponse DeployEnvSuggester.suggest(List<EnvVar> saved, List<EnvTemplateItem> template, List<EnvVar> previous, Long previousTaskId, DeployConfig cfg, List<DbSlotView> databases)` — cfg null이면 기존 4인자 결과에 `databases=List.of()`와 같다.
  - `List<DeployEnvSuggestionResponse.Choice> DbConnectionService.deployChoices(Long repoCatalogId, String adminId, Set<DbType> types, Long alsoIncludeId)` — `selectableChips` 규칙(공용 + 본인, enabled, 암호 기능 켜짐) ∩ types, `alsoIncludeId`가 목록 밖이어도 같은 카탈로그·enabled면 덧붙임.
  - `List<Task> TaskRepository.findRecentlyDeployedWithInput(String githubRepo, Long excludeId, String deployPending)` — `findRecentlyDeployedWithEnv`와 같고 조건만 `(t.env_vars <> '[]' OR t.deploy_db_selection <> '[]')`.
  - `DeployEnvSuggestionResponse TaskService.deployEnvSuggestion(Long taskId, String adminId)` — 기존 1인자는 `(taskId, null)`로 위임.
- 미리 채움 규칙(설계 §7.2·§9): cfg 있을 때 행 = 카탈로그 변수(Source.CATALOG, 값은 저장 행 → 저장 env가 하나도 없을 때만 직전 배포 같은 키, `format` 실음) → 저장 행·템플릿 행 중 `envNames`에 없는 것. 직전 배포의 자유 행은 넣지 않는다. DB ENV 대상 이름과 같은 저장·템플릿 행은 숨긴다. DB 선택: `task.deployDbSelection`이 비어 있지 않으면 그 값만(SAVED, 자리 항목이 없으면 null) — 비어 있으면 직전 배포(`findRecentlyDeployedWithInput` 첫 같은 호스트)의 같은 자리 연결이 `choices` 안에 있을 때만 PREVIOUS. `previousTaskId`는 직전 배포 값을 하나라도 썼을 때만.

- [ ] **Step 1: 실패 시험 작성** — `DeployEnvSuggesterCatalogTest`: `cfg가_없으면_기존과_같다`(기존 `DeployEnvSuggesterTest` 첫 시험 입력으로 4인자·6인자 결과 `isEqualTo`, `databases` 빈 목록), `카탈로그_변수가_먼저_고정으로`(Source.CATALOG·format 실림), `직전배포_자유행은_가져오지_않는다`(#49 꼴 `JDBC_URL` previous → 행에 없음), `DB_ENV대상과_같은이름_저장행은_숨긴다`. `TaskServiceDeployEnvCatalogTest`(Mockito, 기존 `TaskServiceDeployEnvTest` 머리): `고르지_않음을_저장한_작업은_직전배포_DB를_고르지_않는다`(task.deployDbSelection=`[("main",null)]`, 직전 배포 `[("main",43)]` → selected `(null,SAVED)`), `선택이_없으면_직전배포_DB를_PREVIOUS로`, `직전배포_연결을_현재_관리자가_못고르면_비운다`. `DbConnectionServiceDeployChoicesTest`(기존 `DbConnectionServiceTest` 머리): `공용과_본인것만_종류로_거른다`, `저장된_다른관리자_개인연결은_덧붙인다`.
- [ ] **Step 2: 실패 확인** → 컴파일 실패.
- [ ] **Step 3: 구현** — `TaskService`는 카탈로그를 `RepoCatalogRepository.findForTask(...)`로 찾는다(`WorkerService.prebuildFor`와 같은 방식).
- [ ] **Step 4: 통과 확인** — 새 세 클래스 + `DeployEnvSuggesterTest`·`TaskServiceDeployEnvTest` → PASS.
- [ ] **Step 5: Commit** — `feat: 배포 창 미리 채움에 배포 설정·DB 선택`

### Task 7: 배포·재배포 요청에 DB 선택

**Files:**
- Modify: `dto/DeployRequest.java`(`List<@Valid DeployDbChoice> dbSelection` 끝 칸 + 1인자 호환 생성자), `controller/TaskController.java`(deploy·redeploy가 `body.dbSelection()` 전달), `service/TaskService.java`(`deploy`·`redeploy` 4인자 + 기존 3인자 위임), `dto/TaskResponse.java`(`List<DeployDbChoice> deployDbSelection` — 모든 생성자 호출·`redactSecretValues` 포함)
- Create: `service/deployconfig/DeployDbSelectionValidator.java`
- Test: `service/TaskServiceDeployDbSelectionTest.java`

**Interfaces:**
- Consumes: Task 2·4(`envNames`).
- Produces:
  - `Task TaskService.deploy(Long taskId, String adminId, List<EnvVar> envVars, List<DeployDbChoice> dbSelection)`, `redeploy(...)` 같은 모양. `dbSelection==null`이면 저장값 유지, 아니면 검증 뒤 그대로 저장.
  - `List<DeployDbChoice> DeployDbSelectionValidator.validate(DeployConfig cfg, Task task, String adminId, List<DeployDbChoice> in, Function<Long, Optional<DbConnection>> lookup)` — 위반이면 `IllegalArgumentException`. 규칙: cfg null인데 비지 않은 선택 → 거부, 자리가 cfg에 없음·중복, 연결의 `repoCatalogId`가 작업 카탈로그와 다름·비활성·종류 불일치, (공용 아님 && `!isOwnedBy(adminId)` && 이 작업의 저장 선택에 같은 (slot,id) 없음) → 거부.
  - envVars 검사: cfg가 있고 `envVars`에 `dbEnvNames(cfg)`(Task 4)와 같은 키가 있으면 400 `"배포 DB가 정하는 변수는 따로 넣을 수 없습니다: <키>"`. 카탈로그 변수 키는 화면이 같은 키의 행으로 보내므로 허용한다.

- [ ] **Step 1: 실패 시험 작성** — `TaskServiceDeployDbSelectionTest`(기존 `TaskServiceDeployTest` 머리): `선택을_저장한다`, `선택이_null이면_유지`(기존 `deploy_with_null_env_keeps_existing`과 같은 꼴), `고르지_않음을_저장한다`, `다른_카탈로그_연결은_400`, `다른_관리자_개인연결은_400`, `이미_저장된_다른관리자_연결은_허용`, `DB_ENV대상_이름의_자유행은_400`, `카탈로그_변수_키는_허용`, `배포설정_없는_레포에_선택을_보내면_400`.
- [ ] **Step 2: 실패 확인** → 컴파일 실패.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 새 클래스 + `TaskServiceDeployTest` → PASS.
- [ ] **Step 5: Commit** — `feat: 배포 요청에 DB 선택 저장·검증`

### Task 8: claim에 덮어쓰기 싣기 · 구버전 워커 막기

**Files:**
- Create: `service/deployconfig/DeployOverrideService.java`
- Modify: `dto/WorkerTaskResponse.java`(끝 칸 `DeployOverrides deployOverrides` + 36인자 호환 생성자 + `WorkerTaskResponse withDeployOverrides(DeployOverrides o)`), `controller/WorkerController.java`(`SUPPORTS_DEPLOY_OVERRIDES = "deployOverrides"`, caps 파싱, claim 뒤 `.map(r -> withDbMcp(r, caps)).map(this::withDeployOverrides)`), `service/WorkerCapabilities.java`(7번째 `boolean deployOverrides` + 6인자 호환 생성자, `ALL`·`needsFilteredQueue`에 포함), `repository/TaskRepository.java`(`findClaimableAmong`에 `:deployPending`·`:includeDeployOverrides`, 래퍼 오버로드), `service/WorkerService.java`(필터 큐 호출에 새 플래그)
- Test: `service/deployconfig/DeployOverrideServiceTest.java`, `dto/WorkerTaskResponseDeployOverridesTest.java`, `service/WorkerCapabilitiesTest`(추가), `controller/WorkerControllerSupportsTest`(추가), `src/test/.../TaskClaimDeployOverridesSupportIntegrationTest.java`(Testcontainers)

**Interfaces:**
- Consumes: Task 5 `assemble`, Task 2 칸.
- Produces:
  - `@Service DeployOverrideService` — `@Transactional(readOnly=true) DeployOverrides forClaim(long taskId)`: 카탈로그는 `RepoCatalogRepository.findForTask(repoCatalogRepo, t.getRepoCatalogId(), t.getGitUrl())`(Task 6·7과 같은 방법). cfg가 null이고 저장된 선택에 `connectionId != null`인 항목이 없으면 null(지금 동작). cfg가 null인데 고른 연결이 남아 있으면 `failed("레포 카탈로그에 배포 설정이 없어 저장된 배포 DB를 적용할 수 없습니다 — 배포 창에서 다시 고르세요")`. 아니면 `assemble(cfg, task.envVars, task.deployDbSelection, this::resolve)`. `resolve`는 `DbConnectionRepository.findById` + `DbSecretCipher.tryDecrypt`(실패면 빈 값). 암호 기능이 꺼졌는데 고른 자리가 있으면 `failed("DB 접속정보 기능이 꺼져 있어 배포 DB를 적용할 수 없습니다")`. **예외를 던지지 않는다** — 모든 예외를 잡아 `failed("배포 덮어쓰기 준비 실패: <예외 클래스 이름>")`(claim이 이미 커밋돼 던지면 작업이 배포중에 70분 갇힌다).
  - `List<EnvVar> DeployOverrideService.secretsForMasking(Task t)` — 고른 연결의 비밀번호를 `EnvVar("deploy-db:<slot>", pw, true)`로(Task 9가 쓴다). 실패는 빈 목록.
  - `WorkerTaskResponse.deployOverrides()` — DEPLOY 응답에만 채워짐. 다른 종류의 재구성(`withDbMcp`·`withInterviewMockups`)은 건드리지 않는다.
  - SQL 조건(필터 큐): `AND (CAST(:includeDeployOverrides AS boolean) OR NOT (t.status = :deployPending AND (t.deploy_db_selection <> '[]' OR EXISTS (SELECT 1 FROM com.repo_catalog rc WHERE rc.deploy_config IS NOT NULL AND (rc.id = t.repo_catalog_id OR (t.repo_catalog_id IS NULL AND rc.git_url = t.git_url))))))` — `findForTask`와 같은 찾기(카탈로그 id, 없으면 git URL). 배포 설정이 있는 레포는 변수 조립도 새 워커 몫이라 전부 막는다(설계 §9를 이에 맞춰 고침).

- [ ] **Step 1: 실패 시험 작성** — `DeployOverrideServiceTest`(Mockito + 실제 `DbSecretCipher("test-key","0123456789abcdef")`): `배포설정_없으면_null`, `배포설정이_사라졌는데_고른_연결이_남았으면_error`, `고른_연결을_복호화해_조립`, `복호화_실패면_error`, `암호기능_꺼짐이면_error`, `저장소가_예외를_던져도_failed로`(`findById`가 `RuntimeException` → 반환값 `error()`가 `배포 덮어쓰기 준비 실패:`로 시작), `secretsForMasking은_비밀번호를_secret으로`. `WorkerTaskResponseDeployOverridesTest`(기존 `WorkerTaskResponseCloseKeepBranchTest` 꼴): `36인자_생성자는_null`, `withDeployOverrides가_나머지_칸을_보존`. `WorkerCapabilitiesTest`: `deployOverrides가_없으면_필터큐`. `WorkerControllerSupportsTest`: `supports에_deployOverrides`. 통합 시험(CI): `배포설정_레포의_배포대기는_토큰없는_워커에_안간다`, `토큰있는_워커는_받는다`, `배포설정_없는_레포는_그대로`.
- [ ] **Step 2: 실패 확인** → 컴파일 실패.
- [ ] **Step 3: 구현** — 복호화는 claim 트랜잭션이 커밋된 뒤(`WorkerController`의 `withDbMcp`와 같은 자리). `TaskRepositoryClaimableStatusesTest`가 상태 목록 대조를 계속 통과해야 한다.
- [ ] **Step 4: 통과 확인** — 단위 시험 클래스들 + `WorkerServiceDeployTest`·`WorkerServicePrebuildClaimTest`·`TaskRepositoryClaimableStatusesTest` → PASS.
- [ ] **Step 5: Commit** — `feat: 배포 claim에 덮어쓰기 싣기·구버전 워커 가드`

### Task 9: 앱 로그에서 배포 DB 비밀번호 가리기

**Files:**
- Modify: `service/applog/AppLogStreamService.java`(L125 근처 — `SecretMasker.of(concat(task.getEnvVars(), deployOverrideService.secretsForMasking(task)), processEnv)`)
- Test: `service/applog/AppLogStreamServiceTest`(추가)

**Interfaces:**
- Consumes: Task 8 `secretsForMasking(Task)`.

- [ ] **Step 1: 실패 시험 작성** — `배포DB_비밀번호를_가린다`: 가짜 `DockerLogsLauncher`가 `"jdbc.password=Secr3t!"` 줄을 내고, `secretsForMasking`이 `Secr3t!`를 돌려주면 받은 줄에 `Secr3t!`가 없다.
- [ ] **Step 2: 실패 확인** → FAIL(가려지지 않음).
- [ ] **Step 3: 구현** — 생성자에 `DeployOverrideService` 주입.
- [ ] **Step 4: 통과 확인** — `AppLogStreamServiceTest`·`SecretMaskerTest` → PASS.
- [ ] **Step 5: Commit** — `feat: 앱 로그에서 배포 DB 비밀번호 가리기`

### Task 10: 워커 — WAR 설정 파일 사본

**Files:**
- Create: `workerdaemon/deploy/WarPropertiesOverride.java`
- Test: `workerdaemon/deploy/WarPropertiesOverrideTest.java`

**Interfaces:**
- Produces:
  - `static String readEntry(Path war, String entryPath) throws IOException` — `ZipFile`로 읽고 없으면 `NoSuchFileException(entryPath)`. 바이트는 ISO-8859-1로 문자열화(원본 그대로 다시 쓰기 위해).
  - `static String append(String original, Map<String,String> entries)` — 원본 + (끝이 줄바꿈이 아니면 `\n`) + `# Symphony 배포 덮어쓰기\n` + 항목마다 `escapeKey(k)=escapeValue(v)\n`.
  - `static void writeOverrides(Path war, List<DeployOverrides.FileOverride> files, Path overrideRoot) throws IOException` — 파일마다 `overrideRoot.resolve(path)`에 `append(readEntry(...))`를 ISO-8859-1 바이트로 씀.
  - 이스케이프: `java.util.Properties.store`와 같은 규칙(키의 공백·`=`·`:`·`#`·`!`, 값의 앞 공백, `\`, 제어문자 `\t\n\r\f`, 0x20 미만·0x7E 초과는 `\uXXXX`).

- [ ] **Step 1: 실패 시험 작성** — `원본_끝에_덧붙인다`(주석·기존 `jdbc.url` 줄 유지, 마지막 세 줄이 덮어쓰기), `Properties로_읽으면_덮어쓴_값`(`append` 결과를 `Properties.load(Reader)`로 읽어 `jdbc.url`이 새 값), `특수문자_비밀번호_왕복`(값 `"a=b:c\\d#e! f@g%h한\t"` → load 결과가 같음), `원본이_줄바꿈없이_끝나도`, `WAR에_없는_파일은_NoSuchFile`(임시 zip로), `writeOverrides가_경로대로_쓴다`.
- [ ] **Step 2: 실패 확인** → 컴파일 실패.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** → PASS.
- [ ] **Step 5: Commit** — `feat: WAR 설정 파일 덮어쓰기 사본`

### Task 11: 워커 — Tomcat 판별 · Dockerfile 블록 · 생성 규칙

**Files:**
- Modify: `workerdaemon/deploy/DockerfileSupport.java`, `workerdaemon/DeployService.java`(`CONFIG_OVERRIDE_RULE` 상수만)
- Test: `workerdaemon/deploy/DockerfileSupportTest`(추가), `workerdaemon/DeployServiceDockerfilePromptTest`(추가)

**Interfaces:**
- Produces:
  - `static Optional<String> DockerfileSupport.finalStageImage(String dockerfile)` — 마지막 `FROM`의 이미지, 앞 단계 별칭이면 그 단계의 FROM으로 거슬러 올라감. `parseBaseImages`의 토큰 처리(`--platform` 등 플래그, `AS`) 재사용.
  - `static OptionalInt DockerfileSupport.tomcatMajor(String image)` — 이름 끝 경로 조각이 `tomcat`(`tomcat`, `library/tomcat`, `docker.io/library/tomcat`)이고 태그가 숫자로 시작하면 그 앞 숫자. 태그 없음·`latest`·다른 이미지는 빈 값.
  - `static String DockerfileSupport.appendConfigOverride(String dockerfile, int tomcatMajor)` — 끝에 붙임: 주석 `# Symphony 배포 덮어쓰기 (설정 파일)`, `USER root`, `RUN mkdir -p /opt/netis-override/WEB-INF/classes && chmod -R a+rX /opt/netis-override && sed -i '<치환>' "$CATALINA_HOME/conf/context.xml"`(7이면 `VirtualWebappLoader`, 8 이상이면 `PreResources` — 문자열은 Task 1에서 통과한 것), 원래 마지막 `USER`가 있었으면 그 USER로 되돌리는 줄. BuildKit 전용 문법 금지(`DOCKERFILE_NO_BUILD_RULE`와 같은 이유). 줄바꿈은 `appendJavaLocale`과 같은 규칙.
  - `static boolean DockerfileSupport.touchesTomcatContext(String dockerfile)` — 주석 아닌 줄에 `VirtualWebappLoader`·`PreResources`·`context.xml`이 있으면 true.
  - `static final String DeployService.CONFIG_OVERRIDE_RULE` — 생성 프롬프트 끝에 덧붙일 한국어 절: 최종 런타임은 공식 `tomcat` 이미지(`tomcat:<버전>` 태그), 설정 파일 덮어쓰기 장치(엔트리포인트 스크립트, `context.xml`의 Loader·Resources, `/opt/netis-override`)를 만들지 말 것 — 워커가 붙인다.

- [ ] **Step 1: 실패 시험 작성** — `DockerfileSupportTest`: `최종단계_이미지`(두 단계 `FROM maven AS b` / `FROM tomcat:7.0.109-jdk8-openjdk-slim` → tomcat), `별칭을_거슬러_올라간다`(`FROM tomcat:8.5-jre8-temurin AS base` / `FROM base` → `tomcat:8.5-jre8-temurin`), `tomcatMajor`(7.0.109-jdk8-openjdk→7, `docker.io/library/tomcat:8.5-jre8-temurin`→8, `tomcat:9-jdk11`→9, `tomcat`→빈, `tomcat:latest`→빈, `openjdk:8`→빈), `블록은_root로_돌고_USER를_되돌린다`(`USER netis`가 있던 Dockerfile → 결과 끝 세 지시가 `USER root`·`RUN …VirtualWebappLoader…`·`USER netis`), `USER가_없으면_되돌리지_않는다`, `8이상은_PreResources`, `context_xml을_건드리면_true`. `DeployServiceDockerfilePromptTest`: `덮어쓰기_규칙_상수에_핵심문구`(`/opt/netis-override`·`tomcat` 포함).
- [ ] **Step 2: 실패 확인** → 컴파일 실패.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `DockerfileSupportTest`·`DeployServiceDockerfilePromptTest` → PASS.
- [ ] **Step 5: Commit** — `feat: Tomcat 판별·설정 덮어쓰기 Dockerfile 블록`

### Task 12: 워커 — create → cp → start

**Files:**
- Modify: `workerdaemon/deploy/DeployTarget.java`(`DeploySpec` 끝 칸 `Path overrideDir`(null 허용) + 8인자 호환 생성자), `workerdaemon/deploy/LocalDockerTarget.java`
- Test: `workerdaemon/deploy/LocalDockerTargetCreateCopyTest.java`

**Interfaces:**
- Produces:
  - `static List<String> LocalDockerTarget.buildCreateArgs(DeploySpec spec, int hostPort, boolean publicMode, String network, String baseDomain, String slugSuffix)` — `buildRunArgs`와 같고 `run -d` 대신 `create`.
  - `static List<String> LocalDockerTarget.buildCopyArgs(Path overrideDir, String containerName)` → `[docker, cp, <overrideDir 절대경로>/., <containerName>:/opt/netis-override/]` (Task 1에서 원격 cp가 된 형태).
  - `deploy`: `spec.overrideDir()==null`이면 지금 경로 그대로. 아니면 build → rm -f → 포트 → `create`(stdout = 컨테이너 id) → `cp`(실패면 `rm -f` 후 `DeployFailedException("설정 파일 사본을 컨테이너에 넣지 못했습니다", …)`) → `docker start <name>` → 같은 헬스체크·`docker update --restart`. 에코(`runEcho`)도 create·cp·start를 env 값 가린 채 남긴다.

- [ ] **Step 1: 실패 시험 작성** — `create_인자는_run과_같고_명령만_다르다`(run 인자에서 `"run","-d"`를 `"create"`로 바꾼 것과 같음), `cp_인자`(`C:\tmp\ovr` → 끝이 `…ovr/.`이고 대상 `netis-task-54:/opt/netis-override/`), `overrideDir가_없으면_spec_8인자와_같다`(호환 생성자 `overrideDir()==null`).
- [ ] **Step 2: 실패 확인** → 컴파일 실패.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 새 클래스 + `LocalDockerTargetRunArgsTest`·`LocalDockerTargetBuildArgsTest`·`DnsRegisteringDeployTargetTest` → PASS.
- [ ] **Step 5: Commit** — `feat: 설정 사본을 create·cp·start로 컨테이너에 넣기`

### Task 13: 워커 — DeployService 연결 · 지원 토큰

**Files:**
- Modify: `workerdaemon/DeployService.java`, `workerdaemon/WorkerHttpClient.java`(`SUPPORTS`에 `,deployOverrides`)
- Test: `workerdaemon/DeployServiceOverridesTest.java`, `workerdaemon/WorkerHttpClientNextTaskTest`(기대 문자열 갱신)

**Interfaces:**
- Consumes: Task 8(`task.deployOverrides()`), 10, 11, 12.
- 흐름(설계 §10, 이 순서):
  1. `o = task.deployOverrides()`; `o != null && o.error() != null` → 바로 `DeployException(o.error(), log)`(fetch 전).
  2. `repoDockerfile = Files.exists(dockerfile)`을 생성 전에 기록.
  3. `hasFiles = o != null && !o.files().isEmpty()`. hasFiles이면: `staged == null` → `"설정 파일 덮어쓰기는 미리 빌드가 켜진 레포에서만 됩니다"`, `!staged.fileName().endsWith(".war")` → `"설정 파일 덮어쓰기는 WAR 산출물에만 됩니다: <파일>"`, `repoDockerfile` → `"레포에 Dockerfile이 있어 설정 파일 덮어쓰기를 붙이지 않습니다 — 배포 DB를 '고르지 않음'으로 바꾸거나 레포 Dockerfile을 빼세요"`로 실패.
  4. 생성 프롬프트: hasFiles이면 `… + CONFIG_OVERRIDE_RULE`(재생성 프롬프트에도 같은 꼬리).
  5. 생성·로케일 뒤 hasFiles이면: `touchesTomcatContext` → 실패 `"생성한 Dockerfile이 Tomcat 설정을 직접 바꿨습니다 — 다시 배포하세요"`; `finalStageImage`→`tomcatMajor` 빈 값 → 실패 `"설정 파일 덮어쓰기는 tomcat 이미지에서만 됩니다: <이미지>"`; 아니면 `appendConfigOverride` + 로그 `[설정 덮어쓰기] tomcat <major> 블록 추가`.
  6. 사본 폴더 `Paths.get(props.reposDir()).resolveSibling("deploy-override").resolve("task-" + id)`를 비우고 `WarPropertiesOverride.writeOverrides(wt/.netis-prebuild/<staged>, o.files(), 폴더)`(`NoSuchFileException` → `"WAR에 설정 파일이 없습니다: <path>"`) — **빌드 컨텍스트 밖**. `finally`에서 `RepoCacheCleanupJob.deleteTree` + 상위 빈 폴더 정리.
  7. env 맵: `o != null`이면 `o.env()`로, 아니면 지금처럼 `task.envVars()`로.
  8. `o != null && o.summary() != null`이면 로그 `[DB 연결] ` + summary.
  9. `DeploySpec`의 `overrideDir` = hasFiles ? 폴더 : null.

- [ ] **Step 1: 실패 시험 작성** — `DeployServiceOverridesTest`(기존 `DeployServicePrebuildTest` 머리·Mockito): `error면_fetch전에_실패`(`verify(repos, never()).fetchOnly(...)`), `덮어쓰기_없으면_기존과_같다`(`deployOverrides==null` → spec env = envVars, `overrideDir()==null`), `env는_overrides것을_쓴다`, `파일이면_사본을_쓰고_spec에_폴더`(가짜 WAR zip을 staged로 두고 `target.deploy` 시점에 폴더의 사본 내용 검사 — `ArgumentCaptor`로 spec, answer 안에서 파일 읽기), `배포가_끝나면_사본폴더를_지운다`(성공·실패 둘 다), `미리빌드_없으면_실패`, `레포_Dockerfile이면_실패`, `tomcat이_아니면_실패`, `생성본이_context를_건드리면_실패`, `WAR에_파일없으면_실패`, `요약_로그줄`(`[DB 연결] 주 DB = …`, 로그에 비밀번호 없음). `WorkerHttpClientNextTaskTest`: supports 문자열이 `refRepos,fixRounds,dbWrite,mockups,baseSync,closeKeepBranch,deployOverrides`.
- [ ] **Step 2: 실패 확인** → FAIL.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 새 클래스 + `DeployServicePrebuildTest`·`DeployServiceBaseImageTest`·`DeployServiceJavaLocaleTest`·`DeployServiceDockerfilePromptTest`·`DeployServiceDockerfileTimeoutTest`·`WorkerHttpClientNextTaskTest` → PASS.
- [ ] **Step 5: Commit** — `feat: 배포에서 DB 선택·설정 덮어쓰기 적용`

### Task 14: 화면 — 배포 창

**Files:**
- Modify: `frontend/composables/deployEnvForm.ts`, `frontend/components/tasks/DeployEnvDialog.vue`
- Test: `frontend/test/deploy-env-form.spec.ts`(추가), `frontend/test/deploy-env-dialog.spec.ts`(추가)

**Interfaces:**
- Consumes: Task 6 응답 모양, Task 7 바디.
- Produces (deployEnvForm.ts):
  - 타입 `DeployDbChoiceView {id, name, dbType, scope, host, port, database}`, `DeployDbSlotView {slot, label, dbTypes: string[], targetSummary, selected: {connectionId: number|null, from: 'SAVED'|'PREVIOUS'}|null, choices: DeployDbChoiceView[]}`; `SuggestedRow.format?: string|null`, `EnvRowSource`에 `'CATALOG'`, `EnvRow.format: string|null`, `DeployEnvSuggestion.databases?: DeployDbSlotView[]`.
  - `splitFormat(format: string|null|undefined): {prefix: string; suffix: string} | null` — `{값}` 기준.
  - `stripFormat(format: string|null|undefined, value: string): string` — 값이 prefix로 시작하고 suffix로 끝나면(둘 합이 비어 있지 않을 때) 가운데만, 아니면 그대로. `rowsFromSuggestion`이 format 행의 값에 적용한다(예전 전체 값이 `https://` + 전체 값 + `.x`로 두 번 감싸 보이지 않게 — 서버 `wrapVariable`과 짝).
  - `dbModelFromSuggestion(s: DeployEnvSuggestion): Record<string, number|null>` — 자리마다 `selected?.connectionId ?? null`.
  - `toWireDbSelection(slots: DeployDbSlotView[], model: Record<string, number|null>): {slot: string; connectionId: number|null}[]`.
  - `NO_DB_LABEL = '고르지 않음(WAR 기본값)'`, `choiceCaption(c): string` → `` `${c.host}:${c.port}/${c.database} · ${c.scope === 'REPO' ? '공용' : '개인'}` ``.
- 화면: `databases`가 있으면 env 행 위에 "DB" 묶음 — 자리마다 `q-select`(`data-test="deploy-db-select"`, 옵션 첫 항목 `{label: NO_DB_LABEL, value: null}`, `emit-value map-options`), 고른 항목 아래 `choiceCaption`, `selected.from==='PREVIOUS'`면 `직전 배포 값` 배지(기존 배지 재사용). `format`이 있는 행은 `q-input`의 `prefix`/`suffix`에 `splitFormat` 결과, placeholder는 `값`. 제출 바디는 `databases.length > 0`일 때만 `dbSelection` 키를 넣는다(없으면 지금 바디 그대로).

- [ ] **Step 1: 실패 시험 작성** — `deploy-env-form.spec.ts`: `splitFormat`(`'https://{값}.x'` → `{prefix:'https://', suffix:'.x'}`, null → null), `dbModelFromSuggestion`(SAVED null·PREVIOUS 43), `toWireDbSelection`(자리 순서대로), `rowsFromSuggestion이_format을_싣는다`, `예전_전체값은_가운데만_보인다`(format `'https://{값}.x'`, 값 `'https://a.x'` → 행 값 `'a'`; 값 `'a'`는 그대로). `deploy-env-dialog.spec.ts`: `배포설정이_없으면_DB묶음이_없고_바디에_dbSelection이_없다`, `DB를_고르면_바디에_dbSelection`(`useApiMock` 두 번째 호출 바디 `{envVars: [...], dbSelection: [{slot:'main', connectionId:43}]}`), `고르지_않음을_보내면_null`, `format_행은_앞뒤_글자를_보인다`.
- [ ] **Step 2: 실패 확인** — `npx vitest run test/deploy-env-form.spec.ts test/deploy-env-dialog.spec.ts` → FAIL.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 위 두 파일 + `test/inbox-pane-bodies.spec.ts` → PASS.
- [ ] **Step 5: Commit** — `feat: 배포 창에 DB 연결 고르기·짧은 값 입력`

### Task 15: 화면 — 카탈로그 배포 설정 편집

**Files:**
- Create: `frontend/composables/deployConfig.ts`, `frontend/components/admin/DeployConfigEditor.vue`
- Modify: `frontend/pages/admin/repo-catalog.vue`(`RepoEntry.deployConfig`, 폼 묶음 "배포 설정", 수정 때 바뀌었을 때만 전송)
- Test: `frontend/test/deploy-config.spec.ts`, `frontend/test/repo-catalog-admin.spec.ts`(추가)

**Interfaces:**
- Produces (deployConfig.ts):
  - 타입 `DeployConfig {databases: DbSlot[]; variables: DeployVariable[]}`, `DbSlot {slot, label, dbTypes: DbType[], target: {kind: 'ENV'|'FILE', path: string|null, entries: Record<string,string>}}`, `DeployVariable {key, description, secret, required, format: string|null, filePath: string|null, fileEntry: string|null}` (API JSON과 같은 이름).
  - `emptyDeployConfig(): DeployConfig`, `newDbSlot(existing: DbSlot[]): DbSlot`(slot `main`, 있으면 `db2`…, label `주 DB`/`DB 2`…, dbTypes `['MARIADB']`, FILE 대상 path `WEB-INF/classes/config/jdbc_maria.properties`, entries `{'jdbc.url':'', 'jdbc.username':'{username}', 'jdbc.password':'{password}'}`), `deployConfigChanged(a, b): boolean`(JSON 비교, 빈 설정과 null은 같음), `toWireDeployConfig(c): DeployConfig`(빈 entries 행 제거).
- 편집기: DB 자리 목록(이름·표시 이름·종류 다중 선택·대상 종류 토글·경로·항목 키/값 행 추가·삭제), 변수 목록(이름·설명·비밀·필수·형식·파일 대상 선택), 자리표시자 안내 한 줄(`{host} {port} {database} {username} {password} — URL 안 계정은 {username:url} {password:url}`), "다른 레포에서 복사" `q-select`(관리 목록의 다른 레포 중 `deployConfig`가 있는 것 → 고르면 깊은 복사). 서버 400 메시지는 기존 저장 실패 알림 그대로.
- 전송: 만들기는 비어 있지 않을 때만, 수정은 `deployConfigChanged(원래, 지금)`일 때만 `deployConfig` 키(비우면 `{databases:[], variables:[]}`). `toggleEnabled`는 보내지 않는다.

- [ ] **Step 1: 실패 시험 작성** — `deploy-config.spec.ts`: `newDbSlot_기본값`, `두번째_자리는_db2`, `빈설정과_null은_같다`, `toWire가_빈_항목을_뺀다`. `repo-catalog-admin.spec.ts`: `배포설정을_안바꾸면_PUT에_키가_없다`, `자리를_추가하면_PUT에_deployConfig`, `다른레포에서_복사`.
- [ ] **Step 2: 실패 확인** → FAIL.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 위 두 파일 + `test/admin-catalog-cards-mobile.spec.ts`·`test/admin-form-dialog-mobile.spec.ts` → PASS.
- [ ] **Step 5: Commit** — `feat: 레포 카탈로그 배포 설정 편집`

### Task 16: 화면 — 작업 상세 칩 · 하이드레이션 목

**Files:**
- Modify: `frontend/composables/taskDetailTypes.ts`(`TaskResponse.deployDbSelection?: {slot: string; connectionId: number|null}[]`), `frontend/pages/tasks/[id].vue`(배포 정보 L1160-1174 근처), `frontend/components/tasks/TaskDetailMobile.vue`(배포 아코디언 L230-234 근처, `DetailTask`에 같은 칸), `frontend/scripts/hydration-smoke.mjs`(`task()` 기본값 `deployDbSelection: []`, `REPOS`에 `deployConfig: null`, `/api/tasks/<n>/deploy-env` 목 — `{rows: [], templateCount: 0, previousTaskId: null, databases: []}`)
- Test: `frontend/test/task-deploy-db-chip.spec.ts`

**Interfaces:**
- Consumes: 기존 `dbChips`·`fetchDbConnections` 결과(`[id].vue` L79-99), `taskDbChips.ts`의 `dbChipLabels`.
- Produces: `deployDbChipLabels(selection, items): string[]` (taskDbChips.ts) — `connectionId`가 있는 항목만 `dbChipLabels` 규칙(보이지 않으면 `DB #id`).
- 표시: 고른 연결이 있으면 배포 정보에 `q-chip icon="storage"` `배포 DB: <이름 · 종류>`(`data-test="task-deploy-db-chip"`), 없으면 아무것도 안 그린다.

- [ ] **Step 1: 실패 시험 작성** — `deployDbChipLabels`(null 항목 제외·모르는 id는 `DB #id`), 상세 페이지 마운트 시 칩 문구.
- [ ] **Step 2: 실패 확인** → FAIL.
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 새 spec + 기존 `test/task-detail*.spec.ts` → PASS. `npx nuxi typecheck`는 `.nuxt`가 필요하므로 생략하고 CI frontend 잡(하이드레이션 스모크 포함)에 맡긴다.
- [ ] **Step 5: Commit** — `feat: 작업 상세에 배포 DB 칩`

### Task 17: 문서 · 전체 확인

**Files:**
- Modify: `CLAUDE.md`("원격 Docker 배포 호스트" 절 끝에 "배포 DB 연결 고르기(2026-11-24)" 단락 — 카탈로그 `deploy_config`, 배포 창, claim `deployOverrides`·`supports=deployOverrides`, `/opt/netis-override`·Tomcat 7/8 블록·create→cp→start, 실패 문구, 배포 순서(API V50 → 이 PC 워커 → 프론트 → 카탈로그 7부터 입력); "자주 보는 코드" 표에 한 줄)

- [ ] **Step 1: 문서 작성**
- [ ] **Step 2: 백엔드 전체 시험** — `./gradlew test --no-daemon` → 이 PC에서 원래 실패하는 4건(메모: 심볼릭 링크 권한 3·`ProcessRunnerStreamingTest` 1) 밖의 실패 0.
- [ ] **Step 3: 프론트 전체 시험** — `npx vitest run` → 실패 0.
- [ ] **Step 4: 비밀번호 누출 확인** — `git grep -n "getPassword\|password()" -- src/main/java | grep -v Test`로 새 코드의 비밀번호 사용처가 조립·사본 쓰기·가리기 셋뿐인지 확인.
- [ ] **Step 5: Commit** — `docs: 배포 DB 연결 고르기 운영 메모`
