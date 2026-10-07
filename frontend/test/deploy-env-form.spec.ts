import { describe, it, expect } from 'vitest'
import {
  blankRow,
  isSuggestion,
  missingRequired,
  rowsFromEnvVars,
  rowsFromSuggestion,
  suggestionCaption,
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
})
