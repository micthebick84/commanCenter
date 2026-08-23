# stream_event 실물 shape 캡처 — 검증 결과

**실행일:** 2026-08-23
**스크립트:** `scripts/spikeStream.ts`
**대상 레포:** `/tmp/spike-repo` (git init, `a.md` 1개 파일, 커밋 1개)
**옵션:** `buildOptions({ effort: 'high', ... })` + `includePartialMessages: true`
**프롬프트:** "이 레포의 파일 하나를 Read 도구로 읽고, 내용을 한 문장으로 요약해줘."
**결과:** 종료코드 0, `/tmp/stream-capture.jsonl` 81줄 (54,779 bytes), `total_cost_usd: 0.806553`, `num_turns: 4`, `stop_reason: end_turn`.
원본 캡처는 커밋하지 않음(모델 출력 포함).

## 검증 요약 (4개 항목 전부 실측 확인, 가정과 전부 일치)

| 항목 | grep 결과 | 판정 |
|---|---|---|
| ① `stream_event` 봉투 존재 | `grep -c '"type":"stream_event"'` → **56** | MATCH |
| ① `parent_tool_use_id` 필드 존재 | 봉투마다 존재, 캡처 전체에서 값은 전부 `null` (하위 서브에이전트 호출 없음 — 단일 턴이라 예상된 결과) | MATCH |
| ② `content_block_delta` + delta.type | `text_delta`/`thinking_delta`/`signature_delta`/`input_json_delta` 전부 관측 | MATCH |
| ③ `tool_use` 블록 `{name, input.file_path}` | `Glob`, `Read` 도구 호출에서 확인 | MATCH |
| ④ `thinking_delta` 발생 여부 (`effort:'high'`) | `grep -c '"thinking_delta"'` → **2** (0 아님) | 발생함 |

---

## ① stream_event 봉투 — `{type, event, parent_tool_use_id, session_id, uuid}`

가정(sdk.d.ts:2855, `SDKPartialAssistantMessage`): `{type:'stream_event', event, parent_tool_use_id, uuid, session_id, ttft_ms?}`

대표 라인 (message_start, `ttft_ms` optional 필드 포함 예):
```json
{"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-fable-5","id":"msg_011CeKqWRprhQbhNnuqN4D19","type":"message","role":"assistant","content":[],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":32811,...},"diagnostics":null}},"session_id":"c84ead65-3350-418b-b8c8-22d87e6bc9f5","parent_tool_use_id":null,"uuid":"039d5dce-ee0a-4d1c-853a-311a76d62845","ttft_ms":3471}
```
**판정: MATCH.** 필드명·타입 전부 가정대로. 이번 캡처 안의 `parent_tool_use_id`는 전부 `null`(서브에이전트/중첩 tool 호출이 없는 단일 턴이라 예상된 결과 — non-null 케이스는 이번 스파이크로 확인 못함, Task 5 파싱 로직은 `parent_tool_use_id`를 그대로 통과시키기만 하면 되므로 값 종류와 무관하게 안전).

## ② content_block_delta — delta.type별 실물

가정(messages.d.ts `BetaTextDelta`/`BetaThinkingDelta`): `text_delta{text}` / `thinking_delta{thinking}`.

- **text_delta**
```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"이"}},"session_id":"c84ead65-...","parent_tool_use_id":null,"uuid":"f001d4e5-..."}
```
**판정: MATCH.** `delta.text` 필드 그대로.

- **thinking_delta** (effort:'high'로 유도, 2건 관측)
```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"","estimated_tokens":50}},"session_id":"c84ead65-...","parent_tool_use_id":null,"uuid":"9289a8fa-..."}
```
**판정: MATCH** (`delta.thinking` 필드 존재) **+ 부가 관측:** 가정에 없던 `estimated_tokens`(number | null) 필드가 추가로 붙어 있음 — 순수 부가 필드라 파싱에 지장 없음(Task 5는 `delta.type==='thinking_delta'`면 `delta.thinking`만 읽으면 됨).

- **signature_delta** (thinking 블록 종료 시그니처 — 가정 목록에 이름만 언급됐던 항목, 실물 확인)
```json
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"CAISyAMKpwEIERgCKkDQDY9BOEvw...(생략, 길이 ~1100자)"}},"session_id":"c84ead65-...","parent_tool_use_id":null,"uuid":"466bf268-..."}
```
**판정: MATCH.**

- **input_json_delta** (가정 목록엔 없었지만 `BetaRawContentBlockDelta` 유니온에 포함된 타입 — tool_use의 input이 스트리밍될 때 등장. 이번 캡처에서 `partial_json:""` 형태로 관측, Task 5 델타 파싱 대상은 아님)
```json
{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}},"session_id":"c84ead65-...","parent_tool_use_id":null,"uuid":"ec6422ac-..."}
```

관측된 `event.type` 전체 분포: `message_start`(4), `content_block_start`(5), `content_block_delta`(34), `content_block_stop`(5), `message_delta`(4), `message_stop`(4). 가정한 6종 이벤트 타입과 명칭 일치.

## ③ tool_use 블록 — `{name, input.file_path}`

가정: assistant 메시지 content의 `tool_use` 블록에 `name`, `input.file_path`.

`content_block_start`의 tool_use는 `input`이 빈 객체(`{}`)로 시작하고(스트리밍 시작 시점), 완성된 `input`은 최종 `assistant` 타입 메시지(비-stream_event, 델타 조립 완료본)에서 확인됨:
```json
{"type":"assistant","message":{"model":"claude-fable-5","id":"msg_011CeKqWuNJwYJxuVX1hES94","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_015Q7ySR37vGdzPw7oqs1bwP","name":"Read","input":{"file_path":"/Users/micthebick/works/temp/claude-test/a.md"},"caller":{"type":"direct"}}],"stop_reason":null,...},"parent_tool_use_id":null,"session_id":"c84ead65-...","uuid":"9b53779b-...","timestamp":"2026-08-23T14:27:57.261Z","request_id":"req_011CeKqWpcKERMPb3817fEM6"}
```
**판정: MATCH.** `name`, `input.file_path` 필드 존재. 부가 필드 `caller:{type:'direct'}`, `id`, `timestamp`, `request_id` 등은 파싱에 불필요하므로 무해.

**부가 관측 (shape과 무관, 모델 행동 노트):** 위 예시의 `file_path`가 `/Users/micthebick/works/temp/claude-test/a.md`로 실제 작업 디렉터리(`/private/tmp/spike-repo`)와 다르다. 원인 확인됨 — 모델이 첫 Read 시도에서 경로를 잘못 추측했고, `tool_result`로 `"File does not exist. Note: your current working directory is /private/tmp/spike-repo."` 에러를 받은 뒤 두 번째 Read 시도에서 올바른 절대경로(`/private/tmp/spike-repo/a.md`)로 재시도해 성공했다(두 tool_use 모두 캡처에 존재, `toolu_015Q...`=실패/`toolu_01BULG8...`=성공). SDK/CLI의 버그가 아니라 모델의 정상적인 자기수정 행동이며, `cwd` 옵션(`buildOptions`) 자체는 `system/init` 메시지의 `"cwd":"/private/tmp/spike-repo"`로 정확히 전달됨. shape 검증과는 무관하지만 Task 5/Task 11에서 "activity 라인에 찍히는 file_path가 항상 정답 경로는 아닐 수 있다"는 걸 감안할 근거로 남긴다.

## ④ thinking_delta 발생 여부 (`effort:'high'`)

`grep -c '"thinking_delta"'` → **2**. effort:'high' 지정 시 실제로 thinking 델타가 발생함을 확인. (0건이었어도 실패는 아니었을 것 — 브리프의 안전장치대로.) 최종 `result` 메시지의 `usage.output_tokens_details.thinking_tokens: 63`으로도 thinking이 실제로 과금·집계됨을 교차 확인.

## STOP GATE 판정

**모든 관측 shape이 Global Constraints 가정과 일치(MATCH).** 봉투(`sdk.d.ts:2855`)·델타(`@anthropic-ai/sdk` messages.d.ts `BetaTextDelta`/`BetaThinkingDelta`) 전부 실측과 동일한 필드명·타입. 부가 필드(`estimated_tokens`, `caller`, `ttft_ms` 등)는 상위집합(superset)일 뿐 파싱 로직을 깨지 않는다. **스펙 §5.2/Task 5 픽스처 수정 불필요 — 그대로 진행 가능.**

## Task 5로 넘기는 체크리스트

- `event.type==='content_block_delta'`일 때만 `delta` 검사 (다른 `event.type`은 무시 가능).
- `delta.type` 분기: `text_delta`→`delta.text`, `thinking_delta`→`delta.thinking` (나머지 `signature_delta`/`input_json_delta`는 Task 5 activity 스트림 파싱 대상 아님 — 무시).
- `parent_tool_use_id`는 이번 캡처에서 전부 `null`이었으나 필드 자체는 항상 존재하므로 그대로 통과시키면 됨 (non-null 케이스는 미검증 — 운영 중 서브에이전트/중첩 tool_use가 있는 턴에서 나올 수 있음, Task 11 풀스택 스모크에서 재확인 권장).
- `tool_use` 블록의 `input.file_path`는 완성된(비-스트리밍) `assistant` 메시지에서 읽어야 함 — `content_block_start` 시점의 `input`은 빈 객체.
