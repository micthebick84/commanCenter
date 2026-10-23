import type {
  InterviewClaimResponse,
  WorkerActivityRequest,
  WorkerPlanRequest,
  WorkerQuestionRequest,
  WorkerRateLimitRequest,
} from '../types.js';

type FetchLike = typeof fetch;
interface ClientConfig {
  apiBaseUrl: string;
  workerApiKey: string;
  workerId: string;
}

/** HTTP 상태를 보존하는 에러 — ActivityPoster가 404(구 Java)를 식별해 비활성화한다. */
export class HttpStatusError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export class JavaApiClient {
  constructor(
    private readonly cfg: ClientConfig,
    private readonly fetchFn: FetchLike = fetch,
  ) {}

  private headers(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      'X-Worker-API-Key': this.cfg.workerApiKey,
    };
  }

  private url(path: string): string {
    return `${this.cfg.apiBaseUrl}${path}`;
  }

  /** workerId 쿼리파라미터 — Java 워커 엔드포인트는 모두 @RequestParam String workerId(필수)를 받는다. */
  private wq(): string {
    return `workerId=${encodeURIComponent(this.cfg.workerId)}`;
  }

  /**
   * 기동 알림 — 이전 프로세스가 잡고 있던 RUNNING 세션을 API가 되돌리게 한다(첫 claim 전에 1회).
   * 404(알림을 모르는 구 API)면 null, 그 밖의 비정상 응답은 HttpStatusError.
   */
  async startup(): Promise<{ released: number[]; failed: number[] } | null> {
    const res = await this.fetchFn(this.url(`/worker/interviews/startup?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
    });
    if (res.status === 404) return null;
    if (!res.ok) throw new HttpStatusError(res.status, `startup failed: ${res.status}`);
    return (await res.json()) as { released: number[]; failed: number[] };
  }

  async claim(): Promise<InterviewClaimResponse | null> {
    // workerId is a QUERY param (LOCKED CONTRACT); auth is the X-Worker-API-Key header.
    const res = await this.fetchFn(
      this.url(`/worker/interviews/claim?workerId=${encodeURIComponent(this.cfg.workerId)}`),
      {
        method: 'POST',
        headers: this.headers(),
      },
    );
    if (res.status === 204) return null;
    if (!res.ok) throw new Error(`claim failed: ${res.status}`);
    return (await res.json()) as InterviewClaimResponse;
  }

  async postQuestion(id: number, body: WorkerQuestionRequest): Promise<void> {
    const res = await this.fetchFn(this.url(`/worker/interviews/${id}/question?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`question failed: ${res.status}`);
  }

  async postPlan(id: number, body: WorkerPlanRequest): Promise<void> {
    const res = await this.fetchFn(this.url(`/worker/interviews/${id}/plan?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`plan failed: ${res.status}`);
  }

  /** 활동 배치 전송 — 실패 처리(폐기/비활성화)는 호출자(ActivityPoster) 책임. */
  async postActivity(id: number, body: WorkerActivityRequest): Promise<void> {
    const res = await this.fetchFn(this.url(`/worker/interviews/${id}/activity?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new HttpStatusError(res.status, `activity failed: ${res.status}`);
  }

  /** 구독 한도 스냅샷 보고 (스펙 2026-09-05 §4.1). 실패 처리(비활성/경고)는 RateLimitReporter 책임 — 404 식별용 HttpStatusError. */
  async postRateLimit(body: WorkerRateLimitRequest): Promise<void> {
    const res = await this.fetchFn(this.url(`/worker/usage/rate-limits?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new HttpStatusError(res.status, `rate-limit failed: ${res.status}`);
  }

  async heartbeat(id: number): Promise<void> {
    // workerId는 쿼리파라미터(Java @RequestParam). 바디 없음.
    await this.fetchFn(this.url(`/worker/interviews/${id}/heartbeat?${this.wq()}`), {
      method: 'POST',
      headers: this.headers(),
    });
  }

  async fail(id: number, reason: string): Promise<void> {
    // workerId(필수) + reason(선택) 모두 쿼리파라미터(Java @RequestParam). 바디 없음.
    const reasonQs = reason ? `&reason=${encodeURIComponent(reason)}` : '';
    await this.fetchFn(this.url(`/worker/interviews/${id}/fail?${this.wq()}${reasonQs}`), {
      method: 'POST',
      headers: this.headers(),
    });
  }
}
