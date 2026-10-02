import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach } from 'vitest'
import DbConnectionDialog from '../components/DbConnectionDialog.vue'
import { fetchDbConnections } from '../composables/dbConnections'
import { useApiMock } from './mocks/nuxt'

const editing = {
  id: 7, scope: 'USER', repoCatalogId: 1, name: '내 DB', dbType: 'MYSQL', host: 'h', port: 3306,
  databaseName: 'app', username: 'u', enabled: true, mine: true,
}

describe('DbConnectionDialog (스펙 2026-10-02 §7)', () => {
  beforeEach(() => {
    useApiMock.mockReset()
    useApiMock.mockResolvedValue({ id: 9 })
  })

  it('종류를 바꾸면 손대지 않은 포트는 기본값으로, Oracle이면 라벨이 서비스명', async () => {
    const w = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing: null } })
    await flushPromises()
    const vm = w.vm as any
    expect(vm.form.port).toBe(5432)
    vm.form.dbType = 'ORACLE'
    await flushPromises()
    expect(vm.form.port).toBe(1521)
    expect(vm.dbNameLabel).toBe('서비스명')
    vm.onPortInput(1600)
    vm.form.dbType = 'MYSQL'
    await flushPromises()
    expect(vm.form.port).toBe(1600) // 사용자가 고친 포트는 유지
    w.unmount()
  })

  it('새로 저장하면 POST, 수정은 PUT이고 비밀번호는 다시 채우지 않는다', async () => {
    const w = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing: null } })
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.form, { name: '운영', host: 'db.local', databaseName: 'app', username: 'reader', password: 'pw' })
    await vm.save()
    expect(useApiMock).toHaveBeenCalledWith('/api/db-connections', {
      method: 'POST',
      body: { scope: 'USER', repoCatalogId: 1, name: '운영', dbType: 'POSTGRESQL', host: 'db.local', port: 5432, databaseName: 'app', username: 'reader', password: 'pw' },
    })
    expect(w.emitted('saved')).toBeTruthy()
    w.unmount()

    const e = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing } })
    await flushPromises()
    const evm = e.vm as any
    expect(evm.form.password).toBe('')
    expect(evm.canSubmit).toBe(true) // 수정은 비밀번호 없이 저장 가능(=유지)
    await evm.save()
    expect(useApiMock).toHaveBeenLastCalledWith('/api/db-connections/7', expect.objectContaining({ method: 'PUT' }))
    e.unmount()
  })

  it('수정 중 접속 테스트는 id를 함께 보내고 결과를 보여 준다', async () => {
    useApiMock.mockResolvedValueOnce({ ok: false, message: '접속 실패: refused' })
    const w = mount(DbConnectionDialog, { props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing } })
    await flushPromises()
    const vm = w.vm as any
    await vm.runTest()
    expect(useApiMock).toHaveBeenCalledWith('/api/db-connections/test', {
      method: 'POST',
      body: { id: 7, dbType: 'MYSQL', host: 'h', port: 3306, databaseName: 'app', username: 'u', password: '' },
    })
    expect(vm.testResult).toEqual({ ok: false, message: '접속 실패: refused' })
    w.unmount()
  })

  it('fetchDbConnections는 실패·이상 응답을 꺼짐으로 본다', async () => {
    useApiMock.mockRejectedValueOnce(new Error('503'))
    expect(await fetchDbConnections(1)).toEqual({ enabled: false, items: [] })
    useApiMock.mockResolvedValueOnce(null)
    expect(await fetchDbConnections(1)).toEqual({ enabled: false, items: [] })
    useApiMock.mockResolvedValueOnce({ enabled: true, items: [editing] })
    expect((await fetchDbConnections(1)).items).toHaveLength(1)
    expect(useApiMock).toHaveBeenLastCalledWith('/api/db-connections?repoCatalogId=1')
  })
})
