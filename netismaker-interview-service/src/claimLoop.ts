import type { JavaApiClient } from './api/javaClient.js';
import type { InterviewRunner } from './runner/interviewRunner.js';
import type { InterviewClaimResponse } from './types.js';
import { maskSecrets } from './sdk/gitRemote.js';

export interface LoopConfig {
  pollIntervalMs: number;
  /**
   * 동시에 돌릴 턴 수(기본 1 = 직렬). 세션마다 체크아웃 폴더(session-{id})·SDK 옵션·heartbeat·DB MCP가 따로라
   * 턴끼리 공유하는 상태가 없다 — 실제 천장은 PC 메모리(claude CLI 프로세스당 수백 MB)와 구독 한도.
   */
  concurrency?: number;
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * Polls POST /worker/interviews/claim. For each claimed session, hands it to the
 * InterviewRunner (one turn). The runner itself reports /question, /plan, or /fail.
 * A throw escaping the runner is reported via /fail so the loop never dies.
 *
 * 최대 `concurrency`개의 턴을 함께 돌린다. 자리가 차 있으면 claim하지 않으므로 남은 세션은 큐(QUEUED)에 그대로
 * 남는다(SKIP LOCKED라 다른 인터뷰 서비스가 가져갈 수 있다). stop() 뒤에는 새로 claim하지 않고, 진행 중인 턴이
 * 모두 끝나야 start()가 끝난다.
 */
export class ClaimLoop {
  private running = false;
  private readonly inFlight = new Set<Promise<void>>();
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
    const slots = Math.max(1, Math.floor(this.cfg.concurrency ?? 1));
    while (this.running) {
      if (this.inFlight.size >= slots) {
        // 자리가 날 때까지 기다린다 — runOne은 throw하지 않는다.
        await Promise.race(this.inFlight);
        continue;
      }
      let claim: InterviewClaimResponse | null = null;
      try {
        claim = await this.client.claim();
      } catch {
        claim = null; // transient API error: back off and retry next tick
      }
      if (claim) {
        const turn = this.runOne(claim).finally(() => this.inFlight.delete(turn));
        this.inFlight.add(turn);
        // 남은 자리가 있으면 쉬지 않고 바로 다음 세션을 claim한다 — 쌓인 큐를 빨리 채운다.
        continue;
      }
      await sleep(this.cfg.pollIntervalMs);
    }
    await Promise.all(this.inFlight);
  }

  /** 한 턴. 런너를 빠져나온 throw는 /fail로 보고하고 삼킨다 — 다른 턴과 루프에 번지지 않게. */
  private async runOne(claim: InterviewClaimResponse): Promise<void> {
    try {
      await this.runner.run(claim);
    } catch (err) {
      // 런너를 빠져나온 throw는 이례적이다 — 서버 보고와 별개로 stdout에도 남긴다(interview.log).
      // eslint-disable-next-line no-console
      console.warn(
        `[claim-loop] 런너 crash: session=${claim.sessionId} — ${maskSecrets((err as Error).message)}`,
      );
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
}
