import { createServer, request } from 'node:http'
import type { ClientRequest, IncomingMessage, Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { ReadableStream } from 'node:stream/web'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { pipeAppLogStream } from '../server/utils/appLogProxy'

// 앱 로그 SSE 중계(스펙 2026-11-03 §4 "끊김 전달"). `/api/**`는 routeRules 프록시(h3 sendProxy)가 브라우저 끊김을 API에 전하지 않아
// 이 스트림만 Nitro 서버 라우트가 받는다. 진짜 node:http 서버에 pipeAppLogStream을 걸고 가짜 fetch로 API를 흉내 낸다.
// 가짜 본문은 node:stream/web ReadableStream(highWaterMark 0 — 읽을 때만 pull)이고, undici와 달리 abort돼도 스스로 끝나지 않는다
// → 끊김은 구현의 reader.cancel()로만 끝난다. (happy-dom 환경에서는 import.meta.url이 http 스킴이라 파일 경로는 __dirname으로 푼다.)
const root = resolve(__dirname, '..')
const read = (p: string) => readFileSync(resolve(root, p), 'utf8')

const bytes = (s: string) => new Uint8Array(Buffer.from(s))
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** pull마다 next(n)이 주는 조각을 넣는다 — null이면 닫고, undefined면 영영 기다린다(로그가 조용한 컨테이너). */
function fakeBody(next: (n: number) => Uint8Array | null | undefined) {
  const state = { pulls: 0, cancelled: false }
  const body = new ReadableStream<Uint8Array>(
    {
      pull(controller) {
        state.pulls++
        const chunk = next(state.pulls)
        if (chunk === null) controller.close()
        else if (chunk === undefined) return new Promise<void>(() => {})
        else controller.enqueue(chunk)
      },
      cancel() {
        state.cancelled = true
      },
    },
    { highWaterMark: 0 },
  )
  return { body, state }
}

/** fetch 응답 흉내 — 구현은 status·body만 봐야 하고, upstream 머리(content-length 등)를 복사하면 안 된다. */
function upstream(status: number, body: ReadableStream<Uint8Array> | null): Response {
  return {
    status,
    ok: status >= 200 && status < 300,
    body,
    headers: new Headers({ 'content-length': '999', 'x-upstream': 'yes' }),
  } as unknown as Response
}

type Call = [string, RequestInit]

const servers: Server[] = []

afterEach(async () => {
  for (const s of servers.splice(0)) {
    s.closeAllConnections()
    await new Promise<void>((r) => s.close(() => r()))
  }
})

/** 라우트(server/routes/stream/tasks/[id]/app-logs.get.ts)가 h3에서 꺼내 넘기는 값과 같은 모양으로 요청을 풀어 pipeAppLogStream에 넘긴다. */
async function startProxy(respond: (url: string, init: RequestInit) => Promise<Response>, apiBase = 'http://api.test/') {
  const calls: Call[] = []
  let ended = 0
  const server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://proxy.test')
    const id = url.pathname.match(/^\/stream\/tasks\/([^/]+)\/app-logs$/)?.[1]
    const tokens = url.searchParams.getAll('access_token')
    const token = tokens.length > 1 ? tokens : tokens[0]
    const fetchImpl = ((input: string, init: RequestInit) => {
      calls.push([input, init])
      return respond(input, init)
    }) as unknown as typeof fetch
    void pipeAppLogStream({ id, token, apiBase, req, res, fetchImpl }).finally(() => {
      ended++
    })
  })
  servers.push(server)
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()))
  const { port } = server.address() as AddressInfo
  return { base: `http://127.0.0.1:${port}`, calls, ended: () => ended }
}

function open(url: string): Promise<{ req: ClientRequest; res: IncomingMessage }> {
  return new Promise((done, fail) => {
    const req = request(url, (res) => {
      res.on('error', () => {}) // 시험이 req.destroy()로 끊으면 응답 쪽에 aborted 오류가 난다
      done({ req, res })
    })
    req.on('error', fail)
    req.end()
  })
}

function readAll(res: IncomingMessage): Promise<string> {
  return new Promise((done, fail) => {
    let text = ''
    res.setEncoding('utf8')
    res.on('data', (d: string) => {
      text += d
    })
    res.on('end', () => done(text))
    res.on('error', fail)
  })
}

const PING = ':ping\n\n'
const LINES = 'event:lines\ndata:{"lines":[]}\n\n'

/** 짝이 맞는 `{`…`}` 블록 — key 뒤 첫 `{`부터 */
function block(src: string, key: string): string {
  const at = src.indexOf(key)
  expect(at, `${key} 없음`).toBeGreaterThanOrEqual(0)
  const open = src.indexOf('{', at)
  let depth = 0
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) return src.slice(open, i + 1)
  }
  throw new Error(`${key} 블록이 닫히지 않음`)
}

describe('Nitro /stream/tasks/{id}/app-logs (스펙 2026-11-03 §4 끊김 전달)', () => {
  it('토큰이 없으면 401, API를 부르지 않는다', async () => {
    const p = await startProxy(() => Promise.reject(new Error('API를 부르면 안 된다')))
    for (const q of ['', '?access_token=', '?access_token=a&access_token=b']) {
      const { res } = await open(`${p.base}/stream/tasks/42/app-logs${q}`)
      expect(res.statusCode, q).toBe(401)
      expect(await readAll(res)).toBe('')
    }
    expect(p.calls).toHaveLength(0)
    await vi.waitFor(() => expect(p.ended()).toBe(3))
  })

  it('id가 숫자가 아니면 404, API를 부르지 않는다', async () => {
    const p = await startProxy(() => Promise.reject(new Error('API를 부르면 안 된다')))
    for (const id of ['abc', '4.2', '-1', '4%32', '']) {
      const { res } = await open(`${p.base}/stream/tasks/${id}/app-logs?access_token=tok`)
      expect(res.statusCode, id).toBe(404)
      expect(await readAll(res)).toBe('')
    }
    expect(p.calls).toHaveLength(0)
    await vi.waitFor(() => expect(p.ended()).toBe(5))
  })

  it('API에는 Authorization 헤더 하나로 — URL에 토큰 없음', async () => {
    const p = await startProxy(async () => upstream(200, fakeBody(() => null).body))
    const { res } = await open(`${p.base}/stream/tasks/42/app-logs?access_token=tok`)
    await readAll(res)
    expect(p.calls).toHaveLength(1)
    expect(p.calls[0][0]).toBe('http://api.test/api/tasks/42/app-logs/stream') // apiBase 'http://api.test/'의 끝 / 정리
    expect(p.calls[0][0]).not.toContain('access_token')
    expect(p.calls[0][1].headers).toEqual({ authorization: 'Bearer tok', accept: 'text/event-stream' })
    // 끊김을 전할 signal — 쿼리 토큰과 헤더가 함께 있으면 Spring이 거부하므로(SecurityConfig.bearerTokenResolver) 헤더 하나뿐이다
    expect(p.calls[0][1].signal).toHaveProperty('aborted')
  })

  it('200이면 SSE 머리와 바이트를 그대로', async () => {
    const chunks = [PING, LINES]
    const p = await startProxy(async () => upstream(200, fakeBody((n) => (n <= chunks.length ? bytes(chunks[n - 1]) : null)).body))
    const { res } = await open(`${p.base}/stream/tasks/42/app-logs?access_token=tok`)
    expect(res.statusCode).toBe(200)
    expect(res.headers['content-type']).toBe('text/event-stream')
    expect(res.headers['cache-control']).toBe('no-cache, no-transform')
    expect(res.headers['x-accel-buffering']).toBe('no')
    expect(res.headers['content-length']).toBeUndefined()
    expect(res.headers['x-upstream']).toBeUndefined()
    expect(await readAll(res)).toBe(PING + LINES)
    await vi.waitFor(() => expect(p.ended()).toBe(1))
  })

  it('브라우저가 끊으면 1초 안에 upstream을 abort하고 본문을 취소한다', async () => {
    // [RF1] 로그가 조용한 컨테이너 — 첫 핑 뒤로는 upstream이 아무것도 보내지 않는다
    const fake = fakeBody((n) => (n === 1 ? bytes(PING) : undefined))
    const p = await startProxy(async () => upstream(200, fake.body))
    const { req, res } = await open(`${p.base}/stream/tasks/42/app-logs?access_token=tok`)
    await new Promise((r) => res.once('data', r))
    req.destroy()
    await vi.waitFor(
      () => {
        expect(p.calls[0][1].signal?.aborted).toBe(true)
        expect(fake.state.cancelled).toBe(true)
        expect(p.ended()).toBe(1)
      },
      { timeout: 1000, interval: 10 },
    )
  })

  it('클라이언트가 읽지 않으면 drain 전까지 upstream을 더 읽지 않는다', async () => {
    // [RF4] pull마다 64KB — 받는 쪽이 멈추면 소켓 버퍼가 찬 뒤로 pull이 멈춰야 Nitro 메모리가 커지지 않는다
    const fake = fakeBody(() => new Uint8Array(64 * 1024).fill(0x61))
    const p = await startProxy(async () => upstream(200, fake.body))
    const { req, res } = await open(`${p.base}/stream/tasks/42/app-logs?access_token=tok`)
    res.pause()
    await sleep(1000)
    const atOne = fake.state.pulls
    await sleep(300)
    expect(fake.state.pulls).toBe(atOne)
    expect(atOne).toBeGreaterThan(0)
    expect(atOne).toBeLessThan(64)
    // drain을 기다리는 중에 끊겨도 upstream을 놓는다
    req.destroy()
    await vi.waitFor(
      () => {
        expect(fake.state.cancelled).toBe(true)
        expect(p.ended()).toBe(1)
      },
      { timeout: 1000, interval: 10 },
    )
  })

  it('API가 403이면 403, 본문 취소', async () => {
    const fake = fakeBody(() => bytes('{"error":"forbidden"}'))
    const p = await startProxy(async () => upstream(403, fake.body))
    const { res } = await open(`${p.base}/stream/tasks/42/app-logs?access_token=tok`)
    expect(res.statusCode).toBe(403)
    expect(res.headers['content-type'] ?? '').not.toContain('text/event-stream')
    expect(await readAll(res)).toBe('')
    expect(fake.state.cancelled).toBe(true)
    await vi.waitFor(() => expect(p.ended()).toBe(1))
  })

  it('API에 닿지 못하면 502', async () => {
    const p = await startProxy(() => Promise.reject(new TypeError('fetch failed')))
    const { res } = await open(`${p.base}/stream/tasks/42/app-logs?access_token=tok`)
    expect(res.statusCode).toBe(502)
    expect(res.headers['content-type'] ?? '').not.toContain('text/html')
    expect(await readAll(res)).toBe('')
    expect(p.calls).toHaveLength(1)
    await vi.waitFor(() => expect(p.ended()).toBe(1))
  })

  it('nuxt.config: apiProxyTarget는 public 밖, routeRules에 /stream 없음', () => {
    const cfg = read('nuxt.config.ts')
    const runtime = block(cfg, 'runtimeConfig:')
    expect(runtime).toContain("apiProxyTarget: process.env.NUXT_API_PROXY_TARGET || 'http://localhost:8090'")
    // public에 두면 브라우저로 내려가고, 런타임 env 이름도 NUXT_PUBLIC_…로 바뀐다
    expect(block(runtime, 'public:')).not.toContain('apiProxyTarget')
    // /stream을 프록시 규칙에 넣으면 서버 라우트가 가려진다(nitropack app.mjs — routeRules 프록시가 먼저 돈다)
    expect(block(cfg, 'routeRules:')).not.toContain('/stream')
    expect(block(cfg, 'devProxy:')).not.toContain('/stream')
  })

  it('라우트 파일은 server/routes/stream 아래이고 server/api 아래에는 없다', () => {
    const route = 'server/routes/stream/tasks/[id]/app-logs.get.ts'
    expect(existsSync(resolve(root, route))).toBe(true)
    // server/api 아래면 /api 접두가 붙어 routeRules '/api/**' 프록시에 가려진다
    const apiDir = resolve(root, 'server/api')
    const apiFiles = existsSync(apiDir) ? readdirSync(apiDir, { recursive: true }).map(String) : []
    expect(apiFiles.filter((f) => f.includes('app-logs'))).toEqual([])
    const src = read(route)
    expect(src).toContain("getRouterParam(event, 'id')")
    expect(src).toContain('getQuery(event).access_token')
    expect(src).toContain('useRuntimeConfig(event).apiProxyTarget')
    expect(src).toContain('event.node.req')
    expect(src).toContain('event.node.res')
  })
})
