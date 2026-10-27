import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { QSelect } from 'quasar'
import ApproveDialog from './ApproveDialog.vue'
import RefRepoChips from './RefRepoChips.vue'
import RefRepoAddForm from './RefRepoAddForm.vue'
import { useApiMock, authStub } from '../test/mocks/nuxt'
import { setViewportWidth } from '../test/mocks/screen'
import { baseTask } from '../test/fixtures/task'

// 승인대기 작업 픽스처 — 다이얼로그는 부모([id].vue)가 이미 가진 task 객체를 받는다.
const task = {
  ...baseTask,
  id: 7,
  status: 'AWAITING_APPROVAL',
  statusLabel: '승인대기',
  title: '활동 스트림 스모크',
  repoAlias: 'Netis7.0',
  githubBranch: 'main',
  requesterId: 'admin',
  description:
    'README 상단에 프로젝트 한 줄 소개 문구를 추가하는 작은 개선을 계획해줘',
  implementation: null,
  analysis: null,
}

// 지난 인터뷰 행 — 백엔드 InterviewSummaryResponse 계약 미러 (InterviewHistoryCard.spec 선례)
const session = (
  id: number,
  statusName: string,
  status: string,
  model: string,
  effort: string,
) => ({
  id,
  status,
  statusName,
  currentPhase: null,
  model,
  effort,
  createdAt: `2026-08-2${id % 10}T10:00:00+09:00`,
  updatedAt: `2026-08-2${id % 10}T11:00:00+09:00`,
})

const future = new Date(Date.now() + 3 * 3600_000).toISOString()
const usage = {
  limits: [
    {
      limitType: 'five_hour',
      status: 'allowed',
      utilization: 0.42,
      resetsAt: future,
      usingOverage: false,
      reportedBy: 'iw-1',
      updatedAt: new Date().toISOString(),
    },
    {
      limitType: 'seven_day',
      status: 'allowed',
      utilization: 0.18,
      resetsAt: future,
      usingOverage: false,
      reportedBy: 'iw-1',
      updatedAt: new Date().toISOString(),
    },
  ],
}

// 다이얼로그가 부르는 엔드포인트를 URL로 분기 — 카탈로그(McpPicker)·지난 인터뷰·DB 연결·사용량(strip)·승인,
// 그리고 "＋ 참조 추가"를 눌렀을 때만 부르는 레포 카탈로그·브랜치(RefRepoAddForm)
function routeApi(
  opts: {
    catalog?: unknown[]
    interviews?: unknown[] | Error
    usage?: unknown
    db?: unknown
    repoCatalog?: unknown[] | (() => Promise<unknown>)
    approve?: () => Promise<unknown>
  } = {},
) {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/mcp-catalog') return Promise.resolve(opts.catalog ?? [])
    if (url === '/api/repo-catalog') {
      return typeof opts.repoCatalog === 'function'
        ? opts.repoCatalog()
        : Promise.resolve(opts.repoCatalog ?? [])
    }
    if (url === '/api/repos/branches') {
      return Promise.resolve({
        defaultBranch: 'main',
        branches: [{ name: 'main', sha: 'a' }, { name: 'release/6.5', sha: 'b' }],
      })
    }
    if (url === '/api/tasks/7/interviews') {
      return opts.interviews instanceof Error
        ? Promise.reject(opts.interviews)
        : Promise.resolve(opts.interviews ?? [])
    }
    if (url.startsWith('/api/db-connections?repoCatalogId='))
      return Promise.resolve(opts.db ?? { enabled: false, items: [] })
    if (url === '/api/usage/claude')
      return Promise.resolve(opts.usage ?? { limits: [] })
    if (url === '/api/tasks/7/approve')
      return opts.approve ? opts.approve() : Promise.resolve({})
    return Promise.resolve({})
  })
}

function mountDialog(overrides: Record<string, unknown> = {}) {
  return mount(ApproveDialog, {
    props: { modelValue: true, task: { ...task, ...overrides } },
    attachTo: document.body,
  })
}

function approveCall() {
  return useApiMock.mock.calls.find((c) => c[0] === '/api/tasks/7/approve')
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ApproveDialog (승인 팝업 UI/UX 개선 2026-09-06)', () => {
  it('작업 요약(번호·제목·레포·요청자)과 승인 후 다음 단계 안내를 보여준다', async () => {
    routeApi()
    const w = mountDialog()
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('#7')
    expect(text).toContain('활동 스트림 스모크')
    expect(text).toContain('Netis7.0')
    expect(text).toContain('요청자 admin')
    // 상태 칩에 실제로 보이는 백엔드 라벨("입력대기")과 맞춘다 (리뷰 파인딩 9)
    expect(
      document.body.querySelector('[data-test="next-step"]')?.textContent,
    ).toContain('입력대기')
    expect(
      document.body.querySelector('[data-test="next-step"]')?.textContent,
    ).toContain('대화형 분석')
    w.unmount()
  })

  it('승인 시 모델·effort·MCP 선택을 함께 보내고 닫힌다', async () => {
    routeApi()
    const w = mountDialog()
    await flushPromises()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1]).toEqual({
      method: 'POST',
      body: { model: 'claude-opus-5-5', effort: 'medium', mcpCatalogIds: [] },
    })
    expect(w.emitted('approved')).toBeTruthy()
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual([false])
    w.unmount()
  })

  it('MCP를 고르면 선택 id가 payload에 실린다 (flat 모드라 칩이 바로 보인다)', async () => {
    routeApi({
      catalog: [
        {
          id: 3,
          name: 'ctx7',
          displayName: 'Context7',
          url: 'https://ctx7',
          transport: 'http',
          description: null,
          lastCheckStatus: 'HEALTHY',
        },
      ],
    })
    const w = mountDialog()
    await flushPromises()
    expect(document.body.querySelector('[data-test="mcp-flat"]')).not.toBeNull()
    const picker = w.findComponent({ name: 'McpPicker' })
    ;(picker.vm as any).toggle(3)
    await flushPromises()
    await (w.vm as any).approve()
    expect(approveCall()?.[1]).toEqual({
      method: 'POST',
      body: { model: 'claude-opus-5-5', effort: 'medium', mcpCatalogIds: [3] },
    })
    w.unmount()
  })

  it('지난 인터뷰가 있으면 마지막 세션의 모델·추론 단계를 초기값으로 채우고 안내 한 줄을 보여준다', async () => {
    routeApi({
      interviews: [
        session(21, 'CANCELLED', '취소됨', 'claude-sonnet-5', 'max'),
        session(22, 'CANCELLED', '취소됨', 'claude-haiku-4-5', 'low'),
      ],
    })
    const w = mountDialog()
    await flushPromises()
    const hint =
      document.body.querySelector('[data-test="previous-hint"]')?.textContent ??
      ''
    expect(hint).toContain('이전 인터뷰 2건')
    expect(hint).toContain('#22')
    expect(hint).toContain('취소됨')
    expect(hint).toContain('Haiku 4.5')
    expect(hint).toContain('low')
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-haiku-4-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')
    await (w.vm as any).approve()
    expect(approveCall()?.[1]).toEqual({
      method: 'POST',
      body: { model: 'claude-haiku-4-5', effort: 'low', mcpCatalogIds: [] },
    })
    w.unmount()
  })

  it('지난 인터뷰가 없으면 기본값(Opus 5.5 · medium)이고 안내는 없다', async () => {
    routeApi({ interviews: [] })
    const w = mountDialog()
    await flushPromises()
    expect(
      document.body.querySelector('[data-test="previous-hint"]'),
    ).toBeNull()
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-opus-5-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')
    w.unmount()
  })

  it('지난 인터뷰 조회가 실패해도 기본값으로 열린다', async () => {
    routeApi({ interviews: new Error('403') })
    const w = mountDialog()
    await flushPromises()
    expect(
      document.body.querySelector('[data-test="previous-hint"]'),
    ).toBeNull()
    await (w.vm as any).approve()
    expect(approveCall()?.[1]).toEqual({
      method: 'POST',
      body: { model: 'claude-opus-5-5', effort: 'medium', mcpCatalogIds: [] },
    })
    w.unmount()
  })

  it('마지막 세션의 모델이 더는 고를 수 없는 값이면 안내만 보이고 초기값은 기본값이다', async () => {
    routeApi({
      interviews: [
        session(23, 'FAILED', '인터뷰실패', 'claude-fable-5', 'max'),
      ],
    })
    const w = mountDialog()
    await flushPromises()
    expect(
      document.body.querySelector('[data-test="previous-hint"]')?.textContent,
    ).toContain('#23')
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-opus-5-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')
    w.unmount()
  })

  // Opus 5.5가 기본이 된 뒤(2026-09-29)에도 Opus 5는 목록에 남는다 — 박제 모델로 취급하지 않는다.
  it('마지막 세션이 Opus 5면 기본값(Opus 5.5)이 아니라 Opus 5로 채운다', async () => {
    routeApi({
      interviews: [session(25, 'CANCELLED', '취소됨', 'claude-opus-5', 'xhigh')],
    })
    const w = mountDialog()
    await flushPromises()
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-opus-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')
    await (w.vm as any).approve()
    expect(approveCall()?.[1]).toEqual({
      method: 'POST',
      body: { model: 'claude-opus-5', effort: 'xhigh', mcpCatalogIds: [] },
    })
    w.unmount()
  })

  it('구독 사용량이 수집돼 있으면 strip으로 보여주고, 없으면 줄 자체가 없다', async () => {
    routeApi({ usage })
    const w = mountDialog()
    await flushPromises()
    expect(
      document.body.querySelector('[data-test="usage-strip"]')?.textContent,
    ).toContain('42%')
    w.unmount()
    document.body.innerHTML = ''

    routeApi({ usage: { limits: [] } })
    const w2 = mountDialog()
    await flushPromises()
    expect(document.body.querySelector('[data-test="usage-strip"]')).toBeNull()
    w2.unmount()
  })

  it('승인 실패 시 닫히지 않고 approved도 내지 않는다', async () => {
    routeApi({
      approve: () =>
        Promise.reject({
          data: { message: '승인대기 상태에서만 승인할 수 있습니다' },
        }),
    })
    const w = mountDialog()
    await flushPromises()
    await (w.vm as any).approve()
    await flushPromises()
    expect(w.emitted('approved')).toBeFalsy()
    expect(
      w.emitted('update:modelValue')?.some((e) => e[0] === false),
    ).toBeFalsy()
    w.unmount()
  })

  it('승인 요청 중에는 ESC/바깥 클릭으로 닫히지 않도록 persistent가 된다 (리뷰 파인딩 4)', async () => {
    let resolveApprove!: (v: unknown) => void
    routeApi({
      approve: () =>
        new Promise((resolve) => {
          resolveApprove = resolve
        }),
    })
    const w = mountDialog()
    await flushPromises()
    const dialog = w.findComponent({ name: 'QDialog' })
    expect(dialog.props('persistent')).toBe(false)
    const pending = (w.vm as any).approve()
    await flushPromises()
    expect(dialog.props('persistent')).toBe(true)
    resolveApprove({})
    await pending
    w.unmount()
  })

  it('닫았다 다시 열면 지난 인터뷰를 다시 읽고 선택이 프리필 값으로 돌아간다 (리뷰 파인딩 10)', async () => {
    routeApi({
      interviews: [
        session(22, 'CANCELLED', '취소됨', 'claude-sonnet-5', 'high'),
      ],
    })
    const w = mountDialog()
    await flushPromises()
    const fields = w.findComponent({ name: 'ModelEffortFields' })
    ;(fields.vm as any).pickModel('claude-haiku-4-5')
    await flushPromises()
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-haiku-4-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')

    await w.setProps({ modelValue: false })
    await flushPromises()
    await w.setProps({ modelValue: true })
    await flushPromises()

    expect(
      useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks/7/interviews'),
    ).toHaveLength(2)
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-sonnet-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')
    w.unmount()
  })

  it('마지막 세션에 모델·effort가 없으면(레거시) 안내는 상태까지만 쓰고 초기값은 기본값이다', async () => {
    routeApi({
      interviews: [
        {
          ...session(24, 'EXPIRED', '만료됨', 'claude-opus-5', 'high'),
          model: null,
          effort: null,
        },
      ],
    })
    const w = mountDialog()
    await flushPromises()
    const hint = (
      document.body.querySelector('[data-test="previous-hint"]')?.textContent ??
      ''
    ).trim()
    expect(hint).toContain('#24')
    expect(hint.endsWith('만료됨')).toBe(true)
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-opus-5-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')
    w.unmount()
  })

  const dbList = {
    enabled: true,
    items: [
      { id: 3, repoCatalogId: 9, scope: 'REPO', name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'ro', enabled: true, mine: false },
      { id: 4, repoCatalogId: 9, scope: 'USER', name: '내 개발', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'dev', username: 'me', enabled: true, mine: true },
    ],
  }

  // 이 describe는 승인자 = 등록자(admin) — 승인자 ≠ 등록자 케이스는 아래 describe에서 따로 본다(F1)
  beforeEach(() => {
    ;(authStub as any).me = { user_id: 1, username: 'admin', email: null, authorities: ['ROLE_ADMIN'] }
  })
  afterEach(() => {
    delete (authStub as any).me
  })

  it('DB 기능이 켜져 있으면 공용 연결을 기본 선택해 승인 바디에 싣는다', async () => {
    routeApi({ db: dbList })
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    expect(document.body.querySelector('[data-test="approve-db"]')).not.toBeNull()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([3])
    w.unmount()
  })

  it('마지막 인터뷰의 DB 선택이 있으면 그것을 프리필한다(목록에 있는 것만)', async () => {
    routeApi({
      db: dbList,
      interviews: [{ ...session(5, 'CANCELLED', '취소됨', 'claude-opus-5-5', 'high'), dbConnectionIds: [4, 99] }],
    })
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([4])
    w.unmount()
  })

  it('DB 기능이 꺼져 있거나 레포 카탈로그가 없으면 피커가 없고 바디에도 없다', async () => {
    routeApi({ db: { enabled: false, items: [] } })
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    expect(document.body.querySelector('[data-test="approve-db"]')).toBeNull()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body).not.toHaveProperty('dbConnectionIds')
    w.unmount()
  })

  // DB 목록 조회를 호출 순서대로 수동 resolve — 늦게 오는 응답/로딩 중 승인 시나리오용
  function deferDb() {
    const pending: Array<(v: unknown) => void> = []
    const base = useApiMock.getMockImplementation()!
    useApiMock.mockImplementation((url: string, ...rest: unknown[]) => {
      if (url.startsWith('/api/db-connections?repoCatalogId='))
        return new Promise((res) => pending.push(res))
      return (base as any)(url, ...rest)
    })
    return pending
  }
  const dbOf = (id: number) => ({
    enabled: true,
    items: [{ ...dbList.items[0], id }],
  })

  it('재오픈 후 먼저 시작한 DB 응답이 늦게 와도 선택을 덮어쓰지 않는다', async () => {
    routeApi({})
    const pending = deferDb()
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    await w.setProps({ modelValue: false })
    await w.setProps({ modelValue: true })
    await flushPromises()
    expect(pending.length).toBe(2)
    pending[1](dbOf(4))
    await flushPromises()
    pending[0](dbOf(3))
    await flushPromises()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([4])
    w.unmount()
  })

  it('DB 목록 로딩 중에는 승인 버튼이 비활성이고 approve()도 무시한다', async () => {
    routeApi({})
    const pending = deferDb()
    const w = mountDialog({ repoCatalogId: 9 })
    await flushPromises()
    const btn = () => document.body.querySelector('[data-test="approve-submit"]')
    expect(btn()?.hasAttribute('disabled') || btn()?.getAttribute('aria-disabled') === 'true').toBe(true)
    await (w.vm as any).approve()
    expect(approveCall()).toBeUndefined()
    pending[0](dbOf(3))
    await flushPromises()
    expect(btn()?.hasAttribute('disabled') || btn()?.getAttribute('aria-disabled') === 'true').toBe(false)
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([3])
    w.unmount()
  })
})
// F1: 승인은 관리자만 한다. 목록(/api/db-connections)은 보는 사람(관리자) 기준이라 관리자 본인의 USER 행은 보이고
// 등록자의 USER 행은 안 보인다. 서버는 등록자 기준으로 검증하므로, 승인자 ≠ 등록자면 공용(REPO)만 고르게 하고
// 지난 인터뷰의 안 보이는 id(등록자 개인 연결)는 버리지 않고 유지한다.
describe('ApproveDialog DB 연결 — 승인자 ≠ 등록자 (F1)', () => {
  const dbList = {
    enabled: true,
    items: [
      { id: 3, repoCatalogId: 9, scope: 'REPO', name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'ro', enabled: true, mine: false },
      { id: 4, repoCatalogId: 9, scope: 'USER', name: '관리자 개발', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'dev', username: 'adm', enabled: true, mine: true },
    ],
  }
  beforeEach(() => {
    ;(authStub as any).me = { user_id: 1, username: 'admin', email: null, authorities: ['ROLE_ADMIN'] }
  })
  afterEach(() => {
    delete (authStub as any).me
  })

  it('관리자 본인의 USER 연결과 "새 접속 추가"를 숨기고 공용만 보인다', async () => {
    routeApi({ db: dbList })
    const w = mountDialog({ repoCatalogId: 9, requesterId: 'user1' })
    await flushPromises()
    const box = document.body.querySelector('[data-test="approve-db"]')!
    expect(box.textContent).toContain('운영')
    expect(box.textContent).not.toContain('관리자 개발')
    expect(box.querySelector('[data-test="db-add"]')).toBeNull()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([3])
    w.unmount()
  })

  it('지난 인터뷰의 안 보이는 id(등록자 개인 연결)는 유지해 칩으로 보이고, 관리자 본인 USER id는 버린다', async () => {
    routeApi({
      db: dbList,
      interviews: [{ ...session(5, 'CANCELLED', '취소됨', 'claude-opus-5-5', 'high'), dbConnectionIds: [3, 4, 42] }],
    })
    const w = mountDialog({ repoCatalogId: 9, requesterId: 'user1' })
    await flushPromises()
    const box = document.body.querySelector('[data-test="approve-db"]')!
    expect(box.textContent).toContain('등록자 개인 연결 #42')
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1].body.dbConnectionIds).toEqual([3, 42])
    w.unmount()
  })

  it('승인자 = 등록자면 본인 USER 연결과 "새 접속 추가"가 그대로 보인다', async () => {
    routeApi({ db: dbList })
    const w = mountDialog({ repoCatalogId: 9, requesterId: 'admin' })
    await flushPromises()
    const box = document.body.querySelector('[data-test="approve-db"]')!
    expect(box.textContent).toContain('관리자 개발')
    expect(box.querySelector('[data-test="db-add"]')).not.toBeNull()
    w.unmount()
  })
})

// 참조 repo 조정 (작업 스펙 2026-10-24 §7.3) — 열 때마다 작업의 참조로 채운 편집 칩 줄 + 인라인 추가 폼.
// 바디에는 작업 값과 다를 때만 refRepos(전부 빼면 []), 레포 카탈로그는 "＋ 참조 추가"를 처음 누를 때 한 번 받는다.
describe('ApproveDialog 참조 repo (스펙 2026-10-24 §7.3)', () => {
  const OLD = { catalogId: 2, alias: '옛앱', branch: 'release/6.5', sha: null, fetchedAt: null, error: null }
  const UI = { catalogId: 3, alias: '공통 UI', branch: 'main', sha: null, fetchedAt: null, error: null }
  // 1=대상 · 2=옛앱 · 3=공통 UI · 4=지원하지 않는 호스트(추가 폼이 스스로 뺀다)
  const REPO_CATALOG = [
    { id: 1, alias: 'Netis7.0', host: 'github', defaultBranch: 'main' },
    { id: 2, alias: '옛앱', host: 'gitlab', defaultBranch: 'release/6.5' },
    { id: 3, alias: '공통 UI', host: 'gitlab', defaultBranch: 'main' },
    { id: 4, alias: '외부', host: 'other', defaultBranch: null },
  ]
  const dbOn = {
    enabled: true,
    items: [
      { id: 3, repoCatalogId: 1, scope: 'REPO', name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'ro', enabled: true, mine: false },
    ],
  }
  const BODY_WITHOUT_REFS = { model: 'claude-opus-5-5', effort: 'medium', mcpCatalogIds: [] }

  const refsBox = () => document.body.querySelector('[data-test="approve-refs"]')
  const refChips = () => Array.from(document.body.querySelectorAll('[data-test="approve-refs"] [data-test="ref-chip"]'))
  const addForm = () => document.body.querySelector('[data-test="approve-refs"] [data-test="ref-add-form"]')
  const catalogCalls = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/repo-catalog').length
  type Wrapper = ReturnType<typeof mountDialog>
  async function clickAdd(w: Wrapper) {
    await w.findComponent(RefRepoChips).find('[data-test="ref-add"]').trigger('click')
    await flushPromises()
  }
  async function removeFirstChip(w: Wrapper) {
    await w.findComponent(RefRepoChips).find('.q-chip__icon--remove').trigger('click')
    await flushPromises()
  }

  beforeEach(() => {
    ;(authStub as any).me = { user_id: 1, username: 'admin', email: null, authorities: ['ROLE_ADMIN'] }
  })
  afterEach(() => {
    delete (authStub as any).me
  })

  it('작업 참조로 칩 줄을 채우고("인터뷰 때 받음") DB 아래·사용량 위에 둔다', async () => {
    routeApi({ db: dbOn })
    const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD] })
    await flushPromises()
    const box = refsBox()!
    expect(box).not.toBeNull()
    expect(document.body.querySelector('[data-test="approve-db"]')!.nextElementSibling).toBe(box)
    expect(box.nextElementSibling!.classList.contains('usage-slot')).toBe(true)

    expect(box.textContent).toContain('참조 repo · 읽기 전용 · 최대 3개')
    expect(refChips()).toHaveLength(1)
    expect(refChips()[0]!.getAttribute('data-state')).toBe('pending')
    expect(refChips()[0]!.textContent).toContain('📚 옛앱 · release/6.5')
    expect(refChips()[0]!.textContent).toContain('인터뷰 때 받음')
    expect(box.querySelector('[data-test="ref-add"]')).not.toBeNull()
    // 승인 요약에는 읽기 전용 칩 줄을 넣지 않는다 — 편집 줄 하나뿐
    expect(document.body.querySelector('[data-test="approve-summary"] [data-test="ref-repo-chips"]')).toBeNull()
    expect(document.body.querySelectorAll('[data-test="ref-repo-chips"]')).toHaveLength(1)
    w.unmount()
  })

  it('모바일 전체화면에서도 같은 순서다', async () => {
    await setViewportWidth(390)
    try {
      routeApi({ db: dbOn })
      const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD] })
      await flushPromises()
      expect(document.body.querySelector('[data-test="approve-topbar"]')).not.toBeNull()
      const box = refsBox()!
      expect(document.body.querySelector('[data-test="approve-db"]')!.nextElementSibling).toBe(box)
      expect(box.nextElementSibling!.classList.contains('usage-slot')).toBe(true)
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('참조를 안 바꾸면 승인 바디는 지금과 같다', async () => {
    routeApi()
    const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD, UI] })
    await flushPromises()
    expect(refChips()).toHaveLength(2)
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1]).toEqual({ method: 'POST', body: BODY_WITHOUT_REFS })
    w.unmount()
  })

  it('빼면 남은 목록, 전부 빼면 []를 싣는다', async () => {
    routeApi()
    const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD, UI] })
    await flushPromises()
    await removeFirstChip(w)
    expect(refChips()).toHaveLength(1)
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1]).toEqual({
      method: 'POST',
      body: { ...BODY_WITHOUT_REFS, refRepos: [{ repoCatalogId: 3, branch: 'main' }] },
    })
    w.unmount()
    document.body.innerHTML = ''

    useApiMock.mockClear()
    const w2 = mountDialog({ repoCatalogId: 1, refRepos: [OLD, UI] })
    await flushPromises()
    await removeFirstChip(w2)
    await removeFirstChip(w2)
    expect(refChips()).toHaveLength(0)
    expect(refsBox()!.textContent).toContain('참조 repo 없음')
    await (w2.vm as any).approve()
    await flushPromises()
    // 생략(= 작업 값 유지)이 아니라 빈 목록(= 전부 해제)이다
    expect(approveCall()?.[1]).toEqual({ method: 'POST', body: { ...BODY_WITHOUT_REFS, refRepos: [] } })
    w2.unmount()
  })

  it('＋ 참조 추가를 처음 누를 때만 /api/repo-catalog를 부른다 — 열 때는 0회, 두 번째 토글도 1회', async () => {
    routeApi({ repoCatalog: REPO_CATALOG })
    const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD] })
    await flushPromises()
    expect(catalogCalls()).toBe(0)
    expect(addForm()).toBeNull()

    await clickAdd(w)
    expect(catalogCalls()).toBe(1)
    expect(addForm()).not.toBeNull()
    await clickAdd(w) // 접기
    expect(addForm()).toBeNull()
    await clickAdd(w) // 다시 펼치기
    expect(addForm()).not.toBeNull()
    expect(catalogCalls()).toBe(1)
    w.unmount()
  })

  it('레포 목록을 못 받으면 알리고, 다음에 펼칠 때 다시 부른다', async () => {
    let fail = true
    routeApi({
      repoCatalog: () =>
        fail ? Promise.reject({ data: { message: '레포 카탈로그 조회 실패' } }) : Promise.resolve(REPO_CATALOG),
    })
    const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD] })
    await flushPromises()
    const notify = vi.spyOn((w.vm as any).$q, 'notify')

    await clickAdd(w)
    expect(notify).toHaveBeenCalledWith({ type: 'negative', message: '레포 카탈로그 조회 실패' })
    await clickAdd(w) // 접기
    fail = false
    await clickAdd(w)
    expect(catalogCalls()).toBe(2)
    expect(w.findComponent(RefRepoAddForm).props('catalog')).toEqual(REPO_CATALOG)
    w.unmount()
  })

  it('추가 폼 후보는 대상·이미 고른 참조를 뺀다, 추가하면 바디에 실린다', async () => {
    routeApi({ repoCatalog: REPO_CATALOG })
    const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD] })
    await flushPromises()
    await clickAdd(w)

    const form = w.findComponent(RefRepoAddForm)
    // 칩 줄 아래, 같은 참조 줄 안의 윤곽선 패널에 펼친다
    expect(form.element.parentElement!.classList.contains('ref-add-panel')).toBe(true)
    expect(refsBox()!.contains(form.element)).toBe(true)
    expect(form.props('excludeIds')).toEqual([1, 2])
    const repoSelect = form
      .findAllComponents(QSelect)
      .find((c) => (c.vm.$attrs as Record<string, unknown>)['data-test'] === 'ref-add-repo')!
    expect((repoSelect.props('options') as Array<{ value: unknown }>).map((o) => o.value)).toEqual([3])

    repoSelect.vm.$emit('update:modelValue', 3)
    await flushPromises()
    await form.find('[data-test="ref-add-submit"]').trigger('click')
    await flushPromises()
    expect(addForm()).toBeNull() // 추가하면 접힌다
    expect(refChips()).toHaveLength(2)
    expect(refChips()[1]!.textContent).toContain('📚 공통 UI · main')

    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1]).toEqual({
      method: 'POST',
      body: {
        ...BODY_WITHOUT_REFS,
        refRepos: [
          { repoCatalogId: 2, branch: 'release/6.5' },
          { repoCatalogId: 3, branch: 'main' },
        ],
      },
    })
    w.unmount()
  })

  it('닫았다 다시 열면 작업 참조로 다시 채운다', async () => {
    routeApi({ repoCatalog: REPO_CATALOG })
    const w = mountDialog({ repoCatalogId: 1, refRepos: [OLD, UI] })
    await flushPromises()
    await removeFirstChip(w)
    await clickAdd(w)
    expect(refChips()).toHaveLength(1)
    expect(addForm()).not.toBeNull()

    await w.setProps({ modelValue: false })
    await flushPromises()
    await w.setProps({ modelValue: true })
    await flushPromises()

    expect(refChips().map((c) => c.textContent)).toEqual([
      expect.stringContaining('📚 옛앱 · release/6.5'),
      expect.stringContaining('📚 공통 UI · main'),
    ])
    expect(addForm()).toBeNull()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1]).toEqual({ method: 'POST', body: BODY_WITHOUT_REFS })
    w.unmount()
  })

  it('repoCatalogId 없는 작업은 참조 줄이 없다', async () => {
    routeApi()
    const w = mountDialog({ repoCatalogId: null })
    await flushPromises()
    expect(document.body.querySelector('[data-test="approve-summary"]')).not.toBeNull()
    expect(refsBox()).toBeNull()
    await (w.vm as any).approve()
    await flushPromises()
    expect(approveCall()?.[1]).toEqual({ method: 'POST', body: BODY_WITHOUT_REFS })
    w.unmount()
  })
})
