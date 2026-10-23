import { describe, it, expect } from 'vitest'
import {
  MAX_REF_REPOS,
  refChipLabel,
  refState,
  sameRefSet,
  toInputs,
  type RefRepoInput,
  type RefRepoView,
} from './refRepos'

const view = (over: Partial<RefRepoView> = {}): RefRepoView => ({
  catalogId: 1,
  alias: '옛앱',
  branch: 'main',
  ...over,
})
const input = (repoCatalogId: number, branch: string): RefRepoInput => ({ repoCatalogId, branch })

describe('MAX_REF_REPOS', () => {
  it('3이다 (API RepoCatalogService.MAX_REF_REPOS와 같은 값)', () => {
    expect(MAX_REF_REPOS).toBe(3)
  })
})

describe('refChipLabel', () => {
  it('커밋이 있으면 브랜치 뒤에 @앞 7자를 붙인다', () => {
    expect(refChipLabel({ catalogId: 1, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5' })).toBe(
      '📚 옛앱 · release/6.5@a1b2c3d',
    )
    // 40자 전체가 와도 7자만
    expect(refChipLabel(view({ sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678' }))).toBe('📚 옛앱 · main@a1b2c3d')
  })

  it('커밋이 없으면 별칭 · 브랜치만', () => {
    expect(refChipLabel({ catalogId: 1, alias: '옛앱', branch: 'main' })).toBe('📚 옛앱 · main')
    expect(refChipLabel(view({ sha: null }))).toBe('📚 옛앱 · main')
    expect(refChipLabel(view({ sha: '' }))).toBe('📚 옛앱 · main') // 빈 문자열도 "없음"
  })

  it('7자보다 짧은 커밋은 있는 만큼만', () => {
    expect(refChipLabel(view({ sha: 'abc' }))).toBe('📚 옛앱 · main@abc')
  })

  it('실패한 참조도 커밋이 있으면 라벨에 붙는다 (상태는 refState가 따로 판정)', () => {
    expect(refChipLabel(view({ sha: 'a1b2c3d4e5', error: '권한 없음' }))).toBe('📚 옛앱 · main@a1b2c3d')
  })
})

describe('refState', () => {
  it('error가 있으면 sha가 있어도 failed (error 우선)', () => {
    expect(refState({ catalogId: 1, alias: 'a', branch: 'b', sha: 'x', error: '권한 없음' })).toBe('failed')
    expect(refState(view({ sha: null, error: 'fatal: Remote branch b not found' }))).toBe('failed')
  })

  it('error가 없고 sha가 있으면 pinned', () => {
    expect(refState(view({ sha: 'a1b2c3d4e5' }))).toBe('pinned')
    expect(refState(view({ sha: 'a1b2c3d4e5', error: null }))).toBe('pinned')
  })

  it('둘 다 없으면 pending (아직 안 받음)', () => {
    expect(refState(view())).toBe('pending')
    expect(refState(view({ sha: null, error: null, fetchedAt: null }))).toBe('pending')
  })

  it('빈 문자열 error·sha는 없는 값으로 본다', () => {
    expect(refState(view({ sha: 'a1b2c3d', error: '' }))).toBe('pinned')
    expect(refState(view({ sha: '', error: '' }))).toBe('pending')
  })
})

describe('toInputs', () => {
  it('catalogId를 repoCatalogId로, 브랜치는 그대로 — 커밋·시각·사유는 보내지 않는다', () => {
    const refs: RefRepoView[] = [
      { catalogId: 12, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5', fetchedAt: '2026-10-22T05:30:00Z', error: null },
      { catalogId: 7, alias: '공통', branch: 'main', sha: null, fetchedAt: null, error: '권한 없음' },
    ]
    expect(toInputs(refs)).toEqual([
      { repoCatalogId: 12, branch: 'release/6.5' },
      { repoCatalogId: 7, branch: 'main' },
    ])
    // 정확히 두 키만 (ask·등록 바디에 sha 등이 새지 않는다)
    expect(Object.keys(toInputs(refs)[0]!).sort()).toEqual(['branch', 'repoCatalogId'])
  })

  it('입력 순서를 유지하고 빈 목록은 빈 목록', () => {
    expect(toInputs([view({ catalogId: 3 }), view({ catalogId: 1 })]).map((i) => i.repoCatalogId)).toEqual([3, 1])
    expect(toInputs([])).toEqual([])
  })
})

describe('sameRefSet', () => {
  it('순서가 달라도 같은 짝이면 true', () => {
    expect(sameRefSet([input(1, 'a'), input(2, 'b')], [input(2, 'b'), input(1, 'a')])).toBe(true)
  })

  it('같은 레포라도 브랜치가 다르면 false (브랜치는 구분한다)', () => {
    expect(sameRefSet([input(1, 'a')], [input(1, 'b')])).toBe(false)
    expect(sameRefSet([input(1, 'a'), input(2, 'b')], [input(1, 'a'), input(2, 'c')])).toBe(false)
  })

  it('레포가 다르면 false', () => {
    expect(sameRefSet([input(1, 'a')], [input(2, 'a')])).toBe(false)
  })

  it('개수가 다르면 false (한쪽이 부분집합이어도)', () => {
    expect(sameRefSet([input(1, 'a')], [input(1, 'a'), input(2, 'b')])).toBe(false)
    expect(sameRefSet([input(1, 'a'), input(2, 'b')], [input(1, 'a')])).toBe(false)
    expect(sameRefSet([], [input(1, 'a')])).toBe(false)
  })

  it('둘 다 비면 true', () => {
    expect(sameRefSet([], [])).toBe(true)
  })

  it('중복 짝은 개수까지 맞아야 같다 (서버도 같은 원소를 두 번 가리키는 입력을 같은 집합으로 보지 않는다)', () => {
    expect(sameRefSet([input(1, 'a'), input(1, 'a')], [input(1, 'a'), input(1, 'a')])).toBe(true)
    expect(sameRefSet([input(1, 'a'), input(1, 'a')], [input(1, 'a'), input(2, 'b')])).toBe(false)
    expect(sameRefSet([input(1, 'a'), input(1, 'a'), input(2, 'b')], [input(1, 'a'), input(2, 'b'), input(2, 'b')])).toBe(false)
  })

  it('레포 id와 브랜치 이름이 맞붙어 같은 글자가 돼도 다른 짝이다', () => {
    expect(sameRefSet([input(1, '2b')], [input(12, 'b')])).toBe(false)
    expect(sameRefSet([input(1, '2:b'), input(3, 'x')], [input(12, ':b'), input(3, 'x')])).toBe(false)
  })

  it('입력 배열을 바꾸지 않는다', () => {
    const a = [input(2, 'b'), input(1, 'a')]
    const b = [input(1, 'a'), input(2, 'b')]
    sameRefSet(a, b)
    expect(a).toEqual([input(2, 'b'), input(1, 'a')])
    expect(b).toEqual([input(1, 'a'), input(2, 'b')])
  })
})
