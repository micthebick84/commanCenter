import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer } from 'quasar'
import TaskDetail from '../pages/tasks/[id].vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { baseTask } from './fixtures/task'

;(globalThis as any).useRoute = () => ({ params: { id: '42' } })
const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TaskDetail) }) }),
})
const mountOpts = { attachTo: document.body, global: { stubs: { 'router-link': { template: '<a><slot /></a>' }, DesignReviewCard: true } } }

const failed = {
  ...baseTask, status: 'IMPLEMENTATION_FAILED', statusLabel: '구현실패', implementation: null,
  failureReason: 'claude exec 실패: claude timeout after PT4H — 지금까지 작업(커밋 2개)은 브랜치 `netismaker/task-42-x`에 올려 둠',
  implementationMode: 'SDD', implementationAttempt: 1,
}

describe('pages/tasks/[id] — 구현 재시도 (스펙 2026-10-15 §5.5·§7)', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('관리자: 다음 할 일 "구현 재시도" → 방식 다시 고르기 → 재시도 API', async () => {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(failed)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()

    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    const dialog = document.body.querySelector('[data-test="impl-retry-dialog"]')
    expect(dialog).not.toBeNull()
    // 이전 사유·브랜치가 이력에 남는다는 안내와 함께, push 안 된 부분 작업은 지워질 수 있다는 경고
    expect(dialog!.textContent).toContain('이전 실패 사유와 브랜치는 진행 이력에 남아 있습니다')
    expect(dialog!.textContent).toContain('push되지 않은 이전 부분 작업은 재시도하면 이 PC에서 지워질 수 있습니다')
    ;(document.body.querySelector('[data-test="impl-mode-REVIEW"]') as HTMLElement).click()
    await flushPromises()
    useApiMock.mockClear()
    useApiMock.mockResolvedValue(failed)
    ;(document.body.querySelector('[data-test="impl-retry-submit"]') as HTMLElement).click()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', {
      method: 'POST',
      body: { implementationMode: 'REVIEW' },
    })
    w.unmount()
  })

  // 참조 repo 빼고 재시도 (작업 스펙 2026-10-24 §7.5b) — 확정 뒤라 두 참조 모두 고정 커밋이 있다
  const REFS = [
    { catalogId: 2, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0', fetchedAt: '2026-10-24T01:00:00Z', error: null },
    { catalogId: 3, alias: '공통 UI', branch: 'main', sha: 'f0e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9', fetchedAt: '2026-10-24T01:00:00Z', error: null },
  ]
  const isChecked = (el: Element | null) => el?.getAttribute('aria-checked') === 'true'

  async function openRetry(task: Record<string, unknown>) {
    authStub.isAdmin = true
    useApiMock.mockResolvedValue(task)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    await w.find('[data-test="next-action-primary"]').trigger('click')
    await flushPromises()
    const dialog = document.body.querySelector('[data-test="impl-retry-dialog"]')
    expect(dialog).not.toBeNull()
    return { w, dialog: dialog! }
  }
  async function submitRetry(task: Record<string, unknown>) {
    useApiMock.mockClear()
    useApiMock.mockResolvedValue(task)
    ;(document.body.querySelector('[data-test="impl-retry-submit"]') as HTMLElement).click()
    await flushPromises()
  }

  it('참조가 있으면 목록·"이 참조 빼고 재시도"(기본 해제)·안내 문구, 고르지 않으면 바디는 { implementationMode }만', async () => {
    const task = { ...failed, refRepos: REFS }
    const { w, dialog } = await openRetry(task)

    const box = dialog.querySelector('[data-test="impl-retry-refs"]')!
    expect(box).not.toBeNull()
    expect(box.textContent).toContain('참조 repo')
    expect(box.textContent).toContain('📚 옛앱 · release/6.5@a1b2c3d')
    expect(box.textContent).toContain('📚 공통 UI · main@f0e1d2c')
    expect(box.textContent).toContain('뺀 참조는 이번 구현과 이후 재시도에서 쓰지 않습니다.')
    // 구현 방식 아래에 있다
    const picker = dialog.querySelector('[data-test="impl-mode-REVIEW"]')!
    expect(picker.compareDocumentPosition(box) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    for (const id of [2, 3]) {
      const cb = box.querySelector(`[data-test="impl-retry-ref-${id}"]`)
      expect(cb?.textContent).toContain('이 참조 빼고 재시도')
      expect(isChecked(cb)).toBe(false) // 기본 해제
    }

    await submitRetry(task)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', {
      method: 'POST',
      body: { implementationMode: 'SDD' },
    })
    w.unmount()
  })

  it('고른 참조만 작업 참조 순서로 removeRefCatalogIds에 싣는다', async () => {
    const task = { ...failed, refRepos: REFS }
    const { w, dialog } = await openRetry(task)
    ;(dialog.querySelector('[data-test="impl-retry-ref-3"]') as HTMLElement).click()
    await flushPromises()
    ;(dialog.querySelector('[data-test="impl-retry-ref-2"]') as HTMLElement).click()
    await flushPromises()
    expect(isChecked(dialog.querySelector('[data-test="impl-retry-ref-2"]'))).toBe(true)

    await submitRetry(task)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', {
      method: 'POST',
      body: { implementationMode: 'SDD', removeRefCatalogIds: [2, 3] },
    })
    w.unmount()
  })

  it('사유가 참조 준비 실패면 그 참조가 미리 선택되고 바디에 removeRefCatalogIds: [2]', async () => {
    const task = {
      ...failed,
      refRepos: REFS,
      failureReason: '참조 repo 준비 실패: 옛앱(release/6.5) — 고정 커밋 a1b2c3d을(를) 찾지 못했습니다 — 브랜치 release/6.5의 최근 500개 커밋 안에 없음',
    }
    const { w, dialog } = await openRetry(task)
    expect(isChecked(dialog.querySelector('[data-test="impl-retry-ref-2"]'))).toBe(true)
    expect(isChecked(dialog.querySelector('[data-test="impl-retry-ref-3"]'))).toBe(false)

    await submitRetry(task)
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/42/implementation/retry', {
      method: 'POST',
      body: { implementationMode: 'SDD', removeRefCatalogIds: [2] },
    })
    w.unmount()
  })

  it('참조가 없으면 참조 영역이 없다', async () => {
    const { w, dialog } = await openRetry(failed)
    expect(dialog.querySelector('[data-test="impl-retry-refs"]')).toBeNull()
    expect(document.body.querySelector('[data-test^="impl-retry-ref-"]')).toBeNull()
    w.unmount()
  })

  it('요청자: 문구만 있고 재시도 버튼은 없다', async () => {
    authStub.isAdmin = false
    useApiMock.mockResolvedValue(failed)
    const w = mount(PageWrapper, mountOpts)
    await flushPromises()
    expect(w.find('[data-test="next-action-text"]').text()).toContain('관리자 확인이 필요합니다')
    expect(w.find('[data-test="next-action-primary"]').exists()).toBe(false)
    w.unmount()
  })
})
