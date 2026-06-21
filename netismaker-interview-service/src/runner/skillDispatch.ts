import { readFileSync } from 'node:fs';

type ReadFn = (path: string, enc: 'utf8') => string;

// brainstorming checklist item 9 wording: "Transition to implementation — invoke writing-plans".
// 명령형(invoke/transition)뿐 아니라 진행형/1인칭(invoking/transitioning)도 잡는다.
const HANDOFF = /(invok(e|ing)\s+writing-plans|transition(ing)?\s+to\s+(implementation|writing-plans))/i;
const PLAN_INTENT = /(Implementation Plan|구현\s*계획)/i;

/** True when the assistant text announces the brainstorming -> writing-plans handoff. */
export function detectHandoff(assistantText: string): boolean {
  return HANDOFF.test(assistantText);
}

/** 어시스턴트 텍스트가 plan을 마무리하려는 신호인가(보정 splice 시도 여부 판단용). */
export function detectPlanIntent(assistantText: string): boolean {
  return HANDOFF.test(assistantText) || PLAN_INTENT.test(assistantText);
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
