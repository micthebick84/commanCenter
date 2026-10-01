import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import RepoCatalog from '../pages/admin/repo-catalog.vue'
import McpCatalog from '../pages/admin/mcp-catalog.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// 관리 > 레포·MCP 카탈로그의 등록 다이얼로그(AdminFormDialog) 모바일 레이아웃.
// q-dialog는 body 포털로 렌더된다 — document.body에서 찾는다.
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

async function openCreate(page: any, openSelector: string) {
  useApiMock.mockResolvedValueOnce([]) // onMounted load()
  const w = mount(wrap(page), { attachTo: document.body })
  await flushPromises()
  const btn = w.findAll('button').find((b) => b.text().includes(openSelector))
  await btn!.trigger('click')
  await flushPromises()
  return w
}

const card = () => document.body.querySelector('.q-dialog .q-card') as HTMLElement

describe.each([
  { name: '레포', page: RepoCatalog, open: '새 레포 등록' },
  { name: 'MCP', page: McpCatalog, open: '새 MCP 등록' },
])('관리 $name 등록 다이얼로그', ({ page, open }) => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('lt.md에서는 전체화면 — 상단 바(닫기+제목) → 스크롤 본문 → 액션 바, 고정 min-width 없음', async () => {
    await setViewportWidth(390)
    const w = await openCreate(page, open)
    expect(document.body.querySelector('.q-dialog__inner--maximized')).not.toBeNull()
    expect(card().getAttribute('style') ?? '').not.toMatch(/min-width:\s*\d+px/)
    const children = Array.from(card().children)
    expect(children[0]?.getAttribute('data-test')).toBe('form-topbar')
    expect(children[0]?.textContent).toContain(open)
    expect(children.some((c) => c.classList.contains('dialog-body'))).toBe(true)
    expect(card().lastElementChild?.classList.contains('q-card__actions')).toBe(true)
    // 모바일 입력란은 dense가 아니다(터치 높이)
    expect(document.body.querySelector('.q-dialog .q-field--dense')).toBeNull()
    w.unmount()
  })

  it('lt.md 상단 닫기 버튼으로 닫힌다', async () => {
    await setViewportWidth(390)
    const w = await openCreate(page, open)
    ;(document.body.querySelector('[data-test="form-close"]') as HTMLElement).click()
    await flushPromises()
    await new Promise((r) => setTimeout(r, 400)) // 닫힘 트랜지션
    expect(document.body.querySelector('[data-test="form-topbar"]')).toBeNull()
    w.unmount()
  })

  it('데스크톱(1024)에서는 maximized·상단 바 없이 카드 제목으로 연다', async () => {
    const w = await openCreate(page, open)
    expect(document.body.querySelector('.q-dialog__inner--maximized')).toBeNull()
    expect(document.body.querySelector('[data-test="form-topbar"]')).toBeNull()
    expect(card().querySelector('.text-h6')?.textContent).toBe(open)
    w.unmount()
  })
})

describe('MCP 등록 다이얼로그 — transport 입력', () => {
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('lt.md에서는 드롭다운 대신 세그먼트로 고르고, 고른 값이 POST 바디에 실린다', async () => {
    await setViewportWidth(390)
    const w = await openCreate(McpCatalog, '새 MCP 등록')
    const toggle = document.body.querySelector('[data-test="form-transport-toggle"]') as HTMLElement
    expect(toggle).not.toBeNull()
    expect(document.body.querySelector('.q-dialog .q-select')).toBeNull()

    const fill = (sel: string, v: string) => {
      const el = document.body.querySelector(sel) as HTMLInputElement
      el.value = v
      el.dispatchEvent(new Event('input'))
    }
    fill('[data-test="form-name"]', 'company-docs')
    fill('[data-test="form-display-name"]', '사내 문서')
    fill('[data-test="form-url"]', 'https://mcp.example.com/mcp')
    const http = Array.from(toggle.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'http')!
    http.click()
    await flushPromises()

    useApiMock.mockResolvedValueOnce({}) // POST
    useApiMock.mockResolvedValueOnce([]) // reload
    ;(document.body.querySelector('[data-test="form-submit"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/mcp-catalog', {
      method: 'POST',
      body: {
        name: 'company-docs',
        displayName: '사내 문서',
        url: 'https://mcp.example.com/mcp',
        transport: 'http',
        description: null,
        enabled: true,
      },
    })
    w.unmount()
  })

  it('데스크톱에서는 기존 q-select를 유지한다', async () => {
    const w = await openCreate(McpCatalog, '새 MCP 등록')
    expect(document.body.querySelector('.q-dialog .q-select')).not.toBeNull()
    expect(document.body.querySelector('[data-test="form-transport-toggle"]')).toBeNull()
    w.unmount()
  })
})
