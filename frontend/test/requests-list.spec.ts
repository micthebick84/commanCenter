import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import RequestsIndex from '../pages/requests/index.vue'
import { authStub, useApiMock } from './mocks/nuxt'

const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(RequestsIndex) }),
      })
  },
})

function item(over: Record<string, unknown> = {}) {
  return {
    id: 1,
    kind: 'FEATURE',
    kindLabel: '기능 추가',
    title: '다크 모드 지원',
    status: 'RECEIVED',
    statusLabel: '접수',
    requesterId: 'hong',
    unread: false,
    createdAt: '2026-10-19T01:00:00Z',
    updatedAt: '2026-10-19T02:00:00Z',
    ...over,
  }
}

async function mountPage(rows: any[]) {
  useApiMock.mockResolvedValueOnce(rows) // onMounted load()
  const w = mount(PageWrapper, { attachTo: document.body })
  await flushPromises()
  return w
}

beforeEach(() => navigateToMock.mockReset())
afterEach(() => {
  document.querySelectorAll('.q-dialog, .q-menu').forEach((n) => n.remove())
})

describe('requests/index', () => {
  it('user_lists_own: 내 요청을 /api/requests로 읽고 unread 항목에만 점을 단다', async () => {
    const w = await mountPage([
      item({ id: 1, title: '다크 모드 지원', unread: true }),
      item({ id: 2, title: '슬랙 MCP 추가', kind: 'MCP', kindLabel: 'MCP 추가', unread: false }),
    ])
    expect(useApiMock).toHaveBeenCalledWith('/api/requests')
    expect(w.text()).toContain('다크 모드 지원')
    expect(w.text()).toContain('슬랙 MCP 추가')
    expect(w.findAll('[data-test="unread-dot"]')).toHaveLength(1)
    // 사용자에게는 요청자 열과 관리자 필터가 없다
    expect(w.text()).not.toContain('안 읽은 건 포함')
    w.unmount()
  })

  it('admin_default_query: 관리자 기본 조회는 all=true + 열린 상태 + includeUnread', async () => {
    authStub.isAdmin = true
    const w = await mountPage([item()])
    const url = String(useApiMock.mock.calls[0][0])
    expect(url.startsWith('/api/requests?')).toBe(true)
    expect(url).toContain('all=true')
    expect(url).toContain('status=RECEIVED')
    expect(url).toContain('status=REVIEWING')
    expect(url).toContain('includeUnread=true')
    expect(w.text()).toContain('hong')
    w.unmount()
  })

  it('줄을 누르면 상세로 이동하고, REPO 열린 줄은 repo-add-slot 자리를 둔다', async () => {
    authStub.isAdmin = true
    const w = await mountPage([
      item({ id: 7, kind: 'REPO', kindLabel: 'REPO 추가', title: '레포 추가해 주세요' }),
      item({ id: 8, kind: 'REPO', kindLabel: 'REPO 추가', status: 'DONE', statusLabel: '처리완료' }),
      item({ id: 9, kind: 'FEATURE' }),
    ])
    expect(w.findAll('[data-test="repo-add-slot"]')).toHaveLength(1)
    await w.find('[data-test="req-row-7"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/requests/7')
    w.unmount()
  })

  it('repo-add: 드롭다운으로 레포를 등록하면 목록을 다시 읽는다', async () => {
    authStub.isAdmin = true
    const w = await mountPage([item({ id: 7, kind: 'REPO', kindLabel: 'REPO 추가', title: '레포 추가해 주세요' })])
    useApiMock.mockResolvedValueOnce([
      { id: 5, name: 'p', pathWithNamespace: 'g/p', defaultBranch: 'main', description: null, registered: false },
    ]) // repo-candidates
    await w.find('[data-test="repo-add-btn"]').trigger('click')
    await flushPromises()
    // 드롭다운 클릭이 줄 이동으로 번지지 않는다
    expect(navigateToMock).not.toHaveBeenCalled()

    useApiMock.mockResolvedValueOnce({ id: 7, repoAlias: 'p' }) // add-repo
    useApiMock.mockResolvedValueOnce([item({ id: 7, kind: 'REPO', status: 'DONE', statusLabel: '처리완료' })]) // 목록 재조회
    ;(document.querySelector('[data-test="cand-5"]') as HTMLElement).click()
    await flushPromises()
    const urls = useApiMock.mock.calls.map((c) => String(c[0]))
    expect(urls[urls.length - 1]).toContain('/api/requests?')
    expect(w.findAll('[data-test="repo-add-slot"]')).toHaveLength(0)
    expect(navigateToMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('create_posts_repo_hint_only_for_repo: FEATURE는 repoHint 없이, REPO는 입력값과 함께 보낸다', async () => {
    const w = await mountPage([])
    async function create(kind: 'FEATURE' | 'REPO', hint: string | null) {
      await w.find('[data-test="open-create"]').trigger('click')
      await flushPromises()
      // 종류 선택은 토글 버튼 그룹: 라벨이 같은 버튼을 누른다
      const label = kind === 'FEATURE' ? '기능 추가' : 'REPO 추가'
      const kindBtn = Array.from(document.querySelectorAll('[data-test="req-kind"] button')).find((b) =>
        b.textContent?.includes(label),
      ) as HTMLElement
      kindBtn.click()
      await flushPromises()
      const title = document.querySelector('[data-test="req-title"]') as HTMLInputElement
      title.value = '제목'
      title.dispatchEvent(new Event('input'))
      const content = document.querySelector('[data-test="req-content"]') as HTMLTextAreaElement
      content.value = '내용'
      content.dispatchEvent(new Event('input'))
      await flushPromises()
      if (hint !== null) {
        const h = document.querySelector('[data-test="req-hint"]') as HTMLInputElement
        h.value = hint
        h.dispatchEvent(new Event('input'))
        await flushPromises()
      } else {
        expect(document.querySelector('[data-test="req-hint"]')).toBeNull()
      }
      useApiMock.mockResolvedValueOnce({ id: 5 }) // POST
      useApiMock.mockResolvedValueOnce([]) // reload
      ;(document.querySelector('[data-test="req-submit"]') as HTMLElement).click()
      await flushPromises()
    }

    await create('FEATURE', null)
    const featCall = useApiMock.mock.calls.find((c) => c[0] === '/api/requests' && c[1]?.method === 'POST')!
    expect(featCall[1].body.kind).toBe('FEATURE')
    expect(featCall[1].body.repoHint ?? null).toBeNull()

    useApiMock.mockClear()
    await create('REPO', 'https://gitlab.hamon.vip/g/p')
    const repoCall = useApiMock.mock.calls.find((c) => c[0] === '/api/requests' && c[1]?.method === 'POST')!
    expect(repoCall[1].body.kind).toBe('REPO')
    expect(repoCall[1].body.repoHint).toBe('https://gitlab.hamon.vip/g/p')
    w.unmount()
  })
})
