import type { DbConnectionRef } from '../../types.js';
import { stripSql } from '../sqlReadOnly.js';
import { DB_LIMITS } from './limits.js';
import type { QueryResult } from './result.js';

/**
 * 질문 세션 DB 도구의 종류별 읽기 전용 세션 (스펙 2026-10-02 §6.2).
 * 모든 run: 읽기 전용 트랜잭션 시작 → 사용자 SQL(끝 ';' 제거) → 성공·실패와 무관하게 ROLLBACK.
 * 한 세션의 run은 직렬화한다 — CLI가 같은 서버 도구를 병렬 호출해도 한 커넥션에 트랜잭션이 겹치지 않게.
 * ⚠️ ref.password는 드라이버 설정 객체에만 넣는다. 오류 메시지는 dbMcp.safeMessage를 거쳐 나간다.
 */
export interface DbSession {
  /** params는 바인딩 파라미터(카탈로그 조회 전용). maxRows를 넘는 행은 버리고 truncated. */
  run(sql: string, params?: unknown[], maxRows?: number): Promise<QueryResult>;
  close(): Promise<void>;
}

/* 드라이버 최소 구조 타입 — 타입 패키지 없이 컴파일하고, 테스트가 가짜를 주입한다. */
export interface PgClient {
  on(event: 'error', cb: (e: Error) => void): unknown;
  connect(): Promise<unknown>;
  query(q: { text: string; values?: unknown[]; rowMode: 'array' }): Promise<{ fields?: Array<{ name: string }>; rows?: unknown[][] }>;
  end(): Promise<void>;
}
export interface PgModule {
  Client: new (cfg: Record<string, unknown>) => PgClient;
}
export interface MysqlQuery {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  on(event: string, cb: (...args: any[]) => void): MysqlQuery;
}
export interface MysqlConnection {
  query(opts: { sql: string; values?: unknown[]; rowsAsArray?: boolean; timeout?: number }): MysqlQuery;
  on(event: 'error', cb: (e: Error) => void): unknown;
  end(cb?: (e?: Error) => void): void;
  destroy(): void;
}
export interface MysqlModule {
  createConnection(cfg: Record<string, unknown>): MysqlConnection;
}
export interface OracleConnection {
  callTimeout: number;
  execute(sql: string, binds: unknown[], opts?: Record<string, unknown>): Promise<{ metaData?: Array<{ name: string }>; rows?: unknown[][] }>;
  rollback(): Promise<void>;
  close(): Promise<void>;
}
export interface OracleModule {
  getConnection(cfg: Record<string, unknown>): Promise<OracleConnection>;
  OUT_FORMAT_ARRAY: unknown;
  STRING: unknown;
  DB_TYPE_NUMBER: unknown;
  DB_TYPE_CLOB: unknown;
  DB_TYPE_NCLOB: unknown;
  DB_TYPE_DATE: unknown;
  DB_TYPE_TIMESTAMP: unknown;
  DB_TYPE_TIMESTAMP_TZ: unknown;
  DB_TYPE_TIMESTAMP_LTZ: unknown;
}

export interface DriverLoader {
  pg(): Promise<PgModule>;
  mysql(): Promise<MysqlModule>;
  oracle(): Promise<OracleModule>;
}

/** 변수 모듈명 동적 import — 타입 패키지 없이 컴파일되고, 설치가 빠지면 그 도구 호출만 실패한다. */
export async function loadDriver<T>(name: string): Promise<T> {
  try {
    const mod = (await import(name)) as { default?: T };
    return (mod.default ?? mod) as T;
  } catch {
    throw new Error(`DB 드라이버를 불러올 수 없습니다(${name}) — 인터뷰 서비스에서 npm ci를 확인하세요`);
  }
}

export const realDrivers: DriverLoader = {
  pg: () => loadDriver<PgModule>('pg'),
  mysql: () => loadDriver<MysqlModule>('mysql2'),
  oracle: () => loadDriver<OracleModule>('oracledb'),
};

/**
 * sqlReadOnly가 끝 ';' 하나를 허용한다(그 뒤에는 공백·주석만 올 수 있다) — Oracle은 ';'가 있으면 ORA-00933,
 * 커서 DECLARE 안에서도 문법 오류. 끝 ';'와 그 뒤 공백·주석을 함께 지운다.
 * ⚠️ 정규식은 대안끼리 시작 문자가 겹치지 않고 각 대안이 한 가지로만 매치되게 짠다(줄 주석은 줄끝까지 통째로,
 * 블록 주석은 첫 닫힘까지) — 문자열 리터럴 안의 "; -- -- -- …" 같은 입력이 재귀 역추적으로 이벤트 루프를 멈추지 않게.
 */
export function withoutTrailingSemicolon(sql: string): string {
  return sql
    .trim()
    .replace(/;(?:\s|--[^\n]*(?:\n|$)|#[^\n]*(?:\n|$)|\/\*(?:[^*]|\*(?!\/))*\*\/)*$/, '')
    .trimEnd();
}

function limited(columns: string[], rows: unknown[][], maxRows: number): QueryResult {
  return { columns, rows: rows.slice(0, maxRows), truncated: rows.length > maxRows };
}

function serialized(s: DbSession): DbSession {
  let chain: Promise<unknown> = Promise.resolve();
  return {
    run(sql, params, maxRows) {
      const p = chain.then(() => s.run(sql, params, maxRows));
      chain = p.catch(() => undefined);
      return p;
    },
    close: () => chain.then(() => s.close()),
  };
}

export async function openSession(ref: DbConnectionRef, drivers: DriverLoader = realDrivers): Promise<DbSession> {
  switch (ref.dbType) {
    case 'POSTGRESQL':
      return openPostgres(ref, drivers);
    case 'ORACLE':
      return openOracle(ref, drivers);
    default:
      return openMysql(ref, drivers); // MYSQL, MARIADB
  }
}

// ── PostgreSQL ────────────────────────────────────────────────────────────

const PG_CURSOR_LEADING = new Set(['SELECT', 'WITH', 'VALUES', 'TABLE']);

function firstWord(sql: string): string {
  const stripped = stripSql(sql, 'postgres');
  const body = typeof stripped === 'string' ? stripped : sql;
  return (/[A-Za-z_]+/.exec(body)?.[0] ?? '').toUpperCase();
}

async function openPostgres(ref: DbConnectionRef, drivers: DriverLoader): Promise<DbSession> {
  const pg = await drivers.pg();
  const client = new pg.Client({
    host: ref.host,
    port: ref.port,
    database: ref.database,
    user: ref.username,
    password: ref.password,
    connectionTimeoutMillis: DB_LIMITS.connectTimeoutMs,
    application_name: 'netismaker-question',
    options: '-c default_transaction_read_only=on',
  });
  client.on('error', () => undefined); // 유휴 중 끊김 — 리스너가 없으면 프로세스가 죽는다. 다음 run이 오류로 드러낸다.
  await client.connect();
  const q = (text: string, values?: unknown[]) => client.query({ text, values, rowMode: 'array' });
  return serialized({
    async run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      const body = withoutTrailingSemicolon(sql);
      await q('BEGIN TRANSACTION READ ONLY');
      try {
        await q(`SET LOCAL statement_timeout = ${DB_LIMITS.statementTimeoutMs}`);
        // 행 수 상한을 DB 쪽에서 — 큰 테이블 SELECT *가 메모리로 다 오지 않게. DECLARE는 바인딩 파라미터를
        // 받지 못하므로 params가 있는 조회(카탈로그 — 자체 LIMIT)와 SHOW/EXPLAIN은 직접 실행한다.
        let r: { fields?: Array<{ name: string }>; rows?: unknown[][] };
        if (params.length === 0 && PG_CURSOR_LEADING.has(firstWord(body))) {
          await q(`DECLARE netis_q NO SCROLL CURSOR FOR ${body}`);
          r = await q(`FETCH FORWARD ${maxRows + 1} FROM netis_q`);
        } else {
          r = await q(body, params);
        }
        return limited((r.fields ?? []).map((f) => f.name), r.rows ?? [], maxRows);
      } finally {
        await q('ROLLBACK').catch(() => undefined);
      }
    },
    close: () => client.end(),
  });
}

// ── MySQL · MariaDB ───────────────────────────────────────────────────────

function mysqlControl(c: MysqlConnection, sql: string): Promise<void> {
  return new Promise((resolve, reject) => {
    c.query({ sql }).on('error', reject).on('end', () => resolve());
  });
}

function mysqlRows(
  c: MysqlConnection,
  sql: string,
  values: unknown[],
  maxRows: number,
): Promise<{ columns: string[]; rows: unknown[][]; overflow: boolean }> {
  return new Promise((resolve, reject) => {
    const columns: string[] = [];
    const rows: unknown[][] = [];
    let done = false;
    c.query({ sql, values, rowsAsArray: true, timeout: DB_LIMITS.statementTimeoutMs })
      .on('fields', (fields: Array<{ name: string }> | undefined) => {
        if (columns.length === 0 && Array.isArray(fields)) columns.push(...fields.map((f) => f.name));
      })
      .on('result', (row: unknown) => {
        if (done || !Array.isArray(row)) return; // OK 패킷(비행 결과)은 무시
        if (rows.length >= maxRows) {
          done = true;
          resolve({ columns, rows, overflow: true });
          return;
        }
        rows.push(row);
      })
      .on('error', (e: Error) => {
        if (done) return;
        done = true;
        reject(e);
      })
      .on('end', () => {
        if (done) return;
        done = true;
        resolve({ columns, rows, overflow: false });
      });
  });
}

async function openMysql(ref: DbConnectionRef, drivers: DriverLoader): Promise<DbSession> {
  const mysql = await drivers.mysql();
  let conn: MysqlConnection | null = null;
  const drop = (c: MysqlConnection) => {
    c.destroy();
    if (conn === c) conn = null;
  };
  const connect = async (): Promise<MysqlConnection> => {
    const c = mysql.createConnection({
      host: ref.host,
      port: ref.port,
      database: ref.database,
      user: ref.username,
      password: ref.password,
      connectTimeout: DB_LIMITS.connectTimeoutMs,
      multipleStatements: false,
      supportBigNumbers: true,
      bigNumberStrings: true,
      dateStrings: true,
      flags: ['-LOCAL_FILES'], // LOAD DATA LOCAL INFILE 차단
    });
    c.on('error', () => undefined); // 유휴 중 끊김 — 리스너가 없으면 프로세스가 죽는다.
    try {
      await mysqlControl(c, 'SET SESSION TRANSACTION READ ONLY');
    } catch (e) {
      c.destroy();
      throw e;
    }
    // 서버 측 문장 타임아웃 — MySQL(5.7.8+, SELECT만)과 MariaDB(10.1+)의 변수 이름이 달라 둘 다 시도, 모르는 변수 오류는 무시.
    await mysqlControl(c, `SET SESSION max_execution_time = ${DB_LIMITS.statementTimeoutMs}`).catch(() => undefined);
    await mysqlControl(c, `SET SESSION max_statement_time = ${DB_LIMITS.statementTimeoutMs / 1000}`).catch(() => undefined);
    return c;
  };
  return serialized({
    async run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      const c = conn ?? (conn = await connect());
      try {
        await mysqlControl(c, 'START TRANSACTION READ ONLY');
      } catch (e) {
        drop(c);
        throw e;
      }
      let r: { columns: string[]; rows: unknown[][]; overflow: boolean };
      try {
        r = await mysqlRows(c, withoutTrailingSemicolon(sql), params, maxRows);
      } catch (e) {
        await mysqlControl(c, 'ROLLBACK').catch(() => drop(c));
        throw e;
      }
      // 상한 초과: 남은 행 전송을 끊으려고 커넥션째 버린다(트랜잭션도 함께 사라짐). 다음 run이 다시 접속한다.
      if (r.overflow) drop(c);
      else await mysqlControl(c, 'ROLLBACK').catch(() => drop(c));
      return { columns: r.columns, rows: r.rows, truncated: r.overflow };
    },
    async close() {
      const c = conn;
      conn = null;
      if (c) await new Promise<void>((resolve) => c.end(() => resolve()));
    },
  });
}

// ── Oracle (thin — initOracleClient를 부르지 않는다) ──────────────────────

async function openOracle(ref: DbConnectionRef, drivers: DriverLoader): Promise<DbSession> {
  const ora = await drivers.oracle();
  const conn = await ora.getConnection({
    user: ref.username,
    password: ref.password,
    connectString: `${ref.host}:${ref.port}/${ref.database}`,
    connectTimeout: Math.ceil(DB_LIMITS.connectTimeoutMs / 1000), // 초 단위
  });
  conn.callTimeout = DB_LIMITS.statementTimeoutMs;
  const asString = new Set([
    ora.DB_TYPE_NUMBER, ora.DB_TYPE_CLOB, ora.DB_TYPE_NCLOB, ora.DB_TYPE_DATE,
    ora.DB_TYPE_TIMESTAMP, ora.DB_TYPE_TIMESTAMP_TZ, ora.DB_TYPE_TIMESTAMP_LTZ,
  ]);
  const fetchTypeHandler = (m: { dbType?: unknown }) => (asString.has(m.dbType) ? { type: ora.STRING } : undefined);
  return serialized({
    async run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      await conn.rollback(); // SET TRANSACTION은 트랜잭션의 첫 문장이어야 한다
      await conn.execute('SET TRANSACTION READ ONLY', []);
      try {
        const r = await conn.execute(withoutTrailingSemicolon(sql), params, {
          outFormat: ora.OUT_FORMAT_ARRAY,
          maxRows: maxRows + 1,
          fetchTypeHandler,
        });
        return limited((r.metaData ?? []).map((m) => m.name), r.rows ?? [], maxRows);
      } finally {
        await conn.rollback().catch(() => undefined);
      }
    },
    close: () => conn.close(),
  });
}
