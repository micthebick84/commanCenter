# 교차 플랜 일관성 검토 (cross-plan review)

Now I have the full spec. Let me cross-check the exact contract names across plans. Let me verify a few specific concerns by checking the actual field/event names used in each plan against each other.

The key contract surfaces to verify:
1. SSE event names: spec says `question`, `design`, `plan_ready`, `status`, `done`
2. Worker endpoint paths and DTO fields (`WorkerQuestionRequest`, `WorkerPlanRequest`, claim response)
3. `replyToSeq` idempotency field
4. Status enum Korean labels
5. The claim response shape (`InterviewClaimResponse` in plan 01 vs `WorkerInterviewResponse` in plan 02 vs `ClaimResponse` in plan 03)

Let me trace these carefully across the plans. I have enough to produce the review.

커버리지 갭 / 계약 불일치 / 순서 문제 / placeholder — 4개 카테고리 한국어 리뷰:

---

## 1. 커버리지 갭 (SPEC COVERAGE)

- **§8 `GET /api/interviews/{id}` 세션 상세 — 부분 갭.** 스펙은 사용자 목록/상세 ACL을 요구. plan 02가 `GET /{id}`를 구현(Task 5). 단 **plan 01의 `findByRequester`(목록 조회)에 대응하는 `GET /api/interviews` 목록 엔드포인트가 어느 plan에도 없음.** 스펙 §8에는 목록 엔드포인트가 명시되지 않으므로 엄밀히는 갭 아님이나, 프론트(plan 04)는 다이얼로그 내 단일 세션만 다루므로 일관됨. **조치 불필요 (스펙 충족).**
- **§4 ⑤ / §16 `GitRepoCache` depth-1 clone — 갭.** 스펙은 "워커가 레포를 clone 후 읽는다"(Q2, §6 step2)고 요구하나, **clone을 실제로 수행하는 주체가 plan 03 어디에도 없음.** plan 03은 `claim.repoDir`을 이미 존재하는 체크아웃으로 가정(`types.ts`의 `repoDir`, `claims.ts` fixture `/tmp/repos/...`)하고 `cwd`로만 사용. **plan 01/02의 `WorkerInterviewResponse`/`InterviewClaimResponse`에는 `repoDir` 필드 자체가 없음**(아래 계약 불일치 참조). 조치: **clone 책임을 plan 03 `claimLoop`/`interviewRunner` 또는 Java claim 시점에 명시**하고, claim 응답에 `repoDir`(또는 `githubRepo`+`commitSha`로 워커가 clone)를 추가할 것. 현재는 "레포 코드를 읽는다"는 핵심 성공기준 #1이 어느 plan에서도 구동되지 않음.
- **§12 / §15 stale 회수 (`StaleTaskRecoveryJob` 유사) — 갭.** 스펙 §5 "RUNNING 중 워커 사망 → QUEUED 재큐/FAILED", §12 첫 행이 명시. plan 01은 `findInFlightClaimed()`(RUNNING 스캔) 리포지토리 메서드만 추가하고 **회수 잡 자체는 어느 plan에도 없음.** plan 00 findings가 idle TTL/EXPIRED 상수를 결정하지만 expire/stale을 호출하는 스케줄러가 미구현. 조치: **plan 01 또는 02에 인터뷰용 stale-recovery + idle-TTL expire 스케줄러 Task 추가** (plan 01 `InterviewService.expire`/`fail`은 존재하나 호출자 없음).
- **§9 / §3 비용 추적 양분 — 부분 갭.** plan 03 `CostGuard`는 SDK 사이드에서 cap 검사 후 `fail` 호출. plan 01/02는 `total_cost_usd` 누적(`addCost`)만. 일관되나, **세션당 비용 상한값(`INTERVIEW_COST_CAP_USD`)이 SDK env에만 있고 Java에는 없음** — Java는 cap을 모름. 스펙 §9 "세션당 비용 상한(초과 시 FAILED)"은 SDK 단독으로 충족되므로 **조치 불필요**, 단 상한이 두 곳에서 설정되지 않도록 단일 출처(SDK env) 유지.

그 외 §1~§16 주요 요구(상태머신 §5, 데이터흐름 §6, V11 §7, SSE §8, register→COMPLETED §5/Q5, 분할뷰 §10, 스파이크 §13)는 모두 plan에 매핑됨.

---

## 2. 계약 불일치 (CONTRACT CONSISTENCY) — 정확한 이름

backend가 정의, sdk-service & frontend가 소비. 발견된 불일치:

- **【치명】 claim 응답 DTO 이름·타입 3중 분기.**
  - plan 01: `InterviewClaimResponse` (record, 필드 `id, githubRepo, githubBranch, title, description, claudeSessionId, currentPhase, mcpsExtra, lastUserAnswer, turns`)
  - plan 02: `WorkerInterviewResponse` (record, 필드 `id, githubRepo, githubBranch, title, description, claudeSessionId, lastAnswer, currentPhase, turns`)
  - plan 03(sdk): `ClaimResponse` (interface, 필드 `sessionId, githubRepo, githubBranch, title, description, claudeSessionId, currentPhase, lastAnswer, replyToSeq, totalCostUsd, repoDir`)
  
  **불일치 항목:**
  - 세션 ID 필드: plan 01/02 = `id` ↔ plan 03 = **`sessionId`**. SDK가 `claim.sessionId`를 읽으므로 Java가 `id`로 직렬화하면 `undefined`. **조치: Java DTO를 `sessionId`로 통일하거나 SDK를 `id`로 수정.**
  - 마지막 답변 필드: plan 01 = **`lastUserAnswer`** ↔ plan 02 = `lastAnswer` ↔ plan 03 = `lastAnswer`. **조치: plan 01의 `InterviewClaimResponse.lastUserAnswer`를 `lastAnswer`로 변경**(plan 02/03 다수결, 그리고 plan 01 자체가 Phase 2 plan 02에 의해 대체됨).
  - SDK 전용 필드 `replyToSeq, totalCostUsd, repoDir`이 **plan 01/02 claim DTO에 없음.** SDK는 `claim.totalCostUsd`(cost cap 누적 기준)와 `claim.repoDir`(cwd)을 필수로 읽음 → 없으면 cost cap이 0부터 재계산되고 cwd가 `undefined`. **조치: plan 02 `WorkerInterviewResponse`에 `totalCostUsd`, `repoDir`(또는 clone 책임 재배치), `replyToSeq` 추가.**

- **【치명】 DTO 클래스 중복 정의: plan 01 vs plan 02.** plan 01 Task 6과 plan 02 Task 2/3 둘 다 `CreateInterviewRequest`, `AnswerRequest`, `WorkerQuestionRequest`, `WorkerPlanRequest`, `InterviewStatus`, `InterviewStatusConverter`를 **Create**한다. 같은 파일을 두 번 Create → 두 번째 plan 실행 시 충돌/덮어쓰기. 또한 **`InterviewStatusConverter`의 `autoApply`가 상충**: plan 01 = `@Converter(autoApply = true)`, plan 02 = `@Converter(autoApply = false)` + 엔티티에 `@Convert` 명시. **조치: plan 01이 Phase 1, plan 02가 Phase 2로 "이미 존재 시 skip"을 명시하므로, 02가 01과 동일 시그니처를 재정의하지 않도록 02 Task 1~3를 "01 산출물 재사용, 없을 때만 생성"으로 못박을 것.** `autoApply`는 한 값으로 통일(권장 `false` + 명시 `@Convert` — TaskStatus와의 충돌 회피, plan 02 근거가 더 정확).

- **【주의】 `register` 시 `TaskAnalysis.create` 인자 매핑 상충.** plan 01 Task 8 `register`는 `composeAnalysisMarkdown(plan)`으로 **설계+플랜 합본**을 `markdownResult`로, plan 02/Q5는 `markdownResult = design_markdown`(설계만). plan 02 Task 7 테스트가 `a.getMarkdownResult()).isEqualTo("# 설계 문서")`(설계만)로 단언 → **plan 01 합본 구현이면 plan 02 테스트가 실패.** 또한 plan 01은 `TaskAnalysis.create(id, md, planJson, "대화형 분석…", durationMs)`(5인자), plan 02는 `TaskAnalysis.create(id, designMarkdown, planJson, null, durationMs)`(claudeLog=null). **조치: register 구현은 plan 02(Phase 2)를 정본으로 채택 — `markdownResult=design_markdown` 단독, 4번째 인자 null.** plan 01의 `composeAnalysisMarkdown` 합본 방식 폐기.

- **【주의】 claim 정렬 순서 불일치.** plan 01 `findClaimableForUpdateSkipLocked`은 `ORDER BY s.createdAt ASC`, plan 02 Task 6은 `ORDER BY s.lastActivityAt ASC`. 같은 메서드를 두 plan이 다르게 정의. 답변 후 재큐 FIFO 의미가 달라짐(생성순 vs 활동순). **조치: 하나로 통일.** 스펙 §5 "FIFO"만 명시 → `lastActivityAt ASC`(답변 도착이 늦은 세션을 뒤로)가 재큐 공정성에 적합하나, 팀이 명시 선택해 한 plan으로 고정.

- **【주의】 `answer` idempotency 키 메커니즘 불일치.** plan 01 `submitAnswer`은 **turn 스캔**(`replyToSeq 이후 user answer 턴 존재 여부`)으로 dedup, `interview_turn`에 `reply_to_seq` 컬럼 **없음**. plan 02 Task 5는 `turnRepo.existsBySessionIdAndRoleAndReplyToSeq(id,"user",replyToSeq)`를 쓰며 **`interview_turn.reply_to_seq` 컬럼 신설(`V12` 마이그레이션) + 엔티티 필드 + 팩토리 인자 추가**를 요구. 즉 plan 02는 plan 01의 `InterviewTurn.of(sessionId, seq, role, kind, content)` 5인자 팩토리와 충돌(reply_to_seq 인자 추가 필요). **조치: idempotency 방식을 plan 02(reply_to_seq 컬럼)로 통일하고, plan 01 `InterviewTurn` 팩토리/마이그레이션에 `reply_to_seq`를 처음부터 포함**(V12 분리 대신 V11에 합치는 것을 권장 — 아래 순서 문제 참조).

- **【주의】 `claudeSessionId` 비-null 타입 계약.** SDK plan 03 `WorkerQuestionRequest.claudeSessionId: string`(non-null) + `interviewRunner`가 `result.sessionId ?? claim.claudeSessionId ?? ''`로 **빈 문자열 가능**. Java plan 01 `recordQuestion`은 `if (req.claudeSessionId() != null && !isBlank())`로 빈 문자열 무시(안전). plan 02 `recordQuestion`은 `if (req.claudeSessionId() != null) s.setClaudeSessionId(...)` → **빈 문자열을 그대로 저장**하여 다음 resume이 `resume:''`로 깨질 수 있음. **조치: plan 02 `recordQuestion`도 blank 가드 추가**(plan 01 패턴 채택).

- **【확인·일치】** SSE event 이름(`question`/`design`/`plan_ready`/`status`/`done`)은 plan 02 `InterviewStreamService`(정의) ↔ plan 04 `useInterviewStream`(소비) **정확히 일치.** 상태 한글 라벨 8종(`인터뷰대기/인터뷰중/입력대기/플랜완료/등록됨/취소됨/만료됨/인터뷰실패`)은 plan 01·02 enum **일치**(단, plan 04는 SSE `status` 이벤트에서 영문 enum명 `QUEUED/...`를 기대하는데 **plan 02 컨트롤러는 `InterviewStatus.AWAITING_INPUT.dbValue()`=한글을 push** → 아래 추가 불일치).

- **【치명】 SSE `status` 이벤트 값: 한글 vs 영문.** plan 02 `InterviewWorkerController`/`InterviewController`는 `pushStatus(id, InterviewStatus.X.dbValue())`로 **한글 라벨**(`"입력대기"`, `"플랜완료"`, `"인터뷰대기"`)을 전송. plan 04 `useInterviewStream.onStatus`는 `data.status`를 `InterviewStatus`(영문 `'AWAITING_INPUT'|'PLAN_READY'|...`)로 캐스팅하고 `TERMINAL` 배열(영문)과 비교 → **한글이 오면 매칭 실패: 터미널 자동 close 안 됨, 배지 표시도 영문 기대.** plan 04 테스트는 `emit('status',{status:'EXPIRED'})`(영문)로만 검증해 이 불일치를 못 잡음. **조치: 한 표현으로 통일.** 권장: 백엔드가 영문 enum명(`status.name()`)을 SSE로 push(프론트가 enum 키로 처리), 또는 프론트가 `dbValue→enum` 역매핑. 정의자인 backend(plan 02)를 영문 push로 수정하는 쪽이 plan 04 코드와 테스트 모두에 부합.

- **【주의】 `plan_ready` 페이로드 shape 불일치.** plan 02 컨트롤러 `pushPlanReady(id, req.designMarkdown())`로 **design 마크다운 문자열 1개**만 보냄. plan 04 `onPlanReady`는 `data.designMarkdown / data.planMarkdown / data.planJson` **객체**를 기대(`plan.planMarkdown` 단언). → 프론트가 `JSON.parse("# 설계…")`에서 파싱 실패로 plan 무시. plan 04 테스트는 객체를 emit해 통과하지만 실서버 페이로드와 불일치. **조치: plan 02 `pushPlanReady`가 `{designMarkdown, planMarkdown, planJson}` JSON을 보내도록 수정**(`InterviewStreamService.pushPlanReady(payloadJson)` 시그니처는 이미 JSON 문자열을 받게 돼 있으나 컨트롤러가 design만 전달 → 컨트롤러에서 plan 객체 직렬화).

- **【경미】 SDK 의존성 버전 불일치.** plan 00 스파이크는 `@anthropic-ai/claude-agent-sdk@0.2.117`(핀), plan 03 프로덕션은 `^0.1.0`. 스파이크가 검증한 버전과 실제 서비스 버전이 다름 → 스파이크 결론 무효 위험. **조치: plan 03 `package.json`을 `0.2.117`로 정렬**(또는 스파이크 검증 버전으로 핀).

- **【경미】 worker 인증 헤더 이름.** Java(spec §8) = `X-Worker-API-Key`. plan 03 `javaClient` = `'X-Worker-API-Key'` **일치.** 추가로 plan 03은 `'X-Worker-Id'`도 보내나 Java는 body `workerId` 파라미터를 씀 — plan 02 `claim(@RequestParam String workerId)`은 **쿼리 파라미터** 기대, plan 03 `javaClient.claim`은 **body `{workerId}`** 전송. **불일치: 조치 — plan 03 claim을 `?workerId=` 쿼리로 보내거나 plan 02 컨트롤러를 `@RequestBody`로 변경.** (plan 02 워커 테스트는 `.param("workerId",...)`로 쿼리 사용 → plan 03을 쿼리로 맞추는 것이 일관.)

---

## 3. 순서 문제 (DEPENDENCY ORDER) — 00→01→02→03→04

- **00 (스파이크) → 03 (SDK): 올바름, 단 03이 00 결과를 일부 무시.** plan 03 Task 4/9/10/15는 "Phase-0 spike 결과에 contingent"라고 명시하나 `promptCaching` 등 옵션명을 00 검증 없이 하드코딩. plan 00이 SDK 0.2.117을 검증했는데 plan 03은 `^0.1.0`을 깔아 **00의 검증이 03에 전이 안 됨**(위 버전 불일치). 순서상으로는 00이 먼저면 됨. **조치: 03 버전 핀 정렬(위 참조).**
- **01 → 02: 깨짐 위험 (중복 Create).** 02는 "Phase 1(01) 이미 존재 가정"이라 명시하나 **Task 1~3에서 01과 동일 파일을 Create로 재작성.** 순서대로 01 후 02 실행 시 02의 `Create InterviewStatus.java`가 이미 존재하는 파일과 충돌(Write는 기존 파일 덮어쓰기 전 Read 필요/실패 가능). 02가 `autoApply`·정렬순서·`TaskAnalysis.create` 인자를 01과 다르게 정의하므로 **덮어쓰면 01의 Task 9 동시성 테스트가 정렬순서 변경으로 영향**받을 수 있음. **조치: 02 Task 1~3·Task 7을 "01 산출물 검증 후 차이분만 패치"로 재서술**(02 Task 0이 이 의도를 가지나 Task 1~3 본문이 무조건 Create로 작성됨 → 모순). 정본 결정: register 매핑·idempotency·converter autoApply·claim 정렬은 **02를 정본**으로, 01을 02에 맞춰 수정.
- **01/02 → 03: claim DTO 필드명(`sessionId`/`lastAnswer`/`repoDir`/`totalCostUsd`)이 깨짐(위 §2).** 03이 02 산출 JSON을 그대로 역직렬화하므로 02가 먼저 확정돼야 하나 필드명이 어긋나면 런타임 실패. 순서는 맞으나 **계약 동기화가 선행 조건.**
- **02 → 04: SSE `status` 한글/영문 + `plan_ready` shape 불일치로 깨짐(위 §2).** 02가 정의한 페이로드를 04가 소비하는데 형식이 어긋남. 순서는 맞으나 계약 수정 필요.
- **V11 vs V12 마이그레이션 순서.** plan 01은 V11에 `reply_to_seq` 없이 작성, plan 02가 V12로 `ALTER TABLE … ADD reply_to_seq`를 추가하는 분기. 02 Task 0/5가 "Phase 1에 없으면 V12 추가"라 하나, **01·02를 한 PR로 합치는 흐름이면 V11에 처음부터 넣는 게 정합.** **조치: V11에 `reply_to_seq INT` 포함하고 V12 폐기**(01 마이그레이션 수정). 04→03→02→01 역참조는 없음(프론트/SDK가 Java를 호출하는 단방향) — 순서 자체는 00→01→02→03→04가 옳음.

---

## 4. PLACEHOLDER 스캔

- **plan 00:** `00-spikes-findings.md` 골격에 `<...>`, `<PASS/FAIL>`, `<확정/조정>`, `<값>`, `<계산값>`, `<관찰값>` 자리표시자가 **의도적으로 다수 존재.** 단 Task 6 Step 3이 `grep`으로 잔존 placeholder를 차단하고 "NO PLACEHOLDERS LEFT"를 강제 → **실행 후 제거 보장됨. 계획상 정상(템플릿).** 조치 불필요.
- **plan 01:** placeholder 없음. 모든 코드 블록 완전. 단 Task 9 Step 3의 seed user id(`"testuser"`)는 `init-test-schema.sql` 확인 후 치환 지시가 있음 — 하드코딩 가정값이나 검증 step 동반. 경미.
- **plan 02:** Task 0/3/4가 "Phase 1 산출물의 정확한 메서드명/팩토리 시그니처에 맞춰 조정"이라는 **조건부 자리표시 다수**(`InterviewTurn.of(...)` 시그니처 미확정, `findBySessionIdOrderBySeqAsc` 이름 미확정). 실제 placeholder 토큰은 없으나 **"adjust to Phase 1" 미해결 지점이 계약 불일치(§2)의 원인.** 조치: 01 확정 후 02의 "조정" 지점을 실제 이름으로 고정.
- **plan 03:** placeholder 없음. `SUPERPOWERS_PLUGIN_PATH`, `API_BASE_URL=http://localhost:8090` 등 구체값 채워짐. 단 SDK 버전 `^0.1.0`은 실수값(위 §2). 
- **plan 04:** placeholder 없음. 단 `useApi` 호출 URL이 `'/api/interviews/...'`로 하드코딩되어 `useRuntimeConfig().public.apiBaseUrl`(SSE에서는 사용)과 **REST 경로 baseUrl 처리 비일관**(SSE는 config 기반, REST는 리터럴 `/api`). 기능상 동작하나 경미한 일관성 흠.

---

**요약 (정본 결정 권고):** 가장 시급한 수정 3건 — (1) **claim 응답 DTO 필드명 통일**(`sessionId`, `lastAnswer`, `repoDir`+`totalCostUsd` 추가), (2) **SSE `status` 값 영문 enum명으로 통일**(plan 02 backend 수정 → plan 04와 정합), (3) **`plan_ready` 페이로드를 `{designMarkdown,planMarkdown,planJson}` 객체로** push. 그 외 register 매핑·idempotency·converter는 **plan 02를 정본으로 01을 정렬**하고, **clone 책임(§4⑤)과 stale-recovery(§12) 누락 Task를 추가**하면 4개 plan이 정합.
