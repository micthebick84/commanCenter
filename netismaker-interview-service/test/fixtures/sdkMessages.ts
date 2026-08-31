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
    {
      type: 'result',
      subtype: 'success',
      usage: {
        total_cost_usd: 0.12,
        input_tokens: 1000,
        output_tokens: 250,
        cache_creation_input_tokens: 30,
        cache_read_input_tokens: 8000,
      },
      duration_ms: 800,
    },
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
    {
      type: 'result',
      subtype: 'success',
      usage: {
        total_cost_usd: 0.31,
        input_tokens: 2000,
        output_tokens: 500,
        cache_creation_input_tokens: 60,
        cache_read_input_tokens: 5400,
      },
      duration_ms: 5400,
    },
  );

/**
 * includePartialMessages 턴: thinking·text 델타 + tool_use + 서브에이전트 노이즈 + 최종 질문.
 * shape 근거: @anthropic-ai/claude-agent-sdk@0.2.117 sdk.d.ts의 SDKPartialAssistantMessage
 * ({type:'stream_event', event, parent_tool_use_id}) + BetaTextDelta{text}/BetaThinkingDelta{thinking}.
 * 실물 재검증: scripts/spikeStream.ts → test/fixtures/STREAM_SHAPE_FINDINGS.md.
 */
export const streamingQuestionStream = (): AsyncIterable<SdkMessage> =>
  gen(
    { type: 'system', subtype: 'init', session_id: 'sess-stream-1' },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'repo부터 봐야' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'sig==' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: '레포를 먼저 ' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: '읽겠습니다' } },
    },
    {
      type: 'assistant',
      message: {
        content: [
          { type: 'text', text: '레포를 먼저 읽겠습니다' },
          { type: 'tool_use', id: 'tu-1', name: 'Read', input: { file_path: '/work/repo/src/app.ts' } },
        ],
      },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: 'tu-x', // 서브에이전트 스트림 — 활동으로 방출하면 안 됨
      event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'SUBAGENT NOISE' } },
    },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Which columns?' } },
    },
    { type: 'assistant', message: { content: [{ type: 'text', text: 'Which columns?' }] } },
    {
      type: 'result',
      subtype: 'success',
      usage: {
        total_cost_usd: 0.2,
        input_tokens: 1200,
        output_tokens: 300,
        cache_creation_input_tokens: 45,
        cache_read_input_tokens: 6000,
      },
      duration_ms: 900,
    },
  );
