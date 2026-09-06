import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import McpPicker from './McpPicker.vue'
import { useApiMock } from '../test/mocks/nuxt'

const entry = (
  id: number,
  displayName: string,
  lastCheckStatus: string | null = 'HEALTHY',
) => ({
  id,
  name: displayName.toLowerCase(),
  displayName,
  url: `https://${displayName.toLowerCase()}.example`,
  transport: 'http',
  description: null,
  lastCheckStatus,
})

describe('McpPicker flat 모드 (승인 다이얼로그 — 접힘 없이 칩 바로 노출)', () => {
  it('카탈로그가 있으면 확장 패널 없이 칩을 바로 그린다', async () => {
    useApiMock.mockResolvedValue([
      entry(3, 'Context7'),
      entry(4, 'Obsidian', 'DOWN'),
    ])
    const w = mount(McpPicker, { props: { flat: true, modelValue: [] } })
    await flushPromises()
    expect(w.find('.q-expansion-item').exists()).toBe(false)
    expect(w.find('[data-test="mcp-flat"]').exists()).toBe(true)
    expect(w.findAll('.q-chip')).toHaveLength(2)
    expect(w.text()).toContain('Context7')
    w.unmount()
  })

  it('칩을 누르면 선택 id가 v-model로 나간다', async () => {
    useApiMock.mockResolvedValue([entry(3, 'Context7')])
    const w = mount(McpPicker, { props: { flat: true, modelValue: [] } })
    await flushPromises()
    await w.find('.q-chip').trigger('click')
    expect(w.emitted('update:modelValue')?.[0]).toEqual([[3]])
    w.unmount()
  })

  it('카탈로그가 비면 안내 한 줄과 MCP 카탈로그 링크만 보인다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(McpPicker, { props: { flat: true, modelValue: [] } })
    await flushPromises()
    expect(w.findAll('.q-chip')).toHaveLength(0)
    expect(w.find('[data-test="mcp-empty"]').text()).toContain(
      '등록된 MCP 도구가 없습니다',
    )
    expect(w.find('[data-test="mcp-catalog-link"]').text()).toContain(
      'MCP 카탈로그',
    )
    w.unmount()
  })

  it('DOWN 상태 MCP를 고르면 경고 한 줄이 붙는다', async () => {
    useApiMock.mockResolvedValue([entry(4, 'Obsidian', 'DOWN')])
    const w = mount(McpPicker, { props: { flat: true, modelValue: [] } })
    await flushPromises()
    expect(w.find('[data-test="mcp-down-warning"]').exists()).toBe(false)
    await w.setProps({ modelValue: [4] })
    expect(w.find('[data-test="mcp-down-warning"]').text()).toContain('DOWN')
    w.unmount()
  })

  it('기본(접힘) 모드는 그대로 확장 패널이다', async () => {
    useApiMock.mockResolvedValue([])
    const w = mount(McpPicker, { props: { modelValue: [] } })
    await flushPromises()
    expect(w.find('.q-expansion-item').exists()).toBe(true)
    expect(w.find('[data-test="mcp-flat"]').exists()).toBe(false)
    w.unmount()
  })
})
