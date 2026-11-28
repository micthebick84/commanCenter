# 라이브 스택 jar 실행 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `start-public.ps1`이 auth·api·worker를 `gradlew bootRun` 대신 커밋별 jar 사본으로 `java -jar` 실행한다.

**Architecture:** 순수 판정 함수(jar 이름·정리 대상·워커 JVM 고르기)는 `scripts/public-proc.ps1`에 두고 시험한다. 빌드·복사·실행은 `start-public.ps1`의 `Get-ServiceJar` 한 곳.

**Tech Stack:** Windows PowerShell 5.1, Gradle(`bootJar`), JDK 21.

**Spec:** `docs/superpowers/specs/2026-11-28-live-run-jar-design.md`

## Global Constraints

- 스크립트는 ASCII만(PowerShell 5.1이 BOM 없는 UTF-8을 잘못 읽는다).
- JVM 옵션: `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Duser.language=ko -Duser.country=KR`, `-XX:TieredStopAtLevel=1` 없음.
- 실행 폴더 `%USERPROFILE%\netis-maker\run\jars`, 이름 `<접두어>-<sha7>.jar` / `<접두어>-<sha7>-dirty-<yyyyMMddHHmmss>.jar`, 접두어 `netis-maker`·`netis-auth`, 접두어별 최근 5개 + 실행 중인 것 유지.
- 빌드 실패면 `[FAIL]`, 이전 jar로 띄우지 않는다. 실행 중인 jar 파일은 덮어쓰지 않는다.
- 시험은 라이브 `.run`·라이브 프로세스를 건드리지 않는다(기존 시험 파일 규칙).

## Review Focus

- 라이브 체크아웃에 커밋 안 된 변경이 있을 때: 매번 새 이름으로 다시 빌드해야 한다(같은 커밋 jar 재사용 금지).
- api와 worker가 같은 실행에서 같은 jar를 쓸 때: 빌드는 한 번만.
- `build\libs`에 예전 `-plain.jar`·다른 버전 jar가 남아 있을 때: 이번 빌드가 만든 boot jar 하나만 골라야 한다(0개·2개 이상이면 실패).
- 정리 중 실행 중인 jar(다른 서비스가 쓰는 옛 커밋 jar)를 지우지 않아야 한다.
- 워커 JVM 찾기: 다른 java(worktree 시험 JVM, 원래 워커의 옛 JVM)를 잘못 고르지 않아야 한다.

---

### Task 1: 순수 함수와 시험 (`scripts/public-proc.ps1`, `scripts/tests/public-proc.test.ps1`)

- [ ] `Get-JarName([string]$Prefix, [string]$Sha, [bool]$Dirty, [datetime]$Now)` → `netis-maker-1a2b3c4.jar` / `netis-maker-1a2b3c4-dirty-20261128153000.jar`(sha는 앞 7자).
- [ ] `Select-JarsToPrune($Files, [string]$Prefix, [int]$Keep, [string[]]$InUse)` → 지울 전체 경로 목록(접두어가 같은 `.jar`만, LastWriteTime 최신순 `$Keep`개와 `$InUse`(대소문자 무시) 제외).
- [ ] `Select-BootJar($Files, [datetime]$NotBefore)` → `-plain.jar`가 아니고 LastWriteTime ≥ `$NotBefore`인 `.jar`가 정확히 하나면 그 경로, 아니면 `$null`.
- [ ] `Select-WorkerJvm($Processes, [int]$WrapperPid, [string]$JarPath)`로 바꾼다: `java.exe`, `ParentProcessId -eq $WrapperPid`, 명령줄에 `$JarPath`와 `--spring.profiles.active=worker`(대소문자 무시) — 가장 최근 것. `Find-AppPid`의 worker 분기는 `$Root` 자리에 jar 경로를 받는다.
- [ ] 시험: 위 각 함수의 정상·경계(더러운 트리 이름, 실행 중 jar 보호, plain jar·오래된 jar 무시, 2개면 null, 다른 부모·worktree JVM 무시). 실행: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\public-proc.test.ps1` → FAIL 0.

### Task 2: `start-public.ps1` 빌드·실행 전환

- [ ] 파라미터 `-Rebuild`, 실행 폴더 변수 `$JarDir`.
- [ ] `Get-ServiceJar([string]$Prefix, [string]$SrcDir, [string]$LogName)`: `git -C` HEAD·porcelain로 sha·dirty → `Get-JarName` → 깨끗하고 파일이 있고 `-Rebuild`가 아니면 그대로 반환(같은 실행 안 재사용은 캐시 해시), 아니면 `Invoke-Logged`로 `gradlew.bat bootJar -q --no-daemon`(JAVA_HOME 설정) → `Select-BootJar` → 복사 → 경로 반환, 실패면 `$null`.
- [ ] auth·api·worker 명령을 `"<java.exe>" <옵션> -jar "<jar>" [--spring.profiles.active=worker]`로. 실패 시 `[FAIL] <이름> build - see .run\<이름>-build.log`.
- [ ] worker `Register-Service`에 jar 경로를 넘긴다.
- [ ] 끝에 접두어별 `Select-JarsToPrune`(실행 중 = `Win32_Process` java.exe 명령줄에 들어 있는 jar 경로) → 삭제(실패는 경고).
- [ ] `--no-daemon` 설명 주석·머리 주석을 새 구조로 고친다. `stop-public.ps1`·`public-proc.ps1`의 Gradle 트리 주석도.

### Task 3: 문서

- [ ] `CLAUDE.md` 환경 메모의 "라이브 bootRun은 `--no-daemon`" 문단을 jar 실행 설명으로 바꾼다(빌드 시점·실행 폴더·`-Rebuild`·되돌리기, 일회용 데몬 이야기는 빌드에만 해당).

### Task 4: 확인

- [ ] 시험 파일 통과, `powershell -NoProfile -Command "[ScriptBlock]::Create((Get-Content scripts\start-public.ps1 -Raw)) | Out-Null"`로 구문 확인.
- [ ] (병합 뒤, 라이브) 재기동 → 세 서비스 ready, 로그 한글 대체 문자 0, 워커 heartbeat·claim URL, 메모리 전후 표.
