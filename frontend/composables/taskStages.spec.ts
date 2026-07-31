import { describe, it, expect } from 'vitest'
import {
  STAGES, CHIP, MOVES, buildStages, moveFor, stageAccepts, ageOf, agePct, ageColor,
} from './taskStages'

// entity/TaskStatus.java 의 전체 상태. 백엔드에 상태를 추가하면 여기도 추가해야 하고,
// 그러면 아래 커버리지 테스트가 "어느 단계에도 안 들어간다"고 바로 잡아준다.
const ALL_STATUSES = [
  'AWAITING_APPROVAL', 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED',
  'APPROVED', 'IMPLEMENTING', 'PR_CREATED', 'IMPLEMENTATION_FAILED',
  'DEPLOY_PENDING', 'DEPLOYING', 'DEPLOYED', 'DEPLOY_FAILED', 'DEPLOY_LOST',
  'UNDEPLOY_PENDING', 'UNDEPLOYING',
  'DESIGN_PENDING', 'DESIGNING', 'DESIGN_REVIEW', 'DESIGN_FAILED',
  'INTERVIEWING', 'INTERVIEW_INPUT', 'INTERVIEW_REVIEW',
  'CANCELLED',
]

const task = (over: Partial<any> = {}) => ({
  id: 1,
  status: 'PR_CREATED',
  statusLabel: 'PR생성',
  createdAt: '2026-07-01T00:00:00Z',
  ...over,
})

const NOW = new Date('2026-07-31T00:00:00Z').getTime()

describe('taskStages — 커버리지 불변식', () => {
  // 원본 목업이 인터뷰 3종과 취소됨을 빠뜨려 해당 작업이 화면에서 증발했다. 그 재발 방지.
  it('모든 TaskStatus가 정확히 한 단계에 속한다', () => {
    for (const status of ALL_STATUSES) {
      const owners = STAGES.filter((s) => s.statuses.includes(status)).map((s) => s.key)
      expect(owners, `${status} 를 담는 단계`).toHaveLength(1)
    }
  })

  it('단계에 정의된 상태 중 실재하지 않는 것이 없다', () => {
    const declared = STAGES.flatMap((s) => s.statuses)
    expect(declared.filter((s) => !ALL_STATUSES.includes(s))).toEqual([])
  })

  it('모든 상태에 칩 색이 있다', () => {
    for (const status of ALL_STATUSES) expect(CHIP[status], status).toBeDefined()
  })
})

describe('taskStages — buildStages', () => {
  it('세부 상태별로 묶고 단계 정의 순서를 따른다', () => {
    const stages = buildStages(
      [
        task({ id: 1, status: 'IMPLEMENTING', statusLabel: '구현중' }),
        task({ id: 2, status: 'APPROVED', statusLabel: '구현대기' }),
        task({ id: 3, status: 'APPROVED', statusLabel: '구현대기' }),
      ],
      { now: NOW },
    )
    const impl = stages.find((s) => s.key === 'impl')!
    expect(impl.count).toBe(3)
    // STAGES.impl.statuses 순서: APPROVED → IMPLEMENTING → …
    expect(impl.groups.map((g) => g.status)).toEqual(['APPROVED', 'IMPLEMENTING'])
    expect(impl.groups[0].count).toBe(2)
    expect(impl.summary).toBe('구현대기 2 · 구현중 1')
  })

  it('취소됨은 카드가 있을 때만 단계로 나타난다 (파이프라인 단계가 아님)', () => {
    const without = buildStages([task()], { now: NOW })
    expect(without.map((s) => s.key)).not.toContain('closed')

    const withCancelled = buildStages(
      [task(), task({ id: 9, status: 'CANCELLED', statusLabel: '취소됨' })],
      { now: NOW },
    )
    const closed = withCancelled.find((s) => s.key === 'closed')!
    expect(closed.count).toBe(1)
    expect(closed.terminal).toBe(true)
  })

  it('showEmptyStages=false 면 빈 파이프라인 단계를 감춘다', () => {
    const stages = buildStages([task()], { now: NOW, showEmptyStages: false })
    expect(stages.map((s) => s.key)).toEqual(['impl'])
  })

  it('오래된 작업이 먼저 온다', () => {
    const stages = buildStages(
      [
        task({ id: 1, createdAt: '2026-07-30T00:00:00Z' }),
        task({ id: 2, createdAt: '2026-05-01T00:00:00Z' }),
      ],
      { now: NOW },
    )
    expect(stages.find((s) => s.key === 'impl')!.groups[0].cards.map((c) => c.task.id)).toEqual([2, 1])
  })

  it('최다 단계가 4건 이상이면 병목으로 표시한다', () => {
    const four = [1, 2, 3, 4].map((id) => task({ id }))
    const stages = buildStages(four, { now: NOW })
    expect(stages.find((s) => s.key === 'impl')!.bottleneck).toBe(true)
    expect(stages.find((s) => s.key === 'deploy')!.bottleneck).toBe(false)
  })

  it('비관리자에게는 드래그 전이를 주지 않는다', () => {
    const asUser = buildStages([task()], { now: NOW })
    const asAdmin = buildStages([task()], { now: NOW, isAdmin: true })
    expect(asUser.find((s) => s.key === 'impl')!.groups[0].cards[0].move).toBeNull()
    expect(asAdmin.find((s) => s.key === 'impl')!.groups[0].cards[0].move?.path).toBe('deploy')
  })
})

describe('taskStages — 전이', () => {
  it('허용 전이만 존재하고 배포 계열만 확인을 받는다', () => {
    expect(Object.keys(MOVES).sort()).toEqual(
      ['COMPLETED', 'DEPLOYED', 'DEPLOY_FAILED', 'DEPLOY_LOST', 'DESIGN_REVIEW', 'PR_CREATED'].sort(),
    )
    expect(MOVES.COMPLETED.confirm).toBe(false)
    expect(MOVES.PR_CREATED.confirm).toBe(true)
    expect(MOVES.DEPLOYED.confirm).toBe(true)
  })

  it('디자인 요청 작업의 분석완료 승인은 디자인대기로 간다', () => {
    expect(moveFor({ status: 'COMPLETED' })!.to).toBe('APPROVED')
    expect(moveFor({ status: 'COMPLETED', designRequested: true })!.to).toBe('DESIGN_PENDING')
  })

  it('전이가 없는 상태는 드롭 대상을 만들지 않는다', () => {
    expect(moveFor({ status: 'INTERVIEWING' })).toBeNull()
    expect(stageAccepts('impl', null)).toBe(false)
  })

  it('드롭은 목표 상태를 담는 단계만 받는다', () => {
    const move = moveFor({ status: 'PR_CREATED' })
    expect(stageAccepts('deploy', move)).toBe(true)
    expect(stageAccepts('impl', move)).toBe(false)
  })
})

describe('taskStages — 경과 표시', () => {
  it('분/시간/일로 접는다', () => {
    expect(ageOf('2026-07-30T23:30:00Z', NOW)).toBe('30분')
    expect(ageOf('2026-07-30T18:00:00Z', NOW)).toBe('6시간')
    expect(ageOf('2026-07-24T00:00:00Z', NOW)).toBe('7일')
  })

  it('60일 만점 막대와 7일/30일 경계 색', () => {
    expect(agePct('2026-06-01T00:00:00Z', NOW)).toBe(100) // 60일 초과 → 상한
    expect(ageColor('2026-07-29T00:00:00Z', NOW)).toBe('#43a047') // 7일 이내
    expect(ageColor('2026-07-20T00:00:00Z', NOW)).toBe('#ef6c00') // 7일 초과
    expect(ageColor('2026-06-01T00:00:00Z', NOW)).toBe('#c62828') // 30일 초과
  })
})
