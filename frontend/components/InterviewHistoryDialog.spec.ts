import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import InterviewHistoryDialog from './InterviewHistoryDialog.vue'
import { FakeEventSource } from '../test/mocks/eventsource'
import { useApiMock } from '../test/mocks/nuxt'

// GET /api/interviews/{id} 응답 픽스처 — InterviewResponse 계약 미러
const detail = {
  id: 42,
  title: '결제 위젯 추가',
  status: '취소됨',
  statusName: 'CANCELLED',
  currentPhase: 'brainstorming',
  turns: [
    { seq: 1, role: 'assistant', kind: 'question', content: '어떤 화면에 붙일까요?' },
    { seq: 2, role: 'user', kind: 'answer', content: '대시보드에요' },
    { seq: 3, role: 'system', kind: 'note', content: '사용자 취소' },
  ],
  plan: { designMarkdown: '# 설계 문서', planMarkdown: '# 구현 플랜', planJson: '[]' },
}

async function mountDialog(d: unknown = detail) {
  useApiMock.mockResolvedValueOnce(d)
  const w = mount(InterviewHistoryDialog, {
    props: { modelValue: true, sessionId: 42 },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}

afterEach(() => {
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

describe('InterviewHistoryDialog', () => {
  it('열릴 때 REST 1회 조회로 대화·플랜을 렌더한다', async () => {
    const w = await mountDialog()

    expect(useApiMock).toHaveBeenCalledTimes(1)
    expect(useApiMock).toHaveBeenCalledWith('/api/interviews/42')

    // q-dialog는 body로 teleport — document.body에서 조회
    expect(document.body.querySelectorAll('[data-test="history-turn"]')).toHaveLength(3)
    expect(document.body.textContent).toContain('어떤 화면에 붙일까요?')
    expect(document.body.textContent).toContain('대시보드에요')
    expect(document.body.textContent).toContain('# 설계 문서')
    expect(document.body.textContent).toContain('# 구현 플랜')
    expect(document.body.textContent).toContain('#42')
    expect(document.body.textContent).toContain('결제 위젯 추가')
    w.unmount()
  })

  it('SSE(EventSource)를 절대 열지 않는다 — REST 전용', async () => {
    const w = await mountDialog()
    expect(FakeEventSource.instances).toHaveLength(0)
    w.unmount()
  })

  it('상태 배너를 한글 라벨로 표시한다', async () => {
    const w = await mountDialog()
    const banner = document.body.querySelector('[data-test="history-banner"]')
    expect(banner).toBeTruthy()
    expect(banner!.textContent).toContain('취소됨')
    w.unmount()
  })

  it('plan이 없으면 플랜 섹션을 렌더하지 않는다', async () => {
    const w = await mountDialog({ ...detail, plan: null })
    expect(document.body.textContent).not.toContain('구현 플랜')
    expect(document.body.querySelectorAll('[data-test="history-turn"]')).toHaveLength(3)
    w.unmount()
  })
})
