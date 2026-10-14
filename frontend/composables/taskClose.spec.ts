import { describe, it, expect, vi } from 'vitest'
import { closeSteps, obsidianLink, canSkipArtifact, formatMb, startArtifactDownload, MAIL_STATUS_LABEL } from './taskClose'

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

describe('closeSteps — 산출물 모드 (스펙 2026-10-14)', () => {
  const base = { mergeCommitSha: null, notePath: null }
  it('모드가 없으면 기존 4단계', () => {
    expect(closeSteps({ ...base, progress: [], artifactMode: null }, 'CLOSE_REVIEW').map((s) => s.key))
      .toEqual(['MERGED', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED'])
  })
  it('REQUIRED면 배포 중지와 노트 사이에 산출물 업로드가 있고, 거기서 멈추면 failed', () => {
    const s = closeSteps({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')
    expect(s.map((x) => x.key)).toEqual(['MERGED', 'UNDEPLOYED', 'ARTIFACT_UPLOADED', 'NOTE_WRITTEN', 'BRANCH_DELETED'])
    expect(s[2]!.label).toBe('산출물 업로드')
    expect(s[2]!.state).toBe('failed')
  })
  it('SKIPPED면 산출물 단계는 건너뜀이고 다음 단계가 current', () => {
    const s = closeSteps({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'SKIPPED' }, 'CLOSING')
    expect(s[2]!.state).toBe('skipped')
    expect(s[3]!.state).toBe('current')
  })
})

describe('canSkipArtifact', () => {
  const base = { mergeCommitSha: null, notePath: null }
  it('산출물 단계에서 멈춘 종료실패만', () => {
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')).toBe(true)
    expect(canSkipArtifact({ ...base, progress: ['MERGED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')).toBe(false)
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED', 'ARTIFACT_UPLOADED'], artifactMode: 'REQUIRED' }, 'CLOSE_FAILED')).toBe(false)
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: null }, 'CLOSE_FAILED')).toBe(false)
    expect(canSkipArtifact({ ...base, progress: ['MERGED', 'UNDEPLOYED'], artifactMode: 'REQUIRED' }, 'CLOSING')).toBe(false)
    expect(canSkipArtifact(null, 'CLOSE_FAILED')).toBe(false)
  })
})

describe('산출물 표시·내려받기', () => {
  it('크기는 MB 소수 1자리, 메일 상태는 한국어', () => {
    expect(formatMb(265_911_624)).toBe('253.6 MB')
    expect(MAIL_STATUS_LABEL.FAILED).toBe('메일 실패')
  })
  it('download-link를 POST하고 받은 주소로 이동한다', async () => {
    const api = vi.fn().mockResolvedValue({ url: '/api/artifact-downloads/tok' })
    const go = vi.fn()
    await startArtifactDownload(42, api, go)
    expect(api).toHaveBeenCalledWith('/api/tasks/42/artifact/download-link', { method: 'POST' })
    expect(go).toHaveBeenCalledWith('/api/artifact-downloads/tok')
  })
})
