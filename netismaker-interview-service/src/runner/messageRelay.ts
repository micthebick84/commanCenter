import type { SdkMessage } from '../sdk/sdkAdapter.js';

export interface RelayResult {
  sessionId: string | null;
  assistantText: string;
  costUsd: number;
  durationMs: number;
  completed: boolean;
}

function extractText(msg: SdkMessage): string {
  const content = (msg.message as { content?: Array<{ type: string; text?: string }> } | undefined)
    ?.content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((b) => b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text as string)
    .join('\n');
}

/** Drains one SDK turn: captures session_id from init, joins assistant text, reads result usage. */
export async function relay(stream: AsyncIterable<SdkMessage>): Promise<RelayResult> {
  let sessionId: string | null = null;
  const parts: string[] = [];
  let costUsd = 0;
  let durationMs = 0;
  let completed = false;
  for await (const msg of stream) {
    if (msg.type === 'system' && msg.subtype === 'init') {
      sessionId = (msg.session_id as string) ?? null;
    } else if (msg.type === 'assistant') {
      const t = extractText(msg);
      if (t) parts.push(t);
    } else if (msg.type === 'result') {
      const usage = msg.usage as { total_cost_usd?: number } | undefined;
      costUsd = usage?.total_cost_usd ?? 0;
      durationMs = (msg.duration_ms as number) ?? 0;
      completed = true;
    }
  }
  return { sessionId, assistantText: parts.join('\n\n'), costUsd, durationMs, completed };
}
