import type { InterviewClaimResponse } from '../../src/types.js';

export const freshClaim: InterviewClaimResponse = {
  sessionId: 42,
  githubRepo: 'acme/widgets',
  githubBranch: 'main',
  title: 'Add CSV export',
  description: 'Users want to export the dashboard table as CSV.',
  claudeSessionId: null,
  // 갓 claim된(아직 질문 전) 세션은 Java에서 currentPhase=null (recordQuestion 전).
  currentPhase: null,
  workDir: '/Users/micthebick/netis-maker/interviews/acme/widgets/session-42',
  lastAnswer: null,
  replyToSeq: null,
  // Java InterviewClaimResponse.of는 null이 아니라 빈 배열을 반환한다.
  mcpsExtra: [],
  turns: [],
  model: 'claude-opus-4-8',
  effort: 'high',
  // 신규 claim은 누적 비용 0. 누적 가드 시드 검증은 테스트에서 override.
  totalCostUsd: 0,
  attachments: [],
};

export const resumeClaim: InterviewClaimResponse = {
  ...freshClaim,
  claudeSessionId: 'sess-abc-123',
  // resume claim은 직전에 질문이 기록됐으므로 phase는 brainstorming.
  currentPhase: 'brainstorming',
  lastAnswer: 'Yes, scope it to the visible columns only.',
  replyToSeq: 3,
  turns: [
    { seq: 1, role: 'assistant', kind: 'question', content: 'Which columns?', replyToSeq: null },
    { seq: 2, role: 'user', kind: 'answer', content: 'Yes, scope it to the visible columns only.', replyToSeq: 1 },
  ],
};

export const freshClaimWithAttachments: InterviewClaimResponse = {
  ...freshClaim,
  attachments: [
    {
      id: 1,
      fileName: '요구사항.pdf',
      absolutePath: '/Users/micthebick/netis-maker/attachments/task-7/1-요구사항.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1234567,
    },
    {
      id: 2,
      fileName: '화면시안.png',
      absolutePath: '/Users/micthebick/netis-maker/attachments/task-7/2-화면시안.png',
      contentType: null,
      sizeBytes: 2048,
    },
  ],
};
