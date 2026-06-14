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

// writing-plans header convention: "# <Feature> Implementation Plan"
const PLAN_HEADER = /^#\s+.*Implementation Plan\s*$/m;
const TASK_LINE = /^###\s+Task\s+(\d+):\s*(.+?)\s*$/gm;

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
