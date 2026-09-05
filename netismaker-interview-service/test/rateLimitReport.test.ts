import { describe, expect, it, vi } from 'vitest';
import { RateLimitReporter, toRateLimitRequests } from '../src/runner/rateLimitReport.js';
import { HttpStatusError } from '../src/api/javaClient.js';
import type { RateLimitInfo } from '../src/types.js';

describe('toRateLimitRequests (스펙 2026-09-05 §4.3 + 실측 보정 RATE_LIMIT_FINDINGS.md)', () => {
  it('실측 shape: unifiedWindows 창마다 1건, 최상위 utilization 없음 → five_hour/seven_day 2건 (캡처 원문)', () => {
    const real = {
      status: 'allowed',
      resetsAt: 1788616200,
      rateLimitType: 'five_hour',
      overageStatus: 'rejected',
      overageDisabledReason: 'org_level_disabled',
      isUsingOverage: false,
      unifiedWindows: {
        five_hour: { utilization: 0.05, resetsAt: 1788616200 },
        seven_day: { utilization: 0.12, resetsAt: 1788706800 },
      },
    } as RateLimitInfo;
    expect(toRateLimitRequests(real)).toEqual([
      { limitType: 'five_hour', status: 'allowed', utilization: 0.05, resetsAt: '2026-09-05T13:50:00.000Z', isUsingOverage: false },
      { limitType: 'seven_day', status: 'allowed', utilization: 0.12, resetsAt: '2026-09-06T15:00:00.000Z', isUsingOverage: false },
    ]);
  });

  it('주 창만 최상위 status를 받고 나머지 창은 allowed; 미지 키는 버린다; 순서는 고정', () => {
    const out = toRateLimitRequests({
      status: 'allowed_warning',
      rateLimitType: 'seven_day',
      unifiedWindows: { weird: { utilization: 0.5 }, seven_day: { utilization: 0.8 }, five_hour: { utilization: 0.1 } },
    } as RateLimitInfo);
    expect(out.map((r) => [r.limitType, r.status, r.utilization])).toEqual([
      ['five_hour', 'allowed', 0.1],
      ['seven_day', 'allowed_warning', 0.8],
    ]);
  });

  it('구형(flat) 이벤트: 분수 utilization + epoch 초 resetsAt → 1건, ISO', () => {
    expect(toRateLimitRequests({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.42, resetsAt: 1788580800 })).toEqual([
      { limitType: 'five_hour', status: 'allowed', utilization: 0.42, resetsAt: '2026-09-05T04:00:00.000Z', isUsingOverage: false },
    ]);
  });

  it('unifiedWindows에 주 창이 빠졌으면 최상위 utilization/resetsAt으로 주 창을 보충한다', () => {
    const out = toRateLimitRequests({
      status: 'allowed',
      rateLimitType: 'five_hour',
      utilization: 0.3,
      resetsAt: 1788580800,
      unifiedWindows: { seven_day: { utilization: 0.6, resetsAt: 1788854400 } },
    });
    expect(out.map((r) => [r.limitType, r.utilization, r.resetsAt])).toEqual([
      ['five_hour', 0.3, '2026-09-05T04:00:00.000Z'],
      ['seven_day', 0.6, '2026-09-08T08:00:00.000Z'],
    ]);
  });

  it('퍼센트(1 초과)는 /100, 범위 밖은 0..1 클램프, ms resetsAt은 그대로 ISO, 소수 4자리', () => {
    const [pct] = toRateLimitRequests({ status: 'allowed', rateLimitType: 'seven_day', utilization: 63, resetsAt: 1788854400000 });
    expect(pct!.utilization).toBe(0.63);
    expect(pct!.resetsAt).toBe('2026-09-08T08:00:00.000Z');
    expect(toRateLimitRequests({ status: 'rejected', rateLimitType: 'seven_day', utilization: 250 })[0]!.utilization).toBe(1);
    expect(toRateLimitRequests({ status: 'allowed', rateLimitType: 'seven_day', utilization: -3 })[0]!.utilization).toBe(0);
    expect(toRateLimitRequests({ rateLimitType: 'five_hour', unifiedWindows: { five_hour: { utilization: 0.123456 } } })[0]!.utilization).toBe(0.1235);
  });

  it('rateLimitType 없음/미지·비객체·창 없음 → []; utilization 없음 → 0; status 없음 → allowed; overage 플래그는 전 행 공통', () => {
    expect(toRateLimitRequests({ status: 'allowed' })).toEqual([]);
    expect(toRateLimitRequests({ status: 'allowed', rateLimitType: 'weird' as never })).toEqual([]);
    expect(toRateLimitRequests(null)).toEqual([]);
    expect(toRateLimitRequests({ rateLimitType: 'weird' as never, unifiedWindows: { weird: { utilization: 0.5 } } })).toEqual([]);
    expect(toRateLimitRequests({ rateLimitType: 'overage', isUsingOverage: true })).toEqual([
      { limitType: 'overage', status: 'allowed', utilization: 0, resetsAt: null, isUsingOverage: true },
    ]);
    expect(
      toRateLimitRequests({ rateLimitType: 'five_hour', isUsingOverage: true, unifiedWindows: { five_hour: {}, seven_day: {} } }).map((r) => r.isUsingOverage),
    ).toEqual([true, true]);
  });
});

describe('RateLimitReporter', () => {
  it('정규화된 바디를 순서대로 POST하고 flush()로 완료를 기다린다 (type 없는 이벤트는 무시)', async () => {
    const postRateLimit = vi.fn().mockResolvedValue(undefined);
    const r = new RateLimitReporter({ postRateLimit });
    r.report({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.1 });
    r.report({ status: 'allowed' });
    r.report({ status: 'allowed', rateLimitType: 'seven_day', utilization: 0.2 });
    await r.flush();
    expect(postRateLimit).toHaveBeenCalledTimes(2);
    expect(postRateLimit.mock.calls[0]![0].limitType).toBe('five_hour');
    expect(postRateLimit.mock.calls[1]![0].limitType).toBe('seven_day');
  });

  it('실측 shape 이벤트 1건은 창별로 펼쳐 five_hour → seven_day 순으로 2건 POST한다', async () => {
    const postRateLimit = vi.fn().mockResolvedValue(undefined);
    const r = new RateLimitReporter({ postRateLimit });
    r.report({
      status: 'allowed',
      rateLimitType: 'five_hour',
      resetsAt: 1788580800,
      unifiedWindows: { five_hour: { utilization: 0.42, resetsAt: 1788580800 }, seven_day: { utilization: 0.63, resetsAt: 1788854400 } },
    });
    await r.flush();
    expect(postRateLimit.mock.calls.map((c) => [c[0].limitType, c[0].utilization])).toEqual([
      ['five_hour', 0.42],
      ['seven_day', 0.63],
    ]);
  });

  it('404(구버전 API)면 이후 보고를 비활성화하고 경고는 1회만 남긴다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const postRateLimit = vi.fn().mockRejectedValue(new HttpStatusError(404, 'rate-limit failed: 404'));
    const r = new RateLimitReporter({ postRateLimit });
    r.report({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.1 });
    await r.flush();
    r.report({ status: 'allowed', rateLimitType: 'seven_day', utilization: 0.2 });
    await r.flush();
    expect(postRateLimit).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('일시 오류(500)는 경고 1회 후에도 다음 보고를 계속 시도한다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const postRateLimit = vi
      .fn()
      .mockRejectedValueOnce(new HttpStatusError(500, 'rate-limit failed: 500'))
      .mockResolvedValue(undefined);
    const r = new RateLimitReporter({ postRateLimit });
    r.report({ status: 'allowed', rateLimitType: 'five_hour', utilization: 0.1 });
    r.report({ status: 'allowed', rateLimitType: 'seven_day', utilization: 0.2 });
    await r.flush();
    expect(postRateLimit).toHaveBeenCalledTimes(2);
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });
});
