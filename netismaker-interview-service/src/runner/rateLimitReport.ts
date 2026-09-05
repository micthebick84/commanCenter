import { HttpStatusError, type JavaApiClient } from '../api/javaClient.js';
import type { RateLimitInfo, WorkerRateLimitRequest } from '../types.js';

/** 보고 대상 창. 순서 = 프론트 LIMIT_ORDER(+overage) — 펼침 결과의 출력 순서이기도 하다. */
const LIMIT_TYPES = ['five_hour', 'seven_day', 'seven_day_opus', 'seven_day_sonnet', 'overage'] as const;
type LimitType = (typeof LIMIT_TYPES)[number];

const isLimitType = (v: unknown): v is LimitType => typeof v === 'string' && (LIMIT_TYPES as readonly string[]).includes(v);

/** 0..1 분수. 비숫자 → 0. 1 초과면 퍼센트로 간주해 /100. 0..1 클램프, 소수 4자리. */
function normalizeUtilization(v: unknown): number {
  let util = typeof v === 'number' && Number.isFinite(v) ? v : 0;
  if (util > 1) util = util / 100;
  util = Math.min(1, Math.max(0, util));
  return Number(util.toFixed(4));
}

/** 양수만. 1e12 미만이면 epoch 초 → ms. ISO-8601. 없으면 null. */
function normalizeResetsAt(v: unknown): string | null {
  if (typeof v !== 'number' || !Number.isFinite(v) || v <= 0) return null;
  return new Date(v < 1e12 ? v * 1000 : v).toISOString();
}

interface WindowSnapshot {
  utilization?: unknown;
  resetsAt?: unknown;
}

/**
 * SDKRateLimitInfo → Java WorkerRateLimitRequest[] 정규화 (스펙 2026-09-05 §4.3 + 실측 보정 RATE_LIMIT_FINDINGS.md).
 * 실측(CLI 2.1.261): 최상위 utilization은 없고, sdk.d.ts 미선언 unifiedWindows에 창별 {utilization, resetsAt}이
 * 한 이벤트에 동봉된다 → 창마다 1건으로 펼친다.
 * - unifiedWindows의 알려진 타입 키만 채택(미지 키 무시). 주 창(rateLimitType)이 빠져 있으면 최상위 utilization/resetsAt으로
 *   보충한다 — 구형(flat) 이벤트는 이 경로로 1건이 된다.
 * - status: 주 창만 최상위 값(없으면 allowed), 나머지 창은 allowed(창별 status는 이벤트에 없다).
 * - isUsingOverage: 계정 단위라 전 행 공통(=== true만 true).
 * - 출력 순서는 LIMIT_TYPES 고정. rateLimitType 없음/미지 + 창 없음 → [] (보고 안 함).
 */
export function toRateLimitRequests(info: RateLimitInfo | null | undefined): WorkerRateLimitRequest[] {
  if (!info || typeof info !== 'object') return [];
  const primary = info.rateLimitType;
  const windows = new Map<LimitType, WindowSnapshot>();
  const uw = info.unifiedWindows;
  if (uw && typeof uw === 'object') {
    for (const [type, w] of Object.entries(uw)) {
      if (isLimitType(type) && w && typeof w === 'object') windows.set(type, w as WindowSnapshot);
    }
  }
  if (isLimitType(primary) && !windows.has(primary)) {
    windows.set(primary, { utilization: info.utilization, resetsAt: info.resetsAt });
  }
  return LIMIT_TYPES.filter((t) => windows.has(t)).map((type) => {
    const w = windows.get(type)!;
    return {
      limitType: type,
      status: type === primary ? info.status || 'allowed' : 'allowed',
      utilization: normalizeUtilization(w.utilization),
      resetsAt: normalizeResetsAt(w.resetsAt ?? (type === primary ? info.resetsAt : undefined)),
      isUsingOverage: info.isUsingOverage === true,
    };
  });
}

/**
 * rate_limit_event → POST /worker/usage/rate-limits (창마다 1건). 인터뷰 서비스 수명 동안 1개(러너 필드).
 * - 직렬 체이닝(순서 보장). 실패는 경고 1회 후 계속. 404(구버전 Java)면 서비스 수명 동안 비활성(ActivityPoster 선례).
 * - 어떤 경로로도 throw하지 않는다 — 사용량 보고가 인터뷰/답변을 죽이면 안 된다.
 */
export class RateLimitReporter {
  private disabled = false;
  private warned = false;
  private chain: Promise<void> = Promise.resolve();

  constructor(private readonly client: Pick<JavaApiClient, 'postRateLimit'>) {}

  report(info: RateLimitInfo): void {
    if (this.disabled) return;
    for (const body of toRateLimitRequests(info)) this.enqueue(body);
  }

  private enqueue(body: WorkerRateLimitRequest): void {
    this.chain = this.chain
      .then(() => (this.disabled ? undefined : this.client.postRateLimit(body)))
      .catch((err: unknown) => {
        if (err instanceof HttpStatusError && err.status === 404) this.disabled = true;
        if (!this.warned) {
          this.warned = true;
          // eslint-disable-next-line no-console
          console.warn(
            `[rate-limit] 사용량 보고 실패${this.disabled ? ' (404 — 구버전 API, 이후 비활성)' : ''}: ${(err as Error).message}`,
          );
        }
      });
  }

  /** 대기 중인 보고를 모두 끝낸다 — 턴 종료 시 await (테스트 결정성 + 종료 전 flush). */
  flush(): Promise<void> {
    return this.chain;
  }
}
