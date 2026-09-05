# rate_limit_event / 턴 usage 실물 shape 캡처 — 검증 결과

**실행일:** 2026-09-05 · **스크립트:** `scripts/spikeRateLimit.ts` · **모델:** claude-haiku-4-5 / effort low
**결과:** 종료코드 0, `/tmp/rate-limit-capture.jsonl` 50줄 (46,769 bytes). `is_error:false`, `subtype:"success"`, `stop_reason:"end_turn"`, `num_turns:2`, `total_cost_usd:0.0481958`, `duration_ms:8812`(`duration_api_ms:7662`). 원본 캡처는 커밋하지 않음(모델 출력 포함).

**실행 환경:** 운영자 macOS, claude CLI `/Users/micthebick/.local/bin/claude`(2.1.261), 구독 로그인(`system/init.apiKeySource:"none"`), `ANTHROPIC_API_KEY` 미설정. 중첩 Claude Code 세션 감지용 env 8종(`CLAUDECODE`,`CLAUDE_CODE_ENTRYPOINT`,`CLAUDE_CODE_CHILD_SESSION`,`CLAUDE_CODE_SESSION_ID`,`CLAUDE_CODE_MESSAGING_SOCKET`,`CLAUDE_CODE_MESSAGING_TOKEN`,`CLAUDE_CODE_BRIDGE_SESSION_ID`,`CLAUDE_PID`,`CLAUDE_EFFORT`)는 `env -u`로 제거하고 실행 — 프로덕션(플레인 셸)과 동일 조건.

## stdout 원문

```
[rate_limit_event] {"status":"allowed","resetsAt":1788616200,"rateLimitType":"five_hour","overageStatus":"rejected","overageDisabledReason":"org_level_disabled","isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.05,"resetsAt":1788616200},"seven_day":{"utilization":0.12,"resetsAt":1788706800}}}
[message_start.usage(last top-level)] {"input_tokens":8,"cache_creation_input_tokens":1090,"cache_read_input_tokens":20368,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":1090},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"}
[assistant.usage(last top-level)] {"input_tokens":8,"cache_creation_input_tokens":1090,"cache_read_input_tokens":20368,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":1090},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"}
[result.modelUsage] {"claude-haiku-4-5":{"inputTokens":18,"outputTokens":645,"cacheReadInputTokens":20368,"cacheCreationInputTokens":21458,"webSearchRequests":0,"costUSD":0.0481958,"contextWindow":200000,"maxOutputTokens":32000,"thinkingTokens":486,"canonicalModel":"claude-haiku-4-5","provider":"firstParty","costBasis":"list"}}
RESULT: rate_limit_event=1건 assistantUsage=있음 messageStartUsage=있음 modelUsage=있음 capture=/tmp/rate-limit-capture.jsonl
exit=0
```

## 관측된 msg.type 순서 (distinct, 최초 등장 순) 및 rate_limit_event 위치

`system, stream_event, assistant, rate_limit_event, user, result`

세션은 모델 턴 2회(`num_turns:2`, ①Read 도구로 a.md 조회 ②결과 한 문장 요약)로 구성됨. **`rate_limit_event`는 세션 전체에 걸쳐 정확히 1건**만 발생했고, 캡처 31번째 줄(`stream_event/message_stop`으로 첫 턴이 끝나는 30번째 줄 바로 다음, 두 번째 턴을 여는 tool_result `user` 메시지·32번째 줄 바로 전)에 위치한다. 첫 top-level `assistant` 메시지(20번째 줄, thinking 블록)를 기준으로는 11줄 뒤, 첫 턴의 마지막 top-level `assistant` 메시지(27번째 줄, `Read` tool_use 완성분)를 기준으로는 4줄 뒤다. 두 번째 턴(요약 생성, 33~48번째 줄)에는 재발행되지 않았다 — "턴마다"가 아니라 "세션당 한 번 이상"으로 보는 게 실측에 맞다.

## 요약 표

| # | 항목 | 가정(스펙 §4.3) | 관측 | 판정 |
|---|---|---|---|---|
| 1 | `rate_limit_event` 발생 여부/건수/시점 | 턴당 1건 이상, 첫 API 응답 직후 | 세션 전체(모델 턴 2회) 통틀어 **1건**. 첫 턴 `message_stop` 직후 발행(31번째 줄), 둘째 턴에는 미발행 | 존재는 MATCH · 빈도("턴당")는 MISMATCH — 실측은 "세션당 ≥1건" |
| 2 | `rate_limit_info.rateLimitType` 종류 | five_hour / seven_day (+opus/sonnet) | 최상위 `rateLimitType`은 `"five_hour"` 1개만 관측(이벤트가 1건이므로 다른 타입이 top-level에 오는 경우는 미확인). 대신 문서화 안 된 `unifiedWindows` 객체가 `five_hour`/`seven_day` 두 키를 **동시에** 담고 있음. opus/sonnet 세분 타입은 미관측(haiku·단문 세션이라 스코프 밖 — "없다"는 증거는 아님) | 값 자체는 MATCH(문서화된 유니온 안) · 구조는 MISMATCH(아래 STOP GATE) |
| 3 | `utilization` 단위 | 0..1 분수 | **최상위 `utilization` 필드 자체가 없음.** 실제 값은 `unifiedWindows.five_hour.utilization=0.05`, `unifiedWindows.seven_day.utilization=0.12` — 값은 0..1 분수로 가정과 일치하나 위치가 다름 | 단위는 MATCH(분수) · 위치는 MISMATCH — 아래 STOP GATE, 코드 변경 불필요 전제가 깨짐 |
| 4 | `resetsAt` 단위 | epoch 초 | 최상위 `resetsAt=1788616200` → `date -r 1788616200` = `2026-09-05 22:50:00 KST`(캡처 시각 17:55:43 KST 기준 +4h54m, `rateLimitType:"five_hour"` 창과 정합) → **epoch 초 확인**(ms였다면 1970-01-21 근방으로 환산됐을 것). `unifiedWindows.seven_day.resetsAt=1788706800` → `2026-09-07 00:00:00 KST`(자정 고정 경계로 보임, "지금+7일"이 아님) | MATCH(epoch 초) |
| 5 | 최상위 `assistant.message.usage` 필드 | input/cache_creation/cache_read/output | `input_tokens`,`cache_creation_input_tokens`,`cache_read_input_tokens`,`output_tokens` 전부 존재. 부가 필드 `cache_creation:{ephemeral_5m_input_tokens,ephemeral_1h_input_tokens}`,`service_tier`,`inference_geo` | MATCH(+상위집합) |
| 6 | 최상위 `message_start` usage | 동일 필드(출력 전) | `assistant.usage`와 완전히 동일한 필드셋 관측(생성 시작 시점 스냅샷) | MATCH |
| 7 | `result.modelUsage` 키·`contextWindow` | 모델 id 키, 200000 | 키=`"claude-haiku-4-5"`(요청 모델 id 그대로), `contextWindow:200000`. 부가 필드 `inputTokens,outputTokens,cacheReadInputTokens,cacheCreationInputTokens,webSearchRequests,costUSD,maxOutputTokens,thinkingTokens,canonicalModel,provider,costBasis` | MATCH(+상위집합) |

## 실물 JSON — 항목별 근거

### ① `rate_limit_event` (캡처 31번째 줄, 세션 유일의 1건)

```json
{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1788616200,"rateLimitType":"five_hour","overageStatus":"rejected","overageDisabledReason":"org_level_disabled","isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.05,"resetsAt":1788616200},"seven_day":{"utilization":0.12,"resetsAt":1788706800}}},"session_id":"ec86190d-...","uuid":"..."}
```

`SDKRateLimitInfo`(`node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts:2923`) 선언 필드는 `status,resetsAt,rateLimitType,utilization,overageStatus,overageResetsAt,overageDisabledReason,isUsingOverage,surpassedThreshold`다. 실물에서 **`utilization`은 없다.** 대신 `.d.ts`에 없는 `unifiedWindows: {five_hour?:{utilization,resetsAt}, seven_day?:{utilization,resetsAt}, ...}` 필드가 있고, 실제 두 창의 utilization/resetsAt은 전부 그 안에 있다 — SDK 0.2.117의 타입선언이 실제 CLI 2.1.261 서버 응답 계약을 못 따라간 것으로 보인다. `resetsAt`/`status`/`isUsingOverage`/`rateLimitType`은 top-level에 실재하고 선언·가정과 일치한다.

### ② `message_start.usage`(최초, 캡처 11번째 줄) / `assistant.message.usage`(최종, 캡처 46번째 줄 — stdout과 동일)

```json
// message_start.usage — 첫 turn 최초 스냅샷 (11번째 줄)
{"input_tokens":10,"cache_creation_input_tokens":20368,"cache_read_input_tokens":0,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":20368},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"}

// assistant.message.usage — 세션 마지막 top-level assistant (46번째 줄, 둘째 턴의 최종 텍스트 블록) = stdout [assistant.usage(last top-level)]와 동일값
{"input_tokens":8,"cache_creation_input_tokens":1090,"cache_read_input_tokens":20368,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":1090},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"}
```

부가 관측: 같은 턴 안에서 `message_start.usage`와 그 턴에 속한 모든 top-level `assistant.usage`가 완전히 동일한 값을 유지한다(`output_tokens`도 매번 `1`로 고정) — 이 필드는 "지금까지 누적 생성량"이 아니라 **그 API 호출의 입력측 스냅샷**(요청 시점 확정, 델타로 갱신 안 됨)으로 보인다. 세션 전체 누적 출력은 `result.usage.output_tokens=645`(그중 `output_tokens_details.thinking_tokens=486`)로 별도 집계된다. 스펙 §4.2가 컨텍스트 토큰으로 쓰려는 값(`input+cache_creation+cache_read`, 마지막 top-level assistant 메시지 기준)은 이 스냅샷 정의와 맞다 — 코드 변경 필요 없음.

### ③ `result.modelUsage` (캡처 50번째 줄)

```json
{"claude-haiku-4-5":{"inputTokens":18,"outputTokens":645,"cacheReadInputTokens":20368,"cacheCreationInputTokens":21458,"webSearchRequests":0,"costUSD":0.0481958,"contextWindow":200000,"maxOutputTokens":32000,"thinkingTokens":486,"canonicalModel":"claude-haiku-4-5","provider":"firstParty","costBasis":"list"}}
```

키가 요청한 모델 id(`claude-haiku-4-5`) 그대로이고 `contextWindow:200000`이 존재 — 가정과 일치, 상위집합 필드는 무해.

## STOP GATE 판정

- **#1(발생 여부)은 0건이 아니다(1건).** 브리프의 "0건이면 Task 5·7·9 착수 전 보고" 게이트는 문자 그대로는 발동하지 않는다. 컨텍스트 경로(#5~#7, 전부 MATCH)는 그대로 진행 가능.
- **그러나 #2/#3에서 스펙 §4.3 `toRateLimitRequest` 정규화 설계를 무효화하는 구조적 불일치를 발견했다.** 브리프/스펙이 예상한 것은 "값이 있는데 단위만 다름(분수 vs 퍼센트, 초 vs ms) — 정규화가 두 경우 다 흡수"였지만, 실측은 **필드가 있어야 할 자리에 아예 없고, 실제 값은 다른 경로(`unifiedWindows`)에 있다**는 것이다:
  - 계획서(`docs/superpowers/plans/2026-09-05-question-chat-ui.md` Task 7)의 `toRateLimitRequest(info)`는 `info.utilization`을 읽어 "숫자가 아니면 0"으로 폴백한다. 실물 이벤트를 그대로 넣으면 `info.utilization === undefined`이므로 **항상 `utilization: 0`을 Java에 보고하게 된다** — 예외도 안 나고 Task 7의 유닛테스트(전부 `{utilization: 0.42, ...}` 같은 합성 flat 객체만 검증)도 못 잡는, 배포 후 사용량 패널이 조용히 항상 0%로만 뜨는 종류의 버그다.
  - 스펙 §4.1 "한 턴에 여러 이벤트(five_hour/seven_day 각각)가 올 수 있다 — 타입별로 각각 upsert"라는 전제도 실측과 다르다. 이번 실행에서는 **이벤트 1건에 두 타입(`five_hour`+`seven_day`)이 `unifiedWindows`로 동봉**되어 왔다(별도 이벤트 2건이 아니었다). `toRateLimitRequest(info): WorkerRateLimitRequest | null` 같은 "이벤트 1건 → 요청 0/1건" 구조로는 `unifiedWindows`의 두 번째 타입을 표현할 수 없다.
  - `resetsAt`/`status`/`isUsingOverage`는 top-level에 실재하고 실측과 일치하므로 그대로 쓸 수 있다 — 문제는 `utilization`의 위치와 "이벤트당 type 개수" 두 가지뿐이다.
- **권고: Task 7 착수 전에 이 구조적 불일치를 사용자에게 보고할 것.** 0건 게이트는 아니지만, "정규화가 알아서 흡수한다"던 전제 자체가 깨졌으므로 동등하게 중요한 조기 경보로 판단한다. `unifiedWindows`를 못 보고 top-level `utilization`만 보도록 그대로 구현하면 기능은 배포되지만 데이터가 항상 0%로만 나오는, 통합테스트 없이는 못 잡는 실패가 된다.

## Task 7로 넘기는 체크리스트

- `rate_limit_info.utilization`이 아니라 `rate_limit_info.unifiedWindows?.[rate_limit_info.rateLimitType]?.utilization`을 우선 읽고, `unifiedWindows`가 없으면(구버전 CLI 등) top-level `utilization`으로 폴백하는 순서를 권장.
- `unifiedWindows`에 **다른 키**(이번 캡처의 `seven_day`)가 더 있으면 각각 별도 `WorkerRateLimitRequest`로 변환해 **여러 건 upsert**해야 한다 — `toRateLimitRequest`를 배열 반환으로 바꾸거나, 호출부(`RateLimitReporter.report`)가 `unifiedWindows` 순회를 맡도록 재설계가 필요하다(현재 계획서의 단일 반환 시그니처로는 표현 불가).
- `unifiedWindows.<type>`에는 자체 `status`가 없다 — top-level `rateLimitType`과 일치하는 타입만 top-level `status`를 쓰고, 나머지는 스펙 기본 규칙대로 `allowed`로 채우는 정도가 이번 실측 범위 안에서 가능한 최선이다(다른 status를 가진 unifiedWindows 항목은 이번 스파이크로 관측 못함 — 화이트박스 가정임을 명시할 것).
- `resetsAt`(top-level)/`isUsingOverage`/`status`는 계획서 그대로 써도 된다 — 이번 실측과 일치.
- `assistant.message.usage`/`message_start.usage`(컨텍스트 경로, Task 5/6)는 가정대로 안전하다 — 변경 불필요.
