# 배포된 앱 로그 실시간 보기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업 상세(데스크톱·모바일)에서 배포된 컨테이너의 최근 로그 500줄과 새로 찍히는 줄을 실시간으로 본다. 오류 줄 강조, 따라가기, 멈춤, 복사, 내려받기를 제공한다. 배포 중 빌드 로그는 모바일에도 보이게 한다.

**Architecture:** 세 층으로 나눈다.
- **API(Task 1~6):** 연결 하나마다 `docker logs --tail 500 --timestamps --follow netis-task-{id}` 프로세스를 하나 띄운다. 두 파이프를 함께 읽고, 비밀을 가린 줄을 SSE `lines`·`info`·`end`로 묶어 보낸다. 판정·상한·끝내기는 한 서비스가 맡고, 정리는 정확히 한 번만 한다. docker 실행은 인터페이스(`DockerLogsLauncher`) 뒤에 둔다. 그래서 시험은 가짜 실행기로 돌고, 실제 원격 데몬은 Task 12 라이브 확인에서만 본다.
- **Nitro 서버 라우트(Task 7):** `/api/**` 밖 경로인 `/stream/tasks/{id}/app-logs`가 API로 fetch한다. 브라우저 연결이 닫히면 `AbortController`로 upstream을 끊는다.
- **프론트(Task 8~11):** 순수 함수 → EventSource 상태 기계 → 카드 → 화면 배치 순서로 쌓는다.
- Task 12는 문서·배포 순서·라이브 확인이다.

**Tech Stack:** Java 21 · Spring Boot 3.4.1(`SseEmitter`, `ProcessBuilder`) · Mockito/JUnit 5 · Awaitility(`spring-boot-starter-test` 3.4.1 pom에 포함) · Nuxt 3.21 / nitropack 2.13 / h3 1.15 + Quasar · vitest 3(happy-dom)

**Spec:** `docs/superpowers/specs/2026-11-03-deploy-app-log-stream-design.md` 전부를 구현한다. 이 설계와 이 계획은 `origin/main`에 없다(로컬 `feat/task-type-db-write`의 설계 커밋 `b054729`에만 있음). 그래서 Task 1 Step 0에서 새 브랜치로 옮겨 커밋한다.
- **줄 번호 기준:** 이 계획의 코드 줄 번호는 **선행 기능 적용 전** 기준이다.
  - 이 worktree의 `src`·`frontend`는 PR #122 head `c87f82d`와 같다(`git diff c87f82d..HEAD -- src frontend`가 비어 있음). 그래서 #122는 이미 반영돼 있고, 작업 1~3(작업 타입·간단 인터뷰·인터뷰 목업)은 **적용 전** 번호다.
  - 작업 1~3이 먼저 병합되면 다음 파일의 줄이 밀린다. 이 파일들에서는 줄 번호 대신 이름(함수·data-test·yml 키)으로 찾는다.
    - `F/pages/tasks/[id].vue`, `F/components/tasks/TaskDetailMobile.vue`, `F/scripts/hydration-smoke.mjs`, `R/application.yml`, `CLAUDE.md`
    - 참고(병합 순서를 정할 때만 보고, 이 브랜치에는 없을 수 있다): `docs/superpowers/plans/2026-11-03-task-type-db-write.md:329-330,1150-1151,1264-1266`, `docs/superpowers/plans/2026-11-03-interview-superpowers-toggle.md:505-507`
- **설계 줄 번호 정정:**
  - 빌드 로그 구독은 설계가 적은 `TaskController.java:273-279`가 아니라 `:278-284`에 있다.
  - 컨테이너 이름을 만드는 곳은 `DeployTarget.java:44`(Javadoc 예시)가 아니라 `M/workerdaemon/DeployService.java:171`이다. 접두는 `M/workerdaemon/deploy/DockerGcPlanner.java:23`의 `public static final String OWN_PREFIX = "netis-task-"`이다.

경로 줄임: `M/`=`src/main/java/com/hamonsoft/netismaker/`, `T/`=`src/test/java/com/hamonsoft/netismaker/`, `R/`=`src/main/resources/`, `F/`=`frontend/`, `<W>`=이 기능 브랜치 worktree 루트의 절대 경로, `NM\`=라이브 `C:\Users\mic\NetisMaker\commanCenter\frontend\node_modules`(근거 읽기용).

## Global Constraints

- **선행:** PR #122가 main에 병합된 뒤 `origin/main`에서 `feat/deploy-app-log-stream` 브랜치를 만들어 시작한다. 병합 전이면 시작하지 않는다.
  - **설계와 다름:** 설계(선행 줄)는 "#122 위의 독립 브랜치로 진행할 수 있다"(Claude 판단)고 했다. #122는 아직 CI 대기 중이라, 그 위에 쌓으면 이 PR 차이에 #122 커밋이 섞이고 #122가 바뀔 때마다 rebase해야 한다. 그래서 병합 뒤로 미룬다.
  - 작업 1~3 PR과는 독립이고, 병합 순서는 그때 정한다. 먼저 병합된 쪽이 있으면 rebase하고, 위 "줄 번호 기준"의 파일을 이름으로 맞춘다.
  - #122 계획의 Produces 이름을 그대로 쓴다(`docs/superpowers/plans/2026-11-03-fix-request-same-branch.md:52,106,162`).
    - `TaskStatus.FIX_REVIEW`
    - `Task.getActiveFixRequestId()`
    - `TaskResponse.FixView(Integer activeRound, String activeMode, int approvedRounds, int maxRounds)`와 상세 응답의 `fix`
    - `TaskRepository.findDeployReconcilable()`의 `FIX_REVIEW`·`activeFixRequestId IS NOT NULL` 조건
- **범위:** API + 프론트(Nitro 포함)만 바꾼다. 마이그레이션은 없고, 인터뷰 서비스도 바꾸지 않는다.
  - 워커 코드는 `CloseNoteWriter`가 공용 마스킹 유틸을 쓰도록 바뀌는 것 하나뿐이다(동작 동일, Task 1).
    - 이 PC 워커는 jar가 아니라 라이브 체크아웃의 `gradlew bootRun`이라(`scripts/start-public.ps1:328`) 다음 워커 재기동 때 반영된다.
    - 원격 워커 두 대는 종료(CLOSE)를 받지 않으므로(`WORKER_KINDS=IMPLEMENTATION,DESIGN`) 무관하다.
- **허용 상태:** API와 프론트 모두 같은 규칙을 쓴다. 다음 세 조건을 모두 만족해야 한다.
  1. 상태가 `DEPLOYED, DEPLOY_LOST, FIX_REVIEW, CLOSE_REVIEW, CLOSE_PENDING, CLOSING, CLOSE_FAILED` 중 하나이거나, 열린 회차가 있다.
  2. 상태가 `DEPLOY_PENDING, DEPLOYING, UNDEPLOY_PENDING, UNDEPLOYING`가 아니다.
  3. 배포 기록이 있다.
  - API에서 열린 회차는 `activeFixRequestId != null`, 배포 기록은 `deployUrl != null || deployedAt != null`이다(`M/entity/Task.java:154,258,277`).
  - 프론트는 `activeFixRequestId`와 배포 시각만 있는 경우를 볼 수 없다. 그래서 열린 회차는 `fix?.activeRound != null`, 배포 기록은 `!!(deployment?.deployUrl || deployment?.deployedAt)`로 본다(`M/dto/TaskResponse.java:319-329`, `M/service/TaskFixService.java:193-204`).
- **docker 명령:** `[<찾은 docker 절대 경로>, "logs", "--tail", "<tail-lines>", "--timestamps", "--follow", "netis-task-<id>"]`
- **SSE 형식:**
  - `lines` `{"lines":[{"ts":"…","stream":"out"|"err","text":"…"}]}`
  - `info` `{"message":"…"}`
  - `end` `{"reason":"…","message":"…"}` — `message`는 늘 싣는다.
  - 주석 핑 `:ping`은 접속 직후 한 번 보내고, 그 뒤 `ping-seconds`마다 보낸다.
  - HTTP 상태 코드는 401(토큰)·403·404(`getForView`)에만 쓰고, 본문은 없다. 나머지 거절과 종료는 200 응답 안에서 `end`를 보낸 뒤 닫는다.
- **`end` 사유와 문구** (그대로 쓴다):

  | reason | message |
  |---|---|
  | `not-allowed-status` | `지금 상태에서는 앱 로그를 볼 수 없습니다` |
  | `too-many` | `동시에 볼 수 있는 앱 로그 수를 넘었습니다` |
  | `disabled` | `앱 로그 기능이 꺼져 있습니다(docker 없음 또는 설정 꺼짐)` |
  | `no-container` | `컨테이너가 없어 로그를 볼 수 없습니다` |
  | `docker-error` | `docker 로그를 읽지 못했습니다` |
  | `time-limit` | `{max-duration-minutes}분이 지나 연결을 닫았습니다` |
  | `container-stopped` | `컨테이너가 멈췄습니다` |

- **설정 `app.app-log.*`:**
  - `enabled` `${APP_LOG_ENABLED:true}`, `docker-path` `${APP_LOG_DOCKER_PATH:}`
  - `tail-lines` 500, `max-streams` 6, `max-streams-per-task` 3, `max-duration-minutes` 30
  - `ping-seconds` 10, `batch-millis` 300, `batch-lines` 200, `max-line-chars` 4000
  - 빌드 로그 핑은 `app.deploy-log.sse-ping-interval-ms` 20000이다.
- **줄 처리 순서:**
  0. 끝 `\r`과 ANSI 색 코드(`\u001b\[[0-9;]*[A-Za-z]`)를 지운다(Task 3 보탬).
  1. 작업 env의 `secret=true`이면서 4자 이상인 값을 `••••`로 바꾼다.
  2. API 프로세스 env 중 `StackSecretEnv.NAMES` 값이 비어 있지 않으면 `••••`로 바꾼다.
  3. `GitRemotes.mask`로 `://***@`를 만든다.
  4. 맨 토큰을 `***`로 바꾼다.
  5. **가린 뒤에** 4000자로 자르고 `…(잘림)`을 붙인다.
- **docker는 시험에서 실행하지 않는다.**
  - 단위 시험은 가짜 `DockerLogsLauncher`를 쓴다.
  - 통합 시험 컨텍스트는 `TestcontainersConfig`에 `app.app-log.enabled=false`를 더해 막는다.
  - 실제 원격 데몬은 Task 12의 라이브 확인에서만 본다. 그때 API PC와 10.1.1.75 양쪽에 프로세스가 남지 않는지 확인한다.
- **화면:**
  - `v-html`을 쓰지 않는다.
  - Quasar 반응형 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`)을 클래스로 쓰지 않는다.
  - 다이얼로그를 쓰지 않는다.
  - `defineProps<T>()`의 T는 `~/`에서 가져오지 않는다.
  - `shallowRef`·`onBeforeUnmount` 등 시험 전역에 없는 vue 함수는 `'vue'`에서 직접 import한다(vue 전역은 `F/test/setup.ts:37-45`의 9개뿐).
  - `end`와 `onerror` 처리기는 맨 먼저 `es.close()`를 부른다.
- **시험:**
  - 이 기능 worktree에서만 돌린다. `gradlew --stop`은 금지다. 라이브 API·이 PC 워커가 Gradle 데몬의 자식 `bootRun`으로 돈다(`scripts/start-public.ps1:247,328`).
  - 단위 시험은 `.\gradlew.bat test --tests <FQCN>`으로 돌린다. Testcontainers 시험은 CI만 돌린다(이 PC에 Docker 없음). 이 계획의 CI 시험은 기존 `TaskApiIntegrationTest`(게이트 `:36-41`)에 더하고, 새 initializer는 만들지 않는다.
  - `JAVA_HOME`이 비어 있으면 `$env:JAVA_HOME='C:/Users/mic/.jdks/jdk-21.0.12.1+1'`로 둔다.
  - env만 바꿔 다시 돌릴 때는 `cleanTest test`를 쓴다.
  - **frontend `npm ci` 전 정션 확인(필수):** 이 PC의 다른 worktree는 `frontend\node_modules`를 라이브 체크아웃으로 정션해 둔다. npm 11의 `npm ci`는 그 안의 항목을 하나씩 지우므로(`C:\Users\mic\.local\node\node_modules\npm\lib\commands\ci.js:97-104`) 정션이 걸린 채 돌리면 라이브 프론트의 `node_modules`가 지워진다. 그래서 `npm ci` 바로 앞에 늘 이것을 돌린다.
    - `$nm='<W>\frontend\node_modules'; if ((Get-Item -LiteralPath $nm -EA SilentlyContinue).LinkType -eq 'Junction') { cmd /c rmdir $nm }`
    - `Remove-Item -Recurse`로 걷지 않는다. 링크 대상(라이브 체크아웃)까지 지운다.
  - lint 기준선: frontend `npm ci` 직후 `npm run lint` 경고 수를 PR 초안에 적는다. 새 코드로 경고를 늘리지 않는다.
  - frontend spec에는 `// @vitest-environment node`를 쓰지 않는다. `F/test/setup.ts:3`의 Quasar import가 `window is not defined`로 죽는다.
- **명령 위치:** Step 명령은 `Set-Location <W>\…`(절대 경로)로 시작한다. PowerShell 5.1이므로 `&&` 대신 `;`를 쓴다. `[id]`가 든 경로는 `-LiteralPath`로 다룬다.
- **커밋:** 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. **로그가 조용한 컨테이너를 보다가 Cloudflare 경유로 카드를 접거나 탭을 닫는 경우.**
   - Nitro는 1초 안에 upstream을 끊어야 한다.
   - API는 다음 쓰기(핑)가 실패하는 즉시 프로세스 트리를 끝내고 자리를 반납해야 한다.
   - 시험: Task 7 `브라우저가 끊으면 1초 안에 upstream을 abort`, Task 5 `핑_전송이_실패하면_트리를_끝내고_자리를_반납`. 라이브 확인은 Task 12.
2. **끝나는 길이 겹치는 경우.** 예를 들어 컨테이너가 멈추는 순간 브라우저도 닫히거나, 시간 상한과 전송 실패가 함께 일어난다.
   - `end`는 많아야 한 번 보낸다.
   - 상한 카운터는 0으로 돌아와야 하고, 다음 접속이 막히면 안 된다.
   - 시험: Task 5 `정리는_한번만`.
3. **비밀 값이 까다로운 경우.** 정규식 특수문자(`$`·`\`·`(`)가 들어 있거나, 한 값이 다른 값을 포함하거나, 여러 줄인 값이다.
   - 문자 그대로, 긴 값부터, 줄마다 가려야 한다.
   - 시험: Task 3 `정규식_특수문자도_문자그대로`·`긴_값부터`·`여러_줄_비밀은_줄마다`.
4. **로그 양이 극단적이거나 받는 쪽이 느린 경우.** 줄바꿈 없이 수 MB를 한 줄로 찍는 앱(덤프, minified JSON), 초당 수천 줄을 찍는 앱, 그것을 폰·Cloudflare 너머에서 느리게 받는 화면이다.
   - API·Nitro 모두 읽기 메모리가 상한 안에 머물러야 한다. 느린 소비자는 Nitro에서 drain을 기다리고, API에서는 그 스트림의 전송 스레드만 멈춘다. 다른 스트림의 핑·flush와 시간 상한은 제때 돈다.
   - 줄은 4000자 + `…(잘림)`으로 자르고, 자른 경계에 걸친 비밀도 가려야 한다.
   - 이벤트 하나는 200줄 이하여야 한다.
   - 시험: Task 3 `가린_뒤에_자른다`, Task 5 `거대한_한줄도_자르고_가린다`·`줄은_200줄씩_묶고_순서를_지킨다`·`막힌_sink는_다른_스트림의_핑과_줄을_막지_않는다`·`막힌_sink여도_시간_상한이면_트리를_끝내고_자리를_반납`, Task 7 `클라이언트가 읽지 않으면 drain 전까지 upstream을 더 읽지 않는다`.
5. **다시 연결이 불어나는 경우.** `end` 뒤 브라우저의 자동 재연결, 숨은 탭에서 맞은 `time-limit`, 만료된 토큰(`F/stores/auth.ts:41`, 갱신 액션 없음)으로 계속 나는 401, 불안정한 망에서 겹친 재연결이 부른 `too-many`다.
   - EventSource 수는 정해진 횟수 안에서 멈춰야 한다.
   - 시험: Task 9 `end(not-allowed-status)`·`숨은 탭`·`container-stopped`·`too-many`·`onerror` 다섯 시험.

---

# Part A — API (§4)

### Task 1: 공용 비밀 가리기·프로세스 트리 종료 유틸

**Files:**
- Create: `M/util/SecretMasks.java`, `M/util/ProcessTrees.java`
- Create(Step 0): `docs/superpowers/specs/2026-11-03-deploy-app-log-stream-design.md`, `docs/superpowers/plans/2026-11-03-deploy-app-log-stream.md`(이 계획)
- Modify: `M/workerdaemon/CloseNoteWriter.java:36-38`(`BARE_TOKENS` 삭제), `:107-110`(`maskSecrets` 위임)
- `M/service/render/SlideRenderer.java`는 바꾸지 않는다. 설계가 요구하지 않고, 위임하면 라이브 렌더링의 종료 대기 의미(프로세스마다 5초)가 바뀐다. `ProcessTrees`는 앱 로그만 쓴다.
- Test: `T/util/SecretMasksTest.java`, `T/util/ProcessTreesTest.java`
  - 그대로 통과해야 하는 시험: `T/workerdaemon/CloseNoteWriterTest.java:69-98,130-134`, `T/git/GitRemotesTest.java:61-90`

**Interfaces:**
- Produces:
  - `public final class SecretMasks`
    - `public static final Pattern BARE_TOKENS` — 패턴 문자열은 `CloseNoteWriter.java:37-38`에서 그대로 옮긴다.
    - `public static String maskBareTokens(String text)` — 맞는 부분을 `***`로 바꾼다. null이면 null을 돌려준다.
    - `public static String maskUrlAndTokens(String text)` = `maskBareTokens(GitRemotes.mask(text))`
  - `public final class ProcessTrees`
    - `public static boolean killTree(Process p, Duration wait)`
      - 자손 목록을 먼저 뜬 뒤, 자식 → 부모 순서로 `destroyForcibly`한다.
      - `wait` 안에 모두 끝나면 true를 돌려준다.
      - `descendants()`가 `UnsupportedOperationException`을 던지면 자손이 없는 것으로 본다.
      - 인터럽트되면 플래그를 되살리고 false를 돌려준다.

- [ ] **Step 0: 브랜치와 설계 문서 준비**
  - `git -C C:\Users\mic\NetisMaker\commanCenter fetch origin`, `git -C C:\Users\mic\NetisMaker\commanCenter worktree add <W> -b feat/deploy-app-log-stream origin/main`
  - `origin/main`에 설계가 없으면 `git -C <W> checkout feat/task-type-db-write -- docs/superpowers/specs/2026-11-03-deploy-app-log-stream-design.md`로 가져온다. 이 계획은 실행자가 받은 경로에서 `<W>\docs\superpowers\plans\`로 복사한다.
  - `git -C <W> add docs/superpowers; git -C <W> commit -m "docs: 앱 로그 실시간 보기 설계·구현 계획"` → `git -C <W> log -1 --stat`에 두 파일
- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 맨_토큰은_별표_짧은_접두는_그대로() {
    assertThat(SecretMasks.maskBareTokens("x ghp_" + "a".repeat(20) + " y")).isEqualTo("x *** y");
    assertThat(SecretMasks.maskBareTokens("ghp_abc")).isEqualTo("ghp_abc");
    assertThat(SecretMasks.maskBareTokens(null)).isNull(); }
@Test void URL_자격증명과_토큰을_함께() {
    assertThat(SecretMasks.maskUrlAndTokens("https://oauth2:glpat-" + "b".repeat(20) + "@git.example/x sk-ant-" + "c".repeat(16)))
        .isEqualTo("https://***@git.example/x ***"); }
@Test void 트리째_끝낸다()                     // SlideRendererTest:79-80과 같은 OS 분기 명령 → killTree(p, 5s) == true, p와 미리 뜬 자손 모두 !isAlive()
@Test void 자손을_못_보는_프로세스도_끝낸다()     // descendants()가 UnsupportedOperationException인 Process 하위 클래스 → destroyForcibly 1회, true
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.util.SecretMasksTest --tests com.hamonsoft.netismaker.util.ProcessTreesTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - `CloseNoteWriter.maskSecrets(String)`는 package-private 래퍼로 남기고 `SecretMasks.maskUrlAndTokens`를 부른다. 호출부 `:58,87,104`는 그대로 둔다.
- [ ] **Step 4: 통과 확인** — Step 2 명령에 `--tests com.hamonsoft.netismaker.workerdaemon.CloseNoteWriterTest --tests com.hamonsoft.netismaker.git.GitRemotesTest`를 더해 PASS
- [ ] **Step 5: 커밋** — `refactor(util): 맨 토큰 가리기·프로세스 트리 종료를 공용 유틸로`

---

### Task 2: 설정과 허용 판정

**Files:**
- Create: `M/config/AppLogProperties.java`, `M/config/AppLogConfig.java`, `M/service/applog/AppLogPolicy.java`
- Modify: `R/application.yml:136-138` — `question:` 블록 뒤, `db-secret:` 앞에 `app-log:`·`deploy-log:` 블록을 넣는다(Global Constraints 값, 키마다 한 줄 주석).
- Modify: `T/TestcontainersConfig.java:45-46` — `"app.app-log.enabled=false"`와 이유 주석 한 줄(렌더링 차단과 같은 이유)을 넣는다.
- Test: `T/config/AppLogPropertiesBindingTest.java`(`T/service/render/RenderPropertiesBindingTest.java:15-42` 모양), `T/service/applog/AppLogPolicyTest.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("app.app-log") public record AppLogProperties(...)`
    - 칸: `@DefaultValue("true") boolean enabled, @DefaultValue("") String dockerPath, @DefaultValue("500") int tailLines, @DefaultValue("6") int maxStreams, @DefaultValue("3") int maxStreamsPerTask, @DefaultValue("30") int maxDurationMinutes, @DefaultValue("10") int pingSeconds, @DefaultValue("300") int batchMillis, @DefaultValue("200") int batchLines, @DefaultValue("4000") int maxLineChars`
    - compact 생성자: 정수 칸이 1 미만이면 `IllegalArgumentException("app.app-log.<kebab 키>는 1 이상이어야 합니다")`를 던진다(기동 실패).
  - `@Configuration @Profile("api") @EnableConfigurationProperties(AppLogProperties.class) public class AppLogConfig {}`
  - `public final class AppLogPolicy`
    - `public static final Set<TaskStatus> VIEWABLE_STATUSES = EnumSet.of(TaskStatus.DEPLOYED, TaskStatus.DEPLOY_LOST, TaskStatus.FIX_REVIEW, TaskStatus.CLOSE_REVIEW, TaskStatus.CLOSE_PENDING, TaskStatus.CLOSING, TaskStatus.CLOSE_FAILED);`
    - `public static final Set<TaskStatus> IN_FLIGHT_EXCLUDED = EnumSet.of(TaskStatus.DEPLOY_PENDING, TaskStatus.DEPLOYING, TaskStatus.UNDEPLOY_PENDING, TaskStatus.UNDEPLOYING);`
    - 두 선언은 한 문장으로 쓰고, 상수마다 `TaskStatus.` 접두를 붙인다. static import를 쓰지 않는다. Task 8 프론트 시험이 이 소스 문자열을 읽기 때문이다.
    - `public static boolean isViewable(Task t)` — Global Constraints "허용 상태"(API)
    - `static boolean hasDeployRecord(Task t)` = `t.getDeployUrl() != null || t.getDeployedAt() != null`
    - `public static String containerName(long taskId)` = `"netis-task-" + taskId` — 리터럴로 둔다. `workerdaemon` 패키지를 import하지 않는다(지금 `service`·`controller`·`config`에 워커 import가 없다). 워커의 `DockerGcPlanner.OWN_PREFIX`와 같은지는 시험이 대조한다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 허용_상태는_배포기록이_있을때만()        // VIEWABLE 7개 + deployUrl → true; 같은 상태에 deployUrl·deployedAt 둘 다 null → false; deployedAt만 있으면 true
@Test void 열린_회차면_구현대기_구현중_구현실패도()  // APPROVED·IMPLEMENTING·IMPLEMENTATION_FAILED + activeFixRequestId 7 + deployUrl → true; activeFixRequestId null → false
@Test void 진행_상태는_열린_회차여도_아니다()        // IN_FLIGHT_EXCLUDED 4개 × activeFixRequestId 7 + deployUrl → false
@Test void 배포실패_PR생성_작업종료는_아니다()       // DEPLOY_FAILED·PR_CREATED·CLOSED, 회차 없음, deployUrl 있음 → false
@Test void 허용과_제외를_합치면_GC_보호_목록과_같다() // findDeployReconcilable의 @Query에서 TaskStatus\.([A-Z_]+) (TaskRepositoryClaimableStatusesTest.java:25-37 모양)
                                                    //   == VIEWABLE ∪ EXCLUDED, 그리고 VIEWABLE ∩ EXCLUDED 공집합
@Test void 컨테이너_이름() { assertThat(AppLogPolicy.containerName(42)).isEqualTo("netis-task-42").isEqualTo(DockerGcPlanner.OWN_PREFIX + 42); }
// AppLogPropertiesBindingTest
@Test void 값이_없으면_기본값()     // true, "", 500, 6, 3, 30, 10, 300, 200, 4000
@Test void 키를_바꾸면_반영()       // "app.app-log.max-streams"="2", "app.app-log.docker-path"="C:/x/docker.exe"
@Test void 일_미만이면_바인딩_실패() // "app.app-log.ping-seconds"="0" → BindException, 원인 메시지에 "ping-seconds"
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.config.AppLogPropertiesBindingTest --tests com.hamonsoft.netismaker.service.applog.AppLogPolicyTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - **설계와 다름 ①:** 설정 등록은 `NetisMakerApplication.java:15-16`이 아니라 `AppLogConfig`에서 한다. 작업 타입 계획이 같은 줄을 고치므로 충돌을 피하려는 것이다(참고: `plans/2026-11-03-task-type-db-write.md:159`, 이 브랜치에는 없을 수 있다).
  - **설계와 다름 ②:** 배포실패에서 들어온 수정 회차도 규칙대로 허용된다. 수정 요청 가능 상태에 `DEPLOY_FAILED`가 있고(`TaskFixService.java:46-47`), 배포실패는 `deployUrl`을 비우지 않는다(`WorkerService.java:851-856`). 이때 컨테이너는 지워졌거나 멈춰 있으므로 화면에는 `no-container`·`container-stopped`가 나온다. 설계 §8 비목표("배포실패 작업")는 상태가 `DEPLOY_FAILED`인 작업이라 그대로 지켜진다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS, `.\gradlew.bat compileJava compileTestJava` 성공
- [ ] **Step 5: 커밋** — `feat(applog): 앱 로그 설정과 허용 상태 판정`

---

### Task 3: 줄 처리와 비밀 가리기

**Files:**
- Create: `M/service/applog/AppLogLine.java`, `M/service/applog/AppLogLines.java`, `M/service/applog/SecretMasker.java`
- Test: `T/service/applog/AppLogLinesTest.java`, `T/service/applog/SecretMaskerTest.java`

**Interfaces:**
- Consumes: Task 1 `SecretMasks.maskUrlAndTokens`, `StackSecretEnv.NAMES`(`M/util/StackSecretEnv.java:15-17`), `EnvVar`(`M/entity/EnvVar.java:12-19`, 값 ≤4000자)
- Produces:
  - `public record AppLogLine(String ts, String stream, String text)` — JSON 칸 이름이 그대로 나간다.
  - `public final class SecretMasker`
    - `public static SecretMasker of(List<EnvVar> envVars, Map<String,String> processEnv)`
      - 가릴 후보 ①: `envVars` 중 `secret`이고 4자 이상인 값. 값이 여러 줄이면 `\r` 없이 나눈 각 줄 중 4자 이상인 것도 따로 후보로 넣는다.
      - 가릴 후보 ②: `processEnv` 중 이름이 `NAMES`와 같고(대소문자 무시) 값이 비어 있지 않은 것.
      - 후보는 중복을 빼고 길이 내림차순으로 둔다.
    - `public String mask(String text)` — 후보를 문자 그대로 `••••`로 바꾼 뒤 `SecretMasks.maskUrlAndTokens`를 부른다.
  - `public final class AppLogLines`
    - `public static final String CUT_MARK = "…(잘림)"`
    - `static final Pattern TS_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})(?= |\\z)")` — 시각 접두만 `lookingAt()`으로 맞춘다. 본문은 접두 뒤 공백 하나를 뺀 `substring`이다. 본문을 정규식 `.`으로 잡지 않는다(`.`은 `\r`·U+0085·U+2028·U+2029를 넘지 못한다).
    - `static final Pattern ANSI = Pattern.compile("\\u001b\\[[0-9;]*[A-Za-z]")`
    - `public static String cut(String text, int max)` — `max`자를 넘으면 `max`자까지만 남기고 `CUT_MARK`를 붙인다. 끝이 서로게이트 앞 절반이면 그 반쪽은 버린다(`GitRemotes.java:55-68` 근거).
    - `public static AppLogLine toLine(String raw, String stream, SecretMasker masker, int maxChars)` — 끝 `\r`을 지우고 ANSI를 지운 뒤 시각을 뗀다. 시각 접두가 없으면 null을 돌려준다. 본문은 `cut(masker.mask(본문), maxChars)`
    - `public static String toInfo(String raw, SecretMasker masker, int maxChars)` = `cut(masker.mask(끝 \r·ANSI 제거), maxChars)`

- [ ] **Step 1: 실패하는 시험 작성**

```java
static final SecretMasker NONE = SecretMasker.of(List.of(), Map.of());
@Test void 시각_접두를_떼고_본문() { assertThat(AppLogLines.toLine("2026-11-03T01:02:03.123456789Z hello\r", "out", NONE, 4000))
    .isEqualTo(new AppLogLine("2026-11-03T01:02:03.123456789Z", "out", "hello")); }
@Test void 빈_본문_줄()              // "2026-11-03T01:02:03.000000000Z" → text ""
@Test void 본문_중간_CR과_줄구분_문자도_한_줄() // "2026-11-03T01:02:03.000000000Z a\rb\u0085c d e" → text "a\rb\u0085c d e"(null 아님)
@Test void ANSI_색_코드를_지운다()   // "2026-11-03T01:02:03.000000000Z \u001b[31mERROR\u001b[0m x" → text "ERROR x"; toInfo("\u001b[33mWARNING\u001b[0m y") == "WARNING y"
@Test void 시각_없는_줄은_null()       // "Error response from daemon: No such container: netis-task-42" → toLine null, toInfo는 그 문장 그대로
@Test void 넘으면_자르고_표시()        // "x".repeat(5000) → 길이 4000 + CUT_MARK.length(), endsWith("…(잘림)")
@Test void 가린_뒤에_자른다()          // [RF4] "a".repeat(3995) + "S3cretValue!" (secret) → 결과에 "S3cr" 없음, endsWith("••••")
@Test void 비밀_env는_4자_이상만()     // EnvVar("DB_PW","pw12",true) 가림 / EnvVar("K","abc",true) 그대로 / EnvVar("URL","visible1",false) 그대로
@Test void 스택_비밀은_이름으로_대소문자_무시() // Map.of("spring_datasource_password","ntflowLive","DOCKER_HOST","ssh://u@10.1.1.75") → "ntflowLive"는 가림, DOCKER_HOST 값은 그대로
@Test void 정규식_특수문자도_문자그대로()  // [RF3] secret "pa$$(w)rd\\1" → "login pa$$(w)rd\\1 ok" == "login •••• ok"
@Test void 긴_값부터()                  // [RF3] secret "abcd"·"abcdefgh" → mask("abcdefgh") == "••••"
@Test void 여러_줄_비밀은_줄마다()       // [RF3] secret "-----BEGIN KEY-----\nMIIEvQIBADANBg\n-----END KEY-----" → mask("k=MIIEvQIBADANBg") == "k=••••"
@Test void URL_자격증명과_맨_토큰()      // "jdbc:postgresql://app:pw@db:5432/x" → "jdbc:postgresql://***@db:5432/x"; "ghp_" + 20자 → "***"
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.service.applog.AppLogLinesTest --tests com.hamonsoft.netismaker.service.applog.SecretMaskerTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 바꾸기는 `String.replace`(리터럴)로 한다.
  - **설계와 다름(보탬) ①:** 설계는 여러 줄 값을 정하지 않았다. 로그는 줄 단위로 오므로 값 전체는 한 줄에 나오지 않는다. 그래서 줄마다 후보를 더한다.
  - **설계와 다름(보탬) ②:** ANSI 색 코드를 지운다. NestJS 같은 앱은 TTY가 아니어도 색을 찍어, 화면에 `[31m` 잔해가 남고 `\e[31mERROR`에서는 화면의 `\bERROR\b` 강조(Task 8)도 빗나간다(m과 E 사이에 단어 경계가 없다).
  - 2단계는 프로세스 env만 본다. yml 기본값(`R/application.yml:17` `ntflow`)은 보지 않는다. 라이브는 `public.env`의 `SPRING_DATASOURCE_PASSWORD`로 비밀번호를 주므로 가려진다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(applog): 앱 로그 줄 처리와 비밀 가리기`

---

### Task 4: docker 찾기·기동 로그·실행기

**Files:**
- Create: `M/service/applog/DockerLocator.java`, `AppLogAvailability.java`, `DockerLogsLauncher.java`, `ProcessDockerLogsLauncher.java`
- Test: `T/service/applog/DockerLocatorTest.java`(`SofficeLocatorTest` 모양), `AppLogAvailabilityTest.java`, `ProcessDockerLogsLauncherTest.java`(`T/service/render/ProcessBuilderStarterTest.java:19-34` 모양 — 실행하지 않고 builder만 본다)

**Interfaces:**
- Consumes: Task 2 `AppLogProperties`
- Produces:
  - `DockerLocator`
    - `public static Optional<Path> resolve(String configured, String osName, Function<Path,Boolean> exists, String pathEnv, String userHome)`
      - 찾는 순서: 설정값 → PATH(Windows는 `docker.exe`, 그 밖은 `docker`) → Windows면 `{userHome}\.local\bin\docker.exe`
    - `public static Optional<Path> resolveFromEnvironment(String configured)`
  - `@Component @Profile("api") public class AppLogAvailability`
    - `@Autowired` public 생성자 `(AppLogProperties)` — 꺼져 있으면 찾지 않는다. 생성자가 둘이라 `@Autowired`가 없으면 Spring이 기본 생성자를 찾다 기동에 실패한다(`RenderTargetPolicy.java:32-33` 선례). 로컬 `@SpringBootTest`는 모두 `RUN_TESTCONTAINERS`로 건너뛰므로 CI에서야 드러난다.
    - package-private 시험용 생성자 `(AppLogProperties, Optional<Path> docker, String dockerHostEnv)`(`RenderTargetPolicy.java:38` 선례)
    - `public boolean active()`, `public Optional<Path> docker()`
    - `String startupLine()` — 생성할 때 이 줄을 한 번 `log.info`로 남긴다. 줄은 셋 중 하나다.
      - `앱 로그: 켜짐(docker={경로}, DOCKER_HOST={원격|로컬|없음})`
      - `앱 로그: 꺼짐(docker 없음)`
      - `앱 로그: 꺼짐(설정 off)`
    - `static String dockerHostKind(String dockerHost)`
      - null·공백 → `없음`, `npipe://`·`unix://` → `로컬`, 그 밖 → `원격`
      - `LocalDockerTarget.isRemoteDaemon`(`:54-58`)과 같은 규칙이다. 값 자체는 로그에 남기지 않는다(`:45-47` 선례).
  - `public interface DockerLogsLauncher { Process start(List<String> command) throws IOException; }`
  - `@Component @Profile("api") public class ProcessDockerLogsLauncher implements DockerLogsLauncher`
    - `start`: `builder(command, System.getenv()).start()`를 부른 뒤 stdin을 닫는다.
    - `static ProcessBuilder builder(List<String> command, Map<String,String> parentEnv)`
      - env는 clear → putAll → `StackSecretEnv.strip` 순서로 만든다.
      - `redirectErrorStream(false)`, stdout·stderr는 둘 다 PIPE다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 설정값이_있으면_그것()
@Test void 윈도우는_PATH의_docker_exe_다음_로컬_bin()  // osName "Windows 11", PATH에 없음 → Path.of(userHome, ".local", "bin", "docker.exe")
@Test void 리눅스는_PATH의_docker()
@Test void 못_찾으면_empty()
@Test void 켜짐_줄() { var a = new AppLogAvailability(props(true), Optional.of(Path.of("/u/.local/bin/docker")), "ssh://adm@10.1.1.75");
    assertThat(a.active()).isTrue(); assertThat(a.startupLine()).startsWith("앱 로그: 켜짐(docker=").endsWith(", DOCKER_HOST=원격)").doesNotContain("adm@10.1.1.75"); }
@Test void 꺼짐_줄()          // docker 없음 → "앱 로그: 꺼짐(docker 없음)", active false; enabled false → "앱 로그: 꺼짐(설정 off)", active false
@Test void DOCKER_HOST_종류()  // null·"  " → 없음, "npipe:////./pipe/docker_engine"·"unix:///var/run/docker.sock" → 로컬, "tcp://x:2375" → 원격
@Test void 자식_env에서_스택_비밀을_빼고_DOCKER_HOST는_남긴다() // builder(cmd, Map.of("WORKER_API_KEY","k","Spring_Datasource_Password","p","DOCKER_HOST","ssh://u@h","PATH","x"))
                                                            //   env에 앞의 둘 없음, DOCKER_HOST·PATH 있음; redirectErrorStream() false; redirectOutput()·redirectError() == Redirect.PIPE
@Test void render_ProcessStarter를_구현하지_않는다() {   // SlideRenderer.java:45 생성자 주입이 모호해져 기동 실패하는 것을 막는 가드
    assertThat(Class.forName("com.hamonsoft.netismaker.service.render.ProcessStarter").isAssignableFrom(ProcessDockerLogsLauncher.class)).isFalse(); }
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests "com.hamonsoft.netismaker.service.applog.*"` → 컴파일 실패
- [ ] **Step 3: 구현** — 탐색 순서는 `SofficeLocator.resolve`(`:22-47`)를 본뜬다.
  - **설계와 다름 ①:** PATH 다음에 `~\.local\bin\docker.exe`를 한 번 더 본다. 실측 위치가 거기이고(설계 §2), 예약 작업으로 뜬 API가 그 폴더를 PATH에 갖는지는 확인되지 않았다(`scripts/start-public.ps1`은 PATH를 손대지 않는다).
  - **설계와 다름 ②:** 기동 로그에 `DOCKER_HOST` 종류를 붙인다. 설계 §6 배포 순서 1이 기동 로그로 그것을 확인하라고 하기 때문이다.
  - render의 `ProcessBuilderStarter`(`:34-35`)는 stderr를 합쳐 파일로 보낸다. 그래서 그것을 재사용하지 않는다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS, `--tests com.hamonsoft.netismaker.util.StackSecretEnvTest` PASS
- [ ] **Step 5: 커밋** — `feat(applog): docker 찾기·기동 로그·docker logs 실행기`

---

### Task 5: 스트림 본체

**Files:**
- Create: `M/service/applog/AppLogEndReason.java`, `AppLogSink.java`, `SseEmitterSink.java`, `AppLogStream.java`, `AppLogStreamService.java`
- Test: `T/service/applog/AppLogStreamServiceTest.java`
- Test 도우미: `T/service/applog/FakeDockerProcess.java`, `RecordingSink.java`
  - `FakeDockerProcess`: `Process`의 하위 클래스다. 다음을 갖는다.
    - stdout·stderr는 `BlockingQueue<byte[]>` 기반 `InputStream`이고 `writeOut(String)`·`writeErr(String)`로 넣는다. `PipedInputStream`은 쓰지 않는다(쓴 스레드가 끝나면 다음 read가 `IOException: Write end dead`라 시험이 들쭉날쭉해진다). EOF는 `exit`·`destroyForcibly` 때만 낸다.
    - `exit(int)`: 두 스트림에 EOF를 넣고 exit 코드를 정한다. 그 뒤 `waitFor`·`isAlive`·`onExit`가 곧바로 끝난다.
    - `destroyForcibly()`: 카운트를 올리고 `exit(137)`과 같게 동작한다(그래야 `killTree(p, 5s)`가 5초를 기다리지 않는다). `destroyForciblyCount()`
    - `descendants()`는 빈 스트림을 돌려준다.
  - `RecordingSink`: 성공한 이벤트·주석·complete를 순서대로 적는다(실패한 전송은 적지 않는다).
    - `failFrom(int n)`: n번째 전송부터 `IOException`을 던진다.
    - `blockFrom(int n)`: n번째 전송부터 `release()`까지 막는다(느린 소비자).

**Interfaces:**
- Consumes: Task 1 `ProcessTrees.killTree`, Task 2 `AppLogProperties`·`AppLogPolicy`, Task 3 `SecretMasker`·`AppLogLines`·`AppLogLine`, Task 4 `AppLogAvailability`·`DockerLogsLauncher`
- Produces:
  - `public enum AppLogEndReason`
    - 상수: `NOT_ALLOWED_STATUS, TOO_MANY, DISABLED, NO_CONTAINER, DOCKER_ERROR, TIME_LIMIT, CONTAINER_STOPPED`
    - 메서드: `String wire()`, `String message(AppLogProperties p)` — 값은 Global Constraints 표대로다.
  - `public interface AppLogSink { void event(String name, Object data) throws IOException; void comment(String text) throws IOException; void complete(); }`
  - `public final class SseEmitterSink implements AppLogSink`
    - `event` = `emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))`
    - `complete`은 이미 닫힌 경우의 예외를 삼킨다.
  - `public final class AppLogStream`
    - public `void clientGone()` — 정리만 하고 `end`는 보내지 않는다.
    - package-private `void timeLimit()`(= `close(TIME_LIMIT)`), `void close(AppLogEndReason reasonOrNull)`, `boolean closed()`
  - `@Service @Profile("api") public class AppLogStreamService`
    - `@Autowired` public 생성자 `(AppLogProperties, AppLogAvailability, DockerLogsLauncher)` — 시간 상한은 `Duration.ofMinutes(maxDurationMinutes)`, processEnv는 `System.getenv()`. 생성자가 둘이라 `@Autowired`가 꼭 있어야 한다(Task 4와 같은 이유).
    - package-private 시험용 생성자 `(AppLogProperties, AppLogAvailability, DockerLogsLauncher, Map<String,String> processEnv, Duration timeLimit)`
    - `public SseEmitter subscribe(Task task)`
      - `new SseEmitter(Duration.ofMinutes(maxDurationMinutes + 1).toMillis())`로 만든다.
      - `open(task, new SseEmitterSink(e))`를 부른다.
      - `onCompletion`·`onTimeout`·`onError`에서 `stream.clientGone()`을 부른다.
    - `AppLogStream open(Task task, AppLogSink sink)`(package-private) — 거절이면 이미 닫힌 핸들을 돌려준다.
    - `int activeCount()`, `int activeCount(long taskId)`
    - `@PreDestroy public void shutdown()`
    - `static List<String> command(Path docker, int tailLines, long taskId)`

- [ ] **Step 1: 실패하는 시험 작성** — 기다림은 `await().atMost(2, SECONDS)`(Awaitility)로 한다(핑 주기 시험만 3초). 시험의 ts는 9자리 고정·`Z` 형식이다(`2026-11-03T01:02:03.000000001Z`).

```java
@Test void 꺼져_있으면_end_disabled_프로세스_없음()        // launcher 호출 0; sink == [end{reason:"disabled", message:"앱 로그 기능이 꺼져 있습니다(docker 없음 또는 설정 꺼짐)"}, complete]
@Test void 허용_상태가_아니면_end_not_allowed_status()     // DEPLOYING + deployUrl, launcher 호출 0
@Test void 명령과_첫_핑()                                  // command == [docker, "logs","--tail","500","--timestamps","--follow","netis-task-42"]; sink 첫 기록 == comment "ping"
@Test void 핑은_pingSeconds마다()                          // pingSeconds 1, 줄 없음, 전송 스레드의 주기만으로 → 3초 안에 comment "ping" 2개 이상
@Test void subscribe_emitter_타임아웃은_상한_더하기_1분()    // 꺼진 availability로 subscribe(task) → getTimeout() == 31 * 60_000L
@Test void 전체_6_작업당_3을_넘으면_too_many()             // 작업 1~6 하나씩 → 작업 7은 end too-many; (새 서비스) 작업 42 세 개 뒤 네 번째도 too-many; launcher 호출 수 == 성공 수
@Test void 줄은_200줄씩_묶고_순서를_지킨다()               // [RF4] stdout ts 줄 450개 → lines 이벤트 크기 각 ≤200, 합 450, text 순서 그대로(마지막 묶음은 batchMillis 주기로)
@Test void stdout_stderr를_둘다_읽고_stream_표시()          // out 한 줄 → "out", err 한 줄 → "err"
@Test void stderr가_가득_차도_stdout이_막히지_않는다()       // err에 256KB를 쓰는 스레드와 함께 out 한 줄 → 2초 안에 그 줄 도착
@Test void 시각_없는_줄은_info()                            // err "WARNING: ssh …" → info{message:"WARNING: ssh …"}
@Test void exit0이면_남은_줄_뒤_container_stopped()         // 마지막 lines가 end보다 먼저, activeCount()==0
@Test void No_such_container로_끝나면_no_container()        // err "Error response from daemon: No such container: netis-task-42" + exit 1
@Test void 그밖의_비정상_종료는_docker_error()               // err "error during connect: …" + exit 1
@Test void 실행_실패는_docker_error_자리_반납()              // launcher가 IOException → end docker-error, activeCount()==0
@Test void 핑_전송이_실패하면_트리를_끝내고_자리를_반납()       // [RF1] pingSeconds 1, 줄 없이 sink.failFrom(2), 주기 핑만으로 → 3초 안에 destroyForciblyCount()==1, activeCount()==0;
                                                           //   sink 기록 == [comment "ping", complete](end 없음, complete 정확히 1회, 그 밖의 event·comment 없음)
@Test void 시간_상한은_예약돼_end_time_limit()               // 시험용 생성자 timeLimit 300ms, timeLimit()을 부르지 않음 → end{reason:"time-limit", message:"30분이 지나 연결을 닫았습니다"}, destroyForciblyCount()==1
@Test void 막힌_sink는_다른_스트림의_핑과_줄을_막지_않는다()     // [RF4] pingSeconds 1; 스트림 A는 sink.blockFrom(2) + out 한 줄 → 스트림 B의 out 한 줄은 2초 안에, B의 두 번째 ping은 3초 안에 도착
@Test void 막힌_sink여도_시간_상한이면_트리를_끝내고_자리를_반납() // [RF4] timeLimit 300ms, sink.blockFrom(2) + out 한 줄 → release() 전에 destroyForciblyCount()==1, activeCount()==0
@Test void 정리는_한번만()                                  // [RF2] clientGone()·timeLimit()·process.exit(0)을 세 스레드에서 CountDownLatch로 동시에
                                                           //   → end ≤1회, destroyForciblyCount() ≤1, activeCount()==0, activeCount(42)==0, 이어서 작업 42 open 3번이 모두 프로세스를 띄움
@Test void 거대한_한줄도_자르고_가린다()                      // [RF4] ts + 1,000,000자(줄바꿈 없음, 3990자 지점에 secret "dbPassw0rd") + "\n" + 다음 ts 줄
                                                           //   → 첫 줄 text 길이 4000 + CUT_MARK, "dbPa" 없음; 다음 줄도 도착
@Test void 비밀_env는_가려서_보낸다()                         // envVars secret "dbPassw0rd" → out "pw=dbPassw0rd" == "pw=••••", info도 가림
@Test void PreDestroy는_모든_스트림을_끝낸다()                // 둘 → shutdown() → 둘 다 destroyForciblyCount()==1, activeCount()==0
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.service.applog.AppLogStreamServiceTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 다음이 정해진 규칙이다. 나머지는 실행자가 고른다.
  - **open 순서:** 거절이면 프로세스 없이 `end` + `complete`로 끝낸다.
    1. `availability.active()`를 본다.
    2. `AppLogPolicy.isViewable`를 본다.
    3. 상한을 예약한다. 한 락 안에서 전체 < `maxStreams`이고 작업당 < `maxStreamsPerTask`인지 본다.
    4. 주석 `ping`을 보낸다(요청 스레드, 아직 다른 스레드가 sink를 쓰지 않는다).
    5. `launcher.start`를 부른다. `IOException`이면 예약을 반납하고 `docker-error`로 끝낸다.
    6. 읽기 스레드 2개와 전송 스레드 1개를 시작한다.
    7. 시간 상한을 예약한다.
  - **읽기:**
    - 스레드 이름은 `app-log-{id}-out|err`이고 daemon이다. UTF-8을 명시한다.
    - 한 줄은 `2 × maxLineChars`자까지만 모으고, 남은 부분은 `\n`까지 버린다. 근거: `EnvVar` 값은 4000자 이하(`EnvVar.java:14`)라서 처음 `maxLineChars`자에 걸친 비밀은 잘리지 않고 통째로 들어온다.
    - 줄은 스트림의 순서 버퍼 하나에 넣는다. 시각이 있으면 `AppLogLine`, 없으면 info 문자열이다. info는 분류용으로 최근 5개를 따로 남긴다.
    - 버퍼가 `2 × batchLines`개면 자리가 날 때까지 기다린다. 그러면 docker 파이프에 역압이 걸려 읽기 메모리가 묶인다. 버퍼가 `batchLines`에 닿으면 전송 스레드를 깨운다.
  - **전송(느린 소비자 대비):** 스트림마다 daemon 스레드 `app-log-{id}-send` 하나만 open 뒤의 sink를 부른다.
    - `batchMillis`마다(또는 깨워지면) 버퍼를 비운다. 이어진 줄은 `lines` 이벤트 하나에 `batchLines`줄 이하로, info는 `info` 이벤트로, 들어온 순서대로 보낸다.
    - 마지막 핑 뒤 `pingSeconds`가 지났으면 주석 `ping`을 보낸다.
    - 전송이 `IOException`이면 그 자리에서 `close(null)`을 부른다. 끊김 정리를 `onError` 콜백에만 기대지 않는다.
    - sink가 막히면 그 스트림의 전송 스레드만 멈춘다. 다른 스트림의 핑·flush는 각자의 스레드라 제때 간다. 공용 타이머 위에서 보내면 막힌 스트림 하나가 다른 스트림의 핑과 시간 상한까지 세운다.
  - **시간 상한:** 전용 `ScheduledExecutorService`(스레드 1개, daemon, `app-log-timer`, `AttachmentRenderJob.java:58-62` 모양)에 스트림마다 `timeLimit()`을 한 번 예약한다. 이 타이머는 sink를 부르지 않는다.
    - `@Scheduled`를 쓰지 않는 이유: API 스케줄러는 스레드가 1개라(`R/application-worker.yml:22`에만 풀 설정이 있음) 03:30·03:50 정리 잡이 몇 분씩 막을 수 있다.
  - **docker 종료:** 두 읽기가 모두 EOF에 닿으면 `waitFor(5s)` 뒤 사유를 정해 `close(사유)`를 부른다. 남은 줄은 전송 스레드가 `end`보다 먼저 보낸다.
    - exit 0 → `container-stopped`
    - exit≠0이고 최근 info에 `No such container`가 있음 → `no-container`
    - 그 밖 → `docker-error`
  - **`close(reason)`:** `AtomicBoolean`으로 한 번만 돈다. 1~4는 락 없이, 부른 스레드에서 바로 한다. 그래서 sink가 막혀 있어도 프로세스와 자리는 곧바로 정리된다.
    1. 시간 상한 예약 취소
    2. `ProcessTrees.killTree(p, 5s)`
    3. 상한 반납
    4. 레지스트리에서 제거
    5. 버퍼 자리를 기다리는 읽기 스레드를 깨운다(닫힌 뒤 들어온 줄은 버린다). 끝 신호를 전송 스레드에 넘기고 깨운다. 전송 스레드는 남은 줄을 보내고, `reason != null`이면 `end`를 보낸 뒤(전송 실패는 무시) `sink.complete()`를 부르고 끝난다. close를 부른 스레드가 전송 스레드 자신이면 그 자리에서 하고, 전송 스레드가 아직 없으면(open 중 거절·실행 실패) 부른 스레드가 직접 한다.
  - `@Transactional`을 어디에도 달지 않는다. OSIV가 꺼져 있고(`R/application.yml:27-32`), `envVars`는 JSONB 칸이라 즉시 로드된다(`Task.java:113-116`).
  - `shutdown`은 모든 스트림에 `close(null)`을 부른 뒤 executor를 `shutdownNow`한다.
  - **설계와 다름 ①:** 동시 상한을 넘으면 설계 §4의 "429"가 아니라 200 안의 `end(too-many)`로 보낸다. EventSource는 상태 코드를 읽지 못하고, 설계 §4 자체도 상태 코드는 인증·권한에만 쓰고 `too-many`는 `end` reason이라고 한다(설계 :70,:73 ↔ :88 모순).
  - **설계와 다름 ②:** `end` 바디는 늘 `{reason, message}`다(설계 :71과 :97이 다르다).
  - **설계와 다름 ③:** 앱 로그 핑은 20초가 아니라 10초다. 끊긴 상대에게 하는 첫 쓰기는 성공할 수 있고 실패는 대개 두 번째 쓰기에서 난다. 그래서 조용한 로그의 최악 감지 시간은 핑 주기의 2배다. 이 값이어야 완료 조건 "약 20초"(설계 §4)를 맞춘다. 빌드 로그 핑은 설계대로 20초다(Task 6).
  - **설계와 다름 ④:** "API가 종료되면 트리를 끝낸다"는 `@PreDestroy`로는 dev·시험의 정상 종료에서만 이뤄진다. 라이브 정지는 `taskkill /T /F`라(`scripts/stop-public.ps1:53`, `scripts/public-proc.ps1:211`) `@PreDestroy`가 돌지 않는다. 대신 `/T`가 docker·ssh 자손까지 끝낸다. JVM이 그냥 죽는 경우는 위험으로만 적는다(Task 12).
- [ ] **Step 4: 통과 확인** — `Set-Location <W>; .\gradlew.bat test --tests "com.hamonsoft.netismaker.service.applog.*"` PASS
- [ ] **Step 5: 커밋** — `feat(applog): docker logs 스트림 — 묶음 전송·핑·상한·한 번만 정리`

---

### Task 6: 구독 엔드포인트와 빌드 로그 핑

**Files:**
- Create: `M/controller/TaskAppLogController.java`
- Modify: `M/service/DeployLogStreamService.java:57-75` — 접속 핑을 넣고 `subscribe(Long, SseEmitter)`를 분리한다. 새 `ping()`·`keepAlive()`를 둔다.
- Test: `T/controller/TaskAppLogControllerTest.java`(`T/controller/TaskControllerSseResolverTest.java:45-120` 모양), `T/service/DeployLogStreamServiceTest.java`(추가), `T/controller/TaskApiIntegrationTest.java`(추가, CI — `:162-205` 옆, 게이트 `:36-41`)

**Interfaces:**
- Consumes: Task 5 `AppLogStreamService.subscribe(Task)`, `TaskService.getForView(Long, String, boolean)`(`M/service/TaskService.java:417-424`)
- Produces:
  - `@RestController @RequestMapping("/api/tasks") @Profile("api") public class TaskAppLogController(TaskService, AppLogStreamService)`
    - `TaskController`(`:56-72`)는 건드리지 않는다. 작업 타입 계획과의 충돌, 그리고 생성자를 쓰는 시험 4곳 수정을 피하려는 것이다.
  - `@GetMapping(value = "/{id}/app-logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) public ResponseEntity<SseEmitter> appLogsStream(@PathVariable Long id, JwtAuthenticationToken auth)`
    - `TaskException`이면 `ResponseEntity.status(e.getStatus()).build()`를 돌려준다(본문 없음).
  - `DeployLogStreamService`
    - `SseEmitter subscribe(Long taskId, SseEmitter emitter)`(package-private) — 첫 전송은 `SseEmitter.event().comment("ping")`이다. 기존 `subscribe(Long)`은 `new SseEmitter(0L)`을 만들어 이것을 부른다.
    - `public void ping()` — 모든 구독에 주석 핑을 보내고, 실패한 구독은 `remove`한다.
    - `@Scheduled(fixedRateString = "${app.deploy-log.sse-ping-interval-ms:20000}") public void keepAlive()`(`InterviewStreamService.java:179-181` 모양)

- [ ] **Step 1: 실패하는 시험 작성**

```java
// TaskAppLogControllerTest — @WebMvcTest(controllers = TaskAppLogController.class), @Import(SecurityConfig, CorsConfig, WorkerApiKeyFilter), @MockBean JwtDecoder·UserProvisioner·TaskService·AppLogStreamService
@Test void access_token_쿼리로_200_비동기()            // subscribe(stubTask) → new SseEmitter(0L); status 200, request().asyncStarted()
@Test void 토큰이_없으면_401()
@Test void 남의_작업은_SSE_Accept여도_403_본문없음()    // getForView → TaskException.forbidden(); header Accept text/event-stream → 403, content ""; verify(appLogs, never()).subscribe(any())
@Test void 없는_작업은_404()
// DeployLogStreamServiceTest (추가)
@Test void 접속하면_먼저_핑_주석()                     // mock SseEmitter → subscribe(7L, e) → 첫 send 인자의 build() 데이터를 이은 문자열에 ":ping"
@Test void 핑은_모든_구독에_보내고_실패한_구독은_뺀다()  // e1 정상·e2 send IOException → ping() → ingestChunk(7L, 1, "x") 뒤 e2 추가 send 없음, e1은 log 받음
// TaskApiIntegrationTest (추가, CI)
@Test void 앱로그_스트림은_등록자와_관리자만()           // 남 403, 소유자 200 + asyncStarted, 관리자 200 — TestcontainersConfig가 끄므로 docker 없이 end(disabled)
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.controller.TaskAppLogControllerTest --tests com.hamonsoft.netismaker.service.DeployLogStreamServiceTest` → 컴파일 실패
- [ ] **Step 3: 구현** — `userId`·`isAdmin`은 `AuthContext`로 구한다(`TaskController.java:280-282` 선례). 메서드에 `@Transactional`을 달지 않는다.
  - **설계와 다름 ①:** 403·404는 `GlobalExceptionHandler`(`:20-26`)의 JSON 본문 없이 낸다. `produces=text/event-stream` + `Accept: text/event-stream`에서 Map 본문이 406·500으로 바뀌지 않는지는 확인된 바 없기 때문이다. 기존 시험은 Accept 없이 본다(`TaskApiIntegrationTest.java:173-174`).
  - **설계와 다름 ②:** 설계는 빌드 로그 핑의 설정 키를 정하지 않았다. `app.deploy-log.sse-ping-interval-ms`로 둔다. 기존 `@Scheduled` 선례를 따르므로 03:30·03:50 정리 잡이 도는 동안은 밀릴 수 있다.
- [ ] **Step 4: 통과 확인** — Step 2 명령과 `--tests com.hamonsoft.netismaker.controller.TaskControllerSseResolverTest` PASS. `.\gradlew.bat compileTestJava` 성공, 로컬 리포트에서 `TaskApiIntegrationTest`가 skipped다(CI 실행 확인은 Task 12 Step 7).
- [ ] **Step 5: 커밋** — `feat(applog): 앱 로그 구독 엔드포인트·빌드 로그 20초 핑`

---

# Part B — Nitro 서버 라우트 (§4 끊김 전달)

### Task 7: `/stream/tasks/{id}/app-logs`

**Files:**
- Create: `F/server/utils/appLogProxy.ts`, `F/server/routes/stream/tasks/[id]/app-logs.get.ts`
  - `F/server/` 디렉터리는 지금 없다. 이것이 첫 서버 라우트다.
- Modify: `F/nuxt.config.ts:32-43` — `runtimeConfig` 최상위(public 밖)에 `apiProxyTarget`
- Modify: `F/scripts/hydration-smoke.mjs`
  - `:209`: 가로채기 조건에 `url.pathname.startsWith('/stream/')`(204)를 더한다.
  - `main`의 `failures` 선언(`:256`) 바로 뒤: 직접 확인 2건을 넣는다. 서버 기동(`:248`) 직후에는 `failures`가 아직 없다.
- Test: `F/test/app-log-stream-proxy.spec.ts`

**Interfaces:**
- Consumes: Task 6 API 경로·상태 코드 규칙
- Produces:
  - `export interface AppLogProxyOptions { id: string | undefined; token: unknown; apiBase: string; req: IncomingMessage; res: ServerResponse; fetchImpl?: typeof fetch }` — `import type { IncomingMessage, ServerResponse } from 'node:http'`
  - `export async function pipeAppLogStream(o: AppLogProxyOptions): Promise<void>` — 응답이 끝날 때까지 resolve하지 않는다. h3·Nitro 별칭은 import하지 않는다(vitest에서 풀리지 않음).
  - 라우트는 한 줄이다. `export default defineEventHandler(async (event) => { await pipeAppLogStream({ id: getRouterParam(event, 'id'), token: getQuery(event).access_token, apiBase: useRuntimeConfig(event).apiProxyTarget, req: event.node.req, res: event.node.res }) })`
  - `runtimeConfig.apiProxyTarget: process.env.NUXT_API_PROXY_TARGET || 'http://localhost:8090'` — 런타임 env `NUXT_API_PROXY_TARGET`가 덮는다(`NM\nitropack\dist\runtime\internal\config.mjs:5-24`).
  - 브라우저 URL: `/stream/tasks/{id}/app-logs?access_token=…`

- [ ] **Step 1: 실패하는 시험 작성** — 진짜 `node:http` 서버를 `127.0.0.1:0`에 띄운다. 그 핸들러가 `pipeAppLogStream`을 가짜 `fetchImpl`로 부르고, 그 promise가 끝났는지 기록한다. 파일 경로는 `__dirname`으로 푼다.
  - 가짜 본문은 `node:stream/web`의 `new ReadableStream({ pull, cancel }, { highWaterMark: 0 })`이다. `pull` 횟수, `cancel` 호출, `init.signal`의 abort를 기록한다.
  - 가짜 본문은 undici와 달리 abort돼도 스스로 끝나지 않는다. 그래서 끊김은 구현의 `reader.cancel()`로만 끝난다(Step 3).

```ts
describe('Nitro /stream/tasks/{id}/app-logs (스펙 2026-11-03 §4 끊김 전달)', () => {
  it('토큰이 없으면 401, API를 부르지 않는다')
  it('id가 숫자가 아니면 404, API를 부르지 않는다')
  it('API에는 Authorization 헤더 하나로 — URL에 토큰 없음', () => {
    expect(calls[0][0]).toBe('http://api.test/api/tasks/42/app-logs/stream')   // apiBase 'http://api.test/'의 끝 / 정리
    expect(calls[0][1].headers).toEqual({ authorization: 'Bearer tok', accept: 'text/event-stream' }) })
  it('200이면 SSE 머리와 바이트를 그대로')   // content-type 'text/event-stream', cache-control 'no-cache, no-transform', x-accel-buffering 'no', content-length 없음;
                                              // 받은 본문 === ':ping\n\nevent:lines\ndata:{"lines":[]}\n\n'
  it('브라우저가 끊으면 1초 안에 upstream을 abort하고 본문을 취소한다')   // [RF1] 첫 조각을 받은 뒤 client req.destroy() → 1초 안에 init.signal.aborted true, cancel 호출, pipeAppLogStream promise 끝남
  it('클라이언트가 읽지 않으면 drain 전까지 upstream을 더 읽지 않는다')  // [RF4] pull마다 64KB 조각; 클라이언트 응답 res.pause() → 1초 뒤와 1.3초 뒤 pull 수가 같고 64 미만
  it('API가 403이면 403, 본문 취소')
  it('API에 닿지 못하면 502')                 // fetchImpl이 TypeError('fetch failed')로 거부
  it('nuxt.config: apiProxyTarget는 public 밖, routeRules에 /stream 없음')   // brand-assets.spec.ts:34-39 모양의 텍스트 검사
  it('라우트 파일은 server/routes/stream 아래이고 server/api 아래에는 없다')
})
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; $nm='<W>\frontend\node_modules'; if ((Get-Item -LiteralPath $nm -EA SilentlyContinue).LinkType -eq 'Junction') { cmd /c rmdir $nm }; npm ci; npx vitest run test/app-log-stream-proxy.spec.ts` → FAIL
  - 앞의 정션 확인을 빼지 않는다. 정션이 걸린 채 `npm ci`를 돌리면 라이브 프론트의 `node_modules`가 지워진다(Global Constraints "시험"). `Remove-Item -Recurse`로 걷지 않는다.
  - `npm ci` 직후 `npm run lint` 경고 수를 기준선으로 적어 둔다(Task 12 Step 3).
- [ ] **Step 3: 구현**
  - **빠른 거절:** id가 `^\d+$`가 아니면 404, 토큰이 비었거나 문자열이 아니면 401이다. 둘 다 fetch 없이 `res.end()`한다.
  - **upstream 호출:** `fetchImpl(\`${apiBase에서 끝 / 제거}/api/tasks/${id}/app-logs/stream\`, { headers: { authorization: \`Bearer ${token}\`, accept: 'text/event-stream' }, signal })`
    - 토큰은 쿼리에 싣지 않는다. 헤더와 쿼리에 함께 있으면 Spring이 거부한다(`M/config/SecurityConfig.java:104-114`).
  - **끊김:** `req`와 `res`의 `'close'` 둘 다에 `controller.abort()`를 건다. abort는 여러 번 불려도 된다. `signal`의 abort 리스너에서 `reader.cancel().catch(() => {})`를 부른다.
  - **upstream 응답별 처리:**
    - fetch가 abort가 아닌 이유로 거부되면 502를 낸다.
    - status가 200이 아니면 그 상태를 그대로 내고 `upstream.body?.cancel()`을 부른다.
    - 200이면 다음 순서로 보낸다.
      1. 머리 4개를 직접 둔다: `Content-Type: text/event-stream`, `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`, `Connection: keep-alive`. `Content-Length`는 넣지 않고 upstream 머리도 복사하지 않는다.
      2. `res.flushHeaders()`
      3. `const reader = upstream.body.getReader()`로 읽고 바이트를 그대로 넘긴다(핑 주석이 빠지지 않게).
         - 루프: `for (;;) { const r = await reader.read(); if (r.done) break; if (!res.write(r.value)) await once(res, 'drain', { signal }) }` (`import { once } from 'node:events'`)
         - `res.write`가 false면 drain을 기다린다. 기다리는 동안 upstream을 더 읽지 않으므로, 느린 클라이언트가 Nitro 메모리를 키우지 않는다. 끊기면 `signal`이 그 대기를 깬다.
         - `for await`·`pipeTo`는 쓰지 않는다. 잠긴 스트림에는 `body.cancel()`이 TypeError로 거부되고, 반복자의 `return()`은 대기 중인 read 뒤에 줄을 선다.
      4. `signal.aborted` 뒤에 나는 오류(AbortError 포함)는 삼킨다.
      5. finally에서 리스너를 지우고 `res.end()`한다.
  - **쓰지 않는 것:**
    - `proxyRequest`·`sendProxy`: signal이 없고 `accept`를 버린다(`NM\h3\dist\index.mjs:1138-1147,1176-1252`).
    - `upstream.body` 반환: `pipeTo`만 하고 끊김을 전하지 않는다(`:866-875`).
    - 헤더를 보내기 전에 return: 204가 된다(`:2098-2100,2263-2266`).
  - **위치:** `server/routes/`에 둔다. `server/api/`에 두면 `/api` 접두가 붙어 routeRules 프록시에 가려진다(`NM\nitropack\dist\runtime\internal\app.mjs:116-138`).
  - **설계와 다름 ①:** Nitro 라우트 자신의 실패는 새 `end` reason 없이 상태 코드로 낸다(401 토큰 없음·404 id·502 API 연결 실패·upstream 상태 그대로). 브라우저에는 이벤트 없는 `onerror`로 보이고, 설계 §5의 "지수 백오프로 몇 번까지만" 경로(Task 9)를 탄다.
  - **설계와 다름 ②:** 설계 §5는 "빠진 API 목록에 뜨면 목을 넣는다"고 하지만, 스모크는 `/api/`·`/oauth2/`만 가로채므로 `/stream/`은 그 목록에 뜨지 않는다(`hydration-smoke.mjs:209-215`). 그래서 대신 다음 두 가지를 한다.
    - 브라우저 가로채기에 `/stream/` 204를 더한다.
    - `main`의 `failures` 선언 뒤에 Node `fetch`로 직접 확인한다.
      - `${BASE}/stream/tasks/1/app-logs` → 401
      - `?access_token=x` → 502이고 `content-type`에 `text/html`이 없음(런타임 env `NUXT_API_PROXY_TARGET=http://127.0.0.1:9`, `:173`)
      - 하나라도 어긋나면 `{ label, finalPath, problems }` 모양(`:240`)으로 `failures`에 넣어 실패시킨다.
- [ ] **Step 4: 통과 확인**
  - `Set-Location <W>\frontend; npx vitest run test/app-log-stream-proxy.spec.ts` PASS
  - `npx nuxi prepare; npm run typecheck` 오류 0
  - `$env:NUXT_IGNORE_LOCK='1'; npm run build; Remove-Item Env:NUXT_IGNORE_LOCK; npm run test:hydration` → 경고·예외 0, 직접 확인 2건(401·502) 통과
- [ ] **Step 5: 커밋** — `feat(applog): Nitro 서버 라우트로 앱 로그 SSE 끊김 전달`

---

# Part C — 화면 (§5)

### Task 8: 순수 함수 — 판정·강조·줄 합치기·파일명, API 목록 대조

**Files:**
- Create: `F/composables/appLog.ts` + `F/composables/appLog.spec.ts`, `F/composables/saveBlob.ts`
- Modify: `F/composables/attachmentDownload.ts:15-25`(`saveBlob`을 쓰게 바꾼다)
- Test: `appLog.spec.ts`, `F/composables/attachmentDownload.spec.ts`(그대로 통과)

**Interfaces:**
- Consumes: Task 2 `AppLogPolicy.java` 소스 문자열
- Produces(`appLog.ts`):
  - `APP_LOG_VIEWABLE_STATUSES`(7개), `APP_LOG_EXCLUDED_STATUSES`(4개): `readonly string[]`
  - `appLogViewable(task: { status: string; deployment?: { deployUrl: string | null; deployedAt: string | null } | null; fix?: { activeRound: number | null } | null } | null | undefined): boolean`
  - `type AppLogLevel = 'error' | 'warn' | null`
  - `lineLevel(text: string): AppLogLevel` — 오류가 경고보다 앞선다.
    - 오류: `/\b(ERROR|FATAL|SEVERE)\b|Exception|Caused by:|SQLException|ORA-\d+|SQLSTATE|ERROR \d+|ERROR:/`
    - 경고: `/\bWARN(ING)?\b/`
  - `interface AppLogWireLine { ts: string; stream: 'out' | 'err'; text: string }`
  - `interface AppLogViewLine extends AppLogWireLine { seq: number; level: AppLogLevel }`
  - `APP_LOG_MAX_LINES = 5000`
  - `appendLines(old: AppLogViewLine[], incoming: AppLogWireLine[], cutoffTs: string | null, nextSeq: number, max = APP_LOG_MAX_LINES): { lines: AppLogViewLine[]; nextSeq: number }`
    - `cutoffTs`가 있으면 `ts <= cutoffTs`(문자열 비교)인 줄을 버린다.
    - 결과는 끝 `max`줄이다.
  - `appLogStreamUrl(taskId: number | string, token: string): string`
  - `formatLogTime(ts: string): string` — 로컬 `HH:mm:ss.SSS`를 돌려준다. 못 읽으면 `ts`를 그대로 돌려준다.
  - `appLogText(lines: AppLogViewLine[]): string` — 줄마다 `[${ts}] ${text}\n`
  - `appLogFileName(taskId: number | string, now: Date): string` — 로컬 시각으로 직접 조립한다. Intl과 `:`는 쓰지 않는다(`TaskHistoryTimeline.vue:1-9` 선례).
  - `APP_LOG_END_TEXT: Record<string, string>` — 클라이언트 전용 사유 둘만 둔다: `connection-lost`: `연결이 끊겼습니다`, `auth`: `다시 로그인한 뒤 연결하세요`. 서버 사유의 문구는 복사하지 않는다(서버가 늘 `message`를 싣고, `time-limit` 문구의 분 수는 클라이언트가 모른다).
  - `APP_LOG_END_FALLBACK = '앱 로그 연결이 끝났습니다'`
  - `appLogEndText(reason: string | null, message: string | null): string` = `message ?? APP_LOG_END_TEXT[reason ?? ''] ?? APP_LOG_END_FALLBACK`
- Produces(`saveBlob.ts`): `saveBlob(blob: Blob, fileName: string): void` — `attachmentDownload.ts:15-25`의 앵커 click·지연 해제를 그대로 옮긴다.

- [ ] **Step 1: 실패하는 시험 작성** — `describe('appLog (스펙 2026-11-03 §4·§5)')`

```ts
it('허용 상태·열린 회차·배포 기록')   // DEPLOYED+deployUrl → true; DEPLOYED+deployment null → false; IMPLEMENTING+fix.activeRound 1+deployUrl → true;
                                       // DEPLOYING+fix.activeRound 1 → false; CLOSE_FAILED+deployedAt만 → true; PR_CREATED·DEPLOY_FAILED → false; null → false
it('허용·제외 상태 목록이 API AppLogPolicy.java와 같다', () => {
  const src = readFileSync(resolve(__dirname, '../../src/main/java/com/hamonsoft/netismaker/service/applog/AppLogPolicy.java'), 'utf8')
  expect(namesIn(src, 'VIEWABLE_STATUSES')).toEqual([...APP_LOG_VIEWABLE_STATUSES].sort())
  expect(namesIn(src, 'IN_FLIGHT_EXCLUDED')).toEqual([...APP_LOG_EXCLUDED_STATUSES].sort()) })   // namesIn: `<이름> = EnumSet.of(…)` 안의 TaskStatus\.([A-Z_]+), 정렬
it('강조', () => {
  for (const t of ['ERROR o.h.e.j.s.SqlExceptionHelper', 'Caused by: x', 'ORA-00942: table', 'SQLSTATE[42S22]', 'ERROR 1054 (42S22)',
                   'ERROR:  column "x" does not exist', 'java.sql.SQLException', 'FATAL boot', 'SEVERE: y', 'NullPointerException']) expect(lineLevel(t)).toBe('error')
  expect(lineLevel('WARN a')).toBe('warn'); expect(lineLevel('WARNING b')).toBe('warn')
  expect(lineLevel('INFO ERRORS=0')).toBeNull(); expect(lineLevel('WARN x Exception')).toBe('error') })
it('다시 연결 뒤 cutoff 이하 줄은 버린다(같은 ts 포함)')   // ts는 docker --timestamps의 9자리 고정·Z 형식('2026-11-03T01:02:03.000000001Z')이라 문자열 비교가 시각 순서와 같다
it('끝남 문구는 서버 message가 먼저', () => {
  expect(appLogEndText('time-limit', '30분이 지나 연결을 닫았습니다')).toBe('30분이 지나 연결을 닫았습니다')
  expect(appLogEndText('connection-lost', null)).toBe('연결이 끊겼습니다'); expect(appLogEndText('x', null)).toBe('앱 로그 연결이 끝났습니다') })
it('5000줄을 넘으면 오래된 줄부터 버리고 seq는 계속 늘어난다')
it('URL은 /api 밖, 토큰은 인코딩', () => expect(appLogStreamUrl(42, 'a b+c')).toBe('/stream/tasks/42/app-logs?access_token=a%20b%2Bc'))
it('파일명은 로컬 시각, 콜론 없음', () => expect(appLogFileName(42, new Date(2026, 10, 3, 14, 5, 9))).toBe('task-42-app-20261103-140509.log'))
it('복사·내려받기 본문은 [ts] 본문 줄')
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; npx vitest run composables/appLog.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - **설계와 다름 ①:** 프론트는 `active_fix_request_id`를 볼 수 없어 `fix?.activeRound`로 판정한다. 그래서 id는 있는데 요청 기록이 없는 경우에만 API(허용)와 화면(숨김)이 갈린다(`TaskFixService.java:193-204`). 화면이 더 엄격한 쪽이라 받아들인다.
  - **설계와 다름 ②:** 설계 §4의 "두 곳의 상태 목록은 시험 하나로 대조"를 다음 두 시험으로 잇는다. Java↔TS를 대조하는 선례는 없다.
    - 이 vitest가 Java 소스를 읽는다(`F/test/brand-assets.spec.ts:7-9` 선례, CI는 저장소 전체를 checkout한다 `.github/workflows/ci.yml:48`).
    - Task 2의 Java 시험이 JPQL을 읽는다.
- [ ] **Step 4: 통과 확인** — `npx vitest run composables/appLog.spec.ts composables/attachmentDownload.spec.ts` PASS
- [ ] **Step 5: 커밋** — `feat(applog): 앱 로그 판정·강조·줄 합치기 순수 함수와 API 목록 대조`

---

### Task 9: `useAppLogStream` — EventSource 상태 기계

**Files:**
- Create: `F/composables/useAppLogStream.ts` + `.spec.ts`
  - 수명주기 시험은 작은 Host 컴포넌트에 마운트해서 한다(`F/composables/useNowTick.spec.ts:9-30`).
  - 재연결 시험은 `F/composables/useInterviewStream.spec.ts:144-170`을 본뜬다.

**Interfaces:**
- Consumes: Task 8 `appLogStreamUrl`·`appendLines`·`AppLogViewLine`
- Produces:

```ts
export type AppLogConnState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'ended'
export function useAppLogStream(opts: { taskId: () => number | string; viewable: () => boolean; token: () => string | null }): {
  state: Ref<AppLogConnState>; endReason: Ref<string | null>; endMessage: Ref<string | null>; infoMessage: Ref<string | null>
  lines: ShallowRef<AppLogViewLine[]>; paused: Ref<boolean>; pendingCount: Ref<number>
  connect(): void; close(): void; pause(): void; resume(): void; clear(): void
}
```

- 규칙:
  - **`connect()` (사용자 호출):** 재시도 카운터를 0으로 되돌린 뒤 내부 연결을 부른다.
  - **내부 연결:**
    - 이전 연결을 먼저 닫는다(`useInterviewStream.ts:124-140` 선례).
    - `viewable()`이 false면 `idle`로 멈춘다.
    - 토큰이 없으면 `ended`(`auth`)로 끝낸다.
    - 연결할 때 그때까지 받은 ts 중 **최댓값**을 그 연결의 `cutoff`로 고정한다(첫 연결은 null, 연결 중에는 바뀌지 않는다). stdout·stderr를 두 스레드가 읽어 한 버퍼에 넣으므로 받은 순서가 ts 순서가 아니다. 그래서 마지막으로 받은 ts를 쓰면 다시 연결한 뒤 줄이 겹칠 수 있고, 연결 중에 cutoff를 올리면 늦게 온 stderr 줄이 버려진다.
    - `clear()`는 줄과 대기 버퍼만 비우고 `cutoff`는 그대로 둔다.
  - **이벤트별 처리:**
    - `open` → `open`
    - `lines` → `appendLines`로 붙인다. 멈춤 중이면 대기 버퍼(상한 5000)에 넣는다. 오류 재시도 횟수를 0으로 되돌린다.
    - `info` → `infoMessage`에 넣는다. 줄로는 넣지 않는다.
  - **`end`:** 먼저 `es.close()`한다. 그다음 reason별로 처리한다.
    - `time-limit`: `document.visibilityState === 'visible'`이면 바로 다시 연결한다. 아니면 `reconnecting`으로 두고, `visibilitychange`로 보이게 되면 연결한다.
    - `container-stopped`: `viewable()`이고 이번 마운트에서 2회 미만이면 3000ms 뒤 다시 연결한다. 아니면 `ended`.
    - `too-many`: 이번 마운트에서 처음이면 `reconnecting`으로 두고 25000ms 뒤 한 번만 다시 연결한다. 두 번째면 `ended`. 불안정한 망에서 끊긴 이전 API 스트림은 핑 2회(약 20초)가 지나야 자리를 내놓기 때문이다.
    - 그 밖: `ended`.
    - `ended`일 때는 `endReason`과 `endMessage`(서버 `message`, 클라이언트 전용 사유면 null)를 둔다.
  - **`onerror`:** 먼저 `es.close()`한다. 그다음 시도 n이 4 이하면 `Math.min(1000 * 2 ** (n - 1), 15000)`ms 뒤 다시 연결한다(`useInterviewStream.ts:300`). 기다리는 동안 `reconnecting`이다. 다섯 번째는 `ended`(`connection-lost`)다.
  - **정리:** `onBeforeUnmount`에서 닫고, 대기 중인 타이머와 `visibilitychange` 리스너를 지운다. 리스너는 `onMounted`에서 붙인다.

- [ ] **Step 1: 실패하는 시험 작성** — `describe('useAppLogStream (스펙 2026-11-03 §5 재연결)')`, `vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })`, `FakeEventSource`(`F/test/mocks/eventsource.ts`)

```ts
it('connect: /stream URL로 하나만 연다')
it('lines는 줄로 붙이고 info는 안내로')
it('end(not-allowed-status): 즉시 close, 사유, 10초 지나도 새 연결 없음')   // [RF5] instances.length 1, instances[0].readyState 2, state 'ended'
it('end(time-limit): 보이는 탭이면 바로 다시 연결하고, 다시 받은 tail 중 cutoff 이하는 버린다')
it('end(time-limit): 숨은 탭이면 visibilitychange까지 기다린다')   // [RF5] Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'hidden'});
                                                                     //   60초 지나도 instances 1 → 'visible' + dispatchEvent(new Event('visibilitychange')) → 2
it('end(container-stopped): 허용 상태면 3초 뒤 최대 2번, viewable false면 안 함')   // [RF5] instances ≤ 3
it('end(too-many): 25초 뒤 한 번만 다시 연결하고, 또 too-many면 끝남')            // [RF5] 24.9초에 instances 1, 25초에 2, 두 번째 too-many 뒤 60초 지나도 2, state 'ended'
it('cutoff는 받은 ts의 최댓값이고 clear해도 남는다')                               // 첫 연결 lines [ts3, ts1](역순) → 둘 다 붙음 → clear() → time-limit 재연결의 tail [ts1, ts3, ts4] 중 ts4만 붙음
it('onerror: 닫고 1·2·4·8초 뒤 다시, 다섯 번째 실패면 connection-lost')          // [RF5] instances 5, 모두 readyState 2, state 'ended'
it('lines를 받으면 오류 재시도 횟수가 처음부터')
it('토큰이 없으면 연결하지 않고 auth')
it('멈춤 중 모은 줄은 재개 때 붙고 pendingCount 0')
it('언마운트하면 닫고 대기 중인 재연결도 취소한다')   // vi.getTimerCount() === 0
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; npx vitest run composables/useAppLogStream.spec.ts` → FAIL
- [ ] **Step 3: 구현** — 줄 배열은 `shallowRef`에 새 배열을 대입한다.
  - 재연결 횟수(2회·3초, 4회 백오프)는 설계 §5의 "한두 번만"·"몇 번까지만"을 이 값으로 정한 것이다.
  - **설계와 다름(보탬):** 설계 §5는 `too-many`를 "그 밖의 end"(멈춤)로 둔다. 혼자 보는데도 망이 불안정해 이전 스트림이 자리를 쥐고 있을 수 있어 한 번만 25초 뒤 다시 시도한다.
  - JWT는 갱신되지 않는다(갱신 액션이 없고 `refreshToken`은 저장만 한다, `F/stores/auth.ts:32,125`). 그래서 다시 연결할 때마다 `token()`을 새로 읽어 URL을 다시 만든다. 30분 상한 뒤 예전 토큰으로 다시 붙으면 401이 나기 때문이다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(applog): 앱 로그 EventSource 상태 기계와 재연결 규칙`

---

### Task 10: `AppLogCard.vue`

**Files:**
- Create: `F/components/tasks/AppLogCard.vue` + `F/components/tasks/AppLogCard.spec.ts`

**Interfaces:**
- Consumes: Task 8(`lineLevel`·`formatLogTime`·`appLogText`·`appLogFileName`·`appLogEndText`·`saveBlob`), Task 9, `useAuthStore().accessToken`(`ApproveDialog.vue:68` 선례)
- Produces:
  - `defineProps<{ taskId: number; viewable: boolean }>()` — 마운트되면 `connect()`를 부른다. 언마운트되면 Task 9가 닫는다. 부모가 펼칠 때만 마운트한다.
  - data-test: `app-log-status`, `app-log-follow`, `app-log-pause`, `app-log-clear`, `app-log-errors-only`, `app-log-copy`, `app-log-download`, `app-log-reconnect`, `app-log-info`, `app-log-lines`, `app-log-line`
  - 줄 클래스: `app-log-line--error`, `app-log-line--warn`, `app-log-line--err`(stderr 표식)

- [ ] **Step 1: 실패하는 시험 작성** — `describe('AppLogCard (스펙 2026-11-03 §5)')`

```ts
it('마운트하면 연결, 언마운트하면 닫는다')
it('[시각] 본문, stderr 표식, 오류·경고 클래스')
it('본문은 HTML로 해석하지 않는다')   // text '<img src=x onerror=alert(1)>' → w.find('img').exists() false, 텍스트 그대로
it('오류 줄만 토글')
it('위로 스크롤하면 따라가기가 꺼지고, 켜면 맨 아래로')   // app-log-lines에 scrollHeight 1000·clientHeight 200을 defineProperty, scrollTop 100 + scroll 이벤트 → follow off
it('멈춤 중에는 줄이 늘지 않고 재개하면 붙는다')
it('지우기: 줄을 비우고, 다시 연결 뒤 받은 tail 중 이미 본 ts는 다시 나오지 않는다')   // app-log-clear 클릭 → app-log-line 0개 → end(time-limit) 뒤 새 인스턴스의 lines [본 ts, 새 ts] → 1개
it('상태 표시: 연결됨·다시 연결 중·끝남 — 사유')   // open → app-log-status에 '연결됨'; onerror → '다시 연결 중';
                                                   //   end{reason:'not-allowed-status', message:'지금 상태에서는 앱 로그를 볼 수 없습니다'} → '끝남 — 지금 상태에서는 앱 로그를 볼 수 없습니다'
it('복사는 보이는 줄만, 클립보드가 없으면 경고')        // MarkdownViewerDialog.spec.ts:19-26 모양
it('내려받기 파일명과 내용')                           // URL.createObjectURL 바꿔 끼우기(attachmentDownload.spec.ts:5-18), Blob 텍스트 == appLogText(보이는 줄)
it('끝남 사유와 다시 연결 버튼 — 누르면 새 연결')
it('소스에 v-html이 없다')                             // readFileSync(AppLogCard.vue)에 'v-html' 없음
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; npx vitest run components/tasks/AppLogCard.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - **표시:**
    - 줄은 `{{ }}` 보간으로만 그린다.
    - 스타일: `white-space: pre-wrap; overflow-wrap: anywhere`, 고정폭 글꼴, 스크롤 영역 `max-height: 360px`.
    - `v-for` key는 `seq`다.
  - **따라가기:**
    - `useAutoScroll`의 smooth를 쓰지 않는다. 300ms마다 줄이 붙으면 애니메이션 중 스크롤 판정이 따라가기를 끈다(`F/composables/useAutoScroll.ts:31-41`).
    - 그래서 따로 `follow` ref(기본 켬)를 두고, 바닥에서 80px 넘게 떨어지면 끈다.
    - 켤 때와 새 줄이 올 때는 `nextTick` 뒤 `scrollTop = scrollHeight`로 바로 내린다.
  - **도구 줄:**
    - 상태 점: `connecting` → 연결 중, `open` → 연결됨, `reconnecting` → 다시 연결 중, `ended` → `끝남 — {appLogEndText(endReason, endMessage)}`
    - 따라가기, 멈춤·재개, 지우기, 오류 줄만, 복사, 내려받기
  - **복사:** 실패하면 `$q.notify({ type: 'warning', message: '이 브라우저에서는 복사를 지원하지 않습니다' })`를 띄운다(`MarkdownViewerDialog.vue:13-21`). LAN의 http 주소에서는 이 경로로 빠진다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 PASS
- [ ] **Step 5: 커밋** — `feat(applog): 앱 로그 카드 — 강조·따라가기·멈춤·복사·내려받기`

---

### Task 11: 화면 배치 — 데스크톱·모바일 앱 로그, 모바일 빌드 로그

**Files:**
- Modify: `F/pages/tasks/[id].vue`
  - 스크립트: 빌드 로그 블록(`:429-462`) 옆에 `appLogVisible`·`appLogOpen`과 watch
  - 데스크톱 배포 카드: 환경변수 칸(`:1388-1396`) 뒤에 앱 로그 칸. 배포완료의 정적 "배포 로그"(`:1410-1415`)는 그대로 둔다.
  - `TaskDetailMobile` 사용(`:870-895`): `:live-log`, `:app-log-viewable`, 슬롯 `#applog`
  - 스타일: `.deploy-log`(`:1566-1574`, scoped)를 `F/assets/css/main.css`로 옮긴다.
- Modify: `F/components/tasks/TaskDetailMobile.vue`
  - props(`:45-51`): `liveLog?: string`, `appLogViewable?: boolean`
  - open(`:58-62`): `applog: false, buildlog: false`
  - 배포 칸(`:195-215`) 뒤에 칸 두 개
- Modify: `F/assets/css/main.css`
- Test: `F/test/task-detail-app-log.spec.ts` — 하네스는 `F/test/task-detail-fix.spec.ts:11-41`, 모바일은 `F/test/task-detail-mobile.spec.ts:10-19` + `setViewportWidth(390)`
  - 그대로 통과해야 하는 시험: `task-detail-close.spec.ts`, `task-detail-fix.spec.ts`, `task-detail-mobile.spec.ts`(섹션 목록 `:41-43`, aria `:177-191`)

**Interfaces:**
- Consumes: Task 8 `appLogViewable`, Task 10 `AppLogCard`
- Produces:
  - `[id].vue`
    - `const appLogVisible = computed(() => appLogViewable(task.value))`
    - `const appLogOpen = ref(false)` + `watch(appLogVisible, (v) => { if (!v) appLogOpen.value = false })`
    - 데스크톱: `q-expansion-item v-model="appLogOpen" icon="terminal" label="앱 로그" data-test="app-log-desktop"`. 안쪽은 `<AppLogCard v-if="appLogOpen" :key="task.id" …>`로 게이트한다(CLAUDE.md q-expansion-item 주의, `:1509-1510` 선례).
    - 모바일 슬롯: `<template v-if="appLogVisible" #applog><AppLogCard :key="task.id" :task-id="task.id" :viewable="appLogVisible" /></template>`
  - `TaskDetailMobile`
    - 칸 `section-applog`: `appLogViewable`일 때만 보인다. 본문은 `<slot name="applog" />`이고, 칸의 `v-if="open.applog"` 안에서만 마운트된다.
    - 칸 `section-buildlog`: 상태가 `DEPLOYING|UNDEPLOYING`일 때만 보인다. 본문은 `<pre class="deploy-log">{{ liveLog || '로그 대기 중…' }}</pre>`이다.
    - 칸이 숨겨지면 그 `open` 키를 false로 되돌린다.
    - 칸 순서: 배포 → 앱 로그 → 빌드 로그 → 지난 인터뷰 → 정보. 정보 칸은 마지막에 둔다(`:264` 테두리 규칙).

- [ ] **Step 1: 실패하는 시험 작성** — `describe('작업 상세 앱 로그 (스펙 2026-11-03 §5)')`

```ts
it('데스크톱: 배포완료면 "앱 로그"가 보이고 펼치기 전에는 연결이 없다')   // '/stream/' 인스턴스 0 → app-log-desktop 클릭 → 1, url startsWith '/stream/tasks/42/app-logs?access_token='
it('수정 회차 구현중·종료승인대기에서도 보이고, deployment가 없으면 숨는다')
it('재배포로 배포중이 되면 앱 로그를 닫고 빌드 로그가 열린다')   // refresh → DEPLOYING(task-detail-fix.spec.ts:498-515 모양):
                                                                  //   '/stream/' 인스턴스 readyState 2, '/api/tasks/42/logs/stream' 새 인스턴스
it('모바일 390px: 앱 로그 칸은 접힌 채, 펼쳐야 연결')
it('모바일: 숨겨졌다 다시 보여도 저절로 연결하지 않는다')     // 펼침 → DEPLOYING → DEPLOYED: '/stream/' 인스턴스 수 그대로 1, section-applog aria-expanded "false"
it('모바일: 배포중이면 빌드 로그 칸, 펼치면 부모 liveLog')     // '/api/tasks/42/logs/stream' 인스턴스에 emit('log','step 1') → 칸의 pre에 'step 1'
it('데스크톱·모바일: 접으면 /stream 연결을 닫는다')            // 각 폭에서 펼침 → '/stream/' 인스턴스 1 → 같은 머리를 다시 눌러 접음 → 그 인스턴스 readyState 2, 새 인스턴스 없음(설계 완료 조건 "카드를 접은 뒤")
it('데스크톱 빌드 로그는 그대로 .deploy-log')
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; npx vitest run test/task-detail-app-log.spec.ts` → FAIL
- [ ] **Step 3: 구현** — 빌드 로그 연결 구조(`:429-462`)는 바꾸지 않는다. 부모가 이미 연 `liveLog`를 prop으로 넘길 뿐이다(설계 §5).
  - 폴링이 task 객체를 통째로 바꾸므로 watch는 값에서 뽑은 키로 건다(`[id].vue:169-184` 선례).
  - 데스크톱 카드 표시 조건(`:1276-1281`)은 그대로 둔다. 허용 상태이면 늘 `deployment`나 열린 회차가 있어 카드가 이미 보인다.
- [ ] **Step 4: 통과 확인** — `Set-Location <W>\frontend; npx vitest run` 전체 PASS
- [ ] **Step 5: 커밋** — `feat(applog): 작업 상세 앱 로그 칸(데스크톱·모바일)과 모바일 빌드 로그`

---

### Task 12: 문서·배포 순서·마지막 검증·라이브 확인

**Files:**
- Modify: `CLAUDE.md`
  - "원격 Docker 배포 호스트" 절 아래에 단락 하나를 쓴다. 내용:
    - API가 docker CLI를 직접 실행한다는 점과 그 전제(같은 PC·같은 `public.env`)
    - 허용 상태
    - 경로 `/stream/tasks/{id}/app-logs` → `/api/tasks/{id}/app-logs/stream`, 그리고 `/api/**` 밖에 둔 이유
    - 상한·핑·설정 키, 기동 로그 문구, 가리기 순서
    - `TestcontainersConfig` 차단, 배포 순서
  - "자주 보는 코드" 표에 한 줄을 넣는다: `service/applog/*`, `controller/TaskAppLogController.java`, `util/SecretMasks.java`·`ProcessTrees.java`, `frontend/server/{utils/appLogProxy.ts,routes/stream/…}`, `composables/appLog.ts`·`useAppLogStream.ts`, `components/tasks/AppLogCard.vue`

- [ ] **Step 1: 전체 시험**
  - `Set-Location <W>; .\gradlew.bat test` → PASS(Testcontainers는 건너뜀)
  - `Set-Location <W>\frontend; npx vitest run; npx nuxi prepare; npm run typecheck` → PASS, 오류 0
- [ ] **Step 2: 하이드레이션 스모크** — Chrome이 없으면 `$env:SMOKE_BROWSER_CHANNEL='msedge'`를 쓴다. `Set-Location <W>\frontend; $env:NUXT_IGNORE_LOCK='1'; npm run build; Remove-Item Env:NUXT_IGNORE_LOCK; npm run test:hydration` → 경고·예외 0, `/stream` 직접 확인 2건 통과, "목 응답이 없어 null로 답한 API" 없음
- [ ] **Step 3: lint** — `Set-Location <W>\frontend; npm run lint` → 오류 0, 경고 수가 `npm ci` 직후 기준선 이하
- [ ] **Step 4: CLAUDE.md** — Files에 적은 내용을 쓴다.
- [ ] **Step 5: PR 본문** — 실행자는 배포하지 않는다. 배포는 사용자 승인 뒤 운영자가 한다. 다음을 적는다.
  - **배포 순서**(설계 §6): ① API → ② 프론트(`.output` 재빌드). 인터뷰 서비스는 바꾸지 않고 마이그레이션도 없다. `CloseNoteWriter` 변경(동작 동일)은 이 PC 워커가 다음에 재기동될 때 반영된다(라이브 체크아웃 `bootRun`). 원격 워커는 종료를 받지 않아 무관하다.
  - lint 경고 기준선과 이 브랜치의 경고 수(Step 3).
  - **남은 위험:**
    - JVM이 `taskkill /T` 없이 죽으면 `docker logs -f`·ssh가 컨테이너가 멈출 때까지 남는다(정지 스크립트의 고아 정리는 claude만 대상, `scripts/stop-public.ps1:58-74`).
    - 재배포를 누른 뒤 옛 컨테이너가 `rm -f`될 때까지 서버 쪽 스트림은 이어진다. 이를 닫는 것은 화면의 5초 폴링, follow 종료, 30분 상한이다.
    - env로 주지 않은 비밀은 가려지지 않는다(설계 §9).
- [ ] **Step 6: 커밋** — `docs(applog): 앱 로그 실시간 보기와 배포 순서`
- [ ] **Step 7: CI 확인** — 사용자 승인 뒤 draft PR을 push한다.
  - CI `Backend`·`Frontend`·`Interview service` 잡이 모두 성공해야 한다. `Backend`는 `RUN_TESTCONTAINERS: 'true'`이므로(`.github/workflows/ci.yml:22`) `TaskApiIntegrationTest.앱로그_스트림은_등록자와_관리자만`과 `@Autowired` 생성자가 걸린 빈의 기동도 여기서 처음 확인된다.
  - PR 본문에 run id를 적는다.
- [ ] **Step 8: 배포 준비** — PR이 병합되고 사용자가 배포를 승인한 뒤 운영자가 한다.
  1. **라이브 체크아웃 올리기:** `git -C C:\Users\mic\NetisMaker\commanCenter pull --ff-only` → `git -C C:\Users\mic\NetisMaker\commanCenter merge-base --is-ancestor <병합 커밋> HEAD`가 성공(종료 코드 0)해야 한다. 옛 코드로 재기동하면 `앱 로그:` 줄이 아예 없고, Step 9.3의 `APP_LOG_DOCKER_PATH` 우회로 잘못 빠진다.
  2. **이미 반영됐는지:** 여러 세션이 같은 체크아웃을 쓴다. `git -C C:\Users\mic\NetisMaker\commanCenter reflog show main --date=iso`(pull 시각) → `build\classes` 산출물 시각 → `.run\api.log`의 `Started` 시각과 `앱 로그:` 줄을 본다. 모두 병합 뒤면 API는 재기동하지 않는다(memory `live-stack-launch-via-scheduled-task`).
  3. **진행 중 작업 없음:** `$env:PGPASSWORD=<public.env의 SPRING_DATASOURCE_PASSWORD>; & 'C:\Users\mic\pgsql\bin\psql.exe' -h localhost -U postgres -w -d postgres -c "select id, status from com.task where status in ('배포중','배포중지중','구현중')"` → 0행. 있으면 끝날 때까지 기다린다.
- [ ] **Step 9: 배포** — Step 8을 마친 뒤 라이브 체크아웃에서 한다.
  - **API:**
    1. `Set-Location C:\Users\mic\NetisMaker\commanCenter; .\scripts\stop-public.ps1 -Only api; Start-ScheduledTask -TaskName netisMaker-public`
    2. `.run\api.log`에 `앱 로그: 켜짐(docker=…docker.exe, DOCKER_HOST=원격)`이 있는지 본다.
    3. `꺼짐(docker 없음)`이면 `public.env`에 `APP_LOG_DOCKER_PATH=C:\Users\mic\.local\bin\docker.exe`를 넣고 API만 다시 재기동한다.
  - **프론트:**
    1. `.\scripts\stop-public.ps1 -Only front`
    2. `Set-Location frontend; $env:NUXT_IGNORE_LOCK='1'; npm run build; Remove-Item Env:NUXT_IGNORE_LOCK`
    3. `Start-ScheduledTask -TaskName netisMaker-public` — 3001이 비어 있으면 front만 뜬다(`scripts/start-public.ps1:259-282`).
- [ ] **Step 10: 라이브 확인** — 반드시 `https://app-win.micthebick.dev`(Cloudflare → cloudflared → Nitro 3001)로 한다. dev나 LAN 직결로 본 것은 Nitro 끊김 전달을 확인한 것이 아니다(설계 §2). 결과는 PR 본문과 진행상황 노트에 남긴다.
  1. **기준선:** 배포완료이면서 로그가 조용한 작업 하나를 고른다. 두 PC의 프로세스를 두세 번 세어 기준선으로 적는다(워커 정합·GC가 짧게 띄우는 것과 구별하려는 것).
     - API PC: `Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'docker.exe','ssh.exe' } | Select-Object ProcessId,Name,CommandLine`
     - 10.1.1.75: Windows PowerShell 5.1은 네이티브 `ssh`에 넘기는 인자의 안쪽 `"`를 이스케이프하지 않아 인자가 쪼개진다. 그래서 `-EncodedCommand`로 넘긴다.
       - `$c = 'Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match ''dial-stdio'' } | Select-Object ProcessId,Name,CommandLine'; $e = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($c)); ssh Administrator@10.1.1.75 "powershell -NoProfile -EncodedCommand $e"`
  2. **켜기:** 상세에서 "앱 로그"를 펼친다.
     - 최근 줄이 나오고, 앱에 요청을 보내면 새 줄이 붙어야 한다.
     - API PC에 `docker.exe logs --tail 500 --timestamps --follow netis-task-{id}` 1개와 `ssh.exe … dial-stdio` 1개, 10.1.1.75에 `dial-stdio` 1개가 늘어야 한다.
  3. **ts 형식(확인만):** 개발자 도구 Network의 `app-logs` EventStream에서 `ts`가 `YYYY-MM-DDTHH:mm:ss.nnnnnnnnnZ`(고정 9자리, docker의 RFC3339NanoFixed)인지 본다. 어긋나면 실패로 PR에 적고 멈춘다(라이브 확인 중에 코드를 고치지 않는다).
  4. **끊기(완료 조건):** 카드를 접은 뒤 20초 안에 두 PC 모두 1의 수로 돌아와야 한다. 탭을 닫는 경우도 같다. 30초 넘게 남으면 실패로 보고 `.run\api.log`·`.run\front.log`를 남긴다.
  5. **상한:** 같은 작업을 탭 4개로 열면 4번째는 "다시 연결 중"으로 25초 뒤 한 번 더 시도한 뒤 `끝남 — 동시에 볼 수 있는 앱 로그 수를 넘었습니다`가 떠야 한다. 확인 뒤 탭을 닫고 4를 다시 본다.
  6. **모바일:** 폰에서 2·4를 반복한다. 배포중 작업이 있으면 모바일 빌드 로그 칸도 본다.
  7. **API 재기동:** 라이브 API를 한 번 더 내리므로 Step 8.3(진행 중 작업 없음)을 다시 확인하고, 사용자에게 미리 알린 뒤 한다. 스트림을 연 채 `.\scripts\stop-public.ps1 -Only api`를 실행한다. 두 PC에 그 docker·ssh·`dial-stdio`가 남지 않아야 한다(`taskkill /T`). 확인 뒤 `Start-ScheduledTask -TaskName netisMaker-public`으로 API를 다시 띄운다.

