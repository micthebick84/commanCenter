import { describe, it, expect } from 'vitest'
import { CLAUDE_AUTH_EXPIRING_SOON_MS, claudeAuthAlerts, claudeAuthView } from './claudeAuth'

// 로컬 시각으로 만든다 — 만료 표시는 로컬 시간대라 테스트가 TZ에 좌우되지 않게
const NOW = new Date(2026, 10, 2, 10, 20).getTime()
const at = (month: number, day: number, h: number, m = 0) => new Date(2026, month - 1, day, h, m).toISOString()

describe('claudeAuthView (스펙 2026-11-02 §6)', () => {
  it('판정이 없으면 —, 회색, 조치 없음', () => {
    const v = claudeAuthView({ claudeSessionOk: null }, NOW)
    expect(v).toMatchObject({ level: 'unknown', label: '—', methodLabel: null, expiresLabel: null, action: null })
  })

  it('로그인이 끊긴 PC(토큰 비움, refresh 만료 시각 지남)는 빨강 만료 + 다시 로그인 안내', () => {
    const v = claudeAuthView(
      { claudeSessionOk: false, claudeAuthMethod: 'CREDENTIALS', claudeAuthExpiresAt: at(10, 31, 11, 23) },
      NOW,
    )
    expect(v.level).toBe('bad')
    expect(v.label).toBe('만료')
    expect(v.color).toBe('red-2')
    expect(v.textColor).toBe('red-9')
    expect(v.methodLabel).toBe('CLI 로그인')
    expect(v.expiresLabel).toBe('10-31 11:23 만료됨')
    expect(v.action).toContain('claude auth login')
  })

  it('만료 시각 없이 NG면 NG', () => {
    const v = claudeAuthView({ claudeSessionOk: false, claudeAuthMethod: 'CLI' }, NOW)
    expect(v).toMatchObject({ level: 'bad', label: 'NG', methodLabel: 'CLI 확인', expiresLabel: null })
  })

  it('setup-token이 거부되면 토큰 교체 안내', () => {
    const v = claudeAuthView({ claudeSessionOk: false, claudeAuthMethod: 'ENV_TOKEN' }, NOW)
    expect(v.action).toContain('CLAUDE_CODE_OAUTH_TOKEN')
    expect(v.action).not.toContain('claude auth login')
  })

  it('만료까지 3일 이하면 주황 N일 남음, 하루 미만은 시간, 1시간 미만은 곧 만료', () => {
    const ok = { claudeSessionOk: true, claudeAuthMethod: 'CREDENTIALS' }
    expect(claudeAuthView({ ...ok, claudeAuthExpiresAt: at(11, 4, 11, 0) }, NOW)).toMatchObject({
      level: 'expiring', label: '2일 남음', color: 'orange-2', expiresLabel: '11-04 11:00까지',
    })
    expect(claudeAuthView({ ...ok, claudeAuthExpiresAt: new Date(NOW + CLAUDE_AUTH_EXPIRING_SOON_MS).toISOString() }, NOW).label)
      .toBe('3일 남음')
    expect(claudeAuthView({ ...ok, claudeAuthExpiresAt: at(11, 2, 15, 30) }, NOW).label).toBe('5시간 남음')
    expect(claudeAuthView({ ...ok, claudeAuthExpiresAt: at(11, 2, 10, 50) }, NOW).label).toBe('곧 만료')
    expect(claudeAuthView({ ...ok, claudeAuthExpiresAt: at(11, 4, 11, 0) }, NOW).action).toContain('claude auth login')
  })

  it('3일보다 많이 남았거나 만료 시각이 없으면 초록 OK', () => {
    expect(claudeAuthView({ claudeSessionOk: true, claudeAuthMethod: 'CREDENTIALS', claudeAuthExpiresAt: at(11, 30, 9) }, NOW))
      .toMatchObject({ level: 'ok', label: 'OK', color: 'green-2', expiresLabel: '11-30 09:00까지', action: null })
    expect(claudeAuthView({ claudeSessionOk: true, claudeAuthMethod: 'ENV_TOKEN', claudeAuthExpiresAt: null }, NOW))
      .toMatchObject({ level: 'ok', label: 'OK', methodLabel: 'setup-token', expiresLabel: null })
  })

  it('구버전 응답(새 필드 없음)도 그린다', () => {
    expect(claudeAuthView({ claudeSessionOk: true }, NOW)).toMatchObject({ level: 'ok', methodLabel: null })
    expect(claudeAuthView({ claudeSessionOk: true, claudeAuthMethod: 'SOMETHING_NEW' }, NOW).methodLabel).toBeNull()
  })
})

describe('claudeAuthAlerts', () => {
  it('빨강 먼저, 주황 다음 — OK·판정 없음은 빠진다', () => {
    const rows = [
      { workerId: 'a', claudeSessionOk: true, claudeAuthExpiresAt: at(11, 3, 9) },
      { workerId: 'b', claudeSessionOk: true, claudeAuthMethod: 'ENV_TOKEN' },
      { workerId: 'c', claudeSessionOk: false, claudeAuthMethod: 'CREDENTIALS' },
      { workerId: 'd', claudeSessionOk: null },
    ]
    expect(claudeAuthAlerts(rows, NOW).map((a) => [a.workerId, a.view.level])).toEqual([
      ['c', 'bad'],
      ['a', 'expiring'],
    ])
  })
})
