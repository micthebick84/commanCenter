import {
  createSdkMcpServer,
  tool,
  type McpSdkServerConfigWithInstance,
  type SdkMcpToolDefinition,
} from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import type { DbConnectionRef, DbType, InterviewClaimResponse } from '../types.js';
import { catalogSql } from './db/catalog.js';
import { DB_LIMITS } from './db/limits.js';
import { formatResult } from './db/result.js';
import { openSession as realOpenSession, type DbSession } from './db/adapters.js';
import { checkReadOnlySql, type SqlDialect } from './sqlReadOnly.js';

/**
 * 질문 세션 내장 DB MCP (스펙 2026-10-02 §6.2). 연결마다 SDK MCP 서버 `db-<id>`를 이 프로세스 안에 만든다.
 * SDK는 type:'sdk' 서버를 CLI에 {type:'sdk', name}으로만 넘기므로(스펙 §2.1) 접속정보가 명령줄·파일·자식 env로 나가지 않는다.
 * DB 접속은 첫 도구 호출 때(lazy), 러너가 턴 종료 finally에서 close()한다.
 */

const SERVER_NAME = /^db-\d+$/;
// eslint-disable-next-line no-control-regex
const IDENT = z.string().min(1).max(128).regex(/^[^\u0000-\u001f\u007f]+$/, '제어문자는 쓸 수 없습니다');

type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };
const textResult = (text: string, isError = false): ToolResult => ({
  content: [{ type: 'text', text }],
  ...(isError ? { isError: true } : {}),
});

export interface PreparedDbMcp {
  servers: Record<string, McpSdkServerConfigWithInstance>;
  /** 서버별 도구 정의 — 테스트·진단용(핸들러 직접 호출). */
  tools: Record<string, SdkMcpToolDefinition[]>;
  dialects: Record<string, SqlDialect>;
  labels: Array<{ serverName: string; label: string }>;
  notices: string[];
  close(): Promise<void>;
}

export interface DbMcpDeps {
  /** 테스트용 주입 — 기본 adapters.openSession. */
  openSession?: (ref: DbConnectionRef) => Promise<DbSession>;
}

export function dialectFor(t: DbType): SqlDialect {
  if (t === 'POSTGRESQL') return 'postgres';
  if (t === 'ORACLE') return 'oracle';
  return 'mysql'; // MYSQL, MARIADB
}

/** 드라이버 오류 메시지에서 비밀번호를 지우고 500자로 자른다. 도구 결과·로그로 나가는 모든 오류 문구가 거친다. */
export function safeMessage(e: unknown, ref: Pick<DbConnectionRef, 'password'>): string {
  let msg = e instanceof Error ? e.message : String(e);
  if (ref.password) msg = msg.split(ref.password).join('****');
  return msg.length > 500 ? `${msg.slice(0, 500)}…` : msg;
}

/** 한 연결의 도구 3개. getSession은 lazy 접속(첫 호출 때 연다). */
export function dbToolsFor(
  ref: DbConnectionRef,
  dialect: SqlDialect,
  getSession: () => Promise<DbSession>,
): SdkMcpToolDefinition[] {
  const guarded = async (fn: (s: DbSession) => Promise<string>): Promise<ToolResult> => {
    try {
      return textResult(await fn(await getSession()));
    } catch (e) {
      return textResult(`DB 오류: ${safeMessage(e, ref)}`, true);
    }
  };
  return [
    tool(
      'query',
      `${ref.label} 읽기 전용 조회. SELECT 계열 단일 문장만 실행되고, 결과는 최대 ${DB_LIMITS.maxRows}행, ` +
        `${DB_LIMITS.statementTimeoutMs / 1000}초 제한이다. 큰 테이블은 WHERE·LIMIT으로 좁혀서 조회할 것.`,
      { sql: z.string().min(1).max(100_000) },
      async ({ sql }) => {
        // 게이트(permissions.ts)가 1차, 여기는 우회 방지(Review Focus 6). 통과해도 어댑터가 READ ONLY 트랜잭션으로 실행한다.
        const check = checkReadOnlySql(sql, dialect);
        if (!check.ok) {
          return textResult(`읽기 전용 도구입니다 — ${check.reason}. SELECT 계열 단일 문장만 실행할 수 있습니다.`, true);
        }
        return guarded(async (s) => formatResult(await s.run(sql, [], DB_LIMITS.maxRows)));
      },
    ),
    tool(
      'list_tables',
      `${ref.label}의 테이블·뷰 목록. schema를 생략하면 기본 스키마(PostgreSQL은 시스템 스키마 제외 전체).`,
      { schema: IDENT.optional() },
      async ({ schema }) =>
        guarded(async (s) => {
          const c = catalogSql(dialect, 'tables', schema ?? null);
          return formatResult(await s.run(c.sql, c.params, DB_LIMITS.maxCatalogRows), DB_LIMITS.maxCatalogRows);
        }),
    ),
    tool(
      'describe_table',
      `${ref.label}의 테이블 컬럼(이름·타입·널·기본값·주석)과 인덱스. 결과는 {"columns":…,"indexes":…} JSON 한 덩어리.`,
      { table: IDENT, schema: IDENT.optional() },
      async ({ table, schema }) =>
        guarded(async (s) => {
          const c = catalogSql(dialect, 'columns', schema ?? null, table);
          const i = catalogSql(dialect, 'indexes', schema ?? null, table);
          const cols = await s.run(c.sql, c.params, DB_LIMITS.maxCatalogRows);
          const idx = await s.run(i.sql, i.params, DB_LIMITS.maxCatalogRows);
          // 한 JSON 객체로 합친다 — 두 덩어리가 전체 길이 상한(maxResultChars)을 나눠 쓰도록 각각 절반씩.
          const half = Math.floor(DB_LIMITS.maxResultChars / 2);
          const columns = formatResult(cols, DB_LIMITS.maxCatalogRows, half);
          const indexes = formatResult(idx, DB_LIMITS.maxCatalogRows, half);
          return `{"columns":${columns},"indexes":${indexes}}`;
        }),
    ),
  ] as unknown as SdkMcpToolDefinition[]; // 도구마다 입력 스키마 타입이 달라 공통 배열 타입으로 맞춘다
}

export function createDbMcp(claim: InterviewClaimResponse, deps: DbMcpDeps = {}): PreparedDbMcp {
  const refs = Array.isArray(claim.dbConnections) ? claim.dbConnections : [];
  const notices = Array.isArray(claim.dbNotices) ? [...claim.dbNotices] : [];
  const open = deps.openSession ?? ((ref: DbConnectionRef) => realOpenSession(ref));
  const opened: Array<{ serverName: string; session: Promise<DbSession> }> = [];
  const prepared: PreparedDbMcp = {
    servers: {},
    tools: {},
    dialects: {},
    labels: [],
    notices,
    async close() {
      for (const o of opened) {
        try {
          await (await o.session).close();
        } catch (e) {
          // 접속 자체가 실패했거나 닫기 실패 — 턴 결과에는 영향 없음. 서버 이름만 남긴다(메시지에 접속정보가 섞일 수 있다).
          // eslint-disable-next-line no-console
          console.warn(`[dbMcp] 커넥션 종료 건너뜀: ${o.serverName} (${(e as { code?: string }).code ?? 'error'})`);
        }
      }
    },
  };
  for (const ref of refs) {
    if (!SERVER_NAME.test(ref.serverName)) {
      notices.push(`잘못된 DB 서버 이름이라 건너뜁니다: ${ref.label}`);
      continue;
    }
    const dialect = dialectFor(ref.dbType);
    let current: Promise<DbSession> | null = null;
    const getSession = (): Promise<DbSession> => {
      if (!current) {
        const p = open(ref);
        current = p;
        opened.push({ serverName: ref.serverName, session: p });
        p.catch(() => {
          if (current === p) current = null; // 접속 실패 — 다음 호출이 다시 시도
        });
      }
      return current;
    };
    const tools = dbToolsFor(ref, dialect, getSession);
    prepared.servers[ref.serverName] = createSdkMcpServer({ name: ref.serverName, version: '1.0.0', tools });
    prepared.tools[ref.serverName] = tools;
    prepared.dialects[ref.serverName] = dialect;
    prepared.labels.push({ serverName: ref.serverName, label: ref.label });
  }
  return prepared;
}
