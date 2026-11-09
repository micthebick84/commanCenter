// 배포된 앱 로그 실시간 보기 — 순수 함수 (스펙 2026-11-03 §4·§5). 화면 상태·EventSource는 없다(useAppLogStream·AppLogCard).

/**
 * 앱 로그를 볼 수 있는 작업 상태. API `AppLogPolicy.VIEWABLE_STATUSES`와 같아야 한다 — appLog.spec.ts가 그 소스를 읽어 대조한다.
 * 열린 수정 회차가 있으면 이 목록 밖의 상태(구현중 등)여도 볼 수 있다(`appLogViewable`).
 */
export const APP_LOG_VIEWABLE_STATUSES: readonly string[] = [
  'DEPLOYED',
  'DEPLOY_LOST',
  'FIX_REVIEW',
  'CLOSE_REVIEW',
  'CLOSE_PENDING',
  'CLOSING',
  'CLOSE_FAILED',
]

/** 재배포·중지가 진행 중이라 컨테이너가 바뀌거나 내려가는 상태 — 열린 회차가 있어도 앱 로그를 닫는다(빌드 로그가 이어받는다). API `IN_FLIGHT_EXCLUDED`와 같다. */
export const APP_LOG_EXCLUDED_STATUSES: readonly string[] = [
  'DEPLOY_PENDING',
  'DEPLOYING',
  'UNDEPLOY_PENDING',
  'UNDEPLOYING',
]

/**
 * 이 작업의 앱 로그 카드를 보여 줄지. API `AppLogPolicy.isViewable`과 같은 세 조건이다:
 * (1) 허용 상태이거나 열린 수정 회차가 있다 (2) 제외 상태가 아니다 (3) 배포 기록(주소 또는 시각)이 있다.
 * 프론트는 `active_fix_request_id`를 볼 수 없어 `fix.activeRound`로 열린 회차를 판정한다 — id는 있는데 요청 기록이 없는
 * 드문 경우에만 API(허용)와 화면(숨김)이 갈리고, 화면이 더 엄격한 쪽이다.
 * 목록 응답처럼 `deployment`·`fix`가 없는 작업은 배포 기록이 없는 것으로 본다.
 */
export function appLogViewable(
  task:
    | {
        status: string
        deployment?: { deployUrl: string | null; deployedAt: string | null } | null
        fix?: { activeRound: number | null } | null
      }
    | null
    | undefined,
): boolean {
  if (!task) return false
  if (APP_LOG_EXCLUDED_STATUSES.includes(task.status)) return false
  const openRound = task.fix?.activeRound != null
  if (!APP_LOG_VIEWABLE_STATUSES.includes(task.status) && !openRound) return false
  return !!(task.deployment?.deployUrl || task.deployment?.deployedAt)
}

/** 줄 강조 등급 — null은 강조 없음. */
export type AppLogLevel = 'error' | 'warn' | null

// 스펙 §5 강조 규칙. 오류가 경고보다 앞선다('WARN x Exception'은 오류).
const ERROR_LINE =
  /\b(ERROR|FATAL|SEVERE)\b|Exception|Caused by:|SQLException|ORA-\d+|SQLSTATE|ERROR \d+|ERROR:/
const WARN_LINE = /\bWARN(ING)?\b/

export function lineLevel(text: string): AppLogLevel {
  if (ERROR_LINE.test(text)) return 'error'
  if (WARN_LINE.test(text)) return 'warn'
  return null
}

/** SSE `lines` 이벤트의 줄. ts는 docker `--timestamps`가 붙인 시각(9자리 고정 소수부·Z)이라 문자열 비교가 시각 순서와 같다. */
export interface AppLogWireLine {
  ts: string
  stream: 'out' | 'err'
  text: string
}

/** 화면이 들고 있는 줄 — seq는 목록 key(밀려나도 재사용하지 않는다), level은 강조 등급. */
export interface AppLogViewLine extends AppLogWireLine {
  seq: number
  level: AppLogLevel
}

/** 브라우저에 두는 최대 줄 수 — 넘으면 오래된 줄부터 버린다(스펙 §5). */
export const APP_LOG_MAX_LINES = 5000

/**
 * 카드가 DOM으로 그리는 최대 줄 수 — 버퍼(위 5000줄)와 복사·내려받기는 그대로 두고 화면에는 끝 2000줄만 그린다(판정 T10).
 * 5000행을 300ms 묶음마다 다시 그리면 폰 폭·CPU 4배 감속에서 묶음당 p50 230~290ms·p95 730ms로 스크롤·버튼이 끊긴다(2000행은 약 63ms).
 */
export const APP_LOG_RENDER_LINES = 2000

/**
 * 들어온 줄을 기존 줄 뒤에 잇는다. `cutoffTs`가 있으면 ts가 그 이하인 줄(같은 ts 포함)은 버린다 — 다시 연결하면 서버가 tail부터
 * 다시 보내므로, 마지막으로 받은 ts 이하는 이미 화면에 있다. 버린 줄은 seq를 쓰지 않는다. 결과는 끝 `max`줄이고,
 * `max`를 넘어 밀려난 줄의 seq도 이미 소비된 것으로 `nextSeq`는 계속 늘어난다. 붙일 줄이 없으면 `old`를 그대로 돌려준다.
 */
export function appendLines(
  old: AppLogViewLine[],
  incoming: AppLogWireLine[],
  cutoffTs: string | null,
  nextSeq: number,
  max = APP_LOG_MAX_LINES,
): { lines: AppLogViewLine[]; nextSeq: number } {
  let seq = nextSeq
  const added: AppLogViewLine[] = []
  for (const l of incoming) {
    if (cutoffTs != null && l.ts <= cutoffTs) continue
    added.push({ ts: l.ts, stream: l.stream, text: l.text, seq: seq++, level: lineLevel(l.text) })
  }
  if (added.length === 0) return { lines: old, nextSeq }
  const merged = old.length + added.length > max ? [...old, ...added].slice(-max) : [...old, ...added]
  return { lines: merged, nextSeq: seq }
}

/**
 * 앱 로그 SSE 주소. `/api/**` 밖이다 — `/api/**`는 routeRules 프록시가 서버 라우트보다 먼저 받아 Nitro 라우트가 가려진다
 * (server/routes/stream/tasks/[id]/app-logs.get.ts). EventSource는 헤더를 못 실어 토큰을 쿼리로 보낸다.
 */
export function appLogStreamUrl(taskId: number | string, token: string): string {
  return `/stream/tasks/${encodeURIComponent(String(taskId))}/app-logs?access_token=${encodeURIComponent(token)}`
}

const pad = (n: number, width = 2) => String(n).padStart(width, '0')

/**
 * 줄 시각을 로컬 `HH:mm:ss.SSS`로. 못 읽으면 ts를 그대로 돌려준다. Intl은 쓰지 않고 직접 조립한다(Node ICU 편차 —
 * TaskHistoryTimeline.vue의 formatKoDateTime 선례). docker의 9자리 소수부는 밀리초까지만 남기고 파싱한다 —
 * 3자리를 넘는 소수부의 파싱은 브라우저마다 다르다.
 */
export function formatLogTime(ts: string): string {
  const d = new Date(ts.replace(/(\.\d{3})\d+/, '$1'))
  if (Number.isNaN(d.getTime())) return ts
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
}

/** 복사·내려받기 본문 — 줄마다 `[ts] 본문`(원본 ts 그대로). */
export function appLogText(lines: AppLogViewLine[]): string {
  return lines.map((l) => `[${l.ts}] ${l.text}\n`).join('')
}

/** 내려받기 파일명 `task-{id}-app-yyyyMMdd-HHmmss.log` — 로컬 시각, 콜론 없음(Windows 파일명 금지 문자). */
export function appLogFileName(taskId: number | string, now: Date): string {
  const date = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
  const time = `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `task-${taskId}-app-${date}-${time}.log`
}

/**
 * 클라이언트가 스스로 만든 끝남 사유의 문구 둘. 서버 사유의 문구는 여기 두지 않는다 — 서버가 늘 `message`를 싣고,
 * `time-limit` 문구의 분 수는 클라이언트가 모른다.
 */
export const APP_LOG_END_TEXT: Record<string, string> = {
  'connection-lost': '연결이 끊겼습니다',
  auth: '다시 로그인한 뒤 연결하세요',
}

/** 서버 message도 클라이언트 사유 문구도 없을 때(모르는 사유). */
export const APP_LOG_END_FALLBACK = '앱 로그 연결이 끝났습니다'

/** 끝남 문구 — 서버 `message`가 먼저, 다음이 클라이언트 전용 사유 표, 마지막이 일반 문구. */
export function appLogEndText(reason: string | null, message: string | null): string {
  // 서버가 보낸 reason이 'constructor' 같은 Object.prototype 멤버 이름이어도 함수가 문구로 새지 않게 own 키만 본다.
  const own =
    reason != null && Object.prototype.hasOwnProperty.call(APP_LOG_END_TEXT, reason) ? APP_LOG_END_TEXT[reason] : undefined
  return message ?? own ?? APP_LOG_END_FALLBACK
}
