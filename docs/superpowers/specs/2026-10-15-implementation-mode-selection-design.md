# 작업(Task) — 구현 방식 선택(직접·리뷰·SDD)·구현 경과 시간·부분 작업 보존·구현 재시도 설계

- 날짜: 2026-10-15
- 상태: 사용자 승인됨 (brainstorming: 현황 확인(라이브 구현은 SDD가 아님) → 질의 3건(선택지 "직접·리뷰·SDD 셋 다", 기본값 "리뷰", 리뷰 수정 범위 "중대 문제만") → 방안 A 채택 → 설계 1~4부 승인 → 보완 승인(경과 시간 표시, 부분 작업 보존, 구현 재시도))
- 범위: netisMaker 백엔드(Java, API + 워커 데몬) + 프론트엔드(Nuxt) + 기동 스크립트. 인터뷰 서비스는 바꾸지 않는다.
- 근거 실측: 2026-10-15 헤드리스 SDD·중간 방식 실측(같은 날 다른 세션, llm-wiki `netismaker-implementation-modes`) — §2.2에 요약

## 1. 목표

인터뷰 플랜을 확정("구현 진행")할 때 관리자가 작업마다 **구현 방식**을 고르고, 워커가 그 방식으로 구현한다.

| 방식 | 화면 이름 | 하는 일 |
|---|---|---|
| `DIRECT` | 직접 | 지금과 같다 — 한 세션이 계획대로 직접 구현 |
| `REVIEW` | 리뷰 | 직접 구현한 뒤 리뷰 서브에이전트 1회, 중대 문제만 고침 |
| `SDD` | SDD | superpowers `subagent-driven-development`로 계획 작업마다 서브에이전트가 구현·리뷰 |

함께 넣는 것:
- 구현중인 작업에 **경과 시간 / 상한** 표시(목록·상세).
- claude가 중간에 끝나면(시간 초과 등) **지금까지 작업을 브랜치에 보존**.
- 관리자가 구현실패 작업을 방식을 다시 골라 **처음부터 재시도**.

## 2. 배경

### 2.1 현재 동작 (main `7ca25a5`)

- 구현 프롬프트의 플랜 절(`WorkerMainLoop.renderPlanSection`)이 "계획이 가리키는 서브에이전트/실행 스킬(subagent-driven-development 등) 없이 이 세션에서 직접 구현하세요"라고 지시한다. 인터뷰 플랜 머리말의 "REQUIRED SUB-SKILL: subagent-driven-development"를 덮어쓰는 것.
- 워커는 `--setting-sources project`(`ClaudeExecAdapter.buildCommand`)라 superpowers 플러그인이 로드되지 않는다.
- 라이브 구현 세션 기록(작업 9·10·13)에 Skill·Agent 호출이 한 번도 없다.

### 2.2 2026-10-15 실측 요약 (조건마다 1회)

- **완전 SDD(헤드리스)**: `--plugin-dir <superpowers>` + `GIT_AUTHOR_*`/`GIT_COMMITTER_*` env + `.netis/plan/`의 `### Task N` 계획 파일 + 프롬프트 금지 사항(새 worktree·push·finishing 단계·사람에게 질문)으로 연습용 계획(태스크 2개)이 끝까지 돌았다 — 7분 35초, $1.89, 서브에이전트 7개(구현 haiku, 태스크 리뷰 sonnet, 최종 리뷰 opus), 작성자 netisMaker 커밋 3개. 실제 작업 계획으로는 돌리지 않았다.
- **지금 워커에 SDD를 붙일 때 막히는 곳**: ① 태스크별 커밋 뒤 작업 트리가 비어 `commitAndPush`가 "nothing to commit"으로 구현실패, ② 이 PC에 git 전역 작성자가 없어 에이전트 커밋이 `Author identity unknown`, ③ SDD 스크립트(`task-brief`)는 `^#+ Task N` 제목만 찾는데 인터뷰 계획은 `### 작업 N:`, ④ 플러그인 미로드, ⑤ `.design/`·`.netis-deploy-env.json`이 태스크 커밋에 섞임(`info/exclude`로 해결 확인), ⑥ 워커 상한 45분·API 판정 60분.
- **중간 방식(= 이 스펙의 리뷰)**: 지금 구현 + 마지막에 Opus 리뷰 서브에이전트 1회. 작업 10은 구현 비용 +57%·고친 것 3줄, 작업 13은 구현 +77%(작업 전체 +26%)에 원래 MR이 놓친 Important 3건을 잡음. 작업 13이 34분 걸려 45분 상한까지 11분 남았다.
- **사용량**: 결과 JSON의 `total_cost_usd`는 서브에이전트를 포함하지만 `usage` 토큰은 메인 세션 것뿐이다. `modelUsage`(모델별)에는 서브에이전트가 포함된다.

## 3. 확정된 정책 결정

| 결정 | 내용 |
|---|---|
| 선택지 | 직접·리뷰·SDD 셋 다 |
| 고르는 곳 | 인터뷰 패널의 "구현 진행"(관리자), "디자인 단계 포함" 토글 옆. 구현 재시도 때 다시 고를 수 있다 |
| 기본값 | 화면은 **리뷰**를 미리 고른다. API는 값이 없으면 **직접**(옛 화면·다른 호출자는 지금과 같음) |
| 리뷰 수정 범위 | Critical·Important 중 **계획 범위 안의 결함**(깨짐·조용한 오작동·계획 미이행)만 고친다. 계획에 없는 동작 규칙 변경은 하지 않는다. Minor·고치지 않은 지적·"요청자 확인 필요"는 PR/MR 본문 "리뷰 메모"로 |
| 실행 구조 | 방식마다 지시만 달리한 **`claude -p` 한 세션**(방안 A, §3.1) |
| 리뷰어 모델 | 리뷰 방식의 리뷰어는 **opus 고정**(작업 모델과 무관). SDD의 서브에이전트 모델은 SDD가 역할별로 고른다 |
| 시간 상한 | 방식별 구현 상한·멈춤 판정을 **API가 정해** 워커에 넘긴다(§5.4) |
| 준비 실패 | SDD·리뷰에 필요한 준비가 안 되면 **한 단계 낮은 방식으로 대신 진행**(SDD → 리뷰 → 직접)하고 이유를 남긴다 |
| 중단 시 | claude가 중간에 끝나면 남은 변경을 WIP 커밋으로 묶어 작업 브랜치를 push(PR/MR은 만들지 않음) |
| 재시도 | 관리자가 구현실패에서 방식을 다시 골라 **베이스부터 새 브랜치로** 다시 구현(이어하기 아님). 시도 번호로 브랜치 이름과 늦은 보고를 구분 |

### 3.1 방안 비교 (채택: 방안 A)

| 방안 | 요지 | 탈락 사유 |
|---|---|---|
| **A. 한 세션, 방식별 지시** | 직접=지금 그대로, 리뷰=프롬프트에 리뷰 절, SDD=`--plugin-dir` + SDD 지시 | — (채택) 실측과 같은 구조, 변경이 가장 적음 |
| B. 워커가 단계를 나눠 실행 | 구현 세션 → 리뷰 세션 → 수정 세션 | 세션 2~3개로 비용·코드 증가, SDD는 결국 A처럼 해야 함 |
| C. 워커 자체 태스크 루프 | 계획 작업마다 `claude -p` | 구현량 최대, superpowers SDD 개선을 못 따라감 |

## 4. 데이터

마이그레이션 `V29__task_implementation_mode.sql`:

```sql
ALTER TABLE com.task
    ADD COLUMN implementation_mode            VARCHAR(10) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN implementation_attempt         INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN implementation_timeout_minutes INTEGER,
    ADD CONSTRAINT ck_task_implementation_mode    CHECK (implementation_mode IN ('DIRECT', 'REVIEW', 'SDD')),
    ADD CONSTRAINT ck_task_implementation_attempt CHECK (implementation_attempt >= 1);
```

- 기존 작업은 전부 `DIRECT`·1회째 — 지금 동작 그대로.
- enum `ImplementationMode { DIRECT("직접"), REVIEW("리뷰"), SDD("SDD") }`, `Task.implementationMode`(`@Enumerated(STRING)`)·`Task.implementationAttempt`·`Task.implementationTimeoutMinutes`.
- `implementation_timeout_minutes`는 워커가 구현을 가져갈 때(claim) 그 방식의 상한을 기록한 값이다 — 작업 응답이 설정을 몰라도 상한을 보여 줄 수 있고, 실행 중에 설정을 바꿔도 화면 숫자가 이번 시도에 실제로 걸린 상한과 같다.

## 5. API·서비스

### 5.1 플랜 확정

- `POST /api/interviews/{id}/confirm` 바디 `InterviewConfirmRequest(Boolean designRequested, String implementationMode)`.
  - `implementationMode`가 null·공백이면 `DIRECT`. enum 이름(대소문자 구분)이 아니면 400 "알 수 없는 구현 방식: …".
- `InterviewService.confirm(sessionId, adminId, designRequested, mode)`가 `task.implementationMode`에 저장한다(시도 번호는 1 그대로).
- 상태 이력 사유: `플랜 확정 → 구현대기 (interview_session 25, 구현 방식: 리뷰)`.
- 디자인 단계를 거치는 작업도 확정 때 고른 방식으로 구현한다. 레거시 자동분석 경로(분석완료 → 승인)는 `DIRECT`.
- 확정 뒤 방식을 바꾸는 API는 없다(재시도 때만 다시 고른다).

### 5.2 작업 응답

`TaskResponse`(목록·상세 공용)에 추가:

| 필드 | 값 |
|---|---|
| `implementationMode` | `DIRECT`·`REVIEW`·`SDD` |
| `implementationAttempt` | 1부터 |
| `claimedAt` | 워커가 마지막으로 가져간 시각 — 결과 보고 뒤에도 남으므로 화면은 구현중일 때만 쓴다 |
| `implementationTimeoutMinutes` | 이번 시도의 구현 상한(§5.4, claim 때 기록 — 그 전에는 null) |

### 5.3 워커에 넘기는 작업 (claim)

`WorkerTaskResponse`에 구현 지시 블록 `implementation` = `{mode, attempt, timeoutMinutes}`(레코드 `ImplementationSpec`)을 추가한다(IMPLEMENTATION에만 값, 다른 kind는 null). claim이 `구현대기 → 구현중`으로 옮길 때 방식별 상한을 `task.implementation_timeout_minutes`에 기록하고 같은 값을 싣는다. 옛 워커는 모르는 필드를 무시한다(`WorkerHttpClient`가 `FAIL_ON_UNKNOWN_PROPERTIES=false`).

### 5.4 시간 상한·멈춤 판정

`application.yml`의 `app.task`:

| 방식 | 구현 상한(워커가 claude를 끊는 시간) | 멈춤 판정(API stale 회수) |
|---|---|---|
| 직접 | `implementation-timeout-minutes: 45` | `implementation-stale-threshold-minutes: 60`(기존) |
| 리뷰 | `implementation-review-timeout-minutes: 80` | `implementation-review-stale-threshold-minutes: 95` |
| SDD | `implementation-sdd-timeout-minutes: 240` | `implementation-sdd-stale-threshold-minutes: 260` |

- 값은 환경변수로 바꿀 수 있다(Spring relaxed binding, 예: `APP_TASK_IMPLEMENTATION_SDD_TIMEOUT_MINUTES`). 지금 `public.env`·기동 스크립트에 관련 덮어쓰기는 없다.
- 기동 시 검사: 방식마다 `0 < 상한 < 판정`이 아니면 API가 뜨지 않는다.
- `StaleTaskRecoveryJob`의 `IMPLEMENTING` 판정 시간을 작업 방식에 따라 고른다. heartbeat 기반 워커 사망 판정은 그대로.
- 리뷰는 작업 13(태스크 13개) 34분 실측 기준, SDD는 실제 계획 실측이 없어 넉넉히 잡았다 — 첫 실행 뒤 조정한다.
- 워커의 `IMPLEMENTATION_TIMEOUT`(45분)은 claim에 상한이 없을 때(옛 API)만 쓰인다.

### 5.5 구현 재시도

- `POST /api/tasks/{id}/implementation/retry`(관리자) 바디 `{ "implementationMode": "REVIEW" }` — null·공백이면 지금 방식 유지, 잘못된 값 400.
- `구현실패`에서만(아니면 409, 소프트 삭제된 작업은 404). 작업 행 비관적 락.
- 처리: `implementation_attempt += 1`, 방식 반영, 상태 → `구현대기`, `failure_reason`·`head_branch`·`head_sha`·`pr_url`·`pr_number`·`implementation_log`·`worker_id`·`claimed_at`을 비운다. `env_template`·디자인·분석·사용량은 그대로(사용량은 시도마다 구현 단계에 쌓임).
- 이력: `구현 재시도 (2회째, 구현 방식: SDD → 리뷰, 이전 브랜치: netismaker/task-13-…)` — 방식이 같으면 `구현 방식: 리뷰`, 이전 브랜치가 없으면 생략. 이전 실패 사유는 직전 이력 행에 이미 있다.
- 횟수 제한 없음(관리자 판단).

### 5.6 결과 보고 — 시도 번호

- `WorkerResultRequest`에 `implementationAttempt`(Integer)를 추가한다. 워커는 구현 결과(PR생성·구현실패)에만 싣는다.
- `WorkerService.recordResult`는 맨 앞에서: 값이 있고 작업의 현재 시도 번호와 다르면 **상태를 바꾸지 않고** 이력에 `이전 시도(1회째)의 늦은 보고 무시: PR생성`을 남긴 뒤, 그 보고의 사용량만 구현 단계에 더하고 정상 응답한다(워커 dead-letter에 쌓이지 않게). 기존 지각 보고 정합화(구현실패 → PR생성)는 시도 번호가 같을 때만.
- 값이 없으면(옛 워커) 지금과 같다.

## 6. 워커

### 6.1 공통 (모든 방식)

- **시도 번호 브랜치**: 2회째부터 `{prefix}task-{id}-{slug}-r{n}`(slug가 비면 `{prefix}task-{id}-r{n}`). 브랜치 이름이 작업마다 고정이고 push가 강제 덮어쓰기가 아니라서, 이전 시도가 올린 브랜치와 겹치면 push가 거부되기 때문이다. 로컬 브랜치 정리(`deleteTaskBranches`)는 이미 `task-{id}-` 접두로 매칭하므로 그대로 덮인다.
- **임시 파일 커밋 제외**: worktree를 만든 뒤 `git rev-parse --git-path info/exclude`가 가리키는 파일에 `.netis/`, `.design/`, `.netis-deploy-env.json`을 없는 줄만 추가한다(레포 캐시 공용 — 워커 전용 이름이라 다른 작업에도 무해). 실패하면 경고만 남기고 SDD는 리뷰로 대신 진행(§6.5).
- **git 작성자**: 구현 claude 프로세스 env에 `GIT_AUTHOR_NAME`·`GIT_AUTHOR_EMAIL`·`GIT_COMMITTER_NAME`·`GIT_COMMITTER_EMAIL` = 워커 설정 `git-user-name`·`git-user-email`.
- **상한**: claim의 `implementationTimeoutMinutes`(양수일 때), 없으면 `IMPLEMENTATION_TIMEOUT`.
- **커밋 지시 placeholder**: `application-worker.yml` 구현 템플릿의 `- 의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)` 줄과 `defaultImplementationPrompt()`의 `- 각 subtask 완료마다 의미 있는 변경을 누적하세요` 줄을 `- {commit_rule}`로 바꾼다. 치환값 — 직접·리뷰: "의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)"(라이브 yml 문구 그대로라 직접 방식의 실제 프롬프트는 바뀌지 않는다), SDD: "태스크별 커밋은 SDD 흐름대로 하세요 — push는 하지 마세요. 끝난 뒤 남은 변경과 push는 워커가 처리합니다".
- **에이전트 커밋 받아들이기**: `GitOpsService.commitAndPush(dir, ref, branch, message, baseSha)` —
  1. 남은 변경이 있으면 지금처럼 커밋(`-c user.name/email`).
  2. `git rev-list --count {baseSha}..HEAD`가 0이면 지금처럼 `nothing to commit — 구현 시도 후 파일 변경이 없음`.
  3. 아니면 push 후 HEAD 반환.
- **claude가 성공한 뒤 순서**: 리뷰 메모(`.netis/review-notes.md`) 회수 → `.netis/` 삭제 → 기존 `.design/` 삭제·`.netis-deploy-env.json` 회수 → 커밋·push → PR/MR.

### 6.2 직접

`{commit_rule}` 치환 말고는 지금 프롬프트 그대로(`WorkerMainLoopPlanSectionTest` 유지).

### 6.3 리뷰

- 계획 본문이 있으면 `.netis/plan/task-{id}-plan.md`로 쓴다(리뷰어가 읽을 파일).
- 플랜 절의 마지막 줄을 "구현은 계획이 가리키는 서브에이전트/실행 스킬(subagent-driven-development 등) 없이 이 세션에서 직접 하세요. 리뷰만 아래 '마지막 리뷰'대로 서브에이전트 하나에 맡깁니다."로 바꾸고, 계획 본문 뒤에 "## 마지막 리뷰" 절을 붙인다(실측 문구 + 정한 수정 범위):
  1. 구현과 테스트를 마친 뒤 서브에이전트 하나(Agent 도구, model opus)에 전체 변경 리뷰를 맡긴다. 리뷰는 한 번뿐 — 리뷰어를 더 띄우거나 재리뷰를 받지 않는다.
  2. 리뷰어 프롬프트는 아래 "리뷰어 지시" 그대로, `[DESCRIPTION]` 자리에 구현 요약.
  3. Critical·Important 중 계획 범위 안의 결함(깨짐·조용한 오작동·계획 미이행)만 이 세션에서 고치고 테스트를 다시 돌린다. 계획에 없는 동작 규칙 변경(입력 검증 강화·정규화 추가 등)은 하지 않는다.
  4. `.netis/review-notes.md`에 리뷰 결론 한 줄, 고친 것, 고치지 않은 지적(Minor 포함)과 이유, "요청자 확인 필요" 항목을 쓴다.
  5. 커밋하지 않는다. 리뷰어도 작업 트리 변경을 본다.
- **리뷰어 지시**: 리소스 `prompts/implementation-reviewer.md` = superpowers 6.4.1 `skills/requesting-code-review/code-reviewer.md`(MIT, © 2025 Jesse Vincent — 저작권 고지를 리소스 머리 주석으로 유지하고 프롬프트에 넣을 때는 뺀다)에서 실측 때와 같은 세 곳만 고친 사본: 계획 위치(`__PLAN_PATH__`를 전부 읽으라), 리뷰 대상(커밋되지 않은 변경 — `git status --porcelain` + `git diff __BASE_SHA__`, 추적 안 된 파일은 전부 읽기), 읽기 전용(git add·commit·stash·checkout 금지). 워커가 placeholder를 채운다. 계획 본문이 없으면(인터뷰 없이 온 작업을 재시도로 리뷰) 계획 위치 대신 작업 요구사항을 기준으로 삼으라고 쓴다.

### 6.4 SDD

- **준비 조건**(하나라도 안 되면 리뷰로 대신, §6.5): 플러그인 경로가 설정되고 `skills/subagent-driven-development/SKILL.md`가 있음, `info/exclude` 기록 성공, 계획 본문 있음, 계획 파일 쓰기 성공, 변환 뒤 `Task N` 제목이 1개 이상.
- **계획 파일**: `.netis/plan/task-{id}-plan.md` — 코드 블록(```) 밖의 `^(#+[ \t]+)작업([ \t]+[0-9]+)`를 `$1Task$2`로 바꿔 쓴다. 인터뷰 설계 문서(`analysisMarkdown`)가 있으면 `.netis/plan/task-{id}-design.md`로 함께 쓴다.
- **명령**: `--plugin-dir <SUPERPOWERS_PLUGIN_PATH>`를 MCP 인자(`--allowedTools`는 variadic이라 맨 뒤) 앞에 붙인다. `--setting-sources project`는 그대로라 운영자의 다른 플러그인·훅·설정은 여전히 안 붙는다(실측: 스킬 15개·MCP 0개).
- **플랜 절**(계획 본문은 넣지 않고 경로만 — 실측 문구):
  - 요청자와의 인터뷰로 확정된 계획이 `.netis/plan/task-{id}-plan.md`(git 추적 제외)에, 설계 문서(스펙)가 `…-design.md`에 있다.
  - superpowers:subagent-driven-development 스킬로 이 계획 파일을 처음부터 끝까지 실행한다. brainstorming·writing-plans는 다시 하지 않는다.
  - 현재 디렉터리가 이미 이 작업 전용 worktree이고 체크아웃된 브랜치가 작업 브랜치다 — 새 worktree·브랜치를 만들지 않는다.
  - 태스크별 커밋은 해도 되지만 push, PR/MR 생성, 브랜치 병합·삭제·리베이스는 하지 않는다.
  - superpowers:finishing-a-development-branch는 실행하지 않는다. 최종 리뷰까지 끝나면 결과를 요약해 출력하고 종료한다.
  - 비대화식이라 사람에게 묻지 않는다. 판단이 필요하면 SDD 규칙대로 원장에 Ruling으로 남기고 계속한다.
  - 리뷰 지적 수정 범위는 §6.3의 3번과 같다.
  - 끝내기 전에 원장의 Ruling·보류(parked) 항목과 고치지 않은 지적을 `.netis/review-notes.md`에 정리한다.
- **서브에이전트 모델**: SDD가 역할별로 고른다(`CLAUDE_CODE_SUBAGENT_MODEL` 미설정 유지). 작업에서 고른 모델·effort는 SDD를 지휘하는 메인 세션에만 적용된다.
- SDD 원장(`.superpowers/sdd/`)은 SDD가 스스로 만든 `.gitignore`(`*`)로 커밋에서 빠진다.

### 6.5 낮은 방식으로 대신 진행

| 요청 | 대신 | 조건 |
|---|---|---|
| SDD | 리뷰 | §6.4 준비 조건 중 하나라도 실패 |
| 리뷰 | 직접 | 계획 본문이 있는데 계획 파일 쓰기 실패 |

- 실제 쓴 방식과 이유를 PR/MR 본문(§6.7)과 워커 로그(WARN)에 남기고, 실패하면 실패 사유 끝에도 붙인다. DB의 `implementation_mode`는 요청한 방식 그대로 둔다.
- 워커 기동 로그: `SDD 플러그인: <경로>` 또는 `SDD 플러그인 없음 — SDD 요청은 리뷰로 대신 진행`.

### 6.6 중단 시 부분 작업 보존

- **대상**: worktree를 만들고 claude를 띄운 뒤 claude가 성공하지 못하고 끝난 경우 — 시간 초과, exit≠0, 실행 중 예외. fetch·worktree 준비 실패와 claude 성공 뒤의 커밋·PR 실패(지금 처리 그대로)는 제외.
- **순서**:
  1. `.netis/`, `.design/`, `.netis-deploy-env.json`을 지운다.
  2. 남은 변경이 있으면 `wip: 구현 중단({시간 초과|비정상 종료|실행 오류}) — task #{id}` 커밋(netisMaker).
  3. `{baseSha}..HEAD`에 커밋이 있으면 작업 브랜치를 push(강제 아님). PR/MR은 만들지 않는다.
- **보고**: 구현실패 사유 끝에 `— 지금까지 작업(커밋 N개)은 브랜치 \`…\`에 올려 둠`, `head_branch`·`head_sha`에 push한 값. push가 실패하면 `— 부분 작업 push 실패(worktree에만 남음): {마스킹한 오류}`. 남길 게 없으면 사유는 지금 그대로.
- worktree는 지금처럼 실패분으로 남는다(7일 보존). 올라간 WIP 브랜치는 자동 삭제하지 않는다(작업 종료가 지우는 것은 최종 PR 브랜치뿐).
- 사용량: exit≠0은 지금처럼 결과 JSON에서, 시간 초과는 결과를 받기 전에 끊기므로 기록되지 않는다(알려진 한계 — 다음 스펙 ③에서 해결).

### 6.7 PR/MR 본문

- 줄 추가: `- **구현 방식**: 리뷰` — 2회째부터 ` (2회째)`, 대신 진행했으면 ` (SDD 요청 — 플러그인 경로 없음으로 리뷰로 진행)`.
- `.netis/review-notes.md`를 회수했으면 `## 리뷰 메모` 절로 붙인다 — UTF-8, `GitRemotes.mask` 적용, 20,000자 초과분은 자르고 `…(이하 생략)`.

### 6.8 사용량 토큰

`ClaudeExecAdapter`의 결과 파싱에서 `modelUsage`가 있으면 모델별 `inputTokens`·`outputTokens`·`cacheCreationInputTokens`·`cacheReadInputTokens` 합계를 쓰고, 없으면 지금처럼 `usage`. 비용은 지금처럼 `total_cost_usd`. 공용 파서라 모든 `claude -p` 단계의 토큰이 서브에이전트까지 포함해 정확해진다.

### 6.9 설정·기동 스크립트

- 워커 설정 `netis-maker.worker.superpowers-plugin-path: ${SUPERPOWERS_PLUGIN_PATH:}` — `WorkerProperties` 레코드가 아니라 전용 빈 `SuperpowersPlugin`이 읽는다(레코드를 위치 인자로 만드는 테스트 12곳을 건드리지 않으려고). 경로 아래 `skills/subagent-driven-development/SKILL.md`가 있어야 SDD 가능으로 본다.
- Windows `scripts/start-public.ps1`: 인터뷰 서비스용 superpowers 경로 탐색(지금 인터뷰 블록 안)을 워커 기동 앞으로 옮겨 워커 env에도 넘긴다. 못 찾으면 워커는 그대로 띄우고 경고 한 줄.
- mac `scripts/start-all.sh`: `SUPERPOWERS_PLUGIN_PATH`를 워커에도 넘긴다.

## 7. 프론트엔드

- `composables/implementationMode.ts`: 방식 타입·이름·한 줄 설명, 기본값 `REVIEW`, 경과 문구 계산(순수 함수).
  - 설명: 직접 "리뷰 없이 바로 구현 — 가장 빠르고 저렴" / 리뷰 "구현 후 Opus 리뷰 1회, 중대 문제만 수정 — 구현 비용 약 1.6~1.8배(실측 2건)" / SDD "작업마다 서브에이전트가 구현·리뷰 — 가장 느리고 비쌈, 최대 4시간 워커 점유".
- `components/ImplementationModePicker.vue`: `q-btn-toggle`(직접 / 리뷰 / SDD) + 선택한 방식 설명 한 줄. 루트는 `<div>`, 좁은 화면에서 줄바꿈.
- **인터뷰 패널**(`InterviewPanel.vue`): "구현 진행" 줄의 "디자인 단계 포함" 옆에 피커(기본 리뷰), 확정 바디에 `implementationMode`.
- **방식 표시**: 확정 이후 상태(구현대기·구현중·PR생성·구현실패·디자인 4상태·배포 계열·종료 계열)에서 상세 데스크톱 칩 `구현 방식: 리뷰`(2회째부터 ` · 2회째`), 모바일 "정보"의 "구현" 줄. 옛 작업은 "직접".
- **경과 시간**: 구현중이고 `claimedAt`이 있으면 `23분 경과 / 상한 80분` — 상세(데스크톱·모바일)와 목록(데스크톱 표, 모바일 카드)의 상태 옆. 상한의 80% 이상이면 주황색. 화면에서 30초마다 다시 계산(API 호출 추가 없음).
- **구현 재시도**: `nextAction`의 구현실패 — 관리자는 "구현 실패 — 사유와 보존된 브랜치를 확인하고 재시도하세요" + 주 버튼 "구현 재시도"(`retry-implementation`), 관리자가 아니면 "구현 실패 — 관리자 확인이 필요합니다". 버튼은 `ImplementationRetryDialog.vue`(피커, 기본은 지금 방식) → `POST …/implementation/retry` → 새로 읽기. 데스크톱 상세에도 같은 동작.
- 테스트 픽스처·하이드레이션 스모크 목 응답에 새 필드를 넣는다(새 페이지·API 경로는 없음).

## 8. 오류 처리

| 상황 | 처리 |
|---|---|
| 확정·재시도에 모르는 방식 | 400 |
| 구현실패가 아닌 작업 재시도 | 409 |
| SDD·리뷰 준비 실패 | 낮은 방식으로 대신 진행(§6.5) |
| 에이전트가 직접·리뷰에서 커밋 | 받아들임(§6.1) |
| 커밋도 변경도 없음 | 지금처럼 구현실패 `nothing to commit …` |
| 시간 초과·비정상 종료 | 부분 작업 보존 후 구현실패(§6.6) |
| 워커 멈춤·사망 | API가 방식별 판정 시간·heartbeat로 구현실패(지금 경로) |
| 재시도 뒤 이전 시도의 늦은 보고 | 상태 그대로, 이력·사용량만(§5.6) |
| 리뷰 메모 없음·읽기 실패 | 절 생략, 구현은 계속 |
| `info/exclude` 기록 실패 | 경고, SDD는 리뷰로 |

## 9. 테스트

- **API(단위)**: 확정 — 방식 저장·null→직접·잘못된 값 400·이력 사유; 재시도 — 구현실패에서만·시도 번호 증가·필드 비우기·방식 유지/변경·이력 문구·409; 결과 보고 — 시도 번호 다르면 상태 불변·이력·사용량 누적, 같으면 기존 동작, null이면 기존 동작, 지각 정합화는 같은 시도만; claim 페이로드의 세 필드; `TaskResponse` 필드; stale 판정 시간이 방식별로 갈림; 설정 검사(상한 ≥ 판정이면 기동 실패).
- **API(통합, CI Testcontainers)**: V29 적용·CHECK 제약, 확정 → claim → 결과 → 재시도 왕복.
- **워커(단위)**: 방식별 프롬프트(직접은 기존 테스트 유지, `{commit_rule}` 치환), 리뷰 절·리뷰어 지시 placeholder 치환(저작권 주석 제외), SDD 플랜 절; `작업 N` → `Task N` 변환(코드 블록 제외, 0개면 대신 진행); `--plugin-dir`이 SDD에만; git 작성자 env; `info/exclude` 중복 없이; 시도 번호 브랜치 이름; `commitAndPush`의 에이전트 커밋 수용(임시 git 레포); 부분 작업 보존(변경만·커밋만·둘 다·없음·push 실패); PR 본문 방식 줄·대신 진행 문구·리뷰 메모·길이 자르기·마스킹; `modelUsage` 합산(없으면 `usage`); 상한(claim 값 우선, 없으면 설정).
- **프론트(vitest)**: 피커 기본값·설명, 확정 바디의 방식, 방식 칩 표시 상태, 경과 문구(주황 경계 포함), 재시도 다이얼로그 → API·다음 할 일 분기(관리자/아님).
- **로컬 스모크**: 실제 워커 명령 조합(`--mcp-config`·`--strict-mcp-config`·`--allowedTools` 포함 — 실측 때는 없었음)으로 연습용 계획을 리뷰·SDD 각 1회 돌려 Agent·Skill 도구 사용, 커밋 작성자, 커밋에 `.netis/`·`.superpowers/` 없음, 사용량 합산을 확인.
- **라이브 확인**(배포 뒤, 사용자가 확정 클릭): 작은 실제 작업을 리뷰·SDD로 각 1회 — PR/MR 본문, 커밋 작성자, MR에 임시 파일 없음, 구현 단계 사용량, 소요 시간 대 상한, 경과 표시.

## 10. 배포·정리

- 순서: **API**(V29, 확정·재시도·결과 시도 번호) → **워커**(기동 스크립트가 플러그인 경로 전달; 진행 중 구현이 없을 때 재기동) → **프론트**(`.output` 재빌드). 화면이 마지막이라 워커가 준비되기 전에는 리뷰·SDD를 고를 수 없다.
- CLAUDE.md: 작업 상태머신(재시도 전이), 구현 단계 설명(방식·상한·부분 보존), 자주 보는 코드 표, 워커 환경변수(`SUPERPOWERS_PLUGIN_PATH`).
- `renderPlanSection` 주석을 방식별 설명으로 고친다. 이 근거를 담으려던 미커밋 worktree `.claude/worktrees/plan-section-comment`(브랜치 `docs/plan-section-subagent-rationale`)는 이 기능이 대체하므로 정리한다.

## 11. 범위 밖

- 구현 진행률 — ② 계획 진행률(작업 N/M), ③ 실시간 활동(stream-json 중계) — **다음 스펙**. 시간 초과 실행의 사용량 기록도 ③에서.
- SDD 이어하기(재시도는 처음부터), 확정 뒤 방식 변경(재시도 때만).
- WIP 브랜치 자동 삭제.
- 대상 레포의 빌드·검증 방법 문서화(실측에서 검증 깊이가 실행마다 달랐던 문제).
- 서브에이전트 모델 강제, 워커 여러 대 운영(SDD가 도는 동안 다른 작업의 구현·배포·종료는 줄을 선다 — 인터뷰·질문은 별도 서비스라 영향 없음).

## 12. 영향 파일 (예상)

| 구분 | 파일 |
|---|---|
| DB | `src/main/resources/db/migration/V29__task_implementation_mode.sql` |
| API | `entity/ImplementationMode.java`(신규), `entity/Task.java`, `service/ImplementationModePolicy.java`(신규 — 상한·판정 설정과 기동 검사), `controller/InterviewController.java`, `service/InterviewService.java`, `controller/TaskController.java`, `service/TaskService.java`, `dto/TaskResponse.java`, `dto/WorkerTaskResponse.java`, `dto/WorkerResultRequest.java`, `service/WorkerService.java`, `service/StaleTaskRecoveryJob.java`, `src/main/resources/application.yml` |
| 워커 | `workerdaemon/WorkerMainLoop.java`, `workerdaemon/ImplementationScratch.java`(신규 — 계획·설계 파일 쓰기, 제목 변환, 리뷰 메모 회수, 임시 파일 삭제), `workerdaemon/ImplementationModeResolver.java`(신규 — 대신 진행 판정), `workerdaemon/ImplementationPrompts.java`(신규 — 방식별 플랜 절·커밋 지시·PR 방식 줄), `workerdaemon/SuperpowersPlugin.java`(신규 — 플러그인 경로), `workerdaemon/ClaudeExecAdapter.java`, `workerdaemon/GitOpsService.java`, `workerdaemon/WorktreeService.java`, `src/main/resources/application-worker.yml`, `src/main/resources/prompts/implementation-review-section.md`·`implementation-reviewer.md`(신규) |
| 스크립트 | `scripts/start-public.ps1`, `scripts/start-all.sh` |
| 프론트 | `composables/implementationMode.ts`(신규), `composables/useNowTick.ts`(신규 — 30초 시계, 마운트 뒤에만), `components/ImplementationModePicker.vue`(신규), `components/tasks/ImplementationRetryDialog.vue`(신규), `components/InterviewPanel.vue`, `pages/tasks/[id].vue`, `pages/tasks/index.vue`, `components/tasks/TaskDetailMobile.vue`, `components/tasks/TaskCardCompact.vue`, `composables/taskStages.ts`, `test/fixtures/task.ts`, `scripts/hydration-smoke.mjs` |
| 문서 | `CLAUDE.md` |
