export class HarvestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'HarvestError';
  }
}

export interface Harvest {
  designMarkdown: string;
  planMarkdown: string;
  planJson: Array<{ task: number; title: string }>;
}

// writing-plans header convention: "# <Feature> Implementation Plan" 또는 한국어 "# <기능> 구현 계획" (H1/H2)
const PLAN_HEADER = /^#{1,2}\s+.*(Implementation Plan|구현\s*계획)\s*$/m;
const TASK_LINE = /^###\s+(?:Task|작업|태스크)\s+(\d+)\s*[:：]\s*(.+?)\s*$/gm;

/** Splits the final transcript into design (before the plan header) and the plan, and indexes tasks. */
export function harvestPlan(transcript: string): Harvest {
  const match = PLAN_HEADER.exec(transcript);
  if (!match || match.index === undefined) {
    throw new HarvestError('no "Implementation Plan" header found in transcript');
  }
  const designMarkdown = transcript.slice(0, match.index).trim();
  const planMarkdown = transcript.slice(match.index).trim();

  const planJson: Array<{ task: number; title: string }> = [];
  let m: RegExpExecArray | null;
  TASK_LINE.lastIndex = 0;
  while ((m = TASK_LINE.exec(planMarkdown)) !== null) {
    planJson.push({ task: Number(m[1]), title: m[2] ?? '' });
  }
  if (planJson.length === 0) {
    throw new HarvestError('plan contains zero "### Task N:" entries');
  }
  return { designMarkdown, planMarkdown, planJson };
}

export type HarvestResult = { ok: true; harvest: Harvest } | { ok: false };

/** harvestPlan을 try/catch로 감싼다. HarvestError는 ok:false로 흡수, 그 외 에러는 재던짐. */
export function tryHarvest(transcript: string): HarvestResult {
  try {
    return { ok: true, harvest: harvestPlan(transcript) };
  } catch (e) {
    if (e instanceof HarvestError) return { ok: false };
    throw e;
  }
}
