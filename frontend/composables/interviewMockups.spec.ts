import { describe, it, expect } from 'vitest'
import {
  MOCKUP_CSP,
  MAX_CONFIRM_MOCKUPS,
  mockupSrcdoc,
  mockupLabel,
  mockupVersionTail,
  groupMockups,
  planMockupNumbers,
  defaultMockupSelection,
  excludedPlanMockups,
  upsertMockup,
  dropUnbound,
  type MockupSummary,
} from './interviewMockups'

const META = '<meta http-equiv="Content-Security-Policy" content="' + MOCKUP_CSP + '">'

function mk(id: number, n: number, over: Partial<MockupSummary> = {}): MockupSummary {
  return {
    id,
    n,
    name: `m${n}`,
    version: 1,
    title: `T${n}`,
    description: null,
    turnSeq: 2,
    turnKind: 'question',
    ...over,
  }
}

describe('interviewMockups (스펙 2026-11-03 인터뷰 목업 §6)', () => {
  it('CSP는 doctype 바로 뒤, 어떤 외부 요청보다 먼저', () => {
    const out = mockupSrcdoc('<!DOCTYPE html><html><head><link href="https://x/a.css">')
    expect(out.startsWith('<!DOCTYPE html><meta http-equiv="Content-Security-Policy"')).toBe(true)
    expect(out.indexOf('Content-Security-Policy')).toBeLessThan(out.indexOf('<link'))
  })

  it('앞 공백·소문자 doctype·대문자 <HTML>', () => {
    expect(mockupSrcdoc('  <!doctype html><p>x</p>')).toBe('  <!doctype html>' + META + '<p>x</p>')
    expect(mockupSrcdoc('<HTML lang="ko"><body/>').startsWith(META)).toBe(true)
  })

  it('조각은 뼈대로 감싼다', () => {
    expect(mockupSrcdoc('<div>x</div>')).toBe(
      '<!doctype html><html><head><meta charset="utf-8">' + META + '</head><body><div>x</div></body></html>',
    )
  })

  it('CSP 값', () =>
    expect(MOCKUP_CSP).toBe(
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:",
    ))

  it('라벨: v1은 버전 없음', () => {
    expect(mockupLabel({ n: 1, title: 'A안 — 한 줄 필터', version: 1 })).toBe('목업 1 · A안 — 한 줄 필터')
    expect(mockupLabel({ n: 3, title: 'A안', version: 2 })).toBe('목업 3 · A안 (v2)')
    expect(mockupVersionTail(1)).toBe('')
    expect(mockupVersionTail(3)).toBe(' (v3)')
  })

  it('이름별 묶음: 처음 나온 순, 안에서는 최신 위', () => {
    const list = [
      mk(1, 1, { name: 'a' }),
      mk(2, 2, { name: 'b' }),
      mk(3, 3, { name: 'a', version: 2 }),
      mk(4, 4, { name: 'a', version: 3 }),
    ]
    const g = groupMockups(list)
    expect(g.map((x) => x.name)).toEqual(['a', 'b'])
    expect(g[0].latest.id).toBe(4)
    expect(g[0].older.map((x) => x.id)).toEqual([3, 1])
    expect(g[1].older).toEqual([])
  })

  it('계획 번호·기본 선택·제외', () => {
    expect(planMockupNumbers('목업 3, 목업 5, 목업 3')).toEqual([3, 5])
    expect(planMockupNumbers('목업 3, 5')).toEqual([3])
    expect(planMockupNumbers(null)).toEqual([])
    const list = [mk(10, 3), mk(11, 5, { turnSeq: null, turnKind: null }), mk(12, 1)]
    expect(defaultMockupSelection('목업 5, 목업 3, 목업 9', list)).toEqual([10])
    expect(excludedPlanMockups('목업 5, 목업 3, 목업 9', list, [10])).toEqual([5])
    expect(excludedPlanMockups('목업 3', list, [10])).toEqual([])
  })

  it('기본 선택은 10개까지', () => {
    const list = Array.from({ length: 11 }, (_, i) => mk(100 + i, i + 1))
    const plan = list.map((m) => `목업 ${m.n}`).join(', ')
    const sel = defaultMockupSelection(plan, list)
    expect(sel).toHaveLength(MAX_CONFIRM_MOCKUPS)
    expect(sel).toEqual(list.slice(0, 10).map((m) => m.id))
    expect(excludedPlanMockups(plan, list, sel)).toEqual([11])
  })

  it('upsert는 id로 바꿔 끼우고, dropUnbound는 묶이지 않은 것만 뺀다', () => {
    const a = mk(1, 1, { turnSeq: null, turnKind: null })
    const list = upsertMockup([a], mk(1, 1))
    expect(list).toHaveLength(1)
    expect(list[0].turnSeq).toBe(2)
    expect(upsertMockup(list, mk(2, 2))).toHaveLength(2)
    expect(dropUnbound([a, mk(2, 2)]).map((m) => m.id)).toEqual([2])
  })
})
