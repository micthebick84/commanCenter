# 인터뷰 완료감지 재설계 — 설계 스펙 (2026-06-22)

## 1. 배경 / 문제

대화형 분석 인터뷰는 워커(interview-service)가 매 턴 claude를 돌린 뒤, 어시스턴트 출력에서 "구현 계획"을 **harvest** 하면 `PLAN_READY`로 완료된다. 현재 완료감지가 두 단계로 어긋나 있어 실패한다.

- `interviewRunner.ts`: 느슨한 트리거 `/Implementation Plan/m` 이 통과하면 harvest 시도.
- `planHarvest.ts`: 엄격한 영문 구조 요구 — 헤더 `^#\s+.*Implementation Plan$`(H1) + 작업 `^###\s+Task\s+(\d+):`(영문).

에이전트는 프로젝트 한국어 관례대로 `# … 구현 계획` / `### 작업 N:` 으로 출력한다. 결과:

- **하드 FAIL**: 본문에 "Implementation Plan" 문구가 있어 트리거는 발동하나, 엄격 정규식 불일치로 `HarvestError` → 인터뷰 `FAILED`. (실관측: 세션 12, `plan harvest failed: no "Implementation Plan" header found in transcript`)
- **무한 루프**: 영문 문구가 전혀 없으면 트리거 미발동 → 매 턴 `postQuestion`(AWAITING_INPUT) → active 상태라 새로고침마다 자동 재개, 영원히 종료 안 됨.
- **무력한 안전망**: 기존 `CostGuard`는 구독 인증에서 `total_cost_usd`가 항상 0이라 절대 트립되지 않는다. (PR #8에서 누적 시드로 바꿨으나 cost=0이라 여전히 무력)

## 2. 목표 / 비목표

**목표**
- 한국어 plan에서도 완료가 **정상 감지**되어 인터뷰가 `PLAN_READY`로 끝난다.
- 추출 실패(near-miss)가 **하드 FAIL이 아니라 회복(보정 후 계속)** 된다.
- 끝나지 않는 대화에 **실효 백스톱**(턴 상한)이 있어 결국 종료된다.

**비목표**
- 백엔드(Java) 상태머신·DB 스키마·SSE 계약 변경 없음. (claim 페이로드가 이미 `turns`를 실어주므로 워커가 진행 턴 수를 안다)
- 인터뷰의 대화 품질/프롬프트 전반 개편이 아니라 **완료감지 경로**만 손본다.
- planJson 스키마(`[{task, title}]`) 변경 없음.

## 3. 핵심 설계 결정 (브레인스토밍 확정)

1. **plan 언어 = 한국어**, harvest를 **이중언어화**(한/영, H1/H2 인식).
2. **near-miss = 하드 FAIL 금지** → **1회 형식 보정 요청(splice)** 후, 그래도 안 되면 일반 질문으로 대화 계속.
3. **턴 상한 ~20**(설정화). 상한 직전 **force-finish**(지정 형식 plan 강제 요청), 그래도 harvest 실패면 `FAILED`(사유 명시).

## 4. 상세 설계

전 변경은 **워커(`netismaker-interview-service`) 국한**. 백엔드 변경 없음.

### 4.1 `planHarvest.ts` — 이중언어 인식

정규식을 한/영·H1/H2 모두 매칭하도록 확장한다.

```
PLAN_HEADER = /^#{1,2}\s+.*(Implementation Plan|구현\s*계획)\s*$/m
TASK_LINE   = /^###\s+(?:Task|작업|태스크)\s+(\d+)\s*[:：]\s*(.+?)\s*$/gm
```

- 헤더: `#` 또는 `##`, 줄 끝이 "Implementation Plan" 또는 "구현 계획".
- 작업: `### Task|작업|태스크 N:` (반각/전각 콜론 `:`/`：` 허용).
- 동작·반환(`Harvest{designMarkdown, planMarkdown, planJson}`)·`HarvestError`는 그대로. 헤더 이전 = design, 이후 = plan, `### 작업 N:` 들을 planJson으로 인덱싱. 작업 0개면 기존대로 `HarvestError`.

### 4.2 `interviewRunner.ts` — 완료 트리거를 "harvest 시도"로 교체

느슨한 문구매칭(`/Implementation Plan/m`)으로 분기하던 것을 **harvest 시도/성공 여부**로 바꾼다.

- `tryHarvest(text)`: `harvestPlan`을 try/catch로 감싸 `{ ok: true, harvest } | { ok: false }` 반환(순수 헬퍼, 테스트 용이).
- `harvest 성공 → postPlan(PLAN_READY)`; **실패는 더 이상 곧장 FAIL이 아니다**(아래 4.3/4.4).

기존 handoff 시(brainstorming→writing-plans) splice 재실행 로직은 유지하되, 내부 `hasPlan` 판정을 4.1의 이중언어 헤더로 교체한다.

### 4.3 near-miss 보정 (continue, 보정 1회)

harvest 실패 시:

1. **plan 의도 신호** 판정 `detectPlanIntent(text)` = `detectHandoff(text)` 또는 `/(Implementation Plan|구현\s*계획)/i` 매칭 (≈ 기존 느슨한 트리거, 단 이제 "보정 시도" 신호로만 쓰임).
2. plan 의도가 있고 **아직 보정 안 했으면** → `buildPlanReformatSplice()` 를 같은 세션에 splice → 1회 재실행 → 재harvest. 성공 시 `postPlan`.
3. 그래도 실패하거나 plan 의도가 없으면 → **`postQuestion`(AWAITING_INPUT)로 대화 계속**. (FAIL 아님)

`buildPlanReformatSplice()`(skillDispatch): "지금까지 논의로 최종 plan을 **`# <기능> 구현 계획` H1 + 각 작업을 `### 작업 N: <제목>`** 형식으로 한 번에 제시하라. 구현/빌드/커밋 금지." (한국어 정규 구조 명시)

### 4.4 턴 상한 + force-finish (워커측, `claim.turns` 기반)

- `assistantTurnCount(claim.turns)` = `role === 'assistant'` 턴 수(질문+design). 진행 턴 지표.
- 상수(설정화): `INTERVIEW_MAX_TURNS`(기본 20), `INTERVIEW_FORCE_FINISH_TURNS`(기본 = MAX-1 = 19).
- **claim 직후, 턴 실행 전**:
  - `n >= MAX` → 실행 없이 `client.fail("최대 질문 턴(${MAX}) 초과 — plan 미완성")` → FAILED. (런어웨이 종결)
  - `n >= FORCE_FINISH` → 이번 턴 프롬프트를 **force-finish**(=`buildPlanReformatSplice` 와 동일 취지: 지금 지정 형식으로 plan 확정)로 대체. 일반 resume(lastAnswer) 대신 사용.
- force-finish 턴은 **이미 reformat 프롬프트**이므로, harvest 실패 시 4.3의 추가 보정 splice는 **생략**(이중 splice 방지)하고 곧장 `postQuestion`. 다음 claim에서 `n`이 MAX에 도달하면 FAIL. 즉 4.3 보정은 **normal 모드에서만** 적용.
- `config.ts`에 `maxTurns`, `forceFinishTurns` 추가(env override). `INTERVIEW_QUOTA_GUARD`처럼 기본값 내장.

### 4.5 프롬프트 보강

킥오프(`promptFor`)와 writing-plans splice(`buildWritingPlansSplice`)의 헤더 지시를 **한국어 정규 구조**로 갱신(PR #8의 영문-only 지시 대체):

> 최종 plan은 `# <기능> 구현 계획` H1 + 각 작업을 `### 작업 N: <제목>` 형식으로(설명은 한국어). 영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용. 이 구조라야 시스템이 완료를 인식한다.

### 4.6 `CostGuard` 처리

구독 인증에서 cost=0이라 무력하므로 **실효 백스톱은 4.4 턴 상한**임을 코드 주석·이 문서에 명시. `CostGuard` 자체는 제거하지 않는다(API-key 모드에서 유효한 비용 상한이며 무해). PR #8의 `claim.totalCostUsd` 시드도 유지.

## 5. 영향 파일

| 파일 | 변경 |
|---|---|
| `src/runner/planHarvest.ts` | `PLAN_HEADER`/`TASK_LINE` 이중언어화 |
| `src/runner/interviewRunner.ts` | 트리거=harvest 시도, near-miss 보정, 턴 상한/force-finish, `assistantTurnCount` |
| `src/runner/skillDispatch.ts` | `buildPlanReformatSplice` 추가, 프롬프트 문구(한국어 구조) |
| `src/config.ts` | `maxTurns`/`forceFinishTurns`(env) |
| `test/planHarvest.test.ts`, `test/interviewRunner.test.ts`, `test/skillDispatch.test.ts`, `test/config.test.ts` | 아래 6 |

백엔드/Java/프론트 변경 없음.

## 6. 테스트 계획 (TDD, RED→GREEN)

- **planHarvest**: 한글 헤더(`# … 구현 계획`)+`### 작업 N:` harvest 성공 / H2(`## … 구현 계획`) 성공 / 전각 콜론 성공 / 영문 구조 회귀 통과 / 헤더 없음 → HarvestError / 작업 0개 → HarvestError.
- **interviewRunner**:
  - harvest 성공(한글 plan) → `postPlan`, `postQuestion` 미호출.
  - near-miss(plan 의도 있으나 추출 실패) → reformat splice 1회 재실행 후 성공 → `postPlan`; `fail` 미호출.
  - near-miss인데 보정해도 실패 → `postQuestion`(대화 계속), `fail` 미호출.
  - plan 의도 없는 일반 질문 → 보정 splice 없이 `postQuestion`.
  - `claim.turns` assistant 수 `>= MAX` → 실행 없이 `fail("최대 질문 턴")`.
  - `>= FORCE_FINISH` → 프롬프트가 force-finish(정규 구조 요구 문구 포함).
- **skillDispatch**: `buildPlanReformatSplice` 가 한국어 정규 구조(`# … 구현 계획`, `### 작업 N:`) 문구 포함.
- **config**: `maxTurns`/`forceFinishTurns` env override/기본값.

## 7. 범위 밖 / 향후

- 구조화 출력(에이전트가 plan을 전용 tool/JSON으로 신호) 방식은 더 견고하나 이번 범위 밖(향후 V2 후보).
- SSE 끊김 시 패널이 FAILED를 못 받는 표시 갭(긴 턴 중 재연결)은 별도 이슈로 분리(이번 변경과 무관, 백엔드 `/fail`은 이미 status/done push).

## 8. 리스크

- 에이전트가 force-finish에도 정규 구조를 안 지키면 결국 FAIL — 단, near-miss 보정 + force-finish로 두 번 기회를 주고, 프롬프트에 구조를 명시하므로 정상 케이스 회복률↑.
- `assistantTurnCount`가 design 턴까지 포함하면 상한이 더 빨리 찰 수 있음 → 기본 MAX 20은 실관측(세션 12 ~9 assistant 턴) 대비 넉넉.
- 정규식 이중언어화로 인한 오탐 위험은 낮음(헤더는 "구현 계획"/"Implementation Plan" 줄 끝 한정, 작업은 `### 작업/Task N:` 한정).
