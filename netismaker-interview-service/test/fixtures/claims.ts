import type { InterviewClaimResponse } from '../../src/types.js';

export const freshClaim: InterviewClaimResponse = {
  sessionId: 42,
  githubRepo: 'acme/widgets',
  githubBranch: 'main',
  title: 'Add CSV export',
  description: 'Users want to export the dashboard table as CSV.',
  claudeSessionId: null,
  currentPhase: 'brainstorming',
  workDir: '/Users/micthebick/netis-maker/interviews/acme/widgets/session-42',
  lastAnswer: null,
  replyToSeq: null,
  mcpsExtra: null,
  turns: [],
};

export const resumeClaim: InterviewClaimResponse = {
  ...freshClaim,
  claudeSessionId: 'sess-abc-123',
  lastAnswer: 'Yes, scope it to the visible columns only.',
  replyToSeq: 3,
  turns: [
    { seq: 1, role: 'assistant', kind: 'question', content: 'Which columns?', replyToSeq: null },
    { seq: 2, role: 'user', kind: 'answer', content: 'Yes, scope it to the visible columns only.', replyToSeq: 1 },
  ],
};
