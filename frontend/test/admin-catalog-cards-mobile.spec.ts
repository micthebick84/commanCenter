import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import RepoCatalog from '../pages/admin/repo-catalog.vue'
import McpCatalog from '../pages/admin/mcp-catalog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// 관리 > 레포·MCP 카탈로그: lt.md에서는 q-table 대신 카드 목록(AdminCatalogCard).
function wrap(page: any) {
  return defineComponent({
    setup() {
      return () =>
        h(QLayout, { view: 'hHh lpR fFf' }, {
          default: () => h(QPageContainer, {}, { default: () => h(page) }),
        })
    },
  })
}

async function mountPage(page: any, rows: any[]) {
  useApiMock.mockResolvedValueOnce(rows) // onMounted load()
  const w = mount(wrap(page), { attachTo: document.body })
  await flushPromises()
  return w
}

const repoRows = [
  {
    id: 1, alias: 'Netis7.0', gitUrl: 'https://github.com/o/r.git', host: 'github',
    ownerRepo: 'o/r', defaultBranch: 'main', description: '메인 제품', enabled: true,
    createdBy: 'admin', createdAt: '', updatedAt: '',
  },
  {
    id: 2, alias: 'GL', gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git', host: 'gitlab',
    ownerRepo: 'g/sub/p', defaultBranch: null, description: null, enabled: false,
    createdBy: 'admin', createdAt: '', updatedAt: '',
  },
]

const mcpRows = [
  {
    id: 7, name: 'company-docs', displayName: '사내 문서', url: 'https://mcp.example.com/sse',
    transport: 'sse', description: null, enabled: true, createdBy: 'admin', createdAt: '', updatedAt: '',
    lastCheckAt: null, lastCheckStatus: 'DOWN', lastCheckError: 'connect timeout',
  },
]

const cards = (w: any) => w.findAll('[data-test="catalog-card"]')

describe('관리 카탈로그 모바일 카드 목록', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('레포: lt.md에서는 표 대신 카드로 별칭·경로·브랜치·호스트 배지·비활성 표식을 보여 준다', async () => {
    await setViewportWidth(390)
    const w = await mountPage(RepoCatalog, repoRows)
    expect(w.find('.q-table').exists()).toBe(false)
    const [gh, gl] = cards(w)
    expect(cards(w)).toHaveLength(2)
    expect(gh.text()).toContain('Netis7.0')
    expect(gh.text()).toContain('o/r')
    expect(gh.text()).toContain('main')
    expect(gh.text()).toContain('메인 제품')
    expect(gh.find('[data-test="card-off"]').exists()).toBe(false)
    expect(gl.text()).toContain('gitlab')
    expect(gl.text()).toContain('기본 브랜치 자동 감지')
    expect(gl.find('[data-test="card-off"]').exists()).toBe(true)
    w.unmount()
  })

  it('레포: 카드 토글은 enabled를 뒤집어 PUT하고, 연결 확인은 check를 호출한다', async () => {
    await setViewportWidth(390)
    const w = await mountPage(RepoCatalog, repoRows)
    useApiMock.mockResolvedValueOnce({}) // PUT
    useApiMock.mockResolvedValueOnce(repoRows) // reload
    await cards(w)[1].find('[data-test="card-toggle"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog/2', {
      method: 'PUT',
      body: {
        alias: 'GL', gitUrl: 'https://gitlab.hamon.vip/g/sub/p.git', defaultBranch: null,
        description: null, enabled: true,
      },
    })

    useApiMock.mockResolvedValueOnce({ reachable: true, defaultBranch: 'main', branchCount: 3, error: null })
    await cards(w)[0].find('[data-test="card-check"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/repo-catalog/1/check', { method: 'POST' })
    w.unmount()
  })

  it('레포: 카드의 수정은 값이 채워진 전체화면 다이얼로그를 연다', async () => {
    await setViewportWidth(390)
    const w = await mountPage(RepoCatalog, repoRows)
    await cards(w)[0].find('[data-test="card-edit"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-test="form-topbar"]')?.textContent).toContain('레포 수정')
    expect((document.body.querySelector('[data-test="form-alias"]') as HTMLInputElement).value).toBe('Netis7.0')
    w.unmount()
  })

  it('MCP: 카드에 표시명·mcp__name·transport·헬스 상태와 오류를 보여 준다', async () => {
    await setViewportWidth(390)
    const w = await mountPage(McpCatalog, mcpRows)
    expect(w.find('.q-table').exists()).toBe(false)
    const card = cards(w)[0]
    expect(card.text()).toContain('사내 문서')
    expect(card.text()).toContain('mcp__company-docs')
    expect(card.text()).toContain('sse')
    expect(card.find('[data-test="card-health"]').text()).toContain('DOWN')
    expect(card.find('[data-test="card-health"]').text()).toContain('미확인')
    expect(card.text()).toContain('connect timeout')
    w.unmount()
  })

  it.each([
    { page: RepoCatalog, empty: '등록된 레포가 없습니다' },
    { page: McpCatalog, empty: '등록된 MCP가 없습니다' },
  ])('빈 목록이면 안내 문구($empty)', async ({ page, empty }) => {
    await setViewportWidth(390)
    const w = await mountPage(page, [])
    expect(w.find('[data-test="catalog-cards"]').text()).toContain(empty)
    w.unmount()
  })

  it.each([
    { page: RepoCatalog, summary: '레포 목록', body: '스냅샷으로 박제' },
    { page: McpCatalog, summary: 'MCP 서버 목록', body: '스냅샷으로 박제' },
  ])('lt.md 안내 박스는 한 줄 요약으로 접혀 있다가 탭하면 펼쳐진다 ($summary)', async ({ page, summary, body }) => {
    await setViewportWidth(390)
    const w = await mountPage(page, [])
    const toggle = w.find('[data-test="info-banner-toggle"]')
    expect(toggle.text()).toContain(summary)
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(w.find('[data-test="info-banner-body"]').exists()).toBe(false)
    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(w.find('[data-test="info-banner-body"]').text()).toContain(body)
    w.unmount()
  })

  it('데스크톱 안내 박스는 접기 없이 전문을 보여 준다', async () => {
    const w = await mountPage(RepoCatalog, [])
    expect(w.find('[data-test="info-banner-toggle"]').exists()).toBe(false)
    expect(w.find('[data-test="info-banner"]').text()).toContain('스냅샷으로 박제')
    w.unmount()
  })

  it.each([
    { page: RepoCatalog, rows: repoRows },
    { page: McpCatalog, rows: mcpRows },
  ])('데스크톱(1024)에서는 기존 표를 유지하고 카드는 없다', async ({ page, rows }) => {
    const w = await mountPage(page, rows)
    expect(w.find('.q-table').exists()).toBe(true)
    expect(w.find('[data-test="catalog-cards"]').exists()).toBe(false)
    w.unmount()
  })
})
