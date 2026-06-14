import { describe, expect, it, vi } from 'vitest';
import { ClaimLoop } from '../src/claimLoop.js';
import { freshClaim } from './fixtures/claims.js';

describe('ClaimLoop', () => {
  it('claims work, hands it to the runner, then continues; stops when stop() called', async () => {
    const client = {
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
    const client = { claim: vi.fn().mockResolvedValue(null), heartbeat: vi.fn(), fail: vi.fn() };
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
});
