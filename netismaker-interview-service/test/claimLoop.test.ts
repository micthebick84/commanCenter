import { describe, expect, it, vi } from 'vitest';
import { ClaimLoop } from '../src/claimLoop.js';
import { freshClaim } from './fixtures/claims.js';

describe('ClaimLoop', () => {
  it('claims work, hands it to the runner, then continues; stops when stop() called', async () => {
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockResolvedValueOnce(freshClaim).mockResolvedValue(null),
      heartbeat: vi.fn().mockResolvedValue(undefined),
      fail: vi.fn().mockResolvedValue(undefined),
    };
    const runner = { run: vi.fn().mockResolvedValue(undefined) };
    const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

    const p = loop.start();
    // let a few poll cycles happen
    await new Promise((r) => setTimeout(r, 10));
    loop.stop();
    await p;

    expect(runner.run).toHaveBeenCalledWith(freshClaim);
  });

  it('does not run the runner when claim returns null (no work)', async () => {
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockResolvedValue(null),
      heartbeat: vi.fn(),
      fail: vi.fn(),
    };
    const runner = { run: vi.fn() };
    const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await new Promise((r) => setTimeout(r, 5));
    loop.stop();
    await p;

    expect(runner.run).not.toHaveBeenCalled();
  });

  it('reports fail to Java if the runner throws (does not crash the loop)', async () => {
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockResolvedValueOnce(freshClaim).mockResolvedValue(null),
      heartbeat: vi.fn(),
      fail: vi.fn().mockResolvedValue(undefined),
    };
    const runner = { run: vi.fn().mockRejectedValue(new Error('boom')) };
    const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await new Promise((r) => setTimeout(r, 10));
    loop.stop();
    await p;

    expect(client.fail).toHaveBeenCalledWith(freshClaim.sessionId, expect.stringContaining('boom'));
  });

  it('logs the runner crash to stdout (interview.log must not stay silent)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockResolvedValueOnce(freshClaim).mockResolvedValue(null),
      heartbeat: vi.fn(),
      fail: vi.fn().mockResolvedValue(undefined),
    };
    const runner = { run: vi.fn().mockRejectedValue(new Error('boom')) };
    const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await new Promise((r) => setTimeout(r, 10));
    loop.stop();
    await p;

    const logged = warn.mock.calls.map((c) => c.join(' ')).join('\n');
    expect(logged).toContain(String(freshClaim.sessionId));
    expect(logged).toContain('boom');
    warn.mockRestore();
  });

  it('survives when both the runner AND the fail report throw (loop keeps polling)', async () => {
    // 회귀 가드: fail 보고가 네트워크 오류/409로 거부되면 예전엔 start()가 통째로 죽어
    // 워커가 영구 유휴가 됐다 — 보고 실패는 격리하고 다음 틱을 계속 돌아야 한다.
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockResolvedValueOnce(freshClaim).mockResolvedValue(null),
      heartbeat: vi.fn(),
      fail: vi.fn().mockRejectedValue(new Error('api down')),
    };
    const runner = { run: vi.fn().mockRejectedValue(new Error('boom')) };
    const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await new Promise((r) => setTimeout(r, 10));
    loop.stop();
    await p;

    expect(client.fail).toHaveBeenCalled();
    // fail 거부 후에도 루프가 살아서 추가 claim 폴링을 계속했다
    expect(client.claim.mock.calls.length).toBeGreaterThan(1);
  });
});

/** 밖에서 끝낼 수 있는 runner.run — 동시에 몇 턴이 돌고 있는지 센다. */
function controllableRunner() {
  const pending: Array<() => void> = [];
  let active = 0;
  let peak = 0;
  const runner = {
    run: vi.fn(
      () =>
        new Promise<void>((resolve) => {
          active++;
          peak = Math.max(peak, active);
          pending.push(() => {
            active--;
            resolve();
          });
        }),
    ),
  };
  return {
    runner,
    finishOne: () => pending.shift()?.(),
    finishAll: () => {
      while (pending.length) pending.shift()!();
    },
    active: () => active,
    peak: () => peak,
  };
}

const tick = (ms = 10) => new Promise((r) => setTimeout(r, ms));

describe('ClaimLoop concurrency', () => {
  const claims = [1, 2, 3, 4].map((n) => ({ ...freshClaim, sessionId: 100 + n }));

  it('concurrency 1 (default) keeps turns strictly serial', async () => {
    const queue = [...claims];
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockImplementation(async () => queue.shift() ?? null),
      fail: vi.fn(),
    };
    const r = controllableRunner();
    const loop = new ClaimLoop(client as never, r.runner as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await tick();
    expect(r.active()).toBe(1);
    // 앞 턴이 안 끝났으면 다음 claim을 하지 않는다 — 큐에 남은 세션은 다른 워커 몫으로 남는다.
    expect(client.claim).toHaveBeenCalledTimes(1);
    r.finishOne();
    await tick();
    expect(r.runner.run).toHaveBeenCalledTimes(2);
    loop.stop();
    r.finishAll();
    await p;
    expect(r.peak()).toBe(1);
  });

  it('runs up to N turns at once and claims no more until a slot frees', async () => {
    const queue = [...claims];
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockImplementation(async () => queue.shift() ?? null),
      fail: vi.fn(),
    };
    const r = controllableRunner();
    const loop = new ClaimLoop(client as never, r.runner as never, { pollIntervalMs: 1, concurrency: 2 });

    const p = loop.start();
    await tick();
    expect(r.active()).toBe(2);
    expect(client.claim).toHaveBeenCalledTimes(2);
    expect(r.runner.run.mock.calls.map((c) => (c as unknown[])[0])).toEqual([claims[0], claims[1]]);

    r.finishOne();
    await tick();
    expect(r.active()).toBe(2);
    expect(r.runner.run).toHaveBeenCalledTimes(3);
    expect(r.peak()).toBe(2);

    loop.stop();
    r.finishAll();
    await p;
  });

  it('stop() waits for in-flight turns before start() resolves', async () => {
    const queue = [...claims];
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockImplementation(async () => queue.shift() ?? null),
      fail: vi.fn(),
    };
    const r = controllableRunner();
    const loop = new ClaimLoop(client as never, r.runner as never, { pollIntervalMs: 1, concurrency: 3 });

    const p = loop.start();
    await tick();
    expect(r.active()).toBe(3);
    loop.stop();
    let done = false;
    void p.then(() => {
      done = true;
    });
    await tick();
    expect(done).toBe(false);
    r.finishAll();
    await p;
    expect(done).toBe(true);
    // 멈춘 뒤에는 새로 claim하지 않는다.
    expect(client.claim).toHaveBeenCalledTimes(3);
  });

  it('a crashing turn is reported and frees its slot without disturbing the other', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const queue = [claims[0], claims[1], claims[2]];
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [], failed: [] }),
      claim: vi.fn().mockImplementation(async () => queue.shift() ?? null),
      fail: vi.fn().mockResolvedValue(undefined),
    };
    const releases: Array<() => void> = [];
    const runner = {
      run: vi.fn().mockImplementation(async (c: { sessionId: number }) => {
        if (c.sessionId === claims[0]!.sessionId) throw new Error('boom');
        await new Promise<void>((r) => releases.push(r));
      }),
    };
    const loop = new ClaimLoop(client as never, runner as never, { pollIntervalMs: 1, concurrency: 2 });

    const p = loop.start();
    await tick();
    expect(client.fail).toHaveBeenCalledWith(claims[0]!.sessionId, expect.stringContaining('boom'));
    // 실패한 턴의 자리로 세 번째 세션을 claim했고, 두 번째 세션은 그대로 돌고 있다.
    expect(runner.run).toHaveBeenCalledTimes(3);
    expect(releases).toHaveLength(2);
    expect(client.fail).toHaveBeenCalledTimes(1);
    loop.stop();
    releases.forEach((r) => r());
    await p;
    vi.restoreAllMocks();
  });
});

describe('ClaimLoop startup', () => {
  it('does not claim before startup succeeds', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const client = {
      startup: vi.fn(),
      claim: vi.fn().mockResolvedValue(null),
      fail: vi.fn(),
    };
    // startup이 성공하는 순간까지 claim이 몇 번 불렸는지 기록한다.
    let claimsAtSuccess = -1;
    client.startup.mockImplementation(async () => {
      if (client.startup.mock.calls.length < 3) throw new Error('api down');
      claimsAtSuccess = client.claim.mock.calls.length;
      return { released: [1], failed: [] };
    });
    const loop = new ClaimLoop(client as never, { run: vi.fn() } as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await tick(30);
    loop.stop();
    await p;

    expect(client.startup).toHaveBeenCalledTimes(3);
    expect(claimsAtSuccess).toBe(0);
    expect(client.claim).toHaveBeenCalled();
    vi.restoreAllMocks();
  });

  it('proceeds after 404 (startup returns null)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const client = {
      startup: vi.fn().mockResolvedValue(null),
      claim: vi.fn().mockResolvedValue(null),
      fail: vi.fn(),
    };
    const loop = new ClaimLoop(client as never, { run: vi.fn() } as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await tick();
    loop.stop();
    await p;

    expect(client.startup).toHaveBeenCalledTimes(1);
    expect(client.claim).toHaveBeenCalled();
    const logged = [...warn.mock.calls, ...log.mock.calls].map((c) => c.join(' ')).join('\n');
    expect(logged).toContain('[claim-loop] API가 /worker/interviews/startup을 모름 — 기동 정합 건너뜀');
    vi.restoreAllMocks();
  });

  it('logs released/failed on success', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const client = {
      startup: vi.fn().mockResolvedValue({ released: [3, 4], failed: [5] }),
      claim: vi.fn().mockResolvedValue(null),
      fail: vi.fn(),
    };
    const loop = new ClaimLoop(client as never, { run: vi.fn() } as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await tick();
    loop.stop();
    await p;

    const logged = log.mock.calls.map((c) => c.join(' ')).join('\n');
    expect(logged).toContain('[claim-loop] startup: released=[3,4] failed=[5]');
    vi.restoreAllMocks();
  });

  it('stop during startup retry ends start() without claiming', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const client = {
      startup: vi.fn().mockRejectedValue(new Error('api down')),
      claim: vi.fn().mockResolvedValue(null),
      fail: vi.fn(),
    };
    const loop = new ClaimLoop(client as never, { run: vi.fn() } as never, { pollIntervalMs: 1 });

    const p = loop.start();
    await tick();
    expect(client.startup.mock.calls.length).toBeGreaterThan(1);
    loop.stop();
    await p; // 재시도 대기 중 stop()이면 start()가 끝나야 한다
    expect(client.claim).not.toHaveBeenCalled();
    vi.restoreAllMocks();
  });
});
