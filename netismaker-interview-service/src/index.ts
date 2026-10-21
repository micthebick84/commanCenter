import { loadConfig } from './config.js';
import { JavaApiClient } from './api/javaClient.js';
import { realQuery } from './sdk/sdkAdapter.js';
import { resolveClaudeCli } from './sdk/claudeCli.js';
import { loadBaseMcpServers } from './sdk/mcpBase.js';
import { InterviewRunner } from './runner/interviewRunner.js';
import { ClaimLoop } from './claimLoop.js';

async function main(): Promise<void> {
  // CLI가 system/session_state_changed(idle = 턴 완전 종료)를 내보내게 한다. SDK 0.2.117은 이 변수를 넘기지 않고,
  // 자식 claude 프로세스는 process.env를 물려받는다. turnInputGate가 이 신호로 프롬프트 입력을 닫는다 — 없으면 종전대로
  // 첫 result에서 닫혀 백그라운드 서브에이전트 뒤 권한 요청이 "Stream closed"로 실패한다(2026-10-15).
  process.env.CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS ??= '1';
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
    maxTurns: cfg.maxTurns,
    forceFinishTurns: cfg.forceFinishTurns,
    heartbeatIntervalMs: cfg.heartbeatIntervalMs,
    turnTimeoutMs: cfg.turnTimeoutMs,
    gitTokens: { githubPat: cfg.githubPat, gitlabToken: cfg.gitlabToken },
  });
  const loop = new ClaimLoop(client, runner, {
    pollIntervalMs: cfg.claimPollIntervalMs,
    concurrency: cfg.concurrency,
  });

  const shutdown = (): void => loop.stop();
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  // eslint-disable-next-line no-console
  console.log(
    `[interview-service] worker=${cfg.workerId} concurrency=${cfg.concurrency} cli=${claudeCliPath} mcps=[${Object.keys(mcpsBase).join(', ')}] polling ${cfg.apiBaseUrl} git=[${cfg.githubPat ? 'github' : ''}${cfg.gitlabToken ? ' gitlab' : ''}]`,
  );
  await loop.start();
}

void main();
