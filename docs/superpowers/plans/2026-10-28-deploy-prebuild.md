# 배포 전 워커 미리 빌드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레포 카탈로그에서 켠 레포는 배포 때 워커 PC가 먼저 빌드하고, 그 산출물만 원격 Docker로 보내 이미지를 만든다.

**Architecture:** API는 카탈로그에 미리 빌드 설정 4칸(V35)을 저장·검증하고, 배포 claim 바디에 `PrebuildSpec`을 싣는다. 워커는 새 `DeployPrebuilder`로 명령 실행 → 산출물 1개 수집(`.netis-prebuild/`)을 하고, `DeployService`가 그 결과로 Dockerfile 생성 프롬프트·`.dockerignore`·경고를 조립한다. 화면은 카탈로그 다이얼로그에 묶음 하나를 더한다.

**Tech Stack:** Spring Boot 3 / Java 21 / Flyway / JUnit5·AssertJ·Mockito, Nuxt 3 + Quasar / vitest.

**Spec:** `docs/superpowers/specs/2026-10-28-deploy-prebuild-design.md`

## Global Constraints

- 작업 위치: `.claude/worktrees/deploy-prebuild`(브랜치 `feature/deploy-prebuild`, `origin/main`에서). **본 체크아웃(라이브)에서 빌드·테스트 금지.** 시작할 때 본 체크아웃의 미커밋 스펙 파일과 이 계획 파일을 worktree로 옮겨 첫 커밋에 넣는다(본 체크아웃에서는 지운다).
- 백엔드 테스트(Windows): `cmd /c "cd /d <worktree> && .\gradlew.bat test --tests <클래스> --no-daemon"`. JDK `JAVA_HOME=C:/Users/mic/.jdks/jdk-21.0.12.1+1`. Testcontainers IT는 이 PC에서 skip(CI에서 돈다).
- 프론트 테스트: worktree `frontend/node_modules`를 본 체크아웃 `frontend/node_modules`로의 정션으로 두고 `npx vitest run <파일>`. worktree에서 `npm ci`/`npm install` 금지. 정리 전 정션 먼저 제거.
- 켜지 않은 레포: claim 바디 `prebuild=null`, 배포 로그·Dockerfile 생성 프롬프트가 지금과 **문자열까지 같다**.
- 미리 빌드 명령 env에서 `StackSecretEnv.NAMES`를 뺀다(대소문자 무시).
- JDK·Maven 경로는 워커 설정에만. API DB에는 도구 **이름**만.
- 사유 문구(앞부분 고정): `미리 빌드 실패 (exit=N)`, `미리 빌드 시간 초과(N분)`, `미리 빌드 도구 '<이름>'가 이 워커에 설정되지 않음`, `미리 빌드 산출물 없음: <경로>`, `미리 빌드 산출물이 여러 개: a, b, …`(최대 5개), `미리 빌드 산출물이 일반 파일이 아님: <경로>`.
- 산출물 스테이징 폴더 `.netis-prebuild`, 시간 상한 기본 20분(`DEPLOY_PREBUILD_TIMEOUT`), 로그 꼬리 8000자.
- API `app.task.deploy-stale-threshold-minutes` 기본 **70**.
- 프론트 스타일: 작은따옴표·세미콜론 없음, `npm run lint` 오류 0 유지.
- 커밋 메시지는 레포 관례(`feat(scope): 한국어 요약`), 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. 산출물 경로를 Windows식 `hm-dash\target\*.war`로 적은 경우 — 저장이 받아들여지고 워커가 같은 파일을 찾아야 한다(Task 1·3 테스트).
2. 패턴이 폴더와 파일을 함께 맞추는 경우(`hm-dash/target/hm-dash*` → `hm-dash/`(풀린 폴더) + `hm-dash.war`) — 일반 파일만 세어 1개면 성공해야 한다(Task 3 테스트).
3. 목록 화면의 활성화 토글처럼 미리 빌드 필드 없이 오는 PUT — 켜진 미리 빌드 설정이 그대로 남아야 한다(Task 1 테스트).
4. 미리 빌드가 시간 초과로 끝날 때 — 배포 로그에 그때까지의 빌드 출력 꼬리가 남아야 한다(`ProcessRunner`는 출력을 버린다)(Task 3 테스트).
5. Dockerfile을 생성했는데 생성 claude가 `.dockerignore`를 따로 만든 경우 — 워커의 `.dockerignore`가 최종본이어야 한다(Task 4 테스트).

---

### Task 1: 카탈로그 미리 빌드 설정 저장·검증 (API)

**Files:**
- Create: `src/main/resources/db/migration/V35__repo_catalog_prebuild.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/RepoCatalogEntry.java`, `dto/RepoCatalogDto.java`, `service/RepoCatalogService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/RepoCatalogServicePrebuildTest.java`(신규, `RepoCatalogServiceTest`의 목 구성 관례)

**Interfaces:**
- Produces: `RepoCatalogEntry`의 `boolean prebuildEnabled`·`String prebuildCommand`·`String prebuildArtifact`·`String prebuildToolchain`(+ getter/setter, 컬럼 `prebuild_enabled`·`prebuild_command`·`prebuild_artifact`·`prebuild_toolchain`). `RepoCatalogDto.View` 끝에 같은 이름 4개. `UpsertRequest` 끝에 `Boolean prebuildEnabled, @Size(max=4000) String prebuildCommand, @Size(max=500) String prebuildArtifact, @Size(max=50) String prebuildToolchain` + 지금의 7인자 호환 생성자(4개 null).
- Produces: `static void RepoCatalogService.validatePrebuild(String command, String artifact, String toolchain)`(package-private, 위반 시 `TaskException(BAD_REQUEST, 문구)`).

- [ ] **Step 1: 실패하는 테스트 작성** — `RepoCatalogServicePrebuildTest`:
  - `켜면_명령과_산출물이_필요하다`: 명령 `"  "` → 메시지 `미리 빌드 명령이 필요합니다`, 산출물 null → `미리 빌드 산출물 경로가 필요합니다`.
  - `명령은_한_줄`: `"mvn a\nmvn b"` → `미리 빌드 명령은 한 줄이어야 합니다`.
  - `산출물은_상대경로`: `/abs/x.war`, `\x.war`, `C:/x.war`, `a/../x.war` 각각 → `미리 빌드 산출물 경로는 레포 안 상대 경로여야 합니다`.
  - `별표는_마지막_조각만`: `*/target/x.war` → `미리 빌드 산출물 경로의 *는 마지막 이름에만 쓸 수 있습니다`; `hm-dash\target\*.war`는 통과.
  - `도구_이름_형식`: `jdk 8` → `미리 빌드 도구 이름은 영문·숫자·. _ -로 50자 이하여야 합니다`; `null`·`""`·`jdk8-maven`은 통과.
  - `끄면_검증없이_값을_보존`: `prebuildEnabled=false` + 명령 빈 값 → 예외 없음, 엔티티 `prebuildEnabled=false`, 기존 명령·산출물·도구 값은 요청 값으로 저장(빈 문자열은 null).
  - `prebuildEnabled_null이면_설정_유지`: 켜진 엔티티에 7인자 생성자 요청으로 `update` → 4개 값 그대로.
  - `켜면_trim해서_저장`: `" mvn -o package "` → `"mvn -o package"`.
- [ ] **Step 2: 실행해 실패 확인** — `--tests "*RepoCatalogServicePrebuildTest"` → 컴파일 실패(필드 없음).
- [ ] **Step 3: 구현**
  - V35: 스펙 §4.1 SQL 그대로.
  - 엔티티·DTO 필드, `View.of` 두 개 모두 4개 값 전달.
  - `applyPrebuild(e, req)`(private static, `applyArtifact` 옆): `req.prebuildEnabled()==null`이면 return. `true`면 `validatePrebuild` 후 trim 값 저장, `false`면 검증 없이 `blankToNull` 값 저장. `create`·`update`가 `applyArtifact` 다음에 호출.
  - `validatePrebuild` 순서: 명령 필수 → 줄바꿈(`\r`/`\n`) → 산출물 필수 → 상대 경로(`/`·`\` 둘 다 구분자, 선행 구분자·`X:` 드라이브·`..` 조각 금지) → `*` 위치 → 도구 이름 `^[A-Za-z0-9._-]{1,50}$`(비면 통과).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `--tests "*RepoCatalogService*"` 전부 PASS.
- [ ] **Step 5: 커밋** — `feat(catalog): 레포별 배포 전 미리 빌드 설정(V35)·검증`

### Task 2: 배포 claim에 미리 빌드 지시 + `배포중` 판정 70분 (API)

**Files:**
- Modify: `dto/WorkerTaskResponse.java`, `service/WorkerService.java`(배포 claim 분기 `from == TaskStatus.DEPLOY_PENDING`), `service/StaleTaskRecoveryJob.java`(`@Value` 기본값), `src/main/resources/application.yml`(`deploy-stale-threshold-minutes: 70` + 주석 "워커 dockerfile 10m + 미리 빌드 20m + build 30m + 여유 — application-worker.yml deploy 상한과 같이 볼 것")
- Test: `src/test/java/com/hamonsoft/netismaker/service/WorkerServicePrebuildClaimTest.java`(신규), `dto/WorkerTaskResponseRepoTest.java`(JSON 왕복 1건 추가)

**Interfaces:**
- Consumes: Task 1의 `RepoCatalogEntry` 게터.
- Produces: `WorkerTaskResponse.PrebuildSpec(String command, String artifact, String toolchain)` record, 레코드 끝 컴포넌트 `PrebuildSpec prebuild`(지금 정규 생성자 인자 목록의 호환 생성자 추가 — `prebuild=null`; 기존 21개 호출부 수정 없음). `static WorkerTaskResponse forDeploy(Task t, PrebuildSpec prebuild)`, 기존 `forDeploy(Task t)`는 `forDeploy(t, null)`.

- [ ] **Step 1: 실패하는 테스트 작성** — `WorkerServicePrebuildClaimTest`(WorkerService 배포 claim 경로를 기존 claim 테스트의 목 구성으로):
  - `켜진_카탈로그면_spec을_싣는다`: 카탈로그 `prebuildEnabled=true, command "mvn -o package", artifact "t/*.war", toolchain "jdk8-maven"` → 응답 `prebuild()`가 같은 3값.
  - `꺼진_카탈로그면_null`.
  - `비활성_카탈로그도_싣는다`: `enabled=false` + 켜짐 → spec 있음.
  - `카탈로그_id없음_또는_삭제면_null`: `repoCatalogId=null` → null, `findById` 빈 값 → null(경고 로그 `배포 claim: 카탈로그 항목 없음(id=9) — 미리 빌드 끔`).
  - `WorkerTaskResponseRepoTest`: `prebuild` 있는 응답의 Jackson 직렬화·역직렬화 왕복, `prebuild` 키 없는 JSON 역직렬화 → `null`.
  - `StaleTaskRecoveryJobTest`는 리플렉션으로 값을 넣으므로 그대로 둔다. 대신 `application.yml`을 SnakeYAML로 읽어 `app.task.deploy-stale-threshold-minutes == 70`을 확인하는 테스트 1건(`DeployServiceDockerfilePromptTest`의 yml 읽기 방식).
- [ ] **Step 2: 실패 확인** — 해당 클래스들 컴파일 실패/FAIL.
- [ ] **Step 3: 구현** — `WorkerService`에 `private PrebuildSpec prebuildFor(Task t)`(이미 주입된 `repoCatalogRepo` 사용), 배포 분기에서 `forDeploy(t, prebuildFor(t))`. 스펙 §3 "비활성 카탈로그" 규칙.
- [ ] **Step 4: 통과 확인** — 새 테스트 + `--tests "*WorkerTaskResponse*" --tests "*WorkerService*" --tests "*StaleTaskRecovery*"` PASS.
- [ ] **Step 5: 커밋** — `feat(worker-api): 배포 claim에 미리 빌드 지시, 배포중 판정 70분`

### Task 3: 워커 미리 빌드 실행기 (`DeployPrebuilder`)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/workerdaemon/PrebuildProperties.java`, `workerdaemon/DeployPrebuilder.java`
- Modify: `workerdaemon/ProcessRunner.java`(스트리밍 + env 오버로드), `NetisMakerApplication.java`(`@EnableConfigurationProperties`에 `PrebuildProperties.class`), `src/main/resources/application-worker.yml`(스펙 §5.1 블록을 `deploy:` 아래에 — `prebuild:` 키, 주석 포함, 그리고 `build-timeout` 주석에 "API `app.task.deploy-stale-threshold-minutes`(70)보다 생성+미리 빌드+build 합이 작게 둘 것" 한 줄)
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployPrebuilderTest.java`

**Interfaces:**
- Consumes: Task 2의 `WorkerTaskResponse.PrebuildSpec`.
- Produces:
  - `@ConfigurationProperties(prefix = "netis-maker.worker.deploy.prebuild") record PrebuildProperties(Duration timeout, Map<String, Toolchain> toolchains)` — 기본 `timeout` 20분, `toolchains` 빈 맵; `record Toolchain(String javaHome, String path)`; `Optional<Toolchain> configured(String name)`(`javaHome`이 비면 empty).
  - `ProcessRunner.runStreaming(File workingDir, List<String> command, Map<String,String> extraEnv, Set<String> removeEnv, long timeoutSeconds, Consumer<String> onLine)`.
  - `@Component @Profile("worker") class DeployPrebuilder`:
    - `static final String STAGE_DIR = ".netis-prebuild"`; `static final String DOCKERIGNORE = "*\n!.netis-prebuild/\n!.netis-prebuild/**\n"`.
    - `static List<String> shellCommand(String command, boolean windows)` → `["cmd.exe","/d","/s","/c",command]` / `["sh","-c",command]`.
    - `static Map<String,String> toolchainEnv(Toolchain tc, String currentPath, String pathSeparator)` → `JAVA_HOME`=javaHome, `PATH`=`javaHome/bin` + `tc.path`의 조각(구분자 `;`·`:` 중 `pathSeparator`) + currentPath.
    - `static Path resolveArtifact(Path root, String pattern) throws PrebuildException` — `\`를 `/`로 바꿔 상위 폴더 + 마지막 조각 glob(`FileSystems.getDefault().getPathMatcher("glob:"+name)`), 링크를 따라가지 않고 **일반 파일만** 센다.
    - `static String promptSection(String stagedName, String artifactPattern)` — 스펙 §5.3 본문 그대로(`<이름>`·`<산출물 경로>` 치환).
    - `static boolean dockerfileUsesStage(String dockerfileText)` — `.netis-prebuild` 포함 여부.
    - `Staged run(WorkerTaskResponse.PrebuildSpec spec, Path worktree, Consumer<String> sink) throws PrebuildException` — `record Staged(String fileName, long sizeBytes, String sourceRelative)`.
    - `static class PrebuildException extends Exception`(`String getLogTail()`).

- [ ] **Step 1: 실패하는 테스트 작성** — `DeployPrebuilderTest`(@TempDir):
  - `shellCommand_OS별`.
  - `toolchainEnv_PATH_앞붙임`: `Toolchain("C:/jdk8","C:/mvn/bin")`, current `"X"`, sep `";"` → PATH `"C:/jdk8/bin;C:/mvn/bin;X"`, JAVA_HOME `"C:/jdk8"`.
  - `configured_javaHome_빈값은_없음`.
  - `resolveArtifact_하나`(`t/a.war`), `역슬래시_패턴`(`t\*.war`), `없음`→ 메시지 `미리 빌드 산출물 없음: t/*.war`, `여러개`(6개 → 이름 5개 + `…`), `폴더와_파일이_함께_맞으면_파일만`(`t/hm-dash/` 폴더 + `t/hm-dash.war`, 패턴 `t/hm-dash*` → war), `폴더만_맞으면`→ `미리 빌드 산출물이 일반 파일이 아님: t/hm-dash*`.
  - `promptSection`이 `.netis-prebuild/hm-dash.war`·`hm-dash/target/*.war`·`ROOT.war`·`jar`를 담는다.
  - `run_성공`(OS에 맞는 `shellCommand`로 `t/a.war` 파일을 만드는 echo 명령) → `.netis-prebuild/a.war` 존재, sink에 `[미리 빌드] $ ` 줄·`[미리 빌드 산출물] t/a.war (`… 줄.
  - `run_비밀env_제거`: 프로세스 env에 넣을 수 없으므로 `run`이 `ProcessRunner.runStreaming`에 넘기는 removeEnv를 패키지 private 훅(`static Set<String> removedEnv()` = `Set.copyOf(StackSecretEnv.NAMES)`)으로 확인.
  - `run_실패_exit`: exit 3 명령 → `미리 빌드 실패 (exit=3)`, `getLogTail()`에 명령 출력 포함.
  - `run_시간초과`: timeout 2초 + 출력 후 sleep하는 명령 → `미리 빌드 시간 초과(` 시작, `getLogTail()`에 sleep 전 출력 줄 포함(Review Focus 4).
  - `run_도구_미설정`: spec toolchain `"jdk8-maven"`, 설정 없음 → `미리 빌드 도구 'jdk8-maven'가 이 워커에 설정되지 않음`.
- [ ] **Step 2: 실패 확인** — 컴파일 실패.
- [ ] **Step 3: 구현** — `run`: 도구 확인 → sink `[미리 빌드] $ <명령> (도구=<이름|워커 env>)` → `runStreaming`(onLine이 sink + 자체 버퍼에 쌓음, removeEnv=`StackSecretEnv.NAMES`, extraEnv=`toolchainEnv(…, System.getenv("PATH"), File.pathSeparator)` 또는 빈 맵) → IOException 메시지가 `process timeout`으로 시작하면 시간 초과 사유 → exit≠0 사유 → `resolveArtifact` → `STAGE_DIR`로 복사 → sink `[미리 빌드 산출물] <상대경로> (<MB 한 자리>MB) → .netis-prebuild/<이름>`. 꼬리는 버퍼 끝 8000자. 기동 시 한 번(`@PostConstruct`) 설정된 툴체인만 `미리 빌드 도구: jdk8-maven(java=C:\…)` 형식으로 INFO 로그(없으면 `미리 빌드 도구: 없음`).
- [ ] **Step 4: 통과 확인** — `--tests "*DeployPrebuilderTest" --tests "*ProcessRunner*"` PASS.
- [ ] **Step 5: 커밋** — `feat(deploy): 워커 미리 빌드 실행기·툴체인 설정`

### Task 4: 배포 흐름에 미리 빌드 연결 (`DeployService`)

**Files:**
- Modify: `workerdaemon/DeployService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/workerdaemon/DeployServicePrebuildTest.java`(신규, `DeployServiceDockerfileTimeoutTest`의 목 구성), 기존 `DeployServiceDockerfilePromptTest`·`DeployServiceDockerfileTimeoutTest`·`WorktreeImmediateDiscardTest`는 **수정 없이** 통과해야 한다.

**Interfaces:**
- Consumes: Task 3 `DeployPrebuilder`(`run`, `promptSection`, `dockerfileUsesStage`, `DOCKERIGNORE`, `PrebuildException`), Task 2 `task.prebuild()`.
- Produces: 생성자 `@Autowired DeployService(WorkerProperties, GitRepoCache, WorktreeService, ClaudeExecAdapter, DeployTarget, DeployPrebuilder)` + 기존 5인자 생성자(미리 빌드 없음으로 위임 — 테스트 호환). `static String buildDockerfilePrompt(String tpl, String githubRepo, String branch, String prebuildSection)` — `prebuildSection`이 null/빈 값이면 기존 3인자 결과와 같은 문자열, 아니면 템플릿 뒤·`DOCKERFILE_NO_BUILD_RULE` 앞에 삽입.

- [ ] **Step 1: 실패하는 테스트 작성** — `DeployServicePrebuildTest`(`DeployPrebuilder`·`ClaudeExecAdapter`·`DeployTarget` 목):
  - `prebuild_null이면_예전과_같다`: prebuilder 호출 없음, `.dockerignore` 안 만듦, 로그에 `[미리 빌드` 없음.
  - `생성한_Dockerfile이면_dockerignore를_나중에_쓴다`: claude 목이 Dockerfile과 `.dockerignore`(`node_modules`)를 둘 다 쓰게 → 최종 `.dockerignore` == `DeployPrebuilder.DOCKERIGNORE`, 로그 `[빌드 컨텍스트] .dockerignore를 미리 빌드 산출물만 보내도록 씀 (레포의 .dockerignore 교체)`(Review Focus 5). claude에 넘긴 프롬프트가 `promptSection` 결과를 포함하고 `DOCKERFILE_NO_BUILD_RULE`로 끝난다.
  - `레포_Dockerfile이면_dockerignore를_건드리지_않는다`: worktree에 Dockerfile(`.netis-prebuild` 없음)·`.dockerignore` 미리 둠 → `.dockerignore` 원본 그대로, 로그에 `[기존 Dockerfile 사용]`과 `[경고] Dockerfile이 .netis-prebuild/를 쓰지 않음 — 미리 빌드 산출물이 이미지에 들어가지 않을 수 있음`.
  - `미리_빌드_실패면_build하지_않는다`: prebuilder가 `PrebuildException("미리 빌드 실패 (exit=1)", "tail")` → `DeployException` 메시지 그대로, `getDeployLog()`가 `tail` 포함, `target.deploy` 호출 없음.
  - `buildDockerfilePrompt_4인자_빈_섹션은_3인자와_같다`.
- [ ] **Step 2: 실패 확인**.
- [ ] **Step 3: 구현** — 스펙 §5.2 순서: worktree → `task.prebuild()!=null`이면 `prebuilder.run(spec, wt, line -> { log 누적; logSink.accept(line) })` → Dockerfile 없으면 생성(섹션 포함) 후 `.dockerignore` 쓰기 → 있으면 `[기존 Dockerfile 사용]` → 미리 빌드였다면 `dockerfileUsesStage` 경고 → 이후 기존. `PrebuildException`은 `DeployException(msg, log + tail)`.
- [ ] **Step 4: 통과 확인** — `--tests "*DeployService*" --tests "*WorktreeImmediateDiscard*" --tests "*WorkerMainLoopDeployEnv*"` PASS.
- [ ] **Step 5: 커밋** — `feat(deploy): 배포 흐름에 미리 빌드·빌드 컨텍스트 제한 연결`

### Task 5: 카탈로그 화면 (프론트)

**Files:**
- Modify: `frontend/pages/admin/repo-catalog.vue`(타입·폼·로드·바디·다이얼로그 묶음·목록/카드 칩 — 카드는 `AdminCatalogCard` 사용처의 슬롯/props 관례대로)
- Test: `frontend/test/repo-catalog-admin.spec.ts`(케이스 추가)

**Interfaces:**
- Consumes: Task 1 API 필드 `prebuildEnabled`·`prebuildCommand`·`prebuildArtifact`·`prebuildToolchain`.
- Produces: data-test `form-prebuild-enabled`·`form-prebuild-command`·`form-prebuild-artifact`·`form-prebuild-toolchain`·`catalog-prebuild-chip`.

- [ ] **Step 1: 실패하는 테스트 작성** — `repo-catalog-admin.spec.ts`:
  - 토글 전에는 `form-prebuild-command`가 없고, 토글 뒤 세 입력이 보인다.
  - 값을 넣고 저장 → 요청 바디 `expect.objectContaining({ prebuildEnabled: true, prebuildCommand: 'mvn -B -o package', prebuildArtifact: 'hm-dash/target/*.war', prebuildToolchain: 'jdk8-maven' })`; 도구 비우면 `prebuildToolchain: null`.
  - 켜진 항목 목록에 `catalog-prebuild-chip` 텍스트 `미리 빌드`.
  - 수정 다이얼로그가 기존 값을 채운다.
- [ ] **Step 2: 실패 확인** — `npx vitest run test/repo-catalog-admin.spec.ts` FAIL.
- [ ] **Step 3: 구현** — 스펙 §6 문구 그대로(토글 라벨 `배포 전 워커에서 미리 빌드`, 캡션 2개, placeholder `hm-dash/target/*.war`·`jdk8-maven`). 산출물 묶음 아래.
- [ ] **Step 4: 통과 확인** — 같은 파일 + `test/admin-catalog-cards-mobile.spec.ts` PASS, `npm run lint` 오류 0.
- [ ] **Step 5: 커밋** — `feat(frontend): 카탈로그 미리 빌드 설정 묶음`

### Task 6: 문서·전체 검증

**Files:**
- Modify: `CLAUDE.md`("자주 보는 코드" 표에 한 행: 미리 빌드 = `workerdaemon/DeployPrebuilder.java`·`PrebuildProperties.java`·`DeployService`·`RepoCatalogService.validatePrebuild`·V35·`frontend/pages/admin/repo-catalog.vue`; "원격 Docker 배포 호스트" 절에 미리 빌드 요약 3줄 — 켜는 곳·툴체인 env·`배포중` 70분·배포 순서 스펙 §8)
- [ ] **Step 1:** 백엔드 전체 `.\gradlew.bat test --no-daemon` — 이 PC의 기존 건너뜀 외 실패 0(실패 시 단독 재실행으로 `ProcessRunnerTimeoutTest` 간헐 실패 구분).
- [ ] **Step 2:** 프론트 `npx vitest run` 전체 PASS, `npm run lint` 오류 0.
- [ ] **Step 3: 커밋** — `docs: 미리 빌드 운영 메모`
