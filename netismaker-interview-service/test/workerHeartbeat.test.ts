import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClaudeAuthStatus } from '../src/claudeAuth.js';
import { WorkerHeartbeatReporter, serviceVersion } from '../src/workerHeartbeat.js';
import type { WorkerHeartbeatRequest } from '../src/types.js';

const DEAD: ClaudeAuthStatus = { ok: false, method: 'CREDENTIALS', expiresAt: '2026-10-31T02:23:45.460Z' };
const ALIVE: ClaudeAuthStatus = { ok: true, method: 'CREDENTIALS', expiresAt: '2026-12-01T00:00:00.000Z' };

function setup(checkAuth: () => Promise<ClaudeAuthStatus>, clock = { t: Date.parse('2026-11-02T01:20:00Z') }) {
  const sent: WorkerHeartbeatRequest[] = [];
  const client = { workerHeartbeat: vi.fn(async (b: WorkerHeartbeatRequest) => void sent.push(b)) };
  const reporter = new WorkerHeartbeatReporter(client, {
    workerId: 'interview-worker-1',
    version: 'interview-service/0.1.0',
    intervalMs: 20_000,
    authIntervalMs: 300_000,
    checkAuth,
    now: () => clock.t,
    hostname: 'DESKTOP-HFOCJ9G',
  });
  return { reporter, client, sent, clock };
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'log').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('WorkerHeartbeatReporter (스펙 2026-11-02 §5)', () => {
  it('로그인이 끊긴 PC면 claudeSessionOk=false와 방식·만료 시각을 싣고, MCP는 비운다', async () => {
    const { reporter, sent } = setup(async () => DEAD);
    await reporter.tick();
    expect(sent).toEqual([
      {
        workerId: 'interview-worker-1',
        hostname: 'DESKTOP-HFOCJ9G',
        version: 'interview-service/0.1.0',
        claudeSessionOk: false,
        vpnStatus: null,
        mcps: [],
        lostReportCount: 0,
        claudeAuthMethod: 'CREDENTIALS',
        claudeAuthExpiresAt: '2026-10-31T02:23:45.460Z',
      },
    ]);
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('로그인 끊김'));
  });

  it('판정은 authIntervalMs마다 — 그 사이 하트비트는 마지막 값을 싣는다', async () => {
    const checkAuth = vi.fn<() => Promise<ClaudeAuthStatus>>().mockResolvedValueOnce(DEAD).mockResolvedValueOnce(ALIVE);
    const { reporter, sent, clock } = setup(checkAuth);
    await reporter.tick();
    clock.t += 20_000;
    await reporter.tick();
    expect(checkAuth).toHaveBeenCalledTimes(1);
    expect(sent.map((b) => b.claudeSessionOk)).toEqual([false, false]);

    clock.t += 300_000; // 재로그인 뒤 다음 판정 회차
    await reporter.tick();
    expect(checkAuth).toHaveBeenCalledTimes(2);
    expect(sent[2]!.claudeSessionOk).toBe(true);
    expect(sent[2]!.claudeAuthExpiresAt).toBe(ALIVE.expiresAt);
  });

  it('판정이 던지면 null로 싣고 하트비트는 계속 보낸다', async () => {
    const { reporter, sent } = setup(async () => {
      throw new Error('boom');
    });
    await reporter.tick();
    expect(sent[0]).toMatchObject({ claudeSessionOk: null, claudeAuthMethod: null, claudeAuthExpiresAt: null });
  });

  it('보고 실패는 던지지 않고 연속 실패는 첫 번째만 경고, 다시 성공하면 알린다', async () => {
    const { reporter, client } = setup(async () => ALIVE);
    client.workerHeartbeat.mockRejectedValueOnce(new Error('ECONNREFUSED')).mockRejectedValueOnce(new Error('ECONNREFUSED'));
    await reporter.tick();
    await reporter.tick();
    const failWarns = vi.mocked(console.warn).mock.calls.filter(([m]) => String(m).includes('보고 실패'));
    expect(failWarns).toHaveLength(1);
    await reporter.tick();
    expect(console.log).toHaveBeenCalledWith(expect.stringContaining('다시 성공'));
  });

  it('start는 바로 한 번, 그 뒤 intervalMs마다 보내고 stop하면 멈춘다', async () => {
    vi.useFakeTimers();
    const { reporter, client } = setup(async () => ALIVE);
    reporter.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(client.workerHeartbeat).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(40_000);
    expect(client.workerHeartbeat).toHaveBeenCalledTimes(3);
    reporter.stop();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(client.workerHeartbeat).toHaveBeenCalledTimes(3);
  });

  it('앞선 보고가 끝나기 전의 tick은 겹쳐 보내지 않는다', async () => {
    let release!: (s: ClaudeAuthStatus) => void;
    const { reporter, client } = setup(() => new Promise((r) => (release = r)));
    const first = reporter.tick();
    const second = reporter.tick();
    release(ALIVE);
    await Promise.all([first, second]);
    expect(client.workerHeartbeat).toHaveBeenCalledTimes(1);
  });
});

describe('serviceVersion', () => {
  it('package.json version을 읽고, 못 찾으면 unknown', () => {
    expect(serviceVersion()).toMatch(/^\d+\.\d+\.\d+/);
    expect(serviceVersion(() => '{"name":"other","version":"9.9.9"}')).toBe('unknown');
    expect(
      serviceVersion(() => {
        throw new Error('ENOENT');
      }),
    ).toBe('unknown');
  });
});
