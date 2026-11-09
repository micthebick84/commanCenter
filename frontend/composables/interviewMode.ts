// 인터뷰 방식(superpowers 켬/끔) — 스펙 2026-11-03 간단 인터뷰 §6. 끔 = "간단 인터뷰": 질문을 최대 한 번 모아서 묻고 바로 계획을 낸다.
// 값은 세션마다 저장되고(API `superpowersEnabled`) 칸이 없거나 null이면 켬이다 — 끔은 `=== false`일 때만이다.
// 승인 다이얼로그(토글·기본값)·인터뷰 패널(계획 고쳐 달라기)·작업 상세(칩)가 이 문구와 판정을 함께 쓴다.

export const SUPERPOWERS_TOGGLE_LABEL = 'superpowers 인터뷰'
/** 끔일 때 토글 아래 보조 문구 */
export const SIMPLE_INTERVIEW_CAPTION = '간단 인터뷰 — 질문을 최대 한 번 모아서 묻고 바로 계획'
/** 작업 상세 인터뷰 정보 줄의 칩 — 끔일 때만 그린다 */
export const SIMPLE_INTERVIEW_LABEL = '간단 인터뷰'
/** 플랜완료 화면에서 고칠 점을 보내는 버튼(간단 인터뷰만) */
export const PLAN_FIX_SEND = '계획 고쳐 달라기'
export const PLAN_FIX_PLACEHOLDER = '계획에서 고칠 점을 적어 주세요 — 질문 없이 고친 계획을 다시 냅니다'

/**
 * 타입이 정하는 기본값 — DB 작업만 끔, 그 밖의 타입·타입 없음은 켬(스펙 §3).
 * 관리자가 승인 다이얼로그에서 바꿀 수 있는 미리 고르기일 뿐이다.
 */
export function defaultSuperpowers(taskType: string | null | undefined): boolean {
  return taskType !== 'DB_WORK'
}

/**
 * 지난 인터뷰 세션 행의 값 — boolean일 때만 그 값, 행이 없거나 칸이 없거나(구 API) null이면 null(타입 기본값으로 간다).
 * 행은 `/api/tasks/{id}/interviews`를 터미널 상태로 거른 목록의 첫 행이다(DB 연결 프리필과 같은 목록).
 */
export function lastSessionSuperpowers(row: { superpowersEnabled?: boolean | null } | undefined): boolean | null {
  const v = row?.superpowersEnabled
  return typeof v === 'boolean' ? v : null
}

/**
 * 플랜완료 화면에서 "계획 고쳐 달라기"를 보낼 수 있는가 — 플랜완료 + 간단 인터뷰(false)이고 질문 세션이 아닐 때만.
 * 켬·모름(칸 없음·null)은 지금처럼 "구현 진행"과 "인터뷰 취소"만 있다.
 */
export function canRequestPlanFix(
  status: string | null | undefined,
  superpowersEnabled: boolean | null | undefined,
  isQuestion: boolean,
): boolean {
  return status === 'PLAN_READY' && superpowersEnabled === false && !isQuestion
}
