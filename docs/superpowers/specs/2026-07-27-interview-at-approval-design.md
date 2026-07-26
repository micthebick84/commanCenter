# 인터뷰 시점 이동: 등록 → 관리자 승인

작성일: 2026-07-27
상태: 설계 확정 (구현 계획 대기)

## 1. 배경과 목적

현재 netisMaker의 모든 신규 작업은 **등록 시점에** 대화형 인터뷰를 거친다.
`frontend/pages/tasks/index.vue`의 등록 다이얼로그는 제출 버튼이 "인터뷰 시작" 하나뿐이고
(`submit()` = 순수 `POST /tasks`는 어느 버튼에도 연결되지 않은 죽은 경로),
`InterviewService.register()`가 인터뷰 종료 시 `Task(분석완료)` + `TaskAnalysis` 프리필을 만든다.
즉 인터뷰가 자동 분석을 대체하며, 관리자 승인 게이트는 그 **뒤에** 있다.

이 구조의 문제:

| 문제 | 내용 |
|---|---|
| 비용 통제 부재 | 승인되지 않을 작업도 등록만으로 claude 토큰을 소모한다 |
| 요구사항 품질 편차 | 현업 사용자가 답하는 인터뷰는 품질이 들쭉날쭉하다 |
| 등록 부담 | 등록에 긴 대화가 강제되어 사용자가 등록 자체를 꺼린다 |

**목표**: 인터뷰를 관리자 승인 시점으로 옮긴다. 등록은 가볍게, 자원 소모는 승인 이후에만,
인터뷰 답변은 관리자가 주도한다.

## 2. 결정 사항

| 항목 | 결정 |
|---|---|
| 답변 주체 | **관리자 단독**. 요청자는 읽기 전용 열람만 |
| 파이프라인 | 인터뷰가 계속 분석을 대체. 등록 → 승인대기 → (승인=인터뷰 시작) → 인터뷰 → 플랜 → 확정 → 구현/디자인 큐 |
| 2차 게이트 | 있음. 관리자가 플랜을 보고 "구현 진행"을 눌러야 큐 진입 |
| 레거시 자동분석 | 코드·경로 유지. 새 상태를 추가하는 방식으로 공존 |
| 모델·effort·MCP | 승인 다이얼로그에서 관리자가 결정 |
| 디자인 구간 여부 | 플랜 확정 시 관리자가 결정 |
| 구현 접근 | **옵션 A** — Task 중심, 인터뷰 세션은 task 하위 리소스 |

### 채택하지 않은 대안

- **옵션 B (상태 추가 최소화, 세션 조인으로 표시)**: "승인했는데 상태는 그대로"라 히스토리·필터·운영
  조회가 실제 진행 상황과 어긋난다. 목록 API마다 조인이 붙는다.
- **옵션 C (세션을 task와 1:1 흡수, task_id를 세션 PK로)**: `interview_turn`/`interview_plan` FK까지
  마이그레이션해야 하고, 재인터뷰 시 이력이 덮어써진다. 이득 대비 위험이 크다.

## 3. 상태머신

### 새 TaskStatus 4개

| Java | DB값 | 의미 |
|---|---|---|
| `AWAITING_APPROVAL` | `승인대기` | 등록 직후. 어떤 워커 큐에도 들어가지 않음 |
| `INTERVIEWING` | `인터뷰중` | 인터뷰 워커가 턴을 처리하는 중 |
| `INTERVIEW_INPUT` | `입력대기` | 질문이 나왔고 관리자 답변 대기 중 |
| `INTERVIEW_REVIEW` | `플랜승인대기` | 플랜이 나왔고 관리자의 "구현 진행" 확정 대기 중 |

```
등록 → [승인대기] ──(관리자 승인 = 인터뷰 시작)──→ [인터뷰중] ⇄ [입력대기]
                     ↑                                  │
                     │                            (플랜 생성)
                     │                                  ↓
                     │                          [플랜승인대기]
          (실패/만료/취소)                              │ 관리자 "구현 진행"
                     └──────────────────────────────────┤
                                                        ↓
              [구현대기] 또는 [디자인대기] → (이하 기존 파이프라인 그대로)
```

세션 상태를 task 상태로 **미러링**한다. 관리자가 행동해야 진행되는 상태(`입력대기`,
`플랜승인대기`)를 작업 목록에서 바로 식별하기 위해서다. `플랜승인대기`는 기존 `디자인승인대기`
(`DESIGN_REVIEW`)와 같은 성격의 게이트이므로 이름도 그에 맞춘다.

미러링 지점은 `InterviewService`의 전이 3곳뿐이며, 인터뷰 상태 전이의 단일 진입점이 이미 이
클래스라 확산되지 않는다:

| InterviewService 메서드 | 세션 | task |
|---|---|---|
| `recordQuestion` | `입력대기` | `입력대기` |
| `submitAnswer` | `인터뷰대기` | `인터뷰중` |
| `recordPlan` | `플랜완료` | `플랜승인대기` |

task의 `worker_id` / `claimed_at`은 인터뷰 구간에서 **null로 둔다**. 인터뷰 워커의 소유권은
`interview_session`의 동명 컬럼이 갖는다 — task 쪽에 쓰면 `StaleTaskRecoveryJob`의 판단 근거와
섞인다.

레거시 경로(`작업대기 → 분석중 → 분석완료`, 워커 `kind=ANALYSIS`)는 그대로 둔다.

## 4. 데이터 모델

- `interview_session.task_id`를 **세션 생성 시점에** 채운다 (현재는 `register` 시점).
  컬럼과 FK는 V11에 이미 존재하므로 스키마 변경 없음.
- 세션의 `github_repo` / `github_branch` / `title` / `description` / `model` / `effort` /
  `mcps_extra` / `git_url` / `repo_alias` / `repo_catalog_id`는 **task에서 복사한 스냅샷**이다.
  인터뷰 워커는 세션의 출처를 모른다 → `/worker/interviews/*` 계약 불변.
- `session.requester_id`는 **원 요청자**를 넣는다(추적·표시용). 답변 권한은 엔드포인트에서
  `ROLE_ADMIN`으로 잠근다. 요청자는 기존 GET ACL로 열람만 가능하다.
- task 1개에 세션이 **여러 개** 쌓일 수 있다(실패 후 재승인). 최신 세션 = `task_id` 기준
  `created_at DESC` 1건.

### 마이그레이션 V18

`task.status`는 CHECK 제약 없는 `VARCHAR(30)`이므로 새 값 추가에 DDL이 필요 없다. V18에서:

1. `com.task_queue_stats` 뷰 재생성(DROP 후 CREATE — V9/V15와 동일 패턴):
   - `pending_approval` = `status = '승인대기'`
   - `interviewing` = `status IN ('인터뷰중','입력대기')`
   - `plan_review` = `status = '플랜승인대기'`
   - 기존 `awaiting_approval`(분석완료 + 미승인)과 이름이 겹치지 않도록 위 명칭을 쓴다
2. `CREATE INDEX idx_interview_session_task ON com.interview_session(task_id, created_at DESC)`
3. 배포 시점의 고아 세션 일괄 마감:
   ```sql
   UPDATE com.interview_session
      SET status = '취소됨', updated_at = now()
    WHERE task_id IS NULL
      AND status IN ('인터뷰대기','인터뷰중','입력대기','플랜완료');
   ```

기존 task row는 변경하지 않는다. `작업대기` / `분석완료` / `PR생성` 등은 레거시 분기로 흘러간다.

### 한도 규칙 조정

- `TaskRepository.countActiveByRequester`(사용자당 미완료 5건)에 `승인대기` / `인터뷰중` /
  `입력대기` / `플랜승인대기`를 **추가**한다. 누락하면 새 초기 상태가 카운트에서 빠져 한도가
  무력화된다.
- `app.interview.user-concurrent-limit`(인터뷰 3건)은 **승인 경로에서 검사하지 않는다.**
  세션을 만드는 주체가 관리자로 바뀌었고, 상류에 task 등록 한도가 이미 있다. 요청자 기준 한도를
  유지하면 "요청자 A의 인터뷰 3건이 진행 중이라 관리자가 A의 4번째 작업을 승인 못 함"이라는
  비의도적 차단이 생긴다.

## 5. API 계약

### 변경

**`POST /api/tasks`** — body 축소
```
{ repoCatalogId, githubBranch, title, description }
```
`model` / `effort` / `mcpCatalogIds` / `designRequested`는 받지 않는다(오면 무시).
생성 상태는 `승인대기`. `task.model` / `effort`는 NOT NULL이므로 `ModelEffortPolicy.resolveModel(null)`
/ `resolveEffort(null)`의 기본값으로 채우고, 승인 시 관리자가 고른 값으로 덮어쓴다.
`mcps_extra`는 빈 배열, `design_requested`는 `false`로 시작한다.

**`POST /api/tasks/{id}/approve`** — `ROLE_ADMIN`, 상태에 따라 분기
```
body: { model?, effort?, mcpCatalogIds? }

승인대기  → 세션 생성(인터뷰대기) + task = 인터뷰중        ← 새 동작
분석완료  → 기존 그대로 (구현대기 / 디자인대기)             ← 레거시 보존
그 외     → 409
```
엔드포인트를 나누지 않는 이유: 관리자의 멘탈 모델은 "승인" 버튼 하나이고, 레거시 분기는 기존
코드를 그대로 둔다. 대안(`POST /{id}/interview/start` 신설)은 프론트에 "어떤 승인 버튼인가"
분기를 만든다.

**`POST /api/interviews/{sid}/confirm`** — 기존 `/register` 개명 + 동작 변경, `ROLE_ADMIN`
```
body: { designRequested }

세션 플랜완료 + task 플랜승인대기
  → TaskAnalysis 프리필 + task.status = 구현대기 | 디자인대기 + session.status = 등록됨
그 외 → 409
```
프리필 계약은 그대로 유지한다: `markdown_result = design_markdown`(합본 아님),
`subtasks_json = plan_json`, `claude_log = null`, `duration_ms = plan.durationMs`.
**Task를 생성하지 않고 기존 task를 갱신**하는 것이 유일한 실질 변경이다.

**`POST /api/interviews/{sid}/answer`** — 경로 유지, `ROLE_ADMIN` 추가.
`replyToSeq` 기반 멱등 로직은 그대로.

### 제거

- **`POST /api/interviews`** (사용자 세션 직접 생성) — 세션은 승인에서만 생성된다.
- **`GET /api/interviews/active`** + `frontend/composables/interviewResume.ts` —
  "새로고침 후 진행 중 인터뷰 찾기" 디스커버리. 인터뷰가 작업 상세 페이지에 살면 URL이 곧 세션
  위치이므로 불필요해진다.

### 유지

- `GET /api/interviews/{sid}`, `GET /api/interviews/{sid}/stream`(SSE) — ACL 그대로
  (소유자 또는 관리자).
- `/worker/interviews/*` 전 계약(claim / question / answer / plan / fail / heartbeat).
  **인터뷰 서비스(Node) 코드 변경 없음.**

### 되돌림 경로

| 트리거 | 세션 | task |
|---|---|---|
| `fail` (워커 오류·stale 회수) | `인터뷰실패` | `승인대기` |
| `expire` (idle TTL) | `만료됨` | `승인대기` |
| `cancel` (관리자, `플랜승인대기` 포함) | `취소됨` | `승인대기` |

`cancel`은 세션의 `플랜완료`(= task `플랜승인대기`)에서도 허용된다(기존 동작). 이 경우 플랜을
버리고 재승인으로 다시 인터뷰할 수 있다.

**`POST /api/tasks/{id}/cancel`**(요청자)을 `승인대기`에서도 허용하도록 넓힌다.
현재는 `작업대기` 한정이라 새 초기 상태에서 요청자가 자기 작업을 취소할 수 없다.

**삭제**: `인터뷰중` / `입력대기` task를 soft delete할 때 열려 있는 세션을 `취소됨`으로 먼저 닫는다.
배포 계열처럼 차단하지 않는다 — 인터뷰는 되돌릴 수 있는 자원이다.

## 6. 프론트엔드

### 등록 다이얼로그 (`pages/tasks/index.vue`)

2단계(`dialogPhase: 'form' | 'interview'`)를 1단계 폼으로 되돌린다.

- 남는 필드: 레포 선택, 브랜치, 제목, 설명
- 제거: 모델/effort 셀렉트, MCP 칩 + 헬스 배지 + DOWN 경고, `InterviewPanel` 임베드, 재개 피커
- 버튼: "인터뷰 시작" → "작업 등록". 죽어 있던 `submit()`을 다시 연결
- 상태 필터에 `승인대기` / `인터뷰중` / `입력대기` / `플랜승인대기` 추가

### 새 컴포넌트

- **`ApproveDialog.vue`** — 승인 시 뜨는 다이얼로그. 모델·effort·MCP 선택 후
  "승인하고 인터뷰 시작". 등록 폼에서 걷어낸 UI를 그대로 옮긴다.
- **`McpPicker.vue`** — MCP 칩 목록 + 헬스 배지 + DOWN 경고. 현재 `index.vue`에 인라인으로 있는
  블록을 컴포넌트로 추출해 `ApproveDialog`가 사용한다. (모델/effort는 `composables/modelEffort.ts`가
  이미 로직을 갖고 있어 마크업만 이동)

### 작업 상세 (`pages/tasks/[id].vue`)

| task 상태 | 관리자 화면 |
|---|---|
| `승인대기` | **[승인 — 인터뷰 시작]** → `ApproveDialog` |
| `인터뷰중` | `InterviewPanel` (SSE 스트리밍, 입력창 비활성) |
| `입력대기` | `InterviewPanel` (답변 입력창 활성) |
| `플랜승인대기` | `InterviewPanel` + 하단에 디자인 구간 토글 + **[구현 진행]** / [취소] |
| 그 외 | 기존 화면 그대로 |

요청자(비관리자)에게는 같은 위치에 `InterviewPanel`이 **읽기 전용**(`readonly` prop)으로 보인다.
대화 로그와 플랜은 보이고 입력창·버튼은 렌더되지 않는다.

### 배선

- `TaskResponse`에 **`interviewSessionId`**(해당 task의 최신 세션) 1개 추가. 상세 페이지가 이 값으로
  패널을 연다. 목록 응답에는 넣지 않는다 — 목록은 task 상태만으로 충분하다.
- `InterviewPanel.vue` 수정: `register()` → `confirm()`(엔드포인트/라벨), `registered` emit →
  `confirmed`, `readonly` prop 추가. 스트림·턴 렌더링·재연결 로직은 손대지 않는다.
- `QueueStatsBar.vue`에 `승인대기` / `인터뷰중` / `플랜승인대기` 타일 추가
  (`QueueStats` DTO와 `QueueStatsRepository` 매핑도 동일 3개 확장).

### 삭제되는 프론트 자산

`composables/interviewResume.ts`와 그 스펙, `index.vue`의 재개 피커 다이얼로그.

## 7. 에러 처리 · 복구 · 동시성

### 회수 잡

`InterviewStaleRecoveryJob`은 모든 전이를 `InterviewService.fail` / `expire`에 위임한다
(`service/InterviewStaleRecoveryJob.java:60,74`). task 미러링을 그 두 메서드에 넣으면 회수 잡 코드는
변경 없이 task가 `승인대기`로 돌아온다.

- RUNNING 60분 초과(`stale-running-minutes`) → 세션 `인터뷰실패` + task `승인대기`
- 입력대기 24시간 초과(`idle-ttl-minutes:1440`) → 세션 `만료됨` + task `승인대기`

`StaleTaskRecoveryJob`(task 쪽)은 **변경하지 않는다.** 인터뷰 상태를 그 잡의 in-flight 목록에
넣어서는 안 된다 — 인터뷰는 사람의 답변을 기다리는 것이 정상이므로 "오래 머무름 = 고장"이 아니다.

### 워커 큐

`TaskRepository.findClaimableForUpdateSkipLocked`의 후보 상태에 새 상태가 없으므로 구현 워커가
인터뷰 중인 task를 집을 수 없다. 쿼리 수정 불필요.

### 동시성

- 인터뷰 서비스는 단일 폴링 루프(`claimLoop.ts`)로 세션을 순차 처리한다. 승인 3건 → 세션 3건이
  `인터뷰대기`에 쌓여 순서대로 진행된다.
- `recordQuestion`이 워커를 반납(`worker_id = null`)하므로, 관리자가 A의 질문을 읽는 동안 워커는
  B의 첫 턴을 돌린다.
- 승인 더블클릭 → 두 번째 호출은 상태 가드에서 409. 세션 중복 생성 없음.

### 엣지 케이스

| 상황 | 처리 |
|---|---|
| 재승인(실패 후) | 새 세션 생성. 이전 세션·턴·플랜은 DB에 남고 상세에는 최신 세션만 표시 |
| 비용 표시 | 최신 세션의 `total_cost_usd`만 표시. 이전 세션 합산은 하지 않음 |
| 요청자 답변 시도 | 403 (`ROLE_ADMIN` 가드) |
| 인터뷰 중 task 삭제 | 열린 세션을 `취소됨`으로 닫고 soft delete |
| 확정 중 DB 오류 | 트랜잭션 롤백으로 세션 `플랜완료` + task `플랜승인대기` 유지 → 재클릭 |
| 인터뷰 없이 바로 구현 | 이번 스코프 밖. 레거시 자동분석 경로가 살아있어 추후 추가 가능 |

## 8. 테스트

### `TaskServiceTest`
- `create` → `승인대기`
- `approve(승인대기)` → 세션 1건 생성 + task `인터뷰중` + 히스토리 1건
- `approve(분석완료)` → 기존 동작 유지(구현대기 / 디자인대기) — 레거시 회귀 방어
- `approve(인터뷰중)` → 409, 세션 중복 생성 없음
- `cancel(승인대기)` 허용
- `countActiveByRequester`가 새 상태 3개를 포함

### `InterviewServiceTest`
- `confirm(플랜승인대기)` → `TaskAnalysis` 프리필 계약 + task `구현대기`
- `confirm(designRequested=true)` → task `디자인대기`
- `confirm(인터뷰중)` → 409 (플랜 없이 확정 불가)
- `fail` / `expire` / `cancel` → task `승인대기` 미러
- `recordQuestion` → task `입력대기`, `submitAnswer` → task `인터뷰중`,
  `recordPlan` → task `플랜승인대기`
- `submitAnswer` 멱등 재제출이 task 상태를 변경하지 않을 것
  (no-op 반환 경로에 미러링을 넣으면 안 되는 회귀 지점)

### API 통합 (MockMvc + Testcontainers)
- 요청자가 `POST /interviews/{id}/answer` → 403
- 승인 body의 model / effort / mcpCatalogIds가 세션 스냅샷에 반영
- 인터뷰 중 task 삭제 → 세션 `취소됨` + `deleted_at` 설정
- `InterviewWorkerController` 기존 테스트가 무수정 통과할 것 (워커 계약 불변의 증거)

### 프론트 (vitest)
- `ApproveDialog` 제출 payload
- 등록 폼 축소 후 `POST /api/tasks` payload에 model / effort / mcp 없음
- `InterviewPanel`의 `readonly` prop → 입력창·버튼 미렌더
- `interviewResume.spec.ts` 삭제, `useInterviewStream.spec.ts`의 재개 관련 케이스만 정리

## 9. 배포

- API 서버와 프론트를 함께 배포한다 (`POST /api/interviews` 제거로 계약이 깨짐).
- 인터뷰 서비스(Node)는 재기동 불필요(계약 무변경).
- 배포 전 진행 중 인터뷰를 마무리하도록 공지하면 V18의 고아 세션 마감 대상이 0건이 된다.
- `./scripts/start-all.sh` 2단계 런북 그대로.

## 10. 문서 갱신

- `netisMaker/CLAUDE.md`의 작업 상태머신 섹션
- `entity/TaskStatus.java` javadoc의 상태 다이어그램

이 둘이 이 프로젝트의 실질 SSOT이므로 구현과 같은 PR에서 갱신한다.
