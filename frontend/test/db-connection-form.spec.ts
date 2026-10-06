import { describe, it, expect } from 'vitest'
import {
  connectionKey, hostError, portError, requiredError, splitHostPort, testState,
} from '../composables/dbConnectionForm'

describe('dbConnectionForm 순수 함수 (DB 접속정보 입력 실수 방지)', () => {
  it('splitHostPort는 host:port를 나누고, 포트가 없거나 IPv6면 그대로 둔다', () => {
    expect(splitHostPort('10.1.0.156:55432')).toEqual({ host: '10.1.0.156', port: 55432 })
    expect(splitHostPort(' db.local:3306 ')).toEqual({ host: 'db.local', port: 3306 })
    expect(splitHostPort('[::1]:5432')).toEqual({ host: '::1', port: 5432 })
    expect(splitHostPort('db.local')).toEqual({ host: 'db.local' })
    expect(splitHostPort('fe80::1')).toEqual({ host: 'fe80::1' })
    expect(splitHostPort('db.local:abc')).toEqual({ host: 'db.local:abc' })
  })

  it('hostError는 빈 값·URL·공백을 거른다', () => {
    expect(hostError('')).toBe('필수 항목입니다')
    expect(hostError('   ')).toBe('필수 항목입니다')
    expect(hostError('jdbc:postgresql://h:5432/app')).toBe('호스트만 입력하세요 (예: db.example.com)')
    expect(hostError('postgres://h/app')).toBe('호스트만 입력하세요 (예: db.example.com)')
    expect(hostError('db local')).toBe('공백 없이 입력하세요')
    expect(hostError('db.local')).toBeNull()
    expect(hostError('10.1.0.156')).toBeNull()
  })

  it('portError는 1~65535 정수만 받는다', () => {
    expect(portError(5432)).toBeNull()
    expect(portError(1)).toBeNull()
    expect(portError(65535)).toBeNull()
    expect(portError(0)).toBe('1~65535 범위')
    expect(portError(65536)).toBe('1~65535 범위')
    expect(portError(3.5)).toBe('1~65535 범위')
    expect(portError(Number.NaN)).toBe('1~65535 범위')
  })

  it('requiredError는 공백만 있어도 비었다고 본다', () => {
    expect(requiredError(' ')).toBe('필수 항목입니다')
    expect(requiredError('a')).toBeNull()
  })

  it('connectionKey는 이름을 빼고 접속에 쓰이는 값만 묶는다', () => {
    const base = { name: 'a', dbType: 'POSTGRESQL', host: 'h', port: 5432, databaseName: 'd', username: 'u', password: 'p' }
    const renamed = { ...base, name: 'b' }
    expect(connectionKey(base)).toBe(connectionKey(renamed))
    expect(connectionKey(base)).not.toBe(connectionKey({ ...base, host: 'h2' }))
    expect(connectionKey(base)).not.toBe(connectionKey({ ...base, password: 'q' }))
  })

  it('testState는 진행 중 > 결과 없음 > 입력 바뀜 > 성공/실패 순으로 판정한다', () => {
    expect(testState({ testing: true, result: null, testedKey: null, currentKey: 'k' })).toBe('testing')
    expect(testState({ testing: false, result: null, testedKey: null, currentKey: 'k' })).toBe('idle')
    expect(testState({ testing: false, result: { ok: true, message: '' }, testedKey: 'k', currentKey: 'k' })).toBe('ok')
    expect(testState({ testing: false, result: { ok: false, message: '' }, testedKey: 'k', currentKey: 'k' })).toBe('fail')
    expect(testState({ testing: false, result: { ok: true, message: '' }, testedKey: 'k', currentKey: 'k2' })).toBe('stale')
    expect(testState({ testing: false, result: { ok: false, message: '' }, testedKey: 'k', currentKey: 'k2' })).toBe('stale')
  })
})
