import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import DesignLargeViewPage from '../pages/designs/[id].vue'
import { useApiMock } from './mocks/nuxt'

// 디자인 목업 크게 보기(/designs/{id}?screen=N) — 스펙 2026-10-31 §4-5.
// 라우터 없이 마운트하므로 useRoute는 모듈 단계에서 덮고(쿼리는 케이스마다 바꾼다), navigateTo는 스파이로 둔다.
const navigateToMock = vi.fn()
let currentQuery: Record<string, string> = {}
Object.assign(globalThis, { navigateTo: navigateToMock })
;(globalThis as any).useRoute = () => ({ params: { id: '12' }, query: currentQuery })

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(DesignLargeViewPage) }),
      })
  },
})

// 카드 spec(DesignReviewCard.spec.ts)과 같은 모양의 목업 두 개
const TWO = JSON.stringify([
  { path: 'screens/a.html', title: '목록', html: '<p>A</p>' },
  { path: 'screens/b.html', title: '상세', html: '<p>B</p>' },
])

function design(mockupFilesJson = TWO) {
  return {
    designMarkdown: '# 설계',
    mockupFilesJson,
    designProjectId: null,
    designUrl: null,
    rejectCount: 0,
    feedbackHistoryJson: '[]',
    approved: false,
    approvedBy: null,
    approvedAt: null,
    completedAt: '',
  }
}

function task(d: ReturnType<typeof design> | null = design()) {
  return { id: 12, title: '회원 목록 개선', design: d }
}

async function mountPage(body: unknown) {
  useApiMock.mockResolvedValueOnce(body) // onMounted 한 번
  const w = mount(PageWrapper, { attachTo: document.body })
  await flushPromises()
  return w
}

beforeEach(() => {
  navigateToMock.mockReset()
  currentQuery = {}
})

describe('designs/[id] — 크게 보기', () => {
  it('opens the screen from ?screen=2 in a sandboxed frame', async () => {
    currentQuery = { screen: '2' }
    const w = await mountPage(task())
    expect(useApiMock).toHaveBeenCalledTimes(1)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/12')
    const f = w.find('[data-test="design-frame"]')
    expect(f.attributes('srcdoc')).toBe('<p>B</p>')
    // 목업 HTML이 앱 출처(토큰이 든 localStorage)로 돌지 않게 — allow-same-origin 등 다른 토큰 없이 정확히 이 값
    expect(f.attributes('sandbox')).toBe('allow-scripts')
    expect(w.text()).toContain('#12 회원 목록 개선 · 디자인')
    expect(w.find('.q-tab--active').text()).toContain('상세')
    expect(w.find('[data-test="design-back"]').attributes('href')).toBe('/tasks/12')
    expect((globalThis as any).useHead).toHaveBeenCalledWith({ title: '#12 디자인 — Symphony' })
    expect(navigateToMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it.each(['0', '-1', 'abc', '99', '1.5', undefined])('falls back to the first screen for screen=%s', async (screen) => {
    currentQuery = screen === undefined ? {} : { screen }
    const w = await mountPage(task())
    expect(w.find('[data-test="design-frame"]').attributes('srcdoc')).toBe('<p>A</p>')
    expect(w.find('.q-tab--active').text()).toContain('목록')
    w.unmount()
  })

  it('switching a tab replaces the screen query', async () => {
    const w = await mountPage(task())
    await w.findAll('.q-tab')[1]!.trigger('click')
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ query: { screen: '2' } }, { replace: true })
    expect(w.find('[data-test="design-frame"]').attributes('srcdoc')).toBe('<p>B</p>')
    w.unmount()
  })

  it.each([
    ['no design', null],
    ['unreadable mockup JSON', design('not json')],
    ['no mockups', design('[]')],
  ])('shows the empty notice and the back link when the task has %s', async (_case, d) => {
    const w = await mountPage(task(d))
    expect(w.find('[data-test="design-empty"]').text()).toBe('이 작업에는 아직 디자인 목업이 없습니다')
    expect(w.find('[data-test="design-back"]').attributes('href')).toBe('/tasks/12')
    expect(w.find('[data-test="design-frame"]').exists()).toBe(false)
    expect(w.find('[data-test="design-error"]').exists()).toBe(false)
    w.unmount()
  })

  it('shows the server message on 403/404', async () => {
    useApiMock.mockRejectedValueOnce({ statusCode: 403, data: { message: '권한이 없습니다' } })
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="design-error"]').text()).toBe('권한이 없습니다')
    expect(w.find('[data-test="design-back"]').attributes('href')).toBe('/tasks/12')
    expect(w.find('[data-test="design-frame"]').exists()).toBe(false)
    expect(w.find('[data-test="design-empty"]').exists()).toBe(false)
    w.unmount()
  })

  it('falls back to a generic message when the rejection has none', async () => {
    useApiMock.mockRejectedValueOnce({ statusCode: 404 })
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="design-error"]').text()).toBe('작업을 불러오지 못했습니다')
    expect(w.find('[data-test="design-back"]').attributes('href')).toBe('/tasks/12')
    w.unmount()
  })

  it('falls back to a generic message when the server message is an empty string', async () => {
    useApiMock.mockRejectedValueOnce({ statusCode: 403, data: { message: '' } })
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="design-error"]').text()).toBe('작업을 불러오지 못했습니다')
    expect(w.find('[data-test="design-empty"]').exists()).toBe(false) // 빈 메시지가 "목업 없음"으로 잘못 빠지지 않는다
    expect(w.find('[data-test="design-back"]').attributes('href')).toBe('/tasks/12')
    w.unmount()
  })

  it('renders only the bar while loading — the same markup the server renders', async () => {
    useApiMock.mockReturnValueOnce(new Promise(() => {})) // 응답 전
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.text()).toContain('#12 · 디자인')
    expect(w.find('[data-test="design-back"]').attributes('href')).toBe('/tasks/12')
    expect(w.find('[data-test="design-frame"]').exists()).toBe(false)
    expect(w.find('[data-test="design-empty"]').exists()).toBe(false)
    expect(w.find('[data-test="design-error"]').exists()).toBe(false)
    w.unmount()
  })
})
