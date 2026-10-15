import type { SdkQuery } from '../sdk/sdkAdapter.js';

/**
 * 프롬프트 입력을 턴이 정말 끝날 때까지 열어 두는 질의 래퍼.
 *
 * SDK(0.2.117)는 프롬프트 iterable이 끝나면 첫 result를 받는 즉시 CLI stdin을 닫는다(Query.streamInput →
 * waitForFirstResult → endInput). 그 뒤 백그라운드 서브에이전트 완료 알림으로 CLI가 이어서 도구를 부르면 canUseTool
 * 권한 요청을 보낼 통로가 없어 "Tool permission request failed: AbortError: Stream closed"가 된다
 * (2026-10-15 작업 13 세션 25 — 사전승인에서 뺀 mcp__db-N 호출 2건).
 *
 * CLI는 CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS가 켜져 있으면 system/session_state_changed를 내보내고, 'idle'은
 * 보류한 result를 내보내고 백그라운드 에이전트 루프가 끝난 뒤에 온다(SDK 타입 문서: 턴 종료의 기준 신호). 그때까지
 * 프롬프트를 붙잡았다가 놓는다. 상태 이벤트를 한 번도 못 보면(구 CLI·환경변수 없음) 종전대로 첫 result에서 놓아
 * stdin이 영영 안 닫혀 턴이 멈추는 일을 막는다. abort·스트림 종료·예외에서도 반드시 놓는다.
 */
export function withTurnInputGate(query: SdkQuery): SdkQuery {
  return (args) => {
    let release!: () => void;
    const released = new Promise<void>((resolve) => {
      release = resolve;
    });
    const signal = (args.options.abortController as AbortController | undefined)?.signal;
    if (signal?.aborted) release();
    else signal?.addEventListener('abort', () => release(), { once: true });

    const prompt = args.prompt;
    const heldPrompt = (async function* () {
      yield* prompt;
      await released;
    })();

    const inner = query({ ...args, prompt: heldPrompt });
    return (async function* () {
      let sawState = false;
      try {
        for await (const msg of inner) {
          if (msg.type === 'system' && msg.subtype === 'session_state_changed') {
            sawState = true;
            if (msg.state === 'idle') release();
          } else if (msg.type === 'result' && !sawState) {
            release();
          }
          yield msg;
        }
      } finally {
        release();
      }
    })();
  };
}
