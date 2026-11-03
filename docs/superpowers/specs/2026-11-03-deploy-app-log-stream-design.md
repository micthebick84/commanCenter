# 배포된 앱 로그 실시간 보기 설계

- 날짜: 2026-11-03
- 상태: 초안 — 사용자 검토 대기 (brainstorming: 동기 확인 → 제안 1건 확인 → 검토자 3관점 확인 → 질의 1건(보여 줄 상태 = 컨테이너가 살아 있는 동안), 아래 §3)
- 범위: API + 프론트(Nitro 서버 라우트 포함). 워커·인터뷰 서비스·DB 스키마는 바꾸지 않는다(마이그레이션 없음).
- 선행: PR #122(수정 요청). 보여 줄 상태가 #122의 수정승인대기·`active_fix_request_id`에 기대기 때문이다. 작업 1~3(작업 타입·간단 인터뷰·인터뷰 목업)과 겹치는 파일은 작업 상세 화면(`tasks/[id].vue`·`TaskDetailMobile.vue`)뿐이다. 그래서 #122 위의 독립 브랜치로 진행할 수 있고, 병합 순서는 그때 정한다. (Claude 판단)
- 표기: "(Claude 판단)"은 사용자가 정하지 않았고 설계자가 정한 것이다.

## 1. 목표

배포가 끝난 화면에서 구현된 기능이 동작하지 않았는데, 원인을 알 수 없었다. 빠진 DB 컬럼이나 다른 테이블 구조가 의심됐지만 확인할 길이 없었다(사용자 동기).

그래서 **배포된 앱(컨테이너)의 로그를 작업 상세에서 실시간으로** 보게 한다. 열면 최근 로그가 먼저 나오고, 새로 찍히는 줄이 계속 붙는다.

## 2. 배경 — 현재 동작 (PR #122 head `c87f82d` 기준)

- **배포 중 빌드 로그는 이미 실시간이다.**
  - 워커가 `POST /worker/tasks/{id}/deploy-log`로 보내면, API가 `task_deploy_log_chunk`(V10)에 저장하고 SSE로 보낸다(`DeployLogStreamService.java:42-55`).
  - 구독은 `GET /api/tasks/{id}/logs/stream?access_token=…`이다(등록자·관리자, `TaskController.java:273-279`).
  - 연결은 부모 `[id].vue`의 status watch가 화면 폭과 상관없이 배포중·배포중지중에서 `EventSource`로 연다(`[id].vue:429-462`). 데스크톱은 그 로그를 그린다(`:1398-1402`). 모바일(`TaskDetailMobile.vue`)에는 그리는 곳이 없다.
  - 핑과 재연결이 없다. 오류가 나면 그냥 닫는다(`onerror = () => closeLog()`).
- **운영 경로에서는 브라우저의 끊김이 API에 전달되지 않는다** (검토자 확인)
  - `app-win` 주소는 cloudflared → `localhost:3001`(Nitro)로 가고, `/api/**`는 Nitro routeRules 프록시를 거친다(`frontend/nuxt.config.ts:69-71`).
  - h3 `sendProxy`(라이브 1.15.11)는 upstream fetch에 abort signal을 걸지 않는다. 브라우저가 끊긴 뒤에도 upstream을 끝까지 읽고, 끊긴 응답에 써도 예외가 나지 않는다.
  - 그래서 API의 `SseEmitter`는 쓰기 실패를 보지 못한다. 로컬 dev는 devProxy가 끊김을 전달하므로 시험에서는 드러나지 않는다.
- **배포가 끝난 뒤 앱 로그를 보는 기능은 없다.**
  - 워커가 readiness 확인 때 `docker logs --tail 100`·`--tail 2000`을 한 번 돌릴 뿐이다(`LocalDockerTarget.java:514,528`).
  - 컨테이너 이름은 `netis-task-{id}`다(`DeployTarget.java:44`).
- **배포 데몬**
  - 배포는 이 PC 워커만 받는다(원격 워커 2대는 `WORKER_KINDS=IMPLEMENTATION,DESIGN`).
  - docker는 `public.env`의 `DOCKER_HOST=ssh://…@10.1.1.75`로 원격 데몬을 쓴다.
  - 기동 스크립트가 `public.env`를 모든 서비스에 물려주므로(`start-public.ps1:61` `Import-PublicEnv`) API 프로세스도 같은 `DOCKER_HOST`를 갖는다.
- **실측 (2026-11-03, 이 PC에서 API와 같은 env로)**
  - `docker`(`~\.local\bin\docker.exe`)와 Windows 내장 `ssh`로 원격 데몬에 닿는다(`docker ps` 정상).
  - `docker logs -f`가 6초 넘게 유지된다(follow 동작).
  - `docker.exe`를 끝내면 ssh 자식도 함께 사라진다(고아 없음).
- **배포 env의 비밀 값**: `EnvVar(key, value, secret)`(`TaskResponse.java:286`이 비관리자 응답에서 비밀 값을 비운다).

## 3. 결정 (사용자 확인, 2026-11-03)

| 결정 | 내용 |
|---|---|
| 어디서 | 작업 상세(데스크톱·모바일 모두)의 "앱 로그" |
| 보여 줄 상태 | **이전 배포 컨테이너가 살아 있는 동안 계속.** 배포완료뿐 아니라, 수정 요청을 넣은 뒤(수정승인대기·수정 회차의 구현 단계)와 종료 승인·진행 중에도 보인다. 재배포(배포대기·배포중)나 배포 중지가 시작되면 앱 로그를 닫고, 빌드 로그가 보인다(§4 허용 상태) |
| 어떻게 | 열면 컨테이너의 최근 로그 약 500줄을 먼저 보여 주고, 그 뒤로 새 줄이 실시간으로 붙는다 |
| 오류 찾기 | `ERROR`·`Exception` 같은 줄을 눈에 띄게 표시한다. "컬럼이 없음" 같은 SQL 오류가 바로 보이도록 |
| 보기 편의 | 맨 아래 따라가기 켜기/끄기, 멈춤, 보고 있는 내용 복사·내려받기 |
| 볼 수 있는 사람 | 요청자와 관리자. 빌드 로그와 같다 |
| 비밀 값 | 배포 env의 비밀 값이 로그에 찍히면 가려서 보여 준다 |
| 보관 | 하지 않는다. 컨테이너가 살아 있는 동안만 볼 수 있다. 배포를 중지하면 함께 사라진다 |
| 빌드 로그 | 배포 중 빌드 로그를 모바일에도 보이게 한다 |

## 4. API

- **방식** (Claude 판단)
  - API가 docker CLI를 직접 실행한다: `docker logs --tail 500 --timestamps --follow netis-task-{id}`.
  - 워커를 거치지 않는 이유: 배포를 받는 워커가 이 PC 한 대뿐이고, API가 같은 `DOCKER_HOST`를 이미 갖고 있기 때문이다(§2 실측).
  - docker를 찾지 못하면 기능만 꺼진다. 기동 로그는 `앱 로그: 켜짐(docker=…)` / `꺼짐(docker 없음)`이다(soffice 선례).
- **자식 프로세스 env**
  - `StackSecretEnv`로 스택 비밀을 뺀다. `DOCKER_HOST`는 남긴다.
  - stdout·stderr를 둘 다 읽는다. docker는 컨테이너 stderr를 stderr로 내보낸다. 줄마다 `stream: out|err`를 붙인다.
- **구독**: API `GET /api/tasks/{id}/app-logs/stream?access_token=…` (SSE)
  - 권한은 등록자·관리자이고, 빌드 로그 스트림과 같은 인증 방식이다.
  - **허용 상태** (사용자 결정 "살아 있는 동안 계속"을 코드 기준으로 옮긴 것)
    - 대상: GC 보호 목록(`TaskRepository.findDeployReconcilable`, `:239-265` — 이전 배포 컨테이너를 일부러 살려 두는 상태)에서 컨테이너가 바뀌거나 내려가는 진행 상태(배포대기·배포중·배포중지대기·배포중지중)를 뺀 것이다.
    - 구체적으로 `{배포완료, 배포중단됨, 수정승인대기, 종료승인대기, 종료대기, 종료중, 종료실패}`이거나 `active_fix_request_id`가 있는 작업이다(앞의 진행 상태 4개는 제외).
    - 작업에 배포 기록(배포 주소 또는 배포 시각)이 있어야 한다.
    - 이 판정은 한 곳(예: `AppLogPolicy.isViewable(Task)`)에 두고, 프론트는 같은 규칙의 composable(`appLogViewable`)로 카드 표시를 가른다. 두 곳의 상태 목록은 시험 하나로 대조한다.
    - 종료중에 배포 중지 단계가 오면 docker follow가 끝나 `end(container-stopped)`가 된다.
  - HTTP 상태 코드는 인증·권한(401/403/404)에만 쓴다. 나머지 거절과 종료는 200 응답 안의 `end` 이벤트로 보내고 닫는다. 브라우저 `EventSource`는 상태 코드와 본문을 읽지 못하기 때문이다. (Claude 판단)
  - **`end{reason, message}`의 reason**
    - `not-allowed-status`: 허용 상태가 아님
    - `too-many`: 동시에 볼 수 있는 앱 로그 수를 넘었습니다
    - `disabled`: docker 없음 또는 설정 꺼짐
    - `no-container`: 컨테이너가 없어 로그를 볼 수 없습니다
    - `docker-error`
    - `time-limit`
    - `container-stopped`
- **끊김 전달 — 이 스트림만 Nitro 서버 라우트로 받는다** (Claude 판단)
  - 화면은 `/api/**` 밖의 경로(예: `/stream/tasks/{id}/app-logs`)로 연결한다. Nitro 서버 라우트가 API로 fetch하되 `AbortController`를 걸고, 브라우저 요청이 닫히면(`req` 'close') upstream을 끊는다.
  - `/api/**` 아래에 두면 routeRules 프록시가 서버 라우트보다 먼저 돌아 가려진다(nitropack `app.mjs`). 그래서 경로를 따로 둔다.
  - cloudflared 설정은 바꾸지 않는다.
  - 30분 상한과 동시 상한은 보조 안전장치로 남긴다.
  - **완료 조건(라이브 확인)**: Cloudflare를 거쳐 카드를 접은 뒤 약 20초 안에 API PC와 10.1.1.75에서 그 docker·ssh가 사라진다.
- **보는 사람마다 프로세스 하나** (Claude 판단)
  - 연결 하나에 docker 프로세스 하나를 둔다. 공유·버퍼링보다 단순하고, 보는 사람은 많아야 두세 명이다.
  - 상한(설정값)
    - `app.app-log.max-streams` 전체 6, `max-streams-per-task` 3. 넘으면 429 `동시에 볼 수 있는 앱 로그 수를 넘었습니다`.
    - `max-duration-minutes` 30. 넘으면 `end` 이벤트(`reason: "time-limit"`)를 보내고 닫는다. 화면이 자동으로 다시 연결한다.
- **끝내기**
  - 연결이 끊기거나(클라이언트 닫힘·오류·타임아웃), 시간 상한에 걸리거나, API가 종료되면 프로세스 트리를 끝낸다(`ProcessHandle.descendants()` 포함).
  - 실측으로는 docker를 끝내면 ssh도 끝났다. 그래도 트리 전체를 끝낸다.
  - 컨테이너가 멈추거나 지워지면 docker follow가 스스로 끝난다. 그때 `end`(`reason: "container-stopped"`)를 보낸다.
- **SSE 이벤트**
  - `lines`: `{lines: [{ts, stream, text}]}`. 300ms마다 또는 200줄마다 묶어 보낸다. 로그가 쏟아질 때 이벤트 수를 줄이기 위해서다.
  - `info`: `{message}`.
  - `end`: `{reason}`.
  - 20초마다 SSE 주석 핑을 보낸다(Cloudflare 유휴 끊김 방지).
- **줄 처리**
  - 한 줄은 4000자까지만 보내고, 넘으면 `…(잘림)`을 붙인다.
  - 비밀 값 가리기 (Claude 판단, 아래 순서로 적용)
    1. 그 작업의 `envVars` 중 `secret=true`이고 4자 이상인 값을 `••••`로 바꾼다. 짧은 값은 흔한 문자열과 겹쳐 오탐이 많다.
    2. API 프로세스 자신의 `StackSecretEnv.NAMES` 값 중 비어 있지 않은 것도 가린다. 배포 앱이 스택과 같은 DB 비밀번호를 비밀 표시 없이 받았을 때를 막는다.
    3. URL 안의 자격증명(`GitRemotes.mask`, `://user:secret@` 형태)을 가린다.
    4. 단독 토큰(`ghp_`·`github_pat_`·`glpat-`·`sk-ant-` 등)을 가린다. 이 패턴은 지금 워커 `CloseNoteWriter.BARE_TOKENS`에만 있으므로 API·워커 공용 유틸로 옮기고, `CloseNoteWriter`도 그것을 쓰게 한다.
- **빌드 로그 스트림 보강**: 기존 `DeployLogStreamService`의 SSE에도 같은 20초 핑을 넣는다. (Claude 판단)
- **설정**: `app.app-log.*`. `enabled` 기본 true, `tail-lines` 500, 위 상한들.

## 5. 화면 (프론트)

- **"앱 로그" 카드**
  - 데스크톱 `[id].vue`에서는 배포 주소 근처, 모바일 `TaskDetailMobile.vue`에서는 아코디언 한 칸이다.
  - 허용 상태(§4)에서만 보인다. 폴링(5초)으로 상태가 허용 밖으로 바뀌면 스트림을 닫고 카드를 숨긴다. 재배포가 시작되면 빌드 로그가 그 자리를 이어받는다.
  - **펼칠 때만 연결**하고, 접거나 페이지를 떠나면 닫는다. 모바일 아코디언은 `v-if` 게이트를 쓴다(CLAUDE.md의 q-expansion-item 주의).
- **표시**
  - 고정폭 글꼴, `[시각] 본문` 형식이다. stderr 줄은 왼쪽에 작은 표식을 단다.
  - 강조 규칙
    - 빨강: `\b(ERROR|FATAL|SEVERE)\b`, `Exception`, `Caused by:`, `SQLException`, `ORA-\d+`, `SQLSTATE`, MySQL `ERROR \d+`, PostgreSQL `ERROR:`
    - 주황: `\bWARN(ING)?\b`
  - "오류 줄만" 걸러 보기 토글을 둔다. (Claude 판단)
  - 브라우저에는 최근 5000줄까지만 두고, 넘으면 오래된 줄부터 버린다.
- **도구 줄**
  - 연결 상태 점(연결됨·다시 연결 중·끝남 + 사유)
  - 맨 아래 따라가기(켬/끔). 사용자가 위로 스크롤하면 자동으로 끈다.
  - 멈춤/재개. 멈춘 동안 들어온 줄은 모아 두었다가 재개할 때 붙인다.
  - 화면 지우기
  - 복사(지금 보이는 줄)
  - 내려받기(`task-{id}-app-{시각}.log`, 보이는 줄)
- **재연결** (Claude 판단)
  - `end(time-limit)`: 탭이 보일 때(`document.visibilityState === 'visible'`)만 다시 연결한다. 탭이 숨겨진 채 끝났으면 닫아 두었다가, 다시 보이면(`visibilitychange`) 연결한다. 잊고 열어 둔 탭이 docker·ssh를 끝없이 붙잡지 않게 하려는 것이다.
  - `end(container-stopped)`: 작업이 아직 허용 상태면 몇 초 뒤 한두 번만 다시 연결한다. 컨테이너는 `--restart unless-stopped`라(`LocalDockerTarget.java:214`), 앱이 기동하다 죽기를 반복할 수 있다. docker logs는 재시작 전 로그도 주므로 tail에 기동 오류가 남는다.
  - 그 밖의 `end`: 멈추고 사유와 "다시 연결" 버튼을 보여 준다.
  - 이벤트 없이 끊긴 `onerror`: 지수 백오프로 몇 번까지만 재시도한 뒤 멈춘다.
  - 다시 연결하면 서버가 tail 500부터 다시 보낸다. 화면은 마지막으로 받은 `ts` 이하의 줄을 버려 중복을 줄인다.
- **모바일 빌드 로그**: 연결 구조는 그대로 둔다. 부모 `[id].vue`가 이미 연 빌드 로그(`liveLog`)를 prop으로 `TaskDetailMobile.vue`에 넘기고, 아코디언(`v-if` 게이트)으로 그린다. 표시 상태는 데스크톱과 같은 배포중·배포중지중이다. (Claude 판단)
- **스타일 규칙**: Quasar 반응형 이름을 클래스로 쓰지 않는다. 다이얼로그는 쓰지 않는다(카드 안 스크롤 영역).
- **하이드레이션 스모크**: 새 API가 SSE라 목이 필요 없을 수 있다. 빠진 API 목록에 뜨면 넣는다.

## 6. 배포 순서

1. **API**: `docker`가 PATH에 있고 `DOCKER_HOST`가 있는지 기동 로그로 확인한다.
2. **프론트**: `.output` 재빌드.

워커·인터뷰 서비스는 바꾸지 않는다. 마이그레이션도 없다.

## 7. 빌더에게 맡기는 것

- SSE 구현 세부(`SseEmitter` 타임아웃·완료 처리). `SseEmitter(0L)`이 남는 기존 함정을 피한다.
- 줄 묶음 크기와 주기의 정확한 값.
- 강조 정규식의 최종 목록. §5를 기본으로 한다.
- 데스크톱 카드 위치와 모양. 기존 빌드 로그 영역의 모양을 따른다.

## 8. 비목표 · 반목표

- **비목표**
  - 로그 저장·검색 이력.
  - 배포 중지 뒤 로그.
  - 배포실패 작업의 앱 로그. 배포 로그에 이미 `--tail` 결과가 있다.
  - 여러 컨테이너.
  - 로그로 원인을 찾아 주는 Claude 분석.
  - 원격 워커가 배포하는 구성 지원.
- **반목표**(기술적으로 동작해도 실패인 것)
  - 배포 env의 비밀 값이 그대로 화면에 찍히는 것.
  - 화면을 닫았는데 docker·ssh 프로세스가 API PC나 배포 데몬 쪽에 남는 것.
  - 요청자·관리자가 아닌 사용자가 남의 작업 로그를 보는 것.

## 9. 위험

- **비밀 가리기의 한계**: env로 주지 않은 비밀(레포 설정 파일의 비밀번호, 앱이 찍는 접속 URL)은 가려지지 않는다. 빌드 로그와 같은 수준의 노출이고, 볼 수 있는 사람도 같다.
- **배포 데몬 부하**: 스트림 하나가 ssh 연결 하나다. 상한 6·작업당 3·30분으로 묶는다.
- **구성 가정**: API와 배포 워커가 같은 PC·같은 `public.env`를 쓴다는 가정에 기댄다. 배포 워커를 다른 PC로 옮기면 이 기능은 그 데몬에 닿지 못한다. 그때는 "컨테이너가 없어…"로 보이거나 docker 오류가 `info`로 나온다.
