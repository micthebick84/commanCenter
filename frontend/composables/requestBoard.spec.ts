import { describe, it, expect } from 'vitest'
import { adminTransitions, KIND_OPTIONS, STATUS_COLOR } from './requestBoard'

describe('requestBoard', () => {
  it('adminTransitions: 처리완료는 검토중으로만 다시 열 수 있다', () => {
    expect(adminTransitions('DONE')).toEqual(['REVIEWING'])
    expect(adminTransitions('REJECTED')).toEqual(['REVIEWING'])
  })

  it('adminTransitions: 접수는 검토중·처리완료·반려로', () => {
    expect(adminTransitions('RECEIVED')).toEqual(['REVIEWING', 'DONE', 'REJECTED'])
  })

  it('adminTransitions: 검토중은 처리완료·반려·접수로', () => {
    expect(adminTransitions('REVIEWING')).toEqual(['DONE', 'REJECTED', 'RECEIVED'])
  })

  it('종류 라벨과 상태 색', () => {
    expect(KIND_OPTIONS.map((o) => o.label)).toEqual(['기능 추가', 'MCP 추가', 'REPO 추가'])
    expect(STATUS_COLOR.RECEIVED).toBe('grey')
    expect(STATUS_COLOR.REVIEWING).toBe('blue')
    expect(STATUS_COLOR.DONE).toBe('positive')
    expect(STATUS_COLOR.REJECTED).toBe('negative')
  })
})
