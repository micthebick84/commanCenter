import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { QLayout, QPageContainer, QSelect } from 'quasar'
import TasksIndex from '../pages/tasks/index.vue'
import { authStub, useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

const navigateToMock = vi.fn()
Object.assign(globalThis, { navigateTo: navigateToMock })

const PageWrapper = defineComponent({
  setup: () => () =>
    h(QLayout, { view: 'hHh lpR fFf' }, { default: () => h(QPageContainer, {}, { default: () => h(TasksIndex) }) }),
})

const t = (id: number, status: string, statusLabel: string, updatedAt: string, extra: Record<string, unknown> = {}) => ({
  id, githubRepo: 'micthebick84/netis7.0', repoAlias: 'Netis7.0', githubBranch: 'main', title: `작업 ${id}`, description: '',
  status, statusLabel, requesterId: 'admin', retryCount: 0, maxRetry: 3, failureReason: null, designRequested: false,
  createdAt: '2026-08-01T00:00:00Z', updatedAt, implementation: null, totalCostUsd: null, ...extra,
})
const tasks = [
  t(21, 'AWAITING_APPROVAL', '승인대기', '2026-08-24T01:00:00Z'),
  t(18, 'PR_CREATED', 'PR생성', '2026-08-24T02:00:00Z', { implementation: { prUrl: 'https://x/pull/13', prNumber: 13, headBranch: 'b', headSha: 's' } }),
  t(13, 'DEPLOYED', '배포완료', '2026-07-27T00:00:00Z'),
  t(9, 'CANCELLED', '취소됨', '2026-06-01T00:00:00Z'),
]

async function confirmOk() {
  ;(document.body.querySelector('[data-test="confirm-dialog-ok"]') as HTMLElement).click()
  await flushPromises()
}

function stubApi() {
  useApiMock.mockImplementation((url: string) =>
    url.startsWith('/api/tasks')
      ? Promise.resolve({ content: tasks, totalElements: 4, totalPages: 1 })
      : url === '/api/queue/stats'
        ? Promise.resolve({ avgDurationMs: null })
        : Promise.resolve(null),
  )
}

describe('pages/tasks — 모바일 리스트 (스펙 2026-09-06 §4.1)', () => {
  beforeEach(() => { authStub.isAdmin = true; navigateToMock.mockReset(); stubApi() })
  afterEach(async () => { await setViewportWidth(1024); document.body.innerHTML = '' })

  it('lt.md에서는 보드 대신 카드 리스트: 그룹 순서 확인 필요→완료→취소됨(접힘), 요약 스트립, FAB', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('.stage-row').exists()).toBe(false)
    const groups = w.findAll('[data-test^="group-"]').map((g) => g.attributes('data-test'))
    expect(groups).toEqual(['group-attention', 'group-done', 'group-closed'])
    expect(w.find('[data-test="summary-strip"]').text()).toContain('확인 필요 1')
    expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(3) // 취소됨은 접힘
    await w.find('[data-test="closed-toggle"]').trigger('click')
    expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(4)
    expect(w.find('[data-test="fab-create"]').exists()).toBe(true)
    w.unmount()
  })

  it('필터 칩 "완료"는 완료 그룹만 남기고, 카드 탭은 상세로 이동한다', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    await w.find('[data-test="filter-done"]').trigger('click')
    expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(2)
    await w.find('[data-test="task-card-compact"]').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/tasks/18')
    w.unmount()
  })

  describe('⋮ → 시트의 배포·재배포는 환경변수 다이얼로그를 거친다 (본문 없는 POST로 첫 배포가 변수 없이 뜨던 문제)', () => {
    const suggestion = {
      rows: [{ key: 'DB_URL', value: 'jdbc:x', secret: false, description: '', required: true, source: 'TEMPLATE', valueFromPrevious: false }],
      templateCount: 1,
      previousTaskId: null,
    }
    function stubWith(list: unknown[]) {
      useApiMock.mockImplementation((url: string) =>
        /\/api\/tasks\/\d+\/deploy-env$/.test(url)
          ? Promise.resolve(suggestion)
          : url.startsWith('/api/tasks')
            ? Promise.resolve({ content: list, totalElements: list.length, totalPages: 1 })
            : Promise.resolve(null),
      )
    }
    async function openSheetMove(w: ReturnType<typeof mount>, index: number) {
      await w.findAll('[data-test="card-menu"]')[index]!.trigger('click')
      await flushPromises()
      ;(document.body.querySelector('[data-test="sheet-move"]') as HTMLElement).click()
      await flushPromises()
    }

    it('배포: 확인창 없이 다이얼로그가 추천 값을 채우고, 배포를 누르면 envVars를 실어 POST한다', async () => {
      await setViewportWidth(390)
      stubWith(tasks)
      const w = mount(PageWrapper, { attachTo: document.body })
      await flushPromises()
      await openSheetMove(w, 1) // #18 PR생성
      expect(document.body.querySelector('[data-test="confirm-dialog-ok"]')).toBeNull()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/18/deploy-env')
      expect(useApiMock).not.toHaveBeenCalledWith('/api/tasks/18/deploy', expect.anything())
      expect(document.body.querySelector('[data-test="env-key-label"]')!.textContent).toBe('DB_URL')
      ;(document.body.querySelector('[data-test="env-submit"]') as HTMLElement).click()
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/18/deploy', {
        method: 'POST',
        body: { envVars: [{ key: 'DB_URL', value: 'jdbc:x', secret: false }] },
      })
      w.unmount()
    })

    it('재배포(배포실패): 같은 다이얼로그가 redeploy 모드로 열린다', async () => {
      await setViewportWidth(390)
      stubWith([t(40, 'DEPLOY_FAILED', '배포실패', '2026-08-24T03:00:00Z', { envVars: [{ key: 'A', value: '1', secret: false }] })])
      const w = mount(PageWrapper, { attachTo: document.body })
      await flushPromises()
      await openSheetMove(w, 0)
      expect(document.body.querySelector('[data-test="env-submit"]')!.textContent).toContain('재배포')
      ;(document.body.querySelector('[data-test="env-submit"]') as HTMLElement).click()
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/tasks/40/redeploy', expect.objectContaining({ method: 'POST' }))
      w.unmount()
    })
  })

  it('⋮ → 시트의 종료 전이 확인창은 되돌릴 수 없다는 문구를 덧붙인다 — 다른 전이는 그대로', async () => {
    await setViewportWidth(390)
    const closeTasks = [t(30, 'CLOSE_REVIEW', '종료승인대기', '2026-08-24T03:00:00Z'), tasks[1]]
    useApiMock.mockImplementation((url: string) =>
      url.startsWith('/api/tasks')
        ? Promise.resolve({ content: closeTasks, totalElements: 2, totalPages: 1 })
        : Promise.resolve(null),
    )
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    await w.findAll('[data-test="card-menu"]')[0]!.trigger('click') // #30 종료승인대기(확인 필요 그룹 첫 카드)
    await flushPromises()
    ;(document.body.querySelector('[data-test="sheet-move"]') as HTMLElement).click()
    await flushPromises()
    expect(document.body.textContent).toContain('작업 #30 — 종료 승인 — 병합·배포 중지·정리을(를) 실행할까요? PR 병합·배포 중지·브랜치 삭제는 되돌릴 수 없습니다.')
    await confirmOk()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/30/close/approve', { method: 'POST' })
    w.unmount()
  })

  it('데스크톱(1024)에서는 기존 보드가 그대로다', async () => {
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('.stage-row').exists()).toBe(true)
    expect(w.find('[data-test="task-card-compact"]').exists()).toBe(false)
    w.unmount()
  })

  it('"내 작업만" 칩은 mine을 동기화하고 목록을 재조회한다(mine=false)', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    await w.find('[data-test="mine-chip"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith(
      '/api/tasks',
      expect.objectContaining({ params: expect.objectContaining({ mine: 'false' }) }),
    )
    w.unmount()
  })

  it('통계 토글(관리자)은 QueueStatsBar를 펼치고 /api/queue/stats를 호출한다', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    await w.find('[data-test="stats-toggle"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith('/api/queue/stats')
    w.unmount()
  })

  it('⋮ → 시트의 취소는 확인 후 POST /api/tasks/{id}/cancel', async () => {
    await setViewportWidth(390)
    vi.spyOn(window, 'confirm').mockReturnValue(false) // Claude 앱 브라우저 창처럼 네이티브 confirm이 안 뜨는 환경
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    const menus = w.findAll('[data-test="card-menu"]')
    await menus[0]!.trigger('click') // #21 승인대기
    await flushPromises()
    ;(document.body.querySelector('[data-test="sheet-cancel"]') as HTMLElement).click()
    await flushPromises()
    await confirmOk()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/21/cancel', { method: 'POST' })
    w.unmount()
  })

  it('⋮ → 시트의 삭제는 확인 후 DELETE /api/tasks/{id}', async () => {
    await setViewportWidth(390)
    vi.spyOn(window, 'confirm').mockReturnValue(false) // Claude 앱 브라우저 창처럼 네이티브 confirm이 안 뜨는 환경
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    const menus = w.findAll('[data-test="card-menu"]')
    await menus[1]!.trigger('click') // #18 PR생성
    await flushPromises()
    ;(document.body.querySelector('[data-test="sheet-remove"]') as HTMLElement).click()
    await flushPromises()
    await confirmOk()
    expect(useApiMock).toHaveBeenCalledWith('/api/tasks/18', { method: 'DELETE' })
    w.unmount()
  })

  it('필터 결과가 없으면 empty-filter(전체 보기로 복귀 가능), 목록 전체가 비면 empty-all', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    await w.find('[data-test="filter-active"]').trigger('click') // 픽스처엔 진행 중 작업 없음
    expect(w.find('[data-test="empty-filter"]').exists()).toBe(true)
    expect(w.find('[data-test="empty-all"]').exists()).toBe(false)
    await w.find('[data-test="empty-reset"]').trigger('click')
    expect(w.find('[data-test="empty-filter"]').exists()).toBe(false)
    expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(3)
    w.unmount()
  })

  it('필터 칩·통계 토글은 44px 탭 타깃 구조(class 훅)를 갖는다', async () => {
    await setViewportWidth(390)
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    expect(w.find('.chip-row').exists()).toBe(true)
    expect(w.find('[data-test="filter-all"]').classes()).toContain('q-chip')
    expect(w.find('[data-test="stats-toggle"]').classes()).toContain('stats-toggle')
    w.unmount()
  })

  it('데스크톱에서 상태 필터를 고른 뒤 모바일로 좁히면 서버 조회의 status가 무시된다 — 리뷰 파인딩 11', async () => {
    const w = mount(PageWrapper, { attachTo: document.body })
    await flushPromises()
    await w.findComponent(QSelect).vm.$emit('update:modelValue', 'COMPLETED')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith(
      '/api/tasks',
      expect.objectContaining({ params: expect.objectContaining({ status: 'COMPLETED' }) }),
    )
    useApiMock.mockClear()
    await setViewportWidth(390)
    await w.find('[data-test="mine-chip"]').trigger('click')
    await flushPromises()
    expect(useApiMock).toHaveBeenCalledWith(
      '/api/tasks',
      expect.objectContaining({ params: expect.objectContaining({ status: undefined }) }),
    )
    w.unmount()
  })

  describe('상세 필터 시트 (세부 상태)', () => {
    const sheetOptions = () =>
      Array.from(document.body.querySelectorAll('[data-test^="status-option-"]')).map((el) => el.getAttribute('data-test'))

    it('목록에 있는 상태만 건수와 함께 고를 수 있고, 고르면 그 상태 카드만 남는다', async () => {
      await setViewportWidth(390)
      const w = mount(PageWrapper, { attachTo: document.body })
      await flushPromises()
      await w.find('[data-test="status-filter-chip"]').trigger('click')
      await flushPromises()
      expect(sheetOptions()).toEqual([
        'status-option-all',
        'status-option-AWAITING_APPROVAL',
        'status-option-PR_CREATED',
        'status-option-DEPLOYED',
        'status-option-CANCELLED',
      ])
      expect(document.body.querySelector('[data-test="status-option-DEPLOYED"]')!.textContent).toContain('배포완료')
      ;(document.body.querySelector('[data-test="status-option-PR_CREATED"]') as HTMLElement).click()
      await flushPromises()
      const cards = w.findAll('[data-test="task-card-compact"]')
      expect(cards).toHaveLength(1)
      expect(cards[0]!.text()).toContain('작업 18')
      expect(w.find('[data-test="status-filter-chip"]').text()).toContain('PR생성')
      // 요약 스트립은 전체 기준 그대로
      expect(w.find('[data-test="summary-strip"]').text()).toContain('확인 필요 1')
      w.unmount()
    })

    it('그룹 칩과 함께 걸리고, 결과가 없을 때 "전체 보기"는 둘 다 푼다', async () => {
      await setViewportWidth(390)
      const w = mount(PageWrapper, { attachTo: document.body })
      await flushPromises()
      await w.find('[data-test="status-filter-chip"]').trigger('click')
      await flushPromises()
      ;(document.body.querySelector('[data-test="status-option-DEPLOYED"]') as HTMLElement).click()
      await flushPromises()
      await w.find('[data-test="filter-attention"]').trigger('click')
      expect(w.find('[data-test="empty-filter"]').exists()).toBe(true)
      await w.find('[data-test="empty-reset"]').trigger('click')
      expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(3)
      expect(w.find('[data-test="status-filter-chip"]').text()).toContain('상세 필터')
      w.unmount()
    })

    it('칩의 ×로 상태 필터만 푼다', async () => {
      await setViewportWidth(390)
      const w = mount(PageWrapper, { attachTo: document.body })
      await flushPromises()
      await w.find('[data-test="status-filter-chip"]').trigger('click')
      await flushPromises()
      ;(document.body.querySelector('[data-test="status-option-AWAITING_APPROVAL"]') as HTMLElement).click()
      await flushPromises()
      expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(1)
      await w.find('[data-test="status-filter-chip"] .q-chip__icon--remove').trigger('click')
      await flushPromises()
      expect(w.findAll('[data-test="task-card-compact"]')).toHaveLength(3)
      expect(document.body.querySelector('[data-test="status-sheet"]')).toBeNull() // ×가 시트를 열지 않는다
      w.unmount()
    })
  })
})
