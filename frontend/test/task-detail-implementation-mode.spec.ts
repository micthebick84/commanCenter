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

const implementing = (minutesAgo: number, timeout: number | null) => ({
  ...baseTask,
  status: 'IMPLEMENTING',
  statusLabel: '구현중',
  implementation: null,
  implementationMode: 'SDD',
  implementationAttempt: 2,
  claimedAt: new Date(Date.now() - minutesAgo * 60000 - 5000).toISOString(),
  implementationTimeoutMinutes: timeout,
})

describe('pages/tasks/[id] — 구현 방식·경과 시간 (스펙 2026-10-15 §7)', () => {
  beforeEach(() => { authStub.isAdmin = true })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('데스크톱: 구현중이면 경과/상한과 방식 칩(회차 포함)을 보여 준다', async () => {
    useApiMock.mockResolvedValue(implementing(10, 240))
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="impl-elapsed"]').text()).toBe('10분 경과 / 상한 240분')
    expect(w.find('[data-test="impl-elapsed"]').classes()).not.toContain('impl-elapsed--warn')
    expect(w.find('[data-test="impl-mode-chip"]').text()).toContain('구현 방식: SDD · 2회째')
    w.unmount()
  })

  it('상한의 80%를 넘으면 경고 표시', async () => {
    useApiMock.mockResolvedValue(implementing(200, 240))
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="impl-elapsed"]').classes()).toContain('impl-elapsed--warn')
    w.unmount()
  })

  it('확정 전에는 방식 칩이 없고, 구현중이 아니면 경과도 없다', async () => {
    // 방식·시작 시각·상한이 모두 있는 작업이 확정 전 상태일 때 — 상태 확인을 지우면 둘 다 생겨 실패한다
    useApiMock.mockResolvedValue({ ...implementing(10, 240), status: 'INTERVIEW_REVIEW', statusLabel: '플랜승인대기' })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.text()).toContain('플랜승인대기') // 화면이 그려졌다는 확인(스피너가 아님)
    expect(w.find('[data-test="impl-mode-chip"]').exists()).toBe(false)
    expect(w.find('[data-test="impl-elapsed"]').exists()).toBe(false)
    w.unmount()
  })

  it('확정 뒤라도 구현중이 아니면(PR생성) 경과 줄은 없고 방식 칩만 남는다', async () => {
    useApiMock.mockResolvedValue({ ...implementing(10, 240), status: 'PR_CREATED', statusLabel: 'PR생성' })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="impl-mode-chip"]').text()).toContain('구현 방식: SDD · 2회째') // 틱이 지난 뒤의 화면이라는 확인
    expect(w.find('[data-test="impl-elapsed"]').exists()).toBe(false)
    w.unmount()
  })

  it('모바일: 정보 섹션에 구현 방식 줄', async () => {
    useApiMock.mockResolvedValue({ ...baseTask, implementationMode: 'REVIEW', implementationAttempt: 1 })
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="section-info"] .q-item').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="info-impl-mode"]').text()).toContain('리뷰')
    w.unmount()
  })

  it('모바일: 구현중이면 헤더에 경과/상한이, 정보 섹션에 회차가 붙은 방식이 보인다', async () => {
    useApiMock.mockResolvedValue(implementing(10, 240))
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="impl-elapsed"]').text()).toBe('10분 경과 / 상한 240분')
    await w.find('[data-test="section-info"] .q-item').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="info-impl-mode"]').text()).toContain('SDD · 2회째')
    w.unmount()
  })

  it('모바일: 확정 전에는 구현 방식 줄이 없다', async () => {
    useApiMock.mockResolvedValue({ ...implementing(10, 240), status: 'INTERVIEW_REVIEW', statusLabel: '플랜승인대기' })
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="section-info"] .q-item').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="section-info"]').text()).toContain('모델') // 정보 섹션이 열렸다는 확인
    expect(w.find('[data-test="info-impl-mode"]').exists()).toBe(false)
    w.unmount()
  })
})
