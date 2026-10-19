import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import RequestDetailPage from '../pages/requests/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'

const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })
;(globalThis as any).useRoute = () => ({ params: { id: '3' } })

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(RequestDetailPage) }),
      })
  },
})

function detail(over: Record<string, unknown> = {}) {
  return {
    id: 3,
    kind: 'FEATURE',
    kindLabel: '기능 추가',
    title: '다크 모드 지원',
    content: '본문 **굵게** 입니다',
    repoHint: null,
    status: 'RECEIVED',
    statusLabel: '접수',
    requesterId: 'hong',
    repoCatalogId: null,
    repoAlias: null,
    repoGitUrl: null,
    repoCatalogDeleted: false,
    editable: false,
    createdAt: '2026-10-19T01:00:00Z',
    updatedAt: '2026-10-19T02:00:00Z',
    comments: [],
    ...over,
  }
}

async function mountPage(d: Record<string, unknown>) {
  useApiMock.mockResolvedValueOnce(d) // onMounted load()
  const w = mount(PageWrapper, { attachTo: document.body })
  await flushPromises()
  return w
}

function type(el: HTMLInputElement | HTMLTextAreaElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
}

beforeEach(() => navigateToMock.mockReset())
afterEach(() => {
  document.querySelectorAll('.q-dialog, .q-menu').forEach((n) => n.remove())
})

describe('requests/[id]', () => {
  it('renders_thread_and_system_lines: 답글은 마크다운 말풍선(관리자 칩), 시스템 줄은 회색 한 줄', async () => {
    const w = await mountPage(
      detail({
        comments: [
          { id: 1, kind: 'COMMENT', authorId: 'admin', authorAdmin: true, body: '검토 **시작**합니다', createdAt: '2026-10-19T03:00:00Z' },
          { id: 2, kind: 'SYSTEM', authorId: 'admin', authorAdmin: true, body: '상태가 접수에서 검토중으로 바뀌었습니다', createdAt: '2026-10-19T03:01:00Z' },
          { id: 3, kind: 'COMMENT', authorId: 'hong', authorAdmin: false, body: '감사합니다', createdAt: '2026-10-19T03:02:00Z' },
        ],
      }),
    )
    expect(useApiMock).toHaveBeenCalledWith('/api/requests/3')
    // 본문도 마크다운
    expect(w.find('[data-test="request-content"] strong').text()).toBe('굵게')
    const bubbles = w.findAll('[data-test="comment-bubble"]')
    expect(bubbles).toHaveLength(2)
    expect(bubbles[0]!.find('strong').text()).toBe('시작')
    expect(bubbles[0]!.text()).toContain('관리자')
    expect(bubbles[1]!.text()).not.toContain('관리자')
    const lines = w.findAll('[data-test="system-line"]')
    expect(lines).toHaveLength(1)
    expect(lines[0]!.text()).toContain('상태가 접수에서 검토중으로 바뀌었습니다')
    w.unmount()
  })

  it('헤더는 updatedAt을 "최근 활동"으로 표기하고 "수정"이라 하지 않는다', async () => {
    const w = await mountPage(detail({ createdAt: '2026-10-19T01:00:00Z', updatedAt: '2026-10-19T05:00:00Z' }))
    const activity = w.find('[data-test="request-activity"]')
    expect(activity.exists()).toBe(true)
    expect(activity.text()).toContain('최근 활동')
    expect(activity.text()).not.toContain('수정 ')
    expect(w.text()).not.toContain('(수정 ')
    w.unmount()
  })

  it('404면"요청을 찾을 수 없습니다" 빈 상태를 보여 준다', async () => {
    useApiMock.mockRejectedValueOnce({ status: 404, data: { message: '요청을 찾을 수 없습니다' } })
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('[data-test="request-not-found"]').text()).toContain('요청을 찾을 수 없습니다')
    expect(w.find('[data-test="request-content"]').exists()).toBe(false)
    w.unmount()
  })

  it('reply_posts_and_appends: 전송하면 답글 API를 부르고 목록에 추가한다', async () => {
    const w = await mountPage(detail())
    const input = w.find('[data-test="reply-input"]')
    expect(input.exists()).toBe(true)
    type(input.element as HTMLTextAreaElement, '고마워요')
    await flushPromises()
    useApiMock.mockResolvedValueOnce({
      id: 9, kind: 'COMMENT', authorId: 'hong', authorAdmin: false, body: '고마워요', createdAt: '2026-10-19T04:00:00Z',
    })
    await w.find('[data-test="reply-send"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/requests/3/comments', { method: 'POST', body: { body: '고마워요' } })
    expect(w.findAll('[data-test="comment-bubble"]')).toHaveLength(1)
    expect(w.text()).toContain('고마워요')
    // 입력창은 비워진다
    expect((w.find('[data-test="reply-input"]').element as HTMLTextAreaElement).value).toBe('')
    w.unmount()
  })

  it('빈 답글은 전송 버튼이 비활성이다', async () => {
    const w = await mountPage(detail())
    expect(w.find('[data-test="reply-send"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('editable_shows_edit_and_cancel: editable=false이면 둘 다 없다', async () => {
    let w = await mountPage(detail({ editable: true }))
    expect(w.find('[data-test="req-edit"]').exists()).toBe(true)
    expect(w.find('[data-test="req-cancel"]').exists()).toBe(true)
    w.unmount()

    w = await mountPage(detail({ editable: false }))
    expect(w.find('[data-test="req-edit"]').exists()).toBe(false)
    expect(w.find('[data-test="req-cancel"]').exists()).toBe(false)
    w.unmount()
  })

  it('수정 저장은 응답 Detail로 화면을 교체한다', async () => {
    const w = await mountPage(detail({ editable: true }))
    await w.find('[data-test="req-edit"]').trigger('click')
    await flushPromises()
    const title = document.querySelector('[data-test="req-title"]') as HTMLInputElement
    expect(title.value).toBe('다크 모드 지원')
    type(title, '새 제목')
    await flushPromises()
    useApiMock.mockResolvedValueOnce(detail({ editable: true, title: '새 제목' }))
    ;(document.querySelector('[data-test="req-submit"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/requests/3', expect.objectContaining({ method: 'PUT' }))
    expect(w.find('[data-test="request-title"]').text()).toBe('새 제목')
    w.unmount()
  })

  it('취소는 확인 뒤 DELETE하고 목록으로 이동한다', async () => {
    const w = await mountPage(detail({ editable: true }))
    await w.find('[data-test="req-cancel"]').trigger('click')
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalledWith('/api/requests/3', { method: 'DELETE' })
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/requests/3', { method: 'DELETE' })
    expect(navigateToMock).toHaveBeenCalledWith('/requests')
    w.unmount()
  })

  it('비관리자에게는 상태 메뉴가 없다', async () => {
    const w = await mountPage(detail())
    expect(w.find('[data-test="status-menu"]').exists()).toBe(false)
    w.unmount()
  })

  it('관리자 상태 메뉴는 허용된 전이만 보여 주고, 고르면 POST 후 응답으로 화면을 교체한다', async () => {
    authStub.isAdmin = true
    const w = await mountPage(detail())
    await w.find('[data-test="status-menu"]').trigger('click')
    await flushPromises()
    expect(document.querySelector('[data-test="status-REVIEWING"]')).not.toBeNull()
    expect(document.querySelector('[data-test="status-DONE"]')).not.toBeNull()
    expect(document.querySelector('[data-test="status-REJECTED"]')).not.toBeNull()
    expect(document.querySelector('[data-test="status-RECEIVED"]')).toBeNull()
    useApiMock.mockResolvedValueOnce(detail({ status: 'REVIEWING', statusLabel: '검토중' }))
    ;(document.querySelector('[data-test="status-REVIEWING"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/requests/3/status', { method: 'POST', body: { status: 'REVIEWING' } })
    expect(w.find('[data-test="request-status"]').text()).toContain('검토중')
    w.unmount()
  })

  it('admin_reject_requires_reason: 사유가 비면 확인이 비활성, 입력하면 REJECTED와 사유를 보낸다', async () => {
    authStub.isAdmin = true
    const w = await mountPage(detail())
    await w.find('[data-test="status-menu"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[data-test="status-REJECTED"]') as HTMLElement).click()
    await flushPromises()
    const confirm = () => document.querySelector('[data-test="reject-confirm"]') as HTMLButtonElement
    expect(confirm().disabled).toBe(true)
    const reason = document.querySelector('[data-test="reject-reason"]') as HTMLInputElement
    type(reason, '   ')
    await flushPromises()
    expect(confirm().disabled).toBe(true)
    type(reason, '중복')
    await flushPromises()
    expect(confirm().disabled).toBe(false)
    useApiMock.mockResolvedValueOnce(detail({ status: 'REJECTED', statusLabel: '반려' }))
    confirm().click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/requests/3/status', {
      method: 'POST',
      body: { status: 'REJECTED', reason: '중복' },
    })
    expect(w.find('[data-test="request-status"]').text()).toContain('반려')
    w.unmount()
  })

  it('shows_deleted_catalog_note: 등록된 레포와 카탈로그 삭제 표시', async () => {
    let w = await mountPage(
      detail({
        kind: 'REPO', kindLabel: 'REPO 추가', status: 'DONE', statusLabel: '처리완료',
        repoCatalogId: 5, repoAlias: 'widgets', repoGitUrl: 'https://gitlab.hamon.vip/g/widgets',
      }),
    )
    expect(w.find('[data-test="repo-result"]').text()).toBe('등록된 레포: widgets (https://gitlab.hamon.vip/g/widgets)')
    w.unmount()

    w = await mountPage(
      detail({
        kind: 'REPO', kindLabel: 'REPO 추가', status: 'DONE', statusLabel: '처리완료',
        repoCatalogId: null, repoAlias: null, repoGitUrl: 'https://gitlab.hamon.vip/g/widgets', repoCatalogDeleted: true,
      }),
    )
    expect(w.find('[data-test="repo-result"]').text()).toContain('(카탈로그에서 삭제됨)')
    expect(w.find('[data-test="repo-result"]').text()).toContain('https://gitlab.hamon.vip/g/widgets')
    w.unmount()

    // alias가 있어도 삭제 표시가 켜지면 덧붙인다
    w = await mountPage(
      detail({
        kind: 'REPO', kindLabel: 'REPO 추가', status: 'DONE', statusLabel: '처리완료',
        repoAlias: 'widgets', repoGitUrl: 'https://gitlab.hamon.vip/g/widgets', repoCatalogDeleted: true,
      }),
    )
    expect(w.find('[data-test="repo-result"]').text()).toBe(
      '등록된 레포: widgets (https://gitlab.hamon.vip/g/widgets) (카탈로그에서 삭제됨)',
    )
    w.unmount()
  })

  it('repo-add-slot: 관리자 + REPO + 열린 상태에서만 자리를 둔다', async () => {
    authStub.isAdmin = true
    let w = await mountPage(detail({ kind: 'REPO', kindLabel: 'REPO 추가' }))
    expect(w.find('[data-test="repo-add-slot"]').exists()).toBe(true)
    w.unmount()

    w = await mountPage(detail({ kind: 'REPO', kindLabel: 'REPO 추가', status: 'DONE', statusLabel: '처리완료' }))
    expect(w.find('[data-test="repo-add-slot"]').exists()).toBe(false)
    w.unmount()

    w = await mountPage(detail({ kind: 'FEATURE' }))
    expect(w.find('[data-test="repo-add-slot"]').exists()).toBe(false)
    w.unmount()

    authStub.isAdmin = false
    w = await mountPage(detail({ kind: 'REPO', kindLabel: 'REPO 추가' }))
    expect(w.find('[data-test="repo-add-slot"]').exists()).toBe(false)
    w.unmount()
  })

  it('repo-add: 드롭다운에서 레포를 고르면 응답 상세로 화면이 바뀐다', async () => {
    authStub.isAdmin = true
    const w = await mountPage(detail({ kind: 'REPO', kindLabel: 'REPO 추가' }))
    useApiMock.mockResolvedValueOnce([
      { id: 5, name: 'p', pathWithNamespace: 'g/p', defaultBranch: 'main', description: null, registered: false },
    ]) // repo-candidates
    await w.find('[data-test="repo-add-btn"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/requests/3/repo-candidates')

    useApiMock.mockResolvedValueOnce(
      detail({
        kind: 'REPO', kindLabel: 'REPO 추가', status: 'DONE', statusLabel: '처리완료',
        repoAlias: 'p', repoGitUrl: 'https://gitlab.hamon.vip/g/p',
      }),
    ) // add-repo
    ;(document.querySelector('[data-test="cand-5"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/requests/3/add-repo', {
      method: 'POST',
      body: { gitlabProjectId: 5 },
    })
    expect(w.find('[data-test="request-status"]').text()).toBe('처리완료')
    expect(w.find('[data-test="repo-result"]').text()).toContain('등록된 레포: p')
    // 처리완료가 되었으니 추가 자리는 사라진다
    expect(w.find('[data-test="repo-add-slot"]').exists()).toBe(false)
    w.unmount()
  })
})
