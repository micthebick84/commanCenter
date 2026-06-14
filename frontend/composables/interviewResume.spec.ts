import { describe, it, expect } from 'vitest'
import { decideResume, type InterviewSummary } from './interviewResume'

function summary(id: number): InterviewSummary {
  return {
    id,
    status: '입력대기',
    statusName: 'AWAITING_INPUT',
    title: `T${id}`,
    githubRepo: 'owner/repo',
    githubBranch: 'main',
    currentPhase: 'brainstorming',
    lastActivityAt: '2026-06-14T00:00:00Z',
    createdAt: '2026-06-14T00:00:00Z',
  }
}

describe('decideResume', () => {
  it('returns none for empty/null', () => {
    expect(decideResume([])).toEqual({ mode: 'none' })
    expect(decideResume(null)).toEqual({ mode: 'none' })
    expect(decideResume(undefined)).toEqual({ mode: 'none' })
  })

  it('returns auto with the id for exactly one active session', () => {
    expect(decideResume([summary(7)])).toEqual({ mode: 'auto', id: 7 })
  })

  it('returns pick with all candidates for two or more', () => {
    const list = [summary(1), summary(2), summary(3)]
    expect(decideResume(list)).toEqual({ mode: 'pick', candidates: list })
  })
})
