import { describe, it, expect } from 'vitest'
import {
  KIND_OPTIONS,
  KIND_FILTER_OPTIONS,
  ISSUE_TYPE_OPTIONS,
  PRIORITY_OPTIONS,
  MAX_REQUEST_FILES,
  requestFormData,
  taskPrefillFromRequest,
  type RequestDetail,
} from '../composables/requestBoard'
import type { RepoCatalogOption } from '../composables/refRepos'

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

describe('taskPrefillFromRequest', () => {
  const CATALOG: RepoCatalogOption[] = [
    { id: 1, alias: 'Netis7.0', host: 'github', defaultBranch: 'develop' },
    { id: 4, alias: '지원 안 함', host: 'other', defaultBranch: null },
  ]
  function req(over: Partial<RequestDetail> = {}): RequestDetail {
    return {
      id: 7,
      kind: 'DEV',
      kindLabel: '개발 요청',
      title: '대상장비 탭 오류',
      content: '탭을 누르면 빈 화면이 나옵니다',
      repoHint: null,
      status: 'RECEIVED',
      statusLabel: '접수',
      requesterId: 'kim',
      repoCatalogId: null,
      repoAlias: null,
      repoGitUrl: null,
      repoCatalogDeleted: false,
      issueType: 'BUG',
      issueTypeLabel: '버그',
      priority: 'HIGH',
      priorityLabel: '▲ 높음',
      dueDate: null,
      targetRepoCatalogId: 1,
      targetRepoAlias: 'Netis7.0',
      taskId: null,
      taskStatusLabel: null,
      attachments: [],
      editable: false,
      cancellable: false,
      convertible: true,
      createdAt: '2026-11-19T00:00:00Z',
      updatedAt: '2026-11-19T00:00:00Z',
      comments: [],
      ...over,
    }
  }

  it('버그면 BUG_FIX, 개발요청이면 타입을 비운다', () => {
    expect(taskPrefillFromRequest(req(), CATALOG).taskType).toBe('BUG_FIX')
    expect(taskPrefillFromRequest(req({ issueType: 'DEV' }), CATALOG).taskType).toBeNull()
    expect(taskPrefillFromRequest(req({ issueType: 'INQUIRY' }), CATALOG).taskType).toBeNull()
  })

  it('레포: 활성 카탈로그에 있고 지원 호스트일 때만, 아니면 비운다', () => {
    expect(taskPrefillFromRequest(req(), CATALOG).repoCatalogId).toBe(1)
    expect(taskPrefillFromRequest(req({ targetRepoCatalogId: 99 }), CATALOG).repoCatalogId).toBeNull()
    expect(taskPrefillFromRequest(req({ targetRepoCatalogId: 4 }), CATALOG).repoCatalogId).toBeNull()
    expect(taskPrefillFromRequest(req({ targetRepoCatalogId: null }), CATALOG).repoCatalogId).toBeNull()
  })

  it('제목과 설명 꼬리 문자열', () => {
    const p = taskPrefillFromRequest(req(), CATALOG)
    expect(p.title).toBe('대상장비 탭 오류')
    expect(p.description).toBe('탭을 누르면 빈 화면이 나옵니다\n\n— 요청 #7 · 버그 · 우선순위 ▲ 높음 · 요청자 kim')
    const d = taskPrefillFromRequest(req({ dueDate: '2026-12-01' }), CATALOG)
    expect(d.description).toBe('탭을 누르면 빈 화면이 나옵니다\n\n— 요청 #7 · 버그 · 우선순위 ▲ 높음 · 마감 2026-12-01 · 요청자 kim')
  })

  it('첨부 수는 만료되지 않은 것만 센다', () => {
    const attachments = [
      { id: 1, name: 'a', sizeBytes: 1, expired: false },
      { id: 2, name: 'b', sizeBytes: 1, expired: true },
      { id: 3, name: 'c', sizeBytes: 1, expired: false },
    ]
    expect(taskPrefillFromRequest(req({ attachments }), CATALOG).attachmentCount).toBe(2)
  })
})
