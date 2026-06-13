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
      void this.client.heartbeat(this.sessionId).catch(() => undefined);
    }, this.intervalMs);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
