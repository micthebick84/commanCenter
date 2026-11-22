import {
  createSdkMcpServer,
  tool,
  type McpSdkServerConfigWithInstance,
  type SdkMcpToolDefinition,
} from '@anthropic-ai/claude-agent-sdk';
import { z } from 'zod';
import type { QuestionSuggestionInput, SuggestCandidates } from '../types.js';
import { textResult } from './dbMcp.js';

/**
 * 질문 세션 내장 제안 MCP. Claude가 답변 중 "DB 연결이 필요하다/다른 레포를 함께 보면 좋겠다"를 남기는 도구 두 개 —
 * 메모리에만 모으고(붙이는 일은 하지 않는다), 러너가 턴 끝에 답변 보고 바디로 보낸다. 붙이는 곳은 API의 사용자 확인 경로뿐이다.
 * dbMcp.ts·mockupMcp.ts와 같은 모양(SDK 인프로세스 서버, 예외 대신 isError 결과)이다.
 */

export const SUGGEST_SERVER = 'suggest';
export const SUGGEST_DB_TOOL = 'suggest_db';
export const SUGGEST_REF_TOOL = 'suggest_ref_repo';
export const SUGGEST_REASON_MAX = 200;
export const SUGGEST_TARGETS_MAX = 5;
export const SUGGEST_TARGET_CHARS = 80;
export const SUGGEST_REF_MAX_PER_TURN = 2;

const OK_TEXT = '제안을 남겼습니다 — 사용자가 연결하면 다음 답변부터 쓸 수 있습니다. 지금은 코드로 확인한 범위에서 답을 마무리하세요.';
const NO_REASON = '이유를 적어 주세요';

const DB_DESCRIPTION =
  '답이 실제 DB의 구조나 저장된 값에 달려 있는데 지금 연결된 DB로는 볼 수 없을 때, 사용자에게 DB 연결을 제안합니다. ' +
  'reason에 무엇을 확인하려는지, targets에 보고 싶은 테이블 이름을, 후보 중 알맞은 것이 있으면 connectionId를 적습니다. 한 답변에 한 번만 부르세요.';
const REF_DESCRIPTION =
  '이 레포의 코드에서 다른 레포 코드에 답이 달려 있다는 근거(호출 대상·공유 모듈 등)를 확인했을 때, 사용자에게 그 레포를 함께 보도록 제안합니다. ' +
  '목록에 있는 repoCatalogId만 쓸 수 있고 reason에 근거를 적습니다. 한 답변에 최대 2개입니다.';

/** 코드 포인트 기준으로 자른다 — 이모지 같은 서로게이트 쌍이 반으로 갈라지지 않게. */
const cut = (s: string, max: number): string => Array.from(s).slice(0, max).join('');

export interface PreparedSuggestMcp {
  servers: Record<string, McpSdkServerConfigWithInstance>;
  /** 도구 정의 — 테스트·진단용(핸들러 직접 호출). */
  tools: SdkMcpToolDefinition[];
  /** 이 턴에 받은 제안의 사본(받은 순서). */
  collected(): QuestionSuggestionInput[];
}

const hasAny = <T>(list: T[] | undefined): list is T[] => Array.isArray(list) && list.length > 0;

/** 후보가 하나도 없으면(null·undefined·두 목록 모두 빔) null — 호출자는 서버·절·바디 키를 모두 생략한다. */
export function createSuggestMcp(candidates: SuggestCandidates | null | undefined): PreparedSuggestMcp | null {
  const dbCandidates = hasAny(candidates?.dbCandidates) ? candidates!.dbCandidates! : [];
  const refCandidates = hasAny(candidates?.refCandidates) ? candidates!.refCandidates! : [];
  if (dbCandidates.length === 0 && refCandidates.length === 0) return null;

  const items: QuestionSuggestionInput[] = [];
  let dbDone = false;

  const defs: unknown[] = [];
  if (dbCandidates.length > 0) {
    defs.push(
      tool(
        SUGGEST_DB_TOOL,
        DB_DESCRIPTION,
        {
          reason: z.string().min(1),
          targets: z.array(z.string()).optional(),
          connectionId: z.number().int().optional(),
        },
        async ({ reason, targets, connectionId }) => {
          const r = cut(reason.trim(), SUGGEST_REASON_MAX);
          if (r === '') return textResult(NO_REASON, true);
          if (dbDone) return textResult('이번 답변에서는 이미 DB 연결을 제안했습니다', true);
          if (connectionId !== undefined && !dbCandidates.some((c) => c.id === connectionId)) {
            return textResult(`후보에 없는 연결입니다: ${connectionId}`, true);
          }
          const t = (targets ?? [])
            .map((x) => cut(x.trim(), SUGGEST_TARGET_CHARS))
            .filter((x) => x !== '')
            .slice(0, SUGGEST_TARGETS_MAX);
          dbDone = true;
          items.push({ kind: 'DB', reason: r, targets: t, ...(connectionId !== undefined ? { dbConnectionId: connectionId } : {}) });
          return textResult(OK_TEXT);
        },
        { alwaysLoad: true },
      ),
    );
  }
  if (refCandidates.length > 0) {
    defs.push(
      tool(
        SUGGEST_REF_TOOL,
        REF_DESCRIPTION,
        { repoCatalogId: z.number().int(), reason: z.string().min(1) },
        async ({ repoCatalogId, reason }) => {
          const r = cut(reason.trim(), SUGGEST_REASON_MAX);
          if (r === '') return textResult(NO_REASON, true);
          if (!refCandidates.some((c) => c.catalogId === repoCatalogId)) {
            return textResult(`후보에 없는 레포입니다: ${repoCatalogId}`, true);
          }
          const given = items.filter((x) => x.kind === 'REF_REPO');
          if (given.some((x) => x.repoCatalogId === repoCatalogId)) return textResult('이미 제안한 레포입니다', true);
          if (given.length >= SUGGEST_REF_MAX_PER_TURN) {
            return textResult(`이번 답변에서 제안할 수 있는 레포는 ${SUGGEST_REF_MAX_PER_TURN}개까지입니다`, true);
          }
          items.push({ kind: 'REF_REPO', reason: r, repoCatalogId });
          return textResult(OK_TEXT);
        },
        { alwaysLoad: true },
      ),
    );
  }
  const tools = defs as unknown as SdkMcpToolDefinition[]; // 도구 입력 스키마 타입 때문에 공통 배열 타입으로 맞춘다(dbMcp.ts와 같다)

  return {
    servers: { [SUGGEST_SERVER]: createSdkMcpServer({ name: SUGGEST_SERVER, version: '1.0.0', tools }) },
    tools,
    // 깊은 사본 — 돌려받은 쪽이 고쳐도 수집분은 그대로.
    collected: () => items.map((x) => (x.kind === 'DB' && x.targets ? { ...x, targets: [...x.targets] } : { ...x })),
  };
}

/** 제안 절(끝에 빈 줄 포함). 후보가 없으면 ''. 연결 이름·종류와 레포 별칭·키워드만 싣는다(접속정보·description 없음). */
export function suggestSection(candidates: SuggestCandidates | null | undefined): string {
  const dbs = hasAny(candidates?.dbCandidates) ? candidates!.dbCandidates! : [];
  const refs = hasAny(candidates?.refCandidates) ? candidates!.refCandidates! : [];
  if (dbs.length === 0 && refs.length === 0) return '';
  const lines = [
    '[제안 도구]',
    '코드를 읽다가 아래 경우에 해당하면 도구로 제안을 남길 수 있습니다. 제안은 사용자에게 카드로 보이고, 사용자가 연결해야 다음 답변부터 쓸 수 있습니다.',
  ];
  if (dbs.length > 0) {
    lines.push(
      '- suggest_db: 답이 실제 DB의 구조나 저장된 값에 달려 있고, 지금 연결된 DB로는 볼 수 없을 때만 부릅니다. reason에 무엇을 확인하려는지, targets에 보고 싶은 테이블 이름을 적습니다.',
    );
  }
  if (refs.length > 0) {
    lines.push(
      '- suggest_ref_repo: 답이 아래 목록의 다른 레포 코드에 달려 있다는 근거(호출 대상·공유 모듈 등)를 이 레포의 코드에서 확인했을 때만 부릅니다.',
    );
  }
  lines.push(
    '- 추측으로 부르지 않습니다. 한 답변에 종류마다 한 번이면 충분합니다.',
    '- 제안을 남겨도 코드로 확인한 답은 이 답변에서 끝까지 쓰고, 확인하지 못한 부분이 무엇인지 한 문장으로 밝힙니다.',
  );
  for (const d of dbs) lines.push(`연결할 수 있는 DB: ${d.id} · ${d.name} · ${d.dbType}`);
  for (const r of refs) lines.push(`함께 볼 수 있는 레포: ${r.catalogId} · ${r.alias}${r.hint ? ` · ${r.hint}` : ''}`);
  return lines.join('\n') + '\n\n';
}
