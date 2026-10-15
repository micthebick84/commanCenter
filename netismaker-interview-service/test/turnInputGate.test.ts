import { describe, expect, it } from 'vitest';
import { withTurnInputGate } from '../src/runner/turnInputGate.js';
import type { SdkMessage, SdkQuery } from '../src/sdk/sdkAdapter.js';

// SDK는 프롬프트 입력이 끝나면 첫 result에서 CLI stdin을 닫는다. 그 뒤 백그라운드 서브에이전트 완료 알림으로 CLI가
// 이어서 도구를 부르면 canUseTool 권한 요청이 "Stream closed"로 실패했다(2026-10-15 작업 13 세션 25, mcp__db-4 2건).
// 게이트는 CLI가 session_state_changed idle(턴 완전 종료)을 알릴 때까지 프롬프트 입력을 열어 둔다.

const running: SdkMessage = { type: 'system', subtype: 'session_state_changed', state: 'running' };
const idle: SdkMessage = { type: 'system', subtype: 'session_state_changed', state: 'idle' };
const result: SdkMessage = { type: 'result', subtype: 'success', total_cost_usd: 0.1 };
const userTurn = { type: 'user', message: { role: 'user', content: 'hi' } };

async function* onePrompt() {
  yield userTurn;
}

const tick = () => new Promise((r) => setTimeout(r, 5));

/** 실제 SDK처럼 프롬프트를 소비하는 가짜 질의. script는 프롬프트 입력 종료 여부를 보며 메시지를 낸다. */
function fakeQuery(script: (promptEnded: () => boolean, ended: Promise<void>) => AsyncGenerator<SdkMessage>): SdkQuery {
  return ({ prompt }) =>
    (async function* () {
      const it = prompt[Symbol.asyncIterator]();
      await it.next(); // 첫 사용자 메시지
      let done = false;
      const ended = it.next().then(() => {
        done = true;
      });
      yield* script(() => done, ended);
    })();
}

async function drain(stream: AsyncIterable<SdkMessage>): Promise<SdkMessage[]> {
  const out: SdkMessage[] = [];
  for await (const m of stream) out.push(m);
  return out;
}

describe('withTurnInputGate', () => {
  it('keeps the prompt input open after the first result until the CLI reports idle', async () => {
    const seen: Record<string, boolean> = {};
    const q = withTurnInputGate(
      fakeQuery(async function* (promptEnded, ended) {
        yield running;
        yield result;
        await tick();
        seen.afterResult = promptEnded(); // 백그라운드 알림 처리 중 — 아직 열려 있어야 한다
        yield idle;
        await ended; // CLI는 stdin이 닫혀야 끝난다
        seen.afterIdle = promptEnded();
      }),
    );
    const msgs = await drain(q({ prompt: onePrompt(), options: {} }));
    expect(seen.afterResult).toBe(false);
    expect(seen.afterIdle).toBe(true);
    expect(msgs.map((m) => m.type)).toEqual(['system', 'result', 'system']); // 메시지는 그대로 흘려보낸다
  });

  it('falls back to closing at the first result when the CLI emits no session state events', async () => {
    const q = withTurnInputGate(
      fakeQuery(async function* (_promptEnded, ended) {
        yield result;
        await ended; // 구 동작: 첫 result에서 입력 종료 → 여기서 막히지 않아야 한다
      }),
    );
    await expect(drain(q({ prompt: onePrompt(), options: {} }))).resolves.toHaveLength(1);
  });

  it('releases the prompt when the query is aborted', async () => {
    const abortController = new AbortController();
    const q = withTurnInputGate(
      fakeQuery(async function* (_promptEnded, ended) {
        yield running;
        yield result;
        abortController.abort();
        await ended;
      }),
    );
    await expect(drain(q({ prompt: onePrompt(), options: { abortController } }))).resolves.toHaveLength(2);
  });

  it('releases the prompt when the stream ends or throws without idle', async () => {
    let promptDone = false;
    const prompt = (async function* () {
      yield userTurn;
    })();
    const q = withTurnInputGate(({ prompt: p }) =>
      (async function* () {
        const it = p[Symbol.asyncIterator]();
        await it.next();
        void it.next().then(() => {
          promptDone = true;
        });
        yield running;
        throw new Error('boom');
      })(),
    );
    await expect(drain(q({ prompt, options: {} }))).rejects.toThrow('boom');
    await tick();
    expect(promptDone).toBe(true);
  });
});
