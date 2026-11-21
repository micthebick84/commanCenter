import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'
import { QFile, QSelect } from 'quasar'
import QuestionsIndex from '../pages/questions/index.vue'
import RepoRouteCard from '../components/chat/RepoRouteCard.vue'
import QuestionComposer from '../components/chat/QuestionComposer.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'
import { ROUTE_TIMEOUT_MS } from '../composables/questionRoute'

// navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 여기서 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

function stubApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
    // 1=대상으로 쓰는 레포 · 2·3=참조 후보 (참조 repo 테스트가 쓴다 — 스펙 2026-10-22 §7.2)
    if (url === '/api/repo-catalog') return Promise.resolve([
      { id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'a/b', defaultBranch: 'main' },
      { id: 2, alias: '옛앱', host: 'gitlab', ownerRepo: 'product/legacy/old-app', defaultBranch: 'release/6.5' },
      { id: 3, alias: '공통 UI', host: 'github', ownerRepo: 'a/common-ui', defaultBranch: 'main' },
    ])
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    if (url === '/api/db-connections?repoCatalogId=1') return Promise.resolve({
      enabled: true,
      items: [
        { id: 7, scope: 'REPO', repoCatalogId: 1, name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', enabled: true, mine: false },
        { id: 8, scope: 'USER', repoCatalogId: 1, name: '내 DB', dbType: 'ORACLE', host: 'h', port: 1521, databaseName: 'svc', username: 'u', enabled: true, mine: true },
      ],
    })
    return Promise.resolve(null)
  })
}

describe('pages/questions/index — 새 질문 입력창 (스펙 2026-09-05 §3·§6)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    localStorage.setItem('netis-maker-question-repo-mode', 'manual')
    stubApi()
  })

  it('히어로 문구와 입력창을 그리고 레포 카탈로그를 불러오며, 레포/질문 전엔 전송이 막힌다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    expect(w.text()).toContain('무엇이 궁금하세요?')
    expect(useApiMock).toHaveBeenCalledWith('/api/repo-catalog')
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="model-picker"]').attributes('disabled')).toBeUndefined() // create 모드 = 픽커 활성
    expect(w.find('[data-test="mcp-button"]').text()).toContain('MCP 도구') // 넓은 화면: 라벨 노출
    w.unmount()
  })

  it('넓은 화면에서는 목록 버튼이 없고, 셸 사이드바가 접혔으면(questions:list-hidden) 보인다 — 누르면 셸의 열기를 부른다', async () => {
    const plain = mount(QuestionsIndex)
    await flushPromises()
    expect(plain.find('[data-test="open-drawer"]').exists()).toBe(false)
    plain.unmount()

    const openDrawer = vi.fn()
    const w = mount(QuestionsIndex, {
      global: { provide: { 'questions:list-hidden': ref(true), 'questions:open-drawer': openDrawer } },
    })
    await flushPromises()
    await w.find('[data-test="open-drawer"]').trigger('click')
    expect(openDrawer).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('제목 없이 레포/브랜치/질문/모델/effort/MCP로 POST하고, 목록을 갱신한 뒤 대화로 이동한다', async () => {
    const refresh = vi.fn()
    const w = mount(QuestionsIndex, { global: { provide: { 'questions:refresh': refresh } } })
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, {
      repoCatalogId: 1, githubBranch: 'dev', question: '로그인은 어디서?',
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    await vm.submit()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: 1, githubBranch: 'dev', question: '로그인은 어디서?',
        model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
      },
    })
    expect(refresh).toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    w.unmount()
  })

  it('429는 경고 토스트 (등록 실패로 처리하지 않음)', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, { repoCatalogId: 1, githubBranch: 'main', question: 'q' })
    useApiMock.mockImplementationOnce(() =>
      Promise.reject({ statusCode: 429, data: { message: '동시에 진행할 수 있는 질문 세션 한도(3)를 초과했습니다' } }),
    )
    await vm.submit()
    await flushPromises()
    expect(document.body.textContent).toContain('한도(3)')
    expect(navigateToMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('레포를 빠르게 전환하면 늦게 도착한 이전 레포의 브랜치 콜백이 현재 선택을 덮어쓰지 않는다', async () => {
    let resolveA!: (v: unknown) => void
    let resolveB!: (v: unknown) => void
    const pendingA = new Promise((resolve) => { resolveA = resolve })
    const pendingB = new Promise((resolve) => { resolveB = resolve })

    useApiMock.mockImplementation((url: string, opts?: { method?: string; params?: any }) => {
      if (url === '/api/repo-catalog') return Promise.resolve([
        { id: 1, alias: 'A', host: 'github', ownerRepo: 'org/a', defaultBranch: 'main' },
        { id: 2, alias: 'B', host: 'github', ownerRepo: 'org/b', defaultBranch: 'develop' },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      if (url === '/api/repos/branches' && opts?.params?.catalogId === 1) return pendingA
      if (url === '/api/repos/branches' && opts?.params?.catalogId === 2) return pendingB
      return Promise.resolve(null)
    })

    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any

    vm.onRepoSelected(1) // 레포 A 선택 — 브랜치 조회 진행 중
    vm.onRepoSelected(2) // 곧바로 레포 B로 전환 — inflightRepo가 org/b로 바뀜
    await flushPromises()

    resolveB({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'y' }] })
    await flushPromises()
    resolveA({ defaultBranch: 'main', branches: [{ name: 'main', sha: 'x' }] })
    await flushPromises()

    expect(vm.draft.githubBranch).toBe('develop')
    w.unmount()
  })

  it('GitLab 카탈로그 항목(ownerRepo=다단계 경로)도 catalogId로 브랜치를 불러온다', async () => {
    useApiMock.mockImplementation((url: string, opts?: { params?: any }) => {
      if (url === '/api/repo-catalog') return Promise.resolve([
        { id: 5, alias: 'Netis7(GitLab)', host: 'gitlab', ownerRepo: 'product/netis/web/netis-v7.0', defaultBranch: null },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      if (url === '/api/repos/branches' && opts?.params?.catalogId === 5)
        return Promise.resolve({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'z' }] })
      return Promise.resolve(null)
    })
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.onRepoSelected(5)
    await flushPromises()
    expect(vm.draft.githubBranch).toBe('develop')
    expect(useApiMock).toHaveBeenCalledWith('/api/repos/branches', { params: { catalogId: 5 } })
    w.unmount()
  })

  it('지원하지 않는 호스트(other)는 조용히 멈추지 않고 이유를 보여 준다', async () => {
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/repo-catalog') return Promise.resolve([
        { id: 6, alias: 'Bitbucket', host: 'other', ownerRepo: null, defaultBranch: null },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      return Promise.resolve(null)
    })
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.onRepoSelected(6)
    await flushPromises()
    expect(vm.repoStatus).toBe('error')
    expect(vm.repoStatusMsg).toContain('지원하지 않는 호스트')
    expect(useApiMock).not.toHaveBeenCalledWith('/api/repos/branches', expect.anything())
    w.unmount()
  })

  it('파일을 첨부하면 multipart(meta JSON + files)로 POST하고, 성공 시 대기 파일을 비운다 (스펙 2026-09-13 §7)', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, {
      repoCatalogId: 1, githubBranch: 'dev', question: '이 문서 기준으로 설명해줘',
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    const f = new File(['x'], '설계.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    w.findComponent(QFile).vm.$emit('update:modelValue', [f])
    await flushPromises()
    expect(w.find('[data-test="composer-files"]').text()).toContain('설계.docx')

    await vm.submit()
    await flushPromises()

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')
    expect(call).toBeTruthy()
    const body = call![1].body as FormData
    expect(body).toBeInstanceOf(FormData)
    const metaBlob = body.get('meta') as Blob
    expect(metaBlob.type).toBe('application/json')
    // meta = 기존 JSON 바디 그대로 (QuestionCreateRequest 무변경)
    expect(JSON.parse(await metaBlob.text())).toEqual({
      repoCatalogId: 1, githubBranch: 'dev', question: '이 문서 기준으로 설명해줘',
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    expect((body.getAll('files') as File[]).map((x) => x.name)).toEqual(['설계.docx'])
    expect(call![1].headers?.['Content-Type']).toBeUndefined() // $fetch가 boundary를 스스로 설정
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    expect(w.find('[data-test="composer-files"]').exists()).toBe(false)
    w.unmount()
  })

  it('등록이 실패하면 대기 파일을 유지한다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, { repoCatalogId: 1, githubBranch: 'main', question: 'q' })
    w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['x'], '설계.docx')])
    await flushPromises()
    useApiMock.mockImplementationOnce(() =>
      Promise.reject({ statusCode: 400, data: { message: '지원하지 않는 파일 형식입니다' } }),
    )
    await vm.submit()
    await flushPromises()
    expect(navigateToMock).not.toHaveBeenCalled()
    expect(w.find('[data-test="composer-files"]').text()).toContain('설계.docx')
    w.unmount()
  })

  it('xs 화면(<600px)에서는 MCP 도구 버튼이 라벨 없이 아이콘만 남기고 aria-label로 이름을 유지한다', async () => {
    // 390px에서 "MCP 도구" 라벨이 두 줄로 꺾이던 결함(2026-09-06 모바일 QA).
    await setViewportWidth(390)
    try {
      const w = mount(QuestionsIndex)
      await flushPromises()
      const btn = w.find('[data-test="mcp-button"]')
      expect(btn.exists()).toBe(true)
      expect(btn.text()).not.toContain('MCP 도구')
      expect(btn.attributes('aria-label')).toBe('MCP 도구')
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('레포를 고르면 DB 버튼이 생기고 공용 연결이 기본 선택되어 등록 바디에 실린다 (스펙 2026-10-02 §7)', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    expect(w.find('[data-test="db-button"]').exists()).toBe(false)
    const vm = w.vm as any
    vm.draft.repoCatalogId = 1
    vm.onRepoSelected(1)
    await flushPromises()
    expect(w.find('[data-test="db-button"]').exists()).toBe(true)
    expect(vm.draft.dbConnectionIds).toEqual([7])
    Object.assign(vm.draft, { githubBranch: 'main', question: 'DB에 주문 테이블 있어?' })
    await vm.submit()
    await flushPromises()
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')
    expect(call![1].body.dbConnectionIds).toEqual([7])
    w.unmount()
  })

  it('DB 기능이 꺼져 있으면 버튼이 없고 바디에 dbConnectionIds를 싣지 않는다', async () => {
    useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
      if (url.startsWith('/api/db-connections')) return Promise.resolve({ enabled: false, items: [] })
      if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
      if (url === '/api/repo-catalog') return Promise.resolve([{ id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'a/b', defaultBranch: 'main' }])
      return Promise.resolve(null)
    })
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.draft.repoCatalogId = 1
    vm.onRepoSelected(1)
    await flushPromises()
    expect(w.find('[data-test="db-button"]').exists()).toBe(false)
    Object.assign(vm.draft, { githubBranch: 'main', question: 'q' })
    await vm.submit()
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')
    expect('dbConnectionIds' in call![1].body).toBe(false)
    w.unmount()
  })
})

// ── 참조 repo (스펙 2026-10-22 §7.2) ──
// 카탈로그(stubApi): 1=대상으로 쓰는 레포 · 2=옛앱(GitLab, 기본 브랜치 release/6.5) · 3=공통 UI
const REF_OLD = { catalogId: 2, alias: '옛앱', branch: 'release/6.5' }
const REF_UI = { catalogId: 3, alias: '공통 UI', branch: 'main' }
const REF_BATCH = { catalogId: 4, alias: '배치 서버', branch: 'master' }

type Wrapper = ReturnType<typeof mount>

const selectOf = (w: Wrapper, id: string) =>
  w.findAllComponents(QSelect).find((c) => (c.vm.$attrs as Record<string, unknown>)['data-test'] === id)!
const optionValues = (sel: { props: (k: string) => unknown }) =>
  (sel.props('options') as Array<{ value: unknown }>).map((o) => o.value)
const refChips = (w: Wrapper) => w.findAll('[data-test="ref-chip"]')
const refIdsOf = (vm: any) => (vm.draft.refRepos as Array<{ catalogId: number }>).map((r) => r.catalogId)
/** a가 b보다 문서에서 앞에 있는가 */
const precedes = (a: Element, b: Element) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

/** POST /api/questions 호출의 body */
function postedBody() {
  const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')
  expect(call).toBeTruthy()
  return call![1].body
}

/** submit한 뒤의 POST body — 이전 호출 기록은 비우고 시작한다(같은 마운트에서 두 번 보낼 때) */
async function submitAndGetBody(vm: any) {
  useApiMock.mockClear()
  await vm.submit()
  await flushPromises()
  return postedBody()
}

// 추가 폼이 `/api/repos/branches`를 부른다 — 기본 stubApi는 null을 돌려주므로 브랜치 응답만 덧붙인다
function stubBranches() {
  const base = useApiMock.getMockImplementation()!
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/repos/branches') {
      return Promise.resolve({
        defaultBranch: 'main',
        branches: [{ name: 'main', sha: 'a' }, { name: 'release/6.5', sha: 'b' }],
      })
    }
    return base(url, opts)
  })
}

describe('pages/questions/index — 참조 repo (스펙 2026-10-22 §7.2)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    localStorage.setItem('netis-maker-question-repo-mode', 'manual')
    stubApi()
  })

  it('참조를 하나 고르면 등록 바디에 refRepos가 실린다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, {
      repoCatalogId: 1, githubBranch: 'dev', question: '옛앱은 이걸 어떻게 했어?',
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    vm.draft.refRepos.push({ ...REF_OLD })
    await vm.submit()
    await flushPromises()
    // 화면 값(별칭)은 빠지고 (레포, 브랜치)만 나간다
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: 1, githubBranch: 'dev', question: '옛앱은 이걸 어떻게 했어?',
        model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
        refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }],
      },
    })
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    w.unmount()
  })

  it('파일을 함께 보내도 refRepos는 meta JSON에 실린다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, {
      repoCatalogId: 1, githubBranch: 'dev', question: '이 문서 기준으로 설명해줘',
      model: 'claude-sonnet-5', effort: 'medium',
    })
    vm.draft.refRepos.push({ ...REF_OLD }, { ...REF_UI })
    w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['x'], '설계.docx')])
    await flushPromises()
    await vm.submit()
    await flushPromises()
    const body = postedBody() as FormData
    expect(body).toBeInstanceOf(FormData)
    const meta = JSON.parse(await (body.get('meta') as Blob).text())
    expect(meta.refRepos).toEqual([
      { repoCatalogId: 2, branch: 'release/6.5' },
      { repoCatalogId: 3, branch: 'main' },
    ])
    w.unmount()
  })

  it('참조가 없으면 바디에 refRepos 키가 없다 (골랐다가 모두 해제한 경우 포함)', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, { repoCatalogId: 1, githubBranch: 'main', question: 'q' })
    expect('refRepos' in (await submitAndGetBody(vm))).toBe(false)

    // 하나 골랐다가 ✕로 해제하면 다시 키가 없다
    vm.draft.refRepos.push({ ...REF_OLD })
    await flushPromises()
    await w.find('.q-chip__icon--remove').trigger('click')
    expect(vm.draft.refRepos).toEqual([])
    expect('refRepos' in (await submitAndGetBody(vm))).toBe(false)
    w.unmount()
  })

  it('대상을 참조 중 하나로 바꾸면 그 참조만 빠진다', async () => {
    stubBranches()
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.draft.refRepos.push({ ...REF_OLD }, { ...REF_UI })
    await flushPromises()
    expect(refChips(w)).toHaveLength(2)

    vm.draft.repoCatalogId = 2
    vm.onRepoSelected(2)
    await flushPromises()
    expect(refIdsOf(vm)).toEqual([3])
    // 칩 줄도 따라간다
    expect(refChips(w)).toHaveLength(1)
    expect(refChips(w)[0]!.text()).toContain('공통 UI')
    expect(w.text()).not.toContain('📚 옛앱')
    w.unmount()
  })

  it('참조가 아닌 레포로 바꾸거나 대상을 비우면 참조는 그대로 남는다', async () => {
    stubBranches()
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.draft.refRepos.push({ ...REF_OLD }, { ...REF_UI })

    vm.draft.repoCatalogId = 1
    vm.onRepoSelected(1)
    await flushPromises()
    expect(refIdsOf(vm)).toEqual([2, 3])

    vm.draft.repoCatalogId = null
    vm.onRepoSelected(null)
    await flushPromises()
    expect(refIdsOf(vm)).toEqual([2, 3])
    w.unmount()
  })

  it('참조 칩 줄은 대상 레포·브랜치 선택 아래에 있고 3개면 ＋ 참조 추가가 사라진다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    const chips = w.find('[data-test="ref-repo-chips"]').element
    const selectRow = w.find('[data-test="repo-select"]').element.closest('.q-field')!.parentElement!
    expect(selectRow.children).toHaveLength(2) // 레포·브랜치 선택이 나란히 든 줄

    // 선택 줄 안에 끼지 않고 그 줄이 끝난 다음에 오며, 입력창보다는 앞이다
    expect(selectRow.contains(chips)).toBe(false)
    expect(precedes(selectRow.lastElementChild!, chips)).toBe(true)
    expect(precedes(chips, w.find('[data-test="composer-input"]').element)).toBe(true)

    // 편집 모드: 비어 있어도 "＋ 참조 추가"가 보인다
    expect(w.find('[data-test="ref-repo-chips"]').text()).toContain('참조 repo 없음')
    expect(w.find('[data-test="ref-add"]').text()).toContain('＋ 참조 추가')

    vm.draft.refRepos.push({ ...REF_OLD }, { ...REF_UI })
    await flushPromises()
    expect(w.find('[data-test="ref-add"]').exists()).toBe(true)
    vm.draft.refRepos.push({ ...REF_BATCH })
    await flushPromises()
    expect(refChips(w)).toHaveLength(3)
    expect(w.find('[data-test="ref-add"]').exists()).toBe(false)
    expect(w.findAll('.q-chip__icon--remove')).toHaveLength(3) // ✕는 남는다 (편집 모드)
    w.unmount()
  })

  it('본문 컨테이너는 줄바꿈하지 않는다 — 칩 줄·추가 폼으로 입력창이 높아져도 옆 열로 밀려나지 않는다', async () => {
    // Quasar `.column`은 기본이 flex-wrap이라 hero + 입력창이 남은 높이를 넘으면 입력창이 오른쪽 새 열로 밀려 나가
    // 셸(overflow:hidden)에 가려진다(390px 프리뷰 실측). vitest는 CSS를 안 읽어 레이아웃 자체는 못 잡으므로 클래스를 지킨다.
    const w = mount(QuestionsIndex)
    await flushPromises()
    expect(w.find('.new-question-body').classes()).toContain('no-wrap')
    w.unmount()
  })

  it('칩의 ✕를 누르면 그 참조만 빠진다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.draft.refRepos.push({ ...REF_OLD }, { ...REF_UI })
    await flushPromises()
    const removeIcons = w.findAll('.q-chip__icon--remove')
    expect(removeIcons).toHaveLength(2)
    await removeIcons[0]!.trigger('click')
    expect(refIdsOf(vm)).toEqual([3])
    expect(refChips(w)).toHaveLength(1)
    w.unmount()
  })

  it('＋ 참조 추가를 누르면 칩 줄 아래에 추가 폼이 펼쳐지고, 레포·브랜치를 골라 추가하면 칩이 생기고 접힌다', async () => {
    stubBranches()
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.draft.repoCatalogId = 1
    expect(w.find('[data-test="ref-add-form"]').exists()).toBe(false) // 눌러야 펼쳐진다

    await w.find('[data-test="ref-add"]').trigger('click')
    const form = w.find('[data-test="ref-add-form"]')
    expect(form.exists()).toBe(true)
    expect(precedes(w.find('[data-test="ref-repo-chips"]').element, form.element)).toBe(true) // 칩 줄 아래
    // 후보: 대상(1)은 빠진다
    expect(optionValues(selectOf(w, 'ref-add-repo'))).toEqual([2, 3])

    selectOf(w, 'ref-add-repo').vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/repos/branches', { params: { catalogId: 2 } })
    await w.find('[data-test="ref-add-submit"]').trigger('click')
    await flushPromises()

    expect(vm.draft.refRepos).toEqual([{ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }])
    expect(w.find('[data-test="ref-add-form"]').exists()).toBe(false) // 추가하면 접힌다
    expect(refChips(w)).toHaveLength(1)
    expect(refChips(w)[0]!.text()).toContain('📚 옛앱 · release/6.5')

    // 다시 펼치면 이미 고른 레포도 후보에서 빠진다
    await w.find('[data-test="ref-add"]').trigger('click')
    expect(optionValues(selectOf(w, 'ref-add-repo'))).toEqual([3])

    // 칩의 ✕로 빼면 그 레포는 다시 후보가 된다
    await w.find('.q-chip__icon--remove').trigger('click')
    expect(vm.draft.refRepos).toEqual([])
    expect(optionValues(selectOf(w, 'ref-add-repo'))).toEqual([2, 3])
    w.unmount()
  })

  it('추가 폼의 후보는 대상 레포가 바뀌면 따라 바뀌고, ＋ 참조 추가를 한 번 더 누르면 폼이 접힌다', async () => {
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    await w.find('[data-test="ref-add"]').trigger('click')
    // 대상을 아직 안 골랐으면 전부 후보
    expect(optionValues(selectOf(w, 'ref-add-repo'))).toEqual([1, 2, 3])

    vm.draft.repoCatalogId = 3
    await flushPromises()
    expect(optionValues(selectOf(w, 'ref-add-repo'))).toEqual([1, 2])

    await w.find('[data-test="ref-add"]').trigger('click')
    expect(w.find('[data-test="ref-add-form"]').exists()).toBe(false)
    w.unmount()
  })
})

// ── 자동 모드 — 레포 추천 카드 (스펙 2026-11-20 §5.1·§5.2·§6.1·§6.3) ──
const MODE_KEY = 'netis-maker-question-repo-mode'
const ROUTE_CONFIDENT = {
  routeId: 55,
  state: 'CONFIDENT',
  recommendedRepoCatalogId: 1,
  candidates: [{ repoCatalogId: 1, probability: 0.9 }],
  recentRepoCatalogIds: [],
  eligibleRepoCatalogIds: [1, 2, 3],
  dbNeeded: true,
}
const AUTO_Q = '로그인은 어디서 처리하나요?'

function stubAuto(route: unknown = ROUTE_CONFIDENT, dbEnabled = true) {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/questions/route') return Promise.resolve(route)
    if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
    if (url === '/api/repo-catalog') return Promise.resolve([
      { id: 1, alias: 'Netis7.0', description: '본사 7.0', host: 'github', ownerRepo: 'a/b', defaultBranch: 'main' },
      { id: 2, alias: '옛앱', description: null, host: 'gitlab', ownerRepo: 'p/old', defaultBranch: 'release/6.5' },
    ])
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    if (url === '/api/repos/branches') return Promise.resolve({ defaultBranch: 'main', branches: [{ name: 'main', sha: 'a' }] })
    if (url === '/api/db-connections?repoCatalogId=1') return Promise.resolve(
      dbEnabled
        ? { enabled: true, items: [{ id: 7, scope: 'REPO', repoCatalogId: 1, name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', enabled: true, mine: false }] }
        : { enabled: false, items: [] },
    )
    return Promise.resolve(null)
  })
}

const routeCard = (w: Wrapper) => w.findComponent(RepoRouteCard)
const composerOf = (w: Wrapper) => w.findComponent(QuestionComposer)
const startPayload = { repoCatalogId: 1, githubBranch: 'main', dbConnectionIds: [7] as number[] | null }
const questionPost = () => useApiMock.mock.calls.find((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')

/** 기본 구현을 유지한 채 일부 URL만 덮어쓴다 */
function overrideApi(match: (url: string, opts?: { method?: string }) => boolean, handler: () => unknown) {
  const base = useApiMock.getMockImplementation()!
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => (match(url, opts) ? handler() : base(url, opts)))
}
const isRouteCall = (url: string) => url === '/api/questions/route'
const isCreateCall = (url: string, opts?: { method?: string }) => url === '/api/questions' && opts?.method === 'POST'

async function mountAuto(route: unknown = ROUTE_CONFIDENT, dbEnabled = true) {
  stubAuto(route, dbEnabled)
  const w = mount(QuestionsIndex)
  await flushPromises()
  const vm = w.vm as any
  vm.draft.question = AUTO_Q
  return { w, vm }
}
/** 질문을 보내 카드가 뜰 때까지 */
async function toCard(route: unknown = ROUTE_CONFIDENT, dbEnabled = true) {
  const m = await mountAuto(route, dbEnabled)
  await m.vm.submit()
  await flushPromises()
  return m
}

describe('pages/questions/index — 자동 모드 — 레포 추천 카드', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    localStorage.removeItem(MODE_KEY)
    stubAuto()
  })

  it('기본은 자동 모드다 — 레포·브랜치 선택 상자와 참조 칩 줄이 없고 본문만 있으면 보낼 수 있다', async () => {
    const { w, vm } = await mountAuto()
    expect(vm.mode).toBe('auto')
    expect(w.find('[data-test="repo-select"]').exists()).toBe(false)
    expect(w.find('[data-test="ref-repo-chips"]').exists()).toBe(false)
    expect(w.text()).toContain('질문을 보면 찾아 드려요')
    expect(w.text()).toContain('맞는 레포를 찾아 확인받은 뒤 답합니다')
    expect(w.find('[data-test="repo-mode-manual"]').exists()).toBe(true)
    await flushPromises()
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeUndefined()
    vm.draft.question = '  '
    await flushPromises()
    expect(w.find('[data-test="composer-send"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('보내면 /api/questions/route를 부르고 내 말풍선과 카드를 그린다 — 이때 /api/questions POST는 없다', async () => {
    const { w, vm } = await toCard()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/route', {
      method: 'POST',
      body: { question: AUTO_Q },
      timeout: ROUTE_TIMEOUT_MS,
    })
    expect(vm.phase).toBe('card')
    expect(w.find('.bubble.user').text()).toContain(AUTO_Q)
    expect(routeCard(w).exists()).toBe(true)
    expect(w.find('[data-test="route-edit"]').exists()).toBe(true)
    expect(w.text()).not.toContain('무엇이 궁금하세요?') // 히어로는 숨는다
    expect(questionPost()).toBeUndefined()
    w.unmount()
  })

  it('대기 표시와 카드가 생길 때마다 그 자리를 화면 안으로 스크롤한다', async () => {
    const scroll = vi.fn()
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = scroll
    try {
      const { w, vm } = await mountAuto()
      let resolveRoute!: (v: unknown) => void
      overrideApi(isRouteCall, () => new Promise((r) => { resolveRoute = r }))
      const pending = vm.submit()
      await flushPromises()
      expect(vm.phase).toBe('routing')
      expect(scroll).toHaveBeenCalledTimes(1)
      expect(scroll).toHaveBeenLastCalledWith({ block: 'nearest' })
      resolveRoute(ROUTE_CONFIDENT)
      await pending
      await flushPromises()
      expect(vm.phase).toBe('card')
      expect(scroll).toHaveBeenCalledTimes(2)
      w.unmount()
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('route 호출 중에는 입력창이 잠기고 카드 자리에 대기 표시가 있다', async () => {
    const { w, vm } = await mountAuto()
    let resolveRoute!: (v: unknown) => void
    overrideApi(isRouteCall, () => new Promise((r) => { resolveRoute = r }))
    const pending = vm.submit()
    await flushPromises()
    expect(vm.phase).toBe('routing')
    expect(composerOf(w).props('disabled')).toBe(true)
    expect(composerOf(w).props('placeholder')).toBe('레포를 확정하면 답변이 시작됩니다')
    expect(w.find('[data-test="route-waiting"]').exists()).toBe(true)
    expect(routeCard(w).exists()).toBe(false)
    resolveRoute(ROUTE_CONFIDENT)
    await pending
    await flushPromises()
    expect(w.find('[data-test="route-waiting"]').exists()).toBe(false)
    expect(routeCard(w).exists()).toBe(true)
    w.unmount()
  })

  it('카드에서 시작하면 routeId·고른 레포·브랜치·dbConnectionIds로 POST하고 대화로 이동한다', async () => {
    const refresh = vi.fn()
    stubAuto()
    const w = mount(QuestionsIndex, { global: { provide: { 'questions:refresh': refresh } } })
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, { question: AUTO_Q, model: 'claude-sonnet-5', effort: 'medium' })
    await vm.submit()
    await flushPromises()
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    const call = questionPost()!
    expect(call[1].body).toEqual({
      repoCatalogId: 1, githubBranch: 'main', question: AUTO_Q,
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [], dbConnectionIds: [7], routeId: 55,
    })
    expect(Object.keys(call[1].body)).toEqual([
      'repoCatalogId', 'githubBranch', 'question', 'model', 'effort', 'mcpCatalogIds', 'dbConnectionIds', 'routeId',
    ])
    expect(refresh).toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    w.unmount()
  })

  it('카드의 시작 버튼을 눌러도 같은 바디로 POST한다 (실제 카드 연동)', async () => {
    const { w } = await toCard()
    await vi.waitFor(() => expect(w.find('[data-test="route-start"]').attributes('disabled')).toBeUndefined())
    await w.find('[data-test="route-start"]').trigger('click')
    await flushPromises()
    expect(questionPost()![1].body).toMatchObject({ repoCatalogId: 1, githubBranch: 'main', dbConnectionIds: [7], routeId: 55 })
    w.unmount()
  })

  it('DB 기능이 꺼져 있으면 dbConnectionIds를 싣지 않는다', async () => {
    const { w } = await toCard(ROUTE_CONFIDENT, false)
    routeCard(w).vm.$emit('start', { ...startPayload, dbConnectionIds: null })
    await flushPromises()
    expect('dbConnectionIds' in questionPost()![1].body).toBe(false)
    w.unmount()
  })

  it('routeId가 null이면(추천 호출 실패) routeId를 싣지 않는다', async () => {
    const { w, vm } = await toCard(null) // 모양 이상 → UNAVAILABLE, routeId null
    expect(vm.route.routeId).toBeNull()
    expect(vm.route.state).toBe('UNAVAILABLE')
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    expect('routeId' in questionPost()![1].body).toBe(false)
    w.unmount()
  })

  it('자동 모드의 등록 바디에는 refRepos가 없다', async () => {
    const { w, vm } = await toCard()
    vm.draft.refRepos.push({ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }) // 만에 하나 남아 있어도 싣지 않는다
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    expect('refRepos' in questionPost()![1].body).toBe(false)
    w.unmount()
  })

  it('첨부가 있으면 multipart(meta + files)로 보낸다', async () => {
    const { w, vm } = await mountAuto()
    w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['x'], '설계.docx')])
    await flushPromises()
    await vm.submit()
    await flushPromises()
    expect(w.find('.bubble-attachments').text()).toContain('설계.docx') // 말풍선에 이름 칩
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    const body = questionPost()![1].body as FormData
    expect(body).toBeInstanceOf(FormData)
    const meta = JSON.parse(await (body.get('meta') as Blob).text())
    expect(meta).toMatchObject({ repoCatalogId: 1, githubBranch: 'main', question: AUTO_Q, dbConnectionIds: [7], routeId: 55 })
    expect('refRepos' in meta).toBe(false)
    expect((body.getAll('files') as File[]).map((f) => f.name)).toEqual(['설계.docx'])
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    w.unmount()
  })

  it('질문 고치기를 누르면 입력창으로 돌아가고 본문·첨부가 남아 있다', async () => {
    const { w, vm } = await mountAuto()
    w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['x'], '설계.docx')])
    await flushPromises()
    await vm.submit()
    await flushPromises()
    await w.find('[data-test="route-edit"]').trigger('click')
    await flushPromises()
    expect(vm.phase).toBe('compose')
    expect(vm.route).toBeNull()
    expect(vm.draft.question).toBe(AUTO_Q)
    expect(w.find('[data-test="composer-files"]').text()).toContain('설계.docx')
    expect(routeCard(w).exists()).toBe(false)
    expect(w.text()).toContain('무엇이 궁금하세요?')
    expect(composerOf(w).props('disabled')).toBe(false)
    w.unmount()
  })

  it('질문 고치기 뒤에 늦게 도착한 route 응답은 카드를 되살리지 않는다', async () => {
    const { w, vm } = await mountAuto()
    let resolveRoute!: (v: unknown) => void
    overrideApi(isRouteCall, () => new Promise((r) => { resolveRoute = r }))
    const pending = vm.submit()
    await flushPromises()
    await w.find('[data-test="route-edit"]').trigger('click')
    resolveRoute(ROUTE_CONFIDENT)
    await pending
    await flushPromises()
    expect(vm.phase).toBe('compose')
    expect(vm.route).toBeNull()
    expect(routeCard(w).exists()).toBe(false)
    w.unmount()
  })

  it('등록이 400으로 실패하면 카드에 남아 서버 문구를 알린다', async () => {
    const { w, vm } = await toCard()
    overrideApi(isCreateCall, () => Promise.reject({ statusCode: 400, data: { message: '레포를 읽을 수 없습니다' } }))
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    expect(document.body.textContent).toContain('레포를 읽을 수 없습니다')
    expect(vm.phase).toBe('card')
    expect(routeCard(w).exists()).toBe(true)
    expect(routeCard(w).props('starting')).toBe(false)
    expect(navigateToMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('등록이 429면 경고 톤으로 알리고 카드에 남는다', async () => {
    const { w, vm } = await toCard()
    overrideApi(isCreateCall, () =>
      Promise.reject({ statusCode: 429, data: { message: '동시에 진행할 수 있는 질문 세션 한도(3)를 초과했습니다' } }))
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    const notif = Array.from(document.querySelectorAll('.q-notification')).find((n) => n.textContent?.includes('한도(3)'))
    expect(notif).toBeTruthy()
    expect(notif!.className).toContain('bg-warning')
    expect(vm.phase).toBe('card')
    expect(navigateToMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('등록 중에는 카드에 starting을 주고 두 번째 start는 무시한다', async () => {
    const { w } = await toCard()
    let resolvePost!: (v: unknown) => void
    overrideApi(isCreateCall, () => new Promise((r) => { resolvePost = r }))
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    expect(routeCard(w).props('starting')).toBe(true)
    routeCard(w).vm.$emit('start', startPayload)
    await flushPromises()
    expect(useApiMock.mock.calls.filter((c) => c[0] === '/api/questions' && c[1]?.method === 'POST')).toHaveLength(1)
    resolvePost({ id: 12 })
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('직접 고르기로 바꾸면 지금의 선택 상자가 보이고 선택이 저장된다(localStorage)', async () => {
    const { w, vm } = await mountAuto()
    await w.find('[data-test="repo-mode-manual"]').trigger('click')
    await flushPromises()
    expect(vm.mode).toBe('manual')
    expect(w.find('[data-test="repo-select"]').exists()).toBe(true)
    expect(w.find('[data-test="ref-repo-chips"]').exists()).toBe(true)
    expect(w.find('[data-test="repo-mode-auto"]').exists()).toBe(true)
    expect(w.text()).toContain('선택한 레포를 읽고 답합니다')
    expect(localStorage.getItem(MODE_KEY)).toBe('manual')
    w.unmount()
    // 다음 방문: onMounted에서 읽는다
    const again = mount(QuestionsIndex)
    await flushPromises()
    expect((again.vm as any).mode).toBe('manual')
    again.unmount()
  })

  it('저장된 값이 manual이 아니면 자동이다', async () => {
    localStorage.setItem(MODE_KEY, 'weird')
    const w = mount(QuestionsIndex)
    await flushPromises()
    expect((w.vm as any).mode).toBe('auto')
    w.unmount()
  })

  it('직접 모드에서 보내면 route를 부르지 않고 예전과 같은 바디로 POST한다', async () => {
    localStorage.setItem(MODE_KEY, 'manual')
    stubAuto()
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.draft, {
      repoCatalogId: 2, githubBranch: 'dev', question: 'q', model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    await vm.submit()
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalledWith('/api/questions/route', expect.anything())
    expect(questionPost()![1].body).toEqual({
      repoCatalogId: 2, githubBranch: 'dev', question: 'q', model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    expect(vm.phase).toBe('compose')
    w.unmount()
  })

  it('자동으로 찾기로 돌아가면 고른 레포·브랜치·DB·참조가 비워진다', async () => {
    localStorage.setItem(MODE_KEY, 'manual')
    stubAuto()
    const w = mount(QuestionsIndex)
    await flushPromises()
    const vm = w.vm as any
    vm.draft.repoCatalogId = 1
    vm.onRepoSelected(1)
    await flushPromises()
    expect(vm.draft.githubBranch).toBe('main')
    expect(vm.draft.dbConnectionIds).toEqual([7])
    vm.draft.refRepos.push({ catalogId: 2, alias: '옛앱', branch: 'release/6.5' })
    await w.find('[data-test="ref-add"]').trigger('click')
    expect(w.find('[data-test="ref-add-form"]').exists()).toBe(true)

    await w.find('[data-test="repo-mode-auto"]').trigger('click')
    await flushPromises()
    expect(vm.mode).toBe('auto')
    expect(vm.draft.repoCatalogId).toBeNull()
    expect(vm.draft.githubBranch).toBe('')
    expect(vm.draft.dbConnectionIds).toEqual([])
    expect(vm.db.enabled).toBe(false)
    expect(vm.draft.refRepos).toEqual([])
    expect(localStorage.getItem(MODE_KEY)).toBe('auto')
    // 다시 직접 모드로 가도 참조 추가 폼은 접혀 있다
    await w.find('[data-test="repo-mode-manual"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="ref-add-form"]').exists()).toBe(false)
    w.unmount()
  })

  it('폰 폭(390px)에서 카드와 잠긴 입력창이 함께 그려진다', async () => {
    await setViewportWidth(390)
    try {
      const { w } = await toCard()
      expect(routeCard(w).exists()).toBe(true)
      expect(composerOf(w).props('disabled')).toBe(true)
      expect(w.find('.bubble.user').exists()).toBe(true)
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })
})
