import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import DbConnectionAdminDialog from '../components/DbConnectionAdminDialog.vue'
import { useApiMock } from './mocks/nuxt'

const row = (id: number, scope: 'REPO' | 'USER', enabled = true) => ({
  id, scope, repoCatalogId: 1, name: `c${id}`, dbType: 'POSTGRESQL', host: 'h', port: 5432,
  databaseName: 'app', username: 'u', enabled, mine: false,
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
})
