/**
 * 서버가 사유를 보내지 않았을 때 쓰는 실패 안내 — 무엇이 안 됐는지와 다음에 할 일을 함께 말한다(2026-11-11 검토).
 * 예전에는 "삭제 실패"처럼 결과만 보여 사용자가 무엇을 해야 할지 몰랐다. 서버 메시지가 있으면 그쪽이 먼저다:
 *
 *   $q.notify({ type: 'negative', message: e?.data?.message ?? failMsg('삭제') })
 */
export function failMsg(action: string): string {
  return `${action}에 실패했습니다. 새로고침한 뒤 다시 시도하고, 계속되면 관리자에게 알려 주세요.`
}
