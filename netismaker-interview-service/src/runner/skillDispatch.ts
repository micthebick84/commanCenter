import { readFileSync } from 'node:fs';

type ReadFn = (path: string, enc: 'utf8') => string;

// brainstorming checklist item 9 wording: "Transition to implementation — invoke writing-plans".
// 명령형(invoke/transition)뿐 아니라 진행형/1인칭(invoking/transitioning)도 잡는다.
const HANDOFF = /(invok(e|ing)\s+writing-plans|transition(ing)?\s+to\s+(implementation|writing-plans))/i;

/** True when the assistant text announces the brainstorming -> writing-plans handoff. */
export function detectHandoff(assistantText: string): boolean {
  return HANDOFF.test(assistantText);
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
    'Apply this skill exactly:\n\n' +
    skill
  );
}
