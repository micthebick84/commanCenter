import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import MockupPage from '../pages/interviews/[sid]/mockups/[id].vue'
import { useApiMock } from './mocks/nuxt'
import { MOCKUP_CSP } from '../composables/interviewMockups'

// 인터뷰 목업 새 탭(/interviews/{sid}/mockups/{id}) — 스펙 2026-11-03 인터뷰 목업 §6 ②. design-large-view.spec.ts 하네스.
const navigateToMock = vi.fn()
let currentId = '12'
Object.assign(globalThis, { navigateTo: navigateToMock })
;(globalThis as any).useRoute = () => ({ params: { sid: '7', id: currentId }, query: {} })

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(MockupPage) }),
      })
  },
})

function summary(id: number, n: number) {
  return { id, n, name: `m${n}`, version: 1, title: `t${n}`, description: null, turnSeq: 1, turnKind: 'question' }
}
// 4개: id 10~13 ↔ n 1~4
const LIST = [summary(10, 1), summary(11, 2), summary(12, 3), summary(13, 4)]
const iso = new Date(2026, 10, 3, 9, 5).toISOString()
function single(id = 12, n = 3) {
  return { id, sessionId: 7, taskId: 42, n, name: 'a', version: 2, title: 'A안', html: '<p>A</p>', createdAt: iso }
}

async function mountPage(one: unknown, all: unknown = LIST) {
  useApiMock.mockResolvedValueOnce(one).mockResolvedValueOnce(all)
  const w = mount(PageWrapper, { attachTo: document.body })
  await flushPromises()
  return w
}

beforeEach(() => {
  navigateToMock.mockReset()
  currentId = '12'
})

describe('인터뷰 목업 새 탭 (스펙 2026-11-03 인터뷰 목업 §6 ②)', () => {
  it('iframe은 allow-scripts만, srcdoc에 CSP', async () => {
    const w = await mountPage(single())
    const f = w.find('[data-test="mockup-frame"]')
    expect(f.attributes('sandbox')).toBe('allow-scripts')
    expect(f.attributes('srcdoc')).toContain(MOCKUP_CSP)
    expect(f.attributes('srcdoc')).toContain('<p>A</p>')
    w.unmount()
  })

  it('윗줄: 인터뷰로·제목·부제·격리 안내', async () => {
    const w = await mountPage(single())
    expect(w.find('[data-test="mockup-back"]').attributes('href')).toBe('/tasks/42')
    expect(w.find('[data-test="mockup-title"]').text()).toBe('목업 3 · A안 (v2)')
    const sub = w.find('[data-test="mockup-subtitle"]').text()
    expect(sub.startsWith('작업 #42 인터뷰 · 4개 중 3번째 · ')).toBe(true)
    expect(sub).toMatch(/\d{2}:\d{2}$/)
    expect(w.find('[data-test="mockup-isolation-note"]').text()).toBe(
      '에이전트가 만든 화면입니다. 격리된 영역에서 보여 주며, 이 화면은 Symphony의 로그인 정보나 데이터에 접근할 수 없습니다.',
    )
    w.unmount()
  })

  it('처음이면 이전, 끝이면 다음이 비활성', async () => {
    currentId = '10'
    let w = await mountPage(single(10, 1))
    expect(w.find('[data-test="mockup-prev"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="mockup-next"]').attributes('disabled')).toBeUndefined()
    w.unmount()
    currentId = '13'
    w = await mountPage(single(13, 4))
    expect(w.find('[data-test="mockup-next"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="mockup-prev"]').attributes('disabled')).toBeUndefined()
    w.unmount()
  })

  it('다음은 replace로 다음 n의 목업', async () => {
    const w = await mountPage(single())
    await w.find('[data-test="mockup-next"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/interviews/7/mockups/13', { replace: true })
    await w.find('[data-test="mockup-prev"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/interviews/7/mockups/11', { replace: true })
    w.unmount()
  })

  it('로딩 중엔 윗줄만, 실패면 안내', async () => {
    useApiMock.mockReturnValue(new Promise(() => {}))
    let w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="mockup-title"]').text()).toBe('인터뷰 목업')
    expect(w.find('[data-test="mockup-frame"]').exists()).toBe(false)
    expect(w.find('[data-test="mockup-error"]').exists()).toBe(false)
    w.unmount()

    useApiMock.mockReset()
    useApiMock.mockRejectedValue({ statusCode: 404 })
    w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="mockup-error"]').text()).toBe('목업을 불러오지 못했습니다')
    expect(w.find('[data-test="mockup-frame"]').exists()).toBe(false)
    w.unmount()
  })
})
