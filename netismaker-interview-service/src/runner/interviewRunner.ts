import type { JavaApiClient } from '../api/javaClient.js';
import type { SdkMessage, SdkQuery } from '../sdk/sdkAdapter.js';
import type { InterviewClaimResponse } from '../types.js';
import { buildOptions } from '../sdk/sessionOptions.js';
import { QuotaGuardExceeded, CostGuard } from './costGuard.js';
import { harvestPlan, HarvestError } from './planHarvest.js';
import { relay } from './messageRelay.js';
import { ensureRepo as defaultEnsureRepo, type RepoInput } from './repoPrepare.js';
import { buildWritingPlansSplice, detectHandoff } from './skillDispatch.js';
import { HeartbeatTicker } from './heartbeat.js';

export interface RunnerDeps {
  superpowersPluginPath: string;
  /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
  claudeCliPath: string;
  /** SHADOW per-session quota guard (turns/quota, NOT dollars). */
  quotaGuard: number;
  /** Injectable repo prepare (defaults to the real git clone/fetch). */
  ensureRepo?: (input: RepoInput) => Promise<void>;
  /** Injectable SKILL.md reader for the writing-plans splice fallback (defaults to real fs read). */
  spliceRead?: (path: string, enc: 'utf8') => string;
  heartbeatIntervalMs?: number;
}

/** One async-iterable user prompt for the turn. Fresh => kickoff text; resume => the injected answer. */
async function* promptFor(claim: InterviewClaimResponse): AsyncIterable<{ type: 'user'; text: string }> {
  if (!claim.claudeSessionId) {
    yield {
      type: 'user',
      text:
        `I want to add a feature to the repo at ${claim.githubRepo} (branch ${claim.githubBranch}).\n` +
        `Title: ${claim.title}\nRequest: ${claim.description}\n\n` +
        'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.',
    };
  } else {
    yield { type: 'user', text: claim.lastAnswer ?? '' };
  }
}

/**
 * Runs exactly ONE turn for a claimed session, then returns. Resume-per-answer is the
 * DEFAULT loop: pending state comes from the claim payload (DB), never held in memory.
 * The repo is (re)prepared at claim.workDir before EVERY turn — fresh or resume — because
 * options.resume is cwd-pinned to that checkout (spike 02).
 */
export class InterviewRunner {
  private readonly ensureRepo: (input: RepoInput) => Promise<void>;
  constructor(
    private readonly client: JavaApiClient,
    private readonly query: SdkQuery,
    private readonly deps: RunnerDeps,
  ) {
    this.ensureRepo = deps.ensureRepo ?? defaultEnsureRepo;
  }

  async run(claim: InterviewClaimResponse): Promise<void> {
    const ticker = new HeartbeatTicker(this.client, claim.sessionId, this.deps.heartbeatIntervalMs ?? 15000);
    ticker.start();
    // CostGuard is seeded from 0: the claim carries no prior shadow total (LOCKED CONTRACT
    // InterviewClaimResponse has no totalCostUsd). The guard caps a single runaway turn;
    // server-side accumulates the per-session shadow total from /question + /plan costUsd.
    const guard = new CostGuard(this.deps.quotaGuard, 0);
    try {
      // CLONE: ensure the checkout exists at workDir before the (fresh OR resume) turn.
      await this.ensureRepo({
        githubRepo: claim.githubRepo,
        githubBranch: claim.githubBranch,
        workDir: claim.workDir,
      });

      const options = buildOptions({
        superpowersPluginPath: this.deps.superpowersPluginPath,
        workDir: claim.workDir,
        claudeCliPath: this.deps.claudeCliPath,
        claudeSessionId: claim.claudeSessionId,
      });
      const stream: AsyncIterable<SdkMessage> = this.query({
        prompt: promptFor(claim),
        options,
      });
      const result = await relay(stream);
      guard.add(result.costUsd);

      let assistantText = result.assistantText;
      let sessionId = result.sessionId ?? claim.claudeSessionId ?? '';
      let costUsd = result.costUsd;
      let durationMs = result.durationMs;

      // Skill-dispatch shim: if brainstorming announced the writing-plans handoff
      // but no plan was produced, splice the writing-plans SKILL.md into the SAME
      // session and run one more turn (fallback when 'Skill' did not auto-fire).
      const handoff = detectHandoff(assistantText);
      const hasPlan = /Implementation Plan/m.test(assistantText);
      if (handoff && !hasPlan && sessionId) {
        const splice = buildWritingPlansSplice(this.deps.superpowersPluginPath, this.deps.spliceRead);
        const second = await relay(
          this.query({
            prompt: (async function* () {
              yield { type: 'user', text: splice };
            })(),
            options: buildOptions({
              superpowersPluginPath: this.deps.superpowersPluginPath,
              workDir: claim.workDir,
              claudeCliPath: this.deps.claudeCliPath,
              claudeSessionId: sessionId,
            }),
          }),
        );
        guard.add(second.costUsd);
        assistantText = second.assistantText;
        sessionId = second.sessionId ?? sessionId;
        costUsd = second.costUsd;
        durationMs = second.durationMs;
      }

      if (/Implementation Plan/m.test(assistantText)) {
        const harvest = harvestPlan(assistantText);
        // planJson is sent as a JSON STRING — Java stores it as text/JSONB; frontend parses on use.
        await this.client.postPlan(claim.sessionId, {
          designMarkdown: harvest.designMarkdown,
          planMarkdown: harvest.planMarkdown,
          planJson: JSON.stringify(harvest.planJson),
          costUsd,
          durationMs,
        });
        return;
      }

      await this.client.postQuestion(claim.sessionId, {
        content: assistantText,
        claudeSessionId: sessionId,
        kind: 'question',
        costUsd,
      });
    } catch (err) {
      if (err instanceof QuotaGuardExceeded) {
        await this.client.fail(claim.sessionId, err.message);
        return;
      }
      if (err instanceof HarvestError) {
        await this.client.fail(claim.sessionId, `plan harvest failed: ${err.message}`);
        return;
      }
      await this.client.fail(claim.sessionId, `interview turn failed: ${(err as Error).message}`);
    } finally {
      ticker.stop();
    }
  }
}
