import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import InterviewPanel from '../components/InterviewPanel.vue'
import { authStub, useApiMock } from './mocks/nuxt'

const waiting = {
  statusName: 'AWAITING_INPUT',
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '범위는?' }],
  plan: null,
}

async function mountPanel(props: Record<string, unknown> = {}) {
  authStub.accessToken = 'jwt'
  useApiMock.mockResolvedValueOnce(waiting) // onMounted의 GET /{id}가 소비
  const w = mount(InterviewPanel, { props: { sessionId: 9, ...props } })
  await flushPromises()
  return w
}

describe('InterviewPanel — 처리함용 훅 (answered·draft)', () => {
  it('작업_인터뷰_답변이_성공하면_answered를_한_번_보낸다', async () => {
    const w = await mountPanel()
    await w.find('textarea').setValue('대시보드만 추가합니다')
    useApiMock.mockResolvedValueOnce({})
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(w.emitted('answered')?.length).toBe(1)
    w.unmount()
  })

  it('전송이_409면_answered가_없다', async () => {
    const w = await mountPanel()
    await w.find('textarea').setValue('대시보드만 추가합니다')
    useApiMock.mockRejectedValueOnce({ statusCode: 409 })
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(w.emitted('answered')).toBeUndefined()
    w.unmount()
  })

  it('질문_세션은_answered를_보내지_않는다', async () => {
    const w = await mountPanel({ kind: 'QUESTION' })
    await w.find('textarea').setValue('토큰 검증은요?')
    useApiMock.mockResolvedValueOnce({})
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(useApiMock.mock.calls.some((c) => c[0] === '/api/questions/9/ask')).toBe(true)
    expect(w.emitted('answered')).toBeUndefined()
    w.unmount()
  })

  it('draft를_주면_입력창이_그_값으로_시작하고_입력하면_update:draft', async () => {
    const w = await mountPanel({ draft: '쓰던 글' })
    const ta = w.find('textarea')
    expect((ta.element as HTMLTextAreaElement).value).toBe('쓰던 글')
    await ta.setValue('쓰던 글 이어서')
    expect(w.emitted('update:draft')?.at(-1)).toEqual(['쓰던 글 이어서'])
    // 전송 성공 뒤 입력창이 비워지면 그것도 알린다
    useApiMock.mockResolvedValueOnce({})
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(w.emitted('update:draft')?.at(-1)).toEqual([''])
    w.unmount()
  })

  it('draft를_안_주면_update:draft를_보내지_않는다', async () => {
    const w = await mountPanel()
    await w.find('textarea').setValue('아무 글')
    useApiMock.mockResolvedValueOnce({})
    await w.find('[data-test="send-answer"]').trigger('click')
    await flushPromises()
    expect(w.emitted('update:draft')).toBeUndefined()
    w.unmount()
  })
})
