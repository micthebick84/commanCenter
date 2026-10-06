import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import DbConnectionDialog from '../components/DbConnectionDialog.vue'
import { fetchDbConnections, type DbConnectionView } from '../composables/dbConnections'
import { useApiMock } from './mocks/nuxt'
import { setViewportWidth } from './mocks/screen'

const editing: DbConnectionView = {
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

// UI 개선(2026-10-06): 종류 카드·섹션·접속 테스트 상태·입력 검증. q-dialog는 body 포털 — document.body에서 찾는다.
describe('DbConnectionDialog — 화면 구성과 입력 실수 방지', () => {
  const q = (sel: string) => document.body.querySelector(sel) as HTMLElement | null
  const input = (sel: string) => q(sel) as HTMLInputElement // q-input은 data-test를 <input>에 붙인다
  const field = (sel: string) => q(sel)!.closest('.q-field') as HTMLElement
  async function open(props: Record<string, unknown> = {}) {
    const w = mount(DbConnectionDialog, {
      props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing: null, ...props },
      attachTo: document.body,
    })
    await flushPromises()
    return w
  }
  async function type(sel: string, v: string) {
    const el = input(sel)
    el.value = v
    el.dispatchEvent(new Event('input'))
    await flushPromises()
  }
  // 실제 브라우저처럼 focus→blur(focusin/focusout 동반). QField는 focusout을 타이머로 미뤄 처리한다.
  async function blur(sel: string) {
    input(sel).focus()
    input(sel).blur()
    await new Promise((r) => setTimeout(r, 50))
    await flushPromises()
  }

  beforeEach(() => {
    useApiMock.mockReset()
    useApiMock.mockResolvedValue({ id: 9 })
  })
  afterEach(async () => {
    await setViewportWidth(1024)
    document.body.innerHTML = ''
  })

  it('DB 종류는 카드 4개 중 하나를 눌러 고른다', async () => {
    const w = await open()
    const cards = document.body.querySelectorAll('[data-test^="db-type-"]')
    expect(Array.from(cards).map((c) => c.getAttribute('data-test'))).toEqual([
      'db-type-POSTGRESQL', 'db-type-MYSQL', 'db-type-MARIADB', 'db-type-ORACLE',
    ])
    expect(q('[data-test="db-type-POSTGRESQL"]')!.getAttribute('aria-pressed')).toBe('true')
    q('[data-test="db-type-ORACLE"]')!.click()
    await flushPromises()
    expect((w.vm as any).form.dbType).toBe('ORACLE')
    expect(q('[data-test="db-type-ORACLE"]')!.getAttribute('aria-pressed')).toBe('true')
    expect((w.vm as any).form.port).toBe(1521)
    w.unmount()
  })

  it('호스트에 host:port를 붙여 넣고 벗어나면 호스트와 포트로 나뉜다', async () => {
    const w = await open()
    await type('[data-test="db-host"]', '10.1.0.156:55432')
    await blur('[data-test="db-host"]')
    const vm = w.vm as any
    expect(vm.form.host).toBe('10.1.0.156')
    expect(vm.form.port).toBe(55432)
    vm.form.dbType = 'MYSQL' // 붙여 넣은 포트는 사용자가 고른 값 — 종류를 바꿔도 유지
    await flushPromises()
    expect(vm.form.port).toBe(55432)
    w.unmount()
  })

  it('연결 문자열·범위 밖 포트면 칸 아래에 오류를 띄우고 저장을 막는다', async () => {
    const w = await open()
    const vm = w.vm as any
    Object.assign(vm.form, { name: '운영', databaseName: 'app', username: 'reader', password: 'pw' })
    await type('[data-test="db-host"]', 'jdbc:postgresql://h:5432/app')
    await blur('[data-test="db-host"]')
    expect(field('[data-test="db-host"]').textContent).toContain('호스트만 입력하세요')
    expect(vm.canSubmit).toBe(false)
    await type('[data-test="db-host"]', 'db.local')
    expect(vm.canSubmit).toBe(true)
    vm.onPortInput(70000)
    await flushPromises()
    expect(vm.canSubmit).toBe(false)
    w.unmount()
  })

  it('비밀번호 보기 버튼으로 가린 글자를 볼 수 있다', async () => {
    const w = await open()
    expect(input('[data-test="db-password"]').type).toBe('password')
    q('[data-test="db-password-toggle"]')!.click()
    await flushPromises()
    expect(input('[data-test="db-password"]').type).toBe('text')
    w.unmount()
  })

  it('접속 테스트 상태: 미실행 → 성공 → 이름만 바꾸면 유지, 접속값을 바꾸면 다시 테스트 안내', async () => {
    const w = await open()
    const vm = w.vm as any
    Object.assign(vm.form, { name: '운영', host: 'db.local', databaseName: 'app', username: 'reader', password: 'pw' })
    await flushPromises()
    expect(q('[data-test="db-test-status"]')!.getAttribute('data-state')).toBe('idle')
    useApiMock.mockResolvedValueOnce({ ok: true, message: '접속 성공' })
    await vm.runTest()
    await flushPromises()
    expect(q('[data-test="db-test-status"]')!.getAttribute('data-state')).toBe('ok')
    vm.form.name = '운영2'
    await flushPromises()
    expect(q('[data-test="db-test-status"]')!.getAttribute('data-state')).toBe('ok')
    vm.form.host = 'db2.local'
    await flushPromises()
    const status = q('[data-test="db-test-status"]')!
    expect(status.getAttribute('data-state')).toBe('stale')
    expect(status.textContent).toContain('다시 테스트')
    w.unmount()
  })

  it('테스트가 실패한 상태에서는 저장 버튼이 "그래도 저장"으로 바뀌지만 저장은 된다', async () => {
    const w = await open()
    const vm = w.vm as any
    Object.assign(vm.form, { name: '운영', host: 'db.local', databaseName: 'app', username: 'reader', password: 'pw' })
    expect(q('[data-test="form-submit"]')!.textContent).toContain('저장')
    expect(q('[data-test="form-submit"]')!.textContent).not.toContain('그래도')
    useApiMock.mockResolvedValueOnce({ ok: false, message: '접속 실패: Connection refused' })
    await vm.runTest()
    await flushPromises()
    expect(q('[data-test="db-test-status"]')!.textContent).toContain('Connection refused')
    expect(q('[data-test="form-submit"]')!.textContent).toContain('그래도 저장')
    expect(q('[data-test="form-submit"]')!.classList.contains('bg-warning')).toBe(true)
    await vm.save()
    expect(useApiMock).toHaveBeenLastCalledWith('/api/db-connections', expect.objectContaining({ method: 'POST' }))
    w.unmount()
  })

  it('모바일(390)과 데스크톱이 같은 입력 구성을 쓴다', async () => {
    const ids = () => Array.from(document.body.querySelectorAll('.q-dialog [data-test]')).map((e) => e.getAttribute('data-test')).filter((t) => t!.startsWith('db-'))
    const d = await open()
    const desktop = ids()
    d.unmount()
    document.body.innerHTML = ''
    await setViewportWidth(390)
    const m = await open()
    expect(document.body.querySelector('.q-dialog__inner--maximized')).not.toBeNull()
    expect(ids()).toEqual(desktop)
    m.unmount()
  })
})

describe('DbConnectionDialog — 오류 표시 회귀 (2026-10-06 실화면)', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('포트 오류가 뜬 뒤 host:port 분리로 포트가 바르게 바뀌면 오류가 사라진다', async () => {
    useApiMock.mockReset()
    const w = mount(DbConnectionDialog, {
      props: { modelValue: true, scope: 'USER', repoCatalogId: 1, editing: null },
      attachTo: document.body,
    })
    await flushPromises()
    const el = (s: string) => document.body.querySelector(s) as HTMLInputElement
    const leave = async (s: string) => { el(s).focus(); el(s).blur(); await new Promise((r) => setTimeout(r, 50)); await flushPromises() }
    const set = async (s: string, v: string) => { el(s).value = v; el(s).dispatchEvent(new Event('input')); await flushPromises() }
    const portField = () => el('[data-test="db-port"]').closest('.q-field') as HTMLElement

    await set('[data-test="db-port"]', '70000')
    await leave('[data-test="db-port"]')
    expect(portField().classList.contains('q-field--error')).toBe(true)
    await set('[data-test="db-host"]', '10.1.0.156:55432')
    await leave('[data-test="db-host"]')
    expect((w.vm as any).form.port).toBe(55432)
    expect(portField().classList.contains('q-field--error')).toBe(false)
    w.unmount()
  })
})
