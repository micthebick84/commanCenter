import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const failed = {
  ...baseTask, status: 'IMPLEMENTATION_FAILED', statusLabel: '구현실패', implementation: null,
  failureReason: 'claude exec 실패: claude timeout after PT4H — 지금까지 작업(커밋 2개)은 브랜치 `netismaker/task-42-x`에 올려 둠',
  implementationMode: 'SDD', implementationAttempt: 1,
}

describe('pages/tasks/[id] — 구현 재시도 (스펙 2026-10-15 §5.5·§7)', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('관리자: 다음 할 일 "구현 재시도" → 방식 다시 고르기 → 재시도 API', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(failed)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()

    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-test="impl-retry-dialog"]')).not.toBeNull()
    ;(document.body.querySelector('[data-test="impl-mode-REVIEW"]') as HTMLElement).click()
    await flushPromises()
    useApiMock.mockClear()
    useApiMock.mockResolvedValue(failed)
    ;(document.body.querySelector('[data-test="impl-retry-submit"]') as HTMLElement).click()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', {
      method: 'POST',
      body: { implementationMode: 'REVIEW' },
    })
    w.unmount()
  })

  it('요청자: 문구만 있고 재시도 버튼은 없다', async () => {
    authStub.isAdmin = false
    useApiMock.mockResolvedValue(failed)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="next-action-text"]').text()).toContain('관리자 확인이 필요합니다')
    expect(w.find('[data-test="next-action-primary"]').exists()).toBe(false)
    w.unmount()
  })
})
