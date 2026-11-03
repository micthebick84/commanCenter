# Claude 사용량·API 환산 비용 — 일·주·월 보기 (구독료 대비) 설계

- 날짜: 2026-11-03
- 상태: 초안 — 사용자 검토 대기 (brainstorming: 동기 확인 → Claude Code 직접 사용분 포함 여부 → 제안 1건 확인 → 검토자 3관점 확인 → 질의 3건(원격은 Symphony만, 진행 중 기간은 경과 기준, 10-01부터) — 아래 §3)
- 범위: 워커 데몬(수집) + API(저장·집계·설정) + 프론트(작업 목록 요약·상세). 인터뷰 서비스는 바꾸지 않는다.
- 선행 없음. 작업 목록 머리줄(`tasks/index.vue`)을 작업 타입 기능(타입 필터)과 함께 고친다. 그래서 별도 브랜치로 두고 병합 순서는 그때 정한다. 마이그레이션 번호는 병합 시점의 최신 다음 번호다(예정 V42). (Claude 판단)
- 표기: "(Claude 판단)"은 사용자가 정하지 않았고 설계자가 정한 것이다.

## 1. 목표

사용자는 구독료 대비로 자신이 쓰는 토큰량과 그 토큰을 API 가격으로 환산한 비용을 보여 주려 한다. 이를 근거로 회사에 기여하는 생산성을 입증하는 것이 목적이다(사용자 동기).

- 예: "월 구독료는 얼마인데, 이번 달 사용량을 API로 환산하면 그 몇 배"를 **일·주·월** 단위로 보여 준다.
- 사용량에는 Symphony가 돌린 작업·인터뷰·질문뿐 아니라, **같은 구독으로 Claude Code를 직접 쓴 사용량**도 포함한다.

## 2. 배경 — 현재 상태

- **Symphony 사용량은 누적만 있다.**
  - `com.task_stage_usage`(V21, 작업·단계별 합계 upsert)와 `interview_session.total_cost_usd`·토큰 4종이 있다.
  - 언제 쓴 것인지 시점 기록이 없어 일·주·월로 나눌 수 없다.
  - 비용은 CLI가 주는 API 환산값(SHADOW)이다.
  - Claude Code를 직접 쓴 사용량은 아예 없다.
- **사이드바 "구독 사용량" 패널**(`ClaudeUsagePanel.vue`): SDK `rate_limit_event`로 받은 5시간·주간 사용률(%)만 보여 준다.
- **Claude Code 사용 기록 파일 (2026-11-03 이 PC에서 확인)**
  - 위치: `{CLAUDE_CONFIG_DIR 또는 ~/.claude}/projects/<cwd를 바꾼 폴더 이름>/<세션 id>.jsonl`. 서브에이전트 기록은 `<세션 id>/subagents/*.jsonl`에 있다.
  - `type: "assistant"` 줄마다 다음 칸이 있다.
    - `message.id`, `requestId`, `message.model`(예: `claude-opus-5-5`), `timestamp`, `cwd`, `sessionId`, `isSidechain`
    - `entrypoint`: `cli` · `claude-desktop` · `sdk-cli` · `sdk-ts`
    - `message.usage`: `input_tokens`, `output_tokens`, `cache_creation_input_tokens`(+ `cache_creation.ephemeral_5m_input_tokens`·`ephemeral_1h_input_tokens`), `cache_read_input_tokens`, `service_tier`, `speed`
  - Symphony가 돌린 세션도 같은 곳에 남는다. 이 PC에서는 `~/netis-maker/interviews/...`·`~/netis-maker/close-task-...` 등 경로의 세션이 `entrypoint` `sdk-cli`·`sdk-ts`로 보였다.
  - 사용자가 데스크톱 앱으로 쓴 세션은 `claude-desktop`이다.
  - 기록은 Claude Code 기본 설정(`cleanupPeriodDays` 30)에 따라 약 30일 보관된다.
- **Claude를 돌리는 PC는 세 대다.**
  - 이 PC: API, 워커 N, 인터뷰 서비스, 사용자 데스크톱 앱
  - 10.1.1.75(Windows) 원격 워커
  - Mac 원격 워커
  - 세 대 모두 Symphony 워커가 돈다.

## 3. 결정 (사용자 확인, 2026-11-03)

| 결정 | 내용 |
|---|---|
| 포함 범위 | Symphony 사용량과 Claude Code 직접 사용량을 함께 센다. claude.ai 웹·앱 채팅은 기록이 없어 포함하지 않는다 |
| 수집 | Claude를 돌리는 PC 세 대에서 **워커가 기록 파일을 주기적으로 읽어** Symphony로 보낸다 |
| 보내는 것 | 모델·토큰 수·시각(과 구분용 경로 정보)뿐이다. 대화 내용은 보내지 않는다 |
| 중복 | 같은 응답을 두 번 세지 않게 응답마다 붙은 고유 번호로 막는다 |
| 구분 | 어느 폴더에서 돌았는지로 "Symphony 작업"과 "직접 사용"을 나눈다 |
| 환산 | 모델별 API 가격표로 계산한다. 캐시 토큰도 각각의 가격으로 센다 |
| 구독료 | 관리 화면에서 월 구독료를 입력한다. 기간에 맞춰 나눠 "구독료 대비 몇 배"를 낸다 |
| 표시 | 작업 목록 오른쪽 위에 일·주·월을 바꿀 수 있는 요약을 둔다. 토큰 합계, API 환산 비용, 구독료 대비 배수, Symphony와 직접 사용의 비율을 보여 준다 |
| 원격 PC | 10.1.1.75와 Mac에서는 **Symphony 작업만** 센다. 직접 사용분은 이 PC에서만 센다. 기록에 계정 칸이 없어, 원격 PC의 다른 사람·다른 계정 사용이 섞이는 것을 막기 위해서다 |
| 진행 중인 기간 | 이번 달·이번 주·오늘처럼 아직 끝나지 않은 기간의 배수는 **지금까지 지난 만큼의 구독료**로 나눈 값(속도)이다. 직전 기간과의 비교도 같은 경과 구간끼리 한다 |
| 세는 시작일 | **2026-10-01부터** 센다. 그 전에는 워커·인터뷰 서비스의 Claude가 다른 구독(개인 Max)으로 로그인돼 있었다 |
| 권한 | 관리자만 볼 수 있다 |
| 과거분 | 처음 켤 때 남아 있는 기록, 즉 최근 약 30일치를 채운다 |

## 4. 수집 (워커 데몬)

- **수집 작업**
  - 워커 프로필에 예약 작업 `ClaudeCodeUsageCollector`를 둔다. 기본 5분마다 돌고, 주기는 `netis-maker.worker.usage-collect.interval-seconds`, 끄기는 `enabled`로 정한다.
  - 같은 PC의 워커 여러 개 중 하나만 수집한다. 락 파일 `~/netis-maker/claude-usage-collector.lock`은 비차단 FileLock이며, 레포 캐시 정리 잡의 방식과 같다.
- **읽는 곳**: `CLAUDE_CONFIG_DIR`(있으면) 또는 `~/.claude`의 `projects/**/*.jsonl`. `subagents/` 아래도 포함한다.
- **이어 읽기**
  - 파일마다 (경로 → 마지막으로 읽은 바이트 위치·크기·수정 시각)를 로컬 상태 파일(`~/netis-maker/claude-usage-collector.state.json`)에 둔다. 다음 회차에는 덧붙은 줄만 읽는다.
  - 파일이 줄었거나 바뀌었으면 처음부터 다시 읽는다. 중복은 API가 막는다.
  - 마지막 줄이 아직 덜 쓰였으면(개행 없음) 다음 회차로 미룬다.
  - Claude가 쓰고 있는 파일은 공유 읽기로 연다.
- **뽑는 칸**
  - `type == "assistant"`이고 `message.usage`가 있는 줄만 본다.
  - `event_key` = `message.id` + `":"` + `requestId`(없으면 `message.id`만). `message.id`도 없는 줄은 건너뛴다.
  - **같은 응답이 여러 줄로 남는다** (검토자 실측, 이 PC 기록 974개 파일)
    - 응답이 스트리밍되며 블록마다 한 줄씩 남는다. 앞줄의 `output_tokens`는 중간값이고(`stop_reason` null), 마지막 줄이 최종값이다. input·cache 칸은 같다.
    - 첫 줄만 세면 output 토큰이 약 30% 빠진다.
    - 그래서 수집기는 묶음 안에서 키마다 **칸별 최댓값**을 보낸다. 같은 응답의 앞줄과 뒷줄이 다른 회차로 나뉘는 경우는 API가 합친다(§5).
    - 메인 파일과 subagents 파일 사이에 겹치는 키는 없었다. 세션 폴더가 옮겨진 사본(같은 키·같은 값)은 키로 걸러진다. (Claude 판단)
  - `occurred_at` = `timestamp`, `model`, `speed`, `service_tier`, `entrypoint`, `session_id`, `is_sidechain`
  - 토큰: `input`, `output`, `cache_write_5m`, `cache_write_1h`, `cache_read`
    - 5분/1시간 분리가 없으면 `cache_creation_input_tokens` 전체를 5분으로 센다.
  - **내용(`message.content`)·도구 입력은 읽어도 보내지 않는다.** 경로(`cwd`)는 보내지 않고 구분 결과만 보낸다.
- **구분** (Claude 판단)
  - `cwd`가 그 PC의 Symphony 작업 폴더 아래면 `SYMPHONY`, 아니면 `DIRECT`다.
  - Symphony 작업 폴더: 워커의 worktree 루트, `~/netis-maker/`, 그리고 설정 `netis-maker.worker.usage-collect.symphony-roots`(원격 워커의 `C:\netis-worker`·`~/netis-remote-worker` 등).
  - 비교는 대소문자를 무시하고 경로를 정규화해서 한다(Windows).
  - **직접 사용분 포함 여부**: 설정 `netis-maker.worker.usage-collect.include-direct`. 이 PC는 true, 원격 두 PC는 false다(기본 false, 이 PC의 `public.env`에서 켬). false면 `DIRECT` 줄은 보내지 않는다(사용자 결정).
  - **세는 시작일**: 설정 `netis-maker.worker.usage-collect.count-from`(기본 `2026-10-01`, Asia/Seoul 자정). 그보다 이른 `timestamp`의 줄은 보내지 않는다. API 집계도 같은 날짜(`app.usage.count-from`)를 하한으로 쓴다(사용자 결정).
- **보내기**
  - `POST /worker/usage/claude-code-events`(`X-Worker-API-Key`)로 한 번에 1000줄까지 묶어 보낸다. `machine`은 설정값이고 기본은 호스트 이름이다.
  - 새 줄이 없는 회차에도 빈 묶음과 회차 요약(못 읽은 줄 수)을 보낸다. 그래야 "수집이 멈춤"과 "쓴 것이 없음"이 구별된다(§5 수집기 상태). (Claude 판단)
  - 성공한 만큼만 위치를 앞으로 옮긴다. 실패하면(구 API 404 포함) 경고 한 줄을 남기고 다음 회차에 다시 보낸다.
- **과거분**: 상태 파일이 없는 첫 회차는 모든 파일을 처음부터 읽는다. 그래서 남아 있는 기록 가운데 세는 시작일(2026-10-01) 이후분이 자연히 들어간다.

## 5. 저장 (API)

- **`com.claude_usage_event`**
  - 칸: `id`, `event_key` VARCHAR(200) UNIQUE, `occurred_at` timestamptz, `machine`, `category`(`SYMPHONY`|`DIRECT` CHECK), `model`, `speed`, `service_tier`, `entrypoint`, `session_id`, `is_sidechain`, 토큰 5칸(bigint), `created_at`
  - 인덱스 `(occurred_at)`.
  - 넣을 때 `ON CONFLICT (event_key) DO UPDATE`로 토큰 5칸을 칸마다 큰 값(`GREATEST`)으로 바꾼다. 다른 칸은 처음 값을 둔다. 응답은 `{received, inserted, updated}`다. (Claude 판단 — §4의 여러 줄 문제)
- **`com.claude_usage_collector`**
  - 칸: `machine` PK, `last_reported_at`, `last_event_at`, `unreadable_lines`(마지막 회차), `updated_at`
  - 수신할 때마다 upsert한다. 화면의 "PC별 마지막 보고 시각"은 이 표에서 낸다. (Claude 판단)
- **`com.usage_subscription_fee`**
  - 칸: `id`, `effective_from` date, `monthly_fee` numeric(12,2), `currency`('USD'), `created_by`, `created_at`
  - 구독료가 바뀐 이력을 남겨, 지난 달은 그때의 구독료로 계산한다. 통화는 USD 하나로 시작한다. (Claude 판단)
- **가격표**
  - 설정 `app.usage.prices.<모델 id>.{input,output,cache-write-5m,cache-write-1h,cache-read}`(USD / 100만 토큰)이고, fast 모드 배수는 `fast-multiplier`다.
  - 초기값은 **구현 시점의 Anthropic 공식 가격**을 확인해 넣는다. 빌더가 추측으로 채우지 않는다.
  - **모델 이름 맞추기** (Claude 판단)
    - 정확히 일치하는 키가 없으면 끝의 날짜 접미사(`-\d{8}`, 예: `claude-haiku-4-5-20251001`)를 떼고 다시 찾는다.
    - 공식 가격에 프롬프트 길이 구간 단가가 있으면 선택 칸(`long-context.threshold`·단가)으로 둔다. 이때 비용은 이벤트 단위로 계산한 뒤 더한다.
  - 가격표에 없는 모델은 비용을 비워 두고 "가격 미등록 모델" 경고에 이름과 토큰을 보여 준다. 토큰 합이 0인 모델과 `<synthetic>`은 경고에서 뺀다.
  - 가격이 바뀌면 설정을 고치고 API를 재기동한다. 과거분도 새 가격으로 다시 계산된다(이벤트에는 토큰만 저장). (Claude 판단)

## 6. 집계 API (관리자)

- **`GET /api/usage/summary?period=day|week|month&date=YYYY-MM-DD`**(`date`가 없으면 오늘)
  - 기간 경계: Asia/Seoul. 주는 월요일에 시작한다(ISO). (Claude 판단)
  - 응답
    - 기간 시작·끝
    - 토큰 5종 합계, API 환산 비용
    - `byCategory`(SYMPHONY·DIRECT별 토큰·비용)
    - `byModel`
    - 구독료 몫: 모든 기간에서 날짜별로 "그날 적용되는 월 구독료 ÷ 그 달 일수"를 더한다. 달 중간에 구독료가 바뀌거나 기간이 두 달에 걸쳐도 같은 규칙이다.
      - 구독료가 없는 날이 섞인 기간은 `ratio`를 null로 두고 "구독료 적용 전 기간 포함"으로 표시한다.
      - 구독료 설정 폼의 적용 시작일 기본값은 가장 이른 이벤트가 있는 달의 1일이다. (Claude 판단)
    - `ratio` = 비용 ÷ 구독료 몫
    - **진행 중인 기간**(지금이 기간 안): 구독료 몫은 기간 시작부터 지금까지 지난 만큼만 센다. 날짜별 몫 × 그날 지난 비율로 계산하며, 오늘은 지난 시간 비율이다. 그래서 `ratio`는 "지금까지의 속도"다.
      - 응답에 `inProgress: true`를 싣는다.
      - 상세 화면용으로 `fullPeriodFeeShare`(기간 전체 몫)도 싣는다.
      - 직전 기간 비교는 직전 기간 시작부터 같은 경과 길이까지의 비용·배수로 한다(사용자 결정).
    - `series`: 일 → 시간별, 주·월 → 일별 비용
    - 직전 기간 비용·배수(비교용)
    - `coverage`: PC별 마지막 보고 시각·마지막 이벤트 시각(`claude_usage_collector`), 가장 이른 이벤트 시각, 가격 미등록 모델 목록
  - 구독료가 입력되지 않았으면 `ratio`는 null이고, 화면은 "구독료를 입력하세요"를 보여 준다.
- **`GET /api/usage/summary.csv?period=…&date=…`**: 일별(일 기간은 시간별) 행을 내려받는다. 칸은 날짜, 구분, 모델, 토큰 5종, 비용이다. 회사에 보여 줄 근거 자료용이다. (Claude 판단)
- **구독료**
  - `GET /api/usage/subscription-fees`: 이력.
  - `POST /api/usage/subscription-fees` `{effectiveFrom, monthlyFee}`.

## 7. 화면 (프론트)

- **작업 목록 요약**(데스크톱 `tasks/index.vue` 머리줄 오른쪽, 모바일 `TaskListMobile.vue` 위)
  - 관리자에게만 보인다. 인증 분기는 `useHydrationSafeAuth()`를 쓴다.
  - 칩 하나에 "이번 달(지금까지) $1,234 · 구독료의 6.2배"를 보여 준다. 일·주·월 세그먼트로 바꾸면 "오늘"·"이번 주"·"이번 달"이 된다. 진행 중인 기간에는 "(지금까지)"를 붙인다.
  - 숫자는 기간을 바꿀 때와 화면에 들어올 때 불러온다. 자동 갱신은 하지 않는다.
- **상세**(칩을 누르면 여는 다이얼로그; 폰에서는 전체 화면 — `useFullscreenDialog`)
  - 기간 이동(이전·다음), 일·주·월 전환
  - 큰 숫자: API 환산 비용, 구독료 몫, 배수, 직전 기간 대비
  - Symphony·직접 사용 비율 막대, 모델별 표(토큰 5종·비용)
  - 기간 막대 차트(일: 시간별, 주·월: 일별)
  - 수집 상태: PC별 마지막 보고 시각, 가장 이른 기록. 안내: "claude.ai 채팅 제외 · 기록은 Claude Code가 약 30일 보관한 것부터"
  - "CSV 내려받기"
  - "구독료 설정"(적용 시작일·월 구독료 USD, 이력 표)
- **차트**: 기존 프론트 의존성에 차트 라이브러리가 없으면 CSS 막대로 그린다. 새 의존성은 들이지 않는다. (Claude 판단)
- **하이드레이션 스모크**: 새 API 목(`/api/usage/summary`)을 넣는다.

## 8. 배포 순서

1. **API**: 새 마이그레이션, 수집 수신, 집계·구독료 API.
2. **워커 3대**(이 PC, 10.1.1.75, Mac — 진행 중 작업이 없을 때): 수집 작업.
   - 원격 워커는 `symphony-roots`를 그 PC 경로로 설정한다. `include-direct`는 기본값 false 그대로 둔다.
   - 이 PC만 `public.env`에 `USAGE_COLLECT_INCLUDE_DIRECT=true`를 넣는다(키 이름은 빌더가 관례대로).
   - 기동 뒤 첫 회차가 과거분을 채운다.
3. **프론트**: `.output` 재빌드.
4. **구독료 입력**: 관리자가 상세 화면에서 월 구독료를 입력한다.

구버전 워커는 아무것도 보내지 않는다. 화면의 수집 상태에 그 PC가 빠져 보인다.

## 9. 빌더에게 맡기는 것

- 상태 파일 형식, 회차당 읽기 상한(한 번에 너무 많이 읽지 않게).
- 경로 정규화 세부(Windows 드라이브 문자·구분자, Mac).
- 집계 SQL(`date_trunc` + `AT TIME ZONE 'Asia/Seoul'`)과 응답 모양.
- 칩·다이얼로그의 배치. 기존 작업 목록 머리줄의 높이와 간격을 따른다.

## 10. 비목표 · 반목표

- **비목표**
  - claude.ai 웹·앱 채팅 사용량.
  - 워커가 없는 PC(예: 다른 노트북)의 Claude Code 사용량. 나중에 단독 수집 스크립트로 넓힐 수 있다.
  - 사용자별 나누기(구독이 하나).
  - 실제 청구 금액과 맞추기.
  - 이벤트 보관 기간 정리나 롤업.
  - 가격표를 화면에서 고치기.
  - 기존 작업별 비용 배지(`task_stage_usage`) 변경.
- **반목표**(기술적으로 동작해도 실패인 것)
  - 같은 응답이 두 번 세져 비용이 부풀려지는 것. 같은 파일 재수집, 워커 여러 개, 서브에이전트 파일 모두 포함한다.
  - 대화 내용·도구 입력·경로가 Symphony로 보내지는 것.
  - 관리자가 아닌 사용자에게 비용이 보이는 것.

## 11. 위험

- **기록 형식 변경**: Claude Code 버전에 따라 칸 이름이 바뀔 수 있다. 수집기는 모르는 칸과 빠진 칸을 견디고, 못 읽은 줄 수를 회차 로그에 남긴다.
- **기록 보관 30일**: 수집이 30일 넘게 멈추면 그 사이 기록은 Claude Code가 지워 영영 빠진다. 화면의 PC별 마지막 보고 시각으로 알아챈다.
- **가격표 정확도**: 환산 비용은 설정한 가격표만큼만 맞다. 공식 가격이 바뀌면 설정을 고쳐야 한다.
- **구분의 한계**: 사용자가 Symphony 작업 폴더 안에서 직접 Claude Code를 열면 Symphony로 분류된다. 이 PC에서 5건 보였다. 합계에는 영향이 없다.
