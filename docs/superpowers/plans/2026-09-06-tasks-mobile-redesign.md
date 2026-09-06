# 작업 탭 모바일 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 1024px 미만(lt.md)에서 작업 목록·상세·다이얼로그·전역 내비를 모바일에 맞게 재구성하고, 데스크톱 상세에는 진행 스테퍼와 "다음 할 일"을 추가한다. 데스크톱 보드는 바꾸지 않는다.

**Architecture:** 단계·상태·전이 정의는 기존 `frontend/composables/taskStages.ts`에 순수 함수(`attentionGroup`/`sortForMobile`/`nextAction`/`stageSteps`)로 확장하고, 모바일 전용 컴포넌트(`components/tasks/*`)가 이 함수들을 소비한다. 페이지는 `$q.screen.lt.md`로 모바일/데스크톱 트리를 분기한다. 진행 이력은 신규 `GET /api/tasks/{id}/history`(기존 `task_status_history` 테이블, ACL은 `TaskService.getForView`)로 읽는다. 전역 내비는 `layouts/default.vue`에 `QFooter` 탭을 추가한다.

**Tech Stack:** Nuxt 3 + Quasar 2 (`$q.screen`, `q-dialog maximized`, `q-expansion-item`, `q-footer`), Vitest + @vue/test-utils(`test/mocks/screen.ts`의 `setViewportWidth`), markdown-it(`composables/useMarkdown.ts`), Spring Boot 3.4(Java 21) + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-06-tasks-mobile-redesign-design.md` (결정 §9 반영본). 디자인 캔버스: https://claude.ai/code/artifact/b22b91bf-013d-41e7-993f-7fde7e41c7e6 · 작업 파일 `/Users/micthebick/IdeaProjects/design_netismaker_tasks_mobile/`.

## Global Constraints

- 모바일 분기 기준은 **`$q.screen.lt.md`(<1024px)** 하나만 쓴다. `xs`는 카드 메타 2줄 허용에만 쓴다.
- 데스크톱(≥1024px) 목록 보드(`.stage-row` 칸반)·동작은 **무변경**. 기존 스펙 파일(`tasks-index-usage.spec.ts`, `tasks-form-*.spec.ts`, `task-detail-*.spec.ts`)은 그대로 통과해야 한다.
- 프론트 코드 스타일: **작은따옴표 + 세미콜론 없음**, 2-space, trailing comma, `<script setup lang="ts">`. `prettier --write`는 새로 만든 파일에만.
- **클래스명에 Quasar 반응형 헬퍼 이름(`xs sm md lg xl`, `gt-*`, `lt-*`, `*-hide`)을 쓰지 말 것**. `q-dialog` 콘텐츠 루트는 `<div>`(또는 `q-card`)로 둘 것.
- 탭 타깃 ≥ 44px. 모바일에서 가로 오버플로 0(`document.documentElement.scrollWidth === innerWidth`).
- 상태 문자열은 영문 enum 이름(`PR_CREATED` 등)으로만 비교한다. 한글은 `statusLabel`/dbValue 표시 전용.
- 테스트 명령: 프론트 `cd frontend && npx vitest run <spec>`; 전체 `npx vitest run`; 타입 `npm run typecheck`. Java 단위 `./gradlew test --tests '<Class>'`; 통합은 `RUN_TESTCONTAINERS=true ./gradlew test --tests '<Class>'`(Docker 필요).
- 커밋 메시지는 한국어 + 타입 접두(`feat(front):`, `feat(api):`, `test:`, `docs:`), 끝에 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` 한 줄.
- 작업 브랜치 `feature/tasks-mobile-redesign`(베이스 `spec/tasks-mobile-redesign`) — 워크트리 `.claude/worktrees/tasks-mobile`에서 작업.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `frontend/composables/taskStages.ts` (수정) | 단계/상태/전이 정의 + **신규 순수 함수** `stageSteps`, `attentionGroup`, `sortForMobile`, `nextAction`, `cancelable`, `DEPLOY_ACTIVE_STATUSES`(index.vue에서 이동) |
| `frontend/composables/taskStages.spec.ts` (수정) | 위 함수 표 기반 테스트 |
| `frontend/components/tasks/TaskCardCompact.vue` (신규) | 모바일 카드 1장: 제목·상태 칩·4세그먼트·메타·다음 할 일 1줄·⋮ |
| `frontend/components/tasks/TaskActionSheet.vue` (신규) | 하단 시트: 상세/PR/전이(MOVES)/취소/삭제 |
| `frontend/components/tasks/TaskListMobile.vue` (신규) | 요약 스트립 + 필터 칩 + 그룹 리스트 + FAB. 데이터는 부모가 준다 |
| `frontend/components/tasks/TaskProgressStepper.vue` (신규) | 4단계 스테퍼(원+연결선, compact=세그먼트) |
| `frontend/components/tasks/TaskNextAction.vue` (신규) | 다음 할 일 배너(desktop)/하단 바(mobile) |
| `frontend/components/tasks/TaskHistoryTimeline.vue` (신규) | 진행 이력 타임라인(API 호출 포함) |
| `frontend/components/tasks/AdminSectionTabs.vue` (신규) | 관리 3페이지 상단 세그먼트(lt.md) |
| `frontend/components/MarkdownViewerDialog.vue` (신규) | 전체화면 마크다운 뷰어 |
| `frontend/pages/tasks/index.vue` (수정) | lt.md → `TaskListMobile`, 그 외 기존 보드 |
| `frontend/pages/tasks/[id].vue` (수정) | 스테퍼·다음 할 일(양쪽), 모바일 아코디언·하단 바, 마크다운 렌더 |
| `frontend/layouts/default.vue` (수정) | lt.md 하단 내비 + 헤더 탭 숨김 |
| `frontend/components/ApproveDialog.vue`, `DesignReviewCard.vue`, `InterviewHistoryDialog.vue` (수정) | 다이얼로그 maximized + 고정 폭 제거 |
| `frontend/assets/css/main.css` (수정) | `.md-scroll`, `.q-page` 가로 클립 |
| `src/main/java/.../dto/TaskHistoryResponse.java` (신규), `service/TaskService.java`, `controller/TaskController.java` (수정) | 이력 API |
| `src/test/java/.../controller/TaskHistoryApiIntegrationTest.java` (신규) | 이력 API 통합 테스트 |

---

## S1. 즉시 개선

### Task 1: 다이얼로그 5종 — 모바일 전체화면 + 고정 폭 제거

**Files:**
- Modify: `frontend/components/ApproveDialog.vue:52-53`
- Modify: `frontend/components/DesignReviewCard.vue:186-187`
- Modify: `frontend/components/InterviewHistoryDialog.vue` (`.history-dialog-card` 스타일 + `<q-dialog>`)
- Modify: `frontend/pages/tasks/index.vue:594-595` (등록 다이얼로그)
- Modify: `frontend/pages/tasks/[id].vue:750-751` (환경변수 다이얼로그)
- Test: `frontend/test/dialogs-mobile.spec.ts` (신규)

**Interfaces:**
- Produces: 규칙 — 모든 `<q-dialog>`에 `:maximized="$q.screen.lt.md"`, 카드 `style="min-width: Npx"` → `class="dialog-card dialog-card--N"` 대신 인라인 `style="width: min(Npx, 100vw)"`. 각 SFC는 `const $q = useQuasar()`가 이미 있다(index.vue·[id].vue·ApproveDialog·InterviewHistoryDialog). `DesignReviewCard.vue`에 없으면 `import { useQuasar } from 'quasar'` + `const $q = useQuasar()` 추가.

- [ ] **Step 1: 실패 테스트 작성** — `frontend/test/dialogs-mobile.spec.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import ApproveDialog from '../components/ApproveDialog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// q-dialog는 body 포털로 렌더된다 — document.body에서 찾는다.
function maximizedInner() {
  return document.body.querySelector('.q-dialog__inner--maximized')
}

describe('다이얼로그 모바일 전체화면 (스펙 2026-09-06 §4.3)', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('lt.md에서는 ApproveDialog가 maximized로 열리고 고정 min-width가 없다', async () => {
    useApiMock.mockResolvedValue([]) // McpPicker의 GET /api/mcp-catalog
    await setViewportWidth(390)
    const w = mount(ApproveDialog, { props: { taskId: 1, modelValue: true }, attachTo: document.body })
    await flushPromises()
    expect(maximizedInner()).not.toBeNull()
    const card = document.body.querySelector('.q-dialog .q-card') as HTMLElement
    expect(card.getAttribute('style') ?? '').not.toMatch(/min-width:\s*\d+px/)
    w.unmount()
  })

  it('데스크톱(1024)에서는 maximized가 아니다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(ApproveDialog, { props: { taskId: 1, modelValue: true }, attachTo: document.body })
    await flushPromises()
    expect(maximizedInner()).toBeNull()
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인** — Run: `cd frontend && npx vitest run test/dialogs-mobile.spec.ts` · Expected: FAIL (maximized 없음 / min-width 존재)

- [ ] **Step 3: 구현** — 5개 다이얼로그를 같은 패턴으로 바꾼다.

`ApproveDialog.vue`:
```vue
  <q-dialog v-model="show" :maximized="$q.screen.lt.md">
    <q-card style="width: min(480px, 100vw)">
```
`DesignReviewCard.vue`(반려 다이얼로그):
```vue
    <q-dialog v-model="rejectDialog" persistent :maximized="$q.screen.lt.md">
      <q-card style="width: min(480px, 100vw)">
```
`InterviewHistoryDialog.vue`: `<q-dialog ... :maximized="$q.screen.lt.md">`, 스타일 `.history-dialog-card { width: min(720px, 100vw); max-width: 100vw; }` (기존 `min-width: 720px; max-width: 90vw` 교체).
`pages/tasks/index.vue`(등록): `<q-dialog v-model="showCreate" persistent :maximized="$q.screen.lt.md">` + `<q-card style="width: min(520px, 100vw)">`.
`pages/tasks/[id].vue`(환경변수): `<q-dialog v-model="envDialog" :maximized="$q.screen.lt.md">` + `<q-card style="width: min(480px, 100vw)">`.
환경변수 행(`row items-center q-gutter-xs no-wrap`)은 모바일에서 세로로 쌓이도록 `:class="$q.screen.lt.md ? 'column q-gutter-y-xs' : 'row items-center q-gutter-xs no-wrap'"`로 바꾸고 각 `q-input`의 `style="flex: 1"`/`flex: 2`는 유지(column에서는 무해).

- [ ] **Step 4: 통과 확인** — Run: `cd frontend && npx vitest run test/dialogs-mobile.spec.ts test/tasks-form-attachments.spec.ts test/tasks-form-repo-select.spec.ts test/task-detail-usage.spec.ts` · Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add frontend/components/ApproveDialog.vue frontend/components/DesignReviewCard.vue frontend/components/InterviewHistoryDialog.vue frontend/pages/tasks/index.vue "frontend/pages/tasks/[id].vue" frontend/test/dialogs-mobile.spec.ts
git commit -m "fix(front): 다이얼로그 5종 모바일 전체화면(maximized) + 고정 min-width 제거 (390px 잘림)"
```

### Task 2: 마크다운/pre 가로 오버플로 봉쇄

**Files:**
- Modify: `frontend/assets/css/main.css` (끝에 추가)
- Modify: `frontend/pages/tasks/[id].vue` (요청 상세 `pre`, 실패 사유 `pre`, 분석 결과 `pre`, 구현 로그 `pre`)
- Modify: `frontend/components/DesignReviewCard.vue` (디자인 마크다운 컨테이너 — `grep -n "designMarkdown" DesignReviewCard.vue`로 위치 확인)
- Test: `frontend/test/task-detail-mobile.spec.ts` (신규 — 이후 Task 9에서 확장)

**Interfaces:**
- Produces: 전역 클래스 `.md-scroll { max-width: 100%; overflow-x: auto; }`(긴 표/코드가 컨테이너 안에서만 스크롤), `.q-page { overflow-x: clip; }`(안전망).

- [ ] **Step 1: 실패 테스트** — `frontend/test/task-detail-mobile.spec.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { useApiMock } from './mocks/nuxt'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = {
  attachTo: document.body,
  global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } },
}

export const baseTask = {
  id: 42, githubRepo: 'acme/widgets', repoAlias: 'Widgets', githubBranch: 'main', title: '제목', description: '설명',
  status: 'PR_CREATED', statusLabel: 'PR생성', requesterId: 'user1', retryCount: 0, maxRetry: 3, failureReason: null,
  mcpsExtra: [], envVars: [], interviewSessionId: null, createdAt: '2026-08-16T00:00:00Z', updatedAt: '2026-08-16T00:00:00Z',
  model: 'claude-opus-5', effort: 'high', designRequested: false,
  analysis: { markdownResult: '# 요약\n\n| a | b |\n|---|---|\n| 1 | 2 |', subtasksJson: '[]', durationMs: 1000, approved: true, approvedBy: null, approvedAt: null, completedAt: '2026-08-16T00:00:00Z' },
  design: null,
  implementation: { prUrl: 'https://github.com/acme/widgets/pull/1', prNumber: 1, headBranch: 'feature/x', headSha: 'abcdef1234567', implementationLog: null },
  deployment: null, attachments: [], stageUsage: [], totalCostUsd: 0.42, totalTokens: 123000,
}

describe('pages/tasks/[id] — 가로 오버플로 봉쇄 (스펙 2026-09-06 D1)', () => {
  it('요청 상세와 분석 결과 블록은 md-scroll 컨테이너 안에 있다', async () => {
    useApiMock.mockResolvedValue(baseTask)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const scrollers = w.findAll('.md-scroll')
    expect(scrollers.length).toBeGreaterThanOrEqual(2)
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/task-detail-mobile.spec.ts` → FAIL (`.md-scroll` 0개)

- [ ] **Step 3: 구현** — `main.css` 끝에:
```css
/* 긴 표/코드 블록은 컨테이너 안에서만 가로 스크롤 — 페이지 폭을 밀어내지 않게 (스펙 2026-09-06 D1) */
.md-scroll {
  max-width: 100%;
  overflow-x: auto;
}
.md-scroll pre,
.md-scroll table {
  max-width: 100%;
}
/* 안전망: 페이지 레벨 가로 스크롤 금지 */
.q-page {
  overflow-x: clip;
}
```
`[id].vue`: `<pre style="white-space: pre-wrap">{{ task.description }}</pre>` → `<div class="md-scroll"><pre style="white-space: pre-wrap; margin: 0">{{ task.description }}</pre></div>`; 실패 사유 `pre`, 분석 결과 `pre`(font-family Pretendard), 구현 로그 `pre`도 같은 방식으로 `.md-scroll` div로 감싼다. `DesignReviewCard.vue`의 `designMarkdown`을 그리는 요소도 `<div class="md-scroll">`로 감싼다.

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run test/task-detail-mobile.spec.ts test/task-detail-usage.spec.ts test/task-detail-attachments.spec.ts` → PASS

- [ ] **Step 5: Commit**
```bash
git add frontend/assets/css/main.css "frontend/pages/tasks/[id].vue" frontend/components/DesignReviewCard.vue frontend/test/task-detail-mobile.spec.ts
git commit -m "fix(front): 상세 페이지 마크다운/pre 가로 오버플로를 컨테이너 안으로 봉쇄 (.md-scroll, q-page clip)"
```

---

## S2. 모바일 목록

### Task 3: `taskStages.ts` 순수 함수 — 주의 그룹·정렬·스테퍼 단계·다음 할 일

**Files:**
- Modify: `frontend/composables/taskStages.ts`
- Modify: `frontend/pages/tasks/index.vue:146-160` (`DEPLOY_ACTIVE_STATUSES`, `cancelable`을 composable import로 교체)
- Test: `frontend/composables/taskStages.spec.ts`

**Interfaces (Produces — 이후 모든 태스크가 사용):**
```ts
export type AttentionGroup = 'attention' | 'active' | 'done' | 'closed'
export const ATTENTION_GROUPS: { key: AttentionGroup; label: string; color: string }[]
// [{attention:'확인 필요','#ef6c00'}, {active:'진행 중','#1565c0'}, {done:'완료','#00695c'}, {closed:'취소됨','#757575'}]
export const DEPLOY_ACTIVE_STATUSES: string[]      // index.vue에서 이동
export function cancelable(task: { status: string }): boolean   // ['PENDING','AWAITING_APPROVAL']
export function attentionGroup(task: { status: string }, isAdmin: boolean): AttentionGroup
export interface MobileGroup<T> { key: AttentionGroup; label: string; color: string; items: T[] }
export function sortForMobile<T extends { status: string; updatedAt: string }>(tasks: T[], isAdmin: boolean): MobileGroup<T>[]
export type StepState = 'done' | 'current' | 'future' | 'skipped' | 'failed'
export interface StageStep { key: string; label: string; color: string; state: StepState }
export function stageSteps(task: { status: string; designRequested?: boolean }): StageStep[]   // 항상 4개(분석·디자인·구현·배포)
export type NextActionKind = 'approve-interview' | 'open-interview' | 'approve-impl' | 'review-design' | 'deploy' | 'redeploy' | 'open-pr' | 'open-url' | 'retry'
export interface NextAction { text: string; primary?: { label: string; icon: string; kind: NextActionKind } }
export function nextAction(task: { status: string; implementation?: { prUrl: string | null } | null; deployment?: { deployUrl: string | null } | null }, isAdmin: boolean): NextAction | null
```

- [ ] **Step 1: 실패 테스트** — `taskStages.spec.ts` 끝에 추가 (기존 import에 새 이름 추가: `attentionGroup, sortForMobile, stageSteps, nextAction, cancelable, DEPLOY_ACTIVE_STATUSES`)

```ts
describe('attentionGroup / sortForMobile (스펙 2026-09-06 §4.1)', () => {
  const t = (id: number, status: string, updatedAt: string) => ({ id, status, updatedAt })
  it.each([
    ['INTERVIEW_INPUT', false, 'attention'], ['INTERVIEW_REVIEW', false, 'attention'],
    ['FAILED', false, 'attention'], ['IMPLEMENTATION_FAILED', false, 'attention'], ['DESIGN_FAILED', false, 'attention'],
    ['DEPLOY_FAILED', false, 'attention'], ['DEPLOY_LOST', false, 'attention'],
    ['AWAITING_APPROVAL', true, 'attention'], ['COMPLETED', true, 'attention'], ['DESIGN_REVIEW', true, 'attention'],
    ['AWAITING_APPROVAL', false, 'active'], ['COMPLETED', false, 'active'], ['DESIGN_REVIEW', false, 'active'],
    ['INTERVIEWING', false, 'active'], ['PENDING', false, 'active'], ['IN_PROGRESS', false, 'active'], ['APPROVED', false, 'active'],
    ['IMPLEMENTING', false, 'active'], ['DESIGN_PENDING', false, 'active'], ['DESIGNING', false, 'active'],
    ['DEPLOY_PENDING', false, 'active'], ['DEPLOYING', false, 'active'], ['UNDEPLOY_PENDING', false, 'active'], ['UNDEPLOYING', false, 'active'],
    ['PR_CREATED', false, 'done'], ['DEPLOYED', false, 'done'], ['CANCELLED', false, 'closed'],
  ])('%s (admin=%s) → %s', (status, isAdmin, group) => {
    expect(attentionGroup({ status }, isAdmin)).toBe(group)
  })

  it('그룹 순서 확인 필요→진행 중→완료→취소됨, 그룹 안은 updatedAt 내림차순, 빈 그룹 제외', () => {
    const groups = sortForMobile(
      [t(1, 'PR_CREATED', '2026-09-01T00:00:00Z'), t(2, 'INTERVIEW_INPUT', '2026-08-01T00:00:00Z'),
       t(3, 'PR_CREATED', '2026-09-03T00:00:00Z'), t(4, 'CANCELLED', '2026-09-02T00:00:00Z')],
      false,
    )
    expect(groups.map((g) => g.key)).toEqual(['attention', 'done', 'closed'])
    expect(groups[1]!.items.map((i) => i.id)).toEqual([3, 1])
    expect(groups[0]!.label).toBe('확인 필요')
  })
})

describe('stageSteps', () => {
  it('PR생성(디자인 미요청): 분석 done · 디자인 skipped · 구현 current · 배포 future', () => {
    expect(stageSteps({ status: 'PR_CREATED', designRequested: false }).map((s) => s.state))
      .toEqual(['done', 'skipped', 'current', 'future'])
  })
  it('디자인승인대기(디자인 요청): 분석 done · 디자인 current', () => {
    expect(stageSteps({ status: 'DESIGN_REVIEW', designRequested: true }).map((s) => s.state))
      .toEqual(['done', 'current', 'future', 'future'])
  })
  it('배포실패: 배포 단계가 failed', () => {
    const steps = stageSteps({ status: 'DEPLOY_FAILED', designRequested: true })
    expect(steps[3]!.state).toBe('failed')
    expect(steps.map((s) => s.label)).toEqual(['분석', '디자인', '구현', '배포'])
  })
  it('승인대기: 분석 current, 나머지 future(디자인 요청 시)/skipped(미요청)', () => {
    expect(stageSteps({ status: 'AWAITING_APPROVAL', designRequested: true })[0]!.state).toBe('current')
    expect(stageSteps({ status: 'AWAITING_APPROVAL', designRequested: false })[1]!.state).toBe('skipped')
  })
  it('취소됨: 전부 future', () => {
    expect(stageSteps({ status: 'CANCELLED' }).every((s) => s.state === 'future')).toBe(true)
  })
})

describe('nextAction (스펙 2026-09-06 §4.2 표)', () => {
  const pr = { status: 'PR_CREATED', implementation: { prUrl: 'https://x/pull/1' }, deployment: null }
  it('승인대기: 관리자는 승인 주 행동, 요청자는 문구만', () => {
    expect(nextAction({ status: 'AWAITING_APPROVAL' }, true)?.primary?.kind).toBe('approve-interview')
    expect(nextAction({ status: 'AWAITING_APPROVAL' }, false)?.primary).toBeUndefined()
  })
  it('입력대기/플랜승인대기: 관리자 주 행동은 인터뷰 열기', () => {
    expect(nextAction({ status: 'INTERVIEW_INPUT' }, true)?.primary?.kind).toBe('open-interview')
    expect(nextAction({ status: 'INTERVIEW_REVIEW' }, true)?.primary?.kind).toBe('open-interview')
    expect(nextAction({ status: 'INTERVIEW_INPUT' }, false)?.primary).toBeUndefined()
  })
  it('분석완료: 관리자 구현 승인 · 디자인승인대기: 관리자 디자인 검토', () => {
    expect(nextAction({ status: 'COMPLETED' }, true)?.primary?.kind).toBe('approve-impl')
    expect(nextAction({ status: 'DESIGN_REVIEW' }, true)?.primary?.kind).toBe('review-design')
  })
  it('PR생성: 관리자 배포, 요청자 PR 열기(prUrl 있을 때만)', () => {
    expect(nextAction(pr, true)?.primary?.kind).toBe('deploy')
    expect(nextAction(pr, false)?.primary?.kind).toBe('open-pr')
    expect(nextAction({ status: 'PR_CREATED', implementation: { prUrl: null } }, false)?.primary).toBeUndefined()
  })
  it('배포완료: 접속 URL 열기(양쪽) · 배포실패/중단: 관리자 재배포', () => {
    expect(nextAction({ status: 'DEPLOYED', deployment: { deployUrl: 'https://t' } }, false)?.primary?.kind).toBe('open-url')
    expect(nextAction({ status: 'DEPLOY_FAILED' }, true)?.primary?.kind).toBe('redeploy')
    expect(nextAction({ status: 'DEPLOY_LOST' }, false)?.primary).toBeUndefined()
  })
  it('분석실패/디자인실패: 재시도 · 구현실패: 문구만 · 취소됨: null · 진행 중: 문구만', () => {
    expect(nextAction({ status: 'FAILED' }, false)?.primary?.kind).toBe('retry')
    expect(nextAction({ status: 'DESIGN_FAILED' }, true)?.primary?.kind).toBe('retry')
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, true)?.primary).toBeUndefined()
    expect(nextAction({ status: 'CANCELLED' }, true)).toBeNull()
    expect(nextAction({ status: 'IMPLEMENTING' }, true)?.text).toContain('진행 중')
  })
  it('cancelable/DEPLOY_ACTIVE_STATUSES는 index.vue와 같은 집합', () => {
    expect(cancelable({ status: 'PENDING' })).toBe(true)
    expect(cancelable({ status: 'PR_CREATED' })).toBe(false)
    expect(DEPLOY_ACTIVE_STATUSES).toEqual(['DEPLOYED', 'DEPLOY_LOST', 'DEPLOY_PENDING', 'DEPLOYING', 'UNDEPLOY_PENDING', 'UNDEPLOYING'])
  })
})
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run composables/taskStages.spec.ts` → FAIL (export 없음)

- [ ] **Step 3: 구현** — `taskStages.ts` 끝에 추가 (기존 코드 무변경):

```ts
// ── 모바일 리스트 (스펙 2026-09-06 §4.1) ─────────────────────────────────

export type AttentionGroup = 'attention' | 'active' | 'done' | 'closed'

export const ATTENTION_GROUPS: { key: AttentionGroup; label: string; color: string }[] = [
  { key: 'attention', label: '확인 필요', color: '#ef6c00' },
  { key: 'active', label: '진행 중', color: '#1565c0' },
  { key: 'done', label: '완료', color: '#00695c' },
  { key: 'closed', label: '취소됨', color: '#757575' },
]

/** 서버 softDelete 가드와 동일 집합 — 배포 이력이 활성이면 먼저 중지 후 삭제 */
export const DEPLOY_ACTIVE_STATUSES = [
  'DEPLOYED', 'DEPLOY_LOST', 'DEPLOY_PENDING', 'DEPLOYING', 'UNDEPLOY_PENDING', 'UNDEPLOYING',
]

export function cancelable(task: { status: string }): boolean {
  return ['PENDING', 'AWAITING_APPROVAL'].includes(task.status)
}

const FAILED_STATUSES = ['FAILED', 'IMPLEMENTATION_FAILED', 'DESIGN_FAILED', 'DEPLOY_FAILED', 'DEPLOY_LOST']
/** 사람 입력을 기다리는 상태 — 요청자에게도 "확인 필요"로 보인다 */
const HUMAN_INPUT_STATUSES = ['INTERVIEW_INPUT', 'INTERVIEW_REVIEW']
/** 관리자 판단을 기다리는 상태 — 관리자에게만 "확인 필요" */
const ADMIN_GATE_STATUSES = ['AWAITING_APPROVAL', 'COMPLETED', 'DESIGN_REVIEW']
const DONE_STATUSES = ['PR_CREATED', 'DEPLOYED']

export function attentionGroup(task: { status: string }, isAdmin: boolean): AttentionGroup {
  const s = task.status
  if (s === 'CANCELLED') return 'closed'
  if (FAILED_STATUSES.includes(s) || HUMAN_INPUT_STATUSES.includes(s)) return 'attention'
  if (isAdmin && ADMIN_GATE_STATUSES.includes(s)) return 'attention'
  if (DONE_STATUSES.includes(s)) return 'done'
  return 'active'
}

export interface MobileGroup<T> {
  key: AttentionGroup
  label: string
  color: string
  items: T[]
}

/** 확인 필요 → 진행 중 → 완료 → 취소됨. 그룹 안은 updatedAt 내림차순. 빈 그룹은 제외. */
export function sortForMobile<T extends { status: string; updatedAt: string }>(
  tasks: T[],
  isAdmin: boolean,
): MobileGroup<T>[] {
  return ATTENTION_GROUPS.map((g) => ({
    ...g,
    items: tasks
      .filter((t) => attentionGroup(t, isAdmin) === g.key)
      .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()),
  })).filter((g) => g.items.length > 0)
}

// ── 스테퍼 (스펙 §4.2) ──────────────────────────────────────────────────

export type StepState = 'done' | 'current' | 'future' | 'skipped' | 'failed'
export interface StageStep {
  key: string
  label: string
  color: string
  state: StepState
}

const DESIGN_STATUSES = STAGES.find((s) => s.key === 'design')!.statuses

/** 파이프라인 4단계의 표시 상태. 디자인 미요청 작업은 디자인 단계를 skipped로 표시한다. */
export function stageSteps(task: { status: string; designRequested?: boolean }): StageStep[] {
  const pipeline = STAGES.filter((s) => !s.terminal)
  const idx = pipeline.findIndex((s) => s.statuses.includes(task.status))
  const failed = FAILED_STATUSES.includes(task.status)
  const inDesign = DESIGN_STATUSES.includes(task.status)
  return pipeline.map((s, i) => {
    let state: StepState = 'future'
    if (idx >= 0) {
      if (i < idx) state = 'done'
      else if (i === idx) state = failed ? 'failed' : 'current'
    }
    if (s.key === 'design' && !task.designRequested && !inDesign && state !== 'done') state = 'skipped'
    if (s.key === 'design' && !task.designRequested && state === 'done') state = 'skipped'
    return { key: s.key, label: s.name, color: s.color, state }
  })
}

// ── 다음 할 일 (스펙 §4.2 표) ───────────────────────────────────────────

export type NextActionKind =
  | 'approve-interview' | 'open-interview' | 'approve-impl' | 'review-design'
  | 'deploy' | 'redeploy' | 'open-pr' | 'open-url' | 'retry'

export interface NextAction {
  text: string
  primary?: { label: string; icon: string; kind: NextActionKind }
}

const RUNNING_TEXT = '진행 중 — 완료되면 상태가 바뀝니다'

export function nextAction(
  task: {
    status: string
    implementation?: { prUrl: string | null } | null
    deployment?: { deployUrl: string | null } | null
  },
  isAdmin: boolean,
): NextAction | null {
  const s = task.status
  switch (s) {
    case 'CANCELLED':
      return null
    case 'AWAITING_APPROVAL':
      return isAdmin
        ? { text: '승인 대기 — 승인하면 인터뷰가 시작됩니다', primary: { label: '승인 — 인터뷰 시작', icon: 'forum', kind: 'approve-interview' } }
        : { text: '관리자 승인을 기다리는 중입니다' }
    case 'INTERVIEWING':
      return { text: '인터뷰 진행 중', primary: { label: '대화 열기', icon: 'forum', kind: 'open-interview' } }
    case 'INTERVIEW_INPUT':
      return isAdmin
        ? { text: '답변 대기 — 인터뷰 질문에 답해 주세요', primary: { label: '답변하기', icon: 'reply', kind: 'open-interview' } }
        : { text: '관리자 답변을 기다리는 중입니다' }
    case 'INTERVIEW_REVIEW':
      return isAdmin
        ? { text: '플랜 검토 — 확인 후 구현을 진행하세요', primary: { label: '플랜 확인', icon: 'fact_check', kind: 'open-interview' } }
        : { text: '플랜 검토 중입니다' }
    case 'COMPLETED':
      return isAdmin
        ? { text: '분석 완료 — 구현 승인 대기', primary: { label: '구현 승인', icon: 'rocket_launch', kind: 'approve-impl' } }
        : { text: '분석 완료 — 관리자 승인을 기다리는 중입니다' }
    case 'DESIGN_REVIEW':
      return isAdmin
        ? { text: '디자인 검토 — 승인 또는 피드백', primary: { label: '디자인 검토', icon: 'palette', kind: 'review-design' } }
        : { text: '디자인 검토 중입니다' }
    case 'PR_CREATED':
      if (isAdmin) return { text: 'PR 생성됨 — 배포할 수 있습니다', primary: { label: '배포', icon: 'rocket_launch', kind: 'deploy' } }
      return task.implementation?.prUrl
        ? { text: 'PR 생성됨', primary: { label: 'PR 열기', icon: 'open_in_new', kind: 'open-pr' } }
        : { text: 'PR 생성됨' }
    case 'DEPLOYED':
      return task.deployment?.deployUrl
        ? { text: '배포 완료', primary: { label: '접속 URL 열기', icon: 'open_in_new', kind: 'open-url' } }
        : { text: '배포 완료' }
    case 'FAILED':
    case 'DESIGN_FAILED':
      return { text: '실패 — 사유 확인 후 재시도', primary: { label: '재시도', icon: 'refresh', kind: 'retry' } }
    case 'IMPLEMENTATION_FAILED':
      return { text: '구현 실패 — 사유를 확인하세요' }
    case 'DEPLOY_FAILED':
    case 'DEPLOY_LOST':
      return isAdmin
        ? { text: s === 'DEPLOY_LOST' ? '배포 컨테이너 중단 — 재배포 필요' : '배포 실패 — 사유 확인 후 재배포', primary: { label: '재배포', icon: 'refresh', kind: 'redeploy' } }
        : { text: '배포 실패 — 관리자 확인이 필요합니다' }
    default:
      return { text: RUNNING_TEXT }
  }
}
```

그리고 `pages/tasks/index.vue`: 로컬 `DEPLOY_ACTIVE_STATUSES` 상수와 `cancelable` 함수를 삭제하고 import에 추가:
```ts
import { buildStages, stageAccepts, cancelable, DEPLOY_ACTIVE_STATUSES, type MoveDef, type StageCard } from '~/composables/taskStages'
```

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run composables/taskStages.spec.ts test/tasks-index-usage.spec.ts` → PASS

- [ ] **Step 5: Commit**
```bash
git add frontend/composables/taskStages.ts frontend/composables/taskStages.spec.ts frontend/pages/tasks/index.vue
git commit -m "feat(front): taskStages에 모바일 순수 함수 추가 — attentionGroup/sortForMobile/stageSteps/nextAction (표 기반 테스트)"
```

### Task 4: `TaskCardCompact.vue` — 모바일 카드

**Files:**
- Create: `frontend/components/tasks/TaskCardCompact.vue`
- Test: `frontend/components/tasks/TaskCardCompact.spec.ts`

**Interfaces:**
- Consumes: `CHIP`, `ageOf`, `stageSteps`, `attentionGroup`, `nextAction` from `~/composables/taskStages`
- Produces:
```ts
export interface CardTask {
  id: number
  title: string
  status: string
  statusLabel: string
  repoAlias: string | null
  githubRepo: string
  githubBranch: string
  createdAt: string
  updatedAt: string
  requesterId?: string
  designRequested?: boolean
  implementation?: { prUrl: string | null; prNumber: number | null } | null
  deployment?: { deployUrl: string | null } | null
}
// props: { task: CardTask; isAdmin: boolean }  emits: 'open' (카드 탭), 'menu' (⋮)
// data-test: task-card-compact, card-menu, card-next, card-step (×4, data-state=StepState)
```

- [ ] **Step 1: 실패 테스트** — `frontend/components/tasks/TaskCardCompact.spec.ts`

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskCardCompact from './TaskCardCompact.vue'

const base = {
  id: 18, title: '장비 현황 화면 개발', status: 'PR_CREATED', statusLabel: 'PR생성', repoAlias: 'Netis7.0',
  githubRepo: 'micthebick84/netis7.0', githubBranch: 'main', createdAt: '2026-08-24T00:00:00Z',
  updatedAt: '2026-08-24T00:00:00Z', designRequested: true,
  implementation: { prUrl: 'https://github.com/x/pull/13', prNumber: 13 }, deployment: null,
}

describe('TaskCardCompact (스펙 2026-09-06 §4.1 카드)', () => {
  it('제목·상태 칩·4세그먼트(분석 done·디자인 done·구현 current·배포 future)·메타·PR 링크를 그린다', () => {
    const w = mount(TaskCardCompact, { props: { task: base, isAdmin: false } })
    expect(w.text()).toContain('#18')
    expect(w.text()).toContain('장비 현황 화면 개발')
    expect(w.text()).toContain('PR생성')
    const steps = w.findAll('[data-test="card-step"]')
    expect(steps.map((s) => s.attributes('data-state'))).toEqual(['done', 'done', 'current', 'future'])
    expect(w.text()).toContain('Netis7.0')
    expect(w.text()).toContain('main')
    const pr = w.find('a[href="https://github.com/x/pull/13"]')
    expect(pr.exists()).toBe(true)
    expect(pr.text()).toContain('PR #13')
    w.unmount()
  })

  it('확인 필요 그룹(승인대기·관리자)에서만 "다음 할 일" 한 줄을 보여준다', () => {
    const waiting = { ...base, status: 'AWAITING_APPROVAL', statusLabel: '승인대기', implementation: null }
    const admin = mount(TaskCardCompact, { props: { task: waiting, isAdmin: true } })
    expect(admin.find('[data-test="card-next"]').text()).toContain('승인')
    admin.unmount()
    const user = mount(TaskCardCompact, { props: { task: waiting, isAdmin: false } })
    expect(user.find('[data-test="card-next"]').exists()).toBe(false) // 요청자에겐 진행 중 그룹
    user.unmount()
    const done = mount(TaskCardCompact, { props: { task: base, isAdmin: true } })
    expect(done.find('[data-test="card-next"]').exists()).toBe(false) // 완료 그룹은 표시 안 함
    done.unmount()
  })

  it('카드 탭은 open, ⋮은 menu만 emit(open 전파 없음)하고 ⋮ 탭 타깃은 44px 이상', async () => {
    const w = mount(TaskCardCompact, { props: { task: base, isAdmin: true } })
    await w.find('[data-test="card-menu"]').trigger('click')
    expect(w.emitted('menu')).toHaveLength(1)
    expect(w.emitted('open')).toBeUndefined()
    await w.find('[data-test="task-card-compact"]').trigger('click')
    expect(w.emitted('open')).toHaveLength(1)
    expect(w.find('[data-test="card-menu"]').attributes('aria-label')).toBe('작업 메뉴')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run components/tasks/TaskCardCompact.spec.ts` → FAIL (파일 없음)

- [ ] **Step 3: 구현** — `frontend/components/tasks/TaskCardCompact.vue`

```vue
<script setup lang="ts">
// 모바일 작업 카드 (스펙 2026-09-06 §4.1, 캔버스 Main). 제목 2줄 · 상태 칩 + 4세그먼트 · 메타 1줄 · 다음 할 일 1줄(확인 필요 그룹만).
// ⋮(44px)은 액션 시트를 여는 부모 이벤트 — 카드 탭(open)으로 전파되지 않게 stop.
import { CHIP, ageOf, stageSteps, attentionGroup, nextAction } from '~/composables/taskStages'

export interface CardTask {
  id: number
  title: string
  status: string
  statusLabel: string
  repoAlias: string | null
  githubRepo: string
  githubBranch: string
  createdAt: string
  updatedAt: string
  requesterId?: string
  designRequested?: boolean
  implementation?: { prUrl: string | null; prNumber: number | null } | null
  deployment?: { deployUrl: string | null } | null
}

const props = defineProps<{ task: CardTask; isAdmin: boolean }>()
const emit = defineEmits<{ (e: 'open'): void; (e: 'menu'): void }>()

const chip = computed(() => CHIP[props.task.status] ?? ['#f5f5f5', '#616161'])
const steps = computed(() => stageSteps(props.task))
const current = computed(() => steps.value.find((s) => s.state === 'current' || s.state === 'failed'))
const next = computed(() =>
  attentionGroup(props.task, props.isAdmin) === 'attention' ? nextAction(props.task, props.isAdmin) : null,
)
const deployHost = computed(() => {
  const url = props.task.deployment?.deployUrl
  if (!url) return null
  try {
    return new URL(url).host
  } catch {
    return url
  }
})

function segStyle(state: string, color: string) {
  if (state === 'done') return { background: '#a5d6a7' }
  if (state === 'current') return { background: color }
  if (state === 'failed') return { background: '#c62828' }
  if (state === 'skipped') return { background: 'repeating-linear-gradient(90deg, #e0e0e0 0 4px, transparent 4px 8px)' }
  return { background: '#eeeeee' }
}
</script>

<template>
  <div class="compact-card" data-test="task-card-compact" @click="emit('open')">
    <div class="row no-wrap items-start">
      <div class="col title-row">
        <span class="card-id">#{{ task.id }}</span>
        <span class="card-title">{{ task.title }}</span>
      </div>
      <q-btn
        flat
        round
        icon="more_vert"
        color="grey-7"
        aria-label="작업 메뉴"
        class="card-menu"
        data-test="card-menu"
        @click.stop="emit('menu')"
      />
    </div>

    <div class="row items-center no-wrap step-row">
      <span class="card-status" :style="{ background: chip[0], color: chip[1] }">{{ task.statusLabel }}</span>
      <div class="col column seg-col">
        <div class="row no-wrap seg-bar">
          <span
            v-for="s in steps"
            :key="s.key"
            class="seg"
            data-test="card-step"
            :data-state="s.state"
            :style="segStyle(s.state, s.color)"
          />
        </div>
        <div class="seg-labels">
          <span
            v-for="s in steps"
            :key="s.key"
            :style="{ color: s.state === 'current' || s.state === 'failed' ? s.color : s.state === 'done' ? '#9e9e9e' : '#bdbdbd', fontWeight: s === current ? 700 : 400 }"
          >{{ s.label }}</span>
        </div>
      </div>
    </div>

    <div class="card-meta">
      <q-icon name="folder" size="14px" color="grey-6" />
      <span class="ellipsis">{{ task.repoAlias ?? task.githubRepo }}</span>
      <span class="dot">·</span>
      <span class="ellipsis">{{ task.githubBranch }}</span>
      <span class="dot">·</span>
      <span>{{ ageOf(task.updatedAt) }} 전</span>
      <template v-if="task.implementation?.prUrl">
        <span class="dot">·</span>
        <a :href="task.implementation.prUrl" target="_blank" rel="noopener" class="card-link" @click.stop>
          PR #{{ task.implementation.prNumber }}<q-icon name="open_in_new" size="13px" />
        </a>
      </template>
      <template v-else-if="deployHost">
        <span class="dot">·</span>
        <a :href="task.deployment!.deployUrl!" target="_blank" rel="noopener" class="card-link ellipsis" @click.stop>
          {{ deployHost }}<q-icon name="open_in_new" size="13px" />
        </a>
      </template>
    </div>

    <div v-if="next" class="card-next" data-test="card-next">
      <q-icon name="play_arrow" size="18px" />
      <span class="col">다음 할 일 · {{ next.primary?.label ?? next.text }}</span>
      <q-icon name="chevron_right" size="20px" />
    </div>
  </div>
</template>

<style scoped>
.compact-card {
  border: 1px solid rgba(0, 0, 0, 0.14);
  border-radius: 6px;
  background: #fff;
  padding: 12px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.07);
  cursor: pointer;
}
.title-row { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.card-id { font-size: 12px; font-weight: 700; color: #9e9e9e; }
.card-title {
  font-size: 15px; font-weight: 600; line-height: 1.35; color: #212121;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.card-menu { min-width: 44px; min-height: 44px; margin: -10px -12px -10px 0; }
.step-row { gap: 10px; }
.card-status { font-size: 11.5px; font-weight: 600; padding: 3px 8px; border-radius: 4px; white-space: nowrap; }
.seg-col { gap: 3px; min-width: 0; }
.seg-bar { gap: 4px; }
.seg { flex: 1 1 0; height: 5px; border-radius: 3px; }
.seg-labels { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px; font-size: 10.5px; line-height: 1.2; }
.card-meta { display: flex; align-items: center; gap: 5px; font-size: 12.5px; color: #757575; white-space: nowrap; overflow: hidden; }
.card-meta .ellipsis { overflow: hidden; text-overflow: ellipsis; }
.dot { color: #bdbdbd; }
.card-link { display: inline-flex; align-items: center; gap: 2px; font-weight: 600; color: #1976d2; text-decoration: none; }
.card-next {
  display: flex; align-items: center; gap: 8px; min-height: 40px; padding: 6px 10px; border-radius: 6px;
  background: #fff8e1; color: #ef6c00; font-size: 12.5px; font-weight: 600;
}
</style>
```

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run components/tasks/TaskCardCompact.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add frontend/components/tasks/TaskCardCompact.vue frontend/components/tasks/TaskCardCompact.spec.ts && git commit -m "feat(front): 모바일 작업 카드 TaskCardCompact — 상태 칩·4세그먼트·메타·다음 할 일"`

### Task 5: `TaskActionSheet.vue` — 카드 액션 시트

**Files:**
- Create: `frontend/components/tasks/TaskActionSheet.vue`
- Test: `frontend/components/tasks/TaskActionSheet.spec.ts`

**Interfaces:**
- Consumes: `moveFor`, `cancelable`, `DEPLOY_ACTIVE_STATUSES`, `CHIP`, `type MoveDef` from taskStages; `CardTask` from `TaskCardCompact.vue`
- Produces: `props: { task: CardTask | null; isAdmin: boolean }`, `defineModel<boolean>()`(열림), emits `detail`, `move(move: MoveDef)`, `cancel`, `remove`. data-test: `sheet-detail`, `sheet-move`, `sheet-cancel`, `sheet-remove`, `sheet-pr`.

- [ ] **Step 1: 실패 테스트** — `frontend/components/tasks/TaskActionSheet.spec.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import TaskActionSheet from './TaskActionSheet.vue'

const pr = {
  id: 18, title: '장비 현황 화면 개발', status: 'PR_CREATED', statusLabel: 'PR생성', repoAlias: 'Netis7.0',
  githubRepo: 'o/r', githubBranch: 'main', createdAt: '2026-08-24T00:00:00Z', updatedAt: '2026-08-24T00:00:00Z',
  implementation: { prUrl: 'https://github.com/x/pull/13', prNumber: 13 }, deployment: null,
}
function open(task: any, isAdmin: boolean) {
  return mount(TaskActionSheet, { props: { task, isAdmin, modelValue: true }, attachTo: document.body })
}
const body = () => document.body

describe('TaskActionSheet (스펙 2026-09-06 §4.1 ⋮ 액션 시트)', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('관리자 + PR생성: 상세·PR 열기·배포(전이)·삭제 항목, 배포 클릭은 move(MoveDef) emit', async () => {
    const w = open(pr, true)
    await flushPromises()
    expect(body().querySelector('[data-test="sheet-detail"]')).not.toBeNull()
    expect(body().querySelector('[data-test="sheet-pr"]')?.getAttribute('href')).toBe('https://github.com/x/pull/13')
    const move = body().querySelector('[data-test="sheet-move"]') as HTMLElement
    expect(move.textContent).toContain('배포')
    move.click()
    await flushPromises()
    expect(w.emitted('move')?.[0]?.[0]).toMatchObject({ to: 'DEPLOY_PENDING', path: 'deploy', confirm: true })
    expect(body().querySelector('[data-test="sheet-cancel"]')).toBeNull() // PR생성은 취소 불가
    w.unmount()
  })

  it('요청자에게는 전이 항목이 없고, 승인대기는 취소 항목이 있다', async () => {
    const w = open({ ...pr, status: 'AWAITING_APPROVAL', statusLabel: '승인대기', implementation: null }, false)
    await flushPromises()
    expect(body().querySelector('[data-test="sheet-move"]')).toBeNull()
    ;(body().querySelector('[data-test="sheet-cancel"]') as HTMLElement).click()
    expect(w.emitted('cancel')).toHaveLength(1)
    w.unmount()
  })

  it('배포 활성 상태의 삭제 항목은 비활성 + 이유 캡션', async () => {
    const w = open({ ...pr, status: 'DEPLOYED', statusLabel: '배포완료' }, true)
    await flushPromises()
    const remove = body().querySelector('[data-test="sheet-remove"]') as HTMLElement
    expect(remove.getAttribute('aria-disabled')).toBe('true')
    expect(remove.textContent).toContain('먼저 중지')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run components/tasks/TaskActionSheet.spec.ts` → FAIL

- [ ] **Step 3: 구현** — `frontend/components/tasks/TaskActionSheet.vue`

```vue
<script setup lang="ts">
// 카드 ⋮ 하단 시트 (스펙 2026-09-06 §4.1, 캔버스 ActionSheet). 데스크톱 드래그 전이(MOVES)를 터치용으로 복원.
// 전이 실행·확인(confirm:true)·취소·삭제 API 호출은 부모(pages/tasks/index.vue)가 한다 — 여기선 emit만.
import { CHIP, moveFor, cancelable, DEPLOY_ACTIVE_STATUSES, type MoveDef } from '~/composables/taskStages'
import type { CardTask } from '~/components/tasks/TaskCardCompact.vue'

const props = defineProps<{ task: CardTask | null; isAdmin: boolean }>()
const show = defineModel<boolean>({ default: false })
const emit = defineEmits<{
  (e: 'detail'): void
  (e: 'move', move: MoveDef): void
  (e: 'cancel'): void
  (e: 'remove'): void
}>()

const chip = computed(() => (props.task ? CHIP[props.task.status] ?? ['#f5f5f5', '#616161'] : ['#f5f5f5', '#616161']))
const move = computed(() => (props.task && props.isAdmin ? moveFor(props.task) : null))
const deployActive = computed(() => !!props.task && DEPLOY_ACTIVE_STATUSES.includes(props.task.status))
const moveIcon = computed(() =>
  move.value?.path.startsWith('deploy') || move.value?.path === 'redeploy' ? 'rocket_launch'
    : move.value?.path === 'undeploy' ? 'stop' : 'check_circle',
)
</script>

<template>
  <q-dialog v-model="show" position="bottom">
    <q-card v-if="task" class="sheet" data-test="task-action-sheet">
      <div class="handle" />
      <div class="sheet-head">
        <div class="row items-baseline no-wrap" style="gap: 6px">
          <span class="text-caption text-weight-bold text-grey-6">#{{ task.id }}</span>
          <span class="text-subtitle1 text-weight-medium ellipsis">{{ task.title }}</span>
        </div>
        <div class="row items-center" style="gap: 8px">
          <span class="chip" :style="{ background: chip[0], color: chip[1] }">{{ task.statusLabel }}</span>
          <span class="text-caption text-grey-7">{{ task.repoAlias ?? task.githubRepo }} · {{ task.githubBranch }}</span>
        </div>
      </div>
      <q-list>
        <q-item v-close-popup clickable class="sheet-item" data-test="sheet-detail" @click="emit('detail')">
          <q-item-section avatar><q-icon name="info" color="grey-8" /></q-item-section>
          <q-item-section>상세 보기</q-item-section>
          <q-item-section side><q-icon name="chevron_right" color="grey-5" /></q-item-section>
        </q-item>
        <q-item
          v-if="task.implementation?.prUrl"
          v-close-popup
          clickable
          tag="a"
          :href="task.implementation.prUrl"
          target="_blank"
          rel="noopener"
          class="sheet-item"
          data-test="sheet-pr"
        >
          <q-item-section avatar><q-icon name="open_in_new" color="grey-8" /></q-item-section>
          <q-item-section>PR #{{ task.implementation.prNumber }} 열기</q-item-section>
        </q-item>
        <q-item v-if="move" v-close-popup clickable class="sheet-item text-primary" data-test="sheet-move" @click="emit('move', move)">
          <q-item-section avatar><q-icon :name="moveIcon" color="primary" /></q-item-section>
          <q-item-section>
            <q-item-label class="text-weight-medium">{{ move.label }}</q-item-label>
            <q-item-label v-if="move.confirm" caption>확인 후 실행됩니다 (관리자)</q-item-label>
          </q-item-section>
        </q-item>
        <q-separator v-if="cancelable(task) || true" spaced />
        <q-item v-if="cancelable(task)" v-close-popup clickable class="sheet-item text-warning" data-test="sheet-cancel" @click="emit('cancel')">
          <q-item-section avatar><q-icon name="block" color="warning" /></q-item-section>
          <q-item-section>취소</q-item-section>
        </q-item>
        <q-item
          v-close-popup
          :clickable="!deployActive"
          :disable="deployActive"
          :aria-disabled="deployActive ? 'true' : 'false'"
          class="sheet-item text-negative"
          data-test="sheet-remove"
          @click="!deployActive && emit('remove')"
        >
          <q-item-section avatar><q-icon name="delete" :color="deployActive ? 'grey-4' : 'negative'" /></q-item-section>
          <q-item-section>
            <q-item-label>삭제</q-item-label>
            <q-item-label v-if="deployActive" caption>배포 이력이 활성인 작업은 먼저 중지 후 삭제</q-item-label>
          </q-item-section>
        </q-item>
        <q-item v-close-popup clickable class="sheet-item sheet-close">
          <q-item-section class="text-center text-grey-8 text-weight-medium">닫기</q-item-section>
        </q-item>
      </q-list>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.sheet { width: 100%; border-radius: 16px 16px 0 0; padding: 8px 0 12px; }
.handle { width: 36px; height: 4px; border-radius: 2px; background: #e0e0e0; margin: 0 auto 10px; }
.sheet-head { display: flex; flex-direction: column; gap: 4px; padding: 4px 20px 12px; border-bottom: 1px solid rgba(0, 0, 0, 0.12); }
.chip { font-size: 11px; font-weight: 600; padding: 2px 7px; border-radius: 4px; }
.sheet-item { min-height: 48px; font-size: 15px; }
.sheet-close { margin: 4px 8px 0; border-radius: 6px; background: #f5f5f5; }
</style>
```
(`<q-separator v-if="cancelable(task) || true" spaced />`는 항상 표시 — 단순 `<q-separator spaced />`로 적는다.)

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run components/tasks/TaskActionSheet.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add frontend/components/tasks/TaskActionSheet.vue frontend/components/tasks/TaskActionSheet.spec.ts && git commit -m "feat(front): 작업 카드 액션 시트 TaskActionSheet — 상세·PR·관리자 전이·취소·삭제"`

### Task 6: `TaskListMobile.vue` + `pages/tasks/index.vue` 분기

**Files:**
- Create: `frontend/components/tasks/TaskListMobile.vue`
- Modify: `frontend/pages/tasks/index.vue` (템플릿 상단 분기, `QueueStatsBar`·헤더·보드·푸터를 데스크톱 전용으로)
- Test: `frontend/test/tasks-index-mobile.spec.ts`

**Interfaces:**
- Consumes: `TaskCardCompact`(+`CardTask`), `TaskActionSheet`, `QueueStatsBar`, `sortForMobile`, `ATTENTION_GROUPS`, `type AttentionGroup`, `type MoveDef`
- Produces: `TaskListMobile` props `{ tasks: CardTask[]; isAdmin: boolean }`, `defineModel<boolean>('mine')`, emits `create`, `open(task)`, `move(task, move)`, `cancel(task)`, `remove(task)`. data-test: `summary-strip`, `filter-<group|all>`, `mine-chip`, `group-<key>`, `fab-create`, `closed-toggle`, `stats-toggle`.
- index.vue: `navigateTo` 사용(테스트에서는 `globalThis.navigateTo` 스텁).

- [ ] **Step 1: 실패 테스트** — `frontend/test/tasks-index-mobile.spec.ts`

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }) }),
})

const t = (id: number, status: string, statusLabel: string, updatedAt: string, extra: Record<string, unknown> = {}) => ({
  id, githubRepo: 'micthebick84/netis7.0', repoAlias: 'Netis7.0', githubBranch: 'main', title: `작업 ${id}`, description: '',
  status, statusLabel, requesterId: 'admin', retryCount: 0, maxRetry: 3, failureReason: null, designRequested: false,
  createdAt: '2026-08-01T00:00:00Z', updatedAt, implementation: null, totalCostUsd: null, ...extra,
})
const tasks = [
  t(21, 'AWAITING_APPROVAL', '승인대기', '2026-08-24T01:00:00Z'),
  t(18, 'PR_CREATED', 'PR생성', '2026-08-24T02:00:00Z', { implementation: { prUrl: 'https://x/pull/13', prNumber: 13, headBranch: 'b', headSha: 's' } }),
  t(13, 'DEPLOYED', '배포완료', '2026-07-27T00:00:00Z'),
  t(9, 'CANCELLED', '취소됨', '2026-06-01T00:00:00Z'),
]

function stubApi() {
  useApiMock.mockImplementation((url: string) =>
    url.startsWith('/api/tasks') ? Promise.resolve({ content: tasks, totalElements: 4, totalPages: 1 }) : Promise.resolve(null),
  )
}

describe('pages/tasks — 모바일 리스트 (스펙 2026-09-06 §4.1)', () => {
  beforeEach(() => { authStub.isAdmin = true; navigateToMock.mockReset(); stubApi() })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('lt.md에서는 보드 대신 카드 리스트: 그룹 순서 확인 필요→완료→취소됨(접힘), 요약 스트립, FAB', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('.stage-row').exists()).toBe(false)
    const groups = w.findAll('[data-test^="group-"]').map((g) => g.attributes('data-test'))
    expect(groups).toEqual(['group-attention', 'group-done', 'group-closed'])
    expect(w.find('[data-test="summary-strip"]').text()).toContain('확인 필요 1')
    expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(3) // 취소됨은 접힘
    await w.find('[data-test="closed-toggle"]').trigger('click')
    expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(4)
    expect(w.find('[data-test="fab-create"]').exists()).toBe(true)
    w.unmount()
  })

  it('필터 칩 "완료"는 완료 그룹만 남기고, 카드 탭은 상세로 이동한다', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    await w.find('[data-test="filter-done"]').trigger('click')
    expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(2)
    await w.find('[data-test="task-card-compact"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/tasks/18')
    w.unmount()
  })

  it('⋮ → 시트의 배포 전이는 확인 후 POST /api/tasks/{id}/deploy', async () => {
    await setViewportWidth(390)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    const menus = w.findAll('[data-test="card-menu"]')
    await menus[1]!.trigger('click') // #18 PR생성
    await flushPromises()
    ;(document.body.querySelector('[data-test="sheet-move"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/18/deploy', { method: 'POST' })
    w.unmount()
  })

  it('데스크톱(1024)에서는 기존 보드가 그대로다', async () => {
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('.stage-row').exists()).toBe(true)
    expect(w.find('[data-test="task-card-compact"]').exists()).toBe(false)
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/tasks-index-mobile.spec.ts` → FAIL

- [ ] **Step 3: 구현** — `frontend/components/tasks/TaskListMobile.vue`

```vue
<script setup lang="ts">
// 모바일 작업 리스트 (스펙 2026-09-06 §4.1, 캔버스 Main): 요약 스트립 + 필터 칩 + 그룹(확인 필요→진행 중→완료→취소됨) + FAB.
// 데이터·API 호출은 부모(pages/tasks/index.vue) 소유 — 여기선 정렬/필터/시트 상태만 가진다.
import TaskCardCompact, { type CardTask } from '~/components/tasks/TaskCardCompact.vue'
import TaskActionSheet from '~/components/tasks/TaskActionSheet.vue'
import QueueStatsBar from '~/components/QueueStatsBar.vue'
import { sortForMobile, ATTENTION_GROUPS, type AttentionGroup, type MoveDef } from '~/composables/taskStages'

const props = defineProps<{ tasks: CardTask[]; isAdmin: boolean }>()
const mine = defineModel<boolean>('mine', { default: true })
const emit = defineEmits<{
  (e: 'create'): void
  (e: 'open', task: CardTask): void
  (e: 'move', task: CardTask, move: MoveDef): void
  (e: 'cancel', task: CardTask): void
  (e: 'remove', task: CardTask): void
}>()

const filter = ref<AttentionGroup | 'all'>('all')
const closedOpen = ref(false)
const statsOpen = ref(false)

const groups = computed(() => sortForMobile(props.tasks, props.isAdmin))
const counts = computed(() => Object.fromEntries(groups.value.map((g) => [g.key, g.items.length])) as Record<string, number>)
const visibleGroups = computed(() =>
  groups.value.filter((g) => filter.value === 'all' || g.key === filter.value),
)

const sheetTask = ref<CardTask | null>(null)
const sheetOpen = ref(false)
function openSheet(task: CardTask) {
  sheetTask.value = task
  sheetOpen.value = true
}
</script>

<template>
  <div class="mobile-list">
    <div class="summary-strip" data-test="summary-strip">
      <span>확인 필요 <b class="c-attention">{{ counts.attention ?? 0 }}</b></span>
      <span class="dot">·</span>
      <span>진행 중 <b class="c-active">{{ counts.active ?? 0 }}</b></span>
      <span class="dot">·</span>
      <span>완료 <b class="c-done">{{ counts.done ?? 0 }}</b></span>
      <q-space />
      <q-btn
        v-if="isAdmin"
        flat
        dense
        no-caps
        size="sm"
        color="grey-7"
        :icon-right="statsOpen ? 'expand_less' : 'expand_more'"
        label="전체 통계"
        data-test="stats-toggle"
        @click="statsOpen = !statsOpen"
      />
    </div>
    <QueueStatsBar v-if="isAdmin && statsOpen" />

    <div class="chip-row">
      <q-chip
        clickable
        :color="filter === 'all' ? 'primary' : undefined"
        :text-color="filter === 'all' ? 'white' : undefined"
        :outline="filter !== 'all'"
        data-test="filter-all"
        @click="filter = 'all'"
      >전체</q-chip>
      <q-chip
        v-for="g in ATTENTION_GROUPS"
        :key="g.key"
        clickable
        :color="filter === g.key ? 'primary' : undefined"
        :text-color="filter === g.key ? 'white' : undefined"
        :outline="filter !== g.key"
        :data-test="`filter-${g.key}`"
        @click="filter = g.key"
      >
        {{ g.label }}<q-badge v-if="counts[g.key]" rounded :color="filter === g.key ? 'white' : 'grey-3'" :text-color="filter === g.key ? 'primary' : 'grey-8'" class="q-ml-xs">{{ counts[g.key] }}</q-badge>
      </q-chip>
      <q-chip
        clickable
        :icon="mine ? 'check' : undefined"
        :color="mine ? 'blue-1' : undefined"
        :text-color="mine ? 'primary' : undefined"
        :outline="!mine"
        data-test="mine-chip"
        @click="mine = !mine"
      >내 작업만</q-chip>
    </div>

    <div v-if="visibleGroups.length === 0" class="empty">
      <q-icon name="inbox" size="28px" color="grey-5" />
      <div>등록된 작업이 없습니다 — 우하단 + 로 첫 작업을 등록하세요</div>
    </div>

    <section v-for="g in visibleGroups" :key="g.key" class="group" :data-test="`group-${g.key}`">
      <div v-if="g.key !== 'closed'" class="group-head" :style="{ color: g.color }">{{ g.label }}<span class="rule" /></div>
      <div v-else class="closed-head" data-test="closed-toggle" @click="closedOpen = !closedOpen">
        <span>취소됨 {{ g.items.length }}건</span>
        <q-space />
        <span>{{ closedOpen ? '접기' : '펼치기' }}</span>
        <q-icon :name="closedOpen ? 'expand_less' : 'expand_more'" size="20px" />
      </div>
      <div v-if="g.key !== 'closed' || closedOpen" class="cards">
        <TaskCardCompact
          v-for="task in g.items"
          :key="task.id"
          :task="task"
          :is-admin="isAdmin"
          @open="emit('open', task)"
          @menu="openSheet(task)"
        />
      </div>
    </section>

    <q-btn
      fab
      color="primary"
      icon="add"
      aria-label="작업 등록"
      class="fab"
      data-test="fab-create"
      @click="emit('create')"
    />

    <TaskActionSheet
      v-model="sheetOpen"
      :task="sheetTask"
      :is-admin="isAdmin"
      @detail="sheetTask && emit('open', sheetTask)"
      @move="(m) => sheetTask && emit('move', sheetTask, m)"
      @cancel="sheetTask && emit('cancel', sheetTask)"
      @remove="sheetTask && emit('remove', sheetTask)"
    />
  </div>
</template>

<style scoped>
.mobile-list { display: flex; flex-direction: column; gap: 8px; padding-bottom: 88px; }
.summary-strip { display: flex; align-items: center; gap: 4px; min-height: 40px; font-size: 13px; color: #616161; }
.summary-strip b { font-weight: 700; }
.c-attention { color: #ef6c00; } .c-active { color: #1565c0; } .c-done { color: #00695c; }
.dot { color: #bdbdbd; margin: 0 4px; }
.chip-row { display: flex; gap: 4px; overflow-x: auto; white-space: nowrap; margin: 0 -8px; padding: 0 8px 4px; }
.chip-row::-webkit-scrollbar { display: none; }
.group { display: flex; flex-direction: column; gap: 8px; }
.group-head { display: flex; align-items: center; gap: 8px; padding: 10px 4px 0; font-size: 12px; font-weight: 700; letter-spacing: 0.02em; }
.rule { flex: 1; height: 1px; background: rgba(0, 0, 0, 0.12); }
.closed-head { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 8px 4px; font-size: 13px; color: #757575; cursor: pointer; }
.cards { display: flex; flex-direction: column; gap: 8px; }
.empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 48px 16px; color: #757575; font-size: 13px; text-align: center; }
.fab { position: fixed; right: 16px; bottom: calc(var(--bottom-nav-height, 0px) + 16px); z-index: 5; }
</style>
```

`frontend/pages/tasks/index.vue` 템플릿 — `<q-page padding>` 바로 아래를 다음 구조로 바꾼다(기존 내용은 `v-else` 블록으로 감싼다):

```vue
<template>
  <q-page padding>
    <TaskListMobile
      v-if="$q.screen.lt.md"
      v-model:mine="mine"
      :tasks="tasks"
      :is-admin="auth.isAdmin"
      @update:mine="refresh"
      @create="openCreate"
      @open="(t) => navigateTo(`/tasks/${t.id}`)"
      @move="(t, m) => applyMove(t.id, m)"
      @cancel="cancel"
      @remove="remove"
    />
    <template v-else>
      <QueueStatsBar />
      <!-- (기존 헤더 row … stage-rows … board-foot 그대로) -->
    </template>

    <q-inner-loading :showing="moving" />
    <!-- 등록 다이얼로그(Task 1 반영본) 그대로 — 양쪽 공용 -->
  </q-page>
</template>
```
스크립트에 `import TaskListMobile from '~/components/tasks/TaskListMobile.vue'` 추가. `useQuasar`는 이미 `$q`로 있다. `main.css`에 `:root { --bottom-nav-height: 0px; }`를 추가하고, Task 12에서 lt.md일 때 `56px`로 바꾼다.

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run test/tasks-index-mobile.spec.ts test/tasks-index-usage.spec.ts test/tasks-form-attachments.spec.ts test/tasks-form-repo-select.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add frontend/components/tasks/TaskListMobile.vue frontend/pages/tasks/index.vue frontend/assets/css/main.css frontend/test/tasks-index-mobile.spec.ts && git commit -m "feat(front): 작업 목록 모바일 리스트(B안) — 확인 필요 우선 그룹·필터 칩·액션 시트·FAB, 데스크톱 보드 무변경"`

---

## S3. 작업 상세

### Task 7: `TaskProgressStepper.vue`

**Files:**
- Create: `frontend/components/tasks/TaskProgressStepper.vue`
- Test: `frontend/components/tasks/TaskProgressStepper.spec.ts`

**Interfaces:**
- Consumes: `type StageStep` from taskStages
- Produces: props `{ steps: StageStep[]; caption?: string; compact?: boolean }`. data-test: `stepper`, `step-<key>`(data-state), `stepper-caption`.

- [ ] **Step 1: 실패 테스트**

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskProgressStepper from './TaskProgressStepper.vue'
import { stageSteps } from '~/composables/taskStages'

describe('TaskProgressStepper (스펙 2026-09-06 §4.2)', () => {
  it('4단계를 상태별로 그리고 현재 단계를 강조한다', () => {
    const w = mount(TaskProgressStepper, {
      props: { steps: stageSteps({ status: 'PR_CREATED', designRequested: true }), caption: 'PR생성 · PR #13' },
    })
    const states = ['analysis', 'design', 'impl', 'deploy'].map((k) => w.find(`[data-test="step-${k}"]`).attributes('data-state'))
    expect(states).toEqual(['done', 'done', 'current', 'future'])
    expect(w.find('[data-test="step-impl"]').classes()).toContain('step--current')
    expect(w.find('[data-test="stepper-caption"]').text()).toBe('PR생성 · PR #13')
    expect(w.text()).toContain('분석')
    w.unmount()
  })
  it('compact는 세그먼트 4개 + 현재 단계명만 그린다', () => {
    const w = mount(TaskProgressStepper, { props: { steps: stageSteps({ status: 'DEPLOYED', designRequested: false }), compact: true } })
    expect(w.findAll('.seg')).toHaveLength(4)
    expect(w.find('[data-test="step-design"]').attributes('data-state')).toBe('skipped')
    expect(w.text()).toContain('배포')
    w.unmount()
  })
  it('failed 단계는 step--failed', () => {
    const w = mount(TaskProgressStepper, { props: { steps: stageSteps({ status: 'DEPLOY_FAILED', designRequested: true }) } })
    expect(w.find('[data-test="step-deploy"]').classes()).toContain('step--failed')
    w.unmount()
  })
})
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run components/tasks/TaskProgressStepper.spec.ts` → FAIL
- [ ] **Step 3: 구현**

```vue
<script setup lang="ts">
// 4단계 진행 스테퍼 (스펙 2026-09-06 §4.2, 캔버스 Detail/DesktopDetail). 상태 계산은 taskStages.stageSteps — 여기선 표시만.
import type { StageStep } from '~/composables/taskStages'

const props = withDefaults(defineProps<{ steps: StageStep[]; caption?: string; compact?: boolean }>(), { caption: '', compact: false })
const current = computed(() => props.steps.find((s) => s.state === 'current' || s.state === 'failed'))

function circleStyle(s: StageStep) {
  if (s.state === 'done') return { background: '#2e7d32', borderColor: '#2e7d32' }
  if (s.state === 'current') return { background: s.color, borderColor: s.color, boxShadow: `0 0 0 4px ${s.color}2e` }
  if (s.state === 'failed') return { background: '#c62828', borderColor: '#c62828' }
  if (s.state === 'skipped') return { background: '#fff', borderColor: '#e0e0e0', borderStyle: 'dashed' }
  return { background: '#fff', borderColor: '#bdbdbd' }
}
function segStyle(s: StageStep) {
  if (s.state === 'done') return { background: '#a5d6a7' }
  if (s.state === 'current') return { background: s.color }
  if (s.state === 'failed') return { background: '#c62828' }
  if (s.state === 'skipped') return { background: 'repeating-linear-gradient(90deg, #e0e0e0 0 4px, transparent 4px 8px)' }
  return { background: '#eeeeee' }
}
function labelColor(s: StageStep) {
  if (s.state === 'current') return s.color
  if (s.state === 'failed') return '#c62828'
  if (s.state === 'done') return '#2e7d32'
  return '#9e9e9e'
}
</script>

<template>
  <div class="stepper" :class="{ 'stepper--compact': compact }" data-test="stepper">
    <template v-if="compact">
      <div class="row items-center no-wrap" style="gap: 10px">
        <div class="row no-wrap col" style="gap: 4px">
          <span v-for="s in steps" :key="s.key" class="seg" :data-test="`step-${s.key}`" :data-state="s.state" :style="segStyle(s)" />
        </div>
        <span v-if="current" class="text-caption text-weight-bold" :style="{ color: labelColor(current) }">{{ current.label }}</span>
      </div>
    </template>
    <template v-else>
      <div class="row items-center no-wrap track">
        <template v-for="(s, i) in steps" :key="s.key">
          <div class="step" :class="`step--${s.state}`" :data-test="`step-${s.key}`" :data-state="s.state">
            <div class="circle" :style="circleStyle(s)">
              <q-icon v-if="s.state === 'done'" name="check" color="white" size="16px" />
              <q-icon v-else-if="s.state === 'failed'" name="priority_high" color="white" size="16px" />
              <span v-else-if="s.state === 'current'" class="core" />
            </div>
          </div>
          <div v-if="i < steps.length - 1" class="line" :style="{ background: s.state === 'done' ? '#a5d6a7' : '#e0e0e0' }" />
        </template>
      </div>
      <div class="labels">
        <span v-for="s in steps" :key="s.key" :style="{ color: labelColor(s), fontWeight: s === current ? 700 : 400 }">{{ s.label }}</span>
      </div>
      <div v-if="caption" class="caption" data-test="stepper-caption">{{ caption }}</div>
    </template>
  </div>
</template>

<style scoped>
.stepper { display: flex; flex-direction: column; gap: 8px; }
.track { padding: 0 6px; }
.step { display: flex; align-items: center; justify-content: center; }
.circle { width: 26px; height: 26px; border-radius: 50%; border: 2px solid; display: flex; align-items: center; justify-content: center; }
.core { width: 10px; height: 10px; border-radius: 50%; background: #fff; }
.line { flex: 1; height: 3px; }
.labels { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px; text-align: center; font-size: 11.5px; line-height: 1.2; }
.caption { font-size: 12.5px; color: #616161; text-align: center; }
.seg { flex: 1 1 0; height: 5px; border-radius: 3px; }
</style>
```
- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run components/tasks/TaskProgressStepper.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add frontend/components/tasks/TaskProgressStepper.vue frontend/components/tasks/TaskProgressStepper.spec.ts && git commit -m "feat(front): 4단계 진행 스테퍼 TaskProgressStepper (풀/compact)"`

### Task 8: `TaskNextAction.vue`

**Files:**
- Create: `frontend/components/tasks/TaskNextAction.vue`
- Test: `frontend/components/tasks/TaskNextAction.spec.ts`

**Interfaces:**
- Consumes: `type NextAction`, `type NextActionKind` from taskStages
- Produces: props `{ action: NextAction | null; variant: 'banner' | 'bar' }`, slot `extra`(bar의 ⋮ 자리), emits `act(kind: NextActionKind)`. data-test: `next-action`, `next-action-text`, `next-action-primary`.

- [ ] **Step 1: 실패 테스트**

```ts
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskNextAction from './TaskNextAction.vue'

describe('TaskNextAction (스펙 2026-09-06 §4.2)', () => {
  const action = { text: 'PR 생성됨 — 배포할 수 있습니다', primary: { label: '배포', icon: 'rocket_launch', kind: 'deploy' as const } }
  it('banner: 문구 + 주 행동 버튼, 클릭 시 act(kind)', async () => {
    const w = mount(TaskNextAction, { props: { action, variant: 'banner' } })
    expect(w.find('[data-test="next-action-text"]').text()).toContain('배포할 수 있습니다')
    await w.find('[data-test="next-action-primary"]').trigger('click')
    expect(w.emitted('act')?.[0]).toEqual(['deploy'])
    expect(w.classes()).toContain('next-action--banner')
    w.unmount()
  })
  it('bar: 주 행동 버튼은 44px 이상, primary 없으면 문구만', () => {
    const w = mount(TaskNextAction, { props: { action: { text: '진행 중' }, variant: 'bar' } })
    expect(w.classes()).toContain('next-action--bar')
    expect(w.find('[data-test="next-action-primary"]').exists()).toBe(false)
    expect(w.text()).toContain('진행 중')
    w.unmount()
  })
  it('action null이면 아무것도 그리지 않는다', () => {
    const w = mount(TaskNextAction, { props: { action: null, variant: 'banner' } })
    expect(w.find('[data-test="next-action"]').exists()).toBe(false)
    w.unmount()
  })
})
```
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run components/tasks/TaskNextAction.spec.ts` → FAIL
- [ ] **Step 3: 구현**

```vue
<script setup lang="ts">
// "다음 할 일" (스펙 2026-09-06 §4.2). banner = 데스크톱 상세 상단 우측 패널, bar = 모바일 하단 고정 액션 바.
// 실제 동작(승인/배포/재시도…)은 부모가 act(kind)로 받아 기존 핸들러에 연결한다.
import type { NextAction, NextActionKind } from '~/composables/taskStages'

defineProps<{ action: NextAction | null; variant: 'banner' | 'bar' }>()
const emit = defineEmits<{ (e: 'act', kind: NextActionKind): void }>()
</script>

<template>
  <div v-if="action" class="next-action" :class="`next-action--${variant}`" data-test="next-action">
    <template v-if="variant === 'banner'">
      <div class="row items-center text-primary text-caption text-weight-bold" style="gap: 6px; letter-spacing: 0.04em">
        <q-icon name="play_arrow" size="16px" />다음 할 일
      </div>
      <div class="text-body1" data-test="next-action-text">{{ action.text }}</div>
      <div v-if="action.primary" class="row" style="gap: 8px">
        <q-btn unelevated color="primary" :icon="action.primary.icon" :label="action.primary.label" data-test="next-action-primary" @click="emit('act', action.primary.kind)" />
        <slot name="extra" />
      </div>
    </template>
    <template v-else>
      <q-btn
        v-if="action.primary"
        unelevated
        color="primary"
        :icon="action.primary.icon"
        :label="action.primary.label"
        class="col bar-primary"
        data-test="next-action-primary"
        @click="emit('act', action.primary.kind)"
      />
      <div v-else class="col text-body2 text-grey-8 bar-text" data-test="next-action-text">{{ action.text }}</div>
      <slot name="extra" />
    </template>
  </div>
</template>

<style scoped>
.next-action--banner { display: flex; flex-direction: column; gap: 10px; padding: 16px 20px; background: #f5f9fd; height: 100%; }
.next-action--bar {
  position: fixed; left: 0; right: 0; bottom: var(--bottom-nav-height, 0px); z-index: 6;
  display: flex; align-items: center; gap: 8px; padding: 10px 12px; background: #fff; border-top: 1px solid rgba(0, 0, 0, 0.12);
}
.bar-primary { min-height: 44px; }
.bar-text { min-height: 44px; display: flex; align-items: center; padding: 0 4px; }
</style>
```
- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run components/tasks/TaskNextAction.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add frontend/components/tasks/TaskNextAction.vue frontend/components/tasks/TaskNextAction.spec.ts && git commit -m "feat(front): 다음 할 일 TaskNextAction (banner/bar)"`

### Task 9a: 상세 — 스테퍼·다음 할 일(데스크톱+모바일 공용) + 분석 결과 마크다운 렌더

**Files:**
- Modify: `frontend/pages/tasks/[id].vue` (헤더 아래 progress-card 추가, 액션 라우팅 `onAction`, 분석 결과 `pre` → 마크다운, 인터뷰/디자인 카드에 id 부여)
- Test: `frontend/test/task-detail-progress.spec.ts` (신규)

**Interfaces:**
- Consumes: `TaskProgressStepper`, `TaskNextAction`, `stageSteps`, `nextAction`, `renderMarkdown`
- Produces: `[id].vue` 내부 `onAction(kind: NextActionKind)`: `approve-interview`→`openApprove()`, `approve-impl`→`approve()`, `deploy`→`openDeployDialog('deploy')`, `redeploy`→`openDeployDialog('redeploy')`, `retry`→`retry()`, `open-pr`→`window.open(task.implementation.prUrl, '_blank', 'noopener')`, `open-url`→`window.open(task.deployment.deployUrl, '_blank', 'noopener')`, `open-interview`→`scrollTo('interview-card')`, `review-design`→`scrollTo('design-card')`. data-test: `progress-card`.

- [ ] **Step 1: 실패 테스트** — `frontend/test/task-detail-progress.spec.ts` (fixture는 `task-detail-mobile.spec.ts`의 `baseTask`를 import)

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './task-detail-mobile.spec'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

describe('pages/tasks/[id] — 진행 스테퍼 + 다음 할 일 (스펙 2026-09-06 §4.2, 결정 6: 데스크톱도 노출)', () => {
  beforeEach(() => { authStub.isAdmin = true })
  afterEach(() => { document.body.innerHTML = '' })

  it('데스크톱: 제목 아래 progress-card에 스테퍼(구현 current)와 배포 주 행동이 있고, 배포 클릭은 환경변수 다이얼로그를 연다', async () => {
    useApiMock.mockResolvedValue(baseTask)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const card = w.find('[data-test="progress-card"]')
    expect(card.exists()).toBe(true)
    expect(card.find('[data-test="step-impl"]').attributes('data-state')).toBe('current')
    expect(card.find('[data-test="next-action-text"]').text()).toContain('PR 생성됨')
    await card.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('배포 — 환경변수')
    w.unmount()
  })

  it('요청자: 승인대기 작업은 주 행동 없이 문구만', async () => {
    authStub.isAdmin = false
    useApiMock.mockResolvedValue({ ...baseTask, status: 'AWAITING_APPROVAL', statusLabel: '승인대기', implementation: null, analysis: null })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="next-action-text"]').text()).toContain('관리자 승인')
    expect(w.find('[data-test="next-action-primary"]').exists()).toBe(false)
    w.unmount()
  })

  it('분석 결과는 마크다운으로 렌더된다(h1, table)', async () => {
    useApiMock.mockResolvedValue(baseTask)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const md = w.find('[data-test="analysis-markdown"]')
    expect(md.find('h1').text()).toBe('요약')
    expect(md.find('table').exists()).toBe(true)
    expect(md.classes()).toContain('md-scroll')
    w.unmount()
  })
})
```
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/task-detail-progress.spec.ts` → FAIL
- [ ] **Step 3: 구현** — `[id].vue` 스크립트에 추가:

```ts
import TaskProgressStepper from '~/components/tasks/TaskProgressStepper.vue'
import TaskNextAction from '~/components/tasks/TaskNextAction.vue'
import { stageSteps, nextAction, type NextActionKind } from '~/composables/taskStages'
import { renderMarkdown } from '~/composables/useMarkdown'

const steps = computed(() => (task.value ? stageSteps(task.value) : []))
const action = computed(() => (task.value ? nextAction(task.value, auth.isAdmin) : null))
const stepCaption = computed(() => {
  const t = task.value
  if (!t) return ''
  const parts = [t.statusLabel]
  if (t.implementation?.prNumber) parts.push(`PR #${t.implementation.prNumber}`)
  parts.push(`${ageOf(t.updatedAt)} 전 갱신`)
  return parts.join(' · ')
})
const analysisHtml = computed(() => renderMarkdown(task.value?.analysis?.markdownResult))

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function onAction(kind: NextActionKind) {
  const t = task.value
  if (!t) return
  switch (kind) {
    case 'approve-interview': return openApprove()
    case 'approve-impl': return approve()
    case 'deploy': return openDeployDialog('deploy')
    case 'redeploy': return openDeployDialog('redeploy')
    case 'retry': return retry()
    case 'open-pr': return void window.open(t.implementation?.prUrl ?? '', '_blank', 'noopener')
    case 'open-url': return void window.open(t.deployment?.deployUrl ?? '', '_blank', 'noopener')
    case 'open-interview': return scrollTo('interview-card')
    case 'review-design': return scrollTo('design-card')
  }
}
```
(`ageOf`는 `~/composables/taskStages`에서 import.) 템플릿: 헤더 row 바로 아래에

```vue
      <q-card flat bordered class="q-mb-md" data-test="progress-card">
        <div :class="$q.screen.lt.md ? 'column' : 'row items-stretch no-wrap'">
          <div class="col q-pa-md"><TaskProgressStepper :steps="steps" :caption="stepCaption" /></div>
          <q-separator :vertical="!$q.screen.lt.md" />
          <div :class="$q.screen.lt.md ? '' : 'col-4'">
            <TaskNextAction :action="action" variant="banner" @act="onAction">
              <template #extra>
                <q-btn v-if="task.implementation?.prUrl" outline color="primary" icon="open_in_new" :label="`PR #${task.implementation.prNumber} 열기`" :href="task.implementation.prUrl" target="_blank" />
              </template>
            </TaskNextAction>
          </div>
        </div>
      </q-card>
```
인터뷰 카드 `<q-card v-if="isInterviewPhase && task.interviewSessionId" ...>`에 `id="interview-card"`, `<DesignReviewCard .../>`를 `<div id="design-card"><DesignReviewCard .../></div>`로 감싼다. 분석 결과의 `<pre style="… Pretendard …">{{ task.analysis.markdownResult }}</pre>`를 `<div class="md-scroll markdown" data-test="analysis-markdown" v-html="analysisHtml" />`로 교체(Task 2에서 감싼 `.md-scroll` div는 이것으로 대체). `main.css`에 `.markdown table { border-collapse: collapse; } .markdown th, .markdown td { border: 1px solid rgba(0,0,0,0.12); padding: 4px 8px; } .markdown pre { overflow-x: auto; }` 추가.

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run test/task-detail-progress.spec.ts test/task-detail-mobile.spec.ts test/task-detail-usage.spec.ts test/task-detail-attachments.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add "frontend/pages/tasks/[id].vue" frontend/assets/css/main.css frontend/test/task-detail-progress.spec.ts && git commit -m "feat(front): 작업 상세 진행 스테퍼 + 다음 할 일 배너(데스크톱 포함) + 분석 결과 마크다운 렌더"`

### Task 9b: 상세 — 모바일 아코디언·하단 액션 바·전체화면 마크다운 뷰어

**Files:**
- Create: `frontend/components/MarkdownViewerDialog.vue`
- Create: `frontend/components/tasks/TaskDetailMobile.vue`
- Modify: `frontend/pages/tasks/[id].vue` (lt.md 분기: 헤더 축약 + `TaskDetailMobile` + 하단 바)
- Test: `frontend/test/task-detail-mobile.spec.ts` (확장)

**Interfaces:**
- `MarkdownViewerDialog`: `defineModel<boolean>()`, props `{ title: string; markdown: string | null }` → `q-dialog maximized` + 헤더(제목·닫기) + `.md-scroll.markdown` v-html.
- `TaskDetailMobile`: props `{ task: TaskResponse; isAdmin: boolean; steps: StageStep[]; caption: string }`, emits `act(kind)`, `retry`. 슬롯 `interview`(InterviewPanel 카드), `design`(DesignReviewCard), `history`(InterviewHistoryCard·Task 11의 타임라인). 섹션 data-test: `section-history`, `section-request`, `section-analysis`, `section-design`, `section-impl`, `section-deploy`, `section-interviews`, `section-info`. 현재 단계 섹션은 기본 펼침(`stageSteps` current key: analysis→`section-analysis`, design→`section-design`, impl→`section-impl`, deploy→`section-deploy`).
- `[id].vue` lt.md: 헤더는 제목 + 상태 칩만(모델/effort/비용 칩은 `section-info`로), 본문은 `<TaskProgressStepper compact>` 없이 progress-card(Task 9a, column 배치) 유지 + `<TaskDetailMobile>` + `<TaskNextAction variant="bar">`(페이지 `padding-bottom: 80px`).

- [ ] **Step 1: 실패 테스트** — `task-detail-mobile.spec.ts`에 describe 추가

```ts
import { authStub } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

describe('pages/tasks/[id] — 모바일 (스펙 2026-09-06 §4.2)', () => {
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('lt.md: 아코디언 섹션 + 현재 단계(구현)만 펼침 + 하단 액션 바(관리자 배포) + 헤더에 모델 칩 없음', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(baseTask)
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    for (const k of ['request', 'analysis', 'impl', 'deploy', 'interviews', 'info']) {
      expect(w.find(`[data-test="section-${k}"]`).exists()).toBe(true)
    }
    expect(w.find('[data-test="section-impl"] .q-expansion-item__content').exists()).toBe(true) // 펼침
    expect(w.find('[data-test="section-request"] .q-expansion-item__content').exists()).toBe(false) // 접힘
    const bar = w.find('.next-action--bar')
    expect(bar.exists()).toBe(true)
    expect(bar.find('[data-test="next-action-primary"]').text()).toContain('배포')
    expect(w.find('.conv-header-chips').exists()).toBe(false)
    expect(w.text()).not.toContain('claude-opus-5') // 정보 섹션은 접힘 상태
    w.unmount()
  })

  it('분석 결과 섹션의 "전체 보기"는 전체화면 마크다운 뷰어를 연다', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(baseTask)
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="section-analysis"] .q-item').trigger('click')
    await flushPromises()
    await w.find('[data-test="analysis-open-viewer"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.q-dialog__inner--maximized')).not.toBeNull()
    expect(document.body.querySelector('[data-test="markdown-viewer"] h1')?.textContent).toBe('요약')
    w.unmount()
  })
})
```
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/task-detail-mobile.spec.ts` → FAIL
- [ ] **Step 3: 구현**

`frontend/components/MarkdownViewerDialog.vue`:
```vue
<script setup lang="ts">
// 전체화면 마크다운 뷰어 (스펙 2026-09-06 §4.2) — 긴 분석/디자인 문서를 상세 페이지 인라인 대신 여기서 읽는다.
import { renderMarkdown } from '~/composables/useMarkdown'
const props = defineProps<{ title: string; markdown: string | null }>()
const show = defineModel<boolean>({ default: false })
const html = computed(() => renderMarkdown(props.markdown))
</script>

<template>
  <q-dialog v-model="show" maximized>
    <q-card class="viewer">
      <div class="row items-center no-wrap viewer-head">
        <q-btn v-close-popup flat round icon="close" aria-label="닫기" />
        <div class="text-subtitle1 text-weight-medium ellipsis">{{ title }}</div>
      </div>
      <q-separator />
      <q-card-section class="viewer-body"><div class="md-scroll markdown" data-test="markdown-viewer" v-html="html" /></q-card-section>
    </q-card>
  </q-dialog>
</template>

<style scoped>
.viewer { display: flex; flex-direction: column; }
.viewer-head { min-height: 56px; padding: 0 8px; gap: 4px; }
.viewer-body { flex: 1; overflow: auto; }
</style>
```

`frontend/components/tasks/TaskDetailMobile.vue`:
```vue
<script setup lang="ts">
// 모바일 상세 본문 (스펙 2026-09-06 §4.2, 캔버스 Detail/DetailHistory): 아코디언 섹션. 현재 단계 섹션만 기본 펼침.
// 데이터 조회/액션은 부모([id].vue) 소유. 인터뷰 패널·디자인 카드·이력은 슬롯으로 받는다.
import MarkdownViewerDialog from '~/components/MarkdownViewerDialog.vue'
import { renderMarkdown } from '~/composables/useMarkdown'
import type { StageStep } from '~/composables/taskStages'

// [id].vue의 TaskResponse 중 이 컴포넌트가 읽는 부분집합
export interface DetailTask {
  id: number
  description: string
  status: string
  statusLabel: string
  repoAlias: string | null
  githubRepo: string
  githubBranch: string
  model: string
  effort: string
  failureReason: string | null
  retryCount: number
  maxRetry: number
  totalCostUsd: number | null
  totalTokens: number | null
  mcpsExtra: { name: string; transport: string }[]
  attachments: { id: number; fileName: string; sizeBytes: number }[]
  envVars: { key: string; value: string; secret: boolean }[]
  analysis: { markdownResult: string; subtasksJson: string; approved: boolean } | null
  design: { designMarkdown: string } | null
  implementation: { prUrl: string | null; prNumber: number | null; headBranch: string | null; headSha: string | null } | null
  deployment: { deployUrl: string | null; deployHostPort: number | null; deployImage: string | null; deployedAt: string | null; deployLog: string | null } | null
}

const props = defineProps<{ task: DetailTask; isAdmin: boolean; steps: StageStep[] }>()
const emit = defineEmits<{ (e: 'retry'): void; (e: 'download', attachmentId: number): void }>()

const currentKey = computed(() => props.steps.find((s) => s.state === 'current' || s.state === 'failed')?.key ?? 'analysis')
const open = reactive<Record<string, boolean>>({
  history: false, request: false,
  analysis: currentKey.value === 'analysis', design: currentKey.value === 'design',
  impl: currentKey.value === 'impl', deploy: currentKey.value === 'deploy', interviews: false, info: false,
})
const analysisPreview = computed(() => renderMarkdown(props.task.analysis?.markdownResult))
const viewer = reactive({ open: false, title: '', markdown: null as string | null })
function openViewer(title: string, markdown: string | null) {
  viewer.title = title
  viewer.markdown = markdown
  viewer.open = true
}
function fmtTokens(n: number) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k'
  return String(n)
}
function fmtSize(bytes: number) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`
  return `${bytes}B`
}
</script>

<template>
  <div class="detail-mobile">
    <q-list bordered class="rounded-borders bg-white sections">
      <q-expansion-item v-model="open.history" label="진행 이력" data-test="section-history" expand-separator>
        <slot name="history" />
      </q-expansion-item>
      <q-expansion-item v-model="open.request" label="요청 상세" :caption="`${task.repoAlias ?? task.githubRepo} · ${task.githubBranch}`" data-test="section-request" expand-separator>
        <q-card-section><div class="md-scroll"><pre class="req">{{ task.description }}</pre></div></q-card-section>
      </q-expansion-item>
      <q-expansion-item v-if="task.analysis" v-model="open.analysis" label="분석 결과" caption="마크다운" data-test="section-analysis" expand-separator>
        <q-card-section>
          <div class="md-preview"><div class="md-scroll markdown" v-html="analysisPreview" /><div class="fade" /></div>
          <q-btn outline color="primary" class="full-width q-mt-sm" label="전체 보기" data-test="analysis-open-viewer" @click="openViewer('분석 결과', task.analysis.markdownResult)" />
        </q-card-section>
      </q-expansion-item>
      <q-expansion-item v-if="task.design" v-model="open.design" label="디자인 문서" :caption="task.status === 'DESIGN_REVIEW' ? '검토 대기' : '마크다운'" data-test="section-design" expand-separator>
        <slot name="design">
          <q-card-section>
            <q-btn outline color="primary" class="full-width" label="전체 보기" @click="openViewer('디자인 문서', task.design.designMarkdown)" />
          </q-card-section>
        </slot>
      </q-expansion-item>
      <q-expansion-item v-model="open.impl" label="구현 결과" :caption="task.implementation?.prNumber ? `PR #${task.implementation.prNumber}` : '아직 구현 전'" data-test="section-impl" expand-separator>
        <q-card-section v-if="task.implementation" class="kv">
          <div v-if="task.implementation.prUrl"><span class="k">PR</span><a :href="task.implementation.prUrl" target="_blank" rel="noopener">#{{ task.implementation.prNumber }} 열기</a></div>
          <div v-if="task.implementation.headBranch"><span class="k">브랜치</span><code>{{ task.implementation.headBranch }}</code></div>
          <div v-if="task.implementation.headSha"><span class="k">커밋</span><code>{{ task.implementation.headSha.slice(0, 7) }}</code></div>
        </q-card-section>
        <q-card-section v-else class="text-grey-7">플랜 확정·승인 뒤 워커가 구현합니다.</q-card-section>
      </q-expansion-item>
      <q-expansion-item v-model="open.deploy" label="배포" :caption="task.deployment?.deployUrl ?? '아직 배포 전'" data-test="section-deploy" expand-separator>
        <q-card-section v-if="task.deployment?.deployUrl" class="kv">
          <div><span class="k">URL</span><a :href="task.deployment.deployUrl" target="_blank" rel="noopener">{{ task.deployment.deployUrl }}</a></div>
          <div><span class="k">포트</span><code>{{ task.deployment.deployHostPort }}</code></div>
          <div v-if="task.deployment.deployedAt"><span class="k">배포</span>{{ new Date(task.deployment.deployedAt).toLocaleString() }}</div>
        </q-card-section>
        <q-card-section v-else class="text-grey-7">PR생성 상태에서 관리자가 배포할 수 있습니다.</q-card-section>
      </q-expansion-item>
      <q-expansion-item v-model="open.interviews" label="지난 인터뷰" data-test="section-interviews" expand-separator>
        <slot name="interviews" />
      </q-expansion-item>
      <q-expansion-item v-model="open.info" label="정보" caption="모델 · 사용량 · 첨부" data-test="section-info" expand-separator>
        <q-card-section class="kv">
          <div><span class="k">모델</span>{{ task.model }} · {{ task.effort }}</div>
          <div v-if="task.totalCostUsd != null"><span class="k">사용량</span>{{ fmtTokens(task.totalTokens ?? 0) }} 토큰 · ${{ Number(task.totalCostUsd).toFixed(2) }}</div>
          <div v-if="task.mcpsExtra.length"><span class="k">MCP</span>{{ task.mcpsExtra.map((m) => m.name).join(', ') }}</div>
          <div v-if="task.attachments.length" class="column" style="gap: 4px">
            <span class="k">첨부</span>
            <q-chip v-for="a in task.attachments" :key="a.id" clickable dense icon="attach_file" :label="`${a.fileName} (${fmtSize(a.sizeBytes)})`" @click="emit('download', a.id)" />
          </div>
          <div v-if="task.failureReason" class="text-negative"><span class="k">실패 사유</span>{{ task.failureReason }}</div>
          <q-btn v-if="task.status === 'FAILED' || task.status === 'DESIGN_FAILED'" unelevated color="warning" icon="refresh" :label="task.status === 'DESIGN_FAILED' ? '재시도' : `재시도 (${task.retryCount}/${task.maxRetry})`" :disable="task.status !== 'DESIGN_FAILED' && task.retryCount >= task.maxRetry" @click="emit('retry')" />
        </q-card-section>
      </q-expansion-item>
    </q-list>
    <MarkdownViewerDialog v-model="viewer.open" :title="viewer.title" :markdown="viewer.markdown" />
  </div>
</template>

<style scoped>
.sections :deep(.q-item) { min-height: 48px; }
.req { white-space: pre-wrap; margin: 0; font-family: inherit; font-size: 14px; }
.md-preview { position: relative; max-height: 9.3em; overflow: hidden; }
.fade { position: absolute; left: 0; right: 0; bottom: 0; height: 3em; background: linear-gradient(to bottom, rgba(255, 255, 255, 0), #fff); }
.kv { display: flex; flex-direction: column; gap: 8px; font-size: 13.5px; }
.kv .k { display: inline-block; width: 64px; color: #757575; }
</style>
```

`[id].vue` 템플릿: 헤더 row의 모델/effort/비용 칩 3개를 `<div v-if="!$q.screen.lt.md" class="row items-center conv-header-chips">…</div>`로 묶고(모바일에서는 정보 섹션이 대신 보여준다), progress-card 아래를 다음으로 분기:

```vue
      <template v-if="$q.screen.lt.md">
        <TaskDetailMobile :task="task" :is-admin="auth.isAdmin" :steps="steps" @retry="retry" @download="(id) => downloadAttachment(task.attachments.find((a) => a.id === id)!)">
          <template #interviews><InterviewHistoryCard :task-id="task.id" :task-status="task.status" flat /></template>
          <template v-if="task.design" #design>
            <div id="design-card"><DesignReviewCard :task-id="task.id" :status="task.status" :design="task.design" :is-admin="auth.isAdmin" @refresh="refresh" /></div>
          </template>
        </TaskDetailMobile>
        <q-card v-if="isInterviewPhase && task.interviewSessionId" id="interview-card" flat bordered class="q-mt-md">
          <q-card-section class="text-h6">대화형 분석</q-card-section>
          <q-separator />
          <q-card-section class="q-pa-none">
            <InterviewPanel :session-id="task.interviewSessionId" :readonly="!auth.isAdmin" @confirmed="onInterviewConfirmed" @close="refresh" />
          </q-card-section>
        </q-card>
        <TaskNextAction :action="action" variant="bar" @act="onAction" />
      </template>
      <template v-else>
        <!-- 기존 데스크톱 카드들 그대로 -->
      </template>
```
`ApproveDialog`·환경변수 `q-dialog`는 양쪽 공용이라 분기 밖에 둔다. `q-page`에 `:style="$q.screen.lt.md ? 'padding-bottom: 88px' : ''"`. `InterviewHistoryCard`가 `flat` 프롭이 없으면 그대로 두고 `flat`을 뺀다.

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run test/task-detail-mobile.spec.ts test/task-detail-progress.spec.ts test/task-detail-usage.spec.ts test/task-detail-attachments.spec.ts` → PASS. `npm run typecheck` → 오류 0.
- [ ] **Step 5: Commit** — `git add frontend/components/MarkdownViewerDialog.vue frontend/components/tasks/TaskDetailMobile.vue "frontend/pages/tasks/[id].vue" frontend/test/task-detail-mobile.spec.ts && git commit -m "feat(front): 작업 상세 모바일 — 아코디언 섹션·하단 액션 바·전체화면 마크다운 뷰어"`

---

## S4. 진행 이력

### Task 10: `GET /api/tasks/{id}/history` (Java)

**Files:**
- Create: `src/main/java/com/hamonsoft/netismaker/dto/TaskHistoryResponse.java`
- Modify: `src/main/java/com/hamonsoft/netismaker/service/TaskService.java` (`getHistory` 추가, `getForView` 아래)
- Modify: `src/main/java/com/hamonsoft/netismaker/controller/TaskController.java` (`interviews` 아래에 `history`)
- Test: `src/test/java/com/hamonsoft/netismaker/dto/TaskHistoryResponseTest.java` (신규, 순수 단위), `src/test/java/com/hamonsoft/netismaker/controller/TaskHistoryApiIntegrationTest.java` (신규, Testcontainers)

**Interfaces:**
- Produces: `record TaskHistoryResponse(String fromStatus, String toStatus, String fromLabel, String toLabel, String actorType, String actorId, String reason, OffsetDateTime at)` — `fromStatus/toStatus`는 영문 enum 이름(미지 dbValue면 null), `fromLabel/toLabel`은 DB의 한글 dbValue 그대로. `TaskService.getHistory(Long taskId, int limit): List<TaskStatusHistory>` 최신순. 엔드포인트는 최대 200건, ACL = `getForView`(소유자·관리자, 삭제된 작업 404).

- [ ] **Step 1: 실패 테스트(단위)** — `TaskHistoryResponseTest.java`

```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskHistoryResponseTest {

    @Test
    void of_maps_db_labels_to_enum_names_and_keeps_labels() {
        TaskStatusHistory h = TaskStatusHistory.log(18L, TaskStatus.AWAITING_APPROVAL, TaskStatus.INTERVIEWING,
                "user", "admin", "관리자 승인 → 인터뷰 시작");
        TaskHistoryResponse r = TaskHistoryResponse.of(h);
        assertThat(r.fromStatus()).isEqualTo("AWAITING_APPROVAL");
        assertThat(r.toStatus()).isEqualTo("INTERVIEWING");
        assertThat(r.fromLabel()).isEqualTo("승인대기");
        assertThat(r.toLabel()).isEqualTo("인터뷰중");
        assertThat(r.actorType()).isEqualTo("user");
        assertThat(r.actorId()).isEqualTo("admin");
        assertThat(r.reason()).isEqualTo("관리자 승인 → 인터뷰 시작");
        assertThat(r.at()).isNotNull();
    }

    @Test
    void of_first_row_has_null_from() {
        TaskStatusHistory h = TaskStatusHistory.log(18L, null, TaskStatus.AWAITING_APPROVAL, "user", "u1", "작업 등록");
        TaskHistoryResponse r = TaskHistoryResponse.of(h);
        assertThat(r.fromStatus()).isNull();
        assertThat(r.fromLabel()).isNull();
        assertThat(r.toStatus()).isEqualTo("AWAITING_APPROVAL");
    }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*TaskHistoryResponseTest'` → 컴파일 실패(클래스 없음)
- [ ] **Step 3: 구현**

`TaskHistoryResponse.java`:
```java
package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;

import java.time.OffsetDateTime;

/**
 * 작업 상태 변경 이력 1건 (스펙 2026-09-06 §6). fromStatus/toStatus는 영문 enum 이름(프론트 로직·색),
 * fromLabel/toLabel은 DB에 저장된 한글 dbValue 그대로(표시). 과거에 폐기된 상태값이 남아 있으면 이름은 null, 라벨은 유지.
 */
public record TaskHistoryResponse(
        String fromStatus,
        String toStatus,
        String fromLabel,
        String toLabel,
        String actorType,
        String actorId,
        String reason,
        OffsetDateTime at
) {
    public static TaskHistoryResponse of(TaskStatusHistory h) {
        return new TaskHistoryResponse(nameOf(h.getFromStatus()), nameOf(h.getToStatus()),
                h.getFromStatus(), h.getToStatus(), h.getActorType(), h.getActorId(), h.getReason(), h.getAt());
    }

    private static String nameOf(String dbValue) {
        if (dbValue == null) return null;
        try {
            return TaskStatus.fromDb(dbValue).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
```
`TaskService.java` — `getForView` 바로 아래:
```java
    /** 상태 변경 이력, 최신순, 상한 limit. ACL은 호출자가 getForView로 먼저 검증한다. */
    @Transactional(readOnly = true)
    public List<TaskStatusHistory> getHistory(Long taskId, int limit) {
        return historyRepo.findByTaskIdOrderByAtDesc(taskId).stream().limit(limit).toList();
    }
```
`TaskController.java` — `interviews` 아래:
```java
    /**
     * 상태 변경 이력(최신순, 최대 200). ACL = 작업 조회와 동일(소유자 또는 관리자, 삭제된 작업 404).
     * 스펙 2026-09-06 §6 — 모바일 상세 "진행 이력" 타임라인.
     */
    @GetMapping("/{id}/history")
    public List<TaskHistoryResponse> history(@PathVariable Long id, JwtAuthenticationToken auth) {
        String userId = AuthContext.requireUserId(auth);
        taskService.getForView(id, userId, AuthContext.isAdmin(auth));
        return taskService.getHistory(id, 200).stream().map(TaskHistoryResponse::of).toList();
    }
```
(import `com.hamonsoft.netismaker.dto.TaskHistoryResponse` 추가.)

- [ ] **Step 4: 단위 통과** — `./gradlew test --tests '*TaskHistoryResponseTest'` → PASS
- [ ] **Step 5: 통합 테스트 작성** — `TaskHistoryApiIntegrationTest.java` (헬퍼는 `TaskInterviewHistoryIntegrationTest`와 동일 패턴)

```java
package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/tasks/{id}/history — 최신순·ACL·라벨 매핑 (스펙 2026-09-06 §6). RUN_TESTCONTAINERS=true 전용. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class TaskHistoryApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private TaskService taskService;

    @BeforeEach
    void clean() {
        designRepo.deleteAll();
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

    // catalog id=1 = V14 seed (alias 'Netis7.0')
    private Task createTask(String requester) {
        return taskService.create(new TaskCreateRequest(1L, "main", "이력", "설명"), requester);
    }

    @Test
    void 등록과_승인이_최신순으로_라벨과_함께_조회된다() throws Exception {
        Task t = createTask("user1");
        taskService.approve(t.getId(), "admin", null);   // 승인대기 → 인터뷰중

        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(userJwt("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].fromStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[0].toStatus").value("INTERVIEWING"))
                .andExpect(jsonPath("$[0].toLabel").value("인터뷰중"))
                .andExpect(jsonPath("$[0].actorId").value("admin"))
                .andExpect(jsonPath("$[1].fromStatus").isEmpty())
                .andExpect(jsonPath("$[1].toStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[1].reason").value("작업 등록"));
    }

    @Test
    void 비소유자는_403_관리자는_200() throws Exception {
        Task t = createTask("user1");
        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(userJwt("user2"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(adminJwt("admin"))).andExpect(status().isOk());
    }

    @Test
    void 삭제된_작업은_404() throws Exception {
        Task t = createTask("user1");
        taskService.softDelete(t.getId(), "user1", false);
        mvc.perform(get("/api/tasks/" + t.getId() + "/history").with(userJwt("user1"))).andExpect(status().isNotFound());
    }
}
```
`taskService.approve(Long, String, ApproveRequest)`의 세 번째 인자 타입은 `TaskInterviewHistoryIntegrationTest.approveAndGetSessionId`와 같게 `null`을 넘긴다(시그니처는 `grep -n "public Task approve" src/main/java/com/hamonsoft/netismaker/service/TaskService.java`로 확인). "작업 등록" 사유 문자열은 `TaskService.create`의 `TaskStatusHistory.log(... "작업 등록")`와 같아야 한다(`grep -n '"작업 등록"' TaskService.java`로 확인, 다르면 테스트 기대값을 실제 문자열로).

- [ ] **Step 6: 통합 통과** — `RUN_TESTCONTAINERS=true ./gradlew test --tests '*TaskHistoryApiIntegrationTest' --tests '*TaskHistoryResponseTest'` → PASS (Docker 필요; 불가하면 CI에서 확인)
- [ ] **Step 7: Commit** — `git add src/main/java/com/hamonsoft/netismaker/dto/TaskHistoryResponse.java src/main/java/com/hamonsoft/netismaker/service/TaskService.java src/main/java/com/hamonsoft/netismaker/controller/TaskController.java src/test/java/com/hamonsoft/netismaker/dto/TaskHistoryResponseTest.java src/test/java/com/hamonsoft/netismaker/controller/TaskHistoryApiIntegrationTest.java && git commit -m "feat(api): GET /api/tasks/{id}/history — 상태 변경 이력(최신순·라벨·ACL)"`

### Task 11: `TaskHistoryTimeline.vue` + 상세 연결

**Files:**
- Create: `frontend/components/tasks/TaskHistoryTimeline.vue`
- Modify: `frontend/pages/tasks/[id].vue` (모바일 `#history` 슬롯 + 데스크톱 맨 아래 `q-expansion-item` "진행 이력")
- Test: `frontend/components/tasks/TaskHistoryTimeline.spec.ts`

**Interfaces:**
- Consumes: `GET /api/tasks/{id}/history` → `TaskHistoryResponse[]`, `CHIP`
- Produces: props `{ taskId: number }`, emits `loaded(count: number)`. 마운트 시 1회 조회(아코디언이 펼쳐질 때 마운트되므로 지연 로딩). 시각은 `formatKoDateTime(iso)` = `"8월 24일 14:02"`(수동 조립 — `toLocaleTimeString('ko-KR', {hour:'numeric'})`는 Node 24 ICU에서 "AM 7시"가 되는 함정). data-test: `history-entry`, `history-more`.

- [ ] **Step 1: 실패 테스트**

```ts
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskHistoryTimeline, { formatKoDateTime } from './TaskHistoryTimeline.vue'
import { useApiMock } from '../../test/mocks/nuxt'

const rows = [
  { fromStatus: 'IMPLEMENTING', toStatus: 'PR_CREATED', fromLabel: '구현중', toLabel: 'PR생성', actorType: 'system', actorId: 'mac-worker-1', reason: 'Draft PR 생성', at: '2026-08-24T05:02:00Z' },
  { fromStatus: null, toStatus: 'AWAITING_APPROVAL', fromLabel: null, toLabel: '승인대기', actorType: 'user', actorId: 'admin', reason: '작업 등록', at: '2026-07-28T07:09:00Z' },
]

describe('TaskHistoryTimeline (스펙 2026-09-06 §6)', () => {
  it('이력을 최신순으로 그리고 loaded(count)를 emit한다', async () => {
    useApiMock.mockResolvedValueOnce(rows)
    const w = mount(TaskHistoryTimeline, { props: { taskId: 18 } })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/18/history')
    const entries = w.findAll('[data-test="history-entry"]')
    expect(entries).toHaveLength(2)
    expect(entries[0]!.text()).toContain('구현중 → PR생성')
    expect(entries[0]!.text()).toContain('Draft PR 생성')
    expect(entries[1]!.text()).toContain('승인대기') // from 없음 → to만
    expect(w.emitted('loaded')?.[0]).toEqual([2])
    w.unmount()
  })
  it('조회 실패는 안내 문구', async () => {
    useApiMock.mockRejectedValueOnce({ statusCode: 500 })
    const w = mount(TaskHistoryTimeline, { props: { taskId: 18 } })
    await flushPromises()
    expect(w.text()).toContain('이력을 불러오지 못했습니다')
    w.unmount()
  })
  it('formatKoDateTime은 "M월 D일 HH:mm"(24시간, 로컬 시각)', () => {
    const d = new Date(2026, 7, 24, 14, 2)
    expect(formatKoDateTime(d.toISOString())).toBe('8월 24일 14:02')
  })
})
```
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run components/tasks/TaskHistoryTimeline.spec.ts` → FAIL
- [ ] **Step 3: 구현**

```vue
<script lang="ts">
/** "8월 24일 14:02" — Intl 한국어 시간 포맷의 Node 24 ICU 편차("AM 7시")를 피해 직접 조립한다. */
export function formatKoDateTime(iso: string): string {
  const d = new Date(iso)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${hh}:${mm}`
}
</script>

<script setup lang="ts">
// 진행 이력 타임라인 (스펙 2026-09-06 §6) — GET /api/tasks/{id}/history (최신순, 최대 200).
// 아코디언 안에서 펼쳐질 때 마운트되므로 마운트 시 1회만 조회한다.
import { CHIP } from '~/composables/taskStages'

interface HistoryRow {
  fromStatus: string | null
  toStatus: string | null
  fromLabel: string | null
  toLabel: string
  actorType: string
  actorId: string | null
  reason: string | null
  at: string
}

const props = defineProps<{ taskId: number }>()
const emit = defineEmits<{ (e: 'loaded', count: number): void }>()

const rows = ref<HistoryRow[]>([])
const loading = ref(true)
const error = ref(false)
const PAGE = 6
const shown = ref(PAGE)

onMounted(async () => {
  try {
    rows.value = await useApi<HistoryRow[]>(`/api/tasks/${props.taskId}/history`)
    emit('loaded', rows.value.length)
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
})

function colorOf(status: string | null) {
  return status ? (CHIP[status]?.[1] ?? '#757575') : '#757575'
}
</script>

<template>
  <div class="timeline q-px-md q-pb-sm">
    <div v-if="loading" class="text-caption text-grey-6 q-py-sm">불러오는 중…</div>
    <div v-else-if="error" class="text-caption text-negative q-py-sm">이력을 불러오지 못했습니다</div>
    <div v-else-if="rows.length === 0" class="text-caption text-grey-6 q-py-sm">이력이 없습니다</div>
    <template v-else>
      <div v-for="(r, i) in rows.slice(0, shown)" :key="r.at + i" class="entry" data-test="history-entry">
        <div class="rail"><span class="dot" :style="{ background: i === 0 ? colorOf(r.toStatus) : '#bdbdbd' }" /><span v-if="i < Math.min(shown, rows.length) - 1" class="line" /></div>
        <div class="body">
          <div class="text-caption text-grey-6">{{ formatKoDateTime(r.at) }}</div>
          <div class="text-body2 text-weight-medium">
            <template v-if="r.fromLabel">{{ r.fromLabel }} → </template><span :style="{ color: colorOf(r.toStatus) }">{{ r.toLabel }}</span>
          </div>
          <div class="text-caption text-grey-8">{{ r.reason }}<template v-if="r.actorId"> · {{ r.actorId }}</template></div>
        </div>
      </div>
      <q-btn v-if="rows.length > shown" flat no-caps color="primary" class="full-width" :label="`이전 ${rows.length - shown}건 더 보기`" data-test="history-more" @click="shown += PAGE" />
    </template>
  </div>
</template>

<style scoped>
.entry { display: flex; gap: 12px; }
.rail { display: flex; flex-direction: column; align-items: center; width: 12px; }
.dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 5px; }
.line { flex: 1; width: 2px; background: #e0e0e0; margin-top: 4px; }
.body { flex: 1; display: flex; flex-direction: column; gap: 1px; padding-bottom: 12px; }
</style>
```
`[id].vue`: 모바일 `<TaskDetailMobile>`에 `<template #history><TaskHistoryTimeline :task-id="task.id" /></template>`; 데스크톱 `v-else` 블록 맨 아래(분석 결과 카드 뒤)에
```vue
        <q-card flat bordered class="q-mt-md">
          <q-expansion-item icon="history" label="진행 이력" header-class="text-subtitle1" data-test="history-desktop">
            <TaskHistoryTimeline :task-id="task.id" />
          </q-expansion-item>
        </q-card>
```
- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run components/tasks/TaskHistoryTimeline.spec.ts test/task-detail-mobile.spec.ts test/task-detail-progress.spec.ts test/task-detail-usage.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add frontend/components/tasks/TaskHistoryTimeline.vue frontend/components/tasks/TaskHistoryTimeline.spec.ts "frontend/pages/tasks/[id].vue" && git commit -m "feat(front): 진행 이력 타임라인 TaskHistoryTimeline (모바일 아코디언 + 데스크톱 접이식)"`

---

## S5. 전역 내비게이션

### Task 12: 모바일 하단 내비 바 + 헤더 탭 숨김 + 관리 세그먼트

**Files:**
- Modify: `frontend/layouts/default.vue`
- Create: `frontend/components/tasks/AdminSectionTabs.vue`
- Modify: `frontend/pages/admin/workers.vue`, `frontend/pages/admin/mcp-catalog.vue`, `frontend/pages/admin/repo-catalog.vue` (각 `<q-page padding>` 첫 줄에 `<AdminSectionTabs />`)
- Modify: `frontend/assets/css/main.css` (`--bottom-nav-height`), `frontend/pages/questions.vue` (셸 높이에서 내비 높이 차감)
- Test: `frontend/test/layout-default.spec.ts` (확장)

**Interfaces:**
- `layouts/default.vue`: lt.md → `<q-footer data-test="bottom-nav">` 안에 `q-route-tab` 작업(`/tasks`)·질문(`/questions`)·관리(관리자, `/admin/workers`, `/admin/*`에서 활성). 헤더 `q-tabs`는 `v-if="auth.isAuthenticated && !$q.screen.lt.md"`.
- CSS 변수: `:root { --bottom-nav-height: 0px }`, `@media (max-width: 1023.98px) { :root { --bottom-nav-height: 56px } }` — Task 6 FAB·Task 8 bar가 이 변수를 쓴다.

- [ ] **Step 1: 실패 테스트** — `layout-default.spec.ts`에 추가 (기존 `QRouteTabStub`은 `props: { label: String, to: String, icon: String }`로 확장)

```ts
describe('layouts/default — 하단 내비 (스펙 2026-09-06 §4.4, 결정 4)', () => {
  beforeEach(() => {
    Object.assign(authStub, { isAuthenticated: true, isAdmin: true, me: { username: 'admin', email: null } })
  })

  it('lt.md: 헤더 탭은 사라지고 하단 내비에 작업·질문·관리 3탭', async () => {
    await setViewportWidth(390)
    try {
      const w = mountLayout()
      await flushPromises()
      expect(w.find('.q-header .q-tab-stub').exists()).toBe(false)
      const nav = w.find('[data-test="bottom-nav"]')
      expect(nav.exists()).toBe(true)
      expect(nav.findAll('.q-tab-stub').map((t) => t.text())).toEqual(['작업', '질문', '관리'])
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('lt.md + 일반 사용자: 관리 탭 없음', async () => {
    authStub.isAdmin = false
    await setViewportWidth(390)
    try {
      const w = mountLayout()
      await flushPromises()
      expect(w.find('[data-test="bottom-nav"]').findAll('.q-tab-stub').map((t) => t.text())).toEqual(['작업', '질문'])
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('데스크톱: 헤더 탭 5개, 하단 내비 없음', async () => {
    const w = mountLayout()
    await flushPromises()
    expect(w.findAll('.q-header .q-tab-stub')).toHaveLength(5)
    expect(w.find('[data-test="bottom-nav"]').exists()).toBe(false)
    w.unmount()
  })
})
```
`mountLayout`이 `useRoute`를 필요로 하면(관리 탭 활성 판정) 스펙 상단에 `Object.assign(globalThis, { useRoute: () => ({ path: '/tasks' }) })`를 추가한다.

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run test/layout-default.spec.ts` → FAIL
- [ ] **Step 3: 구현**

`layouts/default.vue` 스크립트: `const route = useRoute()` + `const isAdminRoute = computed(() => route.path.startsWith('/admin'))`. 템플릿:
```vue
        <q-tabs v-if="auth.isAuthenticated && !$q.screen.lt.md" shrink>
          …(기존 5개 그대로)…
        </q-tabs>
```
`</q-header>` 뒤, `<q-page-container>` 앞에:
```vue
    <q-footer v-if="auth.isAuthenticated && $q.screen.lt.md" bordered class="bg-white bottom-nav" data-test="bottom-nav">
      <q-tabs dense no-caps align="justify" active-color="primary" indicator-color="transparent" class="text-grey-7">
        <q-route-tab to="/tasks" icon="assignment" label="작업" />
        <q-route-tab to="/questions" icon="forum" label="질문" />
        <q-route-tab v-if="auth.isAdmin" to="/admin/workers" icon="settings" label="관리" :class="{ 'q-tab--active text-primary': isAdminRoute }" />
      </q-tabs>
    </q-footer>
```
스타일: `.bottom-nav { padding-bottom: env(safe-area-inset-bottom); } .bottom-nav :deep(.q-tab) { min-height: 56px; }`.

`main.css`:
```css
:root { --bottom-nav-height: 0px; }
@media (max-width: 1023.98px) {
  :root { --bottom-nav-height: 56px; }
}
```
`pages/questions.vue` 스타일의 `.questions-shell { height: calc(100vh - 50px); }`(53행 부근)을 `height: calc(100vh - 50px - var(--bottom-nav-height, 0px));`로 바꾼다.

`components/tasks/AdminSectionTabs.vue`:
```vue
<script setup lang="ts">
// 관리 하위 3페이지 전환 (스펙 2026-09-06 §4.4) — 모바일에서 헤더 탭이 사라지므로 관리 페이지 상단에 세그먼트로 제공.
import { useQuasar } from 'quasar'
const $q = useQuasar()
</script>

<template>
  <q-tabs v-if="$q.screen.lt.md" dense no-caps align="justify" active-color="primary" indicator-color="primary" class="q-mb-sm admin-tabs" data-test="admin-section-tabs">
    <q-route-tab to="/admin/workers" label="워커 헬스" />
    <q-route-tab to="/admin/mcp-catalog" label="MCP 카탈로그" />
    <q-route-tab to="/admin/repo-catalog" label="레포 카탈로그" />
  </q-tabs>
</template>

<style scoped>
.admin-tabs :deep(.q-tab) { min-height: 44px; }
</style>
```
세 관리 페이지의 `<q-page padding>` 바로 아래에 `<AdminSectionTabs />` + 스크립트 `import AdminSectionTabs from '~/components/tasks/AdminSectionTabs.vue'`.

- [ ] **Step 4: 통과 확인** — `cd frontend && npx vitest run test/layout-default.spec.ts test/repo-catalog-admin.spec.ts test/questions-shell.spec.ts` → PASS
- [ ] **Step 5: Commit** — `git add frontend/layouts/default.vue frontend/components/tasks/AdminSectionTabs.vue frontend/pages/admin frontend/assets/css/main.css frontend/pages/questions.vue frontend/test/layout-default.spec.ts && git commit -m "feat(front): 모바일 하단 내비 바(작업·질문·관리) + 헤더 탭 숨김 + 관리 세그먼트 탭"`

---

## 마무리

### Task 13: 전체 게이트 · 문서 · 스모크 체크리스트

**Files:**
- Modify: `CLAUDE.md`(자주 보는 코드 표 + 프론트 규칙), `docs/superpowers/specs/2026-09-06-tasks-mobile-redesign-design.md`(상태 줄), `TODOS.md`(후속: 하단 내비 배지, 단계별 보기 토글)

- [ ] **Step 1: 전체 게이트**
```bash
cd frontend && npx vitest run && npm run typecheck && NUXT_IGNORE_LOCK=1 npm run build
cd .. && RUN_TESTCONTAINERS=true ./gradlew test
```
Expected: 전부 PASS/성공.

- [ ] **Step 2: 문서** — CLAUDE.md "자주 보는 코드"에 행 추가:
```
| 작업 탭 모바일(리스트·카드·시트·스테퍼·다음 할 일·이력·관리 세그먼트) | `frontend/components/tasks/*.vue`, 순수 함수 `frontend/composables/taskStages.ts`(attentionGroup/sortForMobile/stageSteps/nextAction) |
| 모바일 전역 내비(하단 바) | `frontend/layouts/default.vue`(lt.md `q-footer`), CSS 변수 `--bottom-nav-height` |
```
프론트 규칙에 한 줄: "**모바일 분기는 `$q.screen.lt.md` 하나로.** 다이얼로그는 `:maximized="$q.screen.lt.md"` + `width: min(Npx, 100vw)`, 긴 마크다운/pre는 `.md-scroll`로 감싼다(2026-09-06 작업 탭 재설계)." 스펙 상태 줄을 "구현 완료(PR #N)"로. TODOS에 `### [ ] 하단 내비 확인 필요 배지` 항목 추가(What/Why/Context 형식).

- [ ] **Step 3: Commit** — `git add CLAUDE.md TODOS.md docs && git commit -m "docs: 작업 탭 모바일 재설계 반영 (CLAUDE.md 자주 보는 코드·모바일 규칙, 스펙 상태, TODOS)"`

- [ ] **Step 4: PR + 배포 후 스모크(Playwright, 390/768/1280)** — 체크리스트(PR 본문에 그대로):
  - [ ] `/tasks` 390px: `document.documentElement.scrollWidth === innerWidth`, 첫 카드 y < 600, 보드 `.stage-row` 없음, FAB → 등록 시트 전체화면(카드 x ≥ 0)
  - [ ] `/tasks/18` 390px: 가로 스크롤 없음, 스테퍼 + 하단 바 배포 버튼, 아코디언 "구현 결과" 펼침, 진행 이력 로드, 전체 보기 뷰어
  - [ ] 배포 환경변수 다이얼로그 전체화면(x ≥ 0), ApproveDialog 전체화면
  - [ ] 하단 내비 3탭 전환, `/admin/*` 세그먼트, 질문 대화 페이지 입력창이 내비 위에 보임
  - [ ] 1280px: 보드·상세 기존 모습 + 스테퍼/다음 할 일 배너 추가만

---

## Self-Review (작성자 체크)

- 스펙 커버리지: §1 문제 L1~L6 → Task 6(L1·L2·L3·L4·L5·L6), D1 → Task 2·9a, D2/D3/D4 → Task 9a·9b, D5 → Task 10·11, D6·C1 → Task 1, §4.4 → Task 12, 결정 6(데스크톱 노출) → Task 9a·11, §7 테스트 → 각 태스크 Step 1 + Task 13 스모크. 누락 없음.
- 타입 일관성: `CardTask`(Task 4) ⊂ `TaskResponse`(index.vue) — `implementation`에 `headBranch/headSha`가 있어도 구조적 타이핑으로 허용. `NextActionKind` 9종은 Task 3 정의 = Task 9a `onAction` switch 9분기. `StageStep.key`는 `STAGES.key`(`analysis|design|impl|deploy`) = Task 7 `data-test="step-<key>"` = Task 9b `currentKey` 매핑.
- 플레이스홀더 없음. 각 태스크는 독립 커밋·독립 테스트.
