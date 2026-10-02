import type { DbConnectionRef } from '../../types.js';
import { lastTopLevelSemicolon, stripSql, type SqlDialect } from '../sqlReadOnly.js';
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
  query(q: { text: string; values?: unknown[]; rowMode: 'array'; queryMode?: 'extended' }): Promise<{ fields?: Array<{ name: string }>; rows?: unknown[][] }>;
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
  /** cb를 주면 결과가 버퍼링되고 오류가 cb로 온다. cb가 없으면 오류는 Query가 아니라 커넥션 'error'로만 온다(mysql2 3.x). */
  query(opts: { sql: string; values?: unknown[]; rowsAsArray?: boolean; timeout?: number }, cb?: (err: Error | null) => void): MysqlQuery;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  on(event: string, cb: (...args: any[]) => void): unknown;
  end(cb?: (e?: Error) => void): void;
  destroy(): void;
  /** 하부 소켓 — destroy()는 쓰기 쪽만 닫으므로 전송 중단에는 이것을 직접 파괴한다. */
  stream?: { destroy(): void };
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
 * 커서 DECLARE 안에서도 문법 오류. 문자열·주석 밖의 마지막 ';' 위치를 토크나이저(sqlReadOnly)로 찾아(선형)
 * 그 뒤가 공백·주석뿐이면 거기서 자른다. 그렇지 않으면 trim만 한 원문을 돌려준다(체커가 이미 거부했을 모양).
 */
export function withoutTrailingSemicolon(sql: string, dialect: SqlDialect): string {
  const t = sql.trim();
  const idx = lastTopLevelSemicolon(t, dialect);
  if (idx < 0) return t;
  const rest = stripSql(t.slice(idx + 1), dialect);
  if (typeof rest !== 'string' || rest.trim() !== '') return t;
  return t.slice(0, idx).trimEnd();
}

function limited(columns: string[], rows: unknown[][], maxRows: number): QueryResult {
  return { columns, rows: rows.slice(0, maxRows), truncated: rows.length > maxRows };
}

/** close()가 멈춘 run을 기다려 주는 최대 시간 — 넘으면 기다리지 않고 닫는다(턴 finally가 끝나야 한다). */
const CLOSE_WAIT_MS = 5_000;

function serialized(s: DbSession): DbSession {
  let chain: Promise<unknown> = Promise.resolve();
  return {
    run(sql, params, maxRows) {
      const p = chain.then(() => s.run(sql, params, maxRows));
      chain = p.catch(() => undefined);
      return p;
    },
    async close() {
      let timer: NodeJS.Timeout | undefined;
      await Promise.race([chain, new Promise<void>((resolve) => { timer = setTimeout(resolve, CLOSE_WAIT_MS); })]);
      clearTimeout(timer);
      await s.close();
    },
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
const PG_QUERY_TIMEOUT_SLACK_MS = 5_000;

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
    query_timeout: DB_LIMITS.statementTimeoutMs + PG_QUERY_TIMEOUT_SLACK_MS, // 클라이언트 측 — 서버가 응답하지 않을 때(네트워크 단절) run이 영원히 서지 않게
    application_name: 'netismaker-question',
    options: '-c default_transaction_read_only=on',
  });
  client.on('error', () => undefined); // 유휴 중 끊김 — 리스너가 없으면 프로세스가 죽는다. 다음 run이 오류로 드러낸다.
  await client.connect();
  // 제어 문장(BEGIN·SET LOCAL·FETCH·ROLLBACK)은 simple 프로토콜. 사용자 SQL은 extended 프로토콜로 보낸다 —
  // simple 프로토콜은 ';'로 이어진 여러 문장을 실행하지만(1선이 놓친 'COMMIT; SET …; DELETE …'가 READ ONLY 트랜잭션을 벗어난다)
  // extended는 서버가 "cannot insert multiple commands into a prepared statement"로 거부한다. (Review Focus 6)
  const q = (text: string, values?: unknown[], extended = false) =>
    client.query({ text, values, rowMode: 'array', ...(extended ? { queryMode: 'extended' as const } : {}) });
  return serialized({
    async run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      const body = withoutTrailingSemicolon(sql, 'postgres');
      await q('BEGIN TRANSACTION READ ONLY');
      try {
        await q(`SET LOCAL statement_timeout = ${DB_LIMITS.statementTimeoutMs}`);
        // 행 수 상한을 DB 쪽에서 — 큰 테이블 SELECT *가 메모리로 다 오지 않게. DECLARE는 바인딩 파라미터를
        // 받지 못하므로 params가 있는 조회(카탈로그 — 자체 LIMIT)와 SHOW/EXPLAIN은 직접 실행한다.
        let r: { fields?: Array<{ name: string }>; rows?: unknown[][] };
        if (params.length === 0 && PG_CURSOR_LEADING.has(firstWord(body))) {
          await q(`DECLARE netis_q NO SCROLL CURSOR FOR ${body}`, undefined, true);
          r = await q(`FETCH FORWARD ${maxRows + 1} FROM netis_q`);
        } else {
          r = await q(body, params, true);
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

/**
 * mysql2 3.x는 콜백 없는 명령(스트리밍 query·핸드셰이크)의 오류를 그 명령이 아니라 커넥션 'error'로만 알린다.
 * 그래서 커넥션을 MyLink로 감싸 — 'error'/'end'가 오면 대기 중인 모든 작업을 reject하고 커넥션을 버린다.
 * (리스너가 오류를 삼키기만 하면 접속 실패·연결 끊김 때 run·close가 영원히 끝나지 않는다.)
 */
interface MyLink {
  c: MysqlConnection;
  dead: Error | null;
  waiters: Set<(e: Error) => void>;
  /** 이 커넥션에 마지막으로 건 sql_select_limit — 같으면 다시 보내지 않는다. */
  selectLimit: number | null;
}

/** run 한 번(접속 + 문장들)의 하드 상한 — 넘으면 소켓을 파괴하고 고정 문구로 reject. */
const MYSQL_RUN_GUARD_MS = DB_LIMITS.connectTimeoutMs + DB_LIMITS.statementTimeoutMs + 5_000;
/** close()가 정상 종료(COM_QUIT)를 기다리는 최대 시간 — 넘으면 소켓을 파괴한다. */
const MYSQL_CLOSE_MS = 3_000;
const MYSQL_RUN_TIMEOUT_MESSAGE = 'DB 응답이 제한 시간 안에 오지 않아 연결을 닫았습니다';

/** destroy()는 쓰기 쪽만 닫아(stream.end) 서버가 보내는 결과를 계속 받아 파싱한다 — 소켓 자체를 파괴해 읽기를 멈춘다. */
function killConnection(c: MysqlConnection): void {
  try {
    c.destroy();
  } catch {
    // 이미 닫힘
  }
  try {
    c.stream?.destroy();
  } catch {
    // 이미 닫힘
  }
}

/** link가 죽으면(abort) reject되는 작업 하나. exec가 resolve/reject로 끝낸다. */
function pending<T>(link: MyLink, exec: (resolve: (v: T) => void, reject: (e: Error) => void) => void): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (link.dead) {
      reject(link.dead);
      return;
    }
    const onDead = (e: Error) => {
      link.waiters.delete(onDead);
      reject(e);
    };
    link.waiters.add(onDead);
    try {
      exec(
        (v) => {
          link.waiters.delete(onDead);
          resolve(v);
        },
        (e) => {
          link.waiters.delete(onDead);
          reject(e);
        },
      );
    } catch (e) {
      link.waiters.delete(onDead);
      reject(e as Error);
    }
  });
}

/** 제어 문장 — 콜백 형태라 오류가 reject로 온다. */
function mysqlControl(link: MyLink, sql: string): Promise<void> {
  return pending<void>(link, (resolve, reject) => {
    link.c.query({ sql }, (err) => (err ? reject(err) : resolve()));
  });
}

function mysqlRows(
  link: MyLink,
  sql: string,
  values: unknown[],
  maxRows: number,
): Promise<{ columns: string[]; rows: unknown[][]; overflow: boolean }> {
  return pending(link, (resolve, reject) => {
    const columns: string[] = [];
    const rows: unknown[][] = [];
    let done = false;
    link.c
      .query({ sql, values, rowsAsArray: true, timeout: DB_LIMITS.statementTimeoutMs })
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
  // 현재 커넥션(접속 중 포함). run은 직렬화되어 있어 한 번에 하나만 쓴다.
  let link: MyLink | null = null;
  // 지금 run이 쓰는 커넥션 — close()가 link를 비운 뒤에도 하드 상한이 이것을 파괴할 수 있게 따로 둔다.
  let inflight: MyLink | null = null;

  const abort = (l: MyLink, err: Error): void => {
    if (l.dead) return;
    l.dead = err;
    if (link === l) link = null;
    killConnection(l.c);
    const waiters = [...l.waiters];
    l.waiters.clear();
    for (const w of waiters) w(err);
  };

  const connect = async (): Promise<MyLink> => {
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
    const l: MyLink = { c, dead: null, waiters: new Set(), selectLimit: null };
    link = l;
    inflight = l;
    // 접속 실패·유휴 끊김·쿼리 중 끊김이 전부 여기로 온다 — 삼키지 말고 대기 중인 작업을 깨운다.
    c.on('error', (e: Error) => abort(l, e));
    c.on('end', () => abort(l, new Error('DB 연결이 끊어졌습니다')));
    try {
      await mysqlControl(l, 'SET SESSION TRANSACTION READ ONLY');
      // 서버 측 문장 타임아웃 — MySQL(5.7.8+, SELECT만)과 MariaDB(10.1+)의 변수 이름이 달라 둘 다 시도, 모르는 변수 오류는 무시.
      await mysqlControl(l, `SET SESSION max_execution_time = ${DB_LIMITS.statementTimeoutMs}`).catch(() => undefined);
      await mysqlControl(l, `SET SESSION max_statement_time = ${DB_LIMITS.statementTimeoutMs / 1000}`).catch(() => undefined);
      if (l.dead) throw l.dead;
    } catch (e) {
      abort(l, e as Error);
      throw e;
    }
    return l;
  };

  const runOnce = async (sql: string, params: unknown[], maxRows: number): Promise<QueryResult> => {
    const l = link ?? (await connect());
    inflight = l;
    try {
      // 평범한 SELECT도 서버가 maxRows+1행에서 멈추게(best effort — 변수가 없으면 무시). run마다 상한이 달라 값이 바뀔 때만 보낸다.
      if (l.selectLimit !== maxRows + 1) {
        await mysqlControl(l, `SET SESSION sql_select_limit = ${maxRows + 1}`).catch(() => undefined);
        l.selectLimit = maxRows + 1;
      }
      await mysqlControl(l, 'START TRANSACTION READ ONLY');
    } catch (e) {
      abort(l, e as Error);
      throw e;
    }
    let r: { columns: string[]; rows: unknown[][]; overflow: boolean };
    try {
      r = await mysqlRows(l, withoutTrailingSemicolon(sql, 'mysql'), params, maxRows);
    } catch (e) {
      // 클라이언트 타임아웃이면 서버에서 아직 도는 쿼리 뒤에 ROLLBACK이 줄을 서게 되므로 커넥션째 버린다.
      if ((e as { code?: string }).code === 'PROTOCOL_SEQUENCE_TIMEOUT') abort(l, e as Error);
      else await mysqlControl(l, 'ROLLBACK').catch(() => abort(l, e as Error));
      throw e;
    }
    // 상한 초과: 남은 행 전송을 끊으려고 소켓째 파괴한다(트랜잭션도 함께 사라짐). 다음 run이 다시 접속한다.
    if (r.overflow) abort(l, new Error('행 상한 초과로 연결을 닫았습니다'));
    else await mysqlControl(l, 'ROLLBACK').catch(() => abort(l, new Error('ROLLBACK 실패로 연결을 닫았습니다')));
    return { columns: r.columns, rows: r.rows, truncated: r.overflow };
  };

  return serialized({
    run(sql, params = [], maxRows = DB_LIMITS.maxRows) {
      return new Promise<QueryResult>((resolve, reject) => {
        const timer = setTimeout(() => {
          const err = new Error(MYSQL_RUN_TIMEOUT_MESSAGE);
          if (inflight) abort(inflight, err);
          reject(err);
        }, MYSQL_RUN_GUARD_MS);
        runOnce(sql, params, maxRows).then(
          (v) => {
            clearTimeout(timer);
            resolve(v);
          },
          (e) => {
            clearTimeout(timer);
            reject(e);
          },
        );
      });
    },
    async close() {
      const l = link;
      link = null;
      if (!l) return;
      if (l.dead) {
        killConnection(l.c);
        return;
      }
      await new Promise<void>((resolve) => {
        let finished = false;
        const finish = () => {
          if (finished) return;
          finished = true;
          clearTimeout(timer);
          l.waiters.delete(finish);
          resolve();
        };
        const timer = setTimeout(() => {
          abort(l, new Error('DB 연결 종료가 지연되어 소켓을 닫았습니다'));
          finish();
        }, MYSQL_CLOSE_MS);
        l.waiters.add(finish); // 정상 종료 중에 연결이 끊겨도 끝낸다
        try {
          l.c.end(() => finish());
        } catch {
          abort(l, new Error('DB 연결 종료에 실패해 소켓을 닫았습니다'));
          finish();
        }
      });
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
        const r = await conn.execute(withoutTrailingSemicolon(sql, 'oracle'), params, {
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
