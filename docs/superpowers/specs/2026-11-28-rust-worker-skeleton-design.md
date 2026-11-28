# Rust 워커 1단계(뼈대 + 분석 경로) — 설계 (2026-11-28, 승인됨)

## 의도

워커 데몬을 Rust로 옮겨 보는 시범의 첫 단계다. 시범의 이유는 사용자가 밝힌 세 가지 — 메모리 확보, 속도, 다른 서비스에 Rust를 들이기 전 시험.
전체 범위는 단계로 나눈다(사용자 선택, 2026-11-28): **1단계 뼈대 + 분석 경로 → 2단계 배포·배포 중지 → 결과를 보고 종료·구현·디자인 여부 결정**.
이 문서는 1단계만 다룬다. 1단계가 끝나면 Rust 워커가 Java 워커 옆에서 (스위치를 켰을 때) 떠 있고, API와의 통신 계약·git 캐시·claude 실행·보고 유실 대책이 Rust로 검증돼 있으며, 2단계로 갈지 판단할 측정값이 있다.

숨기지 않을 사실 세 가지:

- **분석(ANALYSIS) 경로는 현재 운영에서 도달할 수 없다.** 작업 등록은 승인대기 → 인터뷰(별도 TS 서비스)로 가고, 워커의 분석 큐(`작업대기`)는 `분석실패` 작업을 재시도할 때만 생긴다(`CLAUDE.md` "현재 프로덕션에서 도달 불가능"). 1단계에서 분석은 가장 짧은 끝-끝 경로(claim → git → claude → 파싱 → 보고)라서 고른 **시험용 레일**이고, 실제 일감 검증은 2단계(배포)부터다.
- **메모리 이득은 1단계에 없다.** 상한은 Java 워커 JVM 약 315MB이고 Java 워커를 완전히 내릴 때만 얻는다. 1단계에서 켜면 Rust 프로세스만큼 오히려 늘어난다(목표: 30MB 이하).
- **속도 이득은 거의 없다.** 작업 시간은 claude·git·docker·maven 하위 프로세스가 좌우한다. Rust가 줄이는 것은 기동 시간(초 단위 → 즉시)과 상주 메모리뿐이다.

## 목표 / 비목표 / 반목표

- 목표
  1. `worker-rs/` Rust 프로그램: 하트비트, 기동 알림, 폴링, 분석 작업 처리, 결과 보고 재시도와 유실 보고 재전송, claude 로그인 판정.
  2. Java와 Rust가 같은 계약 파일을 보게 해서 한쪽만 바뀌면 CI가 실패한다.
  3. 라이브 기동·정지 스크립트에 `worker-rs` 서비스를 넣되 **기본은 꺼짐**(스위치를 켜야 뜬다).
  4. 측정값과 2단계 진행 판단 기준을 남긴다.
- 비목표: 배포·배포 중지·종료·구현·디자인 처리, PC 단위 주기 작업(사용량 수집·worktree 정리·레포 캐시 정리·Docker GC·배포 정합 — Java 워커가 계속 한다), HTTPS(API 주소는 `http://`만), 원격 워커 2대와 macOS, Java 워커 리팩터링, API 동작 변경(시험 코드만 추가).
- 반목표
  - Rust 워커가 처리할 줄 모르는 종류의 작업을 claim하는 것.
  - 비밀값이 claude 자식 프로세스의 env나 로그·실패 사유에 나가는 것.
  - 스위치가 꺼져 있는데 라이브 동작이 달라지는 것.
  - 같은 상수(프롬프트·비밀 env 이름·상태 이름)를 두 언어에 따로 적어 조용히 어긋나는 것.

## 접근

범위(단계적)는 사용자가 골랐다. 아래는 만드는 방법에 대한 결정으로, 사용자가 맡긴 부분이라 모두 작성자의 판단이다.

| 결정 | 선택 | 기각한 것과 이유 |
|---|---|---|
| 동시성 모델 | 표준 스레드 + 동기 HTTP. 워커는 작업을 한 번에 하나만 처리하고 5초마다 폴링하므로 비동기 런타임이 필요 없다. 비동기(axum·tokio)는 B(사용량 서비스)에서 시험한다 | tokio 기반 — 바이너리·메모리·복잡도만 늘고 이득이 없다 |
| Windows 툴체인 | `x86_64-pc-windows-gnu`(rustup, 링커 포함) + C 코드가 없는 크레이트만 | MSVC — Visual Studio Build Tools 설치(수 GB)와 사내 사용 라이선스 확인이 필요하다 |
| 계약 맞추기 | Java 시험이 실제 DTO·상수를 JSON·텍스트 파일로 고정(골든 파일)하고 Rust가 같은 파일을 시험·내장에 쓴다 | 손으로 양쪽에 적기 — 어긋나도 모른다. OpenAPI 생성 — API에 스펙이 없고 워커 엔드포인트만을 위해 들이기엔 크다 |
| 라이브 반영 | 시작 스크립트에 넣되 `WORKER_RS_ENABLED=true`일 때만 기동 | 라이브에 아예 안 넣기 — 기동·정지 통합과 실제 조건의 상주 메모리를 못 잰다. 항상 켜기 — 병합만으로 라이브가 바뀐다 |
| 코드 위치 | 같은 레포 `worker-rs/` | 포크 — 공개 레포의 포크는 비공개로 못 만들고 계약 파일을 한 PR에서 같이 못 고친다 |

## 설계

**기준**: 아래 절에서 "Java와 같게"라고 한 동작은 이 문서의 요약과 Java 코드가 다르면 **Java 코드가 기준**이다(요약은 길잡이). 예외는 "Java와 다르게"라고 명시한 곳뿐이다.

### 1. 프로그램 구조

`worker-rs/`(Cargo 패키지 `netis-worker-rs`, 실행 파일 `netis-worker-rs.exe`). `rust-toolchain.toml`로 버전을 고정한다(std `File::try_lock`이 있는 1.89 이상).
의존성은 순수 Rust만: serde·serde_json, 동기 HTTP 클라이언트(TLS 기능 끔), regex, 시각 처리 하나. 추가는 빌더 재량이되 C 컴파일러가 필요한 크레이트는 금지.

스레드 셋:

- **폴링 스레드**: 기동 알림이 성공할 때까지 5초마다 재시도 → 이후 5초마다 claim, 받으면 그 자리에서 끝까지 처리(처리 중에는 폴링 안 함). 밀린 실행을 몰아서 하지 않는다(처리 후 5초 쉼).
- **하트비트 스레드**: 10초마다. 실패는 경고만.
- **관리 스레드**: claude 로그인 판정(기본 300초, `CLAUDE_AUTH_CHECK_INTERVAL_SECONDS`), 유실 보고 재전송(60초).

### 2. 설정(env)

Java 워커와 같은 이름을 쓴다: `WORKER_ID`, `API_BASE_URL`, `WORKER_API_KEY`, `WORKER_KINDS`, `WORKER_VERSION`, `REPOS_DIR`(기본 `~/netis-maker/repos`), `DEAD_LETTER_DIR`(기본 `~/netis-maker/dead-letter`), `CLAUDE_CLI`, `GITHUB_PAT`, `GITLAB_TOKEN`. 고정값: 폴링 5초, 하트비트 10초, 분석 상한 10분, git 상한 600초, 레포 잠금 대기 60초.

기동 때 검사하고 어기면 `worker-rs fatal: <이유>`를 찍고 0이 아닌 코드로 끝난다:

- `WORKER_ID`·`API_BASE_URL`·`WORKER_API_KEY`가 비어 있다. `WORKER_ID`나 `WORKER_VERSION`이 50자를 넘는다(하트비트의 `hostname`은 100자로 자른다).
- `API_BASE_URL`이 `http://`로 시작하지 않는다.
- **`WORKER_KINDS`에 이 프로그램이 구현하지 않은 종류가 있다**(1단계 구현 집합 = `{ANALYSIS}`). 비어 있으면 "전부"가 아니라 구현 집합 전체로 본다. **claim 요청에는 항상 `kinds=`를 붙인다** — 빈 값은 서버에서 "전부"라는 뜻이라 절대 생략하지 않는다.
- claude 실행 파일을 못 찾는다(`CLAUDE_CLI` → PATH의 `claude`·`claude.exe` → `~/.local/bin/claude[.exe]`).

### 3. API 통신

모든 요청에 `X-Worker-API-Key`. JSON은 camelCase, enum은 상수 이름, 모르는 응답 필드는 무시, 요청의 null 필드는 생략해도 된다.

| 호출 | 내용 |
|---|---|
| `POST /worker/heartbeat` | `workerId, hostname, version, claudeSessionOk, vpnStatus(null), mcps, lostReportCount, claudeAuthMethod, claudeAuthExpiresAt(ISO-8601 문자열 또는 null), deadReportCount`. `mcps`는 §6의 서버 이름 목록. 로그인 세 칸은 §6-1, 첫 판정 전에는 null |
| `POST /worker/startup?workerId=` | 본문 `{pendingTaskIds}`(유실 보고 파일에 남은 작업 번호). 200 본문·빈 본문·404 모두 "알림 끝"으로 본다. 그 밖(연결 오류·5xx·404가 아닌 4xx — 키가 틀린 401·403 포함)은 다음 폴링에 재시도하고 그동안 claim하지 않는다 |
| `GET /worker/next-task?workerId=&kinds=ANALYSIS` | 204면 일감 없음. `supports`는 1단계에서 보내지 않는다(분석 큐에는 걸리지 않는다) |
| `POST /worker/tasks/{id}/result` | 성공: `workerId, status=COMPLETED, markdownResult, subtasksJson, claudeLog, durationMs, usage`. 실패: `workerId, status=FAILED, failureReason, usage(있으면)` |

받은 작업의 `kind`가 `ANALYSIS`가 아니면(서버가 걸러 주므로 생기지 않아야 한다) 처리하지 않고 오류 로그만 남긴다 — 서버의 정체 회수가 되돌린다.

### 4. 분석 처리(Java `processAnalysis`와 같은 순서·같은 문구)

1. **레포 준비**(§5). 실패 → `레포 fetch 실패: <사유>`.
2. **프롬프트**: 분석 템플릿의 `{github_repo}`, `{github_branch}`, `{commit_sha}`, `{title}`, `{description}`을 단순 치환.
3. **claude 실행**(§6), 상한 10분. 실행 자체 실패 → `claude exec 실패: <사유>`. 종료 코드가 0이 아니면 → `claude exit=<N>\n<출력 끝 2000자>`(잘랐으면 앞에 `…`).
4. **파싱**(§7). 실패 → `파싱 실패: <사유>`.
5. **보고**(§8).

처리 중 예상 못 한 오류는 `처리 중 예외: <종류>: <메시지>`로 실패 보고한다. 프로세스는 죽지 않는다.

### 5. git 캐시(Java `GitRepoCache.ensureFresh`와 호환)

Java 워커와 **같은 캐시 폴더를 같이 쓴다**. 그래서 아래를 그대로 지킨다.

- 레포 식별: `gitUrl`이 비면 `https://github.com/<githubRepo>.git`. 호스트가 `github.com`이면 GitHub, 아니면 GitLab. 폴더 키는 GitHub `owner/repo`, GitLab `_gitlab/<경로의 / 를 + 로>`.
- **잠금**: `<REPOS_DIR>/<키>.lock` 파일에 배타 잠금(500ms 간격, 60초까지 → `repo lock timeout (60s): <키>`). 잠금을 잡은 직후 `<키>.last-used`에 현재 시각을 쓴다(Java 쪽 캐시 정리 작업이 이 두 파일을 본다). 잠금 파일은 지우지 않는다. 잠금은 준비가 끝나면 풀고 claude는 잠금 없이 돈다.
- 처음: `git -c credential.helper= clone --depth=1 --branch <브랜치> <인증 URL> <폴더>` → `git remote set-url origin <평문 URL>`.
- 이후: `git remote set-url origin <평문 URL>` → `git -c credential.helper= fetch --prune <인증 URL> +refs/heads/<브랜치>:refs/remotes/origin/<브랜치>` → `git checkout --force --detach origin/<브랜치>`.
- `git rev-parse HEAD`로 커밋 번호. **Java와 다르게** 종료 코드가 0이 아니거나 출력이 비면 실패로 본다(Java는 빈 값을 프롬프트에 넣을 수 있다 — 복제하지 않는다).
- 인증 URL: 토큰이 있으면 `https://` 뒤에 `oauth2:<토큰>@`. 토큰은 GitHub `GITHUB_PAT`, GitLab `GITLAB_TOKEN`. 인증 URL은 명령 인자로만 쓰고 `.git/config`에 남기지 않는다.
- 마스킹: `://` 바로 뒤의 `<사용자>:<비밀>@`를 `***@`로. 실패 메시지(`git failed (<코드>): <출력>`)와 로그, 그리고 보고 본문의 모든 자유 텍스트에 적용한다.
- git 상한 600초, 넘으면 프로세스 트리를 죽이고 `process timeout (600s): <가린 명령>`.

**잠금 호환 확인(구현 초기에 실측)**: Windows에서 Java `FileChannel.tryLock()`이 잡은 파일을 Rust가 못 잡고, 그 반대도 성립하는지 실제 두 프로세스로 확인한다. 성립하지 않으면 멈추고 보고한다(캐시를 따로 쓰는 안으로 설계를 바꿔야 한다). 유닉스에서는 두 구현의 잠금 방식이 달라 호환되지 않을 수 있다 — 1단계 범위 밖이고 문서에만 적는다.

### 6. claude 실행(Java `ClaudeExecAdapter`·`WorkerMcpSupport`와 같게)

- 명령: `<claude> -p --output-format json --setting-sources project [--model <m>] [--effort <e>] [--mcp-config <경로> --strict-mcp-config --allowedTools mcp__<a>,mcp__<b>,…]`. 분석은 권한 건너뛰기 플래그를 쓰지 않는다. `model`·`effort`가 비면 생략.
- 프롬프트는 stdin(UTF-8)으로 쓰고 닫는다. 작업 폴더는 캐시 체크아웃.
- **자식 env**: 워커 env에서 비밀 이름 목록(계약 파일, §9)을 대소문자 무시로 뺀다. `CLAUDE_*`는 남긴다.
- stdout·stderr는 따로 UTF-8로 읽는다. 상한을 넘으면 프로세스 트리를 죽이고(Windows `taskkill /PID <pid> /T /F`) `claude timeout after PT10M`(Java `Duration` 표기 그대로).
- 결과 해석: stdout이 JSON 객체이고 (`total_cost_usd`가 숫자이거나 `usage`가 객체)이고 `result`가 문자열일 때만 `result`를 결과 텍스트로 쓰고, 사용량은 `modelUsage` 합(비었으면 `usage.*`)과 `total_cost_usd`. 그 밖에는 출력 전체가 결과 텍스트이고 사용량 없음. `is_error`는 보지 않는다. 종료 코드가 0이 아니고 stderr가 있으면 `\n[stderr]\n<끝 2000자, 잘랐으면 앞에 …>`를 붙인다.
- **MCP**: 기동 때 `~/.claude.json`의 최상위 `mcpServers`와 `projects.*.mcpServers`를 합쳐(같은 이름은 나중 것) `<REPOS_DIR의 형제>/worker-mcp-<WORKER_ID>.json`에 쓴다. 서버가 0개면 건너뛴다. 작업의 `mcpsExtra`가 있으면 그 위에 `{type: <transport 칸>, url, headers?}`를 이름 키로 덮은 임시 파일을 만들어 쓰고 실행 뒤 지운다. 서버가 하나도 없고 추가분도 없으면 MCP 인자 셋을 모두 뺀다. 합본 만들기가 실패하면 경고만 남기고 MCP 없이 계속한다.

### 6-1. claude 로그인 판정(Java `ClaudeAuthProbe`와 같게)

관리 스레드가 기동 직후와 이후 주기마다 판정해 하트비트의 `claudeSessionOk`·`claudeAuthMethod`·`claudeAuthExpiresAt`에 싣는다. 순서: env `CLAUDE_CODE_OAUTH_TOKEN`이 있으면 `ENV_TOKEN` → `{CLAUDE_CONFIG_DIR 또는 ~/.claude}/.credentials.json`의 `claudeAiOauth`(만료 시각 포함)면 `CREDENTIALS` → `claude auth status --json`의 `loggedIn`이면 `CLI`. 판정하지 못하면 세 칸 모두 null. 읽기만 하므로 Java 워커와 겹쳐 돌아도 된다.

### 7. 결과 파서(Java `PromptResultParser`와 같게)

- 빈 출력 → `출력이 비어있음`. `## 1.`·`## 2.`·`## 3.` 섹션 중 하나라도 없으면 `섹션 ## N. 누락`.
- `## 4.` 본문의 `- **제목**: 요약` 줄들을 작업 목록으로, 각 항목 아래 `- 대상 파일:`·`- 예상 LoC:`·`- 위험도: L|M|H`를 `files`·`estimatedLoc`·`risk`로. `## 4.`가 없으면 `[]`.
- `markdownResult`는 입력 전체 그대로, `subtasksJson`은 JSON 배열 문자열. 경고(섹션 4·5 누락, 한글 비율)는 로그에만.
- 같은 입력에 Java와 같은 결과가 나오는지를 계약 파일의 입력·기대 출력 쌍으로 고정한다(§9).

### 8. 보고와 유실 대책(Java `ResultReporter`·`SilentLossTracker`·`DeadLetterReplayJob`과 같은 파일 형식)

- 보고 전 모든 자유 텍스트 칸을 마스킹한다.
- 최대 6회 시도, 간격 2·4·8·16·32초. 4xx는 재시도하지 않는다. 연결 오류·5xx는 재시도한다.
- 끝내 못 보내면(4xx로 바로 거절된 것 포함 — 그때는 `permanent=true`) `<DEAD_LETTER_DIR>/<WORKER_ID>.jsonl`에 한 줄: `{ts, taskId, status(한글 상태 이름), permanent, reason, attempts, result}`. `result` 안의 `status`는 상수 이름이다.
- 재전송(60초): 줄마다 한 번 다시 보낸다. 성공하면 줄 제거, 4xx면 `attempts+1`이고 3 이상이면 `<WORKER_ID>.dead.jsonl`로 옮긴다(5MB에서 `.1`로 회전). 연결 오류·5xx면 그 회차를 멈춘다.
- 하트비트의 `lostReportCount`는 대기 파일 줄 수, `deadReportCount`는 dead 파일 두 세대 줄 수.
- **Java와 다르게**: 자기가 쓴 줄은 반드시 다시 읽을 수 있어야 한다(Java는 사용량이 든 줄을 다시 못 읽을 가능성이 있다 — 복제하지 않는다. Java 쪽 확인·수정은 범위 밖이고 후속 항목으로 남긴다).

### 9. 계약 파일(Java ↔ Rust)

`worker-rs/contract/`에 둔다. **Java 시험이 만들고 지킨다**: 시험이 실제 클래스에서 내용을 만들어 파일과 비교하고, 다르면 실패하면서 갱신 방법(시스템 속성 하나로 다시 쓰기)을 알려 준다. Rust는 같은 파일을 빌드에 내장하거나 시험에서 읽는다.

| 파일 | 내용 | Rust 쪽 쓰임 |
|---|---|---|
| `constants.json` | 비밀 env 이름 목록(`StackSecretEnv.NAMES`), `TaskStatus` 이름 → 한글 값, 작업 종류 이름, `supports` 토큰 | 내장(자식 env 제거, dead-letter의 한글 상태) |
| `analysis-prompt.txt` | 분석 프롬프트 템플릿(`application-worker.yml`에서) | 내장 |
| `claim-analysis.json` | `WorkerTaskResponse.forAnalysis`를 API와 같은 방식으로 직렬화한 것 | 역직렬화 시험 |
| `result-analysis-ok.json`, `result-analysis-failed.json`, `heartbeat.json`, `startup-request.json`, `startup-response.json` | Rust가 보낼 모양의 본문을 Java DTO가 읽을 수 있는지(그리고 응답 모양) | 직렬화 결과가 이 파일과 같은지 |
| `parser-cases.json` | 파서 입력과 Java 파서의 출력(성공·실패 사유) 여러 쌍 | 파서 시험 |
| `mask-cases.json` | 마스킹 입력·출력 쌍 | 마스킹 시험 |

**요청 본문 파일의 모양과 비교 기준(작성자 판단)**: 파일은 null 칸이 없는 최소 모양이고 `usage`에 `empty` 키가 없다(Rust가 보내는 모양). Java 시험은 (1) 파일을 API와 같은 매퍼로 읽으면 기대한 DTO와 같고 (2) 그 DTO를 null 제외로 다시 쓰면(`usage.empty` 제외) 파일과 JSON 값으로 같음을 지킨다. Rust 시험은 직렬화 결과가 파일과 JSON 값으로 같음을 본다(키 순서·공백은 보지 않는다). 응답 파일(`claim-analysis.json`, `startup-response.json`)은 Java가 실제로 내보내는 모양 그대로(null 칸 포함)다.

`CLAUDE.md`의 "비밀 제거 목록은 두 곳을 같이 고칠 것"에 세 번째(계약 파일 갱신)를 적는다.

### 10. 기동·정지 통합(스크립트는 ASCII만)

- **스위치**: `public.env`(또는 env)에 `WORKER_RS_ENABLED=true`이고 `-WithWorkers`일 때만. 꺼져 있으면 한 줄도 찍지 않고 건너뛴다.
- **빌드**: `worker-rs/`에서 `cargo build --release --locked`(cargo는 `%USERPROFILE%\.cargo\bin\cargo.exe`, 없으면 `[FAIL] worker-rs`이고 다른 서비스에는 영향 없음). 로그 `.run\worker-rs-build.log`.
- **실행 파일**: jar와 같은 규칙으로 `%USERPROFILE%\netis-maker\run\bin\netis-worker-rs-<sha7>[-dirty-<시각>].exe`에 `.part`로 복사한 뒤 이름을 바꿔 그 사본을 실행. 깨끗한 트리에 같은 이름이 있으면 빌드하지 않는다. 최근 5개와 실행 중인 것만 남긴다. 빌드 실패 시 이전 사본으로 띄우지 않는다.
- **env**: `API_BASE_URL=http://localhost:8090`, `WORKER_API_KEY`, `CLAUDE_CLI`, `WORKER_ID=<WORKER_RS_ID, 기본 win-rs-worker-1>`, `WORKER_KINDS=<WORKER_RS_KINDS, 기본 ANALYSIS>`, `WORKER_VERSION=rs-<sha7>`.
- **준비 확인**: 설정 검사와 MCP 파일 준비가 끝나면 `worker-rs started: id=… kinds=…`를 찍는다. 실패 패턴은 `worker-rs fatal`과 래퍼 종료. 대기 60초.
- **프로세스 찾기**: 래퍼(cmd)의 자식 중 실행 파일 경로가 그 사본인 것(순수 함수로 만들고 시험 추가). 기록은 `.run\worker-rs.json`, 로그는 `.run\worker-rs.log`(이전 로그 보관 규칙 그대로).
- **정지**: `stop-public.ps1`의 전체 이름 목록(`$allNames`, `-Only` 검증 겸용)에 `worker-rs`를 `worker` 앞에 추가. `-KeepWorkers`가 멈추는 목록(`front, api, auth`)은 그대로 둔다 — 워커를 남기는 스위치이므로 `worker-rs`도 남는다. `-Only worker-rs`로 단건 정지.
- 로그 줄은 `<ISO 시각> <수준> <메시지>` UTF-8. 비밀값·인증 URL은 찍지 않는다.

### 11. CI

`ci.yml`에 잡 "Worker RS (cargo test)" 추가: `windows-latest`와 `ubuntu-latest`에서 `cargo fmt --check`, `cargo clippy -- -D warnings`, `cargo test --locked`. main 필수 체크에 넣는 것은 저장소 설정이라 사용자 몫이고, 1단계에서는 필수가 아니어도 된다. 계약 파일을 지키는 Java 시험은 기존 Backend 잡에서 돈다.

### 12. 시험과 검증

1. **단위·계약 시험**(CI): 파서·마스킹·레포 식별·설정 검사·사용량 해석·dead-letter 읽기 쓰기·재시도 간격, 계약 파일 왕복.
2. **가짜 API 시험**(CI): 테스트 안에서 띄운 HTTP 서버로 기동 알림 재시도, `kinds=` 항상 전송, 204, claim → 보고, 4xx·5xx 때의 유실 기록과 재전송. git은 임시 로컬 저장소, claude는 가짜 실행 파일로 대신한다.
3. **잠금 호환 실측**(이 PC, 한 번): §5.
4. **격리된 끝-끝 확인**(이 PC, 한 번): worktree에서 만든 API jar를 다른 포트와 임시 데이터베이스로 띄우고, `작업대기` 작업 한 건을 SQL로 넣고, Rust 워커를 그 API에 붙여 실제 claude로 작은 공개 레포를 분석 → 작업이 `분석완료`가 되고 분석 결과·사용량이 저장되는지 확인한 뒤 임시 데이터베이스를 지운다. 라이브 API·라이브 DB는 건드리지 않는다. 격리 조건:
   - 포트·데이터베이스만 바꿔서는 격리되지 않는다. 통합 시험(`TestcontainersConfig`)이 끄는 네 가지를 같이 끈다: `app.attachment.cleanup.enabled=false`, `app.interview.checkout-cleanup.enabled=false`, `app.attachment.render.enabled=false`, `app.app-log.enabled=false`.
   - 빈 데이터베이스에는 Flyway 전에 `com` 스키마와 `com."user"` 테이블이 있어야 한다(`src/test/resources/init-test-schema.sql`과 같은 준비).
   - 워커 키는 시험용 값을 따로 준다(`app.worker.api-key`). 격리 API는 기동 때 라이브 auth(:9000)의 공개 키를 읽기만 한다.
   - 분석 정체 회수(기본 claim 뒤 5분)가 실제 claude 실행 중에 끼어들지 않게 `app.task.stale-threshold-minutes`를 워커 분석 상한(10분)보다 크게 준다.
   - Rust 워커의 `REPOS_DIR`·`DEAD_LETTER_DIR`도 임시 폴더로 준다.
5. **라이브 켜기**(병합 뒤, 사용자 승인 후): `WORKER_RS_ENABLED=true` → 예약 작업으로 기동 → 하트비트 행·관리 화면 표시·`stop-public -Only worker-rs` 확인 → 측정.

### 13. 측정과 2단계 판단 기준

측정해 `docs/rust-pilot/phase1-results.md`에 적는다: 켠 지 1시간 뒤 Rust 워커의 확보 메모리(Java 워커 약 315MB와 나란히), 실행 파일 크기, 처음·증분 빌드 시간, Rust 코드·시험 줄 수와 같은 범위 Java 줄 수, 끝-끝 분석 한 건의 단계별 시간.

2단계(배포·배포 중지)로 가는 조건: 상주 메모리 30MB 이하, 계약 시험이 양쪽 CI에서 통과, 잠금 호환 확인, 격리 끝-끝 성공, 라이브에서 기동·정지 3회 이상 문제 없음. 하나라도 안 되면 원인과 함께 사용자에게 보고하고 멈춘다.

## 되돌리기

`WORKER_RS_ENABLED`를 지우고 `stop-public.ps1 -Only worker-rs` — Java 워커는 처음부터 끝까지 손대지 않으므로 그것으로 원래 상태다. 하트비트 행(`win-rs-worker-1`)은 남아 "끊김"으로 보이니 필요하면 지운다.

## 사전 준비(사용자 승인 필요)

(끝남, 2026-11-28 사용자 승인 뒤) `rustup` 1.29.3(공식 게시 해시와 대조)으로 `stable-x86_64-pc-windows-gnu` 1.100.1을 설치했다. 확인한 것: C 컴파일러 없이 serde·serde_json·regex·time·ureq(TLS 없음)가 빌드·링크된다(릴리스 빌드 약 26초). §5의 잠금 호환도 실측했다 — Java `FileChannel.tryLock()`이 쥔 파일은 Rust `File::try_lock`이 못 잡고, 반대도 같으며, 풀면 양쪽 다 잡는다.

## 빌더에게 맡기는 것

모듈 나누기, 크레이트 선택(순수 Rust 조건 안에서), 오류 타입 구조, 로그 구현, 가짜 claude·가짜 API의 만드는 방식, 계약 시험의 Java 쪽 구현 세부(프롬프트 템플릿을 yml에서 읽는 방법 포함), 격리 끝-끝 확인에서 임시 데이터베이스에 넣을 최소 행.

## 후속으로 남기는 것(이 단계에서 고치지 않는다)

- Java: 사용량이 든 유실 보고 줄을 다시 읽지 못할 가능성(확인 필요), `git rev-parse` 종료 코드 미검사, API의 분석 정체 회수 5분과 워커 분석 상한 10분의 불일치.
- 유닉스에서의 Java·Rust 잠금 호환(원격 macOS 워커로 넓힐 때).
- 2단계 설계: 배포·배포 중지, `supports` 토큰, PC 단위 작업의 소유.
