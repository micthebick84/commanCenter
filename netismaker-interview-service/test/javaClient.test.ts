import { afterEach, describe, expect, it, vi } from 'vitest';
import { JavaApiClient } from '../src/api/javaClient.js';
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

  it('postQuestion PUTs body to /worker/interviews/{id}/question', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await client.postQuestion(42, {
      content: 'What columns?',
      claudeSessionId: 'sess-1',
      kind: 'question',
      costUsd: 0.1,
    });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://api:8090/worker/interviews/42/question');
    expect(JSON.parse(init.body).content).toBe('What columns?');
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
    });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://api:8090/worker/interviews/42/plan');
    // planJson must be a string on the wire — Java stores it as text, frontend parses on use
    expect(typeof JSON.parse(init.body).planJson).toBe('string');
  });

  it('fail POSTs reason to /worker/interviews/{id}/fail', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    const client = new JavaApiClient(cfg, fetchMock);
    await client.fail(42, 'cost cap exceeded');
    expect(fetchMock.mock.calls[0][0]).toBe('http://api:8090/worker/interviews/42/fail');
  });
});
