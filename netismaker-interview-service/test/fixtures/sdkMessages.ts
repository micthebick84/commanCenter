import type { SdkMessage } from '../../src/sdk/sdkAdapter.js';

async function* gen(...msgs: SdkMessage[]): AsyncIterable<SdkMessage> {
  for (const m of msgs) yield m;
}

/** Init -> one assistant question -> result (the agent paused for human input). */
export const questionStream = (): AsyncIterable<SdkMessage> =>
  gen(
    { type: 'system', subtype: 'init', session_id: 'sess-new-1' },
    {
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Which columns should the CSV include?' }] },
    },
    { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.12 }, duration_ms: 800 },
  );

/** Init -> writing-plans completes: assistant emits design+plan, then result. */
export const planCompleteStream = (): AsyncIterable<SdkMessage> =>
  gen(
    { type: 'system', subtype: 'init', session_id: 'sess-resumed-9' },
    {
      type: 'assistant',
      message: {
        content: [
          {
            type: 'text',
            text:
              '# Design\n\nExport visible columns as CSV.\n\n' +
              '# CSV Export Implementation Plan\n\n## File Structure\n\n- a.ts\n\n### Task 1: x\n- [ ] **Step 1: do**',
          },
        ],
      },
    },
    { type: 'result', subtype: 'success', usage: { total_cost_usd: 0.31 }, duration_ms: 5400 },
  );
