import { resolve } from 'node:path';
import type { SessionKind } from '../types.js';

export interface PermissionResult {
  behavior: 'allow' | 'deny';
  message?: string;
}
export type CanUseTool = (
  toolName: string,
  input: Record<string, unknown>,
) => Promise<PermissionResult>;

// Bash commands the interview may run. Read-only / inspection only. NO push, rm, curl, write.
const BASH_WHITELIST = [/^git (status|log|diff|show|branch)\b/, /^ls\b/, /^cat\b/, /^grep\b/, /^rg\b/, /^find\b/, /^head\b/, /^tail\b/, /^wc\b/, /^pwd$/];

// 셸 메타문자 — 화이트리스트 접두사로 위장한 명령 체이닝/치환/리다이렉트를 차단.
//   'git status; rm -rf /', 'git log && curl ...', 'cat x `rm y`', 'ls $(...)' 등.
// 화이트리스트는 ^앵커만 검사하므로, 메타문자를 먼저 거부하지 않으면 두 번째 명령이 통과한다.
const SHELL_METACHARS = /[;&|`$(){}<>\n\r]/;

// 질문 세션(Q&A) 허용 도구 — 이 목록 + 읽기전용 Bash + mcp__* 외에는 전부 deny (스펙 2026-08-30 §6-①).
const QUESTION_ALLOWED = new Set(['Read', 'Grep', 'Glob']);

function bashGate(input: Record<string, unknown>): PermissionResult {
  const cmd = String(input.command ?? '').trim();
  // 메타문자 우선 차단: 화이트리스트 접두사 뒤에 ; && | $() `` 등으로 임의 명령을 붙일 수 없게.
  if (SHELL_METACHARS.test(cmd)) {
    return { behavior: 'deny', message: `Bash 셸 메타문자 금지 (명령 체이닝/치환 차단): ${cmd}` };
  }
  if (BASH_WHITELIST.some((re) => re.test(cmd))) return { behavior: 'allow' };
  return { behavior: 'deny', message: `Bash not whitelisted: ${cmd}` };
}

/**
 * INTERVIEW(기본): Write/Edit/MultiEdit은 docs/superpowers/** 로 경로 제한, Bash는 화이트리스트, 나머지 allow.
 * QUESTION: default-deny — Read/Grep/Glob, 읽기전용 Bash, mcp__* 만 allow. 쓰기형·미지 도구는 전부 deny
 *   (이름 모를 미래 도구도 자동 차단). Q&A 산출물은 대화 텍스트뿐이라 Write 예외가 필요 없다.
 */
export function buildCanUseTool(repoDir: string, kind: SessionKind = 'INTERVIEW'): CanUseTool {
  if (kind === 'QUESTION') {
    return async (toolName, input) => {
      if (toolName === 'Bash') return bashGate(input);
      if (QUESTION_ALLOWED.has(toolName) || toolName.startsWith('mcp__')) return { behavior: 'allow' };
      return { behavior: 'deny', message: `질문 세션에서는 ${toolName} 도구를 사용할 수 없습니다 (읽기 전용 Q&A)` };
    };
  }
  const allowedWriteRoot = resolve(repoDir, 'docs/superpowers');
  return async (toolName, input) => {
    if (toolName === 'Write' || toolName === 'Edit' || toolName === 'MultiEdit') {
      const fp = String(input.file_path ?? '');
      const abs = resolve(repoDir, fp);
      if (abs === allowedWriteRoot || abs.startsWith(allowedWriteRoot + '/')) {
        return { behavior: 'allow' };
      }
      return { behavior: 'deny', message: 'Write confined to docs/superpowers/**' };
    }
    if (toolName === 'Bash') return bashGate(input);
    // Read/Grep/Glob/Skill and other inspection tools are allowed.
    return { behavior: 'allow' };
  };
}
