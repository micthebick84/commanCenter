// 첨부 다운로드 — pages/tasks/[id].vue의 downloadAttachment 패턴을 질문 세션(InterviewPanel 말풍선 칩 ·
// pages/questions/[id].vue 헤더 칩)이 공유하도록 뽑아냄 (스펙 2026-09-13 §7).
// 반드시 useApi 경유 — 전역 $fetch는 Authorization 미첨부로 401. 파일명은 응답 헤더가 아니라 메타 fileName 사용
// ($fetch는 헤더를 안 돌려준다). 실패하면 사용자에게 보여줄 메시지를 담은 Error를 던진다 — 알림은 호출처가 띄운다.
export async function downloadAttachment(
  url: string,
  fileName: string,
): Promise<void> {
  let blob: Blob
  try {
    blob = await useApi<Blob>(url, { responseType: 'blob' })
  } catch (e: unknown) {
    throw new Error(await errorMessage(e))
  }
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objectUrl
  a.download = fileName
  document.body.appendChild(a) // Firefox는 DOM에 붙지 않은 앵커의 click을 무시한다
  a.click()
  // blob URL을 click과 같은 tick에 해제하면 일부 브라우저에서 다운로드가 시작 전에 중단된다(Chromium 41380177, Firefox 1282407).
  setTimeout(() => {
    URL.revokeObjectURL(objectUrl)
    a.remove()
  }, 0)
}

// responseType:'blob'이면 ofetch가 에러 본문도 Blob으로 파싱하므로 e.data는 {message}가 아니라 Blob이다.
async function errorMessage(e: unknown): Promise<string> {
  const data = (e as { data?: unknown } | null)?.data
  try {
    if (data instanceof Blob) {
      const parsed = JSON.parse(await data.text())
      if (parsed?.message) return String(parsed.message)
    } else if (
      data &&
      typeof data === 'object' &&
      'message' in data &&
      (data as { message?: unknown }).message
    ) {
      return String((data as { message: unknown }).message)
    }
  } catch {
    // 본문이 JSON이 아니면 fallback 유지
  }
  return '다운로드 실패'
}
