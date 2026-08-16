import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QFile } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

async function openDialog() {
  useApiMock.mockResolvedValue([])
  const w = mount(PageWrapper, { attachTo: document.body })
  await flushPromises()
  const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
  await addBtn!.trigger('click')
  await flushPromises()
  return w
}

function fillDraft(w: ReturnType<typeof mount>) {
  const page = w.findComponent(TasksIndex)
  const vm = page.vm as unknown as {
    draft: { repoCatalogId: number | null; githubBranch: string; title: string; description: string }
  }
  vm.draft.repoCatalogId = 1
  vm.draft.githubBranch = 'main'
  vm.draft.title = '제목'
  vm.draft.description = '설명'
}

// QDialog teleports its content to document.body (Vue Teleport), so the submit button
// lives outside the mounted wrapper's own DOM subtree — w.findAll('button') can't see it
// (same reason tasks-form-repo-select.spec.ts queries via document.querySelector instead
// of w.find). Query the real DOM directly and take the last match (dialog's submit btn).
function clickSubmitButton() {
  const btns = Array.from(document.querySelectorAll('button')).filter((b) =>
    b.textContent?.includes('작업 등록'),
  )
  ;(btns.at(-1) as HTMLButtonElement).click()
}

describe('tasks form — attachments', () => {
  it('files이 있으면 FormData(meta+files)로 전송한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    const file = new File(['내용'], '요구사항.txt', { type: 'text/plain' })
    w.findComponent(QFile).vm.$emit('update:modelValue', [file])
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    clickSubmitButton()
    await flushPromises()

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')
    expect(call).toBeTruthy()
    const body = call![1].body as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.getAll('files')).toHaveLength(1)
    const metaBlob = body.get('meta') as Blob
    expect(JSON.parse(await metaBlob.text())).toMatchObject({ title: '제목' })
    // Content-Type을 강제로 명시하지 않는다 ($fetch가 boundary를 스스로 설정)
    expect(call![1].headers?.['Content-Type']).toBeUndefined()
    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('files이 없으면 기존 JSON body로 전송한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    // Flush so the disabled attribute (bound to canSubmit) re-renders before the raw
    // DOM click below — otherwise the still-disabled button silently swallows it.
    await flushPromises()
    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    clickSubmitButton()
    await flushPromises()

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')
    expect(call![1].body).toMatchObject({ title: '제목', repoCatalogId: 1 })
    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })

  it('한도 초과(11개)면 전송하지 않고 경고한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    const files = Array.from({ length: 11 }, (_, i) => new File(['x'], `f${i}.txt`))
    w.findComponent(QFile).vm.$emit('update:modelValue', files)
    await flushPromises()

    useApiMock.mockClear()
    clickSubmitButton()
    await flushPromises()

    expect(useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')).toBeUndefined()
    w.unmount()
    document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
  })
})
