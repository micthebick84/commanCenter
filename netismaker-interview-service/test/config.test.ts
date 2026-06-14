import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';

const base = {
  API_BASE_URL: 'http://localhost:8090',
  WORKER_API_KEY: 'k',
  WORKER_ID: 'iw-1',
  SUPERPOWERS_PLUGIN_PATH: '/sp',
};

describe('loadConfig', () => {
  it('parses required env and applies defaults', () => {
    const c = loadConfig(base);
    expect(c.apiBaseUrl).toBe('http://localhost:8090');
    expect(c.quotaGuard).toBe(5);
    expect(c.claimPollIntervalMs).toBe(3000);
    // No ANTHROPIC_API_KEY: auth is subscription via the claude CLI.
    expect(c.claudeCliPath).toBeUndefined();
  });

  it('passes through CLAUDE_CLI override when set', () => {
    const c = loadConfig({ ...base, CLAUDE_CLI: '/custom/claude' });
    expect(c.claudeCliPath).toBe('/custom/claude');
  });

  it('throws when API_BASE_URL missing', () => {
    const { API_BASE_URL: _omit, ...rest } = base;
    expect(() => loadConfig(rest)).toThrow(/API_BASE_URL/);
  });
});
