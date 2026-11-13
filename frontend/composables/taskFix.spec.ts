import { describe, it, expect } from 'vitest'
import {
  canRequestFix,
  canAbortFix,
  canFollowUp,
  fixLimitReached,
  followUpPrefillQuery,
  followUpPath,
  taskPrefillFromTask,
  FIX_MODES,
  fixOutcomeLabel,
  fixRequestStatusLabel,
  canSyncBase,
  isBaseSyncRound,
  fixRoundTitle,
  SYNC_BASE_CONFIRM,
  BASE_SYNC_ABORT_CONFIRM,
} from './taskFix'

const fix = (over: Partial<{ activeRound: number | null; activeMode: string | null; approvedRounds: number; maxRounds: number }> = {}) => ({
  activeRound: null,
  activeMode: null,
  approvedRounds: 0,
  maxRounds: 3,
  ...over,
})
const task = (over: Record<string, unknown> = {}) => ({
  id: 42,
  status: 'DEPLOYED',
  title: '대상장비 탭',
  githubBranch: 'main',
  repoCatalogId: 7,
  implementation: { headBranch: 'netismaker/task-42-x' },
  fix: fix(),
  ...over,
})

describe('canRequestFix (스펙 2026-11-03 §6.1)', () => {
  it('배포완료·배포중단됨·배포실패에서만, 등록자 또는 관리자만', () => {
    for (const status of ['DEPLOYED', 'DEPLOY_LOST', 'DEPLOY_FAILED']) {
      expect(canRequestFix(task({ status }), false, true), status).toBe(true)
      expect(canRequestFix(task({ status }), true, false), status).toBe(true)
      expect(canRequestFix(task({ status }), false, false), status).toBe(false)
    }
    for (const status of ['PR_CREATED', 'IMPLEMENTING', 'FIX_REVIEW', 'CLOSE_REVIEW', 'CLOSED', 'DEPLOYING', 'IMPLEMENTATION_FAILED']) {
      expect(canRequestFix(task({ status }), true, true), status).toBe(false)
    }
  })

  it('브랜치가 없으면 못 낸다', () => {
    expect(canRequestFix(task({ implementation: null }), true, true)).toBe(false)
    expect(canRequestFix(task({ implementation: { headBranch: null } }), true, true)).toBe(false)
    expect(canRequestFix(task({ implementation: { headBranch: '' } }), true, true)).toBe(false)
  })

  it('열린 회차가 있거나 상한을 다 썼으면 못 낸다', () => {
    expect(canRequestFix(task({ fix: fix({ activeRound: 2, activeMode: 'REVIEW', approvedRounds: 2 }) }), true, true)).toBe(false)
    expect(canRequestFix(task({ fix: fix({ approvedRounds: 2 }) }), true, true)).toBe(true)
    expect(canRequestFix(task({ fix: fix({ approvedRounds: 3 }) }), true, true)).toBe(false)
  })

  it('회차 현황이 없는 응답(목록·구 API)에서는 한도를 알 수 없어 열지 않는다', () => {
    expect(canRequestFix(task({ fix: null }), true, true)).toBe(false)
    expect(canRequestFix(task({ fix: undefined }), true, true)).toBe(false)
  })
})

describe('fixLimitReached', () => {
  it('승인된 회차 수가 상한 이상일 때만', () => {
    expect(fixLimitReached(task({ fix: fix({ approvedRounds: 2 }) }))).toBe(false)
    expect(fixLimitReached(task({ fix: fix({ approvedRounds: 3 }) }))).toBe(true)
    expect(fixLimitReached(task({ fix: fix({ approvedRounds: 4 }) }))).toBe(true)
    expect(fixLimitReached(task({ fix: null }))).toBe(false)
  })
})

describe('canFollowUp — 새 작업으로 등록 (스펙 §6.3·§6.5)', () => {
  it('상한을 다 쓴 배포 계열 작업이거나 종료된 작업일 때, 등록자·관리자에게만', () => {
    const full = fix({ approvedRounds: 3 })
    for (const status of ['DEPLOYED', 'DEPLOY_LOST', 'DEPLOY_FAILED']) {
      expect(canFollowUp(task({ status, fix: full }), false, true), status).toBe(true)
      expect(canFollowUp(task({ status, fix: full }), true, false), status).toBe(true)
      expect(canFollowUp(task({ status, fix: full }), false, false), status).toBe(false)
      expect(canFollowUp(task({ status, fix: fix({ approvedRounds: 2 }) }), true, true), status).toBe(false)
    }
    expect(canFollowUp(task({ status: 'CLOSED', fix: fix() }), false, true)).toBe(true)
    expect(canFollowUp(task({ status: 'CLOSED', fix: null }), true, false)).toBe(true)
    expect(canFollowUp(task({ status: 'CLOSED' }), false, false)).toBe(false)
    expect(canFollowUp(task({ status: 'IMPLEMENTING', fix: full }), true, true)).toBe(false)
  })
})

describe('canAbortFix (스펙 §7.1)', () => {
  it('관리자 · 구현실패 · 열린 회차가 있을 때만', () => {
    const open = fix({ activeRound: 1, activeMode: 'REVIEW', approvedRounds: 1 })
    expect(canAbortFix(task({ status: 'IMPLEMENTATION_FAILED', fix: open }), true)).toBe(true)
    expect(canAbortFix(task({ status: 'IMPLEMENTATION_FAILED', fix: open }), false)).toBe(false)
    expect(canAbortFix(task({ status: 'IMPLEMENTATION_FAILED', fix: fix() }), true)).toBe(false)
    expect(canAbortFix(task({ status: 'IMPLEMENTATION_FAILED', fix: null }), true)).toBe(false)
    expect(canAbortFix(task({ status: 'IMPLEMENTING', fix: open }), true)).toBe(false)
  })
})

describe('새 작업 미리 채우기 (스펙 §6.5)', () => {
  it('쿼리·경로는 fromTask 하나', () => {
    expect(followUpPrefillQuery(task())).toEqual({ fromTask: '42' })
    expect(followUpPath(task())).toBe('/tasks?fromTask=42')
  })

  const catalog = [
    { id: 7, host: 'github' },
    { id: 8, host: 'other' },
  ]

  it('같은 레포·브랜치, 제목 접두사, 설명 첫 줄 — 작업종료 전이면 병합 전 안내가 붙는다', () => {
    const p = taskPrefillFromTask(task({ status: 'DEPLOYED' }), catalog)
    expect(p.repoCatalogId).toBe(7)
    expect(p.branch).toBe('main')
    expect(p.title).toBe('[작업 42 후속] 대상장비 탭')
    expect(p.description.split('\n')[0]).toBe('원래 작업: #42 (원래 작업의 변경은 아직 병합 전)')
    expect(p.targetMissing).toBe(false)
  })

  it('작업종료된 작업이면 첫 줄에 원래 작업만 적는다', () => {
    const p = taskPrefillFromTask(task({ status: 'CLOSED' }), catalog)
    expect(p.description.split('\n')[0]).toBe('원래 작업: #42')
  })

  it('카탈로그에 없거나 지원하지 않는 호스트면 대상을 비우고 알린다', () => {
    expect(taskPrefillFromTask(task({ repoCatalogId: 99 }), catalog)).toMatchObject({ repoCatalogId: null, targetMissing: true })
    expect(taskPrefillFromTask(task({ repoCatalogId: 8 }), catalog)).toMatchObject({ repoCatalogId: null, targetMissing: true })
    expect(taskPrefillFromTask(task({ repoCatalogId: null }), catalog)).toMatchObject({ repoCatalogId: null, targetMissing: true })
  })

  it('제목은 서버 상한 500자를 넘지 않는다', () => {
    const p = taskPrefillFromTask(task({ title: '가'.repeat(600) }), catalog)
    expect(p.title.length).toBe(500)
    expect(p.title.startsWith('[작업 42 후속] 가')).toBe(true)
  })
})

describe('표시 문구', () => {
  it('구현 방식 선택지는 리뷰·직접뿐이다 (SDD 없음)', () => {
    expect(FIX_MODES).toEqual(['REVIEW', 'DIRECT'])
  })
  it('요청 상태·결과 라벨', () => {
    expect(fixRequestStatusLabel('PENDING')).toBe('승인 대기')
    expect(fixRequestStatusLabel('APPROVED')).toBe('승인됨')
    expect(fixRequestStatusLabel('REJECTED')).toBe('반려됨')
    expect(fixRequestStatusLabel('WITHDRAWN')).toBe('철회됨')
    expect(fixOutcomeLabel('DEPLOYED')).toBe('재배포 완료')
    expect(fixOutcomeLabel('DEPLOY_FAILED')).toBe('재배포 실패')
    expect(fixOutcomeLabel('ABORTED')).toBe('수정 중단')
    expect(fixOutcomeLabel(null)).toBeNull()
  })
})

describe('기준 브랜치 반영 (BASE_SYNC)', () => {
  const failedTask = (over: Record<string, unknown> = {}) =>
    task({ status: 'CLOSE_FAILED', close: { progress: [] }, fix: fix(), ...over })

  it('canSyncBase: 관리자·종료실패·병합 전·열린 회차 없음일 때만', () => {
    expect(canSyncBase(failedTask(), true)).toBe(true)
    expect(canSyncBase(failedTask({ close: null }), true)).toBe(true)
    expect(canSyncBase(failedTask({ fix: null }), true)).toBe(true)
    expect(canSyncBase(failedTask({ close: { progress: ['MERGED'] } }), true)).toBe(false)
    expect(canSyncBase(failedTask(), false)).toBe(false)
    expect(canSyncBase(failedTask({ status: 'DEPLOYED' }), true)).toBe(false)
    expect(canSyncBase(failedTask({ fix: fix({ activeRound: 1, activeMode: 'DIRECT' }) }), true)).toBe(false)
  })
  it('isBaseSyncRound: 열린 BASE_SYNC 회차만', () => {
    expect(isBaseSyncRound({ fix: { ...fix({ activeRound: 1 }), activeKind: 'BASE_SYNC' } })).toBe(true)
    expect(isBaseSyncRound({ fix: { ...fix({ activeRound: 1 }), activeKind: 'FIX' } })).toBe(false)
    expect(isBaseSyncRound({ fix: { ...fix(), activeKind: 'BASE_SYNC' } })).toBe(false)
    expect(isBaseSyncRound({ fix: null })).toBe(false)
    expect(isBaseSyncRound({})).toBe(false)
  })
  it('fixRoundTitle', () => {
    expect(fixRoundTitle({ kind: 'BASE_SYNC', round: 1 })).toBe('기준 브랜치 반영 1회차')
    expect(fixRoundTitle({ kind: 'FIX', round: 2 })).toBe('2회차')
    expect(fixRoundTitle({ round: 3 })).toBe('3회차')
  })
  it('fixOutcomeLabel: BASE_SYNC 전용 문구, FIX·생략은 기존 값', () => {
    expect(fixOutcomeLabel('NO_CHANGE', 'BASE_SYNC')).toBe('변경 없음')
    expect(fixOutcomeLabel('SYNCED', 'BASE_SYNC')).toBe('반영됨')
    expect(fixOutcomeLabel('DEPLOYED', 'BASE_SYNC')).toBe('재배포됨')
    expect(fixOutcomeLabel('DEPLOY_FAILED', 'BASE_SYNC')).toBe('배포 실패')
    expect(fixOutcomeLabel('ABORTED', 'BASE_SYNC')).toBe('중단')
    expect(fixOutcomeLabel('ABORTED')).toBe('수정 중단')
    expect(fixOutcomeLabel('ABORTED', 'FIX')).toBe('수정 중단')
    expect(fixOutcomeLabel('DEPLOYED', 'FIX')).toBe('재배포 완료')
    expect(fixOutcomeLabel(null, 'BASE_SYNC')).toBeNull()
  })
  it('확인 문구', () => {
    expect(SYNC_BASE_CONFIRM('main')).toBe(
      '종료를 취소하고 작업 브랜치에 기준 브랜치(main)를 반영합니다. 충돌이 있으면 Claude가 해결하고, 끝나면 자동으로 다시 배포됩니다. 배포 결과를 확인한 뒤 종료를 다시 요청해야 합니다.',
    )
    expect(BASE_SYNC_ABORT_CONFIRM).toBe('기준 브랜치 반영을 중단하고 종료 요청 전 상태로 되돌립니다. 작업 브랜치는 바뀌지 않습니다.')
  })
})
