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

/** 질문 세션(Q&A) fresh claim — kind='QUESTION' (스펙 2026-08-30 §5). description = 질문 본문. */
export const questionClaim: InterviewClaimResponse = {
  ...freshClaim,
  sessionId: 77,
  kind: 'QUESTION',
  title: '인증 흐름',
  description: '로그인은 어디서 처리되나요?',
};

/**
 * 질문 세션 fresh claim + 등록 시 첨부(오피스 1건 = sidecar 있음, PDF 1건 = 없음) + attachmentRoot
 * (스펙 2026-09-13 §5.1). 러너는 원본 절대경로 + sidecar 경로를 프롬프트에 나열하고 Read 게이트에 root를 넘긴다.
 */
export const questionClaimWithAttachments: InterviewClaimResponse = {
  ...questionClaim,
  attachmentRoot: '/Users/micthebick/netis-maker/attachments/question-77',
  attachments: [
    {
      id: 11,
      fileName: '요구사항.docx',
      absolutePath: '/Users/micthebick/netis-maker/attachments/question-77/create/1-요구사항.docx',
      contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      sizeBytes: 45678,
      extractedTextPath: '/Users/micthebick/netis-maker/attachments/question-77/create/1-요구사항.docx.txt',
    },
    {
      id: 12,
      fileName: '화면.pdf',
      absolutePath: '/Users/micthebick/netis-maker/attachments/question-77/create/2-화면.pdf',
      contentType: 'application/pdf',
      sizeBytes: 2048,
      extractedTextPath: null,
    },
  ],
};

/**
 * 질문 세션 fresh claim + 읽기 전용 참조 repo 1개 (스펙 2026-10-22 §4.4). `dir`은 API가 claim마다 계산하는
 * `{체크아웃 루트}/_refs/{세션 id}/{카탈로그 id}` 절대경로다 — 테스트는 이 폴더를 만들지 않고 ensureRefRepos를 주입한다.
 * 아직 받은 적이 없으므로 sha는 null(첫 턴). 러너는 이 목록으로 참조를 준비해 프롬프트 접두·게이트 루트·보고에 쓴다.
 */
export const questionClaimWithRefs: InterviewClaimResponse = {
  ...questionClaim,
  refRepos: [
    {
      catalogId: 12,
      alias: '옛앱',
      githubRepo: 'acme/legacy',
      gitUrl: 'https://github.com/acme/legacy.git',
      repoHost: 'github',
      branch: 'release/6.5',
      dir: '/r/_refs/5/12',
      sha: null,
    },
  ],
};

/**
 * 작업 인터뷰(INTERVIEW) fresh claim + 읽기 전용 참조 repo 1개 (스펙 2026-10-24 §4.4·§5). 질문용 픽스처와 같은 참조지만
 * `dir`은 이 세션(42)의 참조 폴더다. 러너는 이 목록으로 참조를 준비해 매 턴 프롬프트 접두와 보고(`refStates`)에 쓴다.
 */
export const interviewClaimWithRefs: InterviewClaimResponse = {
  ...freshClaim,
  kind: 'INTERVIEW',
  refRepos: [
    {
      catalogId: 12,
      alias: '옛앱',
      githubRepo: 'acme/legacy',
      gitUrl: 'https://github.com/acme/legacy.git',
      repoHost: 'github',
      branch: 'release/6.5',
      dir: '/r/_refs/42/12',
      sha: null,
    },
  ],
};
