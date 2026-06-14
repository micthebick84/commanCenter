export class QuotaGuardExceeded extends Error {
  constructor(public readonly total: number, public readonly guard: number) {
    super(`session shadow cost ${total.toFixed(4)} crossed quota guard ${guard.toFixed(4)}`);
    this.name = 'QuotaGuardExceeded';
  }
}

/**
 * Accumulates the SHADOW result.usage.total_cost_usd onto the prior session total and
 * enforces a per-session quota guard. NOT a dollar cap — subscription auth has no per-token
 * billing (Phase-0 spike 00b); this is a turn/quota safety net.
 */
export class CostGuard {
  private _total: number;
  constructor(private readonly guardUsd: number, priorTotalUsd = 0) {
    this._total = priorTotalUsd;
  }
  get total(): number {
    return this._total;
  }
  /** Adds this turn's shadow cost; throws if the running total crosses the guard. */
  add(turnCostUsd: number): void {
    this._total += turnCostUsd;
    if (this._total > this.guardUsd) throw new QuotaGuardExceeded(this._total, this.guardUsd);
  }
}
