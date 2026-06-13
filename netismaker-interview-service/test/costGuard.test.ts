import { describe, expect, it } from 'vitest';
import { QuotaGuardExceeded, CostGuard } from '../src/runner/costGuard.js';

describe('CostGuard', () => {
  it('accumulates onto the prior shadow total', () => {
    const g = new CostGuard(5, 0.42); // quota guard 5, prior shadow 0.42
    g.add(0.1);
    expect(g.total).toBeCloseTo(0.52);
  });

  it('throws QuotaGuardExceeded when the running total crosses the guard', () => {
    const g = new CostGuard(0.5, 0.45);
    expect(() => g.add(0.1)).toThrow(QuotaGuardExceeded);
  });

  it('does not throw exactly at the guard', () => {
    const g = new CostGuard(0.5, 0.4);
    expect(() => g.add(0.1)).not.toThrow();
    expect(g.total).toBeCloseTo(0.5);
  });
});
