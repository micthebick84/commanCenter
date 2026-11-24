import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { deployDbChipLabels } from '../composables/taskDbChips'
import { useApiMock, authStub } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'

// 작업 상세의 "배포 DB" 칩 — 배포 때 고른 DB 연결 (스펙 2026-11-24 deploy-db-connection-select)

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = {
  attachTo: document.body,
  global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } },
}
const item = (id: number, name: string, dbType = 'MYSQL') => ({
  id, repoCatalogId: 9, scope: 'REPO', name, dbType, host: 'h', port: 3306, databaseName: 'app', username: 'ro',
  enabled: true, mine: false, allowWrite: false,
})

afterEach(async () => {
  await setViewportWidth(1024)
  document.body.innerHTML = ''
})

async function mountAt(width: number, deployDbSelection: { slot: string; connectionId: number | null }[]) {
  authStub.isAdmin = true
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/tasks/42') {
      return Promise.resolve({
        ...baseTask,
        status: 'DEPLOYED',
        statusLabel: '배포완료',
        repoCatalogId: 9,
        deployDbSelection,
        deployment: { deployUrl: 'https://task-42.example.dev', deployHostPort: 19001, deployImage: 'img:1', deployedAt: '2026-11-24T00:00:00Z', deployLog: null },
      })
    }
    if (url.startsWith('/api/db-connections?repoCatalogId=')) return Promise.resolve({ enabled: true, items: [item(3, '운영 미러'), item(4, '개발')] })
    return Promise.resolve([])
  })
  await setViewportWidth(width)
  const w = mount(PageWrapper, mountOpts)
  await flushPromises()
  await flushPromises()
  return w
}

async function openDeploy(w: ReturnType<typeof mount>) {
  const header = w.find('[data-test="section-deploy"] .q-item')
  if (header.attributes('aria-expanded') !== 'true') await header.trigger('click')
  await flushPromises()
}

describe('deployDbChipLabels', () => {
  const items = [{ id: 3, name: '운영 미러', dbType: 'MYSQL' as const }]
  it('null 항목은 빼고, 아는 id는 이름 · 종류, 모르는 id는 DB #id', () => {
    const labels = deployDbChipLabels(
      [{ slot: 'main', connectionId: 3 }, { slot: 'sub', connectionId: null }, { slot: 'x', connectionId: 99 }],
      items,
    )
    expect(labels).toHaveLength(2)
    expect(labels[0]).toMatch(/^운영 미러 · /)
    expect(labels[1]).toBe('DB #99')
  })
  it('빈 선택은 빈 목록', () => {
    expect(deployDbChipLabels([], items)).toEqual([])
  })
})

describe('작업 상세 — 배포 DB 칩', () => {
  it('1280px: 고른 연결이 있으면 "배포 DB: 이름 · 종류" 칩', async () => {
    const w = await mountAt(1280, [{ slot: 'main', connectionId: 3 }])
    const chips = w.findAll('[data-test="task-deploy-db-chip"]')
    expect(chips).toHaveLength(1)
    expect(chips[0].text()).toContain('배포 DB: 운영 미러 · ')
    w.unmount()
  })

  it('390px: 모바일 배포 아코디언에도 칩', async () => {
    const w = await mountAt(390, [{ slot: 'main', connectionId: 3 }])
    await openDeploy(w)
    const chips = w.findAll('[data-test="task-deploy-db-chip"]')
    expect(chips).toHaveLength(1)
    expect(chips[0].text()).toContain('배포 DB: 운영 미러 · ')
    w.unmount()
  })

  it('고른 연결이 없으면(선택 비었거나 connectionId null) 아무것도 안 그린다', async () => {
    for (const sel of [[], [{ slot: 'main', connectionId: null }]]) {
      const d = await mountAt(1280, sel)
      expect(d.find('[data-test="task-deploy-db-chip"]').exists()).toBe(false)
      d.unmount()
      document.body.innerHTML = ''
      const m = await mountAt(390, sel)
      await openDeploy(m)
      expect(m.find('[data-test="task-deploy-db-chip"]').exists()).toBe(false)
      m.unmount()
      document.body.innerHTML = ''
    }
  })
})
