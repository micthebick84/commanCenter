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

  it('defaults maxTurns=20, forceFinishTurns=19 and honors env overrides', () => {
    const base = {
      API_BASE_URL: 'http://x', WORKER_API_KEY: 'k', WORKER_ID: 'w',
      SUPERPOWERS_PLUGIN_PATH: '/sp',
    };
    const d = loadConfig({ ...base });
    expect(d.maxTurns).toBe(20);
    expect(d.forceFinishTurns).toBe(19);

    const o = loadConfig({ ...base, INTERVIEW_MAX_TURNS: '30', INTERVIEW_FORCE_FINISH_TURNS: '28' });
    expect(o.maxTurns).toBe(30);
    expect(o.forceFinishTurns).toBe(28);
  });
});
