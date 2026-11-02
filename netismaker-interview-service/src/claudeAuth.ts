import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { childEnv } from './sdk/childEnv.js';

/**
 * claude CLI 로그인 상태 판정 — Java 워커 ClaudeAuthProbe와 같은 규칙(스펙 2026-11-02 §3). 모델을 부르지 않는다.
 *   ① env CLAUDE_CODE_OAUTH_TOKEN(setup-token)이 있으면 OK — 만료 시각은 모른다
 *   ② {CLAUDE_CONFIG_DIR 또는 ~/.claude}/.credentials.json의 claudeAiOauth — 토큰이 다 비었으면 NG(CLI가 갱신에
 *      실패하면 비운다), 만료 시각(refreshTokenExpiresAt)이 지났으면 NG, 아니면 OK
 *   ③ 파일이 없거나 claudeAiOauth가 없으면(macOS 키체인) `claude auth status --json`의 loggedIn
 * `auth status`를 먼저 보지 않는 이유: refresh token이 만료돼도 CLI가 다음 갱신을 시도하기 전까지는 loggedIn=true다.
 * 토큰 문자열은 빈 값인지만 본다 — 로그·결과·오류 메시지에 싣지 않는다.
 */

/** Java ClaudeAuthProbe.Method와 같은 이름 — 하트비트 claudeAuthMethod. */
export type ClaudeAuthMethod = 'ENV_TOKEN' | 'CREDENTIALS' | 'CLI';

export interface ClaudeAuthStatus {
  ok: boolean | null;
  method: ClaudeAuthMethod | null;
  /** ISO-8601 또는 null. */
  expiresAt: string | null;
}

export const UNKNOWN_AUTH: ClaudeAuthStatus = { ok: null, method: null, expiresAt: null };

/** 만료가 이만큼 안으로 남으면 임박 — 워커·화면과 같은 값. 여기서는 로그에만 쓴다. */
export const EXPIRING_SOON_MS = 3 * 24 * 60 * 60 * 1000;

export interface ClaudeAuthDeps {
  env: Record<string, string | undefined>;
  home: string;
  now: () => number;
  /** credentials 파일 내용 — 없거나 읽지 못하면 null. */
  readCredentials: (path: string) => Promise<string | null>;
  /** `claude auth status --json`의 loggedIn — 실행·해석 실패면 null. */
  cliLoggedIn: () => Promise<boolean | null>;
}

/** env 이름을 대소문자 없이 찾는다 — Windows env는 대소문자를 가리지 않는다. */
function envValue(env: Record<string, string | undefined>, name: string): string | undefined {
  if (env[name] !== undefined) return env[name];
  const key = Object.keys(env).find((k) => k.toUpperCase() === name);
  return key === undefined ? undefined : env[key];
}

export function credentialsPath(env: Record<string, string | undefined>, home: string): string {
  const configDir = envValue(env, 'CLAUDE_CONFIG_DIR');
  return join(configDir && configDir.trim() ? configDir : join(home, '.claude'), '.credentials.json');
}

const nonBlank = (v: unknown): boolean => typeof v === 'string' && v.trim() !== '';
const epochMillis = (v: unknown): number | null =>
  typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : null;

/** 파일로 판정할 수 없으면(깨진 JSON·claudeAiOauth 없음) null — CLI에 묻는다. */
export function fromCredentials(text: string, now: number): ClaudeAuthStatus | null {
  let oauth: unknown;
  try {
    oauth = (JSON.parse(text) as { claudeAiOauth?: unknown })?.claudeAiOauth;
  } catch {
    return null; // CLI가 쓰는 중이거나 깨진 파일
  }
  if (!oauth || typeof oauth !== 'object' || Array.isArray(oauth)) return null;
  const o = oauth as Record<string, unknown>;
  const hasRefresh = nonBlank(o.refreshToken);
  const hasAccess = nonBlank(o.accessToken);
  // refresh token이 없고 access token만 있으면 access token의 만료가 끝이다
  const exp = epochMillis(!hasRefresh && hasAccess ? o.expiresAt : o.refreshTokenExpiresAt);
  const expiresAt = exp === null ? null : new Date(exp).toISOString();
  if (!hasRefresh && !hasAccess) return { ok: false, method: 'CREDENTIALS', expiresAt };
  return { ok: exp === null || exp > now, method: 'CREDENTIALS', expiresAt };
}

/** 출력에서 첫 '{'부터 마지막 '}'까지를 JSON으로 읽어 loggedIn — stderr 경고가 섞여도 읽게. */
export function parseLoggedIn(output: string | null | undefined): boolean | null {
  if (!output) return null;
  const start = output.indexOf('{');
  const end = output.lastIndexOf('}');
  if (start < 0 || end < start) return null;
  try {
    const v = (JSON.parse(output.slice(start, end + 1)) as { loggedIn?: unknown }).loggedIn;
    return typeof v === 'boolean' ? v : null;
  } catch {
    return null;
  }
}

export async function checkClaudeAuth(deps: ClaudeAuthDeps): Promise<ClaudeAuthStatus> {
  const token = envValue(deps.env, 'CLAUDE_CODE_OAUTH_TOKEN');
  if (token && token.trim()) return { ok: true, method: 'ENV_TOKEN', expiresAt: null };
  const text = await deps.readCredentials(credentialsPath(deps.env, deps.home));
  const fromFile = text === null ? null : fromCredentials(text, deps.now());
  if (fromFile) return fromFile;
  const loggedIn = await deps.cliLoggedIn();
  return loggedIn === null ? UNKNOWN_AUTH : { ok: loggedIn, method: 'CLI', expiresAt: null };
}

/**
 * `claude auth status --json` — 로그인 안 됨이면 종료 코드 1이라 오류 여부가 아니라 출력 JSON만 본다.
 * 자식 env에서 스택 비밀은 뺀다(childEnv). claude.exe는 단일 프로세스라 execFile 시간 상한으로 충분하다.
 */
export function cliLoggedInVia(cliPath: string, timeoutMs = 30_000): () => Promise<boolean | null> {
  return () =>
    new Promise((resolve) => {
      execFile(
        cliPath,
        ['auth', 'status', '--json'],
        { timeout: timeoutMs, env: childEnv(), windowsHide: true },
        (_err, stdout) => resolve(parseLoggedIn(typeof stdout === 'string' ? stdout : null)),
      );
    });
}

export function realClaudeAuthDeps(cliPath: string): ClaudeAuthDeps {
  return {
    env: process.env,
    home: homedir(),
    now: Date.now,
    readCredentials: (path) => readFile(path, 'utf8').catch(() => null),
    cliLoggedIn: cliLoggedInVia(cliPath),
  };
}
