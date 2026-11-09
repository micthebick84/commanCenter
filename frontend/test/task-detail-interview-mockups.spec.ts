import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const summary = (id: number, n: number, title: string, over: Record<string, unknown> = {}) => ({
  id, n, name: `m-${id}`, version: 1, title, description: null, turnSeq: 1, turnKind: 'question', ...over,
})
const MOCKUPS = [summary(10, 5, 'B안'), summary(11, 4, '버린 것'), summary(12, 3, 'A안', { version: 2 })]

function stubApi(task: Record<string, unknown>, mockups: unknown = MOCKUPS) {
  useApiMock.mockImplementation(async (url: string) => {
    if (url === '/api/tasks/42') return task
    if (url === '/api/interviews/7/mockups') return mockups
    if (/\/(history|interviews|fix-requests|db-changes)$/.test(url)) return []
    return null
  })
}
const withMockups = { ...baseTask, interviewSessionId: 7, implementationMockupIds: [12, 10] }
const mockupCalls = () => useApiMock.mock.calls.map((c) => c[0]).filter((u: string) => u.includes('/mockups'))

describe('pages/tasks/[id] — 인터뷰 목업 구현 기준 절', () => {
  beforeEach(() => { authStub.isAdmin = true })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('넘긴 목업만 n순으로, 새 탭 링크', async () => {
    stubApi(withMockups)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const section = w.find('[data-test="task-interview-mockups"]')
    expect(section.exists()).toBe(true)
    expect(section.text()).toContain('인터뷰 목업 — 구현 기준')
    const rows = section.findAll('[data-test^="task-interview-mockup-"]')
    expect(rows.map((r) => r.attributes('data-test'))).toEqual(['task-interview-mockup-12', 'task-interview-mockup-10'])
    expect(rows[0].text()).toContain('목업 3 · A안 (v2)')
    expect(rows[1].text()).toContain('목업 5 · B안')
    expect(section.text()).not.toContain('버린 것')
    const link = rows[1].find('a')
    expect(link.text()).toBe('새 탭에서 보기')
    expect(link.attributes('href')).toBe('/interviews/7/mockups/10')
    expect(link.attributes('target')).toBe('_blank')
    w.unmount()
  })

  it('1280px·390px 모두 보인다', async () => {
    for (const width of [1280, 390]) {
      await setViewportWidth(width)
      stubApi(withMockups)
      const w = mount(PageWrapper, mountOpts)
      await flushPromises()
      expect(w.find('[data-test="task-interview-mockups"]').exists(), `${width}px`).toBe(true)
      expect(w.findAll('[data-test^="task-interview-mockup-"]')).toHaveLength(2)
      w.unmount()
      document.body.innerHTML = ''
      useApiMock.mockReset()
    }
  })

  it('ids가 비거나 세션이 없으면 조회도 없다', async () => {
    for (const task of [
      { ...baseTask, interviewSessionId: 7, implementationMockupIds: [] },
      { ...baseTask, interviewSessionId: null, implementationMockupIds: [12] },
      { ...baseTask, interviewSessionId: 7 },
    ]) {
      stubApi(task)
      const w = mount(PageWrapper, mountOpts)
      await flushPromises()
      expect(mockupCalls()).toEqual([])
      expect(w.find('[data-test="task-interview-mockups"]').exists()).toBe(false)
      w.unmount()
      document.body.innerHTML = ''
      useApiMock.mockReset()
    }
  })

  it('응답이 배열이 아니면 그리지 않는다', async () => {
    useApiMock.mockResolvedValue(withMockups)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="task-interview-mockups"]').exists()).toBe(false)
    w.unmount()
  })
})
