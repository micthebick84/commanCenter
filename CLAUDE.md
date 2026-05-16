# CLAUDE.md — netisMaker

이 파일은 netisMaker 작업 시 Claude Code에게 주는 가이드.
워크스페이스 상위 `../CLAUDE.md`(netis-auth/backend/nuxt_practice 다룸)와 함께 자동 로드됨.

## 프로젝트 개요

| | |
|---|---|
| 역할 | 사내 코드 분석 큐 시스템 (claude code 구독 활용) |
| 포트 | API 8090, Worker(데몬, 포트 X) |
| 프로파일 | `api` (사내 서버) / `worker` (운영자 macOS) — `--spring.profiles.active=` 로 택일 |
| DB | PostgreSQL `com` 스키마 4테이블 (`task`, `task_analysis`, `task_status_history`, `worker_heartbeat`) |
| Auth | netis-auth(:9000) OAuth2 Authorization Code + PKCE. JWT issuer-uri로 검증 |
| 프론트 | Nuxt 3 + Quasar (`frontend/`), 별도 dev server 3001 |

## Build & Run

```bash
./gradlew build                                             # 빌드
./gradlew bootRun                                           # API 서버 (:8090, profile=api 기본값)
./gradlew bootRun --args='--spring.profiles.active=worker'  # 워커 데몬
./gradlew test
cd frontend && npm run dev                                  # 프론트 :3001
```

워커 실행 환경변수:
```bash
API_BASE_URL=http://localhost:8090 \
WORKER_API_KEY=dev-only-change-me \
WORKER_ID=mac-worker-1 \
./gradlew bootRun --args='--spring.profiles.active=worker'
```
`CLAUDE_CLI` 미지정 시 자동 탐색 (PATH → `~/.local/bin` → `~/.claude/local` → `/opt/homebrew/bin` → `/usr/local/bin` → `/usr/bin`).

## 핵심 설계 제약

- **claude 구독 1개 = macOS 1대 = 워커 1대 = 직렬 분석** (DESIGN.md P6). 동시성은 V2 이후.
- **워커는 사내망 외부에서 폴링**: `/worker/*` 엔드포인트는 `X-Worker-API-Key` 인증. JWT 아님.
- **모든 분석 결과는 한국어 마크다운**: `application-worker.yml`의 `prompt-template` 참고. 섹션 헤더는 파서가 사용하므로 변경 금지.
- **MCP 자동 주입**: `WorkerMcpSupport`가 `~/.claude.json`의 글로벌+프로젝트 mcpServers를 머지해 `~/netis-maker/worker-mcp.json` 생성. claude -p 호출 시 `--mcp-config + --strict-mcp-config + --allowedTools` prepend.
- **프롬프트는 stdin으로 전달**: `--allowedTools <tools...>` variadic이 뒤따라오는 prompt arg를 삼키므로 arg 대신 stdin 사용. ARG_MAX/ps 노출 동시 회피.

## Flyway 마이그레이션

- `src/main/resources/db/migration/V{n}__name.sql`
- `baseline-version: 0` (com 스키마에 netis-backend 잔존 객체 있어도 V1부터 적용)
- 신규 컬럼은 `NOT NULL DEFAULT ...`로 기존 row 안전. `worker_heartbeat.mcps`가 그 예 (`V2__worker_mcps.sql`).

## 자주 보는 코드

| 변경 | 파일 |
|---|---|
| 워커 큐 클레임/결과 처리 | `service/WorkerService.java`, `controller/WorkerController.java` |
| claude exec | `workerdaemon/ClaudeExecAdapter.java` (CLI 자동탐색 포함) |
| MCP 주입 | `workerdaemon/WorkerMcpSupport.java` |
| 워커 메인 루프 (10초 heartbeat, 5초 폴링) | `workerdaemon/WorkerMainLoop.java` |
| 작업 등록 다이얼로그 (브랜치 자동 동기화 + MCP 안내) | `frontend/pages/tasks/index.vue` |
| 워커 헬스 (관리자) | `frontend/pages/admin/workers.vue`, `controller/WorkerHealthController.java` |

## 운영자 환경 권장 셋업

분석 결과/세션 진행을 자동으로 옵시디언 노트에 누적하려면:

→ **`scripts/claude-hooks/README.md`** 참고

요약: PostToolUse(Bash)에서 git commit/push만 한 줄 자동 로깅 + SessionEnd/PreCompact에서 `claude -p`로 transcript 요약. 4단계 설치 (mkdir/cp/매핑 수정/settings.json 병합). 다른 프로젝트도 매핑 한 줄 추가로 확장.

## 환경 메모

- **admin 시드 비밀번호**: `password123` (netis-auth `data.sql`)
- **계정 잠금 해제** (5회 실패 시 30분 lock):
  ```sql
  UPDATE public.users SET account_non_locked=true, failed_attempt=0, lock_time=NULL WHERE username='admin';
  ```
- **MCP 합본**: `~/netis-maker/worker-mcp.json` (워커 부팅 시 자동 생성/갱신)
- **레포 캐시**: `~/netis-maker/repos/{owner}/{repo}` (depth=1 clone, 이후 fetch+reset)

## 진행상황 노트

옵시디언: `Andy/netisMaker-진행상황-YYYY-MM-DD.md` (운영자 vault).
hooks 셋업 후엔 commit/push마다 자동 한 줄 + 세션 종료 시 자동 요약 섹션.
