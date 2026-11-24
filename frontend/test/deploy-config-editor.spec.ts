import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import DeployConfigEditor from '../components/admin/DeployConfigEditor.vue'
import type { DeployConfig } from '../composables/deployConfig'

function config(entries: Record<string, string>): DeployConfig {
  return {
    databases: [{ slot: 'main', label: '주 DB', dbTypes: ['MARIADB'], target: { kind: 'ENV', path: null, entries } }],
    variables: [],
  }
}

// v-model처럼 방출한 값을 다시 prop으로 돌려주는 부모
function mountEditor(initial: DeployConfig) {
  const model = ref<DeployConfig>(initial)
  const Host = defineComponent({
    setup: () => () => h(DeployConfigEditor, { modelValue: model.value, 'onUpdate:modelValue': (v: DeployConfig) => (model.value = v) }),
  })
  const w = mount(Host, { attachTo: document.body })
  return { w, model }
}

const keys = (w: ReturnType<typeof mount>) =>
  w.findAll('[data-test="deploy-entry-key"]').map((e) => (e.element as HTMLInputElement).value)

describe('DeployConfigEditor 항목 행', () => {
  it('이미 있는 키의 앞부분을 쳐도 행이 합쳐지거나 덮이지 않는다', async () => {
    const { w, model } = mountEditor(config({ X_URL: 'a', X_URL_RO: 'b' }))
    await flushPromises()
    // 두 번째 행 키를 X_URL_RO → X_URL 로 바꾸는 중간 상태(첫 행과 겹침)
    await w.findAll('[data-test="deploy-entry-key"]')[1].setValue('X_URL')
    await flushPromises()
    expect(keys(w)).toEqual(['X_URL', 'X_URL'])
    expect(w.findAll('[data-test="deploy-entry-value"]').map((e) => (e.element as HTMLInputElement).value)).toEqual(['a', 'b'])
    // 이어서 마저 고치면 두 행이 모두 살아 있다
    await w.findAll('[data-test="deploy-entry-key"]')[1].setValue('X_URL_2')
    await flushPromises()
    expect(model.value.databases[0].target.entries).toEqual({ X_URL: 'a', X_URL_2: 'b' })
    w.unmount()
  })

  it('빈 키 행이 있어도 항목을 더 추가할 수 있다', async () => {
    const { w, model } = mountEditor(config({ A: '1' }))
    await flushPromises()
    await w.find('[data-test="deploy-add-entry"]').trigger('click')
    await w.find('[data-test="deploy-add-entry"]').trigger('click')
    await flushPromises()
    expect(keys(w)).toEqual(['A', '', ''])
    await w.findAll('[data-test="deploy-entry-key"]')[2].setValue('B')
    await flushPromises()
    expect(keys(w)).toEqual(['A', '', 'B'])
    expect(model.value.databases[0].target.entries).toEqual({ A: '1', B: '' })
    w.unmount()
  })
})
