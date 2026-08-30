# 단계별 토큰 사용량·비용 추적 및 표시 — 설계

날짜: 2026-08-31
상태: 사용자 설계 승인 완료 (섹션별 승인)

## 1. 목표

작업(task)의 각 단계(인터뷰·분석·디자인·구현)에서 사용된 Claude 토큰 수와 비용(USD)을 수집·저장하고,
작업 상세 페이지에 단계별로, 작업 목록에 총합산으로 표시한다.

- 배포/배포중지 단계는 Claude를 사용하지 않으므로 제외.
- 한 단계가 여러 번 실행되면(구현 재시도, 디자인 반려 후 재생성, 재인터뷰 등) **모든 실행을 누적**한다.
  실패한 실행의 지출도 누적에 포함한다 (실지출 반영).
- 과거 작업(수집 이전 데이터)은 백필하지 않고 UI에서 표시를 생략한다.

## 2. 현재 상태 (탐색 결과)

| 단계 | Claude 실행 방식 | 현재 저장 | 토큰 | 비용 |
|---|---|---|---|---|
| 인터뷰 | 인터뷰 서비스(SDK) | `interview_session.total_cost_usd` 턴별 누적, `interview_plan.total_cost_usd` | ✗ | ✓ |
| 분석 | 워커 `claude -p` (plain text stdout) | `task_analysis.duration_ms` | ✗ | ✗ |
| 디자인 | 워커 `claude -p` | `task_design.duration_ms` | ✗ | ✗ |
| 구현 | 워커 `claude -p` | `implementation_log` | ✗ | ✗ |

워커 파이프라인은 `ClaudeExecAdapter`가 stdout(plain text)을 그대로 `PromptResultParser`(분석),
`DesignResultHarvester`(디자인), 구현 로그로 넘기는 구조라 usage 데이터가 존재하지 않는다.
인터뷰 서비스 `messageRelay`는 SDK result 메시지에서 `usage.total_cost_usd`만 읽고 토큰 수는 버린다.

## 3. 스키마 — `V21__stage_usage.sql`

```sql
CREATE TABLE IF NOT EXISTS com.task_stage_usage (
    task_id  BIGINT      NOT NULL REFERENCES com.task(id) ON DELETE CASCADE,
    stage    VARCHAR(20) NOT NULL,
             -- INTERVIEW | ANALYSIS | DESIGN | IMPLEMENTATION
    cost_usd              NUMERIC(12,6) NOT NULL DEFAULT 0,
    input_tokens          BIGINT NOT NULL DEFAULT 0,
    output_tokens         BIGINT NOT NULL DEFAULT 0,
    cache_creation_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens     BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, stage)
);
```

- 누적은 원자적 upsert:
  `INSERT … ON CONFLICT (task_id, stage) DO UPDATE SET cost_usd = task_stage_usage.cost_usd + EXCLUDED.cost_usd, …` (토큰 4컬럼 동일, `updated_at = now()`).
- `com.interview_session`에 토큰 4컬럼 추가 (`input_tokens`, `output_tokens`, `cache_creation_tokens`, `cache_read_tokens`,
  모두 `BIGINT NOT NULL DEFAULT 0`) — 기존 `total_cost_usd`와 같은 방식으로 턴마다 누적.
- `interview_plan`은 변경하지 않는다 (YAGNI).
- stage 값은 CHECK 제약 없는 VARCHAR — 기존 컨벤션(V11 status와 동일) 유지.

## 4. 수집 경로

### 4.1 워커 (분석·디자인·구현)

**`ClaudeExecAdapter`:**
- `buildCommand`에 `--output-format json` 추가.
- `redirectErrorStream(true)` 제거 → stderr 별도 드레인 스레드로 수집 (stdout JSON 오염 방지; 실패 시 tail 로깅에 사용).
- stdout의 JSON envelope(`{result, usage:{input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens}, total_cost_usd, …}`)을
  Jackson으로 파싱 → `ExecResult(exitCode, resultText, usage, durationMs)`.
  `resultText`(= envelope의 `result`)는 기존 plain stdout과 동일 내용이므로
  `PromptResultParser`·`DesignResultHarvester`·구현 로그 소비부는 **무변경**.
- **Fallback:** envelope 파싱 실패 시 raw stdout을 `resultText`로 사용하고 usage=null.
  수집 실패가 본 파이프라인을 실패시키지 않는다 (경고 로그만).

**보고 계약 (`WorkerResultRequest`):** usage 5필드 추가 (전 phase 공용, nullable):
`costUsd`(BigDecimal), `inputTokens`, `outputTokens`, `cacheCreationTokens`, `cacheReadTokens`(Long).
`WorkerMainLoop`의 분석/구현/디자인 성공·실패 보고 전부에 usage를 싣는다 (모든 실행 누적 결정 반영).

**기록 (`WorkerService.recordResult`):** 보고 status → stage 매핑 후 누적 upsert.

| 보고 status | stage |
|---|---|
| COMPLETED, FAILED | ANALYSIS |
| PR_CREATED(구현 경로), IMPLEMENTATION_FAILED | IMPLEMENTATION |
| DESIGN_REVIEW, DESIGN_FAILED | DESIGN |

- usage가 전부 null/0인 보고(예: undeploy 복귀형 PR_CREATED, 구버전 워커)는 no-op.
- 지각 보고 정합화 분기(stale 회수 후 늦은 성공 보고)에서도 usage가 실려 있으면 동일하게 누적.

### 4.2 인터뷰 서비스

- `messageRelay`: result 메시지의 `usage`에서 토큰 4종 추출 (현재 `total_cost_usd`만 읽는 지점 확장).
- `WorkerQuestionRequest`·`WorkerPlanRequest`에 토큰 4필드 추가.
- `InterviewService.addCost` → `addUsage`로 확장: 비용 + 토큰 4종을 세션에 함께 누적.
- **작업 이관:** 세션이 task에 연결·등록되는 시점에 세션 누적치를 `task_stage_usage(task_id, 'INTERVIEW')`로 누적 upsert.
  같은 작업에 인터뷰 세션이 여러 번 붙으면(재인터뷰) 각 세션 완료분이 누적된다.

## 5. API / DTO

- `TaskResponse`(상세)에 추가:
  - `stageUsage: [{stage, costUsd, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens}]`
  - 합산 편의 필드 `totalCostUsd`, `totalTokens` (input+output 합; 캐시 제외)
- 목록 조회: `task_id IN (…)` 일괄 `SUM(cost_usd) GROUP BY task_id` 조회로 N+1 회피.
  목록 응답에는 `totalCostUsd`만 노출.
- 워커/인터뷰 보고 API는 §4의 요청 DTO 확장 외 신규 엔드포인트 없음.

## 6. 프론트 표시

- **`tasks/[id].vue`** (작업 상세): 단계별 카드(대화형 분석·디자인·구현·분석)에 usage chip.
  형식 예: `⚡ 12.3k 입력 · 4.5k 출력 · $0.42` — 캐시 토큰 2종은 chip 툴팁으로.
  헤더 영역에 총합 chip (`totalTokens` + `totalCostUsd`).
  해당 stage 행이 없으면 chip 미표시 (과거 작업/미수집).
- **`tasks/index.vue`** (작업 목록): 진행 중 상태(`…중`: 분석중/구현중/디자인중/배포중/배포중지중/인터뷰중)가 아닌
  작업 카드에 총비용 배지 (`$1.23`) — 성공·실패 종결 상태 모두 포함.
  `totalCostUsd`가 0 또는 없음이면 배지 숨김. 진행 중 작업의 중간 누적은 상세에서만 확인.
- 토큰 수는 k/M 단위 축약, 비용은 소수 2~4자리 표시.

## 7. 에러 처리 요약

| 상황 | 동작 |
|---|---|
| envelope 파싱 실패 | raw stdout fallback, usage 미수집, 경고 로그 — 파이프라인 계속 |
| stderr 출력 | 별도 캡처, 실패 진단 tail에만 사용 |
| usage 없는 보고 (구버전 워커·undeploy 복귀) | usage 기록 no-op, 나머지 처리 기존과 동일 |
| 과거 작업 (행 없음) | UI 표시 생략 |
| 동시 보고 | PK(task_id, stage) upsert 원자성으로 누적 정합 보장 |

## 8. 테스트 계획

- **워커 단위:** envelope 파서 — 정상 JSON / stderr 분리 확인 / plain-text fallback / usage 필드 결손.
- **백엔드 통합:** `recordResult` 누적 upsert (2회 보고 → 합산 확인), status→stage 매핑, usage 없는 보고 no-op,
  실패 보고 누적 포함.
- **인터뷰 서비스:** messageRelay 토큰 추출, 세션 누적, 등록 시 INTERVIEW 행 이관.
- **프론트 spec:** 단계 chip 렌더 / 미수집 시 미표시 분기 / 목록 배지 0-숨김 / 총합 표시.

## 9. 제외 범위 (명시)

- 배포/배포중지 단계 usage (Claude 미사용)
- admin 통계 대시보드 (기간별/사용자별 집계)
- 실행 횟수(run count) 저장·표시
- 과거 데이터 백필
- stream-json 실시간 중간 비용 표시
