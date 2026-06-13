import { describe, expect, it } from 'vitest';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';

describe('resolveClaudeCli', () => {
  it('returns the explicit override when provided', () => {
    const exists = () => true;
    expect(resolveClaudeCli('/custom/claude', '/home/me', exists)).toBe('/custom/claude');
  });

  it('walks the candidate list in order and returns the first that exists', () => {
    // only ~/.local/bin/claude exists
    const exists = (p: string) => p === '/home/me/.local/bin/claude';
    expect(resolveClaudeCli(undefined, '/home/me', exists)).toBe('/home/me/.local/bin/claude');
  });

  it('falls back to bare "claude" (PATH lookup) when no candidate file exists', () => {
    expect(resolveClaudeCli(undefined, '/home/me', () => false)).toBe('claude');
  });
});
