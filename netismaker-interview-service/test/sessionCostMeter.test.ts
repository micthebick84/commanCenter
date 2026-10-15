import { describe, expect, it } from 'vitest';
import { SessionCostMeter } from '../src/runner/sessionCostMeter.js';

// SDK result.total_cost_usd는 resume한 Claude 세션에서는 그 세션 누적값이다(CLI가 비용 상태를 복원).
// 2026-10-15 작업 13 세션 24: 1턴 10.9537, 2턴(실제 약 0.09)의 보고값 11.0414를 그대로 더해 21.99로 집계됐다.
describe('SessionCostMeter', () => {
  it('fresh session: the first query cost is the reported value', () => {
    const m = new SessionCostMeter(0);
    expect(m.ownCost(0.12)).toBeCloseTo(0.12);
  });

  it('resumed session: subtracts the prior session cumulative (session 24 numbers)', () => {
    const m = new SessionCostMeter(10.953736);
    expect(m.ownCost(11.0414)).toBeCloseTo(0.087664, 6);
  });

  it('later queries in the same turn subtract the previous reported cumulative', () => {
    const m = new SessionCostMeter(1);
    expect(m.ownCost(1.5)).toBeCloseTo(0.5);
    expect(m.ownCost(1.7)).toBeCloseTo(0.2); // splice / reformat retry resume the same session
  });

  it('reported below the base (CLI did not restore cost, or a legacy inflated total) counts the whole value', () => {
    const m = new SessionCostMeter(9.84);
    expect(m.ownCost(0.3)).toBeCloseTo(0.3);
    expect(m.ownCost(0.45)).toBeCloseTo(0.15); // base is now the last reported value
  });

  it('treats missing or invalid values as zero cost', () => {
    const m = new SessionCostMeter(2);
    expect(m.ownCost(Number.NaN)).toBe(0);
    expect(m.ownCost(2.5)).toBeCloseTo(0.5);
  });
});
