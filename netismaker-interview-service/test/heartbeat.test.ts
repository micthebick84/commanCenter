import { describe, expect, it, vi } from 'vitest';
import { HeartbeatTicker } from '../src/runner/heartbeat.js';

describe('HeartbeatTicker', () => {
  it('sends heartbeats on the interval until stopped', async () => {
    const client = { heartbeat: vi.fn().mockResolvedValue(undefined) };
    const ticker = new HeartbeatTicker(client as never, 42, 2);
    ticker.start();
    await new Promise((r) => setTimeout(r, 9));
    ticker.stop();
    const calls = client.heartbeat.mock.calls.length;
    expect(calls).toBeGreaterThanOrEqual(2);
    expect(client.heartbeat).toHaveBeenCalledWith(42);
    // no more after stop
    await new Promise((r) => setTimeout(r, 6));
    expect(client.heartbeat.mock.calls.length).toBe(calls);
  });
});
