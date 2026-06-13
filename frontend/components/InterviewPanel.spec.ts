import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import InterviewPanel from './InterviewPanel.vue'
import { FakeEventSource } from '../test/mocks/eventsource'
import { authStub, useApiMock } from '../test/mocks/nuxt'

function mountPanel(sessionId = 5) {
  authStub.accessToken = 'jwt'
  return mount(InterviewPanel, { props: { sessionId } })
}

describe('InterviewPanel — transcript', () => {
  it('renders assistant question turns from the stream', async () => {
    const w = mountPanel()
    FakeEventSource.last().emit('question', { seq: 1, content: '트리거가 뭔가요?' })
    await flushPromises()
    expect(w.text()).toContain('트리거가 뭔가요?')
    w.unmount()
  })
})

describe('InterviewPanel — design sections', () => {
  it('shows a check icon for approved sections', async () => {
    const w = mountPanel()
    FakeEventSource.last().emit('design', {
      key: 'overview',
      title: '개요',
      body: '확정안',
      approved: true,
    })
    await flushPromises()
    expect(w.text()).toContain('개요')
    expect(w.html()).toContain('check_circle')
    w.unmount()
  })
})

describe('InterviewPanel — answer flow', () => {
  it('POSTs the answer with replyToSeq, optimistically appends a user turn, clears input', async () => {
    useApiMock.mockResolvedValueOnce({})
    const w = mountPanel(9)
    FakeEventSource.last().emit('question', { seq: 3, content: '범위는?' })
    await flushPromises()

    await w.find('textarea').setValue('대시보드만 추가합니다')
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/answer', {
      method: 'POST',
      body: { answer: '대시보드만 추가합니다', replyToSeq: 3 },
    })
    expect(w.text()).toContain('대시보드만 추가합니다')
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    w.unmount()
  })

  it('disables send when not AWAITING_INPUT', async () => {
    const w = mountPanel(9)
    // Canonical wire payload: bare English enum name.
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    const btn = w.find('[data-test="send-answer"]')
    expect(btn.attributes('disabled')).toBeDefined()
    w.unmount()
  })
})
