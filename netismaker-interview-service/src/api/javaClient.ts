import type { InterviewClaimResponse, WorkerPlanRequest, WorkerQuestionRequest } from '../types.js';

type FetchLike = typeof fetch;
interface ClientConfig {
  apiBaseUrl: string;
  workerApiKey: string;
  workerId: string;
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
    const res = await this.fetchFn(this.url(`/worker/interviews/${id}/question`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`question failed: ${res.status}`);
  }

  async postPlan(id: number, body: WorkerPlanRequest): Promise<void> {
    const res = await this.fetchFn(this.url(`/worker/interviews/${id}/plan`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`plan failed: ${res.status}`);
  }

  async heartbeat(id: number): Promise<void> {
    await this.fetchFn(this.url(`/worker/interviews/${id}/heartbeat`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ workerId: this.cfg.workerId }),
    });
  }

  async fail(id: number, reason: string): Promise<void> {
    await this.fetchFn(this.url(`/worker/interviews/${id}/fail`), {
      method: 'POST',
      headers: this.headers(),
      body: JSON.stringify({ reason }),
    });
  }
}
