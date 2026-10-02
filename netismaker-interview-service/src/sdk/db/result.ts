import { DB_LIMITS } from './limits.js';

/** 어댑터 한 번 실행 결과. rows는 상한까지만, 넘쳤으면 truncated. */
export interface QueryResult {
  columns: string[];
  rows: unknown[][];
  truncated: boolean;
}

function clip(s: string): string {
  const max = DB_LIMITS.maxCellChars;
  return s.length > max ? `${s.slice(0, max)}…(+${s.length - max}자)` : s;
}

/**
 * 셀 값을 JSON에 안전한 값으로 (스펙 §6.3). bigint를 숫자로 바꾸지 않는다(정밀도) — 문자열.
 * 기존 MariaDB MCP의 BigInt 직렬화 오류가 이 경로에서 생기지 않게 하는 곳.
 */
export function toCell(v: unknown): unknown {
  if (v === null || v === undefined) return null;
  if (typeof v === 'bigint') return v.toString();
  if (typeof v === 'string') return clip(v);
  if (typeof v === 'number' || typeof v === 'boolean') return v;
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? String(v) : v.toISOString();
  if (v instanceof Uint8Array) return `<binary ${v.byteLength} bytes>`; // Buffer 포함
  try {
    return clip(JSON.stringify(v) ?? String(v));
  } catch {
    return clip(String(v));
  }
}

/**
 * 도구 결과 텍스트 — 행 상한·전체 길이 상한을 넘으면 잘라 truncated 표시.
 * maxChars는 한 도구 결과에 여러 덩어리를 담을 때(describe_table) 덩어리별 몫을 줄이는 용도.
 */
export function formatResult(
  r: QueryResult,
  maxRows: number = DB_LIMITS.maxRows,
  maxChars: number = DB_LIMITS.maxResultChars,
): string {
  let truncated = r.truncated || r.rows.length > maxRows;
  let rows = r.rows.slice(0, maxRows).map((row) => row.map(toCell));
  const render = () => JSON.stringify({ columns: r.columns, rows, rowCount: rows.length, truncated });
  let text = render();
  while (text.length > maxChars && rows.length > 0) {
    const keep = Math.floor(rows.length * (maxChars / text.length));
    rows = rows.slice(0, Math.min(rows.length - 1, Math.max(0, keep)));
    truncated = true;
    text = render();
  }
  return text;
}
