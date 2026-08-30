# 질문 세션(Q&A) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 작업 등록(인터뷰→플랜→구현)과 별개로, 사용자·관리자 누구나 레포에 대해 질문하고 Claude가 레포를 읽어 답하는 Q&A 세션을 추가한다 — 플랜 작성·작업 등록·레포 수정 경로가 구조적으로 없는 채로.

**Architecture:** `InterviewSession`에 `kind`(INTERVIEW|QUESTION) 컬럼을 추가해 기존 인터뷰 기계장치(큐/claim, SSE, 턴 저장, 회복 잡, 비용가드)를 그대로 재사용한다. Java는 `/api/questions` 컨트롤러 + `QuestionService`(InterviewService 위임 + kind 가드)를 신설하고, interview-service 러너는 `kind=QUESTION`이면 superpowers 미로드·default-deny 도구 게이트·plan 경로 미진입의 조기 분기를 탄다. 프론트는 `InterviewPanel`에 `kind` prop을 더해 재사용한다.

**Tech Stack:** Java 21 / Spring Boot 3.4 / JPA / Flyway / Testcontainers · TypeScript / Node 20 / `@anthropic-ai/claude-agent-sdk` 0.2.117 / vitest · Nuxt 3 / Quasar / vitest + happy-dom

**Spec:** `docs/superpowers/specs/2026-08-30-question-sessions-design.md`

## Global Constraints

- 저장소 루트: `/Users/micthebick/IdeaProjects/netisMaker`. 아래 경로는 전부 이 루트 기준. 브랜치: `feature/question-sessions` (main에서 분기, 워크트리 권장).
- **배포 순서(스펙 §5)**: interview-service 선배포(재기동) → API 서버. 구버전 러너가 QUESTION claim을 받으면 인터뷰로 처리해 방어가 무력화된다.
- **불변식(스펙 §5)**: 서버 `app.question.max-qa-turns`(기본 10) < 러너 `INTERVIEW_MAX_TURNS`(기본 20).
- **응답코드(스펙 §5)**: 타인 소유 접근 403, kind 불일치 404, 턴 상한 400, 활성 세션 상한 429.
- **Bash 허용 명령(스펙 §6-①)**: 기존 인터뷰 화이트리스트와 동일 — `git status|log|diff|show|branch`, `ls`, `cat`, `grep`, `rg`, `find`, `head`, `tail`, `wc`, `pwd`. 셸 메타문자 차단 유지. 이 목록은 변경하지 않는다.
- **Java 스타일**: Lombok, 메시지 한국어, 400은 `new TaskException(HttpStatus.BAD_REQUEST, "...")` 직접 생성(팩토리 없음), 429는 `TaskException.tooManyRequests(String)`, 404는 `TaskException.notFound()`, 403은 `TaskException.forbidden()`.
- **Java 설정 프리픽스**: API 프로파일은 `app.*` (`application.yml`의 `app:` 블록) + `@Value` 주입. `netis-maker.*`는 워커 프로파일 전용이라 쓰지 않는다.
- **Java 테스트**: 통합 테스트는 `RUN_TESTCONTAINERS=true` env 없으면 skip된다(반드시 붙여 실행). 시드 사용자는 `user1`, `user2`, `admin1`, `admin`만 가능(`requester_id` FK). 레포 카탈로그 시드 id=1 (`Netis7.0`).
- **interview-service 스타일**: 상대 임포트는 `.js` 확장자 필수(NodeNext), `strict` + `noUnusedLocals` + `noUnusedParameters` + `noUncheckedIndexedAccess`. 타입체크는 `npm run build`(테스트 파일 포함), 테스트는 `npm test`.
- **frontend 스타일**: 작은따옴표 + **세미콜론 없음** + 2-space + trailing comma. `npm run lint-prettier`를 전체에 돌리지 말 것(단일 파일만). 테스트: `npx vitest run <file>`.
- **커밋 메시지**: 한국어, `feat:`/`test:`/`docs:` prefix. 각 태스크 끝에 커밋.

---

## File Structure

**Java (`src/main/java/com/hamonsoft/netismaker/`)**
- Create `entity/InterviewKind.java` — enum INTERVIEW|QUESTION.
- Modify `entity/InterviewSession.java` — `kind` 컬럼 + `createQuestion()` + `isQuestion()`.
- Create `src/main/resources/db/migration/V20__interview_kind.sql`.
- Modify `dto/InterviewClaimResponse.java` — `kind` 컴포넌트(워커 계약).
- Modify `dto/InterviewResponse.java` — `kind`, `totalCostUsd` 추가(상세 뷰).
- Create `dto/QuestionCreateRequest.java`, `dto/QuestionSummaryResponse.java`.
- Modify `service/InterviewService.java` — `recordQuestion` currentPhase 분기, `recordPlan`/`confirm` 400 가드, `requireKind()`.
- Modify `controller/InterviewController.java` — 각 엔드포인트 첫 줄 `requireKind(id, INTERVIEW)`.
- Modify `repository/InterviewSessionRepository.java`, `repository/InterviewTurnRepository.java` — 목록/카운트 쿼리.
- Create `service/QuestionService.java` — 등록(429)·목록·조회·ask(400)·close, 전부 kind 가드 후 InterviewService 위임.
- Create `controller/QuestionController.java` — `/api/questions/*`.
- Modify `src/main/resources/application.yml` — `app.question.*`.

**interview-service (`netismaker-interview-service/`)**
- Create `scripts/spikeMcpGate.ts` — 스파이크(미등재 `mcp__` 도구 → canUseTool 도달 실증).
- Modify `src/types.ts` — `SessionKind`, `InterviewClaimResponse.kind?`.
- Modify `src/sdk/permissions.ts` — `buildCanUseTool(repoDir, kind)` default-deny 분기.
- Modify `src/sdk/sessionOptions.ts` — `sessionKind` 입력, plugins/allowedTools 분기.
- Modify `src/runner/interviewRunner.ts` — `questionPromptFor`, `runQuestionTurn` 조기 분기, 중립 fail 문구.
- Modify `test/fixtures/claims.ts` — `questionClaim`.

**frontend (`frontend/`)**
- Modify `composables/interviewLabels.ts` — `SessionKind`, `QUESTION_STATUS_LABELS`, `interviewStatusLabel(name, kind)`.
- Modify `composables/useInterviewStream.ts` — `open(id, apiBase)`.
- Modify `components/InterviewPanel.vue` — `kind` prop, API 경로 5곳 분기, 설계·플랜 컬럼/탭 미렌더, 문구 분기.
- Create `pages/questions/index.vue` — 목록 + 질문하기 다이얼로그.
- Create `pages/questions/[id].vue` — 상세(InterviewPanel kind=QUESTION).
- Modify `layouts/default.vue` — "질문" 탭.

**Docs**
- Modify `CLAUDE.md`(netisMaker) — 질문 세션 상태머신·설정키·파일 표.

---

### Task 1: 스파이크 — 미등재 `mcp__` 도구 호출이 canUseTool에 도달하는지 실증

스펙 §6-①의 "단일 관문" 전제(allowedTools에 `mcp__` 와일드카드를 안 넣으면 MCP 호출이 canUseTool을 경유한다)는 주석·단위테스트로만 문서화돼 있다. 실물로 확인한 뒤 Task 6으로 간다. **미도달이면 Task 6 이후를 진행하지 말고 사용자에게 보고한다.**

**Files:**
- Create: `netismaker-interview-service/scripts/spikeMcpGate.ts`

**Interfaces:**
- Consumes: `realQuery`(`src/sdk/sdkAdapter.ts`), `resolveClaudeCli`(`src/sdk/claudeCli.ts`), `loadBaseMcpServers`(`src/sdk/mcpBase.ts`) — 모두 기존.
- Produces: 판정 결과(stdout `RESULT:` 줄 + exit code). 코드 산출물 없음(스크립트는 기록용으로 커밋).

- [ ] **Step 1: 스파이크 스크립트 작성**

```ts
/**
 * 스파이크 — allowedTools에 미등재된 mcp__ 도구 호출이 canUseTool 콜백에 도달하는지 실증.
 * (스펙 2026-08-30-question-sessions-design §6-① "단일 관문" 전제 — 주석/단위테스트로만 문서화돼 있어 실물 확인.)
 * 실행(운영자 macOS, claude 구독 로그인 + ~/.claude.json에 MCP 서버 1개 이상 필요):
 *   cd netismaker-interview-service && set -a && source .env 2>/dev/null; set +a
 *   node --import tsx scripts/spikeMcpGate.ts
 * 판정:
 *   stdout에 `[gate] toolName=mcp__...` 가 찍히고 마지막 줄이 `RESULT: 도달` → 전제 성립(exit 0).
 *   `RESULT: 미도달`(exit 1) → SDK가 콜백 없이 MCP를 자동 승인한다는 뜻. 계획 Task 6 이후 중단, 보고.
 * canUseTool은 모든 도구를 deny하므로 부수효과 없음(MCP 도구가 실제로 실행되지 않는다).
 */
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';
import { loadBaseMcpServers } from '../src/sdk/mcpBase.js';

const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);
const mcpServers = loadBaseMcpServers();
const names = Object.keys(mcpServers);
if (names.length === 0) {
  console.error('~/.claude.json에 MCP 서버가 없어 스파이크를 실행할 수 없습니다');
  process.exit(2);
}

const seen: string[] = [];

async function* prompt(): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield {
    type: 'user',
    message: {
      role: 'user',
      content:
        `연결된 MCP 서버(${names.join(', ')}) 중 하나의 도구를 반드시 정확히 1회 호출해 보세요. ` +
        '예: local-db가 있으면 query 도구로 "SELECT 1", obsidian 계열이면 목록 조회 도구. ' +
        '호출이 거부되더라도 다시 시도하지 말고, 결과(거부 포함)를 한 줄로 보고하세요.',
    },
  };
}

async function run(): Promise<void> {
  const stream = realQuery({
    prompt: prompt(),
    options: {
      pathToClaudeCodeExecutable: claudeCliPath,
      plugins: [],
      allowedTools: ['Read', 'Grep', 'Glob'], // mcp__ 와일드카드 의도적 미등재 (QUESTION 구성과 동일)
      cwd: process.cwd(),
      permissionMode: 'default',
      mcpServers,
      canUseTool: async (toolName: string) => {
        seen.push(toolName);
        console.log(`[gate] toolName=${toolName}`);
        return { behavior: 'deny' as const, message: 'spike: 모든 도구 거부' };
      },
    },
  });
  for await (const msg of stream) {
    const m = msg as { type: string; message?: { content?: unknown } };
    if (m.type === 'assistant') console.log(`[assistant] ${JSON.stringify(m.message?.content)}`);
  }
  const hit = seen.some((n) => n.startsWith('mcp__'));
  console.log(hit ? 'RESULT: 도달 — mcp__ 도구가 canUseTool을 경유한다 (스펙 §6-① 전제 성립)' : 'RESULT: 미도달 — 계획 중단, 보고');
  process.exit(hit ? 0 : 1);
}

void run();
```

- [ ] **Step 2: 실행**

Run:
```bash
cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service
set -a && source .env 2>/dev/null; set +a
node --import tsx scripts/spikeMcpGate.ts
```
Expected: `[gate] toolName=mcp__<server>__<tool>` 1줄 이상 + `RESULT: 도달` + exit 0.

- [ ] **Step 3: 판정 기록**

`RESULT: 도달`이면 스크립트 상단 주석 끝에 `실측: 2026-08-30 도달 확인 (tool=<찍힌 이름>)` 한 줄을 추가한다. 미도달이면 **여기서 멈추고** 사용자에게 보고(대안: `disallowedTools`에 쓰기형 MCP 도구명을 열거하거나 QUESTION에서 MCP 미주입 — 사용자 결정 필요).

- [ ] **Step 4: 커밋**

```bash
git add netismaker-interview-service/scripts/spikeMcpGate.ts
git commit -m "chore: 스파이크 — 미등재 mcp__ 도구의 canUseTool 도달 실증 스크립트"
```

---

### Task 2: Java 스키마·엔티티·DTO — `kind` 컬럼과 워커/상세 계약

**Files:**
- Create: `src/main/resources/db/migration/V20__interview_kind.sql`
- Create: `src/main/java/com/hamonsoft/netismaker/entity/InterviewKind.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java` (status 필드 아래, `create()` 아래)
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java`
- Test: `src/test/java/com/hamonsoft/netismaker/entity/InterviewSessionTest.java`, `src/test/java/com/hamonsoft/netismaker/dto/InterviewClaimResponseTest.java`, `src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java`

**Interfaces:**
- Produces: `enum InterviewKind { INTERVIEW, QUESTION }`; `InterviewSession.getKind()`, `InterviewSession.isQuestion()`, `static InterviewSession createQuestion(String githubRepo, String githubBranch, String title, String question, String requesterId, List<TaskMcpSpec> mcpsExtra, String model, String effort)`; `InterviewClaimResponse.kind()` (String, enum 이름); `InterviewResponse.kind()` (String), `InterviewResponse.totalCostUsd()` (BigDecimal).

- [ ] **Step 1: 실패하는 엔티티 테스트 작성** — `InterviewSessionTest.java`에 추가

```java
    @Test
    void create_defaults_kind_to_interview() {
        InterviewSession s = InterviewSession.create("o/r", "main", "t", "d", "u1", List.of(), "claude-opus-4-8", "high");
        assertThat(s.getKind()).isEqualTo(InterviewKind.INTERVIEW);
        assertThat(s.isQuestion()).isFalse();
    }

    @Test
    void createQuestion_sets_kind_question_keeps_other_columns_and_no_task() {
        InterviewSession s = InterviewSession.createQuestion(
                "owner/repo", "", "인증 흐름", "로그인은 어디서 처리되나요?", "user1",
                List.of(new TaskMcpSpec("ctx7", "https://x", "http")), "claude-sonnet-5", "medium");
        assertThat(s.getKind()).isEqualTo(InterviewKind.QUESTION);
        assertThat(s.isQuestion()).isTrue();
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getGithubBranch()).isEqualTo("main");        // blank → main (create와 동일)
        assertThat(s.getDescription()).isEqualTo("로그인은 어디서 처리되나요?"); // description = 질문 본문
        assertThat(s.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(s.getEffort()).isEqualTo("medium");
        assertThat(s.getMcpsExtra()).hasSize(1);
        assertThat(s.getTaskId()).isNull();
        assertThat(s.getCurrentPhase()).isNull();
    }
```

- [ ] **Step 2: 실패하는 DTO 테스트 작성**

`InterviewClaimResponseTest.java`에 추가 (기존 테스트가 세션을 만드는 방식과 무관하게 자급자족):
```java
    @Test
    void of_carries_session_kind_as_enum_name() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        InterviewClaimResponse r = InterviewClaimResponse.of(q, List.of(), List.of());
        assertThat(r.kind()).isEqualTo("QUESTION");

        InterviewSession i = InterviewSession.create("o/r", "main", "t", "d", "user1",
                List.of(), "claude-opus-5", "high");
        assertThat(InterviewClaimResponse.of(i, List.of(), List.of()).kind()).isEqualTo("INTERVIEW");
    }
```
(필요 import: `com.hamonsoft.netismaker.entity.InterviewSession`, `java.util.List` — 파일에 이미 있으면 생략.)

`InterviewResponseTest.java`에 추가:
```java
    @Test
    void of_carries_kind_and_total_cost() {
        InterviewSession q = InterviewSession.createQuestion("o/r", "main", "t", "q?", "user1",
                List.of(), "claude-opus-5", "high");
        q.setTotalCostUsd(new java.math.BigDecimal("1.25"));
        InterviewResponse r = InterviewResponse.of(q, List.of(), null);
        assertThat(r.kind()).isEqualTo("QUESTION");
        assertThat(r.totalCostUsd()).isEqualByComparingTo("1.25");
    }
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd /Users/micthebick/IdeaProjects/netisMaker && ./gradlew test --tests '*InterviewSessionTest' --tests '*InterviewClaimResponseTest' --tests '*InterviewResponseTest'`
Expected: 컴파일 에러 (`InterviewKind`/`createQuestion`/`kind()` 없음).

- [ ] **Step 4: Flyway V20 작성** — `V20__interview_kind.sql`

```sql
-- V20: 질문 세션(Q&A) — 인터뷰 세션 테이블을 kind 컬럼으로 공유한다
-- (docs/superpowers/specs/2026-08-30-question-sessions-design.md §4).
-- 기존 row는 DEFAULT 'INTERVIEW'로 안전. 값: INTERVIEW | QUESTION (Java enum 이름 그대로).
ALTER TABLE com.interview_session
    ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'INTERVIEW';

-- 질문 목록(본인/전체, 최신순) 조회 인덱스.
CREATE INDEX IF NOT EXISTS idx_interview_session_kind_requester
    ON com.interview_session(kind, requester_id, created_at DESC);
```

- [ ] **Step 5: `InterviewKind` enum 생성**

```java
package com.hamonsoft.netismaker.entity;

/**
 * 세션 종류. INTERVIEW = 작업 등록용 플랜 인터뷰(기존), QUESTION = Q&A 전용 —
 * 플랜/등록 전이가 불가능하고 인터뷰 서비스가 읽기 전용 Q&A 모드로 실행한다.
 * DB에는 enum 이름 그대로 VARCHAR(20) 저장 (V20).
 */
public enum InterviewKind {
    INTERVIEW,
    QUESTION
}
```

- [ ] **Step 6: `InterviewSession` 수정**

`status` 필드 선언 바로 아래에 추가:
```java
    /** 세션 종류. QUESTION은 플랜/등록 전이 불가, 인터뷰 서비스가 Q&A 모드로 실행 (스펙 2026-08-30 §4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private InterviewKind kind = InterviewKind.INTERVIEW;
```
`create(...)` 메서드 바로 아래에 추가 (`create()`는 `new InterviewSession()`을 쓰므로 필드 초기자 INTERVIEW가 기본):
```java
    /**
     * 질문 세션(Q&A). taskId 없음, description = 질문 본문. 나머지 컬럼은 인터뷰와 의미 동일
     * (레포/브랜치/모델/effort/MCP 스냅샷) — claim 응답 전파 경로도 그대로.
     */
    public static InterviewSession createQuestion(String githubRepo, String githubBranch, String title,
                                                  String question, String requesterId,
                                                  List<TaskMcpSpec> mcpsExtra, String model, String effort) {
        InterviewSession s = create(githubRepo, githubBranch, title, question, requesterId,
                mcpsExtra, model, effort);
        s.kind = InterviewKind.QUESTION;
        return s;
    }

    public boolean isQuestion() {
        return kind == InterviewKind.QUESTION;
    }
```
(`jakarta.persistence.*` 임포트가 이미 있어 `Enumerated`/`EnumType` 추가 임포트 불필요.)

- [ ] **Step 7: `InterviewClaimResponse`에 `kind` 추가**

record 컴포넌트 목록 마지막 `List<AttachmentRef> attachments` 뒤에 추가:
```java
        List<AttachmentRef> attachments,
        /** 세션 종류 'INTERVIEW' | 'QUESTION' (InterviewKind.name()). 인터뷰 서비스가 Q&A 모드 판정에 사용. */
        String kind
```
`of()`의 `return new InterviewClaimResponse(...)` 마지막 인자 `attachments` 뒤에 `, s.getKind().name()` 추가.

- [ ] **Step 8: `InterviewResponse`에 `kind`, `totalCostUsd` 추가**

record 컴포넌트 `OffsetDateTime updatedAt` 뒤에:
```java
        OffsetDateTime updatedAt,
        /** 'INTERVIEW' | 'QUESTION' — 프론트가 질문 세션 UI 분기에 사용. */
        String kind,
        /** 세션 누적 SHADOW 비용(상세 헤더 칩). */
        BigDecimal totalCostUsd
```
`of()`의 `return new InterviewResponse(... s.getCreatedAt(), s.getUpdatedAt())` → `..., s.getUpdatedAt(), s.getKind().name(), s.getTotalCostUsd())`. 임포트 `java.math.BigDecimal` 추가.

- [ ] **Step 9: 단위 테스트 통과 확인**

Run: `./gradlew test --tests '*InterviewSessionTest' --tests '*InterviewClaimResponseTest' --tests '*InterviewResponseTest'`
Expected: PASS.

- [ ] **Step 10: 부팅(ddl validate + Flyway V20) 확인 — 기존 통합 테스트 1개로**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewWorkerApiIntegrationTest'`
Expected: PASS (컨텍스트 기동 = V20 적용 + `kind` 컬럼 validate 통과).

- [ ] **Step 11: 커밋**

```bash
git add src/main/resources/db/migration/V20__interview_kind.sql src/main/java/com/hamonsoft/netismaker/entity/InterviewKind.java src/main/java/com/hamonsoft/netismaker/entity/InterviewSession.java src/main/java/com/hamonsoft/netismaker/dto/InterviewClaimResponse.java src/main/java/com/hamonsoft/netismaker/dto/InterviewResponse.java src/test/java/com/hamonsoft/netismaker/entity/InterviewSessionTest.java src/test/java/com/hamonsoft/netismaker/dto/InterviewClaimResponseTest.java src/test/java/com/hamonsoft/netismaker/dto/InterviewResponseTest.java
git commit -m "feat: interview_session.kind 컬럼(V20) + createQuestion 팩토리 + claim/상세 응답에 kind 전파"
```

---

### Task 3: `InterviewService` kind 가드 + `InterviewController` 교차 kind 404

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/service/InterviewService.java` (`recordQuestion` :184 부근, `recordPlan` 진입부, `confirm` 진입부, private 헬퍼 영역)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java` (get/stream/answer/confirm/cancel 첫 줄)
- Test: `src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java`

**Interfaces:**
- Consumes: `InterviewSession.isQuestion()`, `InterviewKind` (Task 2).
- Produces: `public InterviewSession requireKind(Long id, InterviewKind kind)` — kind 불일치 시 `TaskException.notFound()`. Task 4의 `QuestionService`가 사용.

- [ ] **Step 1: 실패하는 단위 테스트 작성** — `InterviewServiceTest.java`

기존 `session(long, InterviewStatus)` 헬퍼 옆에 추가:
```java
    private InterviewSession questionSession(long id, InterviewStatus status) {
        InterviewSession s = InterviewSession.createQuestion("owner/repo", "main", "T", "q?", "u1",
                List.of(), "claude-opus-4-8", "high");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(status);
        return s;
    }
```
테스트 추가:
```java
    @Test
    void recordQuestion_on_question_session_does_not_set_current_phase_and_touches_no_task() {
        InterviewSession s = questionSession(5L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(5L)).thenReturn(Optional.of(s));
        when(turnRepo.findMaxSeq(5L)).thenReturn(null);
        service.recordQuestion(5L, "w1", new WorkerQuestionRequest("답변입니다", "sess-q", "question",
                new BigDecimal("0.01")));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.AWAITING_INPUT);
        assertThat(s.getCurrentPhase()).isNull();
        assertThat(s.getClaudeSessionId()).isEqualTo("sess-q");
        verify(taskRepo, never()).findActiveByIdForUpdate(any()); // taskId null → mirrorTask no-op
    }

    @Test
    void recordPlan_on_question_session_throws_400_and_keeps_running() {
        InterviewSession s = questionSession(6L, InterviewStatus.RUNNING);
        s.setWorkerId("w1");
        when(sessionRepo.findByIdForUpdate(6L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.recordPlan(6L, "w1",
                new WorkerPlanRequest("# 설계", "# 플랜", "[]", BigDecimal.ONE, 1L)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.RUNNING);
        verify(planRepo, never()).save(any());
    }

    @Test
    void confirm_on_question_session_throws_400_regardless_of_status() {
        InterviewSession s = questionSession(7L, InterviewStatus.PLAN_READY);
        when(sessionRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.confirm(7L, "admin", false))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(taskRepo, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void requireKind_returns_session_on_match_and_404_on_mismatch() {
        InterviewSession s = questionSession(8L, InterviewStatus.QUEUED);
        when(sessionRepo.findActiveById(8L)).thenReturn(Optional.of(s));
        assertThat(service.requireKind(8L, InterviewKind.QUESTION)).isSameAs(s);
        assertThatThrownBy(() -> service.requireKind(8L, InterviewKind.INTERVIEW))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
```
필요 import(없으면 추가): `com.hamonsoft.netismaker.entity.InterviewKind`, `org.springframework.http.HttpStatus`, `static org.assertj.core.api.Assertions.assertThatThrownBy`, `static org.mockito.Mockito.never`, `static org.mockito.Mockito.verify`, `static org.mockito.ArgumentMatchers.any`.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*InterviewServiceTest'`
Expected: 컴파일 에러 (`requireKind` 없음).

- [ ] **Step 3: `InterviewService` 수정**

(a) `recordQuestion` 안의 `s.setCurrentPhase("brainstorming");` 를 다음으로 교체:
```java
        // 질문 세션은 phase 개념이 없다 (스펙 §4) — brainstorming 표기를 남기지 않는다.
        if (!s.isQuestion()) s.setCurrentPhase("brainstorming");
```
(b) `recordPlan`의 `InterviewSession s = requireSessionForUpdate(sessionId);` 바로 다음 줄에 `requireNotQuestion(s, "플랜 보고");`
(c) `confirm`의 `InterviewSession s = requireSessionForUpdate(sessionId);` 바로 다음 줄에 `requireNotQuestion(s, "확정");`
(d) `requireOwner` 위에 헬퍼 2개 추가:
```java
    /** 질문 세션은 플랜/등록 경로 진입 불가 (스펙 §6-③). 상태와 무관하게 400. */
    private void requireNotQuestion(InterviewSession s, String action) {
        if (s.isQuestion()) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "질문 세션에서는 " + action + "이(가) 불가능합니다");
        }
    }

    /**
     * 컨트롤러 교차 kind 가드 — 다른 kind의 세션은 다른 리소스로 취급한다(404, 스펙 §5).
     * /api/interviews/*는 INTERVIEW만, /api/questions/*는 QUESTION만 조작 가능.
     */
    @Transactional(readOnly = true)
    public InterviewSession requireKind(Long id, InterviewKind kind) {
        InterviewSession s = sessionRepo.findActiveById(id).orElseThrow(TaskException::notFound);
        if (s.getKind() != kind) throw TaskException.notFound();
        return s;
    }
```
임포트 추가: `com.hamonsoft.netismaker.entity.InterviewKind`, `org.springframework.http.HttpStatus`(없으면).

- [ ] **Step 4: `InterviewController` 수정**

`get`, `stream`, `answer`, `confirm`, `cancel` 각 메서드 본문 첫 줄에:
```java
        interviewService.requireKind(id, InterviewKind.INTERVIEW); // 질문 세션은 이 API로 조작 불가 (404)
```
임포트 `com.hamonsoft.netismaker.entity.InterviewKind` 추가. 클래스 javadoc ACL 문단 끝에 한 줄: `kind=QUESTION 세션은 /api/questions로만 접근 가능 — 여기서는 404.`

- [ ] **Step 5: 테스트 통과 + 회귀 확인**

Run: `./gradlew test --tests '*InterviewServiceTest' --tests '*InterviewConfirmContractTest' --tests '*InterviewTaskMirrorTest'`
Expected: PASS.
Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*InterviewApiIntegrationTest'`
Expected: PASS (기존 인터뷰 API에 requireKind 추가돼도 INTERVIEW 세션이라 무영향).

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/service/InterviewService.java src/main/java/com/hamonsoft/netismaker/controller/InterviewController.java src/test/java/com/hamonsoft/netismaker/service/InterviewServiceTest.java
git commit -m "feat: 질문 세션 kind 가드 — recordPlan/confirm 400, currentPhase 미세팅, 교차 kind 404"
```

---

### Task 4: `QuestionService` + 레포 쿼리 + DTO + 설정

**Files:**
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/repository/InterviewTurnRepository.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/QuestionCreateRequest.java`
- Create: `src/main/java/com/hamonsoft/netismaker/dto/QuestionSummaryResponse.java`
- Create: `src/main/java/com/hamonsoft/netismaker/service/QuestionService.java`
- Modify: `src/main/resources/application.yml` (`app.interview:` 블록 아래 형제로 `app.question:`)
- Test: `src/test/java/com/hamonsoft/netismaker/service/QuestionServiceTest.java`

**Interfaces:**
- Consumes: `InterviewService.requireKind/getResponse/getForView/submitAnswer/cancel`, `RepoCatalogService.resolveForRegistration(Long) → ResolvedRepo(catalogId, alias, gitUrl, host, ownerRepo, defaultBranch)`, `McpCatalogService.resolveByIds(List<Long>) → List<McpCatalogEntry>`, `ModelEffortPolicy.resolveModel/resolveEffort/validate`.
- Produces: `QuestionService.create(QuestionCreateRequest, String requesterId) → InterviewSession`, `list(String viewerId, boolean isAdmin, boolean all) → List<QuestionSummaryResponse>`, `get(Long, String, boolean) → InterviewResponse`, `requireViewable(Long, String, boolean)`, `ask(Long, String, boolean, AnswerRequest) → InterviewSession`, `close(Long, String, boolean) → InterviewSession`. 레포: `countActiveQuestionsByRequester`, `findByKindAndRequesterIdOrderByCreatedAtDesc`, `findByKindOrderByCreatedAtDesc`, `countBySessionIdAndRole`.

- [ ] **Step 1: 실패하는 단위 테스트 작성** — `QuestionServiceTest.java` (Mockito, Docker 불필요)

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionServiceTest {

    private static final RepoCatalogService.ResolvedRepo REPO = new RepoCatalogService.ResolvedRepo(
            1L, "Netis7.0", "https://github.com/micthebick84/netis7.0.git", "github",
            "micthebick84/netis7.0", "main");

    private InterviewService interviewService;
    private InterviewSessionRepository sessionRepo;
    private InterviewTurnRepository turnRepo;
    private RepoCatalogService repoCatalog;
    private McpCatalogService mcpCatalog;
    private QuestionService service;

    @BeforeEach
    void setUp() {
        interviewService = mock(InterviewService.class);
        sessionRepo = mock(InterviewSessionRepository.class);
        turnRepo = mock(InterviewTurnRepository.class);
        repoCatalog = mock(RepoCatalogService.class);
        mcpCatalog = mock(McpCatalogService.class);
        service = new QuestionService(interviewService, sessionRepo, turnRepo, repoCatalog, mcpCatalog, 3, 10);
        when(sessionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repoCatalog.resolveForRegistration(1L)).thenReturn(REPO);
    }

    private static QuestionCreateRequest req(String model, String effort, List<Long> mcpIds) {
        return new QuestionCreateRequest(1L, "dev", "인증 흐름", "로그인은 어디서 처리되나요?", model, effort, mcpIds);
    }

    @Test
    void create_builds_question_session_from_catalog_repo_and_selected_model() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(req("claude-sonnet-5", "medium", null), "user1");
        assertThat(s.getKind()).isEqualTo(InterviewKind.QUESTION);
        assertThat(s.getStatus()).isEqualTo(InterviewStatus.QUEUED);
        assertThat(s.getGithubRepo()).isEqualTo("micthebick84/netis7.0");
        assertThat(s.getGithubBranch()).isEqualTo("dev");
        assertThat(s.getTitle()).isEqualTo("인증 흐름");
        assertThat(s.getDescription()).isEqualTo("로그인은 어디서 처리되나요?");
        assertThat(s.getRequesterId()).isEqualTo("user1");
        assertThat(s.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(s.getEffort()).isEqualTo("medium");
        assertThat(s.getGitUrl()).isEqualTo(REPO.gitUrl());
        assertThat(s.getRepoAlias()).isEqualTo("Netis7.0");
        assertThat(s.getRepoCatalogId()).isEqualTo(1L);
        assertThat(s.getTaskId()).isNull();
        assertThat(s.getMcpsExtra()).isEmpty();
        verify(sessionRepo).save(s);
    }

    @Test
    void create_blank_model_effort_fall_back_to_policy_defaults() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        InterviewSession s = service.create(req("", null, List.of()), "user1");
        assertThat(s.getModel()).isEqualTo(ModelEffortPolicy.DEFAULT_MODEL);
        assertThat(s.getEffort()).isEqualTo(ModelEffortPolicy.DEFAULT_EFFORT);
    }

    @Test
    void create_snapshots_selected_mcp_catalog_entries() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        McpCatalogEntry entry = mock(McpCatalogEntry.class);
        when(entry.isEnabled()).thenReturn(true);
        when(entry.getName()).thenReturn("ctx7");
        when(entry.getUrl()).thenReturn("https://ctx7");
        when(entry.getTransport()).thenReturn("http");
        when(mcpCatalog.resolveByIds(List.of(9L))).thenReturn(List.of(entry));
        InterviewSession s = service.create(req(null, null, List.of(9L)), "user1");
        assertThat(s.getMcpsExtra()).hasSize(1);
        assertThat(s.getMcpsExtra().get(0).name()).isEqualTo("ctx7");
    }

    @Test
    void create_over_active_limit_throws_429_without_saving() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(3L);
        assertThatThrownBy(() -> service.create(req(null, null, null), "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void create_invalid_effort_for_model_throws_400_before_saving() {
        when(sessionRepo.countActiveQuestionsByRequester("user1")).thenReturn(0L);
        assertThatThrownBy(() -> service.create(req("claude-haiku-4-5", "max", null), "user1"))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void ask_at_turn_cap_throws_400_and_does_not_delegate() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(10L);
        assertThatThrownBy(() -> service.ask(5L, "user1", false, new AnswerRequest("더 자세히?", 9)))
                .isInstanceOf(TaskException.class)
                .satisfies(e -> assertThat(((TaskException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        verify(interviewService, never()).submitAnswer(anyLong(), anyString(), anyBoolean(), any());
    }

    @Test
    void ask_below_cap_delegates_to_submitAnswer_with_real_isAdmin() {
        when(turnRepo.countBySessionIdAndRole(5L, "assistant")).thenReturn(9L);
        AnswerRequest req = new AnswerRequest("더 자세히?", 9);
        service.ask(5L, "user1", false, req);
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        verify(interviewService).submitAnswer(5L, "user1", false, req);
    }

    @Test
    void list_non_admin_ignores_all_flag() {
        service.list("user1", false, true);
        verify(sessionRepo).findByKindAndRequesterIdOrderByCreatedAtDesc(InterviewKind.QUESTION, "user1");
        verify(sessionRepo, never()).findByKindOrderByCreatedAtDesc(any());
    }

    @Test
    void list_admin_with_all_uses_kind_wide_query() {
        service.list("admin1", true, true);
        verify(sessionRepo).findByKindOrderByCreatedAtDesc(InterviewKind.QUESTION);
    }

    @Test
    void close_checks_kind_then_delegates_to_cancel() {
        service.close(5L, "user1", false);
        verify(interviewService).requireKind(5L, InterviewKind.QUESTION);
        verify(interviewService).cancel(eq(5L), eq("user1"), eq(false));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests '*QuestionServiceTest'`
Expected: 컴파일 에러 (`QuestionService`, DTO, 레포 메서드 없음).

- [ ] **Step 3: 레포지토리 쿼리 추가**

`InterviewSessionRepository.java` — `findOpenByTaskId` 아래에:
```java
    /** 질문 세션 남용 가드 — 사용자당 활성(QUEUED/RUNNING/AWAITING_INPUT) 질문 수 (스펙 §2). */
    @Query("""
        SELECT COUNT(s) FROM InterviewSession s
        WHERE s.requesterId = :requesterId
          AND s.kind = com.hamonsoft.netismaker.entity.InterviewKind.QUESTION
          AND s.status IN (com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED,
                           com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING,
                           com.hamonsoft.netismaker.entity.InterviewStatus.AWAITING_INPUT)
    """)
    long countActiveQuestionsByRequester(@Param("requesterId") String requesterId);

    /** 질문 목록 — 본인, 최신순 (V20 idx_interview_session_kind_requester 커버). */
    List<InterviewSession> findByKindAndRequesterIdOrderByCreatedAtDesc(InterviewKind kind, String requesterId);

    /** 질문 목록 — 관리자 전체, 최신순. */
    List<InterviewSession> findByKindOrderByCreatedAtDesc(InterviewKind kind);
```
임포트 `com.hamonsoft.netismaker.entity.InterviewKind` 추가.

`InterviewTurnRepository.java` — `findMaxSeq` 아래에:
```java
    /** 세션의 역할별 턴 수 — 질문 세션 문답 상한 판정(assistant 답변 수 = 러너 판정과 동일 단위). */
    long countBySessionIdAndRole(Long sessionId, String role);
```

- [ ] **Step 4: DTO 생성**

`QuestionCreateRequest.java`:
```java
package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 질문 세션 등록 요청. 승인 게이트가 없으므로 모델/effort/MCP를 등록자가 여기서 정한다
 * (스펙 2026-08-30 §2). model/effort/mcpCatalogIds는 optional — 없으면 ModelEffortPolicy 기본값 + MCP 없음.
 */
public record QuestionCreateRequest(
        @NotNull(message = "repoCatalogId는 필수입니다 (레포 카탈로그에서 선택)")
        Long repoCatalogId,

        @Size(max = 255)
        String githubBranch,

        @NotBlank
        @Size(max = 500)
        String title,

        /** 질문 본문 — InterviewSession.description에 저장. */
        @NotBlank
        String question,

        String model,
        String effort,
        List<Long> mcpCatalogIds
) {}
```

`QuestionSummaryResponse.java`:
```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.InterviewSession;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 질문 목록 항목. InterviewSummaryResponse에는 title/레포/요청자/비용이 없어 별도 DTO. */
public record QuestionSummaryResponse(
        Long id,
        String title,
        String githubRepo,
        String githubBranch,
        String repoAlias,
        String requesterId,
        String status,        // 한글 dbValue (표시용)
        String statusName,    // 영문 enum name (로직용)
        String model,
        String effort,
        BigDecimal totalCostUsd,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static QuestionSummaryResponse of(InterviewSession s) {
        return new QuestionSummaryResponse(
                s.getId(), s.getTitle(), s.getGithubRepo(), s.getGithubBranch(), s.getRepoAlias(),
                s.getRequesterId(), s.getStatus().dbValue(), s.getStatus().name(),
                s.getModel(), s.getEffort(), s.getTotalCostUsd(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
```

- [ ] **Step 5: `QuestionService` 생성**

```java
package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.dto.QuestionSummaryResponse;
import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.InterviewTurnRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 질문 세션(Q&A) — 스펙 docs/superpowers/specs/2026-08-30-question-sessions-design.md.
 * 상태 전이는 전부 InterviewService에 위임하고, 여기서는 (1) kind=QUESTION 교차 가드(404),
 * (2) 등록 시 남용 가드(429)와 모델/MCP 스냅샷, (3) 문답 턴 상한(400)만 담당한다.
 * 등록 즉시 QUEUED — 승인 게이트 없음. taskId는 항상 null(만료/취소의 Task 부수효과는 mirrorTask가 no-op).
 */
@Service
@Profile("api")
public class QuestionService {

    private final InterviewService interviewService;
    private final InterviewSessionRepository sessionRepo;
    private final InterviewTurnRepository turnRepo;
    private final RepoCatalogService repoCatalogService;
    private final McpCatalogService mcpCatalogService;
    private final int maxActivePerUser;
    private final int maxQaTurns;

    public QuestionService(InterviewService interviewService,
                           InterviewSessionRepository sessionRepo,
                           InterviewTurnRepository turnRepo,
                           RepoCatalogService repoCatalogService,
                           McpCatalogService mcpCatalogService,
                           @Value("${app.question.max-active-per-user:3}") int maxActivePerUser,
                           @Value("${app.question.max-qa-turns:10}") int maxQaTurns) {
        this.interviewService = interviewService;
        this.sessionRepo = sessionRepo;
        this.turnRepo = turnRepo;
        this.repoCatalogService = repoCatalogService;
        this.mcpCatalogService = mcpCatalogService;
        this.maxActivePerUser = maxActivePerUser;
        this.maxQaTurns = maxQaTurns;
    }

    @Transactional
    public InterviewSession create(QuestionCreateRequest req, String requesterId) {
        long active = sessionRepo.countActiveQuestionsByRequester(requesterId);
        if (active >= maxActivePerUser) {
            throw TaskException.tooManyRequests(
                    "동시에 진행할 수 있는 질문 세션 한도(" + maxActivePerUser + ")를 초과했습니다");
        }
        RepoCatalogService.ResolvedRepo repo = repoCatalogService.resolveForRegistration(req.repoCatalogId());
        List<TaskMcpSpec> extras = resolveMcpExtras(req.mcpCatalogIds());
        String model = ModelEffortPolicy.resolveModel(req.model());
        String effort = ModelEffortPolicy.resolveEffort(req.effort());
        ModelEffortPolicy.validate(model, effort);   // 검증이 먼저 — 실패 시 세션이 생기면 안 된다

        InterviewSession s = InterviewSession.createQuestion(repo.ownerRepo(), req.githubBranch(),
                req.title(), req.question(), requesterId, extras, model, effort);
        s.setGitUrl(repo.gitUrl());
        s.setRepoAlias(repo.alias());
        s.setRepoCatalogId(repo.catalogId());
        return sessionRepo.save(s);
    }

    /** 본인 목록. 관리자는 all=true일 때만 전체 (스펙 §5). */
    @Transactional(readOnly = true)
    public List<QuestionSummaryResponse> list(String viewerId, boolean isAdmin, boolean all) {
        List<InterviewSession> rows = (isAdmin && all)
                ? sessionRepo.findByKindOrderByCreatedAtDesc(InterviewKind.QUESTION)
                : sessionRepo.findByKindAndRequesterIdOrderByCreatedAtDesc(InterviewKind.QUESTION, viewerId);
        return rows.stream().map(QuestionSummaryResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public InterviewResponse get(Long id, String viewerId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        return interviewService.getResponse(id, viewerId, isAdmin);
    }

    /** SSE 구독 전 ACL 검증 (kind 404 → 소유자/관리자 403). */
    @Transactional(readOnly = true)
    public void requireViewable(Long id, String viewerId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        interviewService.getForView(id, viewerId, isAdmin);
    }

    /**
     * 추가 질문 = submitAnswer 재사용(재큐). 문답 상한(assistant 답변 수)은 여기서 400으로 우아하게 막는다 —
     * 러너의 maxTurns 가드(FAILED)는 방어선으로만 남긴다 (불변식: maxQaTurns < INTERVIEW_MAX_TURNS).
     */
    @Transactional
    public InterviewSession ask(Long id, String actorId, boolean isAdmin, AnswerRequest req) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        long answered = turnRepo.countBySessionIdAndRole(id, "assistant");
        if (answered >= maxQaTurns) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "최대 문답 수(" + maxQaTurns + ")에 도달했습니다 — 새 질문 세션을 열어주세요");
        }
        return interviewService.submitAnswer(id, actorId, isAdmin, req);
    }

    /** 종료 = cancel 재사용 → CANCELLED (질문 문맥 라벨 "종료됨"). */
    @Transactional
    public InterviewSession close(Long id, String actorId, boolean isAdmin) {
        interviewService.requireKind(id, InterviewKind.QUESTION);
        return interviewService.cancel(id, actorId, isAdmin);
    }

    /**
     * 카탈로그 id → 스냅샷 스펙 (비활성/누락 id 거절). TaskService.resolveMcpExtras와 동일 규칙 —
     * private라 복제. (public 승격은 TaskService 테스트의 mcpCatalogService 스텁을 깨므로 v1은 복제 유지.)
     */
    private List<TaskMcpSpec> resolveMcpExtras(List<Long> catalogIds) {
        if (catalogIds == null || catalogIds.isEmpty()) return new ArrayList<>();
        List<McpCatalogEntry> entries = mcpCatalogService.resolveByIds(catalogIds);
        if (entries.size() != catalogIds.size()) {
            throw new TaskException(HttpStatus.BAD_REQUEST,
                    "존재하지 않는 MCP 카탈로그 id 포함. 요청=" + catalogIds.size() + " 매칭=" + entries.size());
        }
        List<TaskMcpSpec> out = new ArrayList<>(entries.size());
        for (McpCatalogEntry e : entries) {
            if (!e.isEnabled()) {
                throw new TaskException(HttpStatus.BAD_REQUEST, "비활성화된 MCP 카탈로그 항목: " + e.getName());
            }
            out.add(new TaskMcpSpec(e.getName(), e.getUrl(), e.getTransport()));
        }
        return out;
    }
}
```

- [ ] **Step 6: 설정 추가** — `application.yml`의 `app.interview:` 블록 바로 아래(같은 들여쓰기)에

```yaml
  question:
    max-active-per-user: 3          # 사용자당 활성(QUEUED/RUNNING/AWAITING_INPUT) 질문 세션 상한 → 초과 시 429
    max-qa-turns: 10                # 세션당 assistant 답변 상한 → ask 시 초과면 400. 불변식: < 러너 INTERVIEW_MAX_TURNS(20)
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests '*QuestionServiceTest'`
Expected: PASS (11 tests).

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/repository/InterviewSessionRepository.java src/main/java/com/hamonsoft/netismaker/repository/InterviewTurnRepository.java src/main/java/com/hamonsoft/netismaker/dto/QuestionCreateRequest.java src/main/java/com/hamonsoft/netismaker/dto/QuestionSummaryResponse.java src/main/java/com/hamonsoft/netismaker/service/QuestionService.java src/main/resources/application.yml src/test/java/com/hamonsoft/netismaker/service/QuestionServiceTest.java
git commit -m "feat: QuestionService — 질문 세션 등록(429 가드)·목록·ask(턴 상한 400)·close, InterviewService 위임"
```

---

### Task 5: `QuestionController` + 통합 테스트

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/controller/QuestionController.java`
- Test: `src/test/java/com/hamonsoft/netismaker/controller/QuestionApiIntegrationTest.java`

**Interfaces:**
- Consumes: `QuestionService`(Task 4), `InterviewStreamService.subscribe/pushStatus/finish`, `AuthContext.requireUserId/isAdmin`.
- Produces: HTTP 표면 — `POST /api/questions`(201, body `QuestionSummaryResponse`, Location), `GET /api/questions?all=`, `GET /api/questions/{id}`(`InterviewResponse`), `GET /api/questions/{id}/stream`(SSE), `POST /api/questions/{id}/ask`(body `AnswerRequest {answer, replyToSeq}`), `POST /api/questions/{id}/close`. 프론트(Task 9~11)가 이 계약을 쓴다.

- [ ] **Step 1: 실패하는 통합 테스트 작성** — `QuestionApiIntegrationTest.java`

```java
package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 질문 세션 API 표면 (스펙 §5). 상한은 낮춰서 429/400 경로를 짧게 검증한다:
 * 활성 세션 2개 → 3번째 429, assistant 답변 2개 → ask 400.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest(properties = {"app.question.max-active-per-user=2", "app.question.max-qa-turns=2"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class QuestionApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskRepository taskRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    private static final String CREATE_BODY =
            "{\"repoCatalogId\":1,\"githubBranch\":\"main\",\"title\":\"인증 흐름\","
                    + "\"question\":\"로그인은 어디서 처리되나요?\",\"model\":\"claude-sonnet-5\",\"effort\":\"medium\"}";

    @BeforeEach void clean() {
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static RequestPostProcessor adminJwt(String userId) {
        return jwt().jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long createQuestion(RequestPostProcessor as) throws Exception {
        String resp = mvc.perform(post("/api/questions").with(as).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    /** 워커가 세션을 claim하고 답변 1건을 보고 → AWAITING_INPUT. */
    private void workerAnswers(long sid, String text) throws Exception {
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid))
                .andExpect(jsonPath("$.kind").value("QUESTION"));
        mvc.perform(post("/worker/interviews/" + sid + "/question").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"content\":\"" + text + "\",\"claudeSessionId\":\"sess-q\",\"kind\":\"question\",\"costUsd\":0.1}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void POST_creates_queued_question_for_any_authenticated_user() throws Exception {
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/questions/")))
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.requesterId").value("user1"))
                .andExpect(jsonPath("$.repoAlias").value("Netis7.0"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"));
    }

    @Test
    void POST_over_active_limit_returns_429() throws Exception {
        createQuestion(userJwt("user1"));
        createQuestion(userJwt("user1"));
        mvc.perform(post("/api/questions").with(userJwt("user1")).contentType(APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void GET_detail_owner_and_admin_ok_other_user_403() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("QUESTION"))
                .andExpect(jsonPath("$.description").value("로그인은 어디서 처리되나요?"));
        mvc.perform(get("/api/questions/" + id).with(adminJwt("admin1"))).andExpect(status().isOk());
        mvc.perform(get("/api/questions/" + id).with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_stream_owner_ok_other_user_403() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions/" + id + "/stream").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/event-stream")));
        mvc.perform(get("/api/questions/" + id + "/stream").with(userJwt("user2"))).andExpect(status().isForbidden());
    }

    @Test
    void GET_list_mine_only_unless_admin_asks_all() throws Exception {
        createQuestion(userJwt("user1"));
        mvc.perform(get("/api/questions").with(userJwt("user1"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/questions").with(userJwt("user2"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/questions").with(adminJwt("admin1"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/questions").param("all", "true").with(adminJwt("admin1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requesterId").value("user1"));
        // 일반 사용자의 all=true는 무시 — 본인 것만
        mvc.perform(get("/api/questions").param("all", "true").with(userJwt("user2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cross_kind_access_is_404_in_both_directions() throws Exception {
        long qid = createQuestion(userJwt("user1"));
        long iid = sessionRepo.save(InterviewSession.create("a/b", "main", "T", "D", "user1",
                List.of(), "claude-opus-5", "high")).getId();
        mvc.perform(get("/api/interviews/" + qid).with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/interviews/" + qid + "/cancel").with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/questions/" + iid).with(adminJwt("admin1"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/questions/" + iid + "/close").with(adminJwt("admin1"))).andExpect(status().isNotFound());
    }

    @Test
    void worker_answer_then_owner_asks_followup_requeues_without_phase() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "AuthController에서 처리합니다");
        mvc.perform(get("/api/questions/" + id).with(userJwt("user1")))
                .andExpect(jsonPath("$.statusName").value("AWAITING_INPUT"))
                .andExpect(jsonPath("$.currentPhase").isEmpty())   // null — brainstorming 표기 없음
                .andExpect(jsonPath("$.turns", hasSize(1)));
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"토큰 검증은요?\",\"replyToSeq\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("QUEUED"))
                .andExpect(jsonPath("$.turns", hasSize(2)));
        // 타인은 추가 질문 불가
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user2"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ask_at_qa_turn_cap_returns_400() throws Exception {
        long id = createQuestion(userJwt("user1"));
        workerAnswers(id, "답변 1");
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"더?\",\"replyToSeq\":1}"))
                .andExpect(status().isOk());
        workerAnswers(id, "답변 2");   // assistant 답변 2개 = 상한
        mvc.perform(post("/api/questions/" + id + "/ask").with(userJwt("user1"))
                        .contentType(APPLICATION_JSON).content("{\"answer\":\"또?\",\"replyToSeq\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("최대 문답 수")));
    }

    @Test
    void worker_plan_on_question_session_returns_400() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(post("/worker/interviews/claim").header("X-Worker-API-Key", apiKey).param("workerId", "iw-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/worker/interviews/" + id + "/plan").header("X-Worker-API-Key", apiKey)
                        .param("workerId", "iw-1").contentType(APPLICATION_JSON)
                        .content("{\"designMarkdown\":\"# 설계\",\"planMarkdown\":\"# 플랜\",\"planJson\":\"[]\",\"costUsd\":0.1,\"durationMs\":1}"))
                .andExpect(status().isBadRequest());
        // 세션은 그대로 RUNNING — 인터뷰 서비스가 이어서 답변 보고 가능
        assertThat(sessionRepo.findById(id).orElseThrow().getStatus().name()).isEqualTo("RUNNING");
    }

    @Test
    void close_by_owner_moves_to_cancelled_and_touches_no_task() throws Exception {
        long id = createQuestion(userJwt("user1"));
        mvc.perform(post("/api/questions/" + id + "/close").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusName").value("CANCELLED"));
        assertThat(taskRepo.count()).isZero();   // taskId null → mirrorTask no-op, task 없음 유지
        mvc.perform(post("/api/questions/" + id + "/close").with(userJwt("user1"))).andExpect(status().isConflict());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*QuestionApiIntegrationTest'`
Expected: 전부 FAIL — `/api/questions` 404 (컨트롤러 없음).

- [ ] **Step 3: `QuestionController` 생성**

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.AnswerRequest;
import com.hamonsoft.netismaker.dto.InterviewResponse;
import com.hamonsoft.netismaker.dto.QuestionCreateRequest;
import com.hamonsoft.netismaker.dto.QuestionSummaryResponse;
import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.service.InterviewStreamService;
import com.hamonsoft.netismaker.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;

/**
 * 질문 세션(Q&A) API — 스펙 2026-08-30 §5. JWT 인증만(USER+ADMIN 공용, @PreAuthorize 없음).
 * ACL은 세션 소유자 OR 관리자(InterviewService.requireOwner) — InterviewController와 달리 isAdmin을
 * 하드코딩하지 않고 AuthContext.isAdmin(auth) 실값을 넘긴다. kind 불일치는 404, 타인 소유는 403.
 * SSE 이벤트/상태 전이는 InterviewStreamService·InterviewService를 그대로 공유한다.
 */
@RestController
@RequestMapping("/api/questions")
@Profile("api")
public class QuestionController {

    private final QuestionService questionService;
    private final InterviewStreamService interviewStream;

    public QuestionController(QuestionService questionService, InterviewStreamService interviewStream) {
        this.questionService = questionService;
        this.interviewStream = interviewStream;
    }

    @PostMapping
    public ResponseEntity<QuestionSummaryResponse> create(@RequestBody @Valid QuestionCreateRequest req,
                                                          JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        InterviewSession s = questionService.create(req, userId);
        return ResponseEntity.created(URI.create("/api/questions/" + s.getId()))
                .body(QuestionSummaryResponse.of(s));
    }

    @GetMapping
    public List<QuestionSummaryResponse> list(@RequestParam(defaultValue = "false") boolean all,
                                              JwtAuthenticationToken auth) {
        return questionService.list(AuthContext.requireUserId(auth), AuthContext.isAdmin(auth), all);
    }

    @GetMapping("/{id}")
    public InterviewResponse get(@PathVariable Long id, JwtAuthenticationToken auth) {
        return questionService.get(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id, JwtAuthenticationToken auth) {
        questionService.requireViewable(id, AuthContext.requireUserId(auth), AuthContext.isAdmin(auth));
        return interviewStream.subscribe(id);
    }

    /** 추가 질문 — 바디는 AnswerRequest {answer, replyToSeq} 그대로(프론트 InterviewPanel 재사용). */
    @PostMapping("/{id}/ask")
    public InterviewResponse ask(@PathVariable Long id, @RequestBody @Valid AnswerRequest req,
                                 JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        questionService.ask(id, userId, isAdmin, req);
        interviewStream.pushStatus(id, InterviewStatus.QUEUED);
        return questionService.get(id, userId, isAdmin);
    }

    @PostMapping("/{id}/close")
    public InterviewResponse close(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        boolean isAdmin = AuthContext.isAdmin(auth);
        questionService.close(id, userId, isAdmin);
        interviewStream.pushStatus(id, InterviewStatus.CANCELLED);
        interviewStream.finish(id); // terminal → done 이벤트
        return questionService.get(id, userId, isAdmin);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `RUN_TESTCONTAINERS=true ./gradlew test --tests '*QuestionApiIntegrationTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: 백엔드 전체 게이트**

Run: `RUN_TESTCONTAINERS=true ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/hamonsoft/netismaker/controller/QuestionController.java src/test/java/com/hamonsoft/netismaker/controller/QuestionApiIntegrationTest.java
git commit -m "feat: /api/questions 컨트롤러 — 등록·목록·상세·SSE·추가 질문·종료 (USER+ADMIN, 본인+관리자 ACL)"
```

---

### Task 6: interview-service — `kind` 타입 + default-deny 도구 게이트 + 세션 옵션 분기

**Files:**
- Modify: `netismaker-interview-service/src/types.ts` (`InterviewClaimResponse` 인터페이스 끝)
- Modify: `netismaker-interview-service/src/sdk/permissions.ts`
- Modify: `netismaker-interview-service/src/sdk/sessionOptions.ts`
- Test: `netismaker-interview-service/test/permissions.test.ts`, `netismaker-interview-service/test/sessionOptions.test.ts`

**Interfaces:**
- Consumes: Java claim 응답의 `kind` (Task 2) — optional로 받는다(구버전 백엔드 호환).
- Produces: `export type SessionKind = 'INTERVIEW' | 'QUESTION'` (`src/types.ts`); `InterviewClaimResponse.kind?: SessionKind`; `buildCanUseTool(repoDir: string, kind?: SessionKind)`; `SessionOptionsInput.sessionKind?: SessionKind`. Task 7이 `sessionKind: 'QUESTION'`으로 호출.

- [ ] **Step 1: 실패하는 게이트 테스트 작성** — `test/permissions.test.ts` 끝에 추가

```ts
describe('canUseTool — kind=QUESTION (default-deny, 스펙 §6-①)', () => {
  const q = buildCanUseTool('/tmp/repo', 'QUESTION');

  it('denies every write tool even inside docs/superpowers (인터뷰 예외조차 없음)', async () => {
    for (const tool of ['Write', 'Edit', 'MultiEdit', 'NotebookEdit']) {
      const r = await q(tool, { file_path: '/tmp/repo/docs/superpowers/specs/x.md' });
      expect(r.behavior, tool).toBe('deny');
    }
  });

  it('denies Skill and any unknown/future tool (허용 목록 외 전부 deny)', async () => {
    expect((await q('Skill', { name: 'brainstorming' })).behavior).toBe('deny');
    expect((await q('FutureWriteTool', {})).behavior).toBe('deny');
    expect((await q('WebFetch', { url: 'http://x' })).behavior).toBe('deny');
  });

  it('allows Read/Grep/Glob and mcp__ tools (base+extras)', async () => {
    expect((await q('Read', { file_path: '/tmp/repo/src/main.ts' })).behavior).toBe('allow');
    expect((await q('Grep', { pattern: 'x' })).behavior).toBe('allow');
    expect((await q('Glob', { pattern: '**/*.ts' })).behavior).toBe('allow');
    expect((await q('mcp__local-db__query', { sql: 'select 1' })).behavior).toBe('allow');
  });

  it('keeps the same read-only Bash whitelist + metachar block as the interview gate', async () => {
    for (const cmd of ['git status', 'git log --oneline', 'ls -la', 'rg foo', 'wc -l a.ts', 'pwd']) {
      expect((await q('Bash', { command: cmd })).behavior, cmd).toBe('allow');
    }
    for (const cmd of ['git push origin main', 'git commit -m x', 'rm -rf /', 'npm install', 'git status; rm -rf /']) {
      expect((await q('Bash', { command: cmd })).behavior, cmd).toBe('deny');
    }
  });

  it('kind omitted → interview gate unchanged (Write inside docs/superpowers still allowed)', async () => {
    const i = buildCanUseTool('/tmp/repo');
    expect((await i('Write', { file_path: '/tmp/repo/docs/superpowers/specs/x.md' })).behavior).toBe('allow');
    expect((await i('NotebookEdit', {})).behavior).toBe('allow');
  });
});
```

- [ ] **Step 2: 실패하는 옵션 테스트 작성** — `test/sessionOptions.test.ts` 끝에 추가

```ts
describe('buildOptions — sessionKind QUESTION (스펙 §6-①)', () => {
  const mcps = {
    mcpsBase: { 'local-db': { command: 'npx', args: ['x'] } },
    mcpsExtra: [{ name: 'ctx7', url: 'https://ctx7', transport: 'http' }],
  };

  it('loads NO plugins and pre-approves ONLY Read/Grep/Glob — no Skill, no mcp__ wildcard', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', ...mcps });
    expect(o.plugins).toEqual([]);
    expect(o.allowedTools).toEqual(['Read', 'Grep', 'Glob']);
  });

  it('still injects merged mcpServers (base+extras, 워커 패리티) — 게이트는 canUseTool 단일 관문', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION', ...mcps });
    expect(o.mcpServers).toEqual({
      'local-db': { command: 'npx', args: ['x'] },
      ctx7: { type: 'http', url: 'https://ctx7' },
    });
  });

  it('canUseTool is the QUESTION gate (denies Write even under docs/superpowers)', async () => {
    const o = buildOptions({ ...base, claudeSessionId: null, sessionKind: 'QUESTION' });
    const gate = o.canUseTool as (t: string, i: Record<string, unknown>) => Promise<{ behavior: string }>;
    expect((await gate('Write', { file_path: '/tmp/repo/docs/superpowers/x.md' })).behavior).toBe('deny');
    expect((await gate('Read', { file_path: '/tmp/repo/a.ts' })).behavior).toBe('allow');
  });

  it('keeps resume/cwd/model/effort/includePartialMessages wiring identical to INTERVIEW', () => {
    const o = buildOptions({
      ...base, claudeSessionId: 'sess-q', sessionKind: 'QUESTION', model: 'claude-sonnet-5', effort: 'medium',
    });
    expect(o.resume).toBe('sess-q');
    expect(o.cwd).toBe('/tmp/repo');
    expect(o.model).toBe('claude-sonnet-5');
    expect(o.effort).toBe('medium');
    expect(o.includePartialMessages).toBe(true);
    expect(o.permissionMode).toBe('default');
  });

  it('sessionKind omitted → INTERVIEW behaviour (superpowers plugin + Skill + mcp__ wildcards)', () => {
    const o = buildOptions({ ...base, claudeSessionId: null, ...mcps });
    expect(o.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
    expect(o.allowedTools).toEqual(['Skill', 'Read', 'Grep', 'Glob', 'mcp__local-db', 'mcp__ctx7']);
  });
});
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd /Users/micthebick/IdeaProjects/netisMaker/netismaker-interview-service && npx vitest run test/permissions.test.ts test/sessionOptions.test.ts`
Expected: QUESTION describe들 FAIL (`sessionKind` 무시 → plugins 비어있지 않음, Write allow 등).

- [ ] **Step 4: `src/types.ts` 수정**

파일 상단(`InterviewTurn` 위)에:
```ts
/** 세션 종류 — Java InterviewKind. QUESTION = Q&A 전용(superpowers 미로드, default-deny 게이트, plan 경로 없음). */
export type SessionKind = 'INTERVIEW' | 'QUESTION';
```
`InterviewClaimResponse`의 `attachments?: AttachmentRef[];` 아래에:
```ts
  /**
   * 세션 종류. 구버전 백엔드는 필드가 없다 — 미존재는 INTERVIEW로 취급(attachments 선례).
   * 러너는 `kind === 'QUESTION'`로만 판정한다.
   */
  kind?: SessionKind;
```

- [ ] **Step 5: `src/sdk/permissions.ts` 수정** — 파일 전체를 다음으로 교체

```ts
import { resolve } from 'node:path';
import type { SessionKind } from '../types.js';

export interface PermissionResult {
  behavior: 'allow' | 'deny';
  message?: string;
}
export type CanUseTool = (
  toolName: string,
  input: Record<string, unknown>,
) => Promise<PermissionResult>;

// Bash commands the interview may run. Read-only / inspection only. NO push, rm, curl, write.
const BASH_WHITELIST = [/^git (status|log|diff|show|branch)\b/, /^ls\b/, /^cat\b/, /^grep\b/, /^rg\b/, /^find\b/, /^head\b/, /^tail\b/, /^wc\b/, /^pwd$/];

// 셸 메타문자 — 화이트리스트 접두사로 위장한 명령 체이닝/치환/리다이렉트를 차단.
//   'git status; rm -rf /', 'git log && curl ...', 'cat x `rm y`', 'ls $(...)' 등.
// 화이트리스트는 ^앵커만 검사하므로, 메타문자를 먼저 거부하지 않으면 두 번째 명령이 통과한다.
const SHELL_METACHARS = /[;&|`$(){}<>\n\r]/;

// 질문 세션(Q&A) 허용 도구 — 이 목록 + 읽기전용 Bash + mcp__* 외에는 전부 deny (스펙 2026-08-30 §6-①).
const QUESTION_ALLOWED = new Set(['Read', 'Grep', 'Glob']);

function bashGate(input: Record<string, unknown>): PermissionResult {
  const cmd = String(input.command ?? '').trim();
  // 메타문자 우선 차단: 화이트리스트 접두사 뒤에 ; && | $() `` 등으로 임의 명령을 붙일 수 없게.
  if (SHELL_METACHARS.test(cmd)) {
    return { behavior: 'deny', message: `Bash 셸 메타문자 금지 (명령 체이닝/치환 차단): ${cmd}` };
  }
  if (BASH_WHITELIST.some((re) => re.test(cmd))) return { behavior: 'allow' };
  return { behavior: 'deny', message: `Bash not whitelisted: ${cmd}` };
}

/**
 * INTERVIEW(기본): Write/Edit/MultiEdit은 docs/superpowers/** 로 경로 제한, Bash는 화이트리스트, 나머지 allow.
 * QUESTION: default-deny — Read/Grep/Glob, 읽기전용 Bash, mcp__* 만 allow. 쓰기형·미지 도구는 전부 deny
 *   (이름 모를 미래 도구도 자동 차단). Q&A 산출물은 대화 텍스트뿐이라 Write 예외가 필요 없다.
 */
export function buildCanUseTool(repoDir: string, kind: SessionKind = 'INTERVIEW'): CanUseTool {
  if (kind === 'QUESTION') {
    return async (toolName, input) => {
      if (toolName === 'Bash') return bashGate(input);
      if (QUESTION_ALLOWED.has(toolName) || toolName.startsWith('mcp__')) return { behavior: 'allow' };
      return { behavior: 'deny', message: `질문 세션에서는 ${toolName} 도구를 사용할 수 없습니다 (읽기 전용 Q&A)` };
    };
  }
  const allowedWriteRoot = resolve(repoDir, 'docs/superpowers');
  return async (toolName, input) => {
    if (toolName === 'Write' || toolName === 'Edit' || toolName === 'MultiEdit') {
      const fp = String(input.file_path ?? '');
      const abs = resolve(repoDir, fp);
      if (abs === allowedWriteRoot || abs.startsWith(allowedWriteRoot + '/')) {
        return { behavior: 'allow' };
      }
      return { behavior: 'deny', message: 'Write confined to docs/superpowers/**' };
    }
    if (toolName === 'Bash') return bashGate(input);
    // Read/Grep/Glob/Skill and other inspection tools are allowed.
    return { behavior: 'allow' };
  };
}
```

- [ ] **Step 6: `src/sdk/sessionOptions.ts` 수정**

(a) 임포트에 `import type { SessionKind } from '../types.js';` 추가.
(b) `SessionOptionsInput`의 `abortController?` 아래에:
```ts
  /**
   * 세션 종류. 'QUESTION'이면 superpowers 미로드 + Skill/mcp__ 사전승인 없음 + default-deny 게이트
   * (스펙 2026-08-30 §6-①). 미지정 = INTERVIEW(기존 동작 그대로).
   */
  sessionKind?: SessionKind;
```
(c) `buildOptions` 본문에서 `const mcpServers = ...` 다음 줄에 `const question = input.sessionKind === 'QUESTION';` 추가하고, 반환 객체의 `plugins`/`allowedTools`/`canUseTool` 세 항목을 다음으로 교체:
```ts
    // QUESTION: 플러그인 자체를 안 붙인다(스킬 없음). INTERVIEW: superpowers만 로컬 플러그인으로.
    plugins: question ? [] : [{ type: 'local', path: input.superpowersPluginPath }],
    // INTERVIEW: mcp__<server>는 해당 서버의 모든 도구 매칭 — 워커의 --allowedTools 와일드카드와 동일.
    //   MCP 도구는 어차피 canUseTool 기본 분기(allow)를 타므로 보안 경계 변화 없음; Write/Bash/Edit 불변식 유지.
    // QUESTION: mcp__ 와일드카드도 미등재 → MCP 호출까지 canUseTool(default-deny) 단일 관문 경유.
    allowedTools: question
      ? ['Read', 'Grep', 'Glob']
      : ['Skill', 'Read', 'Grep', 'Glob', ...Object.keys(merged).map((n) => `mcp__${n}`)],
```
…(중간 항목 그대로)…
```ts
    canUseTool: buildCanUseTool(input.workDir, input.sessionKind ?? 'INTERVIEW'),
```

- [ ] **Step 7: 테스트 + 타입체크 통과 확인**

Run: `npx vitest run test/permissions.test.ts test/sessionOptions.test.ts && npm run build`
Expected: 전부 PASS, tsc 에러 없음.

- [ ] **Step 8: 커밋**

```bash
git add netismaker-interview-service/src/types.ts netismaker-interview-service/src/sdk/permissions.ts netismaker-interview-service/src/sdk/sessionOptions.ts netismaker-interview-service/test/permissions.test.ts netismaker-interview-service/test/sessionOptions.test.ts
git commit -m "feat(interview-service): 질문 세션 default-deny 도구 게이트 + superpowers/Skill 미로드 옵션 분기"
```

---

### Task 7: interview-service — 러너 QUESTION 조기 분기 (kind 가드 4곳)

**Files:**
- Modify: `netismaker-interview-service/src/runner/interviewRunner.ts` (`promptFor` 아래에 `questionPromptFor`; `runTurn` 상단 분기; 새 private `runQuestionTurn`)
- Modify: `netismaker-interview-service/test/fixtures/claims.ts` (`questionClaim` 추가)
- Test: `netismaker-interview-service/test/interviewRunner.test.ts`

**Interfaces:**
- Consumes: `buildOptions({ ..., sessionKind: 'QUESTION' })` (Task 6), `claim.kind` (Task 6 타입), 기존 `relay`, `CostGuard`, `ActivityPoster`, `JavaApiClient.postQuestion`.
- Produces: 관측 가능한 동작 — QUESTION claim은 `postQuestion`으로만 끝난다(`postPlan` 호출 경로 없음). fail 사유 문구 `최대 문답 수(N) 초과 — 새 질문 세션을 열어주세요`.

- [ ] **Step 1: fixture 추가** — `test/fixtures/claims.ts` 끝에

```ts
/** 질문 세션(Q&A) fresh claim — kind='QUESTION' (스펙 2026-08-30 §5). description = 질문 본문. */
export const questionClaim: InterviewClaimResponse = {
  ...freshClaim,
  sessionId: 77,
  kind: 'QUESTION',
  title: '인증 흐름',
  description: '로그인은 어디서 처리되나요?',
};
```

- [ ] **Step 2: 실패하는 러너 테스트 작성** — `test/interviewRunner.test.ts`의 `turns()` 헬퍼 아래에 새 describe

임포트 줄의 fixtures에 `questionClaim` 추가: `import { freshClaim, resumeClaim, freshClaimWithAttachments, questionClaim } from './fixtures/claims.js';`

```ts
describe('InterviewRunner kind=QUESTION (스펙 §6 — plan 경로 미진입, Q&A 전용)', () => {
  type Captured = { prompt: string; options: Record<string, unknown> };
  /** 프롬프트 텍스트 + options를 캡처하고 주어진 스트림을 돌려주는 fakeQuery. */
  function capturing(streamFactory: () => AsyncIterable<unknown>) {
    const captured: Captured = { prompt: '', options: {} };
    const fakeQuery = vi.fn((args: { prompt: AsyncIterable<{ message?: { content?: string } }>; options: Record<string, unknown> }) => {
      captured.options = args.options;
      (async () => { for await (const p of args.prompt) captured.prompt += p.message?.content ?? ''; })();
      return streamFactory();
    });
    return { fakeQuery, captured };
  }

  it('fresh: Q&A 계약 킥오프(질문 본문 포함, 스킬/plan 문구 없음) + QUESTION 옵션 + 답변은 postQuestion', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(questionClaim);

    expect(captured.prompt).toContain('로그인은 어디서 처리되나요?');
    expect(captured.prompt).toContain('Q&A');
    expect(captured.prompt).toContain('읽기 전용');
    expect(captured.prompt).not.toContain('brainstorming');
    expect(captured.prompt).not.toContain('### 작업 N:');
    expect(captured.options.plugins).toEqual([]);
    expect(captured.options.allowedTools).toEqual(['Read', 'Grep', 'Glob']);
    expect(captured.options.cwd).toBe(questionClaim.workDir);
    expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({
      content: 'Which columns should the CSV include?',
      claudeSessionId: 'sess-new-1',
      kind: 'question',
      costUsd: 0.12,
    }));
    expect(client.postPlan).not.toHaveBeenCalled();
  });

  it('resume: 후속 질문(lastAnswer)을 그대로 주입하고 options.resume을 쓴다', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', lastAnswer: '토큰 검증은요?', turns: turns(1) });
    expect(captured.prompt).toBe('토큰 검증은요?');
    expect(captured.options.resume).toBe('sess-q-1');
  });

  it('가드①: forceFinish 턴수(19)에서도 reformat 프롬프트가 아니라 후속 질문을 보낸다', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // forceFinishTurns 19
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', lastAnswer: '마지막 질문', turns: turns(19) });
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(captured.prompt).toBe('마지막 질문');
    expect(captured.prompt).not.toContain('### 작업 N:');
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
  });

  it('가드②: handoff 문구가 와도 splice 재질의 없이 그 텍스트가 답변으로 저장된다', async () => {
    const client = makeClient();
    async function* handoffText() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-h' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: 'Spec approved. Invoke writing-plans skill now.' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.05 }, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => handoffText());
    const spliceRead = vi.fn();
    const runner = new InterviewRunner(client as never, fakeQuery as never, { ...deps, spliceRead } as never);
    await runner.run(questionClaim);
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(spliceRead).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({ content: expect.stringContaining('Invoke writing-plans') }));
    expect(client.postPlan).not.toHaveBeenCalled();
  });

  it('가드③: plan 의도 문구(구조 없음)도 reformat 재질의 없이 답변', async () => {
    const client = makeClient();
    async function* intentNoStructure() {
      yield { type: 'system', subtype: 'init', session_id: 'sess-n' };
      yield { type: 'assistant', message: { content: [{ type: 'text', text: '이제 구현 계획을 정리하겠습니다.' }] } };
      yield { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.1 }, duration_ms: 100 };
    }
    const fakeQuery = vi.fn(() => intentNoStructure());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(questionClaim);
    expect(fakeQuery).toHaveBeenCalledTimes(1);
    expect(client.postQuestion).toHaveBeenCalledTimes(1);
    expect(client.postPlan).not.toHaveBeenCalled();
  });

  it('가드④: plan 정규 형식 답변이 와도 postPlan이 아니라 postQuestion', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => planCompleteStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', lastAnswer: '계획 써줘', turns: turns(1) });
    expect(client.postPlan).not.toHaveBeenCalled();
    expect(client.postQuestion).toHaveBeenCalledWith(77, expect.objectContaining({
      content: expect.stringContaining('Implementation Plan'),
      costUsd: 0.31,
    }));
  });

  it('turn cap: 중립 문구로 fail (plan 언급 없음), 턴 미실행', async () => {
    const client = makeClient();
    const fakeQuery = vi.fn(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never); // maxTurns 20
    await runner.run({ ...questionClaim, claudeSessionId: 'sess-q-1', turns: turns(20) });
    expect(fakeQuery).not.toHaveBeenCalled();
    expect(client.fail).toHaveBeenCalledWith(77, expect.stringContaining('최대 문답 수'));
    expect(client.fail).not.toHaveBeenCalledWith(77, expect.stringContaining('plan'));
  });

  it('환경 준비(ensureRepo)는 QUESTION에서도 매 턴 실행된다 (방어 계층 ⑤)', async () => {
    const client = makeClient();
    ensureRepo.mockClear();
    const runner = new InterviewRunner(client as never, vi.fn(() => questionStream()) as never, deps as never);
    await runner.run(questionClaim);
    expect(ensureRepo).toHaveBeenCalledWith(expect.objectContaining({ githubRepo: 'acme/widgets', workDir: questionClaim.workDir }));
  });

  it('kind 미존재(구버전 백엔드) → 인터뷰 킥오프 그대로', async () => {
    const client = makeClient();
    const { fakeQuery, captured } = capturing(() => questionStream());
    const runner = new InterviewRunner(client as never, fakeQuery as never, deps as never);
    await runner.run(freshClaim);
    expect(captured.prompt).toContain('brainstorming');
    expect(captured.options.plugins).toEqual([{ type: 'local', path: '/sp/5.1.0' }]);
  });
});
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `npx vitest run test/interviewRunner.test.ts`
Expected: 새 describe 대부분 FAIL (킥오프에 'brainstorming' 포함, plugins 비어있지 않음, 가드④에서 postPlan 호출 등).

- [ ] **Step 4: `interviewRunner.ts` — `questionPromptFor` 추가** (`promptFor` 함수 바로 아래)

```ts
/**
 * 질문 세션(Q&A) 프롬프트 — 스킬 언급 없음. fresh = Q&A 전용 계약 + 읽기 전용 규칙 + 질문 본문(스펙 §6-④),
 * resume = 후속 질문(lastAnswer) 주입. 허용 Bash 목록은 permissions.ts BASH_WHITELIST와 동일하게 유지할 것.
 */
async function* questionPromptFor(claim: InterviewClaimResponse): AsyncIterable<UserTurn> {
  if (!claim.claudeSessionId) {
    yield userTurn(
      `당신은 \`${claim.githubRepo}\` (브랜치 ${claim.githubBranch}) 레포에 대한 질문에 답하는 코드 분석 어시스턴트입니다. ` +
        '현재 작업 디렉토리에 이 레포가 체크아웃되어 있습니다.\n\n' +
        `제목: ${claim.title}\n질문: ${claim.description}\n\n` +
        '규칙:\n' +
        '- 이 세션은 질문·답변(Q&A) 전용입니다. 구현 계획 작성, 작업 등록, 코드 수정은 이 세션에서 불가능합니다. ' +
        '그런 요청을 받으면 "작업 등록(인터뷰) 기능을 이용해 주세요"라고 안내하세요.\n' +
        '- 레포는 읽기 전용입니다: 파일 생성/수정, 빌드/설치/테스트 실행, git commit/push를 하지 마세요. ' +
        'Read/Grep/Glob, 읽기 전용 셸 명령(git status/log/diff/show/branch, ls, cat, grep, rg, find, head, tail, wc, pwd), ' +
        '연결된 MCP 도구로만 조사하세요.\n' +
        '- 한국어 마크다운으로 답하고, 근거는 `파일경로:라인` 형식으로 제시하세요. 확실하지 않으면 모른다고 답하세요.\n' +
        '- 스킬(Skill) 도구는 없습니다. 바로 조사하고 답하세요.',
    );
  } else {
    yield userTurn(claim.lastAnswer ?? '');
  }
}
```

- [ ] **Step 5: `runTurn` 상단 분기 수정**

`const assistantTurns = ...` 줄 위에 `const isQuestion = claim.kind === 'QUESTION';` 추가. maxTurns fail 블록을:
```ts
      if (assistantTurns >= this.deps.maxTurns) {
        await this.safeFail(
          claim.sessionId,
          isQuestion
            ? `최대 문답 수(${this.deps.maxTurns}) 초과 — 새 질문 세션을 열어주세요`
            : `최대 질문 턴(${this.deps.maxTurns}) 초과 — plan 미완성`,
        );
        return;
      }
```
`controller.signal.throwIfAborted();` (ensureRepo 직후) 바로 다음, `const options = buildOptions({` 앞에:
```ts
      // 질문 세션: plan 기계장치(forceFinish 삼항식·handoff splice·near-miss reformat·harvest→postPlan)에
      // 진입하지 않는 조기 분기 — 스펙 §6 kind 가드 4곳을 한 번에 만족한다.
      if (isQuestion) {
        await this.runQuestionTurn(claim, controller, guard, poster);
        return;
      }
```

- [ ] **Step 6: `runQuestionTurn` 추가** (`runTurn` 메서드 아래, 클래스 안)

```ts
  /**
   * 질문 세션(Q&A) 턴 — relay → postQuestion 직행. 모델이 plan 형식 텍스트를 내놔도 그냥 답변이다
   * (`postPlan` 호출 경로가 이 메서드에 없다). 옵션은 sessionKind:'QUESTION' (superpowers 미로드, default-deny).
   */
  private async runQuestionTurn(
    claim: InterviewClaimResponse,
    controller: AbortController,
    guard: CostGuard,
    poster: ActivityPoster,
  ): Promise<void> {
    const onActivity = (e: ActivityInput) => poster.push(e);
    const stream: AsyncIterable<SdkMessage> = this.query({
      prompt: questionPromptFor(claim),
      options: buildOptions({
        superpowersPluginPath: this.deps.superpowersPluginPath,
        workDir: claim.workDir,
        claudeCliPath: this.deps.claudeCliPath,
        claudeSessionId: claim.claudeSessionId,
        mcpsExtra: claim.mcpsExtra,
        mcpsBase: this.deps.mcpsBase,
        model: claim.model,
        effort: claim.effort,
        abortController: controller,
        sessionKind: 'QUESTION',
      }),
    });
    const result = await relay(stream, { onActivity, workDir: claim.workDir });
    guard.add(result.costUsd);
    // trailing 활동 배치가 답변보다 늦게 도착하지 않도록 확정 POST 전에 큐를 비운다 (인터뷰 경로와 동일).
    await poster.stop();
    await this.client.postQuestion(claim.sessionId, {
      content: result.assistantText,
      claudeSessionId: result.sessionId ?? claim.claudeSessionId ?? '',
      kind: 'question',
      costUsd: result.costUsd,
    });
  }
```

- [ ] **Step 7: 전체 테스트 + 타입체크**

Run: `npx vitest run && npm run build`
Expected: 전부 PASS(기존 인터뷰 테스트 회귀 없음), tsc 에러 없음. `noUnusedLocals`에 걸리는 변수가 없는지 확인.

- [ ] **Step 8: 커밋**

```bash
git add netismaker-interview-service/src/runner/interviewRunner.ts netismaker-interview-service/test/fixtures/claims.ts netismaker-interview-service/test/interviewRunner.test.ts
git commit -m "feat(interview-service): 질문 세션 러너 조기 분기 — Q&A 킥오프, plan 경로 미진입, 중립 fail 문구"
```

---

### Task 8: frontend — 상태 라벨 kind 분기 + SSE basePath

**Files:**
- Modify: `frontend/composables/interviewLabels.ts`
- Modify: `frontend/composables/useInterviewStream.ts` (`streamUrl`, `open`)
- Test: `frontend/composables/interviewLabels.spec.ts` (신규), `frontend/composables/useInterviewStream.spec.ts` (추가)

**Interfaces:**
- Produces: `export type SessionKind = 'INTERVIEW' | 'QUESTION'`; `QUESTION_STATUS_LABELS`; `interviewStatusLabel(name, kind = 'INTERVIEW')`; `useInterviewStream().open(id, apiBase = '/api/interviews')`. Task 9가 둘 다 사용.

- [ ] **Step 1: 실패하는 라벨 테스트 작성** — `frontend/composables/interviewLabels.spec.ts` (신규)

```ts
import { describe, it, expect } from 'vitest'
import {
  interviewStatusLabel,
  interviewStatusChip,
  QUESTION_STATUS_LABELS,
  INTERVIEW_STATUS_LABELS,
} from './interviewLabels'

describe('interviewLabels — kind 분기 (스펙 2026-08-30 §7 표)', () => {
  it('QUESTION 라벨 표 (같은 enum, 문맥만 다름)', () => {
    expect(interviewStatusLabel('QUEUED', 'QUESTION')).toBe('답변 대기중')
    expect(interviewStatusLabel('RUNNING', 'QUESTION')).toBe('답변 중')
    expect(interviewStatusLabel('AWAITING_INPUT', 'QUESTION')).toBe('답변 완료')
    expect(interviewStatusLabel('CANCELLED', 'QUESTION')).toBe('종료됨')
    expect(interviewStatusLabel('EXPIRED', 'QUESTION')).toBe('만료됨')
    expect(interviewStatusLabel('FAILED', 'QUESTION')).toBe('실패')
  })

  it('kind 생략 → 인터뷰 라벨 (기존 호출부 무변경)', () => {
    expect(interviewStatusLabel('QUEUED')).toBe('대기 중')
    expect(interviewStatusLabel('CANCELLED')).toBe('취소됨')
    expect(interviewStatusLabel(null)).toBe('')
    expect(interviewStatusLabel('WHATEVER', 'QUESTION')).toBe('WHATEVER')
  })

  it('두 표는 같은 키 집합을 가진다 (enum 추가 시 동시 갱신 강제)', () => {
    expect(Object.keys(QUESTION_STATUS_LABELS).sort()).toEqual(Object.keys(INTERVIEW_STATUS_LABELS).sort())
  })

  it('칩 색상은 kind 무관', () => {
    expect(interviewStatusChip('CANCELLED')).toEqual(['#f5f5f5', '#616161'])
  })
})
```

- [ ] **Step 2: 실패하는 스트림 테스트 작성** — `useInterviewStream.spec.ts`의 첫 describe(`connect`) 안에 추가

```ts
  it('opens EventSource under a custom apiBase (질문 세션: /api/questions)', () => {
    authStub.accessToken = 'jwt-xyz'
    const s = useInterviewStream()
    s.open(7, '/api/questions')
    expect(FakeEventSource.last().url).toBe('/api/questions/7/stream?access_token=jwt-xyz')
    s.close()
  })
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd /Users/micthebick/IdeaProjects/netisMaker/frontend && npx vitest run composables/interviewLabels.spec.ts composables/useInterviewStream.spec.ts`
Expected: FAIL (`QUESTION_STATUS_LABELS` 없음 / URL이 `/api/interviews/7/...`).

- [ ] **Step 4: `interviewLabels.ts` 수정**

`INTERVIEW_STATUS_LABELS` 아래에 추가:
```ts
// 세션 종류 — 서버 InterviewKind. 프론트는 라벨/경로 분기에만 쓴다.
export type SessionKind = 'INTERVIEW' | 'QUESTION'

// 질문 세션(Q&A) 표기 — 같은 enum, 문맥만 다르다 (스펙 2026-08-30 §7 표).
// PLAN_READY/REGISTERED는 질문 세션에서 도달 불가 — 방어적으로만 둔다.
export const QUESTION_STATUS_LABELS: Record<InterviewStatus, string> = {
  QUEUED: '답변 대기중',
  RUNNING: '답변 중',
  AWAITING_INPUT: '답변 완료',
  PLAN_READY: '플랜 완료',
  REGISTERED: '등록됨',
  CANCELLED: '종료됨',
  EXPIRED: '만료됨',
  FAILED: '실패',
}
```
`interviewStatusLabel`을 다음으로 교체:
```ts
// 서버 statusName(string)을 안전하게 라벨로 — 알 수 없는 값은 그대로 노출. kind로 문맥 표기 분기.
export function interviewStatusLabel(
  name: string | null | undefined,
  kind: SessionKind = 'INTERVIEW',
): string {
  if (!name) return ''
  const table = kind === 'QUESTION' ? QUESTION_STATUS_LABELS : INTERVIEW_STATUS_LABELS
  return (table as Record<string, string>)[name] ?? name
}
```

- [ ] **Step 5: `useInterviewStream.ts` 수정**

`let sessionId: number | null = null` 아래에 `let basePath = '/api/interviews'` 추가. `streamUrl`을:
```ts
  function streamUrl(id: number): string {
    return `${basePath}/${id}/stream?access_token=${encodeURIComponent(
      auth.accessToken as string,
    )}`
  }
```
`open`을:
```ts
  // apiBase: 질문 세션은 '/api/questions' (스펙 2026-08-30 §7). 재연결(onError→connect)에서도 유지되도록 클로저에 보관.
  function open(id: number, apiBase = '/api/interviews') {
    sessionId = id
    basePath = apiBase
    error.value = null
    if (!auth.accessToken) {
      error.value = '인증 토큰이 없습니다'
      connState.value = 'closed'
      return
    }
    connect()
  }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `npx vitest run composables/interviewLabels.spec.ts composables/useInterviewStream.spec.ts`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add frontend/composables/interviewLabels.ts frontend/composables/interviewLabels.spec.ts frontend/composables/useInterviewStream.ts frontend/composables/useInterviewStream.spec.ts
git commit -m "feat(frontend): 상태 라벨 kind 분기 + SSE 스트림 apiBase 인자"
```

---

### Task 9: frontend — `InterviewPanel` `kind` prop (경로 5곳·설계 컬럼 미렌더·문구)

**Files:**
- Modify: `frontend/components/InterviewPanel.vue`
- Test: `frontend/components/InterviewPanel.spec.ts`

**Interfaces:**
- Consumes: `interviewStatusLabel(name, kind)`, `SessionKind`, `stream.open(id, apiBase)` (Task 8); 서버 `/api/questions/{id}`, `/ask`, `/close`, `/stream` (Task 5).
- Produces: prop `kind?: SessionKind` (기본 INTERVIEW — `pages/tasks/[id].vue` 호출부 무변경). `data-test` 속성은 그대로(`send-answer`, `cancel-interview`, `cancel-confirm`, `cancel-keep`, `confirm`).

- [ ] **Step 1: 실패하는 테스트 작성** — `InterviewPanel.spec.ts`

`mountPanel` 헬퍼에 props 인자 추가:
```ts
async function mountPanel(
  sessionId = 5,
  snapshot: any = { statusName: null, turns: [], plan: null },
  props: Record<string, unknown> = {},
) {
  authStub.accessToken = 'jwt'
  useApiMock.mockResolvedValueOnce(snapshot) // onMounted의 GET /{id}가 소비
  const w = mount(InterviewPanel, { props: { sessionId, ...props } })
  await flushPromises() // 마운트 스냅샷 GET 해소 + hydrate 완료
  return w
}
```
파일 끝에 describe 추가:
```ts
describe('InterviewPanel — kind=QUESTION (스펙 2026-08-30 §7)', () => {
  afterEach(() => {
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  const answered = {
    statusName: 'AWAITING_INPUT',
    turns: [{ seq: 1, role: 'assistant', kind: 'question', content: 'AuthController에서 처리합니다' }],
    plan: null,
  }

  it('snapshot GET과 SSE를 /api/questions 아래로 연다', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/9')
    expect(FakeEventSource.last().url).toBe('/api/questions/9/stream?access_token=jwt')
    w.unmount()
  })

  it('설계·플랜 컬럼/탭/확정 버튼을 렌더하지 않고 대화 컬럼만 남긴다', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    expect(w.find('.design-col').exists()).toBe(false)
    expect(w.find('.interview-tabs').exists()).toBe(false)
    expect(w.find('[data-test="confirm"]').exists()).toBe(false)
    expect(w.find('.chat-col').exists()).toBe(true)
    expect(w.text()).toContain('AuthController에서 처리합니다')
    w.unmount()
  })

  it('질문 문맥 라벨/버튼: 답변 완료 · 추가 질문 · 세션 종료', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    expect(w.text()).toContain('답변 완료')
    expect(w.find('[data-test="send-answer"]').text()).toContain('추가 질문')
    expect(w.find('[data-test="cancel-interview"]').text()).toContain('세션 종료')
    expect(w.find('textarea').attributes('placeholder')).toBe('추가 질문을 입력하세요…')
    w.unmount()
  })

  it('추가 질문은 POST /api/questions/{id}/ask 로 (replyToSeq = 마지막 답변 seq)', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    await w.find('textarea').setValue('토큰 검증은요?')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/9/ask', {
      method: 'POST',
      body: { answer: '토큰 검증은요?', replyToSeq: 1 },
    })
    expect(w.text()).toContain('토큰 검증은요?')
    w.unmount()
  })

  it('턴 상한 400은 경고 토스트로 안내하고 입력을 지우지 않는다', async () => {
    const w = await mountPanel(9, answered, { kind: 'QUESTION' })
    await w.find('textarea').setValue('또?')
    useApiMock.mockRejectedValueOnce({ statusCode: 400, data: { message: '최대 문답 수(10)에 도달했습니다' } })
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('또?')
    expect(document.body.textContent).toContain('최대 문답 수')
    w.unmount()
  })

  it('세션 종료는 확인 다이얼로그 후 POST /api/questions/{id}/close', async () => {
    const w = await mountPanel(7, answered, { kind: 'QUESTION' })
    await w.find('[data-test="cancel-interview"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('질문 세션을 종료할까요?')
    ;(document.querySelector('[data-test="cancel-confirm"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/7/close', { method: 'POST' })
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })

  it('kind 미지정 → 기존 인터뷰 경로/컬럼 유지', async () => {
    const w = await mountPanel(9, answered)
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9')
    expect(w.find('.design-col').exists()).toBe(true)
    expect(w.find('[data-test="send-answer"]').text()).toContain('전송')
    w.unmount()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run components/InterviewPanel.spec.ts`
Expected: QUESTION describe FAIL (경로가 `/api/interviews/...`, design-col 존재 등). 기존 테스트는 PASS.

- [ ] **Step 3: `<script setup>` 수정**

(a) 임포트 `import { INTERVIEW_STATUS_LABELS } from '~/composables/interviewLabels'` →
```ts
import { interviewStatusLabel, type SessionKind } from '~/composables/interviewLabels'
```
(b) props에 `kind?: SessionKind` 추가:
```ts
const props = defineProps<{
  sessionId: number
  model?: string
  effort?: string
  readonly?: boolean
  /** 'QUESTION'이면 Q&A 모드 — /api/questions 경로, 설계·플랜 컬럼 미렌더, 질문 문맥 문구 (스펙 2026-08-30 §7). */
  kind?: SessionKind
}>()
```
(c) `const stream = useInterviewStream()` 위에:
```ts
const isQuestion = computed(() => props.kind === 'QUESTION')
const apiBase = computed(() => (isQuestion.value ? '/api/questions' : '/api/interviews'))
// 문맥 문구 — 인터뷰/질문 분기를 한 곳에 모은다.
const ui = computed(() =>
  isQuestion.value
    ? {
        send: '추가 질문',
        placeholder: '추가 질문을 입력하세요…',
        cancel: '세션 종료',
        cancelTitle: '질문 세션을 종료할까요?',
        cancelBody: '종료 후에는 추가 질문을 할 수 없습니다. 지금까지의 문답은 읽기 전용으로 남습니다.',
        cancelConfirm: '종료하기',
        preparing: '답변을 준비 중입니다…',
        starting: '질문 세션을 시작합니다…',
        expired: '세션이 만료되었습니다. 새 질문 세션을 열어 주세요.',
        cancelled: '질문 세션이 종료되었습니다.',
        failed: '답변 실패',
      }
    : {
        send: '전송',
        placeholder: '답변을 입력하세요…',
        cancel: '대화 취소',
        cancelTitle: '진행 중인 분석을 취소할까요?',
        cancelBody: '지금까지의 대화와 분석 진행 상황이 사라집니다. 작업은 등록되지 않습니다.',
        cancelConfirm: '취소하기',
        preparing: '첫 질문을 준비 중입니다…',
        starting: '인터뷰를 시작합니다…',
        expired: '세션이 만료되었습니다. 다시 인터뷰를 시작해 주세요.',
        cancelled: '인터뷰가 취소되었습니다.',
        failed: '인터뷰 실패',
      },
)
```
(d) `statusLabel`을:
```ts
const statusLabel = computed(() =>
  status.value ? interviewStatusLabel(status.value, props.kind ?? 'INTERVIEW') : '연결 중',
)
```
(e) `sendAnswer`의 `useApi(\`/api/interviews/${props.sessionId}/answer\`, {` →
```ts
    await useApi(`${apiBase.value}/${props.sessionId}/${isQuestion.value ? 'ask' : 'answer'}`, {
```
그리고 catch 블록의 `if (st === 409) {...} else {...}` 를:
```ts
    if (st === 409) {
      $q.notify({ type: 'warning', message: '세션이 만료되었거나 이미 처리된 답변입니다' })
    } else if (st === 400) {
      // 질문 세션 문답 상한 등 — 서버 메시지 그대로, 입력은 유지
      $q.notify({ type: 'warning', message: e?.data?.message ?? '요청이 거부되었습니다' })
    } else {
      $q.notify({ type: 'negative', message: e?.data?.message ?? '답변 전송 실패' })
    }
```
(f) `cancelInterview`의 `useApi(\`/api/interviews/${props.sessionId}/cancel\`, { method: 'POST' })` →
```ts
    await useApi(`${apiBase.value}/${props.sessionId}/${isQuestion.value ? 'close' : 'cancel'}`, { method: 'POST' })
```
(g) `onMounted`의 `useApi<InterviewSnapshot>(\`/api/interviews/${props.sessionId}\`)` → `useApi<InterviewSnapshot>(\`${apiBase.value}/${props.sessionId}\`)`, `stream.open(props.sessionId)` → `stream.open(props.sessionId, apiBase.value)`.
(`confirm()`의 `/api/interviews/.../confirm`은 그대로 — QUESTION에서는 버튼 자체가 미렌더.)

- [ ] **Step 4: 템플릿 수정**

- 배너 3개 문구: `세션이 만료되었습니다. 다시 인터뷰를 시작해 주세요.` → `{{ ui.expired }}`, `인터뷰가 취소되었습니다.` → `{{ ui.cancelled }}`, `인터뷰 실패: {{ error ?? ... }}` → `{{ ui.failed }}: {{ error ?? '알 수 없는 오류가 발생했습니다' }}`.
- `data-test="cancel-interview"` 버튼의 `label="대화 취소"` → `:label="ui.cancel"`.
- 좁은 화면 탭 `<q-tabs v-model="activeTab" class="lt-md text-primary interview-tabs" ...>`에 `v-if="!isQuestion"` 추가.
- 빈 상태 문구: `첫 질문을 준비 중입니다…` → `{{ ui.preparing }}`, `인터뷰를 시작합니다…` → `{{ ui.starting }}`.
- 입력창 `placeholder="답변을 입력하세요…"` → `:placeholder="ui.placeholder"`; `data-test="send-answer"`의 `label="전송"` → `:label="ui.send"`.
- `<q-separator vertical class="gt-sm" />` → `<q-separator v-if="!isQuestion" vertical class="gt-sm" />`.
- 우측 `<section class="design-col column no-wrap" ...>`에 `v-if="!isQuestion"` 추가 (confirm 버튼·designRequested 토글 포함 전체 미렌더 → `.chat-col`이 `flex: 1`이라 자연히 풀폭).
- 취소 다이얼로그: 제목 `진행 중인 분석을 취소할까요?` → `{{ ui.cancelTitle }}`, 본문 → `{{ ui.cancelBody }}`, `data-test="cancel-confirm"`의 `label="취소하기"` → `:label="ui.cancelConfirm"`.

- [ ] **Step 5: 테스트 통과 + 타입체크**

Run: `npx vitest run components/InterviewPanel.spec.ts && npm run typecheck`
Expected: 전부 PASS (기존 인터뷰 테스트 포함), vue-tsc 에러 없음.

- [ ] **Step 6: 커밋**

```bash
git add frontend/components/InterviewPanel.vue frontend/components/InterviewPanel.spec.ts
git commit -m "feat(frontend): InterviewPanel kind=QUESTION — /api/questions 경로, 설계·플랜 컬럼 미렌더, 질문 문맥 문구"
```

---

### Task 10: frontend — 질문 목록 페이지 + 질문하기 다이얼로그 + 네비게이션 탭

**Files:**
- Create: `frontend/pages/questions/index.vue`
- Modify: `frontend/layouts/default.vue` (`<q-route-tab to="/tasks" label="작업" />` 다음 줄)
- Test: `frontend/test/questions-index.spec.ts` (신규)

**Interfaces:**
- Consumes: `GET /api/questions?all=`, `POST /api/questions` (Task 5, body `{repoCatalogId, githubBranch, title, question, model, effort, mcpCatalogIds}`), `GET /api/repo-catalog`, `GET /api/repos/branches?repo=`, `McpPicker`(`v-model` number[]), `modelEffort.ts`, `interviewStatusLabel/Chip(name, 'QUESTION')`.
- Produces: 라우트 `/questions`; `defineExpose({ openCreate, draft, submit })` (테스트용).

- [ ] **Step 1: 실패하는 페이지 테스트 작성** — `frontend/test/questions-index.spec.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionsIndex from '../pages/questions/index.vue'
import { useApiMock } from './mocks/nuxt'

// navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 여기서 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

// QPage requires QLayout > QPageContainer ancestry; wrap for testing.
const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(QuestionsIndex) }),
      })
  },
})

const list = [
  {
    id: 3, title: '인증 흐름', githubRepo: 'a/b', githubBranch: 'main', repoAlias: 'Netis7.0',
    requesterId: 'user1', status: '인터뷰대기', statusName: 'QUEUED', model: 'claude-sonnet-5',
    effort: 'medium', totalCostUsd: 0.4, createdAt: '2026-08-30T01:00:00Z', updatedAt: '2026-08-30T01:00:00Z',
  },
]

function stubApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
    if (url === '/api/questions') return Promise.resolve(list)
    if (url === '/api/repo-catalog') return Promise.resolve([{ id: 1, alias: 'Netis7.0', ownerRepo: 'a/b', defaultBranch: 'main' }])
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    return Promise.resolve(null)
  })
}

describe('pages/questions/index — 목록 + 질문하기 (스펙 2026-08-30 §7)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    stubApi()
  })

  it('내 질문 목록을 질문 문맥 라벨로 렌더하고 클릭 시 상세로 이동한다', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', { params: { all: 'false' } })
    expect(w.text()).toContain('인증 흐름')
    expect(w.text()).toContain('답변 대기중')
    expect(w.text()).toContain('Netis7.0')
    await w.find('[data-test="question-row"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions/3')
    w.unmount()
  })

  it('질문하기: 모델/effort/MCP를 포함해 POST 후 상세로 이동', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    const page = w.findComponent(QuestionsIndex)
    const vm = page.vm as any
    vm.openCreate()
    await flushPromises()
    Object.assign(vm.draft, {
      repoCatalogId: 1, githubBranch: 'dev', title: '인증 흐름', question: '로그인은 어디서?',
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    await vm.submit()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: 1, githubBranch: 'dev', title: '인증 흐름', question: '로그인은 어디서?',
        model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
      },
    })
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    w.unmount()
  })

  it('429는 경고 토스트 (등록 실패로 처리하지 않음)', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    const vm = w.findComponent(QuestionsIndex).vm as any
    vm.openCreate()
    Object.assign(vm.draft, { repoCatalogId: 1, githubBranch: 'main', title: 't', question: 'q' })
    useApiMock.mockImplementationOnce(() =>
      Promise.reject({ statusCode: 429, data: { message: '동시에 진행할 수 있는 질문 세션 한도(3)를 초과했습니다' } }),
    )
    await vm.submit()
    await flushPromises()
    expect(document.body.textContent).toContain('한도(3)')
    expect(navigateToMock).not.toHaveBeenCalled()
    w.unmount()
  })
})
```
주의: 세 번째 테스트의 `mockImplementationOnce`는 `submit()`의 POST 직전에 걸어야 한다 — `openCreate()`가 `loadRepoCatalog()`(GET)를 먼저 호출하므로 위 순서(openCreate → draft 세팅 → mockImplementationOnce → submit)를 지킨다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/questions-index.spec.ts`
Expected: FAIL (모듈 없음).

- [ ] **Step 3: `pages/questions/index.vue` 생성**

```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'
import {
  MODEL_OPTIONS,
  DEFAULT_MODEL,
  DEFAULT_EFFORT,
  effortsForModel,
  coerceEffort,
} from '~/composables/modelEffort'
import McpPicker from '~/components/McpPicker.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'

definePageMeta({ layout: 'default' })

// GET /api/questions (QuestionSummaryResponse) 계약 미러
interface QuestionSummary {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  repoAlias: string | null
  requesterId: string
  status: string
  statusName: string
  model: string
  effort: string
  totalCostUsd: number | null
  createdAt: string
  updatedAt: string
}

const $q = useQuasar()
const auth = useAuthStore()
const all = ref(false)

const { data: questions, refresh } = useTaskPolling<QuestionSummary[]>(() =>
  useApi('/api/questions', { params: { all: String(all.value) } }),
)
// 배열이 아닌 응답(프록시 오류 페이지 등)도 빈 목록으로 — 렌더 중 throw 방지
const rows = computed(() => (Array.isArray(questions.value) ? questions.value : []))

function openQuestion(id: number) {
  navigateTo(`/questions/${id}`)
}

// ── 질문하기 다이얼로그 ─────────────────────────────────────────
// 레포/브랜치/제목/본문은 작업 등록 다이얼로그(pages/tasks/index.vue) 패턴,
// 모델/effort/MCP는 승인 다이얼로그(ApproveDialog.vue) 패턴 — 승인 게이트가 없어 등록자가 여기서 정한다.
const showCreate = ref(false)
const draft = reactive({
  repoCatalogId: null as number | null,
  githubBranch: '',
  title: '',
  question: '',
  model: DEFAULT_MODEL,
  effort: DEFAULT_EFFORT,
  mcpCatalogIds: [] as number[],
})
const submitting = ref(false)
const effortOptions = computed(() => effortsForModel(draft.model))
watch(
  () => draft.model,
  (m) => {
    draft.effort = coerceEffort(m, draft.effort)
  },
)

interface RepoCatalogEntry {
  id: number
  alias: string
  ownerRepo: string | null
  defaultBranch: string | null
}
const repoCatalog = ref<RepoCatalogEntry[]>([])
const repoCatalogLoading = ref(false)
const repoOptions = computed(() =>
  repoCatalog.value.map((r) => ({ label: r.alias, value: r.id })),
)

async function loadRepoCatalog() {
  repoCatalogLoading.value = true
  try {
    const res = await useApi<RepoCatalogEntry[]>('/api/repo-catalog')
    repoCatalog.value = Array.isArray(res) ? res : []
  } catch {
    repoCatalog.value = []
  } finally {
    repoCatalogLoading.value = false
  }
}

type RepoStatus = 'empty' | 'loading' | 'ok' | 'notfound' | 'error'
const repoStatus = ref<RepoStatus>('empty')
const repoStatusMsg = ref('')
interface BranchEntry {
  name: string
  sha: string
}
const branches = ref<BranchEntry[]>([])
const defaultBranch = ref<string | null>(null)
const branchOptions = computed(() =>
  branches.value.map((b) => ({
    label: b.name === defaultBranch.value ? `${b.name} (기본)` : b.name,
    value: b.name,
  })),
)
const filteredBranchOptions = ref<{ label: string; value: string }[]>([])
let inflightRepo = '' // 응답 도착 시 최신 입력과 일치하는지 가드

function resetBranchState() {
  repoStatus.value = 'empty'
  repoStatusMsg.value = ''
  branches.value = []
  defaultBranch.value = null
  filteredBranchOptions.value = []
  draft.githubBranch = ''
}

async function loadBranches(repo: string) {
  inflightRepo = repo
  repoStatus.value = 'loading'
  repoStatusMsg.value = '브랜치 불러오는 중...'
  try {
    const res = await useApi<{
      repo: string
      defaultBranch: string | null
      branches: BranchEntry[]
    }>('/api/repos/branches', { params: { repo } })
    if (inflightRepo !== repo) return
    branches.value = res.branches ?? []
    defaultBranch.value = res.defaultBranch
    filteredBranchOptions.value = branchOptions.value
    draft.githubBranch = res.defaultBranch ?? (res.branches?.[0]?.name ?? '')
    repoStatus.value = 'ok'
    repoStatusMsg.value = `${branches.value.length}개 브랜치 · 방금 동기화`
  } catch (e: any) {
    if (inflightRepo !== repo) return
    const status = e?.statusCode ?? e?.response?.status ?? e?.status
    branches.value = []
    defaultBranch.value = null
    draft.githubBranch = ''
    repoStatus.value = status === 404 ? 'notfound' : 'error'
    repoStatusMsg.value =
      e?.data?.message ?? (status === 404 ? '레포를 찾을 수 없거나 비공개 레포입니다' : '브랜치 동기화 실패')
  }
}

function onRepoSelected(catalogId: number | null) {
  resetBranchState()
  const entry = repoCatalog.value.find((r) => r.id === catalogId)
  if (!entry || !entry.ownerRepo) return
  loadBranches(entry.ownerRepo).then(() => {
    if (entry.defaultBranch) draft.githubBranch = entry.defaultBranch
  })
}

function onBranchFilter(val: string, update: (cb: () => void) => void) {
  update(() => {
    if (!val) {
      filteredBranchOptions.value = branchOptions.value
      return
    }
    const lc = val.toLowerCase()
    filteredBranchOptions.value = branchOptions.value.filter((o) => o.value.toLowerCase().includes(lc))
  })
}

function openCreate() {
  draft.repoCatalogId = null
  draft.githubBranch = ''
  draft.title = ''
  draft.question = ''
  draft.model = DEFAULT_MODEL
  draft.effort = DEFAULT_EFFORT
  draft.mcpCatalogIds = []
  resetBranchState()
  showCreate.value = true
  loadRepoCatalog()
}

const canSubmit = computed(
  () =>
    !submitting.value &&
    draft.repoCatalogId !== null &&
    !!draft.githubBranch &&
    !!draft.title.trim() &&
    !!draft.question.trim(),
)

async function submit() {
  submitting.value = true
  try {
    const created = await useApi<{ id: number }>('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: draft.repoCatalogId,
        githubBranch: draft.githubBranch,
        title: draft.title,
        question: draft.question,
        model: draft.model,
        effort: draft.effort,
        mcpCatalogIds: draft.mcpCatalogIds,
      },
    })
    $q.notify({ type: 'positive', message: '질문이 등록되었습니다 — 답변을 준비합니다' })
    showCreate.value = false
    await navigateTo(`/questions/${created.id}`)
  } catch (e: any) {
    const st = e?.statusCode ?? e?.response?.status ?? e?.status
    // 429(활성 세션 상한)는 실패가 아니라 안내 — 경고 톤.
    $q.notify({ type: st === 429 ? 'warning' : 'negative', message: e?.data?.message ?? '질문 등록 실패' })
  } finally {
    submitting.value = false
  }
}

function fmt(iso: string) {
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' })
}

// 테스트에서 q-select 조작 대신 직접 호출 (McpPicker.toggle 선례)
defineExpose({ openCreate, draft, submit })
</script>

<template>
  <q-page padding>
    <div class="row items-center q-mb-md">
      <div class="text-h5">질문</div>
      <q-space />
      <q-toggle
        v-if="auth.isAdmin"
        v-model="all"
        label="전체 보기"
        data-test="all-toggle"
        @update:model-value="refresh"
      />
      <q-btn class="q-ml-md" color="primary" icon="help_outline" label="질문하기" @click="openCreate" />
    </div>

    <q-list bordered separator>
      <q-item v-if="rows.length === 0">
        <q-item-section class="text-grey">아직 질문이 없습니다. 레포에 대해 궁금한 점을 물어보세요.</q-item-section>
      </q-item>
      <q-item
        v-for="q in rows"
        :key="q.id"
        clickable
        data-test="question-row"
        @click="openQuestion(q.id)"
      >
        <q-item-section>
          <q-item-label>{{ q.title }}</q-item-label>
          <q-item-label caption>
            {{ q.repoAlias ?? q.githubRepo }} · {{ q.githubBranch }}
            <span v-if="all"> · {{ q.requesterId }}</span>
          </q-item-label>
        </q-item-section>
        <q-item-section side>
          <div class="row items-center q-gutter-xs">
            <q-chip dense size="sm" outline icon="smart_toy" :label="q.model" />
            <q-chip
              dense
              size="sm"
              :style="{
                backgroundColor: interviewStatusChip(q.statusName)[0],
                color: interviewStatusChip(q.statusName)[1],
              }"
              :label="interviewStatusLabel(q.statusName, 'QUESTION')"
            />
          </div>
          <q-item-label caption class="q-mt-xs">{{ fmt(q.updatedAt) }}</q-item-label>
        </q-item-section>
      </q-item>
    </q-list>

    <!-- 질문하기 다이얼로그 -->
    <q-dialog v-model="showCreate" persistent>
      <q-card style="min-width: 560px">
        <q-card-section>
          <div class="text-h6">질문하기</div>
          <div class="text-caption text-grey-8">
            선택한 레포를 읽고 답합니다. 코드는 수정되지 않으며, 구현이 필요하면 작업 등록을 이용하세요.
          </div>
        </q-card-section>
        <q-card-section class="q-gutter-md">
          <q-select
            v-model="draft.repoCatalogId"
            :options="repoOptions"
            :loading="repoCatalogLoading"
            label="레포 (별칭 선택)"
            outlined
            dense
            emit-value
            map-options
            autofocus
            data-test="repo-select"
            :hint="repoCatalog.length === 0 ? '등록된 레포 없음 — 관리자에게 문의' : '관리자가 등록한 레포 중 선택'"
            @update:model-value="onRepoSelected"
          >
            <template #no-option>
              <q-item>
                <q-item-section class="text-grey">등록된 레포가 없습니다</q-item-section>
              </q-item>
            </template>
          </q-select>

          <q-select
            v-model="draft.githubBranch"
            :options="filteredBranchOptions"
            :disable="repoStatus !== 'ok'"
            label="브랜치"
            outlined
            dense
            use-input
            input-debounce="0"
            emit-value
            map-options
            :hint="
              repoStatus === 'ok'
                ? '입력해서 검색할 수 있습니다'
                : repoStatus === 'notfound' || repoStatus === 'error'
                  ? repoStatusMsg
                  : '레포 선택 후 브랜치 선택 가능'
            "
            @filter="onBranchFilter"
          >
            <template #no-option>
              <q-item>
                <q-item-section class="text-grey">결과 없음</q-item-section>
              </q-item>
            </template>
          </q-select>

          <q-input v-model="draft.title" label="제목" outlined dense maxlength="500" />
          <q-input
            v-model="draft.question"
            label="질문"
            type="textarea"
            outlined
            autogrow
            rows="4"
            placeholder="예: 로그인 요청은 어느 컨트롤러가 처리하고 토큰은 어디서 검증하나요?"
          />

          <div class="row q-col-gutter-md">
            <q-select
              v-model="draft.model"
              :options="MODEL_OPTIONS"
              emit-value
              map-options
              outlined
              dense
              label="Claude 모델"
              class="col"
            />
            <q-select
              v-model="draft.effort"
              :options="effortOptions"
              emit-value
              map-options
              outlined
              dense
              label="Effort"
              class="col"
              :hint="draft.model === 'claude-haiku-4-5' ? 'Haiku는 low/medium/high만 지원' : ''"
            />
          </div>
          <McpPicker v-model="draft.mcpCatalogIds" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="취소" @click="showCreate = false" />
          <q-btn
            unelevated
            color="primary"
            icon="send"
            label="질문 등록"
            :loading="submitting"
            :disable="!canSubmit"
            @click="submit"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>
```

- [ ] **Step 4: 네비게이션 탭** — `layouts/default.vue`의 `<q-route-tab to="/tasks" label="작업" />` 바로 다음 줄에

```vue
          <q-route-tab to="/questions" label="질문" />
```
(`v-if` 없음 — 모든 인증 사용자. `middleware/auth.global.ts`는 `/admin` prefix만 관리자 제한이라 변경 불필요.)

- [ ] **Step 5: 테스트 통과 + 타입체크**

Run: `npx vitest run test/questions-index.spec.ts && npm run typecheck`
Expected: PASS. (`McpPicker`가 마운트 시 `GET /api/mcp-catalog`를 호출하므로 stubApi가 `[]`를 돌려준다.)

- [ ] **Step 6: 커밋**

```bash
git add frontend/pages/questions/index.vue frontend/layouts/default.vue frontend/test/questions-index.spec.ts
git commit -m "feat(frontend): 질문 목록 페이지 + 질문하기 다이얼로그(모델/effort/MCP 선택) + 질문 탭"
```

---

### Task 11: frontend — 질문 상세 페이지 + 문서 + 수동 스모크

**Files:**
- Create: `frontend/pages/questions/[id].vue`
- Modify: `CLAUDE.md` (netisMaker 루트)
- Test: `frontend/test/questions-detail.spec.ts` (신규)

**Interfaces:**
- Consumes: `GET /api/questions/{id}` (`InterviewResponse` + `kind`, `totalCostUsd`), `InterviewPanel` `kind="QUESTION"` (Task 9).
- Produces: 라우트 `/questions/:id`.

- [ ] **Step 1: 실패하는 상세 페이지 테스트 작성** — `frontend/test/questions-detail.spec.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionDetail from '../pages/questions/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(QuestionDetail) }),
      })
  },
})

const detail = {
  id: 3, title: '인증 흐름', githubRepo: 'a/b', githubBranch: 'main', status: '입력대기',
  statusName: 'AWAITING_INPUT', model: 'claude-sonnet-5', effort: 'medium', kind: 'QUESTION',
  totalCostUsd: 0.42, turns: [{ seq: 1, role: 'assistant', kind: 'question', content: 'AuthController입니다' }],
  plan: null,
}

describe('pages/questions/[id] — 상세 (스펙 2026-08-30 §7)', () => {
  beforeEach(() => {
    authStub.accessToken = 'jwt'
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3' ? Promise.resolve(detail) : Promise.resolve(null),
    )
  })

  it('헤더(제목·레포·비용)를 그리고 InterviewPanel을 kind=QUESTION으로 마운트한다', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    expect(w.text()).toContain('인증 흐름')
    expect(w.text()).toContain('a/b · main')
    expect(w.text()).toContain('0.42')
    const panel = w.findComponent({ name: 'InterviewPanel' })
    expect(panel.exists()).toBe(true)
    expect(panel.props('kind')).toBe('QUESTION')
    expect(panel.props('sessionId')).toBe(3)
    expect(w.text()).toContain('AuthController입니다')
    w.unmount()
  })

  it('조회 실패(403/404)면 목록으로 돌려보낸다', async () => {
    useApiMock.mockImplementation(() => Promise.reject({ statusCode: 403, data: { message: '권한이 없습니다' } }))
    const w = mount(PageWrapper)
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })
})
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `npx vitest run test/questions-detail.spec.ts`
Expected: FAIL (모듈 없음).

- [ ] **Step 3: `pages/questions/[id].vue` 생성**

```vue
<script setup lang="ts">
import { useQuasar } from 'quasar'
import InterviewPanel from '~/components/InterviewPanel.vue'
import { interviewStatusLabel, interviewStatusChip } from '~/composables/interviewLabels'

definePageMeta({ layout: 'default' })

// GET /api/questions/{id} (InterviewResponse) 중 이 화면이 쓰는 부분집합
interface QuestionDetail {
  id: number
  title: string
  githubRepo: string
  githubBranch: string
  statusName: string
  model: string | null
  effort: string | null
  totalCostUsd: number | null
}

const route = useRoute()
const $q = useQuasar()
const sessionId = computed(() => Number(route.params.id))
const detail = ref<QuestionDetail | null>(null)

async function load() {
  try {
    detail.value = await useApi<QuestionDetail>(`/api/questions/${sessionId.value}`)
  } catch (e: any) {
    // 403(타인)/404(없거나 인터뷰 세션) — 목록으로
    $q.notify({ type: 'negative', message: e?.data?.message ?? '질문을 불러오지 못했습니다' })
    await navigateTo('/questions')
  }
}
onMounted(load)

// InterviewPanel의 close = 종료/나중에 — 상태 갱신 후 화면 유지 (읽기 전용 열람 가능)
function onPanelClose() {
  load()
}

// 템플릿에서 navigateTo(자동 임포트)를 직접 부르면 vitest(setup.ts 전역 주입)에서 _ctx.navigateTo가 없다 — 함수로 감싼다.
function goList() {
  navigateTo('/questions')
}
</script>

<template>
  <q-page padding>
    <div class="row items-center q-mb-md">
      <q-btn flat dense round icon="arrow_back" @click="goList" />
      <div class="text-h5 q-ml-sm">{{ detail?.title ?? '질문' }}</div>
      <q-space />
      <template v-if="detail">
        <q-chip
          dense
          size="sm"
          :style="{
            backgroundColor: interviewStatusChip(detail.statusName)[0],
            color: interviewStatusChip(detail.statusName)[1],
          }"
          :label="interviewStatusLabel(detail.statusName, 'QUESTION')"
        />
        <q-chip dense size="sm" outline icon="folder" :label="`${detail.githubRepo} · ${detail.githubBranch}`" />
        <q-chip
          v-if="detail.totalCostUsd != null"
          dense
          size="sm"
          outline
          icon="paid"
          :label="`누적 비용 ${Number(detail.totalCostUsd).toFixed(2)}`"
        />
      </template>
    </div>

    <q-card flat bordered>
      <q-card-section class="q-pa-none">
        <InterviewPanel
          :session-id="sessionId"
          kind="QUESTION"
          :model="detail?.model ?? undefined"
          :effort="detail?.effort ?? undefined"
          @close="onPanelClose"
        />
      </q-card-section>
    </q-card>
  </q-page>
</template>
```

- [ ] **Step 4: 테스트 통과 + 전체 프론트 게이트**

Run: `npx vitest run test/questions-detail.spec.ts && npm test && npm run typecheck && npm run build`
Expected: 전부 PASS, vue-tsc 에러 없음, nuxi build 성공.

- [ ] **Step 5: `CLAUDE.md`(netisMaker) 갱신**

(a) "핵심 설계 제약" 목록 끝에:
```markdown
- **질문 세션(Q&A)**: `InterviewSession.kind=QUESTION`. 승인 없이 등록 즉시 큐 진입, USER+ADMIN 공용(본인+관리자 조회). 인터뷰 서비스는 QUESTION이면 superpowers 미로드 + **default-deny 도구 게이트**(Read/Grep/Glob/읽기전용 Bash/`mcp__*`만) + plan 경로 미진입(`postPlan` 없음). 서버는 `recordPlan`/`confirm`을 400으로 막는다. 스펙: `docs/superpowers/specs/2026-08-30-question-sessions-design.md`. **배포 순서: interview-service 먼저, API 나중** (구버전 러너가 QUESTION을 인터뷰로 처리해 방어 무력화).
```
(b) "작업 상태머신" 코드블록 아래에:
```markdown
질문 세션(kind=QUESTION, task 없음 — `/api/questions`):
```
[답변 대기중(QUEUED)] ──(claim)──→ [답변 중(RUNNING)] ──→ [답변 완료(AWAITING_INPUT)] ──(추가 질문)──→ [QUEUED]
[답변 완료] ──→ [종료됨(CANCELLED)] | [만료됨(EXPIRED)] | [실패(FAILED)]      ※ PLAN_READY/REGISTERED 도달 불가
```
- 상한: `app.question.max-active-per-user`(기본 3, 초과 429) · `app.question.max-qa-turns`(기본 10, `ask` 400). 불변식: `max-qa-turns` < 러너 `INTERVIEW_MAX_TURNS`(20).
```
(c) "자주 보는 코드" 표에 행 추가:
```markdown
| 질문 세션 등록/목록/ask/close (kind 가드) | `service/QuestionService.java`, `controller/QuestionController.java` |
| 질문 세션 러너 분기 (default-deny, Q&A 킥오프) | `netismaker-interview-service/src/runner/interviewRunner.ts` (`runQuestionTurn`), `src/sdk/permissions.ts` |
| 질문 목록/상세 화면 | `frontend/pages/questions/index.vue`, `frontend/pages/questions/[id].vue` (`InterviewPanel kind="QUESTION"`) |
```

- [ ] **Step 6: 수동 스모크 (머지 전, 스펙 §9)**

배포 순서대로 재기동 후 (`./scripts/start-all.sh` 일괄이면 자연 충족):
1. 일반 사용자 계정으로 로그인 → "질문" 탭 → 질문하기(레포 `Netis7.0`, sonnet/medium) → 상세로 이동, "답변 대기중" 배지.
2. 활동 버블(도구/thinking) 스트리밍 → 답변 도착 → "답변 완료".
3. interview-service 로그에서 해당 턴의 `allowedTools`/plugins 확인 불가 시 `.run/interview.log`에 canUseTool deny 메시지(`질문 세션에서는 ... 도구를 사용할 수 없습니다`)가 Write 시도 시 찍히는지 — 답변에 "이 파일을 수정해줘"로 추가 질문해 거부 안내 문구 확인.
4. "구현 계획을 작성해줘"로 추가 질문 → 작업 등록 안내 답변, 상태는 여전히 "답변 완료"(PLAN_READY 아님), 작업 목록에 새 작업 없음.
5. 세션 종료 → "종료됨", 읽기 전용 열람 가능. 목록에서 "종료됨" 칩.
6. 다른 일반 사용자 계정으로 해당 `/questions/{id}` 직접 접근 → 403 토스트 + 목록으로. 관리자 "전체 보기" 토글 → 보임.
7. 워커 체크아웃(`~/netis-maker/interviews/...` 해당 session workDir)에서 `git status` → clean.

스크린샷 1~2장을 워크스페이스 루트에 `netismaker-question-session-verified.png`로 저장(기존 `*-verified.png` 관례).

- [ ] **Step 7: 커밋**

```bash
git add frontend/pages/questions/[id].vue frontend/test/questions-detail.spec.ts CLAUDE.md
git commit -m "feat(frontend): 질문 상세 페이지 + docs: 질문 세션 상태머신·설정·배포 순서"
```

---

## 자체 점검 (스펙 ↔ 계획 대응)

| 스펙 | 태스크 |
|---|---|
| §2 등록 즉시 시작 / 등록자 모델·effort·MCP 선택 / 본인+관리자 / base MCP 주입 / 남용 가드 429 | Task 4(create·429·스냅샷), Task 5(ACL), Task 6(mcpServers 머지 유지), Task 10(피커) |
| §3 taskId-null 안전 | Task 3(recordQuestion 테스트 `taskRepo` 미호출), Task 5(`close_by_owner_..._touches_no_task`) |
| §4 V20 `kind` / `createQuestion` / currentPhase 미세팅 / PLAN_READY·REGISTERED 도달 불가 | Task 2, Task 3 |
| §5 API 표·응답코드(403/404/400/429) / 목록 신규 메서드 / 턴 상한 불변식 / 워커 계약 `kind` optional / 배포 순서 | Task 4·5, Task 6(`kind?`), Global Constraints·Task 11 docs |
| §6 러너 kind 가드 4곳 / 킥오프 계약 / default-deny / `allowedTools` Read·Grep·Glob만 / plugins [] / `buildCanUseTool(repoDir, kind)` / 스파이크 | Task 7(가드①~④ 테스트), Task 6, Task 1 |
| §7 탭 / 목록+다이얼로그 합성 / 상세 / 경로 5곳 / 설계 컬럼+탭 미렌더 / 문구 / 라벨 표 | Task 10, Task 11, Task 9, Task 8 |
| §8 만료·FAILED 표시 / 400·429 토스트 / 비용 칩 | Task 9(배너·400), Task 10(429), Task 11(비용 칩) |
| §9 테스트 목록 + 스모크 | 각 태스크 + Task 11 Step 6 |
| §10 범위 제외 | 첨부·retry·우선순위·공개조회·MCP 스크리닝 미구현 (의도) |

플레이스홀더 스캔: "TBD/TODO/적절히/나중에" 없음. 타입 일관성: `SessionKind`(types.ts ↔ interviewLabels.ts 동일 리터럴), `buildCanUseTool(repoDir, kind)`/`sessionKind`/`open(id, apiBase)`/`requireKind(id, kind)`/`QuestionService` 시그니처가 정의 태스크와 사용 태스크에서 일치함을 확인.
