import { describe, it, expect } from 'vitest'
import {
  KIND_OPTIONS,
  KIND_FILTER_OPTIONS,
  ISSUE_TYPE_OPTIONS,
  PRIORITY_OPTIONS,
  MAX_REQUEST_FILES,
  requestFormData,
} from '../composables/requestBoard'

describe('requestBoard 개발 요청 상수·함수', () => {
  it('KIND_OPTIONS는 등록 폼용: 개발 요청·사이트 추가만(FEATURE·MCP 없음)', () => {
    expect(KIND_OPTIONS).toEqual([
      { value: 'DEV', label: '개발 요청' },
      { value: 'REPO', label: '사이트 추가' },
    ])
    expect(KIND_OPTIONS.some((o) => o.value === 'FEATURE' || o.value === 'MCP')).toBe(false)
  })

  it('KIND_FILTER_OPTIONS는 예전 종류도 포함한다', () => {
    expect(KIND_FILTER_OPTIONS.map((o) => o.label)).toEqual(['개발 요청', '사이트 추가', '기능 추가(예전)', 'MCP 추가(예전)'])
  })

  it('유형·우선순위 선택지', () => {
    expect(ISSUE_TYPE_OPTIONS.map((o) => o.value)).toEqual(['BUG', 'DEV', 'INQUIRY'])
    expect(PRIORITY_OPTIONS.map((o) => o.value)).toEqual(['HIGH', 'NORMAL', 'LOW'])
    expect(MAX_REQUEST_FILES).toBe(10)
  })

  it('requestFormData는 meta(JSON Blob)와 files를 담는다', async () => {
    const f1 = new File(['a'], 'a.txt')
    const f2 = new File(['b'], 'b.txt')
    const fd = requestFormData({ title: '제목' }, [f1, f2])
    const meta = fd.get('meta') as Blob
    expect(meta.type).toBe('application/json')
    expect(JSON.parse(await meta.text())).toEqual({ title: '제목' })
    expect(fd.getAll('files').map((x) => (x as File).name)).toEqual(['a.txt', 'b.txt'])
  })

  it('파일이 없어도 meta만 담긴다', () => {
    const fd = requestFormData({ a: 1 }, [])
    expect(fd.get('meta')).not.toBeNull()
    expect(fd.getAll('files')).toEqual([])
  })
})
