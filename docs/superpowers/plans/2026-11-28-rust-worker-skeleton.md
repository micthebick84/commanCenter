# Rust 워커 1단계(뼈대 + 분석 경로) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `worker-rs/`에 Rust 워커(하트비트·기동 알림·폴링·분석 처리·보고 유실 대책·claude 로그인 판정)를 만들고, Java와 공유하는 계약 파일·기동 정지 통합(기본 꺼짐)·CI를 붙인다.

**Architecture:** 표준 스레드 셋(폴링·하트비트·관리)과 동기 HTTP. 동작의 기준은 Java 워커 코드이고 Rust는 모듈별로 그것을 옮긴다. 상수·프롬프트·본문 모양은 Java 시험이 지키는 `worker-rs/contract/` 골든 파일을 Rust가 내장·시험에 쓴다.

**Tech Stack:** Rust 1.100.1(`x86_64-pc-windows-gnu`, edition 2024), serde·serde_json, ureq 3(TLS 없음), regex, time, 시험용 tiny_http. Java 21·JUnit(계약 시험), Windows PowerShell 5.1(스크립트), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-28-rust-worker-skeleton-design.md`

## Global Constraints

- **Java 코드가 기준**: 스펙이 "Java와 같게"라고 한 동작은 해당 Java 파일을 읽고 옮긴다. 스펙 요약과 Java가 다르면 Java를 따른다. 예외는 스펙이 "Java와 다르게"라고 적은 두 곳(`git rev-parse` 실패 검사, 자기가 쓴 유실 줄은 반드시 다시 읽힘)뿐.
- 의존성은 순수 Rust만. C 컴파일러가 필요한 크레이트(ring, aws-lc, openssl-sys, libgit2-sys 등) 금지. `cargo tree`에 그런 것이 보이면 실패.
- 구현 종류 집합은 `{ANALYSIS}`. claim 요청에는 항상 `kinds=`를 붙인다(빈 값·생략 금지).
- 비밀값·인증 URL(`oauth2:<토큰>@`)은 로그·실패 사유·보고 본문·claude 자식 env 어디에도 나가지 않는다.
- 고정값: 폴링 5초, 하트비트 10초, 분석 상한 10분, git 상한 600초, 레포 잠금 대기 60초(500ms 간격), 보고 재시도 간격 2·4·8·16·32초(최대 6회), 재전송 60초·최대 3회, dead 파일 회전 5MB. 시험에서는 이 값들을 주입해 줄인다.
- 로그 줄: `<ISO-8601 시각> <LEVEL> <메시지>` UTF-8, stdout. 준비 줄 `worker-rs started: id=<id> kinds=<kinds>`, 치명 오류 줄 `worker-rs fatal: <이유>`(종료 코드 1).
- PowerShell 스크립트는 ASCII만(확인: `grep -nP '[^\x00-\x7F]' scripts/*.ps1 scripts/tests/*.ps1`이 빈 출력). `public-proc.ps1` 함수는 throw하지 않고 `$null`/`$false`를 돌려준다.
- 스위치가 꺼져 있으면(`WORKER_RS_ENABLED`가 `true`가 아니면) 시작 스크립트는 worker-rs에 대해 한 줄도 찍지 않고 아무것도 하지 않는다.
- 공개 저장소다: 실제 고객·현장 이름, 사내 IP, 토큰을 코드·시험·문서에 넣지 않는다. 예시는 `acme/widgets` 같은 가짜 이름.
- 작업 폴더는 이 worktree뿐. 라이브 체크아웃(`C:\Users\mic\NetisMaker\commanCenter`)과 라이브 `.run`·`%USERPROFILE%\netis-maker`의 파일·프로세스를 건드리지 않는다. 시험은 임시 폴더만 쓴다.
- 명령 환경: Git Bash에서 `export PATH="$HOME/.cargo/bin:$PATH"` 뒤 `worker-rs/`에서 cargo. Gradle은 worktree 루트에서 `./gradlew test --tests '<패턴>' --no-daemon`(`gradlew --stop` 금지). PowerShell 시험은 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\public-proc.test.ps1`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.

## Review Focus

- `WORKER_KINDS`가 비었거나 모르는 종류가 섞였을 때: 빈 값은 구현 집합으로, 모르는 종류·미구현 종류는 기동 실패. 요청 URL에 `kinds=ANALYSIS`가 항상 있다(Task 2·6 시험).
- git이 실패하며 출력에 인증 URL을 찍을 때: 실패 사유와 로그에 `***@`로 가려진다(Task 4 시험).
- claude 출력이 JSON이 아니거나 `result`가 없을 때, 0이 아닌 종료 + stderr, 상한 초과 시 손자 프로세스까지 종료(Task 5 시험).
- 유실 보고 줄에 사용량·한글·줄바꿈이 들어 있을 때 다시 읽혀 재전송된다. 5xx면 그 회차를 멈추고 줄을 지우지 않는다. 4xx 3회면 dead 파일로(Task 6 시험).
- 다른 프로세스가 레포 잠금을 쥐고 있을 때: 대기 뒤 `repo lock timeout (…): <키>`로 실패하고 캐시를 건드리지 않는다(Task 4 시험).
- 한글이 든 프롬프트(stdin)와 결과(stdout)가 Windows에서 UTF-8 그대로 오간다(Task 5 시험).
- 서버가 `ANALYSIS`가 아닌 종류나 모르는 필드를 돌려줄 때: 역직렬화는 성공하고 처리하지 않는다(Task 7 시험).

---

## 파일 구조

```
worker-rs/
  Cargo.toml  Cargo.lock  rust-toolchain.toml  README.md
  contract/            # Java 시험이 지키는 골든 파일 (Task 1)
  src/main.rs          # 스레드 조립, 기동 줄
  src/lib.rs           # 모듈 공개
  src/log.rs  src/config.rs  src/contract.rs  src/mask.rs  src/dto.rs     (Task 2)
  src/parser.rs                                                          (Task 3)
  src/proc.rs  src/repo.rs                                               (Task 4)
  src/mcp.rs  src/claude.rs  src/auth_probe.rs  src/bin/fake_claude.rs   (Task 5)
  src/api.rs  src/report.rs                                              (Task 6)
  src/analysis.rs  src/runner.rs  tests/fake_api.rs                      (Task 7)
src/test/java/com/hamonsoft/netismaker/contract/WorkerRsContractTest.java (Task 1)
scripts/public-proc.ps1  start-public.ps1  stop-public.ps1  tests/public-proc.test.ps1 (Task 8)
.github/workflows/ci.yml  CLAUDE.md  .gitignore  docs/rust-pilot/        (Task 2·9·10)
```

---

### Task 1: 계약 골든 파일과 Java 계약 시험

**Files:**
- Create: `src/test/java/com/hamonsoft/netismaker/contract/WorkerRsContractTest.java`
- Modify: `build.gradle`(test 태스크가 `contract.update` 시스템 속성을 넘기게 한 줄)
- Create: `worker-rs/contract/{constants.json, analysis-prompt.txt, claim-analysis.json, result-analysis-ok.json, result-analysis-failed.json, heartbeat.json, startup-request.json, startup-response.json, parser-cases.json, mask-cases.json}`

**Interfaces:**
- Produces (Rust 쪽이 읽는 모양):
  - `constants.json`: `{"secretEnvNames":[...], "taskStatuses":{"PENDING":"작업대기",...전부}, "kinds":["ANALYSIS",...], "supports":["refRepos",...]}`
  - `analysis-prompt.txt`: `application-worker.yml`의 `netis-maker.worker.prompt-template` 값 그대로(UTF-8, 줄 끝 `\n`).
  - `claim-analysis.json`: `WorkerTaskResponse.forAnalysis(...)`를 API와 같은 매퍼로 직렬화한 것(null 칸 포함). 값: id 1, repo `acme/widgets`, branch `main`, 제목·설명은 한글 포함, `mcpsExtra` 1개(`{"name":"docs","url":"http://localhost:9/mcp","transport":"http"}`), model `sonnet`, effort `high`, gitUrl null.
  - `result-analysis-ok.json` / `result-analysis-failed.json` / `heartbeat.json` / `startup-request.json`: null 칸 없는 최소 모양, `usage`에 `empty` 없음. `heartbeat.json`의 `claudeAuthExpiresAt`은 `"2026-12-31T00:00:00Z"` 문자열.
  - `startup-response.json`: `{"recovered":[{"taskId":3,"from":"IN_PROGRESS","to":"PENDING"}],"skipped":[7]}`.
  - `parser-cases.json`: `[{"name","input","ok":true,"subtasksJson","warnings":[...]} | {"name","input","ok":false,"error"}]` — 최소 8건: 정상(섹션 1~5, subtask 2개 + 대상 파일·LoC·위험도), 섹션 4 없음, 섹션 4 본문 비어 있음, 섹션 5 없음, 섹션 2 누락(오류), 빈 입력(오류), 같은 번호 섹션 중복, 한글 비율 50% 미만.
  - `mask-cases.json`: `[{"input","output"}]` — 최소 5건: `https://oauth2:tok@host/x`, `http://user:pw@h:8080/a`, 자격증명 없음, 한 줄에 두 URL, `://user@host`(비밀 없음 → 그대로).

- [ ] **Step 1: 시험 작성** — 시험마다 "실제 클래스에서 만든 내용 == 파일"을 단언한다. 시스템 속성 `contract.update=true`면 파일을 다시 쓰고 통과. 다르면 실패 메시지에 `./gradlew test --tests '*WorkerRsContractTest' -Dcontract.update=true --no-daemon` 안내(Gradle test 태스크가 이 속성을 넘기도록 `build.gradle`의 `test { systemProperty ... }` 한 줄 추가).
  - `constantsMatchJavaSources`: `StackSecretEnv.NAMES`, `TaskStatus.values()`(이름 → `dbValue`), `WorkerTaskResponse.Kind.values()`, `WorkerHttpClient`의 supports 상수.
  - `analysisPromptMatchesWorkerYml`: yml에서 읽은 템플릿.
  - `claimAnalysisMatchesForAnalysis`: 위 값으로 만든 `forAnalysis`를 Boot 기본과 같은 매퍼(`Jackson2ObjectMapperBuilder.json().build()`)로 직렬화.
  - `requestBodiesAreReadableAndStable`: 요청 파일 넷을 그 매퍼로 DTO로 읽어 기대 DTO와 `equals`, 그리고 그 DTO를 null 제외(`usage.empty` 제외)로 다시 쓰면 파일과 JSON 트리로 같음. `startup-response.json`은 DTO 직렬화 == 파일.
  - `parserCasesMatchJavaParser`: 각 `input`에 `PromptResultParser.parse` 결과(`subtasksJson`·`warnings` 또는 `ParseException` 메시지)가 파일과 같음.
  - `maskCasesMatchGitRemotes`: `GitRemotes.mask(input) == output`.
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*WorkerRsContractTest' --no-daemon` → 파일 없음으로 FAIL.
- [ ] **Step 3: 파일 생성** — `-Dcontract.update=true`로 한 번 돌려 파일을 만들고 내용을 눈으로 확인(비밀·실명 없음).
- [ ] **Step 4: 통과 확인** — 속성 없이 다시 실행 → PASS. 파일 하나를 한 글자 바꾸면 FAIL하는지 확인하고 되돌린다.
- [ ] **Step 5: Commit** — `test: worker-rs contract golden files guarded by Java`

---

### Task 2: Rust 프로젝트 뼈대 — 설정·계약·마스킹·DTO·로그

**Files:**
- Create: `worker-rs/Cargo.toml`, `worker-rs/rust-toolchain.toml`(channel `1.100.1`, components `rustfmt`,`clippy`), `worker-rs/README.md`(빌드·시험·env 표 한 화면), `worker-rs/src/{lib.rs,main.rs,log.rs,config.rs,contract.rs,mask.rs,dto.rs}`
- Modify: `.gitignore`(`worker-rs/target/` 추가)

**Interfaces:**
- Consumes: Task 1의 `worker-rs/contract/*`.
- Produces:
  - `log::{info,warn,error}(msg: &str)`.
  - `contract::ANALYSIS_PROMPT: &str`, `contract::secret_env_names() -> &'static [String]`, `contract::status_ko(name: &str) -> Option<&'static str>`, `contract::IMPLEMENTED_KINDS: &[&str] = &["ANALYSIS"]`, `contract::known_kinds() -> &'static [String]`.
  - `mask::mask(text: &str) -> String`.
  - `config::Config { worker_id, api_base_url, api_key, kinds: Vec<String>, version, repos_dir: PathBuf, dead_letter_dir: PathBuf, claude_cli: PathBuf, github_pat: Option<String>, gitlab_token: Option<String>, auth_check_interval: Duration, home: PathBuf }`, `Config::from_env(env: &HashMap<String,String>, home: &Path) -> Result<Config, String>`.
  - `dto::TaskClaim { id: i64, github_repo, github_branch, title, description: Option<String>, kind: String, mcps_extra: Vec<McpSpec>, model, effort, git_url: Option<String> }`(모르는 필드 무시, `mcpsExtra` null → 빈 벡터), `dto::McpSpec { name, url, transport: String, headers: Option<BTreeMap<String,String>> }`, `dto::Usage { cost_usd: f64, input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens: i64 }`, `dto::ResultRequest { worker_id, status: String, markdown_result, subtasks_json, claude_log: Option<String>, duration_ms: Option<i64>, failure_reason: Option<String>, usage: Option<Usage> }`(None 생략), `ResultRequest::masked(self) -> ResultRequest`(자유 텍스트 칸 전부에 `mask`), `dto::Heartbeat {…10칸}`, `dto::StartupRequest { pending_task_ids: Vec<i64> }`. JSON 이름은 camelCase.

- [ ] **Step 1: 실패하는 시험 작성** (`#[cfg(test)]` 모듈)
  - `config`: `empty_kinds_default_to_implemented_set`(`WORKER_KINDS` 없음/`""` → `["ANALYSIS"]`), `unknown_kind_is_fatal`(`"ANALYSIS,BOGUS"` → Err에 `BOGUS`), `unimplemented_kind_is_fatal`(`"DEPLOY"` → Err), `kinds_are_trimmed_and_uppercased`(`" analysis "` → `["ANALYSIS"]`), `https_base_url_is_fatal`, `missing_required_is_fatal`(셋 각각), `worker_id_over_50_is_fatal`, `version_over_50_is_fatal`, `claude_cli_missing_is_fatal`, `claude_cli_resolution`(`CLAUDE_CLI`가 있는 파일 → 그것, 없으면 `home/.local/bin/claude.exe`·`claude` 순), `defaults`(repos `home/netis-maker/repos`, dead-letter `home/netis-maker/dead-letter`, version `0.1.0`, 주기 300초).
  - `contract`: `secret_names_include_worker_api_key`, `status_ko_completed_is_분석완료`, `prompt_has_all_placeholders`(다섯 자리표시자).
  - `mask`: `mask_cases_from_contract`(`mask-cases.json` 전부).
  - `dto`: `claim_analysis_deserializes`(`claim-analysis.json` → id 1, kind `ANALYSIS`, mcps 1개, git_url None), `unknown_kind_and_fields_still_deserialize`, `result_ok_matches_contract`·`result_failed_matches_contract`·`heartbeat_matches_contract`·`startup_request_matches_contract`(직렬화 → `serde_json::Value`로 파일과 같음), `masked_hides_credentials_in_all_text_fields`.
- [ ] **Step 2: 실패 확인** — `cargo test --locked` (처음은 `cargo generate-lockfile` 뒤) → 컴파일 실패/FAIL.
- [ ] **Step 3: 구현** — `mask` 정규식은 lookbehind 없이 `(://)[^/@\s:]+:[^/@\s]+@` → `$1***@`. `main.rs`는 이 단계에서 `Config::from_env` 실패 시 `worker-rs fatal: …` 출력·종료 코드 1, 성공 시 `worker-rs started: …`만(스레드는 Task 7).
- [ ] **Step 4: 통과 확인** — `cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test --locked` 모두 통과. `cargo tree -e normal | grep -Ei 'ring|aws-lc|openssl|rustls|libgit2'` 빈 출력.
- [ ] **Step 5: Commit** — `feat(worker-rs): scaffold with config, contract, mask and DTOs`

---

### Task 3: 결과 파서

**Files:** Create `worker-rs/src/parser.rs`. 기준: `workerdaemon/PromptResultParser.java` 전체.

**Interfaces:** Produces `parser::Parsed { markdown: String, subtasks_json: String, warnings: Vec<String> }`, `parser::parse(raw: &str) -> Result<Parsed, String>`(Err 문자열은 Java `ParseException` 메시지와 같음).

- [ ] **Step 1: 실패하는 시험** — `parser_cases_from_contract`: `parser-cases.json`의 모든 건에 대해 `ok`면 `subtasks_json`을 JSON 값으로 비교·`warnings` 같음·`markdown == input`, 아니면 Err == `error`. 추가로 `subtask_fields_are_optional`(대상 파일만 있는 항목 → `files`만 있고 `estimatedLoc`·`risk` 키 없음).
- [ ] **Step 2: 실패 확인** — `cargo test --locked parser` → FAIL.
- [ ] **Step 3: 구현** — Java의 다섯 정규식(MULTILINE)과 섹션 경계 규칙·한글 비율 계산(한글 U+AC00–D7A3·U+1100–11FF ÷ 글자 수, Java `Character.isLetter`에 맞춰 `char::is_alphabetic`; 계약 사례가 어긋나면 사례에 맞춘다)을 옮긴다.
- [ ] **Step 4: 통과 확인** — fmt·clippy·test.
- [ ] **Step 5: Commit** — `feat(worker-rs): analysis result parser`

---

### Task 4: 프로세스 실행과 git 캐시

**Files:** Create `worker-rs/src/proc.rs`, `worker-rs/src/repo.rs`. 기준: `workerdaemon/ProcessRunner.java`, `workerdaemon/GitRepoCache.java`(`ensureFresh`, `withRepoLock`, `.last-used`), `git/RepoRef.java`, `git/GitRemotes.java`.

**Interfaces:**
- Consumes: `mask::mask`.
- Produces:
  - `proc::Output { exit_code: i32, stdout: String, stderr: String, timed_out: bool, duration_ms: u64 }`
  - `proc::run(cmd: &mut std::process::Command, stdin: Option<&[u8]>, timeout: Duration) -> std::io::Result<Output>` — stdout·stderr를 따로 끝까지 읽고(UTF-8 손실 허용 변환), 상한을 넘으면 프로세스 트리를 죽이고(`Windows: taskkill /PID <pid> /T /F`, 그 외: 프로세스 그룹 kill) `timed_out=true`.
  - `repo::RepoRef { host: Host, path: String, git_url: String }`, `Host::{Github,Gitlab}`, `RepoRef::from_snapshot(path: &str, git_url: Option<&str>) -> RepoRef`, `RepoRef::local_key(&self) -> String`, `repo::authenticated_url(r: &RepoRef, github_pat: Option<&str>, gitlab_token: Option<&str>) -> String`.
  - `repo::CheckedOut { dir: PathBuf, commit_sha: String }`
  - `repo::GitCache { repos_dir: PathBuf, github_pat: Option<String>, gitlab_token: Option<String>, lock_wait: Duration, lock_poll: Duration, git_timeout: Duration }`, `GitCache::ensure_fresh(&self, r: &RepoRef, branch: &str) -> Result<CheckedOut, String>`.

- [ ] **Step 1: 실패하는 시험**
  - `proc`: `captures_stdout_and_stderr_separately`, `writes_stdin_then_closes`, `utf8_roundtrip_korean`(한글 stdin → 그대로 stdout; 시험용 자식은 `git hash-object --stdin` 같은 실제 도구나 자기 자신 재실행 중 편한 쪽), `timeout_kills_process_tree`(자식이 손자를 띄우고 둘 다 오래 자는 경우 → `timed_out`, 반환 뒤 손자 pid가 살아 있지 않음), `nonzero_exit_is_reported`.
  - `repo` 순수: `from_snapshot_defaults_to_github`(`("acme/widgets", None)` → Github, `https://github.com/acme/widgets.git`), `from_snapshot_gitlab_by_host`(`https://git.example.test:8443/g/sub/p.git` → Gitlab), `github_host_case_insensitive`, `local_key_github`·`local_key_gitlab`(`_gitlab/g+sub+p`), `authenticated_url_inserts_oauth2`·`authenticated_url_without_token_is_plain`.
  - `repo` 통합(임시 폴더의 로컬 bare 저장소를 `git_url`로): `first_call_clones_and_returns_head_sha`, `second_call_fetches_new_commit`(원격에 커밋 추가 → 새 sha), `origin_url_has_no_credentials`(`.git/config`에 `oauth2:` 없음), `writes_lock_and_last_used_files`(`<키>.lock`·`<키>.last-used` 존재, last-used 내용이 ISO 시각), `lock_held_elsewhere_times_out`(시험이 같은 `.lock`을 먼저 잡고 `lock_wait=300ms` → Err `repo lock timeout` 포함, 체크아웃 폴더 미생성), `git_failure_message_is_masked`(없는 브랜치 + `git_url`에 `https://oauth2:secret@127.0.0.1:1/x.git` → Err에 `secret` 없음·`***@` 있음), `rev_parse_failure_is_error`.
- [ ] **Step 2: 실패 확인** — `cargo test --locked proc repo` → FAIL.
- [ ] **Step 3: 구현** — git 명령·인자·순서·오류 문구(`git failed (<코드>): <가린 출력>`, `process timeout (600s): <가린 명령>`, `repo lock timeout (60s): <키>`)는 Java와 같게. 잠금은 `std::fs::File::try_lock`(2026-11-28 이 PC에서 Java `FileChannel.tryLock`과 서로 배타임을 실측). 잠금은 `ensure_fresh`가 끝나면 푼다.
- [ ] **Step 4: 통과 확인** — fmt·clippy·test.
- [ ] **Step 5: Commit** — `feat(worker-rs): process runner and shared git cache`

---

### Task 5: MCP 합본·claude 실행·로그인 판정

**Files:** Create `worker-rs/src/mcp.rs`, `worker-rs/src/claude.rs`, `worker-rs/src/auth_probe.rs`, `worker-rs/src/bin/fake_claude.rs`; Modify `worker-rs/Cargo.toml`(`[features] test-bins = []`, `[[bin]] name="fake-claude" required-features=["test-bins"]`). 기준: `workerdaemon/WorkerMcpSupport.java`, `ClaudeExecAdapter.java`, `ClaudeAuthProbe.java`, `util/StackSecretEnv.java`.

**Interfaces:**
- Consumes: `proc::run`, `dto::{McpSpec, Usage}`, `contract::secret_env_names`.
- Produces:
  - `mcp::McpSupport::init(claude_json: &Path, out_file: &Path) -> McpSupport`(실패는 경고 후 빈 상태), `McpSupport::server_names(&self) -> &[String]`, `McpSupport::args_for(&self, extras: &[McpSpec]) -> std::io::Result<McpArgs>`, `McpArgs { args: Vec<String>, temp_file: Option<PathBuf> }`.
  - `claude::ExecResult { exit_code: i32, stdout: String, duration_ms: u64, usage: Option<Usage> }`
  - `claude::ClaudeExec { cli: PathBuf, mcp: McpSupport }`, `ClaudeExec::exec(&self, prompt: &str, dir: &Path, timeout: Duration, mcps_extra: &[McpSpec], model: Option<&str>, effort: Option<&str>) -> Result<ExecResult, String>`(Err = 실행 자체 실패·상한 초과 `claude timeout after PT10M` 꼴).
  - `claude::build_args(model: Option<&str>, effort: Option<&str>, mcp_args: &[String]) -> Vec<String>`, `claude::parse_envelope(raw: &str) -> (String, Option<Usage>)`, `claude::child_env(parent: impl Iterator<Item=(String,String)>) -> Vec<(String,String)>`, `claude::iso_duration(d: Duration) -> String`.
  - `auth_probe::AuthState { ok: Option<bool>, method: Option<String>, expires_at: Option<String> }`, `auth_probe::probe(env: &HashMap<String,String>, home: &Path, claude: &Path) -> AuthState`.
  - `fake-claude`(시험 전용 실행 파일): env `FAKE_CLAUDE_STDOUT_FILE`(내용을 stdout에), `FAKE_CLAUDE_STDERR`(문자열), `FAKE_CLAUDE_EXIT`(기본 0), `FAKE_CLAUDE_SLEEP_MS`, `FAKE_CLAUDE_SPAWN_CHILD=1`(같은 시간 자는 손자 실행), `FAKE_CLAUDE_RECORD`(경로에 `{"args":[…],"stdin":"…","cwd":"…","envKeys":[…]}` 기록). 인자 `auth status --json`이면 `FAKE_CLAUDE_AUTH_JSON` 내용을 출력.

- [ ] **Step 1: 실패하는 시험** (`cargo test --locked --features test-bins`; fake는 `env!("CARGO_BIN_EXE_fake-claude")`)
  - `claude` 순수: `args_minimal`(`["-p","--output-format","json","--setting-sources","project"]`), `args_with_model_effort_and_mcp_in_java_order`, `blank_model_is_omitted`, `envelope_result_and_model_usage_sum`, `envelope_falls_back_to_usage_fields`, `envelope_without_cost_or_usage_is_raw`, `envelope_result_not_string_is_raw`, `non_json_is_raw_without_usage`, `child_env_drops_secret_names_case_insensitively_and_keeps_claude_vars`, `iso_duration_10min_is_PT10M`(+ `PT30S`, `PT1H`).
  - `claude` 통합: `prompt_goes_to_stdin_utf8`(한글 프롬프트 == 기록된 stdin, cwd == 넘긴 폴더, 인자 == `build_args`), `secret_env_not_visible_to_child`(부모 env에 `WORKER_API_KEY`·`gitlab_token` 넣고 기록된 envKeys에 없음), `nonzero_exit_appends_stderr_tail`(`\n[stderr]\n` + 끝 2000자, 넘으면 `…` 접두), `timeout_returns_err_and_kills_grandchild`, `extras_temp_file_is_deleted_after_exec`.
  - `mcp`: `merges_top_level_and_project_servers_last_wins`, `no_servers_means_no_args`, `base_only_args`(`--mcp-config <out> --strict-mcp-config --allowedTools mcp__a,mcp__b`), `extras_overlay_uses_transport_as_type_and_optional_headers`, `missing_claude_json_is_empty_not_error`.
  - `auth_probe`: `env_token_wins`(`ENV_TOKEN`, ok true, 만료 None), `credentials_file_gives_expiry_iso`(`claudeAiOauth.expiresAt` epoch ms → `…Z`), `cli_status_logged_in`(fake `{"loggedIn":true}` → `CLI`), `undetermined_is_all_none`. Java와 필드 해석이 다르면 Java를 따른다.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인** — `cargo fmt --check && cargo clippy --all-targets --features test-bins -- -D warnings && cargo test --locked --features test-bins`. `cargo build --release`(기능 없이)에서 `fake-claude.exe`가 만들어지지 않음.
- [ ] **Step 5: Commit** — `feat(worker-rs): claude exec, MCP config and auth probe`

---

### Task 6: API 클라이언트와 보고·유실 대책

**Files:** Create `worker-rs/src/api.rs`, `worker-rs/src/report.rs`. 기준: `workerdaemon/WorkerHttpClient.java`(heartbeat·startup·nextTask·postResult), `ResultReporter.java`, `SilentLossTracker.java`, `DeadLetterReplayJob.java`.

**Interfaces:**
- Consumes: `dto::*`, `contract::status_ko`, `mask`.
- Produces:
  - `api::ApiError::{Client { status: u16, body: String }, Server { status: u16, body: String }, Transport(String)}`
  - `api::ApiClient::new(base_url: &str, api_key: &str, worker_id: &str, kinds: &[String]) -> ApiClient`(연결 10초·전체 60초 상한), `heartbeat(&self, hb: &Heartbeat) -> Result<(), ApiError>`, `startup(&self, pending: &[i64]) -> Result<(), ApiError>`(200·빈 본문·404 → Ok, 그 밖은 Err), `next_task(&self) -> Result<Option<TaskClaim>, ApiError>`(204·빈 본문 → None), `post_result(&self, task_id: i64, r: &ResultRequest) -> Result<(), ApiError>`.
  - `report::ResultSink` 트레이트(`fn post_result(&self, task_id: i64, r: &ResultRequest) -> Result<(), ApiError>`), `ApiClient`가 구현.
  - `report::DeadLetter::new(dir: &Path, worker_id: &str) -> DeadLetter`, `pending_count(&self) -> usize`, `dead_count(&self) -> usize`, `pending_task_ids(&self) -> Vec<i64>`, `record(&self, task_id: i64, r: &ResultRequest, permanent: bool, reason: &str)`, `replay(&self, sink: &dyn ResultSink, max_attempts: u32)`.
  - `report::report_terminal(sink: &dyn ResultSink, dl: &DeadLetter, task_id: i64, r: ResultRequest, backoff: &[Duration])`(`masked()` 적용 뒤 전송; 던지지 않는다), `report::DEFAULT_BACKOFF: [Duration; 5]`.

- [ ] **Step 1: 실패하는 시험** (tiny_http로 임시 포트 서버)
  - `api`: `every_request_has_api_key_header`, `next_task_url_always_has_kinds`(요청 경로·쿼리 == `/worker/next-task?workerId=w1&kinds=ANALYSIS`, `supports` 없음), `next_task_204_is_none`, `next_task_200_parses_contract_claim`, `startup_ok_for_200_empty_and_404`, `startup_err_for_401_and_500_and_refused`, `post_result_body_matches_contract_shape`, `client_and_server_errors_are_distinguished`.
  - `report`: `success_first_try_writes_nothing`, `retries_5xx_with_backoff_then_records_non_permanent`(주입 간격 1ms×5 → 호출 6회, 줄 1개 `permanent=false`), `client_error_records_immediately_as_permanent`(호출 1회), `line_format_matches_java`(키 `ts,taskId,status,permanent,reason,attempts,result`; `status`는 한글 `분석완료`, `result.status`는 `COMPLETED`), `line_with_usage_korean_and_newlines_roundtrips`, `replay_success_removes_line`, `replay_stops_cycle_on_5xx_and_keeps_lines`, `replay_4xx_three_times_moves_to_dead_file`, `dead_file_rotates_at_limit`(한도 주입), `counts_reflect_files`, `pending_task_ids_are_distinct`, `java_written_line_without_attempts_reads_as_zero`, `unreadable_line_goes_to_dead_not_lost`.
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현** — ureq는 상태 코드를 오류로 바꾸지 않게 설정하고 직접 분류. 유실 파일 쓰기는 임시 파일에 쓰고 이름 바꾸기(중간에 죽어도 줄이 반쪽으로 남지 않게). 파일 접근은 `DeadLetter` 안의 뮤텍스로 직렬화(폴링 스레드와 관리 스레드가 같이 쓴다).
- [ ] **Step 4: 통과 확인** — fmt·clippy·test(`--features test-bins`).
- [ ] **Step 5: Commit** — `feat(worker-rs): API client, result reporter and dead-letter replay`

---

### Task 7: 분석 처리와 실행 루프

**Files:** Create `worker-rs/src/analysis.rs`, `worker-rs/src/runner.rs`, `worker-rs/tests/fake_api.rs`; Modify `worker-rs/src/main.rs`, `worker-rs/src/lib.rs`. 기준: `workerdaemon/WorkerMainLoop.java`의 `heartbeat`·`ensureStartupReported`·`pollAndProcess`·`processAnalysis`·`safePostAnalysisFailure`·`usageOf`.

**Interfaces:**
- Consumes: Task 2~6 전부.
- Produces:
  - `analysis::Deps<'a> { worker_id: &'a str, cache: &'a GitCache, claude: &'a ClaudeExec, analysis_timeout: Duration }`, `analysis::process(task: &TaskClaim, deps: &Deps) -> ResultRequest`(항상 COMPLETED 또는 FAILED 요청을 돌려준다; 패닉도 `처리 중 예외: …` FAILED로).
  - `runner::Timings { poll: Duration, heartbeat: Duration, replay: Duration, auth_check: Duration, backoff: Vec<Duration>, analysis_timeout: Duration }`(`Default` = Global Constraints 값), `runner::run(config: Config, timings: Timings, stop: Arc<AtomicBool>)`(stop이 서면 세 스레드를 끝내고 돌아온다 — 시험용; 실제 실행에서는 서지 않는다).
  - `main.rs`: env → `Config::from_env` → `worker-rs started: …` → `runner::run`.

- [ ] **Step 1: 실패하는 시험**
  - `analysis`(로컬 bare 저장소 + fake-claude): `success_fills_markdown_subtasks_log_duration_usage`, `prompt_substitutes_all_five_placeholders`(기록된 stdin에 repo·branch·sha·제목·설명 포함, `{`자리표시자`}` 없음), `fetch_failure_reason_prefix`(`레포 fetch 실패: `), `exec_failure_reason_prefix`(없는 claude 경로 → `claude exec 실패: `), `nonzero_exit_reason_has_tail_and_usage`(`claude exit=3\n…`), `parse_failure_reason_prefix_keeps_usage`(`파싱 실패: 섹션 ## 2. 누락`), `null_title_or_description_does_not_panic`.
  - `tests/fake_api.rs`(프로세스 안 tiny_http 서버 + `runner::run`을 짧은 `Timings`로): `startup_is_retried_until_ok_and_no_claim_before`(첫 두 번 500 → 그동안 `/worker/next-task` 요청 0건), `heartbeat_carries_counts_mcps_and_auth`(본문에 `workerId`·`lostReportCount`·`deadReportCount`·`mcps` 배열), `claims_processes_and_reports_completed`(서버가 계약 claim을 한 번 주면 `/worker/tasks/1/result`에 `status=COMPLETED`·`markdownResult` 비어 있지 않음), `non_analysis_claim_is_not_processed`(`kind=DEPLOY` 응답 → result 요청 없음, 프로세스 계속), `result_5xx_is_dead_lettered_then_replayed`(result에 500을 계속 주다가 200으로 바꾸면 재전송으로 도착하고 파일이 빈다), `no_polling_while_processing`(fake-claude가 자는 동안 next-task 요청이 늘지 않음).
- [ ] **Step 2: 실패 확인** — FAIL.
- [ ] **Step 3: 구현** — 하트비트의 `hostname`은 OS 호스트 이름(실패 시 `unknown`, 100자 절단), 로그인 세 칸은 관리 스레드가 갱신한 공유 상태(첫 판정 전 None). 폴링은 처리 뒤 `poll`만큼 쉰다. 스레드 하나가 패닉해도 다른 스레드가 조용히 계속 돌지 않게, 패닉은 `worker-rs fatal: …`을 찍고 프로세스를 끝낸다(재기동은 운영자·예약 작업 몫).
- [ ] **Step 4: 통과 확인** — fmt·clippy·`cargo test --locked --features test-bins` 전체. `cargo build --release --locked` 뒤 `WORKER_ID` 없이 실행 → `worker-rs fatal:` + 종료 코드 1, 필수 env + 닿지 않는 API 주소로 실행 → `worker-rs started:` 뒤 기동 알림 재시도 경고가 5초마다(수동으로 10초 관찰 후 종료).
- [ ] **Step 5: Commit** — `feat(worker-rs): analysis rail and worker run loop`

---

### Task 8: 기동·정지 스크립트 통합

**Files:** Modify `scripts/public-proc.ps1`, `scripts/start-public.ps1`, `scripts/stop-public.ps1`, `scripts/tests/public-proc.test.ps1`.

**Interfaces:**
- Produces (`public-proc.ps1`의 순수 함수, 모두 throw 없음):
  - `Get-ExeName([string]$Prefix, [string]$Sha, [string]$Tag, [datetime]$Now) -> string` — `<Prefix>-<sha7>.exe` / `<Prefix>-<sha7>-<Tag>-<yyyyMMddHHmmss>.exe`.
  - `Select-NativeApp($Processes, [int]$WrapperPid, [string]$ExePath)` — `ParentProcessId`가 래퍼이고 `ExecutablePath`(없으면 `CommandLine`)가 `$ExePath`와 대소문자 무시로 같은(포함하는) 프로세스 중 가장 최근 것, 없으면 `$null`.
  - `Select-FilesToPrune($Files, [string]$Prefix, [string]$Extension, [int]$Keep, [string[]]$InUse)` — 기존 `Select-JarsToPrune`을 확장자 인자로 일반화(기존 함수는 이것을 부르게 하거나 그대로 두고 새 함수를 추가 — 기존 시험이 그대로 통과해야 한다).
  - `Select-ExesInUse($Processes, [string]$BinDir)` — `$BinDir` 아래 실행 파일로 떠 있는 프로세스의 경로 목록.
  - `Find-AppPid`에 `'worker-rs'` 분기(래퍼의 자식을 `Select-NativeApp`에).
- `start-public.ps1`: 스펙 §10 그대로. 워커 블록 뒤(`-WithWorkers` 안), `$env:WORKER_RS_ENABLED -eq 'true'`일 때만. `Get-ServiceExe 'netis-worker-rs' (Join-Path $Root 'worker-rs') 'worker-rs'`는 `Get-ServiceJar`와 같은 규칙(커밋·더러움 판정, `-Rebuild`는 `cargo clean` 뒤 빌드, `.part` 복사 후 이름 바꾸기, 실패 시 `[FAIL]`·`$null`), 빌드 명령 `"<cargo>" build --release --locked`(cargo = `$env:USERPROFILE\.cargo\bin\cargo.exe`, 없으면 `[FAIL] worker-rs: cargo not found`), 산출물 `worker-rs\target\release\netis-worker-rs.exe`, 실행 폴더 `%USERPROFILE%\netis-maker\run\bin`. env: `API_BASE_URL`, `WORKER_API_KEY`, `CLAUDE_CLI`, `WORKER_ID`(`$env:WORKER_RS_ID` 또는 `win-rs-worker-1`), `WORKER_KINDS`(`$env:WORKER_RS_KINDS` 또는 `ANALYSIS`), `WORKER_VERSION`(`rs-<sha7>`). 준비 패턴 `worker-rs started:`, 실패 패턴 `worker-rs fatal`, 대기 60초. 끝에서 exe 정리(최근 5개 + 실행 중).
- `stop-public.ps1`: `$allNames = @('interview', 'worker-rs', 'worker', 'front', 'api', 'auth')`, `-KeepWorkers` 줄은 그대로, 머리 주석 갱신.

- [ ] **Step 1: 실패하는 시험** (`public-proc.test.ps1`에 추가, 기존 단언 방식)
  - `Get-ExeName`: 깨끗한 이름, 태그 이름, sha 7자 절단.
  - `Select-NativeApp`: 래퍼의 자식 exe를 고름 / 다른 부모의 같은 exe는 무시 / 같은 래퍼의 다른 exe(cmd의 conhost 등) 무시 / 둘이면 최근 것 / 없으면 `$null` / 경로 대소문자 차이 허용.
  - `Select-FilesToPrune`: `.exe`에서 최근 5개 + InUse 유지, 접두어가 다른 파일·`.part` 파일은 건드리지 않음, 기존 jar 사례가 그대로 통과.
  - `Select-ExesInUse`: BinDir 아래 것만.
- [ ] **Step 2: 실패 확인** — 시험 실행 → 새 단언 FAIL, 기존 65개 PASS.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인** — 시험 전체 PASS·종료 코드 0. ASCII 확인 명령 빈 출력. `powershell -NoProfile -Command "[void][System.Management.Automation.Language.Parser]::ParseFile('scripts\start-public.ps1',[ref]$null,[ref]$e); $e.Count"` → 0(세 스크립트 모두). 스위치가 꺼진 경로에 worker-rs 관련 `Write-Host`가 없음을 코드로 확인.
- [ ] **Step 5: Commit** — `feat(scripts): opt-in worker-rs service in the public stack`

---

### Task 9: CI와 문서

**Files:** Modify `.github/workflows/ci.yml`, `CLAUDE.md`; Create `docs/rust-pilot/README.md`.

- [ ] **Step 1: CI 잡 추가** — 잡 id `worker-rs`, 이름 `Worker RS (cargo test)`, `strategy.matrix.os: [windows-latest, ubuntu-latest]`, `defaults.run.working-directory: worker-rs`, 단계: checkout → (Windows만) `rustup set default-host x86_64-pc-windows-gnu` → `rustup show`(rust-toolchain.toml 설치) → `cargo fmt --check` → `cargo clippy --all-targets --features test-bins -- -D warnings` → `cargo test --locked --features test-bins`. 제한 15분. 기존 세 잡의 이름·내용은 바꾸지 않는다(브랜치 보호 필수 체크 이름).
- [ ] **Step 2: 유닉스에서 도는지** — 시험 가운데 Windows 전용 가정(`.exe`, `taskkill`, 경로 구분자)이 `cfg`로 갈려 있는지 훑고, 갈리지 않은 곳을 고친다(이 PC에서 리눅스 실행은 못 하므로 PR의 CI 결과로 확인한다고 PR 본문에 적는다).
- [ ] **Step 3: CLAUDE.md** — (a) 비밀 제거 목록 줄에 "세 번째: `worker-rs/contract/constants.json` — `WorkerRsContractTest`를 `-Dcontract.update=true`로 갱신" 추가, (b) 다중 워커 절에 worker-rs 한 단락: 위치, 구현 종류 `{ANALYSIS}`, 켜는 법(`WORKER_RS_ENABLED=true` → `stop-public -Only worker-rs` → 예약 작업), 워커 DTO·상수를 바꾸면 계약 시험이 실패한다는 것과 갱신 명령, 스펙 경로.
- [ ] **Step 4: `docs/rust-pilot/README.md`** — 시범의 목적 세 가지, 단계 구분, 2단계 판단 기준 다섯 가지(스펙 §13), 측정 방법(프로세스 확보 메모리를 재는 PowerShell 한 줄, 빌드 시간·줄 수 세는 명령), 후속 항목(스펙 "후속으로 남기는 것").
- [ ] **Step 5: 확인·Commit** — `./gradlew test --tests '*WorkerRsContractTest' --no-daemon` PASS, `cargo test --locked --features test-bins` PASS, yml 문법 확인(`node -e`가 아니라 눈으로 들여쓰기·키 확인). Commit `ci,docs: worker-rs CI job and pilot notes`

---

### Task 10: 격리 끝-끝 확인과 측정 (메인 세션이 직접)

**Files:** Create `docs/rust-pilot/phase1-results.md`.

- [ ] **Step 1:** worktree에서 API jar 빌드(`./gradlew bootJar --no-daemon`), `cargo build --release --locked`.
- [ ] **Step 2:** 로컬 PostgreSQL에 임시 데이터베이스를 만들고 `com` 스키마·`com."user"` 준비(`src/test/resources/init-test-schema.sql` 참고) → API jar를 다른 포트·임시 DB·시험용 워커 키로 띄운다. 스펙 §12.4의 다섯 격리 조건(네 속성 끔, stale 상한 > 10분, 임시 `REPOS_DIR`·`DEAD_LETTER_DIR`)을 모두 건다.
- [ ] **Step 3:** `작업대기` 작업 한 건을 SQL로 넣고(작은 공개 레포) Rust 워커를 붙인다 → `분석완료`, `task_analysis` 행, 사용량 누적, 하트비트 행 확인. 실패 경로 한 건(없는 브랜치)도 `분석실패` + 가려진 사유 확인.
- [ ] **Step 4:** 측정(메모리·exe 크기·빌드 시간·줄 수·단계별 시간)을 `phase1-results.md`에 적고, 임시 DB·임시 폴더·프로세스를 정리한다.
- [ ] **Step 5:** Commit `docs: worker-rs phase 1 isolated e2e results`
