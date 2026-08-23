import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import InterviewHistoryCard from './InterviewHistoryCard.vue'
import { useApiMock } from '../test/mocks/nuxt'

// 목록 행 픽스처 — 백엔드 InterviewSummaryResponse 계약 미러
// (status=한글 dbValue, statusName=영문 enum name, turns/plan 없음)
const row = (id: number, statusName: string, status: string) => ({
  id,
  status,
  statusName,
  currentPhase: null,
  model: 'claude-opus-5',
  effort: 'high',
  createdAt: '2026-08-20T10:00:00+09:00',
  updatedAt: '2026-08-20T11:00:00+09:00',
})

afterEach(() => {
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

describe('InterviewHistoryCard', () => {
  it('터미널 세션만 행으로 렌더한다 (진행 중 QUEUED 세션 제외)', async () => {
    useApiMock.mockResolvedValueOnce([
      row(12, 'QUEUED', '인터뷰대기'),
      row(11, 'CANCELLED', '취소됨'),
      row(10, 'FAILED', '인터뷰실패'),
    ])
    const w = mount(InterviewHistoryCard, {
      props: { taskId: 7, taskStatus: 'INTERVIEWING' },
      attachTo: document.body,
    })
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/7/interviews')
    expect(w.findAll('[data-test="history-row"]')).toHaveLength(2)
    expect(w.text()).toContain('#11')
    expect(w.text()).toContain('취소됨')
    expect(w.text()).toContain('#10')
    expect(w.text()).not.toContain('#12')
    w.unmount()
  })

  it('전부 비터미널이면 카드 자체를 렌더하지 않는다', async () => {
    useApiMock.mockResolvedValueOnce([row(12, 'QUEUED', '인터뷰대기')])
    const w = mount(InterviewHistoryCard, { props: { taskId: 7 } })
    await flushPromises()
    expect(w.find('[data-test="history-card"]').exists()).toBe(false)
    w.unmount()
  })

  it('빈 배열이면 카드 자체를 렌더하지 않는다', async () => {
    useApiMock.mockResolvedValueOnce([])
    const w = mount(InterviewHistoryCard, { props: { taskId: 7 } })
    await flushPromises()
    expect(w.find('[data-test="history-card"]').exists()).toBe(false)
    w.unmount()
  })

  it('403/404 실패는 조용히 빈 목록으로 처리한다', async () => {
    useApiMock.mockRejectedValueOnce({ statusCode: 403 })
    const w = mount(InterviewHistoryCard, { props: { taskId: 7 } })
    await flushPromises()
    expect(w.find('[data-test="history-card"]').exists()).toBe(false)
    w.unmount()
  })

  it("'대화 보기' 클릭 시 열람 다이얼로그가 열린다", async () => {
    useApiMock.mockResolvedValueOnce([row(11, 'CANCELLED', '취소됨')])
    const w = mount(InterviewHistoryCard, {
      props: { taskId: 7 },
      attachTo: document.body,
    })
    await flushPromises()

    // 다이얼로그가 열리며 1회 조회할 상세 응답
    useApiMock.mockResolvedValueOnce({
      id: 11,
      title: '제목',
      status: '취소됨',
      statusName: 'CANCELLED',
      turns: [],
      plan: null,
    })
    await w.find('[data-test="view-transcript"]').trigger('click')
    await flushPromises()

    expect(document.body.querySelector('[data-test="history-dialog"]')).toBeTruthy()
    w.unmount()
  })

  it('taskStatus가 바뀌면 목록을 다시 불러온다 (취소 직후 즉시 반영)', async () => {
    useApiMock.mockResolvedValueOnce([])
    const w = mount(InterviewHistoryCard, {
      props: { taskId: 7, taskStatus: 'INTERVIEWING' },
    })
    await flushPromises()
    expect(w.find('[data-test="history-card"]').exists()).toBe(false)

    useApiMock.mockResolvedValueOnce([row(11, 'CANCELLED', '취소됨')])
    await w.setProps({ taskStatus: 'AWAITING_APPROVAL' })
    await flushPromises()
    expect(w.find('[data-test="history-card"]').exists()).toBe(true)
    w.unmount()
  })
})
