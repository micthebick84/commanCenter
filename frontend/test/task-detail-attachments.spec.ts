import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { useApiMock } from './mocks/nuxt'

// useRoute는 Nuxt 자동 임포트 — test/setup.ts의 다른 auto-import처럼 전역 스텁으로 제공
// (vi.mock('vue-router')는 SFC가 명시 임포트할 때만 듣는다)
;(globalThis as any).useRoute = () => ({ params: { id: '42' } })

const PageWrapper = defineComponent({
  setup() {
    return () =>
      h(QLayout, { view: 'hHh lpR fFf' }, {
        default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }),
      })
  },
})

// q-breadcrumbs-el의 :to가 router-link를 요구 — 라우터 없이 마운트하므로 스텁.
// DesignReviewCard는 이 스펙만 [id].vue를 마운트하므로(다른 파일과 공유되지 않음) 여기서
// 발생하는 "Failed to resolve component" 소음은 이 스펙이 새로 만든 것 — stub으로 제거한다.
const mountOpts = {
  attachTo: document.body,
  global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } },
}

const taskFixture = {
  id: 42,
  githubRepo: 'acme/widgets',
  repoAlias: null,
  githubBranch: 'main',
  title: '제목',
  description: '설명',
  status: 'AWAITING_APPROVAL',
  statusLabel: '승인대기',
  requesterId: 'user1',
  retryCount: 0,
  maxRetry: 3,
  failureReason: null,
  mcpsExtra: [],
  envVars: [],
  interviewSessionId: null,
  createdAt: '2026-08-16T00:00:00Z',
  updatedAt: '2026-08-16T00:00:00Z',
  model: 'claude-opus-5',
  effort: 'high',
  designRequested: false,
  analysis: null,
  design: null,
  implementation: null,
  deployment: null,
  attachments: [
    {
      id: 7,
      fileName: '요구사항.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1536,
      createdAt: '2026-08-16T00:00:00Z',
    },
    // formatSize의 MB/B 분기 커버리지 — 1536바이트 하나만으로는 KB 분기만 지나가고
    // MB(everyday path — application.yml 20MB 한도)와 B 분기는 뮤테이션이 살아남는다.
    {
      id: 8,
      fileName: '큰파일.zip',
      contentType: 'application/zip',
      sizeBytes: 5 * 1024 * 1024,
      createdAt: '2026-08-16T00:00:00Z',
    },
    {
      id: 9,
      fileName: '작은파일.txt',
      contentType: 'text/plain',
      sizeBytes: 512,
      createdAt: '2026-08-16T00:00:00Z',
    },
  ],
}

// Task 9(tasks-form-attachments.spec.ts)의 구조 선례를 따른다: 각 테스트 바디가 아니라
// 모듈 레벨 afterEach에 정리 로직을 모아, 실패한 테스트가 다음 테스트에 마운트/스파이를
// 흘리지 않게 한다.
let currentWrapper: ReturnType<typeof mount> | null = null
let clickSpy: ReturnType<typeof vi.spyOn> | null = null

// URL.createObjectURL/revokeObjectURL은 Object.assign으로 갈아끼우므로(스파이가 아니라
// 대입) 반드시 원본으로 되돌려야 한다. 안 되돌리면 Blob 케이스가 앞 테스트의
// createObjectURL 스파이를 그대로 물려받아, 다운로드 경로를 지키는 이 스펙 자체가
// 다른 테스트의 전역 오염에 의존하게 된다. (jsdom에선 원본이 undefined일 수 있고,
// undefined로 되돌리는 것이 바로 "원래 상태"다.)
const origCreateObjectURL = URL.createObjectURL
const origRevokeObjectURL = URL.revokeObjectURL

afterEach(() => {
  currentWrapper?.unmount()
  currentWrapper = null
  clickSpy?.mockRestore()
  clickSpy = null
  Object.assign(URL, {
    createObjectURL: origCreateObjectURL,
    revokeObjectURL: origRevokeObjectURL,
  })
})

async function mountPage(task: unknown = taskFixture) {
  useApiMock.mockResolvedValue(task)
  const w = mount(PageWrapper, mountOpts)
  currentWrapper = w
  await flushPromises()
  return w
}

// a.click()을 가로채 실제 네비게이션 없이 생성된 앵커(download/href)를 검사할 수 있게 한다.
// (앵커는 실제로 DOM에 존재하지만 즉시 다시 제거되므로 - B2 수정 이후 - click 호출 시점의
// this를 통해 관찰한다.)
// B2 가드: appendChild/remove가 통째로 삭제돼도 createObjectURL/revokeObjectURL 호출
// 여부만으로는 안 걸린다 — click 시점에 DOM에 연결돼 있었는지(isConnected/parentNode)와
// revoke 호출 횟수를 그 순간에 동기적으로 기록해 둔다. click 콜백 내부는 setTimeout(0)
// 매크로태스크와 절대 교차할 수 없으므로 이 값들은 나중에(flushPromises 이후) 읽어도
// CPU 경합 등으로 revoke/remove가 먼저 실행돼 버리는 레이스가 없다 — 반면 anchor()의
// parentNode/isConnected를 나중에 직접 읽으면 그 사이 setTimeout이 already 실행됐을 수
// 있어 레이스가 생긴다(실제로 Task 11 게이트에서 관찰됨).
function spyAnchorClick(revokeObjectURL: ReturnType<typeof vi.fn>): {
  anchor: () => HTMLAnchorElement | null
  wasConnectedAtClick: () => boolean
  parentAtClick: () => string | null
  revokeCallsAtClick: () => number
} {
  let lastAnchor: HTMLAnchorElement | null = null
  let connectedAtClick = false
  let parentAtClick: string | null = null
  let revokeCallsAtClick = 0
  clickSpy = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(function (this: HTMLAnchorElement) {
      lastAnchor = this
      connectedAtClick = this.isConnected
      parentAtClick = this.parentNode?.nodeName ?? null
      revokeCallsAtClick = revokeObjectURL.mock.calls.length
    })
  return {
    anchor: () => lastAnchor,
    wasConnectedAtClick: () => connectedAtClick,
    parentAtClick: () => parentAtClick,
    revokeCallsAtClick: () => revokeCallsAtClick,
  }
}

function findAttachmentChip(w: ReturnType<typeof mount>, fileName = '요구사항.pdf') {
  return w.findAll('.q-chip').find((c) => c.text().includes(fileName))
}

describe('task detail — attachments', () => {
  it('첨부 파일명과 크기를 렌더링한다 (B/KB/MB 세 분기 모두)', async () => {
    const w = await mountPage()

    expect(w.text()).toContain('첨부파일')
    expect(w.text()).toContain('요구사항.pdf')
    expect(w.text()).toContain('2KB')
    // MB 분기 — 20MB 한도 하의 일상적 경로. (bytes/1024).toFixed(3) 같은 오분기 뮤테이션은
    // '5120.000MB'를 만들어 이 assertion에서만 걸린다.
    expect(w.text()).toContain('5.0MB')
    // B 분기. '${bytes} bytes!!' 같은 오분기 뮤테이션은 여기서만 걸린다.
    expect(w.text()).toContain('512B')
  })

  it('클릭하면 useApi로 blob 다운로드를 호출하고 메타 fileName + 응답 바이트로 저장한다', async () => {
    const w = await mountPage()

    const createObjectURL = vi.fn(() => 'blob:mock')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const { anchor, wasConnectedAtClick, parentAtClick, revokeCallsAtClick } =
      spyAnchorClick(revokeObjectURL)

    useApiMock.mockClear()
    const blob = new Blob(['pdf'])
    useApiMock.mockResolvedValue(blob)
    const chip = findAttachmentChip(w)
    expect(chip).toBeTruthy()
    await chip!.trigger('click')
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/attachments/7', {
      responseType: 'blob',
    })
    // 응답 바디가 실제로 object URL에 물렸는지 — 파일명은 맞는데 내용이 다른 blob으로
    // 바꿔치기되는 뮤테이션은 createObjectURL 호출 여부만으론 못 잡는다.
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    // 뮤테이션 가드: a.download = att.fileName이 지워지면(파일명 유실) createObjectURL
    // 호출 여부만으로는 걸리지 않는다 — 실제로 지정된 다운로드 파일명을 확인한다.
    expect(anchor()?.download).toBe('요구사항.pdf')
    expect(anchor()?.href).toContain('blob:mock')

    // B2 가드(appendChild): click 시점에 앵커가 실제로 document.body에 붙어 있어야 한다 —
    // appendChild가 삭제돼도 createObjectURL/download 관련 assertion은 안 걸린다.
    // click 콜백 안에서 동기적으로 캡처된 값이므로 setTimeout(0)과 경합하지 않는다.
    expect(wasConnectedAtClick()).toBe(true)
    expect(parentAtClick()).toBe('BODY')

    // B2: object URL을 click과 같은 tick에 revoke하면 일부 브라우저에서 다운로드가 시작되기
    // 전에 끊긴다(Chromium 41380177, Firefox 1282407) — click 시점엔 아직 revoke가 호출된
    // 적이 없어야 한다. (click 콜백 안에서 캡처한 호출 횟수를 확인 — flushPromises 이후에
    // revokeObjectURL 자체를 검사하면 CPU 경합으로 setTimeout(0)이 먼저 실행돼 버릴 때
    // 정상 코드에서도 스퓨리어스하게 실패할 수 있다.)
    expect(revokeCallsAtClick()).toBe(0)
    await new Promise((r) => setTimeout(r, 0))
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock')
    // B2 가드(remove): revoke 이후 앵커가 DOM에서 실제로 제거돼야 한다 — a.remove()가
    // 삭제돼도 revoke 호출 여부 assertion만으론 안 걸린다.
    expect(anchor()?.isConnected).toBe(false)
  })

  it('다운로드 실패 시(JSON 본문) 서버 메시지로 알림을 띄우고 객체 URL을 생성하지 않는다', async () => {
    const w = await mountPage()

    const createObjectURL = vi.fn(() => 'blob:mock')
    Object.assign(URL, { createObjectURL })

    useApiMock.mockClear()
    useApiMock.mockRejectedValue({ data: { message: '첨부파일을 찾을 수 없습니다' } })

    const vm = w.findComponent(TaskDetail).vm as unknown as {
      $q: { notify: (opts: unknown) => void }
    }
    const notifySpy = vi.spyOn(vm.$q, 'notify')

    const chip = findAttachmentChip(w)
    expect(chip).toBeTruthy()
    await chip!.trigger('click')
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith({
      type: 'negative',
      message: '첨부파일을 찾을 수 없습니다',
    })
    expect(createObjectURL).not.toHaveBeenCalled()
  })

  // B1: responseType:'blob'이면 ofetch가 에러 응답 본문도 Blob으로 파싱한다 — e.data는
  // {message} POJO가 아니라 Blob이므로, 서버(Task 6)가 보낸 ACL/404 한국어 메시지가
  // e?.data?.message로는 절대 읽히지 않는다. 이 케이스가 없으면 위 POJO 케이스만으로는
  // "항상 폴백 문자열만 뜨는" 회귀가 그린으로 통과한다.
  it('다운로드 실패 시(Blob 본문) 서버 메시지를 파싱해 알림을 띄운다', async () => {
    const w = await mountPage()

    useApiMock.mockClear()
    const errorBlob = new Blob([JSON.stringify({ error: 'Forbidden', message: '접근 권한이 없습니다' })], {
      type: 'application/json',
    })
    useApiMock.mockRejectedValue({ data: errorBlob })

    const vm = w.findComponent(TaskDetail).vm as unknown as {
      $q: { notify: (opts: unknown) => void }
    }
    const notifySpy = vi.spyOn(vm.$q, 'notify')

    const chip = findAttachmentChip(w)
    expect(chip).toBeTruthy()
    await chip!.trigger('click')
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: '접근 권한이 없습니다' }),
    )
  })

  it('첨부가_없으면_섹션을_렌더링하지_않는다', async () => {
    const w = await mountPage({ ...taskFixture, attachments: [] })

    expect(w.text()).not.toContain('첨부파일')
  })
})
