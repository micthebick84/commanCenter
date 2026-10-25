// 읽기 전용 참조 repo (질문 스펙 2026-10-22 §7.1, 작업 스펙 2026-10-24 §7) — API 계약 미러 + 칩 라벨·상태·집합 비교 순수 함수,
// 맥락별 칩 문구(refChipCopy), 편집 초안(useRefDraft), 후보 카탈로그 지연 로더(useRefCatalog), 재시도 창의 준비 실패 참조
// 판정(prepFailedRefIds). 화면 쪽 조각(RefRepoChips·RefRepoAddForm)이 공유하고, 질문 화면·작업 등록 창·승인 다이얼로그 등이
// 등록·ask·승인·재시도 바디를 만들 때 쓴다.
import type { Ref } from 'vue'

/** 참조 repo 상한 — API `RepoCatalogService.MAX_REF_REPOS`와 같은 값이어야 한다. */
export const MAX_REF_REPOS = 3

/**
 * `GET /api/questions/{id}`의 `refRepos[]` 원소 (Java InterviewResponse). 추가 폼이 내보내는 값도 같은 모양이다(커밋·시각·사유 없이).
 * `sha`는 처음 받은 커밋(40자, 한 번 정해지면 그 참조가 빠질 때까지 불변), `fetchedAt`은 그 시각(ISO-8601),
 * `error`는 마지막 준비 실패 사유(성공하면 서버가 비운다). 셋 다 아직 안 받았으면 null이거나 없다.
 */
export interface RefRepoView {
  catalogId: number
  alias: string
  branch: string
  sha?: string | null
  fetchedAt?: string | null
  error?: string | null
}

/** 등록·ask 바디의 `refRepos[]` 원소 (Java RefRepoInput). */
export interface RefRepoInput {
  repoCatalogId: number
  branch: string
}

/** `GET /api/repo-catalog` 원소 중 참조 후보 판단에 쓰는 부분. `host`가 'other'면 지원하지 않는 호스트. */
export interface RepoCatalogOption {
  id: number
  alias: string
  host: string
  defaultBranch: string | null
}

export type RefChipState = 'pinned' | 'pending' | 'failed'

/** 칩에 보이는 커밋 길이 — 툴팁의 "고정 커밋"도 같은 길이로 쓴다. */
const SHA_DISPLAY_LENGTH = 7

/** 커밋 앞 7자. 칩 라벨과 툴팁이 같은 길이를 쓰도록 한 곳에서 자른다. */
export function refShortSha(sha: string): string {
  return sha.slice(0, SHA_DISPLAY_LENGTH)
}

/** 칩 라벨 `📚 별칭 · 브랜치`. 커밋이 있으면 `@앞 7자`를 뒤에 붙인다. */
export function refChipLabel(r: RefRepoView): string {
  const base = `📚 ${r.alias} · ${r.branch}`
  return r.sha ? `${base}@${refShortSha(r.sha)}` : base
}

/**
 * 칩 상태. 실패 사유(`error`)가 있으면 커밋이 있어도 failed(성공하면 서버가 error를 비우므로 error가 더 최근 사실),
 * 아니면 커밋이 있으면 pinned, 둘 다 없으면 아직 안 받은 pending. 빈 문자열은 없는 값으로 본다.
 */
export function refState(r: RefRepoView): RefChipState {
  if (r.error) return 'failed'
  if (r.sha) return 'pinned'
  return 'pending'
}

/** 화면 값 → 등록·ask 바디 원소. 커밋·시각·사유는 서버가 관리하므로 보내지 않는다. */
export function toInputs(refs: RefRepoView[]): RefRepoInput[] {
  return refs.map((r) => ({ repoCatalogId: r.catalogId, branch: r.branch }))
}

// 레포 id는 숫자라 `:` 앞뒤가 섞일 수 없다 — 브랜치 이름에 무엇이 들어 있어도 키가 겹치지 않는다.
const pairKey = (r: RefRepoInput) => `${r.repoCatalogId}:${r.branch}`

/**
 * 두 선택이 같은 집합인가 — (레포, 브랜치) 짝이 순서와 무관하게 같으면 true. 같은 레포라도 브랜치가 다르면 다른 짝이다.
 * 서버(`QuestionService.applyRefChange`)도 같은 원소를 두 번 가리키는 입력을 "같은 집합"으로 보지 않으므로
 * 중복은 개수까지 맞아야 같다. 대화 화면이 ask 바디에 `refRepos`를 실을지(변경 있음) 생략할지(유지) 정하는 데 쓴다.
 */
export function sameRefSet(a: RefRepoInput[], b: RefRepoInput[]): boolean {
  if (a.length !== b.length) return false
  const counts = new Map<string, number>()
  for (const r of a) counts.set(pairKey(r), (counts.get(pairKey(r)) ?? 0) + 1)
  for (const r of b) {
    const left = counts.get(pairKey(r))
    if (!left) return false
    counts.set(pairKey(r), left - 1)
  }
  return true
}

/**
 * 칩이 그려지는 자리 (작업 스펙 2026-10-24 §7.1). 질문 대화(1단계)와 작업의 세 시점 — 등록·승인(인터뷰 전),
 * 인터뷰 중(답변마다 다시 받음), 계획 확정 뒤(구현이 고정 커밋을 씀) — 은 같은 칩 상태라도 할 말이 다르다.
 */
export type RefChipContext = 'question' | 'task-setup' | 'task-interview' | 'task-fixed'

/**
 * 맥락별 칩 문구. `pendingNote`는 아직 안 받은 칩 안의 안내, `pinnedBasis`는 고정 칩 툴팁 끝("고정 커밋 … — {여기}"),
 * `failedNote`는 실패 칩 툴팁 마지막 줄. 그 맥락에 그 상태의 칩이 그려질 일이 없거나 덧붙일 말이 없으면 null이고 그 줄을 그리지 않는다.
 */
export interface RefChipCopy {
  pendingNote: string | null
  pinnedBasis: string
  failedNote: string | null
}

// 작업에서는 인터뷰가 처음 받은 커밋을 구현(재시도 포함)까지 쓴다(스펙 §2 "커밋 고정")
const TASK_PINNED_BASIS = '구현까지 이 커밋 기준'

// 스펙 §7.1 표. 등록·승인(task-setup)은 아직 아무것도 받지 않았으므로 고정·실패 칩이 생기지 않는다 —
// pinnedBasis는 타입상 늘 문자열이라 작업 문구를 그대로 둔다. 확정 뒤(task-fixed)에는 다시 받지 않으므로 대기·실패 안내가 없다.
const REF_CHIP_COPY: Record<RefChipContext, RefChipCopy> = {
  question: {
    pendingNote: '다음 답변 때 받음',
    pinnedBasis: '대화 끝까지 이 커밋 기준',
    failedNote: '이 참조 없이 답했습니다 — 다음 질문을 보낼 때 다시 받아 봅니다',
  },
  'task-setup': { pendingNote: '인터뷰 때 받음', pinnedBasis: TASK_PINNED_BASIS, failedNote: null },
  'task-interview': {
    pendingNote: '다음 답변 때 받음',
    pinnedBasis: TASK_PINNED_BASIS,
    failedNote: '이 참조 없이 진행했습니다 — 다음 답변 때 다시 받아 봅니다',
  },
  'task-fixed': { pendingNote: null, pinnedBasis: TASK_PINNED_BASIS, failedNote: null },
}

/** 맥락의 칩 문구 — 사본을 돌려준다(받은 쪽이 고쳐도 표는 그대로). */
export function refChipCopy(context: RefChipContext): RefChipCopy {
  return { ...REF_CHIP_COPY[context] }
}

/**
 * 참조 편집 초안 — 편집 칩 줄(RefRepoChips editable)과 그 아래 인라인 추가 폼(RefRepoAddForm)을 잇는 상태 한 벌.
 * 작업 등록 창·승인 다이얼로그·인터뷰 중 참조 변경 메뉴가 같이 쓴다(새 질문 화면 B안과 같은 동작).
 *  - `refs`: 초안 목록. 쓰기 computed여도 된다 — reactive 초안의 필드(`draft.refRepos`)나 v-model(`modelValue` → `update:modelValue`).
 *    바꿀 때는 늘 새 배열을 대입하고 원래 배열은 고치지 않으므로 부모의 props 배열을 직접 건드리지 않는다.
 *  - `targetCatalogId`: 대상 레포 id(없으면 null·undefined). 추가 폼 후보에서는 대상과 이미 고른 참조를 뺀다(`excludeIds`) —
 *    서버도 같은 규칙으로 검증한다. 지원하지 않는 호스트(other)는 폼이 스스로 뺀다.
 * 카탈로그를 언제 불러올지는 쓰는 쪽이 정한다(등록 창은 창을 열 때 이미 받는다).
 */
export function useRefDraft(refs: Ref<RefRepoView[]>, targetCatalogId: () => number | null | undefined) {
  const addOpen = ref(false)
  const excludeIds = computed(() => {
    const target = targetCatalogId()
    return [...(target != null ? [target] : []), ...refs.value.map((r) => r.catalogId)]
  })

  /** "＋ 참조 추가" — 폼을 펼치고, 한 번 더 누르면 접는다. */
  function toggleAdd() {
    addOpen.value = !addOpen.value
  }

  /** 추가 폼이 고른 참조를 끝에 붙이고 폼을 접는다. */
  function add(picked: RefRepoView) {
    refs.value = [...refs.value, picked]
    addOpen.value = false
  }

  /**
   * 그 레포의 참조를 뺀다. 대상 레포를 바꿀 때마다 새 대상 id로 부르므로(그 레포가 참조에 있으면 빠진다)
   * 뺄 것이 없으면(null 포함) 목록을 다시 쓰지 않는다 — v-model이면 쓸데없는 update가 나가지 않게.
   */
  function remove(catalogId: number | null) {
    if (!refs.value.some((r) => r.catalogId === catalogId)) return
    refs.value = refs.value.filter((r) => r.catalogId !== catalogId)
  }

  /** 창을 열 때 — 주어진 목록의 사본(없으면 빈 목록)으로 채우고 폼을 접는다. 초안을 고쳐도 원본(서버 값)은 그대로다. */
  function reset(initial: RefRepoView[] = []) {
    refs.value = [...initial]
    addOpen.value = false
  }

  return { addOpen, excludeIds, toggleAdd, add, remove, reset }
}

/** 카탈로그를 못 받았을 때 서버 문구가 없으면 쓰는 알림. */
const CATALOG_LOAD_FAILED = '레포 목록을 불러오지 못했습니다'

/**
 * 참조 추가 후보 카탈로그(`GET /api/repo-catalog`) — 처음 필요할 때(승인 다이얼로그의 "＋ 참조 추가",
 * 인터뷰 중 참조 변경 메뉴의 @show) 한 번만 받는다. 창·화면을 여는 시점의 API 호출을 늘리지 않으려는 것
 * (질문 대화 화면 `loadRepoCatalogOnce`와 같은 동작). 받는 중에 다시 불러도 요청은 하나이고,
 * 실패하면 `onError`로 알린 뒤(서버 문구, 없으면 기본 문구) 다음에 부를 때 다시 시도한다.
 * 지원하지 않는 호스트(other)는 추가 폼이 스스로 뺀다.
 */
export function useRefCatalog(onError: (message: string) => void) {
  const catalog = ref<RepoCatalogOption[]>([])
  let loaded = false
  let loading = false

  async function loadOnce() {
    if (loaded || loading) return
    loading = true
    try {
      const res = await useApi<RepoCatalogOption[]>('/api/repo-catalog')
      catalog.value = Array.isArray(res) ? res : []
      loaded = true
    } catch (e) {
      onError((e as { data?: { message?: string } })?.data?.message ?? CATALOG_LOAD_FAILED)
    } finally {
      loading = false
    }
  }

  return { catalog, loadOnce }
}

/**
 * 구현 재시도 창이 미리 고를 참조 (작업 스펙 2026-10-24 §7.5b) — 실패 사유가 워커의 참조 준비 실패
 * `참조 repo 준비 실패: {별칭}({브랜치}) — {사유}`(RefRepoPrompts)이면 그 참조의 catalogId.
 * 별칭·브랜치 뒤의 ` — `까지 맞춰야 이름 앞부분만 같은 다른 참조(옛앱 / 옛앱2)를 고르지 않는다.
 * 사유가 없거나 다른 실패면 빈 목록.
 */
export function prepFailedRefIds(reason: string | null | undefined, refs: RefRepoView[]): number[] {
  if (!reason) return []
  return refs
    .filter((r) => reason.startsWith(`참조 repo 준비 실패: ${r.alias}(${r.branch}) — `))
    .map((r) => r.catalogId)
}
