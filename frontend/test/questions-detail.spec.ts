import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { QFile } from 'quasar'
import QuestionDetail from '../pages/questions/[id].vue'
import ModelEffortPicker from '../components/chat/ModelEffortPicker.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { FakeEventSource } from './mocks/eventsource'
import { setViewportWidth } from './mocks/screen'

// useRoute/navigateTo는 Nuxt 자동 임포트 — setup.ts에 없어 전역 주입.
const navigateToMock = vi.fn()
Object.assign(globalThis, {
  navigateTo: navigateToMock,
  useRoute: () => ({ params: { id: '3' } }),
})

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
})
