// 배포 뒤 수정 요청 (스펙 2026-11-03 §6·§7) — 화면 판정·타입·문구·새 작업 미리 채우기. 순수 함수만 둔다(화면 상태 없음).
import type { ImplementationMode } from './implementationMode'

/** `GET /api/tasks/{id}`의 `fix` — 목록·구 API 응답에는 없다(null/undefined). */
export interface FixView {
  /** 열린 수정 회차 번호. 열린 회차가 없으면 null. */
  activeRound: number | null
  /** 열린 회차의 구현 방식(DIRECT|REVIEW). 회차가 없으면 null. */
  activeMode: string | null
  /** 승인된 수정 요청 수 — 반려·철회는 세지 않는다. */
  approvedRounds: number
  /** 작업당 승인 한도(`app.task.fix-request-max-rounds`). */
  maxRounds: number
}

/** `GET /api/tasks/{id}/fix-requests` 한 건 (서버 `FixRequestView`). */
export interface FixRequestView {
  id: number
  /** 승인 때 확정된다 — 승인 전·반려·철회는 null. */
  round: number | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | string
  /** DIRECT|REVIEW. 승인 전에는 null. */
  implementationMode: string | null
  body: string
  requestedBy: string
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionReason: string | null
  attempt: number
  /** 회차가 끝난 결과 — 열려 있는 동안 null. */
  outcome: 'DEPLOYED' | 'DEPLOY_FAILED' | 'ABORTED' | string | null
  closedAt: string | null
}

/** 수정 회차에서 고를 수 있는 구현 방식 — SDD는 확정 플랜 전체를 다시 구현하게 되므로 없다(스펙 §6.2). 화면 순서: 리뷰(기본) → 직접. */
export const FIX_MODES: ImplementationMode[] = ['REVIEW', 'DIRECT']

/** 수정 요청을 낼 수 있는 작업 상태 (스펙 §6.1) — 배포완료·배포중단됨·배포실패. */
const FIX_REQUEST_STATUSES = ['DEPLOYED', 'DEPLOY_LOST', 'DEPLOY_FAILED']

interface FixTaskLike {
  id?: number
  status: string
  implementation?: { headBranch: string | null } | null
  fix?: FixView | null
}

/**
 * 수정 요청 버튼을 열어도 되는가 — 등록자 또는 관리자가 배포 계열 상태에서, 이어 갈 브랜치가 있고, 열린 회차가 없고, 상한 전일 때.
 * 회차 현황(`fix`)이 없는 응답은 한도를 알 수 없어 열지 않는다. 마지막 판단은 서버(409)가 한다.
 */
export function canRequestFix(task: FixTaskLike, isAdmin: boolean, isOwner: boolean): boolean {
  if (!(isAdmin || isOwner)) return false
  if (!FIX_REQUEST_STATUSES.includes(task.status)) return false
  if (!task.implementation?.headBranch) return false
  const fix = task.fix
  if (!fix) return false
  return fix.activeRound == null && fix.approvedRounds < fix.maxRounds
}

/** 승인된 수정 요청을 다 썼는가 — 버튼 대신 "새 작업으로 등록"을 보인다(스펙 §6.3). */
export function fixLimitReached(task: { fix?: FixView | null }): boolean {
  const fix = task.fix
  return !!fix && fix.approvedRounds >= fix.maxRounds
}

/**
 * "새 작업으로 등록"을 보일 때 (스펙 §6.3·§6.5) — 등록자·관리자에게, 상한을 다 쓴 배포 계열 작업이거나 이미 종료된 작업.
 * 종료된 작업은 브랜치가 병합·삭제돼 이어 고칠 수 없다.
 */
export function canFollowUp(task: { status: string; fix?: FixView | null }, isAdmin: boolean, isOwner: boolean): boolean {
  if (!(isAdmin || isOwner)) return false
  if (task.status === 'CLOSED') return true
  return FIX_REQUEST_STATUSES.includes(task.status) && fixLimitReached(task)
}

/** 수정 중단: 관리자가 회차의 구현실패를 닫는다 → PR생성 (스펙 §7.1). 열린 회차가 있는 구현실패에서만. */
export function canAbortFix(task: { status: string; fix?: FixView | null }, isAdmin: boolean): boolean {
  return isAdmin && task.status === 'IMPLEMENTATION_FAILED' && task.fix?.activeRound != null
}

/** 작업 등록 화면을 이 작업에서 미리 채워 여는 쿼리 (질문의 `fromQuestion`과 같은 방식). */
export function followUpPrefillQuery(task: { id: number }): { fromTask: string } {
  return { fromTask: String(task.id) }
}

/** `<a href>`용 경로 — `/tasks?fromTask={id}`. */
export function followUpPath(task: { id: number }): string {
  return `/tasks?${new URLSearchParams(followUpPrefillQuery(task)).toString()}`
}

export interface TaskFollowUpPrefill {
  repoCatalogId: number | null
  branch: string
  title: string
  description: string
  /** 원래 작업의 대상 레포를 카탈로그에서 못 찾았거나 지원하지 않는 호스트 — 등록 창이 레포를 고르게 안내한다. */
  targetMissing: boolean
}

/** 후속 작업 미리 채우기가 읽는 원래 작업 필드 (`GET /api/tasks/{id}`의 일부). */
export interface FollowUpSource {
  id: number
  title: string
  status: string
  githubBranch: string
  repoCatalogId?: number | null
}

/** 서버 `TaskCreateRequest.title` 상한 */
const TITLE_MAX = 500

/**
 * 원래 작업 → 후속 작업 등록 창 미리 채우기 (스펙 §6.5). 대상 레포·브랜치는 원래 작업과 같게(브랜치는 base — 이미 병합됐거나 아직 병합 전이어도
 * 등록 창의 브랜치 선택은 그대로), 제목은 `[작업 {id} 후속] {원제목}`, 설명 첫 줄은 `원래 작업: #{id}`이고 작업종료 전이면
 * "원래 작업의 변경은 아직 병합 전"을 덧붙인다. 설명의 나머지는 사용자가 쓴다.
 */
export function taskPrefillFromTask(
  t: FollowUpSource,
  catalog: Array<{ id: number; host: string }>,
): TaskFollowUpPrefill {
  const usable = t.repoCatalogId != null && catalog.some((c) => c.id === t.repoCatalogId && c.host !== 'other')
  const merged = t.status === 'CLOSED'
  return {
    repoCatalogId: usable ? (t.repoCatalogId as number) : null,
    branch: t.githubBranch,
    title: `[작업 ${t.id} 후속] ${t.title}`.slice(0, TITLE_MAX),
    description: `원래 작업: #${t.id}${merged ? '' : ' (원래 작업의 변경은 아직 병합 전)'}\n\n`,
    targetMissing: !usable,
  }
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: '승인 대기',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
  WITHDRAWN: '철회됨',
}

/** 수정 요청 한 건의 상태 라벨 — 모르는 값은 그대로. */
export function fixRequestStatusLabel(status: string): string {
  return STATUS_LABEL[status] ?? status
}

const OUTCOME_LABEL: Record<string, string> = {
  DEPLOYED: '재배포 완료',
  DEPLOY_FAILED: '재배포 실패',
  ABORTED: '수정 중단',
}

/** 회차가 끝난 결과 라벨 — 열려 있으면(null) null. */
export function fixOutcomeLabel(outcome: string | null | undefined): string | null {
  return outcome ? (OUTCOME_LABEL[outcome] ?? outcome) : null
}
