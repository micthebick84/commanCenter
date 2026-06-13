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

describe('InterviewPanel — register flow', () => {
  it('enables 작업 등록 only at PLAN_READY and emits registered with taskId', async () => {
    useApiMock.mockResolvedValueOnce({ taskId: 123 })
    const w = mountPanel(9)
    const es = FakeEventSource.last()

    // Before plan_ready: button is disabled.
    es.emit('design', { key: 'a', title: 'A', body: 'b', approved: true })
    await flushPromises()
    let reg = w.find('[data-test="register"]')
    expect(reg.attributes('disabled')).toBeDefined()

    es.emit('plan_ready', { designMarkdown: 'd', planMarkdown: 'p', planJson: JSON.stringify([]) })
    await flushPromises()
    reg = w.find('[data-test="register"]')
    expect(reg.attributes('disabled')).toBeUndefined()

    await reg.trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/9/register', { method: 'POST' })
    expect(w.emitted('registered')?.[0]).toEqual([123])
    w.unmount()
  })
})

describe('InterviewPanel — terminal states', () => {
  it('shows an expired banner from the bare EXPIRED status', async () => {
    const w = mountPanel(9)
    // Canonical wire payload: bare English enum name.
    FakeEventSource.last().emit('status', 'EXPIRED')
    await flushPromises()
    expect(w.text()).toContain('만료')
    w.unmount()
  })

  it('renders the Korean badge label (not the raw English enum) for AWAITING_INPUT', async () => {
    const w = mountPanel(9)
    FakeEventSource.last().emit('status', 'AWAITING_INPUT')
    await flushPromises()
    const txt = w.text()
    expect(txt).toContain('입력 대기') // Korean label, display-only
    expect(txt).not.toContain('AWAITING_INPUT') // raw enum name must not surface
    w.unmount()
  })

  it('shows a failed banner from the bare FAILED status', async () => {
    const w = mountPanel(9)
    FakeEventSource.last().emit('status', 'FAILED')
    await flushPromises()
    expect(w.text()).toContain('인터뷰 실패')
    w.unmount()
  })
})
