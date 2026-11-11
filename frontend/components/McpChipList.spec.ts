import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import McpChipList from './McpChipList.vue'

const entry = (id: number, lastCheckStatus: string | null) => ({
  id, name: `mcp${id}`, displayName: `MCP ${id}`, url: 'http://x', transport: 'http', description: null, lastCheckStatus,
})

describe('McpChipList — 상태를 점 색만으로 알리지 않는다 (2026-11-11 검토)', () => {
  it('느림·연결 안 됨·확인 전은 글자로도 보이고, 정상은 점만 둔다', () => {
    const w = mount(McpChipList, {
      props: { catalog: [entry(1, 'HEALTHY'), entry(2, 'DEGRADED'), entry(3, 'DOWN'), entry(4, null)], selected: [] },
    })
    const chips = w.findAll('.q-chip')
    expect(chips[0]!.find('[data-test="mcp-health-text"]').exists()).toBe(false)
    expect(chips[1]!.find('[data-test="mcp-health-text"]').text()).toBe('(느림)')
    expect(chips[2]!.find('[data-test="mcp-health-text"]').text()).toBe('(연결 안 됨)')
    expect(chips[3]!.find('[data-test="mcp-health-text"]').text()).toBe('(확인 전)')
    w.unmount()
  })
})
