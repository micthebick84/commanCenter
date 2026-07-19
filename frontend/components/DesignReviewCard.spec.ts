import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import DesignReviewCard from './DesignReviewCard.vue'

function baseDesign(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    designMarkdown: '# 설계',
    mockupFilesJson: '[]',
    designProjectId: null,
    designUrl: null,
    rejectCount: 0,
    feedbackHistoryJson: '[]',
    approved: false,
    approvedBy: null,
    approvedAt: null,
    completedAt: '',
    ...overrides,
  }
}

describe('DesignReviewCard — admin review actions', () => {
  it('renders approve/reject buttons when status is DESIGN_REVIEW and isAdmin is true', () => {
    const w = mount(DesignReviewCard, {
      props: {
        taskId: 1,
        status: 'DESIGN_REVIEW',
        design: baseDesign(),
        isAdmin: true,
      },
    })
    expect(w.find('[data-test="design-approve"]').exists()).toBe(true)
    expect(w.find('[data-test="design-reject"]').exists()).toBe(true)
    w.unmount()
  })

  it('disables the reject button once rejectCount reaches the limit (3)', () => {
    const w = mount(DesignReviewCard, {
      props: {
        taskId: 1,
        status: 'DESIGN_REVIEW',
        design: baseDesign({ rejectCount: 3 }),
        isAdmin: true,
      },
    })
    const reject = w.find('[data-test="design-reject"]')
    expect(reject.exists()).toBe(true)
    expect(reject.attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('renders no approve/reject buttons when isAdmin is false', () => {
    const w = mount(DesignReviewCard, {
      props: {
        taskId: 1,
        status: 'DESIGN_REVIEW',
        design: baseDesign(),
        isAdmin: false,
      },
    })
    expect(w.find('[data-test="design-approve"]').exists()).toBe(false)
    expect(w.find('[data-test="design-reject"]').exists()).toBe(false)
    w.unmount()
  })
})
