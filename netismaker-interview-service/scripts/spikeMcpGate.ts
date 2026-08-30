/**
 * 스파이크 — allowedTools에 미등재된 mcp__ 도구 호출이 canUseTool 콜백에 도달하는지 실증.
 * (스펙 2026-08-30-question-sessions-design §6-① "단일 관문" 전제 — 주석/단위테스트로만 문서화돼 있어 실물 확인.)
 * 실행(운영자 macOS, claude 구독 로그인 + ~/.claude.json에 MCP 서버 1개 이상 필요):
 *   cd netismaker-interview-service && set -a && source .env 2>/dev/null; set +a
 *   node --import tsx scripts/spikeMcpGate.ts
 * 판정:
 *   stdout에 `[gate] toolName=mcp__...` 가 찍히고 마지막 줄이 `RESULT: 도달` → 전제 성립(exit 0).
 *   `RESULT: 미도달`(exit 1) → SDK가 콜백 없이 MCP를 자동 승인한다는 뜻. 계획 Task 6 이후 중단, 보고.
 * canUseTool은 모든 도구를 deny하므로 부수효과 없음(MCP 도구가 실제로 실행되지 않는다).
 * 실측: 2026-08-30 도달 확인 (tool=mcp__local-db__query)
 */
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';
import { loadBaseMcpServers } from '../src/sdk/mcpBase.js';

const claudeCliPath = resolveClaudeCli(process.env.CLAUDE_CLI);
const mcpServers = loadBaseMcpServers();
const names = Object.keys(mcpServers);
if (names.length === 0) {
  console.error('~/.claude.json에 MCP 서버가 없어 스파이크를 실행할 수 없습니다');
  process.exit(2);
}

const seen: string[] = [];

async function* prompt(): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield {
    type: 'user',
    message: {
      role: 'user',
      content:
        `연결된 MCP 서버(${names.join(', ')}) 중 하나의 도구를 반드시 정확히 1회 호출해 보세요. ` +
        '예: local-db가 있으면 query 도구로 "SELECT 1", obsidian 계열이면 목록 조회 도구. ' +
        '호출이 거부되더라도 다시 시도하지 말고, 결과(거부 포함)를 한 줄로 보고하세요.',
    },
  };
}

async function run(): Promise<void> {
  const stream = realQuery({
    prompt: prompt(),
    options: {
      pathToClaudeCodeExecutable: claudeCliPath,
      plugins: [],
      allowedTools: ['Read', 'Grep', 'Glob'], // mcp__ 와일드카드 의도적 미등재 (QUESTION 구성과 동일)
      cwd: process.cwd(),
      permissionMode: 'default',
      mcpServers,
      canUseTool: async (toolName: string) => {
        seen.push(toolName);
        console.log(`[gate] toolName=${toolName}`);
        return { behavior: 'deny' as const, message: 'spike: 모든 도구 거부' };
      },
    },
  });
  for await (const msg of stream) {
    const m = msg as { type: string; message?: { content?: unknown } };
    if (m.type === 'assistant') console.log(`[assistant] ${JSON.stringify(m.message?.content)}`);
  }
  const hit = seen.some((n) => n.startsWith('mcp__'));
  console.log(hit ? 'RESULT: 도달 — mcp__ 도구가 canUseTool을 경유한다 (스펙 §6-① 전제 성립)' : 'RESULT: 미도달 — 계획 중단, 보고');
  process.exit(hit ? 0 : 1);
}

void run();
