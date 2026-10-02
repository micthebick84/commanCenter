import { describe, expect, it } from 'vitest';
import { formatResult, toCell } from '../src/sdk/db/result.js';
import { DB_LIMITS } from '../src/sdk/db/limits.js';

describe('toCell (스펙 2026-10-02 §6.3)', () => {
  it('형 변환', () => {
    expect(toCell(null)).toBeNull();
    expect(toCell(undefined)).toBeNull();
    expect(toCell(12345678901234567890n)).toBe('12345678901234567890');
    expect(toCell(3)).toBe(3);
    expect(toCell(true)).toBe(true);
    expect(toCell(Buffer.from([1, 2, 3]))).toBe('<binary 3 bytes>');
    expect(toCell(new Date('2026-10-02T01:02:03Z'))).toBe('2026-10-02T01:02:03.000Z');
    expect(toCell({ a: 1 })).toBe('{"a":1}');
  });

  it('긴 문자열은 앞 maxCellChars자 + 남은 길이', () => {
    const s = 'x'.repeat(DB_LIMITS.maxCellChars + 5);
    expect(toCell(s)).toBe('x'.repeat(DB_LIMITS.maxCellChars) + '…(+5자)');
  });
});

describe('formatResult', () => {
  it('JSON 한 덩어리 — columns/rows/rowCount/truncated', () => {
    const t = formatResult({ columns: ['n'], rows: [[1n], [2n]], truncated: false });
    expect(JSON.parse(t)).toEqual({ columns: ['n'], rows: [['1'], ['2']], rowCount: 2, truncated: false });
  });

  it('행 상한을 넘으면 자르고 truncated', () => {
    const rows = Array.from({ length: 5 }, (_, i) => [i]);
    expect(JSON.parse(formatResult({ columns: ['n'], rows, truncated: false }, 3))).toMatchObject({ rowCount: 3, truncated: true });
    expect(JSON.parse(formatResult({ columns: ['n'], rows: [[1]], truncated: true }))).toMatchObject({ truncated: true });
  });

  it('전체 길이 상한을 넘으면 뒤 행부터 버린다', () => {
    const big = 'y'.repeat(DB_LIMITS.maxCellChars);
    const rows = Array.from({ length: 100 }, () => [big]);
    const t = formatResult({ columns: ['c'], rows, truncated: false }, 100);
    expect(t.length).toBeLessThanOrEqual(DB_LIMITS.maxResultChars);
    const parsed = JSON.parse(t);
    expect(parsed.truncated).toBe(true);
    expect(parsed.rowCount).toBeGreaterThan(0);
    expect(parsed.rowCount).toBeLessThan(100);
  });

  it('maxChars 파라미터가 길이 상한을 대신한다', () => {
    const big = 'y'.repeat(DB_LIMITS.maxCellChars);
    const rows = Array.from({ length: 100 }, () => [big]);
    const cap = Math.floor(DB_LIMITS.maxResultChars / 2);
    const t = formatResult({ columns: ['c'], rows, truncated: false }, 100, cap);
    expect(t.length).toBeLessThanOrEqual(cap);
    const parsed = JSON.parse(t);
    expect(parsed.truncated).toBe(true);
    expect(parsed.rowCount).toBeGreaterThan(0);
    // 기본 상한이면 더 많은 행이 들어간다
    const dflt = JSON.parse(formatResult({ columns: ['c'], rows, truncated: false }, 100));
    expect(dflt.rowCount).toBeGreaterThan(parsed.rowCount);
  });
});
