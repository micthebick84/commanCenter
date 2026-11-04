import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, type App } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

// 원래 작업에서 이어 등록 (스펙 2026-11-03 §6.5) — /tasks?fromTask={id}로 들어오면 마운트 뒤 그 작업을 읽어 등록 창을 열고
// 같은 레포·브랜치, 제목 `[작업 {id} 후속] …`, 설명 첫 줄 `원래 작업: #{id}`로 채운다. 하네스는 tasks-form-from-question.spec.ts와 같다.

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

const CATALOG = [
  { id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'acme/netis', defaultBranch: 'develop' },
  { id: 4, alias: '지원 안 함', host: 'other', ownerRepo: null, defaultBranch: null },
]
const BRANCHES = {
  repo: 'acme/netis',
  defaultBranch: 'develop',
  branches: [{ name: 'develop', sha: 'a' }, { name: 'main', sha: 'b' }],
  fetchedAt: '2026-11-03T00:00:00Z',
}
const ORIGINAL = {
  id: 42,
  title: '대상장비 탭 개선',
  status: 'DEPLOYED',
  githubBranch: 'main',
  repoCatalogId: 1,
}

interface PageVm {
  draft: { repoCatalogId: number | null; githubBranch: string; title: string; description: string; refRepos: unknown[] }
  showCreate: boolean
  source: { id: number; title: string } | null
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

function stubApi(task: () => Promise<unknown> = () => Promise.resolve({ ...ORIGINAL }), branches: () => Promise<unknown> = () => Promise.resolve(BRANCHES)) {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/tasks/42') return task()
    if (url.startsWith('/api/questions/')) return Promise.reject({ data: { message: '질문 없음' } })
    if (url === '/api/repo-catalog') return Promise.resolve(CATALOG)
    if (url === '/api/repos/branches') return branches()
    if (url === '/api/tasks') return Promise.resolve({ content: [], totalElements: 0, totalPages: 0 })
    return Promise.resolve(null)
  })
}

async function mountPage(query: Record<string, string>, task?: () => Promise<unknown>, branches?: () => Promise<unknown>) {
  routeQuery = query
  stubApi(task, branches)
  const w = mount(PageWrapper, { attachTo: document.body, global: { plugins: [recordNotify] } })
  current = w
  await flushPromises()
  return w
}

const vmOf = (w: ReturnType<typeof mount>) => w.findComponent(TasksIndex).vm as unknown as PageVm
const apiCalls = (url: string) => useApiMock.mock.calls.filter((c) => c[0] === url)

describe('작업 등록 창 — 원래 작업에서 이어서 (스펙 2026-11-03 §6.5)', () => {
  it('fromTask가 없으면 원래 작업을 읽지 않고 창도 열지 않는다', async () => {
    const w = await mountPage({})
    expect(apiCalls('/api/tasks/42')).toHaveLength(0)
    expect(vmOf(w).showCreate).toBe(false)
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('fromTask가 양의 정수가 아니면 읽지 않고 쿼리만 뺀다', async () => {
    for (const fromTask of ['abc', '0', '-3', '2.5', '']) {
      navigateToMock.mockReset()
      const w = await mountPage({ fromTask })
      expect(useApiMock.mock.calls.some((c) => /^\/api\/tasks\/\d/.test(String(c[0])))).toBe(false)
      expect(vmOf(w).showCreate).toBe(false)
      expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
      current?.unmount()
      current = null
    }
  })

  it('종료 전 작업: 같은 레포·브랜치, 제목 접두사, 설명 첫 줄에 병합 전 안내, 출처 질문 없음, 쿼리를 뺀다', async () => {
    const w = await mountPage({ fromTask: '42' })
    const vm = vmOf(w)
    expect(apiCalls('/api/tasks/42')).toHaveLength(1)
    expect(vm.showCreate).toBe(true)
    expect(document.querySelector('[data-test="repo-select"]')).not.toBeNull()
    expect(vm.draft.repoCatalogId).toBe(1)
    expect(vm.draft.githubBranch).toBe('main') // 카탈로그 기본(develop)이 아니라 원래 작업의 브랜치
    expect(vm.draft.title).toBe('[작업 42 후속] 대상장비 탭 개선')
    expect(vm.draft.description.split('\n')[0]).toBe('원래 작업: #42 (원래 작업의 변경은 아직 병합 전)')
    expect(vm.source).toBeNull()
    expect(vm.draft.refRepos).toEqual([])
    expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
    expect(notifySpy).not.toHaveBeenCalled()
  })

  it('작업종료된 작업: 설명 첫 줄은 원래 작업만', async () => {
    const w = await mountPage({ fromTask: '42' }, () => Promise.resolve({ ...ORIGINAL, status: 'CLOSED' }))
    expect(vmOf(w).draft.description.split('\n')[0]).toBe('원래 작업: #42')
  })

  it('원래 작업의 브랜치를 못 쓰면 비우고 알린다', async () => {
    const w = await mountPage({ fromTask: '42' }, () => Promise.resolve({ ...ORIGINAL, githubBranch: 'release/9' }))
    expect(vmOf(w).draft.githubBranch).toBe('')
    expect(notifySpy).toHaveBeenCalledWith({
      type: 'warning',
      message: '원래 작업의 브랜치 release/9를 찾을 수 없습니다 — 브랜치를 골라 주세요',
    })
  })

  it.each([
    { name: 'repoCatalogId 없음', repoCatalogId: null },
    { name: '카탈로그에 없음', repoCatalogId: 9 },
    { name: '지원하지 않는 호스트', repoCatalogId: 4 },
  ])('대상을 비우고 알린다 — $name', async ({ repoCatalogId }) => {
    const w = await mountPage({ fromTask: '42' }, () => Promise.resolve({ ...ORIGINAL, repoCatalogId }))
    const vm = vmOf(w)
    expect(vm.showCreate).toBe(true)
    expect(vm.draft.repoCatalogId).toBeNull()
    expect(apiCalls('/api/repos/branches')).toHaveLength(0)
    expect(vm.draft.title).toBe('[작업 42 후속] 대상장비 탭 개선')
    expect(notifySpy).toHaveBeenCalledWith({
      type: 'warning',
      message: '원래 작업의 대상 레포를 카탈로그에서 찾을 수 없습니다 — 레포를 골라 주세요',
    })
  })

  it('원래 작업을 못 읽으면 창을 열지 않고 알린다', async () => {
    const w = await mountPage({ fromTask: '42' }, () => Promise.reject({ statusCode: 403, data: { message: '권한이 없습니다' } }))
    expect(vmOf(w).showCreate).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message: '권한이 없습니다' })
    expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
  })

  it('질문에서 넘어온 경우(fromQuestion)에는 원래 작업을 읽지 않는다', async () => {
    await mountPage({ fromQuestion: '3', fromTask: '42' })
    expect(apiCalls('/api/tasks/42')).toHaveLength(0)
  })
})
