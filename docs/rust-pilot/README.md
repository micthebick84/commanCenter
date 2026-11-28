# Rust 워커 시범

워커 데몬 일부를 Rust(`worker-rs/`)로 옮겨 보는 시범이다. 설계는 `docs/superpowers/specs/2026-11-28-rust-worker-skeleton-design.md`, 구현 계획은 `docs/superpowers/plans/2026-11-28-rust-worker-skeleton.md`.
이 문서는 시범의 목적, 단계, 다음 단계로 가는 판단 기준과 측정 방법, 남긴 후속 항목을 모은다. 측정 결과는 `docs/rust-pilot/phase1-results.md`에 따로 적는다(아직 없음).

## 시범의 목적 (세 가지)

1. **메모리 확보** — 워커 JVM의 상주 메모리(약 315MB)를 줄인다. Java 워커를 완전히 내릴 때만 얻는다. 1단계에서는 Rust 프로세스만큼 오히려 늘어난다(목표: 30MB 이하).
2. **속도** — 기동 시간(초 단위 → 즉시)과 상주 메모리만 줄어든다. 작업 시간은 claude·git·docker·maven 하위 프로세스가 좌우하므로 작업 처리 속도 이득은 거의 없다.
3. **다른 서비스에 Rust를 들이기 전 시험** — 순수 Rust 의존성만으로 Windows(gnu 툴체인)와 CI에서 빌드·시험·운영이 되는지, Java와 계약 파일로 어긋남을 막을 수 있는지 먼저 본다.

## 단계

| 단계 | 범위 | 상태 |
|---|---|---|
| 1단계 | 뼈대 + 분석(ANALYSIS) 경로: 하트비트, 기동 알림, 폴링, git 캐시, claude 실행, 결과 파싱, 보고 재시도, 유실 보고 재전송, claude 로그인 판정. 기본은 꺼짐(`WORKER_RS_ENABLED=true`일 때만 기동) | 이 브랜치 |
| 2단계 | 배포·배포 중지 처리 | 1단계 판단 기준(아래)을 모두 넘긴 뒤 |
| 이후 | 종료·구현·디자인 처리 여부 | 2단계 결과를 보고 결정 |

숨기지 않을 사실: 분석 경로는 현재 운영에서 거의 도달하지 않는다(작업 등록은 승인대기 → 인터뷰로 가고, 워커 분석 큐는 `분석실패` 재시도 때만 생긴다). 1단계의 분석은 가장 짧은 끝-끝 경로라서 고른 시험용 레일이고, 실제 일감 검증은 2단계(배포)부터다.

## 켜고 끄기

- 켜기: `public.env`에 `WORKER_RS_ENABLED=true`(선택: `WORKER_RS_ID`, `WORKER_RS_KINDS`, 기본 `win-rs-worker-1`·`ANALYSIS`) 추가 → `scripts\stop-public.ps1 -Only worker-rs` → 작업 스케줄러로 기동. 스위치가 꺼져 있으면 시작 스크립트는 worker-rs에 대해 아무것도 하지 않는다.
- 끄기: 스위치를 지우고 `stop-public.ps1 -Only worker-rs`. Java 워커는 손대지 않으므로 그것으로 원래 상태다. 하트비트 행은 "끊김"으로 남으니 필요하면 지운다.
- 워커 DTO·비밀 env 목록·상태 이름·분석 프롬프트를 바꾸면 Java `WorkerRsContractTest`가 `worker-rs/contract/`와 달라 실패한다. 갱신: `./gradlew test --tests '*WorkerRsContractTest' -Dcontract.update=true --no-daemon`.

## 2단계로 가는 판단 기준 (다섯 가지, 스펙 §13)

하나라도 안 되면 원인과 함께 보고하고 멈춘다.

1. 상주 메모리 30MB 이하.
2. 계약 시험이 양쪽 CI(Java Backend 잡, `Worker RS (cargo test)` 잡)에서 통과.
3. Java와 Rust의 레포 잠금 호환 확인(구현 초기에 실측해 성립을 확인함).
4. 격리 끝-끝 성공(임시 API·임시 DB·실제 claude로 작은 공개 레포 분석 → `분석완료`와 결과·사용량 저장).
5. 라이브에서 기동·정지 3회 이상 문제 없음.

## 측정 방법

PC는 Windows, 셸은 PowerShell이다. 아래 명령은 `worker-rs/`(Rust)와 저장소 루트(Java 줄 수)에서 돌린다.

상주 메모리(켠 지 1시간 뒤 한 번, Java 워커와 나란히):

```powershell
Get-Process netis-worker-rs | Select-Object Id, @{n='PrivateMB';e={[int]($_.PrivateMemorySize64/1MB)}}, @{n='WorkingSetMB';e={[int]($_.WorkingSet64/1MB)}}
# Java 워커(JVM)는 같은 식으로 java 프로세스 중 워커 것을 골라 비교한다
Get-Process java | Select-Object Id, @{n='PrivateMB';e={[int]($_.PrivateMemorySize64/1MB)}}, @{n='WorkingSetMB';e={[int]($_.WorkingSet64/1MB)}}
```

판단 기준의 "상주 메모리"는 확보 메모리(`PrivateMB`)로 본다.

빌드 시간(처음은 `cargo clean` 뒤, 증분은 소스 한 줄을 고친 뒤):

```powershell
cd worker-rs
cargo clean
Measure-Command { cargo build --release --locked }
# 증분
Measure-Command { cargo build --release --locked }
(Get-Item target\release\netis-worker-rs.exe).Length / 1MB   # 실행 파일 크기(MB)
```

줄 수(Rust 코드·시험 대 같은 범위의 Java 클래스):

```powershell
# Rust: 코드 + 시험(contract 골든 파일은 제외)
Get-ChildItem worker-rs\src, worker-rs\tests -Recurse -Filter *.rs | Get-Content | Measure-Object -Line
# Java: 이 시범이 옮긴 범위의 클래스
$java = 'ClaudeAuthProbe','ClaudeExecAdapter','DeadLetterReplayJob','GitRepoCache','PromptResultParser','ResultReporter','SilentLossTracker','WorkerMcpSupport'
Get-ChildItem src\main\java\com\hamonsoft\netismaker\workerdaemon -Filter *.java | Where-Object { $java -contains $_.BaseName } | Get-Content | Measure-Object -Line
```

끝-끝 분석 한 건의 단계별 시간(레포 준비·claude·파싱·보고)은 격리 끝-끝 확인 때 워커 로그의 시각으로 읽는다.

## 유닉스에서 도는지

시험은 `.exe`·`taskkill` 같은 Windows 전용 부분을 `cfg(windows)`/`cfg(unix)`로 나눠 두었지만 이 PC에서는 리눅스를 못 돌린다. 실제 증거는 PR의 CI 결과(`ubuntu-latest`)다. CI 명령은 모두 `--features test-bins`를 붙인다(없으면 가짜 claude를 쓰는 시험이 조용히 빠진다).

## 후속으로 남긴 것 (이 단계에서 고치지 않음)

- Java: 사용량이 든 유실 보고 줄을 다시 읽지 못할 가능성(확인 필요), `git rev-parse` 종료 코드 미검사, API의 분석 정체 회수 5분과 워커 분석 상한 10분의 불일치.
- 유닉스에서의 Java·Rust 레포 잠금 호환(원격 macOS 워커로 넓힐 때).
- 2단계 설계: 배포·배포 중지, `supports` 토큰, PC 단위 주기 작업(사용량 수집·worktree 정리·레포 캐시 정리·Docker GC·배포 정합)의 소유.
- `Worker RS (cargo test)`를 main 브랜치 보호의 필수 체크로 넣을지(저장소 설정, 사용자 결정).
