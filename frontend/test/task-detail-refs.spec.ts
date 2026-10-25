import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import TaskRefChangeMenu from '../components/tasks/TaskRefChangeMenu.vue'
import RefRepoAddForm from '../components/RefRepoAddForm.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { FakeEventSource } from './mocks/eventsource'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'

// 작업 상세의 참조 repo (작업 스펙 2026-10-24 §7.4) — 칩 줄 표시 조건, 인터뷰 중 관리자의 "✎ 참조 변경"(세션마다 시드하고, 보내지 않은 변경이 없으면 서버를 따라가는 초안),
// 바뀌었을 때만 답변 바디에 refRepos, 출처 질문 링크, 모바일 정보 영역·인터뷰 카드.
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

// 폴링 한 번을 흉내 내려고 전역 useTaskPolling 스텁(setup.ts)을 감싸 refresh 핸들을 잡는다 — 마운트마다 새 핸들
const basePolling = (globalThis as any).useTaskPolling
let refreshPage: (() => Promise<void>) | undefined
;(globalThis as any).useTaskPolling = (fetcher: () => Promise<unknown>) => {
  const handle = basePolling(fetcher)
  refreshPage = handle.refresh
  return handle
}

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = {
  attachTo: document.body,
  global: {
    stubs: {
      'router-link': { template: '<a><slot /></a>' },
      DesignReviewCard: true,
      NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
    },
  },
}

// 세션 참조 세 상태 — 고정 · 아직 안 받음 · 실패 (상세 응답은 인터뷰 단계면 진행 중 세션의 참조를 준다, 스펙 §4.9)
const PINNED = {
  catalogId: 2,
  alias: '옛앱',
  branch: 'release/6.5',
  sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  fetchedAt: '2026-10-24T01:00:00Z',
  error: null,
}
const PENDING = { catalogId: 3, alias: '공통 UI', branch: 'main', sha: null, fetchedAt: null, error: null }
const FAILED = {
  catalogId: 5,
  alias: '인증 서버',
  branch: 'develop',
  sha: null,
  fetchedAt: null,
  error: 'fatal: Remote branch develop not found in upstream origin',
}
const NEW_SHA = 'f0e1d2c3b4a5968778695a4b3c2d1e0f12345678'
// 카탈로그: 1 = 이 작업의 대상(Widgets)
const CATALOG = [
  { id: 1, alias: 'Widgets', host: 'github', defaultBranch: 'main' },
  { id: 2, alias: '옛앱', host: 'gitlab', defaultBranch: 'release/6.5' },
  { id: 3, alias: '공통 UI', host: 'gitlab', defaultBranch: 'main' },
  { id: 4, alias: '배치 서버', host: 'gitlab', defaultBranch: 'master' },
  { id: 5, alias: '인증 서버', host: 'github', defaultBranch: 'develop' },
]
const SNAPSHOT = {
  statusName: 'AWAITING_INPUT',
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }],
  plan: null,
}

/** 인터뷰 입력대기 작업 — 대상 Widgets(1), 세션 99 */
const interviewing = (over: Record<string, unknown> = {}) => ({
  ...baseTask,
  status: 'INTERVIEW_INPUT',
  statusLabel: '입력대기',
  repoCatalogId: 1,
  interviewSessionId: 99,
  analysis: null,
  implementation: null,
  refRepos: [PINNED, PENDING],
  ...over,
})

let current: Record<string, unknown> = baseTask
/** 상세는 `current`(폴링 때 바꿔 끼운다), 패널 스냅샷·카탈로그·DB 목록은 고정 응답 */
function routeApi(task: Record<string, unknown>, catalog: () => Promise<unknown> = () => Promise.resolve(CATALOG)) {
  current = task
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/tasks/42') return Promise.resolve(current)
    if (/^\/api\/interviews\/\d+$/.test(url)) return Promise.resolve(SNAPSHOT)
    if (url === '/api/repo-catalog') return catalog()
    if (url.startsWith('/api/db-connections')) {
      return Promise.resolve({ enabled: true, items: [{ id: 7, name: '운영', dbType: 'MYSQL' }] })
    }
    return Promise.resolve(null)
  })
}

async function mountPage() {
  const w = mount(PageWrapper, mountOpts)
  await flushPromises()
  return w
}
type Wrapper = Awaited<ReturnType<typeof mountPage>>

/** 폴링 한 번 — 상세 응답을 바꿔 끼우고 다시 읽는다 */
async function poll(task: Record<string, unknown>) {
  current = task
  await refreshPage!()
  await flushPromises()
}

const answerCalls = (sessionId = 99) => useApiMock.mock.calls.filter((c) => c[0] === `/api/interviews/${sessionId}/answer`)
const catalogCalls = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/repo-catalog').length
/** a가 b보다 문서에서 앞에 있는가 */
const precedes = (a: Element, b: Element) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

// QMenu는 열린 뒤 transition-duration(기본 300ms)이 지나야 show 이벤트를 내고, 닫을 때 포털을 지우는 것도 같다
const menuTransition = () => new Promise((r) => setTimeout(r, 350))
/** 같은 버튼을 누르면 열리고, 다시 누르면 닫힌다(QMenu의 앵커 토글) */
async function toggleRefMenu(w: Wrapper) {
  await w.find('[data-test="ref-change"]').trigger('click')
  await menuTransition()
  await flushPromises()
}
const menuEl = () => document.body.querySelector('.q-menu')
const menuStates = () => Array.from(menuEl()!.querySelectorAll('[data-test="ref-chip"]')).map((c) => c.getAttribute('data-state'))
/** 열린 메뉴에서 n번째 칩의 ✕ */
async function removeInMenu(n: number) {
  menuEl()!.querySelectorAll<HTMLElement>('[data-test="ref-chip"] .q-chip__icon--remove')[n]!.click()
  await flushPromises()
}
const draftIds = (w: Wrapper) =>
  (w.findComponent(TaskRefChangeMenu).props('modelValue') as Array<{ catalogId: number }>).map((r) => r.catalogId)

/** 기본 입력창으로 답한다(작업 상세는 composer 슬롯을 쓰지 않는다) */
async function answer(w: Wrapper, text: string) {
  await w.find('textarea').setValue(text)
  await w.find('[data-test="send-answer"]').trigger('click')
  await flushPromises()
}

describe('pages/tasks/[id] — 참조 repo (작업 스펙 2026-10-24 §7.4)', () => {
  // 열어 둔 메뉴 포털이 다음 테스트로 새지 않게 — body를 통째로 비우면 Quasar 알림 컨테이너까지 떨어져 나가 다음 알림이 DOM에 안 그려진다
  afterEach(() => {
    document.querySelectorAll('.q-menu').forEach((n) => n.remove())
  })

  it.each([
    ['AWAITING_APPROVAL', '인터뷰 때 받음'],
    ['INTERVIEW_INPUT', '다음 답변 때 받음'],
    ['PR_CREATED', null], // 확정 뒤(task-fixed)에는 다시 받지 않으므로 안내가 없다
  ])('%s 대기 칩 문구', async (status, note) => {
    routeApi(
      interviewing({ status, interviewSessionId: status === 'INTERVIEW_INPUT' ? 99 : null, refRepos: [PENDING] }),
    )
    const w = await mountPage()
    const chip = w.find('[data-test="task-ref-chips"] [data-test="ref-chip"]')
    expect(chip.attributes('data-state')).toBe('pending')
    expect(chip.text()).toContain('📚 공통 UI · main')
    if (note) expect(chip.find('.ref-chip-note').text()).toBe(note)
    else expect(chip.find('.ref-chip-note').exists()).toBe(false)
    w.unmount()
  })

  it('참조 칩 줄은 DB 칩 줄 다음, 참조가 없으면 숨김 — 인터뷰 단계 관리자만 "참조 repo 없음" + ✎', async () => {
    authStub.isAdmin = true
    routeApi(interviewing({ dbConnectionIds: [7] }))
    let w = await mountPage()
    const refRow = w.find('[data-test="task-ref-chips"]')
    const dbRow = w.find('[data-test="task-db-chips"]')
    expect(refRow.exists()).toBe(true)
    expect(dbRow.exists()).toBe(true)
    // DB 칩 줄 바로 다음(구분선 하나 사이)
    const sep = refRow.element.previousElementSibling!
    expect(sep.classList.contains('q-separator')).toBe(true)
    expect(sep.previousElementSibling).toBe(dbRow.element)
    expect(refRow.findAll('[data-test="ref-chip"]').map((c) => c.attributes('data-state'))).toEqual(['pinned', 'pending'])
    expect(refRow.findAll('.q-chip__icon--remove')).toHaveLength(0) // 줄은 읽기 전용 — 편집은 메뉴에서
    w.unmount()

    // 참조 없는 작업 상세는 지금 그대로 — 확정 뒤엔 관리자에게도 줄이 없다(빈 목록이든 구버전 API의 필드 없음이든)
    for (const refRepos of [[], undefined]) {
      routeApi({ ...baseTask, refRepos })
      w = await mountPage()
      expect(w.find('[data-test="task-ref-chips"]').exists()).toBe(false)
      expect(w.find('[data-test="ref-change"]').exists()).toBe(false)
      w.unmount()
    }

    // 인터뷰 단계의 관리자에게는 참조 없이 등록된 작업에도 더할 수 있게 그린다
    routeApi(interviewing({ refRepos: [] }))
    w = await mountPage()
    const row = w.find('[data-test="task-ref-chips"]')
    expect(row.exists()).toBe(true)
    expect(row.find('[data-test="ref-empty"]').text()).toBe('참조 repo 없음')
    expect(row.find('[data-test="ref-change"]').exists()).toBe(true)
    w.unmount()

    // 요청자는 인터뷰 단계라도 참조가 없으면 줄이 없다
    authStub.isAdmin = false
    routeApi(interviewing({ refRepos: [] }))
    w = await mountPage()
    expect(w.find('[data-test="task-ref-chips"]').exists()).toBe(false)
    w.unmount()
  })

  it('요청자는 인터뷰 중에도 ✎가 없다, 관리자는 패널 상태가 입력대기일 때만 열린다', async () => {
    routeApi(interviewing())
    let w = await mountPage()
    expect(w.find('[data-test="task-ref-chips"]').exists()).toBe(true) // 칩은 읽을 수 있다
    expect(w.find('[data-test="ref-change"]').exists()).toBe(false)
    w.unmount()

    authStub.isAdmin = true
    w = await mountPage()
    const btn = w.find('[data-test="task-ref-chips"] [data-test="ref-change"]')
    expect(btn.exists()).toBe(true)
    expect(btn.text()).toBe('✎ 참조 변경')
    expect(btn.attributes('aria-label')).toBe('참조 변경')
    expect(btn.attributes('disabled')).toBeUndefined() // 패널 스냅샷 = 입력대기
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="ref-change"]').attributes('disabled')).toBeDefined()
    FakeEventSource.last().emit('question', { seq: 2, content: '다음 질문' })
    await flushPromises()
    expect(w.find('[data-test="ref-change"]').attributes('disabled')).toBeUndefined()
    w.unmount()
  })

  it('참조를 바꿔 기본 입력창으로 답하면 /api/interviews/99/answer 바디에 refRepos, 안 바꾸면 키 없음', async () => {
    authStub.isAdmin = true
    routeApi(interviewing())
    let w = await mountPage()
    await answer(w, '대시보드만')
    expect(answerCalls()).toHaveLength(1)
    expect(answerCalls()[0]![1]).toEqual({ method: 'POST', body: { answer: '대시보드만', replyToSeq: 1 } })
    expect(answerCalls()[0]![1].body).not.toHaveProperty('refRepos')
    w.unmount()

    useApiMock.mockClear()
    w = await mountPage()
    await toggleRefMenu(w)
    await removeInMenu(1) // 공통 UI 빼기
    await toggleRefMenu(w) // 닫는다
    expect(w.find('[data-test="task-ref-chips"]').findAll('[data-test="ref-chip"]')).toHaveLength(2) // 줄은 서버 값 그대로
    await answer(w, '옛앱만 보면 됩니다')
    expect(answerCalls()[0]![1]).toEqual({
      method: 'POST',
      body: { answer: '옛앱만 보면 됩니다', replyToSeq: 1, refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }] },
    })
    w.unmount()

    // 전부 빼면 빈 배열(전부 해제)
    useApiMock.mockClear()
    w = await mountPage()
    await toggleRefMenu(w)
    await removeInMenu(1)
    await removeInMenu(0)
    await toggleRefMenu(w)
    await answer(w, '참조 없이')
    expect(answerCalls()[0]![1].body.refRepos).toEqual([])
    w.unmount()
  })

  it('보내지 않은 초안 변경은 폴링(refresh)에도 유지, interviewSessionId가 바뀌면 다시 시드', async () => {
    authStub.isAdmin = true
    routeApi(interviewing({ refRepos: [PINNED, PENDING] }))
    const w = await mountPage()
    await toggleRefMenu(w)
    await removeInMenu(1) // 공통 UI 빼기
    await toggleRefMenu(w)
    expect(draftIds(w)).toEqual([2])

    // 같은 세션의 폴링: 서버가 공통 UI를 받아 고정했다 — 초안은 서버 값으로 되돌아가지 않고, 줄은 서버 값을 따른다
    await poll(interviewing({ refRepos: [PINNED, { ...PENDING, sha: NEW_SHA, fetchedAt: '2026-10-24T02:00:00Z' }] }))
    expect(draftIds(w)).toEqual([2])
    expect(
      w.find('[data-test="task-ref-chips"]').findAll('[data-test="ref-chip"]').map((c) => c.attributes('data-state')),
    ).toEqual(['pinned', 'pinned'])

    // 인터뷰 취소 → 승인대기 → 재승인(관리자가 옛앱을 뺐다)으로 새 세션 100 — 낡은 초안(옛앱)이 뺀 참조를 되살리지 않는다
    await poll({ ...interviewing(), status: 'AWAITING_APPROVAL', statusLabel: '승인대기', interviewSessionId: null })
    expect(w.findComponent(TaskRefChangeMenu).exists()).toBe(false)
    await poll(interviewing({ interviewSessionId: 100, refRepos: [PENDING] }))
    expect(draftIds(w)).toEqual([3])
    await answer(w, '새 세션 첫 답변')
    expect(answerCalls(100)).toHaveLength(1)
    expect(answerCalls(100)[0]![1].body).not.toHaveProperty('refRepos')
    w.unmount()
  })

  // 같은 세션에서 다른 화면(휴대폰·다른 탭·다른 관리자)이 참조를 바꿨을 때 — 이 화면의 초안이 낡은 집합으로 남으면 다음 답변이 그 변경을 되돌린다
  it('보내지 않은 변경이 없으면 같은 세션의 폴링에서 서버 집합을 따라간다 — 다음 답변이 다른 화면의 변경을 되돌리지 않는다', async () => {
    authStub.isAdmin = true
    routeApi(interviewing({ refRepos: [PINNED, PENDING] }))
    const w = await mountPage()
    expect(draftIds(w)).toEqual([2, 3])

    // 다른 화면에서 공통 UI를 뺐다 — 이 화면은 초안을 건드리지 않았으므로 서버 집합을 따른다
    await poll(interviewing({ refRepos: [PINNED] }))
    expect(draftIds(w)).toEqual([2])
    await answer(w, '옛앱만 보면 됩니다')
    expect(answerCalls()).toHaveLength(1)
    expect(answerCalls()[0]![1].body).not.toHaveProperty('refRepos') // 공통 UI를 되살리지 않는다

    // 다른 화면에서 인증 서버를 더했다 — 더한 것도 따라간다
    await poll(interviewing({ refRepos: [PINNED, FAILED] }))
    expect(draftIds(w)).toEqual([2, 5])
    w.unmount()
  })

  it('보내지 않은 변경이 있으면 폴링이 서버 집합을 바꿔도 초안을 두고, 보낸 변경이 반영된 뒤로는 다시 서버를 따라간다', async () => {
    authStub.isAdmin = true
    routeApi(interviewing({ refRepos: [PINNED, PENDING] }))
    const w = await mountPage()
    await toggleRefMenu(w)
    await removeInMenu(0) // 옛앱 빼기 — 아직 보내지 않았다
    await toggleRefMenu(w)
    expect(draftIds(w)).toEqual([3])

    // 다른 화면에서 인증 서버를 더했다 — 보내지 않은 변경을 지우지 않는다
    await poll(interviewing({ refRepos: [PINNED, PENDING, FAILED] }))
    expect(draftIds(w)).toEqual([3])
    await answer(w, '공통 UI만')
    expect(answerCalls()[0]![1].body.refRepos).toEqual([{ repoCatalogId: 3, branch: 'main' }])

    // 보낸 변경이 서버에 반영됐다(초안 = 서버) — 맞춘 기준을 옮기므로, 그 뒤 다른 화면의 변경은 다시 따라간다
    await poll(interviewing({ refRepos: [PENDING] }))
    expect(draftIds(w)).toEqual([3])
    await poll(interviewing({ refRepos: [PENDING, FAILED] }))
    expect(draftIds(w)).toEqual([3, 5])
    w.unmount()
  })

  it('패널 상태가 입력대기·계획 완료로 바뀌면 작업을 바로 다시 읽는다 — 계획 턴이 고정한 참조가 확정 화면에 바로 고정으로 보인다', async () => {
    authStub.isAdmin = true
    routeApi(interviewing({ refRepos: [PINNED, PENDING] }))
    const w = await mountPage()
    const taskReads = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks/42').length
    expect(taskReads()).toBe(1) // 처음 연결(스냅샷 = 입력대기)에는 다시 읽지 않는다
    const es = FakeEventSource.last()

    es.emit('status', 'RUNNING')
    await flushPromises()
    expect(taskReads()).toBe(1)
    es.emit('question', { seq: 2, content: '다음 질문' }) // → 입력대기
    await flushPromises()
    expect(taskReads()).toBe(2)

    // 계획 턴: 서버는 계획 보고와 같은 요청으로 공통 UI를 고정했다 — 5초 폴링은 아직 오지 않았다
    current = interviewing({
      status: 'INTERVIEW_REVIEW',
      statusLabel: '플랜승인대기',
      refRepos: [PINNED, { ...PENDING, sha: NEW_SHA, fetchedAt: '2026-10-24T02:00:00Z' }],
    })
    es.emit('status', 'RUNNING')
    await flushPromises()
    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()

    expect(taskReads()).toBe(3)
    const box = w.find('[data-test="confirm-refs"]')
    expect(box.exists()).toBe(true)
    expect(box.findAll('[data-test="confirm-ref-dropped"]')).toHaveLength(0) // "인터뷰에서 받지 못해 …"가 잠깐도 보이지 않는다
    expect(box.findAll('[data-test="ref-chip"]').map((c) => c.attributes('data-state'))).toEqual(['pinned', 'pinned'])
    w.unmount()
  })

  it('✎ 메뉴를 처음 열 때만 /api/repo-catalog — 페이지를 열 때는 0회', async () => {
    authStub.isAdmin = true
    routeApi(interviewing())
    const w = await mountPage()
    expect(catalogCalls()).toBe(0)
    await toggleRefMenu(w)
    expect(menuEl()).not.toBeNull()
    expect(catalogCalls()).toBe(1)
    await toggleRefMenu(w) // 닫는다
    expect(menuEl()).toBeNull()
    await toggleRefMenu(w) // 다시 열어도
    expect(menuEl()).not.toBeNull()
    expect(catalogCalls()).toBe(1)
    w.unmount()
  })

  it('카탈로그를 못 받으면 "레포 목록을 불러오지 못했습니다"로 알리고, 다음에 열 때 다시 부른다', async () => {
    authStub.isAdmin = true
    let fail = true
    routeApi(interviewing(), () => (fail ? Promise.reject(new Error('network')) : Promise.resolve(CATALOG)))
    const w = await mountPage()
    await toggleRefMenu(w)
    expect(catalogCalls()).toBe(1)
    const note = Array.from(document.querySelectorAll('.q-notification')).find((n) =>
      n.textContent?.includes('레포 목록을 불러오지 못했습니다'),
    )
    expect(note).toBeTruthy()
    expect(note!.classList.contains('bg-negative')).toBe(true)

    fail = false
    await toggleRefMenu(w) // 닫고
    await toggleRefMenu(w) // 다시 연다 → 재시도
    expect(catalogCalls()).toBe(2)
    w.unmount()
  })

  it('✎ 메뉴의 추가 폼 후보에 대상 레포가 없고, 메뉴 칩은 서버 상태(@sha7·⚠)를 입힌다', async () => {
    authStub.isAdmin = true
    // 승인 직후: 두 참조 모두 아직 안 받았다 — 초안은 이 상태로 시드된다
    routeApi(interviewing({ refRepos: [PENDING, { ...FAILED, error: null }] }))
    const w = await mountPage()
    // 첫 턴 뒤 폴링: 공통 UI는 고정, 인증 서버는 실패
    await poll(interviewing({ refRepos: [{ ...PENDING, sha: NEW_SHA, fetchedAt: '2026-10-24T02:00:00Z' }, FAILED] }))
    await toggleRefMenu(w)
    expect(menuStates()).toEqual(['pinned', 'failed'])
    expect(menuEl()!.textContent).toContain('📚 공통 UI · main@f0e1d2c')
    expect(menuEl()!.textContent).toContain('⚠')
    // 입히는 것은 표시뿐 — 초안 자체는 시드 때 그대로
    const draft = w.findComponent(TaskRefChangeMenu).props('modelValue') as Array<{ sha?: string | null }>
    expect(draft.map((r) => r.sha ?? null)).toEqual([null, null])

    expect(w.findComponent(RefRepoAddForm).exists()).toBe(false) // 눌러야 펼쳐진다
    menuEl()!.querySelector<HTMLElement>('[data-test="ref-add"]')!.click()
    await flushPromises()
    const form = w.findComponent(RefRepoAddForm)
    expect(form.exists()).toBe(true)
    expect(form.props('catalog')).toEqual(CATALOG)
    expect(form.props('excludeIds')).toEqual([1, 3, 5]) // 대상(Widgets) + 이미 고른 참조

    form.vm.$emit('add', { catalogId: 4, alias: '배치 서버', branch: 'master' })
    await flushPromises()
    expect(draftIds(w)).toEqual([3, 5, 4])
    expect(menuStates()).toEqual(['pinned', 'failed', 'pending']) // 서버가 모르는 새 참조는 대기
    expect(w.findComponent(RefRepoAddForm).exists()).toBe(false) // 추가하면 접는다
    expect(menuEl()!.querySelector('[data-test="ref-add"]')).toBeNull() // 3개면 추가 칩이 사라진다
    w.unmount()
  })

  it('메뉴를 닫았다 다시 열면 추가 폼은 접혀 있다', async () => {
    authStub.isAdmin = true
    routeApi(interviewing({ refRepos: [PINNED] }))
    const w = await mountPage()
    await toggleRefMenu(w)
    menuEl()!.querySelector<HTMLElement>('[data-test="ref-add"]')!.click()
    await flushPromises()
    expect(w.findComponent(RefRepoAddForm).exists()).toBe(true)
    await toggleRefMenu(w) // 닫는다
    await toggleRefMenu(w) // 다시 연다
    expect(menuEl()).not.toBeNull()
    expect(w.findComponent(RefRepoAddForm).exists()).toBe(false)
    w.unmount()
  })

  it('출처가 있으면 레포 옆 "↩ 질문 #3에서 이어짐" → /questions/3', async () => {
    routeApi({ ...baseTask, sourceQuestionId: 3 })
    let w = await mountPage()
    const link = w.find('[data-test="source-question-link"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toBe('↩ 질문 #3에서 이어짐')
    expect(link.attributes('href')).toBe('/questions/3')
    // "레포" 칸 안 — 레포 이름과 같은 섹션
    const section = link.element.closest('.q-card__section')!
    expect(section.textContent).toContain('레포')
    expect(section.textContent).toContain('Widgets')
    w.unmount()

    for (const sourceQuestionId of [null, undefined]) {
      routeApi({ ...baseTask, sourceQuestionId })
      w = await mountPage()
      expect(w.find('[data-test="source-question-link"]').exists()).toBe(false)
      w.unmount()
    }
  })

  describe('모바일', () => {
    afterEach(async () => {
      await setViewportWidth(1024)
    })

    it('모바일: 정보 영역 읽기 전용 칩 줄·출처, 관리자 인터뷰 카드에 ✎', async () => {
      authStub.isAdmin = true
      routeApi(interviewing({ sourceQuestionId: 3 }))
      await setViewportWidth(390)
      let w = await mountPage()
      // 데스크톱 줄은 없고, ✎는 모바일 인터뷰 카드 안에 하나뿐
      expect(w.find('[data-test="task-ref-chips"]').exists()).toBe(false)
      expect(w.findAll('[data-test="ref-change"]')).toHaveLength(1)
      const card = w.find('[data-test="mobile-interview-refs"]')
      expect(card.exists()).toBe(true)
      expect(card.find('[data-test="ref-change"]').exists()).toBe(true)
      expect(card.findAll('[data-test="ref-chip"]').map((c) => c.attributes('data-state'))).toEqual(['pinned', 'pending'])
      expect(card.find('[data-state="pending"] .ref-chip-note').text()).toBe('다음 답변 때 받음')
      expect(w.find('#interview-card').element.contains(card.element)).toBe(true)

      // 정보 영역은 눌러야 펼쳐진다(v-if)
      expect(w.find('[data-test="info-refs"]').exists()).toBe(false)
      await w.find('[data-test="section-info"] .q-item').trigger('click')
      await flushPromises()
      const info = w.find('[data-test="info-refs"]')
      expect(info.exists()).toBe(true)
      expect(info.findAll('[data-test="ref-chip"]').map((c) => c.attributes('data-state'))).toEqual(['pinned', 'pending'])
      expect(info.find('[data-state="pending"] .ref-chip-note').text()).toBe('다음 답변 때 받음')
      expect(info.findAll('.q-chip__icon--remove')).toHaveLength(0) // 읽기 전용
      expect(info.find('[data-test="ref-add"]').exists()).toBe(false)
      const src = w.find('[data-test="info-source-question"]')
      expect(src.text()).toBe('↩ 질문 #3에서 이어짐')
      expect(src.attributes('href')).toBe('/questions/3')

      // 모바일 인터뷰 패널도 바꾼 참조를 답변에 싣는다
      await toggleRefMenu(w)
      await removeInMenu(0) // 옛앱 빼기
      await toggleRefMenu(w)
      await answer(w, '공통 UI만')
      expect(answerCalls()[0]![1].body.refRepos).toEqual([{ repoCatalogId: 3, branch: 'main' }])
      w.unmount()

      // 요청자: 인터뷰 카드에 ✎가 없다(정보 영역 칩 줄은 있다)
      authStub.isAdmin = false
      w = await mountPage()
      expect(w.find('[data-test="mobile-interview-refs"]').exists()).toBe(false)
      expect(w.find('[data-test="ref-change"]').exists()).toBe(false)
      await w.find('[data-test="section-info"] .q-item').trigger('click')
      await flushPromises()
      expect(w.find('[data-test="info-refs"]').exists()).toBe(true)
      w.unmount()
    })

    it('모바일 인터뷰 패널도 입력대기·계획 완료로 바뀌면 작업을 바로 다시 읽는다', async () => {
      authStub.isAdmin = true
      routeApi(interviewing())
      await setViewportWidth(390)
      const w = await mountPage()
      expect(w.find('[data-test="mobile-interview-refs"]').exists()).toBe(true) // 모바일 인터뷰 카드의 패널이다
      const taskReads = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks/42').length
      expect(taskReads()).toBe(1)
      const es = FakeEventSource.last()
      es.emit('status', 'RUNNING')
      await flushPromises()
      es.emit('question', { seq: 2, content: '다음 질문' })
      await flushPromises()
      expect(taskReads()).toBe(2)
      es.emit('status', 'RUNNING')
      await flushPromises()
      es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
      await flushPromises()
      expect(taskReads()).toBe(3)
      w.unmount()
    })

    it('모바일: 참조·출처가 없으면 정보 영역에 줄이 없다', async () => {
      routeApi(baseTask)
      await setViewportWidth(390)
      const w = await mountPage()
      await w.find('[data-test="section-info"] .q-item').trigger('click')
      await flushPromises()
      expect(w.find('[data-test="section-info"] .q-expansion-item__content').exists()).toBe(true)
      expect(w.find('[data-test="info-refs"]').exists()).toBe(false)
      expect(w.find('[data-test="info-source-question"]').exists()).toBe(false)
      w.unmount()
    })
  })
})
