import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import ClaudeUsagePanel from './ClaudeUsagePanel.vue'
import { useApiMock } from '../test/mocks/nuxt'

const future = new Date(Date.now() + 3 * 3600_000).toISOString()
const limits = [
  {
    limitType: 'seven_day', status: 'allowed_warning', utilization: 0.8, resetsAt: future,
    usingOverage: false, reportedBy: 'iw-1', updatedAt: new Date(Date.now() - 3 * 60_000).toISOString(),
  },
  {
    limitType: 'five_hour', status: 'allowed', utilization: 0.42, resetsAt: future,
    usingOverage: false, reportedBy: 'iw-1', updatedAt: new Date().toISOString(),
  },
]

describe('ClaudeUsagePanel (스펙 2026-09-05 §3)', () => {
  beforeEach(() => {
    useApiMock.mockReset()
  })

  it('panel: 순서(five_hour→seven_day)·퍼센트·임계색·갱신 시각을 그린다', async () => {
    useApiMock.mockResolvedValue({ limits })
    const w = mount(ClaudeUsagePanel)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/usage/claude')
    const rows = w.findAll('[data-test^="usage-row-"]')
    expect(rows.map((r) => r.attributes('data-test'))).toEqual(['usage-row-five_hour', 'usage-row-seven_day'])
    expect(rows[0]!.text()).toContain('현재 세션 (5시간)')
    expect(rows[0]!.text()).toContain('42%')
    expect(rows[0]!.find('.q-linear-progress').classes()).toContain('text-primary')
    expect(rows[1]!.text()).toContain('80%')
    expect(rows[1]!.find('.q-linear-progress').classes()).toContain('text-warning')
    expect(w.text()).toContain('방금 갱신')
    expect(w.text()).toContain('초기화')
    w.unmount()
  })

  it('panel: 수집 전에는 빈 상태 문구', async () => {
    useApiMock.mockResolvedValue({ limits: [] })
    const w = mount(ClaudeUsagePanel)
    await flushPromises()
    expect(w.find('[data-test="usage-empty"]').text()).toContain('아직 수집된 사용량이 없습니다')
    w.unmount()
  })

  it('strip: 세션·이번 주·컨텍스트를 한 줄로, 90% 이상은 negative', async () => {
    useApiMock.mockResolvedValue({ limits })
    const w = mount(ClaudeUsagePanel, { props: { variant: 'strip', contextPct: 93 } })
    await flushPromises()
    const strip = w.find('[data-test="usage-strip"]')
    expect(strip.text()).toContain('세션')
    expect(strip.text()).toContain('42%')
    expect(strip.text()).toContain('이번 주')
    expect(strip.text()).toContain('80%')
    expect(strip.text()).toContain('컨텍스트')
    expect(strip.text()).toContain('93%')
    expect(strip.findAll('.q-linear-progress')[2]!.classes()).toContain('text-negative')
    w.unmount()
  })

  it('strip: 수집 전에는 "사용량 미수집"을 쓰지만, hideWhenEmpty면 줄 자체를 그리지 않는다 (승인 다이얼로그)', async () => {
    useApiMock.mockResolvedValue({ limits: [] })
    const plain = mount(ClaudeUsagePanel, { props: { variant: 'strip' } })
    await flushPromises()
    expect(plain.find('[data-test="usage-strip"]').text()).toContain('사용량 미수집')
    plain.unmount()

    const hidden = mount(ClaudeUsagePanel, { props: { variant: 'strip', hideWhenEmpty: true } })
    await flushPromises()
    expect(hidden.find('[data-test="usage-strip"]').exists()).toBe(false)
    hidden.unmount()
  })

  it('strip: prefix를 주면 맨 앞에 라벨을 붙인다 (승인 다이얼로그 "Claude 사용량")', async () => {
    useApiMock.mockResolvedValue({ limits })
    const w = mount(ClaudeUsagePanel, { props: { variant: 'strip', prefix: 'Claude 사용량' } })
    await flushPromises()
    const strip = w.find('[data-test="usage-strip"]')
    expect(strip.text().startsWith('Claude 사용량')).toBe(true)
    expect(strip.text()).toContain('42%')
    w.unmount()
  })

  it('strip: hideWhenEmpty여도 수집된 값이 있으면 그린다', async () => {
    useApiMock.mockResolvedValue({ limits })
    const w = mount(ClaudeUsagePanel, { props: { variant: 'strip', hideWhenEmpty: true } })
    await flushPromises()
    expect(w.find('[data-test="usage-strip"]').text()).toContain('42%')
    w.unmount()
  })
})
