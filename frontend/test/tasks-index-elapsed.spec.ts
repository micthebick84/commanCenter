import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }) }),
})

// 시작 시각이 minutesAgo분 전인 구현중 작업 (상한 80분)
const implementing = (minutesAgo: number) => ({
  id: 7, githubRepo: 'org/repo', repoAlias: 'Repo', githubBranch: 'main', title: '구현중 작업', description: 'd',
  status: 'IMPLEMENTING', statusLabel: '구현중', requesterId: 'user1', retryCount: 0, maxRetry: 3, failureReason: null,
  designRequested: false, createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z', implementation: null,
  totalCostUsd: null, claimedAt: new Date(Date.now() - minutesAgo * 60000 - 5000).toISOString(), implementationTimeoutMinutes: 80,
})

// Nuxt 자동 임포트 컴포넌트(QueueStatsBar·NuxtLink)는 vitest에서 해석되지 않는다 — 경고 없이 마운트하려고 스텁한다
const mountOpts = {
  global: { stubs: { QueueStatsBar: { template: '<div />' }, NuxtLink: { template: '<a><slot /></a>' } } },
}

function stubApi(task: ReturnType<typeof implementing>) {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/tasks') return Promise.resolve({ content: [task], totalElements: 1, totalPages: 1 })
    if (url === '/api/repo-catalog') return Promise.resolve([])
    return Promise.resolve(null)
  })
}

describe('pages/tasks/index — 구현중 경과 시간 (스펙 2026-10-15 §7)', () => {
  it('보드 카드에 경과/상한을 보여 준다', async () => {
    stubApi(implementing(12))
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="board-elapsed"]').text()).toBe('12분 경과 / 상한 80분')
    expect(w.find('[data-test="board-elapsed"]').classes()).not.toContain('board-elapsed--warn')
    w.unmount()
  })

  it('상한의 80% 이상 지나면 경고 클래스가 붙는다', async () => {
    stubApi(implementing(70))
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="board-elapsed"]').text()).toBe('70분 경과 / 상한 80분')
    expect(w.find('[data-test="board-elapsed"]').classes()).toContain('board-elapsed--warn')
    w.unmount()
  })
})
