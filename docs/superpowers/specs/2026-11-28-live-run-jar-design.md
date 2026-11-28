# 라이브 스택 jar 실행 전환 — 짧은 설계 (2026-11-28, 승인됨)

## 의도

Windows 라이브 스택(`scripts/start-public.ps1`)은 auth·api·worker를 `gradlew bootRun -q --no-daemon`으로 띄운다.
이 방식은 서비스마다 Gradle 실행기(약 170MB)와 일회용 Gradle 데몬(400~600MB)이 앱 JVM 옆에 계속 떠 있다.
2026-11-28 실측(프로세스가 확보한 메모리)으로 세 서비스 합계 약 3.4GB 가운데 약 2.1GB가 Gradle 몫이었다.
미리 만든 jar를 `java -jar`로 띄워 이 몫을 없앤다. 앱 동작과 운영자의 반영 절차(`stop-public.ps1 -Only <이름>` → 예약 작업)는 바꾸지 않는다.
(이 작업은 일부 서비스를 Rust로 옮겨 보는 시범의 사전 단계이기도 하다 — 비교가 "Rust 대 Gradle+JVM"이 아니라 "Rust 대 JVM"이 되게.)

## 목표 / 비목표

- 목표: 세 서비스를 jar로 실행, Gradle 상주 프로세스 0개, 반영 절차·로그 위치·기동 확인 방식 유지.
- 비목표: JVM 튜닝(힙 상한 등 — 전환 뒤 측정하고 따로 판단), macOS용 `start-*.sh`(라이브 아님), 원격 워커 2대(이미 jar), 프런트·인터뷰 서비스.
- 반목표: 빌드가 실패했는데 이전 jar로 조용히 뜨는 것, 실행 중인 jar 파일을 덮어쓰는 것(원격 워커에서 미로드 클래스가 깨진 적이 있다).

## 접근

| 방식 | 장단점 | 결정 |
|---|---|---|
| A. `start-public.ps1`이 띄우기 직전에 라이브 체크아웃에서 `bootJar` → 커밋 번호 이름으로 실행 폴더에 복사 → `java -jar` | 반영 절차 그대로, 빌드 장소가 지금 `bootRun`과 같음 | **선택** |
| B. 별도 반영 스크립트가 worktree에서 빌드해 jar를 놓고 시작 스크립트는 jar만 실행 | 빌드와 실행이 분리되지만 운영자 절차가 바뀜(빌드 단계 추가) | 기각 |
| C. `bootRun` 유지 + Gradle 데몬 메모리만 줄이기(`org.gradle.jvmargs`) | 상주 프로세스는 그대로, 효과 작음 | 기각 |

## 설계

1. **빌드**: 서비스를 띄우기 직전에 그 서비스 소스 폴더(commanCenter 라이브 체크아웃, netis-auth)에서 `gradlew.bat bootJar -q --no-daemon`.
   - 소스 상태가 지난 빌드와 같으면(커밋이 같고 작업 트리가 깨끗하면 = 그 커밋 이름의 jar가 실행 폴더에 이미 있으면) 다시 빌드하지 않는다.
   - 작업 트리에 커밋 안 된 변경이 있으면 매번 다시 빌드한다(이름에 시각을 붙인다).
   - `-Rebuild` 스위치로 강제 재빌드.
   - 빌드 로그는 `.run\<이름>-build.log`(이전 것은 `.run\logs`로 — 기존 `Save-PreviousLog` 규칙).
   - 빌드 실패면 그 서비스는 `[FAIL]`이고 이전 jar로 띄우지 않는다.
2. **실행 파일**: `build\libs`의 boot jar(`-plain.jar` 제외, 이번 빌드가 만든 것)를 `%USERPROFILE%\netis-maker\run\jars\<접두어>-<sha7>[-dirty-<yyyyMMddHHmmss>].jar`로 복사해 그 사본을 실행한다. 접두어는 `netis-maker`(api·worker 공용), `netis-auth`.
   - 실행 중인 jar는 덮어쓰지 않는다(이름이 커밋마다 다르고, 같은 이름이 이미 있으면 다시 복사하지 않는다).
   - 정리: 접두어별로 최근 5개와 지금 실행 중인 java 프로세스가 쓰는 jar를 남기고 나머지를 지운다(지우기 실패는 경고만).
3. **JVM 옵션**: `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Duser.language=ko -Duser.country=KR` — 지금 로그가 UTF-8이고 로케일이 ko/KR인 것을 그대로 지킨다. `bootRun` 전용 빠른 기동 옵션 `-XX:TieredStopAtLevel=1`은 넣지 않는다. 워커는 `--spring.profiles.active=worker`를 jar 인자로.
4. **env·래퍼**: 지금처럼 `Start-Service-Proc`(cmd 래퍼 + 로그 리다이렉트 + env 주입)으로 띄운다. JAVA_HOME은 계속 넘기고(자식 도구가 쓸 수 있다) 명령은 `"$JavaHome\bin\java.exe"` 절대 경로.
5. **프로세스 찾기·끄기**(`scripts/public-proc.ps1`): 워커 JVM은 래퍼(cmd)의 자식 `java.exe` 중 명령줄에 실행 jar 경로와 `--spring.profiles.active=worker`가 있는 가장 최근 것(`Select-WorkerJvm`의 기준을 `build\classes` 경로에서 바꾼다). api·auth·front는 지금처럼 포트로. 끄기(`Stop-ServiceRecord`)는 구조가 cmd → java로 단순해질 뿐 그대로.
6. **되돌리기**: 라이브 체크아웃을 이전 커밋으로 돌리고 재기동 — 그 커밋 jar가 남아 있으면 빌드 없이 뜬다. 이 변경 자체를 되돌리면(병합 커밋 revert) 다음 재기동부터 `bootRun`으로 돌아간다.
7. **시험**: `scripts/tests/public-proc.test.ps1`(Pester 없이 단순 단언)에 새 순수 함수(소스 키 → jar 이름, 정리 대상 고르기, 워커 JVM 고르기) 시험. 라이브 전환 뒤 세 서비스 기동 로그·로그 한글(대체 문자 0개)·워커 claim·메모리 전후를 확인한다.

## 전환 절차

PR 병합 → 라이브 체크아웃 `git pull` → 진행 중 구현·배포·종료가 없을 때 `.\scripts\stop-public.ps1 -Only auth,api,worker` → `Start-ScheduledTask -TaskName netisMaker-public`(재기동 동안 잠깐 로그인 불가) → 확인·재측정.

## 남은 것 (구현자 몫)

- 함수 이름·파일 배치, 빌드 산출물 고르기 세부, 경고 문구.
