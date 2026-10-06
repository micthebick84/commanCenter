/** 질문 세션 DB 도구 상한 (스펙 2026-10-02 §6.2·§6.3). 설정화는 범위 밖(§12) — 바꿀 때는 스펙과 같이 바꾼다. */
export const DB_LIMITS = {
  statementTimeoutMs: 30_000,
  connectTimeoutMs: 10_000,
  maxRows: 200,
  maxCatalogRows: 1000,
  maxCellChars: 2000,
  maxResultChars: 60_000,
} as const;
