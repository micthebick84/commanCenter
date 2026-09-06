import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskProgressStepper from './TaskProgressStepper.vue'
import { stageSteps } from '~/composables/taskStages'

describe('TaskProgressStepper (스펙 2026-09-06 §4.2)', () => {
  it('4단계를 상태별로 그리고 현재 단계를 강조한다', () => {
    const w = mount(TaskProgressStepper, {
      props: {
        steps: stageSteps({ status: 'PR_CREATED', designRequested: true }),
        caption: 'PR생성 · PR #13',
      },
    })
    const states = ['analysis', 'design', 'impl', 'deploy'].map((k) =>
      w.find(`[data-test="step-${k}"]`).attributes('data-state'),
    )
    expect(states).toEqual(['done', 'done', 'current', 'future'])
    expect(w.find('[data-test="step-impl"]').classes()).toContain(
      'step--current',
    )
    expect(w.find('[data-test="stepper-caption"]').text()).toBe(
      'PR생성 · PR #13',
    )
    expect(w.text()).toContain('분석')
    w.unmount()
  })
  it('compact는 세그먼트 4개 + 현재 단계명만 그린다', () => {
    const w = mount(TaskProgressStepper, {
      props: {
        steps: stageSteps({ status: 'DEPLOYED', designRequested: false }),
        compact: true,
      },
    })
    expect(w.findAll('.seg')).toHaveLength(4)
    expect(w.find('[data-test="step-design"]').attributes('data-state')).toBe(
      'skipped',
    )
    expect(w.text()).toContain('배포')
    w.unmount()
  })
  it('failed 단계는 step--failed', () => {
    const w = mount(TaskProgressStepper, {
      props: {
        steps: stageSteps({ status: 'DEPLOY_FAILED', designRequested: true }),
      },
    })
    expect(w.find('[data-test="step-deploy"]').classes()).toContain(
      'step--failed',
    )
    w.unmount()
  })
})
