import { resolve } from 'node:path';
import { existsSync, realpathSync } from 'node:fs';
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

/**
 * repoDir(체크아웃 루트) 기준으로 p가 실제로 그 안에 있는지 검사 — QUESTION 게이트의 경로 confinement
 * 공용 헬퍼 (라운드3 bypass #3: 커밋된 symlink, 예: `link -> /etc/passwd`, 로 레포 밖을 읽는 우회 차단).
 * 존재하는 경로는 realpath로 symlink를 해소한 뒤 비교하고, 존재하지 않는 경로는 lexical로 판정한다
 * (도구가 스스로 실패하게 둔다 — 없는 파일을 realpath하면 ENOENT).
 *
 * 구현 주의(실측 확인된 반례): repoDir와 target을 무조건 함께 realpath로 승격하면 안 된다 — macOS는
 * `$TMPDIR`가 `/var/folders/… → /private/var/folders/…` 심볼릭 링크다(같은 이유로 `/tmp → /private/tmp`).
 * "존재하지 않는 파일"의 target(lexical, `/var/folders/...`)과 realpath된 base(`/private/var/folders/...`)를
 * 비교하면 같은 레포 안인데도 접두사가 달라 오탐 deny가 난다. 그래서 존재 여부로 먼저 분기해
 * lexical(둘 다 원본 경로) vs realpath(둘 다 해소된 경로)를 대칭으로 비교한다.
 * TOCTOU는 닫지 않는다 — existsSync와 realpathSync 사이에 symlink가 바뀌면 여전히 우회 가능하다.
 */
function insideRepoReal(repoDir: string, p: string): boolean {
  const abs = resolve(repoDir, p);
  if (!existsSync(abs)) {
    return abs === repoDir || abs.startsWith(repoDir + '/');
  }
  let base = repoDir;
  try {
    base = realpathSync(repoDir);
  } catch {
    /* abs가 repoDir 밑에서 존재를 확인했으므로 repoDir 자체는 반드시 존재한다 — 방어적으로만 유지. */
  }
  try {
    const target = realpathSync(abs);
    return target === base || target.startsWith(base + '/');
  } catch {
    return false;
  }
}

/**
 * 라운드2 재검토(final-review round 2)에서 실행으로 검증된 우회 6종 — 문자열 블록리스트만으로는
 * 실제 셸의 이스케이프/치환을 흉내낼 수 없다는 게 핵심 반례였다. 대신 토큰 단위 POLICY로 전환한다:
 *  1) 인자에 따옴표/백슬래시가 하나라도 있으면 통째로 거부(구조적 차단 — 셸이 이를 벗겨내며
 *     `""/etc/passwd`, `\/etc/passwd` 같은 검사 우회를 만들어내므로, "벗겨서 검사"가 아니라
 *     "있으면 거부"로 우회 표면 자체를 없앤다).
 *  2) '-'로 시작하지 않는 값 인자만 절대경로/홈/상위 탈출을 검사(포지셔널 경로 정책).
 *  3) '-'로 시작하는 플래그 인자는 경로 문자(/,~,..)를 아예 금지(퓨전 옵션 `-f/etc/passwd`,
 *     `--git-dir=/x` 류를 개별 나열 없이 구조적으로 차단).
 *  4) 명령별 위험 플래그 정확매치(+`flag=` 접두)는 여전히 필요 — `-exec`/`--output`/`--pre`처럼
 *     경로를 안 실어도 그 자체로 위험한 플래그이기 때문.
 */
const QUOTE_OR_BACKSLASH = /["'\\]/;

/** 값 인자(플래그가 아닌 토큰)가 레포 밖을 가리키는지 검사: 절대경로/홈/상위 디렉토리 탈출. */
function isPathEscapingArg(token: string): boolean {
  return (
    token.startsWith('/') ||
    token.startsWith('~') ||
    token === '..' ||
    token.startsWith('../') || // 선두 ../
    /\/\.\.\//.test(token) || // 중간 /../
    token.endsWith('/..') // 말미 /..  (예: main..HEAD 같은 리비전 범위는 여기 걸리지 않음)
  );
}

/** 플래그 토큰('-'로 시작)이 경로 문자를 포함하는지 검사 — 플래그는 경로를 실어 나를 수 없다(퓨전 옵션 차단). */
function flagCarriesPath(token: string): boolean {
  return token.includes('/') || token.includes('~') || token.includes('..');
}

/**
 * 질문 세션 전용 Bash 인자 토큰 정책 (finding #1, 라운드2/3). 화이트리스트 명령(cat/git/rg/find/ls/
 * head/tail/wc) 뒤에 붙는 각 인자를 토큰 단위로 검사한다. 명령어 자체(첫 토큰)는 여기서 검사하지 않는다
 * — 그건 questionCommandWordGate(라운드3 (a))가 별도로 정확 일치 검사한다.
 */
function questionBashArgsGate(repoDir: string, cmd: string): PermissionResult | null {
  const tokens = cmd.split(/\s+/).filter((t) => t.length > 0);
  const args = tokens.slice(1);
  // 1) 따옴표/백슬래시 우선 차단 — 셸의 이스케이프 해석에 기댄 우회(bypass #1, #2)를 구조적으로 봉쇄.
  //    읽기전용 조사 명령(cat/grep/find/git 등)은 애초에 따옴표/이스케이프가 필요 없다.
  if (args.some((t) => QUOTE_OR_BACKSLASH.test(t))) {
    return { behavior: 'deny', message: `질문 세션 Bash 인자에 따옴표/이스케이프 문자 금지: ${cmd}` };
  }
  // 2)/3) 플래그 vs 포지셔널 인자를 구분해 각각의 경로 정책 적용.
  for (const token of args) {
    if (token.startsWith('-')) {
      if (flagCarriesPath(token)) {
        return { behavior: 'deny', message: `질문 세션 Bash 플래그에 경로 금지: ${cmd}` };
      }
      continue;
    }
    if (isPathEscapingArg(token)) {
      return { behavior: 'deny', message: `질문 세션 Bash는 레포 체크아웃 안의 상대 경로만 허용합니다: ${cmd}` };
    }
    // 라운드3 (c): lexical 검사를 통과한 포지셔널 인자도 realpath로 재확인 — 커밋된 symlink(예:
    // `link -> /etc/passwd`)가 레포 밖을 가리키면 거부한다. 존재하지 않는 토큰(리비전명 `HEAD`,
    // `main..HEAD`, 아직 없는 파일명 등)은 insideRepoReal이 lexical로 판정해 통과시키고 도구 자신이
    // 실패하게 둔다. TOCTOU는 닫지 않는다(insideRepoReal 주석 참고).
    if (!insideRepoReal(repoDir, token)) {
      return { behavior: 'deny', message: `질문 세션 Bash 인자가 레포 밖을 가리킵니다(symlink 포함): ${cmd}` };
    }
  }
  return null;
}

// 명령별 위험 플래그 — 정확 토큰 매치 또는 `flag=` 접두. 경로를 안 실어도 그 자체로 위험하다.
//   git: 전역 옵션(-c/-C/--git-dir 등)은 BASH_WHITELIST 자체가 `^git (status|log|diff|show|branch)\b`로
//        서브커맨드를 맨 앞에 고정하므로 애초에 화이트리스트 정규식을 통과하지 못한다 — --output만 명시 차단.
//   rg:  --pre/--pre-glob는 전처리 명령 실행(임의 코드 실행), -z/--search-zip은 압축 해제 파이프라인.
const DANGEROUS_FLAGS: Record<string, readonly string[]> = {
  find: ['-delete', '-exec', '-execdir', '-ok', '-okdir', '-fprint', '-fprint0', '-fprintf', '-fls'],
  git: ['--output'],
  rg: ['--pre', '--pre-glob', '-z', '--search-zip'],
};

function matchesDangerousFlag(token: string, denylist: readonly string[]): boolean {
  return denylist.some((flag) => token === flag || token.startsWith(flag + '='));
}

/** 질문 세션 전용: find/git/rg 명령별 위험 플래그(쓰기·임의실행) 차단 (finding #2, bypass #5/#6). */
function questionBashDangerousFlagsGate(cmd: string): PermissionResult | null {
  const tokens = cmd.split(/\s+/).filter((t) => t.length > 0);
  const head = tokens[0];
  if (!head) return null;
  const denylist = DANGEROUS_FLAGS[head];
  if (!denylist) return null;
  if (tokens.slice(1).some((t) => matchesDangerousFlag(t, denylist))) {
    return { behavior: 'deny', message: `질문 세션 Bash 위험 플래그 금지: ${cmd}` };
  }
  return null;
}

// 라운드3 (a): 명령어 토큰 정확 일치. BASH_WHITELIST의 `^cat\b` 같은 접두사 정규식은 `\b`가
// 단어문자→비단어문자 경계에서도 성립하므로 `cat/evil`(레포에 커밋된 실행파일 경로)이나
// `git status-foo`(서브커맨드 아님) 같은 "명령어처럼 보이는 문자열"까지 통과시킨다 — Set 정확매치로 대체.
const QUESTION_BASH_COMMANDS = new Set(['git', 'ls', 'cat', 'grep', 'rg', 'find', 'head', 'tail', 'wc', 'pwd']);
const QUESTION_GIT_SUBCOMMANDS = new Set(['status', 'log', 'diff', 'show', 'branch']);

/** 질문 세션 전용: tokens[0](및 git이면 tokens[1])이 허용 목록과 정확히 일치해야 한다 (bypass #1, 라운드3). */
function questionCommandWordGate(cmd: string): PermissionResult | null {
  const deny: PermissionResult = {
    behavior: 'deny',
    message: `질문 세션 Bash 명령어가 허용 목록과 정확히 일치해야 합니다: ${cmd}`,
  };
  const tokens = cmd.split(/\s+/).filter((t) => t.length > 0);
  const head = tokens[0];
  if (!head || !QUESTION_BASH_COMMANDS.has(head)) return deny;
  if (head === 'git') {
    const sub = tokens[1];
    if (!sub || !QUESTION_GIT_SUBCOMMANDS.has(sub)) return deny;
  }
  return null;
}

/**
 * 질문 세션 Bash 게이트: (a) 명령어 토큰 정확 일치 → (기존) bashGate(화이트리스트 접두사 정규식+메타문자,
 * belt-and-braces로 유지) → 위험 플래그 → 인자 토큰 정책(따옴표/이스케이프, 플래그 경로, 포지셔널
 * 경로 탈출 + realpath/symlink confinement) 순으로 검사.
 */
function questionBashGate(repoDir: string, input: Record<string, unknown>): PermissionResult {
  const cmd = String(input.command ?? '').trim();
  const commandWordDeny = questionCommandWordGate(cmd);
  if (commandWordDeny) return commandWordDeny;
  const base = bashGate(input);
  if (base.behavior === 'deny') return base;
  return questionBashDangerousFlagsGate(cmd) ?? questionBashArgsGate(repoDir, cmd) ?? base;
}

/** 질문 세션 Read 게이트: file_path 필수 + `~` 금지(라운드3 (b)) + repoDir 안쪽 실제 경로(symlink 포함)만 허용. */
function questionReadGate(repoDir: string, input: Record<string, unknown>): PermissionResult {
  const fp = input.file_path;
  if (typeof fp !== 'string' || fp.length === 0) {
    return { behavior: 'deny', message: '질문 세션 Read는 file_path가 필요합니다' };
  }
  if (fp.startsWith('~')) {
    return { behavior: 'deny', message: `질문 세션 경로에 ~ 사용 금지: ${fp}` };
  }
  if (!insideRepoReal(repoDir, fp)) {
    return { behavior: 'deny', message: `질문 세션 Read는 레포 체크아웃 안쪽 경로만 허용합니다: ${fp}` };
  }
  return { behavior: 'allow' };
}

/**
 * Grep/Glob 공용: input.path가 문자열로 있으면 `~` 금지(라운드3 (b)) + repoDir 안쪽 실제 경로(symlink
 * 포함)인지 검사, 없으면 allow(cwd=repoDir).
 */
function questionPathScopedGate(repoDir: string, input: Record<string, unknown>, toolName: string): PermissionResult {
  const p = input.path;
  if (typeof p !== 'string' || p.length === 0) return { behavior: 'allow' };
  if (p.startsWith('~')) {
    return { behavior: 'deny', message: `질문 세션 경로에 ~ 사용 금지: ${p}` };
  }
  if (!insideRepoReal(repoDir, p)) {
    return { behavior: 'deny', message: `질문 세션 ${toolName}은 레포 체크아웃 안쪽 경로만 허용합니다: ${p}` };
  }
  return { behavior: 'allow' };
}

/**
 * Glob 게이트: path confinement(공용, `~` 포함) + pattern이 `~`로 시작하거나(라운드3 (b)) 절대경로로
 * 시작하거나 `..`를 포함하면 거부(pattern은 glob 패턴이지 정규식이 아니므로 경로 취급 — bypass #3:
 * `../../../etc/passwd`는 `/`나 `~`로 시작하지 않지만 여전히 레포를 탈출한다).
 */
function questionGlobGate(repoDir: string, input: Record<string, unknown>): PermissionResult {
  const pathResult = questionPathScopedGate(repoDir, input, 'Glob');
  if (pathResult.behavior === 'deny') return pathResult;
  const pattern = input.pattern;
  if (typeof pattern === 'string' && pattern.startsWith('~')) {
    return { behavior: 'deny', message: `질문 세션 경로에 ~ 사용 금지: ${pattern}` };
  }
  if (typeof pattern === 'string' && (pattern.startsWith('/') || pattern.includes('..'))) {
    return { behavior: 'deny', message: `질문 세션 Glob pattern은 절대 경로 또는 상위 디렉토리 탈출을 허용하지 않습니다: ${pattern}` };
  }
  return { behavior: 'allow' };
}

/**
 * INTERVIEW(기본): Write/Edit/MultiEdit은 docs/superpowers/** 로 경로 제한, Bash는 화이트리스트, 나머지 allow.
 *   (관리자가 승인 시작하는 세션이라 Read/Grep/Glob/Bash 인자에 경로 confinement가 없다 — 그대로 유지.)
 * QUESTION: default-deny — Read/Grep/Glob는 `~` 금지 + repoDir 안쪽 실제 경로(symlink 해소 후)로
 *   confinement(Glob은 pattern의 `~`/`..`도 차단), 읽기전용 Bash는 (a) 명령어 토큰이 허용 목록과 정확히
 *   일치해야 하고(화이트리스트 접두사 정규식만으로는 `cat/evil` 같은 "명령어처럼 보이는 경로"를 못 거른다),
 *   그 위에 화이트리스트+메타문자, (b) 명령별 위험 플래그 정확매치(find -delete/-exec.../-fprint0...,
 *   git --output, rg --pre/-z...), (c) 인자 토큰 정책(따옴표·백슬래시 전면 금지 + 포지셔널 인자 경로 탈출
 *   금지 + realpath/symlink confinement + 플래그 인자 경로문자 전면 금지)을 추가 차단, mcp__* 만 allow.
 *   쓰기형·미지 도구는 전부 deny(이름 모를 미래 도구도 자동 차단). Q&A 산출물은 대화 텍스트뿐이라 Write
 *   예외가 필요 없다.
 */
export function buildCanUseTool(repoDir: string, kind: SessionKind = 'INTERVIEW'): CanUseTool {
  if (kind === 'QUESTION') {
    return async (toolName, input) => {
      if (toolName === 'Bash') return questionBashGate(repoDir, input);
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
