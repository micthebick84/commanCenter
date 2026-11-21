import { describe, it, expect } from 'vitest'
import { fetchRepoBranches, initialBranch, type RepoBranches } from './repoBranches'
import { useApiMock } from '../test/mocks/nuxt'

const res = (over: Partial<RepoBranches> = {}): RepoBranches => ({
  defaultBranch: null,
  branches: [],
  ...over,
})

describe('initialBranch', () => {
  it('카탈로그 기본 → 조회 기본 → 첫 브랜치 → 빈 문자열 순', () => {
    const full = res({ defaultBranch: 'dev', branches: [{ name: 'first', sha: 'a' }] })
    expect(initialBranch(full, 'cat')).toBe('cat')
    expect(initialBranch(full, null)).toBe('dev')
    expect(initialBranch(res({ branches: [{ name: 'first', sha: 'a' }] }), null)).toBe('first')
    expect(initialBranch(res(), null)).toBe('')
  })
})

describe('fetchRepoBranches', () => {
  it('catalogId로 브랜치를 조회한다', async () => {
    useApiMock.mockResolvedValue({ defaultBranch: 'main', branches: [{ name: 'main', sha: 'a' }] })
    const r = await fetchRepoBranches(5)
    expect(useApiMock).toHaveBeenCalledWith('/api/repos/branches', { params: { catalogId: 5 } })
    expect(r.defaultBranch).toBe('main')
  })

  it('404면 "레포를 찾을 수 없거나 접근 권한이 없습니다"로 던진다', async () => {
    useApiMock.mockRejectedValue({ statusCode: 404 })
    await expect(fetchRepoBranches(5)).rejects.toThrow('레포를 찾을 수 없거나 접근 권한이 없습니다')
  })

  it('서버 message가 있으면 그 문구로 던진다', async () => {
    useApiMock.mockRejectedValue({ statusCode: 500, data: { message: '서버 사유' } })
    await expect(fetchRepoBranches(5)).rejects.toThrow('서버 사유')
  })

  it('그 밖의 오류는 브랜치 동기화 실패 안내로 던진다', async () => {
    useApiMock.mockRejectedValue(new Error('boom'))
    await expect(fetchRepoBranches(5)).rejects.toThrow('브랜치 동기화에 실패했습니다')
  })
})
