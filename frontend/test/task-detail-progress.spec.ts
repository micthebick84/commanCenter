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

  it('데스크톱 진행 이력 카드는 펼치기 전에는 조회하지 않고, 첫 펼침 때 조회한다(지연 로딩)', async () => {
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/tasks/42/history') return Promise.resolve([])
      return Promise.resolve(baseTask)
    })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/42/history')
    await w.find('[data-test="history-desktop"] .q-item').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/history')
    w.unmount()
  })
})
