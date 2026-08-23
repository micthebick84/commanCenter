import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityPoster } from '../src/runner/activityPoster.js';
import { HttpStatusError } from '../src/api/javaClient.js';

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function makeClient() {
  return { postActivity: vi.fn().mockResolvedValue(undefined) };
}

describe('ActivityPoster', () => {
  it('interval마다 배치 flush + 단조증가 seq 부여', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'tool', label: 'Read', detail: 'a.ts' });
    p.push({ type: 'thinking', content: '음' });
    expect(client.postActivity).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(300);
    expect(client.postActivity).toHaveBeenCalledTimes(1);
    expect(client.postActivity).toHaveBeenCalledWith(42, {
      events: [
        { seq: 1, type: 'tool', label: 'Read', detail: 'a.ts' },
        { seq: 2, type: 'thinking', content: '음' },
      ],
    });
    await p.stop();
  });

  it('동일 type 연속 text/thinking 델타는 content 병합', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: 'a' });
    p.push({ type: 'text', content: 'b' });
    p.push({ type: 'thinking', content: 'x' });
    p.push({ type: 'text', content: 'c' });
    await vi.advanceTimersByTimeAsync(300);
    expect(client.postActivity).toHaveBeenCalledWith(42, {
      events: [
        { seq: 1, type: 'text', content: 'ab' },
        { seq: 2, type: 'thinking', content: 'x' },
        { seq: 3, type: 'text', content: 'c' },
      ],
    });
    await p.stop();
  });

  it('큐가 maxQueue에 닿으면 interval 전에 즉시 flush', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300, maxQueue: 2 });
    p.start();
    p.push({ type: 'tool', label: 'Read', detail: 'a.ts' });
    p.push({ type: 'tool', label: 'Grep', detail: 'x' }); // 병합 안 됨(tool) → 2건 도달
    await vi.advanceTimersByTimeAsync(0); // 마이크로태스크만 소진
    expect(client.postActivity).toHaveBeenCalledTimes(1);
    await p.stop();
  });

  it('직렬 전송: 두 번째 배치는 첫 POST 완료를 기다린다', async () => {
    const client = makeClient();
    let resolveFirst!: () => void;
    client.postActivity
      .mockImplementationOnce(() => new Promise<void>((r) => (resolveFirst = r)))
      .mockResolvedValue(undefined);
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '1' });
    await vi.advanceTimersByTimeAsync(300); // 1차 flush — in flight
    p.push({ type: 'text', content: '2' });
    await vi.advanceTimersByTimeAsync(300); // 2차 flush 시도 — 큐는 비웠지만 POST는 대기
    expect(client.postActivity).toHaveBeenCalledTimes(1);
    resolveFirst();
    await vi.advanceTimersByTimeAsync(0);
    expect(client.postActivity).toHaveBeenCalledTimes(2);
    await p.stop();
  });

  it('전송 실패 배치는 폐기(경고 1회) — throw하지 않는다', async () => {
    const client = makeClient();
    client.postActivity.mockRejectedValue(new Error('boom'));
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '1' });
    await vi.advanceTimersByTimeAsync(300);
    p.push({ type: 'text', content: '2' });
    await vi.advanceTimersByTimeAsync(300);
    expect(client.postActivity).toHaveBeenCalledTimes(2); // 계속 시도는 함(일시 장애 대비)
    expect(console.warn).toHaveBeenCalledTimes(1); // 경고는 1회만
    await p.stop();
  });

  it('404(구 Java)면 남은 세션 동안 비활성화', async () => {
    const client = makeClient();
    client.postActivity.mockRejectedValue(new HttpStatusError(404, 'activity failed: 404'));
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '1' });
    await vi.advanceTimersByTimeAsync(300);
    p.push({ type: 'text', content: '2' });
    await vi.advanceTimersByTimeAsync(600);
    expect(client.postActivity).toHaveBeenCalledTimes(1); // 두 번째 배치는 전송 안 함
    await p.stop();
  });

  it('stop()은 잔여 큐 flush 후 in-flight 완료까지 대기', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '남은 것' });
    await p.stop(); // interval 미도래 — stop이 flush
    expect(client.postActivity).toHaveBeenCalledTimes(1);
  });

  it('병합은 4000자 상한 — 초과분은 새 항목으로 분리된다 (Java @Size(4096) 400 방지)', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'thinking', content: 'x'.repeat(3999) });
    p.push({ type: 'thinking', content: 'yy' }); // 3999+2 > 4000 → 병합 금지, 새 항목
    await vi.advanceTimersByTimeAsync(300);
    const batch = client.postActivity.mock.calls[0]![1] as { events: Array<{ content?: string }> };
    expect(batch.events).toHaveLength(2);
    expect(batch.events[0].content).toHaveLength(3999);
    expect(batch.events[1].content).toBe('yy');
    await p.stop();
  });

  it('개별 content는 4096자, label은 100자로 절단 (Java @Size와 동기)', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: 'z'.repeat(5000) });
    p.push({ type: 'tool', label: 'L'.repeat(150), detail: 'a' });
    await vi.advanceTimersByTimeAsync(300);
    const batch = client.postActivity.mock.calls[0]![1] as {
      events: Array<{ content?: string; label?: string }>;
    };
    expect(batch.events[0].content).toHaveLength(4096);
    expect(batch.events[1].label).toHaveLength(100);
    await p.stop();
  });

  it('detail은 200자로 절단한다 (Java @Size(200) 동기 — 합성 이벤트의 긴 repo URL 방어)', async () => {
    const client = makeClient();
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'tool', label: '환경 준비', detail: 'D'.repeat(300) });
    await vi.advanceTimersByTimeAsync(300);
    const batch = client.postActivity.mock.calls[0]![1] as { events: Array<{ detail?: string }> };
    expect(batch.events[0].detail).toHaveLength(200);
    await p.stop();
  });

  it('404로 비활성화되면 이미 체인에 대기 중이던 배치도 전송하지 않는다', async () => {
    const client = makeClient();
    let rejectFirst!: (e: unknown) => void;
    client.postActivity
      .mockImplementationOnce(() => new Promise<void>((_, rej) => (rejectFirst = rej)))
      .mockResolvedValue(undefined);
    const p = new ActivityPoster(client, 42, { flushIntervalMs: 300 });
    p.start();
    p.push({ type: 'text', content: '1' });
    await vi.advanceTimersByTimeAsync(300); // 배치1 in-flight (미해결)
    p.push({ type: 'text', content: '2' });
    await vi.advanceTimersByTimeAsync(300); // 배치2가 체인에 커밋됨 (아직 disabled 아님)
    rejectFirst(new HttpStatusError(404, 'activity failed: 404')); // 배치1이 404로 종결 → disabled
    await vi.advanceTimersByTimeAsync(0);
    expect(client.postActivity).toHaveBeenCalledTimes(1); // 배치2는 체인 내부 가드로 스킵
    await p.stop();
  });
});
