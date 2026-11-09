import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import UsageFeeSettings from './UsageFeeSettings.vue'
import { useApiMock } from '../../test/mocks/nuxt'

const fees = [
  { id: 1, effectiveFrom: '2026-10-01', monthlyFee: 100, currency: 'USD', createdBy: 'admin', createdAt: '2026-10-02T09:30:00+09:00' },
  { id: 2, effectiveFrom: '2026-11-01', monthlyFee: 200, currency: 'USD', createdBy: 'admin', createdAt: '2026-11-02T10:05:00+09:00' },
]

async function mountSettings() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) =>
    Promise.resolve(url === '/api/usage/subscription-fees' && !opts?.method ? fees : { ok: true }),
  )
  const w = mount(UsageFeeSettings, { props: { defaultFrom: '2026-10-01' }, attachTo: document.body })
  await flushPromises()
  return w
}

function input(w: ReturnType<typeof mount>, test: string) {
  return w.find(`[data-test="${test}"]`)
}
async function type(w: ReturnType<typeof mount>, test: string, value: string) {
  const el = input(w, test)
  expect(el.exists(), test).toBe(true)
  ;(el.element as HTMLInputElement).value = value
  await el.trigger('input')
  await flushPromises()
}

describe('UsageFeeSettings', () => {
  beforeEach(() => useApiMock.mockReset())
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('이력과 기본 시작일', async () => {
    const w = await mountSettings()
    const rows = w.findAll('[data-test="fee-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('2026-11-01') // 최신순
    expect(rows[0].text()).toContain('2026-11-02 10:05') // 문자열 앞 16자 — 시간대 변환 없음
    expect((input(w, 'fee-from').element as HTMLInputElement).value).toBe('2026-10-01')
    w.unmount()
  })

  it('저장 바디', async () => {
    const w = await mountSettings()
    await type(w, 'fee-amount', '200')
    await w.find('[data-test="fee-save"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/usage/subscription-fees', {
      method: 'POST',
      body: { effectiveFrom: '2026-10-01', monthlyFee: 200 },
    })
    // 저장 뒤 이력을 다시 부르고 saved를 낸다
    const gets = useApiMock.mock.calls.filter((c) => c[0] === '/api/usage/subscription-fees' && !c[1])
    expect(gets).toHaveLength(2)
    expect(w.emitted('saved')).toHaveLength(1)
    w.unmount()
  })

  it('금액이 비었거나 0 이하·날짜 형식이 틀리면 저장 막힘', async () => {
    const w = await mountSettings()
    for (const v of ['', '0', '-5']) {
      await type(w, 'fee-amount', v)
      expect(w.find('[data-test="fee-save"]').attributes('disabled'), `금액 '${v}'`).toBeDefined()
    }
    await type(w, 'fee-amount', '200')
    expect(w.find('[data-test="fee-save"]').attributes('disabled')).toBeUndefined()
    await type(w, 'fee-from', '2026-1')
    expect(w.find('[data-test="fee-save"]').attributes('disabled')).toBeDefined()
    await w.find('[data-test="fee-save"]').trigger('click')
    expect(useApiMock.mock.calls.some((c) => c[1]?.method === 'POST')).toBe(false)
    w.unmount()
  })
})
