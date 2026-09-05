/**
 * 스파이크 — 구독 계정에서 SDK 0.2.117이 실제로 rate_limit_event를 내보내는지,
 * SDKRateLimitInfo.utilization(분수/퍼센트)·resetsAt(초/ms) 단위, 최상위 assistant 메시지의
 * message.usage, result.modelUsage[*].contextWindow shape을 실측한다
 * (스펙 docs/superpowers/specs/2026-09-05-question-chat-ui-design.md §4.3).
 *
 * 실행(운영자 macOS, claude 구독 로그인):
 *   cd netismaker-interview-service && set -a && source .env 2>/dev/null; set +a
 *   node --import tsx scripts/spikeRateLimit.ts
 * 산출: stdout 요약 + /tmp/rate-limit-capture.jsonl (모델 출력 포함 — 커밋 금지).
 * 판정: 마지막 RESULT 줄. rate_limit_event=0건이면 사용량 경로(Task 5·7·9) 착수 전 보고.
 * QUESTION 옵션(default-deny, Read는 workDir 한정)이라 부수효과 없음.
 */
import { appendFileSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';
import { buildOptions } from '../src/sdk/sessionOptions.js';

const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);
const workDir = mkdtempSync(join(tmpdir(), 'spike-rate-limit-'));
writeFileSync(join(workDir, 'a.md'), '# spike\n\n한 줄짜리 파일입니다.\n');
const capture = '/tmp/rate-limit-capture.jsonl';
writeFileSync(capture, '');

async function* prompt(): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield {
    type: 'user',
    message: { role: 'user', content: '현재 디렉터리의 a.md를 Read 도구로 읽고 한 문장으로 요약해줘.' },
  };
}

async function run(): Promise<void> {
  const stream = realQuery({
    prompt: prompt(),
    options: buildOptions({
      superpowersPluginPath: '',
      workDir,
      claudeCliPath,
      claudeSessionId: null,
      model: 'claude-haiku-4-5',
      effort: 'low',
      sessionKind: 'QUESTION',
    }),
  });
  let rateLimits = 0;
  let lastAssistantUsage: unknown = null;
  let lastMessageStartUsage: unknown = null;
  let modelUsage: unknown = null;
  for await (const msg of stream) {
    appendFileSync(capture, `${JSON.stringify(msg)}\n`);
    const topLevel = !(msg as { parent_tool_use_id?: string | null }).parent_tool_use_id;
    if (msg.type === 'rate_limit_event') {
      rateLimits += 1;
      // eslint-disable-next-line no-console
      console.log('[rate_limit_event]', JSON.stringify(msg.rate_limit_info));
    } else if (msg.type === 'stream_event' && topLevel) {
      const ev = msg.event as { type?: string; message?: { usage?: unknown } } | undefined;
      if (ev?.type === 'message_start') lastMessageStartUsage = ev.message?.usage ?? null;
    } else if (msg.type === 'assistant' && topLevel) {
      lastAssistantUsage = (msg.message as { usage?: unknown } | undefined)?.usage ?? null;
    } else if (msg.type === 'result') {
      modelUsage = msg.modelUsage ?? null;
    }
  }
  // eslint-disable-next-line no-console
  console.log('[message_start.usage(last top-level)]', JSON.stringify(lastMessageStartUsage));
  // eslint-disable-next-line no-console
  console.log('[assistant.usage(last top-level)]', JSON.stringify(lastAssistantUsage));
  // eslint-disable-next-line no-console
  console.log('[result.modelUsage]', JSON.stringify(modelUsage));
  // eslint-disable-next-line no-console
  console.log(
    `RESULT: rate_limit_event=${rateLimits}건 assistantUsage=${lastAssistantUsage ? '있음' : '없음'} ` +
      `messageStartUsage=${lastMessageStartUsage ? '있음' : '없음'} modelUsage=${modelUsage ? '있음' : '없음'} capture=${capture}`,
  );
  process.exit(rateLimits > 0 && (lastAssistantUsage || lastMessageStartUsage) && modelUsage ? 0 : 1);
}

void run();
