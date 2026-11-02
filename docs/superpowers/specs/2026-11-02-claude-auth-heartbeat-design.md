# 워커·인터뷰 서비스의 claude 로그인 상태 보고 — 설계

날짜: 2026-11-02 · 관련: DESIGN §12(워커 헬스), 스펙 `2026-06-27-worker-silent-loss-observability`

## 1. 왜

2026-11-02 작업 19의 인터뷰 세션 31·32가 `Failed to authenticate: OAuth session expired and could not be refreshed`로 실패했다. 이 PC(10.1.1.77)의 인터뷰 서비스와 워커 `win-worker-1`은 `CLAUDE_CODE_OAUTH_TOKEN` 없이 CLI 로그인(`~/.claude/.credentials.json`)을 쓰는데, 그 refresh token의 `refreshTokenExpiresAt`이 2026-10-31 11:23 KST였다(10-01 로그인 + 30일로 보인다 — 그사이 access token은 여러 번 갱신됐는데 이 시각은 그대로였다). 11-02 10:20 첫 실행이 갱신에 실패했고 CLI가 토큰을 비웠다(`claude auth status` → `loggedIn: false`).

관리 화면 `워커 헬스`에는 이미 "Claude OAuth" 칸(`worker_heartbeat.claude_session_ok`)이 있었지만 워커가 늘 `null`을 보내 칩이 `—`였고, 인터뷰 서비스는 이 테이블에 행이 없었다. 그래서 만료 이틀 전에도, 만료된 뒤에도 아무 신호가 없었다.

원격 워커 두 대(10.1.1.75 Windows, 10.1.3.6 Mac)는 `worker.env`의 setup-token(`CLAUDE_CODE_OAUTH_TOKEN`, 1년)을 써서 영향이 없었다.

## 2. 목표 / 비목표

목표
- 워커와 인터뷰 서비스가 claude 로그인이 살아 있는지 주기적으로 판정해 하트비트로 보고한다 — 죽은 PC는 관리 화면에 빨간 칩.
- refresh token 만료가 3일 안이면 관리 화면에서 주황 칩과 페이지 위 경고로 미리 보인다.
- 판정에 모델 호출(비용·한도)을 쓰지 않는다. 토큰 값은 로그·API·화면 어디에도 싣지 않는다.
- setup-token을 쓰는 원격 워커와 CLI 로그인을 쓰는 이 PC가 둘 다 맞게 판정된다.

비목표
- 자동 재로그인·토큰 교체(사람이 한다).
- 메일·전역 알림(§7 후속).
- setup-token 자체의 만료 예측 — 토큰만 보고는 만든 날짜를 알 수 없다(§7).

## 3. 판정 (워커 `ClaudeAuthProbe` = 인터뷰 서비스 `claudeAuth.ts`, 같은 규칙)

순서대로 보고 처음 답이 나온 곳에서 멈춘다.

1. **env `CLAUDE_CODE_OAUTH_TOKEN`이 비어 있지 않으면 OK** — 방식 `ENV_TOKEN`, 만료 시각 없음. CLI도 이 env를 저장된 로그인보다 먼저 쓴다. 원격 워커는 `run-worker.*`가 `worker.env`를 프로세스 env로 넣으므로 JVM이 직접 본다.
2. **`{CLAUDE_CONFIG_DIR 또는 ~/.claude}/.credentials.json`의 `claudeAiOauth`** — 방식 `CREDENTIALS`.
   - `refreshToken`·`accessToken`이 둘 다 비었으면 **NG**(로그아웃, 또는 CLI가 갱신에 실패해 비운 상태 — 11-02 상태).
   - 만료 시각 = `refreshTokenExpiresAt`(epoch ms). refresh token이 없고 access token만 있으면 `expiresAt`.
   - 만료 시각이 지났으면 **NG**(CLI가 아직 비우기 전 — 10-31 11:23 ~ 11-02 10:20 상태), 아니면 **OK**. 만료 시각 필드가 없으면(구버전 CLI) OK, 만료 시각 없음.
   - 파일을 읽어 숫자·빈 값 여부만 본다. 토큰 문자열은 변수 밖으로 나가지 않는다.
3. **파일이 없거나 `claudeAiOauth`가 없으면**(macOS는 키체인에 둔다) `claude auth status --json`의 `loggedIn` — 방식 `CLI`, 만료 시각 없음. 로그인 안 됨이면 CLI가 종료 코드 1을 내므로 종료 코드가 아니라 출력 JSON을 본다. 30초 상한, 스택 비밀 env는 뺀다.
4. 위에서 답을 못 내면(파일 파싱 실패 뒤 CLI도 실패) 전부 `null` — 화면 `—`.

`auth status`를 1차로 쓰지 않는 이유: 만료 시각이 지나도 CLI가 다음 갱신을 시도하기 전까지는 `loggedIn: true`를 낸다(토큰이 아직 파일에 있으므로). 10-31~11-02 사이를 잡으려면 파일의 시각을 봐야 한다.

주기: 하트비트(워커 10초, 인터뷰 20초)마다가 아니라 **5분 캐시**. 워커는 별도 `@Scheduled(fixedDelay)`가 판정해 필드에 두고 하트비트는 읽기만 한다(하트비트 스레드가 CLI 실행에 막히지 않게). 설정 `netis-maker.worker.claude-auth-check-interval-seconds`(기본 300) / 인터뷰 `CLAUDE_AUTH_CHECK_INTERVAL_MS`(기본 300000). 상태가 바뀔 때만 로그를 남긴다(NG·임박은 WARN).

## 4. 하트비트·DB·API

- `WorkerHeartbeatRequest`에 끝에 두 필드: `claudeAuthMethod`(`ENV_TOKEN`|`CREDENTIALS`|`CLI`, ≤20자), `claudeAuthExpiresAt`(ISO-8601). `claudeSessionOk`는 이제 판정값을 싣는다.
- **V37** `worker_heartbeat`에 `claude_auth_method VARCHAR(20)`, `claude_auth_expires_at TIMESTAMPTZ`(둘 다 NULL 허용 — 구버전 워커는 안 보낸다).
- `GET /api/workers/health` 행에 `claudeAuthMethod`, `claudeAuthExpiresAt`.
- 호환: 구버전 워커 → 새 API는 새 칸이 `null`(지금과 같음). 새 워커 → 구 API는 모르는 필드를 무시한다(Spring 기본). 순서 제약 없음.

## 5. 인터뷰 서비스

같은 `POST /worker/heartbeat`에 `workerId=interview-worker-1`(env `WORKER_ID`)로 20초마다 보고한다(`WORKER_HEARTBEAT_INTERVAL_MS`). 그래서 관리 화면에 인터뷰 서비스 행이 생기고 살아 있는지도 함께 보인다.

- `mcps`는 `[]`, `lostReportCount` 0, `version`은 `interview-service/<package.json version>`. 작업 등록 창의 MCP 합집합(`/api/workers/mcps/available`)에 인터뷰 쪽 MCP가 섞이지 않게 비운다.
- 이 행은 작업을 소유하지 않으므로 `StaleTaskRecoveryJob`(작업 소유 워커 id로만 조회)에 영향이 없다. 인터뷰 세션의 생사 판정은 지금처럼 세션 heartbeat(`/worker/interviews/{id}/heartbeat`)다.
- 보고 실패는 경고만(연속 실패는 첫 번째만) 남기고 턴에 영향을 주지 않는다.

## 6. 관리 화면 (`/admin/workers`)

"Claude OAuth" 칩 상태(순수 함수 `composables/claudeAuth.ts`):

| 조건 | 칩 | 비고 |
|---|---|---|
| `claudeSessionOk === null` | `—` | 구버전·판정 전·판정 실패 |
| `false`, 만료 시각이 지남 | 빨강 `만료` | |
| `false`, 그 밖 | 빨강 `NG` | 로그아웃·토큰 없음 |
| `true`, 만료까지 3일 이하 | 주황 `N일 남음`(하루 미만이면 `N시간 남음`) | |
| `true` | 초록 `OK` | |

칩 아래 작은 글씨로 방식(`CLI 로그인`·`setup-token`·`CLI 확인`)과 만료 시각(있으면). 빨강·주황인 행이 하나라도 있으면 페이지 맨 위에 경고 배너: 워커별 상태와 조치(`CLI 로그인`이면 그 PC에서 `claude` 다시 로그인 또는 setup-token으로 전환, `setup-token`이면 `worker.env` 토큰 교체).

## 7. 후속(이번 범위 밖)

- 이 PC 스택을 setup-token(`public.env`의 `CLAUDE_CODE_OAUTH_TOKEN`)으로 바꾸면 30일 주기 만료가 사라진다 — 원격 워커와 같은 방식. 운영 결정이라 사용자 몫.
- setup-token 만료 예측: 토큰을 만든 날을 env(예: `CLAUDE_CODE_OAUTH_TOKEN_ISSUED_AT`)로 받아 +1년을 만료 시각으로 보고할 수 있다.
- 관리 화면 밖 알림: 관리자 상단 배지·메일(`MAIL_*`)로 NG·임박을 알리기.

## 8. 배포 순서

API(V37) → 워커(이 PC·원격 두 대 jar — 진행 중 구현이 없을 때) → 인터뷰 서비스 → 프론트(`.output` 재빌드). 어느 순서로 섞여도 깨지지 않는다(§4 호환) — 칩은 새 워커가 보고하기 시작한 행부터 채워진다.
