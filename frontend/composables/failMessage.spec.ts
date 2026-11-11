import { describe, it, expect } from 'vitest'
import { failMsg } from './failMessage'

describe('failMsg (2026-11-11 검토)', () => {
  it('무엇이 실패했는지와 다음에 할 일을 함께 말한다', () => {
    const m = failMsg('삭제')
    expect(m.startsWith('삭제에 실패했습니다.')).toBe(true)
    expect(m).toContain('다시 시도')
  })
})
