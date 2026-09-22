import type { JavaApiClient } from '../api/javaClient.js';
import type { SdkMessage, SdkQuery } from '../sdk/sdkAdapter.js';
import type { ActivityInput, AttachmentRef, InterviewClaimResponse, RateLimitInfo } from '../types.js';
import { ActivityPoster } from './activityPoster.js';
import { RateLimitReporter } from './rateLimitReport.js';
import { buildOptions } from '../sdk/sessionOptions.js';
import { QuotaGuardExceeded, CostGuard } from './costGuard.js';
import { tryHarvest } from './planHarvest.js';
import { relay } from './messageRelay.js';
import { ensureRepo as defaultEnsureRepo, type RepoInput } from './repoPrepare.js';
import { maskSecrets, type GitTokens } from '../sdk/gitRemote.js';
import { buildWritingPlansSplice, buildPlanReformatSplice, detectHandoff, detectPlanIntent } from './skillDispatch.js';
import { HeartbeatTicker } from './heartbeat.js';

export interface RunnerDeps {
  superpowersPluginPath: string;
  /** Resolved claude CLI binary for subscription auth (Phase-0 spike 00b). */
  claudeCliPath: string;
  /** SHADOW per-session quota guard (turns/quota, NOT dollars). */
  quotaGuard: number;
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
 * kickoff 첨부 섹션 (스펙 2026-08-16 §7). 구버전 백엔드 claim에는 attachments 필드가
 * 없으므로 무조건 Array.isArray 가드 (sessionOptions.toMcpServers 선례). 0건이면 빈 문자열.
 * contentType null은 표기 생략 — 리터럴 'null'을 렌더링하지 않는다.
 */
function attachmentSection(claim: InterviewClaimResponse): string {
  const atts: AttachmentRef[] = Array.isArray(claim.attachments) ? claim.attachments : [];
  if (atts.length === 0) return '';
  const lines = atts.map((a) => {
    const meta = [a.contentType, formatSize(a.sizeBytes)].filter(Boolean).join(', ');
    return `- ${a.absolutePath}${meta ? ` (${meta})` : ''}`;
  });
  return (
    '첨부 자료 (요청자가 등록 시 업로드한 파일):\n' +
    lines.join('\n') +
    '\n\n' +
    'brainstorming 시작 전에 이 파일들을 Read 도구로 읽고 요구사항 파악에 활용하세요. ' +
    '읽을 수 없는 포맷(docx/xlsx 등 오피스 문서)이거나 파일이 없으면 건너뛰고, ' +
    '필요한 내용은 사용자에게 질문으로 확인하세요.\n\n'
  );
}

/** One async-iterable user prompt for the turn. Fresh => kickoff text; resume => the injected answer. */
async function* promptFor(claim: InterviewClaimResponse): AsyncIterable<UserTurn> {
  if (!claim.claudeSessionId) {
    yield userTurn(
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
    yield userTurn(claim.lastAnswer ?? '');
  }
}

/**
 * 질문 세션 첨부 섹션 (스펙 2026-09-13 §6). 줄 형식 `- {fileName}: {absolutePath}` + Tika sidecar가 있으면
 * ` (텍스트 추출본: {extractedTextPath} — 이 경로를 Read하세요)`. 구버전 Java claim/turn에는 attachments
 * 필드가 없으므로 Array.isArray 가드 (attachmentSection 선례). 0건이면 빈 문자열.
 * INTERVIEW의 attachmentSection과는 별개 — 인터뷰 킥오프 문구(brainstorming 전 읽기)를 섞지 않는다.
 */
function questionAttachmentSection(atts: AttachmentRef[] | undefined, intro: string): string {
  const list: AttachmentRef[] = Array.isArray(atts) ? atts : [];
  if (list.length === 0) return '';
  const lines = list.map((a) => {
    const sidecar = typeof a.extractedTextPath === 'string' && a.extractedTextPath.length > 0
      ? ` (텍스트 추출본: ${a.extractedTextPath} — 이 경로를 Read하세요)`
      : '';
    return `- ${a.fileName}: ${a.absolutePath}${sidecar}`;
  });
  return (
    `${intro}\n` +
    lines.join('\n') +
    '\n\n' +
    'Read 도구로 위 파일을 읽고 답변에 반영하세요. 읽을 수 없는 포맷이면 건너뛰고 사용자에게 확인하세요.\n\n'
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
 * 질문 세션(Q&A) 프롬프트 — 스킬 언급 없음. fresh = Q&A 전용 계약 + 질문 본문 + (등록 시 첨부) + 읽기 전용 규칙
 * (스펙 §6-④), resume = 후속 질문(lastAnswer) + (그 메시지의 첨부) 주입. 허용 Bash 목록은 permissions.ts
 * BASH_WHITELIST와 동일하게 유지할 것.
 */
async function* questionPromptFor(claim: InterviewClaimResponse): AsyncIterable<UserTurn> {
  if (!claim.claudeSessionId) {
    yield userTurn(
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
    yield userTurn(section ? `${claim.lastAnswer ?? ''}\n\n${section}` : (claim.lastAnswer ?? ''));
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
  /** 구독 한도 보고기 — 서비스 수명 동안 1개(404 비활성 상태가 세션을 넘어 유지된다). */
  private readonly rateLimits: RateLimitReporter;
  constructor(
    private readonly client: JavaApiClient,
    private readonly query: SdkQuery,
    private readonly deps: RunnerDeps,
  ) {
    this.ensureRepo = deps.ensureRepo ?? defaultEnsureRepo;
    this.rateLimits = new RateLimitReporter(client);
  }

  async run(claim: InterviewClaimResponse): Promise<void> {
    const ticker = new HeartbeatTicker(this.client, claim.sessionId, this.deps.heartbeatIntervalMs ?? 15000);
    ticker.start();
    // 활동 스트림: 진행(도구/델타)을 300ms 배치로 중계. 실패는 poster가 격리 — 인터뷰에 무영향.
    const poster = new ActivityPoster(this.client, claim.sessionId);
    poster.start();
    // Seed the guard from the claim's accumulated session total so it is CUMULATIVE across all turns
    // (not just one runaway turn): the server accumulates total_cost_usd on every /question + /plan
    // and the claim carries it back, so a looping interview that never completes eventually trips the
    // guard → fail (safety net for the "completion never detected" loop). Fresh claims carry 0.
    const guard = new CostGuard(this.deps.quotaGuard, claim.totalCostUsd ?? 0);
    // Wall-clock 절대 백스톱 (task쪽 StaleTaskRecoveryJob hungBackstop 미러): SDK/git이 행업해도
    // HeartbeatTicker는 계속 돌므로 서버 스윕만으로는 이 워커 슬롯이 풀리지 않는다(ClaimLoop 직렬).
    // 턴 전체를 하나의 데드라인으로 묶고, 초과 시 SDK abort + FAILED 보고로 슬롯을 회수한다.
    // ??는 NaN/0을 통과시킨다 — NaN은 setTimeout에서 1ms로 클램프돼 전 턴 즉시 abort가 되므로
    // 여기서도 방어한다 (config posNum과 이중 방어).
    const rawTimeout = this.deps.turnTimeoutMs;
    const timeoutMs =
      typeof rawTimeout === 'number' && Number.isFinite(rawTimeout) && rawTimeout > 0
        ? rawTimeout
        : 1_800_000;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | null = null;
    const turn = this.runTurn(claim, controller, guard, poster);
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
      if (err instanceof QuotaGuardExceeded) {
        await this.safeFail(claim.sessionId, err.message);
        return;
      }
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

  /** 한 claim의 실제 턴 본문. 타임아웃 취소는 controller.abort()가 SDK로 전파한다. */
  private async runTurn(
    claim: InterviewClaimResponse,
    controller: AbortController,
    guard: CostGuard,
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
      // signal 전파: 타임아웃 abort가 git 자식 프로세스까지 종료해 좀비를 남기지 않는다.
      poster.push({ type: 'tool', label: '환경 준비', detail: claim.githubRepo });
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

      // 질문 세션: plan 기계장치(forceFinish 삼항식·handoff splice·near-miss reformat·harvest→postPlan)에
      // 진입하지 않는 조기 분기 — 스펙 §6 kind 가드 4곳을 한 번에 만족한다.
      if (isQuestion) {
        await this.runQuestionTurn(claim, controller, guard, poster);
        return;
      }

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
      });
      const stream: AsyncIterable<SdkMessage> = this.query({
        prompt:
          // Invariant: forceFinishTurns > 0 means at least one assistant turn has been recorded,
          // which requires a claudeSessionId (the session id is set on the FIRST assistant turn).
          // Therefore `forceFinish && !claudeSessionId` is unreachable in production; the else
          // branch (`promptFor`) only executes on the genuine fresh-start path (turns == 0).
          forceFinish && claim.claudeSessionId
            ? (async function* () { yield userTurn(buildPlanReformatSplice()); })()
            : promptFor(claim),
        options,
      });
      const result = await relay(stream, { onActivity, onRateLimit, workDir: claim.workDir });
      guard.add(result.costUsd);

      let assistantText = result.assistantText;
      let sessionId = result.sessionId ?? claim.claudeSessionId ?? '';
      let costUsd = result.costUsd;
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
            }),
          }),
          { onActivity, onRateLimit, workDir: claim.workDir },
        );
        guard.add(second.costUsd);
        assistantText = second.assistantText;
        sessionId = second.sessionId ?? sessionId;
        // 누적: 이 턴에 relay가 여러 번 돌면(핸드오프 shim) 각 relay분을 합산한다 — 대입 시
        // 1차 relay의 usage가 Java 보고에서 유실된다(스펙 §1 "모든 실행 누적").
        costUsd += second.costUsd;
        inputTokens += second.inputTokens;
        outputTokens += second.outputTokens;
        cacheCreationTokens += second.cacheCreationTokens;
        cacheReadTokens += second.cacheReadTokens;
        durationMs = second.durationMs;
        contextTokens = second.contextTokens ?? contextTokens;
        contextWindow = second.contextWindow ?? contextWindow;
      }

      let harvested = tryHarvest(assistantText);
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
            }),
          }),
          { onActivity, onRateLimit, workDir: claim.workDir },
        );
        guard.add(retry.costUsd);
        assistantText = retry.assistantText;
        sessionId = retry.sessionId ?? sessionId;
        // 누적: near-miss reformat retry도 별도 relay다 — 합산 (위 handoff shim과 동일 근거).
        costUsd += retry.costUsd;
        inputTokens += retry.inputTokens;
        outputTokens += retry.outputTokens;
        cacheCreationTokens += retry.cacheCreationTokens;
        cacheReadTokens += retry.cacheReadTokens;
        durationMs = retry.durationMs;
        contextTokens = retry.contextTokens ?? contextTokens;
        contextWindow = retry.contextWindow ?? contextWindow;
        harvested = tryHarvest(assistantText);
      }
      // trailing 배치가 question/plan보다 늦게 도착하지 않도록, 확정 POST 전에 활동 큐를 비운다.
      // (stop은 멱등 — finally의 stop은 에러 경로 안전망으로 유지)
      await poster.stop();
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
      });
    }
  }

  /**
   * 질문 세션(Q&A) 턴 — relay → postQuestion 직행. 모델이 plan 형식 텍스트를 내놔도 그냥 답변이다
   * (`postPlan` 호출 경로가 이 메서드에 없다). 옵션은 sessionKind:'QUESTION' (superpowers 미로드, default-deny).
   */
  private async runQuestionTurn(
    claim: InterviewClaimResponse,
    controller: AbortController,
    guard: CostGuard,
    poster: ActivityPoster,
  ): Promise<void> {
    const onActivity = (e: ActivityInput) => poster.push(e);
    const onRateLimit = (info: RateLimitInfo) => this.rateLimits.report(info);
    const stream: AsyncIterable<SdkMessage> = this.query({
      prompt: questionPromptFor(claim),
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
      }),
    });
    const result = await relay(stream, { onActivity, onRateLimit, workDir: claim.workDir });
    guard.add(result.costUsd);
    // trailing 활동 배치가 답변보다 늦게 도착하지 않도록 확정 POST 전에 큐를 비운다 (인터뷰 경로와 동일).
    await poster.stop();
    await this.client.postQuestion(claim.sessionId, {
      content: result.assistantText,
      claudeSessionId: result.sessionId ?? claim.claudeSessionId ?? '',
      kind: 'question',
      costUsd: result.costUsd,
      inputTokens: result.inputTokens,
      outputTokens: result.outputTokens,
      cacheCreationTokens: result.cacheCreationTokens,
      cacheReadTokens: result.cacheReadTokens,
      // 컨텍스트 스냅샷 (스펙 2026-09-05 §4.2) — 구버전 Java는 미지 필드를 무시한다.
      contextTokens: result.contextTokens,
      contextWindow: result.contextWindow,
    });
  }
}
