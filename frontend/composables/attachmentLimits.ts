// 첨부 한도 — 작업 등록(pages/tasks/index.vue)과 질문 세션 채팅(QuestionComposer)이 공유한다 (스펙 2026-09-13 §7).
// 서버 AttachmentStorage.validate와 같은 값의 사전 검증. 한도를 문자열에 다시 하드코딩하면 서버/검증과 갈라지므로
// 라벨·툴팁도 이 상수에서 파생할 것.
export const MAX_FILES = 10
export const MAX_FILE_MB = 20
export const MAX_TOTAL_MB = 50

/** 위반 사유(한국어) 또는 null(통과). 빈 배열은 통과. */
export function validateFiles(files: File[]): string | null {
  if (files.length > MAX_FILES)
    return `첨부는 최대 ${MAX_FILES}개까지 가능합니다`
  const over = files.find((f) => f.size > MAX_FILE_MB * 1024 * 1024)
  if (over)
    return `파일당 ${MAX_FILE_MB}MB 이하만 첨부할 수 있습니다: ${over.name}`
  if (files.some((f) => f.size === 0))
    return '빈 파일(0바이트)은 첨부할 수 없습니다'
  const total = files.reduce((s, f) => s + f.size, 0)
  if (total > MAX_TOTAL_MB * 1024 * 1024)
    return `첨부 합계는 ${MAX_TOTAL_MB}MB 이하여야 합니다`
  return null
}

/** 칩 라벨용 크기 표기 — pages/tasks/[id].vue의 formatSize와 동일 규칙(B/KB/MB). */
export function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`
  return `${bytes}B`
}
