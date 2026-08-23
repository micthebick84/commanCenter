/**
 * 활동 스트림 스파이크 — includePartialMessages=true로 1턴 돌려 stream_event 실물 shape 캡처.
 * 실행(운영자 macOS, claude 구독 로그인 필요):
 *   mkdir -p /tmp/spike-repo && cd /tmp/spike-repo && git init -q && echo 'hello' > a.md && git add -A && git commit -qm init
 *   cd <netismaker-interview-service> && set -a && source .env 2>/dev/null; set +a
 *   node --import tsx scripts/spikeStream.ts /tmp/spike-repo > /tmp/stream-capture.jsonl
 * 확인 항목(스펙 §5.0):
 *   ① {type:'stream_event', event, parent_tool_use_id} 봉투
 *   ② event.type==='content_block_delta' && delta.type: text_delta{text} / thinking_delta{thinking} / signature_delta
 *   ③ assistant 메시지 content의 tool_use {name, input.file_path}
 *   ④ effort 지정 시 thinking_delta 발생 여부 (안 나오면 프로덕션에서도 thinking 섹션이 안 뜰 뿐 — 기능 자체는 유효)
 */
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { buildOptions } from '../src/sdk/sessionOptions.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';

const workDir = process.argv[2] ?? process.cwd();
const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);

async function* prompt(
  text: string,
): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield { type: 'user', message: { role: 'user', content: text } };
}

async function capture(): Promise<void> {
  const options = {
    ...buildOptions({
      superpowersPluginPath: process.env.SUPERPOWERS_PLUGIN_PATH ?? '',
      workDir,
      claudeCliPath,
      claudeSessionId: null,
      effort: 'high', // thinking 델타 유도
    }),
    // Task 7 전이라 buildOptions에 아직 없어도 캡처는 가능해야 한다 — 여기서 직접 켠다.
    includePartialMessages: true,
  };
  const stream = realQuery({
    prompt: prompt('이 레포의 파일 하나를 Read 도구로 읽고, 내용을 한 문장으로 요약해줘.'),
    options,
  });
  for await (const msg of stream) {
    // eslint-disable-next-line no-console
    console.log(JSON.stringify(msg));
  }
}

void capture();
