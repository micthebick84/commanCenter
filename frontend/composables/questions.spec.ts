import { describe, it, expect } from 'vitest'
import { groupByRecency, contextPercent, type QuestionSummary } from './questions'

const NOW = new Date(2026, 8, 5, 14, 0).getTime() // 로컬 2026-09-05 14:00

function q(id: number, updatedAt: Date): QuestionSummary {
  return {
    id, title: `q${id}`, githubRepo: 'o/r', githubBranch: 'main', repoAlias: null, requesterId: 'u',
    status: '', statusName: 'AWAITING_INPUT', model: 'claude-opus-5', effort: 'high', totalCostUsd: null,
    contextTokens: null, contextWindow: null, createdAt: updatedAt.toISOString(), updatedAt: updatedAt.toISOString(),
  }
}

describe('questions composable (스펙 2026-09-05 §3)', () => {
  it('groupByRecency: 오늘/지난 7일/이전, 빈 그룹 제외, 입력 순서 유지', () => {
    const groups = groupByRecency(
      [
        q(1, new Date(2026, 8, 5, 9)), // 오늘
        q(2, new Date(2026, 8, 1, 9)), // 4일 전
        q(3, new Date(2026, 7, 20, 9)), // 16일 전
        q(4, new Date(2026, 8, 5, 1)), // 오늘 새벽
      ],
      NOW,
    )
    expect(groups.map((g) => g.label)).toEqual(['오늘', '지난 7일', '이전'])
    expect(groups[0]!.items.map((x) => x.id)).toEqual([1, 4])
    expect(groups[1]!.items.map((x) => x.id)).toEqual([2])
    expect(groups[2]!.items.map((x) => x.id)).toEqual([3])
    expect(groupByRecency([], NOW)).toEqual([])
  })

  it('contextPercent: 반올림 정수, 0..100 클램프, 미보고/창 0은 null', () => {
    expect(contextPercent(76004, 200000)).toBe(38)
    expect(contextPercent(250000, 200000)).toBe(100)
    expect(contextPercent(null, 200000)).toBeNull()
    expect(contextPercent(10, 0)).toBeNull()
    expect(contextPercent(10, undefined)).toBeNull()
  })
})
