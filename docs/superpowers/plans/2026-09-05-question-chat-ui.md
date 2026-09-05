# 질문 탭 채팅형 UI + 구독 사용량 표시 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 질문 탭을 좌측 세션 목록 + 우측 대화창의 채팅 레이아웃으로 바꾸고, 입력창 툴바에서 모델(Fable 제외)·추론 단계를 고르며, Claude Code 구독 사용량(현재 세션 5시간·이번 주)과 대화별 컨텍스트 상태를 UI에서 확인할 수 있게 한다.

**Architecture:** 기존 질문 세션 기계장치는 그대로 두고 세 갈래를 더한다. (1) 인터뷰 서비스 relay가 SDK `rate_limit_event`와 턴 usage(`assistant.message.usage`, `result.modelUsage.contextWindow`)를 뽑아 Java에 보고하고(신규 `/worker/usage/rate-limits`, 기존 `/question` 바디 확장), Java는 `com.claude_rate_limit` upsert + `interview_session.context_*` 저장 후 `/api/usage/claude`·질문 응답으로 노출한다. (2) 프론트는 Nuxt 중첩 라우트(`pages/questions.vue` 셸 + `index`/`[id]`)로 재구성하고 `InterviewPanel`에는 슬롯/프롭만 더해 작업 인터뷰 화면을 건드리지 않는다. (3) 모델 정책은 백엔드(권위)·프론트(미러) 두 곳에서 Fable을 뺀다.

**Tech Stack:** Java 21 / Spring Boot 3.4 / JPA / Flyway / Testcontainers · TypeScript / Node 20 / `@anthropic-ai/claude-agent-sdk` 0.2.117 / vitest · Nuxt 3 / Quasar / markdown-it 14 / vitest + happy-dom

**Spec:** `docs/superpowers/specs/2026-09-05-question-chat-ui-design.md` (디자인 캔버스 https://claude.ai/code/artifact/49954c08-95f6-46e5-880e-06e77b73691c)

## Global Constraints

- 저장소 루트: `/Users/micthebick/IdeaProjects/netisMaker`. 아래 경로는 전부 이 루트 기준. 브랜치: `feature/question-chat-ui` (main에서 분기, 워크트리 권장).
- **배포 순서(스펙 §8)**: API 서버 → 인터뷰 서비스 → 프론트 `.output` 재빌드. 어느 쪽이 구버전이어도 안전하게 설계돼 있다(404 비활성 / 미지 필드 무시 / null 숨김).
- **모델 목록(스펙 §5.4)**: `claude-opus-5`(기본, effort 기본 `high`) · `claude-sonnet-5` · `claude-haiku-4-5`(low/medium/high만). **`claude-fable-5`는 목록에서 뺀다.** 백엔드 `service/ModelEffortPolicy.java`와 프론트 `frontend/composables/modelEffort.ts`를 같은 태스크에서 동시에 수정.
- **임계 색상(스펙 §2)**: 퍼센트 ≥90 → `negative`(#c10015), ≥75 → `warning`(#f2c037), 그 외 `primary`(#1976d2). 세션/주간/컨텍스트 세 지표 공통. SSOT는 `frontend/composables/claudeUsage.ts usageColor()`.
- **사용량 라벨(스펙 §3)**: `five_hour` → "현재 세션 (5시간)", `seven_day` → "이번 주 (모든 모델)", `seven_day_opus` → "이번 주 (Opus)", `seven_day_sonnet` → "이번 주 (Sonnet)". 표시 순서도 이 순서. `overage`는 패널에 그리지 않는다.
- **정규화 규칙(스펙 §4.3)**: utilization 0..1 분수(1 초과면 /100, 클램프, 소수 4자리) · resetsAt 1e12 미만이면 초→ms, ISO 문자열 · 미지 rateLimitType은 보고 안 함.
- **컨텍스트 계산(스펙 §4.2)**: 토큰 = 마지막 최상위(`parent_tool_use_id` 없음) `assistant.message.usage`의 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`; 없으면 마지막 최상위 `message_start` usage. 창 = `result.modelUsage` 중 (inputTokens+cacheRead+cacheCreation) 최대 모델의 `contextWindow`. 퍼센트 = `round(tokens/window*100)` 0..100.
- **제목 자동 생성(스펙 §2)**: title blank → 질문 `strip()` 첫 줄, 연속 공백→1칸, 60자 초과면 앞 60자 + `…`.
- **Java 스타일**: Lombok, 메시지 한국어, 400은 `new TaskException(HttpStatus.BAD_REQUEST, "...")`, 404 `TaskException.notFound()`, 403 `TaskException.forbidden()`, 409 `TaskException.conflict(String)`, 429 `TaskException.tooManyRequests(String)`. 설정 프리픽스는 `app.*`(api 프로파일). 신규 컬럼은 nullable 또는 `NOT NULL DEFAULT`.
- **Java 테스트**: 통합 테스트는 `RUN_TESTCONTAINERS=true` env 없으면 skip된다(반드시 붙여 실행: `RUN_TESTCONTAINERS=true ./gradlew test --tests '...'`). 시드 사용자는 `user1`, `user2`, `admin1`, `admin`만 가능(`requester_id` FK). 레포 카탈로그 시드 id=1 (`Netis7.0`). 워커 인증은 `X-Worker-API-Key` 헤더 + `@Value("${app.worker.api-key}")`.
- **interview-service 스타일**: 상대 임포트 `.js` 확장자 필수(NodeNext), `strict` + `noUnusedLocals` + `noUnusedParameters` + `noUncheckedIndexedAccess`. 타입체크 `npm run build`, 테스트 `npm test`(단일 파일: `npx vitest run test/<file>`). `console.warn`에는 `// eslint-disable-next-line no-console`.
- **frontend 스타일**: 작은따옴표 + **세미콜론 없음** + 2-space + trailing comma. `npm run lint-prettier`를 전체에 돌리지 말 것(단일 파일만). 테스트 `npx vitest run <file>`, 타입 `npm run typecheck`. Nuxt 자동 임포트(`ref`, `computed`, `useApi`, `useTaskPolling`, `navigateTo`, `useRoute`, `inject`, `provide`)는 테스트에서 `test/setup.ts` 전역 또는 파일별 `Object.assign(globalThis, …)`로 주입한다.
- **frontend 마크다운**: `markdown-it@14` + `@types/markdown-it@14`만 추가. `html:false, breaks:true, linkify:false`. 다른 마크다운/sanitizer 라이브러리 추가 금지.
- **디자인 토큰(캔버스)**: 사이드바 280px `#fafafa` + 우측 1px `rgba(0,0,0,.12)`, 활성 행 `#e3f2fd`/제목 `#1976d2`, 행 라운드 6px, 입력창 컨테이너 1px `rgba(0,0,0,.24)` 라운드 12px, 트랜스크립트 최대 820px, 진행바 4px(스트립 3px) 트랙 `grey-4`, 말풍선 값은 기존 `ChatBubble.vue` 그대로.
- **커밋 메시지**: 한국어, `feat:`/`fix:`/`test:`/`docs:` prefix. 각 태스크 끝에 커밋. 본문 끝에 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## File Structure

**Java (`src/main/java/com/hamonsoft/netismaker/`)**
- Modify `service/ModelEffortPolicy.java` — `ALLOWED`에서 fable 제거.
- Create `src/main/resources/db/migration/V22__question_chat_usage.sql` — `interview_session.context_tokens/context_window` + `com.claude_rate_limit`.
- Modify `entity/InterviewSession.java` — `contextTokens`, `contextWindow`.
- Modify `dto/WorkerQuestionRequest.java` — `contextTokens`, `contextWindow` 컴포넌트.
- Modify `dto/InterviewResponse.java`, `dto/QuestionSummaryResponse.java` — 컨텍스트 노출.
- Modify `service/InterviewService.java` — `recordQuestion`에서 컨텍스트 저장.
- Modify `dto/QuestionCreateRequest.java` — `title` optional.
- Modify `service/QuestionService.java` — `deriveTitle()`.
- Create `entity/ClaudeRateLimit.java`, `repository/ClaudeRateLimitRepository.java`, `dto/WorkerRateLimitRequest.java`, `dto/ClaudeUsageResponse.java`, `service/ClaudeUsageService.java`, `controller/UsageWorkerController.java`, `controller/UsageController.java`.

**interview-service (`netismaker-interview-service/`)**
- Create `scripts/spikeRateLimit.ts`, `test/fixtures/RATE_LIMIT_FINDINGS.md` — 실측.
- Modify `src/types.ts` — `RateLimitInfo`, `WorkerRateLimitRequest`, `WorkerQuestionRequest.contextTokens/contextWindow`.
- Modify `src/runner/messageRelay.ts` — `contextTokensOf`, `contextWindowOf`, `onRateLimit`, `RelayResult.contextTokens/contextWindow`.
- Create `src/runner/rateLimitReport.ts` — `toRateLimitRequests()`(실측 shape `unifiedWindows` 창별 펼침), `RateLimitReporter`.
- Modify `src/api/javaClient.ts` — `postRateLimit()`.
- Modify `src/runner/interviewRunner.ts` — 콜백 배선 + postQuestion 컨텍스트 필드.
- Modify `test/fixtures/sdkMessages.ts` — `usageAwareQuestionStream()`.

**frontend (`frontend/`)**
- Modify `composables/modelEffort.ts` — Fable 제거.
- Create `composables/useMarkdown.ts`, `composables/claudeUsage.ts`, `composables/questions.ts`.
- Modify `components/chat/ChatBubble.vue` — 마크다운.
- Create `components/chat/ModelEffortPicker.vue`, `components/chat/QuestionComposer.vue`, `components/ClaudeUsagePanel.vue`, `components/QuestionSidebar.vue`.
- Modify `components/InterviewPanel.vue` — `hideStatusBar`/`fill`/`composer` 슬롯/`status` emit/`requestCancel`.
- Create `pages/questions.vue`; rewrite `pages/questions/index.vue`, `pages/questions/[id].vue`.
- Modify `test/setup.ts` — `provide`/`inject` 전역.

**Docs**
- Modify `CLAUDE.md`(netisMaker) — 질문 탭 구조·사용량·컨텍스트·제목 자동 생성·Fable 제외.
- Modify `TODOS.md` — 후속(대화 중 모델 변경, 유휴 프로브).

---

### Task 1: 스파이크 — `rate_limit_event` / 턴 usage 실물 shape 실측

스펙 §2·§4의 데이터 소스 전제(SDK가 구독 계정에서 `rate_limit_event`를 실제로 내보내는지, `utilization`/`resetsAt` 단위, `assistant.message.usage`와 `result.modelUsage[*].contextWindow` 존재)를 실물로 확인한다. **`rate_limit_event`가 0건이면 Task 5·7·9(사용량 경로)는 착수 전에 사용자에게 보고한다.** 컨텍스트 경로(Task 3·6)는 usage가 있으면 진행.

**Files:**
- Create: `netismaker-interview-service/scripts/spikeRateLimit.ts`
- Create: `netismaker-interview-service/test/fixtures/RATE_LIMIT_FINDINGS.md`

**Interfaces:**
- Consumes: `realQuery`(`src/sdk/sdkAdapter.ts`), `resolveClaudeCli`(`src/sdk/claudeCli.ts`), `buildOptions`(`src/sdk/sessionOptions.ts`) — 모두 기존.
- Produces: 실측 문서(단위·shape). 코드 산출물 없음.

- [ ] **Step 1: 스파이크 스크립트 작성**

```ts
/**
 * 스파이크 — 구독 계정에서 SDK 0.2.117이 실제로 rate_limit_event를 내보내는지,
 * SDKRateLimitInfo.utilization(분수/퍼센트)·resetsAt(초/ms) 단위, 최상위 assistant 메시지의
 * message.usage, result.modelUsage[*].contextWindow shape을 실측한다
 * (스펙 docs/superpowers/specs/2026-09-05-question-chat-ui-design.md §4.3).
 *
 * 실행(운영자 macOS, claude 구독 로그인):
 *   cd netismaker-interview-service && set -a && source .env 2>/dev/null; set +a
 *   node --import tsx scripts/spikeRateLimit.ts
 * 산출: stdout 요약 + /tmp/rate-limit-capture.jsonl (모델 출력 포함 — 커밋 금지).
 * 판정: 마지막 RESULT 줄. rate_limit_event=0건이면 사용량 경로(Task 5·7·9) 착수 전 보고.
 * QUESTION 옵션(default-deny, Read는 workDir 한정)이라 부수효과 없음.
 */
import { appendFileSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';
import { buildOptions } from '../src/sdk/sessionOptions.js';

const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);
const workDir = mkdtempSync(join(tmpdir(), 'spike-rate-limit-'));
writeFileSync(join(workDir, 'a.md'), '# spike\n\n한 줄짜리 파일입니다.\n');
const capture = '/tmp/rate-limit-capture.jsonl';
writeFileSync(capture, '');

async function* prompt(): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield {
    type: 'user',
    message: { role: 'user', content: '현재 디렉터리의 a.md를 Read 도구로 읽고 한 문장으로 요약해줘.' },
  };
}

async function run(): Promise<void> {
  const stream = realQuery({
    prompt: prompt(),
    options: buildOptions({
      superpowersPluginPath: '',
      workDir,
      claudeCliPath,
      claudeSessionId: null,
      model: 'claude-haiku-4-5',
      effort: 'low',
      sessionKind: 'QUESTION',
    }),
  });
  let rateLimits = 0;
  let lastAssistantUsage: unknown = null;
  let lastMessageStartUsage: unknown = null;
  let modelUsage: unknown = null;
  for await (const msg of stream) {
    appendFileSync(capture, `${JSON.stringify(msg)}\n`);
    const topLevel = !(msg as { parent_tool_use_id?: string | null }).parent_tool_use_id;
    if (msg.type === 'rate_limit_event') {
      rateLimits += 1;
      // eslint-disable-next-line no-console
      console.log('[rate_limit_event]', JSON.stringify(msg.rate_limit_info));
    } else if (msg.type === 'stream_event' && topLevel) {
      const ev = msg.event as { type?: string; message?: { usage?: unknown } } | undefined;
      if (ev?.type === 'message_start') lastMessageStartUsage = ev.message?.usage ?? null;
    } else if (msg.type === 'assistant' && topLevel) {
      lastAssistantUsage = (msg.message as { usage?: unknown } | undefined)?.usage ?? null;
    } else if (msg.type === 'result') {
      modelUsage = msg.modelUsage ?? null;
    }
  }
  // eslint-disable-next-line no-console
  console.log('[message_start.usage(last top-level)]', JSON.stringify(lastMessageStartUsage));
  // eslint-disable-next-line no-console
  console.log('[assistant.usage(last top-level)]', JSON.stringify(lastAssistantUsage));
  // eslint-disable-next-line no-console
  console.log('[result.modelUsage]', JSON.stringify(modelUsage));
  // eslint-disable-next-line no-console
  console.log(
    `RESULT: rate_limit_event=${rateLimits}건 assistantUsage=${lastAssistantUsage ? '있음' : '없음'} ` +
      `messageStartUsage=${lastMessageStartUsage ? '있음' : '없음'} modelUsage=${modelUsage ? '있음' : '없음'} capture=${capture}`,
  );
  process.exit(rateLimits > 0 && (lastAssistantUsage || lastMessageStartUsage) && modelUsage ? 0 : 1);
}

void run();
```

- [ ] **Step 2: 실행 (운영자 macOS, 구독 로그인 상태)**

Run:
```bash
cd netismaker-interview-service && set -a && source .env 2>/dev/null; set +a
node --import tsx scripts/spikeRateLimit.ts; echo "exit=$?"
```
Expected: stdout에 `[rate_limit_event] {"status":"allowed","rateLimitType":"five_hour","utilization":0.12,...}` 류가 1건 이상, `[assistant.usage(...)]`에 `input_tokens`/`cache_creation_input_tokens`/`cache_read_input_tokens`, `[result.modelUsage]`에 `"claude-haiku-4-5":{...,"contextWindow":200000,...}`, 마지막 줄 `RESULT: rate_limit_event=N건 ...`, `exit=0`.

- [ ] **Step 3: 실측 문서 작성** — `test/fixtures/RATE_LIMIT_FINDINGS.md` (STREAM_SHAPE_FINDINGS.md 형식). 아래 표의 "관측" 열을 실제 값으로 채운다.

```markdown
# rate_limit_event / 턴 usage 실물 shape 캡처 — 검증 결과

**실행일:** 2026-MM-DD · **스크립트:** `scripts/spikeRateLimit.ts` · **모델:** claude-haiku-4-5 / effort low
**결과:** 종료코드 __, `/tmp/rate-limit-capture.jsonl` __줄. 원본 캡처는 커밋하지 않음(모델 출력 포함).

| # | 항목 | 가정(스펙 §4.3) | 관측 | 판정 |
|---|---|---|---|---|
| 1 | `rate_limit_event` 발생 여부/건수/시점 | 턴당 1건 이상, 첫 API 응답 직후 | | |
| 2 | `rate_limit_info.rateLimitType` 종류 | five_hour / seven_day (+opus/sonnet) | | |
| 3 | `utilization` 단위 | 0..1 분수 | | (퍼센트면 정규화 /100 경로가 처리) |
| 4 | `resetsAt` 단위 | epoch 초 | | (ms면 정규화가 처리) |
| 5 | 최상위 `assistant.message.usage` 필드 | input/cache_creation/cache_read/output | | |
| 6 | 최상위 `message_start` usage | 동일 필드(출력 전) | | |
| 7 | `result.modelUsage` 키·`contextWindow` | 모델 id 키, 200000 | | |

## STOP GATE 판정
- #1이 0건 → 사용량 경로(Task 5·7·9) 보류, 사용자 보고. 컨텍스트 경로(#5~#7 충족 시)는 진행.
- #3/#4 단위가 가정과 달라도 `toRateLimitRequest` 정규화가 양쪽을 받으므로 코드 변경 없음 — 여기 기록만 남긴다.
```

- [ ] **Step 4: 커밋**

```bash
git add netismaker-interview-service/scripts/spikeRateLimit.ts netismaker-interview-service/test/fixtures/RATE_LIMIT_FINDINGS.md
git commit -m "docs: 스파이크 — SDK rate_limit_event·턴 usage 실물 shape 실측

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: 모델 목록에서 Fable 제외 (백엔드 권위 + 프론트 미러)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/ModelEffortPolicy.java:40-44`
- Modify: `src/test/java/com/hamonsoft/netismaker/service/ModelEffortPolicyTest.java:24-30`
- Modify: `frontend/composables/modelEffort.ts:5-21`
- Modify: `frontend/composables/modelEffort.spec.ts:12-16`

**Interfaces:**
- Produces: `ModelEffortPolicy.validate("claude-fable-5", *)` → 400. `MODEL_OPTIONS` = opus-5, sonnet-5, haiku-4-5 (이 순서). 이후 태스크(픽커·테스트 픽스처)는 이 3개만 쓴다.

- [ ] **Step 1: Java 실패 테스트 — 기존 `validate_accepts_fable_opus_and_sonnet_with_max`를 교체**

```java
    @Test
    void validate_accepts_opus_and_sonnet_with_max() {
        ModelEffortPolicy.validate("claude-opus-5", "max");
        ModelEffortPolicy.validate("claude-opus-5", "xhigh");
        ModelEffortPolicy.validate("claude-sonnet-5", "max");
    }

    /** Fable은 2026-09-05 선택 목록에서 뺐다(스펙 2026-09-05 §5.4). 과거 세션의 박제 값은 검증을 타지 않는다. */
    @Test
    void validate_rejects_fable_removed_from_picker() {
        assertThatThrownBy(() -> ModelEffortPolicy.validate("claude-fable-5", "high"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("모델");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*ModelEffortPolicyTest*'`
Expected: FAIL — `validate_rejects_fable_removed_from_picker` (예외 없음).

- [ ] **Step 3: `ModelEffortPolicy.ALLOWED` 수정**

```java
    /**
     * 모델 → 허용 effort. Claude 5 제품군(Opus/Sonnet) + Haiku 4.5 (claude-api 레퍼런스 기준).
     * Haiku만 low/medium/high — 나머지는 xhigh/max까지.
     *
     * claude-fable-5는 2026-09-05 선택 목록에서 뺐다(스펙 2026-09-05-question-chat-ui-design §5.4).
     * 여기서 뺀 모델(fable-5, opus-4-8/4-7, sonnet-4-6)도 CLI에서는 여전히 유효하다.
     * 즉 이 맵은 "새로 고를 수 있는 목록"이고, 과거 작업/세션에 박제된 모델 값은
     * 검증을 타지 않으므로 그대로 실행된다(배포 Dockerfile 생성 등).
     */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "claude-opus-5", FULL,
            "claude-sonnet-5", FULL,
            "claude-haiku-4-5", LIMITED);
```

- [ ] **Step 4: Java 통과 확인**

Run: `./gradlew test --tests '*ModelEffortPolicyTest*'`
Expected: PASS (8 tests).

- [ ] **Step 5: 프론트 실패 테스트 — `modelEffort.spec.ts`의 `exposes the four models` 교체**

```ts
  it('exposes the three picker models (Fable 제외 — 스펙 2026-09-05 §5.4)', () => {
    expect(MODEL_OPTIONS.map((m) => m.value)).toEqual([
      'claude-opus-5', 'claude-sonnet-5', 'claude-haiku-4-5',
    ])
    expect(MODEL_OPTIONS.find((m) => m.value === 'claude-fable-5')).toBeUndefined()
  })
```

- [ ] **Step 6: 실패 확인**

Run: `cd frontend && npx vitest run composables/modelEffort.spec.ts`
Expected: FAIL — 배열 길이 4 ≠ 3.

- [ ] **Step 7: `modelEffort.ts` 수정** (전체 파일)

```ts
// 모델/effort 선택 옵션 + 모델별 허용 effort(단일 진실원천, 백엔드 ModelEffortPolicy와 동기화).
// claude-fable-5는 2026-09-05 목록에서 제외(스펙 2026-09-05-question-chat-ui-design §5.4).
export const DEFAULT_MODEL = 'claude-opus-5'
export const DEFAULT_EFFORT = 'high'

export const MODEL_OPTIONS = [
  { label: 'Opus 5 (기본)', value: 'claude-opus-5' },
  { label: 'Sonnet 5', value: 'claude-sonnet-5' },
  { label: 'Haiku 4.5', value: 'claude-haiku-4-5' },
]

// ultracode는 의도적으로 없다 — CLI가 인식하는 --effort 별칭이지만 헤드리스에서
// xhigh와 관측 가능한 차이가 없다(사유는 백엔드 ModelEffortPolicy 주석 참고).
const FULL = ['low', 'medium', 'high', 'xhigh', 'max']
const LIMITED = ['low', 'medium', 'high']

const MODEL_EFFORT_MAP: Record<string, string[]> = {
  'claude-opus-5': FULL,
  'claude-sonnet-5': FULL,
  'claude-haiku-4-5': LIMITED,
}

/** 모델이 허용하는 effort 목록. 미지 모델은 FULL(서버가 권위 검증). */
export function effortsForModel(model: string): string[] {
  return MODEL_EFFORT_MAP[model] ?? FULL
}

/** 현재 effort가 모델에서 허용되면 유지, 아니면 기본값(high)으로 강등. */
export function coerceEffort(model: string, effort: string): string {
  return effortsForModel(model).includes(effort) ? effort : DEFAULT_EFFORT
}
```

- [ ] **Step 8: 프론트 통과 확인 + 다른 참조 확인**

Run: `cd frontend && npx vitest run composables/modelEffort.spec.ts && grep -rn "fable" --include='*.ts' --include='*.vue' . | grep -v node_modules | grep -v .nuxt`
Expected: PASS · grep 결과 없음(README/FINDINGS 문서의 캡처 로그는 무관).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/ModelEffortPolicy.java src/test/java/com/hamonsoft/netismaker/service/ModelEffortPolicyTest.java frontend/composables/modelEffort.ts frontend/composables/modelEffort.spec.ts
git commit -m "feat: 모델 선택 목록에서 Fable 제외 (ModelEffortPolicy + 프론트 미러)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Java — 세션별 컨텍스트 상태 저장·노출

**Files:**
- Create: `src/main/resources/db/migration/V22__question_chat_usage.sql`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java:96-98` (cacheReadTokens 다음)
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/WorkerQuestionRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java:170-192` (`recordQuestion`)
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java`, `dto/QuestionSummaryResponse.java`
- Modify(호출부 인자 추가): `src/test/java/com/hamonsoft/netismaker/service/InterviewTaskMirrorTest.java:51,79`, `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java:113,126,134`
- Test: `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`, `dto/InterviewResponseTest.java`, Create `dto/QuestionSummaryResponseTest.java`, `controller/QuestionApiIntegrationTest.java`

**Interfaces:**
- Produces: `WorkerQuestionRequest(content, claudeSessionId, kind, costUsd, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, Long contextTokens, Long contextWindow)`; `InterviewSession.getContextTokens()/getContextWindow()` (`Long`, null=미보고); `InterviewResponse.contextTokens()/contextWindow()`, `QuestionSummaryResponse.contextTokens()/contextWindow()` (JSON `contextTokens`, `contextWindow`). Task 7(러너)·Task 12~14(프론트)가 이 이름을 그대로 쓴다.
- V22가 `com.claude_rate_limit` 테이블도 만든다(Task 5가 엔티티를 붙인다 — `ddl-auto: validate`는 엔티티 없는 테이블을 문제 삼지 않는다).

- [ ] **Step 1: 실패 테스트 — `InterviewServiceTest`에 추가** (기존 `recordQuestion_moves_running_to_awaiting_input_and_releases_worker` 아래)

```java
    @Test
    void recordQuestion_stores_context_snapshot_when_reported() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(2L)).thenReturn(null);
        service.recordQuestion(2L, "w1", new com.hamonsoft.netismaker.dto.WorkerQuestionRequest(
                "AuthController입니다", "sess-abc", "question", new BigDecimal("0.01"),
                4L, 120L, 30000L, 46000L, 76004L, 200000L));
        assertThat(s.getContextTokens()).isEqualTo(76004L);
        assertThat(s.getContextWindow()).isEqualTo(200000L);
    }

    /** 구버전 인터뷰 서비스(컨텍스트 미보고)가 보고해도 이전 스냅샷을 지우지 않는다. */
    @Test
    void recordQuestion_keeps_previous_context_when_not_reported() {
        InterviewSession s = session(2L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        s.setContextTokens(50000L);
        s.setContextWindow(200000L);
        when(sessionRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(2L)).thenReturn(null);
        service.recordQuestion(2L, "w1", new com.hamonsoft.netismaker.dto.WorkerQuestionRequest(
                "답변", "sess-abc", "question", BigDecimal.ZERO, null, null, null, null, null, null));
        assertThat(s.getContextTokens()).isEqualTo(50000L);
        assertThat(s.getContextWindow()).isEqualTo(200000L);
    }
```

`InterviewResponseTest`에 추가:

```java
    @Test
    void of_carries_context_snapshot() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setContextTokens(76004L);
        q.setContextWindow(200000L);
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.contextTokens()).isEqualTo(76004L);
        assertThat(r.contextWindow()).isEqualTo(200000L);
    }

    @Test
    void of_leaves_context_null_when_never_reported() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.contextTokens()).isNull();
        assertThat(r.contextWindow()).isNull();
    }
```

새 파일 `src/test/java/com/hamonsoft/netismaker/dto/QuestionSummaryResponseTest.java`:

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionSummaryResponseTest {

    /** 사이드바 행/헤더 칩이 쓰는 컨텍스트 스냅샷이 목록 응답에도 실린다 (스펙 2026-09-05 §5.3). */
    @Test
    void of_carries_context_snapshot() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "인증 흐름", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setContextTokens(76004L);
        q.setContextWindow(200000L);
        QuestionSummaryResponse r = QuestionSummaryResponse.of(q);
        assertThat(r.title()).isEqualTo("인증 흐름");
        assertThat(r.statusName()).isEqualTo("QUEUED");
        assertThat(r.contextTokens()).isEqualTo(76004L);
        assertThat(r.contextWindow()).isEqualTo(200000L);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew compileTestJava`
Expected: 컴파일 실패 — `WorkerQuestionRequest` 생성자 인자 수 불일치, `getContextTokens` 없음.

- [ ] **Step 3: Flyway V22 작성** — `src/main/resources/db/migration/V22__question_chat_usage.sql`

```sql
-- V22: 질문 탭 채팅형 UI + Claude Code 구독 사용량
--      (docs/superpowers/specs/2026-09-05-question-chat-ui-design.md §5.1)

-- 1) 세션별 컨텍스트 상태 — 마지막 턴의 컨텍스트 토큰/창 크기. null = 미보고(구버전 인터뷰 서비스).
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS context_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS context_window BIGINT;

-- 2) Claude Code 구독 사용량 — SDK rate_limit_event 스냅샷. limit_type당 1행 upsert (계정 1개 공유 전제).
CREATE TABLE IF NOT EXISTS com.claude_rate_limit (
    limit_type       VARCHAR(20)  PRIMARY KEY,               -- five_hour | seven_day | seven_day_opus | seven_day_sonnet | overage
    status           VARCHAR(20)  NOT NULL,                  -- allowed | allowed_warning | rejected
    utilization      NUMERIC(6,4) NOT NULL DEFAULT 0,        -- 0..1 분수
    resets_at        TIMESTAMPTZ,
    is_using_overage BOOLEAN      NOT NULL DEFAULT false,
    reported_by      VARCHAR(50)  NOT NULL,                  -- 보고한 인터뷰 서비스 WORKER_ID
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

- [ ] **Step 4: 엔티티 필드 추가** — `InterviewSession.java`, `cacheReadTokens` 필드 바로 아래

```java
    /**
     * 마지막 턴의 컨텍스트 토큰(input + cache_creation + cache_read, 마지막 최상위 assistant 메시지 기준).
     * null = 미보고(구버전 인터뷰 서비스). 스펙 2026-09-05 §4.2.
     */
    @Column(name = "context_tokens")
    @Setter
    private Long contextTokens;

    /** 마지막 턴 주 모델의 컨텍스트 창 크기(result.modelUsage[*].contextWindow). null = 미보고. */
    @Column(name = "context_window")
    @Setter
    private Long contextWindow;
```

- [ ] **Step 5: `WorkerQuestionRequest` 확장** (전체 파일)

```java
package com.hamonsoft.netismaker.dto;

import java.math.BigDecimal;

/**
 * 워커가 보고하는 질문 턴. kind: question | design | gate | note.
 * contextTokens/contextWindow는 스펙 2026-09-05 §4.2 — 구버전 러너는 안 보내므로 null 허용.
 */
public record WorkerQuestionRequest(
        String content,
        String claudeSessionId,
        String kind,
        BigDecimal costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheCreationTokens,
        Long cacheReadTokens,
        /** 마지막 최상위 assistant 메시지의 input+cache_creation+cache_read. null = 미보고. */
        Long contextTokens,
        /** 주 모델의 컨텍스트 창 크기. null = 미보고. */
        Long contextWindow
) {}
```

- [ ] **Step 6: `InterviewService.recordQuestion` — `addUsage(...)` 호출 직후에 추가**

```java
        addUsage(s, req.costUsd(), req.inputTokens(), req.outputTokens(),
                req.cacheCreationTokens(), req.cacheReadTokens());
        // 컨텍스트 스냅샷(스펙 2026-09-05 §4.2): 마지막 턴 값으로 덮어쓴다. 미보고(null)면 이전 값 유지.
        if (req.contextTokens() != null) s.setContextTokens(req.contextTokens());
        if (req.contextWindow() != null) s.setContextWindow(req.contextWindow());
```

- [ ] **Step 7: 응답 DTO 확장**

`InterviewResponse.java` — 레코드 마지막 컴포넌트 `BigDecimal totalCostUsd` 뒤에 추가하고 `of(...)`의 생성자 호출 끝에 `s.getContextTokens(), s.getContextWindow()`를 넘긴다:

```java
        /** 세션 누적 SHADOW 비용(상세 헤더 칩). */
        BigDecimal totalCostUsd,
        /** 마지막 턴 컨텍스트 토큰 / 창 크기 (스펙 2026-09-05 §4.2). null = 미보고 → 프론트는 칩 숨김. */
        Long contextTokens,
        Long contextWindow
) {
```
```java
        return new InterviewResponse(
                s.getId(), s.getGithubRepo(), s.getGithubBranch(), s.getTitle(), s.getDescription(),
                s.getStatus().dbValue(), s.getStatus().name(), s.getCurrentPhase(), s.getWorkDir(), s.getTaskId(),
                s.getModel(), s.getEffort(), tvs, pv, s.getCreatedAt(), s.getUpdatedAt(),
                s.getKind().name(), s.getTotalCostUsd(), s.getContextTokens(), s.getContextWindow());
```

`QuestionSummaryResponse.java` — `OffsetDateTime updatedAt` 뒤에 `Long contextTokens, Long contextWindow` 추가, `of(...)` 마지막 인자에 `s.getContextTokens(), s.getContextWindow()`:

```java
        BigDecimal totalCostUsd,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** 마지막 턴 컨텍스트 스냅샷 (스펙 2026-09-05 §4.2). null = 미보고. */
        Long contextTokens,
        Long contextWindow
) {
    public static QuestionSummaryResponse of(InterviewSession s) {
        return new QuestionSummaryResponse(
                s.getId(), s.getTitle(), s.getGithubRepo(), s.getGithubBranch(), s.getRepoAlias(),
                s.getRequesterId(), s.getStatus().dbValue(), s.getStatus().name(),
                s.getModel(), s.getEffort(), s.getTotalCostUsd(), s.getCreatedAt(), s.getUpdatedAt(),
                s.getContextTokens(), s.getContextWindow());
    }
```

- [ ] **Step 8: 기존 호출부에 인자 2개 추가** — 아래 각 `new WorkerQuestionRequest(...)`의 마지막 인자 뒤에 `, null, null` 을 붙인다.

Run: `grep -rn "new WorkerQuestionRequest(" src/test | cut -d: -f1,2`
Expected: `InterviewTaskMirrorTest.java:51`, `InterviewTaskMirrorTest.java:79`, `InterviewServiceTest.java` 3곳(기존) + Step 1의 2곳(이미 10인자). 기존 5곳을 모두 `…, null, null)`로 수정.

- [ ] **Step 9: 통과 확인**

Run: `./gradlew test --tests '*InterviewServiceTest*' --tests '*InterviewResponseTest*' --tests '*QuestionSummaryResponseTest*' --tests '*InterviewTaskMirrorTest*'`
Expected: PASS.

- [ ] **Step 10: 통합 테스트 — `QuestionApiIntegrationTest`에 추가** (워커 보고 → 사용자 조회 노출)

```java
    /** 워커가 /question에 컨텍스트 스냅샷을 보고하면 상세·목록 응답에 그대로 노출된다 (스펙 2026-09-05 §4.2). */
    @Test
    void worker_context_snapshot_is_exposed_on_detail_and_list() throws Exception {
        String created = mvc.perform(post("/api/questions").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();

        mvc.perform(post("/worker/interviews/claim").param("workerId", "w1").header("X-Worker-API-Key", apiKey))
                .andExpect(status().isOk());
        mvc.perform(post("/worker/interviews/" + id + "/question").param("workerId", "w1")
                        .header("X-Worker-API-Key", apiKey).contentType(APPLICATION_JSON)
                        .content("{\"content\":\"AuthController입니다\",\"claudeSessionId\":\"sess-1\",\"kind\":\"question\","
                                + "\"costUsd\":0.01,\"inputTokens\":4,\"outputTokens\":120,\"cacheCreationTokens\":30000,"
                                + "\"cacheReadTokens\":46000,\"contextTokens\":76004,\"contextWindow\":200000}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextTokens").value(76004))
                .andExpect(jsonPath("$.contextWindow").value(200000));
        mvc.perform(get("/api/questions").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contextTokens").value(76004));
    }
```

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*QuestionApiIntegrationTest*'`
Expected: PASS (V22 적용 포함).

- [ ] **Step 11: 커밋**

```bash
git add src/main/resources/db/migration/V22__question_chat_usage.sql src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java src/main/java/com/hamonsoft/netismaker/dto/WorkerQuestionRequest.java src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java src/main/java/com/hamonsoft/netismaker/dto/QuestionSummaryResponse.java src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/test/java
git commit -m "feat: 질문 세션 컨텍스트 스냅샷(context_tokens/context_window) 저장·노출 + V22

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Java — 제목 자동 생성 (title optional)

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/QuestionCreateRequest.java:18-20`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/QuestionService.java:52-68` (`create`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/QuestionServiceTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/QuestionApiIntegrationTest.java`

**Interfaces:**
- Produces: `QuestionService.deriveTitle(String title, String question)` (package-private static). `POST /api/questions` 바디에서 `title` 생략 가능. Task 13(프론트 새 질문)이 title 없이 POST한다.

- [ ] **Step 1: 실패 테스트 — `QuestionServiceTest`에 추가**

```java
    private static QuestionCreateRequest reqWithTitle(String title, String question) {
        return new QuestionCreateRequest(1L, "dev", title, question, null, null, null);
    }

    @Test
    void create_derives_title_from_first_line_when_blank() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(
                reqWithTitle(null, "  로그인은   어디서 처리되나요?\n두 번째 줄은 제목에 안 들어간다"), "user1");
        assertThat(s.getTitle()).isEqualTo("로그인은 어디서 처리되나요?");
        // 본문(킥오프 프롬프트에 그대로 삽입)은 원문 유지
        assertThat(s.getDescription()).isEqualTo("  로그인은   어디서 처리되나요?\n두 번째 줄은 제목에 안 들어간다");
    }

    @Test
    void create_truncates_derived_title_to_60_chars_with_ellipsis() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(reqWithTitle("   ", "가".repeat(70)), "user1");
        assertThat(s.getTitle()).hasSize(61).startsWith("가".repeat(60)).endsWith("…");
    }

    @Test
    void create_keeps_explicit_title_trimmed() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(reqWithTitle("  인증 흐름  ", "질문 본문"), "user1");
        assertThat(s.getTitle()).isEqualTo("인증 흐름");
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*QuestionServiceTest*'`
Expected: FAIL — `create_derives_title_from_first_line_when_blank`(title null이 그대로 들어가 NPE/불일치), `create_keeps_explicit_title_trimmed`(trim 안 됨).

- [ ] **Step 3: `QuestionCreateRequest.title` optional로 완화**

```java
        /** 선택. blank면 서버가 질문 첫 줄로 생성한다 (QuestionService.deriveTitle, 스펙 2026-09-05 §2). */
        @Size(max = 500)
        String title,
```
(`@NotBlank` import는 `question`이 계속 쓰므로 유지.)

- [ ] **Step 4: `QuestionService` — `deriveTitle` 추가 + `create`에서 사용**

필드 영역에 추가:
```java
    /** 자동 생성 제목 최대 길이(초과 시 절단 + '…'). 스펙 2026-09-05 §2. */
    static final int DERIVED_TITLE_MAX = 60;
```

`create(...)`의 `InterviewSession.createQuestion(...)` 호출을 다음으로 교체:
```java
        InterviewSession s = InterviewSession.createQuestion(repo.ownerRepo(), req.githubBranch(),
                deriveTitle(req.title(), req.question()), req.question(), requesterId, extras, model, effort);
```

클래스 끝에 추가:
```java
    /**
     * 제목 미입력 시 질문 첫 줄에서 생성: strip → 첫 줄 → 연속 공백 1칸 → 60자 초과면 절단+'…'.
     * 명시 제목은 trim만 한다. question이 blank인 경우는 @NotBlank가 먼저 400으로 막는다.
     */
    static String deriveTitle(String title, String question) {
        if (title != null && !title.isBlank()) return title.trim();
        String firstLine = question.strip().lines().findFirst().orElse("")
                .replaceAll("\\s+", " ").trim();
        if (firstLine.length() <= DERIVED_TITLE_MAX) return firstLine;
        return firstLine.substring(0, DERIVED_TITLE_MAX) + "…";
    }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests '*QuestionServiceTest*'`
Expected: PASS.

- [ ] **Step 6: 통합 테스트 — `QuestionApiIntegrationTest`에 추가**

```java
    /** 프론트 새 질문 입력창은 제목을 보내지 않는다 — 서버가 첫 줄로 생성 (스펙 2026-09-05 §2). */
    @Test
    void create_without_title_derives_title_from_question() throws Exception {
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON)
                        .content("{\"repoCatalogId\":1,\"githubBranch\":\"main\","
                                + "\"question\":\"로그인은 어디서 처리되나요?\\n상세 설명\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("로그인은 어디서 처리되나요?"));
    }
```

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*QuestionApiIntegrationTest*'`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/QuestionCreateRequest.java src/main/java/com/hamonsoft/netismaker/service/QuestionService.java src/test/java/com/hamonsoft/netismaker/service/QuestionServiceTest.java src/test/java/com/hamonsoft/netismaker/controller/QuestionApiIntegrationTest.java
git commit -m "feat: 질문 제목 미입력 시 질문 첫 줄로 자동 생성 (title optional)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Java — Claude Code 구독 사용량 저장(워커 보고) · 조회(사용자)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/entity/ClaudeRateLimit.java`
- Create: `src/main/java/com/hamonsoft/netismaker/repository/ClaudeRateLimitRepository.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/WorkerRateLimitRequest.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/ClaudeUsageResponse.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/ClaudeUsageService.java`
- Create: `src/main/java/com/hamonsoft/netismaker/controller/UsageWorkerController.java`
- Create: `src/main/java/com/hamonsoft/netismaker/controller/UsageController.java`
- Test: Create `src/test/java/com/hamonsoft/netismaker/service/ClaudeUsageServiceTest.java`, `src/test/java/com/hamonsoft/netismaker/controller/UsageApiIntegrationTest.java`

**Interfaces:**
- Consumes: V22의 `com.claude_rate_limit` (Task 3).
- Produces: `POST /worker/usage/rate-limits?workerId=…` 바디 `{limitType, status, utilization, resetsAt?, isUsingOverage?}` → 204 (Task 7 러너가 호출). `GET /api/usage/claude` → `{"limits":[{limitType, status, utilization, resetsAt, usingOverage, reportedBy, updatedAt}]}` (Task 9 패널이 호출). `ClaudeUsageService.record(WorkerRateLimitRequest, String workerId)`, `snapshot()`.

- [ ] **Step 1: 실패 테스트 — `ClaudeUsageServiceTest` 작성**

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ClaudeUsageResponse;
import com.hamonsoft.netismaker.dto.WorkerRateLimitRequest;
import com.hamonsoft.netismaker.entity.ClaudeRateLimit;
import com.hamonsoft.netismaker.repository.ClaudeRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeUsageServiceTest {

    private ClaudeRateLimitRepository repo;
    private ClaudeUsageService service;

    @BeforeEach
    void setUp() {
        repo = mock(ClaudeRateLimitRepository.class);
        service = new ClaudeUsageService(repo);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static WorkerRateLimitRequest req(String type, String util, String resetsAt, Boolean overage) {
        return new WorkerRateLimitRequest(type, "allowed", new BigDecimal(util),
                resetsAt == null ? null : OffsetDateTime.parse(resetsAt), overage);
    }

    @Test
    void record_inserts_new_row_keyed_by_limit_type() {
        when(repo.findById("five_hour")).thenReturn(Optional.empty());
        ClaudeRateLimit row = service.record(req("five_hour", "0.42", "2026-09-05T07:00:00Z", null), "iw-1");
        assertThat(row.getLimitType()).isEqualTo("five_hour");
        assertThat(row.getStatus()).isEqualTo("allowed");
        assertThat(row.getUtilization()).isEqualByComparingTo("0.42");
        assertThat(row.getResetsAt()).isEqualTo(OffsetDateTime.parse("2026-09-05T07:00:00Z"));
        assertThat(row.isUsingOverage()).isFalse();          // null → false
        assertThat(row.getReportedBy()).isEqualTo("iw-1");
        assertThat(row.getUpdatedAt()).isNotNull();
        verify(repo).save(row);
    }

    @Test
    void record_updates_existing_row_in_place() {
        ClaudeRateLimit existing = new ClaudeRateLimit();
        existing.setLimitType("seven_day");
        existing.setStatus("allowed");
        existing.setUtilization(new BigDecimal("0.10"));
        existing.setReportedBy("old");
        existing.setUpdatedAt(OffsetDateTime.now().minusHours(1));
        when(repo.findById("seven_day")).thenReturn(Optional.of(existing));

        ClaudeRateLimit row = service.record(req("seven_day", "0.63", null, true), "iw-2");

        assertThat(row).isSameAs(existing);
        assertThat(row.getUtilization()).isEqualByComparingTo("0.63");
        assertThat(row.getResetsAt()).isNull();
        assertThat(row.isUsingOverage()).isTrue();
        assertThat(row.getReportedBy()).isEqualTo("iw-2");
        assertThat(row.getUpdatedAt()).isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    void snapshot_maps_rows_in_repository_order() {
        ClaudeRateLimit a = new ClaudeRateLimit();
        a.setLimitType("five_hour");
        a.setStatus("allowed");
        a.setUtilization(new BigDecimal("0.42"));
        a.setReportedBy("iw-1");
        a.setUpdatedAt(OffsetDateTime.now());
        when(repo.findAllByOrderByLimitTypeAsc()).thenReturn(List.of(a));

        ClaudeUsageResponse res = service.snapshot();

        assertThat(res.limits()).hasSize(1);
        assertThat(res.limits().get(0).limitType()).isEqualTo("five_hour");
        assertThat(res.limits().get(0).utilization()).isEqualByComparingTo("0.42");
        assertThat(res.limits().get(0).usingOverage()).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew compileTestJava`
Expected: 컴파일 실패 — `ClaudeUsageService`, `ClaudeRateLimit` 등 없음.

- [ ] **Step 3: 엔티티 + 리포지토리**

`entity/ClaudeRateLimit.java`:
```java
package com.hamonsoft.netismaker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Claude Code 구독 사용량 스냅샷 — 인터뷰 서비스가 SDK rate_limit_event를 보고할 때마다 limit_type별로 갱신
 * (스펙 2026-09-05 §4.1). 구독 계정 1개를 전체가 공유하므로 계정 키 없이 limit_type이 PK다.
 * 쓰기는 ClaudeUsageService.record만.
 */
@Entity
@Table(name = "claude_rate_limit", schema = "com")
@Getter
@Setter
@NoArgsConstructor
public class ClaudeRateLimit {

    /** five_hour | seven_day | seven_day_opus | seven_day_sonnet | overage (SDKRateLimitInfo.rateLimitType). */
    @Id
    @Column(name = "limit_type", length = 20)
    private String limitType;

    /** allowed | allowed_warning | rejected. */
    @Column(nullable = false, length = 20)
    private String status;

    /** 0..1 분수 (러너가 정규화). */
    @Column(nullable = false)
    private BigDecimal utilization = BigDecimal.ZERO;

    @Column(name = "resets_at")
    private OffsetDateTime resetsAt;

    @Column(name = "is_using_overage", nullable = false)
    private boolean usingOverage;

    /** 보고한 인터뷰 서비스 WORKER_ID. */
    @Column(name = "reported_by", nullable = false, length = 50)
    private String reportedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
```

`repository/ClaudeRateLimitRepository.java`:
```java
package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.ClaudeRateLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaudeRateLimitRepository extends JpaRepository<ClaudeRateLimit, String> {
    /** 표시 순서는 프론트(LIMIT_ORDER)가 정한다 — 여기선 결정적 순서만 보장. */
    List<ClaudeRateLimit> findAllByOrderByLimitTypeAsc();
}
```

- [ ] **Step 4: DTO 2개**

`dto/WorkerRateLimitRequest.java`:
```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Body for POST /worker/usage/rate-limits?workerId=… — 인터뷰 서비스가 정규화한 SDK rate_limit_info
 * (스펙 2026-09-05 §4.3: utilization 0..1, resetsAt ISO-8601 또는 null).
 */
public record WorkerRateLimitRequest(
        @NotBlank @Size(max = 20) String limitType,
        @NotBlank @Size(max = 20) String status,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal utilization,
        OffsetDateTime resetsAt,
        Boolean isUsingOverage
) {}
```

`dto/ClaudeUsageResponse.java`:
```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.ClaudeRateLimit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** GET /api/usage/claude — 프론트 `composables/claudeUsage.ts ClaudeUsageResponse`와 필드명 동기. */
public record ClaudeUsageResponse(List<LimitView> limits) {

    public record LimitView(String limitType, String status, BigDecimal utilization,
                            OffsetDateTime resetsAt, boolean usingOverage,
                            String reportedBy, OffsetDateTime updatedAt) {
        public static LimitView of(ClaudeRateLimit r) {
            return new LimitView(r.getLimitType(), r.getStatus(), r.getUtilization(),
                    r.getResetsAt(), r.isUsingOverage(), r.getReportedBy(), r.getUpdatedAt());
        }
    }
}
```

- [ ] **Step 5: 서비스**

`service/ClaudeUsageService.java`:
```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.ClaudeUsageResponse;
import com.hamonsoft.netismaker.dto.WorkerRateLimitRequest;
import com.hamonsoft.netismaker.entity.ClaudeRateLimit;
import com.hamonsoft.netismaker.repository.ClaudeRateLimitRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Claude Code 구독 사용량 — 스펙 2026-09-05 §4.1. limit_type당 1행 upsert.
 * 보고 빈도가 낮고(턴당 수 건) 보고자가 인터뷰 서비스 1개라 네이티브 ON CONFLICT 없이 findById→save로 충분.
 */
@Service
@Profile("api")
public class ClaudeUsageService {

    private final ClaudeRateLimitRepository repo;

    public ClaudeUsageService(ClaudeRateLimitRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public ClaudeRateLimit record(WorkerRateLimitRequest req, String workerId) {
        ClaudeRateLimit row = repo.findById(req.limitType()).orElseGet(() -> {
            ClaudeRateLimit r = new ClaudeRateLimit();
            r.setLimitType(req.limitType());
            return r;
        });
        row.setStatus(req.status());
        row.setUtilization(req.utilization());
        row.setResetsAt(req.resetsAt());
        row.setUsingOverage(Boolean.TRUE.equals(req.isUsingOverage()));
        row.setReportedBy(workerId);
        row.setUpdatedAt(OffsetDateTime.now());
        return repo.save(row);
    }

    @Transactional(readOnly = true)
    public ClaudeUsageResponse snapshot() {
        return new ClaudeUsageResponse(
                repo.findAllByOrderByLimitTypeAsc().stream().map(ClaudeUsageResponse.LimitView::of).toList());
    }
}
```

- [ ] **Step 6: 컨트롤러 2개**

`controller/UsageWorkerController.java`:
```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.WorkerRateLimitRequest;
import com.hamonsoft.netismaker.service.ClaudeUsageService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 구독 사용량 워커 API (X-Worker-API-Key, InterviewWorkerController 미러).
 *   POST /worker/usage/rate-limits?workerId=… ─► SDK rate_limit_info 스냅샷 upsert (스펙 2026-09-05 §5.2)
 * /worker/** 는 SecurityConfig.workerFilterChain이 감싼다 — 별도 등록 불필요.
 */
@RestController
@RequestMapping("/worker/usage")
@PreAuthorize("hasAuthority('ROLE_WORKER')")
@Profile("api")
public class UsageWorkerController {

    private final ClaudeUsageService usage;

    public UsageWorkerController(ClaudeUsageService usage) {
        this.usage = usage;
    }

    @PostMapping("/rate-limits")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rateLimit(@RequestParam String workerId, @RequestBody @Valid WorkerRateLimitRequest req) {
        usage.record(req, workerId);
    }
}
```

`controller/UsageController.java`:
```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.ClaudeUsageResponse;
import com.hamonsoft.netismaker.service.ClaudeUsageService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구독 사용량 조회 — 인증 사용자 전원 (스펙 2026-09-05 §2 "조회 권한": 구독 계정 1개를 전 사용자가 공유).
 *   GET /api/usage/claude → {limits:[…]}
 */
@RestController
@RequestMapping("/api/usage")
@Profile("api")
public class UsageController {

    private final ClaudeUsageService usage;

    public UsageController(ClaudeUsageService usage) {
        this.usage = usage;
    }

    @GetMapping("/claude")
    public ClaudeUsageResponse claude() {
        return usage.snapshot();
    }
}
```

- [ ] **Step 7: 단위 테스트 통과 확인**

Run: `./gradlew test --tests '*ClaudeUsageServiceTest*'`
Expected: PASS (3 tests).

- [ ] **Step 8: 통합 테스트 — `UsageApiIntegrationTest` 작성**

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.repository.ClaudeRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 구독 사용량 API 표면 (스펙 2026-09-05 §5.2/§5.3): 워커 보고 → 사용자 조회, upsert, 검증, 인증 경계. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class UsageApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ClaudeRateLimitRepository repo;
    @Value("${app.worker.api-key}") private String apiKey;

    @BeforeEach void clean() {
        repo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void worker_report_is_upserted_and_visible_to_any_authenticated_user() throws Exception {
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed\",\"utilization\":0.42,"
                                + "\"resetsAt\":\"2026-09-05T07:00:00Z\",\"isUsingOverage\":false}"))
                .andExpect(status().isNoContent());
        // 같은 타입 재보고 → 행 1개 유지(upsert), 최신 값으로 교체
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed_warning\",\"utilization\":0.81}"))
                .andExpect(status().isNoContent());
        assertThat(repo.count()).isEqualTo(1);

        mvc.perform(get("/api/usage/claude").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits", hasSize(1)))
                .andExpect(jsonPath("$.limits[0].limitType").value("five_hour"))
                .andExpect(jsonPath("$.limits[0].status").value("allowed_warning"))
                .andExpect(jsonPath("$.limits[0].utilization").value(0.81))
                .andExpect(jsonPath("$.limits[0].reportedBy").value("iw-1"))
                .andExpect(jsonPath("$.limits[0].updatedAt").exists());
    }

    @Test
    void utilization_outside_0_1_is_rejected() throws Exception {
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").header("X-Worker-API-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed\",\"utilization\":1.5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auth_boundaries() throws Exception {
        // 워커 키 없음 → WorkerApiKeyFilter가 거절
        mvc.perform(post("/worker/usage/rate-limits").param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"limitType\":\"five_hour\",\"status\":\"allowed\",\"utilization\":0.1}"))
                .andExpect(status().is4xxClientError());
        // JWT 없음 → 401
        mvc.perform(get("/api/usage/claude")).andExpect(status().isUnauthorized());
    }
}
```

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*UsageApiIntegrationTest*'`
Expected: PASS (3 tests).

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/entity/ClaudeRateLimit.java src/main/java/com/hamonsoft/netismaker/repository/ClaudeRateLimitRepository.java src/main/java/com/hamonsoft/netismaker/dto/WorkerRateLimitRequest.java src/main/java/com/hamonsoft/netismaker/dto/ClaudeUsageResponse.java src/main/java/com/hamonsoft/netismaker/service/ClaudeUsageService.java src/main/java/com/hamonsoft/netismaker/controller/UsageWorkerController.java src/main/java/com/hamonsoft/netismaker/controller/UsageController.java src/test/java/com/hamonsoft/netismaker/service/ClaudeUsageServiceTest.java src/test/java/com/hamonsoft/netismaker/controller/UsageApiIntegrationTest.java
git commit -m "feat: Claude Code 구독 사용량 저장(/worker/usage/rate-limits)·조회(/api/usage/claude)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: interview-service — relay가 컨텍스트 스냅샷과 `rate_limit_event`를 뽑는다

**Files:**
- Modify: `netismaker-interview-service/src/types.ts` (`WorkerQuestionRequest` 확장 + `RateLimitInfo`, `WorkerRateLimitRequest` 추가)
- Modify: `netismaker-interview-service/src/runner/messageRelay.ts`
- Modify: `netismaker-interview-service/test/fixtures/sdkMessages.ts` (`usageAwareQuestionStream` 추가)
- Modify: `netismaker-interview-service/test/javaClient.test.ts:36-44` (postQuestion 리터럴에 필드 2개)
- Test: `netismaker-interview-service/test/messageRelay.test.ts`

**Interfaces:**
- Produces: `RelayResult.contextTokens: number | null`, `RelayResult.contextWindow: number | null`; `RelayOpts.onRateLimit?: (info: RateLimitInfo) => void`; export `contextTokensOf(usage: unknown): number | null`, `contextWindowOf(modelUsage: unknown): number | null`; `types.ts`의 `RateLimitInfo`, `WorkerRateLimitRequest`, `WorkerQuestionRequest.contextTokens/contextWindow: number | null`. Task 7이 소비.

- [ ] **Step 1: 타입 추가 — `src/types.ts`**

`WorkerQuestionRequest`의 `cacheReadTokens: number;` 아래에 추가:
```ts
  /** 마지막 최상위 assistant 메시지의 컨텍스트 토큰(input+cache_creation+cache_read). 없으면 null (스펙 2026-09-05 §4.2). */
  contextTokens: number | null;
  /** result.modelUsage 주 모델의 contextWindow. 없으면 null. */
  contextWindow: number | null;
```

파일 끝에 추가:
```ts
/**
 * SDK SDKRateLimitInfo 부분집합 (@anthropic-ai/claude-agent-sdk@0.2.117 sdk.d.ts:2923).
 * relay는 그대로 넘기고, 정규화는 runner/rateLimitReport.ts가 한다 (스펙 2026-09-05 §4.3).
 */
export interface RateLimitInfo {
  status?: 'allowed' | 'allowed_warning' | 'rejected';
  resetsAt?: number;
  rateLimitType?: 'five_hour' | 'seven_day' | 'seven_day_opus' | 'seven_day_sonnet' | 'overage';
  /** 구형(flat) 이벤트에만 존재. 실측(CLI 2.1.261)에서는 없고 unifiedWindows 안에 창별로 온다. */
  utilization?: number;
  isUsingOverage?: boolean;
  /**
   * sdk.d.ts 미선언 — 실측(test/fixtures/RATE_LIMIT_FINDINGS.md ①): 한 이벤트에 five_hour/seven_day 창이 동봉되고
   * 각 창의 utilization(0..1 분수)/resetsAt(epoch 초)은 여기에만 있다. 키는 rateLimitType 유니온과 같은 문자열.
   */
  unifiedWindows?: Record<string, { utilization?: number; resetsAt?: number } | undefined>;
}

/** Body for POST /worker/usage/rate-limits?workerId=… (Java WorkerRateLimitRequest). */
export interface WorkerRateLimitRequest {
  limitType: string;
  status: string;
  /** 0..1 분수 (정규화 완료). */
  utilization: number;
  /** ISO-8601 또는 null. */
  resetsAt: string | null;
  isUsingOverage: boolean;
}
```

`test/javaClient.test.ts`의 `postQuestion` 리터럴(`cacheReadTokens: 0,` 다음)에 `contextTokens: null, contextWindow: null,` 추가 — 타입 필수 필드라 `npm run build`가 여기서 깨진다.

- [ ] **Step 2: 픽스처 추가 — `test/fixtures/sdkMessages.ts` 끝에**

```ts
/**
 * 사용량 인지 턴 (스펙 2026-09-05 §4): rate_limit_event 1건(실측 shape — unifiedWindows에 five_hour/seven_day 동봉, 최상위 utilization 없음) + 최상위 message_start usage +
 * 최상위 assistant usage + 서브에이전트 assistant usage(컨텍스트 계산 제외) + result.modelUsage(주 모델 opus, 부 모델 haiku).
 * shape 근거: sdk.d.ts:2910 SDKRateLimitEvent / :2923 SDKRateLimitInfo / :1050 ModelUsage,
 * 실측: test/fixtures/RATE_LIMIT_FINDINGS.md. resetsAt은 epoch 초(1788580800 = 2026-09-05T04:00:00Z).
 */
export const usageAwareQuestionStream = (): AsyncIterable<SdkMessage> =>
  gen(
    { type: 'system', subtype: 'init', session_id: 'sess-usage-1' },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: {
        type: 'message_start',
        message: {
          usage: { input_tokens: 2, cache_creation_input_tokens: 30000, cache_read_input_tokens: 0, output_tokens: 1 },
        },
      },
    },
    {
      type: 'assistant',
      parent_tool_use_id: null,
      message: {
        content: [{ type: 'text', text: 'AuthController.login()이 처리합니다.' }],
        usage: { input_tokens: 4, cache_creation_input_tokens: 30000, cache_read_input_tokens: 46000, output_tokens: 120 },
      },
    },
    {
      type: 'assistant',
      parent_tool_use_id: 'tu-sub', // 서브에이전트 — 컨텍스트 계산에서 제외돼야 한다
      message: {
        content: [{ type: 'text', text: '(sub)' }],
        usage: { input_tokens: 999999, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
      },
    },
    {
      // 실측 shape(RATE_LIMIT_FINDINGS.md ①): 첫 턴 message_stop 직후 1건, 최상위 utilization 없음, 창별 값은 unifiedWindows.
      type: 'rate_limit_event',
      rate_limit_info: {
        status: 'allowed',
        resetsAt: 1788580800,
        rateLimitType: 'five_hour',
        overageStatus: 'rejected',
        isUsingOverage: false,
        unifiedWindows: {
          five_hour: { utilization: 0.42, resetsAt: 1788580800 },
          seven_day: { utilization: 0.63, resetsAt: 1788854400 },
        },
      },
      session_id: 'sess-usage-1',
    },
    {
      type: 'result',
      subtype: 'success',
      usage: {
        total_cost_usd: 0.05,
        input_tokens: 4,
        output_tokens: 120,
        cache_creation_input_tokens: 30000,
        cache_read_input_tokens: 46000,
      },
      modelUsage: {
        'claude-haiku-4-5': { inputTokens: 500, cacheReadInputTokens: 0, cacheCreationInputTokens: 0, outputTokens: 20, contextWindow: 100000 },
        'claude-opus-5': { inputTokens: 4, cacheReadInputTokens: 46000, cacheCreationInputTokens: 30000, outputTokens: 120, contextWindow: 200000 },
      },
      duration_ms: 1200,
    },
  );
```

- [ ] **Step 3: 실패 테스트 — `test/messageRelay.test.ts`에 추가** (import 갱신 포함)

```ts
import { contextTokensOf, contextWindowOf, relay, summarizeToolUse } from '../src/runner/messageRelay.js';
import { questionStream, streamingQuestionStream, usageAwareQuestionStream } from './fixtures/sdkMessages.js';
import type { ActivityInput, RateLimitInfo } from '../src/types.js';
import type { SdkMessage } from '../src/sdk/sdkAdapter.js';
```

```ts
describe('relay — 컨텍스트 스냅샷 + rate limit (스펙 2026-09-05 §4)', () => {
  it('마지막 최상위 assistant usage로 contextTokens, 주 모델의 contextWindow를 잡는다 (서브에이전트 usage 제외)', async () => {
    const out = await relay(usageAwareQuestionStream());
    expect(out.contextTokens).toBe(4 + 30000 + 46000);
    expect(out.contextWindow).toBe(200000); // haiku(100000)가 아니라 입력 토큰 합이 큰 opus
    expect(out.assistantText).toContain('AuthController');
    expect(out.costUsd).toBeCloseTo(0.05);
  });

  it('assistant usage가 없으면 최상위 message_start usage로 폴백한다', async () => {
    async function* stream(): AsyncIterable<SdkMessage> {
      yield { type: 'system', subtype: 'init', session_id: 's' };
      yield {
        type: 'stream_event',
        parent_tool_use_id: null,
        event: { type: 'message_start', message: { usage: { input_tokens: 2, cache_creation_input_tokens: 30000 } } },
      };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: '답' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0 }, duration_ms: 1 };
    }
    const out = await relay(stream());
    expect(out.contextTokens).toBe(30002);
    expect(out.contextWindow).toBeNull(); // modelUsage 없음
  });

  it('usage가 전혀 없는 구형 스트림은 둘 다 null (기존 픽스처 무변경)', async () => {
    const out = await relay(questionStream());
    expect(out.contextTokens).toBeNull();
    expect(out.contextWindow).toBeNull();
  });

  it('rate_limit_event의 rate_limit_info를 가공 없이 onRateLimit에 넘기고(unifiedWindows 포함), 콜백이 없으면 무시한다', async () => {
    const seen: RateLimitInfo[] = [];
    await relay(usageAwareQuestionStream(), { onRateLimit: (i) => seen.push(i) });
    expect(seen).toHaveLength(1);
    expect(seen[0]!.rateLimitType).toBe('five_hour');
    expect(seen[0]!.utilization).toBeUndefined(); // 실측: 최상위 utilization 없음 — 정규화(Task 7)가 unifiedWindows를 본다
    expect(seen[0]!.unifiedWindows?.seven_day?.utilization).toBe(0.63);
    await expect(relay(usageAwareQuestionStream())).resolves.toMatchObject({ completed: true });
  });
});

describe('contextTokensOf / contextWindowOf', () => {
  it('세 필드 합, 비숫자는 0으로, 세 필드가 전부 없으면 null', () => {
    expect(contextTokensOf({ input_tokens: 1, cache_creation_input_tokens: 2, cache_read_input_tokens: 3 })).toBe(6);
    expect(contextTokensOf({ input_tokens: 1, cache_read_input_tokens: 'x' })).toBe(1);
    expect(contextTokensOf({ output_tokens: 5 })).toBeNull();
    expect(contextTokensOf(undefined)).toBeNull();
  });

  it('입력 토큰 합이 최대인 모델의 contextWindow; contextWindow 없는 항목은 후보에서 제외', () => {
    expect(
      contextWindowOf({
        a: { inputTokens: 10, contextWindow: 100000 },
        b: { inputTokens: 5, cacheReadInputTokens: 50, contextWindow: 200000 },
        c: { inputTokens: 999 },
      }),
    ).toBe(200000);
    expect(contextWindowOf({})).toBeNull();
    expect(contextWindowOf('nope')).toBeNull();
  });
});
```

- [ ] **Step 4: 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/messageRelay.test.ts`
Expected: FAIL — `contextTokensOf`/`contextWindowOf` export 없음, `contextTokens` undefined.

- [ ] **Step 5: `src/runner/messageRelay.ts` 수정**

import와 인터페이스:
```ts
import type { SdkMessage } from '../sdk/sdkAdapter.js';
import type { ActivityInput, RateLimitInfo } from '../types.js';

export interface RelayResult {
  sessionId: string | null;
  assistantText: string;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  durationMs: number;
  completed: boolean;
  /** 마지막 최상위 assistant 메시지(없으면 message_start) usage의 input+cache_creation+cache_read. 없으면 null. */
  contextTokens: number | null;
  /** result.modelUsage 중 입력 토큰 합이 최대인 모델의 contextWindow. 없으면 null. */
  contextWindow: number | null;
}

export interface RelayOpts {
  /** 활동(도구/텍스트 델타/thinking 델타) 실시간 콜백 — 미전달 시 기존과 100% 동일 동작. */
  onActivity?: (e: ActivityInput) => void;
  /** tool detail의 workDir prefix 상대화용. */
  workDir?: string;
  /** rate_limit_event(구독 한도 스냅샷) 콜백 — 미전달 시 무시. 정규화/보고는 호출자(RateLimitReporter) 책임. */
  onRateLimit?: (info: RateLimitInfo) => void;
}
```

`summarizeToolUse` 아래(기존 `type ContentBlock` 위)에 추가:
```ts
type UsageShape = {
  input_tokens?: unknown;
  cache_creation_input_tokens?: unknown;
  cache_read_input_tokens?: unknown;
};

const CONTEXT_KEYS = ['input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens'] as const;

/** usage → 컨텍스트 토큰(input+cache_creation+cache_read). 세 필드 중 숫자가 하나도 없으면 null. */
export function contextTokensOf(usage: unknown): number | null {
  if (!usage || typeof usage !== 'object') return null;
  const u = usage as UsageShape;
  let sum = 0;
  let seen = false;
  for (const k of CONTEXT_KEYS) {
    const v = u[k];
    if (typeof v === 'number' && Number.isFinite(v)) {
      sum += v;
      seen = true;
    }
  }
  return seen ? sum : null;
}

/** result.modelUsage(Record<model, ModelUsage>)에서 주 모델(입력 토큰 합 최대)의 contextWindow. 없으면 null. */
export function contextWindowOf(modelUsage: unknown): number | null {
  if (!modelUsage || typeof modelUsage !== 'object') return null;
  const n = (x: unknown) => (typeof x === 'number' && Number.isFinite(x) ? x : 0);
  let best: { total: number; window: number } | null = null;
  for (const v of Object.values(modelUsage as Record<string, unknown>)) {
    if (!v || typeof v !== 'object') continue;
    const m = v as {
      inputTokens?: unknown;
      cacheReadInputTokens?: unknown;
      cacheCreationInputTokens?: unknown;
      contextWindow?: unknown;
    };
    if (typeof m.contextWindow !== 'number' || !(m.contextWindow > 0)) continue;
    const total = n(m.inputTokens) + n(m.cacheReadInputTokens) + n(m.cacheCreationInputTokens);
    if (!best || total > best.total) best = { total, window: m.contextWindow };
  }
  return best ? best.window : null;
}

/** parent_tool_use_id가 있으면 서브에이전트 스트림 — 최상위 컨텍스트가 아니다. */
function isTopLevel(msg: SdkMessage): boolean {
  return !(msg as { parent_tool_use_id?: string | null }).parent_tool_use_id;
}
```

`relay()` 본문 — 지역 변수 3개 추가, 루프 분기 수정, 반환 확장:
```ts
export async function relay(stream: AsyncIterable<SdkMessage>, opts?: RelayOpts): Promise<RelayResult> {
  let sessionId: string | null = null;
  const parts: string[] = [];
  let costUsd = 0;
  let inputTokens = 0;
  let outputTokens = 0;
  let cacheCreationTokens = 0;
  let cacheReadTokens = 0;
  let durationMs = 0;
  let completed = false;
  let assistantContext: number | null = null;
  let startContext: number | null = null;
  let contextWindow: number | null = null;
  for await (const msg of stream) {
    if (msg.type === 'system' && msg.subtype === 'init') {
      sessionId = (msg.session_id as string) ?? null;
    } else if (msg.type === 'rate_limit_event') {
      // 구독 한도 스냅샷 — 보고 여부/정규화는 콜백 소유자가 결정한다.
      const info = msg.rate_limit_info;
      if (opts?.onRateLimit && info && typeof info === 'object') opts.onRateLimit(info as RateLimitInfo);
    } else if (msg.type === 'stream_event') {
      // message_start usage = 이 API 호출의 요청 컨텍스트(출력 전). assistant usage가 없을 때의 폴백.
      const ev = msg.event as { type?: string; message?: { usage?: unknown } } | undefined;
      if (ev?.type === 'message_start' && isTopLevel(msg)) {
        const ct = contextTokensOf(ev.message?.usage);
        if (ct !== null) startContext = ct;
      }
      if (!opts?.onActivity) continue;
      const activity = deltaActivity(msg);
      if (activity) opts.onActivity(activity);
    } else if (msg.type === 'assistant') {
      if (isTopLevel(msg)) {
        const ct = contextTokensOf((msg.message as { usage?: unknown } | undefined)?.usage);
        if (ct !== null) assistantContext = ct;
      }
      const t = extractText(msg);
      if (t) parts.push(t);
      if (opts?.onActivity) {
        for (const b of blocks(msg)) {
          if (b.type === 'tool_use' && typeof b.name === 'string') {
            opts.onActivity({
              type: 'tool',
              label: b.name,
              detail: summarizeToolUse(b.name, b.input, opts.workDir),
            });
          }
        }
      }
    } else if (msg.type === 'result') {
      const usage = msg.usage as
        | {
            total_cost_usd?: number;
            input_tokens?: number;
            output_tokens?: number;
            cache_creation_input_tokens?: number;
            cache_read_input_tokens?: number;
          }
        | undefined;
      costUsd = usage?.total_cost_usd ?? 0;
      inputTokens = usage?.input_tokens ?? 0;
      outputTokens = usage?.output_tokens ?? 0;
      cacheCreationTokens = usage?.cache_creation_input_tokens ?? 0;
      cacheReadTokens = usage?.cache_read_input_tokens ?? 0;
      durationMs = (msg.duration_ms as number) ?? 0;
      contextWindow = contextWindowOf(msg.modelUsage);
      completed = true;
    }
  }
  return {
    sessionId,
    assistantText: parts.join('\n\n'),
    costUsd,
    inputTokens,
    outputTokens,
    cacheCreationTokens,
    cacheReadTokens,
    durationMs,
    completed,
    contextTokens: assistantContext ?? startContext,
    contextWindow,
  };
}
```
(`deltaActivity`의 기존 `parent_tool_use_id` 검사는 그대로 둔다 — 동작 불변.)

- [ ] **Step 6: 통과 + 타입체크**

Run: `cd netismaker-interview-service && npx vitest run test/messageRelay.test.ts && npm run build`
Expected: PASS · `tsc` 오류 없음 (Step 1의 javaClient.test 리터럴 갱신 덕).

- [ ] **Step 7: 커밋**

```bash
git add netismaker-interview-service/src/types.ts netismaker-interview-service/src/runner/messageRelay.ts netismaker-interview-service/test/fixtures/sdkMessages.ts netismaker-interview-service/test/messageRelay.test.ts netismaker-interview-service/test/javaClient.test.ts
git commit -m "feat(interview-service): relay가 컨텍스트 스냅샷·rate_limit_event를 추출

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: interview-service — 정규화·보고 배선 (`RateLimitReporter`, `postRateLimit`, 러너)

> **실측 보정(Task 1, 2026-09-05 — `netismaker-interview-service/test/fixtures/RATE_LIMIT_FINDINGS.md`):** 실제 `rate_limit_info`에는 최상위 `utilization`이 없고, 타입 선언에 없는 `unifiedWindows: { five_hour: {utilization, resetsAt}, seven_day: {…} }`에 창별 값이 **한 이벤트에 동봉**된다(세션당 1건 이상, 턴당 아님). 따라서 정규화는 이벤트 1건을 **창마다 1건의 `WorkerRateLimitRequest`로 펼친다**(`toRateLimitRequests` → 배열). 규칙: `unifiedWindows`의 알려진 타입 키만 채택(미지 키 무시), 주 창(`rateLimitType`)이 `unifiedWindows`에 없으면 최상위 `utilization`/`resetsAt`으로 보충(구형 flat 이벤트 호환), `status`는 주 창만 최상위 값(없으면 `allowed`)이고 나머지 창은 `allowed`, `isUsingOverage`는 계정 단위라 전 행 공통(`=== true`), 출력 순서는 `five_hour, seven_day, seven_day_opus, seven_day_sonnet, overage` 고정. 단위 정규화(분수/퍼센트, 초/ms)는 스펙 §4.3 그대로. Java(Task 5)·프론트(Task 9)는 타입별 1행 upsert 계약 그대로라 영향 없다.

**Files:**
- Create: `netismaker-interview-service/src/runner/rateLimitReport.ts`
- Modify: `netismaker-interview-service/src/api/javaClient.ts` (`postRateLimit`)
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts` (필드·콜백·postQuestion 필드·flush)
- Test: Create `netismaker-interview-service/test/rateLimitReport.test.ts`; Modify `test/javaClient.test.ts`, `test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: Task 6의 `RelayOpts.onRateLimit`, `RelayResult.contextTokens/contextWindow`, `types.ts` 타입들; Task 5의 `POST /worker/usage/rate-limits`.
- Produces: `toRateLimitRequests(info): WorkerRateLimitRequest[]`(이벤트 1건 → 창별 0..N건; 실측 `unifiedWindows` 펼침 + 구형 flat 폴백), `class RateLimitReporter { report(info): void; flush(): Promise<void> }`, `JavaApiClient.postRateLimit(body): Promise<void>` (non-2xx → `HttpStatusError`). 러너는 인터뷰·질문 두 경로의 `postQuestion`에 `contextTokens`/`contextWindow`를 싣는다.

- [ ] **Step 1: 실패 테스트 — `test/rateLimitReport.test.ts`**

```ts
import { describe, expect, it, vi } from 'vitest';
import { RateLimitReporter, toRateLimitRequests } from '../src/runner/rateLimitReport.js';
import { HttpStatusError } from '../src/api/javaClient.js';
import type { RateLimitInfo } from '../src/types.js';

describe('toRateLimitRequests (스펙 2026-09-05 §4.3 + 실측 보정 RATE_LIMIT_FINDINGS.md)', () => {
  it('실측 shape: unifiedWindows 창마다 1건, 최상위 utilization 없음 → five_hour/seven_day 2건 (캡처 원문)', () => {
    const real = {
      status: 'allowed',
      resetsAt: 1788616200,
      rateLimitType: 'five_hour',
      overageStatus: 'rejected',
      overageDisabledReason: 'org_level_disabled',
      isUsingOverage: false,
      unifiedWindows: {
        five_hour: { utilization: 0.05, resetsAt: 1788616200 },
        seven_day: { utilization: 0.12, resetsAt: 1788706800 },
      },
    } as RateLimitInfo;
    expect(toRateLimitRequests(real)).toEqual([
      { limitType: 'five_hour', status: 'allowed', utilization: 0.05, resetsAt: '2026-09-05T13:50:00.000Z', isUsingOverage: false },
      { limitType: 'seven_day', status: 'allowed', utilization: 0.12, resetsAt: '2026-09-06T15:00:00.000Z', isUsingOverage: false },
    ]);
  });

  it('주 창만 최상위 status를 받고 나머지 창은 allowed; 미지 키는 버린다; 순서는 고정', () => {
    const out = toRateLimitRequests({
      status: 'allowed_warning',
      rateLimitType: 'seven_day',
      unifiedWindows: { weird: { utilization: 0.5 }, seven_day: { utilization: 0.8 }, five_hour: { utilization: 0.1 } },
    } as RateLimitInfo);
    expect(out.map((r) => [r.limitType, r.status, r.utilization])).toEqual([
      ['five_hour', 'allowed', 0.1],
      ['seven_day', 'allowed_warning', 0.8],
    ]);
  });

  it('구형(flat) 이벤트: 분수 utilization + epoch 초 resetsAt → 1건, ISO', () => {
    expect(toRateLimitRequests({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.42, resetsAt: 1788580800 })).toEqual([
      { limitType: 'five_hour', status: 'allowed', utilization: 0.42, resetsAt: '2026-09-05T04:00:00.000Z', isUsingOverage: false },
    ]);
  });

  it('unifiedWindows에 주 창이 빠졌으면 최상위 utilization/resetsAt으로 주 창을 보충한다', () => {
    const out = toRateLimitRequests({
      status: 'allowed',
      rateLimitType: 'five_hour',
      utilization: 0.3,
      resetsAt: 1788580800,
      unifiedWindows: { seven_day: { utilization: 0.6, resetsAt: 1788854400 } },
    });
    expect(out.map((r) => [r.limitType, r.utilization, r.resetsAt])).toEqual([
      ['five_hour', 0.3, '2026-09-05T04:00:00.000Z'],
      ['seven_day', 0.6, '2026-09-08T08:00:00.000Z'],
    ]);
  });

  it('퍼센트(1 초과)는 /100, 범위 밖은 0..1 클램프, ms resetsAt은 그대로 ISO, 소수 4자리', () => {
    const [pct] = toRateLimitRequests({ status: 'allowed', rateLimitType: 'seven_day', utilization: 63, resetsAt: 1788854400000 });
    expect(pct!.utilization).toBe(0.63);
    expect(pct!.resetsAt).toBe('2026-09-08T08:00:00.000Z');
    expect(toRateLimitRequests({ status: 'rejected', rateLimitType: 'seven_day', utilization: 250 })[0]!.utilization).toBe(1);
    expect(toRateLimitRequests({ status: 'allowed', rateLimitType: 'seven_day', utilization: -3 })[0]!.utilization).toBe(0);
    expect(toRateLimitRequests({ rateLimitType: 'five_hour', unifiedWindows: { five_hour: { utilization: 0.123456 } } })[0]!.utilization).toBe(0.1235);
  });

  it('rateLimitType 없음/미지·비객체·창 없음 → []; utilization 없음 → 0; status 없음 → allowed; overage 플래그는 전 행 공통', () => {
    expect(toRateLimitRequests({ status: 'allowed' })).toEqual([]);
    expect(toRateLimitRequests({ status: 'allowed', rateLimitType: 'weird' as never })).toEqual([]);
    expect(toRateLimitRequests(null)).toEqual([]);
    expect(toRateLimitRequests({ rateLimitType: 'weird' as never, unifiedWindows: { weird: { utilization: 0.5 } } })).toEqual([]);
    expect(toRateLimitRequests({ rateLimitType: 'overage', isUsingOverage: true })).toEqual([
      { limitType: 'overage', status: 'allowed', utilization: 0, resetsAt: null, isUsingOverage: true },
    ]);
    expect(
      toRateLimitRequests({ rateLimitType: 'five_hour', isUsingOverage: true, unifiedWindows: { five_hour: {}, seven_day: {} } }).map((r) => r.isUsingOverage),
    ).toEqual([true, true]);
  });
});

describe('RateLimitReporter', () => {
  it('정규화된 바디를 순서대로 POST하고 flush()로 완료를 기다린다 (type 없는 이벤트는 무시)', async () => {
    const postRateLimit = vi.fn().mockResolvedValue(undefined);
    const r = new RateLimitReporter({ postRateLimit });
    r.report({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.1 });
    r.report({ status: 'allowed' });
    r.report({ status: 'allowed', rateLimitType: 'seven_day', utilization: 0.2 });
    await r.flush();
    expect(postRateLimit).toHaveBeenCalledTimes(2);
    expect(postRateLimit.mock.calls[0]![0].limitType).toBe('five_hour');
    expect(postRateLimit.mock.calls[1]![0].limitType).toBe('seven_day');
  });

  it('실측 shape 이벤트 1건은 창별로 펼쳐 five_hour → seven_day 순으로 2건 POST한다', async () => {
    const postRateLimit = vi.fn().mockResolvedValue(undefined);
    const r = new RateLimitReporter({ postRateLimit });
    r.report({
      status: 'allowed',
      rateLimitType: 'five_hour',
      resetsAt: 1788580800,
      unifiedWindows: { five_hour: { utilization: 0.42, resetsAt: 1788580800 }, seven_day: { utilization: 0.63, resetsAt: 1788854400 } },
    });
    await r.flush();
    expect(postRateLimit.mock.calls.map((c) => [c[0].limitType, c[0].utilization])).toEqual([
      ['five_hour', 0.42],
      ['seven_day', 0.63],
    ]);
  });

  it('404(구버전 API)면 이후 보고를 비활성화하고 경고는 1회만 남긴다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const postRateLimit = vi.fn().mockRejectedValue(new HttpStatusError(404, 'rate-limit failed: 404'));
    const r = new RateLimitReporter({ postRateLimit });
    r.report({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.1 });
    await r.flush();
    r.report({ status: 'allowed', rateLimitType: 'seven_day', utilization: 0.2 });
    await r.flush();
    expect(postRateLimit).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('일시 오류(500)는 경고 1회 후에도 다음 보고를 계속 시도한다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const postRateLimit = vi
      .fn()
      .mockRejectedValueOnce(new HttpStatusError(500, 'rate-limit failed: 500'))
      .mockResolvedValue(undefined);
    const r = new RateLimitReporter({ postRateLimit });
    r.report({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.1 });
    r.report({ status: 'allowed', rateLimitType: 'seven_day', utilization: 0.2 });
    await r.flush();
    expect(postRateLimit).toHaveBeenCalledTimes(2);
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});
```

`test/javaClient.test.ts`에 추가:
```ts
  it('postRateLimit POSTs to /worker/usage/rate-limits?workerId=… and throws HttpStatusError on non-2xx', async () => {
    const ok = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const body = {
      limitType: 'five_hour',
      status: 'allowed',
      utilization: 0.42,
      resetsAt: '2026-09-05T04:00:00.000Z',
      isUsingOverage: false,
    };
    await new JavaApiClient(cfg, ok).postRateLimit(body);
    const [url, init] = ok.mock.calls[0]!;
    expect(url).toBe('http://api:8090/worker/usage/rate-limits?workerId=iw-1');
    expect(init.method).toBe('POST');
    expect(init.headers['X-Worker-API-Key']).toBe('KEY');
    expect(JSON.parse(init.body)).toEqual(body);

    const notFound = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    await expect(new JavaApiClient(cfg, notFound).postRateLimit(body)).rejects.toBeInstanceOf(HttpStatusError);
  });
```

`test/interviewRunner.test.ts` — `makeClient()`에 `postRateLimit: vi.fn().mockResolvedValue(undefined),` 추가, import에 `usageAwareQuestionStream` 추가, 테스트 2개 추가:
```ts
  it('usage-aware question turn: reports rate limits in order and posts the context snapshot with the answer', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => usageAwareQuestionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);

    await runner.run(questionClaim);

    expect(client.postRateLimit).toHaveBeenCalledTimes(2);
    expect(client.postRateLimit).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ limitType: 'five_hour', utilization: 0.42, resetsAt: '2026-09-05T04:00:00.000Z' }),
    );
    expect(client.postRateLimit).toHaveBeenNthCalledWith(2, expect.objectContaining({ limitType: 'seven_day', utilization: 0.63 }));
    expect(client.postQuestion).toHaveBeenCalledWith(
      42,
      expect.objectContaining({ contextTokens: 76004, contextWindow: 200000, claudeSessionId: 'sess-usage-1' }),
    );
    expect(client.fail).not.toHaveBeenCalled();
  });

  it('interview turn also carries the context snapshot; legacy streams send null', async () => {
    const client = makeClient();
    const runner = new InterviewRunner(client as never, vi.fn(() => usageAwareQuestionStream()) as never, deps as never);
    await runner.run(freshClaim);
    expect(client.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ contextTokens: 76004, contextWindow: 200000 }));

    const legacy = makeClient();
    const runner2 = new InterviewRunner(legacy as never, vi.fn(() => questionStream()) as never, deps as never);
    await runner2.run(freshClaim);
    expect(legacy.postQuestion).toHaveBeenCalledWith(42, expect.objectContaining({ contextTokens: null, contextWindow: null }));
    expect(legacy.postRateLimit).not.toHaveBeenCalled();
  });
```

- [ ] **Step 2: 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/rateLimitReport.test.ts test/javaClient.test.ts test/interviewRunner.test.ts`
Expected: FAIL — 모듈 없음 / `postRateLimit is not a function` / `contextTokens` 미포함.

- [ ] **Step 3: `src/runner/rateLimitReport.ts` 작성**

```ts
import { HttpStatusError, type JavaApiClient } from '../api/javaClient.js';
import type { RateLimitInfo, WorkerRateLimitRequest } from '../types.js';

/** 보고 대상 창. 순서 = 프론트 LIMIT_ORDER(+overage) — 펼침 결과의 출력 순서이기도 하다. */
const LIMIT_TYPES = ['five_hour', 'seven_day', 'seven_day_opus', 'seven_day_sonnet', 'overage'] as const;
type LimitType = (typeof LIMIT_TYPES)[number];

const isLimitType = (v: unknown): v is LimitType => typeof v === 'string' && (LIMIT_TYPES as readonly string[]).includes(v);

/** 0..1 분수. 비숫자 → 0. 1 초과면 퍼센트로 간주해 /100. 0..1 클램프, 소수 4자리. */
function normalizeUtilization(v: unknown): number {
  let util = typeof v === 'number' && Number.isFinite(v) ? v : 0;
  if (util > 1) util = util / 100;
  util = Math.min(1, Math.max(0, util));
  return Number(util.toFixed(4));
}

/** 양수만. 1e12 미만이면 epoch 초 → ms. ISO-8601. 없으면 null. */
function normalizeResetsAt(v: unknown): string | null {
  if (typeof v !== 'number' || !Number.isFinite(v) || v <= 0) return null;
  return new Date(v < 1e12 ? v * 1000 : v).toISOString();
}

interface WindowSnapshot {
  utilization?: unknown;
  resetsAt?: unknown;
}

/**
 * SDKRateLimitInfo → Java WorkerRateLimitRequest[] 정규화 (스펙 2026-09-05 §4.3 + 실측 보정 RATE_LIMIT_FINDINGS.md).
 * 실측(CLI 2.1.261): 최상위 utilization은 없고, sdk.d.ts 미선언 unifiedWindows에 창별 {utilization, resetsAt}이
 * 한 이벤트에 동봉된다 → 창마다 1건으로 펼친다.
 * - unifiedWindows의 알려진 타입 키만 채택(미지 키 무시). 주 창(rateLimitType)이 빠져 있으면 최상위 utilization/resetsAt으로
 *   보충한다 — 구형(flat) 이벤트는 이 경로로 1건이 된다.
 * - status: 주 창만 최상위 값(없으면 allowed), 나머지 창은 allowed(창별 status는 이벤트에 없다).
 * - isUsingOverage: 계정 단위라 전 행 공통(=== true만 true).
 * - 출력 순서는 LIMIT_TYPES 고정. rateLimitType 없음/미지 + 창 없음 → [] (보고 안 함).
 */
export function toRateLimitRequests(info: RateLimitInfo | null | undefined): WorkerRateLimitRequest[] {
  if (!info || typeof info !== 'object') return [];
  const primary = info.rateLimitType;
  const windows = new Map<LimitType, WindowSnapshot>();
  const uw = info.unifiedWindows;
  if (uw && typeof uw === 'object') {
    for (const [type, w] of Object.entries(uw)) {
      if (isLimitType(type) && w && typeof w === 'object') windows.set(type, w as WindowSnapshot);
    }
  }
  if (isLimitType(primary) && !windows.has(primary)) {
    windows.set(primary, { utilization: info.utilization, resetsAt: info.resetsAt });
  }
  return LIMIT_TYPES.filter((t) => windows.has(t)).map((type) => {
    const w = windows.get(type)!;
    return {
      limitType: type,
      status: type === primary ? info.status || 'allowed' : 'allowed',
      utilization: normalizeUtilization(w.utilization),
      resetsAt: normalizeResetsAt(w.resetsAt ?? (type === primary ? info.resetsAt : undefined)),
      isUsingOverage: info.isUsingOverage === true,
    };
  });
}

/**
 * rate_limit_event → POST /worker/usage/rate-limits (창마다 1건). 인터뷰 서비스 수명 동안 1개(러너 필드).
 * - 직렬 체이닝(순서 보장). 실패는 경고 1회 후 계속. 404(구버전 Java)면 서비스 수명 동안 비활성(ActivityPoster 선례).
 * - 어떤 경로로도 throw하지 않는다 — 사용량 보고가 인터뷰/답변을 죽이면 안 된다.
 */
export class RateLimitReporter {
  private disabled = false;
  private warned = false;
  private chain: Promise<void> = Promise.resolve();

  constructor(private readonly client: Pick<JavaApiClient, 'postRateLimit'>) {}

  report(info: RateLimitInfo): void {
    if (this.disabled) return;
    for (const body of toRateLimitRequests(info)) this.enqueue(body);
  }

  private enqueue(body: WorkerRateLimitRequest): void {
    this.chain = this.chain
      .then(() => (this.disabled ? undefined : this.client.postRateLimit(body)))
      .catch((err: unknown) => {
        if (err instanceof HttpStatusError && err.status === 404) this.disabled = true;
        if (!this.warned) {
          this.warned = true;
          // eslint-disable-next-line no-console
          console.warn(
            `[rate-limit] 사용량 보고 실패${this.disabled ? ' (404 — 구버전 API, 이후 비활성)' : ''}: ${(err as Error).message}`,
          );
        }
      });
  }

  /** 대기 중인 보고를 모두 끝낸다 — 턴 종료 시 await (테스트 결정성 + 종료 전 flush). */
  flush(): Promise<void> {
    return this.chain;
  }
}
```

- [ ] **Step 4: `src/api/javaClient.ts` — import에 `WorkerRateLimitRequest` 추가, `heartbeat` 위에 메서드 추가**

```ts
  /** 구독 한도 스냅샷 보고 (스펙 2026-09-05 §4.1). 실패 처리(비활성/경고)는 RateLimitReporter 책임 — 404 식별용 HttpStatusError. */
  async postRateLimit(body: WorkerRateLimitRequest): Promise<void> {
    const res = await this.fetchFn(this.url(`/worker/usage/rate-limits?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new HttpStatusError(res.status, `rate-limit failed: ${res.status}`);
  }
```

- [ ] **Step 5: `src/runner/interviewRunner.ts` 배선**

(a) import 2곳:
```ts
import type { ActivityInput, AttachmentRef, InterviewClaimResponse, RateLimitInfo } from '../types.js';
import { RateLimitReporter } from './rateLimitReport.js';
```

(b) 클래스 필드/생성자:
```ts
export class InterviewRunner {
  private readonly ensureRepo: (input: RepoInput) => Promise<void>;
  /** 구독 한도 보고기 — 서비스 수명 동안 1개(404 비활성 상태가 세션을 넘어 유지된다). */
  private readonly rateLimits: RateLimitReporter;
  constructor(
    private readonly client: JavaApiClient,
    private readonly query: SdkQuery,
    private readonly deps: RunnerDeps,
  ) {
    this.ensureRepo = deps.ensureRepo ?? defaultEnsureRepo;
    this.rateLimits = new RateLimitReporter(client);
  }
```

(c) `run()`의 `finally` — `await poster.stop();` 다음 줄에:
```ts
      await this.rateLimits.flush(); // 보고 큐 드레인 — fail/success 어느 경로든 턴 밖으로 새지 않게
```

(d) `runTurn()` — `const onActivity = …` 바로 아래에 콜백 정의, 세 `relay(...)` 호출의 옵션을 `{ onActivity, onRateLimit, workDir: claim.workDir }`로 교체:
```ts
    const onActivity = (e: ActivityInput) => poster.push(e);
    const onRateLimit = (info: RateLimitInfo) => this.rateLimits.report(info);
```
첫 relay 직후(`let durationMs = result.durationMs;` 다음):
```ts
      // 컨텍스트 스냅샷은 "마지막 relay" 값 — 뒤이은 handoff/reformat relay가 있으면 그 값으로 갱신(null이면 유지).
      let contextTokens = result.contextTokens;
      let contextWindow = result.contextWindow;
```
handoff shim 블록의 `durationMs = second.durationMs;` 다음:
```ts
        contextTokens = second.contextTokens ?? contextTokens;
        contextWindow = second.contextWindow ?? contextWindow;
```
near-miss retry 블록의 `durationMs = retry.durationMs;` 다음:
```ts
        contextTokens = retry.contextTokens ?? contextTokens;
        contextWindow = retry.contextWindow ?? contextWindow;
```
마지막 `postQuestion` 바디의 `cacheReadTokens,` 다음:
```ts
        contextTokens,
        contextWindow,
```
(`postPlan`은 세션을 끝내므로 컨텍스트를 싣지 않는다.)

(e) `runQuestionTurn()` — 전체 교체:
```ts
  private async runQuestionTurn(
    claim: InterviewClaimResponse,
    controller: AbortController,
    guard: CostGuard,
    poster: ActivityPoster,
  ): Promise<void> {
    const onActivity = (e: ActivityInput) => poster.push(e);
    const onRateLimit = (info: RateLimitInfo) => this.rateLimits.report(info);
    const stream: AsyncIterable<SdkMessage> = this.query({
      prompt: questionPromptFor(claim),
      options: buildOptions({
        superpowersPluginPath: this.deps.superpowersPluginPath,
        workDir: claim.workDir,
        claudeCliPath: this.deps.claudeCliPath,
        claudeSessionId: claim.claudeSessionId,
        mcpsExtra: claim.mcpsExtra,
        mcpsBase: this.deps.mcpsBase,
        model: claim.model,
        effort: claim.effort,
        abortController: controller,
        sessionKind: 'QUESTION',
      }),
    });
    const result = await relay(stream, { onActivity, onRateLimit, workDir: claim.workDir });
    guard.add(result.costUsd);
    // trailing 활동 배치가 답변보다 늦게 도착하지 않도록 확정 POST 전에 큐를 비운다 (인터뷰 경로와 동일).
    await poster.stop();
    await this.client.postQuestion(claim.sessionId, {
      content: result.assistantText,
      claudeSessionId: result.sessionId ?? claim.claudeSessionId ?? '',
      kind: 'question',
      costUsd: result.costUsd,
      inputTokens: result.inputTokens,
      outputTokens: result.outputTokens,
      cacheCreationTokens: result.cacheCreationTokens,
      cacheReadTokens: result.cacheReadTokens,
      // 컨텍스트 스냅샷 (스펙 2026-09-05 §4.2) — 구버전 Java는 미지 필드를 무시한다.
      contextTokens: result.contextTokens,
      contextWindow: result.contextWindow,
    });
  }
```

- [ ] **Step 6: 통과 + 전체 검증**

Run: `cd netismaker-interview-service && npm test && npm run build`
Expected: 전부 PASS, tsc 오류 없음.

- [ ] **Step 7: 커밋**

```bash
git add netismaker-interview-service/src/runner/rateLimitReport.ts netismaker-interview-service/src/api/javaClient.ts netismaker-interview-service/src/runner/interviewRunner.ts netismaker-interview-service/test/rateLimitReport.test.ts netismaker-interview-service/test/javaClient.test.ts netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat(interview-service): 구독 한도 보고(RateLimitReporter)·컨텍스트 스냅샷을 /question에 동봉

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: frontend — assistant 말풍선 마크다운 렌더링

**Files:**
- Modify: `frontend/package.json` (npm install로 자동 갱신)
- Create: `frontend/composables/useMarkdown.ts`, `frontend/composables/useMarkdown.spec.ts`
- Modify: `frontend/components/chat/ChatBubble.vue`
- Test: `frontend/components/chat/ChatBubble.spec.ts`

**Interfaces:**
- Produces: `renderMarkdown(src: string | null | undefined): string` (안전한 HTML 문자열). `ChatBubble` assistant 말풍선은 `.bubble.assistant.md`에 `v-html`. 사용자/시스템 말풍선 마크업 불변 → `InterviewPanel.spec.ts`의 기존 텍스트 단언 유지.

- [ ] **Step 1: 의존성 설치**

Run: `cd frontend && npm install markdown-it@^14 && npm install -D @types/markdown-it@^14`
Expected: `package.json` dependencies에 `markdown-it`, devDependencies에 `@types/markdown-it`.

- [ ] **Step 2: 실패 테스트 — `composables/useMarkdown.spec.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { renderMarkdown } from './useMarkdown'

describe('renderMarkdown (스펙 2026-09-05 §2 답변 렌더링)', () => {
  it('굵게·인라인 코드·목록을 HTML로 렌더한다', () => {
    const html = renderMarkdown('**1. 로그인** `AuthController.login()`\n\n- 항목')
    expect(html).toContain('<strong>1. 로그인</strong>')
    expect(html).toContain('<code>AuthController.login()</code>')
    expect(html).toContain('<li>항목</li>')
  })

  it('원시 HTML은 렌더하지 않고 이스케이프한다 (html:false)', () => {
    const html = renderMarkdown('<script>alert(1)</script> <img src=x onerror=alert(1)>')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
    expect(html).not.toContain('<img')
  })

  it('javascript: 링크는 버리고, 정상 링크는 새 탭 + noopener', () => {
    expect(renderMarkdown('[x](javascript:alert(1))')).not.toContain('href="javascript:')
    const html = renderMarkdown('[문서](https://example.com/a)')
    expect(html).toContain('href="https://example.com/a"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('한 줄 개행은 <br>, 빈 입력은 빈 문자열', () => {
    expect(renderMarkdown('첫 줄\n둘째 줄')).toContain('<br>')
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
  })
})
```

`components/chat/ChatBubble.spec.ts`에 추가:
```ts
  it('assistant 말풍선은 마크다운을 렌더하고 원시 HTML은 이스케이프한다 (스펙 2026-09-05 §2)', () => {
    const w = mount(ChatBubble, {
      props: { role: 'assistant', content: '**요약** `AuthController`\n\n<script>x</script>' },
    })
    expect(w.find('.bubble.assistant strong').text()).toBe('요약')
    expect(w.find('.bubble.assistant code').text()).toBe('AuthController')
    expect(w.html()).not.toContain('<script>')
    expect(w.text()).toContain('<script>x</script>')
  })

  it('user 말풍선은 마크다운을 해석하지 않고 평문으로 보여준다', () => {
    const w = mount(ChatBubble, { props: { role: 'user', content: '**굵게** 아님' } })
    expect(w.find('.bubble.user').text()).toBe('**굵게** 아님')
    expect(w.find('.bubble.user strong').exists()).toBe(false)
  })
```

- [ ] **Step 3: 실패 확인**

Run: `cd frontend && npx vitest run composables/useMarkdown.spec.ts components/chat/ChatBubble.spec.ts`
Expected: FAIL — 모듈 없음 / `<strong>` 없음.

- [ ] **Step 4: `composables/useMarkdown.ts` 작성**

```ts
import MarkdownIt from 'markdown-it'

// 답변 마크다운 렌더러 (스펙 2026-09-05 §2): html:false = 원시 HTML 미렌더(XSS 차단), linkify:false,
// breaks:true = 채팅 줄바꿈. javascript:/vbscript:/file: 링크는 markdown-it 기본 validateLink가 거른다.
// 모듈 스코프 싱글턴 — SSR/클라이언트 양쪽에서 순수 문자열 변환만 한다(DOM 불필요).
const md = new MarkdownIt({ html: false, linkify: false, breaks: true })

// 링크는 새 탭 + noopener — SPA 세션을 떠나지 않게.
const renderLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx]!.attrSet('target', '_blank')
  tokens[idx]!.attrSet('rel', 'noopener noreferrer')
  return renderLinkOpen(tokens, idx, options, env, self)
}

/** 마크다운 → HTML 문자열. assistant 말풍선 전용(v-html). 빈 입력은 빈 문자열. */
export function renderMarkdown(src: string | null | undefined): string {
  if (!src) return ''
  return md.render(src)
}
```

- [ ] **Step 5: `components/chat/ChatBubble.vue` 수정** (전체 파일)

```vue
<script setup lang="ts">
// 대화 턴 1개를 말풍선으로. 순수 프레젠테이션, 내부 상태 없음.
// assistant → 좌측(회색 말풍선 + AI 아바타, 마크다운 렌더), user → 우측(파란 말풍선 + 나 아바타, 평문),
// system → 가운데 노트 칩.
import { renderMarkdown } from '~/composables/useMarkdown'

const props = defineProps<{
  role: 'assistant' | 'user' | 'system'
  content: string
}>()

const avatarLabel = computed(() =>
  props.role === 'assistant' ? 'AI' : props.role === 'user' ? '나' : '',
)
// assistant만 마크다운 — 사용자 입력은 평문 pre-wrap 유지 (스펙 2026-09-05 §2).
const html = computed(() => (props.role === 'assistant' ? renderMarkdown(props.content) : ''))
</script>

<template>
  <div v-if="role === 'system'" class="bubble-row system">
    <span class="system-note">{{ content }}</span>
  </div>
  <div v-else class="bubble-row" :class="role">
    <div class="avatar" :class="role">{{ avatarLabel }}</div>
    <!-- renderMarkdown은 html:false라 원시 HTML을 이스케이프한다 — v-html 안전 -->
    <div v-if="role === 'assistant'" class="bubble assistant md" v-html="html" />
    <div v-else class="bubble" :class="role">{{ content }}</div>
  </div>
</template>

<style scoped>
.bubble-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
}
.bubble-row.assistant {
  flex-direction: row;
}
.bubble-row.user {
  flex-direction: row-reverse;
}
.bubble-row.system {
  justify-content: center;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  flex: 0 0 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  font-weight: 700;
}
.avatar.assistant {
  background: #e3f2fd;
  color: #1565c0;
}
.avatar.user {
  background: #eceff3;
  color: #5b6b7d;
}
.bubble {
  max-width: 80%;
  padding: 9px 12px;
  font-size: 12.5px;
  line-height: 1.46;
  border-radius: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.assistant {
  background: #eef2f8;
  color: #25303f;
  border-bottom-left-radius: 4px;
}
.bubble.user {
  background: #1976d2;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.system-note {
  font-size: 11px;
  color: #8a97a8;
  background: #f0f2f5;
  padding: 3px 11px;
  border-radius: 11px;
}
/* 마크다운 본문 — 렌더된 블록은 pre-wrap을 끄고 여백을 말풍선에 맞춘다 */
.bubble.md {
  white-space: normal;
}
.bubble.md :deep(p) {
  margin: 0 0 6px;
}
.bubble.md :deep(p:last-child) {
  margin-bottom: 0;
}
.bubble.md :deep(ul),
.bubble.md :deep(ol) {
  margin: 4px 0;
  padding-left: 18px;
}
.bubble.md :deep(li) {
  margin: 2px 0;
}
.bubble.md :deep(h1),
.bubble.md :deep(h2),
.bubble.md :deep(h3),
.bubble.md :deep(h4) {
  font-size: 13.5px;
  font-weight: 700;
  margin: 8px 0 4px;
}
.bubble.md :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  background: rgba(37, 48, 63, 0.07);
  padding: 1px 4px;
  border-radius: 3px;
}
.bubble.md :deep(pre) {
  background: rgba(37, 48, 63, 0.07);
  padding: 8px 10px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 6px 0;
}
.bubble.md :deep(pre code) {
  background: none;
  padding: 0;
}
.bubble.md :deep(a) {
  color: #1565c0;
}
.bubble.md :deep(table) {
  border-collapse: collapse;
  margin: 6px 0;
}
.bubble.md :deep(th),
.bubble.md :deep(td) {
  border: 1px solid #d5dbe5;
  padding: 3px 6px;
}
.bubble.md :deep(blockquote) {
  margin: 6px 0;
  padding-left: 10px;
  border-left: 3px solid #c9d2df;
  color: #4b5866;
}
</style>
```

- [ ] **Step 6: 통과 확인 (기존 패널 테스트 포함)**

Run: `cd frontend && npx vitest run composables/useMarkdown.spec.ts components/chat/ChatBubble.spec.ts components/InterviewPanel.spec.ts`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add frontend/package.json frontend/package-lock.json frontend/composables/useMarkdown.ts frontend/composables/useMarkdown.spec.ts frontend/components/chat/ChatBubble.vue frontend/components/chat/ChatBubble.spec.ts
git commit -m "feat(front): assistant 말풍선 마크다운 렌더링 (markdown-it, html:false)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: frontend — 사용량 표시 규칙(`claudeUsage.ts`) + `ClaudeUsagePanel.vue`

**Files:**
- Create: `frontend/composables/claudeUsage.ts`, `frontend/composables/claudeUsage.spec.ts`
- Create: `frontend/components/ClaudeUsagePanel.vue`, `frontend/components/ClaudeUsagePanel.spec.ts`

**Interfaces:**
- Consumes: `GET /api/usage/claude` (Task 5).
- Produces: `usageColor(pct): 'primary'|'warning'|'negative'`, `usagePercent(limit, now?)`, `formatReset(iso, now?)`, `formatAgo(iso, now?)`, `LIMIT_LABELS`, `LIMIT_ORDER`, 타입 `ClaudeLimit`, `ClaudeUsageResponse`. 컴포넌트 `<ClaudeUsagePanel variant="panel" | "strip" :context-pct />` (data-test: `usage-panel`, `usage-row-<type>`, `usage-empty`, `usage-strip`). Task 12(사이드바)·13·14(모바일 스트립)가 사용. `usageColor`는 Task 14의 컨텍스트 칩도 쓴다.

- [ ] **Step 1: 실패 테스트 — `composables/claudeUsage.spec.ts`**

```ts
import { describe, it, expect } from 'vitest'
import {
  LIMIT_LABELS, LIMIT_ORDER, usageColor, usagePercent, formatReset, formatAgo,
} from './claudeUsage'

const NOW = new Date('2026-09-05T02:30:00Z').getTime()

describe('claudeUsage (스펙 2026-09-05 §2·§3)', () => {
  it('라벨/순서 SSOT', () => {
    expect(LIMIT_ORDER).toEqual(['five_hour', 'seven_day', 'seven_day_opus', 'seven_day_sonnet'])
    expect(LIMIT_LABELS.five_hour).toBe('현재 세션 (5시간)')
    expect(LIMIT_LABELS.seven_day).toBe('이번 주 (모든 모델)')
  })

  it('임계 색: 75 미만 primary, 75~89 warning, 90 이상 negative', () => {
    expect(usageColor(0)).toBe('primary')
    expect(usageColor(74)).toBe('primary')
    expect(usageColor(75)).toBe('warning')
    expect(usageColor(89)).toBe('warning')
    expect(usageColor(90)).toBe('negative')
    expect(usageColor(100)).toBe('negative')
  })

  it('usagePercent: 분수→정수 %, 0..100 클램프, resetsAt 경과 시 0', () => {
    expect(usagePercent({ utilization: 0.42, resetsAt: '2026-09-05T04:00:00Z' }, NOW)).toBe(42)
    expect(usagePercent({ utilization: 1.7, resetsAt: null }, NOW)).toBe(100)
    expect(usagePercent({ utilization: 0.9, resetsAt: '2026-09-05T02:00:00Z' }, NOW)).toBe(0)
  })

  it('formatReset: 당일은 시각만, 다른 날은 날짜 포함, 경과는 초기화됨, 없음은 빈 문자열 (로컬 시간대·ICU 버전 무관)', () => {
    const localNow = new Date(2026, 8, 5, 3, 0).getTime() // 로컬 2026-09-05 03:00
    expect(formatReset(new Date(2026, 8, 5, 7, 0).toISOString(), localNow)).toBe('오전 7시 초기화')
    const otherDay = formatReset(new Date(2026, 8, 8, 9, 0).toISOString(), localNow)
    expect(otherDay).toContain('9월 8일')
    expect(otherDay).toContain('오전 9시 초기화')
    expect(formatReset(new Date(2026, 8, 5, 2, 0).toISOString(), localNow)).toBe('초기화됨 · 다음 사용 시 갱신')
    expect(formatReset(null, localNow)).toBe('')
    expect(formatReset('garbage', localNow)).toBe('')
  })

  it('formatAgo: 1분 미만 방금, 분/시간/일 단위', () => {
    expect(formatAgo('2026-09-05T02:29:30Z', NOW)).toBe('방금 갱신')
    expect(formatAgo('2026-09-05T02:27:00Z', NOW)).toBe('3분 전 갱신')
    expect(formatAgo('2026-09-05T00:30:00Z', NOW)).toBe('2시간 전 갱신')
    expect(formatAgo('2026-09-03T02:30:00Z', NOW)).toBe('2일 전 갱신')
    expect(formatAgo(null, NOW)).toBe('')
  })
})
```

`components/ClaudeUsagePanel.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import ClaudeUsagePanel from './ClaudeUsagePanel.vue'
import { useApiMock } from '../test/mocks/nuxt'

const future = new Date(Date.now() + 3 * 3600_000).toISOString()
const limits = [
  {
    limitType: 'seven_day', status: 'allowed_warning', utilization: 0.8, resetsAt: future,
    usingOverage: false, reportedBy: 'iw-1', updatedAt: new Date(Date.now() - 3 * 60_000).toISOString(),
  },
  {
    limitType: 'five_hour', status: 'allowed', utilization: 0.42, resetsAt: future,
    usingOverage: false, reportedBy: 'iw-1', updatedAt: new Date().toISOString(),
  },
]

describe('ClaudeUsagePanel (스펙 2026-09-05 §3)', () => {
  beforeEach(() => {
    useApiMock.mockReset()
  })

  it('panel: 순서(five_hour→seven_day)·퍼센트·임계색·갱신 시각을 그린다', async () => {
    useApiMock.mockResolvedValue({ limits })
    const w = mount(ClaudeUsagePanel)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/usage/claude')
    const rows = w.findAll('[data-test^="usage-row-"]')
    expect(rows.map((r) => r.attributes('data-test'))).toEqual(['usage-row-five_hour', 'usage-row-seven_day'])
    expect(rows[0]!.text()).toContain('현재 세션 (5시간)')
    expect(rows[0]!.text()).toContain('42%')
    expect(rows[0]!.find('.q-linear-progress').classes()).toContain('text-primary')
    expect(rows[1]!.text()).toContain('80%')
    expect(rows[1]!.find('.q-linear-progress').classes()).toContain('text-warning')
    expect(w.text()).toContain('방금 갱신')
    expect(w.text()).toContain('초기화')
    w.unmount()
  })

  it('panel: 수집 전에는 빈 상태 문구', async () => {
    useApiMock.mockResolvedValue({ limits: [] })
    const w = mount(ClaudeUsagePanel)
    await flushPromises()
    expect(w.find('[data-test="usage-empty"]').text()).toContain('아직 수집된 사용량이 없습니다')
    w.unmount()
  })

  it('strip: 세션·이번 주·컨텍스트를 한 줄로, 90% 이상은 negative', async () => {
    useApiMock.mockResolvedValue({ limits })
    const w = mount(ClaudeUsagePanel, { props: { variant: 'strip', contextPct: 93 } })
    await flushPromises()
    const strip = w.find('[data-test="usage-strip"]')
    expect(strip.text()).toContain('세션')
    expect(strip.text()).toContain('42%')
    expect(strip.text()).toContain('이번 주')
    expect(strip.text()).toContain('80%')
    expect(strip.text()).toContain('컨텍스트')
    expect(strip.text()).toContain('93%')
    expect(strip.findAll('.q-linear-progress')[2]!.classes()).toContain('text-negative')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run composables/claudeUsage.spec.ts components/ClaudeUsagePanel.spec.ts`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: `composables/claudeUsage.ts` 작성**

```ts
// GET /api/usage/claude (Java ClaudeUsageResponse) 계약 미러 + 표시 규칙 SSOT (스펙 2026-09-05 §2·§3).
export interface ClaudeLimit {
  limitType: string
  status: string
  /** 0..1 분수 (러너가 정규화) */
  utilization: number
  resetsAt: string | null
  usingOverage: boolean
  reportedBy: string
  updatedAt: string
}

export interface ClaudeUsageResponse {
  limits: ClaudeLimit[]
}

export const LIMIT_LABELS: Record<string, string> = {
  five_hour: '현재 세션 (5시간)',
  seven_day: '이번 주 (모든 모델)',
  seven_day_opus: '이번 주 (Opus)',
  seven_day_sonnet: '이번 주 (Sonnet)',
}

/** 표시 순서. overage는 패널에 그리지 않는다. */
export const LIMIT_ORDER = ['five_hour', 'seven_day', 'seven_day_opus', 'seven_day_sonnet']

/** 세션/주간/컨텍스트 공통 임계 색 (Quasar 팔레트 이름). */
export function usageColor(pct: number): 'primary' | 'warning' | 'negative' {
  if (pct >= 90) return 'negative'
  if (pct >= 75) return 'warning'
  return 'primary'
}

/** 0..1 → 0..100 정수. resetsAt이 지났으면 0 (창이 초기화됨 — 다음 이벤트까지 서버 값은 stale). */
export function usagePercent(
  limit: Pick<ClaudeLimit, 'utilization' | 'resetsAt'>,
  now = Date.now(),
): number {
  if (limit.resetsAt && new Date(limit.resetsAt).getTime() <= now) return 0
  return Math.max(0, Math.min(100, Math.round(limit.utilization * 100)))
}

const WEEKDAYS_KO = ['일', '월', '화', '수', '목', '금', '토']

/**
 * "오전 7시" / "오후 3시 30분" — 로컬 시간대 기준. Intl(ko-KR)의 오전/오후 표기는 ICU 버전에 따라 "AM/PM"으로
 * 바뀌어(Node 24: "AM 7시") 테스트·브라우저 간 결과가 갈리므로 직접 조립한다 (실측 보정 R6, 2026-09-05).
 */
function formatKoTime(t: Date): string {
  const h = t.getHours()
  const period = h < 12 ? '오전' : '오후'
  const h12 = h % 12 === 0 ? 12 : h % 12
  const m = t.getMinutes()
  return m ? `${period} ${h12}시 ${String(m).padStart(2, '0')}분` : `${period} ${h12}시`
}

/**
 * "오전 7시 초기화" / "9월 8일 (화) 오전 9시 초기화" / 경과 → "초기화됨 · 다음 사용 시 갱신" / 없음·파싱 실패 → "".
 * 시각·날짜는 브라우저 로컬 시간대(formatKoTime — Intl 미사용).
 */
export function formatReset(resetsAt: string | null | undefined, now = Date.now()): string {
  if (!resetsAt) return ''
  const t = new Date(resetsAt)
  if (Number.isNaN(t.getTime())) return ''
  if (t.getTime() <= now) return '초기화됨 · 다음 사용 시 갱신'
  const time = formatKoTime(t)
  const n = new Date(now)
  const sameDay =
    t.getFullYear() === n.getFullYear() && t.getMonth() === n.getMonth() && t.getDate() === n.getDate()
  if (sameDay) return `${time} 초기화`
  return `${t.getMonth() + 1}월 ${t.getDate()}일 (${WEEKDAYS_KO[t.getDay()]}) ${time} 초기화`
}

/** "방금 갱신" / "3분 전 갱신" / "2시간 전 갱신" / "2일 전 갱신" / 없음 → "". */
export function formatAgo(updatedAt: string | null | undefined, now = Date.now()): string {
  if (!updatedAt) return ''
  const diff = now - new Date(updatedAt).getTime()
  if (!Number.isFinite(diff) || diff < 60_000) return '방금 갱신'
  const min = Math.floor(diff / 60_000)
  if (min < 60) return `${min}분 전 갱신`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h}시간 전 갱신`
  return `${Math.floor(h / 24)}일 전 갱신`
}
```

- [ ] **Step 4: `components/ClaudeUsagePanel.vue` 작성**

```vue
<script setup lang="ts">
// Claude Code 구독 사용량 (스펙 2026-09-05 §3·§4.1). panel = 좌측 목록 하단 카드, strip = 모바일 입력창 아래 한 줄.
// 5초 폴링(useTaskPolling) — 값은 SDK 턴이 돌 때만 갱신되므로 "N분 전 갱신"을 항상 함께 보여준다.
import {
  LIMIT_LABELS,
  LIMIT_ORDER,
  usageColor,
  usagePercent,
  formatReset,
  formatAgo,
  type ClaudeUsageResponse,
  type ClaudeLimit,
} from '~/composables/claudeUsage'

const props = withDefaults(
  defineProps<{
    variant?: 'panel' | 'strip'
    /** strip 전용: 현재 대화의 컨텍스트 %. null이면 항목 생략. */
    contextPct?: number | null
  }>(),
  { variant: 'panel', contextPct: null },
)

const { data } = useTaskPolling<ClaudeUsageResponse>(() => useApi('/api/usage/claude'))

// "N분 전"·"초기화됨" 판정용 시계 — 폴링과 별개로 30초마다 재계산
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  clock = setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})
onUnmounted(() => {
  if (clock) clearInterval(clock)
})

const limits = computed<ClaudeLimit[]>(() =>
  Array.isArray(data.value?.limits) ? (data.value!.limits as ClaudeLimit[]) : [],
)

const rows = computed(() =>
  LIMIT_ORDER.flatMap((type) => {
    const l = limits.value.find((x) => x.limitType === type)
    if (!l) return []
    const pct = usagePercent(l, now.value)
    return [
      {
        type,
        label: LIMIT_LABELS[type] ?? type,
        shortLabel: type === 'five_hour' ? '세션' : '이번 주',
        pct,
        color: usageColor(pct),
        reset: formatReset(l.resetsAt, now.value),
      },
    ]
  }),
)

const updatedLabel = computed(() => {
  const latest = limits.value
    .map((l) => new Date(l.updatedAt).getTime())
    .filter((t) => Number.isFinite(t))
    .sort((a, b) => b - a)[0]
  return latest ? formatAgo(new Date(latest).toISOString(), now.value) : ''
})
</script>

<template>
  <div
    v-if="props.variant === 'strip'"
    class="usage-strip row items-center no-wrap text-caption text-grey-7"
    data-test="usage-strip"
  >
    <div v-for="r in rows.slice(0, 2)" :key="r.type" class="row items-center no-wrap strip-item">
      <span>{{ r.shortLabel }}</span>
      <q-linear-progress
        :value="r.pct / 100"
        :color="r.color"
        track-color="grey-4"
        rounded
        size="3px"
        class="strip-bar"
      />
      <span class="strip-pct">{{ r.pct }}%</span>
    </div>
    <div v-if="props.contextPct != null" class="row items-center no-wrap strip-item">
      <span>컨텍스트</span>
      <q-linear-progress
        :value="props.contextPct / 100"
        :color="usageColor(props.contextPct)"
        track-color="grey-4"
        rounded
        size="3px"
        class="strip-bar"
      />
      <span class="strip-pct">{{ props.contextPct }}%</span>
    </div>
    <span v-if="rows.length === 0 && props.contextPct == null">사용량 미수집</span>
  </div>

  <div v-else class="usage-panel" data-test="usage-panel">
    <div class="row items-center justify-between">
      <span class="panel-title text-caption text-weight-medium text-grey-7">Claude Code 사용량</span>
      <span class="text-caption text-grey-7">{{ updatedLabel }}</span>
    </div>
    <div v-if="rows.length === 0" class="text-caption text-grey-7 q-mt-sm" data-test="usage-empty">
      아직 수집된 사용량이 없습니다 — 첫 질문 후 표시됩니다.
    </div>
    <div v-for="r in rows" :key="r.type" class="q-mt-sm" :data-test="`usage-row-${r.type}`">
      <div class="row items-center justify-between usage-row-head">
        <span>{{ r.label }}</span>
        <span class="text-weight-medium">{{ r.pct }}%</span>
      </div>
      <q-linear-progress
        :value="r.pct / 100"
        :color="r.color"
        track-color="grey-4"
        rounded
        size="4px"
        class="q-mt-xs"
      />
      <div class="text-caption text-grey-7 reset-label">{{ r.reset }}</div>
    </div>
  </div>
</template>

<style scoped>
.usage-panel {
  border-top: 1px solid rgba(0, 0, 0, 0.12);
  margin-top: 8px;
  padding: 12px 6px 4px;
}
.usage-row-head {
  font-size: 12px;
  line-height: 16px;
}
.reset-label {
  margin-top: 3px;
  line-height: 14px;
}
.usage-strip {
  gap: 12px;
  padding: 6px 6px 0;
  white-space: nowrap;
}
.strip-item {
  gap: 5px;
}
.strip-bar {
  width: 40px;
}
.strip-pct {
  color: rgba(0, 0, 0, 0.87);
  font-weight: 500;
}
</style>
```

- [ ] **Step 5: 통과 확인**

Run: `cd frontend && npx vitest run composables/claudeUsage.spec.ts components/ClaudeUsagePanel.spec.ts`
Expected: PASS (5 + 3).

- [ ] **Step 6: 커밋**

```bash
git add frontend/composables/claudeUsage.ts frontend/composables/claudeUsage.spec.ts frontend/components/ClaudeUsagePanel.vue frontend/components/ClaudeUsagePanel.spec.ts
git commit -m "feat(front): Claude Code 구독 사용량 패널/스트립 (임계색·초기화·갱신 시각)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: frontend — `ModelEffortPicker.vue` + `QuestionComposer.vue`

**Files:**
- Create: `frontend/components/chat/ModelEffortPicker.vue`, `frontend/components/chat/ModelEffortPicker.spec.ts`
- Create: `frontend/components/chat/QuestionComposer.vue`, `frontend/components/chat/QuestionComposer.spec.ts`

**Interfaces:**
- Consumes: `MODEL_OPTIONS`, `effortsForModel`, `coerceEffort` (Task 2).
- Produces: `<ModelEffortPicker v-model:model v-model:effort :disabled />` (expose `pickModel(value)`; data-test `model-picker`, `model-option-<id>`, `effort-toggle`, `effort-dropdown`, `effort-option-<e>`). `<QuestionComposer v-model v-model:model v-model:effort :mode="'create'|'ask'" :can-send :sending :disabled :placeholder :hint @send>` 슬롯 `#top`, `#tools` (data-test `composer-input`, `composer-send`). Task 13·14가 사용.

- [ ] **Step 1: 실패 테스트 — `components/chat/ModelEffortPicker.spec.ts`**

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import ModelEffortPicker from './ModelEffortPicker.vue'

// v-model 2개(model/effort)를 실제로 갱신하는 호스트 — 픽커의 강등 동작을 관찰한다.
function host(initialModel = 'claude-opus-5', initialEffort = 'max', disabled = false) {
  const model = ref(initialModel)
  const effort = ref(initialEffort)
  const Host = defineComponent({
    setup: () => () =>
      h(ModelEffortPicker, {
        model: model.value,
        effort: effort.value,
        disabled,
        'onUpdate:model': (v: string) => (model.value = v),
        'onUpdate:effort': (v: string) => (effort.value = v),
      }),
  })
  return { w: mount(Host), model, effort }
}

describe('ModelEffortPicker (스펙 2026-09-05 §2·§5.4)', () => {
  it('모델 짧은 라벨과 effort 세그먼트 5개를 그린다', () => {
    const { w } = host()
    expect(w.find('[data-test="model-picker"]').text()).toContain('Opus 5')
    const toggle = w.find('[data-test="effort-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.findAll('button')).toHaveLength(5)
    expect(toggle.text()).toContain('xhigh')
    expect(toggle.text()).toContain('max')
    w.unmount()
  })

  it('Haiku로 바꾸면 xhigh/max가 사라지고 effort는 high로 강등되며 힌트를 보여준다', async () => {
    const { w, model, effort } = host()
    ;(w.findComponent(ModelEffortPicker).vm as any).pickModel('claude-haiku-4-5')
    await w.vm.$nextTick()
    expect(model.value).toBe('claude-haiku-4-5')
    expect(effort.value).toBe('high')
    const toggle = w.find('[data-test="effort-toggle"]')
    expect(toggle.findAll('button')).toHaveLength(3)
    expect(toggle.text()).not.toContain('xhigh')
    expect(w.text()).toContain('Haiku는 low/medium/high만 지원')
    w.unmount()
  })

  it('disabled면 모델·effort 컨트롤 모두 비활성', () => {
    const { w } = host('claude-sonnet-5', 'high', true)
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="effort-toggle"] button').attributes('disabled')).toBeDefined()
    w.unmount()
  })
})
```

`components/chat/QuestionComposer.spec.ts`:
```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import QuestionComposer from './QuestionComposer.vue'

function mountComposer(props: Record<string, unknown> = {}) {
  return mount(QuestionComposer, {
    props: {
      mode: 'create', canSend: true, model: 'claude-opus-5', effort: 'high', modelValue: '질문', ...props,
    },
  })
}

describe('QuestionComposer (스펙 2026-09-05 §3·§6)', () => {
  it('Enter는 send, Shift+Enter는 줄바꿈(전송 아님), IME 조합 중 Enter는 무시', async () => {
    const w = mountComposer()
    const ta = w.find('textarea')
    await ta.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('send')).toHaveLength(1)
    await ta.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(w.emitted('send')).toHaveLength(1)
    await ta.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(w.emitted('send')).toHaveLength(1)
    w.unmount()
  })

  it('canSend=false면 Enter도 버튼도 전송하지 않는다', async () => {
    const w = mountComposer({ canSend: false })
    await w.find('textarea').trigger('keydown', { key: 'Enter' })
    expect(w.emitted('send')).toBeUndefined()
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('ask 모드는 픽커를 고정(비활성)하고, disabled면 입력 자체를 막는다', () => {
    const w = mountComposer({ mode: 'ask', disabled: true })
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined()
    expect(w.find('textarea').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('입력은 v-model로 올라가고 hint 문구를 보여준다', async () => {
    const w = mountComposer({ modelValue: '' })
    await w.find('textarea').setValue('새 질문')
    expect(w.emitted('update:modelValue')![0]).toEqual(['새 질문'])
    expect(w.text()).toContain('Enter 전송 · Shift+Enter 줄바꿈')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run components/chat/ModelEffortPicker.spec.ts components/chat/QuestionComposer.spec.ts`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: `components/chat/ModelEffortPicker.vue` 작성**

```vue
<script setup lang="ts">
// 모델 드롭다운 + 추론 단계(effort) 세그먼트 (스펙 2026-09-05 §3). 모델 변경 시 coerceEffort로 강등 —
// 기존 등록/질문 다이얼로그의 watch(draft.model) 규칙과 동일. 좁은 화면(lt.md)은 effort도 드롭다운.
import { useQuasar } from 'quasar'
import { MODEL_OPTIONS, effortsForModel, coerceEffort } from '~/composables/modelEffort'

const props = withDefaults(
  defineProps<{
    /** 세션 생성 후에는 고정 — 읽기 전용 표시 (스펙 §2 "모델/effort 변경 시점"). */
    disabled?: boolean
  }>(),
  { disabled: false },
)
const model = defineModel<string>('model', { required: true })
const effort = defineModel<string>('effort', { required: true })

const $q = useQuasar()
// "Opus 5 (기본)" → "Opus 5": 툴바 칩에는 짧은 이름만
const modelLabel = computed(() =>
  (MODEL_OPTIONS.find((m) => m.value === model.value)?.label ?? model.value).replace(/\s*\(.*\)$/, ''),
)
const efforts = computed(() => effortsForModel(model.value))
const effortOptions = computed(() => efforts.value.map((e) => ({ label: e, value: e })))
const compact = computed(() => !!$q.screen?.lt?.md)
const haikuHint = computed(() =>
  model.value === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : '',
)

function pickModel(value: string) {
  model.value = value
  effort.value = coerceEffort(value, effort.value)
}
// 테스트에서 q-menu(포털) 대신 직접 호출 (McpPicker.toggle 선례)
defineExpose({ pickModel })
</script>

<template>
  <div class="row items-center no-wrap model-effort-picker">
    <q-btn-dropdown
      flat
      dense
      no-caps
      icon="smart_toy"
      :label="modelLabel"
      :disable="props.disabled"
      data-test="model-picker"
    >
      <q-list>
        <q-item
          v-for="m in MODEL_OPTIONS"
          :key="m.value"
          v-close-popup
          clickable
          :active="m.value === model"
          active-class="text-primary"
          :data-test="`model-option-${m.value}`"
          @click="pickModel(m.value)"
        >
          <q-item-section>
            <q-item-label>{{ m.label }}</q-item-label>
            <q-item-label v-if="m.value === 'claude-haiku-4-5'" caption>low/medium/high만 지원</q-item-label>
          </q-item-section>
          <q-item-section v-if="m.value === model" side>
            <q-icon name="check" color="primary" />
          </q-item-section>
        </q-item>
      </q-list>
      <q-tooltip v-if="props.disabled">세션 생성 시 고정 — 바꾸려면 새 질문</q-tooltip>
    </q-btn-dropdown>

    <div class="row items-center no-wrap q-ml-sm effort-group">
      <q-icon name="tune" size="18px" color="grey-7" />
      <span class="text-caption text-grey-7 q-mx-xs">추론</span>
      <q-btn-dropdown
        v-if="compact"
        flat
        dense
        no-caps
        :label="effort"
        :disable="props.disabled"
        data-test="effort-dropdown"
      >
        <q-list dense>
          <q-item
            v-for="e in efforts"
            :key="e"
            v-close-popup
            clickable
            :data-test="`effort-option-${e}`"
            @click="effort = e"
          >
            <q-item-section>{{ e }}</q-item-section>
          </q-item>
        </q-list>
      </q-btn-dropdown>
      <q-btn-toggle
        v-else
        v-model="effort"
        :options="effortOptions"
        outline
        dense
        no-caps
        toggle-color="primary"
        :disable="props.disabled"
        data-test="effort-toggle"
      />
      <span v-if="haikuHint" class="text-caption text-grey-7 q-ml-sm">{{ haikuHint }}</span>
    </div>
  </div>
</template>

<style scoped>
.model-effort-picker {
  gap: 4px;
}
.effort-group {
  white-space: nowrap;
}
</style>
```

- [ ] **Step 4: `components/chat/QuestionComposer.vue` 작성**

```vue
<script setup lang="ts">
// 채팅 입력창 (스펙 2026-09-05 §3·§6): textarea + 툴바(모델/effort 픽커 · #tools 슬롯 · 전송).
// create = 새 질문(픽커 활성, #top 슬롯에 레포/브랜치), ask = 추가 질문(픽커는 세션 값으로 고정 표시).
// Enter 전송 · Shift+Enter 줄바꿈 · 한글 IME 조합 중 Enter는 무시(keydown.isComposing).
import ModelEffortPicker from '~/components/chat/ModelEffortPicker.vue'

const props = withDefaults(
  defineProps<{
    mode: 'create' | 'ask'
    placeholder?: string
    /** 전송 가능 여부 — 부모가 판정(텍스트/상태/레포 선택 등) */
    canSend: boolean
    sending?: boolean
    /** ask 모드: 세션이 입력 대기가 아니면 입력 자체를 막는다 */
    disabled?: boolean
    /** 하단 안내 문구(모바일은 부모가 끈다) */
    hint?: boolean
  }>(),
  { placeholder: '추가 질문을 입력하세요…', sending: false, disabled: false, hint: true },
)
const text = defineModel<string>({ default: '' })
const model = defineModel<string>('model', { required: true })
const effort = defineModel<string>('effort', { required: true })
const emit = defineEmits<{ (e: 'send'): void }>()

function onEnter(ev: KeyboardEvent) {
  if (ev.isComposing) return
  ev.preventDefault()
  if (props.canSend && !props.sending && !props.disabled) emit('send')
}
</script>

<template>
  <div class="composer" :class="{ 'composer--disabled': props.disabled }">
    <slot name="top" />
    <q-input
      v-model="text"
      type="textarea"
      autogrow
      borderless
      dense
      :disable="props.disabled || props.sending"
      :placeholder="props.placeholder"
      class="composer-input"
      data-test="composer-input"
      @keydown.enter.exact="onEnter"
    />
    <div class="row items-center no-wrap composer-bar">
      <ModelEffortPicker v-model:model="model" v-model:effort="effort" :disabled="props.mode === 'ask'" />
      <slot name="tools" />
      <q-space />
      <q-btn
        round
        dense
        unelevated
        color="primary"
        icon="send"
        :loading="props.sending"
        :disable="!props.canSend || props.disabled"
        data-test="composer-send"
        @click="emit('send')"
      />
    </div>
  </div>
  <div v-if="props.hint" class="text-caption text-grey-7 composer-hint">Enter 전송 · Shift+Enter 줄바꿈</div>
</template>

<style scoped>
.composer {
  border: 1px solid rgba(0, 0, 0, 0.24);
  border-radius: 12px;
  background: #fff;
  padding: 4px 8px 8px 10px;
}
.composer--disabled {
  opacity: 0.7;
}
.composer-input :deep(textarea) {
  font-size: 14px;
  line-height: 18px;
}
.composer-bar {
  gap: 4px;
  padding-top: 4px;
}
.composer-hint {
  padding: 4px 12px 0;
}
</style>
```

- [ ] **Step 5: 통과 확인**

Run: `cd frontend && npx vitest run components/chat/ModelEffortPicker.spec.ts components/chat/QuestionComposer.spec.ts`
Expected: PASS (3 + 4).

- [ ] **Step 6: 커밋**

```bash
git add frontend/components/chat/ModelEffortPicker.vue frontend/components/chat/ModelEffortPicker.spec.ts frontend/components/chat/QuestionComposer.vue frontend/components/chat/QuestionComposer.spec.ts
git commit -m "feat(front): 채팅 입력창(QuestionComposer) + 모델/추론 단계 픽커

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: frontend — `InterviewPanel` 슬롯/프롭 확장 (작업 인터뷰 화면 불변)

**Files:**
- Modify: `frontend/components/InterviewPanel.vue` (props/emits, status-bar, answer-bar, expose, style)
- Test: `frontend/components/InterviewPanel.spec.ts`

**Interfaces:**
- Produces: props `hideStatusBar?: boolean`, `fill?: boolean`; emit `status(status: InterviewStatus | null)`(immediate); 스코프 슬롯 `#composer="{ answer, setAnswer, canSend, sending, send, awaiting }"`(폴백 = 기존 입력창); `defineExpose({ requestCancel })`. Task 14가 사용. 기존 prop/emit/데이터 테스트 id 불변.

- [ ] **Step 1: 실패 테스트 — `InterviewPanel.spec.ts`에 추가** (import에 `h` 추가: `import { h } from 'vue'`)

```ts
describe('InterviewPanel — 질문 채팅 셸 연동 (스펙 2026-09-05 §2)', () => {
  it('hideStatusBar면 배지·버튼을 숨기되 터미널 배너는 보여준다', async () => {
    const w = await mountPanel(5, { statusName: null, turns: [], plan: null }, { kind: 'QUESTION', hideStatusBar: true })
    expect(w.find('.status-bar').exists()).toBe(false)
    expect(w.find('[data-test="cancel-interview"]').exists()).toBe(false)
    FakeEventSource.last().emit('status', 'EXPIRED')
    await flushPromises()
    expect(w.text()).toContain('세션이 만료되었습니다')
    w.unmount()
  })

  it('composer 슬롯에 답변 상태/전송 함수를 넘기고 기본 입력창은 렌더하지 않는다', async () => {
    authStub.accessToken = 'jwt'
    useApiMock.mockResolvedValueOnce({ statusName: null, turns: [], plan: null })
    const w = mount(InterviewPanel, {
      props: { sessionId: 7, kind: 'QUESTION' },
      slots: {
        composer: (p: any) => [
          h('div', { 'data-test': 'slot-awaiting' }, String(p.awaiting)),
          h('button', { 'data-test': 'slot-send', disabled: !p.canSend, onClick: () => p.send() }, 'go'),
          h('input', {
            'data-test': 'slot-input',
            value: p.answer,
            onInput: (e: Event) => p.setAnswer((e.target as HTMLInputElement).value),
          }),
        ],
      },
    })
    await flushPromises()
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false)
    FakeEventSource.last().emit('question', { seq: 1, content: '답변입니다' })
    await flushPromises()
    expect(w.find('[data-test="slot-awaiting"]').text()).toBe('true')
    await w.find('[data-test="slot-input"]').setValue('추가 질문')
    await w.find('[data-test="slot-send"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/7/ask', {
      method: 'POST',
      body: { answer: '추가 질문', replyToSeq: 1 },
    })
    w.unmount()
  })

  it('status를 emit하고 requestCancel()이 종료 확인 다이얼로그를 연다', async () => {
    const w = await mountPanel(5, { statusName: 'AWAITING_INPUT', turns: [], plan: null }, { kind: 'QUESTION', hideStatusBar: true })
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    const emitted = w.emitted('status')!
    expect(emitted[emitted.length - 1]).toEqual(['RUNNING'])
    ;(w.vm as any).requestCancel()
    await flushPromises()
    expect(document.body.textContent).toContain('질문 세션을 종료할까요?')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts`
Expected: FAIL — `.status-bar` 존재 / 슬롯 미적용(`send-answer` 존재) / `requestCancel is not a function`.

- [ ] **Step 3: `InterviewPanel.vue` 수정**

(a) props/emits:
```ts
const props = defineProps<{
  sessionId: number
  model?: string
  effort?: string
  readonly?: boolean
  /** 'QUESTION'이면 Q&A 모드 — /api/questions 경로, 설계·플랜 컬럼 미렌더, 질문 문맥 문구 (스펙 2026-08-30 §7). */
  kind?: SessionKind
  /** 질문 채팅 셸이 헤더(상태/비용/종료)를 직접 그릴 때 — 상태 바의 배지·칩·버튼을 숨긴다(터미널 배너는 유지). 스펙 2026-09-05 §2. */
  hideStatusBar?: boolean
  /** 부모 column을 채운다(70vh 고정 높이 대신). 질문 채팅 셸 전용. */
  fill?: boolean
}>()
const emit = defineEmits<{
  (e: 'confirmed', taskId: number): void
  (e: 'close'): void
  /** SSE 상태 변화 — 셸 헤더 배지가 폴링(5초)보다 먼저 반영하도록. immediate. */
  (e: 'status', status: InterviewStatus | null): void
}>()
```

(b) `isTerminal` computed 아래에 추가:
```ts
// 셸 헤더용 실시간 상태 emit (스펙 2026-09-05 §6 [id].vue)
watch(status, (s) => emit('status', s), { immediate: true })

// hideStatusBar여도 만료/종료/실패/오류 배너는 사용자가 봐야 한다.
const showBanner = computed(
  () => ['EXPIRED', 'CANCELLED', 'FAILED'].includes(status.value as string) || !!error.value,
)
```

(c) 파일 끝(`onUnmounted(...)` 아래):
```ts
// 셸의 "세션 종료" 버튼이 같은 확인 다이얼로그를 열 수 있게 노출 (스펙 2026-09-05 §3).
defineExpose({
  requestCancel() {
    showCancelConfirm.value = true
  },
})
```

(d) 템플릿 — 루트와 상태 바:
```vue
  <div class="interview-panel column no-wrap" :class="{ 'interview-panel--fill': props.fill }">
    <div v-if="!props.hideStatusBar || showBanner" class="status-bar row items-center q-pa-sm q-gutter-sm">
      <template v-if="!props.hideStatusBar">
        <q-spinner
          v-if="connState === 'connecting' || connState === 'reconnecting'"
          size="18px"
          color="primary"
        />
        <!-- 색상/터미널 판정은 영문 enum(status), 표시는 한글(statusLabel). -->
        <q-badge :color="status === 'PLAN_READY' ? 'positive' : 'primary'" :label="statusLabel" />
        <q-chip v-if="props.model" dense size="sm" outline icon="smart_toy" :label="props.model" />
        <q-chip v-if="props.effort" dense size="sm" outline icon="tune" :label="props.effort" />
      </template>
      <q-banner v-if="status === 'EXPIRED'" dense class="bg-orange-1 text-orange-10 col"
        >{{ ui.expired }}</q-banner
      >
      <q-banner v-else-if="status === 'CANCELLED'" dense class="bg-grey-2 text-grey-9 col"
        >{{ ui.cancelled }}</q-banner
      >
      <q-banner v-else-if="status === 'FAILED'" dense class="bg-red-1 text-red-9 col"
        >{{ ui.failed }}: {{ error ?? '알 수 없는 오류가 발생했습니다' }}</q-banner
      >
      <q-banner v-else-if="error" dense class="bg-red-1 text-red-9 col">{{ error }}</q-banner>
      <q-space />
      <template v-if="!props.hideStatusBar">
        <q-btn
          v-if="!isTerminal"
          data-test="later-interview"
          flat
          dense
          no-caps
          color="grey-7"
          icon="schedule"
          label="나중에"
          @click="closePanel"
        />
        <q-btn
          v-if="!isTerminal && !readonly"
          data-test="cancel-interview"
          outline
          dense
          no-caps
          color="grey-7"
          icon="stop_circle"
          :label="ui.cancel"
          class="cancel-btn"
          @click="showCancelConfirm = true"
        />
        <q-btn
          v-if="isTerminal && status !== 'REGISTERED'"
          data-test="close-interview"
          flat
          dense
          no-caps
          color="grey-7"
          icon="close"
          label="닫기"
          @click="closePanel"
        />
      </template>
    </div>
```
(테스트 1은 `.status-bar`가 배너 없을 때 아예 없음을 확인한다 — `v-if="!props.hideStatusBar || showBanner"`가 그 조건이다.)

(e) 템플릿 — 입력 영역(`<div v-if="!readonly" class="answer-bar q-pa-sm">` 내부를 슬롯으로 감싼다):
```vue
        <div v-if="!readonly" class="answer-bar q-pa-sm">
          <slot
            name="composer"
            :answer="answer"
            :setAnswer="(v: string) => (answer = v)"
            :canSend="canAnswer"
            :sending="sending"
            :send="sendAnswer"
            :awaiting="status === 'AWAITING_INPUT'"
          >
            <q-input
              v-model="answer"
              type="textarea"
              outlined
              dense
              autogrow
              :disable="status !== 'AWAITING_INPUT' || sending"
              :placeholder="ui.placeholder"
              @keydown.enter.exact.prevent="sendAnswer"
            />
            <div class="row justify-end q-mt-xs">
              <q-btn
                data-test="send-answer"
                unelevated
                color="primary"
                icon="send"
                :label="ui.send"
                :loading="sending"
                :disable="!canAnswer"
                @click="sendAnswer"
              />
            </div>
          </slot>
        </div>
```

(f) 스타일 — `.interview-panel` 규칙 아래에 추가:
```css
.interview-panel--fill {
  height: auto;
  min-height: 0;
  flex: 1 1 auto;
}
```

- [ ] **Step 4: 통과 확인 (기존 테스트 전부 포함)**

Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts test/questions-detail.spec.ts`
Expected: PASS — 기존 인터뷰/질문 상세 테스트 회귀 없음(기본 슬롯 폴백이 기존 마크업).

- [ ] **Step 5: 커밋**

```bash
git add frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts
git commit -m "feat(front): InterviewPanel composer 슬롯·hideStatusBar·fill·status emit·requestCancel

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 12: frontend — 질문 셸(`pages/questions.vue`) + `QuestionSidebar.vue` + `composables/questions.ts`

**Files:**
- Create: `frontend/composables/questions.ts`, `frontend/composables/questions.spec.ts`
- Create: `frontend/components/QuestionSidebar.vue`, `frontend/components/QuestionSidebar.spec.ts`
- Create: `frontend/pages/questions.vue`, `frontend/test/questions-shell.spec.ts`
- Modify: `frontend/test/setup.ts:38-52` (`provide`/`inject` 전역 추가)

**Interfaces:**
- Consumes: `GET /api/questions` (Task 3의 `contextTokens/contextWindow` 포함), `ClaudeUsagePanel` (Task 9), `interviewStatusLabel` (기존).
- Produces: `QuestionSummary`, `QuestionDetail` 타입, `groupByRecency(items, now?)`, `contextPercent(tokens, window)`; `<QuestionSidebar :active-id @select(id) @new />` (expose `refresh()`; data-test `new-question`, `question-row`, `all-toggle`); 셸이 `provide('questions:refresh', () => void)`, `provide('questions:open-drawer', () => void)`. Task 13·14가 `inject`한다.

- [ ] **Step 1: `test/setup.ts` — Nuxt 자동 임포트 전역에 추가** (`nextTick: vue.nextTick,` 다음 줄)

```ts
  provide: vue.provide,
  inject: vue.inject,
```

- [ ] **Step 2: 실패 테스트 — `composables/questions.spec.ts`**

```ts
import { describe, it, expect } from 'vitest'
import { groupByRecency, contextPercent, type QuestionSummary } from './questions'

const NOW = new Date(2026, 8, 5, 14, 0).getTime() // 로컬 2026-09-05 14:00

function q(id: number, updatedAt: Date): QuestionSummary {
  return {
    id, title: `q${id}`, githubRepo: 'o/r', githubBranch: 'main', repoAlias: null, requesterId: 'u',
    status: '', statusName: 'AWAITING_INPUT', model: 'claude-opus-5', effort: 'high', totalCostUsd: null,
    contextTokens: null, contextWindow: null, createdAt: updatedAt.toISOString(), updatedAt: updatedAt.toISOString(),
  }
}

describe('questions composable (스펙 2026-09-05 §3)', () => {
  it('groupByRecency: 오늘/지난 7일/이전, 빈 그룹 제외, 입력 순서 유지', () => {
    const groups = groupByRecency(
      [
        q(1, new Date(2026, 8, 5, 9)), // 오늘
        q(2, new Date(2026, 8, 1, 9)), // 4일 전
        q(3, new Date(2026, 7, 20, 9)), // 16일 전
        q(4, new Date(2026, 8, 5, 1)), // 오늘 새벽
      ],
      NOW,
    )
    expect(groups.map((g) => g.label)).toEqual(['오늘', '지난 7일', '이전'])
    expect(groups[0]!.items.map((x) => x.id)).toEqual([1, 4])
    expect(groups[1]!.items.map((x) => x.id)).toEqual([2])
    expect(groups[2]!.items.map((x) => x.id)).toEqual([3])
    expect(groupByRecency([], NOW)).toEqual([])
  })

  it('contextPercent: 반올림 정수, 0..100 클램프, 미보고/창 0은 null', () => {
    expect(contextPercent(76004, 200000)).toBe(38)
    expect(contextPercent(250000, 200000)).toBe(100)
    expect(contextPercent(null, 200000)).toBeNull()
    expect(contextPercent(10, 0)).toBeNull()
    expect(contextPercent(10, undefined)).toBeNull()
  })
})
```

`components/QuestionSidebar.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import QuestionSidebar from './QuestionSidebar.vue'
import { useApiMock, authStub } from '../test/mocks/nuxt'

const today = new Date().toISOString()
const list = [
  {
    id: 3, title: '인증 흐름 확인', githubRepo: 'a/b', githubBranch: 'main', repoAlias: 'netis7.0', requesterId: 'user1',
    status: '답변완료', statusName: 'AWAITING_INPUT', model: 'claude-opus-5', effort: 'high', totalCostUsd: 0.1,
    contextTokens: 76004, contextWindow: 200000, createdAt: today, updatedAt: today,
  },
  {
    id: 4, title: '장비 상태 화면 데이터 소스', githubRepo: 'a/b', githubBranch: 'feature/device-status', repoAlias: 'netis7.0',
    requesterId: 'user2', status: '답변중', statusName: 'RUNNING', model: 'claude-opus-5', effort: 'high', totalCostUsd: 0,
    contextTokens: null, contextWindow: null, createdAt: today, updatedAt: today,
  },
]

describe('QuestionSidebar (스펙 2026-09-05 §3)', () => {
  beforeEach(() => {
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/questions') return Promise.resolve(list)
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      return Promise.resolve(null)
    })
  })
  afterEach(() => {
    ;(authStub as any).isAdmin = false
  })

  it('목록을 상태·레포 캡션과 함께 그리고, 활성 행을 강조하며, 선택/새 질문을 emit한다', async () => {
    const w = mount(QuestionSidebar, { props: { activeId: 3 } })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', { params: { all: 'false' } })
    const rows = w.findAll('[data-test="question-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.classes()).toContain('active')
    expect(rows[0]!.text()).toContain('답변 완료 · netis7.0 · main')
    expect(rows[1]!.text()).toContain('답변 중')
    expect(w.text()).toContain('오늘')
    await rows[1]!.trigger('click')
    expect(w.emitted('select')![0]).toEqual([4])
    await w.find('[data-test="new-question"]').trigger('click')
    expect(w.emitted('new')).toHaveLength(1)
    expect(w.find('[data-test="usage-panel"]').exists()).toBe(true)
    expect(w.find('[data-test="all-toggle"]').exists()).toBe(false) // 일반 사용자
    w.unmount()
  })

  it('관리자는 전체 보기 토글이 있고, 켜면 all=true로 다시 조회하며 요청자를 캡션에 붙인다', async () => {
    ;(authStub as any).isAdmin = true
    const w = mount(QuestionSidebar, { props: { activeId: null } })
    await flushPromises()
    const toggle = w.find('[data-test="all-toggle"]')
    expect(toggle.exists()).toBe(true)
    await toggle.trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', { params: { all: 'true' } })
    expect(w.text()).toContain('· user1')
    w.unmount()
  })

  it('빈 목록이면 안내 문구', async () => {
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(url === '/api/questions' ? [] : { limits: [] }),
    )
    const w = mount(QuestionSidebar, { props: { activeId: null } })
    await flushPromises()
    expect(w.text()).toContain('아직 질문이 없습니다')
    w.unmount()
  })
})
```

`test/questions-shell.spec.ts`:
```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionsShell from '../pages/questions.vue'
import { useApiMock } from './mocks/nuxt'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock, useRoute: () => ({ params: { id: '3' } }) })

// 자식 라우트 자리 — 렌더 함수 스텁(문자열 템플릿은 런타임 컴파일러가 없어 못 쓴다)
const NuxtPageStub = defineComponent({ setup: () => () => h('div', { 'data-test': 'child-page' }) })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, {
      default: () => h(QPageContainer, {}, { default: () => h(QuestionsShell) }),
    }),
})

const today = new Date().toISOString()

describe('pages/questions (셸) — 스펙 2026-09-05 §6', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(
        url === '/api/questions'
          ? [{
              id: 3, title: '인증 흐름 확인', githubRepo: 'a/b', githubBranch: 'main', repoAlias: null, requesterId: 'user1',
              status: '', statusName: 'AWAITING_INPUT', model: 'claude-opus-5', effort: 'high', totalCostUsd: 0,
              contextTokens: null, contextWindow: null, createdAt: today, updatedAt: today,
            }]
          : { limits: [] },
      ),
    )
  })

  it('사이드바 + 자식 페이지를 그리고, 라우트 id를 활성 행으로 넘기며, 선택/새 질문이 navigateTo를 부른다', async () => {
    const w = mount(PageWrapper, { global: { stubs: { NuxtPage: NuxtPageStub } } })
    await flushPromises()
    expect(w.find('[data-test="child-page"]').exists()).toBe(true)
    const row = w.find('[data-test="question-row"]')
    expect(row.classes()).toContain('active')
    await row.trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions/3')
    await w.find('[data-test="new-question"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })
})
```

- [ ] **Step 3: 실패 확인**

Run: `cd frontend && npx vitest run composables/questions.spec.ts components/QuestionSidebar.spec.ts test/questions-shell.spec.ts`
Expected: FAIL — 모듈 없음.

- [ ] **Step 4: `composables/questions.ts` 작성**

```ts
// 질문 세션 API 계약 미러 (Java QuestionSummaryResponse / InterviewResponse 부분집합) + 사이드바/헤더 순수 계산.
export interface QuestionSummary {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  repoAlias: string | null
  requesterId: string
  status: string
  statusName: string
  model: string
  effort: string
  totalCostUsd: number | null
  contextTokens: number | null
  contextWindow: number | null
  createdAt: string
  updatedAt: string
}

/** GET /api/questions/{id} (InterviewResponse) 중 대화 화면이 쓰는 부분집합. */
export interface QuestionDetail {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  statusName: string
  model: string | null
  effort: string | null
  totalCostUsd: number | null
  contextTokens: number | null
  contextWindow: number | null
}

export interface QuestionGroup {
  key: 'today' | 'week' | 'older'
  label: string
  items: QuestionSummary[]
}

/** updatedAt 기준 오늘/지난 7일/이전 그룹. 빈 그룹 제외, 입력 순서(서버: createdAt DESC) 유지. */
export function groupByRecency(items: QuestionSummary[], now = Date.now()): QuestionGroup[] {
  const startOfToday = new Date(now)
  startOfToday.setHours(0, 0, 0, 0)
  const weekAgo = now - 7 * 86_400_000
  const groups: QuestionGroup[] = [
    { key: 'today', label: '오늘', items: [] },
    { key: 'week', label: '지난 7일', items: [] },
    { key: 'older', label: '이전', items: [] },
  ]
  for (const item of items) {
    const t = new Date(item.updatedAt).getTime()
    const g = t >= startOfToday.getTime() ? groups[0]! : t >= weekAgo ? groups[1]! : groups[2]!
    g.items.push(item)
  }
  return groups.filter((g) => g.items.length > 0)
}

/** 컨텍스트 사용률 0..100 정수. 미보고(null)거나 창이 0이면 null → 칩 숨김 (스펙 2026-09-05 §4.2). */
export function contextPercent(
  tokens: number | null | undefined,
  window: number | null | undefined,
): number | null {
  if (tokens == null || window == null || window <= 0) return null
  return Math.max(0, Math.min(100, Math.round((tokens / window) * 100)))
}
```

- [ ] **Step 5: `components/QuestionSidebar.vue` 작성**

```vue
<script setup lang="ts">
// 좌측 세션 목록 (스펙 2026-09-05 §3). 5초 폴링(기존 useTaskPolling), updatedAt 그룹, 활성 행 강조,
// 관리자 전체 보기 토글(구 목록 페이지에서 이동), 하단 Claude Code 사용량 패널.
import { interviewStatusLabel } from '~/composables/interviewLabels'
import { groupByRecency, type QuestionSummary } from '~/composables/questions'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'

const props = defineProps<{ activeId: number | null }>()
const emit = defineEmits<{ (e: 'select', id: number): void; (e: 'new'): void }>()

const auth = useAuthStore()
const all = ref(false)
const { data, refresh } = useTaskPolling<QuestionSummary[]>(() =>
  useApi('/api/questions', { params: { all: String(all.value) } }),
)
// 배열이 아닌 응답(프록시 오류 페이지 등)도 빈 목록으로 — 렌더 중 throw 방지
const rows = computed(() => (Array.isArray(data.value) ? data.value : []))
const groups = computed(() => groupByRecency(rows.value))

function statusCaption(q: QuestionSummary) {
  return interviewStatusLabel(q.statusName, 'QUESTION')
}
function isBusy(q: QuestionSummary) {
  return q.statusName === 'RUNNING' || q.statusName === 'QUEUED'
}

// 새 질문 등록 직후 셸이 즉시 갱신할 수 있게 노출 (폴링 5초를 기다리지 않는다)
defineExpose({ refresh })
</script>

<template>
  <aside class="question-sidebar column no-wrap">
    <q-btn
      unelevated
      color="primary"
      icon="add"
      label="새 질문"
      class="full-width"
      data-test="new-question"
      @click="emit('new')"
    />
    <div class="col scroll q-mt-sm">
      <div v-if="rows.length === 0" class="text-caption text-grey-7 q-pa-sm">
        아직 질문이 없습니다. 레포에 대해 궁금한 점을 물어보세요.
      </div>
      <template v-for="g in groups" :key="g.key">
        <div class="group-label text-caption text-grey-7">{{ g.label }}</div>
        <div
          v-for="q in g.items"
          :key="q.id"
          class="session-item"
          :class="{ active: q.id === props.activeId }"
          data-test="question-row"
          @click="emit('select', q.id)"
        >
          <div class="session-title ellipsis">{{ q.title }}</div>
          <div class="session-caption ellipsis text-caption text-grey-7">
            <span :class="{ 'text-primary text-weight-medium': isBusy(q) }">{{ statusCaption(q) }}</span>
            · {{ q.repoAlias ?? q.githubRepo }} · {{ q.githubBranch }}<span v-if="all"> · {{ q.requesterId }}</span>
          </div>
        </div>
      </template>
    </div>
    <ClaudeUsagePanel variant="panel" />
    <q-toggle
      v-if="auth.isAdmin"
      v-model="all"
      dense
      label="전체 보기"
      data-test="all-toggle"
      class="q-mt-sm"
      @update:model-value="refresh"
    />
  </aside>
</template>

<style scoped>
.question-sidebar {
  width: 280px;
  flex: 0 0 280px;
  height: 100%;
  background: #fafafa;
  border-right: 1px solid rgba(0, 0, 0, 0.12);
  padding: 12px;
}
.group-label {
  padding: 14px 8px 6px;
  font-weight: 500;
}
.session-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.session-item:hover {
  background: rgba(0, 0, 0, 0.04);
}
.session-item.active {
  background: #e3f2fd;
}
.session-item.active .session-title {
  color: #1976d2;
}
.session-title {
  font-size: 14px;
  line-height: 1.2em;
}
.session-caption {
  margin-top: 4px;
  line-height: 1.2em;
}
</style>
```

- [ ] **Step 6: `pages/questions.vue` 작성** (중첩 라우트 부모 — 자식 `index.vue`/`[id].vue`를 `<NuxtPage>`에 그린다)

```vue
<script setup lang="ts">
// 질문 탭 셸 (스펙 2026-09-05 §3·§6): 데스크톱은 좌측 사이드바 고정, 좁은 화면(lt.md)은 좌측 서랍(q-dialog).
// QDrawer는 QLayout 직속이어야 해 페이지에서 못 쓴다 — q-dialog position="left"로 대체.
import { useQuasar } from 'quasar'
import QuestionSidebar from '~/components/QuestionSidebar.vue'

definePageMeta({ layout: 'default' })

const route = useRoute()
const $q = useQuasar()
const activeId = computed(() => {
  const id = Number(route.params.id)
  return Number.isFinite(id) && id > 0 ? id : null
})
const drawer = ref(false)
const sidebar = ref<InstanceType<typeof QuestionSidebar> | null>(null)
const wide = computed(() => !!$q.screen?.gt?.sm)

function select(id: number) {
  drawer.value = false
  navigateTo(`/questions/${id}`)
}
function startNew() {
  drawer.value = false
  navigateTo('/questions')
}

// 자식 페이지(새 질문 등록 직후 등)가 목록을 즉시 갱신하고 서랍을 열 수 있게 제공
provide('questions:refresh', () => sidebar.value?.refresh())
provide('questions:open-drawer', () => {
  drawer.value = true
})
</script>

<template>
  <q-page class="questions-shell row no-wrap">
    <QuestionSidebar v-if="wide" ref="sidebar" :active-id="activeId" @select="select" @new="startNew" />
    <q-dialog v-else v-model="drawer" position="left">
      <QuestionSidebar
        ref="sidebar"
        :active-id="activeId"
        class="drawer-sidebar"
        @select="select"
        @new="startNew"
      />
    </q-dialog>
    <div class="col column no-wrap questions-main">
      <NuxtPage />
    </div>
  </q-page>
</template>

<style scoped>
.questions-shell {
  height: calc(100vh - 50px);
  overflow: hidden;
}
.questions-main {
  min-width: 0;
  min-height: 0;
}
.drawer-sidebar {
  height: 100vh;
}
</style>
```

- [ ] **Step 7: 통과 확인**

Run: `cd frontend && npx vitest run composables/questions.spec.ts components/QuestionSidebar.spec.ts test/questions-shell.spec.ts`
Expected: PASS (2 + 3 + 1).

- [ ] **Step 8: 커밋**

```bash
git add frontend/test/setup.ts frontend/composables/questions.ts frontend/composables/questions.spec.ts frontend/components/QuestionSidebar.vue frontend/components/QuestionSidebar.spec.ts frontend/pages/questions.vue frontend/test/questions-shell.spec.ts
git commit -m "feat(front): 질문 탭 채팅 셸 — 세션 목록 사이드바 + 중첩 라우트 부모

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 13: frontend — 새 질문 페이지(`pages/questions/index.vue`) 재작성

**Files:**
- Rewrite: `frontend/pages/questions/index.vue`
- Rewrite: `frontend/test/questions-index.spec.ts`

**Interfaces:**
- Consumes: `QuestionComposer`(Task 10), `ClaudeUsagePanel`(Task 9), `McpPicker`(기존), `inject('questions:refresh')`/`inject('questions:open-drawer')`(Task 12), `POST /api/questions`(title 없음 — Task 4).
- Produces: `defineExpose({ draft, submit, onRepoSelected })` (테스트용, 기존 선례).

- [ ] **Step 1: 테스트 재작성 — `test/questions-index.spec.ts`** (전체 파일 교체)

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import QuestionsIndex from '../pages/questions/index.vue'
import { useApiMock } from './mocks/nuxt'

// navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 여기서 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

function stubApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
    if (url === '/api/repo-catalog') return Promise.resolve([{ id: 1, alias: 'Netis7.0', ownerRepo: 'a/b', defaultBranch: 'main' }])
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    return Promise.resolve(null)
  })
}

describe('pages/questions/index — 새 질문 입력창 (스펙 2026-09-05 §3·§6)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    stubApi()
  })

  it('히어로 문구와 입력창을 그리고 레포 카탈로그를 불러오며, 레포/질문 전엔 전송이 막힌다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    expect(w.text()).toContain('무엇이 궁금하세요?')
    expect(useApiMock).toHaveBeenCalledWith('/api/repo-catalog')
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeUndefined() // create 모드 = 픽커 활성
    w.unmount()
  })

  it('제목 없이 레포/브랜치/질문/모델/effort/MCP로 POST하고, 목록을 갱신한 뒤 대화로 이동한다', async () => {
    const refresh = vi.fn()
    const w = mount(QuestionsIndex, { global: { provide: { 'questions:refresh': refresh } } })
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, {
      repoCatalogId: 1, githubBranch: 'dev', question: '로그인은 어디서?',
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    await vm.submit()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: 1, githubBranch: 'dev', question: '로그인은 어디서?',
        model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
      },
    })
    expect(refresh).toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    w.unmount()
  })

  it('429는 경고 토스트 (등록 실패로 처리하지 않음)', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, { repoCatalogId: 1, githubBranch: 'main', question: 'q' })
    useApiMock.mockImplementationOnce(() =>
      Promise.reject({ statusCode: 429, data: { message: '동시에 진행할 수 있는 질문 세션 한도(3)를 초과했습니다' } }),
    )
    await vm.submit()
    await flushPromises()
    expect(document.body.textContent).toContain('한도(3)')
    expect(navigateToMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('레포를 빠르게 전환하면 늦게 도착한 이전 레포의 브랜치 콜백이 현재 선택을 덮어쓰지 않는다', async () => {
    let resolveA!: (v: unknown) => void
    let resolveB!: (v: unknown) => void
    const pendingA = new Promise((resolve) => { resolveA = resolve })
    const pendingB = new Promise((resolve) => { resolveB = resolve })

    useApiMock.mockImplementation((url: string, opts?: { method?: string; params?: any }) => {
      if (url === '/api/repo-catalog') return Promise.resolve([
        { id: 1, alias: 'A', ownerRepo: 'org/a', defaultBranch: 'main' },
        { id: 2, alias: 'B', ownerRepo: 'org/b', defaultBranch: 'develop' },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      if (url === '/api/repos/branches' && opts?.params?.repo === 'org/a') return pendingA
      if (url === '/api/repos/branches' && opts?.params?.repo === 'org/b') return pendingB
      return Promise.resolve(null)
    })

    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any

    vm.onRepoSelected(1) // 레포 A 선택 — 브랜치 조회 진행 중
    vm.onRepoSelected(2) // 곧바로 레포 B로 전환 — inflightRepo가 org/b로 바뀜
    await flushPromises()

    resolveB({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'y' }] })
    await flushPromises()
    resolveA({ defaultBranch: 'main', branches: [{ name: 'main', sha: 'x' }] })
    await flushPromises()

    expect(vm.draft.githubBranch).toBe('develop')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run test/questions-index.spec.ts`
Expected: FAIL — 히어로 문구 없음, `submit`이 `title`을 보냄, `openCreate` 없이 `draft` 미노출.

- [ ] **Step 3: `pages/questions/index.vue` 재작성** (전체 파일)

```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'
import { DEFAULT_MODEL, DEFAULT_EFFORT } from '~/composables/modelEffort'
import McpPicker from '~/components/McpPicker.vue'
import QuestionComposer from '~/components/chat/QuestionComposer.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'

definePageMeta({ layout: 'default' })

// 새 질문 (스펙 2026-09-05 §3 NewQuestion). 제목 입력 없음 — 서버가 질문 첫 줄로 생성한다.
// 레포/브랜치 로직은 구 질문하기 다이얼로그(스펙 2026-08-30 §7)에서 그대로 옮김 (inflightRepo 가드 포함).
const $q = useQuasar()
const refreshList = inject<() => void>('questions:refresh', () => {})
const openDrawer = inject<() => void>('questions:open-drawer', () => {})

const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  question: '',
  model: DEFAULT_MODEL,
  effort: DEFAULT_EFFORT,
  mcpCatalogIds: [] as number[],
})
const submitting = ref(false)

interface RepoCatalogEntry {
  id: number
  alias: string
  ownerRepo: string | null
  defaultBranch: string | null
}
const repoCatalog = ref<RepoCatalogEntry[]>([])
const repoCatalogLoading = ref(false)
const repoOptions = computed(() =>
  repoCatalog.value.map((r) => ({ label: r.alias, value: r.id })),
)

async function loadRepoCatalog() {
  repoCatalogLoading.value = true
  try {
    const res = await useApi<RepoCatalogEntry[]>('/api/repo-catalog')
    repoCatalog.value = Array.isArray(res) ? res : []
  } catch {
    repoCatalog.value = []
  } finally {
    repoCatalogLoading.value = false
  }
}

type RepoStatus = 'empty' | 'loading' | 'ok' | 'notfound' | 'error'
const repoStatus = ref<RepoStatus>('empty')
const repoStatusMsg = ref('')
interface BranchEntry {
  name: string
  sha: string
}
const branches = ref<BranchEntry[]>([])
const defaultBranch = ref<string | null>(null)
const branchOptions = computed(() =>
  branches.value.map((b) => ({
    label: b.name === defaultBranch.value ? `${b.name} (기본)` : b.name,
    value: b.name,
  })),
)
const filteredBranchOptions = ref<{ label: string; value: string }[]>([])
let inflightRepo = '' // 응답 도착 시 최신 입력과 일치하는지 가드

function resetBranchState() {
  repoStatus.value = 'empty'
  repoStatusMsg.value = ''
  branches.value = []
  defaultBranch.value = null
  filteredBranchOptions.value = []
  draft.githubBranch = ''
}

async function loadBranches(repo: string) {
  inflightRepo = repo
  repoStatus.value = 'loading'
  repoStatusMsg.value = '브랜치 불러오는 중...'
  try {
    const res = await useApi<{
      repo: string
      defaultBranch: string | null
      branches: BranchEntry[]
    }>('/api/repos/branches', { params: { repo } })
    if (inflightRepo !== repo) return
    branches.value = res.branches ?? []
    defaultBranch.value = res.defaultBranch
    filteredBranchOptions.value = branchOptions.value
    draft.githubBranch = res.defaultBranch ?? (res.branches?.[0]?.name ?? '')
    repoStatus.value = 'ok'
    repoStatusMsg.value = `${branches.value.length}개 브랜치 · 방금 동기화`
  } catch (e: any) {
    if (inflightRepo !== repo) return
    const status = e?.statusCode ?? e?.response?.status ?? e?.status
    branches.value = []
    defaultBranch.value = null
    draft.githubBranch = ''
    repoStatus.value = status === 404 ? 'notfound' : 'error'
    repoStatusMsg.value =
      e?.data?.message ?? (status === 404 ? '레포를 찾을 수 없거나 비공개 레포입니다' : '브랜치 동기화 실패')
  }
}

function onRepoSelected(catalogId: number | null) {
  resetBranchState()
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry || !entry.ownerRepo) return
  loadBranches(entry.ownerRepo).then(() => {
    // 늦게 도착한 이전 레포의 콜백이 현재 선택을 덮어쓰지 않게 — loadBranches 내부 가드와 동일 기준.
    if (inflightRepo !== entry.ownerRepo) return
    if (entry.defaultBranch) draft.githubBranch = entry.defaultBranch
  })
}

function onBranchFilter(val: string, update: (cb: () => void) => void) {
  update(() => {
    if (!val) {
      filteredBranchOptions.value = branchOptions.value
      return
    }
    const lc = val.toLowerCase()
    filteredBranchOptions.value = branchOptions.value.filter((o) => o.value.toLowerCase().includes(lc))
  })
}

onMounted(loadRepoCatalog)

const canSubmit = computed(
  () =>
    !submitting.value &&
    draft.repoCatalogId !== null &&
    !!draft.githubBranch &&
    !!draft.question.trim(),
)

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    // title 없음 — 서버가 질문 첫 줄로 생성 (QuestionService.deriveTitle)
    const created = await useApi<{ id: number }>('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        question: draft.question,
        model: draft.model,
        effort: draft.effort,
        mcpCatalogIds: draft.mcpCatalogIds,
      },
    })
    $q.notify({ type: 'positive', message: '질문이 등록되었습니다 — 답변을 준비합니다' })
    refreshList()
    await navigateTo(`/questions/${created.id}`)
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    // 429(활성 세션 상한)는 실패가 아니라 안내 — 경고 톤.
    $q.notify({ type: st === 429 ? 'warning' : 'negative', message: e?.data?.message ?? '질문 등록 실패' })
  } finally {
    submitting.value = false
  }
}

// 테스트에서 q-select/입력창 조작 대신 직접 호출 (McpPicker.toggle 선례)
defineExpose({ draft, submit, onRepoSelected })
</script>

<template>
  <div class="new-question column no-wrap">
    <div v-if="$q.screen.lt.md" class="row items-center q-px-sm q-pt-sm">
      <q-btn flat dense round icon="menu" data-test="open-drawer" @click="openDrawer" />
    </div>
    <div class="col column items-center justify-center q-px-lg new-question-body">
      <div class="hero text-center">
        <div class="text-h5">무엇이 궁금하세요?</div>
        <div class="text-body2 text-grey-8 q-mt-xs">
          선택한 레포를 읽고 답합니다. 코드는 수정되지 않으며, 구현이 필요하면 작업 등록을 이용하세요.
        </div>
      </div>
      <div class="composer-wrap q-mt-lg">
        <QuestionComposer
          v-model="draft.question"
          v-model:model="draft.model"
          v-model:effort="draft.effort"
          mode="create"
          placeholder="예: 로그인 요청은 어느 컨트롤러가 처리하고 토큰은 어디서 검증하나요?"
          :can-send="canSubmit"
          :sending="submitting"
          :hint="!$q.screen.lt.md"
          @send="submit"
        >
          <template #top>
            <div class="row q-col-gutter-md q-pa-sm">
              <q-select
                v-model="draft.repoCatalogId"
                :options="repoOptions"
                :loading="repoCatalogLoading"
                label="레포 (별칭 선택)"
                outlined
                dense
                emit-value
                map-options
                class="col-12 col-md-6"
                data-test="repo-select"
                :hint="repoCatalog.length === 0 ? '등록된 레포 없음 — 관리자에게 문의' : '관리자가 등록한 레포 중 선택'"
                @update:model-value="onRepoSelected"
              >
                <template #no-option>
                  <q-item>
                    <q-item-section class="text-grey">등록된 레포가 없습니다</q-item-section>
                  </q-item>
                </template>
              </q-select>
              <q-select
                v-model="draft.githubBranch"
                :options="filteredBranchOptions"
                :disable="repoStatus !== 'ok'"
                label="브랜치"
                outlined
                dense
                use-input
                input-debounce="0"
                emit-value
                map-options
                class="col-12 col-md-6"
                :hint="
                  repoStatus === 'ok'
                    ? repoStatusMsg
                    : repoStatus === 'notfound' || repoStatus === 'error'
                      ? repoStatusMsg
                      : '레포 선택 후 브랜치 선택 가능'
                "
                @filter="onBranchFilter"
              >
                <template #no-option>
                  <q-item>
                    <q-item-section class="text-grey">결과 없음</q-item-section>
                  </q-item>
                </template>
              </q-select>
            </div>
          </template>
          <template #tools>
            <q-btn flat dense no-caps icon="extension" label="MCP 도구" data-test="mcp-button">
              <q-badge v-if="draft.mcpCatalogIds.length" color="primary" floating>
                {{ draft.mcpCatalogIds.length }}
              </q-badge>
              <q-menu>
                <div class="mcp-menu">
                  <McpPicker v-model="draft.mcpCatalogIds" />
                </div>
              </q-menu>
            </q-btn>
          </template>
        </QuestionComposer>
        <ClaudeUsagePanel v-if="$q.screen.lt.md" variant="strip" class="q-mt-sm" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.new-question {
  height: 100%;
}
.new-question-body {
  padding-bottom: 96px; /* 세로 중앙보다 살짝 위 — 모델 메뉴가 아래로 열릴 공간 */
}
.hero {
  max-width: 760px;
}
.composer-wrap {
  width: 100%;
  max-width: 760px;
}
.mcp-menu {
  min-width: 360px;
  max-width: 480px;
}
</style>
```

- [ ] **Step 4: 통과 확인**

Run: `cd frontend && npx vitest run test/questions-index.spec.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/questions/index.vue frontend/test/questions-index.spec.ts
git commit -m "feat(front): 새 질문 페이지 — 히어로 + 채팅 입력창(레포/브랜치·모델/effort·MCP), 제목 입력 제거

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 14: frontend — 대화 페이지(`pages/questions/[id].vue`) 재작성

**Files:**
- Rewrite: `frontend/pages/questions/[id].vue`
- Rewrite: `frontend/test/questions-detail.spec.ts`

**Interfaces:**
- Consumes: `InterviewPanel` 슬롯/프롭/emit/expose(Task 11), `QuestionComposer`(Task 10), `ClaudeUsagePanel`(Task 9), `contextPercent`/`QuestionDetail`(Task 12), `usageColor`(Task 9), `GET /api/questions/{id}`(컨텍스트 필드 — Task 3), `inject` 2개(Task 12).

- [ ] **Step 1: 테스트 재작성 — `test/questions-detail.spec.ts`** (전체 파일 교체)

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import QuestionDetail from '../pages/questions/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { FakeEventSource } from './mocks/eventsource'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

// 헤더 폴링(GET /api/questions/3)과 패널 스냅샷(같은 URL)이 모두 이 응답을 쓴다.
const detail = {
  id: 3, title: '인증 흐름 확인', githubRepo: 'micthebick84/netis7.0', githubBranch: 'main', status: '입력대기',
  statusName: 'AWAITING_INPUT', model: 'claude-sonnet-5', effort: 'medium', kind: 'QUESTION',
  totalCostUsd: 0.42, contextTokens: 76004, contextWindow: 200000,
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '**AuthController**입니다' }],
  plan: null,
}

describe('pages/questions/[id] — 대화 (스펙 2026-09-05 §3·§6)', () => {
  beforeEach(() => {
    authStub.accessToken = 'jwt'
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.resolve(detail)
        : url === '/api/usage/claude'
          ? Promise.resolve({ limits: [] })
          : Promise.resolve(null),
    )
  })

  it('헤더(제목·상태·레포·비용·컨텍스트)와 채팅 입력창을 그리고 답변은 마크다운으로 렌더한다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.text()).toContain('인증 흐름 확인')
    expect(w.find('[data-test="status-badge"]').text()).toBe('답변 완료')
    expect(w.text()).toContain('micthebick84/netis7.0 · main')
    expect(w.text()).toContain('누적 비용 0.42')
    expect(w.find('[data-test="context-chip"]').text()).toContain('컨텍스트 38%')
    expect(w.find('[data-test="composer-send"]').exists()).toBe(true)
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false) // 기본 입력창 대신 슬롯
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeDefined() // ask 모드 = 고정
    expect(w.find('.bubble.assistant strong').text()).toBe('AuthController')
    w.unmount()
  })

  it('입력창에서 Enter로 추가 질문을 보낸다 (replyToSeq = 마지막 assistant seq)', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    const ta = w.find('textarea')
    await ta.setValue('리프레시 토큰은요?')
    await ta.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/ask', {
      method: 'POST',
      body: { answer: '리프레시 토큰은요?', replyToSeq: 1 },
    })
    w.unmount()
  })

  it('SSE 상태가 배지에 즉시 반영되고, 세션 종료 버튼은 패널의 종료 확인 다이얼로그를 연다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="status-badge"]').text()).toBe('답변 중')
    await w.find('[data-test="close-session"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('질문 세션을 종료할까요?')
    w.unmount()
  })

  it('컨텍스트 미보고(null)면 칩을 숨긴다', async () => {
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.resolve({ ...detail, contextTokens: null, contextWindow: null })
        : Promise.resolve({ limits: [] }),
    )
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.find('[data-test="context-chip"]').exists()).toBe(false)
    w.unmount()
  })

  it('조회 실패(403/404)면 목록으로 돌려보낸다', async () => {
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.reject({ statusCode: 403, data: { message: '권한이 없습니다' } })
        : Promise.resolve({ limits: [] }),
    )
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run test/questions-detail.spec.ts`
Expected: FAIL — `status-badge`/`context-chip`/`composer-send` 없음.

- [ ] **Step 3: `pages/questions/[id].vue` 재작성** (전체 파일)

```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'
import InterviewPanel from '~/components/InterviewPanel.vue'
import QuestionComposer from '~/components/chat/QuestionComposer.vue'
import ClaudeUsagePanel from '~/components/ClaudeUsagePanel.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'
import { contextPercent, type QuestionDetail } from '~/composables/questions'
import { usageColor } from '~/composables/claudeUsage'
import type { InterviewStatus } from '~/composables/useInterviewStream'

definePageMeta({ layout: 'default' })

// 대화 화면 (스펙 2026-09-05 §3 Main). 헤더 데이터(제목·비용·컨텍스트)는 5초 폴링 — 답변 도착 후 최대 5초 내 따라온다.
// 상태 배지는 패널의 SSE emit이 폴링보다 먼저 반영한다. 조회 실패(403 타인/404 kind 불일치)는 목록으로.
const route = useRoute()
const $q = useQuasar()
const sessionId = computed(() => Number(route.params.id))
const openDrawer = inject<() => void>('questions:open-drawer', () => {})
const refreshList = inject<() => void>('questions:refresh', () => {})

const redirected = ref(false)
const { data: detail } = useTaskPolling<QuestionDetail | null>(async () => {
  try {
    return await useApi<QuestionDetail>(`/api/questions/${sessionId.value}`)
  } catch (e: any) {
    if (!redirected.value) {
      redirected.value = true
      $q.notify({ type: 'negative', message: e?.data?.message ?? '질문을 불러오지 못했습니다' })
      await navigateTo('/questions')
    }
    return null
  }
})

const liveStatus = ref<InterviewStatus | null>(null)
const statusName = computed(() => liveStatus.value ?? detail.value?.statusName ?? null)
const statusLabel = computed(() => interviewStatusLabel(statusName.value, 'QUESTION'))
const statusChip = computed(() => interviewStatusChip(statusName.value))
const isTerminal = computed(() =>
  ['REGISTERED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(statusName.value ?? ''),
)

const contextPct = computed(() =>
  contextPercent(detail.value?.contextTokens, detail.value?.contextWindow),
)
function fmtTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}
const contextTip = computed(() => {
  const d = detail.value
  return d?.contextTokens != null && d.contextWindow
    ? `${fmtTokens(d.contextTokens)} / ${fmtTokens(d.contextWindow)} 토큰`
    : ''
})

const panel = ref<InstanceType<typeof InterviewPanel> | null>(null)
function requestClose() {
  panel.value?.requestCancel()
}
// 종료/나중에 → 사이드바 캡션(종료됨) 즉시 갱신
function onPanelClose() {
  refreshList()
}
</script>

<template>
  <div class="conversation column no-wrap">
    <div class="row items-center no-wrap conv-header">
      <q-btn
        v-if="$q.screen.lt.md"
        flat
        dense
        round
        icon="menu"
        data-test="open-drawer"
        @click="openDrawer"
      />
      <div class="text-subtitle1 text-weight-medium ellipsis conv-title">{{ detail?.title ?? '질문' }}</div>
      <!-- 진행 상태는 primary 배지, 터미널 상태는 interviewStatusChip 색(기존 상세 헤더와 동일 팔레트) -->
      <q-badge v-if="statusName && !isTerminal" color="primary" :label="statusLabel" data-test="status-badge" />
      <q-chip
        v-else-if="statusName"
        dense
        size="sm"
        :style="{ backgroundColor: statusChip[0], color: statusChip[1] }"
        :label="statusLabel"
        data-test="status-badge"
      />
      <q-chip
        v-if="detail"
        dense
        size="sm"
        outline
        icon="folder"
        :label="`${detail.githubRepo} · ${detail.githubBranch}`"
        class="gt-xs"
      />
      <q-chip
        v-if="detail?.totalCostUsd != null"
        dense
        size="sm"
        outline
        icon="paid"
        :label="`누적 비용 ${Number(detail.totalCostUsd).toFixed(2)}`"
        class="gt-xs"
      />
      <q-chip v-if="contextPct != null" dense size="sm" outline data-test="context-chip">
        <q-circular-progress
          :value="contextPct"
          size="14px"
          :thickness="0.35"
          :color="usageColor(contextPct)"
          track-color="grey-4"
          class="q-mr-xs"
        />
        컨텍스트 {{ contextPct }}%
        <q-tooltip>{{ contextTip }}</q-tooltip>
      </q-chip>
      <q-space />
      <q-btn
        v-if="!isTerminal"
        outline
        dense
        no-caps
        color="grey-7"
        icon="stop_circle"
        label="세션 종료"
        data-test="close-session"
        @click="requestClose"
      />
    </div>

    <InterviewPanel
      ref="panel"
      :session-id="sessionId"
      kind="QUESTION"
      hide-status-bar
      fill
      :model="detail?.model ?? undefined"
      :effort="detail?.effort ?? undefined"
      class="col conv-panel"
      @status="liveStatus = $event"
      @close="onPanelClose"
    >
      <template #composer="{ answer, setAnswer, canSend, sending, send, awaiting }">
        <div class="composer-wrap">
          <QuestionComposer
            :model-value="answer"
            :model="detail?.model ?? 'claude-opus-5'"
            :effort="detail?.effort ?? 'high'"
            mode="ask"
            :can-send="canSend"
            :sending="sending"
            :disabled="!awaiting"
            :hint="!$q.screen.lt.md"
            @update:model-value="setAnswer"
            @send="send"
          >
            <template #tools>
              <q-btn flat dense no-caps icon="extension" label="MCP 도구" disable>
                <q-tooltip>세션 생성 시 고정 — 바꾸려면 새 질문</q-tooltip>
              </q-btn>
            </template>
          </QuestionComposer>
          <ClaudeUsagePanel v-if="$q.screen.lt.md" variant="strip" :context-pct="contextPct" class="q-mt-xs" />
        </div>
      </template>
    </InterviewPanel>
  </div>
</template>

<style scoped>
.conversation {
  height: 100%;
}
.conv-header {
  gap: 8px;
  min-height: 57px;
  padding: 10px 20px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.12);
}
.conv-title {
  min-width: 0;
}
.conv-panel {
  min-height: 0;
}
.composer-wrap {
  width: 100%;
  max-width: 820px;
  margin: 0 auto;
}
/* 트랜스크립트 가운데 정렬 최대 820px (캔버스 Main) — 패널 내부 스크롤 영역에만 적용 */
.conv-panel :deep(.transcript) {
  max-width: 820px;
  width: 100%;
  margin: 0 auto;
}
</style>
```

- [ ] **Step 4: 통과 확인 + 프론트 전체**

Run: `cd frontend && npx vitest run test/questions-detail.spec.ts && npm test`
Expected: PASS (5 tests) · 전체 스위트 그린.

- [ ] **Step 5: 커밋**

```bash
git add frontend/pages/questions/[id].vue frontend/test/questions-detail.spec.ts
git commit -m "feat(front): 대화 페이지 — 헤더(상태·비용·컨텍스트 칩) + 채팅 입력창 슬롯

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 15: 전 계층 검증 · 문서 · 배포 스모크

**Files:**
- Modify: `CLAUDE.md`(netisMaker) — "질문 세션(Q&A)" 항목 + "자주 보는 코드" 표
- Modify: `TODOS.md` — 후속 2건

**Interfaces:**
- Consumes: Task 1~14 산출물 전부. 산출물 없음(검증·문서).

- [ ] **Step 1: 자동 검증 3면**

Run:
```bash
RUN_TESTCONTAINERS=true ./gradlew build
cd netismaker-interview-service && npm test && npm run build && cd ..
cd frontend && npm test && npm run typecheck && npm run build && cd ..
```
Expected: 전부 성공. (`npm run build`가 `.output/`을 재생성한다 — 운영 프론트는 이 산출물을 쓴다.)

- [ ] **Step 2: `CLAUDE.md` 갱신** — "핵심 설계 제약"의 **질문 세션(Q&A)** 불릿 끝에 다음 문장을 잇고, "자주 보는 코드" 표에 행을 추가한다.

질문 세션 불릿에 추가:
```markdown
  **2026-09-05 채팅형 UI**(스펙 `docs/superpowers/specs/2026-09-05-question-chat-ui-design.md`): `/questions`는 중첩 라우트 셸(`pages/questions.vue` 사이드바 + `<NuxtPage>`), `index`=새 질문 입력창, `[id]`=대화. 제목은 서버가 질문 첫 줄로 생성(`QuestionService.deriveTitle`, `title` optional). 모델 목록에서 **Fable 제외**(`ModelEffortPolicy`+`modelEffort.ts` 동시 수정). 구독 사용량: 인터뷰 서비스 relay가 SDK `rate_limit_event`를 `POST /worker/usage/rate-limits`로 보고 → `com.claude_rate_limit` → `GET /api/usage/claude`(사이드바 패널, 인증 사용자 전원). 컨텍스트: `/question` 바디의 `contextTokens/contextWindow` → `interview_session.context_*` → 대화 헤더 칩. 답변 말풍선은 markdown-it(html:false). 배포 순서: API → 인터뷰 서비스 → 프론트 `.output` 재빌드.
```

"자주 보는 코드" 표에 추가할 행:
```markdown
| 질문 탭 셸/사이드바 | `frontend/pages/questions.vue`, `frontend/components/QuestionSidebar.vue` |
| 새 질문 / 대화 페이지 | `frontend/pages/questions/index.vue`, `frontend/pages/questions/[id].vue` |
| 채팅 입력창 · 모델/effort 픽커 | `frontend/components/chat/QuestionComposer.vue`, `frontend/components/chat/ModelEffortPicker.vue` |
| 구독 사용량 | `service/ClaudeUsageService.java`, `controller/UsageWorkerController.java`, `controller/UsageController.java`, `frontend/components/ClaudeUsagePanel.vue`, `frontend/composables/claudeUsage.ts` |
| 사용량/컨텍스트 수집(인터뷰 서비스) | `netismaker-interview-service/src/runner/messageRelay.ts`, `src/runner/rateLimitReport.ts` |
```

- [ ] **Step 3: `TODOS.md` 갱신** — 파일 끝에 추가 (기존 항목 형식)

```markdown
### [ ] 질문 대화 중 모델·effort 변경

**What**: 대화 중 입력창 툴바의 모델/추론 단계 픽커를 활성화해 다음 턴부터 다른 모델/effort로 답하게 한다. 현재는 세션 생성 시 고정(읽기 전용 표시).

**Why**: 긴 질문 세션에서 "이 질문만 Haiku로 싸게" 같은 요구가 나올 수 있다. 다만 `InterviewSession.model/effort`가 세션 단위이고, SDK `resume` 세션에서 모델 교체가 컨텍스트/캐시에 미치는 영향이 미검증이다.

**Context**: 스펙 `docs/superpowers/specs/2026-09-05-question-chat-ui-design.md` §9. 서버 `PATCH /api/questions/{id}/model` + `ModelEffortPolicy.validate` + 다음 claim에 반영, 프론트 `QuestionComposer mode="ask"`의 `disabled` 해제.

**우선순위**: 낮음. 요청이 실제로 나오면 착수.

### [ ] Claude Code 사용량 유휴 갱신 프로브

**What**: 인터뷰 서비스가 유휴일 때도 주기적으로(예: 10분) 초경량 SDK 쿼리(haiku, maxTurns 1)를 돌려 `rate_limit_event`를 받아 사용량 패널을 신선하게 유지한다. 현재는 SDK 턴이 돌 때만 갱신되고 UI가 "N분 전 갱신"으로 정직하게 표기한다.

**Why**: 오래 유휴한 뒤 첫 질문 전에 남은 한도를 정확히 보고 싶을 때. 대신 프로브마다 쿼터를 소모한다(운영 트레이드오프).

**Context**: 스펙 §2 "사용량 신선도"·§9. `config.ts`에 `USAGE_PROBE_INTERVAL_MS`(0=off) 추가, `ClaimLoop` 유휴 틱에서 `RateLimitReporter`로 보고. Java 워커(`claude -p --output-format json`)는 이벤트가 없어 대상이 아니다.

**우선순위**: 낮음. 패널의 "N분 전 갱신" 표기로 운영해 보고 불편이 확인되면 착수.
```

- [ ] **Step 4: 배포 + 수동 스모크 (운영자 macOS, 순서 엄수: API → 인터뷰 서비스 → 프론트)**

Run:
```bash
./scripts/stop-all.sh && ./scripts/start-all.sh      # API(V22 적용) + 워커 + 인터뷰 서비스
cd frontend && npm run build && cd ..                 # .output 재빌드 (curl 스모크는 UI를 검증 못 한다)
# 프론트 프로세스 재기동은 기존 운영 절차(start-public.sh / .output/server) 그대로
grep -n "V22" .run/api.log | head -3                  # Flyway V22 적용 확인
```
체크리스트 (브라우저, 실제 구독 계정):
1. 상단 **질문** 탭 클릭 → 좌측 세션 목록 + 우측 "무엇이 궁금하세요?" 입력창으로 바로 진입한다.
2. 레포·브랜치 선택, 모델 메뉴에 **Opus 5 / Sonnet 5 / Haiku 4.5**만 보이고 Fable이 없다. Haiku 선택 시 xhigh/max가 사라지고 effort가 high로 강등된다.
3. 제목 없이 질문 입력 후 Enter → 토스트 → 사이드바에 새 행이 **즉시** 뜨고(폴링 대기 없음) 대화 화면으로 이동. 행 제목 = 질문 첫 줄.
4. 답변 도착 → 말풍선이 마크다운(굵게·코드·목록)으로 렌더되고, 헤더에 **컨텍스트 N%** 링 칩이 나타난다(툴팁에 `xk / 200.0k 토큰`).
5. 좌측 하단 **Claude Code 사용량**에 "현재 세션 (5시간)"·"이번 주 (모든 모델)" 행과 초기화 시각·"방금 갱신"이 보인다. `/usage`(Claude Code CLI)의 퍼센트와 대략 일치한다.
6. 추가 질문 Enter 전송, Shift+Enter 줄바꿈, 한글 조합 중 Enter로 전송되지 않는다.
7. 브라우저 폭 390px: 헤더 메뉴 버튼으로 세션 서랍이 열리고, 입력창 아래 "세션 · 이번 주 · 컨텍스트" 스트립이 보인다. effort는 드롭다운.
8. **세션 종료** → 확인 다이얼로그 → 종료됨 배너, 입력창 비활성, 사이드바 캡션 "종료됨".
9. 작업 탭 → 인터뷰(작업) 화면의 상태 바·입력창이 기존과 동일하다(회귀 없음).
10. `.run/interview.log`에 `[rate-limit] 사용량 보고 실패` 경고가 없다.

- [ ] **Step 5: 커밋 + PR**

```bash
git add CLAUDE.md TODOS.md
git commit -m "docs: 질문 탭 채팅형 UI·구독 사용량 표시 반영 (CLAUDE.md, TODOS)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
git push -u origin feature/question-chat-ui
gh pr create --title "feat: 질문 탭 채팅형 UI + Claude Code 구독 사용량/컨텍스트 표시 + Fable 제외" --body-file - <<'EOF'
## 요약
- 질문 탭을 좌측 세션 목록 + 우측 대화창의 채팅 레이아웃으로 전환 (`pages/questions.vue` 셸 + `index`/`[id]`)
- 입력창 툴바에서 모델(Fable 제외)·추론 단계 선택, 제목은 서버가 질문 첫 줄로 생성
- 인터뷰 서비스 relay가 SDK `rate_limit_event`·턴 usage를 보고 → `/worker/usage/rate-limits`, `/question` 컨텍스트 필드 → `GET /api/usage/claude`, 대화 헤더 컨텍스트 칩, 사이드바 사용량 패널
- 답변 말풍선 markdown-it 렌더(html:false)

스펙: `docs/superpowers/specs/2026-09-05-question-chat-ui-design.md` · 계획: `docs/superpowers/plans/2026-09-05-question-chat-ui.md`
디자인 캔버스: https://claude.ai/code/artifact/49954c08-95f6-46e5-880e-06e77b73691c

## 배포 순서
API(V22) → 인터뷰 서비스 → 프론트 `.output` 재빌드

## 스모크
- [ ] Task 15 체크리스트 10항목

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

---

## Self-Review (작성 시점 점검 결과)

- **스펙 커버리지**: §2 모델 목록→Task 2 · 제목→Task 4 · 사용량 소스/신선도→Task 1·5·6·7·9 · 컨텍스트→Task 3·6·7·14 · 마크다운→Task 8 · 임계 색→Task 9(`usageColor`) · 조회 권한→Task 5(`UsageController`, JWT 전원) · 모바일→Task 12(서랍)·13·14(스트립) · 폴링→Task 12·14 · InterviewPanel 불변→Task 11 · §5 계약 전부→Task 3·4·5 · §6 파일 표 전부→Task 8~14 · §8 배포 순서→Task 15.
- **타입 일관성**: `contextTokens/contextWindow`(Java record·TS 타입·프론트 인터페이스 동일 이름), `postRateLimit`(javaClient·RateLimitReporter·러너 테스트), `RateLimitInfo`(types·relay·reporter), `usageColor`(claudeUsage·ClaudeUsagePanel·[id].vue), `contextPercent`(questions·[id].vue), `requestCancel`/`hideStatusBar`/`fill`/`status` emit(InterviewPanel·[id].vue), `QuestionComposer` props `mode/canSend/sending/disabled/hint` + `v-model`·`v-model:model`·`v-model:effort`(index·[id]), 슬롯 스코프 `answer/setAnswer/canSend/sending/send/awaiting`(Task 11 정의 = Task 14 사용).
- **의존 순서**: Task 1(스파이크) → 2 → 3(V22) → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 → 14 → 15. 프론트(8~14)는 백엔드(3~5)와 독립적으로 테스트 가능(모든 API는 mock).
