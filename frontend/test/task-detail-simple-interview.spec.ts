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

// 간단 인터뷰(superpowers 끔) 작업 — 칩 문구는 소스 상수가 아니라 리터럴로 고정한다(같은 상수끼리 비교는 아무것도 못 지킨다).
const SIMPLE_CHIP_TEXT = '간단 인터뷰'

describe('pages/tasks/[id] — 간단 인터뷰 칩 (설계 2026-11-03 interview-superpowers-toggle §6)', () => {
  beforeEach(() => { authStub.isAdmin = true })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('데스크톱: 끔(false)이면 구현 방식 칩 옆에 칩 "간단 인터뷰"가 있다', async () => {
    useApiMock.mockResolvedValue({ ...baseTask, interviewSuperpowersEnabled: false })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    const chip = w.find('[data-test="simple-interview-chip"]')
    expect(chip.exists()).toBe(true)
    expect(chip.text()).toBe(SIMPLE_CHIP_TEXT)
    // 구현 방식 칩과 같은 머리줄 묶음 안에 있다
    const group = w.find('.conv-header-chips')
    expect(group.find('[data-test="simple-interview-chip"]').exists()).toBe(true)
    expect(group.find('[data-test="impl-mode-chip"]').exists()).toBe(true)
    // 모바일 정보 줄은 데스크톱에 그리지 않는다
    expect(w.find('[data-test="info-simple-interview"]').exists()).toBe(false)
    w.unmount()
  })

  it('데스크톱: 켬(true)·null·칸 없음이면 칩이 없다 — 끔 판정은 === false뿐', async () => {
    const cases: Array<[string, Record<string, unknown>]> = [
      ['true', { interviewSuperpowersEnabled: true }],
      ['null', { interviewSuperpowersEnabled: null }],
      ['칸 없음', {}],
    ]
    for (const [name, extra] of cases) {
      useApiMock.mockResolvedValue({ ...baseTask, ...extra })
      const w = mount(PageWrapper, mountOpts)
      await flushPromises()
      // 머리줄 칩 묶음이 그려졌다는 확인(칩이 없는 이유가 화면 미렌더가 아님)
      expect(w.find('[data-test="impl-mode-chip"]').exists(), `${name}: 머리줄 렌더 확인`).toBe(true)
      expect(w.find('[data-test="simple-interview-chip"]').exists(), `${name}: 칩 없음`).toBe(false)
      w.unmount()
    }
  })

  it('모바일(390px): 정보 섹션을 펴면 "간단 인터뷰" 줄이 있고 머리줄 칩은 없다', async () => {
    useApiMock.mockResolvedValue({ ...baseTask, interviewSuperpowersEnabled: false })
    await setViewportWidth(390)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    // 접힌 정보 섹션에는 아직 줄이 없다 — 펼칠 때만 마운트(q-slide-transition + v-if)
    expect(w.find('[data-test="info-simple-interview"]').exists()).toBe(false)
    await w.find('[data-test="section-info"] .q-item').trigger('click')
    await flushPromises()
    const line = w.find('[data-test="info-simple-interview"]')
    expect(line.exists()).toBe(true)
    expect(line.text()).toContain(SIMPLE_CHIP_TEXT)
    // 모바일은 `$q.screen.lt.md` 분기 그대로 — 데스크톱 머리줄 칩은 그리지 않는다
    expect(w.find('[data-test="simple-interview-chip"]').exists()).toBe(false)
    w.unmount()
  })

  it('모바일(390px): 켬(true)·null·칸 없음이면 정보 섹션에 "간단 인터뷰" 줄이 없다', async () => {
    const cases: Array<[string, Record<string, unknown>]> = [
      ['true', { interviewSuperpowersEnabled: true }],
      ['null', { interviewSuperpowersEnabled: null }],
      ['칸 없음', {}],
    ]
    await setViewportWidth(390)
    for (const [name, extra] of cases) {
      useApiMock.mockResolvedValue({ ...baseTask, ...extra })
      const w = mount(PageWrapper, mountOpts)
      await flushPromises()
      await w.find('[data-test="section-info"] .q-item').trigger('click')
      await flushPromises()
      expect(w.find('[data-test="section-info"]').text(), `${name}: 정보 섹션이 열렸다는 확인`).toContain('모델')
      expect(w.find('[data-test="info-simple-interview"]').exists(), `${name}: 줄 없음`).toBe(false)
      w.unmount()
    }
  })
})
