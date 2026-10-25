import { describe, it, expect } from 'vitest'
import { computed, reactive, ref } from 'vue'
import {
  MAX_REF_REPOS,
  prepFailedRefIds,
  refChipCopy,
  refChipLabel,
  refState,
  sameRefSet,
  toInputs,
  useRefCatalog,
  useRefDraft,
  type RefRepoInput,
  type RefRepoView,
  type RepoCatalogOption,
} from './refRepos'
import { useApiMock } from '../test/mocks/nuxt'

const view = (over: Partial<RefRepoView> = {}): RefRepoView => ({
  catalogId: 1,
  alias: '옛앱',
  branch: 'main',
  ...over,
})
const input = (repoCatalogId: number, branch: string): RefRepoInput => ({ repoCatalogId, branch })

describe('MAX_REF_REPOS', () => {
  it('3이다 (API RepoCatalogService.MAX_REF_REPOS와 같은 값)', () => {
    expect(MAX_REF_REPOS).toBe(3)
  })
})

describe('refChipLabel', () => {
  it('커밋이 있으면 브랜치 뒤에 @앞 7자를 붙인다', () => {
    expect(refChipLabel({ catalogId: 1, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5' })).toBe(
      '📚 옛앱 · release/6.5@a1b2c3d',
    )
    // 40자 전체가 와도 7자만
    expect(refChipLabel(view({ sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678' }))).toBe('📚 옛앱 · main@a1b2c3d')
  })

  it('커밋이 없으면 별칭 · 브랜치만', () => {
    expect(refChipLabel({ catalogId: 1, alias: '옛앱', branch: 'main' })).toBe('📚 옛앱 · main')
    expect(refChipLabel(view({ sha: null }))).toBe('📚 옛앱 · main')
    expect(refChipLabel(view({ sha: '' }))).toBe('📚 옛앱 · main') // 빈 문자열도 "없음"
  })

  it('7자보다 짧은 커밋은 있는 만큼만', () => {
    expect(refChipLabel(view({ sha: 'abc' }))).toBe('📚 옛앱 · main@abc')
  })

  it('실패한 참조도 커밋이 있으면 라벨에 붙는다 (상태는 refState가 따로 판정)', () => {
    expect(refChipLabel(view({ sha: 'a1b2c3d4e5', error: '권한 없음' }))).toBe('📚 옛앱 · main@a1b2c3d')
  })
})

describe('refState', () => {
  it('error가 있으면 sha가 있어도 failed (error 우선)', () => {
    expect(refState({ catalogId: 1, alias: 'a', branch: 'b', sha: 'x', error: '권한 없음' })).toBe('failed')
    expect(refState(view({ sha: null, error: 'fatal: Remote branch b not found' }))).toBe('failed')
  })

  it('error가 없고 sha가 있으면 pinned', () => {
    expect(refState(view({ sha: 'a1b2c3d4e5' }))).toBe('pinned')
    expect(refState(view({ sha: 'a1b2c3d4e5', error: null }))).toBe('pinned')
  })

  it('둘 다 없으면 pending (아직 안 받음)', () => {
    expect(refState(view())).toBe('pending')
    expect(refState(view({ sha: null, error: null, fetchedAt: null }))).toBe('pending')
  })

  it('빈 문자열 error·sha는 없는 값으로 본다', () => {
    expect(refState(view({ sha: 'a1b2c3d', error: '' }))).toBe('pinned')
    expect(refState(view({ sha: '', error: '' }))).toBe('pending')
  })
})

describe('toInputs', () => {
  it('catalogId를 repoCatalogId로, 브랜치는 그대로 — 커밋·시각·사유는 보내지 않는다', () => {
    const refs: RefRepoView[] = [
      { catalogId: 12, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5', fetchedAt: '2026-10-22T05:30:00Z', error: null },
      { catalogId: 7, alias: '공통', branch: 'main', sha: null, fetchedAt: null, error: '권한 없음' },
    ]
    expect(toInputs(refs)).toEqual([
      { repoCatalogId: 12, branch: 'release/6.5' },
      { repoCatalogId: 7, branch: 'main' },
    ])
    // 정확히 두 키만 (ask·등록 바디에 sha 등이 새지 않는다)
    expect(Object.keys(toInputs(refs)[0]!).sort()).toEqual(['branch', 'repoCatalogId'])
  })

  it('입력 순서를 유지하고 빈 목록은 빈 목록', () => {
    expect(toInputs([view({ catalogId: 3 }), view({ catalogId: 1 })]).map((i) => i.repoCatalogId)).toEqual([3, 1])
    expect(toInputs([])).toEqual([])
  })
})

describe('sameRefSet', () => {
  it('순서가 달라도 같은 짝이면 true', () => {
    expect(sameRefSet([input(1, 'a'), input(2, 'b')], [input(2, 'b'), input(1, 'a')])).toBe(true)
  })

  it('같은 레포라도 브랜치가 다르면 false (브랜치는 구분한다)', () => {
    expect(sameRefSet([input(1, 'a')], [input(1, 'b')])).toBe(false)
    expect(sameRefSet([input(1, 'a'), input(2, 'b')], [input(1, 'a'), input(2, 'c')])).toBe(false)
  })

  it('레포가 다르면 false', () => {
    expect(sameRefSet([input(1, 'a')], [input(2, 'a')])).toBe(false)
  })

  it('개수가 다르면 false (한쪽이 부분집합이어도)', () => {
    expect(sameRefSet([input(1, 'a')], [input(1, 'a'), input(2, 'b')])).toBe(false)
    expect(sameRefSet([input(1, 'a'), input(2, 'b')], [input(1, 'a')])).toBe(false)
    expect(sameRefSet([], [input(1, 'a')])).toBe(false)
  })

  it('둘 다 비면 true', () => {
    expect(sameRefSet([], [])).toBe(true)
  })

  it('중복 짝은 개수까지 맞아야 같다 (서버도 같은 원소를 두 번 가리키는 입력을 같은 집합으로 보지 않는다)', () => {
    expect(sameRefSet([input(1, 'a'), input(1, 'a')], [input(1, 'a'), input(1, 'a')])).toBe(true)
    expect(sameRefSet([input(1, 'a'), input(1, 'a')], [input(1, 'a'), input(2, 'b')])).toBe(false)
    expect(sameRefSet([input(1, 'a'), input(1, 'a'), input(2, 'b')], [input(1, 'a'), input(2, 'b'), input(2, 'b')])).toBe(false)
  })

  it('레포 id와 브랜치 이름이 맞붙어 같은 글자가 돼도 다른 짝이다', () => {
    expect(sameRefSet([input(1, '2b')], [input(12, 'b')])).toBe(false)
    expect(sameRefSet([input(1, '2:b'), input(3, 'x')], [input(12, ':b'), input(3, 'x')])).toBe(false)
  })

  it('입력 배열을 바꾸지 않는다', () => {
    const a = [input(2, 'b'), input(1, 'a')]
    const b = [input(1, 'a'), input(2, 'b')]
    sameRefSet(a, b)
    expect(a).toEqual([input(2, 'b'), input(1, 'a')])
    expect(b).toEqual([input(1, 'a'), input(2, 'b')])
  })
})

// 칩 맥락 문구 (스펙 2026-10-24 §7.1) — 표에서 '—'인 칸은 그 맥락에 그릴 일이 없어 null(그 줄을 그리지 않는다)
describe('refChipCopy', () => {
  it('question(기본)은 1단계 문구 그대로', () => {
    expect(refChipCopy('question')).toEqual({
      pendingNote: '다음 답변 때 받음',
      pinnedBasis: '대화 끝까지 이 커밋 기준',
      failedNote: '이 참조 없이 답했습니다 — 다음 질문을 보낼 때 다시 받아 봅니다',
    })
  })

  it('task-setup: 대기 칩 "인터뷰 때 받음", 실패 줄 없음', () => {
    expect(refChipCopy('task-setup')).toEqual({
      pendingNote: '인터뷰 때 받음',
      pinnedBasis: '구현까지 이 커밋 기준',
      failedNote: null,
    })
  })

  it('task-interview: 다음 답변 때 받음 · 구현까지 · 진행 문구', () => {
    expect(refChipCopy('task-interview')).toEqual({
      pendingNote: '다음 답변 때 받음',
      pinnedBasis: '구현까지 이 커밋 기준',
      failedNote: '이 참조 없이 진행했습니다 — 다음 답변 때 다시 받아 봅니다',
    })
  })

  it('task-fixed: 대기 안내·실패 줄 없음', () => {
    expect(refChipCopy('task-fixed')).toEqual({
      pendingNote: null,
      pinnedBasis: '구현까지 이 커밋 기준',
      failedNote: null,
    })
  })

  it('돌려준 값을 고쳐도 다음 호출은 원래 문구다', () => {
    const copy = refChipCopy('question')
    copy.pendingNote = '바뀜'
    expect(refChipCopy('question').pendingNote).toBe('다음 답변 때 받음')
  })
})

// 참조 편집 초안 — 칩 줄(편집)과 인라인 추가 폼을 잇는 상태 한 벌. 작업 등록 창·승인 다이얼로그·인터뷰 중 참조 변경 메뉴가 같이 쓴다.
describe('useRefDraft', () => {
  const OLD = view({ catalogId: 2, alias: '옛앱', branch: 'release/6.5' })
  const UI = view({ catalogId: 3, alias: '공통 UI', branch: 'main' })

  it('추가 폼에서 뺄 id = 대상 + 고른 참조 — 대상이 없으면 고른 참조만, 둘이 바뀌면 따라간다', () => {
    const refs = ref<RefRepoView[]>([OLD])
    const target = ref<number | null>(1)
    const d = useRefDraft(refs, () => target.value)
    expect(d.excludeIds.value).toEqual([1, 2])

    target.value = null
    expect(d.excludeIds.value).toEqual([2])

    refs.value = [OLD, UI]
    target.value = 7
    expect(d.excludeIds.value).toEqual([7, 2, 3])
  })

  it('대상 id가 undefined여도 대상 없음으로 본다', () => {
    const d = useRefDraft(ref<RefRepoView[]>([UI]), () => undefined)
    expect(d.excludeIds.value).toEqual([3])
  })

  it('＋ 참조 추가는 폼을 펼치고 한 번 더 누르면 접는다 (처음엔 접혀 있다)', () => {
    const d = useRefDraft(ref<RefRepoView[]>([]), () => 1)
    expect(d.addOpen.value).toBe(false)
    d.toggleAdd()
    expect(d.addOpen.value).toBe(true)
    d.toggleAdd()
    expect(d.addOpen.value).toBe(false)
  })

  it('추가하면 끝에 붙인 새 배열로 바꾸고(원래 배열은 그대로) 폼을 접는다', () => {
    const original = [OLD]
    const refs = ref<RefRepoView[]>(original)
    const d = useRefDraft(refs, () => 1)
    d.toggleAdd()
    d.add(UI)
    expect(refs.value).toEqual([OLD, UI])
    expect(original).toEqual([OLD]) // v-model 부모의 props 배열을 직접 고치지 않는다
    expect(d.addOpen.value).toBe(false)
  })

  it('빼기는 그 catalogId만 뺀다 — 없는 id·null이면 목록을 다시 쓰지 않는다', () => {
    const writes: RefRepoView[][] = []
    const backing = ref<RefRepoView[]>([OLD, UI])
    const refs = computed<RefRepoView[]>({
      get: () => backing.value,
      set: (v) => {
        writes.push(v)
        backing.value = v
      },
    })
    const d = useRefDraft(refs, () => 1)

    d.remove(9)
    d.remove(null)
    expect(writes).toEqual([]) // 대상 레포를 바꿀 때마다 부르므로, 뺄 것이 없으면 조용히 지나간다

    d.remove(2)
    expect(writes).toEqual([[UI]])
    expect(backing.value).toEqual([UI])
  })

  it('reset은 주어진 목록의 사본으로 채우고(없으면 빈 목록) 폼을 접는다', () => {
    const refs = ref<RefRepoView[]>([OLD])
    const d = useRefDraft(refs, () => 1)
    d.toggleAdd()
    d.reset()
    expect(refs.value).toEqual([])
    expect(d.addOpen.value).toBe(false)

    const server = [OLD, UI]
    d.toggleAdd()
    d.reset(server)
    expect(refs.value).toEqual([OLD, UI])
    expect(refs.value).not.toBe(server) // 초안을 고쳐도 서버 값(작업의 refRepos)이 따라 바뀌지 않는다
    expect(d.addOpen.value).toBe(false)
  })

  it('reactive 초안의 필드에도 붙는다 (쓰기 computed)', () => {
    const draft = reactive({ repoCatalogId: 1 as number | null, refRepos: [] as RefRepoView[] })
    const d = useRefDraft(
      computed<RefRepoView[]>({ get: () => draft.refRepos, set: (v) => (draft.refRepos = v) }),
      () => draft.repoCatalogId,
    )
    d.add(OLD)
    d.add(UI)
    expect(draft.refRepos).toEqual([OLD, UI])
    expect(d.excludeIds.value).toEqual([1, 2, 3])
    d.remove(2)
    expect(draft.refRepos).toEqual([UI])
    draft.repoCatalogId = null
    expect(d.excludeIds.value).toEqual([3])
  })
})

// 구현 재시도 창이 미리 고를 참조 (작업 스펙 2026-10-24 §7.5b) — 워커의 준비 실패 사유는
// `참조 repo 준비 실패: {별칭}({브랜치}) — {사유}`(RefRepoPrompts)라 별칭·브랜치 뒤의 ` — `까지 맞아야 그 참조다.
describe('prepFailedRefIds', () => {
  const OLD = view({ catalogId: 2, alias: '옛앱', branch: 'release/6.5', sha: 'a'.repeat(40) })
  const UI = view({ catalogId: 3, alias: '공통 UI', branch: 'main', sha: 'b'.repeat(40) })

  it('prepFailedRefIds: 사유의 별칭(브랜치)와 맞는 참조만', () => {
    const reason = '참조 repo 준비 실패: 옛앱(release/6.5) — fatal: could not read Username for \'https://gitlab.example\''
    expect(prepFailedRefIds(reason, [OLD, UI])).toEqual([2])
    expect(prepFailedRefIds('참조 repo 준비 실패: 공통 UI(main) — 시간 초과', [OLD, UI])).toEqual([3])
  })

  it('prepFailedRefIds: 브랜치가 다르거나 다른 사유·null이면 []', () => {
    // 같은 별칭이라도 브랜치가 다르면 다른 참조다
    expect(prepFailedRefIds('참조 repo 준비 실패: 옛앱(main) — fatal: x', [OLD, UI])).toEqual([])
    // 별칭이 앞부분만 같은 다른 레포(옛앱2)는 고르지 않는다 — 괄호·` — `까지 맞춘다
    expect(prepFailedRefIds('참조 repo 준비 실패: 옛앱2(release/6.5) — fatal: x', [OLD, UI])).toEqual([])
    // 사유 앞머리가 다르면(구현 실패·시간 초과 등) 아무것도 고르지 않는다
    expect(prepFailedRefIds('claude exec 실패: 옛앱(release/6.5) — fatal: x', [OLD, UI])).toEqual([])
    expect(prepFailedRefIds('시간 초과: 참조 repo 준비에 2700초를 써 구현 상한(45분)이 남지 않았습니다', [OLD, UI])).toEqual([])
    expect(prepFailedRefIds(null, [OLD, UI])).toEqual([])
    expect(prepFailedRefIds(undefined, [OLD, UI])).toEqual([])
    expect(prepFailedRefIds('', [OLD, UI])).toEqual([])
    expect(prepFailedRefIds('참조 repo 준비 실패: 옛앱(release/6.5) — fatal: x', [])).toEqual([])
  })
})

// 참조 추가 후보 카탈로그 — 승인 다이얼로그(＋ 참조 추가)·인터뷰 중 참조 변경 메뉴(@show)가 처음 필요할 때 한 번만 받는다.
describe('useRefCatalog', () => {
  const CATALOG: RepoCatalogOption[] = [
    { id: 1, alias: 'Netis7.0', host: 'github', defaultBranch: 'main' },
    { id: 2, alias: '옛앱', host: 'gitlab', defaultBranch: 'release/6.5' },
  ]
  const catalogCalls = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/repo-catalog').length

  it('만들 때는 부르지 않고, 처음 loadOnce에서만 /api/repo-catalog를 부른다', async () => {
    useApiMock.mockResolvedValue(CATALOG)
    const errors: string[] = []
    const c = useRefCatalog((m) => errors.push(m))
    expect(c.catalog.value).toEqual([])
    expect(catalogCalls()).toBe(0)

    await c.loadOnce()
    await c.loadOnce()
    expect(catalogCalls()).toBe(1)
    expect(c.catalog.value).toEqual(CATALOG)
    expect(errors).toEqual([])
  })

  it('받는 중에 다시 불러도 요청은 하나다', async () => {
    let resolve!: (v: unknown) => void
    useApiMock.mockReturnValue(new Promise((r) => (resolve = r)))
    const c = useRefCatalog(() => {})
    const first = c.loadOnce()
    const second = c.loadOnce()
    resolve(CATALOG)
    await Promise.all([first, second])
    expect(catalogCalls()).toBe(1)
    expect(c.catalog.value).toEqual(CATALOG)
  })

  it('실패하면 서버 문구(없으면 기본 문구)로 알리고, 다음에 부르면 다시 시도한다', async () => {
    const errors: string[] = []
    const c = useRefCatalog((m) => errors.push(m))

    useApiMock.mockRejectedValueOnce({ data: { message: '권한이 없습니다' } })
    await c.loadOnce()
    useApiMock.mockRejectedValueOnce(new Error('network'))
    await c.loadOnce()
    expect(errors).toEqual(['권한이 없습니다', '레포 목록을 불러오지 못했습니다'])
    expect(c.catalog.value).toEqual([])

    useApiMock.mockResolvedValueOnce(CATALOG)
    await c.loadOnce()
    expect(c.catalog.value).toEqual(CATALOG)
    await c.loadOnce()
    expect(catalogCalls()).toBe(3) // 성공한 뒤로는 더 부르지 않는다
  })

  it('배열이 아닌 응답은 빈 목록으로 본다', async () => {
    useApiMock.mockResolvedValue({})
    const c = useRefCatalog(() => {})
    await c.loadOnce()
    expect(c.catalog.value).toEqual([])
  })
})
