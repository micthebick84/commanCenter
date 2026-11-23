import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import SessionSuggestCard from './SessionSuggestCard.vue'
import type { SessionSuggestion, SuggestPicks } from '~/composables/sessionSuggest'

const dbSg = (over: Partial<SessionSuggestion> = {}): SessionSuggestion => ({
  id: 1,
  turnSeq: 3,
  kind: 'DB',
  status: 'PENDING',
  reason: '주문 상태 값을 실제 데이터로 확인해야 합니다',
  targets: ['orders', 'order_status'],
  dbConnectionId: 4,
  repoCatalogId: null,
  repoAlias: null,
  defaultBranch: null,
  dbOptions: [
    { id: 4, name: '개발 DB', dbType: 'POSTGRESQL' },
    { id: 5, name: '내 DB', dbType: 'MYSQL' },
  ],
  dbSlotsLeft: 3,
  refSlotsLeft: 3,
  ...over,
})
const refSg = (over: Partial<SessionSuggestion> = {}): SessionSuggestion => ({
  id: 2,
  turnSeq: 3,
  kind: 'REF_REPO',
  status: 'PENDING',
  reason: '공통 모듈이 이 레포에 있습니다',
  targets: [],
  dbConnectionId: null,
  repoCatalogId: 11,
  repoAlias: '공통 UI',
  defaultBranch: 'main',
  dbOptions: [],
  dbSlotsLeft: 3,
  refSlotsLeft: 3,
  ...over,
})

function mountCard(suggestions: SessionSuggestion[], props: Record<string, unknown> = {}) {
  return mount(SessionSuggestCard, { props: { suggestions, disabled: false, busy: false, ...props } })
}
type W = ReturnType<typeof mountCard>
const isDisabled = (el: { attributes: (n: string) => string | undefined }) =>
  el.attributes('disabled') !== undefined || el.attributes('aria-disabled') === 'true'
const isChecked = (w: W, sel: string) => w.find(sel).attributes('aria-checked') === 'true'
const refBox = (w: W, catalogId: number) => w.find(`[data-test="suggest-ref-${catalogId}"] [role="checkbox"]`)
const accept = (w: W) => w.find('[data-test="suggest-accept"]')
const acceptedPicks = (w: W) => w.emitted('accept')![0][0] as SuggestPicks

describe('SessionSuggestCard (스펙 2026-11-22 §5·§8)', () => {
  it('DB 제안의 이유·보고 싶은 테이블·선택지를 그린다', () => {
    const w = mountCard([dbSg()])
    const row = w.find('[data-test="suggest-db"]')
    expect(row.text()).toContain('DB 연결이 있으면 더 정확히 답할 수 있습니다')
    expect(row.text()).toContain('주문 상태 값을 실제 데이터로 확인해야 합니다')
    expect(row.text()).toContain('orders')
    expect(row.text()).toContain('order_status')
    expect(w.find('[data-test="suggest-db-opt-4"]').exists()).toBe(true)
    expect(w.find('[data-test="suggest-db-opt-5"]').exists()).toBe(true)
    expect(row.text()).toContain('PostgreSQL')
    expect(accept(w).text()).toContain('연결하고 다시 묻기')
    expect(w.find('[data-test="suggest-dismiss"]').text()).toContain('닫기')
    w.unmount()
  })

  it('Claude가 고른 연결이 미리 체크된다', () => {
    const w = mountCard([dbSg()])
    expect(isChecked(w, '[data-test="suggest-db-opt-4"]')).toBe(true)
    expect(isChecked(w, '[data-test="suggest-db-opt-5"]')).toBe(false)
    w.unmount()
  })

  it('체크된 것이 없으면 연결하고 다시 묻기가 꺼진다', async () => {
    const w = mountCard([dbSg({ dbConnectionId: null })]) // 선택지 2개 + Claude가 고른 것 없음 → 체크 없음
    expect(isDisabled(accept(w))).toBe(true)
    await w.find('[data-test="suggest-db-opt-5"]').trigger('click')
    expect(isDisabled(accept(w))).toBe(false)
    await accept(w).trigger('click')
    expect(acceptedPicks(w)).toEqual({ dbConnectionIds: [5], refs: [], suggestionIds: [1] })
    w.unmount()
  })

  it('연결하고 다시 묻기는 고른 연결과 그 제안 id를 낸다', async () => {
    const w = mountCard([dbSg()])
    await accept(w).trigger('click')
    expect(acceptedPicks(w)).toEqual({ dbConnectionIds: [4], refs: [], suggestionIds: [1] })
    w.unmount()
  })

  it('참조 제안은 미리 체크된다', async () => {
    const w = mountCard([refSg()])
    expect(refBox(w, 11).attributes('aria-checked')).toBe('true')
    expect(isDisabled(accept(w))).toBe(false) // 아무것도 누르지 않아도 켜져 있다
    await accept(w).trigger('click')
    expect(acceptedPicks(w)).toEqual({ dbConnectionIds: [], refs: [{ repoCatalogId: 11, branch: 'main' }], suggestionIds: [2] })
    w.unmount()
  })

  it('참조 제안은 기본 브랜치로 낸다', async () => {
    const w = mountCard([refSg({ defaultBranch: 'release/6.5' })])
    expect(w.find('[data-test="suggest-ref-11"]').text()).toContain('이 레포도 함께 보면 좋습니다: 공통 UI')
    expect(w.find('[data-test="suggest-ref-11"]').text()).toContain('공통 모듈이 이 레포에 있습니다')
    await accept(w).trigger('click')
    expect(acceptedPicks(w)).toEqual({
      dbConnectionIds: [],
      refs: [{ repoCatalogId: 11, branch: 'release/6.5' }],
      suggestionIds: [2],
    })
    w.unmount()
  })

  it('DB와 참조를 함께 고르면 둘 다와 두 제안 id를 낸다', async () => {
    const w = mountCard([dbSg(), refSg()])
    await accept(w).trigger('click')
    expect(acceptedPicks(w)).toEqual({
      dbConnectionIds: [4],
      refs: [{ repoCatalogId: 11, branch: 'main' }],
      suggestionIds: [1, 2],
    })
    w.unmount()
  })

  it('체크하지 않은 제안의 id는 내지 않는다', async () => {
    const w = mountCard([dbSg(), refSg(), refSg({ id: 3, repoCatalogId: 12, repoAlias: '배치 서버', defaultBranch: 'master' })])
    expect(refBox(w, 11).attributes('aria-checked')).toBe('true') // 둘 다 미리 체크돼 있다
    expect(refBox(w, 12).attributes('aria-checked')).toBe('true')
    await refBox(w, 11).trigger('click') // 미리 체크된 참조를 해제
    await w.find('[data-test="suggest-db-opt-4"]').trigger('click') // Claude가 고른 연결을 해제
    await accept(w).trigger('click')
    expect(acceptedPicks(w)).toEqual({
      dbConnectionIds: [],
      refs: [{ repoCatalogId: 12, branch: 'master' }],
      suggestionIds: [3],
    })
    w.unmount()
  })

  it('남은 자리만큼만 체크할 수 있다', async () => {
    const w = mountCard([
      refSg({ refSlotsLeft: 1 }),
      refSg({ id: 3, repoCatalogId: 12, repoAlias: '배치 서버', defaultBranch: 'master', refSlotsLeft: 1 }),
    ])
    // 목록 순서대로 남은 자리(1)만큼만 미리 체크된다
    expect(refBox(w, 11).attributes('aria-checked')).toBe('true')
    expect(refBox(w, 12).attributes('aria-checked')).toBe('false')
    expect(isDisabled(refBox(w, 12))).toBe(true)
    expect(w.find('[data-test="suggest-ref-full"]').text()).toBe('참조 레포는 3개까지 붙일 수 있습니다')
    await refBox(w, 12).trigger('click') // 꺼진 체크박스는 눌러도 체크되지 않는다
    expect(refBox(w, 12).attributes('aria-checked')).toBe('false')
    await accept(w).trigger('click')
    expect(acceptedPicks(w).refs).toEqual([{ repoCatalogId: 11, branch: 'main' }])
    await refBox(w, 11).trigger('click') // 해제하면 다시 고를 수 있다
    expect(isDisabled(refBox(w, 12))).toBe(false)
    expect(w.find('[data-test="suggest-ref-full"]').exists()).toBe(false)
    w.unmount()
  })

  it('같은 제안이 다시 와도 사용자가 바꾼 체크를 되돌리지 않는다', async () => {
    const w = mountCard([refSg()])
    await refBox(w, 11).trigger('click') // 미리 체크된 참조를 해제
    await w.setProps({ suggestions: [refSg(), dbSg()] }) // SSE가 같은 id를 다시 보내고 새 제안이 붙는다
    expect(refBox(w, 11).attributes('aria-checked')).toBe('false')
    expect(isChecked(w, '[data-test="suggest-db-opt-4"]')).toBe(true) // 새로 온 제안은 미리 체크
    w.unmount()
  })

  it('DB 자리가 없으면 선택지가 꺼지고 사유가 보인다', async () => {
    const w = mountCard([dbSg({ dbSlotsLeft: 0 })])
    expect(isChecked(w, '[data-test="suggest-db-opt-4"]')).toBe(false) // 자리가 없으면 미리 체크도 없다
    expect(isDisabled(w.find('[data-test="suggest-db-opt-4"]'))).toBe(true)
    expect(isDisabled(w.find('[data-test="suggest-db-opt-5"]'))).toBe(true)
    expect(w.find('[data-test="suggest-db-full"]').text()).toBe('DB 연결은 3개까지 붙일 수 있습니다')
    await w.find('[data-test="suggest-db-opt-5"]').trigger('click')
    expect(isDisabled(accept(w))).toBe(true)
    w.unmount()
  })

  it('선택지가 비었으면 붙일 수 있는 연결이 없습니다를 보인다', () => {
    const w = mountCard([dbSg({ dbOptions: [], dbConnectionId: null })])
    expect(w.find('[data-test="suggest-db-none"]').text()).toBe('붙일 수 있는 연결이 없습니다')
    expect(w.find('[data-test="suggest-db"] [role="checkbox"]').exists()).toBe(false)
    expect(isDisabled(accept(w))).toBe(true)
    w.unmount()
  })

  it('기본 브랜치가 없는 참조 제안은 체크할 수 없다', async () => {
    const w = mountCard([refSg({ defaultBranch: null })])
    expect(refBox(w, 11).attributes('aria-checked')).toBe('false') // 미리 체크되지 않는다
    expect(isDisabled(refBox(w, 11))).toBe(true)
    await refBox(w, 11).trigger('click')
    expect(refBox(w, 11).attributes('aria-checked')).toBe('false')
    expect(isDisabled(accept(w))).toBe(true)
    w.unmount()

    // 자리도 쓰지 않는다 — 자리가 하나뿐이어도 뒤의 붙일 수 있는 참조가 미리 체크된다
    const v = mountCard([
      refSg({ defaultBranch: null, refSlotsLeft: 1 }),
      refSg({ id: 3, repoCatalogId: 12, repoAlias: '배치 서버', defaultBranch: 'master', refSlotsLeft: 1 }),
    ])
    expect(refBox(v, 12).attributes('aria-checked')).toBe('true')
    expect(v.find('[data-test="suggest-ref-full"]').exists()).toBe(false)
    v.unmount()
  })

  it('닫기는 카드의 모든 제안 id를 낸다', async () => {
    const w = mountCard([dbSg(), refSg()])
    await w.find('[data-test="suggest-dismiss"]').trigger('click')
    expect(w.emitted('dismiss')![0][0]).toEqual([1, 2])
    w.unmount()
  })

  it('disabled면 버튼과 체크박스가 모두 꺼진다', async () => {
    const w = mountCard([dbSg(), refSg()], { disabled: true })
    expect(isDisabled(accept(w))).toBe(true)
    expect(isDisabled(w.find('[data-test="suggest-dismiss"]'))).toBe(true)
    expect(isDisabled(w.find('[data-test="suggest-db-opt-4"]'))).toBe(true)
    expect(isDisabled(w.find('[data-test="suggest-db-opt-5"]'))).toBe(true)
    expect(isDisabled(refBox(w, 11))).toBe(true)
    await accept(w).trigger('click')
    await w.find('[data-test="suggest-dismiss"]').trigger('click')
    expect(w.emitted('accept')).toBeUndefined()
    expect(w.emitted('dismiss')).toBeUndefined()
    w.unmount()
  })

  it('체크박스에 접근 가능한 이름이 있다', () => {
    const w = mountCard([dbSg(), refSg()])
    expect(w.find('[data-test="suggest-db-opt-4"]').attributes('role')).toBe('checkbox')
    expect(w.find('[data-test="suggest-db-opt-4"]').attributes('aria-label')).toBe('개발 DB · PostgreSQL')
    expect(w.find('[data-test="suggest-db-opt-5"]').attributes('aria-label')).toBe('내 DB · MySQL')
    expect(refBox(w, 11).attributes('aria-label')).toBe('공통 UI 참조로 추가')
    w.unmount()
  })

  it('카드 안에 Quasar 반응형 헬퍼 이름의 클래스가 없다', () => {
    const w = mountCard([dbSg(), refSg()])
    const bad = /^(xs|sm|md|lg|xl|gt-.*|lt-.*|.*-hide)$/
    const classes = new Set<string>()
    w.element.querySelectorAll('[class]').forEach((el: Element) => el.classList.forEach((c: string) => classes.add(c)))
    w.element.classList.forEach((c: string) => classes.add(c))
    expect([...classes].filter((c: string) => bad.test(c))).toEqual([])
    w.unmount()
  })
})
