// Blob을 파일로 저장 — 보이지 않는 앵커의 click으로 브라우저 다운로드를 시작한다.
// 첨부 다운로드(attachmentDownload.ts)와 앱 로그 내려받기(AppLogCard)가 공유한다.
export function saveBlob(blob: Blob, fileName: string): void {
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
