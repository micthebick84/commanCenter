# 질문 세션(Q&A) 기능 설계

2026-08-30 · 브레인스토밍 승인 + 코드 대조 검증(3면) 반영본

## 1. 개요

작업 등록(인터뷰→플랜→구현)과 별개로, 레포에 대해 **질문하고 답변받는** 대화형 세션을 추가한다.

- 절차는 인터뷰와 동일한 기계장치(큐/claim, SSE 스트리밍, 턴 저장, resume)를 재사용하되, **방향이 반대**: 인터뷰에선 Claude가 묻고 사람이 답하지만, 질문 세션에선 사람이 묻고 Claude가 레포를 읽고 답한다.
- superpowers 스킬(brainstorming/writing-plans)은 **사용하지 않는다**.
- 일반 사용자(`ROLE_USER`)와 관리자(`ROLE_ADMIN`) 모두 사용 가능.
- 질문 세션은 **Q&A 전용**: 개발 계획을 작성·진행하는 경로가 존재하지 않아야 하고, 레포에 수정이 발생하지 않도록 다층 방어한다 (§6).

## 2. 확정된 정책 결정

| 결정 | 내용 |
|---|---|
| 시작 게이트 | **등록 즉시 시작** — 승인 없음. 등록하면 바로 QUEUED로 큐 진입 |
| 모델·effort | **등록자가 선택** (`ModelEffortPolicy` 재사용). 추가 MCP도 등록 시 카탈로그에서 선택 |
| 조회 범위 | **본인 + 관리자**. 조작(추가 질문·종료)도 본인+관리자만 |
| base MCP | **주입한다** — 디자인/구현 워커·인터뷰와 완전 패리티(`mcpsBase` + extras 머지) |
| MCP 쓰기 리스크 | **수용 결정**: 레포 무수정 방어는 파일시스템/Bash 도구 계층에서 담보(§6). MCP를 통한 레포 밖 부수효과는 base가 운영자 `~/.claude.json`, extras가 관리자 큐레이션 카탈로그로 통제되는 것을 근거로 수용 |
| 남용 가드 | 사용자당 **활성(QUEUED/RUNNING/AWAITING_INPUT) 질문 세션 상한** (설정키 `netis-maker.question.max-active-per-user`, 기본 3) 초과 등록 시 429 (`TaskException.tooMany` 재사용) |
| 첨부파일 | v1 범위 제외 (YAGNI) |

## 3. 접근: `InterviewSession`에 `kind` 컬럼 (승인된 A안)

별도 엔티티/러너(B안)는 claim 루프·회복 잡·SSE·비용가드 복제가 필요해 과설계, Task 확장(C안)은 구현 파이프라인에 결박돼 부적합. **기존 인터뷰 기계장치를 그대로 재사용하고 `kind`로 분기**한다.

재사용되는 것(변경 없음): 큐/claim 직렬 루프, `InterviewTurn` 저장, SSE 활동 스트림(`InterviewStreamService` + `ActivityPoster`), heartbeat, `InterviewStaleRecoveryJob`(queued TTL·유휴 TTL), `CostGuard`(세션 누적), wall-clock 턴 타임아웃, `ensureRepo`(매 턴 fetch+reset).

**taskId 없는 세션의 안전성 (검증됨)**: 인터뷰의 실패/만료/취소가 Task 상태를 되돌리는 부수효과는 `mirrorTask`가 담당하는데, `taskId == null`이면 조용히 no-op이다(`InterviewService.java:484`). 질문 세션(taskId 항상 null)의 만료·종료·실패는 추가 분기 없이 안전 — 단 §9에 taskId-null 케이스 테스트를 명시적으로 둔다.

유의점: 질문과 인터뷰가 **같은 큐·같은 워커 슬롯을 직렬 공유**한다(설계 제약 P6). v1은 우선순위 없이 FIFO — 질문이 인터뷰 뒤에 대기할 수 있다.

## 4. 데이터 모델 · 상태머신

### Flyway `V20__interview_kind.sql`

```sql
ALTER TABLE com.interview_session
  ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'INTERVIEW';
```

기존 row는 DEFAULT로 안전. 값: `INTERVIEW` | `QUESTION`. (테이블명은 `V11__interview.sql`의 `com.interview_session` 확인됨)

### 엔티티

- **레포 정보는 세션 자체 컬럼**: `InterviewSession`은 이미 `githubRepo`/`githubBranch`/`title`/`description`/`gitUrl`/`repoAlias`/`repoCatalogId`/`mcpsExtra`/`model`/`effort`를 직접 보유한다(Task 경유 아님). 질문 세션은 **`taskId`만 null**이고 나머지는 기존 컬럼 그대로 — V20에 kind 외 추가 컬럼 불필요, claim 응답 전파도 기존 경로 그대로.
- `InterviewSession.kind` 필드 + `createQuestion(...)` 정적 팩토리 — `description` = 질문 본문, 등록자가 고른 model/effort/mcpsExtra 스냅샷(기존 freeze 패턴).
- `InterviewTurn` 스키마 무변경 — assistant 턴의 의미(인터뷰 질문 ↔ Q&A 답변)는 세션 kind로 해석하고 UI 라벨만 분기.
- **`currentPhase`**: 현재 `recordQuestion`이 무조건 `setCurrentPhase("brainstorming")`을 실행하므로(`InterviewService.java:184`), **`kind==QUESTION`이면 세팅 생략하는 분기를 추가**한다. UI도 미표시.

### 상태머신 (enum 추가 없음, kind 가드만)

```
QUESTION: QUEUED(답변 대기) → RUNNING(답변 중) → AWAITING_INPUT(답변 완료)
          ⇄ (추가 질문 = 재큐: AWAITING_INPUT → QUEUED)
          → CANCELLED(종료) | EXPIRED(TTL) | FAILED
```

`PLAN_READY`/`REGISTERED`는 QUESTION에서 **도달 불가**: `recordPlan()`·`confirm()`에 `kind==QUESTION → 400` 가드 (§6-③). 정상 종료도 CANCELLED를 재사용하되 질문 문맥 라벨은 "종료됨"(§7) — 상태 레벨 구분은 v1에서 불필요하다고 판단(턴 기록으로 성공 여부 확인 가능).

## 5. API · 권한

### 신규 `QuestionController` (`/api/questions`, `@Profile("api")`)

클래스 레벨 `@PreAuthorize` 없음 — JWT 인증만(USER+ADMIN 공용). 내부는 `InterviewService` 위임. **`isAdmin`은 기존 `InterviewController`처럼 하드코딩하지 말고 `AuthContext.isAdmin(auth)` 실값을 전달**한다(기존 `requireOwner`의 "소유자" 분기는 프로덕션에서 실행된 적 없는 경로 — §9 ACL 테스트 필수).

| 엔드포인트 | 동작 | ACL | 에러 |
|---|---|---|---|
| `POST /api/questions` | 등록 → 즉시 QUEUED | 인증 사용자 누구나 | 활성 세션 상한 초과 429 |
| `GET /api/questions` | 목록 — 본인 것, 관리자는 `?all=true` 전체 | 본인/관리자 | — |
| `GET /api/questions/{id}` | 상세(턴 포함) — `getForView` 재사용 | 본인+관리자 | 타인 403, 교차 kind 404 |
| `GET /api/questions/{id}/stream` | SSE (기존 `?access_token=` 방식) | 본인+관리자 | 상동 |
| `POST /api/questions/{id}/ask` | 추가 질문 = `submitAnswer` 재사용(재큐) | 본인+관리자 | 턴 상한 400 |
| `POST /api/questions/{id}/close` | 종료 = `cancel` 재사용 → CANCELLED | 본인+관리자 | — |

**응답코드 정책 확정**: 타인 소유 접근 = **403**(기존 `requireOwner`→`TaskException.forbidden` 의미론 유지), kind 불일치(질문 API로 인터뷰 세션 접근 또는 역방향) = **404**(다른 리소스로 취급, 존재 은닉).

**목록은 신규 서비스 메서드**: 기존 `listForTask`는 Task 소유권 기반 ACL이라 taskId-null 세션에 사용 불가. 본인 목록은 기존 `InterviewSessionRepository.findByRequester`(현재 미사용)에 kind 필터를 더해 서비스 래퍼 신설, 관리자 전체 목록(`?all=true`)은 신규 쿼리.

### 턴 상한 (서버에서 우아하게)

- **계수 단위 = assistant 답변 수(Q&A 왕복)** — `recordQuestion`이 답변마다 `role='assistant'` 턴을 저장하므로 러너의 `claim.turns` 필터 카운트와 동일 단위.
- 설정키 `netis-maker.question.max-qa-turns` (기본 10). `ask`가 상한 도달 시 **400**("최대 문답 수 도달 — 새 질문 세션을 열어주세요") → 재큐 거부. 정상 답변한 세션이 FAILED로 끝나는 UX 방지.
- **불변식: 서버 상한 < 러너 `maxTurns`(기본 20)** — 러너 가드는 방어선으로만 남는다. QUESTION 경로에서 러너 fail 사유 문구는 인터뷰 전용("plan 미완성")이 아닌 중립 문구로 분기.

### 워커 계약 (`/worker/interviews/*`)

- `InterviewClaimResponse`(Java record)에 `kind` 추가. `of()` 팩토리 단일 생성 지점이라 변경 국소적. 나머지 계약 무변경.
- **interview-service `types.ts`는 `kind?: 'INTERVIEW' | 'QUESTION'` (optional)** + 러너에서 `kind === 'QUESTION'` 판정(미존재 = INTERVIEW 기본) — `attachments` 선례(구버전 백엔드 호환)와 동일 패턴. `test/fixtures/claims.ts` 3개 fixture 갱신.
- **혼합 버전 창 주의**: 구버전 interview-service가 QUESTION claim을 받으면 인터뷰로 처리해(superpowers 로드, 쓰기 게이트 느슨) §6 방어가 무력화된다. **배포 순서: interview-service 선배포(재기동) 후 API 서버** — `start-all.sh` 일괄 재기동이면 자연 충족되지만 개별 재기동 시 순서 엄수.

## 6. 러너 변경 · 방어 계층 (interview-service)

### kind=QUESTION 러너 동작 (`interviewRunner.ts`) — kind 가드 4곳

QUESTION이면 **무조건 `promptFor`(질문용) → relay → `postQuestion` 직행**. 명시적 가드 지점:

1. **프롬프트 선택 삼항식** (`interviewRunner.ts:230-237`): forceFinish 우회 경로(`buildPlanReformatSplice`)에 진입 금지 — 안 막으면 19번째 턴부터 사용자 질문 대신 plan 강제 프롬프트가 나간다.
2. **handoff splice** (`:251-280`): `detectHandoff` 결과 무관하게 미진입.
3. **near-miss reformat** (`:285-311`): `detectPlanIntent` 무관하게 미진입.
4. **harvest→postPlan** (`:282, :315-325`): `tryHarvest` 자체를 건너뜀 — 모델이 "# … 구현 계획" 형식 텍스트로 답해도 그냥 답변 텍스트로 `postQuestion`. ("postPlan 경로가 없다"는 이 가드를 넣어야 성립)

킥오프 프롬프트(스킬 언급 없음): cwd의 레포 체크아웃을 읽고(Read/Grep/Glob/읽기전용 Bash + MCP) 질문에 한국어로 답하라. 근거는 `파일:라인`, 모르면 모른다고. 답변은 마크다운. + §6-④ 계약 문구. 후속 질문은 기존 resume + `lastAnswer` 주입 그대로.

### "질문만 가능, 레포 무수정" 5계층 방어

1. **도구 게이트 default-deny 전환** (핵심). 현행 인터뷰 게이트는 Write/Edit/MultiEdit을 `docs/superpowers/**`로 경로 제한 + Bash 화이트리스트, **나머지(NotebookEdit 포함)는 allow**. 질문 세션은 반대로 **허용 목록 외 전부 deny**:
   - 허용: `Read`, `Grep`, `Glob`, 읽기전용 Bash — 허용 명령은 기존 인터뷰 화이트리스트와 동일: `git status|log|diff|show|branch`, `ls`, `cat`, `grep`, `rg`, `find`, `head`, `tail`, `wc`, `pwd` (셸 메타문자 차단 그대로) — 그리고 `mcp__*`(base+extras)
   - 거부: `Write`/`Edit`/`MultiEdit`/`NotebookEdit` 및 이름 모를 미래 도구 전부
   - `allowedTools` 사전승인은 `['Read','Grep','Glob']`만: `Skill` 제외(superpowers 미로드와 한 쌍), `mcp__` 와일드카드 미등재 → MCP 호출까지 canUseTool **단일 관문** 경유
   - `plugins: []` — superpowers 플러그인 자체를 로드하지 않음
   - 구현: `buildOptions`에 `sessionKind` 추가(단일 input 객체라 비파괴적) + **`buildCanUseTool` 시그니처도 kind 인자(또는 별도 `buildQuestionGate`)로 변경**
   - ⚠️ "미등재 `mcp__*` 호출이 canUseTool에 도달한다"는 주석·테스트로만 문서화된 전제 — **구현 전 미니 스파이크 1건으로 실증** (§9)
2. **plan 경로 미진입**: 위 kind 가드 4곳으로 `postPlan` 호출 경로 차단.
3. **서버 상태머신 가드**: `recordPlan`·`confirm`이 QUESTION에 400 — 만에 하나 뚫려도 작업 생성·구현 큐 진입이 DB 레벨에서 불가능.
4. **프롬프트 계약**: "이 세션은 Q&A 전용. 구현 계획 작성·작업 등록·코드 수정은 불가능하며, 그런 요청을 받으면 작업 등록(인터뷰) 기능을 안내하라"를 킥오프에 명시.
5. **체크아웃 자기복원(보조)**: 매 턴 전 `ensureRepo`의 `fetch --depth 1` + `reset --hard`가 **tracked 파일 변경을 원복**한다(`repoPrepare.ts:41-44`). untracked 신규 파일은 reset이 못 지우지만, QUESTION은 1계층에서 Write가 전면 deny라 생성 경로 자체가 막힌다 — 1계층이 실질 차단선, 5계층은 보조. push는 Bash 화이트리스트에 없어 원격은 애초에 안전.

## 7. 프론트엔드

- **네비게이션**: `layouts/default.vue`에 "질문" 탭 — 모든 인증 사용자 노출.
- **`/questions` 목록**: 내 질문 세션(상태·비용·시각), 관리자 "전체 보기" 토글. "질문하기" 다이얼로그는 **합성**: 레포 카탈로그 셀렉트·브랜치 자동 동기화·제목·본문 입력부는 작업 등록 다이얼로그(`pages/tasks/index.vue`) 패턴, **모델/effort 셀렉트 + `McpPicker`는 승인 다이얼로그(`ApproveDialog.vue`)에서 가져온다**(현행 등록 다이얼로그에는 두 피커가 없음 — 인터뷰에선 관리자가 승인 시 결정하기 때문). 등록 즉시 상세 이동.
- **`/questions/[id]` 대화**: `InterviewPanel`에 `kind` prop 추가(기본 `'INTERVIEW'` — 기존 사용처 무변경):
  - **API 분기 지점 5곳**: SSE 1곳(`useInterviewStream.ts:92` — basePath 인자화) + `InterviewPanel.vue` REST 4곳 — 액션 경로 매핑 명시: answer POST(`:108`)→`/api/questions/{id}/ask`, cancel POST(`:160`)→`/api/questions/{id}/close`, 스냅샷 GET(`:182`)→`/api/questions/{id}`, confirm POST(`:142`)→QUESTION에서 미사용.
  - **우측 "설계·플랜" 컬럼 전체 + 좁은 화면 "설계·플랜" 탭을 kind로 미렌더**(confirm 버튼·designRequested 토글은 그 안에 포함) — 대화 컬럼 단독 풀폭. QUESTION엔 design/plan 이벤트가 오지 않아 빈 패널로 남기 때문.
  - 버튼/문구 분기(현행 라벨 기준): "전송"→"추가 질문", "대화 취소"→"세션 종료", 입력 placeholder "답변을 입력하세요…"→"추가 질문을 입력하세요…".
  - 활동 버블(도구/thinking 스트리밍)은 kind 무관 그대로 재사용.
- **상태 라벨** (`interviewLabels.ts` — 프론트 분기 키는 **영문 enum명**, SSE는 영문 enum명만 push; "DB 한글값"은 DB 계층 얘기): `interviewStatusLabel`/`interviewStatusChip` 헬퍼에 kind 파라미터 추가(소비처 `InterviewHistoryCard`/`InterviewHistoryDialog` 포함 스레딩).

| enum | 인터뷰 라벨(현행) | 질문 라벨 |
|---|---|---|
| QUEUED | 대기 중 | 답변 대기중 |
| RUNNING | 분석 중 | 답변 중 |
| AWAITING_INPUT | 입력 대기 | 답변 완료 |
| CANCELLED | 취소됨 | 종료됨 |
| EXPIRED | 만료됨 | 만료됨 |
| FAILED | 실패 | 실패 |

## 8. 만료 · 에러 처리

- queued TTL·유휴 TTL: `InterviewStaleRecoveryJob` 공유. taskId-null이라 Task 부수효과 없음(§3, mirrorTask no-op). 만료돼도 턴 기록 보존, 읽기 전용 열람.
- FAILED: 러너 fail 사유(QUESTION용 중립 문구)를 상세 화면에 표시. v1 retry 없음 — 새 세션으로 재질문.
- 턴 상한 400 / 활성 세션 상한 429: 프론트 토스트 안내.
- 비용: `total_cost_usd` 누적 칩 그대로.

## 9. 테스트

- **사전 스파이크 1건**: `allowedTools` 미등재 `mcp__*` 도구 호출이 실제로 canUseTool 콜백에 도달하는지 실증(§6-① 단일 관문 전제 — 주석/테스트로만 문서화돼 있고 스파이크 기록 없음).
- **Java**: kind 가드(`recordPlan`/`confirm` → 400), `createQuestion`(+활성 세션 상한 429), 목록 ACL(본인 OK·관리자 OK·타인 403), 교차 kind 404, `ask` 턴 상한 400, `recordQuestion`의 currentPhase 미세팅(QUESTION), claim 응답 `kind` 포함, **taskId-null 세션의 만료/종료/실패가 Task에 무영향** — 기존 Testcontainers 게이트 편승.
- **interview-service (vitest)**: default-deny 게이트(Write/Edit/NotebookEdit/미지 도구 deny, `mcp__*` allow, allowedTools/plugins 구성), 러너 kind 가드 4곳(특히 forceFinish 턴수에서 QUESTION이 reformat 프롬프트를 받지 않음, plan 형식 답변이 와도 `postQuestion`), `kind` optional 판정(미존재=INTERVIEW), fixture 갱신, 기존 인터뷰 경로 회귀 없음.
- **frontend (vitest)**: `InterviewPanel` kind 분기(설계·플랜 컬럼/탭 미렌더, 액션 경로 매핑, 버튼 라벨), 라벨 헬퍼 kind 분기.
- **머지 전 수동 스모크**: 등록 → 스트리밍 → 답변 → 추가 질문 → 종료 E2E 1건 (+ 일반 사용자 계정으로 타인 세션 403 확인).

## 10. 범위 제외 (v1)

- 질문 등록 첨부파일
- 세션 retry
- 큐 우선순위(질문 vs 인터뷰)
- 전체 공개 조회(지식공유 모드)
- MCP 도구 read-only 스크리닝(카탈로그 플래그) — §2 리스크 수용 결정 참조, 후속 과제
