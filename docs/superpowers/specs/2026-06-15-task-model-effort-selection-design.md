# 작업 등록 시 모델 + effort 선택 (설계)

- 날짜: 2026-06-15
- 상태: 승인됨 (구현 대기)
- 브랜치: `feat/task-model-effort` (base `origin/main`, e93a69c)

## 1. 문제 / 목표

작업 등록 화면에서 사용자가 **Claude 모델**과 **effort(추론 깊이)**를 선택할 수 있게 한다. 선택값은 그 작업의 AI 실행 전체 — 대화형 분석 인터뷰(Agent SDK)와 이후 분석/구현 워커(claude CLI) — 에 반영된다.

현재 netisMaker는 Anthropic API를 직접 호출하지 않고 **claude CLI(`claude -p`) + Claude Agent SDK(`@anthropic-ai/claude-agent-sdk@0.2.117`)** 를 구독 인증으로 사용한다. 백엔드는 **model/effort를 어디서도 지정하지 않아** CLI/SDK 기본값으로 동작 중이다(검증: `src/main`에 `--model`/`model`/`effort` 참조 0건).

## 2. 검증된 사실 (직접 확인)

- **claude CLI 2.1.177**: `--model <id>`, `--effort <low|medium|high|xhigh|max>`, `--fallback-model` 네이티브 지원.
- **SDK 0.2.117**: `Options`에 `model?: string`, `effort?: EffortLevel`(`'low'|'medium'|'high'|'xhigh'|'max'`), `fallbackModel?`, `thinking?` 존재. SDK 업그레이드 불필요.
- **effort × model 호환성**(claude-api 레퍼런스 기준, 본 기능의 모델 4종):
  - `claude-opus-4-8` → low, medium, high, xhigh, max
  - `claude-opus-4-7` → low, medium, high, xhigh, max
  - `claude-sonnet-4-6` → low, medium, high, xhigh, max
  - `claude-haiku-4-5` → **low, medium, high 만** (xhigh/max는 런타임 에러)

## 3. 확정된 결정

- **적용 범위**: 양쪽 — 인터뷰(SDK) + 분석/구현(CLI 워커).
- **노출 모델**: `claude-opus-4-8`(기본), `claude-opus-4-7`, `claude-sonnet-4-6`, `claude-haiku-4-5`.
- **effort**: 노출 + **모델별 허용 맵 제약**(Haiku는 low/medium/high만).
- **기본값**: model=`claude-opus-4-8`, effort=`high`.
- **검증**: allowlist + effort×model 호환성을 **백엔드가 권위**로 검증(400 거부), 프론트는 UX용 동적 필터로 미러.
- **읽기 표시**: 선택된 model/effort를 작업 상세·인터뷰 패널에 칩으로 표시(포함).

## 4. 단일 진실원천 (백엔드)

모델별 허용 effort + 기본값을 한 곳에 정의(예: `ModelEffortPolicy` 유틸/enum)하고 검증·기본값 보정에 사용. 프론트는 같은 표를 UX용으로 하드코딩 미러(작은 고정 집합, 컨벤션상 인라인).

```
ALLOWED = {
  "claude-opus-4-8":   [low, medium, high, xhigh, max],
  "claude-opus-4-7":   [low, medium, high, xhigh, max],
  "claude-sonnet-4-6": [low, medium, high, xhigh, max],
  "claude-haiku-4-5":  [low, medium, high],
}
DEFAULT_MODEL  = "claude-opus-4-8"
DEFAULT_EFFORT = "high"
```

검증 규칙:
1. `model`이 blank → `DEFAULT_MODEL`. `effort`가 blank → `DEFAULT_EFFORT`.
2. `model` ∉ ALLOWED keys → 400.
3. `effort` ∉ ALLOWED[model] → 400.

## 5. 데이터 흐름

```
[등록 폼] model/effort 선택 (effort 옵션은 model에 따라 동적)
   POST /api/interviews | /api/tasks  (body에 model, effort)
      → Service.create(): 검증·기본값 보정 → 엔티티 컬럼 영속
         → claim payload(InterviewClaimResponse | WorkerTaskResponse)에 실어 워커로
            → 인터뷰 SDK: buildOptions에 model/effort
            → 워커 CLI: claude --model <id> --effort <level>
      인터뷰 register() → 생성되는 Task에 session.model/effort 전달
[작업 상세 / 인터뷰 패널] 선택값 칩 표시 (read DTO에 포함)
```

## 6. 변경 단위 (레이어별)

### 6.1 데이터
- `Task`(`entity/Task.java`)·`InterviewSession`(`entity/InterviewSession.java`)에 `model`(`@Column VARCHAR(64)`), `effort`(`@Column VARCHAR(16)`) 추가. `create(...)` 팩토리 시그니처에 `String model, String effort` 추가.
- **Flyway `V12__task_model_effort.sql`**: `com.task`·`com.interview_session` **양쪽** `ADD COLUMN IF NOT EXISTS model VARCHAR(64) NOT NULL DEFAULT 'claude-opus-4-8'`, `effort VARCHAR(16) NOT NULL DEFAULT 'high'`.
- `InterviewService.register()`의 `Task.create(...)` 호출에 `s.getModel()`, `s.getEffort()` 추가.

### 6.2 API + 검증
- `dto/TaskCreateRequest.java`·`dto/CreateInterviewRequest.java`에 `String model`, `String effort` 추가.
- `service/ModelEffortPolicy.java`(신규): ALLOWED 맵 + `resolve(model, effort)`(blank 보정) + `validate(model, effort)`(allowlist + 호환성, 위반 시 `new TaskException(HttpStatus.BAD_REQUEST, "...")` — `resolveMcpExtras` 선례와 동일 형식).
- `TaskService.create`·`InterviewService.create`에서 `ModelEffortPolicy.resolve/validate` 호출 후 팩토리에 전달.
- 컨트롤러 무변경(@Valid 자동 바인딩).

### 6.3 claim 전달
- `dto/WorkerTaskResponse.java`: `model`, `effort` 필드 + `forAnalysis`/`forImplementation` 팩토리에서 Task 매핑(deploy/undeploy는 claude 미실행이라 생략 가능).
- `dto/InterviewClaimResponse.java`: `model`, `effort` 필드 + `of(InterviewSession, ...)` 매핑.

### 6.4 워커 CLI
- `workerdaemon/ClaudeExecAdapter.java`: `exec(...)`에 `model`, `effort` 파라미터. `cmd.add("-p")` 뒤, `mcpArgs`(variadic `--allowedTools`) **앞**에 `if model 비-blank → cmd.add("--model"); cmd.add(model)`, `if effort 비-blank → cmd.add("--effort"); cmd.add(effort)` 삽입.
- `workerdaemon/WorkerMainLoop.java`: 분석·구현 2곳의 `claude.exec(...)` 호출에 `task.model()/task.effort()` 전달.

### 6.5 인터뷰 SDK
- `netismaker-interview-service/src/types.ts`: claim 인터페이스에 `model?: string`, `effort?: 'low'|'medium'|'high'|'xhigh'|'max'`.
- `src/sdk/sessionOptions.ts`: `SessionOptionsInput`에 `model?`, `effort?`. `buildOptions()` return에 conditional-spread(`...(input.model ? {model: input.model} : {})`, effort 동일) — 값 없으면 키 생략(기본값 보존).
- `src/runner/interviewRunner.ts`: `buildOptions()` 호출 2곳에 `claim.model`/`claim.effort` 전달.

### 6.6 프론트엔드
- `frontend/pages/tasks/index.vue`:
  - `<script setup>`에 `MODEL_OPTIONS`(label/value 4종), `EFFORT_OPTIONS`, 그리고 `MODEL_EFFORT_MAP`(모델→허용 effort) 하드코딩.
  - `draft`에 `model: 'claude-opus-4-8'`, `effort: 'high'`. `openCreate()`에서 기본값 reset.
  - description q-input과 MCP q-expansion-item 사이에 model·effort q-select 2개(`emit-value map-options dense outlined`, status filter 패턴). **model 변경 시 effort 옵션을 `MODEL_EFFORT_MAP[model]`로 필터링하고, 현재 effort가 비허용이면 `high`로 강등**(computed `availableEfforts` + watch).
  - `startInterview()`·`submit()` 두 POST body에 `model: draft.model, effort: draft.effort`.

### 6.7 읽기 표시 (포함)
- `dto/TaskResponse.java`·`dto/InterviewResponse.java`(+ 사용 시 `InterviewSummary`)의 `of(...)`에 `model`, `effort` 추가.
- 작업 상세(`frontend/pages/tasks/[id].vue`)·인터뷰 패널(`components/InterviewPanel.vue`)에 model/effort 칩 표시.

## 7. 테스트 전략

- **백엔드(JUnit)**:
  - `ModelEffortPolicyTest`(신규, 순수): resolve(blank→기본), validate(allowlist 통과/거부, Haiku+max 거부, Opus/Sonnet+max 통과).
  - `TaskServiceTest`·`InterviewServiceTest`(Mockito, 로컬): create가 정책 적용 후 엔티티에 model/effort 저장; register가 session→task로 전달.
  - `WorkerTaskResponse`/`InterviewClaimResponse` 매핑 단언.
  - `ClaudeExecAdapter`: exec 명령 조립에 `--model`/`--effort`가 올바른 위치(-p 뒤, --allowedTools 앞)에 들어가고 blank면 생략 — 명령 리스트 검사(프로세스 미실행, 가능 범위에서).
  - 통합/리포지토리 테스트는 `RUN_TESTCONTAINERS` 게이트(CI 전용).
- **인터뷰 SDK(Vitest)**: `sessionOptions` buildOptions가 model/effort 전달 시 옵션에 포함, 미전달 시 키 생략.
- **프론트(Vitest)**: 모델→effort 동적 필터(순수 함수로 분리해 테스트), Haiku 선택 시 xhigh/max 제거 + 비허용 effort 강등.

## 8. 엣지 / 위험

- **풀체인 전제**: 프론트만 바꾸면 DTO에 필드 없을 때 Jackson이 조용히 드롭. 5+레이어 모두 완성돼야 실제 claude 호출에 반영.
- **register() 누락 위험**: 인터뷰의 model/effort가 구현 Task로 자동 안 따라감 — 명시 전달 필수, 테스트로 잠금.
- **Flyway interview_session 누락 위험**: 두 테이블 모두 ALTER.
- **레거시/진행중 세션**: Flyway DEFAULT + 워커/SDK의 null/blank 가드(키 생략→기본값)로 방어.
- **잘못된 조합**(Haiku+max): 서버 검증 + 프론트 동적필터 이중 방어.
- **쿼터**: 구독 1개 공유 — 상위 effort/모델은 시간당 메시지 cap 소진을 앞당김(기존 동시성 직렬화로 자연 완화). 운영 모니터링 권고.

## 9. 서브에이전트 모델 동작 (명확화 — 검증됨)

"선택한 모델을 서브에이전트/팀에이전트도 따르는가"에 대한 확정:

- **인터뷰(SDK)**: `sessionOptions.ts`의 `allowedTools=['Skill','Read','Grep','Glob']`에 Task/Agent 도구가 없어 **인터뷰 에이전트는 서브에이전트를 생성할 수 없다**. 단일 인터뷰 에이전트가 선택 모델로 동작 — 서브에이전트 모델 질문 무관.
- **워커(`claude -p`)**: 서브에이전트 model 결정 순서 = `CLAUDE_CODE_SUBAGENT_MODEL` env → 호출 model 파라미터 → agent 정의 `model` frontmatter → **세션 메인 모델(`--model`)**. 따라서 `--model`만 전달해도 자기 model 미명시 서브에이전트는 **선택 모델을 상속**한다. `effort`도 동일하게 기본 상속(서브에이전트 정의에서 override 시 우선).
- **예외**: 빌트인 **Explore** 서브에이전트는 Haiku 고정(세션 모델 무관). 대상 레포의 `.claude/agents/*.md`에 model이 박힌 에이전트는 그 값을 따름.
- **결정**: `CLAUDE_CODE_SUBAGENT_MODEL` env 강제는 **하지 않는다**(기본 상속만). Explore 등 경량 검색은 저렴한 Haiku 유지(쿼터 절약). 따라서 `ClaudeExecAdapter`는 `--model`/`--effort` 플래그만 추가하고 `ProcessBuilder.environment()`는 건드리지 않는다.
- 출처: SDK `sdk.d.ts` `AgentDefinition.model`("If omitted or 'inherit', uses the main model")·`AgentDefinition.effort`, `code.claude.com/docs/en/sub-agents.md`(model 해석 순서·Explore→Haiku).

## 10. 범위 제외 (YAGNI)

- `application-worker.yml` 전역 기본 override(폼 기본값 + Flyway DEFAULT로 충분).
- `--fallback-model` / SDK `fallbackModel` 노출(후속 가능).
- deploy/undeploy 경로의 model/effort(해당 경로는 claude `-p` 미실행).
