import { readFileSync } from 'node:fs';

type ReadFn = (path: string, enc: 'utf8') => string;

// brainstorming checklist item 9 wording: "Transition to implementation — invoke writing-plans".
// 명령형(invoke/transition)뿐 아니라 진행형/1인칭(invoking/transitioning)도 잡는다.
const HANDOFF = /(invok(e|ing)\s+writing-plans|transition(ing)?\s+to\s+(implementation|writing-plans))/i;
// 본문 언급이 아니라 마크다운 헤딩 줄만 plan 신호로 본다. 킥오프가 "`# <기능> 구현 계획` 형식으로"를
// 요구하므로 모델이 질문 턴에서도 "마지막에 구현 계획을 작성하겠습니다"라고 따라 쓴다(2026-09-29 세션 #11).
const PLAN_HEADING = /^\s{0,3}#{1,6}\s+.*(Implementation Plan|구현\s*계획)/im;
const FENCED_CODE = /(```|~~~)[\s\S]*?(\1|$)/g;
const INLINE_CODE = /`[^`\n]*`/g;

/** True when the assistant text announces the brainstorming -> writing-plans handoff. */
export function detectHandoff(assistantText: string): boolean {
  return HANDOFF.test(assistantText);
}

/** 코드 밖 본문에 물음표가 있으면 사용자에게 묻는 턴으로 본다(`a?.b` 같은 코드 속 '?'는 제외). */
function asksUser(assistantText: string): boolean {
  return /[?？]/.test(assistantText.replace(FENCED_CODE, '').replace(INLINE_CODE, ''));
}

/**
 * 어시스턴트 텍스트가 plan을 마무리하려는 신호인가(near-miss 보정 splice 시도 여부 판단용).
 * 사용자에게 묻는 턴은 false — 보정 splice가 질문을 덮어써 사용자가 답할 기회를 잃는다. 오판의 비용이
 * 비대칭이라(놓치면 사용자 왕복 1회, 잘못 쏘면 질문 소실) 질문 쪽으로 기운다.
 */
export function detectPlanIntent(assistantText: string): boolean {
  if (asksUser(assistantText)) return false;
  return HANDOFF.test(assistantText) || PLAN_HEADING.test(assistantText);
}

/** 지정 정규 형식으로 plan을 한 번에 확정 제시하라는 프롬프트(near-miss 보정 / force-finish 공용). */
export function buildPlanReformatSplice(): string {
  return (
    '지금까지의 논의를 바탕으로 최종 구현 계획을 한 번에 확정해 제시하세요. ' +
    '반드시 다음 정규 형식을 지키세요(설명은 한국어): 최상위 헤딩 `# <기능> 구현 계획`, ' +
    '그 아래 각 작업을 `### 작업 N: <제목>` 형식으로 나열. ' +
    '영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용됩니다. ' +
    '이 구조라야 시스템이 완료를 인식합니다. 구현/빌드/커밋은 하지 마세요(plan 문서만).'
  );
}

/**
 * Fallback when the 'Skill' tool did NOT auto-fire writing-plans (spec §9 shim):
 * read writing-plans SKILL.md and return a user prompt that splices it into the SAME session.
 */
export function buildWritingPlansSplice(
  superpowersPluginPath: string,
  readFn: ReadFn = readFileSync as ReadFn,
): string {
  const skillPath = `${superpowersPluginPath}/skills/writing-plans/SKILL.md`;
  const skill = readFn(skillPath, 'utf8');
  return (
    'Now follow the writing-plans skill to turn the approved spec into an implementation plan. ' +
    'Produce the PLAN DOCUMENT ONLY — do NOT implement it: no source edits, no builds/installs/tests, ' +
    'no git commit or push. The plan is reviewed and approved by a human before any implementation. ' +
    'The plan document MUST use a top-level markdown heading that ends with the exact English words ' +
    '"Implementation Plan" (e.g. "# <Feature> Implementation Plan"), even if our conversation is in ' +
    'Korean — the system detects completion by this exact marker. ' +
    '최종 plan은 `# <기능> 구현 계획` H1 + 각 작업을 `### 작업 N: <제목>` 형식으로(설명은 한국어). ' +
    '영문 `# <Feature> Implementation Plan` / `### Task N:` 도 허용. 이 구조라야 시스템이 완료를 인식한다. ' +
    'Apply this skill exactly:\n\n' +
    skill
  );
}
