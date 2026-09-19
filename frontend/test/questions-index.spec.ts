import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QFile } from 'quasar'
import QuestionsIndex from '../pages/questions/index.vue'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

// navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 여기서 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

function stubApi() {
  useApiMock.mockImplementation((url: string, opts?: { method?: string }) => {
    if (url === '/api/questions' && opts?.method === 'POST') return Promise.resolve({ id: 12 })
    if (url === '/api/repo-catalog') return Promise.resolve([{ id: 1, alias: 'Netis7.0', ownerRepo: 'a/b', defaultBranch: 'main' }])
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    return Promise.resolve(null)
  })
}

describe('pages/questions/index — 새 질문 입력창 (스펙 2026-09-05 §3·§6)', () => {
  beforeEach(() => {
    navigateToMock.mockReset()
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
        { id: 1, alias: 'A', ownerRepo: 'org/a', defaultBranch: 'main' },
        { id: 2, alias: 'B', ownerRepo: 'org/b', defaultBranch: 'develop' },
      ])
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      if (url === '/api/repos/branches' && opts?.params?.repo === 'org/a') return pendingA
      if (url === '/api/repos/branches' && opts?.params?.repo === 'org/b') return pendingB
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
})
