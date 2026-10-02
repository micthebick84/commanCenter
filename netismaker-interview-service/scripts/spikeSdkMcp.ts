/**
 * 스파이크(스펙 2026-10-02 §11-1) — 내장 SDK MCP 서버(type:'sdk')가 질문 세션 격리 옵션 아래 붙고,
 * 그 도구 호출이 canUseTool을 거치는지(deny면 핸들러가 안 불리는지) 실측한다. DB 없음 — 가짜 도구.
 * 실행: cd netismaker-interview-service && node --import tsx scripts/spikeSdkMcp.ts
 * 판정: 마지막 줄 RESULT: 도달(exit 0) / 미도달(exit 1). 일회성 도구.
 */
import { createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import { realQuery } from '../src/sdk/sdkAdapter.js';
import { resolveClaudeCli } from '../src/sdk/claudeCli.js';

const handled: string[] = [];
const gated: string[] = [];
const server = createSdkMcpServer({
  name: 'db-1',
  tools: [
    tool('query', '스파이크용 가짜 DB 조회', { sql: z.string() }, async ({ sql }) => {
      handled.push(sql);
      return { content: [{ type: 'text', text: '{"columns":["n"],"rows":[[42]],"rowCount":1,"truncated":false}' }] };
    }),
  ],
});

async function* prompt(): AsyncIterable<{ type: 'user'; message: { role: 'user'; content: string } }> {
  yield {
    type: 'user',
    message: {
      role: 'user',
      content: 'mcp__db-1__query 도구를 정확히 두 번 호출하세요: 먼저 sql="SELECT 42", 다음 sql="DELETE FROM t". 거부돼도 다시 시도하지 말고 결과를 한 줄로 보고하세요.',
    },
  };
}

async function run(): Promise<void> {
  const stream = realQuery({
    prompt: prompt(),
    options: {
      pathToClaudeCodeExecutable: resolveClaudeCli(process.env.CLAUDE_CLI),
      plugins: [],
      settingSources: [],
      strictMcpConfig: true,
      allowedTools: [], // QUESTION 구성과 동일 — 사전승인 없음
      cwd: process.cwd(),
      permissionMode: 'default',
      mcpServers: { 'db-1': server },
      canUseTool: async (toolName: string, input: Record<string, unknown>) => {
        gated.push(`${toolName} ${JSON.stringify(input)}`);
        const sql = String(input.sql ?? '');
        return sql.toUpperCase().startsWith('SELECT')
          ? { behavior: 'allow' as const, updatedInput: input }
          : { behavior: 'deny' as const, message: 'spike: 읽기 전용' };
      },
    },
  });
  for await (const msg of stream) {
    const m = msg as { type: string; subtype?: string; tools?: string[]; mcp_servers?: unknown };
    if (m.type === 'system' && m.subtype === 'init') {
      console.log('[init] mcp_servers=', JSON.stringify(m.mcp_servers), 'tools=', (m.tools ?? []).filter((t) => t.startsWith('mcp__')));
    }
  }
  console.log('[gate]', gated);
  console.log('[handler]', handled);
  const ok = gated.some((g) => g.startsWith('mcp__db-1__query')) && handled.includes('SELECT 42') && !handled.some((s) => s.startsWith('DELETE'));
  console.log(ok ? 'RESULT: 도달 — sdk 서버 도구가 canUseTool을 경유하고, deny는 핸들러를 막는다' : 'RESULT: 미도달 — 보고 후 중단');
  process.exit(ok ? 0 : 1);
}

void run();
