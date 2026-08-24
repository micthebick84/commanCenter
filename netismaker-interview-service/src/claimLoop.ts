import type { JavaApiClient } from './api/javaClient.js';
import type { InterviewRunner } from './runner/interviewRunner.js';

export interface LoopConfig {
  pollIntervalMs: number;
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * Polls POST /worker/interviews/claim. For each claimed session, hands it to the
 * InterviewRunner (one turn). The runner itself reports /question, /plan, or /fail.
 * A throw escaping the runner is reported via /fail so the loop never dies.
 */
export class ClaimLoop {
  private running = false;
  constructor(
    private readonly client: JavaApiClient,
    private readonly runner: InterviewRunner,
    private readonly cfg: LoopConfig,
  ) {}

  stop(): void {
    this.running = false;
  }

  async start(): Promise<void> {
    this.running = true;
    while (this.running) {
      let claim: Awaited<ReturnType<JavaApiClient['claim']>> = null;
      try {
        claim = await this.client.claim();
      } catch {
        claim = null; // transient API error: back off and retry next tick
      }
      if (claim) {
        try {
          await this.runner.run(claim);
        } catch (err) {
          // fail 보고 자체가 실패(네트워크/409)해도 루프는 계속 돌아야 한다 — 여기서 격리.
          try {
            await this.client.fail(claim.sessionId, `runner crashed: ${(err as Error).message}`);
          } catch (failErr) {
            // eslint-disable-next-line no-console
            console.warn(
              `[claim-loop] fail 보고 실패: session=${claim.sessionId} — ${(failErr as Error).message}`,
            );
          }
        }
      }
      await sleep(this.cfg.pollIntervalMs);
    }
  }
}
