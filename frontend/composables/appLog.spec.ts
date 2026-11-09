import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'
import {
  APP_LOG_VIEWABLE_STATUSES,
  APP_LOG_EXCLUDED_STATUSES,
  APP_LOG_MAX_LINES,
  APP_LOG_END_FALLBACK,
  APP_LOG_END_TEXT,
  appLogViewable,
  lineLevel,
  appendLines,
  appLogStreamUrl,
  formatLogTime,
  appLogText,
  appLogFileName,
  appLogEndText,
  type AppLogViewLine,
  type AppLogWireLine,
} from './appLog'

// 순수 함수 — 판정·강조·줄 합치기·파일명 (스펙 2026-11-03 §4·§5). 화면·EventSource 없음.

// `<이름> = EnumSet.of(…)` 안의 TaskStatus.XXX를 모아 정렬한다 — AppLogPolicy.java 소스를 읽어 프론트 목록과 대조하는 용도.
function namesIn(src: string, name: string): string[] {
  const m = new RegExp(`${name}\\s*=\\s*EnumSet\\.of\\(([^)]*)\\)`).exec(src)
  expect(m, `${name} 선언을 AppLogPolicy.java에서 찾지 못함`).not.toBeNull()
  return [...m![1].matchAll(/TaskStatus\.([A-Z_]+)/g)].map((x) => x[1]).sort()
}

const deployed = { deployUrl: 'https://task-42.example.dev', deployedAt: null }
const wire = (ts: string, text = 'x', stream: 'out' | 'err' = 'out'): AppLogWireLine => ({ ts, stream, text })
const view = (seq: number, ts = `2026-11-03T01:02:03.${String(seq).padStart(9, '0')}Z`): AppLogViewLine => ({
  seq,
  ts,
  stream: 'out',
  text: `line ${seq}`,
  level: null,
})

describe('appLog (스펙 2026-11-03 §4·§5)', () => {
  it('허용 상태·열린 회차·배포 기록', () => {
    expect(appLogViewable({ status: 'DEPLOYED', deployment: deployed })).toBe(true)
    expect(appLogViewable({ status: 'DEPLOYED', deployment: null })).toBe(false)
    expect(appLogViewable({ status: 'DEPLOYED' })).toBe(false) // 목록 응답처럼 deployment 칸이 없다
    expect(appLogViewable({ status: 'DEPLOYED', deployment: { deployUrl: null, deployedAt: null } })).toBe(false)
    // 열린 수정 회차가 있으면 상태가 허용 목록 밖(구현중)이어도 보인다
    expect(appLogViewable({ status: 'IMPLEMENTING', fix: { activeRound: 1 }, deployment: deployed })).toBe(true)
    expect(appLogViewable({ status: 'IMPLEMENTING', fix: { activeRound: null }, deployment: deployed })).toBe(false)
    // 재배포 진행 중이면 열린 회차가 있어도 닫는다 — 빌드 로그가 이어받는다
    expect(appLogViewable({ status: 'DEPLOYING', fix: { activeRound: 1 }, deployment: deployed })).toBe(false)
    // 배포 시각만 있어도 배포 기록이다
    expect(
      appLogViewable({ status: 'CLOSE_FAILED', deployment: { deployUrl: null, deployedAt: '2026-11-03T01:02:03Z' } }),
    ).toBe(true)
    expect(appLogViewable({ status: 'PR_CREATED', deployment: deployed })).toBe(false)
    expect(appLogViewable({ status: 'DEPLOY_FAILED', deployment: deployed })).toBe(false)
    expect(appLogViewable(null)).toBe(false)
    expect(appLogViewable(undefined)).toBe(false)
  })

  it('허용 상태마다, 제외 상태마다 판정이 목록과 같다', () => {
    for (const s of APP_LOG_VIEWABLE_STATUSES) expect(appLogViewable({ status: s, deployment: deployed }), s).toBe(true)
    for (const s of APP_LOG_EXCLUDED_STATUSES) {
      expect(appLogViewable({ status: s, deployment: deployed }), s).toBe(false)
      expect(appLogViewable({ status: s, fix: { activeRound: 2 }, deployment: deployed }), `${s}+회차`).toBe(false)
    }
    expect(APP_LOG_VIEWABLE_STATUSES).toHaveLength(7)
    expect(APP_LOG_EXCLUDED_STATUSES).toHaveLength(4)
  })

  it('허용·제외 상태 목록이 API AppLogPolicy.java와 같다', () => {
    const src = readFileSync(
      resolve(__dirname, '../../src/main/java/com/hamonsoft/netismaker/service/applog/AppLogPolicy.java'),
      'utf8',
    )
    expect(namesIn(src, 'VIEWABLE_STATUSES')).toEqual([...APP_LOG_VIEWABLE_STATUSES].sort())
    expect(namesIn(src, 'IN_FLIGHT_EXCLUDED')).toEqual([...APP_LOG_EXCLUDED_STATUSES].sort())
  })

  it('강조', () => {
    for (const t of [
      'ERROR o.h.e.j.s.SqlExceptionHelper',
      'Caused by: x',
      'ORA-00942: table',
      'SQLSTATE[42S22]',
      'ERROR 1054 (42S22)',
      'ERROR:  column "x" does not exist',
      'java.sql.SQLException',
      'FATAL boot',
      'SEVERE: y',
      'NullPointerException',
    ])
      expect(lineLevel(t), t).toBe('error')
    expect(lineLevel('WARN a')).toBe('warn')
    expect(lineLevel('WARNING b')).toBe('warn')
    expect(lineLevel('INFO ERRORS=0')).toBeNull()
    expect(lineLevel('WARN x Exception')).toBe('error')
    expect(lineLevel('')).toBeNull()
    expect(lineLevel('WARNED about nothing')).toBeNull() // \bWARN(ING)?\b — 단어 경계
  })

  it('다시 연결 뒤 cutoff 이하 줄은 버린다(같은 ts 포함)', () => {
    // ts는 docker --timestamps의 9자리 고정·Z 형식이라 문자열 비교가 시각 순서와 같다
    const t = (n: number) => `2026-11-03T01:02:03.00000000${n}Z`
    const { lines, nextSeq } = appendLines(
      [],
      [wire(t(1), 'old'), wire(t(2), 'same'), wire(t(3), 'new'), wire(t(4), 'newer')],
      t(2),
      10,
    )
    expect(lines.map((l) => l.text)).toEqual(['new', 'newer'])
    expect(lines.map((l) => l.seq)).toEqual([10, 11]) // 버린 줄은 seq를 쓰지 않는다
    expect(nextSeq).toBe(12)
    // cutoff가 없으면 모두 붙는다
    expect(appendLines([], [wire(t(1)), wire(t(2))], null, 0).lines).toHaveLength(2)
  })

  it('줄에 seq와 강조 등급을 붙이고 기존 줄 뒤에 잇는다', () => {
    const old = [view(0)]
    const { lines, nextSeq } = appendLines(
      old,
      [wire('2026-11-03T01:02:04.000000000Z', 'ERROR boom', 'err'), wire('2026-11-03T01:02:05.000000000Z', 'WARN hmm')],
      null,
      1,
    )
    expect(lines).toHaveLength(3)
    expect(lines[0]).toBe(old[0])
    expect(lines[1]).toEqual({ seq: 1, ts: '2026-11-03T01:02:04.000000000Z', stream: 'err', text: 'ERROR boom', level: 'error' })
    expect(lines[2]).toMatchObject({ seq: 2, level: 'warn', stream: 'out' })
    expect(nextSeq).toBe(3)
    expect(old).toHaveLength(1) // 입력 배열은 바꾸지 않는다
  })

  it('붙일 줄이 없으면 그대로 돌려준다', () => {
    const old = [view(0)]
    const r = appendLines(old, [], null, 1)
    expect(r.lines).toBe(old)
    expect(r.nextSeq).toBe(1)
    const r2 = appendLines(old, [wire('2026-11-03T01:02:03.000000000Z')], '2026-11-03T01:02:04.000000000Z', 1)
    expect(r2.lines).toBe(old)
    expect(r2.nextSeq).toBe(1)
  })

  it('끝남 문구는 서버 message가 먼저', () => {
    expect(appLogEndText('time-limit', '30분이 지나 연결을 닫았습니다')).toBe('30분이 지나 연결을 닫았습니다')
    expect(appLogEndText('connection-lost', null)).toBe('연결이 끊겼습니다')
    expect(appLogEndText('auth', null)).toBe('다시 로그인한 뒤 연결하세요')
    expect(appLogEndText('x', null)).toBe('앱 로그 연결이 끝났습니다')
    expect(appLogEndText(null, null)).toBe(APP_LOG_END_FALLBACK)
    expect(appLogEndText('connection-lost', '서버 문구')).toBe('서버 문구') // message가 사유 표보다 앞선다
    // 서버 사유 문구는 복사하지 않는다 — 클라이언트 전용 사유 둘만 둔다
    expect(Object.keys(APP_LOG_END_TEXT).sort()).toEqual(['auth', 'connection-lost'])
    // 서버가 보낸 사유 이름이 Object.prototype 멤버와 같아도 함수가 문구로 새지 않는다
    expect(appLogEndText('constructor', null)).toBe(APP_LOG_END_FALLBACK)
    expect(appLogEndText('__proto__', null)).toBe(APP_LOG_END_FALLBACK)
  })

  it('5000줄을 넘으면 오래된 줄부터 버리고 seq는 계속 늘어난다', () => {
    expect(APP_LOG_MAX_LINES).toBe(5000)
    const old = Array.from({ length: 4990 }, (_, i) => view(i))
    const incoming = Array.from({ length: 20 }, (_, i) => wire(`2026-11-04T00:00:00.${String(i).padStart(9, '0')}Z`, `n${i}`))
    const { lines, nextSeq } = appendLines(old, incoming, null, 4990)
    expect(lines).toHaveLength(5000)
    expect(lines[0].seq).toBe(10) // 앞 10줄이 밀려났다
    expect(lines[4979].seq).toBe(4989) // 남은 옛 줄의 마지막
    expect(lines[4980]).toMatchObject({ seq: 4990, text: 'n0' }) // 새 줄의 첫째 — seq는 이어서 늘어난다
    expect(lines[4999]).toMatchObject({ seq: 5009, text: 'n19' })
    expect(nextSeq).toBe(5010)
    // 한 번에 상한보다 많이 와도 끝 max줄만 남고 seq는 모두 소비한다
    const huge = Array.from({ length: 12 }, (_, i) => wire(`2026-11-04T00:00:01.${String(i).padStart(9, '0')}Z`, `h${i}`))
    const r = appendLines([], huge, null, 100, 5)
    expect(r.lines.map((l) => l.text)).toEqual(['h7', 'h8', 'h9', 'h10', 'h11'])
    expect(r.lines.map((l) => l.seq)).toEqual([107, 108, 109, 110, 111])
    expect(r.nextSeq).toBe(112)
  })

  it('URL은 /api 밖, 토큰은 인코딩', () => {
    expect(appLogStreamUrl(42, 'a b+c')).toBe('/stream/tasks/42/app-logs?access_token=a%20b%2Bc')
    expect(appLogStreamUrl('7', 'x')).toBe('/stream/tasks/7/app-logs?access_token=x')
  })

  it('파일명은 로컬 시각, 콜론 없음', () => {
    expect(appLogFileName(42, new Date(2026, 10, 3, 14, 5, 9))).toBe('task-42-app-20261103-140509.log')
    expect(appLogFileName('7', new Date(2026, 0, 2, 3, 4, 5))).toBe('task-7-app-20260102-030405.log') // 한 자리 월·일·시·분·초는 0을 채운다
  })

  it('복사·내려받기 본문은 [ts] 본문 줄', () => {
    const lines: AppLogViewLine[] = [
      { seq: 0, ts: '2026-11-03T01:02:03.000000001Z', stream: 'out', text: 'hello', level: null },
      { seq: 1, ts: '2026-11-03T01:02:04.000000002Z', stream: 'err', text: '', level: null },
      { seq: 2, ts: '2026-11-03T01:02:05.000000003Z', stream: 'err', text: 'ERROR x', level: 'error' },
    ]
    expect(appLogText(lines)).toBe(
      '[2026-11-03T01:02:03.000000001Z] hello\n[2026-11-03T01:02:04.000000002Z] \n[2026-11-03T01:02:05.000000003Z] ERROR x\n',
    )
    expect(appLogText([])).toBe('')
  })

  it('formatLogTime은 로컬 HH:mm:ss.SSS, 못 읽으면 그대로', () => {
    // 로컬 시각에서 ISO를 만들어 시간대와 무관하게 같은 값을 기대한다
    const iso = new Date(2026, 10, 3, 14, 5, 9, 7).toISOString() // 2026-11-03T..:..:09.007Z
    expect(formatLogTime(iso)).toBe('14:05:09.007')
    // docker의 9자리 소수부도 밀리초까지만 읽는다(브라우저마다 9자리 파싱이 다르다)
    expect(formatLogTime(iso.replace(/\.\d{3}Z$/, '.007123456Z'))).toBe('14:05:09.007')
    expect(formatLogTime('아무말')).toBe('아무말')
    expect(formatLogTime('')).toBe('')
  })
})
