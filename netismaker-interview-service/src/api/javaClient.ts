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

/** 턴 결과 보고(question·plan·fail)의 재시도 설정. */
export interface ReportRetry {
  /** 재시도 전 대기(ms) — 길이가 최대 재시도 횟수다. */
  delaysMs: number[];
  sleep: (ms: number) => Promise<void>;
}

/** API 재기동(Windows bootRun 약 10–20초)을 넉넉히 넘기는 합계 약 60초. */
const DEFAULT_REPORT_RETRY: ReportRetry = {
  delaysMs: [1_000, 2_000, 4_000, 8_000, 15_000, 15_000, 15_000],
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

export class JavaApiClient {
  constructor(
    private readonly cfg: ClientConfig,
    private readonly fetchFn: FetchLike = fetch,
    private readonly reportRetry: ReportRetry = DEFAULT_REPORT_RETRY,
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

  /**
   * 턴 결과 보고(question·plan·fail). 턴이 끝난 순간 API가 재기동 중이면 결과를 잃지 않게 네트워크 오류·5xx는
   * reportRetry 간격으로 다시 보낸다. 서버는 RUNNING이고 이 워커가 쥔 세션에만 기록하므로(아니면 409) 같은
   * 보고가 두 번 반영되지는 않는다 — 그래서 재시도에서 받은 409는 "앞선 시도가 이미 반영됐는데 응답만 잃었다"로
   * 보고 성공 처리한다(그사이 사용자가 세션을 끝냈어도 이 결과는 더 쓸 데가 없다). 첫 시도의 409와 그 밖의 4xx는
   * 재시도 없이 HttpStatusError, 재시도를 다 써도 안 되면 마지막 원인으로 던진다.
   */
  private async report(path: string, init: RequestInit, label: string): Promise<void> {
    const delays = this.reportRetry.delaysMs;
    for (let attempt = 0; ; attempt++) {
      let failure: Error;
      try {
        const res = await this.fetchFn(this.url(path), init);
        if (res.ok) return;
        if (res.status === 409 && attempt > 0) {
          // eslint-disable-next-line no-console
          console.warn(`[java-client] ${label}: 재시도에서 409 — 앞선 시도가 이미 반영된 것으로 본다`);
          return;
        }
        failure = new HttpStatusError(res.status, `${label} failed: ${res.status}`);
        if (res.status < 500) throw failure;
      } catch (err) {
        if (err instanceof HttpStatusError && err.status < 500) throw err;
        failure = err as Error;
      }
      if (attempt >= delays.length) throw failure;
      // eslint-disable-next-line no-console
      console.warn(
        `[java-client] ${label}: ${failure.message} — ${delays[attempt]}ms 뒤 재시도 (${attempt + 1}/${delays.length})`,
      );
      await this.reportRetry.sleep(delays[attempt]!);
    }
  }

  async postQuestion(id: number, body: WorkerQuestionRequest): Promise<void> {
    await this.report(
      `/worker/interviews/${id}/question?${this.wq()}`,
      { method: 'POST', headers: this.headers(), body: JSON.stringify(body) },
      'question',
    );
  }

  async postPlan(id: number, body: WorkerPlanRequest): Promise<void> {
    await this.report(
      `/worker/interviews/${id}/plan?${this.wq()}`,
      { method: 'POST', headers: this.headers(), body: JSON.stringify(body) },
      'plan',
    );
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

  /** 끝내 실패하면 던진다 — 호출처(러너 safeFail·ClaimLoop)가 경고로만 남기고 계속 간다. */
  async fail(id: number, reason: string): Promise<void> {
    // workerId(필수) + reason(선택) 모두 쿼리파라미터(Java @RequestParam). 바디 없음.
    const reasonQs = reason ? `&reason=${encodeURIComponent(reason)}` : '';
    await this.report(
      `/worker/interviews/${id}/fail?${this.wq()}${reasonQs}`,
      { method: 'POST', headers: this.headers() },
      'fail',
    );
  }
}
