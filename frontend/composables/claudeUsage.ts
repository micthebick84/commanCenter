// GET /api/usage/claude (Java ClaudeUsageResponse) 계약 미러 + 표시 규칙 SSOT (스펙 2026-09-05 §2·§3).
export interface ClaudeLimit {
  limitType: string
  status: string
  /** 0..1 분수 (러너가 정규화) */
  utilization: number
  resetsAt: string | null
  usingOverage: boolean
  reportedBy: string
  updatedAt: string
}

export interface ClaudeUsageResponse {
  limits: ClaudeLimit[]
}

export const LIMIT_LABELS: Record<string, string> = {
  five_hour: '현재 세션 (5시간)',
  seven_day: '이번 주 (모든 모델)',
  seven_day_opus: '이번 주 (Opus)',
  seven_day_sonnet: '이번 주 (Sonnet)',
}

/** 표시 순서. overage는 패널에 그리지 않는다. */
export const LIMIT_ORDER = ['five_hour', 'seven_day', 'seven_day_opus', 'seven_day_sonnet']

/** 세션/주간/컨텍스트 공통 임계 색 (Quasar 팔레트 이름). */
export function usageColor(pct: number): 'primary' | 'warning' | 'negative' {
  if (pct >= 90) return 'negative'
  if (pct >= 75) return 'warning'
  return 'primary'
}

/** 0..1 → 0..100 정수. resetsAt이 지났으면 0 (창이 초기화됨 — 다음 이벤트까지 서버 값은 stale). */
export function usagePercent(
  limit: Pick<ClaudeLimit, 'utilization' | 'resetsAt'>,
  now = Date.now(),
): number {
  if (limit.resetsAt && new Date(limit.resetsAt).getTime() <= now) return 0
  return Math.max(0, Math.min(100, Math.round(limit.utilization * 100)))
}

const WEEKDAYS_KO = ['일', '월', '화', '수', '목', '금', '토']

/**
 * "오전 7시" / "오후 3시 30분" — 로컬 시간대 기준. Intl(ko-KR)의 오전/오후 표기는 ICU 버전에 따라 "AM/PM"으로
 * 바뀌어(Node 24: "AM 7시") 테스트·브라우저 간 결과가 갈리므로 직접 조립한다 (실측 보정 R6, 2026-09-05).
 */
function formatKoTime(t: Date): string {
  const h = t.getHours()
  const period = h < 12 ? '오전' : '오후'
  const h12 = h % 12 === 0 ? 12 : h % 12
  const m = t.getMinutes()
  return m ? `${period} ${h12}시 ${String(m).padStart(2, '0')}분` : `${period} ${h12}시`
}

/**
 * "오전 7시 초기화" / "9월 8일 (화) 오전 9시 초기화" / 경과 → "초기화됨 · 다음 사용 시 갱신" / 없음·파싱 실패 → "".
 * 시각·날짜는 브라우저 로컬 시간대(formatKoTime — Intl 미사용).
 */
export function formatReset(resetsAt: string | null | undefined, now = Date.now()): string {
  if (!resetsAt) return ''
  const t = new Date(resetsAt)
  if (Number.isNaN(t.getTime())) return ''
  if (t.getTime() <= now) return '초기화됨 · 다음 사용 시 갱신'
  const time = formatKoTime(t)
  const n = new Date(now)
  const sameDay =
    t.getFullYear() === n.getFullYear() && t.getMonth() === n.getMonth() && t.getDate() === n.getDate()
  if (sameDay) return `${time} 초기화`
  return `${t.getMonth() + 1}월 ${t.getDate()}일 (${WEEKDAYS_KO[t.getDay()]}) ${time} 초기화`
}

/** "방금 갱신" / "3분 전 갱신" / "2시간 전 갱신" / "2일 전 갱신" / 없음 → "". */
export function formatAgo(updatedAt: string | null | undefined, now = Date.now()): string {
  if (!updatedAt) return ''
  const diff = now - new Date(updatedAt).getTime()
  if (!Number.isFinite(diff) || diff < 60_000) return '방금 갱신'
  const min = Math.floor(diff / 60_000)
  if (min < 60) return `${min}분 전 갱신`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h}시간 전 갱신`
  return `${Math.floor(h / 24)}일 전 갱신`
}
