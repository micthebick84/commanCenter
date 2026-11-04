import type { InterviewClaimResponse } from '../types.js';
import { PLAN_FORMAT_RULE } from './skillDispatch.js';

/**
 * 인터뷰 "진행 방식 절" — superpowers 켬/끔(스펙 2026-11-03 간단 인터뷰 §5). kickoff에서 진행 방식만 바뀌는 문구를 한 곳에 둔다:
 *  - `planningOnlyParagraph`: PLANNING-ONLY 문단의 `(via brainstorming → writing-plans)` 괄호
 *  - `processLine`          : kickoff 마지막 줄(켬 = brainstorming 지시, 끔 = 간단 인터뷰 지시)
 *  - `SIMPLE_RESUME_PREFIX` : 끔 세션의 resume(답변·계획 수정 요청) 프롬프트 접두
 * 타입 절(`kickoffByType.ts`의 첫 문장·계획 내용 요구)과 첨부 안내는 이 파일이 아니라 각자의 모듈이 끔 인자를 받는다.
 *
 * 켬은 바이트까지 그대로다(`interviewRunner.test.ts`의 KICKOFF_GOLDEN·KICKOFF_FULL_GOLDEN이 가드).
 */

/**
 * 끔인가 — **`claim.superpowersEnabled === false`일 때만** 끔이다(스펙 Global Constraints "끔 판정").
 * 칸이 없거나(구버전 API) null이거나 true면 켬이다 — `refsCleared === true` 선례와 같은 엄격 비교.
 */
export function superpowersOn(claim: Pick<InterviewClaimResponse, 'superpowersEnabled'>): boolean {
  return claim.superpowersEnabled !== false;
}

/**
 * kickoff의 PLANNING-ONLY 금지 문단 — 끝에 빈 줄(`\n\n`)을 포함한다. 끔은 켬 문단에서 진행 방식 괄호 ` (via brainstorming → writing-plans)`만
 * 뺀 것이라, 계획만 쓰고 구현하지 않는다는 금지 사항은 두 경우 모두 같다.
 */
export function planningOnlyParagraph(superpowers: boolean): string {
  return (
    'IMPORTANT — this is a PLANNING-ONLY interview. Your only deliverable is a written ' +
    'implementation PLAN' +
    (superpowers ? ' (via brainstorming → writing-plans)' : '') +
    ', NOT code. Do NOT implement the ' +
    'feature: do not create or modify source files, do not run builds/installs/tests, do not ' +
    'git commit or push. Reading the repo for context is fine. Stop once the plan is written — ' +
    'a human reviews and approves it, and implementation happens later in a separate step.\n\n'
  );
}

/** 간단 인터뷰(끔)의 진행 방식 지시 — 줄 사이 `\n`, 끝 줄바꿈 없음. 본문(채팅)에 계획을 쓰게 하는 것은 끔 resume 턴이 plan 파일로 판정하지 않기 때문이다. */
const SIMPLE_PROCESS =
  '간단 인터뷰입니다 — superpowers 스킬은 쓰지 않습니다. 먼저 등록 설명·첨부·레포 코드·(연결돼 있으면) DB를 읽기 도구로 살펴보세요.\n' +
  '할 일과 범위가 분명하면 질문하지 말고 바로 위 정규 형식의 계획을 이 대화 본문에 쓰세요(파일로 저장하지 마세요).\n' +
  "꼭 사람이 정해야 하는 것이 있을 때만 그 질문들을 한 메시지에 번호를 붙여 모두 담아 한 번만 물으세요. 추측으로 정할 수 있는 것은 묻지 말고 계획에 '가정'으로 적으세요.\n" +
  '질문과 계획을 한 메시지에 섞지 마세요 — 질문할 때는 질문만, 계획을 낼 때는 계획만 씁니다.';

/** kickoff의 마지막 줄 — 켬은 brainstorming 지시(예전 문구 그대로), 끔은 간단 인터뷰 지시. 끝 줄바꿈 없음. */
export function processLine(superpowers: boolean): string {
  return superpowers
    ? 'Use the brainstorming skill: read the project context, then ask me one clarifying question at a time.'
    : SIMPLE_PROCESS;
}

/**
 * 끔 세션의 resume 접두 — 답변 앞에 붙는다(참조 절 뒤). 이 답변(또는 계획 수정 요청)으로 질문은 끝이라 더 묻지 않고 정규 형식의 최종 계획을 내게 한다.
 * 계획 형식은 `buildPlanReformatSplice`와 같은 문구(`PLAN_FORMAT_RULE`)다. 끝에 빈 줄(`\n\n`)을 포함한다.
 */
export const SIMPLE_RESUME_PREFIX =
  '이 답변(또는 계획 수정 요청)으로 질문은 끝입니다. 더 묻지 말고 이 내용을 반영해 정규 형식의 최종 계획을 내세요. ' +
  PLAN_FORMAT_RULE +
  '\n\n';
