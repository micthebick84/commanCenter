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

  it('approveClose_확인하면_true이고_close/approve를_부른다', async () => {
    useApiMock.mockResolvedValueOnce([]) // fetchDeferredWarning
    useApiMock.mockResolvedValueOnce({})
    const p = actions.approveClose()
    await clickDialogButton('병합하고 종료')
    expect(await p).toBe(true)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/close/approve', expect.objectContaining({ method: 'POST' }))
    expect(refresh).toHaveBeenCalled()
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
})
