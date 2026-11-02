import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import {
  UNKNOWN_AUTH,
  checkClaudeAuth,
  cliLoggedInVia,
  credentialsPath,
  fromCredentials,
  parseLoggedIn,
  type ClaudeAuthDeps,
} from '../src/claudeAuth.js';

// Java ClaudeAuthProbeTest와 같은 경우를 본다 (스펙 2026-11-02 §3)
const NOW = Date.parse('2026-11-02T01:20:00Z');
const REFRESH_EXP = '2026-10-31T02:23:45.460Z';
const SECRET = 'sk-ant-oat01-SECRET-VALUE';
const DAY = 24 * 60 * 60 * 1000;

function creds(oauth: Record<string, unknown>): string {
  return JSON.stringify({ mcpOAuth: {}, claudeAiOauth: { scopes: ['user:inference'], ...oauth } });
}

function deps(over: Partial<ClaudeAuthDeps> = {}): ClaudeAuthDeps {
  return {
    env: {},
    home: '/home/me',
    now: () => NOW,
    readCredentials: vi.fn().mockResolvedValue(null),
    cliLoggedIn: vi.fn().mockRejectedValue(new Error('CLI를 부르면 안 된다')),
    ...over,
  };
}

describe('checkClaudeAuth', () => {
  it('env setup-token이면 파일·CLI를 보지 않고 OK', async () => {
    const d = deps({ env: { CLAUDE_CODE_OAUTH_TOKEN: SECRET } });
    expect(await checkClaudeAuth(d)).toEqual({ ok: true, method: 'ENV_TOKEN', expiresAt: null });
    expect(d.readCredentials).not.toHaveBeenCalled();
  });

  it('env 이름은 대소문자 무시, 빈 값은 없는 것으로 본다', async () => {
    expect((await checkClaudeAuth(deps({ env: { claude_code_oauth_token: SECRET } }))).method).toBe('ENV_TOKEN');
    const blank = deps({ env: { CLAUDE_CODE_OAUTH_TOKEN: ' ' }, cliLoggedIn: vi.fn().mockResolvedValue(true) });
    expect((await checkClaudeAuth(blank)).method).toBe('CLI');
  });

  it('CLI 로그인 파일: refresh token이 살아 있으면 OK + 그 만료 시각', async () => {
    const exp = NOW + 20 * DAY;
    const d = deps({
      readCredentials: vi.fn().mockResolvedValue(
        creds({ accessToken: SECRET, refreshToken: SECRET, expiresAt: NOW - 3600_000, refreshTokenExpiresAt: exp }),
      ),
    });
    expect(await checkClaudeAuth(d)).toEqual({ ok: true, method: 'CREDENTIALS', expiresAt: new Date(exp).toISOString() });
    expect(d.readCredentials).toHaveBeenCalledWith(join('/home/me', '.claude', '.credentials.json'));
  });

  it('11-02 상태(CLI가 토큰을 비움)는 NG + 지난 만료 시각', async () => {
    const d = deps({
      readCredentials: vi.fn().mockResolvedValue(
        creds({ accessToken: '', refreshToken: '', expiresAt: 0, refreshTokenExpiresAt: Date.parse(REFRESH_EXP) }),
      ),
    });
    expect(await checkClaudeAuth(d)).toEqual({ ok: false, method: 'CREDENTIALS', expiresAt: REFRESH_EXP });
  });

  it('파일이 없으면 claude auth status', async () => {
    expect(await checkClaudeAuth(deps({ cliLoggedIn: vi.fn().mockResolvedValue(false) }))).toEqual({
      ok: false,
      method: 'CLI',
      expiresAt: null,
    });
  });

  it('claudeAiOauth가 없거나 깨진 파일도 CLI로, CLI도 실패하면 판정 없음', async () => {
    const mcpOnly = deps({
      readCredentials: vi.fn().mockResolvedValue(JSON.stringify({ mcpOAuth: { x: { accessToken: SECRET } } })),
      cliLoggedIn: vi.fn().mockResolvedValue(true),
    });
    expect((await checkClaudeAuth(mcpOnly)).method).toBe('CLI');
    const broken = deps({
      readCredentials: vi.fn().mockResolvedValue('{"claudeAiOauth":{"accessToken":'),
      cliLoggedIn: vi.fn().mockResolvedValue(null),
    });
    expect(await checkClaudeAuth(broken)).toEqual(UNKNOWN_AUTH);
  });
});

describe('fromCredentials', () => {
  it('토큰이 남아 있어도 refresh 만료 시각이 지났으면 NG (CLI가 비우기 전)', () => {
    const exp = Date.parse(REFRESH_EXP);
    expect(fromCredentials(creds({ accessToken: SECRET, refreshToken: SECRET, expiresAt: exp, refreshTokenExpiresAt: exp }), NOW))
      .toEqual({ ok: false, method: 'CREDENTIALS', expiresAt: REFRESH_EXP });
  });

  it('만료 시각 필드가 없으면 OK·시각 없음, 토큰도 없으면 NG·시각 없음', () => {
    expect(fromCredentials(creds({ accessToken: SECRET, refreshToken: SECRET, expiresAt: NOW + 1000 }), NOW))
      .toEqual({ ok: true, method: 'CREDENTIALS', expiresAt: null });
    expect(fromCredentials(creds({ accessToken: '', refreshToken: '', expiresAt: 0 }), NOW))
      .toEqual({ ok: false, method: 'CREDENTIALS', expiresAt: null });
  });

  it('access token만 있으면 그 만료가 끝', () => {
    const accessExp = NOW - 60_000;
    expect(fromCredentials(creds({ accessToken: SECRET, refreshToken: '', expiresAt: accessExp }), NOW))
      .toEqual({ ok: false, method: 'CREDENTIALS', expiresAt: new Date(accessExp).toISOString() });
  });

  it('결과에 토큰 값이 실리지 않는다', () => {
    const s = fromCredentials(creds({ accessToken: SECRET, refreshToken: SECRET, refreshTokenExpiresAt: NOW + DAY }), NOW);
    expect(JSON.stringify(s)).not.toContain('SECRET');
  });
});

describe('credentialsPath / parseLoggedIn', () => {
  it('CLAUDE_CONFIG_DIR를 따른다', () => {
    expect(credentialsPath({ CLAUDE_CONFIG_DIR: '/cfg' }, '/home/me')).toBe(join('/cfg', '.credentials.json'));
    expect(credentialsPath({}, '/home/me')).toBe(join('/home/me', '.claude', '.credentials.json'));
  });

  it('auth status JSON — 로그아웃 출력·앞에 섞인 경고도 읽고, 모르면 null', () => {
    expect(parseLoggedIn('{\n  "loggedIn": false,\n  "authMethod": "none"\n}\n')).toBe(false);
    expect(parseLoggedIn('warn: update available\n{"loggedIn": true}')).toBe(true);
    expect(parseLoggedIn("error: unknown command 'auth'")).toBeNull();
    expect(parseLoggedIn('{"authMethod":"none"}')).toBeNull();
    expect(parseLoggedIn('')).toBeNull();
  });

  it('cliLoggedInVia: 실행 파일이 없으면 null(던지지 않음)', async () => {
    expect(await cliLoggedInVia(join('/no', 'such', 'claude-cli'), 5_000)()).toBeNull();
  });
});
