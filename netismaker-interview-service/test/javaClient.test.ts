import { afterEach, describe, expect, it, vi } from 'vitest';
import { JavaApiClient, HttpStatusError } from '../src/api/javaClient.js';
import type { WorkerPlanRequest, WorkerQuestionRequest } from '../src/types.js';
import { freshClaim } from './fixtures/claims.js';

const cfg = { apiBaseUrl: 'http://api:8090', workerApiKey: 'KEY', workerId: 'iw-1' };

afterEach(() => vi.restoreAllMocks());

describe('JavaApiClient', () => {
  it('claim POSTs to /worker/interviews/claim?workerId=... with worker key header, returns body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(freshClaim), { status: 200 }),
    );
    const client = new JavaApiClient(cfg, fetchMock);
    const res = await client.claim();
    expect(fetchMock).toHaveBeenCalledWith(
      'http://api:8090/worker/interviews/claim?workerId=iw-1',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Worker-API-Key': 'KEY' }),
      }),
    );
    expect(res?.sessionId).toBe(42);
  });

  it('claim returns null on 204 (no work)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new JavaApiClient(cfg, fetchMock);
    expect(await client.claim()).toBeNull();
  });

  it('postQuestion POSTs body to /worker/interviews/{id}/question?workerId=...', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await client.postQuestion(42, {
      content: 'What columns?',
      claudeSessionId: 'sess-1',
      kind: 'question',
      costUsd: 0.1,
      inputTokens: 100,
      outputTokens: 50,
      cacheCreationTokens: 0,
      cacheReadTokens: 0,
      contextTokens: null,
      contextWindow: null,
    });
    const [url, init] = fetchMock.mock.calls[0]!;
    // Java @RequestParam String workerId is REQUIRED on every worker endpoint (query, not body).
    expect(url).toBe('http://api:8090/worker/interviews/42/question?workerId=iw-1');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body).content).toBe('What columns?');
  });

  it('heartbeat POSTs to /worker/interviews/{id}/heartbeat?workerId=... (no body)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await client.heartbeat(42);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://api:8090/worker/interviews/42/heartbeat?workerId=iw-1');
    expect(init.method).toBe('POST');
    expect(init.headers['X-Worker-API-Key']).toBe('KEY');
  });

  it('postPlan sends planJson as a STRING (Java stores as text; frontend parses on use)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    const client = new JavaApiClient(cfg, fetchMock);
    const planJsonStr = JSON.stringify([{ task: 1, title: 'Build exporter' }]);
    await client.postPlan(42, {
      designMarkdown: '# Design',
      planMarkdown: '# Plan',
      planJson: planJsonStr,
      costUsd: 0.3,
      durationMs: 5000,
      inputTokens: 200,
      outputTokens: 100,
      cacheCreationTokens: 0,
      cacheReadTokens: 0,
    });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://api:8090/worker/interviews/42/plan?workerId=iw-1');
    // planJson must be a string on the wire — Java stores it as text, frontend parses on use
    expect(typeof JSON.parse(init.body).planJson).toBe('string');
  });

  it('fail POSTs workerId + reason as QUERY params to /worker/interviews/{id}/fail', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await client.fail(42, 'cost cap exceeded');
    // Java reads workerId (@RequestParam, required) + reason (@RequestParam, optional) from the query.
    expect(fetchMock.mock.calls[0]![0]).toBe(
      'http://api:8090/worker/interviews/42/fail?workerId=iw-1&reason=cost%20cap%20exceeded',
    );
  });

  it('postActivity POSTs the batch to /worker/interviews/{id}/activity?workerId=...', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await client.postActivity(42, {
      events: [
        { seq: 1, type: 'tool', label: 'Read', detail: 'src/app.ts' },
        { seq: 2, type: 'text', content: '이제 ' },
      ],
    });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://api:8090/worker/interviews/42/activity?workerId=iw-1');
    expect(init.method).toBe('POST');
    expect(init.headers['X-Worker-API-Key']).toBe('KEY');
    expect(JSON.parse(init.body).events).toHaveLength(2);
  });

  it('postActivity throws HttpStatusError with the status (404 → poster가 비활성화 판단)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await expect(
      client.postActivity(42, { events: [{ seq: 1, type: 'text', content: 'x' }] }),
    ).rejects.toSatisfy((e: unknown) => e instanceof HttpStatusError && (e as HttpStatusError).status === 404);
  });

  it('postRateLimit POSTs to /worker/usage/rate-limits?workerId=… and throws HttpStatusError on non-2xx', async () => {
    const ok = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const body = {
      limitType: 'five_hour',
      status: 'allowed',
      utilization: 0.42,
      resetsAt: '2026-09-05T04:00:00.000Z',
      isUsingOverage: false,
    };
    await new JavaApiClient(cfg, ok).postRateLimit(body);
    const [url, init] = ok.mock.calls[0]!;
    expect(url).toBe('http://api:8090/worker/usage/rate-limits?workerId=iw-1');
    expect(init.method).toBe('POST');
    expect(init.headers['X-Worker-API-Key']).toBe('KEY');
    expect(JSON.parse(init.body)).toEqual(body);

    const notFound = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    await expect(new JavaApiClient(cfg, notFound).postRateLimit(body)).rejects.toBeInstanceOf(HttpStatusError);
  });
});

  it('startup posts workerId query and returns body', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ released: [1, 2], failed: [3] }), { status: 200 }));
    const client = new JavaApiClient(cfg, fetchMock);
    const res = await client.startup();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://api:8090/worker/interviews/startup?workerId=iw-1');
    expect(init.method).toBe('POST');
    expect(init.headers['X-Worker-API-Key']).toBe('KEY');
    expect(init.body).toBeUndefined();
    expect(res).toEqual({ released: [1, 2], failed: [3] });
  });

  it('startup returns null on 404 (older API)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    const client = new JavaApiClient(cfg, fetchMock);
    expect(await client.startup()).toBeNull();
  });

  it('startup throws HttpStatusError on other non-2xx', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 500 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await expect(client.startup()).rejects.toMatchObject({ status: 500 });
    await expect(client.startup()).rejects.toBeInstanceOf(HttpStatusError);
  });

describe('JavaApiClient 턴 결과 보고 재시도 (question·plan·fail)', () => {
  const fast = () => ({ delaysMs: [10, 20, 30], sleep: vi.fn().mockResolvedValue(undefined) });
  const question = { content: '답', claudeSessionId: 's', kind: 'question' } as WorkerQuestionRequest;
  const plan = { designMarkdown: 'd', planMarkdown: 'p', planJson: '{}', durationMs: 1 } as WorkerPlanRequest;

  it('네트워크 오류는 기다렸다가 다시 보내고, 성공하면 끝난다', async () => {
    const retry = fast();
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('fetch failed'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    await new JavaApiClient(cfg, fetchMock, retry).postQuestion(42, question);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1]![0]).toBe('http://api:8090/worker/interviews/42/question?workerId=iw-1');
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body).content).toBe('답');
    expect(retry.sleep).toHaveBeenCalledWith(10);
  });

  it('5xx도 다시 보낸다 (plan)', async () => {
    const retry = fast();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    await new JavaApiClient(cfg, fetchMock, retry).postPlan(42, plan);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1]![0]).toBe('http://api:8090/worker/interviews/42/plan?workerId=iw-1');
  });

  it('재시도에서 받은 409는 앞선 시도가 반영된 것으로 보고 성공 처리한다', async () => {
    const retry = fast();
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('socket hang up'))
      .mockResolvedValueOnce(new Response(null, { status: 409 }));
    await expect(new JavaApiClient(cfg, fetchMock, retry).postQuestion(42, question)).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('첫 시도의 409와 그 밖의 4xx는 재시도 없이 HttpStatusError', async () => {
    for (const status of [409, 400, 401]) {
      const retry = fast();
      const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status }));
      await expect(new JavaApiClient(cfg, fetchMock, retry).postQuestion(42, question)).rejects.toMatchObject({
        status,
        message: `question failed: ${status}`,
      });
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(retry.sleep).not.toHaveBeenCalled();
    }
  });

  it('재시도를 다 쓰면 마지막 원인으로 던진다 — 5xx', async () => {
    const retry = fast();
    const fetchMock = vi.fn().mockImplementation(async () => new Response(null, { status: 502 }));
    await expect(new JavaApiClient(cfg, fetchMock, retry).postQuestion(42, question)).rejects.toSatisfy(
      (e: unknown) => e instanceof HttpStatusError && e.status === 502,
    );
    expect(fetchMock).toHaveBeenCalledTimes(4); // 첫 시도 + 재시도 3회
    expect(retry.sleep.mock.calls.map((c) => c[0])).toEqual([10, 20, 30]);
  });

  it('재시도를 다 쓰면 마지막 원인으로 던진다 — 네트워크 오류', async () => {
    const retry = fast();
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('connect ECONNREFUSED 127.0.0.1:8090'));
    await expect(new JavaApiClient(cfg, fetchMock, retry).postQuestion(42, question)).rejects.toThrow(
      'ECONNREFUSED',
    );
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('fail도 다시 보내고, 끝내 안 되면 던진다 (호출처가 경고로 처리)', async () => {
    const ok = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('fetch failed'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    await new JavaApiClient(cfg, ok, fast()).fail(42, 'boom');
    expect(ok).toHaveBeenCalledTimes(2);
    expect(ok.mock.calls[1]![0]).toBe('http://api:8090/worker/interviews/42/fail?workerId=iw-1&reason=boom');

    const notFound = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    await expect(new JavaApiClient(cfg, notFound, fast()).fail(42, 'boom')).rejects.toMatchObject({ status: 404 });
  });
});
