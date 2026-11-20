import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QBtnToggle, QLayout, QPageContainer, QSelect } from 'quasar'
import RequestsIndex from '../pages/requests/index.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

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

  it('admin_clear_status_chips: 상태 칩을 모두 끄면 네 상태를 모두 보내 UI(필터 없음)와 맞춘다', async () => {
    authStub.isAdmin = true
    const w = await mountPage([item()])
    useApiMock.mockResolvedValueOnce([item()]) // 재조회
    w.findAllComponents(QSelect)[1].vm.$emit('update:modelValue', [])
    await flushPromises()
    const url = String(useApiMock.mock.calls[useApiMock.mock.calls.length - 1][0])
    for (const s of ['RECEIVED', 'REVIEWING', 'DONE', 'REJECTED']) expect(url).toContain(`status=${s}`)
    w.unmount()
  })

  it('시간 열 머리글은 "최근 활동"이다(updatedAt은 활동 시각)', async () => {
    authStub.isAdmin = true
    const w = await mountPage([item()])
    expect(w.text()).toContain('최근 활동')
    expect(w.text()).not.toMatch(/(^|\s)수정(\s|$)/)
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

  it('제목은 상세로 가는 링크라 키보드로도 연다 (2026-11-11 검토)', async () => {
    const w = await mountPage([item({ id: 7, title: '레포 추가해 주세요' })])
    const link = w.find('[data-test="req-link-7"]')
    expect(link.element.tagName).toBe('A')
    expect(link.attributes('href')).toBe('/requests/7')
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

  it('create_posts_repo_hint_only_for_repo: 개발 요청 폼에는 힌트 칸이 없고, 사이트 추가는 입력값과 함께 보낸다', async () => {
    const w = await mountPage([])
    async function create(kind: 'REPO', hint: string) {
      await w.find('[data-test="open-create"]').trigger('click')
      await flushPromises()
      // 종류 선택은 토글 버튼 그룹: 라벨이 같은 버튼을 누른다
      const label = kind === 'REPO' ? '사이트 추가' : ''
      const kindBtn = Array.from(document.querySelectorAll('[data-test="request-kind"] button')).find((b) =>
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
      const h = document.querySelector('[data-test="req-hint"]') as HTMLInputElement
      h.value = hint
      h.dispatchEvent(new Event('input'))
      await flushPromises()
      useApiMock.mockResolvedValueOnce({ id: 5 }) // POST
      useApiMock.mockResolvedValueOnce([]) // reload
      ;(document.querySelector('[data-test="req-submit"]') as HTMLElement).click()
      await flushPromises()
    }

    // 새 요청의 기본 종류는 개발 요청 — 레포 힌트 칸이 없다
    await w.find('[data-test="open-create"]').trigger('click')
    await flushPromises()
    expect(document.querySelector('[data-test="req-hint"]')).toBeNull()
    ;(document.querySelector('[data-test="form-close"]') as HTMLElement | null)?.click()
    useApiMock.mockClear()
    await create('REPO', 'https://gitlab.hamon.vip/g/p')
    const repoCall = useApiMock.mock.calls.find((c) => c[0] === '/api/requests' && c[1]?.method === 'POST')!
    expect(repoCall[1].body.kind).toBe('REPO')
    expect(repoCall[1].body.repoHint).toBe('https://gitlab.hamon.vip/g/p')
    w.unmount()
  })
})

function issueSelect(w: ReturnType<typeof mount>) {
  return w
    .findAllComponents(QSelect)
    .find((c) => c.element.matches('[data-test="requests-issue-type-filter"]') || c.element.querySelector('[data-test="requests-issue-type-filter"]'))!
}

function devItem(over: Record<string, unknown> = {}) {
  return item({
    id: 20,
    kind: 'DEV',
    kindLabel: '개발 요청',
    title: '로그인 오류',
    issueType: 'BUG',
    issueTypeLabel: '버그',
    priority: 'HIGH',
    priorityLabel: '▲ 높음',
    dueDate: '2026-12-03',
    targetRepoAlias: 'hm-core',
    attachmentCount: 2,
    commentCount: 3,
    taskId: 5,
    taskStatusLabel: '구현중',
    ...over,
  })
}

describe('requests/index — 전체 개발 요청', () => {
  it('user_scope_segment: 일반 사용자에게 세그먼트가 보이고 전체 개발 요청을 고르면 scope=dev로 조회한다', async () => {
    const w = await mountPage([item()])
    expect(w.find('[data-test="requests-scope"]').exists()).toBe(true)
    useApiMock.mockResolvedValueOnce([devItem()])
    w.findComponent(QBtnToggle).vm.$emit('update:modelValue', 'dev')
    await flushPromises()
    const url = String(useApiMock.mock.calls[useApiMock.mock.calls.length - 1][0])
    expect(url).toBe('/api/requests?scope=dev')
    expect(w.text()).toContain('로그인 오류')
    w.unmount()
  })

  it('user_dev_filters: 유형을 고르면 서버로 보내고 상태가 비어 있으면 생략한다', async () => {
    const w = await mountPage([item()])
    useApiMock.mockResolvedValueOnce([devItem()])
    w.findComponent(QBtnToggle).vm.$emit('update:modelValue', 'dev')
    await flushPromises()
    useApiMock.mockResolvedValueOnce([devItem()])
    issueSelect(w).vm.$emit('update:modelValue', 'BUG')
    await flushPromises()
    const url = String(useApiMock.mock.calls[useApiMock.mock.calls.length - 1][0])
    expect(url).toContain('scope=dev')
    expect(url).toContain('issueType=BUG')
    expect(url).not.toContain('status=')
    w.unmount()
  })

  it('admin_no_scope: 관리자에게는 세그먼트가 없고 유형 필터는 화면에서 거른다(백엔드가 issueType을 안 읽는다)', async () => {
    authStub.isAdmin = true
    const w = await mountPage([
      devItem({ id: 1, title: '버그글' }),
      devItem({ id: 2, title: '개발글', issueType: 'DEV', issueTypeLabel: '개발요청' }),
    ])
    expect(w.find('[data-test="requests-scope"]').exists()).toBe(false)
    expect(w.text()).toContain('개발글')
    issueSelect(w).vm.$emit('update:modelValue', 'BUG')
    await flushPromises()
    expect(w.text()).toContain('버그글')
    expect(w.text()).not.toContain('개발글')
    expect(useApiMock.mock.calls.every((c) => !String(c[0]).includes('issueType'))).toBe(true)
    w.unmount()
  })

  it('dev_row_meta: DEV 줄에 유형 칩·우선순위·둘째 줄·작업 연결이 보이고 REPO 줄에는 둘째 줄이 없다', async () => {
    const w = await mountPage([
      devItem(),
      item({ id: 30, kind: 'REPO', kindLabel: '사이트 추가', title: '레포 요청' }),
    ])
    const rows = w.findAll('[data-test="request-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('버그')
    expect(rows[0].text()).toContain('▲ 높음')
    expect(rows[0].text()).toContain('hm-core')
    expect(rows[0].text()).toContain('hong')
    expect(rows[0].text()).toContain('마감 12.03')
    expect(rows[0].text()).toContain('📎2')
    expect(rows[0].text()).toContain('💬3')
    expect(rows[0].find('[data-test="request-row-task"]').text()).toBe('→ 작업 #5 구현중')
    expect(rows[1].find('[data-test="request-row-meta"]').exists()).toBe(false)
    expect(rows[1].find('[data-test="request-row-task"]').exists()).toBe(false)
    w.unmount()
  })
})

describe('requests/index — 좁은 화면', () => {
  it('mobile_dev_meta: 카드에도 유형 칩·둘째 줄(줄바꿈)·작업 연결이 보인다', async () => {
    await setViewportWidth(500)
    const w = await mountPage([devItem()])
    const row = w.find('[data-test="request-row"]')
    expect(row.text()).toContain('버그')
    expect(row.find('[data-test="request-row-meta"]').classes()).toContain('wrap')
    expect(row.find('[data-test="request-row-task"]').text()).toBe('→ 작업 #5 구현중')
    w.unmount()
    await setViewportWidth(1024)
  })
})
