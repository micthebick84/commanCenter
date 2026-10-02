import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import DbConnectionPicker from '../components/DbConnectionPicker.vue'
import type { DbConnectionView } from '../composables/dbConnections'

const item = (id: number, scope: 'REPO' | 'USER', enabled = true): DbConnectionView => ({
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

  it('목록에 없는 선택 id는 "사용할 수 없는 연결"로 보이고 해제할 수 있지만 다시 고를 수는 없다', async () => {
    const w = mount(DbConnectionPicker, {
      props: { modelValue: [1, 99], items: [item(1, 'REPO')], repoCatalogId: 1 },
    })
    await flushPromises()
    const sec = w.find('[data-test="db-section-unavailable"]')
    expect(sec.exists()).toBe(true)
    expect(sec.text()).toContain('사용할 수 없는 연결')
    expect(sec.text()).toContain('연결 #99 (삭제·비활성)')
    expect(sec.find('[data-test="db-unavailable-99"]').exists()).toBe(true)
    const vm = w.vm as any
    vm.toggle(99)
    expect(w.emitted('update:modelValue')!.at(-1)![0]).toEqual([1])
    await w.setProps({ modelValue: [1] })
    expect(w.find('[data-test="db-section-unavailable"]').exists()).toBe(false)
    const n = w.emitted('update:modelValue')!.length
    vm.toggle(99) // 다시 고르기는 불가
    expect(w.emitted('update:modelValue')!.length).toBe(n)
    w.unmount()
  })

  it('knownChips에 이름이 있으면 그 이름으로 보여 준다', async () => {
    const w = mount(DbConnectionPicker, {
      props: { modelValue: [7], items: [], repoCatalogId: 1, knownChips: [{ id: 7, name: '옛 운영 DB', dbType: 'MYSQL' }] },
    })
    await flushPromises()
    expect(w.find('[data-test="db-section-unavailable"]').text()).toContain('옛 운영 DB (삭제·비활성)')
    w.unmount()
  })

  it('선택돼 있는 비활성 항목(관리자 목록)은 해제할 수 있다', async () => {
    const w = mount(DbConnectionPicker, { props: { modelValue: [5], items: [item(5, 'REPO', false)], repoCatalogId: 1 } })
    ;(w.vm as any).toggle(5)
    expect(w.emitted('update:modelValue')!.at(-1)![0]).toEqual([])
    w.unmount()
  })

  it('목록에 없는 id만 있어도 상한 3 검사는 선택 개수 기준으로 동작한다', async () => {
    const w = mount(DbConnectionPicker, {
      props: { modelValue: [97, 98, 99], items: [item(1, 'REPO')], repoCatalogId: 1 },
    })
    ;(w.vm as any).toggle(1) // 이미 3개 → 거부
    expect(w.emitted('update:modelValue')).toBeFalsy()
    ;(w.vm as any).toggle(98) // 해제는 가능
    expect(w.emitted('update:modelValue')!.at(-1)![0]).toEqual([97, 99])
    w.unmount()
  })
})
