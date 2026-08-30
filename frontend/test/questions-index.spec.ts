import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import QuestionsIndex from '../pages/questions/index.vue'
import { useApiMock } from './mocks/nuxt'

// navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 여기서 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

// QPage requires QLayout > QPageContainer ancestry; wrap for testing.
const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(QuestionsIndex) }),
      })
  },
})

const list = [
  {
    id: 3, title: '인증 흐름', githubRepo: 'a/b', githubBranch: 'main', repoAlias: 'Netis7.0',
    requesterId: 'user1', status: '인터뷰대기', statusName: 'QUEUED', model: 'claude-sonnet-5',
    effort: 'medium', totalCostUsd: 0.4, createdAt: '2026-08-30T01:00:00Z', updatedAt: '2026-08-30T01:00:00Z',
  },
]

function stubApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
    if (url === '/api/questions') return Promise.resolve(list)
    if (url === '/api/repo-catalog') return Promise.resolve([{ id: 1, alias: 'Netis7.0', ownerRepo: 'a/b', defaultBranch: 'main' }])
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    return Promise.resolve(null)
  })
}

describe('pages/questions/index — 목록 + 질문하기 (스펙 2026-08-30 §7)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
    stubApi()
  })

  it('내 질문 목록을 질문 문맥 라벨로 렌더하고 클릭 시 상세로 이동한다', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', { params: { all: 'false' } })
    expect(w.text()).toContain('인증 흐름')
    expect(w.text()).toContain('답변 대기중')
    expect(w.text()).toContain('Netis7.0')
    await w.find('[data-test="question-row"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/questions/3')
    w.unmount()
  })

  it('질문하기: 모델/effort/MCP를 포함해 POST 후 상세로 이동', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    const page = w.findComponent(QuestionsIndex)
    const vm = page.vm as any
    vm.openCreate()
    await flushPromises()
    Object.assign(vm.draft, {
      repoCatalogId: 1, githubBranch: 'dev', title: '인증 흐름', question: '로그인은 어디서?',
      model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
    })
    await vm.submit()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', {
      method: 'POST',
      body: {
        repoCatalogId: 1, githubBranch: 'dev', title: '인증 흐름', question: '로그인은 어디서?',
        model: 'claude-sonnet-5', effort: 'medium', mcpCatalogIds: [9],
      },
    })
    expect(navigateToMock).toHaveBeenCalledWith('/questions/12')
    w.unmount()
  })

  it('429는 경고 토스트 (등록 실패로 처리하지 않음)', async () => {
    const w = mount(PageWrapper)
    await flushPromises()
    const vm = w.findComponent(QuestionsIndex).vm as any
    vm.openCreate()
    Object.assign(vm.draft, { repoCatalogId: 1, githubBranch: 'main', title: 't', question: 'q' })
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
      if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
      if (url === '/api/questions') return Promise.resolve(list)
      if (url === '/api/repo-catalog') return Promise.resolve([
        { id: 1, alias: 'A', ownerRepo: 'org/a', defaultBranch: 'main' },
        { id: 2, alias: 'B', ownerRepo: 'org/b', defaultBranch: 'develop' },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/repos/branches' && opts?.params?.repo === 'org/a') return pendingA
      if (url === '/api/repos/branches' && opts?.params?.repo === 'org/b') return pendingB
      return Promise.resolve(null)
    })

    const w = mount(PageWrapper)
    await flushPromises()
    const vm = w.findComponent(QuestionsIndex).vm as any
    vm.openCreate()
    await flushPromises()

    vm.onRepoSelected(1) // 레포 A 선택 — 브랜치 조회 진행 중
    vm.onRepoSelected(2) // 곧바로 레포 B로 전환 — inflightRepo가 org/b로 바뀜
    await flushPromises()

    // B의 응답이 먼저 도착
    resolveB({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'y' }] })
    await flushPromises()
    // A의 응답이 뒤늦게 도착 — 가드가 없으면 이 콜백이 draft.githubBranch를 'main'으로 덮어쓴다
    resolveA({ defaultBranch: 'main', branches: [{ name: 'main', sha: 'x' }] })
    await flushPromises()

    expect(vm.draft.githubBranch).toBe('develop')
    w.unmount()
  })
})
