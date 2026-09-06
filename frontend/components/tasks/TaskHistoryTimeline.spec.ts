import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskHistoryTimeline, { formatKoDateTime } from './TaskHistoryTimeline.vue'
import { useApiMock } from '../../test/mocks/nuxt'

const rows = [
  { fromStatus: 'IMPLEMENTING', toStatus: 'PR_CREATED', fromLabel: '구현중', toLabel: 'PR생성', actorType: 'system', actorId: 'mac-worker-1', reason: 'Draft PR 생성', at: '2026-08-24T05:02:00Z' },
  { fromStatus: null, toStatus: 'AWAITING_APPROVAL', fromLabel: null, toLabel: '승인대기', actorType: 'user', actorId: 'admin', reason: '작업 등록', at: '2026-07-28T07:09:00Z' },
]

describe('TaskHistoryTimeline (스펙 2026-09-06 §6)', () => {
  it('이력을 최신순으로 그리고 loaded(count)를 emit한다', async () => {
    useApiMock.mockResolvedValueOnce(rows)
    const w = mount(TaskHistoryTimeline, { props: { taskId: 18 } })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/18/history')
    const entries = w.findAll('[data-test="history-entry"]')
    expect(entries).toHaveLength(2)
    expect(entries[0]!.text()).toContain('구현중 → PR생성')
    expect(entries[0]!.text()).toContain('Draft PR 생성')
    expect(entries[1]!.text()).toContain('승인대기') // from 없음 → to만
    expect(w.emitted('loaded')?.[0]).toEqual([2])
    w.unmount()
  })
  it('조회 실패는 안내 문구', async () => {
    useApiMock.mockRejectedValueOnce({ statusCode: 500 })
    const w = mount(TaskHistoryTimeline, { props: { taskId: 18 } })
    await flushPromises()
    expect(w.text()).toContain('이력을 불러오지 못했습니다')
    w.unmount()
  })
  it('formatKoDateTime은 "M월 D일 HH:mm"(24시간, 로컬 시각)', () => {
    const d = new Date(2026, 7, 24, 14, 2)
    expect(formatKoDateTime(d.toISOString())).toBe('8월 24일 14:02')
  })
})
