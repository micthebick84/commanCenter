import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { QFile } from 'quasar'
import QuestionDetail from '../pages/questions/[id].vue'
import ModelEffortPicker from '../components/chat/ModelEffortPicker.vue'
import RefRepoChips from '../components/RefRepoChips.vue'
import RefRepoAddForm from '../components/RefRepoAddForm.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { FakeEventSource } from './mocks/eventsource'
import { setViewportWidth } from './mocks/screen'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

// 참조 repo 세 상태 한 벌 (스펙 2026-10-22 §7.3): 커밋 고정 · 아직 안 받음 · 실패. 서버 값(RefRepoView)이라 커밋·시각·사유가 붙는다.
const REF_PINNED = {
  catalogId: 2,
  alias: '옛앱',
  branch: 'release/6.5',
  sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  fetchedAt: '2026-10-22T05:30:00Z',
  error: null,
}
const REF_PENDING = { catalogId: 3, alias: '공통 UI', branch: 'main', sha: null, fetchedAt: null, error: null }
const REF_FAILED = {
  catalogId: 5,
  alias: '인증 서버',
  branch: 'develop',
  sha: null,
  fetchedAt: null,
  error: 'fatal: Remote branch develop not found in upstream origin',
}

// 헤더 폴링(GET /api/questions/3)과 패널 스냅샷(같은 URL)이 모두 이 응답을 쓴다.
const detail = {
  id: 3,
  title: '인증 흐름 확인',
  githubRepo: 'micthebick84/netis7.0',
  githubBranch: 'main',
  status: '입력대기',
  statusName: 'AWAITING_INPUT',
  model: 'claude-sonnet-5',
  effort: 'medium',
  kind: 'QUESTION',
  totalCostUsd: 0.42,
  contextTokens: 76004,
  contextWindow: 200000,
  mcpCatalogIds: [5, 9], // 세션에 현재 적용된 카탈로그 id — 픽커 시딩 (스펙 2026-09-13 §4)
  attachments: [], // 등록 시 첨부(turn_seq null) — 헤더 칩 줄
  refRepos: [REF_PINNED, REF_PENDING, REF_FAILED], // 읽기 전용 참조 repo — 헤더 아래 칩 줄 (스펙 2026-10-22 §7.3)
  turns: [
    {
      seq: 1,
      role: 'assistant',
      kind: 'question',
      content: '**AuthController**입니다',
    },
  ],
  plan: null,
}

describe('pages/questions/[id] — 대화 (스펙 2026-09-05 §3·§6)', () => {
  beforeEach(() => {
    authStub.accessToken = 'jwt'
    navigateToMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.resolve(detail)
        : url === '/api/usage/claude'
          ? Promise.resolve({ limits: [] })
          : Promise.resolve(null),
    )
  })

  it('헤더(제목·상태·레포·비용·컨텍스트)와 채팅 입력창을 그리고 답변은 마크다운으로 렌더한다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.text()).toContain('인증 흐름 확인')
    expect(w.find('[data-test="status-badge"]').text()).toBe('답변 완료')
    expect(w.text()).toContain('micthebick84/netis7.0 · main')
    expect(w.text()).toContain('누적 비용 0.42')
    expect(w.find('[data-test="context-chip"]').text()).toContain(
      '컨텍스트 38%',
    )
    expect(w.find('[data-test="composer-send"]').exists()).toBe(true)
    expect(w.find('[data-test="send-answer"]').exists()).toBe(false) // 기본 입력창 대신 슬롯
    expect(
      w.find('[data-test="model-picker"]').attributes('disabled'),
    ).toBeUndefined() // 입력 대기 중엔 모델·effort도 바꿀 수 있다 (스펙 §2 개정)
    expect(w.find('[data-test="model-picker"]').text()).toContain('Sonnet 5') // 세션 값으로 시드
    expect(w.find('.bubble.assistant strong').text()).toBe('AuthController')
    expect(w.find('[data-test="close-session"]').text()).toContain('세션 종료') // 넓은 화면: 라벨 노출
    expect(w.find('[data-test="open-drawer"]').exists()).toBe(false)
    w.unmount()
  })

  it('입력창에서 Enter로 추가 질문을 보낸다 (replyToSeq = 마지막 assistant seq, 현재 세션 model/effort 동봉)', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    const ta = w.find('textarea')
    await ta.setValue('리프레시 토큰은요?')
    await ta.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/ask', {
      method: 'POST',
      body: {
        answer: '리프레시 토큰은요?',
        replyToSeq: 1,
        model: 'claude-sonnet-5',
        effort: 'medium',
        mcpCatalogIds: [5, 9], // 바꾸지 않아도 현재 집합을 실어 보낸다 — 서버는 같은 집합이면 검증 없는 no-op (스펙 2026-09-13 §5.2)
      },
    })
    w.unmount()
  })

  it('대화 중 모델을 바꿔 보내면 ask 바디에 새 model/effort가 실린다 (다음 답변부터 적용)', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    ;(w.findComponent(ModelEffortPicker).vm as any).pickModel(
      'claude-haiku-4-5',
    )
    await flushPromises()
    expect(w.find('[data-test="model-picker"]').text()).toContain('Haiku 4.5')
    const ta = w.find('textarea')
    await ta.setValue('이번 건 싸게 답해줘')
    await ta.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/ask', {
      method: 'POST',
      body: {
        answer: '이번 건 싸게 답해줘',
        replyToSeq: 1,
        model: 'claude-haiku-4-5',
        effort: 'medium',
        mcpCatalogIds: [5, 9],
      },
    })
    w.unmount()
  })

  it('답변 중(RUNNING)에는 입력창과 함께 픽커도 잠긴다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(
      w.find('[data-test="model-picker"]').attributes('disabled'),
    ).toBeDefined()
    w.unmount()
  })

  it('SSE 상태가 배지에 즉시 반영되고, 세션 종료 버튼은 패널의 종료 확인 다이얼로그를 연다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="status-badge"]').text()).toBe('답변 중')
    await w.find('[data-test="close-session"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('질문 세션을 종료할까요?')
    w.unmount()
  })

  it('컨텍스트 미보고(null)면 칩을 숨긴다', async () => {
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.resolve({
            ...detail,
            contextTokens: null,
            contextWindow: null,
          })
        : Promise.resolve({ limits: [] }),
    )
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.find('[data-test="context-chip"]').exists()).toBe(false)
    w.unmount()
  })

  it('조회 실패(403/404)면 목록으로 돌려보낸다', async () => {
    useApiMock.mockImplementation((url: string) =>
      url === '/api/questions/3'
        ? Promise.reject({
            statusCode: 403,
            data: { message: '권한이 없습니다' },
          })
        : Promise.resolve({ limits: [] }),
    )
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(navigateToMock).toHaveBeenCalledWith('/questions')
    w.unmount()
  })

  it('폴링 중 500 등 비403/404 오류는 목록으로 이동하지 않고 마지막 헤더를 유지한다', async () => {
    // useTaskPolling은 전역 스텁(setup.ts) — 실제 컴포저블처럼 감싸 refresh 핸들을 잡아서
    // "다음 폴링 틱"을 테스트에서 직접 발동시킨다 (첫 마운트는 그대로 성공시킨 뒤).
    const originalUseTaskPolling = (globalThis as any).useTaskPolling
    let capturedRefresh: (() => Promise<void>) | undefined
    ;(globalThis as any).useTaskPolling = (fetcher: () => Promise<unknown>) => {
      const handle = originalUseTaskPolling(fetcher)
      if (!capturedRefresh) capturedRefresh = handle.refresh
      return handle
    }
    try {
      const w = mount(QuestionDetail)
      await flushPromises()
      expect(w.text()).toContain('인증 흐름 확인')
      expect(w.text()).toContain('누적 비용 0.42')

      useApiMock.mockImplementation((url: string) =>
        url === '/api/questions/3'
          ? Promise.reject({ statusCode: 500 })
          : Promise.resolve({ limits: [] }),
      )
      await capturedRefresh!()
      await flushPromises()

      expect(navigateToMock).not.toHaveBeenCalled()
      expect(w.text()).toContain('인증 흐름 확인')
      expect(w.text()).toContain('누적 비용 0.42')
      w.unmount()
    } finally {
      ;(globalThis as any).useTaskPolling = originalUseTaskPolling
    }
  })

  it('좁은 화면(lt.md)에서는 세션 종료 버튼이 라벨 없이 아이콘만 남기고 aria-label로 이름을 유지한다', async () => {
    // 390px에서 라벨이 두 줄로 꺾여 61px 헤더 밖으로 넘치던 결함(2026-09-06 모바일 QA).
    await setViewportWidth(390)
    try {
      const w = mount(QuestionDetail)
      await flushPromises()
      expect(w.find('[data-test="open-drawer"]').exists()).toBe(true) // 서랍 버튼 = 좁은 화면 분기 진입 확인
      const btn = w.find('[data-test="close-session"]')
      expect(btn.exists()).toBe(true)
      expect(btn.text()).not.toContain('세션 종료')
      expect(btn.attributes('aria-label')).toBe('세션 종료')
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  it('xs 화면(<600px)에서는 MCP 도구 버튼도 라벨 없이 아이콘만 남긴다', async () => {
    await setViewportWidth(390)
    try {
      const w = mount(QuestionDetail)
      await flushPromises()
      const btn = w.find('[data-test="mcp-button"]')
      expect(btn.exists()).toBe(true)
      expect(btn.text()).not.toContain('MCP 도구')
      expect(btn.attributes('aria-label')).toBe('MCP 도구')
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })
})

describe('pages/questions/[id] — 대화 중 MCP 변경 + 채팅 첨부 (스펙 2026-09-13 §7)', () => {
  const docx = { id: 7, fileName: '요구사항.docx', contentType: null, sizeBytes: 1536 }
  const detailWith = (extra: Record<string, unknown>) => ({ ...detail, ...extra })

  // URL.createObjectURL/revokeObjectURL은 대입으로 갈아끼우므로 원본으로 되돌린다 (task-detail-attachments.spec.ts 규약).
  const origCreateObjectURL = URL.createObjectURL
  const origRevokeObjectURL = URL.revokeObjectURL
  let clickSpy: ReturnType<typeof vi.spyOn> | null = null

  function stubApi(d: Record<string, unknown> = detail, blob: Blob | null = null) {
    useApiMock.mockImplementation((url: string) => {
      // 폴링마다 새 객체 — 같은 참조를 돌려주면 watch(detail)이 재발화하지 않아 "1회 시딩" 테스트가 무의미해진다
      if (url === '/api/questions/3') return Promise.resolve({ ...d })
      if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
      if (url === '/api/mcp-catalog') return Promise.resolve([])
      if (url.startsWith('/api/questions/3/attachments/')) return Promise.resolve(blob)
      return Promise.resolve(null)
    })
  }

  function spyAnchorClick() {
    let anchor: HTMLAnchorElement | null = null
    clickSpy = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) {
        anchor = this
      })
    return () => anchor
  }

  async function sendText(w: ReturnType<typeof mount>, text: string) {
    const ta = w.find('textarea')
    await ta.setValue(text)
    await ta.trigger('keydown', { key: 'Enter' })
    await flushPromises()
  }

  beforeEach(() => {
    authStub.accessToken = 'jwt'
    navigateToMock.mockReset()
    stubApi()
  })
  afterEach(() => {
    clickSpy?.mockRestore()
    clickSpy = null
    Object.assign(URL, { createObjectURL: origCreateObjectURL, revokeObjectURL: origRevokeObjectURL })
  })

  it('MCP 버튼은 활성이고, 세션의 mcpCatalogIds로 1회 시딩된 선택 개수를 배지로 보인다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    const btn = w.find('[data-test="mcp-button"]')
    expect(btn.attributes('disabled')).toBeUndefined()
    expect(btn.text()).toContain('MCP 도구')
    expect(btn.find('.q-badge').text()).toBe('2')
    expect((w.vm as any).pickedMcp).toEqual([5, 9])
    w.unmount()
  })

  it('MCP 선택을 바꿔 보내면 ask 바디 mcpCatalogIds에 새 집합이 실린다 (다음 질문부터 적용)', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    // 메뉴(q-menu → McpPicker.toggle) 대신 직접 갱신 — pages/questions/index.vue 테스트가 draft를 직접 채우는 것과 같은 선례
    ;(w.vm as any).pickedMcp.push(2)
    await flushPromises()
    expect(w.find('[data-test="mcp-button"] .q-badge').text()).toBe('3')
    await sendText(w, 'github 이슈도 같이 봐줘')
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/ask', {
      method: 'POST',
      body: {
        answer: 'github 이슈도 같이 봐줘',
        replyToSeq: 1,
        model: 'claude-sonnet-5',
        effort: 'medium',
        mcpCatalogIds: [5, 9, 2],
      },
    })
    w.unmount()
  })

  it('전부 해제하면 빈 배열을 보낸다 (null=유지가 아니라 []=전부 해제)', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    ;(w.vm as any).pickedMcp.splice(0)
    await flushPromises()
    expect(w.find('[data-test="mcp-button"] .q-badge').exists()).toBe(false)
    await sendText(w, 'MCP 없이 답해줘')
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
    expect(call![1].body.mcpCatalogIds).toEqual([])
    w.unmount()
  })

  it('폴링이 detail을 다시 가져와도 사용자가 고른 MCP 선택은 덮이지 않는다 (1회 시딩)', async () => {
    const originalUseTaskPolling = (globalThis as any).useTaskPolling
    let capturedRefresh: (() => Promise<void>) | undefined
    ;(globalThis as any).useTaskPolling = (fetcher: () => Promise<unknown>) => {
      const handle = originalUseTaskPolling(fetcher)
      if (!capturedRefresh) capturedRefresh = handle.refresh
      return handle
    }
    try {
      const w = mount(QuestionDetail)
      await flushPromises()
      ;(w.vm as any).pickedMcp.push(2)
      await capturedRefresh!()
      await flushPromises()
      expect((w.vm as any).pickedMcp).toEqual([5, 9, 2])
      w.unmount()
    } finally {
      ;(globalThis as any).useTaskPolling = originalUseTaskPolling
    }
  })

  it('답변 중(RUNNING)에는 MCP 버튼과 클립 버튼도 잠긴다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    FakeEventSource.last().emit('status', 'RUNNING')
    await flushPromises()
    expect(w.find('[data-test="mcp-button"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-test="composer-attach"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('파일을 첨부해 보내면 multipart(meta JSON + files)로 POST하고, 성공 시 대기 칩을 비우며 내 말풍선 아래 칩으로 남긴다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    const f = new File(['hello'], '메모.txt', { type: 'text/plain' })
    w.findComponent(QFile).vm.$emit('update:modelValue', [f])
    await flushPromises()
    expect(w.find('[data-test="composer-files"]').text()).toContain('메모.txt')

    await sendText(w, '이 메모 기준으로 설명해줘')

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
    expect(call).toBeTruthy()
    expect(call![1].method).toBe('POST')
    const body = call![1].body as FormData
    expect(body).toBeInstanceOf(FormData)
    const metaBlob = body.get('meta') as Blob
    expect(metaBlob.type).toBe('application/json')
    expect(JSON.parse(await metaBlob.text())).toEqual({
      answer: '이 메모 기준으로 설명해줘',
      replyToSeq: 1,
      model: 'claude-sonnet-5',
      effort: 'medium',
      mcpCatalogIds: [5, 9],
    })
    const sent = body.getAll('files') as File[]
    expect(sent.map((x) => x.name)).toEqual(['메모.txt'])
    expect(call![1].headers?.['Content-Type']).toBeUndefined() // $fetch가 boundary를 스스로 설정
    // 성공 → 대기 파일 초기화, 낙관적 user 턴 아래 (id 없는) 칩
    expect(w.find('[data-test="composer-files"]').exists()).toBe(false)
    expect(w.find('.bubble-row.user .bubble-attachments').text()).toContain('메모.txt')
    w.unmount()
  })

  it('전송이 거부되면(400) 대기 파일과 입력을 그대로 둔다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['x'], '메모.txt')])
    await flushPromises()
    useApiMock.mockImplementation((url: string, opts?: { method?: string }) =>
      url === '/api/questions/3/ask' && opts?.method === 'POST'
        ? Promise.reject({ statusCode: 400, data: { message: '문답 상한(10턴)에 도달했습니다' } })
        : url === '/api/questions/3'
          ? Promise.resolve(detail)
          : Promise.resolve({ limits: [] }),
    )
    await sendText(w, '한 번 더')
    expect(w.find('[data-test="composer-files"]').text()).toContain('메모.txt')
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('한 번 더')
    expect(document.body.textContent).toContain('문답 상한(10턴)')
    w.unmount()
  })

  it('등록 시 첨부(detail.attachments)는 헤더 아래 칩 줄에 보이고, 클릭하면 blob으로 내려받는다', async () => {
    const blob = new Blob(['docx'])
    stubApi(detailWith({ attachments: [docx] }), blob)
    const createObjectURL = vi.fn(() => 'blob:mock')
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() })
    const anchor = spyAnchorClick()
    const w = mount(QuestionDetail)
    await flushPromises()
    const strip = w.find('[data-test="session-attachments"]')
    expect(strip.exists()).toBe(true)
    expect(strip.text()).toContain('요구사항.docx')
    expect(strip.text()).toContain('2KB')
    await strip.find('.q-chip').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/attachments/7', {
      responseType: 'blob',
    })
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(anchor()?.download).toBe('요구사항.docx')
    w.unmount()
  })

  it('보존 기간이 지나 정리된(expired) 등록 첨부는 비활성 칩이라 눌러도 내려받지 않는다', async () => {
    stubApi(detailWith({ attachments: [{ ...docx, expired: true }] }), new Blob(['x']))
    const w = mount(QuestionDetail)
    await flushPromises()
    const chip = w.find('[data-test="session-attachments"] .q-chip')
    expect(chip.classes()).not.toContain('q-chip--clickable')
    expect(chip.attributes('data-test')).toBe('attachment-expired')
    useApiMock.mockClear()
    await chip.trigger('click')
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalledWith('/api/questions/3/attachments/7', expect.anything())
    w.unmount()
  })

  it('등록 첨부가 없으면 헤더 칩 줄을 그리지 않는다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.find('[data-test="session-attachments"]').exists()).toBe(false)
    w.unmount()
  })

  it('지난 턴의 첨부(스냅샷 turns[].attachments)는 내 말풍선 아래 칩으로 복원되고, 클릭하면 같은 경로로 내려받는다', async () => {
    const blob = new Blob(['png'])
    const png = { id: 8, fileName: '캡처.png', contentType: 'image/png', sizeBytes: 512 }
    stubApi(
      detailWith({
        turns: [
          ...detail.turns,
          { seq: 2, role: 'user', kind: 'answer', content: '이 화면이야', attachments: [png] },
          { seq: 3, role: 'assistant', kind: 'question', content: '확인했습니다' },
        ],
      }),
      blob,
    )
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:mock'), revokeObjectURL: vi.fn() })
    const anchor = spyAnchorClick()
    const w = mount(QuestionDetail)
    await flushPromises()
    const chip = w.find('.bubble-row.user .bubble-attachments .q-chip')
    expect(chip.text()).toContain('캡처.png')
    await chip.trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/attachments/8', {
      responseType: 'blob',
    })
    expect(anchor()?.download).toBe('캡처.png')
    w.unmount()
  })

  it('다운로드 실패(403)는 서버 메시지로 알린다', async () => {
    stubApi(detailWith({ attachments: [docx] }))
    useApiMock.mockImplementation((url: string) => {
      if (url === '/api/questions/3') return Promise.resolve(detailWith({ attachments: [docx] }))
      if (url.startsWith('/api/questions/3/attachments/'))
        return Promise.reject({
          statusCode: 403,
          data: new Blob([JSON.stringify({ message: '권한이 없습니다' })], { type: 'application/json' }),
        })
      return Promise.resolve({ limits: [] })
    })
    const createObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL })
    const w = mount(QuestionDetail)
    await flushPromises()
    await w.find('[data-test="session-attachments"] .q-chip').trigger('click')
    await flushPromises()
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('권한이 없습니다')
    w.unmount()
  })

  it('SSE note 이벤트(MCP 변경)는 가운데 시스템 노트로 즉시 표시된다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    FakeEventSource.last().emit('note', { seq: 2, content: 'MCP 도구 변경: github' })
    await flushPromises()
    expect(w.find('.system-note').text()).toBe('MCP 도구 변경: github')
    w.unmount()
  })

  // 스펙 2026-10-02 §7 — sendText 헬퍼를 쓰려고 이 describe 안에 둔다
  describe('DB 연결', () => {
    const dbDetail = { ...detail, repoCatalogId: 1, dbConnectionIds: [7], dbConnections: [{ id: 7, name: '운영', dbType: 'MYSQL' }] }
    const dbApi = (enabled: boolean) => (url: string) =>
      url === '/api/questions/3' ? Promise.resolve({ ...dbDetail })
        : url === '/api/db-connections?repoCatalogId=1' ? Promise.resolve({
          enabled,
          items: enabled
            ? [{ id: 7, scope: 'REPO', repoCatalogId: 1, name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', enabled: true, mine: false }]
            : [],
        })
          : url === '/api/usage/claude' ? Promise.resolve({ limits: [] })
            : url === '/api/mcp-catalog' ? Promise.resolve([])
              : Promise.resolve(null)

    it('세션 값으로 시딩된 DB 버튼·헤더 칩을 보이고, 보낼 때 dbConnectionIds를 싣는다', async () => {
      useApiMock.mockImplementation(dbApi(true))
      const w = mount(QuestionDetail)
      await flushPromises()
      expect(w.find('[data-test="db-button"] .q-badge').text()).toBe('1')
      expect(w.find('[data-test="session-db-chips"]').text()).toContain('운영')
      ;(w.vm as any).pickedDb.splice(0)
      await sendText(w, 'DB 없이 다시')
      const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
      expect(call![1].body.dbConnectionIds).toEqual([])
      w.unmount()
    })

    const staleDbApi = (url: string) =>
      url === '/api/questions/3'
        ? Promise.resolve({ ...dbDetail, dbConnectionIds: [7, 8], dbConnections: [{ id: 7, name: '운영', dbType: 'MYSQL' }] })
        : dbApi(true)(url)

    it('비활성·삭제돼 목록에 없는 선택 id도 시딩되고 그대로 유지해 보낸다(서버는 이미 있던 id를 검증하지 않는다)', async () => {
      useApiMock.mockImplementation(staleDbApi)
      const w = mount(QuestionDetail)
      await flushPromises()
      expect((w.vm as any).pickedDb).toEqual([7, 8]) // 8은 목록에 없다
      await sendText(w, '질문')
      const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
      expect(call![1].body.dbConnectionIds).toEqual([7, 8])
      w.unmount()
    })

    it('피커에서 "사용할 수 없는 연결"을 해제하면 그 id가 빠진 채로 보낸다', async () => {
      useApiMock.mockImplementation(staleDbApi)
      const w = mount(QuestionDetail)
      await flushPromises()
      ;(w.vm as any).pickedDb.splice(1, 1) // 피커 v-model 갱신과 같은 효과(메뉴 대신 직접 갱신 — 위 선례)
      await sendText(w, '질문')
      const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
      expect(call![1].body.dbConnectionIds).toEqual([7])
      w.unmount()
    })

    it('기능이 꺼져 있으면 DB 버튼을 숨기고 바디에 dbConnectionIds를 싣지 않는다', async () => {
      useApiMock.mockImplementation(dbApi(false))
      const w = mount(QuestionDetail)
      await flushPromises()
      expect(w.find('[data-test="db-button"]').exists()).toBe(false)
      await sendText(w, '그냥 질문')
      const call = useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
      expect(call![1].body).not.toHaveProperty('dbConnectionIds')
      w.unmount()
    })
  })

  // 스펙 2026-10-22 §7.3 — sendText·stubApi·detailWith를 쓰려고 이 describe 안에 둔다
  describe('참조 repo', () => {
    // 카탈로그(GET /api/repo-catalog): 1=대상으로 쓰는 레포 · 2=옛앱 · 3=공통 UI · 4=배치 서버 · 5=인증 서버
    const catalog = [
      { id: 1, alias: 'netis7.0', host: 'github', defaultBranch: 'main' },
      { id: 2, alias: '옛앱', host: 'gitlab', defaultBranch: 'release/6.5' },
      { id: 3, alias: '공통 UI', host: 'github', defaultBranch: 'main' },
      { id: 4, alias: '배치 서버', host: 'gitlab', defaultBranch: 'master' },
      { id: 5, alias: '인증 서버', host: 'github', defaultBranch: 'develop' },
    ]
    // 방금 고른 참조 — 화면 초안에는 커밋·시각·사유 없이 {catalogId, alias, branch}만 들어간다
    const REF_BATCH = { catalogId: 4, alias: '배치 서버', branch: 'master' }

    type Wrapper = ReturnType<typeof mount>

    const askCall = () => useApiMock.mock.calls.find((c) => c[0] === '/api/questions/3/ask')
    const catalogCalls = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/repo-catalog').length
    /** a가 b보다 문서에서 앞에 있는가 */
    const precedes = (a: Element, b: Element) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)
    const refIds = (vm: any) => (vm.pickedRefs as Array<{ catalogId: number }>).map((r) => r.catalogId)

    /** stubApi 위에 /api/repo-catalog 응답만 덮는다 */
    function stubCatalog(onCatalog: () => Promise<unknown>, d: Record<string, unknown> = detail) {
      stubApi(d)
      const base = useApiMock.getMockImplementation()!
      useApiMock.mockImplementation((url: string, ...rest: unknown[]) =>
        url === '/api/repo-catalog' ? onCatalog() : base(url, ...rest),
      )
    }

    // QMenu는 열린 뒤 transition-duration(기본 300ms)이 지나야 show 이벤트를 내고, 닫을 때 포털을 지우는 것도 같다
    const menuTransition = () => new Promise((r) => setTimeout(r, 350))
    /** 같은 버튼을 누르면 열리고, 다시 누르면 닫힌다(QMenu의 앵커 토글) */
    async function toggleRefMenu(w: Wrapper) {
      await w.find('[data-test="ref-change"]').trigger('click')
      await menuTransition()
      await flushPromises()
    }
    /** 열린 메뉴 안의 편집용 칩 줄 — 읽기 전용 칩 줄은 헤더 아래에 따로 있다 */
    const menuChips = (w: Wrapper) => w.findAllComponents(RefRepoChips).find((c) => c.props('editable'))
    const menuEl = () => document.body.querySelector('.q-menu')

    afterEach(() => {
      document.querySelectorAll('.q-menu').forEach((n) => n.remove()) // 열어 둔 메뉴 포털이 다음 테스트로 새지 않게
    })

    it('헤더 아래 참조 칩 줄에 고정 커밋·받는 중·실패 상태를 보이고, DB 칩 줄보다 앞에 놓인다', async () => {
      stubApi(detailWith({ dbConnections: [{ id: 7, name: '운영', dbType: 'MYSQL' }] }))
      const w = mount(QuestionDetail)
      await flushPromises()
      const strip = w.find('[data-test="session-ref-chips"]')
      expect(strip.exists()).toBe(true)
      expect(strip.text()).toContain('참조 repo · 읽기 전용 · 최대 3개')
      const chips = strip.findAll('[data-test="ref-chip"]')
      expect(chips.map((c) => c.attributes('data-state'))).toEqual(['pinned', 'pending', 'failed'])
      expect(chips[0]!.text()).toContain('📚 옛앱 · release/6.5@a1b2c3d') // 고정 커밋 앞 7자
      expect(chips[1]!.text()).toContain('📚 공통 UI · main')
      expect(chips[1]!.text()).toContain('다음 답변 때 받음')
      expect(chips[2]!.text()).toContain('⚠')
      // 칩 줄은 표시 전용 — 편집은 "참조 변경" 메뉴에서만
      expect(strip.findAll('.q-chip__icon--remove')).toHaveLength(0)
      expect(strip.find('[data-test="ref-add"]').exists()).toBe(false)
      // 헤더 아래, DB 칩 줄 위
      expect(precedes(w.find('.conv-header').element, strip.element)).toBe(true)
      expect(precedes(strip.element, w.find('[data-test="session-db-chips"]').element)).toBe(true)
      w.unmount()
    })

    it('참조가 없으면(빈 목록이거나 구버전 백엔드가 필드를 안 주면) 칩 줄은 "참조 repo 없음"이다', async () => {
      for (const refRepos of [[], undefined]) {
        stubApi(detailWith({ refRepos }))
        const w = mount(QuestionDetail)
        await flushPromises()
        const strip = w.find('[data-test="session-ref-chips"]')
        expect(strip.exists()).toBe(true) // 질문 대화 화면에는 항상 그린다
        expect(strip.text()).toContain('참조 repo 없음')
        expect(strip.findAll('[data-test="ref-chip"]')).toHaveLength(0)
        w.unmount()
      }
    })

    it('✎ 참조 변경 버튼은 입력 대기 중에 열려 있고, 좁은 화면에서도 라벨을 줄이지 않는다', async () => {
      const w = mount(QuestionDetail)
      await flushPromises()
      const btn = w.find('[data-test="ref-change"]')
      expect(btn.text()).toBe('✎ 참조 변경')
      expect(btn.attributes('aria-label')).toBe('참조 변경') // 스크린리더에는 장식 글리프(✎) 없이
      expect(btn.attributes('disabled')).toBeUndefined()
      expect(w.find('[data-test="session-ref-chips"]').find('[data-test="ref-change"]').exists()).toBe(true) // 칩 줄 안
      w.unmount()

      // MCP·DB 버튼은 입력창 툴바라 xs에서 아이콘만 남지만, 이 버튼은 칩 줄에 있어 라벨이 그대로다
      await setViewportWidth(390)
      try {
        const narrow = mount(QuestionDetail)
        await flushPromises()
        expect(narrow.find('[data-test="open-drawer"]').exists()).toBe(true) // 좁은 화면 분기 진입 확인
        expect(narrow.find('[data-test="ref-change"]').text()).toBe('✎ 참조 변경')
        narrow.unmount()
      } finally {
        await setViewportWidth(1024)
      }
    })

    it('참조를 바꿔 보내면 ask 바디에 refRepos가 실린다 (초안 순서 그대로, 커밋·시각 없이)', async () => {
      const w = mount(QuestionDetail)
      await flushPromises()
      // 메뉴(RefRepoAddForm) 대신 직접 갱신 — MCP·DB 테스트와 같은 선례. 3개 상한은 칩 줄(RefRepoChips max)이 걸고 페이지는 초안을 그대로 싣는다.
      ;(w.vm as any).pickedRefs.push({ ...REF_BATCH })
      await sendText(w, '배치 서버도 같이 봐줘')
      expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/ask', {
        method: 'POST',
        body: {
          answer: '배치 서버도 같이 봐줘',
          replyToSeq: 1,
          model: 'claude-sonnet-5',
          effort: 'medium',
          mcpCatalogIds: [5, 9],
          refRepos: [
            { repoCatalogId: 2, branch: 'release/6.5' },
            { repoCatalogId: 3, branch: 'main' },
            { repoCatalogId: 5, branch: 'develop' },
            { repoCatalogId: 4, branch: 'master' },
          ],
        },
      })
      w.unmount()
    })

    it('첨부와 함께 보내도 meta JSON에 refRepos가 실린다 (multipart)', async () => {
      const w = mount(QuestionDetail)
      await flushPromises()
      ;(w.vm as any).pickedRefs.splice(1, 2) // 옛앱만 남긴다
      w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['hello'], '메모.txt', { type: 'text/plain' })])
      await flushPromises()
      await sendText(w, '이 메모랑 옛앱만 봐줘')
      const body = askCall()![1].body as FormData
      expect(body).toBeInstanceOf(FormData)
      expect(JSON.parse(await (body.get('meta') as Blob).text())).toEqual({
        answer: '이 메모랑 옛앱만 봐줘',
        replyToSeq: 1,
        model: 'claude-sonnet-5',
        effort: 'medium',
        mcpCatalogIds: [5, 9],
        refRepos: [{ repoCatalogId: 2, branch: 'release/6.5' }],
      })
      w.unmount()
    })

    it('참조를 바꾸지 않으면 ask 바디는 지금과 같다 — refRepos 필드가 없다', async () => {
      const w = mount(QuestionDetail)
      await flushPromises()
      await sendText(w, '리프레시 토큰은요?')
      expect(useApiMock).toHaveBeenCalledWith('/api/questions/3/ask', {
        method: 'POST',
        body: {
          answer: '리프레시 토큰은요?',
          replyToSeq: 1,
          model: 'claude-sonnet-5',
          effort: 'medium',
          mcpCatalogIds: [5, 9],
        },
      })
      expect(askCall()![1].body).not.toHaveProperty('refRepos') // toHaveBeenCalledWith는 undefined 필드를 못 거른다
      w.unmount()
    })

    it('참조가 없는 세션도 refRepos를 싣지 않는다 (빈 목록·필드 없음 모두)', async () => {
      for (const refRepos of [[], undefined]) {
        useApiMock.mockReset()
        stubApi(detailWith({ refRepos }))
        const w = mount(QuestionDetail)
        await flushPromises()
        await sendText(w, '참조 없이 질문')
        expect(askCall()).toBeTruthy()
        expect(askCall()![1].body).not.toHaveProperty('refRepos')
        w.unmount()
      }
    })

    it('참조를 뺐다가 같은 짝으로 되돌리면(순서가 달라도) 변경이 아니라서 필드를 생략한다', async () => {
      const w = mount(QuestionDetail)
      await flushPromises()
      const vm = w.vm as any
      const [pinned, pending, failed] = vm.pickedRefs.splice(0)
      expect(vm.pickedRefs).toEqual([])
      vm.pickedRefs.push(failed, pinned, pending)
      await sendText(w, '순서만 바뀌었어')
      expect(askCall()).toBeTruthy()
      expect(askCall()![1].body).not.toHaveProperty('refRepos')
      w.unmount()
    })

    it('전부 해제하면 빈 배열을 보낸다 (null=유지가 아니라 []=전부 해제)', async () => {
      const w = mount(QuestionDetail)
      await flushPromises()
      ;(w.vm as any).pickedRefs.splice(0)
      await sendText(w, '참조 없이 답해줘')
      expect(askCall()![1].body.refRepos).toEqual([])
      w.unmount()
    })

    it('모델이 없어 초안을 시딩하지 못한 세션은 참조를 싣지 않는다 (빈 초안으로 서버 참조를 전부 해제하지 않는다)', async () => {
      stubApi(detailWith({ model: null }))
      const w = mount(QuestionDetail)
      await flushPromises()
      expect((w.vm as any).pickedRefs).toEqual([]) // 시딩 조건(model)을 못 채웠다
      await sendText(w, '모델 없는 옛 세션')
      expect(askCall()).toBeTruthy()
      expect(askCall()![1].body).not.toHaveProperty('refRepos')
      w.unmount()
    })

    it('답변 중(RUNNING)에는 참조 변경 버튼이 잠긴다 (칩 줄 표시는 그대로)', async () => {
      const w = mount(QuestionDetail)
      await flushPromises()
      FakeEventSource.last().emit('status', 'RUNNING')
      await flushPromises()
      expect(w.find('[data-test="ref-change"]').attributes('disabled')).toBeDefined()
      expect(w.find('[data-test="session-ref-chips"]').findAll('[data-test="ref-chip"]')).toHaveLength(3)
      w.unmount()
    })

    it('상세 폴링이 와도 보내기 전 참조 초안이 유지되고, 칩 줄은 서버 값을 따라간다 (1회 시딩)', async () => {
      const originalUseTaskPolling = (globalThis as any).useTaskPolling
      let capturedRefresh: (() => Promise<void>) | undefined
      ;(globalThis as any).useTaskPolling = (fetcher: () => Promise<unknown>) => {
        const handle = originalUseTaskPolling(fetcher)
        if (!capturedRefresh) capturedRefresh = handle.refresh
        return handle
      }
      try {
        const w = mount(QuestionDetail)
        await flushPromises()
        const vm = w.vm as any
        vm.pickedRefs.push({ ...REF_BATCH })
        // 다음 폴링: 대기 중이던 참조를 서버가 받아 커밋을 고정했다
        const nowPinned = { ...REF_PENDING, sha: 'f0e1d2c3b4a5968778695a4b3c2d1e0f12345678', fetchedAt: '2026-10-22T06:00:00Z' }
        stubApi(detailWith({ refRepos: [REF_PINNED, nowPinned, REF_FAILED] }))
        await capturedRefresh!()
        await flushPromises()
        expect(refIds(vm)).toEqual([2, 3, 5, 4]) // 초안은 서버 값으로 다시 맞춰지지 않는다
        expect(vm.pickedRefs[1].sha).toBeNull()
        const states = w
          .find('[data-test="session-ref-chips"]')
          .findAll('[data-test="ref-chip"]')
          .map((c) => c.attributes('data-state'))
        expect(states).toEqual(['pinned', 'pinned', 'failed']) // 칩 줄은 서버 값(고정됨)
        w.unmount()
      } finally {
        ;(globalThis as any).useTaskPolling = originalUseTaskPolling
      }
    })

    it('메뉴 칩은 초안의 구성에 서버가 아는 상태를 입힌다 — 폴링으로 고정된 참조가 메뉴에 "다음 답변 때 받음"으로 남지 않는다', async () => {
      const originalUseTaskPolling = (globalThis as any).useTaskPolling
      let capturedRefresh: (() => Promise<void>) | undefined
      ;(globalThis as any).useTaskPolling = (fetcher: () => Promise<unknown>) => {
        const handle = originalUseTaskPolling(fetcher)
        if (!capturedRefresh) capturedRefresh = handle.refresh
        return handle
      }
      try {
        // 새 세션 직후: 참조가 아직 안 받아진 상태로 시드된다
        stubCatalog(() => Promise.resolve(catalog), detailWith({ refRepos: [REF_PENDING] }))
        const w = mount(QuestionDetail)
        await flushPromises()
        const vm = w.vm as any
        vm.pickedRefs.push({ ...REF_BATCH }) // 서버가 모르는, 방금 더한 참조
        // 첫 답변이 와서 서버가 대기 중이던 참조를 고정했다
        const nowPinned = { ...REF_PENDING, sha: 'f0e1d2c3b4a5968778695a4b3c2d1e0f12345678', fetchedAt: '2026-10-22T06:00:00Z' }
        stubCatalog(() => Promise.resolve(catalog), detailWith({ refRepos: [nowPinned] }))
        await capturedRefresh!()
        await flushPromises()
        await toggleRefMenu(w)
        const states = Array.from(menuEl()!.querySelectorAll('[data-test="ref-chip"]')).map((c) => c.getAttribute('data-state'))
        expect(states).toEqual(['pinned', 'pending']) // 고정된 것은 고정으로, 서버에 없는 새 참조는 대기로
        expect(menuEl()!.textContent).toContain('@f0e1d2c') // 커밋 앞 7자
        expect(vm.pickedRefs[0].sha).toBeNull() // 초안 자체는 시드 때 그대로 — 입히는 것은 표시뿐이다
        w.unmount()
      } finally {
        ;(globalThis as any).useTaskPolling = originalUseTaskPolling
      }
    })

    it('참조 변경 메뉴를 처음 열 때 카탈로그를 한 번만 불러온다 — 페이지를 여는 시점에는 호출이 늘지 않는다', async () => {
      stubCatalog(() => Promise.resolve(catalog))
      const w = mount(QuestionDetail)
      await flushPromises()
      expect(catalogCalls()).toBe(0)
      await toggleRefMenu(w)
      expect(menuEl()).not.toBeNull()
      expect(catalogCalls()).toBe(1)
      await toggleRefMenu(w) // 닫는다
      expect(menuEl()).toBeNull()
      await toggleRefMenu(w) // 다시 열어도
      expect(menuEl()).not.toBeNull()
      expect(catalogCalls()).toBe(1) // 다시 부르지 않는다
      w.unmount()
    })

    it('카탈로그 조회가 실패하면 알리고, 다음에 열 때 다시 시도한다', async () => {
      let fail = true
      stubCatalog(() =>
        fail ? Promise.reject({ statusCode: 500, data: { message: '레포 카탈로그 서버 오류' } }) : Promise.resolve(catalog),
      )
      const w = mount(QuestionDetail)
      await flushPromises()
      await toggleRefMenu(w)
      expect(catalogCalls()).toBe(1)
      // 서버 메시지를 담은 negative notify (다른 테스트가 남긴 알림과 섞이지 않게 이 메시지를 가진 알림을 집는다)
      const note = Array.from(document.querySelectorAll('.q-notification')).find((n) => n.textContent?.includes('레포 카탈로그 서버 오류'))
      expect(note).toBeTruthy()
      expect(note!.classList.contains('bg-negative')).toBe(true)

      fail = false
      await toggleRefMenu(w) // 닫고
      await toggleRefMenu(w) // 다시 연다 → 재시도
      expect(catalogCalls()).toBe(2)
      w.unmount()
    })

    it('메뉴는 초안을 보여 주고, ✕로 빼면 초안만 바뀐다 — 칩 줄은 서버 값 그대로', async () => {
      stubCatalog(() => Promise.resolve(catalog))
      const w = mount(QuestionDetail)
      await flushPromises()
      await toggleRefMenu(w)
      const chips = menuChips(w)!
      expect(chips.props('items').map((r: { catalogId: number }) => r.catalogId)).toEqual([2, 3, 5])
      expect(chips.props('editable')).toBe(true)

      // 메뉴의 두 번째 칩(공통 UI)의 ✕
      const removeIcons = menuEl()!.querySelectorAll<HTMLElement>('[data-test="ref-chip"] .q-chip__icon--remove')
      expect(removeIcons).toHaveLength(3)
      removeIcons[1]!.click()
      await flushPromises()
      expect(refIds(w.vm)).toEqual([2, 5])
      expect(menuEl()!.querySelectorAll('[data-test="ref-chip"]')).toHaveLength(2)
      expect(w.find('[data-test="session-ref-chips"]').findAll('[data-test="ref-chip"]')).toHaveLength(3) // 서버 값은 그대로
      w.unmount()
    })

    it('＋ 참조 추가는 추가 폼을 펼치고(후보는 대상 레포·이미 고른 것을 뺀다), 추가하면 초안에 더하고 접는다', async () => {
      stubCatalog(() => Promise.resolve(catalog), detailWith({ repoCatalogId: 1, refRepos: [REF_PINNED, REF_PENDING] }))
      const w = mount(QuestionDetail)
      await flushPromises()
      await toggleRefMenu(w)
      expect(w.findComponent(RefRepoAddForm).exists()).toBe(false) // 눌러야 펼쳐진다

      menuEl()!.querySelector<HTMLElement>('[data-test="ref-add"]')!.click()
      await flushPromises()
      const form = w.findComponent(RefRepoAddForm)
      expect(form.exists()).toBe(true)
      expect(form.props('catalog')).toEqual(catalog)
      expect(form.props('excludeIds')).toEqual([1, 2, 3]) // 대상(헤더 📁) + 이미 고른 참조

      // ＋ 참조 추가를 한 번 더 누르면 접힌다
      menuEl()!.querySelector<HTMLElement>('[data-test="ref-add"]')!.click()
      await flushPromises()
      expect(w.findComponent(RefRepoAddForm).exists()).toBe(false)

      menuEl()!.querySelector<HTMLElement>('[data-test="ref-add"]')!.click()
      await flushPromises()
      w.findComponent(RefRepoAddForm).vm.$emit('add', { ...REF_BATCH })
      await flushPromises()
      expect(refIds(w.vm)).toEqual([2, 3, 4])
      expect(w.findComponent(RefRepoAddForm).exists()).toBe(false) // 추가하면 접는다
      expect(menuEl()!.querySelectorAll('[data-test="ref-chip"]')).toHaveLength(3)
      expect(menuEl()!.querySelector('[data-test="ref-add"]')).toBeNull() // 3개면 추가 칩이 사라진다
      w.unmount()
    })

    it('메뉴를 닫았다 다시 열면 추가 폼은 접혀 있다', async () => {
      stubCatalog(() => Promise.resolve(catalog), detailWith({ refRepos: [REF_PINNED] }))
      const w = mount(QuestionDetail)
      await flushPromises()
      await toggleRefMenu(w)
      menuEl()!.querySelector<HTMLElement>('[data-test="ref-add"]')!.click()
      await flushPromises()
      expect(w.findComponent(RefRepoAddForm).exists()).toBe(true)
      await toggleRefMenu(w) // 닫는다
      await toggleRefMenu(w) // 다시 연다
      expect(menuEl()).not.toBeNull()
      expect(w.findComponent(RefRepoAddForm).exists()).toBe(false)
      w.unmount()
    })

    it('메뉴에서 참조를 빼고 더한 뒤 보내면 그 초안이 refRepos로 나간다', async () => {
      stubCatalog(() => Promise.resolve(catalog), detailWith({ repoCatalogId: 1, refRepos: [REF_PINNED, REF_PENDING] }))
      const w = mount(QuestionDetail)
      await flushPromises()
      await toggleRefMenu(w)
      menuEl()!.querySelectorAll<HTMLElement>('[data-test="ref-chip"] .q-chip__icon--remove')[1]!.click() // 공통 UI 빼기
      await flushPromises()
      menuEl()!.querySelector<HTMLElement>('[data-test="ref-add"]')!.click()
      await flushPromises()
      w.findComponent(RefRepoAddForm).vm.$emit('add', { ...REF_BATCH }) // 배치 서버 더하기
      await flushPromises()
      await sendText(w, '배치 서버로 바꿔서 봐줘')
      expect(askCall()![1].body.refRepos).toEqual([
        { repoCatalogId: 2, branch: 'release/6.5' },
        { repoCatalogId: 4, branch: 'master' },
      ])
      w.unmount()
    })
  })
})
