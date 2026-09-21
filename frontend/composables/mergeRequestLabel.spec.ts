import { describe, it, expect } from 'vitest'
import { mrNoun, mrRef } from './mergeRequestLabel'

describe('mergeRequestLabel', () => {
  it('GitHub PR URL과 URL 없음은 PR', () => {
    expect(mrNoun('https://github.com/acme/widgets/pull/13')).toBe('PR')
    expect(mrNoun(null)).toBe('PR')
    expect(mrRef('https://github.com/acme/widgets/pull/13', 13)).toBe('PR #13')
  })

  it('GitLab MR URL은 MR !n', () => {
    const url = 'https://gitlab.hamon.vip/product/netis/web/netis-v7.0/-/merge_requests/12'
    expect(mrNoun(url)).toBe('MR')
    expect(mrRef(url, 12)).toBe('MR !12')
  })
})
