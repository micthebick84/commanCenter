import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import { QSelect } from 'quasar'
import RefRepoAddForm from '../components/RefRepoAddForm.vue'
import type { RepoCatalogOption } from '../composables/refRepos'
import { useApiMock } from './mocks/nuxt'

// 1=대상 레포(제외) · 2=GitLab 구버전(카탈로그 기본 브랜치 있음) · 3=기본 브랜치 없음 · 4=other(지원 안 함) · 5=이미 고른 참조(제외)
const CATALOG: RepoCatalogOption[] = [
  { id: 1, alias: 'Netis7.0', host: 'github', defaultBranch: 'main' },
  { id: 2, alias: 'Netis6.5(구)', host: 'gitlab', defaultBranch: 'release/6.5' },
  { id: 3, alias: '공통 UI', host: 'github', defaultBranch: null },
  { id: 4, alias: 'Bitbucket', host: 'other', defaultBranch: null },
  { id: 5, alias: '배치 서버', host: 'gitlab', defaultBranch: 'master' },
]

type BranchesResponse = { repo?: string; defaultBranch: string | null; branches: Array<{ name: string; sha: string }> }
const BRANCHES: BranchesResponse = {
  repo: 'x/y',
  defaultBranch: 'develop',
  branches: [
    { name: 'develop', sha: 'a' },
    { name: 'main', sha: 'b' },
    { name: 'release/6.5', sha: 'c' },
  ],
}

function stubBranches(res: BranchesResponse = BRANCHES) {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/repos/branches') return Promise.resolve(res)
    return Promise.resolve(null)
  })
}

function mountForm(excludeIds: number[] = [1, 5]) {
  const w = mount(RefRepoAddForm, { props: { catalog: CATALOG, excludeIds } })
  const all = w.findAllComponents(QSelect)
  const byTest = (id: string) => all.find((c) => (c.vm.$attrs as Record<string, unknown>)['data-test'] === id)!
  return { w, repo: byTest('ref-add-repo'), branch: byTest('ref-add-branch') }
}

const optionValues = (sel: { props: (k: string) => unknown }) =>
  (sel.props('options') as Array<{ value: unknown }>).map((o) => o.value)

// Quasar가 폭 밖에서 display:none을 거는 반응형 표시 헬퍼 이름 — 클래스로 쓰면 그 폭에서 콘텐츠가 통째로 사라진다
const RESPONSIVE_HELPER = /^(xs|sm|md|lg|xl|(gt|lt)-(xs|sm|md|lg|xl)|.*-hide)$/
const classNamesOf = (root: Element): string[] =>
  [root, ...Array.from(root.querySelectorAll('*'))].flatMap((el) => Array.from(el.classList))

describe('RefRepoAddForm — 참조 추가 폼 (스펙 2026-10-22 §7.1)', () => {
  beforeEach(() => {
    stubBranches()
  })

  it('루트는 <div>다 (q-dialog·q-menu 안에 그대로 넣는 규칙)', () => {
    const { w } = mountForm()
    expect(w.element.tagName).toBe('DIV')
    expect(w.find('[data-test="ref-add-repo"]').exists()).toBe(true)
    expect(w.find('[data-test="ref-add-branch"]').exists()).toBe(true)
    expect(w.find('[data-test="ref-add-submit"]').exists()).toBe(true)
    w.unmount()
  })

  it('제외 목록과 other 호스트를 뺀 레포만 고를 수 있다', async () => {
    const { w, repo } = mountForm([1, 5])
    expect(optionValues(repo)).toEqual([2, 3]) // 1(대상)·5(이미 고름)·4(other) 빠짐
    expect((repo.props('options') as Array<{ label: string }>).map((o) => o.label)).toEqual(['Netis6.5(구)', '공통 UI'])

    // 제외 목록이 바뀌면 후보도 바뀐다 (대상 레포를 바꾸거나 참조를 뺀 경우)
    await w.setProps({ excludeIds: [1] })
    expect(optionValues(repo)).toEqual([2, 3, 5])
    await w.setProps({ excludeIds: [] })
    expect(optionValues(repo)).toEqual([1, 2, 3, 5])
    w.unmount()
  })

  it('레포를 고르면 브랜치를 불러와 카탈로그 기본, 없으면 응답 기본으로 채운다', async () => {
    const { w, repo, branch } = mountForm()
    // 처음에는 브랜치를 못 고른다
    expect(branch.props('disable')).toBe(true)
    expect(branch.props('modelValue')).toBe('')

    // 카탈로그 기본(release/6.5)이 있으면 그것 — 응답 기본(develop)보다 우선
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/repos/branches', { params: { catalogId: 2 } })
    expect(branch.props('disable')).toBe(false)
    expect(branch.props('modelValue')).toBe('release/6.5')
    expect(optionValues(branch)).toEqual(['develop', 'main', 'release/6.5'])
    // 레포의 실제 기본 브랜치는 목록에서 표시한다 (새 질문 화면과 같은 규칙)
    expect((branch.props('options') as Array<{ label: string }>)[0]!.label).toBe('develop (기본)')

    // 카탈로그 기본이 없으면 응답의 기본 브랜치
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/repos/branches', { params: { catalogId: 3 } })
    expect(branch.props('modelValue')).toBe('develop')
    w.unmount()
  })

  it('카탈로그 기본도 응답 기본도 없으면 첫 브랜치로 채운다', async () => {
    stubBranches({ defaultBranch: null, branches: [{ name: 'trunk', sha: 'a' }, { name: 'dev', sha: 'b' }] })
    const { w, repo, branch } = mountForm()
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()
    expect(branch.props('modelValue')).toBe('trunk')
    w.unmount()
  })

  it('레포를 바꾸면 이전 브랜치는 즉시 비워지고 새 목록이 올 때까지 추가가 막힌다', async () => {
    const { w, repo, branch } = mountForm()
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(branch.props('modelValue')).toBe('release/6.5')
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeUndefined()

    let resolveB!: (v: unknown) => void
    useApiMock.mockImplementation(() => new Promise((r) => { resolveB = r }))
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()
    expect(branch.props('modelValue')).toBe('') // A의 브랜치가 B에 남지 않는다
    expect(branch.props('loading')).toBe(true)
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeDefined()

    resolveB(BRANCHES)
    await flushPromises()
    expect(branch.props('loading')).toBe(false)
    expect(branch.props('modelValue')).toBe('develop')
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeUndefined()
    w.unmount()
  })

  it('먼저 고른 레포의 늦은 응답은 브랜치를 덮지 않는다', async () => {
    let resolveA!: (v: unknown) => void
    let resolveB!: (v: unknown) => void
    const pendingA = new Promise((r) => { resolveA = r })
    const pendingB = new Promise((r) => { resolveB = r })
    useApiMock.mockImplementation((url: string, opts?: { params?: { catalogId?: number } }) => {
      if (url === '/api/repos/branches' && opts?.params?.catalogId === 2) return pendingA
      if (url === '/api/repos/branches' && opts?.params?.catalogId === 3) return pendingB
      return Promise.resolve(null)
    })
    const { w, repo, branch } = mountForm()

    repo.vm.$emit('update:modelValue', 2) // A — 브랜치 조회 진행 중
    repo.vm.$emit('update:modelValue', 3) // 곧바로 B로 전환
    await flushPromises()

    resolveB({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'y' }] })
    await flushPromises()
    resolveA({ defaultBranch: 'main', branches: [{ name: 'main', sha: 'x' }] }) // 늦게 도착한 A
    await flushPromises()

    expect(branch.props('modelValue')).toBe('develop')
    expect(optionValues(branch)).toEqual(['develop']) // 목록도 B의 것
    expect(branch.props('loading')).toBe(false)

    await w.find('[data-test="ref-add-submit"]').trigger('click')
    expect(w.emitted('add')).toEqual([[{ catalogId: 3, alias: '공통 UI', branch: 'develop' }]])
    w.unmount()
  })

  it('먼저 고른 레포의 응답이 B보다 먼저 와도 B가 올 때까지 브랜치를 채우지 않는다', async () => {
    let resolveA!: (v: unknown) => void
    let resolveB!: (v: unknown) => void
    useApiMock.mockImplementation((url: string, opts?: { params?: { catalogId?: number } }) => {
      if (opts?.params?.catalogId === 2) return new Promise((r) => { resolveA = r })
      return new Promise((r) => { resolveB = r })
    })
    const { w, repo, branch } = mountForm()
    repo.vm.$emit('update:modelValue', 2)
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()

    resolveA({ defaultBranch: 'main', branches: [{ name: 'main', sha: 'x' }] })
    await flushPromises()
    expect(branch.props('modelValue')).toBe('') // A의 응답은 무시 — 카탈로그 기본(release/6.5)도 안 채움
    expect(branch.props('loading')).toBe(true) // B가 아직 진행 중이므로 로딩 표시도 유지
    expect(optionValues(branch)).toEqual([])

    resolveB({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'y' }] })
    await flushPromises()
    expect(branch.props('modelValue')).toBe('develop')
    w.unmount()
  })

  it('먼저 고른 레포의 늦은 실패는 사유를 띄우지 않고 B의 브랜치도 건드리지 않는다', async () => {
    let rejectA!: (e: unknown) => void
    useApiMock.mockImplementation((url: string, opts?: { params?: { catalogId?: number } }) => {
      if (opts?.params?.catalogId === 2) return new Promise((_, rej) => { rejectA = rej })
      return Promise.resolve({ defaultBranch: 'develop', branches: [{ name: 'develop', sha: 'y' }] })
    })
    const { w, repo, branch } = mountForm()
    repo.vm.$emit('update:modelValue', 2)
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()
    expect(branch.props('modelValue')).toBe('develop')

    rejectA({ statusCode: 404 })
    await flushPromises()
    expect(branch.props('modelValue')).toBe('develop')
    expect(w.text()).not.toContain('찾을 수 없')
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeUndefined()
    w.unmount()
  })

  it('브랜치를 못 불러오면 사유를 보여 주고 추가는 막힌다 (404는 접근 안내)', async () => {
    useApiMock.mockRejectedValue({ statusCode: 404 })
    const { w, repo, branch } = mountForm()
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(w.text()).toContain('레포를 찾을 수 없거나 접근 권한이 없습니다')
    expect(branch.props('modelValue')).toBe('')
    expect(branch.props('loading')).toBe(false)
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeDefined()
    await w.find('[data-test="ref-add-submit"]').trigger('click')
    expect(w.emitted('add')).toBeUndefined()

    // 서버가 사유를 주면 그 문구를, 다른 레포를 고르면 다시 불러온다
    useApiMock.mockRejectedValue({ statusCode: 502, data: { message: 'GitLab 연결 실패' } })
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()
    expect(w.text()).toContain('GitLab 연결 실패')
    expect(w.text()).not.toContain('접근 권한이 없습니다')

    stubBranches()
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(w.text()).not.toContain('GitLab 연결 실패')
    expect(branch.props('modelValue')).toBe('release/6.5')
    w.unmount()
  })

  it('추가를 누르면 catalogId·alias·branch로 add를 보낸다', async () => {
    const { w, repo, branch } = mountForm()
    // 레포를 고르기 전에는 누를 수 없다
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeDefined()
    await w.find('[data-test="ref-add-submit"]').trigger('click')
    expect(w.emitted('add')).toBeUndefined()

    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeUndefined()
    await w.find('[data-test="ref-add-submit"]').trigger('click')
    // 정확히 세 필드 — sha·fetchedAt·error는 서버가 채운다
    expect(w.emitted('add')).toEqual([[{ catalogId: 2, alias: 'Netis6.5(구)', branch: 'release/6.5' }]])

    // 사용자가 브랜치를 바꾸면 그 값으로
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()
    branch.vm.$emit('update:modelValue', 'main')
    await flushPromises()
    await w.find('[data-test="ref-add-submit"]').trigger('click')
    expect(w.emitted('add')!.at(-1)).toEqual([{ catalogId: 3, alias: '공통 UI', branch: 'main' }])
    w.unmount()
  })

  it('추가한 뒤에는 폼을 비워 같은 선택이 두 번 나가지 않는다', async () => {
    const { w, repo, branch } = mountForm()
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    await w.find('[data-test="ref-add-submit"]').trigger('click')
    expect(w.emitted('add')).toHaveLength(1)

    expect(repo.props('modelValue')).toBeNull()
    expect(branch.props('modelValue')).toBe('')
    expect(branch.props('disable')).toBe(true)
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeDefined()
    await w.find('[data-test="ref-add-submit"]').trigger('click')
    expect(w.emitted('add')).toHaveLength(1)
    w.unmount()
  })

  it('고른 레포가 제외 목록에 들어가면 선택을 비운다 (이미 고른 레포가 또 나가지 않게)', async () => {
    const { w, repo, branch } = mountForm([1])
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(branch.props('modelValue')).toBe('release/6.5')

    await w.setProps({ excludeIds: [1, 2] })
    await flushPromises()
    expect(repo.props('modelValue')).toBeNull()
    expect(branch.props('modelValue')).toBe('')
    expect(w.find('[data-test="ref-add-submit"]').attributes('disabled')).toBeDefined()

    // 다른 레포가 제외되는 경우에는 그대로
    repo.vm.$emit('update:modelValue', 3)
    await flushPromises()
    await w.setProps({ excludeIds: [1, 2, 5] })
    await flushPromises()
    expect(repo.props('modelValue')).toBe(3)
    expect(branch.props('modelValue')).toBe('develop')
    w.unmount()
  })

  it('제외 목록에 들어간 레포의 늦은 응답은 선택을 되살리지 않는다', async () => {
    let resolveA!: (v: unknown) => void
    useApiMock.mockImplementation(() => new Promise((r) => { resolveA = r }))
    const { w, repo, branch } = mountForm([1])
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    await w.setProps({ excludeIds: [1, 2] }) // 조회 중에 제외됨 → 선택 비움
    await flushPromises()
    resolveA(BRANCHES)
    await flushPromises()
    expect(repo.props('modelValue')).toBeNull()
    expect(branch.props('modelValue')).toBe('')
    expect(branch.props('loading')).toBe(false)
    w.unmount()
  })

  it('선택을 비우면(null) 브랜치도 비우고 조회 중이던 응답은 무시한다', async () => {
    let resolveA!: (v: unknown) => void
    useApiMock.mockImplementation(() => new Promise((r) => { resolveA = r }))
    const { w, repo, branch } = mountForm()
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    repo.vm.$emit('update:modelValue', null)
    await flushPromises()
    resolveA(BRANCHES)
    await flushPromises()
    expect(branch.props('modelValue')).toBe('')
    expect(branch.props('disable')).toBe(true)
    expect(branch.props('loading')).toBe(false)
    w.unmount()
  })

  it('카탈로그에 없는 id는 무시한다 (조회하지 않음)', async () => {
    const { w, repo, branch } = mountForm()
    useApiMock.mockClear()
    repo.vm.$emit('update:modelValue', 999)
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalled()
    expect(branch.props('disable')).toBe(true)
    w.unmount()
  })

  it('Quasar 반응형 헬퍼 이름(xs·sm·md·lg·xl, gt-*, lt-*, *-hide)을 클래스로 쓰지 않는다 (2026-09-06 ChatBubble 회귀 가드)', async () => {
    const { w, repo } = mountForm()
    repo.vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(classNamesOf(w.element).filter((c) => RESPONSIVE_HELPER.test(c))).toEqual([])
    w.unmount()
  })
})
