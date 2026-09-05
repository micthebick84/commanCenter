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

/**
 * 사용량 인지 턴 (스펙 2026-09-05 §4): rate_limit_event 1건(실측 shape — unifiedWindows에 five_hour/seven_day 동봉, 최상위 utilization 없음) + 최상위 message_start usage +
 * 최상위 assistant usage + 서브에이전트 assistant usage(컨텍스트 계산 제외) + result.modelUsage(주 모델 opus, 부 모델 haiku).
 * shape 근거: sdk.d.ts:2910 SDKRateLimitEvent / :2923 SDKRateLimitInfo / :1050 ModelUsage,
 * 실측: test/fixtures/RATE_LIMIT_FINDINGS.md. resetsAt은 epoch 초(1788580800 = 2026-09-05T04:00:00Z).
 */
export const usageAwareQuestionStream = (): AsyncIterable<SdkMessage> =>
  gen(
    { type: 'system', subtype: 'init', session_id: 'sess-usage-1' },
    {
      type: 'stream_event',
      parent_tool_use_id: null,
      event: {
        type: 'message_start',
        message: {
          usage: { input_tokens: 2, cache_creation_input_tokens: 30000, cache_read_input_tokens: 0, output_tokens: 1 },
        },
      },
    },
    {
      type: 'assistant',
      parent_tool_use_id: null,
      message: {
        content: [{ type: 'text', text: 'AuthController.login()이 처리합니다.' }],
        usage: { input_tokens: 4, cache_creation_input_tokens: 30000, cache_read_input_tokens: 46000, output_tokens: 120 },
      },
    },
    {
      type: 'assistant',
      parent_tool_use_id: 'tu-sub', // 서브에이전트 — 컨텍스트 계산에서 제외돼야 한다
      message: {
        content: [{ type: 'text', text: '(sub)' }],
        usage: { input_tokens: 999999, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
      },
    },
    {
      // 실측 shape(RATE_LIMIT_FINDINGS.md ①): 첫 턴 message_stop 직후 1건, 최상위 utilization 없음, 창별 값은 unifiedWindows.
      type: 'rate_limit_event',
      rate_limit_info: {
        status: 'allowed',
        resetsAt: 1788580800,
        rateLimitType: 'five_hour',
        overageStatus: 'rejected',
        isUsingOverage: false,
        unifiedWindows: {
          five_hour: { utilization: 0.42, resetsAt: 1788580800 },
          seven_day: { utilization: 0.63, resetsAt: 1788854400 },
        },
      },
      session_id: 'sess-usage-1',
    },
    {
      type: 'result',
      subtype: 'success',
      usage: {
        total_cost_usd: 0.05,
        input_tokens: 4,
        output_tokens: 120,
        cache_creation_input_tokens: 30000,
        cache_read_input_tokens: 46000,
      },
      modelUsage: {
        'claude-haiku-4-5': { inputTokens: 500, cacheReadInputTokens: 0, cacheCreationInputTokens: 0, outputTokens: 20, contextWindow: 100000 },
        'claude-opus-5': { inputTokens: 4, cacheReadInputTokens: 46000, cacheCreationInputTokens: 30000, outputTokens: 120, contextWindow: 200000 },
      },
      duration_ms: 1200,
    },
  );
