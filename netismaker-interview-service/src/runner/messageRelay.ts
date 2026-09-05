import type { SdkMessage } from '../sdk/sdkAdapter.js';
import type { ActivityInput, RateLimitInfo } from '../types.js';

export interface RelayResult {
  sessionId: string | null;
  assistantText: string;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  durationMs: number;
  completed: boolean;
  /** 마지막 최상위 assistant 메시지(없으면 message_start) usage의 input+cache_creation+cache_read. 없으면 null. */
  contextTokens: number | null;
  /** result.modelUsage 중 입력 토큰 합이 최대인 모델의 contextWindow. 없으면 null. */
  contextWindow: number | null;
}

export interface RelayOpts {
  /** 활동(도구/텍스트 델타/thinking 델타) 실시간 콜백 — 미전달 시 기존과 100% 동일 동작. */
  onActivity?: (e: ActivityInput) => void;
  /** tool detail의 workDir prefix 상대화용. */
  workDir?: string;
  /** rate_limit_event(구독 한도 스냅샷) 콜백 — 미전달 시 무시. 정규화/보고는 호출자(RateLimitReporter) 책임. */
  onRateLimit?: (info: RateLimitInfo) => void;
}

const MAX_DETAIL = 120;

/** tool_use input에서 사람이 읽을 detail 추출 (Read→file_path, Grep/Glob→pattern, Skill→skill). */
export function summarizeToolUse(
  name: string,
  input: Record<string, unknown> | undefined,
  workDir?: string,
): string | undefined {
  if (!input) return undefined;
  const raw =
    name === 'Read'
      ? input.file_path
      : name === 'Grep' || name === 'Glob'
        ? input.pattern
        : name === 'Skill'
          ? input.skill
          : undefined;
  if (typeof raw !== 'string' || raw.length === 0) return undefined;
  let detail = raw;
  if (workDir && detail.startsWith(workDir)) detail = detail.slice(workDir.length).replace(/^\//, '');
  return detail.length > MAX_DETAIL ? `${detail.slice(0, MAX_DETAIL)}…` : detail;
}

type UsageShape = {
  input_tokens?: unknown;
  cache_creation_input_tokens?: unknown;
  cache_read_input_tokens?: unknown;
};

const CONTEXT_KEYS = ['input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens'] as const;

/** usage → 컨텍스트 토큰(input+cache_creation+cache_read). 세 필드 중 숫자가 하나도 없으면 null. */
export function contextTokensOf(usage: unknown): number | null {
  if (!usage || typeof usage !== 'object') return null;
  const u = usage as UsageShape;
  let sum = 0;
  let seen = false;
  for (const k of CONTEXT_KEYS) {
    const v = u[k];
    if (typeof v === 'number' && Number.isFinite(v)) {
      sum += v;
      seen = true;
    }
  }
  return seen ? sum : null;
}

/** result.modelUsage(Record<model, ModelUsage>)에서 주 모델(입력 토큰 합 최대)의 contextWindow. 없으면 null. */
export function contextWindowOf(modelUsage: unknown): number | null {
  if (!modelUsage || typeof modelUsage !== 'object') return null;
  const n = (x: unknown) => (typeof x === 'number' && Number.isFinite(x) ? x : 0);
  let best: { total: number; window: number } | null = null;
  for (const v of Object.values(modelUsage as Record<string, unknown>)) {
    if (!v || typeof v !== 'object') continue;
    const m = v as {
      inputTokens?: unknown;
      cacheReadInputTokens?: unknown;
      cacheCreationInputTokens?: unknown;
      contextWindow?: unknown;
    };
    if (typeof m.contextWindow !== 'number' || !(m.contextWindow > 0)) continue;
    const total = n(m.inputTokens) + n(m.cacheReadInputTokens) + n(m.cacheCreationInputTokens);
    if (!best || total > best.total) best = { total, window: m.contextWindow };
  }
  return best ? best.window : null;
}

/** parent_tool_use_id가 있으면 서브에이전트 스트림 — 최상위 컨텍스트가 아니다. */
function isTopLevel(msg: SdkMessage): boolean {
  return !(msg as { parent_tool_use_id?: string | null }).parent_tool_use_id;
}

type ContentBlock = { type: string; text?: string; name?: string; input?: Record<string, unknown> };

function blocks(msg: SdkMessage): ContentBlock[] {
  const content = (msg.message as { content?: ContentBlock[] } | undefined)?.content;
  return Array.isArray(content) ? content : [];
}

function extractText(msg: SdkMessage): string {
  return blocks(msg)
    .filter((b) => b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text as string)
    .join('\n');
}

/** stream_event(SDKPartialAssistantMessage)에서 text/thinking 델타를 활동으로 변환. 그 외 무시. */
function deltaActivity(msg: SdkMessage): ActivityInput | null {
  // parent_tool_use_id가 있으면 서브에이전트 스트림 — 최상위 활동이 아니다.
  if ((msg as { parent_tool_use_id?: string | null }).parent_tool_use_id) return null;
  const event = msg.event as
    | { type?: string; delta?: { type?: string; text?: string; thinking?: string } }
    | undefined;
  if (event?.type !== 'content_block_delta') return null;
  if (event.delta?.type === 'text_delta' && event.delta.text) {
    return { type: 'text', content: event.delta.text };
  }
  if (event.delta?.type === 'thinking_delta' && event.delta.thinking) {
    return { type: 'thinking', content: event.delta.thinking };
  }
  return null; // signature_delta / input_json_delta 등
}

/**
 * Drains one SDK turn: captures session_id from init, joins assistant text, reads result usage.
 * opts.onActivity가 있으면 진행 활동(델타·tool_use)을 실시간 방출한다 — assistantText 계약은 불변:
 * 텍스트는 델타로 이미 흘렸으므로 assistant 메시지에서 활동으로 재방출하지 않는다(중복 방지).
 */
export async function relay(stream: AsyncIterable<SdkMessage>, opts?: RelayOpts): Promise<RelayResult> {
  let sessionId: string | null = null;
  const parts: string[] = [];
  let costUsd = 0;
  let inputTokens = 0;
  let outputTokens = 0;
  let cacheCreationTokens = 0;
  let cacheReadTokens = 0;
  let durationMs = 0;
  let completed = false;
  let assistantContext: number | null = null;
  let startContext: number | null = null;
  let contextWindow: number | null = null;
  for await (const msg of stream) {
    if (msg.type === 'system' && msg.subtype === 'init') {
      sessionId = (msg.session_id as string) ?? null;
    } else if (msg.type === 'rate_limit_event') {
      // 구독 한도 스냅샷 — 보고 여부/정규화는 콜백 소유자가 결정한다.
      const info = msg.rate_limit_info;
      if (opts?.onRateLimit && info && typeof info === 'object') opts.onRateLimit(info as RateLimitInfo);
    } else if (msg.type === 'stream_event') {
      // message_start usage = 이 API 호출의 요청 컨텍스트(출력 전). assistant usage가 없을 때의 폴백.
      const ev = msg.event as { type?: string; message?: { usage?: unknown } } | undefined;
      if (ev?.type === 'message_start' && isTopLevel(msg)) {
        const ct = contextTokensOf(ev.message?.usage);
        if (ct !== null) startContext = ct;
      }
      if (!opts?.onActivity) continue;
      const activity = deltaActivity(msg);
      if (activity) opts.onActivity(activity);
    } else if (msg.type === 'assistant') {
      if (isTopLevel(msg)) {
        const ct = contextTokensOf((msg.message as { usage?: unknown } | undefined)?.usage);
        if (ct !== null) assistantContext = ct;
      }
      const t = extractText(msg);
      if (t) parts.push(t);
      if (opts?.onActivity) {
        for (const b of blocks(msg)) {
          if (b.type === 'tool_use' && typeof b.name === 'string') {
            opts.onActivity({
              type: 'tool',
              label: b.name,
              detail: summarizeToolUse(b.name, b.input, opts.workDir),
            });
          }
        }
      }
    } else if (msg.type === 'result') {
      const usage = msg.usage as
        | {
            total_cost_usd?: number;
            input_tokens?: number;
            output_tokens?: number;
            cache_creation_input_tokens?: number;
            cache_read_input_tokens?: number;
          }
        | undefined;
      costUsd = usage?.total_cost_usd ?? 0;
      inputTokens = usage?.input_tokens ?? 0;
      outputTokens = usage?.output_tokens ?? 0;
      cacheCreationTokens = usage?.cache_creation_input_tokens ?? 0;
      cacheReadTokens = usage?.cache_read_input_tokens ?? 0;
      durationMs = (msg.duration_ms as number) ?? 0;
      contextWindow = contextWindowOf(msg.modelUsage);
      completed = true;
    }
  }
  return {
    sessionId,
    assistantText: parts.join('\n\n'),
    costUsd,
    inputTokens,
    outputTokens,
    cacheCreationTokens,
    cacheReadTokens,
    durationMs,
    completed,
    contextTokens: assistantContext ?? startContext,
    contextWindow,
  };
}
