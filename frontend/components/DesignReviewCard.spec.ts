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

const TWO = JSON.stringify([
  { path: 'screens/a.html', title: '목록', html: '<p>A</p>' },
  { path: 'screens/b.html', title: '상세', html: '<p>B</p>' },
])

describe('DesignReviewCard — 크게 보기', () => {
  it('links 크게 보기 to the selected screen in a new tab', async () => {
    const w = mount(DesignReviewCard, {
      props: {
        taskId: 12,
        status: 'DESIGN_REVIEW',
        design: baseDesign({ mockupFilesJson: TWO }),
        isAdmin: true,
      },
    })
    const btn = () => w.find('[data-test="design-open-large"]')
    expect(btn().attributes('href')).toBe('/designs/12?screen=1')
    expect(btn().attributes('target')).toBe('_blank')
    expect(btn().attributes('rel')).toContain('noopener')
    expect(btn().text()).toContain('크게 보기')
    await w.findAll('.q-tab')[1].trigger('click')
    expect(btn().attributes('href')).toBe('/designs/12?screen=2')
    w.unmount()
  })

  it('shows no 크게 보기 without mockups', () => {
    const w = mount(DesignReviewCard, {
      props: {
        taskId: 12,
        status: 'DESIGN_REVIEW',
        design: baseDesign({ mockupFilesJson: '[]' }),
        isAdmin: true,
      },
    })
    expect(w.find('[data-test="design-open-large"]').exists()).toBe(false)
    w.unmount()
  })

  it('never shows the Claude Design link or the upload-failed chip, even for old rows with a designUrl', () => {
    const w = mount(DesignReviewCard, {
      props: {
        taskId: 12,
        status: 'DESIGN_REVIEW',
        design: baseDesign({
          mockupFilesJson: TWO,
          designUrl: 'https://claude.ai/design/p/1',
          designProjectId: 'p1',
        }),
        isAdmin: true,
      },
    })
    expect(w.text()).not.toContain('Claude Design')
    expect(w.text()).not.toContain('업로드 실패')
    w.unmount()
  })
})
