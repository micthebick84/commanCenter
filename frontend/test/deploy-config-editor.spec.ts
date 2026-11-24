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

// v-model처럼 방출한 값을 다시 prop으로 돌려주는 부모(v-model:problems도 같이)
function mountEditor(initial: DeployConfig) {
  const model = ref<DeployConfig>(initial)
  const problems = ref<string[]>([])
  const Host = defineComponent({
    setup: () => () =>
      h(DeployConfigEditor, {
        modelValue: model.value,
        'onUpdate:modelValue': (v: DeployConfig) => (model.value = v),
        problems: problems.value,
        'onUpdate:problems': (v: string[]) => (problems.value = v),
      }),
  })
  const w = mount(Host, { attachTo: document.body })
  return { w, model, problems }
}

function twoSlots(): DeployConfig {
  return {
    databases: [
      { slot: 'main', label: '주 DB', dbTypes: ['MARIADB'], target: { kind: 'ENV', path: null, entries: { A: '1' } } },
      { slot: 'db2', label: 'DB 2', dbTypes: ['MARIADB'], target: { kind: 'ENV', path: null, entries: { B: '2' } } },
    ],
    variables: [],
  }
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

  it('같은 키 두 행은 두 칸 모두 오류로 표시하고 문제 목록으로 알린다(저장은 화면이 막는다)', async () => {
    const { w, problems } = mountEditor(config({ X_URL: 'a', X_URL_RO: 'b' }))
    await flushPromises()
    expect(problems.value).toEqual([])
    await w.findAll('[data-test="deploy-entry-key"]')[1].setValue('X_URL')
    await flushPromises()
    expect(problems.value).toEqual(["DB 자리 'main'에 같은 항목 키가 두 번 있습니다: X_URL"])
    const rows = w.findAll('[data-test="deploy-entry-row"]')
    expect(rows.map((r) => r.find('.q-field--error').exists())).toEqual([true, true])
    expect(w.text()).toContain('같은 항목 키가 이 자리에 이미 있습니다')
    // 고치면 문제가 사라진다
    await w.findAll('[data-test="deploy-entry-key"]')[1].setValue('X_URL_2')
    await flushPromises()
    expect(problems.value).toEqual([])
    expect(w.find('.q-field--error').exists()).toBe(false)
    w.unmount()
  })

  it('앞 자리를 지워도 뒤 자리의 빈 키 행이 남는다', async () => {
    const { w, model } = mountEditor(twoSlots())
    await flushPromises()
    await w.findAll('[data-test="deploy-add-entry"]')[1].trigger('click') // 둘째 자리에 빈 행
    await flushPromises()
    expect(keys(w)).toEqual(['A', 'B', ''])
    await w.findAll('[data-test="deploy-remove-db"]')[0].trigger('click')
    await flushPromises()
    expect(model.value.databases.map((d) => d.slot)).toEqual(['db2'])
    expect(keys(w)).toEqual(['B', ''])
    w.unmount()
  })
})
