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
const ANON_PATHS = ['/login', '/tasks', '/questions']
const AUTH_PATHS = [
  '/',
  '/login',
  '/tasks',
  '/tasks/1',
  '/questions',
  '/questions/3',
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
  effort: 'medium', designRequested: false, analysis: null, design: null, implementation: null, deployment: null,
  interviewSessionId: null, attachments: [], stageUsage: [], totalCostUsd: null, totalTokens: null, ...extra,
})
const TASKS = [
  task(1, 'PR_CREATED', 'PR생성', {
    implementation: { prUrl: 'https://github.com/acme/widgets/pull/1', prNumber: 1, headBranch: 'netismaker/task-1', headSha: 'abc123', implementationLog: '완료' },
    totalCostUsd: 1.23,
  }),
  task(2, 'AWAITING_APPROVAL', '승인대기'),
  task(3, 'CANCELLED', '취소됨'),
]
const QUESTION = {
  id: 3, title: '인증 흐름 확인', githubRepo: 'acme/widgets', githubBranch: 'main', repoAlias: 'Widgets',
  requesterId: 'admin', status: '입력대기', statusName: 'AWAITING_INPUT', model: 'claude-sonnet-5-5', effort: 'medium',
  kind: 'QUESTION', totalCostUsd: 0.42, contextTokens: 76004, contextWindow: 200000, mcpCatalogIds: [],
  dbConnectionIds: [], attachments: [], createdAt: NOW, updatedAt: NOW,
  turns: [{ seq: 1, role: 'assistant', kind: 'question', content: '**AuthController**입니다' }], plan: null,
}
const REPOS = [{ id: 1, alias: 'Widgets', gitUrl: 'https://github.com/acme/widgets', host: 'github', ownerRepo: 'acme/widgets', defaultBranch: 'main', description: null, enabled: true, createdBy: 'admin', createdAt: NOW, updatedAt: NOW }]
const MCPS = [{ id: 1, name: 'docs', displayName: 'Docs', url: 'https://mcp.example/sse', transport: 'sse', description: null, enabled: true, createdBy: 'admin', createdAt: NOW, updatedAt: NOW, lastCheckAt: null, lastCheckStatus: null, lastCheckError: null }]
const WORKERS = [{ workerId: 'smoke-worker-1', hostname: 'smoke', version: '1.0', lastSeenAt: NOW, alive: true, claudeSessionOk: true, vpnStatus: null, mcps: ['docs'], lostReportCount: 0 }]

/** pathname → 응답 본문. 목록에 없는 /api/ 요청은 null(200)로 답하고 보고서에 남긴다. */
function apiResponse(path) {
  if (path === '/api/me') return { user_id: 1, username: 'admin', email: 'admin@example.com', authorities: ['ROLE_USER', 'ROLE_ADMIN'] }
  if (path === '/api/tasks') return { content: TASKS, totalElements: TASKS.length, totalPages: 1, number: 0, size: 50 }
  const t = path.match(/^\/api\/tasks\/(\d+)$/)
  if (t) return TASKS.find((x) => x.id === Number(t[1])) ?? null
  if (/^\/api\/tasks\/\d+\/(history|interviews)$/.test(path)) return []
  if (path === '/api/questions') return [QUESTION]
  if (/^\/api\/questions\/\d+$/.test(path)) return QUESTION
  if (path === '/api/usage/claude') return { limits: [] }
  if (path === '/api/queue/stats') return { avgDurationMs: null }
  if (path === '/api/repo-catalog' || path === '/api/admin/repo-catalog') return REPOS
  if (path === '/api/mcp-catalog' || path === '/api/admin/mcp-catalog') return MCPS
  if (path === '/api/workers/health') return WORKERS
  if (path === '/api/db-connections') return []
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
      // routeRules 프록시 타깃은 빌드 때 박힌다 — 런타임 보호는 브라우저 목이 맡고, 여기서는 기록용으로만 둔다.
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

async function runCase(browser, { viewport, loggedIn, cookie }) {
  const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } })
  if (cookie) {
    await context.addCookies([{ name: 'netis-maker-screen', value: cookie, url: BASE }])
  }
  if (loggedIn) {
    await context.addInitScript((v) => { try { localStorage.setItem('netis-maker-auth', v) } catch { /* noop */ } }, AUTH_STORAGE)
  }
  const unmocked = new Set()
  await context.route((url) => url.pathname.startsWith('/api/') || url.pathname.startsWith('/oauth2/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/stream')) return route.fulfill({ status: 204, body: '' })
    const body = apiResponse(path)
    if (body === undefined) unmocked.add(`${route.request().method()} ${path}`)
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body ?? null) })
  })

  const failures = []
  const paths = loggedIn ? AUTH_PATHS : ANON_PATHS
  for (const path of paths) {
    const page = await context.newPage()
    const label = `${viewport.name}px ${loggedIn ? '로그인' : '비로그인'} 쿠키=${cookie ?? '없음'} ${path}`
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
