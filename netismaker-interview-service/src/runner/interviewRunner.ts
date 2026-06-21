import type { JavaApiClient } from '../api/javaClient.js';
import type { SdkMessage, SdkQuery } from '../sdk/sdkAdapter.js';
import type { InterviewClaimResponse } from '../types.js';
import { buildOptions } from '../sdk/sessionOptions.js';
import { QuotaGuardExceeded, CostGuard } from './costGuard.js';
import { tryHarvest } from './planHarvest.js';
import { relay } from './messageRelay.js';
import { ensureRepo as defaultEnsureRepo, type RepoInput } from './repoPrepare.js';
import { buildWritingPlansSplice, buildPlanReformatSplice, detectHandoff, detectPlanIntent } from './skillDispatch.js';
import { HeartbeatTicker } from './heartbeat.js';

export interface RunnerDeps {
  superpowersPluginPath: string;
  /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
  claudeCliPath: string;
  /** SHADOW per-session quota guard (turns/quota, NOT dollars). */
  quotaGuard: number;
  /** 세션 최대 assistant 질문 턴(초과 시 FAILED). */
  maxTurns: number;
  /** 이 턴 수 이상이면 force-finish 프롬프트 사용. */
  forceFinishTurns: number;
  /** Injectable repo prepare (defaults to the real git clone/fetch). */
  ensureRepo?: (input: RepoInput) => Promise<void>;
  /** Injectable SKILL.md reader for the writing-plans splice fallback (defaults to real fs read). */
  spliceRead?: (path: string, enc: 'utf8') => string;
  heartbeatIntervalMs?: number;
}

/**
 * SDK stream-json user message. The claude CLI (>=2.1) `--input-format stream-json`
 * parser requires `message:{role,content}` and throws on the legacy `{type,text}` shape
 * (`undefined is not an object (evaluating '_.message.role')` → process exits 1).
 */
type UserTurn = { type: 'user'; message: { role: 'user'; content: string } };
function userTurn(content: string): UserTurn {
  return { type: 'user', message: { role: 'user', content } };
}

/** One async-iterable user prompt for the turn. Fresh => kickoff text; resume => the injected answer. */
async function* promptFor(claim: InterviewClaimResponse): AsyncIterable<UserTurn> {
  if (!claim.claudeSessionId) {
    yield userTurn(
      `I want to add a feature to the repo at ${claim.githubRepo} (branch ${claim.githubBranch}).\n` +
        `Title: ${claim.title}\nRequest: ${claim.description}\n\n` +
        'IMPORTANT — this is a PLANNING-ONLY interview. Your only deliverable is a written ' +
        'implementation PLAN (via brainstorming → writing-plans), NOT code. Do NOT implement the ' +
        'feature: do not create or modify source files, do not run builds/installs/tests, do not ' +
        'git commit or push. Reading the repo for context is fine. Stop once the plan is written — ' +
        'a human reviews and approves it, and implementation happens later in a separate step.\n\n' +
        '최종 plan은 반드시 다음 정규 형식으로 작성하세요(설명은 한국어): ' +
        '최상위 헤딩 `# <기능> 구현 계획`, 그 아래 각 작업을 `### 작업 N: <제목>` 형식으로. ' +
        '영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용됩니다. ' +
        '이 정확한 구조라야 시스템이 완료를 인식하며, 없으면 인터뷰가 끝나지 않습니다.\n\n' +
        'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.',
    );
  } else {
    yield userTurn(claim.lastAnswer ?? '');
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
    // Seed the guard from the claim's accumulated session total so it is CUMULATIVE across all turns
    // (not just one runaway turn): the server accumulates total_cost_usd on every /question + /plan
    // and the claim carries it back, so a looping interview that never completes eventually trips the
    // guard → fail (safety net for the "completion never detected" loop). Fresh claims carry 0.
    const guard = new CostGuard(this.deps.quotaGuard, claim.totalCostUsd ?? 0);
    try {
      // 턴 상한: claim.turns의 assistant 턴 수로 진행도 판정(백엔드 변경 불필요).
      const assistantTurns = claim.turns.filter((t) => t.role === 'assistant').length;
      if (assistantTurns >= this.deps.maxTurns) {
        await this.client.fail(
          claim.sessionId,
          `최대 질문 턴(${this.deps.maxTurns}) 초과 — plan 미완성`,
        );
        return;
      }
      const forceFinish = assistantTurns >= this.deps.forceFinishTurns;

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
        mcpsExtra: claim.mcpsExtra,
        model: claim.model,
        effort: claim.effort,
      });
      const stream: AsyncIterable<SdkMessage> = this.query({
        prompt:
          // Invariant: forceFinishTurns > 0 means at least one assistant turn has been recorded,
          // which requires a claudeSessionId (the session id is set on the FIRST assistant turn).
          // Therefore `forceFinish && !claudeSessionId` is unreachable in production; the else
          // branch (`promptFor`) only executes on the genuine fresh-start path (turns == 0).
          forceFinish && claim.claudeSessionId
            ? (async function* () { yield userTurn(buildPlanReformatSplice()); })()
            : promptFor(claim),
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
      const hasPlan = tryHarvest(assistantText).ok;
      if (handoff && !hasPlan && sessionId) {
        const splice = buildWritingPlansSplice(this.deps.superpowersPluginPath, this.deps.spliceRead);
        const second = await relay(
          this.query({
            prompt: (async function* () {
              yield userTurn(splice);
            })(),
            options: buildOptions({
              superpowersPluginPath: this.deps.superpowersPluginPath,
              workDir: claim.workDir,
              claudeCliPath: this.deps.claudeCliPath,
              claudeSessionId: sessionId,
              mcpsExtra: claim.mcpsExtra,
              model: claim.model,
              effort: claim.effort,
            }),
          }),
        );
        guard.add(second.costUsd);
        assistantText = second.assistantText;
        sessionId = second.sessionId ?? sessionId;
        costUsd = second.costUsd;
        durationMs = second.durationMs;
      }

      let harvested = tryHarvest(assistantText);
      // near-miss 보정: 추출 실패 + plan 의도 신호 시, 같은 세션에 정규 형식 재요청 1회.
      // force-finish는 이미 reformat 프롬프트이므로 이중 splice 방지.
      if (!harvested.ok && !forceFinish && detectPlanIntent(assistantText) && sessionId) {
        const reformatSplice = buildPlanReformatSplice();
        const retry = await relay(
          this.query({
            prompt: (async function* () { yield userTurn(reformatSplice); })(),
            options: buildOptions({
              superpowersPluginPath: this.deps.superpowersPluginPath,
              workDir: claim.workDir,
              claudeCliPath: this.deps.claudeCliPath,
              claudeSessionId: sessionId,
              mcpsExtra: claim.mcpsExtra,
              model: claim.model,
              effort: claim.effort,
            }),
          }),
        );
        guard.add(retry.costUsd);
        assistantText = retry.assistantText;
        sessionId = retry.sessionId ?? sessionId;
        costUsd = retry.costUsd;
        durationMs = retry.durationMs;
        harvested = tryHarvest(assistantText);
      }
      if (harvested.ok) {
        // planJson is sent as a JSON STRING — Java stores it as text/JSONB; frontend parses on use.
        await this.client.postPlan(claim.sessionId, {
          designMarkdown: harvested.harvest.designMarkdown,
          planMarkdown: harvested.harvest.planMarkdown,
          planJson: JSON.stringify(harvested.harvest.planJson),
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
      await this.client.fail(claim.sessionId, `interview turn failed: ${(err as Error).message}`);
    } finally {
      ticker.stop();
    }
  }
}
