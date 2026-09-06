import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ApproveTaskSummary from './ApproveTaskSummary.vue'
import { baseTask } from '../../test/fixtures/task'

const twoDaysAgo = new Date(Date.now() - 2 * 24 * 3600_000).toISOString()
const longText = Array.from(
  { length: 12 },
  (_, i) => `${i + 1}번째 줄 — 요청 상세가 길어지는 경우`,
).join('\n')

function mountSummary(overrides: Record<string, unknown> = {}) {
  return mount(ApproveTaskSummary, {
    props: { task: { ...baseTask, createdAt: twoDaysAgo, ...overrides } },
  })
}

describe('ApproveTaskSummary (승인 다이얼로그 — 어떤 작업을 승인하는지 요약)', () => {
  it('번호·제목·레포/브랜치·요청자·등록 경과를 보여준다', () => {
    const w = mountSummary({
      id: 21,
      title: '활동 스트림 스모크',
      repoAlias: 'Netis7.0',
      githubBranch: 'main',
      requesterId: 'admin',
    })
    expect(w.find('[data-test="summary-title"]').text()).toContain('#21')
    expect(w.find('[data-test="summary-title"]').text()).toContain(
      '활동 스트림 스모크',
    )
    expect(w.find('[data-test="summary-repo"]').text()).toContain('Netis7.0')
    expect(w.find('[data-test="summary-repo"]').text()).toContain('main')
    const meta = w.find('[data-test="summary-meta"]').text()
    expect(meta).toContain('요청자 admin')
    expect(meta).toContain('2일 전 등록')
    w.unmount()
  })

  it('별칭이 없으면 레포 전체 이름을, 첨부가 있으면 개수를 보여준다', () => {
    const w = mountSummary({
      repoAlias: null,
      githubRepo: 'acme/widgets',
      attachments: [{ id: 1 }, { id: 2 }],
    })
    expect(w.find('[data-test="summary-repo"]').text()).toContain(
      'acme/widgets',
    )
    expect(w.find('[data-test="summary-meta"]').text()).toContain('첨부 2')
    w.unmount()
  })

  it('첨부가 없으면 첨부 항목을 아예 쓰지 않는다', () => {
    const w = mountSummary({ attachments: [] })
    expect(w.find('[data-test="summary-meta"]').text()).not.toContain('첨부')
    w.unmount()
  })

  it('요청 상세는 접힌 채(3줄 클램프)로 시작하고 긴 글이면 더 보기로 펼친다', async () => {
    const w = mountSummary({ description: longText })
    const desc = w.find('[data-test="summary-desc"]')
    expect(desc.text()).toContain('1번째 줄')
    expect(desc.classes()).toContain('clamped')
    const more = w.find('[data-test="summary-more"]')
    expect(more.text()).toContain('더 보기')
    await more.trigger('click')
    expect(w.find('[data-test="summary-desc"]').classes()).not.toContain(
      'clamped',
    )
    expect(w.find('[data-test="summary-more"]').text()).toContain('접기')
    w.unmount()
  })

  it('짧은 요청 상세에는 더 보기 토글이 없다', () => {
    const w = mountSummary({ description: '한 줄짜리 요청' })
    expect(w.find('[data-test="summary-desc"]').text()).toContain(
      '한 줄짜리 요청',
    )
    expect(w.find('[data-test="summary-more"]').exists()).toBe(false)
    w.unmount()
  })
})
