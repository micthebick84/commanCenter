import { describe, it, expect } from 'vitest'
import {
  interviewStatusLabel,
  interviewStatusChip,
  QUESTION_STATUS_LABELS,
  INTERVIEW_STATUS_LABELS,
} from './interviewLabels'

describe('interviewLabels — kind 분기 (스펙 2026-08-30 §7 표)', () => {
  it('QUESTION 라벨 표 (같은 enum, 문맥만 다름)', () => {
    expect(interviewStatusLabel('QUEUED', 'QUESTION')).toBe('답변 대기중')
    expect(interviewStatusLabel('RUNNING', 'QUESTION')).toBe('답변 중')
    expect(interviewStatusLabel('AWAITING_INPUT', 'QUESTION')).toBe('답변 완료')
    expect(interviewStatusLabel('CANCELLED', 'QUESTION')).toBe('종료됨')
    expect(interviewStatusLabel('EXPIRED', 'QUESTION')).toBe('만료됨')
    expect(interviewStatusLabel('FAILED', 'QUESTION')).toBe('실패')
  })

  it('kind 생략 → 인터뷰 라벨 (기존 호출부 무변경)', () => {
    expect(interviewStatusLabel('QUEUED')).toBe('대기 중')
    expect(interviewStatusLabel('CANCELLED')).toBe('취소됨')
    expect(interviewStatusLabel(null)).toBe('')
    expect(interviewStatusLabel('WHATEVER', 'QUESTION')).toBe('WHATEVER')
  })

  it('두 표는 같은 키 집합을 가진다 (enum 추가 시 동시 갱신 강제)', () => {
    expect(Object.keys(QUESTION_STATUS_LABELS).sort()).toEqual(Object.keys(INTERVIEW_STATUS_LABELS).sort())
  })

  it('칩 색상은 kind 무관', () => {
    expect(interviewStatusChip('CANCELLED')).toEqual(['#f5f5f5', '#616161'])
  })
})
