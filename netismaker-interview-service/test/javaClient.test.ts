import { afterEach, describe, expect, it, vi } from 'vitest';
import { JavaApiClient, HttpStatusError } from '../src/api/javaClient.js';
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
});
