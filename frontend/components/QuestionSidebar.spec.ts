import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import QuestionSidebar from './QuestionSidebar.vue'
import { useApiMock, authStub } from '../test/mocks/nuxt'

const today = new Date().toISOString()
const list = [
  {
    id: 3, title: '인증 흐름 확인', githubRepo: 'a/b', githubBranch: 'main', repoAlias: 'netis7.0', requesterId: 'user1',
    status: '답변완료', statusName: 'AWAITING_INPUT', model: 'claude-opus-5', effort: 'high', totalCostUsd: 0.1,
    contextTokens: 76004, contextWindow: 200000, createdAt: today, updatedAt: today,
  },
  {
    id: 4, title: '장비 상태 화면 데이터 소스', githubRepo: 'a/b', githubBranch: 'feature/device-status', repoAlias: 'netis7.0',
    requesterId: 'user2', status: '답변중', statusName: 'RUNNING', model: 'claude-opus-5', effort: 'high', totalCostUsd: 0,
    contextTokens: null, contextWindow: null, createdAt: today, updatedAt: today,
  },
]

describe('QuestionSidebar (스펙 2026-09-05 §3)', () => {
  beforeEach(() => {
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/questions') return Promise.resolve(list)
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      return Promise.resolve(null)
    })
  })
  afterEach(() => {
    authStub.isAdmin = false
  })

  it('목록을 상태·레포 캡션과 함께 그리고, 활성 행을 강조하며, 선택/새 질문을 emit한다', async () => {
    const w = mount(QuestionSidebar, { props: { activeId: 3 } })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', { params: { all: 'false' } })
    const rows = w.findAll('[data-test="question-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.classes()).toContain('active')
    expect(rows[0]!.text()).toContain('답변 완료 · netis7.0 · main')
    expect(rows[1]!.text()).toContain('답변 중')
    expect(w.text()).toContain('오늘')
    await rows[1]!.trigger('click')
    expect(w.emitted('select')![0]).toEqual([4])
    await w.find('[data-test="new-question"]').trigger('click')
    expect(w.emitted('new')).toHaveLength(1)
    expect(w.find('[data-test="usage-panel"]').exists()).toBe(true)
    expect(w.find('[data-test="all-toggle"]').exists()).toBe(false) // 일반 사용자
    w.unmount()
  })

  it('관리자는 전체 보기 토글이 있고, 켜면 all=true로 다시 조회하며 요청자를 캡션에 붙인다', async () => {
    authStub.isAdmin = true
    const w = mount(QuestionSidebar, { props: { activeId: null } })
    await flushPromises()
    const toggle = w.find('[data-test="all-toggle"]')
    expect(toggle.exists()).toBe(true)
    await toggle.trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions', { params: { all: 'true' } })
    expect(w.text()).toContain('· user1')
    w.unmount()
  })

  it('빈 목록이면 안내 문구', async () => {
    useApiMock.mockImplementation((url: string) =>
      Promise.resolve(url === '/api/questions' ? [] : { limits: [] }),
    )
    const w = mount(QuestionSidebar, { props: { activeId: null } })
    await flushPromises()
    expect(w.text()).toContain('아직 질문이 없습니다')
    w.unmount()
  })
})
