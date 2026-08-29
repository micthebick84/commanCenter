import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

/**
 * ~/.claude.json의 글로벌 mcpServers + projects[*].mcpServers 합집합 로더 —
 * Java WorkerMcpSupport(디자인/구현 워커의 --mcp-config 합본)와 동일한 병합 규칙.
 * 인터뷰 세션에도 같은 베이스 MCP를 주입하기 위해 부팅 시 1회 호출한다.
 *
 * - 동일 이름 충돌 시 마지막 prevails (보통 프로젝트 스코프가 글로벌 override)
 * - 파일 없음/파싱 실패는 {} 반환 — 워커 init()처럼 MCP 없이 진행, 부팅 실패 금지
 * - 서버 config는 verbatim 전달 (stdio {command,args,env} / http·sse {type,url} 모두 SDK 지원)
 */
export function loadBaseMcpServers(
  claudeJsonPath: string = join(homedir(), '.claude.json'),
): Record<string, unknown> {
  let root: unknown;
  try {
    root = JSON.parse(readFileSync(claudeJsonPath, 'utf8'));
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn(`[mcpBase] ${claudeJsonPath} 읽기 실패 (MCP 없이 진행): ${(e as Error).message}`);
    return {};
  }
  if (typeof root !== 'object' || root === null) return {};

  const merged: Record<string, unknown> = {};
  const copyServers = (servers: unknown): void => {
    if (typeof servers !== 'object' || servers === null || Array.isArray(servers)) return;
    for (const [name, cfg] of Object.entries(servers)) {
      if (typeof cfg === 'object' && cfg !== null) merged[name] = cfg;
    }
  };

  const r = root as Record<string, unknown>;
  copyServers(r.mcpServers);
  const projects = r.projects;
  if (typeof projects === 'object' && projects !== null && !Array.isArray(projects)) {
    for (const proj of Object.values(projects)) {
      if (typeof proj === 'object' && proj !== null) {
        copyServers((proj as Record<string, unknown>).mcpServers);
      }
    }
  }
  return merged;
}
