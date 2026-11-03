# 간단 인터뷰(superpowers 켬/끔) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 승인 때 작업마다 superpowers 인터뷰를 끌 수 있게 하고, 끄면 질문을 많아야 한 번 모아 묻고 바로 정규 형식 계획을 내는 간단 인터뷰로 진행하며, 그 계획에는 "계획 고쳐 달라기"를 연다.

**Architecture:** 인터뷰 서비스(Task 1~3)는 claim의 `superpowersEnabled === false`일 때만 옵션·프롬프트·턴 흐름을 바꾸고, 칸이 없거나 true면 지금과 바이트까지 같다. API(Task 4~6)는 승인 입력의 값을 세션에 저장해 claim·응답에 싣고, PLAN_READY+끔 세션에만 답변(계획 고쳐 달라기)을 받는다. 프론트(Task 7~9)는 승인 토글·패널 입력·상세 칩을 더하며, 배포는 이 순서다(Task 10).

**Tech Stack:** TypeScript(인터뷰 서비스, Agent SDK, vitest) · Java 21 · Spring Boot 3 · JPA/Flyway(PostgreSQL `com`) · Mockito/JUnit 5 · Testcontainers · Nuxt 3 + Quasar(vitest, hydration smoke)

**Spec:** `docs/superpowers/specs/2026-11-03-interview-superpowers-toggle-design.md` 전부를 구현한다.
- 이 계획의 코드 줄 번호는 **작업 타입 계획을 적용하기 전** 기준이다. 코드는 #122 head `c87f82d`와 같다(작성 때 worktree HEAD `f0334b7`은 그 위에 문서 커밋만 더했다).
- 작업 타입 계획이 아래 파일을 먼저 고치므로 줄이 밀린다. 이 파일들에서는 줄 번호를 믿지 말고 이름(함수·필드·data-test)으로 찾는다.
  - 인터뷰 서비스: `src/types.ts`·`src/runner/interviewRunner.ts`·`test/interviewRunner.test.ts`(작업 타입 Task 4·13)
  - API: `InterviewClaimResponse.java`·`InterviewService.java`·`InterviewServiceTest.java`(Task 4·13), `InterviewWorkerController.java`(Task 13), `ApproveRequest.java`·`TaskServiceApproveDbTest.java`(Task 2), `TaskService.java`(Task 2·3), `TaskController.java`(Task 3), `TaskResponse.java`(Task 1)
  - 프론트: `ApproveDialog.vue`·`ApproveDialog.spec.ts`·`TaskDetailMobile.vue`(Task 6·17), `pages/tasks/[id].vue`·`scripts/hydration-smoke.mjs`(Task 6·17·20)
  - 문서: `CLAUDE.md`(Task 21)

경로 줄임: `M/`=`src/main/java/com/hamonsoft/netismaker/`, `T/`=`src/test/java/com/hamonsoft/netismaker/`, `R/`=`src/main/resources/`, `F/`=`frontend/`, `I/`=`netismaker-interview-service/`, `<W>`=이 기능 브랜치 worktree 루트의 절대 경로.

## Global Constraints

- **선행:** 작업 타입 PR(`docs/superpowers/plans/2026-11-03-task-type-db-write.md`)이 main에 병합된 뒤, 그 `origin/main` 위에 별도 브랜치 `feat/interview-superpowers-toggle`를 만들어 시작한다. 병합 전이면 시작하지 않는다.
  - 작업 타입 계획 Interfaces(Produces)의 이름을 그대로 쓴다.
    - Java: `TaskType`, `Task.getTaskType()`, `ApproveRequest(…, String taskType)`, `InterviewClaimResponse.withTaskType`·`withWritableDbConnections`
    - TS: `TaskTypeWire`, `kickoffLead`, `taskTypeGuide(taskType: unknown)`, `writableDbLine`, claim 칸 `taskType`·`writableDbConnections`
    - 시험 헬퍼: `kickoffOf(claim, db = noDb())`, `KICKOFF_GOLDEN`
    - 화면: data-test `approve-task-type`
- **사용자 확인됨(2026-11-03, "추천대로"):** 계획 고쳐 달라기 턴이 실패·만료하면 이미 나온 계획도 확정할 수 없게 된다(Task 6 "결정"). 이 계획은 설계 §4.1("기존 턴 상한·유휴 만료를 따른다")대로 완화하지 않는다. 사용자가 완화를 고르면 Task 6 뒤에 API Task를 하나 더한다(끔+계획 행이 있는 세션의 실패·만료를 PLAN_READY·플랜승인대기로 되돌림, 호출부 6곳의 SSE push 변경).
- **마이그레이션:** Task 4 시작 때 `R/db/migration`의 가장 큰 번호 + 1로 `V{n}__interview_superpowers.sql`을 만든다.
  - 예정 번호는 V40이다. 작업 타입 Part B가 `V40__db_write.sql`을 가져갔으면(`2026-11-03-task-type-db-write.md:23-25`) V41이 된다.
  - PR 병합 직전에 `origin/main`의 가장 큰 번호를 다시 본다. 그사이 같은 번호가 생겼으면 이 브랜치의 번호를 올린다. 체크섬은 main 병합 때 굳는다.
  - `ddl-auto: validate`(`R/application.yml:34`)이므로 엔티티 칸과 SQL은 같은 Task에서 바꾼다.
- **끔 판정:**
  - TS는 `claim.superpowersEnabled === false`일 때만, Java 요청은 `Boolean.FALSE.equals(req.superpowersEnabled())`일 때만 끔이다.
  - 칸이 없거나 null이거나 true면 켬이다. `refsCleared === true` 선례(`I/src/types.ts:165-169`)와 같다.
  - QUESTION 세션은 이 값과 무관하다.
- **켬은 바이트까지 그대로다.** 다음을 가드 시험으로 지킨다.
  - kickoff: `KICKOFF_GOLDEN`(`I/test/interviewRunner.test.ts:1189-1199`)과 `KICKOFF_FULL_GOLDEN`(첨부·DB 작업·쓰기 연결·DB 절·참조가 다 붙은 것, Task 2가 고치기 전 코드로 고정), resume == `lastAnswer`
  - `buildPlanReformatSplice()` 바이트(Task 2 골든), `buildOptions` 결과(Task 1), kickoff·handoff·near-miss relay 옵션(Task 3)
  - 계획 보고 바디(키 추가 없음), handoff·near-miss·force-finish·plan 파일 대체 판정, `recordPlan`의 design 턴 본문
  - 켬 세션 PLAN_READY 답변의 409와 그 메시지
  - 승인 바디(켬이면 `superpowersEnabled` 키 없음)
  - 패널 PLAN_READY의 입력창 막힘
- **문구:** 그대로 쓴다.
  - 이력: `인터뷰 방식: 간단(superpowers 끔)`, `계획 수정 요청 → 인터뷰 재개`
  - 토글 `superpowers 인터뷰`, 보조 문구 `간단 인터뷰 — 질문을 최대 한 번 모아서 묻고 바로 계획`, 칩 `간단 인터뷰`
  - 입력 안내 `계획에서 고칠 점을 적어 주세요 — 질문 없이 고친 계획을 다시 냅니다`, 버튼 `계획 고쳐 달라기`
- **워커(Java 구현·디자인·배포·종료)는 바꾸지 않는다.**
- **시험:** 작업 타입 계획 Global Constraints "시험"(`2026-11-03-task-type-db-write.md:54-63`)을 그대로 따른다. 다른 점은 CI 실행 확인이 이 계획 Task 10 Step 7이라는 것뿐이다.
- **명령 위치:** 셸 cwd가 다음 명령으로 이어지므로 Step 명령은 상대 `cd` 대신 `Set-Location <W>\…`(절대 경로)로 시작한다. Java는 `<W>`, 인터뷰 서비스는 `<W>\netismaker-interview-service`, 프론트는 `<W>\frontend`에서 돈다. PowerShell 5.1이라 `&&` 대신 `;`를 쓴다.
- **커밋:** 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. 질문 없이 첫 턴에 바로 계획이 나온 간단 인터뷰에 "계획 고쳐 달라기"를 보내는 경우. 같은 Claude 대화를 이어 고친 계획을 내야 한다. 새 kickoff로 처음부터 다시 쓰면서 고칠 점을 버리면 안 된다(Task 3 계획 보고 `claudeSessionId`, Task 6 저장과 CI 흐름).
2. 이미 답한 질문이 있는 세션의 플랜완료 화면에서 보내는 경우. replyToSeq가 이미 답한 질문의 seq이면 중복 no-op이 된다(`InterviewService.java:550-555`). 그러면 세션은 PLAN_READY 그대로이고 화면만 QUEUED에서 멈춘다. 이렇게 되면 안 된다(Task 8 `replyToSeq: null`, Task 6 null 수용).
3. 고쳐 달라는 턴(resume)이 채팅에 계획 없이 끝났는데 작업 폴더에 앞 턴의 plan 파일이 남아 있는 경우(`I/src/runner/planFiles.ts:12-16`). 고치기 전 계획이 새 계획으로 기록되면 안 된다(Task 3).
4. 계획을 여러 번 고쳐 assistant 턴이 force-finish 문턱(19, `I/src/config.ts:93`)에 닿은 경우. 그 턴의 고칠 점이 버려지면 안 된다(`interviewRunner.ts:608-609`, Task 3).
5. 비관리자가 작업 상세를 여는 경우(사본 `redactSecretValues`)와 claim DB 보강이 실패한 경우(사본 `withDb`, `InterviewWorkerController.java:79`). 끔 값이 사본에서 빠져 칩이 사라지거나 superpowers가 붙으면 안 된다(Task 5).

---

# 인터뷰 서비스 (먼저 배포)

### Task 1: `buildOptions`의 `superpowers` 입력

**Files:**
- Modify: `I/src/sdk/sessionOptions.ts:6-52`(입력), `:71-106`(머리 주석 — "'Skill' is whitelisted" 설명에 끔 예외 한 줄), `:116-155`
- Test: `I/test/sessionOptions.test.ts`

**Interfaces:**
- Produces: `SessionOptionsInput.superpowers?: boolean`
  - `false`일 때만 끔이다. 선택 입력이므로 공용 `base`(`:4-8`)와 38개 호출은 그대로 컴파일된다.
  - 끔(INTERVIEW)이면 다음 세 값만 바뀐다.
    - `plugins: []`
    - `allowedTools`: `['Read','Grep','Glob', ...mcp__ 와일드카드]` — `'Skill'`만 빠진다.
    - `disallowedTools: ['AskUserQuestion','Skill']`
  - 나머지(settingSources·strictMcpConfig·canUseTool·DB 서버 제외 규칙)는 같다. QUESTION이면 이 입력을 보지 않는다.

- [ ] **Step 1: 실패하는 시험 작성** — `describe('buildOptions — superpowers 끔 (스펙 2026-11-03 간단 인터뷰 §5-1)')`. `claudeSessionId`는 필수 입력이라(`sessionOptions.ts:11`) 모든 호출에 `claudeSessionId: null`을 넣는다(`base`에는 없다).

```ts
it('끔: 플러그인 없음, Skill은 사전 승인에서 빼고 목록에서도 뺀다', () => {
  const o = buildOptions({ ...base, claudeSessionId: null, superpowers: false, mcpsBase: { obsidian: { command: 'x' } } as any })
  expect(o.plugins).toEqual([]); expect(o.allowedTools).toEqual(['Read', 'Grep', 'Glob', 'mcp__obsidian'])
  expect(o.disallowedTools).toEqual(['AskUserQuestion', 'Skill']); expect(o.settingSources).toEqual([]); expect(o.strictMcpConfig).toBe(true) })
it('켬 가드: 생략·true는 지금과 같다')   // canUseTool·env 키를 뺀 나머지 buildOptions({ ...base, claudeSessionId: null }) toEqual buildOptions({ ...base, claudeSessionId: null, superpowers: true }); plugins toEqual [{type:'local', path: base.superpowersPluginPath}]; allowedTools[0] === 'Skill'; disallowedTools toEqual ['AskUserQuestion']
it('QUESTION은 superpowers 값과 무관')   // { ...base, claudeSessionId: null, sessionKind: 'QUESTION', superpowers: true|false } → plugins [], allowedTools [], disallowedTools ['AskUserQuestion']
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\netismaker-interview-service; npx vitest run test/sessionOptions.test.ts` → FAIL(끔 시험)
- [ ] **Step 3: 구현** — `const simple = !question && input.superpowers === false`를 `:116` 옆에 두고 세 값만 가른다.
  - **설계와 다름:** 설계 §5-1은 `allowedTools`에서 `'Skill'`을 빼라고만 했다. 여기서는 `disallowedTools`에도 넣는다.
    - `allowedTools`에서 빼면 사전 승인만 빠질 뿐, INTERVIEW 게이트는 Skill을 통과시킨다(`I/src/sdk/permissions.ts:497-499`).
    - 플러그인이 없을 때 Skill 도구가 안 뜨는지는 검증된 적이 없다(주석 `:82`, `:96`만 그렇게 주장한다).
    - 모델 목록에서 빼는 방법으로 검증된 것은 `disallowedTools`다(`:140-143` 선례).
- [ ] **Step 4: 통과 확인** — `Set-Location <W>\netismaker-interview-service; npx vitest run test/sessionOptions.test.ts; npx tsc --noEmit -p tsconfig.json` PASS(기존 `:55`·`:80`·`:225`·`:261` 포함). tsconfig가 `test/**`를 포함하므로(`tsconfig.json:18`) 시험 코드 타입 오류도 여기서 잡힌다.
- [ ] **Step 5: 커밋** — `feat(interview): buildOptions superpowers 끔 입력`

---

### Task 2: 간단 kickoff·resume 접두 (진행 방식 절)

**Files:**
- Create: `I/src/runner/interviewMode.ts`
- Modify: `I/src/runner/skillDispatch.ts:39-47`(형식 문구 추출), `I/src/runner/kickoffByType.ts`(작업 타입 Task 4), `I/src/types.ts:102-170`
- Modify: `I/src/runner/interviewRunner.ts:125-143`(`attachmentSection`), `:151-177`(`promptFor`)
- Test: `I/test/interviewMode.test.ts`(새), `I/test/skillDispatch.test.ts`, `I/test/kickoffByType.test.ts`, `I/test/interviewRunner.test.ts`
  - runner 시험은 `describe('작업 인터뷰 (스펙 2026-10-24 §5)')`(`:1160`) 안, `kickoffOf` 옆에 둔다. QUESTION 가드는 `capturing`이 있는 `:475` describe에 둔다.

**Interfaces:**
- Consumes: 작업 타입 Task 4 `kickoffLead`·`taskTypeGuide`, Task 13 `writableDbLine`
- Produces:
  - claim 칸: `InterviewClaimResponse.superpowersEnabled?: boolean | null`
  - `skillDispatch.ts`: `export const PLAN_FORMAT_RULE: string`
    - 값: `skillDispatch.ts:42-45`에서 `'반드시 다음 정규 형식을 지키세요(설명은 한국어): …'`부터 `'…이 구조라야 시스템이 완료를 인식합니다.'`까지를 이은 문자열(바이트 그대로, 끝 공백 없음).
    - `buildPlanReformatSplice()`는 `'지금까지의 논의를 바탕으로 최종 구현 계획을 한 번에 확정해 제시하세요. ' + PLAN_FORMAT_RULE + ' 구현/빌드/커밋은 하지 마세요(plan 문서만).'`가 된다. 바이트는 그대로다(Step 1 골든).
  - `interviewMode.ts` — 이 기능이 새로 만드는 "진행 방식 절"이다.
    - `superpowersOn(claim: Pick<InterviewClaimResponse,'superpowersEnabled'>): boolean` — `!== false`
    - `planningOnlyParagraph(superpowers: boolean): string`
      - 켬은 `interviewRunner.ts:163-167` 바이트 그대로다.
      - 끔은 켬 문단에서 ` (via brainstorming → writing-plans)`만 뺀 것이다.
    - `processLine(superpowers: boolean): string`
      - 켬은 `:172` 바이트 그대로다.
      - 끔은 아래 `SIMPLE_PROCESS`다.
    - `SIMPLE_RESUME_PREFIX: string`: `'이 답변(또는 계획 수정 요청)으로 질문은 끝입니다. 더 묻지 말고 이 내용을 반영해 정규 형식의 최종 계획을 내세요. ' + PLAN_FORMAT_RULE + '\n\n'`
  - `taskTypeGuide(taskType: unknown, superpowers = true): string`
    - 둘째 인자를 생략하면 작업 타입 Task 4 출력과 같다.
    - 끔이면 첫 "…부터 질문하세요." 문장만 바뀌고 나머지 문장(계획 내용 요구)은 같다.
      - FEATURE_CHANGE: `작업 타입: 기능 수정. 먼저 코드를 읽어 지금 어떻게 동작하는지 확인하고, 무엇을 어떻게 바꿀지와 바꾸면 안 되는 것(유지할 동작)도 자료·코드·DB로 먼저 확인하세요. 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요.`
      - BUG_FIX: `작업 타입: 버그 픽스. 어느 화면에서, 무엇을 했을 때, 어떤 오류가 뜨는지는 먼저 자료·코드·DB로 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요. 첨부(캡처·로그)가 있으면 …`(이하 켬과 같다)
      - DB_WORK: `작업 타입: DB 작업. 어떤 테이블·데이터를 어떻게 바꿀지는 먼저 자료·코드·DB로 확인하고, 그래도 알 수 없는 것만 한 번의 질문 묶음에 넣으세요. 계획에는 …`(이하 켬과 같다)
  - `attachmentSection(claim, superpowers = true)`(모듈 내부): 끔이면 `:136`의 `brainstorming 시작 전에 `만 `계획을 쓰기 전에 `로 바꾼다.
  - 시험 헬퍼(`'작업 인터뷰'` describe 안, `kickoffOf` 옆): `promptWith(claim: InterviewClaimResponse, opts: { db?: ReturnType<typeof dbOf>; prepared?: RefPrepared[] } = {}): Promise<string>` — 기존 `it` 안의 지역 함수 `promptOf`(`:1245`) 모양을 복제한다(`capturing` + `runnerWith(makeClient(), fakeQuery, preparer(prepared ?? []), { createDbMcp: vi.fn().mockReturnValue(db ?? noDb()) })`). `kickoffOf`는 참조 준비기가 `vi.fn()`이라 참조가 붙은 claim에 못 쓴다.

`SIMPLE_PROCESS`(정확한 문구, 줄 사이 `\n`, 끝 줄바꿈 없음):
```text
간단 인터뷰입니다 — superpowers 스킬은 쓰지 않습니다. 먼저 등록 설명·첨부·레포 코드·(연결돼 있으면) DB를 읽기 도구로 살펴보세요.
할 일과 범위가 분명하면 질문하지 말고 바로 위 정규 형식의 계획을 이 대화 본문에 쓰세요(파일로 저장하지 마세요).
꼭 사람이 정해야 하는 것이 있을 때만 그 질문들을 한 메시지에 번호를 붙여 모두 담아 한 번만 물으세요. 추측으로 정할 수 있는 것은 묻지 말고 계획에 '가정'으로 적으세요.
질문과 계획을 한 메시지에 섞지 마세요 — 질문할 때는 질문만, 계획을 낼 때는 계획만 씁니다.
```

`promptFor` 조립:
- 켬은 지금(작업 타입 적용 뒤)과 같다.
- 끔 kickoff는 다음 순서로 붙인다.
  1. `everyTurn`
  2. `kickoffOnly`
  3. `kickoffLead`
  4. Title·Request
  5. `attachmentSection(claim,false)`
  6. `taskTypeGuide(t,false)`
  7. `writableDbLine`
  8. `planningOnlyParagraph(false)`
  9. 형식 안내(`:168-171`, 공통)
  10. `processLine(false)`
- 끔 resume은 `everyTurn + SIMPLE_RESUME_PREFIX + lastAnswer`다. 접두는 참조 절 뒤, 답변 앞에 둔다(`:146-150` 계약).

- [ ] **Step 1: 켬 골든 두 개를 먼저 고정** — 고치기 전 코드(작업 타입 병합 뒤)로 출력을 뽑아 리터럴로 옮기고(vitest 실패 diff에서 복사), 고치기 전 코드에서 PASS인지 확인한다.

```ts
// skillDispatch.test.ts — 값은 지금 출력(skillDispatch.ts:41-46)
it('buildPlanReformatSplice 바이트 고정 (켬 force-finish·near-miss 가드)', () => expect(buildPlanReformatSplice()).toBe(PLAN_REFORMAT_GOLDEN))
// interviewRunner.test.ts '작업 인터뷰' describe — promptWith(위 Interfaces) 옆
// FULL_CLAIM = { ...interviewClaimWithRefs, taskType: 'DB_WORK', writableDbConnections: ['개발DB (PostgreSQL)'],
//                attachments: [슬라이드 첨부 1건 — `:2038-2050` pptx 모양을 복제(slideImagePaths 2장·extractedTextPath·mediaImagePaths)] }
it('켬 가드: 첨부(슬라이드)·DB 작업·쓰기 연결·DB 절·참조가 다 붙은 kickoff는 골든', async () => {
  for (const v of [undefined, null, true])
    expect(await promptWith({ ...FULL_CLAIM, superpowersEnabled: v } as any, { db: withDb(), prepared: [iAvailable()] })).toBe(KICKOFF_FULL_GOLDEN) })
```

- [ ] **Step 2: 실패하는 시험 작성**

```ts
// skillDispatch.test.ts
it('PLAN_FORMAT_RULE은 재요청 문구 안에 그대로', () => expect(buildPlanReformatSplice()).toContain(PLAN_FORMAT_RULE))
// interviewMode.test.ts
it('superpowersOn: false만 끔', () => { for (const v of [undefined, null, true]) expect(superpowersOn({ superpowersEnabled: v as any })).toBe(true); expect(superpowersOn({ superpowersEnabled: false })).toBe(false) })
it('끔 PLANNING-ONLY는 진행 방식 괄호만 뺀 것', () => expect(planningOnlyParagraph(false)).toBe(planningOnlyParagraph(true).replace(' (via brainstorming → writing-plans)', '')))
it('끔 진행 방식 줄')     // processLine(false) === SIMPLE_PROCESS; '번호를 붙여'·"'가정'"·'섞지 마세요'·'파일로 저장하지 마세요' 포함; 'brainstorming'·'writing-plans' 없음
it('resume 접두')         // startsWith('이 답변(또는 계획 수정 요청)으로 질문은 끝입니다. 더 묻지 말고'), toContain(PLAN_FORMAT_RULE), endsWith('\n\n')
// kickoffByType.test.ts
it('taskTypeGuide 켬은 둘째 인자 생략과 같다')   // 네 타입·undefined·'QA_WORK': taskTypeGuide(t, true) === taskTypeGuide(t)
it('taskTypeGuide 끔은 질문 순서 문장만 바뀐다')  // 세 타입: '부터 질문하세요' 없음, '한 번의 질문 묶음' 있음, '바꾸면 안 되는 것'·'재현 → 원인 → 수정'·'[바로 실행]' 유지; NEW_FEATURE·undefined → ''
// interviewRunner.test.ts '작업 인터뷰' describe
it('켬 가드: superpowersEnabled 없음·null·true는 골든', async () => { for (const v of [undefined, null, true]) expect(await kickoffOf({ ...freshClaim, superpowersEnabled: v } as any)).toBe(KICKOFF_GOLDEN) })
it('끔 kickoff: 진행 방식만 바뀐다', async () => { const p = await kickoffOf({ ...freshClaim, superpowersEnabled: false })
  expect(p).not.toContain('brainstorming'); expect(p).not.toContain('writing-plans'); expect(p.endsWith(processLine(false))).toBe(true)
  for (const s of ['I want to add a feature to the repo at acme/widgets', 'PLANNING-ONLY', 'implementation PLAN, NOT code.', '`### 작업 N: <제목>`']) expect(p).toContain(s) })
it('끔 kickoff + DB 작업·첨부·쓰기 연결')   // promptWith({ ...FULL_CLAIM, superpowersEnabled: false }, { db: withDb(), prepared: [iAvailable()] }) → '[바로 실행]'·'계획을 쓰기 전에 이 파일들을 Read'·'파일이 없으면 건너뛰고'·'구현 단계에서는 다음 DB 연결' 포함, '부터 질문하세요'·'brainstorming' 없음
it('끔 resume: 접두 + 답변', async () => expect(await kickoffOf({ ...resumeClaim, superpowersEnabled: false })).toBe(SIMPLE_RESUME_PREFIX + resumeClaim.lastAnswer))
it('끔 resume에 참조가 있으면 참조 절 → 접두 → 답변')   // promptWith({ ...interviewResume, superpowersEnabled: false }, { prepared: [iAvailable()] }) === interviewRefSection([iAvailable()]) + SIMPLE_RESUME_PREFIX + interviewResume.lastAnswer
it('켬 resume는 그대로', async () => expect(await kickoffOf({ ...resumeClaim, superpowersEnabled: true })).toBe(resumeClaim.lastAnswer))
// ':475' describe(capturing 사용)
it('QUESTION은 superpowersEnabled false가 와도 칸 없을 때와 같다')   // questionClaim과 { ...questionClaim, superpowersEnabled: false }: captured.prompt 서로 같음, 둘 다 options.plugins toEqual [], allowedTools toEqual []
```

- [ ] **Step 3: 실패 확인** — `Set-Location <W>\netismaker-interview-service; npx vitest run test/interviewMode.test.ts test/skillDispatch.test.ts test/kickoffByType.test.ts test/interviewRunner.test.ts` → FAIL(Step 1 골든 두 개는 PASS)
- [ ] **Step 4: 구현** — `promptFor`는 시그니처를 바꾸지 않는다. `superpowersOn(claim)`을 직접 읽는다.
  - **설계와 다름:** 설계 §5-2는 "작업 타입 PR에서 kickoff를 두 절로 나눠 둔다"고 했다. 그러나 작업 타입 계획은 진행 방식 문구를 `promptFor` 안에 그대로 두었다(`2026-11-03-task-type-db-write.md:250`). 그래서 진행 방식 절은 이 Task가 `interviewMode.ts`로 만든다.
  - 또 작업 타입 계획에서는 "…부터 질문하세요"가 `taskTypeGuide` 안에 있다. 그래서 그 함수에 둘째 인자를 더한다.
  - 끔 진행 방식 줄에 "파일로 저장하지 마세요"를 넣는다. 끔 resume 턴은 plan 파일로 판정하지 않으므로(Task 3) 고친 계획은 채팅 본문에 있어야 한다.
- [ ] **Step 5: 통과 확인** — `Set-Location <W>\netismaker-interview-service; npx vitest run; npx tsc --noEmit -p tsconfig.json` PASS(Step 1 골든, 기존 골든 단언, 작업 타입 시험 `'resume은 타입과 무관'`, 첨부 시험 `:225-240` 포함)
- [ ] **Step 6: 커밋** — `feat(interview): 간단 인터뷰 kickoff·resume 접두`

---

### Task 3: 끔 세션의 턴 흐름 — 옵션·재요청·계획 보고

**Files:**
- Modify: `I/src/runner/interviewRunner.ts:583-754`(`runTurn` INTERVIEW 부분), `I/src/types.ts:223-240`(`WorkerPlanRequest`)
- Test: `I/test/interviewRunner.test.ts` — `'작업 인터뷰'` describe 안 새 describe `'간단 인터뷰 턴 흐름 (스펙 2026-11-03 §5)'`(`capturingEach`·`noDb`·`turns(n)` 사용)

**Interfaces:**
- Consumes: Task 1 `superpowers` 입력, Task 2 `superpowersOn`·`SIMPLE_RESUME_PREFIX`
- Produces: `WorkerPlanRequest.claudeSessionId?: string`. 끔이고 `sessionId`가 비어 있지 않을 때만 싣는다. 켬 바디에는 키가 없다.
- 분기: `const on = superpowersOn(claim)`, `const resume = !!claim.claudeSessionId`를 질문 분기(`:578-581`) 뒤에서 한 번 계산한다.

| 자리 | 켬(지금 그대로) | 끔 |
|---|---|---|
| `buildOptions` 3곳(`:587`·`:643`·`:689`) | `superpowers` 생략과 같음 | `superpowers: false` |
| 프롬프트(`:608-613`) | `forceFinish && claudeSessionId`면 재요청 문구만 | 항상 `promptFor`(끔 resume 접두가 곧 마무리 요청) |
| handoff(`:633-635`) | 그대로 | 부르지 않음(`buildWritingPlansSplice` 금지) |
| `harvestTurn`(`:676-680`) | 채팅 → plan 파일 | kickoff은 켬과 같음, resume 턴은 채팅만(`tryHarvest`) |
| 재요청 1회(`:683`) | `!forceFinish && detectPlanIntent(text)` | resume 턴이면 계획이 없을 때 무조건, kickoff이면 켬과 같은 조건 |

재요청 relay와 사용량 합산은 기존 블록(`:684-716`)을 그대로 쓴다. 끔은 handoff가 없으므로 추가 relay가 최대 1회다. QUESTION(`:784`)은 바꾸지 않는다.

- [ ] **Step 1: 실패하는 시험 작성** — 먼저 `capturingEach`(`:1202-1212`)가 질의마다 `options`도 모으게 고친다(`options[i] = args.options`, 반환 `{ fakeQuery, prompts, options }` — 기존 호출은 `prompts`만 꺼내므로 그대로 통과).

```ts
it('켬: kickoff·handoff·near-miss relay 모두 superpowers 옵션')   // resumeClaim(칸 없음), 스트림 handoff 문구 → '# 구현 계획 (초안)\n\n1. 가' → 정규 계획, deps에 spliceRead(:1757 모양): options 3개 모두 plugins toEqual [{type:'local', path:'/sp/5.1.0'}], allowedTools[0] === 'Skill', disallowedTools toEqual ['AskUserQuestion']
it('끔: kickoff·재요청 relay 모두 plugins [] 이고 Skill이 막혀 있다')   // options 각각 plugins toEqual [], disallowedTools toContain 'Skill'
it('끔: 첫 턴에 바로 계획 → postPlan에 claudeSessionId')   // freshClaim(sessionId 42)+끔, 스트림 session_id 'sess-1' + 정규 계획 → postPlan(42, objectContaining({ claudeSessionId: 'sess-1' }))   [RF1]
it('켬: postPlan 바디에 claudeSessionId 키가 없다')   // expect(body).not.toHaveProperty('claudeSessionId')
it('끔 resume이 질문으로 끝나면 같은 세션에 재요청 1회 → 계획이면 postPlan(사용량 합산)')
  // query 2회, 두 번째 prompt === buildPlanReformatSplice(), options.resume === claim.claudeSessionId, costUsd·inputTokens 합
it('재요청 뒤에도 질문이면 postQuestion, relay는 2회뿐')
it('끔 kickoff의 질문(물음표)은 재요청 없이 postQuestion')   // query 1회 — 한 번 모아 묻기는 허용
it('끔: handoff 문구여도 writing-plans를 주입하지 않는다')   // spliceRead 미호출; 두 번째 prompt === buildPlanReformatSplice()
it('끔 resume: 채팅에 계획이 없으면 plan 파일로 판정하지 않는다')   // resumeClaim+끔, deps.harvestPlanFiles가 ok:true를 돌려줘도 호출 0회, postPlan 0회   [RF3]
it('끔 kickoff: 채팅에 계획이 없으면 켬처럼 plan 파일로 판정한다')   // freshClaim+끔, 물음표 없는 본문 + harvestPlanFiles ok:true → 호출 1회, postPlan 1회
it('끔: force-finish 문턱(19)에서도 고칠 점이 실린다')   // turns(19) + lastAnswer '컬럼 이름을 바꿔 주세요' → 첫 prompt === SIMPLE_RESUME_PREFIX + lastAnswer   [RF4]
it('켬: force-finish 문턱에서는 지금처럼 재요청 문구만')   // superpowersEnabled true 명시 → 첫 prompt === buildPlanReformatSplice()
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\netismaker-interview-service; npx vitest run test/interviewRunner.test.ts` → FAIL
- [ ] **Step 3: 구현** — 위 표대로 고친다. `forceFinish`의 의미(`:548`)와 최대 턴 실패(`:539-547`)는 그대로 둔다.
  - **설계와 다름 ①:** 설계 §4.1은 "러너는 바꿀 것이 없다"고 했다.
    - 그러나 `claudeSessionId`는 질문 보고에서만 저장된다(`M/service/InterviewService.java:368-370`).
    - 그래서 첫 턴에 바로 계획을 낸 세션은 고쳐 달라기 claim이 새 kickoff(`interviewRunner.ts:156`)를 타고 고칠 점이 버려진다.
    - 이를 막으려고 계획 보고에 이 값을 싣는다. API 저장은 Task 6이 한다. 구 API는 모르는 칸을 무시한다(`:816` 주석과 같은 전제).
  - **설계와 다름 ②:** 끔은 force-finish의 "재요청 문구만" 분기를 타지 않는다. 그 분기는 `lastAnswer`를 버린다.
  - **설계와 다름 ③:** 끔 resume 턴은 plan 파일 대체 판정을 하지 않는다. 매 턴 `git reset --hard`가 untracked plan 파일을 지우지 않아 앞 턴 plan 파일이 다시 잡히기 때문이다(`I/src/runner/planFiles.ts:12-16`). kickoff은 새 체크아웃이라 오판 위험이 없으므로 켬과 같이 안전망을 둔다(INTERVIEW 게이트가 끔에서도 `docs/superpowers` 쓰기를 허용한다, `permissions.ts:483-491`).
  - **설계와 다름 ④:** 설계 §5-4의 재요청은 기존 near-miss 조건으로 잡히지 않는다. 물음표가 있으면 `detectPlanIntent`가 false다(`skillDispatch.ts:15-17,34-36`). 그래서 끔 resume에 새 조건을 둔다.
- [ ] **Step 4: 통과 확인** — `Set-Location <W>\netismaker-interview-service; npx vitest run; npx tsc --noEmit -p tsconfig.json` PASS. 기존 `:310`·`:377`·`:1690`·`:1738`·`:1783` describe는 바꾸지 않고 통과해야 한다.
- [ ] **Step 5: 커밋** — `feat(interview): 간단 인터뷰 턴 흐름 — 플러그인 없음·재요청 1회·세션 id 보고`

---

# API

### Task 4: 세션 칸·승인 입력·이력

**Files:**
- Create: `R/db/migration/V{n}__interview_superpowers.sql`(번호는 Global Constraints)
- Modify: `M/entity/InterviewSession.java:158-172` 근처, `M/dto/ApproveRequest.java:15-31`(작업 타입 Task 2 뒤), `M/service/InterviewService.java:145-161`, `M/service/TaskService.java:538-566`
- Modify(시험 호출 맞춤 — 5인자):
  - `T/service/TaskServiceApproveDbTest.java:56,66`
  - `T/service/TaskServiceApproveRefsTest.java:123,130,162`
  - 작업 타입 `TaskServiceTaskTypeTest`(`승인_타입이_틀리면_세션을_만들지_않는다`)
  - `T/service/InterviewServiceTest.java:1285,1302`
- Test: `T/entity/InterviewSessionTest.java`(추가), `T/service/TaskServiceApproveSuperpowersTest.java`(새, `TaskServiceApproveRefsTest.java:62-90` 하네스·`reasons()`), `T/service/InterviewServiceTest.java`(추가), `T/dto/ApproveRequestJsonTest.java`(새)

**Interfaces:**
- Produces:
  - `InterviewSession.isSuperpowersEnabled()`/`setSuperpowersEnabled(boolean)`
    - `@Setter @Column(name = "superpowers_enabled", nullable = false) private boolean superpowersEnabled = true`
    - 질문 세션도 필드 초기자로 true다.
  - `ApproveRequest(…, String taskType, Boolean superpowersEnabled)` — 6인자 호환 생성자를 둔다(→ null).
  - `InterviewService.createForTask(Task t, String model, String effort, List<TaskMcpSpec> extras, boolean superpowersEnabled)` — 4인자판은 지운다.
  - 이력(같은 상태 행 INTERVIEWING→INTERVIEWING, `"user"`, adminId): `인터뷰 방식: 간단(superpowers 끔)`. 끔일 때만 남기고, 승인 이력과 기존 같은 상태 이력(참조·타입) 뒤 **마지막**에 둔다. 참조·타입 이력끼리의 순서는 작업 타입 PR 코드를 따른다.
- SQL은 한국어 머리 주석 아래 한 문장이다: `ALTER TABLE com.interview_session ADD COLUMN superpowers_enabled BOOLEAN NOT NULL DEFAULT true;`

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 새_세션은_켬()                       // InterviewSession.create(…)·createQuestion(…) → isSuperpowersEnabled() true
@Test void 바디없음_null_true는_켬_이력없음()     // req null / superpowersEnabled null / TRUE → verify(interviewService).createForTask(eq(t), anyString(), anyString(), anyList(), eq(true)); reasons() containsExactly("관리자 승인 → 인터뷰 시작")
@Test void 끔이면_세션에_false와_마지막_이력()     // FALSE + 참조 변경 + 타입 NEW_FEATURE→DB_WORK → createForTask(…, eq(false));
                                                 // reasons(): hasSize(4), 첫 원소 "관리자 승인 → 인터뷰 시작", 마지막 원소 "인터뷰 방식: 간단(superpowers 끔)", <참조 조정 문구>·"타입 변경: 신규 개발 → DB 작업" 포함
@Test void createForTask는_값을_세션에_저장()     // InterviewServiceTest: false → sessionRepo.save 인자 isSuperpowersEnabled() false
@Test void JSON_바인딩()                         // ApproveRequestJsonTest: {"superpowersEnabled":false} → FALSE, 키 없음 → null
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.service.TaskServiceApproveSuperpowersTest --tests com.hamonsoft.netismaker.entity.InterviewSessionTest --tests com.hamonsoft.netismaker.dto.ApproveRequestJsonTest` → 컴파일 실패
- [ ] **Step 3: 구현**
  - `startInterview` 검증 블록에서 `boolean sp = req == null || !Boolean.FALSE.equals(req.superpowersEnabled())`를 정한다.
  - `createForTask(…, sp)`는 세션 생성 전에 `s.setSuperpowersEnabled(sp)`를 한다. 반환값에 setter를 이어 부르지 않는다. mock이 null을 돌려준다.
  - 4인자판을 지우는 이유: 남겨 두면 기존 `never()` 검증(`TaskServiceApproveDbTest.java:66`, `TaskServiceApproveRefsTest.java:162`, 작업 타입 시험)이 4인자를 보며 아무것도 확인하지 않고 통과한다. 지우면 컴파일 오류로 전부 드러난다.
  - 레거시 분석완료 승인은 바디를 무시하므로 그대로 둔다(`TaskService.java:519`).
- [ ] **Step 4: 통과 확인** — `Set-Location <W>`에서 Step 2 명령과 `.\gradlew.bat test --tests "com.hamonsoft.netismaker.service.TaskService*" --tests "com.hamonsoft.netismaker.service.InterviewService*"` PASS, `.\gradlew.bat compileJava compileTestJava` 성공
- [ ] **Step 5: 커밋** — `feat(interview): 세션 superpowers_enabled와 승인 입력·이력`

---

### Task 5: claim·요약·세션·작업 응답의 칸

**Files:**
- Modify: `M/dto/InterviewClaimResponse.java:18-77,149-203`(끝 컴포넌트, compact, `of`, 사본 다섯 곳), `M/dto/InterviewSummaryResponse.java:13-31`
- Modify: `M/dto/InterviewResponse.java:14-66,111-129`, `M/service/InterviewService.java:795-803`(`LatestSession`)
- Modify: `M/dto/TaskResponse.java:21-77`(끝 컴포넌트, 생성 다섯 곳과 새 사본), `M/controller/TaskController.java:129-147`
- Modify(시험 맞춤): `T/dto/InterviewResponseTest.java:181-182`
- Test: `T/dto/InterviewClaimResponseTest.java`, `T/controller/InterviewWorkerControllerClaimDbTest.java`(`:52-81` 하네스), `T/dto/InterviewSummaryResponseTest.java`(새), `T/dto/InterviewResponseTest.java`, `T/dto/TaskResponseInterviewSuperpowersTest.java`(새), `T/controller/TaskControllerRefReposTest.java`(추가)

**Interfaces:**
- Consumes: Task 4 `isSuperpowersEnabled()`
- Produces:
  - `InterviewClaimResponse.superpowersEnabled()`(Boolean)
    - `of`에서 `s.isSuperpowersEnabled()`로 채운다. compact에서 null이면 TRUE다.
    - 사본 `withDb`·`withRefRepos`·`withRefsCleared`·`withTaskType`·`withWritableDbConnections`는 값을 그대로 실어 나른다. 따로 `with*`는 만들지 않는다.
  - `InterviewSummaryResponse.superpowersEnabled()`, `InterviewResponse.superpowersEnabled()`(Boolean, 끝 칸)
  - `InterviewService.LatestSession(Long id, List<RefRepo> refRepos, boolean superpowersEnabled)` — 2인자 호환 생성자(→ true)를 둔다. 그래서 `TaskControllerRefReposTest.java:80,107`은 그대로 컴파일된다.
  - `TaskResponse.interviewSuperpowersEnabled()`(Boolean, 끝 칸)와 `withInterviewSuperpowersEnabled(Boolean)`
  - `TaskController.get`: 최신 세션이 있으면 상태와 무관하게 그 값을 싣고, 없으면 null이다. 세션 조회는 지금처럼 한 번이다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void claim_사본_다섯곳이_superpowersEnabled_유지_JSON()   // 끔 세션 of(…) → 각 사본 직렬화에 "\"superpowersEnabled\":false"; 정규 생성자 null → TRUE   [RF5]
@Test void DB보강_실패경로도_끔_유지()        // InterviewWorkerControllerClaimDbTest — withDb(List.of(), …) 경로의 응답 superpowersEnabled() false   [RF5]
@Test void 요약과_세션응답은_세션값()          // InterviewSummaryResponse.of, InterviewResponse.of + withDbConnections → false
@Test void 상세는_최신_세션값_세션없으면_null() // LatestSession(9L, List.of(), false) + 상태 승인대기 → false; Optional.empty() → null; verify(interviewService, times(1)).latestSessionForTask(42L)
@Test void 비관리자_상세도_값이_남는다()        // forViewer(r, false) → interviewSuperpowersEnabled() false   [RF5]
@Test void TaskResponse_사본이_값을_유지()      // withTotalCost·withRefRepos·withFix·redactSecretValues → false
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.dto.InterviewClaimResponseTest --tests com.hamonsoft.netismaker.dto.TaskResponseInterviewSuperpowersTest --tests com.hamonsoft.netismaker.controller.TaskControllerRefReposTest` → 컴파일 실패
- [ ] **Step 3: 구현** — 사본(claim 다섯 곳, 작업 `withTotalCost`·`withRefRepos`·`withFix`·`redactSecretValues`)은 새 칸을 그대로 넘기고, 작업 `of`·`ofWithUsage`가 부르는 기본 생성(`TaskResponse.java:334`)은 null로 둔다. DTO 파일 밖에서 claim 정규 생성자를 부르는 곳은 없다.
- [ ] **Step 4: 통과 확인** — `Set-Location <W>`에서 Step 2 명령과 `.\gradlew.bat test --tests "com.hamonsoft.netismaker.dto.*" --tests "com.hamonsoft.netismaker.controller.Interview*" --tests "com.hamonsoft.netismaker.controller.Task*"` PASS
- [ ] **Step 5: 커밋** — `feat(interview): claim·요약·세션·작업 응답에 superpowersEnabled`

---

### Task 6: 계획 고쳐 달라기 — 답변 상태·계획 보고 세션 id·이전 계획 보존

**Files:**
- Modify: `M/service/InterviewService.java:543-565`(`submitAnswer`), `:454-479`(`recordPlan`), `M/dto/WorkerPlanRequest.java:11-35`
- Test: `T/service/InterviewServiceTest.java`(추가, 하네스 `:45-80`), `T/dto/WorkerPlanRequestJsonTest.java`(추가), `T/controller/InterviewPlanFixFlowIntegrationTest.java`(새, CI — MockMvc·`adminJwt`·워커 API 키·`createTask`를 쓰는 `ImplementationModeFlowIntegrationTest.java:45-96,121-165` 하네스)

**Interfaces:**
- Consumes: Task 4 칸, Task 3의 계획 보고 `claudeSessionId`
- Produces:
  - `WorkerPlanRequest(…, List<RefState> refStates, String claudeSessionId)` — 10인자 호환 생성자(→ null)를 두고, 9인자도 유지한다.
  - `recordPlan`
    - `claudeSessionId`가 비어 있지 않으면 세션에 저장한다(`recordQuestion` `:368-370`과 같은 규칙).
    - 끔 세션(`!s.isSuperpowersEnabled()`)이면 design 턴 본문에 계획 본문을 함께 담는다. `designMarkdown`이 비어 있지 않으면 `designMarkdown + "\n\n" + planMarkdown`, 비면 `planMarkdown`이다. 켬은 지금처럼 `designMarkdown`(null이면 `""`)만이다.
  - `submitAnswer`
    - 상태 조건은 `AWAITING_INPUT || (PLAN_READY && !s.isSuperpowersEnabled() && !s.isQuestion())`다. 아니면 지금의 409와 메시지 그대로다. `!s.isQuestion()`은 방어다(질문 세션은 PLAN_READY가 되지 않고 늘 true다).
    - 중복 검사가 상태 검사보다 먼저인 순서(`:550-555`)는 그대로 둔다.
  - 미러 사유: 이전 상태가 PLAN_READY면 `계획 수정 요청 → 인터뷰 재개`, 아니면 기존 `관리자 답변 → 인터뷰 재개`다.

- [ ] **Step 1: 실패하는 시험 작성**

```java
@Test void 간단_인터뷰_플랜완료에_답하면_재큐와_계획수정_이력()   // PLAN_READY·끔·replyToSeq null → QUEUED, user/answer 턴, 작업 이력 사유 "계획 수정 요청 → 인터뷰 재개"
@Test void 켬_플랜완료_답변은_409와_기존_메시지()   // 새 replyToSeq 99 → CONFLICT, 메시지 startsWith "입력대기 상태에서만 답변할 수 있습니다 (현재: ", 턴 저장 never()
@Test void 입력대기_답변_이력은_그대로()             // "관리자 답변 → 인터뷰 재개"
@Test void recordPlan이_claudeSessionId를_저장()   // "sess-1" → 저장; null·"  " → 기존 값 유지   [RF1]
@Test void 끔_recordPlan의_design_턴에_계획_본문()  // 끔: design null·plan "# 계획 v1" → design 턴 본문 "# 계획 v1"; design "# 설계" → "# 설계\n\n# 계획 v1"; 켬: "# 설계" 그대로
@Test void 계획_JSON_claudeSessionId_바인딩()      // WorkerPlanRequestJsonTest: 키 없음 → null
// (CI) InterviewPlanFixFlowIntegrationTest — 모두 HTTP(MockMvc)로 돈다
@Test void 간단_인터뷰_계획을_고치면_같은_대화로_이어_최신_계획으로_확정()   // [RF1]
  // POST /api/tasks/{id}/approve {"superpowersEnabled":false} 200 → POST /worker/interviews/claim: $.superpowersEnabled false, $.claudeSessionId 없음
  // → POST /worker/interviews/{sid}/plan {designMarkdown:"# 설계 v1", planMarkdown:"# 계획 v1\n\n### 작업 1: 가", planJson:"[{\"title\":\"v1\"}]", claudeSessionId:"sess-1", costUsd:0.1, durationMs:1000} 204
  // → POST /api/interviews/{sid}/answer {"answer":"컬럼명을 바꿔 주세요","replyToSeq":null} 200, GET /api/tasks/{id} $.status "INTERVIEWING"
  // → claim: $.claudeSessionId "sess-1", $.lastAnswer "컬럼명을 바꿔 주세요"
  // → plan v2 {designMarkdown:"# 설계 v2", planMarkdown:"# 계획 v2\n\n### 작업 1: 나", planJson:"[{\"title\":\"v2\"}]", claudeSessionId:"sess-1"} 204
  // → JdbcTemplate: interview_plan 행 1개, plan_markdown이 "# 계획 v2"로 시작; assistant/design 턴 2개 본문에 각각 "# 계획 v1"·"# 계획 v2"
  // → POST /api/interviews/{sid}/confirm {"designRequested":false} 200
  // → task_analysis.subtasks_json == [{"title":"v2"}], markdown_result == "# 설계 v2"(design 전용 — 고정 계약 InterviewService.java:660-664, 계획 본문은 넣지 않는다)
  // → GET /worker/next-task?workerId=w1: $.kind "IMPLEMENTATION", $.planMarkdown이 "# 계획 v2"로 시작(구현이 실제로 읽는 본문, WorkerService.java:384-395)
@Test void 칼럼은_NOT_NULL_DEFAULT_true()           // JdbcTemplate information_schema (ImplementationModeFlowIntegrationTest.java:103-117 모양)
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>; .\gradlew.bat test --tests com.hamonsoft.netismaker.service.InterviewServiceTest --tests com.hamonsoft.netismaker.dto.WorkerPlanRequestJsonTest` → FAIL/컴파일 실패
- [ ] **Step 3: 구현** — 위 두 메서드만 고친다. 컨트롤러의 QUEUED push(`InterviewController.java:66`)는 켬 동작이므로 그대로 둔다.
  - **설계와 다름 ①:** 설계 §4.1은 "`recordPlan`이 새 `InterviewPlan` 행을 만든다"고 했다.
    - 실제로는 PK가 `session_id`라 같은 행을 덮어쓴다(`V11__interview.sql:61-62`, `InterviewPlan.java:23-25`). 그래서 confirm(`:692`)과 구현 claim(`WorkerService.java:384-395`)은 고치지 않아도 최신 계획을 쓴다. CI 시험이 이를 고정한다.
    - 설계의 "이전 계획은 대화 기록(design 턴)에 남는다"는 지금 코드로는 지켜지지 않는다. design 턴에는 `designMarkdown`만 남고(`:470-471`) 간단 인터뷰는 대개 그 값이 비어 있다. 그래서 끔 세션의 design 턴에 계획 본문을 함께 담는다(위 Interfaces).
    - system 노트는 평문 칩(`F/components/chat/ChatBubble.vue:38-40`)이라 긴 계획에 맞지 않아 쓰지 않는다. assistant 턴을 하나 더 만들면 턴 상한 계산(`interviewRunner.ts:538`)이 늘어 쓰지 않는다.
  - **설계와 다름 ②:** 계획 고쳐 달라기의 `replyToSeq`는 null로 받는다.
    - 화면은 라이브로 받은 계획의 design 턴 seq를 모른다(plan 엔드포인트는 `plan_ready`·`status`만 보낸다, `InterviewWorkerController.java:127-134`).
    - 두 번째 제출은 중복 no-op 대신 상태 검사의 409로 막힌다.
  - **결정(설계 §4.1 "기존 턴 상한·유휴 만료를 따른다", 사용자 확인됨 2026-11-03 — 완화하지 않음):** 고쳐 달라기 턴이 실패(최대 턴·워커 실패·재기동 재개 소진·stale 회수)하거나 QUEUED·입력대기 TTL로 만료되면 지금 규칙대로 FAILED/EXPIRED·승인대기다(`InterviewService.java:525-537,643-653`). 이미 나온 계획도 확정할 수 없게 되고, 본문은 design 턴에만 남는다.
    - 완화하지 않는 이유: 간단 인터뷰는 재승인 뒤 첫 턴에 다시 계획을 내므로 다시 하는 비용이 작다. 완화하려면 실패·만료 호출부 6곳(`InterviewStaleRecoveryJob.java:80-86,103-108,120-122,135-137`, `InterviewWorkerController.java:94-106,160-163`)이 FAILED·EXPIRED를 고정으로 push하는 것을 바꿔야 한다.
- [ ] **Step 4: 통과 확인** — `Set-Location <W>`에서 Step 2 명령과 `.\gradlew.bat test --tests "com.hamonsoft.netismaker.service.Interview*" --tests "com.hamonsoft.netismaker.controller.Interview*"` PASS, `.\gradlew.bat compileTestJava` 성공, CI 클래스는 skipped
- [ ] **Step 5: 커밋** — `feat(interview): 간단 인터뷰 플랜완료에 계획 수정 요청 받기`

---

# 프론트

### Task 7: 승인 다이얼로그 — superpowers 토글

**Files:**
- Create: `F/composables/interviewMode.ts` + `.spec.ts`
- Modify: `F/components/ApproveDialog.vue`
  - `SessionSummary`(`:42-50`)에 `superpowersEnabled?: boolean | null`
  - 상태(`:57-59` 근처), `init`(`:161-202`), `approve`(`:212-237`)
  - 템플릿 `ModelEffortFields`(`:295-299`)와 `McpPicker`(`:300-302`) 사이
  - 작업 타입 Task 6 타입 select(`data-test="approve-task-type"`)의 `@update:model-value`
- Test: `F/components/ApproveDialog.spec.ts`(`session`·`routeApi`·`mountDialog`·`approveCall` 하네스 — 파일 최상위 함수)
  - `BODY_WITHOUT_REFS`는 `'참조 repo'` describe(`:564`)의 지역 상수라(`:580`) 새 describe 안에 같은 값으로 다시 둔다. 작업 타입 PR이 그 값을 바꿨으면 바뀐 값이다.

**Interfaces:**
- Consumes: Task 5 `InterviewSummaryResponse.superpowersEnabled`, 작업 타입 Task 6 타입 select. 그 v-model ref 이름은 계획에 없으므로 코드에서 `approve-task-type`로 찾는다.
- Produces(`interviewMode.ts`):
  - `defaultSuperpowers(taskType: string | null | undefined): boolean` — `'DB_WORK'`만 false
  - `lastSessionSuperpowers(row: { superpowersEnabled?: boolean | null } | undefined): boolean | null` — boolean일 때만 그 값
  - `canRequestPlanFix(status: string | null | undefined, superpowersEnabled: boolean | null | undefined, isQuestion: boolean): boolean` — `PLAN_READY && === false && !isQuestion`
  - 문구 상수: `SUPERPOWERS_TOGGLE_LABEL`, `SIMPLE_INTERVIEW_LABEL`, `SIMPLE_INTERVIEW_CAPTION`, `PLAN_FIX_SEND`, `PLAN_FIX_PLACEHOLDER`(값은 Global Constraints "문구")
  - data-test: `approve-superpowers`(q-toggle), `approve-superpowers-caption`(끔일 때만)
- 동작:
  - `init` 동기 구간: 토글 = `defaultSuperpowers(현재 타입)`, `superpowersTouched = false`
  - 조회 뒤: `lastSessionSuperpowers(rows[0])`가 boolean이고 touched가 아니면 그 값을 쓴다. 모델 프리필 `if`(`:189-193`) **밖**에 둔다.
  - 타입 select 핸들러: touched가 아니면 새 타입의 기본값으로 바꾼다. `watch`는 쓰지 않는다. `init`이 타입을 다시 넣을 때 프리필을 덮을 수 있어서다.
  - 토글 핸들러: touched = true
  - 바디: `...(superpowers.value ? {} : { superpowersEnabled: false })`

- [ ] **Step 1: 실패하는 시험 작성**

```ts
// interviewMode.spec.ts
it('DB_WORK만 끔')               // 'BUG_FIX'·null·undefined·'QA' → true
it('지난 세션 값은 boolean일 때만') // ({superpowersEnabled:false}) false, ({}) null, (undefined) null
it('canRequestPlanFix')          // ('PLAN_READY', false, false) true; undefined·null·true → false; ('AWAITING_INPUT', false, false) false; ('PLAN_READY', false, true) false
// ApproveDialog.spec.ts — describe('ApproveDialog superpowers 인터뷰 (스펙 2026-11-03 간단 인터뷰 §6)')
it('지난 세션이 없으면 타입 기본값 — DB 작업은 끔과 보조 문구')
it('지난 세션 값이 먼저 — 모델이 목록에 없어도')   // { ...session(5,'CANCELLED','취소됨','fable-5','high'), superpowersEnabled: false } + taskType 'BUG_FIX' → 끔, 모델 프리필 캡션 없음
it('지난 세션에 칸이 없으면(구 API) 타입 기본값')
it('타입을 바꾸면 따라가고, 토글을 직접 바꾼 뒤에는 따라가지 않는다')   // approve-task-type emit 'DB_WORK' → 끔, 'BUG_FIX' → 켬; 토글 emit false 뒤 'BUG_FIX' → 끔 유지
it('다시 열면 직접 바꿈이 풀린다')                 // :341-372 다시 열기 패턴
it('켬이면 바디에 키가 없고, 끔이면 superpowersEnabled:false')   // 켬: toEqual(BODY_WITHOUT_REFS); 끔: toEqual({ ...BODY_WITHOUT_REFS, superpowersEnabled: false })
it('토글은 모델·추론 아래, MCP 위')                // compareDocumentPosition; approve-db → approve-refs → .usage-slot 단언(:609-610,632-633)은 그대로 통과
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; npx vitest run composables/interviewMode.spec.ts components/ApproveDialog.spec.ts` → FAIL
- [ ] **Step 3: 구현**
  - **설계와 다름:** 설계 §6은 "바디에 `superpowersEnabled`를 싣는다"고 했다. 여기서는 끔일 때만 `false`를 싣는다.
    - API가 null을 켬으로 보므로 뜻은 같다(설계 §4).
    - 켬 바디가 지금과 바이트까지 같아 기존 `toEqual` 단언 11곳과 작업 타입의 "그대로면 키 없음" 규칙이 깨지지 않는다.
  - 설계의 "DB 연결 프리필과 같은 판정 기준"은 "`rows[0]`이 있고 그 값이 boolean일 때"로 정한다. DB 프리필은 사실상 "목록이 비어 있지 않을 때"라서(`ApproveDialog.vue:125,187`) 그대로 옮길 수 없다.
- [ ] **Step 4: 통과 확인** — `Set-Location <W>\frontend; npx vitest run` 전체 PASS
- [ ] **Step 5: 커밋** — `feat(interview): 승인 다이얼로그 superpowers 인터뷰 토글`

---

### Task 8: 인터뷰 패널 — 계획 고쳐 달라기

**Files:**
- Modify: `F/composables/useInterviewStream.ts:50-60`(`InterviewSnapshot`에 `superpowersEnabled?: boolean | null` — 타입만)
- Modify: `F/components/InterviewPanel.vue`
  - `ui`(`:46-74`), `canAnswer`(`:96-98`)
  - `sendAnswer`(`:201-262`), `onMounted`(`:320-332`)
  - 입력창·보내기(`:473-493`)
- Test: `F/components/InterviewPanel.spec.ts`(`mountPanel` `:8-18`, `FakeEventSource`)

**Interfaces:**
- Consumes: Task 5 `InterviewResponse.superpowersEnabled`, Task 6 답변 규칙, Task 7 `canRequestPlanFix`·`PLAN_FIX_SEND`·`PLAN_FIX_PLACEHOLDER`
- Produces(패널 내부):
  - `sessionSuperpowers = ref<boolean | null>(null)` — 스냅샷에서만 채운다. 스냅샷이 실패하면 null이고, 켬으로 본다.
  - `planFix = computed(() => canRequestPlanFix(status.value, sessionSuperpowers.value, isQuestion.value))`
- 동작:
  - 입력 가능 조건을 `AWAITING_INPUT || planFix`로 넓힌다. `canAnswer`, 입력창 `:disable`(`:479`), `sendAnswer` 첫 가드(`:203`) 세 곳 모두다. Enter(`:481`)도 같은 함수를 탄다.
  - `planFix`이면 `replyToSeq: null`을 보낸다. 참조 규칙(`:217-222`)과 낙관적 턴·`QUEUED` 처리는 그대로다.
  - `planFix`이면 placeholder와 보내기 이름만 바뀐다. "구현 진행"(`:566-575`)은 그대로다. `readonly`면 답변창 자체가 없다(`:463`).

- [ ] **Step 1: 실패하는 시험 작성** — `describe('InterviewPanel 계획 고쳐 달라기 (스펙 2026-11-03 간단 인터뷰 §4.1·§6)')`

```ts
it('간단 인터뷰 PLAN_READY: 입력창이 열리고 문구가 바뀌며 구현 진행도 켜져 있다')   // send-answer 라벨 '계획 고쳐 달라기', placeholder, [data-test=confirm] enabled
it('보내면 replyToSeq null — 이미 답한 질문이 있어도', async () => {   // [RF2]
  // 스냅샷: assistant/question seq 1, user/answer replyToSeq 1, statusName 'PLAN_READY', superpowersEnabled false, plan {...}
  expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', { method: 'POST', body: { answer: '컬럼명을 바꿔 주세요', replyToSeq: null } })
  /* 뒤이어 confirm disabled(QUEUED) */ })
it('Enter로 보내도 같은 바디')
it('켬(칸 없음·true) PLAN_READY: 입력창 비활성·보내기 막힘·Enter도 POST 없음')   // 반목표 §9 가드
it('스냅샷이 실패하면 켬으로 본다')   // mockRejectedValueOnce → emit('plan_ready') → send-answer disabled
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; npx vitest run components/InterviewPanel.spec.ts` → FAIL
- [ ] **Step 3: 구현** — 패널에 `q-toggle`을 더하지 않는다. 기존 시험이 `.q-toggle`로 디자인 토글을 찾는다(`:115,140,155`).
  - **설계와 다름 ①:** 설계는 답변창 조건 `:96-97`·`:479`만 짚었다. `sendAnswer`의 `:203` 가드도 넓힌다. 그러지 않으면 버튼은 켜지는데 눌러도 아무 일이 없다.
  - **설계와 다름 ②:** 설계 §6은 "구현 진행 버튼은 그대로 함께 보인다"고 했다. 그러나 화면 폭 1024px 미만에서는 답변창(대화 탭)과 "구현 진행"(설계·플랜 탭)이 탭으로 나뉘어 함께 보이지 않는다(`InterviewPanel.vue:407-416,660-664`). 탭 구조는 바꾸지 않는다.
- [ ] **Step 4: 통과 확인** — `Set-Location <W>\frontend; npx vitest run` 전체 PASS(`test/task-detail-refs.spec.ts`의 PLAN_READY 재조회 `:340-372,551-572` 포함)
- [ ] **Step 5: 커밋** — `feat(interview): 인터뷰 패널 계획 고쳐 달라기`

---

### Task 9: 작업 상세 `간단 인터뷰` 칩·하이드레이션 목

**Files:**
- Modify: `F/pages/tasks/[id].vue:115-157`(`TaskResponse`에 `interviewSuperpowersEnabled?: boolean | null`), `:729-736`(`.conv-header-chips`)
- Modify: `F/components/tasks/TaskDetailMobile.vue:14-43`(`DetailTask`), `:240` 근처(정보 섹션 `모델` 줄 아래)
- Modify: `F/scripts/hydration-smoke.mjs:54-91` — 작업 1에 `interviewSuperpowersEnabled: false`
- Test: `F/test/task-detail-simple-interview.spec.ts`(새, `task-detail-implementation-mode.spec.ts:1-26` 하네스)

**Interfaces:**
- Consumes: Task 5 `TaskResponse.interviewSuperpowersEnabled`, Task 7 `SIMPLE_INTERVIEW_LABEL`
- Produces: data-test `simple-interview-chip`(데스크톱), `info-simple-interview`(모바일). `=== false`일 때만 그린다.

- [ ] **Step 1: 실패하는 시험 작성**

```ts
it('데스크톱: 끔이면 칩 "간단 인터뷰", true·null·칸 없음이면 칩 없음')
it('모바일(390px): 정보 섹션을 펴면 "간단 인터뷰" 줄, 켬이면 없음')   // section-info 클릭 뒤 info-simple-interview (:70-79 패턴)
```

- [ ] **Step 2: 실패 확인** — `Set-Location <W>\frontend; npx vitest run test/task-detail-simple-interview.spec.ts` → FAIL
- [ ] **Step 3: 구현** — 데스크톱 칩은 `impl-mode-chip` 옆에 둔다. 모바일 칩은 `$q.screen.lt.md` 분기 그대로 정보 섹션에만 둔다(`test/task-detail-mobile.spec.ts:49`).
- [ ] **Step 4: 통과 확인** — `Set-Location <W>\frontend; npx vitest run` 전체 PASS
- [ ] **Step 5: 커밋** — `feat(interview): 작업 상세 간단 인터뷰 칩`

---

### Task 10: 문서·배포 순서·마지막 검증

**Files:**
- Modify: `CLAUDE.md`
  - "작업 상태머신" 다이어그램에 `[플랜승인대기] ──(계획 고쳐 달라기 — 간단 인터뷰만)──→ [인터뷰중]` 한 줄
  - 그 아래 단락 하나: 끔 판정, 러너 분기 표, 이력 문구, 끔 design 턴에 계획 본문, 배포 순서
  - "자주 보는 코드" 표 한 줄: `interviewMode.ts`(인터뷰 서비스·프론트), `sessionOptions.ts`, `InterviewService.submitAnswer`·`recordPlan`, `ApproveDialog.vue`, `InterviewPanel.vue`

- [ ] **Step 1: 전체 시험**
  - `Set-Location <W>; .\gradlew.bat test` → PASS(Testcontainers는 건너뜀)
  - `Set-Location <W>\netismaker-interview-service; npx vitest run; npx tsc --noEmit -p tsconfig.json` → PASS
  - `Set-Location <W>\frontend; npx vitest run; npx nuxi prepare; npm run typecheck` → PASS, 오류 0
- [ ] **Step 2: 하이드레이션 스모크** — Chrome이 없으면 `$env:SMOKE_BROWSER_CHANNEL='msedge'`를 쓴다. `Set-Location <W>\frontend; npm run build; npm run test:hydration` → 경고·예외 0, "목 응답이 없어 null로 답한 API" 없음
- [ ] **Step 3: lint** — `Set-Location <W>\frontend; npm run lint` → 오류 0, 경고 수가 기준선 이하
- [ ] **Step 4: CLAUDE.md** — Files에 적은 내용을 쓴다.
- [ ] **Step 5: PR 본문** — 실행자는 배포하지 않는다. 배포는 사용자 승인 뒤 운영자가 한다.
  - 배포 순서는 설계 §7을 옮기고 V 번호를 실제 `V{n}`으로 적는다. 단건 재기동은 CLAUDE.md "재기동 정합" 끝의 절차를 따른다고 적는다.
  - 남은 위험: Task 6 "결정"(고쳐 달라기 턴이 실패·만료하면 이미 나온 계획도 확정 불가)과 그에 대한 사용자 확인 결과.
- [ ] **Step 6: 커밋** — `docs(interview): 간단 인터뷰와 계획 고쳐 달라기, 배포 순서`
- [ ] **Step 7: CI 확인** — 사용자 승인 뒤 draft PR을 push한다.
  - `rg -l 'named = "RUN_TESTCONTAINERS"' src/test`에 `controller/InterviewPlanFixFlowIntegrationTest.java`가 나와야 한다.
  - CI `Backend`·`Frontend`·`Interview service` 잡이 모두 성공해야 한다. PR 본문에 run id를 적는다.
- [ ] **Step 8: 실사용 확인(배포 뒤, 설계 §10)** — DB 작업 2~3건을 간단 인터뷰로 돌려 다음을 본다.
  - 질문이 0~1회인지
  - 계획이 정규 형식으로 끝나는지
  - "계획 고쳐 달라기" 1회가 같은 대화로 이어지는지(답에 이전 계획을 가리키는 말이 나오는지, 비용이 0부터 다시 시작하지 않는지)
  - 첫 건은 관리자가 지켜본다. 결과는 진행상황 노트에 남긴다.
