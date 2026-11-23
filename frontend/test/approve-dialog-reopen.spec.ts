import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import ApproveDialog from '../components/ApproveDialog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { baseTask } from './fixtures/task'

const task = { ...baseTask, id: 1, status: 'AWAITING_APPROVAL', statusLabel: '승인대기', implementation: null, analysis: null }

describe('ApproveDialog 승인 뒤 다시 열기', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('승인 성공 뒤 같은 페이지에서 다시 열면 persistent가 꺼지고 닫기 버튼이 살아 있다', async () => {
    await setViewportWidth(390)
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(url === '/api/usage/claude' ? { limits: [] } : url.endsWith('/approve') ? {} : []),
    )
    const w = mount(ApproveDialog, { props: { task, modelValue: true }, attachTo: document.body })
    await flushPromises()
    await (w.vm as any).approve()
    await flushPromises()
    expect(w.emitted('approved')).toBeTruthy()
    await w.setProps({ modelValue: false })
    await flushPromises()
    await w.setProps({ modelValue: true })
    await flushPromises()
    expect(w.findComponent({ name: 'QDialog' }).props('persistent')).toBe(false)
    const close = document.body.querySelector('[data-test="approve-close"]') as HTMLButtonElement
    expect(close).not.toBeNull()
    expect(close.disabled).toBe(false)
    expect(close.getAttribute('aria-disabled')).not.toBe('true')
    w.unmount()
  })
})
