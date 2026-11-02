import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import Workers from '../pages/admin/workers.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// 관리 > 워커 헬스 "Claude OAuth" (스펙 2026-11-02 §6): 하트비트의 claude 로그인 판정을 칩·경고로 보여 준다.
const Wrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(Workers) }),
      })
  },
})

async function mountPage(rows: any[]) {
  useApiMock.mockResolvedValueOnce(rows) // useTaskPolling 첫 조회
  const w = mount(Wrapper, { attachTo: document.body })
  await flushPromises()
  return w
}

const DAY = 24 * 60 * 60 * 1000
const iso = (offsetMs: number) => new Date(Date.now() + offsetMs).toISOString()
const base = { hostname: 'h', version: '0.1.0', lastSeenAt: iso(0), alive: true, vpnStatus: null, mcps: [], lostReportCount: 0 }

// 로그인이 죽은 PC(11-02 win-worker-1: CLI가 토큰을 비움, refresh 만료 시각이 지남)
const dead = { ...base, workerId: 'win-worker-1', claudeSessionOk: false, claudeAuthMethod: 'CREDENTIALS', claudeAuthExpiresAt: iso(-2 * DAY) }
const soon = { ...base, workerId: 'interview-worker-1', claudeSessionOk: true, claudeAuthMethod: 'CREDENTIALS', claudeAuthExpiresAt: iso(2 * DAY + 60_000) }
const token = { ...base, workerId: 'win75-worker-1', claudeSessionOk: true, claudeAuthMethod: 'ENV_TOKEN', claudeAuthExpiresAt: null }
const old = { ...base, workerId: 'old-worker', claudeSessionOk: null }

function chipOf(el: ReturnType<ReturnType<typeof mount>['find']>) {
  return el.find('[data-test="claude-auth-chip"]')
}

describe('워커 헬스 Claude OAuth', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('데스크톱 표: 로그인이 죽은 워커는 빨간 칩, 임박은 주황, setup-token은 초록, 판정 없음은 —', async () => {
    const w = await mountPage([dead, soon, token, old])
    const cells = w.findAll('[data-test="cell-claude-auth"]')
    expect(cells).toHaveLength(4)
    const [deadCell, soonCell, tokenCell, oldCell] = cells

    const red = chipOf(deadCell!)
    expect(red.text()).toBe('만료')
    expect(red.classes()).toContain('bg-red-2')
    expect(red.classes()).toContain('text-red-9')
    expect(deadCell!.find('[data-test="claude-auth-caption"]').text()).toMatch(/^CLI 로그인 · \d{2}-\d{2} \d{2}:\d{2} 만료됨$/)

    const orange = chipOf(soonCell!)
    expect(orange.text()).toBe('2일 남음')
    expect(orange.classes()).toContain('bg-orange-2')

    const green = chipOf(tokenCell!)
    expect(green.text()).toBe('OK')
    expect(green.classes()).toContain('bg-green-2')
    expect(tokenCell!.find('[data-test="claude-auth-caption"]').text()).toBe('setup-token')

    expect(chipOf(oldCell!).exists()).toBe(false)
    expect(oldCell!.text()).toBe('—')
    w.unmount()
  })

  it('빨강·주황 워커가 있으면 페이지 위 경고에 빨강 먼저 조치와 함께 보인다', async () => {
    const w = await mountPage([soon, token, dead])
    const alert = w.find('[data-test="claude-auth-alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.classes()).toContain('bg-red-1')
    const rows = alert.findAll('[data-test="claude-auth-alert-row"]')
    expect(rows.map((r) => r.find('strong').text())).toEqual(['win-worker-1', 'interview-worker-1'])
    expect(rows[0]!.text()).toContain('만료')
    expect(rows[0]!.text()).toContain('claude auth login')
    expect(rows[1]!.text()).toContain('2일 남음')
    w.unmount()
  })

  it('임박만 있으면 경고는 주황, 모두 정상이거나 판정이 없으면 경고 없음', async () => {
    const onlySoon = await mountPage([soon, token])
    expect(onlySoon.find('[data-test="claude-auth-alert"]').classes()).toContain('bg-orange-1')
    onlySoon.unmount()
    document.body.innerHTML = ''

    const fine = await mountPage([token, old])
    expect(fine.find('[data-test="claude-auth-alert"]').exists()).toBe(false)
    fine.unmount()
  })

  it('모바일 카드에도 같은 빨간 칩이 보인다', async () => {
    await setViewportWidth(390)
    const w = await mountPage([dead, token])
    const cards = w.findAll('[data-test="catalog-card"]')
    expect(cards).toHaveLength(2)
    const red = chipOf(cards[0]!.find('[data-test="card-claude-auth"]'))
    expect(red.text()).toBe('만료')
    expect(red.classes()).toContain('bg-red-2')
    expect(chipOf(cards[1]!.find('[data-test="card-claude-auth"]')).classes()).toContain('bg-green-2')
    w.unmount()
  })
})
