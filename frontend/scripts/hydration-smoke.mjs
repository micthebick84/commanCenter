// SSR 하이드레이션 회귀 스모크 (TODOS "하이드레이션 회귀 스모크를 저장소/CI에 편입", 2026-10-08).
//
// vitest(happy-dom)는 SSR도 Quasar CSS도 없어 하이드레이션 불일치를 못 잡는다. 이 스크립트는 프로덕션 빌드
// (.output)를 실제로 띄우고 설치된 Chrome으로 화면 폭 × 로그인 여부 × 폭 힌트 쿠키 × 경로를 돌며, 콘솔의
// 하이드레이션 경고("Hydration completed but contains mismatches" 등)와 잡히지 않은 예외가 0건인지 본다.
//
//   npm run build && npm run test:hydration
//
// - 브라우저는 내려받지 않고 설치된 것을 쓴다(playwright-core). 기본 채널 chrome — 이 PC·GitHub ubuntu 러너 모두 있다.
//   다른 채널: SMOKE_BROWSER_CHANNEL=msedge. 실행 파일 지정: SMOKE_BROWSER_PATH=<경로>.
// - API는 브라우저에서 page.route로 목킹한다. pathname이 /api/로 시작하는 요청만 잡는다 — `**/api/**` 글롭은
//   `/_nuxt/…/api/…` 모듈까지 잡는다. 서버 쪽 프록시 타깃은 닫힌 포트로 돌려 실제 백엔드를 절대 두드리지 않는다.
// - 로그인 상태는 localStorage 토큰 주입(stores/auth.ts의 netis-maker-auth). 토큰은 가짜라 서버 검증 없음.
// - 쿠키 없는 첫 로드는 SSR이 좁은 화면으로 그렸다가 하이드레이션 뒤 실제 폭으로 바뀐다 — 경고 없는 의도된 전환(ssrScreen.ts).
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { chromium } from 'playwright-core'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const ENTRY = join(ROOT, '.output', 'server', 'index.mjs')
const PORT = Number(process.env.SMOKE_PORT ?? 3123)
const BASE = `http://127.0.0.1:${PORT}`
const SETTLE_MS = Number(process.env.SMOKE_SETTLE_MS ?? 800)

const VIEWPORTS = [
  { name: '1280', width: 1280, height: 800 },
  { name: '820', width: 820, height: 1180 },
  { name: '390', width: 390, height: 844 },
]
const ANON_PATHS = ['/login', '/tasks', '/questions', '/requests']
const QUESTION_PATHS = ['/questions', '/questions/3']
const AUTH_PATHS = [
  '/',
  '/login',
  '/tasks',
  // 질문 대화에서 이어서 등록 — 마운트 뒤 질문을 읽어 창을 열고 쿼리를 뺀다(서버 렌더는 쿼리를 보지 않는다)
  '/tasks?fromQuestion=3',
  '/tasks/1',
  // DB 작업 PR생성(스펙 2026-11-03 §6.9) — 관리자 상세가 주 행동 '확인 완료 — 종료 요청'과 보조 배포 버튼(deploy-secondary)을 그린다
  '/tasks/11',
  // 작업종료 + 부분 패치(스펙 2026-11-10) — 상세(1280/390)의 종료 카드 패치 줄·내려받기 버튼, 보드·모바일 카드의 부분 패치 버튼
  '/tasks/12',
  '/questions',
  '/questions/3',
  '/requests',
  '/requests/1',
  // 디자인 목업 크게 보기(blank 레이아웃) — 작업 1의 design 목업을 마운트 뒤에 읽어 sandbox iframe에 그린다
  '/designs/1',
  // 인터뷰 목업 새 탭 보기(blank 레이아웃) — 마운트 뒤에 단건·목록을 읽어 sandbox iframe에 그린다
  '/interviews/7/mockups/1',
  '/admin/repo-catalog',
  '/admin/mcp-catalog',
  '/admin/workers',
]

// ── API 목 ────────────────────────────────────────────────────────────────────
const NOW = new Date().toISOString()
const task = (id, status, statusLabel, extra = {}) => ({
  id, githubRepo: 'acme/widgets', repoAlias: 'Widgets', githubBranch: 'main', title: `스모크 작업 ${id}`,
  description: '하이드레이션 스모크용 작업', status, statusLabel, requesterId: 'admin', retryCount: 0, maxRetry: 3,
  failureReason: null, mcpsExtra: [], envVars: [], createdAt: NOW, updatedAt: NOW, model: 'claude-opus-5-5',
  effort: 'medium', designRequested: false, analysis: null, design: null, implementation: null, deployment: null, close: null,
  interviewSessionId: null, attachments: [], stageUsage: [], totalCostUsd: null, totalTokens: null,
  implementationMode: 'DIRECT', implementationAttempt: 1, claimedAt: null, implementationTimeoutMinutes: null, ...extra,
})
// 읽기 전용 참조 repo — 커밋 고정·아직 안 받음·실패 각 1개: 질문 대화 화면과 작업 상세의 칩 줄이 세 상태(고정 칩의 툴팁·대기 안내·⚠)를 모두 그린다
const REF_REPOS = [
  { catalogId: 2, alias: '옛앱', branch: 'release/6.5', sha: 'a1b2c3d4e5f60718293a4b5c6d7e8f9012345678', fetchedAt: NOW, error: null },
  { catalogId: 3, alias: '공통 UI', branch: 'main', sha: null, fetchedAt: null, error: null },
  { catalogId: 5, alias: '인증 서버', branch: 'develop', sha: null, fetchedAt: null, error: 'fatal: Remote branch develop not found in upstream origin' },
]
const TASKS = [
  task(1, 'PR_CREATED', 'PR생성', {
    implementation: { prUrl: 'https://github.com/acme/widgets/pull/1', prNumber: 1, headBranch: 'netismaker/task-1', headSha: 'abc123', implementationLog: '완료' },
    totalCostUsd: 1.23,
    // 작업 상세의 "인터뷰 목업 — 구현 기준" 절 — 마운트 뒤에 목록을 받아 링크만 그린다
    interviewSessionId: 7,
    implementationMockupIds: [1],
    // 작업 타입 — 보드 카드·모바일 카드·상세 머리줄(1280)·요청 상세 섹션 머리(390)의 타입 칩이 같은 마크업으로 하이드레이션돼야 한다(나머지 작업은 타입 없음 = 칩 없음)
    taskType: 'NEW_FEATURE',
    // 간단 인터뷰(superpowers 끔) — 상세 머리줄(1280)의 칩과 정보 섹션(390)의 줄이 같은 마크업으로 하이드레이션돼야 한다(나머지 작업은 칸 없음 = 켬 = 칩 없음)
    interviewSuperpowersEnabled: false,
    // 수정 요청 회차 현황 — 작업 상세가 수정 요청 기록 카드를 마운트 뒤에 불러와 그린다(위 fix-requests 목)
    fix: { activeRound: null, activeMode: null, approvedRounds: 1, maxRounds: 3 },
    // 작업 상세의 참조 칩 줄(DB 칩 다음)과 "레포" 옆 출처 질문 링크(/questions/3)
    refRepos: REF_REPOS,
    sourceQuestionId: 3,
    // 디자인 목업 — 작업 상세의 디자인 카드와 /designs/1 크게 보기가 그린다
    design: {
      designMarkdown: '# 디자인',
      mockupFilesJson: JSON.stringify([{ path: 'screens/main.html', title: '메인', html: '<p>목업</p>' }]),
      designProjectId: null, designUrl: null, rejectCount: 0, feedbackHistoryJson: '[]',
      approved: true, approvedBy: 'admin', approvedAt: NOW, completedAt: NOW,
    },
  }),
  task(2, 'AWAITING_APPROVAL', '승인대기'),
  // DB 작업 PR생성 — 배포 없이 종료를 요청할 수 있다(스펙 2026-11-03 §6.9): 상세(1280)의 주 행동 종료 요청과 보조 배포 버튼, 390px 하단 바의 종료 요청, 보드·카드의 DB 작업 칩
  task(11, 'PR_CREATED', 'PR생성', {
    implementation: { prUrl: 'https://github.com/acme/widgets/pull/11', prNumber: 11, headBranch: 'netismaker/task-11', headSha: 'def456', implementationLog: '완료' },
    taskType: 'DB_WORK',
  }),
  // 수정승인대기 — 보드 배포 단계 그룹과 칩 색(스펙 2026-11-03 §6)
  task(10, 'FIX_REVIEW', '수정승인대기', { fix: { activeRound: null, activeMode: null, approvedRounds: 0, maxRounds: 3 } }),
  task(3, 'CANCELLED', '취소됨'),
  // 작업종료 + 부분 패치 — 종료 카드의 패치 줄(파일 이름·크기·파일 수·내려받기)과 목록 카드의 부분 패치 버튼(등록자 admin = 로그인 사용자)
  task(12, 'CLOSED', '작업종료', {
    close: {
      progress: ['MERGED', 'PATCH_BUILT', 'UNDEPLOYED', 'NOTE_WRITTEN', 'BRANCH_DELETED'], mergeCommitSha: 'abcdef1234567', notePath: null,
      artifactMode: null, artifact: null, mail: null, patchMode: 'REQUIRED',
      patch: { name: 'patch-task-12.zip', sizeBytes: 3145728, fileCount: 7, empty: false, uploadedAt: NOW },
    },
  }),
  // 구현중 작업 — 경과 문구는 마운트 뒤에만 그려지므로(useNowTick) 하이드레이션 불일치가 없어야 한다
  task(9, 'IMPLEMENTING', '구현중', { implementationMode: 'REVIEW', claimedAt: NOW, implementationTimeoutMinutes: 80 }),
]
const MOCKUPS = [
  { id: 1, n: 1, name: 'a-plan', version: 1, title: 'A안', description: '왼쪽 메뉴형', turnSeq: 1, turnKind: 'design', createdAt: NOW },
  { id: 2, n: 2, name: 'b-plan', version: 1, title: 'B안', description: null, turnSeq: 1, turnKind: 'question', createdAt: NOW },
]
const QUESTION = {
  id: 3, title: '인증 흐름 확인', githubRepo: 'acme/widgets', githubBranch: 'main', repoAlias: 'Widgets',
  requesterId: 'admin', status: '입력대기', statusName: 'AWAITING_INPUT', model: 'claude-sonnet-5-5', effort: 'medium',
  kind: 'QUESTION', totalCostUsd: 0.42, contextTokens: 76004, contextWindow: 200000, mcpCatalogIds: [],
  dbConnectionIds: [], attachments: [], createdAt: NOW, updatedAt: NOW,
  // 대상 레포의 카탈로그 id — "이 대화로 작업 등록"이 열리고, 등록 창 프리필이 대상·브랜치를 채운다
  repoCatalogId: 1,
  // 읽기 전용 참조 repo — 대화 화면 칩 줄 (위 REF_REPOS, 작업 1과 같은 값)
  refRepos: REF_REPOS,
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '**AuthController**입니다' }], plan: null,
}
// 2·3은 질문의 참조 — 5(인증 서버)는 일부러 빼서 등록 창 프리필의 "카탈로그에 없는 참조" 알림 경로도 지난다
const REPOS = [
  { id: 1, alias: 'Widgets', gitUrl: 'https://github.com/acme/widgets', host: 'github', ownerRepo: 'acme/widgets', defaultBranch: 'main', description: null, enabled: true, createdBy: 'admin', createdAt: NOW, updatedAt: NOW },
  { id: 2, alias: '옛앱', gitUrl: 'https://gitlab.example/legacy/app', host: 'gitlab', ownerRepo: null, defaultBranch: 'release/6.5', description: null, enabled: true, createdBy: 'admin', createdAt: NOW, updatedAt: NOW },
  { id: 3, alias: '공통 UI', gitUrl: 'https://gitlab.example/shared/ui', host: 'gitlab', ownerRepo: null, defaultBranch: 'main', description: null, enabled: true, createdBy: 'admin', createdAt: NOW, updatedAt: NOW },
]
const MCPS = [{ id: 1, name: 'docs', displayName: 'Docs', url: 'https://mcp.example/sse', transport: 'sse', description: null, enabled: true, createdBy: 'admin', createdAt: NOW, updatedAt: NOW, lastCheckAt: null, lastCheckStatus: null, lastCheckError: null }]
const REQUESTS = [{ id: 1, kind: 'REPO', kindLabel: 'REPO 추가', title: '스모크 요청', status: 'RECEIVED', statusLabel: '접수', requesterId: 'admin', unread: true, createdAt: NOW, updatedAt: NOW }]
const REQUEST_DETAIL = {
  ...REQUESTS[0], content: '스모크 **본문**', repoHint: null, repoCatalogId: null, repoAlias: null, repoGitUrl: null,
  repoCatalogDeleted: false, editable: true,
  comments: [
    { id: 1, kind: 'COMMENT', authorId: 'admin', authorAdmin: true, body: '검토 **시작**', createdAt: NOW },
    { id: 2, kind: 'SYSTEM', authorId: 'admin', authorAdmin: true, body: '상태가 검토중으로 바뀌었습니다', createdAt: NOW },
  ],
}
const WORKERS = [
  { workerId: 'smoke-worker-1', hostname: 'smoke', version: '1.0', lastSeenAt: NOW, alive: true, claudeSessionOk: true, claudeAuthMethod: 'ENV_TOKEN', claudeAuthExpiresAt: null, vpnStatus: null, mcps: ['docs'], lostReportCount: 0 },
  { workerId: 'smoke-interview-1', hostname: 'smoke', version: 'interview-service/0.1.0', lastSeenAt: NOW, alive: true, claudeSessionOk: false, claudeAuthMethod: 'CREDENTIALS', claudeAuthExpiresAt: '2026-10-31T02:23:45.460Z', vpnStatus: null, mcps: [], lostReportCount: 0 },
]

const USAGE_TOKENS = { input: 1000, output: 200, cacheWrite5m: 0, cacheWrite1h: 0, cacheRead: 5000 }
const USAGE_SUMMARY = {
  period: 'month', date: '2026-11-04', start: '2026-11-01T00:00:00+09:00', end: '2026-12-01T00:00:00+09:00', countFrom: '2026-10-01',
  inProgress: true, tokens: USAGE_TOKENS, cost: 1234,
  byCategory: [{ category: 'SYMPHONY', tokens: USAGE_TOKENS, cost: 900 }, { category: 'DIRECT', tokens: USAGE_TOKENS, cost: 334 }],
  byModel: [{ model: 'claude-smoke-1', tokens: USAGE_TOKENS, cost: 1234 }],
  feeConfigured: true, feeMissing: false, feeShare: 200, fullPeriodFeeShare: 1000, ratio: 6.2, previous: null,
  series: [{ at: '2026-11-01T00:00:00+09:00', cost: 10 }, { at: '2026-11-02T00:00:00+09:00', cost: 20 }],
  coverage: { collectors: [{ machine: 'smoke-pc', lastReportedAt: NOW, lastEventAt: null, unreadableLines: 0 }], earliestEventAt: '2026-10-03T09:12:00+09:00', unpricedModels: [] },
}

/** pathname → 응답 본문. 목록에 없는 /api/ 요청은 null(200)로 답하고 보고서에 남긴다. */
function apiResponse(path) {
  if (path === '/api/me') return { user_id: 1, username: 'admin', email: 'admin@example.com', authorities: ['ROLE_USER', 'ROLE_ADMIN'] }
  if (path === '/api/tasks') return { content: TASKS, totalElements: TASKS.length, totalPages: 1, number: 0, size: 50 }
  const t = path.match(/^\/api\/tasks\/(\d+)$/)
  if (t) return TASKS.find((x) => x.id === Number(t[1])) ?? null
  if (/^\/api\/tasks\/\d+\/(history|interviews)$/.test(path)) return []
  // 수정 요청 기록(최신순) — 작업 상세 카드
  if (/^\/api\/tasks\/\d+\/fix-requests$/.test(path)) {
    return [{
      id: 1, round: 1, status: 'APPROVED', implementationMode: 'REVIEW', body: '스모크 수정 요청 **본문**', requestedBy: 'admin', requestedAt: NOW,
      decidedBy: 'admin', decidedAt: NOW, decisionReason: null, attempt: 1, outcome: 'DEPLOYED', closedAt: NOW,
    }]
  }
  // DB 변경 기록(시간순, 스펙 2026-11-03 §6.5) — 작업 상세 카드. 작업 1만 실행됨 1·승인 대기 1(관리자 실행·실행 안 함 버튼), 나머지는 빈 목록(카드 없음)
  const dbc = path.match(/^\/api\/tasks\/(\d+)\/db-changes$/)
  if (dbc) {
    if (Number(dbc[1]) !== 1) return []
    const base = { connectionId: 1, connectionName: '개발 DB', attempt: 1, fixRequestId: null, reason: null, affectedRows: null, error: null, createdAt: NOW, executedBy: null }
    return [
      { ...base, id: 1, sqlText: 'CREATE TABLE t_smoke (id int)', classification: 'EXECUTED', executedAt: NOW },
      { ...base, id: 2, sqlText: 'ALTER TABLE t_old DROP COLUMN c_old', classification: 'DEFERRED', reason: '남의 테이블 컬럼 삭제는 승인 대기로 기록됩니다', executedAt: null },
    ]
  }
  // 인터뷰 목업 — 목록(2건)과 단건(html 포함, taskId 1)
  if (/^\/api\/interviews\/\d+\/mockups$/.test(path)) return MOCKUPS
  const mk = path.match(/^\/api\/interviews\/\d+\/mockups\/(\d+)$/)
  if (mk) {
    const m = MOCKUPS.find((x) => x.id === Number(mk[1]))
    return m ? { ...m, taskId: 1, html: '<!doctype html><p>목업</p>' } : null
  }
  if (path === '/api/questions') return [QUESTION]
  if (/^\/api\/questions\/\d+$/.test(path)) return QUESTION
  if (path === '/api/usage/claude') return { limits: [] }
  if (path === '/api/usage/summary') return USAGE_SUMMARY
  if (path === '/api/queue/stats') return { avgDurationMs: null }
  if (path === '/api/repo-catalog' || path === '/api/admin/repo-catalog') return REPOS
  if (path === '/api/repos/branches') return { repo: 'acme/widgets', defaultBranch: 'main', branches: [{ name: 'main', sha: 'abc123' }], fetchedAt: NOW }
  if (path === '/api/mcp-catalog' || path === '/api/admin/mcp-catalog') return MCPS
  if (path === '/api/workers/health') return WORKERS
  if (path === '/api/db-connections') return []
  if (path === '/api/admin/requests/summary') return { received: 1, attention: 1 }
  if (path === '/api/requests') return REQUESTS
  if (/^\/api\/requests\/\d+$/.test(path)) return REQUEST_DETAIL
  return undefined
}

const AUTH_STORAGE = JSON.stringify({
  accessToken: 'hydration-smoke-token',
  refreshToken: null,
  idToken: null,
  expiresAt: Date.now() + 24 * 3600 * 1000,
  me: { user_id: 1, username: 'admin', email: 'admin@example.com', authorities: ['ROLE_USER', 'ROLE_ADMIN'] },
})

// ── 서버 ──────────────────────────────────────────────────────────────────────
async function startServer() {
  if (!existsSync(ENTRY)) throw new Error(`${ENTRY} 없음 — 먼저 npm run build`)
  const child = spawn(process.execPath, [ENTRY], {
    cwd: ROOT,
    env: {
      ...process.env,
      PORT: String(PORT),
      HOST: '127.0.0.1',
      NITRO_PORT: String(PORT),
      NITRO_HOST: '127.0.0.1',
      // routeRules 프록시 타깃은 빌드 때 박힌다 — 런타임 보호는 브라우저 목이 맡는다. NUXT_API_PROXY_TARGET는 앱 로그 서버 라우트의
      // runtimeConfig.apiProxyTarget을 기동 때 덮어 닫힌 포트로 보낸다(checkAppLogRoute가 502를 기대한다).
      NUXT_API_PROXY_TARGET: 'http://127.0.0.1:9',
      NUXT_AUTH_PROXY_TARGET: 'http://127.0.0.1:9',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let log = ''
  child.stdout.on('data', (d) => { log += d })
  child.stderr.on('data', (d) => { log += d })
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`서버가 종료됨(exit ${child.exitCode})\n${log}`)
    try {
      const r = await fetch(`${BASE}/login`)
      if (r.ok) return child
    } catch { /* 아직 안 뜸 */ }
    await new Promise((r) => setTimeout(r, 300))
  }
  child.kill()
  throw new Error(`서버가 30초 안에 응답하지 않음\n${log}`)
}

// ── 점검 ──────────────────────────────────────────────────────────────────────
const HYDRATION_RE = /hydrat/i

/**
 * 앱 로그 서버 라우트(server/routes/stream/tasks/[id]/app-logs.get.ts)를 Node fetch로 직접 두드린다 — 브라우저 가로채기는
 * /stream/을 204로 막으므로 빠진 API 목록에 뜨지 않는다. 토큰 없음 → 401, API(닫힌 포트)에 닿지 못함 → 502이고
 * Nuxt 오류 페이지(text/html)가 아니어야 한다(라우트가 머리를 직접 쓰고 끝냈다는 뜻).
 */
async function checkAppLogRoute() {
  const failures = []
  const cases = [
    { label: '앱 로그 라우트 토큰 없음', path: '/stream/tasks/1/app-logs', status: 401 },
    { label: '앱 로그 라우트 API 연결 실패', path: '/stream/tasks/1/app-logs?access_token=x', status: 502 },
  ]
  for (const c of cases) {
    const problems = []
    try {
      const r = await fetch(BASE + c.path)
      await r.arrayBuffer()
      if (r.status !== c.status) problems.push(`상태 ${r.status} (기대 ${c.status})`)
      const type = r.headers.get('content-type') ?? ''
      if (type.includes('text/html')) problems.push(`content-type ${type} — 라우트가 아니라 Nuxt 오류 페이지가 답했다`)
    } catch (e) {
      problems.push(`fetch: ${e.message}`)
    }
    if (problems.length) failures.push({ label: c.label, finalPath: c.path, problems })
    else console.log(`  ok   ${c.label} ${c.path} → ${c.status}`)
  }
  return failures
}

async function runCase(browser, { viewport, loggedIn, cookie, listHidden = false, paths: onlyPaths }) {
  const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } })
  if (cookie) {
    await context.addCookies([{ name: 'netis-maker-screen', value: cookie, url: BASE }])
  }
  if (listHidden) {
    await context.addCookies([{ name: 'netis-maker-questions-list', value: 'hidden', url: BASE }])
  }
  if (loggedIn) {
    await context.addInitScript((v) => { try { localStorage.setItem('netis-maker-auth', v) } catch { /* noop */ } }, AUTH_STORAGE)
  }
  const unmocked = new Set()
  await context.route((url) => url.pathname.startsWith('/api/') || url.pathname.startsWith('/oauth2/') || url.pathname.startsWith('/stream/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    // SSE(EventSource)는 204로 닫는다 — /api/…/stream과 앱 로그 서버 라우트 /stream/…(직접 확인은 main의 checkAppLogRoute)
    if (path.endsWith('/stream') || path.startsWith('/stream/')) return route.fulfill({ status: 204, body: '' })
    const body = apiResponse(path)
    if (body === undefined) unmocked.add(`${route.request().method()} ${path}`)
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body ?? null) })
  })

  const failures = []
  const paths = onlyPaths ?? (loggedIn ? AUTH_PATHS : ANON_PATHS)
  for (const path of paths) {
    const page = await context.newPage()
    const label = `${viewport.name}px ${loggedIn ? '로그인' : '비로그인'} 쿠키=${cookie ?? '없음'}${listHidden ? ' 목록접힘' : ''} ${path}`
    const problems = []
    page.on('console', (msg) => {
      if (HYDRATION_RE.test(msg.text())) problems.push(`console.${msg.type()}: ${msg.text()}`)
    })
    page.on('pageerror', (err) => problems.push(`uncaught: ${err.message}`))
    try {
      await page.goto(BASE + path, { waitUntil: 'load' })
      // 첫 로드 리다이렉트(location.replace)·onMounted navigateTo까지 끝나기를 기다린다.
      await page.waitForLoadState('networkidle').catch(() => {})
      await page.waitForTimeout(SETTLE_MS)
      await page.waitForLoadState('load')
      const hydrated = await page.evaluate(() => !!document.querySelector('#__nuxt')?.__vue_app__)
      if (!hydrated) problems.push('Vue 앱이 마운트되지 않음(#__nuxt.__vue_app__ 없음)')
    } catch (e) {
      problems.push(`navigation: ${e.message}`)
    }
    const finalPath = new URL(page.url()).pathname
    await page.close()
    if (problems.length) failures.push({ label, finalPath, problems })
    else console.log(`  ok   ${label}${finalPath !== path ? ` → ${finalPath}` : ''}`)
  }
  await context.close()
  return { failures, unmocked }
}

async function main() {
  const server = await startServer()
  let browser
  try {
    browser = await chromium.launch({
      ...(process.env.SMOKE_BROWSER_PATH
        ? { executablePath: process.env.SMOKE_BROWSER_PATH }
        : { channel: process.env.SMOKE_BROWSER_CHANNEL ?? 'chrome' }),
    })
    const failures = []
    failures.push(...(await checkAppLogRoute()))
    const unmocked = new Set()
    for (const viewport of VIEWPORTS) {
      const matching = viewport.width >= 1024 ? 'wide' : 'narrow'
      for (const loggedIn of [false, true]) {
        for (const cookie of [null, matching]) {
          const r = await runCase(browser, { viewport, loggedIn, cookie })
          failures.push(...r.failures)
          r.unmocked.forEach((u) => unmocked.add(u))
        }
      }
      // 질문 목록 접힘 쿠키(pages/questions.vue) — 서버·클라이언트가 같은 쿠키로 접힌 사이드바를 그리는지(좁은 화면은 무시)
      const r = await runCase(browser, { viewport, loggedIn: true, cookie: matching, listHidden: true, paths: QUESTION_PATHS })
      failures.push(...r.failures)
      r.unmocked.forEach((u) => unmocked.add(u))
    }
    if (unmocked.size) console.log(`\n목 응답이 없어 null로 답한 API: ${[...unmocked].sort().join(', ')}`)
    if (failures.length) {
      console.error(`\n하이드레이션 스모크 실패 ${failures.length}건:`)
      for (const f of failures) {
        console.error(`- ${f.label} (최종 ${f.finalPath})`)
        for (const p of f.problems) console.error(`    ${p}`)
      }
      process.exitCode = 1
    } else {
      console.log('\n하이드레이션 스모크 통과 — 불일치 경고·잡히지 않은 예외 0건')
    }
  } finally {
    await browser?.close()
    server.kill()
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
