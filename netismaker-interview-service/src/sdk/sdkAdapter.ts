import { query } from '@anthropic-ai/claude-agent-sdk';

export type SdkMessage = {
  type: string;
  [key: string]: unknown;
};

export interface SdkQuery {
  (args: { prompt: AsyncIterable<unknown>; options: Record<string, unknown> }): AsyncIterable<SdkMessage>;
}

/** Default real query; injected as a seam so tests pass a fake. */
export const realQuery: SdkQuery = (args) =>
  query(args as never) as unknown as AsyncIterable<SdkMessage>;
