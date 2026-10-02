/**
 * Read-only SQL checker for question session DB tools (spec 2026-10-02 8.2).
 * Best effort - final defense is read-only DB account.
 *
 * Principle: when our parser and DB parser could see boundaries differently,
 * we always choose the side that sees more (reject side).
 *  - String literals accept only double-quote escape; backslash inside causes rejection.
 *  - Hash is mysql comment only (operator in PG); dashes need space after in mysql.
 *  - Dollar quotes are postgres-only; backticks are mysql-only identifiers.
 *  - MySQL execution comments and MariaDB markers are rejected.
 *  - Block comments do not nest (PG allows nesting; we close early, seeing more).
 *  - I-1: PG non-ASCII identifiers before $$ are rejected (code point >= 0x80).
 *  - I-2: Oracle q-quote alternative literals are rejected.
 *  - I-3: PG functions accepting SQL strings are rejected.
 */
export type SqlDialect = 'postgres' | 'mysql' | 'oracle';
export type SqlCheck = { ok: true } | { ok: false; reason: string };

const LEADING = new Set(['SELECT', 'WITH', 'EXPLAIN', 'SHOW', 'DESC', 'DESCRIBE', 'VALUES', 'TABLE']);

const FORBIDDEN_WORDS = new Set([
  'INSERT', 'UPDATE', 'DELETE', 'MERGE', 'UPSERT', 'CREATE', 'ALTER', 'DROP', 'TRUNCATE', 'RENAME', 'GRANT', 'REVOKE',
  'COMMIT', 'ROLLBACK', 'SAVEPOINT', 'CALL', 'EXEC', 'EXECUTE', 'DO', 'COPY', 'LOCK', 'UNLOCK', 'SET', 'RESET', 'INTO',
  'LOAD', 'HANDLER', 'PREPARE', 'DEALLOCATE', 'LISTEN', 'NOTIFY', 'VACUUM', 'ANALYZE', 'ANALYSE', 'REINDEX', 'CLUSTER',
  'REFRESH', 'DISCARD', 'KILL', 'SHUTDOWN', 'PURGE', 'FLUSH', 'OPTIMIZE', 'REPAIR',
]);

const FORBIDDEN_FUNCS = new Set([
  'pg_terminate_backend', 'pg_cancel_backend', 'pg_reload_conf', 'pg_rotate_logfile', 'set_config', 'pg_read_file',
  'pg_read_binary_file', 'pg_ls_dir', 'pg_stat_file', 'pg_sleep', 'pg_sleep_for', 'pg_sleep_until', 'nextval', 'setval',
  'load_file', 'sleep', 'benchmark', 'get_lock', 'release_lock',
  'ts_stat', 'ts_rewrite', 'pg_notify',
]);
const FORBIDDEN_PREFIXES = ['lo_', 'dblink', 'pg_advisory', 'pg_logical_', 'dbms_', 'utl_', 'query_to_', 'cursor_to_'];

function isIdentChar(ch: string | undefined): boolean {
  if (ch === undefined) return false;
  const code = ch.charCodeAt(0);
  return /[A-Za-z0-9_$#]/.test(ch) || code >= 0x80;
}

/**
 * Strip comments, strings, and quoted identifiers, replacing with whitespace or placeholders.
 * Returns error if dangerous or ambiguous forms are detected.
 */
export function stripSql(sql: string, dialect: SqlDialect): string | { error: string } {
  const r = scanSql(sql, dialect);
  return 'error' in r ? r : r.out;
}

/**
 * Index of the last semicolon outside strings/comments/quoted identifiers (same scanning rules as stripSql),
 * or -1 if there is none or the text is rejected by the scanner. Linear time.
 */
export function lastTopLevelSemicolon(sql: string, dialect: SqlDialect): number {
  const r = scanSql(sql, dialect);
  return 'error' in r ? -1 : r.lastSemicolon;
}

function scanSql(sql: string, dialect: SqlDialect): { out: string; lastSemicolon: number } | { error: string } {
  const backtick = String.fromCharCode(96);
  let out = '';
  let lastSemicolon = -1;
  let i = 0;
  const n = sql.length;
  while (i < n) {
    const ch = sql[i]!;
    const next = sql[i + 1];
    // -- comment (mysql requires space after)
    if (ch === '-' && next === '-') {
      const after = sql[i + 2];
      if (dialect !== 'mysql' || after === undefined || /\s/.test(after)) {
        const e = sql.indexOf('\n', i);
        i = e === -1 ? n : e;
        out += ' ';
        continue;
      }
    }
    if (ch === '#' && dialect === 'mysql') {
      const e = sql.indexOf('\n', i);
      i = e === -1 ? n : e;
      out += ' ';
      continue;
    }
    if (ch === '/' && next === '*') {
      if (dialect === 'mysql' && (sql[i + 2] === '!' || (sql[i + 2] === 'M' && sql[i + 3] === '!'))) {
        return { error: 'MySQL 실행 주석(/*! */)은 허용하지 않습니다' };
      }
      const e = sql.indexOf('*/', i + 2);
      if (e === -1) return { error: '닫히지 않은 주석' };
      i = e + 2;
      out += ' ';
      continue;
    }
    // Check for Oracle q'...' alternative quoting before processing single quotes
    if (dialect === 'oracle' && ch === "'" && i > 0) {
      const prevChar = sql[i - 1];
      if (prevChar === 'q' || prevChar === 'Q') {
        // Could be q' or nq'/Nq'/nQ'/NQ'
        const prevPrevChar = i > 1 ? sql[i - 2] : undefined;
        if (prevPrevChar === 'n' || prevPrevChar === 'N') {
          // Check nq' pattern: is the character before n an identifier char?
          const prevPrevPrevChar = i > 2 ? sql[i - 3] : undefined;
          if (!prevPrevPrevChar || !isIdentChar(prevPrevPrevChar)) {
            // nq is a quote marker, not part of an identifier
            return { error: "Oracle q'[...'] 대안 인용은 허용하지 않습니다" };
          }
        } else if (!prevPrevChar || !isIdentChar(prevPrevChar)) {
          // Check q' pattern: is the character before q an identifier char?
          // q is a quote marker, not part of an identifier
          return { error: "Oracle q'[...'] 대안 인용은 허용하지 않습니다" };
        }
      }
    }
    if (ch === "'" || (ch === '"' && dialect === 'mysql')) {
      const end = scanQuoted(sql, i, ch);
      if (typeof end !== 'number') return end;
      i = end;
      out += "''";
      continue;
    }
    if (ch === '"' || (ch === backtick && dialect === 'mysql')) {
      const end = scanQuoted(sql, i, ch);
      if (typeof end !== 'number') return end;
      i = end;
      out += ' x ';
      continue;
    }
    if (ch === '$' && dialect === 'postgres' && !isIdentChar(sql[i - 1])) {
      const m = /^\$([A-Za-z_][A-Za-z0-9_]*)?\$/.exec(sql.slice(i));
      if (m) {
        const tag = m[0];
        const e = sql.indexOf(tag, i + tag.length);
        if (e === -1) return { error: '닫히지 않은 달러 인용' };
        i = e + tag.length;
        out += "''";
        continue;
      }
    }
    if (ch === ';') lastSemicolon = i;
    out += ch;
    i++;
  }
  return { out, lastSemicolon };
}

/**
 * Scan a quoted string or identifier from start position.
 * Consecutive quotes are treated as escape. Backslash causes rejection.
 */
function scanQuoted(sql: string, start: number, quote: string): number | { error: string } {
  let j = start + 1;
  while (j < sql.length) {
    const c = sql[j]!;
    if (c === '\\') return { error: '백슬래시가 든 문자열/식별자는 허용하지 않습니다' };
    if (c === quote) {
      if (sql[j + 1] === quote) {
        j += 2;
        continue;
      }
      return j + 1;
    }
    j++;
  }
  return { error: '닫히지 않은 문자열/식별자' };
}

export function checkReadOnlySql(sql: string, dialect: SqlDialect): SqlCheck {
  const stripped = stripSql(sql, dialect);
  if (typeof stripped !== 'string') return { ok: false, reason: stripped.error };
  const body = stripped.trim().replace(/;\s*$/, '').trim();
  if (body.length === 0) return { ok: false, reason: '빈 문장' };
  if (body.includes(';')) return { ok: false, reason: '여러 문장은 실행할 수 없습니다' };
  const words = body.match(/[A-Za-z_][A-Za-z0-9_$#]*/g) ?? [];
  const first = (words[0] ?? '').toUpperCase();
  if (!LEADING.has(first)) return { ok: false, reason: `조회문만 허용됩니다(시작 키워드 ${first || '없음'})` };
  for (const w of words) {
    const upper = w.toUpperCase();
    if (FORBIDDEN_WORDS.has(upper)) return { ok: false, reason: `금지 키워드 ${upper}` };
    const lower = w.toLowerCase();
    if (FORBIDDEN_FUNCS.has(lower) || FORBIDDEN_PREFIXES.some((p) => lower.startsWith(p))) {
      return { ok: false, reason: `금지 함수 ${lower}` };
    }
  }
  return { ok: true };
}
