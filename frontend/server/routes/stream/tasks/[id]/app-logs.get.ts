// 앱 로그 SSE — 브라우저 끊김을 API에 전하려고 `/api/**` 프록시 대신 이 라우트로 받는다(server/utils/appLogProxy.ts).
// server/api 아래에 두면 `/api` 접두가 붙어 routeRules '/api/**' 프록시에 가려진다.
export default defineEventHandler(async (event) => {
  await pipeAppLogStream({
    id: getRouterParam(event, 'id'),
    token: getQuery(event).access_token,
    apiBase: useRuntimeConfig(event).apiProxyTarget,
    req: event.node.req,
    res: event.node.res,
  })
})
