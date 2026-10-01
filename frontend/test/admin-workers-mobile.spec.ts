import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import Workers from '../pages/admin/workers.vue'
import AdminCatalogCard from '../components/AdminCatalogCard.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// 관리 > 워커 헬스: lt.md에서는 q-table 대신 읽기 전용 카드(AdminCatalogCard, 토글·액션 없음).
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

const rows = [
  {
    workerId: 'win-worker-1', hostname: 'DESKTOP-01', version: '1.2.0', lastSeenAt: new Date().toISOString(),
    alive: true, claudeSessionOk: true, vpnStatus: 'OK', mcps: ['obsidian', 'jira'], lostReportCount: 0,
  },
  {
    workerId: 'mac-worker-2', hostname: null, version: null, lastSeenAt: null,
    alive: false, claudeSessionOk: null, vpnStatus: null, mcps: [], lostReportCount: 3,
  },
]

describe('관리 워커 헬스 모바일 카드', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('lt.md에서는 표 대신 카드로 상태·마지막 응답·호스트·OAuth·MCP·유실 보고를 보여 준다', async () => {
    await setViewportWidth(390)
    const w = await mountPage(rows)
    expect(w.find('.q-table').exists()).toBe(false)
    const cards = w.findAll('[data-test="catalog-card"]')
    expect(cards).toHaveLength(2)
    const [ok, dead] = cards
    expect(ok.text()).toContain('win-worker-1')
    expect(ok.find('[data-test="card-alive"]').text()).toContain('정상')
    expect(ok.text()).toContain('DESKTOP-01')
    expect(ok.text()).toContain('1.2.0')
    expect(ok.find('[data-test="card-mcps"]').text()).toContain('obsidian')
    expect(dead.find('[data-test="card-alive"]').text()).toContain('응답 없음')
    expect(dead.find('[data-test="card-lost"]').text()).toBe('3')
    expect(dead.find('[data-test="card-lost"]').classes()).toContain('text-red-9')
    expect(dead.find('[data-test="card-mcps"]').exists()).toBe(false)
    // 읽기 전용: 토글·비활성 표식·액션 바 없음
    expect(w.find('[data-test="card-toggle"]').exists()).toBe(false)
    expect(w.find('[data-test="card-off"]').exists()).toBe(false)
    expect(w.find('.card-actions').exists()).toBe(false)
    w.unmount()
  })

  it('lt.md 하단 안내는 접힌 한 줄 요약으로 시작한다', async () => {
    await setViewportWidth(390)
    const w = await mountPage(rows)
    expect(w.find('[data-test="info-banner-toggle"]').text()).toContain('alive 기준')
    expect(w.find('[data-test="info-banner-body"]').exists()).toBe(false)
    w.unmount()
  })

  it('워커가 없으면 기존 안내 배너(실행 방법)를 그대로 보여 준다', async () => {
    await setViewportWidth(390)
    const w = await mountPage([])
    expect(w.find('[data-test="catalog-cards"]').exists()).toBe(false)
    expect(w.text()).toContain('등록된 워커가 없습니다')
    w.unmount()
  })

  it('데스크톱(1024)에서는 기존 표를 유지한다', async () => {
    const w = await mountPage(rows)
    expect(w.find('.q-table').exists()).toBe(true)
    expect(w.find('[data-test="catalog-cards"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('AdminCatalogCard 선택 prop', () => {
  it('enabled를 생략하면 토글·비활성 표식이 없고(Boolean 캐스팅으로 false가 되지 않음), actions 슬롯이 없으면 액션 바도 없다', () => {
    const w = mount(AdminCatalogCard, { props: { title: '읽기 전용' } })
    expect(w.find('[data-test="card-toggle"]').exists()).toBe(false)
    expect(w.find('[data-test="card-off"]').exists()).toBe(false)
    expect(w.classes()).not.toContain('catalog-card--off')
    expect(w.find('.card-actions').exists()).toBe(false)
  })

  it('enabled=false면 토글과 비활성 표식이 함께 나온다', () => {
    const w = mount(AdminCatalogCard, {
      props: { title: '꺼짐', enabled: false },
      slots: { actions: '<button>수정</button>' },
    })
    expect(w.find('[data-test="card-toggle"]').exists()).toBe(true)
    expect(w.find('[data-test="card-off"]').exists()).toBe(true)
    expect(w.find('.card-actions').exists()).toBe(true)
  })
})
