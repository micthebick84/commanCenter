import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import ApproveDialog from './ApproveDialog.vue'
import { useApiMock } from '../test/mocks/nuxt'
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

// 다이얼로그가 부르는 4개 엔드포인트를 URL로 분기 — 카탈로그(McpPicker)·지난 인터뷰·사용량(strip)·승인
function routeApi(
  opts: {
    catalog?: unknown[]
    interviews?: unknown[] | Error
    usage?: unknown
    approve?: () => Promise<unknown>
  } = {},
) {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/mcp-catalog') return Promise.resolve(opts.catalog ?? [])
    if (url === '/api/tasks/7/interviews') {
      return opts.interviews instanceof Error
        ? Promise.reject(opts.interviews)
        : Promise.resolve(opts.interviews ?? [])
    }
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
    expect(
      document.body.querySelector('[data-test="next-step"]')?.textContent,
    ).toContain('답변 대기')
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
      body: { model: 'claude-opus-5', effort: 'high', mcpCatalogIds: [] },
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
      body: { model: 'claude-opus-5', effort: 'high', mcpCatalogIds: [3] },
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

  it('지난 인터뷰가 없으면 기본값(Opus 5 · high)이고 안내는 없다', async () => {
    routeApi({ interviews: [] })
    const w = mountDialog()
    await flushPromises()
    expect(
      document.body.querySelector('[data-test="previous-hint"]'),
    ).toBeNull()
    expect(
      document.body
        .querySelector('[data-test="model-row-claude-opus-5"]')
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
      body: { model: 'claude-opus-5', effort: 'high', mcpCatalogIds: [] },
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
        .querySelector('[data-test="model-row-claude-opus-5"]')
        ?.getAttribute('aria-checked'),
    ).toBe('true')
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
})
