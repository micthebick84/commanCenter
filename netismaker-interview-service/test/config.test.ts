import { describe, expect, it, vi } from 'vitest';
import { MAX_TIMER_MS, loadConfig } from '../src/config.js';

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

  it('defaults turnTimeoutMs=30min and honors INTERVIEW_TURN_TIMEOUT_MS override', () => {
    const base = {
      API_BASE_URL: 'http://x', WORKER_API_KEY: 'k', WORKER_ID: 'w',
      SUPERPOWERS_PLUGIN_PATH: '/sp',
    };
    expect(loadConfig({ ...base }).turnTimeoutMs).toBe(1_800_000);
    expect(loadConfig({ ...base, INTERVIEW_TURN_TIMEOUT_MS: '600000' }).turnTimeoutMs).toBe(600_000);
  });

  it('invalid INTERVIEW_TURN_TIMEOUT_MS falls back to the default (NaN would insta-abort every turn)', () => {
    // '30m' 같은 오기는 Number()가 NaN을 내고, NaN은 setTimeout에서 1ms로 클램프돼
    // 모든 claim이 즉시 abort+FAILED되는 워커 전면 장애가 된다 — posNum이 기본값으로 폴백해야 한다.
    const base = {
      API_BASE_URL: 'http://x', WORKER_API_KEY: 'k', WORKER_ID: 'w',
      SUPERPOWERS_PLUGIN_PATH: '/sp',
    };
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(loadConfig({ ...base, INTERVIEW_TURN_TIMEOUT_MS: '30m' }).turnTimeoutMs).toBe(1_800_000);
    expect(loadConfig({ ...base, INTERVIEW_TURN_TIMEOUT_MS: '0' }).turnTimeoutMs).toBe(1_800_000);
    expect(loadConfig({ ...base, INTERVIEW_TURN_TIMEOUT_MS: '-5' }).turnTimeoutMs).toBe(1_800_000);
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('defaults refTimeoutMs=5min, honors INTERVIEW_REF_TIMEOUT_MS, falls back on 30m/0/-5 with a warning', () => {
    // 참조 repo 하나당 받기 상한 (스펙 2026-10-24 §5.1) — 턴 상한과 같은 posNum 규칙(NaN이 타이머에 들어가면 1ms로 모든 참조가 끊긴다)
    expect(loadConfig(base).refTimeoutMs).toBe(300_000);
    expect(loadConfig({ ...base, INTERVIEW_REF_TIMEOUT_MS: '120000' }).refTimeoutMs).toBe(120_000);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    for (const bad of ['30m', '0', '-5']) {
      expect(loadConfig({ ...base, INTERVIEW_REF_TIMEOUT_MS: bad }).refTimeoutMs).toBe(300_000);
    }
    expect(warnSpy).toHaveBeenCalledTimes(3);
    expect(String(warnSpy.mock.calls[0]![0])).toContain('INTERVIEW_REF_TIMEOUT_MS');
    warnSpy.mockRestore();
  });

  it('caps INTERVIEW_TURN_TIMEOUT_MS and INTERVIEW_REF_TIMEOUT_MS at the timer limit 2147483647 with a warning', () => {
    // setTimeout은 2^31-1ms보다 긴 지연을 1ms로 바꾼다 — 그대로 두면 모든 턴·참조가 바로 끊긴다
    expect(MAX_TIMER_MS).toBe(2_147_483_647);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(loadConfig({ ...base, INTERVIEW_TURN_TIMEOUT_MS: '3000000000' }).turnTimeoutMs).toBe(2_147_483_647);
    expect(loadConfig({ ...base, INTERVIEW_REF_TIMEOUT_MS: '1e12' }).refTimeoutMs).toBe(2_147_483_647);
    expect(warnSpy).toHaveBeenCalledTimes(2);
    expect(String(warnSpy.mock.calls[1]![0])).toContain('INTERVIEW_REF_TIMEOUT_MS');
    // 한도 자체와 그 아래 값은 그대로(경고 없음)
    expect(loadConfig({ ...base, INTERVIEW_REF_TIMEOUT_MS: '2147483647' }).refTimeoutMs).toBe(2_147_483_647);
    expect(loadConfig({ ...base, INTERVIEW_TURN_TIMEOUT_MS: '2147483646' }).turnTimeoutMs).toBe(2_147_483_646);
    expect(warnSpy).toHaveBeenCalledTimes(2);
    warnSpy.mockRestore();
  });

  it('defaults concurrency=1 (serial) and honors INTERVIEW_CONCURRENCY', () => {
    expect(loadConfig(base).concurrency).toBe(1);
    expect(loadConfig({ ...base, INTERVIEW_CONCURRENCY: '3' }).concurrency).toBe(3);
  });

  it('invalid INTERVIEW_CONCURRENCY falls back to 1 with a warning', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    for (const bad of ['0', '-2', '1.5', 'two']) {
      expect(loadConfig({ ...base, INTERVIEW_CONCURRENCY: bad }).concurrency).toBe(1);
    }
    expect(warnSpy).toHaveBeenCalledTimes(4);
    warnSpy.mockRestore();
  });
});
