import { once } from 'node:events'
import type { IncomingMessage, ServerResponse } from 'node:http'

// 앱 로그 SSE 중계 (스펙 2026-11-03 §4 "끊김 전달").
// `/api/**`는 routeRules 프록시(h3 sendProxy)를 거치는데, 그 프록시는 upstream fetch에 abort signal을 걸지 않아
// 브라우저가 끊겨도 API의 SseEmitter가 쓰기 실패를 보지 못한다 → docker logs·ssh가 시간 상한까지 남는다.
// 그래서 이 스트림만 `/stream/tasks/{id}/app-logs` 서버 라우트가 받아, 브라우저가 끊기면 upstream fetch를 abort한다.
//
// 쓰지 않는 것: proxyRequest·sendProxy(signal이 없고 accept를 버린다), upstream.body 반환(pipeTo만 하고 끊김을 전하지 않는다),
// 헤더를 보내기 전에 return(h3가 204로 끝낸다), for await·pipeTo(잠긴 스트림의 body.cancel()은 TypeError로 거부되고,
// 반복자의 return()은 대기 중인 read 뒤에 줄을 선다 — 로그가 조용한 컨테이너면 끊김이 다음 핑까지 밀린다).
// h3·Nitro 별칭은 import하지 않는다(vitest에서 풀리지 않는다) — 라우트가 값을 꺼내 넘긴다.

export interface AppLogProxyOptions {
  /** 라우트 파라미터 그대로(디코드 전) — 숫자가 아니면 404 */
  id: string | undefined
  /** 쿼리 `access_token` 그대로 — 비었거나 문자열이 아니면(같은 이름이 여럿) 401 */
  token: unknown
  /** API 주소(`runtimeConfig.apiProxyTarget`), 끝 `/`는 떼고 쓴다 */
  apiBase: string
  req: IncomingMessage
  res: ServerResponse
  /** 시험용 — 기본은 전역 fetch(undici) */
  fetchImpl?: typeof fetch
}

const SSE_HEADERS = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache, no-transform',
  'X-Accel-Buffering': 'no',
  Connection: 'keep-alive',
}

function endWith(res: ServerResponse, status: number): void {
  res.statusCode = status
  res.end()
}

/**
 * API `GET /api/tasks/{id}/app-logs/stream`을 받아 바이트 그대로(핑 주석 포함) 브라우저에 넘긴다. 응답이 끝날 때까지 resolve하지 않는다.
 * - 브라우저가 끊기면(`req`·`res` 'close') upstream fetch를 abort하고 본문 reader를 취소한다 → API의 다음 쓰기(핑)가 실패해 docker를 끝낸다.
 * - 받는 쪽이 느리면(`res.write`가 false) drain까지 upstream을 더 읽지 않는다 — Nitro 메모리가 소켓 버퍼 이상 커지지 않는다.
 * - 자기 실패는 상태 코드로만 낸다(본문 없음): 404 id, 401 토큰, 502 API에 닿지 못함, 그 밖은 upstream 상태 그대로.
 */
export async function pipeAppLogStream(o: AppLogProxyOptions): Promise<void> {
  const { req, res } = o
  if (o.id === undefined || !/^\d+$/.test(o.id)) return endWith(res, 404)
  if (typeof o.token !== 'string' || o.token === '') return endWith(res, 401)

  const controller = new AbortController()
  const { signal } = controller
  const abort = () => controller.abort()
  req.on('close', abort)
  res.on('close', abort)
  try {
    let upstream: Response
    try {
      // 토큰은 헤더로만 — 헤더와 쿼리에 함께 있으면 Spring이 거부한다(SecurityConfig.bearerTokenResolver)
      upstream = await (o.fetchImpl ?? fetch)(`${o.apiBase.replace(/\/+$/, '')}/api/tasks/${o.id}/app-logs/stream`, {
        headers: { authorization: `Bearer ${o.token}`, accept: 'text/event-stream' },
        signal,
      })
    } catch {
      if (!signal.aborted) res.statusCode = 502 // 브라우저가 먼저 끊겼으면 받을 사람이 없다
      return
    }
    if (upstream.status !== 200 || !upstream.body) {
      res.statusCode = upstream.status === 200 ? 502 : upstream.status
      upstream.body?.cancel().catch(() => {})
      return
    }

    res.writeHead(200, SSE_HEADERS) // upstream 머리는 복사하지 않는다 — Content-Length 없이 청크로
    res.flushHeaders()
    const reader = upstream.body.getReader()
    const cancel = () => {
      reader.cancel().catch(() => {})
    }
    signal.addEventListener('abort', cancel, { once: true })
    if (signal.aborted) cancel() // 응답을 기다리는 사이에 이미 끊겼다
    try {
      for (;;) {
        const r = await reader.read()
        if (r.done) break
        if (!res.write(r.value)) await once(res, 'drain', { signal })
      }
    } catch (e) {
      // 끊긴 뒤의 오류(AbortError 포함)는 삼킨다. 그 밖(API가 도중에 죽음 등)은 머리를 이미 보냈으니 닫기만 한다 —
      // 브라우저 EventSource는 닫힘을 onerror로 보고 다시 연결한다.
      if (!signal.aborted) console.warn(`[app-logs] 작업 ${o.id} 중계가 끊겼습니다:`, e)
    }
  } finally {
    controller.abort() // 어느 길로 나가든 upstream을 놓는다(이미 끝났으면 아무 일도 없다)
    req.off('close', abort)
    res.off('close', abort)
    res.end()
  }
}
