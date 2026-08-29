import { loadConfig } from './config.js';
import { JavaApiClient } from './api/javaClient.js';
import { realQuery } from './sdk/sdkAdapter.js';
import { resolveClaudeCli } from './sdk/claudeCli.js';
import { loadBaseMcpServers } from './sdk/mcpBase.js';
import { InterviewRunner } from './runner/interviewRunner.js';
import { ClaimLoop } from './claimLoop.js';

async function main(): Promise<void> {
  const cfg = loadConfig();
  // Subscription auth: resolve the local claude CLI (Phase-0 spike 00b). No ANTHROPIC_API_KEY.
  const claudeCliPath = resolveClaudeCli(cfg.claudeCliPath);
  // 베이스 MCP 합본: 부팅 시 1회 스냅샷 (Java WorkerMcpSupport @PostConstruct 패리티).
  const mcpsBase = loadBaseMcpServers();
  const client = new JavaApiClient(cfg);
  const runner = new InterviewRunner(client, realQuery, {
    superpowersPluginPath: cfg.superpowersPluginPath,
    claudeCliPath,
    mcpsBase,
    quotaGuard: cfg.quotaGuard,
    maxTurns: cfg.maxTurns,
    forceFinishTurns: cfg.forceFinishTurns,
    heartbeatIntervalMs: cfg.heartbeatIntervalMs,
    turnTimeoutMs: cfg.turnTimeoutMs,
  });
  const loop = new ClaimLoop(client, runner, { pollIntervalMs: cfg.claimPollIntervalMs });

  const shutdown = (): void => loop.stop();
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  // eslint-disable-next-line no-console
  console.log(
    `[interview-service] worker=${cfg.workerId} cli=${claudeCliPath} mcps=[${Object.keys(mcpsBase).join(', ')}] polling ${cfg.apiBaseUrl}`,
  );
  await loop.start();
}

void main();
