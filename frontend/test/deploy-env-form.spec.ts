import { describe, it, expect } from 'vitest'
import {
  NO_DB_LABEL,
  blankRow,
  choiceCaption,
  configNote,
  dbModelFromSuggestion,
  isSuggestion,
  missingRequired,
  noEnvNotice,
  rowsFromEnvVars,
  rowsFromSuggestion,
  showPreviousDbBadge,
  splitFormat,
  stripFormat,
  suggestionCaption,
  toWireDbSelection,
  toWireEnvVars,
  type DeployEnvSuggestion,
  type EnvRow,
} from '../composables/deployEnvForm'

const seq = () => {
  let n = 0
  return () => n++
}

const suggestion: DeployEnvSuggestion = {
  rows: [
    { key: 'DB_URL', value: 'jdbc:prev', secret: false, description: 'DB 접속 URL', required: true, source: 'TEMPLATE', valueFromPrevious: true },
    { key: 'JWT_SECRET', value: '', secret: true, description: '서명 키', required: true, source: 'TEMPLATE', valueFromPrevious: false },
  ],
  templateCount: 2,
  previousTaskId: 41,
}

describe('deployEnvForm (스펙 2026-10-07 §8)', () => {
  it('추천 응답 판별 — rows 배열이 있어야 한다', () => {
    expect(isSuggestion(suggestion)).toBe(true)
    expect(isSuggestion({ id: 42, envVars: [] })).toBe(false) // 작업 응답 같은 엉뚱한 응답
    expect(isSuggestion(null)).toBe(false)
  })

  it('서버 행은 KEY 고정 + 설명·필수·직전 배포 표식을 싣는다', () => {
    const rows = rowsFromSuggestion(suggestion, seq())
    expect(rows.map((r) => [r.id, r.key, r.fixedKey, r.required, r.valueFromPrevious])).toEqual([
      [0, 'DB_URL', true, true, true],
      [1, 'JWT_SECRET', true, true, false],
    ])
    expect(rows[0]!.description).toBe('DB 접속 URL')
    expect(rows[1]!.secret).toBe(true)
  })

  it('대체 경로(저장 env)와 새 행은 KEY를 편집할 수 있다', () => {
    const rows = rowsFromEnvVars([{ key: 'A', value: '1', secret: true }], seq())
    expect(rows[0]).toMatchObject({ key: 'A', value: '1', secret: true, fixedKey: false, required: false })
    expect(rowsFromEnvVars(null, seq())).toEqual([])
    expect(blankRow(seq())).toMatchObject({ key: '', value: '', fixedKey: false })
  })

  it('전송 형식은 KEY를 trim하고 빈 KEY·빈 값 행을 뺀다(값은 그대로)', () => {
    const rows: EnvRow[] = [
      { ...blankRow(seq()), key: ' DB_URL ', value: ' jdbc:x ' },
      { ...blankRow(seq()), key: 'EMPTY', value: '   ' },
      { ...blankRow(seq()), key: '', value: 'orphan' },
      { ...blankRow(seq()), key: 'PW', value: 'p w', secret: true },
    ]
    expect(toWireEnvVars(rows)).toEqual([
      { key: 'DB_URL', value: ' jdbc:x ', secret: false },
      { key: 'PW', value: 'p w', secret: true },
    ])
  })

  it('필수인데 값이 빈 KEY 목록', () => {
    const rows = rowsFromSuggestion(suggestion, seq())
    expect(missingRequired(rows)).toEqual(['JWT_SECRET'])
    rows[1]!.value = 's'
    expect(missingRequired(rows)).toEqual([])
  })

  it('상단 안내 문구', () => {
    expect(suggestionCaption(suggestion)).toBe('구현 시 추출한 변수 2개 · task #41 배포에서 값을 가져왔습니다')
    expect(suggestionCaption({ rows: [], templateCount: 0, previousTaskId: 41 })).toBe('task #41 배포에서 값을 가져왔습니다')
    expect(suggestionCaption({ rows: [], templateCount: 0, previousTaskId: null })).toBeNull()
  })

  it('변수 없음 안내 — 템플릿·저장값·직전 배포가 모두 없을 때만', () => {
    expect(noEnvNotice({ rows: [], templateCount: 0, previousTaskId: null })).toContain('구현 로그')
    expect(noEnvNotice(suggestion)).toBeNull() // 템플릿 있음
    const saved = { key: 'A', value: '1', secret: false, description: '', required: false, source: 'SAVED' as const, valueFromPrevious: false }
    expect(noEnvNotice({ rows: [saved], templateCount: 0, previousTaskId: null })).toBeNull() // 저장값 있음
    const prev = { ...saved, source: 'PREVIOUS' as const, valueFromPrevious: true }
    expect(noEnvNotice({ rows: [prev], templateCount: 0, previousTaskId: 41 })).toBeNull() // 직전 배포 있음
  })
})

describe('deployEnvForm — DB 고르기·짧은 값', () => {
  const slots = [
    { slot: 'main', label: '메인 DB', dbTypes: ['POSTGRESQL'], targetSummary: 'jdbc', selected: { connectionId: null, from: 'SAVED' as const }, choices: [] },
    { slot: 'sub', label: '보조 DB', dbTypes: ['MYSQL'], targetSummary: 'jdbc', selected: { connectionId: 43, from: 'PREVIOUS' as const }, choices: [] },
    { slot: 'etc', label: '기타', dbTypes: [], targetSummary: '', selected: null, choices: [] },
  ]

  it('splitFormat은 {값} 기준으로 앞뒤를 나눈다', () => {
    expect(splitFormat('https://{값}.x')).toEqual({ prefix: 'https://', suffix: '.x' })
    expect(splitFormat(null)).toBeNull()
    expect(splitFormat(undefined)).toBeNull()
    expect(splitFormat('no-token')).toBeNull()
  })

  it('stripFormat은 앞뒤가 맞을 때만 가운데를 돌려준다', () => {
    expect(stripFormat('https://{값}.x', 'https://a.x')).toBe('a')
    expect(stripFormat('https://{값}.x', 'a')).toBe('a')
    expect(stripFormat(null, 'https://a.x')).toBe('https://a.x')
    expect(stripFormat('{값}', 'a')).toBe('a')
  })

  it('rowsFromSuggestion이_format을_싣는다', () => {
    const s: DeployEnvSuggestion = {
      rows: [{ key: 'U', value: 'a', secret: false, description: '', required: false, source: 'CATALOG', valueFromPrevious: false, format: 'https://{값}.x' }],
      templateCount: 0,
      previousTaskId: null,
    }
    expect(rowsFromSuggestion(s, seq())[0].format).toBe('https://{값}.x')
    expect(rowsFromSuggestion(suggestion, seq())[0].format).toBeNull()
  })

  it('예전_전체값은_가운데만_보인다', () => {
    const mk = (value: string): DeployEnvSuggestion => ({
      rows: [{ key: 'U', value, secret: false, description: '', required: false, source: 'PREVIOUS', valueFromPrevious: true, format: 'https://{값}.x' }],
      templateCount: 0,
      previousTaskId: 1,
    })
    expect(rowsFromSuggestion(mk('https://a.x'), seq())[0].value).toBe('a')
    expect(rowsFromSuggestion(mk('a'), seq())[0].value).toBe('a')
  })

  it('dbModelFromSuggestion은 자리마다 선택값 또는 null', () => {
    expect(dbModelFromSuggestion({ ...suggestion, databases: slots })).toEqual({ main: null, sub: 43, etc: null })
    expect(dbModelFromSuggestion(suggestion)).toEqual({})
  })

  it('toWireDbSelection은 자리 순서대로', () => {
    expect(toWireDbSelection(slots, { sub: 43, main: null })).toEqual([
      { slot: 'main', connectionId: null },
      { slot: 'sub', connectionId: 43 },
      { slot: 'etc', connectionId: null },
    ])
  })

  it('라벨과 캡션', () => {
    expect(NO_DB_LABEL).toBe('고르지 않음(WAR 기본값)')
    expect(choiceCaption({ id: 1, name: 'n', dbType: 'POSTGRESQL', scope: 'REPO', host: 'h', port: 5432, database: 'd' })).toBe('h:5432/d · 공용')
    expect(choiceCaption({ id: 1, name: 'n', dbType: 'POSTGRESQL', scope: 'USER', host: 'h', port: 5432, database: 'd' })).toBe('h:5432/d · 개인')
  })

  it('DB 자리가 있으면 변수 없음 안내를 띄우지 않는다(DB는 고른 연결로 들어간다)', () => {
    expect(noEnvNotice({ rows: [], templateCount: 0, previousTaskId: null, databases: slots })).toBeNull()
    expect(noEnvNotice({ rows: [], templateCount: 0, previousTaskId: null, databases: [] })).toContain('구현 로그')
  })

  it('설정 안내 — 배포 설정이 없으면 null, DB 문장은 DB 자리가 있을 때만', () => {
    expect(configNote(suggestion)).toBeNull()
    expect(configNote({ ...suggestion, databases: [] })).toBeNull()
    const withDb = configNote({ rows: [], templateCount: 0, previousTaskId: null, databases: slots })
    expect(withDb).toContain('DB 비밀번호는 저장하지 않고')
    expect(withDb).toContain('설정 파일이 대상이면')
    const catalogRow = { key: 'APP_URL', value: '', secret: false, description: '', required: false, source: 'CATALOG' as const, valueFromPrevious: false, format: null }
    const varsOnly = configNote({ rows: [catalogRow], templateCount: 0, previousTaskId: null })
    expect(varsOnly).toContain('설정 파일이 대상이면')
    expect(varsOnly).not.toContain('DB 비밀번호')
  })

  it('직전 배포 배지는 그 연결이 지금도 골라져 있을 때만', () => {
    const prev = slots[1] // 직전 배포에서 43
    expect(showPreviousDbBadge(prev, { sub: 43 })).toBe(true)
    expect(showPreviousDbBadge(prev, { sub: 7 })).toBe(false)
    expect(showPreviousDbBadge(prev, { sub: null })).toBe(false)
    expect(showPreviousDbBadge(slots[0], { main: null })).toBe(false) // 저장값(SAVED)은 배지 없음
    expect(showPreviousDbBadge(slots[2], {})).toBe(false) // 선택 없음
    const prevNone = { ...slots[1], selected: { connectionId: null, from: 'PREVIOUS' as const } }
    expect(showPreviousDbBadge(prevNone, {})).toBe(true) // 직전 배포가 '고르지 않음'이었고 지금도 그대로
    expect(showPreviousDbBadge(prevNone, { sub: 43 })).toBe(false)
  })
})
