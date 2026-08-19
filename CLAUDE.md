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

## 프론트엔드 코드 스타일 (`frontend/`)

- **작은따옴표(single quote) + 세미콜론 없음.** 코드베이스 전체가 이 스타일로 손수 작성됨 (워크스페이스 `nuxt_practice`와 동일 컨벤션). `<script setup lang="ts">`, 2-space, trailing comma.
- `frontend/.prettierrc`에 `{"singleQuote": true, "semi": false}` 존재 → `lint-prettier`가 따옴표/세미콜론을 컨벤션대로 유지(예전처럼 큰따옴표로 안 뒤집힘). `.prettierignore`로 `.nuxt/` 등 생성물 제외.
- ⚠️ **그래도 `npm run lint-prettier`(=`prettier --write`)를 `frontend/` 전체에 돌리지 말 것**: 코드베이스가 긴 줄을 손으로 유지(prettier 정규형 아님)하므로 전체 실행 시 ~12–18개 파일이 **줄바꿈만으로 churn**된다(따옴표는 안전). 포맷이 필요하면 단일 파일 대상으로만 쓰거나, 별도 일회성 정규화 PR로 처리할 것.
- `npm run lint`(eslint)은 ESLint v9 flat config 부재로 현재 동작하지 않음(별도 정비 필요).

워커 실행 환경변수:
```bash
API_BASE_URL=http://localhost:8090 \
WORKER_API_KEY=dev-only-change-me \
WORKER_ID=mac-worker-1 \
./gradlew bootRun --args='--spring.profiles.active=worker'
```
`CLAUDE_CLI` 미지정 시 자동 탐색 (PATH → `~/.local/bin` → `~/.claude/local` → `/opt/homebrew/bin` → `/usr/local/bin` → `/usr/bin`).

## 핵심 설계 제약

- **claude 구독 1개 = macOS 1대 = 워커 1대 = 직렬 처리** (DESIGN.md P6). 분석/구현이 같은 큐를 공유. 동시성은 V2 이후.
- **워커는 사내망 외부에서 폴링**: `/worker/*` 엔드포인트는 `X-Worker-API-Key` 인증. JWT 아님.
- **모든 분석 결과는 한국어 마크다운**: `application-worker.yml`의 `prompt-template` 참고. 섹션 헤더는 파서가 사용하므로 변경 금지.
- **MCP 자동 주입**: `WorkerMcpSupport`가 `~/.claude.json`의 글로벌+프로젝트 mcpServers를 머지해 `~/netis-maker/worker-mcp.json` 생성. claude -p 호출 시 `--mcp-config + --strict-mcp-config + --allowedTools` prepend.
- **프롬프트는 stdin으로 전달**: `--allowedTools <tools...>` variadic이 뒤따라오는 prompt arg를 삼키므로 arg 대신 stdin 사용. ARG_MAX/ps 노출 동시 회피.

## 다중 워커 운영 (V1.2)

같은 머신에서 워커 N 프로세스를 동시에 띄울 수 있다 (공유 큐 + SKIP LOCKED).

```bash
# 터미널 1
WORKER_ID=mac-worker-1 ./gradlew bootRun --args='--spring.profiles.active=worker'

# 터미널 2 (같은 머신, 다른 WORKER_ID)
WORKER_ID=mac-worker-2 ./gradlew bootRun --args='--spring.profiles.active=worker'
```

자동 격리 사항:
- **MCP config**: `worker-mcp-{WORKER_ID}.json` 으로 파일 충돌 방지
- **GitRepoCache**: `~/netis-maker/repos/{owner}/{repo}.lock` FileLock으로 cross-process fetch 직렬화
- **WorktreeService.create**: 같은 락 안에서 worktree add → `.git/worktrees/` 메타 race 방지
- **heartbeat**: worker_id별로 별도 row, admin/workers UI에 둘 다 표시

**진짜 천장은 Anthropic quota**. 워커가 N대지만 같은 user 구독을 공유 → 시간당 메시지 cap에 빨리 도달하면 모든 워커가 retry/backoff로 자연 직렬화. Max 5x 이상 권장.

## 작업 상태머신 (V1.1 — 구현 파이프라인)

```
[작업대기] → [분석중] → [분석완료] ──(admin 승인)──→ [구현대기] → [구현중] ──┬─→ [PR생성]
                          ↓                                                  └─→ [구현실패]
                       (실패)
                          ↓
                       [분석실패] ──(retry)──→ [작업대기]
```

- **분석**: PENDING → IN_PROGRESS → COMPLETED/FAILED. 결과는 `task_analysis.markdown_result`.
- **승인**: admin이 `/tasks/{id}/approve` → `analysis.approved=true` + `task.status=APPROVED`. 즉시 큐 진입.
- **구현**: 같은 워커가 APPROVED를 claim → `WorktreeService.create` → `claude -p` (worktree에서) → `git commit/push` → `gh pr create --draft` → `task.status=PR_CREATED` (+ pr_url/pr_number/head_branch/head_sha 저장).
- **실패 시**: `IMPLEMENTATION_FAILED` + 사유. 부분 진행분(브랜치까지 push 등)은 보존, worktree도 디버그용 보존.

## 구현 단계 워커 사전 조건

분석만 할 때보다 추가로 필요:

| 도구 | 용도 | 설치/설정 |
|---|---|---|
| `gh` CLI | PR 생성 | `brew install gh` + `gh auth login` (또는 `GH_TOKEN` env) |
| git push 권한 | 브랜치 푸시 | GitRepoCache가 `https://oauth2:$GITHUB_PAT@github.com/...`로 clone하므로 PAT만 있으면 OK |
| worktree 디스크 공간 | 브랜치당 별도 디렉토리 | 기본 `~/netis-maker/worktrees/{owner}/{repo}/task-{id}` |

PR 본문/브랜치 prefix/timeout은 `application-worker.yml`의 `netis-maker.worker.{branch-prefix,implementation-timeout,git-user-*,implementation-prompt-template}`에서 조정.

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
| 워커 메인 루프 (kind=ANALYSIS/IMPLEMENTATION 분기) | `workerdaemon/WorkerMainLoop.java` |
| worktree 라이프사이클 | `workerdaemon/WorktreeService.java` |
| git commit/push + `gh pr create` | `workerdaemon/GitOpsService.java` |
| 작업 등록 다이얼로그 (브랜치 자동 동기화 + MCP 안내) | `frontend/pages/tasks/index.vue` |
| 작업 상세 + 구현 승인 + PR 링크 | `frontend/pages/tasks/[id].vue` |
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

## 공개 배포 주소 (`task-N.micthebick.dev`)

배포된 앱을 외부에서 `https://task-{id}.micthebick.dev`로 접속. 로컬 **Traefik**(Docker provider) 리버스 프록시 + Cloudflare Tunnel 와일드카드 ingress.

- **인프라(1회 셋업)**: `./scripts/setup-public-deploy.sh` — `netis-deploy` 도커 네트워크 + Traefik(`traefik:v3.7`, 호스트 `:18080`) 기동. cloudflared `~/.cloudflared/config.yml`에 `*.micthebick.dev → http://localhost:18080` ingress + 와일드카드 DNS CNAME(`*.micthebick.dev` → 터널). **이미 라이브**(2026-06-29 외부 스모크 통과).
- **켜기**: `./scripts/start-all.sh`가 인프라(`netis-deploy` 네트워크 + `traefik` 컨테이너)를 감지해 **자동으로 `DEPLOY_PUBLIC_ACCESS_ENABLED=true`를 워커에 주입**한다(기동 배너에 `공개 배포 주소=ON/OFF` 표시). 명시 override는 `DEPLOY_PUBLIC_ACCESS_ENABLED=true|false ./scripts/start-all.sh`. 수동으로 `./gradlew bootRun --args='--spring.profiles.active=worker'`를 띄우면 yml 기본값 `false`이므로 env를 직접 줘야 한다. OFF면 기존 `http://localhost:{hostPort}` 동작 그대로(회귀 없음). 켜면 워커가 배포 컨테이너에 `--network netis-deploy` + Traefik Host 라벨 부착, `deploy_url`=공개 URL.
- **⚠️ 이 값은 배포 시점의 워커 프로세스 env로 결정된다.** OFF 상태 워커가 배포하면 `deploy_url`에 `http://localhost:{port}`가 **DB에 박히고**, 컨테이너에 Traefik 라벨/네트워크가 안 붙어 공개 URL이 404가 된다. 고치려면 워커를 ON으로 재기동한 뒤 **재배포**해야 한다(URL 문자열만 수정으론 라우팅이 안 생김).
- **Traefik 라이프사이클**: `./scripts/start-traefik.sh`(기동), `docker rm -f traefik`(정지). `stop-all.sh`는 Traefik **안 멈춤**(`--restart unless-stopped` 장수 프록시 — 의도적). `status.sh`가 실행 여부 표시.
- **⚠️ Traefik 버전**: docker provider가 데몬 API와 버전 호환돼야 함. `v3.1`은 Docker Engine 29와 비호환(provider가 컨테이너 디스커버리 실패→Host 라우팅 404). 현재 `v3.7` 핀. Engine 업글 시 Traefik도 맞춰 올릴 것.
- **undeploy**: `docker rm -f` 시 Traefik 라우트 자동 소멸(별도 정리 불필요).

## 진행상황 노트

옵시디언: `Andy/netisMaker-진행상황-YYYY-MM-DD.md` (운영자 vault).
hooks 셋업 후엔 commit/push마다 자동 한 줄 + 세션 종료 시 자동 요약 섹션.
