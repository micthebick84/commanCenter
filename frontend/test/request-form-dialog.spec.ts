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
  kindLabel: 'REPO 추가',
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
