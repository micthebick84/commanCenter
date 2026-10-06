import { describe, expect, it, vi } from 'vitest';
import { createDbMcp, dbToolsFor, safeMessage } from '../src/sdk/dbMcp.js';
import type { DbSession } from '../src/sdk/db/adapters.js';
import type { DbConnectionRef, InterviewClaimResponse } from '../src/types.js';
import { questionClaim } from './fixtures/claims.js';

const ref = (over: Partial<DbConnectionRef> = {}): DbConnectionRef => ({
  serverName: 'db-7', label: '운영 DB (PostgreSQL)', dbType: 'POSTGRESQL', host: 'db.local', port: 5432,
  database: 'app', username: 'reader', password: 'Pw-secret', ...over,
});
const claimWith = (refs: DbConnectionRef[], notices: string[] = []): InterviewClaimResponse =>
  ({ ...questionClaim, dbConnections: refs, dbNotices: notices });
const fakeSession = (): DbSession & { run: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn> } => ({
  run: vi.fn(async () => ({ columns: ['n'], rows: [[1]], truncated: false })),
  close: vi.fn(async () => undefined),
});
const call = async (tools: ReturnType<typeof dbToolsFor>, name: string, args: Record<string, unknown>) => {
  const t = tools.find((x) => x.name === name);
  if (!t) throw new Error(`도구 없음: ${name}`);
  return (await t.handler(args as never, {})) as { content: Array<{ text: string }>; isError?: boolean };
};

describe('createDbMcp (스펙 2026-10-02 §6.2)', () => {
  it('연결마다 type:sdk 서버 — 인스턴스를 뺀 설정(CLI로 가는 부분)에 비밀번호 없음', () => {
    const p = createDbMcp(claimWith([ref()], ['참고 1']));
    const s = p.servers['db-7']!;
    expect(s.type).toBe('sdk');
    expect(s.name).toBe('db-7');
    expect(s.instance).toBeDefined();
    const { instance: _instance, ...wire } = s;
    expect(JSON.stringify(wire)).not.toContain('Pw-secret');
    expect(p.tools['db-7']!.map((t) => t.name)).toEqual(['query', 'list_tables', 'describe_table']);
    expect(p.dialects).toEqual({ 'db-7': 'postgres' });
    expect(p.labels).toEqual([{ serverName: 'db-7', label: '운영 DB (PostgreSQL)' }]);
    expect(p.notices).toEqual(['참고 1']);
  });

  it('연결이 없거나 필드가 없으면 빈 결과, 잘못된 serverName은 건너뛰고 안내', () => {
    expect(createDbMcp(claimWith([])).servers).toEqual({});
    expect(createDbMcp({ ...questionClaim }).servers).toEqual({});
    const p = createDbMcp(claimWith([ref({ serverName: '../evil' })]));
    expect(p.servers).toEqual({});
    expect(p.notices[0]).toContain('잘못된 DB 서버 이름');
  });

  it('DB 접속은 첫 도구 호출 때 한 번, close는 열린 세션만 닫는다', async () => {
    const session = fakeSession();
    const openSession = vi.fn(async () => session);
    const p = createDbMcp(claimWith([ref(), ref({ serverName: 'db-8', dbType: 'MYSQL' })]), { openSession });
    expect(openSession).not.toHaveBeenCalled();
    await call(p.tools['db-7']!, 'query', { sql: 'SELECT 1' });
    await call(p.tools['db-7']!, 'list_tables', {});
    expect(openSession).toHaveBeenCalledTimes(1);
    await p.close();
    expect(session.close).toHaveBeenCalledTimes(1);
  });

  it('접속 실패 뒤에는 다음 호출이 다시 접속을 시도한다, close 실패는 삼킨다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const session = fakeSession();
    session.close.mockRejectedValue(new Error('close boom'));
    const openSession = vi.fn().mockRejectedValueOnce(new Error('refused')).mockResolvedValue(session);
    const p = createDbMcp(claimWith([ref()]), { openSession });
    expect((await call(p.tools['db-7']!, 'query', { sql: 'SELECT 1' })).isError).toBe(true);
    expect((await call(p.tools['db-7']!, 'query', { sql: 'SELECT 1' })).isError).toBeUndefined();
    expect(openSession).toHaveBeenCalledTimes(2);
    await expect(p.close()).resolves.toBeUndefined();
    // 경고에는 서버 이름만 — 오류 메시지·접속정보 없음
    expect(warn.mock.calls.map((c) => String(c[0])).join('\n')).not.toContain('close boom');
    warn.mockRestore();
  });
});

describe('dbToolsFor — 도구 핸들러', () => {
  it('query: 결과 JSON, 상한 200행으로 실행', async () => {
    const session = fakeSession();
    const tools = dbToolsFor(ref(), 'postgres', async () => session);
    const r = await call(tools, 'query', { sql: 'SELECT 1' });
    expect(session.run).toHaveBeenCalledWith('SELECT 1', [], 200);
    expect(JSON.parse(r.content[0]!.text)).toMatchObject({ rowCount: 1, truncated: false });
  });

  it('handler_rejects_dml_even_if_gate_allowed', async () => {
    // Review Focus 6: 게이트가 allow해도 핸들러가 다시 막는다 — 세션을 열지도 않는다
    const getSession = vi.fn();
    const tools = dbToolsFor(ref(), 'mysql', getSession);
    for (const sql of ['DELETE FROM t', 'SELECT 1; DROP TABLE t', 'SELECT 1 /*! ; DELETE FROM t */']) {
      const r = await call(tools, 'query', { sql });
      expect(r.isError).toBe(true);
      expect(r.content[0]!.text).toContain('읽기 전용');
    }
    expect(getSession).not.toHaveBeenCalled();
  });

  it('error_text_never_contains_password', async () => {
    // Review Focus 5
    const tools = dbToolsFor(ref(), 'postgres', async () => { throw new Error('auth failed for reader using Pw-secret'); });
    const r = await call(tools, 'query', { sql: 'SELECT 1' });
    expect(r.isError).toBe(true);
    expect(r.content[0]!.text).not.toContain('Pw-secret');
    expect(r.content[0]!.text).toContain('****');
  });

  it('list_tables는 카탈로그 SQL을 바인딩으로 실행(카탈로그 상한 1000행)', async () => {
    const session = fakeSession();
    const tools = dbToolsFor(ref({ dbType: 'MYSQL' }), 'mysql', async () => session);
    await call(tools, 'list_tables', { schema: 'app' });
    expect(session.run.mock.calls[0]![1]).toEqual(['app']);
    expect(session.run.mock.calls[0]![2]).toBe(1000);
  });

  it('describe_table은 컬럼·인덱스 두 조회를 JSON 한 덩어리({columns, indexes})로 돌려준다', async () => {
    const session = fakeSession();
    const tools = dbToolsFor(ref({ dbType: 'MYSQL' }), 'mysql', async () => session);
    const d = await call(tools, 'describe_table', { table: 'orders' });
    expect(session.run.mock.calls[0]![1]).toEqual([null, 'orders']);
    expect(session.run.mock.calls[1]![1]).toEqual([null, 'orders']);
    expect(session.run.mock.calls[0]![2]).toBe(1000);
    const parsed = JSON.parse(d.content[0]!.text);
    expect(Object.keys(parsed)).toEqual(['columns', 'indexes']);
    expect(parsed.columns).toMatchObject({ columns: ['n'], rowCount: 1, truncated: false });
    expect(parsed.indexes).toMatchObject({ columns: ['n'], rowCount: 1, truncated: false });
  });

  it('describe_table 전체 길이는 maxResultChars 이하 (각 부분을 절반으로 제한)', async () => {
    const big = 'z'.repeat(2000);
    const session = fakeSession();
    session.run.mockImplementation(async () => ({ columns: ['c'], rows: Array.from({ length: 1000 }, () => [big]), truncated: false }));
    const tools = dbToolsFor(ref(), 'postgres', async () => session);
    const d = await call(tools, 'describe_table', { table: 'wide' });
    const text = d.content[0]!.text;
    expect(text.length).toBeLessThanOrEqual(60_000);
    const parsed = JSON.parse(text);
    expect(parsed.columns.truncated).toBe(true);
    expect(parsed.indexes.truncated).toBe(true);
  });
});

describe('safeMessage', () => {
  it('비밀번호 치환 + 500자 제한', () => {
    expect(safeMessage(new Error('x Pw-secret y Pw-secret'), { password: 'Pw-secret' })).toBe('x **** y ****');
    expect(safeMessage('z'.repeat(600), { password: 'p' }).length).toBeLessThanOrEqual(501);
  });
});
