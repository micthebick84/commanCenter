# 인터뷰 실시간 사고 과정 스트리밍 (Live Activity) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인터뷰 대기 중 침묵 구간을 없앤다 — SDK 스트림의 도구 활동·내레이션/질문 토큰 델타·thinking을 SSE `activity` 이벤트(transient)로 실시간 릴레이해 프론트 진행 버블에 렌더한다.

**Architecture:** 워커(`netismaker-interview-service`)가 `includePartialMessages`로 받은 `stream_event` 델타와 assistant 메시지의 tool_use를 `ActivityPoster`(300ms 배치·직렬 전송)로 신규 Java 엔드포인트 `POST /worker/interviews/{id}/activity`에 보내고, Java `InterviewStreamService`가 그대로 SSE `activity` 이벤트로 fan-out(DB 미저장), 프론트 `useInterviewStream`이 `pending` 상태로 누적해 `PendingBubble`에 렌더한다. 확정 `question` 턴 도착 시 pending은 소거·대체된다.

**Tech Stack:** TypeScript/Node(`@anthropic-ai/claude-agent-sdk@0.2.117`, vitest) · Spring Boot 3.4(Java 21, SseEmitter) · Nuxt 3 + Quasar(Vitest + VTU, happy-dom)

**Spec:** `docs/superpowers/specs/2026-08-23-interview-live-activity-design.md`

## Global Constraints

- **SSE LOCKED CONTRACT는 추가만**: 기존 question/design/plan_ready/status/done 페이로드·의미 절대 불변. 신규 `activity` = `{"events":[{seq,type,label,detail,content}]}` JSON, **transient(미저장)·no-replay**.
- **activity 엔드포인트는 DB 미접근**(JPA 호출 0) — 300ms 배치 고빈도 호출, HikariCP 풀 압박 금지(과거 SSE+OSIV 풀 고갈 사고).
- **activity 실패는 인터뷰에 절대 무영향** — 배치 폐기+경고 1회, 404(구 Java)면 세션 잔여 기간 비활성화. throw 금지.
- **워커 allowedTools 불변식 유지**: `['Skill','Read','Grep','Glob']` — Write/Bash/Edit 추가 금지(`sessionOptions.test.ts` 불변식 단언 존재).
- **프론트 코드 스타일**: 작은따옴표 + **세미콜론 없음** + 2-space + trailing comma. `npm run lint-prettier`를 frontend/ 전체에 돌리지 말 것(단일 파일만).
- **워커(interview-service) 코드 스타일**: 작은따옴표 + **세미콜론 있음** (frontend와 다름 주의).
- **Java 테스트**: 단위(플레인 JUnit+Mockito)는 항상 실행, `@SpringBootTest` 통합은 `RUN_TESTCONTAINERS=true` 게이트(로컬 Docker Desktop에서 동작, ~15초).
- **한국어 주석/커밋** 컨벤션 (기존 파일과 동일 톤).
- 델타 shape 근거(설치본 실측): 봉투는 `@anthropic-ai/claude-agent-sdk@0.2.117`의 `sdk.d.ts:2855` — `SDKPartialAssistantMessage = {type:'stream_event', event: BetaRawMessageStreamEvent, parent_tool_use_id: string|null, …}`, `includePartialMessages` 옵션은 `sdk.d.ts:1316`. 델타 타입은 **의존성 `@anthropic-ai/sdk`의 `resources/beta/messages/messages.d.ts`** — `BetaTextDelta={type:'text_delta',text}`(:1336), `BetaThinkingDelta={type:'thinking_delta',thinking}`(:1461), `BetaRawContentBlockDeltaEvent={type:'content_block_delta',index,delta}`(:1114). Task 3 스파이크가 런타임 실물로 재검증한다.

## File Structure

| 구분 | 파일 | 책임 |
|---|---|---|
| Java 생성 | `src/main/java/com/hamonsoft/netismaker/dto/ActivityEvent.java` | 활동 1건 record + 검증 상한 |
| Java 생성 | `src/main/java/com/hamonsoft/netismaker/dto/WorkerActivityRequest.java` | 배치 record (`events` ≤100) |
| Java 수정 | `service/InterviewStreamService.java` | `pushActivity` + 계약 주석 |
| Java 수정 | `controller/InterviewWorkerController.java` | `POST /{id}/activity` (DB 미접근) |
| 워커 생성 | `src/runner/activityPoster.ts` | 배치·직렬·실패격리 전송기 |
| 워커 생성 | `scripts/spikeStream.ts` | stream_event 실물 캡처 스파이크 |
| 워커 수정 | `src/types.ts` | `ActivityInput`/`ActivityEvent`/`WorkerActivityRequest` |
| 워커 수정 | `src/api/javaClient.ts` | `postActivity` + `HttpStatusError` |
| 워커 수정 | `src/runner/messageRelay.ts` | `relay(stream, opts)` 델타/tool_use 방출 + `summarizeToolUse` |
| 워커 수정 | `src/sdk/sessionOptions.ts` | `includePartialMessages: true` |
| 워커 수정 | `src/runner/interviewRunner.ts` | poster 생성·배선·정리 |
| FE 생성 | `frontend/components/chat/PendingBubble.vue` | 진행 버블 (순수 프레젠테이션) |
| FE 수정 | `frontend/composables/useInterviewStream.ts` | `pending` 누적/소거 |
| FE 수정 | `frontend/components/InterviewPanel.vue` | PendingBubble 렌더 + 스크롤 정책 |

작업 브랜치: `feat/interview-live-activity` (스펙 커밋 위에 계속). 배포 순서 제약(Java→워커→프론트)과 동일하게 태스크도 Java부터.

---

### Task 1: Java DTO + `InterviewStreamService.pushActivity`

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/ActivityEvent.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/WorkerActivityRequest.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewStreamService.java`
- Test: `src/test/java/com/hamonsoft/netismaker/service/InterviewStreamServiceTest.java`

**Interfaces:**
- Consumes: 기존 `InterviewStreamService.sendJson(Long, String, Object)` (private — 같은 클래스 내 재사용)
- Produces: `record ActivityEvent(long seq, String type, String label, String detail, String content)` · `record WorkerActivityRequest(List<ActivityEvent> events)` · `void pushActivity(Long sessionId, WorkerActivityRequest batch)` — Task 2 컨트롤러가 사용

- [ ] **Step 1: 실패하는 테스트 작성** — `InterviewStreamServiceTest.java`에 아래 2개 테스트를 추가한다 (기존 import에 `com.hamonsoft.netismaker.dto.ActivityEvent`, `com.hamonsoft.netismaker.dto.WorkerActivityRequest` 추가):

```java
    /**
     * 와이어 계약 잠금: activity 페이로드 = {events:[{seq,type,label,detail,content}]} JSON.
     * 프론트(useInterviewStream.onActivity)가 data.events 배열을 순회한다.
     */
    @Test
    void activity_batch_serializes_to_frontend_json_contract() throws Exception {
        var batch = new WorkerActivityRequest(List.of(
                new ActivityEvent(1, "tool", "Read", "src/pages/login.vue", null),
                new ActivityEvent(2, "text", null, null, "이제 인증 흐름을")));
        JsonNode n = json.readTree(json.writeValueAsString(batch));
        assertThat(n.get("events")).hasSize(2);
        assertThat(n.get("events").get(0).get("seq").asLong()).isEqualTo(1);
        assertThat(n.get("events").get(0).get("type").asText()).isEqualTo("tool");
        assertThat(n.get("events").get(0).get("label").asText()).isEqualTo("Read");
        assertThat(n.get("events").get(0).get("detail").asText()).isEqualTo("src/pages/login.vue");
        assertThat(n.get("events").get(1).get("content").asText()).isEqualTo("이제 인증 흐름을");
    }

    /** transient 계약: 구독자 유무와 무관하게 예외 없이 동작, DB 접근 없음(turnRepo 미호출). */
    @Test
    void pushActivity_is_safe_with_and_without_subscribers() {
        when(turnRepo.findBySessionIdOrderBySeqAsc(77L)).thenReturn(List.of());
        var svc = new InterviewStreamService(turnRepo, json);
        var batch = new WorkerActivityRequest(List.of(new ActivityEvent(1, "thinking", null, null, "음…")));
        svc.pushActivity(77L, batch);   // 구독자 없음 — no-op
        svc.subscribe(77L);
        svc.pushActivity(77L, batch);   // 구독자 1 — 예외 없이 전달
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*InterviewStreamServiceTest'`
Expected: COMPILE FAILURE — `ActivityEvent`/`WorkerActivityRequest`/`pushActivity` 심볼 없음

- [ ] **Step 3: DTO 2개 생성**

`src/main/java/com/hamonsoft/netismaker/dto/ActivityEvent.java`:

```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SSE event:activity 페이로드의 원소 — 워커가 중계하는 에이전트 진행 활동 1건.
 * type: tool(도구 실행, label/detail) | text(내레이션 델타, content) | thinking(추론 델타, content).
 * transient — DB 미저장·replay 없음. 확정 내용은 question 턴이 대체한다.
 */
public record ActivityEvent(
        long seq,
        @NotBlank @Size(max = 16) String type,
        @Size(max = 100) String label,
        @Size(max = 200) String detail,
        @Size(max = 4096) String content
) {}
```

`src/main/java/com/hamonsoft/netismaker/dto/WorkerActivityRequest.java`:

```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body for POST /worker/interviews/{id}/activity — 활동 이벤트 배치 (스펙 §4.2, 최대 100건). */
public record WorkerActivityRequest(
        @NotEmpty @Size(max = 100) @Valid List<ActivityEvent> events
) {}
```

- [ ] **Step 4: `InterviewStreamService`에 `pushActivity` 추가** — `pushPlanReady` 메서드 바로 아래에 삽입, import에 `com.hamonsoft.netismaker.dto.WorkerActivityRequest` 추가:

```java
    /**
     * activity 페이로드 = {events:[{seq,type,label,detail,content}]} JSON — 워커 배치 그대로 fan-out.
     * transient: DB 미저장, subscribe()의 replay 대상 아님 (확정 내용은 question 턴이 대체).
     */
    public void pushActivity(Long sessionId, WorkerActivityRequest batch) {
        sendJson(sessionId, "activity", batch);
    }
```

그리고 클래스 상단 javadoc의 `이벤트: question | design | plan_ready | status | done` 줄을 다음으로 교체:

```java
 *  이벤트: question | design | plan_ready | status | done | activity
```

또한 `LOCKED CONTRACT v2:` 블록 끝에 한 줄 추가:

```java
 *    activity 페이로드 = {events:[{seq,type,label,detail,content}]} JSON — transient(미저장)·no-replay.
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*InterviewStreamServiceTest'`
Expected: PASS (기존 3개 + 신규 2개)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/dto/ActivityEvent.java \
        src/main/java/com/hamonsoft/netismaker/dto/WorkerActivityRequest.java \
        src/main/java/com/hamonsoft/netismaker/service/InterviewStreamService.java \
        src/test/java/com/hamonsoft/netismaker/service/InterviewStreamServiceTest.java
git commit -m "feat: SSE activity 이벤트 — DTO + InterviewStreamService.pushActivity (transient)"
```

---

### Task 2: Java 워커 엔드포인트 `POST /worker/interviews/{id}/activity`

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerApiIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1의 `WorkerActivityRequest`, `InterviewStreamService.pushActivity(Long, WorkerActivityRequest)`
- Produces: `POST /worker/interviews/{id}/activity?workerId=…` (X-Worker-API-Key, body `{events:[…]}`, 204) — Task 4 워커 클라이언트가 호출

- [ ] **Step 1: 실패하는 통합 테스트 작성** — `InterviewWorkerApiIntegrationTest.java`에 추가 (RUN_TESTCONTAINERS 게이트 클래스):

```java
    @Test
    void activity_returns_204_and_does_not_touch_session_state() throws Exception {
        mvc.perform(post("/worker/interviews/" + sid + "/activity").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"events\":[{\"seq\":1,\"type\":\"tool\",\"label\":\"Read\",\"detail\":\"a.ts\"},"
                                + "{\"seq\":2,\"type\":\"text\",\"content\":\"이제…\"}]}"))
                .andExpect(status().isNoContent());
        // transient 계약: 세션 상태/턴 무변화 (QUEUED 그대로, 턴 0건)
        assertThat(sessionRepo.findById(sid).orElseThrow().getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(turnRepo.findBySessionIdOrderBySeqAsc(sid)).isEmpty();
    }

    @Test
    void activity_rejects_oversized_batch_with_400() throws Exception {
        String events = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(i -> "{\"seq\":" + i + ",\"type\":\"text\",\"content\":\"x\"}")
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/worker/interviews/" + sid + "/activity").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"events\":[" + events + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activity_without_worker_key_is_rejected() throws Exception {
        mvc.perform(post("/worker/interviews/" + sid + "/activity")
                        .param("workerId", "iw-1")
                        .contentType(APPLICATION_JSON)
                        .content("{\"events\":[{\"seq\":1,\"type\":\"text\",\"content\":\"x\"}]}"))
                .andExpect(status().is4xxClientError()); // InterviewSecuritySurfaceTest의 claim 테스트와 동일 기대
    }
```

- [ ] **Step 2: 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewWorkerApiIntegrationTest'`
Expected: 신규 **2개** FAIL (404 — 엔드포인트 없음). `activity_without_worker_key_is_rejected`는 4xx 단언 특성상 미구현 404에서도 처음부터 PASS — 시큐리티 필터체인(`SecurityConfig`의 `/worker/**` securityMatcher가 핸들러 도달 전에 거부) 회귀 가드로 유지한다. "3개 FAIL이 아니네"라고 디버깅에 빠지지 말 것. Docker 불가 환경이면 이 클래스는 skip되므로 Step 4 이후 CI에서 검증됨을 태스크 결과에 명시.

- [ ] **Step 3: 컨트롤러 핸들러 추가** — `InterviewWorkerController.java`의 `heartbeat` 메서드 위에 삽입, import에 `com.hamonsoft.netismaker.dto.WorkerActivityRequest` 추가. 클래스 상단 javadoc 엔드포인트 목록에도 한 줄 추가(`plan` 줄 아래):

```java
 *   POST /worker/interviews/{id}/activity?workerId=… ─► SSE activity fan-out (transient, DB 미접근)
```

```java
    /**
     * 활동 스트림 중계 (transient) — DB 미접근이 계약: 300ms 배치 고빈도 호출이라
     * 커넥션 풀을 만지지 않는다(과거 SSE+OSIV 풀 고갈 사고 재발 방지). 세션 소유 검증도
     * 생략 — 신뢰 경계는 X-Worker-API-Key(다른 워커 엔드포인트와 동일).
     */
    @PostMapping("/{id}/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activity(@PathVariable Long id, @RequestParam String workerId,
                         @RequestBody @Valid WorkerActivityRequest req) {
        interviewStream.pushActivity(id, req);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewWorkerApiIntegrationTest'` (Docker 가용 시)
Expected: PASS. 이어서 `./gradlew test` 전체 그린 확인.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/InterviewWorkerController.java \
        src/test/java/com/hamonsoft/netismaker/controller/InterviewWorkerApiIntegrationTest.java
git commit -m "feat: POST /worker/interviews/{id}/activity — 활동 배치 SSE 중계 (DB 미접근)"
```

---

### Task 3: 스파이크 — `stream_event` 실물 shape 캡처·검증

**Files:**
- Create: `netismaker-interview-service/scripts/spikeStream.ts`
- Create: `netismaker-interview-service/test/fixtures/STREAM_SHAPE_FINDINGS.md` (검증 결과 기록)

**Interfaces:**
- Consumes: 기존 `realQuery`, `buildOptions`, `resolveClaudeCli`
- Produces: 검증된 shape 체크리스트 — Task 5의 픽스처(`streamingQuestionStream`)가 이 결과를 따른다

이 태스크는 **실 claude CLI 실행**(운영자 macOS, 구독 로그인)이 필요하다. 실행 불가 환경이면: 타입 정의 실측(Global Constraints에 파일:줄로 명시 — 봉투는 agent-sdk `sdk.d.ts`, 델타는 의존성 `@anthropic-ai/sdk`의 `messages.d.ts`)을 근거로 Task 5를 진행하고, FINDINGS.md에 "런타임 미검증 — Task 11 풀스택 스모크에서 검증" 이라고 기록한 뒤 넘어간다 (스모크가 최종 게이트).

- [ ] **Step 1: 캡처 스크립트 작성** — `scripts/spikeStream.ts`:

```ts
/**
 * 활동 스트림 스파이크 — includePartialMessages=true로 1턴 돌려 stream_event 실물 shape 캡처.
 * 실행(운영자 macOS, claude 구독 로그인 필요):
 *   mkdir -p /tmp/spike-repo && cd /tmp/spike-repo && git init -q && echo 'hello' > a.md && git add -A && git commit -qm init
 *   cd <netismaker-interview-service> && set -a && source .env 2>/dev/null; set +a
 *   node --import tsx scripts/spikeStream.ts /tmp/spike-repo > /tmp/stream-capture.jsonl
 * 확인 항목(스펙 §5.0):
 *   ① {type:'stream_event', event, parent_tool_use_id} 봉투
 *   ② event.type==='content_block_delta' && delta.type: text_delta{text} / thinking_delta{thinking} / signature_delta
 *   ③ assistant 메시지 content의 tool_use {name, input.file_path}
 *   ④ effort 지정 시 thinking_delta 발생 여부 (안 나오면 프로덕션에서도 thinking 섹션이 안 뜰 뿐 — 기능 자체는 유효)
 */
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { buildOptions } from '../src/sdk/sessionOptions.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';

const workDir = process.argv[2] ?? process.cwd();
const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);

async function* prompt(
  text: string,
): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield { type: 'user', message: { role: 'user', content: text } };
}

async function capture(): Promise<void> {
  const options = {
    ...buildOptions({
      superpowersPluginPath: process.env.SUPERPOWERS_PLUGIN_PATH ?? '',
      workDir,
      claudeCliPath,
      claudeSessionId: null,
      effort: 'high', // thinking 델타 유도
    }),
    // Task 7 전이라 buildOptions에 아직 없어도 캡처는 가능해야 한다 — 여기서 직접 켠다.
    includePartialMessages: true,
  };
  const stream = realQuery({
    prompt: prompt('이 레포의 파일 하나를 Read 도구로 읽고, 내용을 한 문장으로 요약해줘.'),
    options,
  });
  for await (const msg of stream) {
    // eslint-disable-next-line no-console
    console.log(JSON.stringify(msg));
  }
}

void capture();
```

- [ ] **Step 2: 실행·캡처**

Run (스크립트 상단 주석의 명령 그대로):
```bash
mkdir -p /tmp/spike-repo && cd /tmp/spike-repo && git init -q && echo 'hello netis' > a.md && git add -A && git commit -qm init
cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service
set -a && source .env 2>/dev/null; set +a
node --import tsx scripts/spikeStream.ts /tmp/spike-repo > /tmp/stream-capture.jsonl
```
Expected: 종료코드 0, `/tmp/stream-capture.jsonl`에 JSONL 수십~수백 줄.

- [ ] **Step 3: shape 검증** — 4개 확인 항목을 grep으로 검증:

```bash
grep -c '"type":"stream_event"' /tmp/stream-capture.jsonl                      # ① > 0
grep '"content_block_delta"' /tmp/stream-capture.jsonl | head -3              # ② delta.type/필드명 육안 확인
grep '"tool_use"' /tmp/stream-capture.jsonl | head -2                          # ③ name/input.file_path 확인
grep -c '"thinking_delta"' /tmp/stream-capture.jsonl                           # ④ (0이어도 실패 아님 — 기록만)
grep '"parent_tool_use_id"' /tmp/stream-capture.jsonl | head -1                # ① 봉투 필드 확인
```

- [ ] **Step 4: 결과 기록** — `test/fixtures/STREAM_SHAPE_FINDINGS.md`에 확인 항목 ①~④의 실측 결과(대표 JSON 1줄씩 발췌, 가정과 다른 점)를 기록한다. **가정(Global Constraints의 sdk.d.ts shape)과 다르면 여기서 STOP — 스펙 §5.2/Task 5 픽스처를 실측에 맞게 고치고 진행한다.** 원본 캡처(`/tmp/stream-capture.jsonl`)는 커밋하지 않는다(모델 출력 포함, 용량).

- [ ] **Step 5: Commit**

```bash
git add netismaker-interview-service/scripts/spikeStream.ts \
        netismaker-interview-service/test/fixtures/STREAM_SHAPE_FINDINGS.md
git commit -m "chore: stream_event 실물 shape 캡처 스파이크 + 검증 결과"
```

---

### Task 4: 워커 타입 + `JavaApiClient.postActivity`

**Files:**
- Modify: `netismaker-interview-service/src/types.ts`
- Modify: `netismaker-interview-service/src/api/javaClient.ts`
- Test: `netismaker-interview-service/test/javaClient.test.ts`

**Interfaces:**
- Consumes: 기존 `JavaApiClient` 컨벤션 (`wq()` workerId 쿼리파라미터, `X-Worker-API-Key` 헤더)
- Produces: `type ActivityEventType = 'tool'|'text'|'thinking'` · `interface ActivityInput {type, label?, detail?, content?}` · `interface ActivityEvent extends ActivityInput {seq: number}` · `interface WorkerActivityRequest {events: ActivityEvent[]}` · `class HttpStatusError extends Error {status: number}` · `postActivity(id: number, body: WorkerActivityRequest): Promise<void>` — Task 5·6·7이 사용

- [ ] **Step 1: 실패하는 테스트 작성** — `test/javaClient.test.ts`에 추가 (import에 `HttpStatusError` 추가: `import { JavaApiClient, HttpStatusError } from '../src/api/javaClient.js';`):

```ts
  it('postActivity POSTs the batch to /worker/interviews/{id}/activity?workerId=...', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await client.postActivity(42, {
      events: [
        { seq: 1, type: 'tool', label: 'Read', detail: 'src/app.ts' },
        { seq: 2, type: 'text', content: '이제 ' },
      ],
    });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://api:8090/worker/interviews/42/activity?workerId=iw-1');
    expect(init.method).toBe('POST');
    expect(init.headers['X-Worker-API-Key']).toBe('KEY');
    expect(JSON.parse(init.body).events).toHaveLength(2);
  });

  it('postActivity throws HttpStatusError with the status (404 → poster가 비활성화 판단)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await expect(
      client.postActivity(42, { events: [{ seq: 1, type: 'text', content: 'x' }] }),
    ).rejects.toSatisfy((e: unknown) => e instanceof HttpStatusError && (e as HttpStatusError).status === 404);
  });
```

- [ ] **Step 2: 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/javaClient.test.ts`
Expected: FAIL — `postActivity`/`HttpStatusError` 없음

- [ ] **Step 3: `types.ts`에 활동 타입 추가** — `WorkerQuestionRequest` 위에 삽입:

```ts
/** SSE activity 와이어 계약의 활동 type (스펙 §4.1). */
export type ActivityEventType = 'tool' | 'text' | 'thinking';

/** relay → ActivityPoster 콜백 입력 (seq는 poster가 부여). */
export interface ActivityInput {
  type: ActivityEventType;
  /** tool 전용: 'Read'|'Grep'|'Glob'|'Skill'|'환경 준비'… */
  label?: string;
  /** tool 전용: 파일경로/패턴 (workDir prefix 제거, 120자 절단) */
  detail?: string;
  /** text/thinking 전용: 델타 텍스트 청크 */
  content?: string;
}

/** 와이어 ActivityEvent = ActivityInput + 워커 run() 내 단조증가 seq. */
export interface ActivityEvent extends ActivityInput {
  seq: number;
}

/** Body for POST /worker/interviews/{id}/activity — 배치 최대 100건 (Java @Size와 동기). */
export interface WorkerActivityRequest {
  events: ActivityEvent[];
}
```

- [ ] **Step 4: `javaClient.ts`에 `HttpStatusError` + `postActivity` 추가** — import 줄을 다음으로 교체:

```ts
import type {
  InterviewClaimResponse,
  WorkerActivityRequest,
  WorkerPlanRequest,
  WorkerQuestionRequest,
} from '../types.js';
```

클래스 정의 위에 에러 타입 추가:

```ts
/** HTTP 상태를 보존하는 에러 — ActivityPoster가 404(구 Java)를 식별해 비활성화한다. */
export class HttpStatusError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}
```

`postPlan` 메서드 아래에 추가:

```ts
  /** 활동 배치 전송 — 실패 처리(폐기/비활성화)는 호출자(ActivityPoster) 책임. */
  async postActivity(id: number, body: WorkerActivityRequest): Promise<void> {
    const res = await this.fetchFn(this.url(`/worker/interviews/${id}/activity?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new HttpStatusError(res.status, `activity failed: ${res.status}`);
  }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd netismaker-interview-service && npx vitest run test/javaClient.test.ts`
Expected: PASS (기존 6개 + 신규 2개)

- [ ] **Step 6: Commit**

```bash
git add netismaker-interview-service/src/types.ts netismaker-interview-service/src/api/javaClient.ts \
        netismaker-interview-service/test/javaClient.test.ts
git commit -m "feat: 워커 활동 타입 + JavaApiClient.postActivity (HttpStatusError)"
```

---

### Task 5: `messageRelay` — 델타/tool_use 활동 방출

**Files:**
- Modify: `netismaker-interview-service/src/runner/messageRelay.ts`
- Modify: `netismaker-interview-service/test/fixtures/sdkMessages.ts`
- Test: `netismaker-interview-service/test/messageRelay.test.ts`

**Interfaces:**
- Consumes: Task 4의 `ActivityInput`; Task 3에서 검증된 stream_event shape
- Produces: `relay(stream, opts?: { onActivity?: (e: ActivityInput) => void; workDir?: string }): Promise<RelayResult>` (기존 `relay(stream)` 호출 100% 호환) · `summarizeToolUse(name: string, input: Record<string, unknown> | undefined, workDir?: string): string | undefined` — Task 7이 사용

- [ ] **Step 1: 픽스처 추가** — `test/fixtures/sdkMessages.ts` 끝에 추가:

```ts
/**
 * includePartialMessages 턴: thinking·text 델타 + tool_use + 서브에이전트 노이즈 + 최종 질문.
 * shape 근거: @anthropic-ai/claude-agent-sdk@0.2.117 sdk.d.ts의 SDKPartialAssistantMessage
 * ({type:'stream_event', event, parent_tool_use_id}) + BetaTextDelta{text}/BetaThinkingDelta{thinking}.
 * 실물 재검증: scripts/spikeStream.ts → test/fixtures/STREAM_SHAPE_FINDINGS.md.
 */
export const streamingQuestionStream = (): AsyncIterable<SdkMessage> =>
  gen(
    { type: 'system', subtype: 'init', session_id: 'sess-stream-1' },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'repo부터 봐야' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'sig==' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: '레포를 먼저 ' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: '읽겠습니다' } },
    },
    {
      type: 'assistant',
      message: {
        content: [
          { type: 'text', text: '레포를 먼저 읽겠습니다' },
          { type: 'tool_use', id: 'tu-1', name: 'Read', input: { file_path: '/work/repo/src/app.ts' } },
        ],
      },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: 'tu-x', // 서브에이전트 스트림 — 활동으로 방출하면 안 됨
      event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'SUBAGENT NOISE' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Which columns?' } },
    },
    { type: 'assistant', message: { content: [{ type: 'text', text: 'Which columns?' }] } },
    { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.2 }, duration_ms: 900 },
  );
```

- [ ] **Step 2: 실패하는 테스트 작성** — `test/messageRelay.test.ts`를 다음 내용으로 확장 (기존 describe 유지, import 교체):

```ts
import { describe, expect, it } from 'vitest';
import { relay, summarizeToolUse } from '../src/runner/messageRelay.js';
import { questionStream, streamingQuestionStream } from './fixtures/sdkMessages.js';
import type { ActivityInput } from '../src/types.js';
```

기존 describe 아래에 추가:

```ts
describe('relay — activity callback (includePartialMessages)', () => {
  it('emits thinking/text deltas + tool_use in stream order; skips signature + subagent deltas', async () => {
    const events: ActivityInput[] = [];
    const out = await relay(streamingQuestionStream(), {
      onActivity: (e) => events.push(e),
      workDir: '/work/repo',
    });
    expect(events).toEqual([
      { type: 'thinking', content: 'repo부터 봐야' },
      { type: 'text', content: '레포를 먼저 ' },
      { type: 'text', content: '읽겠습니다' },
      { type: 'tool', label: 'Read', detail: 'src/app.ts' },
      { type: 'text', content: 'Which columns?' },
    ]);
    // 최종 assistantText는 기존 계약 그대로 — postQuestion/planHarvest의 원천은 델타가 아니라 assistant 메시지
    expect(out.assistantText).toBe('레포를 먼저 읽겠습니다\n\nWhich columns?');
    expect(out.sessionId).toBe('sess-stream-1');
    expect(out.costUsd).toBeCloseTo(0.2);
  });

  it('without onActivity, behaves exactly as before (기존 계약 무변경)', async () => {
    const out = await relay(streamingQuestionStream());
    expect(out.assistantText).toBe('레포를 먼저 읽겠습니다\n\nWhich columns?');
    expect(out.completed).toBe(true);
  });
});

describe('summarizeToolUse', () => {
  it('Read→file_path(workDir 상대화), Grep/Glob→pattern, Skill→skill', () => {
    expect(summarizeToolUse('Read', { file_path: '/w/repo/src/a.ts' }, '/w/repo')).toBe('src/a.ts');
    expect(summarizeToolUse('Grep', { pattern: 'TODO' })).toBe('TODO');
    expect(summarizeToolUse('Glob', { pattern: '**/*.vue' })).toBe('**/*.vue');
    expect(summarizeToolUse('Skill', { skill: 'brainstorming' })).toBe('brainstorming');
  });

  it('120자 초과는 …로 절단, 미지 도구/입력 없음은 undefined', () => {
    const long = 'x'.repeat(200);
    expect(summarizeToolUse('Grep', { pattern: long })!.length).toBe(121); // 120 + '…'
    expect(summarizeToolUse('WebFetch', { url: 'http://x' })).toBeUndefined();
    expect(summarizeToolUse('Read', undefined)).toBeUndefined();
  });
});
```

- [ ] **Step 3: 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/messageRelay.test.ts`
Expected: FAIL — `summarizeToolUse` 미정의, relay 2번째 인자 무시로 events 빈 배열

- [ ] **Step 4: `messageRelay.ts` 구현** — 파일 전체를 다음으로 교체:

```ts
import type { SdkMessage } from '../sdk/sdkAdapter.js';
import type { ActivityInput } from '../types.js';

export interface RelayResult {
  sessionId: string | null;
  assistantText: string;
  costUsd: number;
  durationMs: number;
  completed: boolean;
}

export interface RelayOpts {
  /** 활동(도구/텍스트 델타/thinking 델타) 실시간 콜백 — 미전달 시 기존과 100% 동일 동작. */
  onActivity?: (e: ActivityInput) => void;
  /** tool detail의 workDir prefix 상대화용. */
  workDir?: string;
}

const MAX_DETAIL = 120;

/** tool_use input에서 사람이 읽을 detail 추출 (Read→file_path, Grep/Glob→pattern, Skill→skill). */
export function summarizeToolUse(
  name: string,
  input: Record<string, unknown> | undefined,
  workDir?: string,
): string | undefined {
  if (!input) return undefined;
  const raw =
    name === 'Read'
      ? input.file_path
      : name === 'Grep' || name === 'Glob'
        ? input.pattern
        : name === 'Skill'
          ? input.skill
          : undefined;
  if (typeof raw !== 'string' || raw.length === 0) return undefined;
  let detail = raw;
  if (workDir && detail.startsWith(workDir)) detail = detail.slice(workDir.length).replace(/^\//, '');
  return detail.length > MAX_DETAIL ? `${detail.slice(0, MAX_DETAIL)}…` : detail;
}

type ContentBlock = { type: string; text?: string; name?: string; input?: Record<string, unknown> };

function blocks(msg: SdkMessage): ContentBlock[] {
  const content = (msg.message as { content?: ContentBlock[] } | undefined)?.content;
  return Array.isArray(content) ? content : [];
}

function extractText(msg: SdkMessage): string {
  return blocks(msg)
    .filter((b) => b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text as string)
    .join('\n');
}

/** stream_event(SDKPartialAssistantMessage)에서 text/thinking 델타를 활동으로 변환. 그 외 무시. */
function deltaActivity(msg: SdkMessage): ActivityInput | null {
  // parent_tool_use_id가 있으면 서브에이전트 스트림 — 최상위 활동이 아니다.
  if ((msg as { parent_tool_use_id?: string | null }).parent_tool_use_id) return null;
  const event = msg.event as
    | { type?: string; delta?: { type?: string; text?: string; thinking?: string } }
    | undefined;
  if (event?.type !== 'content_block_delta') return null;
  if (event.delta?.type === 'text_delta' && event.delta.text) {
    return { type: 'text', content: event.delta.text };
  }
  if (event.delta?.type === 'thinking_delta' && event.delta.thinking) {
    return { type: 'thinking', content: event.delta.thinking };
  }
  return null; // signature_delta / input_json_delta 등
}

/**
 * Drains one SDK turn: captures session_id from init, joins assistant text, reads result usage.
 * opts.onActivity가 있으면 진행 활동(델타·tool_use)을 실시간 방출한다 — assistantText 계약은 불변:
 * 텍스트는 델타로 이미 흘렸으므로 assistant 메시지에서 활동으로 재방출하지 않는다(중복 방지).
 */
export async function relay(stream: AsyncIterable<SdkMessage>, opts?: RelayOpts): Promise<RelayResult> {
  let sessionId: string | null = null;
  const parts: string[] = [];
  let costUsd = 0;
  let durationMs = 0;
  let completed = false;
  for await (const msg of stream) {
    if (msg.type === 'system' && msg.subtype === 'init') {
      sessionId = (msg.session_id as string) ?? null;
    } else if (msg.type === 'stream_event') {
      if (!opts?.onActivity) continue;
      const activity = deltaActivity(msg);
      if (activity) opts.onActivity(activity);
    } else if (msg.type === 'assistant') {
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
      const usage = msg.usage as { total_cost_usd?: number } | undefined;
      costUsd = usage?.total_cost_usd ?? 0;
      durationMs = (msg.duration_ms as number) ?? 0;
      completed = true;
    }
  }
  return { sessionId, assistantText: parts.join('\n\n'), costUsd, durationMs, completed };
}
```

주의: `detail`이 `undefined`인 tool 활동은 `{type:'tool', label, detail: undefined}`로 방출된다 — vitest의 `toEqual`은 undefined 값 프로퍼티를 **무시하므로**(`toStrictEqual`만 구분) 이 형태도 `{type,label}` 기대값과 일치한다. detail 조건부 생략 코드는 불필요하다. 위 테스트는 명시성을 위해 detail이 항상 존재하는 Read 케이스로 단언한다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd netismaker-interview-service && npx vitest run test/messageRelay.test.ts`
Expected: PASS (기존 1개 + 신규 4개)

- [ ] **Step 6: Commit**

```bash
git add netismaker-interview-service/src/runner/messageRelay.ts \
        netismaker-interview-service/test/fixtures/sdkMessages.ts \
        netismaker-interview-service/test/messageRelay.test.ts
git commit -m "feat: messageRelay 활동 방출 — 텍스트/thinking 델타 + tool_use (assistantText 계약 불변)"
```

---

### Task 6: `ActivityPoster` — 배치·직렬·실패격리 전송기

**Files:**
- Create: `netismaker-interview-service/src/runner/activityPoster.ts`
- Test: `netismaker-interview-service/test/activityPoster.test.ts`

**Interfaces:**
- Consumes: Task 4의 `postActivity`, `HttpStatusError`, `ActivityInput`/`ActivityEvent`
- Produces: `class ActivityPoster { constructor(client: Pick<JavaApiClient,'postActivity'>, sessionId: number, opts?: {flushIntervalMs?: number; maxQueue?: number}); start(): void; push(e: ActivityInput): void; stop(): Promise<void> }` — Task 7이 사용

- [ ] **Step 1: 실패하는 테스트 작성** — `test/activityPoster.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityPoster } from '../src/runner/activityPoster.js';
import { HttpStatusError } from '../src/api/javaClient.js';

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function makeClient() {
  return { postActivity: vi.fn().mockResolvedValue(undefined) };
}

describe('ActivityPoster', () => {
  it('interval마다 배치 flush + 단조증가 seq 부여', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'tool', label: 'Read', detail: 'a.ts' });
    p.push({ type: 'thinking', content: '음' });
    expect(client.postActivity).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(300);
    expect(client.postActivity).toHaveBeenCalledTimes(1);
    expect(client.postActivity).toHaveBeenCalledWith(42, {
      events: [
        { seq: 1, type: 'tool', label: 'Read', detail: 'a.ts' },
        { seq: 2, type: 'thinking', content: '음' },
      ],
    });
    await p.stop();
  });

  it('동일 type 연속 text/thinking 델타는 content 병합', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: 'a' });
    p.push({ type: 'text', content: 'b' });
    p.push({ type: 'thinking', content: 'x' });
    p.push({ type: 'text', content: 'c' });
    await vi.advanceTimersByTimeAsync(300);
    expect(client.postActivity).toHaveBeenCalledWith(42, {
      events: [
        { seq: 1, type: 'text', content: 'ab' },
        { seq: 2, type: 'thinking', content: 'x' },
        { seq: 3, type: 'text', content: 'c' },
      ],
    });
    await p.stop();
  });

  it('큐가 maxQueue에 닿으면 interval 전에 즉시 flush', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300, maxQueue: 2 });
    p.start();
    p.push({ type: 'tool', label: 'Read', detail: 'a.ts' });
    p.push({ type: 'tool', label: 'Grep', detail: 'x' }); // 병합 안 됨(tool) → 2건 도달
    await vi.advanceTimersByTimeAsync(0); // 마이크로태스크만 소진
    expect(client.postActivity).toHaveBeenCalledTimes(1);
    await p.stop();
  });

  it('직렬 전송: 두 번째 배치는 첫 POST 완료를 기다린다', async () => {
    const client = makeClient();
    let resolveFirst!: () => void;
    client.postActivity
      .mockImplementationOnce(() => new Promise<void>((r) => (resolveFirst = r)))
      .mockResolvedValue(undefined);
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '1' });
    await vi.advanceTimersByTimeAsync(300); // 1차 flush — in flight
    p.push({ type: 'text', content: '2' });
    await vi.advanceTimersByTimeAsync(300); // 2차 flush 시도 — 큐는 비웠지만 POST는 대기
    expect(client.postActivity).toHaveBeenCalledTimes(1);
    resolveFirst();
    await vi.advanceTimersByTimeAsync(0);
    expect(client.postActivity).toHaveBeenCalledTimes(2);
    await p.stop();
  });

  it('전송 실패 배치는 폐기(경고 1회) — throw하지 않는다', async () => {
    const client = makeClient();
    client.postActivity.mockRejectedValue(new Error('boom'));
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '1' });
    await vi.advanceTimersByTimeAsync(300);
    p.push({ type: 'text', content: '2' });
    await vi.advanceTimersByTimeAsync(300);
    expect(client.postActivity).toHaveBeenCalledTimes(2); // 계속 시도는 함(일시 장애 대비)
    expect(console.warn).toHaveBeenCalledTimes(1); // 경고는 1회만
    await p.stop();
  });

  it('404(구 Java)면 남은 세션 동안 비활성화', async () => {
    const client = makeClient();
    client.postActivity.mockRejectedValue(new HttpStatusError(404, 'activity failed: 404'));
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '1' });
    await vi.advanceTimersByTimeAsync(300);
    p.push({ type: 'text', content: '2' });
    await vi.advanceTimersByTimeAsync(600);
    expect(client.postActivity).toHaveBeenCalledTimes(1); // 두 번째 배치는 전송 안 함
    await p.stop();
  });

  it('stop()은 잔여 큐 flush 후 in-flight 완료까지 대기', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '남은 것' });
    await p.stop(); // interval 미도래 — stop이 flush
    expect(client.postActivity).toHaveBeenCalledTimes(1);
  });

  it('병합은 4000자 상한 — 초과분은 새 항목으로 분리된다 (Java @Size(4096) 400 방지)', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'thinking', content: 'x'.repeat(3999) });
    p.push({ type: 'thinking', content: 'yy' }); // 3999+2 > 4000 → 병합 금지, 새 항목
    await vi.advanceTimersByTimeAsync(300);
    const batch = client.postActivity.mock.calls[0]![1] as { events: Array<{ content?: string }> };
    expect(batch.events).toHaveLength(2);
    expect(batch.events[0].content).toHaveLength(3999);
    expect(batch.events[1].content).toBe('yy');
    await p.stop();
  });

  it('개별 content는 4096자, label은 100자로 절단 (Java @Size와 동기)', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: 'z'.repeat(5000) });
    p.push({ type: 'tool', label: 'L'.repeat(150), detail: 'a' });
    await vi.advanceTimersByTimeAsync(300);
    const batch = client.postActivity.mock.calls[0]![1] as {
      events: Array<{ content?: string; label?: string }>;
    };
    expect(batch.events[0].content).toHaveLength(4096);
    expect(batch.events[1].label).toHaveLength(100);
    await p.stop();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/activityPoster.test.ts`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: `activityPoster.ts` 구현**:

```ts
import { HttpStatusError, type JavaApiClient } from '../api/javaClient.js';
import type { ActivityEvent, ActivityInput } from '../types.js';

export interface ActivityPosterOpts {
  /** flush 주기 (기본 300ms — 스펙 §5.3). */
  flushIntervalMs?: number;
  /** 이 개수 도달 시 interval 전 즉시 flush (기본 20 — Java @Size(max=100)보다 충분히 작게). */
  maxQueue?: number;
}

// Java ActivityEvent @Size 상한과 동기 — 위반 시 400으로 배치 전체가 폐기되므로 생산 측에서 절단한다.
// 병합 상한(4000)은 4096보다 여유를 둔다: SDK 자식프로세스 stdout이 델타 다수를 한 chunk로 실어오면
// for-await 드레인이 setInterval을 기아시켜 연속 델타가 한 항목으로 병합될 수 있다(검증 리뷰 MAJOR).
const MAX_CONTENT = 4096;
const MAX_MERGED = 4000;
const MAX_LABEL = 100;

/**
 * 활동 이벤트 배치 전송기 (스펙 §5.3).
 * - push()는 큐 적재만 — 어떤 경로로도 throw하지 않는다 (활동 스트림은 인터뷰를 절대 죽이지 않는다).
 * - 배치 내 동일 type 연속 text/thinking 델타는 content 병합(페이로드 수 최소화) — 단 병합 4000자,
 *   개별 content 4096자·label 100자 상한(Java @Size 동기, 초과 시 배치 전체 400 폐기 방지).
 * - in-flight POST 1개 직렬 체이닝(순서 보장). 실패 배치는 폐기 + 경고 1회.
 * - 404(구 Java, activity 엔드포인트 부재)면 남은 세션 동안 전송 비활성화.
 */
export class ActivityPoster {
  private queue: ActivityEvent[] = [];
  private seq = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private inFlight: Promise<void> = Promise.resolve();
  private disabled = false;
  private warned = false;

  constructor(
    private readonly client: Pick<JavaApiClient, 'postActivity'>,
    private readonly sessionId: number,
    private readonly opts: ActivityPosterOpts = {},
  ) {}

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.flush(), this.opts.flushIntervalMs ?? 300);
  }

  push(e: ActivityInput): void {
    if (this.disabled) return;
    const content = typeof e.content === 'string' ? e.content.slice(0, MAX_CONTENT) : e.content;
    const label = typeof e.label === 'string' ? e.label.slice(0, MAX_LABEL) : e.label;
    const last = this.queue[this.queue.length - 1];
    if (
      last &&
      last.type === e.type &&
      (e.type === 'text' || e.type === 'thinking') &&
      typeof last.content === 'string' &&
      typeof content === 'string' &&
      last.content.length + content.length <= MAX_MERGED
    ) {
      last.content += content;
      return;
    }
    this.queue.push({ ...e, label, content, seq: ++this.seq });
    if (this.queue.length >= (this.opts.maxQueue ?? 20)) this.flush();
  }

  private flush(): void {
    if (this.disabled || this.queue.length === 0) return;
    const events = this.queue;
    this.queue = [];
    // 직렬 체이닝: 이전 POST 완료 후 다음 배치 — 순서 보장, 동시 요청 없음.
    this.inFlight = this.inFlight.then(async () => {
      try {
        await this.client.postActivity(this.sessionId, { events });
      } catch (err) {
        if (err instanceof HttpStatusError && err.status === 404) this.disabled = true;
        if (!this.warned) {
          this.warned = true;
          // eslint-disable-next-line no-console
          console.warn(
            `[activity] session=${this.sessionId} 전송 실패 — 활동 스트림만 유실, 인터뷰는 계속: ${(err as Error).message}`,
          );
        }
        // 실패 배치는 폐기 — 재시도 없음 (transient 미리보기라 유실 수용)
      }
    });
  }

  /** 타이머 정리 + 잔여 큐 flush 후 in-flight 완료 대기. run()의 finally에서 호출. */
  async stop(): Promise<void> {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.flush();
    await this.inFlight;
  }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd netismaker-interview-service && npx vitest run test/activityPoster.test.ts`
Expected: PASS (9개)

- [ ] **Step 5: Commit**

```bash
git add netismaker-interview-service/src/runner/activityPoster.ts \
        netismaker-interview-service/test/activityPoster.test.ts
git commit -m "feat: ActivityPoster — 300ms 배치·직렬 전송·실패격리 (404 자동 비활성화)"
```

---

### Task 7: `sessionOptions` + `interviewRunner` 배선

**Files:**
- Modify: `netismaker-interview-service/src/sdk/sessionOptions.ts`
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts`
- Test: `netismaker-interview-service/test/sessionOptions.test.ts`, `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: Task 5 `relay(stream, {onActivity, workDir})`, Task 6 `ActivityPoster`, `streamingQuestionStream` 픽스처
- Produces: `buildOptions(...)` 결과에 `includePartialMessages: true` 포함; `run()`이 활동을 poster로 배선 — 이후 태스크 의존 없음(워커 완결)

- [ ] **Step 1: 실패하는 테스트 작성 ①** — `test/sessionOptions.test.ts`에 추가 (기존 base 입력 재사용 — 파일 열어 기존 `buildOptions(...)` 호출 인자 변수를 그대로 사용):

```ts
  it('enables includePartialMessages so relay can stream activity deltas (스펙 §5.1)', () => {
    const opts = buildOptions({
      superpowersPluginPath: '/sp',
      workDir: '/w',
      claudeCliPath: '/bin/claude',
      claudeSessionId: null,
    });
    expect(opts.includePartialMessages).toBe(true);
  });
```

- [ ] **Step 2: 실패하는 테스트 작성 ②** — `test/interviewRunner.test.ts`:

먼저 `makeClient()`에 `postActivity: vi.fn().mockResolvedValue(undefined),` 한 줄을 추가한다 (기존 3개 mock 옆). import에 `streamingQuestionStream` 추가. 그리고 신규 테스트 2개:

```ts
  it('활동 배선: 합성 "환경 준비" 활동이 최초로, relay 활동이 이어서 poster로 전송된다', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => streamingQuestionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    // run()의 finally에서 poster.stop()이 잔여 큐를 flush하므로 최소 1회 전송된다.
    expect(client.postActivity).toHaveBeenCalled();
    const events = client.postActivity.mock.calls.flatMap(
      (c: unknown[]) => (c[1] as { events: Array<Record<string, unknown>> }).events,
    );
    expect(events[0]).toMatchObject({ type: 'tool', label: '환경 준비' });
    // relay 활동(델타/tool_use)도 흘러들어옴
    expect(events.some((e) => e.type === 'thinking')).toBe(true);
    expect(events.some((e) => e.type === 'tool' && e.label === 'Read')).toBe(true);
    // 활동과 무관하게 최종 question 전송은 기존 그대로
    expect(client.postQuestion).toHaveBeenCalled();
  });

  it('활동 전송이 전부 실패해도 인터뷰(question 전송)는 성공한다', async () => {
    const client = makeClient();
    client.postActivity.mockRejectedValue(new Error('boom'));
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fakeQuery = vi.fn(() => streamingQuestionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(client.postQuestion).toHaveBeenCalled();
    expect(client.fail).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });
```

- [ ] **Step 3: 실패 확인**

Run: `cd netismaker-interview-service && npx vitest run test/sessionOptions.test.ts test/interviewRunner.test.ts`
Expected: 신규 3개 FAIL (`includePartialMessages` undefined, `postActivity` 미호출)

- [ ] **Step 4: `sessionOptions.ts` 수정** — `buildOptions` 반환 객체의 `permissionMode: 'default',` 아래에 한 줄 추가:

```ts
    // 활동 스트림: stream_event(텍스트/thinking 델타)를 relay가 실시간 방출할 수 있게 켠다 (스펙 §5.1).
    includePartialMessages: true,
```

- [ ] **Step 5: `interviewRunner.ts` 배선** — 변경 4곳:

① import 추가:

```ts
import { ActivityPoster } from './activityPoster.js';
import type { ActivityInput } from '../types.js';
```

(기존 `import type { AttachmentRef, InterviewClaimResponse } from '../types.js';` 줄을 `import type { ActivityInput, AttachmentRef, InterviewClaimResponse } from '../types.js';`로 교체하고 ActivityPoster import만 추가해도 된다.)

② `run()` 시작부 — `ticker.start();` 바로 아래:

```ts
    // 활동 스트림: 진행(도구/델타)을 300ms 배치로 중계. 실패는 poster가 격리 — 인터뷰에 무영향.
    const poster = new ActivityPoster(this.client, claim.sessionId);
    poster.start();
    const onActivity = (e: ActivityInput) => poster.push(e);
```

③ `await this.ensureRepo({...});` 바로 위에 합성 활동 1건 (클론 구간은 SDK 이벤트가 없어 침묵하므로):

```ts
      poster.push({ type: 'tool', label: '환경 준비', detail: claim.githubRepo });
```

④ 3개 relay 호출을 전부 옵션 전달형으로 교체:

- `const result = await relay(stream);` → `const result = await relay(stream, { onActivity, workDir: claim.workDir });`
- handoff splice의 `const second = await relay(this.query({...}));` → `const second = await relay(this.query({...}), { onActivity, workDir: claim.workDir });`
- reformat retry의 `const retry = await relay(this.query({...}));` → `const retry = await relay(this.query({...}), { onActivity, workDir: claim.workDir });`

⑤ `finally` 블록을 다음으로 교체:

```ts
    } finally {
      ticker.stop();
      await poster.stop();
    }
```

- [ ] **Step 6: 워커 전체 테스트 통과 확인**

Run: `cd netismaker-interview-service && npm test`
Expected: 전체 PASS (기존 스위트 포함 — makeClient 수정으로 기존 러너 테스트도 그린 유지)

- [ ] **Step 7: Commit**

```bash
git add netismaker-interview-service/src/sdk/sessionOptions.ts \
        netismaker-interview-service/src/runner/interviewRunner.ts \
        netismaker-interview-service/test/sessionOptions.test.ts \
        netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat: 인터뷰 워커 활동 스트림 배선 — includePartialMessages + ActivityPoster"
```

---

### Task 8: 프론트 `useInterviewStream` — `pending` 누적/소거

**Files:**
- Modify: `frontend/composables/useInterviewStream.ts`
- Test: `frontend/composables/useInterviewStream.spec.ts`

**Interfaces:**
- Consumes: SSE `activity` = `{events:[{seq,type,label,detail,content}]}` (Task 1 Java 계약)
- Produces: `interface ActivityLine { label: string; detail?: string }` · `interface PendingActivity { activities: ActivityLine[]; narration: string; thinking: string }` · 반환 객체에 `pending: Ref<PendingActivity | null>` 추가 — Task 9·10이 사용

- [ ] **Step 1: 실패하는 테스트 작성** — `useInterviewStream.spec.ts` 끝에 추가:

```ts
describe('useInterviewStream — activity events (진행 미리보기)', () => {
  it('tool 라인·narration·thinking 델타를 pending에 누적한다', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('activity', {
      events: [
        { seq: 1, type: 'tool', label: 'Read', detail: 'src/pages/login.vue' },
        { seq: 2, type: 'text', content: '이제 ' },
      ],
    })
    es.emit('activity', {
      events: [
        { seq: 3, type: 'text', content: '인증 흐름을 확인합니다' },
        { seq: 4, type: 'thinking', content: 'auth flow…' },
      ],
    })
    expect(s.pending.value).not.toBeNull()
    expect(s.pending.value!.activities).toEqual([{ label: 'Read', detail: 'src/pages/login.vue' }])
    expect(s.pending.value!.narration).toBe('이제 인증 흐름을 확인합니다')
    expect(s.pending.value!.thinking).toBe('auth flow…')
    s.close()
  })

  it('tool 라인은 최근 30건만 유지한다(오래된 것 제거)', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    const events = Array.from({ length: 31 }, (_, i) => ({
      seq: i + 1,
      type: 'tool',
      label: `T${i + 1}`,
    }))
    es.emit('activity', { events })
    expect(s.pending.value!.activities).toHaveLength(30)
    expect(s.pending.value!.activities[0].label).toBe('T2') // T1 탈락
    s.close()
  })

  it('신규 question 도착 시 pending 소거 — replay 중복 question은 소거하지 않는다', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('question', { seq: 1, content: 'Q1' })
    es.emit('activity', { events: [{ seq: 1, type: 'text', content: '다음 질문 준비…' }] })
    expect(s.pending.value).not.toBeNull()
    es.emit('question', { seq: 1, content: 'Q1' }) // replay dup — pending 유지
    expect(s.pending.value).not.toBeNull()
    es.emit('question', { seq: 2, content: 'Q2' }) // 신규 — 소거
    expect(s.pending.value).toBeNull()
    s.close()
  })

  it('plan_ready·터미널 status·done에서 pending 소거', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('activity', { events: [{ seq: 1, type: 'text', content: 'x' }] })
    es.emit('plan_ready', { designMarkdown: '#d', planMarkdown: '#p', planJson: '[]' })
    expect(s.pending.value).toBeNull()
    es.emit('activity', { events: [{ seq: 2, type: 'text', content: 'y' }] })
    es.emit('status', 'FAILED') // 터미널 → close() 경유 소거
    expect(s.pending.value).toBeNull()
  })

  it('깨진 activity 페이로드는 무시한다', () => {
    authStub.accessToken = 'jwt'
    const s = useInterviewStream()
    s.open(1)
    const es = FakeEventSource.last()
    es.emit('activity', 'not-json{')
    es.emit('activity', { nothing: true })
    expect(s.pending.value).toBeNull()
    s.close()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run composables/useInterviewStream.spec.ts`
Expected: 신규 5개 FAIL (`s.pending` undefined)

- [ ] **Step 3: 구현** — `useInterviewStream.ts` 변경 6곳:

① `DesignSection` 인터페이스 아래에 타입 추가:

```ts
export interface ActivityLine {
  label: string
  detail?: string
}

/** SSE activity 누적 상태 — 확정 question 도착 전의 transient 진행 미리보기. */
export interface PendingActivity {
  activities: ActivityLine[]
  narration: string
  thinking: string
}
```

② 상태 선언(`const plan = ref<...>`) 옆에:

```ts
  const pending = ref<PendingActivity | null>(null)
```

③ `connect()`의 리스너 등록에 추가 (`design` 리스너 아래):

```ts
    es.addEventListener('activity', (e) => onActivity(e as MessageEvent))
```

④ `onDesign` 아래에 핸들러 추가:

```ts
  const MAX_ACTIVITY_LINES = 30

  // activity 페이로드 = {events:[{seq,type,label,detail,content}]} — transient 진행 미리보기.
  // 확정 question/plan_ready/터미널이 도착하면 pending은 소거되고 확정 턴이 대체한다.
  function onActivity(e: MessageEvent) {
    const data = parse(e)
    if (!data || !Array.isArray(data.events)) return
    const p: PendingActivity = pending.value ?? { activities: [], narration: '', thinking: '' }
    for (const ev of data.events) {
      if (ev?.type === 'tool' && typeof ev.label === 'string') {
        p.activities.push({
          label: ev.label,
          detail: typeof ev.detail === 'string' ? ev.detail : undefined,
        })
        if (p.activities.length > MAX_ACTIVITY_LINES) p.activities.shift()
      } else if (ev?.type === 'text' && typeof ev.content === 'string') {
        p.narration += ev.content
      } else if (ev?.type === 'thinking' && typeof ev.content === 'string') {
        p.thinking += ev.content
      }
    }
    pending.value = { ...p } // 새 객체 할당으로 watch 트리거 보장
  }
```

⑤ 소거 3곳:

- `onQuestion`의 `if (added) status.value = 'AWAITING_INPUT'`를 다음으로 교체:

```ts
    // 새로 도착한 질문일 때만 입력 대기로 전환 + 진행 미리보기 소거(확정 턴이 대체).
    // replay된(이미 있는 seq) 질문은 status도 pending도 건드리지 않는다.
    if (added) {
      status.value = 'AWAITING_INPUT'
      pending.value = null
    }
```

- `onPlanReady`의 `status.value = 'PLAN_READY'` 아래에 `pending.value = null` 추가.
- `close()`의 `connState.value = 'closed'` 위에 `pending.value = null` 추가 (터미널 status·done·언마운트 공통 경로).

⑥ 반환 객체에 `pending,` 추가 (`plan,` 옆).

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frontend && npx vitest run composables/useInterviewStream.spec.ts`
Expected: PASS (기존 16개 + 신규 5개 = 21개)

- [ ] **Step 5: Commit**

```bash
git add frontend/composables/useInterviewStream.ts frontend/composables/useInterviewStream.spec.ts
git commit -m "feat: useInterviewStream — SSE activity 누적(pending)과 확정 턴 도착 시 소거"
```

---

### Task 9: `PendingBubble.vue` — 진행 버블 컴포넌트

**Files:**
- Create: `frontend/components/chat/PendingBubble.vue`
- Create: `frontend/components/chat/PendingBubble.spec.ts`
- Modify: `docs/superpowers/specs/2026-08-23-interview-live-activity-design.md` (§7.2 1줄 — QExpansionItem → 자체 접힘 토글)

**Interfaces:**
- Consumes: Task 8의 `PendingActivity` 타입
- Produces: `<PendingBubble :pending="PendingActivity" />` — Task 10이 사용. data-test 훅: `activity-latest`, `thinking-toggle`, `thinking-text`, `narration`

스펙 보정: §7.2의 "QExpansionItem" 대신 **자체 접힘 토글 버튼**을 쓴다 — `chat/*` 컴포넌트는 의도적으로 Quasar 미의존 순수 프레젠테이션(ChatBubble/TypingIndicator 선례)이며 기능(기본 접힘/펼침)은 동일하다. 스펙 문서의 해당 줄을 함께 수정해 정합을 유지한다.

- [ ] **Step 1: 실패하는 테스트 작성** — `PendingBubble.spec.ts`:

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import PendingBubble from './PendingBubble.vue'

const base = { activities: [], narration: '', thinking: '' }

describe('PendingBubble', () => {
  it('최신 도구 활동 라인을 표시한다 (label · detail)', () => {
    const w = mount(PendingBubble, {
      props: {
        pending: {
          ...base,
          activities: [
            { label: 'Grep', detail: 'auth' },
            { label: 'Read', detail: 'src/pages/login.vue' },
          ],
        },
      },
    })
    const latest = w.find('[data-test="activity-latest"]')
    expect(latest.text()).toContain('Read')
    expect(latest.text()).toContain('src/pages/login.vue')
    w.unmount()
  })

  it('thinking은 기본 접힘 — 토글 클릭 시 펼침', async () => {
    const w = mount(PendingBubble, {
      props: { pending: { ...base, thinking: 'auth flow부터 확인…' } },
    })
    expect(w.find('[data-test="thinking-toggle"]').exists()).toBe(true)
    expect(w.find('[data-test="thinking-text"]').exists()).toBe(false) // 기본 접힘
    await w.find('[data-test="thinking-toggle"]').trigger('click')
    expect(w.find('[data-test="thinking-text"]').text()).toContain('auth flow부터 확인…')
    w.unmount()
  })

  it('narration 미리보기를 렌더하고, 빈 섹션(thinking/활동/내레이션 없음)은 그리지 않는다', () => {
    const w = mount(PendingBubble, {
      props: { pending: { ...base, narration: '레포를 먼저 읽겠습니다' } },
    })
    expect(w.find('[data-test="narration"]').text()).toBe('레포를 먼저 읽겠습니다')
    expect(w.find('[data-test="thinking-toggle"]').exists()).toBe(false)
    expect(w.find('[data-test="activity-latest"]').exists()).toBe(false)
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run components/chat/PendingBubble.spec.ts`
Expected: FAIL — 컴포넌트 없음

- [ ] **Step 3: `PendingBubble.vue` 구현** (ChatBubble/TypingIndicator의 팔레트·아바타 재사용, Quasar 미의존):

```vue
<script setup lang="ts">
// 진행 중(확정 응답 전) 어시스턴트 버블: 최신 도구 활동 + thinking(기본 접힘) + 내레이션 미리보기.
// 순수 프레젠테이션 — 내부 상태는 thinking 펼침 토글뿐. 확정 question이 도착하면 부모가 제거한다.
// 고빈도 델타 갱신이라 aria-live는 off — 확정 턴은 transcript의 aria-live="polite"가 알린다.
import type { PendingActivity } from '~/composables/useInterviewStream'

const props = defineProps<{ pending: PendingActivity }>()

const showThinking = ref(false)

const latest = computed(() =>
  props.pending.activities.length > 0
    ? props.pending.activities[props.pending.activities.length - 1]
    : null,
)
// 최신 라인 직전 최대 3건 (오래된 → 최신)
const recent = computed(() => props.pending.activities.slice(-4, -1))
</script>

<template>
  <div class="pending-row" aria-live="off">
    <div class="avatar ai">AI</div>
    <div class="pending-bubble">
      <div class="activity-head">
        <span class="typing-dots" aria-hidden="true">
          <span class="dot" /><span class="dot" /><span class="dot" />
        </span>
        <span v-if="latest" class="activity-line" data-test="activity-latest">
          {{ latest.label }}<template v-if="latest.detail"> · {{ latest.detail }}</template>
        </span>
      </div>
      <div v-if="recent.length" class="activity-recent">
        <div v-for="(a, i) in recent" :key="i" class="recent-line">
          {{ a.label }}<template v-if="a.detail"> · {{ a.detail }}</template>
        </div>
      </div>
      <div v-if="pending.thinking" class="thinking-box">
        <button
          type="button"
          class="thinking-toggle"
          data-test="thinking-toggle"
          @click="showThinking = !showThinking"
        >
          {{ showThinking ? '▾' : '▸' }} 추론 중…
        </button>
        <pre v-if="showThinking" class="thinking-text" data-test="thinking-text">{{
          pending.thinking
        }}</pre>
      </div>
      <div v-if="pending.narration" class="narration" data-test="narration">{{ pending.narration }}</div>
    </div>
  </div>
</template>

<style scoped>
.pending-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 11px;
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
.avatar.ai {
  background: #e3f2fd;
  color: #1565c0;
}
.pending-bubble {
  max-width: 80%;
  background: #eef2f8;
  border-radius: 14px;
  border-bottom-left-radius: 4px;
  padding: 9px 12px;
}
.activity-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 18px;
}
.typing-dots {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #7f8ea3;
  animation: typing-bounce 1.2s infinite ease-in-out;
}
.dot:nth-child(2) {
  animation-delay: 0.18s;
}
.dot:nth-child(3) {
  animation-delay: 0.36s;
}
@keyframes typing-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-5px);
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
    opacity: 0.6;
  }
}
.activity-line {
  font-size: 11.5px;
  font-weight: 600;
  color: #3d4c60;
  word-break: break-all;
}
.activity-recent {
  margin-top: 4px;
}
.recent-line {
  font-size: 10.5px;
  color: #9aa7b6;
  word-break: break-all;
}
.thinking-box {
  margin-top: 6px;
}
.thinking-toggle {
  border: none;
  background: none;
  padding: 0;
  font-size: 11px;
  color: #8a97a8;
  cursor: pointer;
}
.thinking-text {
  margin: 4px 0 0;
  max-height: 180px;
  overflow-y: auto;
  font-size: 11px;
  line-height: 1.45;
  color: #8a97a8;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'Pretendard', sans-serif;
}
.narration {
  margin-top: 6px;
  font-size: 12.5px;
  line-height: 1.46;
  color: #25303f;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
```

- [ ] **Step 4: 스펙 §7.2 보정** — `docs/superpowers/specs/2026-08-23-interview-live-activity-design.md`의 §7.2에서 `**QExpansionItem "추론 중…"**(기본 접힘, \`dense\`)` 부분을 `**자체 접힘 토글 "추론 중…"**(기본 접힘 — chat/* 컴포넌트는 Quasar 미의존 순수 프레젠테이션 관례)`로 교체.

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd frontend && npx vitest run components/chat/PendingBubble.spec.ts`
Expected: PASS (3개)

- [ ] **Step 6: Commit**

```bash
git add frontend/components/chat/PendingBubble.vue frontend/components/chat/PendingBubble.spec.ts \
        docs/superpowers/specs/2026-08-23-interview-live-activity-design.md
git commit -m "feat: PendingBubble — 도구 활동 + thinking 접힘 + 내레이션 미리보기 버블"
```

---

### Task 10: `InterviewPanel` 통합 — 렌더 분기 + 스크롤 정책

**Files:**
- Modify: `frontend/components/InterviewPanel.vue`
- Test: `frontend/components/InterviewPanel.spec.ts`

**Interfaces:**
- Consumes: Task 8 `pending`, Task 9 `PendingBubble`
- Produces: 사용자-가시 동작 완결 — `waitingForAi && pending` → PendingBubble, `waitingForAi && !pending` → TypingIndicator(기존), 확정 질문 도착 시 교체

- [ ] **Step 1: 실패하는 테스트 작성** — `InterviewPanel.spec.ts`에 추가:

```ts
describe('InterviewPanel — 진행 활동(pending) 버블', () => {
  it('activity 수신 시 PendingBubble을 표시하고 TypingIndicator는 숨긴다', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(true) // 활동 전엔 기존 점 표시
    FakeEventSource.last().emit('activity', {
      events: [
        { seq: 1, type: 'tool', label: 'Read', detail: 'src/pages/login.vue' },
        { seq: 2, type: 'text', content: '레포를 먼저 읽겠습니다' },
      ],
    })
    await flushPromises()
    expect(w.find('[data-test="pending-bubble"]').exists()).toBe(true)
    expect(w.find('[data-test="typing-indicator"]').exists()).toBe(false)
    expect(w.text()).toContain('src/pages/login.vue')
    expect(w.text()).toContain('레포를 먼저 읽겠습니다')
    w.unmount()
  })

  it('확정 question 도착 시 PendingBubble이 사라지고 확정 말풍선으로 대체된다', async () => {
    const w = await mountPanel()
    FakeEventSource.last().emit('status', 'RUNNING')
    FakeEventSource.last().emit('activity', {
      events: [{ seq: 1, type: 'text', content: '어떤 인증을' }],
    })
    await flushPromises()
    expect(w.find('[data-test="pending-bubble"]').exists()).toBe(true)
    FakeEventSource.last().emit('question', { seq: 1, content: '어떤 인증을 쓰나요?' })
    await flushPromises()
    expect(w.find('[data-test="pending-bubble"]').exists()).toBe(false)
    expect(w.text()).toContain('어떤 인증을 쓰나요?') // ChatBubble 확정 턴
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run components/InterviewPanel.spec.ts`
Expected: 신규 2개 FAIL (pending-bubble 없음)

- [ ] **Step 3: `InterviewPanel.vue` 수정** — 변경 4곳:

① import 추가 (`TypingIndicator` import 아래):

```ts
import PendingBubble from '~/components/chat/PendingBubble.vue'
```

② 스트림 구조분해에 `pending` 추가:

```ts
const { connState, status, turns, designSections, plan, error, pending } = stream
```

③ 템플릿의 `<TypingIndicator v-if="waitingForAi" data-test="typing-indicator" />`를 다음으로 교체:

```html
          <PendingBubble
            v-if="waitingForAi && pending"
            data-test="pending-bubble"
            :pending="pending"
          />
          <TypingIndicator v-else-if="waitingForAi" data-test="typing-indicator" />
```

④ 스크롤 정책 watch 추가 — 기존 `watch(waitingForAi, …)` 아래:

```ts
// 진행(활동/델타) 갱신도 '새 메시지'가 아니다 — 하단 근처일 때만 따라 내려가고 unread는 올리지 않는다.
watch(
  () =>
    pending.value
      ? pending.value.narration.length +
        pending.value.thinking.length +
        pending.value.activities.length
      : 0,
  async (len) => {
    if (len === 0) return
    await nextTick()
    if (nearBottom.value) scrollToBottom()
  },
)
```

- [ ] **Step 4: 프론트 전체 테스트 + 타입체크 통과 확인**

Run: `cd frontend && npm test && npm run typecheck`
Expected: 전체 PASS + 타입 에러 0

- [ ] **Step 5: Commit**

```bash
git add frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts
git commit -m "feat: InterviewPanel — 진행 활동 버블 렌더 + 델타 스크롤 정책"
```

---

### Task 11: 풀스택 수동 스모크 + 라이브 반영 (MANUAL — 사람/운영자 확인 필요)

**Files:** 없음 (검증 전용). 필요 시 스크린샷을 PR에 첨부.

**Interfaces:**
- Consumes: Task 1~10 전부
- Produces: 스모크 증거 — **mock은 교차컴포넌트 계약을 못 잡는다**(이 기능군에서 3회 재현). 이 태스크 전에는 "동작함"을 주장하지 않는다.

- [ ] **Step 1: 3개 스위트 최종 그린 확인**

```bash
./gradlew test
cd netismaker-interview-service && npm test && cd ..
cd frontend && npm test && cd ..
```
Expected: 전부 PASS

- [ ] **Step 2: 로컬 풀스택 기동** — 별도 터미널 3개 (netis-auth/PostgreSQL은 기동돼 있다고 가정):

```bash
./gradlew bootRun                                             # API :8090
cd netismaker-interview-service && npm run dev                # 인터뷰 워커 (.env 필요)
cd frontend && npm run dev                                    # 프론트 :3001
```

- [ ] **Step 3: 실 인터뷰 스모크** — 브라우저에서 작업 등록 → 관리자 승인(인터뷰 시작) → 작업 상세의 인터뷰 패널에서 확인:

1. 네트워크 탭 SSE(`/api/interviews/{id}/stream`)에 `event: activity` 프레임 수신
2. 진행 버블: 도구 활동 라인(`환경 준비` → `Read · …`) 표시, 내레이션 텍스트 실시간 증가
3. effort 지정 세션이면 "추론 중…" 접힘 토글 — 클릭 시 thinking 텍스트
4. 질문 도착 시 진행 버블 → 확정 말풍선 교체, 입력창 활성(AWAITING_INPUT)
5. 진행 중 새로고침 → 대화 복원, 다음 activity부터 미리보기 재개(중간 갭 수용)
6. 답변 전송 → 다음 턴에서 2~5 반복
Expected: 6개 전부 통과. 실패 시 해당 구간(워커 로그 `[activity]` 경고 / Java 로그 / 브라우저 콘솔)으로 격리.

- [ ] **Step 4: 하위호환 확인(구 Java 시나리오 대체)** — 통합 환경에서 별도로 검증하기 어려우므로, 워커 로그로 대신 확인: Java를 잠시 내리고 인터뷰 중이 아닐 때 워커가 claim 폴링 에러만 내는지(activity 관련 crash 없음) 확인. 스킵 가능(단위 테스트가 404 비활성화를 커버).

- [ ] **Step 5: 라이브 반영 체크리스트** (머지 후 운영 반영 시 — 코드 머지만으로 반영 안 됨):

```
1. Java 재빌드+재기동 → 2. interview-service 재기동 → 3. frontend .output 재빌드
(공개 스택이면 ./scripts/start-public.sh 먼저, 이후 ./scripts/start-all.sh 런북 순서)
```

- [ ] **Step 6: 스모크 결과를 PR 본문에 기록하고 push**

```bash
git push -u origin feat/interview-live-activity
```

---

## Self-Review 결과 (플랜 작성 후 점검)

- **스펙 커버리지**: §4.1/4.2→Task 1·2·4, §5.0→Task 3, §5.1→Task 7, §5.2→Task 5, §5.3→Task 6, §5.4→Task 4, §5.5→Task 7, §6→Task 1·2, §7.1→Task 8, §7.2→Task 9(자체 토글로 보정, 스펙 동시 수정), §7.3→Task 10, §8 에러표→Task 2(400)·5(무시)·6(폐기/404)·8(파싱 무시), §9→태스크 순서+Task 11 Step 5, §10→각 태스크 테스트+Task 11.
- **타입 일관성**: `ActivityInput`(seq 없음, relay→poster) vs `ActivityEvent`(seq 있음, 와이어) 구분이 Task 4·5·6·7에서 동일. Java `ActivityEvent(long seq, String type, label, detail, content)` ↔ TS/프론트 동일 필드명. `relay(stream, opts)` 시그니처가 Task 5 정의·Task 7 사용 일치.
- **경계 주의**: Task 6 병합은 **flush로 큐가 교체된 뒤에는 이전 배치에 병합하지 않는다**(queue 배열 교체로 자연 보장). Task 8 `close()` 소거는 onUnmounted에도 적용되나 pending은 transient라 무해.
- **검증 워크플로우 반영(2026-08-23, 4에이전트 적대적 리뷰)**: ① MAJOR — 병합/개별 content·label에 Java @Size 동기 상한(4000/4096/100) 추가 (stdout chunk 폭주 시 setInterval 기아 → 무제한 병합 → 400 배치 폐기 경로 차단) ② Task 2 Step 2 기대 FAIL 수 3→2 정정(키 없음 테스트는 4xx 단언 특성상 선통과) ③ vitest toEqual undefined 동작 주석 정정 ④ 기존 테스트 수 실측 정정(useInterviewStream 16개) ⑤ 델타 타입 정의 출처를 @anthropic-ai/sdk messages.d.ts로 정정.
