// 인터뷰 목업(스펙 2026-11-03 인터뷰 목업 §6)의 화면 공용 순수 함수.
// 말풍선 카드·목록·새 탭 페이지·확정 체크 목록이 같은 규칙을 쓰도록 한 곳에 둔다.
// (간단 인터뷰의 interviewMode.ts와는 별개 파일이다.)

export interface MockupSummary {
  id: number
  n: number
  name: string
  version: number
  title: string
  description: string | null
  /** 묶인 턴의 seq. 아직 어느 턴에도 안 묶였으면 null. */
  turnSeq: number | null
  turnKind: 'question' | 'design' | null
  createdAt?: string
}

/** 목업 iframe에 넣는 CSP. 외부 요청을 모두 막고 인라인 스타일·스크립트와 data: 그림·글꼴만 허용한다. */
export const MOCKUP_CSP =
  "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:"

/** 확정 때 구현에 넘길 목업 상한. 서버 InterviewMockupService.MAX_CONFIRM과 같은 값이다. */
export const MAX_CONFIRM_MOCKUPS = 10

const CSP_META = `<meta http-equiv="Content-Security-Policy" content="${MOCKUP_CSP}">`
const DOCTYPE_RE = /^\s*<!doctype[^>]*>/i
const HTML_OPEN_RE = /^\s*<html/i

/**
 * 목업 HTML에 CSP meta를 어떤 외부 요청보다 먼저 넣은 srcdoc을 만든다.
 * doctype이 있으면 그 바로 뒤(앞에 두면 quirks 모드가 되어 배치가 바뀐다), `<html`로 시작하면 맨 앞,
 * 둘 다 아니면 뼈대로 감싼다.
 */
export function mockupSrcdoc(html: string): string {
  const doctype = DOCTYPE_RE.exec(html)
  if (doctype) {
    const end = doctype[0].length
    return html.slice(0, end) + CSP_META + html.slice(end)
  }
  if (HTML_OPEN_RE.test(html)) return CSP_META + html
  return `<!doctype html><html><head><meta charset="utf-8">${CSP_META}</head><body>${html}</body></html>`
}

/** 버전 2 이상이면 ` (v{version})`, 아니면 빈 문자열. 카드·목록·새 탭 라벨이 같은 규칙을 쓰도록 export한다. */
export function mockupVersionTail(version: number): string {
  return version >= 2 ? ` (v${version})` : ''
}

/** `목업 {n} · {title}` + 버전 꼬리. */
export function mockupLabel(m: Pick<MockupSummary, 'n' | 'title' | 'version'>): string {
  return `목업 ${m.n} · ${m.title}${mockupVersionTail(m.version)}`
}

/** 이름별 묶음 — 묶음은 가장 작은 n 순, 안에서는 version 내림차순(latest = 최신). */
export function groupMockups(
  list: MockupSummary[],
): { name: string; latest: MockupSummary; older: MockupSummary[] }[] {
  const byName = new Map<string, MockupSummary[]>()
  for (const m of list) {
    const bucket = byName.get(m.name)
    if (bucket) bucket.push(m)
    else byName.set(m.name, [m])
  }
  const groups = [...byName.entries()].map(([name, items]) => {
    const sorted = items.slice().sort((a, b) => b.version - a.version)
    return { name, latest: sorted[0], older: sorted.slice(1), minN: Math.min(...items.map((x) => x.n)) }
  })
  groups.sort((a, b) => a.minN - b.minN)
  return groups.map(({ name, latest, older }) => ({ name, latest, older }))
}

/** 계획 본문이 가리키는 목업 번호(`목업 3` 꼴, 중복 제거, 나온 순). */
export function planMockupNumbers(plan: string | null | undefined): number[] {
  if (!plan) return []
  const out: number[] = []
  for (const m of plan.matchAll(/목업 (\d+)/g)) {
    const n = Number(m[1])
    if (!out.includes(n)) out.push(n)
  }
  return out
}

/** 확정 체크 목록의 기본 선택 — 계획이 가리키는 번호 중 턴에 묶인 목업의 id, n 순, 앞 MAX_CONFIRM_MOCKUPS개. */
export function defaultMockupSelection(
  plan: string | null | undefined,
  list: MockupSummary[],
): number[] {
  const nums = planMockupNumbers(plan)
  return list
    .filter((m) => m.turnSeq != null && nums.includes(m.n))
    .sort((a, b) => a.n - b.n)
    .slice(0, MAX_CONFIRM_MOCKUPS)
    .map((m) => m.id)
}

/** 계획이 가리키지만 넘기지 않는 목업 번호 — 목록에 있고 고르지 않은 n, 오름차순. */
export function excludedPlanMockups(
  plan: string | null | undefined,
  list: MockupSummary[],
  selectedIds: number[],
): number[] {
  const nums = planMockupNumbers(plan)
  const out: number[] = []
  for (const m of list) {
    if (nums.includes(m.n) && !selectedIds.includes(m.id) && !out.includes(m.n)) out.push(m.n)
  }
  return out.sort((a, b) => a - b)
}

/** id로 바꿔 끼운다(없으면 덧붙임). 같은 목업이 올라올 때(turnSeq null)와 묶일 때 두 번 온다. */
export function upsertMockup(list: MockupSummary[], m: MockupSummary): MockupSummary[] {
  const idx = list.findIndex((x) => x.id === m.id)
  if (idx < 0) return [...list, m]
  const next = list.slice()
  next[idx] = m
  return next
}

/** 어느 턴에도 안 묶인 목업(끊긴 턴의 것)을 뺀다. */
export function dropUnbound(list: MockupSummary[]): MockupSummary[] {
  return list.filter((m) => m.turnSeq != null)
}
