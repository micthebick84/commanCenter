import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h, onUnmounted, reactive } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { QLayout, QPageContainer } from 'quasar'
import InboxPage from '../pages/inbox.vue'
import InterviewPanel from '../components/InterviewPanel.vue'
import ApproveForm from '../components/ApproveForm.vue'
import TaskRefChangeMenu from '../components/tasks/TaskRefChangeMenu.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'
import { AUTO_NEXT_KEY } from '../composables/taskInbox'

// 처리함 (스펙 2026-11-23 §5·§6) — 목록 묶음, ?task 고르기(replace), 처리 성공 → 다음 건, 다른 곳에서 처리된 건,
// 쓰던 답변·참조 초안 유지, j/k, 폰 전체 화면 창.

// 쿼리를 바꿀 수 있는 라우트 — navigateTo 목이 쿼리를 바꿔 고른 건이 따라온다.
// 실제 라우터를 붙인 시험(activeRouter)에서는 라우터 주소도 같이 바꾼다 — QDialog가 $route 변화를 보는 앱과 같게.
const route = reactive({ path: '/inbox', params: {} as Record<string, string>, query: {} as Record<string, string> })
;(globalThis as any).useRoute = () => route
let activeRouter: Router | null = null
const navigateToMock = vi.fn((to: { query?: Record<string, string> }) => {
  route.query = { ...(to.query ?? {}) }
  return activeRouter ? activeRouter.replace({ path: '/inbox', query: to.query ?? {} }) : Promise.resolve()
})
;(globalThis as any).navigateTo = navigateToMock

// 폴링 핸들 모으기 — 목록·처리 영역·사용량 패널(승인 폼 안)이 각자 useTaskPolling을 쓴다. poll()은 살아 있는 것을 전부 한 번 다시 읽는다.
const basePolling = (globalThis as any).useTaskPolling
const pollers = new Set<{ refresh: () => Promise<void> }>()
;(globalThis as any).useTaskPolling = (fetcher: () => Promise<unknown>) => {
  const handle = basePolling(fetcher)
  pollers.add(handle)
  onUnmounted(() => pollers.delete(handle))
  return handle
}
async function poll() {
  await Promise.all([...pollers].map((p) => p.refresh()))
  await flushPromises()
}

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(InboxPage) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' } } } }

// confirmDialog($q)·Notify는 페이지와 별개의 Vue 앱이라 unmount로 같이 내려가지 않는다 — task-detail-close.spec.ts와 같은 정리
function disposeGlobalDialogs() {
  document.body.querySelectorAll<HTMLElement>('[id^="q-portal--dialog"]').forEach((el) => {
    ;(el as unknown as { __vue_app__?: { unmount: () => void } }).__vue_app__?.unmount()
  })
}

const T_APPROVE = '2026-11-23T01:00:00Z'
const T_INPUT = '2026-11-23T02:00:00Z'

function task(id: number, status: string, statusLabel: string, over: Record<string, unknown> = {}) {
  return {
    ...baseTask,
    id,
    status,
    statusLabel,
    title: `작업 ${id}`,
    analysis: null,
    implementation: null,
    refRepos: [],
    repoCatalogId: 1,
    ...over,
  }
}
// 목록 행과 상세는 같은 status·updatedAt — 처리한 건 숨기기는 이 둘이 같을 때만 숨긴다
const approveTask = () => task(2, 'AWAITING_APPROVAL', '승인대기', { updatedAt: T_APPROVE })
const inputTask = () => task(5, 'INTERVIEW_INPUT', '입력대기', { updatedAt: T_INPUT, interviewSessionId: 55 })
const doneTask = () => task(9, 'PR_CREATED', 'PR생성', { updatedAt: '2026-11-23T03:00:00Z' })

const SNAPSHOT = {
  statusName: 'AWAITING_INPUT',
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }],
  plan: null,
}

let list: Record<string, unknown>[]
let total: number | undefined
let details: Record<number, Record<string, unknown>>

function routeApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (opts?.method === 'POST') return Promise.resolve({})
    if (url === '/api/tasks') return Promise.resolve({ content: list, totalElements: total ?? list.length })
    if (/^\/api\/tasks\/\d+\/interviews$/.test(url)) return Promise.resolve([])
    const m = url.match(/^\/api\/tasks\/(\d+)$/)
    if (m) {
      const d = details[Number(m[1])]
      return d ? Promise.resolve(d) : Promise.reject({ statusCode: 404, data: { message: '작업이 없습니다' } })
    }
    if (/^\/api\/interviews\/\d+$/.test(url)) return Promise.resolve(SNAPSHOT)
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    if (url.startsWith('/api/db-connections')) return Promise.resolve({ enabled: false, items: [] })
    if (url === '/api/repo-catalog') return Promise.resolve([])
    return Promise.resolve(null)
  })
}

let w: VueWrapper | undefined
async function mountPage() {
  w = mount(PageWrapper, mountOpts)
  await flushPromises()
  return w
}

const rowIds = (wr: VueWrapper) => wr.findAll('[data-test="inbox-row"]').map((r) => Number(r.attributes('data-id')))
const calls = (url: string) => useApiMock.mock.calls.filter((c) => c[0] === url)
const textarea = (wr: VueWrapper) => wr.findComponent(InterviewPanel).find('textarea')

async function answer(wr: VueWrapper, text: string) {
  const panel = wr.findComponent(InterviewPanel)
  await panel.find('textarea').setValue(text)
  await panel.find('[data-test="send-answer"]').trigger('click')
  await flushPromises()
}

describe('pages/inbox — 처리함', () => {
  beforeEach(() => {
    authStub.isAdmin = true
    ;(authStub as any).isAuthenticated = true
    route.query = {}
    navigateToMock.mockClear()
    list = [approveTask(), inputTask(), doneTask()]
    total = undefined
    details = { 2: approveTask(), 5: inputTask(), 9: doneTask() }
    routeApi()
    localStorage.removeItem(AUTO_NEXT_KEY)
  })

  afterEach(() => {
    w?.unmount()
    w = undefined
    disposeGlobalDialogs()
    document.body.innerHTML = ''
    localStorage.removeItem(AUTO_NEXT_KEY)
    delete (authStub as any).isAuthenticated
  })

  it('묶음과_건수를_순서대로_그리고_쿼리가_없으면_맨_위를_고른다', async () => {
    const wr = await mountPage()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks', { params: { mine: 'false', size: 200, sort: 'updatedAt,desc' } })
    const groups = wr.findAll('[data-test="inbox-group"]')
    expect(groups.map((g) => g.find('[data-test="inbox-group-label"]').text())).toEqual(['답변 대기', '인터뷰 시작 승인'])
    expect(groups.map((g) => g.find('[data-test="inbox-group-count"]').text())).toEqual(['1', '1'])
    expect(rowIds(wr)).toEqual([5, 2]) // #9 PR생성은 확인 필요가 아니다
    expect(wr.find('[data-test="inbox-total"]').text()).toContain('2')
    expect(wr.find('[data-test="inbox-truncated"]').exists()).toBe(false)
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '5' } }, { replace: true })
    await flushPromises()
    expect(wr.find('[data-test="inbox-row"][data-id="5"]').classes()).toContain('inbox-row--active')
    expect(wr.find('[data-test="inbox-pane"]').text()).toContain('작업 5')
  })

  it('입력대기면_참조_줄과_대화_패널을_세션_키로_그린다', async () => {
    route.query = { task: '5' }
    const wr = await mountPage()
    expect(wr.find('[data-test="inbox-ref-row"]').exists()).toBe(true)
    const panel = wr.findComponent(InterviewPanel)
    expect(panel.exists()).toBe(true)
    expect(panel.props('sessionId')).toBe(55)
    expect(panel.vm.$.vnode.key).toBe(55)
    expect(panel.props('draft')).toBe('') // 빈 글이라도 넘겨야 update:draft가 온다
    // 스냅샷이 입력 대기라 ✎ 참조 변경이 열린다
    expect(wr.findComponent(TaskRefChangeMenu).props('disable')).toBe(false)
    expect(wr.find('[data-test="inbox-agent-working"]').exists()).toBe(false)
    expect(wr.find('[data-test="inbox-open-detail"]').attributes('href')).toBe('/tasks/5')
    expect(wr.find('[data-test="inbox-open-detail"]').attributes('target')).toBe('_blank')
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('답변이_성공하면_다음_건으로_넘어가고_그_건은_바로_숨는다', async () => {
    route.query = { task: '5' }
    const wr = await mountPage()
    const listReads = calls('/api/tasks').length
    await answer(wr, '대시보드만 추가합니다')
    expect(calls('/api/interviews/55/answer')).toHaveLength(1)
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '2' } }, { replace: true })
    expect(calls('/api/tasks').length).toBe(listReads + 1) // 목록을 다시 읽는다
    // 다시 읽은 목록에도 #5가 같은 status·updatedAt으로 남아 있지만 숨는다
    expect(rowIds(wr)).toEqual([2])
    expect(wr.findComponent(ApproveForm).exists()).toBe(true)
  })

  it('토글을_끄면_처리한_건에_머문다', async () => {
    localStorage.setItem(AUTO_NEXT_KEY, '0')
    route.query = { task: '5' }
    const wr = await mountPage()
    const toggle = wr.find('[data-test="inbox-auto-next"]')
    expect(toggle.text()).toContain('처리 후 다음 건으로')
    expect(toggle.attributes('aria-checked')).toBe('false')
    await answer(wr, '대시보드만 추가합니다')
    expect(navigateToMock).not.toHaveBeenCalled()
    expect(wr.findComponent(InterviewPanel).exists()).toBe(true)
    expect(rowIds(wr)).toEqual([2]) // 처리한 건은 목록에서 숨고 오른쪽은 그대로
    // 다시 켜면 저장값을 지운다(없음 = 켬)
    await toggle.trigger('click')
    expect(localStorage.getItem(AUTO_NEXT_KEY)).toBeNull()
  })

  it('계획확인에서_구현_진행(confirmed)이면_다음_건', async () => {
    const review = () => task(5, 'INTERVIEW_REVIEW', '플랜승인대기', { updatedAt: T_INPUT, interviewSessionId: 55 })
    list = [approveTask(), review(), doneTask()]
    details[5] = review()
    route.query = { task: '5' }
    const wr = await mountPage()
    expect(wr.findAll('[data-test="inbox-group-label"]').map((g) => g.text())).toEqual(['계획 확인', '인터뷰 시작 승인'])
    expect(wr.findComponent(InterviewPanel).props('sessionId')).toBe(55)
    wr.findComponent(InterviewPanel).vm.$emit('confirmed', 5)
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '2' } }, { replace: true })
    expect(rowIds(wr)).toEqual([2])
  })

  it('토글을_끄면_승인_뒤_작업을_다시_읽어_끝난_폼이_남지_않는다', async () => {
    localStorage.setItem(AUTO_NEXT_KEY, '0')
    route.query = { task: '2' }
    const wr = await mountPage()
    expect(wr.findComponent(ApproveForm).exists()).toBe(true)
    // 서버는 승인 뒤 인터뷰중으로 바꾼다 — 다음 폴링을 기다리지 않고 다시 읽어 대화 패널로 바뀐다
    details[2] = task(2, 'INTERVIEWING', '인터뷰중', { updatedAt: '2026-11-23T06:00:00Z', interviewSessionId: 22 })
    await wr.find('[data-test="approve-submit"]').trigger('click')
    await flushPromises()
    expect(navigateToMock).not.toHaveBeenCalled()
    expect(wr.findComponent(ApproveForm).exists()).toBe(false)
    expect(wr.findComponent(InterviewPanel).props('sessionId')).toBe(22)
    expect(wr.find('[data-test="inbox-agent-working"]').exists()).toBe(true)
  })

  it('승인대기는_ApproveForm(inbox)를_펼치고_승인_성공이면_다음_건', async () => {
    route.query = { task: '2' }
    const wr = await mountPage()
    const form = wr.findComponent(ApproveForm)
    expect(form.exists()).toBe(true)
    expect(form.props('context')).toBe('inbox')
    expect(form.props('task')).toMatchObject({ id: 2 })
    expect(wr.text()).toContain('첫 질문이 오면 처리함의 답변 대기에 올라옵니다')
    expect(wr.find('[data-test="inbox-cancel-task"]').exists()).toBe(true)
    await wr.find('[data-test="approve-submit"]').trigger('click')
    await flushPromises()
    expect(calls('/api/tasks/2/approve')).toHaveLength(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '5' } }, { replace: true })
    expect(rowIds(wr)).toEqual([5])
  })

  it('작업_취소는_확인하면_다음_건이고_확인창을_닫으면_머문다', async () => {
    route.query = { task: '2' }
    const wr = await mountPage()
    await wr.find('[data-test="inbox-cancel-task"]').trigger('click')
    await flushPromises()
    ;(document.body.querySelector('[data-test="confirm-dialog-cancel"]') as HTMLElement).click()
    await flushPromises()
    expect(calls('/api/tasks/2/cancel')).toHaveLength(0)
    expect(navigateToMock).not.toHaveBeenCalled()

    await wr.find('[data-test="inbox-cancel-task"]').trigger('click')
    await flushPromises()
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(calls('/api/tasks/2/cancel')).toHaveLength(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '5' } }, { replace: true })
  })

  it('인터뷰_취소(close)는_넘어가지_않는다', async () => {
    route.query = { task: '5' }
    const wr = await mountPage()
    const reads = calls('/api/tasks/5').length
    wr.findComponent(InterviewPanel).vm.$emit('close')
    await flushPromises()
    expect(calls('/api/tasks/5').length).toBe(reads + 1) // 승인대기로 돌아간 작업을 다시 읽는다
    expect(navigateToMock).not.toHaveBeenCalled()
    expect(rowIds(wr)).toEqual([5, 2])
  })

  it('같은_건의_done이_두_번_와도_한_번만_넘어간다', async () => {
    route.query = { task: '5' }
    const wr = await mountPage()
    const panel = wr.findComponent(InterviewPanel)
    panel.vm.$emit('answered')
    panel.vm.$emit('answered')
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '2' } }, { replace: true })
    expect(rowIds(wr)).toEqual([2])
  })

  it('다른_곳에서_처리되면_머물고_쓰던_답변은_남는다', async () => {
    route.query = { task: '5' }
    const wr = await mountPage()
    await textarea(wr).setValue('쓰던 답')
    // 다른 건을 갔다 돌아와도 쓰던 답이 입력창에 있다
    route.query = { task: '2' }
    await flushPromises()
    expect(wr.findComponent(InterviewPanel).exists()).toBe(false)
    route.query = { task: '5' }
    await flushPromises()
    expect((textarea(wr).element as HTMLTextAreaElement).value).toBe('쓰던 답')

    // 다른 곳에서 답해 인터뷰중 — 같은 세션이라 패널은 그대로, 안내 한 줄, 쓰던 답 유지
    const panelUid = wr.findComponent(InterviewPanel).vm.$.uid
    const interviewing = { ...inputTask(), status: 'INTERVIEWING', statusLabel: '인터뷰중', updatedAt: '2026-11-23T04:00:00Z' }
    details[5] = interviewing
    list = [approveTask(), interviewing, doneTask()]
    await poll()
    expect(wr.find('[data-test="inbox-agent-working"]').text()).toBe('에이전트가 답을 만드는 중입니다')
    expect(wr.findComponent(InterviewPanel).vm.$.uid).toBe(panelUid) // 다시 마운트되지 않았다
    expect((textarea(wr).element as HTMLTextAreaElement).value).toBe('쓰던 답')
    expect(rowIds(wr)).toEqual([2])

    // 이어서 PR생성까지 — 처리할 것이 없는 상태를 보이고 넘어가지 않는다
    details[5] = task(5, 'PR_CREATED', 'PR생성', { updatedAt: '2026-11-23T05:00:00Z', interviewSessionId: 55 })
    await poll()
    expect(wr.find('[data-test="inbox-body-idle"]').text()).toContain('지금 처리할 것이 없습니다(현재: PR생성)')
    expect(wr.findComponent(InterviewPanel).exists()).toBe(false)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('보내지_않은_참조_변경도_건_전환_뒤에_남는다', async () => {
    const REF = { catalogId: 3, alias: 'acme/ui', branch: 'main', sha: null, fetchedAt: null, error: null }
    route.query = { task: '5' }
    const wr = await mountPage()
    wr.findComponent(TaskRefChangeMenu).vm.$emit('update:modelValue', [REF])
    await flushPromises()
    expect(wr.findComponent(InterviewPanel).props('answerRefRepos')).toEqual([{ repoCatalogId: 3, branch: 'main' }])
    route.query = { task: '2' }
    await flushPromises()
    route.query = { task: '5' }
    await flushPromises()
    expect(wr.findComponent(TaskRefChangeMenu).props('modelValue')).toEqual([REF])
    expect(wr.findComponent(InterviewPanel).props('answerRefRepos')).toEqual([{ repoCatalogId: 3, branch: 'main' }])
  })

  it('쿼리의_작업이_목록에_없어도_조회되면_그_작업을_보인다', async () => {
    details[77] = task(77, 'DEPLOYED', '배포완료', { updatedAt: '2026-11-20T00:00:00Z' })
    route.query = { task: '77' }
    const wr = await mountPage()
    expect(wr.find('[data-test="inbox-pane"]').text()).toContain('작업 77')
    expect(wr.find('[data-test="inbox-body-idle"]').text()).toContain('지금 처리할 것이 없습니다(현재: 배포완료)')
    expect(navigateToMock).not.toHaveBeenCalled()
    // 다음 건 → 목록 맨 위
    await wr.find('[data-test="inbox-idle-next"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '5' } }, { replace: true })
  })

  it('쿼리의_작업이_404면_맨_위를_고른다', async () => {
    route.query = { task: '31' }
    await mountPage()
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { task: '5' } }, { replace: true })
  })

  it('j_k로_이동하고_입력칸_안에서는_무시', async () => {
    route.query = { task: '5' }
    const wr = await mountPage()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j' }))
    await flushPromises()
    expect(navigateToMock).toHaveBeenLastCalledWith({ query: { task: '2' } }, { replace: true })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k' }))
    await flushPromises()
    expect(navigateToMock).toHaveBeenLastCalledWith({ query: { task: '5' } }, { replace: true })

    navigateToMock.mockClear()
    textarea(wr).element.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', bubbles: true }))
    await flushPromises()
    expect(navigateToMock).not.toHaveBeenCalled()

    // 처리함을 떠나면 단축키도 없다
    wr.unmount()
    w = undefined
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j' }))
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('목록이_비면_빈_상태', async () => {
    list = []
    const wr = await mountPage()
    expect(wr.find('[data-test="inbox-empty"]').text()).toContain('확인할 작업이 없습니다')
    expect(wr.find('[data-test="inbox-empty"] a').attributes('href')).toBe('/tasks')
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('200건을_넘으면_안내', async () => {
    total = 201
    const wr = await mountPage()
    expect(wr.find('[data-test="inbox-truncated"]').text()).toBe(
      '최근 변경된 200건만 봤습니다 — 오래된 작업은 작업 목록에서 확인하세요',
    )
  })

  it('목록이_늦어도_처리한_건은_그때_목록_값으로_숨고_마지막이면_빈_상태', async () => {
    // 목록 폴링이 상세보다 늦다 — 행의 updatedAt(02:00)이 처리 영역이 본 값(02:30)보다 오래됐다. 숨기는 기준은 그때 목록의 값이다(스펙 §5.4).
    list = [inputTask(), doneTask()]
    details[5] = { ...inputTask(), updatedAt: '2026-11-23T02:30:00Z' }
    route.query = { task: '5' }
    const wr = await mountPage()
    await answer(wr, '대시보드만 추가합니다')
    // 남은 건이 없어 쿼리를 지우고, 방금 처리한 건을 다시 고르지 않는다
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: {} }, { replace: true })
    expect(route.query).toEqual({})
    expect(rowIds(wr)).toEqual([])
    expect(wr.find('[data-test="inbox-empty"]').text()).toContain('확인할 작업이 없습니다')
    expect(wr.find('[data-test="inbox-pane"]').exists()).toBe(false)
  })

  it('폰_폭:_라우터가_있어도_자동_이동은_창을_닫지_않고_내용만_바꾼다', async () => {
    // test/setup.ts에는 라우터가 없어 QDialog의 "주소가 바뀌면 닫기"가 돌지 않는다 — 실제 앱처럼 라우터를 붙인다
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/inbox', component: { render: () => null } }] })
    await router.push('/inbox')
    await router.isReady()
    activeRouter = router
    await setViewportWidth(390)
    try {
      w = mount(PageWrapper, { ...mountOpts, global: { ...mountOpts.global, plugins: [router] } })
      await flushPromises()
      await w.find('[data-test="inbox-row"][data-id="5"]').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.query).toEqual({ task: '5' })
      expect(navigateToMock).toHaveBeenLastCalledWith({ query: { task: '5' } }, { replace: true })
      expect(document.querySelector('[data-test="inbox-sheet"]')).not.toBeNull()

      // 답하면 창은 그대로, 내용만 다음 건(#2)으로
      await answer(w, '대시보드만 추가합니다')
      await flushPromises()
      expect(navigateToMock).toHaveBeenLastCalledWith({ query: { task: '2' } }, { replace: true })
      expect(router.currentRoute.value.query).toEqual({ task: '2' })
      expect(document.querySelector('[data-test="inbox-sheet"]')).not.toBeNull()
      expect(w.findComponent(ApproveForm).exists()).toBe(true)
    } finally {
      activeRouter = null
      await setViewportWidth(1024)
    }
  })

  it('폰_폭:_행을_누르면_전체_화면_창을_열고_마지막_건을_처리하면_닫고_알린다', async () => {
    await setViewportWidth(390)
    try {
      const wr = await mountPage()
      // 앞 시험이 body를 비워 Notify 컨테이너가 문서 밖에 있을 수 있다 — 알림은 $q.notify 호출로 본다
      const notify = vi.spyOn((wr.findComponent(InboxPage).vm as any).$q, 'notify')
      // 폰은 맨 위를 저절로 열지 않는다 — 목록만
      expect(navigateToMock).not.toHaveBeenCalled()
      expect(document.querySelector('[data-test="inbox-sheet"]')).toBeNull()

      await wr.find('[data-test="inbox-row"][data-id="5"]').trigger('click')
      await flushPromises()
      expect(navigateToMock).toHaveBeenLastCalledWith({ query: { task: '5' } }, { replace: true })
      const sheet = document.querySelector<HTMLElement>('[data-test="inbox-sheet"]')
      expect(sheet).not.toBeNull()
      expect(sheet!.tagName).toBe('DIV')
      expect(sheet!.parentElement!.classList.contains('q-dialog__inner')).toBe(true)
      expect(wr.findComponent(InterviewPanel).props('fill')).toBe(true)

      // 답하면 창 안에서 다음 건(#2)으로
      await answer(wr, '대시보드만 추가합니다')
      expect(navigateToMock).toHaveBeenLastCalledWith({ query: { task: '2' } }, { replace: true })
      expect(document.querySelector('[data-test="inbox-sheet"]')).not.toBeNull()
      expect(wr.findComponent(ApproveForm).exists()).toBe(true)

      // 마지막 건을 승인하면 쿼리를 지우고 창을 닫고 알린다
      await wr.findComponent(ApproveForm).find('[data-test="approve-submit"]').trigger('click')
      await flushPromises()
      expect(navigateToMock).toHaveBeenLastCalledWith({ query: {} }, { replace: true })
      await new Promise((r) => setTimeout(r, 400)) // 창 닫힘 전환
      await flushPromises()
      expect(document.querySelector('[data-test="inbox-sheet"]')).toBeNull()
      expect(notify).toHaveBeenCalledWith({ type: 'positive', message: '모두 처리했습니다' })
      expect(wr.find('[data-test="inbox-empty"]').exists()).toBe(true)
    } finally {
      await setViewportWidth(1024)
    }
  })
})
