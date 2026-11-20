import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, afterEach } from 'vitest'
import RequestFormDialog from '../components/requests/RequestFormDialog.vue'
import { useApiMock } from './mocks/nuxt'

afterEach(() => {
  document.querySelectorAll('.q-dialog').forEach((n) => n.remove())
})

const initial: any = {
  id: 42,
  kind: 'REPO',
  kindLabel: '사이트 추가',
  title: '기존 제목',
  content: '기존 내용',
  repoHint: 'old/hint',
  status: 'RECEIVED',
  statusLabel: '접수',
  requesterId: 'hong',
  repoCatalogId: null,
  repoAlias: null,
  repoGitUrl: null,
  repoCatalogDeleted: false,
  editable: true,
  createdAt: '2026-10-19T01:00:00Z',
  updatedAt: '2026-10-19T02:00:00Z',
  comments: [],
}

const devDetail: any = {
  ...initial,
  id: 50,
  kind: 'DEV',
  kindLabel: '개발 요청',
  repoHint: null,
  issueType: 'BUG',
  priority: 'HIGH',
  dueDate: '2026-12-01',
  targetRepoCatalogId: 3,
  attachments: [
    { id: 11, name: 'a.png', sizeBytes: 100, expired: false },
    { id: 12, name: 'b.png', sizeBytes: 100, expired: false },
  ],
}

const catalog = [
  { id: 3, alias: '사이트A', host: 'github.com', ownerRepo: 'o/a', defaultBranch: 'main' },
  { id: 4, alias: '이상한곳', host: 'other', ownerRepo: null, defaultBranch: null },
]

function setVal(sel: string, v: string) {
  const el = document.querySelector(sel) as HTMLInputElement
  el.value = v
  el.dispatchEvent(new Event('input'))
}

describe('RequestFormDialog 개발 요청', () => {
  it('새 요청 기본 종류는 DEV: 개발 요청 칸이 보이고 레포 힌트 칸은 없다', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const w = mount(RequestFormDialog, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()
    expect(document.querySelector('[data-test="request-issue-type"]')).not.toBeNull()
    expect(document.querySelector('[data-test="request-target-repo"]')).not.toBeNull()
    expect(document.querySelector('[data-test="request-priority"]')).not.toBeNull()
    expect(document.querySelector('[data-test="request-due-date"]')).not.toBeNull()
    expect(document.querySelector('[data-test="request-files"]')).not.toBeNull()
    expect(document.querySelector('[data-test="req-hint"]')).toBeNull()
    expect(useApiMock).toHaveBeenCalledWith('/api/repo-catalog')
    w.unmount()
  })

  it('유형을 안 고르면 등록이 비활성이다', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const w = mount(RequestFormDialog, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()
    setVal('[data-test="req-title"]', '제목')
    setVal('[data-test="req-content"]', '내용')
    await flushPromises()
    expect((document.querySelector('[data-test="req-submit"]') as HTMLButtonElement).disabled).toBe(true)
    w.unmount()
  })

  it('DEV 등록은 multipart(meta)로 issueType·priority NORMAL을 보낸다', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const w = mount(RequestFormDialog, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()
    setVal('[data-test="req-title"]', '제목')
    setVal('[data-test="req-content"]', '내용')
    await flushPromises()
    ;(w.vm as any).form.issueType = 'BUG'
    await flushPromises()
    const saved = { id: 1 }
    useApiMock.mockResolvedValueOnce(saved)
    ;(document.querySelector('[data-test="req-submit"]') as HTMLElement).click()
    await flushPromises()

    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/requests')!
    expect(call[1].method).toBe('POST')
    const fd = call[1].body as FormData
    expect(fd).toBeInstanceOf(FormData)
    const meta = JSON.parse(await (fd.get('meta') as Blob).text())
    expect(meta).toMatchObject({ kind: 'DEV', title: '제목', content: '내용', issueType: 'BUG', priority: 'NORMAL' })
    expect(w.emitted('saved')![0][0]).toEqual(saved)
    w.unmount()
  })

  it('활성 레포만 후보로 보인다(other 호스트 제외)', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const w = mount(RequestFormDialog, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()
    const opts = (w.vm as any).repoOptions
    expect(opts.map((o: any) => o.label)).toEqual(['선택 안 함', '사이트A'])
    w.unmount()
  })

  it('수정 중인 요청의 대상 레포가 후보에 없으면 별칭으로 덧붙인다', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const gone = { ...devDetail, targetRepoCatalogId: 99, targetRepoAlias: '비활성 사이트' }
    const w = mount(RequestFormDialog, { props: { modelValue: true, initial: gone }, attachTo: document.body })
    await flushPromises()
    const opts = (w.vm as any).repoOptions
    expect(opts.map((o: any) => [o.label, o.value])).toEqual([
      ['선택 안 함', null],
      ['사이트A', 3],
      ['비활성 사이트', 99],
    ])
    w.unmount()
  })

  it('REPO로 바꾸면 레포 힌트 칸만 보인다', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const w = mount(RequestFormDialog, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()
    ;(w.vm as any).form.kind = 'REPO'
    await flushPromises()
    expect(document.querySelector('[data-test="req-hint"]')).not.toBeNull()
    expect(document.querySelector('[data-test="request-issue-type"]')).toBeNull()
    expect(document.querySelector('[data-test="request-files"]')).toBeNull()
    w.unmount()
  })

  it('수정에서 첨부를 지우면 removeAttachmentIds로 가고 multipart다', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const w = mount(RequestFormDialog, { props: { modelValue: true, initial: devDetail }, attachTo: document.body })
    await flushPromises()
    ;(document.querySelector('[data-test="request-attachment-remove-11"]') as HTMLElement).click()
    await flushPromises()
    useApiMock.mockResolvedValueOnce(devDetail)
    ;(document.querySelector('[data-test="req-submit"]') as HTMLElement).click()
    await flushPromises()
    const call = useApiMock.mock.calls.find((c) => c[0] === '/api/requests/50')!
    expect(call[1].method).toBe('PUT')
    const meta = JSON.parse(await ((call[1].body as FormData).get('meta') as Blob).text())
    expect(meta.removeAttachmentIds).toEqual([11])
    expect(meta.issueType).toBe('BUG')
    expect(meta.dueDate).toBe('2026-12-01')
    expect(meta.targetRepoCatalogId).toBe(3)
    w.unmount()
  })

  it('남은 첨부 + 새 파일이 10개를 넘으면 등록이 비활성이고 문구가 뜬다', async () => {
    useApiMock.mockResolvedValueOnce(catalog)
    const w = mount(RequestFormDialog, { props: { modelValue: true, initial: devDetail }, attachTo: document.body })
    await flushPromises()
    ;(w.vm as any).newFiles = Array.from({ length: 9 }, (_, i) => new File(['x'], `f${i}.txt`))
    await flushPromises()
    expect(document.body.textContent).toContain('첨부는 최대 10개입니다')
    expect((document.querySelector('[data-test="req-submit"]') as HTMLButtonElement).disabled).toBe(true)
    w.unmount()
  })
})

describe('RequestFormDialog edit', () => {
  it('수정은 PUT /api/requests/{id}로 kind 없이 보내고 응답 객체를 saved로 내보낸다', async () => {
    const w = mount(RequestFormDialog, { props: { modelValue: true, initial }, attachTo: document.body })
    await flushPromises()

    // 종류는 잠겨 있고 기존 값이 채워져 있다
    const title = document.querySelector('[data-test="req-title"]') as HTMLInputElement
    expect(title.value).toBe('기존 제목')
    const hint = document.querySelector('[data-test="req-hint"]') as HTMLInputElement
    expect(hint.value).toBe('old/hint')
    title.value = '새 제목'
    title.dispatchEvent(new Event('input'))
    hint.value = 'new/hint'
    hint.dispatchEvent(new Event('input'))
    await flushPromises()

    const response = { ...initial, title: '새 제목', repoHint: 'new/hint' }
    useApiMock.mockResolvedValueOnce(response)
    ;(document.querySelector('[data-test="req-submit"]') as HTMLElement).click()
    await flushPromises()

    expect(useApiMock).toHaveBeenCalledWith('/api/requests/42', {
      method: 'PUT',
      body: { title: '새 제목', content: '기존 내용', repoHint: 'new/hint' },
    })
    const body = useApiMock.mock.calls[0][1].body
    expect('kind' in body).toBe(false)
    expect(w.emitted('saved')![0][0]).toEqual(response)
    expect(w.emitted('update:modelValue')!.at(-1)).toEqual([false])
    w.unmount()
  })
})
