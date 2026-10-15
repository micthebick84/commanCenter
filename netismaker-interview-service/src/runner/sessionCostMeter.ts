/**
 * SDK result.total_cost_usd → 질의 하나의 자체 비용. NOT a dollar cap — subscription auth라 표시용 SHADOW 비용이다.
 *
 * resume한 Claude 세션에서는 CLI가 그 세션의 비용 상태를 복원하므로 total_cost_usd가 **세션 누적값**이다.
 * 그대로 더하면 턴마다 이전 턴 비용이 다시 더해진다(2026-10-15 작업 13: 실제 약 0.09짜리 2턴이 11.04로 집계).
 * 직전 누적값(base)을 빼서 자체 비용을 구하고, 보고값이 base보다 작으면(복원이 안 됐거나, 이 수정 전에 부풀려
 * 기록된 세션 합계) 보고값 전체를 이번 비용으로 본다. 어느 경우든 그 뒤 CLI가 저장한 누적값은 보고값이므로 base를 바꾼다.
 */
export class SessionCostMeter {
  private base: number;

  /** @param priorSessionTotal resume하는 Claude 세션이 지금까지 쓴 누적 비용(서버 세션 합계). 새 세션이면 0. */
  constructor(priorSessionTotal: number) {
    this.base = Number.isFinite(priorSessionTotal) && priorSessionTotal > 0 ? priorSessionTotal : 0;
  }

  /** 같은 Claude 세션의 질의 결과를 순서대로 넣는다(본 질의 → splice → reformat 재질의). */
  ownCost(reported: number): number {
    if (!Number.isFinite(reported) || reported <= 0) return 0;
    const own = reported >= this.base ? reported - this.base : reported;
    this.base = reported;
    return own;
  }
}
