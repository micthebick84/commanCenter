import type { DbConnectionView } from '~/composables/dbConnections'

export type RouteState = 'CONFIDENT' | 'AMBIGUOUS' | 'NO_MATCH' | 'UNAVAILABLE'

export interface QuestionRoute {
  routeId: number | null
  state: RouteState
  recommendedRepoCatalogId: number | null
  candidates: { repoCatalogId: number; probability: number }[]
  recentRepoCatalogIds: number[]
  /** null = 서버에 못 물어봄(화면 대체값) */
  eligibleRepoCatalogIds: number[] | null
  dbNeeded: boolean | null
}

export interface RouteCatalogEntry {
  id: number
  alias: string
  description: string | null
  host: string
  defaultBranch: string | null
}

/** 모호할 때 "비슷한 레포" 칩에 넣는 최소 확률(Task 1 측정 결과). */
export const SIMILAR_MIN_PROBABILITY = 0

/** 추천 호출을 기다리는 최대 시간(서버 상한 3초 + 여유) — 넘으면 UNAVAILABLE 카드. */
export const ROUTE_TIMEOUT_MS = 5000

const MAX_OTHERS = 2
const MAX_RECENT = 3
const MAX_SIMILAR = 3
/** 화면 DB 연결 기본 선택 상한 — pages/questions/index.vue의 MAX_DB_SELECTION과 같다. */
const MAX_DB_SELECTION = 3

const STATES: RouteState[] = ['CONFIDENT', 'AMBIGUOUS', 'NO_MATCH', 'UNAVAILABLE']

/** 새 객체를 돌려준다 — 호출자가 목록을 바꿔도 상수가 오염되지 않게. */
function unavailableRoute(): QuestionRoute {
  return {
    routeId: null,
    state: 'UNAVAILABLE',
    recommendedRepoCatalogId: null,
    candidates: [],
    recentRepoCatalogIds: [],
    eligibleRepoCatalogIds: null,
    dbNeeded: null,
  }
}

export const UNAVAILABLE_ROUTE: QuestionRoute = Object.freeze(unavailableRoute()) as QuestionRoute

/**
 * 질문 레포 추천 호출. 절대 던지지 않는다 — 네트워크 오류·모양 이상은 UNAVAILABLE 경로.
 */
export async function fetchQuestionRoute(question: string): Promise<QuestionRoute> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    // ofetch의 timeout은 요청을 끊고, 아래 경주는 그 옵션이 먹지 않는 경우에도 화면이 멈추지 않게 하는 안전망이다.
    const timedOut = new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('route timeout')), ROUTE_TIMEOUT_MS)
    })
    const call = useApi<Partial<QuestionRoute> | null>('/api/questions/route', {
      method: 'POST',
      body: { question },
      timeout: ROUTE_TIMEOUT_MS,
    })
    call.catch(() => {}) // 경주에서 진 호출의 늦은 실패가 처리되지 않은 거부로 남지 않게
    const res = await Promise.race([call, timedOut])
    if (!res || typeof res !== 'object') return unavailableRoute()
    if (!STATES.includes(res.state as RouteState)) return unavailableRoute()
    if (!Array.isArray(res.candidates) || !Array.isArray(res.recentRepoCatalogIds)) return unavailableRoute()
    if (res.eligibleRepoCatalogIds != null && !Array.isArray(res.eligibleRepoCatalogIds)) return unavailableRoute()
    return {
      routeId: typeof res.routeId === 'number' ? res.routeId : null,
      state: res.state as RouteState,
      recommendedRepoCatalogId:
        typeof res.recommendedRepoCatalogId === 'number' ? res.recommendedRepoCatalogId : null,
      candidates: res.candidates.map((c) => ({ repoCatalogId: c.repoCatalogId, probability: c.probability })),
      recentRepoCatalogIds: [...res.recentRepoCatalogIds],
      eligibleRepoCatalogIds: res.eligibleRepoCatalogIds ? [...res.eligibleRepoCatalogIds] : null,
      dbNeeded: typeof res.dbNeeded === 'boolean' ? res.dbNeeded : null,
    }
  } catch {
    return unavailableRoute()
  } finally {
    if (timer) clearTimeout(timer)
  }
}

export interface RouteCardModel {
  /** CONFIDENT만 */
  recommended: RouteCatalogEntry | null
  /** CONFIDENT: 추천 다음 후보 최대 2 */
  others: RouteCatalogEntry[]
  /** AMBIGUOUS·UNAVAILABLE: 최대 3 */
  recent: RouteCatalogEntry[]
  /** AMBIGUOUS: 후보 중 recent에 없고 확률 ≥ 하한, 최대 3 */
  similar: RouteCatalogEntry[]
  /** 등록 가능한 전체(별칭 순) */
  searchable: RouteCatalogEntry[]
}

export function routeCardModel(route: QuestionRoute, catalog: RouteCatalogEntry[]): RouteCardModel {
  const byId = new Map(catalog.map((e) => [e.id, e]))
  const eligibleSet = route.eligibleRepoCatalogIds ? new Set(route.eligibleRepoCatalogIds) : null
  const allowed = (e: RouteCatalogEntry) => (eligibleSet ? eligibleSet.has(e.id) : e.host !== 'other')
  // 카탈로그에 없거나(그새 꺼짐) 등록할 수 없는 id는 어느 목록에도 나오지 않는다.
  const pick = (id: number): RouteCatalogEntry | null => {
    const e = byId.get(id)
    return e && allowed(e) ? e : null
  }
  const pickAll = (list: number[]): RouteCatalogEntry[] => {
    const seen = new Set<number>()
    const out: RouteCatalogEntry[] = []
    for (const id of list) {
      const e = pick(id)
      if (e && !seen.has(e.id)) {
        seen.add(e.id)
        out.push(e)
      }
    }
    return out
  }

  const searchable = catalog.filter(allowed).sort((a, b) => a.alias.localeCompare(b.alias, 'ko'))
  const empty: RouteCardModel = { recommended: null, others: [], recent: [], similar: [], searchable }

  let state = route.state
  let recommended: RouteCatalogEntry | null = null
  if (state === 'CONFIDENT') {
    recommended = route.recommendedRepoCatalogId == null ? null : pick(route.recommendedRepoCatalogId)
    if (!recommended) state = 'AMBIGUOUS' // 추천이 그새 꺼졌다 — 칩 선택으로 내려간다
  }

  const sorted = [...route.candidates].sort((a, b) => b.probability - a.probability)

  switch (state) {
    case 'CONFIDENT': {
      const rec = recommended as RouteCatalogEntry
      const others = pickAll(sorted.map((c) => c.repoCatalogId).filter((id) => id !== rec.id)).slice(0, MAX_OTHERS)
      return { ...empty, recommended: rec, others }
    }
    case 'AMBIGUOUS': {
      const recent = pickAll(route.recentRepoCatalogIds).slice(0, MAX_RECENT)
      const recentIds = new Set(recent.map((e) => e.id))
      const similar = pickAll(
        sorted.filter((c) => c.probability >= SIMILAR_MIN_PROBABILITY).map((c) => c.repoCatalogId),
      )
        .filter((e) => !recentIds.has(e.id))
        .slice(0, MAX_SIMILAR)
      return { ...empty, recent, similar }
    }
    case 'UNAVAILABLE':
      return { ...empty, recent: pickAll(route.recentRepoCatalogIds).slice(0, MAX_RECENT) }
    default:
      return empty
  }
}

/** 별칭·설명 부분 일치(대소문자 무시). 빈 문자열이면 전체. */
export function searchRepos(list: RouteCatalogEntry[], text: string): RouteCatalogEntry[] {
  const q = text.trim().toLowerCase()
  if (!q) return list
  return list.filter((e) => e.alias.toLowerCase().includes(q) || (e.description ?? '').toLowerCase().includes(q))
}

/**
 * DB 연결 기본 선택. dbNeeded가 false면 없음, true면 공용(REPO) 뒤에 개인(USER), null이면 지금처럼 켜진 공용만.
 * 꺼진 연결은 어느 경우에도 제외, 같은 묶음 안에서는 원래 순서, 최대 3개.
 */
export function seedDbSelection(items: DbConnectionView[], dbNeeded: boolean | null): number[] {
  if (dbNeeded === false) return []
  const enabled = items.filter((c) => c.enabled)
  const repo = enabled.filter((c) => c.scope === 'REPO')
  const picked = dbNeeded === true ? [...repo, ...enabled.filter((c) => c.scope === 'USER')] : repo
  return picked.slice(0, MAX_DB_SELECTION).map((c) => c.id)
}
