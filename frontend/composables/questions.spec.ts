import { describe, it, expect } from 'vitest'
import {
  groupByRecency,
  contextPercent,
  taskPrefillFromQuestion,
  type QuestionDetail,
  type QuestionSummary,
} from './questions'
import type { RepoCatalogOption } from './refRepos'

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

// 질문 대화 → 작업 등록 창 프리필 (작업 스펙 2026-10-24 §7.2)
describe('taskPrefillFromQuestion', () => {
  // 카탈로그(GET /api/repo-catalog): 1=대상 · 2=옛앱 · 3=공통 UI · 4=지원하지 않는 호스트
  const CATALOG: RepoCatalogOption[] = [
    { id: 1, alias: 'Netis7.0', host: 'github', defaultBranch: 'main' },
    { id: 2, alias: '옛앱', host: 'gitlab', defaultBranch: 'release/6.5' },
    { id: 3, alias: '공통 UI', host: 'gitlab', defaultBranch: 'main' },
    { id: 4, alias: '외부 저장소', host: 'other', defaultBranch: null },
  ]
  // 서버 값 — 커밋·받은 시각·사유가 붙어 있다(고정 · 실패)
  const REF_PINNED = {
    catalogId: 2,
    alias: '옛앱',
    branch: 'release/6.5',
    sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
    fetchedAt: '2026-10-22T05:30:00Z',
    error: null,
  }
  const REF_FAILED = {
    catalogId: 3,
    alias: '공통 UI',
    branch: 'main',
    sha: null,
    fetchedAt: null,
    error: 'fatal: Remote branch main not found in upstream origin',
  }

  function question(extra: Partial<QuestionDetail> = {}): QuestionDetail {
    return {
      id: 3,
      title: '옛앱 결재선 비교',
      githubRepo: 'acme/netis',
      githubBranch: 'release/7.0',
      statusName: 'AWAITING_INPUT',
      model: 'claude-opus-5-5',
      effort: 'high',
      totalCostUsd: null,
      contextTokens: null,
      contextWindow: null,
      repoCatalogId: 1,
      refRepos: [REF_PINNED],
      ...extra,
    }
  }

  it('대상·브랜치·제목을 옮기고 참조는 커밋·상태를 지운 대기 값으로', () => {
    const p = taskPrefillFromQuestion(question({ refRepos: [REF_PINNED, REF_FAILED] }), CATALOG)
    expect(p.repoCatalogId).toBe(1)
    expect(p.branch).toBe('release/7.0')
    expect(p.title).toBe('옛앱 결재선 비교')
    expect(p.targetMissing).toBe(false)
    // 고정 커밋도 실패 사유도 남기지 않는다 — 작업은 인터뷰에서 처음부터 다시 받는다
    expect(p.refRepos).toEqual([
      { catalogId: 2, alias: '옛앱', branch: 'release/6.5' },
      { catalogId: 3, alias: '공통 UI', branch: 'main' },
    ])
    expect(p.droppedRefs).toEqual([])
  })

  it('카탈로그에 없거나 other 호스트인 참조는 droppedRefs로', () => {
    const gone = { catalogId: 5, alias: '인증 서버', branch: 'develop', sha: null, fetchedAt: null, error: null }
    const other = { catalogId: 4, alias: '외부 저장소', branch: 'main', sha: null, fetchedAt: null, error: null }
    const p = taskPrefillFromQuestion(question({ refRepos: [gone, REF_PINNED, other] }), CATALOG)
    expect(p.refRepos).toEqual([{ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }])
    // 뺀 참조는 알림용(별칭·브랜치) — 고른 순서 그대로
    expect(p.droppedRefs).toEqual([
      { catalogId: 5, alias: '인증 서버', branch: 'develop' },
      { catalogId: 4, alias: '외부 저장소', branch: 'main' },
    ])
    expect(p.targetMissing).toBe(false)
  })

  it('대상과 같은 레포를 가리키는 참조는 남기지 않는다', () => {
    const sameAsTarget = { catalogId: 1, alias: 'Netis7.0', branch: 'develop' }
    const p = taskPrefillFromQuestion(question({ refRepos: [sameAsTarget, REF_PINNED] }), CATALOG)
    expect(p.refRepos.map((r) => r.catalogId)).toEqual([2])
    expect(p.droppedRefs.map((r) => r.catalogId)).toEqual([1])
  })

  it('질문에 repoCatalogId가 없거나 카탈로그에 없으면 repoCatalogId null + targetMissing', () => {
    for (const repoCatalogId of [null, undefined, 9, 4]) {
      const p = taskPrefillFromQuestion(question({ repoCatalogId }), CATALOG)
      expect(p.repoCatalogId).toBeNull()
      expect(p.targetMissing).toBe(true)
      // 대상이 비어도 제목·참조는 채운다 — 대상만 사용자가 다시 고른다
      expect(p.title).toBe('옛앱 결재선 비교')
      expect(p.refRepos).toEqual([{ catalogId: 2, alias: '옛앱', branch: 'release/6.5' }])
    }
  })

  it('참조 필드가 없는 응답(구버전 백엔드)은 참조 없이', () => {
    const p = taskPrefillFromQuestion(question({ refRepos: undefined }), CATALOG)
    expect(p.refRepos).toEqual([])
    expect(p.droppedRefs).toEqual([])
  })
})
