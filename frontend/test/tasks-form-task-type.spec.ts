import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QSelect } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { useApiMock } from './mocks/nuxt'

// 작업 등록 창의 타입 select (스펙 2026-11-03 §4.2) — 화면에서는 필수, 미리 골라 두는 값은 없다.
// 하네스는 tasks-form-from-task.spec.ts와 같다(쿼리 있는 useRoute + navigateTo 모의).

let routeQuery: Record<string, string> = {}
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  useRoute: () => ({ path: '/tasks', params: {}, query: routeQuery }),
  navigateTo: navigateToMock,
})

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }),
      })
  },
})

const CATALOG = [{ id: 1, alias: 'Netis7.0', host: 'github', ownerRepo: 'acme/netis', defaultBranch: 'main' }]
const BRANCHES = {
  repo: 'acme/netis',
  defaultBranch: 'main',
  branches: [{ name: 'main', sha: 'a' }],
  fetchedAt: '2026-11-04T00:00:00Z',
}
// 원래 작업·질문에 타입이 있어도 등록 창은 그것을 물려받지 않는다
const ORIGINAL_TASK = { id: 42, title: '대상장비 탭 개선', status: 'DEPLOYED', githubBranch: 'main', repoCatalogId: 1, taskType: 'BUG_FIX' }
const QUESTION = {
  id: 3, title: '결재선 비교', repoCatalogId: 1, githubBranch: 'main', refRepos: [], attachments: [], turns: [],
}

interface PageVm {
  draft: { taskType: string | null; repoCatalogId: number | null; githubBranch: string; title: string; description: string }
  canSubmit: boolean
  submit: () => Promise<void>
  openCreate: () => Promise<void>
}

let current: ReturnType<typeof mount> | null = null
beforeEach(() => {
  routeQuery = {}
  navigateToMock.mockReset()
})
afterEach(() => {
  current?.unmount()
  current = null
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

function stubApi() {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/repo-catalog') return Promise.resolve(CATALOG)
    if (url === '/api/repos/branches') return Promise.resolve(BRANCHES)
    if (url === '/api/tasks/42') return Promise.resolve({ ...ORIGINAL_TASK })
    if (url === '/api/questions/3') return Promise.resolve({ ...QUESTION })
    if (url === '/api/tasks') return Promise.resolve({ content: [], totalElements: 0, totalPages: 0 })
    return Promise.resolve({})
  })
}

async function mountPage(query: Record<string, string> = {}) {
  routeQuery = query
  stubApi()
  // Nuxt 자동 임포트 컴포넌트(QueueStatsBar)는 vitest에서 해석되지 않는다 — 경고 없이 마운트하려고 스텁한다
  const w = mount(PageWrapper, { attachTo: document.body, global: { stubs: { QueueStatsBar: { template: '<div />' } } } })
  current = w
  await flushPromises()
  return w
}

const vmOf = (w: ReturnType<typeof mount>) => w.findComponent(TasksIndex).vm as unknown as PageVm

/** 목록 화면의 '작업 등록' 버튼으로 창을 연다(창 안의 같은 이름 버튼은 body로 텔레포트돼 w.findAll에 안 걸린다). */
async function openDialog(w: ReturnType<typeof mount>) {
  const addBtn = w.findAll('button').find((b) => b.text().includes('작업 등록'))
  await addBtn!.trigger('click')
  await flushPromises()
}

const selectOf = (w: ReturnType<typeof mount>, id: string) =>
  w.findAllComponents(QSelect).find((c) => (c.vm.$attrs as Record<string, unknown>)['data-test'] === id)!

/** 창 안 제출 버튼 — 목록의 같은 이름 버튼보다 뒤에 있다. */
const submitButton = () =>
  Array.from(document.querySelectorAll('button')).filter((b) => b.textContent?.includes('작업 등록')).at(-1) as HTMLButtonElement

function fillRest(vm: PageVm) {
  vm.draft.repoCatalogId = 1
  vm.draft.githubBranch = 'main'
  vm.draft.title = 't'
  vm.draft.description = 'd'
}

/** POST /api/tasks 호출의 body */
function postBody() {
  const call = useApiMock.mock.calls.find((c) => c[0] === '/api/tasks' && c[1]?.method === 'POST')
  expect(call).toBeTruthy()
  return call![1].body
}

describe('작업 등록 창 — 작업 타입 (스펙 2026-11-03 §4.2)', () => {
  it('타입을 고르기 전에는 등록 버튼이 막힌다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    fillRest(vm)
    await flushPromises()

    expect(vm.draft.taskType).toBeNull() // 미리 골라 두는 값이 없다
    expect(vm.canSubmit).toBe(false)
    expect(submitButton().disabled).toBe(true)

    selectOf(w, 'form-task-type').vm.$emit('update:modelValue', 'BUG_FIX')
    await flushPromises()
    expect(vm.draft.taskType).toBe('BUG_FIX')
    expect(vm.canSubmit).toBe(true)
    expect(submitButton().disabled).toBe(false)
  })

  it('등록 바디에 taskType', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    fillRest(vm)
    selectOf(w, 'form-task-type').vm.$emit('update:modelValue', 'BUG_FIX')
    await flushPromises()

    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()

    expect(postBody()).toEqual({ repoCatalogId: 1, githubBranch: 'main', title: 't', description: 'd', taskType: 'BUG_FIX' })
  })

  it('첨부와 함께 보내면 meta JSON에도 taskType이 실린다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    fillRest(vm)
    selectOf(w, 'form-task-type').vm.$emit('update:modelValue', 'DB_WORK')
    const file = new File(['내용'], '요구사항.txt', { type: 'text/plain' })
    ;(w.findComponent(TasksIndex).vm as unknown as { draftFiles: File[] }).draftFiles = [file]
    await flushPromises()

    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()

    const body = postBody() as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(JSON.parse(await (body.get('meta') as Blob).text())).toEqual({
      repoCatalogId: 1,
      githubBranch: 'main',
      title: 't',
      description: 'd',
      taskType: 'DB_WORK',
    })
  })

  it('타입이 비어 있으면 바디에 키가 없다(조건부 스프레드)', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    fillRest(vm)

    useApiMock.mockClear()
    await vm.submit()
    await flushPromises()

    expect(postBody()).toEqual({ repoCatalogId: 1, githubBranch: 'main', title: 't', description: 'd' })
    expect(Object.keys(postBody())).not.toContain('taskType')
  })

  it('타입 select는 등록 창 맨 앞(레포 select 위)에 있고 옵션은 네 타입뿐이다', async () => {
    const w = await mountPage()
    await openDialog(w)

    const typeField = document.querySelector('[data-test="form-task-type"]')!.closest('.q-field')!
    const repoField = document.querySelector('[data-test="repo-select"]')!.closest('.q-field')!
    expect(typeField).not.toBe(repoField)
    expect(typeField.compareDocumentPosition(repoField) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    const options = selectOf(w, 'form-task-type').props('options') as { value: unknown; label: string }[]
    expect(options.map((o) => o.value)).toEqual(['NEW_FEATURE', 'FEATURE_CHANGE', 'BUG_FIX', 'DB_WORK'])
    expect(options.map((o) => o.label)).toEqual(['신규 개발', '기능 수정', '버그 픽스', 'DB 작업'])
    expect(selectOf(w, 'form-task-type').props('label')).toBe('작업 타입')
  })

  it('창을 다시 열면 타입이 비워진다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const vm = vmOf(w)
    selectOf(w, 'form-task-type').vm.$emit('update:modelValue', 'FEATURE_CHANGE')
    await flushPromises()
    expect(vm.draft.taskType).toBe('FEATURE_CHANGE')

    await vm.openCreate()
    await flushPromises()
    expect(vm.draft.taskType).toBeNull()
  })

  it('fromQuestion 미리 채움은 타입을 비워 둔다 — 요청자가 직접 고른다', async () => {
    const w = await mountPage({ fromQuestion: '3' })
    const vm = vmOf(w)
    expect(vm.draft.repoCatalogId).toBe(1) // 미리 채움이 일어났다
    expect(vm.draft.title).toBe('결재선 비교')
    expect(vm.draft.taskType).toBeNull()
    vm.draft.description = '설명'
    await flushPromises()
    expect(vm.canSubmit).toBe(false)
  })

  it('fromTask 미리 채움은 원래 작업에 타입이 있어도 타입을 비워 둔다', async () => {
    const w = await mountPage({ fromTask: '42' })
    const vm = vmOf(w)
    expect(vm.draft.repoCatalogId).toBe(1) // 미리 채움이 일어났다
    expect(vm.draft.title).toBe('[작업 42 후속] 대상장비 탭 개선')
    expect(vm.draft.taskType).toBeNull()
    expect(vm.canSubmit).toBe(false)
  })

  it('등록 창 문구에 모델·MCP가 없다', async () => {
    const w = await mountPage()
    await openDialog(w)
    const dialog = document.querySelector('.q-dialog')!
    expect(dialog.textContent).not.toContain('모델')
    expect(dialog.textContent).not.toContain('MCP')
  })
})
