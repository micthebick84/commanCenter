import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock, useRuntimeConfigMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'
import { setViewportWidth } from './mocks/screen'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const emptyClose = { progress: [] as string[], mergeCommitSha: null, notePath: null }
const deployment = { deployUrl: 'http://x:8080', deployHostPort: 8080, deployImage: 'img', deployedAt: null, deployLog: null }

function setMe(username: string | null) {
  ;(authStub as any).me = username === null ? null : { user_id: 7, username, email: null, authorities: [] }
}

// confirmDialog($q)가 여는 Dialog 플러그인 다이얼로그는 페이지와 별개의 Vue 앱(#q-portal--dialog--N, 루트 컨테이너에 __vue_app__)이라
// w.unmount()로 같이 내려가지 않는다. 그런데 QDialog는 show 때 300ms 타이머(showPortal(true) → 포커스 대기 해제)를 걸고,
// 확인·취소 없이 끝난 시험(배포중단됨 DB 작업의 종료 요청 문구 확인)이나 hide 전환이 남은 시험이 이 타이머를 단 채 끝나면
// 파일이 끝나 환경이 내려간 뒤에 터져 "document is not defined"로 전체 실행이 unhandled error가 된다(부하 의존 경합).
// DOM만 지워서는 앱과 타이머가 그대로라, 앱을 unmount해 QDialog가 자기 타이머를 지우게 한다(onBeforeUnmount → removeTimeout).
function disposeGlobalDialogs() {
  document.body.querySelectorAll<HTMLElement>('[id^="q-portal--dialog"]').forEach((el) => {
    ;(el as unknown as { __vue_app__?: { unmount: () => void } }).__vue_app__?.unmount()
  })
}

describe('pages/tasks/[id] — 작업 종료 (스펙 2026-10-13 §6)', () => {
  beforeEach(() => { authStub.isAdmin = false; setMe(null) })
  afterEach(() => {
    disposeGlobalDialogs()
    document.body.innerHTML = ''
    setMe(null)
    useRuntimeConfigMock.mockImplementation(() => ({ public: { apiBaseUrl: '/api', pollIntervalMs: 5000 } }))
  })

  async function mountWith(task: Record<string, unknown>) {
    useApiMock.mockResolvedValue(task)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    return w
  }

  it('요청자 본인(username 일치, 비관리자)은 DEPLOYED에서 "확인 완료 — 종료 요청"이 주 행동이고, 다른 사람이면 아니다', async () => {
    setMe('alice')
    let w = await mountWith({ ...baseTask, status: 'DEPLOYED', statusLabel: '배포완료', requesterId: 'alice', deployment })
    expect(w.find('[data-test="next-action-primary"]').text()).toContain('확인 완료 — 종료 요청')
    w.unmount()
    document.body.innerHTML = ''

    w = await mountWith({ ...baseTask, status: 'DEPLOYED', statusLabel: '배포완료', requesterId: 'bob', deployment })
    expect(w.find('[data-test="next-action-primary"]').text()).not.toContain('종료 요청')
    w.unmount()
  })

  it('CLOSE_REVIEW: 종료 카드와 4단계가 보이고 관리자에게 반려 버튼이 보인다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({ ...baseTask, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', deployment, close: emptyClose })
    expect(w.find('[data-test="close-card"]').exists()).toBe(true)
    for (const k of ['MERGED', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED']) {
      expect(w.find(`[data-test="close-step-${k}"]`).exists()).toBe(true)
    }
    expect(w.find('[data-test="close-reject"]').exists()).toBe(true)
    w.unmount()
  })

  it('비관리자에게는 반려 버튼이 없다', async () => {
    const w = await mountWith({ ...baseTask, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', deployment, close: emptyClose })
    expect(w.find('[data-test="close-card"]').exists()).toBe(true)
    expect(w.find('[data-test="close-reject"]').exists()).toBe(false)
    w.unmount()
  })

  it('관리자 종료 승인: 확인 뒤 close/approve를 POST하고 작업을 다시 읽는다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({ ...baseTask, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', deployment, close: emptyClose })
    useApiMock.mockClear()
    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', { method: 'POST', body: undefined })
    const urls = useApiMock.mock.calls.map((c) => c[0])
    expect(urls.indexOf('/api/tasks/42')).toBeGreaterThan(urls.indexOf('/api/tasks/42/close/approve'))
    w.unmount()
  })

  it('병합 전 CLOSE_FAILED: 관리자에게 "종료 취소"가 보이고, 확인 뒤 close/cancel을 POST한다 — 병합 뒤에는 안 보인다', async () => {
    authStub.isAdmin = true
    let w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: 'head 불일치', deployment, close: emptyClose,
    })
    const btn = w.find('[data-test="close-cancel"]')
    expect(btn.exists()).toBe(true)
    useApiMock.mockClear()
    await btn.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('병합 전 단계에서 실패한 종료를 취소하고 배포 상태로 되돌립니다.')
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/cancel', { method: 'POST', body: undefined })
    w.unmount()
    document.body.innerHTML = ''

    w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: '노트 실패', deployment,
      close: { progress: ['MERGED'], mergeCommitSha: 'abcdef1234', notePath: null },
    })
    expect(w.find('[data-test="close-cancel"]').exists()).toBe(false)
    w.unmount()
  })

  it('관리자 종료 재시도: 병합이 되돌릴 수 없다는 확인 뒤 close/retry를 POST한다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: 'CI 대기', deployment, close: emptyClose,
    })
    useApiMock.mockClear()
    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/42/close/retry', expect.anything())
    expect(document.body.textContent).toContain('남은 종료 단계(병합 포함 가능)를 다시 실행합니다. 병합은 되돌릴 수 없습니다.')
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/retry', { method: 'POST', body: undefined })
    w.unmount()
  })

  it('CLOSED: 노트 경로가 obsidian:// 링크로 나온다(볼트 이름 설정 시)', async () => {
    useRuntimeConfigMock.mockImplementation(() => ({
      public: { apiBaseUrl: '/api', pollIntervalMs: 5000, obsidianVault: 'Obsidian Vault' },
    }))
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { progress: ['MERGED', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED'], mergeCommitSha: 'abcdef1234', notePath: 'HamonSoft/netisMaker/a-작업42.md' },
    })
    const a = w.find('[data-test="close-note"] a')
    expect(a.exists()).toBe(true)
    expect(a.attributes('href')).toMatch(/^obsidian:\/\/open\?vault=Obsidian%20Vault/)
    w.unmount()
  })

  it('볼트 이름이 없으면 노트 경로만 보이고 링크는 없다', async () => {
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { progress: [], mergeCommitSha: null, notePath: 'HamonSoft/netisMaker/a-작업42.md' },
    })
    expect(w.find('[data-test="close-note"]').text()).toContain('a-작업42.md')
    expect(w.find('[data-test="close-note"] a').exists()).toBe(false)
    w.unmount()
  })

  const artifact = { name: 'w-task-42-abcdef1.war', sizeBytes: 265_911_624, sha256: 'ab', uploadedAt: null }

  it('산출물 줄: 등록자에게 내려받기가 보이고 누르면 download-link를 POST한다 — 다른 사용자에게는 없다', async () => {
    setMe('alice')
    let w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', requesterId: 'alice', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'SENT', at: null } },
    })
    expect(w.find('[data-test="close-artifact"]').text()).toContain('w-task-42-abcdef1.war')
    expect(w.find('[data-test="close-artifact"]').text()).toContain('253.6 MB')
    expect(w.find('[data-test="close-mail"]').text()).toContain('메일 보냄')
    useApiMock.mockClear()
    useApiMock.mockResolvedValueOnce({ url: '/api/artifact-downloads/tok' })
    await w.find('[data-test="close-artifact-download"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/artifact/download-link', { method: 'POST' })
    w.unmount()
    document.body.innerHTML = ''

    setMe('bob')
    w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', requesterId: 'alice', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null },
    })
    expect(w.find('[data-test="close-artifact"]').exists()).toBe(true)
    expect(w.find('[data-test="close-artifact-download"]').exists()).toBe(false)
    w.unmount()
  })

  it('메일 실패면 관리자에게 다시 보내기가 보이고 close/mail/resend를 POST한다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'FAILED', at: null } },
    })
    expect(w.find('[data-test="close-mail"]').text()).toContain('메일 실패')
    useApiMock.mockClear()
    await w.find('[data-test="close-mail-resend"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/mail/resend', { method: 'POST', body: undefined })
    w.unmount()
  })

  // 비동기 발송 리스너가 상태를 못 남긴 경우(DB 오류·API 종료 중 큐에 남은 메일) close.mail이 null — 그래도 관리자는 다시 보낼 수 있다
  it('메일 상태가 없어도(close.mail null) 관리자에게 다시 보내기가 보이고 POST한다 — 비관리자·산출물 없는 종료·SENT에는 없다', async () => {
    const closed = { ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment }
    authStub.isAdmin = true
    let w = await mountWith({ ...closed, close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null } })
    expect(w.find('[data-test="close-mail-unrecorded"]').text()).toBe('메일 발송 기록 없음')
    expect(w.find('[data-test="close-mail"] .q-chip').exists()).toBe(false)
    useApiMock.mockClear()
    await w.find('[data-test="close-mail-resend"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/mail/resend', { method: 'POST', body: undefined })
    w.unmount()
    document.body.innerHTML = ''

    // mail 필드 자체가 없어도 같다(null 또는 미포함)
    w = await mountWith({ ...closed, close: { ...emptyClose, artifactMode: 'REQUIRED', artifact } })
    expect(w.find('[data-test="close-mail-resend"]').exists()).toBe(true)
    w.unmount()
    document.body.innerHTML = ''

    // 산출물을 건너뛴 종료(메일 대상 아님)에는 없다
    w = await mountWith({ ...closed, close: { ...emptyClose, artifactMode: 'SKIPPED', artifact: null, mail: null } })
    expect(w.find('[data-test="close-mail"]').exists()).toBe(false)
    w.unmount()
    document.body.innerHTML = ''

    // 이미 보낸 SENT에는 다시 보내기가 없다
    w = await mountWith({ ...closed, close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'SENT', at: null } } })
    expect(w.find('[data-test="close-mail-resend"]').exists()).toBe(false)
    w.unmount()
    document.body.innerHTML = ''

    // 작업종료가 아니면(종료실패 등) 없다
    w = await mountWith({ ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', deployment, close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null } })
    expect(w.find('[data-test="close-mail"]').exists()).toBe(false)
    w.unmount()
    document.body.innerHTML = ''

    // 비관리자(등록자 본인)에게는 줄 자체가 없다
    authStub.isAdmin = false
    setMe('alice')
    w = await mountWith({ ...closed, requesterId: 'alice', close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null } })
    expect(w.find('[data-test="close-artifact-download"]').exists()).toBe(true)
    expect(w.find('[data-test="close-mail"]').exists()).toBe(false)
    expect(w.find('[data-test="close-mail-resend"]').exists()).toBe(false)
    w.unmount()
  })

  it('산출물 단계에서 멈춘 종료실패: 관리자에게 산출물 없이 마무리가 보이고 확인 뒤 POST한다', async () => {
    authStub.isAdmin = true
    let w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: '산출물: 이미지 없음', deployment,
      close: { progress: ['MERGED', 'UNDEPLOYED'], mergeCommitSha: 'abcdef1234', notePath: null, artifactMode: 'REQUIRED' },
    })
    expect(w.find('[data-test="close-step-ARTIFACT_UPLOADED"]').exists()).toBe(true)
    const btn = w.find('[data-test="close-skip-artifact"]')
    expect(btn.exists()).toBe(true)
    useApiMock.mockClear()
    await btn.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('산출물 없이 노트·브랜치 정리를 이어 갑니다. 메일은 보내지 않습니다.')
    ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/skip-artifact', { method: 'POST', body: undefined })
    w.unmount()
    document.body.innerHTML = ''

    w = await mountWith({
      ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: '노트 실패', deployment,
      close: { progress: ['MERGED', 'UNDEPLOYED', 'ARTIFACT_UPLOADED'], mergeCommitSha: 'abcdef1234', notePath: null, artifactMode: 'REQUIRED' },
    })
    expect(w.find('[data-test="close-skip-artifact"]').exists()).toBe(false)
    w.unmount()
  })

  // 스펙 §9 "메일 상태 칩(+ 시각)" — at이 있으면 칩 옆에 로컬 시각, 없으면 칩만
  it('메일 칩 줄에 보낸 시각이 나오고, 시각이 없으면 칩만 나온다', async () => {
    const at = '2026-10-14T00:30:00Z'
    let w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'SENT', at } },
    })
    expect(w.find('[data-test="close-mail"]').text()).toContain('메일 보냄')
    expect(w.find('[data-test="close-mail"]').text()).toContain(new Date(at).toLocaleString())
    w.unmount()
    document.body.innerHTML = ''

    w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'SENT', at: null } },
    })
    expect(w.find('[data-test="close-mail"]').text()).toBe('메일 보냄')
    w.unmount()
  })

  // 스펙 §9·§11 — 보관 파일이 정리된 뒤 내려받기는 410 (본문 메시지가 없어도 같은 안내)
  it('내려받기가 410이면 "보관 파일이 정리되었습니다"를, 그 외 오류면 서버 메시지 또는 "내려받기 실패"를 알린다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null },
    })
    const vm = w.findComponent(TaskDetail).vm as unknown as { $q: { notify: (opts: unknown) => void } }
    const notifySpy = vi.spyOn(vm.$q, 'notify')
    const click = async () => {
      await w.find('[data-test="close-artifact-download"]').trigger('click')
      await flushPromises()
    }

    useApiMock.mockRejectedValueOnce({ statusCode: 410 })
    await click()
    expect(notifySpy).toHaveBeenLastCalledWith({ type: 'negative', message: '보관 파일이 정리되었습니다' })

    useApiMock.mockRejectedValueOnce({ response: { status: 410 }, data: { message: 'gone' } })
    await click()
    expect(notifySpy).toHaveBeenLastCalledWith({ type: 'negative', message: '보관 파일이 정리되었습니다' })

    useApiMock.mockRejectedValueOnce({ statusCode: 403, data: { message: '권한이 없습니다' } })
    await click()
    expect(notifySpy).toHaveBeenLastCalledWith({ type: 'negative', message: '권한이 없습니다' })

    useApiMock.mockRejectedValueOnce(new Error('network'))
    await click()
    expect(notifySpy).toHaveBeenLastCalledWith({ type: 'negative', message: '내려받기 실패' })
    w.unmount()
  })

  // 주소 토큰은 1회용 — 응답이 오기 전 연타가 download-link를 두 번 POST하면 안 된다
  it('내려받기 연타는 응답이 올 때까지 한 번만 POST한다', async () => {
    authStub.isAdmin = true
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null },
    })
    useApiMock.mockClear()
    let release!: (v: { url: string }) => void
    useApiMock.mockReturnValueOnce(new Promise((r) => { release = r }))
    const btn = w.find('[data-test="close-artifact-download"]')
    await btn.trigger('click')
    await btn.trigger('click')
    expect(useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks/42/artifact/download-link')).toHaveLength(1)
    release({ url: '/api/artifact-downloads/tok' })
    await flushPromises()
    expect(assign).toHaveBeenCalledTimes(1)
    expect(assign).toHaveBeenCalledWith('/api/artifact-downloads/tok')
    assign.mockRestore()
    w.unmount()
  })

  // 빠른 API: startArtifactDownload가 응답 직후 동기로 이동하므로 "진행 중" 플래그만으로는 두 번째 클릭이 새어 나온다.
  // 성공 뒤에는 쿨다운(약 2초) 동안 잠그고, 지나면 다시 누를 수 있다. 오류는 위 410 테스트처럼 바로 풀린다.
  it('내려받기 성공 직후 쿨다운 동안은 재클릭해도 POST하지 않고, 지나면 다시 받을 수 있다', async () => {
    authStub.isAdmin = true
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: null },
    })
    // flushPromises는 setImmediate를 쓰므로 setTimeout·clearTimeout만 가짜로 바꾼다(마운트 뒤에).
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      useApiMock.mockClear()
      const posts = () => useApiMock.mock.calls.filter((c) => c[0] === '/api/tasks/42/artifact/download-link')
      const btn = w.find('[data-test="close-artifact-download"]')

      useApiMock.mockResolvedValueOnce({ url: '/api/artifact-downloads/tok' })
      await btn.trigger('click')
      await flushPromises() // 응답은 이미 끝났다 — 진행 중 플래그만으로는 잠기지 않는 구간
      expect(posts()).toHaveLength(1)
      expect(assign).toHaveBeenCalledTimes(1)

      await btn.trigger('click') // 쿨다운 중 재클릭
      await flushPromises()
      vi.advanceTimersByTime(1999)
      await btn.trigger('click')
      await flushPromises()
      expect(posts()).toHaveLength(1)
      expect(assign).toHaveBeenCalledTimes(1)

      vi.advanceTimersByTime(1) // 쿨다운(2000ms) 끝
      await flushPromises()
      useApiMock.mockResolvedValueOnce({ url: '/api/artifact-downloads/tok2' })
      await btn.trigger('click')
      await flushPromises()
      expect(posts()).toHaveLength(2)
      expect(assign).toHaveBeenLastCalledWith('/api/artifact-downloads/tok2')

      // 쿨다운 타이머는 페이지가 사라질 때 같이 정리된다
      expect(vi.getTimerCount()).toBeGreaterThan(0)
      w.unmount()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
      assign.mockRestore()
    }
  })

  // 서버는 재시도가 또 실패해도 200 {status}를 준다(스펙 §7) — SENT만 성공 알림, 나머지는 결과 상태를 경고로 알린다
  it('메일 다시 보내기: SENT면 완료 알림, 다시 실패하면 경고로 결과 상태를 알린다', async () => {
    authStub.isAdmin = true
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { ...emptyClose, artifactMode: 'REQUIRED', artifact, mail: { status: 'FAILED', at: null } },
    })
    const vm = w.findComponent(TaskDetail).vm as unknown as { $q: { notify: (opts: unknown) => void } }
    const notifySpy = vi.spyOn(vm.$q, 'notify')
    const resend = async (res: { status: string }) => {
      notifySpy.mockClear()
      useApiMock.mockResolvedValueOnce(res)
      await w.find('[data-test="close-mail-resend"]').trigger('click')
      await flushPromises()
    }

    await resend({ status: 'SENT' })
    expect(notifySpy).toHaveBeenCalledTimes(1)
    expect(notifySpy).toHaveBeenCalledWith({ type: 'positive', message: '메일 다시 보내기 완료' })

    await resend({ status: 'FAILED' })
    expect(notifySpy).toHaveBeenCalledTimes(1)
    expect(notifySpy).toHaveBeenCalledWith({ type: 'warning', message: '메일 다시 보내기 결과: 메일 실패' })
    expect(notifySpy).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))

    await resend({ status: 'NO_ADDRESS' })
    expect(notifySpy).toHaveBeenCalledWith({ type: 'warning', message: '메일 다시 보내기 결과: 수신 주소 없음' })

    await resend({ status: 'WHATEVER' }) // 모르는 상태는 원문 그대로
    expect(notifySpy).toHaveBeenCalledWith({ type: 'warning', message: '메일 다시 보내기 결과: WHATEVER' })
    w.unmount()
  })

  it('산출물을 건너뛴 종료는 스테퍼에 "(건너뜀)"과 전용 아이콘으로 보인다', async () => {
    const w = await mountWith({
      ...baseTask, status: 'CLOSED', statusLabel: '작업종료', deployment,
      close: { progress: ['MERGED', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED'], mergeCommitSha: 'abcdef1234', notePath: null, artifactMode: 'SKIPPED' },
    })
    const step = w.find('[data-test="close-step-ARTIFACT_UPLOADED"]')
    expect(step.exists()).toBe(true)
    expect(step.text()).toContain('산출물 업로드 (건너뜀)')
    expect(step.find('.q-icon').text()).toBe('remove_circle_outline')
    // 건너뜀이 아닌 단계는 그대로 — 완료 단계에는 (건너뜀)이 붙지 않는다
    expect(w.find('[data-test="close-step-MERGED"]').text()).not.toContain('건너뜀')
    w.unmount()
  })

  it('모바일: 산출물 없이 마무리는 카드 버튼이 숨고 하단 더 보기 메뉴에서 확인 뒤 POST한다', async () => {
    authStub.isAdmin = true
    await setViewportWidth(390)
    try {
      const w = await mountWith({
        ...baseTask, status: 'CLOSE_FAILED', statusLabel: '종료실패', failureReason: '산출물: 이미지 없음', deployment,
        close: { progress: ['MERGED', 'UNDEPLOYED'], mergeCommitSha: 'abcdef1234', notePath: null, artifactMode: 'REQUIRED' },
      })
      expect(w.find('[data-test="close-skip-artifact"]').exists()).toBe(false)
      await w.find('[data-test="bar-more"]').trigger('click')
      await flushPromises()
      useApiMock.mockClear()
      ;(document.body.querySelector('[data-test="bar-more-close-skip-artifact"]') as HTMLElement).click()
      await flushPromises()
      expect(document.body.textContent).toContain('산출물 없이 노트·브랜치 정리를 이어 갑니다. 메일은 보내지 않습니다.')
      ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/skip-artifact', { method: 'POST', body: undefined })
      w.unmount()
    } finally {
      await setViewportWidth(1024)
    }
  })

  describe('관리자의 배포중단됨 작업 종료 요청 (주 행동은 재배포 그대로)', () => {
    const lost = { ...baseTask, status: 'DEPLOY_LOST', statusLabel: '배포중단됨', requesterId: 'admin', deployment }

    async function confirmAndExpectPost() {
      await flushPromises()
      expect(document.body.textContent).toContain('배포 결과를 확인했나요?')
      ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close-request', { method: 'POST', body: undefined })
    }

    it('데스크톱: 진행 카드에 보조 버튼 "종료 요청"이 있고 확인 뒤 close-request를 POST한다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(lost)
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('재배포')
      useApiMock.mockClear()
      await w.find('[data-test="close-request-secondary"]').trigger('click')
      await confirmAndExpectPost()
      w.unmount()
    })

    it('모바일: 하단 더 보기 메뉴에 "종료 요청"이 있다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      await setViewportWidth(390)
      try {
        const w = await mountWith(lost)
        expect(w.find('[data-test="close-request-secondary"]').exists()).toBe(false)
        await w.find('[data-test="bar-more"]').trigger('click')
        await flushPromises()
        useApiMock.mockClear()
        ;(document.body.querySelector('[data-test="bar-more-close-request"]') as HTMLElement).click()
        await confirmAndExpectPost()
        w.unmount()
      } finally {
        await setViewportWidth(1024)
      }
    })

    it('비관리자 등록자는 주 행동이 종료 요청이라 보조 버튼이 없다', async () => {
      setMe('alice')
      const w = await mountWith({ ...lost, requesterId: 'alice' })
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('종료 요청')
      expect(w.find('[data-test="close-request-secondary"]').exists()).toBe(false)
      w.unmount()
    })
  })

  // 스펙 2026-11-03 §6.9 — DB 작업은 PR생성에서 배포 없이 종료를 요청한다. 주 행동은 종료 요청, 관리자의 배포는 보조로 남는다.
  describe('DB 작업 PR생성의 "확인 완료 — 종료 요청" (스펙 2026-11-03 §6.9)', () => {
    const dbPr = { ...baseTask, status: 'PR_CREATED', statusLabel: 'PR생성', taskType: 'DB_WORK', requesterId: 'alice', deployment: null }

    async function confirmAndExpectDbPost() {
      await flushPromises()
      // 배포가 없으니 배포 결과가 아니라 개발 DB 반영 결과를 묻는다 — 되돌릴 수 없다는 배포 문구도 아니다
      expect(document.body.textContent).toContain('개발 DB 반영 결과를 확인했나요? 승인되면 PR을 병합하고 노트 작성·브랜치 정리를 진행합니다.')
      expect(document.body.textContent).not.toContain('배포 결과를 확인했나요?')
      ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close-request', { method: 'POST', body: undefined })
    }

    it('관리자 상세: 주 행동은 종료 요청, 보조 버튼 "배포"가 환경변수 다이얼로그를 연다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(dbPr)
      expect(w.find('[data-test="next-action-text"]').text()).toBe('DB 작업 — 개발 DB 반영을 확인했으면 종료를 요청하세요')
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('확인 완료 — 종료 요청')
      const secondary = w.find('[data-test="deploy-secondary"]')
      expect(secondary.exists()).toBe(true)
      expect(secondary.text()).toContain('배포')
      useApiMock.mockClear()
      await secondary.trigger('click')
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/deploy-env')
      expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/42/close-request', expect.anything())
      w.unmount()
    })

    it('390px: 하단 바 주 행동은 종료 요청이고 "더 보기" 메뉴에 "배포"가 있다 — 배너·카드에 버튼이 겹치지 않는다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      await setViewportWidth(390)
      try {
        const w = await mountWith(dbPr)
        expect(w.find('.next-action--bar [data-test="next-action-primary"]').text()).toContain('확인 완료 — 종료 요청')
        expect(w.find('[data-test="deploy-secondary"]').exists()).toBe(false)
        await w.find('[data-test="bar-more"]').trigger('click')
        await flushPromises()
        const item = document.body.querySelector('[data-test="bar-more-deploy"]') as HTMLElement
        expect(item).not.toBeNull()
        expect(item.textContent).toContain('배포')
        useApiMock.mockClear()
        item.click()
        await flushPromises()
        expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/deploy-env')
        w.unmount()
      } finally {
        await setViewportWidth(1024)
      }
    })

    it('DB 작업 종료 요청 문구: 확인 뒤 close-request를 POST한다 (관리자 주 행동)', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith(dbPr)
      useApiMock.mockClear()
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await confirmAndExpectDbPost()
      w.unmount()
    })

    it('등록자(비관리자)도 주 행동이 종료 요청이고 보조 배포는 없다 — 문구·POST는 같다', async () => {
      setMe('alice')
      const w = await mountWith(dbPr)
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('확인 완료 — 종료 요청')
      expect(w.find('[data-test="deploy-secondary"]').exists()).toBe(false)
      useApiMock.mockClear()
      await w.find('[data-test="next-action-primary"]').trigger('click')
      await confirmAndExpectDbPost()
      w.unmount()
    })

    it('다른 사람(비관리자·비등록자)은 종료 요청도 배포도 없다 — 지금처럼 PR 열기', async () => {
      setMe('bob')
      const w = await mountWith(dbPr)
      expect(w.find('[data-test="next-action-primary"]').text()).toContain('열기')
      expect(w.find('[data-test="next-action-primary"]').text()).not.toContain('종료 요청')
      expect(w.find('[data-test="deploy-secondary"]').exists()).toBe(false)
      w.unmount()
    })

    it('다른 타입·타입 없음 관리자는 지금처럼 배포가 주 행동이고 보조 버튼·종료 요청이 없다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      for (const taskType of ['BUG_FIX', null, undefined]) {
        const w = await mountWith({ ...dbPr, taskType, requesterId: 'admin' })
        expect(w.find('[data-test="next-action-primary"]').text()).toContain('배포')
        expect(w.find('[data-test="next-action-primary"]').text()).not.toContain('종료 요청')
        expect(w.find('[data-test="deploy-secondary"]').exists()).toBe(false)
        w.unmount()
        document.body.innerHTML = ''
      }
    })

    it('배포중단됨 DB 작업의 종료 요청은 배포 문구 그대로다 — 배포를 한 작업이다', async () => {
      authStub.isAdmin = true
      setMe('admin')
      const w = await mountWith({ ...dbPr, status: 'DEPLOY_LOST', statusLabel: '배포중단됨', requesterId: 'admin', deployment })
      useApiMock.mockClear()
      await w.find('[data-test="close-request-secondary"]').trigger('click')
      await flushPromises()
      expect(document.body.textContent).toContain('배포 결과를 확인했나요?')
      expect(document.body.textContent).not.toContain('개발 DB 반영 결과')
      expect(w.find('[data-test="deploy-secondary"]').exists()).toBe(false)
      w.unmount()
    })

    it('종료승인대기의 배포 없는 DB 작업은 스테퍼의 배포 단계가 건너뜀이고, 배포한 DB 작업은 완료다', async () => {
      authStub.isAdmin = true
      let w = await mountWith({ ...dbPr, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', close: emptyClose })
      expect(w.find('[data-test="step-deploy"]').attributes('data-state')).toBe('skipped')
      expect(w.find('[data-test="step-close"]').attributes('data-state')).toBe('current')
      w.unmount()
      document.body.innerHTML = ''

      w = await mountWith({ ...dbPr, status: 'CLOSE_REVIEW', statusLabel: '종료승인대기', deployment, close: emptyClose })
      expect(w.find('[data-test="step-deploy"]').attributes('data-state')).toBe('done')
      w.unmount()
    })
  })
})
