// 인터뷰 상태 표시 상수 (InterviewPanel의 STATUS_LABELS에서 추출해 공유).
// 상태 비교/터미널 판정은 항상 영문 enum 이름(statusName)으로 하고,
// 여기 라벨/색상은 화면 표시 전용이다.
import type { InterviewStatus } from '~/composables/useInterviewStream'

export const INTERVIEW_STATUS_LABELS: Record<InterviewStatus, string> = {
  QUEUED: '대기 중',
  RUNNING: '분석 중',
  AWAITING_INPUT: '입력 대기',
  PLAN_READY: '플랜 완료',
  REGISTERED: '등록됨',
  CANCELLED: '취소됨',
  EXPIRED: '만료됨',
  FAILED: '실패',
}

// 터미널 상태 세트 — useInterviewStream.ts의 TERMINAL과 동일한 집합.
export const INTERVIEW_TERMINAL_STATUSES: InterviewStatus[] = [
  'REGISTERED',
  'CANCELLED',
  'EXPIRED',
  'FAILED',
]

// 터미널 상태 칩 색상 [배경, 글자] — taskStages.ts CHIP 팔레트와 톤 통일.
export const INTERVIEW_STATUS_CHIP: Partial<Record<InterviewStatus, [string, string]>> = {
  CANCELLED: ['#f5f5f5', '#616161'], // taskStages CANCELLED 재사용값
  EXPIRED: ['#fff3e0', '#e65100'], // 주황 계열
  FAILED: ['#ffebee', '#c62828'], // 빨강 계열
  REGISTERED: ['#e0f2f1', '#00695c'], // 초록 계열
}

// 세션 종류 — 서버 InterviewKind. 프론트는 라벨/경로 분기에만 쓴다.
export type SessionKind = 'INTERVIEW' | 'QUESTION'

// 질문 세션(Q&A) 표기 — 같은 enum, 문맥만 다르다 (스펙 2026-08-30 §7 표).
// PLAN_READY/REGISTERED는 질문 세션에서 도달 불가 — 방어적으로만 둔다.
export const QUESTION_STATUS_LABELS: Record<InterviewStatus, string> = {
  QUEUED: '답변 대기중',
  RUNNING: '답변 중',
  AWAITING_INPUT: '답변 완료',
  PLAN_READY: '플랜 완료',
  REGISTERED: '등록됨',
  CANCELLED: '종료됨',
  EXPIRED: '만료됨',
  FAILED: '실패',
}

// 서버 statusName(string)을 안전하게 라벨로 — 알 수 없는 값은 그대로 노출. kind로 문맥 표기 분기.
export function interviewStatusLabel(
  name: string | null | undefined,
  kind: SessionKind = 'INTERVIEW',
): string {
  if (!name) return ''
  const table = kind === 'QUESTION' ? QUESTION_STATUS_LABELS : INTERVIEW_STATUS_LABELS
  return (table as Record<string, string>)[name] ?? name
}

// statusName → 칩 색상. 미정의 상태는 회색 기본값.
export function interviewStatusChip(name: string | null | undefined): [string, string] {
  return (
    (INTERVIEW_STATUS_CHIP as Record<string, [string, string]>)[name ?? ''] ?? [
      '#f5f5f5',
      '#616161',
    ]
  )
}
