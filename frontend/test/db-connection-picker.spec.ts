import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import DbConnectionPicker from '../components/DbConnectionPicker.vue'

const item = (id: number, scope: 'REPO' | 'USER', enabled = true) => ({
  id, scope, repoCatalogId: 1, name: `conn${id}`, dbType: 'POSTGRESQL', host: 'h', port: 5432,
  databaseName: 'app', username: 'u', enabled, mine: scope === 'USER',
})

describe('DbConnectionPicker', () => {
  it('공용/내 접속정보로 나눠 보이고 토글하며 상한 3을 넘기지 않는다', async () => {
    const w = mount(DbConnectionPicker, {
      props: { modelValue: [1], items: [item(1, 'REPO'), item(2, 'REPO'), item(3, 'USER'), item(4, 'USER')], repoCatalogId: 1 },
    })
    await flushPromises()
    expect(w.find('[data-test="db-section-repo"]').text()).toContain('conn1')
    expect(w.find('[data-test="db-section-user"]').text()).toContain('conn3')
    const vm = w.vm as any
    vm.toggle(2); vm.toggle(3)
    const emitted = w.emitted('update:modelValue')!
    expect(emitted.at(-1)![0]).toEqual([1, 2, 3])
    await w.setProps({ modelValue: [1, 2, 3] })
    vm.toggle(4)
    expect(w.emitted('update:modelValue')!.length).toBe(emitted.length) // 4번째는 거부
    vm.toggle(1)
    expect(w.emitted('update:modelValue')!.at(-1)![0]).toEqual([2, 3])
    w.unmount()
  })

  it('비활성 항목은 고를 수 없다(관리자 목록)', async () => {
    const w = mount(DbConnectionPicker, { props: { modelValue: [], items: [item(5, 'REPO', false)], repoCatalogId: 1 } })
    ;(w.vm as any).toggle(5)
    expect(w.emitted('update:modelValue')).toBeFalsy()
    w.unmount()
  })
})
