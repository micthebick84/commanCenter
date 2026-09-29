import { describe, expect, it } from 'vitest';
import { join } from 'node:path';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';

describe('resolveClaudeCli', () => {
  it('returns the explicit override when provided', () => {
    const exists = () => true;
    expect(resolveClaudeCli('/custom/claude', '/home/me', exists)).toBe('/custom/claude');
  });

  it('walks the candidate list in order and returns the first that exists', () => {
    // only ~/.local/bin/claude exists (home 기반 후보는 path.join으로 만들어지므로 win32에선 `\` 구분자)
    const localBin = join('/home/me', '.local/bin/claude');
    const exists = (p: string) => p === localBin;
    expect(resolveClaudeCli(undefined, '/home/me', exists)).toBe(localBin);
  });

  it('falls back to bare "claude" (PATH lookup) when no candidate file exists', () => {
    expect(resolveClaudeCli(undefined, '/home/me', () => false)).toBe('claude');
  });
});
