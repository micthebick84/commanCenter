import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(
      QLayout,
      { view: 'hHh lpR fFf' },
      {
        default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }),
      },
    ),
})
// InterviewPanel(SSE)은 스텁 — 여기서는 승인 다이얼로그 ↔ 페이지 배선만 본다.
const mountOpts = {
  attachTo: document.body,
  global: {
    stubs: {
      'router-link': { template: '<a><slot /></a>' },
      DesignReviewCard: true,
      InterviewPanel: true,
    },
  },
}

const awaiting = {
  ...baseTask,
  title: '활동 스트림 스모크',
  status: 'AWAITING_APPROVAL',
  statusLabel: '승인대기',
  implementation: null,
  analysis: null,
}
const interviewing = {
  ...awaiting,
  status: 'INTERVIEWING',
  statusLabel: '인터뷰중',
  interviewSessionId: 99,
}

// happy-dom엔 scrollIntoView가 없어 스파이로 대체한다 — 테스트 뒤 원래 값으로 되돌린다 (리뷰 파인딩 10)
const originalScrollIntoView = Element.prototype.scrollIntoView

describe('pages/tasks/[id] — 승인 다이얼로그 연동 (승인 팝업 UI/UX 개선 2026-09-06)', () => {
  beforeEach(() => {
    authStub.isAdmin = true
  })
  afterEach(() => {
    document.body.innerHTML = ''
    Element.prototype.scrollIntoView = originalScrollIntoView
  })

  it('승인 — 인터뷰 시작을 누르면 다이얼로그가 이 작업의 요약(번호·제목)을 보여준다', async () => {
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/tasks/42') return Promise.resolve(awaiting)
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      return Promise.resolve([])
    })
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w
      .find('[data-test="progress-card"] [data-test="next-action-primary"]')
      .trigger('click')
    await flushPromises()
    const summary = document.body.querySelector('[data-test="approve-summary"]')
    expect(summary).not.toBeNull()
    expect(summary?.textContent).toContain('#42')
    expect(summary?.textContent).toContain('활동 스트림 스모크')
    w.unmount()
  })

  it('승인이 끝나면 작업을 다시 읽고 대화형 분석 카드로 스크롤한다', async () => {
    let approved = false
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/tasks/42/approve') {
        approved = true
        return Promise.resolve({})
      }
      if (url === '/api/tasks/42')
        return Promise.resolve(approved ? interviewing : awaiting)
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      return Promise.resolve([])
    })
    const scrollSpy = vi.fn()
    Element.prototype.scrollIntoView = scrollSpy
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w
      .find('[data-test="progress-card"] [data-test="next-action-primary"]')
      .trigger('click')
    await flushPromises()
    const dialog = w.findComponent({ name: 'ApproveDialog' })
    await (dialog.vm as any).approve()
    await flushPromises()
    await flushPromises()
    expect(document.getElementById('interview-card')).not.toBeNull()
    expect(scrollSpy).toHaveBeenCalled()
    w.unmount()
  })
})
