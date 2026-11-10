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

const patch = { name: 'patch-task-7.zip', sizeBytes: 1024, fileCount: 3, empty: false, uploadedAt: null }
const closedTask = (id: number, requesterId: string, close: unknown) => ({
  id, githubRepo: 'o/r', repoAlias: 'R', githubBranch: 'main', title: `작업 ${id}`, description: '',
  status: 'CLOSED', statusLabel: '작업종료', requesterId, retryCount: 0, maxRetry: 3, failureReason: null, designRequested: false,
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-24T00:00:00Z', implementation: null, totalCostUsd: null, close,
})
const closeWith = (p: unknown) => ({ progress: [], mergeCommitSha: null, notePath: null, patchMode: 'REQUIRED', patch: p })

function stubApi(list: unknown[]) {
  useApiMock.mockImplementation((url: string) =>
    url.startsWith('/api/tasks') ? Promise.resolve({ content: list, totalElements: list.length, totalPages: 1 })
      : url === '/api/queue/stats' ? Promise.resolve({ avgDurationMs: null })
        : Promise.resolve(null),
  )
}
function setMe(username: string) {
  ;(authStub as any).me = { user_id: 7, username, email: null, authorities: [] }
}

describe('pages/tasks — 작업종료 카드의 부분 패치 (스펙 2026-11-10)', () => {
  beforeEach(() => { authStub.isAdmin = false; setMe('alice'); navigateToMock.mockReset() })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = ''; ;(authStub as any).me = null })

  it('작업종료 카드에 부분 패치 버튼이 있고 카드 이동 없이 내려받는다', async () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    stubApi([closedTask(7, 'alice', closeWith(patch))])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    const btn = w.find('[data-test="card-patch-download"]')
    expect(btn.exists()).toBe(true)
    expect(btn.text()).toContain('부분 패치')
    useApiMock.mockClear()
    useApiMock.mockResolvedValueOnce({ url: '/api/patch-downloads/tok' })
    await btn.trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/7/patch/download-link', { method: 'POST' })
    expect(assign).toHaveBeenCalledWith('/api/patch-downloads/tok')
    expect(navigateToMock).not.toHaveBeenCalled()
    assign.mockRestore()
    w.unmount()
  })

  it('변경 없음이면 회색 글자만 보인다', async () => {
    stubApi([closedTask(7, 'alice', closeWith({ name: null, sizeBytes: null, fileCount: 0, empty: true, uploadedAt: null }))])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="card-patch-empty"]').text()).toBe('변경 없음')
    expect(w.find('[data-test="card-patch-download"]').exists()).toBe(false)
    w.unmount()
  })

  it('패치가 없는 작업종료 카드에는 아무것도 없다', async () => {
    stubApi([closedTask(7, 'alice', { progress: [], mergeCommitSha: null, notePath: null }), closedTask(8, 'alice', null)])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="card-patch-download"]').exists()).toBe(false)
    expect(w.find('[data-test="card-patch-empty"]').exists()).toBe(false)
    w.unmount()
  })

  it('다른 사람의 작업이면 버튼을 보이지 않고, 관리자에게는 보인다', async () => {
    stubApi([closedTask(7, 'bob', closeWith(patch))])
    let w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="card-patch-download"]').exists()).toBe(false)
    w.unmount()
    document.body.innerHTML = ''

    authStub.isAdmin = true
    w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="card-patch-download"]').exists()).toBe(true)
    w.unmount()
  })

  it('모바일 카드의 버튼도 같은 링크를 요청하고 카드 이동은 없다', async () => {
    await setViewportWidth(390)
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    stubApi([closedTask(7, 'alice', closeWith(patch)), closedTask(8, 'bob', closeWith(patch))])
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    const btns = w.findAll('[data-test="card-patch-download"]')
    expect(btns).toHaveLength(1) // bob의 작업에는 없다
    useApiMock.mockClear()
    useApiMock.mockResolvedValueOnce({ url: '/api/patch-downloads/tok' })
    await btns[0]!.trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/7/patch/download-link', { method: 'POST' })
    expect(navigateToMock).not.toHaveBeenCalled()
    assign.mockRestore()
    w.unmount()
  })
})
