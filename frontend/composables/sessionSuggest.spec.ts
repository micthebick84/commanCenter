import { describe, it, expect } from 'vitest'
import {
  upsertSuggestion,
  pendingForTurn,
  decidedForTurn,
  initialDbPicks,
  canPickMore,
  followUpText,
  decidedLine,
  mergeDbIds,
  mergeRefInputs,
  SUGGEST_FOLLOW_UP,
  type SessionSuggestion,
} from './sessionSuggest'

function sg(over: Partial<SessionSuggestion> = {}): SessionSuggestion {
  return {
    id: 1,
    turnSeq: 2,
    kind: 'DB',
    status: 'PENDING',
    reason: '확인 필요',
    targets: [],
    dbConnectionId: null,
    repoCatalogId: null,
    repoAlias: null,
    defaultBranch: null,
    dbOptions: [],
    dbSlotsLeft: 3,
    refSlotsLeft: 3,
    ...over,
  }
}

describe('sessionSuggest', () => {
  it('upsertSuggestion은 같은 id를 교체하고 새 id는 끝에 더한다 — 원본 배열은 그대로', () => {
    const a = sg({ id: 1 })
    const list = [a, sg({ id: 2 })]
    const replaced = upsertSuggestion(list, sg({ id: 1, status: 'ACCEPTED' }))
    expect(replaced.map((s: SessionSuggestion) => s.status)).toEqual(['ACCEPTED', 'PENDING'])
    const added = upsertSuggestion(list, sg({ id: 3 }))
    expect(added.map((s: SessionSuggestion) => s.id)).toEqual([1, 2, 3])
    expect(list).toHaveLength(2)
    expect(list[0]).toBe(a)
  })

  it('pendingForTurn·decidedForTurn이 턴과 상태로 가른다', () => {
    const list = [
      sg({ id: 1, turnSeq: 2 }),
      sg({ id: 2, turnSeq: 2, status: 'ACCEPTED' }),
      sg({ id: 3, turnSeq: 4 }),
      sg({ id: 4, turnSeq: 2, status: 'SUPERSEDED' }),
    ]
    expect(pendingForTurn(list, 2).map((s: SessionSuggestion) => s.id)).toEqual([1])
    expect(decidedForTurn(list, 2).map((s: SessionSuggestion) => s.id)).toEqual([2, 4])
  })

  it('삭제된 레포의 PENDING 참조 제안은 고를 수 없어 한 줄 쪽으로 간다', () => {
    const list = [
      sg({ id: 1, kind: 'REF_REPO', repoCatalogId: null }),
      sg({ id: 2, kind: 'REF_REPO', repoCatalogId: 11, repoAlias: 'acme/y' }),
    ]
    expect(pendingForTurn(list, 2).map((s: SessionSuggestion) => s.id)).toEqual([2])
    expect(decidedForTurn(list, 2).map((s: SessionSuggestion) => s.id)).toEqual([1])
  })

  it('Claude가 고른 연결이 선택지에 있으면 그것을 미리 고른다', () => {
    const s = sg({
      dbConnectionId: 8,
      dbOptions: [
        { id: 7, name: 'a', dbType: 'POSTGRESQL' },
        { id: 8, name: 'b', dbType: 'MYSQL' },
      ],
    })
    expect(initialDbPicks(s)).toEqual([8])
  })

  it('고른 연결이 선택지에 없고 선택지가 하나면 그것을 고른다', () => {
    const s = sg({ dbConnectionId: 99, dbOptions: [{ id: 7, name: 'a', dbType: 'MYSQL' }] })
    expect(initialDbPicks(s)).toEqual([7])
  })

  it('고른 연결이 없고 선택지가 여럿이면 아무것도 고르지 않는다', () => {
    const s = sg({
      dbOptions: [
        { id: 7, name: 'a', dbType: 'MYSQL' },
        { id: 8, name: 'b', dbType: 'MYSQL' },
      ],
    })
    expect(initialDbPicks(s)).toEqual([])
  })

  it('DB 자리가 없으면 아무것도 고르지 않는다', () => {
    const s = sg({ dbSlotsLeft: 0, dbConnectionId: 7, dbOptions: [{ id: 7, name: 'a', dbType: 'MYSQL' }] })
    expect(initialDbPicks(s)).toEqual([])
  })

  it('남은 자리만큼만 체크할 수 있다', () => {
    expect(canPickMore(0, 1)).toBe(true)
    expect(canPickMore(1, 1)).toBe(false)
    expect(canPickMore(0, 0)).toBe(false)
  })

  it('이어 묻기 문장은 DB·참조·둘 다에 따라 다르다', () => {
    expect(followUpText(true, false)).toBe(SUGGEST_FOLLOW_UP.db)
    expect(followUpText(false, true)).toBe(SUGGEST_FOLLOW_UP.ref)
    expect(followUpText(true, true)).toBe(SUGGEST_FOLLOW_UP.both)
    expect(followUpText(false, false)).toBe('')
  })

  it('지난 제안 한 줄은 상태와 종류를 담는다', () => {
    expect(decidedLine(sg({ status: 'ACCEPTED' }))).toBe('제안: DB 연결 — 연결함')
    expect(decidedLine(sg({ status: 'DISMISSED' }))).toBe('제안: DB 연결 — 닫음')
    expect(decidedLine(sg({ status: 'SUPERSEDED' }))).toBe('제안: DB 연결 — 연결하지 않음')
    expect(decidedLine(sg({ status: 'PENDING' }))).toBe('제안: DB 연결 — 연결하지 않음')
    const ref = sg({ kind: 'REF_REPO', status: 'ACCEPTED', repoAlias: 'acme/x' })
    expect(decidedLine(ref)).toBe('제안: 참조 레포 acme/x — 연결함')
    expect(decidedLine({ ...ref, repoAlias: null, status: 'DISMISSED' })).toBe(
      '제안: 참조 레포 (삭제된 레포) — 닫음',
    )
  })

  it('mergeDbIds는 초안 뒤에 새 id를 붙이고 중복을 뺀다', () => {
    const draft = [3, 1]
    expect(mergeDbIds(draft, [1, 5, 5, 2])).toEqual([3, 1, 5, 2])
    expect(draft).toEqual([3, 1])
  })

  it('mergeRefInputs는 같은 레포면 초안의 브랜치를 유지한다', () => {
    const draft = [{ repoCatalogId: 1, branch: 'dev' }]
    const out = mergeRefInputs(draft, [
      { repoCatalogId: 1, branch: 'main' },
      { repoCatalogId: 2, branch: 'main' },
    ])
    expect(out).toEqual([
      { repoCatalogId: 1, branch: 'dev' },
      { repoCatalogId: 2, branch: 'main' },
    ])
    expect(draft).toHaveLength(1)
  })
})
