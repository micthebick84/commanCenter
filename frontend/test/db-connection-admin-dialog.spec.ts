import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import DbConnectionAdminDialog from '../components/DbConnectionAdminDialog.vue'
import { useApiMock } from './mocks/nuxt'

const row = (id: number, scope: 'REPO' | 'USER', enabled = true, allowWrite = false) => ({
  id, scope, repoCatalogId: 1, name: `c${id}`, dbType: 'POSTGRESQL', host: 'h', port: 5432,
  databaseName: 'app', username: 'u', enabled, mine: false, allowWrite,
})

describe('DbConnectionAdminDialog — 레포 공용 접속정보 관리', () => {
  beforeEach(() => {
    useApiMock.mockReset()
    useApiMock.mockImplementation((url: string) =>
      url === '/api/db-connections?repoCatalogId=1'
        ? Promise.resolve({ enabled: true, items: [row(1, 'REPO'), row(2, 'REPO', false), row(3, 'USER')] })
        : Promise.resolve({}),
    )
  })

  it('공용(REPO)만 보이고, 활성 토글은 비밀번호 없이 PUT한다', async () => {
    const w = mount(DbConnectionAdminDialog, { props: { modelValue: true, repo: { id: 1, alias: 'Netis7.0' } } })
    await flushPromises()
    const vm = w.vm as any
    expect(vm.rows.map((r: any) => r.id)).toEqual([1, 2])
    await vm.toggleEnabled(vm.rows[0])
    expect(useApiMock).toHaveBeenCalledWith('/api/db-connections/1', {
      method: 'PUT',
      body: { scope: 'REPO', repoCatalogId: 1, name: 'c1', dbType: 'POSTGRESQL', host: 'h', port: 5432, databaseName: 'app', username: 'u', password: '', enabled: false },
    })
    w.unmount()
  })

  it('기능이 꺼져 있으면 안내만 보인다', async () => {
    useApiMock.mockImplementation(() => Promise.resolve({ enabled: false, items: [] }))
    const w = mount(DbConnectionAdminDialog, { props: { modelValue: true, repo: { id: 1, alias: 'Netis7.0' } } })
    await flushPromises()
    expect((w.vm as any).featureEnabled).toBe(false)
    w.unmount()
  })

  // 구현 단계 개발 DB 쓰기 허용 (스펙 2026-11-03 §6.1 조건 4) — 관리자만 이 화면에서 켜고 끈다
  describe('쓰기 허용', () => {
    const q = (sel: string) => document.body.querySelector(sel) as HTMLElement | null
    async function open(items: unknown[], extra: Record<string, unknown> = {}) {
      useApiMock.mockImplementation((url: string) =>
        url === '/api/db-connections?repoCatalogId=1'
          ? Promise.resolve({ enabled: true, items, ...extra })
          : Promise.resolve({}),
      )
      const w = mount(DbConnectionAdminDialog, {
        props: { modelValue: true, repo: { id: 1, alias: 'Netis7.0' } },
        attachTo: document.body,
      })
      await flushPromises()
      return w
    }
    afterEach(() => { document.body.innerHTML = '' })

    it('쓰기 허용 토글은 allow-write 경로로 PUT하고 목록을 다시 읽는다', async () => {
      const w = await open([row(7, 'REPO')])
      q('[data-test="db-allow-write-7"]')!.click()
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/db-connections/7/allow-write', { method: 'PUT', body: { allowWrite: true } })
      expect(useApiMock).toHaveBeenLastCalledWith('/api/db-connections?repoCatalogId=1')
      w.unmount()
    })

    it('이미 켜진 연결의 토글은 끄는 값을 보낸다', async () => {
      const w = await open([row(7, 'REPO', true, true)])
      q('[data-test="db-allow-write-7"]')!.click()
      await flushPromises()
      expect(useApiMock).toHaveBeenCalledWith('/api/db-connections/7/allow-write', { method: 'PUT', body: { allowWrite: false } })
      w.unmount()
    })

    it('활성 토글 바디에는 allowWrite가 없다', async () => {
      const w = await open([row(7, 'REPO', true, true)])
      useApiMock.mockClear()
      await (w.vm as any).toggleEnabled((w.vm as any).rows[0])
      const put = useApiMock.mock.calls.find((c: any[]) => c[0] === '/api/db-connections/7')!
      expect(put[1].method).toBe('PUT')
      expect(put[1].body).not.toHaveProperty('allowWrite')
      w.unmount()
    })

    it('실패하면 서버 메시지로 알린다', async () => {
      const w = await open([row(7, 'REPO')])
      const notify = vi.spyOn((w.vm as any).$q, 'notify')
      useApiMock.mockRejectedValueOnce({ data: { message: '개인 연결에는 쓰기 허용을 켤 수 없습니다' } })
      q('[data-test="db-allow-write-7"]')!.click()
      await flushPromises()
      expect(notify).toHaveBeenCalledWith({ type: 'negative', message: '개인 연결에는 쓰기 허용을 켤 수 없습니다' })
      w.unmount()
    })

    it('쓰기가 지금 열리지 않는 레포(writeEnabled=false)면 이유를 안내한다', async () => {
      const off = await open([row(7, 'REPO', true, true)], { writeEnabled: false })
      expect(q('[data-test="db-admin-write-off"]')).not.toBeNull()
      off.unmount()
      document.body.innerHTML = ''
      const on = await open([row(7, 'REPO', true, true)], { writeEnabled: true })
      expect(q('[data-test="db-admin-write-off"]')).toBeNull()
      on.unmount()
      document.body.innerHTML = ''
      const unknown = await open([row(7, 'REPO', true, true)]) // 옛 응답에는 칸이 없다 — 모르면 안내하지 않는다
      expect(q('[data-test="db-admin-write-off"]')).toBeNull()
      unknown.unmount()
    })
  })
})
