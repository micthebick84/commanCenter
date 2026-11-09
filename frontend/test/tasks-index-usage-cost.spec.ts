import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QSelect } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

Object.assign(globalThis, { navigateTo: vi.fn() })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }) }),
})

const summaryCalls = () => useApiMock.mock.calls.map((c) => String(c[0])).filter((u) => u.startsWith('/api/usage/summary'))

function stubApi() {
  useApiMock.mockImplementation((url: string) =>
    url.startsWith('/api/tasks')
      ? Promise.resolve({ content: [], totalElements: 0, totalPages: 0 })
      : Promise.resolve(null),
  )
}

describe('pages/tasks — 사용량 칩', () => {
  beforeEach(() => { authStub.isAdmin = true; stubApi() })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('관리자 1280px: q-space 뒤 칩, 첫 QSelect는 상태 select 그대로', async () => {
    await setViewportWidth(1280)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    const chip = w.find('[data-test="cost-chip"]')
    expect(chip.exists()).toBe(true)
    const space = w.find('.q-space').element
    expect(space.compareDocumentPosition(chip.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const sel = w.findComponent(QSelect)
    expect(sel.props('options')).toEqual(expect.arrayContaining([expect.objectContaining({ label: '승인대기' })]))
    expect(summaryCalls()).toEqual(['/api/usage/summary?period=month'])
    w.unmount()
  })

  it('관리자가 아니면 칩도 요약 호출도 없다', async () => {
    authStub.isAdmin = false
    await setViewportWidth(1280)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="cost-chip"]').exists()).toBe(false)
    expect(summaryCalls()).toEqual([])
    w.unmount()
  })

  it('관리자 390px: 칩이 summary-strip 위', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    const chip = w.find('[data-test="cost-chip"]').element
    const strip = w.find('[data-test="summary-strip"]').element
    expect(chip.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    w.unmount()
  })
})
