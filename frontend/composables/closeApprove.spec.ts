import { describe, it, expect, vi } from 'vitest'
import type { QVueGlobals } from 'quasar'
import { closeApproveBody, chooseCloseMode } from './closeApprove'

function fakeQ() {
  const handlers: { ok?: (v: unknown) => void; cancel?: () => void } = {}
  let opts: any = null
  const chain = {
    onOk(fn: (v: unknown) => void) { handlers.ok = fn; return chain },
    onCancel(fn: () => void) { handlers.cancel = fn; return chain },
  }
  const dialog = vi.fn((o: unknown) => { opts = o; return chain })
  return { $q: { dialog } as unknown as QVueGlobals, handlers, opts: () => opts, dialog }
}

describe('closeApproveBody', () => {
  it('MERGE는 바디 없음, KEEP_BRANCH는 { keepBranch: true }', () => {
    expect(closeApproveBody('MERGE')).toBeUndefined()
    expect(closeApproveBody('KEEP_BRANCH')).toEqual({ keepBranch: true })
  })
})

describe('chooseCloseMode', () => {
  it('라디오 두 항목·기준 브랜치 이름·기본 MERGE·제목·본문·버튼을 $q.dialog에 넘긴다', async () => {
    const f = fakeQ()
    const p = chooseCloseMode(f.$q, { taskId: 42, baseBranch: 'release/1.0', extraWarning: ' 승인 대기 SQL 2건 — 병합 뒤 실행 필요' })
    const o = f.opts()
    expect(o.title).toBe('종료 승인 — 작업 #42')
    expect(o.message).toContain('병합·배포 중지·브랜치 삭제는 되돌릴 수 없습니다.')
    expect(o.message).toContain('승인 대기 SQL 2건 — 병합 뒤 실행 필요')
    expect(o.persistent).toBe(true)
    expect(o.ok.label).toBe('종료 승인')
    expect(o.cancel.label).toBe('취소')
    expect(o.options.type).toBe('radio')
    expect(o.options.model).toBe('MERGE')
    expect(o.options.items).toEqual([
      { label: '병합하고 종료 — PR을 release/1.0에 병합 · 배포 중지 · 노트 · 브랜치 삭제', value: 'MERGE' },
      { label: '병합하지 않고 종료 — 브랜치·PR 유지 · 배포 중지 · 노트', value: 'KEEP_BRANCH' },
    ])
    f.handlers.ok!('KEEP_BRANCH')
    await expect(p).resolves.toBe('KEEP_BRANCH')
  })

  it('기준 브랜치가 null·공백이면 "기준 브랜치"라고 쓴다', () => {
    const f = fakeQ()
    void chooseCloseMode(f.$q, { taskId: 1, baseBranch: '  ' })
    expect(f.opts().options.items[0].label).toContain('PR을 기준 브랜치에 병합')
  })

  it('취소하면 null', async () => {
    const f = fakeQ()
    const p = chooseCloseMode(f.$q, { taskId: 1, baseBranch: null })
    f.handlers.cancel!()
    await expect(p).resolves.toBeNull()
  })
})
