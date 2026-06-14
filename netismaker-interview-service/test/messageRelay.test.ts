import { describe, expect, it } from 'vitest';
import { relay } from '../src/runner/messageRelay.js';
import { questionStream } from './fixtures/sdkMessages.js';

describe('relay', () => {
  it('captures session_id from system/init and collects assistant text + result', async () => {
    const out = await relay(questionStream());
    expect(out.sessionId).toBe('sess-new-1');
    expect(out.assistantText).toContain('Which columns');
    expect(out.costUsd).toBeCloseTo(0.12);
    expect(out.durationMs).toBe(800);
    expect(out.completed).toBe(true);
  });
});
