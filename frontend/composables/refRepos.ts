// 질문 세션 읽기 전용 참조 repo (스펙 2026-10-22 §7.1) — API 계약 미러 + 칩 라벨·상태·집합 비교 순수 함수.
// 화면 쪽 조각(RefRepoChips·RefRepoAddForm)이 공유하고, 새 질문·대화 페이지가 등록·ask 바디를 만들 때 쓴다.

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
