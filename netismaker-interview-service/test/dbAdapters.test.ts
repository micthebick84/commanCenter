import { EventEmitter } from 'node:events';
import { describe, expect, it } from 'vitest';
import { loadDriver, openSession, withoutTrailingSemicolon, type DriverLoader } from '../src/sdk/db/adapters.js';
import { catalogSql } from '../src/sdk/db/catalog.js';
import type { DbConnectionRef } from '../src/types.js';

const ref = (over: Partial<DbConnectionRef> = {}): DbConnectionRef => ({
  serverName: 'db-7', label: '운영 DB', dbType: 'POSTGRESQL', host: 'db.local', port: 5432,
  database: 'app', username: 'reader', password: 'Pw-secret', ...over,
});
const tick = () => new Promise((r) => setImmediate(r));

type PgReply = { fields?: Array<{ name: string }>; rows?: unknown[][] } | Error;
function fakePg(reply: (text: string) => PgReply = () => ({})) {
  const log: string[] = [];
  const configs: Array<Record<string, unknown>> = [];
  const loader = {
    pg: async () => ({
      Client: class {
        constructor(cfg: Record<string, unknown>) { configs.push(cfg); }
        on() { return this; }
        async connect() { log.push('CONNECT'); }
        async query(q: { text: string }) {
          await tick(); // 직렬화 테스트: 비동기 사이에 다른 run이 끼어들 틈을 만든다
          log.push(q.text);
          const r = reply(q.text);
          if (r instanceof Error) throw r;
          return r;
        }
        async end() { log.push('END'); }
      },
    }),
  } as unknown as DriverLoader;
  return { loader, log, configs };
}

type MyReply = { fields?: string[]; rows?: unknown[][]; error?: Error };
function fakeMysql(reply: (sql: string) => MyReply = () => ({})) {
  const log: string[] = [];
  const configs: Array<Record<string, unknown>> = [];
  const loader = {
    mysql: async () => ({
      createConnection: (cfg: Record<string, unknown>) => {
        configs.push(cfg);
        const conn = new EventEmitter() as EventEmitter & Record<string, unknown>;
        conn.query = (opts: { sql: string }) => {
          const q = new EventEmitter();
          log.push(opts.sql);
          setImmediate(() => {
            const r = reply(opts.sql);
            if (r.error) { q.emit('error', r.error); return; }
            if (r.fields) q.emit('fields', r.fields.map((name) => ({ name })));
            for (const row of r.rows ?? []) q.emit('result', row);
            q.emit('end');
          });
          return q;
        };
        conn.destroy = () => { log.push('DESTROY'); };
        conn.end = (cb?: () => void) => { log.push('END'); cb?.(); };
        return conn;
      },
    }),
  } as unknown as DriverLoader;
  return { loader, log, configs };
}

function fakeOracle(rows: unknown[][]) {
  const log: string[] = [];
  const configs: Array<Record<string, unknown>> = [];
  const conn = {
    callTimeout: 0,
    async execute(sql: string, _binds: unknown[], opts?: { maxRows?: number }) {
      log.push(sql);
      if (opts) log.push(`maxRows=${opts.maxRows}`);
      return { metaData: [{ name: 'N' }], rows };
    },
    async rollback() { log.push('ROLLBACK'); },
    async close() { log.push('CLOSE'); },
  };
  const loader = {
    oracle: async () => ({
      getConnection: async (cfg: Record<string, unknown>) => { configs.push(cfg); return conn; },
      OUT_FORMAT_ARRAY: 4001, STRING: 'STR', DB_TYPE_NUMBER: 'NUM', DB_TYPE_CLOB: 'CLOB', DB_TYPE_NCLOB: 'NCLOB',
      DB_TYPE_DATE: 'DATE', DB_TYPE_TIMESTAMP: 'TS', DB_TYPE_TIMESTAMP_TZ: 'TSTZ', DB_TYPE_TIMESTAMP_LTZ: 'TSLTZ',
    }),
  } as unknown as DriverLoader;
  return { loader, log, configs, conn };
}

describe('withoutTrailingSemicolon', () => {
  it('끝 ; 와 그 뒤 공백·주석을 지운다, ; 가 없으면 그대로', () => {
    expect(withoutTrailingSemicolon('SELECT 1;')).toBe('SELECT 1');
    expect(withoutTrailingSemicolon('SELECT 1; -- x')).toBe('SELECT 1');
    expect(withoutTrailingSemicolon('SELECT 1 ;  /* c */ ')).toBe('SELECT 1');
    expect(withoutTrailingSemicolon('SELECT 1;\n# m\n')).toBe('SELECT 1');
    expect(withoutTrailingSemicolon('SELECT 1')).toBe('SELECT 1');
    expect(withoutTrailingSemicolon('  SELECT 1  ')).toBe('SELECT 1');
  });

  it('문자열 리터럴 안의 "; -- -- …" 같은 입력에도 역추적 폭주가 없다', () => {
    const evil = `SELECT '; ${'-- '.repeat(5000)}\n' AS x`;
    const t = Date.now();
    expect(withoutTrailingSemicolon(evil)).toBe(evil);
    expect(withoutTrailingSemicolon(`SELECT 1;${' /**/'.repeat(5000)} x`)).toContain('SELECT 1;');
    expect(Date.now() - t).toBeLessThan(1000);
  });
});

describe('PostgreSQL 세션 (스펙 2026-10-02 §6.2)', () => {
  it('SELECT는 READ ONLY 트랜잭션 + 커서 FETCH(maxRows+1) + ROLLBACK, 끝 ; 제거', async () => {
    const pg = fakePg((t) => (t.startsWith('FETCH') ? { fields: [{ name: 'n' }], rows: [[1], [2], [3]] } : {}));
    const s = await openSession(ref(), pg.loader);
    const r = await s.run('SELECT * FROM t;', [], 2);
    expect(pg.log).toEqual([
      'CONNECT',
      'BEGIN TRANSACTION READ ONLY',
      'SET LOCAL statement_timeout = 30000',
      'DECLARE netis_q NO SCROLL CURSOR FOR SELECT * FROM t',
      'FETCH FORWARD 3 FROM netis_q',
      'ROLLBACK',
    ]);
    expect(r).toEqual({ columns: ['n'], rows: [[1], [2]], truncated: true });
    expect(pg.configs[0]).toMatchObject({ options: '-c default_transaction_read_only=on', connectionTimeoutMillis: 10000, password: 'Pw-secret' });
  });

  it('SHOW·EXPLAIN과 바인딩 파라미터가 있는 조회는 커서 없이 직접', async () => {
    const pg = fakePg();
    const s = await openSession(ref(), pg.loader);
    await s.run('SHOW search_path');
    await s.run('SELECT 1 WHERE $1::text IS NULL', [null]);
    expect(pg.log.filter((l) => l.startsWith('DECLARE'))).toEqual([]);
    expect(pg.log).toContain('SHOW search_path');
  });

  it('SQL 오류에도 ROLLBACK', async () => {
    const pg = fakePg((t) => (t.startsWith('DECLARE') ? new Error('boom') : {}));
    const s = await openSession(ref(), pg.loader);
    await expect(s.run('SELECT x')).rejects.toThrow('boom');
    expect(pg.log.at(-1)).toBe('ROLLBACK');
  });

  it('같은 세션의 동시 run은 트랜잭션이 겹치지 않는다', async () => {
    const pg = fakePg();
    const s = await openSession(ref(), pg.loader);
    await Promise.all([s.run('SHOW a'), s.run('SHOW b')]);
    const body = pg.log.slice(1);
    expect(body).toEqual([
      'BEGIN TRANSACTION READ ONLY', 'SET LOCAL statement_timeout = 30000', 'SHOW a', 'ROLLBACK',
      'BEGIN TRANSACTION READ ONLY', 'SET LOCAL statement_timeout = 30000', 'SHOW b', 'ROLLBACK',
    ]);
  });
});

describe('MySQL·MariaDB 세션', () => {
  const my = (over: Partial<DbConnectionRef> = {}) => ref({ dbType: 'MARIADB', port: 3306, ...over });

  it('접속 직후 세션 READ ONLY + 타임아웃 2종, run은 START TRANSACTION READ ONLY … ROLLBACK', async () => {
    const m = fakeMysql((sql) => (sql === 'SELECT 1' ? { fields: ['1'], rows: [[1]] } : {}));
    const s = await openSession(my(), m.loader);
    const r = await s.run('SELECT 1;');
    expect(m.log).toEqual([
      'SET SESSION TRANSACTION READ ONLY',
      'SET SESSION max_execution_time = 30000',
      'SET SESSION max_statement_time = 30',
      'START TRANSACTION READ ONLY',
      'SELECT 1',
      'ROLLBACK',
    ]);
    expect(r).toEqual({ columns: ['1'], rows: [[1]], truncated: false });
    expect(m.configs[0]).toMatchObject({ multipleStatements: false, flags: ['-LOCAL_FILES'], bigNumberStrings: true, connectTimeout: 10000 });
  });

  it('모르는 타임아웃 변수 오류는 무시한다', async () => {
    const m = fakeMysql((sql) => (sql.includes('max_execution_time') ? { error: new Error('Unknown system variable') } : {}));
    const s = await openSession(my(), m.loader);
    await expect(s.run('SELECT 1')).resolves.toMatchObject({ truncated: false });
  });

  it('READ ONLY 설정이 실패하면 쿼리를 실행하지 않는다', async () => {
    const m = fakeMysql((sql) => (sql === 'SET SESSION TRANSACTION READ ONLY' ? { error: new Error('denied') } : {}));
    const s = await openSession(my(), m.loader);
    await expect(s.run('SELECT 1')).rejects.toThrow('denied');
    expect(m.log).not.toContain('SELECT 1');
    expect(m.log).toContain('DESTROY');
  });

  it('행 상한을 넘으면 커넥션을 버리고(ROLLBACK 없이) 다음 run에서 다시 접속', async () => {
    const m = fakeMysql((sql) => (sql === 'SELECT n FROM t' ? { fields: ['n'], rows: [[1], [2], [3], [4], [5]] } : {}));
    const s = await openSession(my(), m.loader);
    const r = await s.run('SELECT n FROM t', [], 2);
    expect(r).toEqual({ columns: ['n'], rows: [[1], [2]], truncated: true });
    expect(m.log.slice(-2)).toEqual(['SELECT n FROM t', 'DESTROY']);
    await s.run('SELECT n FROM t', [], 10);
    expect(m.configs).toHaveLength(2);
  });

  it('SQL 오류면 ROLLBACK 후 오류', async () => {
    const m = fakeMysql((sql) => (sql === 'SELECT bad' ? { error: new Error('syntax') } : {}));
    const s = await openSession(my(), m.loader);
    await expect(s.run('SELECT bad')).rejects.toThrow('syntax');
    expect(m.log.at(-1)).toBe('ROLLBACK');
  });
});

describe('Oracle 세션', () => {
  it('ROLLBACK → SET TRANSACTION READ ONLY → execute(maxRows+1) → ROLLBACK, EZConnect·callTimeout', async () => {
    const o = fakeOracle([[1], [2], [3]]);
    const s = await openSession(ref({ dbType: 'ORACLE', port: 1521, database: 'ORCLPDB1' }), o.loader);
    const r = await s.run('SELECT n FROM dual;', [], 2);
    expect(o.log).toEqual(['ROLLBACK', 'SET TRANSACTION READ ONLY', 'SELECT n FROM dual', 'maxRows=3', 'ROLLBACK']);
    expect(r).toEqual({ columns: ['N'], rows: [[1], [2]], truncated: true });
    expect(o.configs[0]).toMatchObject({ connectString: 'db.local:1521/ORCLPDB1', user: 'reader' });
    expect(o.conn.callTimeout).toBe(30000);
    await s.close();
    expect(o.log.at(-1)).toBe('CLOSE');
  });
});

describe('드라이버 로더·카탈로그', () => {
  it('없는 드라이버는 고정 문구 오류', async () => {
    await expect(loadDriver('netismaker-no-such-driver')).rejects.toThrow('DB 드라이버를 불러올 수 없습니다(netismaker-no-such-driver)');
  });

  it('카탈로그 SQL은 값을 바인딩 파라미터로만 넘긴다', () => {
    expect(catalogSql('postgres', 'tables', null)).toMatchObject({ params: [null] });
    const c = catalogSql('oracle', 'columns', null, "x' OR '1'='1");
    expect(c.params).toEqual([null, "x' OR '1'='1"]);
    expect(c.sql).not.toContain("x' OR");
    expect(c.sql).toContain('UPPER(:2)');
    expect(catalogSql('mysql', 'indexes', 'app', 'orders').params).toEqual(['app', 'orders']);
  });
});
