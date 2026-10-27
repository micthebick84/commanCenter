import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, type App } from 'vue'
import { QLayout, QPageContainer, QFile } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

// 질문 대화에서 넘어온 작업 등록 (스펙 2026-10-24 §7.2) — /tasks?fromQuestion={id}로 들어오면 마운트 뒤 질문을 읽어
// 등록 창을 열고 대상·브랜치·참조·제목을 채운 뒤 주소에서 쿼리를 뺀다. 마운트·창 찾기는 tasks-form-refs.spec.ts와 같다.

// useRoute는 setup.ts 기본값(쿼리 없음)을 덮어 테스트마다 쿼리를 바꾸고, navigateTo는 setup.ts에 없어 여기서 넣는다.
let routeQuery: Record<string, string> = {}
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  useRoute: () => ({ path: '/tasks', params: {}, query: routeQuery }),
  navigateTo: navigateToMock,
})

// $q.notify를 감싸는 플러그인 — Quasar 다음에 설치돼 마운트 전부터 알림을 잡는다. 알림 DOM은 테스트가 끝나도 몇 초 남고
// 같은 문구는 하나로 묶이므로, "이 테스트가 이 알림을 냈는가"는 DOM이 아니라 이 기록으로 본다(알림은 그대로 그려진다).
const notifySpy = vi.fn()
const recordNotify = {
  install(app: App) {
    const $q = app.config.globalProperties.$q as { notify: (opts: unknown) => unknown }
    const show = $q.notify
    $q.notify = (opts: unknown) => {
      notifySpy(opts)
      return show(opts)
    }
  },
}

// QPage requires QLayout > QPageContainer ancestry; wrap for testing.
const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

// 카탈로그: 1=대상(Netis7.0, 기본 브랜치 main) · 2=옛앱 · 3=공통 UI — 5(인증 서버)는 없다
const CATALOG = [
  { id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'acme/netis', defaultBranch: 'main' },
  { id: 2, alias: '옛앱', host: 'gitlab', ownerRepo: null, defaultBranch: 'release/6.5' },
  { id: 3, alias: '공통 UI', host: 'gitlab', ownerRepo: null, defaultBranch: 'main' },
]
// 질문의 참조(서버 값) — 대화에서 이미 받아 커밋이 고정된 것과, 카탈로그에서 빠진 것
const REF_PINNED = {
  catalogId: 2,
  alias: '옛앱',
  branch: 'release/6.5',
  sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  fetchedAt: '2026-10-22T05:30:00Z',
  error: null,
}
const REF_GONE = { catalogId: 5, alias: '인증 서버', branch: 'develop', sha: null, fetchedAt: null, error: null }

const QUESTION = {
  id: 3,
  title: '옛앱 결재선 비교',
  githubRepo: 'acme/netis',
  githubBranch: 'release/7.0',
  statusName: 'AWAITING_INPUT',
  model: 'claude-opus-5-5',
  effort: 'high',
  totalCostUsd: 0.42,
  contextTokens: null,
  contextWindow: null,
  repoCatalogId: 1,
  refRepos: [REF_PINNED],
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '옛앱은 결재선을 …' }],
}
const BRANCHES = {
  repo: 'acme/netis',
  defaultBranch: 'main',
  branches: [{ name: 'main', sha: 'a' }, { name: 'release/7.0', sha: 'b' }],
  fetchedAt: '2026-10-24T00:00:00Z',
}

const TARGET_MISSING = '질문의 대상 레포를 카탈로그에서 찾을 수 없습니다 — 레포를 골라 주세요'
const branchMissing = (b: string) => `질문의 브랜치 ${b}를 찾을 수 없습니다 — 브랜치를 골라 주세요`

interface PageVm {
  draft: {
    repoCatalogId: number | null
    githubBranch: string
    title: string
    description: string
    refRepos: Array<{ catalogId: number; alias: string; branch: string; sha?: string | null }>
  }
  showCreate: boolean
  source: { id: number; title: string } | null
  submit: () => Promise<void>
}

type Wrapper = ReturnType<typeof mount>

let current: Wrapper | null = null
beforeEach(() => {
  routeQuery = {}
  navigateToMock.mockReset()
  notifySpy.mockReset()
})
afterEach(() => {
  current?.unmount()
  current = null
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

interface ApiOverrides {
  question?: () => Promise<unknown>
  branches?: () => Promise<unknown>
}

function stubApi(o: ApiOverrides = {}) {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/questions/3') return o.question ? o.question() : Promise.resolve({ ...QUESTION })
    if (url === '/api/repo-catalog') return Promise.resolve(CATALOG)
    if (url === '/api/repos/branches') return o.branches ? o.branches() : Promise.resolve(BRANCHES)
    if (url === '/api/tasks') return Promise.resolve({ content: [], totalElements: 0, totalPages: 0 })
    return Promise.resolve(null)
  })
}

/** 질문 응답을 바꾼다 */
const questionWith = (extra: Record<string, unknown>) => () => Promise.resolve({ ...QUESTION, ...extra })

async function mountPage(query: Record<string, string>, o: ApiOverrides = {}) {
  routeQuery = query
  stubApi(o)
  const w = mount(PageWrapper, { attachTo: document.body, global: { plugins: [recordNotify] } })
  current = w
  await flushPromises()
  return w
}

const vmOf = (w: Wrapper) => w.findComponent(TasksIndex).vm as unknown as PageVm
const apiCalls = (url: string) => useApiMock.mock.calls.filter((c) => c[0] === url)
const banner = () => document.querySelector('[data-test="source-question-banner"]')
const refChips = () => Array.from(document.querySelectorAll('[data-test="task-form-refs"] [data-test="ref-chip"]'))
const warned = (message: string) => expect(notifySpy).toHaveBeenCalledWith({ type: 'warning', message })

/** POST /api/tasks 호출의 body */
function postedBody() {
  const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks' && c[1]?.method === 'POST')
  expect(call).toBeTruthy()
  return call![1].body
}

describe('작업 등록 창 — 질문 대화에서 이어서 (스펙 2026-10-24 §7.2)', () => {
  it('fromQuestion이 없으면 질문을 읽지 않고 창도 열지 않으며 navigateTo도 안 부른다', async () => {
    const w = await mountPage({})
    expect(useApiMock.mock.calls.some((c) => String(c[0]).startsWith('/api/questions'))).toBe(false)
    expect(vmOf(w).showCreate).toBe(false)
    expect(document.querySelector('[data-test="repo-select"]')).toBeNull()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('fromQuestion이 양의 정수가 아니면 질문을 읽지 않고 쿼리만 뺀다', async () => {
    for (const fromQuestion of ['abc', '0', '-3', '2.5', '']) {
      useApiMock.mockClear()
      navigateToMock.mockReset()
      const w = await mountPage({ fromQuestion })
      expect(useApiMock.mock.calls.some((c) => String(c[0]).startsWith('/api/questions'))).toBe(false)
      expect(vmOf(w).showCreate).toBe(false)
      expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
      current?.unmount()
      current = null
    }
  })

  it('마운트 뒤 질문을 읽어 창을 열고 대상·참조·제목을 채우고 작업 상세는 비우며 쿼리를 뺀다', async () => {
    const w = await mountPage({ fromQuestion: '3' })
    const vm = vmOf(w)

    expect(apiCalls('/api/questions/3')).toHaveLength(1)
    expect(vm.showCreate).toBe(true)
    expect(document.querySelector('[data-test="repo-select"]')).not.toBeNull()
    expect(vm.draft.repoCatalogId).toBe(1)
    expect(vm.draft.githubBranch).toBe('release/7.0')
    expect(vm.draft.title).toBe('옛앱 결재선 비교')
    // 질문은 비교였고 작업은 이식이다 — 작업 상세는 요청자가 직접 쓴다(대화 전문은 첨부로 간다)
    expect(vm.draft.description).toBe('')
    expect(vm.source).toEqual({ id: 3, title: '옛앱 결재선 비교' })

    // 질문에서 고정된 참조도 작업에서는 아직 안 받은 대기 칩이다(커밋·시각·사유 없음)
    expect(vm.draft.refRepos).toEqual([{ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }])
    const chips = refChips()
    expect(chips).toHaveLength(1)
    expect(chips[0]!.getAttribute('data-state')).toBe('pending')
    expect(chips[0]!.textContent).toContain('📚 옛앱 · release/6.5')
    expect(chips[0]!.textContent).not.toContain('@a1b2c3d')
    expect(chips[0]!.textContent).toContain('인터뷰 때 받음')

    // 새로고침해도 다시 열리지 않게 주소에서 쿼리를 뺀다
    expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
    expect(notifySpy).not.toHaveBeenCalled()
  })

  it('질문을 읽는 사이 다른 화면으로 떠나도 /tasks로 끌고 오지 않는다 — 쿼리는 질문을 읽기 전에 뺀다', async () => {
    let answerQuestion: (v: unknown) => void = () => {}
    const w = await mountPage(
      { fromQuestion: '3' },
      { question: () => new Promise((resolve) => (answerQuestion = resolve)) },
    )
    // 질문 응답이 오기 전에 이미 쿼리를 뺐다(한 번)
    expect(apiCalls('/api/questions/3')).toHaveLength(1)
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })

    // 사용자가 다른 화면으로 떠났다(페이지가 내려간다) — 그 뒤에 질문 응답이 와도 다시 이동하지 않는다
    w.unmount()
    current = null
    answerQuestion({ ...QUESTION })
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledTimes(1)
  })

  it('브랜치는 목록이 온 뒤 질문 브랜치로 — 카탈로그 기본(main)이 덮지 않는다', async () => {
    let answerBranches: (v: unknown) => void = () => {}
    const w = await mountPage(
      { fromQuestion: '3' },
      { branches: () => new Promise((resolve) => (answerBranches = resolve)) },
    )
    const vm = vmOf(w)
    expect(vm.draft.repoCatalogId).toBe(1)
    expect(vm.draft.githubBranch).toBe('') // 목록이 오기 전에는 비어 있다

    answerBranches(BRANCHES)
    await flushPromises()
    expect(vm.draft.githubBranch).toBe('release/7.0')
    expect(notifySpy).not.toHaveBeenCalled()
  })

  it.each([
    {
      name: '목록에 없음',
      branch: 'release/7.0',
      branches: () => Promise.resolve({ ...BRANCHES, branches: [{ name: 'main', sha: 'a' }] }),
    },
    {
      name: '조회 실패',
      branch: 'release/7.1',
      branches: () => Promise.reject({ statusCode: 502, data: { message: 'GitLab 응답 없음' } }),
    },
  ])('질문 브랜치를 못 쓰면 비우고 알린다 — $name', async ({ branch, branches }) => {
    const w = await mountPage({ fromQuestion: '3' }, { question: questionWith({ githubBranch: branch }), branches })
    const vm = vmOf(w)
    expect(vm.draft.repoCatalogId).toBe(1)
    // 기본 브랜치로 조용히 바꾸지 않는다
    expect(vm.draft.githubBranch).toBe('')
    warned(branchMissing(branch))
    // 알림은 실제로 그려진다(경고 색)
    const note = Array.from(document.querySelectorAll('.q-notification')).find((n) =>
      n.textContent?.includes(branchMissing(branch)),
    )
    expect(note).toBeTruthy()
    expect(note!.classList.contains('bg-warning')).toBe(true)
  })

  it('카탈로그에 없는 참조는 빼고 알린다', async () => {
    const w = await mountPage({ fromQuestion: '3' }, { question: questionWith({ refRepos: [REF_GONE, REF_PINNED] }) })
    expect(vmOf(w).draft.refRepos).toEqual([{ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }])
    expect(refChips()).toHaveLength(1)
    warned('카탈로그에 없는 참조는 뺐습니다: 인증 서버(develop)')
  })

  it.each([
    { name: 'repoCatalogId 없음', repoCatalogId: null },
    { name: '카탈로그에 없음', repoCatalogId: 9 },
  ])('대상을 비우고 알린다 — $name', async ({ repoCatalogId }) => {
    const w = await mountPage({ fromQuestion: '3' }, { question: questionWith({ repoCatalogId }) })
    const vm = vmOf(w)
    expect(vm.showCreate).toBe(true)
    expect(vm.draft.repoCatalogId).toBeNull()
    expect(vm.draft.githubBranch).toBe('')
    expect(apiCalls('/api/repos/branches')).toHaveLength(0)
    // 대상만 다시 고르면 된다 — 제목·참조·출처는 채운다
    expect(vm.draft.title).toBe('옛앱 결재선 비교')
    expect(vm.draft.refRepos.map((r) => r.catalogId)).toEqual([2])
    expect(vm.source).toEqual({ id: 3, title: '옛앱 결재선 비교' })
    warned(TARGET_MISSING)
  })

  it('안내 띠 문구와 등록 바디(JSON·multipart meta)에 sourceQuestionId·refRepos', async () => {
    const w = await mountPage({ fromQuestion: '3' })
    const vm = vmOf(w)

    // 창 맨 위(제목 바로 아래, 레포 선택 위)
    const b = banner()!
    expect(b).not.toBeNull()
    expect(b.textContent).toContain('질문 #3 "옛앱 결재선 비교"에서 이어짐 — 대화 전문이 첨부됩니다')
    const dialogText = document.querySelector('.q-dialog')!.textContent!
    expect(dialogText.indexOf('작업 등록')).toBeLessThan(dialogText.indexOf('질문 #3'))
    expect(b.compareDocumentPosition(document.querySelector('[data-test="repo-select"]')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    vm.draft.description = '옛앱의 결재선 계산을 새 앱으로 옮긴다'
    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks', {
      method: 'POST',
      body: {
        repoCatalogId: 1,
        githubBranch: 'release/7.0',
        title: '옛앱 결재선 비교',
        description: '옛앱의 결재선 계산을 새 앱으로 옮긴다',
        refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }],
        sourceQuestionId: 3,
      },
    })

    // 파일을 함께 보내면 meta JSON에 같은 필드가 실린다 — 등록이 끝나면 창이 닫히므로 다시 들어와서
    w.unmount()
    current = null
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
    const again = await mountPage({ fromQuestion: '3' })
    const vm2 = vmOf(again)
    vm2.draft.description = '옛앱의 결재선 계산을 새 앱으로 옮긴다'
    again.findComponent(QFile).vm.$emit('update:modelValue', [new File(['x'], '요구사항.txt', { type: 'text/plain' })])
    await flushPromises()
    useApiMock.mockClear()
    await vm2.submit()
    await flushPromises()
    const form = postedBody() as FormData
    expect(form).toBeInstanceOf(FormData)
    expect(JSON.parse(await (form.get('meta') as Blob).text())).toEqual({
      repoCatalogId: 1,
      githubBranch: 'release/7.0',
      title: '옛앱 결재선 비교',
      description: '옛앱의 결재선 계산을 새 앱으로 옮긴다',
      refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }],
      sourceQuestionId: 3,
    })
  })

  it('안내 띠 ✕는 출처만 뺀다 — 채운 값은 그대로, 바디에 sourceQuestionId 없음', async () => {
    const w = await mountPage({ fromQuestion: '3' })
    const vm = vmOf(w)
    const clear = document.querySelector<HTMLElement>('[data-test="source-question-clear"]')!
    expect(clear.getAttribute('aria-label')).toBe('출처 연결 빼기')
    clear.click()
    await flushPromises()

    expect(banner()).toBeNull()
    expect(vm.source).toBeNull()
    expect(vm.showCreate).toBe(true)
    expect(vm.draft.repoCatalogId).toBe(1)
    expect(vm.draft.githubBranch).toBe('release/7.0')
    expect(vm.draft.title).toBe('옛앱 결재선 비교')
    expect(vm.draft.refRepos.map((r) => r.catalogId)).toEqual([2])

    vm.draft.description = '이식'
    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()
    const body = postedBody()
    expect(body).not.toHaveProperty('sourceQuestionId')
    expect(body.refRepos).toEqual([{ repoCatalogId: 2, branch: 'release/6.5' }])
  })

  it.each([
    {
      name: '403',
      error: { statusCode: 403, data: { message: '이 질문을 볼 권한이 없습니다' } },
      message: '이 질문을 볼 권한이 없습니다',
    },
    { name: '서버 메시지 없음', error: new Error('network'), message: '질문을 불러오지 못했습니다' },
  ])('질문을 못 읽으면($name) 서버 메시지로 알리고 창을 열지 않으며 쿼리는 뺀다', async ({ error, message }) => {
    const w = await mountPage({ fromQuestion: '3' }, { question: () => Promise.reject(error) })
    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message })
    expect(vmOf(w).showCreate).toBe(false)
    expect(vmOf(w).source).toBeNull()
    expect(apiCalls('/api/repo-catalog')).toHaveLength(0)
    expect(navigateToMock).toHaveBeenCalledWith({ path: '/tasks', query: {} }, { replace: true })
  })

  it('작업 등록 버튼으로 다시 열면 출처·참조가 비워진다', async () => {
    const w = await mountPage({ fromQuestion: '3' })
    const vm = vmOf(w)
    expect(banner()).not.toBeNull()

    const cancel = Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.trim() === '취소')
    cancel!.click()
    await flushPromises()

    const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
    await addBtn!.trigger('click')
    await flushPromises()

    expect(vm.showCreate).toBe(true)
    expect(vm.source).toBeNull()
    expect(banner()).toBeNull()
    expect(vm.draft.refRepos).toEqual([])
    expect(refChips()).toHaveLength(0)
    expect(vm.draft.repoCatalogId).toBeNull()
    expect(vm.draft.title).toBe('')

    useApiMock.mockClear()
    Object.assign(vm.draft, { repoCatalogId: 1, githubBranch: 'main', title: '새 작업', description: '설명' })
    await vm.submit()
    await flushPromises()
    expect(postedBody()).not.toHaveProperty('sourceQuestionId')
  })

  describe('질문 첨부 승계 안내', () => {
    const att = (id: number, fileName: string) => ({ id, fileName, contentType: 'text/plain', sizeBytes: 10 })
    const notice = () => document.querySelector('[data-test="inherited-attachments"]')
    const toggle = () => document.querySelector('[data-test="inherited-attachments-toggle"]') as HTMLElement | null
    const names = () =>
      Array.from(document.querySelectorAll('[data-test="inherited-attachment-name"]')).map((n) => n.textContent?.trim())

    it('첨부 3개(등록 1 + 턴 2)면 안내 한 줄이 보이고 목록은 접혀 있다가 펼치면 이름 3개가 순서대로', async () => {
      await mountPage(
        { fromQuestion: '3' },
        {
          question: questionWith({
            attachments: [att(1, '요구.txt')],
            turns: [
              { seq: 1, role: 'user', kind: 'question', content: 'a', attachments: [att(2, '화면.png')] },
              { seq: 2, role: 'assistant', kind: 'answer', content: 'b' },
              { seq: 3, role: 'user', kind: 'question', content: 'c', attachments: [att(3, '로그.txt')] },
            ],
          }),
        },
      )
      expect(notice()!.textContent).toContain('질문 첨부 3개가 함께 등록됩니다')
      expect(names()).toEqual([])
      toggle()!.click()
      await flushPromises()
      expect(names()).toEqual(['요구.txt', '화면.png', '로그.txt'])
      // 출처 안내 띠 바로 아래
      expect(banner()!.compareDocumentPosition(notice()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })

    it('첨부가 없으면 안내가 없다', async () => {
      await mountPage({ fromQuestion: '3' })
      expect(notice()).toBeNull()
    })
  })
})
