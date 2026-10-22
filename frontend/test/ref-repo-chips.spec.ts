import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import RefRepoChips from '../components/RefRepoChips.vue'
import type { RefRepoView } from '../composables/refRepos'

// 세 상태 한 벌: 커밋 고정(pinned) · 아직 안 받음(pending) · 실패(failed)
const PINNED: RefRepoView = {
  catalogId: 1,
  alias: '옛앱',
  branch: 'release/6.5',
  sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678',
  fetchedAt: '2026-10-22T05:30:00Z',
  error: null,
}
const PENDING: RefRepoView = { catalogId: 2, alias: '공통 UI', branch: 'main', sha: null, fetchedAt: null, error: null }
const FAILED: RefRepoView = {
  catalogId: 3,
  alias: '배치 서버',
  branch: 'master',
  sha: null,
  fetchedAt: null,
  error: 'fatal: Remote branch master not found in upstream origin',
}

const chipsOf = (w: ReturnType<typeof mount>) => w.findAll('[data-test="ref-chip"]')

// Quasar가 폭 밖에서 display:none을 거는 반응형 표시 헬퍼 이름 — 클래스로 쓰면 그 폭에서 콘텐츠가 통째로 사라진다
const RESPONSIVE_HELPER = /^(xs|sm|md|lg|xl|(gt|lt)-(xs|sm|md|lg|xl)|.*-hide)$/
const classNamesOf = (root: Element): string[] =>
  [root, ...Array.from(root.querySelectorAll('*'))].flatMap((el) => Array.from(el.classList))

/**
 * 칩 하나를 그려 마우스를 올리고 툴팁 문구를 돌려준다(툴팁이 없으면 null). QTooltip은 마우스가 올라와야 body 포털에 그려진다 —
 * mouseenter를 보내고 한 틱 기다린다. 단언이 실패해도 finally에서 언마운트해 툴팁이 다음 테스트로 새지 않게 한다.
 */
async function tooltipOf(item: RefRepoView): Promise<string | null> {
  const w = mount(RefRepoChips, { props: { items: [item] }, attachTo: document.body })
  try {
    await chipsOf(w)[0]!.trigger('mouseenter')
    await new Promise((r) => setTimeout(r, 50))
    const tips = document.querySelectorAll('.q-tooltip')
    if (tips.length === 0) return null
    expect(tips).toHaveLength(1)
    return tips[0]!.textContent ?? ''
  } finally {
    w.unmount()
  }
}

describe('RefRepoChips — 참조 repo 칩 줄 (스펙 2026-10-22 §7.1)', () => {
  it('라벨과 상태별 칩을 그린다', () => {
    const w = mount(RefRepoChips, { props: { items: [PINNED, PENDING, FAILED] } })
    const root = w.find('[data-test="ref-repo-chips"]')
    expect(root.exists()).toBe(true)
    expect(root.text()).toContain('참조 repo · 읽기 전용 · 최대 3개')

    const chips = chipsOf(w)
    expect(chips).toHaveLength(3)

    // 커밋 고정: 7자 + 상태 문구·경고 없음
    expect(chips[0]!.text()).toContain('📚 옛앱 · release/6.5@a1b2c3d')
    expect(chips[0]!.text()).not.toContain('다음 답변 때 받음')
    expect(chips[0]!.text()).not.toContain('⚠')
    expect(chips[0]!.attributes('data-state')).toBe('pinned')

    // 아직 안 받음: 커밋 없이 안내 문구
    expect(chips[1]!.text()).toContain('📚 공통 UI · main')
    expect(chips[1]!.text()).not.toContain('@')
    expect(chips[1]!.text()).toContain('다음 답변 때 받음')
    expect(chips[1]!.text()).not.toContain('⚠')
    expect(chips[1]!.attributes('data-state')).toBe('pending')

    // 실패: ⚠
    expect(chips[2]!.text()).toContain('⚠')
    expect(chips[2]!.text()).toContain('📚 배치 서버 · master')
    expect(chips[2]!.text()).not.toContain('다음 답변 때 받음')
    expect(chips[2]!.attributes('data-state')).toBe('failed')
    w.unmount()
  })

  it('고정 칩 툴팁에 고정 커밋과 기준 문구가 있다', async () => {
    const tip = await tooltipOf(PINNED)
    expect(tip).toContain('고정 커밋 a1b2c3d')
    expect(tip).toContain(new Date(PINNED.fetchedAt!).toLocaleString()) // 받은 시각
    expect(tip).toContain('대화 끝까지 이 커밋 기준')
    expect(tip).not.toContain('a1b2c3d4') // 전체 sha가 아니라 7자
  })

  it('툴팁 전체 문구는 "고정 커밋 {7자} · {받은 시각} — 대화 끝까지 이 커밋 기준"이다', async () => {
    expect(await tooltipOf(PINNED)).toBe(
      `고정 커밋 a1b2c3d · ${new Date(PINNED.fetchedAt!).toLocaleString()} — 대화 끝까지 이 커밋 기준`,
    )
  })

  it('받은 시각이 없거나 읽을 수 없으면 그 부분만 뺀다', async () => {
    expect(await tooltipOf({ ...PINNED, fetchedAt: null })).toBe('고정 커밋 a1b2c3d — 대화 끝까지 이 커밋 기준')
    expect(await tooltipOf({ ...PINNED, fetchedAt: 'not-a-date' })).toBe('고정 커밋 a1b2c3d — 대화 끝까지 이 커밋 기준')
  })

  it('실패 칩 툴팁에 사유가 있다', async () => {
    const tip = await tooltipOf(FAILED)
    expect(tip).toContain('fatal: Remote branch master not found in upstream origin')
    expect(tip).not.toContain('고정 커밋') // 커밋이 없다
  })

  it('아직 안 받은 칩에는 툴팁이 없다 (안내 문구가 칩 안에 이미 있다)', async () => {
    expect(await tooltipOf(PENDING)).toBeNull()
  })

  it('편집 모드에서만 ✕와 ＋ 참조 추가가 보이고, 3개면 추가가 사라진다', async () => {
    // 읽기 전용(기본)
    const ro = mount(RefRepoChips, { props: { items: [PINNED, PENDING] } })
    expect(ro.findAll('.q-chip__icon--remove')).toHaveLength(0)
    expect(ro.find('[data-test="ref-add"]').exists()).toBe(false)
    ro.unmount()

    // 편집 + 2개: 칩마다 ✕, ＋ 참조 추가 보임
    const w = mount(RefRepoChips, { props: { items: [PINNED, PENDING], editable: true } })
    expect(w.findAll('.q-chip__icon--remove')).toHaveLength(2)
    // ✕는 어느 참조를 빼는지 스크린리더가 알 수 있게 이름을 가진다
    expect(w.findAll('.q-chip__icon--remove').map((i) => i.attributes('aria-label'))).toEqual([
      '옛앱 참조 해제',
      '공통 UI 참조 해제',
    ])
    const add = w.find('[data-test="ref-add"]')
    expect(add.exists()).toBe(true)
    expect(add.text()).toContain('＋ 참조 추가')

    // 3개가 되면 추가 칩만 사라지고 ✕는 남는다
    await w.setProps({ items: [PINNED, PENDING, FAILED] })
    expect(w.find('[data-test="ref-add"]').exists()).toBe(false)
    expect(w.findAll('.q-chip__icon--remove')).toHaveLength(3)

    // 하나 빼면 다시 보인다
    await w.setProps({ items: [PINNED, PENDING] })
    expect(w.find('[data-test="ref-add"]').exists()).toBe(true)
    w.unmount()
  })

  it('max를 낮추면 그 개수에서 추가가 사라지고 라벨도 따라간다', () => {
    const w = mount(RefRepoChips, { props: { items: [PINNED, PENDING], editable: true, max: 2 } })
    expect(w.find('[data-test="ref-add"]').exists()).toBe(false)
    expect(w.find('[data-test="ref-repo-chips"]').text()).toContain('참조 repo · 읽기 전용 · 최대 2개')
    w.unmount()
  })

  it('✕를 누르면 그 칩의 catalogId로 remove를, ＋ 참조 추가를 누르면 add를 보낸다', async () => {
    const w = mount(RefRepoChips, { props: { items: [PINNED, PENDING], editable: true } })
    const removeIcons = w.findAll('.q-chip__icon--remove')
    await removeIcons[1]!.trigger('click')
    expect(w.emitted('remove')).toEqual([[2]])
    await removeIcons[0]!.trigger('click')
    expect(w.emitted('remove')).toEqual([[2], [1]])
    expect(w.emitted('add')).toBeUndefined()

    await w.find('[data-test="ref-add"]').trigger('click')
    expect(w.emitted('add')).toHaveLength(1)
    // 부모가 items를 바꾸기 전에는 칩이 스스로 사라지지 않는다 (제어 컴포넌트)
    expect(chipsOf(w)).toHaveLength(2)
    w.unmount()
  })

  it('읽기 전용 칩은 눌러도 아무것도 보내지 않는다', async () => {
    const w = mount(RefRepoChips, { props: { items: [PINNED, FAILED] } })
    for (const chip of chipsOf(w)) await chip.trigger('click')
    expect(w.emitted('remove')).toBeUndefined()
    expect(w.emitted('add')).toBeUndefined()
    w.unmount()
  })

  it('비어 있으면 참조 repo 없음', () => {
    const ro = mount(RefRepoChips, { props: { items: [] } })
    expect(ro.find('[data-test="ref-repo-chips"]').text()).toContain('참조 repo 없음')
    expect(chipsOf(ro)).toHaveLength(0)
    expect(ro.find('[data-test="ref-add"]').exists()).toBe(false)
    ro.unmount()

    // 편집 모드면 비어 있어도 추가 칩은 있다
    const ed = mount(RefRepoChips, { props: { items: [], editable: true } })
    expect(ed.find('[data-test="ref-repo-chips"]').text()).toContain('참조 repo 없음')
    expect(ed.find('[data-test="ref-add"]').exists()).toBe(true)
    ed.unmount()
  })

  it('참조가 있으면 "참조 repo 없음"은 보이지 않는다', () => {
    const w = mount(RefRepoChips, { props: { items: [PENDING] } })
    expect(w.text()).not.toContain('참조 repo 없음')
    w.unmount()
  })

  it('칩 색은 DB 칩(teal)·첨부 칩(blue-grey)과 겹치지 않는 deep-purple 계열이다', () => {
    const w = mount(RefRepoChips, { props: { items: [PINNED, PENDING, FAILED] } })
    const html = w.html()
    expect(html).toMatch(/deep-purple/)
    expect(html).not.toMatch(/teal|blue-grey/)
    w.unmount()
  })

  it('Quasar 반응형 헬퍼 이름(xs·sm·md·lg·xl, gt-*, lt-*, *-hide)을 클래스로 쓰지 않는다 (2026-09-06 ChatBubble 회귀 가드)', () => {
    const w = mount(RefRepoChips, { props: { items: [PINNED, PENDING, FAILED], editable: true } })
    expect(classNamesOf(w.element).filter((c) => RESPONSIVE_HELPER.test(c))).toEqual([])
    w.unmount()
  })
})
