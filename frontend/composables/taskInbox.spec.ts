import { describe, expect, it, vi } from 'vitest'
import { STAGES, attentionGroup } from './taskStages'
import {
  AUTO_NEXT_KEY,
  INBOX_GROUPS,
  type InboxRow,
  buildInbox,
  inboxListParams,
  isHidden,
  isInboxCandidate,
  isTruncated,
  neighborId,
  pickNext,
  pruneHandled,
  readAutoNext,
  shouldIgnoreShortcut,
  writeAutoNext,
} from './taskInbox'

function row(id: number, status: string, updatedAt: string): InboxRow {
  return { id, status, statusLabel: status, updatedAt, title: `작업 ${id}`, repoAlias: null, githubRepo: 'o/r', retryCount: 0, maxRetry: 3 }
}

describe('taskInbox', () => {
  it('확인_필요_상태는_정확히_한_묶음에_든다', () => {
    const all = STAGES.flatMap((s) => s.statuses)
    for (const s of all) {
      const inGroups = INBOX_GROUPS.filter((g) => g.statuses.includes(s)).length
      expect(inGroups).toBe(attentionGroup({ status: s }, true) === 'attention' ? 1 : 0)
    }
  })
  it('묶음_순서와_이름', () => {
    expect(INBOX_GROUPS.map((g) => g.label)).toEqual(['답변 대기', '계획 확인', '인터뷰 시작 승인', '디자인 검토', '수정 요청 승인', '종료 승인', '실패'])
  })
  it('재시도_한도를_다_쓴_FAILED는_후보가_아니다', () => {
    expect(isInboxCandidate({ status: 'FAILED', retryCount: 3, maxRetry: 3 })).toBe(false)
    expect(isInboxCandidate({ status: 'FAILED', retryCount: 1, maxRetry: 3 })).toBe(true)
    expect(isInboxCandidate({ status: 'PR_CREATED', retryCount: 0, maxRetry: 3 })).toBe(false)
  })
  it('묶음_안은_updatedAt_오름차순이고_빈_묶음은_없다', () => {
    const g = buildInbox([row(1, 'INTERVIEW_INPUT', '2026-11-23T02:00:00Z'), row(2, 'INTERVIEW_INPUT', '2026-11-23T01:00:00Z'), row(3, 'CLOSE_FAILED', '2026-11-20T00:00:00Z')], [])
    expect(g.map((x) => x.key)).toEqual(['answer', 'failed'])
    expect(g[0].items.map((x) => x.id)).toEqual([2, 1])
  })
  it('처리한_건은_같은_status·updatedAt인_동안_숨는다', () => {
    const r = row(1, 'INTERVIEW_INPUT', '2026-11-23T01:00:00Z')
    expect(isHidden(r, [{ id: 1, status: 'INTERVIEW_INPUT', updatedAt: '2026-11-23T01:00:00Z' }])).toBe(true)
  })
  it('updatedAt이_바뀌면_같은_상태여도_다시_보인다', () => {
    const marks = [{ id: 1, status: 'INTERVIEW_INPUT', updatedAt: '2026-11-23T01:00:00Z' }]
    const r = row(1, 'INTERVIEW_INPUT', '2026-11-23T01:03:00Z')
    expect(isHidden(r, marks)).toBe(false)
    expect(pruneHandled(marks, [r])).toEqual([])
  })
  it('행이_사라지면_기록도_지운다', () => {
    expect(pruneHandled([{ id: 9, status: 'AWAITING_APPROVAL', updatedAt: 'x' }], [])).toEqual([])
  })
  it('다음_건은_뺀_건을_제외한_맨_위', () => {
    expect(pickNext([{ id: 5 }, { id: 6 }], 5)).toBe(6)
    expect(pickNext([{ id: 5 }], 5)).toBeNull()
    expect(pickNext([], null)).toBeNull()
  })
  it('이웃은_감싸지_않는다', () => {
    const f = [{ id: 1 }, { id: 2 }]
    expect(neighborId(f, 1, 1)).toBe(2)
    expect(neighborId(f, 2, 1)).toBeNull()
    expect(neighborId(f, 1, -1)).toBeNull()
    expect(neighborId(f, 99, 1)).toBe(1) // 목록에 없는 현재 건 → 맨 위
  })
  it('목록_조회_파라미터', () => {
    expect(inboxListParams()).toEqual({ mine: 'false', size: 200, sort: 'updatedAt,desc' })
  })
  it('200건_판정', () => {
    expect(isTruncated({ content: new Array(200), totalElements: 201 })).toBe(true)
    expect(isTruncated({ content: new Array(3), totalElements: 3 })).toBe(false)
    expect(isTruncated(null)).toBe(false)
  })
  it('입력칸·다이얼로그·수식키에서는_단축키를_무시', () => {
    const ta = document.createElement('textarea')
    const ce = document.createElement('div')
    ce.setAttribute('contenteditable', 'true')
    const inner = document.createElement('span')
    ce.appendChild(inner)
    const base = { altKey: false, ctrlKey: false, metaKey: false }
    expect(shouldIgnoreShortcut({ key: 'j', target: ta, ...base }, false)).toBe(true)
    expect(shouldIgnoreShortcut({ key: 'j', target: ce, ...base }, false)).toBe(true)
    expect(shouldIgnoreShortcut({ key: 'j', target: inner, ...base }, false)).toBe(true)
    expect(shouldIgnoreShortcut({ key: 'j', target: document.body, ...base }, true)).toBe(true)
    expect(shouldIgnoreShortcut({ key: 'j', target: document.body, ...base, ctrlKey: true }, false)).toBe(true)
    expect(shouldIgnoreShortcut({ key: 'x', target: document.body, ...base }, false)).toBe(true)
    expect(shouldIgnoreShortcut({ key: 'j', target: document.body, ...base }, false)).toBe(false)
  })
  it('q-select·q-menu_안에서도_단축키를_무시', () => {
    const base = { altKey: false, ctrlKey: false, metaKey: false }
    // q-select는 포커스가 role=combobox인 div에 있다(입력칸이 아니다) — 작업 타입 select에서 j를 누르면 건이 바뀌면 안 된다
    const combo = document.createElement('div')
    combo.setAttribute('role', 'combobox')
    combo.tabIndex = 0
    const comboInner = document.createElement('span')
    combo.appendChild(comboInner)
    // q-menu(✎ 참조 변경 등) 안의 항목·목록
    const menu = document.createElement('div')
    menu.className = 'q-menu'
    const menuItem = document.createElement('div')
    menuItem.className = 'q-item'
    menu.appendChild(menuItem)
    const listbox = document.createElement('div')
    listbox.setAttribute('role', 'listbox')
    const option = document.createElement('div')
    listbox.appendChild(option)
    const roleMenu = document.createElement('div')
    roleMenu.setAttribute('role', 'menu')
    const plain = document.createElement('div')
    for (const target of [combo, comboInner, menu, menuItem, option, roleMenu]) {
      expect(shouldIgnoreShortcut({ key: 'j', target, ...base }, false)).toBe(true)
    }
    expect(shouldIgnoreShortcut({ key: 'k', target: plain, ...base }, false)).toBe(false)
  })
  it('자동_이동_저장값은_0일_때만_끔', () => {
    localStorage.removeItem(AUTO_NEXT_KEY)
    expect(readAutoNext()).toBe(true)
    writeAutoNext(false)
    expect(localStorage.getItem(AUTO_NEXT_KEY)).toBe('0')
    expect(readAutoNext()).toBe(false)
    writeAutoNext(true)
    expect(localStorage.getItem(AUTO_NEXT_KEY)).toBeNull()
  })
  it('저장소가_예외를_던지면_켬', () => {
    // 읽기·쓰기·지우기 모두 막힌 저장소(사생활 보호 창·차단된 사이트 데이터)
    const blocked = () => { throw new Error('blocked') }
    const spies = [
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked),
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked),
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(blocked),
    ]
    try {
      expect(readAutoNext()).toBe(true)
      expect(() => writeAutoNext(false)).not.toThrow()
      expect(() => writeAutoNext(true)).not.toThrow()
    } finally {
      spies.forEach((s) => s.mockRestore())
    }
  })
})
