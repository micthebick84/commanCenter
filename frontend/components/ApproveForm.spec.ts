import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import ApproveForm from './ApproveForm.vue'
import { useApiMock } from '../test/mocks/nuxt'
import { baseTask } from '../test/fixtures/task'

// 창 없이 폼만 마운트 — 처리함이 같은 폼을 펼쳐 쓴다.
const task = {
  ...baseTask,
  id: 7,
  status: 'AWAITING_APPROVAL',
  statusLabel: '승인대기',
  title: '활동 스트림 스모크',
  requesterId: 'admin',
}

function routeApi() {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/mcp-catalog') return Promise.resolve([])
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    if (url === '/api/tasks/7/interviews')
      return Promise.resolve([
        {
          id: 22,
          status: '취소됨',
          statusName: 'CANCELLED',
          model: 'claude-sonnet-5',
          effort: 'high',
          createdAt: '2026-08-21T10:00:00+09:00',
        },
      ])
    if (url.startsWith('/api/db-connections?repoCatalogId='))
      return Promise.resolve({ enabled: false, items: [] })
    return Promise.resolve({})
  })
}

function mountForm(props: Record<string, unknown> = {}) {
  return mount(ApproveForm, {
    props: { task: { ...task }, ...props },
    attachTo: document.body,
  })
}

const interviewCalls = () =>
  useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks/7/interviews')
const approveCall = () =>
  useApiMock.mock.calls.find((c) => c[0] === '/api/tasks/7/approve')

beforeEach(() => {
  useApiMock.mockReset()
  routeApi()
})
afterEach(() => {
  document.body.innerHTML = ''
})

describe('ApproveForm', () => {
  it('창_없이_마운트해도_지난_인터뷰를_불러와_승인_바디를_보낸다', async () => {
    const w = mountForm()
    await flushPromises()
    expect(interviewCalls()).toHaveLength(1)
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1]).toMatchObject({
      method: 'POST',
      body: { model: 'claude-sonnet-5', effort: 'high', mcpCatalogIds: [] },
    })
    expect(w.emitted('approved')).toBeTruthy()
    expect(w.emitted('update:approving')?.map((e) => e[0])).toEqual([true, false])
    w.unmount()
  })

  it('같은_작업의_task_prop이_다시_들어와도_고른_값을_되돌리지_않는다', async () => {
    const w = mountForm()
    await flushPromises()
    const fields = w.findComponent({ name: 'ModelEffortFields' })
    ;(fields.vm as any).pickModel('claude-haiku-4-5')
    await flushPromises()
    await w.setProps({ task: { ...task } })
    await flushPromises()
    await (w.vm as any).approve()
    await flushPromises()
    expect((approveCall()?.[1] as any).body.model).toBe('claude-haiku-4-5')
    expect(interviewCalls()).toHaveLength(1)
    w.unmount()
  })

  it('inbox_문맥이면_처리함_안내와_취소_버튼_없음', async () => {
    const w = mountForm({ context: 'inbox' })
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('첫 질문이 오면 처리함의 답변 대기에 올라옵니다')
    expect(text).not.toContain('대화형 분석')
    expect(
      Array.from(document.body.querySelectorAll('button')).some((b) =>
        b.textContent?.includes('취소'),
      ),
    ).toBe(false)
    expect(text).toContain('승인하고 인터뷰 시작')
    w.unmount()
  })

  it('dialog_문맥이면_취소가_cancel을_올린다', async () => {
    const w = mountForm()
    await flushPromises()
    const cancel = Array.from(document.body.querySelectorAll('button')).find(
      (b) => b.textContent?.includes('취소'),
    )
    cancel!.click()
    expect(w.emitted('cancel')).toBeTruthy()
    w.unmount()
  })
})
