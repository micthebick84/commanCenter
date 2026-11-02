// 워커 헬스 "Claude OAuth" 칩 — 하트비트의 claude 로그인 판정(스펙 2026-11-02 §6).
// 판정 자체는 워커 ClaudeAuthProbe·인터뷰 서비스 claudeAuth.ts가 하고, 화면은 만료 임박만 더 가른다.

/** 만료까지 이만큼 이하로 남으면 임박 — 워커 ClaudeAuthProbe.EXPIRING_SOON과 같은 값. */
export const CLAUDE_AUTH_EXPIRING_SOON_MS = 3 * 24 * 60 * 60 * 1000

export type ClaudeAuthLevel = 'ok' | 'expiring' | 'bad' | 'unknown'

export interface ClaudeAuthFields {
  claudeSessionOk: boolean | null
  claudeAuthMethod?: string | null
  claudeAuthExpiresAt?: string | null
}

export interface ClaudeAuthView {
  level: ClaudeAuthLevel
  /** 칩 글자 — OK / N일 남음 / N시간 남음 / 만료 / NG / — */
  label: string
  color: string
  textColor: string
  /** 'CLI 로그인' | 'setup-token' | 'CLI 확인' | null */
  methodLabel: string | null
  /** 'MM-DD HH:mm까지' | 'MM-DD HH:mm 만료됨' | null (로컬 시각) */
  expiresLabel: string | null
  /** 빨강·주황일 때 할 일, 그 밖에는 null */
  action: string | null
}

const METHOD_LABELS: Record<string, string> = {
  ENV_TOKEN: 'setup-token',
  CREDENTIALS: 'CLI 로그인',
  CLI: 'CLI 확인',
}

const COLORS: Record<ClaudeAuthLevel, [string, string]> = {
  ok: ['green-2', 'green-9'],
  expiring: ['orange-2', 'orange-10'],
  bad: ['red-2', 'red-9'],
  unknown: ['grey-3', 'grey-8'],
}

const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function parseTime(iso: string | null | undefined): number | null {
  if (!iso) return null
  const t = new Date(iso).getTime()
  return Number.isNaN(t) ? null : t
}

function remainingLabel(ms: number): string {
  if (ms >= DAY_MS) return `${Math.floor(ms / DAY_MS)}일 남음`
  if (ms >= HOUR_MS) return `${Math.floor(ms / HOUR_MS)}시간 남음`
  return '곧 만료'
}

function actionFor(method: string | null | undefined): string {
  return method === 'ENV_TOKEN'
    ? 'worker.env의 CLAUDE_CODE_OAUTH_TOKEN을 새 setup-token으로 교체한 뒤 워커를 재기동하세요.'
    : '그 PC에서 claude auth login으로 다시 로그인하거나, 만료가 1년인 setup-token(claude setup-token → CLAUDE_CODE_OAUTH_TOKEN)으로 바꾸세요.'
}

export function claudeAuthView(w: ClaudeAuthFields, now: number = Date.now()): ClaudeAuthView {
  const expires = parseTime(w.claudeAuthExpiresAt)
  let level: ClaudeAuthLevel
  let label: string
  if (w.claudeSessionOk === null || w.claudeSessionOk === undefined) {
    level = 'unknown'
    label = '—'
  } else if (!w.claudeSessionOk) {
    level = 'bad'
    label = expires !== null && expires <= now ? '만료' : 'NG'
  } else if (expires !== null && expires - now <= CLAUDE_AUTH_EXPIRING_SOON_MS) {
    level = 'expiring'
    label = remainingLabel(expires - now)
  } else {
    level = 'ok'
    label = 'OK'
  }
  let expiresLabel: string | null = null
  if (expires !== null) {
    const d = new Date(expires)
    const at = `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    expiresLabel = expires <= now ? `${at} 만료됨` : `${at}까지`
  }
  const [color, textColor] = COLORS[level]
  return {
    level,
    label,
    color,
    textColor,
    methodLabel: (w.claudeAuthMethod && METHOD_LABELS[w.claudeAuthMethod]) || null,
    expiresLabel,
    action: level === 'bad' || level === 'expiring' ? actionFor(w.claudeAuthMethod) : null,
  }
}

/** 페이지 위 경고에 올릴 행 — 빨강 먼저, 그다음 주황(각각 받은 순서). */
export function claudeAuthAlerts<T extends ClaudeAuthFields & { workerId: string }>(
  rows: T[],
  now: number = Date.now(),
): { workerId: string; view: ClaudeAuthView }[] {
  const all = rows.map((r) => ({ workerId: r.workerId, view: claudeAuthView(r, now) }))
  return [...all.filter((a) => a.view.level === 'bad'), ...all.filter((a) => a.view.level === 'expiring')]
}
