// GET /api/usage/summary (Java UsageSummaryResponse)·/api/usage/subscription-fees 계약 미러 + 표시 규칙 SSOT
// (스펙 2026-11-03 §6·§7). 시각 문자열은 서울(+09:00)로 내려온다 — 표시는 문자열을 잘라 쓰고 Date로 로컬 변환하지 않는다
// (JVM·브라우저·CI의 시간대에 따라 표시가 달라지지 않게). 경과 계산(isStale)에만 Date를 쓴다.
export interface UsageTokens {
  input: number
  output: number
  cacheWrite5m: number
  cacheWrite1h: number
  cacheRead: number
}

export interface CategoryUsage {
  category: 'SYMPHONY' | 'DIRECT' | string
  tokens: UsageTokens
  cost: number | null
}

export interface ModelUsage {
  model: string
  tokens: UsageTokens
  /** 가격 미등록 모델이면 null */
  cost: number | null
}

export interface PreviousUsage {
  start: string
  until: string
  cost: number | null
  feeShare: number | null
  ratio: number | null
}

export interface SeriesPoint {
  at: string
  cost: number | null
}

export interface CollectorStatus {
  machine: string
  lastReportedAt: string
  lastEventAt: string | null
  unreadableLines: number
}

export interface UnpricedModel {
  model: string
  tokens: UsageTokens
}

export interface UsageCoverage {
  collectors: CollectorStatus[]
  earliestEventAt: string | null
  unpricedModels: UnpricedModel[]
}

export interface UsageSummary {
  period: UsagePeriodValue
  date: string
  start: string
  end: string
  countFrom: string
  inProgress: boolean
  tokens: UsageTokens
  cost: number | null
  byCategory: CategoryUsage[]
  byModel: ModelUsage[]
  feeConfigured: boolean
  feeMissing: boolean
  feeShare: number | null
  fullPeriodFeeShare: number | null
  ratio: number | null
  previous: PreviousUsage | null
  series: SeriesPoint[]
  coverage: UsageCoverage
}

export interface SubscriptionFee {
  id: number
  effectiveFrom: string
  monthlyFee: number
  currency: string
  createdBy: string | null
  createdAt: string
}

export type UsagePeriodValue = 'day' | 'week' | 'month'

export const PERIOD_OPTIONS: { label: string; value: UsagePeriodValue }[] = [
  { label: '일', value: 'day' },
  { label: '주', value: 'week' },
  { label: '월', value: 'month' },
]

export function summaryUrl(period: UsagePeriodValue, date?: string): string {
  return `/api/usage/summary?period=${period}${date ? `&date=${date}` : ''}`
}

export function csvUrl(period: UsagePeriodValue, date: string): string {
  return `/api/usage/summary.csv?period=${period}&date=${date}`
}

/** 100 이상 "$1,234", 미만 "$12.35", null "—". Intl은 ICU 버전에 따라 갈려 쓰지 않는다. */
export function formatUsd(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—'
  if (v >= 100) return `$${Math.round(v).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')}`
  return `$${v.toFixed(2)}`
}

/** "6.2배" / null. */
export function formatRatio(v: number | null | undefined): string | null {
  if (v == null || !Number.isFinite(v)) return null
  return `${v.toFixed(1)}배`
}

/** "1.23M" / "45.6K" / "789". */
export function formatTokens(n: number): string {
  if (n >= 999_950) return `${(n / 1_000_000).toFixed(2)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return String(n)
}

/** 다섯 칸의 합 — Java total()은 JSON에 실리지 않는다. */
export function totalTokens(t: UsageTokens | null | undefined): number {
  if (!t) return 0
  return (t.input ?? 0) + (t.output ?? 0) + (t.cacheWrite5m ?? 0) + (t.cacheWrite1h ?? 0) + (t.cacheRead ?? 0)
}

const MS_DAY = 86_400_000

function parseDate(d: string): number {
  return Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10)))
}

function fmtDate(ms: number): string {
  const t = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${t.getUTCFullYear()}-${p(t.getUTCMonth() + 1)}-${p(t.getUTCDate())}`
}

/** "오늘(지금까지)" / "2026-10-31" / "2026-10-26 ~ 2026-11-01" / "2026-10". */
export function periodLabel(s: Pick<UsageSummary, 'period' | 'inProgress' | 'start' | 'end'>): string {
  if (s.inProgress) {
    return s.period === 'day' ? '오늘(지금까지)' : s.period === 'week' ? '이번 주(지금까지)' : '이번 달(지금까지)'
  }
  if (s.period === 'month') return s.start.slice(0, 7)
  if (s.period === 'day') return s.start.slice(0, 10)
  // end는 배타적 경계(다음 주 월요일 0시) — 마지막 날은 하루 전이다.
  return `${s.start.slice(0, 10)} ~ ${fmtDate(parseDate(s.end) - MS_DAY)}`
}

/** 작업 목록 머리줄 칩 문구. */
export function chipText(s: UsageSummary | null | undefined): string {
  if (!s) return 'Claude 사용량 —'
  const head = `${periodLabel(s)} ${formatUsd(s.cost)}`
  const ratio = formatRatio(s.ratio)
  if (ratio) return `${head} · 구독료의 ${ratio}`
  if (!s.feeConfigured) return `${head} · 구독료를 입력하세요`
  if (s.feeMissing) return `${head} · 구독료 적용 전 기간 포함`
  return head
}

/** 기간 시작일을 한 기간만큼 옮긴다. UTC 달력으로만 계산해 시간대와 무관하다. */
export function shiftDate(period: UsagePeriodValue, startDate: string, dir: -1 | 1): string {
  const y = Number(startDate.slice(0, 4))
  const m = Number(startDate.slice(5, 7)) - 1
  const d = Number(startDate.slice(8, 10))
  if (period === 'month') return fmtDate(Date.UTC(y, m + dir, d))
  return fmtDate(Date.UTC(y, m, d + dir * (period === 'week' ? 7 : 1)))
}

/** 구독료 적용 시작일 기본값 — 가장 이른 기록이 있는 달의 1일, 없으면 세는 시작일의 달 1일. */
export function defaultFeeStart(s: Pick<UsageSummary, 'countFrom' | 'coverage'> | null | undefined): string {
  const base = s?.coverage?.earliestEventAt ?? s?.countFrom
  return base ? `${base.slice(0, 7)}-01` : ''
}

/** 마지막 보고가 minutes분보다 오래됐는지. 시각을 못 읽으면 false. */
export function isStale(lastReportedAt: string | null | undefined, now: number, minutes = 60): boolean {
  if (!lastReportedAt) return false
  const t = new Date(lastReportedAt).getTime()
  if (!Number.isFinite(t)) return false
  return now - t > minutes * 60_000
}

/** API 시각 문자열 → "YYYY-MM-DD HH:mm" (서울, 문자열 자르기). */
export function formatStamp(at: string | null | undefined): string {
  return at ? at.slice(0, 16).replace('T', ' ') : ''
}

/** API 오류 본문의 message, 없으면 fallback. */
export function apiErrorMessage(e: unknown, fallback: string): string {
  const m = (e as { data?: { message?: unknown } } | null)?.data?.message
  return typeof m === 'string' && m ? m : fallback
}
