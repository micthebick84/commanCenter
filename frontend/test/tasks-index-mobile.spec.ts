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
