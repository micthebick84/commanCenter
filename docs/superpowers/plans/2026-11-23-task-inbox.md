# 처리함(여러 작업의 인터뷰·승인을 한 화면에서) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 `/inbox` 한 화면에서 확인 필요 작업(13개 상태)을 차례로 처리하고, 처리에 성공하면 다음 대기 건으로 넘어가게 한다.

**Architecture:** 프론트만 바꾼다. 왼쪽 목록은 기존 `GET /api/tasks`(`sort=updatedAt,desc&size=200`)를 5초마다 읽어 `attentionGroup`으로 거르고, 오른쪽 처리 영역(`InboxTaskPane`)은 고른 작업 하나만 `GET /api/tasks/{id}`로 폴링한다. 처리 동작은 작업 상세에서 공용 모듈(`useTaskActions`·`useInterviewRefDraft`)로 빼서 함께 쓰고, 승인 창은 폼(`ApproveForm`)과 창 껍데기로 나눈다. 대화 패널에는 `answered` 알림과 `draft` 묶기만 더한다.

**Tech Stack:** Nuxt 3 · Vue 3.5 · Quasar · TypeScript · vitest(happy-dom, `@vue/test-utils`).

**Spec:** `docs/superpowers/specs/2026-11-23-task-inbox-design.md` — 계획과 어긋나면 스펙이 이긴다.

## Global Constraints

- 작업 폴더는 이 worktree(`.claude/worktrees/task-inbox`, 브랜치 `feature/task-inbox`)뿐이다. 본 체크아웃(`C:\Users\mic\NetisMaker\commanCenter`)에서 빌드·시험·git 변경을 하지 않는다(라이브가 거기서 돈다).
- `frontend/node_modules`는 본 체크아웃으로 가는 **정션**이다(이미 만들어 둠). `npm install`·`npm ci`·`npm run build`·`npm run test:hydration`을 이 worktree에서 돌리지 않는다 — 하이드레이션 스모크는 CI가 돌린다. 정션은 `rm -rf`·`Remove-Item -Recurse`로 지우지 않는다.
- 시험 실행: `frontend/`에서 `npx vitest run <파일들>`. 전체는 `npx vitest run`.
- 서버(API·워커·인터뷰 서비스)·DB를 바꾸지 않는다.
- 프론트 규칙(CLAUDE.md): 작은따옴표·세미콜론 없음, 클래스 이름에 Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`) 금지, `defineProps<T>()`의 T를 `~/`에서 가져오지 않는다(상대 경로), 모바일 분기는 `$q.screen.lt.md`, 폰 전체 화면 창은 `useFullscreenDialog()`, `q-dialog` 안 콘텐츠의 루트는 `<div>`, 템플릿의 관리자 분기는 `useHydrationSafeAuth()`, 확인창은 `confirmDialog($q)`.
- **받아들이는 기준(고치지 않고 통과)**: `test/task-detail-*.spec.ts` 전부, `components/ApproveDialog.spec.ts`, `components/InterviewPanel.spec.ts`. 이 파일들은 내용을 바꾸지 않는다(새 시험은 새 파일에).
- 묶음 이름·순서(스펙 §5.2): `답변 대기`(INTERVIEW_INPUT) → `계획 확인`(INTERVIEW_REVIEW, COMPLETED) → `인터뷰 시작 승인`(AWAITING_APPROVAL) → `디자인 검토`(DESIGN_REVIEW) → `수정 요청 승인`(FIX_REVIEW) → `종료 승인`(CLOSE_REVIEW) → `실패`(FAILED, DESIGN_FAILED, IMPLEMENTATION_FAILED, DEPLOY_FAILED, DEPLOY_LOST, CLOSE_FAILED). 재시도 한도를 다 쓴 FAILED(`retryCount >= maxRetry`)는 뺀다.
- 고정 문구: 빈 상태 `확인할 작업이 없습니다`, 200건 안내 `최근 변경된 200건만 봤습니다 — 오래된 작업은 작업 목록에서 확인하세요`, 토글 `처리 후 다음 건으로`, 모두 처리 알림 `모두 처리했습니다`, 인터뷰중 안내 `에이전트가 답을 만드는 중입니다`, 처리할 것 없음 `지금 처리할 것이 없습니다(현재: {statusLabel})`, 승인 폼 처리함 안내 `첫 질문이 오면 처리함의 답변 대기에 올라옵니다`.
- `localStorage` 키 `netis-maker-inbox-auto-next`(값 `'0'`이면 끔, 그 밖·없음·읽기 실패는 켬). 읽기·쓰기는 try/catch.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 푸시는 마지막 Task에서만.
- 저장소는 공개다. 시험·문서에 실제 고객·사이트 이름을 넣지 않는다(`acme/widgets` 같은 일반 이름만).

## Review Focus

1. **다른 곳에서 처리된 건** — 관리자가 답을 쓰는 동안 다른 관리자·폰이 같은 건을 처리해 상태가 확인 필요에서 빠지면, 화면을 바꾸지 않고 “처리할 것이 없는 상태”를 보이며 쓰던 답변은 남아야 한다. → Task 6 `다른_곳에서_처리되면_머물고_쓰던_답변은_남는다`.
2. **처리 성공 알림이 두 번 오는 것** — 같은 처리에 `done`이 두 번 오면(연타·`answered`와 `status`가 겹침) 한 건만 건너뛰어야 한다. → Task 6 `같은_건의_done이_두_번_와도_한_번만_넘어간다`.
3. **에이전트가 폴링 사이에 다음 질문을 올린 경우** — `입력대기 → 인터뷰중 → 입력대기`가 5초 안에 지나가도 그 건이 목록에 다시 보여야 한다. → Task 1 `updatedAt이_바뀌면_같은_상태여도_다시_보인다`.
4. **쿼리의 작업이 지워진 경우** — `?task=`의 작업이 404면 맨 위 건을 고르고 쿼리를 바꿔야 한다. → Task 6 `쿼리의_작업이_404면_맨_위를_고른다`.
5. **브라우저 저장소가 막힌 경우** — `localStorage` 접근이 예외를 던져도 처리함이 뜨고 자동 이동은 켬이어야 한다. → Task 1 `저장소가_예외를_던지면_켬`.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `frontend/composables/taskInbox.ts` (새) | 묶음 정의, 후보 판정, 처리한 건 숨기기, 정렬·묶기, 다음/이웃 고르기, 목록 조회 파라미터·200건 판정, 키보드 무시 판정, 자동 이동 저장값 |
| `frontend/composables/taskInbox.spec.ts` (새) | 위 순수 로직 시험 |
| `frontend/composables/taskDetailTypes.ts` (새) | 작업 상세 응답 타입(`TaskResponse` 등) — `[id].vue`에서 옮김 |
| `frontend/composables/useTaskActions.ts` (새) | 작업 처리 동작(승인·반려·재시도·종료·수정·배포 중지)과 버튼 표시 판정, 각 동작은 `Promise<boolean>` |
| `frontend/test/use-task-actions.spec.ts` (새) | 동작의 true/false 반환 시험 |
| `frontend/composables/useInterviewRefDraft.ts` (새) | 인터뷰 답변용 참조 초안(세션별 시드·서버 따라가기), 선택적 세션별 저장소 |
| `frontend/composables/useInterviewRefDraft.spec.ts` (새) | 저장소 복원 시험 |
| `frontend/pages/tasks/[id].vue` (수정) | 위 세 모듈을 쓰도록 바꿈 — 동작 불변 |
| `frontend/components/ApproveForm.vue` (새) | 승인 창의 내용·불러오기·`approve` |
| `frontend/components/ApproveForm.spec.ts` (새) | 폼 단독 시험 |
| `frontend/components/ApproveDialog.vue` (수정) | `q-dialog` 껍데기 — 열 때마다 폼을 새로 마운트 |
| `frontend/components/InterviewPanel.vue` (수정) | emit `answered`, `draft` 묶기(덧붙이기만) |
| `frontend/test/interview-panel-inbox-hooks.spec.ts` (새) | 위 두 가지 시험 |
| `frontend/components/inbox/InboxTaskPane.vue` (새) | 처리 영역: 고른 작업 폴링, 머리글, 상태별 본문 분배, `done`/`navigate`/`missing` 알림 |
| `frontend/components/inbox/InboxGateActions.vue` (새) | 분석완료·디자인 검토·수정 요청 승인·종료 승인 본문 |
| `frontend/components/inbox/InboxFailureActions.vue` (새) | 실패 6개 상태 본문 |
| `frontend/pages/inbox.vue` (새) | 목록 폴링·묶음·고르기(쿼리)·자동 이동·키보드·폰 창 |
| `frontend/test/inbox-page.spec.ts` (새) | 처리함 페이지 시험 |
| `frontend/test/inbox-pane-bodies.spec.ts` (새) | 상태별 본문 시험(Task 7) |
| `frontend/middleware/auth.global.ts` (수정) | `/inbox` 관리자 검사 |
| `frontend/layouts/default.vue` (수정) | 위 탭·하단 바 5칸·배지 이동 |
| `frontend/test/layout-default.spec.ts`, `frontend/test/auth-middleware.spec.ts` (수정) | 바뀐 동작에 맞춘 시험 |
| `frontend/scripts/hydration-smoke.mjs` (수정) | `/inbox` 경로 |
| `CLAUDE.md` (수정) | “자주 보는 코드” 표에 처리함 한 줄 |

---

### Task 1: 처리함 순수 로직 + 목록 정렬 확인

**Files:**
- Create: `frontend/composables/taskInbox.ts`
- Test: `frontend/composables/taskInbox.spec.ts`

**Interfaces:**
- Consumes: `attentionGroup`, `STAGES`(`composables/taskStages.ts`).
- Produces:
  - `type InboxGroupKey = 'answer' | 'plan' | 'approve' | 'design' | 'fix' | 'close' | 'failed'`
  - `const INBOX_GROUPS: { key: InboxGroupKey; label: string; statuses: string[] }[]`
  - `interface InboxRow { id: number; status: string; statusLabel: string; updatedAt: string; title: string; repoAlias: string | null; githubRepo: string; taskType?: string | null; retryCount: number; maxRetry: number }`
  - `function isInboxCandidate(t: Pick<InboxRow, 'status' | 'retryCount' | 'maxRetry'>): boolean`
  - `interface HandledMark { id: number; status: string; updatedAt: string }`
  - `function isHidden(row: Pick<InboxRow, 'id' | 'status' | 'updatedAt'>, handled: HandledMark[]): boolean`
  - `function pruneHandled(handled: HandledMark[], rows: Pick<InboxRow, 'id' | 'status' | 'updatedAt'>[]): HandledMark[]`
  - `interface InboxGroup<T> { key: InboxGroupKey; label: string; items: T[] }`
  - `function buildInbox<T extends InboxRow>(rows: T[], handled: HandledMark[]): InboxGroup<T>[]`
  - `function flattenInbox<T>(groups: InboxGroup<T>[]): T[]`
  - `function pickNext(flat: { id: number }[], excludeId?: number | null): number | null`
  - `function neighborId(flat: { id: number }[], currentId: number | null, dir: 1 | -1): number | null`
  - `const INBOX_PAGE_SIZE = 200`, `function inboxListParams(): { mine: string; size: number; sort: string }`
  - `function isTruncated(page: { content: unknown[]; totalElements?: number } | null): boolean`
  - `function countInbox(rows: Pick<InboxRow, 'status' | 'retryCount' | 'maxRetry'>[]): number`
  - `function shouldIgnoreShortcut(e: Pick<KeyboardEvent, 'key' | 'target' | 'altKey' | 'ctrlKey' | 'metaKey'>, dialogOpen: boolean): boolean`
  - `const AUTO_NEXT_KEY = 'netis-maker-inbox-auto-next'`, `function readAutoNext(): boolean`, `function writeAutoNext(on: boolean): void`

- [ ] **Step 1: 라이브 API에서 정렬이 먹는지 확인**

브라우저 창(Claude 브라우저)에서 라이브 관리자 화면(`https://app-win.micthebick.dev/tasks`, 로그인돼 있지 않으면 사용자에게 로그인을 부탁)을 열고 `javascript_tool`로 페이지의 토큰을 써서 GET 한 번:
```js
const t = JSON.parse(localStorage.getItem('netis-maker-auth')).accessToken
const r = await fetch('/api/tasks?mine=false&size=5&sort=updatedAt,desc', { headers: { Authorization: `Bearer ${t}` } }).then(r => r.json())
r.content.map(x => x.updatedAt)
```
(토큰은 `stores/auth.ts`의 `STORAGE_KEY`. 결과에 토큰을 싣지 않는다 — 마지막 식은 `updatedAt` 목록만.) Expected: 5개 `updatedAt`이 내림차순. 오름차순·무순서·오류면 **멈추고** 사용자에게 알린다(스펙 §6.1 — 서버 변경이 필요해진다).

- [ ] **Step 2: 실패하는 시험 쓰기** — `composables/taskInbox.spec.ts`

```ts
it('확인_필요_상태는_정확히_한_묶음에_든다', () => {
  const all = STAGES.flatMap((s) => s.statuses)
  for (const s of all) {
    const inGroups = INBOX_GROUPS.filter((g) => g.statuses.includes(s)).length
    expect(inGroups).toBe(attentionGroup({ status: s }, true) === 'attention' ? 1 : 0)
  }
})
it('묶음_순서와_이름', () => {
  expect(INBOX_GROUPS.map((g) => g.label)).toEqual(['답변 대기', '계획 확인', '인터뷰 시작 승인', '디자인 검토', '수정 요청 승인', '종료 승인', '실패'])
})
it('재시도_한도를_다_쓴_FAILED는_후보가_아니다', () => {
  expect(isInboxCandidate({ status: 'FAILED', retryCount: 3, maxRetry: 3 })).toBe(false)
  expect(isInboxCandidate({ status: 'FAILED', retryCount: 1, maxRetry: 3 })).toBe(true)
  expect(isInboxCandidate({ status: 'PR_CREATED', retryCount: 0, maxRetry: 3 })).toBe(false)
})
it('묶음_안은_updatedAt_오름차순이고_빈_묶음은_없다', () => {
  const g = buildInbox([row(1, 'INTERVIEW_INPUT', '2026-11-23T02:00:00Z'), row(2, 'INTERVIEW_INPUT', '2026-11-23T01:00:00Z'), row(3, 'CLOSE_FAILED', '2026-11-20T00:00:00Z')], [])
  expect(g.map((x) => x.key)).toEqual(['answer', 'failed'])
  expect(g[0].items.map((x) => x.id)).toEqual([2, 1])
})
it('처리한_건은_같은_status·updatedAt인_동안_숨는다', () => {
  const r = row(1, 'INTERVIEW_INPUT', '2026-11-23T01:00:00Z')
  expect(isHidden(r, [{ id: 1, status: 'INTERVIEW_INPUT', updatedAt: '2026-11-23T01:00:00Z' }])).toBe(true)
})
it('updatedAt이_바뀌면_같은_상태여도_다시_보인다', () => {
  const marks = [{ id: 1, status: 'INTERVIEW_INPUT', updatedAt: '2026-11-23T01:00:00Z' }]
  const r = row(1, 'INTERVIEW_INPUT', '2026-11-23T01:03:00Z')
  expect(isHidden(r, marks)).toBe(false)
  expect(pruneHandled(marks, [r])).toEqual([])
})
it('행이_사라지면_기록도_지운다', () => {
  expect(pruneHandled([{ id: 9, status: 'AWAITING_APPROVAL', updatedAt: 'x' }], [])).toEqual([])
})
it('다음_건은_뺀_건을_제외한_맨_위', () => {
  expect(pickNext([{ id: 5 }, { id: 6 }], 5)).toBe(6)
  expect(pickNext([{ id: 5 }], 5)).toBeNull()
  expect(pickNext([], null)).toBeNull()
})
it('이웃은_감싸지_않는다', () => {
  const f = [{ id: 1 }, { id: 2 }]
  expect(neighborId(f, 1, 1)).toBe(2)
  expect(neighborId(f, 2, 1)).toBeNull()
  expect(neighborId(f, 1, -1)).toBeNull()
  expect(neighborId(f, 99, 1)).toBe(1) // 목록에 없는 현재 건 → 맨 위
})
it('목록_조회_파라미터', () => {
  expect(inboxListParams()).toEqual({ mine: 'false', size: 200, sort: 'updatedAt,desc' })
})
it('200건_판정', () => {
  expect(isTruncated({ content: new Array(200), totalElements: 201 })).toBe(true)
  expect(isTruncated({ content: new Array(3), totalElements: 3 })).toBe(false)
  expect(isTruncated(null)).toBe(false)
})
it('입력칸·다이얼로그·수식키에서는_단축키를_무시', () => {
  const ta = document.createElement('textarea')
  expect(shouldIgnoreShortcut({ key: 'j', target: ta, altKey: false, ctrlKey: false, metaKey: false }, false)).toBe(true)
  expect(shouldIgnoreShortcut({ key: 'j', target: document.body, altKey: false, ctrlKey: false, metaKey: false }, true)).toBe(true)
  expect(shouldIgnoreShortcut({ key: 'j', target: document.body, altKey: false, ctrlKey: true, metaKey: false }, false)).toBe(true)
  expect(shouldIgnoreShortcut({ key: 'j', target: document.body, altKey: false, ctrlKey: false, metaKey: false }, false)).toBe(false)
})
it('자동_이동_저장값은_0일_때만_끔', () => {
  localStorage.removeItem(AUTO_NEXT_KEY)
  expect(readAutoNext()).toBe(true)
  writeAutoNext(false)
  expect(localStorage.getItem(AUTO_NEXT_KEY)).toBe('0')
  expect(readAutoNext()).toBe(false)
})
it('저장소가_예외를_던지면_켬', () => {
  const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  expect(readAutoNext()).toBe(true)
  expect(() => writeAutoNext(false)).not.toThrow()
  spy.mockRestore()
})
```
(`row(id, status, updatedAt)`는 `InboxRow`를 채우는 시험 도우미 — `retryCount: 0, maxRetry: 3`.) `contenteditable` 요소도 무시 대상에 한 줄 더한다.

- [ ] **Step 3: 시험이 실패하는지 확인** — Run: `npx vitest run composables/taskInbox.spec.ts` / Expected: FAIL(모듈 없음)

- [ ] **Step 4: `composables/taskInbox.ts` 구현** — 위 Interfaces 그대로. `buildInbox`는 `isInboxCandidate && !isHidden`만 남기고 `INBOX_GROUPS` 순서로 묶어 `updatedAt` 오름차순(`Date.parse`) 정렬, 빈 묶음 제외. `shouldIgnoreShortcut`은 `j`/`k`가 아닌 키도 true(무시). `writeAutoNext(true)`는 키를 지운다.

- [ ] **Step 5: 시험 통과 확인** — Run: `npx vitest run composables/taskInbox.spec.ts` / Expected: PASS

- [ ] **Step 6: 커밋** — `git add frontend/composables/taskInbox.ts frontend/composables/taskInbox.spec.ts && git commit -m "feat(inbox): 처리함 묶음·숨기기·다음 건 순수 로직"`

---

### Task 2: 작업 동작 공용 모듈 (`useTaskActions`) — 상세 동작 불변

**Files:**
- Create: `frontend/composables/taskDetailTypes.ts`, `frontend/composables/useTaskActions.ts`
- Modify: `frontend/pages/tasks/[id].vue` (타입 정의 ~67-184행, 동작 함수 245-264·281-290·374-500·641-693·744-760행)
- Test: `frontend/test/use-task-actions.spec.ts`

**Interfaces:**
- Produces:
  - `taskDetailTypes.ts`: `[id].vue`의 `AnalysisView`·`TaskMcpSpec`·`ImplementationView`·`DesignView`·`DeploymentView`·`EnvVar`·`AttachmentMeta`·`StageUsageView`·`TaskResponse`를 그대로 옮겨 `export`(이름·필드 불변).
  - `useTaskActions.ts`:
    ```ts
    export interface ActionTask { id: number; status: string; githubBranch: string; close?: CloseView | null; fix?: FixView | null; taskType?: string | null }
    export function useTaskActions<T extends ActionTask>(task: Ref<T | null>, refresh: () => unknown): TaskActions
    export interface TaskActions {
      approveImpl(): Promise<boolean>; retry(): Promise<boolean>; cancelTask(): Promise<boolean>; undeploy(): Promise<boolean>
      requestClose(): Promise<boolean>; approveClose(): Promise<boolean>; retryClose(): Promise<boolean>; cancelClose(): Promise<boolean>
      syncBase(): Promise<boolean>; rejectClose(): Promise<boolean>; skipPatch(): Promise<boolean>; skipArtifact(): Promise<boolean>; resendMail(): Promise<boolean>
      approveFix(): Promise<boolean>; rejectFix(): Promise<boolean>; withdrawFix(): Promise<boolean>; abortFix(): Promise<boolean>
      canCancelClose: ComputedRef<boolean>; canSyncBaseNow: ComputedRef<boolean>; canSkipArtifactClose: ComputedRef<boolean>
      canSkipPatchClose: ComputedRef<boolean>; canAbort: ComputedRef<boolean>; canAdminRequestCloseLost: ComputedRef<boolean>
      baseSyncOpen: ComputedRef<boolean>; abortLabel: ComputedRef<string>
    }
    ```
    true = 서버가 받아들임, false = 확인창 취소·서버 오류·중복 클릭 무시. 확인창·알림 문구·`CLOSE_ACTION_NAMES`·`closeInFlight`·`fetchDeferredWarning`은 상세의 것을 그대로 옮긴다. `$q.dialog().onOk/onCancel/onDismiss` 기반(`undeploy`·`approveFix`·`rejectFix`·`rejectClose`)은 `new Promise<boolean>`로 감싸 취소·닫기에서 false. `isAdmin`은 `useAuthStore()`에서 읽는다. `canFixRequest`(소유자 판정 필요)는 각 화면에 `canRequestFix(...)` 한 줄로 둔다.

- [ ] **Step 1: 실패하는 시험 쓰기** — `test/use-task-actions.spec.ts`. 작은 호스트 컴포넌트에서 `useTaskActions(ref(task), refresh)`를 부르고 실제 Quasar 확인창 버튼을 누른다(`test/task-detail-close.spec.ts`의 `disposeGlobalDialogs` 방식 재사용).
```ts
it('approveClose_확인하면_true이고_close/approve를_부른다', async () => {
  authStub.isAdmin = true
  useApiMock.mockResolvedValueOnce([]) // fetchDeferredWarning
  useApiMock.mockResolvedValueOnce({})
  const p = actions.approveClose()
  await clickDialogButton('병합하고 종료')
  expect(await p).toBe(true)
  expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', expect.objectContaining({ method: 'POST' }))
  expect(refresh).toHaveBeenCalled()
})
it('확인창을_취소하면_false이고_API를_부르지_않는다', async () => { /* retryClose → '취소' → false, close/retry 호출 없음 */ })
it('서버가_거절하면_false', async () => { /* useApiMock.mockRejectedValueOnce({ data: { message: 'x' } }) → retry() → false */ })
it('rejectFix는_사유를_입력해_확인하면_true이고_reason을_싣는다', async () => { /* body { reason: '사유' } */ })
it('approveFix는_고른_방식을_싣는다', async () => { /* body { implementationMode: 'REVIEW' } */ })
it('undeploy는_확인창_취소면_false', async () => { /* ... */ })
it('closeAction_진행_중의_두번째_호출은_false', async () => { /* 첫 호출 응답 전 두 번째 → false, API 1회 */ })
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run test/use-task-actions.spec.ts` / Expected: FAIL(모듈 없음)

- [ ] **Step 3: `taskDetailTypes.ts`·`useTaskActions.ts` 구현하고 `[id].vue`가 쓰게 바꾸기** — `[id].vue`는 `const actions = useTaskActions(task, refresh)`로 받아 기존 이름(`approve`·`retry`·`cancelTask`·`undeploy`·`requestClose`…·`canCancelClose`…)을 지역 이름으로 연결한다(템플릿·`onAction`은 손대지 않는다). 반환값은 쓰지 않는다.

- [ ] **Step 4: 새 시험과 기존 상세 시험 통과 확인** — Run: `npx vitest run test/use-task-actions.spec.ts test/task-detail-` / Expected: 전부 PASS(기존 `task-detail-*` 파일은 고치지 않음)

- [ ] **Step 5: 커밋** — `git commit -m "refactor(tasks): 작업 상세 동작을 useTaskActions로 — 처리함과 공용"`

---

### Task 3: 인터뷰 참조 초안 공용 모듈 (`useInterviewRefDraft`)

**Files:**
- Create: `frontend/composables/useInterviewRefDraft.ts`
- Modify: `frontend/pages/tasks/[id].vue` (306-363행: `isInterviewPhase`·참조 초안·`answerRefs`·`canChangeRefs`·`showRefRow`)
- Test: `frontend/composables/useInterviewRefDraft.spec.ts`

**Interfaces:**
- Produces:
  ```ts
  export interface RefDraftTask { status: string; interviewSessionId: number | null; refRepos?: RefRepoView[] }
  export interface RefDraftEntry { picked: RefRepoView[]; syncedTo: RefRepoInput[] }
  export function useInterviewRefDraft(task: Ref<RefDraftTask | null>, opts?: { store?: Map<number, RefDraftEntry> }): {
    isInterviewPhase: ComputedRef<boolean>; pickedRefs: Ref<RefRepoView[]>; answerRefs: ComputedRef<RefRepoInput[] | null>
    canChangeRefs: ComputedRef<boolean>; showRefRow: ComputedRef<boolean>
  }
  ```
  `store`가 없으면 상세의 지금 규칙 그대로(세션이 바뀌면 서버 값으로 시드). `store`가 있으면 세션이 바뀔 때 떠나는 세션의 `{picked, syncedTo}`를 저장하고, 들어오는 세션에 저장값이 있으면 그것으로 되살린 뒤 같은 “따라가기” 규칙을 적용한다.

- [ ] **Step 1: 실패하는 시험 쓰기**
```ts
it('store_없이_세션이_바뀌면_서버_값으로_시드', () => { /* 상세와 같은 동작 */ })
it('store가_있으면_돌아온_세션의_보내지_않은_초안을_되살린다', () => {
  // 세션 7에서 pickedRefs에 참조 하나 추가 → task를 세션 8로 → 다시 세션 7(서버 refRepos는 그대로)
  // → pickedRefs가 추가한 초안, answerRefs가 그 집합
})
it('되살린_세션에서도_서버가_바뀌었고_보낸_변경이면_서버를_따른다', () => { /* 초안 = 서버 → syncedTo 갱신 */ })
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run composables/useInterviewRefDraft.spec.ts` / Expected: FAIL

- [ ] **Step 3: 구현하고 `[id].vue`가 `useInterviewRefDraft(task)`(store 없음)를 쓰게 바꾸기** — `interviewLive`·`onInterviewStatus`는 상세에 남긴다.

- [ ] **Step 4: 통과 확인** — Run: `npx vitest run composables/useInterviewRefDraft.spec.ts test/task-detail-refs.spec.ts test/task-detail-` / Expected: PASS(기존 파일 무수정)

- [ ] **Step 5: 커밋** — `git commit -m "refactor(tasks): 인터뷰 참조 초안을 useInterviewRefDraft로 — 세션별 저장소 선택"`

---

### Task 4: 승인 폼 분리 (`ApproveForm`)

**Files:**
- Create: `frontend/components/ApproveForm.vue`
- Modify: `frontend/components/ApproveDialog.vue`
- Test: `frontend/components/ApproveForm.spec.ts`

**Interfaces:**
- Produces:
  - `ApproveForm` props `{ task: SummaryTask; context?: 'dialog' | 'inbox' }`(기본 `'dialog'`), emits `approved`·`cancel`·`update:approving(boolean)`, `defineExpose({ approve })`. 마운트 때 한 번 `init()`(지금의 `watch(show)` 대신 `onMounted`) — `task` prop이 다시 들어와도(폴링) 다시 불러오지 않는다. 늦은 응답 버리기는 `loadSeq` + 언마운트 표시(`show.value` 검사 대신). 루트는 `.dialog-body`(스크롤 본문)와 `q-card-actions`(취소·`승인하고 인터뷰 시작`) 두 요소 — `inheritAttrs: false`. `context === 'inbox'`면 “다음 단계” 안내 마지막 문장이 `첫 질문이 오면 처리함의 답변 대기에 올라옵니다`이고 취소 버튼이 없다.
  - `ApproveDialog`: props·`v-model`·emit `approved`·`defineExpose({ approve })`·폰 위 바·`data-test` 이름 불변. `openSeq`를 `watch(show, (o) => { if (o) openSeq.value++ }, { immediate: true })`로 올려 `<ApproveForm :key="openSeq" ref="form" v-model:approving="approving" @approved="..." @cancel="show = false" />` — 열 때마다 새로 마운트(= 지금의 “열 때마다 초기화”). `persistent`·닫기 버튼 비활성은 `approving`으로.

- [ ] **Step 1: 실패하는 시험 쓰기** — `components/ApproveForm.spec.ts`
```ts
it('창_없이_마운트해도_지난_인터뷰를_불러와_승인_바디를_보낸다', async () => { /* /api/tasks/{id}/interviews → approve() → POST /approve body.model */ })
it('같은_작업의_task_prop이_다시_들어와도_고른_값을_되돌리지_않는다', async () => {
  // ModelEffortFields에서 다른 모델 고름 → setProps({ task: { ...task } }) → approve() 바디의 model이 고른 값
  // /interviews 조회는 1번만
})
it('inbox_문맥이면_처리함_안내와_취소_버튼_없음', async () => { /* '첫 질문이 오면 처리함의 답변 대기에 올라옵니다' 포함 */ })
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run components/ApproveForm.spec.ts` / Expected: FAIL

- [ ] **Step 3: `ApproveForm.vue`로 옮기고 `ApproveDialog.vue`를 껍데기로** — 스타일(`.db-work-warning`·`.previous-hint`·`.usage-slot`·`.next-step`·`.ref-add-panel` 등)은 폼으로, `.approve-topbar`는 창에 둔다.

- [ ] **Step 4: 통과 확인** — Run: `npx vitest run components/ApproveForm.spec.ts components/ApproveDialog.spec.ts test/task-detail-approve.spec.ts test/dialogs-mobile.spec.ts` / Expected: PASS(`ApproveDialog.spec.ts`·`task-detail-approve.spec.ts` 무수정)

- [ ] **Step 5: 커밋** — `git commit -m "refactor(approve): 승인 창 내용을 ApproveForm으로 — 처리함에 펼쳐 쓰기"`

---

### Task 5: 대화 패널 `answered`·`draft`

**Files:**
- Modify: `frontend/components/InterviewPanel.vue` (props 36-54행, emits 55-60행, `sendAnswer` 성공 경로 ~398행, `answer` 123행)
- Test: `frontend/test/interview-panel-inbox-hooks.spec.ts`

**Interfaces:**
- Produces: emit `(e: 'answered'): void` — `kind`가 `'QUESTION'`이 아닐 때 답변·계획 고쳐 달라기 전송이 성공한 직후 한 번. prop `draft?: string` + emit `update:draft` — `draft`가 주어지면 마운트 때 `answer`를 그 값으로 채우고 `answer`가 바뀔 때마다 `update:draft`를 보낸다(주어지지 않으면 내보내지 않는다).

- [ ] **Step 1: 실패하는 시험 쓰기**
```ts
it('작업_인터뷰_답변이_성공하면_answered를_한_번_보낸다', async () => { /* 입력대기 스냅샷 → 입력 → 전송 → emitted('answered').length === 1 */ })
it('전송이_409면_answered가_없다', async () => {})
it('질문_세션은_answered를_보내지_않는다', async () => {})
it('draft를_주면_입력창이_그_값으로_시작하고_입력하면_update:draft', async () => {})
it('draft를_안_주면_update:draft를_보내지_않는다', async () => {})
```
(마운트·스냅샷 목은 `components/InterviewPanel.spec.ts`의 방식을 따른다.)

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run test/interview-panel-inbox-hooks.spec.ts` / Expected: FAIL

- [ ] **Step 3: 구현**

- [ ] **Step 4: 통과 확인** — Run: `npx vitest run test/interview-panel-inbox-hooks.spec.ts components/InterviewPanel.spec.ts test/task-detail-simple-interview.spec.ts test/questions-detail.spec.ts` / Expected: PASS(`InterviewPanel.spec.ts` 무수정)

- [ ] **Step 5: 커밋** — `git commit -m "feat(interview): 패널에 answered 알림과 draft 묶기 — 처리함용, 기존 화면 불변"`

---

### Task 6: 처리함 페이지 + 처리 영역 — 인터뷰 세 상태 끝까지

**Files:**
- Create: `frontend/pages/inbox.vue`, `frontend/components/inbox/InboxTaskPane.vue`
- Test: `frontend/test/inbox-page.spec.ts`

**Interfaces:**
- Consumes: Task 1 전부, `useTaskActions`(Task 2), `TaskResponse`(Task 2), `useInterviewRefDraft`·`RefDraftEntry`(Task 3), `ApproveForm`(Task 4), `InterviewPanel`의 `answered`·`draft`(Task 5), `nextAction`·`ageOf`·`CHIP`(taskStages), `taskTypeLabel`, `taskRefContext`, `RefRepoChips`, `TaskRefChangeMenu`, `downloadAttachment`(attachmentDownload).
- Produces:
  - `InboxTaskPane` props `{ taskId: number; answerDrafts: Map<number, string>; refDraftStore: Map<number, RefDraftEntry>; canPrev: boolean; canNext: boolean; fill?: boolean }`, emits `done(mark: HandledMark)`·`navigate(dir: 1 | -1)`·`missing()`. 안에서 `useTaskPolling(() => useApi('/api/tasks/' + taskId))` — 부모가 `:key="taskId"`로 다시 마운트한다. 동작 직전의 `{id, status, updatedAt}`을 잡아 성공하면 `done`으로 보낸다. 조회가 404면 `missing`.
  - `pages/inbox.vue`: `useTaskPolling(() => useApi('/api/tasks', { params: inboxListParams() }))`, `handled: Ref<HandledMark[]>`(목록이 올 때마다 `pruneHandled`), 고른 id = `route.query.task`, 쿼리 바꾸기는 `navigateTo({ query: { task } }, { replace: true })`(지우기는 `query: {}`).

- [ ] **Step 1: 실패하는 시험 쓰기** — `test/inbox-page.spec.ts`. `useRoute`는 쿼리를 바꿀 수 있는 반응형 객체로, `navigateTo`는 그 쿼리를 바꾸는 목으로 전역에 넣는다. `useApiMock`은 경로로 답하는 구현(`/api/tasks` → 목록 페이지, `/api/tasks/{id}` → 상세, `/api/interviews/{sid}` → 스냅샷, `/api/tasks/{id}/interviews` → `[]`). 관리자(`authStub.isAdmin = true`, `isAuthenticated: true`).
```ts
it('묶음과_건수를_순서대로_그리고_쿼리가_없으면_맨_위를_고른다', async () => {
  // 목록: #2 AWAITING_APPROVAL, #5 INTERVIEW_INPUT, #9 PR_CREATED
  // → 묶음 ['답변 대기', '인터뷰 시작 승인'], #9 없음, navigateTo({ query: { task: '5' } }, { replace: true })
})
it('입력대기면_참조_줄과_대화_패널을_세션_키로_그린다', async () => {})
it('답변이_성공하면_다음_건으로_넘어가고_그_건은_바로_숨는다', async () => {
  // 패널 answered → navigateTo(task: '2'), 목록에서 #5 행 없음(같은 status·updatedAt)
})
it('토글을_끄면_처리한_건에_머문다', async () => { /* localStorage '0' */ })
it('승인대기는_ApproveForm(inbox)를_펼치고_승인_성공이면_다음_건', async () => {})
it('인터뷰_취소(close)는_넘어가지_않는다', async () => {})
it('같은_건의_done이_두_번_와도_한_번만_넘어간다', async () => {})
it('다른_곳에서_처리되면_머물고_쓰던_답변은_남는다', async () => {
  // 답변 입력 → 상세 폴링 응답을 INTERVIEWING으로 → 패널 유지 + '에이전트가 답을 만드는 중입니다'
  // → 상세를 PR_CREATED로 → '지금 처리할 것이 없습니다(현재: PR생성)', navigateTo 호출 없음
  // → 다른 건 골랐다 돌아오면 answerDrafts의 글이 입력창에 있다
})
it('쿼리의_작업이_목록에_없어도_조회되면_그_작업을_보인다', async () => {})
it('쿼리의_작업이_404면_맨_위를_고른다', async () => {})
it('j_k로_이동하고_입력칸_안에서는_무시', async () => {})
it('목록이_비면_빈_상태', async () => { /* '확인할 작업이 없습니다' */ })
it('200건을_넘으면_안내', async () => { /* totalElements 201 */ })
it('폰_폭:_행을_누르면_전체_화면_창을_열고_마지막_건을_처리하면_닫고_알린다', async () => {
  // setViewportWidth(390), 창 루트가 div, '모두 처리했습니다' 알림
})
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run test/inbox-page.spec.ts` / Expected: FAIL

- [ ] **Step 3: `InboxTaskPane.vue` 구현** — 머리글(스펙 §5.3: 제목·상태 칩·레포·브랜치·타입·요청자·대기 시간·`이전`/`다음`·`상세 열기`(`href="/tasks/{id}" target="_blank"`)·접힌 `등록 설명·첨부`·다음 할 일 한 줄·실패 사유) + 본문 분배:
  - `INTERVIEW_INPUT`·`INTERVIEW_REVIEW`·`INTERVIEWING`: 참조 줄(`useInterviewRefDraft(task, { store: refDraftStore })`, `TaskRefChangeMenu`는 패널 상태가 `AWAITING_INPUT`일 때만 활성) + `<InterviewPanel :key="interviewSessionId" :session-id … :answer-ref-repos="answerRefs" :session-ref-repos="task.refRepos ?? []" :draft … @update:draft … @answered="emitDone" @confirmed="emitDone" @close="refresh" @status="onStatus">` — `onStatus`는 상세의 `onInterviewStatus`와 같은 규칙(입력대기·계획 완료로 바뀌면 `refresh`). `INTERVIEWING`이면 위에 `에이전트가 답을 만드는 중입니다`.
  - `AWAITING_APPROVAL`: `<ApproveForm :key="task.id" :task context="inbox" @approved="emitDone" />` + `작업 취소`(`actions.cancelTask`).
  - 그 밖의 확인 필요 상태: 이 Task에서는 다음 할 일 한 줄 + `상세 열기`(Task 7이 채운다).
  - 확인 필요가 아니고 `INTERVIEWING`도 아니면: `지금 처리할 것이 없습니다(현재: {statusLabel})` + 다음 할 일 + `상세 열기` + `다음 건`(`navigate(1)`).
- [ ] **Step 4: `pages/inbox.vue` 구현** — 데스크톱: `row no-wrap` 왼쪽 목록(폭 320px, 세로 스크롤) + 오른쪽 `InboxTaskPane`. 폰: 목록만, 고른 건이 있으면 `maximized` 창(`<div>` 루트, 위 바: 닫기·제목·이전/다음) 안에 `InboxTaskPane fill`. `done(mark)` 처리: 이미 같은 `id`의 기록이 있으면 무시, 아니면 기록을 더하고 목록 `refresh()`, 자동 이동이 켜져 있으면 `pickNext(flat, mark.id)`로 이동(없으면 쿼리 지우기, 폰이면 창 닫고 `모두 처리했습니다` 알림). 키보드는 `window` `keydown`(언마운트 때 제거) + `shouldIgnoreShortcut(e, !!document.querySelector('.q-dialog'))` — 폰 창이 열려 있으면 무시된다. `answerDrafts`·`refDraftStore`는 페이지의 `reactive(new Map())`.

- [ ] **Step 5: 통과 확인** — Run: `npx vitest run test/inbox-page.spec.ts` / Expected: PASS

- [ ] **Step 6: 커밋** — `git commit -m "feat(inbox): 처리함 페이지 — 목록·자동 다음 건·인터뷰 답변·계획 확정·인터뷰 시작 승인"`

---

### Task 7: 나머지 상태 본문 — 승인 계열·실패 계열

**Files:**
- Create: `frontend/components/inbox/InboxGateActions.vue`, `frontend/components/inbox/InboxFailureActions.vue`
- Modify: `frontend/components/inbox/InboxTaskPane.vue` (분배에 두 컴포넌트 연결, 창 3개 배치)
- Test: `frontend/test/inbox-pane-bodies.spec.ts`

**Interfaces:**
- Consumes: `useTaskActions`의 동작·판정(Task 2), `DesignReviewCard`(emit `refresh`), `FixRequestHistory`, `ImplementationRetryDialog`·`DeployEnvDialog`·`FixRequestDialog`(emit `submitted`), `closeSteps`(taskClose), `mrRef`, `canRequestFix`.
- Produces: 두 컴포넌트 모두 props `{ task: TaskResponse; actions: TaskActions }`, emit `done()`(성공한 동작 뒤) + 창 열기 요청 emit `open(kind: 'implRetry' | 'deployEnv' | 'fixRequest')`. 창 3개는 `InboxTaskPane`이 두고(상세와 같은 props), `submitted`면 `done`.

본문·버튼(스펙 §5.3 표, 상세 `pages/tasks/[id].vue`와 대조):
| 상태 | 본문 | 버튼 → 동작 |
|---|---|---|
| COMPLETED | — | `구현 승인` → `approveImpl` |
| DESIGN_REVIEW | `DesignReviewCard`(`is-admin` true) | 카드 `refresh` → `done` |
| FIX_REVIEW | `FixRequestHistory` + 배포 주소 링크 | `수정 승인` → `approveFix`, `반려` → `rejectFix` |
| CLOSE_REVIEW | PR/MR 링크·배포 주소·`closeSteps` 목록 | `종료 승인` → `approveClose`, `반려` → `rejectClose` |
| FAILED·DESIGN_FAILED | (실패 사유는 머리글) | `재시도` → `retry` |
| IMPLEMENTATION_FAILED | PR/MR·브랜치 링크 | `구현 재시도` → 창 `implRetry`, `canAbort`면 `{{ abortLabel }}` → `abortFix` |
| DEPLOY_FAILED | — | `재배포` → 창 `deployEnv`(mode `redeploy`), 가능하면 `수정 요청` → 창 `fixRequest` |
| DEPLOY_LOST | 배포 주소 | `재배포` → 창, `종료 요청` → `requestClose`, `배포 중지` → `undeploy`, 가능하면 `수정 요청` → 창 |
| CLOSE_FAILED | `closeSteps` 목록 | `종료 재시도` → `retryClose`, `canSyncBaseNow`면 `기준 브랜치 반영` → `syncBase`, `canCancelClose`면 `종료 취소` → `cancelClose`, `canSkipArtifactClose`면 `산출물 없이 마무리` → `skipArtifact`, `canSkipPatchClose`면 `패치 없이 마무리` → `skipPatch` |

`data-test`는 `inbox-act-<동작>`(예: `inbox-act-approve-close`).

- [ ] **Step 1: 실패하는 시험 쓰기** — 표의 줄마다 하나:
```ts
it.each([
  ['COMPLETED', 'inbox-act-approve-impl', '/approve'],
  ['FAILED', 'inbox-act-retry', '/retry'],
  ['CLOSE_REVIEW', 'inbox-act-approve-close', '/close/approve'],
  ['CLOSE_FAILED', 'inbox-act-retry-close', '/close/retry'],
  ['DEPLOY_LOST', 'inbox-act-undeploy', '/undeploy'],
  ['FIX_REVIEW', 'inbox-act-approve-fix', '/fix-requests/approve'],
])('%s: 버튼을_누르고_확인하면_API를_부르고_done', async (status, testId, suffix) => {})
it('FIX_REVIEW_반려_확인창_취소면_done이_없다', async () => {})
it('CLOSE_FAILED_병합_전이면_종료_취소·기준_브랜치_반영이_보이고_병합_뒤면_안_보인다', async () => {})
it('IMPLEMENTATION_FAILED_재시도_창이_submitted면_done', async () => {})
it('DEPLOY_FAILED_재배포_창이_submitted면_done', async () => {})
it('DESIGN_REVIEW_카드가_refresh를_보내면_done', async () => {})
it('확인_필요_상태마다_본문이_있다', () => {
  // INBOX_GROUPS의 모든 상태로 pane을 마운트해 '상세에서 처리' 임시 문구가 남아 있지 않은지
})
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run test/inbox-pane-bodies.spec.ts` / Expected: FAIL

- [ ] **Step 3: 두 컴포넌트 구현, `InboxTaskPane`에 연결**(Task 6의 임시 문구 제거)

- [ ] **Step 4: 통과 확인** — Run: `npx vitest run test/inbox-pane-bodies.spec.ts test/inbox-page.spec.ts` / Expected: PASS

- [ ] **Step 5: 커밋** — `git commit -m "feat(inbox): 디자인·수정·종료 승인과 실패 처리 본문"`

---

### Task 8: 들어가는 곳 — 미들웨어·위 탭·하단 바·배지

**Files:**
- Modify: `frontend/middleware/auth.global.ts:16`, `frontend/layouts/default.vue`
- Test: `frontend/test/auth-middleware.spec.ts`, `frontend/test/layout-default.spec.ts`

**Interfaces:**
- Consumes: `inboxListParams`·`countInbox`(Task 1).
- Produces: 데스크톱 위 탭 `처리함`(`to="/inbox"`, 관리자만, “작업” 다음) + 배지 `data-test="inbox-badge"`(색 `warning`, 0이면 없음). 폰 하단 바: 관리자 `작업 · 처리함 · 질문 · 요청 · 관리`(처리함 아이콘 `inbox`, 배지 `data-test="inbox-badge"`), 관리자에게는 `tasks-badge` 없음. 관리자 배지 수 = `countInbox((await useApi('/api/tasks', { params: inboxListParams() })).content)`, 경로가 바뀔 때·폭과 무관하게. 관리자가 아니면 지금 코드 그대로(`mine=true&size=50`, 폰에서만 `tasks-badge`).

- [ ] **Step 1: 시험 고치기·더하기**
  - `auth-middleware.spec.ts`에 `비관리자가_/inbox로_가면_/tasks로` 추가, `관리자는_/inbox_통과` 추가.
  - `layout-default.spec.ts`: “lt.md: … 작업·질문·요청·관리 4탭” 시험을 관리자 5탭 `['작업', '처리함', '질문', '요청', '관리']`로 바꾸고, 관리자 배지 시험(171·203행 근처)을 `inbox-badge`로 옮긴다(관리자는 `tasks-badge`가 없음). 새 시험: `관리자_넓은_화면_위_탭에_처리함과_배지`, `관리자_배지는_sort=updatedAt,desc&size=200으로_센다`(`useApiMock` 호출 params 확인), `재시도_한도를_다_쓴_FAILED는_세지_않는다`. 비관리자 시험은 그대로 둔다.

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run test/auth-middleware.spec.ts test/layout-default.spec.ts` / Expected: FAIL(새·바뀐 시험만)

- [ ] **Step 3: 구현** — 미들웨어: `if ((to.path.startsWith('/admin') || to.path === '/inbox') && !auth.isAdmin) return redirectTo('/tasks')`. 레이아웃: 템플릿 분기는 `useHydrationSafeAuth()`의 `isAdmin`.

- [ ] **Step 4: 통과 확인** — Run: `npx vitest run test/auth-middleware.spec.ts test/layout-default.spec.ts test/hydration-auth-gate.spec.ts` / Expected: PASS

- [ ] **Step 5: 390px 확인** — 하단 바 5칸 글자가 잘리지 않는지는 vitest가 못 본다 → Task 9의 화면 확인 목록에 넣는다.

- [ ] **Step 6: 커밋** — `git commit -m "feat(inbox): 처리함 탭·하단 바·배지와 관리자 경로 검사"`

---

### Task 9: 스모크 경로·문서·전체 확인·PR

**Files:**
- Modify: `frontend/scripts/hydration-smoke.mjs` (AUTH_PATHS 34행~), `CLAUDE.md`(“자주 보는 코드” 표)

- [ ] **Step 1: 스모크 경로 더하기** — `AUTH_PATHS`에 `'/inbox'`, `'/inbox?task=2'`(목 작업 2 = AWAITING_APPROVAL — 승인 폼을 그린다). 목록 목은 pathname만 비교하므로 정렬 쿼리도 같은 목이 답한다. `/api/tasks/2/interviews`는 기존 목이 `[]`로 답한다. 스모크는 이 worktree에서 돌리지 않는다(CI).

- [ ] **Step 2: CLAUDE.md 표에 한 줄** — `| 처리함(관리자 확인 필요 작업을 한 화면에서 — 목록·자동 다음 건·상태별 처리, 스펙 docs/superpowers/specs/2026-11-23-task-inbox-design.md) | frontend/pages/inbox.vue, frontend/components/inbox/*, frontend/composables/taskInbox.ts·useTaskActions.ts·useInterviewRefDraft.ts, frontend/components/ApproveForm.vue |`

- [ ] **Step 3: 전체 시험·린트** — Run: `npx vitest run` / Expected: 전부 PASS. Run: `npx eslint <이 브랜치에서 새로 만들거나 바꾼 .ts·.vue 파일들>` / Expected: 오류 0, 새로 만든 파일은 경고 0, 바꾼 파일은 바꾼 줄에서 새 경고가 없음(정션 `node_modules`에 eslint 플러그인이 없어 실행이 안 되면 PR 본문에 적고 넘어간다).

- [ ] **Step 4: graphify 갱신** — Run(본 체크아웃이 아니라 이 worktree에서): `graphify update .` / Expected: 오류 없음(명령이 없으면 건너뛰고 PR 본문에 적는다).

- [ ] **Step 5: 커밋·푸시·PR** — `git commit -m "chore(inbox): 하이드레이션 스모크 경로와 CLAUDE.md"` → `git push -u origin feature/task-inbox` → `gh pr create --base main --title "feat: 처리함 — 여러 작업의 인터뷰·승인을 한 화면에서" --body-file <scratchpad의 본문 파일>`(본문: 요약, 스펙·계획 링크, 바뀐 공용 모듈과 “기존 상세 시험 무수정 통과”, 배포 = 프론트 `.output` 재빌드만, 라이브 확인 목록 — 데스크톱·390px 하단 바 5칸·처리함 목록·답변 → 다음 건 → 에이전트 질문 다시 올라옴·인터뷰 시작 승인 폼·상세 기존 동작 하나, 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`).

- [ ] **Step 6: CI 확인** — PR의 CI 세 개(특히 frontend 잡의 “Hydration smoke”)가 통과하는지 본다. 실패하면 로그를 읽고 고친다.
