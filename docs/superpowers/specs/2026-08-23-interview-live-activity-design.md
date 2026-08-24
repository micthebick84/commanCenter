# 인터뷰 실시간 사고 과정 스트리밍 (Live Activity) — 설계

- 날짜: 2026-08-23
- 브랜치: `feat/interview-live-activity` (← `main`)
- 대상: `netismaker-interview-service`(워커) + Java API(SSE) + `frontend/`(인터뷰 패널) — 3개 컴포넌트 관통

## 1. 배경 & 목표

대화형 분석 인터뷰에서 에이전트가 레포를 읽고 brainstorming을 도는 **수 분 동안 프론트로 가는 이벤트가 0건**이다. 워커의 `messageRelay.relay()`가 SDK 메시지 스트림을 턴이 끝날 때까지 전부 소진한 뒤에야 `postQuestion` 1회로 전달하기 때문이다(`interviewRunner.ts`). 그 사이 SDK 스트림에는 실시간 정보 — 중간 내레이션 텍스트, tool_use 블록(어떤 파일을 Read/Grep 중인지), thinking 블록(effort 지정 세션) — 가 이미 흐르고 있지만 `extractText`가 최종 text만 모으고 전부 버린다. 프론트(`InterviewPanel.vue`)는 그동안 점 3개 `TypingIndicator`만 표시해, 사용자는 진행 중인지 행(hang)인지 구분할 수 없고 "대화가 끊긴 것처럼" 느낀다.

**목표:** 에이전트의 사고 과정을 실시간으로 대화창에 노출한다. 구체적으로:

1. **도구 활동 라인** — `📖 Read · src/pages/login.vue` 같은 "지금 뭘 하는지" 표시
2. **내레이션/질문 토큰 스트리밍** — 최종 질문이 될 텍스트가 타이핑되듯 실시간 렌더 (`includePartialMessages` 델타)
3. **thinking 스트리밍** — 기본 접힘("추론 중…"), 클릭 시 펼침 (claude.ai 패턴)

**비목표(범위 밖):** 기존 SSE 이벤트(question/design/plan_ready/status/done)의 페이로드·의미 변경, 인터뷰 상태머신 변경, activity의 DB 영속화/replay, 경과 시간 표시, 구현 단계(`WorkerMainLoop`)의 스트리밍.

## 2. 현재 구조 (변경 전)

- **워커** `netismaker-interview-service/`: `interviewRunner.run()` → `relay(stream)`이 SDK 턴 전체를 drain 후 `{assistantText, sessionId, costUsd, durationMs}` 반환 → `postQuestion`/`postPlan` 1회. `sessionOptions.buildOptions`는 `includePartialMessages` 미설정(델타 없음).
- **Java** `InterviewWorkerController`(/worker/interviews, X-Worker-API-Key) + `InterviewStreamService`(in-memory SSE emitter 레지스트리, api 단일 인스턴스). LOCKED CONTRACT v2: question={seq,content} · design={key,title,body,approved} · plan_ready={designMarkdown,planMarkdown,planJson} · status=영문 enum name · done=bare.
- **프론트** `useInterviewStream.ts`(EventSource, 재연결 backoff, seq dedup) + `InterviewPanel.vue`(분할뷰, `waitingForAi`면 `TypingIndicator`).

## 3. 확정된 결정 (브레인스토밍 결과)

| 항목 | 결정 |
|---|---|
| 접근 | **B안: 활동 스트림 인프라 + 토큰 레벨 스트리밍** (A안 활동만 / C안 프론트 가짜 표시 기각) |
| thinking 표시 | **기본 접힘** — "추론 중…" 섹션, 클릭 시 펼침. 도구 활동·내레이션 스트리밍은 항상 표시 |
| 영속성 | **transient** — DB 미저장·replay 없음. 확정 내용은 기존 `question` 턴이 대체. 새로고침 시 진행 중 미리보기는 사라지고 다음 델타부터 재개 |
| DB 접근 | **activity 엔드포인트는 JPA 접근 0** — 고빈도(300ms 배치) 호출이라 HikariCP 풀 압박 금지(과거 SSE+OSIV 풀 고갈 사고 재발 방지). 검증은 워커 API 키 신뢰 경계에 위임 |
| 실패 정책 | activity 전송 실패는 **인터뷰에 절대 영향 없음** — 로그 후 무시, 404(구 Java)면 세션 잔여 기간 비활성화 |

## 4. 와이어 계약 (LOCKED CONTRACT 추가분 — 변경 시 양쪽 동시 수정)

### 4.1 SSE 이벤트 `activity` (신설)

```
event: activity
data: {"events": [ActivityEvent, ...]}        // 워커가 보낸 배치를 그대로 fan-out

ActivityEvent = {
  seq: number,                    // 워커 run() 내 단조증가 (순서/디버깅용)
  type: 'tool' | 'text' | 'thinking',
  label?: string,                 // tool 전용: 'Read'|'Grep'|'Glob'|'Skill'|'환경 준비'…
  detail?: string,                // tool 전용: 파일경로/패턴 (workDir prefix 제거, 120자 절단)
  content?: string                // text/thinking 전용: 델타 텍스트 청크 (배치 내 병합됨)
}
```

- **replay 없음** — `InterviewStreamService.subscribe()`의 turn replay 루프에 activity는 포함되지 않는다 (transient).
- 구 프론트는 `activity` 리스너가 없어 무시(EventSource는 구독한 이벤트명만 발화) — 완전 하위호환.

### 4.2 워커 엔드포인트 `POST /worker/interviews/{id}/activity` (신설)

- 쿼리: `?workerId=…` (필수 — 기존 워커 엔드포인트 컨벤션), 인증: `X-Worker-API-Key`(ROLE_WORKER)
- 바디: `{"events": [ActivityEvent, ...]}` — events 최대 100건, content/detail 개별 길이 상한(각 4KB/200자, `@Valid`)
- 응답: 204. **DB 미접근** — 세션 존재/소유 검증 생략(신뢰 경계=워커 키, 기존 heartbeat와 동일 수준)
- 구 Java(엔드포인트 부재)에 POST 시 404 → 워커가 무시하고 비활성화(§5.3)

## 5. 워커 설계 (`netismaker-interview-service/`)

### 5.0 선행 미니 스파이크 (Phase 0 — 구현 전 필수)

`stream_event` 메시지의 **실제 shape을 실 CLI에서 캡처**한다. 기존 `scripts/spike.ts` 패턴을 재사용해 `includePartialMessages: true`로 1턴 실행하고, `stream_event`·tool_use 포함 `assistant` 메시지의 원본 JSON을 픽스처 파일로 저장 → relay 파싱 테스트의 기준으로 사용한다. **이 프로젝트에서 "가정한 계약 위에 짠 코드"가 실연동에서 깨진 전례 3회**(stream-json user 포맷, bare-string SSE, workerId 누락) — 픽스처는 반드시 실물에서 뜬다. 예상 shape(SDK 0.2.117): `{type:'stream_event', event: RawMessageStreamEvent, session_id, parent_tool_use_id?}` — `content_block_delta`의 `text_delta`/`thinking_delta`를 사용, `signature_delta`/`input_json_delta`는 무시. `parent_tool_use_id`가 있는 이벤트(서브에이전트 스트림)는 스킵.

### 5.1 `sessionOptions.ts`

`buildOptions` 반환 객체에 `includePartialMessages: true` 추가. 그 외 무변경 (allowedTools/permissions 불변식 유지).

### 5.2 `messageRelay.ts` — `relay(stream, onActivity?)`

시그니처 확장: `relay(stream: AsyncIterable<SdkMessage>, onActivity?: (e: ActivityInput) => void)`. `ActivityInput = {type, label?, detail?, content?}` (seq는 poster가 부여).

- `stream_event`: `content_block_delta` → `text_delta`면 `{type:'text', content}`, `thinking_delta`면 `{type:'thinking', content}` 콜백. 나머지 스트림 이벤트 무시. `parent_tool_use_id` 있으면 스킵.
- `assistant` 메시지: 기존 `extractText` 누적(최종 `assistantText`의 원천 — **무변경**, 여전히 postQuestion/planHarvest의 source of truth) + **tool_use 블록별** `{type:'tool', label: block.name, detail: summarize(block.input)}` 콜백. 텍스트는 델타로 이미 흘렸으므로 assistant 메시지에서 text를 activity로 재방출하지 않는다(중복 방지).
- `summarize(input)`: Read→`file_path`, Grep/Glob→`pattern`, Skill→`skill`(명), 기타→undefined. workDir prefix 제거, 120자 절단. 순수함수로 분리(테스트 용이).
- `onActivity` 미전달 시 기존과 100% 동일 동작 (테스트 하위호환).

### 5.3 `ActivityPoster` (신규 모듈 `runner/activityPoster.ts`)

- `push(e: ActivityInput)`: 내부 큐 적재 + seq 부여.
- **배치 flush**: 300ms 간격 또는 큐 20건 도달 시. 배치 내 **동일 type 연속 델타는 content 병합**(text/thinking) — 페이로드 수 최소화. 단 **병합 4000자·개별 content 4096자·label 100자·detail 200자 상한**(Java `@Size`와 동기): SDK 자식프로세스 stdout이 델타 다수를 한 chunk로 실어오면 이벤트 루프 기아로 병합이 무제한 커져 Java 400으로 배치 전체가 폐기될 수 있어 생산 측에서 절단한다.
- **직렬 전송**: in-flight POST 1개(순서 보장). 실패 시 해당 배치 폐기 + 경고 로그(세션당 1회), **404면 남은 세션 동안 전송 비활성화**. 어떤 실패도 throw하지 않는다.
- `stop()`: 타이머 정리 + 마지막 flush 시도. `interviewRunner`의 `finally`에서 호출.

### 5.4 `javaClient.ts`

`postActivity(id: number, body: {events: ActivityEvent[]}): Promise<void>` 추가 — `POST /worker/interviews/{id}/activity?workerId=…`, 실패 시 status 포함 Error throw(처리 책임은 poster).

### 5.5 `interviewRunner.ts`

- `run()` 진입 시 poster 생성, `ensureRepo` 직전 합성 이벤트 `{type:'tool', label:'환경 준비', detail: claim.githubRepo}` 1건(클론 구간은 SDK 이벤트가 없어 침묵하므로).
- 3개 relay 호출 지점(본턴 / handoff splice / reformat retry) 모두 `relay(stream, (e) => poster.push(e))`로 전달.
- `finally`에서 `poster.stop()` (heartbeat ticker와 동일 위치).

## 6. Java 설계

### 6.1 DTO (`dto/`)

- `ActivityEvent(long seq, String type, String label, String detail, String content)` — record.
- `WorkerActivityRequest(List<ActivityEvent> events)` — `@Size(max=100)`, 개별 content `@Size(max=4096)`/detail `@Size(max=200)`.

### 6.2 `InterviewWorkerController`

```java
@PostMapping("/{id}/activity")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void activity(@PathVariable Long id, @RequestParam String workerId,
                     @RequestBody @Valid WorkerActivityRequest req) {
    interviewStream.pushActivity(id, req);   // DB 미접근 — 의도적 (§3)
}
```

### 6.3 `InterviewStreamService`

`pushActivity(Long sessionId, WorkerActivityRequest batch)`: `sendJson(sessionId, "activity", batch)`. `subscribe()`의 replay 루프 **무변경**. 클래스 상단 LOCKED CONTRACT 주석에 `activity = {events:[…]} JSON, transient·no-replay` 1줄 추가.

## 7. 프론트 설계 (`frontend/`)

### 7.1 `useInterviewStream.ts`

- 신규 상태: `pending = ref<PendingActivity | null>(null)`
  ```ts
  interface PendingActivity {
    activities: { label: string; detail?: string }[]  // 최근 30건 유지 (오래된 것 shift)
    narration: string                                  // type='text' content 누적
    thinking: string                                   // type='thinking' content 누적
  }
  ```
- `es.addEventListener('activity', …)`: `{events}` 파싱(기존 `parse()` 재사용, 실패 시 무시) → 이벤트별 누적. 첫 이벤트에서 pending 초기화.
- **pending 소거 시점**: ① `onQuestion`에서 신규 턴 추가 시 ② `onPlanReady` ③ 터미널 status ④ `done`/`close`. (재연결 시에는 유지 — 델타 갭은 transient 미리보기라 수용, question 도착이 최종 정합 보장.)
- 반환 객체에 `pending` 추가.

### 7.2 신규 `components/chat/PendingBubble.vue` (순수 프레젠테이션)

- **Props:** `{ pending: PendingActivity }`
- **렌더** (assistant 말풍선 스타일, `ChatBubble` 룩앤필 재사용):
  - 상단: `TypingIndicator` 점 + **최신 도구 활동 라인** (`activities`의 마지막 항목: 아이콘 + `label · detail`). 이전 활동은 흐릿한 소형 리스트로 최근 3건.
  - 중단: `thinking`이 비어있지 않으면 **자체 접힘 토글 "추론 중…"**(기본 접힘 — chat/* 컴포넌트는 Quasar 미의존 순수 프레젠테이션 관례) — 펼치면 흐릿한 소형 폰트(`pre-wrap`)로 thinking 텍스트.
  - 하단: `narration`이 비어있지 않으면 본문 텍스트(`pre-wrap`) — 최종 질문의 라이브 미리보기.
- 내부 상태는 접힘 토글뿐. `aria-live="off"`(고빈도 갱신이라 스크린리더 소음 방지 — 확정 질문 도착 시 기존 transcript의 `aria-live="polite"`가 알림).

### 7.3 `InterviewPanel.vue`

- `waitingForAi && pending` → `<PendingBubble :pending="pending" />` 렌더, `waitingForAi && !pending` → 기존 `TypingIndicator` 유지(claim 전 QUEUED 등).
- 자동 스크롤: pending 갱신은 '새 메시지' 아님 — `watch`로 narration/thinking 길이·activities 수를 관찰해 `nearBottom`일 때만 `scrollToBottom()`, `unread` 미증가 (기존 `waitingForAi` watch와 동일 정책).

## 8. 에러 처리 요약

| 상황 | 동작 |
|---|---|
| 워커 activity POST 실패(네트워크/5xx) | 배치 폐기, 세션당 1회 경고 로그, 인터뷰 계속 |
| 워커 activity POST 404 (구 Java) | 남은 세션 동안 전송 비활성화, 인터뷰 계속 |
| Java: 구독자 0명 세션에 activity | `send()` 기존 동작 — no-op |
| 프론트: activity JSON 파싱 실패 | 이벤트 무시 (기존 `parse()` 패턴) |
| 프론트: 재연결로 델타 갭 발생 | 수용 — pending은 미리보기, question 도착이 정합 보장 |
| thinking 없음(effort 미지정) | thinking 섹션 미렌더 — 도구 활동+내레이션만 |

## 9. 하위호환 & 배포 순서

1. **Java API** 배포(신규 엔드포인트는 inert) → 2. **워커**(interview-service 재기동) → 3. **프론트** 재빌드.
- 구 워커 + 신 프론트/Java: activity 이벤트 없음 → 현행과 동일(TypingIndicator만).
- 신 워커 + 구 Java: 404 → 비활성화, 인터뷰 무영향.
- 신 Java/워커 + 구 프론트: 미구독 이벤트 무시.
- ⚠️ 코드 머지만으로 라이브 반영 안 됨 — Java 재빌드/재기동 + interview-service 재기동 + `frontend/.output` 재빌드 필요 (start-all.sh 런북).

## 10. 테스트 전략

- **워커(vitest)**: ① `messageRelay` — 실 CLI 캡처 픽스처로 text/thinking 델타 방출·tool_use 추출·텍스트 중복 미방출·`assistantText` 기존 결과 불변·`onActivity` 미전달 시 무변경 ② `activityPoster` — 배치 병합·직렬 전송·실패 폐기·404 비활성화·stop flush ③ `interviewRunner` — 3개 relay 지점 배선·합성 '환경 준비' 이벤트·finally stop ④ `sessionOptions` — `includePartialMessages: true` + 기존 allowedTools 불변식 유지.
- **Java**: `InterviewWorkerController` 슬라이스 테스트(인증·@Valid 상한·pushActivity 위임·DB 미접근), `InterviewStreamService.pushActivity` 단위(구독자가 `activity` 이벤트 + 배치 JSON 수신, 구독자 0명 no-op).
- **프론트(Vitest+VTU)**: `useInterviewStream` — activity 누적·activities 30건 상한·question/plan_ready/터미널/done 소거·파싱 실패 무시; `PendingBubble` — thinking 기본 접힘·빈 섹션 미렌더; `InterviewPanel` — pending 유무에 따른 PendingBubble/TypingIndicator 분기·질문 도착 시 교체·스크롤 정책.
- **풀스택 수동 스모크 (필수)**: 실 인터뷰 1건에서 ① 브라우저에 activity 이벤트 수신(네트워크 탭) ② 도구 활동 라인·내레이션 타이핑·thinking 접힘 렌더 ③ 질문 도착 시 PendingBubble→ChatBubble 교체 ④ 새로고침 후 정상 재개 확인. — **mock은 교차컴포넌트 계약을 못 잡는다**(이 기능군에서 3회 재현된 교훈).
