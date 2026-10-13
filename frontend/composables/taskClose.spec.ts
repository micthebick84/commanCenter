import { describe, it, expect } from 'vitest'
import { closeSteps, obsidianLink } from './taskClose'

describe('closeSteps', () => {
  it('끝난 단계는 done, 종료중이면 다음 단계가 current', () => {
    const s = closeSteps({ progress: ['MERGED'], mergeCommitSha: 'x', notePath: null }, 'CLOSING')
    expect(s.map((x) => x.state)).toEqual(['done', 'current', 'future', 'future'])
    expect(s.map((x) => x.label)).toEqual(['PR 병합', '배포 중지', '옵시디언 노트', '브랜치 삭제'])
  })
  it('종료실패면 다음 단계가 failed', () => {
    const s = closeSteps({ progress: ['MERGED', 'UNDEPLOYED'], mergeCommitSha: null, notePath: null }, 'CLOSE_FAILED')
    expect(s[2]!.state).toBe('failed')
  })
  it('종료승인대기·종료대기는 전부 future', () => {
    expect(closeSteps({ progress: [], mergeCommitSha: null, notePath: null }, 'CLOSE_REVIEW').every((x) => x.state === 'future')).toBe(true)
  })
})

describe('obsidianLink', () => {
  it('볼트 이름과 경로를 인코딩해 obsidian:// 링크를 만든다', () => {
    expect(obsidianLink('Obsidian Vault', 'HamonSoft/netisMaker/로그인-작업7-2026-10-13.md'))
      .toBe('obsidian://open?vault=Obsidian%20Vault&file=HamonSoft%2FnetisMaker%2F%EB%A1%9C%EA%B7%B8%EC%9D%B8-%EC%9E%91%EC%97%857-2026-10-13')
  })
  it('볼트 이름이나 경로가 없으면 null', () => {
    expect(obsidianLink('', 'a.md')).toBeNull()
    expect(obsidianLink('V', null)).toBeNull()
  })
})
