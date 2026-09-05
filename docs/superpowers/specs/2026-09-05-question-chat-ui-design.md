# 질문 탭 채팅형 UI + Claude Code 구독 사용량 표시 설계

2026-09-05 · 디자인 캔버스 확정본 + 코드 대조 검증(백엔드/인터뷰 서비스/프론트 3면) 반영

디자인 캔버스: https://claude.ai/code/artifact/49954c08-95f6-46e5-880e-06e77b73691c
작업 파일: `/Users/micthebick/IdeaProjects/design_netismaker_question_chat/` (`Main.dc.html` 데스크톱 대화, `NewQuestion.dc.html` 새 질문+모델 메뉴, `Mobile.dc.html` 390px 답변 중)

## 1. 개요

질문 탭(`/questions`)을 "목록 페이지 + 질문하기 다이얼로그"에서 **일반적인 채팅창 레이아웃**으로 바꾼다.

- 탭을 누르면 바로 **좌측 세션 목록 + 우측 대화창**으로 들어간다. 새 질문은 대화창 자리에 입력창만 있는 빈 상태로 시작한다.
- **모델·추론 단계(effort)는 입력창 하단 툴바**에서 고른다. 모델 목록에서 **Fable을 제외**한다.
- 이 기능은 **claude CLI 구독 계정**(OAuth, API 키 없음)으로 돈다. 따라서 **Claude Code 사용량(현재 세션 5시간 · 이번 주)** 과 **대화별 컨텍스트 상태**를 UI에서 확인할 수 있어야 한다.
- 기존 기계장치(큐/claim, SSE, 턴 저장, 회복 잡, 비용가드, 질문 세션 default-deny 게이트)는 그대로 재사용한다. 이번 변경은 **표시 계층 + 사용량 수집 경로**다.

## 2. 확정된 정책 결정

| 결정 | 내용 |
|---|---|
| 진입 구조 | `/questions` = 셸(좌측 세션 목록 + `<NuxtPage>`). 자식 라우트 `/questions`(index) = 새 질문 작성, `/questions/{id}` = 대화. Nuxt 중첩 라우트(`pages/questions.vue` + `pages/questions/*.vue`) |
| 모델 목록 | **Fable 제외.** `claude-opus-5`(기본) · `claude-sonnet-5` · `claude-haiku-4-5`. 백엔드 `ModelEffortPolicy.ALLOWED`(권위)와 프론트 `modelEffort.ts MODEL_OPTIONS`(미러)를 **동시에** 수정. Flyway DEFAULT/엔티티 초기자는 opus-5라 변경 없음. 과거 세션에 박제된 `claude-fable-5`는 검증을 타지 않으므로 그대로 실행된다(마이그레이션 없음) |
| 모델/effort 변경 시점 | **세션 생성 시에만.** 대화 중에는 툴바 픽커를 세션 값으로 **읽기 전용** 표시(툴팁 "세션 생성 시 고정 — 바꾸려면 새 질문"). 대화 중 변경은 범위 밖(§9) — 백엔드 `InterviewSession.model/effort`가 세션 단위이고 SDK resume 세션의 모델 교체 동작이 미검증 |
| 제목 | 입력 제거. **서버가 질문 첫 줄에서 자동 생성**(공백 정규화, 60자 초과 시 절단+`…`). `QuestionCreateRequest.title`은 optional로 완화(보내면 그대로 사용 — 구 클라이언트/테스트 호환) |
| 사용량 데이터 소스 | **SDK `rate_limit_event`** (`@anthropic-ai/claude-agent-sdk@0.2.117` sdk.d.ts:2910 `SDKRateLimitEvent`, :2923 `SDKRateLimitInfo{status, resetsAt?, rateLimitType?, utilization?, isUsingOverage?}`)를 인터뷰 서비스 relay가 수신 → `POST /worker/usage/rate-limits` → `com.claude_rate_limit`(limit_type당 1행 upsert) → `GET /api/usage/claude`. **외부 usage 엔드포인트·Keychain 접근·응답 헤더 파싱은 하지 않는다**(비공식 API 의존 회피) |
| 사용량 신선도 | 이벤트는 SDK 턴이 돌 때만 도착한다. UI는 `updated_at` 기준 **"N분 전 갱신"** 을 항상 표기하고, `resets_at`이 지났으면 **0% + "초기화됨 · 다음 사용 시 갱신"** 으로 표시한다. 유휴 프로브(주기적 더미 쿼리)는 범위 밖(§9) |
| 컨텍스트 상태 | **대화별** 값. relay가 마지막 최상위 assistant 메시지 `message.usage`(input+cache_creation+cache_read)를 컨텍스트 토큰으로, `result.modelUsage[*].contextWindow`(입력 토큰 합이 최대인 모델)를 창 크기로 잡아 `/worker/interviews/{id}/question`에 `contextTokens`/`contextWindow`로 보고 → `interview_session.context_tokens/context_window`(null = 미보고) → `InterviewResponse`/`QuestionSummaryResponse`. 표시 = 대화 헤더 링 칩 "컨텍스트 N%"(모바일은 입력창 아래 스트립) |
| 답변 렌더링 | **markdown-it** (`html:false`, `breaks:true`, `linkify:false`). 원시 HTML을 렌더하지 않으므로 별도 sanitizer 없이 XSS 차단, `javascript:` 링크는 markdown-it 기본 `validateLink`가 거른다. 링크는 `target=_blank rel="noopener noreferrer"`. 사용자 말풍선·시스템 노트·진행 미리보기(PendingBubble)는 기존 그대로 평문 |
| 임계 색상 | 세 지표 공통: **75% 이상 warning(`#f2c037`), 90% 이상 negative(`#c10015`), 그 외 primary(`#1976d2`)** — Quasar 기본 팔레트 |
| 조회 권한 | 사용량은 **인증 사용자 전원** 조회(구독 계정 1개를 전 사용자가 공유하는 운영 사실을 그대로 노출). 관리자 한정 아님 |
| 모바일(<1024px) | 세션 목록은 좌측 서랍(`q-dialog position="left"`) — QDrawer는 QLayout 직속이어야 해 페이지에서 못 쓴다. 사용량은 입력창 아래 한 줄 스트립(세션 · 이번 주 · 컨텍스트). effort 픽커는 세그먼트 대신 드롭다운 |
| 폴링 | 기존 `useTaskPolling` 5초를 그대로 쓴다(목록·상세·사용량). SSE는 턴/활동/상태 전용으로 유지 — 상세 폴링이 비용/컨텍스트를 답변 도착 후 최대 5초 내 따라온다 |
| 기존 인터뷰(작업) 화면 | `InterviewPanel`은 **슬롯/프롭 추가만**(`composer` 슬롯, `hideStatusBar`, `fill`, `status` emit, `requestCancel` expose). 기본 슬롯 폴백이 기존 마크업이라 작업 인터뷰 화면은 픽셀 단위로 불변 |

## 3. 화면 (캔버스 → 컴포넌트)

```
┌ q-header (기존 layouts/default.vue, 변경 없음) ──────────────────────────────┐
├ QuestionSidebar (280px, grey-1) ┬ NuxtPage ──────────────────────────────────┤
│ [+ 새 질문]                     │ /questions/{id}                            │
│ 오늘                            │  헤더: 제목 · 상태배지 · 레포칩 · 비용칩 ·  │
│  ● 인증 흐름 확인 (active)      │        컨텍스트 링칩 ·············· [세션 종료]│
│    답변 완료 · netis7.0 · main   │  InterviewPanel(kind=QUESTION, hideStatusBar)│
│  장비 상태 화면 데이터 소스     │    transcript: ChatBubble(마크다운) …       │
│    답변 중 · netis7.0 · feature │    #composer → QuestionComposer(mode=ask)   │
│ 지난 7일 …                      │      [textarea]                             │
│                                 │      [모델▾][추론 low|medium|high|xhigh|max] │
│ Claude Code 사용량   3분 전 갱신 │      [MCP 도구(고정)]              (send)   │
│  현재 세션 (5시간)     42% ▬▬   │  Enter 전송 · Shift+Enter 줄바꿈            │
│  이번 주 (모든 모델)   63% ▬▬   │                                              │
│ [전체 보기] (admin)             │ /questions (index) = 무엇이 궁금하세요? +    │
│                                 │   QuestionComposer(mode=create, #top=레포/브랜치)│
└─────────────────────────────────┴──────────────────────────────────────────────┘
```

- **대화(Main)**: 헤더 한 줄에 제목·상태·레포·비용·컨텍스트, 오른쪽 "세션 종료"(기존 취소 확인 다이얼로그 재사용). 트랜스크립트는 가운데 정렬 최대 820px. 입력창은 하단 고정, 12px 라운드 컨테이너, 툴바에 픽커.
- **새 질문(NewQuestion)**: 세로 중앙 히어로("무엇이 궁금하세요?" + 기존 안내 문구) + 입력창(상단 슬롯에 레포/브랜치 `q-select` 2개, 기존 브랜치 동기화 로직 이동). 제목 입력 없음.
- **모바일(Mobile)**: 헤더 왼쪽 메뉴 버튼 → 세션 서랍. 사용량 스트립은 입력창 아래.
- 사이드바 행: 제목 1줄 + 캡션 `상태 · 레포별칭 · 브랜치`(관리자 전체 보기 시 `· 요청자`). 상태 라벨은 `interviewStatusLabel(name,'QUESTION')` 재사용. 그룹 = `updatedAt` 기준 오늘/지난 7일/이전.

## 4. 데이터 흐름

### 4.1 구독 사용량 (rate limit)

```
claude CLI ─(stream-json)─► SDK query() ─► relay(): msg.type==='rate_limit_event'
   └─► opts.onRateLimit(rate_limit_info) ─► RateLimitReporter.report()
         └─► toRateLimitRequest() 정규화 ─► JavaApiClient.postRateLimit()
               └─► POST /worker/usage/rate-limits?workerId=…  (X-Worker-API-Key)
                     └─► ClaudeUsageService.record() → com.claude_rate_limit upsert(limit_type)
프론트 ClaudeUsagePanel ─(5초 폴링)─► GET /api/usage/claude → {limits:[…]}
```

- 보고 실패는 **경고 1회 + 계속 진행**, 404(구 API)면 서비스 수명 동안 비활성(`ActivityPoster` 선례). 인터뷰/답변을 절대 죽이지 않는다.
- 한 턴에 여러 이벤트(five_hour/seven_day 각각)가 올 수 있다 — 타입별로 각각 upsert.

### 4.2 컨텍스트 상태

```
relay(): stream_event(message_start, 최상위).message.usage → startContextTokens (폴백)
         assistant(최상위).message.usage → assistantContextTokens (우선)
         result.modelUsage → contextWindowOf() (입력 토큰 합 최대 모델의 contextWindow)
runner: postQuestion({…, contextTokens, contextWindow})  ← 인터뷰·질문 경로 모두, postPlan은 제외(세션 종료)
Java:   recordQuestion → s.setContextTokens/ContextWindow (null이면 이전 값 유지)
프론트: contextPercent(tokens, window) → 헤더 칩 / 모바일 스트립
```

### 4.3 정규화 규칙 (인터뷰 서비스 `toRateLimitRequest`)

| 필드 | 규칙 |
|---|---|
| `rateLimitType` | 없거나 `five_hour/seven_day/seven_day_opus/seven_day_sonnet/overage` 외 → **보고 안 함**(null) |
| `utilization` | 0..1 분수로 전달. 숫자 아님 → 0. **1 초과면 퍼센트로 간주해 /100**, 0..1 클램프, 소수 4자리 |
| `resetsAt` | 양수 숫자만. **1e12 미만이면 epoch 초 → ms**. ISO-8601 문자열로 전달, 없으면 null |
| `status` | 없으면 `allowed` |
| `isUsingOverage` | `=== true`만 true |

실단위(분수/퍼센트, 초/ms)는 Task 1 스파이크(`scripts/spikeRateLimit.ts` → `test/fixtures/RATE_LIMIT_FINDINGS.md`)로 확정한다. 정규화가 양쪽을 다 받으므로 코드 변경 없이 기록만 남긴다.

## 5. 계약 변경 (API / DB)

### 5.1 Flyway `V22__question_chat_usage.sql`

```sql
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS context_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS context_window BIGINT;

CREATE TABLE IF NOT EXISTS com.claude_rate_limit (
    limit_type       VARCHAR(20)  PRIMARY KEY,
    status           VARCHAR(20)  NOT NULL,
    utilization      NUMERIC(6,4) NOT NULL DEFAULT 0,
    resets_at        TIMESTAMPTZ,
    is_using_overage BOOLEAN      NOT NULL DEFAULT false,
    reported_by      VARCHAR(50)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### 5.2 워커 API (X-Worker-API-Key)

| 엔드포인트 | 변경 |
|---|---|
| `POST /worker/interviews/{id}/question` | 바디 `WorkerQuestionRequest`에 `contextTokens: Long`, `contextWindow: Long` 추가(둘 다 nullable — 구버전 러너 호환) |
| `POST /worker/usage/rate-limits?workerId=…` | **신규.** 바디 `WorkerRateLimitRequest{limitType, status, utilization(0..1), resetsAt?, isUsingOverage?}` → 204 |

### 5.3 사용자 API (JWT)

| 엔드포인트 | 변경 |
|---|---|
| `POST /api/questions` | `title` optional(blank면 질문 첫 줄로 생성). 응답 `QuestionSummaryResponse`에 `contextTokens`, `contextWindow` 추가 |
| `GET /api/questions`, `GET /api/questions/{id}` | 응답에 `contextTokens`, `contextWindow` 추가(`InterviewResponse`도 동일 — 인터뷰 화면은 무시) |
| `GET /api/usage/claude` | **신규.** `{limits:[{limitType, status, utilization, resetsAt, usingOverage, reportedBy, updatedAt}]}` — 인증 사용자 전원 |

### 5.4 모델 정책

`ModelEffortPolicy.ALLOWED`에서 `claude-fable-5` 제거. `validate("claude-fable-5", …)` → 400 "지원하지 않는 모델". 프론트 `MODEL_OPTIONS` 3개.

## 6. 프론트 구조

| 파일 | 책임 |
|---|---|
| `pages/questions.vue` (신규) | 셸: 사이드바(데스크톱) / 좌측 서랍(모바일) + `<NuxtPage>`. `provide('questions:refresh')`, `provide('questions:open-drawer')` |
| `pages/questions/index.vue` (재작성) | 새 질문: 히어로 + `QuestionComposer(mode=create)` + 레포/브랜치 선택(기존 로직 이동) + MCP 메뉴. POST 후 목록 refresh + 대화로 이동 |
| `pages/questions/[id].vue` (재작성) | 대화: 헤더(상태·비용·컨텍스트·세션 종료) + `InterviewPanel(kind=QUESTION, hideStatusBar, fill)` + `#composer` 슬롯에 `QuestionComposer(mode=ask)`. 상세 5초 폴링 |
| `components/QuestionSidebar.vue` (신규) | 목록 폴링·그룹·활성 표시·전체 보기 토글 + `ClaudeUsagePanel` |
| `components/ClaudeUsagePanel.vue` (신규) | `variant: panel|strip`. `/api/usage/claude` 폴링, 임계 색, 초기화 시각, 갱신 시각, 빈 상태 |
| `components/chat/ModelEffortPicker.vue` (신규) | 모델 드롭다운 + effort 세그먼트(모바일 드롭다운), `coerceEffort` 연동, disabled 표시 |
| `components/chat/QuestionComposer.vue` (신규) | textarea(Enter 전송·Shift+Enter 줄바꿈·IME 조합 가드) + 툴바(픽커 · `#tools` 슬롯 · 전송) + `#top` 슬롯 |
| `components/chat/ChatBubble.vue` (수정) | assistant 말풍선 마크다운 렌더(`renderMarkdown`) |
| `components/InterviewPanel.vue` (수정) | `hideStatusBar`/`fill` prop, `composer` 스코프 슬롯, `status` emit, `requestCancel` expose |
| `composables/useMarkdown.ts` (신규) | markdown-it 싱글턴 + `renderMarkdown()` |
| `composables/claudeUsage.ts` (신규) | 사용량 계약 미러 + 라벨/순서/임계색/퍼센트/초기화·갱신 시각 포맷 |
| `composables/questions.ts` (신규) | `QuestionSummary`/`QuestionDetail` 계약 미러, `groupByRecency()`, `contextPercent()` |
| `composables/modelEffort.ts` (수정) | Fable 제거 |

## 7. 검증

- **Java**: `ModelEffortPolicyTest`(Fable 거부), `QuestionServiceTest`(제목 생성 3케이스), `InterviewServiceTest`(컨텍스트 저장/유지), `InterviewResponseTest`·`QuestionSummaryResponseTest`(컨텍스트 노출), `ClaudeUsageServiceTest`(insert/update/overage), 통합 `QuestionApiIntegrationTest`(제목 없이 등록, 워커 컨텍스트 보고 → 조회), `UsageApiIntegrationTest`(워커 보고 → 사용자 조회, 인증 경계, 400). 통합은 `RUN_TESTCONTAINERS=true`.
- **interview-service**: `messageRelay.test.ts`(컨텍스트 우선순위·모델 선택·rate limit 콜백), `rateLimitReport.test.ts`(정규화·404 비활성·경고 1회), `javaClient.test.ts`(`postRateLimit`), `interviewRunner.test.ts`(보고 배선·postQuestion 컨텍스트 필드). 스파이크 실측 문서.
- **frontend**: `useMarkdown.spec.ts`, `ChatBubble.spec.ts`(마크다운/이스케이프), `claudeUsage.spec.ts`, `questions.spec.ts`(그룹/퍼센트), `ClaudeUsagePanel.spec.ts`, `ModelEffortPicker.spec.ts`, `QuestionComposer.spec.ts`, `InterviewPanel.spec.ts`(슬롯/프롭/emit), `QuestionSidebar.spec.ts`, `questions-shell.spec.ts`, `questions-index.spec.ts`(재작성 — 제목 없는 POST, 429, 브랜치 race 가드 유지), `questions-detail.spec.ts`(재작성). `npm run typecheck` + `npm run build`.
- **수동 스모크**(Task 15 체크리스트): 실제 구독 계정으로 질문 1건 → 사용량 행 등장 · 컨텍스트 칩 · 마크다운 · Haiku effort 강등 · 모바일 서랍/스트립 · 세션 종료.

## 8. 배포 순서 / 호환성

1. **API 서버**(V22 + 신규 워커 엔드포인트) → 2. **인터뷰 서비스** → 3. **프론트 `.output` 재빌드**.
- 구 API + 신 인터뷰 서비스: `postRateLimit` 404 → 비활성(경고 1회), `/question`의 추가 필드는 Jackson이 무시(`FAIL_ON_UNKNOWN_PROPERTIES=false` 기본). 안전.
- 신 API + 구 인터뷰 서비스: 컨텍스트 null → 칩 숨김, 사용량 빈 상태 문구. 안전.
- 프론트 라우트 `/questions/{id}`는 유지되므로 기존 링크 호환.

## 9. 범위 밖 / 후속

- 대화 중 모델·effort 변경(세션 값 갱신 + resume 시 SDK 옵션 반영 검증 필요).
- 유휴 시 사용량 자동 갱신 프로브(주기적 haiku 더미 쿼리 — 쿼터 소모 vs 신선도 트레이드오프, 운영 판단).
- Java 워커(`claude -p --output-format json`)의 rate limit 보고 — json 봉투에는 이벤트가 없어 stream-json 전환이 선행돼야 함.
- 작업(인터뷰) 화면에 사용량 패널 노출.
- 세션 목록 검색/필터.
