import { resolve } from 'node:path';

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

export function buildCanUseTool(repoDir: string): CanUseTool {
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
    if (toolName === 'Bash') {
      const cmd = String(input.command ?? '').trim();
      // 메타문자 우선 차단: 화이트리스트 접두사 뒤에 ; && | $() `` 등으로 임의 명령을 붙일 수 없게.
      if (SHELL_METACHARS.test(cmd)) {
        return { behavior: 'deny', message: `Bash 셸 메타문자 금지 (명령 체이닝/치환 차단): ${cmd}` };
      }
      if (BASH_WHITELIST.some((re) => re.test(cmd))) return { behavior: 'allow' };
      return { behavior: 'deny', message: `Bash not whitelisted: ${cmd}` };
    }
    // Read/Grep/Glob/Skill and other inspection tools are allowed.
    return { behavior: 'allow' };
  };
}
