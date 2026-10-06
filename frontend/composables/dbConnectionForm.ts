// DB 접속정보 입력 폼의 순수 판정 함수 (DbConnectionDialog). 화면 상태와 떼어 단위 테스트한다.

const REQUIRED = '필수 항목입니다'

export function requiredError(v: string): string | null {
  return v.trim() ? null : REQUIRED
}

/** 호스트 칸에는 호스트만 — 연결 문자열·공백은 접속 테스트 전에 걸러 낸다. */
export function hostError(host: string): string | null {
  const h = host.trim()
  if (!h) return REQUIRED
  if (/^jdbc:/i.test(h) || h.includes('://')) return '호스트만 입력하세요 (예: db.example.com)'
  if (/\s/.test(h)) return '공백 없이 입력하세요'
  return null
}

export function portError(port: number): string | null {
  return Number.isInteger(port) && port >= 1 && port <= 65535 ? null : '1~65535 범위'
}

/**
 * 'host:port'로 붙여 넣은 값을 나눈다. 콜론이 둘 이상인 맨 IPv6(fe80::1)는 나누지 않고,
 * 대괄호 표기([::1]:5432)만 포트를 뗀다. 포트가 숫자가 아니면 그대로 둔다(hostError가 아닌 접속 테스트가 판단).
 */
export function splitHostPort(input: string): { host: string; port?: number } {
  const v = input.trim()
  const bracket = /^\[([^\]]+)\]:(\d{1,5})$/.exec(v)
  if (bracket) return { host: bracket[1]!, port: Number(bracket[2]) }
  const plain = /^([^:\s]+):(\d{1,5})$/.exec(v)
  if (plain) return { host: plain[1]!, port: Number(plain[2]) }
  return { host: v }
}

export interface ConnectionFields {
  dbType: string
  host: string
  port: number
  databaseName: string
  username: string
  password: string
}

/** 접속 결과에 영향을 주는 값만 묶은 키 — 이름을 바꿔도 테스트 결과는 유효하다. */
export function connectionKey(f: ConnectionFields): string {
  return JSON.stringify([f.dbType, f.host.trim(), f.port, f.databaseName.trim(), f.username.trim(), f.password])
}

export type TestState = 'idle' | 'testing' | 'ok' | 'fail' | 'stale'

export function testState(s: {
  testing: boolean
  result: { ok: boolean; message: string } | null
  testedKey: string | null
  currentKey: string
}): TestState {
  if (s.testing) return 'testing'
  if (!s.result) return 'idle'
  if (s.testedKey !== s.currentKey) return 'stale'
  return s.result.ok ? 'ok' : 'fail'
}
