import type { JavaApiClient } from '../api/javaClient.js';
import type { SdkMessage, SdkQuery } from '../sdk/sdkAdapter.js';
import type { ActivityInput, AttachmentRef, InterviewClaimResponse, RateLimitInfo, RefRepoClaim, RefState } from '../types.js';
import { ActivityPoster } from './activityPoster.js';
import { RateLimitReporter } from './rateLimitReport.js';
import { buildOptions } from '../sdk/sessionOptions.js';
import { SessionCostMeter } from './sessionCostMeter.js';
import { withTurnInputGate } from './turnInputGate.js';
import { tryHarvest, type HarvestResult } from './planHarvest.js';
import { harvestSessionPlanFiles } from './planFiles.js';
import { relay } from './messageRelay.js';
import {
  ensureRepo as defaultEnsureRepo,
  ensureRefRepos as defaultEnsureRefRepos,
  DEFAULT_REF_TIMEOUT_MS,
  REF_PREPARE_FALLBACK_REASON,
  type RefPrepared,
  type RepoInput,
} from './repoPrepare.js';
import { maskSecrets, type GitTokens } from '../sdk/gitRemote.js';
import { buildWritingPlansSplice, buildPlanReformatSplice, detectHandoff, detectPlanIntent } from './skillDispatch.js';
import { HeartbeatTicker } from './heartbeat.js';
import { createDbMcp, type PreparedDbMcp } from '../sdk/dbMcp.js';
import { MAX_TIMER_MS } from '../config.js';

export interface RunnerDeps {
  superpowersPluginPath: string;
  /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
  claudeCliPath: string;
  /** 세션 최대 assistant 질문 턴(초과 시 FAILED). */
  maxTurns: number;
  /** 이 턴 수 이상이면 force-finish 프롬프트 사용. */
  forceFinishTurns: number;
  /**
   * ~/.claude.json 글로벌+프로젝트 MCP 합본 스냅샷 (부팅 시 loadBaseMcpServers 1회) —
   * 디자인/구현 워커와 동일한 베이스 MCP를 인터뷰 세션에도 주입. 미지정이면 extras만.
   */
  mcpsBase?: Record<string, unknown>;
  /** Injectable repo prepare (defaults to the real git clone/fetch). */
  ensureRepo?: (input: RepoInput) => Promise<void>;
  /**
   * 테스트용 주입 — 기본 repoPrepare.ensureRefRepos (질문·작업 인터뷰의 읽기 전용 참조 repo 준비, 스펙 2026-10-22 §5.1,
   * 2026-10-24 §5.1). 참조 하나가 실패해도(abort·시간 초과 포함) 던지지 않고 `{ error }`로 돌려주는 계약이다.
   * `timeoutMs`는 참조 하나당 받기 상한이다.
   */
  ensureRefRepos?: (
    refs: RefRepoClaim[],
    opts: { tokens?: GitTokens; signal?: AbortSignal; timeoutMs?: number },
  ) => Promise<RefPrepared[]>;
  /**
   * 참조 repo 하나당 받기 상한(ms, 스펙 2026-10-24 §5.1). 넘으면 그 참조만 시간 초과로 끝나고 턴은 계속된다.
   * 기본 5분 — turnTimeoutMs처럼 유한한 양수만 받는다(NaN·0은 기본값).
   */
  refTimeoutMs?: number;
  /** Injectable SKILL.md reader for the writing-plans splice fallback (defaults to real fs read). */
  spliceRead?: (path: string, enc: 'utf8') => string;
  heartbeatIntervalMs?: number;
  /**
   * 한 claim(턴 전체: clone + SDK 최대 3회)의 wall-clock 상한(ms). 초과 시 SDK abort +
   * 세션 FAILED 보고로 워커 슬롯을 회수한다 — heartbeat가 살아있는 행업의 유일한 로컬 백스톱.
   * 기본 30분.
   */
  turnTimeoutMs?: number;
  /** clone/fetch 인증 토큰(GITHUB_PAT / GITLAB_TOKEN). 미지정이면 익명. */
  gitTokens?: GitTokens;
  /** 테스트용 주입 — 기본 dbMcp.createDbMcp (질문 세션 내장 DB 도구, 스펙 2026-10-02 §6). */
  createDbMcp?: (claim: InterviewClaimResponse) => PreparedDbMcp;
  /** 테스트용 주입 — 기본 planFiles.harvestSessionPlanFiles (채팅 추출 실패 시 세션 plan 파일로 대체 판정). */
  harvestPlanFiles?: (workDir: string) => Promise<HarvestResult>;
}

/**
 * SDK stream-json user message. The claude CLI (>=2.1) `--input-format stream-json`
 * parser requires `message:{role,content}` and throws on the legacy `{type,text}` shape
 * (`undefined is not an object (evaluating '_.message.role')` → process exits 1).
 */
type UserTurn = { type: 'user'; message: { role: 'user'; content: string } };
function userTurn(content: string): UserTurn {
  return { type: 'user', message: { role: 'user', content } };
}

function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '';
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`;
  return `${bytes}B`;
}

/**
 * 첨부 한 줄 뒤에 붙는 들여 쓴 하위 줄 (스펙 2026-10-27 §7.3) — INTERVIEW·QUESTION 두 섹션이 공용.
 * 구버전 API claim에는 새 필드가 없으므로 타입별 가드로 걸러 하위 줄을 만들지 않는다.
 */
function attachmentDetailLines(a: AttachmentRef): string[] {
  const lines: string[] = [];
  if (typeof a.extractedTextPath === 'string' && a.extractedTextPath.length > 0) {
    lines.push(`  - 텍스트 추출본: ${a.extractedTextPath}`);
  }
  const slides = Array.isArray(a.slideImagePaths) ? a.slideImagePaths.filter((x) => typeof x === 'string' && x.length > 0) : [];
  if (slides.length > 0) {
    const truncated = typeof a.slideTotalPages === 'number' && a.slideTotalPages > slides.length
      ? ` (전체 ${a.slideTotalPages}장 중 ${slides.length}장만 그림 — 나머지는 텍스트 추출본 참고)`
      : '';
    lines.push(`  - 슬라이드 그림(${slides.length}장, 순서대로): ${slides.join(', ')}${truncated}`);
  }
  const media = Array.isArray(a.mediaImagePaths) ? a.mediaImagePaths.filter((x) => typeof x === 'string' && x.length > 0) : [];
  if (media.length > 0) {
    lines.push(`  - 원본 그림(작은 글씨를 크게 볼 때): ${media.join(', ')}`);
  }
  if (typeof a.renderNote === 'string' && a.renderNote.length > 0) {
    lines.push(`  - ${a.renderNote}`);
  }
  return lines;
}

/** 질문에서 넘어온 첨부면 첨부 줄 끝에 붙는 표시. */
function inheritedMarker(a: AttachmentRef): string {
  return typeof a.inheritedFromQuestionId === 'number' ? ` (질문 #${a.inheritedFromQuestionId}에서 넘어옴)` : '';
}

/**
 * kickoff 첨부 섹션 (스펙 2026-08-16 §7). 구버전 백엔드 claim에는 attachments 필드가
 * 없으므로 무조건 Array.isArray 가드 (sessionOptions.toMcpServers 선례). 0건이면 빈 문자열.
 * contentType null은 표기 생략 — 리터럴 'null'을 렌더링하지 않는다.
 */
function attachmentSection(claim: InterviewClaimResponse): string {
  const atts: AttachmentRef[] = Array.isArray(claim.attachments) ? claim.attachments : [];
  if (atts.length === 0) return '';
  const lines = atts.map((a) => {
    const meta = [a.contentType, formatSize(a.sizeBytes)].filter(Boolean).join(', ');
    return [`- ${a.absolutePath}${meta ? ` (${meta})` : ''}${inheritedMarker(a)}`, ...attachmentDetailLines(a)].join('\n');
  });
  return (
    '첨부 자료 (요청자가 등록 시 업로드한 파일):\n' +
    lines.join('\n') +
    '\n\n' +
    'brainstorming 시작 전에 이 파일들을 Read 도구로 읽고 요구사항 파악에 활용하세요. ' +
    '오피스 원본(pptx/docx/xlsx 등)은 Read로 열 수 없습니다. ' +
    '슬라이드 그림이 있으면 모두 순서대로 Read해서 화면 레이아웃과 빨간 표시·화살표 같은 주석이 가리키는 변경점을 파악하세요. ' +
    '글은 텍스트 추출본으로 읽고, 작은 글씨는 원본 그림으로 확인하세요. ' +
    '그림이 없는 첨부는 추출본만 읽고, 파일이 없으면 건너뛰고 필요한 내용은 사용자에게 질문으로 확인하세요. ' +
    '계획에는 슬라이드 번호와 함께 변경점을 적으세요(예: "슬라이드 1: 상단 카드 2개 추가").\n\n'
  );
}

/**
 * One async-iterable user prompt for the turn. Fresh => kickoff text; resume => the injected answer.
 * 접두는 둘로 나뉜다 (스펙 2026-10-24 §5.2): `everyTurn`(참조 절 — 인터뷰 중 변경·실패를 알리려 매 턴 다시 싣는다)은 kickoff·resume
 * 모두 맨 앞에, `kickoffOnly`(DB 절)는 kickoff에만 붙는다 — DB가 붙은 인터뷰의 resume 프롬프트가 지금과 같도록.
 * 둘 다 비면 프롬프트는 지금과 바이트까지 같다.
 */
async function* promptFor(
  claim: InterviewClaimResponse,
  prefixes: { everyTurn?: string; kickoffOnly?: string } = {},
): AsyncIterable<UserTurn> {
  const everyTurn = prefixes.everyTurn ?? '';
  if (!claim.claudeSessionId) {
    yield userTurn(
      everyTurn +
      (prefixes.kickoffOnly ?? '') +
      `I want to add a feature to the repo at ${claim.githubRepo} (branch ${claim.githubBranch}).\n` +
        `Title: ${claim.title}\nRequest: ${claim.description}\n\n` +
        attachmentSection(claim) +
        'IMPORTANT — this is a PLANNING-ONLY interview. Your only deliverable is a written ' +
        'implementation PLAN (via brainstorming → writing-plans), NOT code. Do NOT implement the ' +
        'feature: do not create or modify source files, do not run builds/installs/tests, do not ' +
        'git commit or push. Reading the repo for context is fine. Stop once the plan is written — ' +
        'a human reviews and approves it, and implementation happens later in a separate step.\n\n' +
        '최종 plan은 반드시 다음 정규 형식으로 작성하세요(설명은 한국어): ' +
        '최상위 헤딩 `# <기능> 구현 계획`, 그 아래 각 작업을 `### 작업 N: <제목>` 형식으로. ' +
        '영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용됩니다. ' +
        '이 정확한 구조라야 시스템이 완료를 인식하며, 없으면 인터뷰가 끝나지 않습니다.\n\n' +
        'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.',
    );
  } else {
    yield userTurn(everyTurn + (claim.lastAnswer ?? ''));
  }
}

/**
 * 질문 세션 첨부 섹션 (스펙 2026-09-13 §6). 줄 형식 `- {fileName}: {absolutePath}` + 하위 줄(`attachmentDetailLines` —
 * 텍스트 추출본·슬라이드 그림·원본 그림·renderNote, 스펙 2026-10-27 §7.3). 구버전 Java claim/turn에는 attachments
 * 필드가 없으므로 Array.isArray 가드 (attachmentSection 선례). 0건이면 빈 문자열.
 * INTERVIEW의 attachmentSection과는 별개 — 인터뷰 킥오프 문구(brainstorming 전 읽기)를 섞지 않는다.
 */
function questionAttachmentSection(atts: AttachmentRef[] | undefined, intro: string): string {
  const list: AttachmentRef[] = Array.isArray(atts) ? atts : [];
  if (list.length === 0) return '';
  const lines = list.map((a) => {
    return [`- ${a.fileName}: ${a.absolutePath}${inheritedMarker(a)}`, ...attachmentDetailLines(a)].join('\n');
  });
  return (
    `${intro}\n` +
    lines.join('\n') +
    '\n\n' +
    'Read 도구로 위 파일을 읽고 답변에 반영하세요. 오피스 원본은 Read로 열 수 없으니 슬라이드 그림과 텍스트 추출본을 읽고, 그림이 없어 모르는 부분은 사용자에게 확인하세요.\n\n'
  );
}

/** claim.turns에서 마지막 `role=user, kind=answer` 턴의 첨부 (resume 시 "이 메시지" 첨부). 없으면 undefined. */
function lastAnswerAttachments(claim: InterviewClaimResponse): AttachmentRef[] | undefined {
  const turns = Array.isArray(claim.turns) ? claim.turns : [];
  for (let i = turns.length - 1; i >= 0; i--) {
    const t = turns[i];
    if (t && t.role === 'user' && t.kind === 'answer') return t.attachments;
  }
  return undefined;
}

/**
 * DB 도구 안내 (스펙 2026-10-02 §6.4). 서버마다 도구 이름이 같으므로(query 등) 서버↔DB 매핑을 매 턴 알려 준다.
 * 연결도 안내도 없으면 빈 문자열 — 프롬프트가 기존과 같다.
 */
export function dbSection(db: Pick<PreparedDbMcp, 'labels' | 'notices'>): string {
  if (db.labels.length === 0 && db.notices.length === 0) return '';
  const lines: string[] = [];
  if (db.labels.length > 0) {
    lines.push('사용 가능한 DB 도구 (읽기 전용 — SELECT 계열 단일 문장, 최대 200행):');
    for (const l of db.labels) lines.push(`- mcp__${l.serverName}__query / list_tables / describe_table : ${l.label}`);
  }
  for (const n of db.notices) lines.push(`참고: ${n}`);
  return lines.join('\n') + '\n\n';
}

/** 못 받은 참조의 사유 요약 상한(글자). */
const REF_REASON_MAX = 200;
/** Node execFile 오류 메시지의 첫 줄 접두(`Command failed: <명령줄>`) — 명령줄과 로컬 경로뿐이라 사유로 쓰지 않는다. */
const EXEC_FAILED_PREFIX = 'Command failed:';
/** git 자신의 진단 줄(`fatal: …`, `error: …`). */
const GIT_DIAGNOSTIC = /^(?:fatal|error):/;

/**
 * 못 받은 참조의 실패 사유를 한 줄로 줄인다 — 프롬프트의 "사용할 수 없는 참조" 줄과 보고 `refStates[].error`(칩 툴팁)가 같은 값을 쓴다.
 * ensureRefRepos가 돌려주는 전문은 Node execFile의 오류 모양이라 `Command failed: <명령줄>\n<stderr>`다. 첫 줄은 명령줄(로컬 경로 포함)이고
 * 진짜 원인(`fatal: Remote branch … not found`, `Could not resolve host` 등)은 stderr 쪽에 있다(스펙 §5.2 예시의 "브랜치를 찾을 수 없음"에 해당).
 * 그래서 가린 문구를 줄로 나눠 (a) `Command failed:` 줄을 버리고, (b) git의 마지막 `fatal:`/`error:` 줄이 있으면 그 줄을, (c) 없으면 남은 줄 중
 * 마지막 비어 있지 않은 줄을 고른다. (d) 다듬어 200자 이내로 자르고 (e) 아무것도 안 남으면 고정 문구(REF_PREPARE_FALLBACK_REASON).
 * 전체 문구는 로그에만 남는다(logRefFailures). 가린 뒤에 고르고 자르는 이유: 자르다가 토큰이 반쪽이 되면 maskSecrets가 알아보지 못한다.
 */
function refReason(error: string): string {
  const lines = maskSecrets(error)
    .split(/\r\n|\r|\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith(EXEC_FAILED_PREFIX));
  const picked = lines.slice().reverse().find((line) => GIT_DIAGNOSTIC.test(line)) ?? lines[lines.length - 1];
  if (!picked) return REF_PREPARE_FALLBACK_REASON;
  let cut = picked.slice(0, REF_REASON_MAX);
  // 서로게이트 쌍의 앞 절반에서 잘렸으면 그 반쪽을 버린다 — 깨진 문자가 JSON·DB로 나가지 않게.
  if (/[\uD800-\uDBFF]$/.test(cut)) cut = cut.slice(0, -1);
  return cut.trim() || REF_PREPARE_FALLBACK_REASON;
}

/** 질문 세션 참조 절의 규칙 (스펙 2026-10-22 §5.2) — 질문 게이트는 Read/Grep/Glob만 참조 폴더까지 허용한다. */
const QUESTION_REF_RULES: readonly string[] = [
  '- 대상 repo는 현재 작업 디렉토리다. 참조 repo는 위 절대 경로를 Read/Grep/Glob의 경로로 주어 읽는다(셸 명령은 대상 안에서만 동작한다).',
  '- 참조 파일 근거는 `별칭:경로:줄`(예: `옛앱:src/a/B.java:12`), 대상 파일은 `경로:줄`로 적는다.',
  '- 사용할 수 없는 참조의 내용은 추측하지 말고 못 읽었다고 답한다.',
];

/**
 * 작업 인터뷰 참조 절의 규칙 (스펙 2026-10-24 §5.2) — 계획·설계 문서에 남는 참조 표기와 구현으로 넘어가는 참조의 범위를 정한다.
 * 인터뷰 게이트는 Read/Grep/Glob을 사전 승인하므로 경로를 어떻게 주라는 말은 없다.
 */
const INTERVIEW_REF_RULES: readonly string[] = [
  '- 대상 repo는 현재 작업 디렉토리다. 참조 repo는 위 절대 경로에서 읽기만 한다.',
  '- 참조 폴더의 파일은 만들거나 고치거나 지우지 않는다(셸 명령 포함).',
  '- 위 목록에 없는 참조(앞 턴에 있던 것 포함)는 더 쓰지 않는다. 구현에는 이 목록의 참조만 넘어간다.',
  '- 설계·계획 문서에는 참조 파일을 절대 경로가 아니라 `별칭:경로:줄`(예: `옛앱:src/a/B.java:12`)로 적는다. 구현 단계에서는 참조 폴더 위치가 다르다.',
  '- 사용할 수 없는 참조의 내용은 추측하지 않는다.',
];

/**
 * 참조 절 — 이번 턴 ensureRefRepos 결과로 만든다. 질문·작업 인터뷰가 머리말·참조 줄·사용 불가 줄을 함께 쓰고 규칙만 다르다.
 * 사용 가능한 참조는 `별칭 · 브랜치@커밋 앞 7자 : 절대경로`, 못 받은 참조는 참조마다 한 줄(`별칭(브랜치) — 한 줄 사유`).
 * 빈 배열이면 빈 문자열이라 참조 없는 세션의 프롬프트는 지금과 같다. 사용 가능한 참조가 없으면 머리말과 `- …` 줄을,
 * 실패가 없으면 "사용할 수 없는 참조" 줄을 뺀다. 끝에 빈 줄 하나(dbSection과 같은 모양).
 */
function buildRefSection(prepared: RefPrepared[], rules: readonly string[]): string {
  if (prepared.length === 0) return '';
  const lines: string[] = [];
  const usable = prepared.filter((p) => p.error === null);
  if (usable.length > 0) {
    lines.push('참조 repo (읽기 전용 — 비교·참고용, 수정 금지):');
    for (const p of usable) {
      const commit = p.sha ? `@${p.sha.slice(0, 7)}` : '';
      lines.push(`- ${p.ref.alias} · ${p.ref.branch}${commit} : ${p.ref.dir}`);
    }
  }
  for (const p of prepared) {
    if (p.error !== null) lines.push(`사용할 수 없는 참조: ${p.ref.alias}(${p.ref.branch}) — ${refReason(p.error)}`);
  }
  lines.push('규칙:', ...rules);
  return lines.join('\n') + '\n\n';
}

/**
 * 질문 세션 참조 repo 안내 (스펙 2026-10-22 §5.2). 새 질문·추가 질문 모두 매 턴 접두로 붙는다
 * (대화 중 참조를 바꾸거나 한 참조가 못 받아질 수 있으니 resume 턴에도 현재 상태를 다시 알려 준다).
 */
export function refSection(prepared: RefPrepared[]): string {
  return buildRefSection(prepared, QUESTION_REF_RULES);
}

/** 작업 인터뷰 참조 repo 안내 (스펙 2026-10-24 §5.2) — 질문용과 규칙만 다르다. kickoff·답변 턴 모두 매 턴 맨 앞에 붙는다. */
export function interviewRefSection(prepared: RefPrepared[]): string {
  return buildRefSection(prepared, INTERVIEW_REF_RULES);
}

/**
 * 작업 인터뷰에서 참조를 대화 중 모두 뺐을 때(claim.refsCleared) 참조 절 대신 매 턴 싣는 한 줄 (스펙 2026-10-24 §5.2) —
 * 앞 턴에 읽은 참조를 계획에 계속 끌어오지 않게 한다.
 */
export const REFS_CLEARED_LINE =
  '참조 repo: 없음(대화 중 모두 뺐다) — 앞서 본 참조는 더 쓰지 않는다. 계획은 대상 repo만으로 세운다.\n\n';

/**
 * 작업 인터뷰 프롬프트의 매 턴 접두 — 이번 턴에 준비한 참조가 있으면 참조 절, 없고 대화 중 모두 뺐으면 그 한 줄, 그 밖에는 ''
 * (참조를 고른 적 없는 인터뷰의 프롬프트는 바이트까지 그대로).
 */
export function interviewRefPrefix(prepared: RefPrepared[], refsCleared: boolean): string {
  if (prepared.length > 0) return interviewRefSection(prepared);
  return refsCleared ? REFS_CLEARED_LINE : '';
}

/**
 * claim의 참조 repo 목록 (스펙 2026-10-22 §4.4, 2026-10-24 §4.4·§5.1) — 질문·작업 인터뷰 공통이다. 보낼지는 API(refsFor)가 정하므로
 * kind로 거르지 않는다. 구버전 백엔드 claim에는 필드가 없다 → Array.isArray 가드(dbConnections 선례).
 */
function claimRefRepos(claim: InterviewClaimResponse): RefRepoClaim[] {
  return Array.isArray(claim.refRepos) ? claim.refRepos : [];
}

/**
 * 참조 준비 결과 → 답변 보고용 상태 (스펙 2026-10-22 §4.5, 입력 순서 그대로). 성공은 `{ sha, error: null }`,
 * 실패는 `{ sha: null, error }` — error는 프롬프트와 같은 한 줄 요약이다(API는 그대로 저장해 칩 툴팁에 보인다).
 */
function refStatesOf(prepared: RefPrepared[]): RefState[] {
  return prepared.map((p) => ({
    catalogId: p.ref.catalogId,
    branch: p.ref.branch,
    sha: p.sha,
    error: p.error === null ? null : refReason(p.error),
  }));
}

/**
 * 못 받은 참조는 세션을 실패시키지 않으므로(스펙 §1.2) 운영자가 원인을 볼 곳은 로그뿐이다 — 한 줄 요약이 아니라 전체 문구를 남긴다.
 * repoPrepare가 이미 가린 문구지만 주입된 준비기까지 믿지 않고 한 번 더 가린다(safeFail과 같은 이중 방어).
 */
function logRefFailures(sessionId: number, prepared: RefPrepared[]): void {
  for (const p of prepared) {
    if (p.error === null) continue;
    // eslint-disable-next-line no-console
    console.warn(
      `[refRepos] 참조 준비 실패: session=${sessionId} ${p.ref.alias}(${p.ref.branch}) catalog=${p.ref.catalogId} — ${maskSecrets(p.error)}`,
    );
  }
}

/**
 * 질문 세션(Q&A) 프롬프트 — 스킬 언급 없음. fresh = Q&A 전용 계약 + 질문 본문 + (등록 시 첨부) + 읽기 전용 규칙
 * (스펙 §6-④), resume = 후속 질문(lastAnswer) + (그 메시지의 첨부) 주입. 허용 Bash 목록은 permissions.ts
 * BASH_WHITELIST와 동일하게 유지할 것.
 */
async function* questionPromptFor(claim: InterviewClaimResponse, prefix = ''): AsyncIterable<UserTurn> {
  if (!claim.claudeSessionId) {
    yield userTurn(
      prefix +
        `당신은 \`${claim.githubRepo}\` (브랜치 ${claim.githubBranch}) 레포에 대한 질문에 답하는 코드 분석 어시스턴트입니다. ` +
        '현재 작업 디렉토리에 이 레포가 체크아웃되어 있습니다.\n\n' +
        `제목: ${claim.title}\n질문: ${claim.description}\n\n` +
        questionAttachmentSection(claim.attachments, '질문에 첨부된 파일:') +
        '규칙:\n' +
        '- 이 세션은 질문·답변(Q&A) 전용입니다. 구현 계획 작성, 작업 등록, 코드 수정은 이 세션에서 불가능합니다. ' +
        '그런 요청을 받으면 "작업 등록(인터뷰) 기능을 이용해 주세요"라고 안내하세요.\n' +
        '- 레포는 읽기 전용입니다: 파일 생성/수정, 빌드/설치/테스트 실행, git commit/push를 하지 마세요. ' +
        'Read/Grep/Glob, 읽기 전용 셸 명령(git status/log/diff/show/branch, ls, cat, grep, rg, find, head, tail, wc, pwd), ' +
        '연결된 MCP 도구로만 조사하세요.\n' +
        '- 첨부파일은 레포 체크아웃 밖(세션 첨부 디렉토리)에 있을 수 있으며, Read 도구로만 읽을 수 있습니다 ' +
        '(Grep/Glob/셸 명령은 레포 안에서만 동작합니다).\n' +
        '- 한국어 마크다운으로 답하고, 근거는 `파일경로:라인` 형식으로 제시하세요. 확실하지 않으면 모른다고 답하세요.\n' +
        '- 스킬(Skill) 도구는 없습니다. 바로 조사하고 답하세요.',
    );
  } else {
    const section = questionAttachmentSection(lastAnswerAttachments(claim), '이 메시지에 첨부된 파일:');
    const body = section ? `${claim.lastAnswer ?? ''}\n\n${section}` : (claim.lastAnswer ?? '');
    yield userTurn(prefix + body);
  }
}

/**
 * Runs exactly ONE turn for a claimed session, then returns. Resume-per-answer is the
 * DEFAULT loop: pending state comes from the claim payload (DB), never held in memory.
 * The repo is (re)prepared at claim.workDir before EVERY turn — fresh or resume — because
 * options.resume is cwd-pinned to that checkout (spike 02).
 */
export class InterviewRunner {
  private readonly ensureRepo: (input: RepoInput) => Promise<void>;
  private readonly ensureRefRepos: NonNullable<RunnerDeps['ensureRefRepos']>;
  /**
   * 참조 하나당 받기 상한(ms). NaN·0·음수·무한대는 기본 5분 — NaN이 타이머에 들어가면 1ms로 모든 참조가 끊긴다(turnTimeoutMs와 같은 방어).
   * 타이머 한도(`MAX_TIMER_MS`)를 넘는 값은 그 한도로 줄인다 — 넘으면 Node가 1ms로 바꿔 같은 일이 난다.
   */
  private readonly refTimeoutMs: number;
  private readonly harvestPlanFiles: (workDir: string) => Promise<HarvestResult>;
  /** 구독 한도 보고기 — 서비스 수명 동안 1개(404 비활성 상태가 세션을 넘어 유지된다). */
  private readonly rateLimits: RateLimitReporter;
  /** 모든 질의는 턴이 끝날 때까지 프롬프트 입력을 열어 둔다 — 첫 result 뒤 권한 요청 "Stream closed" 방지(turnInputGate). */
  private readonly query: SdkQuery;
  constructor(
    private readonly client: JavaApiClient,
    query: SdkQuery,
    private readonly deps: RunnerDeps,
  ) {
    this.query = withTurnInputGate(query);
    this.ensureRepo = deps.ensureRepo ?? defaultEnsureRepo;
    this.ensureRefRepos = deps.ensureRefRepos ?? defaultEnsureRefRepos;
    const refTimeout = deps.refTimeoutMs;
    this.refTimeoutMs =
      typeof refTimeout === 'number' && Number.isFinite(refTimeout) && refTimeout > 0
        ? Math.min(refTimeout, MAX_TIMER_MS)
        : DEFAULT_REF_TIMEOUT_MS;
    this.harvestPlanFiles = deps.harvestPlanFiles ?? harvestSessionPlanFiles;
    this.rateLimits = new RateLimitReporter(client);
  }

  async run(claim: InterviewClaimResponse): Promise<void> {
    const ticker = new HeartbeatTicker(this.client, claim.sessionId, this.deps.heartbeatIntervalMs ?? 15000);
    ticker.start();
    // 활동 스트림: 진행(도구/델타)을 300ms 배치로 중계. 실패는 poster가 격리 — 인터뷰에 무영향.
    const poster = new ActivityPoster(this.client, claim.sessionId);
    poster.start();
    // 턴 비용 계측: resume하는 Claude 세션의 SDK total_cost_usd는 세션 누적값이라 서버 세션 합계를 빼서 이번 턴
    // 비용만 보고한다. 새 Claude 세션(claudeSessionId 없음)은 0부터. 세션 비용 상한(구 CostGuard)은 2026-10-15에
    // 없앴다 — 끝나지 않는 인터뷰는 maxTurns와 턴 wall-clock 타임아웃이 막는다.
    const meter = new SessionCostMeter(claim.claudeSessionId ? (claim.totalCostUsd ?? 0) : 0);
    // Wall-clock 절대 백스톱 (task쪽 StaleTaskRecoveryJob hungBackstop 미러): SDK/git이 행업해도
    // HeartbeatTicker는 계속 돌므로 서버 스윕만으로는 이 워커 슬롯이 풀리지 않는다(ClaimLoop 자리 1개).
    // 턴 전체를 하나의 데드라인으로 묶고, 초과 시 SDK abort + FAILED 보고로 슬롯을 회수한다.
    // ??는 NaN/0을 통과시킨다 — NaN은 setTimeout에서 1ms로 클램프돼 전 턴 즉시 abort가 되므로
    // 여기서도 방어한다 (config posNum과 이중 방어). 타이머 한도를 넘는 값도 1ms가 되므로 한도로 줄인다.
    const rawTimeout = this.deps.turnTimeoutMs;
    const timeoutMs =
      typeof rawTimeout === 'number' && Number.isFinite(rawTimeout) && rawTimeout > 0
        ? Math.min(rawTimeout, MAX_TIMER_MS)
        : 1_800_000;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | null = null;
    const turn = this.runTurn(claim, controller, meter, poster);
    try {
      const outcome = await Promise.race([
        turn.then(() => 'done' as const),
        new Promise<'timeout'>((resolve) => {
          timer = setTimeout(() => {
            controller.abort();
            resolve('timeout');
          }, timeoutMs);
        }),
      ]);
      if (outcome === 'timeout') {
        // 낙오한 턴 promise의 지연 거부(AbortError 등)가 unhandled rejection이 되지 않게 흡수.
        void turn.catch(() => {});
        await this.safeFail(
          claim.sessionId,
          `SDK 턴 wall-clock 타임아웃(${Math.round(timeoutMs / 60_000)}분) 초과 — 행업 회수`,
        );
        return;
      }
    } catch (err) {
      // 원인만 보낸다 — 세션 종류별 라벨("답변 실패: "/"인터뷰 실패: ")은 서버 doFail이 붙이므로
      // 여기서 접두사를 얹으면 배너가 "답변 실패: 답변 생성 실패: …"처럼 겹친다. 다른 safeFail 사유들과 동일한 형태.
      await this.safeFail(claim.sessionId, (err as Error).message);
    } finally {
      if (timer) clearTimeout(timer);
      ticker.stop();
      await poster.stop();
      await this.rateLimits.flush(); // 보고 큐 드레인 — fail/success 어느 경로든 턴 밖으로 새지 않게
    }
  }

  /**
   * fail 보고 자체의 실패(네트워크/409)가 런너 밖으로 새어 ClaimLoop까지 죽이지 않게 격리한다.
   * 모든 실패 경로(타임아웃·쿼터·턴 에러·최대 턴)가 여기를 지나므로, 운영자가 interview.log만 보고도
   * 원인을 알 수 있게 stdout에도 한 줄 남긴다 — 서버 보고만 하면 로그가 조용하다(2026-09-22 라이브 검증).
   * 사유는 보고·로그 모두 maskSecrets를 거친다(repoPrepare가 이미 가린 git 오류에 대한 이중 방어).
   */
  private async safeFail(sessionId: number, reason: string): Promise<void> {
    const safe = maskSecrets(reason);
    // eslint-disable-next-line no-console
    console.warn(`[runner] 세션 실패: session=${sessionId} — ${safe}`);
    try {
      await this.client.fail(sessionId, safe);
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn(`[runner] fail 보고 실패: session=${sessionId} — ${(err as Error).message}`);
    }
  }

  /**
   * claim의 읽기 전용 참조 repo를 준비한다 — 질문·작업 인터뷰 공통 (스펙 2026-10-22 §5.1, 2026-10-24 §5.1). 대상 ensureRepo 다음,
   * DB MCP·SDK 진입 전에 부른다. 참조가 없으면 아무것도 하지 않고 []를 돌려준다(프롬프트·옵션·보고가 지금과 같다).
   * 참조를 못 받아도 턴은 실패하지 않는다(스펙 2026-10-24 §1.2) — 그 참조 없이 진행하고 사유는 프롬프트·보고·로그로 알린다.
   * 준비기가 계약을 어기고 던져도 마찬가지로 모든 참조를 못 받은 것으로 돌린다. 단 턴이 이미 중단됐으면(턴 상한) 여기서 멈춘다 —
   * 준비기는 abort를 예외가 아니라 "못 받은 참조"로 돌려주므로 직접 확인해, 끝난 턴이 SDK에 들어가거나 refStates를 보고하지 않게 한다.
   */
  private async prepareRefs(claim: InterviewClaimResponse, controller: AbortController): Promise<RefPrepared[]> {
    const refClaims = claimRefRepos(claim);
    if (refClaims.length === 0) return [];
    let refs: RefPrepared[];
    try {
      refs = await this.ensureRefRepos(refClaims, {
        tokens: this.deps.gitTokens,
        signal: controller.signal,
        timeoutMs: this.refTimeoutMs,
      });
    } catch (e) {
      if (controller.signal.aborted) throw e;
      const raw = e instanceof Error ? e.message : typeof e === 'string' ? e : '';
      const error = maskSecrets(raw).trim() || REF_PREPARE_FALLBACK_REASON;
      refs = refClaims.map((ref) => ({ ref, sha: null, error }));
    }
    controller.signal.throwIfAborted();
    logRefFailures(claim.sessionId, refs);
    return refs;
  }

  /** 한 claim의 실제 턴 본문. 타임아웃 취소는 controller.abort()가 SDK로 전파한다. */
  private async runTurn(
    claim: InterviewClaimResponse,
    controller: AbortController,
    meter: SessionCostMeter,
    poster: ActivityPoster,
  ): Promise<void> {
    const onActivity = (e: ActivityInput) => poster.push(e);
    const onRateLimit = (info: RateLimitInfo) => this.rateLimits.report(info);
    {
      // 턴 상한: claim.turns의 assistant 턴 수로 진행도 판정(백엔드 변경 불필요).
      const isQuestion = claim.kind === 'QUESTION';
      const assistantTurns = claim.turns.filter((t) => t.role === 'assistant').length;
      if (assistantTurns >= this.deps.maxTurns) {
        await this.safeFail(
          claim.sessionId,
          isQuestion
            ? `최대 문답 수(${this.deps.maxTurns}) 초과 — 새 질문 세션을 열어주세요`
            : `최대 질문 턴(${this.deps.maxTurns}) 초과 — plan 미완성`,
        );
        return;
      }
      const forceFinish = assistantTurns >= this.deps.forceFinishTurns;

      // CLONE: ensure the checkout exists at workDir before the (fresh OR resume) turn.
      // signal 전파: 타임아웃 abort가 git 자식 프로세스까지 종료한다 — Windows는 런처(cmd\git.exe) 밑의 실제 git·git-remote-http까지
      // `taskkill /T /F`로 끝낸다(repoPrepare `execFileWithTreeKill`). 런처만 끝내면 실제 clone이 뒤에서 계속 받는다.
      // claim에 참조 repo가 있으면(질문·작업 인터뷰) 대상 뒤에 ` + 참조 n`을 붙인다(받는 일은 아래 prepareRefs — 스펙 2026-10-22 §5.2).
      const refCount = claimRefRepos(claim).length;
      poster.push({
        type: 'tool',
        label: '환경 준비',
        detail: refCount > 0 ? `${claim.githubRepo} + 참조 ${refCount}` : claim.githubRepo,
      });
      await this.ensureRepo({
        githubRepo: claim.githubRepo,
        githubBranch: claim.githubBranch,
        workDir: claim.workDir,
        gitUrl: claim.gitUrl,
        repoHost: claim.repoHost,
        tokens: this.deps.gitTokens,
        signal: controller.signal,
      });
      // 좀비 턴 가드: 타임아웃으로 이미 abort된 뒤 낙오한 이 턴이 늦게 여기 도달하면
      // (signal을 무시하는 주입 ensureRepo 등) SDK에 진입하지 않고 즉시 중단한다.
      controller.signal.throwIfAborted();

      // 읽기 전용 참조 repo — 대상 다음, DB MCP·SDK 전에 차례로 받는다(질문·작업 인터뷰 공통, 참조 하나당 받기 상한).
      const refs = await this.prepareRefs(claim, controller);

      // 질문 세션: plan 기계장치(forceFinish 삼항식·handoff splice·near-miss reformat·harvest→postPlan)에
      // 진입하지 않는 조기 분기 — 스펙 §6 kind 가드 4곳을 한 번에 만족한다.
      if (isQuestion) {
        await this.runQuestionTurn(claim, controller, meter, poster, refs);
        return;
      }

      // 작업 DB MCP(스펙 2026-10-14 §5): 질문 세션과 같은 내장 서버. 턴마다 새로 만들고 finally에서 닫는다.
      const db = (this.deps.createDbMcp ?? createDbMcp)(claim);
      try {
        // 참조 폴더는 refRoots로 넘기지 않는다 — 인터뷰는 Read/Grep/Glob이 사전 승인이라 게이트·옵션이 그대로다(스펙 2026-10-24 §5.1).
        const options = buildOptions({
          superpowersPluginPath: this.deps.superpowersPluginPath,
          workDir: claim.workDir,
          claudeCliPath: this.deps.claudeCliPath,
          claudeSessionId: claim.claudeSessionId,
          mcpsExtra: claim.mcpsExtra,
          mcpsBase: this.deps.mcpsBase,
          model: claim.model,
          effort: claim.effort,
          abortController: controller,
          dbMcpServers: db.servers,
          dbDialects: db.dialects,
        });
        const stream: AsyncIterable<SdkMessage> = this.query({
          prompt:
            // Invariant: forceFinishTurns > 0 means at least one assistant turn has been recorded,
            // which requires a claudeSessionId (the session id is set on the FIRST assistant turn).
            // Therefore `forceFinish && !claudeSessionId` is unreachable in production; the else
            // branch (`promptFor`) only executes on the genuine fresh-start path (turns == 0).
            // 참조 절은 매 턴 접두(kickoff·resume), DB 절은 kickoff 전용 (스펙 2026-10-24 §5.2). 강제 마무리·handoff·near-miss
            // splice에는 참조 절을 붙이지 않는다 — 보고(refStates)는 그 경우에도 한다.
            forceFinish && claim.claudeSessionId
              ? (async function* () { yield userTurn(buildPlanReformatSplice()); })()
              : promptFor(claim, {
                  everyTurn: interviewRefPrefix(refs, claim.refsCleared === true),
                  kickoffOnly: dbSection(db),
                }),
          options,
        });
        const result = await relay(stream, { onActivity, onRateLimit, workDir: claim.workDir });

        let assistantText = result.assistantText;
        let sessionId = result.sessionId ?? claim.claudeSessionId ?? '';
        let costUsd = meter.ownCost(result.costUsd);
        let inputTokens = result.inputTokens;
        let outputTokens = result.outputTokens;
        let cacheCreationTokens = result.cacheCreationTokens;
        let cacheReadTokens = result.cacheReadTokens;
        let durationMs = result.durationMs;
        // 컨텍스트 스냅샷은 "마지막 relay" 값 — 뒤이은 handoff/reformat relay가 있으면 그 값으로 갱신(null이면 유지).
        let contextTokens = result.contextTokens;
        let contextWindow = result.contextWindow;

        // Skill-dispatch shim: if brainstorming announced the writing-plans handoff
        // but no plan was produced, splice the writing-plans SKILL.md into the SAME
        // session and run one more turn (fallback when 'Skill' did not auto-fire).
        const handoff = detectHandoff(assistantText);
        const hasPlan = tryHarvest(assistantText).ok;
        if (handoff && !hasPlan && sessionId) {
          controller.signal.throwIfAborted(); // relay 사이 gap에서 abort됐으면 SDK 재진입 금지
          const splice = buildWritingPlansSplice(this.deps.superpowersPluginPath, this.deps.spliceRead);
          const second = await relay(
            this.query({
              prompt: (async function* () {
                yield userTurn(splice);
              })(),
              options: buildOptions({
                superpowersPluginPath: this.deps.superpowersPluginPath,
                workDir: claim.workDir,
                claudeCliPath: this.deps.claudeCliPath,
                claudeSessionId: sessionId,
                mcpsExtra: claim.mcpsExtra,
                mcpsBase: this.deps.mcpsBase,
                model: claim.model,
                effort: claim.effort,
                abortController: controller,
                dbMcpServers: db.servers,
                dbDialects: db.dialects,
              }),
            }),
            { onActivity, onRateLimit, workDir: claim.workDir },
          );
          assistantText = second.assistantText;
          sessionId = second.sessionId ?? sessionId;
          // 누적: 이 턴에 relay가 여러 번 돌면(핸드오프 shim) 각 relay분을 합산한다 — 대입 시
          // 1차 relay의 usage가 Java 보고에서 유실된다(스펙 §1 "모든 실행 누적"). 비용은 같은 세션을 resume하므로
          // SDK 보고값이 누적값이라 meter로 이번 relay분만 뗀다.
          costUsd += meter.ownCost(second.costUsd);
          inputTokens += second.inputTokens;
          outputTokens += second.outputTokens;
          cacheCreationTokens += second.cacheCreationTokens;
          cacheReadTokens += second.cacheReadTokens;
          durationMs = second.durationMs;
          contextTokens = second.contextTokens ?? contextTokens;
          contextWindow = second.contextWindow ?? contextWindow;
        }

        // 채팅 본문 추출이 실패하면 이번 세션이 저장한 plan 파일로 한 번 더 판정한다 — writing-plans가
        // plan을 파일에만 쓰고 채팅엔 요약만 남기면 완료를 영영 못 알아본다(2026-10-08 세션 21).
        const harvestTurn = async (text: string): Promise<HarvestResult> => {
          const fromText = tryHarvest(text);
          return fromText.ok ? fromText : this.harvestPlanFiles(claim.workDir);
        };
        let harvested = await harvestTurn(assistantText);
        // near-miss 보정: 추출 실패 + plan 의도 신호 시, 같은 세션에 정규 형식 재요청 1회.
        // force-finish는 이미 reformat 프롬프트이므로 이중 splice 방지.
        if (!harvested.ok && !forceFinish && detectPlanIntent(assistantText) && sessionId) {
          controller.signal.throwIfAborted(); // relay 사이 gap에서 abort됐으면 SDK 재진입 금지
          const reformatSplice = buildPlanReformatSplice();
          const retry = await relay(
            this.query({
              prompt: (async function* () { yield userTurn(reformatSplice); })(),
              options: buildOptions({
                superpowersPluginPath: this.deps.superpowersPluginPath,
                workDir: claim.workDir,
                claudeCliPath: this.deps.claudeCliPath,
                claudeSessionId: sessionId,
                mcpsExtra: claim.mcpsExtra,
                mcpsBase: this.deps.mcpsBase,
                model: claim.model,
                effort: claim.effort,
                abortController: controller,
                dbMcpServers: db.servers,
                dbDialects: db.dialects,
              }),
            }),
            { onActivity, onRateLimit, workDir: claim.workDir },
          );
          assistantText = retry.assistantText;
          sessionId = retry.sessionId ?? sessionId;
          // 누적: near-miss reformat retry도 별도 relay다 — 합산 (위 handoff shim과 동일 근거).
          costUsd += meter.ownCost(retry.costUsd);
          inputTokens += retry.inputTokens;
          outputTokens += retry.outputTokens;
          cacheCreationTokens += retry.cacheCreationTokens;
          cacheReadTokens += retry.cacheReadTokens;
          durationMs = retry.durationMs;
          contextTokens = retry.contextTokens ?? contextTokens;
          contextWindow = retry.contextWindow ?? contextWindow;
          harvested = await harvestTurn(assistantText);
        }
        // trailing 배치가 question/plan보다 늦게 도착하지 않도록, 확정 POST 전에 활동 큐를 비운다.
        // (stop은 멱등 — finally의 stop은 에러 경로 안전망으로 유지)
        await poster.stop();
        // 참조 준비 결과 (스펙 2026-10-24 §5.3) — 이번 턴에 참조를 준비했을 때만 싣는다. 참조 없는 인터뷰의 바디는 지금과 같다
        // (키 자체를 생략). 턴이 실패해 fail로 끝나면 여기 닿지 않으므로 보고하지 않는다.
        const refReport = refs.length > 0 ? { refStates: refStatesOf(refs) } : {};
        if (harvested.ok) {
          // planJson is sent as a JSON STRING — Java stores it as text/JSONB; frontend parses on use.
          await this.client.postPlan(claim.sessionId, {
            designMarkdown: harvested.harvest.designMarkdown,
            planMarkdown: harvested.harvest.planMarkdown,
            planJson: JSON.stringify(harvested.harvest.planJson),
            costUsd,
            inputTokens,
            outputTokens,
            cacheCreationTokens,
            cacheReadTokens,
            durationMs,
            // 계획 턴에 처음 받은 참조도 커밋이 고정되도록 계획 보고에도 싣는다(API recordPlan → applyRefStates).
            ...refReport,
          });
          return;
        }

        await this.client.postQuestion(claim.sessionId, {
          content: assistantText,
          claudeSessionId: sessionId,
          kind: 'question',
          costUsd,
          inputTokens,
          outputTokens,
          cacheCreationTokens,
          cacheReadTokens,
          contextTokens,
          contextWindow,
          ...refReport,
        });
      } finally {
        await db.close();
      }
    }
  }

  /**
   * 질문 세션(Q&A) 턴 — relay → postQuestion 직행. 모델이 plan 형식 텍스트를 내놔도 그냥 답변이다
   * (`postPlan` 호출 경로가 이 메서드에 없다). 옵션은 sessionKind:'QUESTION' (superpowers 미로드, default-deny).
   * `refs`는 runTurn이 이번 턴에 준비한 읽기 전용 참조 repo(스펙 2026-10-22 §5.1, prepareRefs) — 참조가 없으면 빈 배열이라
   * 프롬프트·옵션·보고가 지금과 같다.
   */
  private async runQuestionTurn(
    claim: InterviewClaimResponse,
    controller: AbortController,
    meter: SessionCostMeter,
    poster: ActivityPoster,
    refs: RefPrepared[],
  ): Promise<void> {
    const onActivity = (e: ActivityInput) => poster.push(e);
    const onRateLimit = (info: RateLimitInfo) => this.rateLimits.report(info);
    // 이번 턴에 준비에 성공한 참조 폴더만 읽기 루트다 — 못 받은 참조·대화 중 빠진 참조는 게이트가 거부한다(§5.3).
    const refRoots = refs.filter((p) => p.error === null).map((p) => p.ref.dir);
    // 내장 DB MCP(스펙 2026-10-02 §6.4): 서버는 턴마다 새로 만들고(DB 접속은 첫 도구 호출 때),
    // 성공·실패와 무관하게 finally에서 열린 커넥션을 닫는다.
    const db = (this.deps.createDbMcp ?? createDbMcp)(claim);
    try {
      const stream: AsyncIterable<SdkMessage> = this.query({
        prompt: questionPromptFor(claim, refSection(refs) + dbSection(db)),
        options: buildOptions({
          superpowersPluginPath: this.deps.superpowersPluginPath,
          workDir: claim.workDir,
          claudeCliPath: this.deps.claudeCliPath,
          claudeSessionId: claim.claudeSessionId,
          mcpsExtra: claim.mcpsExtra,
          mcpsBase: this.deps.mcpsBase,
          model: claim.model,
          effort: claim.effort,
          abortController: controller,
          sessionKind: 'QUESTION',
          // 세션 첨부 디렉토리 — Read 게이트의 두 번째 허용 루트 (스펙 2026-09-13 §6). 구버전 Java는 필드 없음 → null.
          attachmentRoot: claim.attachmentRoot ?? null,
          // 이번 턴에 준비에 성공한 참조 폴더 — Read/Grep/Glob 허용 루트(스펙 2026-10-22 §5.3). Bash는 대상 안만 그대로.
          refRoots,
          dbMcpServers: db.servers,
          dbDialects: db.dialects,
        }),
      });
      const result = await relay(stream, { onActivity, onRateLimit, workDir: claim.workDir });
      const costUsd = meter.ownCost(result.costUsd);
      // trailing 활동 배치가 답변보다 늦게 도착하지 않도록 확정 POST 전에 큐를 비운다 (인터뷰 경로와 동일).
      await poster.stop();
      await this.client.postQuestion(claim.sessionId, {
        content: result.assistantText,
        claudeSessionId: result.sessionId ?? claim.claudeSessionId ?? '',
        kind: 'question',
        costUsd,
        inputTokens: result.inputTokens,
        outputTokens: result.outputTokens,
        cacheCreationTokens: result.cacheCreationTokens,
        cacheReadTokens: result.cacheReadTokens,
        // 컨텍스트 스냅샷 (스펙 2026-09-05 §4.2) — 구버전 Java는 미지 필드를 무시한다.
        contextTokens: result.contextTokens,
        contextWindow: result.contextWindow,
        // 참조 준비 결과 (스펙 2026-10-22 §4.5·§5.4) — 이번 턴에 참조를 준비했을 때만 싣는다. 참조 없는 질문의 바디는 지금과 같다
        // (키 자체를 생략). 턴이 실패해 fail로 끝나면 이 줄에 닿지 않으므로 보고하지 않는다.
        ...(refs.length > 0 ? { refStates: refStatesOf(refs) } : {}),
      });
    } finally {
      await db.close();
    }
  }
}
