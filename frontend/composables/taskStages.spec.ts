import { describe, it, expect } from 'vitest'
import {
  STAGES, CHIP, MOVES, buildStages, moveFor, stageAccepts, ageOf, agePct, ageColor,
  attentionGroup, sortForMobile, stageSteps, nextAction, cancelable, DEPLOY_ACTIVE_STATUSES,
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
  'CLOSE_REVIEW', 'CLOSE_PENDING', 'CLOSING', 'CLOSED', 'CLOSE_FAILED',
  'FIX_REVIEW',
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

  it('카드 미니 진행바: 작업종료는 전 단계 완료(모바일 stageSteps와 같게), 취소됨은 전부 회색', () => {
    const stages = buildStages(
      [task({ id: 1, status: 'CLOSED', statusLabel: '작업종료' }), task({ id: 2, status: 'CANCELLED', statusLabel: '취소됨' })],
      { now: NOW },
    )
    const doneCard = stages.find((s) => s.key === 'done')!.groups[0].cards[0]
    expect(doneCard.steps.map((x) => x.bg)).toEqual(Array(5).fill('#a5d6a7'))
    expect(stageSteps({ status: 'CLOSED' }).every((x) => x.state === 'done')).toBe(true)
    const cancelledCard = stages.find((s) => s.key === 'closed')!.groups[0].cards[0]
    expect(cancelledCard.steps.map((x) => x.bg)).toEqual(Array(5).fill('#eeeeee'))
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
      ['CLOSE_FAILED', 'CLOSE_REVIEW', 'COMPLETED', 'DEPLOYED', 'DEPLOY_FAILED', 'DEPLOY_LOST', 'DESIGN_REVIEW', 'PR_CREATED'].sort(),
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

describe('attentionGroup / sortForMobile (스펙 2026-09-06 §4.1)', () => {
  const t = (id: number, status: string, updatedAt: string) => ({ id, status, updatedAt })
  it.each([
    ['INTERVIEW_INPUT', false, 'attention'], ['INTERVIEW_REVIEW', false, 'attention'],
    ['FAILED', false, 'attention'], ['IMPLEMENTATION_FAILED', false, 'attention'], ['DESIGN_FAILED', false, 'attention'],
    ['DEPLOY_FAILED', false, 'attention'], ['DEPLOY_LOST', false, 'attention'],
    ['AWAITING_APPROVAL', true, 'attention'], ['COMPLETED', true, 'attention'], ['DESIGN_REVIEW', true, 'attention'],
    ['AWAITING_APPROVAL', false, 'active'], ['COMPLETED', false, 'active'], ['DESIGN_REVIEW', false, 'active'],
    ['INTERVIEWING', false, 'active'], ['PENDING', false, 'active'], ['IN_PROGRESS', false, 'active'], ['APPROVED', false, 'active'],
    ['IMPLEMENTING', false, 'active'], ['DESIGN_PENDING', false, 'active'], ['DESIGNING', false, 'active'],
    ['DEPLOY_PENDING', false, 'active'], ['DEPLOYING', false, 'active'], ['UNDEPLOY_PENDING', false, 'active'], ['UNDEPLOYING', false, 'active'],
    ['PR_CREATED', false, 'done'], ['DEPLOYED', false, 'done'], ['CANCELLED', false, 'closed'],
  ])('%s (admin=%s) → %s', (status, isAdmin, group) => {
    expect(attentionGroup({ status }, isAdmin)).toBe(group)
  })

  it('그룹 순서 확인 필요→진행 중→완료→취소됨, 그룹 안은 updatedAt 내림차순, 빈 그룹 제외', () => {
    const groups = sortForMobile(
      [t(1, 'PR_CREATED', '2026-09-01T00:00:00Z'), t(2, 'INTERVIEW_INPUT', '2026-08-01T00:00:00Z'),
       t(3, 'PR_CREATED', '2026-09-03T00:00:00Z'), t(4, 'CANCELLED', '2026-09-02T00:00:00Z')],
      false,
    )
    expect(groups.map((g) => g.key)).toEqual(['attention', 'done', 'closed'])
    expect(groups[1]!.items.map((i) => i.id)).toEqual([3, 1])
    expect(groups[0]!.label).toBe('확인 필요')
  })
})

describe('stageSteps', () => {
  it('PR생성(디자인 미요청): 분석 done · 디자인 skipped · 구현 current · 배포 future', () => {
    expect(stageSteps({ status: 'PR_CREATED', designRequested: false }).map((s) => s.state))
      .toEqual(['done', 'skipped', 'current', 'future', 'future'])
  })
  it('디자인승인대기(디자인 요청): 분석 done · 디자인 current', () => {
    expect(stageSteps({ status: 'DESIGN_REVIEW', designRequested: true }).map((s) => s.state))
      .toEqual(['done', 'current', 'future', 'future', 'future'])
  })
  it('배포실패: 배포 단계가 failed', () => {
    const steps = stageSteps({ status: 'DEPLOY_FAILED', designRequested: true })
    expect(steps[3]!.state).toBe('failed')
    expect(steps.map((s) => s.label)).toEqual(['분석', '디자인', '구현', '배포', '종료'])
  })
  it('승인대기: 분석 current, 나머지 future(디자인 요청 시)/skipped(미요청)', () => {
    expect(stageSteps({ status: 'AWAITING_APPROVAL', designRequested: true })[0]!.state).toBe('current')
    expect(stageSteps({ status: 'AWAITING_APPROVAL', designRequested: false })[1]!.state).toBe('skipped')
  })
  it('취소됨: 전부 future', () => {
    expect(stageSteps({ status: 'CANCELLED' }).every((s) => s.state === 'future')).toBe(true)
  })
})

describe('nextAction (스펙 2026-09-06 §4.2 표)', () => {
  const pr = { status: 'PR_CREATED', implementation: { prUrl: 'https://x/pull/1' }, deployment: null }
  it('승인대기: 관리자는 승인 주 행동, 요청자는 문구만', () => {
    expect(nextAction({ status: 'AWAITING_APPROVAL' }, true)?.primary?.kind).toBe('approve-interview')
    expect(nextAction({ status: 'AWAITING_APPROVAL' }, false)?.primary).toBeUndefined()
  })
  it('입력대기/플랜승인대기: 관리자 주 행동은 인터뷰 열기', () => {
    expect(nextAction({ status: 'INTERVIEW_INPUT' }, true)?.primary?.kind).toBe('open-interview')
    expect(nextAction({ status: 'INTERVIEW_REVIEW' }, true)?.primary?.kind).toBe('open-interview')
    expect(nextAction({ status: 'INTERVIEW_INPUT' }, false)?.primary).toBeUndefined()
  })
  it('분석완료: 관리자 구현 승인 · 디자인승인대기: 관리자 디자인 검토', () => {
    expect(nextAction({ status: 'COMPLETED' }, true)?.primary?.kind).toBe('approve-impl')
    expect(nextAction({ status: 'DESIGN_REVIEW' }, true)?.primary?.kind).toBe('review-design')
  })
  it('PR생성: 관리자 배포, 요청자 PR 열기(prUrl 있을 때만)', () => {
    expect(nextAction(pr, true)?.primary?.kind).toBe('deploy')
    expect(nextAction(pr, false)?.primary?.kind).toBe('open-pr')
    expect(nextAction({ status: 'PR_CREATED', implementation: { prUrl: null } }, false)?.primary).toBeUndefined()
  })
  it('배포완료: 접속 URL 열기(양쪽) · 배포실패/중단: 관리자 재배포', () => {
    expect(nextAction({ status: 'DEPLOYED', deployment: { deployUrl: 'https://t' } }, false)?.primary?.kind).toBe('open-url')
    expect(nextAction({ status: 'DEPLOY_FAILED' }, true)?.primary?.kind).toBe('redeploy')
    expect(nextAction({ status: 'DEPLOY_LOST' }, false)?.primary).toBeUndefined()
  })
  it('분석실패/디자인실패: 재시도 · 구현실패: 관리자는 구현 재시도 · 취소됨: null · 진행 중: 문구만', () => {
    expect(nextAction({ status: 'FAILED' }, false)?.primary?.kind).toBe('retry')
    expect(nextAction({ status: 'DESIGN_FAILED' }, true)?.primary?.kind).toBe('retry')
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, true)?.primary?.kind).toBe('retry-implementation')
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, false)?.primary).toBeUndefined()
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED' }, false)?.text).toContain('관리자 확인이 필요합니다')
    expect(nextAction({ status: 'CANCELLED' }, true)).toBeNull()
    expect(nextAction({ status: 'IMPLEMENTING' }, true)?.text).toContain('진행 중')
  })
  it('cancelable/DEPLOY_ACTIVE_STATUSES는 index.vue와 같은 집합', () => {
    expect(cancelable({ status: 'PENDING' })).toBe(true)
    expect(cancelable({ status: 'PR_CREATED' })).toBe(false)
    expect(DEPLOY_ACTIVE_STATUSES).toEqual([
      'DEPLOYED', 'DEPLOY_LOST', 'DEPLOY_PENDING', 'DEPLOYING', 'UNDEPLOY_PENDING', 'UNDEPLOYING',
      'CLOSE_REVIEW', 'CLOSE_PENDING', 'CLOSING', 'CLOSE_FAILED',
      'FIX_REVIEW',
    ])
  })
})

describe('종료 단계 (스펙 2026-10-13)', () => {
  it('종료 4상태는 종료 단계, 작업종료는 terminal 단계', () => {
    const close = STAGES.find((s) => s.key === 'close')!
    expect(close.statuses).toEqual(['CLOSE_REVIEW', 'CLOSE_PENDING', 'CLOSING', 'CLOSE_FAILED'])
    const done = STAGES.find((s) => s.key === 'done')!
    expect(done.terminal).toBe(true)
    expect(done.statuses).toEqual(['CLOSED'])
  })
  it('관리자 드래그: 종료승인대기 → 종료대기, 종료실패 → 종료대기 (둘 다 확인창)', () => {
    expect(moveFor({ status: 'CLOSE_REVIEW' })).toMatchObject({ to: 'CLOSE_PENDING', path: 'close/approve', confirm: true })
    expect(moveFor({ status: 'CLOSE_FAILED' })).toMatchObject({ to: 'CLOSE_PENDING', path: 'close/retry', confirm: true })
    expect(stageAccepts('close', moveFor({ status: 'CLOSE_REVIEW' }))).toBe(true)
  })
  it('배포완료: 등록자·관리자는 종료 요청, 그 외에는 접속 URL 열기', () => {
    const t = { status: 'DEPLOYED', deployment: { deployUrl: 'https://t' } }
    expect(nextAction(t, false, true)?.primary?.kind).toBe('request-close')
    expect(nextAction(t, true, false)?.primary?.kind).toBe('request-close')
    expect(nextAction(t, false, false)?.primary?.kind).toBe('open-url')
  })
  it('배포중단됨: 등록자는 종료 요청, 관리자는 재배포가 주 행동', () => {
    expect(nextAction({ status: 'DEPLOY_LOST' }, false, true)?.primary?.kind).toBe('request-close')
    expect(nextAction({ status: 'DEPLOY_LOST' }, true, true)?.primary?.kind).toBe('redeploy')
  })
  it('종료승인대기: 관리자 승인, 등록자 철회, 그 외 문구', () => {
    expect(nextAction({ status: 'CLOSE_REVIEW' }, true, false)?.primary?.kind).toBe('approve-close')
    expect(nextAction({ status: 'CLOSE_REVIEW' }, false, true)?.primary?.kind).toBe('withdraw-close')
    expect(nextAction({ status: 'CLOSE_REVIEW' }, false, false)?.primary).toBeUndefined()
  })
  it('종료실패: 관리자 재시도 · 작업종료: 주 행동 없음', () => {
    expect(nextAction({ status: 'CLOSE_FAILED' }, true)?.primary?.kind).toBe('retry-close')
    expect(nextAction({ status: 'CLOSE_FAILED' }, false)?.primary).toBeUndefined()
    expect(nextAction({ status: 'CLOSED' }, true)?.primary).toBeUndefined()
  })
  it('주의 그룹: 종료실패는 확인 필요, 종료승인대기는 관리자에게만, 작업종료는 완료', () => {
    expect(attentionGroup({ status: 'CLOSE_FAILED' }, false)).toBe('attention')
    expect(attentionGroup({ status: 'CLOSE_REVIEW' }, true)).toBe('attention')
    expect(attentionGroup({ status: 'CLOSE_REVIEW' }, false)).toBe('active')
    expect(attentionGroup({ status: 'CLOSED' }, false)).toBe('done')
  })
  it('스테퍼: 종료실패는 종료 단계 failed, 작업종료는 전부 future가 아니라 전부 done', () => {
    const failed = stageSteps({ status: 'CLOSE_FAILED', designRequested: false })
    expect(failed[4]!.state).toBe('failed')
    expect(stageSteps({ status: 'CLOSED', designRequested: true }).every((s) => s.state === 'done')).toBe(true)
  })
  it('삭제 가드 집합에 종료 진행 상태가 있다', () => {
    for (const s of ['CLOSE_REVIEW', 'CLOSE_PENDING', 'CLOSING', 'CLOSE_FAILED']) {
      expect(DEPLOY_ACTIVE_STATUSES).toContain(s)
    }
  })
})

describe('수정 요청 단계 (스펙 2026-11-03 §6, 수정승인대기)', () => {
  it('수정승인대기는 배포 단계 열에 들어가고 칩 색이 있다', () => {
    const deploy = STAGES.find((s) => s.key === 'deploy')!
    expect(deploy.statuses).toContain('FIX_REVIEW')
    expect(STAGES.filter((s) => s.statuses.includes('FIX_REVIEW'))).toHaveLength(1)
    expect(CHIP.FIX_REVIEW).toBeDefined()
  })
  it('드래그 전이는 없다 (승인에 구현 방식 선택이 필요하다)', () => {
    expect(MOVES.FIX_REVIEW).toBeUndefined()
    expect(moveFor({ status: 'FIX_REVIEW' })).toBeNull()
  })
  it('배포 단계 카드로 묶이고 라벨은 서버 statusLabel을 쓴다', () => {
    const stages = buildStages([task({ status: 'FIX_REVIEW', statusLabel: '수정승인대기' })], { now: NOW, isAdmin: true })
    const deploy = stages.find((s) => s.key === 'deploy')!
    expect(deploy.groups.map((g) => g.label)).toEqual(['수정승인대기'])
    expect(deploy.groups[0]!.cards[0]!.move).toBeNull()
  })
  it('다음 할 일: 관리자는 수정 승인, 등록자는 요청 철회, 그 외는 문구만', () => {
    expect(nextAction({ status: 'FIX_REVIEW' }, true, false)?.primary).toMatchObject({ label: '수정 승인', kind: 'approve-fix' })
    expect(nextAction({ status: 'FIX_REVIEW' }, true, true)?.primary?.kind).toBe('approve-fix')
    expect(nextAction({ status: 'FIX_REVIEW' }, false, true)?.primary).toMatchObject({ label: '수정 요청 철회', kind: 'withdraw-fix' })
    const other = nextAction({ status: 'FIX_REVIEW' }, false, false)
    expect(other?.primary).toBeUndefined()
    expect(other?.text).toContain('수정 요청')
  })
  it('주의 그룹: 관리자에게만 확인 필요', () => {
    expect(attentionGroup({ status: 'FIX_REVIEW' }, true)).toBe('attention')
    expect(attentionGroup({ status: 'FIX_REVIEW' }, false)).toBe('active')
  })
  it('스테퍼: 배포 단계가 current (실패 아님)', () => {
    const steps = stageSteps({ status: 'FIX_REVIEW', designRequested: false })
    expect(steps.find((s) => s.key === 'deploy')!.state).toBe('current')
  })
  it('삭제 가드 집합에 있다 (서버 softDelete 가드와 같다)', () => {
    expect(DEPLOY_ACTIVE_STATUSES).toContain('FIX_REVIEW')
  })
})

describe('DB 작업 PR생성의 "확인 완료 — 종료 요청" (스펙 2026-11-03 §6.9)', () => {
  const dbPr = { status: 'PR_CREATED', taskType: 'DB_WORK', implementation: { prUrl: 'https://x/pull/1' }, deployment: null }

  it('DB 작업 PR생성: 관리자·등록자는 종료 요청, 다른 타입은 지금처럼 배포', () => {
    expect(nextAction({ status: 'PR_CREATED', taskType: 'DB_WORK' }, true)?.primary?.kind).toBe('request-close')
    expect(nextAction({ status: 'PR_CREATED', taskType: 'DB_WORK' }, false, true)?.primary?.kind).toBe('request-close')
    expect(nextAction({ status: 'PR_CREATED', taskType: 'BUG_FIX' }, true)?.primary?.kind).toBe('deploy')
  })

  it('문구와 주 행동은 정해진 값이다', () => {
    expect(nextAction(dbPr, true)).toEqual({
      text: 'DB 작업 — 개발 DB 반영을 확인했으면 종료를 요청하세요',
      primary: { label: '확인 완료 — 종료 요청', icon: 'task_alt', kind: 'request-close' },
    })
    expect(nextAction(dbPr, false, true)).toEqual(nextAction(dbPr, true))
  })

  it('등록자도 관리자도 아니면 지금처럼 PR 열기 — 타입 없음·null·다른 타입도 그대로', () => {
    expect(nextAction(dbPr, false, false)?.primary?.kind).toBe('open-pr')
    const pr = { implementation: { prUrl: 'https://x/pull/1' } }
    expect(nextAction({ status: 'PR_CREATED', ...pr }, true)?.primary?.kind).toBe('deploy')
    expect(nextAction({ status: 'PR_CREATED', taskType: null, ...pr }, true)?.primary?.kind).toBe('deploy')
    expect(nextAction({ status: 'PR_CREATED', taskType: null, ...pr }, false, true)?.primary?.kind).toBe('open-pr')
    expect(nextAction({ status: 'PR_CREATED', taskType: 'NEW_FEATURE', ...pr }, false, true)?.primary?.kind).toBe('open-pr')
  })

  it('PR생성이 아닌 DB 작업은 타입과 무관하게 지금 표 그대로', () => {
    expect(nextAction({ status: 'IMPLEMENTATION_FAILED', taskType: 'DB_WORK' }, true)?.primary?.kind).toBe('retry-implementation')
    expect(nextAction({ status: 'DEPLOYED', taskType: 'DB_WORK', deployment: { deployUrl: 'https://t' } }, false, false)?.primary?.kind).toBe('open-url')
  })

  it('attentionGroup: DB 작업 PR생성도 배포완료처럼 done — 소유자 인자가 없어 전역 배지 수를 늘리지 않는다', () => {
    const t = { status: 'PR_CREATED', taskType: 'DB_WORK' }
    expect(attentionGroup(t, true)).toBe('done')
    expect(attentionGroup(t, false)).toBe('done')
    expect(attentionGroup({ status: 'DEPLOYED' }, true)).toBe('done')
  })

  it('stageSteps: 배포 없이 종료한 DB 작업은 deploy skipped, 배포했으면 done', () => {
    const deployState = (task: Record<string, unknown>) => stageSteps(task as { status: string }).find((s) => s.key === 'deploy')!.state
    // 종료승인대기·작업종료까지 배포 없이 온 DB 작업 — 배포 단계는 건너뜀
    expect(deployState({ status: 'CLOSE_REVIEW', taskType: 'DB_WORK', deployment: null })).toBe('skipped')
    expect(deployState({ status: 'CLOSING', taskType: 'DB_WORK' })).toBe('skipped') // deployment 키 없음도 "없음"
    expect(deployState({ status: 'CLOSED', taskType: 'DB_WORK', deployment: null })).toBe('skipped')
    // 배포까지 한 DB 작업은 다른 작업과 같다
    expect(deployState({ status: 'CLOSE_REVIEW', taskType: 'DB_WORK', deployment: { deployUrl: 'http://x' } })).toBe('done')
    expect(deployState({ status: 'CLOSED', taskType: 'DB_WORK', deployment: { deployUrl: null } })).toBe('done')
    // 다른 타입·타입 없음은 배포 없이 종료 단계에 있어도 건드리지 않는다
    expect(deployState({ status: 'CLOSE_REVIEW', taskType: 'BUG_FIX', deployment: null })).toBe('done')
    expect(deployState({ status: 'CLOSE_REVIEW', taskType: null, deployment: null })).toBe('done')
    expect(deployState({ status: 'CLOSED', deployment: null })).toBe('done')
  })

  it('stageSteps: PR생성의 DB 작업은 아직 배포를 고를 수 있으니 deploy는 future 그대로', () => {
    expect(stageSteps({ status: 'PR_CREATED', designRequested: false, taskType: 'DB_WORK', deployment: null }).map((s) => s.state))
      .toEqual(['done', 'skipped', 'current', 'future', 'future'])
  })

  it('stageSteps: 배포를 거치는 중(배포 실패·진행)인 DB 작업의 deploy는 current·failed 그대로', () => {
    const deploy = (status: string) => stageSteps({ status, taskType: 'DB_WORK', deployment: { deployUrl: null } }).find((s) => s.key === 'deploy')!.state
    expect(deploy('DEPLOYING')).toBe('current')
    expect(deploy('DEPLOY_FAILED')).toBe('failed')
  })
})
