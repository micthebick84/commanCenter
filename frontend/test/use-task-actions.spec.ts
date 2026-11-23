import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { useTaskActions, type ActionTask, type TaskActions } from '../composables/useTaskActions'
import { authStub, useApiMock } from './mocks/nuxt'

// 확인창은 페이지와 별개의 Vue 앱(#q-portal--dialog--N)이라 w.unmount()로 같이 내려가지 않는다 — 남은 타이머를
// 앱 unmount로 지운다(task-detail-close.spec.ts와 같은 방식).
function disposeGlobalDialogs() {
  document.body.querySelectorAll<HTMLElement>('[id^="q-portal--dialog"]').forEach((el) => {
    ;(el as unknown as { __vue_app__?: { unmount: () => void } }).__vue_app__?.unmount()
  })
}

function dialogButton(label: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('.q-dialog button')).find((b) => b.textContent?.trim() === label)
}

async function clickDialogButton(label: string) {
  await vi.waitFor(() => expect(dialogButton(label)).toBeTruthy())
  dialogButton(label)!.click()
  await flushPromises()
}

describe('useTaskActions', () => {
  let actions: TaskActions
  let refresh: ReturnType<typeof vi.fn>
  let wrapper: ReturnType<typeof mount>

  function setup(task: Partial<ActionTask> = {}) {
    refresh = vi.fn()
    const Host = defineComponent({
      setup() {
        actions = useTaskActions(ref({ id: 42, status: 'DEPLOYED', githubBranch: 'feature/x', ...task } as ActionTask), refresh)
        return () => null
      },
    })
    wrapper = mount(Host, { attachTo: document.body })
  }

  beforeEach(() => {
    authStub.isAdmin = true
    setup()
  })
  afterEach(() => {
    wrapper.unmount()
    disposeGlobalDialogs()
    document.body.innerHTML = ''
    authStub.isAdmin = false
  })

  // 종료 승인은 선택창(스펙 2026-11-23 close-keep-branch §4.5) — 기본 선택은 병합하고 종료(바디 없음)
  it('approveClose_기본_선택으로_승인하면_true이고_바디_없이_close/approve를_부른다', async () => {
    useApiMock.mockResolvedValueOnce([]) // fetchDeferredWarning
    useApiMock.mockResolvedValueOnce({})
    const p = actions.approveClose()
    await vi.waitFor(() => expect(document.body.textContent).toContain('병합하고 종료 — PR을 feature/x에 병합'))
    await clickDialogButton('종료 승인')
    expect(await p).toBe(true)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', { method: 'POST', body: undefined })
    expect(refresh).toHaveBeenCalled()
  })

  it('approveClose_병합하지_않고_종료를_고르면_keepBranch_바디를_싣는다', async () => {
    useApiMock.mockResolvedValueOnce([]) // fetchDeferredWarning
    useApiMock.mockResolvedValueOnce({})
    const p = actions.approveClose()
    await vi.waitFor(() => expect(document.body.querySelectorAll('.q-dialog .q-radio')).toHaveLength(2))
    document.body.querySelectorAll<HTMLElement>('.q-dialog .q-radio')[1]!.click()
    await flushPromises()
    await clickDialogButton('종료 승인')
    expect(await p).toBe(true)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', { method: 'POST', body: { keepBranch: true } })
  })

  it('approveClose_선택창을_취소하면_false이고_승인을_보내지_않는다', async () => {
    useApiMock.mockResolvedValueOnce([]) // fetchDeferredWarning
    const p = actions.approveClose()
    await clickDialogButton('취소')
    expect(await p).toBe(false)
    expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/42/close/approve', expect.anything())
    expect(refresh).not.toHaveBeenCalled()
  })

  it('확인창을_취소하면_false이고_API를_부르지_않는다', async () => {
    const p = actions.retryClose()
    await clickDialogButton('취소')
    expect(await p).toBe(false)
    expect(useApiMock).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
  })

  it('서버가_거절하면_false', async () => {
    useApiMock.mockRejectedValueOnce({ data: { message: 'x' } })
    expect(await actions.retry()).toBe(false)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/retry', expect.objectContaining({ method: 'POST' }))
    expect(refresh).not.toHaveBeenCalled()
  })

  it('retry는_서버가_받으면_true', async () => {
    useApiMock.mockResolvedValueOnce({})
    expect(await actions.retry()).toBe(true)
    expect(refresh).toHaveBeenCalled()
  })

  it('rejectFix는_사유를_입력해_확인하면_true이고_reason을_싣는다', async () => {
    useApiMock.mockResolvedValueOnce({})
    const p = actions.rejectFix()
    await vi.waitFor(() => expect(document.body.querySelector('.q-dialog input')).toBeTruthy())
    const input = document.body.querySelector<HTMLInputElement>('.q-dialog input')!
    input.value = ' 사유 '
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    await clickDialogButton('반려')
    expect(await p).toBe(true)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/reject', expect.objectContaining({ method: 'POST', body: { reason: '사유' } }))
  })

  it('approveFix는_고른_방식을_싣는다', async () => {
    useApiMock.mockResolvedValueOnce({})
    const p = actions.approveFix()
    await clickDialogButton('승인')
    expect(await p).toBe(true)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/fix-requests/approve', expect.objectContaining({ method: 'POST', body: { implementationMode: 'REVIEW' } }))
  })

  it('undeploy는_확인창_취소면_false', async () => {
    const p = actions.undeploy()
    await clickDialogButton('취소')
    expect(await p).toBe(false)
    expect(useApiMock).not.toHaveBeenCalled()
  })

  it('undeploy는_확인하면_서버_결과를_따른다', async () => {
    useApiMock.mockResolvedValueOnce({})
    const p = actions.undeploy()
    await clickDialogButton('배포 중지')
    expect(await p).toBe(true)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/undeploy', expect.objectContaining({ method: 'POST' }))
  })

  it('closeAction_진행_중의_두번째_호출은_false', async () => {
    let release: (v: unknown) => void = () => {}
    useApiMock.mockReturnValueOnce(new Promise((r) => { release = r }))
    const first = actions.resendMail()
    expect(await actions.resendMail()).toBe(false)
    release({ status: 'SENT' })
    expect(await first).toBe(true)
    expect(useApiMock).toHaveBeenCalledTimes(1)
  })

  it('버튼_조건은_상태와_관리자_여부를_따른다', async () => {
    wrapper.unmount()
    setup({ status: 'CLOSE_FAILED', close: { progress: [] } as any })
    expect(actions.canCancelClose.value).toBe(true)
    expect(actions.canAbort.value).toBe(false)
    expect(actions.abortLabel.value).toBe('수정 중단')
    wrapper.unmount()
    authStub.isAdmin = false
    setup({ status: 'CLOSE_FAILED', close: { progress: [] } as any })
    expect(actions.canCancelClose.value).toBe(false)
  })

  it('브랜치_유지_종료는_진행_단계가_없을_때만_종료_취소', async () => {
    wrapper.unmount()
    setup({ status: 'CLOSE_FAILED', close: { progress: ['UNDEPLOYED'], keepBranch: true } as any })
    expect(actions.canCancelClose.value).toBe(false)
    expect(actions.canSyncBaseNow.value).toBe(false)
    wrapper.unmount()
    setup({ status: 'CLOSE_FAILED', close: { progress: [], keepBranch: true } as any })
    expect(actions.canCancelClose.value).toBe(true)
  })

  it('retryClose_문구는_브랜치_유지_종료면_병합하지_않는다고_말한다', async () => {
    wrapper.unmount()
    setup({ status: 'CLOSE_FAILED', close: { progress: ['UNDEPLOYED'], keepBranch: true } as any })
    const p = actions.retryClose()
    await vi.waitFor(() => expect(document.body.textContent).toContain('남은 종료 단계(배포 중지·노트)를 다시 실행합니다. 병합하지 않습니다.'))
    await clickDialogButton('취소')
    expect(await p).toBe(false)
  })
})
