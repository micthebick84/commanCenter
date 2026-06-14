# 대화형 분석 (Conversational Analysis) 설계안

- **날짜**: 2026-06-13
- **상태**: 설계 승인됨 (구현 계획 작성 전)
- **요청 출처**: 작업 등록 전, 선택한 프로젝트 기반으로 추가 기능의 구현 검토 + 디자인 레이아웃을 인터뷰 형식으로 진행. 인터뷰는 superpowers `brainstorming → writing-plans`로 구동하고, 산출된 개발 계획을 "작업 등록"할 수 있게 한다. UI/UX는 작업 등록 팝업 안에서 구현한다.

---

## 1. 개요 & 목표

netisMaker에 **대화형 분석** 기능을 추가한다. 사용자가 작업을 등록하기 전에, 선택한 프로젝트(GitHub 레포) 코드를 실제로 읽는 AI와 **인터뷰**를 진행하여 "추가할 기능의 구현 검토 + 디자인/레이아웃"을 함께 설계하고, 그 결과로 나온 **개발 계획**을 작업으로 등록한다.

인터뷰 엔진은 superpowers의 `brainstorming → writing-plans` 흐름을 **헤드리스(Claude Agent SDK)**로 구동한다:
- `brainstorming`: 프로젝트 컨텍스트 탐색 → 명료화 질문(1개씩) → 접근안 제안 → 설계 섹션별 승인 → 설계 문서(spec)
- `writing-plans`: spec → 잘게 쪼갠 구현 플랜(plan)

산출물(설계 + 플랜)은 netisMaker DB에 저장되고, "작업 등록" 시 기존 작업 파이프라인의 **분석완료(COMPLETED)** 단계로 진입한다.

### 성공 기준
1. 사용자가 작업 등록 팝업에서 프로젝트와 요구를 입력하고 "인터뷰 시작"을 누르면, AI가 레포 코드를 근거로 질문을 하나씩 던지고 사용자가 답한다.
2. 인터뷰가 brainstorming→writing-plans 게이트를 거쳐 설계 문서와 구현 플랜을 만든다.
3. "작업 등록"을 누르면 그 플랜이 채워진 Task가 생성되어 기존 승인→구현→PR→배포 파이프라인을 탄다.
4. **task는 인터뷰 완료 이전에 절대 생성되지 않는다.**

---

## 2. 사용자 시나리오

1. 사용자가 `/tasks`에서 "작업 등록" 버튼을 누른다 → 팝업이 열린다.
2. **Phase 1 (입력)**: GitHub 레포(owner/repo) + 브랜치 + 제목 + 요구(추가하려는 기능) 입력. (선택) 추가 MCP 카탈로그 선택.
3. "인터뷰 시작" → 팝업이 **Phase 2 (분할 뷰)**로 전환된다.
4. 좌측 대화 영역에 AI 질문이 하나씩 스트리밍되고, 사용자가 답한다. 우측에는 설계 섹션·플랜이 실시간으로 조립되며 승인된 섹션이 체크 표시된다.
5. brainstorming의 설계 섹션 승인, 스펙 검토 게이트, writing-plans 핸드오프가 모두 대화 턴으로 진행된다.
6. 플랜이 완성되면(`PLAN_READY`) 우측에 설계+플랜이 표시되고 "작업 등록" 버튼이 활성화된다.
7. "작업 등록" → Task가 **분석완료** 상태로 생성되고, 인터뷰 산출물이 `TaskAnalysis`에 채워진다. 팝업이 닫히고 작업 리스트가 갱신된다.
8. 이후는 기존 흐름: 관리자 승인 → 구현 워커 → PR → 배포.

---

## 3. 핵심 설계 결정

| # | 결정 | 채택 | 기각된 대안 |
|---|------|------|-------------|
| Q1 | 인터뷰와 task의 관계 | **인터뷰는 task가 아닌 독립 `interview_session` 엔티티. task는 인터뷰 완료 후에만 생성(승격).** | (B) 초안 task를 INTERVIEWING으로 즉시 생성 — "task는 완료 전 생성 금지" 요구에 위배. (A) task 없이 전부 임시 — 인프라 전부 신규. |
| Q2 | 인터뷰가 코드를 읽나 | **읽는다. 워커 사이드 레포 체크아웃 후 인터뷰.** | (A) intent만 — "구현 검토"엔 코드 근거 필요. |
| Q3 | 실행 엔진 & 워커 점유 | **Claude Agent SDK + 인터뷰 전용 워커 풀.** | (A) 워커 점유(직렬 큐 블로킹). |
| Q4 | 토폴로지 | **SDK 서비스 = "인터뷰 워커", Java API가 유일 게이트웨이(pull/claim).** | (A) Java push forward — 세션 생명주기 동기화 취약. (B) 브라우저 직결 — 보안 표면 증가. |
| Q5 | 등록 시 파이프라인 진입점 | **분석완료(COMPLETED) — 인터뷰 산출을 `TaskAnalysis`에 프리필, 기존 승인 게이트 유지.** | (C) APPROVED 직행 — 거버넌스 생략. (A) PENDING 재분석 — 낭비. |
| Q6 | 산출물 저장 | **netisMaker DB only. 사용자 레포 미오염(푸시 안 함).** | (B) 레포 커밋/PR — 미채택 인터뷰까지 레포 오염. |
| Q7 | SDK 언어 | **TypeScript / Node** (프론트 Nuxt와 스택 통일). | Python. |
| Q8 | 팝업 UI | **분할 뷰(좌 대화 / 우 설계·플랜 실시간 조립).** | (A) 챗 우선 접이식. (B) 스테퍼 위저드. |

---

## 4. 시스템 아키텍처

5개 컴포넌트. pull/claim 모델, Java API가 유일 게이트웨이.

### ① 브라우저 (Nuxt 팝업) — 기존 확장
`frontend/pages/tasks/index.vue`의 작업 등록 다이얼로그를 확장한다. 2-phase: 입력 폼 → 분할 뷰 인터뷰 패널. 질문은 SSE로 수신, 답변은 REST로 전송, 끝에 "작업 등록".

### ② Java API (netisMaker) — 신규 컨트롤러/서비스
`InterviewController` / `InterviewService` 신규. 책임: 세션 생성, 워커 claim, 질문 fan-out(기존 `DeployLogStreamService` SSE 메커니즘 재사용), 답변 수신, 작업 등록(승격). 유일한 인증/SSE/DB 경계.

### ③ SDK 인터뷰 서비스 (신규 TS/Node 사이드카)
Claude Agent SDK 기반. Java API에서 인터뷰 일감을 claim하는 **기존 워커식 pull 모델**. superpowers 플러그인 로드 후 `brainstorming → writing-plans` 구동, 평문 질문 중계, 스킬 디스패치 shim, resume. **전용 인터뷰 워커 풀, 단일 호스트 고정.** API 키 인증 + 프롬프트 캐싱 + 비용 추적. (기존 Java 워커 데몬은 분석/구현/배포 그대로 담당.)

### ④ PostgreSQL (com 스키마)
신규 3테이블: `interview_session`, `interview_turn`, `interview_plan`. 펜딩 Q&A·`claude_session_id`·산출 플랜을 자체 영속화(SDK 펜딩 콜백에 의존 안 함). 등록 시 기존 `task`·`task_analysis`에 프리필.

### ⑤ 레포 체크아웃 — 기존 재사용
`GitRepoCache`(depth-1 clone)로 인터뷰가 실제 코드를 읽는다. 산출물은 푸시하지 않는다.

---

## 5. `interview_session` 상태 머신

> Task의 상태 enum(`TaskStatus`)은 **변경하지 않는다.** 인터뷰 상태는 전부 `interview_session.status`에 있다.

| 상태 (Java/한글) | 의미 | 다음 전이 |
|---|---|---|
| `QUEUED` / 인터뷰대기 | 생성됨, 워커 claim 대기 (신규 또는 답변 후 재큐) | → RUNNING |
| `RUNNING` / 인터뷰중 | 워커 claim, SDK 세션 실행 중 | → AWAITING_INPUT, PLAN_READY, FAILED |
| `AWAITING_INPUT` / 입력대기 | 에이전트가 질문 emit, 사람 답변 대기 (워커 반납됨) | → QUEUED(답변 도착), EXPIRED, CANCELLED |
| `PLAN_READY` / 플랜완료 | writing-plans 완료, 설계+플랜 영속화 | → REGISTERED, CANCELLED |
| `REGISTERED` / 등록됨 | "작업 등록" 완료, Task 생성됨 (terminal) | — |
| `CANCELLED` / 취소됨 | 사용자 취소 (terminal) | — |
| `EXPIRED` / 만료됨 | idle TTL 초과 (terminal, 재시작 안내) | — |
| `FAILED` / 인터뷰실패 | clone/SDK/parse/비용상한 등 오류 (terminal) | — |

**claim 후보** = `QUEUED`. `claude_session_id`가 null이면 brainstorming 신규 시작, 있으면 `resume`. claim은 기존 패턴대로 `FOR UPDATE SKIP LOCKED`, FIFO. RUNNING 중 워커 사망은 stale 회수로 QUEUED 재큐 또는 FAILED.

---

## 6. 데이터 흐름 (한 턴)

1. 팝업: 프로젝트(repo+branch)+요구 입력 → `POST /api/interviews` → 세션 생성(QUEUED), 프론트가 SSE 구독 시작.
2. 인터뷰 워커가 QUEUED claim → `GitRepoCache` clone → SDK 세션 시작(brainstorming) → RUNNING.
3. 에이전트가 코드를 읽고 질문(평문)을 생성 → 워커가 질문 + `claude_session_id`를 Java로 POST.
4. Java: `interview_turn`(role=assistant) 저장 → SSE `event: question` push → AWAITING_INPUT → 워커 반납.
5. 브라우저: 좌측 채팅에 질문 표시 / 우측 산출물 갱신 → 사용자 답변 → `POST /api/interviews/{id}/answer`.
6. Java: `interview_turn`(role=user) 저장 → 세션 QUEUED 재큐.
7. 워커 재claim → SDK `resume(session_id)` + 답변 주입 → 다음 질문/섹션/플랜 → (3~7 반복).
8. writing-plans 완료 → 설계+플랜 harvest → `interview_plan` 저장 → PLAN_READY → SSE `event: plan_ready` push.
9. 사용자 "작업 등록" → `POST /api/interviews/{id}/register` → Task(COMPLETED) + TaskAnalysis 프리필 생성 → 기존 파이프라인 인수.

---

## 7. DB 스키마 (V11 마이그레이션, com 스키마)

기존 패턴 미러링. status는 CHECK 제약 없는 `VARCHAR(30)`(기존 컨벤션과 동일).

### `com.interview_session` — 큐+클레임 (task 미러)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGSERIAL PK | |
| requester_id | BIGINT | 요청자 |
| github_repo | VARCHAR | owner/repo (shape 검증) |
| github_branch | VARCHAR | |
| title | VARCHAR(500) | |
| description | TEXT | 추가하려는 기능/요구 |
| status | VARCHAR(30) | 상태 머신 값 |
| claude_session_id | VARCHAR(100) | nullable, resume용 |
| work_dir | VARCHAR | nullable, resume cwd(레포 체크아웃 경로) — resume 시 반드시 동일 값 전달 (스파이크 02) |
| worker_id | VARCHAR | nullable, claim 소유 |
| claimed_at | TIMESTAMPTZ | nullable, stale 회수 기준 |
| commit_sha | VARCHAR | nullable, clone HEAD |
| current_phase | VARCHAR(20) | brainstorming / writing-plans |
| total_cost_usd | NUMERIC | 누적 비용 |
| mcps_extra | JSONB | 작업별 추가 MCP 스냅샷 (기존 freeze 패턴) |
| task_id | BIGINT | nullable, 등록 시 set (FK task) |
| created_at / updated_at / last_activity_at | TIMESTAMPTZ | |

### `com.interview_turn` — 추가전용 Q&A 로그 (task_status_history 미러)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGSERIAL PK | |
| session_id | BIGINT FK CASCADE | |
| seq | INT | 순서 |
| role | VARCHAR(20) | assistant / user / system |
| kind | VARCHAR(20) | question / answer / design / gate / note |
| content | TEXT | |
| created_at | TIMESTAMPTZ | |

인덱스: `(session_id, seq)`.

### `com.interview_plan` — 1:1 위성 (task_analysis 미러)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| session_id | BIGINT PK FK | |
| design_markdown | TEXT | brainstorming spec |
| plan_markdown | TEXT | writing-plans 플랜 |
| plan_json | JSONB | 잘게 쪼갠 태스크 배열 |
| duration_ms | BIGINT | |
| total_cost_usd | NUMERIC | |
| completed_at | TIMESTAMPTZ | |

(선택) 인터뷰 큐 카운터 뷰는 기존 `task_queue_stats` 패턴으로 추후 추가 가능(필수 아님).

---

## 8. API 엔드포인트 (신규)

### 사용자용 (`/api/interviews`, OAuth JWT 인증)
- `POST /api/interviews` — 세션 생성. body `{ githubRepo, githubBranch, title, description, mcpCatalogIds: number[] }` → `{ sessionId }`. 동시성 게이트(요청자별 active 한도)는 기존 `countActive` 패턴 재사용.
- `GET /api/interviews/{id}` — 세션 상세(status, turns, plan). ACL 게이트(소유자/관리자).
- `GET /api/interviews/{id}/stream` — SSE. events: `question`, `design`, `plan_ready`, `status`, `done`. 인증은 기존처럼 `?access_token=` 허용 + keepalive ping 추가.
- `POST /api/interviews/{id}/answer` — body `{ answer, replyToSeq }`. idempotent(seq 기준 중복 무시).
- `POST /api/interviews/{id}/register` — 승격 → `{ taskId }`. PLAN_READY에서만 허용.
- `POST /api/interviews/{id}/cancel` — 취소.

### 인터뷰 워커용 (`/worker/interviews`, API-Key 인증 — 기존 `WorkerApiKeyFilter` 재사용)
- `POST /worker/interviews/claim` — QUEUED 세션 claim → 세션 메타 + repo/branch + `claude_session_id`(resume용) + 마지막 사용자 답변 + 누적 turn 컨텍스트.
- `POST /worker/interviews/{id}/question` — body `{ content, claudeSessionId, kind, costUsd }`. Java가 turn 저장 + SSE push + AWAITING_INPUT.
- `POST /worker/interviews/{id}/plan` — body `{ designMarkdown, planMarkdown, planJson, costUsd, durationMs }` → PLAN_READY.
- `POST /worker/interviews/{id}/heartbeat` / `POST /worker/interviews/{id}/fail` — 하트비트/실패 보고(기존 워커 패턴).

---

## 9. SDK 인터뷰 서비스 상세

- **엔진**: `@anthropic-ai/claude-agent-sdk` (TS), **v1 안정 스트리밍 입력** `query({ prompt: AsyncIterable<SDKUserMessage>, options })`. v2 `unstable_v2_*`는 preview라 미채택.
- **스킬/플러그인 로드**: `options.plugins: [{ type: 'local', path: <superpowers 플러그인 디렉터리, 예: ~/.claude/plugins/.../superpowers/<version>> }]` + `options.settingSources: ['user', 'project']` + `options.allowedTools`에 `'Skill'`, `Read`/`Grep`/`Glob`(탐색), `Write`(spec/plan 파일), 제한적 `Bash`. (SDK는 기본적으로 파일시스템 설정/스킬을 로드하지 않으므로 `settingSources` 명시 필수. **단 `settingSources:['user','project']`는 사용자의 모든 플러그인을 로드하므로(스파이크 04 확인), superpowers만 격리하려면 `settingSources`를 생략하고 `plugins:[{type:'local'}]`만 사용 — 이때 `Skill` 툴이 `init.tools`에 남는지 확인.)**
- **권한 (스파이크 04로 정정)**: 권한 제어는 **반드시 `options.canUseTool` 콜백으로 강제** — `allowedTools`는 스킬 내부 tool 호출을 제약하지 못함(brainstorming이 화이트리스트에 없던 `Bash`를 실행함을 관찰). `canUseTool`에서 모든 tool 호출에 대해 `Write`는 `docs/superpowers/**`만 허용, `Bash` 화이트리스트, **레포 수정/푸시 차단**을 검사. 인터뷰는 읽기 위주.
- **질문 중계**: 평문 텍스트 턴. assistant 메시지 텍스트 = 질문 → SSE. 사람 답변 = 다음 user 메시지. **`AskUserQuestion` 미사용**(답이 미리 정의된 옵션 라벨로만 와서 자유 답변 주입 불가).
- **스킬 디스패치 shim**: brainstorming의 종결 상태 = writing-plans invoke. `'Skill'` 툴로 모델이 직접 호출하게 하되, 핸드오프가 발화되지 않으면 **오케스트레이터가 전이를 감지하여 다음 SKILL.md 텍스트를 같은 세션에 splice**(fallback). 이 부분만 평문 대화로 degrade되지 않는 유일 지점 → 스파이크로 자동 트리거 동등성 확인.
- **세션 대기 모델 (검증 조정)**: 기본을 **답변마다 `resume(claude_session_id)` 재진입**으로 잡는다(항상 안전, 펜딩 상태는 DB). 짧은 적극 응답 구간의 인메모리 스트리밍 홀드는 선택적 최적화. 펜딩 질문/답변은 SDK가 아닌 netisMaker DB에 영속화하여 크래시 시 재구동(re-drive) 가능하게 한다.
- **호스트 고정 (스파이크 02로 정밀화)**: `resume`는 로컬 세션 스토어 `~/.claude/projects/<cwd-해시>/`에 의존하며 **cwd에 엄격히 종속**(다른 cwd로 resume 시 하드 실패). 따라서 `interview_session.work_dir`(레포 체크아웃 경로)를 `claude_session_id`와 함께 저장하고 **모든 resume 호출에 동일 cwd 전달**. 인터뷰 워커 풀은 **단일 호스트 또는 공유 볼륨**에 고정. 교차 머신 resume은 범위 외.
- **인증/비용 (스파이크 00b로 정정)**: SDK는 `options.pathToClaudeCodeExecutable`로 로컬 `claude` CLI **구독을 그대로 사용**한다 — `ANTHROPIC_API_KEY` 불필요(`apiKeySource: "none"` 확인), 별도 토큰 과금 없음(나머지 netisMaker와 동일 모델). `result.total_cost_usd`는 실제 청구가 아닌 **shadow 값**이므로 `interview_session.total_cost_usd`에는 쿼터 인지용으로만 누적하고, "비용 상한"은 **세션당 turn 수/쿼터 가드**로 재해석한다. resume가 매 턴 히스토리를 재전송하므로 프롬프트 캐싱은 여전히 권장.
- **작업 디렉터리**: `GitRepoCache` 캐시(분석 읽기 전용). spec/plan `.md`는 캐시 내/scratch에 생성 후 harvest하여 DB 저장, 레포 원격에는 푸시하지 않음.

---

## 10. 프론트엔드 (분할 뷰 팝업)

`tasks/index.vue` 다이얼로그 확장:
- **Phase 1 (입력)**: 기존 폼(repo/branch/title/description/MCP) 재사용. 제출 버튼을 "인터뷰 시작"으로.
- **Phase 2 (분할 뷰)**: 좌측 = 대화 트랜스크립트 + 답변 입력창. 우측 = 설계 섹션(승인 체크) + 플랜 미리보기 + (PLAN_READY 시) "작업 등록" 버튼.
- SSE 클라이언트는 기존 `[id].vue`의 EventSource 패턴 재사용 + 재연결/replay.
- 좁은 화면은 좌/우 탭 전환으로 graceful degrade. 다이얼로그는 인터뷰 단계에서 넓게/최대화.

---

## 11. superpowers 게이트 매핑

| superpowers 게이트 | netisMaker 표현 |
|---|---|
| 명료화 질문 (1개씩) | 대화 턴 (좌측 채팅) |
| 접근안 2~3개 제안 | 대화 턴 |
| 설계 섹션별 승인 | 대화 턴 + 우측 패널 섹션 체크 |
| HARD-GATE (승인 전 구현 금지) | 인터뷰 단계엔 구현 행위 없음(읽기 위주)으로 자연 충족 |
| 스펙 검토 게이트 | 대화 턴 ("스펙 검토하고 알려달라") + 우측 spec 표시 |
| writing-plans 실행 핸드오프 | 인터뷰 종료 = PLAN_READY. 실제 "구현 착수 승인"은 **등록 후 기존 관리자 승인 게이트**(COMPLETED→APPROVED)로 매핑 |
| 비주얼 컴패니언 | 헤드리스 불가 → text-only(스킬 자체 fallback 사용) |

---

## 12. 에러 처리 & 엣지

| 상황 | 처리 |
|---|---|
| RUNNING 중 워커 사망 | stale 회수(`StaleTaskRecoveryJob` 유사) → QUEUED 재큐(resume) / FAILED. 펜딩은 DB라 무손실 |
| 만료·취소된 세션에 답변 도착 | 409 거부 + UI 만료 안내 |
| 중복 답변 제출 | seq/idempotency로 1개만 수용 |
| idle TTL 초과(사람 미복귀) | EXPIRED + 재시작 안내 |
| 레포 clone / SDK / rate limit | 재시도·backoff(분석 워커 정책 재사용), 초과 시 FAILED |
| 비용 상한 초과 | 경고 후 중단 |
| plan harvest/parse 실패 | 형식 검증 → 재시도 / 부분 저장 + 수동 보정 |
| SSE 재연결 | replay(기존 subscribe) + keepalive ping 추가 |
| 멀티 API 인스턴스 | 현재 in-memory 단일 인스턴스 제약 상속(공유 버스는 범위 외) |

---

## 13. 테스트 & 스파이크

### 스파이크 (구현 초기 필수 — SDK 문서 미확인 항목)
1. 30분+ idle 후 스트리밍 세션/`resume` 정상 동작 — idle 타임아웃 확인.
2. 다른 cwd/프로세스에서 crash-and-resume + 펜딩 질문을 자체 영속 상태로 re-drive.
3. 동시 세션 N개 리소스/레이트리밋 측정(세션당 서브프로세스 풋프린트).
4. superpowers 스킬 자동 트리거 동등성(brainstorming→writing-plans 핸드오프 실발화) end-to-end.

### 테스트
- **Java**: 상태 전이 가드 단위테스트, claim 동시성(`FOR UPDATE SKIP LOCKED`), answer idempotency, register→Task+TaskAnalysis 매핑.
- **SDK 서비스**: 질문 중계 루프, resume 재진입, 비용 상한, harvest 파서, 스킬 디스패치 shim.
- **통합 E2E**: 팝업→세션→(모의 SDK)→Q&A 왕복→플랜→등록→기존 파이프라인 진입.
- **프론트**: 분할 뷰 SSE 렌더, 답변 전송, 만료/에러 상태.

---

## 14. 범위 외 / 향후
- 멀티 머신 인터뷰 워커(교차 resume).
- 멀티 인스턴스 SSE 공유 버스(Redis pub/sub 또는 PG LISTEN/NOTIFY) — 06-01 로드맵과 동일.
- 비주얼 컴패니언(헤드리스 불가).
- SDK v2 (`unstable_v2_*`) 채택.
- 인터뷰 산출물의 레포 커밋/PR(Q6의 C안) — 필요 시 확장.

---

## 15. 검증 근거 (Claude Agent SDK 능력 확인)

context7 공식 문서 기반 + 적대적 검증 결과: **조정후 GO**.

**확인됨 (정확한 API)**:
- 스트리밍 멀티턴 입력: `query({ prompt: AsyncIterable<SDKUserMessage>, options })` (v1 안정). 제너레이터가 `await`로 다음 yield를 막아 무기한 대기 가능(문서 예시는 짧은 대기).
- 로컬 플러그인/스킬 로드: `plugins:[{type:'local',path}]` + `settingSources:['user','project']` + `allowedTools:['Skill', ...]` (기본 OFF, 명시 opt-in 필수).
- 세션 resume: `options.resume:'<sessionId>'`, sessionId는 system init 메시지에서 캡처. `forkSession:true` 분기.
- 권한 제어: `canUseTool`, `permissionMode('default'|'acceptEdits'|'bypassPermissions'|'plan')`, `allowedTools`/`disallowedTools`.

**미확인 리스크 (스파이크 대상, §13)**: 무기한 idle 타임아웃 / 교차-프로세스·머신 resume + 크래시 시 펜딩 콜백 재구동 / 고동시성 / 세션 스토어 경로 / superpowers 스킬 자동 트리거 동등성. → §9의 "resume-per-answer + DB 자체 영속 + 단일 호스트 고정"으로 회피 설계.

**인증 (스파이크 00b로 정정)**: 검증 워크플로의 "API 키 전용" 문서 주장은 **SDK-via-CLI 경로에서 정정됨**. 스파이크 00b에서 SDK가 `pathToClaudeCodeExecutable`로 로컬 `claude` CLI 구독을 그대로 사용함을 확인(`apiKeySource: "none"`, API 키 미설정 상태로 정상 응답). 실제 과금은 구독이 커버하고 `total_cost_usd`는 shadow 값. 따라서 인터뷰 엔진은 기존 netisMaker 워커와 동일한 구독 모델을 탄다.

---

## 16. 영향받는 기존 코드 (참고)

- `frontend/pages/tasks/index.vue` — 등록 다이얼로그 확장(분할 뷰)
- `frontend/pages/tasks/[id].vue` — SSE/EventSource 패턴 참조 재사용
- `service/DeployLogStreamService.java` — SSE fan-out 메커니즘 재사용(질문 push)
- `controller/WorkerController.java`, `config/WorkerApiKeyFilter.java` — 워커 claim/인증 패턴 재사용
- `workerdaemon/GitRepoCache.java` — 레포 체크아웃 재사용
- `service/StaleTaskRecoveryJob.java` — stale 회수 패턴 참조
- `entity/TaskAnalysis.java`, `entity/Task.java`, `service/TaskService.java` — 등록(승격) 시 프리필 매핑
- `src/main/resources/db/migration/V11__interview.sql` — 신규 마이그레이션
- **신규**: `InterviewController`/`InterviewService`/`InterviewSession`·`InterviewTurn`·`InterviewPlan` 엔티티 + 리포지토리, SDK 인터뷰 서비스(별도 TS/Node 프로젝트)
