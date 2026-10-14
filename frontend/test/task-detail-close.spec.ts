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

describe('pages/tasks/[id] — 작업 종료 (스펙 2026-10-13 §6)', () => {
  beforeEach(() => { authStub.isAdmin = false; setMe(null) })
  afterEach(() => {
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
})
