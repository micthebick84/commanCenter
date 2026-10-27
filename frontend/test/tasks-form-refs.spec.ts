import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QFile, QInput, QSelect } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import RefRepoChips from '../components/RefRepoChips.vue'
import RefRepoAddForm from '../components/RefRepoAddForm.vue'
import { useApiMock } from './mocks/nuxt'

// 작업 등록 창의 참조 repo 줄 (스펙 2026-10-24 §7.2) — tasks-form-repo-select.spec.ts와 같은 PageWrapper·마운트 방식.
// 등록 창(q-dialog)은 document.body로 텔레포트되므로 DOM은 document에서, 컴포넌트는 findComponent로 찾는다.

// QPage requires QLayout > QPageContainer ancestry; wrap for testing.
const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

// 카탈로그: 1=대상으로 쓰는 레포 · 2=옛앱(GitLab, 기본 브랜치 release/6.5) · 3=공통 UI
const CATALOG = [
  { id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'acme/netis', defaultBranch: 'main' },
  { id: 2, alias: '옛앱', host: 'gitlab', ownerRepo: null, defaultBranch: 'release/6.5' },
  { id: 3, alias: '공통 UI', host: 'gitlab', ownerRepo: null, defaultBranch: 'main' },
]
const REF_OLD = { catalogId: 2, alias: '옛앱', branch: 'release/6.5' }
const REF_UI = { catalogId: 3, alias: '공통 UI', branch: 'main' }

interface RefDraft {
  catalogId: number
  alias: string
  branch: string
}
interface PageVm {
  draft: {
    repoCatalogId: number | null
    githubBranch: string
    title: string
    description: string
    refRepos: RefDraft[]
  }
  refAddOpen: boolean
  submit: () => Promise<void>
  onRepoSelected: (catalogId: number | null) => void
}

type Wrapper = ReturnType<typeof mount>

let current: Wrapper | null = null
afterEach(() => {
  current?.unmount()
  current = null
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

function stubApi() {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/repo-catalog') return Promise.resolve(CATALOG)
    if (url === '/api/repos/branches') {
      return Promise.resolve({
        repo: 'acme/netis',
        defaultBranch: 'main',
        branches: [{ name: 'main', sha: 'a' }, { name: 'release/6.5', sha: 'b' }],
        fetchedAt: '2026-10-24T00:00:00Z',
      })
    }
    return Promise.resolve([])
  })
}

async function mountPage() {
  stubApi()
  const w = mount(PageWrapper, { attachTo: document.body })
  current = w
  await flushPromises()
  return w
}

const vmOf = (w: Wrapper) => w.findComponent(TasksIndex).vm as unknown as PageVm

/** 목록 화면의 '작업 등록' 버튼으로 창을 연다(창 안의 같은 이름 버튼은 body로 텔레포트돼 w.findAll에 안 걸린다). */
async function openDialog(w: Wrapper) {
  const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
  await addBtn!.trigger('click')
  await flushPromises()
}

function fillDraft(vm: PageVm) {
  vm.draft.repoCatalogId = 1
  vm.draft.githubBranch = 'main'
  vm.draft.title = '옛앱 결재선 이식'
  vm.draft.description = '옛앱의 결재선 계산을 새 앱으로 옮긴다'
}

/** POST /api/tasks 호출의 body */
function postedBody() {
  const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks' && c[1]?.method === 'POST')
  expect(call).toBeTruthy()
  return call![1].body
}

const selectOf = (w: Wrapper, id: string) =>
  w.findAllComponents(QSelect).find((c) => (c.vm.$attrs as Record<string, unknown>)['data-test'] === id)!
const optionValues = (sel: { props: (k: string) => unknown }) =>
  (sel.props('options') as Array<{ value: unknown }>).map((o) => o.value)
const refsRow = () => document.querySelector('[data-test="task-form-refs"]')
const refChips = () => Array.from(document.querySelectorAll('[data-test="task-form-refs"] [data-test="ref-chip"]'))

describe('작업 등록 창 — 참조 repo (스펙 2026-10-24 §7.2)', () => {
  it('참조를 고르면 등록 바디(JSON)에 refRepos가 실린다', async () => {
    const w = await mountPage()
    const vm = vmOf(w)
    fillDraft(vm)
    vm.draft.refRepos.push({ ...REF_OLD })

    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()

    // 화면 값(별칭)은 빠지고 (레포, 브랜치)만 나간다
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks', {
      method: 'POST',
      body: {
        repoCatalogId: 1,
        githubBranch: 'main',
        title: '옛앱 결재선 이식',
        description: '옛앱의 결재선 계산을 새 앱으로 옮긴다',
        refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }],
      },
    })
  })

  it('파일을 함께 보내도 meta JSON에 refRepos가 실린다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    fillDraft(vm)
    vm.draft.refRepos.push({ ...REF_OLD }, { ...REF_UI })
    w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['x'], '요구사항.txt', { type: 'text/plain' })])
    await flushPromises()

    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()

    const body = postedBody() as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(JSON.parse(await (body.get('meta') as Blob).text())).toEqual({
      repoCatalogId: 1,
      githubBranch: 'main',
      title: '옛앱 결재선 이식',
      description: '옛앱의 결재선 계산을 새 앱으로 옮긴다',
      refRepos: [
        { repoCatalogId: 2, branch: 'release/6.5' },
        { repoCatalogId: 3, branch: 'main' },
      ],
    })
  })

  it('골랐다가 모두 빼면 바디에 refRepos 키가 없다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    fillDraft(vm)
    vm.draft.refRepos.push({ ...REF_OLD })
    await flushPromises()

    // 칩의 ✕로 뺀다
    await w.findComponent(RefRepoChips).find('.q-chip__icon--remove').trigger('click')
    expect(vm.draft.refRepos).toEqual([])

    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()
    expect(postedBody()).not.toHaveProperty('refRepos')
  })

  it('대상을 참조 중 하나로 바꾸면 그 참조만 빠진다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    vm.draft.refRepos.push({ ...REF_OLD }, { ...REF_UI })
    await flushPromises()
    expect(refChips()).toHaveLength(2)

    vm.draft.repoCatalogId = 2
    vm.onRepoSelected(2)
    await flushPromises()
    expect(vm.draft.refRepos.map((r) => r.catalogId)).toEqual([3])
    // 칩 줄도 따라간다
    expect(refChips()).toHaveLength(1)
    expect(refChips()[0]!.textContent).toContain('공통 UI')

    // 참조가 아닌 레포로 바꾸거나 대상을 비우면 남은 참조는 그대로다
    vm.draft.repoCatalogId = 1
    vm.onRepoSelected(1)
    await flushPromises()
    vm.draft.repoCatalogId = null
    vm.onRepoSelected(null)
    await flushPromises()
    expect(vm.draft.refRepos.map((r) => r.catalogId)).toEqual([3])
  })

  it('칩 줄은 브랜치 선택 바로 아래(제목 위)에 있고 대기 칩은 "인터뷰 때 받음"', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)

    const branchField = document.querySelector('[data-test="branch-select"]')!.closest('.q-field')!
    const titleField = w.findAllComponents(QInput).find((c) => c.props('label') === '작업 제목')!.element
    const row = refsRow()!
    expect(branchField.nextElementSibling).toBe(row)
    expect(row.nextElementSibling).toBe(titleField)

    // 편집 모드: 비어 있어도 "＋ 참조 추가"가 보인다
    expect(row.textContent).toContain('참조 repo · 읽기 전용 · 최대 3개')
    expect(row.textContent).toContain('참조 repo 없음')
    expect(row.querySelector('[data-test="ref-add"]')!.textContent).toContain('＋ 참조 추가')

    vm.draft.refRepos.push({ ...REF_OLD })
    await flushPromises()
    const chip = refChips()[0]!
    expect(chip.getAttribute('data-state')).toBe('pending')
    expect(chip.textContent).toContain('📚 옛앱 · release/6.5')
    expect(chip.textContent).toContain('인터뷰 때 받음')
    expect(chip.textContent).not.toContain('다음 답변')
  })

  it('＋ 참조 추가는 인라인 폼을 펼치고(후보에서 대상·고른 것 제외), 추가하면 칩이 생기고 접힌다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    vm.draft.repoCatalogId = 1
    vm.draft.refRepos.push({ ...REF_UI })
    await flushPromises()
    expect(document.querySelector('[data-test="ref-add-form"]')).toBeNull() // 눌러야 펼쳐진다

    await w.findComponent(RefRepoChips).find('[data-test="ref-add"]').trigger('click')
    const form = w.findComponent(RefRepoAddForm)
    expect(form.exists()).toBe(true)
    // 칩 줄 아래, 같은 참조 줄 안의 윤곽선 패널에 펼친다
    expect(form.element.parentElement!.classList.contains('ref-add-panel')).toBe(true)
    expect(refsRow()!.contains(form.element)).toBe(true)
    // 후보: 대상(1)과 이미 고른 참조(3)는 빠진다 — 레포 선택(카탈로그)을 그대로 넘긴다
    expect(form.props('excludeIds')).toEqual([1, 3])
    expect(form.props('catalog')).toEqual(CATALOG)
    expect(optionValues(selectOf(w, 'ref-add-repo'))).toEqual([2])

    selectOf(w, 'ref-add-repo').vm.$emit('update:modelValue', 2)
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/repos/branches', { params: { catalogId: 2 } })
    await form.find('[data-test="ref-add-submit"]').trigger('click')
    await flushPromises()

    expect(vm.draft.refRepos).toEqual([REF_UI, { catalogId: 2, alias: '옛앱', branch: 'release/6.5' }])
    expect(vm.refAddOpen).toBe(false)
    expect(document.querySelector('[data-test="ref-add-form"]')).toBeNull() // 추가하면 접힌다
    expect(refChips()).toHaveLength(2)
    expect(refChips()[1]!.textContent).toContain('📚 옛앱 · release/6.5')
  })

  it('창을 닫았다 다시 열면 참조가 비워진다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    vm.draft.refRepos.push({ ...REF_OLD })
    await flushPromises()
    await w.findComponent(RefRepoChips).find('[data-test="ref-add"]').trigger('click')
    expect(vm.refAddOpen).toBe(true)

    const cancel = Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.trim() === '취소')
    cancel!.click()
    await flushPromises()

    await openDialog(w)
    expect(vm.draft.refRepos).toEqual([])
    expect(vm.refAddOpen).toBe(false)
    expect(refChips()).toHaveLength(0)
    expect(document.querySelector('[data-test="ref-add-form"]')).toBeNull()
  })
})
