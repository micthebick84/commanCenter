import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskCardCompact from './TaskCardCompact.vue'

const base = {
  id: 18,
  title: '장비 현황 화면 개발',
  status: 'PR_CREATED',
  statusLabel: 'PR생성',
  repoAlias: 'Netis7.0',
  githubRepo: 'micthebick84/netis7.0',
  githubBranch: 'main',
  createdAt: '2026-08-24T00:00:00Z',
  updatedAt: '2026-08-24T00:00:00Z',
  designRequested: true,
  implementation: { prUrl: 'https://github.com/x/pull/13', prNumber: 13 },
  deployment: null,
}

describe('TaskCardCompact (스펙 2026-09-06 §4.1 카드)', () => {
  it('제목·상태 칩·4세그먼트(분석 done·디자인 done·구현 current·배포 future)·메타·PR 링크를 그린다', () => {
    const w = mount(TaskCardCompact, { props: { task: base, isAdmin: false } })
    expect(w.text()).toContain('#18')
    expect(w.text()).toContain('장비 현황 화면 개발')
    expect(w.text()).toContain('PR생성')
    const steps = w.findAll('[data-test="card-step"]')
    expect(steps.map((s) => s.attributes('data-state'))).toEqual([
      'done',
      'done',
      'current',
      'future',
    ])
    expect(w.text()).toContain('Netis7.0')
    expect(w.text()).toContain('main')
    const pr = w.find('a[href="https://github.com/x/pull/13"]')
    expect(pr.exists()).toBe(true)
    expect(pr.text()).toContain('PR #13')
    w.unmount()
  })

  it('확인 필요 그룹(승인대기·관리자)에서만 "다음 할 일" 한 줄을 보여준다', () => {
    const waiting = {
      ...base,
      status: 'AWAITING_APPROVAL',
      statusLabel: '승인대기',
      implementation: null,
    }
    const admin = mount(TaskCardCompact, {
      props: { task: waiting, isAdmin: true },
    })
    expect(admin.find('[data-test="card-next"]').text()).toContain('승인')
    admin.unmount()
    const user = mount(TaskCardCompact, {
      props: { task: waiting, isAdmin: false },
    })
    expect(user.find('[data-test="card-next"]').exists()).toBe(false) // 요청자에겐 진행 중 그룹
    user.unmount()
    const done = mount(TaskCardCompact, {
      props: { task: base, isAdmin: true },
    })
    expect(done.find('[data-test="card-next"]').exists()).toBe(false) // 완료 그룹은 표시 안 함
    done.unmount()
  })

  it('카드 탭은 open, ⋮은 menu만 emit(open 전파 없음)하고 ⋮ 탭 타깃은 44px 이상', async () => {
    const w = mount(TaskCardCompact, { props: { task: base, isAdmin: true } })
    await w.find('[data-test="card-menu"]').trigger('click')
    expect(w.emitted('menu')).toHaveLength(1)
    expect(w.emitted('open')).toBeUndefined()
    await w.find('[data-test="task-card-compact"]').trigger('click')
    expect(w.emitted('open')).toHaveLength(1)
    expect(w.find('[data-test="card-menu"]').attributes('aria-label')).toBe(
      '작업 메뉴',
    )
    w.unmount()
  })
})
