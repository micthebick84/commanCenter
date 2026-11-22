import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { QFile } from 'quasar'
import QuestionDetail from '../pages/questions/[id].vue'
import SessionSuggestCard from '../components/chat/SessionSuggestCard.vue'
import { SUGGEST_FOLLOW_UP } from '../composables/sessionSuggest'
import { authStub, useApiMock } from './mocks/nuxt'

// 마운트 방식은 questions-detail.spec.ts와 같다 — useRoute/navigateTo는 Nuxt 자동 임포트라 전역 주입.
Object.assign(globalThis, {
  navigateTo: vi.fn(),
  useRoute: () => ({ params: { id: '3' } }),
})

const REF_PINNED = { catalogId: 2, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678', fetchedAt: null, error: null }

const dbSuggestion = {
  id: 1,
  turnSeq: 1,
  kind: 'DB',
  status: 'PENDING',
  reason: '실제 값을 봐야 합니다',
  targets: ['orders'],
  dbConnectionId: 4,
  repoCatalogId: null,
  repoAlias: null,
  defaultBranch: null,
  dbOptions: [{ id: 4, name: '개발 DB', dbType: 'POSTGRESQL' }],
  dbSlotsLeft: 2,
  refSlotsLeft: 2,
}
const refSuggestion = {
  ...dbSuggestion,
  id: 2,
  kind: 'REF_REPO',
  reason: '공통 모듈이 있습니다',
  targets: [],
  dbConnectionId: null,
  repoCatalogId: 11,
  repoAlias: '공통 UI',
  defaultBranch: 'main',
  dbOptions: [],
}

// 헤더 폴링(GET /api/questions/3)과 패널 스냅샷(같은 URL)이 모두 이 응답을 쓴다.
const detail = {
  id: 3,
  title: '주문 상태 확인',
  githubRepo: 'acme/widgets',
  githubBranch: 'main',
  status: '입력대기',
  statusName: 'AWAITING_INPUT',
  model: 'claude-sonnet-5',
  effort: 'medium',
  kind: 'QUESTION',
  repoCatalogId: 1,
  mcpCatalogIds: [5],
  dbConnectionIds: [3],
  dbConnections: [{ id: 3, name: '운영', dbType: 'MYSQL' }],
  attachments: [],
  refRepos: [REF_PINNED],
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '주문 상태는 코드로 보면 이렇습니다' }],
  plan: null,
  suggestions: [dbSuggestion, refSuggestion],
}

let askResult: () => Promise<unknown> = () => Promise.resolve(null)
function stubApi() {
  useApiMock.mockImplementation((url: string) => {
    if (url === '/api/questions/3') return Promise.resolve({ ...detail })
    if (url === '/api/questions/3/ask') return askResult()
    if (url === '/api/db-connections?repoCatalogId=1') {
      return Promise.resolve({
        enabled: true,
        items: [{ id: 3, scope: 'REPO', repoCatalogId: 1, name: '운영', dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', enabled: true, mine: false }],
      })
    }
    if (url === '/api/usage/claude') return Promise.resolve({ limits: [] })
    return Promise.resolve(null)
  })
}

type Picks = { dbConnectionIds: number[]; refs: Array<{ repoCatalogId: number; branch: string }>; suggestionIds: number[] }
async function accept(w: ReturnType<typeof mount>, picks: Picks) {
  w.findComponent(SessionSuggestCard).vm.$emit('accept', picks)
  await flushPromises()
}
const askCall = () => useApiMock.mock.calls.find((c: unknown[]) => c[0] === '/api/questions/3/ask')
const refIds = (vm: unknown) => (vm as { pickedRefs: Array<{ catalogId: number }> }).pickedRefs.map((r: { catalogId: number }) => r.catalogId)

describe('pages/questions/[id] — 대화 중 제안 받아들이기 (스펙 2026-11-22 §5.3·§8)', () => {
  beforeEach(() => {
    authStub.accessToken = 'jwt'
    askResult = () => Promise.resolve(null)
    stubApi()
  })

  it('받아들이면 초안의 DB에 고른 연결을 더해 보내고 초안도 맞춘다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    expect(w.find('[data-test="suggest-card"]').exists()).toBe(true)
    await accept(w, { dbConnectionIds: [4], refs: [], suggestionIds: [1] })
    expect(askCall()![1]).toEqual({
      method: 'POST',
      body: {
        answer: SUGGEST_FOLLOW_UP.db,
        replyToSeq: 1,
        model: 'claude-sonnet-5',
        effort: 'medium',
        mcpCatalogIds: [5],
        dbConnectionIds: [3, 4],
        acceptSuggestionIds: [1],
      },
    })
    expect((w.vm as unknown as { pickedDb: number[] }).pickedDb).toEqual([3, 4])
    w.unmount()
  })

  it('참조를 받아들이면 refRepos에 더해 보낸다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    await accept(w, { dbConnectionIds: [], refs: [{ repoCatalogId: 11, branch: 'main' }], suggestionIds: [2] })
    expect(askCall()![1].body).toEqual({
      answer: SUGGEST_FOLLOW_UP.ref,
      replyToSeq: 1,
      model: 'claude-sonnet-5',
      effort: 'medium',
      mcpCatalogIds: [5],
      dbConnectionIds: [3],
      refRepos: [
        { repoCatalogId: 2, branch: 'release/6.5' },
        { repoCatalogId: 11, branch: 'main' },
      ],
      acceptSuggestionIds: [2],
    })
    // 참조 변경 메뉴의 초안도 보낸 것과 같아진다 — 새 참조는 커밋 없는 대기 칩
    const vm = w.vm as unknown as { pickedRefs: Array<{ catalogId: number; alias: string; branch: string; sha?: string | null }> }
    expect(refIds(vm)).toEqual([2, 11])
    expect(vm.pickedRefs[1]).toEqual({ catalogId: 11, alias: '공통 UI', branch: 'main' })
    w.unmount()
  })

  it('받아들여도 대기 중인 첨부는 그대로 남는다', async () => {
    const w = mount(QuestionDetail)
    await flushPromises()
    w.findComponent(QFile).vm.$emit('update:modelValue', [new File(['hello'], '메모.txt', { type: 'text/plain' })])
    await flushPromises()
    await w.find('textarea').setValue('쓰던 질문')
    await accept(w, { dbConnectionIds: [4], refs: [], suggestionIds: [1] })
    const body = askCall()![1].body
    expect(body).not.toBeInstanceOf(FormData)
    expect(body.answer).toBe(SUGGEST_FOLLOW_UP.db)
    expect(w.find('[data-test="composer-files"]').text()).toContain('메모.txt')
    expect((w.find('textarea').element as HTMLTextAreaElement).value).toBe('쓰던 질문')
    w.unmount()
  })

  it('전송이 실패하면 초안을 바꾸지 않는다', async () => {
    askResult = () => Promise.reject({ statusCode: 400, data: { message: '연결을 찾을 수 없습니다' } })
    const w = mount(QuestionDetail)
    await flushPromises()
    await accept(w, { dbConnectionIds: [4], refs: [{ repoCatalogId: 11, branch: 'main' }], suggestionIds: [1, 2] })
    expect(askCall()).toBeTruthy()
    const vm = w.vm as unknown as { pickedDb: number[] }
    expect(vm.pickedDb).toEqual([3])
    expect(refIds(vm)).toEqual([2])
    expect(w.find('[data-test="suggest-card"]').exists()).toBe(true) // 카드는 그대로 남는다
    expect(document.body.textContent).toContain('연결을 찾을 수 없습니다')
    w.unmount()
  })
})
