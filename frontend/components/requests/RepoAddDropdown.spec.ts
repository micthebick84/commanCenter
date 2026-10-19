import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import RepoAddDropdown from './RepoAddDropdown.vue'
import { useApiMock } from '../../test/mocks/nuxt'

// q-btn-dropdown 메뉴는 포털(body)로 그려지므로 document에서 찾는다.
const q = (sel: string) => document.querySelector(sel) as HTMLElement | null
const qa = (sel: string) => Array.from(document.querySelectorAll(sel)) as HTMLElement[]

const cand = (id: number, over: Record<string, unknown> = {}) => ({
  id,
  name: `proj-${id}`,
  pathWithNamespace: `grp/proj-${id}`,
  defaultBranch: 'main',
  description: `설명 ${id}`,
  registered: false,
  ...over,
})

const CAND_URL = '/api/admin/requests/7/repo-candidates'

function routeApi(handlers: Record<string, (opts?: any) => unknown>) {
  useApiMock.mockImplementation(async (url: string, opts?: any) => {
    const h = handlers[url]
    if (!h) throw new Error('unexpected api call: ' + url)
    return h(opts)
  })
}

async function mountAndOpen() {
  const w = mount(RepoAddDropdown, { props: { requestId: 7 }, attachTo: document.body })
  await w.find('[data-test="repo-add-btn"]').trigger('click')
  await flushPromises()
  return w
}

async function typeSearch(value: string) {
  const input = q('[data-test="cand-search"]') as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

afterEach(() => {
  vi.useRealTimers()
  document.querySelectorAll('.q-menu, .q-dialog, .q-notification').forEach((n) => n.remove())
})

describe('RepoAddDropdown', () => {
  it('loads_candidates_on_open: 열 때 후보를 읽어 줄로 그리고 등록된 건 칩을 단다', async () => {
    routeApi({
      [CAND_URL]: () => [cand(5), cand(6, { registered: true, description: null })],
    })
    const w = await mountAndOpen()
    expect(useApiMock).toHaveBeenCalledWith(CAND_URL)
    const row5 = q('[data-test="cand-5"]')!
    expect(row5.textContent).toContain('grp/proj-5')
    expect(row5.textContent).toContain('main')
    expect(row5.textContent).toContain('설명 5')
    expect(row5.textContent).not.toContain('등록됨')
    expect(q('[data-test="cand-6"]')!.textContent).toContain('등록됨')
    expect(q('[data-test="cand-search"]')).not.toBeNull()
    w.unmount()
  })

  it('empty_candidates_shows_search_hint: 후보가 없으면 안내 문구와 검색창', async () => {
    routeApi({ [CAND_URL]: () => [] })
    const w = await mountAndOpen()
    expect(document.body.textContent).toContain('추측한 후보가 없어요. 검색해 주세요.')
    expect(q('[data-test="cand-search"]')).not.toBeNull()
    w.unmount()
  })

  it('search_debounced_and_min_two_chars: 2자 미만은 호출 없음, 2자 이상은 300ms 뒤 한 번', async () => {
    routeApi({
      [CAND_URL]: () => [],
      '/api/admin/gitlab-projects?search=co': () => [cand(9)],
    })
    const w = await mountAndOpen()
    vi.useFakeTimers()
    useApiMock.mockClear()

    await typeSearch('c')
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalled()

    await typeSearch('co')
    vi.advanceTimersByTime(299)
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledTimes(1)
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/gitlab-projects?search=co')
    // 검색 결과가 후보 영역을 대신한다
    expect(q('[data-test="cand-9"]')).not.toBeNull()
    w.unmount()
  })

  it('search_encodes_query: 검색어는 encodeURIComponent로 보낸다', async () => {
    routeApi({
      [CAND_URL]: () => [],
      [`/api/admin/gitlab-projects?search=${encodeURIComponent('a b&c')}`]: () => [],
    })
    const w = await mountAndOpen()
    vi.useFakeTimers()
    useApiMock.mockClear()
    await typeSearch('a b&c')
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/gitlab-projects?search=a%20b%26c')
    w.unmount()
  })

  it('clear_search_restores_candidates: 지우기 아이콘(null)으로 검색을 비우면 원래 후보가 돌아오고 오류가 없다', async () => {
    routeApi({
      [CAND_URL]: () => [cand(5)],
      '/api/admin/gitlab-projects?search=co': () => [cand(9)],
    })
    const w = await mountAndOpen()
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.useFakeTimers()
    await typeSearch('co')
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(q('[data-test="cand-9"]')).not.toBeNull()
    expect(q('[data-test="cand-5"]')).toBeNull()

    const clearIcon = (q('[data-test="cand-search"]') as HTMLElement).closest('.q-field')!.querySelector(
      '.q-field__append .q-icon',
    ) as HTMLElement
    expect(clearIcon).not.toBeNull()
    clearIcon.click()
    await flushPromises()
    expect(q('[data-test="cand-9"]')).toBeNull()
    expect(q('[data-test="cand-5"]')).not.toBeNull()
    expect(errSpy).not.toHaveBeenCalled()
    errSpy.mockRestore()
    w.unmount()
  })

  it('click_adds_and_emits: 항목을 누르면 확인 없이 POST하고 added를 emit한다', async () => {
    const detail = { id: 7, kind: 'REPO', repoAlias: 'Proj5' }
    routeApi({
      [CAND_URL]: () => [cand(5), cand(6)],
      '/api/admin/requests/7/add-repo': () => detail,
    })
    const w = await mountAndOpen()
    q('[data-test="cand-5"]')!.click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/admin/requests/7/add-repo', {
      method: 'POST',
      body: { gitlabProjectId: 5 },
    })
    expect(w.emitted('added')).toEqual([[detail]])
    expect(document.body.textContent).toContain('Proj5 등록됨')
    w.unmount()
  })

  it('click_in_flight_disables_all: 진행 중에는 다른 항목을 눌러도 두 번째 POST가 없다', async () => {
    let release: (v: unknown) => void = () => {}
    routeApi({
      [CAND_URL]: () => [cand(5), cand(6)],
      '/api/admin/requests/7/add-repo': () => new Promise((r) => (release = r)),
    })
    const w = await mountAndOpen()
    q('[data-test="cand-5"]')!.click()
    await flushPromises()
    q('[data-test="cand-6"]')!.click()
    await flushPromises()
    const posts = useApiMock.mock.calls.filter((c) => c[0] === '/api/admin/requests/7/add-repo')
    expect(posts).toHaveLength(1)
    expect(qa('[data-test^="cand-"].disabled, [data-test^="cand-"][aria-disabled="true"]').length).toBeGreaterThan(0)
    release({ id: 7, repoAlias: 'x' })
    await flushPromises()
    w.unmount()
  })

  it('add_failure_notifies_and_does_not_emit: 실패하면 서버 메시지 알림, emit 없음', async () => {
    routeApi({
      [CAND_URL]: () => [cand(5)],
      '/api/admin/requests/7/add-repo': () => {
        throw { statusCode: 409, data: { message: '이미 처리된 요청입니다' } }
      },
    })
    const w = await mountAndOpen()
    q('[data-test="cand-5"]')!.click()
    await flushPromises()
    expect(w.emitted('added')).toBeUndefined()
    expect(document.body.textContent).toContain('이미 처리된 요청입니다')
    w.unmount()
  })

  it('transient_load_error_keeps_search: 502면 메시지를 보이되 검색창은 남긴다', async () => {
    routeApi({
      [CAND_URL]: () => {
        throw { statusCode: 502, data: { message: 'GitLab 조회에 실패했습니다' } }
      },
    })
    const w = await mountAndOpen()
    expect(document.body.textContent).toContain('GitLab 조회에 실패했습니다')
    expect(q('[data-test="cand-search"]')).not.toBeNull()
    w.unmount()
  })

  it('unconfigured_shows_message_only: 503이면 서버 메시지만 보이고 검색창이 없다', async () => {
    routeApi({
      [CAND_URL]: () => {
        throw { statusCode: 503, data: { message: '사내 GitLab이 설정되지 않았습니다' } }
      },
    })
    const w = await mountAndOpen()
    expect(document.body.textContent).toContain('사내 GitLab이 설정되지 않았습니다')
    expect(q('[data-test="cand-search"]')).toBeNull()
    w.unmount()
  })
})
