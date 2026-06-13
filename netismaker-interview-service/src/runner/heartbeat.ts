import type { JavaApiClient } from '../api/javaClient.js';

/** Periodically POSTs /worker/interviews/{id}/heartbeat while a turn is in flight. */
export class HeartbeatTicker {
  private timer: ReturnType<typeof setInterval> | null = null;
  constructor(
    private readonly client: Pick<JavaApiClient, 'heartbeat'>,
    private readonly sessionId: number,
    private readonly intervalMs: number,
  ) {}

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => {
      void this.client.heartbeat(this.sessionId).catch((err: unknown) => {
        // 무성 swallow 금지 — heartbeat 실패는 stale 오탐으로 이어지므로 최소한 경고는 남긴다.
        // eslint-disable-next-line no-console
        console.warn(`[heartbeat] session=${this.sessionId} 실패: ${(err as Error).message}`);
      });
    }, this.intervalMs);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
