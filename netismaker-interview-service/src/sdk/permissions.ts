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

function bashGate(input: Record<string, unknown>): PermissionResult {
  const cmd = String(input.command ?? '').trim();
  // 메타문자 우선 차단: 화이트리스트 접두사 뒤에 ; && | $() `` 등으로 임의 명령을 붙일 수 없게.
  if (SHELL_METACHARS.test(cmd)) {
    return { behavior: 'deny', message: `Bash 셸 메타문자 금지 (명령 체이닝/치환 차단): ${cmd}` };
  }
  if (BASH_WHITELIST.some((re) => re.test(cmd))) return { behavior: 'allow' };
  return { behavior: 'deny', message: `Bash not whitelisted: ${cmd}` };
}

// ---- QUESTION 게이트 전용 경로/인자 confinement (스펙 §6-①, 최종 리뷰 finding #1/#2) ----

/** 절대경로 p가 repoDir(체크아웃 루트) 안에 있는지 검사 — QUESTION 게이트의 경로 confinement 공용 헬퍼. */
function insideRepo(repoDir: string, p: string): boolean {
  const abs = resolve(repoDir, p);
  return abs === repoDir || abs.startsWith(repoDir + '/');
}

/** 토큰 양끝의 홑/겹따옴표 한 겹을 벗겨낸다 ("../../.env" → ../../.env). */
function stripQuotes(token: string): string {
  if (token.length >= 2) {
    const first = token.charAt(0);
    const last = token.charAt(token.length - 1);
    if ((first === '"' && last === '"') || (first === "'" && last === "'")) {
      return token.slice(1, -1);
    }
  }
  return token;
}

/**
 * 질문 세션 전용 Bash 인자 게이트 (finding #1). 화이트리스트 명령(cat/git/rg/find/ls/head/tail/wc)
 * 뒤에 절대경로·홈(~)·상위 디렉토리 탈출 인자를 붙여 레포 밖(.env, ~/.ssh, /etc/passwd 등)을 읽는
 * 것을 막는다. 명령어 자체(첫 토큰)는 검사하지 않는다.
 */
function questionBashArgsGate(cmd: string): PermissionResult | null {
  const tokens = cmd.split(/\s+/).filter((t) => t.length > 0).map(stripQuotes);
  for (const token of tokens.slice(1)) {
    if (
      token.startsWith('/') ||
      token.startsWith('~') ||
      token === '..' ||
      token.startsWith('../') ||
      token.endsWith('/..') ||
      token.includes('/../')
    ) {
      return { behavior: 'deny', message: `질문 세션 Bash는 레포 체크아웃 안의 상대 경로만 허용합니다: ${cmd}` };
    }
  }
  return null;
}

// find/git 화이트리스트에 숨어있는 파일 쓰기 플래그 (finding #2).
const FIND_WRITE_FLAG = /^-(delete|exec|execdir|ok|okdir|fprint|fprintf|fls)$/;
const GIT_OUTPUT_FLAG = /^--output(=.*)?$/;

/**
 * 질문 세션 전용: `find -delete/-exec/-execdir/-ok/-okdir/-fprint/-fprintf/-fls`,
 * `git log|diff|show --output[=FILE]` 등 레포 밖에 파일을 쓰거나 실행할 수 있는 플래그를 차단한다.
 */
function questionBashWriteFlagsGate(cmd: string): PermissionResult | null {
  const tokens = cmd.split(/\s+/).filter((t) => t.length > 0);
  const head = tokens[0];
  if (head === 'find' && tokens.some((t) => FIND_WRITE_FLAG.test(t))) {
    return { behavior: 'deny', message: `질문 세션 Bash 쓰기 플래그 금지: ${cmd}` };
  }
  if (head === 'git' && tokens.some((t) => GIT_OUTPUT_FLAG.test(t))) {
    return { behavior: 'deny', message: `질문 세션 Bash 쓰기 플래그 금지: ${cmd}` };
  }
  return null;
}

/** 질문 세션 Bash 게이트: 기존 bashGate(화이트리스트+메타문자) 통과 후 쓰기 플래그·경로 탈출 인자를 추가 검사. */
function questionBashGate(input: Record<string, unknown>): PermissionResult {
  const base = bashGate(input);
  if (base.behavior === 'deny') return base;
  const cmd = String(input.command ?? '').trim();
  return questionBashWriteFlagsGate(cmd) ?? questionBashArgsGate(cmd) ?? base;
}

/** 질문 세션 Read 게이트: file_path 필수 + repoDir 안쪽 경로만 허용. */
function questionReadGate(repoDir: string, input: Record<string, unknown>): PermissionResult {
  const fp = input.file_path;
  if (typeof fp !== 'string' || fp.length === 0) {
    return { behavior: 'deny', message: '질문 세션 Read는 file_path가 필요합니다' };
  }
  if (!insideRepo(repoDir, fp)) {
    return { behavior: 'deny', message: `질문 세션 Read는 레포 체크아웃 안쪽 경로만 허용합니다: ${fp}` };
  }
  return { behavior: 'allow' };
}

/** Grep/Glob 공용: input.path가 문자열로 있으면 repoDir 안쪽인지 검사, 없으면 allow(cwd=repoDir). */
function questionPathScopedGate(repoDir: string, input: Record<string, unknown>, toolName: string): PermissionResult {
  const p = input.path;
  if (typeof p !== 'string' || p.length === 0) return { behavior: 'allow' };
  if (!insideRepo(repoDir, p)) {
    return { behavior: 'deny', message: `질문 세션 ${toolName}은 레포 체크아웃 안쪽 경로만 허용합니다: ${p}` };
  }
  return { behavior: 'allow' };
}

/** Glob 게이트: path confinement(공용) + pattern이 절대/홈 경로로 시작하면 거부(pattern은 glob 패턴이지 정규식이 아니므로 경로 취급). */
function questionGlobGate(repoDir: string, input: Record<string, unknown>): PermissionResult {
  const pathResult = questionPathScopedGate(repoDir, input, 'Glob');
  if (pathResult.behavior === 'deny') return pathResult;
  const pattern = input.pattern;
  if (typeof pattern === 'string' && (pattern.startsWith('/') || pattern.startsWith('~'))) {
    return { behavior: 'deny', message: `질문 세션 Glob pattern은 절대/홈 경로를 허용하지 않습니다: ${pattern}` };
  }
  return { behavior: 'allow' };
}

/**
 * INTERVIEW(기본): Write/Edit/MultiEdit은 docs/superpowers/** 로 경로 제한, Bash는 화이트리스트, 나머지 allow.
 *   (관리자가 승인 시작하는 세션이라 Read/Grep/Glob/Bash 인자에 경로 confinement가 없다 — 그대로 유지.)
 * QUESTION: default-deny — Read/Grep/Glob는 repoDir 안쪽 경로로 confinement, 읽기전용 Bash는 화이트리스트에
 *   더해 쓰기 플래그(find -delete/-exec.../-fprint... , git --output)와 절대/홈/상위 탈출 인자를 추가 차단,
 *   mcp__* 만 allow. 쓰기형·미지 도구는 전부 deny(이름 모를 미래 도구도 자동 차단).
 *   Q&A 산출물은 대화 텍스트뿐이라 Write 예외가 필요 없다.
 */
export function buildCanUseTool(repoDir: string, kind: SessionKind = 'INTERVIEW'): CanUseTool {
  if (kind === 'QUESTION') {
    return async (toolName, input) => {
      if (toolName === 'Bash') return questionBashGate(input);
      if (toolName === 'Read') return questionReadGate(repoDir, input);
      if (toolName === 'Grep') return questionPathScopedGate(repoDir, input, 'Grep');
      if (toolName === 'Glob') return questionGlobGate(repoDir, input);
      if (toolName.startsWith('mcp__')) return { behavior: 'allow' };
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
