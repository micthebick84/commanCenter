import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QFile } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

const MB = 1024 * 1024

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

// Fix round 1 hygiene fix: cleanup lived in every test body, so a failing assertion
// mid-test left a mounted page + open dialog behind for the next test. Centralize it.
let currentWrapper: ReturnType<typeof mount> | null = null

afterEach(() => {
  currentWrapper?.unmount()
  currentWrapper = null
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

async function mountPage() {
  useApiMock.mockResolvedValue([])
  const w = mount(PageWrapper, { attachTo: document.body })
  currentWrapper = w
  await flushPromises()
  return w
}

async function openDialog() {
  const w = await mountPage()
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

// Builds a File with a stubbed `size` so boundary tests (20MB/50MB) don't need to
// allocate real multi-megabyte buffers. `size` is a getter on Blob.prototype but an
// own data property shadowing it is allowed even though the prototype accessor has
// no setter.
function sizedFile(name: string, size: number): File {
  const f = new File([], name)
  Object.defineProperty(f, 'size', { value: size, configurable: true })
  return f
}

describe('tasks form — attachments', () => {
  it('files이 있으면 FormData(meta+files)로 전송한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    const files = [
      new File(['내용1'], '요구사항.txt', { type: 'text/plain' }),
      new File(['내용2'], '스크린샷.png', { type: 'image/png' }),
      new File(['내용3'], '메모.md', { type: 'text/markdown' }),
    ]
    w.findComponent(QFile).vm.$emit('update:modelValue', files)
    await flushPromises()

    useApiMock.mockClear()
    useApiMock.mockResolvedValue({})
    clickSubmitButton()
    await flushPromises()

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')
    expect(call).toBeTruthy()
    const body = call![1].body as FormData
    expect(body).toBeInstanceOf(FormData)
    // Fix round 1 (finding 1): assert all 3 files fan out, by name — a loop bug that
    // appends only the first file would still pass a bare toHaveLength(1) check.
    const sentFiles = body.getAll('files') as File[]
    expect(sentFiles).toHaveLength(3)
    expect(sentFiles.map((f) => f.name)).toEqual(['요구사항.txt', '스크린샷.png', '메모.md'])
    // Fix round 1 (finding 3): assert the full DTO shape (all 4 fields) and the Blob's
    // MIME type — a meta object missing fields, or sent as text/plain, would 400/415
    // against the real Task 4 multipart controller even though a shallow toMatchObject
    // check on {title} alone would stay green.
    const metaBlob = body.get('meta') as Blob
    expect(metaBlob.type).toBe('application/json')
    expect(JSON.parse(await metaBlob.text())).toEqual({
      repoCatalogId: 1,
      githubBranch: 'main',
      title: '제목',
      description: '설명',
    })
    // Content-Type을 강제로 명시하지 않는다 ($fetch가 boundary를 스스로 설정)
    expect(call![1].headers?.['Content-Type']).toBeUndefined()
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
  })

  it('한도 초과(11개)면 전송하지 않고 경고한다', async () => {
    const w = await openDialog()
    fillDraft(w)
    const files = Array.from({ length: 11 }, (_, i) => new File(['x'], `f${i}.txt`))
    w.findComponent(QFile).vm.$emit('update:modelValue', files)
    await flushPromises()

    // Fix round 1 (finding 2): spy on the real $q.notify the component captured at
    // setup time (reachable via vm.$q, same as vm.draft/vm.validateFiles) — a deleted
    // $q.notify call would leave the submit button silently doing nothing, which the
    // "doesn't send" assertion alone can't catch.
    const vm = w.findComponent(TasksIndex).vm as unknown as { $q: { notify: (opts: unknown) => void } }
    const notifySpy = vi.spyOn(vm.$q, 'notify')

    useApiMock.mockClear()
    clickSubmitButton()
    await flushPromises()

    expect(useApiMock.mock.calls.find((c) => c[0] === '/api/tasks')).toBeUndefined()
    expect(notifySpy).toHaveBeenCalledWith({
      type: 'warning',
      message: '첨부는 최대 10개까지 가능합니다',
    })
  })
})

// Fix round 1 (finding 4): validateFiles has 4 rules; only the >10-files rule had any
// coverage (indirectly, via the dialog test above). Call the function directly — the
// reviewer confirmed vm.validateFiles is reachable from the mounted page the same way
// vm.draft already is — to cover the other 3 rules plus operator-boundary cases.
describe('validateFiles — 서버 한도와 동일한 검증(직접 호출)', () => {
  async function getValidateFiles() {
    const w = await mountPage()
    return (
      w.findComponent(TasksIndex).vm as unknown as {
        validateFiles: (files: File[]) => string | null
      }
    ).validateFiles
  }

  it('파일당 20MB 초과 시 거부한다', async () => {
    const validateFiles = await getValidateFiles()
    const big = sizedFile('big.bin', 20 * MB + 1)
    expect(validateFiles([big])).toBe('파일당 20MB 이하만 첨부할 수 있습니다: big.bin')
  })

  it('0바이트 파일은 거부한다', async () => {
    const validateFiles = await getValidateFiles()
    const empty = sizedFile('empty.txt', 0)
    expect(validateFiles([empty])).toBe('빈 파일(0바이트)은 첨부할 수 없습니다')
  })

  it('개별 파일은 한도 이내라도 합계 50MB 초과 시 거부한다', async () => {
    const validateFiles = await getValidateFiles()
    const files = [
      sizedFile('a.bin', 19 * MB),
      sizedFile('b.bin', 19 * MB),
      sizedFile('c.bin', 19 * MB),
    ]
    expect(validateFiles(files)).toBe('첨부 합계는 50MB 이하여야 합니다')
  })

  it('경계값 — 정확히 10개 · 파일당 정확히 20MB · 합계 정확히 50MB는 통과한다', async () => {
    const validateFiles = await getValidateFiles()
    // 정확히 10개, 각 5MB = 합계 정확히 50MB — 세 한도의 등호 경계를 동시에 검증
    const tenFiles = Array.from({ length: 10 }, (_, i) => sizedFile(`f${i}.bin`, 5 * MB))
    expect(validateFiles(tenFiles)).toBeNull()
    // 파일 1개, 정확히 20MB
    expect(validateFiles([sizedFile('exact20.bin', 20 * MB)])).toBeNull()
  })
})
