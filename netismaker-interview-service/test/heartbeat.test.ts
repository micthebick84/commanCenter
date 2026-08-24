import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HeartbeatTicker } from '../src/runner/heartbeat.js';

// fake timers: 실타이머(2ms 간격 + 9ms 슬립)는 러너 부하 시 인터벌이 밀려 ~1/10 간헐 실패했다
// (CI/로컬 공통). 가짜 타이머로 발화 횟수를 결정적으로 고정한다.
beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('HeartbeatTicker', () => {
  it('sends heartbeats on the interval until stopped', async () => {
    const client = { heartbeat: vi.fn().mockResolvedValue(undefined) };
    const ticker = new HeartbeatTicker(client as never, 42, 1000);
    ticker.start();
    await vi.advanceTimersByTimeAsync(3000); // 정확히 3회 발화
    ticker.stop();
    expect(client.heartbeat).toHaveBeenCalledTimes(3);
    expect(client.heartbeat).toHaveBeenCalledWith(42);
    // no more after stop
    await vi.advanceTimersByTimeAsync(5000);
    expect(client.heartbeat).toHaveBeenCalledTimes(3);
  });
});
