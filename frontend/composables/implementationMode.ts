// 구현 방식 (스펙 2026-10-15 §7) — 서버 enum 이름을 그대로 주고받는다. 화면 기본값은 리뷰.

export type ImplementationMode = 'DIRECT' | 'REVIEW' | 'SDD'

export const DEFAULT_IMPLEMENTATION_MODE: ImplementationMode = 'REVIEW'

export const IMPLEMENTATION_MODES: ReadonlyArray<{ value: ImplementationMode; label: string; description: string }> = [
  { value: 'DIRECT', label: '직접', description: '리뷰 없이 바로 구현 — 가장 빠르고 저렴' },
  { value: 'REVIEW', label: '리뷰', description: '구현 후 Opus 리뷰 1회, 중대 문제만 수정 — 구현 비용 약 1.6~1.8배(실측 2건)' },
  { value: 'SDD', label: 'SDD', description: '작업마다 서브에이전트가 구현·리뷰 — 가장 느리고 비쌈, 최대 4시간 워커 점유' },
]

export function implementationModeLabel(mode: string | null | undefined): string {
  return IMPLEMENTATION_MODES.find((m) => m.value === mode)?.label ?? '직접'
}

export function implementationModeDescription(mode: string | null | undefined): string {
  return IMPLEMENTATION_MODES.find((m) => m.value === mode)?.description ?? ''
}

// 플랜 확정(구현 진행) 이후 상태 — 이때부터 방식이 의미 있다.
const POST_CONFIRM_STATUSES = [
  'APPROVED', 'IMPLEMENTING', 'PR_CREATED', 'IMPLEMENTATION_FAILED',
  'DESIGN_PENDING', 'DESIGNING', 'DESIGN_REVIEW', 'DESIGN_FAILED',
  'DEPLOY_PENDING', 'DEPLOYING', 'DEPLOYED', 'DEPLOY_FAILED', 'DEPLOY_LOST', 'UNDEPLOY_PENDING', 'UNDEPLOYING',
  'CLOSE_REVIEW', 'CLOSE_PENDING', 'CLOSING', 'CLOSED', 'CLOSE_FAILED',
]

export function showsImplementationMode(status: string): boolean {
  return POST_CONFIRM_STATUSES.includes(status)
}

/**
 * "23분 경과 / 상한 80분" — 구현중이고 시작 시각이 있을 때만. 상한의 80% 이상이면 warn.
 * now가 null(SSR·마운트 전)이면 null — 시간에 따라 바뀌는 문구가 서버 HTML과 어긋나지 않게.
 */
export function elapsedLabel(
  task: { status: string; claimedAt?: string | null; implementationTimeoutMinutes?: number | null },
  now: number | null,
): { text: string; warn: boolean } | null {
  if (now == null || task.status !== 'IMPLEMENTING' || !task.claimedAt) return null
  const started = Date.parse(task.claimedAt)
  if (Number.isNaN(started)) return null
  const minutes = Math.max(0, Math.floor((now - started) / 60000))
  const limit = task.implementationTimeoutMinutes
  if (!limit) return { text: `${minutes}분 경과`, warn: false }
  return { text: `${minutes}분 경과 / 상한 ${limit}분`, warn: minutes >= limit * 0.8 }
}
