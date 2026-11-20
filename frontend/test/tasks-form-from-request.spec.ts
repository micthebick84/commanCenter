import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, type App } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

// 요청에서 작업 등록 (스펙 2026-11-19 §5.4) — /tasks?fromRequest={id}로 들어오면 마운트 뒤 요청을 읽어 등록 창을 채운다.
// 하네스는 tasks-form-from-task.spec.ts와 같다.

let routeQuery: Record<string, string> = {}
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  useRoute: () => ({ path: '/tasks', params: {}, query: routeQuery }),
  navigateTo: navigateToMock,
})

const notifySpy = vi.fn()
const recordNotify = {
  install(app: App) {
    const $q = app.config.globalProperties.$q as { notify: (opts: unknown) => unknown }
    const show = $q.notify
    $q.notify = (opts: unknown) => {
      notifySpy(opts)
      return show(opts)
    }
  },
}

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

const CATALOG = [{ id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'acme/netis', defaultBranch: 'develop' }]
const BRANCHES = {
  repo: 'acme/netis',
  defaultBranch: 'develop',
  branches: [{ name: 'develop', sha: 'a' }],
  fetchedAt: '2026-11-19T00:00:00Z',
}
const REQUEST = {
  id: 7,
  kind: 'DEV',
  title: '대상장비 탭 오류',
  content: '탭을 누르면 빈 화면이 나옵니다',
  requesterId: 'kim',
  issueType: 'BUG',
  issueTypeLabel: '버그',
  priority: 'HIGH',
  priorityLabel: '▲ 높음',
  dueDate: null,
  targetRepoCatalogId: 1,
  convertible: true,
  attachments: [
    { id: 1, name: 'a.png', sizeBytes: 1, expired: false },
    { id: 2, name: 'b.png', sizeBytes: 1, expired: true },
    { id: 3, name: 'c.png', sizeBytes: 1, expired: false },
  ],
}
const DESCRIPTION = '탭을 누르면 빈 화면이 나옵니다\n\n— 요청 #7 · 버그 · 우선순위 ▲ 높음 · 요청자 kim'

interface PageVm {
  draft: { taskType: string | null; repoCatalogId: number | null; githubBranch: string; title: string; description: string }
  showCreate: boolean
  submit: () => Promise<void>
}

let current: ReturnType<typeof mount> | null = null
beforeEach(() => {
  routeQuery = {}
  navigateToMock.mockReset()
  notifySpy.mockReset()
})
afterEach(() => {
  current?.unmount()
  current = null
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

function stubApi(request: () => Promise<unknown>) {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/requests/7') return request()
    if (url === '/api/tasks/42') return Promise.resolve({ id: 42, title: '원래', status: 'DEPLOYED', githubBranch: 'develop', repoCatalogId: 1 })
    if (url.startsWith('/api/questions/')) return Promise.reject({ data: { message: '질문 없음' } })
    if (url === '/api/tasks' && opts?.method === 'POST') return Promise.resolve({ id: 55 })
    if (url === '/api/repo-catalog') return Promise.resolve(CATALOG)
    if (url === '/api/repos/branches') return Promise.resolve(BRANCHES)
    if (url === '/api/tasks') return Promise.resolve({ content: [], totalElements: 0, totalPages: 0 })
    return Promise.resolve(null)
  })
}

async function mountPage(query: Record<string, string>, request: () => Promise<unknown> = () => Promise.resolve({ ...REQUEST })) {
  routeQuery = query
  stubApi(request)
  const w = mount(PageWrapper, { attachTo: document.body, global: { plugins: [recordNotify] } })
  current = w
  await flushPromises()
  return w
}

const vmOf = (w: ReturnType<typeof mount>) => w.findComponent(TasksIndex).vm as unknown as PageVm
const apiCalls = (url: string) => useApiMock.mock.calls.filter((c) => c[0] === url)
const notice = () => document.querySelector('[data-test="request-attachments-notice"]')

describe('작업 등록 창 — 요청에서 (스펙 2026-11-19 §5.4)', () => {
  it('fromRequest가 없으면 요청을 읽지 않고 창도 열지 않는다', async () => {
    const w = await mountPage({})
    expect(apiCalls('/api/requests/7')).toHaveLength(0)
    expect(vmOf(w).showCreate).toBe(false)
  })

  it('창이 채워져 열린다 — 레포·브랜치·타입(BUG_FIX)·제목·설명, 쿼리를 뺀다', async () => {
    const w = await mountPage({ fromRequest: '7' })
    const vm = vmOf(w)
    expect(vm.showCreate).toBe(true)
    expect(vm.draft.repoCatalogId).toBe(1)
    expect(vm.draft.githubBranch).toBe('develop')
    expect(vm.draft.taskType).toBe('BUG_FIX')
    expect(vm.draft.title).toBe('대상장비 탭 오류')
    expect(vm.draft.description).toBe(DESCRIPTION)
    expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
  })

  it('개발요청 유형이면 작업 타입은 비어 있다', async () => {
    const w = await mountPage({ fromRequest: '7' }, () => Promise.resolve({ ...REQUEST, issueType: 'DEV', issueTypeLabel: '개발요청' }))
    expect(vmOf(w).draft.taskType).toBeNull()
  })

  it('첨부 안내는 만료되지 않은 수만, 0이면 줄이 없다', async () => {
    await mountPage({ fromRequest: '7' })
    expect(notice()!.textContent).toContain('요청 첨부 2개가 함께 등록됩니다')
    current?.unmount()
    current = null
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
    await mountPage({ fromRequest: '7' }, () => Promise.resolve({ ...REQUEST, attachments: [] }))
    expect(notice()).toBeNull()
  })

  it('등록 바디에 sourceRequestId를 싣고 성공하면 새 작업 상세로 간다', async () => {
    const w = await mountPage({ fromRequest: '7' })
    const vm = vmOf(w)
    useApiMock.mockClear()
    navigateToMock.mockClear()
    await vm.submit()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks', {
      method: 'POST',
      body: {
        repoCatalogId: 1,
        githubBranch: 'develop',
        title: '대상장비 탭 오류',
        description: DESCRIPTION,
        taskType: 'BUG_FIX',
        sourceRequestId: 7,
      },
    })
    expect(navigateToMock).toHaveBeenCalledWith('/tasks/55')
  })

  it('등록 실패(409 등)는 서버 메시지를 알리고 창을 유지한다', async () => {
    const w = await mountPage({ fromRequest: '7' })
    const vm = vmOf(w)
    useApiMock.mockImplementation((url: string, opts?: { method?: string }) =>
      url === '/api/tasks' && opts?.method === 'POST' ? Promise.reject({ data: { message: '이미 작업으로 등록된 요청입니다' } }) : Promise.resolve(null),
    )
    await vm.submit()
    await flushPromises()
    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message: '이미 작업으로 등록된 요청입니다' })
    expect(vm.showCreate).toBe(true)
  })

  it('convertible=false면 알림만 띄우고 창을 열지 않는다', async () => {
    const w = await mountPage({ fromRequest: '7' }, () => Promise.resolve({ ...REQUEST, convertible: false }))
    expect(vmOf(w).showCreate).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith({ type: 'warning', message: '작업으로 등록할 수 없는 요청입니다' })
  })

  it('요청을 못 읽으면 창을 열지 않고 알린다', async () => {
    const w = await mountPage({ fromRequest: '7' }, () => Promise.reject({ statusCode: 404, data: { message: '요청을 찾을 수 없습니다' } }))
    expect(vmOf(w).showCreate).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message: '요청을 찾을 수 없습니다' })
  })

  it('fromQuestion이 같이 있으면 질문이 이긴다 — 요청을 읽지 않는다', async () => {
    await mountPage({ fromQuestion: '3', fromRequest: '7' })
    expect(apiCalls('/api/requests/7')).toHaveLength(0)
  })

  it('fromTask가 같이 있어도 요청을 읽지 않는다', async () => {
    await mountPage({ fromTask: '42', fromRequest: '7' })
    expect(apiCalls('/api/requests/7')).toHaveLength(0)
  })
})
