import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ApproveDialog from './ApproveDialog.vue'
import { useApiMock } from '../test/mocks/nuxt'

describe('ApproveDialog', () => {
  it('승인 시 모델·effort·MCP 선택을 함께 보낸다', async () => {
    useApiMock.mockResolvedValue([]) // McpPicker의 GET /api/mcp-catalog
    const w = mount(ApproveDialog, {
      props: { modelValue: true, taskId: 7 },
      attachTo: document.body,
    })
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    await (w.vm as any).approve()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/7/approve', {
      method: 'POST',
      body: { model: 'claude-opus-4-8', effort: 'high', mcpCatalogIds: [] },
    })
    expect(w.emitted('approved')).toBeTruthy()

    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('MCP를 고르면 선택 id가 payload에 실린다', async () => {
    useApiMock.mockResolvedValue([
      {
        id: 3,
        name: 'ctx7',
        displayName: 'Context7',
        url: 'https://ctx7',
        transport: 'http',
        description: null,
        lastCheckStatus: 'HEALTHY',
      },
    ])
    const w = mount(ApproveDialog, {
      props: { modelValue: true, taskId: 7 },
      attachTo: document.body,
    })
    await flushPromises()

    const picker = w.findComponent({ name: 'McpPicker' })
    ;(picker.vm as any).toggle(3)
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    await (w.vm as any).approve()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/7/approve', {
      method: 'POST',
      body: { model: 'claude-opus-4-8', effort: 'high', mcpCatalogIds: [3] },
    })

    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })
})
