import { loadConfig } from './config.js';
import { JavaApiClient } from './api/javaClient.js';
import { realQuery } from './sdk/sdkAdapter.js';
import { resolveClaudeCli } from './sdk/claudeCli.js';
import { InterviewRunner } from './runner/interviewRunner.js';
import { ClaimLoop } from './claimLoop.js';

async function main(): Promise<void> {
  const cfg = loadConfig();
  // Subscription auth: resolve the local claude CLI (Phase-0 spike 00b). No ANTHROPIC_API_KEY.
  const claudeCliPath = resolveClaudeCli(cfg.claudeCliPath);
  const client = new JavaApiClient(cfg);
  const runner = new InterviewRunner(client, realQuery, {
    superpowersPluginPath: cfg.superpowersPluginPath,
    claudeCliPath,
    quotaGuard: cfg.quotaGuard,
    heartbeatIntervalMs: cfg.heartbeatIntervalMs,
  });
  const loop = new ClaimLoop(client, runner, { pollIntervalMs: cfg.claimPollIntervalMs });

  const shutdown = (): void => loop.stop();
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  // eslint-disable-next-line no-console
  console.log(`[interview-service] worker=${cfg.workerId} cli=${claudeCliPath} polling ${cfg.apiBaseUrl}`);
  await loop.start();
}

void main();
